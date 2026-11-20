"""Block-style forage store of wild yukkuri (kit.py), matching its inventory icon. Units are 1/16 block, +Y up.

The nests themselves are rounded low-poly props in care-props (placed.py: the pet beds and the 2x2 woven nest; wild.py:
the 2x2 wild bedding and the predator twig nest).
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
# Forage store (life/ForagePileBlock; ForagePileRenderer draws units 0..stage): unit 0 is a bed of three big leaves,
# always drawn; units 1-4 are a stepped heap of gathered grass that grows per stage (each contains the last), with red
# berries on its left flank and brown nuts on its top; the full heap carries one white daisy.
LEAVES = (((-2, 1), 4.2, 2.6, 'LEAF'), ((2, 2), 3.8, 2.4, 'LEAF'), ((0, -2), 4.4, 2.8, 'STRAW'))
MOUND = ((2.6, 1.6), (3.3, 2.4), (3.9, 3.1), (4.4, 3.9))      # radius, height of the heap per stage
MOUND_Z = -1


def forage_pile():
    b = kit.Block('forage_pile')
    plate = b.part('leaf_plate', 0)
    for (cx, cz), a, c, ramp in LEAVES:
        def leaf(cell, face, cx=cx, cz=cz, ramp=ramp):
            x, y, z = cell
            if face != 'up':
                return (ramp, 1)
            return (ramp, 1) if x == cx or (x - cx) == (z - cz) else (ramp, 3 if mix(x, z) < 6 else 2)   # midrib and vein
        material = kit.Material(ramp, pattern=leaf, bevel=False)
        for x in range(-7, 7):
            for z in range(-7, 7):
                if ((x + .5 - cx) / a) ** 2 + ((z + .5 - cz) / c) ** 2 <= 1 and (x, 0, z) not in plate.cells:
                    plate.cell((x, 0, z), material)

    def stock(cell, face):
        x, y, z = cell
        h = mix(x, y, z)
        if face == 'down':
            return ('LEAF', 0)
        if x <= -1 and h < 4:
            return ('RED', 3 if face == 'up' else 2)               # berries on the left flank
        if x >= 0 and face == 'up' and h > 11:
            return ('WOOD', 3 if h > 13 else 2)                    # nuts on the right of the top
        if h < 3:
            return ('STRAW', 3)                                    # dry stalks in the green
        return ('LEAF', (3 if face == 'up' else 2) + (1 if h > 13 else 0))
    grass = kit.Material('LEAF', pattern=stock, bevel=False)
    previous = set()
    for stage, (R, H) in enumerate(MOUND, 1):
        cells = {(x, y, z) for x in range(-6, 6) for z in range(-7, 5) for y in range(1, 6)
                 if ((x + .5) / R) ** 2 + ((z + .5 - MOUND_Z) / (R * .9)) ** 2 + ((y + .5 - 1) / H) ** 2 <= 1}
        part = b.part(f'stock_{stage}', stage)
        for cell in sorted(cells - previous):
            part.cell(cell, grass)
        previous |= cells
    top = max(y for x, y, z in previous if (x, z) == (0, MOUND_Z))
    daisy = b.part('daisy', 4)
    daisy.cell((0, top + 1, MOUND_Z), kit.Material('LEAF', step=2, bevel=False))
    flower = kit.Material('FLOWER', step=2, bevel=False,
                          pattern=lambda c, f: ('FLOWER', 1) if (c[0], c[2]) == (0, MOUND_Z) else 0)
    for dx, dz in ((0, 0), (1, 0), (-1, 0), (0, 1), (0, -1)):
        daisy.cell((dx, top + 2, MOUND_Z + dz), flower)
    return b
