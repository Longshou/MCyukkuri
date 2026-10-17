"""Nests: the shop-sold round pet cushion bed, and the predator species' rough woven twig bowl."""
import math
from kit import Prop, Band, Planar, colour, fill, paint, deterministic, unit

C = lambda ramp, step: (ramp, step)
QUILT_AT = (16, 0)          # 10x10 texel quilted cushion top (1 texel per unit)


def yukkuri_nest():
    """Round pet cushion: thick rolled strawberry rim (4 high), recessed quilted cream centre with a seam ring."""
    p = Prop('yukkuri_nest', wide=True)
    # quilted cushion top decal: diamond stitching, one tufted button
    ox, oy = QUILT_AT
    fill(p.img, ox, oy, 10, 10, C('paper', 2))
    for y in range(10):
        for x in range(10):
            if (x + y) % 4 == 0 or (x - y) % 4 == 0:
                p.img.putpixel((ox + x, oy + y), colour('paper', 1) + (255,))
    for (x, y) in ((4, 4), (5, 4), (4, 5), (5, 5)):
        p.img.putpixel((ox + x, oy + y), colour('berry', 2) + (255,))
    top = Planar(ox + 5, oy + 5, ax=0, ay=2, sx=1, sy=1)
    cushion = p.part('quilted_cushion')
    profile = [(0, 0), (5, 0), (5, 1.5), (4.5, 2), (3, 2.25), (1.5, 2.5), (0, 2.5)]
    specs = [C('paper', 0), C('paper', 1), C('paper', 2), top, top, top]
    for i in range(len(profile) - 1):
        p.lathe(cushion, profile[i:i + 2], specs[i], seg=16, smooth='both' if i >= 3 else 'around', grid=.5)
    rim = p.part('rolled_rim')
    # one ramp step paler than before so the rim sits with the cream and mint pet goods
    tone = [C('berry', 1), C('berry', 2), C('berry', 3), C('berry', 3), C('berry', 2), C('berry', 2)]
    p.torus(rim, [0, 2, 0], 5.5, 2, seg=16, tube=6, spec=Band(lambda i, j, c: tone[i]), grid=.5, smooth='both')
    seam = p.part('seam_ring')
    p.torus(seam, [0, 2.0, 0], 3.75, .5, seg=16, tube=3, spec=C('berry', 1), grid=.25, smooth='both')
    piping = p.part('cream_piping')
    p.torus(piping, [0, 3.75, 0], 5.5, .5, seg=16, tube=3, spec=C('paper', 3), grid=.25, smooth='both')
    return p


def predator_nest():
    """Wild woven twig bowl a yukkuri can sit in: three ordered stick rings (rim about 5 high), five protruding stick ends,
    dark bark, a dry-grass lining cup with dry leaves and a few pale feathers."""
    p = Prop('predator_nest', wide=True)
    rng = deterministic(7302)
    lining = p.part('dry_grass_lining')
    p.lathe(lining, [(0, .5), (5.5, .5), (5.5, 1.0), (6.0, 2.0), (6.0, 3.0), (5.0, 2.5), (4.0, 1.5), (0, 1.5)],
            Band(lambda i, j, c: C('straw', 0) if i < 2 else C('straw', 1) if i < 4 else C('straw', 2) if i < 5 else C('straw', 1)), seg=12, grid=.5, smooth='around')
    twigs = p.part('woven_twigs')
    count = 9
    for layer in range(3):
        y = 1.3 + layer * 1.3
        for k in range(count):
            a0 = math.tau * k / count + layer * math.tau / (2 * count) + rng.uniform(-.05, .05)
            a1 = a0 + math.tau / count * 1.35
            R = 6.3 + layer * .15 + rng.uniform(-.1, .2)
            lift = .35 * math.sin(a0 * 3) if layer == 2 else 0
            A = [R * math.cos(a0), y + lift, R * math.sin(a0)]
            B = [R * math.cos(a1), y + rng.uniform(-.2, .3) + lift, R * math.sin(a1)]
            p.rod(twigs, A, B, .6, .55, seg=4, spec=C('wood', (0, 1, 1, 2)[(k + layer) % 4]), caps=(False, False), grid=.5, phase=.4)
    sticks = p.part('protruding_sticks')
    for k, a in enumerate((.35, 1.6, 2.9, 4.2, 5.3)):
        y = 2.0 + (k % 3) * .9
        inner = [5.6 * math.cos(a), y, 5.6 * math.sin(a)]
        outer = [(7.6 + (k % 2) * .3) * math.cos(a + .06), y + .5 + (k % 2) * .5, (7.6 + (k % 2) * .3) * math.sin(a + .06)]
        p.rod(sticks, inner, outer, .6, .5, seg=4, spec=C('wood', 1 + k % 2), caps=(False, True), grid=.5)
    leaves = p.part('dry_leaves')
    for k in range(8):
        a = rng.uniform(0, math.tau)
        rr = rng.uniform(.4, 4.2)
        y = 1.62 + .1 * k % 3 * .1
        ang = rng.uniform(0, math.pi)
        ln, wd = 1.7, .7
        cx, cz = rr * math.cos(a), rr * math.sin(a)
        dx, dz = math.cos(ang) * ln, math.sin(ang) * ln
        nx, nz = -math.sin(ang) * wd, math.cos(ang) * wd
        pts = [[cx - dx, y, cz - dz], [cx + nx, y + .15, cz + nz], [cx + dx, y + .1, cz + dz], [cx - nx, y + .15, cz - nz]]
        p.quad(leaves, pts, C('pellet', 1 + k % 2) if k % 2 else C('straw', 1), grid=.25, double=True, normal=[0, 1, 0])
    feathers = p.part('pale_feathers')
    for k, (fx, fz, fa) in enumerate(((-1.6, 1.4, .5), (1.9, -1.2, 2.3), (-.3, -2.4, 4.0))):
        y = 1.85 + k * .04
        dx, dz = math.cos(fa) * 1.7, math.sin(fa) * 1.7
        nx, nz = -math.sin(fa) * .5, math.cos(fa) * .5
        tip, base = [fx + dx, y + .1, fz + dz], [fx - dx, y, fz - dz]
        left, right = [fx + nx, y + .12, fz + nz], [fx - nx, y + .12, fz - nz]
        p.tri(feathers, [base, left, tip], [[0, 1, 0]] * 3, C('paper', 3), None)
        p.tri(feathers, [base, tip, right], [[0, 1, 0]] * 3, C('paper', 2), None)
        p.tri(feathers, [base, tip, left], [[0, -1, 0]] * 3, C('paper', 1), None)
        p.tri(feathers, [base, right, tip], [[0, -1, 0]] * 3, C('paper', 1), None)
    return p
