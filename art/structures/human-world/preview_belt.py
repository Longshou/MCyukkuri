"""Offline preview of the plant's short floor belt and the manju steamer (author_products.machines, author_plant.belt_steamer): the four
steamer states, the belt stopped and running (an animated GIF built from the real animated textures), and the belt floor in the plant
with the largest admitted bodies on the belt and beside it (runtime models from the shared pose core).

Requires Pillow, NumPy, Node and the Minecraft client jar (--minecraft-resources). No game or browser. Writes .local/previews/fac-belt
of the main checkout (or MCYUKKURI_PREVIEWS); no source or runtime file is modified.
"""
from pathlib import Path
import argparse, io, os, shutil, sys
import numpy as np
from PIL import Image
from types import SimpleNamespace
sys.path.insert(0, str(Path(__file__).parent))
import preview_shop as ps
import author_plant, author_products, check_structure
from preview_plant import core_body, subset
from preview_street import mesh, view, Plan

ASSETS = Path(__file__).resolve().parents[3] / 'src/main/resources/assets/mcyukkuri'
STATES = [('manju_steamer', '关盖 · 冷'), ('manju_steamer_lit', '关盖 · 蒸制（汽从排汽管出）'), ('manju_steamer_open', '开盖 · 取放'),
          ('manju_steamer_open_lit', '开盖 · 冒汽')]
PAGE = """<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>加工所 · 短传送带与蒸制台</title>
<style>body{font-family:"Microsoft YaHei",sans-serif;background:#eff2eb;color:#273e37;margin:0 auto;max-width:1500px;padding:24px}
h2{margin:28px 0 8px}.d{background:#fffdf7;border-left:6px solid #d8432c;padding:12px 16px}img{max-width:100%;border-radius:8px;display:block;margin:8px 0}
p{line-height:1.6}</style>
<h1>加工所 · 短传送带与蒸制台</h1>
<p class="d"><b>要你决定：</b>地面短传送带（橡胶带面上的人字防滑条指向运行方向，运转时带面向前走、两侧小灯变绿）和改做的馒头蒸台
（炉膛、托盘、钢锅、可开合的锅盖、蒸汽）是否像朴素工厂里的设备、和厂房其他部分放在一起是否协调。</p>
<p>画面为离线渲染（真实方块模型与贴图，无光照；储物箱、木牌字等方块实体不画）。带面在游戏里由动画贴图滚动；蒸汽是像素点阵的上升动画。
模板放下的是停带和关盖冷台，生产时的状态切换由后续玩法接入。</p>
<h2>蒸台四种状态</h2><img src="steamer.png"><p>{{STATES}}。正面（朝员工）是炉门与托盘：托盘右格（草垫）放真小麦，左格（白蒸布）放出笼的馒头；格里不画假货，代码包把真实物品摆上去；开盖后锅里是放遗体的蒸架。</p>
<h2>传送带：停 / 运转</h2><img src="belt-stopped.png"><img src="belt-running.gif">
<p>两格宽、带面离地 6/16 格，最大的灵胖（1.44 格宽）也整个落在带面上；带尾是石砖墙挡头（碰撞 1.5 格高，带面上的个体跨不过去）。
过了上料端的控制柱，带两侧都是平整干地，带尾留出侧退空地。</p>
<h2>在厂房里</h2><img src="area.png"><img src="area-steam.png"><img src="area-plan.png">
<p>西端（左）是上料端，员工从甜化过道把实篮里的个体放上带；两根控制柱上各有一根原版拉杆和木牌（北：传送带开停；南：料牌，普通甜也送 / 只等浓甜）。
东端挡头墙北侧是员工站位，伸手够得到带尾的个体，转身就是蒸台；蒸台北边是放小麦的箱子。
画面里：带上一只最大的成年灵胖（1.8 倍蓬发）和一只戴帽成年魔理沙（1.2 倍，最高），带尾南侧一只刚下带的普通成年灵梦。</p>
</html>
"""


def place_body(a, ident, scale, xz, y, slot, turn):
    """A runtime body (idle) standing at (x, y, z), turned from facing -z by `turn` quarter turns clockwise seen from above."""
    v, uv, n = core_body(ident, scale, [], OUT / '_work'); v = v / 16
    for _ in range(turn % 4): v = np.c_[-v[:, 2], v[:, 1], v[:, 0]]; n = np.c_[-n[:, 2], n[:, 1], n[:, 0]]
    v = v + [xz[0], y, xz[1]]
    tex = Image.open(ASSETS / f'textures/entity/{ident}/neutral.png').convert('RGBA').resize((512, 512), Image.Resampling.NEAREST)
    ox, oy = (slot % 4) * 512, 1536
    a.atlas.paste(tex, (ox, oy))
    return np.c_[v, uv * .25 + [ox / 2048, oy / 2048], n]


def set_frame(a, name, k):
    """Show frame k of an animated block texture in its atlas slot."""
    a.tex(name)
    strip = Image.open(io.BytesIO(a.read(name, 'textures', 'png'))).convert('RGBA')
    frame = strip.crop((0, 16 * k, 16, 16 * k + 16)).resize((32, 32), Image.Resampling.NEAREST)
    i = a.slots[name]; a.atlas.paste(frame, ((i % 64) * 32, (i // 64) * 32))


def model_at(a, model, pos, yrot=0):
    v = a.faces(model, yrot=yrot).copy(); v[:, :3] += pos; return v


def main():
    global OUT
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--minecraft-resources', type=Path, required=True)
    ap.add_argument('--out', type=Path, default=Path(os.environ.get('MCYUKKURI_PREVIEWS', 'F:/yukkuri/.local/previews')) / 'fac-belt')
    args = ap.parse_args(); OUT = out = args.out; out.mkdir(parents=True, exist_ok=True); (out / '_work').mkdir(exist_ok=True)
    a = ps.Assets(args.minecraft_resources)
    floor = lambda x0, z0, x1, z1: [model_at(a, 'minecraft:block/smooth_stone', (x, -1, z)) for x in range(x0, x1) for z in range(z0, z1)]
    # The four steamer states in a row, fronts toward the camera (south), steam at its first frame.
    row = [model_at(a, 'mcyukkuri:block/' + m, (2.2 * i - 3.8, 0, -.5), 180) for i, (m, _) in enumerate(STATES)]
    view(a, np.concatenate(row + floor(-5, -2, 5, 1)), out, 'steamer', 1600, 700, 2.8, .32, 2.3, .9)
    wall = [model_at(a, 'minecraft:block/stone_bricks', (2, 0, z)) for z in (-1, 0)]
    slab = a.block('minecraft:stone_brick_slab', tuple(sorted(author_plant.STOP_CAP.items())))
    wall += [slab + [2, 1, z, 0, 0, 0, 0, 0] for z in (-1, 0)]
    # The belt alone: 2 x 4 cells running east with the stop wall; stopped, then eight frames of the running texture.
    run = lambda running: np.concatenate([model_at(a, 'mcyukkuri:block/factory_belt' + ('_running' if running else ''), (x - 2, 0, z - 1), 90)
                                          for x in range(4) for z in range(2)] + wall + floor(-3, -2, 4, 3))
    view(a, run(False), out, 'belt-stopped', 1200, 640, 2.6, .55, 3.0, .3)
    frames = []
    for k in range(author_products.BELT_FRAMES):
        set_frame(a, 'mcyukkuri:block/factory_belt_top_running', k)
        frames.append(view(a, run(True), out, f'_belt-{k}', 1200, 640, 2.6, .55, 3.0, .3))
        (out / f'_belt-{k}.png').unlink()
    frames[0].save(out / 'belt-running.gif', save_all=True, append_images=frames[1:], duration=100, loop=0)
    # The belt floor in the plant from the south-west, roof and lamps left out; the largest bodies on and beside the belt.
    b = author_plant.plant(); belt = b.belt
    area = subset(b, lambda x, y, z: 7 <= x <= 20 and 15 <= z <= 26 and y <= 6)
    top = 3 + check_structure.BELT_TOP
    bodies = [place_body(a, 'reimu_wasa_deibu', 1.8, (13.0, 21.0), top, 0, 1), place_body(a, 'marisa', 1.2, (15.2, 21.0), top, 1, 1),
              place_body(a, 'reimu', 1.0, (16.0, 22.9), 3, 2, 2)]
    centre = np.array([-14, 0, -21.5, 0, 0, 0, 0, 0])
    view(a, np.concatenate([mesh(a, area)] + bodies) + centre, out, 'area', 1600, 1000, -2.45, .55, 6.4, 4)
    # The same floor from the north-west with the belt running and the steamer open and steaming.
    sx, sz = belt['steamer']
    rest = subset(area, lambda x, y, z: (x, z) != (sx, sz) or y != 3)
    running = {p: (n, dict(q, running='true') if n == 'mcyukkuri:factory_belt' else q, nbt) for p, (n, q, nbt) in rest.blocks.items()}
    set_frame(a, 'mcyukkuri:block/factory_belt_top_running', 3); set_frame(a, 'mcyukkuri:block/manju_steamer_steam', 2)
    steam = model_at(a, 'mcyukkuri:block/manju_steamer_open_lit', (sx, 3, sz), 270)
    end = np.array([-16, 0, -20.5, 0, 0, 0, 0, 0])
    view(a, np.concatenate([mesh(a, SimpleNamespace(blocks=running)), steam] + bodies) + end, out, 'area-steam', 1600, 1000, -.95, .42, 3.6, 4.3)
    # Top-down plan of the belt floor (x6..21, z14..27) with the stands and the exit.
    X0, Z0 = 6, 14
    plan_area = SimpleNamespace(size=[16, 13, 14], blocks={(x - X0, y, z - Z0): v for (x, y, z), v in b.blocks.items() if X0 <= x < X0 + 16 and Z0 <= z < Z0 + 14})
    plan = Plan(a, out, 'area-plan', [(plan_area, (0, 0, 0))], cut=5, px=60, margin=0)
    for (x, z), colour, text in [(belt['load'], '#2d8ae0', '上料站位'), (belt['stand'], '#e07a2d', '员工站位'), (belt['exit'], '#2fae5a', '侧退出口'),
                                 ((belt['wheat'][0] - 1, belt['wheat'][1]), '#b08a2d', '取小麦')]:
        plan.cell(x - X0, z - Z0, outline=colour, width=4, inset=.08); plan.text(x - X0 + .5, z - Z0 + .5, text, 15, back=colour + 'cc')
    for x, z, text in [(13.5, 20.6, '传送带 → 向东'), (18.5, 19.5, '蒸台'), (18.5, 18.3, '小麦箱'), (17.5, 21, '挡头'), (11.5, 18.4, '开停拉杆'),
                       (11.5, 23.6, '料牌拉杆'), (8, 25, '甜化过道'), (19.9, 25, '中过道')]:
        plan.text(x - X0, z - Z0, text, 15, back='#273e37cc')
    plan.image.save(out / 'area-plan.png')
    shutil.rmtree(out / '_work', ignore_errors=True)
    (out / 'index.html').write_bytes(PAGE.replace('{{STATES}}', '；'.join(f'{i + 1}. {t}' for i, (_, t) in enumerate(STATES))).encode('utf-8'))
    print('Rendered the belt and steamer:', out)


if __name__ == '__main__': main()
