"""Offline preview of the town lots (author_lots.py) in every village type.

Per type: one mock vanilla lane with the five lots and two vanilla houses of that type standing on it (vanilla templates read from the
client jar; a town lot sits one block above the lane as the jigsaw places it), and a 3/4 view of each lot. Plains: textured top-down
plans with the stray objects, residents, pet yards, hidden corners and the gate drawn over them. Requires Pillow, NumPy, Node and the
Minecraft client jar (--minecraft-resources). No game or browser. Writes .local/previews/town-lots of the main checkout (or
MCYUKKURI_PREVIEWS); no source or runtime file is modified. --styles limits the types, --lots the lots.
"""
from pathlib import Path
import argparse, os, shutil, sys, zipfile
import numpy as np
sys.path.insert(0, str(Path(__file__).parent))
import preview_shop as ps
from preview_street import mesh, view, Plan, overlay, NAMES_ZH
from check_town import read_nbt
import author_lots as lots

DIRS = ('north', 'east', 'south', 'west')
VANILLA = ('{t}_small_house_1', '{t}_medium_house_1')
TITLES = {'home_yard': '民居与宠物院子', 'home_yard_large': '大民居（后院平台与宠物花园）', 'kiosk': '街角小卖亭',
          'pocket_park': '口袋公园', 'back_lot': '后院杂物角'}


class Layout:
    """A plain block layout for mesh(): blocks {(x, y, z): (name, props, nbt)} and a size."""
    def __init__(self, size, blocks): self.size = list(size); self.blocks = blocks


def final(blocks):
    """Jigsaws become their final state, as the game places them; structure voids and air are dropped."""
    out = {}
    for pos, (name, props, nbt) in blocks.items():
        if name == 'minecraft:jigsaw':
            state = (nbt or {}).get('final_state', 'minecraft:air')
            state = state[1] if isinstance(state, tuple) else state
            name, _, rest = state.partition('['); props = dict(kv.split('=') for kv in rest.rstrip(']').split(',') if kv)
        if name in ('minecraft:air', 'minecraft:structure_void'): continue
        out[pos] = (name, props, None)
    return out


def turn(props):
    """Block state turned 90 degrees clockwise (seen from above)."""
    out = dict(props)
    for key in ('facing',):
        if props.get(key) in DIRS: out[key] = DIRS[(DIRS.index(props[key]) + 1) % 4]
    if 'axis' in props and props['axis'] != 'y': out['axis'] = 'z' if props['axis'] == 'x' else 'x'
    for d in DIRS:
        if d in props: out[DIRS[(DIRS.index(d) + 1) % 4]] = props[d]
    if 'rotation' in props: out['rotation'] = str((int(props['rotation']) + 4) % 16)
    return out


def rotated(layout, turns):
    for _ in range(turns):
        w, h, d = layout.size
        layout = Layout((d, h, w), {(d - 1 - z, y, x): (n, turn(p), nbt) for (x, y, z), (n, p, nbt) in layout.blocks.items()})
    return layout


def vanilla(jar, style, name):
    """A vanilla house turned so that its building entrance faces north, with the entrance cell and its height."""
    doc = read_nbt(jar.read(f'data/minecraft/structure/village/{style}/houses/{name.format(t=style)}.nbt'))
    palette = [(s['Name'], s.get('Properties', {})) for s in doc['palette']]
    blocks = {tuple(r['pos']): (*palette[r['state']], r.get('nbt', {})) for r in doc['blocks']}
    entrance = next((pos, props['orientation'].split('_')[0]) for pos, (n, props, nbt) in blocks.items()
                    if n == 'minecraft:jigsaw' and nbt.get('name') == 'minecraft:building_entrance')
    layout = Layout(doc['size'], blocks)
    turns = (DIRS.index('north') - DIRS.index(entrance[1])) % 4
    marked = {pos: (n, {**p, '_entrance': '1'} if pos == entrance[0] else p, nbt) for pos, (n, p, nbt) in blocks.items()}
    layout = rotated(Layout(doc['size'], marked), turns)
    gate = next(pos for pos, (_, p, _) in layout.blocks.items() if p.get('_entrance'))
    layout.blocks[gate] = (layout.blocks[gate][0], {k: v for k, v in layout.blocks[gate][1].items() if k != '_entrance'}, layout.blocks[gate][2])
    return Layout(layout.size, final(layout.blocks)), gate


def lane(p, x0, x1):
    """The mock vanilla lane (3 wide, z -3..-1) and the natural ground around everything at y0."""
    blocks = {}
    for x in range(x0, x1):
        for z in range(-6, 18):
            ground = p.lane_at(x, z) if -3 <= z <= -1 else p.ground
            blocks[x, 0, z] = ('minecraft:' + ground, {'snowy': 'false'} if ground == 'grass_block' else {}, None)
    return Layout((x1 - x0, 1, 24), blocks)


def street(jar, style, order):
    """[(layout, offset)] of the lane and the lots and houses standing on its south side, gates against the lane edge."""
    p = lots.PALETTES[style]; built = lots.pieces(style); parts = []; x = 0
    for item in order:
        if item.startswith('vanilla'):
            layout, (gx, gy, gz) = vanilla(jar, style, VANILLA[int(item[-1])])
            parts.append((layout, (x, 1 - gy, -gz), item))   # the entrance at the lane's first free height (y1), on the front row
        else:
            b = built[item]; parts.append((Layout(b.size, final(b.blocks)), (x, 1, 0), item))
        x += parts[-1][0].size[0] + 2
    return [(lane(p, -3, x + 1), (0, 0, 0), 'lane')] + parts


PAGE = """<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>村庄城镇 · 镇内地块</title>
<style>body{font-family:"Microsoft YaHei",sans-serif;background:#eff2eb;color:#273e37;margin:0 auto;max-width:1500px;padding:24px}
h1{margin:0 0 6px}h2{margin:28px 0 8px}h3{margin:18px 0 4px}.d{background:#fffdf7;border-left:6px solid #d8432c;padding:12px 16px}
img{max-width:100%;border-radius:8px;display:block;margin:8px 0}.g{display:grid;grid-template-columns:1fr 1fr;gap:10px}
p{line-height:1.6}nav a{margin-right:14px}</style>
<h1>村庄城镇 · 镇内地块（五种村庄）</h1>
<p class="d"><b>要你决定：</b>民居与宠物院子、大民居、街角小卖亭、口袋公园和后院杂物角，放在原版村道旁、挨着原版房子时，是否像镇上本来就有的房子，不出戏。
每种村庄用自己原版村庄的材料；地块比村道高一格（原版房子也这样接在村道上），门口有半砖台阶。</p>
<p>画面为离线渲染（真实方块贴图，无光照）；人物、宠物位、垃圾桶等在平面图上用符号表示。宠物院子里目前只有窝、食盆、厕所和玩具，宠物本身等后续的城镇生活包接入。</p>
<nav>{{NAV}}</nav>
{{STYLES}}
<h2>平面图（平原，五种村庄布局相同）</h2>
<p>红点：垃圾桶（浅红格是取物格）；蓝格：挡风缝（地上一张纸）；棕框：纸箱位；绿框：宠物院子；“宠”：宠物起点；“隐”：隐蔽角；“员”：居民住处；紫色箭头：大门（接村道）。</p>
{{PLANS}}
</html>
"""


def plan_extras(plan, b):
    for yard in b.yards:
        (x0, _, z0), (x1, _, z1) = yard['low'], yard['high']
        (u0, v0), (u1, v1) = plan.at(x0, z0), plan.at(x1 + 1, z1 + 1)
        plan.draw.rectangle((u0, v0, u1, v1), outline='#2f9e44', width=4)
        px, _, pz = yard['pet']; plan.dot(px + .5, pz + .5, 10, '#2f9e44', '宠', 12)
        for key, label in (('nest', '窝'), ('bowl', '盆'), ('toilet', '厕')):
            x, _, z = yard[key]; plan.text(x + .5, z + .5, label, 12, back='#2f9e44cc')
    for x, _, z in b.corners: plan.text(x + .5, z + .5, '隐', 13, back='#7048e8cc')
    for (x, y, z), (name, props, _) in b.blocks.items():
        if name.endswith('_bed') and props.get('part') == 'head': plan.text(x + .5, z + .5, '床', 12, back='#495057cc')
    plan.arrow(b.gate, 0, 'north', '#ff3df2', 1.2)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--minecraft-resources', type=Path, required=True)
    ap.add_argument('--styles', nargs='*', default=list(lots.STYLES))
    ap.add_argument('--lots', nargs='*', default=list(lots.LOTS))
    ap.add_argument('--no-plans', action='store_true')
    ap.add_argument('--out', type=Path, default=Path(os.environ.get('MCYUKKURI_PREVIEWS', 'F:/yukkuri/.local/previews')) / 'town-lots')
    args = ap.parse_args(); out = args.out; out.mkdir(parents=True, exist_ok=True)
    a = ps.Assets(args.minecraft_resources); jar = zipfile.ZipFile(args.minecraft_resources)
    sections, nav = [], []
    order = ['home_yard', 'vanilla0', 'kiosk', 'pocket_park', 'vanilla1', 'back_lot', 'home_yard_large']
    for style in args.styles:
        parts = street(jar, style, [o for o in order if o.startswith('vanilla') or o in args.lots])
        x1 = max(o[0] + l.size[0] for l, o, _ in parts[1:]); cx = x1 / 2
        v = np.concatenate([mesh(a, l, (o[0] - cx, o[1], o[2] - 6)) for l, o, _ in parts])
        view(a, v, out, f'{style}-lane', 2000, 820, -.45, .5, x1 / 4.4 + 3, 4)
        cells = []
        built = lots.pieces(style)
        for name in args.lots:
            b = built[name]; layout = Layout(b.size, final(b.blocks))
            v = mesh(a, layout, (-b.size[0] / 2, 0, -b.size[2] / 2))
            view(a, v, out, f'{style}-{name}', 1000, 760, -.6, .55, max(b.size[0], b.size[2]) * .62 + 1, 3)
            view(a, v, out, f'{style}-{name}-back', 1000, 760, 2.5, .6, max(b.size[0], b.size[2]) * .62 + 1, 3)
            cells.append(f'<div><h3>{TITLES[name]}</h3><img src="{style}-{name}.png"><img src="{style}-{name}-back.png"></div>')
        nav.append(f'<a href="#{style}">{NAMES_ZH[style]}</a>')
        sections.append(f'<h2 id="{style}">{NAMES_ZH[style]}村庄</h2><p>村道旁：民居、原版小屋、小卖亭、口袋公园、原版中屋、杂物角、大民居（自西向东）。</p>'
                        f'<img src="{style}-lane.png"><div class="g">{"".join(cells)}</div>')
        print('rendered', style, flush=True)
    plans = []
    if not args.no_plans:
        built = lots.pieces('plains')
        for name in args.lots:
            b = built[name]
            for cut, suffix in ((2, ''),) + (((4, '-floor'),) if name == 'home_yard_large' else ()):
                plan = Plan(a, out, f'plan-{name}{suffix}', [(Layout(b.size, final(b.blocks)), (0, 0, 0))], cut=cut, px=34)
                overlay(plan, b, (0, 0, 0)); plan_extras(plan, b)
                plan.image.save(out / f'plan-{name}{suffix}.png')
                plans.append(f'<h3>{TITLES[name]}{"（屋内楼层）" if suffix else ""}</h3><img src="plan-{name}{suffix}.png">')
    shutil.rmtree(out / '_work', ignore_errors=True)
    page = PAGE.replace('{{NAV}}', ''.join(nav)).replace('{{STYLES}}', '\n'.join(sections)).replace('{{PLANS}}', '\n'.join(plans))
    (out / 'index.html').write_bytes(page.encode('utf-8'))
    print('Rendered the town lots in', ', '.join(args.styles), ':', out)
    print('Special block renderers omitted:', ', '.join(sorted(a.missing)) or 'none')


if __name__ == '__main__': main()
