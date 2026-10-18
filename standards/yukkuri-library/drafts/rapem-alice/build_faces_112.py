"""Composite the 1.12 adult-Alice face frames onto the project atlas.

Eye and brow pixels are taken verbatim (downscaled) from the user-provided
八景 ver1.12 sheets; frame choices are recorded in face-design.json under
frames_112. The labeled inventory for reviewing choices is produced by
inventory_faces_112.py.
"""
from pathlib import Path
import json
from PIL import Image

HERE = Path(__file__).parent
ROOT = HERE.parents[3]
PICT = ROOT / '油库里八景ver1.12/油库里八景ver1.12/pict'
BASE = ROOT / 'standards/yukkuri-library/assembled/alice/textures/neutral.png'
DESIGN = HERE / 'face-design.json'

TILE = 240
REGIONS = {'leftEye': (58, 0, 15, 12), 'rightEye': (73, 0, 15, 12),
           'leftBrow': (102, 0, 17, 6), 'rightBrow': (102, 6, 17, 6)}


def tile(path, index):
    im = Image.open(path).convert('RGBA')
    y = index * TILE
    return im.crop((0, y, TILE, y + TILE))


def content(tile_img, threshold=40):
    from PIL import ImageChops
    bg = Image.new('RGBA', tile_img.size, (0, 0, 0, 0))
    diff = ImageChops.difference(tile_img, bg)
    return diff.getbbox()


def crop_feature(path, index):
    t = tile(path, index)
    box = content(t)
    assert box, f'empty frame {index} in {path.name}'
    return t.crop(box)


def fit_into(atlas, region, feature, mirror=False):
    ox, oy, w, h = region
    # Preserve the original aspect: match width, clamp height into the patch, center vertically.
    scale = w / feature.width
    height = max(1, min(h, round(feature.height * scale)))
    size = (w, height)
    resized = feature.resize(size, Image.Resampling.BOX)
    if mirror:
        resized = resized.transpose(Image.Transpose.FLIP_LEFT_RIGHT)
    y = oy + (h - height) // 2
    atlas.alpha_composite(resized, (ox, y))


def clear(atlas, region):
    ox, oy, w, h = region
    empty = Image.new('RGBA', (w, h), (0, 0, 0, 0))
    atlas.alpha_composite(empty, (ox, oy))


def build_face(eye_frame, brow_frame, out_name):
    atlas = Image.open(BASE).convert('RGBA')
    eyes = PICT / 'arisu/sei_new/6.png'
    brows = PICT / 'arisu/sei_new/7.png'
    eye = crop_feature(eyes, eye_frame)
    brow = crop_feature(brows, brow_frame)
    for side in ('left', 'right'):
        clear(atlas, REGIONS[side + 'Eye'])
        clear(atlas, REGIONS[side + 'Brow'])
    for side in ('left', 'right'):
        mirror = side == 'right'
        fit_into(atlas, REGIONS[side + 'Eye'], eye, mirror)
        fit_into(atlas, REGIONS[side + 'Brow'], brow, mirror)
    out = HERE / 'variant' / f'{out_name}.png'
    atlas.save(out)
    print('wrote', out.name, f'(eye #{eye_frame} {eye.size}, brow #{brow_frame} {brow.size})')


def main():
    design = json.loads(DESIGN.read_text(encoding='utf-8'))
    frames = design.get('frames_112', {})
    build_face(frames.get('breeding_period_eye', 0), frames.get('breeding_period_brow', 0), 'breeding_period')
    build_face(frames.get('alice_agitated_eye', 50), frames.get('alice_agitated_brow', 25), 'alice_agitated')


if __name__ == '__main__':
    main()
