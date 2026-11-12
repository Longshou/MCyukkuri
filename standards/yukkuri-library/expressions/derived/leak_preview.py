"""Review page .local/previews/leak/: (1) the excited leak - one on-body stream from the mamu site in the leak faces and the
wet bottom (leak_wet_* in the baked sole's projection) - next to the user's reference panel (ref.png, kept in the page
folder), as frame strips and animated GIFs at close range and at game distance per growth stage; (2) the organ shapes -
peni before / after and the mamu line.
Renders go through scripts/preview.py strip (real runtime models and atlases). The wet bottom is a 2D mock-up laid on the
render's lower skin: each skin pixel's angle around the body and height above the base pick the texel the sole projection
would give it (WoundRenderer.soleTexture), with the stage and timing of the report's wiring table.

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
STAGES = [('赤油', .15), ('子油', .5), ('成油', 1.0)]  # growth scale (ordinary forms: baby 0.1-0.2, child 0.2-0.8, adult 0.8-1.2)
FLOW = 15  # flow frames of 4 ticks in the played sequence
# One played sequence: (face, ticks, wet texture or None, alpha). Stage = third of the flow; the dry fade is shortened here.
SEQUENCE = ([('leak_startle', 10, None, 1)]
            + [(('leak_flow', 'leak_flow_b', 'leak_flow_c')[i % 3], 4, f'leak_wet_{1+3*i//FLOW}', 1) for i in range(FLOW)]
            + [('leak_after', 30, 'leak_wet_3', 1), ('leak_after_b', 10, 'leak_wet_3', 1)]*2
            + [('neutral', 20, 'leak_wet_dry', a) for a in (1, .7, .4, .15)])
SHOWN = {'leak_startle': None, 'leak_flow': 'leak_wet_1', 'leak_flow_b': 'leak_wet_2', 'leak_flow_c': 'leak_wet_3',
         'leak_after': 'leak_wet_3', 'leak_after_b': 'leak_wet_3'}
VIEW = (25, 30)  # a player standing a few blocks away looks down on a small body
BG = (205, 214, 190, 255)


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
        return self.run(['--model', model, '--clip', clip, '--times', f'{time:g}s', '--view', str(view[0]), str(view[1])]
                        + (['--before', before] if before else []), size)


WET = {}
def wet(body, name, alpha=1., view=VIEW):
    """The body render with the wet-bottom texture mocked onto its lower skin (see the module note), over the page ground."""
    out = Image.new('RGBA', body.size, BG); out.alpha_composite(body)
    box = body.getbbox()
    if not name or not box: return out
    if name not in WET: WET[name] = Image.open(ASSETS/f'textures/props/body_wounds/{name}.png').convert('RGBA')
    texture = WET[name]; src = body.load(); px = out.load()
    width = (box[2]-box[0])*.86; cx = (box[0]+box[2])/2; height = width*.8*math.cos(math.radians(view[1]))
    low = [(c, r) for r in range(max(box[1], round(box[3]-height*.18)), box[3]) for c in range(box[0], box[2]) if src[c, r][3] > 200]
    if not low: return out
    skin = max(set(src[p][:3] for p in low), key=lambda colour: sum(1 for p in low if src[p][:3] == colour))
    az = math.radians(view[0])
    for c in range(box[0], box[2]):
        column = [r for r in range(box[1], box[3]) if src[c, r][3] > 200]
        if not column: continue
        base = column[-1]; s = max(-1., min(1., (c+.5-cx)/(width/2)))
        u = .5+(math.asin(s)+az)/(2*math.pi)
        for r in column:
            y = (base-r+.5)/height*9.6; v = (2-y)/2.5
            if not 0 <= v <= .8 or sum(abs(a-b) for a, b in zip(src[c, r][:3], skin)) > 60: continue
            t = texture.getpixel((min(1023, int(u*1024)), min(255, int(v*256))))
            if t[3]: px[c, r] = tuple(round(p*(1-t[3]*alpha/255)+q*t[3]*alpha/255) for p, q in zip(px[c, r][:3], t[:3]))+(255,)
    return out


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


def main():
    ap = argparse.ArgumentParser(description=__doc__); ap.add_argument('--node', default='node')
    ap.add_argument('--before', default='HEAD', help='Git ref of the organ shapes to compare against')
    args = ap.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    for old in ('stages.png', 'organ-peni-front.png', 'puddle.png', 'mamu.png'):
        (OUT/old).unlink(missing_ok=True)
    with tempfile.TemporaryDirectory(prefix='leak-', dir=ROOT/'.local/tmp' if (ROOT/'.local/tmp').is_dir() else None) as directory:
        render = Renderer(args.node, Path(directory)); files = {}
        faces = FRAMES+['neutral']
        above = {f: render('reimu', f, 360) for f in faces}
        front = {f: render('reimu', f, 360, (0, -2)) for f in FRAMES}
        files['frames.png'] = sheet([(crop(wet(front[f], SHOWN[f], view=(0, -2)), (.15, .35, .85, .9)), LABEL[f]) for f in FRAMES]
                                    + [(crop(wet(above[f], SHOWN[f])), LABEL[f]+'（斜上方）') for f in FRAMES], 6,
                                    '灵梦：六帧（上排正面略低，下排玩家常见的斜上方；底部湿痕按阶段 1→3，示意）')
        close = [(crop(wet(above[face], name, alpha)), ticks) for face, ticks, name, alpha in SEQUENCE]
        gif(OUT/'play-close.gif', close, 2)
        small = {label: {f: render('reimu', f, max(16, round(110*scale))) for f in faces} for label, scale in STAGES}
        distance = []
        for face, ticks, name, alpha in SEQUENCE:
            frame = Image.new('RGBA', (330, 130), BG); x = 14
            for label, _ in STAGES:
                im = wet(small[label][face], name, alpha); frame.alpha_composite(im, (x, 122-im.height)); x += im.width+40
            distance.append((frame, ticks))
        gif(OUT/'play-distance.gif', distance)
        files['distance.png'] = sheet([(distance[0][0], '惊'), (distance[8][0], '漏到一半'), (distance[FLOW][0], '漏完'),
                                       (distance[-3][0], '干涸淡出')], 4, '游戏距离（约 5 格外的实际像素大小；左起赤、子、成）')
        cells = []
        for model in ['marisa', 'alice', 'chen', 'myon', 'remilia', 'patchouli', 'reimu_deibu', 'house_reimu', 'reimu_bean']:
            cells += [(crop(wet(render(model, f, 300, (15, 20)), SHOWN[f], view=(15, 20))), f'{model} · {LABEL[f]}')
                      for f in ('leak_startle', 'leak_flow_c', 'leak_after')]
        files['models.png'] = sheet(cells, 6, '各品种（家油、豆形的脸不在身体下部，尿线只到下巴；底部湿痕为示意）')
        # Organs: old / current crops around the extended organ (the only part that differs between the two versions).
        size = 480
        def organ(clip, view, side):
            stage = render.clip('reimu', clip, .24, view, size, args.before)  # the extend clip's full length
            pair = Image.open(stage/'compare.png').convert('RGBA'); old, new = pair.crop((0, 0, size, size)), pair.crop((size, 0, 2*size, size))
            box = ImageChops.difference(new.convert('RGB'), old.convert('RGB')).convert('L').point(lambda v: 255 if v > 30 else 0).getbbox()
            if not box: box = (size//2-side//2, size//2-side//2, size//2+side//2, size//2+side//2)
            cx, cy = (box[0]+box[2])//2, (box[1]+box[3])//2
            area = (cx-side//2, cy-side//2, cx+side//2, cy+side//2)
            return [im.crop(area).resize((300, 300), Image.NEAREST) for im in (old, new)]
        cells = []
        for view, label in (((90, 0), '侧面'), ((30, -10), '斜前方略低'), ((0, -8), '正面略低')):
            old, new = organ('anatomy_peni_extend', view, 120)
            cells += [(old, f'上一版 · {label}'), (new, f'当前 · {label}')]
        files['organ-peni.png'] = sheet(cells, 6, '佩妮佩妮伸出（放大 2.5 倍；每组左为上一版，右为当前）')
        stage = render.clip('reimu', 'anatomy_mamu_extend', .24, (0, -20), size)
        files['organ-mamu.png'] = sheet([(crop(Image.alpha_composite(Image.new('RGBA', (size, size), BG), Image.open(stage/'atlas.png').convert('RGBA')),
                                               (.3, .45, .7, .85)).resize((384, 384), Image.NEAREST), 'A 竖线（定稿）')], 1, '麻姆麻姆伸出')
        for name, image in files.items(): image.save(OUT/name)
    E = html.escape
    timing = [('0', '触发（强烈情绪尖峰）', 'leak_startle（单帧）', '无', '「!」'),
              ('6–10', '开始漏', 'leak_flow（三帧各 4 tick 循环）', 'leak_wet_1', '—'),
              ('+ D/3、+ 2D/3', '持续漏', 'leak_flow', 'leak_wet_2 → leak_wet_3', '—'),
              ('D 结束后 60–100', '之后', 'leak_after（30／10 tick 循环）', 'leak_wet_3', '汗滴'),
              ('之后 200', '恢复', '平常表情', 'leak_wet_dry，透明度 1→0 淡出', '—')]
    style = ('body{background:#f2efe6;font:16px system-ui,"Microsoft YaHei";margin:20px;max-width:1500px}img{max-width:100%;image-rendering:pixelated;'
             'display:block;margin:8px 0}h2{margin-top:30px}table{border-collapse:collapse}td,th{border:1px solid #bbb;padding:4px 8px}'
             '.ask{background:#fff7d6;border:1px solid #d9c27a;padding:10px 14px}.row{display:flex;gap:16px;align-items:flex-start;flex-wrap:wrap}')
    body = [
        '<div class="ask"><b>请你确认：</b>漏尿（起点、居中、底部湿痕）与佩妮佩妮新形状是否接受，要改哪里；麻姆麻姆已按 A 定稿。</div>',
        '<h2>1. 漏尿：参考原图与新动态</h2><p>照你的意见：尿线从麻姆麻姆的位置开始，严格居中（原先偏了一格：嘴下那块贴图本身不对称，'
        '旧算法按贴图格子居中，所以偏到一侧）；去掉了地上水滩，改成像底部烧焦、叶子襁褓那样画在身体底部：身前底部一块描边的淡黄湿痕，'
        '边缘有几道往上渗的湿舌，漏的时候分三档长大，之后变成断续描边的干痕再淡出。湿痕按身体底部的投影贴，随体型自动缩放。'
        '页面里的湿痕是离线示意，游戏里由代码按同一投影画在身体上。</p>'
        '<div class="row"><img src="ref.png" style="max-width:600px"><img src="play-close.gif" style="width:560px"></div>',
        '<h2>2. 游戏距离（按成长阶段）</h2><img src="play-distance.gif" style="width:660px"><img src="distance.png">',
        '<h2>3. 六帧与各品种</h2><img src="frames.png"><img src="models.png">'
        '<p><a href="../leak-viewer/index.html">真实运行模型查看器（表情选 leak_flow 看身上尿线循环；查看器不画底部湿痕）</a></p>',
        '<h3>时间表（tick）</h3><table><tr><th>时刻</th><th>阶段</th><th>表情（含身上尿线）</th><th>身体底部</th><th>头顶符号</th></tr>'
        + ''.join(f'<tr>{"".join(f"<td>{E(c)}</td>" for c in row)}</tr>' for row in timing) + '</table>'
        '<p>流出时长 D：赤油 40–60，子油 30–50，成油 20–40（按自制在区间内平移，自制高取短端）。</p>'
        '<h3>几率（每次情绪尖峰；区间按自制平移，自制 0 取上端、100 取下端）</h3><table><tr><th></th><th>强烈刺激（被突然拎起／摔落／天敌惊吓／濒死）</th><th>中等刺激（被吓到、被训斥、巨响）</th></tr>'
        '<tr><td>赤油</td><td>50–80%</td><td>20–40%</td></tr><tr><td>子油</td><td>25–45%</td><td>5–15%</td></tr><tr><td>成油</td><td>5–15%</td><td>0</td></tr></table>'
        '<p>同一只漏过后 1200 tick 内不再漏。数值为项目设计，待你定。</p>',
        '<h2>4. 佩妮佩妮：上一版与当前</h2><p>改成一截小触角：粗细均匀的短圆柱，前端是圆头（不尖、不收细），整体是一块光滑网格，'
        '不再由方块拼接，所以前端没有台阶线；肤色，长度与上翘角度不变。</p><img src="organ-peni.png">',
        '<h2>5. 麻姆麻姆：A 竖线（定稿）</h2><img src="organ-mamu.png">']
    (OUT/'index.html').write_text('<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>激动漏尿与器官形状</title>'
                                  f'<style>{style}</style><body><a href="../index.html">预览总入口</a><h1>激动漏尿与器官形状</h1>{"".join(body)}</body></html>', encoding='utf-8')
    print('Wrote', OUT/'index.html')


if __name__ == '__main__':
    main()
