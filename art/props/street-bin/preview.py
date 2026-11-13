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
sys.path.insert(0, str(ROOT / 'standards/yukkuri-library/ecology/wild-shelters'))
sys.path.insert(0, str(ROOT / 'scripts'))
from cardboard import INNER_REACH, REST_OFFSET
import render_preview as R
from preview_block_model import mesh

OUT = Path(os.environ.get('MCYUKKURI_PREVIEWS', ROOT / '.local/previews')) / 'box-bin'
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
FONT = os.environ.get('MCYUKKURI_FONT', 'C:/Windows/Fonts/msyh.ttc')
BG = (226, 228, 222, 255)
CARTON_WIDTH = 2 * (REST_OFFSET + INNER_REACH + 1)


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
        # The two widest common hats use the actual side-by-side rest spots, facing the opening (+Z).
        pair_old = [('yuk:reimu:neutral', 8, 0, 0, 0, 1.0, 180), ('yuk:marisa:neutral', 8, 0, 0, 16, 1.0, 180)]
        pair = [('yuk:marisa:neutral', 8, x, 0, 0, 1.0, 180) for x in (-REST_OFFSET, REST_OFFSET)]
        ground = ('ground:40', 8, 0, 0, 0, 1)
        box = lambda mask, src, stage='': ('cardboard_shelter' + stage, ('mask', mask), 0, 0, 0, 1, 0, src)
        tiles = [
            tile(new, [ground, box(1, old), *pair_old], '最早：3×3×2 格的旧纸箱', size, ppu, az=20, el=22),
            tile(new, [ground, box(1, new), *pair], f'现在：{CARTON_WIDTH / 16:g} 格宽，两只成体魔理沙并排', size, ppu, az=20, el=22),
            tile(new, [ground, box(1, new)], '背面：标签、胶带', size, ppu, az=150, el=26),
            tile(new, [ground, box(0, new)], '雨①干燥：顶盖竖起', size, ppu, az=330, el=30),
            tile(new, [ground, box(0, new, '_damp')], '雨②受潮：水渍下洇，顶盖前垂', size, ppu, az=330, el=30),
            tile(new, [ground, box(0, new, '_soaked')], '雨③泡透：变深、顶塌、墙凹、盖垂下', size, ppu, az=330, el=30),
        ]
        bins = [
            tile(new, [('ground:20', 8, 0, 0, 0, 1), ('bin', 8, 0, 0, 0, 1), ('yuk:reimu:neutral', 8, 0, 0, 18, 1.0, 0)],
                 '街边垃圾桶（立着）与成体灵梦', size, 11, az=300, el=20),
            tile(new, [('ground:20', 8, 0, 0, 0, 1), ('bin:tipped', 8, 0, 0, 0, 1), ('yuk:reimu:neutral', 8, 0, 0, 20, 1.0, 0)],
                 '被撞倒：桶口朝倒下方向，盖子靠在旁边，灵梦在桶口', size, 11, az=300, el=20),
            tile(new, [('ground:20', 8, 0, 0, 0, 1), ('bin:tipped', 8, 0, 0, 0, 1)], '近看桶口：深色内里露出垃圾', size, 16, az=340, el=16),
        ]
        street = tile(new, [('ground:56', 8, 0, 0, 0, 1), box(1, new), *pair,
                            ('bin', 8, -40, 0, 0, 1), ('bin:tipped', 8, -64, 0, 4, 1), ('yuk:reimu:neutral', 8, -64, 0, 22, 1.0, 0)],
                      '街角示意（约 8 格外）：纸箱里两只并排；左边一只立着的桶、一只被撞倒的桶和在桶口翻找的灵梦', (1320, 420), 5, az=15, el=16)
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
        f'<p>纸箱主体宽 {CARTON_WIDTH / 16:g} 格，图中两只成体魔理沙按实际睡位并排朝外，帽檐之间留有空隙。'
        '淋雨分三档且各不相同：受潮时水渍从顶上往下洇开、顶盖向前耷拉；泡透时整只箱子变深、箱顶一级级塌下去、两侧墙往里凹、顶盖垂下来挡住开口上半截，之后才塌掉。</p>'
        '<p>纸箱：方块风格长方体与像素纸板贴图，体块互不重叠；墙和顶有真实碰撞，只能从开口进出；进箱按身体连同头饰的实际尺寸判断。'
        '垃圾桶：镀锌带盖铁皮桶方块，立着够不着，被撞倒后从桶口翻。大小都按游戏实际比例。'
        '要你决定：纸箱宽度与三档淋雨样子、垃圾桶外观能否采用。</p>'
        '<img src="current.png" alt="纸箱与垃圾桶当前预览" style="max-width:100%">', encoding='utf-8')
    print(OUT / 'current.png')


if __name__ == '__main__':
    main()
