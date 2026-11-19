"""Hard authored-body checks on exported runtime tracks (no re-authoring)."""
import importlib.util
import math

from export_registry import load

# standards/yukkuri-library/AGENTS.md: the whole body's pitch and roll together stay about 12 degrees.
# PoseChecks.tilt applies it to the composed root up axis; a clip that leans further on purpose declares
# parameters.tilt_max with a one-line tilt_why in its authoring source, and the exporters carry both into runtime.
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
    # The whole body leans through root and body_motion (the rig's spine below every body part);
    # a lean authored on either one is what the budget limits.
    spine = [i for i, b in enumerate(data['bones']) if b['name'] in ('root', 'body_motion')]

    def rotation_keys(bone):
        tracks = [t for t in clip['tracks'] if t['bone'] == bone and t['channel'] == 'rotation']
        defaults = [t for t in idle['tracks'] if t['bone'] == bone and t['channel'] == 'rotation']
        return tracks[-1]['keys'] if tracks else defaults[-1]['keys'][:1] if defaults else [[0, 0, 0, 0]]

    keys = {bone: rotation_keys(bone) for bone in spine}

    # Clips.Track linearly interpolates Euler channels; Compose applies them as Rz*Ry*Rx, so the
    # visible lean is that composed up axis' angle to the world's. Summing the channels is wrong
    # once yaw is large: a yawed pose can carry big pitch/roll channels and still lean little.
    def rotation(bone, t):
        samples = keys[bone]
        a, b = samples[0], samples[-1]
        for x, y in zip(samples, samples[1:]):
            if t <= y[0]:
                a, b = x, y
                break
        q = 0 if a[0] == b[0] else min(1, max(0, (t-a[0])/(b[0]-a[0])))
        return [x+(y-x)*q for x, y in zip(a[1:], b[1:])]

    def matrix(angles):
        x, y, z = (math.radians(v) for v in angles)
        cx, sx, cy, sy, cz, sz = math.cos(x), math.sin(x), math.cos(y), math.sin(y), math.cos(z), math.sin(z)
        return [[cz*cy, cz*sy*sx-sz*cx, cz*sy*cx+sz*sx],
                [sz*cy, sz*sy*sx+cz*cx, sz*sy*cx-cz*sx],
                [-sy, cy*sx, cy*cx]]

    def composed(t):
        m = [[1., 0., 0.], [0., 1., 0.], [0., 0., 1.]]
        for bone in spine:
            n = matrix(rotation(bone, t))
            m = [[sum(m[i][k]*n[k][j] for k in range(3)) for j in range(3)] for i in range(3)]
        return m

    def tilt(t):
        return math.degrees(math.acos(max(-1., min(1., composed(t)[1][1]))))

    # Sample every key span like braid_keys.continuity: the composed angle is not convex, so a
    # yaw swing between two keys can lean further than either key.
    times = {0., clip['duration']}
    for samples in keys.values():
        for a, b in zip(samples, samples[1:]):
            times.update(t for t in (a[0]+(b[0]-a[0])*i/8 for i in range(9)) if 0 <= t <= clip['duration'])
    params = clip.get('parameters', {})
    limit = params.get('tilt_max', TILT_MAX)
    if 'tilt_max' in params and 'tilt_why' not in params:
        raise ValueError(f'{name}: tilt_max requires tilt_why')
    failures = []
    for t in sorted(times):
        value = tilt(t)
        if value > limit and not math.isclose(value, limit, rel_tol=0, abs_tol=1e-9):
            lean = '; '.join(f'{data["bones"][bone]["name"]} pitch={rotation(bone, t)[0]:g}, '
                             f'yaw={rotation(bone, t)[1]:g}, roll={rotation(bone, t)[2]:g}' for bone in spine)
            failures.append(f'{name}/{model} t={t:g}s tilt={value:.2f} deg > {limit:g} ({lean})')
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
