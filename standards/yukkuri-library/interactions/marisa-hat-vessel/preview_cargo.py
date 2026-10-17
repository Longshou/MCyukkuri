"""Offline study of the hat-boat provisions (帽船载粮); no game, browser or Java compilation.

Places the authored hat_cargo_* servings in the brim berths of hat_vessel.json cargo.berths (the slots the server reserves and
HatCargoRenderer draws) on the current hat mesh, at hull scale 1 and bowl squash, and writes .local/previews/hat-boat-cargo/.
Wheat uses a stand-in colour for the vanilla sprite it references at runtime.
"""
import math
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
import preview_cabin as PC
from preview_cabin import H, P, ROOT, Cabin

OUT = ROOT / '.local/previews/hat-boat-cargo'
KINDS = {'apple': 'hat_cargo_apple', 'berries': 'hat_cargo_berries', 'carrot': 'hat_cargo_carrot', 'bread': 'hat_cargo_bread',
         'cookie': 'hat_cargo_cookie', 'melon': 'hat_cargo_melon', 'grass': 'hat_cargo_grass', 'tall grass': 'hat_cargo_tall_grass',
         'wheat': 'hat_cargo_wheat', 'candy': 'hat_cargo_candy', 'pudding': 'hat_cargo_pudding'}
MIXED = ['apple', 'apple', 'berries', 'bread', 'apple', 'wheat', 'cookie', 'carrot']


def scene(cabin, kinds, young, parent=True):
    servings = [cabin.serving(KINDS[k], i) for i, k in enumerate(kinds)]
    for i, mesh in enumerate(servings):
        if not cabin.clear(mesh): print(f'note: serving {i} ({kinds[i]}) dips into a wall or ledge edge by under a texel')
    hull = cabin.hull.reshape(-1, 3, 8)
    pieces = [hull.reshape(-1, 8)]
    if kinds: pieces.append(cabin.mat(len(kinds)))
    pieces.extend(servings)
    if parent: pieces.append(cabin.parent)
    if young: pieces.extend(cabin.young)
    return np.concatenate(pieces).reshape(-1, 3, 8)


def render(mesh, texture, az, el, span, w, h, center):
    H.W, H.H = w, h
    pixels, _, _ = H.rasterize(mesh, texture, az=az, el=el, span=span, center_y=center[1], center_x=center[0], center_z=center[2])
    return Image.fromarray(pixels)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    cabin = Cabin()
    H.BG = (237, 241, 237)
    font = lambda n: P.preview_font(n)
    texture = np.array(cabin.atlas.image)
    brim = cabin.vessel['bowl']['freeboard']
    # -- close-ups of the side-brim heaps, growing from 1 to 8 servings (right brim in front, left behind the hull) ----
    rows = [('1 serving', ['apple']), ('3: apples', ['apple'] * 3), ('6: floor of leaf pads', ['apple', 'apple', 'berries', 'bread', 'wheat', 'cookie']),
            ('8: mixed provisions', MIXED)]
    close = Image.new('RGB', (620 * 4, 1240), '#edf1ed'); draw = ImageDraw.Draw(close)
    for i, (label, kinds) in enumerate(rows):
        mesh = scene(cabin, kinds, False)
        for r, (az, center) in enumerate(((-1.35, (5.8, brim - 2.5, 2.5)), (1.35, (-5.45, brim - 2.5, 2.5)))):
            close.paste(render(mesh, texture, az, .55, 7.5, 620, 560, center), (i * 620, 60 + r * 600))
        draw.text((i * 620 + 16, 14), label, font=font(30), fill='#243c38')
    close.save(OUT / 'heap-closeups.png')
    # -- gameplay distance: ~5 blocks away, slightly above, 1080p-equivalent (hull scale 1.5: 0.094 block per unit) --------
    px_per_unit = 1080 / (2 * 5 * math.tan(math.radians(35))) * cabin.vessel['aquatic_scale'] / 16
    span = 1080 / (2 * px_per_unit)
    cases = [('Captain aboard, 2 servings, 2 children', ['apple', 'berries'], True),
             ('Captain aboard, 8 servings (full load), 2 children', MIXED, True)]
    game = Image.new('RGB', (1600, 56 + 2 * 520 + 60 + 520), '#edf1ed'); gd = ImageDraw.Draw(game)
    gd.text((16, 10), 'Gameplay distance: 5 blocks away, slightly above, 1080p crop at 1:1 (hull scale 1.5)', font=font(28), fill='#243c38')
    enlarged = []
    for row, (label, kinds, parent) in enumerate(cases):
        mesh = scene(cabin, kinds, True, parent)
        gd.text((16, 56 + row * 520), label, font=font(22), fill='#243c38')
        for col, az in enumerate((-1.0, -.35)):
            crop = render(mesh, texture, az, .5, span, 1440, 1080, (0, 0, -.5)).crop((320, 330, 1120, 750))
            game.paste(crop, (col * 800, 86 + row * 520))
            if col == 0: enlarged.append(crop.crop((120, 60, 620, 360)).resize((1000, 600), Image.Resampling.NEAREST))
    gd.text((16, 56 + 2 * 520), 'The same near view enlarged 2x (nearest) so the heap can be read', font=font(22), fill='#243c38')
    for i, big in enumerate(enlarged): game.paste(big.resize((800, 480), Image.Resampling.NEAREST), (i * 800, 86 + 2 * 520))
    game.save(OUT / 'gameplay-distance.png')
    # -- whole-hat overview (captain + children + 8 servings, with the largest load a seated captain allows) -------------
    whole = render(scene(cabin, MIXED, True, True), texture, -1.0, .75, 13, 1400, 900, (0, 0, 0))
    sheet = Image.new('RGB', (1400, 960), '#edf1ed'); sd = ImageDraw.Draw(sheet)
    sd.text((16, 10), 'Whole boat: captain, two children, all 8 servings heaped on the side brims', font=font(30), fill='#243c38')
    sheet.paste(whole, (0, 60)); sheet.save(OUT / 'hat-boat-cargo.png')
    # -- every serving on its own -------------------------------------------------------------------------------------
    kinds = list(KINDS)
    cols = 4; cw, ch = 400, 400
    gallery = Image.new('RGB', (cols * cw, ((len(kinds) + cols - 1) // cols) * ch + 50), '#edf1ed'); gd = ImageDraw.Draw(gallery)
    gd.text((16, 8), 'Servings, one per food kind (about 1.1 hat units across before the 1.8x display scale)', font=font(26), fill='#243c38')
    for n, k in enumerate(kinds):
        m = cabin.prop(KINDS[k]).reshape(-1, 3, 8)
        gallery.paste(render(m, texture, .5, .7, 1.5, cw, ch - 10, (0, .5, 0)), ((n % cols) * cw, 50 + (n // cols) * ch))
        gd.text(((n % cols) * cw + 12, 52 + (n // cols) * ch), k, font=font(24), fill='#243c38')
    gallery.save(OUT / 'servings.png')
    (OUT / 'index.html').write_text('<!doctype html><meta charset="utf-8"><title>Hat-boat cargo</title><style>body{background:#edf1ed;margin:24px}img{max-width:100%;display:block;margin-bottom:16px}</style><a href="../index.html">preview hub</a><img src="gameplay-distance.png"><img src="hat-boat-cargo.png"><img src="heap-closeups.png"><img src="servings.png">', encoding='utf-8')
    PC.build_local_hub()
    print(OUT / 'gameplay-distance.png')


if __name__ == '__main__':
    main()
