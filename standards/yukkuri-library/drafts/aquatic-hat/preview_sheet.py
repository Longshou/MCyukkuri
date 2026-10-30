"""Contact sheet for the aquatic hat candidates against the current boat (offline CPU renders, not the game).

Columns: the current boat form (the land hat turned over at 1.4, crown pressed 0.55, riders by the current support
rule), then each candidate of build.py. Rows: worn front and 3/4, the empty hollow from above, and set down on water:
the owner aboard, the owner with two newborns, stores at three fills (one level layer), and night with the newborns at
the bottom and the owner leaning over them. Cut views remove the half of the hat toward the camera (side: from the
owner's right; front: from the bow), the cut cloth drawn flat. Bodies are the selected runtime Marisa posed with the
exported boat clips; a body stands on the hollow's height field (its real underside against the surface, the
candidates' proposed rule), newborns at growth 0.145 keep clear of the owner's body, hair and braid. Newborn insets
are cut from a render without the owner, their visible pixels outlined.

    python preview_sheet.py     # writes <MCY_LOCAL or .local>/previews/aquatic-hat/sheet.png and index.html
"""
from pathlib import Path
import importlib.util, json, math, os, sys
import numpy as np
from PIL import Image, ImageDraw, ImageEnhance

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import build as B
ROOT = B.ROOT
LORE = ROOT/'standards/yukkuri-library/motions/variants/lore'
spec = importlib.util.spec_from_file_location('lore_preview', LORE/'preview.py'); P = importlib.util.module_from_spec(spec); spec.loader.exec_module(P)
sys.path.insert(0, str(ROOT/'scripts')); import placement_fit
LOCAL = Path(os.environ.get('MCY_LOCAL', ROOT/'.local'))
OUT = LOCAL/'previews/aquatic-hat'
ASSETS = B.ASSETS
CORR = json.loads((ASSETS/'yukkuri_presentation/motion_corrections.json').read_text(encoding='utf-8'))
VESSEL = json.loads((ASSETS/'yukkuri_presentation/hat_vessel.json').read_text(encoding='utf-8'))
LIVING = json.loads((ASSETS/'yukkuri_presentation/living.json').read_text(encoding='utf-8'))
K = B.K
TOP = B.S['freeboard']*K           # the brim face over the water, world units
BABY = B.S['newborn_scale']
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


# ------------------------------------------------------------------ bodies
class Body(P.Posed):
    """The selected Marisa (placement fitted) with the boat clips; the worn hat hidden or drawn at a scale."""
    def __init__(self, hide_hat, hat_scale=1.):
        super().__init__('marisa', CORR, False, worn_hat_scale=hat_scale)
        model = placement_fit.fitted(ASSETS/'yukkuri/marisa.json', placement_fit.load())
        V = np.array(model['vertices']).reshape(-1, 8); Wt = np.array(model['weights'])
        hidden = tuple(LIVING['variants']['marisa']['accessory_prefixes']) if hide_hat else ()
        keep = [i for p in model['parts'] if not p.get('anatomy') and not (hidden and p['name'].startswith(hidden))
                for i in range(p['start'], p['start']+p['count'])]
        self.model = model; self.v, self.uv, self.n = V[keep, :3], V[keep, 3:5], V[keep, 5:8]
        self.a, self.b, self.q = Wt[keep, 0].astype(int), Wt[keep, 1].astype(int), Wt[keep, 2:3]
        self.body_rows = np.array([any(p['name'].startswith(('rounded_body', 'rounded_rump')) and p['start'] <= i < p['start']+p['count']
                                       for p in model['parts']) for i in keep])
        self.hat_bone = next(i for i, b in enumerate(model['bones']) if b['name'] == 'hat')

    def mesh(self, clip, t, atlas, face=None, hat=None):
        """Triangles (model units) of the posed body, its own hat (land) or `hat` (candidate worn mesh) on the hat bone."""
        c = self.clips[clip]; d = c['duration']; t = t % d if c['loop'] else min(d, max(0, t))
        face = face or self.face(clip, t)
        pos, uv, n = self.frame(clip, t)
        tris = np.concatenate((pos, uv, np.repeat(n[:, None, :], 3, axis=1)), axis=2)
        tris[:, :, 3:5] = atlas.uv(f'marisa/{face}', Image.fromarray(self.texture(face)), tris[:, :, 3:5])
        nn = tris[:, :, 5:8]; tris[:, :, 5:8] = nn/np.maximum(1e-9, np.linalg.norm(nn, axis=2, keepdims=True))
        body = self.body_rows.reshape(-1, 3)[:, 0]
        parts = [tris]
        if hat is not None:
            M = self.last_matrices[self.hat_bone]; h = hat.copy()
            h[:, :, :3] = h[:, :, :3]@M[:3, :3].T+M[:3, 3]
            nh = h[:, :, 5:8]@np.linalg.inv(M[:3, :3]); h[:, :, 5:8] = nh/np.maximum(1e-9, np.linalg.norm(nh, axis=2, keepdims=True))
            h[:, :, 3:5] = atlas.uv('marisa/neutral', Image.fromarray(self.texture('neutral')), h[:, :, 3:5])
            parts.append(h); body = np.r_[body, np.zeros(len(h), bool)]
        return np.concatenate(parts), body


def place(tris, at, yaw=0., scale=1.):
    out = tris.copy(); R = P.euler([0, yaw, 0])*scale
    out[:, :, :3] = tris[:, :, :3]@R.T+np.asarray(at, float)
    nn = tris[:, :, 5:8]@P.euler([0, yaw, 0]).T; out[:, :, 5:8] = nn
    return out


GRID = B.Grid(half=16., step=.125)


def runtime_support(field, x, z, half):
    """The current rule (HatGeometry.travelSupport): the highest surface over a 3 x 3 grid at the half width."""
    best = -np.inf
    for dx in (-1, 0, 1):
        for dz in (-1, 0, 1):
            i, j = GRID.index(x+dx*half, z+dz*half)
            if 0 <= i < GRID.n and 0 <= j < GRID.n and np.isfinite(field[i, j]): best = max(best, field[i, j])
    return best


# ------------------------------------------------------------------ the hulls
class Hull:
    def __init__(self, name):
        self.name = name
        if name == 'current':
            prop = json.loads((ASSETS/'yukkuri_props/marisa_hat_turned.json').read_text(encoding='utf-8'))
            V = np.array(prop['vertices'], float).reshape(-1, 3, 8); sq = VESSEL['squash']['bowl']
            V[:, :, :3] *= [K, K*sq, K]; V[:, :, 1] += TOP
            n = V[:, :, 5:8]/[1, sq, 1]; V[:, :, 5:8] = n/np.linalg.norm(n, axis=2, keepdims=True)
            self.uv_raw = V[:, :, 3:5].copy(); self.boat = V
            self.worn = None; self.label = '现版（陆生帽 1.4 倍倒扣）'; self.young_floor = None
            self.rule = 'runtime'
        else:
            c, worn, afloat, _ = B.build(name)
            self.boat = B.boat_world(afloat); self.uv_raw = self.boat[:, :, 3:5].copy(); self.worn = worn; self.young_floor = None
            self.label = c['label']; self.rule = 'underside'
            if c['kind'] == 'drum_pocket':
                self.young_floor = TOP-((B.BRIM['top']-B.BRIM['bottom'])+c['drum']['h']-B.W)*K-.3
        self.field = B.raster(self.boat, GRID, up=True); self.low = B.raster(self.boat, GRID, up=False)
        self.cavity = np.isfinite(self.field) & (self.field < TOP-1e-3)

    def mesh(self, atlas, img):
        m = self.boat.copy(); m[:, :, 3:5] = atlas.uv('marisa/neutral', img, self.uv_raw); return m

    def dry(self, x, z):
        i, j = GRID.index(x, z); ok = (i >= 0) & (i < GRID.n) & (j >= 0) & (j < GRID.n)
        out = np.zeros(x.shape, bool); ii, jj = np.clip(i, 0, GRID.n-1), np.clip(j, 0, GRID.n-1)
        out[ok] = (self.low[ii, jj] < 0)[ok]
        return out


def food_level(hull, portions):
    if not portions: return None
    return B.fill_level(hull.field, GRID, portions*B.portion_volume(), TOP)


def food_mesh(hull, level, atlas, food_img, cut=False):
    """The stores as one level layer: a textured top over every hollow cell below the level, and its cut face."""
    if level is None: return None
    sel = hull.cavity & (hull.field < level)
    if cut == 'x': sel &= GRID.x <= 0
    if cut == 'z': sel &= GRID.z >= 0
    ii, jj = np.nonzero(sel); s = GRID.step
    x0 = GRID.x[ii, jj]-s/2; z0 = GRID.z[ii, jj]-s/2; tile = 8.
    u0 = np.mod(x0, tile)/tile; v0 = np.mod(z0, tile)/tile; du = s/tile
    corners = [(0, 0), (1, 0), (1, 1), (0, 1)]
    quads = np.zeros((len(ii), 4, 8))
    for k, (a, b) in enumerate(corners):
        quads[:, k, 0] = x0+a*s; quads[:, k, 1] = level; quads[:, k, 2] = z0+b*s
        quads[:, k, 3] = u0+a*du; quads[:, k, 4] = v0+b*du; quads[:, k, 6] = 1
    tris = np.concatenate([quads[:, [0, 3, 2]], quads[:, [0, 2, 1]]])
    if cut == 'x':  # the cut face at x = 0, facing +x
        col = np.nonzero(sel[GRID.n//2-1])[0]; faces = []
        for j in col:
            z = GRID.z[GRID.n//2-1, j]; h = hull.field[GRID.n//2-1, j]
            q = np.array([[0, h, z-s/2], [0, h, z+s/2], [0, level, z+s/2], [0, level, z-s/2]])
            uv = np.array([[0, .9], [.06, .9], [.06, .9-(level-h)/tile], [0, .9-(level-h)/tile]])
            for a, b, c in ((0, 2, 1), (0, 3, 2)):
                faces.append(np.array([[*q[m], *uv[m], 1, 0, 0] for m in (a, b, c)]))
        if faces: tris = np.concatenate([tris, np.array(faces)])
    tris[:, :, 3:5] = atlas.uv('food', food_img, tris[:, :, 3:5])
    return tris


# ------------------------------------------------------------------ scenes
ADULT = Body(hide_hat=True, hat_scale=K)
LAND = Body(hide_hat=False, hat_scale=K)
FOOD_IMG = Image.open(HERE/'food_layer.png').convert('RGBA')


def body_tris(hull, clip, t, atlas, face=None, owner=False):
    """The owner aboard has no hat on (it is the boat); the young wear their own at their size."""
    if owner: return ADULT.mesh(clip, t, atlas, face)
    if hull.name == 'current': return LAND.mesh(clip, t, atlas, face)
    return ADULT.mesh(clip, t, atlas, face, hat=hull.worn)


class Shape:
    """A posed body's underside (body cloth only) and top maps, rasterized once at the origin and shifted by whole cells."""
    def __init__(self, tris, mask, yaw=0., scale=1.):
        self.tris = place(tris, (0, 0, 0), yaw, scale); self.mask = mask
        low = B.raster(self.tris[mask], GRID, up=False); top = B.raster(self.tris, GRID, up=True)
        under = B.raster(self.tris, GRID, up=False)
        ok = np.argwhere(np.isfinite(under) | np.isfinite(top)); (i0, j0), (i1, j1) = ok.min(0), ok.max(0)+1
        self.di, self.dj = i0-GRID.n//2, j0-GRID.n//2
        self.low, self.top, self.under = low[i0:i1, j0:j1], top[i0:i1, j0:j1], under[i0:i1, j0:j1]

    def window(self, x, z):
        si, sj = int(round(x/GRID.step)), int(round(z/GRID.step))
        i0, j0 = GRID.n//2+self.di+si, GRID.n//2+self.dj+sj
        return slice(i0, i0+self.low.shape[0]), slice(j0, j0+self.low.shape[1]), (si*GRID.step, sj*GRID.step)

    def fits(self, x, z):
        si, sj, _ = self.window(x, z)
        return si.start >= 0 and sj.start >= 0 and si.stop <= GRID.n and sj.stop <= GRID.n

    def rest(self, field, x, z):
        if not self.fits(x, z): return None
        si, sj, _ = self.window(x, z); f = field[si, sj]
        both = np.isfinite(f) & np.isfinite(self.low)
        return float((f[both]-self.low[both]).max()) if both.any() else None

    def full(self, which, x, z, y):
        out = np.full((GRID.n, GRID.n), -np.inf if which == 'top' else np.inf)
        si, sj, _ = self.window(x, z); out[si, sj] = getattr(self, which)+y
        return out

    def mesh(self, x, z, y):
        _, _, (sx, sz) = self.window(x, z); m = self.tris.copy(); m[:, :, 0] += sx; m[:, :, 2] += sz; m[:, :, 1] += y
        return m


def seat(hull, shape, at, stack=None, half=None):
    """y lift for a body at (x, z): the proposed underside rule, or the current 3 x 3 rule on the current hat."""
    field = hull.field if stack is None else np.fmax(hull.field, stack)
    if hull.rule == 'runtime':
        lift = runtime_support(field, at[0], at[1], half)
        extra = shape.rest(stack, *at) if stack is not None else None  # what lies under it (stores) still bears it
        return max(lift, extra if extra is not None else -np.inf)
    return shape.rest(field, *at)


def day_seat(hull, shape, stack):
    """The owner's day seat: the current stern seat, or the farthest toward the stern she sits without rising."""
    if hull.name == 'current': return (0., round((VESSEL['opening'][3]*K-5.9)/GRID.step)*GRID.step)
    base = seat(hull, shape, (0, 0), stack); e = 0.
    for step in np.arange(.25, 3.01, .25):
        y = seat(hull, shape, (0, step), stack)
        if y is None or y > base+.08: break
        e = float(step)
    return (0., e)


def turn_lift(hull):
    """How far the owner (resting pose) rises when she turns in place, at the centre and at her day seat."""
    atlas = Atlas(); o_tris, o_mask = body_tris(hull, 'boat_drift', .3, atlas, 'closed_smile', owner=True)
    shapes = [Shape(o_tris, o_mask, yaw) for yaw in range(0, 181, 15)]
    out = {}
    for key, at in (('centre', (0., 0.)), ('seat', day_seat(hull, shapes[0], None))):
        ys = [seat(hull, s, at, None, 6.8) for s in shapes]
        out[key] = round(max(ys)-ys[0], 2)
    return out


def compose(hull, kind, atlas, fill=None, cut=False, aboard=True):
    """Meshes, flags and readings for a situation. The owner rests on the hull (and the stores); by day the young sit
    clear of her, by night they lie under her leaning front only where her underside clears them."""
    meshes, flags, notes = [], [], {}
    hull_mesh = hull.mesh(atlas, Image.fromarray(ADULT.texture('neutral')))
    level = food_level(hull, fill) if fill else None
    stack = np.where(hull.cavity & (hull.field < level), level, -np.inf) if level is not None else None
    night = kind == 'night'
    owner_clip, owner_t, owner_face = ('boat_cover_young', .9, 'sleep') if night else ('boat_drift', .3, 'closed_smile')
    owner = Shape(*body_tris(hull, owner_clip, owner_t, atlas, owner_face, owner=True))
    o_at = day_seat(hull, owner, stack)
    o_y = seat(hull, owner, o_at, stack, 6.8)
    o = owner.mesh(*o_at, o_y); o_low = owner.full('under', *o_at, o_y); o_body = owner.full('low', *o_at, o_y)
    focus = []
    if kind in ('young', 'night'):
        b_clip, b_t, b_face = ('boat_nest_curl', .5, 'sleep') if night else ('boat_drift', 1.2, 'closed_smile')
        bt, bm = body_tris(hull, b_clip, b_t, atlas, b_face)
        rows = np.isfinite(o_low) & (np.abs(GRID.x-o_at[0]) < 3)
        z_front = float(GRID.z[rows].min())
        # pairs side by side across her front (their hats are about 4 wide), as the front cut shows them
        if hull.name == 'current':
            prefer = [(-1.8, 3.4), (1.8, 3.4)] if night else [(-2.2, -4.8), (2.2, -4.8)]
        elif night:  # under her leaning front
            prefer = [(o_at[0]-2.0, z_front+1.4), (o_at[0]+2.0, z_front+1.4)]
        else:  # by day in front of her
            prefer = [(o_at[0]-2.2, z_front-1.6), (o_at[0]+2.2, z_front-1.6)]
        taken = []; r_baby = 6.4*BABY+.32
        for px, pz in prefer:
            baby = Shape(bt, bm, (-20 if px >= o_at[0] else 20) if not night else (-35 if px >= o_at[0] else 35), BABY)
            best = None
            for dx in np.arange(-7, 7.01, .5):
                for dz in np.arange(-7, 7.01, .5):
                    x, z = round((px+dx)*2)/2, round((pz+dz)*2)/2
                    if not baby.fits(x, z): continue
                    y = seat(hull, baby, (x, z), stack, 6.4*BABY+.4)
                    if y is None or not np.isfinite(y): continue
                    if any(math.hypot(x-tx, z-tz) < 2*r_baby for tx, tz in taken): continue
                    si, sj, _ = baby.window(x, z)
                    if not hull.cavity[si, sj][np.isfinite(baby.low)].all(): continue  # in the hollow, never on the brim
                    if hull.young_floor is not None and y < hull.young_floor: continue  # the pouch holds stores, not the young
                    top = baby.top+y; ol = o_low[si, sj]; over = np.isfinite(ol) & np.isfinite(top)
                    if over.any() and (top[over] > ol[over]-.05).any(): continue  # under her (hair, braid) only with room
                    if not night and (np.isfinite(o_body[si, sj]) & np.isfinite(top)).any(): continue  # by day not under her body
                    score = math.hypot(x-px, z-pz)+.15*(y-TOP)
                    if best is None or score < best[0]: best = (score, x, z, y)
            if best is None: continue
            _, x, z, y = best; m = baby.mesh(x, z, y)
            taken.append((x, z)); meshes.append(m); flags.append(np.zeros(len(m), int))
            notes.setdefault('babies', []).append(round(TOP-y-float(np.nanmin(np.where(np.isfinite(baby.low), baby.low, np.nan))), 2))
            focus.append(m[:, :, :3].reshape(-1, 3).mean(0)); notes.setdefault('young', []).append(m)
            top = baby.full('top', x, z, y); over = np.isfinite(o_low) & np.isfinite(top)
            if over.any(): notes['clear'] = round(min(notes.get('clear', 99), float((o_low-top)[over].min())), 2)
    notes['owner_count'] = len(o) if aboard else 0
    if aboard:
        meshes.append(o); flags.append(np.zeros(len(o), int))
        notes['owner'] = round(TOP-o_y-float(np.nanmin(np.where(np.isfinite(owner.low), owner.low, np.nan))), 2)
        notes['stern'] = round(o_at[1], 2)
    if level is not None: notes['level'] = round(TOP-level, 2)
    h = clip(hull_mesh, cut) if cut else hull_mesh
    meshes.insert(0, h); flags.insert(0, np.ones(len(h), int) if cut else np.zeros(len(h), int))
    f = food_mesh(hull, level, atlas, FOOD_IMG, cut)
    if f is not None:
        meshes.append(f); flags.append(np.full(len(f), 2 if cut else 0))
    notes['focus'] = focus
    return np.concatenate(meshes), np.concatenate(flags), notes


def shot(tris, flags, atlas, view, hull=None, night=False, focus=(), owner=None, ghost=False, young=None, cut=False):
    """owner: (start, count) of the owner's triangles; the newborn inset is cut from a render without her, and with
    ghost she is drawn half transparent over what she covers."""
    az, el, span, center = view
    tex = np.array(atlas.image); cut = bool(cut)
    pixels, depth, cam = rasterize(tris, flags, tex, az, el, span, center)
    if hull is not None: pixels = water(pixels, depth, cam, hull.dry, cut=cut)
    alone = owner is not None and young is not None and len(young)
    if alone:
        keep = np.ones(len(tris), bool); keep[owner[0]:owner[0]+owner[1]] = False
        under, d2, _ = rasterize(tris[keep], flags[keep], tex, az, el, span, center)
        if hull is not None: under = water(under, d2, cam, hull.dry, cut=cut)
        if ghost: pixels = (pixels.astype(float)*.4+under.astype(float)*.6).astype(np.uint8)
    seen = None; base = under if alone else pixels; ref = d2 if alone else depth
    if young is not None and len(young):  # rim the young's visible pixels so the small dark-hatted bodies read
        yp, yd, _ = rasterize(young, np.zeros(len(young), int), tex, az, el, span, center)
        own = np.isfinite(yd); vis = own & (np.abs(np.where(own, yd, 0)-np.where(own, ref, 0)) < 1e-3)
        seen = vis.sum()/max(1, own.sum())
        grow = vis.copy()
        for dy, dx in ((1, 0), (-1, 0), (0, 1), (0, -1), (2, 0), (-2, 0), (0, 2), (0, -2)):
            grow |= np.roll(np.roll(vis, dy, 0), dx, 1)
        base = base.copy(); base[grow & ~vis] = (255, 236, 150)
    img = Image.fromarray(pixels); detail = Image.fromarray(base)
    if night:
        def dusk(im): return Image.fromarray(np.clip(np.array(im).astype(float)*[.52, .58, .82]+[6, 8, 22], 0, 255).astype(np.uint8))
        img, detail = dusk(img), dusk(detail)
    out = img.resize(CELL, Image.Resampling.LANCZOS)
    if len(focus):  # the newborns are small: a 2x inset of them, cut from the full render
        right, up, eye, scale, c = cam
        pts = np.array([[RW/2+(p-c)@right*scale, RH/2-(p-c)@up*scale] for p in focus]); m = pts.mean(0)
        w, h = (200, 140) if cut else (240, 170); x0 = int(np.clip(m[0]-w/2, 0, RW-w)); y0 = int(np.clip(m[1]-h/2, 0, RH-h))
        inset = ImageEnhance.Brightness(detail.crop((x0, y0, x0+w, y0+h))).enhance(1.6 if night else 1.15)
        frame = Image.new('RGB', (w+4, h+4), (255, 255, 255)); frame.paste(inset, (2, 2))
        px = 6 if cut else CELL[0]-w-8  # cut views: the open corner left of her head
        out.paste(frame, (px, 6))
        text = ('新生放大（不画帽主）' if alone else '新生放大') if seen is None or seen >= .12 else '新生放大：大部分被挡住'
        dr = ImageDraw.Draw(out); f = P.preview_font(12); box = dr.textbbox((px+4, 8), text, font=f)
        dr.rectangle((box[0]-2, box[1]-1, box[2]+2, box[3]+1), fill=(255, 255, 255)); dr.text((px+4, 8), text, font=f, fill='#203e48')
    return out


VIEWS = {
    'worn_front': (0., .06, 16., (0, 13.2, 0)),
    'worn_34': (.62, .32, 16., (0, 12.6, 0)),
    'boat_top': (.55, 1.05, 15.5, (0, 0., -.5)),
    'boat_34': (.55, .7, 13.5, (0, 1.8, -1.)),
    'boat_side': (math.pi/2, .10, 11., (0, 2.2, 0)),
    'boat_cut': (math.pi/2, .10, 10.8, (0, 1.4, -.5)),
    'boat_front_cut': (0., .10, 11.4, (0, 2.0, 0.)),
}


def worn_cell(hull, view, atlas):
    if hull.name == 'current':
        tris, _ = LAND.mesh('idle', 0, atlas, 'neutral')
    else:
        tris, _ = ADULT.mesh('idle', 0, atlas, 'neutral', hat=hull.worn)
    return shot(tris, np.zeros(len(tris), int), atlas, VIEWS[view])


def boat_cell(hull, kind, view, fill=None):
    atlas = Atlas(); cut = {'boat_cut': 'x', 'boat_front_cut': 'z'}.get(view)
    tris, flags, notes = compose(hull, kind, atlas, fill, cut, aboard=kind not in ('food', 'empty'))
    focus = notes.pop('focus'); young = notes.pop('young', None); n_owner = notes.pop('owner_count')
    young = np.concatenate(young) if young else None
    # by night from the bank the owner is drawn half transparent: the young she covers show through
    owner = (len(tris)-n_owner, n_owner) if n_owner and fill is None else None  # no stores aboard: she is drawn last
    if view == 'boat_cut' and kind == 'night': focus = ()  # this row shows her lean; the young are in the front cut
    return shot(tris, flags, atlas, VIEWS[view], hull, night=kind == 'night', focus=focus, owner=owner,
                ghost=kind == 'night' and not cut, young=young, cut=cut), notes


ROWS = [
    ('戴着\n正面', 'worn', 'worn_front', None),
    ('戴着\n3/4', 'worn', 'worn_34', None),
    ('放下：空帽\n俯视帽腔', 'empty', 'boat_top', None),
    ('(a) 帽主坐船\n3/4', 'owner', 'boat_34', None),
    ('(a) 帽主坐船\n侧视（外观）', 'owner', 'boat_side', None),
    ('(b) 帽主\n+ 两只新生\n3/4', 'young', 'boat_34', None),
    ('(b) 帽主\n+ 两只新生\n剖面正视', 'young', 'boat_front_cut', None),
    ('(c) 储粮少 6 份\n3/4（空帽）', 'food', 'boat_34', 'low'),
    ('(c) 储粮半 12 份\n3/4（空帽）', 'food', 'boat_34', 'half'),
    ('(c) 储粮满 24 份\n3/4（空帽）', 'food', 'boat_34', 'full'),
    ('(c) 储粮少/半/满\n+ 帽主\n剖面侧视', 'food3', 'boat_cut', None),
    ('(d) 夜：新生在\n帽底，帽主俯身\n3/4（帽主半透明）', 'night', 'boat_34', None),
    ('(d) 夜\n剖面正视', 'night', 'boat_front_cut', None),
    ('(d) 夜\n剖面侧视\n（看俯身）', 'night', 'boat_cut', None),
]


def note_text(kind, notes, fill):
    t = f"帽主身底低于帽檐面 {notes['owner']:.1f}（{notes['owner']/16:.2f} 格）" if 'owner' in notes else ('空帽（看粮层）' if fill else '空帽')
    if 'level' in notes: t += f" · 粮层面低于帽檐 {notes['level']:.1f}"
    if notes.get('babies'): t += ' · 新生身底 '+'/'.join(f'{b:.1f}' for b in notes['babies'])
    if kind == 'night' and notes.get('clear', 99) < 50: t += f" · 俯身离新生 {notes['clear']:.1f}"
    return t


def label(img, sub):
    d = ImageDraw.Draw(img); f = P.preview_font(13)
    if sub:
        d.rectangle((0, CELL[1]-20, CELL[0], CELL[1]), fill=(255, 255, 255)); d.text((5, CELL[1]-18), sub, font=f, fill='#36565f')
    return img


def food3(hull):
    parts = []
    for key in ('low', 'half', 'full'):
        atlas = Atlas(); n = B.S['portion']['fills'][key]
        tris, flags, notes = compose(hull, 'food', atlas, n, 'x'); notes.pop('focus'); notes.pop('owner_count')
        v = VIEWS['boat_cut']; img = rasterize(tris, flags, np.array(atlas.image), v[0], v[1], 8.5, (0, -.8, -.5))
        px = water(img[0], img[1], img[2], hull.dry, cut=True)
        im = Image.fromarray(px).crop((RW*.18, 0, RW*.82, RH)).resize((CELL[0]//3, CELL[1]-22), Image.Resampling.LANCZOS)
        parts.append((im, notes))
    cell = Image.new('RGB', CELL, BG); dr = ImageDraw.Draw(cell); f = P.preview_font(13)
    for i, (im, notes) in enumerate(parts):
        cell.paste(im, (i*(CELL[0]//3), 0))
        tag = ('少 6 份', '半 12 份', '满 24 份')[i]; box = dr.textbbox((i*(CELL[0]//3)+4, 4), tag, font=f)
        dr.rectangle((box[0]-2, box[1]-1, box[2]+2, box[3]+1), fill=(255, 255, 255)); dr.text((i*(CELL[0]//3)+4, 4), tag, font=f, fill='#203e48')
    sub = '粮层面低于帽檐 '+' / '.join(f"{n['level']:.1f}" for _, n in parts)+' · 帽主 '+' / '.join(f"{n['owner']:.1f}" for _, n in parts)
    return label(cell, sub), parts


TITLES = {'current': '现版：陆生帽倒扣（基线）', 'cone': 'A 尖锥帽', 'basin': 'B 圆冠盆', 'pocket': 'C 筒帽兜'}


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    columns = [Hull('current')]+[Hull(n) for n in B.S['candidates']]
    head, left = 64, 170
    sheet = Image.new('RGB', (left+len(columns)*CELL[0], head+len(ROWS)*CELL[1]), BG); d = ImageDraw.Draw(sheet)
    titles = TITLES
    for j, h in enumerate(columns):
        d.text((left+j*CELL[0]+8, 10), titles[h.name], font=P.preview_font(22), fill='#203e48')
        d.text((left+j*CELL[0]+8, 40), '现行支撑规则（3×3 方格）' if h.rule == 'runtime' else '建议规则：身体底面贴帽面高度场', font=P.preview_font(13), fill='#4b6b74')
    report = {}
    for i, (title, kind, view, fill) in enumerate(ROWS):
        y = head+i*CELL[1]
        d.text((8, y+10), title, font=P.preview_font(15), fill='#203e48')
        for j, h in enumerate(columns):
            if kind == 'worn':
                img = worn_cell(h, view, Atlas()); sub = None
            elif kind == 'food3':
                img, parts = food3(h); sub = None
                report.setdefault(h.name, {})['food'] = [n for _, n in parts]
            else:
                n = B.S['portion']['fills'][fill] if fill else None
                img, notes = boat_cell(h, kind, view, n); sub = note_text(kind, notes, fill)
                report.setdefault(h.name, {})[f'{kind}:{view}:{fill}'] = notes
            if sub: label(img, sub)
            sheet.paste(img, (left+j*CELL[0], y))
            d.rectangle((left+j*CELL[0], y, left+(j+1)*CELL[0]-1, y+CELL[1]-1), outline=(200, 214, 218))
        print(title, flush=True)
    sheet.save(OUT/'sheet.png')
    for h in columns: report[h.name]['turn'] = turn_lift(h)
    (OUT/'report.json').write_text(json.dumps(report, ensure_ascii=False, indent=1), encoding='utf-8')
    page(columns, titles, report)
    print(OUT/'sheet.png')


def page(columns, titles, report):
    """index.html: the sheet and one table of the readings behind it (world units, 16 a block)."""
    def f(v): return '–' if v is None else f'{v:.1f}'
    rows = []
    for h in columns:
        r = report[h.name]; food = r['food']
        geo = None if h.name == 'current' else json.loads((HERE/h.name/'geometry.json').read_text(encoding='utf-8'))
        day, night = r['young:boat_front_cut:None'], r['night:boat_front_cut:None']
        rows.append('<tr>'+''.join(f'<td>{x}</td>' for x in (
            titles[h.name],
            f(geo['opening_radius']*2/16*16)+' / '+f'{geo["opening_radius"]*2/16:.2f} 格' if geo else '帽口 12.2×16.8',
            f(geo['hollow_depth']) if geo else f'{VESSEL["floor"]*VESSEL["squash"]["bowl"]*K:.1f}',
            f(r['owner:boat_side:None']['owner']),
            '/'.join(f(b) for b in day.get('babies', [])),
            '/'.join(f(b) for b in night.get('babies', []))+f" · 离身 {f(night.get('clear'))}",
            ' / '.join(f(x['level']) for x in food), ' / '.join(f(x['owner']) for x in food),
            f"{r['turn']['centre']:.2f} / {r['turn']['seat']:.2f}",
            f(-float(np.nanmin(np.where(np.isfinite(h.low), h.low, np.nan))))))+'</tr>')
    head = ('方案', '帽口直径', '帽腔最深', '帽主身底深', '日间新生身底', '夜间新生身底 · 帽主俯身离新生', '粮层面 少/半/满',
            '帽主身底 少/半/满', '原地转身抬升 中央/座位', '水下最深')
    html = ('<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>水生魔理沙帽子候选</title>'
            '<body style="margin:16px;background:#ecf5f6;font-family:\'Microsoft YaHei\',sans-serif;color:#203e48">'
            '<p><a href="../index.html">预览总入口</a></p><h2>水生魔理沙帽子：三个候选与现版对照</h2>'
            '<p>离线 CPU 渲染，非游戏画面；候选未导出到运行资源。帽子统一 1.4 倍（相对陆生帽），候选共用圆帽檐与帽冠底，只改帽冠和倒扣后的帽腔。'
            '现版列按现行支撑规则（3×3 方格）摆放；候选列按建议规则：身体真实底面贴帽面高度场。新生为 0.145 倍。'
            '储粮按“一份占现有画法的空间（半径 0.09 格、高 0.06 格）”铺成一层；3/4 储粮行为空帽，剖面行带帽主看抬升。'
            '剖面视图去掉靠镜头的半顶帽子（侧视从帽主右侧、正视从船首），浅紫色为布料剖面；夜间 3/4 把帽主画成半透明，好看见她盖住的新生。帽檐外缘戴着时下垂，放下后翘起（EH22-001）。</p>'
            '<table border="1" cellpadding="4" style="border-collapse:collapse;background:#fff;font-size:13px"><tr>'
            +''.join(f'<th>{x}</th>' for x in head)+'</tr>'+''.join(rows)+'</table>'
            '<p style="font-size:13px">数值为成体模型单位（16 = 1 格），均为“低于帽檐面”的深度（负数在帽檐面以上）。'
            '辫子托兜在 braid_cradle_lift 中最低只到身底以上约 0.75，最远离辫根 9.9：夜里新生身底若比帽主早晨座位深很多，辫子够不到。'
            '设计说明见 standards/yukkuri-library/drafts/aquatic-hat/README.md。</p>'
            '<img src="sheet.png" style="max-width:100%"></body></html>\n')
    (OUT/'index.html').write_text(html, encoding='utf-8')


if __name__ == '__main__':
    main()
