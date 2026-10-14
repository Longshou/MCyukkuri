"""Author selected whole-head motions and the round cheek puff (v7).

The source rigs remain frozen inputs. Clips other than the puff keep one root
transform. Puffing grows two round cheek lobes on a locally refined copy of the
skin and carries the blush on the same skin triangles. Eyes, brows, mouth, base
and back never move; the root never moves.

Shape: the skin is united with one tilted ellipsoid per cheek, projected
radially from an anchor inside both; facial ink keeps an undisplaced margin.
Side locks (hand_/sideburn_ branches) swing rigidly and symmetrically where that
enters no other solid; every other solid, and a lock that cannot swing far
enough, is an obstacle the cheek stops short of, so the puff never enters hair.
All lengths derive from the blush centre (face scale), not the body height, so
tall bean bodies and small house bodies keep the same facial proportion. All
outputs are one rest-space morph target scaled by the clip's puff envelope.
"""
import math
import numpy as np


def smooth(x):
    x = max(0, min(1, x))
    return x*x*(3-2*x)


def smooth_array(x):
    x = np.clip(x, 0, 1)
    return x*x*(3-2*x)


def envelope(t, e):
    """Inflate, hold and release; must match YukkuriModel and pose.js."""
    u = (t-e[0])/(e[1]-e[0])
    base = smooth(u/e[2])*smooth((1-u)/e[2])
    if base <= 0 or len(e) < 5:
        return base
    hold = smooth((u-e[2])/e[2])*smooth((1-e[2]-u)/e[2])
    return base*(1-e[3]*hold*(.5-.5*math.cos(2*math.pi*e[4]*(t-e[0]))))


# ------------------------------------------------------------ skin refinement
class SkinMesh:
    """Closed triangle mesh: shared positions for topology, corner attributes kept."""

    def __init__(self, vertices, start, count):
        self.pos, self.key, self.tris = [], {}, []
        for k in range(start*8, (start+count)*8, 24):
            ids, attrs = [], []
            for j in range(3):
                v = vertices[k+j*8:k+j*8+8]
                ids.append(self._id(v[:3])); attrs.append(list(v[3:8]))
            self.tris.append([ids, attrs])
        self.alive = [True]*len(self.tris)
        self.edges, self.mid = {}, {}
        for t, (ids, _) in enumerate(self.tris):
            for e in self._edges(ids):
                self.edges.setdefault(e, set()).add(t)

    def _id(self, p):
        k = tuple(round(x*1e5) for x in p)
        if k not in self.key:
            self.key[k] = len(self.pos); self.pos.append([float(x) for x in p])
        return self.key[k]

    @staticmethod
    def _edges(ids):
        return [tuple(sorted((ids[i], ids[(i+1) % 3]))) for i in range(3)]

    def length(self, e):
        return math.dist(self.pos[e[0]], self.pos[e[1]])

    def longest(self, t):
        # A deterministic tie break keeps both neighbours on one terminal edge.
        return max(self._edges(self.tris[t][0]), key=lambda e: (round(self.length(e), 9), e))

    def _bisect(self, t, e):
        ids, attrs = self.tris[t]
        i = next(i for i in range(3) if tuple(sorted((ids[i], ids[(i+1) % 3]))) == e)
        a, b, c = ids[i], ids[(i+1) % 3], ids[(i+2) % 3]
        A, B, C = attrs[i], attrs[(i+1) % 3], attrs[(i+2) % 3]
        if e not in self.mid:
            self.mid[e] = self._id([(self.pos[a][k]+self.pos[b][k])/2 for k in range(3)])
        m = self.mid[e]; M = [(A[k]+B[k])/2 for k in range(5)]
        self.alive[t] = False
        for ee in self._edges(ids):
            self.edges[ee].discard(t)
        for nids, nattrs in [([a, m, c], [A, M, C]), ([m, b, c], [M, B, C])]:
            self.tris.append([nids, nattrs]); self.alive.append(True)
            for ee in self._edges(nids):
                self.edges.setdefault(ee, set()).add(len(self.tris)-1)

    def refine(self, t):
        """Rivara longest-edge propagation keeps the refined skin conforming."""
        while self.alive[t]:
            path = [t]
            while True:
                cur = path[-1]; e = self.longest(cur)
                nbs = [n for n in self.edges[e] if n != cur]
                if not nbs or self.longest(nbs[0]) == e or nbs[0] in path:
                    break
                path.append(nbs[0])
            e = self.longest(path[-1])
            for n in list(self.edges[e]):
                self._bisect(n, e)

    def refine_where(self, wants):
        changed = True
        while changed:
            changed = False
            for t in range(len(self.tris)):
                if self.alive[t] and wants([self.pos[i] for i in self.tris[t][0]], self.length(self.longest(t))):
                    self.refine(t); changed = True

    def triangles(self):
        return [tri for tri, ok in zip(self.tris, self.alive) if ok]


def vertex_normals(tris, pos):
    n = np.zeros_like(pos)
    for ids, _ in tris:
        P = pos[ids]; f = np.cross(P[1]-P[0], P[2]-P[0])
        n[ids] += f
    length = np.linalg.norm(n, axis=1); length[length < 1e-12] = 1
    return n/length[:, None]


# ------------------------------------------------------------------- lobes
def ellipsoid_exit(anchor, centre, radii, directions):
    d = directions/radii; o = (anchor-centre)/radii
    a = (d*d).sum(1); b = (d*o).sum(1); c = (o*o).sum()-1
    assert c < 0, 'anchor outside cheek lobe'
    return (-b+np.sqrt(b*b-a*c))/a


def lobe_exit(lobe, directions):
    """Exit distance from a tilted lobe whose front and rear halves differ in depth."""
    c, s = math.cos(lobe['tilt']), math.sin(lobe['tilt'])
    R = np.array([[c, s, 0], [-s, c, 0], [0, 0, 1.0]])
    centre = lobe['centre']; anchor = centre+R@(lobe['anchor']-centre); d = directions@R.T
    front = ellipsoid_exit(anchor, centre, lobe['radii'], d)
    rear = ellipsoid_exit(anchor, centre, np.array([lobe['radii'][0], lobe['radii'][1], lobe['back']]), d)
    return np.where(anchor[2]+d[:, 2]*front <= centre[2], front, rear)


def smax(a, b, k):
    h = np.maximum(k-np.abs(a-b), 0)/k
    return np.maximum(a, b)+h*h*k/4


def planar_distance(points, cloud):
    if len(cloud) == 0:
        return np.full(len(points), 1e9)
    out = np.empty(len(points))
    for s in range(0, len(points), 2048):
        d = points[s:s+2048, None, :]-cloud[None, :, :]
        out[s:s+2048] = np.sqrt((d*d).sum(2)).min(1)
    return out


class Cheeks:
    """Two mirrored lobes measured from this model's own face and skin."""

    def __init__(self, model, cfg, face):
        body = next(p for p in model['parts'] if 'body_surface' in p['name'])
        V = np.array(model['vertices']).reshape(-1, 8)
        skin = V[body['start']:body['start']+body['count'], :3]
        self.front = float(skin[:, 2].min()); self.floor = float(skin[:, 1].min())
        bl = next(p for p in model['parts'] if p['name'] == 'face_blush_left')
        bv = V[bl['start']:bl['start']+bl['count'], :3]
        bx = float((bv[:, 0].min()+bv[:, 0].max())/2); by = float((bv[:, 1].min()+bv[:, 1].max())/2)
        F = self.scale = bx; c = self.cfg = cfg
        self.eyes = np.asarray(face['eyes'], float); self.mouth = np.asarray(face['mouth'], float)
        self.blend = c['blend']*F
        cx, cy = bx*c['centre_x'], by+c['centre_y']*F
        self.lobes = []
        for side in (1, -1):
            centre = np.array([side*cx, cy, self.front+c['centre_depth']*F])
            self.lobes.append({'side': side, 'centre': centre,
                               'radii': np.array([c['radius_x']*F, c['radius_y']*F, c['radius_front']*F]),
                               'back': c['radius_back']*F, 'tilt': side*math.radians(c['tilt_degrees']),
                               'anchor': np.array([side*cx, cy+c['anchor_y']*F, self.front+c['anchor_depth']*F])})

    def scaled(self, factor):
        """The same lobes shrunk about their centres; anchors, guards and blend unchanged."""
        out = object.__new__(Cheeks); out.__dict__.update(self.__dict__); out.size = factor
        out.lobes = [dict(l, radii=l['radii']*factor, back=l['back']*factor) for l in self.lobes]
        return out

    def describe(self):
        r = lambda v: [round(float(x), 5) for x in v]
        return {'face_scale': round(self.scale, 5), 'blend': round(self.blend, 5), 'size': round(getattr(self, 'size', 1.0), 4),
                'lobes': [{'centre': r(l['centre']), 'radii': r(l['radii']), 'back': round(l['back'], 5),
                           'tilt_degrees': round(math.degrees(l['tilt']), 3), 'anchor': r(l['anchor'])} for l in self.lobes]}

    def growth(self, points):
        """Radial growth onto the smooth union of skin and lobes, before any guard."""
        p = np.asarray(points, float); out = np.zeros_like(p)
        for lobe in self.lobes:
            m = p[:, 0]*lobe['side'] > 0
            if not m.any():
                continue
            q = p[m]; v = q-lobe['anchor']; r = np.linalg.norm(v, axis=1); u = v/r[:, None]
            out[m] = lobe['anchor']+u*smax(r, lobe_exit(lobe, u), self.blend)[:, None]-q
        return out

    def guard(self, points):
        """Per point: (forward, sliding) factors, 1 where the face may swell, 0 on ink.

        Sliding along the face fades over twice the distance: skin that slid
        toward a held eye or mouth faster than its neighbours would fold."""
        p = np.asarray(points, float); c = self.cfg; F = self.scale
        near_face = smooth_array((self.front+c['guard_depth']*F-p[:, 2])/(c['guard_depth']*F))
        de = planar_distance(p[:, :2], self.eyes)-c['eye_gap']*F
        dm = planar_distance(p[:, :2], self.mouth)-c['mouth_gap']*F
        out = []
        for stretch in (1, c['slide_fade_factor']):
            eye = smooth_array(de/(c['eye_fade']*F*stretch)); mouth = smooth_array(dm/(c['mouth_fade']*F*stretch))
            out.append(1-near_face*(1-eye*mouth))
        return np.stack(out, 1)

    def planted(self, points, out):
        """The base stays planted: nothing near the floor may move downward."""
        low = smooth_array((np.asarray(points, float)[:, 1]-self.floor)/(self.cfg['floor_fade']*self.scale))
        out[:, 1] = np.where(out[:, 1] < 0, out[:, 1]*low, out[:, 1])
        return out

    def offsets(self, points, guard=None):
        g = self.guard(points) if guard is None else guard
        out = self.growth(points)
        out[:, 2] *= g[:, 0]; out[:, :2] *= g[:, 1:2]
        return self.planted(points, out)


def refined_skin(model, cheeks, cfg):
    index = next(i for i, p in enumerate(model['parts']) if 'body_surface' in p['name'])
    part = model['parts'][index]; mesh = SkinMesh(model['vertices'], part['start'], part['count'])
    F = cheeks.scale; h = cfg['edge']*F
    top = max(l['centre'][1]+max(l['radii'][:2]) for l in cheeks.lobes)+cheeks.blend*3
    bottom = min(l['centre'][1]-max(l['radii'][:2]) for l in cheeks.lobes)-cheeks.blend*3
    inner = min(abs(l['centre'][0])-max(l['radii'][:2]) for l in cheeks.lobes)-cheeks.blend*3
    depth = cheeks.front+cfg['region_depth']*F

    def wants(points, longest):
        p = np.array(points)
        if p[:, 1].min() > top or p[:, 1].max() < bottom or p[:, 2].min() > depth or np.abs(p[:, 0]).max() < inner:
            return False
        samples = np.vstack([p, (p+np.roll(p, 1, 0))/2, p.mean(0, keepdims=True), (p+p.mean(0))/2])
        moved = np.abs(cheeks.offsets(samples)).max() > 1e-5
        if not moved:
            # Large flat triangles could still contain a lobe between samples.
            return longest > cfg['coarse_limit']*F
        if longest > h*cfg['coarse_factor']:
            return True
        q = p+cheeks.offsets(p)
        return max(np.linalg.norm(q[i]-q[(i+1) % 3]) for i in range(3)) > h
    mesh.refine_where(wants)
    tris = mesh.triangles(); pos = np.array(mesh.pos)
    # Distances to discrete ink texels crease along their Voronoi borders. A
    # spatial minimum followed by a narrower mean removes the creases at any
    # mesh density and never raises the guard, so ink stays as clear as before.
    raw = cheeks.guard(pos)
    guard = np.stack([conservative_smooth(pos, raw[:, k], cfg['guard_min_radius']*F, cfg['guard_blur_radius']*F) for k in range(2)], 1)
    return index, tris, pos, guard


def conservative_smooth(points, values, min_radius, blur_radius):
    """Minimum within min_radius, then mean within blur_radius (<= min_radius).

    For every point the result is at most its own value: each averaged minimum
    already includes that point."""
    assert blur_radius <= min_radius
    out = values.copy()
    for radius, reduce in ((min_radius, np.min), (blur_radius, np.mean)):
        cells = {}
        for i, key in enumerate(map(tuple, np.floor(points/radius).astype(int))):
            cells.setdefault(key, []).append(i)
        src = out.copy()
        for key, members in cells.items():
            around = [j for dx in (-1, 0, 1) for dy in (-1, 0, 1) for dz in (-1, 0, 1)
                      for j in cells.get((key[0]+dx, key[1]+dy, key[2]+dz), ())]
            around = np.array(around); members = np.array(members)
            d = np.linalg.norm(points[members, None, :]-points[None, around, :], axis=2)
            masked = np.where(d <= radius, src[around][None, :], np.nan)
            out[members] = np.nanmin(masked, 1) if reduce is np.min else np.nanmean(masked, 1)
    return out


def indexed(rows, offsets, digits):
    """Share identical corners; readers expand them back to a triangle list."""
    unique, vertices, indices, packed = {}, [], [], []
    for row, off in zip(rows, offsets):
        key = tuple(round(x, digits) for x in row)+tuple(round(x, digits) for x in off)
        if key not in unique:
            unique[key] = len(vertices)//8
            vertices.extend(key[:8]); packed.extend(key[8:])
        indices.append(unique[key])
    return {'vertices': vertices, 'indices': indices, 'offsets': packed}


def blush_on_skin(model, name, tris, pos, off, gap):
    """Clip the refined skin to the blush outline; ride those triangles at a normal gap.

    The decal's front faces tile one rectangle in the face plane, so each skin
    triangle is clipped once; texture coordinates come from the decal face that
    holds each clipped corner.
    """
    part = next(p for p in model['parts'] if p['name'] == name)
    V = model['vertices']; rows, offsets = [], []
    faces = [[V[k+j*8:k+j*8+8] for j in range(3)] for k in range(part['start']*8, (part['start']+part['count'])*8, 24)]
    faces = [f for f in faces if f[0][7] < -.2]
    lo = np.array([min(v[0] for f in faces for v in f), min(v[1] for f in faces for v in f)])
    hi = np.array([max(v[0] for f in faces for v in f), max(v[1] for f in faces for v in f)])
    area = sum(abs((f[1][0]-f[0][0])*(f[2][1]-f[0][1])-(f[2][0]-f[0][0])*(f[1][1]-f[0][1]))/2 for f in faces)
    assert abs(area-np.prod(hi-lo)) < 1e-4*np.prod(hi-lo), f'{name}: blush faces must tile their rectangle'

    def uv(x, y):
        best = None
        for a, b, c in faces:
            den = (b[1]-c[1])*(a[0]-c[0])+(c[0]-b[0])*(a[1]-c[1])
            if abs(den) < 1e-12:
                continue
            u = ((b[1]-c[1])*(x-c[0])+(c[0]-b[0])*(y-c[1]))/den
            v = ((c[1]-a[1])*(x-c[0])+(a[0]-c[0])*(y-c[1]))/den
            w = (u, v, 1-u-v); worst = min(w)
            if best is None or worst > best[0]:
                best = (worst, [sum(w[i]*t[3] for i, t in enumerate((a, b, c))), sum(w[i]*t[4] for i, t in enumerate((a, b, c)))])
        return best[1]
    N0 = vertex_normals(tris, pos); N1 = vertex_normals(tris, pos+off)
    sliver = 2*(gap*.05)**2; fold_cos = .25
    planes = [(0, lo[0], 1), (0, hi[0], -1), (1, lo[1], 1), (1, hi[1], -1)]
    for ids, _ in tris:
        P = pos[ids]
        if np.cross(P[1]-P[0], P[2]-P[0])[2] >= -1e-9:
            continue
        if P[:, 0].max() < lo[0] or P[:, 0].min() > hi[0] or P[:, 1].max() < lo[1] or P[:, 1].min() > hi[1]:
            continue
        poly = [np.concatenate([P[j], off[ids[j]], np.eye(3)[j]]) for j in range(3)]
        for axis, edge, sign in planes:
            clipped = []
            for j, p in enumerate(poly):
                q = poly[(j+1) % len(poly)]; dp, dq = sign*(p[axis]-edge), sign*(q[axis]-edge)
                if dp >= 0:
                    clipped.append(p)
                if (dp >= 0) != (dq >= 0):
                    clipped.append(p+(q-p)*(dp/(dp-dq)))
            poly = clipped
            if not poly:
                break
        for j in range(1, len(poly)-1):
            corners = (poly[0], poly[j], poly[j+1])
            # Clipping leaves needle slivers along the skin edges; they carry no
            # visible pigment but can invert once the skin bends.
            rest = np.cross(corners[1][:3]-corners[0][:3], corners[2][:3]-corners[0][:3])
            if np.linalg.norm(rest) < sliver:
                continue
            # Pigment folded steeply under the eye ridge faces the floor and is unseen.
            grown = np.cross((corners[1][:3]+corners[1][3:6])-(corners[0][:3]+corners[0][3:6]),
                             (corners[2][:3]+corners[2][3:6])-(corners[0][:3]+corners[0][3:6]))
            if np.dot(rest, grown) < fold_cos*np.linalg.norm(rest)*np.linalg.norm(grown):
                continue
            for p in corners:
                s3 = p[6:9]
                n0 = s3@N0[ids]; n0 /= np.linalg.norm(n0); n1 = s3@N1[ids]; n1 /= np.linalg.norm(n1)
                rows.append([*(p[:3]+n0*gap), *uv(p[0], p[1]), *n0])
                offsets.append(list(p[3:6]+(n1-n0)*gap))
    return rows, offsets


# ------------------------------------------------- side locks and other hair
def hidden_parts(model):
    """Parts collapsed by the idle defaults (old cheek pads, puff decals)."""
    hidden = set()
    for track in model['clips']['idle']['tracks']:
        if track['channel'] == 'scale' and min(abs(v) for k in track['keys'] for v in k[1:]) < 1e-3:
            hidden.add(track['bone'])
    grew = True
    while grew:
        grew = False
        for i, b in enumerate(model['bones']):
            if i not in hidden and b['parent'] in hidden:
                hidden.add(i); grew = True
    out = set()
    for index, p in enumerate(model['parts']):
        ws = model['weights'][p['start']:p['start']+p['count']]
        if ws and all(a in hidden and (q == 0 or b in hidden) for a, b, q in ws):
            out.add(index)
    return out


def lock_groups(model, hidden):
    """The yukkuri's movable side locks: everything on a hand_/sideburn_ branch, per side."""
    names = [b['name'] for b in model['bones']]; groups = []
    for side, label in [(1, 'right'), (-1, 'left')]:
        root = next((names.index(n) for n in ('hand_'+label, 'sideburn_'+label) if n in names), None)
        if root is None:
            continue
        branch = {root}; grew = True
        while grew:
            grew = False
            for i, b in enumerate(model['bones']):
                if i not in branch and b['parent'] in branch:
                    branch.add(i); grew = True
        parts = [i for i, p in enumerate(model['parts']) if not p['anatomy'] and i not in hidden
                 and any(model['weights'][k][0] in branch for k in range(p['start'], p['start']+p['count']))]
        if parts:
            groups.append({'side': side, 'bone': names[root], 'parts': parts})
    return groups


def lobe_clear(Q, lobe, clearance):
    c, s = math.cos(lobe['tilt']), math.sin(lobe['tilt']); d = Q-lobe['centre']
    lx = c*d[:, 0]+s*d[:, 1]; ly = -s*d[:, 0]+c*d[:, 1]; lz = d[:, 2]
    rz = np.where(lz < 0, lobe['radii'][2], lobe['back'])
    level = (lx/(lobe['radii'][0]+clearance))**2+(ly/(lobe['radii'][1]+clearance))**2+(lz/(rz+clearance))**2
    return bool((level >= 1).all())


def segments_hit(P, Q, T):
    """Segment P->Q crosses triangle T strictly (arrays of pairs)."""
    d = Q-P; e1 = T[:, 1]-T[:, 0]; e2 = T[:, 2]-T[:, 0]
    h = np.cross(d, e2); det = (e1*h).sum(1)
    tol = 1e-7
    with np.errstate(divide='ignore', invalid='ignore'):
        f = 1/det; s = P-T[:, 0]; u = f*(s*h).sum(1); q = np.cross(s, e1); v = f*(d*q).sum(1); t = f*(e2*q).sum(1)
        return (np.abs(det) > 1e-12) & (u > tol) & (v > tol) & (u+v < 1-tol) & (t > tol) & (t < 1-tol)


def crossing_pairs(A, B):
    """Indices of (a, b) triangle pairs that intersect, after a bounding-box prefilter."""
    if not len(A) or not len(B):
        return set()
    lo_a, hi_a, lo_b, hi_b = A.min(1), A.max(1), B.min(1), B.max(1)
    out = set()
    for start in range(0, len(A), 64):
        a = np.arange(start, min(len(A), start+64))
        overlap = ((lo_a[a, None, :] <= hi_b[None]) & (hi_a[a, None, :] >= lo_b[None])).all(2)
        ia, ib = np.nonzero(overlap)
        if not len(ia):
            continue
        ia = a[ia]; TA = A[ia]; TB = B[ib]; hit = np.zeros(len(ia), bool)
        for k in range(3):
            hit |= segments_hit(TA[:, k], TA[:, (k+1) % 3], TB)
            hit |= segments_hit(TB[:, k], TB[:, (k+1) % 3], TA)
        out.update(zip(ia[hit].tolist(), ib[hit].tolist()))
    return out


def rotate_about(P, pivot, side, degrees):
    a = math.radians(side*degrees); c, s = math.cos(a), math.sin(a); d = P-pivot
    return np.stack([c*d[..., 0]-s*d[..., 1], s*d[..., 0]+c*d[..., 1], d[..., 2]], -1)+pivot


def swing(model, group, cheeks, cfg, blockers):
    """How far a lock may swing rigidly without entering other hair or the skin.

    Two pivots are tried, the rig's own bone and the lock's innermost root; the
    free angle is searched up to what the full-size lobe needs. The cheek is
    pressed against the lock for anything the free angle cannot clear.
    """
    V = np.array(model['vertices']).reshape(-1, 8); side = group['side']
    tris = np.vstack([V[model['parts'][i]['start']:model['parts'][i]['start']+model['parts'][i]['count'], :3].reshape(-1, 3, 3) for i in group['parts']])
    flat = tris.reshape(-1, 3); samples = np.vstack([flat, ((tris+np.roll(tris, 1, 1))/2).reshape(-1, 3), tris.mean(1)])
    top = flat[:, 1].max(); crown = flat[flat[:, 1] > top-cfg['lock_root_band']*cheeks.scale]
    pivots = {'bone': np.array(model['bones'][[b['name'] for b in model['bones']].index(group['bone'])]['pivot'], float),
              'root': np.array([crown[np.argmin(np.abs(crown[:, 0])), 0], top, float(crown[:, 2].mean())])}
    lobe = next(l for l in cheeks.lobes if l['side'] == side)
    clearance = cfg['lock_clearance']*cheeks.scale+cheeks.blend/4; limit = cfg['lock_max_degrees']
    near = blockers[((blockers.min(1) <= flat.max(0)+limit/10) & (blockers.max(1) >= flat.min(0)-limit/10)).all(1)]
    base = crossing_pairs(tris, near)
    best = None
    for name, pivot in pivots.items():
        need = next((step/4 for step in range(int(limit*4)+1) if lobe_clear(rotate_about(samples, pivot, side, step/4), lobe, clearance)), limit)

        def free(angle):
            return not (crossing_pairs(rotate_about(tris, pivot, side, angle), near)-base)
        if free(need):
            angle = need
        else:
            lo, hi = 0.0, need
            for _ in range(7):
                mid = (lo+hi)/2
                lo, hi = (mid, hi) if free(mid) else (lo, mid)
            angle = math.floor(lo*4)/4
        if best is None or angle > best[1]+1e-9:
            best = (name, angle, pivot, need)
    name, angle, pivot, need = best
    return {'side': side, 'bone': group['bone'], 'parts': group['parts'], 'pivot_kind': name, 'pivot': pivot,
            'free_degrees': angle, 'samples': samples}


def swing_need(sw, cheeks, cfg):
    lobe = next(l for l in cheeks.lobes if l['side'] == sw['side'])
    clearance = cfg['lock_clearance']*cheeks.scale+cheeks.blend/4; limit = cfg['lock_max_degrees']
    return next((step/4 for step in range(int(limit*4)+1)
                 if lobe_clear(rotate_about(sw['samples'], sw['pivot'], sw['side'], step/4), lobe, clearance)), limit)


def swing_offsets(model, sw):
    V = np.array(model['vertices']).reshape(-1, 8); out = {}
    for i in sw['parts']:
        p = model['parts'][i]; P = V[p['start']:p['start']+p['count'], :3]
        out[i] = rotate_about(P, sw['pivot'], sw['side'], sw['degrees'])-P
    return out


def obstacles(model, excluded, moved):
    """Solid triangles the cheek may touch but never enter, locks at their swung pose."""
    V = np.array(model['vertices']).reshape(-1, 8); out = []
    for i, p in enumerate(model['parts']):
        if i in excluded or p['anatomy'] or p['name'].startswith('face_') or 'body_surface' in p['name']:
            continue
        P = V[p['start']:p['start']+p['count'], :3]
        if i in moved:
            P = P+moved[i]
        out.append(P.reshape(-1, 3, 3))
    return np.vstack(out) if out else np.zeros((0, 3, 3))


def first_hits(origin, directions, near, far, T):
    """Nearest triangle hit per ray within (near, far]; inf where none (Moller-Trumbore)."""
    best = np.full(len(directions), np.inf)
    if not len(T):
        return best
    e1 = T[:, 1]-T[:, 0]; e2 = T[:, 2]-T[:, 0]; s = origin-T[:, 0]
    for k in range(0, len(directions), 256):
        d = directions[k:k+256, None, :]
        h = np.cross(d, e2[None]); det = (e1[None]*h).sum(2)
        with np.errstate(divide='ignore', invalid='ignore'):
            f = 1/det; u = f*(s[None]*h).sum(2)
            q = np.cross(s, e1)[None]; v = f*(d*q).sum(2); t = f*(e2[None]*q).sum(2)
            ok = (np.abs(det) > 1e-12) & (u >= 0) & (v >= 0) & (u+v <= 1) & (t > near[k:k+256, None]) & (t <= far[k:k+256, None])
        t = np.where(ok, t, np.inf); best[k:k+256] = t.min(1)
    return best


def press(cheeks, tris, pos, off, solid, cfg):
    """Stop the grown skin short of every obstacle; smooth the limit without raising it.

    Rays pass through every vertex, edge midpoint and centroid of the moving
    skin, so a thin lock tip cannot slip between two vertex rays; each sample's
    limit binds all corners of its triangle."""
    clearance = cfg['press_clearance']*cheeks.scale
    moving = np.linalg.norm(off, axis=1) > 1e-6
    T = np.array([ids for ids, _ in tris]); T = T[moving[T].any(1)]
    weights = np.array([[1, 0, 0], [0, 1, 0], [0, 0, 1], [.5, .5, 0], [0, .5, .5], [.5, 0, .5], [1/3, 1/3, 1/3]])
    P = np.einsum('sk,tkd->tsd', weights, pos[T]).reshape(-1, 3); O = np.einsum('sk,tkd->tsd', weights, off[T]).reshape(-1, 3)
    allowed = np.ones(len(P)); depth = 0.0
    reach = np.linalg.norm(np.vstack([l['radii'] for l in cheeks.lobes]), axis=1).max()
    for lobe in cheeks.lobes:
        m = (P[:, 0]*lobe['side'] > 0) & (np.linalg.norm(O, axis=1) > 1e-6)
        if not m.any():
            continue
        idx = np.nonzero(m)[0]; v = P[idx]-lobe['anchor']; r = np.linalg.norm(v, axis=1); u = v/r[:, None]
        grown = np.einsum('ij,ij->i', O[idx], u)  # growth along the anchor ray
        lo = lobe['centre']-reach-clearance*4; hi = lobe['centre']+reach+clearance*4
        near = solid[((solid.max(1) >= lo) & (solid.min(1) <= hi)).all(1)]
        hit = first_hits(lobe['anchor'], u, r-1e-4, r+grown+clearance*2, near)
        allowed[idx] = np.clip(np.where(np.isfinite(hit), (hit-clearance-r)/np.maximum(grown, 1e-9), 1), 0, 1)
        depth = max(depth, float(((1-allowed[idx])*grown).max()))
    scale = np.ones(len(pos))
    per_triangle = np.minimum.reduceat(allowed, np.arange(0, len(allowed), len(weights))) if len(allowed) else allowed
    for k in range(3):
        np.minimum.at(scale, T[:, k], per_triangle)
    # Conservative smoothing in space keeps the pressed cheek round at any density.
    scale = conservative_smooth(pos, scale, cfg['press_min_radius']*cheeks.scale, cfg['press_blur_radius']*cheeks.scale)
    return off*scale[:, None], int((scale < 1-1e-6).sum()), depth


# ------------------------------------------------------------- root clips
def state(action, t, duration, cfg, height, angle):
    scale, rotation, lift = [1., 1., 1.], [0., 0., 0.], 0.
    if action == 'puff':
        pass  # Only the local cheek morph and side locks change; the root stays at rest.
    elif action in ('side_bend', 'exercise_sides'):
        # Ease through the centre without elongating the top of the head.
        rotation[2] = angle*math.sin(2*math.pi*t/duration)**3
    elif action in ('stretch', 'stretch_shape', 'exercise_vertical'):
        q = math.sin(math.pi*t/duration)**2
        scale = [1-.045*q, 1+cfg['stretch_height']*q, 1-.025*q]
    elif action == 'hop':
        q = t/duration
        if q < .2:
            s = math.sin(math.pi*q/.2)**2
            scale = [1+.075*s, 1-.12*s, 1+.045*s]
        elif q < .8:
            air = (q-.2)/.6
            lift = height*cfg['hop_height_body_ratio']*4*air*(1-air)
            s = math.sin(math.pi*air)
            scale = [1-.025*s, 1+.055*s, 1-.02*s]
        else:
            s = math.sin(math.pi*(q-.8)/.2)**2
            scale = [1+.06*s, 1-.09*s, 1+.035*s]
    elif action == 'taiso':
        if t < 2.4:
            return state('exercise_vertical', t, 2.4, cfg, height, angle)
        if t < 4.8:
            return state('side_bend', t-2.4, 2.4, cfg, height, angle)
        if t < 7.2:
            rotation[1] = 18*math.sin(2*math.pi*(t-4.8)/2.4)**3
        elif t < 10.4:
            return state('hop', (t-7.2) % cfg['hop_seconds'], cfg['hop_seconds'], cfg, height, angle)
        elif t < 13.6:
            return state('puff', t-10.4, 3.2, cfg, height, angle)
    return scale, rotation, lift


def compile_revision(model, cfg, face):
    """face: {'eyes': [[x, y]...], 'mouth': [[x, y]...], 'mouth_patch': {index, vertices} | None}."""
    names = {b['name']: i for i, b in enumerate(model['bones'])}
    root = names['root']; pivot = model['bones'][root]['pivot']
    assert model['bones'][root]['parent'] == names['world_motion']
    points, body = [], []
    for part in model['parts']:
        if part.get('anatomy') or part['name'].startswith('puff_'):
            continue
        vertices = model['vertices'][part['start']*8:(part['start']+part['count'])*8]
        vv = [vertices[i:i+3] for i in range(0, len(vertices), 8)]
        points.extend(vv)
        if 'body_surface' in part['name']:
            body.extend(vv)
    assert body, model['id']
    height = max(v[1] for v in body)-min(v[1] for v in body)
    floor = min(v[1] for v in points)
    # Only the convex hull can support the floor. Deduplicate source triangles.
    points = list(set(tuple(v) for v in points))
    angle = cfg['shell_side_degrees'] if model['id'] == 'marisa_tsumuri' else cfg['side_degrees']

    full = Cheeks(model, cfg['cheeks'], face)
    body_index, tris, pos, guard = refined_skin(model, full, cfg['cheeks'])
    hidden = hidden_parts(model)
    groups = lock_groups(model, hidden); locked = {i for g in groups for i in g['parts']}
    V = np.array(model['vertices']).reshape(-1, 8); skin_part = model['parts'][body_index]
    rest_skin = V[skin_part['start']:skin_part['start']+skin_part['count'], :3].reshape(-1, 3, 3)
    blockers = np.vstack([obstacles(model, hidden | locked, {}), rest_skin])
    swings = [swing(model, g, full, cfg['cheeks'], blockers) for g in groups]

    def attempt(size):
        cheeks = full.scaled(size)
        # Both locks swing by the same angle; an uneven pair reads as a twisted head.
        common = min((min(swing_need(sw, cheeks, cfg['cheeks']), sw['free_degrees']) for sw in swings), default=0)
        common = common if common >= cfg['cheeks']['lock_min_degrees'] else 0
        moved = {i: o for sw in swings for i, o in swing_offsets(model, dict(sw, degrees=common)).items()}
        off, pressed, depth = press(cheeks, tris, pos, cheeks.offsets(pos, guard), obstacles(model, hidden, moved), cfg['cheeks'])
        return {'cheeks': cheeks, 'degrees': common, 'moved': moved, 'off': off, 'pressed': pressed, 'depth': depth}
    # A slight reduction is taken only when it turns a press into a graze (round
    # lobes beat dented ones); hair that frames the face anyway keeps the full
    # threat size and simply presses the cheek.
    tolerance = cfg['cheeks']['press_depth_limit']*full.scale
    chosen = attempt(1.0)
    if chosen['depth'] > tolerance:
        lo, hi = cfg['cheeks']['size_floor'], 1.0
        trial = attempt(lo)
        if trial['depth'] <= tolerance:
            chosen = trial
            for _ in range(6):
                mid = (lo+hi)/2; trial = attempt(mid)
                if trial['depth'] <= tolerance:
                    lo, chosen = mid, trial
                else:
                    hi = mid
    cheeks, moved, off, pressed, depth = (chosen[k] for k in ('cheeks', 'moved', 'off', 'pressed', 'depth'))
    digits = cfg['cheeks']['digits']
    rows, offsets = [], []
    for ids, attrs in tris:
        for i, a in zip(ids, attrs):
            rows.append(list(pos[i])+a); offsets.append(list(off[i]))
    surface = dict(part_index=body_index, bone=root, **indexed(rows, offsets, digits))
    gap = cfg['cheeks']['blush_gap']*cheeks.scale
    attachments = []
    for name in ('face_blush_left', 'face_blush_right'):
        rows, offsets = blush_on_skin(model, name, tris, pos, off, gap)
        index = next(i for i, p in enumerate(model['parts']) if p['name'] == name)
        attachments.append(dict(part_index=index, bone=root, **indexed(rows, offsets, digits)))
    in_place = [{'part_index': i, 'offsets': [round(float(x), digits) for x in o.reshape(-1)]}
                for i, o in sorted(moved.items()) if np.abs(o).max() > 1e-6]

    result = {}
    for action in cfg['actions']:
        duration = cfg['hop_seconds'] if action == 'hop' else model['clips'][action]['duration']
        count = math.ceil(duration/cfg['sample_seconds'])
        keys = {c: [] for c in ['position', 'rotation', 'scale']}
        times = [duration*i/count for i in range(count+1)]
        for t in times:
            scale, rot, lift = state(action, t, duration, cfg, height, angle)
            # Rx is zero. With Rz*Ry*S the resulting y row is enough for contact.
            ry, rz = math.radians(rot[1]), math.radians(rot[2])
            row = [math.sin(rz)*math.cos(ry)*scale[0], math.cos(rz)*scale[1], math.sin(rz)*math.sin(ry)*scale[2]]
            lowest = min(sum(row[k]*(v[k]-pivot[k]) for k in range(3))+pivot[1] for v in points)
            pos_ = [0, floor-lowest+lift, 0]
            for c, value in [('position', pos_), ('rotation', rot), ('scale', scale)]:
                keys[c].append([round(t, 8), *[round(x, 7) for x in value]])
        clip = {'duration': duration, 'loop': model['clips'][action]['loop'],
                'tracks': [{'bone': root, 'channel': c, 'keys': k} for c, k in keys.items()]}
        span = {'puff': [0, duration], 'taiso': [10.4, 13.6]}.get(action)
        if span:
            clip['expression_spans'] = [[span[0], span[1], 'cheek_puff']]
            clip['puff_envelope'] = [span[0], span[1], cfg['puff_ramp'], cfg['puff_tremor'], cfg['puff_tremor_hz']]
        result[action] = clip
    result.update(braid_follow(model, cfg, result))
    revision = {'bone_names': [b['name'] for b in model['bones']], 'clips': result,
                'puff_surface': surface, 'puff_attachments': attachments, 'puff_offsets': in_place,
                'expression_aliases': {'cheek_puff': 'neutral'},
                'cheek': dict(cheeks.describe(), pressed_vertices=int(pressed), press_depth=round(depth, 5),
                              locks={sw['bone']: {'degrees': chosen['degrees'], 'free_degrees': sw['free_degrees'], 'pivot_kind': sw['pivot_kind'],
                                                  'pivot': [round(float(x), 5) for x in sw['pivot']]} for sw in swings}),
                'body_height': height, 'hop_height': height*cfg['hop_height_body_ratio']}
    if face.get('mouth_patch'):
        revision['expression_patches'] = {'cheek_puff': [face['mouth_patch']]}
    return revision


# ---------------------------------------------------- single braid follow (v8)
def braid_follow(model, cfg, corrected=None):
    """Base clips plus rotation tracks for Marisa's single braid (hand_right).

    The braid is rigid on the head. In the waddle the lower body twists under it, and
    in brace/press the head sinks until the tuft would enter the floor. Each frame the
    braid takes the body twist at its own height (rotation about Y), then the smallest
    outward splay about Z that keeps it no deeper in the skin than at rest and its tip
    above the floor. Clips already corrected here (side bends) are extended, not
    replaced. Peaks are held, then blurred, wrapping for looping clips."""
    spec = cfg.get('braid_follow')
    names = {b['name']: i for i, b in enumerate(model['bones'])}
    braid = [i for p in model['parts'] if p['name'].startswith('braid_lock_') for i in range(p['start'], p['start']+p['count'])]
    skin = next((p for p in model['parts'] if 'body_surface' in p['name']), None)
    if not spec or not braid or skin is None or 'hand_right' not in names:
        return {}
    hand = names['hand_right']; parent = model['bones'][hand]['parent']; pivot = np.array(model['bones'][hand]['pivot'])
    V = np.array(model['vertices']).reshape(-1, 8)[:, :3]; W = np.array(model['weights'])
    # Only a braid carried by the hand bone swings; skin-following braids (sparse, bean) move with the body.
    carried = {hand} | {i for i, b in enumerate(model['bones']) if b['parent'] == hand}
    braid = [i for i in braid if int(W[i][0]) in carried]
    if not braid:
        return {}
    tri = np.arange(skin['start'], skin['start']+skin['count']).reshape(-1, 3)
    points = np.unique(np.round(V[braid], 4), axis=0)

    def euler(r):
        x, y, z = np.radians(r)
        cx, sx, cy, sy, cz, sz = np.cos(x), np.sin(x), np.cos(y), np.sin(y), np.cos(z), np.sin(z)
        return np.array([[cz*cy, cz*sy*sx-sz*cx, cz*sy*cx+sz*sx], [sz*cy, sz*sy*sx+cz*cx, sz*sy*cx-cz*sx], [-sy, cy*sx, cy*cx]])

    def value(keys, t):
        if t <= keys[0][0]: return keys[0][1:]
        for a, b in zip(keys, keys[1:]):
            if t <= b[0]:
                q = (t-a[0])/(b[0]-a[0]) if b[0] > a[0] else 0
                return [x+(y-x)*q for x, y in zip(a[1:], b[1:])]
        return keys[-1][1:]

    def frame(clip, t):
        pose = {}
        for tr in clip['tracks']:
            pose.setdefault(tr['bone'], {})[tr['channel']] = value(tr['keys'], t)
        mats = []
        for i, b in enumerate(model['bones']):
            p = pose.get(i, {}); m = np.eye(4); s = np.array(p.get('scale', [1, 1, 1]))
            m[:3, :3] = euler(p.get('rotation', [0, 0, 0]))*s; pv = np.array(b['pivot'])
            m[:3, 3] = pv+np.array(p.get('position', [0, 0, 0]))-m[:3, :3]@pv
            mats.append(mats[b['parent']]@m if b['parent'] >= 0 else m)
        return pose, np.array(mats)

    def skin_at(mats):
        hv = np.c_[V, np.ones(len(V))]; a, b, q = W[:, 0].astype(int), W[:, 1].astype(int), W[:, 2:3]
        pa = np.einsum('nij,nj->ni', mats[a], hv)[:, :3]; pb = np.einsum('nij,nj->ni', mats[b], hv)[:, :3]
        return (pa*(1-q)+pb*q)[tri]

    def depth(P, T):
        """Deepest penetration of points into the (outward-wound) skin, by nearest face."""
        A, B, C = T[:, 0], T[:, 1], T[:, 2]; n = np.cross(B-A, C-A); n /= np.linalg.norm(n, axis=1, keepdims=True)+1e-12
        centre = (A+B+C)/3; d = np.linalg.norm(P[:, None]-centre[None], axis=-1); j = d.argmin(1)
        return float(np.max(-((P-centre[j])*n[j]).sum(-1)*(d[np.arange(len(P)), j] < spec['near'])))

    def braid_at(mats, y, z):
        R = euler([0, y, z]); local = (points-pivot)@R.T+pivot
        return (mats[parent]@np.c_[local, np.ones(len(local))].T).T[:, :3]

    rest_pose, rest_mats = frame(model['clips']['idle'], 0)
    rest_skin = skin_at(rest_mats); probe = (rest_skin[:, 1]-rest_skin[:, 0]).sum(0)
    # Orient faces outward once, from the rest pose.
    nn = np.cross(rest_skin[:, 1]-rest_skin[:, 0], rest_skin[:, 2]-rest_skin[:, 0]); cc = rest_skin.mean(1)
    if (nn*(cc-np.c_[np.zeros(len(cc)), cc[:, 1], np.zeros(len(cc))])).sum(-1).mean() < 0:
        tri = tri[:, ::-1]; rest_skin = rest_skin[:, ::-1]
    baseline = depth(braid_at(rest_mats, 0, 0), rest_skin)
    slices = [(i, b['pivot'][1]) for i, b in enumerate(model['bones']) if b['name'].startswith('soft_head_slice_')]
    span = (points[:, 1].min(), points[:, 1].max())
    out = {}
    for action in spec['actions']:
        clip = (corrected or {}).get(action) or model['clips'].get(action)
        if not clip: continue
        count = math.ceil(clip['duration']/spec['sample_seconds']); times = [clip['duration']*i/count for i in range(count+1)]
        ys, zs = [], []
        for t in times:
            pose, mats = frame(clip, t)
            twist = [pose.get(i, {}).get('rotation', [0, 0, 0])[1] for i, y in slices if span[0] <= y <= span[1]]
            y = float(np.mean(twist)) if twist else 0.0
            T = skin_at(mats); z = 0.0
            while z < spec['max_splay_degrees']:
                P = braid_at(mats, y, z)
                if depth(P, T) <= baseline+spec['skin_tolerance'] and P[:, 1].min() >= spec['floor_clearance']: break
                z += spec['step_degrees']
            ys.append(y); zs.append(z)
        if max(zs) < 1 and max(abs(v) for v in ys) < 1:
            continue
        k = max(1, round(spec['smooth_seconds']/spec['sample_seconds']))
        def held(values):
            v = np.array(values); m = len(v)
            idx = lambda j: j % (m-1) if clip['loop'] else min(max(j, 0), m-1)
            peak = np.array([max(v[idx(j+o)] for o in range(-k, k+1)) for j in range(m)])
            return [float(np.mean([peak[idx(j+o)] for o in range(-k, k+1)])) for j in range(m)]
        zs = held(zs)
        keys = [[round(t, 8), 0, round(y, 5), round(z, 5)] for t, y, z in zip(times, ys, zs)]
        if clip['loop']: keys[-1][2:] = keys[0][2:]
        out[action] = dict(clip, tracks=[tr for tr in clip['tracks'] if tr['bone'] != hand]+[{'bone': hand, 'channel': 'rotation', 'keys': keys}])
    return out
