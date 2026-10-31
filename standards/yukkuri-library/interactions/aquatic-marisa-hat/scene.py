"""Bodies, footprints, support, stores and hydrostatics for the aquatic hat (offline; no game).

World units are adult model units at growth 1 (16 a block); the set-down hat's frame has its origin at the middle of the
brim face (y 0), +Y out of the hollow, the boat's bow toward -Z. Bodies are the selected runtime Marisa posed with the
exported clips (placement fitted). A body rests on the hat by its real underside: the highest (surface - underside) over
the cells under it. Masses are block³ of water: a body as the round ball of the WaterDistress density, a stored portion as
a lump of the same density, the cloth hull as hat_vessel.json afloat.hull_mass per scale².
"""
from pathlib import Path
import importlib.util, json, math, os, sys
import numpy as np
from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import build as B
ROOT = B.ROOT
ASSETS = B.ASSETS
LORE = ROOT/'standards/yukkuri-library/motions/variants/lore'
spec = importlib.util.spec_from_file_location('lore_preview', LORE/'preview.py'); P = importlib.util.module_from_spec(spec); spec.loader.exec_module(P)
sys.path.insert(0, str(ROOT/'scripts')); import placement_fit
CORR = json.loads((ASSETS/'yukkuri_presentation/motion_corrections.json').read_text(encoding='utf-8'))
LIVING = json.loads((ASSETS/'yukkuri_presentation/living.json').read_text(encoding='utf-8'))
VESSEL = json.loads((ASSETS/'yukkuri_presentation/hat_vessel.json').read_text(encoding='utf-8'))
BRAID = json.loads((ASSETS/'yukkuri_presentation/braid_rescue.json').read_text(encoding='utf-8'))
K = B.K
GRID = B.Grid(half=16., step=.125)
DENSITY = .56*.56*(3-2*.56)                      # Buoyancy.BODY_DENSITY
HULL_MASS = VESSEL['afloat']['hull_mass']*K*K     # block³
FOOT, MARGIN = .5, .02                           # Occupancy.foot = FOOT * bbWidth + MARGIN (blocks)
ADULT_W, ADULT_H = 12.8, 9.6                     # body_geometry.json marisa bounds (model units at growth 1)
SWELL, LOW_WIDE, LOW_DEEP, LOW_FRONT = .26, .16, .06, .28   # LiveBirthGeometry


def foot(scale, belly=0.):
    """Occupancy.foot in world units for a body of this growth (a pregnant one by LiveBirthGeometry.footprint)."""
    w = ADULT_W/16*scale
    if belly: w *= (1+SWELL*belly)*(1+(LOW_DEEP+LOW_FRONT)*belly)
    return (FOOT*w+MARGIN)*16


def mass(scale, belly=0.):
    """A body's mass (block³ of water): a mother as her own body only (her unborn young are added by the caller)."""
    w, h = ADULT_W/16*scale, ADULT_H/16*scale
    return math.pi/6*w*w*h*DENSITY


def smooth(v):
    q = min(max(v, 0.), 1.); return q*q*(3-2*q)


def deform(v, belly):
    """LiveBirthGeometry.deform, model units (contraction 0)."""
    if belly <= 0: return v
    out = v.copy(); s = 1+SWELL*belly
    y = v[..., 1]; z = v[..., 2]
    low = np.vectorize(smooth)(y/1.5)*(1-np.vectorize(smooth)((y-1.5)/4.5))
    front = np.where(z < 0, np.vectorize(smooth)(-z/4), 0)
    out[..., 0] = v[..., 0]*s*(1+LOW_WIDE*belly*low)
    out[..., 1] = y*s
    out[..., 2] = z*s*(1+(LOW_DEEP+LOW_FRONT*front)*belly*low)
    return out


# ------------------------------------------------------------------ bodies
class Body(P.Posed):
    """The selected Marisa with the exported clips; her own (land) hat hidden; the aquatic hat drawn on the hat bone."""
    def __init__(self):
        super().__init__('marisa', CORR, False, worn_hat_scale=K)
        model = placement_fit.fitted(ASSETS/'yukkuri/marisa.json', placement_fit.load())
        V = np.array(model['vertices']).reshape(-1, 8); Wt = np.array(model['weights'])
        hidden = tuple(LIVING['variants']['marisa']['accessory_prefixes'])
        keep = [i for p in model['parts'] if not p.get('anatomy') and not p['name'].startswith(hidden)
                for i in range(p['start'], p['start']+p['count'])]
        self.model = model; self.v, self.uv, self.n = V[keep, :3], V[keep, 3:5], V[keep, 5:8]
        self.a, self.b, self.q = Wt[keep, 0].astype(int), Wt[keep, 1].astype(int), Wt[keep, 2:3]
        names = {p['name']: p for p in model['parts']}
        self.body_rows = np.array([any(p['name'].startswith(('rounded_body', 'rounded_rump')) and p['start'] <= i < p['start']+p['count']
                                       for p in model['parts']) for i in keep])
        self.bones = {b['name']: i for i, b in enumerate(model['bones'])}

    def mesh(self, clip, t, atlas=None, face=None, hat=None, belly=0.):
        """Triangles (model units, growth 1) of the posed body and, given, a worn hat mesh on the hat bone; and which
        triangles are body cloth (the underside a body rests on)."""
        c = self.clips[clip]; d = c['duration']; t = t % d if c['loop'] else min(d, max(0, t))
        face = face or self.face(clip, t)
        pos, uv, n = self.frame(clip, t)
        if belly: pos = deform(pos, belly)
        tris = np.concatenate((pos, uv, np.repeat(n[:, None, :], 3, axis=1)), axis=2)
        if atlas is not None: tris[:, :, 3:5] = atlas.uv(f'marisa/{face}', Image.fromarray(self.texture(face)), tris[:, :, 3:5])
        nn = tris[:, :, 5:8]; tris[:, :, 5:8] = nn/np.maximum(1e-9, np.linalg.norm(nn, axis=2, keepdims=True))
        body = self.body_rows.reshape(-1, 3)[:, 0]
        parts = [tris]
        if hat is not None:
            M = self.last_matrices[self.bones['hat']]; h = hat.copy()
            h[:, :, :3] = h[:, :, :3]@M[:3, :3].T+M[:3, 3]
            nh = h[:, :, 5:8]@np.linalg.inv(M[:3, :3]); h[:, :, 5:8] = nh/np.maximum(1e-9, np.linalg.norm(nh, axis=2, keepdims=True))
            if atlas is not None: h[:, :, 3:5] = atlas.uv('marisa/neutral', Image.fromarray(self.texture('neutral')), h[:, :, 3:5])
            parts.append(h); body = np.r_[body, np.zeros(len(h), bool)]
        return np.concatenate(parts), body

    def braid_root(self):
        """The braid's root (model units, growth 1) in the last posed frame."""
        return np.array(BRAID['braid_root'], float)


BODY = Body()
WORN = B.worn_mesh()


def place(tris, at, yaw=0., scale=1.):
    out = tris.copy(); R = P.euler([0, yaw, 0])
    out[:, :, :3] = tris[:, :, :3]@(R*scale).T+np.asarray(at, float)
    out[:, :, 5:8] = tris[:, :, 5:8]@R.T
    return out


class Shape:
    """A posed body's underside (body cloth only), whole underside (hair and braid too) and top, rasterized once at the
    origin and shifted by whole cells."""
    def __init__(self, tris, mask, yaw=0., scale=1.):
        self.tris = place(tris, (0, 0, 0), yaw, scale); self.mask = mask; self.scale = scale; self.yaw = yaw
        low = B.raster(self.tris[mask], GRID, up=False); top = B.raster(self.tris, GRID, up=True)
        under = B.raster(self.tris, GRID, up=False)
        ok = np.argwhere(np.isfinite(under) | np.isfinite(top)); (i0, j0), (i1, j1) = ok.min(0), ok.max(0)+1
        self.di, self.dj = i0-GRID.n//2, j0-GRID.n//2
        btop = B.raster(self.tris[mask], GRID, up=True)
        self.low, self.top, self.under = low[i0:i1, j0:j1], top[i0:i1, j0:j1], under[i0:i1, j0:j1]
        self.btop = btop[i0:i1, j0:j1]                            # the body cloth's own top (no hat, hair or braid)
        self.bottom = float(np.nanmin(np.where(np.isfinite(self.low), self.low, np.nan)))
        self.height = float(np.nanmax(np.where(np.isfinite(self.top), self.top, np.nan)))-self.bottom
        body = self.tris[mask][:, :, :3].reshape(-1, 3)
        self.cg = body.mean(0)                                   # the body cloth's centre (origin-relative)
        self.tall = float(body[:, 1].max()-body[:, 1].min())     # the body's own height (bbHeight; hats do not count)

    def window(self, x, z):
        si, sj = int(round(x/GRID.step)), int(round(z/GRID.step))
        i0, j0 = GRID.n//2+self.di+si, GRID.n//2+self.dj+sj
        return slice(i0, i0+self.low.shape[0]), slice(j0, j0+self.low.shape[1]), (si*GRID.step, sj*GRID.step)

    def fits(self, x, z):
        si, sj, _ = self.window(x, z)
        return si.start >= 0 and sj.start >= 0 and si.stop <= GRID.n and sj.stop <= GRID.n

    def rest(self, field, x, z):
        """The lift that sets the body's underside on the field at x, z (the support rule), or None off the field."""
        if not self.fits(x, z): return None
        si, sj, _ = self.window(x, z); f = field[si, sj]
        both = np.isfinite(f) & np.isfinite(self.low)
        if not both.any() or not np.isfinite(self.low).sum() == both.sum(): return None
        return float((f[both]-self.low[both]).max())

    def full(self, which, x, z, y):
        out = np.full((GRID.n, GRID.n), -np.inf if which == 'top' else np.inf)
        si, sj, _ = self.window(x, z); out[si, sj] = getattr(self, which)+y
        return out

    def mesh(self, x, z, y):
        _, _, (sx, sz) = self.window(x, z); m = self.tris.copy(); m[:, :, 0] += sx; m[:, :, 2] += sz; m[:, :, 1] += y
        return m


# ------------------------------------------------------------------ the set-down hat
class Hull:
    """A set-down hull as drawn (world units): this make-source's by default, or another hull's triangles (placed), the
    height of its rim (dam: water over it floods the hollow) and its opening (inside(x, z))."""
    def __init__(self, placed=None, dam=None, inside=None):
        self.placed = B.world(B.placed_mesh()) if placed is None else placed
        self.field = B.raster(self.placed, GRID, True)          # what a body stands on
        self.low = B.raster(self.placed, GRID, False)           # the outer bottom
        self.dam = B.BRIM['droop']*K if dam is None else dam   # the turned-up rim
        self.inside = inside or (lambda x, z: np.hypot(x, z) < B.IN['opening']*K)
        self.cavity = np.isfinite(self.field) & self.inside(GRID.x, GRID.z) & (self.field < -1e-3)
        # the dry hull's top: the brim where there is cloth, up to the rim's top over the hollow until it floods
        self.top = np.where(self.inside(GRID.x, GRID.z), self.dam, self.field)
        self.rim_r = float(np.hypot(self.placed[:, :, 0], self.placed[:, :, 2]).max())

    def stores(self, portions):
        """The layer's level (y) for this many portions, or None."""
        if not portions: return None
        return B.fill_level(self.field, GRID, portions*B.portion_volume())

    def surface(self, level):
        """The stand field with the stores layer (its level over every hollow cell below it)."""
        if level is None: return self.field
        return np.where(self.cavity & (self.field < level), level, self.field)

    def layer_volume(self, level):
        if level is None: return 0.
        h = self.field[self.cavity]; return float(np.clip(level-h, 0, None).sum())*GRID.step**2


# ------------------------------------------------------------------ hydrostatics (blocks)
def hydro(hull, loads, max_iter=80):
    """Static equilibrium of the set-down hat on calm water: the plane y = h + a x + b z (hull frame, blocks) whose
    displaced volume (outer bottom up to the plane, capped by the dry top) carries every load, with the centre of buoyancy
    straight under the centre of mass. loads: (mass, x, y, z) in world units for position. Returns a dict: heel (degrees,
    roll about z then pitch about x), the waterline at the middle, the least freeboard round the rim and where, and
    whether it floods (no upright equilibrium short of the rim going under)."""
    s = 1/16; cell = (GRID.step*s)**2
    bot = np.where(np.isfinite(hull.low), hull.low*s, np.nan); top = np.where(np.isfinite(hull.top), hull.top*s, np.nan)
    ok = np.isfinite(bot) & np.isfinite(top)
    X, Z, Bt, Tp = GRID.x[ok]*s, GRID.z[ok]*s, bot[ok], top[ok]
    m = HULL_MASS+sum(w for w, *_ in loads)
    G = (sum(w*np.array(p) for w, *p in loads)*s+HULL_MASS*np.array([0, -1.5*s, 0]))/m

    def disp(h, a, b):
        w = h+a*X+b*Z; col = np.clip(np.minimum(w, Tp)-Bt, 0, None); v = col.sum()*cell
        if v <= 0: return 0, np.zeros(3)
        cy = Bt+col/2
        return v, np.array([(col*X).sum(), (col*cy).sum(), (col*Z).sum()])*cell/v

    def level(a, b):
        lo, hi = np.nanmin(Bt)-1, np.nanmax(Tp)+1
        for _ in range(50):
            mid = (lo+hi)/2
            if disp(mid, a, b)[0] < m: lo = mid
            else: hi = mid
        return (lo+hi)/2

    a = b = 0.
    for _ in range(max_iter):
        h = level(a, b); v, Bc = disp(h, a, b)
        # in the hull frame the world vertical is the plane's normal (-a, 1, -b): B - G must lie along it
        ex = (Bc[0]-G[0])+a*(Bc[1]-G[1]); ez = (Bc[2]-G[2])+b*(Bc[1]-G[1])
        if abs(ex) < 1e-6 and abs(ez) < 1e-6: break
        a -= .8*ex; b -= .8*ez
        if abs(a) > 1.2 or abs(b) > 1.2: break
    h = level(a, b)
    rim = []
    for i in range(48):
        ang = i*2*math.pi/48; x, z = math.cos(ang)*hull.rim_r*s, math.sin(ang)*hull.rim_r*s
        rim.append((hull.dam*s-(h+a*x+b*z), ang))
    least, at = min(rim)
    heel = math.degrees(math.atan(math.hypot(a, b)))
    return dict(heel=round(heel, 2), roll=round(math.degrees(math.atan(a)), 2), pitch=round(math.degrees(math.atan(b)), 2),
                waterline=round(h*16, 2), freeboard=round(least*16, 2), toward=round(math.degrees(at)), mass=round(m, 4),
                floods=least <= 0 or abs(a) > 1.2 or abs(b) > 1.2, G=(G*16).round(2).tolist())


def flood_angle(hull, loads, direction):
    """The heel (degrees) toward a horizontal direction (radians, hull frame) at which the rim first meets the water, the
    hat holding the same displaced volume: how far a load's static heel is from turning it over."""
    s = 1/16; cell = (GRID.step*s)**2
    bot = np.where(np.isfinite(hull.low), hull.low*s, np.nan); top = np.where(np.isfinite(hull.top), hull.top*s, np.nan)
    ok = np.isfinite(bot) & np.isfinite(top)
    X, Z, Bt, Tp = GRID.x[ok]*s, GRID.z[ok]*s, bot[ok], top[ok]
    m = HULL_MASS+sum(w for w, *_ in loads)
    cx, cz = math.cos(direction), math.sin(direction)
    for deg in np.arange(0, 60, .25):
        t = math.tan(math.radians(deg)); a, b = t*cx, t*cz
        lo, hi = np.nanmin(Bt)-1, np.nanmax(Tp)+1
        for _ in range(40):
            mid = (lo+hi)/2; w = mid+a*X+b*Z
            if np.clip(np.minimum(w, Tp)-Bt, 0, None).sum()*cell < m: lo = mid
            else: hi = mid
        h = (lo+hi)/2; r = hull.rim_r*s
        if hull.dam*s-(h+a*r*cx+b*r*cz) <= 0: return float(deg)
    return 60.


# ------------------------------------------------------------------ the braid's reach (morning lift)
CRADLE = CORR['models']['marisa']['clips']['braid_cradle_lift']


def braid_envelope():
    """The scoop point of braid_cradle_lift relative to the braid root over the clip (model units, growth 1): its farthest
    reach from the root and its lowest height above her bottom."""
    c = CRADLE['contacts']['braid_cradle']; root = np.array(BRAID['braid_root'], float); far, low = 0., 99.
    for t in np.linspace(0, CRADLE['duration'], 49):
        BODY.frame('braid_cradle_lift', t)
        p = (BODY.last_matrices[BODY.bones[c['bone']]]@np.r_[c['point'], 1])[:3]
        far = max(far, float(np.linalg.norm(p-root))); low = min(low, float(p[1]))
    return far, low


REACH, REACH_LOW = braid_envelope()


def braid_reaches(owner_at, owner_scale, owner_bottom, young_centre):
    """The owner, turning in place at her seat, brings her braid's scoop (the clip's own envelope, at her size) to a young's
    centre: within its farthest reach from the braid root and no lower than the scoop goes below her bottom. Returns
    (reaches, distance from the root at the best turn)."""
    root = np.array(BRAID['braid_root'], float)*owner_scale; best = 1e9
    for yaw in range(0, 360, 10):
        rr = P.euler([0, yaw, 0])@root
        p = np.array([owner_at[0]+rr[0], owner_bottom+rr[1], owner_at[1]+rr[2]])
        best = min(best, float(np.linalg.norm(p-young_centre)))
    low_ok = young_centre[1] >= owner_bottom+REACH_LOW*owner_scale-1.0*owner_scale
    return best <= REACH*owner_scale and low_ok, best
