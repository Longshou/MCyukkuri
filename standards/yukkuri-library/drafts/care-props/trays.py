"""Toilets and floor mats in the bowl's cream-ceramic language; mats are thin boxes with a tiled 1-texel-per-unit top."""
import math
from kit import Prop, Band, Planar, colour, fill, paint, deterministic, rrect_points, ring_wall, unit, mul, sub, add

C = lambda ramp, step: (ramp, step)
MAT_AT = (16, 0)          # 16x16 texel mat top


def tray_poly(hx, hz, c, gap):
    """Chamfered rectangle with extra points on the front (-Z) edge so a gap of half-width `gap` can be left open."""
    pts = rrect_points(hx, hz, c)
    pts = pts[:7] + [(-gap, -hz), (gap, -hz)] + pts[7:]      # front edge: (-(hx-c),-hz) -> (-gap,-hz) -> (gap,-hz) -> (hx-c,-hz)
    return pts


GAP_EDGE = 7            # edge from (-gap,-hz) to (gap,-hz)


def prism(p, part, poly, y0, y1, side, top, bottom):
    n = len(poly)
    for k in range(n):
        a, b = poly[k], poly[(k + 1) % n]
        nrm = unit([b[1] - a[1], 0, -(b[0] - a[0])])
        p.quad(part, [[a[0], y0, a[1]], [b[0], y0, b[1]], [b[0], y1, b[1]], [a[0], y1, a[1]]], side, normal=nrm)
    centre = [sum(q[0] for q in poly) / n, 0, sum(q[1] for q in poly) / n]
    for k in range(n):
        a, b = poly[k], poly[(k + 1) % n]
        p.tri(part, [[centre[0], y1, centre[2]], [a[0], y1, a[1]], [b[0], y1, b[1]]], [[0, 1, 0]] * 3, top, None)
        if bottom:
            p.tri(part, [[centre[0], y0, centre[2]], [b[0], y0, b[1]], [a[0], y0, a[1]]], [[0, -1, 0]] * 3, bottom, None)


def tray(p, floor, rim_top, hood=False):
    outer = tray_poly(7.5, 7, 2, 3)
    inner = tray_poly(6.3, 5.8, 1.5, 3)
    foot = p.part('foot_ring')
    prism(p, foot, tray_poly(6.5, 6, 1.5, 3), 0, .5, C('ceramic', 0), C('ceramic', 0), C('ceramic', 0))
    base = p.part('floor_slab')
    prism(p, base, outer, .5, floor, C('ceramic', 1), C('ceramic', 1), None)
    wall = p.part('glazed_wall')
    ring_wall(p, wall, outer, inner, floor, rim_top,
              [(floor, floor + .8, C('ceramic', 1)), (floor + .8, rim_top - .7, C('mint', 2)), (rim_top - .7, rim_top, C('ceramic', 3))],
              C('ceramic', 3), C('ceramic', 1), skip={GAP_EDGE})


def ceramic_toilet():
    p = Prop('ceramic_toilet')
    tray(p, 1.0, 3.5)
    return p


def covered_toilet():
    """Cream tray with the same mint band, plus a mint half-dome hood over the back; the front stays open."""
    p = Prop('covered_toilet')
    tray(p, 1.5, 3.5)
    hood = p.part('mint_hood')
    outer = [(6.5, 1.5), (6.5, 3.5), (6.25, 4.25), (5.25, 5.0), (3.5, 5.75), (1.75, 6.0), (0, 6.25)]
    inner = [(0, 5.5), (1.5, 5.25), (3.25, 5.0), (4.6, 4.4), (5.5, 3.75), (5.5, 3.5), (5.5, 1.5)]
    profile = outer + inner
    tones = [C('mint', 2), C('mint', 2), C('mint', 3), C('mint', 3), C('mint', 3), C('mint', 3), C('mint', 1),
             C('mint', 0), C('mint', 0), C('mint', 1), C('mint', 1), C('mint', 0), C('mint', 0)]
    arc = (math.pi, math.tau)
    p.lathe(hood, profile, Band(lambda i, j, c: tones[i]), seg=8, squash=.65, center=(0, 7), arc=arc, grid=.25, smooth='around')
    # flat back panel against the tray's back wall
    pts = [(r, y) for r, y in outer] + [(-r, y) for r, y in reversed(outer)]
    cx, cy = 0, 4.0
    for k in range(len(pts)):
        a, b = pts[k], pts[(k + 1) % len(pts)]
        p.tri(hood, [[cx, cy, 7], [a[0], a[1], 7], [b[0], b[1], 7]], [[0, 0, 1]] * 3, C('mint', 1), None)
    return p


# ---- mats ----------------------------------------------------------------------------------------------
def mat(name, thickness, side, painter):
    p = Prop(name, wide=True)
    ox, oy = MAT_AT
    painter(p.img, ox, oy)
    top = Planar(ox + 8, oy + 8, ax=0, ay=2, sx=1, sy=1)
    part = p.part('mat')
    p.box(part, [-8, 0, -8], [8, thickness, 8], {'*': side, 'top': top, 'bottom': side}, grid=.5)
    return p


def paint_newspaper(img, ox, oy):
    fill(img, ox, oy, 16, 16, C('paper', 3))
    fill(img, ox + 1, oy + 1, 14, 2, C('wood', 1))                       # masthead
    fill(img, ox + 1, oy + 4, 6, 5, C('paper', 1))                        # photo
    for y in (4, 6, 8):
        fill(img, ox + 8, oy + y, 7, 1, C('wood', 2))
    for col in (1, 8):
        for y in range(10, 15, 2):
            fill(img, ox + col, oy + y, 6, 1, C('wood', 2))


def paint_soil(img, ox, oy):
    rng = deterministic(5150)
    fill(img, ox, oy, 16, 16, C('wood', 1))
    for _ in range(34):
        img.putpixel((ox + rng.randrange(16), oy + rng.randrange(16)), colour('wood', rng.choice([0, 0, 2])) + (255,))
    for _ in range(4):
        x, y = rng.randrange(14), rng.randrange(15)
        for dx, dy in ((0, 0), (1, 0)):
            img.putpixel((ox + x + dx, oy + y + dy), colour('wood', 3) + (255,))


def paint_sand(img, ox, oy):
    rng = deterministic(6061)
    fill(img, ox, oy, 16, 16, C('cardboard', 2))
    for _ in range(30):
        img.putpixel((ox + rng.randrange(16), oy + rng.randrange(16)), colour('cardboard', rng.choice([1, 3, 3])) + (255,))
    for _ in range(10):
        img.putpixel((ox + rng.randrange(16), oy + rng.randrange(16)), colour('wood', 0) + (255,))


def newspaper_mat():
    return mat('newspaper_mat', .5, C('paper', 0), paint_newspaper)


def soil_mat():
    return mat('soil_mat', 1, C('wood', 0), paint_soil)


def sandpaper_mat():
    return mat('sandpaper_mat', .5, C('cardboard', 0), paint_sand)
