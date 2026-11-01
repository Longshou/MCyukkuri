"""Preview of the lily-pad hat boat (offline CPU render of the real meshes, not the game): the set-down hull at its fixed
waterline with the captain paddling at the stern seat (braid and stick over the brim, the exported boat_paddle clip), two
赤油 in the basin, a 子油 on the brim edge leaning out to splash, the stores texture on the floor; a cut through the axis;
the four stores frames.

    MCY_LOCAL=<main checkout>/.local python preview.py   # previews/aquatic-hat-seats/current.png
"""
from pathlib import Path
import math, os, sys
import numpy as np
from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import layout as L
S = L.S; B = L.B; P = S.P
OUT = Path(os.environ.get('MCY_LOCAL', B.ROOT/'.local'))/'previews/aquatic-hat-seats'
BG = (236, 245, 246)
CUT = (58, 60, 88)                  # cut cloth (the hat's navy)
FOOD_CUT = (150, 112, 62)
RW, RH = 1200, 834                  # render size


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
    what lies inside the waterline (the cut cloth, the hollow, riders) stays dry."""
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
        wet &= ~inside   # the hollow is dry: no water drawn over what lies inside the waterline
    ripple = (np.sin(x*.22+z*.17) > .975) & (np.cos(z*.31-x*.04) > .15)
    col = np.zeros_like(pixels, float)+[123, 187, 203]; col[ripple] = [171, 218, 224]
    alpha = np.where(np.isfinite(depth), .5, 1.)
    out = pixels.astype(float); out[wet] = out[wet]*(1-alpha[wet, None])+col[wet]*alpha[wet, None]
    return out.astype(np.uint8)


def clip(tris, axis, keep=-1):
    """Cut a mesh through the hat's axis: 'x' keeps the half on the `keep` side of x 0, 'z' the half on that side of -z."""
    x = (tris[:, :, 0] if axis == 'x' else -tris[:, :, 2])*-keep; inside = x <= 1e-9
    allin = inside.all(1); out = [tris[allin]]
    for t, m in zip(tris[~allin & inside.any(1)], inside[~allin & inside.any(1)]):
        poly = []
        for i in range(3):
            p, q = t[i], t[(i+1) % 3]; pin, qin = m[i], m[(i+1) % 3]
            if pin: poly.append(p)
            if pin != qin:
                a, b = (p[0], q[0]) if axis == 'x' else (-p[2], -q[2])
                f = a/(a-b); poly.append(p+(q-p)*f)
        for i in range(1, len(poly)-1): out.append(np.array([poly[0], poly[i], poly[i+1]])[None])
    return np.concatenate(out) if out else tris[:0]


# ------------------------------------------------------------------ the scene
SWATCH = {'stick': (132, 92, 52), 'stick_dark': (98, 66, 36), 'splash': (232, 246, 250)}


def swatch(atlas, name, uv):
    return atlas.uv(name, Image.new('RGBA', (16, 16), (*SWATCH[name], 255)), uv*0+.5)


def tube(a, b, rad, n=8):
    """A closed tube (world units) from a to b."""
    a, b = np.asarray(a, float), np.asarray(b, float); d = b-a; d /= np.linalg.norm(d)
    u = np.cross(d, [0, 1, 0] if abs(d[1]) < .9 else [1, 0, 0]); u /= np.linalg.norm(u); v = np.cross(d, u); out = []
    for j in range(n):
        p, q = [rad*(u*math.cos(2*math.pi*k/n)+v*math.sin(2*math.pi*k/n)) for k in (j, j+1)]
        for tri in ((a+p, b+p, b+q), (a+p, b+q, a+q)):
            nrm = np.cross(tri[1]-tri[0], tri[2]-tri[0]); nrm /= max(1e-9, np.linalg.norm(nrm))
            out.append([[*x, 0, 0, *nrm] for x in tri])
    return np.array(out)


def blob(c, r, n=8):
    """A small sphere (splash droplets)."""
    out = []
    for i in range(n):
        for j in range(2*n):
            pts = []
            for a, b in ((i, j), (i+1, j), (i+1, j+1), (i, j+1)):
                th, ph = math.pi*a/n, math.pi*b/n
                pts.append(np.array([math.sin(th)*math.cos(ph), math.cos(th), math.sin(th)*math.sin(ph)]))
            for tri in ((pts[0], pts[1], pts[2]), (pts[0], pts[2], pts[3])):
                out.append([[*(np.asarray(c)+r*x), 0, 0, *x] for x in tri])
    return np.array(out)


def disc(radius, y, frame, frames, n=40):
    """The stores texture on the floor: a disc whose uvs cover one tier frame (+x right, the bow up)."""
    out = []
    for j in range(n):
        pts = [(0., 0.)]+[(radius*math.cos(2*math.pi*k/n), radius*math.sin(2*math.pi*k/n)) for k in (j+1, j)]
        out.append([[x, y, z, (frame+(x/radius+1)/2)/frames, (z/radius+1)/2, 0, 1, 0] for x, z in pts])
    return np.array(out)


def posed(sh, atlas, x, z, y, lean=0., yaw=0.):
    """A cached shape's triangles with the atlas uvs of its face, leant toward its +x about its bottom, turned, placed."""
    m = sh.tris.copy()
    if lean:
        a = math.radians(lean); R = np.array([[math.cos(a), math.sin(a), 0], [-math.sin(a), math.cos(a), 0], [0, 0, 1]])
        m[:, :, :3] = m[:, :, :3]@R.T; m[:, :, 5:8] = m[:, :, 5:8]@R.T
    if yaw:
        R = P.euler([0, -yaw, 0]); m[:, :, :3] = m[:, :, :3]@R.T; m[:, :, 5:8] = m[:, :, 5:8]@R.T
    m[:, :, :3] += [x, y, z]
    m[:, :, 3:5] = atlas.uv(f'marisa/{sh.face}', Image.fromarray(S.body().texture(sh.face)), m[:, :, 3:5])
    return m


def scene(atlas, tier=2):
    """The lily-pad hull with its riders: the captain paddling at the stern seat, two 赤油 before her, a 子油 on the brim
    edge leaning out to splash, the stores at `tier` on the floor (world units, water at y 0 after the shift)."""
    hull = S.Hull(); bounds = L.bounds(hull); T = L.tools()
    seat = bounds['captain_seat']; cy, cz = seat['y']*16, seat['z']*16
    model, tracks, samples, sticks, c = T.exported('boat_paddle')
    m = c['markers']; t = m['power_start']+.45*(m['power_end']-m['power_start'])
    k = int(np.argmin([abs(s-t) for s in samples])); t = samples[k]; G, d = sticks[k]; spec = T.paddle_spec(c)
    meshes, flags = [], []
    def add(tris, flag=0): meshes.append(tris); flags.append(np.full(len(tris), flag))
    hull_tris = hull.placed.copy()
    hull_tris[:, :, 3:5] = atlas.uv('marisa/neutral', Image.fromarray(S.body().texture('neutral')), hull_tris[:, :, 3:5])
    cap = S.Shape.posed('boat_paddle', t, 'open_smile')
    add(posed(cap, atlas, 0, cz, cy))
    a, b = G-spec['top_length']*d, G+spec['blade_length']*d
    stick = tube(a+[0, cy, cz], b+[0, cy, cz], .32); stick[:, :, 3:5] = swatch(atlas, 'stick', stick[:, :, 3:5]); add(stick)
    water = -B.WATER*B.K; A, Bq = a+[0, cy, cz], b+[0, cy, cz]; blade = A+(Bq-A)*(A[1]-water)/(A[1]-Bq[1])   # where the stick enters
    drops = [(blade[0]+dx, water+dy, blade[2]+dz, r) for dx, dy, dz, r in ((.9, .5, -.6, .32), (.4, 1.1, -.2, .26), (1.3, .9, .4, .22), (-.2, .6, .5, .24))]
    for sh, x, z, y in L.seat_young(hull, [L.BABY, L.BABY], L.captain(hull), ahead=1.5):
        sx, sz = sh.window(x, z)[2]; add(posed(sh, atlas, sx, sz, y))
    young = L.young_shape(L.SMALL); bear = -45.; r = bounds['brim_ring']['radius']*16
    under = dict(bounds['brim_ring']['underside_y'])[L.SMALL]*16
    yx, yz = r*math.cos(math.radians(bear)), r*math.sin(math.radians(bear))
    add(posed(young, atlas, yx, yz, under-young.bottom, L.LEAN, bear))
    tip = np.array([yx, water, yz])+(L.CHAIN*L.SMALL*.15)*np.array([math.cos(math.radians(bear)), 0, math.sin(math.radians(bear))])
    drops += [(tip[0]+dx, water+dy, tip[2]+dz, rr) for dx, dy, dz, rr in ((.3, .45, -.3, .2), (-.2, .8, .2, .16), (.5, .7, .4, .14))]
    for x, y, z, rr in drops:
        bl = blob((x, y, z), rr); bl[:, :, 3:5] = swatch(atlas, 'splash', bl[:, :, 3:5]); add(bl)
    food = None
    if tier:
        frames = np.array(Image.open(HERE/'food_layer.png').convert('RGBA')); n = B.R['stores']['frame']
        food = disc(B.IN['floor_radius']*B.K, -B.IN['floor_depth']*B.K+.06, 0, 1)
        food[:, :, 3:5] = atlas.uv(f'food{tier}', Image.fromarray(frames[:, tier*n:(tier+1)*n]), food[:, :, 3:5])
    return hull, hull_tris, meshes, flags, food


def render(meshes, flags, atlas, view, hull, cut=False):
    """One view; the water plane at the fixed waterline over everything outside the hull's dry hollow."""
    az, el, span, center = view; line = -B.WATER*B.K
    tris = np.concatenate(meshes).copy(); fl = np.concatenate(flags); tris[:, :, 1] -= line
    px, depth, cam = rasterize(tris, fl, np.array(atlas.image), az, el, span, np.array(center, float)-[0, line, 0])
    def dry(x, z):
        i = np.floor((x+S.GRID.half)/S.GRID.step).astype(int); j = np.floor((z+S.GRID.half)/S.GRID.step).astype(int)
        ok = (i >= 0) & (i < S.GRID.n) & (j >= 0) & (j < S.GRID.n); out = np.zeros(x.shape, bool)
        out[ok] = B.in_opening(x[ok], z[ok]); return out
    return Image.fromarray(water(px, depth, cam, dry, cut=cut)), cam


def side_water(img, cam):
    """Side cut (level camera): water over everything under the waterline except the dry hollow, the line itself drawn."""
    right, up, eye, scale, c = cam; px = np.array(img).astype(float); line = -B.WATER*B.K
    yy, xx = np.mgrid[:RH, :RW]; z = (xx+.5-RW/2)/scale+c@right; y = (RH/2-yy-.5)/scale+c@up+line
    curve = np.array([(r*B.K, -d*B.K) for r, d in B.basin_curve()])[::-1]
    floor = np.interp(np.abs(z), curve[:, 0], curve[:, 1], right=np.inf)
    wet = (y < line) & ~((np.abs(z) < B.IN['opening']*B.K) & (y > floor-.05))
    px[wet] = px[wet]*.45+np.array([110, 178, 200])*.55
    px[(np.abs(y-line) < 1.2/scale) & (np.abs(z) >= B.IN['opening']*B.K)] = (60, 128, 160)   # the line on open water
    return Image.fromarray(px.astype(np.uint8))


def strip(scale=7):
    """The four stores frames, upscaled, labelled by portions."""
    frames = Image.open(HERE/'food_layer.png').convert('RGBA'); n = B.R['stores']['frame']; f = P.preview_font(22)
    out = Image.new('RGB', (4*(n*scale+40)+20, n*scale+70), (250, 251, 252)); d = ImageDraw.Draw(out)
    for i, (lo, hi) in enumerate(B.R['stores']['tiers']):
        x0 = 20+i*(n*scale+40); bg = Image.new('RGBA', (n*scale, n*scale), (74, 78, 108, 255))
        bg.alpha_composite(frames.crop((i*n, 0, (i+1)*n, n)).resize((n*scale, n*scale), Image.Resampling.NEAREST))
        out.paste(bg.convert('RGB'), (x0, 10))
        d.text((x0, n*scale+20), '空（0 份）' if not hi else f'{lo}–{hi} 份', font=f, fill=(40, 60, 70))
    return out


def main():
    atlas = Atlas()
    hull, hull_tris, meshes, flags, food = scene(atlas)
    bodies = meshes+([food] if food is not None else [])
    bflags = flags+([np.zeros(len(food), int)] if food is not None else [])
    top, _ = render([hull_tris]+bodies, [np.zeros(len(hull_tris), int)]+bflags, atlas, (.3, .36, 14., (2., -1., -2.)), hull)
    cut_hull = clip(hull_tris, 'x', keep=1); cut_food = clip(food, 'x', keep=1) if food is not None else None
    side_meshes = [cut_hull]+meshes+([cut_food] if cut_food is not None else [])
    side_flags = [np.ones(len(cut_hull), int)]+flags+([np.zeros(len(cut_food), int)] if cut_food is not None else [])
    tris = np.concatenate(side_meshes).copy(); line = -B.WATER*B.K; tris[:, :, 1] -= line
    px, _, cam = rasterize(tris, np.concatenate(side_flags), np.array(atlas.image), -math.pi/2, 0., 10.5, np.array([0, .5-line, -1.5]))
    side = side_water(px, cam)
    W = 1840; img = Image.new('RGB', (W, 1100), (250, 251, 252)); d = ImageDraw.Draw(img); f, g = P.preview_font(30), P.preview_font(18)
    img.paste(top.resize((900, 626), Image.Resampling.LANCZOS), (20, 60)); img.paste(side.resize((880, 612), Image.Resampling.LANCZOS), (940, 60))
    d.text((20, 14), '睡莲叶帽船（当前网格）：斜上方看', font=f, fill=(32, 52, 64))
    d.text((940, 14), '剖面（船首在右）', font=f, fill=(32, 52, 64))
    notes = [('帽子翻过来放下，与戴着同大（圆形 1.0 倍）', '帽盆在水下，但里面是干的'),
             ('帽檐外缘落到水面，像睡莲叶', '水线固定在帽檐面下 0.5 模型单位'), ('船长坐在船尾（座位离中心 0.75）', '辫子握着木枝越过帽檐划水'),
             ('两只赤油（0.20）坐在帽盆里', '身体低于帽口'), ('小子油（0.30）坐在船首帽檐边', '向水面侧倾 10° 时辫子能拍到水'),
             ('帽底铺一张储粮贴图', '左图为 3–5 份')]
    bare = [hull_tris]+([food] if food is not None else [])
    above, _ = render(bare, [np.zeros(len(m), int) for m in bare], atlas, (0., 1.5, 15.5, (0., -2., 0.)), hull)
    img.paste(above.resize((480, 334), Image.Resampling.LANCZOS), (20, 740))
    d.text((20, 702), '空帽从上方看（帽底储粮 3–5 份，不画乘员）', font=P.preview_font(22), fill=(32, 52, 64))
    for i, (t, u) in enumerate(notes):
        d.text((520, 745+i*56), '· '+t, font=g, fill=(40, 60, 70)); d.text((536, 771+i*56), u, font=g, fill=(40, 60, 70))
    d.text((940, 700), '储粮贴图分档（帽底，一张贴图四格）', font=P.preview_font(22), fill=(32, 52, 64))
    s = strip(6); img.paste(s.resize((880, int(880*s.height/s.width)), Image.Resampling.LANCZOS), (940, 740))
    OUT.mkdir(parents=True, exist_ok=True)
    img.save(OUT/'current.png'); (OUT/'source.sha256').write_text(B.source_digest(), encoding='utf-8')
    print(OUT/'current.png')


if __name__ == '__main__':
    main()
