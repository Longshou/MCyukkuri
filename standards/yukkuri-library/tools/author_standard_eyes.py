"""Replace old-style wide-oval eye parts with current-style eyes on the standard 15x12 grid.

Replaces the four legacy wide-oval eye parts (13px wide, 2-3 colour old-draft style with (73, 54, 45) outline or
wide horizontal sclera) with compact eyes matching the current standard palette and proportions (neutral 9x10,
dry_dead 11x9, sukkiri_grin 11x11):

eye_down         look_down, down_angry (replaces 394256bd3d, 13x11)
eye_pin_cry      pin_cry, pin_cry_b, tongue_cry, tongue_cry_b (replaces 1cadef959b, 13x12)
eye_squashed     squashed (replaces b11bb4b57a, 13x11)
eye_dead_tongue  dead_tongue (replaces 5fa59088f4, 13x9)

    python standards/yukkuri-library/tools/author_standard_eyes.py [--check]
"""
from pathlib import Path
import argparse, hashlib, json
from PIL import Image
from author_sukkiri_faces import LIB, PALETTE, encode, read

# Current standard palette plus the approved dry_dead dull-gray tones (H, I, J).
COLOURS = dict(PALETTE, H=(94, 88, 80, 255), I=(130, 124, 114, 255), J=(158, 152, 140, 255))

ART = {
    # Downcast eye: exact 9x10 neutral footprint, upper sclera showing downward gaze, dark iris shifted low.
    'eye_down': ['...............', '...............', '.....AAAAA.....', '....ABBBBBA....', '...ABBBBBBBA...',
                 '...ABCCCCCBA...', '...ACBBCCCCA...', '...ACCCCCCCA...', '...ACCCCCCCA...', '....ACCCCBA....',
                 '.....AEEEA.....', '......AAA......'],
    # Glazed / pin-pupil crying eye: 9x10 neutral footprint, complete outline, dull gray-brown iris with small dark
    # pin pupil and watery catchlight.
    'eye_pin_cry': ['...............', '...............', '.....AAAAA.....', '....ABHHHBA....', '...ABHIIIHBA...',
                    '...AHBBIHIHA...', '...AHIICIIHA...', '...AHIICIIHA...', '...AHIIIIIHA...', '....AIJJJIA....',
                    '.....AIIIA.....', '......AAA......'],
    # Squashed lifeless dull eye: 11x8 matching dry_dead stroke weight and shading, slightly flattened.
    'eye_squashed': ['...............', '...............', '...............', '....AAAAAAA....', '...AHHHHHHHA...',
                     '..AHHIIIIIHHA..', '..AHIIIIIIIHA..', '..AIIIJJJIIIA..', '..AHIIIIIIIHA..', '...AAHHHHHAA...',
                     '.....AAAAA.....', '...............'],
    # Rolled-up dead eye: 11x9 matching sukkiri_grin and dry_dead proportions, dull gray iris tucked under upper lid.
    'eye_dead_tongue': ['...............', '...............', '....AAAAAAA....', '...AHIIIIIHA...', '..AHIICCCIIHA..',
                        '..ABIICCCIIBA..', '..ABBBBBBBBBA..', '..ABBBBBBBBBA..', '...ABBBBBBBA...', '....ABBBBBA....',
                        '.....AAAAA.....', '...............'],
}

OLD_PARTS = (
    '394256bd3d1fd6dedc15',
    '1cadef959b1def31db66',
    'b11bb4b57a0b70246f4f',
    '5fa59088f4db7f73dc8f',
)

UPDATES = {
    'look_down': 'eye_down',
    'down_angry': 'eye_down',
    'drink_lap': 'eye_down',
    'pin_cry': 'eye_pin_cry',
    'pin_cry_b': 'eye_pin_cry',
    'tongue_cry': 'eye_pin_cry',
    'tongue_cry_b': 'eye_pin_cry',
    'squashed': 'eye_squashed',
    'dead_tongue': 'eye_dead_tongue',
}


def draw(rows):
    image = Image.new('RGBA', (len(rows[0]), len(rows)))
    for y, row in enumerate(rows):
        for x, key in enumerate(row):
            if key != '.':
                image.putpixel((x, y), COLOURS[key])
    return image


def outputs():
    files, registry_path = {}, LIB / 'expressions/parts/index.json'
    registry = read(registry_path)
    parts = {}
    for name, rows in ART.items():
        image = draw(rows)
        path, data = encode(image)
        files[LIB / path] = data
        registry[Path(path).stem] = dict(path=path, size=list(image.size), sha256=hashlib.sha256(data).hexdigest())
        parts[name] = path
    for old in OLD_PARTS:
        registry.pop(old, None)
    index = read(LIB / 'expressions/index.json')
    for ident, eye_name in UPDATES.items():
        preset_path = LIB / index['presets'][ident]
        preset = read(preset_path)
        preset['parts']['face_eye_left'] = parts[eye_name]
        preset['parts']['face_eye_right'] = parts[eye_name]
        files[preset_path] = (json.dumps(preset, ensure_ascii=False, indent=2) + '\n').encode()
    files[registry_path] = json.dumps(registry, indent=2).encode()
    return files


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    for path, data in outputs().items():
        if args.check:
            assert path.is_file() and path.read_bytes() == data, 'Outdated standard eye: ' + str(path)
        else:
            path.write_bytes(data)
    if not args.check:
        for old in OLD_PARTS:
            p = LIB / 'expressions/parts' / (old + '.png')
            if p.is_file():
                p.unlink()
    else:
        for old in OLD_PARTS:
            assert not (LIB / 'expressions/parts' / (old + '.png')).exists(), 'Unremoved old eye part: ' + old
    print(f'{len(UPDATES)} presets ({len(ART)} eye parts) ' + ('verified' if args.check else 'written'))
