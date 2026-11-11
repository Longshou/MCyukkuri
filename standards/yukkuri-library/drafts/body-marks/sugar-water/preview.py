"""Review page .local/previews/leak/: the redrawn stem-drain faces (before / after), the excited-leak faces per model and
an offline 2D mock of the leak visual. Renders go through scripts/preview.py strip (real runtime models and atlases);
the drain tint and thinning and the puddle / drops are composited here as stand-ins for the runtime renderer.

    python standards/yukkuri-library/drafts/body-marks/sugar-water/preview.py [--before main] [--node node]
"""
from pathlib import Path
import argparse, html, json, os, shutil, subprocess, sys, tempfile
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[4]
ASSETS = ROOT/'src/main/resources/assets/mcyukkuri'
SHARED = Path(os.environ.get('MCYUKKURI_PREVIEWS', ROOT.parent.parent/'.local/previews' if ROOT.parent.name == '.worktrees' else ROOT/'.local/previews'))
OUT = SHARED/'leak'
WOUNDS = 'src/main/resources/assets/mcyukkuri/textures/wounds/hair'
DRAIN = [('teary', 1), ('neutral', 2), ('neutral', 3)]
TINT = [(240, 238, 240), (222, 220, 224), (204, 202, 210)]
LEAK = ['leak_startle', 'leak_flow', 'leak_after']
LABEL = {'leak_startle': '惊', 'leak_flow': '漏', 'leak_after': '之后'}
BG = (205, 214, 190, 255)


def font(size):
    for name in ('msyh.ttc', 'simhei.ttf', 'arial.ttf'):
        try: return ImageFont.truetype(name, size)
        except OSError: pass
    return ImageFont.load_default()


class Renderer:
    def __init__(self, node, work):
        self.node, self.work, self.count = node, work, 0

    def __call__(self, model, texture=None, face=None, size=300, view=(0, 4)):
        self.count += 1; topic = f'r{self.count}'
        env = {**os.environ, 'PYTHONUTF8': '1', 'MCYUKKURI_PREVIEWS': str(self.work)}
        args = [sys.executable, str(ROOT/'scripts/preview.py'), 'strip', topic, '--model', model, '--times', '0s', '--size', str(size), str(size),
                '--view', str(view[0]), str(view[1]), '--columns', '1', '--node', self.node]
        args += ['--texture', str(texture)] if texture else ['--face', face or 'neutral']
        subprocess.run(args, check=True, env=env, cwd=ROOT, capture_output=True)
        return Image.open(self.work/topic/'atlas.png').convert('RGBA')


def git_file(ref, path, target):
    target.write_bytes(subprocess.run(['git', 'show', f'{ref}:{path}'], check=True, cwd=ROOT, capture_output=True).stdout)
    return target


def drained(atlas_path, stage, target):
    """Stand-in for the runtime: the body's skin colours multiplied by the drain tint (the face decals are not tinted)."""
    image = Image.open(atlas_path).convert('RGBA'); counts = {}
    for p in image.get_flattened_data():
        if p[3] > 200: counts[p[:3]] = counts.get(p[:3], 0)+1
    skin = sorted(counts, key=counts.get, reverse=True)[:2]; px = image.load(); tint = TINT[stage-1]
    for y in range(image.height):
        for x in range(image.width):
            if px[x, y][:3] in skin: px[x, y] = tuple(round(c*t/255) for c, t in zip(px[x, y][:3], tint))+(255,)
    image.save(target); return target


def thin(image, stage, loss):
    w, h = image.size; nw = round(w*(1-loss[stage-1]))
    out = Image.new('RGBA', (w, h)); out.alpha_composite(image.resize((nw, h), Image.NEAREST), ((w-nw)//2, 0)); return out


def crop(image, box=(.2, .45, .8, .82)):
    w, h = image.size; return image.crop((int(box[0]*w), int(box[1]*h), int(box[2]*w), int(box[3]*h)))


def sheet(cells, columns, title=None, note=None):
    """cells: (image, caption) rows of equal-size images."""
    w, h = cells[0][0].size; top = 36 if title else 0; rows = (len(cells)+columns-1)//columns
    out = Image.new('RGBA', (w*columns, top+rows*(h+26)), BG); d = ImageDraw.Draw(out)
    if title: d.text((6, 6), title, fill=(30, 30, 30, 255), font=font(20))
    for i, (image, caption) in enumerate(cells):
        x, y = i % columns*w, top+i//columns*(h+26)
        out.alpha_composite(image, (x, y+24)); d.text((x+4, y+3), caption, fill=(30, 30, 30, 255), font=font(15))
    return out


def body_box(image):
    alpha = image.getchannel('A').point(lambda a: 255 if a > 100 else 0); return alpha.getbbox()


def emote(name, scale):
    data = json.loads((ASSETS/'textures/emote/emotes.json').read_text(encoding='utf-8')); atlas = Image.open(ASSETS/'textures/emote/atlas.png').convert('RGBA')
    cell = next(e['cell'] for e in data['emotes'] if e['id'] == name); s = data['cell_size']
    return atlas.crop((cell[0]*s, cell[1]*s, cell[0]*s+s, cell[1]*s+s)).resize((s*scale, s*scale), Image.NEAREST)


def mock(body, puddle=0.0, fade=1.0, drops=0, lift=0.0, ratio=1.2, mark=None, canvas=(300, 300)):
    """2D stand-in: puddle under the front of the body (drawn first), the body, falling drops, a head-top symbol."""
    out = Image.new('RGBA', canvas, BG); x0, y0, x1, y1 = body_box(body); bw = x1-x0; ground = y1
    raised = round(lift*(y1-y0))
    if puddle > 0:
        width = max(6, round(ratio*bw*puddle)); height = max(3, round(width*.3))
        pool = Image.open(ASSETS/'textures/effects/sugar_water_puddle.png').convert('RGBA').resize((width, height), Image.NEAREST)
        if fade < 1: pool.putalpha(pool.getchannel('A').point(lambda a: round(a*fade)))
        out.alpha_composite(pool, ((x0+x1)//2-width//2, ground-round(height*.55)))
    out.alpha_composite(body, (0, -raised))
    drop = Image.open(ASSETS/'textures/effects/sugar_water_drop.png').convert('RGBA')
    size = max(4, round(bw/14)); drop = drop.resize((size, size), Image.NEAREST); cx = (x0+x1)//2
    top = y1-raised-round(size*.6)
    for i in range(drops):
        y = top+round((ground-top-size//2)*(i+.5)/drops) if drops > 1 else top
        out.alpha_composite(drop, (cx-size//2+(i % 2)*2-1+round(i*size*.15), y))
    if mark:
        icon = emote(mark, max(2, round(bw/48))); out.alpha_composite(icon, (x1-icon.width//2, max(0, y0-raised-icon.height//2)))
    return out


def page(sections):
    style = ('body{background:#f2efe6;font:16px system-ui,"Microsoft YaHei";margin:20px;max-width:1500px}img{max-width:100%;image-rendering:pixelated;'
             'display:block;margin:8px 0}h2{margin-top:34px}table{border-collapse:collapse}td,th{border:1px solid #bbb;padding:4px 8px}'
             '.ask{background:#fff7d6;border:1px solid #d9c27a;padding:10px 14px}')
    return ('<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>耗馅脸重画与激动漏尿</title>'
            f'<style>{style}</style><body><a href="../index.html">预览总入口</a><h1>耗馅脸重画与激动漏尿</h1>{"".join(sections)}</body></html>')


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--before', default='main', help='Git ref with the previous drain faces')
    ap.add_argument('--node', default='node'); args = ap.parse_args()
    recipe = json.loads((ROOT/'standards/yukkuri-library/drafts/body-marks/hair-wounds/recipe.json').read_text(encoding='utf-8'))
    loss = recipe['thermal']['dry_shape']['width_loss']
    OUT.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='leak-', dir=ROOT/'.local/tmp' if (ROOT/'.local/tmp').is_dir() else None) as directory:
        work = Path(directory); render = Renderer(args.node, work/'pv'); files = {}
        # 1. Drain faces: before / after per stage, with the runtime tint and thinning composited on both.
        cells, small = [], []
        for model in ['reimu', 'marisa', 'alice', 'chen', 'remilia', 'reimu_deibu']:
            healthy = render(model, face='neutral')
            cells.append((crop(healthy), f'{model} 健康'))
            for (face, stage) in DRAIN:
                path = f'{WOUNDS}/{model}/dry-{face}-{stage}.png'
                old = drained(git_file(args.before, path, work/f'old-{model}-{stage}.png'), stage, work/f'old-t-{model}-{stage}.png')
                new = drained(ROOT/path, stage, work/f'new-t-{model}-{stage}.png')
                a, b = thin(render(model, old), stage, loss), thin(render(model, new), stage, loss)
                cells += [(crop(a), f'{stage}级 旧'), (crop(b), f'{stage}级 新')]
                if model in ('reimu', 'alice', 'remilia'):
                    small += [(thin(render(model, old, size=110, view=(20, 8)), stage, loss), f'{stage} 旧'),
                              (thin(render(model, new, size=110, view=(20, 8)), stage, loss), f'{stage} 新')]
        files['drain.png'] = sheet(cells, 7, '耗馅三级：旧 / 新（同机位；已叠加游戏里的灰调与变窄）')
        files['drain-distance.png'] = sheet(small, 6, '游戏距离（约 5 格外的实际像素大小）')
        # 2. Leak faces per model, close and at game distance.
        cells, small = [], []
        for model in ['reimu', 'marisa', 'alice', 'chen', 'myon', 'remilia', 'patchouli', 'reimu_deibu', 'house_reimu']:
            for face in LEAK:
                cells.append((crop(render(model, face=face)), f'{model} · {LABEL[face]}'))
            if model in ('reimu', 'marisa', 'alice'):
                small += [(render(model, face=face, size=110, view=(20, 8)), face.removeprefix('leak_')) for face in ['neutral']+LEAK]
        files['leak-faces.png'] = sheet(cells, 6, '激动漏尿表情（各品种用自己的眼色与嘴色；赤／子／成共用同一套贴图）')
        files['leak-distance.png'] = sheet(small, 8, '游戏距离')
        # 3. Visual mock: timeline, lifted, stage sizes.
        view = (20, 22)
        faces = {f: render('reimu', face=f, size=300, view=view) for f in ['neutral']+LEAK}
        frames = [(mock(faces['leak_startle'], mark='exclaim'), '0 t 受到强烈刺激：惊脸＋「!」'),
                  (mock(faces['leak_flow'], .3, drops=3), '8 t 开始漏：底部前方滴落'),
                  (mock(faces['leak_flow'], .85, drops=4), '40 t 流出中：水洼长大'),
                  (mock(faces['leak_after'], 1, mark='sweat'), '70 t 结束：恍惚害羞＋汗滴'),
                  (mock(faces['neutral'], 1, fade=.45), '约 600 t 后：水洼渐淡消失'),
                  (mock(faces['leak_flow'], .9, drops=6, lift=.42), '被拎起时：从高处滴落成水洼')]
        files['visual.png'] = sheet(frames, 3, '漏尿视觉示意（2D 合成，非游戏渲染；水洼／水滴为本包贴图）')
        files['visual-distance.png'] = sheet([(im.resize((110, 110), Image.LANCZOS), c.split('：')[0]) for im, c in frames], 6, '游戏距离')
        stages = []
        for label, scale, ratio in [('赤油（0.1–0.2）', .3, 2.0), ('子油（0.2–0.8）', .6, 1.5), ('成油（0.8–1.2）', 1, 1.2)]:
            body = faces['leak_flow'].resize((round(300*scale), round(300*scale)), Image.NEAREST)
            canvas = Image.new('RGBA', (300, 300)); canvas.alpha_composite(body, ((300-body.width)//2, 300-body.height-40+round(40*scale)))
            stages.append((mock(canvas, 1, drops=3, ratio=ratio), f'{label} 水洼直径 {ratio}×体宽'))
        files['stages.png'] = sheet(stages, 3, '体型与水洼大小（示意比例，非真实缩放）')
        pool = Image.new('RGBA', (32*8+8*8+24, 32*8), BG)
        pool.alpha_composite(Image.open(ASSETS/'textures/effects/sugar_water_puddle.png').convert('RGBA').resize((256, 256), Image.NEAREST), (0, 0))
        pool.alpha_composite(Image.open(ASSETS/'textures/effects/sugar_water_drop.png').convert('RGBA').resize((64, 64), Image.NEAREST), (280, 0))
        files['textures.png'] = pool
        for name, image in files.items(): image.save(OUT/name)
    E = html.escape
    timing = [('0', '触发（强烈情绪尖峰）', 'leak_startle', '「!」', '无'),
              ('6–10', '开始漏', 'leak_flow', '—', '底部前方开始滴落，水洼从 0 长大'),
              ('流出 D tick', '持续漏', 'leak_flow', '—', '每 1 tick（赤油每 2 tick）一滴；落地并入水洼；首秒溅出 2–4 小滴'),
              ('D 结束后 60–100', '之后', 'leak_after', '汗滴', '停止滴落，水洼保持'),
              ('600 + 100', '水洼存续与淡出', '恢复平常', '—', '沿用漏馅痕迹的 600 tick 存续、末 100 tick 淡出')]
    sections = [
        '<div class="ask"><b>请你决定：</b>① 耗馅新脸（下垂半闭的无神眼、黑眼圈、无笑、病态的小嘴）是否接受；'
        '② 三张漏尿表情（惊／漏／之后）是否接受，要改哪里；③ 漏尿的水洼与水滴效果、时间表、几率区间是否按此接线。</div>',
        '<h2>1. 耗馅（茎吸营养）脸：旧 → 新</h2><p>1 级仍是含泪担心脸，加淡黑眼圈、去腮红；2 级眼皮向外下垂盖住上半眼、眼睛失去高光、嘴角向下；'
        '3 级眼睛只剩一条缝、虹膜发灰、黑眼圈加深、脸颊凹陷、嘴无力微张。旧版 2 级是眯眼瞪视加微笑、3 级是闭眼笑，正是你说的“鉴定信念”与“一直微笑”。'
        '同一贴图也用于全身烧伤的静止脸。</p><img src="drain.png"><img src="drain-distance.png">',
        '<h2>2. 激动漏尿表情</h2><p>参照你给的漫画：大而圆、带两处高光的湿润眼，小张嘴，排线腮红。惊＝眉毛上扬、小圆嘴；漏＝担心眉、含泪、张嘴见舌；'
        '之后＝眼皮半垂、含泪、颤抖的波浪嘴。表情库按模型而非年龄区分，赤／子／成共用同一张。</p>'
        '<img src="ref.png" style="max-width:600px"><img src="leak-faces.png"><img src="leak-distance.png">'
        '<p><a href="../leak-viewer/index.html">真实运行模型查看器（可切换表情 leak_startle / leak_flow / leak_after，拖动旋转）</a></p>',
        '<h2>3. 漏尿视觉</h2><p>设定：Wiki F37 排尿口在下巴与 peni／mamu 之间，排出糖水；幼体缺乏控制常年湿漉（<code>wiki/hakkei-reference/lore/entities-sources.md</code>）。'
        '液体为淡琥珀色半透明糖水；水滴从身体底部前方落下，在地面并成一滩水洼，之后慢慢淡去。下图是 2D 合成示意，真实效果待接线后试玩。</p>'
        '<img src="visual.png"><img src="visual-distance.png"><img src="stages.png"><img src="textures.png">',
        '<h3>时间表（tick）</h3><table><tr><th>时刻</th><th>阶段</th><th>表情</th><th>头顶符号</th><th>效果</th></tr>'
        + ''.join(f'<tr>{"".join(f"<td>{E(c)}</td>" for c in row)}</tr>' for row in timing) + '</table>'
        '<p>流出时长 D：赤油 40–60，子油 30–50，成油 20–40（按自制在区间内平移，自制高取短端）。水洼直径＝体宽 × 赤 2.0／子 1.5／成 1.2，最小 0.12 格；水滴边长＝体宽 / 14，最小 0.03 格。</p>'
        '<h3>几率（每次情绪尖峰；区间按自制平移，自制 0 取上端、100 取下端）</h3><table><tr><th></th><th>强烈刺激（被突然拎起／摔落／天敌惊吓／濒死）</th><th>中等刺激（被吓到、被训斥、巨响）</th></tr>'
        '<tr><td>赤油</td><td>50–80%</td><td>20–40%</td></tr><tr><td>子油</td><td>25–45%</td><td>5–15%</td></tr><tr><td>成油</td><td>5–15%</td><td>0</td></tr></table>'
        '<p>同一只漏过后 1200 tick 内不再漏。数值为项目设计，待你定。</p>']
    (OUT/'index.html').write_text(page(sections), encoding='utf-8')
    print('Wrote', OUT/'index.html')


if __name__ == '__main__':
    main()
