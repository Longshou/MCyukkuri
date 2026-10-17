"""Mesh kit for the care-prop family: soft low-poly shapes on the 1/16 grid, flat swatch colours.

Units are 1/16 block, origin at the centre of the footprint, +Y up. Every prop samples one shared
palette file (art/props/palette.json): ramp row r, step column s, each swatch 4x4 texels. Props that need a
printed label or a tiled surface add a decal area right of the swatches (x >= 16) painted at one to two texels
per unit. No black outlines: contact creases take the darkest step of their own material.

Triangles carry per-vertex normals (smooth modes give the soft look). No Minecraft or game pixels are used.
"""
import base64, io, json, math, random, uuid
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[4]
PALETTE_PATH = ROOT / 'art/props/palette.json'
_palette = json.loads(PALETTE_PATH.read_text(encoding='utf-8'))
RAMPS = list(_palette['ramps'])
SWATCH = _palette['swatch_texels']
SIZE_H = SWATCH * len(RAMPS)          # palette rows
WIDE = 64                              # texture width when a decal area is used


def hex_rgb(value):
    return tuple(int(value[i:i + 2], 16) for i in (1, 3, 5))


def luminance(rgb):
    return .299 * rgb[0] + .587 * rgb[1] + .114 * rgb[2]


def hex_of(ramp, step):
    return _palette['ramps'][ramp][step]


def colour(ramp, step):
    return hex_rgb(_palette['ramps'][ramp][step])


def nearest_swatch(rgb, allowed=None):
    """Nearest palette swatch (ramp, step) for an RGB value; used to touch up older meshes."""
    best, pick = None, None
    for ramp in (allowed or RAMPS):
        for step, value in enumerate(_palette['ramps'][ramp]):
            c = hex_rgb(value)
            d = sum((a - b) ** 2 for a, b in zip(rgb, c))
            if best is None or d < best:
                best, pick = d, (ramp, step)
    return pick


# ---- small vector helpers -------------------------------------------------------------------
def sub(a, b): return [a[i] - b[i] for i in range(3)]
def add(a, b): return [a[i] + b[i] for i in range(3)]
def mul(a, k): return [x * k for x in a]
def dot(a, b): return sum(a[i] * b[i] for i in range(3))
def cross(a, b): return [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]
def length(a): return math.sqrt(dot(a, a))
def unit(a):
    n = length(a)
    return [x / n for x in a] if n > 1e-9 else [0.0, 1.0, 0.0]
def snap(p, grid):
    return [round(x / grid) * grid + 0.0 for x in p] if grid else list(p)


class Planar:
    """Planar texel mapping of a surface: texel = origin + (position - ref) * scale, in texture pixels."""
    def __init__(self, ox, oy, ax=0, ay=2, sx=1.0, sy=1.0, rx=0.0, ry=0.0):
        self.ox, self.oy, self.ax, self.ay, self.sx, self.sy, self.rx, self.ry = ox, oy, ax, ay, sx, sy, rx, ry

    def __call__(self, pos):
        return (self.ox + (pos[self.ax] - self.rx) * self.sx, self.oy + (pos[self.ay] - self.ry) * self.sy)


class Band:
    """Colour chosen per skin quad: fn(band, segment, centre) -> swatch."""
    def __init__(self, fn):
        self.fn = fn


class Prop:
    def __init__(self, name, wide=False):
        self.name, self.wide = name, wide
        self.W, self.H = (WIDE if wide else SWATCH * 4), SIZE_H
        self.parts = []
        self.img = Image.new('RGBA', (self.W, self.H), (0, 0, 0, 0))
        for r, ramp in enumerate(RAMPS):
            for s, value in enumerate(_palette['ramps'][ramp]):
                for y in range(SWATCH):
                    for x in range(SWATCH):
                        self.img.putpixel((s * SWATCH + x, r * SWATCH + y), hex_rgb(value) + (255,))

    # -- colour / uv -----------------------------------------------------------------------------
    def swatch_uv(self, ramp, step):
        return ((step + .5) * SWATCH, (RAMPS.index(ramp) + .5) * SWATCH)

    def uv(self, spec, pos):
        if callable(spec):
            return spec(pos)
        if isinstance(spec, tuple) and isinstance(spec[0], str):
            return self.swatch_uv(*spec)
        return spec

    # -- parts -----------------------------------------------------------------------------------
    def part(self, name, unit_=0, **extra):
        part = dict(name=name, unit=unit_, tris=[], **extra)
        self.parts.append(part)
        return part

    def tri(self, part, P, N, spec, grid=None, uvs=None):
        P = [snap(p, grid) for p in P] if grid else [list(p) for p in P]
        g = cross(sub(P[1], P[0]), sub(P[2], P[0]))
        if length(g) < 1e-6:
            return
        if dot(g, add(add(N[0], N[1]), N[2])) < 0:
            P[1], P[2], N = P[2], P[1], [N[0], N[2], N[1]]
            if uvs:
                uvs = [uvs[0], uvs[2], uvs[1]]
        part['tris'].append([(P[i], N[i], uvs[i] if uvs else self.uv(spec, P[i])) for i in range(3)])

    # -- primitives ------------------------------------------------------------------------------
    def quad(self, part, pts, spec, grid=None, double=False, uvs=None, normal=None):
        pts = [snap(p, grid) for p in pts] if grid else [list(p) for p in pts]
        n = normal or unit(cross(sub(pts[1], pts[0]), sub(pts[3], pts[0])))
        for sign in ((1, -1) if double else (1,)):
            nn = mul(n, sign)
            order = (0, 1, 2, 3) if sign == 1 else (0, 3, 2, 1)
            q = [pts[i] for i in order]
            qu = [uvs[i] for i in order] if uvs else None
            self.tri(part, [q[0], q[1], q[2]], [nn] * 3, spec, None, qu and [qu[0], qu[1], qu[2]])
            self.tri(part, [q[0], q[2], q[3]], [nn] * 3, spec, None, qu and [qu[0], qu[2], qu[3]])

    def box(self, part, lo, hi, spec, grid=1.0):
        """Axis-aligned box. spec is one swatch or a dict with keys top bottom n s e w and '*'."""
        lo, hi = snap(lo, grid), snap(hi, grid)
        x0, y0, z0 = lo
        x1, y1, z1 = hi
        faces = {
            'top': [[x0, y1, z0], [x0, y1, z1], [x1, y1, z1], [x1, y1, z0]],
            'bottom': [[x0, y0, z0], [x1, y0, z0], [x1, y0, z1], [x0, y0, z1]],
            'n': [[x0, y0, z0], [x0, y1, z0], [x1, y1, z0], [x1, y0, z0]],    # -z
            's': [[x0, y0, z1], [x1, y0, z1], [x1, y1, z1], [x0, y1, z1]],    # +z
            'w': [[x0, y0, z0], [x0, y0, z1], [x0, y1, z1], [x0, y1, z0]],    # -x
            'e': [[x1, y0, z0], [x1, y1, z0], [x1, y1, z1], [x1, y0, z1]],    # +x
        }
        normals = {'top': [0, 1, 0], 'bottom': [0, -1, 0], 'n': [0, 0, -1], 's': [0, 0, 1], 'w': [-1, 0, 0], 'e': [1, 0, 0]}
        for key, pts in faces.items():
            s = spec.get(key, spec.get('*')) if isinstance(spec, dict) else spec
            if s is None:
                continue
            self.quad(part, pts, s, None, normal=normals[key])

    def skin(self, part, loops, spec, smooth='both', caps=(False, False), grid=None, closed=True,
             auto_orient=False, flip=False):
        """Surface between rows of loop points. Quad normal = (row direction) x (loop direction).
        spec is a swatch, a Planar, or f(band, seg, centre) -> swatch."""
        loops = [[snap(p, grid) for p in loop] for loop in loops] if grid else [[list(p) for p in loop] for loop in loops]
        rows, n = len(loops), len(loops[0])
        segs = n if closed else n - 1
        cells = {}
        for i in range(rows - 1):
            for j in range(segs):
                p00, p01 = loops[i][j], loops[i][(j + 1) % n]
                p10, p11 = loops[i + 1][j], loops[i + 1][(j + 1) % n]
                cells[i, j] = (p00, p01, p11, p10, cross(sub(p10, p00), sub(p01, p00)))
        # normals from the first diagonal if the quad is partly collapsed
        for key, c in list(cells.items()):
            if length(c[4]) < 1e-9:
                alt = cross(sub(c[2], c[1]), sub(c[3], c[1]))
                cells[key] = (*c[:4], mul(alt, -1))
        sign = -1 if flip else 1
        if auto_orient:
            pts = [p for loop in loops for p in loop]
            centre = [sum(p[k] for p in pts) / len(pts) for k in range(3)]
            total = sum(dot(c[4], sub(c[0], centre)) for c in cells.values())
            sign = 1 if total >= 0 else -1
        def vn(i, j, band_rows, seg_cols):
            acc = [0, 0, 0]
            for bi in band_rows:
                for sj in seg_cols:
                    c = cells.get((bi, sj % n if closed else sj))
                    if c and (closed or 0 <= sj < segs):
                        acc = add(acc, c[4])
            return unit(mul(acc, sign)) if length(acc) > 1e-9 else None
        for (i, j), c in cells.items():
            flat = unit(mul(c[4], sign))
            corners = [(i, j), (i, j + 1), (i + 1, j + 1), (i + 1, j)]
            normals = []
            for (ci, cj) in corners:
                if smooth == 'flat':
                    nn = flat
                elif smooth == 'around':
                    nn = vn(ci, cj, [i], [cj - 1, cj])
                elif smooth == 'along':
                    nn = vn(ci, cj, [ci - 1, ci], [j])
                else:
                    nn = vn(ci, cj, [ci - 1, ci], [cj - 1, cj])
                normals.append(nn or flat)
            centre = [sum(p[k] for p in c[:4]) / 4 for k in range(3)]
            s = spec.fn(i, j, centre) if isinstance(spec, Band) else spec
            if s is None:
                continue
            self.tri(part, [c[0], c[1], c[2]], [normals[0], normals[1], normals[2]], s)
            self.tri(part, [c[0], c[2], c[3]], [normals[0], normals[2], normals[3]], s)
        for end, flag in ((0, caps[0]), (rows - 1, caps[1])):
            if not flag:
                continue
            loop = loops[end]
            centre = [sum(p[k] for p in loop) / n for k in range(3)]
            other = loops[1 if end == 0 else rows - 2]
            oc = [sum(p[k] for p in other) / n for k in range(3)]
            outward = unit(sub(centre, oc))
            cs = spec.fn(0 if end == 0 else rows - 2, 0, centre) if isinstance(spec, Band) else spec
            for j in range(n if closed else n - 1):
                self.tri(part, [centre, loop[j], loop[(j + 1) % n]], [outward] * 3, cs)

    def lathe(self, part, profile, spec, seg=12, squash=1.0, center=(0.0, 0.0), phase=0.0, twist=0.0,
              grid=.5, smooth='around', arc=None, caps=(False, False)):
        """Solid of revolution about the Y axis. profile = [(radius, y), ...] traversed with the solid on the left."""
        cx, cz = center
        loops = []
        for i, (r, y) in enumerate(profile):
            ph = phase + twist * i
            if arc:
                pts = [[cx + r * math.cos(arc[0] + (arc[1] - arc[0]) * j / seg + ph), y,
                        cz + r * squash * math.sin(arc[0] + (arc[1] - arc[0]) * j / seg + ph)] for j in range(seg + 1)]
            else:
                pts = [[cx + r * math.cos(math.tau * j / seg + ph), y, cz + r * squash * math.sin(math.tau * j / seg + ph)]
                       for j in range(seg)]
            loops.append(pts)
        self.skin(part, loops, spec, smooth=smooth, grid=grid, closed=arc is None, caps=caps)

    def torus(self, part, center, R, r, seg=12, tube=6, spec=None, grid=.5, squash=1.0, smooth='both'):
        cx, cy, cz = center
        profile = [(R + r * math.cos(math.tau * k / tube - math.pi / 2), cy + r * math.sin(math.tau * k / tube - math.pi / 2))
                   for k in range(tube + 1)]
        # counter-clockwise in (r, y) with the solid on the left: bottom, outer, top, inner
        profile = [(R + r * math.cos(-math.pi / 2 + math.tau * k / tube), cy + r * math.sin(-math.pi / 2 + math.tau * k / tube))
                   for k in range(tube + 1)]
        self.lathe(part, profile, spec, seg=seg, squash=squash, center=(cx, cz), grid=grid, smooth=smooth)

    def rod(self, part, a, b, r0, r1, seg=5, spec=None, caps=(True, True), grid=.5, end_spec=None, phase=0.0, smooth='around'):
        a, b = list(a), list(b)
        axis = unit(sub(b, a))
        ref = [1, 0, 0] if abs(axis[0]) < .9 else [0, 0, 1]
        u = unit(cross(axis, ref))
        v = cross(axis, u)
        loops = []
        for centre, radius in ((a, r0), (b, r1)):
            loops.append([[centre[k] + radius * (u[k] * math.cos(phase + math.tau * j / seg) + v[k] * math.sin(phase + math.tau * j / seg))
                           for k in range(3)] for j in range(seg)])
        self.skin(part, loops, spec, smooth=smooth, grid=grid, caps=caps, auto_orient=True)

    def blob(self, part, center, radii, spec_fn, rng=None, jitter=.12, grid=None, tilt=0.0, smooth=False):
        """Icosahedron lump, flat shaded. spec_fn(normal) -> swatch."""
        t = (1 + math.sqrt(5)) / 2
        base = [[-1, t, 0], [1, t, 0], [-1, -t, 0], [1, -t, 0], [0, -1, t], [0, 1, t], [0, -1, -t], [0, 1, -t],
                [t, 0, -1], [t, 0, 1], [-t, 0, -1], [-t, 0, 1]]
        faces = [(0, 11, 5), (0, 5, 1), (0, 1, 7), (0, 7, 10), (0, 10, 11), (1, 5, 9), (5, 11, 4), (11, 10, 2), (10, 7, 6),
                 (7, 1, 8), (3, 9, 4), (3, 4, 2), (3, 2, 6), (3, 6, 8), (3, 8, 9), (4, 9, 5), (2, 4, 11), (6, 2, 10), (8, 6, 7), (9, 8, 1)]
        pts = []
        for p in base:
            p = unit(p)
            if rng:
                p = mul(p, 1 + rng.uniform(-jitter, jitter))
            pts.append(p)
        out, local = [], []
        for p in pts:
            q = [p[0] * radii[0], p[1] * radii[1], p[2] * radii[2]]
            if tilt:
                c, s = math.cos(tilt), math.sin(tilt)
                q = [q[0] * c - q[1] * s, q[0] * s + q[1] * c, q[2]]
            local.append(q)
            out.append(add(q, center))
        centre = [sum(p[k] for p in out) / 12 for k in range(3)]
        vnorm = [unit([q[0] / radii[0] ** 2, q[1] / radii[1] ** 2, q[2] / radii[2] ** 2]) for q in local]
        for f in faces:
            tri = [out[i] for i in f]
            n = unit(cross(sub(tri[1], tri[0]), sub(tri[2], tri[0])))
            if dot(n, sub(tri[0], centre)) < 0:
                n = mul(n, -1)
            self.tri(part, tri, [vnorm[i] for i in f] if smooth else [n] * 3, spec_fn(n), grid)

    def graft(self, other, scale=1.0, offset=(0, 0, 0)):
        """Copy another prop's parts (same texture layout) scaled and moved; normals are kept."""
        for part in other.parts:
            new = self.part(part['name'], part['unit'])
            for tri in part['tris']:
                new['tris'].append([([p[0] * scale + offset[0], p[1] * scale + offset[1], p[2] * scale + offset[2]], n, uv) for p, n, uv in tri])

    @classmethod
    def from_mesh(cls, name, mesh, swatch_of, wide=False):
        """Rebuild a prop from an older triangle mesh, replacing each triangle's colour with swatch_of(part, uv_triplet)."""
        prop = cls(name, wide=wide)
        v = mesh['vertices']
        for entry in mesh['parts']:
            part = prop.part(entry['name'], entry.get('unit', 0))
            for first in range(entry['start'], entry['start'] + entry['count'], 3):
                rows = [v[(first + k) * 8:(first + k) * 8 + 8] for k in range(3)]
                spec = swatch_of(entry['name'], [(r[3], r[4]) for r in rows])
                part['tris'].append([([r[0], r[1], r[2]], [r[5], r[6], r[7]], prop.uv(spec, None)) for r in rows])
        return prop

    # -- export ----------------------------------------------------------------------------------
    def tri_count(self):
        return sum(len(p['tris']) for p in self.parts)

    def mesh(self):
        vertices, parts = [], []
        for part in self.parts:
            start = len(vertices) // 8
            for tri in part['tris']:
                for pos, nrm, uv in tri:
                    vertices.extend([round(pos[0], 4) + 0.0, round(pos[1], 4) + 0.0, round(pos[2], 4) + 0.0,
                                     round(uv[0] / self.W, 6), round(uv[1] / self.H, 6),
                                     round(nrm[0], 4) + 0.0, round(nrm[1], 4) + 0.0, round(nrm[2], 4) + 0.0])
            entry = {'name': part['name'], 'start': start, 'count': len(vertices) // 8 - start, 'unit': part['unit']}
            for key in ('texture', 'tint', 'translucent'):
                if key in part:
                    entry[key] = part[key]
            if entry['count']:
                parts.append(entry)
        bounds = [[min(vertices[k::8]), max(vertices[k::8])] for k in range(3)]
        return {'format': 1, 'units_per_block': 16, 'id': self.name, 'texture_size': [self.W, self.H],
                'vertices': vertices, 'parts': parts, 'bounds': bounds}

    def png(self):
        stream = io.BytesIO()
        self.img.save(stream, format='PNG', optimize=True)
        return stream.getvalue()

    def bbmodel(self):
        elements = []
        for index, part in enumerate(self.parts):
            if not part['tris']:
                continue
            vertices, faces, ids = {}, {}, {}
            for tri in part['tris']:
                names = []
                for pos, nrm, uv in tri:
                    key = tuple(round(c, 4) for c in pos)
                    if key not in ids:
                        ids[key] = str(len(ids))
                        vertices[ids[key]] = [c for c in key]
                    names.append((ids[key], uv))
                faces[str(len(faces))] = {'vertices': [n for n, _ in names], 'uv': {n: [round(uv[0], 3), round(uv[1], 3)] for n, uv in names}, 'texture': 0}
            elements.append({'name': part['name'], 'type': 'mesh', 'uuid': str(uuid.uuid5(uuid.NAMESPACE_URL, f'{self.name}/{part["name"]}/{index}')),
                             'origin': [0, 0, 0], 'rotation': [0, 0, 0], 'vertices': vertices, 'faces': faces})
        return {'meta': {'format_version': '4.10', 'model_format': 'free'}, 'name': self.name,
                'resolution': {'width': self.W, 'height': self.H}, 'elements': elements, 'outliner': [e['uuid'] for e in elements],
                'textures': [{'name': 'texture', 'source': 'data:image/png;base64,' + base64.b64encode(self.png()).decode(),
                              'width': self.W, 'height': self.H, 'uv_width': self.W, 'uv_height': self.H, 'id': '0'}]}


def dumps(data):
    return (json.dumps(data, ensure_ascii=False, separators=(',', ':')) + '\n').encode('utf-8')


# ---- ring walls (trays) -----------------------------------------------------------------------
def rrect_points(hx, hz, c):
    """Chamfered rectangle outline, counter-clockwise from +x seen from above (x toward z)."""
    return [(hx, -(hz - c)), (hx, hz - c), (hx - c, hz), (-(hx - c), hz), (-hx, hz - c), (-hx, -(hz - c)), (-(hx - c), -hz), (hx - c, -hz)]


def ring_wall(prop, part, outer, inner, y0, y1, bands, top_spec, inner_spec, skip=(), inner_y0=None):
    """Wall between two matching 2D polygons. bands = [(ya, yb, spec)] for the outer face; edge k joins point k and k+1.
    Edges in skip are left open (an entrance); their ends are closed."""
    n = len(outer)
    iy0 = y0 if inner_y0 is None else inner_y0
    for k in range(n):
        k2 = (k + 1) % n
        a, b = outer[k], outer[k2]
        ia, ib = inner[k], inner[k2]
        if k in skip:
            continue
        # outer polygon runs counter-clockwise, so (dz, -dx) points outward
        nrm = unit([(b[1] - a[1]), 0, -(b[0] - a[0])])
        for ya, yb, spec in bands:
            prop.quad(part, [[a[0], ya, a[1]], [b[0], ya, b[1]], [b[0], yb, b[1]], [a[0], yb, a[1]]], spec, normal=nrm)
        prop.quad(part, [[a[0], y1, a[1]], [b[0], y1, b[1]], [ib[0], y1, ib[1]], [ia[0], y1, ia[1]]], top_spec, normal=[0, 1, 0])
        inrm = mul(unit([(ib[1] - ia[1]), 0, -(ib[0] - ia[0])]), -1)
        prop.quad(part, [[ia[0], iy0, ia[1]], [ib[0], iy0, ib[1]], [ib[0], y1, ib[1]], [ia[0], y1, ia[1]]], inner_spec, normal=inrm)
    for k in skip:
        k2 = (k + 1) % n
        for idx, sgn in ((k, 1), (k2, -1)):
            p, ip = outer[idx], inner[idx]
            edge = unit([outer[k2][0] - outer[k][0], 0, outer[k2][1] - outer[k][1]])
            face_n = mul(edge, sgn)
            prop.quad(part, [[p[0], iy0, p[1]], [ip[0], iy0, ip[1]], [ip[0], y1, ip[1]], [p[0], y1, p[1]]], top_spec, normal=face_n)


# ---- pixel art helpers ------------------------------------------------------------------------
def paint(img, x, y, rows, legend):
    """Draw ASCII rows at (x, y). legend maps a character to (ramp, step) or an RGBA tuple; '.' is transparent."""
    for dy, row in enumerate(rows):
        for dx, ch in enumerate(row):
            if ch == '.' or ch == ' ':
                continue
            v = legend[ch]
            rgb = colour(*v) if isinstance(v[0], str) else v
            img.putpixel((x + dx, y + dy), tuple(rgb) + (255,))


def fill(img, x, y, w, h, spec):
    rgb = colour(*spec)
    for j in range(h):
        for i in range(w):
            img.putpixel((x + i, y + j), rgb + (255,))


def deterministic(seed):
    return random.Random(seed)
