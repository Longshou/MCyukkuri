#!/usr/bin/env python3
"""Ship the inventory icons of the items whose world look is a 3D prop (toys, devices, medicines, care tools).

Reads the 16x16 snapshot PNGs written by standards/yukkuri-library/drafts/item-icons/draw.py from art/props/item-icons/ and
writes src/main/resources/assets/mcyukkuri/textures/item/<id>.png plus the GUI-only model models/item/icon/<id>.json
(item/generated); client/ItemIcons shows that model in inventory slots and keeps the item's own prop model everywhere else.
The item models themselves stay with their prop owners. Input and output fingerprints go to art/props/item-icons/provenance.json.
--check is read-only.
"""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SOURCE = ROOT / 'art/props/item-icons'
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
STATUS = 'pending_user_confirmation'


def sha(data):
    return hashlib.sha256(data).hexdigest()


def outputs():
    result, inputs, outs = {}, {}, {}
    for png in sorted(SOURCE.glob('*.png')):
        name, data = png.stem, png.read_bytes()
        if not (ASSETS / f'models/item/{name}.json').is_file():
            raise SystemExit(f'Icon {name} has no item model: models/item/{name}.json')
        model = (json.dumps({'parent': 'minecraft:item/generated', 'textures': {'layer0': f'mcyukkuri:item/{name}'}},
                            separators=(',', ':')) + '\n').encode('utf-8')
        texture_path, model_path = ASSETS / f'textures/item/{name}.png', ASSETS / f'models/item/icon/{name}.json'
        result[texture_path], result[model_path] = data, model
        inputs[png.relative_to(ROOT).as_posix()] = sha(data)
        for path, content in ((texture_path, data), (model_path, model)):
            outs[path.relative_to(ROOT).as_posix()] = sha(content)
    provenance = {'status': STATUS, 'generator': 'standards/yukkuri-library/drafts/item-icons/draw.py',
                  'exporter': 'scripts/export_item_icons.py', 'inputs': inputs, 'outputs': outs}
    result[SOURCE / 'provenance.json'] = (json.dumps(provenance, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    generated = outputs()
    stale = sorted(set((ASSETS / 'models/item/icon').glob('*.json')) - set(generated))
    if stale:   # a dropped icon: delete its snapshot, icon model and texture together
        raise SystemExit('Item icon model without a snapshot: ' + ', '.join(p.relative_to(ROOT).as_posix() for p in stale))
    for path, content in generated.items():
        if args.check:
            if not path.is_file() or path.read_bytes().replace(b'\r\n', b'\n') != content.replace(b'\r\n', b'\n'):
                raise SystemExit(f'Item icon export differs: {path.relative_to(ROOT)}')
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(content)
    print(f'{len(generated)} item icon files ' + ('match.' if args.check else 'written.'))


if __name__ == '__main__':
    main()
