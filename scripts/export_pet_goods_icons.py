#!/usr/bin/env python3
"""Ship the four pet-care goods icons (sweet candy, custard pudding, pet harness, swaddle cloth).

Reads the 16x16 snapshot PNGs written by standards/yukkuri-library/drafts/care-props/build.py from art/props/pet-goods/ and writes
src/main/resources/assets/mcyukkuri/textures/item/<id>.png, then records input and output fingerprints in
art/props/pet-goods/provenance.json. The item models (plain item/generated) are unchanged. --check is read-only.
"""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'art/props/pet-goods'
ITEMS = ROOT / 'src/main/resources/assets/mcyukkuri/textures/item'
NAMES = ('sweet_candy', 'custard_pudding', 'pet_harness', 'swaddle_cloth')
STATUS = 'pending_user_confirmation'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def outputs():
    result, inputs, outs = {}, {}, {}
    for name in NAMES:
        data = (SOURCE / f'{name}.png').read_bytes()
        result[ITEMS / f'{name}.png'] = data
        inputs[f'art/props/pet-goods/{name}.png'] = sha(data)
        outs[f'src/main/resources/assets/mcyukkuri/textures/item/{name}.png'] = sha(data)
    provenance = {'status': STATUS, 'generator': 'standards/yukkuri-library/drafts/care-props/build.py',
                  'exporter': 'scripts/export_pet_goods_icons.py', 'palette': 'art/props/palette.json',
                  'inputs': inputs, 'outputs': outs}
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
                raise SystemExit(f'Pet goods export differs: {path.relative_to(ROOT)}')
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
    print(f'{len(generated)} pet goods files ' + ('match.' if args.check else 'written.'))


if __name__ == '__main__':
    main()
