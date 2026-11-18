"""Deterministic checks for the pet-shop template, run against the Building plan in build.py (no Minecraft needed).

Fails on: a furnishing write that replaced a different block without an explicit allow; an entity whose position cell or head cell is
solid or that has no solid floor; a pen whose bowl or nest was overwritten (or whose interior is smaller than declared); a covered
walkable floor cell below block light 8 (flood fill from lanterns/light blocks through non-opaque blocks); a door that is not 2 high;
staff that cannot be reached from the street door; a facade patch of one block larger than 6x6; a roof above 41% of facade height.
Usage: python check_structure.py   (also called by scripts/export_human_world.py --check)
"""
from collections import deque
import json, math
from pathlib import Path
import importlib.util, sys

AIR = 'minecraft:air'
# Blocks an entity (or a walking human) may share a cell with: no collision box.
FREE = {AIR, 'mcyukkuri:exam_mark', 'minecraft:light'}
def free(name): return name in FREE or name.endswith(('_sign', '_banner', '_carpet'))
# Blocks that let block light through. Furniture, stairs, slabs and every full cube count as opaque (a conservative estimate).
TRANSPARENT = {AIR, 'minecraft:glass', 'minecraft:glass_pane', 'minecraft:lantern', 'minecraft:chain', 'minecraft:light', 'minecraft:spruce_fence', 'minecraft:oak_leaves',
               'minecraft:jigsaw'}   # a jigsaw becomes air when the piece is placed
# Plants light passes through.
TRANSPARENT |= {'minecraft:' + n for n in ('short_grass', 'tall_grass', 'fern', 'large_fern', 'dead_bush', 'dandelion', 'poppy', 'cornflower',
                                           'oxeye_daisy', 'allium', 'flowering_azalea', 'azalea')}
def transparent(name):
    return (name in TRANSPARENT or name.startswith(('mcyukkuri:', 'minecraft:potted_')) or
            name.endswith(('_door', '_sign', '_banner', '_carpet', '_pressure_plate', '_trapdoor', '_fence', '_leaves')))
def source_level(name, props):
    if name == 'minecraft:light': return int(props.get('level', '15'))
    return {'minecraft:lantern': 15, 'minecraft:sea_lantern': 15, 'minecraft:glowstone': 15, 'minecraft:torch': 14, 'minecraft:ochre_froglight': 15, 'minecraft:shroomlight': 15}.get(name, 0)
def name_at(b, x, y, z): return b.blocks.get((x, y, z), (AIR,))[0]

def light_levels(b):
    """Block light per cell {(x,y,z): level}: multi-source flood fill, one level lost per step through non-opaque blocks."""
    sx, sy, sz = b.size; level = {}; queue = deque()
    for (x, y, z), (name, props, _) in b.blocks.items():
        v = source_level(name, props)
        if v: level[x, y, z] = v
    for v in range(15, 0, -1):
        for pos in [p for p, l in level.items() if l == v]: queue.append(pos)
    # Process levels from the brightest down so each cell receives its maximum.
    queue = deque(sorted(queue, key=lambda p: -level[p]))
    while queue:
        x, y, z = queue.popleft(); v = level[x, y, z]
        if v <= 1: continue
        for dx, dy, dz in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)):
            n = (x + dx, y + dy, z + dz)
            if not (0 <= n[0] < sx and 0 <= n[1] < sy and 0 <= n[2] < sz): continue
            if level.get(n, 0) >= v - 1 or not transparent(name_at(b, *n)): continue
            level[n] = v - 1; queue.append(n)
    return level

def walk_cells(b, y=3):
    """Walkable floor cells at the floor level: solid floor below, two free cells above, and a roof/awning over the column."""
    sx, sy, sz = b.size; out = []
    for x in range(1, sx - 1):
        for z in range(sz):
            floor = name_at(b, x, y - 1, z)
            if floor == AIR or not free(name_at(b, x, y, z)) and name_at(b, x, y, z) != 'mcyukkuri:exam_podium': continue
            if not free(name_at(b, x, y + 1, z)): continue
            if any(name_at(b, x, yy, z) != AIR and not transparent(name_at(b, x, yy, z)) for yy in range(y + 2, sy)): out.append((x, z))
    return out

def solid_cell_below(b, x, y, z): return name_at(b, x, y - 1, z) != AIR

# ---------------------------------------------------------------------------------------------------- light on every floor (all pieces)
# A stand cell has a supporting floor and two clear cells above it (a jigsaw counts as the air it becomes). Covered stand cells need
# block light >= 8; a stand cell on a sturdy floor (a full block that is not glass or leaves) needs >= 1, or monsters spawn there at
# night. Both apply at every height, roofs included; the cell just above the template is checked too (nothing can light it, so the
# top layer must not be a sturdy floor). Wind gaps need >= 1 as well.
PLANTS = {'minecraft:' + n for n in ('short_grass', 'tall_grass', 'fern', 'large_fern', 'dead_bush', 'poppy', 'dandelion')}
def state_at(b, x, y, z):
    name, props, _ = b.blocks.get((x, y, z), (AIR, {}, None))
    return (AIR, {}) if name == 'minecraft:jigsaw' else (name, props or {})
def clear(name, props): return free(name) or name in PLANTS or name == 'minecraft:snow' and props.get('layers') == '1'
def support(name, props):
    return name not in ('minecraft:water',) and not clear(name, props) and (
        full_block(name) and not name.endswith(('_fence_gate', '_sapling', ':snow')) or name.endswith(('_slab', '_stairs')) or name == 'minecraft:dirt_path')
def sturdy(name, props):
    return support(name, props) and full_block(name) and not name.endswith(('glass', '_leaves'))
def stand(b, x, y, z):
    return support(*state_at(b, x, y - 1, z)) and clear(*state_at(b, x, y, z)) and clear(*state_at(b, x, y + 1, z))
def covered(b, x, y, z): return any(support(*state_at(b, x, yy, z)) for yy in range(y + 2, b.size[1]))

def light_needs(b, level=None):
    """[(cell, required level)] of stand cells and wind gaps whose block light is too low."""
    level = light_levels(b) if level is None else level
    out = []; sx, sy, sz = b.size
    for x in range(sx):
        for z in range(sz):
            for y in range(1, sy + 1):
                if not stand(b, x, y, z): continue
                lv = level.get((x, y, z), 0)
                if covered(b, x, y, z):
                    if lv < 8: out.append(((x, y, z), 8))
                elif lv == 0 and sturdy(*state_at(b, x, y - 1, z)) and not name_at(b, x, y, z).endswith('_carpet'): out.append(((x, y, z), 1))
    for x, y, z in getattr(b, 'gaps', ()):
        if not level.get((x, y, z)): out.append(((x, y, z), 1))
    return out

def light_errors(b):
    needs = light_needs(b)
    return [f'{b.name}: {len(needs)} floor cells too dark (covered < 8 or open = 0), first {needs[:6]}'] if needs else []

def check(b):
    errors = []
    for pos, old, new in b.conflicts: errors.append(f'block overwrite at {pos}: {old} -> {new}')
    for ent in b.entities:
        nbt = ent['nbt'][1]; kind = nbt['id'][1]; x, y, z = ent['pos'][1][1]
        cx, cy, cz = int(x // 1), int(y // 1), int(z // 1)
        if kind == 'minecraft:marker': continue
        if kind == 'minecraft:item_display':
            if name_at(b, cx, cy, cz) != AIR: errors.append(f'item display inside a block at {(cx, cy, cz)}')
            continue
        head = cy + (1 if kind.startswith('mcyukkuri:caretaker') or kind.endswith('oniisan') else 0)
        for yy in sorted({cy, head}):
            if not free(name_at(b, cx, yy, cz)): errors.append(f'{kind} at {(x, y, z)}: cell {(cx, yy, cz)} is {name_at(b, cx, yy, cz)}')
        if not solid_cell_below(b, cx, cy, cz) or name_at(b, cx, cy - 1, cz) in ('minecraft:glass',): errors.append(f'{kind} at {(x, y, z)} has no floor')
    seen = reachable(b)
    for pen in b.pens:
        x0, z0, x1, z1 = pen['interior']; bx, bz = pen['bowl']; y = pen['y']
        if name_at(b, bx, y, bz) != 'mcyukkuri:food_bowl': errors.append(f"pen {pen['name']}: bowl {(bx, y, bz)} is {name_at(b, bx, y, bz)}")
        for nx, nz in pen['nests']:
            if not name_at(b, nx, y, nz).endswith('nest'): errors.append(f"pen {pen['name']}: nest {(nx, y, nz)} is {name_at(b, nx, y, nz)}")
        if x1 - x0 + 1 < pen['min'][0] or z1 - z0 + 1 < pen['min'][1]: errors.append(f"pen {pen['name']} interior {x1 - x0 + 1}x{z1 - z0 + 1} below {pen['min']}")
        for x in range(x0, x1 + 1):
            for z in range(z0, z1 + 1):
                if name_at(b, x, y - 1, z) == AIR or name_at(b, x, y + 1, z) not in FREE: errors.append(f"pen {pen['name']}: cell {(x, z)} lacks floor or headroom")
        # Shop pens (those with a staff door) have walls from the floor to the ceiling height (y .. y+4): no hop clears them and nothing
        # is handed over them. The exam wing's low trainee and mock-room walls are not sale pens.
        ring = [(x, z) for x in range(x0 - 1, x1 + 2) for z in (z0 - 1, z1 + 1)] + [(x, z) for x in (x0 - 1, x1 + 1) for z in range(z0, z1 + 1)]
        for x, z in ring if pen['hatch'] else ():
            if any(name_at(b, x, yy, z) == AIR for yy in range(y, y + 5)):
                errors.append(f"pen {pen['name']}: wall at {(x, z)} is open below y{y + 5}")
        # The predator room is sealed overhead too: a flying predator cannot leave it.
        if pen['name'] == 'predator' and any(name_at(b, x, y + 5, z) == AIR for x in range(x0, x1 + 1) for z in range(z0, z1 + 1)):
            errors.append('pen predator: open ceiling')
        if pen['hatch']: errors += hatch_errors(b, pen, seen)
    level = light_levels(b)
    dark = [(x, z, level.get((x, 3, z), 0)) for x, z in walk_cells(b) if level.get((x, 3, z), 0) < 8]
    if dark: errors.append(f'{len(dark)} walkable cells below light 8, first {dark[:6]}')
    for (x, y, z) in b.doors:
        up = b.blocks.get((x, y + 1, z)); lo = b.blocks.get((x, y, z))
        if not (lo and up and lo[0].endswith('_door') and up[0] == lo[0] and lo[1].get('half') == 'lower' and up[1].get('half') == 'upper' and lo[1].get('facing') == up[1].get('facing')):
            errors.append(f'door at {(x, y, z)} is not a matching 2-high pair')
    for (x, y, z), (name, props, _) in b.blocks.items():
        if name.endswith('_door') and props.get('half') == 'lower' and (x, y, z) not in b.doors: errors.append(f'door at {(x, y, z)} not registered')
    errors += reachability(b)
    errors += facade_patches(b)
    p = b.palette
    errors += roof_patches(b, skip='minecraft:' + p.wall_light)
    if not p.flat:   # a flat roof adds no height above the 6-row facade
        tiles = [y for (x, y, z), (n, _, _) in b.blocks.items() if n in (f'minecraft:{p.roof}_slab', f'minecraft:{p.roof}_stairs')]
        rows = max(tiles) - 9 + 1; ratio = rows / (6 + rows)
        if ratio > 0.41: errors.append(f'roof is {ratio:.0%} of facade height')
    return [f'{b.name} ({p.style}): {e}' for e in errors]

def reachable(b):
    """Floor cells staff reach on foot (2-high clearance, doors opened on the way) from the pavement in front of the shop door.
    Staff on a job open the iron pen doors too (shop/ShopStaff)."""
    def ok(x, z):
        if not (0 <= x < b.size[0] and 0 <= z < b.size[2]): return False
        if name_at(b, x, 2, z) == AIR: return False
        def passable(y): n = name_at(b, x, y, z); return free(n) or n.endswith('_door') or n == 'mcyukkuri:exam_podium'
        return passable(3) and passable(4)
    start = (12, 2); seen = {start}; queue = deque([start])
    while queue:
        x, z = queue.popleft()
        for n in ((x + 1, z), (x - 1, z), (x, z + 1), (x, z - 1)):
            if n not in seen and ok(*n): seen.add(n); queue.append(n)
    return seen

def hatch_errors(b, pen, seen):
    """A pen's staff door: a 2-high iron door in its wall ring, one clear interior cell inside it, and outside it a floor cell staff walk to."""
    x0, z0, x1, z1 = pen['interior']; y = pen['y']; hx, hz = pen['hatch']; name = pen['name']
    if name_at(b, hx, y, hz) != 'minecraft:iron_door' or name_at(b, hx, y + 1, hz) != 'minecraft:iron_door':
        return [f'pen {name}: no 2-high iron service door at {(hx, y, hz)}']
    inside = [(hx + dx, hz + dz) for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1)) if x0 <= hx + dx <= x1 and z0 <= hz + dz <= z1]
    if len(inside) != 1: return [f'pen {name}: service door {(hx, hz)} is not in its wall ring']
    ix, iz = inside[0]
    if name_at(b, ix, y, iz) != AIR or name_at(b, ix, y + 1, iz) != AIR:
        return [f"pen {name}: inside service stand {(ix, y, iz)} is obstructed"]
    inward = [(ix + dx, iz + dz) for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))
              if x0 <= ix + dx <= x1 and z0 <= iz + dz <= z1]
    if not any(name_at(b, x, y, z) == AIR and name_at(b, x, y + 1, z) == AIR for x, z in inward):
        return [f"pen {name}: inside service stand {(ix, y, iz)} has no clear exit into the pen"]
    ox, oz = 2 * hx - ix, 2 * hz - iz
    if name_at(b, ox, y, oz) != AIR: return [f'pen {name}: service door {(hx, hz)} opens onto {name_at(b, ox, y, oz)}']
    if (ox, oz) not in seen: return [f'pen {name}: staff cannot walk to its service door stand {(ox, oz)}']
    return []

def reachability(b):
    """Staff must be reachable on foot from the pavement in front of the shop door."""
    seen = reachable(b)
    errors = []
    for ent in b.entities:
        nbt = ent['nbt'][1]
        if nbt['id'][1].startswith('mcyukkuri:caretaker'):
            x, y, z = ent['pos'][1][1]
            if (int(x // 1), int(z // 1)) not in seen: errors.append(f'staff {nbt["SiteRole"][1]} at {(x, z)} unreachable from the street door')
    return errors

def facade_patches(b, limit=6):
    """No connected patch of one block type larger than 6x6 on the four outer wall planes (y 3..12)."""
    return plane_patches(b, (('z=3', [(x, y, 3) for x in range(1, 40) for y in range(3, 13)]), ('z=30', [(x, y, 30) for x in range(1, 40) for y in range(3, 13)]),
                             ('x=1', [(1, y, z) for z in range(3, 31) for y in range(3, 13)]), ('x=39', [(39, y, z) for z in range(3, 31) for y in range(3, 13)])), limit)

def plane_patches(b, planes, limit=6):
    """No connected patch of one block type larger than limit x limit on the given wall planes [(label, cells)]; a label 'z=..' is a
    plane of constant z (its width runs along x), 'x=..' one of constant x."""
    errors = []
    for plane, cells in planes:
        todo = set(cells); name = lambda p: name_at(b, *p)
        axis = 0 if plane.startswith('z') else 2
        while todo:
            seed = todo.pop(); kind = name(seed); comp = [seed]; queue = deque([seed])
            while queue:
                p = queue.popleft()
                for d in ((1, 0, 0), (-1, 0, 0), (0, 1, 0), (0, -1, 0), (0, 0, 1), (0, 0, -1)):
                    n = (p[0] + d[0], p[1] + d[1], p[2] + d[2])
                    if n in todo and name(n) == kind: todo.discard(n); comp.append(n); queue.append(n)
            if kind == AIR: continue
            width = max(p[axis] for p in comp) - min(p[axis] for p in comp) + 1; height = max(p[1] for p in comp) - min(p[1] for p in comp) + 1
            if width > limit and height > limit: errors.append(f'{plane}: {kind} patch {width}x{height}')
    return errors

def roof_patches(b, limit=6, skip='minecraft:smooth_quartz'):
    """Roof planes seen from above: no window of one block family larger than 6x6 (trim, ridge cap, seams and skylights break the
    slopes). `skip` is the pale wall of the gables and the false front."""
    top = {}
    for (x, y, z), (n, _, _) in b.blocks.items():
        if y >= 9 and n not in ('minecraft:light', skip) and top.get((x, z), (0,))[0] < y: top[x, z] = (y, n.replace('_slab', '').replace('_stairs', ''))
    errors = []; n = limit + 1
    for (x, z), (_, kind) in sorted(top.items()):   # any 7x7 window of one block family is a patch larger than 6x6
        if all(top.get((x + i, z + j), (0, None))[1] == kind for i in range(n) for j in range(n)): errors.append(f'roof: {kind} patch at x{x} z{z}'); break
    return errors

def load_build():
    path = Path(__file__).parent / 'build.py'
    sys.path.insert(0, str(path.parent))
    spec = importlib.util.spec_from_file_location('build_human_structures_check', path); m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
    return m

def styled_errors(b):
    """The light rule on every floor and the material set of the piece's village type."""
    from palette import violations
    errors = light_errors(b)
    foreign = violations(b, b.palette.style)
    if foreign: errors.append(f'{b.name}: blocks outside the {b.palette.style} palette: {", ".join(foreign)}')
    return errors

def check_shop():
    build = load_build(); errors = []
    for style in build.STYLES:
        shop = build.shop(build.PALETTES[style]); errors += check(shop) + styled_errors(shop)
    return errors

# ---------------------------------------------------------------------------------------------------- street pieces and the oni-san shop
# Run against author_street.py plans. Fails on: overwrites; entities in blocks or without floor; pens as above (walls closed to the ceiling,
# 2-high iron service door with clear stands, staff reach the outside stand); oni-shop covered floor below light 8; doors; staff unreachable
# from the street door; a 1-wide corridor cell in the oni shop (two staff, or staff and stock, would jam in it); a garbage point, wind gap
# or shelter spot that the stray systems would not accept or a stray cannot walk to from the lane; a street connector off the lane centre.
PARTIAL = ('_slab', '_stairs', '_fence', '_wall', '_trapdoor', '_pane', 'iron_bars', 'lantern', 'dirt_path', 'composter', 'cauldron',
           'lectern', 'grindstone', 'brewing_stand', 'hopper', '_bed', 'chain', 'cobweb', 'jigsaw', '_door')
def full_block(name): return name != AIR and not free(name) and not name.startswith(('mcyukkuri:', 'minecraft:potted_')) and not name.endswith(PARTIAL)

def reach_from(b, start, y, body=2, doors=True):
    """Cells reached on foot at walking level y (floor at y-1, `body` free cells above it; doors opened on the way)."""
    def passable(x, yy, z):
        n, _ = state_at(b, x, yy, z); return free(n) or (doors and n.endswith('_door'))
    def ok(x, z):
        return 0 <= x < b.size[0] and 0 <= z < b.size[2] and name_at(b, x, y - 1, z) != AIR and all(passable(x, y + i, z) for i in range(body))
    seen = {start} if ok(*start) else set(); queue = deque(seen)
    while queue:
        x, z = queue.popleft()
        for n in ((x + 1, z), (x - 1, z), (x, z + 1), (x, z - 1)):
            if n not in seen and ok(*n): seen.add(n); queue.append(n)
    return seen

def entity_errors(b):
    errors = []
    for ent in b.entities:
        nbt = ent['nbt'][1]; kind = nbt['id'][1]; x, y, z = ent['pos'][1][1]
        cx, cy, cz = int(x // 1), int(y // 1), int(z // 1)
        if kind == 'minecraft:marker': continue
        if kind == 'minecraft:item_display':
            name, props, _ = b.blocks.get((cx, cy, cz), (AIR, {}, None))
            if name != AIR and not (name.endswith('_slab') and props['type'] == 'bottom' and y >= cy + .5):
                errors.append(f'{b.name}: item display inside a block at {(x, y, z)}')
            continue
        head = cy + (1 if kind.startswith('mcyukkuri:caretaker') or kind.endswith('oniisan') else 0)
        for yy in sorted({cy, head}):
            if not free(name_at(b, cx, yy, cz)): errors.append(f'{b.name}: {kind} at {(x, y, z)}: cell {(cx, yy, cz)} is {name_at(b, cx, yy, cz)}')
        if name_at(b, cx, cy - 1, cz) in (AIR, 'minecraft:glass'): errors.append(f'{b.name}: {kind} at {(x, y, z)} has no floor')
    return errors

def pen_errors(b, seen):
    errors = []
    for pen in b.pens:
        x0, z0, x1, z1 = pen['interior']; bx, bz = pen['bowl']; y = pen['y']; name = f"{b.name} pen {pen['name']}"
        if name_at(b, bx, y, bz) != 'mcyukkuri:food_bowl': errors.append(f'{name}: bowl {(bx, y, bz)} is {name_at(b, bx, y, bz)}')
        for nx, nz in pen['nests']:
            if not name_at(b, nx, y, nz).endswith('nest'): errors.append(f'{name}: nest {(nx, y, nz)} is {name_at(b, nx, y, nz)}')
        if x1 - x0 + 1 < pen['min'][0] or z1 - z0 + 1 < pen['min'][1]: errors.append(f"{name} interior below {pen['min']}")
        for x in range(x0, x1 + 1):
            for z in range(z0, z1 + 1):
                if name_at(b, x, y - 1, z) == AIR or name_at(b, x, y + 1, z) not in FREE: errors.append(f'{name}: cell {(x, z)} lacks floor or headroom')
        ring = [(x, z) for x in range(x0 - 1, x1 + 2) for z in (z0 - 1, z1 + 1)] + [(x, z) for x in (x0 - 1, x1 + 1) for z in range(z0, z1 + 1)]
        for x, z in ring:
            if any(name_at(b, x, yy, z) == AIR for yy in range(y, y + 5)): errors.append(f'{name}: wall at {(x, z)} is open below y{y + 5}')
        if pen['hatch']: errors += [f'{b.name}: {e}' for e in hatch_errors(b, pen, seen)]
    return errors

def door_errors(b):
    errors = []
    thin = ('minecraft:iron_bars', 'minecraft:glass_pane')
    clockwise = {'north': (1, 0), 'east': (0, 1), 'south': (-1, 0), 'west': (0, -1)}
    for (x, y, z) in b.doors:
        name, lo = state_at(b, x, y, z)
        upper, up = state_at(b, x, y + 1, z)
        if not (name.endswith('_door') and name == upper and lo.get('half') == 'lower' and up == {**lo, 'half': 'upper'}):
            errors.append(f'{b.name}: door at {(x, y, z)} is not a matching 2-high pair'); continue
        if name == 'minecraft:iron_door':
            dx, dz = clockwise[lo['facing']]
            other_hinge = 'right' if lo['hinge'] == 'left' else 'left'
            if other_hinge == 'left': dx, dz = -dx, -dz
            for sx, sz in ((x + dx, z + dz), (x - dx, z - dz)):
                for yy in (y, y + 1):
                    side, props = state_at(b, sx, yy, sz)
                    paired = (sx, sz) == (x + dx, z + dz) and side == name and props == {
                        **b.blocks[x, yy, z][1], 'hinge': other_hinge}
                    if not paired and not full_block(side):
                        errors.append(f'{b.name}: iron door {(x, yy, z)} is not framed at {(sx, yy, sz)} ({side})')
            if not full_block(name_at(b, x, y + 2, z)):
                errors.append(f'{b.name}: iron door {(x, y, z)} lacks a full lintel at {(x, y + 2, z)} ({name_at(b, x, y + 2, z)})')
        else:
            for dx, dy, dz in ((1, 0, 0), (-1, 0, 0), (0, 0, 1), (0, 0, -1), (0, 2, 0)):
                if name_at(b, x + dx, y + dy, z + dz) in thin:
                    errors.append(f'{b.name}: door {(x, y, z)} touches thin pane/bars at {(x + dx, y + dy, z + dz)}')
    for (x, y, z), (name, props, _) in b.blocks.items():
        if name.endswith('_door') and props.get('half') == 'lower' and (x, y, z) not in b.doors: errors.append(f'{b.name}: door at {(x, y, z)} not registered')
    return errors

def corridor_errors(b, y, x0, z0, x1, z1, skip=()):
    """No 1-wide corridor cell: a walkable cell open on two opposite sides and closed on the other two. `skip`: cells that are not staff
    passages (the inside of yukkuri boxes)."""
    def passable(x, yy, z): n = name_at(b, x, yy, z); return free(n) or n.endswith('_door')
    walk = {(x, z) for x in range(x0, x1 + 1) for z in range(z0, z1 + 1)
            if (x, z) not in skip and name_at(b, x, y - 1, z) != AIR and passable(x, y, z) and passable(x, y + 1, z)}
    out = []
    for x, z in sorted(walk):
        n, s, e, w = (x, z - 1) in walk, (x, z + 1) in walk, (x + 1, z) in walk, (x - 1, z) in walk
        if (n and s and not e and not w) or (e and w and not n and not s): out.append((x, z))
    return [f'{b.name}: 1-wide corridor cells {out[:8]}'] if out else []

STEPS = ((1, 0), (-1, 0), (0, 1), (0, -1))


def stray_errors(b, lane):
    """Garbage points, wind gaps and shelter spots as the stray systems read them, each reachable by a stray (1-high body) from the lane."""
    errors = []
    strays = reach_from(b, lane, 1, body=1, doors=False)
    for (x, yy, z), facing, (sx, sy, sz) in b.garbage:
        state = b.blocks.get((x, yy, z))
        if not state or state[0] != 'mcyukkuri:garbage_bin' or state[1].get('facing') != facing or 'Items' not in (state[2] or {}):
            errors.append(f'{b.name}: garbage {(x, yy, z)} is not a stocked garbage bin facing {facing}')
            continue
        if not free(name_at(b, sx, sy, sz)) or not full_block(name_at(b, sx, sy - 1, sz)): errors.append(f'{b.name}: garbage stand {(sx, sy, sz)} is not a dry floor cell')
        if (sx, sz) not in strays: errors.append(f'{b.name}: garbage stand {(sx, sz)} unreachable from the lane')
        if state[1].get('tipped') == 'false':
            # A standing bin needs a side to be shoved from whose far side is open, and that far cell is then its mouth stand.
            shoves = [(dx, dz) for dx, dz in STEPS if (x + dx, z + dz) in strays and free(name_at(b, x - dx, yy, z - dz))
                      and full_block(name_at(b, x - dx, yy - 1, z - dz)) and (x - dx, z - dz) in strays]
            if not shoves: errors.append(f'{b.name}: standing garbage {(x, yy, z)} cannot be shoved over toward a reachable mouth')
    for x, yy, z in b.gaps:
        above = name_at(b, x, yy + 1, z)
        if not free(name_at(b, x, yy, z)) or not full_block(name_at(b, x, yy - 1, z)): errors.append(f'{b.name}: wind gap {(x, yy, z)} is not a free floor cell')
        if above == AIR or free(above): errors.append(f'{b.name}: wind gap {(x, yy, z)} has nothing over it')
        if not any(full_block(name_at(b, x + dx, yy, z + dz)) for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))): errors.append(f'{b.name}: wind gap {(x, yy, z)} has no wall')
        if (x, z) not in strays: errors.append(f'{b.name}: wind gap {(x, z)} unreachable from the lane')
    for (x, yy, z), facing in b.shelters:
        for dx in (-1, 0, 1):
            for dz in (-1, 0, 1):
                if dz if facing in ('north', 'south') else dx:
                    continue
                for dy in (0, 1):
                    part = dx + 2 + 5 * (dz + 2) + 25 * dy
                    state = b.blocks.get((x + dx, yy + dy, z + dz))
                    if not state or state[0] != 'mcyukkuri:cardboard_shelter' or state[1] != {'facing': facing, 'part': str(part)} or bool(state[2]) != (part == 12):
                        errors.append(f'{b.name}: carton has an invalid shell part at {(x + dx, yy + dy, z + dz)}')
                if not full_block(name_at(b, x + dx, yy - 1, z + dz)): errors.append(f'{b.name}: carton spot {(x, z)} floor {(x + dx, z + dz)} is not sturdy')
        ex, ez = x + {'east': 1, 'west': -1}.get(facing, 0), z + {'south': 1, 'north': -1}.get(facing, 0)
        if not free(name_at(b, ex, yy, ez)) or (ex, ez) not in strays: errors.append(f'{b.name}: carton spot {(x, z)} entrance {(ex, ez)} blocked or unreachable')
    for (x, yy, z), facing, name, target, pool in b.jigsaws:
        edge = {'west': x == 0, 'east': x == b.size[0] - 1, 'north': z == 0}.get(facing, False)
        if not edge or yy != 1 or (facing != 'north' and z != 4) or name_at(b, x, 0, z) == AIR:
            errors.append(f'{b.name}: connector {(x, yy, z)} {facing} is off the lane edge')
    return errors

def check_site(b, door=None, region=None):
    """door: a pavement cell staff start from (oni shop); region: x0, z0, x1, z1 of the interior checked for light and corridors."""
    errors = [f'{b.name}: block overwrite at {pos}: {old} -> {new}' for pos, old, new in b.conflicts]
    errors += entity_errors(b) + door_errors(b)
    y = b.floor
    seen = reach_from(b, door, y) if door else set()
    errors += pen_errors(b, seen)
    for ent in b.entities:
        nbt = ent['nbt'][1]
        if door and 'SiteRole' in nbt:
            x, _, z = ent['pos'][1][1]
            if (int(x // 1), int(z // 1)) not in seen: errors.append(f'{b.name}: staff {nbt["SiteRole"][1]} at {(x, z)} unreachable from the street door')
    if region:
        level = light_levels(b); x0, z0, x1, z1 = region
        dark = [(x, z, level.get((x, y, z), 0)) for x, z in walk_cells(b, y) if x0 <= x <= x1 and z0 <= z <= z1 and level.get((x, y, z), 0) < 8]
        if dark: errors.append(f'{b.name}: {len(dark)} walkable cells below light 8, first {dark[:6]}')
        errors += corridor_errors(b, y, *region)
    return errors

def stock_nest_clearance(b):
    """A stock body's feet must clear the cushion rolls extending beyond their own block."""
    path = Path(__file__).resolve().parents[3] / 'src/main/resources/assets/mcyukkuri/yukkuri_presentation/care_collision.json'
    rolls = json.loads(path.read_text(encoding='utf-8'))['pet_nest_0']['boxes']
    nests = [(x + .5, y, z + .5) for (x, y, z), (name, _, _) in b.blocks.items() if name == 'mcyukkuri:yukkuri_nest']
    errors = []
    for ent in b.entities:
        nbt = ent['nbt'][1]
        custody = nbt.get('NeoForgeData', (0, {}))[1].get('HumanCommerce', (0, {}))[1]
        if custody.get('Use', (0, ''))[1] != 'stock': continue
        x, y, z = ent['pos'][1][1]
        radius = .4  # Ordinary adult body width in the template; young are smaller.
        for nx, ny, nz in nests:
            if y != ny or abs(x - nx) > 1.3 or abs(z - nz) > 1.3: continue
            if any(x + radius > nx + left / 16 and x - radius < nx + right / 16
                   and z + radius > nz + front / 16 and z - radius < nz + back / 16
                   for left, front, right, back, _ in rolls):
                errors.append(f'{b.name}: stock at {(x, y, z)} overlaps the nest at {(nx, ny, nz)}')
    return errors

def load_street():
    path = Path(__file__).parent / 'author_street.py'
    sys.path.insert(0, str(path.parent))
    spec = importlib.util.spec_from_file_location('author_street_check', path); m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
    return m

def resident_errors(b, lane):
    """Street residents (job 0) live indoors: their home cell is covered and reached from the lane only through a wooden door. The stall
    keepers' home is their booth behind the counter: covered and closed to passers-by the same way."""
    errors = []
    walk_in = reach_from(b, lane, 1); passers = reach_from(b, lane, 1, doors=False)
    for ent in b.entities:
        nbt = ent['nbt'][1]
        if nbt.get('HumanJob', (0, -1))[1] != 0: continue
        x, y, z = (int(v // 1) for v in ent['pos'][1][1])
        if (x, z) not in walk_in or (x, z) in passers or not covered(b, x, y, z):
            errors.append(f'{b.name}: resident home {(x, y, z)} is not an indoor cell behind a door')
    return errors

# ShopRegistry.authoredProtection for the oni shop: the protected box relative to the clerk's post (unrotated template).
ONI_PROTECTION = ((-9, -3, -16), (15, 11, 7))

def stress_room_errors(b, room):
    """The oni shop's stress room (author_street.stress_room): its interior (between the two mcy_oni_room markers) lies wholly outside the
    shop's protection box, on a dry full-block floor with a clear 3x3 middle, lit and reached on foot from the street door; its door and the
    customer's stand at the lending tray see each other at eye level; the tray holds the restraint frame."""
    errors = []; tags = {}
    for ent in b.entities:
        nbt = ent['nbt'][1]; x, y, z = ent['pos'][1][1]
        if nbt['id'][1] == 'minecraft:marker': tags.setdefault(nbt['Tags'][1][1][0], []).append((int(x // 1), int(y // 1), int(z // 1)))
        elif nbt.get('SiteRole', (0, ''))[1] == 'oni_shopkeeper': clerk = (int(x // 1), int(y // 1), int(z // 1))
    rooms, lend = tags.get('mcy_oni_room', []), tags.get('mcy_oni_lend', [])
    if len(rooms) != 2 or len(lend) != 1: return [f'{b.name}: stress room needs 2 mcy_oni_room and 1 mcy_oni_lend markers']
    (lo, hi) = [tuple(c + o for c, o in zip(clerk, off)) for off in ONI_PROTECTION]
    (ax, y, az), (bx, _, bz) = rooms
    cells = [(x, z) for x in range(min(ax, bx), max(ax, bx) + 1) for z in range(min(az, bz), max(az, bz) + 1)]
    inside = [c for c in cells if lo[0] <= c[0] <= hi[0] and lo[2] <= c[1] <= hi[2]]
    if inside: errors.append(f'{b.name}: stress room cells {inside[:4]} inside the shop protection {lo}..{hi}')
    wet = [c for c in cells if not full_block(name_at(b, c[0], y - 1, c[1])) or 'water' in name_at(b, c[0], y - 1, c[1])
           or name_at(b, c[0], y, c[1]) == 'minecraft:water']
    if wet: errors.append(f'{b.name}: stress room floor not a dry full block at {wet[:4]}')
    cx = sorted({x for x, _ in cells})[len({x for x, _ in cells}) // 2]; cz = sorted({z for _, z in cells})[len({z for _, z in cells}) // 2]
    blocked = [(x, z) for x in range(cx - 1, cx + 2) for z in range(cz - 1, cz + 2) if not (free(name_at(b, x, y, z)) and free(name_at(b, x, y + 1, z)))]
    if blocked: errors.append(f'{b.name}: stress room middle 3x3 not clear at {blocked}')
    walk = reach_from(b, (room + 11, 1), y)
    if any(c not in walk for c in cells if free(name_at(b, c[0], y, c[1]))): errors.append(f'{b.name}: stress room not reached on foot from the street door')
    level = light_levels(b)
    dark = [c for c in cells if free(name_at(b, c[0], y, c[1])) and level.get((c[0], y, c[1]), 0) < 8]
    if dark: errors.append(f'{b.name}: stress room cells below light 8: {dark[:4]}')
    # Eye line (feet + 1.6) from the customer's stand in front of the tray to each door leaf, sampled finely; doors, bars and panes see through.
    tx, ty, tz = lend[0]; tray = b.blocks.get((tx, ty, tz))
    if not tray or tray[0] != 'mcyukkuri:display_tray' or 'restraint_frame' not in str(tray[2]): errors.append(f'{b.name}: lending tray at {lend[0]} holds no restraint frame')
    doors = [(x, z) for x, yy, z in b.doors if yy == y and x == room + 1]
    if len(doors) != 2: errors.append(f'{b.name}: stress room door is not a 2-wide pair in the shop wall'); return errors
    see = lambda n: free(n) or n.endswith(('_door', '_pane', 'iron_bars', 'lantern'))
    for dx, dz in doors:
        a, c = (tx + .5, tz - .5), (dx + .5, dz + .5); steps = 64
        for i in range(1, steps):
            px, pz = a[0] + (c[0] - a[0]) * i / steps, a[1] + (c[1] - a[1]) * i / steps
            n = name_at(b, int(px // 1), y + 1, int(pz // 1))
            if not see(n): errors.append(f'{b.name}: tray stand cannot see the stress room door {(dx, dz)}: {n} at {(int(px), int(pz))}'); break
    return errors

def check_street():
    street = load_street(); errors = []
    for style in street.STYLES:
        built = street.pieces(style)
        for key in ('stall', 'alley'):
            b = built[key]; errors += check_site(b) + stray_errors(b, (1, 4)) + resident_errors(b, (1, 4)) + styled_errors(b)
        room = street.ROOM   # the stress room's columns west of the shop (template x = shop x + ROOM); the region covers both
        errors += check_site(built['oni'], door=(room + 11, 1), region=(1, 4, room + 22, 23)) + styled_errors(built['oni'])
        errors += stress_room_errors(built['oni'], room)
        errors += stock_nest_clearance(built['oni'])
        errors += stray_errors(built['village_oni'], (1, 4)) + styled_errors(built['village_oni'])
        lot = street.build.village_shop(street.build.shop(street.PALETTES[style]))
        lot.floor = 1
        errors += stray_errors(lot, (1, 4)) + styled_errors(lot)
    return errors

# ---------------------------------------------------------------------------------------------------- the processing plant
# Run against author_plant.py. Fails on: overwrites; entities in blocks; doors; light on every floor; a vanilla villager workstation (the
# plant stands at a village edge); a block outside the plant's material set; a facade patch larger than 6x6; a 1-wide corridor cell;
# staff unable to walk from the main door to every box, pen, booth and room door and to every reserved floor; a box, the sick pen or the
# receiving booth whose ring is open below its lid (a yukkuri steps 1 block, so every ring cell is closed up to the lid) or whose lid has
# a gap; two neighbouring boxes not sharing exactly one glass wall; a box without its bowl, toilet and nest; a box that the largest
# admitted body cannot stand in or walk into; reserved floors that are not empty walkable floor; boundary corners that do not contain
# the plant's walls.
WORKSTATIONS = {'minecraft:' + n for n in ('barrel', 'lectern', 'composter', 'smoker', 'blast_furnace', 'cartography_table', 'brewing_stand',
                                           'cauldron', 'water_cauldron', 'fletching_table', 'grindstone', 'loom', 'smithing_table', 'stonecutter')}
PLANT_BLOCKS = {'minecraft:' + n for n in (
    'air', 'light', 'lantern', 'chain', 'ochre_froglight', 'glass', 'glass_pane', 'white_concrete', 'yellow_concrete',
    'stone_bricks', 'stone_brick_wall', 'stone_brick_stairs', 'stone_brick_slab', 'polished_andesite', 'polished_andesite_stairs', 'polished_andesite_slab', 'smooth_stone',
    'smooth_stone_slab', 'andesite', 'stone', 'gravel', 'waxed_copper_door', 'chest', 'hay_block', 'birch_wall_sign', 'potted_fern', 'lever')}
# The largest body a sweet box admits: an adult deibu (formScale 1.5) at the top adult scale 1.2 -- hitbox 0.8 x 0.6 per unit growth
# (body_geometry.json) -> 1.44 wide, 1.08 high; its rounded body surface is as wide (12.8 units). The widest drawn model is the wasa
# deibu (reimu_wasa_deibu) at 1.8: 2.41 across its hair, which WallYield presses onto any face nearer than that (measured with the pose
# core between two faces 0.8125 from the axis: the loose hair ends at 0.8125, the body surface at 0.72). The tallest is an adult Marisa
# at 1.2 with her hat, 1.60 high (21.28 model units). A body this wide needs a 2 x 2 free square, this tall two free cells over the
# floor; the wasa hair standing unyielded needs a 3 x 3 free square (the wide box).
LARGEST = {'width': 1.44, 'drawn_width': 2.41, 'yielded_half': .8125, 'height': 1.60}
ORDINARY_ADULT = .96            # a non-deibu adult at the top adult scale 1.2
DEIBU_PER_SCALE = .8 * 1.5      # deibu hitbox width per unit adult scale
# Each open copper leaf occupies 3/16 of the two-block opening.
DOOR_CLEAR = 2 - 2 * 3 / 16
# Pet nest rules (life/NestResting.CUSHION, life/PetNestBlock, life/NestDoorway, motor/surface/Occupancy, life/SurfaceApproach):
# a round bed (no side pressed flat) keeps its 11.5/16 level pad; a body enters through the low lip when the lip's half width covers
# foot(width) = 0.5 width + 0.02 plus the 0.02 arrival allowance.
NEST_PAD, FOOT_MARGIN, ARRIVAL = 11.5 / 16, .02, .02

def nest_opening(mask=0):
    """Half width of a pet nest's low lip (NestDoorway.shape) from the authored collision cells, entrance north, level pad 11.5/16."""
    raw = json.loads((Path(__file__).resolve().parents[3] / 'src/main/resources/assets/mcyukkuri/yukkuri_presentation/care_collision.json')
                     .read_text(encoding='utf-8'))[f'pet_nest_{mask}']['boxes']
    boxes = [(b[0] / 16, b[2] / 16, -b[3] / 16, -b[1] / 16, b[4] / 16) for b in raw]   # lateral x0, x1, depth d0, d1 toward the entrance, top
    top = max(b[4] for b in boxes); middle = max(b[4] for b in boxes if b[0] <= 0 <= b[1] and b[2] <= 0 <= b[3])
    threshold = (middle + top) / 2
    crest = lambda x: max([b[4] for b in boxes if b[0] <= x <= b[1] and b[3] >= NEST_PAD] or [0])
    opening, x = 0, 0
    while x < NEST_PAD and crest(x) <= threshold and crest(-x) <= threshold: opening = x; x += 1 / 64
    return opening

def nest_admits():
    """The widest body a free-standing (round) pet nest takes: it must enter through the lip and rest on the level pad."""
    return min(2 * NEST_PAD, 2 * (nest_opening() - FOOT_MARGIN - ARRIVAL))

def ring_cells(x0, z0, x1, z1):
    return [(x, z) for x in range(x0 - 1, x1 + 2) for z in (z0 - 1, z1 + 1)] + [(x, z) for x in (x0 - 1, x1 + 1) for z in range(z0, z1 + 1)]

def enclosure_errors(b, box, seen):
    """A box, the sick pen or the booth: closed ring up to the lid, a whole lid, doors with clear stands, contents, the largest body."""
    errors = []; x0, z0, x1, z1 = box['interior']; top = box['top']; name = f"{b.name} {box['kind']} {box['id']}"
    for x, z in ring_cells(x0, z0, x1, z1):
        start_y = 5 if name_at(b, x, 3, z).endswith('_door') else 3
        open_at = [y for y in range(start_y, top) if free(name_at(b, x, y, z))]
        if open_at: errors.append(f'{name}: wall {(x, z)} open at y{open_at}')
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            if free(name_at(b, x, top, z)): errors.append(f'{name}: lid open over {(x, z)}')
            if name_at(b, x, 2, z) == AIR: errors.append(f'{name}: no floor at {(x, z)}')
    for (lx, lz), (ox, oz), (ix, iz) in box['doors']:
        along = (1, 0) if lx == ix else (0, 1)
        facing = {(0, -1): 'north', (0, 1): 'south', (1, 0): 'east', (-1, 0): 'west'}[ox - lx, oz - lz]
        hinges = ('left', 'right') if facing in ('north', 'east') else ('right', 'left')
        for (dx, dz), hinge in zip(((0, 0), along), hinges):
            leaf = (lx + dx, lz + dz)
            outside = (ox + dx, oz + dz)
            inside = (ix + dx, iz + dz)
            states = [state_at(b, leaf[0], y, leaf[1]) for y in (3, 4)]
            if any(block != 'minecraft:waxed_copper_door' for block, _ in states):
                errors.append(f'{name}: missing waxed copper door leaf at {leaf}')
            elif any((props['facing'], props['hinge']) != (facing, hinge) for _, props in states):
                errors.append(f'{name}: door leaf at {leaf} must face {facing} with its {hinge} hinge on the outside')
            if outside not in seen: errors.append(f'{name}: staff cannot reach the door stand {outside}')
            if any(not free(name_at(b, inside[0], y, inside[1])) for y in (3, 4)):
                errors.append(f'{name}: inside door stand {inside} blocked')
    if box['kind'] == 'booth': return errors
    for key, suffix in (('bowl', 'food_bowl'), ('toilet', 'yukkuri_toilet'), ('nest', 'nest')):
        if key in box and not name_at(b, box[key][0], 3, box[key][1]).endswith(suffix): errors.append(f'{name}: no {key} at {box[key]}')
    # The largest body: free cells of the interior two high (it walks in through the door), a 2x2 free square among those it reaches.
    cells = {(x, z) for x in range(x0, x1 + 1) for z in range(z0, z1 + 1) if free(name_at(b, x, 3, z)) and free(name_at(b, x, 4, z))}
    start = [(ix, iz) for _, _, (ix, iz) in box['doors']]
    reached = set(c for c in start if c in cells); queue = deque(reached)
    while queue:
        x, z = queue.popleft()
        for n in ((x + 1, z), (x - 1, z), (x, z + 1), (x, z - 1)):
            if n in cells and n not in reached: reached.add(n); queue.append(n)
    need = math.ceil(LARGEST['width'])
    squares = [(x, z) for x, z in reached if all((x + i, z + j) in reached for i in range(need) for j in range(need))]
    if not squares: errors.append(f'{name}: no {need}x{need} free square the largest body ({LARGEST["width"]} wide) can walk to')
    if top - 3 < LARGEST['height']: errors.append(f'{name}: {top - 3} blocks clear inside, below {LARGEST["height"]}')
    if DOOR_CLEAR < LARGEST['width']: errors.append(f'{name}: open door clears {DOOR_CLEAR}, below the body {LARGEST["width"]}')
    if box.get('wide'):
        three = [(x, z) for x, z in reached if all((x + i, z + j) in reached for i in range(3) for j in range(3))]
        if not three: errors.append(f'{name}: no 3x3 free square for the unyielded wasa deibu ({LARGEST["drawn_width"]} across)')
    if 'nest' in box:
        # The shared resting spot is a round bed: nothing stands in its eight neighbours (a pressed side shuts out adults from about 1.15x),
        # its entrance cell is walkable floor, and the bed admits every ordinary adult; the wide box's admits an adult-scale deibu.
        nx, nz = box['nest']; state = b.blocks[(nx, 3, nz)][1]
        near = [(nx + i, nz + j) for i in (-1, 0, 1) for j in (-1, 0, 1) if (i, j) != (0, 0) and not free(name_at(b, nx + i, 3, nz + j))]
        if near: errors.append(f'{name}: nest {box["nest"]} is pressed by {near}')
        step = {'north': (0, -1), 'south': (0, 1), 'east': (1, 0), 'west': (-1, 0)}[state.get('facing', 'north')]
        if (nx + step[0], nz + step[1]) not in reached: errors.append(f'{name}: nest entrance {(nx + step[0], nz + step[1])} not walkable')
        need = DEIBU_PER_SCALE if box.get('wide') else ORDINARY_ADULT
        if nest_admits() < need: errors.append(f'{name}: nest admits {nest_admits():.3f} wide, below {need:.3f}')
    return errors

def plant_report(b):
    """Stand-height and clearance numbers per box (for the package report and the preview)."""
    rows = []
    for box in b.boxes:
        x0, z0, x1, z1 = box['interior']
        rows.append(dict(id=box['id'], kind=box['kind'], interior=f'{x1 - x0 + 1}x{z1 - z0 + 1}', clear=box['top'] - 3,
                         door='2 x 2', largest_height=LARGEST['height'], largest_width=LARGEST['width']))
    return rows

def nest_report():
    """The round nest's lip, the widest body it admits, and the largest deibu adult scale that fits (for the report and the preview)."""
    return dict(opening=nest_opening(), admits=nest_admits(), deibu_scale=nest_admits() / DEIBU_PER_SCALE, door_clear=DOOR_CLEAR)

def load_plant():
    path = Path(__file__).parent / 'author_plant.py'
    sys.path.insert(0, str(path.parent))
    spec = importlib.util.spec_from_file_location('author_plant_check', path); m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
    return m

BELT_TOP, STEP, WALL_COLLISION = 6 / 16, 1.0, 1.5
# YukkuriEntity STEP_HEIGHT and collision height of the full brick and bottom slab stop.


def belt_errors(b, seen):
    """The belt as F7b's contact contract states it: east-running stopped belt cells two wide (wider than the largest admitted body),
    headroom for the tallest body, a full-width stop, flat dry side exits and stands, and the steamer facing its stand."""
    belt = b.belt; errors = []
    if not belt: return [f'{b.name}: no belt and steamer']
    x0, z0 = belt['low']; x1, z1 = belt['high']

    def dry_floor(x, z):
        return name_at(b, x, 2, z) in ('minecraft:smooth_stone', 'minecraft:yellow_concrete')

    def dry_stand(x, z):
        return (x, z) in seen and dry_floor(x, z) and all(free(name_at(b, x, y, z)) for y in (3, 4))

    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            n, p = state_at(b, x, 3, z)
            if n != 'mcyukkuri:factory_belt' or p != {'facing': 'east', 'running': 'false'}: errors.append(f'{b.name}: belt cell {(x, z)} is {n} {p}')
            if not dry_floor(x, z): errors.append(f'{b.name}: belt cell {(x, z)} has no flat dry base')
            top = next((y for y in range(4, b.size[1]) if not free(name_at(b, x, y, z))), b.size[1])
            if top - (3 + BELT_TOP) < LARGEST['height']: errors.append(f'{b.name}: belt cell {(x, z)} headroom {top - 3 - BELT_TOP:.2f} under the tallest body')
    if z1 - z0 + 1 < LARGEST['width']: errors.append(f'{b.name}: belt {z1 - z0 + 1} wide, the largest body {LARGEST["width"]}')
    for z in range(z0, z1 + 1):
        stop = belt['stop']
        cap = state_at(b, stop, 4, z)
        if (stop != x1 + 1 or name_at(b, stop, 3, z) != 'minecraft:stone_bricks'
                or cap != ('minecraft:stone_brick_slab', {'type': 'bottom', 'waterlogged': 'false'})
                or WALL_COLLISION - BELT_TOP <= STEP):
            errors.append(f'{b.name}: belt row z{z} needs a full-width stop at the belt edge above step height')
    for x in range(x0, x1 + 1):
        for z in (z0 - 1, z1 + 1):
            if x == x0 and (x, z) in belt['levers'].values(): continue
            if not dry_stand(x, z): errors.append(f'{b.name}: no dry floor beside the belt at {(x, z)}')
    ex, ez = belt['exit']; dz = 1 if ez > z1 else -1
    if (ex != x1 or ez not in (z0 - 1, z1 + 1) or
            not all(dry_stand(ex - dx, ez + dz * k) for dx in (0, 1) for k in (0, 1))):
        errors.append(f'{b.name}: the end exit {belt["exit"]} has no 2 x 2 dry floor at the belt end')
    lx, lz = belt['load']
    if lx != x0 - 1 or not z0 <= lz <= z1: errors.append(f'{b.name}: load stand {belt["load"]} must border the west end')
    for key in ('load', 'stand'):
        if not dry_stand(*belt[key]): errors.append(f'{b.name}: belt {key} stand {belt[key]} needs reachable dry floor')
    sx, sz = belt['steamer']; tx, tz = belt['stand']
    if (tx, tz) != (x1 + 1, z0 - 1): errors.append(f'{b.name}: worker stand {(tx, tz)} must reach the north side of the stop')
    facing = {(-1, 0): 'west', (1, 0): 'east', (0, -1): 'north', (0, 1): 'south'}.get((tx - sx, tz - sz))
    if state_at(b, sx, 3, sz) != ('mcyukkuri:manju_steamer', {'facing': facing, 'open': 'false', 'lit': 'false'}):
        errors.append(f'{b.name}: the steamer at {belt["steamer"]} does not face its stand {belt["stand"]}')
    wx, wz = belt['wheat']
    if (state_at(b, wx, 3, wz) != ('minecraft:chest', {'facing': 'west', 'type': 'single', 'waterlogged': 'false'})
            or not dry_stand(wx - 1, wz) or not free(name_at(b, wx, 4, wz))):
        errors.append(f'{b.name}: the wheat chest {belt["wheat"]} is not reachable')
    for x, z in belt['levers'].values():
        if not (state_at(b, x, 5, z) == ('minecraft:lever', {'face': 'floor', 'facing': 'west', 'powered': 'false'})
                and name_at(b, x, 4, z) == 'minecraft:polished_andesite'
                and state_at(b, x - 1, 4, z) == ('minecraft:birch_wall_sign', {'facing': 'west', 'waterlogged': 'false'})
                and dry_stand(x - 1, z)):
            errors.append(f'{b.name}: lever post {(x, z)} needs a reachable unpowered floor lever and sign')
    return errors + belt_marker_errors(b)


def belt_marker_errors(b):
    belt = b.belt
    x, z = belt['stand']
    at = lambda cell, y=3: [cell[0] - x, y - 3, cell[1] - z]
    low, high = belt['floor']
    expected = {'Low': at(low), 'High': at(high), 'BeltLow': at(belt['low']), 'BeltHigh': at(belt['high']),
                **{key.title(): at(belt[key]) for key in ('load', 'steamer', 'wheat', 'exit')},
                **{key.title(): at(cell, 5) for key, cell in belt['levers'].items()}}
    zones = []
    for ent in b.entities:
        nbt = ent['nbt'][1]
        if nbt['id'][1] != 'minecraft:marker' or 'mcy_plant_zone' not in nbt['Tags'][1][1]: continue
        data = nbt.get('data', (10, {}))[1]
        if data.get('Kind') == (8, 'belt_steamer'): zones.append((ent, data))
    if len(zones) != 1: return [f'{b.name}: expected one belt_steamer marker, found {len(zones)}']
    ent, data = zones[0]
    if (ent['pos'][1][1] != [x + .5, 3, z + .5] or ent['nbt'][1]['Rotation'][1][1] != [180., 0.]
            or any(data.get(key) != (9, (3, value)) for key, value in expected.items())):
        return [f'{b.name}: belt_steamer marker does not match the stand and contact offsets']
    return []


def machine_contact_errors():
    from author_products import STEAMER_SLOTS, belt_model, steamer_model

    def surface(model, texture, start, end):
        return any(e['from'] == start and e['to'] == end and e['faces'].get('up', {}).get('texture') == '#' + texture
                   for e in model['elements'])

    errors = []
    for running in (False, True):
        if not surface(belt_model(running), 'top', [0, 2, 0], [16, 16 * BELT_TOP, 16]):
            errors.append(f'factory_belt running={running}: expected full-cell support at 6/16')
    ix, iy, iz = STEAMER_SLOTS['input']
    wx, wy, wz = STEAMER_SLOTS['wheat']
    ox, oy, oz = STEAMER_SLOTS['output']
    for open_ in (False, True):
        for lit in (False, True):
            model = steamer_model(open_, lit)
            if not (wy == oy and wz == oz and surface(model, 'tray', [wx - 3.75, wy - 1, wz - 3.5], [ox + 3.75, oy, oz + 2.5])
                    and surface(model, 'deck', [(wx + ox - 1) / 2, wy, wz - 2.5], [(wx + ox + 1) / 2, oy + 1, oz + 2.5])):
                errors.append(f'manju_steamer open={open_} lit={lit}: wheat and output slot tray or divider moved')
            if open_ and not surface(model, 'rack', [ix - 5, iy - 2, iz - 3.5], [ix + 5, iy, iz + 3.5]):
                errors.append(f'manju_steamer open={open_} lit={lit}: input rack moved')
            if not open_ and not surface(model, 'lid', [1, 19, 5], [15, 21, 16]):
                errors.append(f'manju_steamer open={open_} lit={lit}: closed lid must meet the 21/16 collision height')
    return errors


def check_plant():
    m = load_plant(); b = m.plant(); errors = [f'{b.name}: block overwrite at {pos}: {old} -> {new}' for pos, old, new in b.conflicts]
    errors += entity_errors(b) + door_errors(b) + light_errors(b)
    used = {n for n, _, _ in b.blocks.values()}
    errors += [f'{b.name}: villager workstation {n}' for n in sorted(used & WORKSTATIONS)]
    foreign = sorted(n for n in used if n not in PLANT_BLOCKS and not n.startswith('mcyukkuri:'))
    if foreign: errors.append(f'{b.name}: blocks outside the plant material set: {", ".join(foreign)}')
    errors += [f'{b.name}: {e}' for e in plane_patches(b, (
        ('z=3', [(x, y, 3) for x in range(1, 44) for y in range(3, 13)]), ('z=39', [(x, y, 39) for x in range(1, 44) for y in range(3, 13)]),
        ('z=13', [(x, y, 13) for x in range(20, 44) for y in range(3, 13)]),
        ('x=1', [(1, y, z) for z in range(3, 40) for y in range(3, 13)]), ('x=43', [(43, y, z) for z in range(3, 40) for y in range(3, 13)])))]
    inside = {(x, z) for box in b.boxes if box['kind'] != 'booth' for x in range(box['interior'][0], box['interior'][2] + 1)
              for z in range(box['interior'][1], box['interior'][3] + 1)}
    errors += corridor_errors(b, 3, 2, 4, 42, 38, inside)
    fx, fz = m.FRONT_DOOR; seen = reach_from(b, (fx, fz - 1), 3)
    for box in b.boxes: errors += enclosure_errors(b, box, seen)
    boxes = [box for box in b.boxes if box['kind'] == 'box']
    for first, second in zip(boxes, boxes[1:]):
        wall = first['interior'][3] + 1
        if second['interior'][1] != wall + 1: errors.append(f'{b.name}: boxes {first["id"]} and {second["id"]} do not share one wall')
        for x in range(first['interior'][0], first['interior'][2] + 1):
            if any(name_at(b, x, y, wall) != 'minecraft:glass' for y in (3, 4, 5)):
                errors.append(f'{b.name}: wall between boxes {first["id"]} and {second["id"]} at x{x} is not one clear glass')
    for zone in b.zones:
        (x0, z0), (x1, z1) = zone['low'], zone['high']
        cells = [(x, z) for x in range(x0, x1 + 1) for z in range(z0, z1 + 1)]
        bad = [c for c in cells if name_at(b, c[0], 2, c[1]) == AIR or not free(name_at(b, c[0], 3, c[1])) or not free(name_at(b, c[0], 4, c[1]))]
        if bad: errors.append(f"{b.name}: reserved floor {zone['kind']} not empty walkable floor at {bad[:4]}")
        if not any(c in seen for c in cells): errors.append(f"{b.name}: staff cannot reach the reserved floor {zone['kind']}")
    errors += belt_errors(b, seen) + machine_contact_errors()
    for room in b.rooms:
        for (dx, dz), facing in room['doors']:
            ox, oz = {'north': (dx, dz - 1), 'south': (dx, dz + 1), 'west': (dx - 1, dz), 'east': (dx + 1, dz)}[facing]
            if (ox, oz) not in seen: errors.append(f"{b.name}: room {room['id']} door {(dx, dz)} unreachable")
    tags = {}
    for ent in b.entities:
        nbt = ent['nbt'][1]
        if nbt['id'][1] == 'minecraft:marker': tags.setdefault(nbt['Tags'][1][1][0], []).append(ent['pos'][1][1])
    corners = tags.get('mcy_plant_corner', [])
    if len(corners) != 2: errors.append(f'{b.name}: {len(corners)} boundary corners, expected 2')
    else:
        lo = [min(c[i] for c in corners) for i in range(3)]; hi = [max(c[i] for c in corners) for i in range(3)]
        walls = [p for p, (n, _, _) in b.blocks.items() if p[1] >= 3 and n not in FREE and 1 <= p[0] <= 43 and 3 <= p[2] <= 39]
        out = [p for p in walls if not all(lo[i] - .5 <= p[i] <= hi[i] + .5 for i in range(3))]
        if out: errors.append(f'{b.name}: plant blocks outside the boundary box, first {out[:4]}')
        if lo[2] - .5 <= fz - 1: errors.append(f'{b.name}: the forecourt is inside the boundary box')
    if len(tags.get('mcy_plant_box', [])) != len(boxes): errors.append(f'{b.name}: box markers do not match the boxes')
    return errors

if __name__ == '__main__':
    problems = check_shop() + check_street() + check_plant()
    print('\n'.join(problems) if problems else 'yukkuri_shop, street, oni-san shop and processing plant structure checks passed')
    sys.exit(1 if problems else 0)
