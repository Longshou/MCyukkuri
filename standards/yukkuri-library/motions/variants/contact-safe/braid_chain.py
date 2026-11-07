"""Marisa's braid as a bone chain (body-standard/features/marisa-single-braid/author.py).

The braid hangs from hand_right through hand_right_braid_1..5 to the tail bone hand_right_tip,
one woven lobe per bone. `arc` bends it to a point as the game does (motor/geom/BraidChain.java):
a smooth arc that keeps every segment's length, turns no joint more than MAX_TURN degrees and
bulges forward/down; a point out of reach gets the straight braid. `follow` passes a clip's
braid swing down the chain with a lag, the root leading, so strokes and sways bend along the
length while a held pose keeps the braid the clip authored. Rotations are R = Rz Ry Rx in
degrees, the game's rotateZYX.
"""
import math
import numpy as np
from pathlib import Path
import sys
sys.path.insert(0, str(Path(__file__).resolve().parents[5]/'scripts'))
from pose_client import request

MAX_TURN = 40.


def chain(names):
    """Bone indices hand_right, hand_right_braid_1.., hand_right_tip in `names` (name -> index), or None."""
    if 'hand_right' not in names or 'hand_right_tip' not in names or 'hand_right_braid_1' not in names:
        return None
    out, k = [names['hand_right']], 1
    while f'hand_right_braid_{k}' in names:
        out.append(names[f'hand_right_braid_{k}']); k += 1
    return out+[names['hand_right_tip']]


def euler(r):
    # Authoring rotations (wave/sag key generation), not a sampled bone palette.
    x, y, z = np.radians(r)
    cx, sx, cy, sy, cz, sz = math.cos(x), math.sin(x), math.cos(y), math.sin(y), math.cos(z), math.sin(z)
    return np.array([[cz*cy, cz*sy*sx-sz*cx, cz*sy*cx+sz*sx], [sz*cy, sz*sy*sx+cz*cx, sz*sy*cx-cz*sx], [-sy, cy*sx, cy*cx]])


def to_euler(R, near=None):
    """Degrees [x, y, z] of R, turned by whole turns to lie nearest `near` (keeps keys continuous)."""
    y = math.asin(max(-1., min(1., -R[2, 0])))
    if abs(R[2, 0]) < .9999:
        x = math.atan2(R[2, 1], R[2, 2]); z = math.atan2(R[1, 0], R[0, 0])
    else:
        x = math.atan2(-R[1, 2], R[1, 1]); z = 0.
    out = [math.degrees(x), math.degrees(y), math.degrees(z)]
    if near is not None:
        out = [v+360*round((p-v)/360) for v, p in zip(out, near)]
    return out


def aim(v, d):
    """Smallest rotation turning direction v onto d."""
    v, d = v/np.linalg.norm(v), d/np.linalg.norm(d); axis = np.cross(v, d); s = np.linalg.norm(axis); c = float(v@d)
    if s < 1e-9:
        if c > 0: return np.eye(3)
        n = np.cross(v, [1., 0., 0.] if abs(v[0]) < .9 else [0., 1., 0.]); n /= np.linalg.norm(n)
        return 2*np.outer(n, n)-np.eye(3)  # half a turn about a perpendicular
    k = axis/s; K = np.array([[0, -k[2], k[1]], [k[2], 0, -k[0]], [-k[1], k[0], 0]])
    return np.eye(3)+s*K+(1-c)*K@K


def partial(R, q):
    """The rotation R taken a share q of the way from rest (same axis, q times the angle)."""
    c = max(-1., min(1., (np.trace(R)-1)/2)); angle = math.acos(c)
    if angle < 1e-9: return np.eye(3)
    axis = np.array([R[2, 1]-R[1, 2], R[0, 2]-R[2, 0], R[1, 0]-R[0, 1]])
    if np.linalg.norm(axis) < 1e-9:  # half a turn: the axis is the eigenvector of +1
        w, v = np.linalg.eigh((R+R.T)/2); axis = v[:, np.argmax(w)]
    k = axis/np.linalg.norm(axis); K = np.array([[0, -k[2], k[1]], [k[2], 0, -k[0]], [-k[1], k[0], 0]]); a = q*angle
    return np.eye(3)+math.sin(a)*K+(1-math.cos(a))*K@K


def arc(joints, target, max_turn=MAX_TURN):
    """Joints (root first) bent so the last lies on `target`: a circular arc through the joints, lengths kept.

    The bulge projects model forward plus half-strength down onto the chord's normal plane. Nearer than
    the tightest allowed curl, the curl folds further (FABRIK) until the end is on the target; farther than
    the braid's length, the braid points straight at it."""
    if max_turn != MAX_TURN:
        raise ValueError('The runtime braid joint limit is fixed at 40 degrees')
    return np.array(request(dict(op='arc', joints=np.asarray(joints, float).ravel().tolist(),
                                 target=np.asarray(target, float).tolist()))['joints']).reshape(-1, 3)


def rotations(rest, solved):
    """Each chain bone's rotation relative to its parent bone that takes the rest joints onto the solved ones:
    a swing carried down from the bone above, so the woven lobes never twist against each other."""
    out, above = [], np.eye(3)
    for i in range(len(rest)-1):
        W = aim(above@(rest[i+1]-rest[i]), solved[i+1]-solved[i])@above; out.append(above.T@W); above = W
    return out


def _soft(R, limit):
    """R with its angle eased under `limit` degrees (limit*tanh(angle/limit)), so a fast swing whips but never kinks."""
    angle = math.degrees(math.acos(max(-1., min(1., (np.trace(R)-1)/2))))
    return R if angle < 1e-6 else partial(R, limit*math.tanh(angle/limit)/angle)


def _sample(keys, t):
    if t <= keys[0][0]: return keys[0][1:]
    for a, b in zip(keys, keys[1:]):
        if t <= b[0]:
            q = (t-a[0])/(b[0]-a[0]) if b[0] > a[0] else 0
            return [x+(y-x)*q for x, y in zip(a[1:], b[1:])]
    return keys[-1][1:]


def graded(value, k, joints):
    """Joint k's (1 below the root .. `joints` the tail) share of a [root, tail] pair graded along the chain, or the one value."""
    if not isinstance(value, (list, tuple)): return float(value)
    return value[0]+(value[1]-value[0])*((k-1)/(joints-1) if joints > 1 else 0.)


def phase(spec, joints):
    """Seconds the tail trails the root: the chain's graded lags added up."""
    return sum(graded(spec['lag_seconds'], k, joints) for k in range(1, joints+1))


def _window(t, load, duration):
    """0..1 tension of a `load` window {'start', 'end', 'ramp'} (clip seconds, wrapping) at clip second t."""
    x, span = (t-load['start']) % duration, (load['end']-load['start']) % duration
    if x > span: return 0.
    ease = lambda v: (lambda q: q*q*(3-2*q))(min(1., max(0., v)))
    return ease(x/load['ramp'])*ease((span-x)/load['ramp'])


def _sag(R, direction, degrees):
    """R turned (in its parent's frame) so the segment it carries along `direction` dips toward -Y by `degrees` times the sine
    of its angle from straight down: a braid held out sags under its weight, a hanging one does not."""
    d = R@direction; axis = np.cross(d, [0., -1., 0.]); s = float(np.linalg.norm(axis))
    if s < 1e-6 or degrees <= 0: return R
    a = math.radians(degrees)*s; k = axis/s; K = np.array([[0, -k[2], k[1]], [k[2], 0, -k[0]], [-k[1], k[0], 0]])
    return (np.eye(3)+math.sin(a)*K+(1-math.cos(a))*K@K)@R


def follow(bones, clip, spec):
    """The clip with its braid root swing passed down the chain as a travelling wave.

    Joint k trails the joint above by its `lag_seconds` and bends at most about its `max_bend_degrees` against it; a
    [root, tail] pair grades either along the chain, so a short lag and a small bend at the stiff root grow to a long lag
    and a loose tail, and a swing runs down the braid instead of bending it as one arc. Each joint sags `sag_degrees` at
    the horizontal (a braid held out droops a little under its weight, a hanging one does not). A `load` window
    ({'start', 'end', 'ramp'} clip seconds and `tension` 0..1; e.g. the power half of a paddle stroke) is a pull against
    the water: the braid straightens (bend and sag) under the tension and lets the stored bend go as it releases.

    `bones` is the chain (`chain`). A clip that already poses the inner joints is kept as authored. The tail keeps its
    own authored turn on top. Once clips let the lag settle over their last `settle_seconds`."""
    root, inner, tail = bones[0], bones[1:-1], bones[-1]
    tracks = clip['tracks']
    swing = next((t for t in tracks if t['bone'] == root and t['channel'] == 'rotation'), None)
    if swing is None or any(t['bone'] in inner for t in tracks):
        return clip
    own = next((t for t in tracks if t['bone'] == tail and t['channel'] == 'rotation'), None)
    duration, loop, settle, load = clip['duration'], clip['loop'], spec['settle_seconds'], spec.get('load')
    joints = len(bones)-1
    delay = np.cumsum([0.]+[graded(spec['lag_seconds'], k, joints) for k in range(1, joints+1)])
    caps = [graded(spec['max_bend_degrees'], k, joints) for k in range(1, joints+1)]
    down = np.array([0., -1., 0.])
    times = sorted({k[0] for k in swing['keys']} | ({k[0] for k in own['keys']} if own else set()))

    def at(t):
        t = t % duration if loop else min(max(t, 0.), duration)
        return euler(_sample(swing['keys'], t))
    keys = {b: [] for b in inner+[tail]}; last = {}
    for t in times:
        fade = 1. if loop else min(1., max(0., (duration-t)/settle))
        W = [at(t-d*fade) for d in delay]
        slack = 1.-(load['tension']*_window(t, load, duration) if load else 0.)
        above, local = W[0], []
        for k in range(1, len(bones)):
            R = partial(_soft(W[k-1].T@W[k], caps[k-1]), slack)
            world = _sag(above@R, down, spec['sag_degrees']*slack)
            local.append(above.T@world); above = world
        if own:
            local[-1] = local[-1]@euler(_sample(own['keys'], t))
        for b, R in zip(inner+[tail], local):
            last[b] = to_euler(R, last.get(b)); keys[b].append([round(t, 8), *[round(v, 5) for v in last[b]]])
    kept = [t for t in tracks if not (t['bone'] == tail and t['channel'] == 'rotation')]
    return dict(clip, tracks=kept+[{'bone': b, 'channel': 'rotation', 'keys': keys[b]} for b in inner+[tail]])


def rate_limited(rotations, step, loop):
    """Rotations (one per sample) each turned at most `step` degrees from the one before, so a joint chasing a target never
    snaps round; a loop runs twice so its first sample follows its last."""
    out, previous = [], None
    for R in (list(rotations)*2 if loop else rotations):
        if previous is not None:
            D = previous.T@R; angle = math.degrees(math.acos(max(-1., min(1., (np.trace(D)-1)/2))))
            if angle > step:
                U, _, Vt = np.linalg.svd(previous@partial(D, step/angle)); R = U@Vt
        out.append(R); previous = R
    return out[len(rotations):] if loop else out
