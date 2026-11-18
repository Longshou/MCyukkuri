"""Block-style toys (kit.py), matching their inventory icons.

yarn_ball (the rolling toy): a stepped rose ball five units across, centred on the origin like the old sphere, wound with
crossing darker strands and a loose end trailing onto the floor toward +x.
"""
import importlib.util, sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
if 'block_props_kit' not in sys.modules:   # loaded under its own name: care-props also has a `kit`
    _spec = importlib.util.spec_from_file_location('block_props_kit', HERE / 'kit.py')
    sys.modules['block_props_kit'] = _module = importlib.util.module_from_spec(_spec)
    _spec.loader.exec_module(_module)
kit = sys.modules['block_props_kit']


def yarn_ball():
    b = kit.Block('yarn_ball', offset=(-.5, 0, -.5))    # odd widths: cell centres on whole units

    def wound(cell, face):
        x, y, z = cell
        # Each face direction winds its own diagonal, so strands cross where the faces meet, as on the icon.
        line = {'east': z + y, 'west': z - y, 'north': x - y, 'south': x + y, 'up': x + z, 'down': x - z}[face] % 3 == 0
        return -1 if line else 0                    # a strand one step darker than the wound surface
    rose = kit.Material('ROSE', step=3, top=1, bottom=-1, bevel=False, pattern=wound)
    ball = b.part('wound_ball')
    for x in range(-2, 3):
        for y in range(5):
            for z in range(-2, 3):
                if x * x + (y - 2) ** 2 + z * z <= 6.25:
                    ball.cell((x, y, z), rose)
    tail = kit.Material('ROSE', step=3, top=1, bottom=-1, bevel=False)
    loose = b.part('loose_tail')
    for cell in ((2, 0, 1), (3, 0, 1), (4, 0, 2)):
        loose.cell(cell, tail)
    return b


PROPS = {'yarn_ball': yarn_ball}
