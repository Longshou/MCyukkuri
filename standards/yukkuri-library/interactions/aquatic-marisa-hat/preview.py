"""Preview of the lily-pad hat boat (offline CPU render of the real meshes, not the game): the set-down hull at its fixed
waterline with the captain paddling at the stern seat (braid and stick over the brim, the exported boat_paddle clip), two
赤油 in the basin, a 子油 on the brim edge leaning out to splash, the stores texture on the floor; a cut through the axis;
the four stores coverage tiers, all cropping the existing texture at the same texel size.

    MCY_LOCAL=<main checkout>/.local python preview.py          # previews/aquatic-hat-seats/current.png
    MCY_LOCAL=<main checkout>/.local python preview.py --clips  # previews/aquatic-clips/current.png: key frames of the
                                                                # boat clips (paddling, rescue reach, a 子油 splashing, hiding)
    MCY_LOCAL=<main checkout>/.local python preview.py --paddle # previews/braid-paddle/current.png: one boat_paddle stroke
    MCY_LOCAL=<main checkout>/.local python preview.py --places # previews/art-a1-places/current.png: captain seat, hatted
                                                                # cabin young, head riders and the braid's transfer paths
    MCY_LOCAL=<main checkout>/.local python preview.py --brim   # previews/art-a2b-brim-water/current.png: brim splash and
                                                                # mimic paddle (braid to the water), head peek, the swimming
                                                                # beckon stroke beside the hat
    MCY_LOCAL=<main checkout>/.local python preview.py --birth  # previews/art-a3-hat-birth/current.png: the full-term captain
                                                                # in labour and one and two newborns at their landing spots
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
    right = np.array([-c, 0, -s]); up = np.array([-s*se, ce, c*se]); eye = np.array([s*ce, se, -c*ce]); scale = RH/(2*span)
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
SWATCH = {'stick': (132, 92, 52), 'stick_dark': (98, 66, 36), 'splash': (232, 246, 250), 'path_cabin': (232, 128, 40),
          'path_head': (150, 70, 190)}


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


def disc(radius, y, fraction, n=40):
    """Bow-tangent coverage; hull-space UVs on a 2x2 repeat of the existing texture, at 16 px/block."""
    out = []; r = radius*fraction; z0 = -(1-fraction)*radius
    uv_scale = B.R['stores']['pixels_per_block']/(16*2*B.R['stores']['texture_px'])
    for j in range(n):
        pts = [(0., z0)]+[(r*math.cos(2*math.pi*k/n), z0+r*math.sin(2*math.pi*k/n)) for k in (j+1, j)]
        out.append([[x, y, z, .5+x*uv_scale, .5+z*uv_scale, 0, 1, 0] for x, z in pts])
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
        texture = np.tile(np.array(Image.open(HERE/'food_layer.png').convert('RGBA')), (2, 2, 1))
        food = disc(B.IN['floor_radius']*B.K, -B.IN['floor_depth']*B.K+.06, B.R['stores']['radius_fractions'][tier])
        food[:, :, 3:5] = atlas.uv('food', Image.fromarray(texture), food[:, :, 3:5])
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
    """The same floor and texel size at every tier; the bow is up and each food disc touches that wall."""
    texture = np.array(Image.open(HERE/'food_layer.png').convert('RGBA')); n = 32*scale; f = P.preview_font(22)
    radius = B.IN['floor_radius']*B.K; axis = ((np.arange(n)+.5)/n*2-1)*radius; x, z = np.meshgrid(axis, axis)
    uv = np.floor(axis*B.R['stores']['pixels_per_block']/16).astype(int) % B.R['stores']['texture_px']
    pixels = texture[uv[:, None], uv[None, :], :3]
    out = Image.new('RGB', (4*(n+40)+20, n+70), (250, 251, 252)); d = ImageDraw.Draw(out)
    for i, (lo, hi) in enumerate(B.R['stores']['tiers']):
        x0 = 20+i*(n+40); bg = np.full((n, n, 3), (250, 251, 252), np.uint8)
        bg[x*x+z*z <= radius*radius] = (74, 78, 108)
        fraction = B.R['stores']['radius_fractions'][i]; covered = x*x+(z+(1-fraction)*radius)**2 < (fraction*radius)**2
        bg[covered] = pixels[covered]; out.paste(Image.fromarray(bg), (x0, 10))
        d.text((x0, n+20), '空（0 份）' if not hi else f'{lo}–{hi} 份', font=f, fill=(40, 60, 70))
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
    d.text((940, 14), '剖面（船首在左）', font=f, fill=(32, 52, 64))
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
    d.text((940, 700), '储粮覆盖分档（复用 16×16 贴图，固定 16 像素/格）', font=P.preview_font(22), fill=(32, 52, 64))
    s = strip(6); img.paste(s.resize((880, int(880*s.height/s.width)), Image.Resampling.LANCZOS), (940, 740))
    OUT.mkdir(parents=True, exist_ok=True)
    img.save(OUT/'current.png'); (OUT/'source.sha256').write_text(B.source_digest(), encoding='utf-8')
    print(OUT/'current.png')


def lowest(tris, outside=0.):
    """The lowest vertex of a mesh farther than `outside` from the hull's axis (a braid tip over the water)."""
    v = tris[:, :, :3].reshape(-1, 3); v = v[np.hypot(v[:, 0], v[:, 2]) >= outside]; return v[np.argmin(v[:, 1])]


def splash(atlas, at, size=1.):
    out = []
    for dx, dy, dz, r in ((.5, .45, -.3, .24), (-.3, .85, .2, .2), (.7, .7, .45, .17), (-.5, .4, -.5, .15)):
        bl = blob((at[0]+dx*size, -B.WATER*B.K+dy*size, at[2]+dz*size), r*size); bl[:, :, 3:5] = swatch(atlas, 'splash', bl[:, :, 3:5])
        out.append(bl)
    return np.concatenate(out)


def clips_main():
    """Key frames of the boat performances as the player sees them: <MCY_LOCAL or .local>/previews/aquatic-clips/current.png."""
    atlas = Atlas(); hull, hull_tris, meshes, flags, food = scene(atlas)
    bounds = L.bounds(hull); seat = bounds['captain_seat']; cy, cz = seat['y']*16, seat['z']*16
    ring, under = bounds['brim_ring']['radius']*16, dict(bounds['brim_ring']['underside_y'])[L.SMALL]*16
    edge = B.BRIM['radius']*B.K; base = [hull_tris]+([food] if food is not None else [])
    sit = L.young_shape(L.SMALL).bottom   # the clip leans about where it sits, so place it by its upright underside
    def kid(clip, t, face, bear):
        sh = S.Shape.posed(clip, t, face, L.SMALL, 0., hat=True)
        return posed(sh, atlas, ring*math.cos(math.radians(bear)), ring*math.sin(math.radians(bear)), under-sit, 0., bear)
    rest = posed(S.Shape.posed('boat_feed_young', 0., 'closed_smile'), atlas, 0, cz, cy)
    babies = meshes[2:4]
    reach = posed(S.Shape.posed('braid_rescue_reach', 1.0, 'teary'), atlas, 0, cz, cy); tip = lowest(reach, edge)
    child = S.Shape.posed('water_struggle', .3, 'cry', L.BABY, 0., hat=True); out = tip/np.hypot(tip[0], tip[2])
    swim = posed(child, atlas, tip[0]+out[0]*2.2, tip[2]+out[2]*2.2, -B.WATER*B.K-.25*child.height, 0., 0.)
    splasher = kid('kid_brim_splash', .62, 'happy', -50.)
    hider = kid('kid_hide_behind', 1.2, 'teary', -3.)
    panels = [('船长坐在船尾，用辫子握着木枝划水', meshes[:4]+meshes[5:9], (.3, .5, 14., (2., -1., -2.))),
              ('辫子越过帽檐伸到水面，接住落水的孩子', [reach, swim]+babies, (1.05, .55, 15.5, (5., -1., -1.))),
              ('子油坐在帽檐边，探出身子用辫子拍水', [rest, splasher, splash(atlas, lowest(splasher, edge), 1.6)]+babies, (-.87, .22, 13.5, (4., 0., -5.))),
              ('受惊的子油缩到妈妈的辫子下面', [rest, hider]+babies, (.08, .16, 13., (6., 2., 0.)))]
    W, H = 1840, 1400; img = Image.new('RGB', (W, H), (250, 251, 252)); d = ImageDraw.Draw(img); f = P.preview_font(28)
    for i, (caption, bodies, view) in enumerate(panels):
        shot, _ = render(base+bodies, [np.zeros(len(m), int) for m in base+bodies], atlas, view, hull)
        x, y = 20+(i % 2)*910, 20+(i//2)*690
        img.paste(shot.resize((890, 619), Image.Resampling.LANCZOS), (x, y+50)); d.text((x, y+8), caption, font=f, fill=(32, 52, 64))
    out = OUT.parent/'aquatic-clips'; out.mkdir(parents=True, exist_ok=True)
    img.save(out/'current.png'); (out/'source.sha256').write_text(B.source_digest(), encoding='utf-8')
    print(out/'current.png')


def paddle_main():
    """One boat_paddle stroke from the stern and from her braid side: <MCY_LOCAL or .local>/previews/braid-paddle/current.png."""
    atlas = Atlas(); hull = S.Hull(); bounds = L.bounds(hull); T = L.tools()
    seat = bounds['captain_seat']; cy, cz = seat['y']*16, seat['z']*16
    hull_tris = hull.placed.copy()
    hull_tris[:, :, 3:5] = atlas.uv('marisa/neutral', Image.fromarray(S.body().texture('neutral')), hull_tris[:, :, 3:5])
    _, _, samples, sticks, c = T.exported('boat_paddle'); m = c['markers']; spec = T.paddle_spec(c)
    back = m['power_end']+(c['duration']-(m['power_end']-m['power_start']))/2
    beats = [(m['power_start'], '入水：辫子从根部垂下，下段伸出帽檐'), ((m['power_start']+m['power_end'])/2, '拉水：辫梢带着木枝向后划'),
             (m['power_end'], '出水：木枝到了身后，辫根只微动'), (back % c['duration'], '回桨：辫梢向外卷起，把木枝往前送')]
    views = [('从船尾看辫子的形状', (math.pi, .15, 9., (7., 4.5, 2.))), ('从辫子一侧看划水的前后摆动', (math.pi/2, .35, 11., (9., 2., 0.)))]
    W, H = 4*450+40, 2*(330+50)+70; img = Image.new('RGB', (W, H), (250, 251, 252)); d = ImageDraw.Draw(img)
    d.text((20, 14), '魔理沙用辫子划船：辫子自然垂下，末梢向外（离线渲染，不是游戏画面）', font=P.preview_font(28), fill=(32, 52, 64))
    for j, (t, caption) in enumerate(beats):
        k = int(np.argmin([abs(s-t) for s in samples])); G, dv = sticks[k]
        a, b = G-spec['top_length']*dv+[0, cy, cz], G+spec['blade_length']*dv+[0, cy, cz]
        stick = tube(a, b, .32); stick[:, :, 3:5] = swatch(atlas, 'stick', stick[:, :, 3:5])
        bodies = [hull_tris, posed(S.Shape.posed('boat_paddle', samples[k], 'closed_smile'), atlas, 0, cz, cy), stick]
        for i, (label, view) in enumerate(views):
            shot, _ = render(bodies, [np.zeros(len(q), int) for q in bodies], atlas, view, hull)
            x, y = 20+j*450, 70+i*380
            if j == 0: d.text((x, y), label, font=P.preview_font(22), fill=(70, 90, 100))
            img.paste(shot.resize((440, 306), Image.Resampling.LANCZOS), (x, y+34))
            if i == 0: d.text((x, y+344), caption, font=P.preview_font(19), fill=(32, 52, 64))
    out = OUT.parent/'braid-paddle'; out.mkdir(parents=True, exist_ok=True)
    img.save(out/'current.png'); (out/'source.sha256').write_text(B.source_digest(), encoding='utf-8')
    print(out/'current.png')


def keep_out(cap_sh, cx, cz, cy, bearing, extra):
    """The captain's footprint reach (all of her mesh) along a bearing from her middle, plus `extra` (world units)."""
    top = cap_sh.full('top', cx, cz, cy); m = np.isfinite(top); dx, dz = S.GRID.x[m]-cx, S.GRID.z[m]-cz
    a = np.arctan2(dz, dx); near = np.abs(np.angle(np.exp(1j*(a-bearing)))) < math.radians(8)
    return float(np.hypot(dx[near], dz[near]).max())+extra


def carry_path(cap_sh, cx, cz, cy, start, end, high, disc):
    """The transfer contract in the carrier frame: rise at the grip to `high`, swing round her middle at that height through
    the front half (bow, -z) outside her footprint plus the child's headwear disc, come in above the destination, go straight
    down onto it."""
    pts = [np.array(start, float), np.array([start[0], high, start[2]])]
    a0 = math.atan2(start[2]-cz, start[0]-cx); a1 = math.atan2(end[2]-cz, end[0]-cx)
    turn = math.atan2(math.sin(a1-a0), math.cos(a1-a0)); other = turn-math.copysign(2*math.pi, turn)
    if math.sin(a0+other/2) < math.sin(a0+turn/2): turn = other          # the arc whose middle lies further toward the bow (-z)
    r0 = math.hypot(start[0]-cx, start[2]-cz)
    for q in np.linspace(0, 1, 25)[1:]:
        a = a0+turn*q; r = max(r0*(1-q)+math.hypot(end[0]-cx, end[2]-cz)*q, keep_out(cap_sh, cx, cz, cy, a, disc) if high < 99 else 0)
        pts.append(np.array([cx+r*math.cos(a), high, cz+r*math.sin(a)]))
    pts += [np.array([end[0], high, end[2]]), np.array(end, float)]
    return pts


def places_main():
    """Captain at her seat, hatted 赤油 in the basin at the measured pair limit, two on her bare head, and the braid's two
    transfer paths (water to the basin, water to the head): <MCY_LOCAL or .local>/previews/art-a1-places/current.png."""
    atlas = Atlas(); hull = S.Hull(); cap_sh, (cx, cz, cy) = L.captain(hull)
    hull_tris = hull.placed.copy()
    hull_tris[:, :, 3:5] = atlas.uv('marisa/neutral', Image.fromarray(S.body().texture('neutral')), hull_tris[:, :, 3:5])
    meshes = [hull_tris, posed(cap_sh, atlas, *cap_sh.window(cx, cz)[2], cy)]
    size = .15; cabin = L.cabin_hats(hull, [size, size], mirror=True)
    for s_, x, z in cabin:
        sh = L.young_shape(s_); meshes.append(posed(sh, atlas, *sh.window(x, z)[2], sh.rest(hull.field, x, z)))
    top = cap_sh.full('top', cx, cz, cy); heads = []
    for dz in (-2.4, 3.4):
        sh = L.young_shape(size, 90.); x, z = cx, cz+dz; y = sh.rest(top, x, z, overhang=True)
        meshes.append(posed(sh, atlas, *sh.window(x, z)[2], y)); heads.append((x, y+sh.bottom, z))
    child = L.young_shape(size); h = child.height; disc = 20.26*B.K*size/2+L.HAT_GAP; water = -B.WATER*B.K
    start = (17.5, water, -2.)
    cab = cabin[0] if cabin[0][1] > 0 else cabin[1]
    paths = [(carry_path(cap_sh, cx, cz, cy, start, (cab[1], hull.field[np.unravel_index(np.argmin(np.hypot(S.GRID.x-cab[1], S.GRID.z-cab[2])), S.GRID.x.shape)], cab[2]), h+1.5, disc), 'path_cabin'),
             (carry_path(cap_sh, cx, cz, cy, start, heads[0], float(np.nanmax(np.where(np.isfinite(top), top, np.nan)))+h+1.5, disc), 'path_head')]
    for pts, key in paths:
        for a, b in zip(pts, pts[1:]):
            if np.linalg.norm(b-a) < 1e-6: continue
            t = tube(a, b, .22, 6); t[:, :, 3:5] = swatch(atlas, key, t[:, :, 3:5]); meshes.append(t)
        for k in (0, 1, len(pts)-2, len(pts)-1):
            bl = blob(pts[k], .5, 6); bl[:, :, 3:5] = swatch(atlas, key, bl[:, :, 3:5]); meshes.append(bl)
    flags = [np.zeros(len(m), int) for m in meshes]
    views = [('从辫子一侧斜上方看', (2.25, .45, 15., (3., 1., -2.))), ('从船首看（她面朝镜头）', (0., .2, 13., (0., 1.5, 0.))),
             ('从上方看搬运路线（船首在下）', (0., 1.5, 17., (2., 0., -1.)))]
    W, H = 3*600+40, 560; img = Image.new('RGB', (W, H), (250, 251, 252)); d = ImageDraw.Draw(img)
    d.text((20, 14), '帽船上的位置：船长座、帽内赤油（带帽测量）、头顶位、辫子搬运路线（离线渲染，不是游戏画面）', font=P.preview_font(26), fill=(32, 52, 64))
    for i, (label, view) in enumerate(views):
        shot, _ = render(meshes, flags, atlas, view, hull)
        x = 20+i*600; d.text((x, 60), label, font=P.preview_font(21), fill=(70, 90, 100))
        img.paste(shot.resize((590, 410), Image.Resampling.LANCZOS), (x, 92))
    notes = [f'船长坐在船尾座位（离中心 0.75），面朝船首；帽内两只 {size:.2f} 赤油戴着帽子，帽子不碰她的脸和帽壁',
             '头顶两只赤油前后坐在头发顶上（摘帽时才有头顶位），可以转身，不走动，不碰水',
             '橙线：从水里提起→升高→绕辫子一侧经身前转过去→正上方直放进帽内；紫线：同样绕前，升过头顶后直放到头上；都不穿过她的身体']
    img2 = Image.new('RGB', (W, H+90), (250, 251, 252)); img2.paste(img, (0, 0)); d2 = ImageDraw.Draw(img2)
    for i, t in enumerate(notes): d2.text((20, 512+i*30), '· '+t, font=P.preview_font(19), fill=(40, 60, 70))
    out = OUT.parent/'art-a1-places'; out.mkdir(parents=True, exist_ok=True)
    img2.save(out/'current.png'); (out/'source.sha256').write_text(B.source_digest(), encoding='utf-8')
    print(out/'current.png', 'cabin', cabin, 'heads', [tuple(round(v, 2) for v in q) for q in heads])


def rider(atlas, clip, t, face, scale, support, x, z, yaw):
    """An exported clip posed at growth `scale`, turned by `yaw` (scene.place) and standing at (x, z) where its upright young
    pose rests on `support` (layout.act's placement): its triangles and its braid's points."""
    up = L.young_shape(scale, yaw); y0 = up.rest(support, x, z, overhang=True); _, _, (sx, sz) = up.window(x, z)
    tris, kinds = S.body().mesh(clip, t, face=face, hat=B.worn_mesh() if scale < 1 else None)
    m = S.place(tris, (sx, y0, sz), yaw, scale)
    m[:, :, 3:5] = atlas.uv(f'marisa/{face}', Image.fromarray(S.body().texture(face)), m[:, :, 3:5])
    return m, m[kinds == 2][:, :, :3].reshape(-1, 3)


def wet_splash(atlas, braid, size, outside=0.):
    """Droplets where the braid is under the water (past `outside` from the hull's axis), else nothing."""
    v = braid[np.hypot(braid[:, 0], braid[:, 2]) >= outside]; low = v[np.argmin(v[:, 1])]
    return [splash(atlas, low, size)] if low[1] < -B.WATER*B.K else []


def brim_main():
    """The visible baby repertoire and the swimming beckon (layout.BRIM_ACTS / HEAD_ACTS facings, exported clips):
    <MCY_LOCAL or .local>/previews/art-a2b-brim-water/current.png."""
    atlas = Atlas(); hull = S.Hull(); cap_sh, (cx, cz, cy) = L.captain(hull); edge = B.BRIM['radius']*B.K; water = -B.WATER*B.K
    hull_tris = hull.placed.copy()
    hull_tris[:, :, 3:5] = atlas.uv('marisa/neutral', Image.fromarray(S.body().texture('neutral')), hull_tris[:, :, 3:5])
    cap = posed(cap_sh, atlas, *cap_sh.window(cx, cz)[2], cy); top = cap_sh.full('top', cx, cz, cy)
    size, bear = .15, L.BEARING; a = math.radians(bear); r = L.brim_ring(size); bx, bz = r*math.cos(a), r*math.sin(a)
    clips = S.body().clips; panels = []
    def face_on(yaw):   # the camera azimuth looking at a rider's face (its -z) from in front
        f = S.place(np.array([[[0., 0., -1., 0, 0, 0, 0, 0]]*3]), (0, 0, 0), yaw)[0, 0, :3]; return math.atan2(f[0], -f[2])
    def brim(clip, t, kind, caption, bearing=bear, face='happy', side=0., outside=False):
        q = math.radians(bearing); rr = L.brim_ring(size); x, z = rr*math.cos(q), rr*math.sin(q); yaw = L.facing(kind, bearing)
        kid, braid = rider(atlas, clip, t, face, size, hull.field, x, z, yaw)
        wet = wet_splash(atlas, braid, .4, edge) if clip in L.WATER_ACTS else []
        view = (math.atan2(math.cos(q), -math.sin(q))+side, .3, 3.2, (x*.9, .8, z*.9)) if outside else (face_on(yaw)+side, .12, 2.1, (x, .5, z))
        panels.append((caption+f'（{t:.2f}s）', [hull_tris, cap, kid]+wet, view))
    splash_c = clips['kid_brim_splash']['markers']
    brim('kid_brim_splash', .3, 'braid_out', '帽檐拍水·举辫：辫子一侧朝水')
    brim('kid_brim_splash', splash_c['slap_1'], 'braid_out', '帽檐拍水·拍下：辫梢拍进水里')
    brim('kid_mimic_paddle', clips['kid_mimic_paddle']['markers']['dip'], 'braid_out', '帽檐学划：辫梢在水面往后划')
    peek, _ = rider(atlas, 'boat_rim_peek', 1.3, 'happy', size, top, cx, cz-2.4, L.facing('bow', 0.))
    panels.append(('头顶探头：摘帽时坐在她头上向前探身（不碰水）（1.30s）', [hull_tris, cap, peek], (math.pi/2, .1, 3.2, (cx, 6.8, cz-3.))))
    brim('boat_rim_peek', 1.3, 'face_out', '帽檐探头：面朝外探身看水', side=.6)
    brim('kid_hide_behind', 1.2, 'face_in', '受惊缩身：在帽檐上朝向亲油缩成一团', bearing=20., face='teary', side=.5, outside=True)
    c = clips['water_beckon']; m = c['markers']; d = c['duration']; hx = edge+13.6+2.   # she floats this far from the hat's axis
    for t, caption in ((m['power_start'], '入水：辫梢伸向帽子'), (m['power_end'], '拉水到身边：辫梢在水下往回拨'),
                       ((m['stroke']+d)/2, '回程：辫梢出水再伸出去')):
        sh = S.Shape.posed('water_beckon', t, 'closed_smile', 1., 90.)
        body = posed(sh, atlas, hx, 0., water-4.)
        tris, kinds = S.body().mesh('water_beckon', t, face='closed_smile'); braid = S.place(tris, (hx, water-4., 0.), 90.)[kinds == 2][:, :, :3].reshape(-1, 3)
        panels.append((f'游在帽旁拨水·{caption}（{t:.2f}s）', [hull_tris, body]+wet_splash(atlas, braid, 1.6), (math.pi, .12, 9., (hx-8., 2., 0.))))
    W, H = 3*600+40, 3*470+150; img = Image.new('RGB', (W, H), (250, 251, 252)); dr = ImageDraw.Draw(img)
    dr.text((20, 14), '帽檐与头顶上看得见的赤油演出、三格内游在帽旁拨水（离线渲染，不是游戏画面）', font=P.preview_font(26), fill=(32, 52, 64))
    for i, (caption, meshes, view) in enumerate(panels):
        shot, _ = render(meshes, [np.zeros(len(q), int) for q in meshes], atlas, view, hull)
        x, y = 20+(i % 3)*600, 60+(i//3)*470
        dr.text((x, y), caption, font=P.preview_font(19), fill=(40, 60, 70)); img.paste(shot.resize((590, 410), Image.Resampling.LANCZOS), (x, y+32))
    notes = ['上两排：赤油（0.15）坐在帽檐上，拍水与学划时辫子一侧朝外、辫梢入水（白点为水花）；探头面朝外；受惊时朝向亲油缩身；头顶的赤油只做不碰水的动作',
             '下排从侧面看：她游在帽子旁边，辫子一侧转向帽子，辫梢在水下从外往身边拨，回程出水；帽子靠水流与这一拨漂近，不是被拽']
    for i, t in enumerate(notes): dr.text((20, H-80+i*32), '· '+t, font=P.preview_font(19), fill=(40, 60, 70))
    out = OUT.parent/'art-a2b-brim-water'; out.mkdir(parents=True, exist_ok=True)
    img.save(out/'current.png'); (out/'source.sha256').write_text(B.source_digest(), encoding='utf-8')
    print(out/'current.png')


def birth_main():
    """Own-hat birth afloat (layout.hat_birth): the full-term captain bracing on her seat line, then one and two newborns at
    their landing spots with the drop from each exit: <MCY_LOCAL or .local>/previews/art-a3-hat-birth/current.png."""
    atlas = Atlas(); hull = S.Hull(); cap_sh, (_, z, cy) = L.captain(hull); hb = L.hat_birth(hull, z)
    hull_tris = hull.placed.copy()
    hull_tris[:, :, 3:5] = atlas.uv('marisa/neutral', Image.fromarray(S.body().texture('neutral')), hull_tris[:, :, 3:5])

    def her(belly, t, sq=0.):
        sh = L.labor_shape(belly, t, sq); return posed(sh, atlas, *sh.window(0., z)[2], L.seat_lift(hull, z, belly))

    def drop(a, b, ticks, key):
        pts = []
        for k in range(4*ticks+1):
            t = k/4; p = a+(b-a)*t/ticks; p[1] += L.GRAVITY/2*t*(ticks-t); pts.append(p+[0, .6, 0])
        out = []
        for p, q in zip(pts, pts[1:]):
            tb = tube(p, q, .16, 6); tb[:, :, 3:5] = swatch(atlas, key, tb[:, :, 3:5]); out.append(tb)
        bl = blob(pts[0], .4, 6); bl[:, :, 3:5] = swatch(atlas, key, bl[:, :, 3:5]); return out+[bl]

    def bow_cut(at):
        """The hull without its bow wall beyond z = at, so the newborns on the floor show from the bow."""
        m = hull_tris.copy(); m[:, :, 2] -= at; m = clip(m, 'z', keep=-1); m[:, :, 2] += at; return m

    def brood(count, growth, t_mother, cut=None):
        row = hb[count]; meshes = [hull_tris if cut is None else bow_cut(cut), her(L.belly_after(count, count), t_mother)]
        _, kids = L.check_spots(hull, z, count, growth, row['spots'])
        for (x, zz), kid in zip(row['spots'], kids):
            sh = L.young_shape(growth); meshes.append(posed(sh, atlas, *sh.window(x, zz)[2], sh.rest(hull.field, x, zz)))
            meshes += drop(np.array(kid['exit']), np.array(kid['land']), kid['ticks'], 'path_cabin')
        return meshes

    g1, g2 = hb[1]['growth_max'], hb[2]['growth_max']
    lean = L.marker('exit')+1.2
    shots = [('临产：满孕亲油在船尾座位用力（肚子把帽口塞满，身体搭在帽壁上）', [hull_tris, her(1., 3.9, 1.)], (-.7, .3, 15., (0., 2., -1.))),
             (f'生一只（{g1:.3f}）：从出口落进身前帽底，她俯身看着（剖开船首帽壁）', brood(1, g1, lean, hb[1]['spots'][0][1]-2.), (.25, .2, 10., (0., -1., -5.))),
             (f'生两只（各 {g2:.3f}）：先辫子一侧后另一侧（剖开船首帽壁）', brood(2, g2, lean, hb[2]['spots'][0][1]-2.), (.25, .2, 10., (0., -1., -5.))),
             ('从上方看两只的落点（船首在下）', brood(2, g2, lean), (0., 1.5, 13., (0., 0., -3.)))]
    W, H = 2*700+30, 2*470+170; img = Image.new('RGB', (W, H), (250, 251, 252)); d = ImageDraw.Draw(img)
    d.text((20, 14), '水上自帽分娩：一至两只直接生到自己帽子里（离线渲染，不是游戏画面）', font=P.preview_font(26), fill=(32, 52, 64))
    for i, (label, meshes, view) in enumerate(shots):
        shot, _ = render(meshes, [np.zeros(len(m), int) for m in meshes], atlas, view, hull, cut='剖开' in label)
        x, y = 20+(i % 2)*705, 60+(i//2)*470; d.text((x, y), label, font=P.preview_font(20), fill=(70, 90, 100))
        img.paste(shot.resize((690, 430), Image.Resampling.LANCZOS), (x, y+30))
    under = hb['underside']
    notes = [f'满孕时身体比帽底宽，下侧头发搭在帽壁上，比平时坐高 {(under[1.0]-cy-cap_sh.bottom)/16:.2f} 格；生完逐只回落（船尾座位不变，不转身）',
             f'橙线：孩子从出口下落 {hb[1][g1]["children"][0]["ticks"]} 刻到帽底，不高过出口，翻不出帽檐；落点在她身前、帽壁以内',
             f'一只最大 {g1:.3f}，两只各最大 {g2:.3f}（胎生 0.10–0.19）；更大或三只时照常临产上岸']
    for i, t in enumerate(notes): d.text((20, H-100+i*30), '· '+t, font=P.preview_font(19), fill=(40, 60, 70))
    out = OUT.parent/'art-a3-hat-birth'; out.mkdir(parents=True, exist_ok=True)
    img.save(out/'current.png'); (out/'source.sha256').write_text(B.source_digest(), encoding='utf-8')
    print(out/'current.png')


if __name__ == '__main__':
    birth_main() if '--birth' in sys.argv else brim_main() if '--brim' in sys.argv else clips_main() if '--clips' in sys.argv else paddle_main() if '--paddle' in sys.argv else places_main() if '--places' in sys.argv else main()
