"""Measure interpenetration in the single-braid interactions; writes reports/current/clearance.json (local, not committed).

Sampled with build.state() and the runtime/motion.js bone convention (see render_checks).
Volumes: the rounded body from body-standard/profile.json (the same 12-sided rings the
mesh is built from, plus the hair cap thickness above the brows), and every hat cuboid in
its current pose. Points sample each part's corners, edge midpoints and face centres.
Depths are in model units (16 per block); the braid root's attachment is excluded.
"""
from pathlib import Path
import json, math, sys

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import build
from render_checks import matrix, compose

PROFILE = json.loads((build.LIB/'body-standard/profile.json').read_text(encoding='utf-8'))['body_profile']
HAIR = .55  # hair cap and bangs over the scalp, above the brows

def ring(y, grow=0):
    y = max(PROFILE[0][0], min(PROFILE[-1][0], y))
    for a, b in zip(PROFILE, PROFILE[1:]):
        if y <= b[0]:
            t = (y-a[0])/(b[0]-a[0]); x, z, f = [a[k]+(b[k]-a[k])*t+grow for k in range(1, 4)]; break
    return [[x, 0], [.94*x, .60*z], [.70*x, .91*z], [.35*x, z], [-.35*x, z], [-.70*x, .91*z], [-.94*x, .60*z], [-x, 0],
            [-.95*x, -.68*f], [-.85*x, -f], [.85*x, -f], [.95*x, -.68*f]]

def head_depth(p):
    """Positive depth inside the body (and hair above y=8), else negative distance."""
    x, y, z = p; top = PROFILE[-1][0]+(HAIR if y > 8 else 0)
    if y < 0 or y > top: return -min(abs(y), abs(y-top))
    poly = ring(y, HAIR*max(0, min(1, (y-8)/1.2)))
    inside = False; best = math.inf
    for (ax, az), (bx, bz) in zip(poly, poly[1:]+poly[:1]):
        if (az > z) != (bz > z) and x < ax+(z-az)*(bx-ax)/(bz-az): inside = not inside
        dx, dz = bx-ax, bz-az; t = max(0, min(1, ((x-ax)*dx+(z-az)*dz)/(dx*dx+dz*dz)))
        best = min(best, math.hypot(x-ax-t*dx, z-az-t*dz))
    best = min(best, y, top-y)
    return best if inside else -best

def samples(e):
    lo, hi = e['from'], e['to']; pts = []
    for fx in (0, .5, 1):
        for fy in (0, .5, 1):
            for fz in (0, .5, 1):
                pts.append([lo[k]+(hi[k]-lo[k])*f for k, f in enumerate((fx, fy, fz))])
    if any(e.get('rotation', [0, 0, 0])):
        o = e['origin']; pts = [build.add(build.rotate(build.sub(p, o), [-e['rotation'][0], -e['rotation'][1], e['rotation'][2]]), o) for p in pts]
    return pts

def world_of(action, t):
    pose, meta = build.state(action, t); world = {}
    for bone in build.bones:
        m = matrix(bone, pose); world[bone['name']] = compose(world[bone['parent']], m) if bone['parent'] else m
    return world, meta

apply = lambda m, p: [sum(m[r][k]*p[k] for k in range(3))+m[r][3] for r in range(3)]
visible = lambda m: abs(m[0][0])+abs(m[1][1])+abs(m[2][2]) > 1e-3
PARTS = [(e, g, samples(e)) for e, g in zip(build.elements, build.groups) if e.get('type', 'cube') == 'cube']
HAT = [(e, g) for e, g in zip(build.elements, build.groups) if g in ('hat', 'hat_front', 'bow') and e.get('type', 'cube') == 'cube']
HEAD_PARTS = [(e, g, s) for e, g, s in PARTS if g in ('hat', 'hat_front', 'bow')]
BRAID = {f'braid_{i}' for i in range(build.SEGMENTS)} | {'braid_ribbon', 'braid_tip'}

def inside_box(p, e, m):
    """Depth of p inside a posed cuboid (inverse of a rigid transform with axis scale)."""
    cols = [[m[r][c] for r in range(3)] for c in range(3)]; d = build.sub(p, [m[r][3] for r in range(3)])
    local = [sum(d[r]*cols[c][r] for r in range(3))/max(1e-9, sum(v*v for v in cols[c])) for c in range(3)]
    if any(e.get('rotation', [0, 0, 0])):
        o = e['origin']; local = build.add(build.rotate(build.sub(local, o), [0, 0, -e['rotation'][2]]), o)
    return min(min(local[k]-e['from'][k], e['to'][k]-local[k]) for k in range(3))

def analyse(action, rate=10):
    duration = build.recipes['actions'][action]['duration']; result = dict(braid_body=0, braid_hat=0, hat_head=0, child=0, prop_body=0)
    worst = {k: None for k in result}
    for i in range(int(duration*rate)+1):
        t = round(i/rate, 3); world, meta = world_of(action, t)
        hat_on = world['hat']
        for e, g, pts in PARTS:
            m = world[g]
            if not visible(m): continue
            if g in BRAID:
                for p in pts:
                    q = apply(m, p)
                    if build.length(build.sub(q, build.ROOT)) < 1.6: continue
                    d = head_depth(q)
                    if d > result['braid_body']: result['braid_body'], worst['braid_body'] = d, (t, e['name'])
                    if meta.get('contact') != 'hat':
                        for he, hg in HAT:
                            hd = inside_box(q, he, world[hg])
                            if hd > result['braid_hat']: result['braid_hat'], worst['braid_hat'] = hd, (t, e['name'], he['name'])
            elif g in ('hat', 'hat_front', 'bow') and action in ('remove_hat', 'care_hat') and 1.9 < t < build.recipes['actions'][action]['release_time']-.4:
                for p in pts:
                    d = head_depth(apply(m, p))
                    if d > result['hat_head']: result['hat_head'], worst['hat_head'] = d, (t, e['name'])
            elif g == 'child' and visible(m):
                if e['name'].startswith('child_braid') or e['name'].startswith('child_side_hair'): continue
                for p in pts:
                    q = apply(m, p); d = max(head_depth(q), max(inside_box(q, he, world[hg]) for he, hg in HAT))
                    if d > result['child']: result['child'], worst['child'] = d, (t, e['name'])
            elif g in ('stone', 'sword', 'grass'):
                for p in pts:
                    d = head_depth(apply(m, p))
                    if d > result['prop_body']: result['prop_body'], worst['prop_body'] = d, (t, e['name'])
    return {k: dict(depth=round(v, 3), at=worst[k]) for k, v in result.items()}

if __name__ == '__main__':
    rest = analyse('wave_goodbye', rate=1)  # t=0 rest pose baseline included in each action
    report = {a: analyse(a) for a in (sys.argv[1:] or build.recipes['actions'])}
    for a, r in report.items(): print(a, {k: (v['depth'], v['at']) for k, v in r.items() if v['depth'] > 0})
    (HERE/'reports/current').mkdir(parents=True, exist_ok=True)
    (HERE/'reports/current/clearance.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
