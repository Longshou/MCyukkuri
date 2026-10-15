"""Copy two confirmed standard expressions into the game textures for the bereaved reaction.

`blank_confused` (N007, white blank eyes) becomes the runtime `dazed` face and `blank_cry`
keeps its name. Both are in the confirmed standard set (provenance/standard-expressions.json).
Only species whose runtime neutral atlas is byte-identical to the current standard export
(the rounded body standard re-rendered every atlas, so the cube-era hashes in the provenance
no longer apply) receive them, from that same export; a form whose runtime atlas is that same
export (the bushy Reimu, `reimu_wasa`, shares Reimu's face sheet) receives its base's faces. Other forms fall back along the lore recipe's
`face_fallback` (dazed -> surprise, blank_cry -> cry). Writes art/source/motion-corrections/extra-faces.json.

  python faces.py [--check]
"""
from pathlib import Path
import argparse, hashlib, json

HERE = Path(__file__).resolve().parent
LIB = HERE.parents[2]; ROOT = LIB.parents[1]
TEXTURES = ROOT/'src/main/resources/assets/mcyukkuri/textures/entity'
FACES = {'dazed': 'blank_confused', 'blank_cry': 'blank_cry'}
SPECIES = ['reimu', 'marisa', 'alice', 'chen', 'patchouli', 'marisa_tsumuri']
SAME_AS = {'reimu_wasa': 'reimu'}  # forms drawn on their base's face sheet
OUT = ROOT/'art/source/motion-corrections/extra-faces.json'


def sha(data): return hashlib.sha256(data).hexdigest()


def main():
    ap = argparse.ArgumentParser(); ap.add_argument('--check', action='store_true'); args = ap.parse_args()
    approved = json.loads((LIB/'provenance/standard-expressions.json').read_text(encoding='utf-8'))
    accepted = {e['id'] for e in approved['entries']}
    record = {'note': __doc__.splitlines()[0], 'faces': FACES, 'models': {}}
    for ident in SPECIES+list(SAME_AS):
        source = LIB/'assembled'/SAME_AS.get(ident, ident)/'textures'
        assert (source/'neutral.png').read_bytes() == (TEXTURES/ident/'neutral.png').read_bytes(), f'{ident}: runtime atlas differs from the standard export'
        entry = {}
        for runtime, standard in FACES.items():
            assert standard in accepted, standard
            data = (source/f'{standard}.png').read_bytes()
            target = TEXTURES/ident/f'{runtime}.png'
            if args.check: assert target.read_bytes() == data, f'stale {target}'
            else: target.write_bytes(data)
            entry[runtime] = {'source': (source/f'{standard}.png').relative_to(ROOT).as_posix(), 'runtime': target.relative_to(ROOT).as_posix(), 'sha256': sha(data)}
        record['models'][ident] = entry
    text = json.dumps(record, ensure_ascii=False, indent=2)+'\n'
    if args.check: assert OUT.read_text(encoding='utf-8') == text, 'stale manifest'
    else: OUT.write_text(text, encoding='utf-8')
    print('faces', 'checked' if args.check else 'written', len(SPECIES)+len(SAME_AS), 'forms')


if __name__ == '__main__':
    main()
