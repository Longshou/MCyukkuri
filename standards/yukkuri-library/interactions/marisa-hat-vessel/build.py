"""Marisa's own hat as a vessel: the worn hat hollowed, turned over, as a cradle on land and a boat.

The worn hat of the selected runtime Marisa (axis-aligned cuboids brim_*, hat_*, bow_*) is
copied box for box. The brim and the two upper crown tiers are opened into a dry well with a
ledge between the tiers and a floor at the top of the second tier; the narrow tiers and the
bent tip stay solid as the keel. Outer faces keep the hat's own atlas cells (the prop draws
with the Marisa atlas), faces turned to the well use the dark brim-edge cell.

The vessel frame has its origin at the opening centre on the rim, +Y out of the opening, the
long brim axis on Z (-Z is the front, the side the braid grips when worn) and the crown below.
On land the hat lies on its side at its natural rest tilt, the opening facing the mother; a
boat floats opening up. The aquatic Marisa's hat is larger (user, 2026-10-14).

Placements put the hat where the catcher's braid can hold it: a helper beside the lying hat,
holding its rim (searched over yaw and position for braid reach, clearance and a view of the
mother), or the mother setting it before her own outlet.

Writes the runtime prop, the shared metrics read by the game and the motion author, and a
manifest. Python only; does not start the game or a browser.

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
PROP = ASSETS/'yukkuri_props/marisa_hat_vessel.json'
METRICS = ASSETS/'yukkuri_presentation/hat_vessel.json'
INDEX = ASSETS/'yukkuri_props/subspecies-index.json'
TEXTURE = 'mcyukkuri:textures/entity/marisa/neutral.png'
R = json.loads((HERE/'recipe.json').read_text(encoding='utf-8'))


def sha(data): return hashlib.sha256(data).hexdigest()


def smooth(x):
    x = min(1., max(0., x)); return x*x*(3-2*x)


# ------------------------------------------------------------------ worn hat
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
    """Open the brim and the two upper tiers into a well; returns boxes (source, lo, hi) and the cavity."""
    w = R['wall']; base, t1 = boxes['hat_base'], boxes['hat_taper_1']
    hole1 = (np.r_[base['lo'][0]+w, 0, base['lo'][2]+w], np.r_[base['hi'][0]-w, 0, base['hi'][2]-w])
    hole2 = (np.r_[t1['lo'][0]+w, 0, t1['lo'][2]+w], np.r_[t1['hi'][0]-w, 0, t1['hi'][2]-w])
    ledge = base['hi'][1]-w; floor = t1['hi'][1]-w
    out = []
    for name, b in boxes.items():
        lo, hi = b['lo'].copy(), b['hi'].copy()
        if name.startswith('brim_') or name in ('hat_base', 'hat_band'):
            for a, z in subtract(lo, np.r_[hi[0], min(hi[1], ledge) if name == 'hat_base' else hi[1], hi[2]], *hole1): out.append((name, a, z))
            if name == 'hat_base':  # the ledge: the base top, open over the narrower tier
                for a, z in subtract(np.r_[lo[0], ledge, lo[2]], hi, *hole2): out.append((name, a, z))
        elif name == 'hat_taper_1':
            for a, z in subtract(lo, np.r_[hi[0], floor, hi[2]], *hole2): out.append((name, a, z))
            out.append((name, np.r_[lo[0], floor, lo[2]], hi))
        else:
            out.append((name, lo, hi))
    y0 = min(b['lo'][1] for n, b in boxes.items() if n.startswith('brim_'))
    cavity = [(np.r_[hole1[0][0], y0-1, hole1[0][2]], np.r_[hole1[1][0], ledge, hole1[1][2]]),
              (np.r_[hole2[0][0], ledge-.01, hole2[0][2]], np.r_[hole2[1][0], floor, hole2[1][2]])]
    return out, cavity, dict(rim=y0, ledge=ledge, floor=floor, hole1=hole1, hole2=hole2)


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


def triangles(boxes, worn, cavity, anchor):
    lining = worn['brim_sides']['faces'][(0, 1, 0)]
    lining_uv = [lining[1][:, 0].min(), lining[1][:, 1].min(), lining[1][:, 0].max(), lining[1][:, 1].max()]
    parts = {}
    for name, lo, hi in boxes:
        if np.any(hi-lo < 1e-4): continue
        for n, picks in CORNERS.items():
            c = np.array([[(lo, hi)[s][k] for k, s in enumerate(pick)] for pick in picks])
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


# ------------------------------------------------------------------ poses
def rot_x(a):
    c, s = math.cos(a), math.sin(a); return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def rot_y(a):  # x' = x cos + z sin, z' = -x sin + z cos (the game's Axis.YP / Vec3.yRot)
    c, s = math.cos(a), math.sin(a); return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


LYING = {'back': -1, 'bow': 1}  # which way the long axis is turned: lying on the back of the crown, or on the bow


def lay(mode):
    return rot_y(LYING[mode]*math.pi/2)


def rest_tilt(points, mode):
    """Lying on its side, opening toward cradle -Z: the tilt where the rim lip and the crown both touch.
    On its back the bow is on top; on the bow (a mother setting her own hat) the braid-side brim ends on her braid side."""
    flat = points@lay(mode).T  # long axis across (X), opening still up
    for deg in np.arange(20, 90.01, .05):
        P = flat@rot_x(-math.radians(deg)).T; low = P[:, 1].min()
        touching = P[P[:, 1] < low+.02]
        if touching[:, 2].min() < -1 and touching[:, 2].max() > 1:  # lip side and crown side both down
            return float(deg)
    raise SystemExit('no rest tilt')


def cradle_matrix(tilt, mode):
    return rot_x(-math.radians(tilt))@lay(mode)


def lying(P, opening, mode):
    tilt = rest_tilt(P, mode); C = cradle_matrix(tilt, mode); flat = P@C.T; lift = -float(flat[:, 1].min())
    lip = flat[flat[:, 1] < flat[:, 1].min()+.05]; lip_z = float(lip[:, 2].min())
    cy = R['cradle']; low = opening[0]+cy['from_wall'] if mode == 'back' else opening[1]-cy['from_wall']
    seats = [[round(float(v), 4) for v in (C@np.array([low, -cy['depth'], x])+[0, lift, 0])] for x in cy['across']]
    return dict(tilt=round(tilt, 3), lift=round(lift, 4), lip_z=round(lip_z, 4), seats=seats)


def body_exit(body, belly=1.):
    """Front of the live-birth exit at full belly (LiveBirthGeometry.localExit), model units."""
    b = R['pregnancy']; s = 1+b['swell']*belly; wide = (1+b['low_wide']*belly)
    half = (body['bounds'][0][1]-body['bounds'][0][0])/2*s*wide
    return half+.15*16


def braid(model):
    names = {b['name']: i for i, b in enumerate(model['bones'])}
    root = np.array(model['bones'][names['hand_right']]['pivot']); tip = np.array(model['bones'][names['hand_right_tip']]['pivot'])
    return root, tip, float(np.linalg.norm(tip-root))


def grip_reach(root, length, p):
    return float(np.linalg.norm(p-root))/length


def brim_points(worn, anchor, root, length, k):
    """Brim points on the braid side the braid reaches while the (scaled) hat is worn."""
    out = []
    for x in np.arange(6.0, 10.2, .5):
        for z in np.arange(-6.5, 6.6, 1.):
            inside = any(b['lo'][0]-1e-3 <= x <= b['hi'][0]+1e-3 and b['lo'][2]-1e-3 <= z <= b['hi'][2]+1e-3 for n, b in worn.items() if n.startswith('brim_'))
            g = np.array([x, 10.85, z])
            if inside and .6 <= grip_reach(root, length, anchor+k*(g-anchor)) <= 1.25: out.append(g)
    return out


def hat_pose(mode, k, yaw_rel, origin, cradle, freeboard):
    """Vessel points -> mother frame (mother at the origin, scene angle 0) for a hat whose entity yaw is the mother's + yaw_rel."""
    Rm = rot_y(math.radians(-yaw_rel))
    if mode == 'float':
        C, lift = np.eye(3), freeboard*k
    else:
        C, lift = cradle_matrix(cradle[mode]['tilt'], mode), cradle[mode]['lift']*k
    return lambda pts: ((pts*k)@C.T+[0, lift, 0])@Rm.T+origin


def place_helper(to_mother, vessel_points, grip_vessel, body, root, length, yaw_rel, origin, land_from, reach_max=None):
    """A helper holding the hat's rim with the braid: search the helper's angle and place (mother frame).

    land_from: the nearest z the helper's centre may take (the bank edge for a floating hat)."""
    W = R['helper']; pts = np.unique(np.round(vessel_points, 1), axis=0)
    hat = to_mother(pts); grip = to_mother(grip_vessel[None])[0]
    half = (body['bounds'][0][1]-body['bounds'][0][0])/2; depth = (body['bounds'][2][1]-body['bounds'][2][0])/2
    outlet = np.array([0, 1.3, -half])
    best = None
    for a in range(0, 360, 5):
        Rinv = rot_y(math.radians(-a))  # mother frame -> helper frame
        for hx in np.arange(-36, 36.01, 1.):
            for hz in np.arange(-34, 14.01, 1.):
                h = np.array([hx, 0, hz])
                if hz < land_from or (abs(hx) < 2*half+W['clear'] and abs(hz) < 2*depth+W['clear']): continue  # on land, clear of the mother
                if hz < origin[2]-W['behind_max']: continue  # beside the hat's rim, not behind its crown
                local_grip = Rinv@(grip-h); reach = grip_reach(root, length, local_grip)
                if not W['reach'][0] <= reach <= (reach_max or W['reach'][1]) or local_grip[0] < W['grip_side_min']: continue
                local_hat = (hat-h)@Rinv.T
                inside = (np.abs(local_hat[:, 0]) < half+W['clear'])&(local_hat[:, 2] > -depth-W['clear'])&(local_hat[:, 2] < depth+W['clear'])&(local_hat[:, 1] < 10)
                if inside.any(): continue
                to = Rinv@(outlet-h); to[1] = 0
                look = math.degrees(math.acos(max(-1, min(1, -to[2]/np.linalg.norm(to)))))
                score = W['look']*look+W['reach_weight']*abs(reach-W['reach_ideal'])+W['distance']*float(np.linalg.norm(h[[0, 2]]))
                if best is None or score < best[0]:
                    best = (score, dict(offset=[round(float(x), 4) for x in Rinv@(origin-h)], yaw=int((a+yaw_rel) % 360), reach=round(reach, 4),
                                        look=round(look, 2), at_mother=[float(hx), 0., float(hz)], angle=a,
                                        hat_in_mother=dict(origin=[round(float(x), 4) for x in origin], yaw=yaw_rel)))
    return best


def main():
    ap = argparse.ArgumentParser(); ap.add_argument('--check', action='store_true'); args = ap.parse_args()
    model = json.loads(MODEL.read_text(encoding='utf-8')); body = json.loads(BODY.read_text(encoding='utf-8'))['variants']['marisa']
    worn = worn_boxes(model); boxes, cavity, levels = hollow(worn)
    anchor = np.array([0, levels['rim'], (worn['hat_base']['lo'][2]+worn['hat_base']['hi'][2])/2])
    flat, parts = triangles(boxes, worn, cavity, anchor)
    P = np.array(flat).reshape(-1, 8)[:, :3]
    bounds = [[round(float(P[:, k].min()), 5), round(float(P[:, k].max()), 5)] for k in range(3)]
    prop = {'format': 1, 'units_per_block': 16, 'vertices': flat, 'parts': parts, 'bounds': bounds}
    # cavity in vessel coordinates
    def rect(hole):
        a, b = worn_to_vessel(np.r_[hole[0][0], levels['rim'], hole[0][2]], anchor), worn_to_vessel(np.r_[hole[1][0], levels['rim'], hole[1][2]], anchor)
        lo, hi = np.minimum(a, b), np.maximum(a, b); return [round(float(lo[0]), 4), round(float(hi[0]), 4), round(float(lo[2]), 4), round(float(hi[2]), 4)]
    opening, well = rect(levels['hole1']), rect(levels['hole2'])
    ledge, floor = -(levels['ledge']-levels['rim']), -(levels['floor']-levels['rim'])
    cradle = {mode: lying(P, opening, mode) for mode in LYING}
    model_root, model_tip, length = braid(model)
    grip_vessel = {kind: {role: worn_to_vessel(np.array(g), anchor) for role, g in roles.items()} for kind, roles in R['grip_worn'].items()}
    placements = {}
    exit_front = body_exit(body); lip_target = -(exit_front-R['lip_before_exit'])  # mother frame: the lip just short of the exit
    pivot = np.array(model['bones'][[b['name'] for b in model['bones']].index('root')]['pivot'])
    bowed = rot_x(math.radians(R['self_bow']))@(model_root-pivot)+pivot  # a mother bows to reach her own hat
    fb = R['boat']['freeboard']; fl = R['float']
    for kind, k in (('land', 1.), ('aquatic', R['aquatic_scale'])):
        if kind == 'land':
            # on land the hat lies on its side before the outlet, opening toward her
            back, bow = cradle['back'], cradle['bow']
            origin = np.array([0., 0., lip_target+back['lip_z']*k])
            pose = hat_pose('back', k, 180, origin, cradle, fb)
            _, helper = place_helper(pose, P, grip_vessel[kind]['helper'], body, model_root, length, 180, origin, -99)
            helper['mode'] = 'back'; helper['grip_worn'] = R['grip_worn']['land']['helper']
            self_origin = np.array([0., 0., lip_target+bow['lip_z']*k])
            g = hat_pose('bow', k, 180, self_origin, cradle, fb)(grip_vessel[kind]['self'][None])[0]
            mine = dict(offset=[round(float(x), 4) for x in self_origin], yaw=180, mode='bow', reach=round(grip_reach(bowed, length, g), 4), grip_worn=R['grip_worn']['land']['self'])
        else:
            # at the bank the aquatic Marisa floats her hat at the water's edge before the outlet, opening up
            half = exit_front-.15*16; edge = -half
            helper, mine = None, None
            for yaw_rel in (90, -90):
                near = (P[:, 0].max() if yaw_rel == 90 else -P[:, 0].min())*k
                origin = np.array([0., -fl['bank_drop'], edge-near+fl['under_bank']])
                pose = hat_pose('float', k, yaw_rel, origin, cradle, fb)
                for grip in brim_points(worn, anchor, model_root, length, k):  # the brim point the braid can reach on the head and here
                    gv = worn_to_vessel(grip, anchor)
                    found = place_helper(pose, P, gv, body, model_root, length, yaw_rel, origin, edge+fl['helper_back'], fl['reach_max'])
                    if found and (helper is None or found[0] < helper[0]): helper = (found[0], dict(found[1], mode='float', grip_worn=[round(float(x), 3) for x in grip]))
                    reach = grip_reach(bowed, length, pose(gv[None])[0])
                    if mine is None or abs(reach-1) < abs(mine['reach']-1):
                        mine = dict(offset=[round(float(x), 4) for x in origin], yaw=yaw_rel % 360, mode='float', reach=round(reach, 4), grip_worn=[round(float(x), 3) for x in grip])
            if helper is None: raise SystemExit('no aquatic helper placement')
            helper = helper[1]
        mine['bow'] = R['self_bow']
        placements[kind] = dict(scale=k, helper=helper, self=mine, lip_from_mother=round(lip_target, 4))
    boat = R['boat']
    metrics = {
        'note': __doc__.splitlines()[0], 'source_model': MODEL.relative_to(ROOT).as_posix(), 'source_model_sha256': sha(MODEL.read_bytes()),
        'prop': 'marisa_hat_vessel', 'anchor_worn': [round(float(x), 5) for x in anchor], 'aquatic_scale': R['aquatic_scale'],
        'opening': opening, 'well': well, 'brim_x': [round(float(P[:, 0].min()), 4), round(float(P[:, 0].max()), 4)], 'ledge': round(ledge, 4), 'floor': round(floor, 4), 'keel': round(float(P[:, 1].min()), 4),
        'braid': {'root': [round(float(x), 4) for x in model_root], 'tip': [round(float(x), 4) for x in model_tip], 'length': round(length, 4)},
        'grip_worn': R['grip_worn'], 'grip_vessel': {k: {r: [round(float(x), 4) for x in v] for r, v in roles.items()} for k, roles in grip_vessel.items()},
        'cradle': cradle,
        'boat': {'freeboard': boat['freeboard'], 'owner_sink': boat['owner_sink'], 'young_day': boat['young_day'], 'young_night': boat['young_night']},
        'float': R['float'], 'lip_before_exit': R['lip_before_exit'],
        'placements': placements, 'pregnancy': R['pregnancy']}
    texts = {PROP: json.dumps(prop, separators=(',', ':'))+'\n', METRICS: json.dumps(metrics, ensure_ascii=False, indent=2)+'\n'}
    index = json.loads(INDEX.read_text(encoding='utf-8')); index.setdefault('marisa_hat_vessel', {})
    texts[INDEX] = json.dumps(index, separators=(',', ':'))
    manifest = {'note': 'Runtime outputs of build.py', 'recipe_sha256': sha((HERE/'recipe.json').read_bytes()), 'build_sha256': sha(Path(__file__).read_bytes()),
                'outputs': {p.relative_to(ROOT).as_posix(): sha(t.encode()) for p, t in texts.items()}}
    texts[HERE/'manifest.json'] = json.dumps(manifest, ensure_ascii=False, indent=2)+'\n'
    for path, text in texts.items():
        if args.check:
            assert path.read_text(encoding='utf-8') == text, f'stale {path}'
        else:
            path.write_text(text, encoding='utf-8')
    print('vessel', 'checked' if args.check else 'written', len(flat)//8, 'vertices', len(parts), 'parts;', {m: (c['tilt'], c['lift']) for m, c in cradle.items()})
    for kind, p in placements.items(): print(kind, p)


if __name__ == '__main__':
    main()
