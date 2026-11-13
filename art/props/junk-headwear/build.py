"""Junk headwear: a paper box, a bottle cap and an ice-cream cone worn as stand-ins for a lost accessory, plus the item icon.

Each prop is a low-poly mesh on the care-prop kit (standards/yukkuri-library/drafts/care-props/kit.py) with its own painted
texture, origin at the contact point on the crown, +Y up, front toward -Z, 16 units per block. The meshes are shabby on
purpose: crushed, taped and water-stained cardboard with a torn flap, a bent and rusty crimped cap, a bitten wafer with dried ice cream.

Seats: YukkuriModel.renderJunkHat draws the prop in the features frame of the wearer (at rest equal to the model frame) and
applies the prop's anchor named after the runtime model. The anchor is fitted here against every runtime model with its
accessory hidden (placement fit applied, as the game loads it): scaled to the bare head's width, turned by the prop's
authored tilt and lowered until its lowest point presses SINK units into the hair. `--report` prints the contact and the
clearance in front of the eyes for each form.

Item icon: one generic 16x16 sprite (a little heap of the three) in the care-supply item style.

Run from the repository root; --check is read-only.
"""
import argparse, io, json, math, random, sys
from pathlib import Path
import numpy as np
from PIL import Image

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
sys.path.insert(0, str(ROOT / 'scripts'))
sys.path.insert(0, str(ROOT / 'standards/yukkuri-library/drafts/care-props'))
import kit  # noqa: E402
import placement_fit  # noqa: E402
from asset_export_checks import check_output, fingerprints, preserve_equivalent_png, provenance  # noqa: E402

VARIANTS = ('paper_box', 'bottle_cap', 'ice_cream_cone')
PROPS = {v: 'junk_' + v for v in VARIANTS}
TEX = 2                      # decal texels per unit
SINK = .2                    # how far the lowest point presses into the hair, in units at full size
STANDARD_HALF_WIDTH = 6.4    # bare head half width of the ordinary rounded body
# Authored wear: offset on the crown (x, z in units at full size), yaw, tilt about x (forward +) and z (to the left +).
WEAR = {
    'paper_box': dict(offset=(0.0, .3), yaw=11.0, tilt=(-3.0, 5.0)),
    'bottle_cap': dict(offset=(1.3, -.2), yaw=-20.0, tilt=(4.0, -13.0)),
    'ice_cream_cone': dict(offset=(-.3, .5), yaw=25.0, tilt=(-7.0, 6.0)),
}
H = kit.hex_of


def rgba(hex_colour, alpha=255):
    return tuple(int(hex_colour[i:i + 2], 16) for i in (1, 3, 5)) + (alpha,)


class Painter:
    """Deterministic pixel painting in the prop's decal area."""
    def __init__(self, prop, seed):
        self.img, self.rng = prop.img, random.Random(seed)

    def put(self, x, y, colour):
        if 0 <= x < self.img.width and 0 <= y < self.img.height:
            self.img.putpixel((int(x), int(y)), rgba(colour) if isinstance(colour, str) else colour)

    def rect(self, x, y, w, h, ramp, noise=.22, base=2):
        """Material area: one ramp step with sparse neighbouring-step pixels (like the kit's tiles)."""
        steps = kit._palette['ramps'][ramp] if isinstance(ramp, str) else ramp
        for j in range(h):
            for i in range(w):
                r = self.rng.random()
                step = base - 1 if r < noise / 2 else base + 1 if r < noise else base
                self.put(x + i, y + j, steps[max(0, min(len(steps) - 1, step))])

    def blot(self, cx, cy, rx, ry, colour, area, chance=1.0):
        x0, y0, w, h = area
        for y in range(y0, y0 + h):
            for x in range(x0, x0 + w):
                d = ((x + .5 - cx) / rx) ** 2 + ((y + .5 - cy) / ry) ** 2
                if d <= 1 + self.rng.uniform(-.35, .1) and self.rng.random() < chance:
                    self.put(x, y, colour)


def flat_normal(P):
    return kit.unit(kit.cross(kit.sub(P[1], P[0]), kit.sub(P[2], P[0])))


def add_tri(part, P, uvs, normal=None):
    """Raw triangle: positions already deformed, uvs in texture pixels; flat normal from the winding."""
    n = normal or flat_normal(P)
    if kit.length(kit.cross(kit.sub(P[1], P[0]), kit.sub(P[2], P[0]))) < 1e-7:
        return
    part['tris'].append([(list(P[i]), n, uvs[i]) for i in range(3)])


def grid_face(part, corner, du, dv, nu, nv, uv_of, deform, outward):
    """A planar rest face (corner + s*du + t*dv) split nu x nv, each vertex deformed after its uv is taken."""
    pts = [[[corner[k] + du[k] * i / nu + dv[k] * j / nv for k in range(3)] for i in range(nu + 1)] for j in range(nv + 1)]
    for j in range(nv):
        for i in range(nu):
            quad = [pts[j][i], pts[j][i + 1], pts[j + 1][i + 1], pts[j + 1][i]]
            for tri in ((0, 1, 2), (0, 2, 3)):
                rest = [quad[k] for k in tri]
                P = [deform(p) for p in rest]
                uv = [uv_of(p) for p in rest]
                if kit.dot(flat_normal(P), outward) < 0:
                    P, uv = [P[0], P[2], P[1]], [uv[0], uv[2], uv[1]]
                add_tri(part, P, uv)


# ---------------------------------------------------------------- paper box
BOX = dict(hx=3.6, hz=2.8, h=4.2, wall=.35)


def paper_box():
    prop = kit.Prop(PROPS['paper_box'], wide=True, noise=False)
    paint = Painter(prop, 'paper_box')
    hx, hz, h, t = BOX['hx'], BOX['hz'], BOX['h'], BOX['wall']
    card = kit._palette['ramps']['cardboard']
    print_blue = ['#3f5f78', '#5f8098', '#8eaabb']                    # faded printing
    tape = ['#b8a26a', '#d8c690', '#ece0b4']                          # old packing tape
    # Decal areas (x, y, w, h) at TEX texels per unit.
    A = dict(front=(16, 0, 15, 9), back=(32, 0, 15, 9), left=(16, 10, 12, 9), right=(29, 10, 12, 9), top=(16, 20, 15, 12),
             flap=(32, 20, 10, 6), inner=(48, 0, 12, 12))
    for key in ('front', 'back', 'left', 'right', 'top'):
        paint.rect(*A[key], card, noise=.03)
    paint.rect(*A['inner'], card, base=1, noise=.1)
    paint.rect(*A['flap'], card, base=2, noise=.03)
    # The old bottom, now on top: the two flaps' seam under a strip of packing tape, one end peeled up.
    tx0, ty0, tw, th = A['top']
    for x in range(tx0, tx0 + tw):
        paint.put(x, ty0 + 5, tape[0])
        paint.put(x, ty0 + 6, tape[1] if x < tx0 + tw - 3 else card[1])
        paint.put(x, ty0 + 7, tape[2] if x < tx0 + tw - 3 else card[0])
    for x in range(tx0 + 2, tx0 + tw - 2, 3):
        paint.put(x, ty0 + 6, tape[2])
    # Printed sides: a faded blue sweets label with clear edges on the front, a thin printed line round the box.
    fx0, fy0, fw, fh = A['front']
    for y in range(fy0 + 2, fy0 + 6):
        for x in range(fx0 + 3, fx0 + 11):
            edge = y in (fy0 + 2, fy0 + 5) or x in (fx0 + 3, fx0 + 10)
            paint.put(x, y, print_blue[0] if edge else print_blue[1])
    for x, y in ((fx0 + 5, fy0 + 3), (fx0 + 6, fy0 + 3), (fx0 + 8, fy0 + 3), (fx0 + 5, fy0 + 4), (fx0 + 7, fy0 + 4), (fx0 + 8, fy0 + 4)):
        paint.put(x, y, H('paper', 2))
    for x, y in ((fx0 + 9, fy0 + 4), (fx0 + 9, fy0 + 3), (fx0 + 10, fy0 + 4)):
        paint.put(x, y, card[2])                                      # label scuffed off at one corner
    for key in ('front', 'back', 'left', 'right'):
        x0, y0, w, hh = A[key]
        for x in range(x0, x0 + w):
            if paint.rng.random() < .85:
                paint.put(x, y0 + 1, print_blue[2])
            paint.put(x, y0 + hh - 1, card[1])                      # grime along the open edge
    # A water stain, the crease of the crushed corner and a scuff.
    paint.blot(A['left'][0] + 4, A['left'][1] + 5, 2.6, 2.2, card[1], A['left'])
    paint.blot(A['back'][0] + 10, A['back'][1] + 3, 2.2, 1.6, card[1], A['back'])
    paint.blot(tx0 + 4, ty0 + 9, 2.2, 1.6, card[1], A['top'])
    for i in range(5):
        paint.put(fx0 + 14 - i, fy0 + i, card[0])
        paint.put(tx0 + 14 - i // 2, ty0 + i, card[1])
    flx0, fly0, flw, flh = A['flap']
    for x in range(flx0, flx0 + flw):
        paint.put(x, fly0, H('paper', 3))                            # torn edge: the light paper core
        if paint.rng.random() < .5:
            paint.put(x, fly0 + 1, H('paper', 2))
        paint.put(x, fly0 + flh - 1, card[1])

    def deform(p):
        x, y, z = p
        u = max(0.0, x / hx); v = max(0.0, -z / hz)
        y -= .9 * (y / h) * u * v                                    # crushed front-right top corner
        x -= .25 * (y / h) * u * v
        if y > h - t - .01:                                          # the old bottom sags a little
            y -= .18 * max(0.0, 1 - (x / hx) ** 2) * max(0.0, 1 - (z / hz) ** 2)
        return [x + .12 * y / h, y, z]

    def planar(area, ax, ay, origin_a, origin_b, sa, sb):
        x0, y0 = area[0], area[1]
        return lambda p: (x0 + (p[ax] - origin_a) * sa * TEX, y0 + (p[ay] - origin_b) * sb * TEX)

    # Every face uses the same 3 x 3 split so shared edges keep identical vertices after the crush (no cracks).
    outer = prop.part('box')
    grid_face(outer, [-hx, 0, -hz], [2 * hx, 0, 0], [0, h, 0], 3, 3, planar(A['front'], 0, 1, -hx, h, 1, -1), deform, [0, 0, -1])
    grid_face(outer, [hx, 0, hz], [-2 * hx, 0, 0], [0, h, 0], 3, 3, planar(A['back'], 0, 1, hx, h, -1, -1), deform, [0, 0, 1])
    grid_face(outer, [-hx, 0, hz], [0, 0, -2 * hz], [0, h, 0], 3, 3, planar(A['left'], 2, 1, hz, h, -1, -1), deform, [-1, 0, 0])
    grid_face(outer, [hx, 0, -hz], [0, 0, 2 * hz], [0, h, 0], 3, 3, planar(A['right'], 2, 1, -hz, h, 1, -1), deform, [1, 0, 0])
    grid_face(outer, [-hx, h, -hz], [2 * hx, 0, 0], [0, 0, 2 * hz], 3, 3, planar(A['top'], 0, 2, -hx, -hz, 1, 1), deform, [0, 1, 0])
    # Inside (seen only from below or past the flap) and the board edge round the open end.
    ix, iz, ih = hx - t, hz - t, h - t
    inner_uv = lambda p: (A['inner'][0] + (p[0] + ix) * 1.4, A['inner'][1] + (p[2] + iz) * 1.8 + p[1] * .3)
    inside = prop.part('box_inside')
    grid_face(inside, [-ix, 0, -iz], [2 * ix, 0, 0], [0, ih, 0], 3, 3, inner_uv, deform, [0, 0, 1])
    grid_face(inside, [ix, 0, iz], [-2 * ix, 0, 0], [0, ih, 0], 3, 3, inner_uv, deform, [0, 0, -1])
    grid_face(inside, [-ix, 0, iz], [0, 0, -2 * iz], [0, ih, 0], 3, 3, inner_uv, deform, [1, 0, 0])
    grid_face(inside, [ix, 0, -iz], [0, 0, 2 * iz], [0, ih, 0], 3, 3, inner_uv, deform, [-1, 0, 0])
    grid_face(inside, [-ix, ih, -iz], [2 * ix, 0, 0], [0, 0, 2 * iz], 3, 3, inner_uv, deform, [0, -1, 0])
    edge = prop.swatch_uv('cardboard', 1)
    ring = [(-hx, -hz), (hx, -hz), (hx, hz), (-hx, hz)]
    iring = [(-ix, -iz), (ix, -iz), (ix, iz), (-ix, iz)]
    for k in range(4):
        a, b, ia, ib = ring[k], ring[(k + 1) % 4], iring[k], iring[(k + 1) % 4]
        P = [deform([a[0], 0, a[1]]), deform([b[0], 0, b[1]]), deform([ib[0], 0, ib[1]]), deform([ia[0], 0, ia[1]])]
        add_tri(inside, [P[0], P[2], P[1]], [edge] * 3, [0, -1, 0])
        add_tri(inside, [P[0], P[3], P[2]], [edge] * 3, [0, -1, 0])
    # The old lid's left flap, torn, sticking out sideways and up off the head.
    flaps = prop.part('box_flaps')
    fuv = lambda s, r: (flx0 + s * (flw - 1), fly0 + r * (flh - 1))
    a, b = deform([-hx, 0, -2.4]), deform([-hx, 0, 2.2])
    out = kit.unit([-1, .85, .05])
    tear = [(0.0, .55), (.22, .95), (.4, .7), (.58, 1.0), (.8, .62), (1.0, .4)]
    edge_pts = [([a[k] + (b[k] - a[k]) * s + out[k] * 2.2 * cut for k in range(3)], fuv(s, 1 - cut)) for s, cut in tear]
    for i in range(len(tear) - 1):
        s0, s1 = tear[i][0], tear[i + 1][0]
        h0 = [a[k] + (b[k] - a[k]) * s0 for k in range(3)]
        h1 = [a[k] + (b[k] - a[k]) * s1 for k in range(3)]
        for P, uv in (([h0, h1, edge_pts[i + 1][0]], [fuv(s0, 1), fuv(s1, 1), edge_pts[i + 1][1]]),
                      ([h0, edge_pts[i + 1][0], edge_pts[i][0]], [fuv(s0, 1), edge_pts[i + 1][1], edge_pts[i][1]])):
            if flat_normal(P)[1] < 0:                                # one face, lit from above; drawn on both sides
                P, uv = [P[0], P[2], P[1]], [uv[0], uv[2], uv[1]]
            add_tri(flaps, P, uv)
    return prop


# ---------------------------------------------------------------- bottle cap
CAP = dict(flutes=14, r_out=2.95, r_in=2.6, top_r=2.45, skirt_h=1.25, top_y=1.42, dome=.14, wall=.15)


def bottle_cap():
    prop = kit.Prop(PROPS['bottle_cap'], wide=True, noise=False)
    paint = Painter(prop, 'bottle_cap')
    c = CAP
    n = c['flutes'] * 2
    red = ['#6b2a2a', '#8e3a35', '#a8524a', '#c27a6a']
    steel = kit._palette['ramps']['steel']
    rust = ['#5a3418', '#7a4a22', '#9a6430']
    # Areas: printed top (3 texels per unit), skirt strip, liner.
    TOP = (16, 0, 14, 14)
    SKIRT = (16, 16, 48, 4)
    LINER = (32, 0, 12, 12)
    tx0, ty0, tw, th = TOP
    cx, cy = tx0 + tw / 2, ty0 + th / 2
    for y in range(ty0, ty0 + th):
        for x in range(tx0, tx0 + tw):
            d = math.hypot(x + .5 - cx, y + .5 - cy)
            col = red[2] if d < 4.2 else red[1]
            if 4.3 <= d < 5.2:
                col = H('paper', 2)                                  # faded white ring
            if d >= 6.2:
                col = steel[1]
            if paint.rng.random() < .16:
                col = red[3] if d < 6.2 else steel[2]
            paint.put(x, y, col)
    for x, y in ((cx - 2, cy - 1), (cx - 1, cy - 1), (cx, cy - 1), (cx + 1, cy - 1), (cx - 1, cy + 1), (cx, cy + 1)):
        paint.put(x, y, H('paper', 1))                               # worn-off brand letters
    paint.blot(cx + 3, cy + 2.5, 2.2, 1.8, rust[1], TOP)
    paint.blot(cx + 3.4, cy + 2.8, 1.2, 1.0, rust[0], TOP)
    paint.blot(cx - 3.5, cy + 3, 1.3, 1.1, rust[2], TOP)
    sx0, sy0, sw, sh = SKIRT
    for x in range(sx0, sx0 + sw):
        flute = (x - sx0) % 3
        for y in range(sy0, sy0 + sh):
            col = red[1] if flute else steel[2]                      # paint chipped off the crimp ridges
            if y == sy0 + sh - 1:
                col = steel[1] if flute else steel[3]
            if paint.rng.random() < .12:
                col = rust[1]
            paint.put(x, y, col)
    paint.rect(*LINER, kit._palette['ramps']['straw'], base=1, noise=.3)
    paint.blot(LINER[0] + 7, LINER[1] + 6, 2.5, 2, H('straw', 0), LINER)

    def bend(p):
        x, y, z = p
        if x > .5:                                                   # pried up on one side by the opener
            y += (x - .5) * .3
            x -= (x - .5) * .05
        return [x, y, z]

    def ring(radius_fn, y, k_shift=0.0):
        return [[radius_fn(k) * math.cos(2 * math.pi * (k + k_shift) / n), y, radius_fn(k) * math.sin(2 * math.pi * (k + k_shift) / n)]
                for k in range(n)]
    bottom = ring(lambda k: c['r_out'] if k % 2 == 0 else c['r_in'], 0.0)
    bottom[4] = [bottom[4][0] * 1.12, .05, bottom[4][2] * 1.12]      # one bent-out crimp
    upper = ring(lambda k: c['top_r'] + (.1 if k % 2 == 0 else 0), c['skirt_h'])
    inner_bottom = [[p[0] * (1 - c['wall'] / 2.4), p[1], p[2] * (1 - c['wall'] / 2.4)] for p in bottom]
    inner_upper = [[p[0] * .93, c['skirt_h'] - c['wall'], p[2] * .93] for p in upper]
    skirt = prop.part('cap_skirt')
    for k in range(n):
        k2 = (k + 1) % n
        u0, u1 = sx0 + sw * k / n, sx0 + sw * (k + 1) / n
        quad = [bottom[k], bottom[k2], upper[k2], upper[k]]
        uv = [(u0, sy0 + sh - .01), (u1, sy0 + sh - .01), (u1, sy0 + .01), (u0, sy0 + .01)]
        P = [bend(p) for p in quad]
        mid = [sum(p[i] for p in P) / 4 for i in range(3)]
        out = [mid[0], 0, mid[2]]
        for tri in ((0, 1, 2), (0, 2, 3)):
            T, U = [P[i] for i in tri], [uv[i] for i in tri]
            if kit.dot(flat_normal(T), out) < 0:
                T, U = [T[0], T[2], T[1]], [U[0], U[2], U[1]]
            add_tri(skirt, T, U)
        # Inside of the skirt and the rolled bottom edge.
        Q = [bend(p) for p in (inner_bottom[k], inner_bottom[k2], inner_upper[k2], inner_upper[k])]
        for tri in ((0, 1, 2), (0, 2, 3)):
            T = [Q[i] for i in tri]
            if kit.dot(flat_normal(T), out) > 0:
                T = [T[0], T[2], T[1]]
            add_tri(skirt, T, [prop.swatch_uv('steel', 1)] * 3)
        E = [P[0], P[1], Q[1], Q[0]]
        add_tri(skirt, [E[0], E[2], E[1]], [prop.swatch_uv('steel', 3)] * 3, [0, -1, 0])
        add_tri(skirt, [E[0], E[3], E[2]], [prop.swatch_uv('steel', 3)] * 3, [0, -1, 0])
    top = prop.part('cap_top')
    scale = 3.0
    top_uv = lambda p: (cx + p[0] * scale, cy + p[2] * scale)
    for k in range(n):
        k2 = (k + 1) % n
        a = [upper[k][0], c['top_y'], upper[k][2]]
        b = [upper[k2][0], c['top_y'], upper[k2][2]]
        ma = [a[0] * .55, c['top_y'] + c['dome'] * .7, a[2] * .55]
        mb = [b[0] * .55, c['top_y'] + c['dome'] * .7, b[2] * .55]
        for rest in ((ma, mb, [0, c['top_y'] + c['dome'], 0]), (a, b, mb), (a, mb, ma)):
            T = [bend(p) for p in rest]
            U = [top_uv(p) for p in rest]
            if flat_normal(T)[1] < 0:
                T, U = [T[0], T[2], T[1]], [U[0], U[2], U[1]]
            add_tri(top, T, U)
        # the step from the skirt top up to the printed face
        S = [bend(upper[k]), bend(upper[k2]), bend(b), bend(a)]
        out = [S[0][0], 0, S[0][2]]
        for tri in ((0, 1, 2), (0, 2, 3)):
            T = [S[i] for i in tri]
            if kit.dot(flat_normal(T), out) < 0:
                T = [T[0], T[2], T[1]]
            add_tri(top, T, [prop.swatch_uv('steel', 2)] * 3)
        # liner underneath
        L = [bend(inner_upper[k]), bend(inner_upper[k2]), bend([0, c['skirt_h'] - c['wall'], 0])]
        luv = lambda p: (LINER[0] + 6 + p[0] * 2.2, LINER[1] + 6 + p[2] * 2.2)
        T, U = L, [luv(inner_upper[k]), luv(inner_upper[k2]), luv([0, 0, 0])]
        if flat_normal(T)[1] > 0:
            T, U = [T[0], T[2], T[1]], [U[0], U[2], U[1]]
        add_tri(top, T, U)
    return prop


# ---------------------------------------------------------------- ice-cream cone
CONE = dict(seg=12, rim_r=2.35, rim_h=.8, height=7.4, tip_r=.25, bite=-50.0)


def ice_cream_cone():
    prop = kit.Prop(PROPS['ice_cream_cone'], wide=True, noise=False)
    paint = Painter(prop, 'ice_cream_cone')
    c = CONE
    n = c['seg']
    wafer = ['#8a5428', '#b0783c', '#cf9a52', '#e6bb72', '#f2d69a']
    pink = ['#a8546a', '#cd8191', '#e8b4bd']
    # The wall unrolls exactly (a cone is developable): a sector about the apex, so the waffle grid keeps one size
    # from the open end to the tip. s = slant distance from the apex, phi = unrolled angle.
    R0, Y0 = c['rim_r'] - .1, c['rim_h']
    apex = Y0 + (c['height'] - Y0) * R0 / (R0 - c['tip_r'])
    L = math.hypot(R0, apex - Y0)
    D = 2.5                                                          # texels per unit on the wall
    CX, CY = 40, 0.5
    def wall_uv(theta, y):
        r = R0 * (apex - y) / (apex - Y0)
        phi = (theta - math.pi) * R0 / L
        return (CX + r / R0 * L * D * math.sin(phi), CY + r / R0 * L * D * math.cos(phi))
    WALL = (16, 0, 48, 20)
    RIM = (16, 22, 48, 4)
    wx0, wy0, ww, wh = WALL
    edge = L * D
    for y in range(wy0, wy0 + wh):
        for x in range(wx0, wx0 + ww):
            a, b = (x + y) % 6, (x - y) % 6                          # waffle diamonds, lit from the upper left
            col = wafer[1] if a == 0 or b == 0 else wafer[3] if a == 1 or b == 5 else wafer[2]
            if paint.rng.random() < .04:
                col = wafer[4]
            s = math.hypot(x + .5 - CX, y + .5 - CY)
            # Dried strawberry ice cream along the open end, with the old runs it left when the cone was held the right
            # way up (they now point up the wall toward the tip).
            if s > edge - 1.0 - (.7 if paint.rng.random() < .35 else 0):
                col = pink[1] if paint.rng.random() < .7 else pink[0]
            paint.put(x, y, col)
    for angle, length in ((-50, 4.5), (-46, 2.5), (-12, 5.5), (21, 3.5), (47, 4.5)):
        a = math.radians(angle)
        for k in range(int(length * 2)):
            s = edge - 1 - k / 2
            paint.put(CX + s * math.sin(a), CY + s * math.cos(a), pink[2] if k >= length * 2 - 2 else pink[1])
    for k in range(7):                                               # a crack and a grubby thumb mark
        paint.put(CX - 4 + k // 3, CY + 6 + k, wafer[0])
    paint.blot(CX + 7, CY + 9, 2, 1.5, wafer[0], WALL, .7)
    rx0, ry0, rw, rh = RIM
    for y in range(ry0, ry0 + rh):
        for x in range(rx0, rx0 + rw):
            col = wafer[3] if y in (ry0, ry0 + 2) else wafer[2]
            if y == ry0 + rh - 1 and paint.rng.random() < .5 or paint.rng.random() < .12:
                col = pink[1] if paint.rng.random() < .6 else pink[0]
            paint.put(x, y, col)

    bite = math.radians(c['bite'])
    def notch(theta):
        d = abs((theta - bite + math.pi) % (2 * math.pi) - math.pi)
        return max(0.0, 1 - d / (2 * math.pi / n * 1.2))
    def bent(p):                                                     # soft tip pushed over to one side
        x, y, z = p
        k = max(0.0, (y - 3) / (c['height'] - 3)) ** 2
        return [x + .55 * k, y, z - .2 * k]
    rows = [(c['rim_r'], 0.0, 'rim'), (c['rim_r'] + .15, .45, 'rim'), (c['rim_r'] - .1, c['rim_h'], 'wall')]
    steps = 5
    for i in range(1, steps + 1):
        f = i / steps
        rows.append((c['rim_r'] - .1 + (c['tip_r'] - c['rim_r'] + .1) * f, c['rim_h'] + (c['height'] - c['rim_h']) * f, 'wall'))
    loops = []
    for r, y, kind in rows:
        loop = []
        for k in range(n + 1):
            theta = 2 * math.pi * k / n
            lift = 1.6 * notch(theta) if y < c['rim_h'] + .01 else 0
            lift *= 1 - y / (c['rim_h'] * 1.6)
            loop.append(([r * math.cos(theta), y + lift, r * math.sin(theta)], k / n))
        loops.append((loop, y, kind))
    part = prop.part('cone')
    for (l0, y0, k0), (l1, y1, k1) in zip(loops, loops[1:]):
        for k in range(n):
            P = [l0[k][0], l0[k + 1][0], l1[k + 1][0], l1[k][0]]
            if k0 == k1 == 'wall':
                th = lambda j: 2 * math.pi * j / n
                U = [wall_uv(th(k), y0), wall_uv(th(k + 1), y0), wall_uv(th(k + 1), y1), wall_uv(th(k), y1)]
            else:
                v0 = ry0 + (rh - .01) * (1 - y0 / c['rim_h']); v1 = ry0 + (rh - .01) * (1 - y1 / c['rim_h'])
                U = [(rx0 + rw * l0[k][1], v0), (rx0 + rw * l0[k + 1][1], v0), (rx0 + rw * l1[k + 1][1], v1), (rx0 + rw * l1[k][1], v1)]
            Q = [bent(p) for p in P]
            mid = [sum(p[i] for p in Q) / 4 for i in range(3)]
            out = [mid[0] - .55 * max(0.0, (mid[1] - 3) / (c['height'] - 3)) ** 2, 0, mid[2]]
            for tri in ((0, 1, 2), (0, 2, 3)):
                T, UV = [Q[i] for i in tri], [U[i] for i in tri]
                if kit.dot(flat_normal(T), out) < 0:
                    T, UV = [T[0], T[2], T[1]], [UV[0], UV[2], UV[1]]
                add_tri(part, T, UV)
    # Broken-off tip and the dark inside seen through the open end and the bite.
    tip_ring = [bent(p) for p, _ in loops[-1][0][:n]]
    tip = bent([0, c['height'] + .12, 0])
    for k in range(n):
        T = [tip_ring[k], tip_ring[(k + 1) % n], tip]
        if flat_normal(T)[1] < 0:
            T = [T[0], T[2], T[1]]
        add_tri(part, T, [prop.swatch_uv('pellet', 2)] * 3)
    inside = prop.part('cone_inside')
    for (l0, y0, k0), (l1, y1, k1) in zip(loops, loops[1:]):
        for k in range(n):
            P = [[p[0] * .9, p[1], p[2] * .9] for p in (l0[k][0], l0[k + 1][0], l1[k + 1][0], l1[k][0])]
            Q = [bent(p) for p in P]
            mid = [sum(p[i] for p in Q) / 4 for i in range(3)]
            for tri in ((0, 1, 2), (0, 2, 3)):
                T = [Q[i] for i in tri]
                if kit.dot(flat_normal(T), [mid[0], 0, mid[2]]) > 0:
                    T = [T[0], T[2], T[1]]
                add_tri(inside, T, [prop.swatch_uv('pellet', 1)] * 3)
    for k in range(n):                                               # wafer thickness at the open end
        a, b = loops[0][0][k][0], loops[0][0][k + 1][0]
        P = [bent(a), bent(b), bent([b[0] * .9, b[1], b[2] * .9]), bent([a[0] * .9, a[1], a[2] * .9])]
        add_tri(inside, [P[0], P[2], P[1]], [prop.swatch_uv('pellet', 3)] * 3)
        add_tri(inside, [P[0], P[3], P[2]], [prop.swatch_uv('pellet', 3)] * 3)
    return prop


BUILDERS = {'paper_box': paper_box, 'bottle_cap': bottle_cap, 'ice_cream_cone': ice_cream_cone}


# ---------------------------------------------------------------- item icon
ICON_LEGEND = {
    'c': '#5e3d1e', 'C': '#8a5f33', 'b': '#b0844d', 'B': '#cfa86c',                      # cardboard (darkest is the outline)
    't': '#d8c690', 'T': '#ece0b4', 'l': '#3f5f78', 'L': '#5f8098',                      # packing tape, faded blue label
    'w': '#8a5428', 'W': '#b0783c', 'f': '#cf9a52', 'F': '#e6bb72', 'd': '#5a3418',      # wafer, the empty inside
    'k': '#a8546a', 'K': '#cd8191',                                                      # dried ice cream
    's': '#4f5d60', 'S': '#7d8e90', 'e': '#b7c3c4',                                      # cap steel
    'x': '#8e2f2a', 'X': '#c8463c', 'Y': '#e0645a',                                      # cap paint (white print uses the tape tone)
}
ICON = [
    '...........wwww.',
    '..........wKkKKw',
    '.........wKddddw',
    '.........wkdddKw',
    '........wFKKKKw.',
    '........wfWFfw..',
    '.......wFfWFw...',
    '.ccccc.wWfFw....',
    'cTtttTcwFWw.....',
    'cBBBBBCcww......',
    'cbllllCc........',
    'cblLLlCc.sssss..',
    'cbllllCcsYYXXXs.',
    'cbbbbbCsXTTXXXxs',
    '.cccccsseSeSeSes',
    '.......sssssss..',
]


def icon():
    image = Image.new('RGBA', (16, 16), (0, 0, 0, 0))
    for y, row in enumerate(ICON):
        for x, ch in enumerate(row):
            if ch in ICON_LEGEND:
                image.putpixel((x, y), rgba(ICON_LEGEND[ch]))
    stream = io.BytesIO(); image.save(stream, format='PNG')
    return stream.getvalue()


# ---------------------------------------------------------------- seats
def prefixes():
    out = {}
    for name in ('living.json', 'subspecies.json'):
        data = json.loads((ASSETS / 'yukkuri_presentation' / name).read_text(encoding='utf-8'))
        for key, value in (data['variants'] if 'variants' in data else data).items():
            out[key] = tuple(value['accessory_prefixes'])
    return out


def runtime_models():
    return sorted(p.stem for p in (ASSETS / 'yukkuri').glob('*.json') if p.stem != 'badge_mounts')


SKIP = ('face_', 'anatomy', 'peni', 'mamu', 'puff_')


def bare_head(model_id, hidden):
    """Triangles of the fitted model as the game draws it with the accessory gone (faces and anatomy left out)."""
    data = placement_fit.fitted(ASSETS / 'yukkuri' / f'{model_id}.json')
    v = np.array(data['vertices'], dtype=np.float64).reshape(-1, 8)[:, :3]
    keep = []
    for part in data['parts']:
        name = part['name']
        if name.startswith(hidden) or name.startswith(SKIP) or part.get('anatomy'):
            continue
        keep.append(v[part['start']:part['start'] + part['count']])
    return np.concatenate(keep).reshape(-1, 3, 3)


def heights(tris, points):
    """Highest surface of the triangles straight above/below each (x, z) point; -inf where nothing is."""
    top = tris[:, :, 1].max(1)
    out = np.full(len(points), -np.inf)
    a, b, c = tris[:, 0], tris[:, 1], tris[:, 2]
    det = (b[:, 2] - c[:, 2]) * (a[:, 0] - c[:, 0]) + (c[:, 0] - b[:, 0]) * (a[:, 2] - c[:, 2])
    ok = np.abs(det) > 1e-9
    a, b, c, det, top = a[ok], b[ok], c[ok], det[ok], top[ok]
    for i, (x, z) in enumerate(points):
        w0 = ((b[:, 2] - c[:, 2]) * (x - c[:, 0]) + (c[:, 0] - b[:, 0]) * (z - c[:, 2])) / det
        w1 = ((c[:, 2] - a[:, 2]) * (x - c[:, 0]) + (a[:, 0] - c[:, 0]) * (z - c[:, 2])) / det
        w2 = 1 - w0 - w1
        hit = (w0 >= -1e-6) & (w1 >= -1e-6) & (w2 >= -1e-6)
        if hit.any():
            out[i] = (w0[hit] * a[hit, 1] + w1[hit] * b[hit, 1] + w2[hit] * c[hit, 1]).max()
    return out


def rotation(rx, ry, rz):
    """Anchor.apply order: translate, rotate Z, then Y, then X, then scale (right-handed degrees)."""
    a, b, g = (math.radians(d) for d in (rx, ry, rz))
    Rx = np.array([[1, 0, 0], [0, math.cos(a), -math.sin(a)], [0, math.sin(a), math.cos(a)]])
    Ry = np.array([[math.cos(b), 0, math.sin(b)], [0, 1, 0], [-math.sin(b), 0, math.cos(b)]])
    Rz = np.array([[math.cos(g), -math.sin(g), 0], [math.sin(g), math.cos(g), 0], [0, 0, 1]])
    return Rz @ Ry @ Rx


def head_half_width(tris, crown):
    pts = tris.reshape(-1, 3)
    band = pts[(pts[:, 1] > crown - 3.5) & (pts[:, 1] < crown - .5)]
    return float(np.abs(band[:, 0]).max()) if len(band) else STANDARD_HALF_WIDTH


def fit(model_id, hidden, meshes, anchor):
    tris = bare_head(model_id, hidden)
    near = (np.abs(tris[:, :, 0] - anchor[0]).min(1) < 10) & (np.abs(tris[:, :, 2] - anchor[2]).min(1) < 10)
    tris = tris[near]
    crown = heights(tris, [(anchor[0], anchor[2])])[0]
    tris = tris[tris[:, :, 1].max(1) > crown - 8]
    scale = round(min(1.0, max(.3, head_half_width(tris, crown) / STANDARD_HALF_WIDTH)), 3)
    seats, report = {}, {}
    for variant, verts in meshes.items():
        wear = WEAR[variant]
        R = rotation(wear['tilt'][0], wear['yaw'], wear['tilt'][1])
        local = (R @ (verts * scale).T).T
        ox, oz = anchor[0] + wear['offset'][0] * scale, anchor[2] + wear['offset'][1] * scale
        xz = local[:, [0, 2]] + [ox, oz]
        hair = heights(tris, xz)
        under = np.isfinite(hair)
        y = float(np.max(hair[under] - local[under, 1]) - SINK * scale)
        gaps = local[:, 1] + y - hair
        low = local[:, 1] < local[:, 1].min() + .4 * scale
        contact = gaps[under & low]
        world = local + [ox, y, oz]
        front = world[(world[:, 2] < -3.0 * scale) & (world[:, 1] < y + .5 * scale)]
        seats[variant] = {'position': [round(ox, 4), round(y, 4), round(oz, 4)],
                          'rotation': [wear['tilt'][0], wear['yaw'], wear['tilt'][1]], 'scale': scale}
        report[variant] = dict(crown=round(float(crown), 2), seat=round(y, 2), scale=scale,
                               contact_min=round(float(contact.min()), 2) if len(contact) else None,
                               contact_max=round(float(contact.max()), 2) if len(contact) else None,
                               overhang=int((~under).sum()), front_low=len(front))
    return seats, report


def mesh_vertices(mesh):
    return np.unique(np.array(mesh['vertices'], dtype=np.float64).reshape(-1, 8)[:, :3], axis=0)


# ---------------------------------------------------------------- outputs
def dumps(data):
    return (json.dumps(data, ensure_ascii=False, separators=(',', ':')) + '\n').encode('utf-8')


def outputs(report=None):
    previous = provenance(HERE / 'provenance.json')
    props = {v: BUILDERS[v]() for v in VARIANTS}
    meshes = {v: p.mesh() for v, p in props.items()}
    anchor = json.loads((ASSETS / 'yukkuri_presentation/hat_vessel.json').read_text(encoding='utf-8'))['anchor_worn']
    hidden = prefixes()
    vertices = {v: mesh_vertices(m) for v, m in meshes.items()}
    for model_id in runtime_models():
        seats, rows = fit(model_id, hidden[model_id], vertices, anchor)
        for v in VARIANTS:
            meshes[v].setdefault('anchors', {})[model_id] = seats[v]
        if report is not None:
            report[model_id] = rows
    result, index = {}, {}
    for v in VARIANTS:
        name = PROPS[v]
        mesh = dict(meshes[v])
        png = props[v].png()
        result[ASSETS / f'yukkuri_props/{name}.json'] = dumps(mesh)
        result[ASSETS / f'textures/props/{name}.png'] = preserve_equivalent_png(ASSETS / f'textures/props/{name}.png', png)
        result[HERE / 'assembled' / f'{name}.bbmodel'] = dumps(props[v].bbmodel())
        index[name] = {'bounds': mesh['bounds'], 'triangles': len(mesh['vertices']) // 24}
    result[ASSETS / 'yukkuri_props/junk-index.json'] = dumps(index)
    sprite = icon()
    result[HERE / 'junk_headwear.png'] = preserve_equivalent_png(HERE / 'junk_headwear.png', sprite)
    result[ASSETS / 'textures/item/junk_headwear.png'] = preserve_equivalent_png(ASSETS / 'textures/item/junk_headwear.png', sprite)
    result[ASSETS / 'models/item/junk_headwear.json'] = (json.dumps(
        {'parent': 'minecraft:item/generated', 'textures': {'layer0': 'mcyukkuri:item/junk_headwear'}}, indent=2) + '\n').encode('utf-8')
    inputs = [Path(__file__).resolve(), ROOT / 'standards/yukkuri-library/drafts/care-props/kit.py', ROOT / 'art/props/palette.json',
              ASSETS / 'yukkuri_presentation/placement_fit.json', ASSETS / 'yukkuri_presentation/living.json',
              ASSETS / 'yukkuri_presentation/subspecies.json', ASSETS / 'yukkuri_presentation/hat_vessel.json',
              *[ASSETS / 'yukkuri' / f'{m}.json' for m in runtime_models()]]
    result[HERE / 'provenance.json'] = (json.dumps({
        'status': 'pending_user_confirmation', 'source': 'art/props/junk-headwear/build.py',
        'inputs': fingerprints(ROOT, {p: p.read_bytes() for p in inputs}, previous.get('inputs', {})),
        'outputs': fingerprints(ROOT, {p: d for p, d in result.items()}, previous.get('outputs', {}))}, indent=2) + '\n').encode('utf-8')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__.split('\n')[0])
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--report', action='store_true', help='print the seat contact and front clearance per form')
    args = parser.parse_args()
    rows = {} if args.report else None
    changed = []
    for path, data in outputs(rows).items():
        if not check_output(path, data):
            changed.append(path.relative_to(ROOT).as_posix())
            if not args.check:
                path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(data)
    if rows is not None:
        for model_id, row in rows.items():
            print(model_id, json.dumps(row, ensure_ascii=False))
    if args.check and changed:
        raise SystemExit('Stale junk headwear exports: ' + ', '.join(changed))
    print(f'Junk headwear: {len(VARIANTS)} props, {len(runtime_models())} seats each; {len(changed)} files ' + ('differ' if args.check else 'written'))


if __name__ == '__main__':
    main()
