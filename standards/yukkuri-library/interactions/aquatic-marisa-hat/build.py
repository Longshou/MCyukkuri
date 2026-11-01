"""The aquatic Marisa's hat: worn on her head, set down brim up as her boat, a lily pad on the water (make-source;
export.py exports it).

Outside it is round stepped tiers (the land hat's tiers turned round) under the land hat's own bent tip, a band and the
land bow at the front. Set down it is the worn hat flipped at the same size: the base tier stands upright round a dry basin
below the water, the tiers under it are pressed into a keel, and the brim lies on the water (recipe set_down: the brim
level from the opening to droop_from, its outer part falling to a fixed presentation waterline). The basin inside (a
straight lip the brim's thickness, a quarter-ellipse wall, a level floor with a shallow dimple) is its own shape; the
cloth between basin and tiers is as thick as the two leave. Faces take the Marisa atlas cells the land hat uses.

Frames. Worn: the Marisa model frame in hat units, the hat's axis through anchor_worn. Set down: hat units, origin at the
middle of the brim face that sat on the head, +Y out of the hollow, the worn front (bow) toward +X and the worn +X (the
bent tip's side) toward -Z (the bow of the boat, where the owner faces). World: model units of an adult at growth 1 (16 a
block), set-down hat units times scale.

    python build.py [--set key.path=value ...]   # writes geometry.json; with --set prints only
    python build.py --check                      # compares without writing

override() (and --set) merges recipe changes in memory for a block, so export.py, layout.py and the gates can try sizes
without writing any file.
"""
from pathlib import Path
import argparse, contextlib, copy, json, math, sys
import numpy as np

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
ASSETS = ROOT/'src/main/resources/assets/mcyukkuri'
RECIPE = json.loads((HERE/'recipe.json').read_text(encoding='utf-8'))


def use(recipe):
    """Derive the module's sizes from a recipe (hat units)."""
    global R, K, W, BRIM, OUT, IN, Z_AXIS, SEG, YB0, YB1, LIP, R0, WATER, DIP, HALF
    R = recipe; K = R['scale']; W = R['wall']; BRIM = R['brim']; OUT = R['outside']; IN = R['inside']
    Z_AXIS = R['anchor_worn'][2]; SEG = R['segments']; YB0, YB1 = BRIM['bottom'], BRIM['top']; LIP = YB1-YB0
    R0 = OUT['tiers'][0]['r']; WATER, DIP = R['set_down']['waterline'], R['set_down']['edge_dip']
    HALF = math.ceil(BRIM['radius']*K)+4.   # study grids reach past the brim (world units, whole numbers)


def merged(base, changes):
    out = copy.deepcopy(base)
    for k, v in changes.items(): out[k] = merged(out[k], v) if isinstance(v, dict) and isinstance(out.get(k), dict) else v
    return out


@contextlib.contextmanager
def override(changes):
    """Recipe changes (nested dicts merge) for the block, nothing written: `with override({'brim': {'radius': 9.5}}): ...`."""
    old = R; use(merged(R, changes))
    try: yield R
    finally: use(old)


def parse_sets(items):
    """['set_down.waterline=0.4', ...] -> nested changes (values parsed as JSON)."""
    out = {}
    for item in items or ():
        path, value = item.split('=', 1); keys = path.split('.'); d = out
        for k in keys[:-1]: d = d.setdefault(k, {})
        d[keys[-1]] = json.loads(value)
    return out


use(RECIPE)
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
    """The brim ring: flat from the crown out to droop_from, then drooping to its edge worn; set down the same cloth lies
    on the water, its outer part falling to the waterline with its edge just under it (no raised rim)."""
    Rb, dr, r1 = BRIM['radius'], BRIM['droop'], BRIM['droop_from']
    if worn:
        return [ring(R0-W, YB0, cell='cloth'), ring(r1, YB0, cell='cloth'), ring(Rb, YB0-dr, cell='edge'),
                ring(Rb, YB1-dr, cell='cloth'), ring(r1, YB1, cell='cloth')]
    edge = -(WATER+DIP)
    return [ring(IN['opening'], 0, cell='cloth'), ring(r1, 0, cell='cloth'), ring(Rb, edge, cell='edge'),
            ring(Rb, edge-LIP, cell='cloth'), ring(r1, -LIP, cell='cloth')]


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
    """Set down (hat units, brim face at y 0): brim lying on the water, the base tier upright, the pressed tiers as a
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


def section():
    """The set-down hull's cut (world units): a closed loop of [r, y] through the brim, tiers, keel and basin, the same at
    every bearing (round; the bow is a patch on the base tier under the brim)."""
    return np.array([[q['r']*K, q['y']*K] for q in placed_rings()])


def check_inside():
    """The basin stays inside the upright base tier with at least min_wall of cloth, its floor above that tier's bottom
    and its opening short of where the brim starts to fall; the waterline within the brim's thickness."""
    base_bottom = depth_of_tier(tiers()[1][0]); problems = []
    for r, d in basin_curve():
        if d <= base_bottom and r > R0-IN['min_wall']+1e-9: problems.append(f'wall r={r:.2f} at depth {d:.2f}')
    if IN['floor_depth']+IN['dimple'] > base_bottom-IN['min_wall']: problems.append('floor below the base tier')
    if IN['opening'] >= BRIM['droop_from']: problems.append('opening reaches the falling brim')
    if WATER >= LIP: problems.append('waterline under the brim cloth')
    return problems


# ------------------------------------------------------------------ height fields (world units)
class Grid:
    def __init__(self, half=None, step=.125):
        half = HALF if half is None else half
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


def in_opening(x, z):
    """Within the opening (world units)."""
    return x*x+z*z < (IN['opening']*K)**2


def world(tris):
    out = tris.copy(); out[:, :, :3] *= K; return out


def summary():
    placed = world(placed_mesh()); grid = Grid()
    field = raster(placed, grid, True); low = raster(placed, grid, False)
    t, tops, _ = tiers()
    r = lambda v: round(v*K, 3)
    return dict(
        units='world: adult model units at growth 1 (16 a block); hat: land-hat units (world = hat x scale); set down round',
        scale=K, brim_radius=r(BRIM['radius']), brim_level_to=r(BRIM['droop_from']), crown_radius=r(R0),
        opening_radius=r(IN['opening']), floor_radius=r(IN['floor_radius']), floor_depth=r(IN['floor_depth']),
        hollow_depth=round(-float(np.nanmin(np.where(np.isfinite(field), field, np.nan))), 3),
        keel_depth=round(-float(np.nanmin(np.where(np.isfinite(low), low, np.nan))), 3),
        waterline=r(-WATER), brim_edge_top=r(-(WATER+DIP)), worn_height=r(tops[-1]-YB0),
        basin_profile=[(r(a), r(d)) for a, d in basin_curve()], inside_problems=check_inside())


# What the preview reads: this make-source and the runtime data its bodies and clips come from.
SOURCES = ('recipe.json', 'build.py', 'scene.py', 'layout.py', 'preview.py', 'food_layer.png', 'geometry.json', 'export.py')
RUNTIME = ('yukkuri/marisa.json', 'yukkuri_presentation/motion_corrections.json', 'yukkuri_presentation/braid_rescue.json',
           'yukkuri_presentation/living.json', 'yukkuri_presentation/placement_fit.json')


def source_digest():
    """Fingerprint of the preview's inputs: previews/aquatic-hat-seats/source.sha256 holds it after preview.py; the
    preview hub publishes that preview only while it still matches."""
    import hashlib
    digest = hashlib.sha256()
    for path in [HERE/n for n in SOURCES]+[ASSETS/n for n in RUNTIME]+[ROOT/'standards/yukkuri-library/motions/variants/lore/preview.py']:
        digest.update(path.read_bytes().replace(b'\r\n', b'\n'))
    return digest.hexdigest()


def main():
    ap = argparse.ArgumentParser(); ap.add_argument('--check', action='store_true')
    ap.add_argument('--set', action='append', metavar='KEY=VALUE', help='recipe change in memory (prints, writes nothing)')
    args = ap.parse_args()
    with override(parse_sets(args.set)):
        data = summary()
    text = json.dumps(data, ensure_ascii=False, indent=1)+'\n'
    path = HERE/'geometry.json'
    if args.set: print(text); return 0
    if args.check:
        same = path.is_file() and path.read_text(encoding='utf-8') == text
        print('geometry.json', 'ok' if same else 'differs')
        return 0 if same else 1
    path.write_text(text, encoding='utf-8', newline='\n')
    print(path, {k: data[k] for k in ('opening_radius', 'floor_radius', 'floor_depth', 'waterline', 'keel_depth', 'inside_problems')})
    return 0


if __name__ == '__main__':
    sys.exit(main())
