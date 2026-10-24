#!/usr/bin/env python3
"""Ship the five care-supply item sprites (orange juice, flour paste, budget / regular / premium oil grain).

Reads the 16x16 snapshot PNGs written by standards/yukkuri-library/drafts/care-props/supplies.py from art/props/supply-icons/,
writes src/main/resources/assets/mcyukkuri/textures/item/<id>.png and the plain item/generated models/item/<id>.json, then
records input and output fingerprints in art/props/supply-icons/provenance.json. --check is read-only.
"""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'art/props/supply-icons'
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
NAMES = ('orange_juice', 'flour_paste', 'oil_grain_budget', 'oil_grain_regular', 'oil_grain_premium')
STATUS = 'pending_user_confirmation'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def outputs():
    result, inputs, outs = {}, {}, {}
    for name in NAMES:
        data = (SOURCE / f'{name}.png').read_bytes()
        model = (json.dumps({'parent': 'minecraft:item/generated', 'textures': {'layer0': f'mcyukkuri:item/{name}'}},
                            separators=(',', ':')) + '\n').encode('utf-8')
        texture_path, model_path = ASSETS / f'textures/item/{name}.png', ASSETS / f'models/item/{name}.json'
        result[texture_path], result[model_path] = data, model
        inputs[f'art/props/supply-icons/{name}.png'] = sha(data)
        for path, content in ((texture_path, data), (model_path, model)):
            outs[path.relative_to(ROOT).as_posix()] = sha(content)
    provenance = {'status': STATUS, 'generator': 'standards/yukkuri-library/drafts/care-props/supplies.py',
                  'exporter': 'scripts/export_supply_icons.py', 'inputs': inputs, 'outputs': outs}
    result[SOURCE / 'provenance.json'] = (json.dumps(provenance, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    generated = outputs()
    for path, content in generated.items():
        if args.check:
            if not path.is_file() or path.read_bytes().replace(b'\r\n', b'\n') != content.replace(b'\r\n', b'\n'):
                raise SystemExit(f'Supply icon export differs: {path.relative_to(ROOT)}')
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
    print(f'{len(generated)} supply icon files ' + ('match.' if args.check else 'written.'))


if __name__ == '__main__':
    main()
