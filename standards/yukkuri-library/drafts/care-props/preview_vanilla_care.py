"""Offline preview sheets for the placed pet-care goods (placed.py): the goods beside vanilla blocks, every dye style, the
bowl fills by portion, and an optional before/after row.

Local preview only. Vanilla block models are read from the locally downloaded client jar (MCYUKKURI_VANILLA_JAR, default
.local/vanilla-ref/dl/client.jar) and textures from MCYUKKURI_VANILLA; nothing vanilla is copied into the repository.
Meshes come from this tree's runtime assets (run build.py and the exporters first). MCYUKKURI_BEFORE may name a tree that
holds src/main/resources/assets/mcyukkuri of the previous meshes for the before/after sheet.
Output: <MCYUKKURI_PREVIEWS or .local/previews>/pet-goods/{vanilla.png, bowls.png, fills.png, nests.png, large_nests.png,
toilets.png, before_after.png, index.html}.
Usage: python preview_vanilla_care.py [vanilla bowls fills nests large toilets before]
"""
import json, os, sys, zipfile
from pathlib import Path
import numpy as np
sys.path.insert(0, str(Path(__file__).resolve().parent))
import render_preview as R
import placed
from PIL import Image, ImageDraw, ImageFont

PREVIEWS = Path(os.environ.get('MCYUKKURI_PREVIEWS', Path(__file__).resolve().parents[4] / '.local/previews'))
if not (PREVIEWS / 'index.html').exists() and (R.ROOT.parents[1] / '.local/previews').exists():
    PREVIEWS = R.ROOT.parents[1] / '.local/previews'            # worktree: share the main checkout's preview hub
OUT = PREVIEWS / 'pet-goods'
JAR = Path(os.environ.get('MCYUKKURI_VANILLA_JAR', R.VANILLA.parents[2] / 'dl/client.jar'))
F = ImageFont.truetype(os.environ.get('MCYUKKURI_FONT', 'C:/Windows/Fonts/msyh.ttc'), 16)
FS = ImageFont.truetype(os.environ.get('MCYUKKURI_FONT', 'C:/Windows/Fonts/msyh.ttc'), 13)
BG = (214, 222, 230, 255)
FACES = {'up': ([0, 1, 0], 1), 'down': ([0, -1, 0], 1), 'north': ([0, 0, -1], 2), 'south': ([0, 0, 1], 2),
         'west': ([-1, 0, 0], 0), 'east': ([1, 0, 0], 0)}
LABELS = {
    'food_bowl': {'': '奶油薄荷（青）', 'hearts': '红心（红）', 'polka': '樱粉波点（粉红）', 'sailor': '水手条纹（淡蓝）', 'paws': '柠檬爪印（黄）',
                  'terracotta': '赤陶流釉（橙）', 'steel': '不锈钢（淡灰）'},
    'yukkuri_nest': {'': '草莓（红）', 'gingham': '蓝格（淡蓝）', 'polka': '粉波点（粉红）', 'floral': '薄荷碎花（黄绿）', 'lavender': '薰衣草绗缝（紫）',
                     'teddy': '小熊绒（棕）', 'stars': '星夜（蓝）'},
    'large_yukkuri_nest': {'': '草莓编织（红）', 'wicker': '原色藤编（棕）', 'mint': '薄荷格纹（青）', 'lavender': '薰衣草（紫）'},
    'yukkuri_toilet': {'': '薄荷（青）', 'pink': '樱粉（粉红）', 'lemon': '柠檬（黄）', 'sky': '天蓝（淡蓝）'},
    'ceramic_toilet': {'': '奶油薄荷（白）', 'polka': '樱粉波点（粉红）', 'sailor': '水手条纹（淡蓝）'},
    'covered_toilet': {'': '薄荷盖（青）', 'pink': '粉点盖（粉红）', 'navy': '星夜盖（蓝）'},
}


class Scene(R.Source):
    """render_preview.Source plus `vanilla:<block>` (a vanilla block model, centred like a placed care prop)."""
    _jar = None

    def tex_of(self, name, part):
        path = super().tex_of(name, part)
        if not part.get('texture') and ':' not in name and not Path(path).exists():
            return R.Source(R.ROOT).tex_of(name, part)
        return path

    def model_json(self, name):
        if Scene._jar is None:
            Scene._jar = zipfile.ZipFile(JAR)
        data = json.loads(Scene._jar.read(f'assets/minecraft/models/block/{name}.json'))
        textures, elements = {}, None
        while True:
            textures = {**data.get('textures', {}), **textures}
            elements = elements or data.get('elements')
            parent = data.get('parent')
            if not parent:
                break
            data = json.loads(Scene._jar.read(f'assets/minecraft/models/{parent.split(":")[-1]}.json'))

        def resolve(ref):
            while ref.startswith('#'):
                ref = textures[ref[1:]]
            return 'minecraft:textures/' + ref.split(':')[-1] + '.png'
        return elements or [], resolve

    def load(self, name):
        if not name.startswith('vanilla:'):
            if ':' not in name and not (self.assets / 'yukkuri_props' / f'{name}.json').exists():
                return R.Source(R.ROOT).load(name)            # props this tree does not hold come from the worktree
            return super().load(name)
        elements, resolve = self.model_json(name.split(':', 1)[1])
        rows, parts = [], []
        for el in elements:
            lo, hi = np.array(el['from'], float) - [8, 0, 8], np.array(el['to'], float) - [8, 0, 8]
            for face, spec in el['faces'].items():
                n, axis = FACES[face]
                sign = n[axis]
                plane = hi[axis] if sign > 0 else lo[axis]
                a, b = [k for k in (0, 1, 2) if k != axis]
                pts = []
                for ca, cb in [(lo[a], lo[b]), (hi[a], lo[b]), (hi[a], hi[b]), (lo[a], hi[b])]:
                    p = [0.0, 0.0, 0.0]
                    p[axis], p[a], p[b] = plane, ca, cb
                    pts.append(p)
                f, t = np.array(el['from'], float), np.array(el['to'], float)
                uv = spec.get('uv') or {'up': [f[0], f[2], t[0], t[2]], 'down': [f[0], 16 - t[2], t[0], 16 - f[2]],
                                        'north': [16 - t[0], 16 - t[1], 16 - f[0], 16 - f[1]], 'south': [f[0], 16 - t[1], t[0], 16 - f[1]],
                                        'west': [f[2], 16 - t[1], t[2], 16 - f[1]], 'east': [16 - t[2], 16 - t[1], 16 - f[2], 16 - f[1]]}[face]

                def face_ab(p, face=face):
                    x, y, z = p[0] + 8, p[1], p[2] + 8
                    return {'up': (x, z), 'down': (x, 16 - z), 'north': (16 - x, 16 - y), 'south': (x, 16 - y),
                            'west': (z, 16 - y), 'east': (16 - z, 16 - y)}[face]
                ab = [face_ab(q) for q in pts]
                amin, amax = min(q[0] for q in ab), max(q[0] for q in ab)
                bmin, bmax = min(q[1] for q in ab), max(q[1] for q in ab)
                start = sum(len(r) for r in rows)
                tri = []
                for i in (0, 1, 2, 0, 2, 3):
                    ua, va = face_ab(pts[i])
                    u = uv[0] + (uv[2] - uv[0]) * ((ua - amin) / max(amax - amin, 1e-6))
                    v = uv[1] + (uv[3] - uv[1]) * ((va - bmin) / max(bmax - bmin, 1e-6))
                    tri.append([*pts[i], u / 16, v / 16, *n])
                rows.append(tri)
                parts.append({'name': face, 'start': start, 'count': 6, 'texture': resolve(spec['texture'])})
        arr = np.array([r for tri in rows for r in tri], dtype=np.float32)
        return {'parts': parts, 'bounds': [[float(arr[:, i].min()), float(arr[:, i].max())] for i in range(3)]}, arr


AFTER = Scene(os.environ.get('MCYUKKURI_AFTER', R.ROOT))
# A worktree can share the main checkout's before tree; the main checkout itself
# has no parent project directory to reuse, so it uses its own current assets.
BEFORE_ROOT = R.ROOT.parents[1] if R.ROOT.parent.name == '.worktrees' else R.ROOT
BEFORE = Scene(os.environ.get('MCYUKKURI_BEFORE', BEFORE_ROOT / '.local/care-props-before'))
REIMU, MARISA = 'yuk:reimu:neutral', 'yuk:marisa:neutral'
CAULDRON, COMPOSTER, POT = 'vanilla:cauldron', 'vanilla:composter', 'vanilla:flower_pot'
COLLISION = json.loads((R.ROOT / 'src/main/resources/assets/mcyukkuri/yukkuri_presentation/care_collision.json').read_text(encoding='utf-8'))


def rest(nest, x, z, scale):
    """Height a body of this scale rests at in a nest: the highest authored collision box under its footprint (12.8 wide
    at scale 1), as the game's own collision would place it."""
    h = 12.8 * scale / 2 - .08
    return max((b[4] for b in COLLISION[nest]['boxes'] if b[0] < x + h and b[2] > x - h and b[1] < z + h and b[3] > z - h), default=0.0)


def sit(who, nest, x, z, scale, yaw):
    return (who, 8, x, rest(nest, x, z, scale), z, scale, yaw)


def tile(items, label, size, ppu, az=210, el=30, src=AFTER):
    im = Image.new('RGBA', size, BG)
    r, _ = R.render(src, items, az=az, el=el, ppu=ppu)
    k = min((size[0] - 8) / r.width, (size[1] - 30) / r.height, 1.0)
    if k < 1:
        r = r.resize((max(1, int(r.width * k)), max(1, int(r.height * k))), Image.LANCZOS)
    im.paste(r, ((size[0] - r.width) // 2, 26 + (size[1] - 26 - r.height) // 2), r)
    ImageDraw.Draw(im).text((8, 4), label, font=FS, fill=(20, 20, 20, 255))
    return im


def sheet(rows, out, title, size):
    W, H = size
    cols = max(len(r) for r in rows)
    s = Image.new('RGBA', (W * cols, 36 + H * len(rows)), BG)
    d = ImageDraw.Draw(s)
    d.text((10, 8), title, font=F, fill=(0, 0, 0, 255))
    for ri, tiles in enumerate(rows):
        for ci, t in enumerate(tiles):
            s.paste(t, (ci * W, 36 + ri * H))
            d.rectangle([ci * W, 36 + ri * H, ci * W + W - 1, 36 + ri * H + H - 1], outline=(170, 178, 186, 255))
    OUT.mkdir(parents=True, exist_ok=True)
    s.convert('RGB').save(OUT / out)
    print(OUT / out)


def g(half):
    return ('ground:%g' % half, 0, 0, 0, 0, 1)


def styles(base):
    return [(placed.model_name(base, s), LABELS[base][s]) for s in placed.STYLES[base]]


def vanilla():
    """The goods in a row of blocks next to a flower pot, cauldron and composter, with Reimu / Marisa for scale."""
    SZ = (720, 400)

    def line(*things):
        return [g(34)] + [(n, u, 24 - 16 * i + dx, y, dz, sc, *rest) for i, group in enumerate(things)
                          for (n, u, dx, y, dz, sc, *rest) in group]
    one = lambda n, u=0: [(n, u, 0, 0, 0, 1)]
    feed = line(one(POT), one('food_bowl'), [('food_bowl_hearts', 0, 0, 0, 0, 1), ('food_bowl_regular', 8, 0, 0, 0, 1)], one(CAULDRON))
    beds = line(one(COMPOSTER), [('yukkuri_nest', 0, 0, 0, 0, 1), sit(REIMU, 'yukkuri_nest', 0, 0, 1.0, 340)],
                [('yukkuri_nest_teddy', 0, 0, 0, 0, 1), sit(MARISA, 'yukkuri_nest', 0, 0, .5, 20)], one(CAULDRON))
    loos = line(one('yukkuri_toilet'), one('ceramic_toilet_sailor'), one('covered_toilet'), one(COMPOSTER))
    big = [g(30), ('large_yukkuri_nest', 0, 0, 0, 0, 1), (CAULDRON, 0, -26, 0, -8, 1), (COMPOSTER, 0, 26, 0, -8, 1),
           sit(REIMU, 'large_yukkuri_nest', -5.8, -5, 1.0, 175), sit(MARISA, 'large_yukkuri_nest', 5.6, .4, 1.0, 210),
           sit(REIMU, 'large_yukkuri_nest', -1.6, 6, .32, 205), sit(MARISA, 'large_yukkuri_nest', 1.8, 8.6, .32, 172)]
    rows = [[tile(feed, '花盆｜食盆（奶油薄荷）｜红心食盆盛油粮 8 份｜炼药锅', SZ, 11, az=200, el=28),
             tile(beds, '堆肥桶｜软垫窝 + 灵梦｜小熊绒软垫窝 + 子体魔理沙｜炼药锅', SZ, 11, az=200, el=28)],
            [tile(loos, '沙厕｜水手条纹陶厕｜带盖厕（盖在后半，前侧开口）｜堆肥桶', SZ, 11, az=200, el=28),
             tile(big, '2×2 编织大窝（入口 +z）与炼药锅、堆肥桶；亲油两只、新生两只', SZ, 10, az=30)]]
    sheet(rows, 'vanilla.png', '宠物照料用品与原版方块并排：圆润低多边形 + 1 像素／单位手绘材质（离线渲染）', SZ)


def bowls():
    SZ = (380, 300)
    items = styles('food_bowl')
    rows = [[tile([g(9), (n, 0, 0, 0, 0, 1)], l, SZ, 26, el=28) for n, l in items[:4]],
            [tile([g(9), (n, 0, 0, 0, 0, 1)], l, SZ, 26, el=28) for n, l in items[4:]] +
            [tile([g(9), ('food_bowl', 0, 0, 0, 0, 1)], '奶油薄荷 侧视', SZ, 26, az=180, el=5)],
            [tile([g(9), (n, 0, 0, 0, 0, 1)], l + ' 俯视', SZ, 22, az=0, el=80) for n, l in (items[0], items[1], items[3], items[4])]]
    sheet(rows, 'bowls.png', '食盆款式：放置后用对应颜色染料右键切换（括号内为染料）；14 宽、3 高、盆底 y 1', SZ)


def fills():
    SZ = (360, 290)
    rows = []
    for name, label, bowl in (('food_bowl_regular', '油粮', 'food_bowl'), ('food_bowl_wheat', '小麦', 'food_bowl_terracotta'),
                              ('food_bowl_forage', '短草', 'food_bowl_sailor')):
        r = [tile([g(9), (bowl, 0, 0, 0, 0, 1), (name, k, 0, 0, 0, 1)], f'{label} {k}/8', SZ, 26, el=38) for k in (1, 2, 4, 8)]
        r.append(tile([g(9), (bowl, 0, 0, 0, 0, 1), (name, 8, 0, 0, 0, 1)], f'{label} 8/8 俯视', SZ, 22, az=0, el=80))
        rows.append(r)
    sheet(rows, 'fills.png', '盆内填充：自绘材质堆按份数长大（1/2/4/8 份），不再插原版贴图；填充与盆的款式无关', SZ)


def nests():
    SZ = (380, 300)
    items = styles('yukkuri_nest')
    rows = [[tile([g(12), (n, 0, 0, 0, 0, 1)], l, SZ, 17, el=30) for n, l in items[:4]],
            [tile([g(11), (n, 0, 0, 0, 0, 1)], l, SZ, 18, el=30) for n, l in items[4:]] +
            [tile([g(11), ('yukkuri_nest', 0, 0, 0, 0, 1)], '侧视：右侧为北入口，软卷降成低卷边', SZ, 18, az=90, el=6)],
            [tile([g(11), ('yukkuri_nest', 0, 0, 0, 0, 1), sit(REIMU, 'yukkuri_nest', 0, 0, 1.0, 345)], '成体灵梦（坐在垫面上，软卷低于腰线）', SZ, 15, el=26),
             tile([g(11), ('yukkuri_nest_polka', 0, 0, 0, 0, 1), sit(MARISA, 'yukkuri_nest', -3.8, 0, .5, 20), sit(REIMU, 'yukkuri_nest', 3.8, 0, .5, 320)],
                  '两只子体 0.5', SZ, 15, el=26),
             tile([g(11), ('yukkuri_nest_stars', 0, 0, 0, 0, 1)], '星夜 俯视', SZ, 15, az=0, el=80),
             tile([g(11), ('yukkuri_nest_teddy', 0, 0, 0, 0, 1)], '小熊绒 侧视', SZ, 18, az=90, el=4)]]
    sheet(rows, 'nests.png', '宠物软垫窝款式（直径 26.6、软卷 5.25 高、平垫面 y 1.6–1.8 半径 10.1，北侧 U 形卷边缺口；中间 23 见方平垫碰撞，1.2 倍成体足月也坐进窝里）：染料右键切换', SZ)


def large():
    SZ = (460, 340)
    items = styles('large_yukkuri_nest')
    fam = [sit(who, 'large_yukkuri_nest', x, z, s, yaw) for who, x, z, s, yaw in
           ((REIMU, -5.8, -6, 1.0, 175), (MARISA, 5.6, .4, 1.0, 210), (REIMU, -1.6, 4.6, .32, 205),
            (MARISA, -7.4, 4.6, .32, 140), (REIMU, 1.8, 8.6, .32, 172), (MARISA, 8.8, -7.2, .32, 248))]
    rows = [[tile([g(18), (n, 0, 0, 0, 0, 1)], l, SZ, 9, az=30) for n, l in items],
            [tile([g(18), ('large_yukkuri_nest', 0, 0, 0, 0, 1)] + fam, '成体 2 + 新生 4', SZ, 9, az=30),
             tile([g(18), ('large_yukkuri_nest_wicker', 0, 0, 0, 0, 1)], '原色藤编 入口侧视（+z）', SZ, 9, az=0, el=8),
             tile([g(18), ('large_yukkuri_nest_mint', 0, 0, 0, 0, 1)] + fam, '薄荷格纹 俯视', SZ, 8, az=0, el=80),
             tile([g(18), ('large_yukkuri_nest_lavender', 0, 0, 0, 0, 1)], '薰衣草 背面', SZ, 9, az=200)]]
    sheet(rows, 'large_nests.png', '2×2 编织大窝款式（32 宽圆角方篮、后侧与两侧围沿 6 高、窝内带折边软垫 29.8 宽，正面 +z 开口落地；碰撞跟随可见外形）：染料右键切换', SZ)


def toilets():
    SZ = (340, 270)
    sand = styles('yukkuri_toilet')
    dirty = lambda floor, style='': [('toilet_waste', 0, -4 + 2.56 * (i % 4), floor, -2.4 + 3.68 * (i // 4), .5) for i in range(6)]
    rows = [[tile([g(8), (n, 0, 0, 0, 0, 1)], '沙厕 ' + l, SZ, 26) for n, l in sand],
            [tile([g(8), (n + '_dirty', 0, 0, 0, 0, 1)], '沙厕 脏 ' + l.split('（')[0], SZ, 26, el=42) for n, l in sand],
            [tile([g(8), (n, 0, 0, 0, 0, 1)], '陶厕 ' + l, SZ, 24) for n, l in styles('ceramic_toilet')] +
            [tile([g(8), ('ceramic_toilet', 0, 0, 0, 0, 1)] + dirty(1.0), '陶厕 脏 6 份', SZ, 24, az=200, el=45)],
            [tile([g(8), (n, 0, 0, 0, 0, 1)], '带盖厕 ' + l, SZ, 24) for n, l in styles('covered_toilet')] +
            [tile([g(8), ('covered_toilet_pink', 0, 0, 0, 0, 1)] + dirty(1.5), '带盖厕 脏 6 份（前开口）', SZ, 24, az=200, el=40)]]
    sheet(rows, 'toilets.png', '厕所款式：沙厕 14.4×12（沙面 y 2，成体可坐进）、陶厕与带盖厕 14×14（底面 y 1 / 1.5，前侧缺口）；染料右键切换', SZ)


def before():
    """Previous meshes (MCYUKKURI_BEFORE) against the current ones, same camera."""
    SZ = (360, 280)
    if not (BEFORE.assets / 'yukkuri_props/food_bowl.json').exists():
        print('skip before/after: no MCYUKKURI_BEFORE tree')
        return
    rows = []
    for tag, src in (('前', BEFORE), ('后', AFTER)):
        bowl_fill = [('food_bowl_forage', 8, 0, 0, 0, 1)] if src is BEFORE else [('food_bowl', 0, 0, 0, 0, 1), ('food_bowl_forage', 8, 0, 0, 0, 1)]
        rows.append([tile([g(9), ('food_bowl', 0, 0, 0, 0, 1)], f'{tag} 食盆', SZ, 24, src=src),
                     tile([g(9)] + bowl_fill, f'{tag} 短草 8 份', SZ, 24, el=38, src=src),
                     tile([g(11), ('yukkuri_nest', 0, 0, 0, 0, 1)], f'{tag} 软垫窝', SZ, 17, src=src),
                     tile([g(18), ('large_yukkuri_nest', 0, 0, 0, 0, 1)], f'{tag} 编织大窝', SZ, 9, az=30, src=src),
                     tile([g(8), ('covered_toilet', 0, 0, 0, 0, 1)], f'{tag} 带盖厕', SZ, 24, src=src)])
    sheet(rows, 'before_after.png', '上一版与当前（软垫窝放大、编织大窝 32 宽、沙厕放大）', SZ)


def index():
    pages = [('fit.png', '窝的容纳与剖面'), ('lineup.png', '全部放置方块与成长阶段'), ('vanilla.png', '与原版方块并排'), ('bowls.png', '食盆款式'), ('fills.png', '盆内填充'), ('nests.png', '软垫窝款式'),
             ('large_nests.png', '2×2 编织大窝款式'), ('toilets.png', '厕所款式'), ('before_after.png', '上一轮对比')]
    body = ''.join(f'<h2>{t}</h2><img src="{p}" alt="{t}" style="max-width:100%">' for p, t in pages if (OUT / p).exists())
    (OUT / 'index.html').write_text('<!doctype html><meta charset="utf-8"><title>宠物照料用品款式</title><h1>宠物照料用品款式</h1>'
                                    '<p>放置后的食盆与填充、宠物软垫窝、2×2 编织大窝和三种厕所：圆润低多边形 + 手绘像素材质，'
                                    '每件可用染料右键切换款式。离线渲染，非游戏截图；原版方块只作风格与尺度对照。</p>' + body, encoding='utf-8')


if __name__ == '__main__':
    which = sys.argv[1:] or ['vanilla', 'bowls', 'fills', 'nests', 'large', 'toilets', 'before']
    for w in which:
        {'vanilla': vanilla, 'bowls': bowls, 'fills': fills, 'nests': nests, 'large': large, 'toilets': toilets, 'before': before}[w]()
    index()
