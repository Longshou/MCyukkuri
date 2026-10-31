"""Marisa's own hat turned upside down, its crown pressed flat: the cradle on land and the boat afloat.

The user (2026-10-15) asked for the hat itself turned over with the soft crown at the bottom
squashed; "a nest" was a likeness, not a new shape. The worn hat of the selected runtime Marisa
(axis-aligned cuboids brim_*, hat_*, bow_*) is copied with the brim and three crown tiers
opened into a deep well, retaining a shallow front foothold in the solid base; outer faces keep the hat's own atlas
cells (the prop draws with the Marisa atlas, as the worn hat does), faces turned to the well use
the dark brim-edge cell. The game draws it turned over with the crown pressed down along the hat's
axis: on dry ground to a low hat whose crown rests on the ground, afloat less (the crown under the
water). The braid clips (motions/variants/lore/hat.py) end with the worn hat in exactly this pose,
so the set-down hat takes its place without a jump.

Frame: origin at the opening's centre on the brim (the face that sat on the head), +Y up out of
the opening, the long brim axis on Z, the worn hat's front, where the bow is, on +X.

Placements put the hat where the game sets it to catch and the catcher where her braid holds its
brim: a helper beside it (searched over her angle and place for braid reach, clearance from the
swollen mother and from the hat, and a view of the outlet), or the mother before her own outlet.
The hat stands clear of the live exit: a newborn is pushed out over its near brim on a short arc
(delivery.py, also used by the births preview and mirrored in the game). Python and numpy only.

  python build.py [--check]
"""
from pathlib import Path
import argparse, hashlib, itertools, json, math, sys
import numpy as np

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import delivery as DL
ROOT = HERE.parents[3]
ASSETS = ROOT/'src/main/resources/assets/mcyukkuri'
MODEL = ASSETS/'yukkuri/marisa.json'
BODY = ASSETS/'yukkuri_presentation/body_geometry.json'
PROP = ASSETS/'yukkuri_props/marisa_hat_turned.json'
OLD_PROPS = [ASSETS/'yukkuri_props/marisa_hat_nest.json', ASSETS/'yukkuri_props/marisa_hat_vessel.json']
METRICS = ASSETS/'yukkuri_presentation/hat_vessel.json'
INDEX = ASSETS/'yukkuri_props/subspecies-index.json'
TEXTURE = 'mcyukkuri:textures/entity/marisa/neutral.png'
GROWTH = json.loads((ASSETS/'yukkuri_presentation/family.json').read_text(encoding='utf-8'))['growth']
LIVE_BABY = GROWTH['birth']['live']['scale']  # the size standard (GrowthSize.LIVE)
R = json.loads((HERE/'recipe.json').read_text(encoding='utf-8'))


def sha(data): return hashlib.sha256(data).hexdigest()


def rot_x(a):
    c, s = math.cos(a), math.sin(a); return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def rot_y(a):  # x' = x cos + z sin, z' = -x sin + z cos (the game's Axis.YP / Vec3.yRot)
    c, s = math.cos(a), math.sin(a); return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


# ------------------------------------------------------------------ the worn hat, opened
def worn_boxes(model):
    """Each hat cuboid with its bounds and the atlas cell of every face, keyed by outward normal."""
    V = np.array(model['vertices']).reshape(-1, 8); out = {}
    for p in model['parts']:
        if not p['name'].startswith(('brim_', 'hat_', 'bow_')): continue
        v = V[p['start']:p['start']+p['count']]
        faces = {}
        for f in range(0, len(v), 6):
            t = v[f:f+6]; n = tuple(int(round(x)) for x in t[0, 5:8])
            faces[n] = (t[:, :3], t[:, 3:5])
        out[p['name']] = dict(lo=v[:, :3].min(0), hi=v[:, :3].max(0), faces=faces)
    return out


def uv_map(points, uvs, normal):
    """Affine map from the face plane to its atlas cell."""
    axes = [k for k in range(3) if normal[k] == 0]
    A = np.c_[points[:, axes], np.ones(len(points))]
    coef, *_ = np.linalg.lstsq(A, uvs, rcond=None)
    return lambda q: np.r_[q[axes], 1.]@coef


def subtract(lo, hi, hole_lo, hole_hi):
    """Box minus an x/z prism through it: up to four wall boxes."""
    out = []
    if hole_lo[0] >= hi[0] or hole_hi[0] <= lo[0] or hole_lo[2] >= hi[2] or hole_hi[2] <= lo[2]:
        return [(lo, hi)]
    x0, x1 = max(lo[0], hole_lo[0]), min(hi[0], hole_hi[0]); z0, z1 = max(lo[2], hole_lo[2]), min(hi[2], hole_hi[2])
    if lo[0] < x0: out.append((lo, np.r_[x0, hi[1], hi[2]]))
    if x1 < hi[0]: out.append((np.r_[x1, lo[1], lo[2]], hi))
    if lo[2] < z0: out.append((np.r_[x0, lo[1], lo[2]], np.r_[x1, hi[1], z0]))
    if z1 < hi[2]: out.append((np.r_[x0, lo[1], z1], np.r_[x1, hi[1], hi[2]]))
    return out


def hollow(boxes):
    """Follow the external crown tiers with a continuous wall and supported narrowing steps."""
    w = R['wall']; tiers = R['cavity_tiers']
    holes = [(np.r_[boxes[n]['lo'][0]+w, 0, boxes[n]['lo'][2]+w],
              np.r_[boxes[n]['hi'][0]-w, 0, boxes[n]['hi'][2]-w]) for n in tiers]
    depths = [boxes[n]['hi'][1]-w for n in tiers]
    y0 = min(b['lo'][1] for n, b in boxes.items() if n.startswith('brim_'))
    perch = y0+R['front_tread']['depth']
    front_hole = (holes[0][0].copy(), holes[0][1].copy())
    front_hole[1][0] = -R['front_tread']['inner_z']
    for h in holes[1:]: h[1][0] = min(h[1][0], front_hole[1][0])
    hole1, hole2 = holes[:2]; ledge, floor = depths[0], depths[-1]
    out = []
    for name, b in boxes.items():
        lo, hi = b['lo'].copy(), b['hi'].copy()
        if name.startswith('brim_') or name == 'hat_band':
            for a, z in subtract(lo, hi, *hole1): out.append((name, a, z))
        elif name in tiers:
            i = tiers.index(name); depth = depths[i]
            if i == 0:
                # Leave a shallow front foothold in the original solid base, joined to the side walls.
                for a, z in subtract(lo, np.r_[hi[0], perch, hi[2]], *holes[i]): out.append((name, a, z))
                for a, z in subtract(np.r_[lo[0], perch, lo[2]], np.r_[hi[0], depth, hi[2]], *front_hole): out.append((name, a, z))
            else:
                for a, z in subtract(lo, np.r_[hi[0], depth, hi[2]], *holes[i]): out.append((name, a, z))
            base_lo = np.r_[lo[0], depth, lo[2]]
            # Each tread joins its wall to the next smaller crown tier; only the last tier is closed.
            pieces = subtract(base_lo, hi, *holes[i+1]) if i+1 < len(tiers) else [(base_lo, hi)]
            for a, z in pieces: out.append((name, a, z))
        else:
            out.append((name, lo, hi))
    holes = [holes[0], front_hole]+holes[1:]
    depths = [perch]+depths
    cavity = [(np.r_[h[0][0], y0-1 if i == 0 else depths[i-1]-.01, h[0][2]],
               np.r_[h[1][0], depths[i], h[1][2]]) for i, h in enumerate(holes)]
    return out, cavity, dict(rim=y0, ledge=ledge, floor=floor, hole1=hole1, hole2=hole2, holes=holes, depths=depths)


CORNERS = {  # outward normal -> the four corners as (x, y, z) picks of lo(0)/hi(1), counter-clockwise seen from outside
    (1, 0, 0): [(1, 0, 1), (1, 0, 0), (1, 1, 0), (1, 1, 1)], (-1, 0, 0): [(0, 0, 0), (0, 0, 1), (0, 1, 1), (0, 1, 0)],
    (0, 1, 0): [(0, 1, 1), (1, 1, 1), (1, 1, 0), (0, 1, 0)], (0, -1, 0): [(0, 0, 0), (1, 0, 0), (1, 0, 1), (0, 0, 1)],
    (0, 0, 1): [(0, 0, 1), (1, 0, 1), (1, 1, 1), (0, 1, 1)], (0, 0, -1): [(1, 0, 0), (0, 0, 0), (0, 1, 0), (1, 1, 0)]}


def inside(p, cavity):
    return any(np.all(p >= a-1e-6) and np.all(p <= b+1e-6) for a, b in cavity)


def worn_to_vessel(p, anchor):
    """Turn the worn hat over (180 deg about X) and put the long brim axis on Z."""
    q = np.asarray(p)-anchor
    return np.array([-q[..., 2], -q[..., 1], -q[..., 0]]).T if q.ndim > 1 else np.array([-q[2], -q[1], -q[0]])


def face_cells(lo, hi, normal, picks, cavity):
    """Split at cavity boundaries before classifying a face; a tread can be only partly exposed."""
    full = np.array([[(lo, hi)[s][k] for k, s in enumerate(pick)] for pick in picks])
    probe = full+np.array(normal)*.02
    low, high = probe.min(0), probe.max(0)
    relevant = [(a, b) for a, b in cavity if np.all(high >= a-1e-6) and np.all(low <= b+1e-6)]
    if not relevant or any(np.all(low >= a-1e-6) and np.all(high <= b+1e-6) for a, b in relevant):
        yield full
        return
    axes = [k for k in range(3) if normal[k] == 0]
    cuts = []
    for axis in axes:
        values = {float(lo[axis]), float(hi[axis])}
        values.update(float(bound[axis]) for pair in relevant for bound in pair if lo[axis] < bound[axis] < hi[axis])
        values = sorted(values)
        cuts.append(list(zip(values, values[1:])))
    for intervals in itertools.product(*cuts):
        a, b = lo.copy(), hi.copy()
        for axis, (start, end) in zip(axes, intervals): a[axis], b[axis] = start, end
        yield np.array([[(a, b)[s][k] for k, s in enumerate(pick)] for pick in picks])


def triangles(boxes, worn, cavity, anchor):
    lining = worn['brim_sides']['faces'][(0, 1, 0)]
    lining_uv = [lining[1][:, 0].min(), lining[1][:, 1].min(), lining[1][:, 0].max(), lining[1][:, 1].max()]
    parts = {}
    for name, lo, hi in boxes:
        if np.any(hi-lo < 1e-4): continue
        for n, c in ((n, c) for n, picks in CORNERS.items() for c in face_cells(lo, hi, n, picks, cavity)):
            centre = c.mean(0); normal = np.array(n, float)
            interior = inside(centre+normal*.02, cavity)
            source = worn[name]['faces'].get(n)
            on_face = source is not None and abs(centre@normal-source[0][0]@normal) < 1e-4
            if interior:
                uv = [[lining_uv[0], lining_uv[3]], [lining_uv[2], lining_uv[3]], [lining_uv[2], lining_uv[1]], [lining_uv[0], lining_uv[1]]]
            elif on_face:
                f = uv_map(source[0], source[1], n); uv = [f(q) for q in c]
            else:
                continue  # a cut face buried in the wall, never seen
            vn = worn_to_vessel(normal+anchor, anchor)
            vc = worn_to_vessel(c, anchor)
            rows = [list(vc[i])+list(uv[i])+list(vn) for i in (0, 1, 2, 0, 2, 3)]
            parts.setdefault(name+('_lining' if interior else ''), []).extend(rows)
    flat, out = [], []
    for name, rows in parts.items():
        out.append(dict(name=name, start=len(flat)//8, count=len(rows), texture=TEXTURE))
        flat.extend(round(float(x), 5) for row in rows for x in row)
    return flat, out


# ------------------------------------------------------------------ placements
def body_half(body, belly=1.):
    """Half width of a full belly (LiveBirthGeometry.bodyDimensions), model units."""
    b = R['pregnancy']; return (body['bounds'][0][1]-body['bounds'][0][0])/2*(1+b['swell']*belly)*(1+b['low_wide']*belly)


def braid(model):
    names = {b['name']: i for i, b in enumerate(model['bones'])}
    root = np.array(model['bones'][names['hand_right']]['pivot']); tip = np.array(model['bones'][names['hand_right_tip']]['pivot'])
    return root, float(np.linalg.norm(tip-root))


def reach(root, length, p): return float(np.linalg.norm(p-root))/length


class Turned:
    """The opened hat's measures in the vessel frame, and how it is pressed down on land (nest) or afloat (bowl)."""

    def __init__(self, P, levels, anchor, worn):
        rect = lambda hole: [float(v) for v in sorted([-(hole[0][2]-anchor[2]), -(hole[1][2]-anchor[2])])]+[float(-hole[1][0]), float(-hole[0][0])]
        self.opening, self.well = rect(levels['hole1']), rect(levels['hole2'])  # x0, x1, z0, z1
        self.sections = [dict(rect=[round(v, 4) for v in rect(h)], depth=round(d-levels['rim'], 4))
                         for h, d in zip(levels['holes'], levels['depths'])]
        self.keel = -float(P[:, 1].min()); self.floor = levels['floor']-levels['rim']; self.ledge = levels['ledge']-levels['rim']
        self.brim = [float(np.abs(P[:, 0]).max()), float(np.abs(P[:, 2]).max())]
        self.brim_rects = []  # the brim's boxes seen from above (x0, x1, z0, z1)
        for name, b in worn.items():
            if not name.startswith('brim_'): continue
            c = worn_to_vessel(np.array([b['lo'], b['hi']]), anchor)
            self.brim_rects.append([round(float(c[:, 0].min()), 4), round(float(c[:, 0].max()), 4), round(float(c[:, 2].min()), 4), round(float(c[:, 2].max()), 4)])
        self.squash = {'nest': R['nest']['height']/self.keel, 'bowl': R['bowl']['squash']}

    def lift(self, kind, k):
        return self.keel*self.squash['nest']*k if kind == 'nest' else R['bowl']['freeboard']*k

    def rim_points(self, n=36):
        """Grip candidates on the brim just outside the opening (the face that sat on the head)."""
        x0, x1, z0, z1 = self.opening; cx, cz, hx, hz = (x0+x1)/2, (z0+z1)/2, (x1-x0)/2, (z1-z0)/2; out = []
        for phi in np.linspace(0, 2*math.pi, n, endpoint=False):
            c, s = math.cos(phi), math.sin(phi); t = min(hx/max(abs(c), 1e-6), hz/max(abs(s), 1e-6))+R['rim_out']
            out.append([cx+c*t, 0., cz+s*t])
        return np.array(out)


def hat_pose(turned, kind, k, yaw_rel, origin):
    """Vessel points -> mother frame for the pressed hat whose entity yaw is the mother's + yaw_rel."""
    Rm = rot_y(math.radians(-yaw_rel)); S = np.array([k, k*turned.squash[kind], k]); lift = np.array([0, turned.lift(kind, k), 0])
    return lambda pts: (np.asarray(pts)*S+lift)@Rm.T+origin


def in_mother(p, yaw_rel, origin):
    return np.asarray(p)@rot_y(math.radians(-yaw_rel)).T+origin


def head_grips(hat, anchor, root, length, k):
    """Brim points on the braid side the braid reaches while the (scaled) hat is worn."""
    brims = [b for n, b in hat.items() if n.startswith('brim_')]; out = []
    for x in np.arange(5.5, 10.2, .5):
        for z in np.arange(-6.9, 6.8, .5):
            g = np.array([x, 10.85, z])
            if any(b['lo'][0]-1e-3 <= x <= b['hi'][0]+1e-3 and b['lo'][2]-1e-3 <= z <= b['hi'][2]+1e-3 for b in brims) \
                    and .65 <= reach(root, length, anchor+k*(g-anchor)) <= 1.2:
                out.append(g)
    return out


def end_grip(g, anchor, sigma, k, rim_y):
    """Where a worn brim point ends when the turned-over hat is pressed onto the set-down hat (hat frame)."""
    return worn_to_vessel(g, anchor)*np.array([1, sigma, 1])*k+[0, rim_y, 0]


def helper_spots(pose, turned, kind, k, root, length, mother_r, land_from, reach_range):
    """Places and angles (mother frame) from which the braid holds the brim, best first. The brim point held stays
    out of the newborn's way: not on the near half of the brim beside the outlet line."""
    W = R['helper']; rims = pose(turned.rim_points()); centre = pose(np.zeros((1, 3)))[0]
    clear_path = ~((rims[:, 2] > centre[2]) & (np.abs(rims[:, 0]) < W['corridor']))
    brim_r = max(turned.brim)*k+W['hat_clear']; body_r = W['body_radius']
    outlet = np.array([0, 1.3, -mother_r]); out = []
    for a in range(0, 360, 5):
        Rinv = rot_y(math.radians(-a))
        for hx in np.arange(-40, 40.01, 1.):
            for hz in np.arange(-40, 16.01, 1.):
                h = np.array([hx, 0, hz])
                if hz < land_from or math.hypot(hx, hz) < mother_r+body_r+W['mother_clear']: continue
                if kind == 'nest' and math.hypot(hx-centre[0], hz-centre[2]) < brim_r+body_r: continue  # a floated brim lies below the bank
                local = (rims-h)@Rinv.T; rs = np.linalg.norm(local-root, axis=1)/length
                ok = (rs >= reach_range[0])&(rs <= reach_range[1])&(local[:, 0] >= W['grip_side_min'])&clear_path
                if not ok.any(): continue
                i = int(np.argmin(np.where(ok, abs(rs-W['reach_ideal']), 9)))
                to = Rinv@(outlet-h); to[1] = 0
                look = math.degrees(math.acos(max(-1, min(1, -to[2]/np.linalg.norm(to)))))
                score = W['look']*look+W['reach_weight']*abs(rs[i]-W['reach_ideal'])+W['distance']*math.hypot(hx, hz)
                out.append((score, h, a, i, float(rs[i]), look))
    out.sort(key=lambda x: x[0]); return out


def place(variant, kind, turned, k, origins, hat, anchor, root, bowed, length, half, land_from):
    """The helper's and the mother's own placement: the hat's turn, the brim point the braid carries it by, the brim
    point it holds. origins(role, yaw_rel) gives where the hat lies before the mother for that turn."""
    W = R['helper']; hover = np.array([0, R['hover'], 0]); grips = head_grips(hat, anchor, root, length, k)
    sigma = turned.squash[kind]; best_h = best_s = None
    for yaw_rel in (90, -90):
        origin = origins('helper', yaw_rel); pose = hat_pose(turned, kind, k, yaw_rel, origin) if origin is not None else None
        ends = [in_mother(end_grip(g, anchor, sigma, k, turned.lift(kind, k)), yaw_rel, origin) for g in grips] if pose else []
        for score, h, a, i, rr, look in [] if pose is None else helper_spots(pose, turned, kind, k, root, length, half, land_from, R['reach'][variant])[:400]:
            Rinv = rot_y(math.radians(-a))
            for g, e in zip(grips, ends):
                re = reach(root, length, Rinv@(e-h)); rh = reach(root, length, Rinv@(e+hover-h))
                if not .6 <= re <= R['reach'][variant][1]+.1 or rh > R['reach'][variant][1]+.2: continue
                total = score+W['reach_weight']*(abs(re-1)+.5*abs(rh-1))
                if best_h is None or total < best_h[0]: best_h = (total, yaw_rel, h, a, i, rr, look, g, re, rh, origin)
        # the mother on her own, bowed: her braid side of the brim, and the brim point that lands within reach
        origin = origins('self', yaw_rel)
        if origin is None: continue
        pose = hat_pose(turned, kind, k, yaw_rel, origin)
        ends = [in_mother(end_grip(g, anchor, sigma, k, turned.lift(kind, k)), yaw_rel, origin) for g in grips]
        low = np.array([0, R['self_hover'], 0])
        for g, e in zip(grips, ends):  # she lets go where the braid set it down
            re = reach(bowed, length, e); rh = reach(bowed, length, e+low); total = abs(re-1)+.5*abs(rh-1)
            if best_s is None or total < best_s[0]: best_s = (total, yaw_rel, g, worn_to_vessel(g, anchor)*np.array([1, sigma, 1]), re, rh, origin)
    rnd = lambda v: [round(float(x), 4) for x in v]; rims = turned.rim_points(); helper = None
    if origins('helper', 90) is not None:
        if best_h is None: raise SystemExit(f'{variant}: no helper placement')
        _, yaw_rel, h, a, i, rr, look, g, re, rh, origin = best_h; Rinv = rot_y(math.radians(-a))
        helper = dict(mode=kind, offset=rnd(Rinv@(origin-h)), yaw=int((a+yaw_rel) % 360), hat_in_mother=dict(origin=rnd(origin), yaw=yaw_rel),
                      at_mother=rnd(h), angle=a, rim=rnd(rims[i]), grip_worn=rnd(g),
                      reach=dict(hold=round(rr, 3), set_down=round(re, 3), above=round(rh, 3)), look=round(look, 2))
    if best_s is None: return helper, None
    _, yaw_rel, g, rim, re, rh, origin = best_s
    mine = dict(mode=kind, offset=rnd(origin), yaw=yaw_rel, hat_in_mother=dict(origin=rnd(origin), yaw=yaw_rel), rim=rnd(rim), grip_worn=rnd(g),
                bow=R['self_bow'], reach=dict(set_down=round(re, 3), above=round(rh, 3)))
    return helper, mine


def outputs():
    model = json.loads(MODEL.read_text(encoding='utf-8')); body = json.loads(BODY.read_text(encoding='utf-8'))['variants']['marisa']
    worn = worn_boxes(model); boxes, cavity, levels = hollow(worn)
    anchor = np.array([0, levels['rim'], (worn['hat_base']['lo'][2]+worn['hat_base']['hi'][2])/2])
    flat, parts = triangles(boxes, worn, cavity, anchor)
    P = np.array(flat).reshape(-1, 8)[:, :3]
    prop = {'format': 1, 'units_per_block': 16, 'vertices': flat, 'parts': parts, 'bounds': [[round(float(P[:, i].min()), 5), round(float(P[:, i].max()), 5)] for i in range(3)]}
    t = Turned(P, levels, anchor, worn); fb = R['bowl']['freeboard']; sn, sw = t.squash['nest'], t.squash['bowl']
    x0, x1, z0, z1 = t.opening
    model_root, length = braid(model)
    pivot = np.array(model['bones'][[b['name'] for b in model['bones']].index('root')]['pivot'])
    bowed = rot_x(math.radians(R['self_bow']))@(model_root-pivot)+pivot  # a mother bows to set her own hat
    half = body_half(body); exit_front = half+.15*16; D = R['delivery']
    width = body['bounds'][0][1]-body['bounds'][0][0]; r_live = D['young_radius']*width*LIVE_BABY
    rnd = lambda v: [round(float(x), 4) for x in v]

    def near(yaw_rel, k):
        """How far the brim reaches from the hat's origin toward the mother (+Z) for this turn."""
        Rm = rot_y(math.radians(-yaw_rel))
        return max((Rm@np.array([x, 0, z]))[2] for b in t.brim_rects for x in b[:2] for z in b[2:])*k

    def before_exit(gap, k):  # the near brim this far past the live exit, beyond the newborn's round body
        return lambda yaw_rel: np.array([0., 0., -(exit_front+r_live+gap+near(yaw_rel, k))])
    placements = {}
    for variant, k in (('land', 1.), ('aquatic', R['hat_scale']['aquatic_marisa'])):
        if variant == 'land':
            kind, land_from = 'nest', -99.
            helper_at = before_exit(D['gap']['helper'], k)
            helper, _ = place(variant, kind, t, k, lambda role, yaw: helper_at(yaw) if role == 'helper' else None,
                              worn, anchor, model_root, bowed, length, half, land_from)
            helper['gap'] = D['gap']['helper']
            for gap in D['gap']['self']:  # a mother on her own sets it as far out as her braid reaches
                self_at = before_exit(gap, k)
                _, mine = place(variant, kind, t, k, lambda role, yaw: self_at(yaw) if role == 'self' else None,
                                worn, anchor, model_root, bowed, length, half, land_from)
                if max(mine['reach'].values()) <= D['self_reach']: break
            mine['gap'] = gap
        else:
            # floated at the bank's edge before the outlet, its near brim slipped under the bank
            origin = np.array([0., -R['float']['bank_drop'], -half-t.brim[0]*k+R['float']['under_bank']]); kind, land_from = 'bowl', -half+R['float']['helper_back']
            helper, mine = place(variant, kind, t, k, lambda role, yaw: origin, worn, anchor, model_root, bowed, length, half, land_from)
        for p in (helper, mine): p['near_brim'] = round(near(p['hat_in_mother']['yaw'], 1.), 4)
        placements[variant] = dict(scale=k, helper=helper, self=mine, mother_half=round(half, 4), exit_front=round(exit_front, 4))
    metrics = {
        'note': __doc__.splitlines()[0], 'source_model': MODEL.relative_to(ROOT).as_posix(), 'source_model_sha256': sha(MODEL.read_bytes()),
        'prop': 'marisa_hat_turned', 'scale': R['hat_scale'], 'anchor_worn': rnd(anchor),
        # turned over, the crown (keel deep) is pressed toward the brim along the hat's axis: on land until it rests on the ground
        'keel': round(t.keel, 4), 'squash': {'nest': round(sn, 4), 'bowl': round(sw, 4)},
        'opening': rnd(t.opening), 'well': rnd(t.well), 'brim': rnd(t.brim), 'floor': round(t.floor, 4), 'ledge': round(t.ledge, 4),
        'cavity_sections': t.sections,
        'nest': {'height': round(t.keel*sn, 4)},
        # afloat the owner rests on the ledge between the two opened tiers, the young on the floor below
        'bowl': {'freeboard': fb, 'owner_y': round(fb-t.ledge*sw, 4), 'mask': [[x0, z0], [x1, z0], [x1, z1], [x0, z1]]},
        'brim_rects': t.brim_rects, 'braid': {'root': rnd(model_root), 'length': round(length, 4)},
        'delivery': {k: v for k, v in D.items() if k not in ('gap', 'gap_lore', 'self_reach')},
        'placements': placements, 'float': R['float'], 'pregnancy': R['pregnancy'], 'afloat': R['afloat']}
    runtime = {k: metrics[k] for k in ('scale', 'anchor_worn', 'delivery', 'placements', 'afloat')}
    runtime['delivery'] = {k: D[k] for k in ('gravity', 'tmin', 'tmax', 'max_speed', 'clear', 'young_radius')}
    runtime['afloat'] = {k: v for k, v in R['afloat'].items() if k != 'lore'}
    runtime['placements'] = {kind: {role: {**{k: p[k] for k in ('offset', 'yaw', 'grip_worn', 'gap') if k in p},
        'hat_in_mother': {'yaw': p['hat_in_mother']['yaw']}} for role, p in rows.items() if role in ('helper', 'self')}
        for kind, rows in placements.items()}
    runtime['forms'] = {p.stem: 'turned_land_hat' for p in sorted((ASSETS/'yukkuri').glob('*marisa*.json'))}
    texts = {PROP: json.dumps(prop, separators=(',', ':'))+'\n',
             HERE/'geometry.json': json.dumps(metrics, ensure_ascii=False, indent=2)+'\n',
             METRICS: json.dumps(runtime, ensure_ascii=False, indent=2)+'\n'}
    index = json.loads(INDEX.read_text(encoding='utf-8'))
    for name in ('marisa_hat_vessel', 'marisa_hat_nest', 'hat_boat'): index.pop(name, None)  # earlier props, deleted
    index.setdefault('marisa_hat_turned', {}); texts[INDEX] = json.dumps(index, separators=(',', ':'))
    manifest = {'note': 'Runtime outputs of build.py', 'recipe_sha256': sha((HERE/'recipe.json').read_bytes()), 'build_sha256': sha(Path(__file__).read_bytes()),
                'growth_sha256': sha(json.dumps(GROWTH, sort_keys=True).encode()),
                'outputs': {p.relative_to(ROOT).as_posix(): sha(t.encode()) for p, t in texts.items()}}
    texts[HERE/'manifest.json'] = json.dumps(manifest, ensure_ascii=False, indent=2)+'\n'
    return texts, metrics, body


def main():
    ap = argparse.ArgumentParser(); ap.add_argument('--check', action='store_true'); args = ap.parse_args()
    texts, metrics, body = outputs()
    for path, text in texts.items():
        if args.check:
            assert path.read_text(encoding='utf-8') == text, f'stale {path}'
        else:
            path.write_text(text, encoding='utf-8')
    if args.check: assert not any(p.exists() for p in OLD_PROPS), 'old soft hat props left behind'
    else:
        for p in OLD_PROPS: p.unlink(missing_ok=True)
    print('turned hat', 'checked' if args.check else 'written', len(json.loads(texts[PROP])['vertices'])//8, 'vertices; squash', metrics['squash'], 'nest height', metrics['nest']['height'])
    for variant, p in metrics['placements'].items():
        print(variant, {r: {k: p[r][k] for k in ('at_mother', 'angle', 'yaw', 'reach', 'look', 'grip_worn', 'gap') if k in p[r]} for r in ('helper', 'self')})
    report(metrics, body)


def report(V, body):
    """The canonical live births into the hat on land: ticks and highest point of each newborn's arc."""
    D = V['delivery']; hat = DL.Hat(V, 'nest'); width = body['bounds'][0][1]-body['bounds'][0][0]
    b = R['pregnancy']; y_out = body['outlet'][1]*(1+b['swell'])
    start = [0., max(.24, y_out-body['height']*LIVE_BABY/2), -V['placements']['land']['exit_front']]
    r = DL.radius(D, width*LIVE_BABY)
    for role in ('helper', 'self'):
        h = V['placements']['land'][role]['hat_in_mother']; pose = DL.Pose(h['origin'], -h['yaw'], 1.)
        seats = DL.pack(hat, D, [r]*5, 'catch'); line = []
        for s in seats:
            end = pose.world(s); T = DL.solve(start, end, pose, hat, r, D); f = DL.arc(start, end, T, D['gravity'])
            line.append((T, round(max(f(i/4)[1] for i in range(4*T+1)), 2)))
        print('live', role, 'arcs (ticks, top)', line)


if __name__ == '__main__':
    main()
