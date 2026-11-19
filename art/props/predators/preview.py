"""Offline review sheet for the predator nest frame stages beside the finished nest; no game or browser required.

Renders the runtime exports (native frame models, the nest prop mesh) with the care-props render_preview over vanilla blocks
(MCYUKKURI_VANILLA, default the shared checkout's .local/vanilla-ref): each `twigs` stage 1..7 and the finished nest on an oak
log at the same camera, top views, and the frame in a leaf canopy beside an adult remilia, seen from the side and from the
ground below.
Usage: <py> art/props/predators/preview.py [--out DIR]   (default .local/tmp/tree-predators-art/stages.png)
Publish with `scripts/preview.py collection <topic> --source DIR --decision ...`."""
import argparse, json, os, subprocess, sys
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

ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
FONT = os.environ.get('MCYUKKURI_FONT', 'C:/Windows/Fonts/msyh.ttc')
BG = (214, 226, 232, 255)
FOLIAGE = [89, 174, 48]          # the plains/forest foliage tint of oak leaves


def cube(side, top=None):
    faces = {d: {'texture': '#s'} for d in ('north', 'south', 'east', 'west')}
    faces['up'] = faces['down'] = {'texture': '#t'}
    return {'elements': [{'from': [0, -16, 0], 'to': [16, 0, 16], 'faces': faces}],
            'textures': {'s': 'minecraft:block/' + side, 't': 'minecraft:block/' + (top or side)}}


class Scene(R.Source):
    def load(self, name):
        kind, _, rest = name.partition(':')
        if kind == 'm':                 # m:<block model> standing on y 0
            model = json.loads((ASSETS / f'models/block/{rest}.json').read_text(encoding='utf-8'))
            return mesh(model, model['textures'])
        if kind in ('log', 'leaves'):   # a support block whose top is y 0
            data, v = mesh(*(lambda m: (m, m['textures']))(cube('oak_log', 'oak_log_top') if kind == 'log' else cube('oak_leaves')))
            if kind == 'leaves':
                for part in data['parts']:
                    part['tint'] = FOLIAGE
            return data, v
        return super().load(name)

    def tex_of(self, name, part):
        t = part.get('texture')
        if not t:
            return super().tex_of(name, part)
        return R.vanilla(t) if t.startswith('minecraft:') else self.assets / t.split(':', 1)[1]


def tile(src, items, label, size, ppu, az=215, el=30):
    im = Image.new('RGBA', size, BG)
    ImageDraw.Draw(im).text((8, 5), label, font=ImageFont.truetype(FONT, 15), fill=(0, 0, 0, 255))
    r, _ = R.render(src, items, az=az, el=el, ppu=ppu)
    k = min((size[0] - 10) / r.width, (size[1] - 34) / r.height)
    if k < 1:
        r = r.resize((max(1, int(r.width * k)), max(1, int(r.height * k))), Image.LANCZOS)
    im.paste(r, ((size[0] - r.width) // 2, 30 + (size[1] - 30 - r.height) // 2), r)
    return im


STAGES = {1: '1 根：一根细枝横在树枝上', 2: '2 根：第二根搭在上面', 3: '3 根：三根交叉（斜的一根垫在下面）',
          4: '4 根：围出巢底第一圈', 5: '5 根：第二圈的一半', 6: '6 根：第二圈合拢', 7: '7 根：第三圈起了一半，窝心仍是空的'}


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument('--out', type=Path, default=ROOT / '.local/tmp/tree-predators-art')
    args = ap.parse_args()
    args.out.mkdir(parents=True, exist_ok=True)
    src = Scene(ROOT)
    size = (360, 300)
    log = ('log', 8, 0, 0, 0, 1)
    nest = ('predator_nest', 8, 0, 0, 0, 1)
    stage = lambda n, dx=0, dy=0, dz=0: ('m:predator_nest_frame_%d' % n, 8, dx, dy, dz, 1)
    row1 = [tile(src, [log, stage(n)], STAGES[n], size, 13) for n in range(1, 8)]
    row1.append(tile(src, [log, nest], '第 8 根：完工的枝巢（现有模型，不改）', size, 13))
    top = [tile(src, [log, stage(n)], f'俯视：{n} 根', size, 13, az=200, el=80) for n in (3, 5, 7)]
    top.append(tile(src, [log, nest], '俯视：完工', size, 13, az=200, el=80))
    canopy = [('leaves', 8, x, 0, z, 1) for x in (-16, 0, 16) for z in (-16, 0, 16)] + [('log', 8, 0, -16, 0, 1)]
    remilia = ('yuk:remilia:neutral', 8, 16, 0, 2, 1.0, 250)
    wide = (720, 360)
    # A canopy pocket: a log branch with leaves behind, beside and over the back, open toward the viewer (+z).
    pocket = [('log', 8, 0, 0, 0, 1)] + [('leaves', 8, x, y, z, 1) for x, y, z in
                                         ((-16, 0, 0), (16, 0, 0), (-16, 0, -16), (0, 0, -16), (16, 0, -16), (0, 16, -16),
                                          (-16, 16, -16), (16, 16, -16), (-16, 16, 0), (16, 16, 0), (0, 32, -16), (0, 32, 0))]
    scene = [
        tile(src, [*canopy, stage(6), remilia], '树冠顶上：6 根的巢架与成体蕾米莉亚（实际比例）', wide, 9, az=200, el=22),
        tile(src, [*pocket, stage(7)], '树冠里的凹处：三面和头顶有叶，敞开一面朝外（7 根）', wide, 9, az=15, el=10),
        tile(src, [*canopy, ('predator_nest', 8, 0, 0, 0, 1), remilia], '完工后同一位置', wide, 9, az=200, el=22),
        tile(src, [*pocket, nest], '凹处完工', wide, 9, az=15, el=10),
    ]
    W = 4 * size[0]
    sheet = Image.new('RGBA', (W, 3 * size[1] + 2 * wide[1]), BG)
    for i, im in enumerate(row1 + top):
        sheet.paste(im, ((i % 4) * size[0], (i // 4) * size[1]))
    for i, im in enumerate(scene):
        sheet.paste(im, ((i % 2) * wide[0], 3 * size[1] + (i // 2) * wide[1]))
    target = args.out / 'stages.png'
    sheet.save(target)
    print(target)


if __name__ == '__main__':
    main()
