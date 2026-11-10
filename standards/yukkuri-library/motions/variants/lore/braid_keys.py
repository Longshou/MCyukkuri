"""Marisa's braid laid by authored key poses (braid_brim_lift, braid_brim_lower; project design).

The braid is too short and thick to hook over her own brim (root under the brim, 9.7 units long, ~2.5 thick), so these clips
never ask it to: it curls under a young on the ground beside her braid side, swings it out in an arc that stays outside her
body and hat, rises along the outside of the brim edge and holds the young level with the brim just outside it; the young
steps onto the brim itself (user 2026-11-05). The lowering is its own loop: the tuft curls up close outside her cheek, then
swings out wide and down, so it never retraces the way up (a recipe `mirror` still plays a clip's keys and body backwards).

A key (`braid_keys`: [t, "rest"] or [t, {"tip", "root", "arrive"}], then "stop" and/or "turn") gives the tuft end (the
farthest tuft vertex, where the young sits) and two directions in the model frame at that moment: the braid leaves its root
along `root` and reaches the tip coming from `arrive`. The chain is laid along a cubic Bezier guide through them, every segment
keeping its authored length, the guide's handles scaled until the last joint lands on the tip. Between keys the tip and both
directions follow C1 cubics with Catmull-Rom tangents, zero only at keys marked "stop" (holds, the ends), and the chain is laid
again on every sample, so the braid passes its other keys without stopping, turning back or re-folding.

A key marked "turn" is reached by turning the chain bones from the key before's laid pose to its own instead (shortest arc).
Between the hanging rest and a raised pose the braid has slack the guide can only take up by folding back on itself, and a
chain laid on such a fold kinks and jumps from sample to sample (seen as the braid spinning round as it rises). Off rest the
tuft leads and the root follows (`LEAD`), so the tuft curls up close beside her before the braid lifts; onto rest the root
leads, the same curl played back.

The exported clip carries `tip`: the tuft end per sample from the exported tracks' own forward kinematics, which is where
the game seats the young (family/ParentCarry). `gate` checks every sample: braid against her body skin (beyond its rest
contact) and every hat cuboid, the floor, and the young (a cylinder on the tip, smallest and largest brim size) against body
and hat while it rides, and `continuity` the chain's turn between samples. Run with `--check` for the report on the exported
clips (`continuity` on every Marisa braid_* clip).
"""
from pathlib import Path
import json, math, sys
import numpy as np

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[4]
sys.path.insert(0, str(HERE.parent/'contact-safe'))
import braid_chain

RECIPE = json.loads((HERE/'recipe.json').read_text(encoding='utf-8'))

TOUCH = .1  # model units a surface may be pressed in between the drawn meshes (resting contact)
YOUNG = ((.7, .53), (1.7, 1.28))  # radius and height (model units) of the smallest and largest young the brim seats
HAT = ('brim_', 'hat_', 'bow_')
LEAD = .6  # share of a "turn" span the root starts after the tuft (project design: the tuft curls up beside her cheek first)
# Degrees a braid joint may turn between two exported samples (0.04 s): the chain's whole per-joint bend allowance
# (motor/geom/BraidChain MAX_TURN). A joint turning further inside one sample jumps rather than swings, and the game lerps
# each Euler channel between samples (pose/Clips.sample), so a channel stepping half a turn or more spins it.
STEP = braid_chain.MAX_TURN


def smooth(x):
    x = min(1., max(0., x)); return x*x*(3-2*x)


def bump(x):
    return math.sin(math.pi*min(1., max(0., x)))**2


def keyed(t, keys):
    if t <= keys[0][0]: return keys[0][1]
    for (a, x), (b, y) in zip(keys, keys[1:]):
        if t <= b: return x+(y-x)*smooth((t-a)/(b-a))
    return keys[-1][1]


def linear(t, rows):
    for (a, x), (b, y) in zip(rows, rows[1:]):
        if t <= b: return x+(y-x)*min(1., max(0., (t-a)/(b-a)))
    return rows[-1][1]


def source(name, c):
    """The clip whose keys and body this one plays, the time map onto it and back (identity, or the recipe mirror)."""
    if 'mirror' not in c: return name, c, (lambda t: t), (lambda t: t)
    rows = c['mirror']['map']; back = sorted([y, x] for x, y in rows)
    return c['mirror']['of'], RECIPE['clips'][c['mirror']['of']], (lambda t: linear(t, rows)), (lambda t: linear(t, back))


def state(action, t, c):
    """Root (scale, rotation, lift, [x, z] shift). She leans and looks to her braid side while the braid reaches the young on
    the ground (roll -7, pitch -3: tilt 10), squashes a little with the effort of the lift and is upright again by place."""
    of, c, at, _ = source(action, c); t = at(t)
    if of == 'braid_brim_lift':
        lean = keyed(t, [(0, 0), (.45, 1), (.6, 1), (1.3, .15), (1.65, 0)])
        look = keyed(t, [(0, 0), (.45, 1), (.7, 1), (1.65, 0)]); effort = bump((t-.55)/1.1)
        return ([1+.03*lean+.02*effort, 1-.08*lean-.04*effort, 1+.03*lean+.02*effort], [-3*lean, -10*look, -7*lean], 0.,
                [.3*lean, 0.])
    if of == 'braid_brim_lower':
        # The same body the other way round: upright while the young steps on, leaning and looking to her braid side as the
        # braid sets it on the ground (tilt 10), the effort of the carry in between.
        lean = keyed(t, [(0, 0), (1.15, 0), (1.85, 1), (2.0, 1), (2.6, 0)])
        look = keyed(t, [(0, 0), (.8, 0), (1.85, 1), (2.15, 1), (2.6, 0)]); effort = bump((t-.8)/1.1)
        return ([1+.03*lean+.02*effort, 1-.08*lean-.04*effort, 1+.03*lean+.02*effort], [-3*lean, -10*look, -7*lean], 0.,
                [.3*lean, 0.])
    raise KeyError(of)


def authored(c):
    """Whether a recipe clip lays its braid by key poses (its own, or its mirror source's)."""
    return 'braid_keys' in c or 'braid_keys' in RECIPE['clips'].get(c.get('mirror', {}).get('of', ''), {})


class Chain:
    """The model's braid chain at rest: bones root to tuft, their pivots and the tuft end, the segment lengths."""
    def __init__(self, model):
        self.model = model; names = {b['name']: i for i, b in enumerate(model['bones'])}
        self.bones = braid_chain.chain(names)
        V = np.array(model['vertices']).reshape(-1, 8)[:, :3]
        tuft = [i for p in model['parts'] if p['name'].startswith('braid_lock_tuft') for i in range(p['start'], p['start']+p['count'])]
        top = np.array(model['bones'][self.bones[0]]['pivot'], float)
        self.end = tuft[int(np.argmax(np.linalg.norm(V[tuft]-top, axis=1)))]
        self.rest = np.array([model['bones'][i]['pivot'] for i in self.bones]+[V[self.end].tolist()], float)
        self.seg = np.linalg.norm(np.diff(self.rest, axis=0), axis=1)

    def along(self, curve):
        """Joints at the segment lengths along a dense polyline from its start, or None when the polyline runs out first."""
        J, k = [curve[0]], 0
        for s in self.seg:
            c = J[-1]
            while k < len(curve)-1 and np.linalg.norm(curve[k+1]-c) < s: k += 1
            if k >= len(curve)-1: return None
            a, d = curve[k], curve[k+1]-curve[k]; f = a-c
            A, B, C = d@d, 2*f@d, f@f-s*s; J.append(a+d*(-B+math.sqrt(max(B*B-4*A*C, 0)))/(2*A))
        return np.array(J)

    def lay(self, tip, root, arrive):
        """Joints (local frame) along the Bezier guide root -> tip, its handles scaled until the chain ends on the tip."""
        u = np.linspace(0, 1, 2000)[:, None]; r = self.rest[0]
        d0 = np.asarray(root, float)/np.linalg.norm(root); d1 = np.asarray(arrive, float)/np.linalg.norm(arrive)
        bez = lambda lam: (1-u)**3*r+3*(1-u)**2*u*(r+lam*d0)+3*(1-u)*u**2*(tip+lam*d1)+u**3*tip
        if np.linalg.norm(tip-r) >= self.seg.sum(): raise ValueError(f'braid key tip {tip} out of the braid reach')
        lo, hi = 0., 30.
        for _ in range(60):
            mid = (lo+hi)/2
            if self.along(bez(mid)) is None: lo = mid
            else: hi = mid
        return self.along(bez(hi))


def rotvec(R):
    c = max(-1., min(1., (np.trace(R)-1)/2)); a = math.acos(c)
    if a < 1e-9: return np.zeros(3)
    if a > math.pi-1e-6: w, v = np.linalg.eigh((R+R.T)/2); return v[:, np.argmax(w)]*a
    return np.array([R[2, 1]-R[1, 2], R[0, 2]-R[2, 0], R[1, 0]-R[0, 1]])/(2*math.sin(a))*a


def matrix(v):
    a = float(np.linalg.norm(v))
    if a < 1e-12: return np.eye(3)
    k = v/a; K = np.array([[0, -k[2], k[1]], [k[2], 0, -k[0]], [-k[1], k[0], 0]])
    return np.eye(3)+math.sin(a)*K+(1-math.cos(a))*K@K


def hermite(times, values, stops, t):
    """C1 cubic through `values` at `times`: Catmull-Rom tangents, zero at stopping keys and the ends."""
    n = len(times)
    if t <= times[0]: return values[0]
    if t >= times[-1]: return values[-1]
    i = max(j for j in range(n-1) if times[j] <= t)
    def tangent(j):
        if stops[j] or j in (0, n-1): return np.zeros_like(values[j])
        return (values[j+1]-values[j-1])/(times[j+1]-times[j-1])
    h = times[i+1]-times[i]; s = (t-times[i])/h
    return ((2*s**3-3*s**2+1)*values[i]+(s**3-2*s**2+s)*h*tangent(i)+(-2*s**3+3*s**2)*values[i+1]+(s**3-s**2)*h*tangent(i+1))


def tracks(model, name, c, samples, root_tracks, palette):
    """Rotation tracks of the chain bones for clip `name` (its own keys, or its mirror source's played through the time map, the
    source's keys posed on this clip's root tracks read back through the same map). Every sample lays the chain on a guide whose
    tip, root and arrival directions follow C1 cubics through the keys', so the braid between keys is itself a guide-laid curve
    and the tip runs one smooth path; a "rest" key is the rest braid's own guide, its small difference from the exact rest pose
    easing out over the next key's span. A key marked "turn" is reached by turning every chain bone from the key before's laid
    pose to its own (shortest arc, each bone eased over its share of the span, the tuft first off rest, the root first back)."""
    chain = Chain(model); of, c, at, back = source(name, c)
    own = [{'bone': tr['bone'], 'channel': tr['channel'], 'keys': [[k[0], *sample_root(tr['keys'], back(k[0]))] for k in tr['keys']]}
           for tr in root_tracks] if of != name else root_tracks
    parent = model['bones'][chain.bones[0]]['parent']
    rest = {'tip': chain.rest[-1], 'root': chain.rest[1]-chain.rest[0], 'arrive': chain.rest[-2]-chain.rest[-1]}
    keys = c['braid_keys']; times = [k[0] for k in keys]; stops = ['stop' in k[2:] for k in keys]
    specs = [rest if k[1] == 'rest' else {f: np.asarray(v, float) for f, v in k[1].items()} for k in keys]
    def lay(spec, t, frame):
        inv = np.linalg.inv(palette(model, own, t)[parent]) if frame else np.eye(4)
        tip = (inv@np.r_[spec['tip'], 1])[:3]; dirs = [inv[:3, :3]@spec[f] for f in ('root', 'arrive')]
        return np.array([rotvec(R) for R in braid_chain.rotations(chain.rest, chain.lay(tip, *dirs))])
    # The rest guide is laid in the chain's own rest frame; its offset from the true rest eases out away from each rest key.
    offset = -lay(rest, 0, False)
    def laid(u):
        v = lay({f: hermite(times, [s[f] for s in specs], stops, u) for f in ('tip', 'root', 'arrive')}, u, True)
        for j, k in enumerate(keys):
            if k[1] != 'rest': continue
            span = times[j+1]-times[j] if j+1 < len(keys) else times[j]-times[j-1]
            v = v+offset*(1-smooth(abs(u-times[j])/span))
        return v
    ends = {j: [[matrix(x) for x in laid(times[i])] for i in (j-1, j)] for j, k in enumerate(keys) if 'turn' in k[2:]}
    def pose(u):
        j = next((j for j in ends if times[j-1] < u <= times[j]), None)
        if j is None: return laid(u)
        s = (u-times[j-1])/(times[j]-times[j-1]); n = len(chain.bones)-1; off = keys[j-1][1] == 'rest'
        delay = [LEAD*(n-k)/n if off else LEAD*k/n for k in range(n+1)]  # chain bones root (k = 0) to tuft (k = n)
        return np.array([rotvec(A@braid_chain.partial(A.T@B, smooth((s-d)/(1-LEAD)))) for d, (A, B) in zip(delay, zip(*ends[j]))])
    rows, last = {b: [] for b in chain.bones}, {}
    for t in samples:
        v = pose(at(t))
        for b, x in zip(chain.bones, v):
            last[b] = braid_chain.to_euler(matrix(x), last.get(b)); rows[b].append([round(t, 8), *[round(y, 5) for y in last[b]]])
    return [{'bone': b, 'channel': 'rotation', 'keys': rows[b]} for b in chain.bones]


def sample_root(keys, t):
    if t <= keys[0][0]: return keys[0][1:]
    for a, b in zip(keys, keys[1:]):
        if t <= b[0]:
            q = (t-a[0])/(b[0]-a[0]) if b[0] > a[0] else 0
            return [x+(y-x)*q for x, y in zip(a[1:], b[1:])]
    return keys[-1][1:]


def posed(model, M, idx):
    V = np.array(model['vertices']).reshape(-1, 8)[:, :3][idx]; W = np.array(model['weights'])[idx]
    hv = np.c_[V, np.ones(len(V))]; a, b, q = W[:, 0].astype(int), W[:, 1].astype(int), W[:, 2:3]
    return (np.einsum('nij,nj->ni', M[a], hv)*(1-q)+np.einsum('nij,nj->ni', M[b], hv)*q)[:, :3]


def tip_track(model, samples, all_tracks, palette):
    """The tuft end per sample, model units, as the game poses the exported clip."""
    end = Chain(model).end
    return [[round(t, 8), *[round(float(x), 5) for x in posed(model, palette(model, all_tracks, t), [end])[0]]] for t in samples]


# ---- the gate ---------------------------------------------------------------------------------------------------------

def _inward(poly):
    tri = poly.reshape(-1, 3, 3); n = np.cross(tri[:, 1]-tri[:, 0], tri[:, 2]-tri[:, 0]); ln = np.linalg.norm(n, axis=1); ok = ln > 1e-9
    n = n[ok]/ln[ok, None]; o = tri[ok, 0]; n = n*np.sign(((poly.mean(0)-o)*n).sum(1))[:, None]
    return n, o


def depth(points, poly):
    """How deep each point lies inside the convex part `poly` (its triangles), negative outside."""
    n, o = _inward(poly); return (((points[:, None, :]-o[None])*n[None]).sum(-1)).min(1)


def _closest(points, tris):
    sys.path.insert(0, str(ROOT/'standards/yukkuri-library/body-standard/features/marisa-single-braid'))
    import check_runtime_clearance
    return check_runtime_clearance.closest(points, tris)


def cylinder(feet, r, h, n=10):
    a = np.linspace(0, 2*math.pi, n, endpoint=False)
    ring = np.c_[np.cos(a)*r, np.zeros(n), np.sin(a)*r]
    return np.vstack([feet+ring+[0, y, 0] for y in (0, h/2, h)]+[feet+[0, h, 0], feet+[0, h/2, 0]])


def gate(model, name, samples, all_tracks, tip, c, palette):
    """Worst braid-in-body, braid-in-hat, braid-below-floor, young-in-body and young-in-hat depths (model units) over the
    samples, with the time of each; raises when any passes TOUCH."""
    parts = {p['name']: p for p in model['parts']}; span = lambda p: list(range(p['start'], p['start']+p['count']))
    braid = [i for p in model['parts'] if p['name'].startswith('braid_lock_') for i in span(p)]
    body = parts['rounded_body_surface']; tri = np.array(span(body)).reshape(-1, 3)
    hats = [span(p) for p in model['parts'] if p['name'].startswith(HAT)]
    def skin(M):
        P = posed(model, M, list(range(len(model['weights'])))); t3 = P[tri]
        n = np.cross(t3[:, 1]-t3[:, 0], t3[:, 2]-t3[:, 0]); cc = t3.mean(1)
        return P, P[tri[:, ::-1] if (n*(cc-np.array([0, 1, 0])*cc[:, 1:2])).sum(-1).mean() < 0 else tri]
    P0, S0 = skin(palette(model, [], 0)); base = np.maximum(-_closest(P0[braid], S0), 0)
    m = c['markers']; ride = (m['lift_start'], m['place'])
    worst = {k: [-math.inf, 0.] for k in ('braid_body', 'braid_hat', 'braid_floor', 'young_body', 'young_hat')}
    def note(k, v, t):
        if v > worst[k][0]: worst[k] = [round(float(v), 3), round(t, 3)]
    for t, row in zip(samples, tip):
        P, S = skin(palette(model, all_tracks, t)); B = P[braid]
        note('braid_body', (-_closest(B, S)-base).max(), t)
        note('braid_hat', max(depth(B, P[h]).max() for h in hats), t)
        note('braid_floor', -B[:, 1].min(), t)
        if ride[0] <= t <= ride[1]:
            for r, h in YOUNG:
                Y = cylinder(np.array(row[1:]), r, h)
                note('young_body', -_closest(Y, S).min(), t)
                note('young_hat', max(depth(Y, P[q]).max() for q in hats), t)
    print(f'  {name}: '+', '.join(f'{k} {v[0]:+.2f}@{v[1]:.2f}' for k, v in worst.items()), flush=True)
    continuity(model, name, all_tracks)
    bad = {k: v for k, v in worst.items() if v[0] > TOUCH}
    if bad: raise ValueError(f'{model["id"]} {name}: braid or young passes into her body, hat or the floor {bad}')
    return worst


def continuity(model, name, tracks):
    """The braid chain's largest turn between two samples as the game draws it (each Euler channel lerped between the keys,
    degrees, with its bone and time) and its whole turns: a channel stepping half a turn or more between two samples, or
    starting or ending the clip past half a turn (carried into whatever the game blends next). Raises past STEP or on any."""
    chain = braid_chain.chain({b['name']: i for i, b in enumerate(model['bones'])})
    worst, turns = [0., 0., ''], []
    for tr in tracks:
        if tr['channel'] != 'rotation' or tr['bone'] not in chain: continue
        bone, keys = model['bones'][tr['bone']]['name'], tr['keys']
        turns += [f'{bone}@{k[0]:.2f} ends at {k[1:]}' for k in (keys[0], keys[-1]) if max(map(abs, k[1:])) > 180]
        for a, b in zip(keys, keys[1:]):
            x, y = np.array(a[1:]), np.array(b[1:])
            if abs(y-x).max() >= 180: turns.append(f'{bone}@{a[0]:.2f} steps {np.round(y-x, 1).tolist()}')
            R = [braid_chain.euler(x+(y-x)*q) for q in np.linspace(0, 1, 9)]
            swept = sum(math.degrees(math.acos(max(-1., min(1., (np.trace(P.T@Q)-1)/2)))) for P, Q in zip(R, R[1:]))
            if swept > worst[0]: worst = [round(swept, 1), round(a[0], 3), bone]
    print(f'  {name}: braid joint step {worst[0]:.1f} deg@{worst[1]:.2f} ({worst[2]}), whole turns {len(turns)}', flush=True)
    if worst[0] > STEP or turns:
        raise ValueError(f'{model["id"]} {name}: braid joint turns {worst[0]} degrees at {worst[1]:.3f}s ({worst[2]}) in one sample (> {STEP:g}) or whole turns {turns[:4]}')
    return worst


if __name__ == '__main__':
    import argparse
    ap = argparse.ArgumentParser(description='Gate the exported braid-key clips and the braid continuity of every braid_* clip (no re-authoring).')
    ap.add_argument('--check', action='store_true')
    ap.parse_args()
    sys.path.insert(0, str(HERE)); import aquatic_tools as A
    model = A.runtime_model(); names = {b['name']: i for i, b in enumerate(model['bones'])}
    rev = json.loads((ROOT/'src/main/resources/assets/mcyukkuri/yukkuri_presentation/motion_corrections.json').read_text(encoding='utf-8'))['models']['marisa']
    keyed = [name for name, c in RECIPE['clips'].items() if authored(c)]
    failed = []
    for name in keyed+sorted(n for n in rev['clips'] if n.startswith('braid_') and n not in keyed):
        clip = rev['clips'][name]
        tr = [dict(t, bone=names[rev['bone_names'][t['bone']]]) for t in clip['tracks'] if rev['bone_names'][t['bone']] in names]
        try:
            if name in keyed: gate(model, name, [k[0] for k in clip['tip']], tr, clip['tip'], dict(RECIPE['clips'][name], markers=clip['markers']), A.palette)
            else: continuity(model, name, tr)
        except ValueError as error:
            print(f'  FAIL {error}', flush=True); failed.append(name)
    print(f'braid_keys: {"FAIL "+", ".join(failed) if failed else "PASS"}', flush=True)
    sys.exit(1 if failed else 0)
