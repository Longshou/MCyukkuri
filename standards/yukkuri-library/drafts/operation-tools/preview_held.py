"""Offline self-check of the 3D tool models (local preview only; nothing here is shipped).

Writes .local/previews/<topic>/: each tool as held (hand space, three views) beside its GUI sprite, and the bound tools
at their body contact on every runtime form at rest: pin and rotor on the renderer's surface triangle (same rule as
YukkuriModel.locateSurface), the hook from the contact-pose surface point, and the fitted restraint board, sling and iron
box. Uses the care-props software renderer; reads the current exported resources.

    python preview_held.py [--topic oni-tools-v2] [--forms reimu,marisa,...]
"""
import argparse
import io
import json
import os
import sys
from pathlib import Path

import numpy as np
from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(ROOT / 'standards/yukkuri-library/drafts/care-props'))
import held                                          # noqa: E402
import render_preview as R                           # noqa: E402

ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
SKIN = ('soft_head_slice_', 'house_body_surface', 'predator_body_surface', 'rounded_body_surface', 'deficient_body_surface')


class HeldSource(R.Source):
    """Adds 'held:<id>:<frame>' meshes straight from held.py (hand or contact frame)."""
    def load(self, name):
        if name.startswith('held:'):
            _, ident, frame = name.split(':')
            rows = held.triangles(ident, frame, hand_only=frame != 'contact')
            arr = np.array(rows, dtype=np.float32)
            return {'parts': [{'name': 'model', 'start': 0, 'count': len(rows)}],
                    'bounds': [[float(arr[:, i].min()), float(arr[:, i].max())] for i in range(3)]}, arr
        return super().load(name)

    def tex_of(self, name, part):
        if name.startswith('held:'):
            ident = name.split(':')[1]
            return ASSETS / f'textures/item/{ident}_model.png'
        return super().tex_of(name, part)


def surface_frame(form):
    """Skin triangle and basis exactly as YukkuriModel.locateSurface / renderSurfaceProp (rest pose)."""
    data = json.loads((ASSETS / f'yukkuri/{form}.json').read_text(encoding='utf-8'))
    v = data['vertices']
    house = form.startswith('house_')
    target = (1.2, 1.4, -1.65) if house else (3.6, 2.2, -4.8)
    best, tri = 1e18, None
    for part in data['parts']:
        if not part['name'].startswith(SKIN):
            continue
        for first in range(part['start'], part['start'] + part['count'], 3):
            c = [sum(v[(first + i) * 8 + k] for i in range(3)) / 3 for k in range(3)]
            d = sum((c[k] - target[k]) ** 2 for k in range(3))
            if d < best:
                best, tri = d, [v[(first + i) * 8:(first + i) * 8 + 3] for i in range(3)]
    p = np.array(tri, dtype=np.float64)
    origin = p.mean(axis=0)
    normal = np.cross(p[1] - p[0], p[2] - p[0]); normal /= np.linalg.norm(normal)
    if normal[2] > 0:
        normal = -normal
    right = np.cross(normal, [0, 1, 0]); right /= np.linalg.norm(right)
    up = np.cross(right, normal); up /= np.linalg.norm(up)
    return origin, np.stack([right, normal, up], axis=1), (.22 if house else .65)


class Placed(HeldSource):
    """A contact mesh moved onto a form's skin frame (model units, 16 per block)."""
    def __init__(self, root, ident, form, scale):
        super().__init__(root)
        self.ident, self.form, self.scale = ident, form, scale

    def load(self, name):
        if name != 'placed':
            return super().load(name)
        data, arr = super().load(f'held:{self.ident}:contact')
        origin, basis, factor = surface_frame(self.form)
        arr = arr.copy()
        arr[:, :3] = arr[:, :3] @ basis.T * factor * self.scale + origin + basis[:, 1] * .03
        arr[:, 5:8] = arr[:, 5:8] @ basis.T
        return data, arr

    def tex_of(self, name, part):
        if name == 'placed':
            return ASSETS / f'textures/item/{self.ident}_model.png'
        return super().tex_of(name, part)


def png(image):
    stream = io.BytesIO(); image.save(stream, format='PNG'); return stream.getvalue()


def label(image, text):
    canvas = Image.new('RGBA', (image.width, image.height + 22), (232, 228, 218, 255))
    canvas.paste(image, (0, 0), image)
    ImageDraw.Draw(canvas).text((6, image.height + 4), text, fill=(40, 50, 45, 255))
    return canvas


def hand_sheet(src):
    tiles = []
    for ident in held.HELD:
        sprite = Image.open(ASSETS / f'textures/item/{ident}.png').convert('RGBA').resize((128, 128), Image.NEAREST)
        row = [sprite]
        for az, el in ((180, 0), (215, 25), (110, 20)):
            image, _ = R.render(src, [(f'held:{ident}:hand', 9, 0, 0, 0, 1)], az=az, el=el, ppu=8, pad=1.2, bg=(198, 198, 198, 255))
            image.thumbnail((170, 170), Image.NEAREST)
            row.append(image)
        strip = Image.new('RGBA', (128 + 3 * 174 + 8, 174), (232, 228, 218, 255))
        x = 2
        for image in row:
            strip.paste(image, (x, (174 - image.height) // 2)); x += image.width + 4
        tiles.append(label(strip, f'{ident}: GUI sprite | hand model (three views)'))
    sheet = Image.new('RGBA', (tiles[0].width, sum(t.height for t in tiles)), (232, 228, 218, 255))
    y = 0
    for tile in tiles:
        sheet.paste(tile, (0, y)); y += tile.height
    return sheet


def contact_tile(src_root, form, scale_of):
    items = [(f'yuk:{form}:neutral', 9, 0, 0, 0, 1)]
    sources = []
    for ident, scale in scale_of.items():
        s = Placed(src_root, ident, form, scale)
        items.append(('placed', 9, 0, 0, 0, 1, 0, s))
    base = R.Source(src_root)
    image, _ = R.render(base, items, az=150, el=12, ppu=9, size=(240, 230), bg=(214, 222, 214, 255))
    return image


PAGE = """<!doctype html><meta charset="utf-8"><title>鬼威惨工具 2：手持与身上的立体模型</title>
<style>body{font-family:sans-serif;background:#e8e4da;margin:24px;color:#253c35}img{image-rendering:pixelated;max-width:100%}</style>
<h1>鬼威惨工具 2：手持与身上的立体模型</h1>
<p>剪刀类（理发器、裁剪刀、去势剪、两种剪茎剪刀）并入原版剪刀，加热锥子是锥子烧热后的样子。剩下八种工具：物品栏里仍是图标，
手里换成同样配色的立体模型；大头针、振动器、鱼钩束缚时身上是同一个模型，固定台、吊带、铁箱按体型贴合并用同一套材质。</p>
<p>请决定：手里的立体模型和身上的模型方向是否可用。</p>
<h2>图标与手持模型（三视角）</h2><img src="hand.png">
<h2>身上：各形态的大头针、振动器、吊带与铁箱（静止姿态）</h2><img src="contact.png">
"""


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--topic', default='oni-tools-v2')
    parser.add_argument('--forms', default='')
    args = parser.parse_args()
    out = Path(os.environ.get('MCYUKKURI_PREVIEWS', ROOT / '.local/previews')) / args.topic
    out.mkdir(parents=True, exist_ok=True)
    src = HeldSource(ROOT)
    hand_sheet(src).save(out / 'hand.png')
    forms = [f for f in args.forms.split(',') if f] or sorted(p.stem[len('iron_enclosure_'):]
                                                              for p in (ASSETS / 'yukkuri_props').glob('iron_enclosure_*.json'))
    tiles = []
    for form in forms:
        row = []
        for ident, scale in (('pin_tool', .6), ('care_vibrator', 1)):
            row.append(label(contact_tile(ROOT, form, {ident: scale}), f'{form} · {ident}'))
        for fitted in ('restraint_frame', 'wrap_sling', 'iron_enclosure'):
            name = f'{fitted}_{form}'
            items = [(f'yuk:{form}:neutral', 9, 0, 0, 0, 1)]
            lift = 0
            if fitted == 'restraint_frame':
                continue                     # drawn supine in game; the fitted board is checked by build.py
            items.append((name, 9, 0, lift, 0, 1))
            image, _ = R.render(R.Source(ROOT), items, az=150, el=12, ppu=9, size=(240, 230), bg=(214, 222, 214, 255))
            row.append(label(image, f'{form} · {fitted}'))
        strip = Image.new('RGBA', (sum(t.width + 4 for t in row), max(t.height for t in row)), (232, 228, 218, 255))
        x = 0
        for tile in row:
            strip.paste(tile, (x, 0)); x += tile.width + 4
        tiles.append(strip)
    sheet = Image.new('RGBA', (max(t.width for t in tiles), sum(t.height + 4 for t in tiles)), (232, 228, 218, 255))
    y = 0
    for tile in tiles:
        sheet.paste(tile, (0, y)); y += tile.height + 4
    sheet.save(out / 'contact.png')
    (out / 'index.html').write_text(PAGE, encoding='utf-8')
    print(f'Wrote {out / "hand.png"} and {out / "contact.png"} ({len(forms)} forms)')


if __name__ == '__main__':
    main()
