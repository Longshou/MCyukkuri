"""Derived `sad` and `afraid` face atlases for every runtime model (project design; pending user approval).

The server asks for `sad` (self-rescue SAFE, BOAT_CALM, a stolen treasure) and the aquatic design adds `afraid`, but no
species shipped either. Both are built from the model's own approved atlases, so each species keeps its colours.

sad: a dejected face
is the model's own teary face without the welling tears: the worried brows, the small downturned mouth and its own eyes
and blush, so each species keeps its colours and face shapes, with heavy half-lowered lids (the top quarter of each
eye replaced by a flat lid line in the eye's own darkest colour). The teary atlas's tear and mouth-effect cells are
taken from the same model's neutral atlas; geometry is unchanged (teary has no expression patch).

afraid: the teary face's worried brows with the surprise atlas's wide eyes, each given a small dark pupil at its centre
(shrunken pupils), the mouth redrawn as a pressed, trembling zigzag line of the same width and colour, and no tears.

Excited-leak faces (the manga look the user picked: huge round glossy eyes, a small open mouth, a hatched blush), each
eye redrawn as one big round wet eye in the model's own outline, iris, lower-reflection and catch-light colours, the
mouth redrawn on the 2-texel mouth grid at the box's own scale (predator fangs kept), the brows from the model's own
surprise / teary atlas and the blush from its flushed_smile atlas:
leak_startle: the startled instant - raised brows, small round open mouth, no tears.
leak_flow: while it runs - worried brows, welling tears, a wider open wailing mouth with the tongue showing.
leak_after: dazed and ashamed afterwards - the big eyes half-lidded, welling tears, a wavering mouth.

    python standards/yukkuri-library/expressions/derived/faces.py [--check]
"""
from pathlib import Path
import argparse, hashlib, io, json, math
from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
ASSETS = ROOT/'src/main/resources/assets/mcyukkuri'
CLEARED = ('face_tear_left', 'face_tear_right', 'face_effect_mouth')
EYES = ('face_eye_left', 'face_eye_right')
MOUTH = 'face_mouth'
BROWS = ('face_brow_left', 'face_brow_right')
BLUSH = ('face_blush_left', 'face_blush_right')
LID = .25
LEAK = ('leak_startle', 'leak_flow', 'leak_after')
# One big wet eye (13x12, centred in the 15x12 eye box): A outline, C iris, E lower reflection, B catch-light.
BIG_EYE = ('....AAAAA....', '..AACCCCCAA..', '.ABBBCCCCCCA.', '.ABBBBCCCCCA.', 'ACBBBBCCCCCCA', 'ACCBBCCCCCCCA',
           'ACCCCCCCCCCCA', 'ACCCCCCCBBCCA', '.ACCCCCCBBCA.', '.ACCEEEEECCA.', '..AAEEEEEAA..', '....AAAAA....')
# Mouth grid cells (x, y) of 2x2 texels in a 28x24 box: outline, inside, tongue.
MOUTHS = {
    'leak_startle': ([(11, 4), (13, 4), (15, 4), (9, 6), (17, 6), (9, 8), (17, 8), (11, 10), (13, 10), (15, 10)],
                     [(11, 6), (13, 6), (15, 6), (11, 8), (13, 8), (15, 8)], []),
    'leak_flow': ([(10, 2), (12, 2), (14, 2), (16, 2), (8, 4), (18, 4), (8, 6), (18, 6), (8, 8), (18, 8), (8, 10), (18, 10),
                   (10, 12), (12, 12), (14, 12), (16, 12)],
                  [(10, 4), (12, 4), (14, 4), (16, 4), (10, 6), (12, 6), (14, 6), (16, 6)],
                  [(10, 8), (12, 8), (14, 8), (16, 8), (10, 10), (12, 10), (14, 10), (16, 10)]),
    'leak_after': ([(8, 8), (10, 6), (12, 8), (14, 6), (16, 8), (18, 6)], [], []),
}
FANGS = {'leak_startle': [(11, 6), (15, 6)], 'leak_flow': [(10, 4), (16, 4)], 'leak_after': []}


def boxes(model, size):
    V = model['vertices']; out = {}
    for p in model['parts']:
        if p['name'] not in CLEARED+EYES+(MOUTH,)+BROWS+BLUSH: continue
        front = [p['start']+i for i in range(p['count']) if V[(p['start']+i)*8+7] < -.2]  # the face-on decal only, not its edges
        us = [V[i*8+3] for i in front]; vs = [V[i*8+4] for i in front]
        out[p['name']] = (math.floor(min(us)*size), math.floor(min(vs)*size), math.ceil(max(us)*size), math.ceil(max(vs)*size))
    return out


def lower_lid(image, box):
    px = image.load(); x0, y0, x1, y1 = box
    rows = [y for y in range(y0, y1) if any(px[x, y][3] >= 128 for x in range(x0, x1))]
    if len(rows) < 3: return
    cut = max(1, round(len(rows)*LID)); lid = rows[cut]
    opaque = [px[x, y] for y in rows for x in range(x0, x1) if px[x, y][3] >= 128]
    dark = min(opaque, key=lambda c: c[0]+c[1]+c[2])
    for y in rows[:cut]:
        for x in range(x0, x1): px[x, y] = (0, 0, 0, 0)
    for x in range(x0, x1):
        if px[x, lid][3] >= 128: px[x, lid] = dark


def pupil(image, box, scale):
    px = image.load(); x0, y0, x1, y1 = box
    cells = [(x, y) for y in range(y0, y1) for x in range(x0, x1) if px[x, y][3] >= 128]
    if not cells: return
    opaque = [px[c] for c in cells]; dark = min(opaque, key=lambda c: c[0]+c[1]+c[2])
    cx = (min(x for x, _ in cells)+max(x for x, _ in cells)+1)/2; cy = (min(y for _, y in cells)+max(y for _, y in cells)+1)/2
    size = max(1, round(2*scale))
    for y in range(round(cy-size/2), round(cy-size/2)+size):
        for x in range(round(cx-size/2), round(cx-size/2)+size):
            if px[x, y][3] >= 128: px[x, y] = dark


def tremble_mouth(image, box, scale):
    px = image.load(); x0, y0, x1, y1 = box
    cells = [(x, y) for y in range(y0, y1) for x in range(x0, x1) if px[x, y][3] >= 128]
    if not cells: return
    dark = min((px[c] for c in cells), key=lambda c: c[0]+c[1]+c[2])
    left, right = min(x for x, _ in cells), max(x for x, _ in cells)
    centre = round((min(y for _, y in cells)+max(y for _, y in cells))/2)
    for x, y in cells: px[x, y] = (0, 0, 0, 0)
    step = max(1, round(scale)); width = max(right-left+1, 5*step)
    left = max(x0, (left+right+1)//2-width//2)
    for x in range(left, min(x1, left+width)):
        up = ((x-left)//step) % 2
        for k in range(step):
            y = centre-up*step+k
            if y0 <= y < y1: px[x, y] = dark


def big_eye(image, source, box, lid=0, inner_right=True):
    """The source eye's own colours on one big round wet eye; lid > 0 lowers an upper lid to that row at the inner
    corner, sloping two rows lower to the outer corner (the left box is the face's left eye on screen)."""
    src = source.crop(box); px = src.load(); w, h = src.size
    cells = [px[x, y] for y in range(h) for x in range(w) if px[x, y][3] >= 128]
    if not cells: return
    light = max(cells, key=lambda c: sum(c[:3]))
    rest = [c for c in cells if c != light]
    iris = max(set(rest), key=rest.count) if rest else light
    others = [c for c in rest if c != iris]
    outline = min(others, key=lambda c: sum(c[:3])) if others else iris
    bottom = [px[x, y] for y in range(h) for x in range(w) if px[x, y][3] >= 128 and px[x, y] not in (outline, iris, light)]
    reflect = max(set(bottom), key=bottom.count) if bottom else iris
    roles = {'A': outline, 'C': iris, 'E': reflect, 'B': light}
    part = Image.new('RGBA', (w, h)); out = part.load(); left = (w-len(BIG_EYE[0]))//2
    span = len(BIG_EYE[0])-1
    for y, row in enumerate(BIG_EYE[:h]):
        for x, role in enumerate(row):
            edge = lid+round(2*((span-x) if inner_right else x)/span) if lid else 0
            if role == '.' or y < edge: continue
            out[left+x, y] = outline if lid and y == edge else roles[role]
    image.paste(part, box[:2])


def grid_mouth(image, source, box, face):
    """Redraws the mouth on the 2-texel grid in the source mouth's own outline colour, at the box's scale."""
    src = source.crop(box); px = src.load(); w, h = src.size
    cells = [px[x, y] for y in range(h) for x in range(w) if px[x, y][3] >= 128]
    line = min(cells, key=lambda c: sum(c[:3])) if cells else (128, 80, 68, 255)
    fangs = any(sum(c[:3]) > 650 for c in cells)
    pink = [c for c in cells if c[0] > c[1]*1.4 and sum(c[:3]) > 300]
    tongue = max(set(pink), key=pink.count) if pink else (217, 122, 114, 255)
    unit = w/28; part = Image.new('RGBA', (w, h)); draw = ImageDraw.Draw(part)
    outline, inside, tongue_cells = MOUTHS[face]
    for colour, group in ((line, outline), ((112, 46, 52, 255), inside), (tongue, tongue_cells),
                          ((250, 248, 244, 255), FANGS[face] if fangs else [])):
        for x, y in group:
            draw.rectangle([round(x*unit), round(y*unit), round((x+2)*unit)-1, round((y+2)*unit)-1], fill=colour)
    image.paste(part, box[:2])


def leak_face(face, atlases, found):
    neutral, teary, surprise = atlases['neutral'], atlases['teary'], atlases['surprise']
    out = neutral.copy()
    for name in BROWS: out.paste((surprise if face == 'leak_startle' else teary).crop(found[name]), found[name][:2])
    blush = atlases.get('flushed_smile') or neutral
    for name in BLUSH: out.paste(blush.crop(found[name]), found[name][:2])
    for name in CLEARED: out.paste((neutral if face == 'leak_startle' else teary).crop(found[name]), found[name][:2])
    for name in EYES: big_eye(out, neutral, found[name], 3 if face == 'leak_after' else 0, name.endswith('left'))
    grid_mouth(out, surprise, found[MOUTH], face)
    return out


def outputs():
    files, records = {}, {}
    for folder in sorted((ASSETS/'textures/entity').iterdir()):
        model_path = ASSETS/'yukkuri'/(folder.name+'.json')
        if not folder.is_dir() or not model_path.is_file() or not (folder/'teary.png').is_file(): continue
        model = json.loads(model_path.read_text(encoding='utf-8'))
        teary = Image.open(folder/'teary.png').convert('RGBA'); neutral = Image.open(folder/'neutral.png').convert('RGBA')
        assert teary.size == neutral.size, folder.name
        found = boxes(model, teary.width); assert set(found) == set(CLEARED+EYES+(MOUTH,)+BROWS+BLUSH), folder.name
        surprise = Image.open(folder/'surprise.png').convert('RGBA'); assert surprise.size == teary.size, folder.name
        faces = {}
        sad = teary.copy()
        for name in CLEARED: sad.paste(neutral.crop(found[name]), found[name][:2])
        for name in EYES: lower_lid(sad, found[name])
        faces['sad'] = sad
        afraid = teary.copy()
        for name in CLEARED: afraid.paste(neutral.crop(found[name]), found[name][:2])
        for name in EYES: afraid.paste(surprise.crop(found[name]), found[name][:2]); pupil(afraid, found[name], teary.width/256)
        tremble_mouth(afraid, found[MOUTH], teary.width/256)
        faces['afraid'] = afraid
        atlases = {n: Image.open(folder/f'{n}.png').convert('RGBA') for n in ('neutral', 'teary', 'surprise', 'flushed_smile')
                   if (folder/f'{n}.png').is_file()}
        for face in LEAK: faces[face] = leak_face(face, atlases, found)
        records[folder.name] = {}
        for face, out in faces.items():
            data = io.BytesIO(); out.save(data, format='PNG'); data = data.getvalue()
            target = folder/f'{face}.png'; files[target] = data
            records[folder.name][face] = {'runtime': target.relative_to(ROOT).as_posix(), 'sha256': hashlib.sha256(data).hexdigest()}
        records[folder.name]['from'] = {n: hashlib.sha256((folder/f'{n}.png').read_bytes()).hexdigest() for n in ('teary', 'neutral', 'surprise', 'flushed_smile')
                                         if (folder/f'{n}.png').is_file()}
    manifest = {'status': 'pending_user_review', 'origin': 'project design', 'recipe': {'sad': 'teary atlas, tear and mouth-effect cells from neutral, top quarter of each eye under a flat lid line',
                           'afraid': 'teary atlas, tear and mouth-effect cells from neutral, eyes from surprise with a small dark pupil, a pressed zigzag mouth',
                           'leak_startle': 'neutral atlas, surprise brows, flushed_smile blush, big round wet eyes in the neutral eye colours, a small round open mouth',
                           'leak_flow': 'neutral atlas, teary brows and welling tears, flushed_smile blush, big round wet eyes, a wide open mouth with the tongue',
                           'leak_after': 'neutral atlas, teary brows and welling tears, flushed_smile blush, big wet eyes under a lowered lid, a wavering mouth'},
                'builder': Path(__file__).relative_to(ROOT).as_posix(), 'builder_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
                'models': records}
    files[HERE/'manifest.json'] = (json.dumps(manifest, indent=2)+'\n').encode()
    return files


if __name__ == '__main__':
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('--check', action='store_true'); args = parser.parse_args()
    files = outputs()
    for path, data in files.items():
        if args.check: assert path.is_file() and path.read_bytes() == data, 'Outdated: '+str(path)
        else: path.write_bytes(data)
    print(f'sad, afraid and leak faces for {(len(files)-1)//5} models '+('verified' if args.check else 'written'))
