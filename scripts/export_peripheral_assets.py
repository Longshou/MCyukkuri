#!/usr/bin/env python3
"""Export the authorized R2.3 integration selection without changing standard approvals.

Owns selected static meshes, item/block dispatch models and new survival recipes.
--check is read-only. Existing P1/P4 exporters defer the same paths to this owner.
"""
import argparse
import json
import io
from asset_export_checks import check_output, fingerprint, fingerprints, provenance
from pathlib import Path
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'art/props/runtime-peripheral'
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
DATA = ROOT / 'src/main/resources/data/mcyukkuri'

def recipe():
    return json.loads((SOURCE / 'recipe.json').read_text(encoding='utf-8'))

def owned_paths():
    spec = recipe()
    result = {ASSETS / f'models/item/{name}.json' for name in spec['items']}
    result |= {ASSETS / f'models/block/{name}.json' for name in spec['blocks']}
    result |= {DATA / f'{kind}/{name}.json' for name in spec['retired_recipes']
               for kind in ('recipe', 'advancement/recipes/misc')}
    return result


def selected_bowl_palette(atlas_bytes, palette_bytes):
    """Reuse the selected original palette, avoiding encoder-version drift."""
    with Image.open(io.BytesIO(atlas_bytes)) as atlas, Image.open(io.BytesIO(palette_bytes)) as palette:
        crop = atlas.crop((0, 0, 256, 256))
        if (crop.mode != palette.mode or crop.size != palette.size or
                crop.tobytes() != palette.tobytes()):
            raise ValueError('Selected bowl palette differs from the preview atlas crop')
    return palette_bytes


def outputs():
    spec = recipe()
    previous = provenance(SOURCE / 'provenance.json')
    result, inputs, index = {}, {}, {}
    def emit(path, data):
        result[path] = (json.dumps(data, ensure_ascii=False, separators=(',', ':')) + '\n').encode('utf-8')
    for name in dict.fromkeys(spec['items'] + spec['blocks'] + spec['attachments']):
        folder = ROOT / spec['source'] / name
        mesh_path, texture_path = folder / f'{name}.mesh.json', folder / 'texture.png'
        mesh = json.loads(mesh_path.read_text(encoding='utf-8'))
        if name == 'house_crevice':
            # Loose lining is drawn only from actual nest materials, not baked into an empty crevice.
            original = mesh['vertices']; vertices, parts = [], []
            for part in mesh['parts']:
                if part['name'].startswith('cotton_'):
                    continue
                start = len(vertices)//8
                vertices.extend(original[part['start']*8:(part['start']+part['count'])*8])
                parts.append({**part, 'start': start})
            mesh['vertices'], mesh['parts'] = vertices, parts
        texture = texture_path.read_bytes()
        if name in ('food_bowl_forage', 'food_bowl_wheat'):
            # The preview atlas embeds vanilla pixels in its top-right corner. Runtime references
            # those vanilla resources directly and ships only our original palette at top-left.
            palette_path = ROOT / spec['source'] / 'food_bowl/texture.png'
            palette_bytes = palette_path.read_bytes()
            texture = selected_bowl_palette(texture, palette_bytes)
            key = palette_path.relative_to(ROOT).as_posix()
            inputs[key] = fingerprint(palette_path, palette_bytes, previous.get('inputs', {}).get(key))
            for part in mesh['parts']:
                native = part['name'].startswith(('cut_grass_', 'laid_wheat_'))
                if native:
                    part['texture'] = 'minecraft:textures/item/wheat.png' if name.endswith('wheat') else 'minecraft:textures/block/short_grass.png'
                    part['tint'] = [255, 255, 255] if name.endswith('wheat') else [145, 189, 89]
                for i in range(part['start']*8, (part['start']+part['count'])*8, 8):
                    u,v = mesh['vertices'][i+3:i+5]
                    mesh['vertices'][i+3:i+5] = [(u*512-256)/16, v*512/16] if native else [u*2,v*2]
        vertices = mesh['vertices']
        assert len(vertices) % 24 == 0 and all(isinstance(v, (int, float)) for v in vertices)
        bounds = [[min(vertices[i::8]), max(vertices[i::8])] for i in range(3)]
        mesh['bounds'] = bounds
        mesh['format'] = 1
        # Triangles and material are selected verbatim; no body rig is attached to static props.
        emit(ASSETS / f'yukkuri_props/{name}.json', mesh)
        result[ASSETS / f'textures/props/{name}.png'] = texture
        for path in (mesh_path, texture_path):
            key = path.relative_to(ROOT).as_posix()
            inputs[key] = fingerprint(path, path.read_bytes(), previous.get('inputs', {}).get(key))
        index[name] = {'bounds': bounds, 'triangles': len(vertices) // 24}
    # Species accessories are selected from the same fitted triangles hidden on
    # their owner, preserving the real bonnet/headband shape when removed.
    living_path = ROOT / 'art/props/care-facilities/recipe.json'
    living = json.loads(living_path.read_text(encoding='utf-8'))['presentation']['variants']
    key = living_path.relative_to(ROOT).as_posix()
    inputs[key] = fingerprint(living_path, living_path.read_bytes(), previous.get('inputs', {}).get(key))
    for species, name in spec.get('species_accessories', {}).items():
        model_path = ROOT / f'art/source/standard-runtime/{species}/runtime.model.json'
        texture_path = ROOT / f'art/source/standard-runtime/{species}/textures/neutral.png'
        model = json.loads(model_path.read_text(encoding='utf-8'))
        prefixes = tuple(living[species]['accessory_prefixes'])
        vertices, parts = [], []
        for part in model['parts']:
            if not part['name'].startswith(prefixes):
                continue
            parts.append({'name': part['name'], 'start': len(vertices)//8, 'count': part['count']})
            vertices.extend(model['vertices'][part['start']*8:(part['start']+part['count'])*8])
        if not vertices:
            raise ValueError(f'No accessory geometry selected for {species}')
        bounds = [[min(vertices[i::8]), max(vertices[i::8])] for i in range(3)]
        emit(ASSETS / f'yukkuri_props/{name}.json', {'format': 1, 'units_per_block': 16,
             'vertices': vertices, 'parts': parts, 'bounds': bounds})
        result[ASSETS / f'textures/props/{name}.png'] = texture_path.read_bytes()
        for path in (model_path, texture_path):
            key = path.relative_to(ROOT).as_posix()
            inputs[key] = fingerprint(path, path.read_bytes(), previous.get('inputs', {}).get(key))
        index[name] = {'bounds': bounds, 'triangles': len(vertices)//24}
    emit(ASSETS / 'yukkuri_props/index.json', index)
    for name in spec['items'] + list(spec.get('species_accessories', {}).values()):
        emit(ASSETS / f'models/item/{name}.json', {
            'parent': 'minecraft:builtin/entity', 'gui_light': 'front',
            'textures': {'particle': f'mcyukkuri:props/{name}'},
            'display': {
                'gui': {'rotation': [25, 225, 0], 'translation': [0, 0, 0], 'scale': [.85, .85, .85]},
                'ground': {'translation': [0, 3, 0], 'scale': [.5, .5, .5]},
                'fixed': {'scale': [.75, .75, .75]},
                'thirdperson_righthand': {'rotation': [0, 0, 0], 'translation': [0, 3, 0], 'scale': [.5, .5, .5]},
                'firstperson_righthand': {'rotation': [0, 0, 0], 'translation': [0, 2, 0], 'scale': [.6, .6, .6]}}})
    for name in spec['blocks']:
        emit(ASSETS / f'models/block/{name}.json', {'textures': {'particle': f'mcyukkuri:props/{name}'}, 'elements': []})
    for name, details in spec['recipes'].items():
        emit(DATA / f'recipe/{name}.json', {'type': 'minecraft:crafting_shapeless', 'category': 'misc',
             'ingredients': [{'item': value} for value in details['ingredients']], 'result': {'id': f'mcyukkuri:{name}', 'count': details['count']}})
        emit(DATA / f'advancement/recipes/misc/{name}.json', {'parent': 'minecraft:recipes/root',
             'criteria': {'has_ingredient': {'trigger': 'minecraft:inventory_changed', 'conditions': {'items': [{'items': details['ingredients'][0]}]}}},
             'requirements': [['has_ingredient']], 'rewards': {'recipes': [f'mcyukkuri:{name}']}})
    # NeoForge conditions retire old crafting routes while retaining IDs for existing saves.
    for name in spec['retired_recipes']:
        for kind in ('recipe', 'advancement/recipes/misc'):
            emit(DATA / f'{kind}/{name}.json', {'neoforge:conditions': [{'type': 'neoforge:false'}]})
    key = 'art/props/runtime-peripheral/recipe.json'
    inputs[key] = fingerprint(SOURCE/'recipe.json', (SOURCE/'recipe.json').read_bytes(), previous.get('inputs', {}).get(key))
    emit(SOURCE / 'provenance.json', {'status': spec['status'], 'skeleton': 'static props; no character rig changes',
         'inputs': inputs, 'outputs': fingerprints(ROOT,result,previous.get('outputs',{}))})
    return result

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    generated = outputs()
    for path, content in generated.items():
        if args.check:
            if not check_output(path,content):
                raise SystemExit(f'Peripheral export differs: {path.relative_to(ROOT)}')
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
    print(f'{len(generated)} peripheral exports ' + ('match.' if args.check else 'written.'))

if __name__ == '__main__':
    main()
