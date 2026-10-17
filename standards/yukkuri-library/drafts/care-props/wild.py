"""Self-built nests of wild yukkuri: piled leaves, grass, twigs, moss and straw in a hollow.

Every surface references a vanilla texture (minecraft:textures/..., never copied into the repository) mapped at the vanilla
density of 1 texel per unit (16 per block); leaf and grass textures carry the vanilla plains tint as the part tint.
Units are 1/16 block, origin at the centre of the bed, ground Y = 0, +Z is the south side.

Real body sizes: standard adult 12.8 wide x 10.7 deep (individual target up to 1.2x, deibu 1.5x), children grow from 0.2 to
0.8. The shared family bed therefore keeps a 16 x 16 hollow and builds its rim in the block space around it (exposed
sides only); the personal bed of a growing child has a 10 x 8 hollow.

Render units follow client/ShelterRenderer: forest_burrow 1 = bedding, 2..5 = north / east / south / west rim;
personal_leaf_nest 1 = underlayer, 2 = spread second portion, 3 = pressed rim, grass and twigs; bedding_bundle and
predator_nest are unit 0.
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


def leaf_mat(rng):
    r = rng.random()
    return LEAF_DRY if r < .55 else LEAF if r < .78 else LEAF_BIRCH if r < .94 else LEAF_SPRUCE


def pad(b, unit, cells, half_x, half_z, lift=0.0, litter=True):
    """Packed mulch bed: dirt slabs in a 4-unit grid inside an ellipse, topped by a loose litter layer of thin leaf plates.
    The surface dips to a soft hollow in the middle (ground Y = 0 is the sleeping plane) and rises toward the edge."""
    rng = b.rng
    for gx in range(-cells, cells):
        for gz in range(-cells, cells):
            cx, cz = (gx + .5) * 4.0, (gz + .5) * 4.0
            if (cx / half_x) ** 2 + (cz / half_z) ** 2 > 1.18:
                continue
            e = min(1.0, (cx / (half_x + 1)) ** 2 + (cz / (half_z + 1)) ** 2)
            top = .12 + lift + .75 * e + rng.uniform(-.04, .04)
            mat = rng.choice((PODZOL, PODZOL, PODZOL, COARSE))
            b.box(mat, unit, (cx, top - .6, cz), (2.0, .6, 2.0))
    n = int(4 * cells + 2)
    step = 4.0 * cells * 2 / n
    for i in range(n):
        for j in range(n):
            x = -4.0 * cells + (i + .5) * step + rng.uniform(-.5, .5)
            z = -4.0 * cells + (j + .5) * step + rng.uniform(-.5, .5)
            if (x / half_x) ** 2 + (z / half_z) ** 2 > 1.0 or not litter:
                continue
            e = min(1.0, (x / (half_x + 1)) ** 2 + (z / (half_z + 1)) ** 2)
            top = .12 + lift + .75 * e
            w = rng.uniform(1.3, 1.8)
            b.box(HAY if rng.random() < .14 else leaf_mat(rng), unit, (x, top + .12 + (i * n + j) % 3 * .035, z), (w, .12, rng.uniform(1.1, 1.5)), yaw=rng.uniform(0, math.pi))


def clump(b, unit, u, t, width, depth, height, frame, lean=0.0):
    """One piled leaf clump of the rim. frame(u, t) -> (x, z, yaw offset): maps the side frame (u along, t outward) to the bed."""
    rng = b.rng
    x, z, yaw0 = frame(u, t)
    y0 = rng.uniform(.0, .3)
    mat = leaf_mat(rng)
    b.box(mat, unit, (x, y0 + height / 2, z), (width / 2, height / 2, depth / 2), yaw=yaw0 + rng.uniform(-.28, .28), pitch=lean)
    if height > 2.0 and rng.random() < .55:          # a smaller second leaf lump on top gives a ragged crest
        b.box(leaf_mat(rng), unit, (x + rng.uniform(-.6, .6), y0 + height + .35, z + rng.uniform(-.6, .6)),
              (width / 3, .45, depth / 3), yaw=rng.uniform(0, 3), pitch=0)


def side_frame(side):
    """Side 0..3 = north, east, south, west: (u, t) -> (x, z, yaw)."""
    return {0: lambda u, t: (-u, -t, 0.0), 1: lambda u, t: (t, -u, math.pi / 2),
            2: lambda u, t: (u, t, 0.0), 3: lambda u, t: (-t, u, math.pi / 2)}[side]


def bank(b, unit, frame, t_in, t_out, u_max, h_in, h_out, mats, step=(3.4, 4.2), yaw_var=.14):
    """Packed mulch bank under the leaf rim: stepped slabs (inner row lower, outer row higher) of dirt, moss and straw."""
    rng = b.rng
    u = -u_max + rng.uniform(0, .6)
    while u < u_max:
        w = rng.uniform(*step)
        for row, (t0, t1, h) in enumerate(((t_in, (t_in + t_out) / 2, h_in), ((t_in + t_out) / 2, t_out, h_out))):
            hh = h + rng.uniform(-.25, .25)
            x, z, yaw0 = frame(u + w / 2, (t0 + t1) / 2)
            b.box(rng.choice(mats), unit, (x, hh / 2, z), (w / 2 + .15, hh / 2, (t1 - t0) / 2 + .1), yaw=yaw0 + rng.uniform(-yaw_var, yaw_var))
        u += w * .96


def rim_side(b, unit, side):
    """Rim just outside the 16-unit cell: a packed bank, then piled leaves, a few twigs, tucked grass and straw."""
    rng, frame = b.rng, side_frame(side)
    bank(b, unit, frame, 7.9, 10.9, 10.8, 1.9, 3.0, (PODZOL, PODZOL, PODZOL, MOSS))
    for band, (t0, t1, h0, h1, wd) in enumerate(((7.7, 8.8, 1.9, 2.7, (2.8, 3.6)), (8.8, 10.4, 3.4, 4.4, (3.0, 4.0)), (10.2, 11.2, 2.0, 2.9, (2.6, 3.4)))):
        u = -10.6 + rng.uniform(0, 1)
        while u < 10.8:
            width = rng.uniform(*wd)
            t = rng.uniform(t0, t1)
            height = rng.uniform(h0, h1) + (.4 if abs(u) > 9 and band == 1 else 0)
            clump(b, unit, u + width / 2, t, width, rng.uniform(2.0, 2.9), height, frame, lean=0.0)
            u += width * rng.uniform(.85, 1.0)
    for k in range(2):                                   # twigs laid over the crest
        u = rng.uniform(-8, 8)
        x, z, yaw0 = frame(u, rng.uniform(8.8, 9.8))
        b.box(LOG if k % 2 else LOG_DARK, unit, (x, rng.uniform(4.2, 4.7), z), (rng.uniform(2.6, 3.4), .4, .4), yaw=yaw0 + math.pi / 2 + rng.uniform(-.6, .6), pitch=rng.uniform(-.12, .12), grain=True)
    x, z, _ = frame(rng.uniform(-7, 7), rng.uniform(8.0, 9.6))
    b.tuft(unit, x, z, 3.3, 3.4)
    x, z, _ = frame(rng.uniform(-6, 6), 9.2)
    b.box(MOSS, unit, (x, 4.3, z), (1.5, .3, 1.3), yaw=rng.uniform(0, 3))
    for k in range(2):                                   # loose straw
        x, z, _ = frame(rng.uniform(-8, 8), rng.uniform(7.8, 9.0))
        b.sprite(WHEAT, unit, (x, rng.uniform(3.4, 4.2), z), 3.6, rng.uniform(0, 6.28), tilt=rng.uniform(-.2, .2))


def forest_burrow():
    p = Prop('forest_burrow')
    b = Builder(p, 931)
    pad(b, 1, 2, 8.2, 8.2)
    for k in range(8):                                   # moss and grass tufts in the bed, clear of the sleeping spot
        x, z = b.rng.uniform(-7, 7), b.rng.uniform(-7, 7)
        if math.hypot(x, z) < 5.4:
            continue
        if k % 2:
            b.tuft(1, x, z, .5, 3.0)
        else:
            b.box(MOSS, 1, (x, .45, z), (1.2, .12, 1.0), yaw=b.rng.uniform(0, 3))
    for side in range(4):
        rim_side(b, 2 + side, side)
    return p


def personal_leaf_nest():
    """A growing child's own bed: oval hollow 10 x 8, three build stages (underlayer, second portion, pressed rim)."""
    p = Prop('personal_leaf_nest')
    b = Builder(p, 4417)
    rng = b.rng
    pad(b, 1, 2, 6.8, 5.6)
    # stage 2: a second portion of leaves spread over the hollow
    for i in range(16):
        a = rng.uniform(0, math.tau)
        r = math.sqrt(rng.uniform(.05, 1)) * 1.0
        x, z = math.cos(a) * 5.2 * r, math.sin(a) * 4.1 * r
        e = min(1.0, (x / 7.0) ** 2 + (z / 6.0) ** 2)
        b.box(leaf_mat(rng), 2, (x, .12 + .75 * e + .42 + (i % 3) * .04, z), (rng.uniform(1.2, 1.8), .1, rng.uniform(1.0, 1.5)), yaw=rng.uniform(0, math.pi))
    for i in range(3):
        a = rng.uniform(0, math.tau)
        b.box(MOSS, 2, (math.cos(a) * 3.6, .78, math.sin(a) * 2.9), (1.2, .18, 1.0), yaw=rng.uniform(0, 3))
    # stage 3: the rim is pressed up around the child, then grass and a few twigs
    count = 17
    for i in range(count):
        a = math.tau * i / count + rng.uniform(-.08, .08)
        rx, rz = 6.1 + rng.uniform(-.25, .3), 5.0 + rng.uniform(-.25, .3)
        width = rng.uniform(2.6, 3.4)
        height = rng.uniform(1.7, 2.8)
        x, z = math.cos(a) * rx, math.sin(a) * rz
        b.box(leaf_mat(rng), 3, (x, rng.uniform(0, .3) + height / 2, z), (width / 2, height / 2, rng.uniform(1.0, 1.4)), yaw=-a + math.pi / 2 + rng.uniform(-.25, .25))
        if i % 2 == 0:                                   # inner slope
            x2, z2 = math.cos(a) * (rx - 1.8), math.sin(a) * (rz - 1.5)
            b.box(leaf_mat(rng), 3, (x2, .55, z2), (width / 2.4, .55, .9), yaw=-a + math.pi / 2 + rng.uniform(-.3, .3))
    for i in range(5):
        a = math.tau * (i + rng.uniform(-.3, .3)) / 5
        b.tuft(3, math.cos(a) * 5.0, math.sin(a) * 4.1, 1.5, 3.8)
    for i in range(4):
        a = math.tau * (i + rng.uniform(-.2, .2)) / 4 + .4
        b.box(LOG if i % 2 else LOG_DARK, 3, (math.cos(a) * 6.1, 2.1, math.sin(a) * 5.0), (rng.uniform(2.0, 2.8), .4, .4), yaw=-a + rng.uniform(-.8, .8) + math.pi / 2, pitch=rng.uniform(-.1, .1), grain=True)
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


WILD = {'forest_burrow': forest_burrow, 'personal_leaf_nest': personal_leaf_nest, 'bedding_bundle': bedding_bundle,
        'predator_nest': predator_nest}
