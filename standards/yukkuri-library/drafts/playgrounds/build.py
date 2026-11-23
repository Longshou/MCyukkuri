"""Minecraft-style playground equipment: faceted low-poly and box assemblies with hand-painted 1-texel-per-unit pixel
textures from the inventory icons' colour ramps, and the matching physics.

16 units = one block, +Y up; a facility is entered from -Z and fits its 3 x 2 x 3 (slide 3 x 2 x 5) block footprint.
Every surface is flat-shaded (crisp faceted normals, no smooth-shaded blobs) and maps to its own painted pixel region
at ~1 texel per unit with a 1-px atlas gutter. Palettes come solely from item-icons/draw.py (deliberate highlights,
edge trim, seams, rivets, treads, coils — no random noise, no smooth RGB gradients).

ceramic_tunnel: a symmetric 16-sided glazed clay pipe lying along Z on a flat terracotta cradle, with matching stepped
raised collars at both ends (-Z and +Z), incised kiln bands around the barrel, and a faceted bore over a flat floor at
y 3 that darkens in crisp pixel steps toward the middle.
yukkuri_slide: a steel-and-teal playground slide built from crisp boxes and faceted chute segments: four treads with
anti-slip ribs and bright nosings, bolted side panels framed by square posts and mitered handrails, a teal deck and
U-trough chute with chamfered lips, curved run-out and exit support struts.
yukkuri_trampoline: a 16-sided round mini trampoline with a taut woven dark mat and target ring (`bed`, unit 2, pivot
at y 8), 16 visible radial steel coil springs and V-ring hooks between the mat and the steel ring, a segmented stitched
gold ring pad (top y 9) leaving the springs readable underneath, six braced iron legs with rubber feet, and a faceted
shaded deflection well under the bed so pressing down up to 2 units shows a taut dish with no gap.
yukkuri_pool (orange-juice tub, id kept for saves): a low 16-sided faceted white-enamel washtub with a navy rim, fluted
outer walls, pinstripe and foot ring, ceramic floor drain, symmetric box-section lug handles, an inner bench step at
z < -6.5 (top 3.25) and a two-plank wooden step stool with legs and stretcher outside at z -21.5..-16.6 (top 3.5).
yukkuri_pool_juice: four opaque pixel-art orange-juice surfaces (juice_u1..juice_u4, units 1..4) at y 2/3/4/5 with a
bright meniscus and crisp citrus sheen bars.
--check verifies every export.
"""
import argparse, base64, importlib.util, io, json, math, sys, uuid
from pathlib import Path
from PIL import Image

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
sys.path.insert(0, str(ROOT / 'scripts'))
from asset_export_checks import check_output, fingerprints, preserve_equivalent_png, preserve_negative_zero_mesh_json, provenance
from facility_outline import facility_outline


def _load(name, path):
    if name not in sys.modules:
        spec = importlib.util.spec_from_file_location(name, path)
        sys.modules[name] = module = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(module)
    return sys.modules[name]


KIT = HERE.parent / 'block-props/kit.py'
kit = _load('block_props_kit', KIT)          # loaded under its own name: care-props also has a `kit`
SNAPSHOT = ROOT / 'art/props/playgrounds'
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
DATA = ROOT / 'src/main/resources/data/mcyukkuri'
TAU = math.tau
PAD = 1


# ---- small vector & pixel helpers -------------------------------------------------------------------------------------
def sub(a, b): return (a[0] - b[0], a[1] - b[1], a[2] - b[2])
def add(a, b): return (a[0] + b[0], a[1] + b[1], a[2] + b[2])
def mul(a, k): return (a[0] * k, a[1] * k, a[2] * k)
def dot(a, b): return a[0] * b[0] + a[1] * b[1] + a[2] * b[2]
def cross(a, b): return (a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0])
def norm(a): return math.sqrt(dot(a, a))
def lerp(a, b, t): return a + (b - a) * t


def unit(a):
    n = norm(a)
    return (a[0] / n, a[1] / n, a[2] / n) if n > 1e-12 else (0.0, 1.0, 0.0)


def canvas(w, h, rgb=None):
    return Image.new('RGBA', (max(1, int(w)), max(1, int(h))), (tuple(rgb) + (255,)) if rgb else (0, 0, 0, 0))


def put(img, x, y, rgb, wrap_x=False):
    w, h = img.size
    if wrap_x:
        x %= w
    if 0 <= x < w and 0 <= y < h:
        img.putpixel((x, y), (rgb if len(rgb) == 4 else tuple(rgb) + (255,)))


def hline(img, y, rgb, x0=0, x1=None):
    w, h = img.size
    if 0 <= y < h:
        for x in range(max(0, x0), min(w, w if x1 is None else x1)):
            put(img, x, y, rgb)


def vline(img, x, rgb, y0=0, y1=None):
    w, h = img.size
    if 0 <= x < w:
        for y in range(max(0, y0), min(h, h if y1 is None else y1)):
            put(img, x, y, rgb)


def rect(img, x0, y0, x1, y1, rgb):
    for y in range(y0, y1):
        for x in range(x0, x1):
            put(img, x, y, rgb)


def frame_panel(w, h, ramp, fill=2, top=3, side=2, bottom=1, hi=4, dark=0, rivets=False):
    """Crisp pixel-art framed plate: bright top/left edge, dark bottom/right contact, optional corner rivets."""
    img = canvas(w, h, ramp[fill])
    hline(img, 0, ramp[top])
    vline(img, 0, ramp[top])
    hline(img, h - 1, ramp[bottom])
    vline(img, w - 1, ramp[bottom])
    if w >= 4 and h >= 4:
        put(img, 0, 0, ramp[hi])
        put(img, w - 1, h - 1, ramp[dark])
    if rivets and w >= 6 and h >= 6:
        for rx in (1, w - 2):
            for ry in (1, h - 2):
                put(img, rx, ry, ramp[dark])
                put(img, rx + (1 if rx == 1 else -1), ry, ramp[hi])
    return img


# ---- pixel-region atlas & flat-shaded mesh builder --------------------------------------------------------------------
def _pack(sizes):
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


class PixelAtlas:
    """Shelf-packed square power-of-two RGBA texture sheet with 1-px clamped/wrapped gutters."""

    def __init__(self):
        self.entries = {}
        self.slots = None
        self.size = None

    def add(self, key, img, wrap_x=False):
        if key not in self.entries:
            self.entries[key] = (img, wrap_x)
        return key

    def layout(self):
        keys = list(self.entries)
        sizes = [(self.entries[k][0].size[0] + 2 * PAD, self.entries[k][0].size[1] + 2 * PAD) for k in keys]
        self.size, slots = _pack(sizes)
        self.slots = {k: (x + PAD, y + PAD) for k, (x, y) in zip(keys, slots)}
        out = Image.new('RGBA', (self.size, self.size), (0, 0, 0, 0))
        for k, (ox, oy) in self.slots.items():
            img, wrap_x = self.entries[k]
            w, h = img.size
            for gy in range(-PAD, h + PAD):
                sy = min(max(gy, 0), h - 1)
                for gx in range(-PAD, w + PAD):
                    sx = gx % w if wrap_x else min(max(gx, 0), w - 1)
                    out.putpixel((ox + gx, oy + gy), img.getpixel((sx, sy)))
        return out


class Model:
    """Flat-shaded Minecraft prop mesh with per-region 1-texel-per-unit pixel art."""

    def __init__(self, name):
        self.name = name
        self.atlas = PixelAtlas()
        self.parts = []
        self._auto = 0

    def part(self, name, unit=0):
        for p in self.parts:
            if p['name'] == name:
                return p
        p = {'name': name, 'unit': unit, 'tris': []}
        self.parts.append(p)
        return p

    def reg(self, img, key=None, wrap_x=False):
        if key is None:
            self._auto += 1
            key = f'_r{self._auto}'
        return self.atlas.add(key, img, wrap_x=wrap_x)

    def tri(self, part, pts, normal, key, uvs):
        a, b, c = pts
        g = cross(sub(b, a), sub(c, a))
        if norm(g) < 1e-9:
            return
        n = unit(normal)
        if dot(g, n) < 0:
            pts = (a, c, b)
            uvs = (uvs[0], uvs[2], uvs[1])
        part['tris'].append((tuple(tuple(float(v) for v in p) for p in pts), n, key, tuple(uvs)))

    def quad(self, part, pts, facing, key, uvs):
        p0, p1, p2, p3 = pts
        g = cross(sub(p2, p0), sub(p3, p1))
        if norm(g) < 1e-9:
            g = cross(sub(p1, p0), sub(p2, p0))
        if norm(g) < 1e-9:
            return
        n = unit(g)
        if dot(n, facing) < 0:
            n = mul(n, -1)
        self.tri(part, (p0, p1, p2), n, key, (uvs[0], uvs[1], uvs[2]))
        self.tri(part, (p0, p2, p3), n, key, (uvs[0], uvs[2], uvs[3]))

    def box(self, part, lo, hi, faces):
        """Axis-aligned box [lo, hi]; `faces` maps direction -> PIL.Image or (key, PIL.Image)."""
        x0, y0, z0 = lo
        x1, y1, z1 = hi
        spec = {
            'up':    ([(x0, y1, z0), (x1, y1, z0), (x1, y1, z1), (x0, y1, z1)], (0, 1, 0)),
            'down':  ([(x0, y0, z1), (x1, y0, z1), (x1, y0, z0), (x0, y0, z0)], (0, -1, 0)),
            'north': ([(x1, y1, z0), (x0, y1, z0), (x0, y0, z0), (x1, y0, z0)], (0, 0, -1)),
            'south': ([(x0, y1, z1), (x1, y1, z1), (x1, y0, z1), (x0, y0, z1)], (0, 0, 1)),
            'east':  ([(x1, y1, z1), (x1, y1, z0), (x1, y0, z0), (x1, y0, z1)], (1, 0, 0)),
            'west':  ([(x0, y1, z0), (x0, y1, z1), (x0, y0, z1), (x0, y0, z0)], (-1, 0, 0)),
        }
        for side, entry in faces.items():
            if entry is None:
                continue
            key, img = entry if isinstance(entry, tuple) else (None, entry)
            key = self.reg(img, key)
            w, h = img.size
            pts, n = spec[side]
            self.quad(part, pts, n, key, [(0, 0), (w, 0), (w, h), (0, h)])

    def strip(self, part, rows, facing_fn, img, key=None, closed=False, wrap_x=False, us=None, vs=None):
        """Flat-shaded faceted strip between rows of points, mapped onto pixel-art `img`."""
        key = self.reg(img, key, wrap_x=wrap_x or closed)
        w, h = img.size
        nr, n = len(rows), len(rows[0])
        segs = n if closed else n - 1
        if us is None:
            us = [w * j / segs for j in range(segs + 1)]
        if vs is None:
            vs = [h * i / (nr - 1) for i in range(nr)]
        for i in range(nr - 1):
            for j in range(segs):
                j1 = (j + 1) % n
                p00, p01, p11, p10 = rows[i][j], rows[i][j1], rows[i + 1][j1], rows[i + 1][j]
                c = mul(add(add(p00, p01), add(p10, p11)), .25)
                uvs = [(us[j], vs[i]), (us[j + 1], vs[i]), (us[j + 1], vs[i + 1]), (us[j], vs[i + 1])]
                self.quad(part, [p00, p01, p11, p10], facing_fn(i, j, c), key, uvs)

    def polygon(self, part, points, normal, img, uv_fn, key=None):
        """Planar ear-clipped polygon with flat normal and pixel-art `img`; uv_fn(p) -> (u, v) in texels."""
        key = self.reg(img, key)
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
                self.tri(part, (points[a], points[b], points[c]), normal, key,
                         (uv_fn(points[a]), uv_fn(points[b]), uv_fn(points[c])))
                del index[k]
                break
            else:
                break
        if len(index) == 3:
            self.tri(part, (points[index[0]], points[index[1]], points[index[2]]), normal, key,
                     (uv_fn(points[index[0]]), uv_fn(points[index[1]]), uv_fn(points[index[2]])))

    def export(self, origins=None):
        image = self.atlas.layout()
        size = self.atlas.size
        vertices, parts, elements = [], [], []
        for part in self.parts:
            start = len(vertices) // 8
            bb_vertices, bb_faces, ids = {}, {}, {}
            for pts, nrm, key, uvs in part['tris']:
                ox, oy = self.atlas.slots[key]
                w, h = self.atlas.entries[key][0].size
                names = []
                for pos, (u, v) in zip(pts, uvs):
                    tu = ox + max(0.0, min(float(w), float(u)))
                    tv = oy + max(0.0, min(float(h), float(v)))
                    p = [round(c, 3) + 0.0 for c in pos]
                    vertices.extend([*p, round(tu / size, 5) + 0.0, round(tv / size, 5) + 0.0,
                                     *[round(c, 3) + 0.0 for c in nrm]])
                    vkey = tuple(p)
                    if vkey not in ids:
                        ids[vkey] = str(len(ids))
                        bb_vertices[ids[vkey]] = p
                    names.append((ids[vkey], (round(tu, 3), round(tv, 3))))
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
    return not ((d1 < -1e-9 or d2 < -1e-9 or d3 < -1e-9) and (d1 > 1e-9 or d2 > 1e-9 or d3 > 1e-9))


def boxes(cells):
    """Greedy merge of unit cells into [x0, y0, z0, x1, y1, z1] boxes (x runs, then z rows, then y slabs)."""
    left, result = set(cells), []
    for x, y, z in sorted(cells, key=lambda c: (c[1], c[2], c[0])):
        if (x, y, z) not in left:
            continue
        x1 = x
        while (x1 + 1, y, z) in left:
            x1 += 1
        z1 = z
        while all((i, y, z1 + 1) in left for i in range(x, x1 + 1)):
            z1 += 1
        y1 = y
        while all((i, y1 + 1, k) in left for i in range(x, x1 + 1) for k in range(z, z1 + 1)):
            y1 += 1
        for i in range(x, x1 + 1):
            for j in range(y, y1 + 1):
                for k in range(z, z1 + 1):
                    left.discard((i, j, k))
        result.append([x, y, z, x1 + 1, y1 + 1, z1 + 1])
    return result


def columns(heights):
    """Greedy merge of unit plan cells {(x, z): (y0, y1)} with equal spans into [x0, y0, z0, x1, y1, z1] boxes."""
    left, result = dict(heights), []
    for x, z in sorted(heights, key=lambda c: (c[1], c[0])):
        if (x, z) not in left:
            continue
        span = left[(x, z)]
        x1 = x
        while left.get((x1 + 1, z)) == span:
            x1 += 1
        z1 = z
        while all(left.get((i, z1 + 1)) == span for i in range(x, x1 + 1)):
            z1 += 1
        for i in range(x, x1 + 1):
            for k in range(z, z1 + 1):
                del left[(i, k)]
        result.append([x, round(span[0], 4), z, x1 + 1, round(span[1], 4), z1 + 1])
    return result


# ---- ceramic tunnel ---------------------------------------------------------------------------------------------------
# Symmetric 16-sided glazed clay pipe lying along Z with matching stepped raised collars at BOTH ends (-Z and +Z),
# sitting on a flat terracotta cradle; bore is faceted over a flat floor at y 3 (contract).
CY, RO, RS, RB, FLOOR = 14.5, 14.5, 15.8, 13.0, 3.0
TZ0, TZ1 = -22.0, 22.0
COLLAR_Z = 16.5        # both ends |z| >= 16.5 carry identical raised collars
PIPE_ARC = 16          # crisp 16-sided Minecraft faceted cylinder


def pipe_loop(R, clip):
    """16-sided faceted pipe section [(x, y)] above y = clip, counter-clockwise from right corner over top to left."""
    s = (clip - CY) / R
    a0 = -math.pi / 2 if s <= -1 else math.asin(s)
    # Use a fixed set of 16 faceted steps over the arc so all sections share topology cleanly
    arc = [(R * math.cos(a), CY + R * math.sin(a)) for a in (a0 + (math.pi - 2 * a0) * k / PIPE_ARC for k in range(PIPE_ARC + 1))]
    (rx, ry), (lx, ly) = arc[0], arc[-1]
    return arc + [(lerp(lx, rx, k / 4), lerp(ly, ry, k / 4)) for k in range(1, 4)]


def ceramic_tunnel():
    m = Model('ceramic_tunnel')
    terra = kit.ramp('TERRA')
    anko = kit.ramp('ANKO')
    pipe = m.part('pipe')

    # Symmetric outer profile: -Z mouth bevel -> -Z collar -> shoulder -> barrel -> shoulder -> +Z collar -> +Z mouth bevel.
    outer_prof = [
        (TZ0, RS - .6), (TZ0 + .6, RS), (-COLLAR_Z, RS), (-COLLAR_Z + .8, RO),
        (0.0, RO),
        (COLLAR_Z - .8, RO), (COLLAR_Z, RS), (TZ1 - .6, RS), (TZ1, RS - .6),
    ]
    # Build pixel-art exterior texture: u = around the pipe (48 px, ~2 px per facet), v = along Z (44 px, 1 px/unit).
    W, H = 48, 44
    ext = canvas(W, H, terra[2])
    for v in range(H):
        z_mid = TZ0 + v + .5
        in_collar = abs(z_mid) >= COLLAR_Z
        is_lip = v in (0, H - 1)
        is_shoulder = v in (5, 6, H - 7, H - 6)
        is_incised = v in (14, H - 15)    # two symmetric kiln-incised decorative rings on the barrel
        for u in range(W):
            # Map u around the section: u in [0..38] is the arc (right -> top at 19 -> left), [38..48] is flat bottom
            if u >= 38:
                step = 0
            else:
                t = u / 38.0              # 0 = right foot, 0.5 = crown, 1 = left foot
                elev = math.sin(t * math.pi)
                step = 3 if elev > .78 else 2 if elev > .32 else 1
            if in_collar:
                step = min(4, step + 1)
            if is_lip:
                step = min(4, step + 1)
            elif is_shoulder:
                step = max(0, step - 1)
            elif is_incised:
                step = max(0, step - 1)
            elif not in_collar and u == 21 and not is_shoulder:
                # Single crisp longitudinal glaze sheen line along the upper-left crown
                step = min(4, step + 1)
            put(ext, u, v, terra[step])

    # Compute exact v positions matching z along TZ0..TZ1
    vs = [max(0.0, min(float(H), z - TZ0)) for z, _ in outer_prof]
    # Open arc over the top (no y=0 flat bottom strip on the ground plane)
    rows = [[(x, y, z) for x, y in pipe_loop(R, 0.0)[:PIPE_ARC + 1]] for z, R in outer_prof]
    us_arc = [38.0 * j / PIPE_ARC for j in range(PIPE_ARC + 1)]
    m.strip(pipe, rows, lambda i, j, c: (c[0], c[1] - CY, 0.0),
            ext, 'tunnel_ext', closed=False, wrap_x=False, us=us_arc, vs=vs)
    # Full-loop u coordinates (used by bore and mouth rings)
    n_pts = len(pipe_loop(RO, 0.0))
    us = [38.0 * j / PIPE_ARC if j <= PIPE_ARC else 38.0 + 10.0 * (j - PIPE_ARC) / (n_pts - PIPE_ARC) for j in range(n_pts + 1)]

    # Symmetric flat cradle foot under the pipe so it sits solidly grounded on y = 0 (z in [-21, 21]);
    # closed on top (no open slit showing the ground) and open on the y = 0 bottom.
    cradle_side = frame_panel(42, 3, terra, fill=1, top=2, bottom=0, hi=2, dark=0)
    cradle_end = frame_panel(20, 3, terra, fill=1, top=2, bottom=0, hi=2, dark=0)
    cradle_top = frame_panel(20, 42, terra, fill=1, top=2, bottom=0, hi=2, dark=0)
    m.box(pipe, (-10.0, 0.0, TZ0 + 1.0), (10.0, 2.5, TZ1 - 1.0), {
        'up': ('cradle_top', cradle_top),
        'north': ('cradle_end', cradle_end), 'south': ('cradle_end', cradle_end),
        'east': ('cradle_side', cradle_side), 'west': ('cradle_side', cradle_side),
    })

    # Bore: faceted inner arch + flat floor at y = 3, darkening in crisp pixel-art steps from either mouth to the middle.
    bore_zs = [TZ0, TZ0 + 2, TZ0 + 6, TZ0 + 12, 0.0, TZ1 - 12, TZ1 - 6, TZ1 - 2, TZ1]
    bore_img = canvas(W, H, anko[1])
    for v in range(H):
        depth = min(v, H - 1 - v)
        is_seam = v in (6, 14, H - 15, H - 7)
        for u in range(W):
            on_floor = u >= 38
            if depth < 2:
                col = terra[3 if on_floor else 2]
            elif depth < 5:
                col = terra[2 if on_floor else 1]
            elif depth < 10:
                col = terra[1 if on_floor else 0]
            elif depth < 15:
                col = terra[0] if on_floor else anko[1]
            else:
                col = anko[1] if on_floor else anko[0]
            if is_seam and depth >= 3:
                col = anko[0]
            elif on_floor and u in (38, W - 1) and depth < 14:
                col = terra[0]    # crisp floor-wall corner shadow
            put(bore_img, u, v, col)

    b_rows = [[(x, y, z) for x, y in pipe_loop(RB, FLOOR)] for z in bore_zs]
    b_vs = [z - TZ0 for z in bore_zs]
    m.strip(pipe, b_rows, lambda i, j, c: (-c[0], CY - c[1], 0.0) if j < PIPE_ARC else (0.0, 1.0, 0.0),
            bore_img, 'tunnel_bore', closed=True, wrap_x=True, us=us, vs=b_vs)

    # Identical mouth face rings at BOTH ends (-Z and +Z): radial band strip (zero pixel jaggedness!).
    mouth_img = canvas(W, 4, terra[3])
    for u in range(W):
        on_floor = u >= 38
        elev = 0.0 if on_floor else math.sin((u / 38.0) * math.pi)
        mid = 4 if elev > .75 else 3 if elev > .25 else 2
        put(mouth_img, u, 0, terra[min(4, mid + 1)])   # bright outer bevel
        put(mouth_img, u, 1, terra[mid])               # mouth face
        put(mouth_img, u, 2, terra[max(1, mid - 1)])   # inner mouth face
        put(mouth_img, u, 3, terra[1])                 # crisp inner lip shadow
    for z, sign in ((TZ0, -1), (TZ1, 1)):
        o_pts = [(x, y, z) for x, y in pipe_loop(RS - .6, 0.0)]
        i_pts = [(x, y, z) for x, y in pipe_loop(RB, FLOOR)]
        m.strip(pipe, [o_pts, i_pts], lambda i, j, c, s=sign: (0.0, 0.0, float(s)),
                mouth_img, 'tunnel_mouth', closed=True, wrap_x=True, us=us, vs=[0.0, 4.0])

    # Symmetric collision cells matching the pipe walls and floor at y = 3.
    cells = set()
    for z in range(int(TZ0), int(TZ1)):
        R = RS if abs(z + .5) >= COLLAR_Z else RO
        for x in range(-17, 17):
            for y in range(0, 32):
                d = (x + .5) ** 2 + (y + .5 - CY) ** 2
                if (d <= R * R or (y < 3 and abs(x + .5) <= 10.0)) and not (d < RB * RB and y + .5 > FLOOR):
                    cells.add((x, y, z))
    return m, boxes(cells), {}


# ---- slide ------------------------------------------------------------------------------------------------------------
STEPS = ((-36, 4), (-28, 8), (-20, 12), (-12, 16))
DECK = (-4.0, 16.0)
K = 15 / 34
RUN = (31.0, 38.0)
LIP_Y = 16 - (RUN[0] - 2) * K - K * (RUN[1] - RUN[0]) / 2
CHUTE_END = 40.0


def chute_y(z):
    if z <= 1:
        return 16.0
    if z <= 3:
        return 16 - K * (z - 1) ** 2 / 4
    if z <= RUN[0]:
        return 16 - (z - 2) * K
    if z <= RUN[1]:
        return LIP_Y + K / (2 * (RUN[1] - RUN[0])) * (z - RUN[1]) ** 2
    return LIP_Y


def guard(z):
    t = max(0.0, min(1.0, (z - 0.0) / 6.0))
    return 6.0 - 2.0 * t * t * (3 - 2 * t)


def rail_y(z):
    """Handrail top height along the stair side: 10 at foot z=-37 rising linearly to 22 at z=-12, flat 22 to z=-4."""
    if z <= -37.0:
        return 10.0
    if z <= -12.0:
        return lerp(10.0, 22.0, (z + 37.0) / 25.0)
    return 22.0


def yukkuri_slide():
    m = Model('yukkuri_slide')
    iron = kit.ramp('IRON')
    teal = kit.ramp('TEAL')

    # 1. Steps & tower: crisp boxes with proud bright nosings and anti-slip pixel-art treads.
    steps = m.part('steps')
    tread_img = canvas(18, 8, iron[3])
    hline(tread_img, 0, iron[4])                     # bright front nosing highlight
    hline(tread_img, 2, iron[2], 2, 16)              # anti-slip embossed tread ribs
    hline(tread_img, 3, iron[4], 2, 16)
    hline(tread_img, 5, iron[2], 2, 16)
    hline(tread_img, 6, iron[4], 2, 16)
    hline(tread_img, 7, iron[1])                     # shadow at riser base
    vline(tread_img, 0, iron[2])
    vline(tread_img, 17, iron[2])

    riser_img = canvas(18, 4, iron[2])
    hline(riser_img, 0, iron[0])                     # deep shadow under overhanging nosing
    hline(riser_img, 3, iron[1])
    vline(riser_img, 0, iron[1])
    vline(riser_img, 17, iron[1])

    back_plate = frame_panel(18, 15, iron, fill=1, top=2, bottom=0, hi=3, dark=0, rivets=True)
    for idx, (z0, top) in enumerate(STEPS):
        z1 = z0 + 8 if idx < 3 else 1
        sup_top = top - 1.5 if idx == 3 else top - 1.0
        # Solid support under step (open on y = 0 ground plane)
        m.box(steps, (-9.0, 0.0, z0), (9.0, sup_top, z1), {
            'north': ('step_riser', riser_img),
            'south': ('tower_back', back_plate) if idx == 3 else None,
        })
        # Proud step tread cap (0.4 overhang at front); top step ends at DECK[0] where the chute deck starts
        tread_z1 = DECK[0] if idx == 3 else z1
        m.box(steps, (-9.0, top - 1.0, z0 - .4), (9.0, top, tread_z1), {
            'up': ('step_tread', tread_img),
            'north': ('step_nose', frame_panel(18, 1, iron, fill=4, top=4, bottom=3, hi=4, dark=2)),
            'down': ('step_riser', riser_img),
        })

    # 2. Framed steel side panels, square posts, footplates, and crisp mitered handrails.
    panels = m.part('panels')
    rails = m.part('handrails')
    # Side panel polygon under the stairs/deck (recessed slightly inside the posts so the posts read crisply!)
    panel_pts = [(-36.5, 0.0), (1.0, 0.0), (1.0, 21.0), (-12.0, 21.0), (-36.5, 9.0)]
    PW, PH = 38, 22
    panel_img = canvas(PW, PH, iron[2])
    for v in range(PH):
        for u in range(PW):
            if v >= PH - 2:
                col = iron[0]                        # ground contact shadow
            elif v in (6, 13):
                col = iron[1]                        # horizontal panel seam
            elif v in (7, 14):
                col = iron[3]                        # lower seam bevel
            elif u in (12, 24, 32):
                col = iron[1]                        # vertical structural seam
            else:
                col = iron[2]
            put(panel_img, u, v, col)
    # Crisp bolts/rivets along panel seams
    for ru in (4, 10, 16, 22, 28, 34):
        for rv in (10, 17):
            put(panel_img, ru, rv, iron[0])
            put(panel_img, ru + 1, rv, iron[3])

    for s in (-1, 1):
        x_in, x_out = sorted((s * 9.15, s * 10.2))
        for x, sign in ((x_in, -s), (x_out, s)):
            pts3 = [(x, y, z) for z, y in panel_pts]
            m.polygon(panels, pts3, (float(sign), 0.0, 0.0), panel_img,
                      lambda p: (p[2] + 37.0, 21.5 - p[1]), 'slide_panel')

        # Square vertical posts + footplates (foot z=-37, mid z=-21, deck front z=-4.5, deck back z=0.5)
        post_img = frame_panel(2, 12, iron, fill=3, top=4, bottom=1, hi=4, dark=0)
        cap_img = frame_panel(2, 2, iron, fill=4, top=4, bottom=2, hi=4, dark=1)
        foot_img = frame_panel(3, 2, iron, fill=1, top=2, bottom=0, hi=3, dark=0)
        for pz, ph in ((-37.0, 10.0), (-21.0, 17.6), (-4.5, 22.08), (0.5, 22.08)):
            px0, px1 = (s * 8.92, s * 10.58) if s > 0 else (-10.58, -8.92)
            m.box(rails, (px0, 0.0, pz - .75), (px1, ph, pz + .75), {
                'up': ('post_cap', cap_img), 'north': ('post_face', post_img), 'south': ('post_face', post_img),
                'east': ('post_face', post_img), 'west': ('post_face', post_img),
            })
            fx0, fx1 = (s * 9.05, s * 10.8) if s > 0 else (-10.8, -9.05)
            m.box(rails, (fx0, 0.0, pz - .92), (fx1, 1.0, pz + .92), {
                'up': ('foot_cap', foot_img), 'north': ('foot_cap', foot_img), 'south': ('foot_cap', foot_img),
                'east': ('foot_cap', foot_img), 'west': ('foot_cap', foot_img),
            })

        # Crisp square-section handrail along the stair slope and flat over the top step/deck
        rail_pts = [(-37.2, 10.0), (-12.0, 22.0), (-3.8, 22.0)]
        rx0, rx1 = (9.08, 10.42) if s > 0 else (-10.42, -9.08)
        rail_top = frame_panel(16, 2, iron, fill=4, top=4, bottom=3, hi=4, dark=2)
        rail_side = frame_panel(16, 2, iron, fill=3, top=4, bottom=1, hi=4, dark=0)
        for (za, ya), (zb, yb) in zip(rail_pts, rail_pts[1:]):
            # 4-sided square rail segment
            h_bar = 1.2
            p_top = [(rx0, ya, za), (rx1, ya, za), (rx1, yb, zb), (rx0, yb, zb)]
            p_bot = [(rx0, ya - h_bar, za), (rx1, ya - h_bar, za), (rx1, yb - h_bar, zb), (rx0, yb - h_bar, zb)]
            rk_top = m.reg(rail_top, 'rail_top')
            rk_side = m.reg(rail_side, 'rail_side')
            m.quad(rails, p_top, (0.0, 1.0, 0.0), rk_top, [(0, 0), (0, 2), (16, 2), (16, 0)])
            m.quad(rails, p_bot, (0.0, -1.0, 0.0), rk_side, [(0, 0), (0, 2), (16, 2), (16, 0)])
            m.quad(rails, [p_top[0], p_top[3], p_bot[3], p_bot[0]], (-1.0, 0.0, 0.0), rk_side, [(0, 0), (16, 0), (16, 2), (0, 2)])
            m.quad(rails, [p_top[1], p_top[2], p_bot[2], p_bot[1]], (1.0, 0.0, 0.0), rk_side, [(0, 0), (16, 0), (16, 2), (0, 2)])

    # 3. Teal enamel deck & faceted U-trough chute with chamfered side lips and exit support struts.
    chute = m.part('chute')
    # Clean faceted U-section (12 vertices around the cross-section, sharp creases, chamfered top lips)
    def chute_sec(h):
        return [
            (-10.5, -1.4), (10.5, -1.4), (10.5, h - .5), (10.0, h), (9.4, h), (9.0, h - .5),
            (9.0, 0.0), (-9.0, 0.0), (-9.0, h - .5), (-9.4, h), (-10.0, h), (-10.5, h - .5),
        ]
    # Clean piecewise-linear/faceted z stations (crisp Minecraft segments, exact bed height; 0.1 inset from z=40 block plane)
    zs = [DECK[0], 1.0, 3.0, 9.0, 15.0, 21.0, 27.0, RUN[0], 33.5, 36.0, RUN[1], CHUTE_END - 0.1]
    rows = [[(x, chute_y(z) + y, z) for x, y in chute_sec(guard(z))] for z in zs]
    # Pixel-art chute texture: u = around cross-section (48 px), v = along chute (44 px)
    CW, CH = 48, 44
    chute_img = canvas(CW, CH, teal[2])
    # Map u bands to the 12 edges of chute_sec:
    # 0: underside (-10.5..10.5), 1: right outer wall, 2-4: right chamfered lip, 5: right inner wall,
    # 6: bed (9..-9), 7: left inner wall, 8-10: left chamfered lip, 11: left outer wall
    us_c = [0, 8, 12, 13, 15, 16, 20, 38, 42, 43, 45, 46, 48]
    for v in range(CH):
        seam = v in (5, 13, 21, 29, 37)              # crisp enamel panel seams along the chute
        end_lip = v >= CH - 2
        for u in range(CW):
            if u < 8:
                col = teal[0] if seam else teal[1]   # underside
            elif u < 12 or u >= 46:
                col = teal[1] if seam else teal[2]   # outer side walls
            elif (12 <= u < 16) or (42 <= u < 46):
                col = teal[4] if u in (13, 14, 43, 44) else teal[3]   # bright chamfered lips
            elif (16 <= u < 20) or (38 <= u < 42):
                col = teal[1] if u in (19, 38) else teal[2]           # inner walls + bed corner shadow
            else:
                # Sliding bed (u in 20..38): bright gloss stripe down left side, crisp seam lines
                if end_lip:
                    col = teal[4]
                elif seam:
                    col = teal[2]
                elif u in (22, 23):
                    col = teal[4]
                else:
                    col = teal[3]
            put(chute_img, u, v, col)
    vs_c = [max(0.0, min(float(CH), z - DECK[0])) for z in zs]
    sec_ref = chute_sec(4.0)
    ns = len(sec_ref)

    def chute_facing(i, j, c):
        (xa, ya), (xb, yb) = sec_ref[j], sec_ref[(j + 1) % ns]
        return (yb - ya, -(xb - xa), 0.0)
    m.strip(chute, rows, chute_facing, chute_img, 'chute_body', closed=True, wrap_x=True, us=us_c, vs=vs_c)

    # End caps of the chute (+Z exit lip and -Z deck edge)
    cap_teal = frame_panel(22, 6, teal, fill=2, top=4, bottom=1, hi=4, dark=0)
    m.polygon(chute, rows[-1], (0.0, 0.0, 1.0), cap_teal, lambda p: (p[0] + 11.0, 5.5 - (p[1] - LIP_Y)), 'chute_cap')

    # Crisp steel support struts under the chute run-out (z=35..36.5) so the exit sits grounded.
    strut_img = frame_panel(2, 3, iron, fill=2, top=3, bottom=0, hi=3, dark=0)
    for sx in (-1, 1):
        x0, x1 = (8.8, 10.2) if sx > 0 else (-10.2, -8.8)
        m.box(rails, (x0, 0.0, 35.0), (x1, LIP_Y + .2, 36.5), {
            'north': ('strut', strut_img), 'south': ('strut', strut_img),
            'east': ('strut', strut_img), 'west': ('strut', strut_img),
        })

    # Exact contract collision: steps, deck, sampled chute, chute walls, side panels.
    collision = [[-9, 0, z, 9, top, z + 8] for z, top in STEPS[:-1]] + [[-9, 0, -12, 9, 16, 1]]
    for z in range(1, int(CHUTE_END)):
        lo = max(0.0, chute_y(z + 1) - 1.5)
        collision.append([-9, round(lo, 4), z, 9, round(chute_y(z), 4), z + 1])
    for z in range(int(DECK[0]), int(CHUTE_END)):
        lo = round(max(0.0, chute_y(z + 1) - 1.5), 4)
        hi = round(chute_y(z) + guard(z), 4)
        collision += [[-10.5, lo, z, -9, hi, z + 1], [9, lo, z, 10.5, hi, z + 1]]
    for z in range(-37, int(DECK[0])):
        hi = round(max(rail_y(z), rail_y(z + 1)), 4)
        collision += [[-10.5, 0, z, -9, hi, z + 1], [9, 0, z, 10.5, hi, z + 1]]
    return m, collision, {}


# ---- trampoline -------------------------------------------------------------------------------------------------------
# 16-sided faceted mini trampoline: taut woven jumping mat (`bed`, unit 2, pivot at y 8), 16 visible radial steel coil
# springs bridging to a faceted steel ring on 6 braced legs with rubber feet, a stitched segmented gold ring pad (top y 9)
# overhanging the springs so they stay visible, and a faceted deflection well under the bed.
BED, RIM = 8.0, 9.0
BED_R, PAD_IN, PAD_OUT = 13.8, 15.6, 19.4
WELL = ((15.7, 7.90), (5.0, 5.65))
LEGS = 6
LEG_R = 18.2
TRAMP_SEG = 16


def yukkuri_trampoline():
    m = Model('yukkuri_trampoline')
    gold = kit.ramp('GOLD')
    iron = kit.ramp('IRON')
    ink = kit.ramp('INK')
    frame = m.part('frame')

    # 1. Segmented stitched gold safety pad (y 7.6..9.0, r 15.0..19.4): 16 crisp pie-cut pad segments!
    # Leaves a clean open gap below y 7.6 so the steel springs and frame ring read clearly!
    pad_prof = [
        (PAD_IN, 7.6), (PAD_IN, 8.2), (PAD_IN + .7, RIM), (PAD_OUT - .7, RIM),
        (PAD_OUT, 8.3), (PAD_OUT, 7.5), (PAD_OUT - .5, 7.3), (PAD_IN + .3, 7.3), (PAD_IN, 7.6),
    ]
    PW, PH = 64, 12           # 4 texels per segment around the 16-sided ring!
    pad_img = canvas(PW, PH, gold[2])
    for v in range(PH):
        for u in range(PW):
            seam = (u % 4 == 0)
            if v in (0, 1):
                col = gold[1]                        # inner lip facing mat
            elif v in (2, 3, 4, 5):
                # Top surface: bright outer piping, stitch seams between the 16 pad segments
                if v == 5:
                    col = gold[3]                    # bright outer piping
                elif seam:
                    col = gold[1]                    # stitched segment seam
                elif v == 2:
                    col = gold[3]                    # inner crest highlight
                else:
                    col = gold[2]
            elif v in (6, 7):
                col = gold[0] if seam else gold[1]   # outer skirt lip
            else:
                col = gold[0]                        # underside
            put(pad_img, u, v, col)
    rows = [[(r * math.cos(TAU * j / TRAMP_SEG), y, r * math.sin(TAU * j / TRAMP_SEG)) for j in range(TRAMP_SEG)]
            for r, y in pad_prof]
    vs_p = [0, 1.5, 2.5, 5.5, 6.5, 7.8, 8.8, 10.5, 12.0]

    def rev_facing(prof):
        def f(i, j, c):
            (r0, y0), (r1, y1) = prof[i], prof[i + 1]
            rad = unit((c[0], 0.0, c[2]))
            return add(mul(rad, -(y1 - y0)), (0.0, r1 - r0, 0.0))
        return f
    m.strip(frame, rows, rev_facing(pad_prof), pad_img, 'tramp_pad', closed=True, wrap_x=True, vs=vs_p)

    # 2. Faceted steel ring (y 5.4..6.8, r 17.6..18.8) — crisp box-section 16-sided hoop!
    ring_prof = [(17.6, 5.4), (17.6, 6.8), (18.8, 6.8), (18.8, 5.4), (17.6, 5.4)]
    ring_img = canvas(64, 8, iron[2])
    for v in range(8):
        for u in range(64):
            bolt = (u % 11 == 5) and v in (3, 4)
            if v < 2:
                col = iron[1]                        # inner face
            elif v < 4:
                col = iron[4]                        # lit top
            elif v < 6:
                col = iron[0] if bolt else iron[3]   # outer steel band with leg bolts
            else:
                col = iron[0]                        # underside
            put(ring_img, u, v, col)
    r_rows = [[(r * math.cos(TAU * j / TRAMP_SEG), y, r * math.sin(TAU * j / TRAMP_SEG)) for j in range(TRAMP_SEG)]
              for r, y in ring_prof]
    m.strip(frame, r_rows, rev_facing(ring_prof), ring_img, 'tramp_ring', closed=True, wrap_x=True, vs=[0, 2, 4, 6, 8])

    # 3. 16 visible radial steel coil springs bridging from the mat edge (r 13.6) across the open spring channel
    #    to the outer steel ring under the gold pad (r 17.6), clearly readable from both above and the side!
    spring_img = canvas(10, 2, iron[2])
    for u in range(10):
        # Alternating bright/dark coil bands -> immediately reads as coiled steel tension springs!
        put(spring_img, u, 0, iron[4] if u % 2 == 0 else iron[1])
        put(spring_img, u, 1, iron[3] if u % 2 == 0 else iron[0])
    s_key = m.reg(spring_img, 'tramp_spring')
    for k in range(TRAMP_SEG):
        ang = TAU * (k + .5) / TRAMP_SEG
        ca, sa = math.cos(ang), math.sin(ang)
        tx, tz = -sa * .45, ca * .45
        r0, r1 = 13.5, 17.8
        y_lo, y_hi = 7.25, 8.02
        p = [
            (r0 * ca - tx, y_hi, r0 * sa - tz), (r0 * ca + tx, y_hi, r0 * sa + tz),
            (r1 * ca + tx, y_hi - .45, r1 * sa + tz), (r1 * ca - tx, y_hi - .45, r1 * sa - tz),
            (r0 * ca - tx, y_lo, r0 * sa - tz), (r0 * ca + tx, y_lo, r0 * sa + tz),
            (r1 * ca + tx, y_lo - .45, r1 * sa + tz), (r1 * ca - tx, y_lo - .45, r1 * sa - tz),
        ]
        uv = [(0, 0), (0, 2), (10, 2), (10, 0)]
        m.quad(frame, [p[0], p[1], p[2], p[3]], (0.0, 1.0, 0.0), s_key, uv)
        m.quad(frame, [p[4], p[5], p[6], p[7]], (0.0, -1.0, 0.0), s_key, uv)
        m.quad(frame, [p[0], p[3], p[7], p[4]], (-tx, 0.0, -tz), s_key, [(0, 0), (10, 0), (10, 2), (0, 2)])
        m.quad(frame, [p[1], p[2], p[6], p[5]], (tx, 0.0, tz), s_key, [(0, 0), (10, 0), (10, 2), (0, 2)])

    # 4. Six sturdy square-section iron legs with thick rubber feet!
    leg_img = frame_panel(2, 5, iron, fill=2, top=3, bottom=0, hi=4, dark=0)
    foot_img = frame_panel(3, 2, ink, fill=1, top=2, bottom=0, hi=3, dark=0)
    for k in range(LEGS):
        ang = TAU * (k + .5) / LEGS
        cx, cz = LEG_R * math.cos(ang), LEG_R * math.sin(ang)
        m.box(frame, (cx - .7, 1.2, cz - .7), (cx + .7, 5.6, cz + .7), {
            'north': ('tramp_leg', leg_img), 'south': ('tramp_leg', leg_img),
            'east': ('tramp_leg', leg_img), 'west': ('tramp_leg', leg_img),
        })
        m.box(frame, (cx - 1.0, 0.0, cz - 1.0), (cx + 1.0, 1.4, cz + 1.0), {
            'up': ('tramp_foot', foot_img),
            'north': ('tramp_foot', foot_img), 'south': ('tramp_foot', foot_img),
            'east': ('tramp_foot', foot_img), 'west': ('tramp_foot', foot_img),
        })

    # 5. Static deflection well under the bed (unit 0): faceted dark cone with radial tension creases!
    well = m.part('well')
    (r_out, y_out), (r_in, y_in) = WELL
    well_img = canvas(32, 12, ink[1])
    for v in range(12):
        for u in range(32):
            crease = (u % 2 == 0) and v in (2, 3, 4, 5, 6, 7)
            if v < 2:
                col = ink[0]
            elif v >= 10:
                col = ink[2]
            else:
                col = ink[0] if crease else ink[1]
            put(well_img, u, v, col)
    w_prof = [(r_in, y_in), (lerp(r_in, r_out, .5), lerp(y_in, y_out, .5)), (r_out, y_out)]
    w_rows = [[(r * math.cos(TAU * j / TRAMP_SEG), y, r * math.sin(TAU * j / TRAMP_SEG)) for j in range(TRAMP_SEG)]
              for r, y in w_prof]
    m.strip(well, w_rows, rev_facing(w_prof), well_img, 'tramp_well', closed=True, wrap_x=True, vs=[0, 6, 12])

    # 6. Taut woven jumping mat (`bed`, unit 2, origin [0, 8, 0]): crisp pixel-art weave, target ring & bound border!
    bed = m.part('bed', unit=2)
    MW = 32
    mat_img = canvas(MW, MW, ink[2])
    for v in range(MW):
        for u in range(MW):
            x, z = u + .5 - MW / 2, v + .5 - MW / 2
            r = math.hypot(x, z)
            if r < 1.5:
                col = ink[3]                         # center bullseye
            elif 2.3 <= r <= 3.2:
                col = ink[4]                         # crisp target bounce ring
            elif 12.2 <= r <= 13.1:
                col = ink[3]                         # taut stitched perimeter binding
            elif r > 13.1:
                col = ink[1]                         # bound mat edge
            elif (u % 3 == 0 and v % 3 == 0):
                col = ink[1]                         # subtle taut woven mesh dots (clean, non-noisy)
            else:
                col = ink[2]
            put(mat_img, u, v, col)
    bed_pts_top = [(BED_R * math.cos(TAU * j / TRAMP_SEG), BED, BED_R * math.sin(TAU * j / TRAMP_SEG)) for j in range(TRAMP_SEG)]
    bed_pts_bot = [(x, BED - .6, z) for x, _, z in bed_pts_top]
    m.polygon(bed, bed_pts_top, (0.0, 1.0, 0.0), mat_img, lambda p: (p[0] + MW / 2, p[2] + MW / 2), 'tramp_mat')
    m.polygon(bed, bed_pts_bot, (0.0, -1.0, 0.0), canvas(4, 4, ink[0]), lambda p: (2.0, 2.0), 'tramp_mat_bot')
    edge_img = canvas(32, 2, ink[1])
    hline(edge_img, 0, gold[2])
    hline(edge_img, 1, ink[0])
    m.strip(bed, [bed_pts_top, bed_pts_bot], lambda i, j, c: (c[0], 0.0, c[2]), edge_img, 'tramp_mat_edge', closed=True, wrap_x=True)

    # Contract collision: ONLY the bed disc tops at 8.0, pad ring at 9.0 over 6.0, legs 0..6.0.
    cells = {}
    for x in range(-20, 20):
        for z in range(-20, 20):
            r = math.hypot(x + .5, z + .5)
            if r < 14.6:
                cells[(x, z)] = (BED - 1, BED)
            elif r <= PAD_OUT:
                cells[(x, z)] = (6.0, RIM)
    collision = columns(cells)
    legs = set()
    for k in range(LEGS):
        ang = TAU * (k + .5) / LEGS
        cx, cz = LEG_R * math.cos(ang), LEG_R * math.sin(ang)
        legs |= {(x, z) for x in range(-20, 20) for z in range(-20, 20) if math.hypot(x + .5 - cx, z + .5 - cz) < 1.0}
    collision += columns({c: (0.0, 6.0) for c in legs})
    return m, collision, {'origin': {'bed': [0, BED, 0]}}


# ---- orange-juice tub -------------------------------------------------------------------------------------------------
# Contract numbers (data/mcyukkuri/playgrounds/yukkuri_pool.json + play/JuiceTub.java):
# floor 1, rim 7, bench 3.25 at z < -6.5, stool 3.5 at x -4.5..4.5 z -21.5..-16.6, rim at z -15, bench entry z -11.5,
# juice levels 1-4 = 2/3/4/5.
TUB_HX, TUB_HZ, TUB_RC = 21.0, 14.5, 7.5   # inner half-extents at wall top, beveled corner radius
FLOOR_Y, WALL_TOP, RIM_Y = 1.0, 6.1, 7.0
WALL_FOOT = -1.5                            # slight outward flare from floor to rim
BENCH = (-6.5, 3.25)
LEVELS = (2.0, 3.0, 4.0, 5.0)
STOOL = ((-4.5, -21.5), (4.5, -16.6), 3.5)
CORNER_ARC = 3                              # 3 facets per corner -> crisp 16-sided faceted rounded-rectangle


def tub_loop(o, y):
    """16-sided faceted rounded-rectangle plan at offset o (both half-axes grown by o) at height y."""
    cx, cz = TUB_HX - TUB_RC, TUB_HZ - TUB_RC
    r = max(.5, TUB_RC + o)
    quads = [(1, 1), (-1, 1), (-1, -1), (1, -1)]
    pts = []
    for q, (sx, sz) in enumerate(quads):
        a0 = q * math.pi / 2
        for k in range(CORNER_ARC + 1):
            a = a0 + (math.pi / 2) * k / CORNER_ARC
            pts.append((sx * cx + r * math.cos(a), y, sz * cz + r * math.sin(a)))
    return pts


def tub_offset(x, z):
    """Signed offset o of (x, z) from the inner rim rounded-rectangle (o = 0 at inner rim)."""
    cx, cz = TUB_HX - TUB_RC, TUB_HZ - TUB_RC
    dx, dz = max(0.0, abs(x) - cx), max(0.0, abs(z) - cz)
    if dx > 0 or dz > 0:
        return math.hypot(dx, dz) - TUB_RC
    return max(abs(x) - TUB_HX, abs(z) - TUB_HZ)


def wall_offset(y):
    return WALL_FOOT + (y - FLOOR_Y) * (-WALL_FOOT) / (WALL_TOP - FLOOR_Y)


def wall_height(o):
    return FLOOR_Y + (o - WALL_FOOT) * (WALL_TOP - FLOOR_Y) / (-WALL_FOOT)


def tub_x_span(z, o):
    """Half-width in x of the rounded-rectangle at offset o along line z."""
    cx, cz = TUB_HX - TUB_RC, TUB_HZ - TUB_RC
    r = max(.5, TUB_RC + o)
    dz = max(0.0, abs(z) - cz)
    if dz >= r:
        return 0.0
    return cx + math.sqrt(max(0.0, r * r - dz * dz))


def yukkuri_pool():
    m = Model('yukkuri_pool')
    white = kit.ramp('WHITE')
    navy = kit.ramp('SKY')
    wood = kit.ramp('WOOD')
    iron = kit.ramp('IRON')

    # 1. Faceted white-enamel washtub shell with crisp navy rim, vertical fluting, navy pinstripe & foot ring.
    tub = m.part('tub')
    prof = [
        (-2.5, FLOOR_Y), (WALL_FOOT, FLOOR_Y), (0.0, WALL_TOP), (0.0, RIM_Y),
        (1.25, RIM_Y), (1.25, 6.0), (.85, 5.8), (-.8, .8), (-1.2, 0.0),
    ]
    # Painted 1-texel-per-unit band around the tub (perimeter ~128 units -> W = 128, H = 16 rows)
    TW, TH = 128, 16
    band = canvas(TW, TH, white[3])
    for u in range(TW):
        flute = (u % 4 == 0)
        # Rows 0..4: inside wall (floor -> rim)
        put(band, u, 0, white[2])
        put(band, u, 1, white[2] if flute else white[3])
        put(band, u, 2, white[3])
        put(band, u, 3, white[4])
        put(band, u, 4, white[3])                    # clean bright inner lip meeting the navy rim
        # Rows 5..7: navy rolled rim (top + outer edge)
        put(band, u, 5, navy[2])                     # lit rim top
        put(band, u, 6, navy[1])                     # outer rim face
        put(band, u, 7, navy[0])                     # rim underside lip
        # Rows 8..13: white enamel outer wall with pressed vertical fluting & navy pinstripe
        put(band, u, 8, white[2])                    # shadow under rim overhang
        put(band, u, 9, white[4] if flute else white[3])
        put(band, u, 10, navy[1])                    # crisp navy pinstripe around the tub!
        put(band, u, 11, white[4] if flute else white[3])
        put(band, u, 12, white[3] if flute else white[2])
        put(band, u, 13, white[1])
        # Rows 14..15: navy foot ring & underside
        put(band, u, 14, navy[1])
        put(band, u, 15, navy[0])

    rows = [tub_loop(o, y) for o, y in prof]
    # Arc-length u coordinates around the rounded-rectangle so pixels stay square (1:1 density)
    ref = tub_loop(0.0, 0.0)
    acc = [0.0]
    for a, b in zip(ref, ref[1:] + [ref[0]]):
        acc.append(acc[-1] + math.hypot(b[0] - a[0], b[2] - a[2]))
    us = [TW * s / acc[-1] for s in acc]
    vs = [0.0, 0.5, 4.8, 5.2, 6.2, 7.2, 8.0, 13.8, 15.0]

    def tub_facing(i, j, c):
        (o0, y0), (o1, y1) = prof[i], prof[i + 1]
        cx, cz = TUB_HX - TUB_RC, TUB_HZ - TUB_RC
        rx = max(0.0, abs(c[0]) - cx) * (1 if c[0] >= 0 else -1)
        rz = max(0.0, abs(c[2]) - cz) * (1 if c[2] >= 0 else -1)
        rad = unit((rx, 0.0, rz)) if (rx or rz) else unit((c[0] / TUB_HX, 0.0, c[2] / TUB_HZ))
        return add(mul(rad, -(y1 - y0)), (0.0, o1 - o0, 0.0))
    m.strip(tub, rows, tub_facing, band, 'tub_band', closed=True, wrap_x=True, us=us, vs=vs)

    # Interior floor at y = 1.0 with subtle glazed ceramic tile grid and a central metal drain grate!
    FW, FH = 44, 32
    floor_img = canvas(FW, FH, white[3])
    for v in range(FH):
        for u in range(FW):
            if u % 6 == 0 or v % 6 == 0:
                put(floor_img, u, v, white[2])       # crisp subtle enamel floor seams
            x, z = u + .5 - FW / 2, v + .5 - FH / 2
            # Small central drain grate at (0, 3) (under adult_alone center, visible when empty!)
            r = math.hypot(x, z - 3.0)
            if r <= 2.3:
                put(floor_img, u, v, iron[0] if (u + v) % 2 == 0 and r < 1.5 else iron[2] if r < 1.7 else iron[3])
    m.polygon(tub, tub_loop(-2.5, FLOOR_Y), (0.0, 1.0, 0.0), floor_img,
              lambda p: (p[0] + FW / 2, p[2] + FH / 2), 'tub_floor')

    # 2. Inner front bench step (z < -6.5, top y = 3.25): crisp enamel step with bright edge & anti-slip ribs.
    bench = m.part('bench')
    zb, yb = BENCH
    bx = tub_x_span(zb, wall_offset(yb))
    # Bench top polygon clipped to the inner tub contour at y = yb
    b_loop = [p for p in tub_loop(wall_offset(yb), yb) if p[2] < zb - .05]
    b_loop.sort(key=lambda p: p[0])
    bench_top_pts = [(-bx, yb, zb)] + b_loop + [(bx, yb, zb)]
    BW, BH = 40, 10
    bench_top_img = canvas(BW, BH, white[3])
    hline(bench_top_img, BH - 1, white[4])           # bright front edge highlight
    hline(bench_top_img, BH - 3, white[2], 4, BW - 4)  # anti-slip groove
    hline(bench_top_img, BH - 5, white[2], 4, BW - 4)
    m.polygon(bench, bench_top_pts, (0.0, 1.0, 0.0), bench_top_img,
              lambda p: (p[0] + BW / 2, p[2] - (zb - BH)), 'bench_top')
    # Vertical front riser of bench (z = zb, y = FLOOR_Y..yb)
    bx_bot = tub_x_span(zb, wall_offset(FLOOR_Y))
    riser_img = frame_panel(38, 3, white, fill=2, top=3, bottom=1, hi=4, dark=1)
    m.quad(bench, [(-bx, yb, zb), (bx, yb, zb), (bx_bot, FLOOR_Y, zb), (-bx_bot, FLOOR_Y, zb)],
           (0.0, 0.0, 1.0), m.reg(riser_img, 'bench_riser'), [(0, 0), (38, 0), (38, 3), (0, 3)])

    # 3. Symmetric box-section navy lug handles on both ends (x = ±22.2, y = 4.6..5.6).
    lugs = m.part('handles')
    lug_img = frame_panel(4, 2, navy, fill=1, top=2, bottom=0, hi=3, dark=0)
    for s in (-1, 1):
        # Two short standoff arms rooted cleanly in the outer wall (x=±21.2..±21.8, stopping at the grip bar) + outer grip bar
        x_wall = s * 21.2
        x_arm = s * 21.8
        x_out = s * 22.8
        ax0, ax1 = sorted((x_wall, x_arm))
        gx0, gx1 = sorted((x_arm, x_out))
        for sz in (-1, 1):
            z0, z1 = (sz * 2.2, sz * 3.3) if sz > 0 else (-3.3, -2.2)
            m.box(lugs, (ax0, 4.7, z0), (ax1, 5.6, z1), {
                'up': ('lug', lug_img), 'down': ('lug', lug_img),
                'north': ('lug', lug_img), 'south': ('lug', lug_img),
            })
        m.box(lugs, (gx0, 4.7, -3.3), (gx1, 5.6, 3.3), {
            'up': ('lug', lug_img), 'down': ('lug', lug_img),
            'north': ('lug', lug_img), 'south': ('lug', lug_img),
            'east': ('lug', lug_img), 'west': ('lug', lug_img),
        })

    # 4. Crafted two-plank wooden step stool outside the front rim (x -4.5..4.5, z -21.5..-16.6, top 3.5).
    stool = m.part('stool')
    (x0, z0), (x1, z1), sh = STOOL
    # Top deck (two planks with seam, subtle grain lines & corner wooden pegs!)
    deck_img = canvas(10, 6, wood[3])
    hline(deck_img, 0, wood[4])                      # front lit edge
    hline(deck_img, 2, wood[2], 1, 9)                # wood grain
    hline(deck_img, 3, wood[1])                      # plank seam between the two boards!
    hline(deck_img, 5, wood[2])                      # rear edge
    for px in (1, 8):
        for py in (1, 4):
            put(deck_img, px, py, wood[0])           # dowel pegs
    edge_img = frame_panel(10, 2, wood, fill=2, top=3, bottom=1, hi=4, dark=0)
    side_img = frame_panel(6, 2, wood, fill=2, top=3, bottom=1, hi=4, dark=0)
    m.box(stool, (x0, sh - 1.0, z0), (x1, sh, z1), {
        'up': ('stool_top', deck_img), 'down': ('stool_edge', edge_img),
        'north': ('stool_edge', edge_img), 'south': ('stool_edge', edge_img),
        'east': ('stool_side', side_img), 'west': ('stool_side', side_img),
    })
    # Two sturdy side legs + recessed cross stretcher underneath so daylight shows under the stool!
    leg_img = frame_panel(5, 3, wood, fill=2, top=2, bottom=0, hi=3, dark=0)
    for lx0, lx1 in ((x0 + .3, x0 + 1.5), (x1 - 1.5, x1 - .3)):
        m.box(stool, (lx0, 0.0, z0 + .3), (lx1, sh - 1.0, z1 - .3), {
            'north': ('stool_leg', leg_img), 'south': ('stool_leg', leg_img),
            'east': ('stool_leg', leg_img), 'west': ('stool_leg', leg_img),
        })
    m.box(stool, (x0 + 1.5, 1.0, (z0 + z1) / 2 - .5), (x1 - 1.5, 2.0, (z0 + z1) / 2 + .5), {
        'up': ('stool_edge', edge_img), 'down': ('stool_edge', edge_img),
        'north': ('stool_edge', edge_img), 'south': ('stool_edge', edge_img),
    })

    # Contract collision columns matching the tub walls, floor (1), rim (7), bench (3.25) and stool (3.5).
    cells = {}
    for x in range(-24, 24):
        for z in range(-24, 24):
            xc, zc = x + .5, z + .5
            if x0 <= xc <= x1 and z0 <= zc <= z1:
                cells[(x, z)] = (0.0, sh)
                continue
            o = tub_offset(xc, zc)
            if o > 1.25:
                continue
            top_y = RIM_Y if o >= 0 else max(FLOOR_Y, wall_height(o))
            if zc < zb and o < wall_offset(yb):
                top_y = max(top_y, yb)
            cells[(x, z)] = (0.0, round(top_y * 4) / 4)
    return m, columns(cells), {}


def yukkuri_pool_juice():
    """Four opaque pixel-art orange-juice liquid surfaces (juice_u1..juice_u4, units 1..4) at y = 2, 3, 4, 5."""
    m = Model('yukkuri_pool_juice')
    juice = kit.ramp('JUICE')
    for k, y in enumerate(LEVELS, 1):
        o = wall_offset(y) - .04
        W = int(math.ceil(2 * (TUB_HX + o))) + 2
        H = int(math.ceil(2 * (TUB_HZ + o))) + 2
        img = canvas(W, H, juice[2])
        for v in range(H):
            for u in range(W):
                x, z = u + .5 - W / 2, v + .5 - H / 2
                dist = o - tub_offset(x, z)          # distance inward from the tub wall
                if dist < 1.2:
                    col = juice[3]                   # crisp 1-px bright citrus meniscus along the wall
                elif dist < 2.2 and z > 6:
                    col = juice[1]                   # subtle shade against the back wall
                elif (v % 5 == 1 and (u + v * 3) % 11 in (0, 1, 2)):
                    col = juice[3]                   # clean pixel-art horizontal surface ripple bars
                elif (-12 <= x <= -4 and 3 <= z <= 5) or (-10 <= x <= -6 and z == 6):
                    col = juice[4]                   # crisp pixel specular highlight patch
                elif (6 <= x <= 11 and -2 <= z <= -1):
                    col = juice[3]                   # secondary gentle highlight
                else:
                    col = juice[2]
                put(img, u, v, col)
        part = m.part(f'juice_u{k}', unit=k)
        m.polygon(part, tub_loop(o, y), (0.0, 1.0, 0.0), img,
                  lambda p, W=W, H=H: (p[0] + W / 2, p[2] + H / 2), f'juice_l{k}')
    return m, None, {}


# ---- exports ----------------------------------------------------------------------------------------------------------
PROPS = {'ceramic_tunnel': ceramic_tunnel, 'yukkuri_slide': yukkuri_slide, 'yukkuri_trampoline': yukkuri_trampoline,
         'yukkuri_pool': yukkuri_pool, 'yukkuri_pool_juice': yukkuri_pool_juice}
PLACED = ('ceramic_tunnel', 'yukkuri_slide', 'yukkuri_trampoline', 'yukkuri_pool')
# Cells PlayBlock spreads each facility over (x -1..1, y 0..1, z -depth..depth); the data outline uses the same grid.
DEPTHS = {'ceramic_tunnel': 1, 'yukkuri_slide': 2, 'yukkuri_trampoline': 1, 'yukkuri_pool': 1}
LABELS = {'ceramic_tunnel': ['陶管', 'Ceramic tunnel'], 'yukkuri_slide': ['油库里滑梯', 'Yukkuri slide'],
          'yukkuri_trampoline': ['油库里蹦床', 'Yukkuri trampoline'], 'yukkuri_pool': ['橙汁澡盆', 'Orange juice tub']}
INGREDIENTS = {'ceramic_tunnel': ['brick'] * 6, 'yukkuri_slide': ['iron_ingot'] * 4 + ['stick'] * 2,
               'yukkuri_trampoline': ['string'] * 4 + ['slime_ball', 'iron_ingot'], 'yukkuri_pool': ['brick'] * 4 + ['cyan_dye']}


def tub_data():
    """The tub numbers the soak gameplay reads, in model units (1/16 block) from the facility's root block floor."""
    seats = [{'id': 'adult_left', 'size': 'adult', 'x': -9.0, 'z': 3.0, 'y': FLOOR_Y},
             {'id': 'adult_right', 'size': 'adult', 'x': 9.0, 'z': 3.0, 'y': FLOOR_Y},
             {'id': 'adult_alone', 'size': 'adult', 'x': 0.0, 'z': 3.0, 'y': FLOOR_Y},
             {'id': 'small_bench_left', 'size': 'small', 'x': -7.0, 'z': -9.6, 'y': BENCH[1]},
             {'id': 'small_bench_middle', 'size': 'small', 'x': 0.0, 'z': -9.6, 'y': BENCH[1]},
             {'id': 'small_bench_right', 'size': 'small', 'x': 7.0, 'z': -9.6, 'y': BENCH[1]}]
    for seat in seats:
        seat['soak'] = [round(max(0.0, y - seat['y']), 4) for y in LEVELS]
    return {'fill_mesh': 'yukkuri_pool_juice', 'levels': list(LEVELS), 'floor_top': FLOOR_Y, 'bench_top': BENCH[1],
            'bench_edge_z': BENCH[0], 'rim_top': RIM_Y, 'stool_top': STOOL[2],
            'stool': [STOOL[0][0], STOOL[0][1], STOOL[1][0], STOOL[1][1]], 'seats': seats}


def build(name):
    """(mesh, bbmodel, png, collision, parts summary) of one prop."""
    model, collision, extra = PROPS[name]()
    mesh, bb, png = model.export(extra.get('origin'))
    summary = [{'name': p['name'], 'unit': p['unit'], 'triangles': len(p['tris'])} for p in model.parts]
    return mesh, bb, png, collision, summary


def outputs():
    previous = provenance(SNAPSHOT / 'manifest.json')
    result, index, recipe = {}, {}, {}

    def emit(path, data):
        result[path] = (json.dumps(data, ensure_ascii=False, separators=(',', ':')) + '\n').encode()

    def emit_mesh(path, data):
        result[path] = preserve_negative_zero_mesh_json(path, (json.dumps(data, ensure_ascii=False, separators=(',', ':')) + '\n').encode())
    for name in PROPS:
        mesh, bb, png, collision, summary = build(name)
        recipe[name] = {'parts': summary} | ({'collision': collision} if collision is not None else {})
        texture = preserve_equivalent_png(ASSETS / f'textures/props/{name}.png', png)
        for folder in (HERE / 'assembled' / name, SNAPSHOT / 'selected' / name):
            emit(folder / f'{name}.bbmodel', bb)
            emit_mesh(folder / f'{name}.mesh.json', mesh)
            result[folder / 'texture.png'] = texture
        emit_mesh(ASSETS / f'yukkuri_props/{name}.json', mesh)
        result[ASSETS / f'textures/props/{name}.png'] = texture
        index[name] = {'bounds': mesh['bounds'], 'triangles': len(mesh['vertices']) // 24}
        if name not in PLACED:
            continue
        emit(ASSETS / f'models/item/{name}.json', {'parent': 'minecraft:builtin/entity', 'gui_light': 'front', 'textures': {'particle': f'mcyukkuri:props/{name}'}, 'display': {'gui': {'rotation': [25, 225, 0], 'scale': [.85, .85, .85]}}})
        emit(ASSETS / f'blockstates/{name}.json', {'variants': {'': {'model': f'mcyukkuri:block/{name}'}}})
        emit(ASSETS / f'models/block/{name}.json', {'textures': {'particle': f'mcyukkuri:props/{name}'}, 'elements': []})
        emit(DATA / f'loot_table/blocks/{name}.json', {'type': 'minecraft:block', 'pools': []})
        emit(DATA / f'playgrounds/{name}.json', {'outline': facility_outline(collision, DEPTHS[name]), 'collision': collision} | ({'tub': tub_data()} if name == 'yukkuri_pool' else {}))
        emit(DATA / f'recipe/{name}.json', {'type': 'minecraft:crafting_shapeless', 'category': 'misc', 'ingredients': [{'item': 'minecraft:' + x} for x in INGREDIENTS[name]], 'result': {'id': 'mcyukkuri:' + name, 'count': 1}})
    emit(HERE / 'recipe.json', {'status': 'pending-review', 'units_per_block': 16, 'style': 'faceted pixel-art props', 'props': recipe})
    emit(ASSETS / 'yukkuri_props/playgrounds-index.json', index)
    for i, locale in enumerate(('zh_cn', 'en_us')):
        path = ASSETS / f'lang/{locale}.json'
        existing = json.loads(path.read_text(encoding='utf8'))
        strings = {'item.mcyukkuri.' + name: labels[i] for name, labels in LABELS.items()}
        strings.update({'block.mcyukkuri.' + name: labels[i] for name, labels in LABELS.items()})
        strings.update({'activity.mcyukkuri.' + key: values[i] for key, values in {'tunnel': ['穿过管道', 'Passing through a tunnel'], 'slide_climb': ['登上滑梯', 'Climbing the slide'],
            'slide_down': ['顺坡滑下', 'Sliding down'], 'trampoline': ['玩蹦床', 'Bouncing on a trampoline'], 'tub_climb': ['爬进澡盆', 'Climbing into the tub'], 'juice_soak': ['泡橙汁澡', 'Soaking in orange juice']}.items()})
        strings.update({'play.mcyukkuri.no_space': ['需要平坦、空旷的 %s×%s×%s 空间', 'Needs a clear, supported %s×%s×%s space'][i],
                        'play.mcyukkuri.pool': ['橙汁澡盆：%s／%s 份橙汁。右键用橙汁一瓶补一份；油库会自己爬进去泡澡。', 'Orange juice tub: %s/%s portions of juice. Use orange juice on it, one bottle per portion; yukkuri climb in to soak on their own.'][i],
                        'play.mcyukkuri.pool_full': ['澡盆已经满了', 'The tub is full'][i]})
        changed = any(existing.get(k) != v for k, v in strings.items())
        existing.update(strings)
        if changed:
            result[path] = (json.dumps(existing, ensure_ascii=False, indent=2) + '\n').encode()
    emit(SNAPSHOT / 'manifest.json', {'status': 'pending-review-game-adaptation', 'source': HERE.relative_to(ROOT).as_posix() + '/build.py',
        'inputs': fingerprints(ROOT, {p: p.read_bytes() for p in (Path(__file__).resolve(), KIT, kit.DRAW)}, previous.get('inputs', {})),
        'files': fingerprints(ROOT, {p: v for p, v in result.items() if p.parent.name != 'lang'}, previous.get('files', {}))})
    return result


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    changed = []
    for path, data in outputs().items():
        if not check_output(path, data):
            changed.append(path.relative_to(ROOT).as_posix())
            if not args.check:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(data)
    if args.check and changed:
        raise SystemExit('Stale exports: ' + ', '.join(changed))
    print(f'Playgrounds: {len(PROPS)} models; {len(changed)} files ' + ('differ' if args.check else 'written'))
