"""Offline preview of the village street pieces and the oni-san shop.

Textured top-down plans (real vanilla and mod block textures) with the stray objects, stock, staff, goods and markers drawn over them, and
3/4 views of one assembled street (alley + stall + pet-shop lot + oni-san shop, joined as jigsaw joins them). Entities are drawn as symbols.
Requires Pillow, NumPy, Node and the Minecraft client jar (--minecraft-resources). No game or browser. Writes .local/previews/street-oni-layout
of the main checkout (or MCYUKKURI_PREVIEWS); no source or runtime file is modified.
"""
from pathlib import Path
import argparse, html, os, shutil, sys
import numpy as np
from PIL import Image, ImageDraw
sys.path.insert(0, str(Path(__file__).parent))
import preview_shop as ps
import build, author_street as street

ROOT = Path(__file__).resolve().parents[3]
OPAQUE = ('planks', 'cobblestone', 'stone_bricks', 'log', 'wood', 'wool', 'terracotta', 'concrete', 'quartz', 'andesite', 'gravel',
          'grass_block', 'dirt', 'mud_bricks', 'barrel', 'hay_block', 'bookshelf')
NAMES = {'reimu': '灵', 'marisa': '魔', 'alice': '爱', 'chen': '橙', 'patchouli': '帕', 'remilia': '蕾', 'flandre': '芙'}
HUMANS = {'oniisan': '鬼', 'caretaker_brother': '员', 'caretaker_sister': '员'}


def opaque(name): return any(s in name for s in OPAQUE) and not name.endswith(('_slab', '_stairs', '_fence', '_wall', '_pane', '_trapdoor', '_door'))


def mesh(a, layout, offset=(0, 0, 0), cut=None):
    """Vertices of a Building plan: internal solid blocks dropped, blocks above `cut` left out (a cutaway)."""
    out = []
    keep = {p: v for p, v in layout.blocks.items() if cut is None or p[1] <= cut}
    for pos, (name, props, _) in keep.items():
        if name == 'minecraft:jigsaw': continue
        if opaque(name) and all(opaque(keep.get(tuple(pos[i] + d[i] for i in range(3)), ('air', {}, None))[0]) for d, _ in ps.FACES.values()):
            continue
        v = a.block(name, tuple(sorted(props.items()))).copy()
        if not len(v): continue
        v[:, :3] += np.array(pos) + np.array(offset); out.append(v)
    return np.concatenate(out) if out else np.empty((0, 8))


def view(a, vertices, out, name, *args):
    """A 3/4 render from the shared camera."""
    im = ps.render(a, vertices, out, name, *args).convert('RGB')
    im.save(out / f'{name}.png')
    return im


class Plan:
    """A top-down map (north up, east right) with a cell-to-pixel mapping for overlays."""
    def __init__(self, a, out, name, parts, cut, px=22, margin=2):
        xs = [o[0] + x for b, o in parts for x in (0, b.size[0])]; zs = [o[2] + z for b, o in parts for z in (0, b.size[2])]
        self.x0, self.x1, self.z0, self.z1 = min(xs) - margin, max(xs) + margin, min(zs) - margin, max(zs) + margin
        self.px = px; w = (self.x1 - self.x0) * px; h = (self.z1 - self.z0) * px
        cx, cz = (self.x0 + self.x1) / 2, (self.z0 + self.z1) / 2
        vertices = np.concatenate([mesh(a, b, (o[0] - cx, o[1], o[2] - cz), cut) for b, o in parts])
        self.image = ps.render(a, vertices, out, name, w, h, np.pi, np.pi / 2, h / (2 * px), 0).convert('RGBA')
        self.draw = ImageDraw.Draw(self.image, 'RGBA')

    def at(self, x, z):
        """Pixel of the world point (x, z)."""
        return (x - self.x0) * self.px, (z - self.z0) * self.px

    def cell(self, x, z, fill=None, outline=None, width=2, inset=0):
        (u0, v0), (u1, v1) = self.at(x + inset, z + inset), self.at(x + 1 - inset, z + 1 - inset)
        self.draw.rectangle((u0, v0, u1, v1), fill=fill, outline=outline, width=width)

    def text(self, x, z, label, size=14, fill='#ffffff', back='#000000aa'):
        u, v = self.at(x, z); f = ps.font(size)
        box = self.draw.textbbox((u, v), label, font=f, anchor='mm')
        self.draw.rectangle((box[0] - 3, box[1] - 2, box[2] + 3, box[3] + 2), fill=back)
        self.draw.text((u, v), label, font=f, fill=fill, anchor='mm')

    def dot(self, x, z, r, fill, label='', size=13):
        u, v = self.at(x, z)
        self.draw.ellipse((u - r, v - r, u + r, v + r), fill=fill, outline='#1d1d1d', width=2)
        if label: self.draw.text((u, v), label, font=ps.font(size), fill='#ffffff', anchor='mm')

    def arrow(self, x, z, facing, color, length=1.4):
        dx, dz = street.STEP[facing]
        a, b = self.at(x + .5, z + .5), self.at(x + .5 + dx * length, z + .5 + dz * length)
        self.draw.line((a, b), fill=color, width=4)
        self.draw.ellipse((b[0] - 5, b[1] - 5, b[0] + 5, b[1] + 5), fill=color)


def overlay(plan, b, offset, goods=None):
    """Symbols for one plan piece. goods collects the frame items in order (numbered on the plan)."""
    ox, oz = offset[0], offset[2]
    for (x, y, z), (name, props, _) in b.blocks.items():
        if name == 'minecraft:lantern' and y <= b.floor + 3: plan.dot(ox + x + .5, oz + z + .5, 5, '#ffd34d')
        if name.endswith('_door') and props.get('half') == 'lower':
            plan.cell(ox + x, oz + z, outline='#4fd1ff' if 'iron' in name else '#ffffff', width=3, inset=.12)
    for (x, y, z), facing, (sx, sy, sz) in getattr(b, 'garbage', []):
        plan.cell(ox + sx, oz + sz, fill='#ff5a3c55'); plan.dot(ox + x + .5, oz + z + .5, 10, '#d8432c', '垃')
    for x, y, z in getattr(b, 'gaps', []): plan.cell(ox + x, oz + z, fill='#3c8dff88', outline='#1f5fbf', width=2, inset=.08)
    for (x, y, z), kind, facing in getattr(b, 'shelters', []):
        (u0, v0), (u1, v1) = plan.at(ox + x - 1, oz + z - 1), plan.at(ox + x + 2, oz + z + 2)
        plan.draw.rectangle((u0, v0, u1, v1), fill='#c8964b66', outline='#7a4a12', width=3)
        plan.arrow(ox + x, oz + z, facing, '#7a4a12', 2.2)
        plan.text(ox + x + .5, oz + z + .5, '木箱位' if 'crate' in kind else '纸箱位', 13)
    for (x, y, z), facing, *_ in getattr(b, 'jigsaws', []): plan.arrow(ox + x, oz + z, facing, '#ff3df2', 1.2)
    frames = {}
    for ent in b.entities:
        nbt = ent['nbt'][1]; kind = nbt['id'][1].split(':')[1]; x, y, z = ent['pos'][1][1]; x += ox; z += oz
        if kind in NAMES:
            young = 'YukkuriFamily' in nbt
            plan.dot(x, z, 7 if young else 10, '#e0457b' if not young else '#f08bb0', NAMES[kind], 12 if not young else 10)
        elif kind in HUMANS: plan.dot(x, z, 12, '#30343a', HUMANS[kind], 14)
        elif kind == 'item':
            plan.cell(int(x // 1), int(z // 1), fill='#ffffffcc', outline='#555555', width=1, inset=.32)
        elif kind == 'marker':
            tag = nbt['Tags'][1][1][0]
            if tag == 'mcy_shop_frame' and goods is not None:
                goods.append(nbt['data'][1]['Item'][1]['id'][1].split(':')[1])
                frames.setdefault((x, z), []).append(str(len(goods)))
            elif tag.startswith('mcy_oni_') and 'corner' not in tag:
                plan.text(x, z + .9, {'mcy_oni_counter': '柜台交接', 'mcy_oni_dock': '进出货桶', 'mcy_oni_trash': '废物桶'}[tag], 12, back='#5b2a86cc')
    for (x, z), numbers in frames.items():   # two rows of frames on one wall cell share a label
        u, v = plan.at(x, z); label = '/'.join(numbers); f = ps.font(11)
        box = plan.draw.textbbox((u, v), label, font=f, anchor='mm')
        plan.draw.rounded_rectangle((box[0] - 4, box[1] - 3, box[2] + 4, box[3] + 3), 6, fill='#f2a33a', outline='#1d1d1d', width=2)
        plan.draw.text((u, v), label, font=f, fill='#ffffff', anchor='mm')


ZONES = [  # oni shop interior labels (template coordinates)
    (4.5, 6.6, '橱窗笼'), (12, 5.5, '入口'), (19.5, 6.5, '顾客桌'), (3.5, 8.5, '工具墙'), (5, 14.6, '消耗品'), (9, 14.6, '粮食·日用'),
    (18.5, 10.7, '笼 A'), (18.5, 14.7, '笼 B'), (18.5, 18.7, '笼 C'), (22, 12, '员工通道'), (12, 16.5, '柜台'),
    (4, 18, '柜台后'), (6, 21.5, '后屋'), (11.5, 22.3, '处理台')]


def legend(width):
    rows = [('#d8432c', '垃', '垃圾点（标记木桶，前方是取物格，上方活板门是盖子）'), ('#3c8dff', '', '挡风缝（低顶、一侧有墙，地上一张真纸）'),
            ('#c8964b', '', '纸箱／木箱位（代码在此放置实体箱，箭头为开口）'), ('#ffd34d', '', '路灯／灯笼'),
            ('#ff3df2', '', '街道接口（与相邻地块拼接的方向）'), ('#e0457b', '灵', '在售油库里（小圆为子油）'),
            ('#30343a', '鬼', '店主（鬼威惨）／店员'), ('#f2a33a', '1', '展示框（编号见下表）'), ('#4fd1ff', '', '铁门（员工门，两格宽）')]
    im = Image.new('RGBA', (width, 40 + 34 * ((len(rows) + 1) // 2)), '#f6f4ee'); d = ImageDraw.Draw(im)
    for i, (color, mark, label) in enumerate(rows):
        x = 20 + (i % 2) * (width // 2); y = 20 + (i // 2) * 34
        d.ellipse((x, y, x + 22, y + 22), fill=color, outline='#1d1d1d', width=2)
        if mark: d.text((x + 11, y + 11), mark, font=ps.font(12), fill='#ffffff', anchor='mm')
        d.text((x + 32, y + 11), label, font=ps.font(17), fill='#273e37', anchor='lm')
    return im


PAGE = """<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>街道布局与鬼威惨商店</title>
<style>body{font-family:"Microsoft YaHei",sans-serif;background:#eff2eb;color:#273e37;margin:0 auto;max-width:1500px;padding:24px}
h1{margin:0 0 6px}h2{margin:28px 0 8px}.d{background:#fffdf7;border-left:6px solid #d8432c;padding:12px 16px}img{max-width:100%;border-radius:8px;display:block;margin:8px 0}
p{line-height:1.6}ul.g{columns:3;line-height:1.7}</style>
<h1>街道布局与鬼威惨商店 · 布局预览</h1>
<p class="d"><b>要你决定：</b>街道与鬼威惨商店的布局方向是否可以。① 街道不再有售货机等现代物件，换成原版村庄风格的集市摊位和两间小屋夹出的后巷；路灯保留；垃圾桶、挡风缝、纸箱／木箱位放在流浪油会去的地方。② 鬼威惨商店的分区：临街橱窗笼、工具墙、两排货架、三间铁栏笼（员工从后面的通道进笼）、柜台、柜台后员工区和后屋处理台。商店的买卖与店员逻辑由后续代码包接入，目前只是建筑与标记。</p>
<p>画面为离线渲染（真实方块贴图，无光照效果）；油库里、人物、展示框与标记用符号表示。生成时宠物店东侧接口优先接鬼威惨商店，西侧接口接集市或后巷，可继续向外延伸，也可能空着。</p>
<h2>一条街的样子</h2><p>从村庄道路一侧看（北侧）：左边（东）是鬼威惨商店，中间宠物店，右边（西）是集市摊位和后巷。</p><img src="street-overview.png"><img src="street-east.png"><img src="street-west.png"><img src="street-plan.png"><img src="legend.png">
<h2>鬼威惨商店</h2><img src="oni-exterior.png"><img src="oni-interior.png"><img src="oni-plan.png">
<p>展示框里的商品（编号对应平面图）：</p><ul class="g">{{GOODS}}</ul>
<h2>集市摊位</h2><img src="stall-view.png"><img src="stall-plan.png">
<h2>后巷</h2><img src="alley-view.png"><img src="alley-plan.png">
</html>
"""


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--minecraft-resources', type=Path, required=True)
    ap.add_argument('--out', type=Path, default=Path(os.environ.get('MCYUKKURI_PREVIEWS', 'F:/yukkuri/.local/previews')) / 'street-oni-layout')
    args = ap.parse_args(); out = args.out; out.mkdir(parents=True, exist_ok=True)
    a = ps.Assets(args.minecraft_resources)
    pieces = street.pieces(); lot = build.village_shop(build.shop()); lot.floor = 1
    stall, alley, oni, village_oni = pieces['stall'], pieces['alley'], pieces['oni'], pieces['village_oni']
    # One street as jigsaw assembles it: west socket -> stall -> alley; east socket -> oni-san shop.
    assembled = [(alley, (-15 - alley.size[0], 0, 0)), (stall, (-stall.size[0], 0, 0)), (lot, (0, 0, 0)), (village_oni, (lot.size[0], 0, 0))]
    # 3/4 views.
    x0 = -15 - alley.size[0]; x1 = lot.size[0] + village_oni.size[0]; cx = (x0 + x1) / 2
    v = np.concatenate([mesh(a, b, (o[0] - cx, 0, o[2] - 20)) for b, o in assembled])
    view(a, v, out, 'street-overview', 2000, 900, -.5, .5, 25, 4)
    east = [(lot, (0, 0, 0)), (village_oni, (lot.size[0], 0, 0))]
    view(a, np.concatenate([mesh(a, b, (o[0] - 45, 0, o[2] - 18)) for b, o in east]), out, 'street-east', 1800, 1000, -.55, .42, 19, 5)
    west = [(alley, (-15 - alley.size[0], 0, 0)), (stall, (-stall.size[0], 0, 0)), (lot, (0, 0, 0))]
    view(a, np.concatenate([mesh(a, b, (o[0] + 10, 0, o[2] - 10)) for b, o in west]), out, 'street-west', 1800, 1000, -.45, .5, 16, 3)
    v = mesh(a, oni, (-12.5, 0, -13))
    view(a, v, out, 'oni-exterior', 1400, 950, -.5, .38, 15, 6)
    view(a, mesh(a, oni, (-12.5, 0, -13), cut=5), out, 'oni-interior', 1400, 1000, -.62, .95, 15.5, 2)
    for b, name in [(stall, 'stall'), (alley, 'alley')]:
        view(a, mesh(a, b, (-b.size[0] / 2, 0, -b.size[2] / 2)), out, f'{name}-view', 1100, 800, -.55, .5, b.size[2] / 2 + 2, 2)
    # Plans.
    goods = []
    plan = Plan(a, out, 'oni-plan', [(oni, (0, 0, 0))], cut=4, px=34)
    overlay(plan, oni, (0, 0, 0), goods)
    for x, z, label in ZONES: plan.text(x, z, label, 15, back='#273e37cc')
    plan.image.save(out / 'oni-plan.png')
    for b, name in [(stall, 'stall-plan'), (alley, 'alley-plan')]:
        p = Plan(a, out, name, [(b, (0, 0, 0))], cut=b.floor + 1, px=34); overlay(p, b, (0, 0, 0)); p.image.save(out / f'{name}.png')
    p = Plan(a, out, 'street-plan', assembled, cut=3, px=16)
    for b, o in assembled: overlay(p, b, o)
    p.image.save(out / 'street-plan.png')
    legend(1400).save(out / 'legend.png')
    shutil.rmtree(out / '_work', ignore_errors=True)
    labels = {'pin_tool': '大头针', 'care_mallet': '护理锤', 'care_awl': '锥子', 'restraint_frame': '仰卧固定台',
              'wrap_sling': '包裹吊带', 'hanging_hook': '悬吊鱼钩', 'care_vibrator': '振动器', 'iron_enclosure': '铁箱', 'shears': '剪刀',
              'spicy_grain': '超辣油粮', 'sleep_spray': '弹珠汽水喷雾', 'stimulant': '兴奋药', 'sandpaper_mat': '砂纸垫',
              'oil_grain_budget': '廉价油粮', 'bread': '面包', 'candle': '蜡烛', 'paper': '纸'}
    rows = ''.join(f'<li><b>{i}</b> {html.escape(labels.get(g, g))}</li>' for i, g in enumerate(goods, 1))
    (out / 'index.html').write_bytes(PAGE.replace('{{GOODS}}', rows).encode('utf-8'))
    print('Rendered street pieces and the oni-san shop:', out)
    print('Special block renderers omitted:', ', '.join(sorted(a.missing)) or 'none')


if __name__ == '__main__': main()
