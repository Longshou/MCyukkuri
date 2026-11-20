"""Block-style placed care devices and carriers (kit.py). Footprints and heights keep the CareBlock collision and the
contact heights the game uses (toilet sand 2, waterer rim 2); the dye styles keep their mesh names and
life/CareStyles.java's dye table. The food bowl and its fills are round (care-props/bowl.py).

waterer: the bowl's sibling for water, radially symmetric (no facing). A stepped saucer 14 across (an octagon on a foot
inset by one cell), only 2 high so the `drink` clip's low mouth meets the rim (the bowl is 3), with a blue rim line where
the bowl has its red one; on a cork collar in the middle
stands an inverted glass jar, 6 across and 9 high, its top at y 12. The glass is cut-out like vanilla glass: lit frame rows,
solid stepped corners and clear panes with a glint, nothing drawn on its inside. waterer_water is the water alone, drawn
inside the waterer like a bowl fill: one 4 x 4 row of the jar per portion (tank_u1 at the bottom to tank_u8, units 1-8)
and the saucer's water ring (dish_water, unit 1), so drawing units 1..k shows a jar k rows full over a wet saucer and
drawing none shows it dry.

yukkuri_toilet and three dye styles, each with a dirty state: a rectangular litter tray 14 x 12, 3 high, clearly a tray
beside the round bowl: a one-cell plastic rim with clipped corners round sand at y 2 in pixel grain. The dirty tray has
damp clumps in the sand and two droppings.

Carriers: an open woven basket with an arched handle, in the three icon tints (natural STRAW, blue NAVYW, green SAGEW)
with the pale lining folded over below the rim; the +Z front opens down to the y=1 floor with stepped wicker shoulders so
seated occupants' faces stay visible (the fine carrier adds open-weave side viewing windows and iron handle clasps; the
large carrier is two units wider and deeper and one taller, with its arched handle moved to the rear rim so it never
crosses the multi-occupant seats).
"""
import importlib.util, sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
if 'block_props_kit' not in sys.modules:   # loaded under its own name: care-props also has a `kit`
    _spec = importlib.util.spec_from_file_location('block_props_kit', HERE / 'kit.py')
    sys.modules['block_props_kit'] = _module = importlib.util.module_from_spec(_spec)
    _spec.loader.exec_module(_module)
kit = sys.modules['block_props_kit']


def mix(*values):
    """A fixed per-cell hash in 0..15, so grain and strand patterns stay put between exports."""
    h = 0
    for v in values:
        h = (h * 31 + v * 17 + 7) & 0xffff
    return (h ^ (h >> 5)) & 15


# =====================================================================================================================
# Waterer. Saucer rows: 0 foot (the inset), 1 wall; up faces: rim top at y 2 and the floor at y 1. The cork collar
# stands on the floor to y 3; the glass jar is rows 3-10 with a stepped roof at 11.
SAUCER = kit.octagon(7, 2)                           # 14 across, two one-cell steps per corner
WELL = kit.inset(SAUCER)                             # foot, floor and the water ring's plan
JAR = kit.octagon(3, 1)                              # collar and jar plan: 6 across with the corners cut
JAR_CORE = kit.inset(JAR)                            # the 4 x 4 the water fills inside the one-cell glass
JAR_Y, JAR_ROWS = 3, 8                               # first glass row; one row per portion


def saucer_glaze(cell, face):
    y = cell[1]
    if face == 'up':
        return ('WHITE', 4) if y else ('CREAM', 4)                 # lit rim; floor
    if face == 'down':
        return ('CREAM', 2)
    if kit.inward(cell, face):
        return ('CREAM', 3)
    return ('SKY', 2) if y else ('CREAM', 3)                       # the bowl's rim line in blue; foot


def cork(cell, face):
    x, y, z = cell
    if face == 'up':
        return ('CORK', 3)
    return ('CORK', 1 if y == 1 or mix(x, y, z) < 3 else 2)        # darker where it stands in the water; cork flecks


def jar_glass(cell, face):
    x, y, z = cell
    if face == 'down' or kit.inward(cell, face):
        return kit.CLEAR                                           # nothing inside: the water is drawn there
    if face == 'up':
        return ('WHITE', 4) if (x, z) == (-2, -2) else ('GLASS', 3)        # the jar's base, a glint at its top left
    along, depth = (x, z) if face in ('north', 'south') else (z, x)
    if y == JAR_Y + JAR_ROWS or depth not in (-3, 2):
        return ('GLASS', 3)                                        # roof edge; the one-cell corner steps: the frame
    if y == JAR_Y:
        return ('GLASS', 2)
    if y == JAR_Y + JAR_ROWS - 1:
        return ('GLASS', 3)
    if (along, y) in ((-2, 7), (-1, 8), (1, 5)):
        return ('WHITE', 4)                                        # glints on the pane
    return kit.CLEAR


def waterer():
    b = kit.Block('waterer')
    ceramic = kit.Material('WHITE', top=0, bottom=0, bevel=False, pattern=saucer_glaze)
    dish = b.part('dish')
    dish.layer(WELL, 0, 1, ceramic)                                # foot and floor
    dish.layer(SAUCER - WELL, 1, 2, ceramic)                         # wall and rim
    b.part('collar').layer(JAR, 1, JAR_Y, kit.Material('CORK', bevel=False, pattern=cork))
    glass = kit.Material('GLASS', bevel=False, pattern=jar_glass)
    jar = b.part('jar')
    jar.layer(JAR - JAR_CORE, JAR_Y, JAR_Y + JAR_ROWS, glass)
    jar.layer(JAR_CORE, JAR_Y + JAR_ROWS, JAR_Y + JAR_ROWS + 1, glass)     # the roof steps in one cell
    return b


def waterer_water():
    b = kit.Block('waterer_water')

    def ripple(cell, face):
        x, y, z = cell
        if face == 'up':
            return 2 if mix(x, z) < 2 else 1                       # a few white ripples on the surface
        return 0
    water = kit.Material('WATER', step=2, top=0, bottom=0, bevel=False, pattern=ripple)
    b.part('dish_water', 1).layer(WELL - JAR, 1, 2, water)
    for k in range(1, JAR_ROWS + 1):
        b.part(f'tank_u{k}', k).layer(JAR_CORE, JAR_Y + k - 1, JAR_Y + k, water)
    return b


# =====================================================================================================================
# Sand toilet: CareBlock's sand tray box is 14.46 x 11.58 and 2.35 high; the sand top is at y 2.
TRAY = kit.octagon(7, 1, half_z=6)
SAND = kit.octagon(6, 1, half_z=5)                   # the sand's corners are clipped too, so the rim is closed round them
TRAY_TOP = 2
SAND_MIDDLE = kit.inset(SAND)
PLASTICS = {'': 'MINTC', 'pink': 'ROSE', 'lemon': 'BLOND', 'sky': 'SKY'}
DROPPINGS = (((1, -1), 2), ((-4, 2), 1))                  # (plan centre, size) of the dropped swirls in a dirty tray


def plastic(ramp):
    def paint(cell, face):
        x, y, z = cell
        if face == 'up':
            return (ramp, 4)
        if face == 'down':
            return (ramp, 0)
        if kit.inward(cell, face):
            return (ramp, 1 if y < TRAY_TOP else 2)               # shaded inside above the sand
        return (ramp, (1, 2, 3)[min(y, 2)])                        # contact row, wall, lit lip
    return kit.Material(ramp, pattern=paint, bevel=False)


def sand(dirty):
    def paint(cell, face):
        x, y, z = cell
        if face != 'up':
            return ('SAND', 1)
        if dirty and mix(x, z, 3) == 0 or dirty and (x, z) in {(-1, 3), (0, 3), (3, -2), (4, 1)}:
            return ('ANKO', 2)                                     # damp clumps
        if dirty and mix(x, z, 5) < 2:
            return ('SAND', 0)
        h = mix(x, z)
        if (x, z) not in SAND_MIDDLE:
            return ('SAND', 2)                                     # shaded where the sand meets the wall
        return ('SAND', 4) if h < 3 else ('SAND', 2) if h > 12 else ('SAND', 3)
    return kit.Material('SAND', pattern=paint, bevel=False)


def swirl(shape, centre, size, base_y, material):
    """A dropping: a stepped soft cone of `size` tiers (1 or 2) on base_y."""
    cx, cz = centre
    core = {(cx, cz), (cx - 1, cz), (cx, cz - 1), (cx - 1, cz - 1)}
    tiers = {2: [kit.octagon(2, 1, (cx, cz)), core],
             1: [core]}[size]
    for k, plan in enumerate(tiers):
        shape.layer(plan, base_y + k, base_y + k + 1, material)


def dropping_material():
    def paint(cell, face):
        x, y, z = cell
        if face == 'up':
            return 1
        return 1 if (x + z + y) % 3 == 0 else -1 if (x - z + y) % 4 == 0 else 0
    return kit.Material('ANKO', step=2, top=1, bottom=-1, bevel=False, pattern=paint)


def toilet(style='', dirty=False):
    base = f'yukkuri_toilet_{style}' if style else 'yukkuri_toilet'
    b = kit.Block(base + ('_dirty' if dirty else ''))
    tray = b.part('tray')
    rim = plastic(PLASTICS[style])
    tray.layer(TRAY - SAND, 0, TRAY_TOP + 1, rim)
    tray.layer(SAND, 0, TRAY_TOP, sand(dirty))
    if dirty:
        drop = b.part('droppings')
        for centre, size in DROPPINGS:
            swirl(drop, centre, size, TRAY_TOP, dropping_material())
    return b


def yukkuri_toilet(style=''):
    return toilet(style)


def yukkuri_toilet_dirty(style=''):
    return toilet(style, True)


# =====================================================================================================================
# Carriers: (half width, half depth, body height, wicker ramp, lining ramp).
# Occupants face +Z on the y=1 floor (inner footprint 8x6 for plain/fine, 10x8 for large). At render scales 0.18-0.40
# eyes sit at y=1.57-3.63 and faces at y=1.05-4.20, so the +Z front opens down to y=1 and the large handle moves to the
# rear rim (z=-6..-5) so it never crosses the multi-occupant seats from above.
CARRIERS = {'yukkuri_carrier': (6, 5, 7, 'STRAW', 'CREAM'), 'yukkuri_carrier_fine': (6, 5, 7, 'NAVYW', 'PAPER'),
            'yukkuri_carrier_large': (7, 6, 8, 'SAGEW', 'PAPER')}


def carrier(name):
    hx, hz, top, wicker, lining = CARRIERS[name]
    fine = name == 'yukkuri_carrier_fine'
    large = name == 'yukkuri_carrier_large'
    b = kit.Block(name)
    plan = kit.octagon(hx, 2, half_z=hz)
    foot = kit.inset(plan)
    inner = kit.inset(foot)
    wall_ring = plan - foot
    open_x = range(-4, 4) if large else range(-3, 3)

    # Local wall height on the outer ring: wide +Z front opening down to y=1, stepped shoulders rising to full height.
    def wall_top(x, z):
        if z == hz - 1:
            if x in open_x:
                return 0
            return 5 if fine else 4
        if z == hz - 2 and x in (-hx + 1, hx - 2):
            return 6 if fine else 5
        if z == hz - 3 and x in (-hx, hx - 1):
            return top if fine else 6
        if large and z in (1, 2) and x in (-hx, hx - 1):
            return 7
        return top

    # Precompute basket cells so weave() knows local rims, cut edges and window frames.
    wall_cells = set()
    for x, z in foot:
        wall_cells.add((x, 0, z))
    for x, z in foot - inner:
        if not (z == hz - 2 and x in open_x):
            wall_cells.add((x, 1, z))
    for x, z in wall_ring:
        h = wall_top(x, z)
        for y in range(2, h):
            # Fine basket: framed open-weave viewing windows on both sides forward of the handle posts (solid sill at y=2).
            if fine and x in (-hx, hx - 1) and z in (1, 2) and 3 <= y <= 4:
                continue
            wall_cells.add((x, y, z))

    def weave(cell, face):
        x, y, z = cell
        if face == 'down':
            return (wicker, 0)
        if face == 'up':
            if y == 0:
                if z == hz - 2 and x in open_x:
                    return (wicker, 3)                             # woven threshold across the open front
                return (lining, 3)                                 # lined floor
            return (wicker, 4)                                     # lit braided rim on any wall top or sill
        # Cut edges bordering the front opening or fine side windows read as bound wicker trim, not raw lining.
        if face in ('east', 'west') and (x, z) in wall_ring and z >= hz - 3:
            if (x + (1 if face == 'east' else -1), y, z) not in wall_cells and not kit.inward(cell, face):
                return (wicker, 3 if y % 2 else 2)
        if fine and x in (-hx, hx - 1) and face in ('north', 'south') and z in (0, 3) and 2 <= y <= 4:
            return (wicker, 3 if y % 2 else 2)
        if kit.inward(cell, face):
            return (lining, 2 if y < top - 2 else 3)               # the lining inside
        is_rim = (x, y + 1, z) not in wall_cells and y >= 2
        if is_rim:
            return (wicker, 4 if (x + z) % 2 else 3)              # braided rim
        if y == top - 2 and (x, y + 1, z) in wall_cells:
            return (lining, 3)                                     # the lining folded over below the full rim
        k = (x + z + 2 * y) % 4                                    # over-under weave, rows offset by two
        return (wicker, 3 if k == 0 else 1 if k == 3 else 2)

    basket = kit.Material(wicker, pattern=weave, bevel=False)
    body = b.part('basket')
    for cell in sorted(wall_cells):
        body.cell(cell, basket)

    def cane(cell, face):
        x, y, z = cell
        if (fine or large) and y == top:
            return ('IRON', 4 if face == 'up' else 3 if (x + z) % 2 else 2)  # iron handle clasps (matches recipe)
        return (wicker, 4 if face == 'up' else 3 if (x + y) % 2 else 2)

    handle = b.part('handle')
    cane_material = kit.Material(wicker, pattern=cane, bevel=False)
    if large:
        # Rear-mounted arched handle over the back rim (z=-6..-5), completely behind the 10x8 seating area (z=-4..4).
        for x in (-hx + 1, hx - 2):
            handle.box((x, top, -hz + 1), (x + 1, top + 1, -hz + 2), cane_material)
        for x in (-hx + 2, hx - 3):
            handle.box((x, top, -hz), (x + 1, top + 3, -hz + 1), cane_material)
        handle.box((-hx + 3, top + 2, -hz), (-hx + 4, top + 3, -hz + 1), cane_material)
        handle.box((hx - 4, top + 2, -hz), (hx - 3, top + 3, -hz + 1), cane_material)
        handle.box((-hx + 3, top + 3, -hz), (hx - 3, top + 4, -hz + 1), cane_material)
    else:
        for x in (-hx, hx - 1):                                    # posts on the side rims, stepping in to the bar
            handle.box((x, top, -1), (x + 1, top + 2, 1), cane_material)
        handle.box((-hx + 1, top + 2, -1), (-hx + 2, top + 3, 1), cane_material)
        handle.box((hx - 2, top + 2, -1), (hx - 1, top + 3, 1), cane_material)
        handle.box((-hx + 2, top + 3, -1), (hx - 2, top + 4, 1), cane_material)
    return b


def yukkuri_carrier():
    return carrier('yukkuri_carrier')


def yukkuri_carrier_fine():
    return carrier('yukkuri_carrier_fine')


def yukkuri_carrier_large():
    return carrier('yukkuri_carrier_large')


STYLES = {'yukkuri_toilet': list(PLASTICS)}


def variants():
    """Non-default toilet styles and every toilet style's dirty state, shipped as runtime-peripheral attachments."""
    out = {}
    for s in list(PLASTICS)[1:]:
        out[f'yukkuri_toilet_{s}'] = (lambda s=s: yukkuri_toilet(s))
        out[f'yukkuri_toilet_{s}_dirty'] = (lambda s=s: yukkuri_toilet_dirty(s))
    return out


PROPS = {'waterer': waterer, 'waterer_water': waterer_water, 'yukkuri_toilet': yukkuri_toilet, 'yukkuri_toilet_dirty': yukkuri_toilet_dirty,
         'yukkuri_carrier': yukkuri_carrier, 'yukkuri_carrier_fine': yukkuri_carrier_fine,
         'yukkuri_carrier_large': yukkuri_carrier_large}
ALL = {**PROPS, **variants()}
