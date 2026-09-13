"""Export reviewed native lines, locale entries and available AquesTalk recordings.

No game is started. --check is read-only; synthesis is a separate, explicit step.
"""
import argparse
import hashlib
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
PREFIX = 'dialogue.mcyukkuri.native.'
PREFIXES = (PREFIX, 'dialogue.mcyukkuri.original.')


def outputs():
    source = json.loads((ROOT / 'art/dialogue/runtime-lines.json').read_text(encoding='utf-8'))
    corpus_text = (ROOT / 'art/dialogue/native-u/catalogue-data.js').read_text(encoding='utf-8')
    corpus = json.loads(corpus_text[len('window.HAKKEI_DIALOGUE='):-2])
    native = {(r['file'], r['line']): r for r in corpus['entries']}
    original_path = ROOT / 'art/dialogue/species-original.json'
    authored = {r['id']:r for r in json.loads(original_path.read_text(encoding='utf-8'))['lines']} if original_path.exists() else {}
    manifest_path = ROOT / 'art/audio/aquestalk/manifest.json'
    voices = json.loads(manifest_path.read_text(encoding='utf-8'))['clips'] if manifest_path.exists() else {}
    catalog, sounds, files = [], {}, {}
    locales = {}
    for locale in ('zh_cn', 'ja_jp', 'en_us'):
        path = ASSETS / f'lang/{locale}.json'
        old = json.loads(path.read_text(encoding='utf-8')) if path.exists() else {}
        locales[locale] = {k: v for k, v in old.items() if not k.startswith(PREFIXES)}
    seen = set()
    for line in source['lines']:
        ident, ref = line['id'], line['source']
        assert ident not in seen, ident
        seen.add(ident)
        origin = line.get('origin', 'native')
        if origin == 'native':
            original = native[(ref['file'], ref['line'])]
            assert original['raw'] == ref['raw']
            assert hashlib.sha256(ref['raw'].encode()).hexdigest() == line['source_sha256']
            namespace = 'native'
        else:
            assert origin == 'project-original' and ref['file'] == 'art/dialogue/species-original.json'
            original = authored[ref['id']]
            assert all(original[k] == line[k] for k in ['id','japanese','chinese','species','age','events'])
            assert hashlib.sha256(line['japanese'].encode()).hexdigest() == line['source_sha256']
            namespace = 'original'
        key, sound_id = 'dialogue.mcyukkuri.' + namespace + '.' + ident, 'voice.' + namespace + '.' + ident
        clip = voices.get(ident)
        ticks = max(50, min(180, len(line['japanese']) * 4 + 15))
        if clip:
            assert clip['japanese'] == line['japanese'] and clip['engine'] == 'AquesTalk1'
            path = ROOT / clip['ogg']
            assert path.is_file() and hashlib.sha256(path.read_bytes()).hexdigest() == clip['sha256']
            ticks = max(45, int(clip['duration_seconds'] * 20 + .999) + 15)
            target = f'sounds/voice/{namespace}/{ident}.ogg'
            files[ASSETS / target] = path.read_bytes()
            sounds[sound_id] = {'subtitle': key, 'sounds': [{'name': 'mcyukkuri:' + target[7:-4], 'attenuation_distance': 16}]}
        catalog.append(dict(id=ident, key=key, origin=origin, species=line['species'], age=line['age'], events=line['events'],
                            ticks=ticks, sound=sound_id if clip else ''))
        locales['zh_cn'][key] = line['chinese']
        locales['ja_jp'][key] = line['japanese']
        # Until an English translation is reviewed, retain the original Japanese.
        locales['en_us'][key] = line['japanese']
    for locale, values in locales.items():
        files[ASSETS / f'lang/{locale}.json'] = (json.dumps(values, ensure_ascii=False, indent=2) + '\n').encode()
    files[ASSETS / 'dialogue/catalog.json'] = (json.dumps(catalog, ensure_ascii=False, indent=2) + '\n').encode()
    sounds_path = ASSETS / 'sounds.json'
    existing = json.loads(sounds_path.read_text(encoding='utf-8')) if sounds_path.exists() else {}
    existing = {k: v for k, v in existing.items() if not k.startswith(('voice.native.', 'voice.original.'))}
    existing.update(sounds)
    files[sounds_path] = (json.dumps(existing, ensure_ascii=False, indent=2) + '\n').encode()
    return files, len(catalog), len(sounds)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    parser.add_argument('--stage', type=Path, help='Write a staged asset tree instead of shared runtime assets')
    args = parser.parse_args()
    files, count, voiced = outputs()
    for path, data in files.items():
        if args.stage: path = args.stage / path.relative_to(ASSETS)
        if args.check:
            assert path.is_file(), f'Missing: {path}'
            matches = json.loads(path.read_bytes()) == json.loads(data) if path.suffix == '.json' else path.read_bytes() == data
            assert matches, f'Out of date: {path}'
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
    print(f'{count} sourced lines; {voiced} recorded voices; ' + ('checked' if args.check else 'exported'))


if __name__ == '__main__':
    main()
