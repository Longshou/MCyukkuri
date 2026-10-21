"""The aquatic Marisa's own hat: deeper, rounder, a separate object from the land Marisa hat (recipe.json).

One triangle set, authored in the turned-over vessel frame from the recipe's outlines, is written twice:
  accessory_marisa_aquatic    worn orientation, marisa.json model units (the frame of accessory_marisa and of
                              the model's own brim_/hat_/bow_ parts): worn on the head, the removed item, damage
  marisa_aquatic_hat_turned   the same triangles turned over exactly as marisa_hat_turned is (vessel frame)
and the game's stepped-cavity metrics for it go to marisa_aquatic_hat_vessel.json, in the schema of
hat_vessel.json plus convex polygon outlines (cavity_sections[].outline, brim_outline, bowl.mask_outline).
Braid placements reuse the land package's search on this hat; hat provisions reuse care-props/cargo.py on
this cavity. The land hat (marisa-hat-vessel) is read, never written. Python, numpy and Pillow only.

  python build.py [--check]
"""
from pathlib import Path
import argparse, hashlib, importlib.util, json, math, sys
import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import hull as H
ROOT = HERE.parents[3]
ASSETS = ROOT/'src/main/resources/assets/mcyukkuri'
MODEL = ASSETS/'yukkuri/marisa.json'
BODY = ASSETS/'yukkuri_presentation/body_geometry.json'
FIT = ASSETS/'yukkuri_presentation/placement_fit.json'
LAND = HERE.parent/'marisa-hat-vessel'
CARE = HERE.parents[1]/'drafts/care-props'
WORN = ASSETS/'yukkuri_props/accessory_marisa_aquatic.json'
TURNED = ASSETS/'yukkuri_props/marisa_aquatic_hat_turned.json'
PROVISIONS = ASSETS/'yukkuri_props/marisa_aquatic_hat_provisions.json'
METRICS = ASSETS/'yukkuri_presentation/marisa_aquatic_hat_vessel.json'
LAYOUT = ASSETS/'yukkuri_presentation/marisa_aquatic_hat_cargo_layout.json'
ITEM = ASSETS/'models/item/marisa_aquatic_hat.json'
INDEX = ASSETS/'yukkuri_props/subspecies-index.json'
TEXTURE = 'mcyukkuri:textures/entity/marisa/neutral.png'
PROVISIONS_TEXTURE = 'mcyukkuri:textures/props/hat_provisions.png'
LANG = ASSETS/'lang'
# Item names this package owns, merged into the shared language files like the other package exporters do (zh, en, ja).
LABELS = {'marisa_aquatic_hat': ('水生魔理沙的帽子', "Aquatic Marisa's Hat", '水上まりさのぼうし')}
R = H.R


def sha(data): return hashlib.sha256(data if isinstance(data, bytes) else data.encode()).hexdigest()


def load(name, path):
    spec = importlib.util.spec_from_file_location(name, path); module = importlib.util.module_from_spec(spec)
    sys.path.insert(0, str(path.parent)); spec.loader.exec_module(module); return module


LH = load('land_hat_build', LAND/'build.py')  # the land package: worn boxes, anchor, braid placement search


# ------------------------------------------------------------------ mesh (vessel frame)
class Mesh:
    def __init__(self, size): self.parts, self.size = {}, size

    def uv(self, cell):
        u0, v0, u1, v1 = cell; s = self.size; e = .5
        return [[(u0+e)/s, (v1-e)/s], [(u1-e)/s, (v1-e)/s], [(u1-e)/s, (v0+e)/s], [(u0+e)/s, (v0+e)/s]]

    def quad(self, part, pts, cell, normals=None, uvs=None):
        """Two triangles (0,1,2)(0,2,3) as the land props; a quad with one edge collapsed draws as a triangle."""
        P = [np.asarray(p, float) for p in pts]
        area = np.linalg.norm(np.cross(P[2]-P[0], P[1]-P[0]))+np.linalg.norm(np.cross(P[3]-P[0], P[2]-P[0]))
        if area < 1e-8: return
        if normals is None:
            n = np.cross(P[1]-P[0], P[3]-P[0]) if np.linalg.norm(np.cross(P[1]-P[0], P[3]-P[0])) > 1e-9 else np.cross(P[2]-P[1], P[3]-P[1])
            normals = [n/np.linalg.norm(n)]*4
        uvs = uvs or self.uv(cell)
        # Wind counter-clockwise about the stated normal: the damage renderer and the badge exporter read the winding.
        face = np.cross(P[1]-P[0], P[2]-P[0])+np.cross(P[2]-P[0], P[3]-P[0])
        if face @ np.sum(np.asarray(normals, float), axis=0) < 0:
            P, normals, uvs = [P[i] for i in (0, 3, 2, 1)], [normals[i] for i in (0, 3, 2, 1)], [uvs[i] for i in (0, 3, 2, 1)]
        rows = self.parts.setdefault(part, [])
        for i in (0, 1, 2, 0, 2, 3): rows.append(list(P[i])+list(uvs[i])+list(np.asarray(normals[i], float)))

    def flat(self, transform=None):
        flat, parts = [], []
        for name, rows in self.parts.items():
            parts.append(dict(name=name, start=len(flat)//8, count=len(rows), texture=TEXTURE))
            for row in rows:
                p, uv, n = np.array(row[:3]), row[3:5], np.array(row[5:8])
                if transform: p, n = transform(p, n)
                flat.extend(round(float(x), 5)+0. for x in list(p)+list(uv)+list(n))
        P = np.array(flat).reshape(-1, 8)[:, :3]
        return {'format': 1, 'units_per_block': 16, 'vertices': flat, 'parts': parts,
                'bounds': [[round(float(P[:, i].min()), 5), round(float(P[:, i].max()), 5)] for i in range(3)]}


def unit(v): v = np.asarray(v, float); n = np.linalg.norm(v); return v/n if n > 1e-12 else v


def outward(points, half, n):
    """Horizontal outward normals of a superellipse ring at its vertices."""
    out = []
    for x, z in points:
        gx = math.copysign(abs(x/half[0])**(n-1)/half[0], x); gz = math.copysign(abs(z/half[1])**(n-1)/half[1], z)
        out.append(unit([gx, 0, gz]))
    return out


def lift(ring2, y): return [np.array([x, y, z]) for x, z in ring2]


def band_ring(mesh, part, inner, outer, y, cell, up):
    m = len(inner)
    for k in range(m):
        j = (k+1) % m
        pts = [[*inner[k][:1], y, inner[k][1]], [inner[j][0], y, inner[j][1]], [outer[j][0], y, outer[j][1]], [outer[k][0], y, outer[k][1]]]
        mesh.quad(part, pts if up else pts[::-1], cell, [[0, 1 if up else -1, 0]]*4)


def skin(mesh, part, grid, cell, outward_hint=None):
    """Quads between consecutive rings (rows of the same vertex count), smooth vertex normals."""
    G = np.array(grid); rows, m = G.shape[:2]
    du = np.roll(G, -1, axis=1)-np.roll(G, 1, axis=1)
    dv = np.zeros_like(G); dv[1:-1] = G[2:]-G[:-2]; dv[0] = G[1]-G[0]; dv[-1] = G[-1]-G[-2]
    N = np.cross(dv, du)
    for i in range(rows):
        for k in range(m):
            n = N[i, k]
            if np.linalg.norm(n) < 1e-9: n = outward_hint(G[i, k]) if outward_hint else np.array([0, 1., 0])
            if outward_hint is not None and np.dot(n, outward_hint(G[i, k])) < 0: n = -n
            N[i, k] = unit(n)
    for i in range(rows-1):
        for k in range(m):
            j = (k+1) % m
            mesh.quad(part, [G[i, k], G[i, j], G[i+1, j], G[i+1, k]], cell, [N[i, k], N[i, j], N[i+1, j], N[i+1, k]])


def wall(mesh, part, ring2, y0, y1, cell):
    """A vertical riser along an outline from y0 down to y1, facing the hat axis."""
    m = len(ring2)
    for k in range(m):
        j = (k+1) % m
        a, b = ring2[k], ring2[j]
        if math.hypot(b[0]-a[0], b[1]-a[1]) < 1e-9: continue
        n = unit([-(b[1]-a[1]), 0, b[0]-a[0]])  # left of a counter-clockwise edge: toward the axis
        mesh.quad(part, [[a[0], y0, a[1]], [b[0], y0, b[1]], [b[0], y1, b[1]], [a[0], y1, a[1]]], cell, [n]*4)


def fill(mesh, part, ring2, y, cell):
    """A convex floor zipped from its first ray across to the opposite one, as quads."""
    m = len(ring2); start = int(np.argmin(np.abs(H.PHI-math.pi))); half = m//2
    left = [ring2[(start-i) % m] for i in range(half+1)]; right = [ring2[(start+i) % m] for i in range(m-half+1)]
    steps = min(len(left), len(right))-1
    for i in range(steps):
        a, b, c, d = left[i], right[i], right[i+1], left[i+1]
        mesh.quad(part, [[a[0], y, a[1]], [d[0], y, d[1]], [c[0], y, c[1]], [b[0], y, b[1]]], cell, [[0, 1, 0]]*4)


def tube(mesh, centre, radius, sides, cells):
    """A soft tapering tip along a bent centre line (parallel-transported rings), ending in a point."""
    C = [np.array(c, float) for c in centre]; tangents = []
    for i in range(len(C)):
        tangents.append(unit(C[min(i+1, len(C)-1)]-C[max(i-1, 0)]))
    ref = unit(np.cross(tangents[0], [1, 0, 0])); rings = []
    for i, (c, t, r) in enumerate(zip(C, tangents, radius)):
        ref = unit(ref-np.dot(ref, t)*t); other = np.cross(t, ref)
        rings.append([c+r*(math.cos(a)*ref+math.sin(a)*other) for a in np.linspace(0, math.tau, sides, endpoint=False)])
    for i in range(len(rings)-1):
        part, cell = cells[i]
        for k in range(sides):
            j = (k+1) % sides
            P = [rings[i][k], rings[i][j], rings[i+1][j], rings[i+1][k]]
            ns = [unit(p-C[i if q < 2 else i+1]) for q, p in enumerate(P)]
            mesh.quad(part, P, cell, ns)
    part, cell = cells[-1]
    for k in range(sides):  # close the point
        j = (k+1) % sides; end = C[-1]+tangents[-1]*radius[-1]
        mesh.quad(part, [rings[-1][k], rings[-1][j], end, end], cell, [unit(rings[-1][k]-C[-1]), unit(rings[-1][j]-C[-1]), tangents[-1], tangents[-1]])


def crown_x(z, depth):
    """How far the outside of the crown reaches toward +x at this z and depth."""
    half, n = H.crown_ring(depth)
    return 0. if abs(z) >= half[1] else half[0]*(1-abs(z/half[1])**n)**(1/n)


def bow(mesh, model, anchor):
    """The land hat's own bow, wrapped onto the rounder band: every vertex moves out along x by how far this band's
    front stands beyond the land band's front at that vertex's z (a shear along z). Boxes that touch on the land hat
    still touch, and each keeps its own depth into or height above the band; the triangles and UVs are the source's."""
    V = np.array(model['vertices']).reshape(-1, 8)
    names = {p['name']: p for p in model['parts']}
    band = names['hat_band']; b = V[band['start']:band['start']+band['count'], :3]
    old_band = LH.worn_to_vessel(np.array([0, b[:, 1].min(), b[:, 2].min()]), anchor)[0]  # the land band's flat front
    half, n = H.crown_ring(R['band']['from']); drawn = H.ring([half[0]+R['band']['out'], half[1]+R['band']['out']], n)
    front = [(p, q) for p, q in zip(drawn, np.roll(drawn, -1, 0)) if p[0] > 0 and q[0] > 0 and (p[1]-q[1]) != 0]

    def band_x(z):
        """The drawn (faceted) band front at this z, so every bow vertex keeps its land distance to a real face."""
        for p, q in front:
            if min(p[1], q[1]) <= z <= max(p[1], q[1]): return p[0]+(q[0]-p[0])*(z-p[1])/(q[1]-p[1])
        raise ValueError(f'bow beyond the band front at z={z}')
    shift = lambda z: band_x(z)-old_band
    for name, p in names.items():
        if not name.startswith(R['bow']['source_parts']): continue
        rows = V[p['start']:p['start']+p['count']]
        P = LH.worn_to_vessel(rows[:, :3], anchor); N = np.array([LH.worn_to_vessel(n+anchor, anchor) for n in rows[:, 5:8]])
        on_band = [p for p in P if R['band']['from'] <= -p[1] <= R['band']['to']]
        stand = min(p[0]-old_band for p in on_band) if on_band else None  # its standoff from the flat land band
        P[:, 0] += [shift(z) for z in P[:, 2]]
        if stand is not None and stand > 0:
            # a straight face spans the faceted round band as a chord; keep the same standoff from every band vertex under it
            z0, z1 = P[:, 2].min(), P[:, 2].max(); x0 = lambda z: min(p[0] for p in P if abs(p[2]-z) < 1e-6)
            inner = lambda z: x0(z0)+(x0(z1)-x0(z0))*(z-z0)/(z1-z0)
            deficit = max([band_x(q[1])+stand-inner(q[1]) for q in drawn if q[0] > 0 and z0 < q[1] < z1]+[0.])
            P[:, 0] += deficit
        target = mesh.parts.setdefault(name, [])  # the source triangles, only moved; normals of the sheared faces
        for t in range(0, len(P), 3):
            order = (t, t+1, t+2); n = unit(np.cross(P[t+1]-P[t], P[t+2]-P[t]))
            if n @ N[t] < 0: order, n = (t, t+2, t+1), -n  # wound about the source's outward normal
            for i in order: target.append(list(P[i])+rows[i, 3:5].tolist()+list(n))


def author(model, anchor):
    mesh = Mesh(model['texture_size'])
    C = R['cells']; tb = R['brim']['thickness']
    B = H.brim_outline(); sections = H.sections(); O = sections[0][1]
    top, top_n = H.crown_ring(tb)
    E0 = H.ring(top, top_n)
    # brim: top face round the opening, outer edge, underside round the crown
    band_ring(mesh, 'brim_top', O, B, 0., C['cloth'], True)
    edge_n = outward(B, R['brim']['half'], R['brim']['exponent'])
    for k in range(len(B)):
        j = (k+1) % len(B)
        mesh.quad('brim_edge', [[B[k][0], 0, B[k][1]], [B[k][0], -tb, B[k][1]], [B[j][0], -tb, B[j][1]], [B[j][0], 0, B[j][1]]],
                  C['dark'], [edge_n[k], edge_n[k], edge_n[j], edge_n[j]])
    band_ring(mesh, 'brim_under', E0, B, -tb, C['cloth'], False)
    # outside of the crown down to the keel
    rings = [lift(H.ring(*H.crown_ring(d)), -d) for d in R['crown']['rings']]
    rings.append([np.array([0., -R['crown']['keel'], 0.])]*len(H.PHI))
    skin(mesh, 'hat_crown', rings, C['cloth'], lambda p: unit([p[0], 0, p[2]]) if math.hypot(p[0], p[2]) > 1e-6 else np.array([0, -1., 0]))
    # hat band standing a little proud of the crown
    Bd = R['band']; bh, bn = H.crown_ring(Bd['from'])
    band = H.ring([bh[0]+Bd['out'], bh[1]+Bd['out']], bn); inner_band = H.ring(bh, bn)
    band_n = outward(band, [bh[0]+Bd['out'], bh[1]+Bd['out']], bn)
    for k in range(len(band)):
        j = (k+1) % len(band)
        mesh.quad('hat_band', [[band[k][0], -Bd['from'], band[k][1]], [band[k][0], -Bd['to'], band[k][1]], [band[j][0], -Bd['to'], band[j][1]],
                               [band[j][0], -Bd['from'], band[j][1]]], C['band'], [band_n[k], band_n[k], band_n[j], band_n[j]])
    band_ring(mesh, 'hat_band', inner_band, band, -Bd['from'], C['band'], True)
    band_ring(mesh, 'hat_band', inner_band, band, -Bd['to'], C['band'], False)
    # the well: risers and treads, all lining
    above = 0.
    for i, (name, outline, depth) in enumerate(sections):
        wall(mesh, 'hat_lining_wall', outline, -above, -depth, C['dark'])
        if i+1 < len(sections): band_ring(mesh, 'hat_lining_tread', sections[i+1][1], outline, -depth, C['dark'], True)
        else: fill(mesh, 'hat_lining_floor', outline, -depth, C['dark'])
        above = depth
    bow(mesh, model, anchor)
    T = R['tip']; segments = len(T['centre'])-1
    tube(mesh, T['centre'], T['radius'], T['sides'], [('hat_tip', C['cloth'])]*(segments-1)+[('hat_tip_end', C['dark'])])
    return mesh


# ------------------------------------------------------------------ placements (the land package's search)
class Turned:
    """Duck type of the land build's Turned for this hat (vessel frame, hat units)."""

    def __init__(self, keel, brim_rects, B, O):
        self.keel = keel; self.opening = H.bbox(O); self.brim = [float(abs(B[:, 0]).max()), float(abs(B[:, 1]).max())]
        self.brim_rects = brim_rects; self.B = B
        self.squash = {'nest': R['nest']['height']/keel, 'bowl': R['afloat']['squash']}

    def lift(self, kind, k): return self.keel*self.squash['nest']*k if kind == 'nest' else R['afloat']['freeboard']*k

    def rim_points(self, n=36):
        out = []
        for phi in np.linspace(0, math.tau, n, endpoint=False):
            r = H.superellipse(phi, R['opening']['half'], R['opening']['exponent'])+R['rim_out']
            out.append([r*math.cos(phi), 0., r*math.sin(phi)])
        return np.array(out)


def grips_on(B, crown_top, anchor):
    """Brim points the braid can hold on the head: on the brim, clear of the crown (replaces head_grips' box test)."""
    def head_grips(hat, a, root, length, k):
        out = []
        for x in np.arange(5.5, 10.2, .5):
            for z in np.arange(-6.9, 6.8, .5):
                g = np.array([x, 10.85, z]); v = LH.worn_to_vessel(g, anchor)
                if H.inside(B, v[0], v[2], -.05) and not H.inside(crown_top, v[0], v[2], .3) \
                        and .65 <= LH.reach(root, length, a+k*(g-a)) <= 1.2:
                    out.append(g)
        return out
    return head_grips


def placements(model, body, t, B, crown_top, anchor):
    LH.head_grips = grips_on(B, crown_top, anchor)
    model_root, length = LH.braid(model)
    pivot = np.array(model['bones'][[b['name'] for b in model['bones']].index('root')]['pivot'])
    bowed = LH.rot_x(math.radians(LH.R['self_bow']))@(model_root-pivot)+pivot
    half = LH.body_half(body); exit_front = half+.15*16
    rnd = lambda v: round(float(v), 4)

    def near(yaw_rel, k):
        Rm = LH.rot_y(math.radians(-yaw_rel))
        return max((Rm@np.array([x, 0, z]))[2] for x, z in B)*k
    k = R['aquatic_scale']
    origin = np.array([0., -LH.R['float']['bank_drop'], -half-t.brim[0]*k+LH.R['float']['under_bank']])
    helper, mine = LH.place('aquatic', 'bowl', t, k, lambda role, yaw: origin, None, anchor, model_root, bowed, length, half,
                            -half+LH.R['float']['helper_back'])
    for p in (helper, mine): p['near_brim'] = rnd(near(p['hat_in_mother']['yaw'], 1.))
    return {'aquatic': dict(scale=k, helper=helper, self=mine, mother_half=rnd(half), exit_front=rnd(exit_front))}, model_root, length


# ------------------------------------------------------------------ cargo (care-props/cargo.py on this cavity)
class _Vessel:
    """Lets cargo.py read this hat's metrics in place of hat_vessel.json."""

    def __init__(self, text): self.text = text

    def read_text(self, encoding=None): return self.text

    def relative_to(self, root): return METRICS.relative_to(root)


def cargo(metrics_text, V):
    sys.path.insert(0, str(CARE)); cg = load('care_cargo', CARE/'cargo.py')
    cg.VESSEL_PATH = _Vessel(metrics_text)
    land = cg.placements
    hull = H.Hull(V, 'bowl'); src = cg._source(); pts = np.array(cg._points(src)); y0 = pts[:, 1].min()

    def fits(p):
        """Every vertex of the scaled pile inside the cavity wall that bounds its height (bowl mode heights)."""
        sx, sy, sz = p['scale']; q = np.c_[pts[:, 0]*sx+p['x'], (pts[:, 1]-y0)*sy+p['bottom'], pts[:, 2]*sz+p['z']]
        return all(hull.contains(x, hull.top+y*hull.sq, z, 0, 0) for x, y, z in q)

    def aquatic():
        inside, aside = land()
        for group in inside:
            for p in group:  # on the real deep floor, the whole pile shrunk uniformly until it clears every tread
                p['x'], p['z'] = 0., 0.; g = 1.
                while not fits(dict(p, scale=[s*g for s in p['scale']])) and g > .3: g -= .02
                p['scale'] = [round(s*g, 4) for s in p['scale']]
        spots = {}
        for group in aside:
            for p in group:
                sx, _, sz = p['scale']; r = max(math.hypot(a*sx, c*sz) for a, _, c in pts)
                key = (p['x'] > 0); spots[key] = max(spots.get(key, 0), r)
        for group in aside:
            for p in group:  # the side stow moves out onto this wider brim, clear of the rounder opening
                side = 1 if p['x'] > 0 else -1; r = spots[p['x'] > 0]
                (a, b), n = R['opening']['half'], R['opening']['exponent']
                ox = a*max(0., 1-abs(p['z']/b)**n)**(1/n)
                p['x'] = round(side*(ox+r+R['cargo']['aside_margin']), 3)
        return inside, aside
    cg.placements = aquatic
    layout = cg.layout(); layout['source'] = 'standards/yukkuri-library/interactions/marisa-aquatic-hat/build.py (care-props/cargo.py on this cavity)'
    layout['visual_prop'] = 'marisa_aquatic_hat_provisions'
    prop = cg.provisions(); prop.name = 'marisa_aquatic_hat_provisions'
    mesh = prop.mesh()
    shipped = ASSETS/'textures/props/hat_provisions.png'
    from PIL import Image
    import io
    same = np.array_equal(np.array(Image.open(io.BytesIO(prop.png())).convert('RGBA')), np.array(Image.open(shipped).convert('RGBA')))
    if not same: raise SystemExit('hat_provisions texture differs from the shipped one; cannot share it')
    for part in mesh['parts']: part['texture'] = PROVISIONS_TEXTURE
    return layout, mesh


# ------------------------------------------------------------------ metrics
def fit_offset(model):
    """The runtime placement fit's shift of the worn hat (marisa: hat parts moved as one)."""
    fit = json.loads(FIT.read_text(encoding='utf-8'))['models']['marisa']
    V = np.array(model['vertices']).reshape(-1, 8)
    for p in fit['parts']:
        if p['name'] == 'hat_base':
            part = model['parts'][p['part_index']]
            return [round(float(x), 4) for x in np.array(p['vertices']).reshape(-1, 5)[:, :3].min(0)-V[part['start']:part['start']+part['count'], :3].min(0)]


def badge(anchor):
    """The land hat's badge sits on the band front (art/props/badges, accessory_marisa); same height, on this band."""
    mounts = json.loads((ROOT/'art/source/rounded-runtime/attachments.json').read_text(encoding='utf-8'))
    spec = dict(mounts['badge_mounts']['detached']['accessory_marisa'])
    v = LH.worn_to_vessel(np.array(spec['point']), anchor); half, n = H.crown_ring(-v[1])
    x = crown_x(v[2], -v[1])+R['band']['out']
    w = anchor+np.array([-v[2], -v[1], -x])
    n = outward([(x, v[2])], [half[0]+R['band']['out'], half[1]+R['band']['out']], n)[0]  # the band's own facing there
    spec['point'] = [round(float(c), 4) for c in w]; spec['normal'] = [round(float(c), 4)+0. for c in (-n[2], -n[1], -n[0])]; return spec


def main():
    ap = argparse.ArgumentParser(); ap.add_argument('--check', action='store_true'); args = ap.parse_args()
    model = json.loads(MODEL.read_text(encoding='utf-8')); body = json.loads(BODY.read_text(encoding='utf-8'))['variants']['marisa']
    worn = LH.worn_boxes(model)
    anchor = np.array([0, min(b['lo'][1] for n, b in worn.items() if n.startswith('brim_')), (worn['hat_base']['lo'][2]+worn['hat_base']['hi'][2])/2])
    mesh = author(model, anchor)
    turned = mesh.flat()
    to_worn = lambda p, n: (anchor+np.array([-p[2], -p[1], -p[0]]), np.array([-n[2], -n[1], -n[0]]))
    wornp = mesh.flat(to_worn)
    P = np.array(turned['vertices']).reshape(-1, 8)[:, :3]
    keel = round(-float(P[:, 1].min()), 4)
    B = H.brim_outline(); sections = H.sections(); O = sections[0][1]
    rnd = lambda v: [round(float(x), 4) for x in v]; poly = lambda a: [[round(float(x), 5), round(float(z), 5)] for x, z in a]
    brim_rects = H.strips(B, .5)
    t = Turned(keel, brim_rects, B, O)
    crown_top = H.ring(*H.crown_ring(R['brim']['thickness']))
    place, model_root, length = placements(model, body, t, B, crown_top, anchor)
    sq = R['afloat']['squash']; fb = R['afloat']['freeboard']; x0, x1, z0, z1 = rnd(H.bbox(O))
    D = LH.R['delivery']
    metrics = {
        'note': __doc__.splitlines()[0], 'status': R['status'],
        'source': HERE.relative_to(ROOT).as_posix(), 'source_model': MODEL.relative_to(ROOT).as_posix(), 'source_model_sha256': sha(MODEL.read_bytes()),
        'prop': TURNED.stem, 'worn_prop': WORN.stem, 'provisions_prop': PROVISIONS.stem, 'cargo_layout': LAYOUT.relative_to(ROOT).as_posix(),
        'aquatic_scale': R['aquatic_scale'], 'anchor_worn': rnd(anchor),
        'turned_from_worn': 'vessel = (-(w.z-a.z), -(w.y-a.y), -(w.x-a.x)) with a = anchor_worn (the transform of marisa_hat_turned)',
        'keel': keel, 'squash': {'nest': round(R['nest']['height']/keel, 4), 'bowl': sq},
        'opening': rnd(H.bbox(O)), 'well': rnd(H.bbox(sections[2][1])), 'brim': rnd(t.brim),
        'floor': sections[-1][2], 'ledge': R['ledge']['depth'],
        'cavity_sections': [dict(name=n, rect=rnd(H.bbox(o)), depth=d, outline=poly(o), strips=H.strips(o, .25)) for n, o, d in sections],
        'cavity_note': 'outline is authoritative: a convex polygon, counter-clockwise from +x toward +z, the exact drawn riser. rect is only its bounding box (a search range), not a stand-in. strips are rectangles inside the outline covering it to within a 0.25 band, for solid wall boxes (HatCareGeometry): everything outside them is wall.',
        'nest': {'height': R['nest']['height']},
        'bowl': {'freeboard': fb, 'owner_y': round(fb-R['ledge']['depth']*sq, 4),
                 'mask': [[x0, z0], [x1, z0], [x1, z1], [x0, z1]], 'mask_outline': poly(O),
                 'mask_note': 'mask (four points, the opening bounding box) for the current quad pass; mask_outline is the true opening for a fan.'},
        'brim_rects': brim_rects, 'brim_outline': poly(B),
        'brim_note': 'brim_rects are rectangles inside the round brim covering it to within 0.5 units, for code that reads rectangles; brim_outline is exact.',
        'braid': {'root': rnd(model_root), 'length': round(length, 4)},
        'delivery': {k: v for k, v in D.items() if k not in ('gap', 'gap_lore', 'self_reach')},
        'placements': place, 'float': LH.R['float'], 'pregnancy': LH.R['pregnancy'],
        'worn': {'frame': 'marisa.json model units before placement fit, as accessory_marisa and the model hat parts',
                 'replaces_parts': ['brim_', 'hat_', 'bow_'], 'bone': 'hat', 'bow_bone': 'bow', 'placement_fit_offset': fit_offset(model),
                 'hat_scale_about': 'anchor_worn, by aquatic_scale, as the model hat parts today'},
        'badge_mount': badge(anchor),
    }
    metrics_text = json.dumps(metrics, ensure_ascii=False, indent=2)+'\n'
    layout, provisions = cargo(metrics_text, json.loads(metrics_text))
    index = json.loads(INDEX.read_text(encoding='utf-8'))
    for name in (WORN.stem, TURNED.stem, PROVISIONS.stem): index.setdefault(name, {})
    item = {'parent': 'minecraft:builtin/entity', 'gui_light': 'front', 'textures': {'particle': 'mcyukkuri:entity/marisa/neutral'},
            'display': {'gui': {'rotation': [25, 225, 0], 'translation': [0, 0, 0], 'scale': [.85, .85, .85]},
                        'ground': {'translation': [0, 3, 0], 'scale': [.5, .5, .5]}, 'fixed': {'scale': [.75, .75, .75]},
                        'thirdperson_righthand': {'rotation': [0, 0, 0], 'translation': [0, 3, 0], 'scale': [.5, .5, .5]},
                        'firstperson_righthand': {'rotation': [0, 0, 0], 'translation': [0, 2, 0], 'scale': [.6, .6, .6]}}}
    compact = lambda d: json.dumps(d, separators=(',', ':'))+'\n'
    texts = {WORN: compact(wornp), TURNED: compact(turned), PROVISIONS: compact(provisions), METRICS: metrics_text,
             LAYOUT: compact(layout), ITEM: compact(item), INDEX: json.dumps(index, separators=(',', ':'))}
    shared = {INDEX}
    for i, locale in enumerate(('zh_cn', 'en_us', 'ja_jp')):
        path = LANG/f'{locale}.json'; data = json.loads(path.read_text(encoding='utf-8'))
        data.update({'item.mcyukkuri.'+key: names[i] for key, names in LABELS.items()})
        texts[path] = json.dumps(data, ensure_ascii=False, indent=2)+'\n'; shared.add(path)
    manifest = {'note': 'Runtime outputs of build.py', 'recipe_sha256': sha((HERE/'recipe.json').read_bytes()),
                'build_sha256': sha((HERE/'build.py').read_bytes()), 'hull_sha256': sha((HERE/'hull.py').read_bytes()),
                'land_build_sha256': sha((LAND/'build.py').read_bytes()), 'cargo_sha256': sha((CARE/'cargo.py').read_bytes()),
                'source_model_sha256': metrics['source_model_sha256'],
                'outputs': {p.relative_to(ROOT).as_posix(): sha(t) for p, t in texts.items() if p not in shared},
                'language_keys': {'item.mcyukkuri.'+key: list(names) for key, names in LABELS.items()}}
    texts[HERE/'manifest.json'] = json.dumps(manifest, ensure_ascii=False, indent=2)+'\n'
    for path, text in texts.items():
        if args.check:
            assert path.read_text(encoding='utf-8') == text, f'stale {path.relative_to(ROOT)}'
        else:
            path.write_text(text, encoding='utf-8')
    print('aquatic hat', 'checked' if args.check else 'written', len(turned['vertices'])//8, 'vertices; keel', keel,
          'floor', metrics['floor'], 'opening', metrics['opening'], 'brim', metrics['brim'])
    a = place['aquatic']
    print('aquatic', {r: {k: a[r][k] for k in ('at_mother', 'angle', 'yaw', 'reach', 'grip_worn', 'near_brim', 'offset') if k in a[r]} for r in ('helper', 'self')})
    print('cargo inside', [[(p['x'], p['z'], p['depth'], p['radius']) for p in g] for g in layout['tiers']])
    print('cargo aside', [[(p['x'], p['z'], p['radius']) for p in g] for g in layout['aside_tiers']])


if __name__ == '__main__':
    main()
