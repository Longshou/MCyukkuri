"""Offline before/after sheets for the vanilla-style placed care props (bowl and fills, pet nest, 2x2 nests, toilets),
with Reimu / Marisa for scale and a vanilla cauldron / composter / flower pot beside them for style comparison.

Local preview only: vanilla block models are read from the locally downloaded client jar (MCYUKKURI_VANILLA_JAR, default
.local/vanilla-ref/dl/client.jar) and textures from MCYUKKURI_VANILLA; nothing vanilla is copied into the repository.
BEFORE = MCYUKKURI_BEFORE (a tree holding src/main/resources/assets/mcyukkuri of the previous meshes).
Output: <MCYUKKURI_PREVIEWS or .local/previews>/care-props/{bowl.png, nests.png, large_nests.png, toilets.png, index.html}.
Usage: python preview_vanilla_care.py [bowl nests large toilets]
"""
import json, os, sys, zipfile
from pathlib import Path
import numpy as np
sys.path.insert(0, str(Path(__file__).resolve().parent))
import render_preview as R
from PIL import Image, ImageDraw, ImageFont

PREVIEWS = Path(os.environ.get('MCYUKKURI_PREVIEWS', Path(__file__).resolve().parents[4] / '.local/previews'))
if not (PREVIEWS / 'index.html').exists() and (R.ROOT.parents[1] / '.local/previews').exists():
    PREVIEWS = R.ROOT.parents[1] / '.local/previews'            # worktree: share the main checkout's preview hub
OUT = PREVIEWS / 'care-props'
JAR = Path(os.environ.get('MCYUKKURI_VANILLA_JAR', R.VANILLA.parents[2] / 'dl/client.jar'))
F = ImageFont.truetype(os.environ.get('MCYUKKURI_FONT', 'C:/Windows/Fonts/msyh.ttc'), 15)
FS = ImageFont.truetype(os.environ.get('MCYUKKURI_FONT', 'C:/Windows/Fonts/msyh.ttc'), 12)
BG = (214, 222, 230, 255)
FACES = {'up': ([0, 1, 0], 1), 'down': ([0, -1, 0], 1), 'north': ([0, 0, -1], 2), 'south': ([0, 0, 1], 2),
         'west': ([-1, 0, 0], 0), 'east': ([1, 0, 0], 0)}


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
                corners = [(lo[a], lo[b]), (hi[a], lo[b]), (hi[a], hi[b]), (lo[a], hi[b])]
                pts = []
                for ca, cb in corners:
                    p = [0.0, 0.0, 0.0]
                    p[axis], p[a], p[b] = plane, ca, cb
                    pts.append(p)
                f, t = np.array(el['from'], float), np.array(el['to'], float)
                uv = spec.get('uv') or {'up': [f[0], f[2], t[0], t[2]], 'down': [f[0], 16 - t[2], t[0], 16 - f[2]],
                                        'north': [16 - t[0], 16 - t[1], 16 - f[0], 16 - f[1]], 'south': [f[0], 16 - t[1], t[0], 16 - f[1]],
                                        'west': [f[2], 16 - t[1], t[2], 16 - f[1]], 'east': [16 - t[2], 16 - t[1], 16 - f[2], 16 - f[1]]}[face]
                def face_ab(p):
                    # texture-space position (0..16, v down) of a point on this face, vanilla orientation
                    x, y, z = p[0] + 8, p[1], p[2] + 8
                    return {'up': (x, z), 'down': (x, 16 - z), 'north': (16 - x, 16 - y), 'south': (x, 16 - y),
                            'west': (z, 16 - y), 'east': (16 - z, 16 - y)}[face]
                ab = [face_ab(q) for q in pts]
                amin, amax = min(q[0] for q in ab), max(q[0] for q in ab)
                bmin, bmax = min(q[1] for q in ab), max(q[1] for q in ab)
                def tex_uv(p):
                    ua, va = face_ab(p)
                    u = uv[0] + (uv[2] - uv[0]) * ((ua - amin) / max(amax - amin, 1e-6))
                    v = uv[1] + (uv[3] - uv[1]) * ((va - bmin) / max(bmax - bmin, 1e-6))
                    return u / 16, v / 16
                start = sum(len(r) for r in rows)
                tri = []
                for i in (0, 1, 2, 0, 2, 3):
                    u, v = tex_uv(pts[i])
                    tri.append([*pts[i], u, v, *n])
                rows.append(tri)
                parts.append({'name': face, 'start': start, 'count': 6, 'texture': resolve(spec['texture'])})
        arr = np.array([r for tri in rows for r in tri], dtype=np.float32)
        return {'parts': parts, 'bounds': [[float(arr[:, i].min()), float(arr[:, i].max())] for i in range(3)]}, arr


AFTER = Scene(os.environ.get('MCYUKKURI_AFTER', R.ROOT))
BEFORE = Scene(os.environ.get('MCYUKKURI_BEFORE', R.ROOT.parents[1] / '.local/care-props-before'))
REIMU, MARISA = 'yuk:reimu:neutral', 'yuk:marisa:neutral'


def tile(src, items, label, size, ppu, az=210, el=30, lock=None):
    im = Image.new('RGBA', size, BG)
    r, _ = R.render(src, items, az=az, el=el, ppu=ppu, lock=lock)
    k = min((size[0] - 8) / r.width, (size[1] - 26) / r.height, 1.0)
    if k < 1:
        r = r.resize((max(1, int(r.width * k)), max(1, int(r.height * k))), Image.LANCZOS)
    im.paste(r, ((size[0] - r.width) // 2, 22 + (size[1] - 22 - r.height) // 2), r)
    ImageDraw.Draw(im).text((6, 3), label, font=FS, fill=(20, 20, 20, 255))
    return im


def sheet(rows, out, title, size):
    W, H = size
    cols = max(len(r) for r in rows)
    s = Image.new('RGBA', (W * cols, 32 + H * len(rows)), BG)
    d = ImageDraw.Draw(s)
    d.text((8, 7), title, font=F, fill=(0, 0, 0, 255))
    for ri, tiles in enumerate(rows):
        for ci, t in enumerate(tiles):
            s.paste(t, (ci * W, 32 + ri * H))
            d.rectangle([ci * W, 32 + ri * H, ci * W + W - 1, 32 + ri * H + H - 1], outline=(170, 178, 186, 255))
    OUT.mkdir(parents=True, exist_ok=True)
    s.convert('RGB').save(OUT / out)
    print(OUT / out)


def views(src, tag, items, label, size, ppu, scale_items=()):
    """3/4 view with scale yukkuri and vanilla neighbours, plus top and side of the prop alone."""
    return [tile(src, items + list(scale_items), f'{tag} {label} 3/4', size, ppu),
            tile(src, items, f'{tag} {label} 俯视', size, ppu, az=180, el=88),
            tile(src, items, f'{tag} {label} 侧视', size, ppu, az=180, el=4)]


GROUND = ('ground:24', 0, 0, 0, 0, 1)
CAULDRON = ('vanilla:cauldron', 0, 6, 0, 22, 1)
COMPOSTER = ('vanilla:composter', 0, 22, 0, 4, 1)
POT = ('vanilla:flower_pot', 0, -12, 0, 16, 1)


def bowl():
    SZ = (460, 330)
    rows = []
    for src, tag in ((BEFORE, '前'), (AFTER, '后')):
        rows.append(views(src, tag, [GROUND, ('food_bowl', 0, 0, 0, 0, 1)], '空食盆', SZ, 14,
                          [CAULDRON, POT, (REIMU, 8, -13, 0, 4, 1.0, 345), (MARISA, 8, 11, 0, -9, .5, 330)]))
    for name, label in (('food_bowl_regular', '油粮'), ('food_bowl_wheat', '小麦'), ('food_bowl_forage', '短草')):
        for src, tag in ((BEFORE, '前'), (AFTER, '后')):
            r = []
            for units in (0, 1, 4, 8):
                r.append(tile(src, [('ground:12', 0, 0, 0, 0, 1), (name, units, 0, 0, 0, 1)], f'{tag} {label} {units}/8 3/4', SZ, 26))
            r.append(tile(src, [('ground:12', 0, 0, 0, 0, 1), (name, 8, 0, 0, 0, 1)], f'{tag} {label} 8/8 俯视', SZ, 26, az=180, el=88))
            rows.append(r)
    sheet(rows, 'bowl.png', '食盆与盆内填充：前＝当前主线，后＝原版风格（1/16 方块 + 16px 材质）；份数 0/1/4/8 逐份可见', SZ)


def nests():
    SZ = (460, 340)
    rows = []
    for src, tag in ((BEFORE, '前'), (AFTER, '后')):
        floor = 1.5
        rows.append(views(src, tag, [GROUND, ('yukkuri_nest', 0, 0, 0, 0, 1)], '宠物软垫窝', SZ, 13,
                          [CAULDRON, COMPOSTER, (REIMU, 8, 0, floor, 0, 1.0, 345)]))
        rows.append([tile(src, [GROUND, ('yukkuri_nest', 0, 0, 0, 0, 1), (MARISA, 8, 0, floor, 0, 1.2, 340)], f'{tag} 软垫窝 + 魔理沙 1.2', SZ, 13),
                     tile(src, [GROUND, ('yukkuri_nest', 0, 0, 0, 0, 1), (MARISA, 8, -3, floor, 1, .5, 20), (REIMU, 8, 3, floor, -1.5, .5, 320)],
                          f'{tag} 软垫窝 + 两只子体 0.5', SZ, 13),
                     tile(src, [GROUND, ('yukkuri_nest', 0, 0, 0, 0, 1)], f'{tag} 软垫窝 入口侧(-z)', SZ, 13, az=180, el=20)])
    sheet(rows, 'nests.png', '宠物软垫窝（1×1，可外伸至 20 宽，入口朝 -z）：前＝当前主线，后＝原版风格', SZ)


ADULTS = ((-5.8, -6.0, 175), (5.6, .4, 210))
NEWBORNS = ((-1.6, 4.6, 205), (-7.4, 4.6, 140), (1.8, 8.6, 172), (8.8, -7.2, 248))


def family(floor):
    return ([(REIMU if i == 0 else MARISA, 8, x, floor, z, 1.0, yaw) for i, (x, z, yaw) in enumerate(ADULTS)] +
            [(MARISA if i % 2 else REIMU, 8, x, floor, z, .32, yaw) for i, (x, z, yaw) in enumerate(NEWBORNS)])


def large():
    SZ = (520, 400)
    rows = []
    g = ('ground:34', 0, 0, 0, 0, 1)
    for name, floor_b, floor_a, label in (('large_yukkuri_nest', 1.0, 1.0, '大编织软窝'), ('large_wild_nest', .2, .5, '野生大草窝')):
        for src, tag, fl in ((BEFORE, '前', floor_b), (AFTER, '后', floor_a)):
            nest = (name, 0, 0, 0, 0, 1)
            rows.append([tile(src, [g, nest, ('vanilla:cauldron', 0, -26, 0, -14, 1), ('vanilla:composter', 0, 26, 0, -18, 1)] + family(fl),
                              f'{tag} {label} 成体2+新生4 3/4', SZ, 10, az=30),
                         tile(src, [g, nest] + family(fl), f'{tag} {label} 俯视', SZ, 9, az=0, el=88),
                         tile(src, [g, nest], f'{tag} {label} 入口侧视(+z)', SZ, 9, az=0, el=6),
                         tile(src, [g, nest], f'{tag} {label} 空窝 3/4', SZ, 9, az=30)])
    sheet(rows, 'large_nests.png', '2×2 家庭窝（内部 29 单位 ≈ 1.8 格，碰撞内宽 28.5，入口 +z）：前＝当前主线，后＝原版风格', SZ)


def toilets():
    SZ = (430, 320)
    rows = []
    for src, tag in ((BEFORE, '前'), (AFTER, '后')):
        rows.append(views(src, tag, [GROUND, ('yukkuri_toilet', 0, 0, 0, 0, 1)], '沙厕 干净', SZ, 14,
                          [CAULDRON, COMPOSTER, (REIMU, 8, 0, 2.35, 0, .6, 345)]))
        r = [tile(src, [GROUND, ('yukkuri_toilet_dirty', 8, 0, 0, 0, 1), CAULDRON, COMPOSTER], f'{tag} 沙厕 脏 3/4', SZ, 14),
             tile(src, [GROUND, ('yukkuri_toilet_dirty', 8, 0, 0, 0, 1)], f'{tag} 沙厕 脏 俯视', SZ, 14, az=180, el=88)]
        r.append(tile(src, [GROUND, ('ceramic_toilet', 0, 0, 0, 0, 1)], f'{tag} 陶厕', SZ, 14))
        waste = 'toilet_waste' if src is AFTER else 'yukkuri_waste'
        scale, unit = (.5, 0) if src is AFTER else (.4, 1)
        def dirty(floor):     # CareRenderer: one dropping per stored waste, up to 8, at fixed spots on the floor
            return [(waste, unit, -4 + 2.56 * (i % 4), floor, -2.4 + 3.68 * (i // 4), scale) for i in range(6)]
        r.append(tile(src, [GROUND, ('ceramic_toilet', 0, 0, 0, 0, 1)] + dirty(1.0 if src is AFTER else .88), f'{tag} 陶厕 脏 6', SZ, 14))
        rows.append(r)
        rows.append([tile(src, [GROUND, ('covered_toilet', 0, 0, 0, 0, 1)], f'{tag} 带盖厕', SZ, 14),
                     tile(src, [GROUND, ('covered_toilet', 0, 0, 0, 0, 1)] + dirty(1.5 if src is AFTER else 1.376), f'{tag} 带盖厕 脏 6（前开口）', SZ, 14, az=200, el=45),
                     tile(src, [GROUND, ('wild_latrine', 0, 0, 0, 0, 1)], f'{tag} 野外便所', SZ, 14),
                     tile(src, [GROUND, ('wild_latrine_dirty', 8, 0, 0, 0, 1)], f'{tag} 野外便所 脏', SZ, 14)])
    sheet(rows, 'toilets.png', '厕所：沙厕干净／脏（前＝当前主线，后＝原版风格），同族陶厕、带盖厕、野外便所对照', SZ)


def index():
    pages = [('bowl.png', '食盆与填充'), ('nests.png', '宠物软垫窝'), ('large_nests.png', '2×2 家庭窝'), ('toilets.png', '厕所')]
    body = ''.join(f'<h2>{t}</h2><img src="{p}" alt="{t}" style="max-width:100%">' for p, t in pages if (OUT / p).exists())
    (OUT / 'index.html').write_text('<!doctype html><meta charset="utf-8"><title>照料道具原版风格</title><h1>照料道具原版风格（前／后）</h1>'
                                    '<p>放置后的食盆与填充、宠物软垫窝、2×2 家庭窝和厕所；原版炼药锅、堆肥桶、花盆作风格对照，灵梦／魔理沙作尺度。</p>'
                                    + body, encoding='utf-8')


if __name__ == '__main__':
    which = sys.argv[1:] or ['bowl', 'nests', 'large', 'toilets']
    for w in which:
        {'bowl': bowl, 'nests': nests, 'large': large, 'toilets': toilets}[w]()
    index()
