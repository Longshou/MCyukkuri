"""Bodies, support and the braid's reach for the aquatic hat (offline; no game).

World units are adult model units at growth 1 (16 a block); the set-down hat's frame has its origin at the middle of the
brim face (y 0), +Y out of the hollow, the boat's bow toward -Z. Bodies are the selected runtime Marisa posed with the
exported clips (placement fitted). A body rests on the hat by its real underside: the highest (surface - underside) over
the cells under it. Posed bodies and the braid's reach are cached under <MCY_LOCAL or .local>/cache/aquatic-marisa-hat,
keyed by every input they read (the hat recipe in memory included), so repeated studies skip the posing.
"""
from pathlib import Path
import hashlib, importlib.util, json, os, sys
import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import build as B
ROOT = B.ROOT
ASSETS = B.ASSETS
LORE = ROOT/'standards/yukkuri-library/motions/variants/lore'
spec = importlib.util.spec_from_file_location('lore_preview', LORE/'preview.py'); P = importlib.util.module_from_spec(spec); spec.loader.exec_module(P)
sys.path.insert(0, str(ROOT/'scripts')); import placement_fit
CORR_PATH = ASSETS/'yukkuri_presentation/motion_corrections.json'
LIVING = json.loads((ASSETS/'yukkuri_presentation/living.json').read_text(encoding='utf-8'))
BRAID = json.loads((ASSETS/'yukkuri_presentation/braid_rescue.json').read_text(encoding='utf-8'))
GRID = B.Grid(half=22., step=.125)   # fixed, so cached shapes stay valid under recipe overrides
CACHE = Path(os.environ.get('MCY_LOCAL', ROOT/'.local'))/'cache/aquatic-marisa-hat'
INPUTS = hashlib.sha256(b''.join((ASSETS/n).read_bytes() for n in (
    'yukkuri/marisa.json', 'yukkuri_presentation/motion_corrections.json', 'yukkuri_presentation/placement_fit.json',
    'yukkuri_presentation/living.json', 'yukkuri_presentation/braid_rescue.json'))+(LORE/'preview.py').read_bytes()
    + Path(__file__).read_bytes()).hexdigest()[:16]


# ------------------------------------------------------------------ bodies
class Body(P.Posed):
    """The selected Marisa with the exported clips; her own (land) hat hidden; the aquatic hat drawn on the hat bone."""
    def __init__(self):
        super().__init__('marisa', json.loads(CORR_PATH.read_text(encoding='utf-8')), False, worn_hat_scale=B.K)
        model = placement_fit.fitted(ASSETS/'yukkuri/marisa.json', placement_fit.load())
        V = np.array(model['vertices']).reshape(-1, 8); Wt = np.array(model['weights'])
        hidden = tuple(LIVING['variants']['marisa']['accessory_prefixes'])
        keep = [i for p in model['parts'] if not p.get('anatomy') and not p['name'].startswith(hidden)
                for i in range(p['start'], p['start']+p['count'])]
        self.model = model; self.v, self.uv, self.n = V[keep, :3], V[keep, 3:5], V[keep, 5:8]
        self.a, self.b, self.q = Wt[keep, 0].astype(int), Wt[keep, 1].astype(int), Wt[keep, 2:3]
        self.body_rows = np.array([any(p['name'].startswith(('rounded_body', 'rounded_rump')) and p['start'] <= i < p['start']+p['count']
                                       for p in model['parts']) for i in keep])
        self.braid_rows = np.array([any(p['name'].startswith('braid_lock_') and p['start'] <= i < p['start']+p['count']
                                        for p in model['parts']) for i in keep])
        self.bones = {b['name']: i for i, b in enumerate(model['bones'])}

    def mesh(self, clip, t, face=None, hat=None):
        """Triangles (model units, growth 1) of the posed body and, given, a worn hat mesh on the hat bone; and per
        triangle 1 for body cloth (the underside a body rests on), 2 for the braid, 0 for the rest."""
        c = self.clips[clip]; d = c['duration']; t = t % d if c['loop'] else min(d, max(0, t))
        pos, uv, n = self.frame(clip, t)
        tris = np.concatenate((pos, uv, np.repeat(n[:, None, :], 3, axis=1)), axis=2)
        nn = tris[:, :, 5:8]; tris[:, :, 5:8] = nn/np.maximum(1e-9, np.linalg.norm(nn, axis=2, keepdims=True))
        body = self.body_rows.reshape(-1, 3)[:, 0]+2*self.braid_rows.reshape(-1, 3)[:, 0]; parts = [tris]
        if hat is not None:
            M = self.last_matrices[self.bones['hat']]; h = hat.copy()
            h[:, :, :3] = h[:, :, :3]@M[:3, :3].T+M[:3, 3]
            nh = h[:, :, 5:8]@np.linalg.inv(M[:3, :3]); h[:, :, 5:8] = nh/np.maximum(1e-9, np.linalg.norm(nh, axis=2, keepdims=True))
            parts.append(h); body = np.r_[body, np.zeros(len(h), int)]
        return np.concatenate(parts), body


_BODY, _POSES = [], {}


def smooth(v): q = np.clip(v, 0., 1.); return q*q*(3-2*q)


def deform(P, belly, squeeze=0.):
    """LiveBirthGeometry.deform on model-unit points (n, 3): the full belly swells the body from the floor, its lower
    front most (SWELL .26, LOW_WIDE .16, LOW_DEEP .06, LOW_FRONT .28); squeeze is the contraction."""
    x, y, z = P[:, 0], P[:, 1], P[:, 2]; s = 1+.26*belly
    low = smooth(y/1.5)*(1-smooth((y-1.5)/4.5)); front = np.where(z < 0, smooth(-z/4), 0.); q = 1+.035*squeeze
    return np.stack([x*s*(1+.16*belly*low)*q, y*s*(1-.04*squeeze), z*s*(1+(.06+.28*front)*belly*low)*q], axis=1)


def pregnant(tris, kinds, belly, squeeze=0.):
    """A posed mesh as YukkuriModel draws it at this belly: the body and hair deform, the braid rides its root's lift."""
    out = tris.copy(); P = out[:, :, :3].reshape(-1, 3); braid = np.repeat(kinds == 2, 3)
    who = body(); root = np.array(who.model['bones'][who.bones['hand_right']]['pivot'], float)
    P[~braid] = deform(P[~braid], belly, squeeze); P[braid] += deform(root[None], belly)[0]-root
    out[:, :, :3] = P.reshape(-1, 3, 3); return out


def body():
    """The posed Marisa (built on first use: posing is the slow part, cached shapes skip it)."""
    if not _BODY: _BODY.append(Body())
    return _BODY[0]


def place(tris, at, yaw=0., scale=1.):
    out = tris.copy(); R = P.euler([0, yaw, 0])
    out[:, :, :3] = tris[:, :, :3]@(R*scale).T+np.asarray(at, float)
    out[:, :, 5:8] = tris[:, :, 5:8]@R.T
    return out


class Shape:
    """A posed body's underside (body cloth only), whole underside (hair, hat and braid too), firm underside (all but the
    braid, which drapes) and top, rasterized once at the origin on GRID and shifted by whole cells; `face` is the
    expression its triangles' uvs belong to. kinds: per triangle 1 body cloth, 2 braid, 0 the rest (Body.mesh)."""
    FIELDS = ('tris', 'mask', 'low', 'top', 'under', 'firm', 'btop', 'cg', 'scale', 'yaw', 'di', 'dj', 'bottom', 'height', 'tall')

    def __init__(self, tris, kinds, yaw=0., scale=1.):
        self.tris = place(tris, (0, 0, 0), yaw, scale); self.mask = mask = kinds == 1; self.scale = scale; self.yaw = yaw
        low = B.raster(self.tris[mask], GRID, up=False); top = B.raster(self.tris, GRID, up=True)
        under = B.raster(self.tris, GRID, up=False); firm = B.raster(self.tris[kinds != 2], GRID, up=False)
        ok = np.argwhere(np.isfinite(under) | np.isfinite(top)); (i0, j0), (i1, j1) = ok.min(0), ok.max(0)+1
        self.di, self.dj = i0-GRID.n//2, j0-GRID.n//2
        btop = B.raster(self.tris[mask], GRID, up=True)
        self.low, self.top, self.under, self.firm = low[i0:i1, j0:j1], top[i0:i1, j0:j1], under[i0:i1, j0:j1], firm[i0:i1, j0:j1]
        self.btop = btop[i0:i1, j0:j1]                            # the body cloth's own top (no hat, hair or braid)
        self.bottom = float(np.nanmin(np.where(np.isfinite(self.low), self.low, np.nan)))
        self.height = float(np.nanmax(np.where(np.isfinite(self.top), self.top, np.nan)))-self.bottom
        body_pts = self.tris[mask][:, :, :3].reshape(-1, 3)
        self.cg = body_pts.mean(0)                                # the body cloth's centre (origin-relative)
        self.tall = float(body_pts[:, 1].max()-body_pts[:, 1].min())   # the body's own height (hats do not count)

    @classmethod
    def posed(cls, clip, t, face, scale=1., yaw=0., hat=False, belly=0., squeeze=0.):
        """The body posed by an exported clip at t, scaled and turned; hat: wearing this make-source's worn hat (as
        recipe-overridden); belly, squeeze: a pregnant mother (pregnant). Read from the disk cache when its inputs match,
        else posed and stored."""
        key = json.dumps([INPUTS, clip, t, face, scale, yaw, B.R if hat else None, GRID.n, GRID.step]+([belly, squeeze] if belly else []), sort_keys=True)
        path = CACHE/f'shape-{hashlib.sha256(key.encode()).hexdigest()[:20]}.npz'
        if path.is_file():
            data = np.load(path); sh = cls.__new__(cls)
            for k in cls.FIELDS: setattr(sh, k, data[k] if data[k].ndim else data[k].item())
            sh.face = face; return sh
        pose = json.dumps([clip, t, face, B.R if hat else None], sort_keys=True)   # the same pose at another growth or yaw
        if pose not in _POSES: _POSES[pose] = body().mesh(clip, t, face=face, hat=B.worn_mesh() if hat else None)
        tris, kinds = _POSES[pose]
        sh = cls(pregnant(tris, kinds, belly, squeeze) if belly else tris, kinds, yaw, scale); sh.face = face
        CACHE.mkdir(parents=True, exist_ok=True); np.savez_compressed(path, **{k: getattr(sh, k) for k in cls.FIELDS})
        return sh

    def window(self, x, z):
        si, sj = int(round(x/GRID.step)), int(round(z/GRID.step))
        i0, j0 = GRID.n//2+self.di+si, GRID.n//2+self.dj+sj
        return slice(i0, i0+self.low.shape[0]), slice(j0, j0+self.low.shape[1]), (si*GRID.step, sj*GRID.step)

    def rest(self, field, x, z, overhang=False):
        """The lift that sets the body's underside on the field at x, z (the support rule); None off the field, or
        (unless overhang) where part of the underside has no hull below it."""
        si, sj, _ = self.window(x, z); f = field[si, sj]
        both = np.isfinite(f) & np.isfinite(self.low)
        if not both.any() or (not overhang and np.isfinite(self.low).sum() != both.sum()): return None
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
    """The set-down hull as drawn (world units) on GRID: what a body stands on (field), its outer bottom (low) and the
    hollow (cavity: inside the opening, under the brim face)."""
    def __init__(self):
        self.placed = B.world(B.placed_mesh())
        self.field = B.raster(self.placed, GRID, True); self.low = B.raster(self.placed, GRID, False)
        self.cavity = np.isfinite(self.field) & B.in_opening(GRID.x, GRID.z) & (self.field < -1e-3)


# ------------------------------------------------------------------ the braid's reach
def braid_envelope():
    """braid_cradle_lift's scoop point relative to the braid root over the clip (model units, growth 1): its farthest
    reach from the root and its lowest height above her bottom (cached)."""
    path = CACHE/f'envelope-{INPUTS}.json'
    if path.is_file(): return tuple(json.loads(path.read_text(encoding='utf-8')))
    who = body(); clip = who.clips['braid_cradle_lift']; c = clip['contacts']['braid_cradle']
    root = np.array(BRAID['braid_root'], float); far, low = 0., 99.
    for t in np.linspace(0, clip['duration'], 49):
        who.frame('braid_cradle_lift', t)
        p = (who.last_matrices[who.bones[c['bone']]]@np.r_[c['point'], 1])[:3]
        far = max(far, float(np.linalg.norm(p-root))); low = min(low, float(p[1]))
    CACHE.mkdir(parents=True, exist_ok=True); path.write_text(json.dumps([far, low]), encoding='utf-8')
    return far, low
