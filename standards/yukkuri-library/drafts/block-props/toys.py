"""Block-style toys (kit.py), matching their inventory icons: yarn ball, glass marble, pretty stone and the two dolls
(each documented at its function). A dropped toy is drawn at its real size standing on its bottom and never turns, so
every side must read on its own.

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


def _painted(front, north_x):
    """Reads a row-string picture as it is seen from the front: `north_x` is the cell x of the leftmost column."""
    return lambda x, y: front[len(front) - 1 - y][north_x - x] if 0 <= y < len(front) and 0 <= north_x - x < len(front[0]) else '.'


def glass_marble():
    """The rolling marble: a stepped sphere five units across (3x3 caps on every side, every edge cut), in the icon's
    deep glass blue with its white glint at the top left and the orange twist drawn on every side face, so the
    twist shows from wherever it has rolled to. Opaque: the old translucent shell hid everything but the twist."""
    b = kit.Block('glass_marble', offset=(-.5, 0, -.5))

    def glass(cell, face):
        x, y, z = cell
        if face == 'up':
            return ('MARBLE', 4) if (x, z) == (1, -1) else ('MARBLE', 2 if y == 4 else 1)   # the glint, top left from the front
        if face == 'down':
            return ('MARBLE', 0)
        along = {'north': -x, 'south': x, 'east': -z, 'west': z}[face]            # left to right seen from outside, -2..2
        if (along, y) in ((1, 3), (0, 3), (0, 2), (-1, 2)):                # one stepped band
            return ('AMBER', 3 if y == 3 else 2)                                  # the twist, a diagonal
        if y == 4 or (along, y) == (-2, 2):
            return ('MARBLE', 2)                                                  # lit cap and the upper left
        return ('MARBLE', 0 if y == 0 else 1)
    b.part('marble').ball(2.5, (.5, 2.5, .5), kit.Material('MARBLE', top=0, bottom=0, bevel=False, pattern=glass))
    return b


def pretty_stone():
    """A flat river pebble, 6 x 3 x 5: an oval layer on a stepped-in foot under a smaller crown, in the icon's blue-grey
    with its pale quartz vein running diagonally over the top and down both sides, and the glint at the top left."""
    b = kit.Block('pretty_stone', offset=(0, 0, -.5))

    def grain(cell, face):
        x, y, z = cell
        vein = x + z in (0, 1) if face in ('up', 'down') else (x == -3 + y if face == 'south' else x == 1 - y if face == 'north' else False)
        if vein:
            return ('PAPER', 3 if face == 'up' else 2)
        if face == 'up':
            return ('STONE', 4) if (x, z) == (1, -1) else ('STONE', 3 if x + z > 0 or y == 2 else 2)   # glint; lit left half
        if face == 'down':
            return ('STONE', 0)
        return ('STONE', 1 if y == 0 else 2)
    stone = kit.Material('STONE', top=0, bottom=0, bevel=False, pattern=grain)
    oval = {(x, z) for x in range(-3, 3) for z in range(-2, 3)} - {(-3, -2), (2, -2), (-3, 2), (2, 2)}
    pebble = b.part('pebble')
    pebble.layer(kit.inset(oval), 0, 1, stone)                      # the foot steps in all round: a pebble, not a slab
    pebble.layer(oval, 1, 2, stone)
    pebble.layer({(x, z) for x in range(-2, 2) for z in range(-1, 2)}, 2, 3, stone)
    return b


FACE = ("hhhhhh",     # the icon's face at 6 x 5: hair fringe, two dot eyes, blush and the small smile
        "hssssh",
        "sesses",
        "bsmmsb",
        "ssssss")


def _doll(name, hair, front):
    """A plush yukkuri: a stepped round bun 6 x 5 x 5 (every edge cut one cell), the face on its front (-Z), the hair
    over the top, the back and the upper sides. Returns the block and its part for the headwear."""
    b = kit.Block(name, offset=(0, 0, -.5))
    picture = _painted(front, 2)

    def plush(cell, face):
        x, y, z = cell
        if face == 'up':
            return (hair, 3)
        if face == 'down':
            return ('SKIN', 1)
        if face == 'north':
            return {'h': (hair, 3 if y == 4 else 2), 's': ('SKIN', 3 if y else 2), 'e': ('INK', 1),
                    'b': ('BERRY', 4), 'm': ('BERRY', 3)}[picture(x, y)]
        if face == 'south':
            return (hair, 2 if y > 1 else 1) if y else ('SKIN', 1)
        if y >= 3 or (z >= 0 and y >= 1):
            return (hair, 2 if y >= 3 else 1)                       # sides: hair over the top and the back half
        return ('SKIN', 2 if y else 1)
    body = b.part('bun')
    skin = kit.Material('SKIN', top=0, bottom=0, bevel=False, pattern=plush)
    body.box((-3, 0, -2), (3, 5, 3), skin)
    for x in (-3, 2):                                               # cut every edge one cell: a stepped round bun
        for z in (-2, 2):
            body.carve((x, 0, z), (x + 1, 5, z + 1))
    for y in (0, 4):
        for x in range(-3, 3):
            for z in range(-2, 3):
                if x in (-3, 2) or z in (-2, 2):
                    body.carve((x, y, z), (x + 1, y + 1, z + 1))
    for x in (-3, 2):                                               # the lower corners one cell more: a round bottom
        for z in (-1, 1):
            body.carve((x, 1, z), (x + 1, 2, z + 1))
    return b, body


def reimu_doll():
    """Reimu plush: dark brown hair and the big red bow standing on top, two lobes over a white knot."""
    b, body = _doll('reimu_doll', 'HAIR', FACE)

    def ribbon(cell, face):
        x, y, z = cell
        if -1 <= x < 1:
            return ('PAPER', 4 if face in ('up', 'north') else 3)   # the knot
        if face == 'up':
            return ('RED', 3)
        outer = x in (-3, 2)
        return ('RED', 1 if face == 'down' or (y == 5 and outer and face in ('north', 'south')) else 2)
    red = kit.Material('RED', top=0, bottom=0, bevel=False, pattern=ribbon)
    for x0 in (-3, 1):
        body.box((x0, 5, 0), (x0 + 2, 7, 2), red)                   # lobes
    body.box((-1, 5, 0), (1, 6, 2), red)                            # knot, lower than the lobes
    return b


def marisa_doll():
    """Marisa plush: blond hair under the black witch hat: a brim wider than the bun, the white band, a stepped cone
    with the tip bent to the right as on the icon."""
    b, body = _doll('marisa_doll', 'BLOND', FACE[:1] + ('hhsshh',) + FACE[2:])   # a full fringe under the brim

    def felt(cell, face):
        x, y, z = cell
        if y == 5 and face not in ('up', 'down'):
            return ('PAPER', 4 if face in ('north', 'west') else 3)  # the band
        if face == 'up':
            return ('INK', 2)
        return ('INK', 1 if face in ('north', 'east') else 0)                   # the icon's near-black purple
    ink = kit.Material('INK', top=0, bottom=0, bevel=False, pattern=felt)
    brim = {(x, z) for x in range(-4, 4) for z in range(-2, 3)} - {(-4, -2), (3, -2), (-4, 2), (3, 2)}
    body.layer(brim, 4, 5, ink)
    body.box((-2, 5, -1), (2, 6, 2), ink)                           # crown with the band
    body.box((-2, 6, -1), (1, 7, 2), ink)                           # the cone leans to the right (-X) as it rises
    body.box((-2, 7, 0), (0, 8, 1), ink)
    return b


PROPS = {'yarn_ball': yarn_ball, 'glass_marble': glass_marble, 'pretty_stone': pretty_stone,
         'reimu_doll': reimu_doll, 'marisa_doll': marisa_doll}
