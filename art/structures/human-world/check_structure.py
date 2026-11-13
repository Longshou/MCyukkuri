"""Deterministic checks for the pet-shop template, run against the Building plan in build.py (no Minecraft needed).

Fails on: a furnishing write that replaced a different block without an explicit allow; an entity whose position cell or head cell is
solid or that has no solid floor; a pen whose bowl or nest was overwritten (or whose interior is smaller than declared); a covered
walkable floor cell below block light 8 (flood fill from lanterns/light blocks through non-opaque blocks); a door that is not 2 high;
staff that cannot be reached from the street door; a facade patch of one block larger than 6x6; a roof above 41% of facade height.
Usage: python check_structure.py   (also called by scripts/export_human_world.py --check)
"""
from collections import deque
from pathlib import Path
import importlib.util, sys

AIR = 'minecraft:air'
# Blocks an entity (or a walking human) may share a cell with: no collision box.
FREE = {AIR, 'mcyukkuri:exam_mark', 'minecraft:light'}
def free(name): return name in FREE or name.endswith(('_sign', '_banner', '_carpet'))
# Blocks that let block light through. Furniture, stairs, slabs and every full cube count as opaque (a conservative estimate).
TRANSPARENT = {AIR, 'minecraft:glass', 'minecraft:glass_pane', 'minecraft:lantern', 'minecraft:chain', 'minecraft:light', 'minecraft:spruce_fence', 'minecraft:oak_leaves'}
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
    errors += roof_patches(b)
    tiles = [y for (x, y, z), (n, _, _) in b.blocks.items() if n.endswith('mud_brick_slab') or n.endswith('mud_brick_stairs')]
    rows = max(tiles) - 9 + 1; ratio = rows / (6 + rows)
    if ratio > 0.41: errors.append(f'roof is {ratio:.0%} of facade height')
    return errors

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
    errors = []
    for plane, cells in (('z=3', [(x, y, 3) for x in range(1, 40) for y in range(3, 13)]), ('z=30', [(x, y, 30) for x in range(1, 40) for y in range(3, 13)]),
                         ('x=1', [(1, y, z) for z in range(3, 31) for y in range(3, 13)]), ('x=39', [(39, y, z) for z in range(3, 31) for y in range(3, 13)])):
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

def roof_patches(b, limit=6):
    """Roof planes seen from above: no window of one block family larger than 6x6 (trim, ridge cap and skylights break the slopes)."""
    top = {}
    for (x, y, z), (n, _, _) in b.blocks.items():
        if y >= 9 and n not in ('minecraft:light', 'minecraft:smooth_quartz') and top.get((x, z), (0,))[0] < y: top[x, z] = (y, n.replace('_slab', '').replace('_stairs', ''))
    errors = []; n = limit + 1
    for (x, z), (_, kind) in sorted(top.items()):   # any 7x7 window of one block family is a patch larger than 6x6
        if all(top.get((x + i, z + j), (0, None))[1] == kind for i in range(n) for j in range(n)): errors.append(f'roof: {kind} patch at x{x} z{z}'); break
    return errors

def load_build():
    path = Path(__file__).parent / 'build.py'
    sys.path.insert(0, str(path.parent))
    spec = importlib.util.spec_from_file_location('build_human_structures_check', path); m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
    return m

def check_shop(): return check(load_build().shop())

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
        n = name_at(b, x, yy, z); return free(n) or (doors and n.endswith('_door'))
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
            if name_at(b, cx, cy, cz) != AIR: errors.append(f'{b.name}: item display inside a block at {(cx, cy, cz)}')
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
    for (x, y, z) in b.doors:
        up = b.blocks.get((x, y + 1, z)); lo = b.blocks.get((x, y, z))
        if not (lo and up and lo[0].endswith('_door') and up[0] == lo[0] and lo[1].get('half') == 'lower' and up[1].get('half') == 'upper'):
            errors.append(f'{b.name}: door at {(x, y, z)} is not a matching 2-high pair')
    for (x, y, z), (name, props, _) in b.blocks.items():
        if name.endswith('_door') and props.get('half') == 'lower' and (x, y, z) not in b.doors: errors.append(f'{b.name}: door at {(x, y, z)} not registered')
    return errors

def corridor_errors(b, y, x0, z0, x1, z1):
    """No 1-wide corridor cell: a walkable cell open on two opposite sides and closed on the other two."""
    def passable(x, yy, z): n = name_at(b, x, yy, z); return free(n) or n.endswith('_door')
    walk = {(x, z) for x in range(x0, x1 + 1) for z in range(z0, z1 + 1)
            if name_at(b, x, y - 1, z) != AIR and passable(x, y, z) and passable(x, y + 1, z)}
    out = []
    for x, z in sorted(walk):
        n, s, e, w = (x, z - 1) in walk, (x, z + 1) in walk, (x + 1, z) in walk, (x - 1, z) in walk
        if (n and s and not e and not w) or (e and w and not n and not s): out.append((x, z))
    return [f'{b.name}: 1-wide corridor cells {out[:8]}'] if out else []

def stray_errors(b, lane):
    """Garbage points, wind gaps and shelter spots as the stray systems read them, each reachable by a stray (1-high body) from the lane."""
    errors = []
    strays = reach_from(b, lane, 1, body=1, doors=False)
    for (x, yy, z), facing, (sx, sy, sz) in b.garbage:
        state = b.blocks.get((x, yy, z))
        if not state or state[0] != 'minecraft:barrel' or state[1].get('facing') != facing or 'NeoForgeData' not in (state[2] or {}):
            errors.append(f'{b.name}: garbage {(x, yy, z)} is not a marked barrel facing {facing}')
        if not free(name_at(b, sx, sy, sz)) or not full_block(name_at(b, sx, sy - 1, sz)): errors.append(f'{b.name}: garbage stand {(sx, sy, sz)} is not a dry floor cell')
        if not name_at(b, sx, sy + 1, sz).endswith('_trapdoor'): errors.append(f'{b.name}: garbage {(x, yy, z)} has no lid over its stand')
        if (sx, sz) not in strays: errors.append(f'{b.name}: garbage stand {(sx, sz)} unreachable from the lane')
    for x, yy, z in b.gaps:
        above = name_at(b, x, yy + 1, z)
        if not free(name_at(b, x, yy, z)) or not full_block(name_at(b, x, yy - 1, z)): errors.append(f'{b.name}: wind gap {(x, yy, z)} is not a free floor cell')
        if above == AIR or free(above): errors.append(f'{b.name}: wind gap {(x, yy, z)} has nothing over it')
        if not any(full_block(name_at(b, x + dx, yy, z + dz)) for dx, dz in ((1, 0), (-1, 0), (0, 1), (0, -1))): errors.append(f'{b.name}: wind gap {(x, yy, z)} has no wall')
        if (x, z) not in strays: errors.append(f'{b.name}: wind gap {(x, z)} unreachable from the lane')
    for (x, yy, z), kind, facing in b.shelters:
        for dx in (-1, 0, 1):
            for dz in (-1, 0, 1):
                for dy in (0, 1):
                    part = dx + 2 + 5 * (dz + 2) + 25 * dy
                    state = b.blocks.get((x + dx, yy + dy, z + dz))
                    if not state or state[0] != 'mcyukkuri:' + kind or state[1] != {'facing': facing, 'part': str(part)} or bool(state[2]) != (part == 12):
                        errors.append(f'{b.name}: {kind} has an invalid shell part at {(x + dx, yy + dy, z + dz)}')
                if not full_block(name_at(b, x + dx, yy - 1, z + dz)): errors.append(f'{b.name}: {kind} spot {(x, z)} floor {(x + dx, z + dz)} is not sturdy')
        ex, ez = x + 2 * {'east': 1, 'west': -1}.get(facing, 0), z + 2 * {'south': 1, 'north': -1}.get(facing, 0)
        if not free(name_at(b, ex, yy, ez)) or (ex, ez) not in strays: errors.append(f'{b.name}: {kind} spot {(x, z)} entrance {(ex, ez)} blocked or unreachable')
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

def load_street():
    path = Path(__file__).parent / 'author_street.py'
    sys.path.insert(0, str(path.parent))
    spec = importlib.util.spec_from_file_location('author_street_check', path); m = importlib.util.module_from_spec(spec); spec.loader.exec_module(m)
    return m

def check_street():
    street = load_street(); built = street.pieces(); errors = []
    for key in ('stall', 'alley'):
        b = built[key]; errors += check_site(b) + stray_errors(b, (1, 4))
    errors += check_site(built['oni'], door=(11, 1), region=(2, 4, 22, 23))
    errors += stray_errors(built['village_oni'], (1, 4))
    lot = street.build.village_shop(street.build.shop())
    lot.floor = 1
    errors += stray_errors(lot, (1, 4))
    return errors

if __name__ == '__main__':
    problems = check_shop() + check_street()
    print('\n'.join(problems) if problems else 'yukkuri_shop, street and oni-san shop structure checks passed')
    sys.exit(1 if problems else 0)
