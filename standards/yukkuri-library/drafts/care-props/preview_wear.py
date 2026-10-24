"""Offline preview sheets of the leaf and cloth swaddle worn on the runtime bodies, and of the swaddle icons (not shipped).

Writes the raw textures (entity faces, the leaf sheet, the cloth fabric tiles and trim sheet) and a task file for preview_wear.cjs (the
CareWearRenderer port, which bakes vertex colours into tinted atlas copies), runs node and assembles in .local/previews/swaddle/:
leaf.png (baby and young child at their true scales, front, three-quarter, side and back, mid-hop and asleep, the opened leaf under the
child, one and three stool loads, a family at true sizes), cloth.png (the eight cloth styles on reimu from four sides and on marisa and
alice) and icons.png (the eight swaddle cloth icons at 1x and 4x, the leaf sheet, fabric tiles and trim sheet). Panels are mirrored
left-right so they read as in the game (the review rasterizer is mirror-handed).
Environment: MCYUKKURI_PREVIEWS overrides the preview root.
"""
import json, os, subprocess, sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[4]
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
OUT = Path(os.environ.get('MCYUKKURI_PREVIEWS', ROOT / '.local/previews')) / 'swaddle'
WORK = OUT.parent / '_work/swaddle'
W, H = 260, 230
MODELS = ('reimu', 'marisa', 'alice')
FACES = ('neutral', 'sleep')
FABRICS = ('knit', 'cotton', 'fleece', 'tiger', 'gingham', 'floral')
STYLES = (('knit', '奶油针织（项目设计）'), ('frill', '荷叶边蝴蝶结（EH30）'), ('letter', '首字母（EH34 第7–8页）'), ('tiger', '虎纹（EH34 第12–14页）'),
          ('outing', '外出款（EH31 第2页）'), ('fleece', '防寒厚绒（N91 用途）'), ('gingham', '格纹（项目设计）'), ('floral', '碎花（项目设计）'))
BG = (226, 228, 222)


def textures():
    """Raw RGBA of every texture the port samples, keyed by the names it uses."""
    WORK.mkdir(parents=True, exist_ok=True)
    out = {}

    def raw(name, path):
        image = Image.open(path).convert('RGBA')
        file = WORK / (name.replace(':', '_') + '.rgba')
        file.write_bytes(image.tobytes())
        out[name] = {'file': str(file), 'w': image.width, 'h': image.height}
    for model in MODELS:
        for face in FACES:
            raw(f'entity:{model}:{face}', ASSETS / f'textures/entity/{model}/{face}.png')
    raw('leaf', ASSETS / 'textures/misc/swaddle_leaf_wear.png')
    raw('trim', ASSETS / 'textures/misc/swaddle_trim.png')
    for fabric in FABRICS:
        raw(fabric, ASSETS / f'textures/misc/swaddle/{fabric}.png')
    return out


VIEWS = (('正面', 0.0), ('3/4', 0.75), ('侧面', 1.5708), ('背面', 3.14159))
STAGES = (('子油', .40), ('赤油', .18))


def leaf_panels():
    panels, rows = [], []
    for stage, scale in STAGES:
        for model in ('reimu', 'marisa'):
            row = []
            for vname, az in VIEWS:
                pid = f'leaf-{stage}-{model}-{len(row)}'
                panels.append({'id': pid, 'azimuth': az, 'span': 11 * scale, 'centerY': 7.5 * scale,
                               'actors': [{'model': model, 'action': 'idle', 'scale': scale, 'wear': 'leaf'}]})
                row.append((pid, f'{stage}({scale}) {model} 树叶襁褓 {vname}'))
            for label, action, phase, face, az in (('跳跃 3/4', 'hop', .4, 'neutral', .75), ('睡眠 侧面', 'sleep', .5, 'sleep', 1.5708)):
                pid = f'leaf-{stage}-{model}-{len(row)}'
                panels.append({'id': pid, 'azimuth': az, 'span': 11 * scale, 'centerY': 7.5 * scale,
                               'actors': [{'model': model, 'action': action, 'phase': phase, 'face': face, 'scale': scale, 'wear': 'leaf'}]})
                row.append((pid, f'{stage} {model} {label}'))
            rows.append(row)
    extra = []
    for pid, actor, az, label in (
            ('open-34', {'model': 'reimu', 'action': 'first_toilet_assist', 'phase': 0.0, 'wear': 'open', 'scale': .4}, .75, '解开照护：叶片摊开在身下 3/4'),
            ('open-side', {'model': 'marisa', 'action': 'idle', 'wear': 'open', 'scale': .4}, 1.5708, '解开照护：摊开的叶 侧面'),
            ('soil-1', {'model': 'reimu', 'action': 'idle', 'wear': 'leaf', 'soil': 1, 'scale': .4}, 2.4, '积便 1 份：下部染色'),
            ('soil-3', {'model': 'reimu', 'action': 'idle', 'wear': 'leaf', 'soil': 3, 'scale': .4}, 2.4, '积便 3 份：染色更高'),
            ('alice-34', {'model': 'alice', 'action': 'idle', 'wear': 'leaf', 'scale': .4}, .75, '子油 alice 3/4')):
        panels.append({'id': pid, 'azimuth': az, 'span': 11 * .4, 'centerY': 7.5 * .4, 'actors': [actor], 'elevation': .5 if pid.startswith('open') else .12})
        extra.append((pid, label))
    pid = 'family-leaf'
    panels.append({'id': pid, 'azimuth': 1.1, 'span': 9, 'centerY': 6, 'actors': [
        {'model': 'reimu', 'action': 'idle', 'at': [-5, 0, 6]},
        {'model': 'reimu', 'action': 'idle', 'scale': .4, 'at': [3, 0, -5], 'wear': 'leaf'},
        {'model': 'marisa', 'action': 'idle', 'scale': .18, 'at': [8, 0, -7], 'wear': 'leaf'}]})
    extra.append((pid, '实际大小：成体亲油、子油(.40)、赤油(.18)'))
    rows.append(extra)
    return panels, rows


def cloth_panels():
    panels, rows = [], []
    for style, label in STYLES:
        row = []
        for model, vname, az in (('reimu', '正面', 0.0), ('reimu', '3/4', .75), ('reimu', '侧面', 1.5708), ('reimu', '背面', 3.14159),
                                 ('marisa', '3/4', .75), ('marisa', '背面', 3.14159), ('alice', '3/4', -.75)):
            pid = f'cloth-{style}-{len(row)}'
            panels.append({'id': pid, 'azimuth': az, 'actors': [{'model': model, 'action': 'idle', 'wear': 'cloth', 'style': style}]})
            row.append((pid, f'{label} {model} {vname}' if not row else f'{model} {vname}'))
        rows.append(row)
    return panels, rows


def sheet(rows, title, file):
    font = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 13)
    cols = max(len(r) for r in rows)
    image = Image.new('RGB', (W * cols, 30 + (H + 4) * len(rows)), BG)
    draw = ImageDraw.Draw(image)
    draw.text((8, 6), title, font=font, fill=(10, 10, 10))
    for r, row in enumerate(rows):
        for c, (pid, label) in enumerate(row):
            # the review rasterizer draws +X on the right when looking from -Z; the game's right-handed view has it on the left
            tile = Image.frombytes('RGBA', (W, H), (WORK / f'{pid}.rgba').read_bytes()).transpose(Image.Transpose.FLIP_LEFT_RIGHT)
            bg = Image.new('RGBA', (W, H), BG + (255,))
            bg.alpha_composite(tile)
            d = ImageDraw.Draw(bg)
            d.text((5, 3), label, font=font, fill=(10, 10, 10, 255))
            d.rectangle([0, 0, W - 1, H - 1], outline=(160, 160, 160, 255))
            image.paste(bg.convert('RGB'), (c * W, 30 + r * (H + 4)))
    image.save(OUT / file)
    print(OUT / file)


def icons():
    font = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 13)
    goods = ASSETS / 'textures/item'
    names = ['swaddle_cloth'] + [f'swaddle_cloth_{s}' for s, _ in STYLES[1:]]
    image = Image.new('RGB', (900, 640), BG)
    draw = ImageDraw.Draw(image)
    draw.text((8, 6), '襁褓布物品图标（1x 与 4x，同一物品 swaddle_cloth 按款式切换）', font=font, fill=(10, 10, 10))
    for n, name in enumerate(names):
        icon = Image.open(goods / f'{name}.png').convert('RGBA')
        x = 10 + n * 108
        tray = Image.new('RGBA', (100, 100), (139, 139, 139, 255))
        tray.alpha_composite(icon.resize((64, 64), Image.NEAREST), (18, 4))
        tray.alpha_composite(icon, (42, 76))
        image.paste(tray.convert('RGB'), (x, 30))
        draw.text((x, 134), STYLES[n][1].split('（')[0], font=font, fill=(10, 10, 10))
    draw.text((8, 160), '身上贴图：单片树叶（干净／积便染色，8x）　布料平铺 2×2（8x）　装饰图集（4x）', font=font, fill=(10, 10, 10))
    leaf = Image.open(ASSETS / 'textures/misc/swaddle_leaf_wear.png').convert('RGBA')
    panel = Image.new('RGBA', (256, 512), (110, 110, 110, 255))
    panel.alpha_composite(leaf.resize((256, 512), Image.NEAREST))
    image.paste(panel.convert('RGB').resize((224, 448), Image.NEAREST), (10, 184))
    for n, fabric in enumerate(FABRICS):
        tile = Image.open(ASSETS / f'textures/misc/swaddle/{fabric}.png').convert('RGBA')
        big = Image.new('RGBA', (32, 32))
        for a in range(2):
            for b in range(2):
                big.paste(tile, (a * 16, b * 16))
        image.paste(big.resize((128, 128), Image.NEAREST).convert('RGB'), (250 + n % 3 * 136, 184 + n // 3 * 136))
    trim = Image.open(ASSETS / 'textures/misc/swaddle_trim.png').convert('RGBA')
    panel = Image.new('RGBA', (256, 256), (110, 110, 110, 255))
    panel.alpha_composite(trim.resize((256, 256), Image.NEAREST))
    image.paste(panel.convert('RGB'), (250, 460 - 70))
    image.save(OUT / 'icons.png')
    print(OUT / 'icons.png')


def main():
    leaf, leaf_rows = leaf_panels()
    cloth, cloth_rows = cloth_panels()
    tasks = {'width': W, 'height': H, 'work': str(WORK), 'textures': textures(), 'panels': leaf + cloth}
    task_file = WORK / 'tasks.json'
    task_file.write_text(json.dumps(tasks), encoding='utf-8')
    subprocess.run(['node', str(Path(__file__).with_name('preview_wear.cjs')), '--tasks', str(task_file)], check=True)
    OUT.mkdir(parents=True, exist_ok=True)
    for old in OUT.glob('*.png'):
        old.unlink()
    sheet(leaf_rows, '树叶襁褓离线预览（CareWearRenderer 逐行移植；姿态取运行动作）：一片叶，叶柄在油菊处，从后兜过底部包到前方排泄口，脸、头发、帽饰外露',
          'leaf.png')
    sheet(cloth_rows, '布襁褓八款离线预览（同一物品 swaddle_cloth，款式存于物品数据）：袋状包被、卷边／荷叶边／滚边、交叠襟与蝴蝶结、背后字母、虎纹、外出胶底与挎包、厚绒绒球',
          'cloth.png')
    icons()


if __name__ == '__main__':
    sys.exit(main())
