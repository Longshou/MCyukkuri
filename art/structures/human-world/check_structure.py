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
    return {'minecraft:lantern': 15, 'minecraft:sea_lantern': 15, 'minecraft:glowstone': 15, 'minecraft:torch': 14, 'minecraft:shroomlight': 15}.get(name, 0)
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
        if kind == 'minecraft:item_display':
            if name_at(b, cx, cy, cz) != AIR: errors.append(f'item display inside a block at {(cx, cy, cz)}')
            continue
        head = cy + (1 if kind.startswith('mcyukkuri:caretaker') or kind.endswith('oniisan') else 0)
        for yy in sorted({cy, head}):
            if not free(name_at(b, cx, yy, cz)): errors.append(f'{kind} at {(x, y, z)}: cell {(cx, yy, cz)} is {name_at(b, cx, yy, cz)}')
        if not solid_cell_below(b, cx, cy, cz) or name_at(b, cx, cy - 1, cz) in ('minecraft:glass',): errors.append(f'{kind} at {(x, y, z)} has no floor')
    for pen in b.pens:
        x0, z0, x1, z1 = pen['interior']; bx, bz = pen['bowl']
        if name_at(b, bx, 3, bz) != 'mcyukkuri:food_bowl': errors.append(f"pen {pen['name']}: bowl {(bx, 3, bz)} is {name_at(b, bx, 3, bz)}")
        for nx, nz in pen['nests']:
            if not name_at(b, nx, 3, nz).endswith('nest'): errors.append(f"pen {pen['name']}: nest {(nx, 3, nz)} is {name_at(b, nx, 3, nz)}")
        if x1 - x0 + 1 < pen['min'][0] or z1 - z0 + 1 < pen['min'][1]: errors.append(f"pen {pen['name']} interior {x1 - x0 + 1}x{z1 - z0 + 1} below {pen['min']}")
        for x in range(x0, x1 + 1):
            for z in range(z0, z1 + 1):
                if name_at(b, x, 2, z) == AIR or name_at(b, x, 4, z) not in FREE: errors.append(f"pen {pen['name']}: cell {(x, z)} lacks floor or headroom")
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

def reachability(b):
    """Staff must be reachable on foot (2-high clearance, doors open) from the pavement in front of the shop door."""
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

if __name__ == '__main__':
    problems = check_shop()
    print('\n'.join(problems) if problems else 'yukkuri_shop structure checks passed')
    sys.exit(1 if problems else 0)
