"""Author the whole-body puff (v10) and the selected whole-head motions.

The source rigs remain frozen inputs. Every clip written here is one root
transform (scale, rotation, lift) sampled over time, landed on the floor by
`ground()` so the base never leaves the ground; hair, hat, wings and braid ride
the root. The puff (ぷくー) is a squash and stretch clip: a small inhale squash,
a swell with overshoot, a hold with breathing and a fast tremor, a quick
ぷしゅー deflate squash and a settle. Its phases are fractions of the clip
duration, so `taiso` re-uses the same curve for its puff segment. The face comes
from the expression timeline: the angry pouting `puff` face where a model has
that texture, the derived `cheek_puff` face otherwise. Faces the server sets for
injury (`expression_yield`) win over the timeline.

The v7 cheek-lobe morph (refined skin, blush carriers, side-lock swing) was
retired in v10; its lumpy jowls read as tumours, not as a pout.
"""
import json, math, runpy, sys
from pathlib import Path
import numpy as np
sys.path.insert(0, str(Path(__file__).resolve().parent))
import braid_chain

LIBRARY = Path(__file__).resolve().parents[3]
TEXTURES = LIBRARY.parents[1]/'src/main/resources/assets/mcyukkuri/textures/entity'


def smooth(x):
    x = max(0, min(1, x))
    return x*x*(3-2*x)


def puff_shape(ident):
    """Body class that sets the swell amplitude: tall beans stay slender, fat deibu stays small."""
    return 'bean' if 'bean' in ident else 'deibu' if 'deibu' in ident else 'standard'


def puff_curve(t, duration, p, shape):
    """Root (scale, rotation) of one puff at time t; every phase is a fraction of the duration."""
    a = p['peak'][shape]; u = t/duration
    scale, pitch, roll = [1., 1., 1.], 0., 0.
    inhale, swell, hold, release = p['inhale_end'], p['swell_end'], p['hold_end'], p['release_end']
    mix = lambda k, w: [1+k[i]*w for i in range(3)]
    if u < inhale:
        q = smooth(u/inhale)
        scale = mix(p['inhale'], q); pitch = -p['pitch_degrees']*q
    elif u < swell:
        q = smooth((u-inhale)/(swell-inhale)); grow = q+p['overshoot']*math.sin(math.pi*q)
        scale = [1+a[i]*grow+p['inhale'][i]*(1-q) for i in range(3)]; pitch = -p['pitch_degrees']*(1-q)
    elif u < hold:
        h = (u-swell)/(hold-swell); ramp = smooth(h/.12)*smooth((1-h)/.12); env = math.sin(math.pi*h)
        breath = p['breath']*math.sin(2*math.pi*p['breath_hz']*t)*ramp
        tremor = p['tremor']*math.sin(2*math.pi*p['tremor_hz']*t)*env
        scale = [1+a[0]*(1+breath)+tremor, 1+a[1]*(1+breath)-tremor*p['tremor_height'], 1+a[2]*(1+breath)+tremor]
        roll = p['roll_degrees']*math.sin(2*math.pi*p['roll_hz']*t)*env
    elif u < release:
        q = smooth((u-hold)/(release-hold))
        scale = [1+a[i]*(1-q)+p['release'][i]*q for i in range(3)]
    else:
        q = smooth((u-release)/(1-release))
        scale = [1+p['release'][i]*(1-q) for i in range(3)]
    return scale, [pitch, 0., roll]


def puff_faces(cfg, ident, start, duration):
    """Face timeline of one puff, in clip time; the server's own face shows before the swell."""
    p = cfg['puff']
    swell = 'puff' if (TEXTURES/ident/'puff.png').exists() else 'cheek_puff'
    return [[round(start+duration*p['face_swell'], 6), round(start+duration*p['face_deflate'], 6), swell],
            [round(start+duration*p['face_deflate'], 6), round(start+duration, 6), 'cheek_puff']]


# ------------------------------------------------------------- root clips
def state(action, t, duration, cfg, height, angle, shape='standard'):
    scale, rotation, lift = [1., 1., 1.], [0., 0., 0.], 0.
    if action == 'puff':
        scale, rotation = puff_curve(t, duration, cfg['puff'], shape)
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
            return state('puff', t-10.4, cfg['taiso_puff_seconds'], cfg, height, angle, shape)
    return scale, rotation, lift


def compile_revision(model, cfg, face):
    """face: {'mouth_patch': {index, vertices} | None}; only the mouth patch of the derived face is kept."""
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
    shape = puff_shape(model['id'])

    def ground(scale, rot):
        """Root y offset that puts the lowest visible point back on the floor."""
        ry, rz = math.radians(rot[1]), math.radians(rot[2])
        if rot[0] == 0:  # Rz*Ry*S, the form every clip up to v8 used
            row = [math.sin(rz)*math.cos(ry)*scale[0], math.cos(rz)*scale[1], math.sin(rz)*math.sin(ry)*scale[2]]
        else:  # y row of Rz*Ry*Rx*S
            rx = math.radians(rot[0]); sx, cx = math.sin(rx), math.cos(rx)
            row = [math.sin(rz)*math.cos(ry)*scale[0], (math.sin(rz)*math.sin(ry)*sx+math.cos(rz)*cx)*scale[1],
                   (math.sin(rz)*math.sin(ry)*cx-math.cos(rz)*sx)*scale[2]]
        lowest = min(sum(row[k]*(v[k]-pivot[k]) for k in range(3))+pivot[1] for v in points)
        return floor-lowest

    result = {}
    for action in cfg['actions']:
        duration = {'hop': cfg['hop_seconds'], 'puff': cfg['puff_seconds']}.get(action) or model['clips'][action]['duration']
        step = cfg['puff']['sample_seconds'] if action == 'puff' else cfg['sample_seconds']
        count = math.ceil(duration/step)
        keys = {c: [] for c in ['position', 'rotation', 'scale']}
        times = [duration*i/count for i in range(count+1)]
        for t in times:
            scale, rot, lift = state(action, t, duration, cfg, height, angle, shape)
            pos_ = [0, ground(scale, rot)+lift, 0]
            for c, value in [('position', pos_), ('rotation', rot), ('scale', scale)]:
                keys[c].append([round(t, 8), *[round(x, 7) for x in value]])
        clip = {'duration': duration, 'loop': model['clips'][action]['loop'],
                'tracks': [{'bone': root, 'channel': c, 'keys': k} for c, k in keys.items()]}
        if action in ('puff', 'taiso'):
            start, span = (0, duration) if action == 'puff' else (10.4, cfg['taiso_puff_seconds'])
            clip['expression_spans'] = puff_faces(cfg, model['id'], start, span)
            clip['expression_yield'] = cfg['puff']['expression_yield']
        result[action] = clip
    # World-setting motions (v9): new clip names the game selects from everyday behaviors.
    if cfg.get('lore'):
        lore_cfg = json.loads((LIBRARY/cfg['lore']).read_text(encoding='utf-8'))
        lore = runpy.run_path(str((LIBRARY/cfg['lore']).parent/'author.py'))
        result.update(lore['compile_clips'](model, lore_cfg, root, ground, height))
        cfg = dict(cfg, braid_follow=dict(cfg['braid_follow'], actions=cfg['braid_follow']['actions']+lore_cfg['braid_follow']))
    result.update(braid_follow(model, cfg, result))
    chain = braid_chain.chain({b['name']: i for i, b in enumerate(model['bones'])})
    if chain:  # the braid swing runs down the chain in every clip that moves it
        result = {k: braid_chain.follow(chain, clip, cfg['braid_chain']) for k, clip in result.items()}
        if 'braid_cradle_lift' in result:
            result['braid_cradle_lift'] = braid_chain.reach_clip(model, result['braid_cradle_lift'], lore['TOOLS']['palette'])
    revision = {'bone_names': [b['name'] for b in model['bones']], 'clips': result,
                'expression_aliases': {'cheek_puff': 'neutral'},
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
    replaced. Peaks are held (also while the chain's lag passes the twist down the
    braid), then blurred, wrapping for looping clips."""
    spec = cfg.get('braid_follow')
    names = {b['name']: i for i, b in enumerate(model['bones'])}
    braid = [i for p in model['parts'] if p['name'].startswith('braid_lock_') for i in range(p['start'], p['start']+p['count'])]
    skin = next((p for p in model['parts'] if 'body_surface' in p['name']), None)
    if not spec or not braid or skin is None or 'hand_right' not in names:
        return {}
    hand = names['hand_right']; parent = model['bones'][hand]['parent']; pivot = np.array(model['bones'][hand]['pivot'])
    V = np.array(model['vertices']).reshape(-1, 8)[:, :3]; W = np.array(model['weights'])
    # Only a braid carried by the hand bone swings; skin-following braids (sparse, bean) move with the body.
    carried = {hand}
    for i, b in enumerate(model['bones']):  # the whole braid chain under the hand bone
        if b['parent'] in carried: carried.add(i)
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
        # The splay is held while the lagging braid chain (braid_chain.follow) passes the same twist.
        chain = braid_chain.chain(names); lag = cfg['braid_chain']['lag_seconds']*(len(chain)-1) if chain else 0
        k = max(1, round((spec['smooth_seconds']+lag)/spec['sample_seconds']))
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
