"""Aquatic Marisa hat candidates: worn on her head and set down brim up as her boat (drafts, not exported).

Each candidate (<name>/recipe.json) is one closed cloth section turned round the hat's axis. The worn hat and the
set-down hat use the same rings in the same order, so the change between them is a per-vertex blend; the band, the
bow (copied from the land hat) and, for stepped crowns, the bent tip as a tube ride along. Faces take the Marisa atlas
cells the land hat uses. The set-down hollow is read back as a height field: the surface a rider stands on.

Frames. Worn: the Marisa model frame in hat units, hat axis through anchor_worn. Set down: hat units, origin at the
opening's centre on the brim face that sat on the head, +Y up out of the hollow, the worn front (bow) toward +X and
the worn +X (the bent tip's side) toward -Z, the bow of the boat, where the owner faces. World: model units of an
adult at growth 1 (16 a block): set-down hat units times scale, the water plane at y = 0 and the brim face freeboard
above it.

    python build.py           # writes <name>/geometry.json and food_layer.png
    python build.py --check   # compares without writing
"""
from pathlib import Path
import argparse, json, math, sys
import numpy as np
from PIL import Image

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
ASSETS = ROOT/'src/main/resources/assets/mcyukkuri'
S = json.loads((HERE/'recipe.json').read_text(encoding='utf-8'))
K = S['scale']
W = S['wall']
R0 = S['crown_base']
BRIM = S['brim']
Z_AXIS = S['anchor_worn'][2]
SEG = S['segments']
# Marisa atlas cells (pixels, 16 x 16) the land hat draws with: crown cloth, the lighter base cloth, the dark brim edge,
# the cream band and bow, the bow knot. The hollow's walls take the dark cell, its level floors the lighter one.
CELLS = {'cloth': (64, 48), 'light': (80, 48), 'edge': (96, 48), 'band': (112, 48), 'knot': (16, 64), 'lining': (96, 48), 'floor': (80, 48)}
TEXTURE = 'marisa'


def recipe(name):
    return json.loads((HERE/name/'recipe.json').read_text(encoding='utf-8'))


def ring(r, y, cx=0., cz=0., cell='cloth'):
    return dict(r=max(0., float(r)), y=float(y), cx=float(cx), cz=float(cz), cell=cell)


# ------------------------------------------------------------------ the closed section, worn and set down
def offset(points, w, toward):
    """Offset a profile polyline [(r, y), ...] by w along its normal, on the side of `toward` (r, y): (-1, -1) into the
    worn crown (toward the axis and down), (1, -1) out of the set-down hollow (away from the axis and down)."""
    out = []
    for i, (r, y) in enumerate(points):
        a = np.array(points[max(i-1, 0)]); b = np.array(points[min(i+1, len(points)-1)])
        t = b-a; t = t/max(1e-9, np.linalg.norm(t)); n = np.array([-t[1], t[0]])
        if n@np.array(toward, float) < 0: n = -n
        out.append((r+w*n[0], y+w*n[1]))
    return out


def tiers_section(c):
    """Stepped round crown: worn and set-down rings (same order) plus the y map used for attached pieces."""
    yb0, yb1 = BRIM['bottom'], BRIM['top']; tiers = c['tiers']; b0, b1 = S['band']
    tops = list(np.cumsum([t['h'] for t in tiers])+yb1)
    starts = [yb1]+tops[:-1]
    breaks = [yb0, yb1]+tops; slopes = [1.]+[t['squash'] for t in tiers]
    tail = c.get('hook', {}).get('squash', tiers[-1]['squash'])

    def depth(y):
        d = 0.
        for i in range(len(slopes)):
            lo, hi = breaks[i], breaks[i+1]
            if y <= lo: return d
            d += (min(y, hi)-lo)*slopes[i]
            if y <= hi: return d
        return d+(y-breaks[-1])*tail

    outer = [ring(R0, yb1, cell='light'), ring(R0, yb1+b0, cell='band'), ring(R0+.07, yb1+b0, cell='band'),
             ring(R0+.07, yb1+b1, cell='band'), ring(R0, yb1+b1, cell=tiers[0]['cell'])]
    for i, t in enumerate(tiers):
        cx, cz = t['lean']
        if i: outer.append(ring(t['r'], starts[i], cx, cz, t['cell']))
        outer.append(ring(t['r'], tops[i], cx, cz, 'cloth'))
        nxt = tiers[i+1] if i+1 < len(tiers) else None
        if nxt is None: outer.append(ring(0, tops[i], cx, cz, 'cloth'))
    inner = []
    for i, t in reversed(list(enumerate(tiers))):
        cx, cz = t['lean']
        if i == len(tiers)-1: inner.append(ring(0, tops[i]-W, cx, cz, 'lining'))
        inner.append(ring(t['r']-W, tops[i]-W, cx, cz, 'lining'))
        inner.append(ring(t['r']-W, (starts[i]-W) if i else yb0, cx, cz, 'lining'))
    worn = outer+inner
    bend = c.get('afloat_bend', .5)
    afloat = [dict(q, y=-depth(q['y']), cx=-q['cz']*bend, cz=-q['cx']*bend) for q in worn]
    return worn, afloat, depth


def smooth_section(c):
    """Soft round crown (basin): worn cone with a hanging tip; set down, a quarter-ellipse wall and a flat floor."""
    yb0, yb1 = BRIM['bottom'], BRIM['top']; p = c['worn']; a = c['afloat']; b0, b1 = S['band']
    H, n = p['height'], p['rings']
    ts = sorted({0., b0/H, b1/H, *[k/n for k in range(1, n+1) if k/n > b1/H+.02]})

    def worn_at(t):
        r = R0*(1-t)**p['power']; y = yb1+H*t; cx = 0.; cz = p['lean_z']*t
        d = p['droop']
        if t > d['from']:
            u = (t-d['from'])/(1-d['from']); cx += d['x']*u*u; y += d['y']*u*u
        return r, y, cx, cz

    # the cavity curve set down, by the same t: the band and the lower crown make the wall, the rest lies on the floor
    Rin, D, Rf = R0-W, a['floor_depth'], a['floor_radius']; tw = ts[min(a['wall_rings'], len(ts)-2)+1]

    lip = BRIM['top']-BRIM['bottom']  # the opening's edge is the brim's thickness; the wall curves in below it

    def cavity_at(t):
        if t == 0: return Rin, 0.
        if t <= tw:
            phi = t/tw*math.pi/2
            return Rf+(Rin-Rf)*math.cos(phi), lip+(D-lip)*math.sin(phi)
        u = (t-tw)/(1-tw); r = Rf*(1-u)
        return r, D+a['dimple']*u*u

    outer_w, inner_w, outer_a, inner_a = [], [], [], []
    prof = [worn_at(t) for t in ts]
    inner_prof = offset([(r, y) for r, y, _, _ in prof], W, (-1, -1))
    cav = [cavity_at(t) for t in ts]
    under = offset([(r, -d) for r, d in cav], W, (1, -1))
    for i, t in enumerate(ts):
        r, y, cx, cz = prof[i]
        band = b0/H-1e-9 <= t < b1/H-1e-9
        cell = 'band' if band else ('light' if t < b0/H else 'cloth')
        outer_w.append(ring(r, y, cx, cz, cell))
        ri, yi = inner_prof[i]
        inner_w.append(ring(ri if i else R0-W, yi if i else yb0, cx, cz, 'lining'))
        ro, yo = under[i]
        outer_a.append(ring(ro if i else R0, yo if i else -(yb1-yb0), 0, 0, cell))
        inner_a.append(ring(cav[i][0], -cav[i][1], 0, 0, 'lining'))
    outer_w[-1]['r'] = inner_w[-1]['r'] = outer_a[-1]['r'] = inner_a[-1]['r'] = 0.
    worn = outer_w+inner_w[::-1]; afloat = outer_a+inner_a[::-1]
    return worn, afloat, lambda y: y-yb0


def drum_pocket_section(c):
    """Stiff drum (band zone) under a soft cone: set down the drum stays upright and its top is the floor; the cone's
    lower part lies flat on it, the upper cone and the bent tip fold into a round pouch toward the bow."""
    yb0, yb1 = BRIM['bottom'], BRIM['top']; d = c['drum']; cn = c['cone']; a = c['afloat']; b0, b1 = S['band']
    Yd = yb1+d['h']; Rc = cn['base']; n = cn['rings']
    ts = [k/n for k in range(n+1)]

    def worn_at(t):
        r = Rc*(1-t)**cn['power']; y = Yd+cn['height']*t
        return r, y, cn['lean_x']*t**cn['lean_power'], cn['lean_z']*t

    floor = (yb1-yb0)+d['h']-W  # the drum's inner top, set down: the floor
    P, rp, Dp, tp = a['pocket_center'], a['pocket_radius'], a['pocket_depth'], a['pocket_from']
    cs = .5*((P-(Rc-W-rp))+(R0-W-(Rc-W)))  # the cone base ring's centre on the floor, between the nesting limits

    def cavity_at(t):  # (r, depth, centre toward the bow) for the cone's inner ring t
        if t <= tp:
            u = t/tp; return (Rc-W)+(rp-(Rc-W))*u, floor, cs+(P-cs)*u
        psi = (t-tp)/(1-tp)*math.pi/2
        return rp*math.cos(psi), floor+Dp*math.sin(psi), P

    outer_w = [ring(R0, yb1, cell='light'), ring(R0, yb1+b0, cell='band'), ring(R0+.07, yb1+b0, cell='band'),
               ring(R0+.07, yb1+b1, cell='band'), ring(R0, yb1+b1, cell=d['cell']), ring(R0, Yd, cell='cloth')]
    prof = [worn_at(t) for t in ts]
    inner_prof = offset([(r, y) for r, y, _, _ in prof], W, (-1, -1))
    for r, y, cx, cz in prof: outer_w.append(ring(r, y, cx, cz, cn['cell']))
    outer_w[-1]['r'] = 0
    inner_w = [ring(ri, yi, prof[i][2], prof[i][3], 'lining') for i, (ri, yi) in enumerate(inner_prof)]
    inner_w[0] = ring(Rc-W, Yd-W, 0, 0, 'lining'); inner_w[-1]['r'] = 0
    inner_w += [ring(R0-W, Yd-W, cell='lining'), ring(R0-W, yb0, cell='lining')]
    worn = outer_w+[inner_w[i] for i in range(len(prof)-1, -1, -1)]+inner_w[len(prof):]

    # set down: the drum and band as worn (depth = y - yb0), the cone rings on the floor and in the pouch
    def drum(q, y=None):
        return dict(q, y=-(q['y']-yb0) if y is None else y, cx=0., cz=0.)
    outer_a = [drum(q) for q in outer_w[:6]]
    cav = [cavity_at(t) for t in ts]
    under = offset([(r, -dd) for r, dd, _ in cav], W, (1, -1))
    for i, t in enumerate(ts):
        ro, yo = under[i]
        # below the floor the cloth hangs as the pouch; on the floor the drum's top is the cloth under it
        outer_a.append(dict(outer_w[6+i], r=ro if t > tp else (Rc if i == 0 else cav[i][0]+W*0),
                            y=yo if t > tp else -(floor+W), cx=0., cz=-cav[i][2]))
    outer_a[-1]['r'] = 0
    inner_a = [dict(inner_w[i], r=cav[i][0], y=-cav[i][1], cx=0., cz=-cav[i][2]) for i in range(len(prof))]
    inner_a[-1]['r'] = 0
    inner_a += [dict(inner_w[len(prof)], r=R0-W, y=-floor, cx=0., cz=0.), dict(inner_w[len(prof)+1], r=R0-W, y=0., cx=0., cz=0.)]
    afloat = outer_a+[inner_a[i] for i in range(len(prof)-1, -1, -1)]+inner_a[len(prof):]
    return worn, afloat, lambda y: y-yb0


SECTIONS = {'tiers': tiers_section, 'smooth': smooth_section, 'drum_pocket': drum_pocket_section}


def brim_rings():
    """The brim: flat from the crown out to droop_from, then drooping to its edge (set down: a turned-up rim)."""
    yb0, yb1 = BRIM['bottom'], BRIM['top']; Rb = BRIM['radius']; dr = BRIM.get('droop', 0.); r1 = BRIM.get('droop_from', Rb)
    worn = [ring(R0-W, yb0, cell='cloth'), ring(r1, yb0, cell='cloth'), ring(Rb, yb0-dr, cell='edge'),
            ring(Rb, yb1-dr, cell='cloth'), ring(r1, yb1, cell='cloth')]
    afloat = [dict(q, y=-(q['y']-yb0)) for q in worn]
    return worn, afloat


# ------------------------------------------------------------------ meshes
def cell_uv(cell, j, quad=True):
    u0, v0 = CELLS[cell]
    if cell in ('cloth', 'light') and j % 5:  # most facets take the plain part of the cell, every fifth its highlight
        u0, v0, s = u0+8, v0+8, 7
    else:
        s = 16
    return np.array([[u0, v0], [u0+s, v0], [u0+s, v0+s], [u0, v0+s]], float)/256


def loop_mesh(rings, afloat, seg=SEG):
    """Strips between consecutive rings of a closed loop; returns (N, 3, 8) triangles: xyz, uv, normal (unset)."""
    tris = []
    for i in range(len(rings)):
        a, b = rings[i], rings[(i+1) % len(rings)]
        for j in range(seg):
            th0, th1 = 2*math.pi*j/seg, 2*math.pi*(j+1)/seg
            pts = []
            for q, th in ((a, th0), (a, th1), (b, th1), (b, th0)):
                ang = -th-math.pi/2 if afloat else th
                pts.append([q['cx']+q['r']*math.cos(ang), q['y'], q['cz']+q['r']*math.sin(ang)])
            uv = cell_uv(a['cell'], j)
            for k in ((0, 1, 2), (0, 2, 3)):
                tri = np.array([[*pts[m], *uv[m], 0, 0, 0] for m in k], float)
                tris.append(tri)
    return np.array(tris)


def tube(path, radii, cell='cloth', sides=8):
    """A capped tube along a polyline (hat units)."""
    P = np.array(path, float); tris = []
    T = np.gradient(P, axis=0); T /= np.linalg.norm(T, axis=1, keepdims=True)
    ref = np.array([0, 0, 1.])
    rings = []
    for p, t, r in zip(P, T, radii):
        u = np.cross(t, ref); u /= max(1e-9, np.linalg.norm(u)); v = np.cross(t, u)
        rings.append([p+r*(math.cos(2*math.pi*k/sides)*u+math.sin(2*math.pi*k/sides)*v) for k in range(sides)])
    uv = cell_uv(cell, 1)
    def quad(a, b, c, d):
        for k in ((a, b, c), (a, c, d)):
            tris.append(np.array([[*q, *uv[m], 0, 0, 0] for m, q in enumerate(k)], float))
    for i in range(len(rings)-1):
        for k in range(sides):
            quad(rings[i][k], rings[i][(k+1) % sides], rings[i+1][(k+1) % sides], rings[i+1][k])
    for ends, flip in ((rings[0], True), (rings[-1], False)):
        c = np.mean(ends, axis=0)
        for k in range(sides):
            a, b = ends[k], ends[(k+1) % sides]
            tri = (c, b, a) if flip else (c, a, b)
            tris.append(np.array([[*q, *uv[m], 0, 0, 0] for m, q in enumerate(tri)], float))
    return np.array(tris)


def normals(tris):
    a, b, c = tris[:, 0, :3], tris[:, 1, :3], tris[:, 2, :3]
    n = np.cross(b-a, c-a); length = np.linalg.norm(n, axis=1)
    keep = length > 1e-9
    tris = tris[keep]; n = n[keep]/length[keep, None]
    tris[:, :, 5:8] = n[:, None, :]
    return tris


def orient(tris):
    """Wind a closed part outward: flip it if its signed volume is negative."""
    a, b, c = tris[:, 0, :3], tris[:, 1, :3], tris[:, 2, :3]
    if np.einsum('ij,ij->i', a, np.cross(b, c)).sum() < 0: tris = tris[:, ::-1].copy()
    return tris


def bow_parts():
    """The land hat's bow cuboids (marisa.json bow_*), as triangles with their own atlas uvs."""
    m = json.loads((ASSETS/'yukkuri/marisa.json').read_text(encoding='utf-8'))
    V = np.array(m['vertices']).reshape(-1, 8); out = []
    for p in m['parts']:
        if p['name'].startswith('bow_'):
            out.append(V[p['start']:p['start']+p['count']])
    return np.concatenate(out).reshape(-1, 3, 8)


def worn_band_radius(worn_rings):
    band = [q['r'] for q in worn_rings if q['cell'] == 'band']
    return max(band) if band else R0


def build(name):
    """Worn mesh (Marisa model frame) and set-down mesh (hat units, set-down frame) with the same triangle order."""
    c = recipe(name)
    section, section_a, depth = SECTIONS[c['kind']](c)
    bw, ba = brim_rings()
    worn_rings, afloat_rings = bw+section, ba+section_a
    assert len(worn_rings) == len(afloat_rings)
    worn = loop_mesh(worn_rings, False); afloat = loop_mesh(afloat_rings, True)
    # inner strips that lie nearly level set down (under about 15 degrees) are floors (lighter cell), so steps and pouch read
    for i in range(len(afloat_rings)):
        a, b = afloat_rings[i], afloat_rings[(i+1) % len(afloat_rings)]
        if a['cell'] == 'lining' and abs(a['y']-b['y']) < .27*max(.1, abs(a['r']-b['r'])):
            for j in range(SEG):
                uv = cell_uv('floor', j)
                for t, k in enumerate(((0, 1, 2), (0, 2, 3))):
                    idx = (i*SEG+j)*2+t
                    worn[idx, :, 3:5] = afloat[idx, :, 3:5] = uv[list(k)]
    parts_w, parts_a = [orient(worn)], [orient(afloat)]
    if 'hook' in c:
        h = c['hook']
        last = [q for q in worn_rings if q['cell'] != 'lining'][-1]
        base = np.array([last['cx'], last['y'], last['cz']])
        path_w = [base+np.array(p) for p in h['path']]
        bend = c.get('afloat_bend', .5)
        # set down, the bent tip lies folded flat inside the bottom cloth of the hollow (worn x, z -> set-down -z, -x)
        low = next(q for q in afloat_rings if q['r'] == 0 and q['cell'] != 'lining')
        path_a = [np.array([low['cx']-.15*(p[2]-base[2]), low['y']+W/2+.04*(p[1]-base[1]), low['cz']-.15*(p[0]-base[0])]) for p in path_w]
        radius_a = [r*h.get('afloat_radius', 1.) for r in h['radius']]
        parts_w.append(orient(tube(path_w, h['radius']))); parts_a.append(orient(tube(path_a, radius_a)))
    # the bow on the band's front: the land bow moved out to this hat's band, set down as the band moves
    bow = bow_parts(); rb = worn_band_radius(worn_rings)
    dz = (-(rb)+.02)-(-4.76)
    bw_ = bow.copy(); bw_[:, :, 2] += dz-Z_AXIS  # axis-relative z
    ba_ = bw_.copy()
    x, y, z = bw_[:, :, 0].copy(), bw_[:, :, 1].copy(), bw_[:, :, 2].copy()
    ba_[:, :, 0] = -z; ba_[:, :, 2] = -x; ba_[:, :, 1] = -np.vectorize(depth)(y)
    if c['kind'] != 'tiers':  # the crown is pressed but not along y: the bow lies half height on the upright outer wall
        ba_[:, :, 1] = -((BRIM['top']-BRIM['bottom'])+(y-BRIM['top'])*.5)
        ba_[:, :, 0] += max(q['r'] for q in afloat_rings if q['cell'] == 'band')-rb
    parts_w.append(bw_); parts_a.append(ba_)
    worn = normals(np.concatenate(parts_w)); afloat = normals(np.concatenate(parts_a))
    worn[:, :, 2] += Z_AXIS
    return c, worn, afloat, (worn_rings, afloat_rings)


def boat_world(afloat):
    """Set-down hat units to the boat's world frame (adult model units, water plane y = 0)."""
    out = afloat.copy(); out[:, :, :3] *= K; out[:, :, 1] += S['freeboard']*K
    return out


# ------------------------------------------------------------------ height fields (what stands on what)
class Grid:
    def __init__(self, half=16., step=.125):
        self.step = step; self.n = int(round(2*half/step)); self.half = half
        c = (np.arange(self.n)+.5)*step-half
        self.x, self.z = np.meshgrid(c, c, indexing='ij')

    def index(self, x, z):
        return np.floor((np.asarray(x)+self.half)/self.step).astype(int), np.floor((np.asarray(z)+self.half)/self.step).astype(int)


def raster(tris, grid, up=True):
    """Highest up-facing (or lowest down-facing) surface over each grid cell; -inf / +inf where none."""
    fill = -np.inf if up else np.inf
    out = np.full((grid.n, grid.n), fill)
    face = tris[:, 0, 6]
    sel = tris[face > 1e-4] if up else tris[face < -1e-4]
    for t in sel:
        p = t[:, :3]
        lo = np.floor((p[:, [0, 2]].min(0)+grid.half)/grid.step).astype(int)
        hi = np.ceil((p[:, [0, 2]].max(0)+grid.half)/grid.step).astype(int)
        lo = np.clip(lo, 0, grid.n-1); hi = np.clip(hi, 0, grid.n)
        if (hi <= lo).any(): continue
        xs = grid.x[lo[0]:hi[0], lo[1]:hi[1]]; zs = grid.z[lo[0]:hi[0], lo[1]:hi[1]]
        (x0, y0, z0), (x1, y1, z1), (x2, y2, z2) = p
        det = (z1-z2)*(x0-x2)+(x2-x1)*(z0-z2)
        if abs(det) < 1e-12: continue
        a = ((z1-z2)*(xs-x2)+(x2-x1)*(zs-z2))/det; b = ((z2-z0)*(xs-x2)+(x0-x2)*(zs-z2))/det; g = 1-a-b
        inside = (a >= -1e-6) & (b >= -1e-6) & (g >= -1e-6)
        y = a*y0+b*y1+g*y2
        view = out[lo[0]:hi[0], lo[1]:hi[1]]
        if up: view[inside] = np.maximum(view[inside], y[inside])
        else: view[inside] = np.minimum(view[inside], y[inside])
    return out


def floor_profile(field, grid, along='z'):
    """The standing surface along the bow-stern line through the axis (world units)."""
    i0 = grid.n//2
    line = field[i0, :] if along == 'z' else field[:, i0]
    c = (np.arange(grid.n)+.5)*grid.step-grid.half
    return [(round(float(z), 3), round(float(h), 3)) for z, h in zip(c, line) if np.isfinite(h)]


def portion_volume():
    p = S['portion']; r = p['radius_blocks']*16
    return math.pi*r*r*p['height_blocks']*16


def fill_level(field, grid, volume, top):
    """The level a layer of this volume reaches in the hollow (cells below the brim face only)."""
    cav = np.isfinite(field) & (field < top-1e-3)
    h = field[cav]; area = grid.step**2
    lo, hi = h.min(), top
    for _ in range(60):
        mid = .5*(lo+hi)
        if np.clip(mid-h, 0, None).sum()*area < volume: lo = mid
        else: hi = mid
    return .5*(lo+hi)


def food_texture(path):
    """16 x 16 stores layer: grain, dry grass, berries and seeds (procedural, fixed seed)."""
    rng = np.random.default_rng(7)
    base = np.array([176, 140, 78]); img = np.zeros((16, 16, 3))
    img[:] = base+rng.integers(-14, 15, (16, 16, 1))
    for _ in range(26):
        x, y = rng.integers(0, 16, 2); img[y, x] = [212, 182, 104]
    for _ in range(12):
        x, y = rng.integers(0, 15, 2); img[y, x] = img[y, x+1] = [108, 150, 62]
    for _ in range(7):
        x, y = rng.integers(0, 16, 2); img[y, x] = [196, 52, 64]
    for _ in range(9):
        x, y = rng.integers(0, 16, 2); img[y, x] = [104, 72, 40]
    Image.fromarray(np.clip(img, 0, 255).astype(np.uint8)).convert('RGBA').save(path)


def summary(name):
    c, worn, afloat, (wr, ar) = build(name)
    boat = boat_world(afloat); grid = Grid()
    field = raster(boat, grid, True); hull_low = raster(boat, grid, False)
    top = S['freeboard']*K
    opening = (R0-W)*K
    deepest = top-np.nanmin(np.where(np.isfinite(field), field, np.nan))
    draft = -np.nanmin(np.where(np.isfinite(hull_low), hull_low, np.nan))
    v = portion_volume(); fills = {}
    for key, count in S['portion']['fills'].items():
        level = fill_level(field, grid, count*v, top)
        fills[key] = dict(portions=count, level_below_brim=round(top-level, 3))
    wl = np.isfinite(hull_low) & (hull_low < 0)
    return dict(
        candidate=name, label=c['label'], kind=c['kind'], scale=K,
        units='world: adult model units at growth 1 (16 a block); hat: land-hat units (world = hat x scale)',
        brim_radius=round(BRIM['radius']*K, 3), opening_radius=round(opening, 3),
        hollow_depth=round(float(deepest), 3), hull_below_water=round(float(draft), 3),
        waterline_area=round(float(wl.sum()*grid.step**2), 2),
        portion_volume=round(v, 3), fills=fills,
        floor_profile=floor_profile(field, grid)[::4],
        rings=dict(worn=len(wr), afloat=len(ar), segments=SEG, triangles=int(len(worn)))
    )


def main():
    ap = argparse.ArgumentParser(); ap.add_argument('--check', action='store_true'); args = ap.parse_args()
    ok = True
    for name in S['candidates']:
        data = summary(name); text = json.dumps(data, ensure_ascii=False, indent=1)+'\n'
        path = HERE/name/'geometry.json'
        if args.check:
            same = path.is_file() and path.read_text(encoding='utf-8') == text
            print(name, 'ok' if same else 'differs'); ok &= same
        else:
            path.write_text(text, encoding='utf-8'); print(path)
        print(f"  opening r {data['opening_radius']}  hollow {data['hollow_depth']}  hull under water {data['hull_below_water']}  fills",
              {k: v['level_below_brim'] for k, v in data['fills'].items()})
    if not args.check: food_texture(HERE/'food_layer.png')
    return 0 if ok else 1


if __name__ == '__main__':
    sys.exit(main())
