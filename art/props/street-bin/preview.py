"""Offline review sheet for the stray cardboard carton and the street garbage bin; no game or browser required.

Renders the runtime exports with the care-props render_preview over vanilla dirt (MCYUKKURI_VANILLA): the old and the new
carton side by side at the same scale with two adults in the two sleeping cells, the carton's rain stages, the bin standing and
knocked over next to an adult, and one street mock-up at a viewing distance. The old carton is read from a Git ref (default
the commit before the carton rebuild) by `git archive` of its runtime resources.
Usage: <py> art/props/street-bin/preview.py [--before REF]
Output: <MCYUKKURI_PREVIEWS or .local/previews>/box-bin/{current.png, index.html}."""
import argparse, json, os, subprocess, sys, tarfile, tempfile
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(ROOT / 'standards/yukkuri-library/drafts/care-props'))
sys.path.insert(0, str(ROOT / 'scripts'))
import render_preview as R
from preview_block_model import mesh

OUT = Path(os.environ.get('MCYUKKURI_PREVIEWS', ROOT / '.local/previews')) / 'box-bin'
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
FONT = os.environ.get('MCYUKKURI_FONT', 'C:/Windows/Fonts/msyh.ttc')
BG = (226, 228, 222, 255)


def block(name, turn=0):
    """A native block model as a render_preview mesh, turned like its blockstate y rotation."""
    m = json.loads((ASSETS / f'models/block/{name}.json').read_text(encoding='utf-8'))
    data, v = mesh(m, m['textures'])
    if turn:
        v = v.copy(); r = R.rot_y(-turn)
        v[:, :3] = v[:, :3] @ r.T; v[:, 5:8] = v[:, 5:8] @ r.T
    return data, v


class Scene(R.Source):
    def load(self, name):
        if name.startswith('bin'):          # bin[:tipped][:turn]
            bits = name.split(':')
            return block('garbage_bin_tipped' if 'tipped' in bits else 'garbage_bin', int(bits[-1]) if bits[-1].isdigit() else 0)
        return super().load(name)

    def tex_of(self, name, part):
        t = part.get('texture')
        if not t:
            return super().tex_of(name, part)
        return R.vanilla(t) if t.startswith('minecraft:') else self.assets / t.split(':', 1)[1]


def tile(src, items, label, size, ppu, az=210, el=24):
    im = Image.new('RGBA', size, BG)
    ImageDraw.Draw(im).text((8, 5), label, font=ImageFont.truetype(FONT, 15), fill=(0, 0, 0, 255))
    r, _ = R.render(src, items, az=az, el=el, ppu=ppu)
    k = min((size[0] - 10) / r.width, (size[1] - 34) / r.height)
    if k < 1:
        r = r.resize((max(1, int(r.width * k)), max(1, int(r.height * k))), Image.LANCZOS)
    im.paste(r, ((size[0] - r.width) // 2, 30 + (size[1] - 30 - r.height) // 2), r)
    return im


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--before', default='81188b4f1', help='Git ref with the old carton')
    args = ap.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(dir=ROOT / '.local') as tmp:
        archive = Path(tmp) / 'old.tar'
        subprocess.run(['git', 'archive', '--format=tar', '--output', str(archive), args.before, '--',
                        'src/main/resources/assets/mcyukkuri/yukkuri_props/cardboard_shelter.json',
                        'src/main/resources/assets/mcyukkuri/textures/props/cardboard_shelter.png'], cwd=ROOT, check=True)
        with tarfile.open(archive) as t:
            t.extractall(Path(tmp) / 'old', filter='data')
        old, new = Scene(Path(tmp) / 'old'), Scene(ROOT)
        size, ppu = (440, 380), 7
        # The carton's sleeping cells: the core (back) and the cell in front (mouth); adults face the opening (+Z).
        pair = [('yuk:reimu:neutral', 8, 0, 0, 0, 1.0, 180), ('yuk:marisa:neutral', 8, 0, 0, 16, 1.0, 180)]
        ground = ('ground:40', 8, 0, 0, 0, 1)
        box = lambda mask, src: ('cardboard_shelter', ('mask', mask), 0, 0, 0, 1, 0, src)
        tiles = [
            tile(new, [ground, box(1, old), *pair], '改前：3×3×2 格的旧纸箱，两只成体', size, ppu, az=20, el=22),
            tile(new, [ground, box(2, new), *pair], '改后：侧放开口纸箱（宽1.5 深2 高1.5格），一前一后两只成体', size, ppu, az=20, el=22),
            tile(new, [ground, box(2 | 1, new), *pair], '改后：背面（标签、胶带），已铺垫', size, ppu, az=150, el=26),
            tile(new, [('ground:24', 8, 0, 0, 0, 1), box(2, new)], '近看：方块风格长方体、像素纸板', size, 12, az=330, el=18),
            tile(new, [('ground:24', 8, 0, 0, 0, 1), box(4, new)], '泡透后（塌前）：顶盖耷拉在开口上（另有变深）', size, 12, az=330, el=18),
            tile(new, [ground, box(2, new), ('yuk:reimu:neutral', 8, 0, 0, 0, 1.0, 180)], '一只成体在后格（从开口斜上方看）', size, ppu, az=10, el=40),
        ]
        bins = [
            tile(new, [('ground:20', 8, 0, 0, 0, 1), ('bin', 8, 0, 0, 0, 1), ('yuk:reimu:neutral', 8, 0, 0, 18, 1.0, 0)],
                 '街边垃圾桶（立着）与成体灵梦', size, 11, az=300, el=20),
            tile(new, [('ground:20', 8, 0, 0, 0, 1), ('bin:tipped', 8, 0, 0, 0, 1), ('yuk:reimu:neutral', 8, 0, 0, 20, 1.0, 0)],
                 '被撞倒：桶口朝倒下方向，盖子靠在旁边，灵梦在桶口', size, 11, az=300, el=20),
            tile(new, [('ground:20', 8, 0, 0, 0, 1), ('bin:tipped', 8, 0, 0, 0, 1)], '近看桶口：深色内里露出垃圾', size, 16, az=340, el=16),
        ]
        street = tile(new, [('ground:56', 8, 0, 0, 0, 1), box(2, new), *pair,
                            ('bin', 8, -34, 0, 0, 1), ('bin:tipped', 8, -58, 0, 4, 1), ('yuk:reimu:neutral', 8, -58, 0, 22, 1.0, 0)],
                      '街角示意（约 8 格外）：纸箱里一前一后两只；左边一只立着的桶、一只被撞倒的桶和在桶口翻找的灵梦', (1320, 420), 5, az=15, el=16)
    W = 3 * size[0]
    sheet = Image.new('RGBA', (W, 3 * size[1] + 420), BG)
    for i, im in enumerate(tiles):
        sheet.paste(im, ((i % 3) * size[0], (i // 3) * size[1]))
    for i, im in enumerate(bins):
        sheet.paste(im, (i * size[0], 2 * size[1]))
    sheet.paste(street, (0, 3 * size[1]))
    sheet.save(OUT / 'current.png')
    (OUT / 'index.html').write_text(
        '<!doctype html><meta charset="utf-8"><title>纸箱重做与街边垃圾桶</title><h1>纸箱重做与街边垃圾桶</h1>'
        '<p>纸箱：改成侧放的开口纸板箱，方块风格长方体与像素纸板贴图，体块互不重叠（近看不再闪烁）；墙和顶有真实碰撞，只能从开口进出，'
        '一前一后挤两只成体，进箱按身体连同头饰的实际尺寸判断。垃圾桶：新做的镀锌带盖铁皮桶方块，立着够不着，被撞倒后从桶口翻；'
        '大小都按游戏实际比例。要你决定：两件外观能否采用，纸箱大小是否合适。</p>'
        '<img src="current.png" alt="纸箱与垃圾桶当前预览" style="max-width:100%">', encoding='utf-8')
    print(OUT / 'current.png')


if __name__ == '__main__':
    main()
