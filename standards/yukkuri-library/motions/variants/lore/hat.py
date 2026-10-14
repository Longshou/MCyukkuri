"""Marisa's braid takes her hat off, sets it down turned over as a cradle, holds it, and puts it back on.

Compiled into the Marisa model by author.compile_clips for the recipe's clips with a `hat` block.
The hat (hat_follow, which carries the hat and its bow) moves as one rigid body hooked to the
braid at one brim point (hat_vessel.json `grip_worn`). The braid (hand_right) aims its tip joint
at that point, stretching along its length, and the tuft (hand_right_tip) curls round the brim.
The hat ends where the game puts the cradle (hat_vessel.json `placements`): beside the helper
when a Marisa catches for her mate, or before her own outlet when a mother catches alone. The
aquatic variants carry her larger hat; the game scales the hat bone about `anchor_worn`, so the
track here places the unscaled hat with its anchor where the larger cradle's anchor lies.
"""
from pathlib import Path
import json, math
import numpy as np

ROOT = Path(__file__).resolve().parents[5]
METRICS = ROOT/'src/main/resources/assets/mcyukkuri/yukkuri_presentation/hat_vessel.json'


def smooth(x):
    x = min(1., max(0., x)); return x*x*(3-2*x)


def bump(x):
    return math.sin(math.pi*min(1., max(0., x)))**2


def rot_x(a):
    c, s = math.cos(a), math.sin(a); return np.array([[1, 0, 0], [0, c, -s], [0, s, c]])


def rot_y(a):
    c, s = math.cos(a), math.sin(a); return np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])


def euler(r):
    x, y, z = np.radians(r)
    cx, sx, cy, sy, cz, sz = math.cos(x), math.sin(x), math.cos(y), math.sin(y), math.cos(z), math.sin(z)
    return np.array([[cz*cy, cz*sy*sx-sz*cx, cz*sy*cx+sz*sx], [sz*cy, sz*sy*sx+cz*cx, sz*sy*cx-cz*sx], [-sy, cy*sx, cy*cx]])


def to_euler(R):
    """Degrees [x, y, z] with R = Rz Ry Rx (the game's rotateZYX)."""
    y = math.asin(max(-1., min(1., -R[2, 0])))
    if abs(R[2, 0]) < .9999:
        x = math.atan2(R[2, 1], R[2, 2]); z = math.atan2(R[1, 0], R[0, 0])
    else:
        x = math.atan2(-R[1, 2], R[1, 1]); z = 0.
    return [math.degrees(x), math.degrees(y), math.degrees(z)]


def quat(R):
    t = np.trace(R)
    if t > 0:
        s = math.sqrt(t+1)*2; q = [.25*s, (R[2, 1]-R[1, 2])/s, (R[0, 2]-R[2, 0])/s, (R[1, 0]-R[0, 1])/s]
    elif R[0, 0] > R[1, 1] and R[0, 0] > R[2, 2]:
        s = math.sqrt(1+R[0, 0]-R[1, 1]-R[2, 2])*2; q = [(R[2, 1]-R[1, 2])/s, .25*s, (R[0, 1]+R[1, 0])/s, (R[0, 2]+R[2, 0])/s]
    elif R[1, 1] > R[2, 2]:
        s = math.sqrt(1+R[1, 1]-R[0, 0]-R[2, 2])*2; q = [(R[0, 2]-R[2, 0])/s, (R[0, 1]+R[1, 0])/s, .25*s, (R[1, 2]+R[2, 1])/s]
    else:
        s = math.sqrt(1+R[2, 2]-R[0, 0]-R[1, 1])*2; q = [(R[1, 0]-R[0, 1])/s, (R[0, 2]+R[2, 0])/s, (R[1, 2]+R[2, 1])/s, .25*s]
    q = np.array(q); return q/np.linalg.norm(q)


def mat(q):
    w, x, y, z = q
    return np.array([[1-2*(y*y+z*z), 2*(x*y-z*w), 2*(x*z+y*w)], [2*(x*y+z*w), 1-2*(x*x+z*z), 2*(y*z-x*w)], [2*(x*z-y*w), 2*(y*z+x*w), 1-2*(x*x+y*y)]])


def slerp(A, B, q):
    a, b = quat(A), quat(B); d = float(a@b)
    if d < 0: b, d = -b, -d
    if d > .9995: r = a+(b-a)*q
    else:
        th = math.acos(d); r = (math.sin((1-q)*th)*a+math.sin(q*th)*b)/math.sin(th)
    return mat(r/np.linalg.norm(r))


def aim(v, d):
    """Smallest rotation turning direction v onto d."""
    v, d = v/np.linalg.norm(v), d/np.linalg.norm(d); axis = np.cross(v, d); s = np.linalg.norm(axis); c = float(v@d)
    if s < 1e-9: return np.eye(3)
    k = axis/s; K = np.array([[0, -k[2], k[1]], [k[2], 0, -k[0]], [-k[1], k[0], 0]])
    return np.eye(3)+s*K+(1-c)*K@K


def affine(R, t):
    m = np.eye(4); m[:3, :3] = R; m[:3, 3] = t; return m


class Rig:
    def __init__(self, model, root):
        self.m = model; self.names = {b['name']: i for i, b in enumerate(model['bones'])}; self.root = root
        B = model['bones']; self.pivot = lambda i: np.array(B[i]['pivot'], float)
        self.hat = self.names['hat_follow']; self.braid = self.names['hand_right']
        self.tips = [self.names[n] for n in ('hand_right_tip', 'hand_right_ribbon') if n in self.names]
        assert B[self.hat]['parent'] == B[self.braid]['parent']
        self.parent = B[self.hat]['parent']
        V = np.array(model['vertices']).reshape(-1, 8)[:, :3]; W = np.array(model['weights'])
        group = {self.hat} | {i for i, b in enumerate(B) if b['parent'] == self.hat}
        self.hat_points = np.unique(np.round(V[[int(w[0]) in group for w in W]], 3), axis=0)
        body = [i for p in model['parts'] if 'body_surface' in p['name'] for i in range(p['start'], p['start']+p['count'])]
        self.body_lo, self.body_hi = V[body].min(0), V[body].max(0)

    def chain(self, root_pose):
        """World matrix of the hat/braid parent for a root (position, rotation, scale); other bones rest."""
        B = self.m['bones']; mats = {}
        def world(i):
            if i in mats: return mats[i]
            m = np.eye(4)
            if i == self.root:
                pos, rot, scl = root_pose; R = euler(rot)*np.array(scl); pv = self.pivot(i)
                m[:3, :3] = R; m[:3, 3] = pv+np.array(pos)-R@pv
            mats[i] = world(B[i]['parent'])@m if B[i]['parent'] >= 0 else m
            return mats[i]
        return world(self.parent)


def target_pose(metrics, variant, role):
    """Hat transform (unscaled worn hat -> catcher's model space) where the game puts the cradle or the floated hat."""
    place = metrics['placements'][variant]; k = place['scale']; p = place[role]
    anchor = np.array(metrics['anchor_worn'])
    Mv = np.array([[0, 0, -1], [0, -1, 0], [-1, 0, 0]], float)  # worn -> vessel (build.worn_to_vessel)
    if p['mode'] == 'float':  # opening up on the water, the rim a freeboard above it
        C, lift = np.eye(3), metrics['boat']['freeboard']*k
    else:  # lying on its side on the ground
        cr = metrics['cradle'][p['mode']]
        C, lift = rot_x(-math.radians(cr['tilt']))@rot_y({'back': -1, 'bow': 1}[p['mode']]*math.pi/2), cr['lift']*k
    Ry = rot_y(math.radians(-p['yaw']))  # hat frame -> catcher frame
    R = Ry@C@Mv
    # the scaled hat's anchor (the vessel origin) sits at offset + Ry (0, lift, 0)
    t = np.array(p['offset'])+Ry@np.array([0, lift, 0])-R@anchor
    return affine(R, t), k, np.array(p['grip_worn'])


def swing(g0, g1, centre, out, q):
    """Grip path round the braid root: through a point out to the braid side, the reach blending."""
    a, b, m = g0-centre, g1-centre, out
    ra, rb = np.linalg.norm(a), np.linalg.norm(b)
    def turn(u, v, x):
        u, v = u/np.linalg.norm(u), v/np.linalg.norm(v); d = max(-1., min(1., float(u@v))); th = math.acos(d)
        if th < 1e-6: return u
        return (math.sin((1-x)*th)*u+math.sin(x*th)*v)/math.sin(th)
    direction = turn(a, m, q/.5) if q < .5 else turn(m, b, (q-.5)/.5)
    return centre+direction*(ra+(rb-ra)*q)


def compile_hat(model, cfg, root, ground, height, name, c):
    """Tracks for one hat clip: root, hat_follow (position, rotation), braid and tuft (rotation, scale)."""
    metrics = json.loads(METRICS.read_text(encoding='utf-8'))
    rig = Rig(model, root); spec = c['hat']; variant, role = spec['variant'], spec['role']
    end, k, grip_worn = target_pose(metrics, variant, role)
    anchor = np.array(metrics['anchor_worn'])
    grip_local = anchor+k*(grip_worn-anchor)  # on the (scaled) hat, in rest coordinates
    pv_hat, pv_braid = rig.pivot(rig.hat), rig.pivot(rig.braid)
    v0 = rig.pivot(rig.tips[0])-pv_braid
    d = c['duration']; reverse = spec.get('reverse', False)
    P = c['phases']  # reach, lift, carry, release (self only), back
    n = math.ceil(d/cfg['sample_seconds']); samples = [d*i/n for i in range(n+1)]

    def root_motion(t):
        s, r, lift = [1., 1., 1.], [0., 0., 0.], 0.
        r[2] = c['side_lean']*bump(t/P['carry'][1]) if t < P['carry'][1] else 0.
        if role == 'self':
            a = smooth((t-P['carry'][0])/(P['carry'][1]-P['carry'][0]))*(1-smooth((t-P['release'][0])/(d-P['release'][0])))
            r[0] = c['bow']*a; s = [1+.02*a, 1-.03*a, 1+.02*a]
        else:
            r[0] = c['bow']*bump((t-P['carry'][0])/(d-P['carry'][0]))
        return s, r, lift

    def hat_world(t, base):
        """Unscaled hat transform in model space."""
        on = base  # on the head, following the body
        lifted = on.copy(); lifted[:3, 3] += [0, c['lift'], 0]
        if t < P['lift'][0]: return on
        if t < P['lift'][1]:
            q = smooth((t-P['lift'][0])/(P['lift'][1]-P['lift'][0])); m = on.copy(); m[:3, 3] += [0, c['lift']*q, 0]; return m
        target = end.copy()
        if role == 'self':
            target[:3, 3] += [0, c['drop'], 0]  # let go just above where it comes to rest
        if t < P['carry'][1]:
            q = smooth((t-P['carry'][0])/(P['carry'][1]-P['carry'][0]))
            R = slerp(lifted[:3, :3], target[:3, :3], q)
            g0 = lifted[:3, :3]@grip_local+lifted[:3, 3]; g1 = target[:3, :3]@grip_local+target[:3, 3]
            centre = (base@np.r_[pv_braid, 1])[:3]  # swing round the braid root, out to its side, then down
            g = swing(g0, g1, centre, np.array(c['swing_out']), q)
            return affine(R, g-R@grip_local)
        if role == 'self' and t < P['release'][1]:
            q = ((t-P['release'][0])/(P['release'][1]-P['release'][0]))**2  # falls the last bit
            m = target.copy(); m[:3, 3] = target[:3, 3]+(end[:3, 3]-target[:3, 3])*min(1, max(0, q)); return m
        return end if role == 'self' else target

    def braid_weight(t):
        w = smooth(t/P['reach'][1])
        if role == 'self': w *= 1-smooth((t-P['release'][0])/(P['back'][1]-P['release'][0]))
        return w

    keys = {k_: [] for k_ in ('root_p', 'root_r', 'root_s', 'hat_p', 'hat_r', 'braid_r', 'braid_s', 'tip_r', 'tip_s')}
    times = [d-t for t in samples] if reverse else samples
    prev = {}
    worst = dict(stretch=[9, 0], clear=9.)
    for out_t, t in zip(samples, times):
        s, r, lift = root_motion(t)
        pos = [0., ground(s, r)+lift, 0.]
        parent = rig.chain((pos, r, s)); inv = np.linalg.inv(parent)
        base = parent  # the hat on the head moves with its parent
        G = hat_world(t, base)
        L = inv@G  # hat_follow local
        hat_rot = to_euler(L[:3, :3]); hat_pos = L[:3, :3]@pv_hat+L[:3, 3]-pv_hat
        # braid: aim the tip joint at the grip, blend from rest
        grip = G[:3, :3]@grip_local+G[:3, 3]
        dl = (inv@np.r_[grip, 1])[:3]-pv_braid
        L2 = float(dl@dl); sy = math.sqrt(max(L2-v0[0]**2-v0[2]**2, 1e-6))/abs(v0[1])
        Ra = aim(np.array([v0[0], v0[1]*sy, v0[2]]), dl)
        w = braid_weight(t)
        Rb = slerp(np.eye(3), Ra, w); sb = 1+(sy-1)*w
        curl = c['curl']*w
        worst['stretch'] = [min(worst['stretch'][0], sb), max(worst['stretch'][1], sb)]
        # clearance of the hat from the head while it is off
        if P['lift'][1] <= t:
            pts = (G[:3, :3]@(anchor+k*(rig.hat_points-anchor)).T).T+G[:3, 3]
            local = (inv@np.c_[pts, np.ones(len(pts))].T).T[:, :3]
            lo, hi = rig.body_lo, rig.body_hi
            depth = np.minimum.reduce([local[:, 0]-lo[0], hi[0]-local[:, 0], local[:, 1]-lo[1], hi[1]-local[:, 1], local[:, 2]-lo[2], hi[2]-local[:, 2]])
            worst['clear'] = min(worst['clear'], float(-depth.max()))
        for key, val in (('root_p', pos), ('root_r', r), ('root_s', s), ('hat_p', list(hat_pos)), ('hat_r', hat_rot),
                         ('braid_r', to_euler(Rb)), ('braid_s', [1., sb, 1.]), ('tip_r', [0., 0., curl]), ('tip_s', [1., 1/sb, 1.])):
            val = list(val)
            if key.endswith('_r') and key in prev:  # keep Euler angles continuous between samples
                val = [v+360*round((p-v)/360) for v, p in zip(val, prev[key])]
            prev[key] = val
            keys[key].append([round(out_t, 8), *[round(float(x), 5) for x in val]])
    tracks = [{'bone': root, 'channel': 'position', 'keys': keys['root_p']}, {'bone': root, 'channel': 'rotation', 'keys': keys['root_r']},
              {'bone': root, 'channel': 'scale', 'keys': keys['root_s']},
              {'bone': rig.hat, 'channel': 'position', 'keys': keys['hat_p']}, {'bone': rig.hat, 'channel': 'rotation', 'keys': keys['hat_r']},
              {'bone': rig.braid, 'channel': 'rotation', 'keys': keys['braid_r']}, {'bone': rig.braid, 'channel': 'scale', 'keys': keys['braid_s']}]
    for tip in rig.tips:
        tracks += [{'bone': tip, 'channel': 'rotation', 'keys': keys['tip_r']}, {'bone': tip, 'channel': 'scale', 'keys': keys['tip_s']}]
    return tracks, worst


def compile_hold(model, cfg, root, ground, height, c):
    """The helper holds the lying hat's rim with the braid and cheers with each contraction."""
    metrics = json.loads(METRICS.read_text(encoding='utf-8'))
    rig = Rig(model, root); spec = c['hat']; end, k, grip_worn = target_pose(metrics, spec['variant'], 'helper')
    anchor = np.array(metrics['anchor_worn']); grip_local = anchor+k*(grip_worn-anchor)
    grip = end[:3, :3]@grip_local+end[:3, 3]
    pv_hat, pv_braid = rig.pivot(rig.hat), rig.pivot(rig.braid); v0 = rig.pivot(rig.tips[0])-pv_braid
    d = c['duration']; n = math.ceil(d/cfg['sample_seconds']); samples = [d*i/n for i in range(n+1)]
    keys = {k_: [] for k_ in ('root_p', 'root_r', 'root_s', 'hat_p', 'hat_r', 'braid_r', 'braid_s', 'tip_r', 'tip_s')}
    prev = {}; worst = dict(stretch=[9, 0], clear=9.)
    for t in samples:
        k_ = c['crouch']; s = [1+.5*k_, 1-k_, 1+.5*k_]; r = [c['lean'], 0., 0.]
        if .3 <= t < 1.4: r[0] += c['nod']*bump(((t-.3)/.55) % 1)
        lift = 0.
        if t >= 1.8:  # the birth: a little hop of joy
            e = bump((t-1.8)/.6); lift = .03*height*e
        pos = [0., ground(s, r)+lift, 0.]
        parent = rig.chain((pos, r, s)); inv = np.linalg.inv(parent)
        L = inv@end; hat_rot = to_euler(L[:3, :3]); hat_pos = L[:3, :3]@pv_hat+L[:3, 3]-pv_hat
        dl = (inv@np.r_[grip, 1])[:3]-pv_braid
        sy = math.sqrt(max(float(dl@dl)-v0[0]**2-v0[2]**2, 1e-6))/abs(v0[1]); Ra = aim(np.array([v0[0], v0[1]*sy, v0[2]]), dl)
        worst['stretch'] = [min(worst['stretch'][0], sy), max(worst['stretch'][1], sy)]
        for key, val in (('root_p', pos), ('root_r', r), ('root_s', s), ('hat_p', list(hat_pos)), ('hat_r', hat_rot),
                         ('braid_r', to_euler(Ra)), ('braid_s', [1., sy, 1.]), ('tip_r', [0., 0., c['curl']]), ('tip_s', [1., 1/sy, 1.])):
            val = list(val)
            if key.endswith('_r') and key in prev: val = [v+360*round((p-v)/360) for v, p in zip(val, prev[key])]
            prev[key] = val; keys[key].append([round(t, 8), *[round(float(x), 5) for x in val]])
    tracks = [{'bone': root, 'channel': 'position', 'keys': keys['root_p']}, {'bone': root, 'channel': 'rotation', 'keys': keys['root_r']},
              {'bone': root, 'channel': 'scale', 'keys': keys['root_s']},
              {'bone': rig.hat, 'channel': 'position', 'keys': keys['hat_p']}, {'bone': rig.hat, 'channel': 'rotation', 'keys': keys['hat_r']},
              {'bone': rig.braid, 'channel': 'rotation', 'keys': keys['braid_r']}, {'bone': rig.braid, 'channel': 'scale', 'keys': keys['braid_s']}]
    for tip in rig.tips:
        tracks += [{'bone': tip, 'channel': 'rotation', 'keys': keys['tip_r']}, {'bone': tip, 'channel': 'scale', 'keys': keys['tip_s']}]
    return tracks, worst
