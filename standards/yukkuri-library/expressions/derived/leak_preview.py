"""Review page .local/previews/leak/: the excited-leak faces with their on-body streams, next to the user's reference panel
(ref.png, kept in the page folder), as frame strips and animated GIFs at close range and at game distance per growth stage.
Renders go through scripts/preview.py strip (real runtime models and atlases); timing follows face_library animations.

    python standards/yukkuri-library/expressions/derived/leak_preview.py [--node node]
"""
from pathlib import Path
import argparse, html, json, os, subprocess, sys, tempfile
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
ASSETS = ROOT/'src/main/resources/assets/mcyukkuri'
SHARED = Path(os.environ.get('MCYUKKURI_PREVIEWS', ROOT.parent.parent/'.local/previews' if ROOT.parent.name == '.worktrees' else ROOT/'.local/previews'))
OUT = SHARED/'leak'
FRAMES = ['leak_startle', 'leak_flow', 'leak_flow_b', 'leak_flow_c', 'leak_after', 'leak_after_b']
LABEL = {'leak_startle': '惊：第一股', 'leak_flow': '漏 A', 'leak_flow_b': '漏 B', 'leak_flow_c': '漏 C', 'leak_after': '之后 A', 'leak_after_b': '之后 B'}
# One played sequence (ticks): startle, flow looping, after looping (face_library animations give the loop timing).
SEQUENCE = [('leak_startle', 10)]+[(f, 4) for _ in range(5) for f in ('leak_flow', 'leak_flow_b', 'leak_flow_c')]+[('leak_after', 30), ('leak_after_b', 10)]*2
STAGES = [('赤油', .15), ('子油', .5), ('成油', 1.0)]  # growth scale (ordinary forms: baby 0.1-0.2, child 0.2-0.8, adult 0.8-1.2)
BG = (205, 214, 190, 255)


def font(size):
    for name in ('msyh.ttc', 'simhei.ttf', 'arial.ttf'):
        try: return ImageFont.truetype(name, size)
        except OSError: pass
    return ImageFont.load_default()


class Renderer:
    def __init__(self, node, work):
        self.node, self.work, self.count = node, work, 0

    def __call__(self, model, face, size=300, view=(0, 4)):
        self.count += 1; topic = f'r{self.count}'
        env = {**os.environ, 'PYTHONUTF8': '1', 'MCYUKKURI_PREVIEWS': str(self.work)}
        texture = ASSETS/f'textures/entity/{model}/{face}.png'
        subprocess.run([sys.executable, str(ROOT/'scripts/preview.py'), 'strip', topic, '--model', model, '--texture', str(texture),
                        '--times', '0s', '--size', str(size), str(size), '--view', str(view[0]), str(view[1]), '--columns', '1',
                        '--node', self.node], check=True, env=env, cwd=ROOT, capture_output=True)
        image = Image.new('RGBA', (size, size), BG); image.alpha_composite(Image.open(self.work/topic/'atlas.png').convert('RGBA'))
        return image


def crop(image, box=(.15, .35, .85, .88)):
    w, h = image.size; return image.crop((int(box[0]*w), int(box[1]*h), int(box[2]*w), int(box[3]*h)))


def sheet(cells, columns, title=None):
    w, h = cells[0][0].size; top = 36 if title else 0; rows = (len(cells)+columns-1)//columns
    out = Image.new('RGBA', (w*columns, top+rows*(h+26)), BG); d = ImageDraw.Draw(out)
    if title: d.text((6, 6), title, fill=(30, 30, 30, 255), font=font(20))
    for i, (image, caption) in enumerate(cells):
        x, y = i % columns*w, top+i//columns*(h+26)
        out.alpha_composite(image, (x, y+24)); d.text((x+4, y+3), caption, fill=(30, 30, 30, 255), font=font(15))
    return out


def gif(path, frames, scale=1):
    images = [im.resize((im.width*scale, im.height*scale), Image.NEAREST).convert('P', palette=Image.Palette.ADAPTIVE) for im, _ in frames]
    images[0].save(path, save_all=True, append_images=images[1:], duration=[t*50 for _, t in frames], loop=0, disposal=2)


def main():
    ap = argparse.ArgumentParser(description=__doc__); ap.add_argument('--node', default='node'); args = ap.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    for old in ('drain.png', 'drain-distance.png', 'leak-faces.png', 'leak-distance.png', 'visual.png', 'visual-distance.png', 'stages.png', 'textures.png'):
        (OUT/old).unlink(missing_ok=True)
    with tempfile.TemporaryDirectory(prefix='leak-', dir=ROOT/'.local/tmp' if (ROOT/'.local/tmp').is_dir() else None) as directory:
        render = Renderer(args.node, Path(directory)); files = {}
        close = {f: render('reimu', f, 360, (0, -2)) for f in FRAMES}
        above = {f: render('reimu', f, 360, (25, 18)) for f in FRAMES}
        files['frames.png'] = sheet([(crop(close[f]), LABEL[f]) for f in FRAMES]+[(crop(above[f]), LABEL[f]+'（俯视）') for f in FRAMES], 6,
                                    '灵梦：六帧（上排正面略低，下排玩家常见的斜上方）')
        cells = []
        for model in ['marisa', 'alice', 'chen', 'myon', 'remilia', 'patchouli', 'reimu_deibu', 'house_reimu', 'reimu_bean']:
            cells += [(crop(render(model, f, 300, (15, 8))), f'{model} · {LABEL[f]}') for f in ('leak_startle', 'leak_flow', 'leak_after')]
        files['models.png'] = sheet(cells, 6, '各品种（用自己的眼色与嘴色；家油、豆形的脸不在身体下部，尿线只到下巴）')
        gif(OUT/'play-close.gif', [(crop(above[f]), t) for f, t in SEQUENCE], 2)
        small = {}
        for label, scale in STAGES:
            size = max(16, round(110*scale))
            small[label] = {f: render('reimu', f, size, (25, 18)) for f in ('leak_startle', 'leak_flow', 'leak_flow_b', 'leak_flow_c', 'leak_after', 'leak_after_b')}
        canvas = []
        for f, t in SEQUENCE:
            frame = Image.new('RGBA', (300, 120), BG); x = 10
            for label, _ in STAGES:
                im = small[label][f]; frame.alpha_composite(im, (x, 115-im.height)); x += im.width+30
            canvas.append((frame, t))
        gif(OUT/'play-distance.gif', canvas, 1)
        files['stages.png'] = sheet([(canvas[0][0], '惊'), (canvas[2][0], '漏'), (canvas[-1][0], '之后')], 3, '游戏距离（约 5 格外的实际像素大小；左起赤、子、成）')
        for name, image in files.items(): image.save(OUT/name)
    E = html.escape
    timing = [('0', '触发（强烈情绪尖峰）', 'leak_startle（单帧）', '「!」'),
              ('6–10', '开始漏', 'leak_flow（三帧各 4 tick 循环）', '—'),
              ('+ 流出 D', '持续漏', 'leak_flow', '—'),
              ('D 结束后 60–100', '之后', 'leak_after（30／10 tick 循环）', '汗滴'),
              ('之后', '恢复', '回到平常表情', '—')]
    style = ('body{background:#f2efe6;font:16px system-ui,"Microsoft YaHei";margin:20px;max-width:1500px}img{max-width:100%;image-rendering:pixelated;'
             'display:block;margin:8px 0}h2{margin-top:30px}table{border-collapse:collapse}td,th{border:1px solid #bbb;padding:4px 8px}'
             '.ask{background:#fff7d6;border:1px solid #d9c27a;padding:10px 14px}.row{display:flex;gap:16px;align-items:flex-start;flex-wrap:wrap}')
    body = [
        '<div class="ask"><b>请你决定：</b>身上尿线的画法（位置、颜色、粗细、流动帧）与三张表情是否接受，要改哪里。憔悴脸已通过，不再列出。</div>',
        '<h2>1. 参考原图与身上动态</h2><p>照你的意见：不用水滴粒子，不做地上水洼，像表情一样直接画在身上。三道带深色描边的淡黄糖水线从嘴下方'
        '（Wiki F37：排尿口在下巴下方）沿身前下部流到底部弯面，高光每帧下移表现流动；之后变成断续的干痕，底部挂一滴、下一帧落下。</p>'
        '<div class="row"><img src="ref.png" style="max-width:600px"><img src="play-close.gif" style="width:504px"></div>',
        '<h2>2. 游戏距离（按成长阶段）</h2><div class="row"><img src="play-distance.gif" style="width:600px"></div><img src="stages.png">',
        '<h2>3. 六帧与各品种</h2><img src="frames.png"><img src="models.png">'
        '<p><a href="../leak-viewer/index.html">真实运行模型查看器（表情选 leak_flow 看循环，可拖动旋转）</a></p>',
        '<h3>时间表（tick）</h3><table><tr><th>时刻</th><th>阶段</th><th>表情（含身上尿线）</th><th>头顶符号</th></tr>'
        + ''.join(f'<tr>{"".join(f"<td>{E(c)}</td>" for c in row)}</tr>' for row in timing) + '</table>'
        '<p>流出时长 D：赤油 40–60，子油 30–50，成油 20–40（按自制在区间内平移，自制高取短端）。</p>'
        '<h3>几率（每次情绪尖峰；区间按自制平移，自制 0 取上端、100 取下端）</h3><table><tr><th></th><th>强烈刺激（被突然拎起／摔落／天敌惊吓／濒死）</th><th>中等刺激（被吓到、被训斥、巨响）</th></tr>'
        '<tr><td>赤油</td><td>50–80%</td><td>20–40%</td></tr><tr><td>子油</td><td>25–45%</td><td>5–15%</td></tr><tr><td>成油</td><td>5–15%</td><td>0</td></tr></table>'
        '<p>同一只漏过后 1200 tick 内不再漏。数值为项目设计，待你定。</p>']
    (OUT/'index.html').write_text('<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>激动漏尿</title>'
                                  f'<style>{style}</style><body><a href="../index.html">预览总入口</a><h1>激动漏尿</h1>{"".join(body)}</body></html>', encoding='utf-8')
    print('Wrote', OUT/'index.html')


if __name__ == '__main__':
    main()
