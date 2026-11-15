"""Offline review sheet for the two town props (notice board, newspaper stack); no game or browser required.

Renders the runtime block models with the care-props render_preview over vanilla blocks (MCYUKKURI_VANILLA, default the shared
checkout's .local/vanilla-ref): the board on a stone-brick wall from three sides and close up, the stack with one to three
bundles, both beside an adult yukkuri for scale, the inventory views at 1x, and one street corner with the bench and the bin.
Usage: <py> art/props/notice-board/preview.py
Output: <MCYUKKURI_PREVIEWS or shared .local/previews>/town-blocks/{current.png, index.html}."""
import json, os, subprocess, sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
SHARED = Path(subprocess.check_output(['git', 'rev-parse', '--path-format=absolute', '--git-common-dir'], cwd=ROOT, text=True).strip()).parent
os.environ.setdefault('MCYUKKURI_VANILLA', str(SHARED / '.local/vanilla-ref/tex/assets/minecraft'))
sys.path.insert(0, str(ROOT / 'standards/yukkuri-library/drafts/care-props'))
sys.path.insert(0, str(ROOT / 'scripts'))
import render_preview as R
from preview_block_model import mesh

OUT = Path(os.environ.get('MCYUKKURI_PREVIEWS', SHARED / '.local/previews')) / 'town-blocks'
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
FONT = os.environ.get('MCYUKKURI_FONT', 'C:/Windows/Fonts/msyh.ttc')
BG = (226, 228, 222, 255)


def cube(texture):
    face = {'texture': '#t'}
    return {'elements': [{'from': [0, 0, 0], 'to': [16, 16, 16], 'faces': {d: face for d in ('north', 'south', 'east', 'west', 'up', 'down')}}],
            'textures': {'t': 'minecraft:block/' + texture}}


class Scene(R.Source):
    def load(self, name):
        kind, _, rest = name.partition(':')
        if kind in ('m', 'v'):          # m:<block model>[:turn] or v:<vanilla texture> (a full cube)
            bits = rest.split(':')
            model = json.loads((ASSETS / f'models/block/{bits[0]}.json').read_text(encoding='utf-8')) if kind == 'm' else cube(bits[0])
            data, v = mesh(model, model['textures'])
            turn = int(bits[1]) if len(bits) > 1 else 0
            if turn:
                v = v.copy(); r = R.rot_y(-turn)
                v[:, :3] = v[:, :3] @ r.T; v[:, 5:8] = v[:, 5:8] @ r.T
            return data, v
        return super().load(name)

    def tex_of(self, name, part):
        t = part.get('texture')
        if not t:
            return super().tex_of(name, part)
        return R.vanilla(t) if t.startswith('minecraft:') else self.assets / t.split(':', 1)[1]


def tile(src, items, label, size, ppu, az=180, el=20, bg=BG):
    im = Image.new('RGBA', size, bg)
    ImageDraw.Draw(im).text((8, 5), label, font=ImageFont.truetype(FONT, 15), fill=(0, 0, 0, 255))
    r, _ = R.render(src, items, az=az, el=el, ppu=ppu)
    k = min((size[0] - 10) / r.width, (size[1] - 34) / r.height)
    if k < 1:
        r = r.resize((max(1, int(r.width * k)), max(1, int(r.height * k))), Image.LANCZOS)
    im.paste(r, ((size[0] - r.width) // 2, 30 + (size[1] - 30 - r.height) // 2), r)
    return im


def icon(src, model, label):
    """The inventory view: the block/block gui transform (30, 225) at 16 px per block, shown at 1x and at 4x nearest."""
    r, _ = R.render(src, [(model, 8, 0, 0, 0, 1)], az=225, el=30, ppu=1.0)
    im = Image.new('RGBA', (220, 120), BG)
    ImageDraw.Draw(im).text((8, 5), label, font=ImageFont.truetype(FONT, 15), fill=(0, 0, 0, 255))
    slot = Image.new('RGBA', (18, 18), (139, 139, 139, 255)); slot.paste(r, ((18 - r.width) // 2, (18 - r.height) // 2), r)
    im.paste(slot, (20, 60)); im.paste(slot.resize((72, 72), Image.NEAREST), (80, 36))
    return im


def wall(xs, ys, z, texture='stone_bricks'):
    return [('v:' + texture, 8, x, y, z, 1) for x in xs for y in ys]


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    src = Scene(ROOT)
    size = (440, 380)
    ground, small = ('ground:40', 8, 0, 0, 0, 1), ('ground:10', 8, 0, 0, 0, 1)
    brick = wall((-16, 0, 16), (0, 16, 32), 16)
    board = ('m:notice_board', 8, 0, 16, 0, 1)
    reimu = ('yuk:reimu:neutral', 8, -4, 0, -14, 1.0, 0)
    tiles = [
        tile(src, [ground, *brick, board, reimu], '告示板：挂在砖墙上（离地一格），灵梦在前', size, 7, az=200, el=14),
        tile(src, [*wall((0,), (16,), 16), board], '正面近看：走失油库里（撕条）、徽章考试、领养', size, 20, az=180, el=4),
        tile(src, [ground, *brick, board], '侧面：小披檐、笔槽', size, 7, az=250, el=24),
        tile(src, [small, ('m:newspaper_stack_1', 8, 0, 0, 0, 1)], '报纸堆：一捆', size, 22, az=210, el=30),
        tile(src, [small, ('m:newspaper_stack_2', 8, 0, 0, 0, 1)], '两捆（第二捆斜放）', size, 22, az=150, el=30),
        tile(src, [('ground:24', 8, 0, 0, 0, 1), ('m:newspaper_stack_3', 8, 0, 0, 0, 1), ('yuk:reimu:neutral', 8, 18, 0, 6, 1.0, 0)],
             '三捆与成体灵梦', size, 12, az=210, el=24),
    ]
    icons = [icon(src, 'm:notice_board', '物品栏：告示板'), icon(src, 'm:newspaper_stack_2', '物品栏：报纸堆')]
    street = tile(src, [('ground:72', 8, 0, 0, 0, 1), *wall(range(-48, 49, 16), (0, 16, 32), 24, 'oak_planks'),
                        ('m:notice_board', 8, -16, 16, 8, 1), ('m:wooden_bench_single', 8, 16, 0, 0, 1),
                        ('m:garbage_bin', 8, 40, 0, 4, 1), ('m:newspaper_stack_3', 8, -40, 0, 2, 1),
                        ('m:newspaper_stack_1', 8, -40, 0, -14, 1, 0), ('yuk:marisa:neutral', 8, -14, 0, -8, 1.0, 20),
                        ('yuk:reimu:neutral', 8, 16, 8, 0, 1.0, 0)],
                  '街角示意（约 6 格外）：木屋墙上的告示板、长椅上的灵梦、垃圾桶、报纸堆与魔理沙', (1320, 420), 5, az=195, el=16)
    sheet = Image.new('RGBA', (3 * size[0], 2 * size[1] + 120 + 420), BG)
    for i, im in enumerate(tiles):
        sheet.paste(im, ((i % 3) * size[0], (i // 3) * size[1]))
    for i, im in enumerate(icons):
        sheet.paste(im, (i * 240, 2 * size[1]))
    sheet.paste(street, (0, 2 * size[1] + 120))
    sheet.save(OUT / 'current.png')
    (OUT / 'index.html').write_text(
        '<!doctype html><meta charset="utf-8"><title>城镇道具：告示板与报纸堆</title><h1>城镇道具：告示板与报纸堆</h1>'
        '<p>告示板：挂墙木板，小披檐与笔槽，三张传单（走失油库里带撕条、徽章考试、领养），右上一枚旧图钉和撕剩的纸角。'
        '报纸堆：一到三捆十字捆扎的旧报纸，第二捆斜放，捆间夹着彩色广告单。大小按游戏实际比例。</p>'
        '<p>要你决定：两件道具的外观能否采用。</p>'
        '<img src="current.png" alt="告示板与报纸堆当前预览" style="max-width:100%">', encoding='utf-8')
    print(OUT / 'current.png')


if __name__ == '__main__':
    main()
