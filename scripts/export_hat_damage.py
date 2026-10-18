#!/usr/bin/env python3
"""Export the accessory damage recipe (art/props/hat-damage/recipe.json) and the shard item model.

Writes src/main/resources/assets/mcyukkuri/yukkuri_presentation/hat_damage.json and models/item/accessory_shard.json.
--check is read-only and fails when an export differs from the recipe.
"""
import argparse, json, sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RECIPE = ROOT / 'art/props/hat-damage/recipe.json'
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'

def outputs():
    recipe = json.loads(RECIPE.read_text(encoding='utf-8'))
    runtime = {k: recipe[k] for k in ('format', 'fray_tint', 'frayed', 'torn', 'pieced', 'pieces')}
    runtime['source'] = RECIPE.relative_to(ROOT).as_posix()
    shard = {'parent': 'minecraft:builtin/entity', 'gui_light': 'front', 'textures': {'particle': 'mcyukkuri:props/accessory_reimu'},
             'display': {'gui': {'rotation': [25, 225, 0], 'translation': [0, 0, 0], 'scale': [.85, .85, .85]},
                         'ground': {'translation': [0, 3, 0], 'scale': [.5, .5, .5]}, 'fixed': {'scale': [.75, .75, .75]},
                         'thirdperson_righthand': {'rotation': [0, 0, 0], 'translation': [0, 3, 0], 'scale': [.5, .5, .5]},
                         'firstperson_righthand': {'rotation': [0, 0, 0], 'translation': [0, 2, 0], 'scale': [.6, .6, .6]}}}
    dump = lambda data: (json.dumps(data, ensure_ascii=False, separators=(',', ':')) + '\n').encode('utf-8')
    return {ASSETS / 'yukkuri_presentation/hat_damage.json': dump(runtime), ASSETS / 'models/item/accessory_shard.json': dump(shard)}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    for path, content in outputs().items():
        if args.check:
            if not path.exists() or path.read_bytes() != content:
                sys.exit(f'Hat damage export differs: {path.relative_to(ROOT)}')
        else:
            path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(content)
    print('hat damage exports ' + ('match.' if args.check else 'written.'))

if __name__ == '__main__':
    main()
