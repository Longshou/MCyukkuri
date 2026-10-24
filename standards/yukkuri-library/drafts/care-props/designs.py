"""Care-prop designs: the forage pile (wild nest stock, reused by the hat-boat provisions). The placed food bowl and its
fills are vanilla-style voxel props in vanilla_props.py.

Every design returns a kit.Prop. Colours are (ramp, step) swatches of art/props/palette.json.
Units are 1/16 block; origin at the footprint centre; +Z is the side the shop icon faces by default is -Z.
"""
import math
from kit import (Prop, Band, Planar, RAMPS, colour, deterministic, fill, paint, ring_wall, rrect_points, unit, add, sub, mul, cross, dot)

C = lambda ramp, step: (ramp, step)

# ---------------------------------------------------------------------------------------------------------
# Forage pile (储粮堆): the wild stock of a nest. A low flat base of a few overlapping dry leaves always shows; the stored
# food lies DOWN on it, nothing stands like a live plant: a rounded heap of cut grass and dry stalks with tied bundles
# lying across it, crossed wheat ears laid flat, a few red berries and apples and brown nuts. Four stages (unit 1..4), each
# its own heap and accents (a later heap swallows the earlier one): about 2/16 high for a handful, about 7/16 high and
# rounded when full. Wheat references the vanilla sprite (not copied); everything else samples the shared palette.
PILE_RADIUS = (3.6, 4.5, 5.3, 6.0)        # heap footprint radius per stage
PILE_PEAK = (1.7, 3.2, 5.0, 6.4)          # heap height above the base per stage
PILE_BASE_TOP = .5


def pile_surface(r, stage):
    """Height of the heap surface at radius r for a stage (1..4)."""
    R, H = PILE_RADIUS[stage - 1], PILE_PEAK[stage - 1]
    return PILE_BASE_TOP + (H * (1 - (r / R) ** 2) ** .8 if r < R else 0.0)


def pile_bundle(p, part, cx, cz, yaw, y, length, tone, n=3):
    """A small tied bundle of cut stalks lying horizontally: n parallel rods and a straw tie in the middle."""
    ux, uz = math.cos(yaw), math.sin(yaw)
    vx, vz = -uz, ux
    r = .42
    for k in range(n):
        off = (k - (n - 1) / 2) * r * 1.7
        lift = r * (1.1 if k == n // 2 and n > 2 else .35)
        a = [cx + vx * off - ux * length / 2, y + lift, cz + vz * off - uz * length / 2]
        b = [cx + vx * off + ux * length / 2, y + lift, cz + vz * off + uz * length / 2]
        p.rod(part, a, b, r * .95, r * .8, seg=5, spec=C(*tone[k % len(tone)]), caps=(True, True), grid=None)
    tie = r * 1.75 if n > 2 else r * 1.35
    mid = [cx, y + r * (.9 if n > 2 else .5), cz]
    a = [mid[0] - ux * .35, mid[1], mid[2] - uz * .35]
    b = [mid[0] + ux * .35, mid[1], mid[2] + uz * .35]
    p.rod(part, a, b, tie, tie, seg=6, spec=C('straw', 3), caps=(True, True), grid=None)


def forage_pile():
    p = Prop('forage_pile')
    rng = deterministic(731)
    # -- flat base: a few overlapping dry leaves and flattened grass, no raised rim ----------------------------
    base = p.part('dry_leaves')
    tones = [('straw', 1), ('wood', 2), ('straw', 2), ('wood', 1), ('straw', 0), ('wood', 3)]
    for k in range(9):
        ang = math.tau * k / 9 + .3
        reach = 3.0 + (k % 3) * .8
        cx, cz = reach * math.cos(ang), reach * math.sin(ang)
        length, width = 3.4 + (k % 2) * .6, 2.2
        ux, uz = math.cos(ang + .35), math.sin(ang + .35)
        vx, vz = -uz, ux
        y = .12 + (k % 3) * .1
        pts = [[cx - ux * length / 2, y, cz - uz * length / 2], [cx - ux * length * .12 + vx * width / 2, y + .05, cz - uz * length * .12 + vz * width / 2],
               [cx + ux * length / 2, y + .1, cz + uz * length / 2], [cx - ux * length * .12 - vx * width / 2, y + .05, cz - uz * length * .12 - vz * width / 2]]
        p.quad(base, pts, C(*tones[k % len(tones)]), None, double=True, normal=[0, 1, 0])
    for k in range(7):                       # a hub of flattened grass under the heap
        a0, a1 = math.tau * k / 7, math.tau * (k + 1) / 7
        p.tri(base, [[0, PILE_BASE_TOP, 0], [3.6 * math.cos(a0), .32, 3.6 * math.sin(a0)], [3.6 * math.cos(a1), .32, 3.6 * math.sin(a1)]],
              [[0, 1, 0]] * 3, C('straw', 0 if k % 2 else 1), None)
    # -- four heaps ----------------------------------------------------------------------------------------------
    bundles = (1, 2, 3, 3)
    wheat = (1, 2, 2, 3)
    apples = (0, 1, 1, 2)
    berries = (2, 3, 4, 5)
    nuts = (0, 2, 3, 3)
    for stage in range(1, 5):
        R, H = PILE_RADIUS[stage - 1], PILE_PEAK[stage - 1]
        heap = p.part(f'cut_stalks_{stage}', stage)
        rings = [1.0, .9, .75, .55, .3, 0.0]
        profile = [(0, PILE_BASE_TOP - .1), (R, PILE_BASE_TOP - .1)] + [(R * f, PILE_BASE_TOP + H * (1 - f * f) ** .8) for f in rings]
        heap_tones = [('straw', 1), ('straw', 1), ('straw', 2), ('straw', 1), ('straw', 3), ('straw', 0), ('straw', 2), ('wood', 3)]
        p.lathe(heap, profile, Band(lambda i, j, c, t=heap_tones, s=stage: t[(j * 3 + s) % len(t)]), seg=14, phase=stage * .4, grid=None, smooth='flat')
        strands = p.part(f'hay_strands_{stage}', stage)
        for i in range(6 + 4 * stage):          # loose cut stalks lying down the flanks of the heap
            ang = i * 2.399963 + stage * .8
            r0, r1 = R * (.25 + .25 * ((i * .37) % 1)), R * (.7 + .22 * ((i * .53) % 1))
            w = .22
            def at(r, a):
                return [r * math.cos(a), pile_surface(r, stage) + .1, r * math.sin(a)]
            quad = [at(r0, ang - w / r0), at(r1, ang - w / r1), at(r1, ang + w / r1), at(r0, ang + w / r0)]
            p.quad(strands, quad, C(*[('straw', 3), ('leaf', 2), ('straw', 2), ('wood', 3)][i % 4]), None, double=True, normal=[0, 1, 0])
        accents = p.part(f'bundles_{stage}', stage)
        for i in range(bundles[stage - 1]):
            r = R * (.28 + .3 * ((i * .618 + stage * .31) % 1))
            ang = i * 2.399963 + stage * 1.3
            cx, cz = r * math.cos(ang), r * math.sin(ang)
            y = pile_surface(r, stage) - .05
            yaw = ang + math.pi / 2 + rng.uniform(-.3, .3)
            n = 3 if (i + stage) % 2 == 0 else 2
            mix = [('leaf', 2), ('leaf', 1), ('straw', 2)] if i % 2 == 0 else [('straw', 2), ('straw', 1), ('straw', 3)]
            pile_bundle(p, accents, cx, cz, yaw, y, 3.8 + .2 * stage, mix, n)
        ears = p.part(f'laid_wheat_{stage}', stage, texture='minecraft:textures/item/wheat.png', tint=[255, 255, 255])
        for i in range(wheat[stage - 1]):
            r = R * (.2 + .55 * ((i * .73 + stage * .21) % 1))
            ang = i * 2.399963 + stage * 2.9 + 1.2
            cx, cz = r * math.cos(ang), r * math.sin(ang)
            for cross in (0, 1):                 # two ears crossed, both flat
                yaw = ang + (.5 if cross else -.5) + rng.uniform(-.15, .15)
                y = pile_surface(r, stage) + .12 + cross * .12
                half = 1.5
                pts = []
                for sx, sz in ((-1, -1), (1, -1), (1, 1), (-1, 1)):
                    px, pz = sx * half, sz * half
                    pts.append([cx + px * math.cos(yaw) - pz * math.sin(yaw), y, cz + px * math.sin(yaw) + pz * math.cos(yaw)])
                uvs = [(0, p.H), (p.W, p.H), (p.W, 0), (0, 0)]
                p.quad(ears, pts, None, uvs=uvs, double=True, normal=[0, 1, 0])
        red = p.part(f'red_fruit_{stage}', stage)
        for i in range(apples[stage - 1]):
            r = R * (.25 + .4 * ((i * .5 + stage * .17) % 1))
            ang = i * 3.1 + stage * 1.9 + .4
            cx, cz = r * math.cos(ang), r * math.sin(ang)
            y = pile_surface(r, stage) + .6
            p.blob(red, [cx, y, cz], [.95, .85, .95], lambda n: C('chili', 2 if n[1] > .55 else 1 if n[1] > -.2 else 0), rng, .06, smooth=True)
            p.rod(red, [cx, y + .75, cz], [cx + .1, y + 1.2, cz], .1, .08, seg=4, spec=C('wood', 0), caps=(False, True), grid=None)
        for i in range(berries[stage - 1]):
            r = R * (.15 + .75 * ((i * .618 + stage * .27) % 1))
            ang = i * 2.399963 + stage * 2.1 + 1.0
            cx, cz = r * math.cos(ang), r * math.sin(ang)
            y = pile_surface(r, stage) + .3
            p.blob(red, [cx, y, cz], [.5, .46, .5], lambda n: C('chili', 2 if n[1] > .55 else 1 if n[1] > -.2 else 0), rng, .08, smooth=True)
        if nuts[stage - 1]:
            kernels = p.part(f'nuts_{stage}', stage)
            for i in range(nuts[stage - 1]):
                r = R * (.2 + .7 * ((i * .41 + stage * .33) % 1))
                ang = i * 2.399963 + stage * .7 + 2.4
                cx, cz = r * math.cos(ang), r * math.sin(ang)
                y = pile_surface(r, stage) + .3
                p.blob(kernels, [cx, y, cz], [.62, .45, .45], lambda n: C('wood', 3 if n[1] > .55 else 2 if n[1] > -.2 else 0), rng, .1, tilt=rng.uniform(-.4, .4), smooth=True)
    return p
