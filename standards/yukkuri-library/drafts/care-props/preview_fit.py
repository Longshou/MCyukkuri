"""Offline fit check of every nest and placed block against the yukkuri growth stages (local only, nothing shipped).

For each nest: bodies of each stage resting where the game's collision puts them (the highest collision box under the
square body box, as Minecraft resolves it), and cross-sections through the nest centre along an axis and along the
diagonal, showing the mesh, the collision top and the rounded body outline (rounded_body_surface) of a 1.0 and a 1.2
adult. Plus a size lineup of all placed blocks beside an adult, subadult, child and newborn. Reads this tree's runtime
meshes and collision; MCYUKKURI_BEFORE (a checkout of the previous revision) adds the previous cushion nest for contrast.
Output: <previews>/pet-goods/{fit.png, lineup.png} and <previews>/wild-nests/fit.png (worktrees share the main hub).
"""
import json, math, os, sys
from pathlib import Path
import numpy as np
sys.path.insert(0, str(Path(__file__).resolve().parent))
import render_preview as R
from PIL import Image, ImageDraw, ImageFont

PREVIEWS = Path(os.environ.get('MCYUKKURI_PREVIEWS', R.ROOT / '.local/previews'))
if not (PREVIEWS / 'index.html').exists() and (R.ROOT.parents[1] / '.local/previews').exists():
    PREVIEWS = R.ROOT.parents[1] / '.local/previews'
AFTER = R.Source(R.ROOT)
BEFORE = R.Source(os.environ.get('MCYUKKURI_BEFORE', R.ROOT))
F = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 16)
FS = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 13)
BG = (222, 228, 222, 255)
REIMU, MARISA = 'yuk:reimu:neutral', 'yuk:marisa:neutral'
STAGES = {'成体 1.2': 1.2, '成体 1.0': 1.0, '亚成 0.75': .75, '子油 0.5': .5, '赤油 0.15': .15}


def collision_of(src):
    path = src.assets / 'yukkuri_presentation/care_collision.json'
    return json.loads(path.read_text(encoding='utf-8'))


def wild_large_boxes(full=16.0, inner_r=14.75):
    """life/CareBlock.largeWildShape, as [x0, z0, x1, z1, top] from the centre."""
    out = []
    for i in range(int(2 * full)):
        x = -full + i
        s = abs(x + .5)
        outer = max(0, full ** 4 - s ** 4) ** .25
        inner = max(0, inner_r ** 4 - s ** 4) ** .25
        out.append([x, -outer, x + 1, outer, .5])
        if inner == 0:
            out.append([x, -outer, x + 1, outer, 2.6])
        else:
            out.append([x, -outer, x + 1, -inner, 2.6])
            south = .5 if s < 7 else 1.5 if s < 10 else 2.3 if s < 12 else 2.6
            out.append([x, inner, x + 1, outer, south])
    return out


def boxes_for(nest, src=AFTER):
    if nest in ('yukkuri_nest', 'large_yukkuri_nest'):
        return collision_of(src)[nest]['boxes']
    if nest == 'large_wild_nest':
        return wild_large_boxes()
    return []                                   # wild leaf beds have no collision: bodies stand on the ground


def rest(boxes, x, z, scale):
    h = 12.8 * scale / 2 - .02
    return max((b[4] for b in boxes if b[0] < x + h and b[2] > x - h and b[1] < z + h and b[3] > z - h), default=0.0)


def top_at(boxes, x, z):
    return max((b[4] for b in boxes if b[0] <= x < b[2] and b[1] <= z < b[3]), default=0.0)


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
            d.rectangle([ci * W, 36 + ri * H, ci * W + W - 1, 36 + ri * H + H - 1], outline=(160, 168, 176, 255))
    out.parent.mkdir(parents=True, exist_ok=True)
    s.convert('RGB').save(out)
    print(out)


# ---- cross-sections ---------------------------------------------------------------------------------------------------
def tris(src, name, units=99, part_filter=None):
    data, v = src.load(name)
    out = []
    for part in data['parts']:
        if part_filter and not part_filter(part['name']):
            continue
        u = part.get('unit', 0)
        if isinstance(units, tuple):
            if u and not (units[1] & (1 << (u - 1))):
                continue
        elif u > units:
            continue
        s, c = part['start'], part['count']
        out.append(v[s:s + c, :3].reshape(-1, 3, 3))
    return np.concatenate(out) if out else np.zeros((0, 3, 3))


def slice_segments(T, diagonal):
    """Segments where triangles cross the vertical plane through the origin (x axis, or the x = z diagonal)."""
    P = T.copy()
    if diagonal:
        x, z = P[..., 0].copy(), P[..., 2].copy()
        P[..., 0], P[..., 2] = (x + z) / math.sqrt(2), (z - x) / math.sqrt(2)
    segs = []
    for tri in P:
        d = tri[:, 2]
        pts = []
        for i in range(3):
            a, b = tri[i], tri[(i + 1) % 3]
            if (a[2] > 0) != (b[2] > 0):
                t = a[2] / (a[2] - b[2])
                p = a + (b - a) * t
                pts.append((p[0], p[1]))
        if len(pts) == 2:
            segs.append(pts)
    return segs


BODY = tris(AFTER, REIMU, part_filter=lambda n: n == 'rounded_body_surface')


def section(nest, units, label, people, diagonal=False, half=14.0, src=AFTER, size=(440, 300)):
    """people: [(scale, x along the cut, colour)]."""
    boxes = boxes_for(nest, src)
    im = Image.new('RGBA', size, (246, 247, 243, 255))
    d = ImageDraw.Draw(im)
    W, H = size
    k = (W - 20) / (2 * half)
    oy = H - 34

    def P(x, y):
        return (W / 2 + x * k, oy - y * k)
    for gx in range(-int(half), int(half) + 1):                 # 1/16 grid, block edges darker
        c = (200, 204, 196, 255) if gx % 8 else (150, 156, 146, 255)
        if gx % 2 == 0 or gx % 8 == 0:
            d.line([P(gx, 0), P(gx, (oy - 30) / k)], fill=c if gx % 8 else (120, 130, 120, 255), width=1)
    d.line([P(-half, 0), P(half, 0)], fill=(90, 70, 50, 255), width=2)
    if nest:
        for a, b in slice_segments(tris(src, nest, units), diagonal):
            d.line([P(*a), P(*b)], fill=(150, 90, 40, 255), width=2)
    n = int(half * 2 * 8)
    prev = None
    for i in range(n + 1):                                        # collision top along the cut
        t = -half + i / 8
        x, z = (t / math.sqrt(2), t / math.sqrt(2)) if diagonal else (t, 0.0)
        y = top_at(boxes, x, z)
        if prev is not None:
            d.line([P(prev[0], prev[1]), P(t, prev[1]), P(t, y)], fill=(40, 110, 220, 255), width=2)
        prev = (t, y)
    for scale, at, colour in people:
        bx, bz = (at / math.sqrt(2), at / math.sqrt(2)) if diagonal else (at, 0.0)
        y0 = rest(boxes, bx, bz, scale)
        body = BODY * scale
        body = body + np.array([0, y0, 0])
        for a, b in slice_segments(body, diagonal):
            d.line([P(a[0] + at, a[1]), P(b[0] + at, b[1])], fill=colour, width=2)
        w = 12.8 * scale / 2 * (math.sqrt(2) if diagonal else 1)       # body box extent along the cut
        d.rectangle([P(at - w, y0 + 9.6 * scale), P(at + w, y0)], outline=colour[:3] + (110,), width=1)
        d.line([P(at - w, y0 + 4.8 * scale), P(at + w, y0 + 4.8 * scale)], fill=colour[:3] + (110,), width=1)
    d.text((8, 4), label + ('（对角剖面）' if diagonal else '（轴向剖面）'), font=FS, fill=(20, 20, 20, 255))
    d.text((8, H - 22), '棕：模型  蓝：碰撞顶面  色线：身体轮廓、碰撞箱与腰线  竖粗线：方块边界', font=FS, fill=(60, 60, 60, 255))
    return im


RED, ORANGE, GREEN, PURPLE = (200, 30, 40, 255), (230, 120, 0, 255), (40, 150, 60, 255), (120, 60, 170, 255)


def g(h):
    return ('ground:%g' % h, 0, 0, 0, 0, 1)


def seat(nest, who, x, z, s, yaw, src=AFTER):
    return (who, 8, x, rest(boxes_for(nest, src), x, z, s), z, s, yaw)


# ---- pet goods --------------------------------------------------------------------------------------------------------
def pet():
    SZ = (440, 300)
    N = 'yukkuri_nest'
    row_old = []
    if (BEFORE.assets / 'yukkuri_props/yukkuri_nest.json').exists() and BEFORE.root != AFTER.root:
        row_old = [tile([g(12), (N, 0, 0, 0, 0, 1), seat(N, REIMU, 0, 0, 1.0, 345, BEFORE)], '前：成体停在软卷顶上（悬空）', SZ, 15, el=22, src=BEFORE),
                   section(N, 0, '前：软垫窝 + 成体 1.0／1.2', [(1.0, 0, RED), (1.2, 0, ORANGE)], src=BEFORE, size=SZ),
                   section(N, 0, '前：软垫窝', [(1.0, 0, RED), (1.2, 0, ORANGE)], diagonal=True, src=BEFORE, size=SZ)]
    rows = [row_old] if row_old else []
    rows.append([tile([g(12), (N, 0, 0, 0, 0, 1), seat(N, REIMU, 0, 0, 1.0, 345)], '后：成体 1.0 坐进垫面', SZ, 15, el=22),
                 section(N, 0, '后：软垫窝 + 成体 1.0／1.2', [(1.0, 0, RED), (1.2, 0, ORANGE)], size=SZ),
                 section(N, 0, '后：软垫窝', [(1.0, 0, RED), (1.2, 0, ORANGE)], diagonal=True, size=SZ)])
    rows.append([tile([g(12), ('yukkuri_nest_teddy', 0, 0, 0, 0, 1), seat(N, MARISA, 0, 0, 1.2, 20)], '成体 1.2（最大个体）', SZ, 15, el=22),
                 tile([g(12), ('yukkuri_nest_polka', 0, 0, 0, 0, 1), seat(N, REIMU, -4.2, 0, .55, 330), seat(N, MARISA, 4.2, 0, .55, 30)],
                      '两只子油 0.55（并排上限约 0.59）', SZ, 15, el=26),
                 tile([g(12), ('yukkuri_nest_floral', 0, 0, 0, 0, 1)] +
                      [seat(N, REIMU if i % 2 else MARISA, x, z, .15, 180 + 40 * i) for i, (x, z) in enumerate(((-3, -2), (2.5, -2.5), (-2, 3), (3, 2.5)))],
                      '四只赤油 0.15', SZ, 22, el=34)])
    L = 'large_yukkuri_nest'
    fam = [seat(L, REIMU, -7.2, -5, 1.0, 160), seat(L, MARISA, 7.2, 1.5, 1.0, 215)] + \
          [seat(L, REIMU if i % 2 else MARISA, x, z, .15, 170 + 30 * i) for i, (x, z) in enumerate(((-4, 7), (-1, 9.5), (1.5, 11), (-6, 10)))]
    big = [seat(L, REIMU, -7.55, 0, 1.1, 175), seat(L, MARISA, 7.55, 0, 1.1, 200)]
    rows.append([tile([g(18), (L, 0, 0, 0, 0, 1)] + fam, '编织大窝：成体 2 + 赤油 4', SZ, 9, az=30),
                 tile([g(18), ('large_yukkuri_nest_wicker', 0, 0, 0, 0, 1)] + big, '编织大窝：两只 1.1 倍成体并排', SZ, 9, az=30),
                 section(L, 0, '编织大窝 + 两只成体 1.0／1.1', [(1.0, -7.1, RED), (1.1, 7.55, ORANGE)], half=18, size=SZ)])
    sheet(rows, PREVIEWS / 'pet-goods/fit.png', '宠物窝容纳：整格是平软垫，软卷在身体外侧且低于腰线；成体坐进窝里，不再停在窝沿上（离线渲染与剖面）', SZ)


def wild():
    SZ = (440, 300)
    SINGLE = 1 | 2 | 4 | 8 | 16 | (1 << 5) | (1 << 6) | (1 << 7) | (1 << 8)
    B = ('forest_burrow', ('mask', SINGLE), 0, 0, 0, 1)
    P = ('personal_leaf_nest', ('mask', 7), 0, 0, 0, 1)
    W = 'large_wild_nest'
    rows = [[tile([g(14), B, (REIMU, 8, 0, 0, 0, 1.0, 345)], '叶床单睡位 + 成体 1.0', SZ, 15, el=26),
             tile([g(14), B, (MARISA, 8, 0, 0, 0, 1.2, 20)], '叶床 + 成体 1.2', SZ, 15, el=26),
             section('forest_burrow', ('mask', SINGLE), '叶床 + 成体 1.0／1.2（无碰撞，站在地面）', [(1.0, 0, RED), (1.2, 0, ORANGE)], size=SZ)],
            [tile([g(14), P, (REIMU, 8, 0, 0, 0, 1.0, 345)], '个人小叶巢 + 长成的成体 1.0', SZ, 15, el=26),
             tile([g(14), P, (MARISA, 8, 0, 0, 0, .75, 20)], '个人小叶巢 + 亚成 0.75', SZ, 15, el=26),
             section('personal_leaf_nest', ('mask', 7), '个人小叶巢 + 成体 1.0／亚成 0.75', [(1.0, 0, RED), (.75, 0, GREEN)], size=SZ)],
            [tile([g(18), (W, 0, 0, 0, 0, 1), seat(W, REIMU, -7.2, -5, 1.0, 160), seat(W, MARISA, 7.2, 1.5, 1.0, 215)] +
                  [seat(W, REIMU if i % 2 else MARISA, x, z, .15, 180 + 30 * i) for i, (x, z) in enumerate(((-4, 7), (-1, 9.5), (1.5, 11), (-6, 10)))],
                  '野生大窝：成体 2 + 赤油 4', SZ, 9, az=30),
             tile([g(18), (W, 0, 0, 0, 0, 1), seat(W, REIMU, -7.55, 0, 1.1, 175), seat(W, MARISA, 7.55, 0, 1.1, 200)], '野生大窝：两只 1.1 倍成体', SZ, 9, az=30),
             section(W, 0, '野生大窝 + 两只成体 1.0／1.1', [(1.0, -7.1, RED), (1.1, 7.55, ORANGE)], half=18, size=SZ)]]
    sheet(rows, PREVIEWS / 'wild-nests/fit.png', '野生巢容纳：叶床睡坑整格、个人小叶巢放大到成体也躺得下、野生大窝两格宽（离线渲染与剖面）', SZ)


def lineup():
    """Every placed block in a row, with the growth stages standing in front."""
    things = [('food_bowl', 0, 14, '食盆'), ('yukkuri_toilet', 0, 15, '沙厕'), ('ceramic_toilet', 0, 15, '陶厕'), ('covered_toilet', 0, 15, '带盖厕'),
              ('yukkuri_nest', 0, 23, '软垫窝'), ('large_yukkuri_nest', 0, 32, '编织大窝'),
              ('forest_burrow', ('mask', 1 | 2 | 4 | 8 | 16 | (1 << 5) | (1 << 6) | (1 << 7) | (1 << 8)), 26, '叶床'),
              ('personal_leaf_nest', ('mask', 7), 24, '个人小叶巢'), ('large_wild_nest', 0, 32, '野生大窝'), ('forage_pile', 4, 14, '储粮堆')]
    items, x = [], 0.0
    marks = []
    for name, units, width, label in things:
        x += width / 2
        items.append((name, units, x, 0, 0, 1))
        marks.append((x, label))
        x += width / 2 + 3
    total = x
    # camera at az 210 looks from -x/-z: screen right is -x, so lay the row out toward -x to read left to right
    items = [(n, u, total / 2 - dx, y, z, s, 0) for n, u, dx, y, z, s in items]
    sx = total / 2 - 8
    for i, (label, s) in enumerate(STAGES.items()):
        items.append((REIMU if i % 2 == 0 else MARISA, 8, sx - (i * 15 if i < 2 else 30 + (i - 2) * 9), 0, -34, s, 345))
    im, _ = R.render(AFTER, items, az=210, el=32, ppu=7, bg=BG)
    canvas = Image.new('RGBA', (im.width, im.height + 60), BG)
    canvas.paste(im, (0, 50), im)
    d = ImageDraw.Draw(canvas)
    d.text((10, 8), '全部放置方块与各成长阶段同框（前排：成体 1.2、成体 1.0、亚成 0.75、子油 0.5、赤油 0.15；后排从左到右：'
           + '、'.join(t[3] for t in things) + '）', font=F, fill=(0, 0, 0, 255))
    out = PREVIEWS / 'pet-goods/lineup.png'
    canvas.convert('RGB').save(out)
    print(out)


if __name__ == '__main__':
    for w in sys.argv[1:] or ['pet', 'wild', 'lineup']:
        {'pet': pet, 'wild': wild, 'lineup': lineup}[w]()
