"""Marisa's braid takes her hat off, sets it down turned over as a cradle, holds it, and puts it back on.

Compiled into the Marisa model by author.compile_clips for the recipe's clips with a `hat` block.
The hat (hat_follow, which carries the hat and its bow) moves as one rigid body hooked to the
braid at one brim point (hat_vessel.json `grip_worn`). The braid chain (hand_right and its inner
joints) curves its tail joint onto that point, stretching along its length only beyond its reach,
and the tuft (hand_right_tip) curls round the brim.
The hat ends where the game puts the cradle (hat_vessel.json `placements`): beside the helper
when a Marisa catches for her mate, or before her own outlet when a mother catches alone. The
aquatic variants carry her larger hat; the game scales the hat bone about `anchor_worn`, so the
track here places the unscaled hat with its anchor where the larger cradle's anchor lies.
"""
from pathlib import Path
import json, math, sys
import numpy as np
sys.path.insert(0, str(Path(__file__).resolve().parents[1]/'contact-safe'))
import braid_chain

ROOT = Path(__file__).resolve().parents[5]
METRICS = ROOT/'standards/yukkuri-library/interactions/marisa-hat-vessel/geometry.json'


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
        chain = braid_chain.chain(self.names) or [self.braid, self.tips[0]]
        self.inner = chain[1:-1]; self.joints = np.array([B[i]['pivot'] for i in chain], float)
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
    """Where the game sets the hat down (hat_vessel.json placements), in the catcher's model space.

    Returns the rigid transform that turns the unscaled worn hat over and puts its anchor (the brim's
    seat on the head) where the set-down hat's brim lies, the crown squash that presses it down as
    the game draws it, the hat scale, the brim point the braid takes on the head, and the brim point it holds."""
    place = metrics['placements'][variant]; k = place['scale']; p = place[role]
    anchor = np.array(metrics['anchor_worn'])
    Mv = np.array([[0, 0, -1], [0, -1, 0], [-1, 0, 0]], float)  # worn -> vessel (build.worn_to_vessel)
    Ry = rot_y(math.radians(-p['yaw']))  # hat frame -> catcher frame
    # afloat the brim is a freeboard over the water line; on land the pressed crown stands the brim at the nest height
    lift = np.array([0, (metrics['bowl']['freeboard'] if p['mode'] == 'bowl' else metrics['nest']['height'])*k, 0])
    R = Ry@Mv
    t = np.array(p['offset'])+Ry@lift-R@anchor
    rim = np.array(p['offset'])+Ry@(np.array(p['rim'])*k+lift)
    return affine(R, t), metrics['squash'][p['mode']], k, np.array(p['grip_worn']), rim


def squashed(G, anchor, sigma):
    """G after pressing the worn hat's crown toward its brim (worn Y about the anchor)."""
    S = np.eye(4); S[1, 1] = sigma; S[1, 3] = anchor[1]*(1-sigma)
    return G@S


def bone_track(inv, G, pivot, sigma):
    """Local position, rotation and scale of hat_follow so its world transform is G after a crown squash."""
    L = inv@G; R = L[:3, :3]@np.diag([1, 1/sigma, 1])
    # L(p) = R S p + L_t, and the bone gives R S (p - P) + P + pos
    pos = L[:3, 3]+R@np.diag([1, sigma, 1])@pivot-pivot
    return list(pos), to_euler(R), [1., sigma, 1.]


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


def braid_to(inv, target, pv_braid, v0):
    dl = (inv@np.r_[target, 1])[:3]-pv_braid
    sy = math.sqrt(max(float(dl@dl)-v0[0]**2-v0[2]**2, 1e-6))/abs(v0[1])
    return aim(np.array([v0[0], v0[1]*sy, v0[2]]), dl), sy


def chain_to(inv, target, rig, offset=np.zeros(3)):
    """Rotations of hand_right and each inner chain bone that put the tail joint on `target`, and the braid's
    stretch: within its length the braid curves onto the point (braid_chain.arc, lengths kept); beyond it the
    straight braid stretches along its length as before."""
    rest = rig.joints+offset; local = (inv@np.r_[target, 1])[:3]
    if np.linalg.norm(local-rest[0]) <= np.linalg.norm(np.diff(rest, axis=0), axis=1).sum():
        return braid_chain.rotations(rest, braid_chain.arc(rest, local)), 1.
    R, sy = braid_to(inv, target, rest[0], rest[-1]-rest[0])
    return [R]+[np.eye(3)]*(len(rest)-2), sy


class Keys:
    NAMES = ('root_p', 'root_r', 'root_s', 'hat_p', 'hat_r', 'hat_s', 'braid_r', 'braid_s', 'tip_r', 'tip_s')

    def __init__(self): self.keys = {k: [] for k in self.NAMES}; self.prev = {}

    def add(self, t, inner=(), **vals):
        vals.update({f'inner_{i}_r': v for i, v in enumerate(inner)})
        for key, val in vals.items():
            self.keys.setdefault(key, [])
            val = [float(x) for x in val]
            if key.endswith('_r') and key in self.prev:  # keep Euler angles continuous between samples
                val = [v+360*round((p-v)/360) for v, p in zip(val, self.prev[key])]
            self.prev[key] = val; self.keys[key].append([round(t, 8), *[round(x, 5) for x in val]])

    def tracks(self, root, rig):
        k = self.keys
        out = [{'bone': root, 'channel': c, 'keys': k['root_'+c[0]]} for c in ('position', 'rotation', 'scale')]
        out += [{'bone': rig.hat, 'channel': c, 'keys': k['hat_'+c[0]]} for c in ('position', 'rotation', 'scale')]
        out += [{'bone': rig.braid, 'channel': 'rotation', 'keys': k['braid_r']}, {'bone': rig.braid, 'channel': 'scale', 'keys': k['braid_s']}]
        out += [{'bone': b, 'channel': 'rotation', 'keys': k[f'inner_{i}_r']} for i, b in enumerate(rig.inner)]
        for tip in rig.tips:
            out += [{'bone': tip, 'channel': 'rotation', 'keys': k['tip_r']}, {'bone': tip, 'channel': 'scale', 'keys': k['tip_s']}]
        return out


def mother_clearance(metrics, variant, role, pts):
    """How far the carried hat stays outside the swollen mother (a helper's clip only)."""
    place = metrics['placements'][variant]
    if role != 'helper': return 9.
    p = place['helper']; Rinv = rot_y(math.radians(-p['angle'])); m = Rinv@(-np.array(p['at_mother']))
    half = place['mother_half']; d = np.hypot(pts[:, 0]-m[0], pts[:, 2]-m[2])-half
    tall = pts[:, 1] < 9.6*1.26+1
    return float(d[tall].min()) if tall.any() else 9.


def compile_hat(model, cfg, root, ground, height, name, c):
    """Tracks for one hat clip: root, hat_follow (position, rotation, crown squash), braid and tuft (rotation, scale).

    The braid hooks the brim on the head, lifts the hat, swings it round its root out to its side and
    turns it over above the place, then presses it down: the soft crown squashes as the game draws the
    set-down hat while the braid slides to the brim point it holds (a helper) or lets go (a mother on
    her own). `reverse` plays the same motion backwards to put the hat back on."""
    metrics = json.loads(METRICS.read_text(encoding='utf-8'))
    rig = Rig(model, root); spec = c['hat']; variant, role = spec['variant'], spec['role']
    end, sigma, k, grip_worn, rim = target_pose(metrics, variant, role)
    anchor = np.array(metrics['anchor_worn'])
    grip_local = anchor+k*(grip_worn-anchor)  # on the (scaled) hat, in rest coordinates
    pv_hat, pv_braid = rig.pivot(rig.hat), rig.pivot(rig.braid)
    v0 = rig.pivot(rig.tips[0])-pv_braid
    d = c['duration']; reverse = spec.get('reverse', False); P = c['phases']
    n = math.ceil(d/cfg['sample_seconds']); samples = [d*i/n for i in range(n+1)]
    # it comes down onto the place from above and from beyond it (away from the catcher)
    out = (end@np.r_[anchor, 1])[:3]; out[1] = 0; out = out/(np.linalg.norm(out)+1e-9)
    above = end.copy(); above[:3, 3] += np.array([0, c['hover'], 0])+out*c.get('hover_out', 0.)

    def root_motion(t):
        s, r = [1., 1., 1.], [0., 0., 0.]
        r[2] = c['side_lean']*bump(t/P['carry'][1]) if t < P['carry'][1] else 0.
        if role == 'self':
            a = smooth((t-P['carry'][0])/(P['carry'][1]-P['carry'][0]))*(1-smooth((t-P['back'][0])/(P['back'][1]-P['back'][0])))
            r[0] = c['bow']*a; s = [1+.02*a, 1-.03*a, 1+.02*a]
        else:
            r[0] = c['bow']*bump((t-P['carry'][0])/(d-P['carry'][0]))
        return s, r

    def hat_world(t, base):
        """Rigid hat transform in model space and the crown squash."""
        if t < P['lift'][0]: return base, 1.
        if t < P['lift'][1]:
            q = smooth((t-P['lift'][0])/(P['lift'][1]-P['lift'][0])); m = base.copy(); m[:3, 3] += [0, c['lift']*q, 0]; return m, 1.
        lifted = base.copy(); lifted[:3, 3] += [0, c['lift'], 0]
        if t < P['carry'][1]:
            q = smooth((t-P['carry'][0])/(P['carry'][1]-P['carry'][0]))
            R = slerp(lifted[:3, :3], above[:3, :3], q)
            g0 = lifted[:3, :3]@grip_local+lifted[:3, 3]; g1 = above[:3, :3]@grip_local+above[:3, 3]
            centre = (base@np.r_[pv_braid, 1])[:3]  # round the braid root, out to its side, then over the place
            return affine(R, swing(g0, g1, centre, np.array(c['swing_out']), q)-R@grip_local), 1.
        q = smooth((t-P['settle'][0])/(P['settle'][1]-P['settle'][0]))
        m = above.copy(); m[:3, 3] = above[:3, 3]+(end[:3, 3]-above[:3, 3])*q
        return m, 1+(sigma-1)*q  # pressed down, the soft crown gives

    def braid_target(t, G, sq):
        on_hat = (squashed(G, anchor, sq)@np.r_[grip_local, 1])[:3]
        if t < P['settle'][0]: return on_hat, smooth(t/P['reach'][1])
        q = smooth((t-P['settle'][0])/(P['settle'][1]-P['settle'][0]))
        target = on_hat+(rim-on_hat)*q  # from the brim it carried by to the rim it holds
        w = 1.
        if role == 'self': w = 1-smooth((t-P['back'][0])/(P['back'][1]-P['back'][0]))
        return target, w

    keys = Keys(); times = [d-t for t in samples] if reverse else samples
    worst = dict(stretch=[9, 0], clear=9., mother=9.)
    for out_t, t in zip(samples, times):
        s, r = root_motion(t)
        pos = [0., ground(s, r), 0.]
        parent = rig.chain((pos, r, s)); inv = np.linalg.inv(parent)
        G, sq = hat_world(t, parent)
        Gs = squashed(G, anchor, sq)
        hp, hr, hs = bone_track(inv, Gs, pv_hat, sq)
        target, w = braid_target(t, G, sq)
        Ra, sy = chain_to(inv, target, rig)
        Rb = [braid_chain.partial(R, w) for R in Ra]; sb = 1+(sy-1)*w
        worst['stretch'] = [min(worst['stretch'][0], sb), max(worst['stretch'][1], sb)]
        if P['lift'][1] <= t:  # the hat off the head: clear of the catcher's head and of the mother
            pts = (Gs[:3, :3]@(anchor+k*(rig.hat_points-anchor)).T).T+Gs[:3, 3]
            local = (inv@np.c_[pts, np.ones(len(pts))].T).T[:, :3]
            lo, hi = rig.body_lo, rig.body_hi
            depth = np.minimum.reduce([local[:, 0]-lo[0], hi[0]-local[:, 0], local[:, 1]-lo[1], hi[1]-local[:, 1], local[:, 2]-lo[2], hi[2]-local[:, 2]])
            worst['clear'] = min(worst['clear'], float(-depth.max()))
            if t < P['settle'][0]:  # set down, the near brim tucks under the mother's belly by design
                worst['mother'] = min(worst['mother'], mother_clearance(metrics, variant, role, pts))
        keys.add(out_t, root_p=pos, root_r=r, root_s=s, hat_p=hp, hat_r=hr, hat_s=hs, braid_r=to_euler(Rb[0]), braid_s=[1., sb, 1.],
                 tip_r=[0., 0., c['curl']*w], tip_s=[1., 1/sb, 1.], inner=[to_euler(R) for R in Rb[1:]])
    return keys.tracks(root, rig), worst


def compile_hold(model, cfg, root, ground, height, c):
    """The helper holds the set-down hat's brim with the braid and cheers with each contraction."""
    metrics = json.loads(METRICS.read_text(encoding='utf-8'))
    rig = Rig(model, root); spec = c['hat']; end, sigma, k, _, rim = target_pose(metrics, spec['variant'], 'helper')
    anchor = np.array(metrics['anchor_worn'])
    pv_hat, pv_braid = rig.pivot(rig.hat), rig.pivot(rig.braid); v0 = rig.pivot(rig.tips[0])-pv_braid
    d = c['duration']; n = math.ceil(d/cfg['sample_seconds']); samples = [d*i/n for i in range(n+1)]
    keys = Keys(); worst = dict(stretch=[9, 0], clear=9., mother=9.); Gs = squashed(end, anchor, sigma)
    for t in samples:
        k_ = c['crouch']; s = [1+.5*k_, 1-k_, 1+.5*k_]; r = [c['lean'], 0., 0.]
        if .3 <= t < 1.4: r[0] += c['nod']*bump(((t-.3)/.55) % 1)
        lift = .03*height*bump((t-1.8)/.6) if t >= 1.8 else 0.  # the birth: a little hop of joy
        pos = [0., ground(s, r)+lift, 0.]
        parent = rig.chain((pos, r, s)); inv = np.linalg.inv(parent)
        hp, hr, hs = bone_track(inv, Gs, pv_hat, sigma)  # (in the game the set-down hat is its own entity)
        Ra, sy = chain_to(inv, rim, rig)
        worst['stretch'] = [min(worst['stretch'][0], sy), max(worst['stretch'][1], sy)]
        keys.add(t, root_p=pos, root_r=r, root_s=s, hat_p=hp, hat_r=hr, hat_s=hs, braid_r=to_euler(Ra[0]), braid_s=[1., sy, 1.],
                 tip_r=[0., 0., c['curl']], tip_s=[1., 1/sy, 1.], inner=[to_euler(R) for R in Ra[1:]])
    return keys.tracks(root, rig), worst
