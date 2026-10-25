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

    python standards/yukkuri-library/expressions/derived/faces.py [--check]
"""
from pathlib import Path
import argparse, hashlib, io, json, math
from PIL import Image

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
ASSETS = ROOT/'src/main/resources/assets/mcyukkuri'
CLEARED = ('face_tear_left', 'face_tear_right', 'face_effect_mouth')
EYES = ('face_eye_left', 'face_eye_right')
MOUTH = 'face_mouth'
LID = .25


def boxes(model, size):
    V = model['vertices']; out = {}
    for p in model['parts']:
        if p['name'] not in CLEARED+EYES+(MOUTH,): continue
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


def outputs():
    files, records = {}, {}
    for folder in sorted((ASSETS/'textures/entity').iterdir()):
        model_path = ASSETS/'yukkuri'/(folder.name+'.json')
        if not folder.is_dir() or not model_path.is_file() or not (folder/'teary.png').is_file(): continue
        model = json.loads(model_path.read_text(encoding='utf-8'))
        teary = Image.open(folder/'teary.png').convert('RGBA'); neutral = Image.open(folder/'neutral.png').convert('RGBA')
        assert teary.size == neutral.size, folder.name
        found = boxes(model, teary.width); assert set(found) == set(CLEARED+EYES+(MOUTH,)), folder.name
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
        records[folder.name] = {}
        for face, out in faces.items():
            data = io.BytesIO(); out.save(data, format='PNG'); data = data.getvalue()
            target = folder/f'{face}.png'; files[target] = data
            records[folder.name][face] = {'runtime': target.relative_to(ROOT).as_posix(), 'sha256': hashlib.sha256(data).hexdigest()}
        records[folder.name]['from'] = {n: hashlib.sha256((folder/f'{n}.png').read_bytes()).hexdigest() for n in ('teary', 'neutral', 'surprise')}
    manifest = {'status': 'pending_user_review', 'origin': 'project design', 'recipe': {'sad': 'teary atlas, tear and mouth-effect cells from neutral, top quarter of each eye under a flat lid line',
                           'afraid': 'teary atlas, tear and mouth-effect cells from neutral, eyes from surprise with a small dark pupil, a pressed zigzag mouth'},
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
    print(f'sad and afraid faces for {(len(files)-1)//2} models '+('verified' if args.check else 'written'))
