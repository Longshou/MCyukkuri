"""Family-size 2x2 nests: the woven pet bed (placed.py) and the wild bedding (wild.py), registered in vanilla_props.py.

Writes the snapshot art/props/large-nests/<id>/{<id>.bbmodel, <id>.mesh.json, texture.png} with manifest fingerprints, and the
runtime mesh, palette texture, particle-only block model, builtin item model, blockstate, loot table and zh_cn / en_us names.
Meshes are centred on the 2x2 footprint (origin = shared corner of the four blocks), entrance on +z. The props join the shared
yukkuri_props/index.json as owner entries, which scripts/export_peripheral_assets.py keeps. --check is read-only.
"""
import argparse, hashlib, json, sys
from pathlib import Path

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(ROOT / 'scripts'))
import kit, vanilla_props
from asset_export_checks import check_output, preserve_equivalent_png

SNAPSHOT = ROOT / 'art/props/large-nests'
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
DATA = ROOT / 'src/main/resources/data/mcyukkuri'
GENERATOR = 'standards/yukkuri-library/drafts/care-props/large_nests.py'
BUILD = vanilla_props.LARGE
LABELS = {'large_yukkuri_nest': ('油库里大编织软窝', 'Large Woven Yukkuri Nest'),
          'large_wild_nest': ('野生大草窝', 'Large Wild Grass Nest')}


def outputs():
    result, props = {}, {}
    def emit(path, data):
        result[path] = kit.dumps(data)
    index = json.loads((ASSETS / 'yukkuri_props/index.json').read_text(encoding='utf-8'))
    for name, factory in BUILD.items():
        prop = props[name] = factory()
        mesh = prop.mesh()
        folder = SNAPSHOT / name
        texture = preserve_equivalent_png(folder / 'texture.png', prop.png())
        emit(folder / f'{name}.mesh.json', mesh)
        emit(folder / f'{name}.bbmodel', prop.bbmodel())
        result[folder / 'texture.png'] = texture
        emit(ASSETS / f'yukkuri_props/{name}.json', mesh)
        result[ASSETS / f'textures/props/{name}.png'] = texture
        index[name] = {'bounds': mesh['bounds'], 'triangles': len(mesh['vertices']) // 24, 'owner': 'large_nests'}
        emit(ASSETS / f'models/block/{name}.json', {'textures': {'particle': f'mcyukkuri:props/{name}'}, 'elements': []})
        emit(ASSETS / f'blockstates/{name}.json', {'variants': {'': {'model': f'mcyukkuri:block/{name}'}}})
        emit(ASSETS / f'models/item/{name}.json', {
            'parent': 'minecraft:builtin/entity', 'gui_light': 'front', 'textures': {'particle': f'mcyukkuri:props/{name}'},
            'display': {'gui': {'rotation': [25, 225, 0], 'translation': [0, 0, 0], 'scale': [.85, .85, .85]},
                        'ground': {'translation': [0, 3, 0], 'scale': [.25, .25, .25]},
                        'fixed': {'scale': [.4, .4, .4]},
                        'thirdperson_righthand': {'rotation': [0, 0, 0], 'translation': [0, 3, 0], 'scale': [.25, .25, .25]},
                        'firstperson_righthand': {'rotation': [0, 0, 0], 'translation': [0, 2, 0], 'scale': [.3, .3, .3]}}})
        emit(DATA / f'loot_table/blocks/{name}.json', {'type': 'minecraft:block', 'pools': [{'rolls': 1, 'entries': [
            {'type': 'minecraft:item', 'name': f'mcyukkuri:{name}'}], 'conditions': [{'condition': 'minecraft:survives_explosion'}]}]})
    emit(ASSETS / 'yukkuri_props/index.json', index)
    for i, locale in enumerate(('zh_cn', 'en_us')):
        path = ASSETS / f'lang/{locale}.json'
        existing = json.loads(path.read_text(encoding='utf-8'))
        strings = {f'{kind}.mcyukkuri.{name}': labels[i] for name, labels in LABELS.items() for kind in ('block', 'item')}
        changed = any(existing.get(k) != v for k, v in strings.items())
        existing.update(strings)
        result[path] = (json.dumps(existing, ensure_ascii=False, indent=2) + '\n').encode('utf-8') if changed else path.read_bytes()
    files = {p.relative_to(ROOT).as_posix(): hashlib.sha256(v).hexdigest() for p, v in result.items() if p.parent.name != 'lang'
             and p.name != 'index.json'}
    emit(SNAPSHOT / 'manifest.json', {'status': 'pending_user_confirmation', 'generator': GENERATOR,
         'footprint': '2x2 blocks (32 x 32 units), origin at the footprint centre, entrance +z',
         'capacity': 'two adults and four newborns settling anywhere on an unmarked floor',
         'interior': 'about 29 units (1.8 blocks) across inside a one-cell rim; outline within 15.5 of the centre',
         'rim_max_height': {'large_yukkuri_nest': 7.0, 'large_wild_nest': 3.0, 'collision_rim': 3.0}, 'files': files})
    return result, props


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    result, props = outputs()
    changed = []
    for path, content in result.items():
        if not check_output(path, content):
            changed.append(path.relative_to(ROOT).as_posix())
            if not args.check:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_bytes(content)
    if args.check and changed:
        raise SystemExit('Large nest exports differ: ' + ', '.join(changed))
    print(f'Large nests: {len(props)} meshes; ' + ('match.' if args.check else f'{len(changed)} files written.'))
    for name, prop in props.items():
        bounds = prop.mesh()['bounds']
        print(f'  {name}: {prop.tri_count()} triangles, bounds {[[round(a, 2), round(b, 2)] for a, b in bounds]}')


if __name__ == '__main__':
    main()
