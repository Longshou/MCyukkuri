"""Offline software renderer for yukkuri_props meshes (local preview only, nothing here is shipped).

Vanilla texture references (minecraft:textures/...) resolve to a locally extracted copy of the Minecraft client assets,
given by the environment variable MCYUKKURI_VANILLA (folder holding `textures/`, default `.local/vanilla-ref/tex/assets/minecraft`);
that copy is never committed. Supports UV repeat, per-part tint, unit masks and runtime yukkuri models posed at rest
(`yuk:<species>:<expression>`)."""
import json, math, os
from pathlib import Path
import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[4]
YUK = ROOT / 'src/main/resources/assets/mcyukkuri'
VANILLA = Path(os.environ.get('MCYUKKURI_VANILLA', ROOT / '.local/vanilla-ref/tex/assets/minecraft'))
_tex = {}
L0 = np.array([0.2, 1.0, -0.7]); L0 /= np.linalg.norm(L0)
L1 = np.array([-0.2, 1.0, 0.7]); L1 /= np.linalg.norm(L1)
SKIP = ('face_effect', 'face_tear', 'mamu', 'peni', 'face_blush')


def tex(path):
    path = str(path)
    if path not in _tex:
        _tex[path] = np.array(Image.open(path).convert('RGBA'))
    return _tex[path]


def vanilla(ref):
    ns, p = ref.split(':', 1)
    assert ns == 'minecraft', ref
    return VANILLA / p


class Source:
    def __init__(self, root):
        self.root = Path(root)
        self.assets = self.root / 'src/main/resources/assets/mcyukkuri'

    def load(self, name):
        if name.startswith('ground'):         # ground[:half-size]: a vanilla dirt plane at y = 0 (preview floor)
            h = float(name.split(':')[1]) if ':' in name else 17.0
            k = 2 * h / 16
            rows = [[-h, -.01, -h, 0, 0, 0, 1, 0], [-h, -.01, h, 0, k, 0, 1, 0], [h, -.01, h, k, k, 0, 1, 0],
                    [-h, -.01, -h, 0, 0, 0, 1, 0], [h, -.01, h, k, k, 0, 1, 0], [h, -.01, -h, k, 0, 0, 1, 0]]
            return {'parts': [{'name': 'ground', 'start': 0, 'count': 6, 'texture': 'minecraft:textures/block/dirt.png'}],
                    'bounds': [[-h, h], [0, 0], [-h, h]]}, np.array(rows, dtype=np.float32)
        if name.startswith('yuk:'):      # yuk:reimu[:expression]
            bits = name.split(':')
            return self.load_yukkuri(bits[1], bits[2] if len(bits) > 2 else 'sleep')
        d = json.loads((self.assets / 'yukkuri_props' / f'{name}.json').read_text(encoding='utf-8'))
        return d, np.array(d['vertices'], dtype=np.float32).reshape(-1, 8)

    def tex_of(self, name, part):
        t = part.get('texture')
        if t:
            return vanilla(t) if t.startswith('minecraft:') else self.assets / t.split(':', 1)[1]
        if name.startswith('yuk:'):
            bits = name.split(':')
            return YUK / 'textures/entity' / bits[1] / f'{bits[2] if len(bits) > 2 else "sleep"}.png'
        return self.assets / 'textures/props' / f'{name}.png'

    def load_yukkuri(self, species, expression):
        d = json.loads((YUK / 'yukkuri' / f'{species}.json').read_text(encoding='utf-8'))
        v = np.array(d['vertices'], dtype=np.float32).reshape(-1, 8)
        rows, parts, n = [], [], 0
        for p in d['parts']:
            if p['name'].startswith(SKIP) or p.get('anatomy'):
                continue
            parts.append({'name': p['name'], 'start': n, 'count': p['count']})
            rows.append(v[p['start']:p['start'] + p['count']])
            n += p['count']
        arr = np.concatenate(rows)
        return {'parts': parts, 'bounds': [[float(arr[:, i].min()), float(arr[:, i].max())] for i in range(3)]}, arr

    def extent(self, name):
        d, _ = self.load(name)
        b = d['bounds']
        return max(b[0][1] - b[0][0], b[1][1] - b[1][0], b[2][1] - b[2][0])


def light(n):
    return np.minimum(1.0, 0.4 + 0.6 * (np.maximum(0, n @ L0) + np.maximum(0, n @ L1)))


def rot_y(deg):
    a = math.radians(deg); c, s = math.cos(a), math.sin(a)
    return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]], dtype=np.float32)


def render(src, items, az=225, el=28, ppu=24, size=None, flat=False, lock=None, pad=1.08, bg=(0, 0, 0, 0)):
    """items: (name, units_or_('mask',m), dx, dy, dz, scale[, yaw_deg[, source]])"""
    a, e = math.radians(az), math.radians(el)
    d = np.array([math.sin(a) * math.cos(e), math.sin(e), math.cos(a) * math.cos(e)])
    right = np.cross([0, 1, 0.0], d); right /= np.linalg.norm(right)
    up = np.cross(d, right)
    tris = []
    for it in items:
        nm, un, dx, dy, dz, sc = it[:6]
        yaw = it[6] if len(it) > 6 else 0
        s_ = it[7] if len(it) > 7 else src
        R = rot_y(yaw)
        data, v = s_.load(nm)
        for part in data['parts']:
            u = part.get('unit', 0)
            if isinstance(un, tuple):
                if u and not (un[1] & (1 << (u - 1))):
                    continue
            elif u > un:
                continue
            T = tex(s_.tex_of(nm, part))
            tint = np.array(part.get('tint', [255, 255, 255]), dtype=np.float32) / 255
            s, c = part['start'], part['count']
            for f in range(s, s + c, 3):
                tv = v[f:f + 3]
                P = (tv[:, :3] @ R.T) * sc + np.array([dx, dy, dz])
                N = tv[:, 5:8] @ R.T
                tris.append((P, tv[:, 3:5], N, T, tint))
    allp = np.concatenate([t[0] for t in tris])
    sx, sy = allp @ right, allp @ up
    minx, maxx, miny, maxy = lock if lock else (sx.min(), sx.max(), sy.min(), sy.max())
    if size is None:
        W = int(math.ceil((maxx - minx) * ppu * pad)) + 4
        H = int(math.ceil((maxy - miny) * ppu * pad)) + 4
    else:
        W, H = size
    cx, cy = (minx + maxx) / 2, (miny + maxy) / 2
    img = np.zeros((H, W, 4), dtype=np.float32); img[:] = np.array(bg) / 255.0
    zb = np.full((H, W), -1e9, dtype=np.float32)
    for p, uv, nrm, T, tint in tris:
        X = (p @ right - cx) * ppu + W / 2
        Y = H / 2 - (p @ up - cy) * ppu
        Z = p @ d
        x0, x1 = max(int(math.floor(X.min())), 0), min(int(math.ceil(X.max())), W - 1)
        y0, y1 = max(int(math.floor(Y.min())), 0), min(int(math.ceil(Y.max())), H - 1)
        if x1 < x0 or y1 < y0:
            continue
        den = (Y[1] - Y[2]) * (X[0] - X[2]) + (X[2] - X[1]) * (Y[0] - Y[2])
        if abs(den) < 1e-9:
            continue
        gx, gy = np.meshgrid(np.arange(x0, x1 + 1) + .5, np.arange(y0, y1 + 1) + .5)
        w0 = ((Y[1] - Y[2]) * (gx - X[2]) + (X[2] - X[1]) * (gy - Y[2])) / den
        w1 = ((Y[2] - Y[0]) * (gx - X[2]) + (X[0] - X[2]) * (gy - Y[2])) / den
        w2 = 1 - w0 - w1
        m = (w0 >= -1e-6) & (w1 >= -1e-6) & (w2 >= -1e-6)
        if not m.any():
            continue
        z = w0 * Z[0] + w1 * Z[1] + w2 * Z[2]
        sub = zb[y0:y1 + 1, x0:x1 + 1]
        upd = m & (z > sub + 1e-3)
        if not upd.any():
            continue
        u = w0 * uv[0, 0] + w1 * uv[1, 0] + w2 * uv[2, 0]
        vv = w0 * uv[0, 1] + w1 * uv[1, 1] + w2 * uv[2, 1]
        th, tw = T.shape[:2]
        tx = np.floor(u * tw).astype(int) % tw        # GL_REPEAT
        ty = np.floor(vv * th).astype(int) % th
        col = T[ty, tx].astype(np.float32) / 255
        upd = upd & (col[..., 3] > 0.5)
        if not upd.any():
            continue
        if flat:
            sh = np.ones(gx.shape, dtype=np.float32)
        else:
            nn = w0[..., None] * nrm[0] + w1[..., None] * nrm[1] + w2[..., None] * nrm[2]
            nn /= np.maximum(np.linalg.norm(nn, axis=-1, keepdims=True), 1e-6)
            sh = light(nn)
        rgba = col.copy()
        rgba[..., :3] *= tint * sh[..., None]
        rgba[..., 3] = 1
        sub[upd] = z[upd]
        img[y0:y1 + 1, x0:x1 + 1][upd] = rgba[upd]
    return Image.fromarray((np.clip(img, 0, 1) * 255).astype(np.uint8), 'RGBA'), (minx, maxx, miny, maxy)
