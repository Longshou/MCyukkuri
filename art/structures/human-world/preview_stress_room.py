"""Offline preview of the oni-san shop's stress room (author_street.stress_room): the room beside the shop in the five village types, the
room's interior with an adult reimu on the hard middle floor, a plan with the shop's protection box and the eye line between the counter's
lending tray and the room door, and the counter's west end with the lending tray. Real block models and textures, no lighting; no game or
browser. Requires Pillow, NumPy, Node and the Minecraft client jar (--minecraft-resources). Writes .local/previews/oni-stress-room of the
main checkout (or MCYUKKURI_PREVIEWS).
"""
from pathlib import Path
import argparse, os, shutil, sys
import numpy as np
HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(HERE))
import preview_shop as ps
import preview_furniture as pf
import preview_street as pst
import author_street as street
from check_structure import ONI_PROTECTION
from palette import STYLES

NAMES_ZH = {'plains': '平原', 'desert': '沙漠', 'savanna': '热带草原', 'snowy': '雪原', 'taiga': '针叶林'}


def protection(b):
    """Template (x, z) corners of the shop's protection box, from the clerk's post as ShopRegistry computes it."""
    for ent in b.entities:
        nbt = ent['nbt'][1]
        if nbt.get('SiteRole', (0, ''))[1] == 'oni_shopkeeper':
            x, _, z = (int(v // 1) for v in ent['pos'][1][1])
            (lx, _, lz), (hx, _, hz) = ONI_PROTECTION
            return (x + lx, z + lz), (x + hx, z + hz)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--minecraft-resources', type=Path, required=True)
    ap.add_argument('--out', type=Path, default=Path(os.environ.get('MCYUKKURI_PREVIEWS', ROOT.parents[1] / '.local/previews'
                                                                     if ROOT.parent.name == '.worktrees' else ROOT / '.local/previews')) / 'oni-stress-room')
    args = ap.parse_args(); out = args.out; out.mkdir(parents=True, exist_ok=True)
    a = ps.Assets(args.minecraft_resources)
    shot = lambda vertices, name, view: ps.render(a, vertices, out, name, *view).convert('RGB')
    R = street.ROOM; z0, z1 = street.ROOM_Z; cards = []
    # Beside the shop, from the street's west end, in every village type.
    images = []
    for style in STYLES:
        lot = street.pieces(style)['village_oni']
        images.append(shot(pf.mesh(a, lot.blocks, (10, 0, 20)), 'outside-' + style, (1000, 640, -.75, .38, 13, 4)))
    cards.append(pf.sheet(images, [NAMES_ZH[s] for s in STYLES], 2, '解压间 · 贴在鬼威惨商店西侧',
                          '矮披屋，墙、屋面和柱用该村庄的材料与店身相同；门开在店内西墙上，房间在店铺保护范围之外', out, 'outside'))
    # Inside: the room cut away above head height, an adult reimu on the hard floor, the door and the counter beyond.
    oni = street.pieces('plains')['oni']; bodies = pf.Bodies(a)
    room = pf.mesh(a, oni.blocks, (8, 0, 14), ((0, 0, 8), (R + 10, 5, 20)))
    images = [shot(np.concatenate([room, bodies.at('reimu', 3.5 - 8, 3, 14.5 - 14, 90)]), 'inside', (1300, 820, -2.3, .75, 8, 2.5)),
              shot(np.concatenate([room, bodies.at('reimu', 3.5 - 8, 3, 14.5 - 14, 90)]), 'inside-door', (1300, 820, .9, .55, 8, 2.5))]
    cards.append(pf.sheet(images, ['从西南俯看：长椅、中间一块硬地面（开篮处）、通风铁栏、门外就是柜台西端',
                                   '从店里看门：柜台西端的托盘、顾客站位与门在同一排'], 1,
                          '解压间室内（平原，剖到头顶高度）', '室内 5×7 格干燥地面，光照 ≥ 8；成体灵梦作尺寸参照', out, 'inside'))
    # Plan: protection box, the room box, the eye line from the tray stand to the door.
    plan = pst.Plan(a, out, 'plan', [(oni, (0, 0, 0))], cut=4, px=34)
    pst.overlay(plan, oni, (0, 0, 0), [])
    (px0, pz0), (px1, pz1) = protection(oni)
    (u0, v0), (u1, v1) = plan.at(px0, pz0), plan.at(px1 + 1, pz1 + 1)
    plan.draw.rectangle((u0, v0, u1, v1), outline='#d8432c', width=5)
    (u0, v0), (u1, v1) = plan.at(1, z0 + 1), plan.at(R, z1)
    plan.draw.rectangle((u0, v0, u1, v1), outline='#2b9a4a', width=5)
    for z in (street.ROOM_DOOR + .5, street.ROOM_DOOR + 1.5): plan.draw.line((plan.at(R + 5.5, 15.5), plan.at(R + 1.5, z)), fill='#ffd34d', width=4)
    plan.text(3.5, z0 + 3, '解压间', 16, back='#2b9a4acc'); plan.text(R + 12, 2, '店铺保护范围（红框）', 15, back='#d8432ccc')
    plan.text(R + 6, 13, '视线', 13, back='#8a6d00cc')
    plan.image.save(out / 'plan.png')
    cards.append('plan.png')
    # The counter's west end: the lending tray with the restraint frame, next to the manju trays, the room door at the left.
    images = [shot(pf.mesh(a, oni.blocks, (R + 7.5, 3, 16), ((R + 1, 2, 14), (R + 10, 6, 18))), 'counter', (1200, 620, .35, .45, 3.6, 1))]
    cards.append(pf.sheet(images, ['从顾客一侧看柜台西端：靠解压间门的一格（画面右侧）托盘里放着店里的固定台，借出时押金放在这里'], 1,
                          '柜台借还格（平原）', '托盘是现有的抬高展示托盘，与半砖台面同高；标记 mcy_oni_lend', out, 'counter'))
    shutil.rmtree(out / '_work', ignore_errors=True)
    for p in out.glob('*.png'):
        if p.name not in cards: p.unlink()
    body = '\n'.join(f'<figure><img src="{c}"></figure>' for c in cards)
    page = f"""<!doctype html><meta charset="utf-8"><title>鬼威惨商店解压间</title>
<style>body{{background:#edf1eb;font-family:"Microsoft YaHei",sans-serif;color:#273e37;margin:24px}}figure{{margin:0 0 28px}}img{{max-width:100%}}</style>
<h1>鬼威惨商店解压间</h1>
<p>离线渲染，真实方块模型与贴图，无光照。平面图：红框是店铺保护范围，绿框是解压间室内（不受保护），黄线是借还格前的顾客站位与门之间的视线。</p>
<p>请决定：解压间的位置、大小和外观能否采用。</p>
{body}
"""
    (out / 'index.html').write_text(page, encoding='utf-8', newline='\n')
    print('Wrote', out / 'index.html')
    print('Special block renderers omitted:', ', '.join(sorted(a.missing)) or 'none')


if __name__ == '__main__': main()
