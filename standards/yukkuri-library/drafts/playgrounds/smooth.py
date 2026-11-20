"""Smooth-mesh kit for the playground equipment: swept, revolved and extruded surfaces with per-vertex normals, painted
from a clean atlas of the item-icon ramps.

The large playground props are modelled as real shapes (round sections, bevels, fillets) rather than 1/16 voxel cells.
Their textures stay flat and legible at 1x: each surface band samples one ramp step (a 4 x 4 swatch), a depth or height
shade samples a smooth vertical gradient strip, and the few printed details (plank seams, juice glints) are small
decals at one texel per unit. No noise tiles, no outlines; the game's directional light shades the curved normals.

Units are 1/16 block, +Y up. Every surface is built from rows of points (a strip); its normals are auto-smoothed inside
the strip with a crease angle and oriented by a `facing` hint, so a crease (a floor meeting a wall) stays sharp while a
round stays round. Exports the PropModels triangle mesh (eight floats per vertex), a Blockbench mesh model and the PNG.
"""
import base64, io, json, math, uuid
from PIL import Image


# ---- small vector helpers ---------------------------------------------------------------------------------------------
def sub(a, b): return (a[0] - b[0], a[1] - b[1], a[2] - b[2])
def add(a, b): return (a[0] + b[0], a[1] + b[1], a[2] + b[2])
def mul(a, k): return (a[0] * k, a[1] * k, a[2] * k)
def dot(a, b): return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
def cross(a, b): return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
def norm(a): return math.sqrt(dot(a, a))


def unit(a):
    n = norm(a)
    return (a[0] / n, a[1] / n, a[2] / n) if n > 1e-12 else (0.0, 1.0, 0.0)


def lerp(a, b, t):
    return a + (b - a) * t


def fillet(points, radius, steps=4, closed=False, radii=None):
    """Round the corners of a 2D polyline: each interior corner k becomes an arc of `radii[k]` (default `radius`)
    tangent to both edges, sampled with `steps` segments. Corners with radius 0 stay sharp."""
    pts = [tuple(p) for p in points]
    n = len(pts)
    out = []
    for k in range(n):
        r = radius if radii is None else radii[k]
        if (not closed and k in (0, n - 1)) or r <= 0:
            out.append(pts[k])
            continue
        p, a, b = pts[k], pts[k - 1], pts[(k + 1) % n]
        u = (a[0] - p[0], a[1] - p[1])
        w = (b[0] - p[0], b[1] - p[1])
        lu, lw = math.hypot(*u), math.hypot(*w)
        u, w = (u[0] / lu, u[1] / lu), (w[0] / lw, w[1] / lw)
        half = math.acos(max(-1.0, min(1.0, u[0] * w[0] + u[1] * w[1]))) / 2
        if half < 1e-6 or half > math.pi / 2 - 1e-6:
            out.append(p)
            continue
        d = min(r / math.tan(half), lu * .5, lw * .5)        # tangent distance along each edge
        r = d * math.tan(half)
        t0, t1 = (p[0] + u[0] * d, p[1] + u[1] * d), (p[0] + w[0] * d, p[1] + w[1] * d)
        bis = (u[0] + w[0], u[1] + w[1])
        lb = math.hypot(*bis)
        c = (p[0] + bis[0] / lb * r / math.sin(half), p[1] + bis[1] / lb * r / math.sin(half))
        a0, a1 = math.atan2(t0[1] - c[1], t0[0] - c[0]), math.atan2(t1[1] - c[1], t1[0] - c[0])
        sweep = (a1 - a0 + math.pi) % (2 * math.pi) - math.pi
        for i in range(steps + 1):
            ang = a0 + sweep * i / steps
            out.append((c[0] + r * math.cos(ang), c[1] + r * math.sin(ang)))
    return out


def resample(points, count):
    """`count` points spaced evenly along an open 2D/3D polyline (both ends kept)."""
    lengths = [0.0]
    for a, b in zip(points, points[1:]):
        lengths.append(lengths[-1] + math.dist(a, b))
    total, out, j = lengths[-1], [], 0
    for i in range(count):
        s = total * i / (count - 1)
        while j < len(points) - 2 and lengths[j + 1] < s:
            j += 1
        span = lengths[j + 1] - lengths[j] or 1.0
        t = (s - lengths[j]) / span
        out.append(tuple(lerp(points[j][k], points[j + 1][k], t) for k in range(len(points[0]))))
    return out


# ---- atlas ------------------------------------------------------------------------------------------------------------
def _pack(sizes):
    """Shelf packing, tallest first, into the smallest square power-of-two atlas (at least 16)."""
    order = sorted(range(len(sizes)), key=lambda i: (-sizes[i][1], -sizes[i][0], i))
    size = 16
    while True:
        slots, x, y, row = [None] * len(sizes), 0, 0, 0
        for i in order:
            w, h = sizes[i]
            if x + w > size:
                x, y, row = 0, y + row, 0
            if w > size or y + h > size:
                break
            slots[i] = (x, y)
            x += w
            row = max(row, h)
        else:
            return size, slots
        size *= 2


class Atlas:
    """Swatches (4 x 4 texels of one ramp step), vertical gradient strips (4 wide) and painted decals, packed on export.

    A vertex carries a uv spec: (key,) for a swatch, (key, t) with t in 0..1 down a gradient, (key, s, t) in decal texels."""

    def __init__(self, ramp):
        self.ramp, self.entries, self.slots, self.size = ramp, {}, None, None

    def swatch(self, name, step):
        key = ('s', name, step)
        if key not in self.entries:
            ramp = self.ramp(name)
            colour = ramp[max(0, min(len(ramp) - 1, step))]
            self.entries[key] = (4, 4, lambda image, x, y, c=colour: _fill(image, x, y, 4, 4, c))
        return key

    def gradient(self, name, stops, length=64):
        """stops: [(t, (ramp, step) or rgb)] with t rising from 0 to 1; linear in RGB between stops."""
        key = ('g', name)
        if key not in self.entries:
            cols = [(t, self.ramp(c[0])[c[1]] if isinstance(c[0], str) else c) for t, c in stops]

            def paint(image, x, y):
                for j in range(length):
                    t = (j + .5) / length
                    for (ta, ca), (tb, cb) in zip(cols, cols[1:]):
                        if t <= tb or (tb, cb) == cols[-1]:
                            f = 0 if tb == ta else max(0.0, min(1.0, (t - ta) / (tb - ta)))
                            rgb = tuple(round(lerp(ca[i], cb[i], f)) for i in range(3))
                            break
                    _fill(image, x, y + j, 4, 1, rgb)
            self.entries[key] = (4, length, paint)
        return key

    def decal(self, name, width, height, paint):
        """paint(set_texel) is called with set_texel(u, v, rgb) for u < width, v < height."""
        key = ('d', name)
        if key not in self.entries:
            def draw(image, x, y):
                paint(lambda u, v, rgb: image.putpixel((x + u, y + v), tuple(rgb) + (255,)))
            self.entries[key] = (width, height, draw)
        return key

    def layout(self):
        keys = list(self.entries)
        self.size, slots = _pack([self.entries[k][:2] for k in keys])
        self.slots = dict(zip(keys, slots))
        image = Image.new('RGBA', (self.size, self.size), (0, 0, 0, 0))
        for key in keys:
            self.entries[key][2](image, *self.slots[key])
        return image

    def texel(self, spec):
        """Texture pixel coordinates of a uv spec (after layout)."""
        key = spec[0]
        x, y = self.slots[key]
        w, h, _ = self.entries[key]
        if key[0] == 's':
            return x + 2.0, y + 2.0
        if key[0] == 'g':
            t = max(0.0, min(1.0, spec[1]))
            return x + 2.0, y + .5 + t * (h - 1)
        return x + max(.01, min(w - .01, spec[1])), y + max(.01, min(h - .01, spec[2]))


def _fill(image, x, y, w, h, rgb):
    for j in range(h):
        for i in range(w):
            image.putpixel((x + i, y + j), tuple(rgb) + (255,))


# ---- model ------------------------------------------------------------------------------------------------------------
class Model:
    """Named parts of triangles; each triangle corner is (position, normal, uv spec)."""

    def __init__(self, name, atlas):
        self.name, self.atlas, self.parts = name, atlas, []

    def part(self, name, unit=0):
        for p in self.parts:
            if p['name'] == name:
                return p
        p = {'name': name, 'unit': unit, 'tris': []}
        self.parts.append(p)
        return p

    def tri(self, part, corners):
        """corners: three (position, normal, uv spec); wound counter-clockwise around the mean normal."""
        (a, na, _), (b, nb, _), (c, nc, _) = corners
        g = cross(sub(b, a), sub(c, a))
        if norm(g) < 1e-9:
            return
        if dot(g, add(add(na, nb), nc)) < 0:
            corners = [corners[0], corners[2], corners[1]]
        part['tris'].append([(tuple(float(v) for v in p), unit(n), uv) for p, n, uv in corners])

    def strip(self, part, rows, paint, facing, closed=False, crease=30.0, smooth=True):
        """Surface between rows of points (all rows the same length); `closed` joins each row's last point to its first.

        paint(i, j, point) -> uv spec for corner `point` of quad (i, j) (row band i, segment j).
        facing(i, j, centre) -> a direction quad (i, j) should face (its normal is flipped to agree).
        Normals average the faces round each vertex within `crease` degrees of each other (flat when smooth=False)."""
        nr, n = len(rows), len(rows[0])
        segs = n if closed else n - 1
        faces = {}
        for i in range(nr - 1):
            for j in range(segs):
                p00, p01 = rows[i][j], rows[i][(j + 1) % n]
                p10, p11 = rows[i + 1][j], rows[i + 1][(j + 1) % n]
                g = add(cross(sub(p11, p00), sub(p10, p01)), (0.0, 0.0, 0.0))
                if norm(g) < 1e-9:
                    continue
                centre = mul(add(add(p00, p01), add(p10, p11)), .25)
                g = unit(g)
                if dot(g, facing(i, j, centre)) < 0:
                    g = mul(g, -1)
                faces[i, j] = (g, centre)
        limit = math.cos(math.radians(crease))

        def vertex_normal(vi, vj, own):
            acc = (0.0, 0.0, 0.0)
            for fi in (vi - 1, vi):
                for fj in (vj - 1, vj):
                    fj2 = fj % n if closed else fj
                    face = faces.get((fi, fj2))
                    if face and dot(face[0], own) >= limit:
                        acc = add(acc, face[0])
            return unit(acc) if norm(acc) > 1e-9 else own

        for (i, j), (g, _) in faces.items():
            corners = [(i, j), (i, j + 1), (i + 1, j + 1), (i + 1, j)]
            out = []
            for vi, vj in corners:
                p = rows[vi][vj % n]
                nn = vertex_normal(vi, vj, g) if smooth else g
                out.append((p, nn, paint(i, j, p)))
            self.tri(part, [out[0], out[1], out[2]])
            self.tri(part, [out[0], out[2], out[3]])

    def polygon(self, part, points, normal, paint):
        """Planar polygon (ear clipping), flat normal; paint(point) -> uv spec."""
        normal = unit(normal)
        ref = (1.0, 0.0, 0.0) if abs(normal[0]) < .9 else (0.0, 0.0, 1.0)
        u = unit(cross(normal, ref))
        v = cross(normal, u)
        flat = [(dot(p, u), dot(p, v)) for p in points]
        area = sum(flat[k - 1][0] * flat[k][1] - flat[k][0] * flat[k - 1][1] for k in range(len(flat)))
        index = list(range(len(points)))
        if area < 0:
            index.reverse()
        guard = 0
        while len(index) > 3 and guard < 10000:
            guard += 1
            for k in range(len(index)):
                a, b, c = index[k - 1], index[k], index[(k + 1) % len(index)]
                pa, pb, pc = flat[a], flat[b], flat[c]
                turn = (pb[0] - pa[0]) * (pc[1] - pa[1]) - (pb[1] - pa[1]) * (pc[0] - pa[0])
                if turn <= 1e-9:
                    continue
                if any(_inside(flat[m], pa, pb, pc) for m in index if m not in (a, b, c)):
                    continue
                self.tri(part, [(points[x], normal, paint(points[x])) for x in (a, b, c)])
                del index[k]
                break
            else:
                break
        if len(index) == 3:
            self.tri(part, [(points[x], normal, paint(points[x])) for x in index])

    def tube(self, part, path, radius, paint, seg=10, caps=(True, True), crease=40.0):
        """A round tube along a 3D polyline (parallel-transported frame), optional flat end caps."""
        frames = []
        prev = None
        for k, p in enumerate(path):
            a = path[max(0, k - 1)]
            b = path[min(len(path) - 1, k + 1)]
            t = unit(sub(b, a))
            if prev is None:
                ref = (0.0, 1.0, 0.0) if abs(t[1]) < .9 else (1.0, 0.0, 0.0)
                nrm = unit(cross(t, ref))
            else:
                nrm = unit(sub(prev, mul(t, dot(prev, t))))
            prev = nrm
            frames.append((p, t, nrm, cross(t, nrm)))
        rows = [[add(p, add(mul(nn, radius * math.cos(math.tau * j / seg)), mul(bn, radius * math.sin(math.tau * j / seg))))
                 for j in range(seg)] for p, t, nn, bn in frames]
        centres = list(path)

        def facing(i, j, c):
            return sub(c, mul(add(centres[i], centres[i + 1]), .5))
        self.strip(part, rows, paint, facing, closed=True, crease=crease)
        for end, flag in ((0, caps[0]), (len(path) - 1, caps[1])):
            if flag:
                t = frames[end][1]
                self.polygon(part, rows[end], mul(t, -1 if end == 0 else 1), lambda p: paint(0, 0, p))

    # ---- export ---------------------------------------------------------------------------------------------------------
    def export(self, origins=None):
        """Returns (mesh, bbmodel, png bytes); `origins` maps a part name to its Blockbench pivot."""
        image = self.atlas.layout()
        size = self.atlas.size
        vertices, parts, elements = [], [], []
        for index, part in enumerate(self.parts):
            start = len(vertices) // 8
            bb_vertices, bb_faces, ids = {}, {}, {}
            for tri in part['tris']:
                names = []
                for pos, nrm, spec in tri:
                    tu, tv = self.atlas.texel(spec)
                    p = [round(c, 3) + 0.0 for c in pos]
                    vertices.extend([*p, round(tu / size, 5) + 0.0, round(tv / size, 5) + 0.0,
                                     *[round(c, 3) + 0.0 for c in nrm]])
                    key = tuple(p)
                    if key not in ids:
                        ids[key] = str(len(ids))
                        bb_vertices[ids[key]] = p
                    names.append((ids[key], (round(tu, 3), round(tv, 3))))
                bb_faces[str(len(bb_faces))] = {'vertices': [k for k, _ in names], 'texture': 0,
                                                'uv': {k: list(uv) for k, uv in names}}
            parts.append({'name': part['name'], 'start': start, 'count': len(vertices) // 8 - start, 'unit': part['unit']})
            elements.append({'name': part['name'], 'type': 'mesh', 'origin': (origins or {}).get(part['name'], [0, 0, 0]),
                             'rotation': [0, 0, 0],
                             'uuid': str(uuid.uuid5(uuid.NAMESPACE_URL, f'mcyukkuri/playgrounds/{self.name}/{part["name"]}')),
                             'vertices': bb_vertices, 'faces': bb_faces})
        bounds = [[min(vertices[k::8]), max(vertices[k::8])] for k in range(3)]
        mesh = {'format': 1, 'units_per_block': 16, 'id': self.name, 'texture_size': [size, size],
                'vertices': vertices, 'parts': parts, 'bounds': bounds}
        stream = io.BytesIO()
        image.save(stream, format='PNG', optimize=True)
        png = stream.getvalue()
        bb = {'meta': {'format_version': '4.10', 'model_format': 'free'}, 'name': self.name,
              'resolution': {'width': size, 'height': size}, 'elements': elements,
              'outliner': [e['uuid'] for e in elements],
              'textures': [{'name': 'texture', 'source': 'data:image/png;base64,' + base64.b64encode(png).decode(),
                            'width': size, 'height': size, 'uv_width': size, 'uv_height': size, 'id': '0'}]}
        return mesh, bb, png


def _inside(p, a, b, c):
    def side(p1, p2, p3):
        return (p1[0] - p3[0]) * (p2[1] - p3[1]) - (p2[0] - p3[0]) * (p1[1] - p3[1])
    d1, d2, d3 = side(p, a, b), side(p, b, c), side(p, c, a)
    neg = d1 < -1e-9 or d2 < -1e-9 or d3 < -1e-9
    pos = d1 > 1e-9 or d2 > 1e-9 or d3 > 1e-9
    return not (neg and pos)


# ---- revolved shapes -------------------------------------------------------------------------------------------------
def revolve(model, part, profile, paint, seg=64, centre=(0.0, 0.0), crease=30.0):
    """Surface of revolution about the vertical axis through `centre`: profile [(r, y)] traversed with the solid on its
    right (outward over a top, down an outside wall), so each band faces its left side (-dy, dr) turned round the axis.
    paint(i, j, point) as in Model.strip; give each band i one swatch (a quad mixing two swatches would blend them)."""
    cx, cz = centre
    rows = [[(cx + r * math.cos(math.tau * j / seg), y, cz + r * math.sin(math.tau * j / seg)) for j in range(seg)]
            for r, y in profile]

    def facing(i, j, c):
        (r0, y0), (r1, y1) = profile[i], profile[i + 1]
        radial = unit((c[0] - cx, 0.0, c[2] - cz))
        return add(mul(radial, -(y1 - y0)), (0.0, r1 - r0, 0.0))
    model.strip(model.part(part) if isinstance(part, str) else part, rows, paint, facing, closed=True, crease=crease)
