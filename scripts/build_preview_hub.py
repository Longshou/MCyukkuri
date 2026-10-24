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
import sys
from datetime import datetime, timedelta, timezone

import build_review_catalog
import build_preview_library

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
    for ident, name, group in build_review_catalog.current_roles():
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


def render_overview():
    from PIL import Image, ImageDraw, ImageOps
    sheet = Image.new('RGB', (1600, 1160), '#f3f0e8')
    draw = ImageDraw.Draw(sheet)
    draw.text((54, 30), '看得到的动作与场景', font=font(44), fill='#253c35')
    draw.text((56, 98), '从出生到离家 · 当前运行资源离线演示', font=font(22), fill='#66796c')
    cards = [('亲油微微前倾递喂', 'childhood-life/childhood-land-feed.png', 0),
             ('首次排便由亲油照料', 'childhood-life/childhood-first-toilet.gif', 12),
             ('先爬行，再练习短跳', 'childhood-life/childhood-locomotion.gif', 15),
             ('长大后铺个人小巢', 'actions/family-small-build.gif', 10),
             ('亚成油另筑新居', 'actions/build-nest.gif', 10),
             ('在帽沿向水中排泄', 'childhood-life/childhood-boat-toilet.png', 0)]
    for i, (title, source, frame) in enumerate(cards):
        x, y = 48 + i % 3 * 508, 158 + i // 3 * 462
        draw.rounded_rectangle((x, y, x+488, y+438), 18, fill='#fffef9')
        path = OUT / source
        if path.exists():
            with Image.open(path) as gif:
                gif.seek(min(frame, gif.n_frames-1))
                still = ImageOps.contain(gif.convert('RGBA'), (468, 344))
                sheet.paste(still, (x+(488-still.width)//2, y+30+(344-still.height)//2), still)
        else:
            draw.text((x+30, y+150), '分镜尚未生成', font=font(25), fill='#66796c')
        draw.text((x+24, y+382), title, font=font(27), fill='#253c35')
    draw.text((56, 1110), '页面打开即播放；选择场景切换动作。继任、库存等无独立动画的变化用状态图说明。', font=font(21), fill='#52685d')
    sheet.save(OUT / 'overview.png')


def prepare_chapters(manifest, actions):
    """Publish only usable local media; gallery-only topics become selectable stills."""
    chapters = []
    for raw in manifest['chapters']:
        chapter = dict(raw)
        chapter.setdefault('label', chapter['title'])
        chapter.setdefault('tag', '当前素材 · 离线预览')
        if chapter.get('source') and not (ROOT / chapter['source']).is_file():
            raise FileNotFoundError(chapter['source'])
        if chapter.get('demoPage') and not (OUT / chapter['demoPage']).is_file():
            print(f"Omitted unavailable demo: {chapter.pop('demoPage')}")
        chapter['gallery'] = [item for item in chapter.get('gallery', []) if (OUT / item['src']).is_file()]
        steps = []
        for raw_step in chapter.get('steps', []):
            step = dict(raw_step)
            step.setdefault('body', step.pop('caption', ''))
            if step.get('demo'):
                demo = actions[step['demo']]
                step.update(image=f"actions/{demo['id']}.png", animation=f"actions/{demo['id']}.gif", model=demo['model'], action=demo['action'])
                if step.get('still'):
                    step.pop('animation')
                    step.pop('action')
            if step.get('kind') == 'playback' and not chapter.get('demoPage'):
                continue
            if step.get('image') and not (OUT / step['image']).is_file():
                print(f"Omitted unavailable still: {step['image']}")
                continue
            if step.get('animation') and not (OUT / step['animation']).is_file():
                step.pop('animation')
            steps.append(step)
        if not steps and chapter['gallery']:
            steps = [dict(title=item['label'], body='当前素材离线预览。', image=item['src'])
                     for item in chapter['gallery']]
            chapter['gallery'] = []
        if not steps:
            print(f"Omitted empty topic: {chapter['id']}")
            continue
        chapter['steps'] = steps
        chapters.append(chapter)
    if not chapters:
        raise ValueError('No usable preview topics; generate topic media first.')
    manifest['chapters'] = chapters


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--render', action='store_true')
    parser.add_argument('--node', default='node')
    args = parser.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    build_review_catalog.main()
    manifest = json.loads((ROOT / 'preview/hub/recent.json').read_text(encoding='utf-8'))
    manifest['sourceRevision'] = subprocess.check_output(['git', 'rev-parse', 'HEAD'], cwd=ROOT, text=True).strip()
    manifest['workingChanges'] = bool(subprocess.check_output(['git', 'status', '--porcelain'], cwd=ROOT, text=True).strip())
    manifest['generatedAt'] = datetime.now(timezone(timedelta(hours=8))).isoformat(timespec='seconds')
    manifest['assetFingerprint'] = hashlib.sha256((ROOT / 'src/main/resources/assets/mcyukkuri/yukkuri_presentation/motion_corrections.json').read_bytes()).hexdigest()
    digest = hashlib.sha256((OUT / 'models/catalog.js').read_bytes())
    for relative in ('scripts/build_preview_hub.py', 'scripts/render_preview_models.cjs',
                     'preview/review/pose.js', 'standards/yukkuri-library/runtime/render.js'):
        digest.update((ROOT / relative).read_bytes())
    for ident, _, _ in build_review_catalog.current_roles():
        digest.update((build_review_catalog.ASSETS / 'textures/entity' / ident / 'neutral.png').read_bytes())
    fingerprint = digest.hexdigest()
    stamp = OUT / '_work/model-preview.sha256'
    missing_thumb = any(not (OUT / 'models/thumbs' / (ident + '.png')).exists()
                        for ident, _, _ in build_review_catalog.current_roles())
    if args.render or missing_thumb or not (OUT / 'overview.png').exists() or not stamp.exists() or stamp.read_text() != fingerprint:
        render_models(args.node)
        stamp.write_text(fingerprint)
    command = [sys.executable, str(ROOT / 'scripts/build_preview_actions.py'), '--node', args.node]
    if args.render:
        command.append('--force')
    subprocess.run(command, check=True, cwd=ROOT)
    childhood = [sys.executable, str(ROOT / 'scripts/build_preview_actions.py'), '--node', args.node,
                 '--manifest', str(ROOT / 'preview/hub/childhood-life-actions.json'),
                 '--out', str(OUT / 'childhood-life')]
    if args.render:
        childhood.append('--force')
    subprocess.run(childhood, check=True, cwd=ROOT)
    subprocess.run([sys.executable, str(ROOT / 'scripts/build_preview_props.py')], check=True, cwd=ROOT)
    actions = {item['id']: item for item in json.loads((ROOT / 'preview/hub/actions.json').read_text(encoding='utf-8'))['clips']}
    catalog = json.loads((OUT / 'models/catalog.js').read_text(encoding='utf-8').removeprefix('window.REVIEW_CATALOG=').rstrip(';\n'))
    manifest['audioSamples'] = {
        'hello': next(item for item in catalog['audio'] if item['species']=='reimu' and item['age']=='adult' and 'hello' in item['events']),
        'song': next(item for item in catalog['audio'] if item['id']=='ecology.song.reimu')}
    manifest['subtitleSamples'] = [next(item for item in catalog['subtitles'] if item['id']==ident)
                                   for ident in ('remilia.adult.o_hello', 'flandre.adult.o_hello')]
    prepare_chapters(manifest, actions)
    build_preview_library.build(manifest, catalog, OUT)
    render_overview()
    manifest['availableMedia'] = sorted({
        media for chapter in manifest['chapters']
        for media in [*(step[key] for step in chapter['steps'] for key in ('image', 'animation') if step.get(key)),
                      *(item['src'] for item in chapter['gallery'])]})
    (OUT / 'recent.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    for name in ('style.css', 'app.js', 'index.html'):
        shutil.copy2(ROOT / 'preview/hub' / name, OUT / name)
    print(OUT / 'index.html')


if __name__ == '__main__':
    main()
