"""Preview of the second aquatic hat form beside the current one, and who sits where inside it (offline CPU renders, not
the game).

Rows: the current aquatic hat (the land hat turned over, as the game draws it) and this form, worn and set down on water;
cuts through both empty hulls, the new hollow from above and the owner aboard; then layouts solved by layout.py
(body-outline room, the proposed rule) as a top view with each body's outline and a cut from the side, by day and by night
(the owner leaning over the young), with stores.

    MCY_LOCAL=<main checkout>/.local python layout.py    # first: previews/aquatic-hat-round/layouts.json
    MCY_LOCAL=<main checkout>/.local python preview.py   # sheet.png and index.html (with 'page': index.html only)
"""
from pathlib import Path
import json, math, os, sys
import numpy as np
from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import scene as S
import layout as L
import export as E
P = S.P
OUT = L.OUT

BG = (236, 245, 246)
CUT = (168, 132, 190)               # cut cloth
FOOD_CUT = (150, 112, 62)
RW, RH = 920, 640                   # render size (downsampled to the cell)
CELL = (440, 306)


# ------------------------------------------------------------------ atlas and rasterizer (runtime/render.js orthographic)
class Atlas:
    def __init__(self):
        self.image = Image.new('RGBA', (1024, 1024)); self.slots = {}

    def uv(self, key, image, uv):
        if key not in self.slots:
            i = len(self.slots); self.slots[key] = i
            self.image.paste(image.convert('RGBA').resize((256, 256), Image.Resampling.NEAREST), ((i % 4)*256, (i//4)*256))
        i = self.slots[key]
        return uv*.25+np.array([(i % 4)*.25, (i//4)*.25])


def rasterize(tris, flags, texture, az, el, span, center):
    """flags: 0 cull back faces, 1 draw back faces flat in CUT, 2 the same in FOOD_CUT."""
    s, c, se, ce = math.sin(az), math.cos(az), math.sin(el), math.cos(el)
    right = np.array([c, 0, s]); up = np.array([-s*se, ce, c*se]); eye = np.array([s*ce, se, -c*ce]); scale = RH/(2*span)
    center = np.asarray(center, float); pos = tris[:, :, :3]-center
    pts = np.stack((RW/2+pos@right*scale, RH/2-pos@up*scale, pos@eye), axis=2)
    front = tris[:, 0, 5:8]@eye > 0
    keep = front | (flags > 0)
    pts, uv, nrm, flags, front = pts[keep], tris[keep, :, 3:5], tris[keep, 0, 5:8], flags[keep], front[keep]
    a, b, cc = pts[:, 0], pts[:, 1], pts[:, 2]
    det = (b[:, 1]-cc[:, 1])*(a[:, 0]-cc[:, 0])+(cc[:, 0]-b[:, 0])*(a[:, 1]-cc[:, 1])
    ok = np.abs(det) > 1e-8
    pts, uv, nrm, flags, front, det = pts[ok], uv[ok], nrm[ok], flags[ok], front[ok], det[ok]
    lo = np.maximum(0, np.floor(pts[:, :, :2].min(axis=1)).astype(int)); hi = np.minimum([RW-1, RH-1], np.ceil(pts[:, :, :2].max(axis=1)).astype(int))
    dims = np.maximum(0, hi-lo+1); areas = dims[:, 0]*dims[:, 1]
    depth = np.full(RW*RH, -np.inf); pixels = np.tile(np.array(BG, np.uint8), (RW*RH, 1))
    shade = .74+.26*np.maximum(0, nrm@np.array([-.35, .8, -.48]))
    flat = np.array([[0, 0, 0], CUT, FOOD_CUT], float)
    for first in range(0, len(pts), 1024):
        end = min(first+1024, len(pts)); counts = areas[first:end]; total = counts.sum()
        if not total: continue
        ids = np.repeat(np.arange(first, end), counts); local = np.arange(total)-np.repeat(np.r_[0, np.cumsum(counts)[:-1]], counts)
        widths = dims[ids, 0]; x = lo[ids, 0]+local % widths; y = lo[ids, 1]+local//widths
        A, Bq, C = pts[ids, 0], pts[ids, 1], pts[ids, 2]
        wa = ((Bq[:, 1]-C[:, 1])*(x+.5-C[:, 0])+(C[:, 0]-Bq[:, 0])*(y+.5-C[:, 1]))/det[ids]
        wb = ((C[:, 1]-A[:, 1])*(x+.5-C[:, 0])+(A[:, 0]-C[:, 0])*(y+.5-C[:, 1]))/det[ids]; wc = 1-wa-wb
        inside = (wa >= -1e-7) & (wb >= -1e-7) & (wc >= -1e-7)
        ids, x, y = ids[inside], x[inside], y[inside]; w = np.stack((wa[inside], wb[inside], wc[inside]), axis=1)
        tex = np.clip(np.floor((uv[ids]*w[:, :, None]).sum(axis=1)*1024).astype(int), 0, 1023)
        color = texture[tex[:, 1], tex[:, 0]]; opaque = color[:, 3] >= 128
        ids, x, y, w, color = ids[opaque], x[opaque], y[opaque], w[opaque], color[opaque, :3].astype(float)
        z = (pts[ids, :, 2]*w).sum(axis=1); addr = y*RW+x; cand = z > depth[addr]+1e-7
        ids, w, color, z, addr = ids[cand], w[cand], color[cand], z[cand], addr[cand]
        np.maximum.at(depth, addr, z); near = z >= depth[addr]-1e-8
        ids, color, addr = ids[near], color[near], addr[near]
        lit = color*shade[ids][:, None]
        back = ~front[ids]
        lit[back] = flat[flags[ids][back]]
        pixels[addr] = lit.astype(np.uint8)
    return pixels.reshape(RH, RW, 3), depth.reshape(RH, RW), (right, up, eye, scale, center)


def water(pixels, depth, camera, dry, cut=False):
    """The water plane y = 0 over everything below it, except inside the hull's waterline (dry hollow). In a cut view
    what lies inside the waterline (the cut cloth, the hollow, riders) is only lightly tinted below the water line."""
    right, up, eye, scale, center = camera
    yy, xx = np.mgrid[:RH, :RW]; sx = (xx+.5-RW/2)/scale+center@right; sy = (RH/2-yy-.5)/scale+center@up
    inv = np.linalg.inv(np.array([[right[0], right[2]], [up[0], up[2]]]))
    # the point on the plane under each pixel: solve right.p = sx, up.p = sy with p.y = 0
    x = inv[0, 0]*sx+inv[0, 1]*sy; z = inv[1, 0]*sx+inv[1, 1]*sy
    wd = x*eye[0]+z*eye[2]-center@eye
    wet = wd > depth+1e-7
    if dry is not None: wet &= ~dry(x, z)
    if cut and dry is not None:
        p = (center+np.einsum('hw,k->hwk', (xx+.5-RW/2)/scale, right)+np.einsum('hw,k->hwk', (RH/2-yy-.5)/scale, up)
             + np.einsum('hw,k->hwk', np.where(np.isfinite(depth), depth, 0), eye))
        inside = np.isfinite(depth) & dry(p[..., 0], p[..., 2])
        under = inside & (p[..., 1] < 0)
        wet &= ~inside
        out = pixels.astype(float); out[under] = out[under]*.8+np.array([123, 187, 203])*.2
        pixels = out.astype(np.uint8)
    ripple = (np.sin(x*.22+z*.17) > .975) & (np.cos(z*.31-x*.04) > .15)
    col = np.zeros_like(pixels, float)+[123, 187, 203]; col[ripple] = [171, 218, 224]
    alpha = np.where(np.isfinite(depth), .5, 1.)
    out = pixels.astype(float); out[wet] = out[wet]*(1-alpha[wet, None])+col[wet]*alpha[wet, None]
    return out.astype(np.uint8)


def clip(tris, axis):
    """Cut a mesh through the hat's axis, keeping the half away from the camera: 'x' seen from +x, 'z' seen from -z."""
    x = tris[:, :, 0] if axis == 'x' else -tris[:, :, 2]; keep_below = 0.; inside = x <= keep_below+1e-9
    allin = inside.all(1); out = [tris[allin]]
    for t, m in zip(tris[~allin & inside.any(1)], inside[~allin & inside.any(1)]):
        poly = []
        for i in range(3):
            p, q = t[i], t[(i+1) % 3]; pin, qin = m[i], m[(i+1) % 3]
            if pin: poly.append(p)
            if pin != qin:
                a, b = (p[0], q[0]) if axis == 'x' else (-p[2], -q[2])
                f = (keep_below-a)/(b-a); poly.append(p+(q-p)*f)
        for i in range(1, len(poly)-1): out.append(np.array([poly[0], poly[i], poly[i+1]])[None])
    return np.concatenate(out) if out else tris[:0]




# ------------------------------------------------------------------ scenes
HULL = L.HULL
FOOD_IMG = Image.open(HERE/'food_layer.png').convert('RGBA')
MARISA = S.BODY


def body_tris(atlas, scale, day, belly, young, yaw, x, z, y):
    clip, t, face = (('boat_drift', 1.2, 'closed_smile') if day else ('boat_nest_curl', .5, 'sleep')) if young else \
        (('boat_drift', .3, 'closed_smile') if day else ('boat_cover_young', .9, 'sleep'))
    sh = L.shape(scale, day, belly, young, yaw)
    m = sh.mesh(x, z, y)
    m[:, :, 3:5] = atlas.uv(f'marisa/{face}', Image.fromarray(MARISA.texture(face)), m[:, :, 3:5])
    return m


def hull_tris(atlas, cut=None):
    m = HULL.placed.copy()
    m[:, :, 3:5] = atlas.uv('marisa/neutral', Image.fromarray(MARISA.texture('neutral')), m[:, :, 3:5])
    return clip(m, cut) if cut else m


def food_tris(atlas, level, cut=None):
    """The stores as one level layer: a textured top over every hollow cell below the level, and its cut face."""
    if level is None: return None
    G = S.GRID; sel = HULL.cavity & (HULL.field < level)
    if cut == 'x': sel &= G.x <= 0
    ii, jj = np.nonzero(sel); s = G.step; tile = 8.
    x0 = G.x[ii, jj]-s/2; z0 = G.z[ii, jj]-s/2; u0 = np.mod(x0, tile)/tile; v0 = np.mod(z0, tile)/tile; du = s/tile
    quads = np.zeros((len(ii), 4, 8))
    for k, (a, b) in enumerate(((0, 0), (1, 0), (1, 1), (0, 1))):
        quads[:, k, 0] = x0+a*s; quads[:, k, 1] = level; quads[:, k, 2] = z0+b*s
        quads[:, k, 3] = u0+a*du; quads[:, k, 4] = v0+b*du; quads[:, k, 6] = 1
    tris = np.concatenate([quads[:, [0, 3, 2]], quads[:, [0, 2, 1]]])
    if cut == 'x':
        faces = []
        for j in np.nonzero(sel[G.n//2-1])[0]:
            z = G.z[G.n//2-1, j]; h = HULL.field[G.n//2-1, j]
            q = np.array([[0, h, z-s/2], [0, h, z+s/2], [0, level, z+s/2], [0, level, z-s/2]])
            uv = np.array([[0, .9], [.06, .9], [.06, .9-(level-h)/tile], [0, .9-(level-h)/tile]])
            for a, b, c in ((0, 2, 1), (0, 3, 2)): faces.append(np.array([[*q[m], *uv[m], 1, 0, 0] for m in (a, b, c)]))
        if faces: tris = np.concatenate([tris, np.array(faces)])
    tris[:, :, 3:5] = atlas.uv('food', FOOD_IMG, tris[:, :, 3:5])
    return tris


def dry(x, z, line, hull=None):
    hull = hull or HULL
    i = np.floor((x+S.GRID.half)/S.GRID.step).astype(int); j = np.floor((z+S.GRID.half)/S.GRID.step).astype(int)
    ok = (i >= 0) & (i < S.GRID.n) & (j >= 0) & (j < S.GRID.n); out = np.zeros(x.shape, bool)
    ii, jj = np.clip(i, 0, S.GRID.n-1), np.clip(j, 0, S.GRID.n-1)
    out[ok] = (hull.low[ii, jj] < line)[ok]
    return out


def render(meshes, flags, atlas, view, water_at=None, cut=False, night=False, hull=None):
    """One cell; water_at is the waterline (y, world units) the hull (default this form's) floats at, or None."""
    az, el, span, center = view
    tris = np.concatenate(meshes); fl = np.concatenate(flags)
    tris = tris.copy(); fl = fl.copy()
    c = np.array(center, float)
    if water_at is not None: tris[:, :, 1] -= water_at; c = c-[0, water_at, 0]  # the water plane is y 0
    px, depth, cam = rasterize(tris, fl, np.array(atlas.image), az, el, span, c)
    if water_at is not None: px = water(px, depth, cam, lambda x, z: dry(x, z, water_at, hull), cut=cut)
    img = Image.fromarray(px)
    if night:
        img = Image.fromarray(np.clip(np.array(img).astype(float)*[.55, .6, .84]+[6, 8, 22], 0, 255).astype(np.uint8))
    return img, cam


def project(cam, p):
    right, up, eye, scale, c = cam
    return RW/2+(np.asarray(p)-c)@right*scale, RH/2-(np.asarray(p)-c)@up*scale


def cellify(img, sub=None, title=None):
    out = img.resize(CELL, Image.Resampling.LANCZOS); d = ImageDraw.Draw(out); f = P.preview_font(13)
    if title:
        box = d.textbbox((6, 4), title, font=f); d.rectangle((box[0]-3, box[1]-2, box[2]+3, box[3]+2), fill=(255, 255, 255))
        d.text((6, 4), title, font=f, fill='#203e48')
    if sub:
        d.rectangle((0, CELL[1]-20, CELL[0], CELL[1]), fill=(255, 255, 255)); d.text((5, CELL[1]-18), sub, font=f, fill='#36565f')
    return out


# water: where each empty hull floats (its own weight only), for the look rows
WATER = S.hydro(HULL, [])['waterline']
OLD_TRIS, _, _, (OX0, OX1, OZ0, OZ1) = E.old_hull()
OLD_PROFILE = json.loads((E.PROFILES/f'{E.OLD_HULL_ID}.json').read_text(encoding='utf-8'))
OLD = S.Hull(OLD_TRIS, OLD_PROFILE['rim']['y']*16, lambda x, z: (OX0 < x) & (x < OX1) & (OZ0 < z) & (z < OZ1))
OLD_WATER = S.hydro(OLD, [])['waterline']
OLD_WORN = np.array(json.loads((S.ASSETS/'yukkuri_props/accessory_marisa.json').read_text(encoding='utf-8'))['vertices'],
                    float).reshape(-1, 3, 8)


def old_tris(atlas, cut=None):
    m = OLD_TRIS.copy()
    m[:, :, 3:5] = atlas.uv('marisa/neutral', Image.fromarray(MARISA.texture('neutral')), m[:, :, 3:5])
    return clip(m, cut) if cut else m


def waterline(img, cam):
    y0 = project(cam, (0, 0, -20))[1]; ImageDraw.Draw(img).line([(0, y0), (RW, y0)], fill=(30, 90, 210), width=4)


def compare_cells():
    """Both forms side by side (current first): worn, afloat, cut empty; the new hollow from above and its owner aboard."""
    g = json.loads((HERE/'geometry.json').read_text(encoding='utf-8'))
    cells = []
    for hat, title in ((OLD_WORN, '现行水生帽 · 戴着 3/4'), (S.WORN, '新形态 · 戴着 3/4')):
        atlas = Atlas(); tris, _ = MARISA.mesh('idle', 0, atlas, 'neutral', hat=hat)
        img, _ = render([tris], [np.zeros(len(tris), int)], atlas, (.62, .32, 15.5, (0, 13., 0)))
        cells.append(cellify(img, title=title))
    for make, hull, line, title, sub in (
            (old_tris, OLD, OLD_WATER, '现行帽船 · 水上 3/4（空帽）', '陆生帽翻过来压扁：平帽檐，方形阶梯帽腔'),
            (hull_tris, HULL, WATER, '新帽船 · 水上 3/4（空帽）', '外：圆台阶帽冠，下层压成水下龙骨；内：圆盆')):
        atlas = Atlas(); h = make(atlas)
        img, _ = render([h], [np.zeros(len(h), int)], atlas, (.6, .5, 14.5, (0, -.5, 0)), water_at=line, hull=hull)
        cells.append(cellify(img, sub, title))
    for make, hull, line, title, prof in ((old_tris, OLD, OLD_WATER, '现行帽船 · 剖面（空帽）', OLD_PROFILE),
                                          (hull_tris, HULL, WATER, '新帽船 · 剖面（空帽）', None)):
        atlas = Atlas(); h = make(atlas, 'x')
        img, cam = render([h], [np.ones(len(h), int)], atlas, (math.pi/2, .02, 10.5, (0, -1.5, 0)), water_at=line, cut=True, hull=hull)
        waterline(img, cam)
        if prof: sub = f"帽腔底在帽檐面下 {-prof['floor']['y']*16:.1f}，龙骨底 {-prof['keel']['y']*16:.1f}（蓝线为水线）"
        else: sub = f"平底在帽檐面下 {g['floor_depth']:.1f}，龙骨底 {g['keel_depth']:.1f}（蓝线为水线）"
        cells.append(cellify(img, sub, title))
    atlas = Atlas(); h = hull_tris(atlas)
    img, cam = render([h], [np.zeros(len(h), int)], atlas, (math.pi, math.pi/2-.001, 13., (0, 0, 0)))
    d = ImageDraw.Draw(img)
    for r, col in ((g['opening_radius'], (40, 120, 200)), (g['floor_radius'], (230, 140, 40))):
        d.line([project(cam, (r*math.cos(a), 0, r*math.sin(a))) for a in np.linspace(0, 2*math.pi, 73)], fill=col, width=3)
    cells.append(cellify(img, f"帽口半径 {g['opening_radius']:.1f}（蓝）· 平底半径 {g['floor_radius']:.1f}（橙）· 深 {g['floor_depth']:.1f}",
                         '新帽船 · 俯视帽腔（船首↑）'))
    # the crew aboard, seen into the open hollow from the bow quarter: owner, six newborns before her, twelve portions
    r = L.solve(next(c for c in L.COMBOS if c[0] == 'owner_6n'), 'day', 12, 'body')
    atlas = Atlas(); hm = hull_tris(atlas); bodies = [body_tris(atlas, *d[:8]) for d in r['_draw']]
    food = food_tris(atlas, HULL.stores(12))
    meshes = [hm, food]+bodies
    img, _ = render(meshes, [np.zeros(len(m), int) for m in meshes], atlas, (.55, .8, 13.5, (0, 0., -1.)),
                    water_at=r['hydro']['waterline'])
    cells.append(cellify(img, f"帽主身底 {r['owner']['bottom']:.1f} · 粮层面 {r['level']:.1f} · 帽檐面高出水面 {-r['hydro']['waterline']:.1f}",
                         '新帽船 · 3/4（帽主＋6 新生＋储粮 12）'))
    return cells


def hull_outline(points):
    """Convex outline (monotone chain) of projected points."""
    pts = sorted(set((round(x, 1), round(y, 1)) for x, y in points))
    if len(pts) < 3: return pts
    def cross(o, a, b): return (a[0]-o[0])*(b[1]-o[1])-(a[1]-o[1])*(b[0]-o[0])
    lower, upper = [], []
    for p in pts:
        while len(lower) >= 2 and cross(lower[-2], lower[-1], p) <= 0: lower.pop()
        lower.append(p)
    for p in reversed(pts):
        while len(upper) >= 2 and cross(upper[-2], upper[-1], p) <= 0: upper.pop()
        upper.append(p)
    return lower[:-1]+upper[:-1]


def footprint_outline(cam, d):
    """A body's own footprint (its body cloth's underside cells) as a projected outline."""
    scale, day, belly, young, yaw, x, z, y = d[:8]
    sh = L.shape(scale, day, belly, young, yaw)
    si, sj, _ = sh.window(x, z)
    cells = np.isfinite(sh.low)
    xs = S.GRID.x[si, sj][cells]; zs = S.GRID.z[si, sj][cells]; ys = (sh.low+y)[cells]
    return hull_outline([project(cam, (a, b, c)) for a, b, c in zip(xs, ys, zs)])


def young_highlight(img, cam_view, meshes, flags, atlas, young_meshes, depth_ref):
    """Outline the young's visible pixels (against depth_ref, the render they are cut from) in light yellow."""
    if not young_meshes: return img
    az, el, span, center = cam_view
    yt = np.concatenate(young_meshes)
    _, yd, _ = rasterize(yt, np.zeros(len(yt), int), np.array(atlas.image), az, el, span, np.array(center, float))
    own = np.isfinite(yd); vis = own & (np.abs(np.where(own, yd, 0)-np.where(own, depth_ref, 0)) < 1e-3)
    grow = vis.copy()
    for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1), (2, 0), (-2, 0), (0, 2), (0, -2)):
        grow |= np.roll(np.roll(vis, dy, 0), dx, 1)
    a = np.array(img); a[grow & ~vis] = (255, 236, 120)
    return Image.fromarray(a)


def tint_night(img):
    return Image.fromarray(np.clip(np.array(img).astype(float)*[.62, .64, .74]+[4, 6, 14], 0, 255).astype(np.uint8))


def layout_cells(combo_key, period, stores, rule='body'):
    combo = next(c for c in L.COMBOS if c[0] == combo_key)
    r = L.solve(combo, period, stores, rule)
    night = period == 'night'; level = HULL.stores(stores)
    floor_depth = json.loads((HERE/'geometry.json').read_text(encoding='utf-8'))['floor_depth']
    out = []
    for kind in ('top', 'cut'):
        atlas = Atlas(); cut = 'x' if kind == 'cut' else None
        hull = hull_tris(atlas, cut)
        meshes = [hull]; flags = [np.full(len(hull), 1 if cut else 0)]
        owner = body_tris(atlas, *r['_draw'][0][:8])
        young = [body_tris(atlas, *d[:8]) for d in r['_draw'][1:]]
        f = food_tris(atlas, level, cut)
        rest = ([f] if f is not None else [])
        rest_flags = ([np.full(len(f), 2 if cut else 0)] if f is not None else [])
        water_at = r['hydro']['waterline'] if kind == 'cut' else None
        view = (math.pi, math.pi/2-.001, 12.5, (0, 0, 0)) if kind == 'top' else (math.pi/2, .06, 10.5, (0, 1.5, 0))
        both, cam = render(meshes+[owner]+young+rest, flags+[np.zeros(len(owner), int)]+[np.zeros(len(m), int) for m in young]+rest_flags,
                           atlas, view, water_at=water_at, cut=bool(cut))
        alone, cam = render(meshes+young+rest, flags+[np.zeros(len(m), int) for m in young]+rest_flags, atlas, view, water_at=water_at, cut=bool(cut))
        # the owner half transparent over what she covers, so the young she sits by or leans over show
        img = Image.fromarray((np.array(both).astype(float)*.45+np.array(alone).astype(float)*.55).astype(np.uint8))
        c = np.array(view[3], float)-([0, water_at, 0] if water_at is not None else 0)
        shifted = [m.copy() for m in young]
        for m in shifted:
            if water_at is not None: m[:, :, 1] -= water_at
        shifted_owner = owner.copy()
        if water_at is not None: shifted_owner[:, :, 1] -= water_at
        all_tris = np.concatenate([m.copy() for m in meshes+young+rest]);
        if water_at is not None: all_tris[:, :, 1] -= water_at
        _, depth_ref, _ = rasterize(all_tris, np.concatenate(flags+[np.zeros(len(m), int) for m in young]+rest_flags),
                                    np.array(atlas.image), view[0], view[1], view[2], c)
        img = young_highlight(img, (view[0], view[1], view[2], c), None, None, atlas, shifted, depth_ref)
        dr = ImageDraw.Draw(img); f13 = P.preview_font(24)
        if kind == 'top':
            for i, d in enumerate(r['_draw']):
                outline = footprint_outline(cam, d)
                if len(outline) >= 3: dr.line(outline+[outline[0]], fill=(255, 140, 60) if i == 0 else (90, 220, 255), width=3)
            boxes = []
            for p, d in zip(r['placed'], r['_draw'][1:]):
                if not p.get('placed'): continue
                px, py = project(cam, (d[5], 0, d[6]))
                text = f"{p['bottom']:.1f}"
                for _ in range(6):  # step a label down past the ones already placed
                    box = dr.textbbox((px, py), text, font=f13, anchor='mm')
                    if not any(box[0] < b[2]+14 and box[2] > b[0]-14 and box[1] < b[3]+6 and box[3] > b[1]-6 for b in boxes): break
                    py += box[3]-box[1]+6
                boxes.append(box)
                dr.rectangle((box[0]-3, box[1]-2, box[2]+3, box[3]+2), fill=(255, 255, 255)); dr.text((px, py), text, font=f13, fill='#203e48', anchor='mm')
            sub = f"帽主身底 {r['owner']['bottom']:.1f}" + (f" · 粮层面 {r['level']:.1f}" if r['level'] is not None else '') + \
                  f" · 横倾 {r['hydro']['heel']:.1f}°/进水 {r['flood']:.0f}°"
        else:
            # the waterline across the cut
            y0 = project(cam, (0, 0, -20))[1]
            dr.line([(0, y0), (RW, y0)], fill=(30, 90, 210), width=4)
            placed = r['placed']
            braid = '辫托够' if all(p.get('braid') in (True, None) for p in placed if p.get('placed')) else '辫托不够：'+','.join(p['kind'] for p in placed if p.get('braid') is False)
            def depth(p):
                if not p.get('placed'): return '放不下'
                ground = r['level'] if r['level'] is not None else floor_depth  # the layer or the floor under it
                return f"{p['bottom']:.1f}" + ('*' if p['bottom'] < ground-.5 else '')
            sub = ('幼体身底 '+'/'.join(depth(p) for p in placed)+'（*靠弧壁）· '+braid) if placed else f"高座：身底在帽檐面下 {r['owner']['bottom']:.1f}"
        if night: img = tint_night(img)
        cell = cellify(img, sub, f"{combo[1]} · {'白天' if not night else '夜里（俯身）'} · 储粮 {stores} · {'俯视' if kind == 'top' else '剖面'}")
        small = [m for m, d in zip(shifted, r['_draw'][1:]) if d[0] < L.SMALL]
        if kind == 'cut' and small:  # newborns and babies are small: an inset fitted to them, from the owner-less render
            pts = np.array([project(cam, v) for m in small for v in m[:, :, :3].reshape(-1, 3)[::7]])
            (x0, y0), (x1, y1) = pts.min(0)-12, pts.max(0)+12
            w, h = 170, 116
            span_w = min(max(x1-x0, (y1-y0)*w/h), RW); span_h = span_w*h/w
            cx = min(max((x0+x1)/2, span_w/2), RW-span_w/2); cy = min(max((y0+y1)/2, span_h/2), RH-span_h/2)  # kept inside the render
            box = (int(cx-span_w/2), int(cy-span_h/2), int(cx+span_w/2), int(cy+span_h/2))
            src = young_highlight(alone, (view[0], view[1], view[2], c), None, None, atlas, shifted, depth_ref)
            if night: src = tint_night(src)
            frame = Image.new('RGB', (w+4, h+4), (255, 255, 255))
            frame.paste(src.crop(box).resize((w, h), Image.Resampling.LANCZOS), (2, 2))
            # the top corner that covers the least of the young and the owner (projected, cell pixels)
            k = CELL[0]/RW
            boxes = [(*pts.min(0)*k, *pts.max(0)*k)]
            op = np.array([project(cam, v) for v in shifted_owner[:, :, :3].reshape(-1, 3)[::9]])*k
            boxes.append((*op.min(0), *op.max(0)))
            def cover(x, y): return sum(max(0, min(x+w+4, b[2])-max(x, b[0]))*max(0, min(y+h+4, b[3])-max(y, b[1])) for b in boxes)
            corner = min(((6, 26), (CELL[0]-w-10, 26)), key=lambda c_: cover(*c_))
            cell.paste(frame, corner)
        out.append(cell)
    return out, r


PANELS = [('owner_6n', 'day', 12), ('owner_6n', 'night', 12), ('owner_6b', 'day', 24), ('owner_c', 'day', 0)]


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    cols = 4
    look = compare_cells()
    rows = [look[:4], look[4:8]]
    for i in range(0, len(PANELS), 2):
        row = []
        for key, period, stores in PANELS[i:i+2]:
            cells, _ = layout_cells(key, period, stores); row += cells
            print(key, period, stores, flush=True)
        rows.append(row)
    head = 60
    sheet = Image.new('RGB', (cols*CELL[0], head+len(rows)*CELL[1]), BG); d = ImageDraw.Draw(sheet)
    d.text((10, 8), '水生魔理沙帽两种形态对照：现行（陆生帽翻过来）与新形态（外圆台阶尖锥＋陆生帽弯尖，内圆盆）；下两行为新形态乘员布局（建议规则：身体轮廓占位）', font=P.preview_font(22), fill='#203e48')
    d.text((10, 36), '离线 CPU 渲染；俯视：帽主半透明，轮廓为身体本身的平面范围（橙＝帽主，蓝＝幼体），白底数字为幼体身底深度，船首在上；轮廓相叠处两只身体上下错开（身体是圆的，靠弧壁的那只更高），逐格高度不相交；剖面左上为幼体放大；数值为成体模型单位（16＝1 格），深度从帽檐面往下量', font=P.preview_font(13), fill='#4b6b74')
    for i, row in enumerate(rows):
        for j, cell in enumerate(row):
            sheet.paste(cell, (j*CELL[0], head+i*CELL[1]))
            d.rectangle((j*CELL[0], head+i*CELL[1], (j+1)*CELL[0]-1, head+(i+1)*CELL[1]-1), outline=(200, 214, 218))
    sheet.save(OUT/'sheet.png'); print(OUT/'sheet.png')




def table():
    """Rows of the layout study (layouts.json): per combination, both room rules, day and night over stores 0/12/24."""
    data = json.loads((OUT/'layouts.json').read_text(encoding='utf-8'))
    by = {}
    for r in data['results']: by.setdefault((r['combo'], r['rule']), []).append(r)
    rows = []
    for key, title, oscale, young in L.COMBOS:
        cells = [title, str(sum(L.KIND[y][1] for y in young)+(2 if key.startswith('pregnant') else 0))]
        for rule in L.RULES:
            rs = by.get((key, rule), [])
            def mark(period):
                return ''.join('✓' if r['fits'] else '✗' for r in sorted((r for r in rs if r['period'] == period), key=lambda r: r['stores']))
            cells.append(f"昼 {mark('day')} / 夜 {mark('night')}")
        rs = [r for r in by.get((key, 'body'), []) if r['fits']]
        if rs:
            heel = max(rs, key=lambda r: r['ratio'])
            braid = all(p.get('braid') in (True, None) for r in rs for p in r['placed'] if p.get('placed'))
            day0 = next((r for r in rs if r['period'] == 'day' and r['stores'] == 0), rs[0])
            cells += [f"{day0['owner']['bottom']:.1f}", ' / '.join(f"{p['bottom']:.1f}" for p in day0['placed'] if p.get('placed')) or '—',
                      '✓' if braid else '✗', f"{heel['hydro']['heel']:.1f}° / {heel['flood']:.0f}°（{heel['period']}·{heel['stores']}）",
                      '✓' if all(r['upright'] for r in rs) else '偏大（>0.6）']
        else:
            cells += ['—']*5
        rows.append(cells)
    return rows, data['extra']


def page():
    rows, extra = table()
    head = ['组合', '载重', '现行规则：脚圆（储粮 0/12/24）', '建议规则：身体轮廓（储粮 0/12/24）', '帽主身底（昼·0）', '幼体身底（昼·0）',
            '早上辫托', '最大横倾 / 进水角', '稳']
    climb = ' · '.join(f"{'成体' if c['scale'] == 1 else '子油 0.45'} {int(c['share']*100)}% 体重挂檐 → {c['heel']:.0f}°{'（进水）' if c['floods'] else ''}"
                       for c in extra['climb'])
    g = extra['geometry']
    html = ('<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>水生魔理沙帽：乘员布局</title>'
            '<body style="margin:16px;background:#ecf5f6;font-family:\'Microsoft YaHei\',sans-serif;color:#203e48">'
            '<p><a href="../index.html">预览总入口</a></p><h2>水生魔理沙帽第二形态：外圆台阶帽冠＋陆生帽弯尖，内圆盆；与现行帽对照，乘员能否放下、坐在哪里</h2>'
            f"<p>离线 CPU 渲染与离线计算，非游戏画面；新形态已导出运行网格与帽船剖面，尚未接入游戏。尺寸（成体模型单位，16 = 1 格）：帽檐半径 {g['brim_radius']}，"
            f"帽冠底半径 {g['crown_radius']}，帽口半径 {g['opening_radius']}，平底半径 {g['floor_radius']}、深 {g['floor_depth']}，"
            f"龙骨底 {g['keel_depth']}，翘边高 {g['rim_height']}。载重规则见 AQUATIC_MARISA_IMPLEMENTATION 帽内空间与载重。"
            '支撑按身体真实底面；占位规则对比现行的脚圆（半身宽＋0.02 格的圆，高度重叠即冲突）与建议的身体轮廓（只算身体，不算帽子、头发和辫子）。'
            '白天帽主坐中间或尽量少往船尾挪，幼体在她身前；夜里她用 boat_cover_young 俯身，幼体在她身前或俯身下。'
            '辫托按 braid_cradle_lift 实际托兜范围，帽主原地转身。稳：静横倾不超过进水角的 0.6（HullAfloat.STEADY）。</p>'
            '<table border="1" cellpadding="4" style="border-collapse:collapse;background:#fff;font-size:13px"><tr>'
            +''.join(f'<th>{h}</th>' for h in head)+'</tr>'
            +''.join('<tr>'+''.join(f'<td>{c}</td>' for c in row)+'</tr>' for row in rows)+'</table>'
            f"<p style='font-size:13px'>从水里攀上帽檐（空帽）：{climb}。</p>"
            '<img src="sheet.png" style="max-width:100%"></body></html>\n')
    (OUT/'index.html').write_text(html, encoding='utf-8')
    return rows


if __name__ == '__main__':
    if sys.argv[1:] != ['page']: main()   # 'page' rewrites only index.html from layouts.json
    for row in page(): print(' | '.join(row))
    (OUT/'source.sha256').write_text(S.B.source_digest(), encoding='utf-8')
