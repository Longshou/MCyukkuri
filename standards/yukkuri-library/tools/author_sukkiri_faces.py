"""Sukkiri faces: new face components drawn on the standard part grid, their presets, and the short tongue.

Project design (user-approved proposal, sukkiri-plan page): the release peak shout, the dazed after-face, Marisa's
smug afterglow, Alice's rolled-eye grin and her manic grin, and the mother's tender closing face. The long hanging
tongue (part 37ac1b...) is replaced by a short rounded tongue tip in every preset that used it (flushed_tongue,
teary_tongue and its B frame). Style references (wiki/art-references): EH27-007 lower panel (flat lid over the pupil,
small soft mouth), EH17-002 top panel (the ":3" smug mouth and blush), M02-003 (lidded smug Marisa), EH16-002 and
EH06-002 (mating drawn with flushed faces and body contact, no explicit detail).

Parts are written as expressions/parts/<sha20>.png and registered in expressions/parts/index.json, like the other
tools. Registration in the standard set (index.json, provenance) is done once by hand.

    python standards/yukkuri-library/tools/author_sukkiri_faces.py [--check]
"""
from pathlib import Path
import argparse, hashlib, io, json
from PIL import Image

LIB = Path(__file__).resolve().parents[1]
LONG_TONGUE = 'expressions/parts/37ac1b379663c1f0195e.png'
PALETTE = dict(A=(72, 53, 46, 255), B=(255, 248, 233, 255), C=(50, 41, 35, 255), E=(118, 82, 57, 255),
               F=(150, 112, 84, 255), G=(196, 158, 122, 255), M=(166, 84, 72, 255), T=(220, 137, 116, 255),
               L=(240, 170, 150, 255))
ART = {
    # Eyes rolled up: only the lower edge of the iris left under the upper lid.
    'eye_rolled': ['...............', '.....AAAAA.....', '....ACCCCCA....', '...ABCCCCCBA...', '..ABBEEEEEBBA..',
                   '..ABBBBBBBBBA..', '..ABBBBBBBBBA..', '..ABBBBBBBBBA..', '..ABBBBBBBBBA..', '...ABBBBBBBA...',
                   '....ABBBBBA....', '.....AAAAA.....'],
    # Manic: wide white eye with a shrunken pupil.
    'eye_manic': ['...............', '.....AAAAA.....', '....ABBBBBA....', '...ABBBBBBBA...', '..ABBBBBBBBBA..',
                  '..ABBBBCCBBBA..', '..ABBBBCCBBBA..', '..ABBBBBBBBBA..', '..ABBBBBBBBBA..', '...ABBBBBBBA...',
                  '....ABBBBBA....', '.....AAAAA.....'],
    # Smug: a flat, slightly flicked upper lid sitting on the pupil, the shine kept (EH27-007, M02-003).
    'eye_smug': ['...............', '...............', '...............', '...............', '..AAAAAAAAAA...',
                 '...ACCCCCCCA...', '...ACBBCCCCA...', '...ACBCCCCCA...', '....ACCCCBA....', '.....EEEEA.....',
                 '......AAA......', '...............'],
    # Dazed: heavy lids, the pupil drifted up under the lid, a lighter lower iris and a small low shine.
    'eye_dazed': ['...............', '...............', '...............', '...............', '...............',
                  '...AAAAAAAAA...', '...AFFCCCFFA...', '...AGGGGGGGA...', '...AGGGGGBGA...', '....AGGGGGA....',
                  '.....AAAAA.....', '...............'],
    # Tender: softly closed, the lid curving down like a content sleeper.
    'eye_tender': ['...............', '...............', '...............', '...............', '...............',
                   '...A.......A...', '....AA...AA....', '......AAA......', '...............', '...............',
                   '...............', '...............'],
    # Short round tongue tip poking just past the lip of a small rounded mouth (no outline under the tip).
    'mouth_tongue': ['..............', '..............', '..............', '....AAAAAA....', '...AMMMMMMA...',
                     '...AMMMMMMA...', '....AATTAA....', '....ATTLTA....', '.....TTTT.....', '..............',
                     '..............', '..............'],
    # Slack, softly open small mouth.
    'mouth_slack': ['..............', '..............', '..............', '.....AAAA.....', '....AMMMMA....',
                    '....AMMMMA....', '....AMTTMA....', '.....AAAA.....', '..............', '..............',
                    '..............', '..............'],
    # The yukkuri ":3" smug mouth (EH17-002), closed.
    'mouth_cat': ['..............', '..............', '..............', '..............', '..A...AA...A..',
                  '..A..A..A..A..', '...AA....AA...', '..............', '..............', '..............',
                  '..............', '..............'],
    # Wide, round-bottomed laughing mouth; the tongue stays inside.
    'mouth_grin': ['..............', '.AAAAAAAAAAAA.', '.AMMMMMMMMMMA.', '.AMMMMMMMMMMA.', '.AMMMMMMMMMMA.',
                   '..AMMTTTTMMA..', '..AMTTLTTTMA..', '...ATTTTTTA...', '....AAAAAA....', '..............',
                   '..............', '..............'],
    # A small soft closed smile.
    'mouth_soft': ['..............', '..............', '..............', '..............', '..............',
                   '....A....A....', '.....AAAA.....', '..............', '..............', '..............',
                   '..............', '..............'],
}
STD = dict(flush='cef8e0a1eb', bridge='78921e8cff', blank='fd1165af5d', blush='4f872d58d2', relaxed='af2d4cf86a')
# id: (base preset, label, slots, note). Eye names get a mirrored right part.
FACES = {
    'sukkiri_squeeze': ('impact', '潮红挤眼喊', {'blush': 'flush', 'face_effect_mouth': 'bridge'},
                        'release peak: impact eyes, brows and shout on the flushed blush and blush bridge'),
    'sukkiri_dazed': ('open_smile', '失神', {'eye': 'eye_dazed', 'blush': 'flush', 'face_mouth': 'mouth_slack',
                                           'face_effect_mouth': 'blank'},
                      'after release: heavy lids, the pupil drifted up, slack small mouth, flushed; no drool'),
    'sukkiri_smug': ('smug', '潮红得意', {'eye': 'eye_smug', 'blush': 'flush', 'face_mouth': 'mouth_cat',
                                       'face_effect_mouth': 'blank'},
                     'Marisa afterglow: flat lid on the pupil, the yukkuri :3 mouth, flushed'),
    'sukkiri_grin': ('blank_happy', '翻白眼咧嘴笑', {'eye': 'eye_rolled', 'blush': 'flush', 'face_mouth': 'mouth_grin',
                                                  'face_effect_mouth': 'blank'},
                     'Alice in the act: eyes rolled up, wide laughing mouth, flushed (manic, evil)'),
    'sukkiri_manic': ('blank_happy', '癫狂咧嘴笑', {'eye': 'eye_manic', 'blush': 'flush', 'face_mouth': 'mouth_grin',
                                                 'face_effect_mouth': 'blank'},
                      'Alice hunting and sated afterwards: wide eyes with shrunken pupils, the same wide grin'),
    'sukkiri_tender': ('satisfied', '温柔满足', {'eye': 'eye_tender', 'blush': 'blush', 'brow': 'relaxed',
                                               'face_mouth': 'mouth_soft', 'face_effect_mouth': 'blank'},
                       'mother closing: softly closed eyes, a small soft smile, a light blush'),
}


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def encode(im):
    name = 'expressions/parts/' + hashlib.sha256(im.tobytes()).hexdigest()[:20] + '.png'
    buf = io.BytesIO(); im.save(buf, format='PNG')
    return name, buf.getvalue()


def draw(rows):
    im = Image.new('RGBA', (len(rows[0]), len(rows)))
    for y, row in enumerate(rows):
        for x, key in enumerate(row):
            if key != '.':
                im.putpixel((x, y), PALETTE[key])
    return im


def std(prefix):
    return 'expressions/parts/' + next((LIB/'expressions/parts').glob(prefix+'*.png')).name


def outputs():
    files, registry_path = {}, LIB/'expressions/parts/index.json'
    registry = read(registry_path)
    parts = {}
    for name, rows in ART.items():
        im = draw(rows)
        variants = {name: im}
        if name.startswith('eye_'):
            variants[name+'_r'] = im.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
        for key, image in variants.items():
            path, data = encode(image)
            files[LIB/path] = data
            registry[Path(path).stem] = dict(path=path, size=list(image.size), sha256=hashlib.sha256(data).hexdigest())
            parts[key] = path
    index = read(LIB/'expressions/index.json')
    for ident, (base, label, slots, note) in FACES.items():
        source = read(LIB/index['presets'][base])
        chosen = dict(source['parts'])
        for slot, value in slots.items():
            path = parts.get(value) or std(STD[value])
            if slot in ('eye', 'blush', 'brow'):
                right = parts.get(value+'_r', path)
                chosen[f'face_{slot}_left'], chosen[f'face_{slot}_right'] = path, right
            else:
                chosen[slot] = path
        preset = dict(id=ident, label=label, group='特殊状态',
                      recipe=dict(source='tools/author_sukkiri_faces.py', base=base, note=note),
                      parts=chosen, sources=[], status='accepted', art='Project design; drawn on the standard part grid',
                      aliases=[], origin='project design', review_pair=None,
                      review_decision=dict(status='accepted', date='2026-11-12',
                                           note='用户认可素鸡立表情方案（sukkiri-plan 第二稿与修改意见）'))
        if base == 'impact':
            preset['geometry_offsets'] = source['geometry_offsets']
        files[LIB/'expressions/presets'/(ident+'.json')] = (json.dumps(preset, ensure_ascii=False, indent=2)+'\n').encode()
    # The short tongue replaces the long hanging tongue wherever it was used.
    for ident in ('flushed_tongue', 'teary_tongue', 'teary_tongue_b'):
        path = LIB/index['presets'][ident]
        preset = read(path)
        if preset['parts']['face_mouth'] in (LONG_TONGUE, parts['mouth_tongue']):
            preset['parts']['face_mouth'] = parts['mouth_tongue']
        files[path] = json.dumps(preset, ensure_ascii=False, indent=2).encode()
    registry.pop(Path(LONG_TONGUE).stem, None)  # no preset uses the long tongue any more
    files[registry_path] = json.dumps(registry, indent=2).encode()
    return files


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    for path, data in outputs().items():
        if args.check:
            assert path.is_file() and path.read_bytes() == data, 'Outdated sukkiri face: '+str(path)
        else:
            path.write_bytes(data)
    print(f'{len(FACES)} sukkiri faces and the short tongue ' + ('verified' if args.check else 'written'))
