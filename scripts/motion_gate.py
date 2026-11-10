"""Hard authored-body checks on exported runtime tracks (no re-authoring)."""
import importlib.util
import math

from export_registry import load

# standards/yukkuri-library/AGENTS.md: the whole body's pitch and roll together stay about 12 degrees.
# PoseChecks.tilt applies it to the composed root up axis and lets a clip override it through
# parameters.tilt_max with a written tilt_why (the toilet strain clip alone does).
TILT_MAX = 12


def exported(root, model, name):
    assets = root/'src/main/resources/assets/mcyukkuri'
    data = load(assets/'yukkuri'/f'{model}.json')
    names = [b['name'] for b in data['bones']]
    clips = dict(data['clips'])
    # Same clip revision order as OfflineModel; placement_fit changes geometry only.
    for file in ('motion_corrections', 'house_ecology', 'toilet_contact', 'face_library'):
        revision = load(assets/'yukkuri_presentation'/f'{file}.json')['models'].get(model)
        if revision is not None:
            if revision['bone_names'] != names:
                raise ValueError(f'incompatible {file} for {model}')
            clips.update(revision['clips'])
    clips.update(data.get('behavior_clips', {}))
    return data, clips[name], clips['idle']


def body_gate(root, model, name):
    data, clip, idle = exported(root, model, name)
    return check_body(root, model, name, data, clip, idle)


def check_body(root, model, name, data, clip, idle):
    root_bone = next(i for i, b in enumerate(data['bones']) if b['name'] == 'root')
    tracks = [t for t in clip['tracks'] if t['bone'] == root_bone and t['channel'] == 'rotation']
    defaults = [t for t in idle['tracks'] if t['bone'] == root_bone and t['channel'] == 'rotation']
    keys = tracks[-1]['keys'] if tracks else defaults[-1]['keys'][:1] if defaults else [[0, 0, 0, 0]]
    # Clips.Track linearly interpolates Euler channels; Compose applies them as Rz*Ry*Rx, so the
    # visible lean is that composed up axis' angle to the world's. Summing the channels is wrong
    # once yaw is large: a yawed pose can carry big pitch/roll channels and still lean little.
    def rotation(t):
        a, b = keys[0], keys[-1]
        for x, y in zip(keys, keys[1:]):
            if t <= y[0]:
                a, b = x, y
                break
        q = 0 if a[0] == b[0] else min(1, max(0, (t-a[0])/(b[0]-a[0])))
        return [x+(y-x)*q for x, y in zip(a[1:], b[1:])]

    def tilt(t):
        x, y, z = (math.radians(v) for v in rotation(t))
        return math.degrees(math.acos(max(-1., min(1., math.cos(z)*math.cos(x)+math.sin(z)*math.sin(y)*math.sin(x)))))

    # Sample every key span like braid_keys.continuity: the composed angle is not convex, so a
    # yaw swing between two keys can lean further than either key.
    times = {0., clip['duration']}
    for a, b in zip(keys, keys[1:]):
        times.update(t for t in (a[0]+(b[0]-a[0])*i/8 for i in range(9)) if 0 <= t <= clip['duration'])
    params = clip.get('parameters', {})
    limit = params.get('tilt_max', TILT_MAX)
    if 'tilt_max' in params and 'tilt_why' not in params:
        raise ValueError(f'{name}: tilt_max requires tilt_why')
    failures = []
    for t in sorted(times):
        value = tilt(t)
        if value > limit:
            pitch, yaw, roll = rotation(t)
            failures.append(f'{name}/{model} t={t:g}s tilt={value:.2f} deg > {limit:g} (pitch={pitch:g}, yaw={yaw:g}, roll={roll:g})')
    print(f'{name}/{model}: whole-clip tilt {"FAIL" if failures else "PASS"} ({len(times)} samples, limit {limit:g} deg)', flush=True)
    if model == 'marisa' and name.startswith('braid_') and any(b['name'] == 'hand_right_braid_1' for b in data['bones']):
        path = root/'standards/yukkuri-library/motions/variants/lore/braid_keys.py'
        spec = importlib.util.spec_from_file_location('motion_braid_keys', path)
        checker = importlib.util.module_from_spec(spec)
        spec.loader.exec_module(checker)
        try:
            checker.continuity(data, name, clip['tracks'])
        except ValueError as error:
            failures.append(f'{name}/{model}: continuity FAIL {error}')
    else:
        print(f'{name}/{model}: continuity SKIPPED (requires Marisa braid_* clip with braid bones)', flush=True)
    for failure in failures:
        print('FAIL '+failure, flush=True)
    return bool(failures)
