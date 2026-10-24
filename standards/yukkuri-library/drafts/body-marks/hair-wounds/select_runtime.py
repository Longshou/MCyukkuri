"""Select the existing authored wound art; never redraw or regenerate source assets."""
from pathlib import Path
import argparse
import gzip
import hashlib
import json

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[4]
SNAPSHOT = ROOT / 'art/source/hair-wounds'
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'

def encoded(value):
    return (json.dumps(value, ensure_ascii=False, separators=(',', ':')) + '\n').encode('utf-8')

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    outputs = {}
    def select(path, data):
        outputs[path] = data
    def snapshot(path):
        relative = path.relative_to(HERE)
        data = path.read_bytes()
        if path.suffix in ('.json', '.cjs', '.py'):
            data = data.replace(b'\r\n', b'\n')
        select(SNAPSHOT / relative, data)
    recipe = json.loads((HERE / 'recipe.json').read_text(encoding='utf-8'))
    catalog = json.loads((HERE / 'species.json').read_text(encoding='utf-8'))
    profiles = {}
    input_meshes = {}
    for style in recipe['grades']:
        profiles[style] = {}
        for grade in (1, 2, 3):
            path = HERE / f'assembled/species/reimu/hair-{style}-{grade}-normal.json.gz'
            mesh = json.loads(gzip.decompress(path.read_bytes()))
            profiles[style][str(grade)] = {key: mesh['profile'][key] for key in
                ('radius', 'cutRadius', 'outline', 'hairOutline', 'open')}
            input_meshes[path.relative_to(ROOT).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
            select(SNAPSHOT / f'profiles/{style}-{grade}.json', encoded(mesh['profile']))
    # These are authored control rings, copied from author.cjs; the material/shape
    # generation remains solely in that source. They are selected, not redesigned.
    cavity_controls = [[.999,.012,194,158,115],[.98,.04,192,152,112],
        [.94,.15,179,134,97],[.72,.5,111,73,56],[.40,.85,85,51,43],[0,1,68,40,35]]
    core_controls = [[.999,.012,0,[194,158,115]],[.985,.05,0,[192,152,112]],
        [.97,.20,0,[179,134,97]],[.87,.78,.12,[103,69,53]],
        [.79,1.75,.34,[97,64,50]],[.72,3,.65,[91,60,47]],
        [.62,4.5,1,[82,54,42]],[0,5.7,1,[76,50,39]]]
    core_levels = []
    for index, (a, b) in enumerate(zip(core_controls, core_controls[1:])):
        for step in range(1 if index < 2 else 6):
            t = step / (1 if index < 2 else 6)
            row = [a[k]*(1-t)+b[k]*t for k in range(3)]
            blend = max(0, min(1, (row[1]-1.75)/2))
            color = [(a[3][k]*(1-t)+b[3][k]*t)*(1-blend)+recipe['filling_material']['base'][k]*blend for k in range(3)]
            core_levels.append(row+color)
    core_levels.append(core_controls[-1][:3]+recipe['filling_material']['base'])
    for name in [*(f'{style}-{g}' for style in recipe['grades'] for g in (1,2,3)),
                 *(f'thermal-{g}' for g in (1,2,3)), 'scar']:
        path = HERE / f'assembled/{name}.png'
        snapshot(path)
        select(ASSETS / f'textures/wounds/hair/{name}.png', path.read_bytes())
    faces = {}
    for model in catalog['models']:
        faces[model] = {}
        for path in sorted((HERE / f'assembled/species/{model}').glob('dry-*.png')):
            expression, grade = path.stem.removeprefix('dry-').rsplit('-', 1)
            faces[model].setdefault(grade, []).append(expression)
            snapshot(path)
            select(ASSETS / f'textures/wounds/hair/{model}/{path.name}', path.read_bytes())
    runtime_recipe = {key: recipe[key] for key in ('grades', 'core_openings', 'thermal',
        'core_center', 'core_radius', 'segments', 'filling_material')}
    selected = dict(format=1, recipe=runtime_recipe, models=catalog['models'], profiles=profiles,
        dry_faces=faces, cavity_controls=cavity_controls, core_levels=core_levels, core_reference_depth=5.7)
    fits = json.loads((ASSETS / 'yukkuri_presentation/placement_fit.json').read_text(encoding='utf-8'))['models']
    for ident, info in catalog['models'].items():
        if not info['house']:
            info['mechanical_v_scale'] = {}
            for receiver in ('hair', 'skin'):
                sample_path = HERE / f'assembled/species/{ident}/{receiver}-scratch-1-normal.json.gz'
                sample = json.loads(gzip.decompress(sample_path.read_bytes()))
                info['mechanical_v_scale'][receiver] = sample['profile']['radius'][1] / recipe['grades']['scratch'][0]['radius'][1]
                input_meshes[sample_path.relative_to(ROOT).as_posix()] = hashlib.sha256(sample_path.read_bytes()).hexdigest()
        path = ASSETS / f'yukkuri/{ident}.json'
        raw = path.read_bytes()
        model = json.loads(raw)
        fit = fits.get(ident)
        if fit is not None:
            if fit['base_sha256'] != hashlib.sha256(raw).hexdigest():
                raise ValueError(f'Incompatible selected model fit: {ident}')
            for entry in fit['parts']:
                part = model['parts'][entry['part_index']]
                if part['name'] != entry['name']:
                    raise ValueError(f'Incompatible selected part: {ident}')
                start = part['start'] if entry['inplace'] else len(model['vertices']) // 8
                for corner, index in enumerate(entry['indices']):
                    value = entry['vertices'][index*5:index*5+5] + [0, 0, -1]
                    binding = entry['weights'][index*3:index*3+3]
                    if entry['inplace']:
                        model['vertices'][(start+corner)*8:(start+corner+1)*8] = value
                        model['weights'][start+corner] = binding
                    else:
                        model['vertices'].extend(value)
                        model['weights'].append(binding)
                if not entry['inplace']:
                    part['start'], part['count'] = start, len(entry['indices'])
            for entry in fit['bone_pivots']:
                model['bones'][entry['index']]['pivot'] = entry['pivot']
        parts, points, uv, weights = [], [], [], []
        hair = set(info['hair_parts']) | set(info['thermal_extra_hair_parts'])
        receivers = hair | set(info['protected_parts'])
        for part in model['parts']:
            name = part['name']
            if name not in receivers:
                continue
            first = len(weights)
            for index in range(part['start'], part['start']+part['count']):
                points.extend(model['vertices'][index*8:index*8+3])
                uv.extend(model['vertices'][index*8+3:index*8+5])
                weights.append(model['weights'][index])
            xs = points[first*3::3]
            susceptible = name in info['hair_parts'] or any(term in name for term in ('hair', 'bang', 'braid')) or name.startswith('wasa_')
            mask = 4 if susceptible else 0
            if susceptible and not any(term in name for term in ('bang', 'crown', 'wrap')) and not name.startswith('wasa_scalp'):
                mask |= 1 if sum(xs) < 0 else 2
            parts.append(dict(name=name, start=first, count=part['count'],
                hair=name in hair, receiver=name in info['hair_parts'],
                accessory=any(name.startswith(prefix) for prefix in info['accessory_prefixes']), hiddenMask=mask))
        geometry = dict(format=1, runtimeSourceHash=hashlib.sha256(raw).hexdigest(), points=points, uv=uv,
            weights=weights, parts=parts, bones=model['bones'])
        packed = gzip.compress(encoded(geometry), compresslevel=9, mtime=0)
        select(SNAPSHOT / f'geometry/{ident}.json.gz', packed)
        select(ASSETS / f'yukkuri_wounds/hair/{ident}.json.gz', packed)
        info['hair_geometry'] = f'yukkuri_wounds/hair/{ident}.json.gz'
    for name in ('recipe.json', 'species.json', 'sources.json', 'mc-damage-map.json',
                 'author.cjs', 'thermal.cjs'):
        snapshot(HERE / name)
    select(SNAPSHOT / 'runtime.json', encoded(selected))
    select(ASSETS / 'yukkuri_presentation/hair_wounds.json', encoded(selected))
    manifest = dict(format=1, source=HERE.relative_to(ROOT).as_posix(),
        selector_sha256=hashlib.sha256(Path(__file__).read_bytes().replace(b'\r\n', b'\n')).hexdigest(),
        scope='Existing authored profiles, transparent materials and expression atlases; runtime reprojects at the authoritative wound site.',
        status='selected_for_runtime_pending_user_review',
        input_meshes=input_meshes,
        files={path.relative_to(ROOT).as_posix(): hashlib.sha256(data).hexdigest()
               for path, data in sorted(outputs.items())})
    select(SNAPSHOT / 'manifest.json', encoded(manifest))
    previous = SNAPSHOT / 'manifest.json'
    if previous.exists():
        for relative in json.loads(previous.read_text(encoding='utf-8'))['files']:
            old = ROOT / relative
            if old not in outputs and old.resolve().is_relative_to(SNAPSHOT.resolve()) and old.is_file():
                if args.check:
                    raise SystemExit(f'Obsolete selected wound output: {relative}')
                old.unlink()
    for path, data in outputs.items():
        if args.check:
            actual = path.read_bytes() if path.exists() else None
            if actual is not None and path.suffix in ('.json', '.cjs', '.py'):
                actual = actual.replace(b'\r\n', b'\n')
            if actual != data:
                raise SystemExit(f'Stale selected wound asset: {path.relative_to(ROOT)}')
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
    print(f'{"Verified" if args.check else "Selected"} {len(outputs)} authored wound assets for {len(faces)} models')

if __name__ == '__main__':
    main()
