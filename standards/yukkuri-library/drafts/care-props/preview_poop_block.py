"""Offline current preview of the four droppings-heap tiers (poop_block.py); no game or browser required.

Reads the runtime block models and texture, renders them with render_preview over vanilla dirt (MCYUKKURI_VANILLA), close
up, from play distance, inside a one-block dug pit and beside an adult for scale. Output:
<MCYUKKURI_PREVIEWS or .local/previews>/poop-block/{current.png, index.html}."""
import json, os, sys
from pathlib import Path
import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(ROOT / 'scripts'))
import render_preview as R
from preview_block_model import mesh

OUT = Path(os.environ.get('MCYUKKURI_PREVIEWS', ROOT / '.local/previews')) / 'poop-block'
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
FONT = os.environ.get('MCYUKKURI_FONT', 'C:/Windows/Fonts/msyh.ttc')
BG = (226, 228, 222, 255)
DIRT = {'dirt': 'minecraft:block/dirt'}


def tier_model(t):
    return json.loads((ASSETS / f'models/block/yukkuri_poop_tier{t}.json').read_text(encoding='utf-8'))


def dirt_cells(cells):
    """cells: (cx, cz, top) block columns one block deep; the renderer centres the model at (8, 0, 8)."""
    faces = {f: {'texture': '#dirt'} for f in ('up', 'north', 'south', 'west', 'east')}
    return {'elements': [{'from': [cx * 16, top - 16, cz * 16], 'to': [cx * 16 + 16, top, cz * 16 + 16], 'faces': faces}
                         for cx, cz, top in cells]}


class Scene(R.Source):
    def load(self, name):
        kind, *arg = name.split(':')
        if kind == 'tier':
            return mesh(tier_model(int(arg[0])), tier_model(int(arg[0]))['textures'])
        if kind == 'flat':      # n x n cells of dirt, top at y 0
            n = int(arg[0])
            return mesh(dirt_cells([(x, z, 0) for x in range(-(n // 2), n // 2 + 1) for z in range(-(n // 2), n // 2 + 1)]), DIRT)
        if kind == 'pit':       # 3 x 3 dirt with the middle cell dug one block down
            cells = [(x, z, -16 if (x, z) == (0, 0) else 0) for x in (-1, 0, 1) for z in (-1, 0, 1)]
            return mesh(dirt_cells(cells), DIRT)
        return super().load(name)

    def tex_of(self, name, part):
        t = part.get('texture')
        if not t:
            return super().tex_of(name, part)
        return R.vanilla(t) if t.startswith('minecraft:') else self.assets / t.split(':', 1)[1]


SRC = Scene(ROOT)


def tile(items, label, size, ppu, az=210, el=30):
    im = Image.new('RGBA', size, BG)
    ImageDraw.Draw(im).text((8, 5), label, font=ImageFont.truetype(FONT, 15), fill=(0, 0, 0, 255))
    r, _ = R.render(SRC, items, az=az, el=el, ppu=ppu)
    k = min((size[0] - 10) / r.width, (size[1] - 34) / r.height)
    if k < 1:
        r = r.resize((max(1, int(r.width * k)), max(1, int(r.height * k))), Image.LANCZOS)
    im.paste(r, ((size[0] - r.width) // 2, 30 + (size[1] - 30 - r.height) // 2), r)
    return im


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    size = (420, 330)
    close = [tile([('ground:9', 8, 0, 0, 0, 1), (f'tier:{t}', 8, 0, 0, 0, 1)], f'{t} 档（{2 * t - 1}–{2 * t} 份）近看', size, 18)
             for t in range(1, 5)]
    row = [('ground:40', 8, 0, 0, 0, 1)] + [(f'tier:{t}', 8, (2.5 - t) * 16, 0, 0, 1) for t in range(1, 5)]
    far = tile(row, '四档并排（左 1 → 右 4），约 8 格外', (840, 330), 4.2, az=180, el=40)
    pit = tile([('pit', 8, 0, 0, 0, 1), ('tier:4', 8, 0, -16, 0, 1)], '挖下一格的坑底：4 档', size, 12, az=200, el=68)
    pit2 = tile([('pit', 8, 0, 0, 0, 1), ('tier:2', 8, 0, -16, 0, 1)], '坑底：2 档', size, 12, az=200, el=68)
    scale = tile([('flat:3', 8, 0, 0, 0, 1), ('tier:4', 8, -7, 0, 0, 1), ('yuk:reimu:neutral', 8, 9, 0, 2, 1.0, 20),
                  ('tier:1', 8, 22, 0, -6, 1)], '与成体灵梦同框（4 档、1 档）', (840, 330), 7, az=200, el=24)
    cols = [close[:2] + close[2:3], [close[3], pit, pit2]]
    W, H = 3 * size[0], 2 * size[1] + 2 * 330
    sheet = Image.new('RGBA', (W, H), BG)
    for j, line in enumerate(cols):
        for i, im in enumerate(line):
            sheet.paste(im, (i * size[0], j * size[1]))
    sheet.paste(far, (0, 2 * size[1]))
    sheet.paste(scale, (0, 2 * size[1] + 330))
    sheet.save(OUT / 'current.png')
    (OUT / 'index.html').write_text('<!doctype html><meta charset="utf-8"><title>嗯嗯方块分档</title><h1>嗯嗯方块分档</h1>'
                                    '<p>yukkuri_poop 四档原生方块模型：1 档一小坨、2 档两坨、3 档一小堆、4 档几乎铺满格底的高堆；'
                                    '高度 2／4／6／8 单位，不超过选择框，无碰撞。外观待用户确认。</p>'
                                    '<img src="current.png" alt="嗯嗯方块四档当前预览">', encoding='utf-8')
    print(OUT / 'current.png')


if __name__ == '__main__':
    main()
