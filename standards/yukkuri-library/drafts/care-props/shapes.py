"""Rounded low-poly shapes with hand-painted pixel regions, for the placed pet-care goods (placed.py).

A prop is a few clean solids: outlines (circles or rounded rectangles) revolved through a short profile, flat or domed
caps, small boxes and cut-out sprites. Every surface samples its own painted region of one texture sheet at about one
texel per unit: walls use wrapped bands (u = distance around the outline, v = rows down the profile), rims, floors,
cushions and fills use planar top views (u = x, v = z, centred). Normals are smooth around the outline and across the
profile points marked smooth, creased elsewhere, so the game's entity lighting shades them like soft solids.
Units are 1/16 block, origin at the footprint centre, +y up. Nothing here copies Minecraft pixels.
"""
import math, random, zlib
from PIL import Image
from kit import Prop, cross, sub, unit, dot, length, add

PAD = 1


def rgb(h):
    return tuple(int(h[i:i + 2], 16) for i in (1, 3, 5))


def rng(key):
    return random.Random(zlib.crc32(key.encode()))


def smoothstep(a, b, x):
    t = min(1.0, max(0.0, (x - a) / (b - a)))
    return t * t * (3 - 2 * t)


# ---- pixel painting -------------------------------------------------------------------------------------------------
def canvas(w, h, colour=None):
    return Image.new('RGBA', (w, h), (rgb(colour) + (255,)) if colour else (0, 0, 0, 0))


def put(img, x, y, colour, wrap=True):
    w, h = img.size
    if wrap:
        x %= w
    if 0 <= x < w and 0 <= y < h:
        img.putpixel((x, y), (rgb(colour) if isinstance(colour, str) else tuple(colour[:3])) + (255,))


def get(img, x, y):
    w, h = img.size
    return img.getpixel((x % w, min(h - 1, max(0, y))))


def grain(img, ramp, key, amount=.08, rows=None, up=.5):
    """Sparse one-step grain: pixels already on a ramp step move one step lighter or darker (vanilla material noise)."""
    r = rng(key)
    w, h = img.size
    steps = [rgb(c) for c in ramp]
    for y in (rows if rows is not None else range(h)):
        for x in range(w):
            px = get(img, x, y)[:3]
            if px not in steps or r.random() >= amount:
                continue
            i = steps.index(px) + (1 if r.random() < up else -1)
            put(img, x, y, ramp[max(0, min(len(ramp) - 1, i))])
    return img


def stamp(img, x, y, rows, legend, wrap=True):
    """ASCII stamp: characters missing from the legend keep the pixel below."""
    for dy, line in enumerate(rows):
        for dx, ch in enumerate(line):
            if ch in legend:
                put(img, x + dx, y + dy, legend[ch], wrap)


def top_view(size, painter):
    """Planar top-view region: painter(x, z, r) -> colour or None at each texel centre (origin at the image centre)."""
    img = canvas(size, size)
    for v in range(size):
        for u in range(size):
            x, z = u + .5 - size / 2, v + .5 - size / 2
            c = painter(x, z, math.hypot(x, z))
            if c:
                put(img, u, v, c, False)
    return img


# ---- texture sheet --------------------------------------------------------------------------------------------------
class Sheet:
    """Regions packed in shelves with a one-texel gutter (wrapped bands repeat across it, others extend their edge)."""

    def __init__(self, width=128):
        self.width, self.regions, self.images = width, {}, {}
        self.x = self.y = self.row = 0

    def add(self, key, img, wrap=False):
        if key in self.regions:
            return self.regions[key]
        w, h = img.size
        if self.x + w + 2 * PAD > self.width:
            self.x, self.y, self.row = 0, self.y + self.row, 0
        self.regions[key] = (self.x + PAD, self.y + PAD)
        self.images[key] = (img, wrap)
        self.x += w + 2 * PAD
        self.row = max(self.row, h + 2 * PAD)
        return self.regions[key]

    def image(self):
        height = 16
        while height < self.y + self.row:
            height *= 2
        out = Image.new('RGBA', (self.width, height), (0, 0, 0, 0))
        for key, (ox, oy) in self.regions.items():
            img, wrap = self.images[key]
            w, h = img.size
            for gy in range(-PAD, h + PAD):
                for gx in range(-PAD, w + PAD):
                    sx = gx % w if wrap else min(max(gx, 0), w - 1)
                    out.putpixel((ox + gx, oy + gy), img.getpixel((sx, min(max(gy, 0), h - 1))))
        return out


class Build:
    def __init__(self, name, width=128):
        self.p = Prop(name, noise=False)
        self.p.parts = []
        self.sheet = Sheet(width)
        self.parts = {}

    def part(self, name, unit_=0, **extra):
        if name not in self.parts:
            self.parts[name] = self.p.part(name, unit_, **extra)
        return self.parts[name]

    def region(self, key, img, wrap=False):
        return self.sheet.add(key, img, wrap)

    def finish(self):
        img = self.sheet.image()
        self.p.img = img
        self.p.W, self.p.H = img.size
        return self.p


# ---- outlines -------------------------------------------------------------------------------------------------------
class Outline:
    """Rounded rectangle (half extents hx, hz, corner radius rc); hx = hz = rc = 0 is a circle whose offset is its radius.
    Samples run counter-clockwise from +x toward +z and keep their order at every offset, so profile rows line up."""

    def __init__(self, hx, hz, rc, arc=8, step=4.0):
        self.rc = rc
        cx, cz = hx - rc, hz - rc
        corners = [(1, 1), (-1, 1), (-1, -1), (1, -1)]
        self.samples = []
        for q in range(4):
            a0 = q * math.pi / 2
            c = (corners[q][0] * cx, corners[q][1] * cz)
            for i in range(arc):
                a = a0 + math.pi / 2 * i / arc
                self.samples.append((c[0], c[1], math.cos(a), math.sin(a)))
            nxt = (corners[(q + 1) % 4][0] * cx, corners[(q + 1) % 4][1] * cz)
            span = math.hypot(nxt[0] - c[0], nxt[1] - c[1])
            if span > 1e-6:
                a = a0 + math.pi / 2
                n = max(2, 2 * math.ceil(span / (2 * step)))         # even, so a side always has a midpoint sample
                for j in range(n):
                    t = j / n
                    self.samples.append((c[0] + (nxt[0] - c[0]) * t, c[1] + (nxt[1] - c[1]) * t, math.cos(a), math.sin(a)))

    def at(self, s, o):
        cx, cz, nx, nz = s
        return cx + nx * (self.rc + o), cz + nz * (self.rc + o)

    def select(self, keep):
        """Indices of samples with keep(x, z) at offset 0, as one run in order (for an open partial surface)."""
        flags = [keep(*self.at(s, 0)) for s in self.samples]
        n = len(flags)
        start = next(i for i in range(n) if flags[i] and not flags[i - 1])
        out = []
        i = start
        while flags[i % n] and len(out) < n:
            out.append(i % n)
            i += 1
        return out

    def us(self, width, ref, cols=None, closed=True):
        idx = cols if cols is not None else list(range(len(self.samples)))
        pts = [self.at(self.samples[i], ref) for i in idx]
        if closed:
            pts = pts + [pts[0]]
        acc = [0.0]
        for a, b in zip(pts, pts[1:]):
            acc.append(acc[-1] + math.hypot(b[0] - a[0], b[1] - a[1]))
        return [width * a / acc[-1] for a in acc]

    def u_at(self, width, ref, x, z):
        """Band u of the sample nearest the direction of (x, z)."""
        us = self.us(width, ref)
        i = min(range(len(self.samples)), key=lambda i: math.hypot(self.at(self.samples[i], ref)[0] - x, self.at(self.samples[i], ref)[1] - z))
        return us[i]

    def perimeter(self, ref):
        pts = [self.at(s, ref) for s in self.samples]
        return sum(math.hypot(b[0] - a[0], b[1] - a[1]) for a, b in zip(pts, pts[1:] + pts[:1]))


CIRCLE = lambda segs: Outline(0, 0, 0, arc=segs // 4)


# ---- surfaces -------------------------------------------------------------------------------------------------------
def grid(b, part, P, UV, origin, closed, hint, smooth_rows=(), around=True):
    """Quads between rows of points P[i][j] with texel coordinates UV[i][j] (UV rows hold one extra column when closed).
    Face normals face hint(i, j); vertex normals average around (unless around=False: one flat facet per column),
    and across rows listed in smooth_rows."""
    rows, cols = len(P), len(P[0])
    segs = cols if closed else cols - 1
    F = {}
    for i in range(rows - 1):
        for j in range(segs):
            j1 = (j + 1) % cols
            n = cross(sub(P[i + 1][j1], P[i][j]), sub(P[i + 1][j], P[i][j1]))
            if length(n) < 1e-9:
                continue
            if dot(n, hint(i, j)) < 0:
                n = [-c for c in n]
            F[i, j] = unit(n)

    def vn(i, j, ri, cj):
        rs = [ri - 1, ri] if ri in smooth_rows else [i]
        cs = [cj - 1, cj] if around else [j]
        acc = [0.0, 0.0, 0.0]
        for r in rs:
            for c in cs:
                c = c % cols if closed else c
                if (r, c) in F:
                    acc = add(acc, F[r, c])
        return unit(acc) if length(acc) > 1e-9 else F[i, j]
    ox, oy = origin
    for (i, j), _ in sorted(F.items()):
        j1 = (j + 1) % cols
        corners = [(i, j, j), (i, j1, j + 1), (i + 1, j1, j + 1), (i + 1, j, j)]
        pts = [P[r][c] for r, c, _ in corners]
        nrm = [vn(i, j, r, cu) for r, _, cu in corners]
        uvs = [(ox + UV[r][cu][0], oy + UV[r][cu][1]) for r, _, cu in corners]
        for a, c, d in ((0, 1, 2), (0, 2, 3)):
            b.p.tri(part, [pts[a], pts[c], pts[d]], [nrm[a], nrm[c], nrm[d]], None, uvs=[uvs[a], uvs[c], uvs[d]])


def revolve(b, part, outline, profile, key, img, smooth=(), ymap=None, cols=None, ref=None, inward=False, facet=False):
    """Band surface: profile [(offset, y, v)] in order bottom-outer -> over the top -> inner-down, around the outline.
    The painted band `img` wraps around (u = distance at offset `ref`), v is the given row coordinate."""
    closed = cols is None
    idx = cols if cols is not None else list(range(len(outline.samples)))
    W = img.size[0]
    us = outline.us(W, profile[0][0] if ref is None else ref, cols, closed)
    origin = b.region(key, img, wrap=closed)
    P, UV = [], []
    for o, y, v in profile:
        row = []
        for i in idx:
            x, z = outline.at(outline.samples[i], o)
            row.append([x, ymap(x, z, y) if ymap else y, z])
        P.append(row)
        UV.append([(u, v) for u in us])

    def hint(i, j):
        (o0, y0, _), (o1, y1, _) = profile[i], profile[i + 1]
        a, c = (y1 - y0), -(o1 - o0)
        s = outline.samples[idx[j]]
        n = [s[2] * a, c, s[3] * a]
        return [-x for x in n] if inward else n
    grid(b, part, P, UV, origin, closed, hint, smooth, around=not facet)


def cap(b, part, outline, rings, key, img, down=False, ymap=None, centre=None, cols=None):
    """Planar top-view surface over rings [(offset, y)] from the outside inward, closed by a fan to (0, centre, 0)
    when centre is given. The region img is centred on the origin at one texel per unit."""
    closed = cols is None
    idx = cols if cols is not None else list(range(len(outline.samples)))
    w, h = img.size
    origin = b.region(key, img)
    P = []
    for o, y in rings:
        row = []
        for i in idx:
            x, z = outline.at(outline.samples[i], o)
            row.append([x, ymap(x, z, y) if ymap else y, z])
        P.append(row)
    if centre is not None:
        P.append([[0.0, centre, 0.0] for _ in idx])
    UV = [[(p[0] + w / 2, p[2] + h / 2) for p in row] + ([(row[0][0] + w / 2, row[0][2] + h / 2)] if closed else []) for row in P]
    up = [0, -1 if down else 1, 0]
    grid(b, part, P, UV, origin, closed, lambda i, j: up, smooth_rows=set(range(1, len(P) - 1)))


def box(b, part, lo, hi, key, img, faces=('top', 'n', 's', 'w', 'e')):
    """Small box; every face samples the whole region img."""
    ox, oy = b.region(key, img)
    w, h = img.size
    x0, y0, z0 = lo
    x1, y1, z1 = hi
    quads = {'top': ([[x0, y1, z0], [x1, y1, z0], [x1, y1, z1], [x0, y1, z1]], [0, 1, 0]),
             'bottom': ([[x0, y0, z0], [x1, y0, z0], [x1, y0, z1], [x0, y0, z1]], [0, -1, 0]),
             'n': ([[x0, y1, z0], [x1, y1, z0], [x1, y0, z0], [x0, y0, z0]], [0, 0, -1]),
             's': ([[x0, y1, z1], [x1, y1, z1], [x1, y0, z1], [x0, y0, z1]], [0, 0, 1]),
             'w': ([[x0, y1, z0], [x0, y1, z1], [x0, y0, z1], [x0, y0, z0]], [-1, 0, 0]),
             'e': ([[x1, y1, z0], [x1, y1, z1], [x1, y0, z1], [x1, y0, z0]], [1, 0, 0])}
    uv = [(ox, oy), (ox + w, oy), (ox + w, oy + h), (ox, oy + h)]
    for f in faces:
        pts, n = quads[f]
        b.p.quad(part, pts, None, uvs=uv, normal=n)


def sprite(b, part, corners, key, img, normal=None):
    """One cut-out quad through four corners (texel corners TL, TR, BR, BL of the region)."""
    ox, oy = b.region(key, img)
    w, h = img.size
    n = normal or unit(cross(sub(corners[1], corners[0]), sub(corners[3], corners[0])))
    if n[1] < 0:
        n = [-c for c in n]
    b.p.quad(part, corners, None, uvs=[(ox, oy), (ox + w, oy), (ox + w, oy + h), (ox, oy + h)], normal=n)
