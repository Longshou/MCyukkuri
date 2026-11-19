"""Block-style placed care devices and carriers (kit.py). Footprints and heights keep the CareBlock collision and the
contact heights the game uses (bowl rim 3, heap base 1; toilet sand 2); the dye styles keep their mesh names and
life/CareStyles.java's dye table.

food_bowl and its six dye styles: a stepped round ceramic bowl after its icon, an octagon 14 across on a foot inset by
one cell all round, 3 high. The floor top is at y 1 and the well is the 12-wide inset, so the fills heap inside it.
A style only repaints the glaze: rim band, one belly row, inside, foot and a small print on the floor (seen from above,
where the bowl is read). Default: warm white glaze with the icon's red line at the rim.

Fills (food_bowl_regular / _wheat / _forage): stepped heaps on the well floor, one part per portion (units 1-8). Heap k is
a stepped dome that contains heap k-1 and is wider and higher, so CareRenderer's drawing of units 1..k shows heap k; the
last heaps rise above the rim as on the icon. Kibble is a field of lit and shaded grains, wheat golden stalks with ears,
the grass fill cut blades.

waterer: the bowl's sibling for water, radially symmetric (no facing). A saucer on the bowl's plan, only 2 high so the
`drink` clip's low mouth meets the rim (the bowl is 3), with the bowl's rim line in blue; on a cork collar in the middle
stands an inverted glass jar, 6 across and 9 high, its top at y 12. The glass is cut-out like vanilla glass: lit frame rows,
solid stepped corners and clear panes with a glint, nothing drawn on its inside. waterer_water is the water alone, drawn
inside the waterer like a bowl fill: one 4 x 4 row of the jar per portion (tank_u1 at the bottom to tank_u8, units 1-8)
and the saucer's water ring (dish_water, unit 1), so drawing units 1..k shows a jar k rows full over a wet saucer and
drawing none shows it dry.

yukkuri_toilet and three dye styles, each with a dirty state: a rectangular litter tray 14 x 12, 3 high, clearly a tray
beside the round bowl: a one-cell plastic rim with clipped corners round sand at y 2 in pixel grain. The dirty tray has
damp clumps in the sand and two droppings.

Carriers: an open woven basket with an arched handle across, in the three icon tints (natural STRAW, blue NAVYW, green
SAGEW) with the pale lining folded over below the rim; the large carrier (12 places) is two units wider and deeper and
one taller than the other two (4 and 6 places).
"""
import importlib.util, sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
if 'block_props_kit' not in sys.modules:   # loaded under its own name: care-props also has a `kit`
    _spec = importlib.util.spec_from_file_location('block_props_kit', HERE / 'kit.py')
    sys.modules['block_props_kit'] = _module = importlib.util.module_from_spec(_spec)
    _spec.loader.exec_module(_module)
kit = sys.modules['block_props_kit']


def stamp(rows, x0=0, z0=0):
    """Cells of a text motif centred on (x0, z0), seen from above with -z at the top: {(x, z): char}."""
    h, w = len(rows), len(rows[0])
    return {(x0 + i - w // 2, z0 + j - h // 2): ch for j, row in enumerate(rows) for i, ch in enumerate(row) if ch != '.'}


def mix(*values):
    """A fixed per-cell hash in 0..15, so grain and strand patterns stay put between exports."""
    h = 0
    for v in values:
        h = (h * 31 + v * 17 + 7) & 0xffff
    return (h ^ (h >> 5)) & 15


# =====================================================================================================================
# Food bowl. Rows: 0 foot (the inset), 1 belly, 2 rim band; up faces: rim top at y 3 and the floor at y 1.
BOWL_TOP = 2
BOWL = kit.octagon(7, 2)
WELL = kit.inset(BOWL)
HEART = ['.x.x.', 'xxxxx', 'xxxxx', '.xxx.', '..x..']
ANCHOR = ['..x..', '.xxx.', '..x..', 'x.x.x', '.xxx.']
PAW = ['x.x', '.x.', 'xxx']
#             belly             rim band        rim top         inside          foot           floor print
GLAZES = {
    'hearts':     (('WHITE', 4), ('RED', 2), ('RED', 3), ('WHITE', 4), ('WHITE', 3), (HEART, ('RED', 2))),
    'polka':      (('ROSE', 3), ('WHITE', 4), ('WHITE', 4), ('WHITE', 4), ('ROSE', 2), ('dots', ('ROSE', 3))),
    'sailor':     (('SKY', 3), ('INK', 2), ('WHITE', 4), ('WHITE', 4), ('INK', 2), (ANCHOR, ('INK', 2))),
    'paws':       (('BLOND', 3), ('ROPE', 2), ('BLOND', 4), ('CREAM', 4), ('ROPE', 1), (PAW, ('ROPE', 1))),
    'terracotta': (('TERRA', 2), ('CREAM', 3), ('CREAM', 4), ('CREAM', 3), ('TERRA', 1), ('flower', ('TERRA', 3))),
    'steel':      (('IRON', 3), ('IRON', 3), ('IRON', 4), ('IRON', 3), ('IRON', 2), ('brushed', ('IRON', 4))),
}


def default_glaze(cell, face):
    # Warm white glaze (the top steps of CREAM) with the icon's pure white on the lit rim: under in-game side shading
    # the WHITE ramp's lower steps turn cold grey.
    y = cell[1]
    if face == 'up':
        return ('WHITE', 4) if y == BOWL_TOP else ('CREAM', 4)     # lit rim; floor of the well
    if face == 'down':
        return ('CREAM', 2)
    if kit.inward(cell, face):
        return ('CREAM', 3)                                        # inner wall
    if y == BOWL_TOP:
        return ('RED', 2)                                          # the icon's red line at the rim
    return ('CREAM', 3) if y == 0 else ('CREAM', 4)                # foot one step below the body


def style_glaze(style):
    belly, band, rim, inside, foot, (print_, ink) = GLAZES[style]
    marks = stamp(print_) if isinstance(print_, list) else {}

    def glaze(cell, face):
        x, y, z = cell
        if face == 'up' and y == BOWL_TOP:
            return rim
        if face == 'up':                                           # the well floor, seen from above
            if (x, z) in marks:
                return ink
            if print_ == 'dots' and (x * 2 + z * 3) % 7 == 0 and (x + z) % 2 == 0:
                return ink
            if print_ == 'flower' and (x, z) in stamp(['.p.', 'pcp', '.p.']):
                return ('BLOND', 3) if (x, z) == (0, 0) else ink
            if print_ == 'brushed' and z % 3 == 0 and (x + z) % 4:
                return ink
            return (inside[0], inside[1])
        if face == 'down':
            return (foot[0], max(0, foot[1] - 1))
        if kit.inward(cell, face):
            return (inside[0], max(0, inside[1] - 1))
        u = x + z
        if y == BOWL_TOP:
            if style == 'terracotta' and u % 3 == 0:
                return ('CREAM', 4)                                # glaze running down from the lip ...
            return band
        if y == 1:
            if style == 'polka' and u % 3 == 0:
                return ('WHITE', 4)
            if style == 'hearts' and u % 4 == 1:
                return ('RED', 2)
            if style == 'terracotta' and u % 6 == 0:
                return ('CREAM', 3)                                # ... over bare clay
            if style == 'steel' and mix(x, z) < 4:
                return ('IRON', 4)
            return belly
        return foot
    return glaze


def food_bowl(style=''):
    b = kit.Block(f'food_bowl_{style}' if style else 'food_bowl')
    ceramic = kit.Material('WHITE', top=0, bottom=0, pattern=style_glaze(style) if style else default_glaze)
    bowl = b.part('bowl')
    # Plan: an octagon (two one-cell steps per corner) and its one-cell inset. The inset is the floor and foot and the
    # well, so the wall is one cell thick all round, the rim top is an even band and the foot steps in evenly.
    bowl.layer(WELL, 0, 1, ceramic)                                # foot and floor
    bowl.layer(BOWL - WELL, 1, BOWL_TOP + 1, ceramic)              # wall and rim
    return b


# ---- fills: one stepped heap per portion -----------------------------------------------------------------------------
HEAP_R = [2.4, 3.0, 3.6, 4.2, 4.8, 5.4, 6.0, 6.7]       # plan radius of heap k (clipped to the well)
HEAP_H = [1.0, 1.3, 1.6, 1.9, 2.2, 2.6, 3.0, 3.5]       # height above the floor (y 1) at the middle


def heap(k):
    R, H = HEAP_R[k - 1], HEAP_H[k - 1]
    return {(x, y, z) for x, z in WELL for y in range(1, 6)
            if ((x + .5) ** 2 + (z + .5) ** 2) / R ** 2 + (y + .5 - 1) ** 2 / H ** 2 <= 1}


def fill(name, material):
    b = kit.Block(name)
    previous = set()
    for k in range(1, 9):
        cells = heap(k)
        part = b.part(f'heap_u{k}', k)
        for cell in sorted(cells - previous):
            part.cell(cell, material)
        previous |= cells
    return b


def kibble(cell, face):
    # Separate grains: a grain's lit top-left texel, a shaded corner and dark gaps, scattered by a fixed hash.
    x, y, z = cell
    h = mix(x, y, z, 'nesw ud'.find(face[0]))
    return 2 if h < 4 else 1 if h < 8 else -1 if h > 13 else 0


def wheat(cell, face):
    # Golden stalks lying along x with plump ears every few texels.
    x, y, z = cell
    if face == 'up':
        return 2 if (x + 2 * z) % 5 == 0 else -1 if z % 2 else 0
    return 1 if (x + y) % 4 == 0 else -1 if (x + z) % 3 == 0 else 0


def blades(cell, face):
    # Cut grass: long blades running diagonally, light toward the tips, dark at the cut roots.
    x, y, z = cell
    d = (x - z + 3 * y) % 5
    return 1 if d == 0 else -1 if d == 3 else 0


def food_bowl_regular():
    return fill('food_bowl_regular', kit.Material('PELLET', step=1, top=0, bottom=-1, bevel=False, pattern=kibble))


def food_bowl_wheat():
    return fill('food_bowl_wheat', kit.Material('STRAW', step=3, top=1, bottom=-2, bevel=False, pattern=wheat))


def food_bowl_forage():
    return fill('food_bowl_forage', kit.Material('LEAF', step=2, top=1, bottom=-1, bevel=False, pattern=blades))


# =====================================================================================================================
# Waterer. Saucer rows: 0 foot (the bowl's inset), 1 wall; up faces: rim top at y 2 and the floor at y 1. The cork collar
# stands on the floor to y 3; the glass jar is rows 3-10 with a stepped roof at 11.
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
    dish.layer(BOWL - WELL, 1, 2, ceramic)                         # wall and rim
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
CARRIERS = {'yukkuri_carrier': (6, 5, 7, 'STRAW', 'CREAM'), 'yukkuri_carrier_fine': (6, 5, 7, 'NAVYW', 'PAPER'),
            'yukkuri_carrier_large': (7, 6, 8, 'SAGEW', 'PAPER')}


def carrier(name):
    hx, hz, top, wicker, lining = CARRIERS[name]
    b = kit.Block(name)

    def weave(cell, face):
        x, y, z = cell
        if face == 'down':
            return (wicker, 0)
        if face == 'up':
            return (lining, 3) if y < top - 1 else (wicker, 4)    # floor inside; lit rim
        if kit.inward(cell, face):
            return (lining, 2 if y < top - 2 else 3)               # the lining inside
        if y == top - 1:
            return (wicker, 4 if (x + z) % 2 else 3)              # braided rim
        if y == top - 2:
            return (lining, 3)                                     # the lining folded over below the rim
        k = (x + z + 2 * y) % 4                                    # over-under weave, rows offset by two
        return (wicker, 3 if k == 0 else 1 if k == 3 else 2)
    basket = kit.Material(wicker, pattern=weave, bevel=False)
    plan = kit.octagon(hx, 2, half_z=hz)
    foot = kit.inset(plan)
    body = b.part('basket')
    body.layer(foot, 0, 1, basket)                                 # base, one cell in from the wall: a tapered foot
    body.layer(foot - kit.inset(foot), 1, 2, basket)
    body.layer(plan - foot, 2, top, basket)

    def cane(cell, face):
        x, y, z = cell
        return (wicker, 4 if face == 'up' else 3 if (x + y) % 2 else 2)
    handle = b.part('handle')
    cane_material = kit.Material(wicker, pattern=cane, bevel=False)
    for x in (-hx, hx - 1):                                        # posts on the side rims, stepping in to the bar
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


STYLES = {'food_bowl': ['', *GLAZES], 'yukkuri_toilet': list(PLASTICS)}


def variants():
    """Non-default dye styles (and every toilet style's dirty state), shipped as runtime-peripheral attachments."""
    out = {f'food_bowl_{s}': (lambda s=s: food_bowl(s)) for s in GLAZES}
    for s in list(PLASTICS)[1:]:
        out[f'yukkuri_toilet_{s}'] = (lambda s=s: yukkuri_toilet(s))
        out[f'yukkuri_toilet_{s}_dirty'] = (lambda s=s: yukkuri_toilet_dirty(s))
    return out


PROPS = {'food_bowl': food_bowl, 'food_bowl_regular': food_bowl_regular, 'food_bowl_wheat': food_bowl_wheat,
         'food_bowl_forage': food_bowl_forage, 'waterer': waterer, 'waterer_water': waterer_water, 'yukkuri_toilet': yukkuri_toilet, 'yukkuri_toilet_dirty': yukkuri_toilet_dirty,
         'yukkuri_carrier': yukkuri_carrier, 'yukkuri_carrier_fine': yukkuri_carrier_fine,
         'yukkuri_carrier_large': yukkuri_carrier_large}
ALL = {**PROPS, **variants()}
