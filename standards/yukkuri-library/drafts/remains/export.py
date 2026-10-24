"""Select the authored remains art for the game.

Copies assembled/ atlases, overlays and decals to the art/source/remains snapshot
and the runtime textures, writes yukkuri_presentation/remains_forms.json from
recipe.json and registers the pack in art/runtime-sources.json. --check is read-only.
"""
import argparse
import hashlib
import json
from pathlib import Path

HERE = Path(__file__).resolve().parent
LIB = HERE.parents[1]
ROOT = LIB.parents[1]
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
SNAPSHOT = ROOT / 'art/source/remains'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def rel(path):
    return path.relative_to(ROOT).as_posix()


def outputs():
    recipe = json.loads((HERE / 'recipe.json').read_text(encoding='utf-8'))
    built = json.loads((HERE / 'assembled/manifest.json').read_text(encoding='utf-8'))
    assert built['recipe_sha256'] == sha((HERE / 'recipe.json').read_bytes()), 'Run build.py: recipe changed since the last build'
    files, records = {}, {}
    for ident, atlases in built['atlases'].items():
        for name, record in atlases.items():
            data = (HERE / 'assembled' / ident / f'remains_{name}.png').read_bytes()
            assert sha(data) == record['sha256'], (ident, name)
            runtime = ASSETS / 'textures/entity' / ident / f'remains_{name}.png'
            snapshot = SNAPSHOT / 'textures' / ident / f'remains_{name}.png'
            files[runtime] = files[snapshot] = data
            records[rel(runtime)] = {'source': rel(HERE / 'assembled' / ident / f'remains_{name}.png'), 'face': record['face'],
                                     'face_source': record['face_source'], 'sha256': record['sha256']}
    for name, digest in built['shared'].items():
        data = (HERE / 'assembled/shared' / f'{name}.png').read_bytes()
        assert sha(data) == digest, name
        runtime = ASSETS / 'textures/entity/remains' / f'{name}.png'
        files[runtime] = files[SNAPSHOT / 'shared' / f'{name}.png'] = data
        records[rel(runtime)] = {'source': rel(HERE / 'assembled/shared' / f'{name}.png'), 'sha256': digest}

    def entry(spec):
        result = {'scale': spec['scale']}
        for key in ('face', 'roll', 'overlay'):
            if key in spec:
                result[key] = spec[key]
        if 'decal' in spec:
            result['decal'] = spec['decal']
        return result

    runtime = {'format': 1,
               'forms': {name: entry(spec) for name, spec in recipe['forms'].items()},
               'water': {name: entry(spec) for name, spec in recipe['water'].items()},
               'atlases': {ident: sorted(atlases) for ident, atlases in built['atlases'].items()}}
    runtime_path = ASSETS / 'yukkuri_presentation/remains_forms.json'
    files[runtime_path] = (json.dumps(runtime, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    manifest = {'status': 'selected_for_runtime_pending_user_review', 'source': rel(HERE), 'exporter': rel(Path(__file__)),
                'recipe_sha256': built['recipe_sha256'], 'runtime_profile': rel(runtime_path), 'runtime_profile_sha256': sha(files[runtime_path]),
                'files': records}
    files[SNAPSHOT / 'manifest.json'] = (json.dumps(manifest, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    return files


def ledger(check):
    path = ROOT / 'art/runtime-sources.json'
    data = json.loads(path.read_text(encoding='utf-8'))
    data['remains_art'] = {
        'source': rel(HERE), 'exporter': rel(Path(__file__)), 'selected_snapshot': rel(SNAPSHOT), 'manifest': rel(SNAPSHOT / 'manifest.json'),
        'runtime_profile': 'src/main/resources/assets/mcyukkuri/yukkuri_presentation/remains_forms.json',
        'runtime_textures': 'src/main/resources/assets/mcyukkuri/textures/entity/{model}/remains_{form}.png + textures/entity/remains/{overlay,decal}_*.png',
        'status': 'selected_for_runtime_pending_user_review',
        'scope': 'Eight death-cause forms and the dissolving residue for the nine rounded runtime bodies: accepted standard dead faces '
                 '(N068/N044/N027/N045/N026/N069/N060/N070) on each body atlas, skin/hair/accessory recolouring, cylindrical skin overlays and '
                 'two ground decals. House and rare forms keep the sleep face with the same pose and overlays. Deibu bodies use the ordinary '
                 'reimu dead faces without the mature-face treatment. No user visual acceptance yet.'}
    text = json.dumps(data, ensure_ascii=False, indent=2) + '\n'
    if check:
        assert path.read_bytes() == text.encode('utf-8'), 'Ledger entry outdated: art/runtime-sources.json'
    else:
        path.write_bytes(text.encode('utf-8'))


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    files = outputs()
    for path, data in files.items():
        if args.check:
            assert path.is_file() and path.read_bytes() == data, 'Outdated remains export: ' + str(path)
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
    ledger(args.check)
    print(f'{len(files)} remains runtime files ' + ('verified' if args.check else 'exported'))


if __name__ == '__main__':
    main()
