"""Offline study of the hat-boat provisions (帽船载粮); no game, browser or Java compilation.

Places the authored hat_cargo_* servings in the display slots of care-props/cargo.py (the layout HatCargoRenderer
mirrors) inside the current hat mesh, at hull scale 1 and bowl squash, and writes .local/previews/hat-boat-cargo/.
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
MIXED = ['apple', 'apple', 'apple', 'berries', 'bread', 'cookie', 'carrot', 'melon']


def scene(cabin, kinds, young):
    servings = [cabin.serving(KINDS[k], i) for i, k in enumerate(kinds)]
    for i, mesh in enumerate(servings):
        if not cabin.clear(mesh): print(f'note: serving {i} ({kinds[i]}) dips into a wall or ledge edge by under a texel')
    hull = cabin.hull.reshape(-1, 3, 8)
    pieces = [hull.reshape(-1, 8), *servings]
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
    # ledge area (x 3.8, z -1.8) seen from the bow side and above
    view = dict(az=-1.05, el=.62, span=3.3, center=(2.6, cabin.surface(3.76, -1.8), -1.8))
    whole = dict(az=-1.05, el=.62, span=8.5, center=(0, 0, -1))
    w, h = 700, 640
    rows = [('1 serving', ['apple']), ('3 servings: apples', ['apple'] * 3), ('5 servings: apples and berries', ['apple'] * 3 + ['berries'] * 2),
            ('8 servings: mixed provisions', MIXED)]
    sheet = Image.new('RGB', (w * 4, h + 60 + h + 60 + 60), '#edf1ed'); draw = ImageDraw.Draw(sheet)
    draw.text((20, 12), 'Hat-boat provisions: volumetric servings heaped on the ledge', font=font(34), fill='#243c38')
    for i, (label, kinds) in enumerate(rows):
        mesh = scene(cabin, kinds, False); texture = np.array(cabin.atlas.image)
        sheet.paste(render(mesh, texture, az=view['az'], el=view['el'], span=view['span'], w=w, h=h, center=view['center']), (i * w, 60))
        draw.text((i * w + 20, 66), label, font=font(26), fill='#f0f4ee')
    # whole hat with a young passenger pair, then the same ledge close
    mesh = scene(cabin, MIXED, True); texture = np.array(cabin.atlas.image)
    sheet.paste(render(mesh, texture, az=whole['az'], el=whole['el'], span=whole['span'], w=w * 2, h=h, center=whole['center']), (0, 60 + h + 30))
    draw.text((20, 60 + h + 36), '8 mixed servings with two young passengers (whole hat)', font=font(26), fill='#243c38')
    mesh = scene(cabin, MIXED, True)
    sheet.paste(render(mesh, texture, az=2.1, el=.75, span=8.5, w=w * 2, h=h, center=(0, 0, -1)), (w * 2, 60 + h + 30))
    draw.text((w * 2 + 20, 60 + h + 36), 'same load, from the stern side', font=font(26), fill='#243c38')
    draw.text((20, h * 2 + 100), 'Offline staging of the current authored meshes at hull scale 1; not a game capture. Wheat sprite is a stand-in colour.',
              font=font(22), fill='#526c61')
    sheet.save(OUT / 'hat-boat-cargo.png')
    # every serving on its own, close
    kinds = list(KINDS)
    cols = 4; cw, ch = 400, 400
    gallery = Image.new('RGB', (cols * cw, ((len(kinds) + cols - 1) // cols) * ch + 50), '#edf1ed'); gd = ImageDraw.Draw(gallery)
    gd.text((16, 8), 'Servings, one per food kind (about 1.1 hat units across)', font=font(28), fill='#243c38')
    for n, k in enumerate(kinds):
        m = cabin.prop(KINDS[k]).reshape(-1, 3, 8)
        gallery.paste(render(m, texture, az=.5, el=.7, span=1.5, w=cw, h=ch - 10, center=(0, .5, 0)), ((n % cols) * cw, 50 + (n // cols) * ch))
        gd.text(((n % cols) * cw + 12, 52 + (n // cols) * ch), k, font=font(24), fill='#243c38')
    gallery.save(OUT / 'servings.png')
    (OUT / 'index.html').write_text('<!doctype html><meta charset="utf-8"><title>Hat-boat cargo</title><style>body{background:#edf1ed;margin:24px}img{max-width:100%;display:block;margin-bottom:16px}</style><a href="../index.html">preview hub</a><img src="hat-boat-cargo.png"><img src="servings.png">', encoding='utf-8')
    PC.build_local_hub()
    print(OUT / 'hat-boat-cargo.png')


if __name__ == '__main__':
    main()
