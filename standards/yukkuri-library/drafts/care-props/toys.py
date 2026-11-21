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
from shapes import Build, ellipsoid, tube, sphere_paint, turn, canvas, put, stamp, rgb

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
YARN_Y = 3.2
YARN_BANDS = (
    # (axis_turn, latitude_offset, half_width, r_out, (strand_a, strand_b, edge))
    (turn(12, 0, 28),    0.0,  1.28, 3.18, (2, 1, 1)),
    (turn(100, 0, -34),  0.0,  1.22, 3.28, (2, 3, 1)),
    (turn(52, 0, 70),    0.30, 1.15, 3.38, (3, 2, 1)),
    (turn(-36, 0, -64), -0.25, 1.05, 3.50, (4, 3, 2)),
)


def _vadd(a, b): return [a[i] + b[i] for i in range(3)]
def _vsub(a, b): return [a[i] - b[i] for i in range(3)]
def _vcross(a, b): return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]


def _flat_quad(b, part, pts, uvs, outward_hint):
    """Two flat-shaded triangles for a quad, with face normal oriented along outward_hint."""
    e1, e2 = _vsub(pts[1], pts[0]), _vsub(pts[2], pts[0])
    n = _vcross(e1, e2)
    nl = math.sqrt(sum(c * c for c in n))
    if nl < 1e-8:
        return
    n = [c / nl for c in n]
    if _dot(n, outward_hint) < 0:
        n = [-c for c in n]
    for i0, i1, i2 in ((0, 1, 2), (0, 2, 3)):
        b.p.tri(part, [pts[i0], pts[i1], pts[i2]], [n] * 3, None, uvs=[uvs[i0], uvs[i1], uvs[i2]])


def _wrap_band(b, part, centre, r_in, r_out, half_w, axis_turn, offset, key, img, segs=12, floor_y=0.0):
    """A flat-shaded polygonal belt hugging a sphere, with outer face and tucked side walls."""
    axis = [axis_turn[i][1] for i in range(3)]
    e1 = [axis_turn[i][0] for i in range(3)]
    e2 = [axis_turn[i][2] for i in range(3)]
    ox, oy = b.region(key, img, wrap=True)
    W, H = img.size

    def pt(r, s, t):
        rad = [math.cos(t) * e1[i] + math.sin(t) * e2[i] for i in range(3)]
        rr = math.sqrt(max(0.05, r * r - s * s))
        p = [centre[i] + axis[i] * s + rad[i] * rr for i in range(3)]
        if p[1] < floor_y:
            p[1] = floor_y
        return p, rad

    s0, s1 = offset - half_w, offset + half_w
    for k in range(segs):
        t0, t1 = 2 * math.pi * k / segs, 2 * math.pi * (k + 1) / segs
        u0, u1 = ox + W * k / segs, ox + W * (k + 1) / segs
        o00, rad0 = pt(r_out, s0, t0)
        o10, rad1 = pt(r_out, s0, t1)
        o11, _ = pt(r_out, s1, t1)
        o01, _ = pt(r_out, s1, t0)
        i00, _ = pt(r_in, s0, t0)
        i10, _ = pt(r_in, s0, t1)
        i11, _ = pt(r_in, s1, t1)
        i01, _ = pt(r_in, s1, t0)
        rmid = _unit([rad0[c] + rad1[c] for c in range(3)])
        _flat_quad(b, part, [o00, o10, o11, o01], [(u0, oy), (u1, oy), (u1, oy + H), (u0, oy + H)], rmid)
        _flat_quad(b, part, [i00, i10, o10, o00], [(u0, oy), (u1, oy), (u1, oy + 1), (u0, oy + 1)], [-c for c in axis])
        _flat_quad(b, part, [o01, o11, i11, i01], [(u0, oy + H - 1), (u1, oy + H - 1), (u1, oy + H), (u0, oy + H)], axis)


def _square_strand(b, part, path, half_w, key, img):
    """A chunky flat-shaded 4-sided yarn tail along a polyline, resting cleanly on the ground."""
    ox, oy = b.region(key, img, wrap=False)
    W, H = img.size
    n = len(path)
    rings = []
    for k, p in enumerate(path):
        t = _unit(_vsub(path[min(k + 1, n - 1)], path[max(k - 1, 0)]))
        up = (0.0, 1.0, 0.0) if abs(t[1]) < 0.85 else (1.0, 0.0, 0.0)
        s = _unit(_vcross(t, up))
        u = _unit(_vcross(s, t))
        corners = [
            [p[c] + (-s[c] + u[c]) * half_w for c in range(3)],
            [p[c] + ( s[c] + u[c]) * half_w for c in range(3)],
            [p[c] + ( s[c] - u[c]) * half_w for c in range(3)],
            [p[c] + (-s[c] - u[c]) * half_w for c in range(3)],
        ]
        rings.append((corners, s, u))
    for k in range(n - 1):
        c0, s0, u0 = rings[k]
        c1, s1, u1 = rings[k + 1]
        v0, v1 = oy + H * k / (n - 1), oy + H * (k + 1) / (n - 1)
        hints = (u0, s0, [-x for x in u0], [-x for x in s0])
        for f in range(4):
            f1 = (f + 1) % 4
            fu0, fu1 = ox + W * f / 4, ox + W * (f + 1) / 4
            _flat_quad(b, part, [c0[f], c0[f1], c1[f1], c1[f]], [(fu0, v0), (fu1, v0), (fu1, v1), (fu0, v1)], hints[f])
    for end, toward in ((0, 1), (n - 1, n - 2)):
        hint = _unit(_vsub(path[end], path[toward]))
        c = rings[end][0]
        _flat_quad(b, part, c, [(ox, oy), (ox + W, oy), (ox + W, oy + H), (ox, oy + H)], hint)


def yarn_ball():
    """The rolling toy: a flat-shaded low-poly ball of rose yarn (~6.8 across) built from overlapping modelled wrap bands
    over a calm core, with a short chunky loose end resting on the ground inside the ball's footprint."""
    b = Build('yarn_ball', square=True)
    centre = (0.0, YARN_Y, 0.0)
    core_img = sphere_paint(16, 8, lambda d: ROSE[2] if d[1] > -0.2 else ROSE[1])
    ellipsoid(b, b.part('wound_core'), centre, (3.06, 3.06, 3.06), 'core', core_img, segs=16, rows=7,
              floor=-3.02, flat=True)
    bands = b.part('wrap_bands')
    for idx, (axis, offset, half_w, r_out, (t_a, t_b, t_edge)) in enumerate(YARN_BANDS):
        band_img = canvas(16, 5)
        for u in range(16):
            put(band_img, u, 0, ROSE[t_edge], False)
            put(band_img, u, 1, ROSE[t_a], False)
            put(band_img, u, 2, ROSE[t_b], False)
            put(band_img, u, 3, ROSE[t_a], False)
            put(band_img, u, 4, ROSE[t_edge], False)
        _wrap_band(b, bands, centre, 2.90, r_out, half_w, axis, offset, f'band{idx}', band_img, segs=16, floor_y=0.0)
    tail_img = canvas(4, 6)
    for v in range(6):
        put(tail_img, 0, v, ROSE[3], False)
        put(tail_img, 1, v, ROSE[2], False)
        put(tail_img, 2, v, ROSE[1], False)
        put(tail_img, 3, v, ROSE[2], False)
    tail = [(-2.35, 1.05, -1.75), (-2.80, 0.48, -1.95), (-3.10, 0.22, -1.65),
            (-3.22, 0.22, -0.85), (-2.85, 0.22, -0.25)]
    _square_strand(b, b.part('loose_thread'), tail, 0.22, 'tail', tail_img)
    return b.finish()


def glass_marble():
    """The rolling marble: a flat-shaded low-poly translucent glass sphere (4.4 across) enclosing an opaque 3D amber-gold
    cat's-eye swirl and topped with a crisp white specular glint."""
    b = Build('glass_marble', square=True)
    centre = (0.0, 2.1, 0.0)
    # Part 0 (opaque): three crossed, twisted cat's-eye ribbon vanes suspended inside the glass.
    swirl = b.part('cat_eye')
    tilt = turn(20, 18, -25)
    e1 = [tilt[i][0] for i in range(3)]
    ax = [tilt[i][1] for i in range(3)]
    e2 = [tilt[i][2] for i in range(3)]
    vane_palettes = (
        ('#ff8c1a', '#ffc23d', '#fff4c2'),
        ('#ffad2e', '#ffe066', '#fffbe6'),
        ('#ff8c1a', '#ffc23d', '#fff4c2'),
    )
    steps = 8
    for vi, (c_out, c_mid, c_in) in enumerate(vane_palettes):
        vimg = canvas(4, 8)
        for y in range(8):
            put(vimg, 0, y, c_in, False)
            put(vimg, 1, y, c_mid, False)
            put(vimg, 2, y, c_mid, False)
            put(vimg, 3, y, c_out, False)
        ox, oy = b.region(f'vane{vi}', vimg, wrap=False)
        base_a = vi * 2 * math.pi / 3
        rings = []
        for s in range(steps + 1):
            t = -1.0 + 2.0 * s / steps
            h = 1.52 * t
            w = 1.48 * math.sqrt(max(0.0, 1.0 - t * t))
            ang = base_a + 1.15 * t
            rad = [math.cos(ang) * e1[c] + math.sin(ang) * e2[c] for c in range(3)]
            nrm = [-math.sin(ang) * e1[c] + math.cos(ang) * e2[c] for c in range(3)]
            p_in = [centre[c] + ax[c] * h for c in range(3)]
            p_out = [centre[c] + ax[c] * h + rad[c] * max(0.14, w) for c in range(3)]
            rings.append((p_in, p_out, nrm))
        for s in range(steps):
            a0, b0, n0 = rings[s]
            a1, b1, n1 = rings[s + 1]
            v0, v1 = oy + 8 * s / steps, oy + 8 * (s + 1) / steps
            nmid = _unit([n0[c] + n1[c] for c in range(3)])
            uvs = [(ox, v0), (ox + 4, v0), (ox + 4, v1), (ox, v1)]
            _flat_quad(b, swirl, [a0, b0, b1, a1], uvs, nmid)
            _flat_quad(b, swirl, [a0, a1, b1, b0], [(ox, v0), (ox, v1), (ox + 4, v1), (ox + 4, v0)], [-c for c in nmid])
    # Part 1 (translucent): flat-shaded clear/sky-blue glass sphere with crisp specular highlight.
    def glass(d):
        g = _dot(d, _unit((0.46, 0.68, -0.56)))
        if g > 0.945:
            return (255, 255, 255, 245)                     # tight crisp white specular glint
        if g > 0.87:
            return (218, 242, 255, 165)                     # small pale glint halo
        if _dot(d, _unit((-0.45, -0.50, 0.55))) > 0.92:
            return (210, 238, 255, 150)                     # tiny opposite bounce glint
        if d[1] > 0.65:
            return (162, 218, 255, 88)                      # clear bright sky dome
        if d[1] < -0.55:
            return (64, 136, 214, 132)                      # deep sky-blue glass base
        return (96, 178, 245, 74)                           # clear sky-blue glass body
    ellipsoid(b, b.part('glass_shell', translucent=True), centre, (2.2, 2.2, 2.2), 'glass',
              sphere_paint(24, 12, glass), segs=14, rows=7, floor=-2.1, flat=True)
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
