"""Main-street pieces of the village and the oni-san shop: original block plans on the Building writer of build.py, exported once per
village type in that type's materials (palette.py).

Street convention (shared with the pet-shop apron in build.village_shop): paving at y0, people walk at y1, the lane on z3..5 in the type's
vanilla street block with sidewalks on z0..2 and z6..7 in the town's stone, buildings south of the lane (z >= 8). A piece joins the
street with a horizontal jigsaw on the lane centre (z4) at both ends. The east-edge connector is named `mcyukkuri:street_west` and draws
`village/<type>/main_market`, the west-edge one is named `mcyukkuri:street_east` and draws `village/<type>/main_civic`; with these names
jigsaw can only attach a piece unrotated, so its buildings always stand on the far side of the lane from the village road, and one
template serves both sides of the pet shop.

Stray objects are the ones the stray systems read (docs/FEATURES.md 流浪): a garbage point is a `mcyukkuri:garbage_bin` with a finite real
stock, standing or already knocked over with its mouth toward its stand cell (settlement/GarbageSearch); a wind gap is a dry floor cell with a
collision block right above and a full block beside it, with a real paper on it (settlement/FoundShelter); cardboard boxes and crates
use the normal rotating shelter blocks. Street residents' homes are indoor cells behind a wooden door, except the stall keepers, who live in
their booth behind the counter.
"""
from pathlib import Path
import json, math, sys
sys.path.insert(0, str(Path(__file__).parent))
import build
from build import (Building, stairs, slab, barrel, furniture, marker, frame, register_pen, stock_growth, name_at, string, byte, integer,
                   compound, sequence, integers, real, flat_roof, gable_snow, ground, light_up)
from palette import PALETTES, PLAINS, STYLES, link, pick

OUT = build.OUT
OPP = {'north': 'south', 'south': 'north', 'east': 'west', 'west': 'east'}
STEP = {'north': (0, -1), 'south': (0, 1), 'east': (1, 0), 'west': (-1, 0)}
LOG = {'axis': 'y'}

# Pool table rows of the pieces of this module and of the pet shop (build.py), read by town_pools.py (design town.md section 9).
# Terrain bands: the pet shop 10 cut / 8 fill, main-street lots 6 / 4.
POOL_ENTRIES = [
    {'pool': 'frontage', 'piece': 'pet_shop', 'weight': 1, 'max_count': 1, 'max_cut': 10, 'max_fill': 8, 'styles': None},
    {'pool': 'main_market', 'piece': 'oni_shop', 'weight': 6, 'max_count': 1, 'max_cut': 6, 'max_fill': 4, 'styles': None},
    {'pool': 'main_market', 'piece': 'stall', 'weight': 3, 'max_count': 2, 'max_cut': 6, 'max_fill': 4, 'styles': None},
    {'pool': 'main_market', 'piece': 'alley', 'weight': 2, 'max_count': 1, 'max_cut': 6, 'max_fill': 4, 'styles': None},
    {'pool': 'main_civic', 'piece': 'stall', 'weight': 1, 'max_count': 2, 'max_cut': 6, 'max_fill': 4, 'styles': None},
]


def pool_id(p, role): return f'mcyukkuri:village/{p.style}/{role}'


class Site(Building):
    """A Building that also records the stray objects, lamps and jigsaws it places, for check_structure.check_street."""
    def __init__(self, name, size, p, floor=1):
        super().__init__(name, size)
        self.palette = p; self.floor = floor; self.garbage = []; self.gaps = []; self.shelters = []; self.jigsaws = []; self.staff_doors = []


def records(b):
    for key in ('garbage', 'gaps', 'shelters', 'jigsaws'):
        if not hasattr(b, key): setattr(b, key, [])
    return b


# ---------------------------------------------------------------------------------------------------------- shared street objects
def item_stack(slot, item, count):
    return {'Slot': byte(slot), 'id': string(item if ':' in item else 'minecraft:' + item), 'count': integer(count)}


def garbage(b, x, y, z, facing, items, tipped=False):
    """A stray garbage point: a street bin with a finite real stock. A tipped bin's mouth faces its stand cell; a standing one
    faces that cell too and is shoved over from any side whose far side is open."""
    records(b)
    nbt = {'id': string('mcyukkuri:garbage_bin'), 'Items': sequence(10, [item_stack(i, item, n) for i, (item, n) in enumerate(items)])}
    b.block(x, y, z, 'mcyukkuri:garbage_bin', {'facing': facing, 'tipped': 'true' if tipped else 'false'}, nbt)
    dx, dz = STEP[facing]
    b.garbage.append(((x, y, z), facing, (x + dx, y, z + dz)))


def wind_gap(b, x, y, z, paper=True):
    """A low covered cell against a wall; a real paper lies in it as bedding (it never despawns; anyone may still take it)."""
    records(b).gaps.append((x, y, z))
    if paper:
        b.entity(x + .5, y, z + .5, 'minecraft:item', {'Item': compound({'id': string('minecraft:paper'), 'count': integer(1)}),
                                                       'Age': integer(-32768)})


def shelter_spot(b, x, y, z, kind, facing):
    """A regular 3×3×2 found shelter with a single block entity at its floor centre."""
    records(b).shelters.append(((x, y, z), kind, facing))
    for dy in range(2):
        for dz in range(-1, 2):
            for dx in range(-1, 2):
                part = dx + 2 + 5 * (dz + 2) + 25 * dy
                core = {'id': string('mcyukkuri:wild_shelter')} if part == 12 else None
                b.block(x + dx, y + dy, z + dz, 'mcyukkuri:' + kind, {'part': str(part), 'facing': facing}, core)


def lamp(b, x, y, z, height=3):
    """A village street lamp: a post of the type's lamp fence or wall with a lantern standing on top."""
    post = b.palette.lamp_post
    for dy in range(height): b.block(x, y + dy, z, post, link(post))
    b.block(x, y + height, z, 'lantern', {'hanging': 'false', 'waterlogged': 'false'})


def jigsaw(b, x, y, z, facing, name, target='minecraft:empty', pool='minecraft:empty'):
    records(b).jigsaws.append(((x, y, z), facing, name, target, pool))
    b.block(x, y, z, 'jigsaw', {'orientation': facing + '_up'}, {
        'id': string('minecraft:jigsaw'), 'name': string(name), 'target': string(target),
        'pool': string(pool), 'joint': string('aligned'), 'final_state': string('minecraft:air')})


def street_ends(b, width, west=True, east=True):
    """The two lane-centre connectors of a chainable piece (see the module note)."""
    p = b.palette
    if west: jigsaw(b, 0, 1, 4, 'west', 'mcyukkuri:street_east', 'mcyukkuri:street_west', pool_id(p, 'main_civic'))
    if east: jigsaw(b, width - 1, 1, 4, 'east', 'mcyukkuri:street_west', 'mcyukkuri:street_east', pool_id(p, 'main_market'))


def street_band(b, x0, x1):
    """Sidewalks in the town's stone either side of a lane in the type's vanilla street block, worn in patches."""
    p = b.palette
    for x in range(x0, x1 + 1):
        for z in range(8):
            b.block(x, 0, z, p.lane_at(x, z) if 3 <= z <= 5 else p.sidewalk_at(x, z))


def lot_ground(b, x0, x1, z0, z1):
    p = b.palette
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            block = p.ground_at(x, z)
            b.block(x, 0, z, block, {'snowy': 'false'} if block == 'grass_block' else None)


def street_residents(b, first, second):
    """Two resident home stands on the lot. Only the second slot can roll a non-neutral attitude."""
    for (x, z), kind, tags in [
        (first, 'caretaker_brother', ['mcy_street_local']),
        (second, 'oniisan', ['mcy_street_local', 'mcy_street_special']),
    ]:
        b.entity(x + .5, 1, z + .5, f'mcyukkuri:{kind}',
                 {'HumanJob': integer(0), 'HumanAttitude': integer(2), 'Tags': sequence(8, tags)})


def fence_row(b, x0, x1, y, z, kind):
    for x in range(x0, x1 + 1):
        b.block(x, y, z, kind, link(kind, [s for s, ok in (('west', x > x0), ('east', x < x1)) if ok]))


def ground_display(b, x, y, z, item, yaw=0., scale=.6):
    """A good set out on a surface: a fixed item display standing in the cell above it."""
    h = math.radians(yaw) / 2
    b.entity(x + .5, y, z + .5, 'minecraft:item_display', {'item_display': string('ground'),
        'item': compound({'id': string(item if ':' in item else 'minecraft:' + item), 'count': integer(1)}),
        'transformation': compound({'translation': sequence(5, [0., .1, 0.]), 'scale': sequence(5, [scale, scale, scale]),
                                    'left_rotation': sequence(5, [0., math.sin(h), 0., math.cos(h)]),
                                    'right_rotation': sequence(5, [0., 0., 0., 1.])})})


def shed(b, x0, x1, z0, z1, wall, corner, roof, door=None, windows=()):
    """A small vanilla-village style building on the street paving: plinth course (y1), `wall` (y2..3), `corner` posts, and a gable roof
    of the `roof` family whose ridge runs along z (a flat roof with a parapet in the desert). door = (x, z, facing); windows = [(x, z)]
    glass panes at y2."""
    p = b.palette
    b.box((x0, 0, z0), (x1, 0, z1), p.plinth)
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            if x in (x0, x1) or z in (z0, z1):
                b.block(x, 1, z, p.plinth_at(x, z, 5))
                b.box((x, 2, z), (x, 3, z), wall)
    for x in (x0, x1):
        for z in (z0, z1): b.box((x, 1, z), (x, 3, z), *p.state(corner))
    if door:
        dx, dz, facing = door
        b.door(dx, 1, dz, facing, p.wood)
    for wx, wz in windows:
        b.block(wx, 2, wz, 'glass_pane', {'north': 'false', 'south': 'false', 'east': 'false', 'west': 'false', 'waterlogged': 'false'})
    if p.flat:
        flat_roof(b, p, {(x, z) for x in range(x0, x1 + 1) for z in range(z0, z1 + 1)}, 4); return
    width = x1 - x0 + 1; half = width // 2
    for i in range(half):
        y = 4 + i
        for z in range(z0, z1 + 1):
            stairs(b, x0 + i, y, z, roof, 'east'); stairs(b, x1 - i, y, z, roof, 'west')
        for z in (z0, z1):
            if x0 + i + 1 <= x1 - i - 1: b.box((x0 + i + 1, y, z), (x1 - i - 1, y, z), wall)
    if width % 2:
        for z in range(z0, z1 + 1): slab(b, x0 + half, 4 + half, z, roof)
    if p.snow:
        for z in range(z0, z1 + 1):
            for x in (x0 + half - 1, x1 - half + 1): b.block(x, 4 + half - 1, z, p.planks(roof), over=True); b.block(x, 4 + half, z, 'snow', {'layers': '2'})


def connect_bars(b):
    """Iron bar and glass pane connections as vanilla would update them (bars, panes, glass and full blocks)."""
    from check_structure import transparent
    joins = ('minecraft:glass_pane', 'minecraft:glass', 'minecraft:iron_bars')
    for (x, y, z), (n, p, nbt) in list(b.blocks.items()):
        if n not in ('minecraft:glass_pane', 'minecraft:iron_bars'): continue
        props = dict(p)
        for d, (dx, dz) in {'north': (0, -1), 'south': (0, 1), 'east': (1, 0), 'west': (-1, 0)}.items():
            m = name_at(b, x + dx, y, z + dz)
            props[d] = 'true' if m in joins or (m != 'minecraft:air' and not transparent(m) and not m.endswith(('_stairs', '_slab', '_trapdoor', '_fence', '_wall', 'lantern')) and not m.startswith('mcyukkuri:')) else 'false'
        props.setdefault('waterlogged', 'false')
        b.blocks[x, y, z] = (n, props, nbt)


# ---------------------------------------------------------------------------------------------------------- street pieces
def street_stall(p):
    """A market stall on the far side of the lane: striped awning on four posts over a booth with plank sides and a side door, a
    produce counter, stores behind it. The two stall keepers live in the booth behind the counter. Old crates by the stall (a crate spot),
    the market bin (garbage point) and the dry crawl space under the produce shelf behind the stall (wind gap)."""
    W, H, D = 15, 8, 16
    b = Site(f'village/{p.style}/stall', [W, H, D], p)
    b.box((0, 1, 0), (W - 1, H - 1, D - 1), 'air')
    street_band(b, 0, W - 1)
    lot_ground(b, 0, W - 1, 8, D - 1)
    b.box((2, 0, 8), (11, 0, 13), p.lane)                         # trampled ground under the booth (the crate spot keeps the ground)
    for x, z in [(3, 9), (11, 9), (3, 13), (11, 13)]: b.box((x, 1, z), (x, 3, z), *p.state(p.post))
    for x in range(2, 13):
        wool = 'orange_wool' if (x // 2) % 2 == 0 else 'white_wool'
        for z in range(8, 15): b.block(x, 4, z, wool)
        b.block(x, 3, 8, wool)                                   # front valance, above head height
    b.box((2, 1, 14), (12, 3, 14), p.wall)                        # back wall of the booth
    # Booth sides: two-high plank panels between the posts, the keepers' door in the west one.
    for x in (3, 11): b.box((x, 1, 10), (x, 2, 12), p.planks())
    b.door(3, 1, 11, 'west', p.wood)
    # Produce counter: boards with a slab top the goods sit on (a step and a half: nobody climbs over it); stores at the back.
    for x in range(4, 11):
        if x == 7: b.block(x, 1, 9, 'hay_block', LOG)
        else: b.block(x, 1, 9, p.planks(p.dark))
        slab(b, x, 2, 9, p.dark)
    for x, item in [(4, 'carrot'), (5, 'potato'), (6, 'apple'), (8, 'beetroot'), (9, 'bread'), (10, 'pumpkin_pie')]:
        ground_display(b, x, 2.5, 9, item, yaw=20. * x)
    for x, block in [(4, 'pumpkin'), (5, 'hay_block'), (9, 'melon'), (10, 'pumpkin')]:
        b.block(x, 1, 13, block, LOG if block == 'hay_block' else None)
    b.block(7, 3, 11, 'lantern', {'hanging': 'true', 'waterlogged': 'false'})
    # Produce shelf behind the booth: boards on the back wall at y2, stored produce on top; the cells under it are a dry gap with a paper.
    b.box((4, 2, 15), (7, 2, 15), p.planks())
    for x, block in zip(range(4, 8), ('hay_block', 'pumpkin', 'melon', 'hay_block')): b.block(x, 3, 15, block, LOG if block == 'hay_block' else None)
    for x in (4, 5, 6, 7): wind_gap(b, x, 1, 15, paper=(x == 5))
    # Old crates beside the booth, opening toward the lane; the market bin by the west post, within 8 blocks of the whole gap.
    shelter_spot(b, 13, 1, 12, 'crate_shelter', 'north')
    garbage(b, 1, 1, 10, 'north', [('carrot', 2), ('potato', 2), ('beetroot', 1), ('paper', 1), ('stick', 1), ('rotten_flesh', 1), ('mcyukkuri:junk_headwear', 1)])
    # Sidewalks: lamps and a bench facing the lane.
    lamp(b, 13, 1, 7); lamp(b, 1, 1, 1)
    furniture(b, 'bench', 1, [(4, 1), (5, 1), (6, 1)])
    for x, i in [(3, 0), (7, 1)]: b.block(x, 1, 1, p.pot(i))
    street_residents(b, (5, 11), (9, 11))
    street_ends(b, W)
    connect_bars(b)
    light_up(b)
    return b


def street_alley(p):
    """A back alley between two village sheds: a storehouse with its door on the alley, a tool shed with a lean-to and a woodpile,
    two bins against the storehouse wall, a cardboard spot at the dead end and a lantern on the end wall. One resident lives in each shed."""
    W, H, D = 17, 9, 20
    b = Site(f'village/{p.style}/alley', [W, H, D], p)
    b.box((0, 1, 0), (W - 1, H - 1, D - 1), 'air')
    street_band(b, 0, W - 1)
    lot_ground(b, 0, W - 1, 8, D - 1)
    floor = p.sidewalk + tuple((block, 2) for block, _ in p.lane_wear if block not in ('dirt_path',))
    for x in range(6, 11):
        for z in range(8, 20): b.block(x, 0, z, pick(floor, x, z, 9))   # the alley: old paving worn through in places
    # West: storehouse, door on the alley; hay, stores and a work table inside.
    shed(b, 0, 5, 8, 17, p.wall, p.post, p.roof, door=(5, 10, 'east'), windows=[(5, 13), (5, 15), (2, 8)])
    for y in (1, 2): b.block(1, y, 9, 'hay_block', LOG)
    b.block(1, 1, 10, 'pumpkin'); b.block(4, 1, 16, 'crafting_table'); b.box((1, 1, 16), (2, 1, 16), 'hay_block', LOG)
    b.block(1, 3, 9, 'lantern', {'hanging': 'false', 'waterlogged': 'false'})   # on the hay stack
    # East: tool shed, door on the sidewalk; a lean-to on its alley wall and a woodpile closing it at the far end.
    shed(b, 11, 16, 8, 15, p.wall, p.beam, p.roof_trim, door=(13, 8, 'north'), windows=[(16, 11), (11, 9)])
    b.block(15, 1, 14, p.tree_log, LOG); ground_display(b, 15, 2, 14, 'iron_axe', yaw=30.)   # chopping block
    b.block(12, 1, 14, p.tree_log, {'axis': 'x'})
    for y in (1, 2): b.block(15, y, 9, 'hay_block', LOG)
    b.block(15, 3, 9, 'lantern', {'hanging': 'false', 'waterlogged': 'false'})
    b.box((10, 2, 12), (10, 2, 14), p.planks())
    b.box((10, 1, 15), (10, 2, 15), p.tree_log, {'axis': 'z'})
    for z in (12, 13, 14): wind_gap(b, 10, 1, z, paper=(z == 13))
    # Bins along the storehouse wall: one already knocked over, one standing with room behind it to fall; a hay bale further on.
    garbage(b, 6, 1, 11, 'east', [('bread', 1), ('apple', 1), ('paper', 2), ('feather', 1), ('rotten_flesh', 2), ('mcyukkuri:junk_headwear', 1)], tipped=True)
    garbage(b, 6, 1, 13, 'east', [('potato', 1), ('cookie', 1), ('stick', 2), ('paper', 1)])
    b.block(6, 1, 15, 'hay_block', LOG)
    # Dead end: low wall with a rail, a lantern on its west post; a cardboard spot facing back up the alley.
    for x in range(6, 11): b.block(x, 1, 19, p.plinth_at(x, 19, 2))
    fence_row(b, 6, 10, 2, 19, p.fence)
    b.block(6, 3, 19, 'lantern', {'hanging': 'false', 'waterlogged': 'false'})
    shelter_spot(b, 8, 1, 17, 'cardboard_shelter', 'north')
    # Behind the sheds: the ground, a pumpkin patch and a rail.
    b.block(13, 1, 18, 'pumpkin'); fence_row(b, 11, 16, 1, 19, p.fence)
    # Sidewalks: lamps, a trough and planters.
    lamp(b, 6, 1, 7); lamp(b, 12, 1, 1)
    b.block(3, 0, 1, 'water', {'level': '0'}, over=True)
    b.block(4, 1, 1, 'hay_block', LOG)
    # A raised storage barrel covers one dry stand. The lower bin and its lid are separate.
    b.block(9, 2, 12, 'barrel', {'facing': 'west', 'open': 'false'},
            {'id': string('minecraft:barrel'), 'NeoForgeData': compound({'StreetShelter': byte(1)})})
    for x, i in [(8, 0), (9, 1)]: b.block(x, 1, 1, p.pot(i))
    street_residents(b, (3, 12), (13, 11))
    street_ends(b, W)
    connect_bars(b)
    gable_snow(b); light_up(b)
    return b


# ---------------------------------------------------------------------------------------------------------- oni-san shop
# Tools on the wall, consumables and goods on the two shelves (docs/FEATURES.md 鬼威惨工具; wiki shops-factory-npcs R7/R8).
# Cutting is done with vanilla shears (they hang with the tools).
TOOLS = ['pin_tool', 'care_mallet', 'care_awl', 'restraint_frame', 'wrap_sling', 'hanging_hook', 'care_vibrator',
         'iron_enclosure', 'minecraft:shears']
CONSUMABLES = ['spicy_grain', 'sleep_spray', 'stimulant']
GROCERY = ['oil_grain_budget', 'minecraft:bread', 'minecraft:candle', 'minecraft:paper']
# Scummy stock: intelligence, friendliness, boldness, attachment, self-control. Arrogant and rude (gesu) or dull and timid; underfed and dirty.
GESU = (25, 15, 90, 30, 10)
DULL = (15, 40, 20, 45, 30)
WHINY = (30, 25, 55, 70, 15)


def oni_stock(b, x, z, kind, traits, nutrition, hygiene, age=None, form='NORMAL', shape='NORMAL'):
    data = {'PendingRole': string('oni_shopkeeper'), 'Use': string('stock'), 'FactoryGroup': integer(0)}
    life = {'Nutrition': real(float(nutrition)), 'Hygiene': real(float(min(hygiene, 44))),
            'Personality': compound({'Version': integer(1), 'Innate': integers(list(traits))})}
    extra = {'NeoForgeData': compound({'HumanCommerce': compound(data)}), 'YukkuriLife': compound(life)}
    if form != 'NORMAL' or shape != 'NORMAL':
        extra['YukkuriSubspecies'] = compound({'Kind': string(form), 'Congenital': string(shape), 'Initialized': byte(1)})
    if age is not None:
        extra['YukkuriFamily'] = stock_growth(age, 150000)
    b.entity(x, 3, z, 'mcyukkuri:' + kind, extra)


def cage(b, x0, z0, x1, z1, door, facing, floor='coarse_dirt'):
    """A stock cage: iron bars from floor to ceiling on the given ring cells, a coarse floor; the 2-wide iron staff door sits in its ring
    between posts with a lintel (no door panel against bars). door = first leaf (x, z), facing as Building.double_door."""
    p = b.palette
    b.box((x0, 2, z0), (x1, 2, z1), floor, over=True)
    leaves = {door, (door[0] + 1, door[1]) if facing in ('north', 'south') else (door[0], door[1] + 1)}
    if facing in ('north', 'south'): posts = {(door[0] - 1, door[1]), (door[0] + 2, door[1])}
    else: posts = {(door[0], door[1] - 1), (door[0], door[1] + 2)}
    ring = [(x, z) for x in range(x0 - 1, x1 + 2) for z in (z0 - 1, z1 + 1)] + [(x, z) for x in (x0 - 1, x1 + 1) for z in range(z0, z1 + 1)]
    for x, z in ring:
        if (x, z) in leaves: continue
        if (x, z) in posts: b.box((x, 3, z), (x, 6, z), *p.state(p.post), over=True); continue
        if name_at(b, x, 4, z) in ('minecraft:air', 'minecraft:iron_bars'): b.box((x, 3, z), (x, 6, z), 'iron_bars', over=True)
    b.double_door(door[0], 3, door[1], facing)
    for x, z in leaves: b.box((x, 5, z), (x, 6, z), *p.state(p.post, 'z' if facing in ('east', 'west') else 'x'), over=True)


def oni_shop(p=PLAINS, name='yukkuri_oni_shop'):
    """The oni-san shop (25 x 15 x 26): a shabby, low single storey in the type's materials with its weathering (worn plinth, patched
    roof or parapet, replaced floor boards) and a faded two-head sign. Street window cage with floor-level glass (stock on display), a tool
    wall, two goods shelves, three stock cages behind full bars serviced from a staff corridor, a counter, the staff strip and a back
    room with the dock, the waste barrel and a treatment bench. Floor y2, people walk at y3, ceiling y7; walls x1..23, z3..24."""
    from check_structure import light_levels, walk_cells
    b = Site(name, [25, 15, 26], p, floor=3)
    POST = p.state(p.post if p.flat else p.tree_log)   # bark posts: an old frame, not the clean trim of the pet shop
    planks = p.planks(); board = p.planks(p.dark)
    worn = ((p.plinth, 60),) + tuple((block, 2 * w) for block, w in p.weathered)
    # ---- Shell.
    b.box((0, 0, 0), (24, 0, 25), p.plinth)
    for x in range(25):
        for z in range(26): b.block(x, 1, z, p.plinth_at(x, z))
    for x in range(25):
        for z in range(3): b.block(x, 2, z, pick(p.sidewalk + tuple((block, 2) for block, _ in p.weathered), x, z, 4))
    b.box((0, 3, 0), (24, 14, 25), 'air')
    floor, mend = (p.floor, p.roof_patch) if p.flat else (planks, board)
    b.box((1, 2, 3), (23, 2, 24), floor)
    for x in range(2, 23):
        for z in range(4, 24):
            if (x * 73856093 ^ z * 19349663) % 11 == 0: b.block(x, 2, z, mend)   # floor boards replaced over the years
    for x in range(1, 24):
        for z in range(3, 25):
            if x in (1, 23) or z in (3, 24):
                b.block(x, 3, z, pick(worn, x, z, 6))
                for y in (4, 5, 6): b.block(x, y, z, p.roof_patch if p.flat and (x * 5 + y * 7 + z * 3) % 9 == 0 else p.wall)
                b.block(x, 7, z, board)
    b.box((2, 7, 4), (22, 7, 23), planks)                                       # ceiling
    for x in (1, 7, 10, 13, 17, 23): b.box((x, 3, 3), (x, 7, 3), *POST)
    for x in (1, 6, 12, 18, 23): b.box((x, 3, 24), (x, 7, 24), *POST)
    for z in (3, 8, 13, 19, 24):
        for x in (1, 23): b.box((x, 3, z), (x, 7, z), *POST)
    # Facade: floor-level glass of the window cage, the lobby window (one pane long since replaced by bars), the nook window.
    b.box((2, 3, 3), (6, 5, 3), 'glass')
    pane = {'north': 'false', 'south': 'false', 'east': 'false', 'west': 'false', 'waterlogged': 'false'}
    for x, kind in [(8, 'glass_pane'), (9, 'iron_bars'), (15, 'iron_bars'), (18, 'glass_pane'), (19, 'glass_pane'), (20, 'iron_bars'), (21, 'glass_pane')]:
        b.box((x, 4, 3), (x, 5, 3), kind, pane)
    for z, kind in [(21, 'glass_pane'), (22, 'glass_pane')]:
        for x in (1, 23): b.box((x, 4, z), (x, 5, z), kind, pane)
    for x in (3, 4, 20, 21): b.box((x, 4, 24), (x, 5, 24), 'glass_pane', pane)
    b.door(11, 3, 3, 'north', p.wood, 'left'); b.door(12, 3, 3, 'north', p.wood, 'right')
    for x in (10, 11, 12, 13):   # street ramp: two half steps up to the pavement
        b.block(x, 2, 0, 'air'); stairs(b, x, 1, 0, p.step, 'south'); stairs(b, x, 2, 1, p.step, 'south')
    # Faded awning: brown and white stripes stepping out over the pavement, its front edge torn in places.
    for x in range(1, 24):
        wool = 'brown_wool' if (x // 2) % 2 == 0 else 'white_wool'
        b.block(x, 6, 2, wool)
        if x not in (4, 9, 16, 20): b.block(x, 5, 1, wool)
    if p.flat:
        # Flat roof with a parapet patched in odd stone, a few coping slabs long gone.
        flat_roof(b, p, {(x, z) for x in range(1, 24) for z in range(3, 25)}, 8)
        for x in range(1, 24):
            for z in range(3, 25):
                if name_at(b, x, 9, z).endswith('_slab') and (x * 7 + z * 3) % 10 == 0: b.block(x, 9, z, 'air')
                elif name_at(b, x, 8, z) == 'minecraft:' + p.roof_trim and (x + 2 * z) % 7 == 0: b.block(x, 8, z, p.roof_patch)
    else:
        # Patched low roof (ridge along x), gables at both ends; odd boards and old repairs.
        dmax = max(min(z - 3, 24 - z) for z in range(3, 25))
        for z in range(3, 25):
            front = z - 3 <= 24 - z; d = min(z - 3, 24 - z); y = 8 + d // 2
            for x in range(25):
                mat = p.roof_trim if d in (0, dmax) or x in (0, 24) else p.roof
                if mat == p.roof and (x * 3 + z * 5) % 11 == 0: mat = p.roof_patch
                elif mat == p.roof and (x + 2 * z) % 13 == 0: mat = p.roof_trim
                if p.snow and d == dmax: b.block(x, y, z, p.planks(p.roof)); b.block(x, y + 1, z, 'snow', {'layers': '2'}); continue
                if d % 2 == 0: slab(b, x, y, z, mat)
                else: stairs(b, x, y, z, mat, 'south' if front else 'north')
            for x in (1, 23):
                for yy in range(8, y): b.block(x, yy, z, *((p.wall, None) if (yy + z) % 3 else p.state(p.band, 'y')))
    # Sign board in front of the roof: a Reimu and a Marisa head on faded board, as seen from the street (viewer's left is +x).
    b.box((5, 7, 2), (18, 12, 2), board)
    for x in range(5, 19): slab(b, x, 13, 2, p.dark)
    emblem = ['RR.RR....KK.', 'KKKKK...KKK.', 'KSSSK..KKKKK', 'KESEK..YSSSY', 'FSMSF..YESEY']
    mats = {'.': 'cyan_terracotta', 'R': 'red_concrete', 'K': 'black_concrete', 'S': 'white_terracotta', 'E': 'black_concrete',
            'F': 'white_concrete', 'M': 'red_terracotta', 'Y': 'yellow_concrete'}
    for r, row in enumerate(emblem):
        for c, ch in enumerate(row): b.block(17 - c, 12 - r, 2, mats[ch])
    for x, plant in [(3, 'potted_dead_bush'), (21, 'potted_dead_bush')]: b.block(x, 3, 1, plant)
    b.block(22, 3, 1, p.tree_log, {'axis': 'x'})                                 # an old log by the wall
    if not p.flat:
        for z in (10, 18):
            for x in (4, 12, 20): b.block(x, 9, z, 'light', {'level': '10', 'waterlogged': 'false'})   # attic: nothing spawns under the roof
    # ---- Furnishing phase.
    b.strict = True
    # Window cage (stock on display, interior x2..6 z4..6): floor-level street glass, bars to the lobby and the sales floor.
    b.box((7, 3, 4), (7, 6, 6), 'iron_bars')
    cage(b, 2, 4, 6, 6, (4, 7), 'south')
    # The oni shop prepares no beds (user): stock sleeps on the pen floor; each pen's bowl is its place (ShopStaff.PEN_STOCK).
    b.bowl(6, 6, grain=3)
    register_pen(b, 'window', (2, 4, 6, 6), (6, 6), [], (5, 3), hatch=(4, 7))
    oni_stock(b, 3.5, 5.5, 'reimu', GESU, 38, 45, age=60000, form='WASA_REIMU')
    oni_stock(b, 5.0, 4.5, 'marisa', DULL, 42, 40, age=63000)
    oni_stock(b, 2.75, 6.35, 'reimu', WHINY, 30, 50, age=180000, shape='SPARSE_HAIR')
    # Stock cages (interiors x16..19): full bars to the customers (x15), iron staff doors on the corridor side (x20).
    b.box((21, 3, 7), (22, 6, 7), p.wall)              # closes the staff corridor off from the nook
    for name, z0, stock in [('cage_a', 8, [('marisa', GESU, 35, 40, 62000), ('marisa', DULL, 30, 45, 160000)]),
                            ('cage_b', 12, [('alice', DULL, 40, 55, 93000), ('chen', WHINY, 33, 50, 57000)]),
                            ('cage_c', 16, [('reimu', GESU, 36, 42, 272000), ('patchouli', DULL, 30, 55, 56000), ('reimu', WHINY, 28, 48, 249000)])]:
        cage(b, 16, z0, 19, z0 + 2, (20, z0), 'east')
        b.bowl(18, z0 + 2, grain=2)
        register_pen(b, name, (16, z0, 19, z0 + 2), (18, z0 + 2), [], (4, 3), hatch=(20, z0))
        starts = [(18.0, z0 + .6), (19.0, z0 + 1.4), (18.0, z0 + 1.5)]
        for i, ((kind, traits, nutrition, hygiene, age), (x, z)) in enumerate(zip(stock, starts)):
            shape = 'FACE_ASYMMETRY' if name == 'cage_c' and i == 2 else 'NORMAL'
            oni_stock(b, x, z, kind, traits, nutrition, hygiene, age, shape=shape)
    # Tool wall (west, x2 against x1): fourteen tools in two rows of fixed frames.
    for i, item in enumerate(TOOLS): frame(b, 2, 5 - i // 7, 9 + i % 7, item, facing='east')
    # Two goods shelves of stacked barrels: consumables on the first, grain and household goods on the second; frames on their east faces.
    for z in range(10, 14):
        for x in (4, 5, 8, 9):
            barrel(b, x, 3, z, 'up'); barrel(b, x, 4, z, 'east' if x in (5, 9) else 'west')
    for z, item in zip(range(10, 14), CONSUMABLES): frame(b, 6, 4, z, item, facing='east')
    for z, item in zip(range(10, 14), GROCERY): frame(b, 10, 4, z, item, facing='east')
    # Eye-level serving opening and a member door at the west end.
    b.box((5, 3, 16), (14, 3, 16), f'stripped_{p.dark}_log', {'axis': 'x'})
    b.block(9, 3, 16, 'lectern', {'facing': 'north', 'has_book': 'false', 'powered': 'false'}, over=True)
    b.box((2, 3, 16), (2, 6, 16), 'iron_bars')
    b.box((3, 5, 16), (14, 6, 16), 'iron_bars')
    b.double_door(3, 3, 16, 'south')
    for x in range(5, 15): slab(b, x, 4, 16, p.dark, 'bottom')
    for x, item in [(6, 'mcyukkuri:yukkuri_manju'), (7, 'mcyukkuri:yukkuri_manju'), (12, 'minecraft:cookie'), (13, 'mcyukkuri:spicy_grain')]:
        ground_display(b, x, 4.5, 16, item, yaw=35. * x)
    b.human(9.5, 17.5, 'oniisan', 1, 0, 'oni_shopkeeper')
    marker(b, 9.5, 3, 17.5, 'mcy_oni_counter')
    # Clutter that makes it feel cramped without narrowing a passage below 2: crates stacked by the door, a demo table in the main
    # aisle with tools laid out on it, sacks against the counter end.
    for x, z, h in [(8, 4, 2), (9, 4, 1), (8, 5, 1)]:
        for y in range(3, 3 + h): barrel(b, x, y, z, 'up' if y == 3 else 'south')
    ground_display(b, 8, 5, 4, 'mcyukkuri:care_mallet', yaw=80.)
    furniture(b, 'table', 3, [(12, 9), (12, 10)])
    for z, item in [(9, 'mcyukkuri:pin_tool'), (10, 'mcyukkuri:iron_enclosure')]: ground_display(b, 12, 4, z, item, yaw=25. * z, scale=.5)
    b.block(14, 3, 15, 'hay_block', LOG)
    for x, z in [(10, 6), (11, 6), (12, 6), (13, 6), (11, 7), (12, 7)]: b.block(x, 3, z, 'brown_carpet')
    # Customer nook by the east street window: a small table under the window with a chair on each side, the cages in view.
    furniture(b, 'table', 3, [(19, 4)]); furniture(b, 'bench', 3, [(18, 4)], 'z'); furniture(b, 'bench', 3, [(20, 4)], 'z')
    for x, z in [(22, 4), (15, 4)]: b.block(x, 3, z, 'potted_dead_bush')
    # Staff wall to the back room: 2-wide iron door, a barred window onto the treatment bench.
    b.box((2, 3, 19), (14, 6, 19), p.wall)
    b.box((11, 4, 19), (13, 5, 19), 'iron_bars', over=True)
    for x in (6, 9): b.box((x, 3, 19), (x, 6, 19), *POST, over=True)
    b.double_door(7, 3, 19, 'south')
    for x in (7, 8): b.box((x, 5, 19), (x, 6, 19), *p.state(POST[0], 'x'), over=True)
    # Back room (staff, z20..23): dock and waste barrels, feed, a treatment bench (brewing stand, water, grindstone, tools), supplies.
    barrel(b, 2, 3, 20, 'east'); barrel(b, 2, 3, 23, 'east'); barrel(b, 2, 3, 21, 'up'); barrel(b, 2, 4, 21, 'up')
    marker(b, 2.5, 3.5, 20.5, 'mcy_oni_dock'); marker(b, 2.5, 3.5, 23.5, 'mcy_oni_trash')
    b.box((10, 3, 23), (12, 3, 23), board)
    b.block(11, 4, 23, 'brewing_stand', {'has_bottle_0': 'true', 'has_bottle_1': 'false', 'has_bottle_2': 'true'})
    b.block(13, 3, 23, 'water_cauldron', {'level': '2'})
    b.block(9, 3, 23, 'grindstone', {'face': 'floor', 'facing': 'north'})
    ground_display(b, 10, 4, 23, 'mcyukkuri:care_awl', yaw=60.); ground_display(b, 12, 4, 23, 'minecraft:shears', yaw=-30.)
    for x in (16, 17, 18):
        barrel(b, x, 3, 23, 'up')
        if x != 17: barrel(b, x, 4, 23, 'up')
    b.block(4, 3, 23, 'hay_block', LOG); b.block(5, 3, 23, 'hay_block', LOG); b.block(4, 4, 23, 'hay_block', LOG)
    for x in (19, 20, 21, 22): barrel(b, x, 3, 23, 'up')
    barrel(b, 20, 4, 23, 'up'); b.block(22, 3, 22, 'composter', {'level': '6'})
    ground_display(b, 17, 4, 23, 'mcyukkuri:yukkuri_carrier', yaw=15.)
    b.human(14.5, 21.5, 'caretaker_brother', 1, 0, 'oni_staff')
    for x, z in [(2, 23), (22, 23), (2, 17), (22, 4)]: b.block(x, 6, z, 'cobweb')
    # Shop footprint (protection, roster, staff membership): the building from the facade to the back wall.
    marker(b, 1.5, 0.5, 3.5, 'mcy_oni_corner'); marker(b, 23.5, 13.5, 24.5, 'mcy_oni_corner')
    # ---- Lighting: a few lanterns on chains, then hidden light blocks until every covered walkable cell has light >= 8.
    for x, z in [(4, 9), (12, 6), (18, 6), (12, 12), (7, 17), (17, 21), (21, 13)]:
        b.block(x, 6, z, 'lantern', {'hanging': 'true', 'waterlogged': 'false'})
    order = sorted(((abs(dx) + abs(dz), dx, dz) for dx in range(-2, 3) for dz in range(-2, 3)))
    def invisible(x, z):
        y = 6 if 4 <= z <= 23 else 4            # under the ceiling indoors, at head height under the awning outside
        if not 0 <= x <= 24 or not 0 <= z <= 23 or name_at(b, x, y, z) != 'minecraft:air' or (y == 6 and not 2 <= x <= 22): return False
        b.block(x, y, z, 'light', {'level': '15' if y == 6 else '12', 'waterlogged': 'false'}); return True
    for _ in range(200):
        level = light_levels(b); dark = sorted((level.get((x, 3, z), 0), (x, z)) for x, z in walk_cells(b) if level.get((x, 3, z), 0) < 8)
        if not dark: break
        x, z = dark[0][1]
        if not any(invisible(x + dx, z + dz) for _, dx, dz in order): break
    connect_bars(b)
    gable_snow(b); light_up(b)
    return b


# ---------------------------------------------------------------------------------------------------------- village lots
def shifted(original, name, dz):
    """The original layout moved dz rows south (the museum template stays byte-identical), with its entities moved along."""
    width, height, depth = original.size
    lot = Site(name, [width, height, depth + dz], original.palette, getattr(original, 'floor', 1))
    lot.blocks = {(x, y, z + dz): block for (x, y, z), block in original.blocks.items()}
    lot.pens = [dict(p, interior=(p['interior'][0], p['interior'][1] + dz, p['interior'][2], p['interior'][3] + dz),
                     bowl=(p['bowl'][0], p['bowl'][1] + dz), nests=[(x, z + dz) for x, z in p['nests']],
                     hatch=None if p['hatch'] is None else (p['hatch'][0], p['hatch'][1] + dz)) for p in original.pens]
    lot.doors = [(x, y, z + dz) for x, y, z in original.doors]
    for entity in original.entities:
        x, y, z = entity['pos'][1][1]
        nbt = dict(entity['nbt'][1]); nbt['Pos'] = sequence(6, [x, y, z + dz])
        lot.entities.append({'pos': nbt['Pos'], 'blockPos': sequence(3, [int(x), int(y), int(z) + dz]), 'nbt': compound(nbt)})
    return lot


def village_oni_shop(original):
    """The oni-san shop on the main street: the same building behind 8 rows of street, `village/<type>/oni_shop`."""
    p = original.palette
    lot = shifted(original, f'village/{p.style}/oni_shop', 8)
    street_band(lot, 0, original.size[0] - 1)
    # Staff carry actual scraps to the pavement bin. There is no prefilled paper or hair to mask that transfer.
    garbage(lot, 1, 1, 7, 'north', [('stick', 2), ('bread', 1)])
    lamp(lot, 16, 1, 7); lamp(lot, 6, 1, 1); lamp(lot, 20, 1, 1)
    street_ends(lot, original.size[0])
    light_up(lot)
    return lot


def dress_shop_apron(lot):
    """The pet shop's 8-row apron (build.village_shop): its two bins become garbage points, lamps on both sidewalks, sockets at both ends
    (the pet shop arrives from the village centre, so neither end is attachable from outside)."""
    p = lot.palette; width = lot.size[0]
    for x in (4, 34):
        # Wedged between the bench and the shop wall a standing bin cannot be shoved over: both start knocked over.
        garbage(lot, x + 3, 1, 7, 'north', [('paper', 2), ('apple', 1), ('cookie', 1), ('stick', 1)], tipped=True)
    for x, z in [(1, 7), (20, 7), (width - 2, 7), (6, 1), (width - 7, 1)]: lamp(lot, x, 1, z)
    jigsaw(lot, 0, 1, 4, 'west', 'minecraft:empty', 'mcyukkuri:street_west', pool_id(p, 'main_civic'))
    jigsaw(lot, width - 1, 1, 4, 'east', 'minecraft:empty', 'mcyukkuri:street_east', pool_id(p, 'main_market'))


def pieces(style='plains'):
    """Every template of this module in one village type, built fresh (for export, checks and previews)."""
    p = PALETTES[style]
    oni = oni_shop(p, 'yukkuri_oni_shop' if style == 'plains' else f'village/{style}/oni_shop_base')
    return {'stall': street_stall(p), 'alley': street_alley(p), 'oni': oni, 'village_oni': village_oni_shop(oni)}


def export():
    """Saves the museum oni-san shop (plains) and the stall, alley and oni-san shop lot of every village type."""
    summary = []
    for style in STYLES:
        built = pieces(style)
        keep = ('stall', 'alley', 'village_oni') + (('oni',) if style == 'plains' else ())
        summary += [built[k].save() for k in keep]
    for s in summary: s['source'] = 'art/structures/human-world/author_street.py'
    return summary


if __name__ == '__main__': print(json.dumps(export(), ensure_ascii=False))
