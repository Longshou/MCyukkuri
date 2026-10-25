"""Offline current preview of the den-mouth screen (den_barrier.py); no game or browser required.

Reads the runtime block model and texture and renders them with the care-props render_preview over vanilla dirt
(MCYUKKURI_VANILLA): close up from two sides, in the mouth of a one-block dirt den with an adult Reimu in the den
behind it (near, about eight blocks away, and with the roof cut away), and the same den open for comparison.
Output: <MCYUKKURI_PREVIEWS or .local/previews>/den-barrier/{current.png, index.html}."""
import json, os, sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
sys.path.insert(0, str(ROOT / 'standards/yukkuri-library/drafts/care-props'))
sys.path.insert(0, str(ROOT / 'scripts'))
import render_preview as R
from preview_block_model import mesh

OUT = Path(os.environ.get('MCYUKKURI_PREVIEWS', ROOT / '.local/previews')) / 'den-barrier'
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
FONT = os.environ.get('MCYUKKURI_FONT', 'C:/Windows/Fonts/msyh.ttc')
BG = (226, 228, 222, 255)
EARTH = {'dirt': 'minecraft:block/dirt'}


def barrier():
    m = json.loads((ASSETS / 'models/block/den_barrier.json').read_text(encoding='utf-8'))
    return mesh(m, m['textures'])


def earth(cells):
    """cells: (cx, cy, cz) dirt blocks; the renderer centres the model at (8, 0, 8)."""
    faces = {f: {'texture': '#dirt'} for f in ('up', 'north', 'south', 'west', 'east')}
    return {'elements': [{'from': [cx * 16, cy * 16, cz * 16], 'to': [cx * 16 + 16, cy * 16 + 16, cz * 16 + 16], 'faces': faces}
                         for cx, cy, cz in cells]}


def den(roof=True):
    """A bank of earth two blocks high; the den core is cell (0, 0, 0), its mouth (0, 0, 1) opens on the +Z face."""
    open_cells = {(0, 0, 0), (0, 0, 1)}
    cells = [(x, y, z) for x in (-2, -1, 0, 1, 2) for y in (0, 1) for z in (-2, -1, 0, 1)
             if (x, y, z) not in open_cells and (roof or y == 0 or abs(x) > 1)]
    return mesh(earth(cells), EARTH)


class Scene(R.Source):
    def load(self, name):
        if name == 'barrier':
            return barrier()
        if name.startswith('den'):
            return den(roof=name != 'den:cut')
        return super().load(name)

    def tex_of(self, name, part):
        t = part.get('texture')
        if not t:
            return super().tex_of(name, part)
        return R.vanilla(t) if t.startswith('minecraft:') else self.assets / t.split(':', 1)[1]


SRC = Scene(ROOT)


def tile(items, label, size, ppu, az=210, el=24):
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
    size = (420, 360)
    reimu = ('yuk:reimu:neutral', 8, 0, 0, 0, 1.0, 0)
    mouth = ('barrier', 8, 0, 0, 16, 1)
    tiles = [
        tile([('ground:12', 8, 0, 0, 0, 1), ('barrier', 8, 0, 0, 0, 1)], '近看（西南）', size, 18, az=210),
        tile([('ground:12', 8, 0, 0, 0, 1), ('barrier', 8, 0, 0, 0, 1)], '近看（东北，另一面）', size, 18, az=30),
        tile([('ground:12', 8, 0, 0, 0, 1), ('barrier', 8, 0, 0, 0, 1)], '正面平视', size, 18, az=0, el=6),
        tile([('ground:44', 8, 0, 0, 0, 1), ('den', 8, 0, 0, 0, 1), mouth, reimu], '土洞洞口封上，成体灵梦在洞里', size, 7, az=15, el=14),
        tile([('ground:44', 8, 0, 0, 0, 1), ('den', 8, 0, 0, 0, 1), reimu], '对照：同一洞口未封', size, 7, az=15, el=14),
        tile([('ground:44', 8, 0, 0, 0, 1), ('den:cut', 8, 0, 0, 0, 1), mouth, reimu], '剖开洞顶：灵梦在屏后', size, 7, az=30, el=48),
    ]
    far = tile([('ground:40', 8, 0, 0, 0, 1), ('den', 8, 0, 0, 0, 1), mouth, reimu], '约 8–10 格外的屏幕大小', (630, 300), 5, az=12, el=10)
    far_open = tile([('ground:40', 8, 0, 0, 0, 1), ('den', 8, 0, 0, 0, 1), reimu], '同距离对照：未封', (630, 300), 5, az=12, el=10)
    W, H = 3 * size[0], 2 * size[1] + 300
    sheet = Image.new('RGBA', (W, H), BG)
    for i, im in enumerate(tiles):
        sheet.paste(im, ((i % 3) * size[0], (i // 3) * size[1]))
    sheet.paste(far, (0, 2 * size[1]))
    sheet.paste(far_open, (630, 2 * size[1]))
    sheet.save(OUT / 'current.png')
    (OUT / 'index.html').write_text('<!doctype html><meta charset="utf-8"><title>洞口结界屏障</title><h1>洞口结界屏障</h1>'
                                    '<p>den_barrier 原生方块模型：插进土里互相斜靠的细枝、脚下横放叠压两根、一根横枝搁在枝杈上，'
                                    '枝后推进一层落叶，缝里塞几片叶子；无绳结捆扎，有缝。碰撞 14×16×14。外观待用户确认。</p>'
                                    '<img src="current.png" alt="洞口结界屏障当前预览">', encoding='utf-8')
    print(OUT / 'current.png')


if __name__ == '__main__':
    main()
