"""Offline preview of the village furniture: the bench, table and produce crate in the four woods, the crate close up, the market
stall counter of every village type, the display tray close up and the desks and counters that use it, rendered with the real block
models and textures; an adult Reimu stands by for scale.

Usage: <py> preview_furniture.py --minecraft-resources <client jar> [--out <dir>]
Crate goods are block-entity sprites in the game (client/ProduceCrateRenderer); here they are drawn from the crate's authored contents
with the same layout numbers (GOODS and TRAY below; a tray item without a flat sprite model is drawn as its icon). Requires Pillow, NumPy, Node. Writes .local/previews/town-props of the main checkout (or
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
import author_civic as civic
from build import sequence, string, byte, integer   # NBT literals for mock crates
from palette import PALETTES, STYLES

OPAQUE = ('planks', 'cobblestone', 'stone_bricks', 'log', 'wood', 'wool', 'terracotta', 'concrete', 'quartz', 'andesite', 'gravel',
          'grass_block', 'dirt', 'mud_bricks', 'barrel', 'hay_block', 'bookshelf', 'glass', 'sandstone', 'packed_mud')
# client/ProduceCrateRenderer: sprite size, floor height, row rise, row and item spacing (px), lean (degrees).
GOODS = {'scale': .4, 'floor': 2, 'rise': 1.1, 'rows': 3.2, 'across': 1.9, 'shift': .4, 'lean': -18}
# client/ProduceCrateRenderer.tray: floor and slab height (px), pile size by pile count, pile offset from the centre (px).
TRAY = {'floor': 1, 'slab': 8, 'size': {1: .62, 2: .42, 3: .36, 4: .36}, 'offset': 3.4}
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


def sprite(a, item):
    """Atlas origin of the item's flat sprite: its generated layer, else the fixed-perspective layer, else its icon texture."""
    ns, key = item.split(':')
    try:
        model = a.model(f'{ns}:item/{key}')
        layers = [model.get('textures', {}).get('layer0'), model.get('perspectives', {}).get('fixed', {}).get('textures', {}).get('layer0'),
                  f'{ns}:item/{key}']
        for texture in layers:
            if texture:
                try: return a.tex(texture)
                except (KeyError, FileNotFoundError): pass
    except (KeyError, FileNotFoundError): pass
    a.missing.add(item)


def tray_mesh(a, items, facing, raised):
    """Sprites of a tray's contents lying on its floor, one pile per occupied row."""
    out = []; t = TRAY; turn = ps.rotation('y', -YROT[facing]); piles = len(items); size = t['size'][piles]
    floor = (t['floor'] + (t['slab'] if raised else 0)) / 16
    for pile, (row, item, count) in enumerate(items):
        o = sprite(a, item)
        if o is None: continue
        s = 1 / 64; uv = [o, o + [s, 0], o + [s, s], o + [0, s]]
        x = 0 if piles == 1 else (-t['offset'] if pile % 2 == 0 else t['offset']) / 16
        z = 0 if piles <= 2 else (-t['offset'] if pile < 2 else t['offset']) / 16
        for i in range(min(count, 4)):
            spin = ps.rotation('y', (row * 67 + i * 29) % 50 - 25); h = size / 2
            centre = np.array([x, floor + (i + .5) * size / 16, z])
            corners = [turn @ (spin @ np.array(c, float) + centre) + [.5, 0, .5] for c in ([-h, 0, -h], [h, 0, -h], [h, 0, h], [-h, 0, h])]
            for tri in ((0, 1, 2), (0, 2, 3)):
                for k in tri: out.append([*corners[k], *uv[k], 0, 1, 0])
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
        if name == 'mcyukkuri:display_tray' and nbt: parts.append(tray_mesh(a, contents(nbt), props['facing'], props['raised'] == 'true'))
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


def tray_block(facing, goods, raised=False, wood='oak'):
    """A mock tray as author_street.display_tray writes it."""
    nbt = {'Items': sequence(10, [{'Slot': byte(i), 'id': string(item if ':' in item else 'minecraft:' + item), 'count': integer(n)}
                                  for i, (item, n) in enumerate(goods)])}
    return 'mcyukkuri:display_tray', {'facing': facing, 'raised': str(raised).lower(), 'wood': wood}, nbt


def tray_row():
    """Oak trays: three on a table (one thing, two piles, four piles), then a slab-topped counter with two raised trays in it."""
    b = {}
    for (x, z), props in joined('wooden_table', [(-4, 0), (-3, 0), (-2, 0)], 'x', 'oak').items(): b[x, 0, z] = ('mcyukkuri:wooden_table', props, None)
    b[-4, 1, 0] = tray_block('south', [('mcyukkuri:badge_handbook', 1)])
    b[-3, 1, 0] = tray_block('south', [('mcyukkuri:care_awl', 1), ('shears', 1)])
    b[-2, 1, 0] = tray_block('south', [('book', 2), ('mcyukkuri:bronze_yukkuri_badge', 1), ('paper', 3), ('writable_book', 1)])
    for x in range(0, 5):
        b[x, 0, 0] = ('minecraft:oak_planks', {}, None); b[x, 1, 0] = ('minecraft:oak_slab', {'type': 'bottom', 'waterlogged': 'false'}, None)
    b[1, 1, 0] = tray_block('south', [('mcyukkuri:yukkuri_manju', 3)], True)
    b[3, 1, 0] = tray_block('south', [('mcyukkuri:orange_juice', 2), ('cookie', 2)], True)
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
    # The display tray close up, then the places that use it (plains; every type has the same layout in its own wood).
    images = [shot(mesh(a, tray_row(), (.5, 0, .5)), 'trays', (1100, 460, 3.3, .6, 2.6, .4))]
    cards.append(sheet(images, ['木桌上：一件、两堆、四堆；右边是半砖台面柜台，其中两格换成带托盘的台面（与半砖同高）'], 1,
                       '展示托盘近看 · 桌上、柜台上摆的东西都真的在托盘里',
                       '托盘里每堆最多四件、最多四堆，平放画出；和货箱一样空手右键拿走一件，手持物品右键放进一件，打掉后里面的东西掉出来', out, 'tray'))
    p = PALETTES['plains']; hall, oni, shelter = civic.badge_hall(p), street.oni_shop(p), civic.shelter(p)
    images = [
        shot(mesh(a, hall.blocks, (13, 3, 25), ((4, 2, 22), (21, 6, 30))), 'desk-hall', (1000, 520, .2, .5, 5.2, .8)),
        shot(mesh(a, oni.blocks, (9.5, 3, 16), ((4, 2, 14), (15, 6, 18))), 'desk-oni', (1000, 520, .2, .5, 4.2, .8)),
        shot(mesh(a, oni.blocks, (12, 3, 9.5), ((10, 2, 7), (14, 5, 12))), 'desk-oni-table', (1000, 520, -1.2, .6, 2.2, .7)),
        shot(mesh(a, oni.blocks, (11.5, 3, 23), ((8, 2, 21), (14, 5, 24))), 'desk-oni-bench', (1000, 520, .2, .6, 2.6, .8)),
        shot(mesh(a, shelter.blocks, (14, 1, 16), ((11, 0, 14), (17, 3, 18))), 'desk-shelter', (1000, 520, .25, .5, 2.6, .8)),
        shot(mesh(a, shelter.blocks, (5.5, 1, 21), ((2, 0, 18), (9, 3, 23))), 'desk-supply', (1000, 520, .2, .5, 3.4, .8)),
    ]
    cards.append(sheet(images, ['徽章会馆受理柜台：铜章、徽章手册、书与笔；后面办公桌上一本书', '鬼威惨商店柜台：馒头、曲奇、辣味粮',
                                '鬼威惨商店演示桌：别针与铁箱', '鬼威惨商店后屋处理台：锥子与剪刀', '保护站接收窗口：橙汁与擦洗布',
                                '保护站护理桌与物资架：货箱装油粮与襁褓布，托盘放擦洗布、携带箱、食盆'], 2,
                       '用上托盘的桌面与柜台（平原）', '原来悬浮在台面上的展示物都换成托盘或货箱里的真实物品；立体道具（携带箱、食盆、擦洗布）在这里画成图标，游戏里是缩小的立体模型', out, 'desks'))
    print('Special block renderers omitted:', ', '.join(sorted(a.missing)) or 'none')
    shutil.rmtree(out / '_work')
    for p in out.glob('*.png'):
        if p.name not in cards: p.unlink()
    body = '\n'.join(f'<figure><img src="{c}" alt="{html.escape(c)}"></figure>' for c in cards)
    page = f"""<!doctype html><meta charset="utf-8"><title>村庄木家具、货箱与展示托盘</title>
<style>body{{background:#edf1eb;font-family:"Microsoft YaHei",sans-serif;color:#273e37;margin:24px}}figure{{margin:0 0 28px}}img{{max-width:100%;image-rendering:pixelated}}</style>
<h1>村庄木家具、货箱与展示托盘</h1>
<p>离线渲染，真实方块模型与贴图；成体灵梦为尺寸参照。货箱里的物品在游戏中由方块实体按真实库存逐个画出，这里按同样的排布画出。</p>
<p>请决定：长椅、木桌按村庄木料换色的样子，集市柜台上货箱的样子，以及桌面与柜台上展示托盘的样子，能否采用。</p>
{body}
"""
    (out / 'index.html').write_text(page, encoding='utf-8', newline='\n')
    print('Wrote', out / 'index.html')


if __name__ == '__main__': main()
