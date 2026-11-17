"""Offline preview of the processing plant (author_plant.py): the exterior from the road, a roofless cutaway, a labelled plan, the sweet
boxes up close with the largest admitted bodies standing in them (an adult deibu Reimu at scale 1.8 and an adult Marisa with her hat at
1.2, the runtime models), and the front desk with the receiving booth.

Requires Pillow, NumPy, Node and the Minecraft client jar (--minecraft-resources). No game or browser. Writes .local/previews/fac-plant
of the main checkout (or MCYUKKURI_PREVIEWS); no source or runtime file is modified.
"""
from pathlib import Path
import argparse, json, math, os, shutil, subprocess, sys
import numpy as np
from PIL import Image
from types import SimpleNamespace
sys.path.insert(0, str(Path(__file__).parent))
import preview_shop as ps
import author_plant
import check_structure
from preview_street import mesh, view, Plan

ROOT = Path(__file__).resolve().parents[3]
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
LORE = ROOT / 'standards/yukkuri-library/motions/variants/lore'

PAGE = """<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>油库里加工所 · 厂房</title>
<style>body{font-family:"Microsoft YaHei",sans-serif;background:#eff2eb;color:#273e37;margin:0 auto;max-width:1500px;padding:24px}
h1{margin:0 0 6px}h2{margin:28px 0 8px}.d{background:#fffdf7;border-left:6px solid #d8432c;padding:12px 16px}img{max-width:100%;border-radius:8px;display:block;margin:8px 0}
p{line-height:1.6}table{border-collapse:collapse}td,th{border:1px solid #c9d1c6;padding:4px 10px}</style>
<h1>油库里加工所 · 厂房、前台、甜化箱</h1>
<p class="d"><b>要你决定：</b>村边加工所的外观（白色混凝土墙板、石砖柱、锯齿采光屋顶）是否像一座朴素的油库里加工厂、和村庄放在一起是否不出戏；
甜化箱（单层玻璃隔开的低箱、玻璃盖、正面铜门）的大小与排布、前台与收货玻璃间是否好用。传送带与蒸台、教育室、活袋台、滚轴间先只留地面（黄色角标），后续美术包再填。</p>
<p>45 × 13 × 41 格。正面朝村道：左边是前台楼（大厅、前台柜台、收货玻璃间、柜台后员工区），右边是露天卸货场（矮墙、车道门口、到货箱）。后面是锯齿屋顶的车间：
西墙一排四个甜化箱（A–C 4×4，D 是 4×7 的加宽箱），箱与箱之间只隔一层原版玻璃，箱背是外墙的落地玻璃，缺口可通向厂外，走过厂界才离厂；对面是病栏和药箱、饲料箱；
中间留出传送带与蒸台的地面；东边是教育室、滚轴间（两间都有朝过道的窗）、活袋台地面和库房。所有门都是打过蜡的铜门：玩家和职员能用手开，油库里打不开。</p>
<p>画面为离线渲染（真实方块贴图，无光照；草窝、食盆、沙厕、储物箱、读数牌等方块实体不画，隐藏光源不显示）。站高测试里的油库里是当前运行模型按游戏缩放摆进箱里。</p>
<h2>外观（从村道看）</h2><img src="exterior.png"><img src="exterior-back.png">
<h2>去顶布局</h2><img src="cutaway.png">
<h2>平面图</h2><img src="plan.png">
<h2>甜化箱近看与站高测试</h2><img src="sweet-boxes.png"><p>去掉箱子正面后的站高：D 加宽箱里最宽的蓬发灵胖（1.8 倍，头发展开 2.41 格），A 箱戴帽成年魔理沙（1.2 倍，最高）与 B 箱普通成年灵梦（1 倍），玻璃盖在头顶上方。</p><img src="stand-height-d.png"><img src="stand-height-ab.png"><p>D 箱两扇铜门打开、同一只蓬发灵胖站在门口：身体 1.44 格，门扇之间净宽 1.625 格；头发按游戏里的贴墙让位（WallYield）压到两扇门板内侧，不穿门。</p><img src="door-d.png"><img src="door-d-top.png">{{HEIGHTS}}
<h2>前台与收货玻璃间</h2><img src="front-desk.png">
</html>
"""


CORE = r"""
const fs=require('fs'),path=require('path');
const [root,id,scale,faces,out]=process.argv.slice(2);
const {RuntimePose}=require(path.join(root,'preview/review/pose.js'));
const assets=path.join(root,'src/main/resources/assets/mcyukkuri');
const read=p=>JSON.parse(fs.readFileSync(p,'utf8'));
const pose=new RuntimePose(read(path.join(assets,'yukkuri',id+'.json')),+scale,
  read(path.join(assets,'yukkuri_presentation/motion_corrections.json')).models[id],read(path.join(assets,'yukkuri_presentation/placement_fit.json')).models[id]);
const walls=JSON.parse(faces);if(walls.length)pose.wall={yaw:0,faces:walls,yield:true};
fs.writeFileSync(out,Buffer.from(Float32Array.from(pose.frame('idle',0)).buffer));process.exit(0);
"""


def core_body(ident, scale, faces, work):
    """The idle mesh of a runtime model from the shared pose core (model units x scale), yielded against `faces` as in the game:
    [[nx, nz, distance, low, high]] in blocks around the body axis, the model facing -z."""
    work.mkdir(parents=True, exist_ok=True); script = work / 'core.cjs'; script.write_text(CORE, encoding='utf-8')
    target = work / f'{ident}-{len(faces)}.bin'
    subprocess.run(['node', str(script), str(ROOT), ident, str(scale), json.dumps(faces), str(target)], check=True)
    v = np.frombuffer(target.read_bytes(), dtype='<f4').reshape(-1, 8).astype(float)
    return v[:, :3], v[:, 3:5], v[:, 5:8]


def bodies(a, work):
    """Vertices of the bodies standing in the boxes, facing the sweet aisle (east), their textures on the atlas's lower half.
    The wasa deibu (the widest drawn body, scale 1.8) stands in the wide box D unyielded and passes D's open door with its loose hair
    pressed onto the two open copper panels (faces 0.8125 either side of its axis) by the pose core's WallYield. The adult hatted
    Marisa (1.2, the tallest) stands in A, an ordinary adult Reimu in B."""
    sys.path.insert(0, str(LORE))
    from preview import Posed
    corrections = json.loads((ASSETS / 'yukkuri_presentation/motion_corrections.json').read_text(encoding='utf-8'))
    half = check_structure.DOOR_CLEAR / 2
    bodies = [('reimu_wasa_deibu', 1.8, (3.5, 36.0), []), ('reimu_wasa_deibu', 1.8, (6.5, 35.0), [[1, 0, half, 0, 2], [-1, 0, half, 0, 2]]),
              ('marisa', 1.2, (3.8, 19.5), None), ('reimu', 1.0, (3.5, 24.5), None)]
    out = []
    for i, (ident, scale, (x, z), faces) in enumerate(bodies):
        if faces is None:
            posed = Posed(ident, corrections); tri, uv, n = posed.frame('idle', 0)
            v, uv, n = tri.reshape(-1, 3) * scale, uv.reshape(-1, 2), np.repeat(n, 3, axis=0)
        else:
            v, uv, n = core_body(ident, scale, faces, work)
        v = v / 16
        # The model faces -z; turn it to face +x (the aisle): x' = -z, z' = x.
        v = np.c_[-v[:, 2], v[:, 1], v[:, 0]] + [x, 3, z]
        n = np.c_[-n[:, 2], n[:, 1], n[:, 0]]
        tex = Image.open(ASSETS / f'textures/entity/{ident}/neutral.png').convert('RGBA').resize((512, 512), Image.Resampling.NEAREST)
        ox, oy = (i % 4) * 512, 1536
        a.atlas.paste(tex, (ox, oy))
        out.append(np.c_[v, uv * .25 + [ox / 2048, oy / 2048], n])
    return out


def subset(b, keep):
    """The blocks of b for which keep(x, y, z) holds, as a layout `mesh` can draw."""
    return SimpleNamespace(blocks={p: v for p, v in b.blocks.items() if keep(*p)})


def heights_table(b):
    rows = ''.join(f"<tr><td>{r['id']}</td><td>{ {'box': '甜化箱', 'sick': '病栏', 'booth': '收货间'}[r['kind']] }</td><td>{r['interior']}</td>"
                   f"<td>{r['clear']}</td><td>{r['door']}</td></tr>" for r in check_structure.plant_report(b))
    L = check_structure.LARGEST
    N = check_structure.nest_report()
    return (f"<p>最大可收个体：成年灵胖（体型 ×1.5，成体缩放上限 1.2）碰撞箱 {L['width']} 格宽，蓬发灵胖的模型头发展开 {L['drawn_width']} 格；"
            f"最高的是戴帽成年魔理沙（1.2 倍）{L['height']} 格高。箱内净高 3 格（玻璃盖在第 4 格），门 2 格宽 2 格高（开门后净宽 {N['door_clear']} 格），"
            f"每箱都留得出 2×2 空地，D 箱留 3×3 以上。每箱一个不贴墙的圆草窝，入口朝门：按游戏的窝口与垫面规则收得下 {N['admits']:.2f} 格宽的身体，"
            f"即所有普通成体和成体缩放 {N['deibu_scale']:.2f} 以下的灵胖；1.2 倍的最大灵胖现有任何窝都进不去（单格窝口 {N['opening']:.3f}，2×2 窝口更窄），睡在箱底地面。</p><table><tr><th>编号</th><th>种类</th><th>内部（深×宽）</th><th>净高</th><th>门（宽×高）</th></tr>{rows}</table>")


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--minecraft-resources', type=Path, required=True)
    ap.add_argument('--out', type=Path, default=Path(os.environ.get('MCYUKKURI_PREVIEWS', 'F:/yukkuri/.local/previews')) / 'fac-plant')
    args = ap.parse_args(); out = args.out; out.mkdir(parents=True, exist_ok=True)
    a = ps.Assets(args.minecraft_resources)
    b = author_plant.plant(); w, h, d = b.size
    centre = (-w / 2, 0, -d / 2)
    view(a, mesh(a, b, centre), out, 'exterior', 1700, 1100, -.55, .42, 27, 4)
    view(a, mesh(a, b, centre), out, 'exterior-back', 1500, 950, 2.5, .45, 27, 4)
    view(a, mesh(a, b, centre, cut=6), out, 'cutaway', 1700, 1300, -.45, .95, 26, 2)
    # Sweet boxes up close from the sweet aisle (the hall east of x9 and everything above the lids left out), the three bodies inside.
    boxes = subset(b, lambda x, y, z: x <= 9 and 14 <= z and y <= 6)
    placed = bodies(a, out / '_work'); inside = np.concatenate([placed[0]] + placed[2:])
    sweet = np.concatenate([mesh(a, boxes), inside]); sweet[:, :3] -= [5, 0, 27.5]
    view(a, sweet, out, 'sweet-boxes', 1900, 1000, 1.22, .4, 7.2, 4.5)
    # Stand-height cut: the box fronts (x6) and the aisle taken away, lids kept, the bodies where they stand.
    cut = subset(b, lambda x, y, z: (x <= 5 or y <= 2) and x <= 7 and 15 <= z and y <= 6)
    for name, zc in (('stand-height-d', 35.5), ('stand-height-ab', 21)):
        v = np.concatenate([mesh(a, cut), inside]); v[:, :3] -= [4, 0, zc]
        view(a, v, out, name, 1500, 900, 1.4, .18, 3.4, 4.6)
    # Box D's open door with the wasa deibu in the doorway. The top view omits the lintel to expose the leaves.
    opened = {p: (n, dict(props, open='true') if n.endswith('_door') and 6 <= p[0] <= 6 and 34 <= p[2] <= 35 else props, nbt)
              for p, (n, props, nbt) in b.blocks.items()}
    for name, az, el, span, top in (('door-d', 1.25, .3, 2.6, 5), ('door-d-top', 1.5, 1.35, 3.2, 4)):
        door = subset(SimpleNamespace(blocks=opened), lambda x, y, z: x <= 9 and 30 <= z and y <= top)
        v = np.concatenate([mesh(a, door), placed[1]]); v[:, :3] -= [6, 0, 35]
        view(a, v, out, name, 1400, 900, az, el, span, 3.8)
    # The front desk and the receiving booth from the lobby: the facade and everything above y7 left out.
    desk = subset(b, lambda x, y, z: x <= 21 and z <= 13 and y <= 7 and (z >= 4 or y <= 2))
    view(a, mesh(a, desk, (-11, 0, -9)), out, 'front-desk', 1600, 1000, -.35, .55, 8.5, 4)
    plan = Plan(a, out, 'plan', [(b, (0, 0, 0))], cut=5, px=26)
    labels = [(4, 18.5, '甜化箱 A'), (4, 23.5, 'B'), (4, 28.5, 'C'), (4, 35, 'D 加宽箱'), (12.5, 34, '病栏'), (15, 21, '传送带与蒸台（预留）'),
              (26.5, 21, '教育室（预留）'), (26.5, 33.5, '滚轴间（预留）'), (38.5, 21, '活袋台（预留）'), (38, 30.5, '库房'), (8, 26, '甜化过道'),
              (21, 14.8, '主过道'), (10.5, 6.5, '大厅'), (14.8, 8.3, '收货间'), (5.5, 11.6, '前台'), (31, 8, '卸货场（露天）'), (11, 26.5, '药箱·饲料'),
              (19.9, 30, '中过道')]
    for x, z, text in labels: plan.text(x, z, text, 14, back='#273e37cc')
    for (x, y, z), (name, props, _) in b.blocks.items():
        if name.endswith('_door') and props.get('half') == 'lower': plan.cell(x, z, outline='#e07a2d', width=3, inset=.12)
    plan.cell(1, 3, outline='#ff3df2', width=4); plan.cell(43, 39, outline='#ff3df2', width=4)
    plan.text(2.6, 2.4, '厂界角', 12, back='#ff3df2cc'); plan.text(41.6, 40.3, '厂界角', 12, back='#ff3df2cc')
    plan.image.save(out / 'plan.png')
    shutil.rmtree(out / '_work', ignore_errors=True)
    (out / 'index.html').write_bytes(PAGE.replace('{{HEIGHTS}}', heights_table(b)).encode('utf-8'))
    print('Rendered the processing plant:', out)
    print('Special block renderers omitted:', ', '.join(sorted(a.missing)) or 'none')


if __name__ == '__main__': main()
