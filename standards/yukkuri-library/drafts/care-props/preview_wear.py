"""Offline preview sheets of the leaf and cloth swaddle worn on the runtime bodies, and of the swaddle icons (not shipped).

Writes the raw textures (entity faces, the leaf sheet, the cloth fabric tiles and trim sheet) and a task file for preview_wear.cjs (the
CareWearRenderer port, which bakes vertex colours into tinted atlas copies), runs node and assembles in .local/previews/swaddle/:
leaf.png (baby and young child at their true scales, front, three-quarter, side and back, mid-hop and asleep, the opened leaf under the
child, one and three stool loads, a family at true sizes), cloth.png (the eight cloth styles on reimu from four sides and on marisa and
alice), fit.png (close-ups of the leaf and cloth under the hair of the sidelock, braid and long-haired species, front, side and back)
and icons.png (the eight swaddle cloth icons at 1x and 4x, the leaf sheet, fabric tiles and trim sheet). Panels are mirrored
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
MODELS = ('reimu', 'marisa', 'alice', 'patchouli', 'flandre', 'chen')
FACES = ('neutral', 'sleep')
FABRICS = ('knit', 'cotton', 'fleece', 'tiger', 'gingham', 'floral')
STYLES = (('knit', '奶油针织（项目设计）'), ('frill', '荷叶边蝴蝶结（EH30）'), ('letter', '首字母（EH34 第7–8页）'), ('tiger', '虎纹（EH34 第12–14页）'),
          ('outing', '外出款（EH31 第2页）'), ('fleece', '防寒厚绒（N91 用途）'), ('gingham', '格纹（项目设计）'), ('floral', '碎花（项目设计）'))
BG = (226, 228, 222)

# --neglect: one Reimu baby (赤油 .18) at the leaf stages of a neglected wrap and its retention remains, the placeholder look beside the
# current one, from the front three-quarter, the side and the back (LeafSwaddle.wearBits: stage, loads, swelling; RemainsAssets forms).
NEGLECT_SCALE = .18
NEGLECT_VIEWS = (('3/4', .75), ('侧面', 1.5708), ('背面', 3.14159))
NEGLECT_STATES = (
    ('0 新叶', {'stage': 0, 'soil': 0, 'swell': 0, 'face': 'happy'}, None),
    ('1 积便', {'stage': 1, 'soil': 1, 'swell': 0, 'face': 'cry'}, None),
    ('2 干黄（肿胀 .3）', {'stage': 2, 'soil': 2, 'swell': .3, 'face': 'squeezed_frown'}, None),
    ('3 硬褐（肿胀满）', {'stage': 3, 'soil': 3, 'swell': 1, 'face': 'squeezed_cry'}, None),
    ('便秘遗体', {'skin': 'remains:reimu:retained', 'stage': 3, 'soil': 3, 'swell': 1}, 'retained'),
)
NEGLECT_FACES = ('happy', 'cry', 'squeezed_frown', 'squeezed_cry')
NEGLECT_REMAINS = {'sick': ASSETS / 'textures/entity/reimu/remains_sick.png',
                   'retained': ROOT / 'standards/yukkuri-library/drafts/remains/assembled/reimu/remains_retained.png'}


def textures():
    """Raw RGBA of every texture the port samples, keyed by the names it uses."""
    WORK.mkdir(parents=True, exist_ok=True)
    out = {}

    def raw(name, path):
        image = (path if isinstance(path, Image.Image) else Image.open(path)).convert('RGBA')
        file = WORK / (name.replace(':', '_') + '.rgba')
        file.write_bytes(image.tobytes())
        out[name] = {'file': str(file), 'w': image.width, 'h': image.height}
    for model in MODELS:
        for face in FACES:
            raw(f'entity:{model}:{face}', ASSETS / f'textures/entity/{model}/{face}.png')
    raw('leaf', ASSETS / 'textures/misc/swaddle_leaf_wear.png')
    # the former 32x64 sheet (fresh leaf and its stained copy) is the first stage of the current one, for the placeholder look
    raw('leaf_old', Image.open(ASSETS / 'textures/misc/swaddle_leaf_wear.png').crop((0, 0, 32, 64)))
    for face in NEGLECT_FACES:
        raw(f'entity:reimu:{face}', ASSETS / f'textures/entity/reimu/{face}.png')
    for form, path in NEGLECT_REMAINS.items():
        raw(f'remains:reimu:{form}', path)
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
    panels.append({'id': pid, 'azimuth': .25, 'span': 11, 'centerY': 7, 'actors': [
        {'model': 'reimu', 'action': 'idle', 'at': [-10, 0, 0]},
        {'model': 'reimu', 'action': 'idle', 'scale': .4, 'at': [3, 0, 0], 'wear': 'leaf'},
        {'model': 'marisa', 'action': 'idle', 'scale': .18, 'at': [9, 0, 0], 'wear': 'leaf'}]})
    extra.append((pid, '实际大小：成体亲油、子油(.40)、赤油(.18)'))
    rows.append(extra)
    sizes = []
    for pid, az, label in (('true-front', .35, '同一镜头实际大小 正面偏左：子油与赤油'), ('true-side', 1.9, '同一镜头实际大小 侧后：子油与赤油')):
        panels.append({'id': pid, 'azimuth': az, 'span': 5.5, 'centerY': 3, 'actors': [
            {'model': 'reimu', 'action': 'idle', 'scale': .4, 'at': [-3.5, 0, 0], 'wear': 'leaf'},
            {'model': 'marisa', 'action': 'idle', 'scale': .18, 'at': [3.5, 0, -.5], 'wear': 'leaf'}]})
        sizes.append((pid, label))
    rows.append(sizes)
    return panels, rows


def neglect_panels():
    recipe = json.loads((ROOT / 'standards/yukkuri-library/drafts/remains/recipe.json').read_text(encoding='utf-8'))['forms']
    panels, rows = [], []
    for label, state, form in NEGLECT_STATES:
        row = []
        for old in (True, False):
            for vname, az in NEGLECT_VIEWS:
                actor = {'model': 'reimu', 'action': 'idle', 'scale': NEGLECT_SCALE, 'wear': 'leaf', **state}
                if form:
                    # before: the sick remains form without the leaf; now: the retention form keeps the swollen body in its hard leaf
                    look = recipe['sick' if old else form]
                    actor.update(face=look['face'], squash=look['scale'], roll=look.get('roll', 0), skin=f"remains:reimu:{'sick' if old else form}")
                    if old:
                        actor.pop('wear')
                elif old:
                    actor['placeholder'] = True
                pid = f'neglect-{len(rows)}-{len(row)}'
                panels.append({'id': pid, 'azimuth': az, 'span': 11 * NEGLECT_SCALE, 'centerY': 6.5 * NEGLECT_SCALE, 'actors': [actor]})
                row.append((pid, f"{'旧' if old else '新'} {label} {vname}"))
        rows.append(row)
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


FIT = (('reimu', '子油', .40, '灵梦 鬓发'), ('reimu', '赤油', .18, '灵梦 鬓发'), ('marisa', '子油', .40, '魔理沙 单辫'),
       ('marisa', '赤油', .18, '魔理沙 单辫'), ('patchouli', '子油', .40, '帕秋莉 长鬓发'), ('flandre', '子油', .40, '芙兰 侧发'),
       ('alice', '子油', .40, '爱丽丝 后发'), ('chen', '赤油', .18, '橙 后发与尾'))
FIT_VIEWS = (('leaf', None, '叶 正面', 0.0), ('leaf', None, '叶 侧面', 1.5708), ('leaf', None, '叶 背面', 3.14159),
             ('cloth', 'knit', '针织 正面', 0.0), ('cloth', 'knit', '针织 3/4', .75), ('cloth', 'knit', '针织 侧面', -1.5708),
             ('cloth', 'knit', '针织 背面', 3.14159), ('cloth', 'fleece', '厚绒 3/4', -.75), ('cloth', 'frill', '荷叶边 3/4', .75))


def fit_panels():
    panels, rows = [], []
    for model, stage, scale, label in FIT:
        row = []
        for wear, style, vname, az in FIT_VIEWS:
            pid = f'fit-{model}-{stage}-{len(row)}'
            actor = {'model': model, 'action': 'idle', 'scale': scale, 'wear': wear}
            if style:
                actor['style'] = style
            panels.append({'id': pid, 'azimuth': az, 'span': 11 * scale, 'centerY': 7 * scale, 'actors': [actor]})
            row.append((pid, f'{stage}({scale}) {label} {vname}' if not row else vname))
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
    draw.text((8, 160), '身上贴图：单片树叶（四个失护阶段，各为干净／积便染色，2x）　布料平铺 2×2（8x）　装饰图集（4x）', font=font, fill=(10, 10, 10))
    leaf = Image.open(ASSETS / 'textures/misc/swaddle_leaf_wear.png').convert('RGBA')
    panel = Image.new('RGBA', (256, 512), (110, 110, 110, 255))
    panel.alpha_composite(leaf.resize((64, 512), Image.NEAREST), (96, 0))
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
    if '--neglect' in sys.argv:
        panels, rows = neglect_panels()
        tasks = {'width': W, 'height': H, 'work': str(WORK), 'textures': textures(), 'panels': panels}
        task_file = WORK / 'tasks.json'
        task_file.write_text(json.dumps(tasks), encoding='utf-8')
        subprocess.run(['node', str(Path(__file__).with_name('preview_wear.cjs')), '--tasks', str(task_file)], check=True)
        target = OUT.parent / 'swaddle-neglect-art'
        target.mkdir(parents=True, exist_ok=True)
        sheet(rows, '树叶襁褓失护：灵梦赤油 0–3 段与便秘遗体；左三列旧占位（顶点染色、叶缘外扩、病死遗体），右三列新（叶片分段贴图、身体潴留肿胀、苍白遗体保留肿胀与硬叶）',
              target / 'neglect.png')
        return
    leaf, leaf_rows = leaf_panels()
    cloth, cloth_rows = cloth_panels()
    fit, fit_rows = fit_panels()
    tasks = {'width': W, 'height': H, 'work': str(WORK), 'textures': textures(), 'panels': leaf + cloth + fit}
    task_file = WORK / 'tasks.json'
    task_file.write_text(json.dumps(tasks), encoding='utf-8')
    subprocess.run(['node', str(Path(__file__).with_name('preview_wear.cjs')), '--tasks', str(task_file)], check=True)
    OUT.mkdir(parents=True, exist_ok=True)
    for old in OUT.glob('*.png'):
        old.unlink()
    sheet(leaf_rows, '树叶襁褓离线预览（CareWearRenderer 逐行移植；姿态取运行动作）：一片叶，叶柄在油菊处，从后兜过底部包到前方排泄口，脸、头发、帽饰外露',
          'leaf.png')
    sheet(cloth_rows, '布襁褓八款离线预览（同一物品 swaddle_cloth，款式存于物品数据）：贴皮肤的包被、头发在外，翻折边／荷叶边、交叠襟与蝴蝶结、背后字母、虎纹、外出胶底与挎包、厚绒绒球',
          'cloth.png')
    sheet(fit_rows, '襁褓贴皮肤、头发在外（近景）：鬓发、单辫、长鬓发、侧发与后发都盖在叶片和布外，不被吞进去也不从布里穿出；子油 .40、赤油 .18',
          'fit.png')
    icons()


if __name__ == '__main__':
    sys.exit(main())
