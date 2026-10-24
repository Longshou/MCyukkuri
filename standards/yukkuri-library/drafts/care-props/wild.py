"""Self-built nests of wild yukkuri.

Ordinary wild yukkuri (forest_burrow, personal_leaf_nest, bedding_bundle, large_wild_nest) use the hand-drawn wild_kit atlas
shared with the forage pile: a woven dry-grass roll, a leaf-litter pad, big leaves, square twigs and grass tufts, every
texture at 1 texel per unit. The predator species' twig nest (predator_nest) keeps its vanilla texture references
(minecraft:textures/..., never copied, plains tint on leaves and grass) through Builder below.
Units are 1/16 block, origin at the centre of the bed, ground Y = 0, +Z is the south side.

Real body sizes: standard adult 12.8 wide x 10.7 deep (individual target up to 1.2x, deibu 1.5x), children grow from 0.2 to
0.8. The shared family bed therefore keeps a 16 x 16 hollow and builds its rim in the block space around it (exposed
sides only); the personal bed of a growing child has a 10 x 8 hollow.

Render units follow client/ShelterRenderer: forest_burrow 1 = bedding, 2..5 = side mids north / east / south / west,
6..9 = corner arcs NE / SE / SW / NW, 10..17 = side ends (see forest_burrow); personal_leaf_nest 1 = a few leaves,
2 = rough ring, 3 = finished nest; bedding_bundle, large_wild_nest and predator_nest are unit 0.
"""
import math
from kit import Prop, deterministic
from wild_kit import WildProp, PathLine, bundle, rim_profile, rng_of, smooth

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


# =====================================================================================================================
# Self-built nests of ordinary wild yukkuri (wild_kit atlas, hand drawn). A bird's-nest bowl: a woven roll of dry grass
# whose strands circle the bed, a floor of fallen leaves and straw, a few big leaves laid in as lining, a couple of square
# twigs worked into the rim and grass tufts poking out. Wiki: dry-grass bowl nests lined with green leaves (EH19, M04),
# a hay bed wider than the sleeping circle (N103), leaves and twigs gathered in autumn (N38), moss layers (N23).
# Arrangement, sizes and the moss / twig mix are project design.

# Side frame: a = along the edge, t = outward from the bed centre. Sides 0..3 = north, east, south, west.
def to_world(side, a, t):
    return ((-a, -t), (t, -a), (a, t), (-t, a))[side]


SIDE_H = (5.0, 4.0, 2.4, 4.0)          # crest height by side: back (north) highest, front (south, entrance side) lowest
CREST = 9.0                            # crest line distance from the bed centre
BED_IN, BED_OUT = 2.9, 2.8             # bundle half widths at the foot: inner foot near t 6.1, outer foot near t 11.8
PAD_Y = .18                            # top of the leaf pad; the sleeping surface stays at y 0
ARC_SIDES = {(-1, -1): (0, 3), (-1, 1): (3, 2), (1, 1): (2, 1), (1, -1): (1, 0)}     # corner -> (side before, side after)


def side_line(side):
    """Crest line of one side, parametrised by a + 8 (a = -8..8 along the edge)."""
    nx, nz = to_world(side, 0, 1)
    pts = []
    for k in range(17):
        x, z = to_world(side, k - 8, CREST)
        pts.append((x, z, nx, nz))
    return PathLine(pts)


def arc_line(sx, sz, n=12):
    a_side, b_side = ARC_SIDES[(sx, sz)]
    da, db = to_world(a_side, 0, 1), to_world(b_side, 0, 1)
    a0, a1 = math.atan2(da[1], da[0]), math.atan2(db[1], db[0])
    while a1 - a0 > math.pi:
        a1 -= math.tau
    while a0 - a1 > math.pi:
        a1 += math.tau
    pts = []
    for i in range(n + 1):
        ang = a0 + (a1 - a0) * i / n
        pts.append((4.0 * sx + math.cos(ang) * (CREST - 4), 4.0 * sz + math.sin(ang) * (CREST - 4), math.cos(ang), math.sin(ang)))
    return PathLine(pts), SIDE_H[a_side], SIDE_H[b_side]


def tufts(p, part, rng, line, s_lo, s_hi, h, count):
    """Loose stems breaking the rim outline: about half poke out of the outer slope, the rest cross the top of the rim."""
    for k in range(count):
        s = rng.uniform(s_lo, s_hi)
        x, z, nx, nz = line.at(s)
        if k % 2 == 0:
            t = rng.uniform(.4, 1.2)
            p.straw(part, [x + nx * t, h * rng.uniform(.5, .85), z + nz * t], math.atan2(nz, nx) + rng.uniform(-1.0, 1.0),
                    rng.uniform(.05, .6), rng.uniform(2.0, 3.8), s0=rng.randrange(8))
        else:
            tx, tz = -nz, nx
            a = math.atan2(tz, tx) + rng.choice((0, math.pi)) + rng.uniform(-.45, .45)
            p.straw(part, [x + nx * rng.uniform(-.6, .4), h * rng.uniform(.88, 1.0), z + nz * rng.uniform(-.6, .4)], a,
                    rng.uniform(-.15, .2), rng.uniform(2.5, 4.0), s0=rng.randrange(8))


def rim_run(p, part, rng, line, lo, hi, h, n, tex0=0):
    """n thin grass bundles overlapping along a stretch of rim, staggered in two rows (the inner row a little higher)."""
    step = (hi - lo) / n
    for k in range(n):
        s0 = lo if k == 0 else lo + k * step - rng.uniform(.6, 1.2)
        s1 = hi if k == n - 1 else lo + (k + 1) * step + rng.uniform(.6, 1.2)
        inner_row = k % 2 == 0
        bundle(p, part, line, s0, s1, h * (rng.uniform(.95, 1.08) if inner_row else rng.uniform(.8, .95)),
               BED_IN * rng.uniform(.5, .62), BED_OUT * rng.uniform(.5, .62), inner_foot=PAD_Y,
               shift=(-.95 if inner_row else .95) + rng.uniform(-.25, .25), droop=rng.uniform(-.25, .25), tex0=tex0 + 5 * k,
               old=rng.random() < .33)
        if n > 2 and k < n - 1:                                       # a second, offset bundle under each joint
            bundle(p, part, line, lo + (k + .6) * step, lo + (k + 1.4) * step, h * rng.uniform(.75, .9),
                   BED_IN * .5, BED_OUT * .5, inner_foot=PAD_Y, shift=(.95 if inner_row else -.95), droop=0.0, tex0=tex0 + 3,
                   old=rng.random() < .33)


def forest_burrow():
    """Shared family bed cell. Unit 1: leaf pad over the whole cell (seamless with neighbouring cells). Units 2..5 = side
    mids N/E/S/W (|a| <= 4), 6..9 = corner arcs NE/SE/SW/NW (both adjoining sides exposed), 10..17 = side ends where the edge
    runs on over the next bed: N-west N-east E-north E-south S-east S-west W-south W-north. Each piece is two to four thin,
    straight tied sheaves (flat cut ends, a dark tie, a third of them weathered grey-brown) in two staggered rows, reaching a little past its ends, so joined pieces overlap into one lumpy rim round the whole bed."""
    p = WildProp('forest_burrow')
    rng = rng_of('forest_burrow')
    pad = p.mat('leaf_pad', 1)
    p.planar(pad, [[-8, PAD_Y, -8], [-8, PAD_Y, 8], [8, PAD_Y, 8], [8, PAD_Y, -8]], [[0, 1, 0]] * 4, 'litter', (8, 8))
    p.patch(pad, (-3.5, 2.8), (2.4, 1.8), PAD_Y + .03, 'moss', rng)
    lining = p.mat('lining', 1)
    p.leaf(lining, 'leaf_green', (2.6, PAD_Y + .05, -2.0), 2.2, fold=.25, curl=.05)
    p.leaf(lining, 'leaf_dry', (-2.4, PAD_Y + .09, -3.2), 4.1, fold=.2, curl=.05)
    p.leaf(lining, 'leaf_old', (3.4, PAD_Y + .07, 3.6), .9, fold=.15, curl=.04)
    for side in range(4):
        part = p.mat(f'rim_side_{"nesw"[side]}', 2 + side)
        line = side_line(side)
        h = SIDE_H[side]
        rim_run(p, part, rng, line, 3.3, 12.7, h, 4)                       # a = -4.7 .. 4.7
        if side != 2:
            x0, z0, nx, nz = line.at(5.0)
            x1, z1, _, _ = line.at(11.5)
            o = rng.uniform(-.3, .8)
            p.stick(part, [x0 + nx * o, h - .2, z0 + nz * o], [x1 + nx * (o + .5), h - .5, z1 + nz * (o + .5)], .42, s0=rng.randrange(16))
        tufts(p, part, rng, line, 4.0, 12.0, h, 6)
        if side in (1, 3):                                                # a green lining leaf spilling over the rim
            x, z, nx, nz = line.at(8.0 + (2 if side == 1 else -2))
            p.leaf(part, 'leaf_slim', (x - nx * 1.6, h * .8, z - nz * 1.6), math.atan2(nx, nz), fold=.35, tilt=-.42)
    for unit, corner in zip((6, 7, 8, 9), ((1, -1), (1, 1), (-1, 1), (-1, -1))):
        part = p.mat('rim_corner_' + {(1, -1): 'ne', (1, 1): 'se', (-1, 1): 'sw', (-1, -1): 'nw'}[corner], unit)
        line, ha, hb = arc_line(*corner)
        rim_run(p, part, rng, line, -.4, line.length + .4, (ha + hb) / 2 + .2, 3, tex0=12)
        x, z, nx, nz = line.at(line.length / 2)
        p.ball(part, [x + nx * 2.2, .5, z + nz * 2.2], 1.1, 'moss', rng, seg=6, squash=.55)
        tufts(p, part, rng, line, 1.0, line.length - 1.0, ha, 3)
    for unit, side, sign in ((10, 0, 1), (11, 0, -1), (12, 1, 1), (13, 1, -1), (14, 2, 1), (15, 2, -1), (16, 3, 1), (17, 3, -1)):
        part = p.mat(f'rim_end_{unit}', unit)
        line = side_line(side)
        lo, hi = (11.4, 16.6) if sign > 0 else (-.6, 4.6)                  # a = 3.4 .. 8.6 or -8.6 .. -3.4
        rim_run(p, part, rng, line, lo, hi, SIDE_H[side], 2, tex0=3)
        tufts(p, part, rng, line, lo + 1, hi - 1, SIDE_H[side], 3)
    return p


def oval_line(rx, rz, n=32):
    pts = []
    for i in range(n):
        a = math.tau * i / n
        nx, nz = math.cos(a) / rx, math.sin(a) / rz
        d = math.hypot(nx, nz)
        pts.append((math.cos(a) * rx, math.sin(a) * rz, nx / d, nz / d))
    return PathLine(pts, closed=True)


def front_drop(x, z):
    """0 at the back, 1 at the front (+z, the entrance side)."""
    a = math.atan2(z, x)
    return smooth((math.sin(a) + .25) * 1.2)


def ring_of_bundles(p, part, rng, line, count, h_back, h_front, inner, outer, foot, overlap=1.1, gaps=()):
    step = line.length / count
    start = rng.uniform(0, step)
    for k in range(count):
        if k in gaps:
            continue
        s0 = start + k * step - overlap * rng.uniform(.6, 1.0)
        s1 = start + (k + 1) * step + overlap * rng.uniform(.6, 1.0)
        x, z, _, _ = line.at((s0 + s1) / 2)
        h = h_back - (h_back - h_front) * front_drop(x, z)
        inner_row = k % 2 == 0
        bundle(p, part, line, s0, s1, h * (rng.uniform(.95, 1.1) if inner_row else rng.uniform(.8, .95)),
               inner * rng.uniform(.5, .62), outer * rng.uniform(.5, .62), inner_foot=foot,
               shift=(-.55 if inner_row else .55) * (inner + outer) / 2 + rng.uniform(-.2, .2), droop=rng.uniform(-.25, .25),
               tex0=rng.randrange(16), old=rng.random() < .33)


def personal_leaf_nest():
    """A growing child's own bed, oval hollow about 10 x 8. Stage 1 (unit 1): a scrap of leaf litter, a few big leaves and
    fallen stems on the spot. Stage 2 (unit 2): a low, thin ring of loose grass bundles with a gap round a wider pad. Stage 3
    (unit 3): the ring tied up into a bowl of thin bundles in two staggered rows (back highest, front low), twigs worked in, stems poking out and
    a leaf hanging over the rim."""
    p = WildProp('personal_leaf_nest')
    rng = rng_of('personal_leaf_nest')
    RX, RZ = 6.6, 5.5
    s1 = p.mat('scattered_leaves', 1)
    p.patch(s1, (.4, -.3), (3.8, 3.0), .1, 'litter', rng, seg=10, wobble=.3)
    p.leaf(s1, 'leaf_dry', (-2.2, .14, .6), .5, fold=.2, curl=.08)
    p.leaf(s1, 'leaf_green', (2.4, .16, -.8), 2.6, fold=.25, curl=.06)
    p.leaf(s1, 'leaf_old', (.6, .18, 2.6), 4.4, fold=.15, curl=.05)
    for k in range(3):                                             # a few fallen stems
        p.straw(s1, [-2.0 + k * .7, .25, -2.4 + k * .3], .4 + k * .5, 0.0, 3.0 + k * .4, s0=k * 5)
    s2 = p.mat('rough_ring', 2)
    p.patch(s2, (0, 0), (RX - .4, RZ - .4), .12, 'litter_dry', rng, seg=14, wobble=.06)
    ring_of_bundles(p, s2, rng, oval_line(RX - .5, RZ - .5), 12, 1.6, 1.0, 1.3, 1.2, .1, overlap=.4, gaps=(7, 8))
    s3 = p.mat('woven_bowl', 3)
    line = oval_line(RX, RZ)
    ring_of_bundles(p, s3, rng, line, 16, 3.4, 1.9, 2.0, 2.1, .14)
    for f0, f1, o in ((.05, .2, .3), (.55, .68, .2)):
        x0, z0, nx0, nz0 = line.at(line.length * f0)
        x1, z1, nx1, nz1 = line.at(line.length * f1)
        h0 = 3.4 - 1.5 * front_drop(x0, z0)
        h1 = 3.4 - 1.5 * front_drop(x1, z1)
        p.stick(s3, [x0 + nx0 * o, h0 - .2, z0 + nz0 * o], [x1 + nx1 * (o + .3), h1 - .1, z1 + nz1 * (o + .3)], .38, s0=int(f0 * 40))
    for _ in range(15):
        x, z, nx, nz = line.at(rng.uniform(0, line.length))
        h = 3.4 - 1.5 * front_drop(x, z)
        t = rng.uniform(.3, .9)
        p.straw(s3, [x + nx * t, h * rng.uniform(.55, .8), z + nz * t], math.atan2(nz, nx) + rng.uniform(-.9, .9), rng.uniform(.05, .5),
                rng.uniform(1.5, 2.8), s0=rng.randrange(16))
    x, z, nx, nz = line.at(line.length * .82)
    p.leaf(s3, 'leaf_slim', (x - nx * 1.6, 3.4 * .75, z - nz * 1.6), math.atan2(nx, nz), fold=.35, tilt=-.45)
    return p


def bedding_bundle():
    """One carried portion: a small tied sheaf of dry grass (a hay bale in miniature, strands along its length) with a green
    grass tie, a leaf tucked under the tie and splayed straw at both ends."""
    p = WildProp('bedding_bundle')
    part = p.mat('sheaf')
    p.stick(part, [-2.5, 1.3, .35], [2.5, 1.3, -.35], 1.25, side='weave', end='hay', hw=1.1, roll=.12)
    p.stick(part, [-.45, 1.3, .06], [.45, 1.3, -.06], 1.4, side='blades', end='blades', hw=1.25, roll=.12)
    p.leaf(part, 'leaf_slim', (.2, 2.6, .1), 1.6, fold=.35, curl=.1, scale=.7)
    for x, z, a in ((-2.5, .35, math.pi), (2.5, -.35, 0.0)):        # splayed stems at both cut ends
        for k, (dy, da) in enumerate(((.5, .35), (-.3, -.4), (.1, .05))):
            p.straw(part, [x, 1.3 + dy, z], a + da, .1 * k - .05, 1.4 + .3 * k, s0=4 * k)
    return p


# ---- 2x2 wild family nest -------------------------------------------------------------------------------------------
# 31 units across (outline within 15.5 of the centre), entrance on +z. Collision (life/CareBlock.largeNestShape wild):
# floor 0.5, rim 2.6 outside a 14.25 rounded square, the front open to the floor across |x| < 7, then 1.5 to 10 and 2.3 to
# 12. The woven roll follows that profile and stays within 3.0 high.
LARGE_CREST = 14.35


def squircle(theta, r):
    c, s = math.cos(theta), math.sin(theta)
    k = (c ** 4 + s ** 4) ** -.25
    x, z = r * c * k, r * s * k
    nx, nz = x ** 3, z ** 3
    d = math.hypot(nx, nz) or 1
    return x, z, nx / d, nz / d


def large_rim_height(x, z):
    if z <= 0:
        return 2.72
    a = abs(x)
    pts = ((7.0, 1.05), (8.0, 1.45), (9.6, 1.6), (10.8, 2.25), (12.4, 2.72))
    if a <= pts[0][0]:
        return pts[0][1]
    for (a0, h0), (a1, h1) in zip(pts, pts[1:]):
        if a <= a1:
            return h0 + (h1 - h0) * smooth((a - a0) / (a1 - a0))
    return 2.72


def large_wild_nest():
    """Wild family bedding on a 2x2 footprint: a woven dry-grass roll round three sides and the front shoulders, a pad of
    fallen leaves at the collision floor with big green and dry leaves, moss and loose hay laid in, square twigs worked
    into the roll and dry tufts poking out of it. Open across the +z front."""
    p = WildProp('large_wild_nest')
    rng = rng_of('large_wild_nest')
    FLOOR = .55
    floor = p.mat('leaf_floor')
    ring = [squircle(math.tau * k / 40, 14.6)[:2] for k in range(40)]
    centre = [0.0, FLOOR, 0.0]
    for k in range(40):
        a, b = ring[k], ring[(k + 1) % 40]
        p.planar(floor, [centre, [b[0], FLOOR, b[1]], [a[0], FLOOR, a[1]]], [[0, 1, 0]] * 3, 'litter')
        if a[1] > 10 and b[1] > 10:                                # the floor edge shows across the open front
            n = [(a[1] + b[1]) / 2 / 15, 0, 0]
            nrm = [0, 0, 1]
            p.face(floor, [[a[0], 0, a[1]], [b[0], 0, b[1]], [b[0], FLOOR, b[1]], [a[0], FLOOR, a[1]]], [nrm] * 4, 'litter_dry',
                   [(a[0], 8), (b[0], 8), (b[0], 8 - FLOOR), (a[0], 8 - FLOOR)])
    lining = p.mat('lining')
    for (x, z), tile, r in (((-6.5, -6.0), 'moss', (3.6, 2.6)), ((6.5, 5.0), 'hay', (3.4, 2.4))):
        p.patch(lining, (x, z), r, FLOOR + .03, tile, rng, seg=10)
    for name, x, z, yaw in (('leaf_green', -2.0, -1.5, .7), ('leaf_green', 3.0, 1.0, 2.2), ('leaf_dry', 5.5, -6.5, 4.0),
                            ('leaf_slim', -6.5, 5.0, 5.4)):
        p.leaf(lining, name, (x, FLOOR + .06 + rng.uniform(0, .05), z), yaw, fold=.22, curl=.05)
    # roll: from the front-right end round the back to the front-left end
    start = math.atan2(1, 0)                                       # +z
    pts = []
    for k in range(361):
        th = start + math.radians(k)
        x, z, nx, nz = squircle(th, LARGE_CREST)
        pts.append((x, z, nx, nz))
    pts = [q for q in pts if not (q[1] > 0 and abs(q[0]) < 7.0)]
    # order: begin just right of the gap (x > 0), go clockwise seen from above (toward -z), end left of the gap
    i0 = min(range(len(pts)), key=lambda i: (pts[i][0] < 0, abs(pts[i][0] - 7) if pts[i][1] > 0 else 99))
    pts = pts[i0:] + pts[:i0]
    if pts[1][1] > pts[0][1]:
        pts = pts[::-1]
    line = PathLine(pts)
    rim = p.mat('woven_rim')
    count = 26                                                         # thin bundles in two staggered rows
    step = line.length / count
    for k in range(count):
        s0 = max(0.0, k * step - rng.uniform(.8, 1.4))
        s1 = min(line.length, (k + 1) * step + rng.uniform(.8, 1.4))
        x, z, _, _ = line.at((s0 + s1) / 2)
        droop = rng.uniform(-.2, .2)
        inner_row = k % 2 == 0
        h = min(large_rim_height(x, z) * (rng.uniform(.96, 1.06) if inner_row else rng.uniform(.8, .92)), 2.75 / (1 + abs(droop) / 2))
        bundle(p, rim, line, s0, s1, h, 1.7 * rng.uniform(.9, 1.1), .9, inner_foot=FLOOR,
               shift=(-1.1 if inner_row else -.2) + rng.uniform(-.15, .05), droop=droop, tex0=rng.randrange(16),
               old=rng.random() < .33)
    for k in range(27):                                                # loose stems crossing the rim top and the inner slope
        x, z, nx, nz = line.at(line.length * (k + rng.uniform(.1, .9)) / 27)
        tx, tz = -nz, nx
        if k % 3 == 2:
            a, base = math.atan2(-nz, -nx) + rng.uniform(-.8, .8), [x - nx * 1.2, 1.4, z - nz * 1.2]
        else:
            a, base = math.atan2(tz, tx) + rng.choice((0, math.pi)) + rng.uniform(-.4, .4), [x - nx * rng.uniform(.4, 1.0), 2.4, z - nz * rng.uniform(.4, 1.0)]
        p.straw(rim, base, a, rng.uniform(-.12, .08), rng.uniform(2.2, 3.4), s0=rng.randrange(8))
    for f0, f1, o in ((.12, .2, -.4), (.36, .45, -.3), (.6, .69, -.5), (.82, .9, -.3)):     # twigs worked into the rim
        x0, z0, nx0, nz0 = line.at(line.length * f0)
        x1, z1, nx1, nz1 = line.at(line.length * f1)
        h0, h1 = large_rim_height(x0, z0), large_rim_height(x1, z1)
        p.stick(rim, [x0 + nx0 * o, h0 - .55, z0 + nz0 * o], [x1 + nx1 * o, h1 - .45, z1 + nz1 * o], .4, s0=int(f0 * 50))
    for f, name in ((.22, 'leaf_green'), (.64, 'leaf_green')):         # lining leaves spilling over the inner slope
        x, z, nx, nz = line.at(line.length * f)
        p.leaf(rim, name, (x - nx * 6.4, .95, z - nz * 6.4), math.atan2(-nx, -nz), fold=.2, tilt=-.1)
    for f in (.16, .48, .8):                                           # moss clumps at the outer foot
        x, z, nx, nz = line.at(line.length * f)
        p.ball(rim, [x - nx * .1, .45, z - nz * .1], .9, 'moss', rng, seg=6, squash=.5)
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


WILD = {'forest_burrow': forest_burrow, 'personal_leaf_nest': personal_leaf_nest, 'bedding_bundle': bedding_bundle,
        'large_wild_nest': large_wild_nest, 'predator_nest': predator_nest}
