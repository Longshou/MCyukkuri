"""Outlines of the aquatic Marisa hat (recipe.json) and the stepped cavity the game stands young on.

Vessel frame, as marisa_hat_turned: origin at the opening's centre on the rim plane (the face that sat on
the head), +y up out of the opening, x across with the worn front (bow) on +x, z along the long brim
axis. Every outline is a convex polygon sampled on one shared set of rays from the hat axis, so rings
pair vertex for vertex and the polygons the server tests are the drawn edges.

Hull mirrors the game's stepped-cavity reading (HatVessel.surface / cabinClear) with polygon sections in
place of rectangles; delivery.place from the land package runs unchanged on it.
"""
import json, math
from pathlib import Path
import numpy as np

HERE = Path(__file__).resolve().parent
R = json.loads((HERE/'recipe.json').read_text(encoding='utf-8'))


def superellipse(phi, half, n):
    """Distance from the axis to |x/a|^n + |z/b|^n = 1 along the ray at angle phi (x toward z)."""
    a, b = half; c, s = abs(math.cos(phi)), abs(math.sin(phi))
    return (((c/a)**n if c > 1e-12 else 0)+((s/b)**n if s > 1e-12 else 0))**(-1/n)


def front_corners():
    """Rays through the two corners where the front tread's straight edge meets the opening."""
    zf = R['front_tread']['z']; half, n = R['opening']['half'], R['opening']['exponent']
    x = half[0]*(1-abs(zf/half[1])**n)**(1/n)
    return [math.atan2(zf, -x) % math.tau, math.atan2(zf, x) % math.tau]


def directions():
    n = R['sampling']['directions']
    return np.array(sorted({round(math.tau*k/n, 12) for k in range(n)} | {round(a, 12) for a in front_corners()}))


PHI = directions()


def ring(half, n, phi=PHI):
    return np.array([[superellipse(p, half, n)*math.cos(p), superellipse(p, half, n)*math.sin(p)] for p in phi])


def clip_front(points, zf):
    """The outline with everything in front of z = zf cut away, kept on the same rays."""
    out = points.copy()
    for i, (x, z) in enumerate(points):
        if z < zf:  # on this ray the cut line comes first
            t = zf/z; out[i] = [x*t, zf]
    return out


def crown_ring(depth):
    """Outside of the crown at this depth below the rim: half extents and exponent."""
    C = R['crown']; y0, keel = C['straight_to'], C['keel']
    t = 0. if depth <= y0 else min(1., (depth-y0)/(keel-y0))
    s = max(0., 1-t**C['profile_power'])**.5
    n = C['top_exponent']+(C['bottom_exponent']-C['top_exponent'])*t
    return [C['top'][0]*s, C['top'][1]*s], n


def sections():
    """Cavity sections from shallow to deep: (name, outline, depth). A section is open down to its depth."""
    opening = ring(R['opening']['half'], R['opening']['exponent'])
    out = [('front_tread', opening, R['front_tread']['depth']),
           ('ledge', clip_front(opening, R['front_tread']['z']), R['ledge']['depth'])]
    for i, tier in enumerate(R['bowl_tiers']):
        out.append((f'bowl_{i+1}', ring(tier['half'], tier['exponent']), tier['depth']))
    return out


def brim_outline(): return ring(R['brim']['half'], R['brim']['exponent'])


# ------------------------------------------------------------------ polygons
def inside(poly, x, z, eps=1e-9):
    """Point in a convex polygon whose vertices run counter-clockwise (x toward z)."""
    a = poly; b = np.roll(poly, -1, axis=0)
    return bool(np.all((b[:, 0]-a[:, 0])*(z-a[:, 1])-(b[:, 1]-a[:, 1])*(x-a[:, 0]) >= -eps))


def rect_inside(poly, x0, x1, z0, z1, margin=0.):
    """An axis-aligned rectangle lies in a convex polygon iff its four corners do (with an edge margin)."""
    a = poly; b = np.roll(poly, -1, axis=0); d = b-a; length = np.hypot(d[:, 0], d[:, 1])
    for x, z in ((x0, z0), (x1, z0), (x1, z1), (x0, z1)):
        if np.any((d[:, 0]*(z-a[:, 1])-d[:, 1]*(x-a[:, 0]))/length < margin-1e-9): return False
    return True


def bbox(poly): return [float(poly[:, 0].min()), float(poly[:, 0].max()), float(poly[:, 1].min()), float(poly[:, 1].max())]


def strips(poly, step):
    """Axis-aligned rectangles inside a convex polygon whose union covers it to within one step band
    (the conservative stand-in for code that still reads rectangles)."""
    z0, z1 = poly[:, 1].min(), poly[:, 1].max(); out = []
    edges = [(poly[i], poly[(i+1) % len(poly)]) for i in range(len(poly))]

    def span(z):
        xs = [a[0]+(b[0]-a[0])*(z-a[1])/(b[1]-a[1]) for a, b in edges if (a[1]-z)*(b[1]-z) <= 0 and abs(b[1]-a[1]) > 1e-12]
        return (min(xs), max(xs)) if xs else None
    count = max(1, math.ceil((z1-z0)/step)); zs = np.linspace(z0, z1, count+1)
    for lo, hi in zip(zs, zs[1:]):
        s0, s1 = span(lo+1e-9), span(hi-1e-9)
        if s0 is None or s1 is None: continue
        x0, x1 = max(s0[0], s1[0]), min(s0[1], s1[1])
        if x1-x0 > 1e-6: out.append([round(float(x0), 4), round(float(x1), 4), round(float(lo), 4), round(float(hi), 4)])
    return out


def segment_distance(p, a, b):
    d = b-a; L = (d*d).sum(-1); t = np.clip(((p-a)*d).sum(-1)/np.where(L < 1e-12, 1, L), 0, 1)
    return np.hypot(*(p-(a+t[..., None]*d)).T)


def gap(inner, outer):
    """Smallest distance between the boundary of inner and that of outer; negative if inner leaves outer."""
    if not all(inside(outer, x, z) for x, z in inner): return -1.
    a, b = outer, np.roll(outer, -1, axis=0)
    return float(min(segment_distance(np.repeat(p[None], len(a), 0), a, b).min() for p in inner))


# ------------------------------------------------------------------ the surface young stand on (hat units)
class Hull:
    """Polygon version of delivery.Hat / HatVessel.surface for the aquatic hat."""

    def __init__(self, V, mode):
        sq = V['squash'][mode]
        self.mode = mode; self.top = V['nest']['height'] if mode == 'nest' else V['bowl']['freeboard']; self.sq = sq
        self.ledge = self.top-V['ledge']*sq; self.floor = self.top-V['floor']*sq
        self.opening, self.well = V['opening'], V['well']
        self.sections = [(np.array(s['outline']), self.top-s['depth']*sq, s['depth']) for s in V['cavity_sections']]
        self.brim = np.array(V['brim_outline']); self.brims = V['brim_rects']

    def surface(self, x, z):
        for poly, height, _ in reversed(self.sections):
            if inside(poly, x, z): return height
        if inside(self.brim, x, z): return self.top
        return None

    def footprint(self, x, z, r):
        best = None
        for i in range(9):
            h = self.surface(x, z) if i == 8 else self.surface(x+r*math.cos(i*math.pi/4), z+r*math.sin(i*math.pi/4))
            if h is not None and (best is None or h > best): best = h
        return best

    def extent(self, x, z, dx, dz, r):
        d = 0.
        while self.footprint(x+dx*d, z+dz*d, r) is not None and d < 64: d += .125
        return d

    def contains(self, x, y, z, hx, hz):
        """HatVessel.cavityContains with polygon sections: the wall that bounds height y holds the whole rectangle."""
        if y >= self.top-1e-4: return True
        if y < self.floor-1e-4: return False
        for poly, height, _ in self.sections:
            if y < height-1e-4: continue
            return rect_inside(poly, x-hx, x+hx, z-hz, z+hz, .025)
        return False
