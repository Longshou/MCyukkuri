#!/usr/bin/env python3
"""Reference geometry of the growing stem and of the withered stem kept on remains, with the order and clearance check.

This is the data contract the game follows (FamilyGeometry / FamilyRenderer / RemainsRenderer), at mother scale 1 in
model units (16 per block, ground y=0, forward -Z). Inputs: recipe.json "growth" (curves), the stem component
(standards/yukkuri-v1/components/common/stem.json: arc, sockets, radius, pendant), and the remains recipe "stem" block
(standards/yukkuri-library/drafts/remains/recipe.json: droop, thickness, slack, shrivel, colour).

Living, at gestation progress p (the stem grows by extension, never by scaling):
- the stalk is always the full-length ripe arc (FamilyGeometry.arc, smoothed as FamilyRenderer.smooth); its final radius
  runs from the root radius to the tip radius along the smoothed points. Only the part up to arc length
  extend(p) x total is drawn; within tip_taper (x maternal scale) behind the growing end the radius narrows smoothly to
  the stalk's tip radius, so the shoot is pointed while the grown part keeps its final width;
- a socket sits at arc fraction u (its closest point on the smoothed line). Its pedicel starts when the growing tip
  reaches u (the first p with extend(p) >= u) and extends straight down to its full pendant over pedicel_grow of p;
- the fruit of socket i emerges after the whole stalk and its own pedicel: fruit_emerge shifted by fruit_stagger per
  place from the root (index count-1 is nearest the root), size s = emerge * lerp(min(bud_scale, b), b, ripen(p)) for a
  child of birth size b; not drawn while s = 0. It hangs plumb with its hanging anchor (registered, else crown top) at the
  pedicel end, as FamilyGeometry.childBase.

Remains (frozen at the death progress): the grown part of the living pose at that p, withered:
- root' = remains pose (form scale x fullness/rot factors, roll about the ground contact) applied to the living root,
  which stays on the squashed crown because the living root sits on the living crown;
- the stalk keeps its length (not squashed) but is turned by the corpse roll and wilts: walking from the root, each
  segment keeps its length and heading, its pitch lowered by droop_degrees x the litter's length scale (count_length_scale
  x variation, so a longer stalk bends further) x the segment's arc fraction of the full stalk, never steeper than
  steepest_degrees below level (sockets along a falling stalk stay spread out); a segment that would go under the ground
  (its radius above y = 0) bends to lie along it. Radius x thickness;
- a pedicel hangs from its socket's wilted point, pedicel_slack longer than in life, splayed pedicel_splay_degrees from
  plumb across the stalk's vertical plane, to alternate sides from the root; when the fruit's base would reach the
  ground the rest of its length lays the fruit out beside the stalk, and the pedicel sags between them. Fruit keep their
  death size, shrunk per axis by fruit_shrivel;
- stalk and pedicels take the remains stem colour; fruit use remains_stem_fruit.png where the model has it, else sleep.png.

  python stem_growth.py --check     # order, and clearance over counts 1-5, Reimu/Marisa mothers, every child kind, p every 1%
"""
from pathlib import Path
import argparse
from itertools import product
import json
import math
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
SUBDIVISIONS = 4  # FamilyRenderer.STALK_SUBDIVISIONS


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
    def extension(self, p):
        """Grown arc-length fraction of the stalk."""
        return curve(self.g['extend'], p) if p > 0 else 0.0

    def reached(self, u):
        """The first progress at which the growing tip reaches arc fraction u (extend knots strictly rise)."""
        k = np.asarray(self.g['extend'], float)
        return float(np.interp(u, k[:, 1], k[:, 0]))

    def pedicel_growth(self, p, u):
        return float(np.clip((p-self.reached(u))/self.g['pedicel_grow'], 0, 1)) if p > 0 else 0.0

    def emerge_start(self, count, index):
        return self.g['fruit_emerge'][0][0]+self.g['fruit_stagger']*(count-1-index)

    def fruit(self, p, count, index, birth):
        emerge = curve(self.g['fruit_emerge'], p-self.g['fruit_stagger']*(count-1-index))
        bud = min(self.g['bud_scale'], birth)
        return emerge*(bud+(birth-bud)*curve(self.g['fruit_ripen'], p))

    # ------------------------------------------------------------ living geometry (mother scale 1, model units)
    def length(self, count, variance):
        return self.recipe['count_length_scale'][count-1]*variance

    def root(self, mother):
        return np.asarray(self.recipe['variants'][mother]['root'], float)

    def arc(self, count, mother, variance=1.0):
        """The authored centerline points of the full-length stalk (FamilyGeometry.arc)."""
        L = self.length(count, variance)
        return np.asarray([self.stem.point(zy, L, mother) for zy in self.stem_data['centerline']])

    def radius(self, count, along, variance=1.0):
        r = self.stem_data['radius']
        return (r[0]+(r[1]-r[0])*along)*self.recipe['count_radius_scale'][count-1]*variance

    def line(self, count, mother, variance=1.0):
        """The smoothed full stalk, its final radius per point and the arc length at each point."""
        pts = smooth(self.arc(count, mother, variance))
        radii = np.linspace(self.radius(count, 0, variance), self.radius(count, 1, variance), len(pts))
        return pts, radii, np.r_[0, np.cumsum(np.linalg.norm(np.diff(pts, axis=0), axis=1))]

    def grown(self, count, p, mother, variance=1.0, scale=1.0):
        """The drawn part of the stalk: points, radii (tapered behind the growing end) and their arc lengths."""
        pts, radii, s = self.line(count, mother, variance)
        return grow(pts, radii, s, self.extension(p), self.g['tip_taper']*scale)

    def sockets(self, count, mother, variance=1.0):
        L = self.length(count, variance)
        return np.asarray([self.stem.point(zy, L, mother) for zy in self.stem_data['socket_layouts'][str(count)]])

    def pendant(self):
        return self.stem_data['pendant_length_reference']*self.units

    def anchor(self, kind, size):
        var = self.recipe['variants'][kind]
        a = var.get('hanging_anchor')
        return np.asarray(a if a else [0, var['crown_height'], 0], float)*size

    def hang_clear(self, kind, size):
        var = self.recipe['variants'][kind]
        return var.get('hang_clearance_height', var['crown_height'])*size

    def living(self, count, p, mother, kinds, births, variance=1.0):
        """The whole living pose: grown stalk, and per socket its arc fraction, pedicel growth, tip, fruit base and size."""
        full, _, s = self.line(count, mother, variance)
        arc, radii, along = self.grown(count, p, mother, variance)
        fruit = []
        for i, socket in enumerate(self.sockets(count, mother, variance)):
            u = fraction(full, s, socket)
            q = self.pedicel_growth(p, u)
            size = self.fruit(p, count, i, births[i])
            a = self.anchor(kinds[i], size)
            drop = q*self.pendant()+(self.hang_clear(kinds[i], size)-a[1] if size > 0 else 0)
            tip = socket-[0, drop, 0]
            fruit.append(dict(index=i, socket=socket, u=u, q=q, tip=tip, base=tip-a, size=size, shrivel=np.ones(3), kind=kinds[i],
                              path=np.asarray([socket, tip])))
        return dict(arc=arc, radii=radii, along=along, length=float(s[-1]), fruit=fruit, root=self.root(mother), count=count, p=p,
                    variance=variance)

    # ------------------------------------------------------------ remains
    def remains_root(self, mother, form):
        """The living root carried by the corpse pose (form scale and roll; fullness/rot factors multiply in game)."""
        sx, sy, sz = form['scale']
        r = self.root(mother)*[sx, sy, sz]
        return roll(r, form.get('roll', 0))

    def remains_pose(self, living, mother, form):
        """The frozen living pose carried by the corpse and withered (module docstring)."""
        R = self.remains
        start = self.remains_root(mother, form)
        rel = np.asarray([roll(q-living['root'], form.get('roll', 0)) for q in living['arc']])
        radii = living['radii']*R['thickness']
        droop = math.radians(R['droop_degrees'])*self.length(living['count'], living['variance'])
        arc = wilt(start, rel, living['along'], living['length'], radii, droop, math.radians(R['steepest_degrees']))
        shrivel = np.asarray(R['fruit_shrivel'], float)
        fruit = []
        for index, f in enumerate(living['fruit']):
            if f['q'] <= 0:
                continue
            at = f['u']*living['length']
            socket = point_at(arc, living['along'], at)
            side = 1 if (living['count']-1-index) % 2 == 0 else -1
            size = f['size']
            a = self.anchor(f['kind'], size)*shrivel
            length = (f['q']*self.pendant()+(self.hang_clear(f['kind'], size)*shrivel[1]-a[1] if size > 0 else 0))*R['pedicel_slack']
            tip, path = limp(socket, length, a[1], side, math.radians(R['pedicel_splay_degrees']))
            fruit.append(dict(index=index, socket=socket, u=f['u'], q=f['q'], tip=tip, base=tip-a, size=size, shrivel=shrivel, kind=f['kind'], path=path))
        return dict(arc=arc, radii=radii, along=living['along'], length=living['length'], fruit=fruit, root=start,
                    count=living['count'], p=living['p'], variance=living['variance'])


def grow(pts, radii, s, extension, taper):
    """FamilyRenderer.grown: the line cut at extension x its length, radii narrowing to the tip radius behind the cut."""
    if extension <= 0:
        return pts[:0], radii[:0], s[:0]
    end = extension*s[-1]
    keep = int(np.searchsorted(s, end, side='left'))
    if keep >= len(s):
        P_, R_, S_ = pts.copy(), radii.copy(), s.copy()
    else:
        w = (end-s[keep-1])/max(1e-12, s[keep]-s[keep-1])
        P_ = np.r_[pts[:keep], [pts[keep-1]+(pts[keep]-pts[keep-1])*w]]
        R_ = np.r_[radii[:keep], [radii[keep-1]+(radii[keep]-radii[keep-1])*w]]
        S_ = np.r_[s[:keep], [end]]
    tip = radii[-1]
    k = np.clip((end-S_)/taper, 0, 1)
    k = k*k*(3-2*k)
    return P_, np.where(R_ > tip, tip+(R_-tip)*k, R_), S_


def wilt(start, rel, along, total, radii, droop, steepest):
    """FamilyRenderer.wilt: from the root, each segment keeps its length and heading, its pitch lowered by
    droop x its arc fraction of the full stalk, never past straight down; it lies along the ground rather than under it."""
    out = [start+rel[0]]
    heading = np.array([0.0, 0.0, -1.0])
    for k in range(len(rel)-1):
        d = rel[k+1]-rel[k]
        n = float(np.linalg.norm(d))
        h = np.array([d[0], 0.0, d[2]])
        hl = float(np.linalg.norm(h))
        if hl > 1e-9:
            heading = h/hl
        pitch = max(-steepest, math.atan2(d[1], hl)-droop*(along[k]+along[k+1])/2/total)
        floor = radii[k+1]
        if n > 1e-12 and out[-1][1]+n*math.sin(pitch) < floor:
            pitch = math.asin(float(np.clip((floor-out[-1][1])/n, -1, 1)))
        out.append(out[-1]+n*(math.cos(pitch)*heading+[0, math.sin(pitch), 0]))
    return np.asarray(out)


def point_at(points, along, at):
    k = int(np.clip(np.searchsorted(along, at), 1, len(along)-1))
    w = (at-along[k-1])/max(1e-12, along[k]-along[k-1])
    return points[k-1]+(points[k]-points[k-1])*float(np.clip(w, 0, 1))


def limp(socket, length, rest, side, splay):
    """FamilyRenderer.limp: a slack pedicel of this length from the socket, splayed from plumb to one side of the stalk;
    when the fruit's base (rest below the pedicel end) would reach the ground the rest of its length lays the fruit out
    beside the stalk. Returns the end and the sagging path (a quadratic Bezier)."""
    drop = min(length*math.cos(splay), max(0.0, float(socket[1]-rest)))
    out = math.sqrt(max(0.0, length*length-drop*drop))
    tip = socket+[side*out, -drop, 0]  # across the stalk's vertical plane, which wilting keeps
    tip[1] = max(tip[1], rest)
    chord = float(np.linalg.norm(tip-socket))
    control = (socket+tip)/2-[0, 0.2*chord+0.5*max(0.0, length-chord), 0]
    control[1] = max(control[1], 0.0)
    t = np.linspace(0, 1, 8)[:, None]
    return tip, (1-t)**2*socket+2*(1-t)*t*control+t*t*tip


def fraction(line, s, q):
    """Arc fraction of the point of a polyline closest to q."""
    k, w = along_segment(line, q)
    return float((s[k]+(s[k+1]-s[k])*w)/s[-1])


def roll(v, degrees):
    a = np.radians(degrees)
    c, s = np.cos(a), np.sin(a)
    return np.asarray([v[0]*c-v[1]*s, v[0]*s+v[1]*c, v[2]])


def along_segment(points, q):
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
        stalk or another pedicel through a fruit, stalk inside the mother away from its root."""
        solid = self.mother(mother, form)
        worst = dict(mother=0.0, fruit=0.0, ground=9e9, stalk=0.0, body=0.0)
        boxes = []
        for f in pose['fruit']:
            if f['size'] <= 0:
                continue
            scale = f['size']*f['shrivel']
            pts = self.points[f['kind']]*scale+f['base']
            worst['mother'] = max(worst['mother'], float(solid.depth(pts).max()))
            worst['ground'] = min(worst['ground'], float(pts[:, 1].min()))
            lo, hi = pts.min(0), pts.max(0)
            c, h = (lo+hi)/2, (hi-lo)/2*0.85  # rounded bodies: shrink the box away from its empty corners
            boxes.append((c-h, c+h, f, scale))
        for i in range(len(boxes)):
            for j in range(i+1, len(boxes)):
                overlap = np.minimum(boxes[i][1], boxes[j][1])-np.maximum(boxes[i][0], boxes[j][0])
                worst['fruit'] = max(worst['fruit'], float(max(0, overlap.min())))
        line = pose['arc']  # already the smoothed stalk
        for _, _, f, scale in boxes:  # the stalk and the other pedicels through a fruit's own mesh (its local frame, depth in model units)
            others = [g['path'] for g in pose['fruit'] if g is not f]
            through = np.concatenate([line, *others]) if others or len(line) else line
            if len(through):
                worst['stalk'] = max(worst['stalk'], float(self.body[f['kind']].depth((through-f['base'])/scale).max())*float(scale.max()))
        if len(line) > 1:
            along = np.r_[0, np.cumsum(np.linalg.norm(np.diff(line, axis=0), axis=1))]
            away = line[along > ROOT_EMBED]
            worst['body'] = float(solid.depth(away).max()) if len(away) else 0.0
        return worst


def smooth(points, sub=SUBDIVISIONS):
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


def order(G):
    """Every fruit emerges only after the whole stalk has grown and its own pedicel is complete."""
    stalk_done = G.reached(1.0)
    bad = []
    for count in range(1, 6):
        for mother, variance in product(MOTHERS, (0.93, 1.0, 1.07)):
            full, _, s = G.line(count, mother, variance)
            for i, socket in enumerate(G.sockets(count, mother, variance)):
                done = G.reached(fraction(full, s, socket))+G.g['pedicel_grow']
                start = G.emerge_start(count, i)
                if start < max(stalk_done, done)-1e-9:
                    bad.append((count, mother, variance, i, round(start, 4), round(max(stalk_done, done), 4)))
    return bad


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--check', action='store_true', help='accepted for scripts/export.py; the script never writes and always exits non-zero on a failure')
    ap.add_argument('--step', type=float, default=0.01)
    args = ap.parse_args()
    G, form = Growth(), read(REMAINS)['forms']['starved']
    misordered = order(G)
    for row in misordered:
        print('ORDER count/mother/variance/index emerges', row[4], 'before stalk and pedicel are done at', row[5], row[:4])
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
    print('order', 'ok' if not misordered else f'{len(misordered)} fruit too early')
    print('clearance', 'ok' if not failures else f'{len(failures)} failing groups')
    if failures or misordered:
        raise SystemExit(1)


if __name__ == '__main__':
    main()
