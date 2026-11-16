"""Civic pieces of the village main street (design town.md sections 2 and 4): the badge hall, the stray shelter and the park on the civic
side, the side street on the market side, and the two street mouths that hand the main street back to the vanilla lanes. Original block
plans on the Building writer of build.py, exported once per village type in that type's materials (palette.py).

Street convention (author_street.py): paving at y0, people walk at y1, near sidewalk z0..2, lane z3..5 in the type's vanilla street block,
far sidewalk z6..7, the lot from z8; lane sockets at (edge, 1, 4) with the direction-locked names, so every piece stands unrotated with its
buildings on the far side of the lane. The mouths and the side street end in a `minecraft:street` connector into the type's vanilla
streets pool, so vanilla lanes and houses grow on from them.

Behaviour hooks are the existing ones only: wooden benches (stray naps), garbage bins with a finite real stock (standing bins have room to
be shoved over), paper-lined wind gaps (FoundShelter), crate shelters, wool canopies (stray rain cover), a pond (drink), roofed pavilions
(street residents' rain cover), and street residents whose home is an indoor cell behind a wooden door (HumanNpc). The shelter volunteers are
caring residents (`HumanAttitude 1`, no village roll). No vanilla workstation blocks; every floor cell is lit (build.light_up).
"""
from pathlib import Path
import json, math, sys
sys.path.insert(0, str(Path(__file__).parent))
from build import stairs, slab, furniture, light_up, flat_roof, gable_snow, ground, name_at, string, integer, compound, sequence
from palette import PALETTES, STYLES, link, pick, leaves, mc
from author_street import (Site, garbage, wind_gap, shelter_spot, lamp, jigsaw, street_ends, street_band, lot_ground, fence_row,
                           display_tray, produce_crate)
from author_lots import no_carton

LOG = {'axis': 'y'}
PANE = {'north': 'false', 'south': 'false', 'east': 'false', 'west': 'false', 'waterlogged': 'false'}

# Pool table rows (design town.md sections 2 and 9). Main-street lots 6 cut / 4 fill, mouths 4 / 3.
POOL_ENTRIES = [
    {'pool': 'main_civic', 'piece': 'badge_hall', 'weight': 6, 'max_count': 1, 'max_cut': 6, 'max_fill': 4, 'styles': None, 'new': True},
    {'pool': 'main_civic', 'piece': 'shelter', 'weight': 5, 'max_count': 1, 'max_cut': 6, 'max_fill': 4, 'styles': None, 'new': True},
    {'pool': 'main_civic', 'piece': 'park', 'weight': 4, 'max_count': 1, 'max_cut': 6, 'max_fill': 4, 'styles': None, 'new': True},
    {'pool': 'main_market', 'piece': 'side_street', 'weight': 3, 'max_count': 1, 'max_cut': 6, 'max_fill': 4, 'styles': None, 'new': True},
    {'pool': 'main_end', 'piece': 'mouth_east', 'weight': 1, 'max_count': None, 'max_cut': 4, 'max_fill': 3, 'styles': None, 'new': True},
    {'pool': 'main_end', 'piece': 'mouth_west', 'weight': 1, 'max_count': None, 'max_cut': 4, 'max_fill': 3, 'styles': None, 'new': True},
]


# ---------------------------------------------------------------------------------------------------------- shared helpers
def resident(b, x, z, kind, attitude=2, tags=(), y=None):
    """A street resident (HumanJob 0, no site role); the cell it is written in becomes its home on the first tick."""
    extra = {'HumanJob': integer(0), 'HumanAttitude': integer(attitude)}
    if tags: extra['Tags'] = sequence(8, list(tags))
    b.entity(x + .5, b.floor if y is None else y, z + .5, 'mcyukkuri:' + kind, extra)


def locals_(b, first, second, y=None):
    """Two neighbourhood residents, as author_street.street_residents: only the second can roll a non-neutral attitude."""
    resident(b, *first, 'caretaker_brother', 2, ['mcy_street_local'], y)
    resident(b, *second, 'oniisan', 2, ['mcy_street_local', 'mcy_street_special'], y)


def wall_item(b, x, y, z, item, facing='north', scale=.5):
    """A good hung flat on the wall behind this air cell (a fixed item display; `facing` is the side it shows)."""
    dx, dz, yaw = {'north': (.5, .94, 0.), 'south': (.5, .06, 180.), 'east': (.06, .5, 90.), 'west': (.94, .5, 270.)}[facing]
    h = math.radians(yaw) / 2
    b.entity(x + dx, y + .5, z + dz, 'minecraft:item_display', {'item_display': string('fixed'),
        'item': compound({'id': string(item if ':' in item else 'mcyukkuri:' + item), 'count': integer(1)}),
        'transformation': compound({'translation': sequence(5, [0., 0., 0.]), 'scale': sequence(5, [scale, scale, scale]),
                                    'left_rotation': sequence(5, [0., math.sin(h), 0., math.cos(h)]),
                                    'right_rotation': sequence(5, [0., 0., 0., 1.])})})


def notice(b, x, y, z, facing):
    b.block(x, y, z, 'mcyukkuri:notice_board', {'facing': facing})


def wall_sign(b, x, y, z, lines, facing):
    b.wall_sign(x, y, z, lines, facing, b.palette.wood)


def paper(b, x, y, z):
    """A loose sheet of paper (strays bed on it); it never despawns."""
    b.entity(x + .5, y, z + .5, 'minecraft:item', {'Item': compound({'id': string('minecraft:paper'), 'count': integer(1)}),
                                                   'Age': integer(-32768)})


def ring(x0, x1, z0, z1):
    return [(x, z) for x in range(x0, x1 + 1) for z in range(z0, z1 + 1) if x in (x0, x1) or z in (z0, z1)]


def shell(b, x0, x1, z0, z1, y0, y1, block, props=None):
    """Walls on the outline of a rectangle from y0 to y1."""
    for x, z in ring(x0, x1, z0, z1): b.box((x, y0, z), (x, y1, z), block, props)


def posts(b, cells, y0, y1, block=None):
    p = b.palette
    for x, z in cells: b.box((x, y0, z), (x, y1, z), *p.state(block or p.post))


def windows(b, cells, y0, y1):
    for x, z in cells: b.box((x, y0, z), (x, y1, z), 'glass_pane', PANE)


def pitched(b, x0, x1, z0, z1, y, along='x', overhang=1, steep=False, fill=None):
    """A gable roof over the wall rectangle in the type's roof family, its ridge along x (eaves over z0/z1) or z (eaves over x0/x1),
    rising from y. Low pitch (slab/stair pairs, 1 up per 2 rows, as the pet shop) or steep (one stair per row, as vanilla houses). Trim of
    the type on the eaves, ridge and verges; gable ends at the wall lines filled with `fill` (name, props); snow on the ridge in snowy."""
    p = b.palette
    fill = fill or (p.wall, None)
    u0, u1, v0, v1 = (x0, x1, z0, z1) if along == 'x' else (z0, z1, x0, x1)
    near, far = ('south', 'north') if along == 'x' else ('east', 'west')
    dmax = (v1 - v0) // 2
    def at(u, v): return (u, v) if along == 'x' else (v, u)
    for v in range(v0, v1 + 1):
        d = min(v - v0, v1 - v)
        h = y + (d if steep else d // 2)
        face = near if v - v0 <= v1 - v else far
        for u in range(u0 - overhang, u1 + overhang + 1):
            x, z = at(u, v)
            edge = d in (0, dmax) or u in (u0 - overhang, u1 + overhang)
            stair = steep or d % 2
            mat = p.roof_trim if edge and (not stair or p.trim_stairs) else p.roof
            if p.snow and d == dmax:
                b.block(x, h, z, p.planks(p.roof)); b.block(x, h + 1, z, 'snow', {'layers': '2'})
            elif steep and d == dmax and (v1 - v0) % 2 == 0: slab(b, x, h, z, mat)
            elif stair: stairs(b, x, h, z, mat, face)
            else: slab(b, x, h, z, mat)
        for u in (u0, u1) if fill != 'open' else ():
            x, z = at(u, v)
            for yy in range(y, h): b.block(x, yy, z, *fill)


def roof(b, x0, x1, z0, z1, y, along='x', overhang=1, steep=False, fill=None):
    """The building's roof: pitched in most types, a flat roof with a parapet in the desert."""
    if b.palette.flat:
        flat_roof(b, b.palette, {(x, z) for x in range(x0, x1 + 1) for z in range(z0, z1 + 1)}, y); return
    pitched(b, x0, x1, z0, z1, y, along, overhang, steep, fill)


def tree(b, x, z, size=1, base=1):
    """A tree of the village type rooted on the ground below `base`: oak (plains), acacia (savanna), spruce (snowy, taiga), with snow on
    the crown in snowy (added by finish, after the roof snow pass). The desert has no trees. Leaves never overwrite a block."""
    p = b.palette
    log = f'{p.wood}_log'
    crown = []
    def leaf(cx, cy, cz):
        if 0 <= cx < b.size[0] and 0 <= cz < b.size[2] and cy < b.size[1] - 1 and name_at(b, cx, cy, cz) == 'minecraft:air':
            b.block(cx, cy, cz, p.hedge, leaves(p.hedge)); crown.append((cx, cy, cz))
    def layer(cx, cy, cz, r, corners=False):
        for dx in range(-r, r + 1):
            for dz in range(-r, r + 1):
                if corners or r == 0 or abs(dx) + abs(dz) < 2 * r: leaf(cx + dx, cy, cz + dz)
    y0 = base - 1
    if p.wood == 'oak':
        top = y0 + 4 + size
        b.box((x, base, z), (x, top, z), log, LOG)
        for y in (top - 1, top): layer(x, y, z, 2)
        layer(x, top + 1, z, 1, True); layer(x, top + 2, z, 1)
    elif p.wood == 'acacia':
        b.box((x, base, z), (x, y0 + 3, z), log, LOG); b.box((x + 1, y0 + 4, z), (x + 1, y0 + 4 + size, z), log, LOG)
        cy = y0 + 5 + size
        layer(x + 1, cy, z, 2); layer(x + 1, cy + 1, z, 1)
    else:
        top = y0 + 6 + size
        b.box((x, base, z), (x, top, z), log, LOG)
        for i, y in enumerate(range(y0 + 3, top + 1)): layer(x, y, z, 2 if i % 2 == 0 and y < top - 1 else 1)
        leaf(x, top + 1, z)
    if p.snow: b.__dict__.setdefault('tree_snow', []).extend(crown)


GROWS = {'minecraft:grass_block', 'minecraft:podzol', 'minecraft:dirt', 'minecraft:coarse_dirt', 'minecraft:sand'}


def plants(b, cells, salt=0):
    """The type's ground plants scattered over open ground cells (bare in some cells, never on paving)."""
    p = b.palette
    for x, z in cells:
        plant = pick(p.plants, x, z, salt)
        if plant and name_at(b, x, 1, z) == 'minecraft:air' and name_at(b, x, 0, z) in GROWS: b.block(x, 1, z, plant)


def hedge_row(b, cells):
    """A low clipped hedge of the type's leaves (a stone wall in the desert)."""
    p = b.palette
    for x, z in cells:
        if p.hedge: b.block(x, 1, z, p.hedge, leaves(p.hedge))
        else:
            sides = [s for s, (dx, dz) in (('north', (0, -1)), ('south', (0, 1)), ('east', (1, 0)), ('west', (-1, 0))) if (x + dx, z + dz) in cells]
            b.block(x, 1, z, p.fence, link(p.fence, sides))


def finish(b):
    """Roof snow only over open roof space, then snow on the exposed tops of tree crowns (snowy), then hidden light on every floor."""
    gable_snow(b)
    for x, y, z in getattr(b, 'tree_snow', ()):
        if name_at(b, x, y + 1, z) == 'minecraft:air' and y + 1 < b.size[1] - 1: b.block(x, y + 1, z, 'snow', {'layers': '1'})
    light_up(b)
    return b


# ---------------------------------------------------------------------------------------------------------- badge hall
BADGES = [(7, ['platinum_yukkuri_badge'] * 2), (6, ['gold_yukkuri_badge'] * 4), (5, ['silver_yukkuri_badge'] * 6),
          (4, ['bronze_yukkuri_badge'] * 8)]
# A gold medal hanging from a red-and-white ribbon in the pediment (a plain 2-4-4-2 disc read as a cross); the stepped desert front
# carries it one block higher.
MEDAL = {(12, 12): 'red_concrete', (13, 12): 'white_concrete', (12, 11): 'yellow_concrete', (13, 11): 'yellow_concrete',
         (11, 10): 'yellow_concrete', (12, 10): 'white_concrete', (13, 10): 'yellow_concrete', (14, 10): 'yellow_concrete',
         (12, 9): 'yellow_concrete', (13, 9): 'yellow_concrete'}


def badge_hall(p):
    """徽章会馆: the civic hall of the badge system on a two-step plinth in the type's pale wall, a columned portico with a pediment carrying
    a gold medal relief, and in front the exchange plaza: a paved square with a canopied bench ring around a table, a planted bed, lamps
    and two standing bins flanking the steps. Inside: waiting benches, a notice board, a reception counter with the badge handbook (no
    lectern), archives and a wall of displayed badges by grade behind the clerk. Vent niches in the portico plinth beside the steps are
    the wind gaps. The clerk and the hall's caretaker live behind the counter door."""
    W, H, D = 26, 16, 32
    b = Site(f'village/{p.style}/badge_hall', [W, H, D], p)
    b.box((0, 1, 0), (W - 1, H - 1, D - 1), 'air')
    street_band(b, 0, W - 1)
    lot_ground(b, 0, W - 1, 8, D - 1)
    Q = p.state(p.wall_light)
    # ---- Exchange plaza (z8..15): the town's paving, a stone-framed axis from the lane to the steps.
    for x in range(1, W - 1):
        for z in range(8, 16): b.block(x, 0, z, p.sidewalk_at(x, z))
    for z in range(8, 16):
        for x in (10, 15): b.block(x, 0, z, p.plinth_at(x, z, 2))
        for x in range(11, 15): b.block(x, 0, z, p.aisle)
    for x in range(1, W - 1): b.block(x, 0, 8, p.plinth_at(x, 8, 3))
    # ---- Main block (x3..22, z19..30) on a two-high plinth: floor y2, people walk at y3, walls y3..7, cornice y8.
    b.box((3, 0, 19), (22, 2, 30), p.plinth)
    for x, z in ring(3, 22, 19, 30): b.block(x, 1, z, p.plinth_at(x, z, 1))
    b.box((4, 2, 20), (21, 2, 29), p.aisle)
    shell(b, 3, 22, 19, 30, 3, 7, *Q)
    for x, z in ring(3, 22, 19, 30): b.block(x, 8, z, *p.state(p.band, 'x' if z in (19, 30) else 'z'))
    b.box((4, 8, 20), (21, 8, 29), p.planks())                                     # ceiling
    posts(b, [(x, 19) for x in (3, 7, 18, 22)] + [(x, 30) for x in (3, 8, 12, 13, 17, 22)] + [(x, z) for x in (3, 22) for z in (23, 26)], 3, 7)
    windows(b, [(x, 19) for x in (4, 5, 6, 19, 20, 21, 9, 10, 15, 16)], 4, 6)
    windows(b, [(x, z) for x in (3, 22) for z in (20, 21, 22, 24, 25, 27, 28, 29)], 4, 6)
    windows(b, [(x, 30) for x in (5, 6, 19, 20)], 4, 6)
    b.door(12, 3, 19, 'north', p.wood, 'left'); b.door(13, 3, 19, 'north', p.wood, 'right')
    windows(b, [(12, 19), (13, 19)], 5, 5)                                         # transom over the doors
    for x in (5, 20): b.block(x, 7, 18, 'red_wall_banner', {'facing': 'north'})
    # ---- Portico (x8..17, z16..18): a deck at floor height, four columns, two flights of steps down to the plaza.
    b.box((8, 0, 16), (17, 2, 18), p.plinth)
    for x in range(8, 18):
        for z in (16, 17, 18): b.block(x, 1, z, p.plinth_at(x, z, 4))
        for z in (16, 17, 18): b.block(x, 2, z, p.aisle)
    for x in range(9, 17):
        b.block(x, 0, 14, p.plinth); stairs(b, x, 1, 14, p.step, 'south')
        b.block(x, 0, 15, p.plinth); b.block(x, 1, 15, p.plinth); stairs(b, x, 2, 15, p.step, 'south')
    for x in (8, 17): b.block(x, 1, 15, p.plinth_at(x, 15, 5)); slab(b, x, 2, 15, p.step)   # cheeks of the flight
    posts(b, [(x, 16) for x in (8, 11, 14, 17)], 3, 7)
    for x in range(8, 18): b.block(x, 8, 16, *p.state(p.band, 'x'))
    for x in (8, 17):
        for z in (17, 18): b.block(x, 8, z, *p.state(p.band, 'z'))
    b.box((9, 8, 17), (16, 8, 18), p.planks())                                     # portico ceiling
    # Vent niches in the deck sides beside the steps: dry, roofed by the deck, walled by the plinth; a paper in each.
    for x in (8, 17):
        b.block(x, 1, 17, 'air'); wind_gap(b, x, 1, 17)
    # ---- Roofs: the main block's gable along x, and the portico's cross gable along z from the pediment to the back wall, which rises
    # over the main ridge. The pediment carries the medal; the desert has flat roofs and a stepped parapet front instead.
    if p.flat:
        cells = {(x, z) for x in range(3, 23) for z in range(19, 31)} | {(x, z) for x in range(8, 18) for z in range(16, 19)}
        flat_roof(b, p, cells, 9)
        for x in range(8, 18):
            k = min(x - 8, 17 - x); top = 10 + k
            for yy in range(10, top): b.block(x, yy, 16, *Q)
            slab(b, x, top, 16, p.roof_trim)
        for (x, y), block in MEDAL.items(): b.block(x, y + 1, 16, block)
    else:
        pitched(b, 3, 22, 19, 30, 9, 'x', fill=Q)
        main = {z: 9 + min(z - 19, 30 - z) // 2 for z in range(19, 31)}
        for z in range(16, 31):
            for x in range(8, 18):
                k = min(x - 8, 17 - x); h = 9 + k
                if h <= main.get(z, -1): continue
                if p.snow and k == 4: b.block(x, h, z, p.planks(p.roof)); b.block(x, h + 1, z, 'snow', {'layers': '2'})
                else: stairs(b, x, h, z, p.roof_trim if (k == 0 or z == 16 or k == 4) and p.trim_stairs else p.roof, 'east' if x < 13 else 'west')
                if z in (16, 30):
                    for yy in range(9 if z == 16 else main[z] + 1, h): b.block(x, yy, z, *Q)
        for (x, y), block in MEDAL.items(): b.block(x, y, 16, block)
    # ---- Plaza furniture. East: four posts carry a plain white cloth canopy over a bench ring around a table (badge holders meet
    # here; strays nap on the benches and shelter from rain under the cloth).
    posts(b, [(19, 9), (23, 9), (19, 13), (23, 13)], 1, 3)
    b.box((18, 4, 8), (24, 4, 14), 'white_wool')
    furniture(b, 'bench', 1, [(x, 10) for x in (20, 21, 22)]); furniture(b, 'bench', 1, [(x, 12) for x in (20, 21, 22)])
    furniture(b, 'table', 1, [(x, 11) for x in (20, 21, 22)])
    display_tray(b, 21, 2, 11, 'north', 'mcyukkuri:badge_handbook')
    b.block(21, 3, 11, 'lantern', {'hanging': 'true', 'waterlogged': 'false'})
    # West: a raised stone bed with the type's tree (pots in the desert) and the hall's notice pillar facing the street.
    for x, z in ring(2, 6, 9, 13): b.block(x, 1, z, p.plinth_at(x, z, 6))
    for x in range(3, 6):
        for z in range(10, 13): b.block(x, 1, z, *ground(p))
    if p.hedge: tree(b, 4, 11, 0, base=2)
    else:
        for i, (x, z) in enumerate([(3, 10), (5, 10), (3, 12), (5, 12), (4, 11)]): b.block(x, 2, z, p.pot(i))
    b.box((8, 1, 9), (8, 3, 9), *p.state(p.post)); slab(b, 8, 4, 9, p.step)
    notice(b, 8, 2, 8, 'north')
    # Two standing bins flank the steps, each with room to be shoved over; lamps flank the steps and light the sidewalk.
    for x in (7, 18):
        garbage(b, x, 1, 13, 'north', [('paper', 2), ('bread', 1), ('cookie', 1), ('apple', 1), ('stick', 1)])
    lamp(b, 8, 1, 14); lamp(b, 17, 1, 14); lamp(b, 1, 1, 7); lamp(b, W - 2, 1, 7)
    # Sides of the hall: a clipped hedge along the lot edges, plants at its foot.
    hedge_row(b, [(1, z) for z in range(15, 31)] + [(W - 2, z) for z in range(15, 31)])
    plants(b, [(x, z) for x in (2, 23) for z in range(16, 31, 3)], 4)
    for x, z in [(4, 18), (21, 18)]: b.block(x, 1, z, p.pot(x))
    # ---- Inside (walk y3). Red runner from the doors to the counter, waiting benches either side, a notice board by the door.
    b.strict = True
    for x in (12, 13):
        for z in range(20, 25): b.block(x, 3, z, 'red_carpet')
    for x0 in (5, 18): furniture(b, 'bench', 3, [(x, 22) for x in range(x0, x0 + 3)])
    notice(b, 8, 4, 20, 'south'); notice(b, 17, 4, 20, 'south')
    for x, z in [(4, 20), (21, 20), (4, 24), (21, 24)]: b.block(x, 3, z, p.pot(x + z))
    wall_sign(b, 11, 5, 18, ['油库里徽章会馆', '受理 · 考试', '登记 · 换章'], 'north')
    # Reception counter across the hall: boards with a slab top (no lectern), the badge handbook and a bronze badge laid out on trays;
    # end walls with the staff door at the west end.
    b.box((4, 3, 25), (5, 7, 25), p.planks()); b.box((20, 3, 25), (21, 7, 25), p.planks())
    b.door(5, 3, 25, 'north', p.wood)
    for x in range(6, 20): b.block(x, 3, 25, p.planks()); slab(b, x, 4, 25, p.wood)
    display_tray(b, 12, 4, 25, 'north', 'mcyukkuri:badge_handbook', raised=True)
    display_tray(b, 9, 4, 25, 'north', 'mcyukkuri:bronze_yukkuri_badge', raised=True)
    display_tray(b, 16, 4, 25, 'north', 'minecraft:writable_book', raised=True)
    # Behind the counter: archives (bookshelves), a desk, and the wall of badges by grade (platinum on top, bronze at the bottom).
    for z in (26, 27, 28, 29):
        b.box((4, 3, z), (4, 4, z), 'bookshelf'); b.box((21, 3, z), (21, 4, z), 'bookshelf')
    furniture(b, 'table', 3, [(7, 27), (8, 27)]); furniture(b, 'bench', 3, [(7, 28)])
    display_tray(b, 8, 4, 27, 'north', 'minecraft:book')
    for y, items in BADGES:
        x0 = 13 - len(items) // 2
        for i, item in enumerate(items): wall_item(b, x0 + i, y, 29, item, 'north', .45)
    resident(b, 12, 27, 'caretaker_sister', 2, y=3)                      # the clerk: her home is behind the counter
    resident(b, 17, 28, 'caretaker_brother', 2, ['mcy_street_local', 'mcy_street_special'], y=3)   # the hall's caretaker
    for x, z in [(8, 22), (17, 22), (12, 27)]: b.block(x, 7, z, 'lantern', {'hanging': 'true', 'waterlogged': 'false'})
    b.strict = False
    street_ends(b, W)
    return finish(b)


# ---------------------------------------------------------------------------------------------------------- shelter
def shelter(p):
    """ゆっくりんピース (油库里保护站): a modest single-storey house behind an open low-fenced yard. In the yard a green-and-white canopy
    covers three crate shelters, the free houses for strays; under the house's front shelf runs a paper-lined wind gap; a standing bin by
    the door is stocked with food. Inside: an intake counter with a hatch onto the yard, a care table with orange juice and flour paste, the
    supply shelf, and the volunteers' room. Two caring volunteers live there and tend strays with the existing care routine."""
    W, H, D = 21, 10, 24
    b = Site(f'village/{p.style}/shelter', [W, H, D], p)
    b.box((0, 1, 0), (W - 1, H - 1, D - 1), 'air')
    street_band(b, 0, W - 1)
    lot_ground(b, 0, W - 1, 8, D - 1)
    # ---- Yard: worn ground, a low fence along the street and the sides with a wide open entrance (strays walk in), a path to the door.
    no_carton(b, (0, 1, 8), (W - 1, 3, 14), (10, 1, 11))
    for x in range(8, 18):
        for z in range(9, 16):
            if x <= 12 or z == 15: b.block(x, 0, z, pick(((p.lane, 3),) + p.sidewalk, x, z, 8))
    fence = [(x, 8) for x in range(0, W) if not 8 <= x <= 12] + [(x, z) for x in (0, W - 1) for z in range(9, 15)]
    for x, z in fence:
        sides = [s for s, (dx, dz) in (('north', (0, -1)), ('south', (0, 1)), ('east', (1, 0)), ('west', (-1, 0))) if (x + dx, z + dz) in fence]
        b.block(x, 1, z, p.fence, link(p.fence, sides))
    # ---- Canopy over the free houses: posts and green/white stripes at y4; three crate shelters open onto the yard path.
    posts(b, [(x, z) for x in (3, 17) for z in (9, 14)], 1, 3)
    for x in range(3, 18):
        for z in range(9, 15): b.block(x, 4, z, 'green_wool' if (x // 2) % 2 == 0 else 'white_wool')
    shelter_spot(b, 6, 1, 10, 'crate_shelter', 'east'); shelter_spot(b, 6, 1, 13, 'crate_shelter', 'east')
    shelter_spot(b, 14, 1, 12, 'crate_shelter', 'west')
    b.block(10, 3, 12, 'lantern', {'hanging': 'true', 'waterlogged': 'false'})
    for x, z in [(14, 9), (15, 9), (16, 10)]: b.block(x, 1, z, p.pot(x + z))
    # ---- The house (x2..18, z16..23): plinth course, plank walls, frame posts, a low gable roof; floor y0, people walk at y1.
    b.box((2, 0, 16), (18, 0, 23), p.plinth); b.box((3, 0, 17), (17, 0, 22), p.floor)
    for x, z in ring(2, 18, 16, 23): b.block(x, 1, z, p.plinth_at(x, z, 2)); b.box((x, 2, z), (x, 3, z), p.wall)
    posts(b, [(2, 16), (18, 16), (2, 23), (18, 23), (11, 16), (11, 23), (8, 16)], 1, 3)
    roof(b, 2, 18, 16, 23, 4, 'x')
    windows(b, [(5, 23), (6, 23), (14, 23), (15, 23), (2, 19), (2, 20), (18, 19), (18, 20)], 2, 2)
    b.door(10, 1, 16, 'north', p.wood)
    wall_sign(b, 10, 3, 15, ['油库里保护站', '免费小屋 请自取', '领养 · 咨询'], 'north')
    # Front shelf west of the door: boards at y2 with pots and folded blankets on top; the dry run under it is the wind gap.
    for x in range(3, 8):
        b.block(x, 2, 15, p.planks()); wind_gap(b, x, 1, 15, paper=x in (4, 6))
    for x in (3, 5, 7): b.block(x, 3, 15, p.pot(x))
    # Standing bin by the door, stocked with food for strays; room on both sides to be shoved over.
    garbage(b, 9, 1, 15, 'north', [('bread', 2), ('apple', 2), ('carrot', 1), ('mcyukkuri:oil_grain_budget', 2), ('paper', 1)])
    # Intake counter in the front wall east of the door: boards, a slab top, a hatch above it onto the yard.
    for x in (13, 14, 15):
        b.block(x, 1, 16, p.planks()); slab(b, x, 2, 16, p.wood); b.block(x, 3, 16, 'air')
    display_tray(b, 13, 2, 16, 'north', ('mcyukkuri:orange_juice', 2), raised=True)
    display_tray(b, 15, 2, 16, 'north', 'mcyukkuri:wash_cloth', raised=True)
    lamp(b, 1, 1, 7); lamp(b, W - 2, 1, 7); lamp(b, 12, 1, 15, 2)
    # ---- Inside. West room: care table and the supply shelf. East room (behind its own door): the intake counter and the volunteers'
    # cots.
    b.strict = True
    b.box((11, 1, 17), (11, 3, 22), p.wall); b.door(11, 1, 18, 'east', p.wood)
    furniture(b, 'table', 1, [(6, 19), (7, 19)])
    for x, item in [(6, 'mcyukkuri:orange_juice'), (7, 'mcyukkuri:flour_paste')]: display_tray(b, x, 2, 19, 'north', item)
    furniture(b, 'bench', 1, [(5, 19)], 'z'); furniture(b, 'bench', 1, [(8, 19)], 'z')
    for x in range(3, 9): b.block(x, 2, 22, p.planks())
    for x, item in zip(range(3, 6), ['oil_grain_regular', 'oil_grain_regular', 'swaddle_cloth']):
        produce_crate(b, x, 3, 22, 'north', [('mcyukkuri:' + item, 3), ('mcyukkuri:' + item, 2)])
    for x, item in [(6, 'wash_cloth'), (7, 'yukkuri_carrier'), (8, 'food_bowl')]: display_tray(b, x, 3, 22, 'north', 'mcyukkuri:' + item)
    b.block(3, 1, 22, 'hay_block', LOG); b.block(4, 1, 22, 'hay_block', LOG)
    b.block(3, 1, 17, p.pot(1)); b.block(10, 1, 22, p.pot(2))
    furniture(b, 'bench', 1, [(13, 22), (14, 22)]); furniture(b, 'bench', 1, [(16, 22), (17, 22)])
    for x, z in [(13, 21), (14, 21), (16, 21), (17, 21)]: b.block(x, 1, z, 'green_carpet')
    for x, z in [(6, 18), (14, 19)]: b.block(x, 3 if p.flat else 4, z, 'lantern', {'hanging': 'true', 'waterlogged': 'false'})
    resident(b, 14, 18, 'caretaker_sister', 1)           # volunteer at the intake counter
    resident(b, 15, 20, 'caretaker_brother', 1)          # volunteer
    b.strict = False
    street_ends(b, W)
    return finish(b)


# ---------------------------------------------------------------------------------------------------------- park
def park(p):
    """公园: a green rest stop. A clipped hedge with a wide gap to the street, a path loop of the lane block around a lawn, a small pond
    (one block deep, a dry grass bank) with benches facing it, a roofed pavilion with a bench (residents' rain cover), the type's trees and
    plants, and the gardeners' lodge at the back where two residents live. Desert: no trees; pergolas with a wool shade, pots and dead bushes."""
    W, H, D = 23, 13, 27
    b = Site(f'village/{p.style}/park', [W, H, D], p)
    b.box((0, 1, 0), (W - 1, H - 1, D - 1), 'air')
    street_band(b, 0, W - 1)
    lot_ground(b, 0, W - 1, 8, D - 1)
    # Hedge on the street side and both lot edges, open between x9 and x13.
    hedge_row(b, [(x, 8) for x in range(0, W) if not 9 <= x <= 13] + [(x, z) for x in (0, W - 1) for z in range(9, D)])
    # Path: a spine from the gate and a loop around the lawn (z10..20), worn at the edges.
    path = {(x, z) for x in range(10, 13) for z in range(8, 10)} | {(x, z) for x in range(2, 21) for z in (10, 20)} | \
           {(x, z) for x in (2, 20) for z in range(10, 21)} | {(x, z) for x in range(16, 18) for z in range(21, 23)}
    for x, z in path: b.block(x, 0, z, p.lane_at(x, z))
    # Pond west of centre: water at y0, one deep, rounded; the bank stays dry ground. A stone rim in the desert.
    pond = {(x, z) for x in range(5, 10) for z in range(13, 18)} - {(5, 13), (9, 13), (5, 17), (9, 17)}
    for x, z in pond: b.block(x, 0, z, 'water', {'level': '0'})
    if p.flat:
        for x in range(4, 11):
            for z in range(12, 19):
                if (x, z) not in pond and any((x + dx, z + dz) in pond for dx in (-1, 0, 1) for dz in (-1, 0, 1)): b.block(x, 0, z, p.plinth)
    # Benches facing the pond from the north and south paths.
    furniture(b, 'bench', 1, [(x, 11) for x in (6, 7, 8)]); furniture(b, 'bench', 1, [(x, 19) for x in (6, 7, 8)])
    # Pavilion (x14..18, z12..16): plank floor, four posts, a roof of the type; a bench and a lantern under it.
    for x in range(14, 19):
        for z in range(12, 17): b.block(x, 0, z, p.floor)
    for x in range(15, 18): b.block(x, 0, 11, p.lane_at(x, 11))
    posts(b, [(14, 12), (18, 12), (14, 16), (18, 16)], 1, 3)
    if p.flat:
        for x in range(13, 20):
            for z in range(11, 18): slab(b, x, 4, z, p.roof_trim if x in (13, 19) or z in (11, 17) else p.roof)
        b.block(16, 3, 14, 'lantern', {'hanging': 'true', 'waterlogged': 'false'})
    else:
        pitched(b, 14, 18, 12, 16, 4, 'x', overhang=1, steep=True, fill='open')
        b.block(16, 5, 14, 'lantern', {'hanging': 'true', 'waterlogged': 'false'})
    furniture(b, 'bench', 1, [(x, 16) for x in (15, 16, 17)])
    # Trees of the type, or shade pergolas in the desert.
    if p.hedge:
        tree(b, 9 if p.wood == 'acacia' else 10, 17, 1); tree(b, 4, 23, 0); tree(b, 10, 23, 1)
    else:
        for x0, z0 in [(10, 13), (7, 22)]:
            posts(b, [(x0, z0), (x0 + 2, z0), (x0, z0 + 2), (x0 + 2, z0 + 2)], 1, 3)
            for x in range(x0 - 1, x0 + 4):
                for z in range(z0, z0 + 3): b.block(x, 4, z, 'orange_wool' if x % 2 else 'white_wool')
            for x in range(x0, x0 + 3): b.block(x, 4, z0 - 1, p.beam, None); b.block(x, 4, z0 + 3, p.beam, None)
        for i, (x, z) in enumerate([(4, 21), (12, 22), (14, 19), (3, 12)]): b.block(x, 1, z, p.pot(i))
    # Lawn plants (bare where the type has none), clear of paths, benches and the pond.
    lawn = [(x, z) for x in range(3, 20) for z in range(11, 20) if (x, z) not in path and (x, z) not in pond]
    plants(b, [(x, z) for x, z in lawn if (x * 7 + z * 3) % 5 == 0 and not 13 <= x <= 19], 1)
    plants(b, [(x, z) for x in range(1, 15) for z in range(21, 26) if (x * 5 + z) % 4 == 0], 2)
    # Gardeners' lodge (x15..21, z21..25): a small house of the type, door onto the path; two residents live in it.
    lodge = (15, 21, 22, 26)
    x0, x1, z0, z1 = lodge
    b.box((x0, 0, z0), (x1, 0, z1), p.plinth); b.box((x0 + 1, 0, z0 + 1), (x1 - 1, 0, z1 - 1), p.floor)
    for x, z in ring(x0, x1, z0, z1): b.block(x, 1, z, p.plinth_at(x, z, 7)); b.box((x, 2, z), (x, 3, z), p.wall)
    posts(b, [(x0, z0), (x1, z0), (x0, z1), (x1, z1)], 1, 3)
    roof(b, x0, x1, z0, z1, 4, 'z', overhang=0, steep=True)
    b.door(17, 1, z0, 'north', p.wood)
    windows(b, [(20, z0), (x1, 24), (x0, 24)], 2, 2)
    b.block(19, 1, 21, p.tree_log, {'axis': 'x'} if p.tree_log.endswith('_log') else LOG)   # a log by the door
    lamp(b, 14, 1, 21, 2)
    # Bins: a standing one at the gate, one knocked over by the south bench.
    garbage(b, 9, 1, 9, 'north', [('paper', 2), ('bread', 1), ('apple', 1), ('cookie', 1)])
    garbage(b, 10, 1, 19, 'west', [('paper', 1), ('bread', 1), ('stick', 2), ('rotten_flesh', 1)], tipped=True)
    lamp(b, 1, 1, 7); lamp(b, W - 2, 1, 7); lamp(b, 3, 1, 9); lamp(b, 19, 1, 9)
    b.strict = True
    b.block(18, 3 if p.flat else 6, 24, 'lantern', {'hanging': 'true', 'waterlogged': 'false'})
    furniture(b, 'table', 1, [(18, 25)]); furniture(b, 'bench', 1, [(17, 25)], 'z')
    resident(b, 16, 24, 'caretaker_brother', 2, ['mcy_street_local'])
    resident(b, 20, 24, 'oniisan', 2, ['mcy_street_local', 'mcy_street_special'])
    b.strict = False
    street_ends(b, W)
    return finish(b)


# ---------------------------------------------------------------------------------------------------------- side street
def side_street(p):
    """街角小店: a 3-wide lane leaves the main street between a corner shop and a two-storey house and runs south to a vanilla street
    connector. The corner shop sells through a hatch onto the sidewalk under a blue-and-white awning, with a newspaper stack and a loose
    sheet beside it; on the lane side its raised produce shelf, under the same awning, has the wind gap beneath. The shopkeeper lives
    behind the counter; two neighbours live in the house."""
    W, H, D = 18, 15, 22
    b = Site(f'village/{p.style}/side_street', [W, H, D], p)
    b.box((0, 1, 0), (W - 1, H - 1, D - 1), 'air')
    street_band(b, 0, W - 1)
    lot_ground(b, 0, W - 1, 8, D - 1)
    # ---- The lane (x7..9) from the main street to the connector, setts and worn edges where it meets the paving.
    for x in range(6, 11):
        for z in range(6, D):
            if 7 <= x <= 9: b.block(x, 0, z, p.lane_at(x, z))
            elif z <= 9 or (x * 3 + z) % 4 == 0: b.block(x, 0, z, p.sidewalk_at(x, z))
    # ---- Corner shop (x0..5, z8..17): plinth, plank walls, frame posts, a steep gable facing the street.
    b.box((0, 0, 8), (5, 0, 17), p.plinth); b.box((1, 0, 9), (4, 0, 16), p.floor)
    for x, z in ring(0, 5, 8, 17): b.block(x, 1, z, p.plinth_at(x, z, 8)); b.box((x, 2, z), (x, 5, z), p.wall)
    posts(b, [(0, 8), (5, 8), (0, 17), (5, 17), (5, 12)], 1, 5)
    for x in range(0, 6): b.block(x, 5, 8, *p.state(p.beam, 'x'))
    roof(b, 0, 5, 8, 17, 6, 'z', overhang=0, steep=True)
    # Hatch onto the sidewalk: a board counter carrying open crates of real goods, an opening above it.
    for x in range(1, 5): b.block(x, 1, 8, p.planks()); b.box((x, 3, 8), (x, 4, 8), 'air')
    for x, item in [(1, 'minecraft:bread'), (2, 'mcyukkuri:sweet_candy'), (3, 'minecraft:apple'), (4, 'mcyukkuri:oil_grain_budget')]:
        produce_crate(b, x, 2, 8, 'north', [(item, 3), (item, 2)])
    # Blue/white awning over the hatch and along the lane side; the newspaper stack and a loose sheet on the sidewalk.
    for x in range(0, 6):
        wool = 'blue_wool' if x % 2 == 0 else 'white_wool'
        b.block(x, 5, 7, wool); b.block(x, 4, 6, wool)
    for z in range(9, 17):
        wool = 'blue_wool' if z % 2 == 0 else 'white_wool'
        # Keep the paper nooks below the inner edge outside FoundShelter's three-block wool search.
        b.block(6, 5, z, wool); b.block(7, 4, z, wool)
    b.block(1, 1, 6, 'mcyukkuri:newspaper_stack', {'bundles': '3', 'facing': 'east'})
    b.block(2, 1, 6, 'mcyukkuri:newspaper_stack', {'bundles': '1', 'facing': 'north'})
    paper(b, 3, 1, 6)
    # Raised produce shelf on the lane side: boards at y2, produce on top; the dry run under it is the wind gap.
    for z in range(10, 15):
        b.block(6, 2, z, p.planks()); wind_gap(b, 6, 1, z, paper=z in (11, 13))
    for z, block in zip(range(10, 15), ('pumpkin', 'melon', 'hay_block', 'pumpkin', 'melon')):
        b.block(6, 3, z, block, LOG if block == 'hay_block' else None)
    b.door(5, 1, 15, 'east', p.wood)
    garbage(b, 6, 1, 9, 'east', [('paper', 2), ('bread', 1), ('apple', 1), ('cookie', 1), ('stick', 1)])
    # ---- Two-storey house (x10..17, z9..19): stone ground floor, timber-framed upper floor, steep gable to the street.
    b.box((10, 0, 9), (17, 0, 19), p.plinth); b.box((11, 0, 10), (16, 0, 18), p.floor)
    for x, z in ring(10, 17, 9, 19):
        b.block(x, 1, z, p.plinth_at(x, z, 9))
        b.box((x, 2, z), (x, 4, z), p.plinth); b.block(x, 5, z, *p.state(p.beam, 'x' if z in (9, 19) else 'z'))
        b.box((x, 6, z), (x, 8, z), p.wall)
    b.box((11, 5, 10), (16, 5, 18), p.planks())                                    # upper floor
    posts(b, [(10, 9), (17, 9), (10, 19), (17, 19), (10, 14), (17, 14), (13, 9), (14, 19)], 6, 8)
    roof(b, 10, 17, 9, 19, 9, 'z', overhang=0, steep=True)
    b.door(13, 1, 9, 'north', p.wood)
    windows(b, [(11, 9), (15, 9), (16, 9), (10, 11), (10, 12), (10, 16), (10, 17), (17, 12), (17, 16)], 2, 3)
    windows(b, [(11, 9), (12, 9), (15, 9), (16, 9), (10, 11), (10, 12), (10, 16), (10, 17), (17, 11), (17, 17), (12, 19), (16, 19)], 6, 7)
    for x in (12, 14): b.block(x, 1, 8, p.pot(x))
    # Stairs to the upper floor along the east wall.
    for i, z in enumerate(range(11, 16)):
        stairs(b, 16, 1 + i, z, p.wood, 'south')
        if z < 15: b.block(16, 5, z, 'air')
    # ---- Lane end: a lamp and a bollard where the lane leaves for the village; a lamp at the corner.
    lamp(b, 6, 1, 19); b.block(11, 1, 20, p.plinth); slab(b, 11, 2, 20, p.step)
    lamp(b, 1, 1, 1); lamp(b, W - 2, 1, 7)
    # ---- Inside.
    b.strict = True
    for z, item in zip(range(10, 16), ['minecraft:bread', 'minecraft:apple', 'mcyukkuri:oil_grain_regular', 'minecraft:candle',
                                        'mcyukkuri:sweet_candy', 'minecraft:paper']):
        b.block(1, 2, z, p.planks()); produce_crate(b, 1, 3, z, 'east', [(item, 3), (item, 2)])
    b.block(2, 5 if p.flat else 7, 12, 'lantern', {'hanging': 'true', 'waterlogged': 'false'})
    resident(b, 2, 9, 'caretaker_brother', 2)                                       # the shopkeeper, home behind the counter
    furniture(b, 'table', 1, [(12, 13), (13, 13)]); furniture(b, 'bench', 1, [(12, 12), (13, 12)]); furniture(b, 'bench', 1, [(12, 14), (13, 14)])
    for x, z in [(11, 17), (12, 17), (13, 17)]: b.block(x, 1, z, 'brown_carpet')
    b.block(13, 4, 15, 'lantern', {'hanging': 'true', 'waterlogged': 'false'})
    furniture(b, 'bench', 6, [(11, 17), (12, 17)]); b.box((11, 6, 10), (11, 7, 10), 'bookshelf')
    for x, z in [(12, 12), (13, 16)]: b.block(x, 6, z, 'red_carpet')
    locals_(b, (11, 17), (15, 17))
    b.strict = False
    street_ends(b, W)
    jigsaw(b, 8, 1, D - 1, 'south', 'minecraft:street', 'minecraft:street', f'minecraft:village/{p.style}/streets')
    return finish(b)


# ---------------------------------------------------------------------------------------------------------- mouths
def mouth(p, east):
    """The end of the main street (mouth_east on the market end, mouth_west on the civic end): the sidewalks end at a lamp post and a
    bollard, the lane narrows to the type's vanilla path with scattered setts and leaves in a `minecraft:street` connector; a bench and
    a notice board on a post stand at the corner."""
    W, H, D = 9, 6, 8
    name = 'mouth_east' if east else 'mouth_west'
    b = Site(f'village/{p.style}/{name}', [W, H, D], p)
    b.box((0, 1, 0), (W - 1, H - 1, D - 1), 'air')
    X = (lambda x: x) if east else (lambda x: W - 1 - x)        # authored for the east end; mirrored for the west
    for u in range(W):
        x = X(u)
        for z in range(D):
            fray = u <= 3 or u == 4 and (x + 2 * z) % 3 != 0 or u == 5 and (3 * x + z) % 4 == 0   # sidewalks give out over two columns
            if 3 <= z <= 5: block = p.lane_at(x, z)
            elif fray: block = p.sidewalk_at(x, z)
            else: block = None
            if block: b.block(x, 0, z, block)
            else:
                g = p.ground_at(x, z); b.block(x, 0, z, g, {'snowy': 'false'} if g == 'grass_block' else None)
    lamp(b, X(4), 1, 7); b.block(X(4), 1, 1, p.plinth); slab(b, X(4), 2, 1, p.step)
    furniture(b, 'bench', 1, [(X(u), 7) for u in (1, 2, 3)])
    b.box((X(6), 1, 0), (X(6), 3, 0), *p.state(p.post)); slab(b, X(6), 4, 0, p.step)
    notice(b, X(6), 2, 1, 'south')
    plants(b, [(X(u), z) for u in (5, 6, 7, 8) for z in (0, 6, 7)], 5)
    if east: jigsaw(b, 0, 1, 4, 'west', 'mcyukkuri:street_east')
    else: jigsaw(b, W - 1, 1, 4, 'east', 'mcyukkuri:street_west')
    jigsaw(b, X(W - 1), 1, 4, 'east' if east else 'west', 'minecraft:street', 'minecraft:street', f'minecraft:village/{p.style}/streets')
    return finish(b)


# ---------------------------------------------------------------------------------------------------------- export
def pieces(style='plains'):
    p = PALETTES[style]
    return {'badge_hall': badge_hall(p), 'shelter': shelter(p), 'park': park(p), 'side_street': side_street(p),
            'mouth_east': mouth(p, True), 'mouth_west': mouth(p, False)}


def build():
    """Every template of this module in every village type (scripts/export.py --family human-world saves and records them)."""
    for style in STYLES: yield from pieces(style).values()


if __name__ == '__main__':
    print(json.dumps([{'name': b.name, 'size': b.size, 'blocks': len(b.blocks), 'entities': len(b.entities)} for b in build()], ensure_ascii=False))
