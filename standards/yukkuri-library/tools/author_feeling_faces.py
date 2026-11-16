"""Social-feeling faces: sneer, envy, hmph and ashamed, drawn or assembled on the standard part grid.

Project design. The library had no face for looking down on someone, for coveting what another has, for turning the
nose up, or for shame, so those beats borrowed angry, teary, neutral or a smile:

sneer    lidded eyes sitting on the pupil (the sukkiri_smug eyes), the smug brows and a lopsided smirk; no flush.
envy     both eyes cut sideways under a flat, half-lowered lid (the white shows on one side), the smug brows knit over
         them and a small sulking frown.
hmph     eyes shut in a firm downward curve, the smug brows and a flat turned-down mouth: the nose turned up.
ashamed  the look_down eyes (downcast), the worried teary brows, the hatched flush with its bridge over the nose and the
         wavering cold mouth; approved parts only.

Parts are written as expressions/parts/<sha20>.png and registered in expressions/parts/index.json, like the other
tools. Registration in the standard set (index.json, provenance) is done once by hand.

    python standards/yukkuri-library/tools/author_feeling_faces.py [--check]
"""
from pathlib import Path
import argparse, hashlib, json
from author_sukkiri_faces import LIB, draw, encode, read, std

ART = {
    # Side glance: a flat half-lowered lid, the white on one side and the iris pushed to the other. Not mirrored: both
    # eyes look the same way.
    'eye_side': ['...............', '...............', '...............', '...............', '..AAAAAAAAAAA..',
                 '...ABBBCCCCA...', '...ABBBCBCCA...', '...ABBBCCCCA...', '...ABBBCCCCA...', '....ABBCCCA....',
                 '.....AEEEA.....', '......AAA......'],
    # Shut in a firm downward curve, the corners turned up.
    'eye_shut': ['...............', '...............', '...............', '...............', '...............',
                 '...............', '..A.........A..', '..AA.......AA..', '...AAAAAAAAA...', '...............',
                 '...............', '...............'],
    # Lopsided smirk: flat on one side, the other corner pulled up.
    'mouth_smirk': ['..............', '..............', '..............', '..........AA..', '.........AA...',
                    '...A....AA....', '....AAAAA.....', '..............', '..............', '..............',
                    '..............', '..............'],
    # Flat mouth with both corners turned down.
    'mouth_frown': ['..............', '..............', '..............', '.....AAAA.....', '....AA..AA....',
                    '....A....A....', '..............', '..............', '..............', '..............',
                    '..............', '..............'],
    # Small sulking frown.
    'mouth_sulk': ['..............', '..............', '..............', '......AA......', '.....AAAA.....',
                   '....AA..AA....', '..............', '..............', '..............', '..............',
                   '..............', '..............'],
}
# Approved parts reused by hash prefix.
STD = dict(smug_eye_l='4e7eae754f', smug_eye_r='8ea6755ac7', smug_brow_l='4a98859cf9', smug_brow_r='87649084e1',
           worry_brow_l='38cbe6444a', worry_brow_r='72256f4d77', flush='cef8e0a1eb', bridge='78921e8cff',
           waver='ef9406d62e', blank='fd1165af5d')
SMUG_BROWS = {'face_brow_left': 'smug_brow_l', 'face_brow_right': 'smug_brow_r'}
# id: (base preset, label, group, slots, note)
FACES = {
    'sneer': ('neutral', '嘲讽', '生气 / 鼓脸',
              {'face_eye_left': 'smug_eye_l', 'face_eye_right': 'smug_eye_r', **SMUG_BROWS, 'face_mouth': 'mouth_smirk'},
              'looking down on someone: lidded eyes on the pupil, smug brows, a lopsided smirk'),
    'envy': ('neutral', '眼红', '生气 / 鼓脸',
             {'face_eye_left': 'eye_side', 'face_eye_right': 'eye_side', **SMUG_BROWS, 'face_mouth': 'mouth_sulk'},
             'coveting what another has: a side glance under a flat lid, knit brows, a small sulking frown'),
    'hmph': ('neutral', '撇嘴不理', '生气 / 鼓脸',
             {'face_eye_left': 'eye_shut', 'face_eye_right': 'eye_shut', **SMUG_BROWS, 'face_mouth': 'mouth_frown'},
             'turning the nose up: eyes shut in a firm curve, smug brows, a flat turned-down mouth'),
    'ashamed': ('look_down', '羞愧', '惊吓 / 困惑',
                {'face_brow_left': 'worry_brow_l', 'face_brow_right': 'worry_brow_r', 'face_blush_left': 'flush',
                 'face_blush_right': 'flush', 'face_mouth': 'waver', 'face_effect_mouth': 'bridge'},
                'shame: downcast eyes, worried brows, the hatched flush across the nose, a wavering mouth'),
}


def outputs():
    files, registry_path = {}, LIB/'expressions/parts/index.json'
    registry = read(registry_path)
    parts = {}
    for name, rows in ART.items():
        image = draw(rows)
        path, data = encode(image)
        files[LIB/path] = data
        registry[Path(path).stem] = dict(path=path, size=list(image.size), sha256=hashlib.sha256(data).hexdigest())
        parts[name] = path
    index = read(LIB/'expressions/index.json')
    for ident, (base, label, group, slots, note) in FACES.items():
        chosen = dict(read(LIB/index['presets'][base])['parts'])
        for slot, value in slots.items():
            chosen[slot] = parts.get(value) or std(STD[value])
        preset = dict(id=ident, label=label, group=group,
                      recipe=dict(source='tools/author_feeling_faces.py', base=base, note=note),
                      parts=chosen, sources=[], status='accepted', art='Project design; drawn on the standard part grid',
                      aliases=[], origin='project design', review_pair=None,
                      review_decision=dict(status='accepted', date='2026-11-16'))
        files[LIB/'expressions/presets'/(ident+'.json')] = (json.dumps(preset, ensure_ascii=False, indent=2)+'\n').encode()
    files[registry_path] = json.dumps(registry, indent=2).encode()
    return files


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    for path, data in outputs().items():
        if args.check:
            assert path.is_file() and path.read_bytes() == data, 'Outdated feeling face: '+str(path)
        else:
            path.write_bytes(data)
    print(f'{len(FACES)} feeling faces ' + ('verified' if args.check else 'written'))
