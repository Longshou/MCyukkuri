"""Block-style placed care devices (kit.py). Footprints and heights keep the CareBlock collision and the
contact heights the game uses (toilet sand 2); the dye styles keep their mesh names and life/CareStyles.java's dye
table. The food bowl and its fills and the waterer are round (care-props/bowl.py, care-props/waterer.py).

yukkuri_toilet and three dye styles, each with a dirty state: a rectangular litter tray 14 x 12, 3 high, clearly a tray
beside the round bowl: a one-cell plastic rim with clipped corners round sand at y 2 in pixel grain. The dirty tray has
damp clumps in the sand and two droppings.
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


STYLES = {'yukkuri_toilet': list(PLASTICS)}


def variants():
    """Non-default toilet styles and every toilet style's dirty state, shipped as runtime-peripheral attachments."""
    out = {}
    for s in list(PLASTICS)[1:]:
        out[f'yukkuri_toilet_{s}'] = (lambda s=s: yukkuri_toilet(s))
        out[f'yukkuri_toilet_{s}_dirty'] = (lambda s=s: yukkuri_toilet_dirty(s))
    return out


PROPS = {'yukkuri_toilet': yukkuri_toilet, 'yukkuri_toilet_dirty': yukkuri_toilet_dirty}
ALL = {**PROPS, **variants()}
