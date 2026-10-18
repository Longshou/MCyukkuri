"""Composite the 1.12 adult-Alice face frames onto the project atlas.

Sheet structure (verified against the package):
- arisu/sei_new/6.png,7.png are RGBA, 240px rows; each logical frame is a
  LEFT/RIGHT twin pair (rows 2F and 2F+1), and HSP 表示枚数 indexes the pair
  (eye 50 -> rows 50/51, brow 54 -> rows 54/55).
- base/sei/8.png,9.png are RGB pairs (colour 0-240, alpha 240-480) whose
  表示枚数 maps to row index//2 (mouth 132 -> row 66, cheek 28 -> row 14).

Features are cover-fitted into the model's face patches (fill the patch,
centre-crop) so the original pixel density and placement are preserved.
Frame choices live in face-design.json under frames_112.
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
           'leftBrow': (102, 0, 17, 6), 'rightBrow': (102, 6, 17, 6),
           'mouth': (0, 0, 28, 24), 'leftCheek': (88, 11, 10, 6), 'rightCheek': (58, 12, 10, 6)}


def rgba_row(path, row):
    im = Image.open(path)
    y = row * TILE
    if im.mode == 'RGBA':
        return im.crop((0, y, TILE, y + TILE))
    part = im.crop((0, y, TILE, y + TILE)).convert('RGBA')
    part.putalpha(im.crop((TILE, y, TILE * 2, y + TILE)).convert('L'))
    return part


def content(tile_img):
    box = tile_img.getbbox()
    assert box, 'empty frame row'
    return tile_img.crop(box)


def cover(feature, w, h, top_anchored=False):
    """Fill the patch completely, cropping the overflow (centred, or from the top)."""
    scale = max(w / feature.width, h / feature.height)
    scaled = feature.resize((max(1, round(feature.width * scale)), max(1, round(feature.height * scale))),
                            Image.Resampling.BOX)
    x = (scaled.width - w) // 2
    y = 0 if top_anchored else (scaled.height - h) // 2
    return scaled.crop((x, y, x + w, y + h))


def paste(atlas, region, feature, top_anchored=False):
    ox, oy, w, h = region
    atlas.alpha_composite(cover(feature, w, h, top_anchored), (ox, oy))


def clear(atlas, region):
    ox, oy, w, h = region
    atlas.alpha_composite(Image.new('RGBA', (w, h), (0, 0, 0, 0)), (ox, oy))


def build_face(frames, out_name, ecchi):
    if out_name == 'breeding_period':
        # Ordinary observation uses Alice's current authored neutral atlas.
        # Copying it also retains her hair palette and character-local blue irises.
        (HERE / 'variant' / 'breeding_period.png').write_bytes(BASE.read_bytes())
        return
    atlas = Image.open(BASE).convert('RGBA')
    eyes = PICT / 'arisu/sei_new/6.png'
    brows = PICT / 'arisu/sei_new/7.png'
    mouth = PICT / 'base/sei/8.png'
    cheek = PICT / 'base/sei/9.png'
    eye_frame, brow_frame = frames['eye'], frames['brow']
    for side in ('left', 'right'):
        clear(atlas, REGIONS[side + 'Eye'])
        clear(atlas, REGIONS[side + 'Brow'])
        clear(atlas, REGIONS[side + 'Cheek'])
    # Left/right twins are composited from their own rows; no mirroring.
    paste(atlas, REGIONS['leftEye'], content(rgba_row(eyes, eye_frame * 2)))
    paste(atlas, REGIONS['rightEye'], content(rgba_row(eyes, eye_frame * 2 + 1)))
    paste(atlas, REGIONS['leftBrow'], content(rgba_row(brows, brow_frame * 2)), top_anchored=True)
    paste(atlas, REGIONS['rightBrow'], content(rgba_row(brows, brow_frame * 2 + 1)), top_anchored=True)
    clear(atlas, REGIONS['mouth'])
    paste(atlas, REGIONS['mouth'], content(rgba_row(mouth, frames['mouth'] // 2)))
    if ecchi:
        # The act blush (1.12 頬=28): the same mark on both cheeks.
        blush = content(rgba_row(cheek, frames['cheek'] // 2))
        paste(atlas, REGIONS['leftCheek'], blush)
        paste(atlas, REGIONS['rightCheek'], blush)
    out = HERE / 'variant' / f'{out_name}.png'
    atlas.save(out)
    print('wrote', out.name, f"(eye F{eye_frame} rows {eye_frame*2}/{eye_frame*2+1}, brow F{brow_frame}, "
                             f"mouth {frames['mouth']}, cheek {frames['cheek'] if ecchi else '-'})")


def build_victim(species, sheet):
    """The pinned victim's face from its own 1.12 sheets: eyes 50 (rows 50/51), a steep
    anguished brow (rows 25/26), the act victim mouth 188 (row 94) and cheek 18 (row 9)."""
    atlas = Image.open(ROOT / 'standards/yukkuri-library/assembled' / species / 'textures/neutral.png').convert('RGBA')
    eyes = PICT / sheet / 'sei_new/6.png'
    brows = PICT / sheet / 'sei_new/7.png'
    mouth, cheek = PICT / 'base/sei/8.png', PICT / 'base/sei/9.png'
    for side in ('left', 'right'):
        clear(atlas, REGIONS[side + 'Eye'])
        clear(atlas, REGIONS[side + 'Brow'])
        clear(atlas, REGIONS[side + 'Cheek'])
    paste(atlas, REGIONS['leftEye'], content(rgba_row(eyes, 50)))
    paste(atlas, REGIONS['rightEye'], content(rgba_row(eyes, 51)))
    paste(atlas, REGIONS['leftBrow'], content(rgba_row(brows, 25)), top_anchored=True)
    paste(atlas, REGIONS['rightBrow'], content(rgba_row(brows, 26)), top_anchored=True)
    clear(atlas, REGIONS['mouth'])
    paste(atlas, REGIONS['mouth'], content(rgba_row(mouth, 94)))
    blush = content(rgba_row(cheek, 9))
    paste(atlas, REGIONS['leftCheek'], blush)
    paste(atlas, REGIONS['rightCheek'], blush)
    out = HERE / 'variant' / f'rapem_victim_{species}.png'
    atlas.save(out)
    print('wrote', out.name)


def main():
    design = json.loads(DESIGN.read_text(encoding='utf-8'))
    f = design['frames_112']
    build_face({'eye': f.get('breeding_period_eye', 0), 'brow': f.get('breeding_period_brow', 0),
                'mouth': f.get('breeding_period_mouth', 0), 'cheek': 28}, 'breeding_period', ecchi=False)
    build_face({'eye': f.get('alice_agitated_eye', 25), 'brow': f.get('alice_agitated_brow', 27),
                'mouth': f.get('alice_agitated_mouth', 132), 'cheek': f.get('alice_agitated_cheek', 28)},
               'alice_agitated', ecchi=True)
    for species, sheet in (('reimu', 'reimu'), ('marisa', 'marisa'), ('alice', 'arisu')):
        build_victim(species, sheet)


if __name__ == '__main__':
    main()
