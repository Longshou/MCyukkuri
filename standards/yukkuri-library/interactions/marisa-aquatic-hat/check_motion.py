"""Read-only check that the aquatic hat clips hand the hat over to the hat entity exactly.

Forward kinematics as the game composes them (YukkuriModel.composeWorld: T(pivot+pos) Rz Ry Rx S T(-pivot) down the
bone chain, then the hat scale about anchor_worn on hat_follow) on the placement-fitted marisa.json, with the
clips from motion_corrections.json. The aquatic hat is drawn as integrated: accessory_marisa_aquatic plus the
placement-fit offset, on the hat bone. It must coincide with the set-down hat entity (marisa_aquatic_hat_turned
at the placement, scaled, crown pressed, at the freeboard) at the end of hat_off*_aq and the start of hat_on*_aq, and the
braid tip holds the brim point through hat_hold_aq (the hat is then its own entity and the worn hat is not drawn). The land clips are measured the same way,
with the model's own hat parts against marisa_hat_turned, and only reported.

  python check_motion.py [motion_corrections.json]
"""
from pathlib import Path
import json, math, sys
import numpy as np

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
ASSETS = ROOT/'src/main/resources/assets/mcyukkuri'
sys.path.insert(0, str(ROOT/'scripts'))
import placement_fit


def read(p): return json.loads(Path(p).read_text(encoding='utf-8'))


def euler(r):
    x, y, z = np.radians(r); cx, sx, cy, sy, cz, sz = math.cos(x), math.sin(x), math.cos(y), math.sin(y), math.cos(z), math.sin(z)
    return np.array([[cz*cy, cz*sy*sx-sz*cx, cz*sy*cx+sz*sx], [sz*cy, sz*sy*sx+cz*cx, sz*sy*cx-cz*sx], [-sy, cy*sx, cy*cx]])


def sample(keys, t):
    times = [k[0] for k in keys]
    if t <= times[0]: return np.array(keys[0][1:])
    if t >= times[-1]: return np.array(keys[-1][1:])
    i = int(np.searchsorted(times, t))-1; a, b = keys[i], keys[i+1]; q = (t-a[0])/(b[0]-a[0])
    return np.array(a[1:])+(np.array(b[1:])-np.array(a[1:]))*q


def world(model, names, clip, t, anchor, k):
    """Bone world matrices at time t; hat_follow carries the hat scale about anchor like the game."""
    tracks = {}
    for tr in clip['tracks']: tracks.setdefault(names[tr['bone']], {})[tr['channel']] = tr['keys']
    bones = model['bones']; out = []
    for b in bones:
        ch = tracks.get(b['name'], {}); pos = sample(ch['position'], t) if 'position' in ch else np.zeros(3)
        rot = sample(ch['rotation'], t) if 'rotation' in ch else np.zeros(3); scl = sample(ch['scale'], t) if 'scale' in ch else np.ones(3)
        pv = np.array(b['pivot'], float); m = np.eye(4); m[:3, :3] = euler(rot)@np.diag(scl); m[:3, 3] = pv+pos-m[:3, :3]@pv
        w = out[b['parent']]@m if b['parent'] >= 0 else m
        if b['name'] == 'hat_follow':
            s = np.eye(4); s[:3, :3] *= k; s[:3, 3] = anchor-k*anchor; w = w@s
        out.append(w)
    return {b['name']: out[i] for i, b in enumerate(bones)}


def entity(V, prop_points, role, variant):
    """The set-down hat entity's points in the catcher's model space (HatBoatRenderer: yaw, freeboard, scale, squash)."""
    place = V['placements'][variant]; p = place[role]; k = place['scale']; sigma = V['squash'][p['mode']]
    a = np.array(V['anchor_worn']); Mv = np.array([[0, 0, -1], [0, -1, 0], [-1, 0, 0]], float)
    c, s = math.cos(math.radians(-p['yaw'])), math.sin(math.radians(-p['yaw'])); Ry = np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])
    lift = (V['bowl']['freeboard'] if p['mode'] == 'bowl' else V['nest']['height'])*k
    local = (prop_points-a)@Mv.T*np.array([k, k*sigma, k])+[0, lift, 0]
    return local@Ry.T+np.array(p['offset']), np.array(p['offset'])+Ry@(np.array(p['rim'])*k+[0, lift, 0])


def main():
    motion = read(sys.argv[1] if len(sys.argv) > 1 else ASSETS/'yukkuri_presentation/motion_corrections.json')['models']['marisa']
    model = placement_fit.fitted(ASSETS/'yukkuri/marisa.json', placement_fit.load())
    names = motion['bone_names']; recipe = read(ROOT/'standards/yukkuri-library/motions/variants/lore/recipe.json')['clips']
    aq, land = read(ASSETS/'yukkuri_presentation/marisa_aquatic_hat_vessel.json'), read(ASSETS/'yukkuri_presentation/hat_vessel.json')
    worn = np.array(read(ASSETS/'yukkuri_props/accessory_marisa_aquatic.json')['vertices']).reshape(-1, 8)[:, :3]
    raw = read(ASSETS/'yukkuri/marisa.json'); R0 = np.array(raw['vertices']).reshape(-1, 8)
    land_raw = np.concatenate([R0[p['start']:p['start']+p['count'], :3] for p in raw['parts'] if p['name'].startswith(('brim_', 'hat_', 'bow_'))])
    F0 = np.array(model['vertices']).reshape(-1, 8)
    land_fit = np.concatenate([F0[p['start']:p['start']+p['count'], :3] for p in model['parts'] if p['name'].startswith(('brim_', 'hat_', 'bow_'))])
    offset = np.array(aq['worn']['placement_fit_offset'])
    rows = []
    for name, c in recipe.items():
        spec = c.get('hat')
        if not spec or name not in motion['clips']: continue
        variant, role = spec['variant'], spec['role']; V = aq if variant == 'aquatic' else land
        clip = motion['clips'][name]; d = clip['duration']; a = np.array(V['anchor_worn']); k = V['placements'][variant]['scale']
        drawn_rest, entity_rest = (worn+offset, worn) if variant == 'aquatic' else (land_fit, land_raw)
        target, rim = entity(V, entity_rest, role, variant)
        times = [d] if not spec.get('reverse') else [0.]
        if not c.get('phases'): times = list(np.linspace(0, d, 9))
        worst = tip_off = 0.; hold = not c.get('phases')
        for t in times:
            W = world(model, names, clip, t, a, k)
            if not hold:
                pts = (np.c_[drawn_rest, np.ones(len(drawn_rest))]@W['hat'].T)[:, :3]
                worst = max(worst, float(np.abs(pts-target).max()))
            if role == 'helper':
                tip = (W['hand_right_tip']@np.r_[np.array(model['bones'][[b['name'] for b in model['bones']].index('hand_right_tip')]['pivot']), 1])[:3]
                tip_off = max(tip_off, float(np.linalg.norm(tip-rim)))
        rows.append((name, variant, None if hold else round(worst, 4), round(tip_off, 4) if hold else None))
    for r in rows: print(f'{r[0]:18s} {r[1]:8s} ' + (f'hat vs entity max {r[2]:7.4f}' if r[2] is not None else f'held brim: braid tip off by {r[3]:.4f}'))
    for name, variant, worst, tip in rows:
        if variant == 'aquatic':
            assert worst is None or worst < .01, (name, 'hat off the entity', worst)
            assert tip is None or tip < .05, (name, 'braid tip off the held brim', tip)
    print('aquatic hand-offs exact (model units); land rows are the existing land clips, reported only')


if __name__ == '__main__':
    main()
