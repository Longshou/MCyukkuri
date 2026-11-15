"""Offline preview of the whole village main street with the civic pieces (author_civic.py) in every village type.

Per type: the assembled street as jigsaw joins it (west mouth, shelter, badge hall | pet shop | oni-san shop, side street, east mouth), close
3/4 views of the civic side, the market side and the park, and, for plains, top-down plans of the new pieces with the stray objects and
residents drawn over them. Requires Pillow, NumPy, Node and the Minecraft client jar (--minecraft-resources). No game or browser. Writes
.local/previews/town-main of the main checkout (or MCYUKKURI_PREVIEWS); no source or runtime file is modified.
"""
from pathlib import Path
import argparse, os, shutil, sys
import numpy as np
sys.path.insert(0, str(Path(__file__).parent))
import preview_shop as ps
import build, author_street, author_civic
from preview_street import mesh, view, Plan, overlay, legend, NAMES_ZH

PAGE = """<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>村庄主街 · 公共建筑</title>
<style>body{font-family:"Microsoft YaHei",sans-serif;background:#eff2eb;color:#273e37;margin:0 auto;max-width:1500px;padding:24px}
h1{margin:0 0 6px}h2{margin:28px 0 8px}.d{background:#fffdf7;border-left:6px solid #d8432c;padding:12px 16px}img{max-width:100%;border-radius:8px;display:block;margin:8px 0}
p{line-height:1.6}nav a{margin-right:14px}</style>
<h1>村庄主街 · 公共建筑（五种村庄）</h1>
<p class="d"><b>要你决定：</b>主街两侧新加的徽章会馆、油库里保护站、公园、街角小店与两端街口，在五种原版村庄里是否一眼看得出用途、和宠物店／鬼威惨商店／原版房子放在一起是否不出戏。</p>
<p>每种村庄一整条主街（实际生成时每侧最多两处地块再接街口，随机抽取；这里西侧取保护站＋徽章会馆，东侧取鬼威惨商店＋街角小店，公园单独展示）。
徽章会馆：两级台基上的浅色会馆，柱廊山花上是金色徽章浮雕；门前交流广场有布篷下的长椅圈和桌子、两只立着的垃圾桶，台阶两侧台基里是挡风缝。
保护站：矮栅栏围着的开放院子，绿白布篷下三个木箱小屋（流浪油库里的免费住处），屋前搁板下是铺纸的挡风缝，门边垃圾桶里有食物；屋里有接收窗口、护理桌和两位爱护派志愿者。
公园：树篱、环形小路、浅池塘、长椅、带顶凉亭和园丁小屋（沙漠为布荫凉架）。街角小店：朝人行道的售货窗口、蓝白遮阳篷、报纸堆和一张纸；侧巷边的货架下是挡风缝；侧巷通往原版村路。
街口：人行道在路灯与石桩处收住，车道收成原版小路接出去，旁边有长椅和告示板。</p>
<p>画面为离线渲染（真实方块贴图，无光照；水、木箱小屋等方块实体不显示）；平面图里人物、垃圾点、挡风缝与木箱位用符号表示。</p>
<nav>{{NAV}}</nav>
{{STYLES}}
<h2>平面图（平原）</h2><img src="legend.png">{{PLANS}}
</html>
"""


def street(style):
    """[(piece, offset)] of one main street: west chain, the pet-shop lot, east chain; every joint is the lane socket at (edge, 1, 4)."""
    p = build.PALETTES[style]
    civic = author_civic.pieces(style)
    lot = build.village_shop(build.shop(p)); lot.floor = 1
    oni = author_street.pieces(style)['village_oni']
    west = [civic['mouth_west'], civic['shelter'], civic['badge_hall']]
    east = [oni, civic['side_street'], civic['mouth_east']]
    out, x = [], 0
    for b in reversed(west):
        x -= b.size[0]; out.insert(0, (b, (x, 0, 0)))
    out.append((lot, (0, 0, 0))); x = lot.size[0]
    for b in east:
        out.append((b, (x, 0, 0))); x += b.size[0]
    return civic, out


def render_set(a, parts, out, name, size, az, el, span, centre=None):
    x0 = min(o[0] for _, o in parts); x1 = max(o[0] + b.size[0] for b, o in parts)
    cx = (x0 + x1) / 2 if centre is None else centre
    v = np.concatenate([mesh(a, b, (o[0] - cx, 0, o[2] - 18)) for b, o in parts])
    view(a, v, out, name, *size, az, el, span, 4)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--minecraft-resources', type=Path, required=True)
    ap.add_argument('--styles', nargs='*', default=list(build.STYLES))
    ap.add_argument('--out', type=Path, default=Path(os.environ.get('MCYUKKURI_PREVIEWS', 'F:/yukkuri/.local/previews')) / 'town-main')
    args = ap.parse_args(); out = args.out; out.mkdir(parents=True, exist_ok=True)
    a = ps.Assets(args.minecraft_resources)
    sections, nav = [], []
    for style in args.styles:
        civic, parts = street(style)
        render_set(a, parts, out, f'{style}-street', (2200, 820), -.5, .5, 40)
        render_set(a, parts[:3], out, f'{style}-civic', (1600, 950), -.55, .45, 22)
        render_set(a, parts[4:], out, f'{style}-market', (1500, 900), -.55, .45, 20)
        park = civic['park']
        render_set(a, [(park, (0, 0, 0))], out, f'{style}-park', (1300, 900), -.6, .6, 15)
        nav.append(f'<a href="#{style}">{NAMES_ZH[style]}</a>')
        sections.append(f'<h2 id="{style}">{NAMES_ZH[style]}村庄</h2><img src="{style}-street.png"><p>西侧：街口、保护站、徽章会馆</p>'
                        f'<img src="{style}-civic.png"><p>东侧：鬼威惨商店、街角小店与侧巷、街口</p><img src="{style}-market.png">'
                        f'<p>公园（主街西侧的另一种地块）</p><img src="{style}-park.png">')
        print('rendered', style, flush=True)
    plans = []
    civic = author_civic.pieces('plains')
    for key in ('badge_hall', 'shelter', 'park', 'side_street', 'mouth_east'):
        b = civic[key]
        plan = Plan(a, out, f'plan-{key}', [(b, (0, 0, 0))], cut=b.floor + (3 if key == 'badge_hall' else 2), px=30)
        overlay(plan, b, (0, 0, 0)); plan.image.save(out / f'plan-{key}.png'); plans.append(f'<img src="plan-{key}.png">')
    legend(1400).save(out / 'legend.png')
    shutil.rmtree(out / '_work', ignore_errors=True)
    page = PAGE.replace('{{NAV}}', ''.join(nav)).replace('{{STYLES}}', '\n'.join(sections)).replace('{{PLANS}}', ''.join(plans))
    (out / 'index.html').write_bytes(page.encode('utf-8'))
    print('Rendered the civic main street in', ', '.join(args.styles), ':', out)
    print('Special block renderers omitted:', ', '.join(sorted(a.missing)) or 'none')


if __name__ == '__main__': main()
