#!/usr/bin/env python3
"""Export P1/P2 native block/item geometry and procedural presentation parameters.

Reads art/props/care-facilities/recipe.json and the existing bowl source, never
edits standard character snapshots. --check compares bytes without writing.
"""
import argparse
import hashlib
import json
from export_peripheral_assets import owned_paths
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
SOURCE = ROOT / 'art/props/care-facilities'


def outputs():
    recipe = json.loads((SOURCE / 'recipe.json').read_text(encoding='utf-8'))
    result = {}
    delegated = owned_paths()
    def emit(path, value):
        if path not in delegated:
            result[path] = (json.dumps(value, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    bowl = recipe['food_bowl']
    geometry = json.loads((ROOT / bowl['source_geometry']).read_text(encoding='utf-8'))['minecraft:geometry'][0]
    width = geometry['description']['texture_width']
    elements = []
    for bone in geometry['bones']:
        if bone['name'] not in bowl['bones']:
            continue
        for cube in bone['cubes']:
            start = [a + b for a, b in zip(cube['origin'], bowl['offset'])]
            finish = [a + b for a, b in zip(start, cube['size'])]
            faces = {}
            for face, uv in cube['uv'].items():
                x, y = uv['uv']; w, h = uv['uv_size']
                faces[face] = {'texture': '#bowl', 'uv': [v * 16 / width for v in (x, y, x+w, y+h)]}
            elements.append({'from': start, 'to': finish, 'faces': faces})
    emit(ASSETS / 'models/block/food_bowl.json', {'textures': {'bowl':'mcyukkuri:block/care_bowl','particle':'mcyukkuri:block/care_bowl'}, 'elements':elements})
    result[ASSETS / 'textures/block/care_bowl.png'] = (ROOT / bowl['source_texture']).read_bytes()
    for name, model in recipe['models'].items():
        elements = [{'from':e['from'],'to':e['to'], 'faces':{face:{'texture':'#'+e['texture']} for face in ('north','south','east','west','up','down')}} for e in model['elements']]
        textures = {**model['textures'], 'particle':model['textures']['base']}
        is_block = name in ('yukkuri_nest','yukkuri_toilet')
        emit(ASSETS / ('models/block' if is_block else 'models/item') / (name+'.json'),
             {'parent':'minecraft:block/block','textures':textures,'elements':elements})
    for name in ('food_bowl','yukkuri_nest','yukkuri_toilet'):
        emit(ASSETS / 'models/item' / (name+'.json'), {'parent':'mcyukkuri:block/'+name})
        emit(ASSETS / 'blockstates' / (name+'.json'), {'variants':{'':{'model':'mcyukkuri:block/'+name}}})
    for name, texture in {'wash_cloth':'white_wool','yarn_ball':'magenta_wool','yukkuri_waste':'brown_terracotta','teaching_whistle':'copper_block'}.items():
        emit(ASSETS / 'models/item' / (name+'.json'), {'parent':'minecraft:item/generated','textures':{'layer0':'minecraft:block/'+texture}})
    emit(ASSETS / 'yukkuri_presentation/living.json', recipe['presentation'])
    records = {'role':'P1/P2 game integration; standard character shape and source fingerprints unchanged',
               'inputs':{path:hashlib.sha256((ROOT/path).read_bytes()).hexdigest() for path in
                         ('art/props/care-facilities/recipe.json',bowl['source_geometry'],bowl['source_texture'])},
               'outputs':{path.relative_to(ROOT).as_posix():hashlib.sha256(data).hexdigest() for path,data in result.items()}}
    emit(SOURCE / 'provenance.json', records)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    generated = outputs()
    for path, data in generated.items():
        if args.check:
            if not path.is_file() or path.read_bytes() != data:
                raise SystemExit(f'Living asset export differs: {path.relative_to(ROOT)}')
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
    print(f'{len(generated)} living asset files ' + ('match their sources.' if args.check else 'exported.'))


if __name__ == '__main__':
    main()
