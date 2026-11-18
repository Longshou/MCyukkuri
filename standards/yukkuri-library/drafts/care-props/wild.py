"""Self-built nests of wild yukkuri.

Ordinary wild yukkuri (forest_burrow, personal_leaf_nest, bedding_bundle) use the hand-drawn wild_kit atlas: loose dry
grass pressed down in layers, a leaf-litter pad, big leaves, square twigs and stems, every texture at 1 texel per unit.
The 2x2 wild bedding, the predator twig nest and the forage store are block-style props in block-props/nests.py.
Units are 1/16 block, origin at the centre of the bed, ground Y = 0, +Z is the south side.

Real body sizes: standard adult 12.8 wide x 10.7 deep (individual target up to 1.2x, deibu 1.5x), children grow from 0.2 to
0.8. Every bed keeps a level hollow wide enough for an adult: the shared family bed keeps its whole 16 x 16 cell as the
hollow (the rim's inner foot 7.8 from the centre) and builds its rim in the block space around it (exposed sides only);
the personal bed of a growing child, which it keeps as a subadult and adult, has an oval hollow about 15.6 x 14.4.

Render units follow client/ShelterRenderer: forest_burrow 1 = bedding, 2..5 = side mids north / east / south / west,
6..9 = corner arcs NE / SE / SW / NW, 10..17 = side ends (see forest_burrow); personal_leaf_nest 1 = a few leaves,
2 = rough ring, 3 = finished nest; bedding_bundle is unit 0.
"""
import math
from wild_kit import WildProp, PathLine, pressed_rim, wad, rng_of, smooth

# =====================================================================================================================
# Self-built nests of ordinary wild yukkuri (wild_kit atlas, hand drawn). A bird's-nest bowl: wild yukkuri have no hands
# and only carry in the mouth, push, press and pile (user ruling), so the rim is mouthfuls of loose dry grass pushed into a
# ring and pressed down in layers, stems lying round the bed; a floor of fallen leaves and straw, a few big leaves laid in
# as lining, a couple of square twigs pushed into the rim and loose stems poking out. Nothing is tied or bound. Wiki: dry-grass bowl nests lined with green leaves (EH19, M04),
# a hay bed wider than the sleeping circle (N103), leaves and twigs gathered in autumn (N38), moss layers (N23).
# Arrangement, sizes and the moss / twig mix are project design.

# Side frame: a = along the edge, t = outward from the bed centre. Sides 0..3 = north, east, south, west.
def to_world(side, a, t):
    return ((-a, -t), (t, -a), (a, t), (-t, a))[side]


SIDE_H = (5.0, 4.0, 2.4, 4.0)          # crest height by side: back (north) highest, front (south, entrance side) lowest
CREST = 10.3                           # crest line distance from the bed centre
BED_IN, BED_OUT = 2.5, 2.8             # rim half widths at the foot: inner foot near t 7.8, outer foot near t 13.1
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
            base = math.atan2(tz, tx) + rng.choice((0, math.pi))
            dev = rng.uniform(0, .35)                       # along the crest, swung outward, never into the hollow
            a = max(base + dev, base - dev, key=lambda q: math.cos(q) * nx + math.sin(q) * nz)
            o = rng.uniform(-.1, .5)
            p.straw(part, [x + nx * o, h * rng.uniform(.88, 1.0), z + nz * o], a,
                    rng.uniform(-.15, .2), rng.uniform(2.5, 4.0), s0=rng.randrange(8))


def forest_burrow():
    """Shared family bed cell. Unit 1: leaf pad over the whole cell (seamless with neighbouring cells). Units 2..5 = side
    mids N/E/S/W (|a| <= 4), 6..9 = corner arcs NE/SE/SW/NW (both adjoining sides exposed), 10..17 = side ends where the edge
    runs on over the next bed: N-west N-east E-north E-south S-east S-west W-south W-north. Each piece is loose dry grass
    carried in by mouth and pressed down in layers (pressed_rim), reaching a little past its ends, so joined pieces
    overlap into one lumpy rim round the whole bed; nothing is tied."""
    p = WildProp('forest_burrow')
    rng = rng_of('forest_burrow')
    pad = p.mat('leaf_pad', 1)
    p.planar(pad, [[-8, PAD_Y, -8], [-8, PAD_Y, 8], [8, PAD_Y, 8], [8, PAD_Y, -8]], [[0, 1, 0]] * 4, 'litter', (8, 8))
    p.patch(pad, (-3.5, 2.8), (2.4, 1.8), PAD_Y + .03, 'moss', rng)
    lining = p.mat('lining', 1)
    p.leaf(lining, 'leaf_green', (2.6, PAD_Y + .03, -2.0), 2.2, fold=.06, curl=.02)      # pressed flat under the sleepers
    p.leaf(lining, 'leaf_dry', (-2.4, PAD_Y + .05, -3.2), 4.1, fold=.05, curl=.02)
    p.leaf(lining, 'leaf_old', (3.4, PAD_Y + .04, 3.6), .9, fold=.04, curl=.02)
    for side in range(4):
        part = p.mat(f'rim_side_{"nesw"[side]}', 2 + side)
        line = side_line(side)
        h = SIDE_H[side]
        pressed_rim(p, part, rng, line, 4.0, 12.0, h, BED_IN, BED_OUT, PAD_Y, overlap=.8)   # a = -4 .. 4
        if side != 2:                                                     # a twig pushed in at the crest, leaning outward
            x0, z0, nx, nz = line.at(rng.uniform(6.0, 10.0))
            j = rng.uniform(-1.0, 1.0)
            p.stick(part, [x0 + nx * .3, h - .15, z0 + nz * .3], [x0 + nx * 3.4 - nz * j, h * .4, z0 + nz * 3.4 + nx * j], .42,
                    s0=rng.randrange(16))
        tufts(p, part, rng, line, 4.0, 12.0, h, 6)
        if side in (1, 3):                                                # a green lining leaf lying over the rim crest, clear of the hollow
            x, z, nx, nz = line.at(8.0 + (2 if side == 1 else -2))
            p.leaf(part, 'leaf_slim', (x + nx * .4, h * .85, z + nz * .4), math.atan2(nx, nz) + math.pi / 2, fold=.35, tilt=-.2)
    for unit, corner in zip((6, 7, 8, 9), ((1, -1), (1, 1), (-1, 1), (-1, -1))):
        part = p.mat('rim_corner_' + {(1, -1): 'ne', (1, 1): 'se', (-1, 1): 'sw', (-1, -1): 'nw'}[corner], unit)
        line, ha, hb = arc_line(*corner)
        pressed_rim(p, part, rng, line, 0.0, line.length, (ha + hb) / 2 + .2, BED_IN, BED_OUT, PAD_Y, overlap=.5)
        x, z, nx, nz = line.at(line.length / 2)
        p.ball(part, [x + nx * 2.2, .5, z + nz * 2.2], 1.1, 'moss', rng, seg=6, squash=.55)
        tufts(p, part, rng, line, 1.0, line.length - 1.0, ha, 3)
    for unit, side, sign in ((10, 0, 1), (11, 0, -1), (12, 1, 1), (13, 1, -1), (14, 2, 1), (15, 2, -1), (16, 3, 1), (17, 3, -1)):
        part = p.mat(f'rim_end_{unit}', unit)
        line = side_line(side)
        lo, hi = (12.0, 16.0) if sign > 0 else (0.0, 4.0)                  # a = 4 .. 8 or -8 .. -4
        pressed_rim(p, part, rng, line, lo, hi, SIDE_H[side], BED_IN, BED_OUT, PAD_Y, overlap=.7)
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


def pressed_ring(p, part, rng, line, count, h_back, h_front, inner, outer, foot, layers=3, gaps=()):
    """A closed rim of pressed loose grass in `count` stretches, each as high as its place between back and front."""
    step = line.length / count
    start = rng.uniform(0, step)
    for k in range(count):
        if k in gaps:
            continue
        s0, s1 = start + k * step, start + (k + 1) * step
        x, z, _, _ = line.at((s0 + s1) / 2)
        h = h_back - (h_back - h_front) * front_drop(x, z)
        pressed_rim(p, part, rng, line, s0, s1, h, inner, outer, foot, layers=layers, overlap=.6)


def personal_leaf_nest():
    """A growing child's own bed (kept as a subadult and adult), oval hollow about 15.6 x 14.4. Stage 1 (unit 1): a scrap of leaf litter, a few big leaves and
    fallen stems on the spot. Stage 2 (unit 2): a low, thin ring of pressed loose grass with gaps round a wider pad. Stage 3
    (unit 3): more grass pushed on and pressed in layers into a bowl (back highest, front low), twigs pushed in, stems poking out and
    a leaf hanging over the rim."""
    p = WildProp('personal_leaf_nest')
    rng = rng_of('personal_leaf_nest')
    RX, RZ = 9.8, 9.2
    s1 = p.mat('scattered_leaves', 1)
    p.patch(s1, (.4, -.3), (5.4, 4.4), .1, 'litter', rng, seg=12, wobble=.3)
    p.leaf(s1, 'leaf_dry', (-3.2, .12, .8), .5, fold=.06, curl=.02)
    p.leaf(s1, 'leaf_green', (3.4, .13, -1.2), 2.6, fold=.06, curl=.02)
    p.leaf(s1, 'leaf_old', (.8, .14, 3.8), 4.4, fold=.05, curl=.02)
    for k in range(4):                                             # a few fallen stems
        p.straw(s1, [-3.0 + k * .9, .2, -3.4 + k * .4], .4 + k * .5, 0.0, 3.0 + k * .4, s0=k * 5)
    s2 = p.mat('rough_ring', 2)
    p.patch(s2, (0, 0), (RX - .4, RZ - .4), .12, 'litter_dry', rng, seg=14, wobble=.06)
    pressed_ring(p, s2, rng, oval_line(RX - .5, RZ - .5), 8, 1.6, 1.0, 1.3, 1.2, .1, layers=1, gaps=(5,))
    s3 = p.mat('pressed_bowl', 3)
    line = oval_line(RX, RZ)
    pressed_ring(p, s3, rng, line, 9, 3.4, 1.9, 2.0, 2.1, .14)
    for f0, f1, o in ((.05, .2, .3), (.55, .68, .2)):
        x0, z0, nx0, nz0 = line.at(line.length * f0)
        x1, z1, nx1, nz1 = line.at(line.length * f1)
        h0 = 3.4 - 1.5 * front_drop(x0, z0)
        h1 = 3.4 - 1.5 * front_drop(x1, z1)
        p.stick(s3, [x0 + nx0 * o, h0 - .2, z0 + nz0 * o], [x1 + nx1 * (o + .3), h1 - .1, z1 + nz1 * (o + .3)], .38, s0=int(f0 * 40))
    for _ in range(22):
        x, z, nx, nz = line.at(rng.uniform(0, line.length))
        h = 3.4 - 1.5 * front_drop(x, z)
        t = rng.uniform(.3, .9)
        p.straw(s3, [x + nx * t, h * rng.uniform(.55, .8), z + nz * t], math.atan2(nz, nx) + rng.uniform(-.9, .9), rng.uniform(.05, .5),
                rng.uniform(1.5, 2.8), s0=rng.randrange(16))
    x, z, nx, nz = line.at(line.length * .82)
    p.leaf(s3, 'leaf_slim', (x + nx * .3, 3.4 * .8, z + nz * .3), math.atan2(nx, nz) + math.pi / 2, fold=.35, tilt=-.2)
    return p


def bedding_bundle():
    """One carried portion: a small loose mouthful of dry grass and leaves as it was set down - two pressed wads, a green
    leaf caught in them and stems sticking out every way. Nothing is tied."""
    p = WildProp('bedding_bundle')
    rng = rng_of('bedding_bundle')
    part = p.mat('mouthful')
    line = PathLine([(-2.6, .4, -.15, .99), (2.6, -.4, -.15, .99)])
    wad(p, part, line, 0.0, line.length, 0.0, 1.9, 1.6, 1.5, droop=.15, tex0=3)
    wad(p, part, line, .8, line.length - 1.2, .9, 2.7, 1.0, 1.0, shift=.3, droop=-.2, tex0=9, old=True)
    p.leaf(part, 'leaf_slim', (.3, 2.2, .3), 1.9, fold=.35, curl=.1, scale=.7)
    for k in range(9):
        a = rng.uniform(0, math.tau)
        p.straw(part, [math.cos(a) * 1.2, rng.uniform(.6, 2.2), math.sin(a) * .6], a + rng.uniform(-.6, .6), rng.uniform(-.2, .5),
                rng.uniform(1.6, 2.8), s0=k)
    return p


# ---- 2x2 wild family nest -------------------------------------------------------------------------------------------
# 32 units across (two blocks, centred on the bed block; outline within 16 of the centre), entrance on +z. Collision
# (life/CareBlock.largeWildShape): floor 0.5, rim 2.6 outside a 14.75 rounded square, the front open to the floor across
# |x| < 7, then 1.5 to 10 and 2.3 to 12. The pressed grass rim follows that profile and stays within 3.0 high; two adults
# up to about 1.1x lie side by side on the leaf floor with four newborns.
WILD = {'forest_burrow': forest_burrow, 'personal_leaf_nest': personal_leaf_nest, 'bedding_bundle': bedding_bundle}
