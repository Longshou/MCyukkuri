"""Supine restraint table (`mcyukkuri:restraint_frame`): a low iron-shod wooden table with two leather straps, as a native block.

Run from the repository root: <py> art/props/restraint-table/build.py [--check]
Writes the block textures, the two block models, the blockstate and the strap-fit table under src/main/resources and the export
fingerprints in this folder's manifest.json; --check compares instead and also fails on coincident overlapping faces. The item
model, sprite and recipe stay with the operation tools (standards/yukkuri-library/drafts/operation-tools/build.py); the item's
hand model is this block model.

Block pixels: 16 per block, ground y = 0, tabletop y = 5 (RestraintTableBlock.TOP). The occupant lies supine with its width
along x (west-east); the straps run across it from a fixed end clamped at the west edge to a buckle at the east edge, at z = 4
and z = 12. `restraint_frame` is the empty table, its straps lying open across the top; `restraint_frame_occupied` keeps the
clamped ends, tabs and buckles only, for the drawn closed straps (`closed_straps` below; see README for the render hook).

Colours are the operation-tool ramps (icons.py: WOOD, IRON, STRAP), the same as the item sprite.
"""
from pathlib import Path
from itertools import combinations
import gzip, hashlib, io, json, math, sys, zlib
from PIL import Image

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
RUN = ROOT / 'src/main/resources'
ASSETS = RUN / 'assets/mcyukkuri'
sys.path.insert(0, str(ROOT / 'standards/yukkuri-library/drafts/operation-tools'))
import icons as I   # noqa: E402  (shared operation-tool ramps)

hexrgb = lambda h: tuple(int(h[i:i + 2], 16) for i in (1, 3, 5)) + (255,)
WOOD, IRON, STRAP = [list(map(hexrgb, r)) for r in (I.WOOD, I.IRON, I.STRAP)]

TOP = 5.0                    # tabletop height (px)
STRAP_Z = (4.0, 12.0)        # strap centre lines (px)
STRAP_W = 2.0                # strap width at the anchors (px)
STRAP_T = 0.25               # leather thickness (px)
PLATE_TOP = 4.25             # the fixed end leaves the west side plate here, x = -STRAP_T
BUCKLE_TOP = 4.75            # top bar of the east buckle; its outer face is at x = 16.5
TONGUE_TIP = 13.0            # open strap's free end lying on the top, short of the east edge
# Closed-strap anchors in the strap plane (u = x - 8, v = y - TOP, px): the fixed end at the west plate, the buckle's top bar,
# and the two top edges the strap may wrap over.
WEST_ANCHOR, EAST_ANCHOR = (-8 - STRAP_T, PLATE_TOP - TOP), (8.45, BUCKLE_TOP - TOP)
EDGES = ((-8.03, .03), (8.03, .03))


def noise(x, y, salt=0):
    return zlib.crc32(f'{salt}:{x}:{y}'.encode()) / 0xffffffff


def paint(fn):
    im = Image.new('RGBA', (16, 16))
    for y in range(16):
        for x in range(16):
            im.putpixel((x, y), fn(x, y))
    return im


# ------------------------------------------------------------------------------------------------------------------ textures
def top(u, v):
    """Four 4-px boards running north-south; a dark seam on each board's east column, worn centre."""
    board, col = divmod(u, 4)
    if col == 3:
        return WOOD[0]
    r = noise(u, v, 'top')
    streak = noise(u, 0, 'grain')
    step = 2 if col == 0 else 1
    if streak > .7 and r > .35:
        step += 1
    if r > .94:
        step = max(0, step - 1)
    if 5 <= u <= 10 and 5 <= v <= 10 and r < .18:      # worn where bodies lie
        step = 2
    return WOOD[min(step, 3)]


def side(u, v):
    """Rows 0-1: board ends (north and south faces, a seam every 4 px); rows 2-3: the long board edge (east and west faces)."""
    if v in (0, 2):
        return WOOD[2] if noise(u, v, 'side') > .2 else WOOD[1]
    if v == 1:
        return WOOD[0] if u % 4 == 3 else WOOD[1]
    return WOOD[1] if noise(u, v, 'edge') > .25 else WOOD[0]


def leg(u, v):
    """Square posts: vertical grain, lit west column; the bottom rows are an iron shoe."""
    if v >= 14:
        return IRON[2] if v == 14 and u % 3 != 2 else IRON[1]
    if u % 3 == 2:
        return WOOD[0]
    return WOOD[1] if noise(u, v // 2, 'leg') > .3 or u % 3 == 1 else WOOD[2]


def iron(u, v):
    """Dull wrought iron: mid steps in 2x2 patches, a light rivet head every 4 px, a few dark pits."""
    if u % 4 == 1 and v % 4 == 1:
        return IRON[3]
    if u % 4 == 2 and v % 4 == 2:
        return IRON[0]
    r = noise(u, v, 'iron')
    if r < .06:
        return IRON[0]
    return IRON[2] if noise(u // 2, v // 2, 'patch') > .55 else IRON[1]


def strap(u, v):
    """Rows 0-1: the strap seen from above (2 px wide, lit north row); rows 2-3: the same with punched holes for the tongue.
    Columns 14-15: the strap running down a side face (lit west column). Elsewhere: edge colour."""
    if u >= 14:
        return STRAP[2] if u == 14 else STRAP[1]
    if v == 2 and u in (8, 10, 12):
        return STRAP[0]
    if v in (0, 2):
        return STRAP[3] if noise(u, v, 'strap') > .9 else STRAP[2]
    return STRAP[1]


TEXTURES = {'restraint_table_top': top, 'restraint_table_side': side, 'restraint_table_leg': leg,
            'restraint_table_iron': iron, 'restraint_table_strap': strap}


# ------------------------------------------------------------------------------------------------------------------ models
def face(texture, uv=None):
    f = {'texture': '#' + texture}
    if uv is not None:
        f['uv'] = [round(x, 4) for x in uv]
    return f


FACE_DIMS = {'north': (0, 1), 'south': (0, 1), 'east': (2, 1), 'west': (2, 1), 'up': (0, 2), 'down': (0, 2)}


def box(a, b, faces):
    """Faces given without a uv take the top-left corner of their texture at one texel per pixel (never outside 0..16)."""
    for name, f in faces.items():
        if 'uv' not in f:
            i, j = FACE_DIMS[name]
            f['uv'] = [0, 0, round(min(16, b[i] - a[i]), 4), round(min(16, b[j] - a[j]), 4)]
    return {'from': list(a), 'to': list(b), 'faces': faces}


def strap_top_uv(length, holes=False):
    row = 2 if holes else 0
    return [0, row, length, row + 2]


def anchors(z):
    """Hardware of the strap at centre line z: the fixed end's iron plate on the west side face, the buckle on the east side face."""
    plate = box((-.5, 3.25, z - 1.5), (0, PLATE_TOP, z + 1.5),
                {d: face('iron') for d in ('west', 'up', 'down', 'north', 'south')})
    bars = ('east', 'up', 'down', 'north', 'south')
    buckle = [
        box((16, BUCKLE_TOP - .5, z - 1.5), (16.5, BUCKLE_TOP, z + 1.5), {d: face('iron') for d in bars}),
        box((16, 3.25, z - 1.5), (16.5, 3.75, z + 1.5), {d: face('iron') for d in bars}),
        box((16, 3.75, z - 1.5), (16.5, BUCKLE_TOP - .5, z - 1), {d: face('iron') for d in ('east', 'north', 'south')}),
        box((16, 3.75, z + 1), (16.5, BUCKLE_TOP - .5, z + 1.5), {d: face('iron') for d in ('east', 'north', 'south')}),
        box((16.3, 3.6, z - .2), (16.6, 4.4, z + .2), {d: face('iron') for d in ('east', 'up', 'down', 'north', 'south')}),
    ]
    return [plate] + buckle


def open_strap(z):
    """The unbuckled strap: up the west side face from its plate, over the edge and across the top, its holed end short of the
    east edge."""
    z0, z1 = z - STRAP_W / 2, z + STRAP_W / 2
    length = TONGUE_TIP + STRAP_T
    end = [0, 4, .25, 6]            # strap cut edge (one texel)
    return [
        box((-STRAP_T, PLATE_TOP, z0), (0, TOP, z1), {'west': face('strap', [14, 16 - TOP, 16, 16 - PLATE_TOP]),
                                                      'north': face('strap', end), 'south': face('strap', end)}),
        box((-STRAP_T, TOP, z0), (TONGUE_TIP, TOP + STRAP_T, z1),
            {'up': face('strap', strap_top_uv(length, holes=True)), 'west': face('strap', end), 'east': face('strap', end),
             'north': face('strap', [0, 0, length, .25]), 'south': face('strap', [0, 1, length, 1.25])}),
    ]


def table():
    """Plank top (2 px) on four 3x3 iron-shod posts inside the collision footprint, iron caps over the four corners."""
    sides = {'north': face('side', [0, 0, 16, 2]), 'south': face('side', [0, 0, 16, 2]),
             'east': face('side', [0, 2, 16, 4]), 'west': face('side', [0, 2, 16, 4])}
    parts = [box((0, 3, 0), (16, TOP, 16), {'up': face('top'), 'down': face('top'), **sides})]
    for x in (2, 11):
        for z in (2, 11):
            parts.append(box((x, 0, z), (x + 3, 3, z + 3),
                             {d: face('leg', [0, 12, 3, 15]) for d in ('north', 'south', 'east', 'west')} |
                             {'down': face('leg', [0, 13, 3, 16])}))
    for x, z in ((0, 0), (0, 1), (1, 0), (1, 1)):
        x0, x1 = (-.25, 1.75) if x == 0 else (14.25, 16.25)
        z0, z1 = (-.25, 1.75) if z == 0 else (14.25, 16.25)
        parts.append(box((x0, 2.75, z0), (x1, TOP + .25, z1),
                         {d: face('iron') for d in ('up', 'down', 'north', 'south', 'east', 'west')}))
    return parts


def model(elements):
    return {'parent': 'minecraft:block/block',
            'textures': {'particle': 'mcyukkuri:block/restraint_table_top', 'top': 'mcyukkuri:block/restraint_table_top',
                         'side': 'mcyukkuri:block/restraint_table_side', 'leg': 'mcyukkuri:block/restraint_table_leg',
                         'iron': 'mcyukkuri:block/restraint_table_iron', 'strap': 'mcyukkuri:block/restraint_table_strap'},
            'elements': elements}


def models():
    hardware = table() + [part for z in STRAP_Z for part in anchors(z)]
    return {'restraint_frame': model(hardware + [part for z in STRAP_Z for part in open_strap(z)]),
            'restraint_frame_occupied': model(hardware)}


FACE_AXIS = {'west': (0, 0), 'east': (0, 1), 'down': (1, 0), 'up': (1, 1), 'north': (2, 0), 'south': (2, 1)}


def overlapping_faces(elements):
    """Pairs of emitted faces on one plane whose rectangles overlap with area: they z-fight (or hide each other exactly)."""
    faces = []
    for i, e in enumerate(elements):
        for name in e['faces']:
            axis, high = FACE_AXIS[name]
            plane = (e['to'] if high else e['from'])[axis]
            rect = [(e['from'][k], e['to'][k]) for k in range(3) if k != axis]
            faces.append((axis, plane, rect, f'#{i}/{name}'))
    clash = []
    for (axis, plane, rect, a), (other, plane2, rect2, b) in combinations(faces, 2):
        if axis == other and abs(plane - plane2) < 1e-6 and all(
                max(lo, lo2) < min(hi, hi2) - 1e-6 for (lo, hi), (lo2, hi2) in zip(rect, rect2)):
            clash.append(f'{a} {b}')
    return clash


# ------------------------------------------------------------------------------------------------------------------ strap fit
def hull(points):
    """Counter-clockwise convex hull of 2D points (monotone chain)."""
    pts = sorted(set((round(x, 5), round(y, 5)) for x, y in points))
    if len(pts) < 3:
        return pts
    cross = lambda o, a, b: (a[0] - o[0]) * (b[1] - o[1]) - (a[1] - o[1]) * (b[0] - o[0])
    lower, upper = [], []
    for p in pts:
        while len(lower) > 1 and cross(lower[-2], lower[-1], p) <= 0:
            lower.pop()
        lower.append(p)
    for p in reversed(pts):
        while len(upper) > 1 and cross(upper[-2], upper[-1], p) <= 0:
            upper.pop()
        upper.append(p)
    return lower[:-1] + upper[:-1]


def simplify(poly, angle=4.0, edge=.25):
    """Drop hull vertices that turn less than `angle` degrees or sit closer than `edge` units to the previous kept vertex."""
    kept = list(poly)
    changed = True
    while changed and len(kept) > 8:
        changed = False
        for i in range(len(kept)):
            a, b, c = kept[i - 1], kept[i], kept[(i + 1) % len(kept)]
            d1 = (b[0] - a[0], b[1] - a[1]); d2 = (c[0] - b[0], c[1] - b[1])
            n1, n2 = math.hypot(*d1), math.hypot(*d2)
            if n1 < edge or n2 < 1e-9 or math.degrees(math.acos(max(-1, min(1, (d1[0] * d2[0] + d1[1] * d2[1]) / (n1 * n2))))) < angle:
                del kept[i]
                changed = True
                break
    return kept


def section(triangles, y):
    """Points where the triangle edges cross the plane at model height y, as (x, z)."""
    out = []
    for tri in triangles:
        for i in range(3):
            a, b = tri[i], tri[(i + 1) % 3]
            if (a[1] - y) * (b[1] - y) > 0 or a[1] == b[1]:
                continue
            t = (y - a[1]) / (b[1] - a[1])
            out.append((a[0] + t * (b[0] - a[0]), a[2] + t * (b[2] - a[2])))
    return out


def runtime_models():
    """Runtime yukkuri models (the ones the renderer draws) with their accessory prefixes."""
    presentation = json.loads((ASSETS / 'yukkuri_presentation/living.json').read_text(encoding='utf-8'))['variants']
    presentation.update(json.loads((ASSETS / 'yukkuri_presentation/subspecies.json').read_text(encoding='utf-8')))
    for path in sorted((ASSETS / 'yukkuri').glob('*.json')):
        data = json.loads(path.read_text(encoding='utf-8'))
        if all(k in data for k in ('id', 'vertices', 'weights', 'bones', 'clips')):
            yield data, tuple(presentation[data['id']]['accessory_prefixes'])


def visible_triangles(data, prefixes, bare):
    v = data['vertices']
    tris = []
    for part in data['parts']:
        name = part['name']
        if part['anatomy'] or 'puff' in name or name.startswith('face_') or bare and name.startswith(prefixes):
            continue
        for first in range(part['start'], part['start'] + part['count'], 3):
            tris.append([v[(first + i) * 8:(first + i) * 8 + 3] for i in range(3)])
    return tris


def band_heights(data):
    """Two strap lines that keep the face clear: one over the bangs above the brows, one over the base below the mouth.
    Model units: width 0.21 of the body height; the lower line starts just above the base, the upper one just above the brows."""
    v = data['vertices']
    def top_of(pred):
        ys = [v[i * 8 + 1] for p in data['parts'] if pred(p['name']) for i in range(p['start'], p['start'] + p['count'])]
        return max(ys)
    body = top_of(lambda n: n.endswith('body_surface') or n.startswith('soft_head_slice_'))
    brow = top_of(lambda n: n.startswith('face_brow') or n.startswith('face_eye'))
    width = .21 * body
    return width, (.02 * body + width / 2, brow + .05 * body + width / 2)


def strap_fits():
    """Per runtime model (and `_bare` without its accessory): the two strap lines and the convex outline of the visible
    model across each strap's width, in model units (x, z) before the supine turn."""
    fits = {}
    for data, prefixes in runtime_models():
        width, heights = band_heights(data)
        for bare in (False, True):
            tris = visible_triangles(data, prefixes, bare)
            bands = []
            for y in heights:
                pts = [p for dy in (-.5, 0, .5) for p in section(tris, y + dy * width)]
                poly = simplify(hull(pts))
                bands.append({'y': round(y, 4), 'width': round(width, 4), 'hull': [[round(x, 3), round(z, 3)] for x, z in poly]})
            fits[data['id'] + ('_bare' if bare else '')] = bands
    return fits


def supine_frame(asset, data, prefixes, bare):
    """Model units -> table frame before scale: the renderer's FIXED transform (contact lift, table depth, +90 degrees about X).
    Returns (lift, centre): table-frame point = (x, lift - z, y - centre) in model units."""
    z = [data['vertices'][i * 8 + 2] for p in data['parts']
         if not p['anatomy'] and 'puff' not in p['name'] and not (bare and p['name'].startswith(prefixes))
         for i in range(p['start'], p['start'] + p['count'])]
    surface = json.loads(gzip.open(ASSETS / f'yukkuri_wounds/{asset}.json.gz').read())
    pts, n = surface['points'], surface['skinCount']
    ys = [pts[i * 5 + 1] for i in range(n)]
    return .096 + max(z), (min(ys) + max(ys)) / 2


def closed_straps(fit, lift, centre, scale, flip=1):
    """Reference geometry of the drawn closed straps (the render hook): block pixels, origin at the table's top centre, +y up.

    Each strap line's outline is moved onto the table like the body (`flip` = -1 for a body lying head to the other end) and
    inflated by a clearance. In the strap's vertical plane (u = x, v = y) it is joined to the fixed end at the west plate, the
    buckle's top bar and the two top edges; the convex hull's chain from the buckle over the top to the plate is the strap's inner
    surface. Points that are not on the body (anchors, edges) keep the anchor's z and the full 2 px width; on the body the strap
    has the line's z and its fitted width. Returns [(points, normals, z, width)] per strap, points (u, v)."""
    fixed = {WEST_ANCHOR, EAST_ANCHOR, *EDGES}
    straps = []
    lines = sorted(fit, key=lambda b: flip * (b['y'] - centre))
    for band, anchor_z in zip(lines, sorted(z - 8 for z in STRAP_Z)):
        body = [(flip * scale * x, scale * (lift - z)) for x, z in band['hull']]
        cx = sum(p[0] for p in body) / len(body); cy = sum(p[1] for p in body) / len(body)
        clearance = .12
        body = [(x + clearance * (x - cx) / max(1e-6, math.hypot(x - cx, y - cy)),
                 y + clearance * (y - cy) / max(1e-6, math.hypot(x - cx, y - cy))) for x, y in body]
        poly = hull(body + list(fixed))
        i_east, i_west = poly.index(EAST_ANCHOR), poly.index(WEST_ANCHOR)
        chain = [poly[(i_east + k) % len(poly)] for k in range((i_west - i_east) % len(poly) + 1)]   # buckle -> over the top -> plate
        normals = []
        for k, p in enumerate(chain):
            a, b = chain[max(0, k - 1)], chain[min(len(chain) - 1, k + 1)]
            dx, dy = b[0] - a[0], b[1] - a[1]
            length = math.hypot(dx, dy) or 1
            normals.append((dy / length, -dx / length))      # outward for a counter-clockwise hull
        z_body = flip * scale * (band['y'] - centre)
        width_body = max(.6, min(STRAP_W, band['width'] * scale))
        zs = [anchor_z if p in fixed else z_body for p in chain]
        widths = [STRAP_W if p in fixed else width_body for p in chain]
        straps.append((chain, normals, zs, widths))
    return straps


def strap_rows(straps, texture='mcyukkuri:textures/block/restraint_table_strap.png'):
    """Triangle rows (x, y, z, u, v, nx, ny, nz) of the closed straps for the offline renderer (block px, y from the ground)."""
    rows = []
    for chain, normals, zs, widths in straps:
        length = 0.0
        for k in range(len(chain) - 1):
            p0, p1 = chain[k], chain[k + 1]
            n0, n1 = normals[k], normals[k + 1]
            seg = math.hypot(p1[0] - p0[0], p1[1] - p0[1])
            u0, u1 = length / 16, (length + seg) / 16
            length += seg
            def at(p, n, z, w, side, outer):
                off = STRAP_T if outer else 0
                return [p[0] + n[0] * off, TOP + p[1] + n[1] * off, z + side * w / 2]
            corners = {}
            for outer in (0, 1):
                for side in (-1, 1):
                    corners[outer, side, 0] = at(p0, n0, zs[k], widths[k], side, outer)
                    corners[outer, side, 1] = at(p1, n1, zs[k + 1], widths[k + 1], side, outer)
            quads = [  # outer face, inner face, both edges
                ([corners[1, -1, 0], corners[1, 1, 0], corners[1, 1, 1], corners[1, -1, 1]], (0, 2 / 16)),
                ([corners[0, 1, 0], corners[0, -1, 0], corners[0, -1, 1], corners[0, 1, 1]], (1 / 16, 2 / 16)),
                ([corners[0, -1, 0], corners[1, -1, 0], corners[1, -1, 1], corners[0, -1, 1]], (1 / 16, 1.25 / 16)),
                ([corners[1, 1, 0], corners[0, 1, 0], corners[0, 1, 1], corners[1, 1, 1]], (1 / 16, 1.25 / 16)),
            ]
            for quad, (v0, v1) in quads:
                a, b, c = quad[0], quad[1], quad[2]
                e1 = [b[i] - a[i] for i in range(3)]; e2 = [c[i] - a[i] for i in range(3)]
                n = [e1[1] * e2[2] - e1[2] * e2[1], e1[2] * e2[0] - e1[0] * e2[2], e1[0] * e2[1] - e1[1] * e2[0]]
                ln = math.sqrt(sum(x * x for x in n)) or 1
                n = [x / ln for x in n]
                uv = [(u0, v0), (u0, v1), (u1, v1), (u1, v0)]
                for t in ((0, 1, 2), (0, 2, 3)):
                    for i in t:
                        rows.append([*quad[i], *uv[i], *n])
    return rows, texture


# ------------------------------------------------------------------------------------------------------------------ exports
def png(im):
    buf = io.BytesIO(); im.save(buf, format='PNG')
    return buf.getvalue()


def outputs(fits):
    js = lambda d: (json.dumps(d, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    files = {}
    for name, painter in TEXTURES.items():
        files[ASSETS / f'textures/block/{name}.png'] = png(paint(painter))
    for name, data in models().items():
        files[ASSETS / f'models/block/{name}.json'] = js(data)
    files[ASSETS / 'blockstates/restraint_frame.json'] = js({'variants': {'': {'model': 'mcyukkuri:block/restraint_frame'}}})
    fit = {'units': 'model units (16 per block) before the supine turn: y is the strap line height, hull is (x, z) counter-clockwise',
           'strap': {'width_px': STRAP_W, 'thickness_px': STRAP_T, 'clearance_px': .12, 'min_width_px': .6},
           'anchors_px': {'note': 'strap plane (u = x - 8, v = y - 5); the hull also takes both top edges',
                          'west': list(WEST_ANCHOR), 'east': list(EAST_ANCHOR), 'edges': [list(e) for e in EDGES], 'z': list(STRAP_Z)},
           'models': fits}
    files[ASSETS / 'restraint_table/strap_fits.json'] = (json.dumps(fit, ensure_ascii=False, separators=(',', ':')) + '\n').encode()
    return files


SCALES = (.1, .2, .4, .8, 1.0, 1.5)    # baby to an adult deibu (growth x 1.5)


def strap_problems(fits):
    """Closed straps for every model, with and without its accessory, at every scale and both lying directions: the anchors
    must be on the hull, no strap point may sit inside the tabletop, and no strap segment may lie in a table face plane."""
    problems, count = [], 0
    for data, prefixes in runtime_models():
        for bare in (False, True):
            asset = data['id'] + ('_bare' if bare else '')
            lift, centre = supine_frame(data['id'], data, prefixes, bare)
            for scale in SCALES:
                for flip in (1, -1):
                    try:
                        straps = closed_straps(fits[asset], lift, centre, scale, flip)
                    except ValueError as error:
                        problems.append(f'{asset} x{scale} flip{flip}: {error}')
                        continue
                    count += 1
                    for chain, *_ in straps:
                        if any(abs(u) < 8 and v < 0 for u, v in chain):
                            problems.append(f'{asset} x{scale} flip{flip}: strap inside the tabletop')
                        for (u0, v0), (u1, v1) in zip(chain, chain[1:]):
                            if abs(v0) < 1e-6 and abs(v1) < 1e-6 or abs(abs(u0) - 8) < 1e-6 and abs(abs(u1) - 8) < 1e-6:
                                problems.append(f'{asset} x{scale} flip{flip}: strap segment on a table face')
    return problems, count


def main():
    check = '--check' in sys.argv
    clash = {name: overlapping_faces(m['elements']) for name, m in models().items()}
    for name, pairs in clash.items():
        for pair in pairs:
            print('COPLANAR', name, pair)
    print('Coplanar overlapping faces:', {name: len(pairs) for name, pairs in clash.items()})
    fits = strap_fits()
    problems, count = strap_problems(fits)
    for problem in problems:
        print('STRAP', problem)
    print(f'Closed straps: {count} fits checked, {len(problems)} problems')
    files = outputs(fits)
    stale = [p for p, data in files.items() if not p.exists() or p.read_bytes() != data]
    failed = any(clash.values()) or problems
    if check:
        for p in stale:
            print('STALE', p.relative_to(ROOT))
        return 1 if stale or failed else 0
    if failed:
        return 1
    for p in stale:
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(files[p])
    manifest = {'authoring': 'art/props/restraint-table/build.py', 'status': 'pending_user_confirmation',
                'exports': {p.relative_to(ROOT).as_posix(): hashlib.sha256(d).hexdigest() for p, d in sorted(files.items())}}
    (HERE / 'manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8', newline='\n')
    print('Wrote', len(stale), 'of', len(files), 'files')
    return 0


if __name__ == '__main__':
    sys.exit(main())
