"""The shop toys as round props after their inventory icons: yarn ball, glass marble, pretty stone, Reimu and Marisa dolls.

Round things are modelled round: smooth ellipsoids, rings and strands (shapes.py) whose painted regions are laid out by
where a mark sits on the ball (sphere_paint), at about two to three texels per unit so faces and strands read at play
distance. Colours are the icons' own ramps (item-icons/draw.py). A dropped toy is drawn at its real size standing on its
bottom (client/HouseScrapRenderer) and never turns, so each one rests on a small flat base, is centred on x = z = 0 and
reads from every side; the dolls face -z. Their atlases are square.

Units are 1/16 block, +y up. Nothing here copies Minecraft pixels.
"""
import importlib.util, math, sys
from pathlib import Path
from shapes import Build, ellipsoid, tube, sphere_paint, turn, canvas, put, stamp

DRAW = Path(__file__).resolve().parent.parent / 'item-icons/draw.py'
if 'item_icon_ramps' not in sys.modules:
    _spec = importlib.util.spec_from_file_location('item_icon_ramps', DRAW)
    sys.modules['item_icon_ramps'] = _module = importlib.util.module_from_spec(_spec)
    _spec.loader.exec_module(_module)
draw = sys.modules['item_icon_ramps']
ROSE, MARBLE, AMBER, STONE, PAPER = draw.ROSE, draw.MARBLE, draw.AMBER, draw.STONE, draw.PAPER
SKIN, HAIR, BLOND, INK, BERRY, RED = draw.SKIN, draw.HAIR, draw.BLOND, draw.INK, draw.BERRY, draw.RED
LIGHT = (.45, .7, -.55)          # the icons' light, from the top left of the front (+x is the viewer's left)


def _unit(v):
    n = math.sqrt(sum(c * c for c in v))
    return tuple(c / n for c in v)


def _dot(a, b):
    return sum(x * y for x, y in zip(a, b))


def lit(d):
    """-1 .. 1: how far a direction faces the icons' light."""
    return _dot(d, _unit(LIGHT))


def strip(w, h, painter):
    """A tube region: painter(u, v) -> colour per texel (u round the strand, v along it)."""
    img = canvas(w, h)
    for v in range(h):
        for u in range(w):
            put(img, u, v, painter(u, v), False)
    return img


def ring(centre, radius, axis_turn, offset, lift=0.0, segs=20):
    """Points of a circle on a ball: the latitude `offset` along the axis turned by axis_turn, `lift` above the surface."""
    axis = [axis_turn[i][1] for i in range(3)]
    e1 = [axis_turn[i][0] for i in range(3)]
    e2 = [axis_turn[i][2] for i in range(3)]
    r = math.sqrt(radius ** 2 - offset ** 2) + lift
    c = [centre[i] + axis[i] * offset for i in range(3)]
    return [[c[i] + r * (math.cos(t) * e1[i] + math.sin(t) * e2[i]) for i in range(3)]
            for t in (2 * math.pi * k / segs for k in range(segs))]


# =====================================================================================================================
YARN_R, YARN_Y = 3.4, 3.3
WRAPS = ((turn(20, 0, 52), .7), (turn(-35, 0, -48), .8), (turn(95, 0, 70), 1.4))   # outer turns of yarn: (axis, latitude)


def yarn_ball():
    """The rolling toy: a ball of rose yarn 6.8 across. Its body is wound in rows (dark grooves between lit strands, as the
    icon's dark rows), three outer turns cross over it in relief, and the loose end runs down its lower right and curls on
    the floor inside the ball's own footprint, so the bounds-centred ground drawing keeps the ball on the item."""
    b = Build('yarn_ball', square=True)
    centre = (0.0, YARN_Y, 0.0)
    wind = _unit((.25, 1, .3))

    def wound(d):
        row = (math.asin(max(-1, min(1, _dot(d, wind)))) / math.pi * 9) % 1      # nine rows pole to pole
        tone = 3 if lit(d) > .3 else 2 if lit(d) > -.4 else 1
        if row < .2:
            return ROSE[max(0, tone - 2)]                         # the groove between two strands
        return ROSE[min(4, tone + 1)] if .45 < row < .75 and tone >= 2 else ROSE[tone]
    ellipsoid(b, b.part('wound_ball'), centre, (YARN_R,) * 3, 'ball', sphere_paint(64, 32, wound), segs=20, rows=12,
              floor=-YARN_Y)
    turns = b.part('outer_turns')       # sunk into the ball so each turn stands only a strand's width proud of it
    strand = strip(6, 24, lambda u, v: ROSE[3] if u in (1, 2, 3) else ROSE[2])
    for k, (axis, offset) in enumerate(WRAPS):
        tube(b, turns, [(p, .25) for p in ring(centre, YARN_R, axis, offset, lift=-.08, segs=28)], f'turn{k}', strand, segs=6,
             closed=True)
    end = [((-1.95, 2.0, -2.15), .27), ((-2.4, 1.35, -2.5), .27), ((-2.65, .7, -2.55), .27), ((-2.75, .3, -2.25), .27),
           ((-2.95, .27, -1.45), .27), ((-3.1, .27, -.6), .27), ((-2.85, .27, .15), .27), ((-2.35, .27, .45), .25)]
    tube(b, b.part('loose_thread'), end, 'thread', strip(6, 16, lambda u, v: ROSE[3] if u in (1, 2) else ROSE[2]), segs=6)
    return b.finish()


def glass_marble():
    """The rolling marble: a ball of deep blue glass 4.4 across with the icon's white glint at the top left and its orange
    twist seen inside: an S-shaped streak in the middle of each of the four sides, so it shows from wherever the marble
    has rolled to. Opaque, like the icon's glass at 1x."""
    b = Build('glass_marble', square=True)
    sides = [((0, 0, -1), (-1, 0, 0)), ((-1, 0, 0), (0, 0, 1)), ((0, 0, 1), (1, 0, 0)), ((1, 0, 0), (0, 0, -1))]

    def glass(d):
        g = _dot(d, _unit((.5, .62, -.6)))
        if g > .97:
            return MARBLE[4]                                      # the glint
        if g > .93:
            return MARBLE[3]
        for face, right in sides:                                 # (outward, the viewer's right on that side)
            if _dot(d, face) > .5 and abs(d[1]) < .5:
                off = _dot(d, right) - (.25 * d[1] + .12 * math.sin(d[1] * 6))
                if abs(off) < .11:
                    return AMBER[4] if d[1] > .2 and off < 0 else AMBER[3]
                if abs(off) < .17:
                    return AMBER[2]
        return MARBLE[2] if d[1] > .8 else MARBLE[1] if d[1] > -.55 else MARBLE[0]      # a small lit cap, no lid edge
    ellipsoid(b, b.part('marble'), (0.0, 2.2, 0.0), (2.2, 2.2, 2.2), 'glass', sphere_paint(48, 24, glass), segs=18, rows=10,
              floor=-2.1)
    return b.finish()


# =====================================================================================================================
def pretty_stone():
    """A smooth river pebble 6 x 2.6 x 4.9, flat underneath, in the icon's blue-grey with a pale quartz vein running
    diagonally over the top and down both sides and a glint at the top left."""
    b = Build('pretty_stone', square=True)
    vein = _unit((.7, .12, .7))

    def pebble(d):
        if abs(_dot(d, vein)) < .075:
            return PAPER[3] if d[1] > .3 else PAPER[2]
        if _dot(d, _unit((.5, .75, -.45))) > .95:
            return STONE[4]
        return STONE[3 if d[1] > .7 else 2 if d[1] > -.1 else 1]
    ellipsoid(b, b.part('pebble'), (0.0, 1.0, 0.0), (3.0, 1.6, 2.45), 'stone', sphere_paint(40, 16, pebble), segs=20, rows=8,
              floor=-1.0)
    return b.finish()


# =====================================================================================================================
BUN = (3.6, 3.1, 3.3)                # the plush bun's radii; it stands on a flat bottom 2.6 below its centre
BUN_Y = 2.6
BUN_TOP = BUN_Y + BUN[1]


def face_painter(hair, fringe):
    """The bun's skin and hair (sx to the viewer's right, sy up, both in radii): the hair over the top, the back and down
    the sides of the face, `fringe(sx)` being how far it comes down the forehead; the skin is shaded by height only, so
    the face has no shading edge."""
    def paint(d):
        sx, sy = -d[0], d[1]
        front = d[2] < 0
        shade = 3 if lit(d) > .2 else 2 if lit(d) > -.45 else 1
        strand = (math.atan2(d[0], d[2]) * 18 / math.pi) % 1 < .18
        is_hair = (sy > .45 or (front and sy > fringe(sx)) or (not front or abs(sx) > .8) and sy > -.3
                   or front and abs(sx) > .72 and sy > -.5)
        if is_hair:
            return hair[max(0, min(len(hair) - 1, shade - (1 if strand else 0)))]
        return SKIN[3] if front and sy > -.7 else SKIN[2] if sy > -.9 else SKIN[1]
    return paint


def front_stamp(img, sx, sy, rows, legend):
    """Stamp pixel rows on a sphere_paint region centred on the front point (sx, sy), whole texels so a face stays crisp."""
    w, h = img.size
    phi = math.atan2(-sx, -math.sqrt(max(0.0, 1 - sx * sx - sy * sy))) % (2 * math.pi)
    u = phi / (2 * math.pi) * w - len(rows[0]) / 2
    v = (math.pi / 2 - math.asin(sy)) / math.pi * h - len(rows) / 2
    stamp(img, round(u), round(v), rows, legend, wrap=False)


def _bun(name, hair, fringe):
    """The plush bun with the icon's face: two dark eyes just under the middle, a blush under each and a small smile."""
    b = Build(name, square=True)
    head = b.part('bun')
    skin = sphere_paint(64, 32, face_painter(hair, fringe))
    for sx in (-.34, .34):
        front_stamp(skin, sx, -.15, ['ee', 'ee', 'ee'], {'e': INK[1]})
        front_stamp(skin, sx * 1.6, -.4, ['bb'], {'b': BERRY[3]})
    front_stamp(skin, 0.0, -.56, ['mmm'], {'m': BERRY[1]})
    ellipsoid(b, head, (0.0, BUN_Y, 0.0), BUN, 'bun', skin, segs=24, rows=12, floor=-BUN_Y)
    return b, head


def reimu_doll():
    """Reimu plush: a round bun with dark brown hair and its fringe, the big red bow standing on top in two puffy lobes
    over a white knot, and a red-and-white tube on each side lock, as on the icon."""
    b, head = _bun('reimu_doll', HAIR, lambda sx: .42 - .12 * max(0.0, math.cos(sx * 11)))

    def ribbon(d):
        if abs(d[2]) < .22 and d[1] < .55:
            return RED[1]                                         # the fold between the bow's two faces
        return RED[3] if lit(d) > .3 else RED[2] if lit(d) > -.4 else RED[1]
    bow = b.part('bow')
    for side in (1, -1):
        ellipsoid(b, bow, (side * 1.95, BUN_TOP + .55, .55), (1.75, 1.2, .7), 'lobe', sphere_paint(24, 12, ribbon), segs=14,
                  rows=7, rot=turn(0, 0, side * 22))
    ellipsoid(b, bow, (0.0, BUN_TOP + .35, .5), (.6, .62, .55), 'knot',
              sphere_paint(12, 6, lambda d: PAPER[4] if lit(d) > -.2 else PAPER[3]), segs=10, rows=5)
    cuff = strip(6, 12, lambda u, v: PAPER[4 if u < 3 else 3] if 4 <= v < 8 else RED[2 if u < 3 else 1])
    for side in (1, -1):
        x = side * 3.42
        tube(b, b.part('side_tubes'), [((x, 2.55, -.85), .45), ((x * 1.01, 1.75, -.9), .45), ((x * .99, .95, -.85), .4)],
             'cuff', cuff, segs=8)
    return b.finish()


def marisa_doll():
    """Marisa plush: a round bun with blond hair and a full fringe under the black witch hat: a wide round brim and a
    crown that tapers to a tip bent to the viewer's right, with the white band round its foot, as on the icon."""
    b, head = _bun('marisa_doll', BLOND, lambda sx: .36 - .08 * max(0.0, math.cos(sx * 13)))
    hat = b.part('hat')
    ellipsoid(b, hat, (0.0, 4.55, .15), (4.5, .3, 4.1), 'brim',
              sphere_paint(32, 8, lambda d: INK[0] if d[1] < 0 else INK[2] if lit(d) > .55 else INK[1]), segs=24, rows=4)
    crown = [((0.0, 4.5, .15), 2.75), ((0.0, 5.3, .15), 2.45), ((-.05, 6.1, .2), 2.0), ((-.25, 6.9, .25), 1.45),
             ((-.6, 7.6, .3), .95), ((-1.2, 8.2, .35), .55), ((-1.95, 8.5, .4), .2)]
    # Round the crown u = 0 faces -z and runs toward -x, so columns 10-11 face the light at the front left.
    felt = strip(12, 24, lambda u, v: (PAPER[4] if u in (9, 10, 11, 0) else PAPER[3]) if 2 <= v < 5 else
                 INK[2] if u in (10, 11) else INK[1] if u in (0, 1, 8, 9) else INK[0])
    tube(b, hat, crown, 'crown', felt, segs=16)
    return b.finish()


PROPS = {'yarn_ball': yarn_ball, 'glass_marble': glass_marble, 'pretty_stone': pretty_stone,
         'reimu_doll': reimu_doll, 'marisa_doll': marisa_doll}
