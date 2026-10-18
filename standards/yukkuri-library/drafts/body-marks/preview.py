"""Author and render the pending body-mark material study on current models.

Pure offline mesh rendering and procedural surface materials, without browser,
Java compilation, runtime writes, or external image extraction. Requires numpy,
Pillow and Node. Run from anywhere; outputs go to .local/previews/body-marks/.
"""
from pathlib import Path
import argparse
import hashlib
import json
import math
import subprocess

import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
OUT = ROOT / '.local/previews/body-marks'
WORK = ROOT / '.local/body-marks-work'
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
RECIPE = json.loads((HERE / 'recipe.json').read_text(encoding='utf-8'))
PALETTE = {k: np.array([int(v[i:i+2], 16) for i in (1, 3, 5)], np.float32)
           for k, v in RECIPE['palette'].items()}
BG = '#f4f0e6'
INK = '#303b32'
MUTED = '#777b70'


def font(size):
    return ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', size)


def smooth(a, b, x):
    t = np.clip((x-a)/(b-a), 0, 1)
    return t*t*(3-2*t)


def noise(x, y, seed=0):
    """Continuous world-space variation; stable when the camera changes."""
    return (np.sin(x*3.7+y*2.3+seed)*np.cos(y*4.1-x*.9+seed*.7)*.55
            + np.sin(x*9.2-y*6.7+seed*1.3)*.28
            + np.sin(x*22.4+y*16.3-seed)*.12
            + np.sin(x*53.1-y*41.7+seed)*.05)


def grain_noise(x, y, seed=0):
    """Non-periodic value noise; avoids a woven grid in close-up mould."""
    ix, iy = np.floor(x), np.floor(y)
    fx, fy = x-ix, y-iy
    fx, fy = fx*fx*(3-2*fx), fy*fy*(3-2*fy)
    def at(dx, dy):
        h = (ix.astype(np.float64)+dx)*127.1+(iy.astype(np.float64)+dy)*311.7+seed*74.7
        return (np.mod(np.sin(h)*43758.5453, 1)*2-1).astype(np.float32)
    return (at(0,0)*(1-fx)+at(1,0)*fx)*(1-fy)+(at(0,1)*(1-fx)+at(1,1)*fx)*fy


class Surface:
    def __init__(self, rgba, buf):
        self.base = rgba
        self.valid = buf[..., 7] > .5
        p = buf[self.valid]
        self.u = np.arctan2(p[:, 0]/6.4, -(p[:, 2]-.5)/5.3)*6.4
        self.v = p[:, 1]
        self.light = p[:, 6, None]
        self.color = rgba[self.valid, :3].astype(np.float32)

    def put(self, key, amount):
        a = np.clip(amount, 0, 1)[:, None]
        col = PALETTE[key] if isinstance(key, str) else np.array(key)
        self.color = self.color*(1-a)+col*self.light*a

    def xy(self, u, v, size=1, tilt=0):
        x = (self.u-u)/size
        y = (self.v-v)/size
        c, s = math.cos(tilt), math.sin(tilt)
        return c*x+s*y, -s*x+c*y

    def finish(self):
        if not np.isfinite(self.color).all():
            raise ValueError('Non-finite procedural material output')
        out = self.base.copy()
        out[self.valid, :3] = np.clip(self.color, 0, 255).astype(np.uint8)
        return Image.fromarray(out)


def blob(x, y, rx=1, ry=1, seed=0, rough=.2):
    return np.sqrt((x/rx)**2+(y/ry)**2)+noise(x*1.7, y*1.7, seed)*rough


def stroke(x, y, points, width):
    """Tapered authored path; width envelope suppresses round end caps."""
    field = np.zeros_like(x)
    for i, (a, b) in enumerate(zip(points, points[1:])):
        ax, ay = a
        bx, by = b
        vx, vy = bx-ax, by-ay
        t = np.clip(((x-ax)*vx+(y-ay)*vy)/(vx*vx+vy*vy), 0, 1)
        phase = (i+t)/(len(points)-1)
        radius = width*(.12+.88*np.clip(np.sin(np.pi*phase), 0, 1)**.55)
        dist = np.hypot(x-ax-t*vx, y-ay-t*vy)
        field = np.maximum(field, 1-smooth(radius*.5, radius, dist))
    return field


def mold(s, level):
    size = RECIPE['mold']['sizes'][level-1]
    # Stable nested sequence. More colonies appear as older colonies expand.
    sites = [(4.7, 3.3), (-4.3, 1.65), (6.9, 1.85), (-5.9, 4.3),
             (1.9, .85), (7.7, 4.5), (-1.4, 6.25)]
    for j, (u, v) in enumerate(sites[:RECIPE['mold']['colonies'][level-1]]):
        x, y = s.xy(u, v, size*(1 if j%2 == 0 else .72), -.25+j*.43)
        rng = np.random.default_rng(RECIPE['mold']['seed']+j)
        # Unequal overlapping patches, with thin diffuse outer bloom.
        for k in range(7):
            cx, cy = rng.normal(0, .36, 2)
            rx, ry = rng.uniform(.22, .49), rng.uniform(.17, .38)
            d = blob(x-cx, y-cy, rx, ry, k+j*3, .23)
            s.put('mold_halo', (1-smooth(.95, 1.55, d))*.24)
            grain = .76+.24*grain_noise(x*45, y*45, k+3)
            s.put('mold_fuzz', (1-smooth(.9, 1.18, d))*.7)
            s.put('mold_sage', (1-smooth(.76, 1.02, d))*.94*grain)
            s.put('mold_mid', (1-smooth(.35, .83, d))*.86*grain)
            s.put('mold_deep', (1-smooth(.04, .4, d))*.72)
        # Fine powder and satellite spores, kept sparse and irregular.
        for k in range(105):
            cx, cy = rng.normal(0, .55, 2)
            radius = rng.uniform(.009, .034)
            d = np.hypot(x-cx, (y-cy)*1.15)
            a = 1-smooth(radius*.45, radius, d)
            s.put('mold_fuzz' if k%4 else 'mold_spore', a*rng.uniform(.3, .8))


SCAR_PATH = [(-1.0, -.13), (-.62, -.06), (-.26, .045), (.1, .025), (.49, .14), (.98, .15)]


def scar(s):
    for j, (u, v, size, tilt) in enumerate([(4.6, 2.8, 1.16, -.3), (-4.0, 1.7, .68, .16)]):
        x, y = s.xy(u, v, size, tilt)
        s.put('scar_shadow', stroke(x, y+.045, SCAR_PATH, .13)*.46)
        s.put('scar_rim', stroke(x, y, SCAR_PATH, .105)*.8)
        s.put('scar_core', stroke(x, y-.018, SCAR_PATH, .058))
        for k, points in enumerate([[(-.45, -.01), (-.5, .15), (-.58, .22)],
                                    [(.38, .11), (.42, -.015), (.47, -.1)]]):
            s.put('scar_shadow', stroke(x, y, points, .03)*.3)
            s.put('scar_core', stroke(x, y-.025, points, .023)*.65)


def dirt(s, level):
    rng = np.random.default_rng(RECIPE['dirt']['seed'])
    floor = (1-smooth(.3, 1.55+level*.1, s.v))
    variation = .6+.4*noise(s.u*.7, s.v*1.2, 3)
    s.put('dust', floor*variation*(.18+.1*level))
    sites = [(4.8, 1.9, 1.2, -.2), (-4.5, 1.1, .9, .3), (7.4, .9, 1.3, -.05),
             (-6.9, 3.1, .8, -.7), (1.1, .65, .8, .1)]
    for j, (u, v, size, tilt) in enumerate(sites[:level+2]):
        x, y = s.xy(u, v, size, tilt)
        field = blob(x, y, 1, .35, j*7, .25)
        s.put('dust', (1-smooth(.6, 1.5, field))*(.17+.12*level))
        for k in range(6+level*2):
            cx, cy = rng.uniform(-.8, .8), rng.uniform(-.24, .24)
            length = rng.uniform(.18, .5)
            points = [(cx-length, cy-.02), (cx, cy), (cx+length*.6, cy+.03)]
            s.put('mud' if k%3 else 'mud_light', stroke(x, y, points, rng.uniform(.025, .075))*(.3+level*.12))
        for k in range(20+level*12):
            cx, cy = rng.uniform(-1, 1), rng.normal(0, .24)
            r = rng.uniform(.012, .048)*(1+level*.13)
            mask = 1-smooth(r*.6, r, np.hypot(x-cx, y-cy))
            s.put('mud_deep' if k%5 == 0 else 'mud', mask*rng.uniform(.18, .7))


def scratch(s):
    x, y = s.xy(4.7, 2.9, 1.05, -.35)
    halo = blob(x, y, .9, .65, 6, .28)
    s.put('scrape_halo', (1-smooth(.55, 1.35, halo))*.36)
    for k, (cx, length) in enumerate([(-.44, .65), (-.08, .95), (.23, .76)]):
        points = [(cx-.2, -length*.53), (cx-.015, -.03), (cx+.09, length*.5)]
        s.put('scrape', stroke(x, y, points, .055)*.83)
        s.put('scrape_light', stroke(x-.045, y+.012, points, .035)*.82)
    rng = np.random.default_rng(28)
    for i in range(30):
        cx, cy = rng.uniform(-.7, .7), rng.uniform(-.5, .5)
        r = rng.uniform(.009, .027)
        s.put('scrape', (1-smooth(r*.5, r, np.hypot(x-cx, y-cy)))*.38)


def dent(s):
    x, y = s.xy(4.6, 2.8, 1.15, -.2)
    d = blob(x, y, 1, .62, 14, .19)
    s.put('dent_halo', (1-smooth(.3, 1.3, d))*.42)
    s.put('dent', (1-smooth(.1, 1.0, d))*.38)
    s.put('dent_core', (1-smooth(0, .6, d))*.24)
    s.put('scar_core', stroke(x, y, [(-.8, -.18), (-.3, -.4), (.3, -.41), (.76, -.25)], .04)*.36)


def tear(s):
    x, y = s.xy(4.65, 2.9, 1.12, -.31)
    center = .085*np.sin(x*3.8)+.028*np.sin(x*11)
    envelope = np.clip(1-x*x, 0, 1)**.62
    jag = .028*np.sin(x*27)+.018*np.sin(x*43+.8)
    width = .29*envelope+jag*envelope
    dy = np.abs(y-center)
    limit = (np.abs(x)<1.0).astype(float)
    outer = (1-smooth(width+.04, width+.15, dy))*limit
    s.put('tear_shadow', outer*.55)
    s.put('tear_lip', (1-smooth(width+.01, width+.085, dy))*limit*.95)
    inner = (1-smooth(width-.025, width+.005, dy))*limit
    s.put('paste_dark', inner)
    # Uneven, matte bean paste. Small highlights stay within the opening.
    s.put('paste', inner*(.5+.3*noise(x*4, y*4, 8)))
    rng = np.random.default_rng(28)
    for k in range(25):
        cx, cy = rng.uniform(-.85, .85), rng.uniform(-.18, .18)
        rx, ry = rng.uniform(.04, .12), rng.uniform(.025, .065)
        d = blob(x-cx, y-cy, rx, ry, k, .12)
        s.put('paste_mid' if k%3 else 'paste_light', (1-smooth(.5, 1.1, d))*inner*(.55 if k%3 else .36))
    # Light catches the lower torn skin, without a concentric pink ring.
    edge = (1-smooth(.013, .05, np.abs(y-center+width+.035)))*limit*envelope
    s.put('tear_light', edge*.88)
def char(s):
    top = .63+.15*np.sin(s.u*2.6)+.09*np.sin(s.u*7.1)
    edge = s.v-top
    s.put('toast', (1-smooth(.05, .7, edge))*.54)
    s.put('char_rim', (1-smooth(-.12, .2, edge))*.82)
    s.put('char', (1-smooth(-.35, -.01, edge))*.96)
    # Broken dry plates, no six repeated starburst stamps.
    rng = np.random.default_rng(83)
    for k in range(24):
        u = -12+k
        x, y = s.xy(u, .35, 1)
        points = [(-.3, -.4), (-.15, -.06), (.03, .1), (-.06, .42+rng.random()*.2)]
        s.put('char_light', stroke(x, y, points, .035)*(s.v<top)*.55)


def old(s, kind):
    """Old visual grammar, matched location/size; not a game capture."""
    x, y = s.xy(4.7, 2.9, 1.2, -.2)
    if kind == 'mold':
        d = blob(x, y, 1, .82, 0, .08)
        s.put((98, 142, 84), (d<1)*1.)
        for i in range(5):
            a, r = i*2.3999+3, .4+.12*(i%3)
            d = blob(x-math.cos(a)*r, y-math.sin(a)*r, .26, .21, 0, .09)
            s.put((204, 226, 222), (d<1)*1.)
        for i in range(8):
            a, r = i*1.7+2, .5*math.sqrt((i+1)/9)
            s.put((44, 76, 58), (blob(x-math.cos(a)*r, y-math.sin(a)*r, .1, .09)<1)*1.)
    elif kind == 'scar':
        path = [(-1.7, -.45), (-.35, .1), (1.6, .5)]
        s.put((196, 136, 132), stroke(x, y, path, .22))
        s.put((236, 208, 202), stroke(x, y, path, .12))
        for k in (-1, 0, 1):
            s.put((196, 136, 132), stroke(x, y, [(k*.85-.12, -.75+.18*k), (k*.85+.12, .9+.1*k)], .10))
    elif kind == 'dirt':
        s.put((116, 90, 66), (blob(x, y, 1, .8, 0, .13)<1)*1.)
        for i in range(3):
            a = i*2.1+6
            s.put((78, 60, 48), (blob(x-math.cos(a)*.55, y-math.sin(a)*.55, .42, .34)<1)*1.)
        s.put((156, 134, 104), (blob(x+.25, y-.15, .3, .24)<1)*1.)
    else:
        for rx, ry, color in [(1.13,.79,(194,135,130)), (1,.7,(109,63,65)), (.8,.55,(52,27,38)), (.62,.43,(86,46,58))]:
            s.put(color, (blob(x,y,rx,ry,0,.12)<1)*1.)


def material(rgba, buf, kind, level=2):
    s = Surface(rgba, buf)
    if kind.startswith('old_'):
        old(s, kind[4:])
    elif kind == 'mold':
        mold(s, level)
    elif kind == 'dirt':
        dirt(s, level)
    elif kind == 'scar':
        scar(s)
    elif kind == 'scratch':
        scratch(s)
    elif kind == 'dent':
        dent(s)
    elif kind == 'tear':
        tear(s)
    elif kind in ('core', 'broken'):
        raise ValueError('Core exposure requires cavity geometry, never a small surface mark')
    elif kind == 'char':
        char(s)
    return s.finish()


def render_buffers(node):
    WORK.mkdir(parents=True, exist_ok=True)
    views = {
        'reimu': dict(model='reimu', width=720, height=660, az=.36, el=.12, span=11.7, cy=8.5),
        'marisa': dict(model='marisa', width=720, height=660, az=.36, el=.12, span=11.7, cy=8.5),
        'detail': dict(model='reimu', width=660, height=480, az=.4, el=.08, span=2.1, cx=4.0, cy=2.8, cz=-3.8),
        'side': dict(model='reimu', width=720, height=660, az=1.1, el=.16, span=11.7, cy=8.5),
        'core': dict(model='reimu', width=720, height=660, az=.36, el=.12, span=11.7, cy=8.5, cavity='intact'),
        'broken': dict(model='reimu', width=720, height=660, az=.36, el=.12, span=11.7, cy=8.5, cavity='broken'),
        'core_close': dict(model='reimu', width=720, height=580, az=.36, el=.12, span=4.25, cx=1.8, cy=3.8, cz=-2, cavity='intact'),
        'broken_close': dict(model='reimu', width=720, height=580, az=.36, el=.12, span=4.25, cx=1.8, cy=3.8, cz=-2, cavity='broken'),
        'core_side': dict(model='reimu', width=720, height=580, az=.95, el=.12, span=4.25, cx=1.8, cy=3.8, cz=-2, cavity='intact'),
    }
    jobs = []
    for key, view in views.items():
        tex = ASSETS / 'textures/entity' / view['model'] / 'neutral.png'
        rgba = WORK / (view['model']+'.texture')
        rgba.write_bytes(Image.open(tex).convert('RGBA').tobytes())
        jobs.append(dict(**view, texture=str(rgba), out=str(WORK/key)))
    (WORK/'jobs.json').write_text(json.dumps(jobs), encoding='utf-8')
    subprocess.run([node, str(HERE/'surface.cjs'), str(WORK/'jobs.json')], check=True, cwd=ROOT)
    result = {}
    for key, job in zip(views, jobs):
        h, w = job['height'], job['width']
        result[key] = (np.fromfile(WORK/(key+'.rgba'), np.uint8).reshape(h,w,4),
                       np.fromfile(WORK/(key+'.f32'), np.float32).reshape(h,w,8))
    return result


def panel(img, width, height):
    out = Image.new('RGB', (width, height), '#fffdf7')
    img = img.copy()
    img.thumbnail((width, height), Image.Resampling.LANCZOS)
    out.paste(img, ((width-img.width)//2, (height-img.height)//2), img)
    return out


def sheet(title, subtitle, cards, columns, image_size=(460, 410), footer=''):
    iw, ih = image_size
    gap, margin, top, label = 18, 36, 138, 92
    rows = math.ceil(len(cards)/columns)
    width = margin*2+columns*iw+(columns-1)*gap
    height = top+rows*(ih+label+gap)+58
    out = Image.new('RGB', (width,height), BG)
    d = ImageDraw.Draw(out)
    d.text((margin,24), title, font=font(36), fill=INK)
    d.text((margin,82), subtitle, font=font(19), fill=MUTED)
    for i, (name, note, img) in enumerate(cards):
        x, y = margin+(i%columns)*(iw+gap), top+(i//columns)*(ih+label+gap)
        d.rounded_rectangle((x,y,x+iw,y+ih+label), radius=16, fill='#fffdf7')
        out.paste(panel(img,iw,ih), (x,y))
        d.text((x+20,y+ih+4), name, font=font(24), fill=INK)
        d.text((x+20,y+ih+43), note, font=font(16), fill=MUTED)
    d.text((margin,height-39), footer or '2026.10.18  /  当前角色网格的离线材质提案  /  待确认，尚未接入游戏', font=font(17), fill=MUTED)
    return out


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--node', default='node')
    args = ap.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    buffers = render_buffers(args.node)
    def img(kind, view='reimu', level=2):
        return material(*buffers[view], kind, level)
    cards = [
        ('霉斑 · 灰绿菌落', '粉霜薄边，深浅错落；从原位蔓延', img('mold','marisa',3)),
        ('伤疤 · 面皮愈合', '淡米色收口，细长、微凹、两端收尖', img('scar')),
        ('擦伤 · 浅表损伤', '断续细划痕，少量面皮擦破', img('scratch')),
        ('裂伤 · 豆沙外露', '不规则窄裂口，浅色面皮托住深馅', img('tear')),
        ('脏污 · 尘土与擦蹭', '下缘积尘，侧面擦痕，零星泥粒', img('dirt','marisa',3)),
        ('烤足 · 焦化底缘', '沿足缘变深、干裂，向上渐淡', img('char')),
    ]
    sheet('身体纹样 · 材质改稿', '面皮 / 粉霜 / 豆沙 / 尘土     让状态可辨认，也保留角色本身。', cards, 3).save(OUT/'overview.png')
    details = [(name, note, img(kind,'detail',3)) for name,note,kind in [
        ('霉斑', '细碎菌落与粉霜外缘','mold'), ('旧疤', '收细的浅色新生面皮','scar'),
        ('擦伤', '细线、轻微破皮与细碎擦痕','scratch'), ('裂口', '面皮翻边与哑光豆沙','tear'),
        ('脏污', '低对比浮尘与方向性擦痕','dirt'), ('压伤', '局部轻微暗沉，避免紫色圆章','dent')]]
    sheet('近看纹样', '直接放大同一块角色表面；纹样受网格、光照、五官与头发遮挡。', details, 3, (460,335)).save(OUT/'details.png')
    comparison = []
    for kind,name in [('mold','霉斑'),('scar','伤疤'),('tear','裂伤'),('dirt','脏污')]:
        comparison.append((name+' · 旧图形', '按现有图形规则作同位置示意', img('old_'+kind,'detail')))
        comparison.append((name+' · 新提案', '当前草稿，尚未接入游戏', img(kind,'detail',3)))
    sheet('旧图形 → 新材质', '相同角色、相同局部、相同光照；旧栏为图形复现，不是游戏截图。', comparison, 4, (350,270)).save(OUT/'comparison.png')
    stages = [('霉斑 · '+name, '相同落点逐步扩散', img('mold','marisa',level))
              for level,name in enumerate(['早期','中期','晚期','终期'],1)]
    stages += [('脏污 · '+name, '尘土、擦痕与泥粒逐步积累', img('dirt','marisa',level))
               for level,name in enumerate(['轻度','中度','重度'],1)]
    stages.append(('完整角色 · 干净对照','同一机位与模型',img('clean','marisa')))
    sheet('蔓延与积累', '霉斑保留四档；脏污保留三档。图形生长，不靠简单盖满大色块。', stages,4,(350,335)).save(OUT/'stages.png')
    injury = [('压伤','低对比的局部暗沉',img('dent')), ('普通裂伤','仅面皮破开与豆沙外露',img('tear')),
              ('中枢外露 · 大缺损','大创口向身体深处敞开',img('clean','core')),('中枢破裂','核心仍在体内，只增加裂损',img('clean','broken')),
              ('烤足 · 斜前','焦色由足缘向上衰减',img('char')),('烤足 · 侧面','破碎焦边，避免规则烙印',img('char','side'))]
    sheet('其他受伤状态', '沿用 wiki 的面皮与馅料逻辑；具体颜色、纹路和覆盖量为原创设计。', injury,3).save(OUT/'injuries.png')
    cavity_cards = [
        ('普通裂伤', '只露豆沙，核心仍被遮住', img('tear')),
        ('中枢外露 · 大创口', '开口约半个体宽，通向深处核心', img('clean','core')),
        ('中枢破裂', '同一个中央核心出现损伤', img('clean','broken')),
        ('外露近看', '面皮断面 → 豆沙腔壁 → 核心', img('clean','core_close')),
        ('破裂近看', '裂损位于核心，未改变核心位置', img('clean','broken_close')),
        ('侧向观察', '腔壁自然遮住部分核心', img('clean','core_side')),
    ]
    sheet('中枢外露 · 大创口与真实深度', '中枢在身体中央；只有大范围面皮与馅料缺损，才会从外部看见它。',
          cavity_cards,3,(460,410), '离线立体草稿 / 开口、腔壁与核心使用三维几何 / 尺寸为本次美术提案，尚未接入游戏').save(OUT/'core-cavity.png')
    (OUT/'index.html').write_text((HERE/'preview.html').read_text(encoding='utf-8'), encoding='utf-8')
    report = {'status': RECIPE['status'], 'runtime_selected': False,
              'outputs': {p.name: {'size': Image.open(p).size, 'sha256': hashlib.sha256(p.read_bytes()).hexdigest()}
                          for p in OUT.glob('*.png')},
              'inputs': {str(p.relative_to(ROOT)): hashlib.sha256(p.read_bytes()).hexdigest()
                         for p in [HERE/'recipe.json', HERE/'preview.py', HERE/'surface.cjs', HERE/'cavity.cjs']}}
    reports = ROOT/'.local/reports'
    reports.mkdir(parents=True, exist_ok=True)
    (reports/'body-marks-preview.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print(OUT/'overview.png')


if __name__ == '__main__':
    main()
