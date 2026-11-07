"""Export presentation rows; --check validates references and byte-for-byte freshness."""
import argparse
import json
import math
from pathlib import Path

from face_usage import check_coverage

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT/'src/main/resources/assets/mcyukkuri'
SOURCE = ROOT/'standards/yukkuri-library/presentation/moments.json'
TARGET = ASSETS/'yukkuri_presentation/moments.json'


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def validate(data):
    clips = {}
    for path in (ASSETS/'yukkuri').glob('*.json'):
        model = read(path)
        clips.update(model.get('clips', {}))
        clips.update(model.get('behavior_clips', {}))
    for model in read(ASSETS/'yukkuri_presentation/motion_corrections.json')['models'].values():
        clips.update(model['clips'])
    emotes = {r['id'] for r in read(ASSETS/'textures/emote/emotes.json')['emotes']}
    usage = {}
    for ident, row in data['moments'].items():
        if not row['steps']:
            raise ValueError(f'{ident}: empty steps')
        for step in row['steps']:
            if step['clip'] not in clips:
                raise ValueError(f'{ident}: unknown clip {step["clip"]}')
            for key in ('tempo', 'intensity'):
                if not math.isfinite(step[key]) or step[key] <= 0:
                    raise ValueError(f'{ident}: invalid {key}')
            duration = clips[step['clip']]['duration']/step['tempo']
            previous = -1
            for at, face in step['faces']:
                if not math.isfinite(at) or at < 0 or at <= previous or at >= duration:
                    raise ValueError(f'{ident}: invalid face time {at}')
                usage.setdefault(face, set()).add(ident)
                previous = at
            if step.get('emote') and step['emote'] not in emotes:
                raise ValueError(f'{ident}: unknown emote {step["emote"]}')
            for key in ('particle', 'sound'):
                if step.get(key) and not step[key].startswith('minecraft:'):
                    raise ValueError(f'{ident}: {key} must be a vanilla resource id')
        if 'condition_face' in row:
            usage.setdefault(row['condition_face'], set()).add(ident)
    for face in data['predator_faces'].values():
        usage.setdefault(face, set()).add('predator_faces')
    for table in ('behaviors', 'reactions', 'groups', 'conditions'):
        for key, ident in data[table].items():
            if ident not in data['moments']:
                raise ValueError(f'{table}/{key}: unknown moment {ident}')
    check_coverage(read(ASSETS/'yukkuri_presentation/face_library.json'), usage)
    return data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    data = validate(read(SOURCE))
    encoded = (json.dumps(data, ensure_ascii=False, separators=(',', ':'))+'\n').encode('utf-8')
    if args.check:
        if TARGET.read_bytes() != encoded:
            raise ValueError('Outdated moments runtime; run scripts/export_moments.py')
    else:
        TARGET.write_bytes(encoded)
    print(f'Moments: {len(data["moments"])} rows '+('verified' if args.check else 'exported'))


if __name__ == '__main__':
    main()
