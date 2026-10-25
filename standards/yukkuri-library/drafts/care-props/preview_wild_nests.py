"""Offline current-model preview of the wild nest family (local only, nothing shipped): shared leaf bed, personal leaf nest
stages and the carried bundle, the 2x2 wild nest and the forage pile stages, with adult and child yukkuri for scale.
Reads the runtime meshes and textures; no game or browser. The hat-boat crown larder is previewed by
motions/variants/lore/aquatic_home_preview.py.

Output: <MCYUKKURI_PREVIEWS or .local/previews>/wild-nests/{nests.png, stages.png, forage.png, index.html}."""
import os, sys
from pathlib import Path
HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
OUT = Path(os.environ.get('MCYUKKURI_PREVIEWS', HERE.parents[3] / '.local/previews')) / 'wild-nests'
os.environ['MCYUKKURI_OUT'] = str(OUT)
import numpy as np
from PIL import Image, ImageDraw
from preview_nests_lib import AFTER, tile, sheet, F

SZ = (520, 400)
ADULT, CHILD = 'yuk:reimu:neutral', 'yuk:marisa:neutral'
SINGLE = 1 | 2 | 4 | 8 | 16 | (1 << 5) | (1 << 6) | (1 << 7) | (1 << 8)


def shared_mask(shared, bed):
    """Same layer mask as client/ShelterRenderer.sharedLeafLayers; bed = (x, z), north is z - 1."""
    x, z = bed
    n, e, s, w = (x, z - 1) not in shared, (x + 1, z) not in shared, (x, z + 1) not in shared, (x - 1, z) not in shared
    m = 1 | (2 if n else 0) | (4 if e else 0) | (8 if s else 0) | (16 if w else 0)
    for flag, bit in ((n and e, 5), (s and e, 6), (s and w, 7), (n and w, 8), (n and not w, 9), (n and not e, 10), (e and not n, 11),
                      (e and not s, 12), (s and not e, 13), (s and not w, 14), (w and not s, 15), (w and not n, 16)):
        if flag:
            m |= 1 << bit
    return m


def nests():
    g = ('ground:17', 0, 0, 0, 0, 1)
    bed = ('forest_burrow', ('mask', SINGLE), 0, 0, 0, 1)
    cells = [(0, 0), (1, 0), (0, 1)]
    fam = [('forest_burrow', ('mask', shared_mask(set(cells), c)), (c[0] - .5) * 16, 0, (c[1] - .5) * 16, 1) for c in cells]
    row1 = [tile(AFTER, [g, bed], '叶床（单睡位，入口 +z 低沿）', size=SZ, ppu=19),
            tile(AFTER, [g, bed, (ADULT, 8, 0, 0, 0, 1.0, 345)], '叶床 + 成体 1.0', size=SZ, ppu=19),
            tile(AFTER, [('ground:26', 0, 0, 0, 0, 1)] + fam + [(ADULT, 8, -8, 0, -8, 1.0, 160), (ADULT, 8, 8, 0, -8, 1.0, 200), (CHILD, 8, -8, 0, 8, .5, 180)],
                 '三睡位相连的共巢（L 形，一圈连续围沿）', size=SZ, ppu=10)]
    large = ('large_wild_nest', 8, 0, 0, 0, 1)
    family = [(ADULT, 8, -7.1, .5, -3.0, 1.0, 155), (ADULT, 8, 7.1, .5, -3.0, 1.0, 222)] + \
             [(CHILD, 8, x, .5, z, .32, yaw) for x, z, yaw in ((-1.6, 4.6, 205), (-7.4, 4.6, 140), (1.8, 8.6, 172), (8.8, -7.2, 248))]
    row2 = [tile(AFTER, [('ground:24', 0, 0, 0, 0, 1), large], '2×2 野生大窝（入口 +z）', size=SZ, ppu=12),
            tile(AFTER, [('ground:24', 0, 0, 0, 0, 1), large] + family, '野生大窝 + 成体 2 + 新生 4', size=SZ, ppu=12),
            tile(AFTER, [('ground:24', 0, 0, 0, 0, 1), large], '野生大窝 正面低视（开口落到窝底）', size=SZ, ppu=12, az=0, el=8)]
    sheet([row1, row2], 'nests.png', '野生油自建巢：叼来的散干草堆压成的鸟巢式围沿 + 落叶垫，不扎不捆，原版 1 texel/单位手绘贴图', 3, size=SZ)


def stages():
    g = ('ground:11', 0, 0, 0, 0, 1)
    row = [tile(AFTER, [('ground:8', 0, 0, 0, 0, 1), ('bedding_bundle', 0, 0, 0, 0, 1)], '叶束（衔运的一份垫料）', size=SZ, ppu=34)]
    for m, lab in ((1, '阶段 1 散落几片叶'), (3, '阶段 2 粗略成圈'), (7, '阶段 3 堆压成小窝')):
        row.append(tile(AFTER, [g, ('personal_leaf_nest', ('mask', m), 0, 0, 0, 1)], f'个人小叶巢 {lab}', size=SZ, ppu=26))
    row2 = [tile(AFTER, [g, ('personal_leaf_nest', ('mask', 7), 0, 0, 0, 1), (CHILD, 8, 0, 0, 0, s, 20)], f'个人小叶巢 + {"子体" if s < .8 else "成体"} {s}', size=SZ, ppu=20)
            for s in (.5, 1.0)]
    row2.append(tile(AFTER, [g, ('personal_leaf_nest', ('mask', 7), 0, 0, 0, 1)], '个人小叶巢 俯视', size=SZ, ppu=26, az=0, el=80))
    row2.append(tile(AFTER, [g, ('personal_leaf_nest', ('mask', 7), 0, 0, 0, 1)], '个人小叶巢 正面低视', size=SZ, ppu=26, az=0, el=10))
    sheet([row, row2], 'stages.png', '个人小叶巢的建造阶段（衔回叶束 → 散叶 → 粗圈 → 堆压成小窝，坑约 15.6×14.4），子体 0.5 / 长成后的成体 1.0', 4, size=SZ)


def forage():
    g = ('ground:9', 0, 0, 0, 0, 1)
    labels = ('0 份：只剩叶托', '1–8 份', '9–32 份', '33–96 份', '97 份以上')
    row = [tile(AFTER, [g, ('forage_pile', st, 0, 0, 0, 1)], f'储粮堆 {labels[st]}', size=(420, 360), ppu=24) for st in range(5)]
    row2 = [tile(AFTER, [('ground:20', 0, 0, 0, 0, 1), ('forage_pile', 4, -9, 0, 0, 1), (ADULT, 8, 8, 0, 0, 1.0, 200)], '满堆与成体 1.0 对照', size=(420, 360), ppu=12),
            tile(AFTER, [('ground:20', 0, 0, 0, 0, 1), ('forage_pile', 2, -7, 0, 0, 1), (CHILD, 8, 6, 0, 0, .5, 200)], '9–32 份与子体 0.5', size=(420, 360), ppu=14),
            tile(AFTER, [g, ('forage_pile', 4, 0, 0, 0, 1)], '满堆近景', size=(420, 360), ppu=34, az=50, el=30),
            tile(AFTER, [g, ('forage_pile', 4, 0, 0, 0, 1)], '满堆侧视', size=(420, 360), ppu=24, az=0, el=6),
            tile(AFTER, [g, ('forage_pile', 4, 0, 0, 0, 1)], '满堆俯视', size=(420, 360), ppu=24, az=0, el=80)]
    sheet([row, row2], 'forage.png', '陆上储粮堆：叶托上的低圆草丘，丘顶压着散草与一簇浆果；草团、毛虫放在前方叶托上，满档一朵小白花', 5, size=(420, 360))


INDEX = '''<!doctype html><meta charset="utf-8"><title>野生巢与储粮堆</title>
<style>body{font:18px sans-serif;background:#eef2e9;color:#2b4035;margin:24px}img{max-width:100%;display:block;margin:8px 0 28px}</style>
<a href="../index.html">预览总入口</a><h1>野生巢与储粮堆（当前运行资源离线预览）</h1>
<p>叼来的散干草堆压成的鸟巢式围沿（不扎不捆）、落叶垫、整片大叶、方截面细枝与外露草茎；储粮堆为叶托上的低圆草丘，丘顶压着散草与一簇浆果，草团与毛虫放在前方叶托上。
同一套手绘 16×16 贴图，1 texel/单位。离线渲染，不是游戏画面或行为验收。</p>
<h2>窝的容纳与剖面</h2><img src="fit.png"><h2>叶床与 2×2 野生大窝</h2><img src="nests.png"><h2>个人小叶巢阶段</h2><img src="stages.png">
<h2>陆上储粮堆</h2><img src="forage.png">'''


if __name__ == '__main__':
    OUT.mkdir(parents=True, exist_ok=True)
    which = sys.argv[1:] or ['nests', 'stages', 'forage']
    for w in which:
        {'nests': nests, 'stages': stages, 'forage': forage}[w]()
        print('done', w)
    (OUT / 'index.html').write_text(INDEX, encoding='utf-8')
