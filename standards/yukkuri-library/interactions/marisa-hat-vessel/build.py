"""Marisa's own hat turned over, as soft cloth: a nest on land, a pleated bowl afloat.

Marisa's hat is cloth. Turned over and set down, its soft crown folds into a nest with the brim
drooping round it (user, 2026-10-15); afloat it is a pleated bowl with a scalloped brim lying on
the water (reference EH22-001, EH22-006/007, wiki/art-references). Sizes come from the worn hat
of the selected runtime Marisa: the crown base gives the opening, the brim its reach. Each hat
is one cloth shell: a mid-surface per angle from the bowl's centre, up the wall, over a rolled
rim and out to the brim edge, thickened both ways, pleated round the bowl and scalloped at the
brim, with the band below the rim and the bow on the outside. Colours are the hat's own atlas
cells (the props draw with the Marisa atlas, as the worn hat does).

  marisa_hat_nest    on dry ground, opening up, the crown folded into a low nest
  marisa_hat_vessel  afloat: the aquatic Marisa's boat, or floated at a bank to catch; a deep bowl

Frame: origin at the opening's centre, on the ground (nest) or at the rim (bowl); +Y up; the long
axis on Z; the worn hat's front, where the bow is, on +X.

Placements put the hat where the game sets it to catch and the catcher where her braid holds its
rim: a helper beside the nest (searched over her angle and place for braid reach, clearance from
the swollen mother and the hat, and a view of the outlet), or the mother before her own outlet.
The braid clips (motions/variants/lore/hat.py) carry the worn hat to the same place and press it
down, its crown squashing to the nest's or bowl's depth. Python and numpy only.

  python build.py [--check]
"""
from pathlib import Path
import argparse, hashlib, json, math
import numpy as np

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
ASSETS = ROOT/'src/main/resources/assets/mcyukkuri'
MODEL = ASSETS/'yukkuri/marisa.json'
BODY = ASSETS/'yukkuri_presentation/body_geometry.json'
PROPS = {'nest': ASSETS/'yukkuri_props/marisa_hat_nest.json', 'bowl': ASSETS/'yukkuri_props/marisa_hat_vessel.json'}
METRICS = ASSETS/'yukkuri_presentation/hat_vessel.json'
INDEX = ASSETS/'yukkuri_props/subspecies-index.json'
TEXTURE = 'mcyukkuri:textures/entity/marisa/neutral.png'
R = json.loads((HERE/'recipe.json').read_text(encoding='utf-8'))


def sha(data): return hashlib.sha256(data).hexdigest()


def smooth(x):
    x = min(1., max(0., x)); return x*x*(3-2*x)


def rot_x(a):
    c, s = math.cos(a), math.sin(a); return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def rot_y(a):  # x' = x cos + z sin, z' = -x sin + z cos (the game's Axis.YP / Vec3.yRot)
    c, s = math.cos(a), math.sin(a); return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


# ------------------------------------------------------------------ the worn hat
def worn(model):
    """Bounds of the worn hat's parts and the centre of each part's atlas cell."""
    V = np.array(model['vertices']).reshape(-1, 8); out = {}
    for p in model['parts']:
        if not p['name'].startswith(('brim_', 'hat_', 'bow_')): continue
        v = V[p['start']:p['start']+p['count']]
        top = v[np.isclose(v[:, 6], 1)] if np.isclose(v[:, 6], 1).any() else v[:6]
        out[p['name']] = dict(lo=v[:, :3].min(0), hi=v[:, :3].max(0), cell=[float(top[:, 3].mean()), float(top[:, 4].mean())])
    return out


def worn_to_vessel(p, anchor):
    """Turn the worn hat over (180 deg about X) and put the long brim axis on Z."""
    q = np.asarray(p, float)-anchor
    return np.stack([-q[..., 2], -q[..., 1], -q[..., 0]], -1)


# ------------------------------------------------------------------ the cloth shell
class Shell:
    """One hat: per angle a profile from the bowl's centre (t=0) to the brim edge (t=1)."""

    def __init__(self, kind, hat):
        self.kind = kind; c = R[kind]; self.c = c
        base, brims = hat['hat_base'], [hat[n] for n in hat if n.startswith('brim_')]
        # opening = the crown base, brim = the outer brim; vessel X = worn Z (short), vessel Z = worn X (long)
        self.rim = np.array([(base['hi'][2]-base['lo'][2])/2, (base['hi'][0]-base['lo'][0])/2])
        self.brim = np.array([max(b['hi'][2]-b['lo'][2] for b in brims)/2, max(b['hi'][0]-b['lo'][0] for b in brims)/2])
        self.t_rim = c['rim_at']

    def radius(self, ellipse, phi):
        a, b = ellipse; return a*b/math.hypot(b*math.cos(phi), a*math.sin(phi))

    def profile(self, t, phi):
        """(radius, y) of the mid-surface."""
        c = self.c; r_rim, r_brim = self.radius(self.rim, phi), self.radius(self.brim, phi)
        if t <= self.t_rim:
            u = t/self.t_rim
            if self.kind == 'nest':  # the folded crown: a fat low wall up to the rim
                r = u*(1+c['bulge']*math.sin(math.pi*u)); y = c['floor']+(c['rim']-c['floor'])*u**c['wall_power']
            else:  # a deep soft bowl, steep at the rim
                r = u; y = -c['depth']*max(0., 1-u*u)**c['wall_power']
            pleat = 1+c['pleat']*math.cos(c['pleats']*phi)*math.sin(math.pi*u)*u
            return r*r_rim*pleat, y
        v = (t-self.t_rim)/(1-self.t_rim)
        edge = 1+c['scallop']*math.cos(c['scallops']*phi)*v*v
        r = (r_rim+(r_brim-r_rim)*v)*edge
        roll = c['roll']*math.sin(math.pi*min(1., v/c['roll_span']))  # the rolled rim
        y = c['rim']+roll-c['droop']*v**c['droop_power']+c['wave']*math.cos(c['scallops']*phi+.6)*v*v
        return r, y

    def point(self, t, phi):
        r, y = self.profile(t, phi); return np.array([r*math.cos(phi), y, r*math.sin(phi)])

    def mid(self, nt, nphi):
        T = np.linspace(0, 1, nt); P = np.linspace(0, 2*math.pi, nphi, endpoint=False)
        G = np.array([[self.point(t, p) for p in P] for t in T])
        # the normal pointing out of the bowl's inside and the brim's top
        dt = np.gradient(G, axis=0); dp = np.roll(G, -1, 1)-np.roll(G, 1, 1)
        N = np.cross(dp, dt); N /= np.linalg.norm(N, axis=-1, keepdims=True)+1e-12
        N[0] = [0, 1, 0]
        if N[-1, :, 1].mean() < 0: N = -N
        return T, P, G, N


def cells(hat):
    return {'lining': hat['brim_sides']['cell'], 'brim': hat['brim_center']['cell'], 'outside': hat['hat_base']['cell'],
            'band': hat['hat_band']['cell'], 'under': hat['brim_sides']['cell'], 'bow': hat['bow_left_outer']['cell'], 'knot': hat['bow_knot']['cell']}


def build(kind, hat):
    sh = Shell(kind, hat); c = R[kind]; th = c['thickness']; cell = cells(hat)
    T, P, G, N = sh.mid(c['rings'], c['segments'])
    top, bottom = G+N*th/2, G-N*th/2
    if kind == 'nest': bottom[..., 1] = np.maximum(bottom[..., 1], 0.)  # never into the ground
    parts = {}
    def tri(name, a, b, cc, na, nb, nc):
        uv = cell[{'rim_edge': 'under'}.get(name, name)]
        parts.setdefault(name, []).extend([[*a, *uv, *na], [*b, *uv, *nb], [*cc, *uv, *nc]])
    nt, npf = len(T), len(P); rim_i = int(round(sh.t_rim*(nt-1)))
    band = (c['rim']-c['band'][1], c['rim']-c['band'][0])
    for i in range(nt-1):
        for j in range(npf):
            k = (j+1) % npf
            # inside of the bowl and top of the brim
            name = 'lining' if i < rim_i else 'brim'
            a, b, cc, d = top[i, j], top[i+1, j], top[i+1, k], top[i, k]
            tri(name, a, cc, b, N[i, j], N[i+1, k], N[i+1, j]); tri(name, a, d, cc, N[i, j], N[i, k], N[i+1, k])
            # outside of the bowl (band just below the rim) and under the brim
            ymid = (bottom[i, j, 1]+bottom[i+1, j, 1])/2
            name = 'under' if i >= rim_i else 'band' if band[0] <= ymid <= band[1] else 'outside'
            a, b, cc, d = bottom[i, j], bottom[i+1, j], bottom[i+1, k], bottom[i, k]
            tri(name, a, b, cc, -N[i, j], -N[i+1, j], -N[i+1, k]); tri(name, a, cc, d, -N[i, j], -N[i+1, k], -N[i, k])
    for j in range(npf):  # the brim's cut edge
        k = (j+1) % npf; out = G[-1, j]-G[-2, j]; out = out/np.linalg.norm(out); o2 = G[-1, k]-G[-2, k]; o2 = o2/np.linalg.norm(o2)
        a, b, cc, d = top[-1, j], bottom[-1, j], bottom[-1, k], top[-1, k]
        tri('rim_edge', a, b, cc, out, out, o2); tri('rim_edge', a, cc, d, out, o2, o2)
    bow(parts, sh, c, cell)
    flat, out = [], []
    for name, rows in parts.items():
        out.append(dict(name=name, start=len(flat)//8, count=len(rows), texture=TEXTURE))
        flat.extend(round(float(x), 5) for row in rows for x in row)
    return sh, flat, out


def bow(parts, sh, c, cell):
    """The bow on the outside below the rim, on +X (the worn hat's front)."""
    yb = c['rim']-c['bow_below']
    t = min(np.linspace(0, sh.t_rim, 400), key=lambda t: abs(sh.profile(t, 0.)[1]-yb))
    p = sh.point(t, 0.); n = np.array([1., 0, 0]); t1 = np.array([0, 0, 1.]); t2 = np.array([0, 1., 0])
    p = p+n*(c['thickness']/2+.05); s = c['bow_scale']
    for (u, v, w), (du, dv, dw), name in (((-1.7, .2, .4), (3.0, 2.4, .8), 'bow'), ((1.7, .2, .4), (3.0, 2.4, .8), 'bow'),
                                         ((0, .1, .6), (1.4, 1.5, 1.0), 'knot'), ((-.9, -1.6, .3), (1.2, 1.4, .5), 'knot'), ((.9, -1.6, .3), (1.2, 1.4, .5), 'knot')):
        centre = p+(t1*u+t2*v+n*w)*s; half = np.array([du, dv, dw])*s/2
        corners = lambda sx, sy, sz: centre+t1*sx*half[0]+t2*sy*half[1]+n*sz*half[2]
        for axis, vec in ((0, t1), (1, t2), (2, n)):
            for sign in (-1, 1):
                q = [[0, 0, 0] for _ in range(4)]
                others = [k for k in range(3) if k != axis]
                for idx, (a, b) in enumerate(((-1, -1), (1, -1), (1, 1), (-1, 1))):
                    q[idx][axis] = sign; q[idx][others[0]] = a; q[idx][others[1]] = b
                pts = [corners(*x) for x in q]; nn = vec*sign
                if np.dot(np.cross(pts[1]-pts[0], pts[2]-pts[0]), nn) < 0: pts = pts[::-1]
                uv = cell[name]
                for a_, b_, c_ in ((pts[0], pts[1], pts[2]), (pts[0], pts[2], pts[3])):
                    parts.setdefault(name, []).extend([[*a_, *uv, *nn], [*b_, *uv, *nn], [*c_, *uv, *nn]])


def floor_at(sh, x, z):
    """Height of the inside of the bowl at (x, z), cloth top."""
    phi = math.atan2(z, x); rr = math.hypot(x, z)
    ts = np.linspace(0, sh.t_rim, 300); radii = np.array([sh.profile(t, phi)[0] for t in ts])
    t = ts[min(len(ts)-1, int(np.searchsorted(radii, rr)))]
    return sh.profile(t, phi)[1]+R[sh.kind]['thickness']/2


# ------------------------------------------------------------------ placements
def body_half(body, belly=1.):
    """Half width of a full belly (LiveBirthGeometry.bodyDimensions), model units."""
    b = R['pregnancy']; return (body['bounds'][0][1]-body['bounds'][0][0])/2*(1+b['swell']*belly)*(1+b['low_wide']*belly)


def braid(model):
    names = {b['name']: i for i, b in enumerate(model['bones'])}
    root = np.array(model['bones'][names['hand_right']]['pivot']); tip = np.array(model['bones'][names['hand_right_tip']]['pivot'])
    return root, float(np.linalg.norm(tip-root))


def reach(root, length, p): return float(np.linalg.norm(p-root))/length


def hat_pose(kind, k, yaw_rel, origin):
    """Vessel points -> mother frame for a hat whose entity yaw is the mother's + yaw_rel."""
    Rm = rot_y(math.radians(-yaw_rel)); lift = np.array([0, R['bowl']['freeboard']*k if kind == 'bowl' else 0, 0])
    return lambda pts: (np.asarray(pts)*k+lift)@Rm.T+origin


def rim_points(sh, n=36):
    """Grip candidates on the rolled rim (cloth top)."""
    out = []
    for phi in np.linspace(0, 2*math.pi, n, endpoint=False):
        t = sh.t_rim+(1-sh.t_rim)*R[sh.kind]['roll_span']*.5
        p = sh.point(t, phi)+np.array([0, R[sh.kind]['thickness']/2, 0]); out.append(p)
    return np.array(out)


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


def helper_spots(pose, sh, k, root, length, mother_r, land_from, reach_range):
    """Places and angles (mother frame) from which the braid holds the rim, best first."""
    W = R['helper']; rims = pose(rim_points(sh)); centre = pose(np.zeros((1, 3)))[0]
    brim_r = max(sh.brim)*k+W['hat_clear']; body_r = W['body_radius']
    outlet = np.array([0, 1.3, -mother_r]); out = []
    for a in range(0, 360, 5):
        Rinv = rot_y(math.radians(-a))
        for hx in np.arange(-40, 40.01, 1.):
            for hz in np.arange(-40, 16.01, 1.):
                h = np.array([hx, 0, hz])
                if hz < land_from or math.hypot(hx, hz) < mother_r+body_r+W['mother_clear']: continue
                if sh.kind == 'nest' and math.hypot(hx-centre[0], hz-centre[2]) < brim_r+body_r: continue  # a floated brim lies below the bank
                local = (rims-h)@Rinv.T; rs = np.linalg.norm(local-root, axis=1)/length
                ok = (rs >= reach_range[0])&(rs <= reach_range[1])&(local[:, 0] >= W['grip_side_min'])
                if not ok.any(): continue
                i = int(np.argmin(np.where(ok, abs(rs-W['reach_ideal']), 9)))
                to = Rinv@(outlet-h); to[1] = 0
                look = math.degrees(math.acos(max(-1, min(1, -to[2]/np.linalg.norm(to)))))
                score = W['look']*look+W['reach_weight']*abs(rs[i]-W['reach_ideal'])+W['distance']*math.hypot(hx, hz)
                out.append((score, h, a, i, float(rs[i]), look))
    out.sort(key=lambda x: x[0]); return out


def place(variant, kind, sh, k, origin, sigma, rim_y, hat, anchor, root, bowed, length, half, land_from):
    """The helper's and the mother's own placement: the hat's turn, the brim point the braid carries it by, the rim it holds."""
    W = R['helper']; hover = np.array([0, R['hover'], 0]); grips = head_grips(hat, anchor, root, length, k)
    best_h = best_s = None
    for yaw_rel in (90, -90):
        pose = hat_pose(kind, k, yaw_rel, origin)
        ends = [in_mother(end_grip(g, anchor, sigma, k, rim_y)+(np.array([0, R['bowl']['freeboard']*k, 0]) if kind == 'bowl' else 0), yaw_rel, origin) for g in grips]
        for score, h, a, i, rr, look in helper_spots(pose, sh, k, root, length, half, land_from, R['reach'][variant])[:400]:
            Rinv = rot_y(math.radians(-a))
            for g, e in zip(grips, ends):
                re = reach(root, length, Rinv@(e-h)); rh = reach(root, length, Rinv@(e+hover-h))
                if not .6 <= re <= R['reach'][variant][1]+.1 or rh > R['reach'][variant][1]+.2: continue
                total = score+W['reach_weight']*(abs(re-1)+.5*abs(rh-1))
                if best_h is None or total < best_h[0]: best_h = (total, yaw_rel, origin, h, a, i, rr, look, g, re, rh)
        # the mother on her own, bowed: her braid side of the rim, and the brim point that lands within reach
        rims = pose(rim_points(sh)); low = np.array([0, R['self_hover'], 0])
        for g, e in zip(grips, ends):
            re = reach(bowed, length, e); rh = reach(bowed, length, e+low)
            for i, rim in enumerate(rims):
                if rim[0] <= 0: continue
                rr = reach(bowed, length, rim); total = abs(re-1)+.5*abs(rh-1)+abs(rr-1)
                if best_s is None or total < best_s[0]: best_s = (total, yaw_rel, g, i, re, rh, rr)
    if best_h is None: raise SystemExit(f'{variant}: no helper placement')
    _, yaw_rel, origin, h, a, i, rr, look, g, re, rh = best_h; Rinv = rot_y(math.radians(-a))
    rnd = lambda v: [round(float(x), 4) for x in v]
    helper = dict(mode=kind, offset=rnd(Rinv@(origin-h)), yaw=int((a+yaw_rel) % 360), hat_in_mother=dict(origin=rnd(origin), yaw=yaw_rel),
                  at_mother=rnd(h), angle=a, rim=rnd(rim_points(sh)[i]), grip_worn=rnd(g),
                  reach=dict(hold=round(rr, 3), set_down=round(re, 3), above=round(rh, 3)), look=round(look, 2))
    _, yaw_rel, g, i, re, rh, rr = best_s
    mine = dict(mode=kind, offset=rnd(origin), yaw=yaw_rel, hat_in_mother=dict(origin=rnd(origin), yaw=yaw_rel), rim=rnd(rim_points(sh)[i]), grip_worn=rnd(g),
                bow=R['self_bow'], reach=dict(rim=round(rr, 3), set_down=round(re, 3), above=round(rh, 3)))
    return helper, mine


def main():
    ap = argparse.ArgumentParser(); ap.add_argument('--check', action='store_true'); args = ap.parse_args()
    model = json.loads(MODEL.read_text(encoding='utf-8')); body = json.loads(BODY.read_text(encoding='utf-8'))['variants']['marisa']
    hat = worn(model)
    brim_lo = min(b['lo'][1] for n, b in hat.items() if n.startswith('brim_'))
    anchor = np.array([0, brim_lo, (hat['hat_base']['lo'][2]+hat['hat_base']['hi'][2])/2])
    keel = max(b['hi'][1] for n, b in hat.items() if n.startswith('hat_'))-brim_lo  # how far the crown reaches when turned over
    shells, props = {}, {}
    for kind in ('nest', 'bowl'):
        sh, flat, parts = build(kind, hat); shells[kind] = sh
        P = np.array(flat).reshape(-1, 8)[:, :3]
        props[kind] = {'format': 1, 'units_per_block': 16, 'vertices': flat, 'parts': parts,
                       'bounds': [[round(float(P[:, i].min()), 5), round(float(P[:, i].max()), 5)] for i in range(3)]}
    nest, bowl = shells['nest'], shells['bowl']
    # the bowl's opening at the water line, for the water mask
    fb = R['bowl']['freeboard']; mask = []
    for phi in np.linspace(0, 2*math.pi, 24, endpoint=False):
        ts = np.linspace(0, bowl.t_rim, 400); ys = np.array([bowl.profile(t, phi)[1] for t in ts])
        t = ts[int(np.argmin(abs(ys+fb)))]; r = bowl.profile(t, phi)[0]-R['bowl']['thickness']*.4  # just into the wall, so no water rim shows
        mask.append([round(r*math.cos(phi), 4), round(r*math.sin(phi), 4)])
    seats = {'nest': [[x, round(floor_at(nest, x, z), 4), z] for x, z in R['nest']['seats']],
             'day': [[x, round(floor_at(bowl, x, z), 4), z] for x, z in R['bowl']['young_day']],
             'night': [[x, round(floor_at(bowl, x, z), 4), z] for x, z in R['bowl']['young_night']]}
    root, length = braid(model)
    pivot = np.array(model['bones'][[b['name'] for b in model['bones']].index('root')]['pivot'])
    bowed = rot_x(math.radians(R['self_bow']))@(root-pivot)+pivot  # a mother bows to set her own hat
    half = body_half(body); exit_front = half+.15*16
    placements = {}
    sq = {'nest': R['nest']['rim']/keel, 'bowl': R['bowl']['depth']/keel}
    for variant, k in (('land', 1.), ('aquatic', R['aquatic_scale'])):
        if variant == 'land':
            # the nest before the outlet, its opening's near edge a little nearer than the exit, the long axis across
            near = nest.radius(nest.rim, 0.)*k; origin = np.array([0., 0., -(exit_front-R['inside_exit'])-near])
            kind, sh, land_from, rim_y = 'nest', nest, -99., R['nest']['rim']*k
        else:
            # floated at the bank's edge before the outlet, its near brim slipped under the bank
            near = bowl.radius(bowl.brim, 0.)*k; origin = np.array([0., -R['float']['bank_drop'], -half-near+R['float']['under_bank']])
            kind, sh, land_from, rim_y = 'bowl', bowl, -half+R['float']['helper_back'], 0.
        helper, mine = place(variant, kind, sh, k, origin, sq[kind], rim_y, hat, anchor, root, bowed, length, half, land_from)
        placements[variant] = dict(scale=k, helper=helper, self=mine, mother_half=round(half, 4), exit_front=round(exit_front, 4))
    metrics = {
        'note': __doc__.splitlines()[0], 'source_model': MODEL.relative_to(ROOT).as_posix(), 'source_model_sha256': sha(MODEL.read_bytes()),
        'props': {'nest': 'marisa_hat_nest', 'bowl': 'marisa_hat_vessel'}, 'aquatic_scale': R['aquatic_scale'],
        'anchor_worn': [round(float(x), 5) for x in anchor], 'keel_worn': round(float(keel), 4),
        # the worn hat, turned over and pressed down, takes the nest's or bowl's depth (hat.py squashes the crown to this)
        'squash': {k: round(float(v), 4) for k, v in sq.items()},
        'nest': {'rim': R['nest']['rim'], 'floor': R['nest']['floor'], 'opening': [round(float(x), 4) for x in nest.rim], 'brim': [round(float(x), 4) for x in nest.brim]},
        'bowl': {'depth': R['bowl']['depth'], 'freeboard': fb, 'owner_sink': R['bowl']['owner_sink'], 'opening': [round(float(x), 4) for x in bowl.rim],
                 'brim': [round(float(x), 4) for x in bowl.brim], 'mask': mask},
        'seats': seats, 'braid': {'root': [round(float(x), 4) for x in root], 'length': round(length, 4)},
        'placements': placements, 'float': R['float'], 'inside_exit': R['inside_exit'], 'pregnancy': R['pregnancy']}
    texts = {PROPS[k]: json.dumps(v, separators=(',', ':'))+'\n' for k, v in props.items()}
    texts[METRICS] = json.dumps(metrics, ensure_ascii=False, indent=2)+'\n'
    index = json.loads(INDEX.read_text(encoding='utf-8')); index.setdefault('marisa_hat_vessel', {}); index.setdefault('marisa_hat_nest', {})
    texts[INDEX] = json.dumps(index, separators=(',', ':'))
    manifest = {'note': 'Runtime outputs of build.py', 'recipe_sha256': sha((HERE/'recipe.json').read_bytes()), 'build_sha256': sha(Path(__file__).read_bytes()),
                'outputs': {p.relative_to(ROOT).as_posix(): sha(t.encode()) for p, t in texts.items()}}
    texts[HERE/'manifest.json'] = json.dumps(manifest, ensure_ascii=False, indent=2)+'\n'
    for path, text in texts.items():
        if args.check:
            assert path.read_text(encoding='utf-8') == text, f'stale {path}'
        else:
            path.write_text(text, encoding='utf-8')
    print('hats', 'checked' if args.check else 'written', {k: len(v['vertices'])//8 for k, v in props.items()}, 'vertices; squash', metrics['squash'])
    for variant, p in placements.items():
        print(variant, {r: {k: p[r][k] for k in ('at_mother', 'angle', 'yaw', 'reach', 'look', 'grip_worn') if k in p[r]} for r in ('helper', 'self')})


if __name__ == '__main__':
    main()
