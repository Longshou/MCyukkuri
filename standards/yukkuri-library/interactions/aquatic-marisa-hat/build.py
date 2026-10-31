"""The aquatic Marisa's second hat form: worn on her head, set down brim up as her boat (make-source; export.py exports it).

Outside it is round stepped tiers (the land hat's tiers turned round) under the land hat's own bent tip, a band and the
land bow at the front; set down, the base tier stands upright round a round basin and the tiers under it are pressed into
a keel below the water. The basin inside (a straight lip the brim's thickness, a quarter-ellipse wall, a level floor with
a shallow dimple) is its own shape; the cloth between basin and tiers is as thick as the two leave. Faces take the Marisa
atlas cells the land hat uses.

Frames. Worn: the Marisa model frame in hat units, the hat's axis through anchor_worn. Set down: hat units, origin at the
middle of the brim face that sat on the head, +Y out of the hollow, the worn front (bow) toward +X and the worn +X (the
bent tip's side) toward -Z (the bow of the boat, where the owner faces). World: model units of an adult at growth 1 (16 a
block), set-down hat units times scale.

    python build.py           # writes geometry.json (profile, sizes) and food_layer.png
    python build.py --check   # compares without writing
"""
from pathlib import Path
import argparse, json, math, sys
import numpy as np
from PIL import Image

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
ASSETS = ROOT/'src/main/resources/assets/mcyukkuri'
R = json.loads((HERE/'recipe.json').read_text(encoding='utf-8'))
K = R['scale']
W = R['wall']
BRIM = R['brim']
OUT = R['outside']
IN = R['inside']
Z_AXIS = R['anchor_worn'][2]
SEG = R['segments']
YB0, YB1 = BRIM['bottom'], BRIM['top']
LIP = YB1-YB0
R0 = OUT['tiers'][0]['r']
# Marisa atlas cells (pixels, 16 x 16) the land hat draws with; the hollow's walls take the dark cell, its floors the lighter.
CELLS = {'cloth': (64, 48), 'light': (80, 48), 'edge': (96, 48), 'band': (112, 48), 'lining': (96, 48), 'floor': (80, 48)}


def ring(r, y, cx=0., cz=0., cell='cloth'):
    return dict(r=max(0., float(r)), y=float(y), cx=float(cx), cz=float(cz), cell=cell)


def cell_uv(cell, j):
    u0, v0 = CELLS[cell]
    if cell in ('cloth', 'light') and j % 5:  # most facets take the plain part of the cell, every fifth its highlight
        u0, v0, s = u0+8, v0+8, 7
    else:
        s = 16
    return np.array([[u0, v0], [u0+s, v0], [u0+s, v0+s], [u0, v0+s]], float)/256


def loop_mesh(rings, mirrored):
    """Strips between consecutive rings of a closed loop: (N, 3, 8) triangles (xyz, uv, normal unset)."""
    tris = []
    for i in range(len(rings)):
        a, b = rings[i], rings[(i+1) % len(rings)]
        level = a['cell'] == 'lining' and abs(a['y']-b['y']) < .27*max(.1, abs(a['r']-b['r']))
        for j in range(SEG):
            th0, th1 = 2*math.pi*j/SEG, 2*math.pi*(j+1)/SEG
            pts = []
            for q, th in ((a, th0), (a, th1), (b, th1), (b, th0)):
                ang = -th-math.pi/2 if mirrored else th
                pts.append([q['cx']+q['r']*math.cos(ang), q['y'], q['cz']+q['r']*math.sin(ang)])
            uv = cell_uv('floor' if level else a['cell'], j)
            for k in ((0, 1, 2), (0, 2, 3)):
                tris.append(np.array([[*pts[m], *uv[m], 0, 0, 0] for m in k], float))
    return np.array(tris)


def orient(tris):
    """Wind a closed part outward: flip it if its signed volume is negative."""
    a, b, c = tris[:, 0, :3], tris[:, 1, :3], tris[:, 2, :3]
    if np.einsum('ij,ij->i', a, np.cross(b, c)).sum() < 0: tris = tris[:, ::-1].copy()
    return tris


def normals(tris):
    a, b, c = tris[:, 0, :3], tris[:, 1, :3], tris[:, 2, :3]
    n = np.cross(b-a, c-a); length = np.linalg.norm(n, axis=1)
    keep = length > 1e-9
    tris = tris[keep]; n = n[keep]/length[keep, None]
    tris[:, :, 5:8] = n[:, None, :]
    return tris


def land_parts(prefixes):
    """Cuboids of the land hat (marisa.json) by part name prefix, as triangles with their own atlas uvs."""
    m = json.loads((ASSETS/'yukkuri/marisa.json').read_text(encoding='utf-8'))
    V = np.array(m['vertices']).reshape(-1, 8); out = {}
    for p in m['parts']:
        if p['name'].startswith(tuple(prefixes)): out[p['name']] = V[p['start']:p['start']+p['count']].reshape(-1, 3, 8)
    return out


# ------------------------------------------------------------------ worn
def tiers():
    t = OUT['tiers']; tops = list(np.cumsum([x['h'] for x in t])+YB1)
    return t, tops, [YB1]+tops[:-1]


def brim(worn):
    """The brim ring: flat from the crown out to droop_from, then drooping to its edge worn, turned up set down."""
    Rb, dr, r1 = BRIM['radius'], BRIM['droop'], BRIM['droop_from']
    inner = (R0-W) if worn else IN['opening']
    if worn:
        return [ring(inner, YB0, cell='cloth'), ring(r1, YB0, cell='cloth'), ring(Rb, YB0-dr, cell='edge'),
                ring(Rb, YB1-dr, cell='cloth'), ring(r1, YB1, cell='cloth')]
    return [ring(inner, 0, cell='cloth'), ring(r1, 0, cell='cloth'), ring(Rb, dr, cell='edge'),
            ring(Rb, dr-LIP, cell='cloth'), ring(r1, -LIP, cell='cloth')]


def worn_rings():
    t, tops, starts = tiers(); b0, b1 = R['band']
    out = brim(True)+[ring(R0, YB1, cell='light'), ring(R0, YB1+b0, cell='band'), ring(R0+.07, YB1+b0, cell='band'),
                      ring(R0+.07, YB1+b1, cell='band'), ring(R0, YB1+b1, cell=t[0]['cell'])]
    for i, x in enumerate(t):
        cx, cz = x['lean']
        if i: out.append(ring(x['r'], starts[i], cx, cz, x['cell']))
        out.append(ring(x['r'], tops[i], cx, cz, 'cloth'))
    out.append(ring(0, tops[-1], *t[-1]['lean'], 'cloth'))
    inner = [ring(0, tops[-1]-W, *t[-1]['lean'], 'lining')]
    for i, x in reversed(list(enumerate(t))):
        cx, cz = x['lean']
        inner.append(ring(x['r']-W, tops[i]-W, cx, cz, 'lining'))
        inner.append(ring(x['r']-W, (starts[i]-W) if i else YB0, cx, cz, 'lining'))
    return out+inner


def tip_and_bow():
    """The land hat's own bent tip, set on the top tier as it sits on hat_taper_3, and its bow moved out to the band."""
    land = land_parts(OUT['tip']+[OUT['tip_base'], 'bow_'])
    base = land.pop(OUT['tip_base'])[:, :, :3].reshape(-1, 3)
    t, tops, _ = tiers()
    top_centre = np.array([t[-1]['lean'][0], tops[-1], Z_AXIS+t[-1]['lean'][1]])
    land_centre = np.array([(base[:, 0].min()+base[:, 0].max())/2, base[:, 1].max(), (base[:, 2].min()+base[:, 2].max())/2])
    tip = np.concatenate([land[n] for n in OUT['tip']]).copy(); tip[:, :, :3] += top_centre-land_centre
    bow = np.concatenate([v for k, v in land.items() if k.startswith('bow_')]).copy()
    bow[:, :, 2] += (Z_AXIS-(R0+.07)+.02)-(-4.76)
    return tip, bow


def worn_parts():
    """The worn hat's named parts (Marisa model frame, hat units): crown rings, the land hat's tip, its bow."""
    tip, bow = tip_and_bow()
    rings = worn_rings()
    for q in rings: q['cz'] += Z_AXIS
    return [('crown', normals(orient(loop_mesh(rings, False)))), ('tip', normals(tip)), ('bow', normals(bow))]


def worn_mesh():
    return np.concatenate([t for _, t in worn_parts()])


# ------------------------------------------------------------------ set down
def depth_of_tier(y):
    """Worn height -> set-down depth below the brim face for the outside: brim straight, each tier pressed by its squash."""
    t, tops, starts = tiers()
    if y <= YB1: return y-YB0
    d = LIP
    for x, lo, hi in zip(t, starts, tops):
        d += (min(y, hi)-lo)*x['squash']
        if y <= hi: return d
    return d


def basin_curve():
    """The hollow's surface, rim to middle: (r, depth below the brim face) in hat units."""
    Ro, Rf, D = IN['opening'], IN['floor_radius'], IN['floor_depth']
    pts = [(Ro, 0.), (Ro, IN['lip'])]
    n = IN['wall_rings']
    for k in range(1, n+1):
        phi = k/n*math.pi/2
        pts.append((Rf+(Ro-Rf)*math.cos(phi), IN['lip']+(D-IN['lip'])*math.sin(phi)))
    m = IN['floor_rings']
    for k in range(1, m+1):
        u = k/m; pts.append((Rf*(1-u), D+IN['dimple']*u*u))
    return pts


def placed_rings():
    """Set down (hat units, brim face at y 0): brim with its turned-up rim, the base tier upright, the pressed tiers as a
    keel, then up through the middle and out along the basin to the opening's edge."""
    t, tops, starts = tiers(); b0, b1 = R['band']
    out = brim(False)
    out += [ring(R0, -LIP, cell='light'), ring(R0, -(LIP+b0), cell='band'), ring(R0+.07, -(LIP+b0), cell='band'),
            ring(R0+.07, -(LIP+b1), cell='band'), ring(R0, -(LIP+b1), cell=t[0]['cell'])]
    for i, x in enumerate(t):
        if i: out.append(ring(x['r'], -depth_of_tier(starts[i]), cell=x['cell']))
        out.append(ring(x['r'], -depth_of_tier(tops[i]), cell='cloth'))
    out.append(ring(0, -depth_of_tier(tops[-1]), cell='cloth'))
    curve = basin_curve()
    out += [ring(r, -d, cell='lining') for r, d in reversed(curve)][:-1]
    return out


def placed_parts():
    """The set-down hat's named parts (hat units, origin the brim face's middle): the hull, and its bow lying on the
    upright base tier."""
    _, bow = tip_and_bow()
    b = bow.copy(); x, y, z = b[:, :, 0].copy(), b[:, :, 1].copy(), b[:, :, 2]-Z_AXIS
    b[:, :, 0], b[:, :, 2] = -z, -x
    centre = (YB1+R['band'][0]+YB1+R['band'][1])/2
    b[:, :, 1] = -(LIP+R['band'][0]+(centre-YB1-R['band'][0])+(y-centre)*.6)
    return [('hull', normals(orient(loop_mesh(placed_rings(), True)))), ('bow', normals(b))]


def placed_mesh():
    return np.concatenate([t for _, t in placed_parts()])


def check_inside():
    """The basin stays inside the upright base tier with at least min_wall of cloth; its floor above that tier's bottom."""
    base_bottom = depth_of_tier(tiers()[1][0])
    problems = []
    for r, d in basin_curve():
        if d <= base_bottom and r > R0-IN['min_wall']+1e-9: problems.append(f'wall r={r:.2f} at depth {d:.2f}')
    if IN['floor_depth']+IN['dimple'] > base_bottom-IN['min_wall']: problems.append('floor below the base tier')
    if IN['opening'] >= BRIM['droop_from']: problems.append('opening reaches the turned-up rim')
    return problems


# ------------------------------------------------------------------ height fields (world units)
class Grid:
    def __init__(self, half=16., step=.125):
        self.step = step; self.n = int(round(2*half/step)); self.half = half
        c = (np.arange(self.n)+.5)*step-half
        self.x, self.z = np.meshgrid(c, c, indexing='ij')


def raster(tris, grid, up=True):
    """Highest up-facing (or lowest down-facing) surface over each grid cell; -inf / +inf where none."""
    out = np.full((grid.n, grid.n), -np.inf if up else np.inf)
    face = tris[:, 0, 6]
    sel = tris[face > 1e-4] if up else tris[face < -1e-4]
    for t in sel:
        p = t[:, :3]
        lo = np.clip(np.floor((p[:, [0, 2]].min(0)+grid.half)/grid.step).astype(int), 0, grid.n-1)
        hi = np.clip(np.ceil((p[:, [0, 2]].max(0)+grid.half)/grid.step).astype(int), 0, grid.n)
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


def world(tris):
    out = tris.copy(); out[:, :, :3] *= K; return out


def portion_volume():
    p = R['portion']; r = p['radius_blocks']*16
    return math.pi*r*r*p['height_blocks']*16


def fill_level(field, grid, volume, top=0.):
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
    img = np.zeros((16, 16, 3)); img[:] = np.array([176, 140, 78])+rng.integers(-14, 15, (16, 16, 1))
    for _ in range(26):
        x, y = rng.integers(0, 16, 2); img[y, x] = [212, 182, 104]
    for _ in range(12):
        x, y = rng.integers(0, 15, 2); img[y, x] = img[y, x+1] = [108, 150, 62]
    for _ in range(7):
        x, y = rng.integers(0, 16, 2); img[y, x] = [196, 52, 64]
    for _ in range(9):
        x, y = rng.integers(0, 16, 2); img[y, x] = [104, 72, 40]
    Image.fromarray(np.clip(img, 0, 255).astype(np.uint8)).convert('RGBA').save(path)


def summary():
    placed = world(placed_mesh()); grid = Grid()
    field = raster(placed, grid, True); low = raster(placed, grid, False)
    curve = basin_curve()
    t, tops, _ = tiers()
    v = portion_volume()
    fills = {k: round(-fill_level(field, grid, n*v), 3) for k, n in R['portion']['fills'].items()}
    return dict(
        units='world: adult model units at growth 1 (16 a block); hat: land-hat units (world = hat x scale)',
        scale=K, brim_radius=round(BRIM['radius']*K, 3), crown_radius=round(R0*K, 3), opening_radius=round(IN['opening']*K, 3),
        floor_radius=round(IN['floor_radius']*K, 3), floor_depth=round(IN['floor_depth']*K, 3),
        hollow_depth=round(-float(np.nanmin(np.where(np.isfinite(field), field, np.nan))), 3),
        keel_depth=round(-float(np.nanmin(np.where(np.isfinite(low), low, np.nan))), 3),
        rim_height=round(BRIM['droop']*K, 3), worn_height=round((tops[-1]-YB0)*K, 3),
        basin_profile=[(round(r*K, 3), round(d*K, 3)) for r, d in curve],
        stores_level_below_brim=fills, portion_volume=round(v, 3), inside_problems=check_inside())


# What the layout study and its preview read: this make-source and the runtime data its bodies and clips come from.
SOURCES = ('recipe.json', 'build.py', 'scene.py', 'layout.py', 'preview.py', 'food_layer.png', 'geometry.json', 'export.py')
RUNTIME = ('yukkuri/marisa.json', 'yukkuri_props/accessory_marisa.json', 'yukkuri_props/marisa_hat_turned.json', 'yukkuri_presentation/motion_corrections.json', 'yukkuri_presentation/hat_vessel.json',
           'yukkuri_presentation/braid_rescue.json', 'yukkuri_presentation/living.json', 'yukkuri_presentation/placement_fit.json')


def source_digest():
    """Fingerprint of the study's inputs: previews/aquatic-hat-round/source.sha256 holds it after preview.py; the preview hub
    reuses that preview only while it still matches."""
    import hashlib
    digest = hashlib.sha256()
    for path in [HERE/n for n in SOURCES]+[ASSETS/n for n in RUNTIME]+[ROOT/'standards/yukkuri-library/motions/variants/lore/preview.py']:
        digest.update(path.read_bytes().replace(b'\r\n', b'\n'))
    return digest.hexdigest()


def main():
    ap = argparse.ArgumentParser(); ap.add_argument('--check', action='store_true'); args = ap.parse_args()
    data = summary(); text = json.dumps(data, ensure_ascii=False, indent=1)+'\n'
    path = HERE/'geometry.json'
    if args.check:
        same = path.is_file() and path.read_text(encoding='utf-8') == text
        print('geometry.json', 'ok' if same else 'differs'); return 0 if same else 1
    path.write_text(text, encoding='utf-8'); food_texture(HERE/'food_layer.png')
    print(path, {k: data[k] for k in ('opening_radius', 'floor_radius', 'floor_depth', 'hollow_depth', 'keel_depth', 'inside_problems')})
    return 0


if __name__ == '__main__':
    sys.exit(main())
