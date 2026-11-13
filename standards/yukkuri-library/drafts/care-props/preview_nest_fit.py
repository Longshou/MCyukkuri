"""Offline preview of the one-block pet beds (local only, nothing shipped): the three grades side by side, every grade
against a wall and in a corner, and the fitted cushion shapes (flat side, corner, corridor, three walls, blocked entrance)
next to stone blocks, with adult and child yukkuri for scale; plus each grade at native size (1 texel per pixel, 16 px a
block, as on screen at default zoom). Fitted shapes draw with the bed's own texture, as client/CareRenderer does.
Output .local/previews/nest-grades/ (MCYUKKURI_PREVIEWS changes the root)."""
import os, sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).resolve().parent))
import numpy as np
from PIL import Image, ImageDraw, ImageFont
import render_preview as R

ROOT = R.ROOT
OUT = Path(os.environ.get('MCYUKKURI_PREVIEWS', ROOT.parents[1] / '.local/previews' if ROOT.parent.name == '.worktrees'
                          else ROOT / '.local/previews')) / 'nest-grades'
if 'MCYUKKURI_VANILLA' not in os.environ and ROOT.parent.name == '.worktrees':
    R.VANILLA = ROOT.parents[1] / '.local/vanilla-ref/tex/assets/minecraft'
FONT = ImageFont.truetype(os.environ.get('MCYUKKURI_FONT', 'C:/Windows/Fonts/msyh.ttc'), 13)
BG = (226, 228, 222, 255)
GRADES = [('straw_nest', '1 档 草窝'), ('yukkuri_nest', '2 档 软垫窝'), ('velvet_nest', '3 档 丝绒窝')]


class Src(R.Source):
    """`pet_nest_fit_<m>@<look>` draws a fitted shape with that look's texture; `stone` is one stone-brick block."""
    def load(self, name):
        if name == 'stone':
            h, t = 8.0, 1.0
            faces = []
            for n, quad in (((0, 1, 0), [(-h, 16, -h), (-h, 16, h), (h, 16, h), (h, 16, -h)]),
                            ((0, 0, 1), [(-h, 16, h), (-h, 0, h), (h, 0, h), (h, 16, h)]),
                            ((0, 0, -1), [(h, 16, -h), (h, 0, -h), (-h, 0, -h), (-h, 16, -h)]),
                            ((1, 0, 0), [(h, 16, h), (h, 0, h), (h, 0, -h), (h, 16, -h)]),
                            ((-1, 0, 0), [(-h, 16, -h), (-h, 0, -h), (-h, 0, h), (-h, 16, h)])):
                uv = [(0, 0), (0, t), (t, t), (t, 0)]
                for a, b, c in ((0, 1, 2), (0, 2, 3)):
                    for k in (a, b, c):
                        faces.append([*quad[k], *uv[k], *n])
            return ({'parts': [{'name': 'stone', 'start': 0, 'count': len(faces), 'texture': 'minecraft:textures/block/stone_bricks.png'}],
                     'bounds': [[-h, h], [0, 16], [-h, h]]}, np.array(faces, dtype=np.float32))
        return super().load(name.split('@')[0])

    def tex_of(self, name, part):
        if '@' in name:
            return self.assets / 'textures/props' / f"{name.split('@')[1]}.png"
        return super().tex_of(name, part)


SRC = Src(ROOT)
ADULT, CHILD = 'yuk:reimu:neutral', 'yuk:marisa:neutral'
# Nest frame bits (placed.nest_walls): 1 north = entrance side, 2 east, 4 south, 8 west.
WALL_AT = {1: (0, -16), 2: (16, 0), 4: (0, 16), 8: (-16, 0)}


def scene(look, mask, sitters=()):
    name = look if mask == 0 else f'pet_nest_fit_{mask}@{look}'
    items = [('ground:28', 0, 0, 0, 0, 1), (name, 99, 0, 0, 0, 1)]
    for bit, (x, z) in WALL_AT.items():
        if mask & bit:
            items.append(('stone', 0, x, 0, z, 1))
    if mask & 2 and mask & 4:
        items.append(('stone', 0, 16, 0, 16, 1))
    items += [(who, 0, x, 1.75, z, s, yaw) for who, x, z, s, yaw in sitters]
    return items


def tile(items, label, size=(330, 270), ppu=7, az=200, el=38):
    im = Image.new('RGBA', size, BG)
    ImageDraw.Draw(im).text((6, 4), label, font=FONT, fill=(0, 0, 0, 255))
    r, _ = R.render(SRC, items, az=az, el=el, ppu=ppu)
    k = min((size[0] - 8) / r.width, (size[1] - 26) / r.height, 1.0)
    if k < 1:
        r = r.resize((max(1, int(r.width * k)), max(1, int(r.height * k))), Image.LANCZOS)
    im.paste(r, ((size[0] - r.width) // 2, 22 + (size[1] - 22 - r.height) // 2), r)
    return im


def sheet(rows, title, size=(330, 270)):
    cols = max(len(r) for r in rows)
    s = Image.new('RGBA', (size[0] * cols, 34 + size[1] * len(rows)), BG)
    ImageDraw.Draw(s).text((8, 8), title, font=FONT, fill=(0, 0, 0, 255))
    for ri, tiles in enumerate(rows):
        for ci, t in enumerate(tiles):
            s.paste(t, (ci * size[0], 34 + ri * size[1]))
    return s.convert('RGB')


def native(look):
    """One bed at about 16 px a block, the default in-game size, then shown 3x with nearest-neighbour."""
    r, _ = R.render(SRC, [(look, 99, 0, 0, 0, 1)], az=200, el=38, ppu=1.0)
    return r.resize((r.width * 3, r.height * 3), Image.NEAREST)


def main():
    OUT.mkdir(parents=True, exist_ok=True)
    adult = [(ADULT, 0, 0, 1.0, 20)]
    back, corner = [(ADULT, 0, -2.0, 1.0, 20)], [(ADULT, -2.0, -2.0, 1.0, 20)]   # the resting middle moves off the walls
    grades = [[tile(scene(look, 0, adult), f'{label}（空地，圆形）') for look, label in GRADES],
              [tile(scene(look, 4, back), f'{label}（背靠墙）') for look, label in GRADES],
              [tile(scene(look, 6, corner), f'{label}（墙角）') for look, label in GRADES]]
    sheet(grades, '三档宠物窝：同一形体，草编 / 软垫 / 丝绒；入口（低卷边）朝镜头左前').save(OUT / 'grades.png')
    shapes = [(2, '一侧贴墙（D 形，平背）', [(ADULT, -2.0, 0, 1.0, 20)]), (6, '墙角', [(ADULT, -2.0, -2.0, 1.0, 20)]),
              (10, '一格宽走廊（两侧都贴墙）', [(ADULT, 0, 0, 1.0, 20)]), (14, '一格宽凹槽（三面墙，只剩入口）', [(ADULT, 0, -2.0, 1.0, 20)]),
              (14, '凹槽里的子油', [(CHILD, 0, -1, .55, 20)]), (15, '四面都挡（放不下；放好后被围住则走不进）', [])]
    rows = [[tile(scene('yukkuri_nest', m, s), label) for m, label, s in shapes[:3]],
            [tile(scene('yukkuri_nest', m, s), label) for m, label, s in shapes[3:]],
            [tile(scene('yukkuri_nest', m, s), label + '（俯视）', az=180, el=80) for m, label, s in shapes[:3]]]
    sheet(rows, '贴墙变形：伸进邻格的软卷压平贴墙，不穿进方块（灰色石砖为墙）').save(OUT / 'fit.png')
    strip = Image.new('RGBA', (3 * 150, 120), BG)
    d = ImageDraw.Draw(strip)
    for i, (look, label) in enumerate(GRADES):
        n = native(look)
        strip.paste(n, (i * 150 + (150 - n.width) // 2, 22 + (98 - n.height) // 2), n)
        d.text((i * 150 + 6, 4), label, font=FONT, fill=(0, 0, 0, 255))
    strip.convert('RGB').save(OUT / 'native.png')
    (OUT / 'index.html').write_text('''<!doctype html><meta charset="utf-8"><title>三档宠物窝与贴墙变形</title>
<style>body{font-family:sans-serif;background:#e2e4de;margin:16px}img{display:block;margin:8px 0 20px;image-rendering:pixelated}</style>
<h1>三档宠物窝与贴墙变形</h1>
<p>要你决定：三档（草窝 / 软垫窝 / 丝绒窝）一眼能否分出高低、风格是否和现有物品一致；贴墙压平的形状（D 形平背、墙角、走廊）是否自然。</p>
<h2>1 倍大小（默认视距约 16 像素一格，放大 3 倍显示）</h2><img src="native.png" alt="三档 1 倍">
<h2>物品栏图标（16×16，放大 8 倍）</h2><img src="icons.png" alt="图标">
<h2>三档：空地、背靠墙、墙角</h2><img src="grades.png" alt="三档">
<h2>贴墙变形：一侧、墙角、走廊、凹槽、四面（入口被挡时自动转到下一个空着的一侧）</h2><img src="fit.png" alt="贴墙">
''', encoding='utf-8')
    print(OUT)


if __name__ == '__main__':
    main()
