"""Sparkle face: starry, shining eyes for food that delights (the top picky-eating grade).

Project design. The library had open_smile and happy for pleasure but no face that says "this is wonderful!" at a glance,
so the delighted grade borrowed open_smile -> happy:

sparkle  wide eyes (the surprise outline) with the dark iris, a large four-point star in the upper half (white centre,
         pale gold arms), a small second twinkle low on the other side and a warm glow along the lower lid; the normal
         brows, the open smile and the happy blush. The bright star against the dark iris still reads from eight blocks.
         Twinkles drawn outside the eyes (the brow slot) were tried and dropped: the bangs cover them on every form.

Parts are written as expressions/parts/<sha20>.png and registered in expressions/parts/index.json, like the other
tools. Registration in the standard set (index.json, provenance) is done once by hand.

    python standards/yukkuri-library/tools/author_sparkle_face.py [--check]
"""
from pathlib import Path
import argparse, hashlib, json
from PIL import Image
from author_sukkiri_faces import LIB, PALETTE, encode, read, std

# The shared part palette plus the star's pale gold arms (Y).
COLOURS = dict(PALETTE, Y=(255, 232, 140, 255))
ART = {
    # Wide round eye (the surprise outline): dark iris, the star (white centre, gold arms) up and to one side, a small
    # twinkle low on the other, a warm glow on the lower lid. Not mirrored: both eyes catch the light on the same side.
    'eye_sparkle': ['...............', '.....AAAAA.....', '....ACYCCCA....', '...ACCYCCCCA...', '..ACCBBBCCCCA..',
                    '..AYYBBBYYCCA..', '..ACCBBBCCCCA..', '..ACCCYCCCYCA..', '..ACCCYCCYBYA..', '...AFFFFFFYA...',
                    '....AEEEEEA....', '.....AAAAA.....'],
}
# Approved parts reused by hash prefix.
STD = dict(blush_l='4f872d58d2', blush_r='1890bec72e')
# id: (base preset, label, group, slots, note)
FACES = {
    'sparkle': ('open_smile', '闪亮眼', '日常',
                {'face_eye_left': 'eye_sparkle', 'face_eye_right': 'eye_sparkle',
                 'face_blush_left': 'blush_l', 'face_blush_right': 'blush_r'},
                'delighted by food: wide starry eyes (a gold-and-white four-point star in the dark iris), open smile, happy blush'),
}


def draw(rows):
    image = Image.new('RGBA', (len(rows[0]), len(rows)))
    for y, row in enumerate(rows):
        for x, key in enumerate(row):
            if key != '.':
                image.putpixel((x, y), COLOURS[key])
    return image


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
                      recipe=dict(source='tools/author_sparkle_face.py', base=base, note=note),
                      parts=chosen, sources=[], status='accepted', art='Project design; drawn on the standard part grid',
                      aliases=[], origin='project design', review_pair=None,
                      review_decision=dict(status='accepted', date='2026-11-18',
                                           note='项目设计，待美术裁定与用户试玩'))
        files[LIB/'expressions/presets'/(ident+'.json')] = (json.dumps(preset, ensure_ascii=False, indent=2)+'\n').encode()
    files[registry_path] = json.dumps(registry, indent=2).encode()
    return files


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    for path, data in outputs().items():
        if args.check:
            assert path.is_file() and path.read_bytes() == data, 'Outdated sparkle face: '+str(path)
        else:
            path.write_bytes(data)
    print(f'{len(FACES)} sparkle face ' + ('verified' if args.check else 'written'))
