"""Self-built nests of wild yukkuri: piled leaves, grass, twigs, moss and straw in a hollow.

Every surface references a vanilla texture (minecraft:textures/..., never copied into the repository) mapped at the vanilla
density of 1 texel per unit (16 per block); leaf and grass textures carry the vanilla plains tint as the part tint.
Units are 1/16 block, origin at the centre of the bed, ground Y = 0, +Z is the south side.

Real body sizes: standard adult 12.8 wide x 10.7 deep (individual target up to 1.2x, deibu 1.5x), children grow from 0.2 to
0.8. The shared family bed therefore keeps a 16 x 16 hollow and builds its rim in the block space around it (exposed
sides only); the personal bed of a growing child has a 10 x 8 hollow.

Render units follow client/ShelterRenderer: forest_burrow 1 = bedding, 2..5 = side mids north / east / south / west,
6..9 = corner arcs NE / SE / SW / NW, 10..17 = side ends (see forest_burrow); personal_leaf_nest 1 = a few leaves,
2 = rough ring, 3 = finished nest; bedding_bundle and predator_nest are unit 0.
"""
import math
from kit import Prop, deterministic

V = 'minecraft:textures/'
LEAF = (V + 'block/oak_leaves.png', (138, 196, 58))        # plains foliage tint
LEAF_DRY = (V + 'block/oak_leaves.png', (206, 170, 72))    # withered leaves: the same texture in a dry tint
LEAF_BIRCH = (V + 'block/birch_leaves.png', (128, 167, 85))
LEAF_SPRUCE = (V + 'block/spruce_leaves.png', (97, 153, 97))
GRASS = (V + 'block/short_grass.png', (145, 189, 89))       # plains grass tint
MOSS = (V + 'block/moss_block.png', None)
PODZOL = (V + 'block/podzol_top.png', None)
COARSE = (V + 'block/coarse_dirt.png', None)
DIRT = (V + 'block/dirt.png', None)
LOG = (V + 'block/oak_log.png', None)
LOG_DARK = (V + 'block/spruce_log.png', None)
HAY = (V + 'block/hay_block_top.png', (214, 204, 168))   # softened straw
HAY_SIDE = (V + 'block/hay_block_side.png', None)
DEAD_BUSH = (V + 'block/dead_bush.png', None)
WHEAT = (V + 'item/wheat.png', None)
FEATHER = (V + 'item/feather.png', None)
WOOL = (V + 'block/white_wool.png', None)
FULL = [(0, 1), (1, 1), (1, 0), (0, 0)]


class Builder:
    """Collects vanilla-textured parts of one prop by (material, unit)."""

    def __init__(self, prop, seed):
        self.p, self.rng, self.cache = prop, deterministic(seed), {}

    def part(self, mat, unit):
        key = (mat, unit)
        if key not in self.cache:
            extra = {'texture': mat[0]}
            if mat[1]:
                extra['tint'] = list(mat[1])
            name = mat[0].rsplit('/', 1)[1][:-4] + ('' if not mat[1] else '_t%02x%02x%02x' % mat[1]) + f'_u{unit}'
            self.cache[key] = self.p.part(name, unit, **extra)
        return self.cache[key]

    # -- oriented box with planar vanilla-density mapping -------------------------------------------
    def box(self, mat, unit, centre, half, yaw=0.0, pitch=0.0, density=1.0, grain=False, bottom=False):
        hx, hy, hz = half
        cy, sy = math.cos(yaw), math.sin(yaw)
        cp, sp = math.cos(pitch), math.sin(pitch)

        def place(x, y, z):
            x2, y2 = x * cp - y * sp, x * sp + y * cp          # pitch about local z
            return [centre[0] + x2 * cy + z * sy, centre[1] + y2, centre[2] - x2 * sy + z * cy]

        def rot_n(n):
            x2, y2 = n[0] * cp - n[1] * sp, n[0] * sp + n[1] * cp
            return [x2 * cy + n[2] * sy, y2, -x2 * sy + n[2] * cy]
        part = self.part(mat, unit)
        faces = [
            ('top', [(-hx, hy, -hz), (-hx, hy, hz), (hx, hy, hz), (hx, hy, -hz)], (0, 1, 0), 0, 2, 2 * hx, 2 * hz),
            ('s', [(-hx, -hy, hz), (hx, -hy, hz), (hx, hy, hz), (-hx, hy, hz)], (0, 0, 1), 0, 1, 2 * hx, 2 * hy),
            ('n', [(hx, -hy, -hz), (-hx, -hy, -hz), (-hx, hy, -hz), (hx, hy, -hz)], (0, 0, -1), 0, 1, 2 * hx, 2 * hy),
            ('e', [(hx, -hy, hz), (hx, -hy, -hz), (hx, hy, -hz), (hx, hy, hz)], (1, 0, 0), 2, 1, 2 * hz, 2 * hy),
            ('w', [(-hx, -hy, -hz), (-hx, -hy, hz), (-hx, hy, hz), (-hx, hy, -hz)], (-1, 0, 0), 2, 1, 2 * hz, 2 * hy),
        ]
        if bottom:
            faces.append(('bottom', [(-hx, -hy, -hz), (hx, -hy, -hz), (hx, -hy, hz), (-hx, -hy, hz)], (0, -1, 0), 0, 2, 2 * hx, 2 * hz))
        for key, corners, normal, ax_a, ax_b, ext_a, ext_b in faces:
            ea, eb = min(ext_a * density, 16.0), min(ext_b * density, 16.0)
            oa, ob = self.rng.uniform(0, 16 - ea), self.rng.uniform(0, 16 - eb)
            uvs = []
            for c in corners:
                a = (c[ax_a] + ext_a / 2) * density
                b = ((ext_b / 2) - c[ax_b]) * density if key not in ('top', 'bottom') else (c[ax_b] + ext_b / 2) * density
                a, b = max(0.0, min(ea, a)), max(0.0, min(eb, b))
                uvs.append(((ob + b) / 16, (oa + a) / 16) if grain else ((oa + a) / 16, (ob + b) / 16))
            self.p.quad(part, [place(*c) for c in corners], None, uvs=uvs, normal=rot_n(normal))

    def sprite(self, mat, unit, centre, size, yaw, up=False, tilt=0.0, density=None):
        """Square vanilla sprite: lying flat (slightly tilted) or standing as one plane; double sided."""
        part = self.part(mat, unit)
        h = size / 2
        pts = []
        for sx, sz in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
            a, b = sx * h, sz * h
            if up:
                x, y, z = a, b + h, 0.0
            else:
                x, y, z = a, a * tilt, b
            pts.append([centre[0] + x * math.cos(yaw) - z * math.sin(yaw), centre[1] + y, centre[2] + x * math.sin(yaw) + z * math.cos(yaw)])
        self.p.quad(part, pts, None, uvs=FULL, double=True, normal=[0, 1, 0])

    def tuft(self, unit, x, z, y, size=4.5, mat=GRASS):
        yaw = self.rng.uniform(0, math.pi)
        for off in (0, math.pi / 2):
            self.sprite(mat, unit, (x, y, z), size, yaw + off, up=True)


DRY = (V + 'block/short_grass.png', (206, 180, 92))         # dry grass: the same sprite in a straw tint


def leaf_mat(rng):
    r = rng.random()
    return LEAF_DRY if r < .55 else LEAF if r < .78 else LEAF_BIRCH if r < .94 else LEAF_SPRUCE


def smooth(x):
    x = max(0.0, min(1.0, x))
    return x * x * (3 - 2 * x)


# Side frame: a = along the edge, t = outward from the bed centre. Sides 0..3 = north, east, south, west.
def to_world(side, a, t):
    return ((-a, -t), (t, -a), (a, t), (-t, a))[side]


def tangent_yaw(tx, tz):
    """box() yaw that turns the local x axis onto the world direction (tx, tz)."""
    return math.atan2(-tz, tx)


# Crest height by side: the back (north) is the highest, the front (south, the entrance side) the lowest so a face stays visible.
SIDE_H = (5.0, 4.0, 2.4, 4.0)
CORNER_H = {(1, -1): 4.5, (1, 1): 3.2, (-1, 1): 3.2, (-1, -1): 4.5}      # (sign x, sign z): NE, SE, SW, NW
CREST = 9.0                                                              # distance of the rim crest from the bed centre


def heap(b, unit, x, z, yaw, w, d, h, one_mat=True, turn=.2):
    """A gathered pile of leaves: three stacked, slightly turned boxes (wide base, narrower middle, small cap). A smaller
    turn also keeps the upper boxes aligned and centred, for a thin rim band."""
    rng = b.rng
    base = leaf_mat(rng)
    pick = (lambda: base) if one_mat else (lambda: leaf_mat(rng))
    h1, h2 = h * .58, h * .3
    loose = turn / .2
    b.box(base, unit, (x, h1 / 2, z), (w / 2, h1 / 2, d / 2), yaw=yaw + rng.uniform(-turn, turn))
    b.box(pick(), unit, (x + rng.uniform(-.5, .5) * loose, h1 + h2 / 2 - .1, z + rng.uniform(-.5, .5) * loose), (w * .38, h2 / 2 + .1, d * .38), yaw=yaw + rng.uniform(-.7, .7) * loose)
    if h > 2.4:
        mat = pick()
        centre = (x + rng.uniform(-.5, .5) * loose, h1 + h2 + .2, z + rng.uniform(-.4, .4) * loose)
        cap = rng.uniform(0, 3)
        b.box(mat, unit, centre, (w * .2, .35, d * .2), yaw=cap if turn == .2 else yaw + (cap - 1.5) * turn)


def trimmings(b, unit, x, z, h, tx, tz, dry=True):
    """Dry-grass tufts, a straw ear and a twig tucked into a rim pile."""
    rng = b.rng
    if rng.random() < .4:
        b.tuft(unit, x + rng.uniform(-.8, .8), z + rng.uniform(-.8, .8), h * .72, rng.uniform(2.6, 3.4), mat=DRY if dry else GRASS)
    if rng.random() < .4:
        b.sprite(WHEAT, unit, (x + rng.uniform(-1, 1), h * .8 + rng.uniform(-.2, .3), z + rng.uniform(-1, 1)), 3.6, rng.uniform(0, 6.28), tilt=rng.uniform(-.25, .25))
    if rng.random() < .45:
        b.box(LOG if rng.random() < .5 else LOG_DARK, unit, (x, h * .85, z), (rng.uniform(2.0, 3.0), .4, .4),
              yaw=tangent_yaw(tx, tz) + rng.uniform(-.9, .9), pitch=rng.uniform(-.14, .14), grain=True)


def rim_run(b, unit, pts, height, thick=3.4):
    """pts = [(x, z, tx, tz, nx, nz)]: heaps along the crest with an inner low shoulder and a ragged outer edge."""
    rng = b.rng
    for (x, z, tx, tz, nx, nz) in pts:
        h = height * rng.uniform(.82, 1.15)
        yaw = tangent_yaw(tx, tz)
        off = rng.uniform(-.5, .5)
        heap(b, unit, x + nx * off, z + nz * off, yaw, rng.uniform(3.8, 5.0), rng.uniform(3.2, thick + .6), h, one_mat=rng.random() < .7)
        if rng.random() < .8:                              # soft inner shoulder
            heap(b, unit, x - nx * 2.0, z - nz * 2.0, yaw + rng.uniform(-.4, .4), rng.uniform(2.6, 3.6), 2.2, h * .38)
        if rng.random() < .55:                             # loose outer scrap, breaks the straight outline
            sx, sz = x + nx * rng.uniform(2.0, 2.8) + tx * rng.uniform(-1.2, 1.2), z + nz * rng.uniform(2.0, 2.8) + tz * rng.uniform(-1.2, 1.2)
            b.box(leaf_mat(rng), unit, (sx, .3 + rng.uniform(0, .25), sz), (rng.uniform(1.1, 1.6), .22, rng.uniform(.8, 1.2)), yaw=rng.uniform(0, 3))
        trimmings(b, unit, x, z, h, tx, tz)


def side_pts(side, lo, hi, count, jitter=.35):
    """Points on the straight crest of a side between a = lo and a = hi (world frame, outward normal included)."""
    rng = deterministic(7 * side + 13 * int(lo + 20) + int(hi))
    out = []
    for i in range(count):
        a = lo + (hi - lo) * (i + .5) / count + rng.uniform(-jitter, jitter)
        t = CREST + rng.uniform(-.5, .5)
        x, z = to_world(side, a, t)
        tx, tz = to_world(side, 1, 0)
        nx, nz = to_world(side, 0, 1)
        out.append((x, z, tx, tz, nx, nz))
    return out


def corner_pts(sx, sz, count=4):
    """Arc around a convex corner: centre (4 sx, 4 sz), radius CREST - 4, from the side facing z to the side facing x."""
    rng = deterministic(31 + sx * 3 + sz * 5)
    cx, cz = 4.0 * sx, 4.0 * sz
    out = []
    for i in range(count):
        ang = math.pi / 2 * (i + .5) / count
        nx, nz = sx * math.sin(ang), sz * math.cos(ang)       # from (0, sz) toward (sx, 0)
        r = CREST - 4.0 + rng.uniform(-.4, .4)
        out.append((cx + nx * r, cz + nz * r, -nz, nx, nx, nz))
    return out


def litter(b, unit, bounds, spacing, dish, rng_scale=1.0, skip=None, hay=.08):
    """Loose leaf litter: overlapping thin leaf plates on a jittered grid, lying in a soft dish (low in the middle)."""
    rng = b.rng
    (x0, x1, z0, z1) = bounds
    nx, nz = int((x1 - x0) / spacing) + 1, int((z1 - z0) / spacing) + 1
    k = 0
    for i in range(nx):
        for j in range(nz):
            x = x0 + (i + .5) * (x1 - x0) / nx + rng.uniform(-.7, .7)
            z = z0 + (j + .5) * (z1 - z0) / nz + rng.uniform(-.7, .7)
            if skip and skip(x, z):
                continue
            k += 1
            y = .1 + dish(x, z) + (k % 3) * .04
            mat = HAY if rng.random() < hay else leaf_mat(rng)
            b.box(mat, unit, (x, y + .12, z), (rng.uniform(1.5, 2.2) * rng_scale, .12, rng.uniform(1.2, 1.7) * rng_scale), yaw=rng.uniform(0, math.pi))


def forest_burrow():
    """Shared family bed cell. Unit 1: leaf litter pad in a soft dish. Units 2..5 = side mids N/E/S/W (|a| <= 4), 6..9 = corner
    arcs NE/SE/SW/NW (both adjoining sides exposed), 10..17 = side ends where the edge runs on over the next bed:
    N-west N-east E-north E-south S-east S-west W-south W-north. Joined, the pieces make one round-cornered ring."""
    p = Prop('forest_burrow')
    b = Builder(p, 931)
    rng = b.rng
    dish = lambda x, z: .55 * smooth((math.hypot(x, z) - 3.5) / 7.0) + .08
    for _ in range(6):                                   # dark mulch showing through the litter
        x, z = rng.uniform(-5.5, 5.5), rng.uniform(-5.5, 5.5)
        b.box(PODZOL, 1, (x, .07, z), (rng.uniform(1.8, 2.6), .07, rng.uniform(1.6, 2.4)), yaw=rng.uniform(0, 3))
    litter(b, 1, (-8.6, 8.6, -8.6, 8.6), 3.2, dish)
    for _ in range(5):                                   # dry tufts and a little moss, clear of the sleeping spot
        a = rng.uniform(0, math.tau)
        r = rng.uniform(5.2, 7.6)
        b.tuft(1, math.cos(a) * r, math.sin(a) * r, .7, 3.2, mat=DRY)
    b.box(MOSS, 1, (rng.uniform(-5, 5), .33, 6.2), (1.5, .1, 1.1), yaw=.4)
    for side in range(4):
        rim_run(b, 2 + side, side_pts(side, -4, 4, 3), SIDE_H[side])
    for unit, (sx, sz) in zip((6, 7, 8, 9), ((1, -1), (1, 1), (-1, 1), (-1, -1))):
        rim_run(b, unit, corner_pts(sx, sz), CORNER_H[(sx, sz)])
    # ends: (unit, side, sign of a)
    for unit, side, sign in ((10, 0, 1), (11, 0, -1), (12, 1, 1), (13, 1, -1), (14, 2, 1), (15, 2, -1), (16, 3, 1), (17, 3, -1)):
        lo, hi = (4, 8) if sign > 0 else (-8, -4)
        rim_run(b, unit, side_pts(side, lo, hi, 3, jitter=.2), SIDE_H[side])
    return p


def personal_leaf_nest():
    """A growing child's own bed, oval hollow about 10 x 8. Stage 1 (unit 1): a few leaves and straw scattered on the spot.
    Stage 2 (unit 2): a rough low ring of leaf piles and more litter. Stage 3 (unit 3): the ring built up (back highest,
    front low), dry grass, straw and a few twigs tucked in."""
    p = Prop('personal_leaf_nest')
    b = Builder(p, 4417)
    rng = b.rng
    RX, RZ = 6.6, 5.5
    inside = lambda x, z: (x / (RX + .6)) ** 2 + (z / (RZ + .6)) ** 2 > 1
    dish = lambda x, z: .4 * smooth((math.hypot(x / RX, z / RZ) * 8 - 2.5) / 6) + .06
    for _ in range(3):
        b.box(PODZOL, 1, (rng.uniform(-3, 3), .06, rng.uniform(-2.5, 2.5)), (rng.uniform(1.6, 2.2), .06, rng.uniform(1.4, 2.0)), yaw=rng.uniform(0, 3))
    litter(b, 1, (-6.4, 6.4, -5.4, 5.4), 3.6, dish, rng_scale=.9, skip=inside, hay=.2)
    # stage 2: a rough, low, uneven ring
    ring = []
    for i in range(10):
        a = math.tau * i / 10 + rng.uniform(-.12, .12)
        ring.append((a, RX + rng.uniform(-.4, .5), RZ + rng.uniform(-.4, .5)))
    for a, rx, rz in ring:
        x, z = math.cos(a) * rx, math.sin(a) * rz
        front = smooth((math.sin(a) + .2) * 1.3)                  # +z is the front: lower there
        h = (2.1 - .7 * front) * rng.uniform(.8, 1.1)
        heap(b, 2, x, z, tangent_yaw(-math.sin(a), math.cos(a)), rng.uniform(3.2, 4.2), rng.uniform(2.6, 3.4), h, one_mat=rng.random() < .6)
    litter(b, 2, (-5.2, 5.2, -4.2, 4.2), 3.8, lambda x, z: dish(x, z) + .22, rng_scale=.8, skip=lambda x, z: x * x / 22 + z * z / 14 > 1)
    # stage 3: built up, trimmed
    for a, rx, rz in ring:
        x, z = math.cos(a) * rx, math.sin(a) * rz
        front = smooth((math.sin(a) + .2) * 1.3)
        h = (3.4 - 1.5 * front) * rng.uniform(.85, 1.1)
        tx, tz = -math.sin(a), math.cos(a)
        heap(b, 3, x * 1.04, z * 1.04, tangent_yaw(tx, tz), rng.uniform(3.4, 4.4), rng.uniform(2.6, 3.2), h, one_mat=rng.random() < .7)
        trimmings(b, 3, x, z, h, tx, tz)
    for _ in range(4):
        a = rng.uniform(0, math.tau)
        r = rng.uniform(.55, .8)
        b.tuft(3, math.cos(a) * RX * r, math.sin(a) * RZ * r, .5, 3.0, mat=DRY)
    return p


def bedding_bundle():
    """One carried portion: a loose heap of leaves with a grass tuft, a straw ear and a twig."""
    p = Prop('bedding_bundle')
    b = Builder(p, 207)
    rng = b.rng
    for i in range(9):
        a = rng.uniform(0, math.tau)
        r = rng.uniform(0, 1.7)
        b.box(leaf_mat(rng), 0, (math.cos(a) * r, .35 + .24 * i, math.sin(a) * r * .8), (rng.uniform(1.4, 1.9), .16, rng.uniform(1.0, 1.4)), yaw=rng.uniform(0, math.pi), pitch=rng.uniform(-.15, .15))
    b.tuft(0, -1.0, .6, .8, 3.6)
    b.tuft(0, 1.2, -.7, .9, 3.2)
    b.sprite(WHEAT, 0, (0.4, 2.6, .4), 3.6, .7, tilt=.1)
    b.box(LOG, 0, (0, 1.2, 1.5), (2.2, .35, .35), yaw=.5, pitch=.08, grain=True)
    return p


def predator_nest():
    """Woven twig bowl of the flying (Remilia / Flandre) species on a ledge: four courses of oak and spruce twigs, a floor of
    straw, moss and dry leaves at the block's standing height (2.4) with pale feathers, a few sticks poking out.
    Interior 15.4 across so a 12.8 body fits with its wings folded."""
    p = Prop('predator_nest')
    b = Builder(p, 7302)
    rng = b.rng
    FLOOR = 2.4
    for gx in range(-2, 2):
        for gz in range(-2, 2):
            cx, cz = (gx + .5) * 4.0, (gz + .5) * 4.0
            if math.hypot(cx, cz) > 7.2:
                continue
            e = math.hypot(cx, cz) / 9.0
            top = FLOOR - .15 + .35 * e ** 2 + rng.uniform(-.03, .03)
            b.box(rng.choice((HAY_SIDE, MOSS, PODZOL, PODZOL)), 0, (cx, top - 1.0, cz), (2.0, 1.0, 2.0))
    for i in range(22):
        a = rng.uniform(0, math.tau)
        r = math.sqrt(rng.uniform(0, 1)) * 6.6
        b.box(LEAF_DRY if i % 3 else LEAF_BIRCH, 0, (math.cos(a) * r, FLOOR + .02 + (i % 4) * .03, math.sin(a) * r), (rng.uniform(1.0, 1.6), .1, rng.uniform(.9, 1.3)), yaw=rng.uniform(0, math.pi))
    for i in range(5):
        a = rng.uniform(0, math.tau)
        r = rng.uniform(1.0, 5.5)
        b.sprite(FEATHER, 0, (math.cos(a) * r, FLOOR + .3 + i * .02, math.sin(a) * r), rng.uniform(2.6, 3.6), rng.uniform(0, 6.28), tilt=rng.uniform(-.2, .2))
    count = 15
    for course in range(4):
        R = 8.3 + course * .3
        y = FLOOR - .6 + course * 1.05
        for k in range(count):
            a = math.tau * (k + .5 * (course % 2)) / count + rng.uniform(-.04, .04)
            x, z = math.cos(a) * R, math.sin(a) * R
            length = math.tau * R / count * 1.45 / 2
            b.box(LOG if (k + course) % 3 else LOG_DARK, 0, (x, y + rng.uniform(-.12, .12), z), (length, .55, .55),
                  yaw=-a + math.pi / 2 + rng.uniform(-.12, .12), pitch=rng.uniform(-.06, .06), grain=True)
    for i in range(6):                                   # sticks poking out of the weave, bound at slight angles
        a = math.tau * (i + rng.uniform(-.2, .2)) / 6
        y = FLOOR + rng.uniform(.4, 2.2)
        r0, r1 = 7.0, rng.uniform(10.2, 11.2)
        rc = (r0 + r1) / 2
        b.box(LOG if i % 2 else LOG_DARK, 0, (math.cos(a) * rc, y + .3, math.sin(a) * rc), ((r1 - r0) / 2, .5, .5),
              yaw=-a, pitch=rng.uniform(.06, .2), grain=True)
    for i in range(4):
        a = rng.uniform(0, math.tau)
        b.tuft(0, math.cos(a) * 8.4, math.sin(a) * 8.4, FLOOR + 1.2, 4.0, mat=DEAD_BUSH)
    return p


def squircle(half, t, n=4.0):
    c, s = math.cos(t), math.sin(t)
    return (half * math.copysign(abs(c) ** (2 / n), c), half * math.copysign(abs(s) ** (2 / n), s))


def large_wild_nest():
    """Family bedding on a 2x2 block footprint (32 x 32, origin at the centre, entrance on +z / south). Pressed grass and leaf
    litter in a rounded square: one continuous bed of leaves, hay and a little moss with no marked sleeping places, about 28.5
    across. The rim is one thin band of low leaf heaps bound with laid twigs at the very edge (about 1.4 deep, under 3.0 high,
    outline within 15.6 of the centre) and is open across the front so newborns walk in."""
    p = Prop('large_wild_nest')
    b = Builder(p, 5521)
    rng = b.rng
    INNER, CREST = 14.25, 14.9
    sq = lambda x, z: ((x / INNER) ** 4 + (z / INNER) ** 4) ** .25          # 1 on the inner squircle
    dish = lambda x, z: .4 * smooth((sq(x, z) - .6) / .4) + .06
    for _ in range(12):                                    # dark ground showing through
        x, z = rng.uniform(-12, 12), rng.uniform(-12, 12)
        b.box(PODZOL, 0, (x, .06, z), (rng.uniform(1.8, 2.6), .06, rng.uniform(1.6, 2.4)), yaw=rng.uniform(0, 3))
    # Plate centres stay 1.6 inside the rim so the plates tuck under the heaps instead of spilling past the outline.
    litter(b, 0, (-INNER, INNER, -INNER, INNER), 3.2, dish, skip=lambda x, z: sq(x, z) > .89, hay=.15)
    placed = 0
    while placed < 11:                                     # stray hay wisps and moss tufts dropped at random on the litter
        x, z = rng.uniform(-INNER, INNER), rng.uniform(-INNER, INNER)
        if sq(x, z) > .85:
            continue
        placed += 1
        mat = MOSS if rng.random() < .3 else HAY
        b.box(mat, 0, (x, dish(x, z) + .1, z), (rng.uniform(.8, 1.9), .06, rng.uniform(.5, 1.1)), yaw=rng.uniform(0, math.pi))
    for _ in range(9):                                     # loose litter spilling out of the opening as a walk-in ramp
        x, z = rng.uniform(-6.5, 6.5), rng.uniform(12.8, 14.2)
        b.box(leaf_mat(rng), 0, (x, .08, z), (rng.uniform(1.2, 1.6), .08, rng.uniform(.8, 1.1)), yaw=rng.uniform(0, math.pi))
    # Rim: a single course of low heaps on the crest, highest at the back, tapering to nothing either side of the front
    # opening. Heaps are long along the edge and shallow across it, turned only slightly, so the band stays thin.
    count = 32
    for k in range(count):
        t = math.tau * (k + rng.uniform(-.15, .15)) / count
        front = math.sin(t)
        if front > .93:                                    # about 14 units of open front between the low end heaps
            continue
        h = (2.6 - .7 * smooth((front + .2) / .9) - .9 * smooth((front - .6) / .33)) * rng.uniform(.9, 1.05)
        x, z = squircle(CREST, t)
        nx, nz = squircle(CREST + 1, t)
        nx, nz = nx - x, nz - z
        n = math.hypot(nx, nz)
        nx, nz = nx / n, nz / n
        tx, tz = -nz, nx
        yaw = tangent_yaw(tx, tz)
        heap(b, 0, x, z, yaw, rng.uniform(3.8, 4.4), rng.uniform(1.2, 1.4), h, one_mat=rng.random() < .7, turn=.05)
        if k % 2 == 0:                                     # twigs laid along the rim bind the heaps
            b.box(LOG if rng.random() < .5 else LOG_DARK, 0, (x, h * .55, z), (rng.uniform(2.2, 2.6), .35, .35),
                  yaw=yaw + rng.uniform(-.06, .06), pitch=rng.uniform(-.08, .08), grain=True)
        if rng.random() < .45:
            u = rng.uniform(-1.2, 1.2)
            b.tuft(0, x + tx * u, z + tz * u, h * .45, rng.uniform(1.3, 1.6), mat=DRY if rng.random() < .6 else GRASS)
        if rng.random() < .3:
            u = rng.uniform(-1, 1)
            b.sprite(WHEAT, 0, (x + tx * u, h * .7, z + tz * u), 1.6, yaw + rng.uniform(-.15, .15), tilt=rng.uniform(-.2, .2))
    return p


WILD = {'forest_burrow': forest_burrow, 'personal_leaf_nest': personal_leaf_nest, 'bedding_bundle': bedding_bundle,
        'predator_nest': predator_nest}
