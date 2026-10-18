"""Refresh Alice's selected atlases after the character-local appearance revision.

No geometry or other species is rebuilt. Run after the Alice source builder; --check
compares the same outputs without writing. Existing expression selections are retained.
"""
from pathlib import Path
import argparse
import base64
import copy
import hashlib
import io
import json
import sys
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
LIB = ROOT / 'standards/yukkuri-library'
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
SELECTED = ROOT / 'art/source/rounded-runtime/alice'
SOURCE = LIB / 'assembled/alice'
DRAFT = LIB / 'drafts/rapem-alice/variant'
sys.path.insert(0, str(LIB))
from character_appearance import apply_style


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def png(data):
    image = Image.open(io.BytesIO(data)).convert('RGBA')
    result = apply_style('alice', image.copy())
    if result.tobytes() == image.tobytes():
        return data
    buffer = io.BytesIO()
    result.save(buffer, format='PNG')
    return buffer.getvalue()


def outputs():
    files = {}

    def content(path):
        return files[path] if path in files else path.read_bytes()

    def sha(path):
        return hashlib.sha256(content(path)).hexdigest()

    def save(path, data):
        files[path] = (json.dumps(data, ensure_ascii=False, indent=2) + '\n').encode()

    # Source atlases are already composed from the shared expressions; apply only
    # Alice's palette/iris transformation, including special-state source sheets.
    for folder in (SOURCE / 'textures', DRAFT):
        for source in sorted(folder.glob('*.png')):
            if folder == DRAFT and source.stem not in ('breeding_period', 'state_recover'):
                continue
            # The period state is ordinary observation, so use the current neutral
            # face instead of overlaying old reference pixels on a different iris.
            files[source] = content(SOURCE / 'textures/neutral.png') if folder == DRAFT and source.stem == 'breeding_period' else png(source.read_bytes())

    runtime = ASSETS / 'textures/entity/alice'
    aliases = {'dazed': 'blank_confused'}
    for target in sorted(runtime.glob('*.png')):
        name = aliases.get(target.stem, target.stem)
        source = SOURCE / 'textures' / (name + '.png')
        if not source.exists():
            source = DRAFT / (name + '.png')
        if source.exists():
            files[target] = content(source)
        elif name == 'cheek_puff':
            # This atlas has a separately authored mouth. Keep it and recolour the eyes.
            files[target] = png(target.read_bytes())
        else:
            raise ValueError('Unregistered Alice face: ' + target.name)
        snapshot = SELECTED / 'textures' / target.name
        if snapshot.exists():
            files[snapshot] = content(target)
        # Adult-state snapshots use species prefixes for shared standard faces.
        for filename in (target.name, 'alice_' + target.name):
            snapshot = ROOT / 'art/source/adult-states' / filename
            if filename and snapshot.is_file():
                files[snapshot] = content(target)

    files[SOURCE / 'alice.png'] = content(SOURCE / 'textures/neutral.png')
    bb = read(SOURCE / 'alice.bbmodel')
    bb['textures'][0]['source'] = 'data:image/png;base64,' + base64.b64encode(content(SOURCE / 'alice.png')).decode()
    files[SOURCE / 'alice.bbmodel'] = json.dumps(bb, ensure_ascii=False, separators=(',', ':')).encode()
    files[SELECTED / 'selected/alice.bbmodel'] = content(SOURCE / 'alice.bbmodel')

    manifest_path = ROOT / 'art/source/rounded-runtime/manifest.json'
    manifest = read(manifest_path)
    record = manifest['models']['alice']
    record['selected_model_sha256'] = sha(SOURCE / 'alice.bbmodel')
    record['selected_rig_sha256'] = sha(SOURCE / 'animation-rig.json')
    record['selected_runtime_sha256'] = sha(SOURCE / 'runtime.model.json')
    for item in record['textures'].values():
        for key in ('source_sha256', 'sha256'):
            if key in item:
                item[key] = sha(ROOT / item['source' if key == 'source_sha256' else 'runtime'])
    save(manifest_path, manifest)
    ledger_path = ROOT / 'art/runtime-sources.json'
    ledger = read(ledger_path)
    ledger['models']['alice'] = copy.deepcopy(record)
    ledger['alice_appearance'] = {
        'source': 'standards/yukkuri-library/body-standard/features/alice-hakkei',
        'texture_style': 'standards/yukkuri-library/characters/alice.json',
        'exporter': 'scripts/export_alice_appearance.py',
        'status': 'selected_for_runtime_pending_visual_feedback',
        'scope': 'Alice hair, headband and open-eye irises; all existing selected expressions, including adult states',
        'shared_expression_shapes': 'preserved',
    }
    save(ledger_path, ledger)

    # Keep existing provenance dependency hashes current without changing their
    # acceptance states or re-exporting any unrelated models.
    for name in ('art/source/motion-corrections/manifest.json',
                 'art/props/care-facilities/provenance.json'):
        path = ROOT / name
        data = read(path)
        def refresh(value):
            if isinstance(value, dict):
                for key, item in value.items():
                    if isinstance(item, str) and len(item) == 64 and ROOT / key in files:
                        value[key] = sha(ROOT / key)
                    elif isinstance(item, (dict, list)):
                        refresh(item)
            elif isinstance(value, list):
                for item in value:
                    refresh(item)
        refresh(data)
        if 'models' in data and 'alice' in data['models']:
            item = data['models']['alice']
            item['texture_sha256'] = sha(runtime / 'cheek_puff.png')
            for expr in item.get('expression_source_sha256', {}):
                item['expression_source_sha256'][expr] = sha(runtime / (expr + '.png'))
        save(path, data)
    return files


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    files = outputs()
    changed = 0
    for path, data in files.items():
        if path.exists() and path.read_bytes() == data:
            continue
        if args.check:
            raise SystemExit('Stale Alice export: ' + str(path.relative_to(ROOT)))
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)
        changed += 1
    print(f'Alice appearance: {len(files)} outputs checked, {changed} updated.')


if __name__ == '__main__':
    main()
