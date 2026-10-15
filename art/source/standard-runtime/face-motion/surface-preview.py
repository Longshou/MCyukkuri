"""Verify current house surface shading and create reproducible offline previews."""
from pathlib import Path
import datetime
import hashlib
import json
import subprocess
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
TMP = ROOT / '.local/house-face/surface-review'
OUT = HERE / 'previews/current'
TMP.mkdir(parents=True, exist_ok=True)
OUT.mkdir(parents=True, exist_ok=True)
sha = lambda p: hashlib.sha256(p.read_bytes()).hexdigest()
models, hashes = {}, {}
for ident in ('house_reimu', 'house_marisa'):
    file = ROOT / f'src/main/resources/assets/mcyukkuri/yukkuri/{ident}.json'
    model = json.loads(file.read_text(encoding='utf-8'))
    model['clips'].update(model.pop('behavior_clips', {}))
    models[ident], hashes[ident] = model, sha(file)
    texture = ROOT / f'src/main/resources/assets/mcyukkuri/textures/entity/{ident}/neutral.png'
    (TMP / f'{ident}-neutral.rgba').write_bytes(Image.open(texture).convert('RGBA').tobytes())
(TMP / 'input.json').write_text(json.dumps({'models': models}, separators=(',', ':')), encoding='utf-8')
subprocess.run(['node', str(HERE / 'surface-verify.cjs'), str(TMP), str(ROOT)], check=True)
report = json.loads((TMP / 'result.json').read_text(encoding='utf-8'))
font = ImageFont.truetype('C:/Windows/Fonts/msyh.ttc', 22)
ids = [('house_reimu', '家油灵梦'), ('house_marisa', '家油魔理沙')]


def tile(ident, version, angle, frame):
    im = Image.frombytes('RGBA', (460, 400), (TMP / f'{ident}-{version}-{angle}-{frame}.rgba').read_bytes())
    bg = Image.new('RGB', im.size, '#f1ede3')
    bg.paste(im, mask=im.getchannel('A'))
    return bg


files = []
for angle, name in [(0, 'front'), (0.55, 'angle')]:
    canvas = Image.new('RGB', (920, 870), '#f1ede3')
    draw = ImageDraw.Draw(canvas)
    for row, (ident, label) in enumerate(ids):
        for col, version in enumerate(('before', 'after')):
            draw.text((col*460+15, row*435+5), label + (' · 修前' if col == 0 else ' · 连续表面光照'), font=font, fill='#303229')
            canvas.paste(tile(ident, version, angle, 18), (col*460, row*435+35))
    file = OUT / f'surface-{name}.png'
    canvas.save(file)
    files.append(file)
frames = []
for frame in range(24):
    canvas = Image.new('RGB', (920, 435), '#f1ede3')
    draw = ImageDraw.Draw(canvas)
    for col, (ident, label) in enumerate(ids):
        draw.text((col*460+15, 5), label + ' · 面部光照修订', font=font, fill='#303229')
        canvas.paste(tile(ident, 'after', 0, frame), (col*460, 35))
    frames.append(canvas)
file = OUT / 'surface-waddle.gif'
frames[0].save(file, save_all=True, append_images=frames[1:], duration=50, loop=0)
files.append(file)
sources = [HERE / 'surface-preview.py', HERE / 'surface-verify.cjs', ROOT / 'standards/yukkuri-library/runtime/render.js',
           ROOT / 'standards/yukkuri-library/body-standard/previews/current/pose.js', ROOT / 'art/source/standard-runtime/preview.js']
report.update(created_at=datetime.datetime.now(datetime.timezone.utc).isoformat(), runtime_sha256=hashes,
              source_sha256={p.relative_to(ROOT).as_posix(): sha(p) for p in sources},
              previews={p.relative_to(HERE).as_posix(): sha(p) for p in files}, minecraft='pending_current_fixed_build',
              grouping='round(rest position * 100000), round(authored normal * 1000); posed triangle area weighted',
              user_confirmation='pending')
(HERE / 'reports/current').mkdir(parents=True, exist_ok=True)
(HERE / 'reports/current/surface-lighting.json').write_text(json.dumps(report, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
