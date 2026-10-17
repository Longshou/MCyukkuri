"""Hat-boat provisions (帽船载粮): one volumetric serving per food kind, heaped on the hat's inner ledges.

Unlike the other care props these are authored in HAT units (1 unit = 1/16 block at hull scale 1): the renderer scales a
serving by the hull scale alone, so the numbers below are what the hat shows. A serving is about 1.1 units across (the
width of the front ledge) and at most 1.2 high; the renderer stacks them two layers deep. Palette colours only; wheat and
grass tint nothing and reference no game pixels except the wheat ear, which names the vanilla sprite. Flat swatch UVs
(no material noise) because a serving is only a few texels wide on screen.
"""
import math
from kit import Prop, Band, deterministic

C = lambda ramp, step: (ramp, step)


def _prop(name):
    return Prop(name, noise=False)


def apple():
    p = _prop('hat_cargo_apple')
    body = p.part('apple')
    profile = [(0, .06), (.3, .0), (.5, .2), (.56, .52), (.5, .8), (.32, .98), (.1, .93), (0, .84)]

    def tone(i, j, c):
        if i >= 4 and j % 5 == 1:
            return C('chili', 3)
        return C('chili', 2 if i >= 3 else 1 if i >= 1 else 0)
    p.lathe(body, profile, Band(tone), seg=10, grid=None, smooth='both')
    stem = p.part('stem')
    p.rod(stem, [0, .84, 0], [.07, 1.2, 0], .06, .045, seg=4, spec=C('wood', 1), caps=(False, True), grid=None)
    leaf = p.part('leaf')
    p.quad(leaf, [[.07, 1.1, 0], [.5, 1.2, .12], [.58, 1.12, .0], [.28, 1.04, -.14]], C('leaf', 2), double=True)
    return p


def _berry(p, part, x, y, z, r):
    """One round berry: a lathe sphere, deep red with a lighter glossy cap and a small pale highlight."""
    profile = [(0, y), (r * .55, y + r * .08), (r * .92, y + r * .42), (r, y + r * .95), (r * .9, y + r * 1.5), (r * .55, y + r * 1.9), (0, y + r * 2.0)]
    p.lathe(part, profile, Band(lambda i, j, c: C('chili', 3 if (i >= 4 and j % 4 == 0) else 2 if i >= 3 else 1 if i >= 1 else 0)),
            center=(x, z), seg=8, grid=None, smooth='both')
    spark = p.part('shine')
    p.quad(spark, [[x - r * .5, y + r * 1.45, z - r * .2], [x - r * .2, y + r * 1.62, z - r * .45], [x - r * .05, y + r * 1.45, z - r * .45], [x - r * .35, y + r * 1.3, z - r * .2]],
           C('paper', 3), double=True)


def berries():
    p = _prop('hat_cargo_berries')
    fruit = p.part('berries')
    # a tight cluster of round berries, two layers high, about 1.2 across
    for x, y, z, r in [(-.3, 0, -.2, .27), (.25, 0, -.25, .26), (.0, 0, .3, .27), (-.45, 0, .25, .22), (.5, 0, .22, .22),
                       (-.1, .4, -.05, .27), (.22, .38, .2, .23)]:
        _berry(p, fruit, x, y, z, r)
    stem = p.part('stem')
    p.rod(stem, [-.1, 1.05, -.05], [-.02, 1.35, .02], .05, .04, seg=4, spec=C('leaf', 0), caps=(False, True), grid=None)
    leaf = p.part('leaf')
    p.quad(leaf, [[-.02, 1.28, .02], [.45, 1.28, .22], [.6, 1.2, .0], [.25, 1.14, -.15]], C('leaf', 2), double=True)
    p.quad(leaf, [[-.02, 1.28, .02], [-.42, 1.3, .2], [-.58, 1.2, -.02], [-.25, 1.14, -.15]], C('leaf', 1), double=True)
    return p


def carrot():
    p = _prop('hat_cargo_carrot')
    root = p.part('root')
    p.rod(root, [0, .24, -.5], [0, .09, .62], .24, .04, seg=7, spec=Band(lambda i, j, c: C('gold', 2 if j % 3 else 1)), caps=(True, True), grid=None, smooth='both')
    p.rod(root, [0, .25, -.15], [0, .2, -.1], .245, .245, seg=7, spec=C('gold', 0), caps=(False, False), grid=None)
    leaves = p.part('leaves')
    for k, (dz, dx, step) in enumerate([(-.45, .0, 2), (-.4, .22, 1), (-.4, -.22, 3)]):
        p.rod(leaves, [0, .24, -.5], [dx, .3 + .08 * k, -.5 + dz], .07, .035, seg=4, spec=C('leaf', step), caps=(False, True), grid=None)
    return p


def bread():
    p = _prop('hat_cargo_bread')
    loaf = p.part('loaf')
    profile = [(0, 0), (.62, 0), (.68, .2), (.56, .46), (.3, .6), (0, .64)]
    # long axis along X: the lathe is squashed on Z
    p.lathe(loaf, profile, Band(lambda i, j, c: C('wood', 3 if i >= 4 else 2 if i >= 2 else 1)), seg=10, squash=.78,
            grid=None, smooth='both')
    score = p.part('scoring')
    for k in (-1, 0, 1):
        x = k * .28
        p.quad(score, [[x - .05, .6, -.27], [x + .1, .6, -.27], [x + .1, .6, .27], [x - .05, .6, .27]], C('straw', 2), double=True, normal=[0, 1, 0])
    return p


def cookie():
    p = _prop('hat_cargo_cookie')
    rng = deterministic(1307)
    disc = p.part('cookie')
    profile = [(0, 0), (.5, 0), (.56, .08), (.52, .2), (.3, .26), (0, .27)]
    p.lathe(disc, profile, Band(lambda i, j, c: C('gold', 2 if i >= 3 else 1)), seg=10, grid=None, smooth='both')
    chips = p.part('chips')
    for k in range(5):
        a = k * 2.4 + .3
        r = .12 + .22 * ((k * .6) % 1)
        p.blob(chips, [r * math.cos(a), .27, r * math.sin(a)], [.11, .07, .11], lambda n: C('anko', 1), rng, .1)
    return p


def melon():
    p = _prop('hat_cargo_melon')
    R, T = .66, .26
    steps = 8
    ring = [(R, 'rind'), (R * .9, 'white'), (R * .8, 'flesh')]
    rind, white, flesh = p.part('rind'), p.part('white'), p.part('flesh')
    pts = lambda r, y: [[r * math.cos(math.pi * k / steps), y, r * math.sin(math.pi * k / steps) * 1.0] for k in range(steps + 1)]
    # flat on the table, rind down the curve; the straight cut edge faces -Z
    for k in range(steps):
        a0, a1 = math.pi * k / steps, math.pi * (k + 1) / steps
        o0, o1 = [R * math.cos(a0), R * math.sin(a0)], [R * math.cos(a1), R * math.sin(a1)]
        m0, m1 = [R * .9 * math.cos(a0), R * .9 * math.sin(a0)], [R * .9 * math.cos(a1), R * .9 * math.sin(a1)]
        i0, i1 = [R * .78 * math.cos(a0), R * .78 * math.sin(a0)], [R * .78 * math.cos(a1), R * .78 * math.sin(a1)]
        # rind wall
        p.quad(rind, [[o0[0], 0, o0[1]], [o1[0], 0, o1[1]], [o1[0], T, o1[1]], [o0[0], T, o0[1]]], C('leaf', 1 if k % 2 else 2), double=False,
               normal=[math.cos((a0 + a1) / 2), 0, math.sin((a0 + a1) / 2)])
        # top: rind lip, white band, red flesh fan
        p.quad(rind, [[o0[0], T, o0[1]], [o1[0], T, o1[1]], [m1[0], T, m1[1]], [m0[0], T, m0[1]]], C('leaf', 3), normal=[0, 1, 0])
        p.quad(white, [[m0[0], T, m0[1]], [m1[0], T, m1[1]], [i1[0], T, i1[1]], [i0[0], T, i0[1]]], C('mash', 3), normal=[0, 1, 0])
        p.tri(flesh, [[0, T, 0], [i0[0], T, i0[1]], [i1[0], T, i1[1]]], [[0, 1, 0]] * 3, C('chili', 2 if k % 2 else 3))
        p.tri(rind, [[0, 0, 0], [o1[0], 0, o1[1]], [o0[0], 0, o0[1]]], [[0, -1, 0]] * 3, C('leaf', 0))
    p.quad(flesh, [[-R, 0, 0], [-R, T, 0], [R, T, 0], [R, 0, 0]], C('chili', 1), normal=[0, 0, -1])
    seeds = p.part('seeds')
    rng = deterministic(1311)
    for x, z in ((-.22, .24), (.1, .3), (.28, .16), (-.02, .14)):
        p.blob(seeds, [x, T, z], [.05, .035, .075], lambda n: C('anko', 0), rng, .05)
    # centre the straight edge's midpoint on the origin so the half disc sits in the middle of its berth
    for part in p.parts:
        for tri in part['tris']:
            for vertex in tri:
                vertex[0][2] -= R * .42
    return p


def _bundle(p, part, n_rows, length, tones, radius, splay):
    """Stalks lying along X: row 0 at the bottom, upper rows nestled in the grooves, a straw tie in the middle."""
    layout = {2: [(-.5, 0), (.5, 0), (0, 1)], 1: [(-.55, 0), (.55, 0), (0, 1), (-.28, 1), (.28, 1)]}[n_rows]
    for k, (off, row) in enumerate(layout):
        z = off * radius * 2.4
        y = radius * (1.0 + row * 1.45)
        sx = (k - 1) * splay
        p.rod(part, [-length / 2, y, z - sx], [length / 2, y + .02 * row, z + sx], radius * .9, radius * .55, seg=5,
              spec=C(*tones[k % len(tones)]), caps=(True, True), grid=None)
    top = radius * (2.2 if n_rows == 2 else 3.0)
    p.rod(part, [-.1, top * .52, 0], [.1, top * .52, 0], radius * 2.1, radius * 2.1, seg=7, spec=C('straw', 3), caps=(True, True), grid=None)


def grass(tall=False):
    p = _prop('hat_cargo_tall_grass' if tall else 'hat_cargo_grass')
    blades = p.part('blades')
    if tall:
        _bundle(p, blades, 1, 1.3, [('leaf', 2), ('leaf', 3), ('leaf', 1), ('straw', 2), ('leaf', 2)], .1, .08)
    else:
        _bundle(p, blades, 2, 1.1, [('leaf', 2), ('leaf', 1), ('leaf', 3)], .14, .06)
    return p


def wheat():
    """A small sheaf: straw stalks tied with a cord, and three ears standing at the head, each drawn as two crossed
    sprites so it reads as a bunch from every side (vanilla sprite referenced, not copied)."""
    p = _prop('hat_cargo_wheat')
    stalks = p.part('stalks')
    _bundle(p, stalks, 2, 1.1, [('straw', 2), ('straw', 1), ('straw', 3)], .13, .05)
    ears = p.part('ears', texture='minecraft:textures/item/wheat.png', tint=[255, 255, 255])
    uv = [(0, 1), (1, 1), (1, 0), (0, 0)]
    for k, (z, yaw) in enumerate(((-.2, .15), (0, -.1), (.2, .25))):
        base = [.5, .3, z]
        for plane in (0, 1):
            # a sprite standing on its stalk, tilted forward along +X; the second plane is the first turned 90 degrees
            up = [.55, .85, 0]; side = [0, 0, .5] if plane == 0 else [.0, .0, .0]
            if plane == 1:
                side = [.38, 0, 0]; up = [.0, .85, .0]
            c, s = math.cos(yaw), math.sin(yaw)
            rot = lambda v: [v[0] * c - v[2] * s, v[1], v[0] * s + v[2] * c]
            u, w = rot(up), rot(side)
            pts = [[base[i] - w[i] for i in range(3)], [base[i] + w[i] for i in range(3)],
                   [base[i] + w[i] + u[i] for i in range(3)], [base[i] - w[i] + u[i] for i in range(3)]]
            p.quad(ears, pts, None, uvs=uv, double=True)
    return p


def candy():
    p = _prop('hat_cargo_candy')
    rng = deterministic(1319)
    core = p.part('wrapper')
    p.blob(core, [0, .3, 0], [.38, .3, .3], lambda n: C('berry', 2 if n[1] > .3 else 1), rng, .05, smooth=True)
    for sign in (-1, 1):
        p.rod(core, [sign * .3, .3, 0], [sign * .62, .3, 0], .1, .22, seg=5, spec=C('berry', 3), caps=(False, True), grid=None)
    return p


def pudding():
    p = _prop('hat_cargo_pudding')
    cup = p.part('custard')
    p.lathe(cup, [(0, 0), (.4, 0), (.5, .3), (.55, .6)], Band(lambda i, j, c: C('gold', 2 if i else 1)), seg=10, grid=None, smooth='both')
    top = p.part('caramel')
    p.lathe(top, [(.55, .58), (.57, .6), (.5, .68), (.3, .72), (0, .73)], Band(lambda i, j, c: C('anko', 2 if i >= 2 else 1)), seg=10, grid=None, smooth='both')
    return p


CARGO = {
    'hat_cargo_apple': apple,
    'hat_cargo_berries': berries,
    'hat_cargo_carrot': carrot,
    'hat_cargo_bread': bread,
    'hat_cargo_cookie': cookie,
    'hat_cargo_melon': melon,
    'hat_cargo_grass': lambda: grass(False),
    'hat_cargo_tall_grass': lambda: grass(True),
    'hat_cargo_wheat': wheat,
    'hat_cargo_candy': candy,
    'hat_cargo_pudding': pudding,
}


def mat():
    """A pad of overlapping leaves with a straw cord under the heap, so the load reads as packed food. Pad n (unit n)
    lies under display slot n-1; the origin is the heap centre (layout() x0, z0)."""
    p = _prop('hat_cargo_mat')
    for n, (dx, dz, _, _, _) in enumerate(FLOOR, 1):
        part = p.part(f'pad_{n}', n)
        p.lathe(part, [(0, .0), (.78, .0), (.8, .1), (.62, .17), (0, .19)], Band(lambda i, j, c, n=n: C('leaf', (1, 2, 1, 3)[(n + j) % 4] if i >= 2 else 0)),
                center=(dx, dz), seg=8, squash=.9, phase=n * .5, grid=None, smooth='both')
    cord = p.part('cord', 3)
    p.rod(cord, [-.78, .22, -.95], [.78, .22, -.95], .1, .1, seg=6, spec=C('straw', 3), caps=(True, True), grid=None)
    return p


CARGO['hat_cargo_mat'] = mat


# -- how servings are heaped (mirrored by client/HatCargoRenderer; X0/Z0 come from hat_vessel.json cargo.berths) ------
# Fill order follows the server's berth order: bow end first, so the first servings stay clear of a seated captain
# (her body starts about 2.6 units forward of the stern; HatBoatEntity.cargoRoom blocks the berths beyond that).
SERVING_SCALE = 1.1          # a serving is drawn this much larger than authored (about 1.2 hat units across)
NARROW_SCALE = .9            # the narrow ledge's serving
NARROW_DX = -7.52            # narrow ledge x (-3.84, clear of the ledge wall) relative to the heap centre x0
MAT_LIFT = .16               # servings rest on the leaf pad
LAYER = .72                  # second-layer servings rest in the grooves between two floor servings
PITCH = .95                  # distance between floor servings along the wide ledge
# (dx, dz, lift, yaw, scale) about the heap centre; floor slots first (each has a leaf pad, unit = slot number)
FLOOR = [(.1, -2 * PITCH, MAT_LIFT, 40, SERVING_SCALE), (NARROW_DX, -2 * PITCH, MAT_LIFT, -15, NARROW_SCALE),
         (-.1, -PITCH, MAT_LIFT, 25, SERVING_SCALE), (.1, 0.0, MAT_LIFT, -18, SERVING_SCALE), (-.1, PITCH, MAT_LIFT, -30, SERVING_SCALE)]
TOP = [(0, -1.5 * PITCH, 35), (0, -.5 * PITCH, 5), (0, .5 * PITCH, -22)]


def layout(berths):
    """Eight display slots in fill order: bow-end servings on the leaf pads, then three nested above them.
    Returns (x0, z0, slots) with slots (dx, dz, lift, yaw, scale) about the heap centre."""
    x0 = berths[1]['x'] - .08
    z0 = (berths[1]['z'] + berths[7]['z']) / 2 + .1
    return x0, z0, list(FLOOR) + [(dx, dz, MAT_LIFT + LAYER, yaw, SERVING_SCALE) for dx, dz, yaw in TOP]


# item id -> serving prop (anything else with a mod prop keeps that prop; others are drawn as their item)
SERVINGS = {
    'minecraft:apple': 'hat_cargo_apple', 'minecraft:sweet_berries': 'hat_cargo_berries', 'minecraft:carrot': 'hat_cargo_carrot',
    'minecraft:bread': 'hat_cargo_bread', 'minecraft:cookie': 'hat_cargo_cookie', 'minecraft:melon_slice': 'hat_cargo_melon',
    'minecraft:short_grass': 'hat_cargo_grass', 'minecraft:tall_grass': 'hat_cargo_tall_grass', 'minecraft:wheat': 'hat_cargo_wheat',
    'mcyukkuri:sweet_candy': 'hat_cargo_candy', 'mcyukkuri:custard_pudding': 'hat_cargo_pudding',
}
