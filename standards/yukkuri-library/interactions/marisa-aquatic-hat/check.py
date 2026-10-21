"""Read-only checks of the exported aquatic hat against its own metrics, and its cabin against the land hat boat.

1. accessory_marisa_aquatic and marisa_aquatic_hat_turned are the same triangles (turned_from_worn).
2. Vertical rays through the opening hit the drawn tread exactly where the polygon sections say (both modes).
3. Sections are convex, nested and deepening; the crown is at least 0.5 thick round every tier.
4. Both braid grips lie on the brim, clear of the crown; the badge point is on the band.
5. Every provisions vertex stays inside the cavity wall bounding its height; side heaps lie wholly on the brim.
6. Settled young (HatVessel.place rules: centre on a real tread, full collision square clear of the riser at
   that height, no overlap) pack at least as many per size as the 12-share rule can ever admit, and are
   reported beside the current hat boat (without the owner's own reservation).
7. The wrapped bow keeps the land bow's construction: every pair of bow boxes that touches or overlaps on the
   land hat still does (separating-axis test), and no bow face lies in a band face's plane over an area.
8. Materials: every part of both props names a texture that exists, and samples only opaque texels inside its UVs.
No game, browser or Java compilation.
"""
from pathlib import Path
import itertools, json, math, sys
import numpy as np
from PIL import Image

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import hull as H
ROOT = HERE.parents[3]
ASSETS = ROOT/'src/main/resources/assets/mcyukkuri'


def read(p): return json.loads((ASSETS/p).read_text(encoding='utf-8'))


def rect_poly(r): x0, x1, z0, z1 = r; return [[x0, z0], [x1, z0], [x1, z1], [x0, z1]]


def as_polygons(V):
    """The land hat's rectangles as polygons, so both hats run through the same Hull."""
    V = dict(V); V['cavity_sections'] = [dict(s, outline=s.get('outline', rect_poly(s['rect']))) for s in V['cavity_sections']]
    V.setdefault('brim_outline', rect_poly([-V['brim'][0], V['brim'][0], -V['brim'][1], V['brim'][1]])); return V


def pack(hull, r, owner=None, step=.25):
    """Greedy seats for equal young of radius r (hat units), shallow treads first, as HatVessel.place for residents."""
    half = r/.42*.5; x0, x1, z0, z1 = hull.opening; taken = [owner] if owner else []
    while True:
        best = None
        for x in np.arange(x0+r, x1-r+1e-9, step):
            for z in np.arange(z0+r, z1-r+1e-9, step):
                h = hull.footprint(x, z, r); s = hull.surface(x, z)
                if h is None or s is None or abs(s-h) > 1e-4 or not hull.contains(x, h, z, half, half): continue
                if any(math.hypot(x-a, z-c) < r+b+.04 for a, c, b in taken): continue
                cost = 4*(hull.top-h)+math.hypot(x, z)
                if best is None or cost < best[0]: best = (cost, x, z)
        if best is None: return len(taken)-(1 if owner else 0)
        taken.append((best[1], best[2], r))


def part_points(prop, prefix):
    V = np.array(prop['vertices']).reshape(-1, 8)
    return {p['name']: V[p['start']:p['start']+p['count'], :3] for p in prop['parts'] if p['name'].startswith(prefix)}


def box_gap(P, Q):
    """Largest separation of two convex boxes along any separating axis (<= 0: touching or overlapping)."""
    def axes(X):
        d = []
        for a, b in itertools.combinations(np.unique(np.round(X, 5), axis=0), 2):
            e = b-a; L = np.linalg.norm(e)
            if L < 1e-6: continue
            e = e/L
            if all(abs(abs(e@x)-1) > 1e-4 and abs(e@x) < 1e-3 for x in d): d.append(e)
            if len(d) == 3: break
        return d
    A, B = axes(P), axes(Q); best = -9.
    for n in A+B+[np.cross(a, b) for a in A for b in B]:
        L = np.linalg.norm(n)
        if L < 1e-6: continue
        p, q = P@(n/L), Q@(n/L); best = max(best, q.min()-p.max(), p.min()-q.max())
    return best


def coplanar_overlap(A, B):
    """Total area where triangles of A lie in the plane of triangles of B (angle < 2.6 deg, within 0.002) and overlap."""
    def cross2(p, q): return p[0]*q[1]-p[1]*q[0]
    def clip(a, b, n):
        u = np.cross(n, [1, 0, 0] if abs(n[0]) < .9 else [0, 1, 0]); u /= np.linalg.norm(u); v = np.cross(n, u)
        P = [np.array([p@u, p@v]) for p in a]; Q = [np.array([q@u, q@v]) for q in b]
        if cross2(Q[1]-Q[0], Q[2]-Q[0]) < 0: Q = Q[::-1]
        out = P
        for i in range(3):
            c, e = Q[i], Q[(i+1) % 3]; inp, out = out, []; side = lambda p: cross2(e-c, p-c)
            for j in range(len(inp)):
                s, t = inp[j], inp[(j+1) % len(inp)]
                if side(t) >= 0:
                    if side(s) < 0: out.append(s+(t-s)*side(s)/(side(s)-side(t)))
                    out.append(t)
                elif side(s) >= 0: out.append(s+(t-s)*side(s)/(side(s)-side(t)))
            if not out: return 0.
        return .5*abs(sum(cross2(out[k], out[(k+1) % len(out)]) for k in range(len(out))))
    total = 0.
    for a in A.reshape(-1, 3, 3):
        n = np.cross(a[1]-a[0], a[2]-a[0]); L = np.linalg.norm(n)
        if L < 1e-9: continue
        n = n/L
        for b in B.reshape(-1, 3, 3):
            m = np.cross(b[1]-b[0], b[2]-b[0]); M = np.linalg.norm(m)
            if M < 1e-9 or abs(abs(n@m/M)-1) > 1e-3 or abs(n@(b[0]-a[0])) > 2e-3: continue
            total += clip(a, b, n)
    return total


def opaque(prop):
    """Parts whose texture is missing, or whose UV triangles reach a texel below alpha 128 (sampled inside)."""
    bad = []
    for p in prop['parts']:
        ns, path = p['texture'].split(':'); file = ASSETS.parent/ns/path
        if not file.is_file(): bad.append((p['name'], 'missing '+p['texture'])); continue
        atlas = np.array(Image.open(file).convert('RGBA')); h, w = atlas.shape[:2]
        T = np.array(prop['vertices']).reshape(-1, 8)[p['start']:p['start']+p['count'], 3:5].reshape(-1, 3, 2)
        for a in np.linspace(.02, .96, 6):
            for b in np.linspace(.02, .96-a, 6):
                uv = T[:, 0]*(1-a-b)+T[:, 1]*a+T[:, 2]*b
                if (atlas[np.minimum(h-1, (uv[:, 1]*h).astype(int)), np.minimum(w-1, (uv[:, 0]*w).astype(int)), 3] < 128).any():
                    bad.append((p['name'], 'transparent texel')); break
            else: continue
            break
    return bad


def main():
    aq = read('yukkuri_presentation/marisa_aquatic_hat_vessel.json'); land = read('yukkuri_presentation/hat_vessel.json')
    worn = read('yukkuri_props/accessory_marisa_aquatic.json'); turned = read('yukkuri_props/marisa_aquatic_hat_turned.json')
    a = np.array(aq['anchor_worn'])
    W = np.array(worn['vertices']).reshape(-1, 8); T = np.array(turned['vertices']).reshape(-1, 8)
    back = np.c_[-(W[:, 2]-a[2]), -(W[:, 1]-a[1]), -(W[:, 0]-a[0])]
    assert worn['parts'] == turned['parts'] and np.abs(back-T[:, :3]).max() < 1e-4 and np.array_equal(W[:, 3:5], T[:, 3:5]), 'worn and turned differ'
    # 3. sections and wall
    S = aq['cavity_sections']; polys = [np.array(s['outline']) for s in S]
    for p in polys:
        e = np.roll(p, -1, 0)-p; assert np.all(e[:, 0]*np.roll(e, -1, 0)[:, 1]-e[:, 1]*np.roll(e, -1, 0)[:, 0] >= -1e-6), 'concave section'
    for o, i, so, si in zip(polys, polys[1:], S, S[1:]):
        assert all(H.inside(o, x, z, 1e-6) for x, z in i) and so['depth'] < si['depth'] < aq['keel']
    # 2. treads as drawn
    up = T.reshape(-1, 3, 8); names = [p['name'] for p in turned['parts'] for _ in range(p['count']//3)]
    up = np.array([t for t, n in zip(up, names) if n.startswith('hat_lining') and np.all(t[:, 6] > .99)])
    checked = 0
    for mode in ('bowl', 'nest'):
        hull = H.Hull(aq, mode); x0, x1, z0, z1 = aq['opening']
        for x in np.linspace(x0+.04, x1-.04, 23):
            for z in np.linspace(z0+.04, z1-.04, 29):
                s = hull.surface(x, z)
                if s is None or not H.inside(hull.sections[0][0], x, z): continue
                hits = []
                for t in up:
                    p, q, r = t[:, [0, 2]]; M = np.column_stack((q-p, r-p))
                    if abs(np.linalg.det(M)) < 1e-12: continue
                    u, v = np.linalg.solve(M, np.array([x, z])-p)
                    if u >= -1e-7 and v >= -1e-7 and u+v <= 1+1e-7: hits.append(t[0, 1])
                assert hits, ('no tread', mode, x, z)
                assert abs(hull.top+max(hits)*hull.sq-s) < 2e-4, ('tread mismatch', mode, x, z)
                checked += 1
    thinnest = 9.
    above = 0.
    for s, p in zip(S, polys):
        for d in np.linspace(max(above, H.R['brim']['thickness']), s['depth']+H.R['wall'], 8):
            thinnest = min(thinnest, H.gap(p, H.ring(*H.crown_ring(d))))
        above = s['depth']
    assert thinnest >= .5, ('thin wall', thinnest)
    # 4. grips and badge
    B = np.array(aq['brim_outline']); top = H.ring(*H.crown_ring(H.R['brim']['thickness']))
    for role in ('helper', 'self'):
        g = np.array(aq['placements']['aquatic'][role]['grip_worn']); v = (-(g[2]-a[2]), -(g[0]-a[0]))
        assert H.inside(B, *v) and not H.inside(top, *v), ('grip off the brim', role)
    bp = np.array(aq['badge_mount']['point']); depth = bp[1]-a[1]
    assert H.R['band']['from'] <= depth <= H.R['band']['to'], 'badge off the band'
    # 5. cargo
    layout = read('yukkuri_presentation/marisa_aquatic_hat_cargo_layout.json'); prov = read('yukkuri_props/marisa_aquatic_hat_provisions.json')
    P = np.array(prov['vertices']).reshape(-1, 8); hull = H.Hull(aq, 'bowl'); O = polys[0]; outside = 0
    for part in prov['parts']:
        if not part['name'].startswith('inside'): continue
        for x, y, z in P[part['start']:part['start']+part['count'], :3][::3]:
            outside += not hull.contains(x, hull.top+y*hull.sq, z, 0, 0)
    assert outside == 0, ('provisions through the cavity', outside)
    for tier in layout['aside_tiers']:
        for c in tier:
            ring = [(c['x']+c['radius']*math.cos(t), c['z']+c['radius']*math.sin(t)) for t in np.linspace(0, math.tau, 24, endpoint=False)]
            assert all(H.inside(B, x, z) and not H.inside(O, x, z) for x, z in ring), ('side stow off the brim', c)
    # 6. capacity against the land hat boat (newborn 0.14, small young 0.20, young 0.25, in an adult's 1.5 hat)
    b = read('yukkuri_presentation/body_geometry.json')['variants']['marisa']['bounds'][0]
    width = (b[1]-b[0])/aq['aquatic_scale']; rows = []
    old, new = H.Hull(as_polygons(land), 'bowl'), H.Hull(aq, 'bowl')
    for scale, shares in ((.14, 2), (.20, 4), (.25, 4)):  # design: 12 shares, newborn 2, juvenile 4
        r = .42*width*scale; ro = .42*width  # the owner's whole round footprint at the stern, a stricter test than the game's
        aboard = pack(new, r, (0, aq['opening'][3]-ro, ro))
        rows.append((scale, pack(old, r), pack(new, r), pack(old, r, (0, land['opening'][3]-ro, ro)), aboard))
        assert aboard >= 12//shares, ('fewer seats than the 12-share rule allows, owner aboard', rows[-1])
    # 7. the bow keeps the land bow's contacts, and never shares a plane with the band
    land_bow, aq_bow = part_points(read('yukkuri_props/accessory_marisa.json'), 'bow_'), part_points(worn, 'bow_')
    contacts = 0
    for x, y in itertools.combinations(sorted(land_bow), 2):
        if box_gap(land_bow[x], land_bow[y]) <= 1e-4:
            contacts += 1; assert box_gap(aq_bow[x], aq_bow[y]) <= 1e-4, ('bow boxes came apart', x, y)
    band = part_points(worn, 'hat_band')['hat_band']
    shared = coplanar_overlap(np.concatenate(list(aq_bow.values())), band)
    assert shared < 1e-4, ('bow face in a band face plane', shared)
    # 8. materials, and winding: the damage renderer and badge exporter take a face's normal from its winding
    for name in ('accessory_marisa_aquatic', 'marisa_aquatic_hat_turned'):
        bad = opaque(read(f'yukkuri_props/{name}.json')); assert not bad, (name, bad)
        Tn = np.array(read(f'yukkuri_props/{name}.json')['vertices']).reshape(-1, 3, 8)
        g = np.cross(Tn[:, 1, :3]-Tn[:, 0, :3], Tn[:, 2, :3]-Tn[:, 0, :3]); real = np.linalg.norm(g, axis=1) > 1e-8
        assert np.all((g*Tn[:, :, 5:8].sum(1)).sum(1)[real] > 0), (name, 'triangles wound against their normals')
    mount = json.loads((ROOT/'art/props/badges/mounts.json').read_text(encoding='utf-8'))['detached'].get('accessory_marisa_aquatic')
    assert mount == {k: aq['badge_mount'][k] for k in ('part', 'point', 'normal', 'diameter')}, 'badge recipe differs from the hat build'
    exported = json.loads((ASSETS/'yukkuri/badge_mounts.json').read_text(encoding='utf-8'))['detached']['accessory_marisa_aquatic']
    assert np.linalg.norm(np.array(exported['rest_point'])-np.array(mount['point'])) < .05, ('badge exported off its point', exported['rest_point'])
    print(f'worn == turned; {checked} tread rays; {len(S)} convex nested sections; thinnest crown wall {thinnest:.2f}; '
          f'grips on brim; provisions inside; seats (scale, current, new, current+owner, new+owner) {rows}; '
          f'{contacts} land bow contacts kept, bow/band coplanar area {shared:.5f}; every part textured and opaque')


if __name__ == '__main__':
    main()
