"""Marisa's braid as a bone chain (body-standard/features/marisa-single-braid/author.py).

The braid hangs from hand_right through hand_right_braid_1..5 to the tail bone hand_right_tip,
one woven lobe per bone. `arc` bends it to a point as the game does (motor/geom/BraidChain.java):
a smooth arc that keeps every segment's length, turns no joint more than MAX_TURN degrees and
bulges to the given side; a point out of reach gets the straight braid. `follow` passes a clip's
braid swing down the chain with a lag, the root leading, so strokes and sways bend along the
length while a held pose keeps the braid the clip authored. Rotations are R = Rz Ry Rx in
degrees, the game's rotateZYX.
"""
import math
import numpy as np

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


def _central(k, seg):
    return 2*np.arcsin(np.clip(k*seg/2, 0, 1))


def arc(joints, target, max_turn=MAX_TURN):
    """Joints (root first) bent so the last lies on `target`: a circular arc through the joints, lengths kept.

    The bulge goes to the joints' own side, plus a little sag below the chord and away from the body's axis. Nearer than
    the tightest allowed curl, the curl folds further (FABRIK) until the end is on the target; farther than
    the braid's length, the braid points straight at it."""
    P = np.asarray(joints, float); root = P[0]; seg = np.linalg.norm(np.diff(P, axis=0), axis=1)
    target = np.asarray(target, float); to = target-root; d = float(np.linalg.norm(to))
    if len(seg) == 0 or d < 1e-6 or seg.min() < 1e-9:
        return P.copy()
    u = to/d; flat = lambda v: v-u*float(v@u); length = float(seg.sum())
    if d >= length:
        return np.vstack([root, root+np.outer(np.cumsum(seg), u)])
    out = np.array([root[0], 0., root[2]]); out = .5*out/max(np.linalg.norm(out), 1e-9)  # away from the body's axis
    p = flat(sum((q-root for q in P[1:-1]), np.zeros(3))+np.array([0., -.3, 0.])+out)
    if np.linalg.norm(p) < 1e-6:
        p = flat(np.array([1., 0., 0.] if abs(u[0]) < .9 else [0., 0., 1.]))
    p = p/np.linalg.norm(p)
    turn = lambda k: max(((a+b)/2 for a, b in zip(_central(k, seg)[:-1], _central(k, seg)[1:])), default=0.)
    total = lambda k: float(_central(k, seg).sum())
    chord = lambda k: 2*math.sin(total(k)/2)/k if k > 1e-9 else length
    lo, hi = 0., 1.999/float(seg.max())
    for _ in range(50):  # the tightest curl inside the joint limit and short of a full turn
        k = (lo+hi)/2
        lo, hi = (k, hi) if turn(k) <= math.radians(max_turn) and total(k) <= 1.9*math.pi else (lo, k)
    k = lo; folded = chord(k) > d
    if not folded:
        a, b = 0., k
        for _ in range(60):
            m = (a+b)/2; a, b = (m, b) if chord(m) > d else (a, m)
        k = (a+b)/2
    out = P.copy()
    if k > 1e-9:
        phi = np.cumsum(_central(k, seg)); whole = total(k)
        t0 = math.cos(whole/2)*u+math.sin(whole/2)*p; n0 = math.sin(whole/2)*u-math.cos(whole/2)*p
        out[1:] = root+np.outer(np.sin(phi)/k, t0)+np.outer((1-np.cos(phi))/k, n0)
    if folded:  # FABRIK from the tightest arc
        for _ in range(32):
            out[-1] = target
            for i in range(len(seg)-1, -1, -1):
                v = out[i]-out[i+1]; out[i] = out[i+1]+v*seg[i]/max(np.linalg.norm(v), 1e-12)
            out[0] = root
            for i in range(len(seg)):
                v = out[i+1]-out[i]; out[i+1] = out[i]+v*seg[i]/max(np.linalg.norm(v), 1e-12)
            if np.linalg.norm(out[-1]-target) < 1e-4: break
    return out


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


def follow(bones, clip, spec):
    """The clip with its braid root swing passed down the chain, each joint `lag_seconds` behind the one above
    and bent at most about `max_bend_degrees` against it; with `drag_seconds` (a loaded stroke, e.g. a paddle in water)
    every joint also bends back by the swing of the last `drag_seconds`.

    `bones` is the chain (`chain`). A clip that already poses the inner joints is kept as authored. The tail
    keeps its own authored turn on top. Once clips let the lag settle over their last `settle_seconds`."""
    root, inner, tail = bones[0], bones[1:-1], bones[-1]
    tracks = clip['tracks']
    swing = next((t for t in tracks if t['bone'] == root and t['channel'] == 'rotation'), None)
    if swing is None or any(t['bone'] in inner for t in tracks):
        return clip
    own = next((t for t in tracks if t['bone'] == tail and t['channel'] == 'rotation'), None)
    duration, loop, lag, settle = clip['duration'], clip['loop'], spec['lag_seconds'], spec['settle_seconds']
    drag = spec.get('drag_seconds', 0.)
    times = sorted({k[0] for k in swing['keys']} | ({k[0] for k in own['keys']} if own else set()))

    def at(t):
        t = t % duration if loop else min(max(t, 0.), duration)
        return euler(_sample(swing['keys'], t))
    keys = {b: [] for b in inner+[tail]}; last = {}
    for t in times:
        fade = 1. if loop else min(1., max(0., (duration-t)/settle))
        W = [at(t-k*lag*fade) for k in range(len(bones))]
        load = W[0].T@at(t-drag*fade) if drag else np.eye(3)  # a loaded stroke (drag) bends every joint back against it
        local = [_soft(W[k-1].T@W[k]@load, spec['max_bend_degrees']) for k in range(1, len(bones))]
        if own:
            local[-1] = local[-1]@euler(_sample(own['keys'], t))
        for b, R in zip(inner+[tail], local):
            last[b] = to_euler(R, last.get(b)); keys[b].append([round(t, 8), *[round(v, 5) for v in last[b]]])
    kept = [t for t in tracks if not (t['bone'] == tail and t['channel'] == 'rotation')]
    return dict(clip, tracks=kept+[{'bone': b, 'channel': 'rotation', 'keys': keys[b]} for b in inner+[tail]])
