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
    tone = [C('berry', 0), C('berry', 1), C('berry', 2), C('berry', 3), C('berry', 2), C('berry', 1)]
    p.torus(rim, [0, 2, 0], 5.5, 2, seg=16, tube=6, spec=Band(lambda i, j, c: tone[i]), grid=.5, smooth='both')
    seam = p.part('seam_ring')
    p.torus(seam, [0, 2.0, 0], 3.75, .5, seg=16, tube=3, spec=C('berry', 0), grid=.25, smooth='both')
    piping = p.part('cream_piping')
    p.torus(piping, [0, 3.75, 0], 5.5, .5, seg=16, tube=3, spec=C('paper', 3), grid=.25, smooth='both')
    return p


def predator_nest():
    """Wild woven twig bowl: higher irregular rim, protruding stick ends, dark bark, a few dry leaves."""
    p = Prop('predator_nest', wide=True)
    rng = deterministic(7301)
    floor = p.part('dry_grass_floor')
    p.lathe(floor, [(0, .5), (6, .5), (6.5, 1.5), (0, 1.5)], Band(lambda i, j, c: C('straw', 0) if i == 0 else C('straw', 1) if i == 1 else C('straw', 2)),
            seg=12, grid=.5, smooth='around', caps=(False, False))
    floor['tris'] = [t for t in floor['tris']]          # keep as authored
    twigs = p.part('woven_twigs')
    def bark():
        return C('wood', rng.choice([0, 1, 1, 2]))
    for layer in range(4):
        count = 10 if layer < 3 else 6
        for k in range(count):
            a0 = math.tau * (k + rng.uniform(-.15, .15)) / count + layer * .45
            span = math.tau / count * rng.uniform(1.25, 1.6)
            # the rim rises and falls around the bowl
            lift = (1.4 * max(0, math.sin(a0 * 2 + 1)) + .8 * max(0, math.sin(a0 * 3))) if layer >= 2 else 0
            R = 6.3 + rng.uniform(-.25, .55) + layer * .1
            y = 1.3 + layer * 1.5 + lift + rng.uniform(-.2, .2)
            a1 = a0 + span
            A = [R * math.cos(a0), y, R * math.sin(a0)]
            B = [R * math.cos(a1), y + rng.uniform(-.4, .7), R * math.sin(a1)]
            p.rod(twigs, A, B, .55, .5, seg=4, spec=bark(), caps=(False, False), grid=.5, phase=rng.uniform(0, 1))
    sticks = p.part('protruding_sticks')
    for k in range(9):
        a = math.tau * (k + rng.uniform(-.2, .2)) / 9 + .3
        y = 2.2 + rng.uniform(0, 3.4)
        inner = [5.2 * math.cos(a), y, 5.2 * math.sin(a)]
        tilt = rng.uniform(.0, 1.6)
        reach = 7.1 + rng.uniform(0, .8)
        outer = [reach * math.cos(a + .05), y + tilt, reach * math.sin(a + .05)]
        p.rod(sticks, inner, outer, .6, .5, seg=4, spec=C('wood', 1 + k % 2), caps=(False, True), grid=.5)
        # cut end grain in the light straw step
    ends = p.part('stick_ends')
    for t in sticks['tris']:
        pass
    spikes = p.part('rim_spikes')
    for k in range(5):
        a = math.tau * (k + rng.uniform(-.2, .2)) / 5 + .9
        base = [6.2 * math.cos(a), 4.5 + rng.uniform(0, 1), 6.2 * math.sin(a)]
        tip = [6.4 * math.cos(a + .1), base[1] + rng.uniform(1.8, 2.8), 6.4 * math.sin(a + .1)]
        p.rod(spikes, base, tip, .5, .15, seg=4, spec=C('wood', 0 + k % 2), caps=(False, False), grid=.5)
    leaves = p.part('dry_leaves')
    for k in range(7):
        a = rng.uniform(0, math.tau)
        rr = rng.uniform(.5, 4.5) if k < 4 else rng.uniform(5.5, 6.6)
        y = 1.65 if k < 4 else rng.uniform(2.5, 5.2)
        ang = rng.uniform(0, math.pi)
        ln, wd = 1.8, .75
        cx, cz = rr * math.cos(a), rr * math.sin(a)
        dx, dz = math.cos(ang) * ln, math.sin(ang) * ln
        nx, nz = -math.sin(ang) * wd, math.cos(ang) * wd
        pts = [[cx - dx, y, cz - dz], [cx + nx, y + .15, cz + nz], [cx + dx, y + .1, cz + dz], [cx - nx, y + .15, cz - nz]]
        p.quad(leaves, pts, C('pellet', rng.choice([1, 2])) if k % 2 else C('straw', 1), grid=.25, double=True, normal=[0, 1, 0])
    return p
