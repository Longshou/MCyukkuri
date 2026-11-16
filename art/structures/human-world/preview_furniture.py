"""Offline preview of the village furniture: the bench, table and produce crate in the four woods, the crate close up, and the market
stall counter of every village type, rendered with the real block models and textures; an adult Reimu stands by for scale.

Usage: <py> preview_furniture.py --minecraft-resources <client jar> [--out <dir>]
Crate goods are block-entity sprites in the game (client/ProduceCrateRenderer); here they are drawn from the crate's authored contents
with the same layout numbers (GOODS below). Requires Pillow, NumPy, Node. Writes .local/previews/town-props of the main checkout (or
MCYUKKURI_PREVIEWS); no source or runtime file is modified.
"""
from pathlib import Path
import argparse, html, json, os, shutil, sys
import numpy as np
from PIL import Image, ImageDraw
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(ROOT / 'scripts'))
from furniture_placement import joined
import preview_shop as ps
import author_street as street
from build import sequence, string, byte, integer   # NBT literals for mock crates
from palette import PALETTES, STYLES

OPAQUE = ('planks', 'cobblestone', 'stone_bricks', 'log', 'wood', 'wool', 'terracotta', 'concrete', 'quartz', 'andesite', 'gravel',
          'grass_block', 'dirt', 'mud_bricks', 'barrel', 'hay_block', 'bookshelf', 'glass', 'sandstone', 'packed_mud')
# client/ProduceCrateRenderer: sprite size, floor height, row rise, row and item spacing (px), lean (degrees).
GOODS = {'scale': .4, 'floor': 2, 'rise': 1.1, 'rows': 3.2, 'across': 1.9, 'shift': .4, 'lean': -18}
YROT = {'south': 0, 'west': 90, 'north': 180, 'east': 270}
WOOD_NOTE = {'oak': '橡木 · 平原', 'spruce': '云杉 · 雪原与针叶林', 'acacia': '金合欢 · 热带草原', 'jungle': '丛林木 · 沙漠'}
STYLE_NAME = {'plains': '平原', 'desert': '沙漠', 'savanna': '热带草原', 'snowy': '雪原', 'taiga': '针叶林'}


def opaque(n): return any(s in n for s in OPAQUE) and not n.endswith(('_slab', '_stairs', '_fence', '_wall', '_pane', '_trapdoor', '_door'))


def goods_mesh(a, items, facing):
    """Sprites of a crate's contents ((slot, item id, count) rows) in block-local coordinates."""
    out = []; g = GOODS; turn = ps.rotation('y', -YROT[facing])
    for row, item, count in items:
        ns, key = item.split(':')
        try: texture = a.model(f'{ns}:item/{key}')['textures']['layer0']
        except KeyError: a.missing.add(item); continue
        o = a.tex(texture); s = 1 / 64
        uv = [o, o + [s, 0], o + [s, s], o + [0, s]]
        for i in range(min(count, 4)):
            centre = np.array([((i - 1.5) * g['across'] + (-g['shift'] if row % 2 == 0 else g['shift'])) / 16,
                               (g['floor'] + (3 - row) * g['rise']) / 16 + g['scale'] / 2, (row - 1.5) * g['rows'] / 16])
            pose = ps.rotation('x', g['lean']) @ ps.rotation('z', (i * 37 + row * 53) % 21 - 10)
            h = g['scale'] / 2
            corners = [turn @ (pose @ np.array(c, float) + centre) + [.5, 0, .5] for c in ([-h, h, 0], [h, h, 0], [h, -h, 0], [-h, -h, 0])]
            normal = turn @ pose @ np.array([0, .6, .8])
            for tri in ((0, 1, 2), (0, 2, 3)):
                for k in tri: out.append([*corners[k], *uv[k], *normal])
    return np.array(out, float).reshape(-1, 8)


def contents(nbt):
    return [(row['Slot'][1], row['id'][1], row['count'][1]) for row in nbt['Items'][1][1]]


def mesh(a, blocks, origin, box=None):
    """Visible blocks ({pos: (name, props, nbt)}), optionally inside box, moved so that origin is at 0; crates with their goods."""
    keep = {p: v for p, v in blocks.items() if box is None or all(box[0][i] <= p[i] <= box[1][i] for i in range(3))}
    out = []
    for pos, (name, props, nbt) in keep.items():
        if name in ('minecraft:jigsaw', 'minecraft:air'): continue
        if opaque(name) and all(opaque(keep.get(tuple(pos[i] + d[i] for i in range(3)), ('air', {}, None))[0]) for d, _ in ps.FACES.values()):
            continue
        parts = [a.block(name, tuple(sorted(props.items()))).copy()]
        if name == 'mcyukkuri:produce_crate' and nbt: parts.append(goods_mesh(a, contents(nbt), props['facing']))
        for v in parts:
            if len(v): v[:, :3] += np.array(pos) - np.array(origin); out.append(v)
    return np.concatenate(out)


class Bodies:
    """Adult yukkuri at game scale (model units / units_per_block), their textures pasted into a free corner of the block atlas."""
    SLOTS = {'reimu': (1024, 1536)}

    def __init__(self, a):
        lore = ROOT / 'standards/yukkuri-library/motions/variants/lore'      # its preview.py, not scripts/preview.py
        sys.path.insert(0, str(lore)); sys.modules.pop('preview', None)
        from preview import Posed
        corrections = json.loads((ps.ASSETS / 'mcyukkuri/yukkuri_presentation/motion_corrections.json').read_text(encoding='utf8'))
        self.meshes = {}
        for ident, (ux, uy) in self.SLOTS.items():
            posed = Posed(ident, corrections); tri, uv, n = posed.frame('idle', 0)
            unit = posed.model['units_per_block']
            texture = Image.fromarray(posed.texture('neutral')).resize((512, 512), Image.Resampling.NEAREST)
            a.atlas.paste(texture, (ux, uy))
            uv = uv.reshape(-1, 2) * (512 / 2048) + np.array([ux, uy]) / 2048
            self.meshes[ident] = np.c_[tri.reshape(-1, 3) / unit, uv, np.repeat(n, 3, axis=0)]

    def at(self, ident, x, y, z, yaw=0.):
        v = self.meshes[ident].copy(); c, s = np.cos(np.radians(yaw)), np.sin(np.radians(yaw))
        turn = np.array([[c, 0, s], [0, 1, 0], [-s, 0, c]])
        v[:, :3] = v[:, :3] @ turn.T + [x, y, z]; v[:, 5:8] = v[:, 5:8] @ turn.T
        return v


def crate_block(wood, facing, goods):
    """A mock crate as author_street.produce_crate writes it."""
    nbt = {'Items': sequence(10, [{'Slot': byte(i), 'id': string('minecraft:' + item), 'count': integer(n)} for i, (item, n) in enumerate(goods)])}
    return 'mcyukkuri:produce_crate', {'facing': facing, 'wood': wood}, nbt


def wood_set(style):
    """A stool, a three-piece bench, a two-piece table and crates (one on the table, one on the ground) in the type's wood, in front of
    a wall of the type's planks on its sidewalk stone."""
    p = PALETTES[style]; b = {}
    for x in range(-5, 6):
        for z in range(-2, 3): b[x, -1, z] = ('minecraft:' + p.sidewalk[0][0], {}, None)
        for y in (0, 1): b[x, y, -2] = ('minecraft:' + p.planks(), {}, None)
    for kind, cells in [('wooden_bench', [(-4, 0)]), ('wooden_bench', [(-2, 0), (-1, 0), (0, 0)]), ('wooden_table', [(2, 0), (3, 0)])]:
        for (x, z), props in joined(kind, cells, 'x', p.wood).items(): b[x, 0, z] = ('mcyukkuri:' + kind, props, None)
    b[3, 1, 0] = crate_block(p.wood, 'south', [('apple', 4), ('apple', 4), ('apple', 3)])
    b[4, 0, 1] = crate_block(p.wood, 'south', [('carrot', 4), ('potato', 4), ('beetroot', 3), ('bread', 2)])
    return b


def crate_row():
    """Oak crates on a board counter: full, half full with mixed goods, one row, empty."""
    b = {}
    for x in range(-2, 3): b[x, -1, 0] = ('minecraft:oak_planks', {}, None)
    for x, goods in [(-2, [('apple', 4)] * 4), (-1, [('carrot', 4), ('potato', 4), ('beetroot', 3)]), (0, [('bread', 3), ('pumpkin_pie', 2)]),
                     (1, [('carrot', 2)]), (2, [])]:
        b[x, 0, 0] = crate_block('oak', 'south', goods)
    return b


def sheet(images, titles, columns, title, note, out, name):
    w, h = images[0].size; rows = -(-len(images) // columns); head = 96
    page = Image.new('RGB', (w * columns + 20 * (columns - 1), head + rows * (h + 44)), '#edf1eb'); d = ImageDraw.Draw(page)
    d.text((20, 10), title, font=ps.font(28), fill='#273e37'); d.text((20, 54), note, font=ps.font(17), fill='#596f65')
    for i, (im, text) in enumerate(zip(images, titles)):
        x, y = (i % columns) * (w + 20), head + (i // columns) * (h + 44)
        d.text((x + 20, y + 6), text, font=ps.font(22), fill='#2b6a3b'); page.paste(im, (x, y + 44))
    page.save(out / f'{name}.png')
    return name + '.png'


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--minecraft-resources', type=Path, required=True)
    ap.add_argument('--out', type=Path, default=Path(os.environ.get('MCYUKKURI_PREVIEWS', ROOT.parents[1] / '.local/previews'
                                                                     if ROOT.parent.name == '.worktrees' else ROOT / '.local/previews')) / 'town-props')
    args = ap.parse_args(); out = args.out; out.mkdir(parents=True, exist_ok=True)
    a = ps.Assets(args.minecraft_resources); bodies = Bodies(a)
    shot = lambda vertices, name, view: ps.render(a, vertices, out, name, *view).convert('RGB')
    cards = []
    # The four woods, one village type each (snowy and taiga share spruce).
    styles = ['plains', 'snowy', 'savanna', 'desert']
    images = [shot(np.concatenate([mesh(a, wood_set(s), (0, 0, 0)), bodies.at('reimu', -2.2, 0, 1.6, 200)]), 'wood-' + s, (900, 520, 3.5, .42, 3.3, .5))
              for s in styles]
    cards.append(sheet(images, [WOOD_NOTE[PALETTES[s].wood] for s in styles], 2, '长椅、木桌、货箱 · 四种木料',
                       '每组背后是该村庄类型的原版木板墙，地面是它的人行道石料；从左到右：凳子、三节长椅、两节木桌（桌上一只货箱）、地上一只货箱', out, 'woods'))
    # The crate close up.
    images = [shot(mesh(a, crate_row(), (.5, 0, .5)), 'crates', (1100, 420, 3.3, .5, 1.5, .2))]
    cards.append(sheet(images, ['从左到右：装满 16 个、三排、两排、一排两个、空箱'], 1, '货箱近看 · 画出来的就是箱里装的',
                       '箱里每个物品单独画出：最多四排、每排四个；空手右键拿走一个，手持物品右键放进一个，打掉箱子里面的东西掉出来', out, 'crate'))
    # The market stall of every village type, seen from the lane.
    images = []
    for s in STYLES:
        stall = street.street_stall(PALETTES[s])
        images.append(shot(np.concatenate([mesh(a, stall.blocks, (7, 0, 8), ((0, 0, 5), (14, 7, 15))), bodies.at('reimu', 2.5 - 7, 1, 7.5 - 8, 20)]),
                           'stall-' + s, (1000, 560, .25, .14, 2.7, 1.9)))
    cards.append(sheet(images, [STYLE_NAME[s] for s in STYLES], 1, '集市摊位 · 五种村庄',
                       '柜台上原来悬浮的展示物换成货箱：胡萝卜、马铃薯、苹果、甜菜根、面包、南瓜派；货箱用该类型的木料', out, 'stalls'))
    print('Special block renderers omitted:', ', '.join(sorted(a.missing)) or 'none')
    shutil.rmtree(out / '_work')
    for p in out.glob('*.png'):
        if p.name not in cards: p.unlink()
    body = '\n'.join(f'<figure><img src="{c}" alt="{html.escape(c)}"></figure>' for c in cards)
    page = f"""<!doctype html><meta charset="utf-8"><title>村庄木家具与集市货箱</title>
<style>body{{background:#edf1eb;font-family:"Microsoft YaHei",sans-serif;color:#273e37;margin:24px}}figure{{margin:0 0 28px}}img{{max-width:100%;image-rendering:pixelated}}</style>
<h1>村庄木家具与集市货箱</h1>
<p>离线渲染，真实方块模型与贴图；成体灵梦为尺寸参照。货箱里的物品在游戏中由方块实体按真实库存逐个画出，这里按同样的排布画出。</p>
<p>请决定：长椅、木桌按村庄木料换色的样子，以及集市柜台上货箱的样子，能否采用。</p>
{body}
"""
    (out / 'index.html').write_text(page, encoding='utf-8', newline='\n')
    print('Wrote', out / 'index.html')


if __name__ == '__main__': main()
