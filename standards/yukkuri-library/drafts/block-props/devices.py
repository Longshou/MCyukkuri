"""Block-style placed care devices (kit.py). Footprints and heights keep the CareBlock collision and the fill meshes.

food_bowl (default style): a stepped round ceramic bowl after its icon: white glaze, a red line under the rim, a mint
glazed floor; 14 across on a 12-wide foot and 3 high (the old bounds, so the CareBlock collision and the mouth contact
at its top still meet the rim), floor top at y 1 so the existing heap fills (radius 6, base y 1.03) sit inside the
12-wide well and heap above the rim as on the icon.
"""
import importlib.util, sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
if 'block_props_kit' not in sys.modules:   # loaded under its own name: care-props also has a `kit`
    _spec = importlib.util.spec_from_file_location('block_props_kit', HERE / 'kit.py')
    sys.modules['block_props_kit'] = _module = importlib.util.module_from_spec(_spec)
    _spec.loader.exec_module(_module)
kit = sys.modules['block_props_kit']


def food_bowl():
    b = kit.Block('food_bowl')
    top = 2                                       # the rim row: 3 high like the old bowl and its stand contact

    def glaze(cell, face):
        y = cell[1]
        if face == 'up':
            return ('WHITE', 4) if y == top else ('WHITE', 3)      # lit rim; glazed floor
        if face == 'down':
            return ('WHITE', 1)
        if kit.inward(cell, face):
            return ('WHITE', 2)                                    # inner wall, in its own shade
        if y == top:
            return ('RED', 2)                                      # the icon's red line under the rim
        return ('WHITE', 1) if y == 0 else ('WHITE', 3)            # darker foot under the lit body
    ceramic = kit.Material('WHITE', top=0, bottom=0, pattern=glaze)
    bowl = b.part('bowl')
    # An octagon of stacked cuboids (two steps per corner) reads round at 1x without a sawtooth rim; inside it is the
    # 12-wide well the heap fills need, on a foot one unit narrower than the body.
    bowl.rounded(6, 2, 0, 1, ceramic)                              # foot and floor
    bowl.rounded(7, 2, 1, top + 1, ceramic).rounded(6, 2, 1, top + 1, None)
    return b


PROPS = {'food_bowl': food_bowl}
