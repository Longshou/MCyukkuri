"""Offline preview sheet of the cloth and leaf swaddle worn on the runtime bodies (not shipped).

Builds a texture atlas (entity faces, the knit tile tinted like CareWearRenderer's vertex colours, the leaf sheet and its
stool-stained tints), writes a task file for preview_wear.cjs (the shell-maths port), runs node and assembles
.local/previews/swaddle/on-body.png: reimu, marisa and alice at idle, mid-hop and asleep from the front, three-quarter and
back; a child beside its mother at true scale; the opened wrap as a ground leaf and a soiled wrap.
Environment: MCYUKKURI_PREVIEWS overrides the preview root.
"""
import json, os, subprocess, sys
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parents[4]
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
OUT = Path(os.environ.get('MCYUKKURI_PREVIEWS', ROOT / '.local/previews')) / 'swaddle'
WORK = OUT.parent / '_work/swaddle'
SIZE, W, H = 1024, 300, 270
# CareWearRenderer vertex colours for the knit tile
TINTS = {'cloth': (255, 252, 244), 'fold': (226, 218, 198), 'hem': (242, 191, 207)}
MODELS = ('reimu', 'marisa', 'alice')
FACES = ('neutral', 'sleep')


def atlas():
    image = Image.new('RGBA', (SIZE, SIZE), (0, 0, 0, 0))
    tiles, slot = {}, 0
    for model in MODELS:
        for face in FACES:
            x, y = slot % 4 * 256, slot // 4 * 256
            image.paste(Image.open(ASSETS / f'textures/entity/{model}/{face}.png').convert('RGBA'), (x, y))
            tiles[f'entity:{model}:{face}'] = [x, y, 256, 256, 1, 1, 0]
            slot += 1
    wool = Image.open(ASSETS / 'textures/misc/swaddle_wool_wear.png').convert('RGBA')
    big = Image.new('RGBA', (256, 128))
    for i in range(16):
        for j in range(8):
            big.paste(wool, (i * 16, j * 16))
    x0, y0 = 512, 256
    for n, (name, tint) in enumerate(TINTS.items()):
        px = big.load()
        tinted = Image.new('RGBA', big.size)
        out = tinted.load()
        for x in range(256):
            for y in range(128):
                r, g, b, a = px[x, y]
                out[x, y] = (r * tint[0] // 255, g * tint[1] // 255, b * tint[2] // 255, a)
        image.paste(tinted, (x0, y0 + n * 128))
        tiles[f'wool_{name}'] = [x0, y0 + n * 128, 256, 128, 16, 8, 3]
    leaf = Image.open(ASSETS / 'textures/misc/swaddle_leaf_wear.png').convert('RGBA')
    image.paste(leaf, (800, 256))
    tiles['leaf'] = [800, 256, 32, 64, 1, 1, 0]
    # CareWearRenderer darkens the base of a full wrap by vertex colour (255-70, 255-95, 255-125 at the bottom); the preview tints whole quads
    dark = Image.new('RGBA', leaf.size)
    for x in range(leaf.width):
        for y in range(leaf.height):
            r, g, b, a = leaf.getpixel((x, y))
            dark.putpixel((x, y), (r * 200 // 255, g * 172 // 255, b * 140 // 255, a))
    image.paste(dark, (840, 256))
    tiles['leaf_blotch'] = [840, 256, 32, 64, 1, 1, 0]
    WORK.mkdir(parents=True, exist_ok=True)
    raw = WORK / 'atlas.rgba'
    raw.write_bytes(image.tobytes())
    return {'size': SIZE, 'file': str(raw), 'tiles': tiles}


VIEWS = (('正面', 0.0), ('3/4', 0.75), ('背面', 3.14159))
FRAMES = (('静止', 'idle', 0.0, 'neutral'), ('跳跃', 'hop', 0.4, 'neutral'), ('睡眠', 'sleep', 0.5, 'sleep'))


def panels():
    out, rows = [], []
    for wear, label in (('leaf', '树叶襁褓'), ('cloth', '布襁褓')):
        for model in MODELS:
            row = []
            for fname, action, phase, face in FRAMES:
                for vname, az in VIEWS:
                    pid = f'{wear}-{model}-{action}-{len(row)}'
                    out.append({'id': pid, 'azimuth': az, 'actors': [{'model': model, 'action': action, 'phase': phase, 'face': face, 'wear': wear}]})
                    row.append((pid, f'{label} {model} {fname} {vname}'))
            rows.append(row)
    extra = []
    for wear, label in (('leaf', '树叶'), ('cloth', '布')):
        pid = f'family-{wear}'
        out.append({'id': pid, 'azimuth': .5, 'span': 13, 'centerY': 8, 'actors': [
            {'model': 'reimu', 'action': 'idle', 'at': [-6, 0, 8]},
            {'model': 'reimu', 'action': 'idle', 'scale': .4, 'at': [2, 0, -6], 'wear': wear},
            {'model': 'marisa', 'action': 'hop', 'phase': .4, 'scale': .18, 'at': [8, 0, -8], 'wear': wear}]})
        extra.append((pid, f'{label}襁褓 实际大小：成体亲油、子油(.40)、赤油(.18)'))
    for pid, actor, az, label in (('open', {'model': 'reimu', 'action': 'first_toilet_assist', 'phase': 0.0, 'wear': 'open'}, .75, '解开照护：叶片平铺作承托叶'),
                                  ('soil-1', {'model': 'reimu', 'action': 'idle', 'wear': 'leaf', 'soil': 1}, .35, '积便 1 份：下沿染色'),
                                  ('soil', {'model': 'reimu', 'action': 'idle', 'wear': 'leaf', 'soil': 3}, .35, '积便 3 份：染色更高'),
                                  ('soil-back', {'model': 'marisa', 'action': 'idle', 'wear': 'leaf', 'soil': 3}, 2.6, '积便 3 份（魔理沙 背侧）')):
        out.append({'id': pid, 'azimuth': az, 'actors': [actor]})
        extra.append((pid, label))
    rows.append(extra)
    return out, rows


def main():
    tasks_panels, rows = panels()
    tasks = {'width': W, 'height': H, 'work': str(WORK), 'atlas': atlas(), 'panels': tasks_panels}
    task_file = WORK / 'tasks.json'
    task_file.write_text(json.dumps(tasks), encoding='utf-8')
    subprocess.run(['node', str(Path(__file__).with_name('preview_wear.cjs')), '--tasks', str(task_file)], check=True)
    font = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 13)
    cols = max(len(r) for r in rows)
    sheet = Image.new('RGB', (W * cols, 30 + (H + 4) * len(rows)), (226, 228, 222))
    draw = ImageDraw.Draw(sheet)
    draw.text((8, 6), '身上外观离线预览（CareWearRenderer 逐行移植；姿态取运行动作）：树叶襁褓四片叠叶、布襁褓毛线织纹；脸、头发、帽子外露', font=font, fill=(10, 10, 10))
    for r, row in enumerate(rows):
        for c, (pid, label) in enumerate(row):
            tile = Image.frombytes('RGBA', (W, H), (WORK / f'{pid}.rgba').read_bytes())
            bg = Image.new('RGBA', (W, H), (226, 228, 222, 255))
            bg.alpha_composite(tile)
            d = ImageDraw.Draw(bg)
            d.text((5, 3), label, font=font, fill=(10, 10, 10, 255))
            d.rectangle([0, 0, W - 1, H - 1], outline=(160, 160, 160, 255))
            sheet.paste(bg.convert('RGB'), (c * W, 30 + r * (H + 4)))
    OUT.mkdir(parents=True, exist_ok=True)
    sheet.save(OUT / 'on-body.png')
    print(OUT / 'on-body.png')


if __name__ == '__main__':
    sys.exit(main())
