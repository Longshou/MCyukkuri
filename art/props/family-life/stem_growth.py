#!/usr/bin/env python3
"""Reference geometry of the growing stem and of the stem kept on remains, with the clearance check.

This is the data contract the game follows (FamilyGeometry / FamilyRenderer / RemainsRenderer), at mother scale 1 in
model units (16 per block, ground y=0, forward -Z). Inputs: recipe.json "growth" (curves), the stem component
(standards/yukkuri-v1/components/common/stem.json: arc, sockets, radius, pendant), and the remains recipe "stem" block
(standards/yukkuri-library/drafts/remains/recipe.json: sag, pedicel slack, colour).

Living, at gestation progress p:
- g = stalk(p). Arc points and sockets are FamilyGeometry.point with length(count, variance) * g (rise and reach
  shrink; the depth clearance still ramps with the actual rise over clearance_rise, so the young stalk already leans out
  over the brow and brim); the pendant (pedicel) length is * g^2 so buds stay close under the young stalk.
  Replaces the current root->point lerp in arc(); socket() takes the same g.
- stalk and pedicel radius *= thickness_min + (1-thickness_min)*g.
- fruit size s = emerge(p) * lerp(min(bud_scale, b), b, ripen(p)) for a child of birth size b; not drawn while s = 0.
- the fruit hangs plumb: its hanging anchor (registered, else crown top) at socket - (0, pendant*g^2, 0), as childBase.

Remains (frozen at the death progress): the living pose at that p, then
- root' = remains pose (form scale x fullness/rot factors, roll about the ground contact) applied to the living root,
  which stays on the squashed crown because the living root sits on the living crown;
- the stalk keeps its own size (not squashed). Living arc point i, relative to the living root, is turned by the corpse
  roll and lowered by sag * rise * u^2, where u is its arc-length fraction along the living arc and rise the living arc's
  highest point above the root: a limp sag that keeps horizontal reach and fruit spacing. A socket takes the u of its
  closest point on the living arc polyline;
- pedicels hang plumb, length * pedicel_slack; a fruit whose base would go below the ground rests on it (y = 0) and its
  pedicel shortens to reach;
- stalk and pedicels take the remains stem colour; fruit use remains_stem_fruit.png where the model has it, else sleep.png.

  python stem_growth.py --check     # clearance over counts 1-5, Reimu/Marisa mothers, every child kind, p every 1%
"""
from pathlib import Path
import argparse
from itertools import product
import json
import sys

import numpy as np

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(HERE))
from fit_stem import Stem  # noqa: E402

sys.path.insert(0, str(ROOT/'standards/yukkuri-library/motions/variants/lore'))
import preview as P  # noqa: E402

RECIPE = HERE/'recipe.json'
STEM = ROOT/'standards/yukkuri-v1/components/common/stem.json'
REMAINS = ROOT/'standards/yukkuri-library/drafts/remains/recipe.json'
KINDS = ['reimu', 'marisa', 'chen', 'alice', 'patchouli', 'remilia', 'flandre']
MOTHERS = ['reimu', 'marisa']
# As fit_stem's spacing set: tails, wings and long hair of the other kinds are soft and only reported.
FIRM = ['reimu', 'marisa']
# Allowed: fruit skin grazing hair by LIMIT['mother'], the stalk root embedded within ROOT_EMBED of the root.
LIMIT = {'mother': 0.15, 'fruit': 0.0, 'ground': 0.0, 'stalk': 0.0, 'body': 0.15}
ROOT_EMBED = 3.0  # arc length from the root where the stalk may still run through hair or a hat brim


def curve(knots, p):
    k = np.asarray(knots, float)
    return float(np.interp(p, k[:, 0], k[:, 1]))


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


class Growth:
    def __init__(self, recipe=None, stem=None, remains=None):
        self.recipe = recipe or read(RECIPE)
        self.stem_data = stem or read(STEM)
        self.remains = (remains or read(REMAINS))['stem']
        self.g = self.recipe['growth']
        self.stem = Stem(self.recipe, self.stem_data)
        self.units = self.stem_data['reference_units_to_model']

    # ------------------------------------------------------------ curves
    def stalk(self, p):
        return curve(self.g['stalk'], p) if p > 0 else 0.0

    def thickness(self, p):
        t = self.g['thickness_min']
        return t+(1-t)*self.stalk(p)

    def fruit(self, p, birth):
        emerge = curve(self.g['fruit_emerge'], p)
        return emerge*(min(self.g['bud_scale'], birth)+(birth-min(self.g['bud_scale'], birth))*curve(self.g['fruit_ripen'], p))

    # ------------------------------------------------------------ living geometry (mother scale 1, model units)
    def length(self, count, variance):
        return self.recipe['count_length_scale'][count-1]*variance

    def root(self, mother):
        return np.asarray(self.recipe['variants'][mother]['root'], float)

    def arc(self, count, p, mother, variance=1.0):
        """Authored centerline points (FamilyGeometry.point) with the length scale times g: rise and reach shrink,
        the early depth clearance still follows the actual rise, so even a sprout leans out over the brow first."""
        L = self.length(count, variance)*self.stalk(p)
        return np.asarray([self.stem.point(zy, L, mother) for zy in self.stem_data['centerline']])

    def radius(self, count, along, p, variance=1.0):
        r = self.stem_data['radius']
        return (r[0]+(r[1]-r[0])*along)*self.recipe['count_radius_scale'][count-1]*variance*self.thickness(p)

    def sockets(self, count, p, mother, variance=1.0):
        L = self.length(count, variance)*self.stalk(p)
        return np.asarray([self.stem.point(zy, L, mother) for zy in self.stem_data['socket_layouts'][str(count)]])

    def pendant(self, p):
        return self.stem_data['pendant_length_reference']*self.units*self.stalk(p)**2

    def anchor(self, kind, size):
        var = self.recipe['variants'][kind]
        a = var.get('hanging_anchor')
        return np.asarray(a if a else [0, var['crown_height'], 0], float)*size

    def hang_clear(self, kind, size):
        var = self.recipe['variants'][kind]
        return var.get('hang_clearance_height', var['crown_height'])*size

    def living(self, count, p, mother, kinds, births, variance=1.0):
        """The whole living pose: arc, radii, and per fruit (socket, pedicel tip, fruit base, size, kind)."""
        arc = self.arc(count, p, mother, variance)
        fruit = []
        for i, socket in enumerate(self.sockets(count, p, mother, variance)):
            size = self.fruit(p, births[i])
            a = self.anchor(kinds[i], size)
            drop = self.pendant(p)+self.hang_clear(kinds[i], size)-a[1]
            tip = socket-[0, drop, 0]
            fruit.append(dict(socket=socket, tip=tip, base=tip-a, size=size, kind=kinds[i]))
        return dict(arc=arc, fruit=fruit, root=self.root(mother), count=count, p=p, variance=variance)

    # ------------------------------------------------------------ remains
    def remains_root(self, mother, form):
        """The living root carried by the corpse pose (form scale and roll; fullness/rot factors multiply in game)."""
        sx, sy, sz = form['scale']
        r = self.root(mother)*[sx, sy, sz]
        return roll(r, form.get('roll', 0))

    def remains_pose(self, living, mother, form):
        """The frozen living pose carried by the corpse: rooted at the carried root, turned by the corpse roll, and sagged:
        a point at arc fraction u drops sag * rise * u^2 (rise = the living stalk's highest point above its root), so the
        limp stalk keeps its horizontal reach and fruit spacing while its outer part hangs down."""
        arc, root, turn = living['arc'], living['root'], form.get('roll', 0)
        rel = np.asarray([roll(q-root, turn) for q in arc])
        u = np.r_[0, np.cumsum(np.linalg.norm(np.diff(rel, axis=0), axis=1))]
        u = u/(u[-1] or 1)
        drop = self.remains['sag']*max(0.0, float(rel[:, 1].max()))
        start = self.remains_root(mother, form)
        out = start+rel-np.outer(u**2, [0, drop, 0])
        fruit = []
        for f in living['fruit']:
            q = roll(f['socket']-root, turn)
            k, w = along(rel, q)
            at = u[k]+(u[k+1]-u[k])*w
            socket = start+q-[0, drop*at**2, 0]
            size = f['size']
            a = self.anchor(f['kind'], size)
            hang = (self.pendant(living['p'])+self.hang_clear(f['kind'], size)-a[1])*self.remains['pedicel_slack']
            tip = socket-[0, hang, 0]
            base = tip-a
            if base[1] < 0:  # resting on the ground: the pedicel shortens to reach
                tip, base = tip-[0, base[1], 0], base-[0, base[1], 0]
            fruit.append(dict(socket=socket, tip=tip, base=base, size=size, kind=f['kind']))
        return dict(arc=out, fruit=fruit, root=start, count=living['count'], p=living['p'], variance=living['variance'])


def roll(v, degrees):
    a = np.radians(degrees)
    c, s = np.cos(a), np.sin(a)
    return np.asarray([v[0]*c-v[1]*s, v[0]*s+v[1]*c, v[2]])


def along(points, q):
    """Nearest segment of a polyline to q and the parameter (0-1) of the closest point on it."""
    best, index, param = 1e18, 0, 0.0
    for i in range(len(points)-1):
        a, d = points[i], points[i+1]-points[i]
        t = float(np.clip(np.dot(q-a, d)/max(1e-12, np.dot(d, d)), 0, 1))
        e = float(np.sum((a+d*t-q)**2))
        if e < best:
            best, index, param = e, i, t
    return index, param


# ---------------------------------------------------------------- clearance
class Solid:
    """Inside test by vertical-ray parity on a 0.2-unit grid of a posed mesh (triangles n x 3 x 3)."""
    STEP = 0.2

    def __init__(self, tri):
        self.lo = tri.reshape(-1, 3).min(0)-1
        hi = tri.reshape(-1, 3).max(0)+1
        self.n = np.ceil((hi-self.lo)/self.STEP).astype(int)+1
        self.cells = {}
        for t in tri:
            xz = t[:, [0, 2]]
            x0, z0 = np.floor((xz.min(0)-self.lo[[0, 2]])/self.STEP).astype(int)
            x1, z1 = np.ceil((xz.max(0)-self.lo[[0, 2]])/self.STEP).astype(int)
            a, b, c = t
            det = (b[2]-c[2])*(a[0]-c[0])+(c[0]-b[0])*(a[2]-c[2])
            if abs(det) < 1e-12:
                continue
            for ix in range(x0, x1+1):
                x = self.lo[0]+ix*self.STEP
                for iz in range(z0, z1+1):
                    z = self.lo[2]+iz*self.STEP
                    w0 = ((b[2]-c[2])*(x-c[0])+(c[0]-b[0])*(z-c[2]))/det
                    w1 = ((c[2]-a[2])*(x-c[0])+(a[0]-c[0])*(z-c[2]))/det
                    w2 = 1-w0-w1
                    if w0 >= 0 and w1 >= 0 and w2 >= 0:
                        self.cells.setdefault((ix, iz), []).append(w0*a[1]+w1*b[1]+w2*c[1])
        width = max(len(v) for v in self.cells.values())
        self.grid = np.full((self.n[0]+1, self.n[2]+1, width), np.inf)
        for (ix, iz), ys in self.cells.items():
            self.grid[ix, iz, :len(ys)] = np.sort(ys)

    def depth(self, points):
        """Per point: how far inside (>0) the solid it is, 0 outside."""
        ix = np.clip(np.rint((points[:, 0]-self.lo[0])/self.STEP).astype(int), 0, self.n[0])
        iz = np.clip(np.rint((points[:, 2]-self.lo[2])/self.STEP).astype(int), 0, self.n[2])
        rows, y = self.grid[ix, iz], points[:, 1:2]
        finite = np.isfinite(rows)
        above = (rows > y) & finite
        inside = above.sum(1) % 2 == 1
        up = np.where(above, rows-y, np.inf).min(1)
        down = np.where(finite & ~above, y-rows, np.inf).min(1)
        return np.where(inside & np.isfinite(down), np.minimum(up, down), 0)


def triangles(kind):
    posed = P.Posed(kind, read(P.ASSETS/'yukkuri_presentation/motion_corrections.json'))
    pos, _, _ = posed.frame('idle', 0)
    return pos


def samples(tri, keep=2500):
    """Surface points of a posed mesh: every extreme point plus a fixed even subset of vertices and centroids."""
    pts = np.unique(np.round(np.concatenate([tri.reshape(-1, 3), tri.mean(1)]), 3), axis=0)
    extreme = np.r_[pts.argmin(0), pts.argmax(0)]
    rest = np.random.default_rng(7).choice(len(pts), min(keep, len(pts)), replace=False)
    return pts[np.unique(np.r_[extreme, rest])]


class Checker:
    def __init__(self, growth):
        self.G = growth
        self.tri = {k: triangles(k) for k in KINDS}
        self.points = {k: samples(self.tri[k]) for k in KINDS}
        self.body = {k: Solid(self.tri[k]) for k in KINDS}
        self.solid = {}

    def mother(self, mother, form=None):
        key = (mother, None if form is None else json.dumps(form, sort_keys=True))
        if key not in self.solid:
            tri = self.tri[mother]
            if form:
                tri = np.asarray([[roll(v*form['scale'], form.get('roll', 0)) for v in t] for t in tri])
            self.solid[key] = Solid(tri)
        return self.solid[key]

    def check(self, pose, mother, form=None):
        """Worst values of one pose: fruit inside the mother (depth), fruit-fruit box overlap, lowest fruit point,
        stalk through a fruit, stalk inside the mother away from its root."""
        solid = self.mother(mother, form)
        worst = dict(mother=0.0, fruit=0.0, ground=9e9, stalk=0.0, body=0.0)
        boxes = []
        for f in pose['fruit']:
            if f['size'] <= 0:
                continue
            pts = self.points[f['kind']]*f['size']+f['base']
            worst['mother'] = max(worst['mother'], float(solid.depth(pts).max()))
            worst['ground'] = min(worst['ground'], float(pts[:, 1].min()))
            lo, hi = pts.min(0), pts.max(0)
            c, h = (lo+hi)/2, (hi-lo)/2*0.85  # rounded bodies: shrink the box away from its empty corners
            boxes.append((c-h, c+h, f))
        for i in range(len(boxes)):
            for j in range(i+1, len(boxes)):
                overlap = np.minimum(boxes[i][1], boxes[j][1])-np.maximum(boxes[i][0], boxes[j][0])
                worst['fruit'] = max(worst['fruit'], float(max(0, overlap.min())))
        line = smooth(pose['arc'])
        for _, _, f in boxes:  # the stalk through a fruit's own mesh (its local frame, depth back in model units)
            worst['stalk'] = max(worst['stalk'], float(self.body[f['kind']].depth((line-f['base'])/f['size']).max())*f['size'])
        along = np.r_[0, np.cumsum(np.linalg.norm(np.diff(line, axis=0), axis=1))]
        away = line[along > ROOT_EMBED]
        worst['body'] = float(solid.depth(away).max()) if len(away) else 0.0
        return worst


def smooth(points, sub=4):
    """FamilyRenderer.smooth: uniform Catmull-Rom through the points, ends clamped."""
    n = len(points)
    if n < 3:
        return points
    out = []
    for k in range(n-1):
        p0, p1, p2, p3 = points[max(0, k-1)], points[k], points[k+1], points[min(n-1, k+2)]
        for s in range(sub):
            t = s/sub
            out.append((2*p1+(p2-p0)*t+(2*p0-5*p1+4*p2-p3)*t*t+(3*p1-p0-3*p2+p3)*t*t*t)/2)
    out.append(points[-1])
    return np.asarray(out)


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--check', action='store_true', help='accepted for scripts/export.py; the script never writes and always exits non-zero on a collision')
    ap.add_argument('--step', type=float, default=0.01)
    args = ap.parse_args()
    G, form = Growth(), read(REMAINS)['forms']['starved']
    C = Checker(G)
    progress = [float(p) for p in np.round(np.arange(args.step, 1+1e-9, args.step), 4)]
    worst, failures, soft = {}, {}, {}
    # The ripe stalk may cross a hat brim near its root (Marisa); growth may cross it no deeper than the deepest ripe one.
    brim = {m: max(C.check(G.living(c, 1, m, [k]*c, [b]*c, v), m)['body'] for c, v, b, k in product(range(1, 6), (0.93, 1.07), (0.10, 0.19), FIRM))
            for m in MOTHERS}
    for count, mother in product(range(1, 6), MOTHERS):
        for variance, birth, kind in product((0.93, 1.07), (0.10, 0.19), KINDS):
            # The ripe living pose is the accepted fit_stem layout; growth and remains may not be worse than it.
            ripe = C.check(G.living(count, 1, mother, [kind]*count, [birth]*count, variance), mother)
            for p in progress:
                living = G.living(count, p, mother, [kind]*count, [birth]*count, variance)
                for phase, pose, solid in (('living', living, None), ('remains', G.remains_pose(living, mother, form), form)):
                    w = C.check(pose, mother, solid)
                    s = worst.setdefault((phase, count), dict(mother=0, fruit=0, ground=9e9, stalk=0, body=0))
                    for k, v in w.items():
                        s[k] = min(s[k], v) if k == 'ground' else max(s[k], v)
                        allowed = max(LIMIT[k], ripe[k]+0.02, brim[mother]+0.02 if k == 'body' else 0)
                        if v < LIMIT[k] if k == 'ground' else v > allowed:
                            rec = (failures if kind in FIRM else soft).setdefault((phase, count, mother, k), dict(worst=v, p=[p, p], at=(variance, birth, kind, p)))
                            rec['p'] = [min(rec['p'][0], p), max(rec['p'][1], p)]
                            if (v < rec['worst']) if k == 'ground' else (v > rec['worst']):
                                rec['worst'], rec['at'] = v, (variance, birth, kind, p)
    for (phase, count), s in sorted(worst.items()):
        print(phase, count, {k: round(v, 2) for k, v in s.items()})
    for label, table in (('SOFT', soft), ('FAIL', failures)):
        for key, rec in sorted(table.items()):
            print(label, *key, 'worst', round(rec['worst'], 2), 'p', rec['p'], 'at variance/birth/kind/p', rec['at'])
    print('clearance', 'ok' if not failures else f'{len(failures)} failing groups')
    if failures:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
