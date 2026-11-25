"""Read-only face registry check: standards/yukkuri-library/expressions/registry.json against presets, approval, runtime
textures, the generated face library and every emitter (Java literals, moments, clip spans, hair-wound faces).

    python scripts/check_face_registry.py
"""
import json
import re
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
LIB = ROOT/'standards/yukkuri-library'
ASSETS = ROOT/'src/main/resources/assets/mcyukkuri'
NAME = re.compile(r'^[a-z][a-z0-9]*(_[a-z0-9]+)*$')
# N-01: model names and age words never appear in a face id; one legacy id is allowed.
FORBIDDEN = {'reimu', 'marisa', 'alice', 'chen', 'flandre', 'remilia', 'patchouli', 'myon', 'adult', 'child', 'young', 'baby'}
LEGACY = {'alice_agitated'}
# Atlases of the remains entity, not expressions.
NOT_FACES = {'remains_retained', 'remains_stem_fruit'}


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def problems(usage=None):
    """Every violation as a string; usage is face_usage.emitted_faces() (needs the project JDK), None skips the emitter rules."""
    registry = read(LIB/'expressions/registry.json')['faces']
    approved = {e['id'] for e in read(LIB/'provenance/standard-expressions.json')['entries']}
    presets = read(LIB/'expressions/index.json')['presets']
    library = read(ASSETS/'yukkuri_presentation/face_library.json')
    found = []
    standard = {i for i, row in registry.items() if row['kind'] == 'standard'}
    for label, ids in (('approved entries', approved), ('index presets', set(presets))):
        for i in sorted(standard ^ ids): found.append(f'registry standard faces and {label} differ: {i}')
    for i in sorted(standard):
        if not (LIB/f'expressions/presets/{i}.json').is_file(): found.append(f'{i}: no preset file')
        if not (LIB/f'expressions/thumbnails/{i}.png').is_file(): found.append(f'{i}: no thumbnail')
    on_disk = {p.stem for p in (LIB/'expressions/presets').glob('*.json')}
    for i in sorted(on_disk - standard): found.append(f'{i}: preset file without registry row')
    for i in sorted(set(read(LIB/'expressions/index.json')) & {'aliases', 'effects', 'effect_aliases'}): found.append(f'index.json still has {i}')
    if library.get('aliases') or any(m.get('face_aliases') for m in library['models'].values()): found.append('face library still carries aliases')
    for model, row in library['models'].items():
        expected = sorted(i for i, r in registry.items() if model in r.get('models', [model]))
        if row['selectable'] != expected:
            found.append(f'{model}: selectable differs from the registry: {sorted(set(row["selectable"]) ^ set(expected))}')
        for i in row['selectable']:
            if not (ASSETS/'textures/entity'/model/f'{i}.png').is_file(): found.append(f'{model}/{i}: no runtime texture')
        for path in (ASSETS/'textures/entity'/model).glob('*.png'):
            if path.stem not in registry and path.stem not in NOT_FACES: found.append(f'{model}/{path.stem}: texture without registry row')
    for i, row in registry.items():
        if not NAME.match(i) or '__' in i: found.append(f'{i}: bad id')
        if (set(i.split('_')) & FORBIDDEN) and i not in LEGACY: found.append(f'{i}: forbidden word in id')
        if row.get('frame_of'):
            a = registry.get(row['frame_of'])
            if not i.endswith('_b') or a is None or 'frame_ticks' not in a: found.append(f'{i}: frame_of needs an A frame with frame_ticks')
        if 'frame_ticks' in row and (i + '_b') not in registry: found.append(f'{i}: frame_ticks without a _b frame')
        if i.endswith('_b') and 'frame_of' not in row: found.append(f'{i}: _b face without frame_of')
    if usage is not None:
        faces = set(registry)
        hair = {f for grades in read(ASSETS/'yukkuri_presentation/hair_wounds.json')['dry_faces'].values() for ids in grades.values() for f in ids}
        for i in sorted(hair - faces): found.append(f'hair_wounds dry face not registered: {i}')
        for i, sources in sorted(usage.items()):
            if i not in faces: found.append(f'emitted but not registered: {i} ({sorted(sources)[0]})')
            elif 'frame_of' in registry[i]: found.append(f'{i}: a B frame is emitted directly ({sorted(sources)[0]})')
        for i, row in sorted(registry.items()):
            if i not in usage and 'frame_of' not in row and i not in hair and not row.get('reserved'):
                found.append(f'{i}: no emitter and no reserved reason')
    return found


def main():
    from face_usage import emitted_faces
    found = problems(emitted_faces())
    for line in found: print(line)
    print('Face registry: ' + ('FAILED, ' + str(len(found)) + ' problems' if found else 'consistent'))
    raise SystemExit(1 if found else 0)


if __name__ == '__main__':
    import sys
    sys.path.insert(0, str(Path(__file__).resolve().parent))
    main()
