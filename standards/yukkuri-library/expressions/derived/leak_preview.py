"""Review page .local/previews/leak/: (1) the excited leak - one on-body stream in the leak faces and the ground puddle under
the front of the body - next to the user's reference panel (ref.png, kept in the page folder), as frame strips and animated
GIFs at close range and at game distance per growth stage; (2) the organ shapes - peni before / after, the default mamu
line and flat mamu candidates (2D mock-ups on the real render, labelled B, C, ...).
Renders go through scripts/preview.py strip (real runtime models and atlases); the puddle textures are laid on the ground
plane of the same camera (orthographic approximation) with the size and timing the wiring table in the report gives.

    python standards/yukkuri-library/expressions/derived/leak_preview.py [--node node] [--before REV]
"""
from pathlib import Path
import argparse, html, math, os, subprocess, sys, tempfile
from PIL import Image, ImageChops, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
ASSETS = ROOT/'src/main/resources/assets/mcyukkuri'
SHARED = Path(os.environ.get('MCYUKKURI_PREVIEWS', ROOT.parent.parent/'.local/previews' if ROOT.parent.name == '.worktrees' else ROOT/'.local/previews'))
OUT = SHARED/'leak'
FRAMES = ['leak_startle', 'leak_flow', 'leak_flow_b', 'leak_flow_c', 'leak_after', 'leak_after_b']
LABEL = {'leak_startle': '惊：第一股', 'leak_flow': '漏 A', 'leak_flow_b': '漏 B', 'leak_flow_c': '漏 C', 'leak_after': '之后 A',
         'leak_after_b': '之后 B', 'neutral': '恢复'}
PUDDLE_OF = {'leak_flow': 'leak_puddle_flow', 'leak_flow_b': 'leak_puddle_flow_b', 'leak_flow_c': 'leak_puddle_flow_c',
             'leak_after': 'leak_puddle_still', 'leak_after_b': 'leak_puddle_still', 'neutral': 'leak_puddle_dry'}
# Puddle final diameter as a multiple of the body width, per growth stage (report wiring table); the flow grows it from
# 35 % to 100 % (square-root ease), the after phase holds it, then the dry frame fades out.
STAGES = [('赤油', .15, 2.5), ('子油', .5, 1.5), ('成油', 1.0, 1.1)]  # label, growth scale, puddle diameter / body width
FLOW = 15  # flow frames of 4 ticks in the played sequence
# One played sequence: (face, ticks, puddle growth 0-1 or None, puddle alpha). The fade is shortened 200 -> 80 ticks here.
SEQUENCE = ([('leak_startle', 10, None, 1)]
            + [(('leak_flow', 'leak_flow_b', 'leak_flow_c')[i % 3], 4, .35+.65*math.sqrt(i/(FLOW-1)), 1) for i in range(FLOW)]
            + [('leak_after', 30, 1, 1), ('leak_after_b', 10, 1, 1), ('leak_after', 30, 1, 1), ('leak_after_b', 10, 1, 1)]
            + [('neutral', 20, 1, a) for a in (1, .7, .4, .15)])
VIEW = (25, 30)  # a player standing a few blocks away looks down on a small body
BG = (205, 214, 190, 255)
MAMU = ['B 杏仁形轮廓', 'C 小圆点', 'D 竖向小椭圆', 'E 波浪竖线']


def font(size):
    for name in ('msyh.ttc', 'simhei.ttf', 'arial.ttf'):
        try: return ImageFont.truetype(name, size)
        except OSError: pass
    return ImageFont.load_default()


class Renderer:
    def __init__(self, node, work):
        self.node, self.work, self.count = node, work, 0

    def run(self, extra, size):
        self.count += 1; topic = f'r{self.count}'
        env = {**os.environ, 'PYTHONUTF8': '1', 'MCYUKKURI_PREVIEWS': str(self.work)}
        subprocess.run([sys.executable, str(ROOT/'scripts/preview.py'), 'strip', topic, *extra, '--size', str(size), str(size),
                        '--columns', '1', '--node', self.node], check=True, env=env, cwd=ROOT, capture_output=True)
        return self.work/topic

    def __call__(self, model, face, size=300, view=VIEW):
        """Transparent render of the model wearing this face atlas."""
        stage = self.run(['--model', model, '--texture', str(ASSETS/f'textures/entity/{model}/{face}.png'), '--times', '0s',
                          '--view', str(view[0]), str(view[1])], size)
        return Image.open(stage/'atlas.png').convert('RGBA')

    def clip(self, model, clip, time, view, size=360, before=None):
        stage = self.run(['--model', model, '--clip', clip, '--times', f'{time:g}s', '--view', str(view[0]), str(view[1])]
                         + (['--before', before] if before else []), size)
        return stage


def on_ground(body, puddle, ratio, alpha=1., view=VIEW, pad=(0, 0, 0, 0)):
    """The body render over its puddle: the 32x32 top-down texture (row 0 against the body) laid on the ground plane,
    centred 0.45 body widths in front of the body's ground centre, diameter = ratio x body width."""
    l, t, r, b = pad
    canvas = Image.new('RGBA', (body.width+l+r, body.height+t+b), BG)
    box = body.getbbox()
    if puddle and box:
        az, el = math.radians(view[0]), math.radians(view[1]); width = (box[2]-box[0])*.86
        d = ratio*width; s = math.sin(el)
        ground = ((box[0]+box[2])/2+l, box[3]+t-.32*width*s)
        fx, fy = -math.sin(az), math.cos(az)*s  # body forward on screen
        rx, ry = math.cos(az), math.sin(az)*s   # body right on screen
        centre = (ground[0]+.45*width*fx, ground[1]+.45*width*fy)
        texture = Image.open(ASSETS/f'textures/effects/{puddle}.png').convert('RGBA')
        if alpha < 1: texture.putalpha(texture.getchannel('A').point(lambda v: round(v*alpha)))
        # screen = centre + (u-16)/32*d*right + (v-16)/32*d*forward; PIL wants the inverse map screen -> texture.
        a, bb, c, e = rx*d/32, fx*d/32, ry*d/32, fy*d/32; det = a*e-bb*c
        ia, ib, ic, ie = e/det, -bb/det, -c/det, a/det
        ox, oy = centre[0]-16*a-16*bb, centre[1]-16*c-16*e
        layer = texture.transform(canvas.size, Image.AFFINE, (ia, ib, -ia*ox-ib*oy, ic, ie, -ic*ox-ie*oy), Image.NEAREST)
        canvas.alpha_composite(layer)
    canvas.alpha_composite(body, (l, t))
    return canvas


def crop(image, box=(.12, .3, .88, .98)):
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


def mamu_mock(extended, retracted, label):
    """A candidate drawn where the real line is: the line's box from the extended / retracted difference, its own ink."""
    diff = ImageChops.difference(extended.convert('RGB'), retracted.convert('RGB')).convert('L').point(lambda v: 255 if v > 40 else 0)
    box = diff.getbbox(); out = retracted.copy()
    if not box: return out
    ink = min((extended.getpixel((x, y)) for x in range(box[0], box[2]) for y in range(box[1], box[3]) if diff.getpixel((x, y))),
              key=lambda c: sum(c[:3]))
    cx, cy, h = (box[0]+box[2])/2, (box[1]+box[3])/2, box[3]-box[1]; line = max(1, box[2]-box[0]); d = ImageDraw.Draw(out)
    if label.startswith('B'):
        w = h*.42; pts = [(cx+w/2*math.sin(math.pi*k/12), cy-h/2+h*k/12) for k in range(13)]
        pts += [(cx-w/2*math.sin(math.pi*k/12), cy+h/2-h*k/12) for k in range(13)]
        d.line(pts, fill=ink, width=line)
    elif label.startswith('C'):
        r = max(1.5, h*.18); d.ellipse((cx-r, cy-r, cx+r, cy+r), fill=ink)
    elif label.startswith('D'):
        d.ellipse((cx-h*.16, cy-h*.32, cx+h*.16, cy+h*.32), fill=ink)
    else:
        pts = [(cx+line*1.2*math.sin(k/16*math.pi*3), cy-h/2+h*k/16) for k in range(17)]
        d.line(pts, fill=ink, width=line)
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__); ap.add_argument('--node', default='node')
    ap.add_argument('--before', default='HEAD', help='Git ref of the organ shapes to compare against')
    args = ap.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    for old in ('stages.png', 'organ-peni-front.png'):
        (OUT/old).unlink(missing_ok=True)
    with tempfile.TemporaryDirectory(prefix='leak-', dir=ROOT/'.local/tmp' if (ROOT/'.local/tmp').is_dir() else None) as directory:
        render = Renderer(args.node, Path(directory)); files = {}
        faces = FRAMES+['neutral']
        above = {f: render('reimu', f, 360) for f in faces}
        front = {f: render('reimu', f, 360, (0, -2)) for f in FRAMES}
        adult = STAGES[2][2]
        files['frames.png'] = sheet([(crop(front[f], (.15, .35, .85, .88)), LABEL[f]) for f in FRAMES]
                                    + [(crop(on_ground(above[f], PUDDLE_OF.get(f), adult, pad=(0, 0, 0, 90)), (.1, .3, .9, 1)), LABEL[f]+'（斜上方＋水滩）') for f in FRAMES], 6,
                                    '灵梦：六帧（上排正面略低看身上尿线，下排玩家常见的斜上方，水滩按成油最终大小）')
        textures = [(Image.open(ASSETS/f'textures/effects/{n}.png').convert('RGBA').resize((160, 160), Image.NEAREST), n)
                    for n in ('leak_puddle_flow', 'leak_puddle_flow_b', 'leak_puddle_flow_c', 'leak_puddle_still', 'leak_puddle_dry')]
        files['puddle.png'] = sheet([(Image.alpha_composite(Image.new('RGBA', (160, 160), (120, 150, 90, 255)), im), n) for im, n in textures], 5,
                                    '水滩贴图（32×32 俯视，上边贴着身体；漏时三帧循环，之后静止，最后干涸淡出）')
        close = []
        for face, ticks, grow, alpha in SEQUENCE:
            row = Image.new('RGBA', (3*274, 360), BG); d = ImageDraw.Draw(row)
            for i, (label, _, ratio) in enumerate(STAGES):
                cell = crop(on_ground(above[face], PUDDLE_OF.get(face) if grow else None, ratio*(grow or 0), alpha, pad=(0, 0, 0, 120)), (.12, .3, .88, 1))
                row.alpha_composite(cell.resize((274, round(cell.height*274/cell.width))), (i*274, 16)); d.text((i*274+6, 0), f'{label}（水滩 {ratio:g} 倍体宽）', fill=(30, 30, 30, 255), font=font(14))
            close.append((row, ticks))
        gif(OUT/'play-close.gif', close)
        small = {}
        for label, scale, ratio in STAGES:
            size = max(16, round(110*scale))
            small[label] = {f: render('reimu', f, size) for f in faces}
        distance = []
        for face, ticks, grow, alpha in SEQUENCE:
            frame = Image.new('RGBA', (360, 150), BG); x = 14
            for label, _, ratio in STAGES:
                im = small[label][face]; pad = round(im.width*.6)
                cell = on_ground(im, PUDDLE_OF.get(face) if grow else None, ratio*(grow or 0), alpha, pad=(pad, 0, pad, pad))
                frame.alpha_composite(cell, (x-pad, 128-im.height)); x += im.width+44
            distance.append((frame, ticks))
        gif(OUT/'play-distance.gif', distance)
        files['distance.png'] = sheet([(distance[0][0], '惊'), (distance[8][0], '漏到一半'), (distance[FLOW][0], '漏完'),
                                       (distance[-3][0], '干涸淡出')], 4, '游戏距离（约 5 格外的实际像素大小；左起赤、子、成）')
        cells = []
        for model in ['marisa', 'alice', 'chen', 'myon', 'remilia', 'patchouli', 'reimu_deibu', 'house_reimu', 'reimu_bean']:
            cells += [(crop(on_ground(render(model, f, 300, (15, 20)), PUDDLE_OF.get(f) if f != 'leak_startle' else None, adult, view=(15, 20), pad=(0, 0, 0, 70)), (.1, .3, .9, 1)),
                       f'{model} · {LABEL[f]}') for f in ('leak_startle', 'leak_flow', 'leak_after')]
        files['models.png'] = sheet(cells, 6, '各品种（用自己的眼色与嘴色；家油、豆形的脸不在身体下部，尿线只到下巴）')
        # Organs: peni before / after (same camera), mamu default line and candidates.
        size = 480
        def flat(image): return Image.alpha_composite(Image.new('RGBA', image.size, (238, 232, 221, 255)), image.convert('RGBA'))  # compare.png ground
        def organ(clip, view, side):
            """Old / current crops around the extended organ (the only part that differs between the two versions)."""
            stage = render.clip('reimu', clip, .24, view, size, args.before)  # the extend clip's full length
            pair = Image.open(stage/'compare.png').convert('RGBA'); old, new = pair.crop((0, 0, size, size)), pair.crop((size, 0, 2*size, size))
            retracted = flat(Image.open(render.clip('reimu', clip, 0, view, size)/'atlas.png'))
            box = ImageChops.difference(new.convert('RGB'), old.convert('RGB')).convert('L').point(lambda v: 255 if v > 30 else 0).getbbox()
            cx, cy = (box[0]+box[2])//2, (box[1]+box[3])//2
            area = (cx-side//2, cy-side//2, cx+side//2, cy+side//2)
            return [im.crop(area).resize((300, 300), Image.NEAREST) for im in (old, new)], new, retracted, area
        cells = []
        for view, label in (((90, 0), '侧面'), ((30, -10), '斜前方略低')):
            (old, new), *_ = organ('anatomy_peni_extend', view, 120)
            cells += [(old, f'原版本 · {label}'), (new, f'当前 · {label}')]
        files['organ-peni.png'] = sheet(cells, 4, '佩妮佩妮伸出（放大 2.5 倍；左原版本，右当前）')
        (old, new), extended, retracted, area = organ('anatomy_mamu_extend', (0, -20), 100)
        files['organ-mamu.png'] = sheet([(old, '原版本'), (new, '当前（A 竖线）')], 2, '麻姆麻姆伸出（放大 3 倍）')
        def zoom(image): return image.crop(area).resize((300, 300), Image.NEAREST)
        files['mamu.png'] = sheet([(zoom(extended), 'A 竖线（默认，真实模型）')]
                                  + [(zoom(mamu_mock(extended, retracted, n)), n+'（示意）') for n in MAMU], 5,
                                  '麻姆麻姆形状候选（正面略低，放大 3 倍；颜色沿用肤色与原深色线；B–E 为在同一渲染上画的平面示意）')
        for name, image in files.items(): image.save(OUT/name)
    E = html.escape
    timing = [('0', '触发（强烈情绪尖峰）', 'leak_startle（单帧）', '无', '「!」'),
              ('6–10', '开始漏', 'leak_flow（三帧各 4 tick 循环）', 'leak_puddle_flow（三帧与表情同步），从 35% 长到 100%', '—'),
              ('+ 流出 D', '持续漏', 'leak_flow', '继续长大（平方根缓动，先快后慢）', '—'),
              ('D 结束后 60–100', '之后', 'leak_after（30／10 tick 循环）', 'leak_puddle_still，保持大小', '汗滴'),
              ('之后 200', '恢复', '平常表情', 'leak_puddle_dry，透明度 1→0 淡出（水滩留在原地，不跟着走）', '—')]
    style = ('body{background:#f2efe6;font:16px system-ui,"Microsoft YaHei";margin:20px;max-width:1500px}img{max-width:100%;image-rendering:pixelated;'
             'display:block;margin:8px 0}h2{margin-top:30px}table{border-collapse:collapse}td,th{border:1px solid #bbb;padding:4px 8px}'
             '.ask{background:#fff7d6;border:1px solid #d9c27a;padding:10px 14px}.row{display:flex;gap:16px;align-items:flex-start;flex-wrap:wrap}')
    body = [
        '<div class="ask"><b>请你决定：</b>（1）漏尿：单条尿线＋地上水滩是否接受，水滩大小、位置、溅射点要改哪里；'
        '（2）麻姆麻姆选一个形状（A 默认竖线，或 B–E）；佩妮佩妮的新形状是否接受。</div>',
        '<h2>1. 漏尿：参考原图与新动态</h2><p>照你的意见：身上只有一条尿线（嘴下方正中，高光每帧下移），不再是三条；因为身体下方空间不多，'
        '在身体正前方地面加一滩小水洼：同样的描边、淡黄、高光，落点有一圈波纹，边缘有溅射短线（像原图的「ぱちゃ」），漏的时候长大，'
        '之后静止，再干涸淡出。水滩按体型定大小，幼体相对更大，保证远处也看得见。</p>'
        '<div class="row"><img src="ref.png" style="max-width:600px"></div><img src="play-close.gif" style="width:822px">',
        '<h2>2. 游戏距离（按成长阶段）</h2><img src="play-distance.gif" style="width:660px"><img src="distance.png">',
        '<h2>3. 六帧、水滩贴图与各品种</h2><img src="frames.png"><img src="puddle.png"><img src="models.png">'
        '<p><a href="../leak-viewer/index.html">真实运行模型查看器（表情选 leak_flow 看身上尿线循环，可拖动旋转；查看器不画水滩）</a></p>',
        '<h3>时间表（tick）</h3><table><tr><th>时刻</th><th>阶段</th><th>表情（含身上尿线）</th><th>地上水滩</th><th>头顶符号</th></tr>'
        + ''.join(f'<tr>{"".join(f"<td>{E(c)}</td>" for c in row)}</tr>' for row in timing) + '</table>'
        '<p>流出时长 D：赤油 40–60，子油 30–50，成油 20–40（按自制在区间内平移，自制高取短端）。水滩最终直径：赤油 2.5、子油 1.5、成油 1.1 倍体宽，'
        '至少 0.35 格；中心在身体正前方 0.45 倍体宽处，后半被身体挡住。</p>'
        '<h3>几率（每次情绪尖峰；区间按自制平移，自制 0 取上端、100 取下端）</h3><table><tr><th></th><th>强烈刺激（被突然拎起／摔落／天敌惊吓／濒死）</th><th>中等刺激（被吓到、被训斥、巨响）</th></tr>'
        '<tr><td>赤油</td><td>50–80%</td><td>20–40%</td></tr><tr><td>子油</td><td>25–45%</td><td>5–15%</td></tr><tr><td>成油</td><td>5–15%</td><td>0</td></tr></table>'
        '<p>同一只漏过后 1200 tick 内不再漏。数值为项目设计，待你定。</p>',
        '<h2>4. 佩妮佩妮：新旧对比</h2><p>颜色恢复为肤色（去掉粉色尖端）；形状改成一个浑然一体的圆润小揪揪：不再是五段逐级收细的“石笋”，'
        '而是一整块圆角短柱加圆头，长度和上翘角度不变。左为原版本，右为当前。</p><img src="organ-peni.png">',
        '<h2>5. 麻姆麻姆：默认竖线与候选</h2><p>颜色恢复为肤色（去掉粉色垫）；不再突出身体，只剩一道竖线（A，已做进模型）。'
        'B–E 是同一位置的平面候选示意，选中后再做进模型。</p><img src="organ-mamu.png"><img src="mamu.png">']
    (OUT/'index.html').write_text('<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>激动漏尿与器官形状</title>'
                                  f'<style>{style}</style><body><a href="../index.html">预览总入口</a><h1>激动漏尿与器官形状</h1>{"".join(body)}</body></html>', encoding='utf-8')
    print('Wrote', OUT/'index.html')


if __name__ == '__main__':
    main()
