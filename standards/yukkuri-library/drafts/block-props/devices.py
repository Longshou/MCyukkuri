"""Block-style placed care devices (kit.py). Footprints and heights keep the CareBlock collision and the fill meshes.

food_bowl (default style): a stepped round ceramic bowl after its icon: white glaze with the red line at the rim; an
octagon 14 across on a foot inset by one cell all round, 3 high (the old bounds, so the CareBlock collision and the mouth
contact at its top still meet the rim). The floor top is at y 1 and the well is the 12-wide inset, so the existing heap
fills (radius 6, base y 1.03) sit inside it and heap above the rim as on the icon. A rim wider than the body is not
possible inside these bounds without narrowing the well below the fills.
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
        # Warm white glaze (the top steps of CREAM) with the icon's pure white on the lit rim: under in-game side
        # shading the WHITE ramp's lower steps turn cold grey.
        y = cell[1]
        if face == 'up':
            return ('WHITE', 4) if y == top else ('CREAM', 4)      # lit rim; floor of the well
        if face == 'down':
            return ('CREAM', 2)
        if kit.inward(cell, face):
            return ('CREAM', 3)                                    # inner wall
        if y == top:
            return ('RED', 2)                                      # the icon's red line at the rim
        return ('CREAM', 3) if y == 0 else ('CREAM', 4)            # foot one step below the body
    ceramic = kit.Material('WHITE', top=0, bottom=0, pattern=glaze)
    bowl = b.part('bowl')
    # Plan: an octagon (two one-cell steps per corner) and its one-cell inset. The inset is the floor and foot and the
    # well, so the wall is one cell thick all round, the rim top is an even band and the foot steps in evenly.
    body = kit.octagon(7, 2)
    well = kit.inset(body)
    bowl.layer(well, 0, 1, ceramic)                                # foot and floor
    bowl.layer(body - well, 1, top + 1, ceramic)                   # wall and rim
    return b


PROPS = {'food_bowl': food_bowl}
