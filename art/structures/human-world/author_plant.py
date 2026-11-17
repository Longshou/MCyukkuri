"""The village-edge yukkuri processing plant (加工所), template `yukkuri_processing_plant` (45 x 13 x 41): one plain industrial
building on the Building writer of build.py, in fixed materials (white concrete, stone brick, polished andesite, glass, lanterns);
it does not change with the village type.

Plan (x east, z south; the facade faces north onto the village road at z0; floor y2, people walk at y3):
- Forecourt z0..2 with a ramp to the main door (x10..11) and to the unloading-yard gate (x32..35).
- Front block x1..20, z3..13 (ceiling y8, flat roof): the customer lobby z4..9, the counter line z10 with the front desk (x3..8) and the
  receiving counter (x18..19), the glass receiving booth (interior x13..16, z7..9) with a lobby door and a staff door, the staff strip
  z11..12 behind the counter.
- Unloading yard x21..42, z4..12: open to the sky inside a low wall, gate in the front wall, arrivals chest, empty crates.
- Work hall x1..43, z13..39 under a north-light sawtooth roof: the main aisle z14..15; along the west wall a row of four sweet boxes
  (interiors x2..5) whose fronts (x6) face the sweet aisle x7..9, the sick pen across it (interior x11..14, z32..35), the medicine and
  feed chests; the belt-and-steamer floor x11..18, z17..24; the middle aisle x19..20; the classroom (interior x22..31, z17..24) and the
  roller room (interior x22..31, z29..38) east of it, the packing floor x35..42, z16..25 and the store x33..42, z28..38.

Sweet boxes: interior 4 deep (x2..5) and 4 wide (A z17..20, B z22..25, C z27..30) or 7 wide (D z32..38, the wide box), walls y3..5 with a
glass lid at y6 (3 blocks clear inside). Adjacent boxes share exactly one wall of vanilla clear glass; the back wall is the outer wall's
floor-level glass, so a broken box opens straight out past the plant boundary. The front is glass with a 2-wide copper double door
(the side opening, floor level, no sill) under a smooth-stone lintel carrying the box's readout sign. Each box holds one free-standing straw nest
(the one good resting spot), a food bowl at the front glass and a sand toilet in a back corner. The sick pen is the same box with solid
sides and a white floor. Every door in the plant is a waxed copper door: players and staff open it by hand, yukkuri never do.

The belt-and-steamer floor holds the short floor belt, its stop wall, the steamer with its wheat chest and two lever posts (see
belt_steamer). Reserved floors (classroom, packing table, roller room) are left empty with yellow corner marks; later art packages
fill them. Markers (vanilla marker entities, read by code; offsets in `data` are relative to the marker and turn with its yaw, as
human/TownMarkers reads them) are listed in the README section 加工所.
"""
from pathlib import Path
import json, sys
sys.path.insert(0, str(Path(__file__).parent))
import build
from build import Building, stairs, slab, marker, name_at, byte, compound, integer, string, sequence, light_up, connect_panes
from palette import link, pick

sys.path.insert(0, str(build.ROOT / 'scripts'))
from furniture_placement import joined

NAME = 'yukkuri_processing_plant'
SIZE = [45, 13, 41]
ENTRANCE_X = 10          # the street cell in front of the main door's west leaf (HumanScenes.place convention: (x, 1, 0))
WOOD = 'spruce'          # benches and tables
WALL, POST, BAND, FRAME = 'white_concrete', 'stone_bricks', 'polished_andesite', 'smooth_stone'
FLOOR, LOBBY, LINE, SICK = 'smooth_stone', 'polished_andesite', 'yellow_concrete', 'white_concrete'
DOOR = 'waxed_copper'
PANE = {'north': 'false', 'south': 'false', 'east': 'false', 'west': 'false', 'waterlogged': 'false'}
CORNER = (1, 0, 3), (43, 12, 39)     # the plant box: outer walls, lobby, yard, roof
FRONT_DOOR = (10, 3)                  # west leaf of the main door; staff and visitors start on the forecourt in front of it

# Sweet boxes: id, interior z range, first door leaf z. Interiors are x2..5; the front wall is x6.
BOXES = [('A', 17, 20, 17), ('B', 22, 25, 22), ('C', 27, 30, 27), ('D', 32, 38, 34)]
BOX_X0, BOX_X1, BOX_FRONT = 2, 5, 6
LID = 6                               # glass lid level of the boxes and the sick pen (inside clear y3..5)
SICK_PEN = dict(x0=11, z0=32, x1=14, z1=35, front=10, door=33)
BOOTH = dict(x0=13, z0=7, x1=16, z1=9, door=14)
ZONES = [('classroom', (22, 17), (31, 24)), ('packing', (35, 16), (42, 25)), ('roller', (22, 29), (31, 38))]
# Belt and steamer floor x11..18, z17..24: a 2-wide floor belt (mcyukkuri:factory_belt, running east) from the load end at x11, by the
# sweet aisle, to a stone-brick stop wall at x17; the steamer east of the stop faces west onto the worker's stand, its wheat chest north
# of it; two control posts (run lever, grade lever) flank the load end. Both sides of the belt stay dry walkable floor.
BELT_FLOOR = ((11, 17), (18, 24))
BELT = dict(x0=11, x1=16, z0=20, z1=21, stop=17)
STOP_CAP = {'type': 'bottom', 'waterlogged': 'false'}
LOAD, STAND, STEAMER, WHEAT, EXIT = (10, 20), (17, 19), (18, 19), (18, 18), (16, 22)
LEVERS = {'run': ((11, 19), ['传送带', '拉杆开：运转', '拉杆关：停', '']), 'grade': ((11, 22), ['料牌', '拉杆关：普通甜也送', '拉杆开：只等浓甜', ''])}
ROOMS = {'classroom': dict(x0=21, z0=16, x1=32, z1=25, doors=[((26, 16), 'north'), ((21, 20), 'west')], sign='教育室'),
         'roller': dict(x0=21, z0=28, x1=32, z1=39, doors=[((26, 28), 'north'), ((21, 32), 'west')], sign='滚轴间')}


class Plant(Building):
    """The plant's Building, with the boxes, zones and rooms recorded for check_structure.check_plant and the preview."""
    def __init__(self):
        super().__init__(NAME, SIZE)
        self.floor = 3; self.boxes = []; self.zones = []; self.rooms = []; self.gaps = []; self.belt = None


def relative(origin, target): return sequence(3, [int(b - a) for a, b in zip(origin, target)])


def site_marker(b, cell, tag, data=None):
    """A marker in a cell (its offsets turn with the template like TownMarkers reads them)."""
    x, y, z = cell; marker(b, x + .5, y + .5 if tag.endswith(('corner', 'dock', 'goods', 'medicine', 'feed', 'trash')) else y, z + .5,
                           tag, data)


def door_pair(b, x, z, facing, y=3):
    """A 2-wide copper double door hinged on its outer sides; facing north/south runs along x, east/west along z."""
    along = (1, 0) if facing in ('north', 'south') else (0, 1)
    first = 'left' if facing in ('north', 'east') else 'right'
    second = 'right' if first == 'left' else 'left'
    b.door(x, y, z, facing, DOOR, first); b.door(x + along[0], y, z + along[1], facing, DOOR, second)


def chest(b, x, z, facing, y=3):
    b.block(x, y, z, 'chest', {'facing': facing, 'type': 'single', 'waterlogged': 'false'}, {'id': string('minecraft:chest')})


def wall_sign(b, x, y, z, facing, lines): b.wall_sign(x, y, z, lines, facing, 'birch')


def bench(b, cells, axis):
    for (x, z), props in joined('wooden_bench', cells, axis, WOOD).items(): b.block(x, 3, z, 'mcyukkuri:wooden_bench', props)


def table(b, cells):
    for (x, z), props in joined('wooden_table', cells, 'x', WOOD).items(): b.block(x, 3, z, 'mcyukkuri:wooden_table', props)


def hang(b, x, z, y=7):
    """A lantern on a chain from the roof (y8) down to y7."""
    if name_at(b, x, y, z) != 'minecraft:air' or name_at(b, x, y + 1, z) != 'minecraft:air': return
    b.block(x, y + 1, z, 'chain', {'axis': 'y', 'waterlogged': 'false'}); b.block(x, y, z, 'lantern', {'hanging': 'true', 'waterlogged': 'false'})


def outer_wall(b, cells, posts, windows=(), glass_floor=(), low=False):
    """A wall line: stone-brick posts to the cornice, a plinth course, concrete panels with a polished-andesite cornice at y8, glass panes
    at y5..6 in `windows` cells, floor-level glass y3..5 in `glass_floor` cells (box backs). low: the yard wall (y3..4 brick, y5 wall)."""
    for x, z in cells:
        if low:
            b.box((x, 3, z), (x, 4, z), POST); b.block(x, 5, z, 'stone_brick_wall'); continue
        if (x, z) in posts: b.box((x, 3, z), (x, 8, z), POST); continue
        if (x, z) in glass_floor:
            b.box((x, 3, z), (x, 5, z), 'glass'); b.box((x, 6, z), (x, 7, z), WALL)
        else:
            b.block(x, 3, z, POST); b.box((x, 4, z), (x, 7, z), WALL)
            if (x, z) in windows: b.box((x, 5, z), (x, 6, z), 'glass_pane', PANE)
        b.block(x, 8, z, BAND)


def sawtooth(b, x0, x1, z0, z1):
    """North-light roof: teeth of four rows along z, each a one-high glass light (y10 over a polished-andesite course) looking north,
    then a polished-andesite slope stepping down to the south (stair y10, slab y10, stair y9). The end walls close the profile."""
    for z in range(z0, z1 + 1):
        phase = (z - z0) % 4
        for x in range(x0, x1 + 1):
            base = WALL if x in (x0, x1) else BAND
            if phase == 0: b.block(x, 9, z, base); b.block(x, 10, z, WALL if x in (x0, x1) else 'glass')
            elif phase == 1: b.block(x, 9, z, base); stairs(b, x, 10, z, 'polished_andesite', 'north')
            elif phase == 2: b.block(x, 9, z, base); slab(b, x, 10, z, 'polished_andesite')
            else: stairs(b, x, 9, z, 'polished_andesite', 'north')


def flat_roof(b, x0, x1, z0, z1, open_edges=()):
    """Smooth-stone slab roof at y9 over the ceiling with a parapet (block + slab cap) on the outline, except on `open_edges`."""
    for x in range(x0, x1 + 1):
        for z in range(z0, z1 + 1):
            edge = {'w': x == x0, 'e': x == x1, 'n': z == z0, 's': z == z1}
            if any(v for k, v in edge.items() if k not in open_edges):
                b.block(x, 9, z, FRAME); slab(b, x, 10, z, FRAME)
            else: slab(b, x, 9, z, FRAME)


def connect_walls(b):
    """Stone-brick wall joins as vanilla would update them: to neighbouring walls and full blocks."""
    from check_structure import full_block
    for (x, y, z), (n, p, nbt) in list(b.blocks.items()):
        if n != 'minecraft:stone_brick_wall': continue
        sides = [d for d, (dx, dz) in {'north': (0, -1), 'south': (0, 1), 'east': (1, 0), 'west': (-1, 0)}.items()
                 if name_at(b, x + dx, y, z + dz) == n or full_block(name_at(b, x + dx, y, z + dz))]
        b.blocks[x, y, z] = (n, link(n, sides), nbt)


def box_ring(b, x0, z0, x1, z1, front, door, facing, sides, label, readout):
    """A low box: walls y3..5 around the interior with a lid at y6; `sides` maps 'n','s','w','e' to the wall block of that side (None:
    already built). The front side (`front` = its letter) is glass with a copper double door at `door` (first leaf), smooth-stone posts
    at its ends, a lintel over the door carrying the readout sign."""
    def sides_of(x, z):
        return {k for k, on in (('n', z == z0 - 1), ('s', z == z1 + 1), ('w', x == x0 - 1), ('e', x == x1 + 1)) if on}
    along = (0, 1) if front in 'we' else (1, 0)
    leaves = {door, (door[0] + along[0], door[1] + along[1])}
    for x in range(x0 - 1, x1 + 2):
        for z in range(z0 - 1, z1 + 2):
            own = sides_of(x, z)
            if not own or (x, z) in leaves: continue
            if any(sides.get(k, '') is None for k in own): continue          # an existing wall (the outer wall) stays
            if front in own: mat = FRAME if len(own) == 2 else 'glass'           # posts at the ends of the glass front
            else: mat = sides[next(iter(own))]
            b.box((x, 3, z), (x, 5, z), mat, over=True)
    door_pair(b, door[0], door[1], facing)
    for x, z in leaves: b.block(x, 5, z, FRAME, over=True)
    # The lid: glass over the interior, a polished-andesite frame over the walls (outer walls already stand there).
    for x in range(x0 - 1, x1 + 2):
        for z in range(z0 - 1, z1 + 2):
            if name_at(b, x, LID, z) != 'minecraft:air': continue
            b.block(x, LID, z, BAND if sides_of(x, z) else 'glass')
    sx, sz = readout
    wall_sign(b, sx, 5, sz, facing, [label, '', '', ''])


def corner_marks(b, x0, z0, x1, z1):
    """Yellow L-shaped floor marks in the four corners of a work floor."""
    for cx, cz, sx, sz in [(x0, z0, 1, 1), (x1, z0, -1, 1), (x0, z1, 1, -1), (x1, z1, -1, -1)]:
        for x, z in [(cx, cz), (cx + sx, cz), (cx + 2 * sx, cz), (cx, cz + sz), (cx, cz + 2 * sz)]:
            if name_at(b, x, 2, z) == 'minecraft:' + FLOOR: b.block(x, 2, z, LINE, over=True)


def belt_steamer(b):
    """The belt line of the sweet line: candidates are released onto the load end (x11) from the stand west of it, ride east on the
    support surface (6/16 above the floor) and stop at the full-width stone-brick wall, 1.5 high with its slab cap;
    beyond the control posts the belt has dry floor on both sides for stepping off. The worker on the stand
    north-east of the end reaches the last belt cells and turns to the steamer (open side west); the wheat chest is north of it."""
    (fx0, fz0), (fx1, fz1) = BELT_FLOOR
    corner_marks(b, fx0, fz0, fx1, fz1)
    for x in range(BELT['x0'], BELT['x1'] + 1):
        for z in (BELT['z0'], BELT['z1']): b.block(x, 3, z, 'mcyukkuri:factory_belt', {'facing': 'east', 'running': 'false'})
    for z in (BELT['z0'], BELT['z1']):
        b.block(BELT['stop'], 3, z, 'stone_bricks')
        b.block(BELT['stop'], 4, z, 'stone_brick_slab', STOP_CAP)
    b.block(STEAMER[0], 3, STEAMER[1], 'mcyukkuri:manju_steamer', {'facing': 'west', 'open': 'false', 'lit': 'false'})
    chest(b, WHEAT[0], WHEAT[1], 'west')
    for (x, z), lines in LEVERS.values():
        b.block(x, 3, z, POST); b.block(x, 4, z, BAND)
        b.block(x, 5, z, 'lever', {'face': 'floor', 'facing': 'west', 'powered': 'false'})
        wall_sign(b, x - 1, 4, z, 'west', lines)
    anchor = (STAND[0], 3, STAND[1])
    at = lambda cell, y=3: relative(anchor, (cell[0], y, cell[1]))
    site_marker(b, anchor, 'mcy_plant_zone', {'Kind': string('belt_steamer'), 'Low': at((fx0, fz0)), 'High': at((fx1, fz1)),
                                              'BeltLow': at((BELT['x0'], BELT['z0'])), 'BeltHigh': at((BELT['x1'], BELT['z1'])), 'Load': at(LOAD),
                                              'Steamer': at(STEAMER), 'Wheat': at(WHEAT), 'Exit': at(EXIT),
                                              'Run': at(LEVERS['run'][0], 5), 'Grade': at(LEVERS['grade'][0], 5)})
    b.belt = dict(floor=BELT_FLOOR, low=(BELT['x0'], BELT['z0']), high=(BELT['x1'], BELT['z1']), stop=BELT['stop'],
                  load=LOAD, stand=STAND, steamer=STEAMER, wheat=WHEAT, exit=EXIT,
                  levers={key: cell for key, (cell, _) in LEVERS.items()})


def plant():
    from check_structure import light_levels
    b = Plant(); B = b.box
    # ---- Ground: foundation y0..1, the paved forecourt and a gravelled apron round the other sides (y2).
    B((0, 0, 0), (44, 1, 40), POST)
    for x in range(45):
        for z in range(41):
            if z <= 2: b.block(x, 2, z, pick((('polished_andesite', 70), ('andesite', 20), ('stone', 10)), x, z, 3))
            else: b.block(x, 2, z, pick((('andesite', 60), ('gravel', 25), ('stone', 15)), x, z, 5))
    for x0, x1 in [(9, 12), (32, 35)]:                       # ramps down to the road at the main door and the yard gate
        for x in range(x0, x1 + 1):
            b.block(x, 2, 0, 'air'); stairs(b, x, 1, 0, 'stone_brick', 'south'); stairs(b, x, 2, 1, 'stone_brick', 'south')
    # Floors inside the outer walls.
    B((2, 2, 4), (19, 2, 9), LOBBY); B((2, 2, 10), (19, 2, 12), FLOOR)
    for x in range(21, 43):
        for z in range(4, 13): b.block(x, 2, z, pick((('andesite', 55), ('stone', 25), ('gravel', 20)), x, z, 9))
    B((2, 2, 14), (42, 2, 38), FLOOR); B((2, 2, 14), (42, 2, 15), LOBBY)
    # ---- Outer walls. Front block x1..20 (z3..13) and the hall x1..43 (z13..39) are full height; the yard has a low wall.
    front = [(x, 3) for x in range(1, 21)]
    outer_wall(b, front, posts={(x, 3) for x in (1, 5, 9, 12, 16, 20)},
               windows={(x, 3) for x in (2, 3, 4, 6, 7, 8, 13, 14, 15, 17, 18, 19)})
    outer_wall(b, [(1, z) for z in range(4, 14)], posts={(1, 8), (1, 13)}, windows={(1, z) for z in (5, 6, 10, 11)})
    outer_wall(b, [(20, z) for z in range(4, 14)], posts={(20, 8), (20, 13)}, windows={(20, z) for z in (5, 6)})
    outer_wall(b, [(x, 13) for x in range(21, 44)], posts={(x, 13) for x in (26, 32, 38, 43)},
               windows={(x, 13) for x in (22, 23, 24, 34, 35, 36, 39, 40, 41)})
    B((2, 3, 13), (19, 7, 13), WALL); B((2, 8, 13), (19, 8, 13), BAND)   # front block / hall wall (inside)
    box_backs = {(1, z) for _, z0, z1, _ in BOXES for z in range(z0, z1 + 1)} | {(x, 39) for x in range(BOX_X0, BOX_X1 + 1)}
    outer_wall(b, [(1, z) for z in range(14, 40)], posts={(1, z) for z in (16, 21, 26, 31, 35, 39)}, glass_floor=box_backs,
               windows={(1, z) for z in (14, 15)})
    outer_wall(b, [(43, z) for z in range(14, 40)], posts={(43, z) for z in (19, 25, 31, 37, 39)},
               windows={(43, z) for z in (15, 16, 17, 21, 22, 23, 27, 28, 29, 33, 34, 35)})
    outer_wall(b, [(x, 39) for x in range(2, 43)], posts={(x, 39) for x in (6, 12, 18, 24, 30, 36)}, glass_floor=box_backs,
               windows={(x, 39) for x in (8, 9, 10, 14, 15, 16, 20, 21, 22, 26, 27, 28, 32, 33, 34, 38, 39, 40, 41)})
    outer_wall(b, [(x, 3) for x in range(21, 44)] + [(43, z) for z in range(4, 13)], posts=set(), low=True)
    for x in (31, 36, 43):                                   # yard gate pillars and the corner post, a lantern on each
        B((x, 3, 3), (x, 6, 3), POST, over=True); b.block(x, 7, 3, 'lantern', {'hanging': 'false', 'waterlogged': 'false'})
    B((32, 3, 3), (35, 5, 3), 'air', over=True)              # the gate
    # ---- Roofs: the front block's flat roof over a smooth-stone ceiling, the hall's sawtooth.
    B((2, 8, 4), (19, 8, 12), FRAME)
    flat_roof(b, 1, 20, 3, 12, open_edges='s')
    sawtooth(b, 1, 43, 13, 39)
    # Entrance: copper double door, a glass transom, a slab canopy over the steps, the name boards on the door posts.
    B((10, 3, 3), (11, 7, 3), 'air', over=True); B((10, 5, 3), (11, 6, 3), 'glass'); B((10, 7, 3), (11, 7, 3), WALL)
    for x in range(8, 14): slab(b, x, 6, 2, FRAME, 'top')
    # ---- Furnishing phase: from here a write over a different block is an error unless allowed.
    b.strict = True
    door_pair(b, 10, 3, 'north')
    wall_sign(b, 9, 4, 2, 'north', ['油库里加工所', 'YUKKURI', 'PROCESSING', ''])
    wall_sign(b, 12, 4, 2, 'north', ['接待 · 收货', '成品 · 买出', '', ''])
    wall_sign(b, 31, 4, 2, 'north', ['卸货场', '收货车道', '', ''])
    # ---- Lobby (customers, x2..19 z4..9): bench, plants; the counter line at z10 (1.5-high counters with a hand-over slot under glass).
    bench(b, [(2, z) for z in (5, 6, 7)], 'z'); bench(b, [(x, 4) for x in (6, 7, 8)], 'x')
    for x, z in [(2, 4), (19, 4)]: b.block(x, 3, z, 'potted_fern')
    for x in (2, 9): B((x, 3, 10), (x, 7, 10), POST)
    for x0, x1 in [(3, 8), (18, 19)]:
        B((x0, 3, 10), (x1, 3, 10), BAND)
        for x in range(x0, x1 + 1): slab(b, x, 4, 10, FRAME)
        B((x0, 5, 10), (x1, 7, 10), 'glass')
    door_pair(b, 10, 10, 'north'); B((10, 5, 10), (11, 7, 10), WALL)
    wall_sign(b, 9, 5, 9, 'north', ['前台', '成品与买出', '', ''])
    site_marker(b, (5, 3, 11), 'mcy_plant_counter')
    # Receiving booth: glass to the ceiling, the lobby door north (players let an individual in), the staff door south.
    bx0, bz0, bx1, bz1 = BOOTH['x0'], BOOTH['z0'], BOOTH['x1'], BOOTH['z1']; d = BOOTH['door']
    for x in range(bx0 - 1, bx1 + 2):
        for z in (bz0 - 1, bz1 + 1):
            if x in (d, d + 1): continue
            B((x, 3, z), (x, 7, z), FRAME if x in (bx0 - 1, bx1 + 1) else 'glass')
    for x in (bx0 - 1, bx1 + 1): B((x, 3, bz0), (x, 7, bz1), 'glass')
    for z, facing in [(bz0 - 1, 'north'), (bz1 + 1, 'south')]:
        door_pair(b, d, z, facing); B((d, 5, z), (d + 1, 5, z), FRAME); B((d, 6, z), (d + 1, 7, z), 'glass')
    wall_sign(b, d, 5, bz0 - 2, 'north', ['收货台', '把油库里', '放进来', ''])
    site_marker(b, (d, 3, bz1), 'mcy_plant_receive', {'Low': relative((d, 3, bz1), (bx0, 3, bz0)), 'High': relative((d, 3, bz1), (bx1, 3, bz1)),
                                                     'Counter': relative((d, 3, bz1), (18, 3, 11))})
    b.boxes.append(dict(id='booth', kind='booth', interior=(bx0, bz0, bx1, bz1), top=8,
                        doors=[((d, bz0 - 1), (d, bz0 - 2), (d, bz0)), ((d, bz1 + 1), (d, bz1 + 2), (d, bz1))]))
    # Staff strip (z11..12): the goods chests by the counter; doors to the hall and to the yard.
    chest(b, 2, 11, 'east'); chest(b, 2, 12, 'east'); site_marker(b, (2, 3, 11), 'mcy_plant_goods')
    B((10, 3, 13), (11, 7, 13), 'air', over=True); door_pair(b, 10, 13, 'south'); B((10, 5, 13), (11, 7, 13), WALL, over=True)
    B((20, 3, 11), (20, 7, 12), 'air', over=True); door_pair(b, 20, 11, 'east'); B((20, 5, 11), (20, 7, 12), WALL, over=True)
    # ---- Unloading yard (x21..42 z4..12, open sky): arrivals chests by the hall door, empty transport crates, straw, lamps.
    B((29, 3, 13), (30, 7, 13), 'air', over=True); door_pair(b, 29, 13, 'south'); B((29, 5, 13), (30, 7, 13), WALL, over=True)
    chest(b, 42, 11, 'west'); chest(b, 42, 12, 'west'); site_marker(b, (42, 3, 11), 'mcy_plant_dock')
    for x, z in [(23, 4), (24, 4), (23, 5)]:
        b.block(x, 3, z, 'mcyukkuri:produce_crate', {'facing': 'south', 'wood': WOOD}, {'id': string('mcyukkuri:produce_crate')})
    for x, z, y in [(41, 4, 3), (42, 4, 3), (42, 5, 3), (42, 4, 4)]: b.block(x, y, z, 'hay_block', {'axis': 'y'})
    for x, z in [(26, 8), (38, 8)]:
        for y in (3, 4, 5): b.block(x, y, z, 'stone_brick_wall')
        b.block(x, 6, z, 'lantern', {'hanging': 'false', 'waterlogged': 'false'})
    # ---- Sweet boxes along the west wall (backs: the outer wall's floor glass; fronts: x6 onto the sweet aisle x7..9).
    for ident, z0, z1, dz in BOXES:
        # Box A's north wall is glass onto the main aisle; each later box's north wall is the one glass wall it shares with the previous.
        box_ring(b, BOX_X0, z0, BOX_X1, z1, 'e', (BOX_FRONT, dz), 'east', {'n': 'glass', 's': 'glass' if z1 < 38 else None, 'w': None},
                 f'甜化箱 {ident}', (BOX_FRONT + 1, dz))
        # The one nest stands free of every wall (a round bed: a pressed-flat side would shut out adults from about 1.15x), its
        # entrance toward the door. NestResting/NestDoorway admit bodies up to 1.326 wide in it (every ordinary adult, a deibu up
        # to adult scale 1.10); no placeable nest takes a 1.2x deibu (1.44), which rests on the box floor.
        nest = (BOX_X0 + 1, z0 + 1)
        b.block(nest[0], 3, nest[1], 'mcyukkuri:straw_nest', {'facing': 'east', 'north': 'false', 'east': 'false', 'south': 'false', 'west': 'false'})
        bowl = (BOX_X1, z1 if ident != 'D' else z1 - 1); toilet = (BOX_X0, z1)
        b.bowl(*bowl, grain=6); b.block(toilet[0], 3, toilet[1], 'mcyukkuri:yukkuri_toilet')
        anchor = (BOX_X1, 3, dz)
        site_marker(b, anchor, 'mcy_plant_box', {'Box': string(ident), 'Low': relative(anchor, (BOX_X0, 3, z0)),
                                                 'High': relative(anchor, (BOX_X1, 3, z1)), 'Door': relative(anchor, (BOX_FRONT + 1, 3, dz)),
                                                 'Readout': relative(anchor, (BOX_FRONT + 1, 5, dz))})
        b.boxes.append(dict(id=ident, kind='box', wide=ident == 'D', interior=(BOX_X0, z0, BOX_X1, z1), top=LID, bowl=bowl, toilet=toilet, nest=nest,
                            doors=[((BOX_FRONT, dz), (BOX_FRONT + 1, dz), (BOX_X1, dz))]))
    tools = ['mcyukkuri:care_awl', 'mcyukkuri:pin_tool', 'mcyukkuri:restraint_frame', 'minecraft:name_tag']
    items = [{'Slot': byte(slot), 'id': string(tool), 'count': integer(1)} for slot, tool in enumerate(tools)]
    items[-1]['components'] = compound({'minecraft:custom_name': string('{"text":"Taffy"}')})
    b.block(9, 3, 16, 'chest', {'facing': 'west', 'type': 'single', 'waterlogged': 'false'},
            {'id': string('minecraft:chest'), 'Items': sequence(10, items)})
    site_marker(b, (9, 3, 16), 'mcy_plant_tools')
    # Sick pen across the aisle: solid sides and back, glass front with the door, white floor, a bowl and a toilet, no nest.
    s = SICK_PEN
    B((s['x0'], 2, s['z0']), (s['x1'], 2, s['z1']), SICK, over=True)
    box_ring(b, s['x0'], s['z0'], s['x1'], s['z1'], 'w', (s['front'], s['door']), 'west', {'n': WALL, 's': WALL, 'e': WALL, 'w': 'glass'},
             '病栏', (s['front'] - 1, s['door']))
    b.bowl(s['x0'], s['z1']); b.block(s['x1'], 3, s['z1'], 'mcyukkuri:yukkuri_toilet')
    anchor = (s['x0'], 3, s['door'])
    site_marker(b, anchor, 'mcy_plant_sick', {'Low': relative(anchor, (s['x0'], 3, s['z0'])), 'High': relative(anchor, (s['x1'], 3, s['z1'])),
                                              'Door': relative(anchor, (s['front'] - 1, 3, s['door']))})
    b.boxes.append(dict(id='sick', kind='sick', interior=(s['x0'], s['z0'], s['x1'], s['z1']), top=LID, bowl=(s['x0'], s['z1']),
                        toilet=(s['x1'], s['z1']), doors=[((s['front'], s['door']), (s['front'] - 1, s['door']), (s['x0'], s['door']))]))
    # Care corner by the sweet aisle: the medicine chest (orange juice), a work table, the feed chest for the bowls.
    chest(b, 10, 25, 'west'); site_marker(b, (10, 3, 25), 'mcy_plant_medicine')
    table(b, [(10, 26), (10, 27)])
    chest(b, 10, 28, 'west'); site_marker(b, (10, 3, 28), 'mcy_plant_feed')
    # ---- Classroom and roller room: concrete walls to y8 with eye-level glass towards the aisles, copper doors, a name sign on each.
    for key, r in ROOMS.items():
        x0, z0, x1, z1 = r['x0'], r['z0'], r['x1'], r['z1']
        cells = [(x, z) for x in range(x0, x1 + 1) for z in (z0, z1)] + [(x, z) for x in (x0, x1) for z in range(z0 + 1, z1)]
        leaves = set()
        for (dx, dz), facing in r['doors']:
            leaves |= {(dx, dz), (dx + 1, dz) if facing in ('north', 'south') else (dx, dz + 1)}
        for x, z in cells:
            if z == 39: continue                                  # the roller room's south wall is the outer wall
            corner = x in (x0, x1) and z in (z0, z1)
            if (x, z) in leaves:
                B((x, 5, z), (x, 7, z), WALL, over=True); b.block(x, 8, z, BAND, over=True); continue
            if corner: B((x, 3, z), (x, 8, z), POST, over=True); continue
            b.block(x, 3, z, BAND, over=True); B((x, 4, z), (x, 7, z), WALL, over=True); b.block(x, 8, z, BAND, over=True)
            if (z == z0 or x == x0) and (x, z) not in leaves and all((x + ax, z + az) not in leaves for ax, az in ((1, 0), (-1, 0), (0, 1), (0, -1))):
                B((x, 4, z), (x, 5, z), 'glass_pane', PANE, over=True)
        for (dx, dz), facing in r['doors']: door_pair(b, dx, dz, facing)
        (sx, sz), facing = r['doors'][0]
        wall_sign(b, sx, 5, sz - 1, 'north', [r['sign'], '', '', ''])
        b.rooms.append(dict(id=key, **r))
    belt_steamer(b)
    # ---- Reserved floors: yellow corner marks on the floor; empty for the later art packages.
    for kind, (x0, z0), (x1, z1) in ZONES:
        corner_marks(b, x0, z0, x1, z1)
        anchor = (x0, 3, z0)
        site_marker(b, anchor, 'mcy_plant_zone', {'Kind': string(kind), 'Low': relative(anchor, (x0, 3, z0)), 'High': relative(anchor, (x1, 3, z1))})
        b.zones.append(dict(kind=kind, low=(x0, z0), high=(x1, z1)))
    # ---- Store (x33..42 z28..38): supply chests on the east wall, the waste chest, a staff table with benches, straw.
    for z in (29, 30, 32, 33): chest(b, 42, z, 'west')
    chest(b, 42, 37, 'west'); site_marker(b, (42, 3, 37), 'mcy_plant_trash')
    table(b, [(37, 33), (38, 33)]); bench(b, [(37, 32), (38, 32)], 'x'); bench(b, [(37, 34), (38, 34)], 'x')
    for x, z, y in [(34, 38, 3), (35, 38, 3), (34, 38, 4)]: b.block(x, y, z, 'hay_block', {'axis': 'y'})
    # ---- Plant boundary: the box from the outer walls' low corner to the roof's high corner (lobby, yard and walls inside).
    for cell in CORNER: site_marker(b, cell, 'mcy_plant_corner')
    # ---- Lighting: froglights in the front ceiling, lanterns on chains under the hall roof, hidden lights for the rest.
    for x in (4, 8, 12, 16):
        for z in (5, 8, 11): b.block(x, 8, z, 'ochre_froglight', {'axis': 'y'}, over=True)
    for x in range(4, 43, 5):
        for z in (14, 20, 26, 32, 38): hang(b, x, z)
    connect_panes(b); connect_walls(b)
    light_up(b)
    return b


def export():
    summary = plant().save(); summary['source'] = 'art/structures/human-world/author_plant.py'
    return [summary]


if __name__ == '__main__': print(json.dumps(export(), ensure_ascii=False))
