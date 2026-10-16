"""Build the local preview hub and current model views; no browser, game or Java compilation.

All generated pages and media live in .local/previews. Authored templates remain in preview/.
Run with --render to regenerate current model thumbnails and the overview image.
"""
from pathlib import Path
import argparse
import hashlib
import json
import shutil
import subprocess
from datetime import datetime, timedelta, timezone

import build_review_catalog

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / '.local/previews'


def font(size):
    from PIL import ImageFont
    for name in ('/System/Library/Fonts/STHeiti Medium.ttc', '/Library/Fonts/Arial Unicode.ttf',
                 'C:/Windows/Fonts/msyh.ttc', '/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc'):
        if Path(name).exists():
            return ImageFont.truetype(name, size)
    raise RuntimeError('Install a Chinese font to render the overview image.')


def render_models(node):
    from PIL import Image, ImageDraw
    work = OUT / '_work'
    work.mkdir(parents=True, exist_ok=True)
    tasks = []
    for ident, name, group in build_review_catalog.ROLES:
        texture = ROOT / 'src/main/resources/assets/mcyukkuri/textures/entity' / ident / 'neutral.png'
        (work / f'{ident}.texture').write_bytes(Image.open(texture).convert('RGBA').tobytes())
        tasks.append(dict(id=ident, model=ident, action='idle', time=0, face='neutral',
                          texture=f'{ident}.texture', width=400, height=360))
    (work / 'render-tasks.json').write_text(json.dumps(tasks), encoding='utf-8')
    subprocess.run([node, str(ROOT / 'scripts/render_preview_models.cjs')], check=True, cwd=ROOT)
    thumbs = OUT / 'models/thumbs'
    thumbs.mkdir(parents=True, exist_ok=True)
    for task in tasks:
        Image.frombytes('RGBA', (task['width'], task['height']), (work / (task['id'] + '.rgba')).read_bytes()).save(thumbs / (task['id'] + '.png'))
    # A portable, directly shareable cover. The detailed hub contains each motion and behavior sequence.
    sheet = Image.new('RGB', (1600, 1080), '#f3f0e8')
    draw = ImageDraw.Draw(sheet)
    draw.text((64, 44), '油库里的新一天', font=font(48), fill='#253c35')
    draw.text((64, 112), '10 月 15 日 20:00 起的更新 · 当前资源离线预览', font=font(23), fill='#66796c')
    panels = [('水上的一家人', '帽船育幼、夜宿与初航', 'marisa', '#dcebed'),
              ('每个地方都有自己的季节', '群系季节、秋储与冬歇', 'reimu', '#e7ead7'),
              ('长大，带着材料去建窝', '自然离巢、探亲与空茎', 'chen', '#eee1cc'),
              ('一个会记事的聚落', '见证继任、派工与迁徙', 'patchouli', '#e6e0ed')]
    for i, (title, sub, ident, color) in enumerate(panels):
        x, y = 54 + i % 2 * 758, 184 + i // 2 * 408
        draw.rounded_rectangle((x, y, x+734, y+384), 24, fill=color)
        thumb = Image.open(thumbs / f'{ident}.png').convert('RGBA')
        thumb.thumbnail((350, 314))
        sheet.paste(thumb, (x+365, y+52), thumb)
        draw.text((x+28, y+34), f'0{i+1}', font=font(24), fill='#718577')
        # Fixed line breaks keep the exported cover legible without a browser layout pass.
        lines = {'水上的一家人':['水上的一家人'], '每个地方都有自己的季节':['每个地方都有', '自己的季节'],
                 '长大，带着材料去建窝':['长大，带着材料', '去建窝'], '一个会记事的聚落':['一个会记事的', '聚落']}[title]
        for j, line in enumerate(lines):
            draw.text((x+28, y+100+j*46), line, font=font(31), fill='#253c35')
        draw.text((x+28, y+290), sub, font=font(21), fill='#52685d')
    draw.text((64, 1022), '还有：温度避险 · 壳上徽章 · 日文与捕食种字幕     详细动作与流程见集中预览', font=font(22), fill='#52685d')
    sheet.save(OUT / 'overview.png')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--render', action='store_true')
    parser.add_argument('--node', default='node')
    args = parser.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    build_review_catalog.main()
    for name in ('index.html', 'style.css', 'app.js'):
        shutil.copy2(ROOT / 'preview/hub' / name, OUT / name)
    manifest = json.loads((ROOT / 'preview/hub/recent.json').read_text(encoding='utf-8'))
    manifest['generatedAt'] = datetime.now(timezone(timedelta(hours=8))).isoformat(timespec='seconds')
    manifest['assetFingerprint'] = hashlib.sha256((ROOT / 'src/main/resources/assets/mcyukkuri/yukkuri_presentation/motion_corrections.json').read_bytes()).hexdigest()
    digest = hashlib.sha256((OUT / 'models/catalog.js').read_bytes())
    for relative in ('scripts/build_preview_hub.py', 'scripts/render_preview_models.cjs',
                     'preview/review/pose.js', 'standards/yukkuri-library/runtime/render.js'):
        digest.update((ROOT / relative).read_bytes())
    for ident, _, _ in build_review_catalog.ROLES:
        digest.update((build_review_catalog.ASSETS / 'textures/entity' / ident / 'neutral.png').read_bytes())
    fingerprint = digest.hexdigest()
    stamp = OUT / '_work/model-preview.sha256'
    missing_thumb = any(not (OUT / 'models/thumbs' / (ident + '.png')).exists()
                        for ident, _, _ in build_review_catalog.ROLES)
    if args.render or missing_thumb or not (OUT / 'overview.png').exists() or not stamp.exists() or stamp.read_text() != fingerprint:
        render_models(args.node)
        stamp.write_text(fingerprint)
    for chapter in manifest['chapters']:
        if not (OUT / chapter['image']).is_file():
            chapter['image'] = f"models/thumbs/{chapter['model']}.png"
            chapter['mediaPending'] = True
        if chapter.get('animation') and not (OUT / chapter['animation']).is_file():
            del chapter['animation']
        chapter['gallery'] = [item for item in chapter.get('gallery', []) if (OUT / item['src']).is_file()]
    manifest['availableMedia'] = sorted(str(p.relative_to(OUT)) for p in OUT.rglob('*')
                                        if p.suffix in ('.png', '.gif', '.webm', '.mp4') and not p.relative_to(OUT).parts[0].startswith('_'))
    (OUT / 'recent.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(OUT / 'index.html')


if __name__ == '__main__':
    main()
