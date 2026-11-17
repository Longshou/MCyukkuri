"""Offline checks for exported village templates. --self-test runs deliberate breakages."""
import argparse
from collections import Counter, deque
from dataclasses import dataclass
from functools import cached_property
from gzip import decompress
from hashlib import sha256
from itertools import product
import json
from pathlib import Path
import re
from struct import error as StructError, unpack_from
import subprocess
import sys

from check_structure import AIR, free, full_block, source_level, transparent

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
DATA = ROOT / 'src/main/resources/data'
STYLES = ('plains', 'desert', 'savanna', 'snowy', 'taiga')
POOL_NAMES = ('frontage', 'main_market', 'main_civic', 'main_end', 'town_lots')
HORIZONTAL = {'west': (-1, 0, 0), 'east': (1, 0, 0), 'north': (0, 0, -1), 'south': (0, 0, 1)}
OPPOSITE = {'west': 'east', 'east': 'west', 'north': 'south', 'south': 'north'}
ADJACENT = (*HORIZONTAL.values(), (0, 1, 0), (0, -1, 0))
VANILLA_POOLS = {'minecraft:empty', *(f'minecraft:village/{style}/streets' for style in STYLES)}
RESOURCE = re.compile(r'[a-z0-9_.-]+:[a-z0-9_./-]+')
EMPTY = 'minecraft:empty'
BIN = 'mcyukkuri:garbage_bin'
SHELTERS = {'mcyukkuri:cardboard_shelter', 'mcyukkuri:crate_shelter', 'mcyukkuri:forest_burrow'}
WORKSTATIONS = {'barrel', 'blast_furnace', 'brewing_stand', 'cartography_table', 'cauldron',
                'composter', 'fletching_table', 'grindstone', 'lectern', 'loom', 'smoker', 'smithing_table',
                'stonecutter', 'water_cauldron', 'lava_cauldron', 'powder_snow_cauldron'}
HAZARDS = {'cactus', 'sweet_berry_bush', 'magma_block', 'powder_snow', 'lava', 'fire', 'soul_fire',
           'campfire', 'soul_campfire', 'wither_rose'}
REPLACEABLE = {'short_grass', 'tall_grass', 'fern', 'large_fern', 'dead_bush', 'poppy', 'dandelion'}
AIR_STATE = (AIR, {}, {})
FIELDS = ('max_count', 'max_cut', 'max_fill')
SIDE_LOTS = 2
FILL_DEPTH = 24  # LevelledPoolElement.FILL_DEPTH


def add(pos, delta, scale=1):
    return tuple(p + scale * d for p, d in zip(pos, delta))


def village_pool(style, name):
    return f'mcyukkuri:village/{style}/{name}'


def village_distance(style):
    path = DATA / 'minecraft/worldgen/structure' / f'village_{style}.json'
    return json.loads(path.read_text(encoding='utf-8'))['max_distance_from_center']


def valid_id(value):
    return isinstance(value, str) and RESOURCE.fullmatch(value) and '..' not in value.split('/')


def read_nbt(raw):
    """Read the gzip structure format emitted by Building.save, including entity and block tags."""
    data = decompress(raw)
    offset = 0

    def number(fmt):
        nonlocal offset
        value, = unpack_from('>' + fmt, data, offset)
        offset += {'b': 1, 'h': 2, 'H': 2, 'i': 4, 'q': 8, 'f': 4, 'd': 8}[fmt]
        return value

    def value(tag):
        nonlocal offset
        if 1 <= tag <= 6:
            return number('bhiqfd'[tag - 1])
        if tag == 8:
            length = number('H')
            start, offset = offset, offset + length
            return data[start:offset].decode('utf-8')
        if tag == 9:
            kind, length = number('b'), number('i')
            return [value(kind) for _ in range(length)]
        if tag == 10:
            fields = {}
            while kind := number('b'):
                name = value(8)
                fields[name] = value(kind)
            return fields
        if tag in (7, 11, 12):
            return [number({7: 'b', 11: 'i', 12: 'q'}[tag]) for _ in range(number('i'))]
        raise ValueError(f'unknown NBT tag {tag}')

    if number('b') != 10:
        raise ValueError('structure NBT root must be a compound')
    value(8)
    result = value(10)
    if offset != len(data):
        raise ValueError('trailing bytes after the structure NBT')
    return result


@dataclass
class Piece:
    id: str
    size: tuple
    blocks: dict
    entities: list

    @classmethod
    def read(cls, id, path):
        doc = read_nbt(path.read_bytes())
        palette = [(s['Name'], s.get('Properties', {})) for s in doc['palette']]
        blocks = {tuple(row['pos']): (*palette[row['state']], row.get('nbt', {})) for row in doc['blocks']}
        return cls(id, tuple(doc['size']), blocks, doc['entities'])

    @cached_property
    def sockets(self):
        return {pos: (props.get('orientation'), nbt)
                for pos, (name, props, nbt) in self.blocks.items() if name == 'minecraft:jigsaw'}

    def state(self, pos):
        name, props, nbt = self.blocks.get(pos, AIR_STATE)
        if name == 'minecraft:jigsaw':
            # JigsawReplacementProcessor replaces connectors before the piece is played.
            final = nbt.get('final_state', '').split('[', 1)[0]
            return final or name, {}, {}
        return name, props, nbt

    def inside(self, pos):
        x, y, z = pos
        return 0 <= x < self.size[0] and 0 <= y <= self.size[1] and 0 <= z < self.size[2]


def validate_entries(authors):
    """A duplicate declaration, bad style, missing limit field or invalid weight fails before exporting."""
    entries = {}
    errors = []
    for author in authors:
        if not isinstance(author.POOL_ENTRIES, (list, tuple)):
            errors.append(f'{author.__name__}: POOL_ENTRIES must be a sequence')
            continue
        for row in author.POOL_ENTRIES:
            if (not isinstance(row, dict) or row.keys() - {'pool', 'piece', 'styles', 'weight', 'new', *FIELDS}
                    or not {'pool', 'piece', 'styles', 'weight', *FIELDS} <= row.keys()
                    or row['pool'] not in POOL_NAMES or not isinstance(row['piece'], str)
                    or not re.fullmatch(r'[a-z0-9_]+', row['piece'])
                    or type(row['weight']) is not int or not 1 <= row['weight'] <= 150
                    or type(row.get('new', False)) is not bool
                    or any(row[f] is not None and (type(row[f]) is not int or row[f] < (f == 'max_count'))
                           for f in FIELDS)):
                errors.append(f'{author.__name__}: invalid POOL_ENTRIES row {row!r}')
                continue
            styles = row['styles'] if row['styles'] is not None else STYLES
            if (not isinstance(styles, (list, tuple)) or not styles or any(s not in STYLES for s in styles)
                    or len(set(styles)) != len(styles)):
                errors.append(f'{author.__name__}: invalid styles in {row!r}')
                continue
            for style in styles:
                key = (village_pool(style, row['pool']), village_pool(style, row['piece']))
                if key in entries:
                    errors.append(f'{author.__name__}: duplicate pool entry {key}')
                entries[key] = row
    return entries, errors


def pool_checks(pools, pieces, entries, warn):
    """Reject a missing fallback, missing template, duplicate element or stale declared pool weight/limits."""
    seen = set()
    for pool, doc in pools.items():
        if doc['name'] != pool:
            warn(pool, f'pool name in JSON is {doc["name"]}')
        if doc['fallback'] not in pools and doc['fallback'] not in VANILLA_POOLS:
            warn(pool, f'dangling fallback {doc["fallback"]}')
        if pool.count('/') == 2:
            style, role = pool.split('/')[1:]
            fallback = {'frontage': f'minecraft:village/{style}/streets',
                        'main_market': village_pool(style, 'main_end'),
                        'main_civic': village_pool(style, 'main_end')}.get(role, EMPTY)
            if doc['fallback'] != fallback:
                warn(pool, f'expected fallback {fallback}')
        declared_lots = pool.endswith('/town_lots') and any(p == pool for p, _ in entries)
        if declared_lots:
            from town_pools import STYLES as POOL_STYLES
            vanilla_weight = POOL_STYLES[style][2]
            declared_weight = sum(entry['weight'] for (p, _), entry in entries.items() if p == pool)
        for row in doc['elements']:
            element = row['element']
            if type(row['weight']) is not int or not 1 <= row['weight'] <= 150:
                warn(pool, f'invalid element weight {row["weight"]}')
            if element['element_type'] == 'minecraft:empty_pool_element':
                continue
            if (element['element_type'], element.get('processors'), element.get('projection')) != (
                    'mcyukkuri:levelled', EMPTY, 'rigid'):
                warn(pool, f'expected a rigid levelled element: {element!r}')
            location = element['location']
            key = (pool, location)
            if key in seen:
                warn(pool, f'duplicate pool template {location}')
            seen.add(key)
            if location not in pieces:
                warn(pool, f'dangling template {location}')
            if key in entries:
                expected = entries[key]
                # Same integer arithmetic as town_pools: a float share would round 14.5 differently.
                weight = (max(1, round(expected['weight'] * vanilla_weight / declared_weight))
                          if declared_lots else expected['weight'])
                limits = (expected['max_count'], None, 5) if declared_lots else tuple(expected[f] for f in FIELDS)
                if row['weight'] != weight or tuple(element.get(f) for f in FIELDS) != limits:
                    warn(pool, f'{location} weight or limits differ from POOL_ENTRIES')
            elif location.count('/') == 2:
                warn(pool, f'{location} has no POOL_ENTRIES declaration for this pool')
    for pool, location in sorted(entries.keys() - seen):
        warn(pool, f'declared template {location} missing from exported pool')


def socket_checks(piece, style, roles, pools):
    """Catch moved or reversed lane sockets, a missing gate/hand-back and dangling connector pools."""
    errors = []
    incoming = []
    lanes = []
    streets = []
    for pos, (orientation, nbt) in piece.sockets.items():
        facing, _, top = (orientation or '').partition('_')
        fields = ('name', 'target', 'pool')
        if (facing not in HORIZONTAL or top != 'up' or nbt.get('id') != 'minecraft:jigsaw'
                or nbt.get('joint') not in ('aligned', 'rollable')
                or any(not valid_id(nbt.get(key)) for key in fields)
                or not valid_id(nbt.get('final_state', '').split('[', 1)[0])):
            errors.append(f'jigsaw {pos} has malformed orientation or NBT')
            continue
        name, target, pool = (nbt[key] for key in fields)
        if pool not in pools and pool not in VANILLA_POOLS:
            errors.append(f'jigsaw {pos} references dangling pool {pool}')
        x, y, z = pos
        next_pos = add(pos, HORIZONTAL[facing])
        if piece.inside(next_pos):
            errors.append(f'jigsaw {pos} {facing} does not face out of the piece')
        if y == 1 and not support(piece.state((x, 0, z))):
            errors.append(f'jigsaw {pos} has no y0 paving')
        if name == 'minecraft:building_entrance':
            incoming.append(pos)
            if y != 1 or z != 0 or facing != 'north' or target != EMPTY or pool != EMPTY:
                errors.append(f'building entrance {pos} must face north from the y1 front edge')
            lane = 'smooth_sandstone' if style == 'desert' else 'dirt_path'
            if nbt['final_state'] != AIR or piece.state((x, 0, z))[0] != 'minecraft:' + lane:
                errors.append(f'building entrance {pos} must become air above y0 {lane}')
        elif name.startswith('mcyukkuri:street_') or target.startswith('mcyukkuri:street_'):
            lanes.append(facing)
            if facing not in ('west', 'east') or (y, z) != (1, 4):
                errors.append(f'lane jigsaw {pos} {facing} is off the y1 z4 east/west edge')
            elif style:
                outgoing = piece.id.rsplit('/', 1)[-1] not in ('mouth_east', 'mouth_west')
                side = 'main_civic' if facing == 'west' else 'main_market'
                expected = (f'mcyukkuri:street_{OPPOSITE[facing]}',
                            f'mcyukkuri:street_{facing}' if outgoing else EMPTY,
                            village_pool(style, side) if outgoing else EMPTY)
                # The frontage arrives from vanilla and does not accept another lot.
                if 'frontage' in roles:
                    expected = (EMPTY, expected[1], expected[2])
                if (name, target, pool) != expected or nbt['joint'] != 'aligned':
                    errors.append(f'{facing} lane jigsaw {pos} must use {expected} with an aligned joint')
        elif name == 'minecraft:street' or target == 'minecraft:street':
            streets.append((facing, name, target, pool))
            if y != 1:
                errors.append(f'vanilla street jigsaw {pos} is off y1')
            elif piece.id.rsplit('/', 1)[-1] in ('mouth_east', 'mouth_west') and z != 4:
                errors.append(f'vanilla street jigsaw {pos} is off the z4 lane centre')
        else:
            errors.append(f'unrecognised town jigsaw {pos} ({name} -> {target})')
    if style:
        leaf = piece.id.rsplit('/', 1)[-1]
        vanilla_street = f'minecraft:village/{style}/streets'
        if 'town_lots' in roles:
            if len(incoming) != 1 or len(piece.sockets) != 1:
                errors.append('town lot needs exactly one front building entrance')
        else:
            expected_lanes = (['west'] if leaf == 'mouth_east' else ['east'] if leaf == 'mouth_west'
                              else ['east', 'west'])
            if sorted(lanes) != expected_lanes or incoming:
                errors.append(f'expected lane sockets {expected_lanes}')
            expected_streets = []
            if 'frontage' in roles:
                expected_streets = [('north', 'minecraft:street', EMPTY, EMPTY)]
            elif leaf in ('mouth_east', 'mouth_west', 'side_street'):
                facing = {'mouth_east': 'east', 'mouth_west': 'west', 'side_street': 'south'}[leaf]
                names = {EMPTY, 'minecraft:street'}
                expected_streets = [(facing, name, 'minecraft:street', vanilla_street) for name in sorted(names)]
            if (expected_streets and (len(streets) != 1 or streets[0] not in expected_streets)
                    or not expected_streets and streets):
                errors.append(f'expected vanilla street connectors {expected_streets}')
    return errors


def hazard(name):
    return name.startswith('minecraft:') and name[10:] in HAZARDS


def dry(state):
    name, props, _ = state
    return name not in ('minecraft:water', 'minecraft:bubble_column') and not hazard(name) and props.get('waterlogged') != 'true'


def clear(state):
    name, props, _ = state
    return dry(state) and (free(name) or name.startswith('minecraft:') and name[10:] in REPLACEABLE
                           or name == 'minecraft:snow' and props.get('layers') == '1')


def support(state):
    name, props, _ = state
    return dry(state) and not clear(state) and (
        full_block(name) and not name.endswith(('_fence_gate', '_candle', '_torch', '_sapling', ':snow'))
        or name.endswith(('_slab', '_stairs')) or name == 'minecraft:dirt_path')


def sturdy(state):
    name, props, _ = state
    return support(state) and (full_block(name) or name.endswith('_slab') and props.get('type') == 'double')


def covered(piece, pos):
    x, y, z = pos
    return any(support(piece.state((x, height, z))) for height in range(y + 2, piece.size[1]))


def palette_checks(piece, allowed):
    """Reject foreign material, hazards in walk space and snow layers at ground level or off a roof."""
    used = {piece.state(pos)[0] for pos in piece.blocks}
    foreign = sorted(block for block in used - allowed if not block.startswith('mcyukkuri:'))
    errors = [f'blocks outside palette: {", ".join(foreign)}'] if foreign else []
    for pos in piece.blocks:
        state = piece.state(pos)
        if state[0] == 'minecraft:snow':
            below = piece.state(add(pos, (0, -1, 0)))
            roof = pos[1] >= 3 and any(clear(piece.state(add(pos, (0, -depth, 0)))) for depth in (2, 3))
            if not (below[0].endswith('_leaves') or support(below) and roof):
                errors.append(f'snow layer {pos} is off a roof')
        if hazard(state[0]) and any(
                stand(piece, add(pos, (dx, dy, dz)), 2, False)
                for dx, _, dz in HORIZONTAL.values() for dy in (0, 1)):
            errors.append(f'hazard {state[0]} beside a walk cell at {pos}')
    return errors


def stand(piece, pos, height, doors):
    return piece.inside(pos) and support(piece.state(add(pos, (0, -1, 0)))) and all(
        clear(piece.state(p)) or pass_door(piece.state(p)[0], doors)
        for p in (add(pos, (0, 1, 0), i) for i in range(height)))


def pass_door(name, doors):
    if name.endswith('_fence_gate'):
        return doors is True
    if name.endswith('_door'):
        if doors == 'wood':
            return name != 'minecraft:iron_door'
        if doors == 'iron':
            return name == 'minecraft:iron_door'
        return doors
    return False


def walk(piece, start, height=2, doors=True):
    """Flood supported cells, with one-block steps and clearance above the lower side of a step."""
    if not stand(piece, start, height, doors):
        return set()
    seen, todo = {start}, deque([start])
    while todo:
        pos = todo.popleft()
        for dx, _, dz in HORIZONTAL.values():
            for dy in (0, -1, 1):
                nxt = add(pos, (dx, dy, dz))
                lower = pos if dy > 0 else nxt
                overhead = piece.state(add(lower, (0, height, 0)))
                if (nxt not in seen and stand(piece, nxt, height, doors)
                        and (not dy or clear(overhead))):
                    seen.add(nxt)
                    todo.append(nxt)
    return seen


def light_checks(piece, gaps=()):
    """Catch an unlit exposed or enclosed floor, even at roof height, or a covered walk below light 8."""
    levels = {pos: level for pos in piece.blocks if (level := source_level(*piece.state(pos)[:2]))}
    todo = deque(levels)
    while todo:
        pos = todo.popleft()
        remaining = levels[pos] - 1
        if not remaining:
            continue
        for delta in ADJACENT:
            nxt = add(pos, delta)
            if not piece.inside(nxt) or levels.get(nxt, 0) >= remaining:
                continue
            state = piece.state(nxt)
            if transparent(state[0]) or clear(state):
                levels[nxt] = remaining
                todo.append(nxt)
    dark, covered_dark = [], []
    for floor in sorted(piece.blocks):
        pos = add(floor, (0, 1, 0))
        if not stand(piece, pos, 2, False):
            continue
        level = levels.get(pos, 0)
        if level == 0 and sturdy(piece.state(floor)) and not piece.state(floor)[0].endswith(('_glass', ':glass', '_leaves')):
            if not piece.state(pos)[0].endswith(('_carpet', ':snow')):
                dark.append(pos)
        if level < 8 and covered(piece, pos):
            covered_dark.append((pos, level))
    errors = []
    if dark:
        errors.append(f'{len(dark)} spawnable floor cells have block light 0, first {dark[:6]}')
    if covered_dark:
        errors.append(f'{len(covered_dark)} covered walk cells below block light 8, first {covered_dark[:6]}')
    dark_gaps = [pos for pos in sorted(gaps) if not levels.get(pos)]
    if dark_gaps:
        errors.append(f'{len(dark_gaps)} wind-gap cells have block light 0, first {dark_gaps[:6]}')
    return errors


def poi_checks(piece, workstations=True):
    """Catch a vanilla workstation, including filled cauldrons, or an unintended villager bed."""
    found = []
    beds = []
    for pos in piece.blocks:
        name, props, nbt = piece.state(pos)
        if not name.startswith('minecraft:'):
            continue
        raised_barrel = name == 'minecraft:barrel' and nbt.get('NeoForgeData', {}).get('StreetShelter')
        if workstations and name[10:] in WORKSTATIONS and not (piece.id.rsplit('/', 1)[-1] in ('alley', 'street_alley') and raised_barrel):
            found.append((pos, name))
        if name.endswith('_bed') and props.get('part') == 'head':
            beds.append(pos)
    errors = [f'vanilla workstation POI {name} at {pos}' for pos, name in found]
    expected_beds = int(piece.id.rsplit('/', 1)[-1] in ('home_yard', 'home_yard_large'))
    if len(beds) != expected_beds:
        errors.append(f'expected {expected_beds} vanilla beds, found {len(beds)} at {beds[:6]}')
    return errors


def near_bin_checks(points, bins):
    """Catch a carton spot or wind-gap cell whose nearest bin is more than eight blocks away."""
    return [f'{label} {pos} has no bin within 8 blocks' for label, pos in points
            if not any(sum((a - b) ** 2 for a, b in zip(pos, bin_pos)) <= 8 ** 2 for bin_pos in bins)]


def gap_at(piece, pos):
    return (stand(piece, pos, 1, False) and not clear(piece.state(add(pos, (0, 1, 0))))
            and dry(piece.state(add(pos, (0, 1, 0))))
            and any(sturdy(piece.state(add(pos, delta))) for delta in HORIZONTAL.values()))


def entity_features(piece):
    targets, gaps, cartons, awnings, paper = [], set(), set(), set(), set()
    for entity in piece.entities:
        pos = tuple(entity['blockPos'])
        nbt = entity['nbt']
        tags = nbt.get('Tags', [])
        if nbt['id'] == 'minecraft:item' and nbt['Item']['id'] == 'minecraft:paper':
            paper.add(pos)
        if nbt['id'] == 'minecraft:marker':
            if 'mcy_wind_gap' in tags:
                gaps.add(pos)
            if 'mcy_carton_spot' in tags:
                cartons.add(pos)
            if 'mcy_awning' in tags:
                awnings.add(pos)
            if any(tag.endswith('_counter') or tag == 'mcy_counter_stand' for tag in tags):
                targets.append(('counter stand', pos))
        if nbt.get('HumanJob') == 0:
            targets.append(('resident home', pos))
    return targets, gaps, cartons, awnings, paper


def stray_checks(piece, starts, targets, gaps):
    """Catch a blocked bin stand/shove, malformed shelter shell, wet or unpapered wind gap and broken wool awning."""
    entity_targets, marked_gaps, cartons, awnings, paper = entity_features(piece)
    targets.extend(entity_targets)
    gaps.update(marked_gaps)
    gaps.update(pos for pos in paper if gap_at(piece, pos))
    # One sheet can mark a whole connected low nook (the stall and alley use this layout).
    bedding = set()
    todo = list(gaps & paper)
    while todo:
        pos = todo.pop()
        if pos in bedding:
            continue
        bedding.add(pos)
        todo.extend(nxt for delta in HORIZONTAL.values()
                    if (nxt := add(pos, delta)) not in bedding and gap_at(piece, nxt))
    gaps.update(bedding)
    errors = []
    bins, shelters = {}, {}
    low_targets = [('carton spot', pos) for pos in sorted(cartons)]
    for pos, (name, props, nbt) in piece.blocks.items():
        if name == BIN:
            bins[pos] = props, nbt
        if name in SHELTERS:
            part = int(props['part'])
            center = add(pos, (2 - part % 5, -(part // 25), 2 - part // 5 % 5))
            shelters[center] = name, props['facing']
        if name == 'mcyukkuri:wooden_bench':
            targets.append(('bench', pos))
    for pos, (props, nbt) in bins.items():
        facing = HORIZONTAL[props['facing']]
        mouth = add(pos, facing)
        if not stand(piece, mouth, 1, False) or 'Items' not in nbt:
            errors.append(f'bin {pos} has a blocked mouth stand or missing stock tag')
        low_targets.append(('bin stand', mouth))
    for pos, (name, facing) in shelters.items():
        carton = name == 'mcyukkuri:cardboard_shelter'
        for dx, dy, dz in product((-1, 0, 1), (0, 1), (-1, 0, 1)):
            if carton and (dz if facing in ('north', 'south') else dx):
                continue
            p = add(pos, (dx, dy, dz))
            part = dx + 2 + 5 * (dz + 2) + 25 * dy
            block, props, nbt = piece.state(p)
            if (block != name or props.get('facing') != facing or props.get('part') != str(part)
                    or bool(nbt) != (part == 12) or not sturdy(piece.state(add(pos, (dx, -1, dz))))):
                errors.append(f'shelter {pos} has an invalid shell or floor at {p}')
        if carton:
            cartons.add(pos)
        low_targets.append(('shelter entrance', add(pos, HORIZONTAL[facing], 1 if carton else 2)))
    for pos in sorted(gaps):
        if not gap_at(piece, pos):
            errors.append(f'wind gap {pos} lacks dry footing, collision overhead or a sturdy side')
        elif pos not in bedding:
            errors.append(f'wind gap {pos} has no paper in its nook')
        low_targets.append(('wind gap', pos))
    for pos in sorted(awnings):
        roofs = (add(pos, (0, height, 0)) for height in (1, 2, 3))
        if not any(piece.state(roof)[0].endswith('_wool')
                   and any(piece.state(add(roof, delta))[0].endswith('_wool') for delta in HORIZONTAL.values())
                   for roof in roofs):
            errors.append(f'awning {pos} has no adjacent wool roof pair')
    errors.extend(near_bin_checks([('carton spot', p) for p in sorted(cartons)]
                                  + [('wind gap', p) for p in sorted(gaps)], bins))
    for start in starts:
        reachable = walk(piece, start, 1, doors=False)
        tall = walk(piece, start)
        reachable_nooks = {pos for pos in gaps if any(add(pos, delta) in tall for delta in HORIZONTAL.values())}
        todo = list(reachable_nooks)
        while todo:
            pos = todo.pop()
            for delta in HORIZONTAL.values():
                nxt = add(pos, delta)
                if nxt in gaps and nxt not in reachable_nooks:
                    reachable_nooks.add(nxt)
                    todo.append(nxt)
        for label, pos in low_targets:
            if pos not in reachable or pos not in (reachable_nooks if label == 'wind gap' else tall):
                errors.append(f'{label} {pos} unreachable from {start}')
        for pos, (props, _) in bins.items():
            if props.get('tipped') == 'false' and not any(
                    add(pos, delta) in reachable and add(pos, delta, -1) in reachable
                    for delta in HORIZONTAL.values()):
                errors.append(f'standing bin {pos} cannot be shoved over from {start}')
    return errors


def walk_checks(piece, starts, targets):
    """Catch blocked routes to doors, counters, benches, resident homes and villager beds."""
    errors = []
    for pos, (name, props, _) in piece.blocks.items():
        if name.endswith('_door') and props.get('half') == 'lower':
            targets.append(('door', pos))
            upper, state, _ = piece.state(add(pos, (0, 1, 0)))
            if upper != name or state != {**props, 'half': 'upper'}:
                errors.append(f'door {pos} has no matching upper half')
        if name.endswith('_bed') and props.get('part') == 'head':
            targets.append(('villager bed', pos))
    for start in starts:
        reachable = walk(piece, start)
        if not reachable:
            errors.append(f'street socket or gate {start} has no two-high entry')
            continue
        if any(label in ('resident home', 'villager bed') for label, _ in targets):
            with_wood = walk(piece, start, doors='wood')
        if any(label == 'resident home' for label, _ in targets):
            without_wood = walk(piece, start, doors='iron')
        for label, pos in targets:
            sites = [pos]
            if label == 'bench':
                sites = [add(pos, delta) for delta in HORIZONTAL.values()]
            if label == 'villager bed':
                if not any(add(pos, delta) in with_wood for delta in HORIZONTAL.values()):
                    errors.append(f'villager bed {pos} has no path through wooden doors from {start}')
            elif not any(p in reachable for p in sites):
                errors.append(f'{label} {pos} has no two-high path from {start}')
            elif label == 'resident home' and (pos not in with_wood or pos in without_wood or not covered(piece, pos)
                                                or not stand(piece, pos, 2, False)):
                errors.append(f'resident home {pos} needs a dry indoor stand behind a door')
    return errors


def ramp_checks(piece, cut, fill, edge, interior, strict_cut=True):
    """Catch a hole at y0, unsafe fill/edge, a ramp below the fill depth or terrain above a corner-scanned piece."""
    errors = []
    width, height_limit, depth = piece.size
    columns = list(product(range(width), range(depth)))
    holes = [(x, z) for x, z in columns
             if not support(piece.state((x, 0, z))) and piece.state((x, 0, z))[0] != 'minecraft:water']
    if holes:
        errors.append(f'{len(holes)} footprint columns have no ground at y0, first {holes[:6]}')
    if not all(sturdy((block, {}, {})) and block not in ('minecraft:sand', 'minecraft:red_sand', 'minecraft:gravel')
               for block in (edge, interior)):
        errors.append(f'levelling edge/fill need stable solid blocks: {edge}, {interior}')
    summaries = []
    for axis, size in enumerate((width, depth)):
        for slope in (1, -1):
            heights = [round(-fill + (cut + fill) * (x if slope > 0 else size - 1 - x) / max(size - 1, 1))
                       for x in range(size)]
            exposed, retained, removed, buried = 0, 0, 0, 0
            for x, z in columns:
                height = heights[(x, z)[axis]]
                removed += min(max(height, 0), height_limit - 1)
                buried += max(0, height - height_limit + 1)
                # Ground y0 comes from the template. Below it the runtime fill loop writes
                # the soft space down to terrain, using the edge state only on the rim.
                depth_to_ground = max(-1 - height, 0)
                exposed += max(depth_to_ground - FILL_DEPTH, 0)
                if x in (0, width - 1) or z in (0, depth - 1):
                    retained += min(depth_to_ground, FILL_DEPTH)
            if exposed:
                errors.append(f'axis {axis} ramp {slope:+} leaves {exposed} unfilled cells below the piece')
            # Town lots only check height at the centre, so an edge above the cut is expected there.
            if buried and strict_cut:
                errors.append(f'axis {axis} ramp {slope:+} leaves {buried} terrain cells above the cut')
            summaries.append(f'cut {removed}, exposed {edge} {retained}, buried {buried}')
    return errors, summaries


def walk_starts(piece):
    return [add(pos, (0, 1, 0)) if pos[1] == 0 else pos for pos in piece.sockets]


def joint_check(parent, left, child, right, direction):
    """Catch overlapping templates and a missing lane/sidewalk surface or headroom at a seam."""
    shift = add(add(left, HORIZONTAL[direction]), right, -1)
    if all(-b < d < a for a, b, d in zip(parent.size, child.size, shift)):
        return shift, [f'{parent.id} -> {child.id}: overlapping template bounds at {shift}']
    for dz in range(-4, 4):  # z0..2 and z6..7 sidewalks, z3..5 carriageway.
        a, b = add(left, (0, 0, dz)), add(right, (0, 0, dz))
        if not stand(parent, a, 2, False) or not stand(child, b, 2, False):
            return shift, [f'{parent.id} -> {child.id}: lane or sidewalk gap at joint {a} / {b}']
    return shift, []


def chain_checks(style, pieces, pools, warn, print_report):
    """Check flat chains from the frontage socket against the village's configured radius."""
    distance = village_distance(style)
    root_pool = village_pool(style, 'frontage')
    if root_pool not in pools:
        warn(root_pool, 'missing frontage pool')
        return
    roots = [pieces[row['element']['location']] for row in pools[root_pool]['elements']
             if row['element'].get('location') in pieces]
    for root in roots:
        entrances = [p for p, (_, nbt) in root.sockets.items() if nbt.get('name') == 'minecraft:street']
        origin_x = entrances[0][0] if entrances else root.size[0] // 2
        for direction in ('west', 'east'):
            lengths, mouth_depths, distance_rejected = Counter(), Counter(), 0
            joints = set()
            deepest_lot = 0
            mouth_offered = False

            def visit(parent, offset, used, lots):
                nonlocal distance_rejected, deepest_lot, mouth_offered
                deepest_lot = max(deepest_lot, lots)
                sockets = [(p, nbt) for p, (rotation, nbt) in parent.sockets.items()
                           if rotation == direction + '_up' and nbt.get('target') == f'mcyukkuri:street_{direction}']
                children = []
                can_end = False
                for left, socket in sockets:
                    pool = socket['pool']
                    seen_pools = set()
                    while pool in pools and pool not in seen_pools:
                        seen_pools.add(pool)
                        for row in pools[pool]['elements']:
                            element = row['element']
                            if element['element_type'] == 'minecraft:empty_pool_element':
                                if lots < SIDE_LOTS or pool.endswith('/main_end'):
                                    can_end = True
                                continue
                            if element.get('location') not in pieces:
                                continue
                            child = pieces[element['location']]
                            mouth = child.id.rsplit('/', 1)[-1] == f'mouth_{direction}'
                            for right, (rotation, nbt) in child.sockets.items():
                                if rotation == OPPOSITE[direction] + '_up' and nbt.get('name') == socket['target']:
                                    children.append((left, child, right, element.get('max_count'), mouth))
                        pool = pools[pool]['fallback']
                placed = False
                for left, child, right, limit, mouth in children:
                    mouth_offered |= mouth
                    key = (parent.id, left, child.id, right)
                    shift, errors = joint_check(parent, left, child, right, direction)
                    if key not in joints:
                        for error in errors:
                            warn(style, error)
                        joints.add(key)
                    if errors or not mouth and lots == SIDE_LOTS or limit is not None and used.count(child.id) >= limit:
                        continue
                    child_x = offset + shift[0]
                    length = -child_x if direction == 'west' else child_x + child.size[0] - 1
                    if length > distance:
                        distance_rejected += 1
                        continue
                    placed = True
                    if mouth:
                        lengths[length] += 1
                        mouth_depths[lots + 2] += 1
                    else:
                        visit(child, child_x, (*used, child.id), lots + 1)
                if can_end or not placed:
                    length = -offset if direction == 'west' else offset + parent.size[0] - 1
                    lengths[length] += 1
                    if sockets and not children and not can_end and lots < SIDE_LOTS:
                        warn(style, f'{parent.id} {direction} has no matching pool candidate')

            visit(root, -origin_x, (root.id,), 0)
            if mouth_offered and not mouth_depths[deepest_lot + 2]:
                warn(style, f'{direction} has no fitting mouth at lot count {deepest_lot}')
            label = ', '.join(f'{n}x{count}' for n, count in sorted(lengths.items()))
            print_report(f'{style} {direction}: side lengths (blocks x layouts) {label}; '
                         f'{sum(mouth_depths.values())}/{sum(lengths.values())} end in a mouth; '
                         f'mouth depths {dict(sorted(mouth_depths.items()))}; {distance_rejected} exceed {distance} blocks')


def inspect_town(entries):
    failures = []

    def fail(id, problem):
        failures.append(f'{id}: {problem}')

    def resources(folder, suffix):
        base = DATA / 'mcyukkuri' / folder
        for p in sorted((base / 'village').rglob('*' + suffix)):
            yield f'mcyukkuri:{p.relative_to(base).with_suffix("").as_posix()}', p

    pools = {id: json.loads(path.read_text(encoding='utf-8'))
             for id, path in resources('worldgen/template_pool', '.json')}
    village = {id: Piece.read(id, path) for id, path in resources('structure', '.nbt')}
    pool_checks(pools, village, entries, fail)
    roles = {}
    elements = {}
    for pool, doc in pools.items():
        for row in doc['elements']:
            element = row['element']
            if 'location' in element:
                location = element['location']
                roles.setdefault(location, set()).add(pool.rsplit('/', 1)[-1])
                elements[location] = element
    new_status = {}
    for (_, id), row in entries.items():
        new = row.get('new', False)
        if new_status.setdefault(id, new) != new:
            fail(id, 'POOL_ENTRIES disagree on new status')

    styled = {id.split('/')[1] for id in village if id.count('/') == 2}.intersection(STYLES)
    allowed = {}
    if styled:
        from palette import allowed_blocks
        allowed = {style: set(allowed_blocks(style)) for style in sorted(styled)}
    for id, piece in village.items():
        parts = id.split('/')
        style = parts[1] if len(parts) == 3 else None
        if style is not None and style not in allowed:
            fail(id, f'unknown village style {style}')
            continue
        if id not in roles:
            fail(id, 'exported village template is absent from all pools')
        for problem in socket_checks(piece, style, roles.get(id, set()), pools):
            fail(id, problem)
        if style and id in elements:
            for problem in palette_checks(piece, allowed[style]):
                fail(id, problem)
            element = elements[id]
            town_lot = 'town_lots' in roles.get(id, ())
            cut, fill = (8, 5) if town_lot else (element['max_cut'], element['max_fill'])
            problems, summaries = ramp_checks(piece, cut, fill, element['edge']['Name'],
                                              element['fill']['Name'], strict_cut=not town_lot)
            for problem in problems:
                fail(id, problem)
            print(f'{id}: {cut}/{fill} synthetic ramps (x+, x-, z+, z-) ' + ' | '.join(summaries))
        starts = walk_starts(piece)
        targets = []
        gaps = set()
        checks = stray_checks(piece, starts, targets, gaps) + walk_checks(piece, starts, targets) + light_checks(piece, gaps)
        # Shops keep the established service furnishings. Other legacy POIs are reported before restyling.
        checks += poi_checks(piece, parts[-1] not in ('pet_shop', 'yukkuri_shop', 'oni_shop'))
        if new_status.get(id, False):
            for problem in dict.fromkeys(checks):
                fail(id, problem)
        else:
            if not style:
                print(f'RESTYLE {id}: needs a biome palette, styled connectors and a terrain band')
            for problem in dict.fromkeys(checks):
                print(f'RESTYLE {id}: {problem}')
    for style in STYLES:
        chain_checks(style, village, pools, fail, print)
    print('SKIPPED: vanilla house and bed counts over 20 seeds (requires vanilla village generation).')
    return failures


def export_authors(authors, entries):
    from export_registry import load, save
    from runtime_model_fingerprint import matches_model_fingerprint

    manifest_path, exports_path = HERE / 'manifest.json', HERE / 'exports.json'
    manifest = load(manifest_path)
    for author in authors:
        if author.__name__ == 'author_street':
            # The existing human-world exporter already builds and records this module.
            continue
        for building in author.build():
            exported = building.save()
            exported['source'] = Path(author.__file__).relative_to(ROOT).as_posix()
            manifest.append(exported)
    if any(author.__name__ != 'author_street' for author in authors):
        save(manifest_path, manifest)
    pool_writer = HERE / 'town_pools.py'
    if pool_writer.is_file():
        subprocess.run([sys.executable, str(pool_writer)], cwd=ROOT, check=True)
    if entries or pool_writer.is_file():
        # Pool generation runs after all authors, so the original exporter may have hashed
        # a previous pool table and missed new template files.
        exports = load(exports_path)
        prefix = 'src/main/resources/data/mcyukkuri/'
        patterns = (prefix + 'structure/village/**/*.nbt', prefix + 'worldgen/template_pool/village/**/*.json')
        files = {name: digest for name, digest in exports['files'].items()
                 if not name.startswith((prefix + 'structure/village/', prefix + 'worldgen/template_pool/village/'))}
        sources = [Path(author.__file__) for author in authors]
        sources.extend(p for name in ('palette.py', 'town_pools.py') if (p := HERE / name).is_file())
        for path in [*sources, *(p for pattern in patterns for p in ROOT.glob(pattern))]:
            name = path.relative_to(ROOT).as_posix()
            raw = path.read_bytes()
            digest = sha256(raw).hexdigest()
            previous = exports['files'].get(name)
            files[name] = previous if previous and (
                digest == previous or path.suffix in ('.json', '.py')
                and matches_model_fingerprint(raw, previous)) else digest
        exports['files'] = dict(sorted(files.items()))
        save(exports_path, exports)


def self_test():
    from types import SimpleNamespace

    stone = ('minecraft:stone', {}, {})
    bed = ('minecraft:white_bed', {'facing': 'south', 'part': 'head', 'occupied': 'false'}, {})
    style = 'plains'
    end = village_pool(style, 'main_end')
    frontage = village_pool(style, 'frontage')
    market = village_pool(style, 'main_market')
    civic = village_pool(style, 'main_civic')
    empty_element = {'weight': 1, 'element': {'element_type': 'minecraft:empty_pool_element'}}
    vanilla = f'minecraft:village/{style}/streets'

    def floor(id='fixture', width=9, height=8):
        return Piece(id, (width, height, 8), {(x, 0, z): stone for x, z in product(range(width), range(8))}, [])

    def socket(facing, name=EMPTY, target=EMPTY, pool=EMPTY, final=AIR):
        return ('minecraft:jigsaw', {'orientation': facing + '_up'},
                dict(id='minecraft:jigsaw', name=name, target=target, pool=pool, joint='aligned', final_state=final))

    def table(id, fallback, *pieces):
        return dict(name=id, fallback=fallback, elements=[
            {'weight': 1, 'element': dict(element_type='mcyukkuri:levelled', location=p.id,
                                         processors=EMPTY, projection='rigid', max_count=1)} for p in pieces])

    def caught(label, messages, expected):
        if not any(expected in message for message in messages):
            raise AssertionError(f'{label}: expected {expected!r}, got {messages}')
        print(f'CAUGHT {label}: {next(message for message in messages if expected in message)}')

    p = floor()
    if palette_checks(p, {stone[0]}) or ramp_checks(p, 6, 4, stone[0], stone[0])[0]:
        raise AssertionError('positive palette and ramp control failed')
    p.blocks[2, 1, 2] = ('minecraft:light', {'level': '15'}, {})
    if light_checks(p):
        raise AssertionError('positive block light control failed')
    del p.blocks[2, 1, 2]
    roof = Piece('roof', (1, 4, 1), {(0, 3, 0): stone}, [])
    caught('unlit roof', light_checks(roof), '(0, 4, 0)')
    roof.blocks.update({(0, 0, 0): stone, (0, 1, 0): ('minecraft:light', {'level': '4'}, {})})
    caught('dim covered walk', light_checks(roof), 'covered walk cells below block light 8')
    p.blocks[0, 1, 0] = ('minecraft:water_cauldron', {'level': '2'}, {})
    caught('filled workstation', poi_checks(p), 'minecraft:water_cauldron')
    shop = floor(village_pool(style, 'pet_shop'))
    shop.blocks[0, 1, 0] = ('minecraft:lectern', {'facing': 'north', 'has_book': 'false'}, {})
    if poi_checks(shop, workstations=False):
        raise AssertionError('shop workstation exemption failed')
    caught('ordinary workstation', poi_checks(shop), 'minecraft:lectern')
    home = floor(village_pool(style, 'home_yard'))
    home.blocks[2, 1, 2] = bed
    if poi_checks(home):
        raise AssertionError('one-bed home control failed')
    home.blocks[3, 1, 2] = bed
    caught('second villager bed', poi_checks(home), 'expected 1 vanilla beds')
    narrow = floor(width=1)
    narrow.blocks[0, 1, 3] = bed
    if walk_checks(narrow, [(0, 1, 0)], []):
        raise AssertionError('positive villager bed route control failed')
    narrow.blocks[0, 1, 1] = ('minecraft:oak_fence_gate', {'open': 'false'}, {})
    caught('bed behind fence gate', walk_checks(narrow, [(0, 1, 0)], []), 'villager bed')
    p.blocks[1, 1, 1] = ('minecraft:oak_planks', {}, {})
    p.blocks[2, 1, 1] = ('minecraft:cactus', {}, {})
    p.blocks[3, 1, 1] = ('minecraft:snow', {'layers': '1'}, {})
    problems = palette_checks(p, {stone[0], 'minecraft:cactus', 'minecraft:snow', 'minecraft:water_cauldron'})
    caught('foreign palette block', problems, 'minecraft:oak_planks')
    caught('danger by walk cell', problems, 'hazard minecraft:cactus')
    caught('snow on paving', problems, 'off a roof')
    del p.blocks[0, 0, 0]
    problems, _ = ramp_checks(p, 10, FILL_DEPTH + 2, 'minecraft:sand', stone[0])
    caught('missing foundation', problems, 'no ground at y0')
    caught('unstable retaining edge', problems, 'stable solid')
    caught('ramp beyond fill', problems, 'unfilled cells')
    caught('ramp exceeding cut height', problems, 'terrain cells above')
    short = floor()
    problems, summaries = ramp_checks(short, 10, 4, stone[0], stone[0], strict_cut=False)
    if problems or not all('buried' in summary for summary in summaries):
        raise AssertionError(f'centre-only ramp control failed: {problems}, {summaries}')
    caught('terrain above a corner-scanned piece',
           ramp_checks(short, 10, 4, stone[0], stone[0])[0], 'terrain cells above')
    caught('distant bin', near_bin_checks([('carton spot', (0, 1, 0))], [(9, 1, 0)]), 'within 8 blocks')
    if near_bin_checks([('wind gap', (0, 1, 0))], [(8, 1, 0)]):
        raise AssertionError('eight-block bin threshold rejected its boundary')

    p = floor()
    p.blocks.update({(1, 2, 1): stone, (1, 1, 2): stone, (5, 3, 3): ('minecraft:white_wool', {}, {}),
                     (2, 1, 5): (BIN, {'facing': 'east', 'tipped': 'false'}, {'Items': []}),
                     (3, 1, 5): stone, (1, 1, 5): stone, (2, 1, 4): stone, (2, 1, 6): stone,
                     (7, 1, 3): ('mcyukkuri:cardboard_shelter', {'part': '12', 'facing': 'north'}, {})})
    p.entities = [{'blockPos': pos, 'nbt': {'id': 'minecraft:marker', 'Tags': [tag]}}
                  for pos, tag in [((1, 1, 1), 'mcy_wind_gap'), ((5, 1, 3), 'mcy_awning')]]
    gaps = set()
    problems = stray_checks(p, [(0, 1, 0)], [], gaps)
    caught('gap without paper', problems, 'no paper')
    caught('dark narrow gap', light_checks(p, gaps), 'wind-gap cells')
    caught('isolated awning wool', problems, 'no adjacent wool')
    caught('blocked bin', problems, 'blocked mouth stand')
    caught('standing bin with no shove', problems, 'cannot be shoved')
    caught('missing carton shell blocks', problems, 'invalid shell or floor')
    p.blocks.update({(4, y, z): stone for y, z in product(range(1, 4), range(8))})
    caught('severed route', walk_checks(p, [(0, 1, 0)], [('counter stand', (8, 1, 0))]), 'no two-high path')
    caught('blocked entry', walk_checks(p, [(4, 1, 1)], []), 'has no two-high entry')
    caught('home without a wooden door', walk_checks(p, [(0, 1, 0)], [('resident home', (0, 1, 0))]), 'behind a door')

    pet = floor(village_pool(style, 'pet_shop'))
    west = 'mcyukkuri:street_west'
    east = 'mcyukkuri:street_east'
    pet.blocks.update({(0, 1, 4): socket('west', target=west, pool=civic),
                       (8, 1, 4): socket('east', target=east, pool=market),
                       (4, 1, 0): socket('north', 'minecraft:street')})
    mouth = floor(village_pool(style, 'mouth_east'))
    mouth.blocks.update({(0, 1, 4): socket('west', east),
                         (8, 1, 4): socket('east', target='minecraft:street', pool=vanilla)})
    pools = {frontage: table(frontage, vanilla, pet), market: table(market, end), civic: table(civic, end),
             end: table(end, EMPTY, mouth)}
    lot = floor(village_pool(style, 'stall'))
    lot.blocks.update({(0, 1, 4): socket('west', east, west, civic),
                       (8, 1, 4): socket('east', west, east, market)})
    pools[market] = table(market, end, lot)
    pools[civic]['elements'].append(empty_element)
    if socket_checks(pet, style, {'frontage'}, pools) or socket_checks(mouth, style, {'main_end'}, pools):
        raise AssertionError('positive jigsaw control failed')
    failures, samples = [], []
    pieces = {p.id: p for p in (pet, mouth, lot)}
    chain_checks(style, pieces, pools, lambda _, error: failures.append(error), samples.append)
    if failures or not any('mouth depths {2: 1, 3: 1}' in sample for sample in samples):
        raise AssertionError(f'positive flat chain/fallback failed: {failures}, {samples}')
    mouth.size = (village_distance(style), 8, 8)
    chain_checks(style, pieces, pools, lambda _, error: failures.append(error), samples.append)
    caught('mouth outside the street budget', failures, 'no fitting mouth at lot count 1')
    mouth.size = (9, 8, 8)
    del lot.blocks[0, 0, 1]
    chain_checks(style, pieces, pools, lambda _, error: failures.append(error), samples.append)
    caught('broken seam', failures, 'lane or sidewalk gap')
    caught('overlapping templates', joint_check(pet, (3, 1, 4), mouth, (0, 1, 4), 'east')[1], 'overlapping')
    moved = floor(mouth.id)
    moved.blocks[0, 1, 3] = socket('west', east)
    del moved.blocks[0, 1, 3][2]['joint']
    caught('missing jigsaw field', socket_checks(moved, style, {'main_end'}, pools), 'malformed orientation or NBT')
    moved.blocks[0, 1, 3][2]['joint'] = 'aligned'
    del moved.sockets
    moved.blocks[8, 1, 4] = socket('east', target='minecraft:street', pool='minecraft:village/plains/typo')
    problems = socket_checks(moved, style, {'main_end'}, pools)
    caught('off-centre jigsaw', problems, 'off the y1 z4')
    caught('dangling jigsaw pool', problems, 'references dangling pool')
    off_centre = floor(village_pool(style, 'mouth_east'))
    off_centre.blocks.update({(0, 1, 4): socket('west', east),
                              (8, 1, 3): socket('east', target='minecraft:street', pool=vanilla)})
    caught('mouth connector off the lane centre',
           socket_checks(off_centre, style, {'main_end'}, pools), 'z4 lane centre')
    gate = floor(village_pool(style, 'home_yard'))
    gate.blocks[4, 0, 0] = ('minecraft:dirt_path', {}, {})
    gate.blocks[4, 1, 0] = socket('north', 'minecraft:building_entrance')
    if socket_checks(gate, style, {'town_lots'}, pools):
        raise AssertionError('positive town gate control failed')
    inward = floor(village_pool(style, 'home_yard'))
    inward.blocks[4, 1, 0] = socket('south', 'minecraft:building_entrance')
    caught('town gate facing in', socket_checks(inward, style, {'town_lots'}, pools), 'must face north')
    raised = floor(village_pool(style, 'home_yard'))
    raised.blocks[4, 0, 0] = socket('north', 'minecraft:building_entrance', final='minecraft:dirt_path')
    caught('town gate one block above lane', socket_checks(raised, style, {'town_lots'}, pools), 'y1 front edge')
    pools[market]['fallback'] = 'mcyukkuri:village/plains/typo'
    failures = []
    pool_checks(pools, {pet.id: pet}, {}, lambda _, error: failures.append(error))
    caught('dangling fallback pool', failures, 'dangling fallback')
    caught('dangling template', failures, 'dangling template')
    entry = dict(pool='main_market', piece='stall', styles=[style], weight=1,
                 max_count=1, max_cut=6, max_fill=4, new=True)
    _, failures = validate_entries([SimpleNamespace(__name__='author_fixture', POOL_ENTRIES=[entry, entry])])
    caught('duplicate pool declaration', failures, 'duplicate pool entry')

    pool = village_pool(style, 'town_lots')
    weights = [7, 15, 9, 11]
    rows = [dict(pool='town_lots', piece=f'lot_{i}', styles=[style], weight=weight,
                 max_count=None, max_cut=None, max_fill=None, new=True)
            for i, weight in enumerate(weights)]
    town_entries, failures = validate_entries([SimpleNamespace(__name__='author_weights', POOL_ENTRIES=rows)])
    if failures:
        raise AssertionError(f'town weight declaration fixture failed: {failures}')
    # 7 * 87 / 42 is 14.5: town_pools and the checker must both round it to 14, not by a float share.
    town = {pool: dict(name=pool, fallback=EMPTY, elements=[
        dict(weight=round(row['weight'] * 87 / 42), element=dict(
            element_type='mcyukkuri:levelled', location=location, processors=EMPTY,
            projection='rigid', max_fill=5))
        for (_, location), row in town_entries.items()])}
    sys.modules['town_pools'] = SimpleNamespace(STYLES={style: (EMPTY, EMPTY, 87)})
    site = {location: floor(location) for _, location in town_entries}
    failures = []
    pool_checks(town, site, town_entries, lambda _, error: failures.append(error))
    if failures:
        raise AssertionError(f'normalized town weight control failed: {failures}')
    town[pool]['elements'][0]['weight'] = 15
    failures = []
    pool_checks(town, site, town_entries, lambda _, error: failures.append(error))
    caught('stale town lot weight', failures, 'weight or limits differ')
    del sys.modules['town_pools']
    print('Town negative controls passed.')
    return 0


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    mode = parser.add_mutually_exclusive_group()
    mode.add_argument('--check', action='store_true')
    mode.add_argument('--self-test', action='store_true')
    mode.add_argument('--export', action='store_true', help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.self_test:
        return self_test()
    sys.path.insert(0, str(ROOT / 'scripts'))
    from export import town_authors

    authors = list(town_authors(HERE))
    entries, errors = validate_entries(authors)
    if not errors:
        if args.export:
            export_authors(authors, entries)
        errors = inspect_town(entries)
    for problem in errors:
        print(f'FAIL {problem}')
    if not errors:
        print('Town checks passed.')
    return int(bool(errors))


if __name__ == '__main__':
    try:
        sys.exit(main())
    except (OSError, ValueError, KeyError, IndexError, ImportError, StructError, subprocess.CalledProcessError) as error:
        sys.exit(f'FAIL town input: {error}')
