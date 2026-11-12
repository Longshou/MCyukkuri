"""Offline before/after page for the operation-tool item sprites (icons.py).

Before: the old builtin item meshes read from a Git revision (default: the parent of the commit that removed them) and rendered with the care-props preview
renderer at the preview-library camera. After: the current sprites at 8x, a GUI-scale-3 hotbar beside the flour paste,
orange juice and a few vanilla tools, and the 1x/2x sizes. Local preview only: vanilla textures are read from
MCYUKKURI_VANILLA (default .local/vanilla-ref/tex/assets/minecraft) and nothing vanilla is copied into the repository.
Output: <MCYUKKURI_PREVIEWS or the main checkout's .local/previews>/oni-tools/{before_after.png, hotbar.png, index.html}.
Usage: python preview_icons.py [--before REV]
"""
import argparse, html, json, os, subprocess, sys, tempfile
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont, ImageOps

HERE = Path(__file__).resolve().parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(HERE.parent / 'care-props'))
import icons
import render_preview as R

ROOT = HERE.parents[3]
PREVIEWS = Path(os.environ.get('MCYUKKURI_PREVIEWS', ROOT / '.local/previews'))
if not (PREVIEWS / 'index.html').exists() and (ROOT.parents[1] / '.local/previews').exists():
    PREVIEWS = ROOT.parents[1] / '.local/previews'            # worktree: share the main checkout's preview hub
OUT = PREVIEWS / 'oni-tools'
FONT = os.environ.get('MCYUKKURI_FONT', 'C:/Windows/Fonts/msyh.ttc')
F, FS = ImageFont.truetype(FONT, 18), ImageFont.truetype(FONT, 14)
BG = (236, 238, 232, 255)
LANG = json.loads((ROOT / 'src/main/resources/assets/mcyukkuri/lang/zh_cn.json').read_text(encoding='utf-8'))
VANILLA_TOOLS = ('shears', 'iron_axe', 'flint_and_steel', 'lead')


def name(ident):
    return LANG.get(f'item.mcyukkuri.{ident}', ident)


def git_bytes(rev, path):
    return subprocess.run(['git', 'show', f'{rev}:{path}'], cwd=ROOT, capture_output=True, check=True).stdout


def before_images(rev):
    """Render each old item mesh exactly as the preview library did (it was the builtin item model)."""
    result = {}
    with tempfile.TemporaryDirectory(dir=ROOT / '.local' if (ROOT / '.local').is_dir() else None) as tmp:
        assets = Path(tmp) / 'src/main/resources/assets/mcyukkuri'
        for ident in icons.SHIPPED:
            for rel in (f'yukkuri_props/{ident}.json', f'textures/props/{ident}.png'):
                target = assets / rel
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(git_bytes(rev, f'src/main/resources/assets/mcyukkuri/{rel}'))
        source = R.Source(tmp)
        for ident in icons.SHIPPED:
            image, _ = R.render(source, [(ident, 8, 0, 0, 0, 1)], ppu=18, az=220, el=28, bg=(0, 0, 0, 0))
            result[ident] = ImageOps.contain(image, (128, 128))
    return result


def sprite(ident):
    return Image.open(icons.SNAPSHOT / f'{ident}.png').convert('RGBA')


def vanilla(ident):
    roots = [R.VANILLA, ROOT.parents[1] / '.local/vanilla-ref/tex/assets/minecraft']   # worktree: the main checkout's copy
    path = next((r / 'textures/item' / f'{ident}.png' for r in roots if (r / 'textures/item' / f'{ident}.png').is_file()), roots[0])
    return Image.open(path).convert('RGBA').crop((0, 0, 16, 16)) if path.is_file() else None


def before_after(before):
    idents = list(icons.SHIPPED) + list(icons.DRAFTS)
    cols, cw, ch = 4, 360, 170
    rows = (len(idents) + cols - 1) // cols
    sheet = Image.new('RGBA', (cols * cw + 20, rows * ch + 20), BG)
    d = ImageDraw.Draw(sheet)
    for k, ident in enumerate(idents):
        x, y = 10 + (k % cols) * cw, 10 + (k // cols) * ch
        d.rounded_rectangle((x, y, x + cw - 10, y + ch - 10), 8, fill=(250, 250, 246, 255), outline=(200, 200, 190, 255))
        label = name(ident) if ident in icons.SHIPPED else '酒精灯（草稿，未接入）'
        d.text((x + 10, y + 6), f'{label}  {ident}', font=FS, fill=(40, 40, 40, 255))
        slot = (139, 139, 139, 255)
        if ident in before:
            d.rectangle((x + 10, y + 30, x + 138, y + 158), fill=(243, 240, 232, 255))
            img = before[ident]
            sheet.alpha_composite(img, (x + 10 + (128 - img.width) // 2, y + 30 + (128 - img.height) // 2))
            d.text((x + 145, y + 85), '→', font=F, fill=(90, 90, 90, 255))
        d.rectangle((x + 170, y + 30, x + 298, y + 158), fill=slot)
        sheet.alpha_composite(sprite(ident).resize((128, 128), Image.NEAREST), (x + 170, y + 30))
        sheet.alpha_composite(sprite(ident), (x + 310, y + 30))
        sheet.alpha_composite(sprite(ident).resize((32, 32), Image.NEAREST), (x + 310, y + 52))
    return sheet


def slot_row(images, scale, dark):
    s = 18 * scale
    row = Image.new('RGBA', (len(images) * s + 2 * scale, s + 2 * scale), (55, 55, 55, 255) if dark else (198, 198, 198, 255))
    d = ImageDraw.Draw(row)
    for k, img in enumerate(images):
        x, y = scale + k * s, scale
        d.rectangle((x, y, x + s - 1, y + s - 1), fill=(139, 139, 139, 255) if not dark else (70, 70, 70, 255))
        d.line((x, y, x + s - 1, y), fill=(55, 55, 55, 255) if not dark else (30, 30, 30, 255), width=scale)
        d.line((x, y, x, y + s - 1), fill=(55, 55, 55, 255) if not dark else (30, 30, 30, 255), width=scale)
        d.line((x, y + s - 1, x + s - 1, y + s - 1), fill=(255, 255, 255, 255) if not dark else (100, 100, 100, 255), width=scale)
        d.line((x + s - 1, y, x + s - 1, y + s - 1), fill=(255, 255, 255, 255) if not dark else (100, 100, 100, 255), width=scale)
        if img is not None:
            row.alpha_composite(img.resize((16 * scale, 16 * scale), Image.NEAREST), (x + scale, y + scale))
    return row


def hotbar():
    tools = [sprite(i) for i in icons.SHIPPED]
    supplies = [Image.open(ROOT / 'art/props/supply-icons' / f'{n}.png').convert('RGBA') for n in ('flour_paste', 'orange_juice')]
    neighbours = supplies + [vanilla(n) for n in VANILLA_TOOLS]
    parts = [('物品栏 · GUI 缩放 3（浅色背包 / 深色快捷栏）', None)]
    for dark in (False, True):
        parts.append((None, slot_row(tools[:8], 3, dark)))
        parts.append((None, slot_row(tools[8:] + neighbours[:2], 3, dark)))
    parts.append(('与现有物品并排：小麦粉糊、橙汁与原版剪刀、铁斧、打火石、拴绳（原版贴图仅本机参考）', None))
    parts.append((None, slot_row(neighbours + tools[:4], 3, False)))
    parts.append(('实际 1× 与缩放 2', None))
    parts.append((None, slot_row(tools, 1, False)))
    parts.append((None, slot_row(tools, 2, False)))
    width = max([p.width for _, p in parts if p is not None] + [int(F.getlength(t)) for t, _ in parts if t]) + 40
    height = sum(30 if t else p.height + 12 for t, p in parts) + 20
    sheet = Image.new('RGBA', (width, height), BG)
    d = ImageDraw.Draw(sheet)
    y = 10
    for title, img in parts:
        if title:
            d.text((20, y), title, font=F, fill=(40, 40, 40, 255)); y += 30
        else:
            sheet.alpha_composite(img, (20, y)); y += img.height + 12
    return sheet


def page():
    return f"""<!doctype html><meta charset="utf-8"><title>鬼威惨工具 · 物品图标新旧对比</title>
<style>body{{font-family:'Microsoft YaHei',sans-serif;background:#eceee8;margin:24px;color:#222}}img{{image-rendering:pixelated;max-width:100%}}
h1{{font-size:22px}}p{{max-width:1100px;line-height:1.6}}</style>
<h1>鬼威惨工具 · 物品图标新旧对比</h1>
<p>{html.escape('左：旧物品外观（早期 3D 网格在物品栏里的样子）；右：新的 16×16 原版风格图标，与小麦粉糊、橙汁、油粮同一画法（左上受光、每种材质一条色相偏移色阶、轮廓取本材质最暗一阶、无黑边无抗锯齿）。工具握把在左下、工作端在右上，跟原版工具同一斜向。装在身上的针、钩、振动器、固定台、吊带、铁箱仍是身上的 3D 模型，本页不改。酒精灯是鬼威惨商店要卖的新货，只画了草稿图标，还没有物品。')}</p>
<p>要你决定：新图标是否替换旧外观（可逐件指出要改的）；酒精灯草稿是否按这个方向做。</p>
<h2>逐件新旧对比（右侧小图为 1× 与 2×）</h2><img src="before_after.png">
<h2>物品栏模拟</h2><img src="hotbar.png">
"""


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--before', help='Git revision holding the old builtin item meshes')
    args = parser.parse_args()
    if not args.before:
        removed = subprocess.run(['git', 'log', '-1', '--format=%H', '--diff-filter=D', '--',
                                  'src/main/resources/assets/mcyukkuri/yukkuri_props/care_mallet.json'],
                                 cwd=ROOT, capture_output=True, text=True, check=True).stdout.strip()
        args.before = f'{removed}^' if removed else 'HEAD'
    OUT.mkdir(parents=True, exist_ok=True)
    before_after(before_images(args.before)).save(OUT / 'before_after.png')
    hotbar().save(OUT / 'hotbar.png')
    (OUT / 'index.html').write_text(page(), encoding='utf-8')
    print(OUT / 'index.html')


if __name__ == '__main__':
    main()
