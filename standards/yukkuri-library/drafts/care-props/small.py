"""Small props: crumbs, waste, filling, mash, cloth, yarn, carrier basket."""
import math
from kit import Prop, Band, Planar, colour, fill, paint, deterministic

C = lambda ramp, step: (ramp, step)


def food_scrap():
    """Crumbs and bits in the pellet and straw ramps; flat, small, no mineral edges."""
    p = Prop('food_scrap')
    rng = deterministic(311)
    spots = [(-1.5, .2, .5, .22), (-.3, -.6, .7, .3), (1.1, .5, .55, .25), (1.7, -.4, .38, .2), (-.2, .8, .35, .18), (.5, -.1, .3, .16), (-1.0, -.9, .3, .16), (.2, 1.0, .25, .14)]
    for k, (x, z, r, h) in enumerate(spots):
        part = p.part(f'crumb_{k}')
        base = 'straw' if k in (2, 5) else 'pellet'
        def spec(n, base=base):
            if n[1] > .6:
                return C(base, 3)
            return C(base, 1 if n[1] < 0 else 2)
        p.blob(part, [x, h * .8, z], [r, h, r * .8], spec, rng, .12, grid=.05)
    leaf = p.part('leaf_bit')
    p.blob(leaf, [-.6, .2, 1.0], [.4, .2, .3], lambda n: C('leaf', 2 if n[1] > .3 else 1), rng, .12, grid=.25)
    return p


def yukkuri_waste():
    """Anko-coloured soft swirl mound: three stacked coils and a small curl at the top."""
    p = Prop('yukkuri_waste')
    profile = [(0, 0), (2.5, 0), (2.5, .5), (2, 1), (1.5, 1), (1.5, 1.5), (1.25, 2), (.75, 2.25), (.75, 2.5), (.5, 3), (0, 3.5)]
    tones = [C('anko', 0), C('anko', 0), C('anko', 1), C('anko', 0), C('anko', 1), C('anko', 1), C('anko', 2), C('anko', 1), C('anko', 2), C('anko', 2)]
    body = p.part('swirl_mound')
    p.lathe(body, profile, Band(lambda i, j, c: tones[i] if (j + i) % 3 else C('anko', min(3, tones[i][1] + 1))), seg=8, twist=.35, grid=.25, smooth='both')
    return p


LUMPS = [(.05, -.1), (-.45, .2), (.1, -.7), (.75, .6), (-1.25, -.2), (1.25, -.6), (-.35, 1.0), (-.75, -1.1), (1.75, .4), (-1.75, .45), (.9, -1.4), (.7, 1.4)]


def leaked_filling():
    """Loose lumps of bean-paste filling in the filling ramp. Lump k (unit k) appears when k pieces were leaked."""
    p = Prop('leaked_filling')
    rng = deterministic(409)
    for k, (x, z) in enumerate(LUMPS, 1):
        part = p.part(f'lump_{k}', k)
        r, h = .78 + rng.uniform(-.08, .1), .55
        profile = [(r, 0), (r * .86, h * .7), (r * .45, h), (0, h * 1.05)]
        tones = [C('anko', 1), C('anko', 2), C('anko', 3)]
        p.lathe(part, profile, Band(lambda i, j, c, t=tones: t[i]), seg=6, squash=.85, center=(x, z), phase=rng.uniform(0, 1), grid=None, smooth='both')
    return p


def food_mash_serving():
    """Soft pale green-beige porridge dollop with a glossy highlight, on a small leaf."""
    p = Prop('food_mash_serving')
    leaf = p.part('leaf')
    pts = [[-1.1, 0, 0], [-.4, 0, -.75], [.7, 0, -.65], [1.15, 0, 0], [.7, 0, .65], [-.4, 0, .75]]
    for k in range(6):
        a, b = pts[k], pts[(k + 1) % 6]
        p.tri(leaf, [[0, .06, 0], [a[0], .02, a[2]], [b[0], .02, b[2]]], [[0, 1, 0]] * 3, C('leaf', 2 if k % 2 else 1), None)
        p.tri(leaf, [[0, .0, 0], [b[0], .0, b[2]], [a[0], .0, a[2]]], [[0, -1, 0]] * 3, C('leaf', 0), None)
    dollop = p.part('dollop')
    profile = [(0, .06), (.8, .06), (.85, .2), (.6, .42), (.3, .56), (.12, .7), (0, .74)]
    p.lathe(dollop, profile, Band(lambda i, j, c: C('mash', 1) if i == 0 else C('mash', 2)), seg=8, twist=.25, grid=None, smooth='both')
    shine = p.part('gloss')
    p.blob(shine, [-.2, .5, -.15], [.16, .07, .12], lambda n: C('mash', 3), None, 0)
    for k, (x, z) in enumerate([(.3, .25), (-.35, .2), (.1, -.35)]):
        specks = p.part(f'plant_bit_{k}')
        p.blob(specks, [x, .38 - .1 * math.hypot(x, z), z], [.09, .05, .08], lambda n: C('leaf', 3), None, 0)
    return p


def wash_cloth():
    """Folded sky-blue towel with cream border stripes and a small berry tag."""
    p = Prop('wash_cloth')
    for k, (hx, hz, shift) in enumerate([(2.75, 2, 0), (2.5, 1.75, .25), (2.25, 1.5, -.25)]):
        part = p.part(f'fold_{k}')
        y0, y1 = k * .5, (k + 1) * .5
        p.box(part, [-hx + shift, y0, -hz], [hx + shift, y1, hz], {'*': C('sky', 1), 'top': C('sky', 2), 'bottom': C('sky', 0)}, grid=.25)
        p.box(part, [-hx + shift, y1 - .05, -hz], [-hx + shift + .5, y1 + .02, hz], C('paper', 2), grid=.05)
        p.box(part, [hx + shift - .5, y1 - .05, -hz], [hx + shift, y1 + .02, hz], C('paper', 2), grid=.05)
    tag = p.part('tag')
    p.box(tag, [1.0, 1.0, -.5], [2.0, 1.1, .5], C('berry', 2), grid=.05)
    return p


def yarn_ball():
    """Berry yarn ball: twelve-sided sphere with diagonal windings and a loose tail."""
    p = Prop('yarn_ball')
    r = 2.5
    profile = [(0, 0)] + [(r * math.sin(math.pi * k / 8), r - r * math.cos(math.pi * k / 8)) for k in range(1, 8)] + [(0, 2 * r)]
    body = p.part('wound_ball')
    def tone(i, j, c):
        w = (j * 2 + i * 3) % 8
        return C('berry', 1) if w in (0, 1) else C('berry', 3) if w == 4 and i in (2, 3) else C('berry', 2)
    p.lathe(body, profile, Band(tone), seg=16, grid=.25, smooth='both')
    tail = p.part('loose_tail')
    p.rod(tail, [1.9, .9, 1.2], [3.2, .3, 2.2], .3, .3, seg=4, spec=C('berry', 3), caps=(False, True), grid=.25)
    p.rod(tail, [3.2, .3, 2.2], [4.0, .3, 1.6], .3, .3, seg=4, spec=C('berry', 3), caps=(False, True), grid=.25)
    return p


def yukkuri_carrier():
    """Oval wicker carrier: checker-woven body, rolled rim, cream liner, arched handle."""
    p = Prop('yukkuri_carrier')
    rx, rz = 5.5, 4.5
    sq = rz / rx
    body = p.part('wicker_body')
    profile = [(0, 0), (4.5, 0), (5, 1.5), (5.5, 3.5), (5.5, 5.5), (5.5, 7)]
    p.lathe(body, profile, Band(lambda i, j, c: C('straw', 0) if i == 0 else (C('straw', 1) if (i + j) % 2 else C('straw', 2))),
            seg=16, squash=sq, grid=.5, smooth='around')
    rim = p.part('rolled_rim')
    p.torus(rim, [0, 7, 0], 5.0, .75, seg=16, tube=4, spec=C('wood', 2), squash=sq, grid=.25, smooth='both')
    liner = p.part('cream_liner')
    p.lathe(liner, [(5.0, 7), (4.75, 6.5), (4.5, 5.5), (0, 5.5)], Band(lambda i, j, c: C('straw', 0) if i < 2 else C('paper', 2)), seg=16, squash=sq, grid=.25, smooth='around')
    handle = p.part('arched_handle')
    pts = [[5.25 * math.cos(math.pi * k / 8), 7.25 + 3.5 * math.sin(math.pi * k / 8), 0] for k in range(9)]
    for k in range(8):
        p.rod(handle, pts[k], pts[k + 1], .6, .6, seg=4, spec=C('wood', 3) if k % 2 else C('wood', 2), caps=(False, False), grid=.25)
    return p
