"""Hard authored-body checks on exported runtime tracks (no re-authoring)."""
import importlib.util

from export_registry import load

# standards/yukkuri-library/AGENTS.md: whole-head |pitch| + |roll|, including peaks.
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
    # Clips.Track linearly interpolates Euler channels. The absolute sum is convex
    # on every span, so testing all keys (plus clipped endpoints) bounds the entire
    # clip, including the left limit of a looping endpoint, without a sampling gap.
    def rotation(t):
        a, b = keys[0], keys[-1]
        for x, y in zip(keys, keys[1:]):
            if t <= y[0]:
                a, b = x, y
                break
        q = 0 if a[0] == b[0] else min(1, max(0, (t-a[0])/(b[0]-a[0])))
        return [x+(y-x)*q for x, y in zip(a[1:], b[1:])]
    times = sorted({0, clip['duration'], *(k[0] for k in keys if 0 <= k[0] <= clip['duration'])})
    failures = []
    for t in times:
        pitch, _, roll = rotation(t)
        value = abs(pitch)+abs(roll)
        if value > TILT_MAX:
            failures.append(f'{name}/{model} t={t:g}s tilt_sum={value:g} deg > {TILT_MAX} (pitch={pitch:g}, roll={roll:g})')
    print(f'{name}/{model}: whole-clip tilt_sum {"FAIL" if failures else "PASS"} ({len(times)} extrema/endpoints)', flush=True)
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
