"""Review page .local/previews/leak/: the excited leak - the two leak faces (the startle with the first spurt, then the
stream with the small wet edge at the body bottom), the small ground puddle left where the stream ends, and the return
through existing library faces - next to the user's reference panel (ref.png, kept in the page folder), as a close-up GIF,
a game-distance GIF per growth stage and frame sheets.
Renders go through scripts/preview.py strip (real runtime models and atlases). Two 2D mock-ups stand in for client code
that is not written yet: the wet edge laid on the render's lower skin (each skin pixel's angle around the body and height
above the base pick the texel the sole projection would give it, WoundRenderer.soleTexture), and the puddle texture laid on
the ground plane of the same camera (orthographic approximation) with the size and timing of the report's wiring table.

    python standards/yukkuri-library/expressions/derived/leak_preview.py [--node node]
"""
from pathlib import Path
import argparse, html, json, math, os, subprocess, sys, tempfile
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
ASSETS = ROOT/'src/main/resources/assets/mcyukkuri'
SHARED = Path(os.environ.get('MCYUKKURI_PREVIEWS', ROOT.parent.parent/'.local/previews' if ROOT.parent.name == '.worktrees' else ROOT/'.local/previews'))
OUT = SHARED/'leak'
sys.path.insert(0, str(HERE)); import faces  # noqa: E402  (the decal boxes of the leak builder)
FRAMES = ['leak_startle', 'leak_flow']
LABEL = {'leak_startle': '惊：第一股', 'leak_flow': '漏 A', 'side_teary': '难为情（现有表情）', 'side_teary_b': '难为情 b',
         'satisfied': '松口气（现有表情）', 'neutral': '平常'}
LEAK = {'leak_startle', 'leak_flow'}  # derived atlases on the neutral geometry; the rest are library faces
BODY_WIDTH = .75  # blocks at growth scale 1 (YukkuriVariant, ordinary forms)
STAGES = [('赤油', .15), ('子油', .5), ('成油', 1.0)]  # growth scale (ordinary forms: baby 0.1-0.2, child 0.2-0.8, adult 0.8-1.2)
RATIO, FLOOR, DEPTH = .6, .12, .68  # puddle width = max(FLOOR, RATIO x body width) blocks (RATIO 0.5-0.7 by flow length); depth / width
OUT_FRONT = .2  # puddle centre ahead of the body's front edge, in puddle widths


def puddle_ratio(scale):
    """Puddle width over body width for this growth scale (the report's wiring table, mid flow length)."""
    width = BODY_WIDTH*scale; return max(FLOOR, RATIO*width)/width


# One played sequence: (face, ticks, 漏 A body marks alpha (0 = none), puddle growth 0-1 or None, puddle alpha, body shift in
# body widths). After the flow the 漏 A marks - the stream in the leak_flow atlas's mouth-effect cell and the leak_wet edge -
# stay on the body unchanged (they move with it) under the after face, and dry over their last 200 of 600 ticks; the puddle
# stays on the ground and fades over its last 100.
FLOW = 40  # D, mid adult / child range
SEQUENCE = ([('neutral', 10, 0, None, 1, 0), ('leak_startle', 8, 0, None, 1, 0)]
            + [('leak_flow', FLOW//4, 1, None, 1, 0)]*4
            + [('side_teary', 4, 1, g, 1, 0) for g in (.4, .7, 1)]
            + [('side_teary', 24, 1, 1, 1, 0), ('side_teary_b', 12, 1, 1, 1, 0), ('side_teary', 28, 1, 1, 1, 0)]
            + [('neutral', 4, 1, 1, 1, s) for s in (.15, .35, .6, .85, 1.1, 1.35)]
            + [('neutral', 20, 1, 1, 1, 1.35)]
            + [('neutral', 10, t, 1-.2*(1-a), a, 1.35) for t, a in ((.8, 1), (.6, 1), (.45, .8), (.3, .55), (.15, .3))]
            + [('neutral', 20, 0, None, 1, 1.35)])
VIEW = (25, 30)  # a player standing a few blocks away looks down on a small body
BG = (205, 214, 190, 255)


def font(size):
    for name in ('msyh.ttc', 'simhei.ttf', 'arial.ttf'):
        try: return ImageFont.truetype(name, size)
        except OSError: pass
    return ImageFont.load_default()


class Renderer:
    def __init__(self, node, work):
        self.node, self.work, self.count, self.cache = node, work, 0, {}

    def atlas(self, model, face, keep):
        """The face atlas; keep > 0 lays the leak_flow atlas's mouth-effect cell (the 漏 A stream) over it at that alpha -
        what the client's second pass of the face_effect_mouth decal with the leak_flow texture draws."""
        path = ASSETS/f'textures/entity/{model}/{face}.png'
        if not keep or face == 'leak_flow': return path
        out = self.work/f'{model}-{face}-{keep:g}.png'
        if not out.is_file():
            image = Image.open(path).convert('RGBA'); flow = Image.open(ASSETS/f'textures/entity/{model}/leak_flow.png').convert('RGBA')
            box = faces.boxes(json.loads((ASSETS/f'yukkuri/{model}.json').read_text(encoding='utf-8')), image.width, True)['face_effect_mouth']
            cell = flow.crop(box); cell.putalpha(cell.getchannel('A').point(lambda v: round(v*keep)))
            image.alpha_composite(cell, box[:2]); image.save(out)
        return out

    def __call__(self, model, face, size=300, view=VIEW, keep=0, clip=None):
        """Transparent render of the model wearing this face (leak atlases on the neutral geometry), the 漏 A stream kept
        at alpha keep, optionally in a clip's last pose (the mamu extended)."""
        key = (model, face, size, view, keep, clip)
        if key not in self.cache:
            self.count += 1; topic = f'r{self.count}'
            env = {**os.environ, 'PYTHONUTF8': '1', 'MCYUKKURI_PREVIEWS': str(self.work)}
            subprocess.run([sys.executable, str(ROOT/'scripts/preview.py'), 'strip', topic, '--model', model,
                            '--face', 'neutral' if face in LEAK else face, '--texture', str(self.atlas(model, face, keep)),
                            *(['--clip', clip, '--times', '0.24s'] if clip else ['--times', '0s']),
                            '--view', str(view[0]), str(view[1]), '--size', str(size), str(size),
                            '--columns', '1', '--node', self.node], check=True, env=env, cwd=ROOT, capture_output=True)
            self.cache[key] = Image.open(self.work/topic/'atlas.png').convert('RGBA')
        return self.cache[key]


MARKS = {n: Image.open(ASSETS/f'textures/props/body_wounds/{n}.png').convert('RGBA') for n in ('leak_wet',)}
PUDDLE = Image.open(ASSETS/'textures/effects/leak_puddle.png').convert('RGBA')


def wet(body, view=VIEW, mark=('leak_wet', 1.)):
    """The body render with a sole-projection skin mark (the 漏 A wet edge, at an alpha) mocked onto its lower
    skin (see the module note)."""
    texture, alpha = MARKS[mark[0]], mark[1]
    out = body.copy(); box = body.getbbox()
    if not box: return out
    src = body.load(); px = out.load()
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
            t = texture.getpixel((min(1023, int(u*1024)), min(255, int(v*256)))); k = t[3]*alpha/255
            if k: px[c, r] = tuple(round(p*(1-k)+q*k) for p, q in zip(px[c, r][:3], t[:3]))+(255,)
    return out


def scene(body, ratio, growth=None, alpha=1., shift=0., view=VIEW, pad=(0, 0, 0, 0)):
    """The body over the page ground with its puddle: the 32x32 top-down texture on the ground plane, centred on the
    body's front where the body stood (the shift moves only the body, in body widths to the right on screen), width
    = ratio x body width x growth, depth DEPTH of that; its centre lies OUT_FRONT puddle widths ahead of the body's front edge."""
    l, t, r, b = pad
    canvas = Image.new('RGBA', (body.width+l+r, body.height+t+b), BG)
    box = body.getbbox()
    if not box: return canvas
    width = (box[2]-box[0])*.86
    if growth:
        az, el = math.radians(view[0]), math.radians(view[1]); s = math.sin(el); d = ratio*width*growth
        fx, fy = -math.sin(az), math.cos(az)*s  # body forward on screen
        rx, ry = math.cos(az), math.sin(az)*s   # body right on screen
        # The front edge of the base: the lowest body pixel in the column the body front faces (side hair hangs lower
        # than the base, so the box bottom is not the ground).
        column = round((box[0]+box[2])/2+.4*width*fx); alpha_px = body.getchannel('A').load()
        base = max((y for y in range(box[1], box[3]) if alpha_px[column, y] > 200), default=box[3]-1)
        centre = (column+l+OUT_FRONT*d*fx, base+1+t+OUT_FRONT*d*fy)  # just in front: its back edge stays under the body
        texture = PUDDLE if alpha >= 1 else PUDDLE.copy()
        if alpha < 1: texture.putalpha(PUDDLE.getchannel('A').point(lambda v: round(v*alpha)))
        # screen = centre + (u-16)/32*d*right + (v-16)/32*d*DEPTH*forward; PIL wants the inverse map screen -> texture.
        a, bb, c, e = rx*d/32, fx*d*DEPTH/32, ry*d/32, fy*d*DEPTH/32; det = a*e-bb*c
        ia, ib, ic, ie = e/det, -bb/det, -c/det, a/det
        ox, oy = centre[0]-16*a-16*bb, centre[1]-16*c-16*e
        canvas.alpha_composite(texture.transform(canvas.size, Image.AFFINE, (ia, ib, -ia*ox-ib*oy, ic, ie, -ic*ox-ie*oy), Image.NEAREST))
    canvas.alpha_composite(body, (l+round(shift*width), t))
    return canvas


def crop(image, box=(.12, .3, .88, .98)):
    w, h = image.size; return image.crop((int(box[0]*w), int(box[1]*h), int(box[2]*w), int(box[3]*h)))


def sheet(cells, columns, title=None):
    w, h = max(c[0].width for c in cells), max(c[0].height for c in cells); top = 36 if title else 0; rows = (len(cells)+columns-1)//columns
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
    args = ap.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    for old in ('organ-peni.png', 'organ-mamu.png', 'stages.png', 'puddle.png', 'mamu.png'):
        (OUT/old).unlink(missing_ok=True)
    with tempfile.TemporaryDirectory(prefix='leak-', dir=ROOT/'.local/tmp' if (ROOT/'.local/tmp').is_dir() else None) as directory:
        render = Renderer(args.node, Path(directory)); files = {}
        def body(model, face, size, view=VIEW, keep=0, clip=None):
            """The render with the 漏 A body marks (stream decal and wet edge) at alpha keep."""
            image = render(model, face, size, view, keep, clip)
            return wet(image, view, ('leak_wet', keep)) if keep else image
        # Close-up of one adult over the whole timeline.
        adult = puddle_ratio(1.)
        close = [(crop(scene(body('reimu', f, 300, keep=w), adult, g, a, s, pad=(0, 0, 300, 60)), (0, .16, 1, 1)), ticks)
                 for f, ticks, w, g, a, s in SEQUENCE]
        gif(OUT/'play-close.gif', close, 2)
        # Game distance: baby, child, adult side by side at the size a player sees them about five blocks away.
        distance = []
        for f, ticks, w, g, a, s in SEQUENCE:
            frame = Image.new('RGBA', (560, 150), BG); x = 14
            for label, scale in STAGES:
                im = body('reimu', f, max(16, round(110*scale)), keep=w); pad = round(im.width*.6)
                cell = scene(im, puddle_ratio(scale), g, a, s, pad=(pad, 0, pad+round(im.width*1.5), pad))
                frame.alpha_composite(cell, (x-pad, 128-im.height)); x += round(im.width*2.4)+30
            distance.append((frame, ticks))
        gif(OUT/'play-distance.gif', distance)
        index = {'惊': 1, '漏': 3, '水滩刚成形': 8, '走开后水滩留在原地': 18}
        files['distance.png'] = sheet([(distance[i][0], label) for label, i in index.items()], 2,
                                      '游戏距离（约 5 格外的实际像素大小；左起赤、子、成）')
        # The two frames, front and from above, then the return faces.
        front = [(crop(body('reimu', f, 360, (0, -2), int(f == 'leak_flow')), (.15, .35, .85, .9)), LABEL[f]+'（正面略低）') for f in FRAMES]
        above = [(crop(scene(body('reimu', f, 360, keep=int(f == 'leak_flow')), adult)), LABEL[f]+'（斜上方）') for f in FRAMES]
        after = [(crop(scene(body('reimu', f, 360, keep=1), adult, 1)), LABEL[f][:3]+'＋漏A痕迹＋水滩') for f in ('side_teary', 'satisfied')]
        files['frames.png'] = sheet(front+above+after, 3, '灵梦：两帧与之后（只换表情，漏 A 身上的尿线和湿边原样保留，地上小水滩；湿边为示意）')
        cells = []
        for model in ['marisa', 'alice', 'chen', 'myon', 'remilia', 'patchouli', 'reimu_deibu', 'house_reimu', 'reimu_bean']:
            cells += [(crop(scene(body(model, f, 300, (15, 20), int(f == 'leak_flow')), adult, None, view=(15, 20))), f'{model} · {LABEL[f]}')
                      for f in FRAMES]
        files['models.png'] = sheet(cells, 6, '各品种（家油、豆形、灵胖的麻姆麻姆在嘴的高度以下、贴图格之外：脸上不画尿线，只有底部湿边；湿边为示意）')
        # The smallest bodies next to a normal adult: 漏 A with the mamu extended, the start low and clear of the mouth.
        small = []
        for model, scale, label in (('reimu', 1., '普通成油'), ('reimu', .15, '赤油（游戏像素放大）'), ('reimu_bean', 1., '豆油（不足油）'),
                                    ('marisa_bean', 1., '豆油魔理沙'), ('house_reimu', 1., '家油（最小体型）'), ('reimu_deibu', 1., '灵胖')):
            size = 300 if scale == 1 else max(16, round(110*scale))
            im = crop(scene(body(model, 'leak_flow', size, (15, 8), 1, 'anatomy_mamu_extend'), adult, None, view=(15, 8)), (.1, .2, .9, 1))
            if scale != 1: im = im.resize((240, round(im.height*240/im.width)), Image.NEAREST)
            small.append((im, label))
        files['small.png'] = sheet(small, 6, '最小体型与普通成油（漏 A，麻姆麻姆伸出；起点按从身体底部往上量，豆油、家油、灵胖脸上不再有尿线）')
        texture = Image.alpha_composite(Image.new('RGBA', PUDDLE.size, (120, 150, 90, 255)), PUDDLE).resize((192, 192), Image.NEAREST)
        files['puddle-texture.png'] = sheet([(texture, 'leak_puddle（32×32 俯视，放大 6 倍）')], 1)
        for name, image in files.items(): image.save(OUT/name)
    E = html.escape
    timing = [('0', '触发（强烈／中等情绪尖峰）', 'leak_startle（单帧，第一股）', '—', '—', '「!」'),
              ('s = 6–10', '漏', 'leak_flow（单帧，漏 A）', 'leak_wet 小湿边', '—', '—'),
              ('s + D', '漏完', '难为情 side_teary（现有动画）；兴奋型尖峰用松口气 satisfied', '漏 A 的身上尿线与湿边原样保留，随身体走', '在身前地上出现，约 10 tick 摊开', '汗滴'),
              ('s + D + 60–100', '恢复', '回到原来的情绪表情', '仍在', '留在原地，油库里走开也不动', '—'),
              ('漏完后 400–600', '干', '—', '最后 200 tick 淡出', '最后 100 tick（500–600）缩小到 80% 并淡出', '—')]
    style = ('body{background:#f2efe6;font:16px system-ui,"Microsoft YaHei";margin:20px;max-width:1500px}img{max-width:100%;image-rendering:pixelated;'
             'display:block;margin:8px 0}h2{margin-top:30px}table{border-collapse:collapse}td,th{border:1px solid #bbb;padding:4px 8px}'
             '.ask{background:#fff7d6;border:1px solid #d9c27a;padding:10px 14px}.row{display:flex;gap:16px;align-items:flex-start;flex-wrap:wrap}')
    body = [
        '<div class="ask"><b>请你确认：</b>漏尿只留「惊：第一股」和「漏 A」两帧，漏完在身前地上形成小水滩（大小见下），'
        '之后的表情用现有的难为情／松口气；是否接受，水滩大小要不要再调。</div>',
        '<h2>1. 参考原图与完整过程</h2><p>照你的意见：只留第一股和漏 A；漏 B、漏 C、之后 A／B 和身体底部逐档长大的湿痕都去掉了，'
        '漏 A 保留它画里那一小块底部湿边。漏完时在身前地上摊开一小滩，按体型缩放（比上一版的水滩小得多，成油约 0.6 倍体宽），'
        '它是地上的痕迹，油库里走开后留在原地，过一会儿缩小淡出。之后只换表情（现有的难为情／松口气），漏 A 身上的尿线和底部湿边原样保留、跟着身体走，和水滩一起慢慢干掉淡出。尿的起点按麻姆麻姆从身体底部往上量：豆油、家油、灵胖的麻姆麻姆在嘴的高度以下，脸上不再画尿线，只留底部湿边。'
        '页面里的底部湿边和水滩是离线示意，游戏里由代码画。</p>'
        '<div class="row"><img src="ref.png" style="max-width:600px"><img src="play-close.gif" style="width:760px"></div>',
        '<h2>2. 游戏距离（按成长阶段）</h2><p>赤油身体太小，水滩取下限 0.12 格（约等于它的体宽），否则远处看不见；'
        '子油、成油按体宽 0.5–0.7 倍（漏得越久越大）。</p><img src="play-distance.gif" style="width:1120px"><img src="distance.png">',
        '<h2>3. 两帧、之后与各品种</h2><img src="frames.png"><img src="small.png"><img src="models.png"><img src="puddle-texture.png">',
        '<h3>时间表（tick）</h3><table><tr><th>时刻</th><th>阶段</th><th>表情（含身上尿线）</th><th>身体底部</th><th>地上水滩</th><th>头顶符号</th></tr>'
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
