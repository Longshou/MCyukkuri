"""Author remains atlases, skin overlays and ground decals from recipe.json.

Inputs are accepted standard dead faces (assembled/<species>/textures) on each
selected rounded body's own atlas. Masks come from the runtime mesh UVs, so only
texels the body really samples are recoloured. Outputs go to assembled/ next to
this file; export.py selects them for the game. --check is read-only.
"""
import argparse
import hashlib
import io
import json
import math
import random
import sys
from pathlib import Path
from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
LIB = HERE.parents[1]
ROOT = LIB.parents[1]
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
OUT = HERE / 'assembled'
sys.path.insert(0, str(LIB / 'drafts/body-marks/hair-wounds'))
from dry_face import apply_style as dry_style, face_boxes  # noqa: E402

RECIPE = json.loads((HERE / 'recipe.json').read_text(encoding='utf-8'))
PALETTE = {k: tuple(v) for k, v in RECIPE['palette'].items()}


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8-sig'))


def sha(data):
    return hashlib.sha256(data).hexdigest()


# ---------------------------------------------------------------- colour operations
def recolour(rgb, op):
    r, g, b = (float(c) for c in rgb)
    if 'toward' in op:
        t, a = op['toward'], op['amount']
        r, g, b = r + (t[0] - r) * a, g + (t[1] - g) * a, b + (t[2] - b) * a
    if 'desaturate' in op:
        y = .299 * r + .587 * g + .114 * b
        d = op['desaturate']
        r, g, b = r + (y - r) * d, g + (y - g) * d, b + (y - b) * d
    if 'darken' in op:
        k = 1 - op['darken']
        r, g, b = r * k, g * k, b * k
    if 'lighten' in op:
        k = op['lighten']
        r, g, b = r + (255 - r) * k, g + (255 - g) * k, b + (255 - b) * k
    return tuple(max(0, min(255, round(c))) for c in (r, g, b))


# ---------------------------------------------------------------- masks from the runtime mesh
def part_classes(ident, model):
    living = read(ASSETS / 'yukkuri_presentation/living.json')['variants']
    sub = read(ASSETS / 'yukkuri_presentation/subspecies.json')
    prefixes = (sub.get(ident) or living.get(ident) or {}).get('accessory_prefixes', [])
    result = {}
    for part in model['parts']:
        name = part['name']
        if name.startswith('face_'):
            continue
        if name.startswith(('mamu_', 'peni_')):
            continue
        if name in RECIPE['skin_parts']:
            result[name] = 'skin'
        elif any(name.startswith(p) for p in prefixes):
            result[name] = 'accessory'
        else:
            result[name] = 'hair'
    return result


def masks(ident, model):
    size = model['texture_size']
    layers = {k: Image.new('L', (size, size)) for k in ('skin', 'hair', 'accessory')}
    draws = {k: ImageDraw.Draw(v) for k, v in layers.items()}
    vertices = model['vertices']
    classes = part_classes(ident, model)
    for part in model['parts']:
        kind = classes.get(part['name'])
        if not kind:
            continue
        for first in range(part['start'], part['start'] + part['count'], 3):
            tri = [(vertices[(first + j) * 8 + 3] * size, vertices[(first + j) * 8 + 4] * size) for j in range(3)]
            xs, ys = [p[0] for p in tri], [p[1] for p in tri]
            if max(xs) - min(xs) < 1 and max(ys) - min(ys) < 1:
                # A collapsed island sits on a texel corner; nearest sampling can hit any
                # texel around each vertex, so every one of them gets the colour.
                for x0, y0 in tri:
                    for x in {math.floor(x0 - 1e-3), math.floor(x0 + 1e-3)}:
                        for y in {math.floor(y0 - 1e-3), math.floor(y0 + 1e-3)}:
                            if 0 <= x < size and 0 <= y < size:
                                draws[kind].point((x, y), fill=255)
            else:
                draws[kind].polygon([(x - .5, y - .5) for x, y in tri], fill=255)
    return layers


# ---------------------------------------------------------------- atlases
def body_sources(ident):
    cfg = read(LIB / 'body-standard/recipe.json')['sources']
    spec = cfg['reimu_wasa' if ident == 'reimu_wasa_deibu' else ident]
    return spec, LIB / spec['export'] / 'textures'


def face_atlas(ident, expression, boxes):
    """The selected body atlas carrying the accepted standard face (the rounded exporter's derivation)."""
    spec, folder = body_sources(ident)
    own = folder / f'{expression}.png'
    derived = ident in ('reimu_wasa', 'reimu_deibu', 'reimu_wasa_deibu') or not own.exists()
    if not derived:
        return Image.open(own).convert('RGBA'), own
    donor = LIB / 'assembled' / spec['base'] / 'textures' / f'{expression}.png'
    atlas = Image.open(folder / 'neutral.png').convert('RGBA')
    image = Image.open(donor).convert('RGBA')
    for box in boxes.values():
        atlas.paste(image.crop(box), box[:2])
    return atlas, donor


def apply_masked(atlas, mask, op):
    if not op:
        return
    px, mk = atlas.load(), mask.load()
    for y in range(atlas.size[1]):
        for x in range(atlas.size[0]):
            if mk[x, y] and px[x, y][3]:
                r, g, b, a = px[x, y]
                px[x, y] = (*recolour((r, g, b), op), a)


def skin_colour(atlas, mask):
    px, mk = atlas.load(), mask.load()
    for y in range(atlas.size[1]):
        for x in range(atlas.size[0]):
            if mk[x, y] and px[x, y][3]:
                return px[x, y][:3]
    return (240, 214, 170)


def face_pass(atlas, boxes, skin, blush_fade, ink_fade=0.0):
    px = atlas.load()
    for name, box in boxes.items():
        for y in range(box[1], box[3]):
            for x in range(box[0], box[2]):
                r, g, b, a = px[x, y]
                if not a:
                    continue
                if name.startswith('face_blush_'):
                    if blush_fade >= 1:
                        px[x, y] = (0, 0, 0, 0)
                    elif blush_fade > 0:
                        px[x, y] = (round(r + (skin[0] - r) * blush_fade), round(g + (skin[1] - g) * blush_fade), round(b + (skin[2] - b) * blush_fade), a)
                elif ink_fade > 0:
                    if name.startswith(('face_tear_', 'face_effect_')):
                        # Washed out: tears and effect marks are gone, the rest is a faint ghost of the face.
                        px[x, y] = (0, 0, 0, 0)
                    else:
                        px[x, y] = (round(r + (skin[0] - r) * ink_fade), round(g + (skin[1] - g) * ink_fade), round(b + (skin[2] - b) * ink_fade), a)


def face_adapt(atlas, boxes, spec, skin, ident):
    """Small authored changes on the accepted face: sunken slit eyes, glazed pupils, cleared tears, a borrowed slack mouth."""
    px = atlas.load()
    paint = spec.get('face_paint')
    if spec.get('mouth_from'):
        donor, _ = face_atlas(ident, spec['mouth_from'], boxes)
        for name in ('face_mouth', 'face_effect_mouth'):
            atlas.paste(donor.crop(boxes[name]), boxes[name][:2])
    if spec.get('tears') == 'clear':
        for name in ('face_tear_left', 'face_tear_right'):
            box = boxes[name]
            atlas.paste(Image.new('RGBA', (box[2] - box[0], box[3] - box[1])), box[:2])
    for name in ('face_eye_left', 'face_eye_right'):
        box = boxes[name]
        w, h = box[2] - box[0], box[3] - box[1]
        if paint == 'sunken':
            # Lifeless lids: a flat slit in a greyed hollow, no sleeping arc.
            part = Image.new('RGBA', (w, h))
            d = ImageDraw.Draw(part)
            hollow = tuple(round(c * .74 + 118 * .26) for c in skin)
            d.line([(3, 6), (11, 6)], fill=(*hollow, 255))
            d.line([(4, 8), (10, 8)], fill=(*hollow, 255))
            d.line([(2, 7), (12, 7)], fill=(70, 58, 54, 255))
            d.point((12, 8), fill=(70, 58, 54, 255))
            if name.endswith('right'):
                part = part.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
            atlas.paste(part, box[:2])
        elif paint == 'glaze':
            # Frozen eyes: the dark pupil turns a filmed, icy grey-blue.
            for y in range(box[1], box[3]):
                for x in range(box[0], box[2]):
                    r, g, b, a = px[x, y]
                    if a and r + g + b < 330:
                        px[x, y] = (round(r + (150 - r) * .5), round(g + (172 - g) * .5), round(b + (196 - b) * .5), a)


def author_atlas(ident, spec, model, layers, boxes):
    atlas, source = face_atlas(ident, spec['face'], boxes)
    if spec.get('dry_grade'):
        atlas = dry_style(atlas, boxes, spec['face'], spec['dry_grade'])
    apply_masked(atlas, layers['skin'], spec.get('skin'))
    apply_masked(atlas, layers['hair'], spec.get('hair'))
    apply_masked(atlas, layers['accessory'], spec.get('accessory'))
    skin = skin_colour(atlas, layers['skin'])
    face_adapt(atlas, boxes, spec, skin, ident)
    if spec.get('hair_ash'):
        # Singed tips: each hair swatch maps its lower rows to the lower edge of the hair faces. The bottom row scorches
        # brown-grey, the row above partly, and small ash clusters sit just above it, so the burn rises from the char crust.
        rng = random.Random(RECIPE['seed'] + len(ident))
        px, mk = atlas.load(), layers['hair'].load()
        scorch, ash = PALETTE['scorch'], PALETTE['ash']

        def toward(x, y, colour, amount):
            r, g, b, a = px[x, y]
            px[x, y] = (round(r + (colour[0] - r) * amount), round(g + (colour[1] - g) * amount), round(b + (colour[2] - b) * amount), a)

        width, height = atlas.size
        for x in range(width):
            y = 0
            while y < height:
                if not (mk[x, y] and px[x, y][3]):
                    y += 1
                    continue
                start = y
                while y < height and mk[x, y] and px[x, y][3]:
                    y += 1
                bottom = y - 1
                if bottom - start < 4:
                    continue
                toward(x, bottom, scorch, .7)
                if rng.random() < .55:
                    toward(x, bottom - 1, scorch, .45)
                if rng.random() < spec['hair_ash']:
                    row = bottom - rng.choice((2, 3))
                    for dx, dy in ((0, 0), (1, 0), (0, -1))[:rng.choice((2, 3))]:
                        if 0 <= x + dx < width and mk[x + dx, row + dy] and px[x + dx, row + dy][3]:
                            px[x + dx, row + dy] = (*ash, px[x + dx, row + dy][3])
    face_pass(atlas, boxes, skin, spec.get('blush_fade', 0), spec.get('face_fade', 0))
    return atlas, source


# ---------------------------------------------------------------- cylindrical skin overlays (u around, front at .5; v top -> bottom)
def overlay(name):
    w, h = RECIPE['overlay_size']
    rng = random.Random(RECIPE['seed'] + sum(map(ord, name)))
    image = Image.new('RGBA', (w, h))
    px = image.load()
    P = PALETTE

    def put(x, y, c):
        if 0 <= y < h:
            px[x % w, y] = (*c, 255)

    def front(x, y=6):
        # Keep marks out of the eyes and mouth; the cheeks and chin stay available.
        return abs(((x + .5) / w) - .5) < .075 and 3 <= y <= 10

    def blob(cx, cy, rx, ry, colour):
        for y in range(int(cy - ry) - 1, int(cy + ry) + 2):
            for x in range(int(cx - rx) - 1, int(cx + rx) + 2):
                if ((x - cx) / rx) ** 2 + ((y - cy) / ry) ** 2 < 1 and not front(x % w, y):
                    put(x, y, colour)

    def side():
        # The visible flanks either side of the face, then anywhere round the back.
        return rng.choice((rng.uniform(.2, .4), rng.uniform(.6, .8), rng.uniform(0, 1))) * w

    if name == 'wrinkle':
        for _ in range(28):
            x, y = rng.randrange(w), rng.randrange(3, h - 2)
            if front(x, y):
                continue
            for k in range(rng.choice((2, 3, 3, 4))):
                yy = y + (1 if k in (0, 3) else 0)
                put(x + k, yy, P['crease_dark'])
                if rng.random() < .6:
                    put(x + k, yy + 1, P['crease'])
    elif name == 'crack':
        for i in range(9):
            x = round(i * w / 9 + rng.randrange(3))
            y = h - 1
            for _ in range(rng.randrange(3, 6)):
                put(x, y, P['anko'])
                if rng.random() < .5:
                    put(x + 1, y, P['anko_light'])
                y -= 1
                x += rng.choice((-1, 0, 0, 1))
    elif name == 'char':
        # Soot and char: black crust from the sole up, ash-grey flakes higher on the body.
        for x in range(w):
            top = h - 1 - rng.choice((2, 3, 3, 4, 5))
            for y in range(top, h):
                put(x, y, P['char'] if rng.random() < .8 else P['ash'])
        for _ in range(16):
            blob(side(), rng.uniform(4, h - 4), rng.uniform(1.2, 2.6), rng.uniform(.8, 1.6), P['char'])
        for _ in range(26):
            x, y = rng.randrange(w), rng.randrange(1, h - 3)
            if not front(x, y):
                put(x, y, P['ash'])
    elif name == 'gash':
        # Filling seeping from cuts on the flanks and running down to the sole.
        for _ in range(5):
            x, y = round(side()), rng.randrange(5, 9)
            for k in range(rng.randrange(4, h - y)):
                put(x, y + k, P['anko'])
                if k < 2:
                    put(x + 1, y + k, P['anko_light'])
        for x in range(w):
            if rng.random() < .35:
                put(x, h - 1, P['anko'])
    elif name == 'damp':
        for _ in range(60):
            x, y = rng.randrange(w), min(h - 1, round(rng.triangular(1, h, h)))
            if front(x, y):
                continue
            put(x, y, P['damp'])
        for _ in range(10):
            x, y = rng.randrange(w), rng.randrange(1, 6)
            if not front(x, y):
                put(x, y, P['damp_shine'])
        # A wet hem: water still running off the lower edge in short drips.
        for x in range(w):
            put(x, h - 1, P['drowned_drip'])
            if x % 3 == 0:
                put(x, h - 2, P['drowned_drip'])
            if x % 7 == 2:
                for k in range(2, rng.randrange(3, 6)):
                    put(x, h - 1 - k, P['damp'])
    elif name == 'frost':
        # Rime caps the crown and gathers on the flanks; the cheeks stay mostly clear.
        for x in range(w):
            for y in range(rng.choice((2, 3, 3, 4))):
                put(x, y, P['frost'] if rng.random() < .7 else P['frost_blue'])
        for _ in range(22):
            blob(side(), rng.uniform(2, h - 2), rng.uniform(1.0, 2.2), rng.uniform(.8, 1.6), P['frost'] if rng.random() < .6 else P['frost_blue'])
        for x in range(w):
            if rng.random() < .6:
                put(x, h - 1, P['frost_blue'])
    elif name == 'sallow':
        for _ in range(30):
            blob(side(), rng.uniform(2, h - 2), rng.uniform(1.0, 2.4), rng.uniform(.8, 1.5), P['sallow'] if rng.random() < .7 else P['bruise'])
        for _ in range(9):
            # Small mould clusters: a green-grey core with pale fuzz.
            cx, cy = side(), rng.uniform(3, h - 2)
            blob(cx, cy, 1.4, 1.1, P['mould'])
            if not front(round(cx + 1) % w, round(cy) - 1):
                put(round(cx) + 1, round(cy) - 1, P['frost'])
    elif name == 'soaked':
        # Waterlogged dough: a few large soft blotches and a swollen pale hem.
        for _ in range(9):
            blob(rng.uniform(0, w), rng.uniform(2, h - 2), rng.uniform(3.5, 6.0), rng.uniform(2.0, 3.2), P['soak'])
        for y in range(h - 3, h):
            for x in range(w):
                put(x, y, P['soak'])
        for _ in range(8):
            x, y = rng.randrange(w), rng.randrange(3, h - 4)
            if not front(x, y):
                for k in range(3):
                    put(x + k, y + (k == 1), P['soak_fold'])
    elif name == 'melt':
        # The skin runs: dough drips from the shoulder down, the lower band opens onto filling.
        for x in range(w):
            start = rng.randrange(1, 6)
            length = rng.randrange(4, 11)
            if front(x, start):
                start = 11
            for y in range(start, min(h, start + length)):
                put(x, y, P['dough'] if (x * 3 + y) % 5 else P['dough_shade'])
        for _ in range(14):
            x, y = rng.randrange(w), rng.randrange(h - 5, h - 1)
            for dx in range(rng.randrange(2, 4)):
                put(x + dx, y, P['anko'])
                put(x + dx, y + 1, P['anko_light'])
        for x in range(w):
            put(x, h - 1, P['dough_shade'])
    return image


def decal(name):
    import math
    s = RECIPE['decal_size']
    rng = random.Random(RECIPE['seed'] * 7 + sum(map(ord, name)))
    image = Image.new('RGBA', (s, s))
    px = image.load()
    P = PALETTE
    c = (s - 1) / 2
    lobes = [(rng.uniform(0, 6.283), rng.uniform(.75, 1.0)) for _ in range(7)]

    def edge(angle, size=.62):
        # Irregular blob radius: a few soft lobes, never a perfect disc.
        value = size
        for phase, weight in lobes:
            value += .07 * weight * math.cos(angle * 3 + phase)
        return value * c

    if name == 'leak':
        # One pool seeping out from under the body on the front-right side, with a trickle back to it.
        ox, oy, r = c + 8.5, c - 7.5, 5.5
        for y in range(s):
            for x in range(s):
                d, a = math.hypot(x - ox, y - oy), math.atan2(y - oy, x - ox)
                if d < r * (1 + .18 * math.cos(a * 3 + 1.3)):
                    px[x, y] = (*(P['anko_light'] if d > r * .8 and (x + y) % 2 else P['anko']), 255)
        for k in range(10):
            x, y = round(c + (ox - c) * k / 10), round(c + (oy - c) * k / 10)
            px[x, y] = (*P['anko'], 255)
            px[x, y + 1] = (*P['anko'], 255)
        return image
    for y in range(s):
        for x in range(s):
            dx, dy = x - c, y - c
            d, a = math.hypot(dx, dy), math.atan2(dy, dx)
            if name == 'splat':
                limit = edge(a)
                if d < limit * .78:
                    px[x, y] = (*P['anko'], 255)
                elif d < limit and (x + y) % 3:
                    px[x, y] = (*P['anko_light'], 255)
            else:
                # Dissolved residue: a solid pale dough puddle with filling swirled through it; no loose specks.
                limit = edge(a, .8)
                if d < limit * .6 and (math.sin(x * .9) + math.cos(y * .8)) > .9:
                    px[x, y] = (*P['anko_light'], 255)
                elif d < limit * .82:
                    px[x, y] = (*P['dough_shade'], 255) if (x * 5 + y * 3) % 11 == 0 else (*P['dough'], 255)
                elif d < limit:
                    px[x, y] = (*P['dough_shade'], 255)
    if name == 'splat':
        for _ in range(9):
            a = rng.uniform(0, 6.283)
            r = edge(a) + rng.uniform(1.5, 3.5)
            x, y = round(c + math.cos(a) * r), round(c + math.sin(a) * r)
            if 0 <= x < s and 0 <= y < s:
                px[x, y] = (*P['anko'], 255)
    return image


# ---------------------------------------------------------------- build
def outputs():
    files, records = {}, {}
    entries = dict(RECIPE['forms'])
    entries['dissolving'] = RECIPE['water']['dissolving']
    for ident in RECIPE['models']:
        model = read(ASSETS / 'yukkuri' / f'{ident}.json')
        boxes = face_boxes(model)
        layers = masks(ident, model)
        records[ident] = {}
        for name, spec in entries.items():
            atlas, source = author_atlas(ident, spec, model, layers, boxes)
            buffer = io.BytesIO()
            atlas.save(buffer, format='PNG', optimize=True)
            path = OUT / ident / f'remains_{name}.png'
            files[path] = buffer.getvalue()
            records[ident][name] = {'face': spec['face'], 'face_source': source.relative_to(ROOT).as_posix(), 'sha256': sha(buffer.getvalue())}
    shared = {}
    for name in sorted({s['overlay'] for s in list(RECIPE['forms'].values()) + list(RECIPE['water'].values()) if s.get('overlay')}):
        buffer = io.BytesIO()
        overlay(name).save(buffer, format='PNG')
        files[OUT / 'shared' / f'overlay_{name}.png'] = buffer.getvalue()
        shared[f'overlay_{name}'] = sha(buffer.getvalue())
    for name in sorted({s['decal']['name'] for s in list(RECIPE['forms'].values()) + list(RECIPE['water'].values()) if s.get('decal')}):
        buffer = io.BytesIO()
        decal(name).save(buffer, format='PNG')
        files[OUT / 'shared' / f'decal_{name}.png'] = buffer.getvalue()
        shared[f'decal_{name}'] = sha(buffer.getvalue())
    manifest = {'recipe_sha256': sha((HERE / 'recipe.json').read_bytes()), 'builder_sha256': sha(Path(__file__).read_bytes().replace(b'\r\n', b'\n')),
                'standard_confirmation': 'standards/yukkuri-library/provenance/standard-expressions.json', 'atlases': records, 'shared': shared}
    files[OUT / 'manifest.json'] = (json.dumps(manifest, ensure_ascii=False, indent=2) + '\n').encode('utf-8')
    return files


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check', action='store_true')
    args = parser.parse_args()
    accepted = {e['id']: e for e in read(LIB / 'provenance/standard-expressions.json')['entries']}
    for spec in list(RECIPE['forms'].values()) + [RECIPE['water']['dissolving']]:
        assert spec['face'] in accepted, 'Not an accepted standard expression: ' + spec['face']
    files = outputs()
    for path, data in files.items():
        if args.check:
            assert path.is_file() and path.read_bytes() == data, 'Outdated remains art: ' + str(path)
        else:
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_bytes(data)
    print(f'{len(files)} remains files ' + ('verified' if args.check else 'written'))


if __name__ == '__main__':
    main()
