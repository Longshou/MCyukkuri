"""Measure braid and loose-lock clearance in the game Marisa models; writes a JSON report.

Poses each runtime model (src/main/resources/.../yukkuri/<id>.json) with its two-bone
weights, overlaying the published motion corrections for the clips they replace, and
measures for every clip how far the braid and loose lock go into the posed body skin
beyond their rest contact, and below the floor (y=0). Placement fit is not applied; it
only tucks the lock tops under the hat. Needs numpy. No game, browser or build runs.

  python check_runtime_clearance.py [model ...] [--out report.json] [--corrections motion_corrections.json]
"""
from pathlib import Path
import json, math, sys
import numpy as np

ROOT = Path(__file__).resolve().parents[5]
ASSETS = ROOT/'src/main/resources/assets/mcyukkuri'
BODY = ('rounded_body_surface', 'house_body_surface', 'deficient_body_surface')
CORRECTIONS = ASSETS/'yukkuri_presentation/motion_corrections.json'
MODELS = ('marisa', 'marisa_tsumuri', 'marisa_sparse', 'marisa_face', 'marisa_bean', 'house_marisa')

def euler(r):
    x, y, z = [math.radians(v) for v in r]
    cx, sx, cy, sy, cz, sz = math.cos(x), math.sin(x), math.cos(y), math.sin(y), math.cos(z), math.sin(z)
    return np.array([[cz*cy, cz*sy*sx-sz*cx, cz*sy*cx+sz*sx], [sz*cy, sz*sy*sx+cz*cx, sz*sy*cx-cz*sx], [-sy, cy*sx, cy*cx]])

def sample(keys, t):
    if t <= keys[0][0]: return keys[0][1:]
    for a, b in zip(keys, keys[1:]):
        if t <= b[0]:
            q = (t-a[0])/(b[0]-a[0]) if b[0] > a[0] else 0
            return [x+(y-x)*q for x, y in zip(a[1:], b[1:])]
    return keys[-1][1:]

def worlds(model, clips, name, t):
    """Idle at t=0 under the clip, as the client layers them (motion.js / YukkuriModel)."""
    pose = {}
    for clip, at in ((clips['idle'], 0), (clips[name], t)):
        for tr in clip['tracks']:
            pose.setdefault(tr['bone'], {})[tr['channel']] = sample(tr['keys'], at)
    out = []
    for i, b in enumerate(model['bones']):
        p = pose.get(i, {}); m = np.eye(4); s = p.get('scale', [1, 1, 1])
        m[:3, :3] = euler(p.get('rotation', [0, 0, 0]))*np.array(s)
        pv = np.array(b['pivot']); m[:3, 3] = pv+np.array(p.get('position', [0, 0, 0]))-m[:3, :3]@pv
        out.append(out[b['parent']]@m if b['parent'] >= 0 else m)
    return np.array(out)

def posed(model, mats):
    v = np.array(model['vertices']).reshape(-1, 8)[:, :3]; w = np.array(model['weights'])
    a, b, q = w[:, 0].astype(int), w[:, 1].astype(int), w[:, 2:3]
    hv = np.c_[v, np.ones(len(v))]
    pa = np.einsum('nij,nj->ni', mats[a], hv)[:, :3]; pb = np.einsum('nij,nj->ni', mats[b], hv)[:, :3]
    return pa*(1-q)+pb*q

def closest(points, tris):
    """Signed distance of points to a triangle soup (negative inside, by face normal)."""
    a, b, c = tris[:, 0], tris[:, 1], tris[:, 2]; ab, ac = b-a, c-a
    n = np.cross(ab, ac); n /= np.linalg.norm(n, axis=1, keepdims=True)+1e-12
    best = np.full(len(points), np.inf); sign = np.ones(len(points))
    for i in range(0, len(points), 64):
        p = points[i:i+64, None, :]; ap = p-a
        d1, d2 = (ab*ap).sum(-1), (ac*ap).sum(-1)
        bp = p-b; d3, d4 = (ab*bp).sum(-1), (ac*bp).sum(-1)
        cp = p-c; d5, d6 = (ab*cp).sum(-1), (ac*cp).sum(-1)
        va, vb, vc = d3*d6-d5*d4, d5*d2-d1*d6, d1*d4-d3*d2
        denom = va+vb+vc; v = vb/np.where(denom == 0, 1, denom); w = vc/np.where(denom == 0, 1, denom)
        proj = a+ab*v[..., None]+ac*w[..., None]
        inside = (va >= 0) & (vb >= 0) & (vc >= 0)
        # Outside the face region: fall back to the nearest edge or vertex.
        cands = [proj]
        for s, e in ((a, b), (b, c), (c, a)):
            se = e-s; t = np.clip(((p-s)*se).sum(-1)/np.maximum((se*se).sum(-1), 1e-12), 0, 1)
            cands.append(s+se*t[..., None])
        dist = np.stack([np.linalg.norm(p-x, axis=-1) for x in cands])
        dist[0] = np.where(inside, dist[0], np.inf)
        k = dist.argmin(0); dmin = np.take_along_axis(dist, k[None], 0)[0]
        j = dmin.argmin(1); best[i:i+64] = dmin[np.arange(len(j)), j]
        nearest = np.stack([x for x in cands])[k[np.arange(len(j)), j], np.arange(len(j)), j]
        sign[i:i+64] = np.sign(((points[i:i+64]-nearest)*n[j]).sum(-1)+1e-9)
    return best*sign

def analyse(ident, rate=10):
    model = json.loads((ASSETS/'yukkuri'/f'{ident}.json').read_text(encoding='utf-8'))
    corrections = json.loads(Path(CORRECTIONS).read_text(encoding='utf-8'))['models'].get(ident, {})
    index = {b['name']: i for i, b in enumerate(model['bones'])}
    remap = lambda clip: dict(clip, tracks=[dict(tr, bone=index[corrections['bone_names'][tr['bone']]]) for tr in clip['tracks']
                                            if corrections['bone_names'][tr['bone']] in index])
    clips = {**model['clips'], **model.get('behavior_clips', {}), **{k: remap(v) for k, v in corrections.get('clips', {}).items()}}
    parts = {p['name']: p for p in model['parts']}
    body = next(parts[n] for n in BODY if n in parts)
    span = lambda p: range(p['start'], p['start']+p['count'])
    braid = [i for p in model['parts'] if p['name'].startswith('braid_lock_') for i in span(p)]
    loose = [i for p in model['parts'] if p['name'].startswith(('side_hair_loose', 'bean_hair_loose')) for i in span(p)]
    tri = np.array(list(span(body))).reshape(-1, 3)
    rest = posed(model, worlds(model, clips, 'idle', 0))
    # Orient body normals outward: centroids should face away from the vertical axis.
    t3 = rest[tri]; n = np.cross(t3[:, 1]-t3[:, 0], t3[:, 2]-t3[:, 0]); c = t3.mean(1)
    flip = (n*(c-np.array([0, 1, 0])*c[:, 1:2])).sum(-1).mean() < 0
    tri_o = tri[:, ::-1] if flip else tri
    base = {k: -closest(rest[idx], rest[tri_o]) for k, idx in (('braid', braid), ('loose', loose)) if idx}
    report = {}
    for name, clip in clips.items():
        if name.startswith('anatomy_'): continue
        steps = max(1, int(clip['duration']*rate)); worst = {}
        for s in range(steps+1):
            t = clip['duration']*s/steps; pts = posed(model, worlds(model, clips, name, t))
            for k, idx in (('braid', braid), ('loose', loose)):
                if not idx: continue
                depth = np.maximum(-closest(pts[idx], pts[tri_o])-np.maximum(base[k], 0), 0).max()
                floor = max(0.0, -float(pts[idx][:, 1].min()))
                w = worst.setdefault(k, dict(extra_depth=0.0, at=0.0, below_floor=0.0, floor_at=0.0))
                if depth > w['extra_depth']: w.update(extra_depth=round(float(depth), 3), at=round(t, 2))
                if floor > w['below_floor']: w.update(below_floor=round(floor, 3), floor_at=round(t, 2))
        report[name] = worst
    return dict(rest_contact={k: round(float(np.maximum(v, 0).max()), 3) for k, v in base.items()}, clips=report)

if __name__ == '__main__':
    args = [a for a in sys.argv[1:] if not a.startswith('--')]
    out = sys.argv[sys.argv.index('--out')+1] if '--out' in sys.argv else None
    if out in args: args.remove(out)
    if '--corrections' in sys.argv:
        CORRECTIONS = sys.argv[sys.argv.index('--corrections')+1]; args.remove(CORRECTIONS)
    result = {m: analyse(m) for m in (args or MODELS)}
    for m, r in result.items():
        bad = {c: {k: (v['extra_depth'], v['at'], v['below_floor']) for k, v in w.items() if v['extra_depth'] > .15 or v['below_floor'] > .05} for c, w in r['clips'].items()}
        print(m, 'rest', r['rest_contact'], {c: v for c, v in bad.items() if v})
    if out: Path(out).write_text(json.dumps(result, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
