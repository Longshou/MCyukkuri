"""Town lots: small yukkuri-town pieces that stand along ordinary vanilla lanes, drawn from the vanilla `houses` pool of each village
type (town_pools.py appends `village/<type>/town_lots` to it). One authored layout per lot, exported once per village type in that
type's materials (palette.py).

The front edge is z0, with one north-facing `minecraft:building_entrance` jigsaw at (gate, 1, 0) that becomes air. A vanilla lane
places the socket at the first free height, so the paving at y0 meets the lane at grade. The rest of the rim uses the type's plinth stone.

- home_yard: a cottage at the end of a garden path, a fenced pet yard beside the path in front of it (its gate on the path, so the
  way to the door and the bed has no fence gate, which villagers cannot open). The cottage eave runs down over the yard row along the
  wall as a porch over the pet nest, bowl and toilet; a toy lies on the grass; the owner (caring, no village roll) lives indoors behind
  the wooden door; one vanilla bed; a hidden corner between two walls and a bookcase; a standing bin by the gate.
- home_yard_large: a two-room house raised on a stone plinth, a back deck, a fenced pet garden behind it with a roofed kennel corner, a
  side passage. Owner and one local live indoors; one vanilla bed; a hidden corner; a wind gap under the deck end, reached from the side
  passage; a standing bin in the passage.
- kiosk: a small corner kiosk on a pavement: serving hatch (counter one and a half blocks high) under a blue/white awning, goods on the
  counter, newspaper stacks, a notice board, a bench nook under the west eave with a low dry gap under the paper shelf; the keeper lives
  behind the counter (back door); a standing bin.
- pocket_park: a lawn behind a kerb and hedges with one tree of the type (a pergola with a cloth shade in the desert), a bench pair, a
  small pond, a lamp and a standing bin.
- back_lot: the poor corner: a shabby shed, a woodpile, a lean-to against the shed with a two-cell wind gap, a cardboard spot and a bin
  already knocked over.

Markers for the town-life package (vanilla marker entities, they turn with the template; no code reads them yet): `mcy_pet_yard` x2 =
low and high corner of a pet yard (also a no-carton zone), `mcy_yard_pet` = the yard cell a resident pet starts from, `mcy_hidden_corner`
= a hidden corner cell (no-carton). No live pet is placed: without the pairing glue it would be an unowned stray in a closed yard.
"""
from pathlib import Path
import math, sys
sys.path.insert(0, str(Path(__file__).parent))
from build import stairs, slab, furniture, marker, flat_roof, gable_snow, light_up, name_at, integer, sequence
from palette import PALETTES, STYLES, BEDS, link, leaves, mc, pick
from author_street import Site, garbage, wind_gap, shelter_spot, lamp, lot_ground, ground_display, connect_bars, jigsaw

LOG = {'axis': 'y'}

# Pool rows (design town.md sections 4 and 9). town_pools.py scales the town-lot weights so that the lots take about half of the house
# draws of each type; max_count is per village. Town lots get the centre terrain check (8 cut / 5 fill) from town_pools.py.
POOL_ENTRIES = [
    {'pool': 'town_lots', 'piece': piece, 'weight': weight, 'max_count': limit, 'max_cut': None, 'max_fill': None, 'styles': None,
     'new': True}
    for piece, weight, limit in [('home_yard', 7, None), ('home_yard_large', 4, None), ('kiosk', 6, 3), ('pocket_park', 4, 2),
                                 ('back_lot', 4, 2)]
]
RUGS = {'plains': 'red_carpet', 'desert': 'brown_carpet', 'savanna': 'red_carpet', 'snowy': 'light_blue_carpet', 'taiga': 'green_carpet'}


# ---------------------------------------------------------------------------------------------------------- shared lot parts
def new_lot(piece, size, p, gate):
    """An empty lot with a paved rim and a lane-level entrance."""
    width, height, depth = size
    b = Site(f'village/{p.style}/{piece}', list(size), p)
    b.box((0, 1, 0), (width - 1, height - 1, depth - 1), 'air')
    lot_ground(b, 0, width - 1, 0, depth - 1)
    for x in range(width):
        for z in range(depth):
            if x in (0, width - 1) or z in (0, depth - 1): b.block(x, 0, z, p.plinth_at(x, z, 2))
    b.gate = gate; b.yards = []; b.corners = []
    b.block(gate, 0, 0, p.lane)
    jigsaw(b, gate, 1, 0, 'north', 'minecraft:building_entrance')
    return b


def path(b, cells):
    """Trodden lane-block path cells at y0 (the gate's street block carried into the lot)."""
    for x, z in cells: b.block(x, 0, z, b.palette.lane_at(x, z))


def ring(b, x0, x1, z0, z1, y0, y1, wall, course=True):
    """Walls on the outline of x0..x1, z0..z1 from y0 to y1: a plinth course at y0 (course=True), `wall` above, posts at the corners."""
    p = b.palette
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            if x in (x0, x1) or z in (z0, z1):
                if course: b.block(x, y0, z, p.plinth_at(x, z, 5))
                b.box((x, y0 + course, z), (x, y1, z), wall)
    for x in (x0, x1):
        for z in (z0, z1): b.box((x, y0, z), (x, y1, z), *p.state(p.post))


def panes(b, cells):
    for x, y, z in cells:
        b.block(x, y, z, 'glass_pane', {'north': 'false', 'south': 'false', 'east': 'false', 'west': 'false', 'waterlogged': 'false'})


def gable(b, x0, x1, z0, z1, y, along='x', eaves=False, fill=None):
    """A vanilla-village gable over x0..x1, z0..z1 from height y (the course above the walls). along='x': the ridge runs along x and the
    slopes face north and south; along='z': the ridge runs along z. Gable ends are filled with the wall; an odd span gets a ridge slab. eaves=True adds an overhanging stair row one lower outside both eaves. Snowy roofs carry snow on a plank ridge; the desert
    gets the flat roof with a parapet instead."""
    p = b.palette
    if p.flat:
        flat_roof(b, p, {(x, z) for x in range(x0, x1 + 1) for z in range(z0, z1 + 1)}, y)
        return
    fill = fill or p.wall
    lo, hi = (z0, z1) if along == 'x' else (x0, x1)
    run = list(range(x0, x1 + 1) if along == 'x' else range(z0, z1 + 1))
    rise = ('south', 'north') if along == 'x' else ('east', 'west')
    at = (lambda r, c: (r, c)) if along == 'x' else (lambda r, c: (c, r))
    span = hi - lo + 1; half = span // 2
    if eaves:
        for r in run:
            for c, facing in ((lo - 1, rise[0]), (hi + 1, rise[1])):
                x, z = at(r, c); stairs(b, x, y - 1, z, p.roof, facing)
    for i in range(half):
        for r in run:
            for c, facing in ((lo + i, rise[0]), (hi - i, rise[1])):
                x, z = at(r, c); stairs(b, x, y + i, z, p.roof, facing)
        for r in (run[0], run[-1]):
            for c in range(lo + i + 1, hi - i):
                x, z = at(r, c); b.block(x, y + i, z, fill)
    if span % 2:
        ridge = [(at(r, lo + half), y + half) for r in run]
        for (x, z), yy in ridge: slab(b, x, yy, z, p.roof)
    else:
        ridge = [(at(r, c), y + half - 1) for r in run for c in (lo + half - 1, hi - half + 1)]
    if p.snow:
        for (x, z), yy in ridge:
            b.block(x, yy, z, p.planks(p.roof)); b.block(x, yy + 1, z, 'snow', {'layers': '2'})


SOLIDS_NOT_JOINED = ('_leaves', 'pumpkin', 'melon', 'water', ':snow', 'hay_block')


def connect_fences(b):
    """Fence and wall joins as vanilla would update them: to fences or walls of the same kind, to gates and to solid full blocks."""
    from check_structure import full_block, TRANSPARENT
    sides = {'north': (0, -1), 'south': (0, 1), 'east': (1, 0), 'west': (-1, 0)}
    for (x, y, z), (name, props, nbt) in list(b.blocks.items()):
        wall = name.endswith('_wall')
        if not (wall or name.endswith('_fence')): continue
        joined = []
        for side, (dx, dz) in sides.items():
            other = name_at(b, x + dx, y, z + dz)
            if (other.endswith('_fence_gate') or (other.endswith('_wall') if wall else other.endswith('_fence'))
                    or full_block(other) and other not in TRANSPARENT and not other.endswith(SOLIDS_NOT_JOINED)):
                joined.append(side)
        b.blocks[x, y, z] = (name, link(name.split(':')[-1], joined), nbt)


def fence(b, cells, y=1):
    """Yard fence of the type (a sandstone wall in the desert) on the given (x, z) cells; joins are set by connect_fences."""
    for x, z in cells: b.block(x, y, z, b.palette.fence, link(b.palette.fence))


def fence_gate(b, x, y, z, facing):
    p = b.palette
    b.block(x, y, z, f'{p.wood}_fence_gate', {'facing': facing, 'in_wall': 'true' if p.fence.endswith('_wall') else 'false',
                                             'open': 'false', 'powered': 'false'})


def bed(b, x, y, z, facing='south'):
    """One vanilla bed of the type's colour: foot at (x, z), head one cell toward `facing` (villagers claim it as a home)."""
    dx, dz = {'north': (0, -1), 'south': (0, 1), 'east': (1, 0), 'west': (-1, 0)}[facing]
    name = BEDS[b.palette.style]
    b.block(x, y, z, name, {'facing': facing, 'part': 'foot', 'occupied': 'false'})
    b.block(x + dx, y, z + dz, name, {'facing': facing, 'part': 'head', 'occupied': 'false'})


def resident(b, x, y, z, kind, attitude, local=False):
    """A street resident (job 0) whose home is this cell. attitude 1 = caring (pet owners, no village roll); a local is rolled by the
    village like the main-street residents."""
    extra = {'HumanJob': integer(0), 'HumanAttitude': integer(attitude)}
    if local: extra['Tags'] = sequence(8, ['mcy_street_local'])
    b.entity(x + .5, y, z + .5, f'mcyukkuri:{kind}', extra)


def pet_yard(b, low, high, pet, nest, bowl, toilet, toy, y=1):
    """A pet yard: nest, bowl and toilet blocks, a toy on the ground, the yard corners and the pet's start cell as markers."""
    nx, nz = nest; b.block(nx, y, nz, 'mcyukkuri:yukkuri_nest')
    b.bowl(*bowl, y=y)
    b.block(toilet[0], y, toilet[1], 'mcyukkuri:yukkuri_toilet')
    b.toy(toy[0] + .5, y, toy[1] + .5, 'yarn_ball')
    (x0, y0, z0), (x1, y1, z1) = low, high
    marker(b, x0 + .5, y0 + .5, z0 + .5, 'mcy_pet_yard'); marker(b, x1 + .5, y1 + .5, z1 + .5, 'mcy_pet_yard')
    marker(b, pet[0] + .5, y, pet[1] + .5, 'mcy_yard_pet')
    b.yards.append({'low': low, 'high': high, 'pet': (pet[0], y, pet[1]), 'nest': (nx, y, nz), 'bowl': (bowl[0], y, bowl[1]),
                    'toilet': (toilet[0], y, toilet[1])})


def hidden_corner(b, x, y, z):
    marker(b, x + .5, y, z + .5, 'mcy_hidden_corner'); b.corners.append((x, y, z))


def bush(b, x, y, z):
    p = b.palette
    if p.hedge: b.block(x, y, z, p.hedge, leaves(p.hedge))
    else: b.block(x, y, z, 'dead_bush')


def plant(b, x, y, z, i):
    p = b.palette
    choices = [block for block, _ in p.plants if block]
    if choices: b.block(x, y, z, choices[i % len(choices)])


def finish(b):
    connect_fences(b); connect_bars(b); gable_snow(b); light_up(b)
    return b


# ---------------------------------------------------------------------------------------------------------- the lots
def home_yard(p):
    """9 x 13: a garden path (x2) from the gate to the cottage door with a standing bin beside it, the fenced pet yard east of the path
    (x4..8, z2..6, its gate on the path), the cottage z7..12. Villagers do not open fence gates, so the way to the bed stays gate-free."""
    W, H, D = 9, 9, 13
    b = new_lot('home_yard', (W, H, D), p, 2)
    path(b, [(2, z) for z in range(1, 7)])
    # Yard: fence on the front, the path side and the east edge, its gate on the path; the cottage roof runs on down over the row
    # along its wall (a catslide porch at y3) that shelters the nest, the bowl and the toilet.
    fence(b, [(x, 2) for x in range(4, W)] + [(4, z) for z in (3, 5, 6)] + [(8, z) for z in range(3, 7)])
    fence_gate(b, 4, 1, 4, 'east')
    for x in range(W):
        if p.flat: slab(b, x, 3, 6, p.roof)
        else: stairs(b, x, 3, 6, p.roof, 'south')
    b.block(4, 2, 6, 'lantern', {'hanging': 'true', 'waterlogged': 'false'})
    pet_yard(b, (5, 0, 3), (7, 3, 6), pet=(6, 4), nest=(5, 6), bowl=(6, 6), toilet=(7, 6), toy=(7, 4))
    # Garden: a bush and flowers west of the path and along the yard front.
    bush(b, 1, 1, 5); plant(b, 1, 1, 4, 0); plant(b, 1, 1, 6, 1); plant(b, 5, 1, 1, 2); plant(b, 7, 1, 1, 0); plant(b, 6, 1, 3, 1)
    # Cottage: plinth course, walls, corner posts; door at the end of the path, windows front, sides and back; gable along x.
    b.box((1, 0, 8), (7, 0, 11), p.floor)
    ring(b, 0, 8, 7, 12, 1, 3, p.wall)
    b.door(2, 1, 7, 'north', p.wood)
    panes(b, [(5, 2, 7), (7, 2, 7), (0, 2, 9), (0, 2, 10), (8, 2, 9), (3, 2, 12), (5, 2, 12)])
    gable(b, 0, 8, 7, 12, 4, 'x')
    # Room: the bed against the back wall, a bookcase that closes the hidden corner, table and stools, a rug, the owner's home cell.
    bed(b, 7, 1, 10, 'south')
    b.box((2, 1, 11), (2, 2, 11), 'bookshelf')
    hidden_corner(b, 1, 1, 11)
    furniture(b, 'table', 1, [(4, 11)]); furniture(b, 'bench', 1, [(3, 11)], 'z'); furniture(b, 'bench', 1, [(5, 11)], 'z')
    b.block(4, 2, 11, 'lantern', {'hanging': 'false', 'waterlogged': 'false'})
    for x in (3, 4, 5): b.block(x, 1, 9, RUGS[p.style])
    b.block(1, 1, 8, p.pot(0)); b.block(7, 1, 8, p.pot(1))
    resident(b, 5, 1, 9, 'caretaker_sister', 1)
    # Standing bin beside the path near the gate: its mouth faces the lane, shoved over from the path side.
    garbage(b, 1, 1, 2, 'north', [('bread', 1), ('paper', 1), ('bone', 1), ('apple', 1)])
    return finish(b)


def home_yard_large(p):
    """12 x 16: front garden and stoop z0..2, raised two-room house z3..8 (floor y2, walk y3), back deck z9..10 over a stone base,
    fenced pet garden z11..15 with a kennel corner, side passage x9..11 to the wind gap under the deck end."""
    W, H, D = 12, 12, 16
    b = new_lot('home_yard_large', (W, H, D), p, 6)
    # Stoop: two steps between stone cheeks with lanterns, up to the front door at y3.
    stairs(b, 6, 1, 1, p.step, 'south'); b.block(6, 1, 2, p.plinth); stairs(b, 6, 2, 2, p.step, 'south')
    for x in (5, 7):
        b.box((x, 1, 2), (x, 2, 2), p.plinth)
        b.block(x, 3, 2, 'lantern', {'hanging': 'false', 'waterlogged': 'false'})
    # Front garden: a low hedge and flowers.
    for x in range(1, 4): bush(b, x, 1, 1)
    for i, (x, z) in enumerate([(1, 2), (3, 2), (8, 1), (4, 1)]): plant(b, x, 1, z, i)
    # House: two plinth courses (y1..2), floor boards at y2, walls y3..5, corner posts; gable along x from y6.
    b.box((0, 1, 3), (8, 2, 8), p.plinth)
    b.box((1, 2, 4), (7, 2, 7), p.floor)
    ring(b, 0, 8, 3, 8, 3, 5, p.wall, course=False)
    b.box((4, 3, 4), (4, 5, 7), p.wall); b.box((4, 3, 5), (4, 4, 5), 'air')   # partition with a doorway
    b.door(6, 3, 3, 'north', p.wood); b.door(6, 3, 8, 'south', p.wood)
    panes(b, [(2, 4, 3), (8, 4, 5), (0, 4, 5), (0, 4, 6), (2, 4, 8), (8, 4, 6)])
    gable(b, 0, 8, 3, 8, 6, 'x')
    # Bedroom (west): the bed, a bookcase closing the hidden corner, a rug; one local lives here.
    bed(b, 1, 3, 7, 'north')
    b.box((2, 3, 7), (2, 4, 7), 'bookshelf')
    hidden_corner(b, 3, 3, 7)
    for x in (1, 2, 3):
        for z in (4, 5): b.block(x, 3, z, RUGS[p.style])
    b.block(3, 3, 4, p.pot(0))
    resident(b, 2, 3, 5, 'caretaker_sister', 2, local=True)
    # Living room (east): table and stool, a lantern; the owner's home cell by the window.
    furniture(b, 'table', 3, [(7, 6), (7, 7)]); furniture(b, 'bench', 3, [(7, 5)], 'z')
    b.block(7, 4, 7, 'lantern', {'hanging': 'false', 'waterlogged': 'false'})
    b.block(5, 3, 4, p.pot(1))
    resident(b, 5, 3, 6, 'caretaker_brother', 1)
    # Back deck at the floor height on a stone base; the base leaves a dry crawl gap at its east end, open to the side passage.
    b.box((0, 1, 9), (8, 1, 10), p.plinth)
    b.box((0, 2, 9), (8, 2, 10), p.floor)
    for z in (9, 10): b.block(8, 1, z, 'air')
    wind_gap(b, 8, 1, 9); wind_gap(b, 8, 1, 10, paper=False)
    stairs(b, 4, 2, 10, p.roof if p.flat else p.wood, 'north'); stairs(b, 4, 1, 11, p.roof if p.flat else p.wood, 'north')
    fence(b, [(0, 9), (0, 10), (8, 9), (8, 10)] + [(x, 10) for x in range(1, 8) if x != 4], y=3)
    b.block(8, 4, 10, 'lantern', {'hanging': 'false', 'waterlogged': 'false'})
    # Pet garden: fences on three sides; the kennel corner is a board-walled nook (back and west) under a slab roof at y3, open to
    # the garden on its north and east sides, over the nest and the bowl.
    fence(b, [(0, z) for z in (11, 12)] + [(x, 15) for x in range(3, 8)] + [(8, z) for z in range(11, 16)])
    b.box((0, 1, 15), (2, 2, 15), p.wall); b.box((0, 1, 13), (0, 2, 14), p.wall)
    b.box((0, 3, 13), (2, 3, 15), p.roof + '_slab', {'type': 'bottom', 'waterlogged': 'false'})
    b.block(2, 2, 13, 'lantern', {'hanging': 'true', 'waterlogged': 'false'})
    pet_yard(b, (0, 0, 9), (8, 4, 15), pet=(5, 12), nest=(1, 14), bowl=(2, 14), toilet=(7, 14), toy=(5, 13))
    bush(b, 7, 1, 11); plant(b, 6, 1, 14, 1); plant(b, 1, 1, 11, 2)
    # Side passage: a standing bin against the house, the woodpile at the end, a lamp at the front corner.
    garbage(b, 10, 1, 4, 'west', [('potato', 1), ('paper', 2), ('stick', 1), ('cookie', 1)])
    for x in (10, 11):
        for z in (14, 15): b.block(x, 1, z, p.tree_log, {'axis': 'x'})
        b.block(x, 2, 15, p.tree_log, {'axis': 'x'})
    lamp(b, 11, 1, 0)
    plant(b, 9, 1, 2, 0); plant(b, 9, 1, 12, 1)
    for z in (2, 6, 8, 11): b.block(10, 0, z, p.sidewalk_at(10, z))   # stepping stones down the passage
    return finish(b)


def kiosk(p):
    """9 x 9: a 5 x 5 kiosk (x2..6, z3..7) on a paved front, the hatch facing the lane under the blue/white awning (z1..2), the bench
    nook under the west eave with the paper shelf over a two-cell gap, a notice board on the east wall, the back door at z7."""
    W, H, D = 9, 9, 9
    b = new_lot('kiosk', (W, H, D), p, 4)
    for x in range(1, W - 1):
        for z in (1, 2): b.block(x, 0, z, p.sidewalk_at(x, z))
    b.box((3, 0, 4), (5, 0, 6), p.floor)
    # Shell: walls y1..4 (plinth course y1), corner posts; the front is the hatch: counter (boards and a slab top), open above, a beam.
    ring(b, 2, 6, 3, 7, 1, 4, p.wall)
    for x in (3, 4, 5):
        b.block(x, 1, 3, p.planks()); slab(b, x, 2, 3, p.wood); b.block(x, 3, 3, 'air')
        b.block(x, 4, 3, *p.state(p.beam, 'x'))
    for (x, item), yaw in zip([(3, 'minecraft:bread'), (4, 'minecraft:paper'), (5, 'mcyukkuri:sweet_candy')], (20., -15., 40.)):
        ground_display(b, x, 2.5, 3, item, yaw=yaw)
    b.door(4, 1, 7, 'south', p.wood)
    panes(b, [(2, 3, 4), (6, 3, 4), (6, 3, 6), (3, 3, 7)])
    # Roof: ridge along z with overhanging eaves; the west eave runs on down over the bench nook as a lean-to.
    if p.flat:
        flat_roof(b, p, {(x, z) for x in range(2, 7) for z in range(3, 8)}, 5)
        for z in range(3, 8): slab(b, 1, 4, z, p.roof); slab(b, 0, 3, z, p.roof)
    else:
        gable(b, 2, 6, 3, 7, 5, 'z', eaves=True)
        for z in range(3, 8): stairs(b, 0, 3, z, p.roof, 'east')
    # Awning: blue and white stripes over the pavement in front of the hatch, a valance on its front edge.
    for x in range(1, 8):
        wool = 'blue_wool' if x % 2 else 'white_wool'
        for z in (1, 2): b.block(x, 4, z, wool)
        b.block(x, 3, 1, wool)
    # Inside: two goods tables at the back (a lantern on one), the keeper's home cell behind the counter.
    furniture(b, 'table', 1, [(3, 6)]); furniture(b, 'table', 1, [(5, 6)])
    ground_display(b, 3, 2, 6, 'minecraft:apple', yaw=10.)
    b.block(5, 2, 6, 'lantern', {'hanging': 'false', 'waterlogged': 'false'})
    resident(b, 4, 1, 5, 'caretaker_sister', 2)
    # Bench nook (west): a two-seat bench, then the paper shelf on boards at y2 with the newspaper stacks; the dry cells under it.
    furniture(b, 'bench', 1, [(1, 3), (1, 4)], 'z')
    b.box((1, 2, 6), (1, 2, 7), p.planks())
    b.block(1, 3, 6, 'mcyukkuri:newspaper_stack', {'facing': 'east', 'bundles': '2'})
    b.block(1, 3, 7, 'mcyukkuri:newspaper_stack', {'facing': 'south', 'bundles': '1'})
    wind_gap(b, 1, 1, 6); wind_gap(b, 1, 1, 7, paper=False)
    # Front: a stack by the hatch, the standing bin at the east end of the awning, a lamp; the notice board on the east wall.
    b.block(2, 1, 2, 'mcyukkuri:newspaper_stack', {'facing': 'south', 'bundles': '3'})
    garbage(b, 7, 1, 2, 'west', [('paper', 2), ('bread', 1), ('cookie', 1), ('stick', 1)])
    lamp(b, 0, 1, 1)
    b.block(7, 2, 5, 'mcyukkuri:notice_board', {'facing': 'east'})
    b.block(8, 1, 6, p.pot(0))
    return finish(b)


def tree(b, x, z):
    """One tree of the type standing at (x, z): an oak in the plains, a flat-topped acacia in the savanna, a spruce in the snowy and
    taiga villages. Returns the cells over its top leaves that take snow in the snowy village."""
    p = b.palette
    log = (p.tree_log, LOG); leaf = (p.hedge, leaves(p.hedge))
    def disc(cx, cy, cz, r):
        for dx in range(-math.ceil(r), math.ceil(r) + 1):
            for dz in range(-math.ceil(r), math.ceil(r) + 1):
                if dx * dx + dz * dz <= r * r + .1 and name_at(b, cx + dx, cy, cz + dz) == 'minecraft:air': b.block(cx + dx, cy, cz + dz, *leaf)
    if p.style == 'savanna':
        b.box((x, 1, z), (x, 3, z), *log); b.block(x + 1, 4, z, *log); b.block(x + 1, 5, z, *log)
        disc(x + 1, 6, z, 2.5); disc(x + 1, 7, z, 1.5)
    elif p.wood == 'spruce':
        b.box((x, 1, z), (x, 6, z), *log)
        for y, r in [(3, 2.3), (4, 1.5), (5, 2), (6, 1), (7, .5)]: disc(x, y, z, r)
        b.block(x, 8, z, *leaf)
    else:
        b.box((x, 1, z), (x, 4, z), *log)
        for y, r in [(3, 2.3), (4, 2.3), (5, 1.5), (6, 1)]: disc(x, y, z, r)
    if not p.snow: return []
    crown = {(cx, cy, cz) for (cx, cy, cz), (name, _, _) in b.blocks.items() if name == mc(p.hedge) and cy >= 3}
    return sorted((cx, cy + 1, cz) for cx, cy, cz in crown if (cx, cy + 1, cz) not in crown and name_at(b, cx, cy + 1, cz) == 'minecraft:air')


def pocket_park(p):
    """11 x 11: a lawn with a round paved square, a bench pair either side, one tree at the back (a pergola in the desert), a small pond
    in the back corner, a lamp and a standing bin by the east bench; hedges (pots in the desert) on the kerb of three sides."""
    W, H, D = 11, 11, 11
    b = new_lot('pocket_park', (W, H, D), p, 5)
    path(b, [(5, 1), (5, 2)])
    for x in range(1, W - 1):
        for z in range(1, D - 1):
            if (x - 5) ** 2 + (z - 5) ** 2 <= 6.5: b.block(x, 0, z, p.sidewalk_at(x, z))
    furniture(b, 'bench', 1, [(2, 4), (2, 5), (2, 6)], 'z'); furniture(b, 'bench', 1, [(8, 4), (8, 5), (8, 6)], 'z')
    for x, z in [(1, 8), (2, 8), (1, 9), (2, 9)]: b.block(x, 0, z, 'water', {'level': '0'})
    if p.flat:
        # Pergola: four sandstone posts, cut sandstone beams, a white cloth shade between them, potted cactus and a dead bush.
        for x, z in [(4, 7), (7, 7), (4, 9), (7, 9)]: b.box((x, 1, z), (x, 3, z), p.post)
        for x in (4, 7): b.box((x, 4, 7), (x, 4, 9), p.beam)
        b.box((5, 4, 7), (6, 4, 9), 'white_wool')
        b.block(5, 1, 9, 'potted_cactus'); b.block(6, 1, 9, 'dead_bush')
        snow = []
    else:
        snow = tree(b, 6, 8)
    # Kerb planting: hedges along the sides and back (a row of pots in the desert); flowers on the lawn.
    for z in range(2, D - 1):
        for x in (0, W - 1):
            if p.hedge: bush(b, x, 1, z)
            elif z % 3 == 0: b.block(x, 1, z, p.pot(z))
    for x in range(1, W - 1):
        if p.hedge: bush(b, x, 1, D - 1)
        elif x % 3 == 0: b.block(x, 1, D - 1, p.pot(x))
    for i, (x, z) in enumerate([(1, 2), (1, 4), (3, 9), (9, 9), (1, 6), (9, 8)]):
        if name_at(b, x, 1, z) == 'minecraft:air': plant(b, x, 1, z, i)
    lamp(b, 3, 1, 2)
    garbage(b, 8, 1, 3, 'west', [('paper', 1), ('apple', 1), ('cookie', 1), ('stick', 1)])
    finish(b)
    for x, y, z in snow:   # after gable_snow, which keeps snow only over open roof space
        if name_at(b, x, y, z) == 'minecraft:air': b.block(x, y, z, 'snow', {'layers': '1'})
    return b


def back_lot(p):
    """7 x 9: worn ground, a shabby shed (x0..3, z5..8, door north), a lean-to on its east wall over a two-cell wind gap, a woodpile
    closing the lean-to at the back, a cardboard spot (x4..6, z1..3, opening west) and a knocked-over bin."""
    W, H, D = 7, 8, 9
    b = new_lot('back_lot', (W, H, D), p, 3)
    worn = ((p.ground, 6), ('coarse_dirt', 3)) + tuple((block, 1) for block, _ in p.lane_wear)
    for x in range(1, W - 1):
        for z in range(1, D - 1):
            block = pick(worn, x * 3 + z, z * 5 - x, 29)
            b.block(x, 0, z, block, {'snowy': 'false'} if block == 'grass_block' else None)
    path(b, [(3, 1), (3, 2)])
    # Shed: walls with a plinth course, a patched gable along z (or a flat roof), hay and stores inside.
    ring(b, 0, 3, 5, 8, 1, 3, p.wall)
    b.door(2, 1, 5, 'north', p.wood)
    panes(b, [(0, 2, 7)])
    gable(b, 0, 3, 5, 8, 4, 'z')
    for x, y, z in [(0, 4, 6), (3, 4, 8), (2, 5, 5)]:   # old repairs in odd material
        name, props, _ = b.blocks.get((x, y, z), ('minecraft:air', {}, None))
        if name.endswith('_stairs'): b.block(x, y, z, p.roof_patch + '_stairs', props)
    b.block(1, 1, 7, 'hay_block', LOG); b.block(1, 1, 6, 'pumpkin')
    # Lean-to: stairs against the shed wall at y2 over the dry cells (4, 1, 6..7); a log end and the woodpile close it at the back.
    for z in (6, 7, 8): stairs(b, 4, 2, z, p.roof if p.flat else p.wood, 'west')
    b.block(4, 1, 8, p.tree_log, {'axis': 'x'})
    for x in (5, 6): b.block(x, 1, 8, p.tree_log, {'axis': 'x'})
    b.block(5, 2, 8, p.tree_log, {'axis': 'x'})
    b.block(6, 2, 8, 'lantern', {'hanging': 'false', 'waterlogged': 'false'})
    wind_gap(b, 4, 1, 6); wind_gap(b, 4, 1, 7, paper=False)
    # The cardboard spot and the bin already knocked over, its mouth toward the gate path; a broken fence on the west side.
    shelter_spot(b, 5, 1, 2, 'cardboard_shelter', 'west')
    garbage(b, 1, 1, 2, 'east', [('rotten_flesh', 2), ('paper', 1), ('bone', 1), ('bread', 1), ('mcyukkuri:junk_headwear', 1)],
            tipped=True)
    fence(b, [(0, 1), (0, 3)])
    return finish(b)


LOTS = {'home_yard': home_yard, 'home_yard_large': home_yard_large, 'kiosk': kiosk, 'pocket_park': pocket_park, 'back_lot': back_lot}


def pieces(style='plains'):
    """Every lot of this module in one village type, built fresh (for export, checks and previews)."""
    p = PALETTES[style]
    return {name: make(p) for name, make in LOTS.items()}


def problems(b):
    """check_structure's rules on a lot plan: overwrites, entities in blocks, doors, stray objects as the stray systems read them (from
    the gate cell), light on every floor and the material set. Paths through the raised house are checked by check_town.py."""
    from check_structure import check_site, stray_errors, styled_errors
    return check_site(b) + stray_errors(b, (b.gate, 0)) + styled_errors(b)


def build():
    """Every lot in every village type; fails on any check_structure problem (called by check_town.py --export)."""
    built = [b for style in STYLES for b in pieces(style).values()]
    errors = [e for b in built for e in problems(b)]
    if errors: raise SystemExit('Town lot checks failed: ' + '; '.join(errors))
    return built


if __name__ == '__main__':
    errors = [e for style in STYLES for b in pieces(style).values() for e in problems(b)]
    print('\n'.join(errors) if errors else f'{len(LOTS)} town lots x {len(STYLES)} types: structure checks passed')
    sys.exit(1 if errors else 0)
