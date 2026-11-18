"""Block-style toys (kit.py), matching their inventory icons.

yarn_ball (the rolling toy): a stepped sphere seven units across (corners cut on all three axes), centred on the origin
like the old ball. Its identity is the icon's wound strands: dark-pink diagonal rows painted across the faces, crossing where
faces meet, on a lit upper and a shaded lower half. A one-cell thread comes out from under the bulge and curls on the floor,
inside the ball's own bounds so the bounds-centred ground drawing keeps the ball on the item's position. Rolling
uses the item entity, not this mesh.
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
    r = 3.5
    centre = (.5, r, .5)                                  # cell-centre coordinates; the offset puts it on x = z = 0
    b = kit.Block('yarn_ball', offset=(-.5, 0, -.5))

    def wound(cell, face):
        x, y, z = cell
        # One-texel diagonal strands every third texel; each face direction winds its own diagonal, so where faces
        # meet the strands cross, as the icon's dark rows do over the lit ball.
        strand = {'east': z + y, 'west': z - y, 'north': x - y, 'south': x + y, 'up': x + z, 'down': x - z}[face] % 3 == 0
        lower = y + .5 < centre[1] and face != 'up'
        return -2 if strand else -1 if lower else 0
    rose = kit.Material('ROSE', step=3, top=1, bottom=-1, bevel=False, pattern=wound)
    b.part('wound_ball').ball(r, centre, rose)
    thread = kit.Material('ROSE', step=2, top=1, bottom=-1, bevel=False)
    loose = b.part('loose_thread')
    for cell in ((-2, 0, -2), (-3, 0, -2), (-3, 0, -3)):   # from under the bulge, lower right seen from the front
        loose.cell(cell, thread)
    return b


PROPS = {'yarn_ball': yarn_ball}
