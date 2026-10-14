"""Numeric checks of the single-braid choreography; writes checks/motion-checks.json.

Samples build.state() at the recipe rate. Checks braid stretch, contact accuracy, frame
to frame rotation steps, hat clearance above the head while carried, and the end state.
No browser, renderer or game is started; this does not replace viewing the animation.
"""
from pathlib import Path
import datetime, hashlib, json, math, sys

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import build

def matrix(r):
    """Rotation as composed by runtime/motion.js: Rz*Ry*Rx, degrees."""
    x, y, z = [math.radians(v) for v in r]
    cx, sx, cy, sy, cz, sz = math.cos(x), math.sin(x), math.cos(y), math.sin(y), math.cos(z), math.sin(z)
    return [[cz*cy, cz*sy*sx-sz*cx, cz*sy*cx+sz*sx], [sz*cy, sz*sy*sx+cz*cx, sz*sy*cx-cz*sx], [-sy, cy*sx, cy*cx]]

def turn(a, b):
    """Actual angle between two orientations; Euler components can jump near the poles."""
    A, B = matrix(a), matrix(b)
    trace = sum(A[k][r]*B[k][r] for r in range(3) for k in range(3))
    return math.degrees(math.acos(max(-1, min(1, (trace-1)/2))))

HEAD_TOP = build.bounds(lambda n: n in ('hair_cap', 'crown') or n.startswith('bang_'))[1][1]
rate = build.recipes['sample_rate']; report = {}; failures = []
for action, recipe in build.recipes['actions'].items():
    steps = round(recipe['duration']*rate); previous = None
    stretch = [9, 0]; worst_step = 0; contact_error = 0; clearance = math.inf
    for i in range(steps+1):
        t = round(i/rate, 6); pose, meta = build.state(action, t)
        for k in range(build.SEGMENTS):
            s = pose[f'braid_{k}']['scale'][1]; stretch = [min(stretch[0], s), max(stretch[1], s)]
        rotations = {n: v.get('rotation', [0, 0, 0]) for n, v in pose.items()}
        if previous:
            for n, r in rotations.items():
                if n in previous and n.startswith(('braid_', 'hat')):
                    worst_step = max(worst_step, turn(previous[n], r))
        previous = rotations
        grip = build.add(build.REST_END, pose['grip']['position'])
        contact_error = max(contact_error, build.length(build.sub(grip, meta['target'])))
        if action in ('remove_hat', 'care_hat'):
            pos, rot = build.hat_pose(action, t); release = recipe['release_time']
            if 2.7 <= t <= release-1.2:
                # Lowest brim corner while the hat is off the head and near it.
                corners = [[x*s, build.HAT_BOTTOM, z*s] for x in (-9.2, 9.2) for z in (-6.9, 6.8) for s in (1, .6)]
                points = [build.hat_point(c, pos, rot) for c in corners]
                near = [p for p in points if abs(p[0]) < 7 and -5.5 < p[2] < 7]
                if near: clearance = min(clearance, min(p[1] for p in near)-HEAD_TOP)
    end_pose, end_meta = build.state(action, recipe['duration'])
    rest = build.length(build.sub(build.add(build.REST_END, end_pose['grip']['position']), build.REST_END))
    result = dict(duration=recipe['duration'], samples=steps+1, stretch_min=round(stretch[0], 3), stretch_max=round(stretch[1], 3),
                  max_rotation_step_degrees=round(worst_step, 2), max_grip_target_error=round(contact_error, 6),
                  braid_end_from_rest=round(rest, 4))
    if action in ('remove_hat', 'care_hat'):
        pos, rot = build.hat_pose(action, recipe['duration'])
        result.update(hat_end_offset=round(build.length(pos)+build.length(rot), 4), min_brim_clearance_over_head=round(clearance, 3))
        if result['hat_end_offset'] > 1e-6: failures.append(action+': hat not back on the head')
        if clearance < .3: failures.append(action+': hat passes too close to the head')
    if action == 'care_hat':
        result['dust_cleared'] = all(end_pose[f'hat_dust_{i}']['scale'][0] < 1e-3 for i in range(4))
        if not result['dust_cleared']: failures.append('care_hat: dust left on the band')
    if action == 'lift_child':
        result['child_end'] = [round(v, 3) for v in end_pose['child']['position']]
        if abs(end_pose['child']['position'][1]-build.SHELF_Y) > 1e-6: failures.append('lift_child: child not on the brim shelf')
    # v1 (approved) already shortened the braid axially to about 0.3 at the hat grip.
    if not .28 <= stretch[0] or stretch[1] > 1.6: failures.append(f'{action}: braid stretch {stretch}')
    if worst_step > 15: failures.append(f'{action}: rotation step {worst_step:.1f} degrees per sample')
    if contact_error > 1e-6: failures.append(f'{action}: grip leaves its target')
    if rest > 1e-6: failures.append(f'{action}: braid does not return to rest')
    report[action] = result
source = {p: hashlib.sha256((HERE/p).read_bytes()).hexdigest() for p in ('build.py', 'recipes.json')}
base = build.LIB/'assembled/marisa/marisa.bbmodel'
out = dict(created_at=datetime.datetime.now(datetime.timezone.utc).isoformat(timespec='seconds'), environment='offline Python sampling; no browser or game',
           sources=source, base_model_sha256=hashlib.sha256(base.read_bytes()).hexdigest(), head_top=round(HEAD_TOP, 3),
           status='passed' if not failures else 'failed', failures=failures, actions=report)
(HERE/'checks').mkdir(exist_ok=True)
(HERE/'checks/motion-checks.json').write_text(json.dumps(out, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
print(json.dumps(dict(status=out['status'], failures=failures), ensure_ascii=False))
for a, r in report.items(): print(a, r)
