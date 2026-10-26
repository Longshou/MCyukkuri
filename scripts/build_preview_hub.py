"""Build the local preview hub and current model views; no browser, game or Java compilation.

All generated pages and media live in .local/previews. Authored templates remain in preview/.
Default refresh: catalog, model thumbnails, item views and continuous scene GIFs.
Use --render to regenerate all specialized software-rendered sheets; never compiles Java.
"""
from pathlib import Path
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
from datetime import datetime, timedelta, timezone

import build_review_catalog
import build_preview_library

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / '.local/previews'
PET_GOODS = ('vanilla', 'bowls', 'fills', 'nests', 'large', 'toilets')
PET_GOODS_MEDIA = ('vanilla', 'bowls', 'fills', 'nests', 'large_nests', 'toilets')


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


def prepare_chapters(manifest, current_media):
    """Only publish media rebuilt (or fingerprint-checked) by this invocation.

    Existence and file dates are insufficient evidence for old hand-built
    sheets and embedded demo pages. New producers must join main explicitly.
    """
    for chapter in manifest['chapters']:
        if chapter.get('demoPage'):
            raise ValueError(f"Unmanaged embedded preview: {chapter['id']}")
        if not (ROOT/chapter['source']).is_file():
            raise FileNotFoundError(chapter['source'])
        steps = list(chapter.get('steps', []))
        steps.extend(dict(title=item['label'], body=chapter['summary'], image=item['src'])
                     for item in chapter.get('gallery', []))
        for step in steps:
            for key in ('image', 'animation'):
                if media := step.get(key):
                    if media not in current_media or not (OUT/media).is_file():
                        raise ValueError(f'Preview not refreshed by this build: {media}')
            if playback := step.get('playback'):
                media = playback['image']
                if media not in current_media or not (OUT/media).is_file():
                    raise ValueError(f'Preview frames not refreshed by this build: {media}')
        if not steps:
            raise ValueError(f"Empty preview topic: {chapter['id']}")
        chapter.update(steps=steps, gallery=[])


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
    if args.render or missing_thumb or not stamp.exists() or stamp.read_text() != fingerprint:
        render_models(args.node)
        stamp.write_text(fingerprint)
    current_media = set()
    scene_stamp = OUT / '_work/scene-source.sha256'
    scene_digest = hashlib.sha256()
    for base in (ROOT/'src/main/resources/assets/mcyukkuri', ROOT/'src/main/java', ROOT/'standards/yukkuri-library', ROOT/'scripts', ROOT/'preview/hub'):
        for source in sorted(base.rglob('*')):
            if source.is_file() and source.suffix in ('.json', '.png', '.py', '.js', '.cjs', '.java'):
                scene_digest.update(str(source.relative_to(ROOT)).encode())
                scene_digest.update(source.read_bytes())
    scene_fingerprint = scene_digest.hexdigest()
    if args.render:
        subprocess.run([sys.executable, str(ROOT / 'scripts/build_preview_props.py')], check=True, cwd=ROOT)
        from build_preview_props import GROUPS
        current_media = {f'care-current/{name}.png' for name in GROUPS}
        # Placed pet-goods style sheets (offline, beside vanilla blocks); rebuilt here from the current runtime meshes.
        subprocess.run([sys.executable, str(ROOT / 'standards/yukkuri-library/drafts/care-props/preview_vanilla_care.py'), *PET_GOODS],
                       check=True, cwd=ROOT, env={**os.environ, 'MCYUKKURI_PREVIEWS': str(OUT)})
        current_media |= {f'pet-goods/{name}.png' for name in PET_GOODS_MEDIA}
        # worn swaddles: drafts/care-props/preview_wear.py redraws its four sheets from the current runtime textures on every build
        wear = ROOT / 'standards/yukkuri-library/drafts/care-props/preview_wear.py'
        subprocess.run([sys.executable, str(wear)], check=True, cwd=ROOT, env={**os.environ, 'MCYUKKURI_PREVIEWS': str(OUT)})
        current_media |= {f'swaddle/{name}.png' for name in ('leaf', 'cloth', 'fit', 'icons')}
        # wild nests and the forage pile: drafts/care-props/preview_wild_nests.py redraws the sheets from the runtime meshes
        nests = ROOT / 'standards/yukkuri-library/drafts/care-props/preview_wild_nests.py'
        subprocess.run([sys.executable, str(nests)], check=True, cwd=ROOT, env={**os.environ, 'MCYUKKURI_PREVIEWS': str(OUT)})
        current_media |= {f'wild-nests/{name}.png' for name in ('nests', 'stages', 'forage')}
        # aquatic Marisa tool clips, hull dressing and the derived faces: lore/aquatic_tools_preview.py reads the runtime clips and props
        aquatic = ROOT / 'standards/yukkuri-library/motions/variants/lore/aquatic_tools_preview.py'
        subprocess.run([sys.executable, str(aquatic)], check=True, cwd=ROOT, env={**os.environ, 'MCYUKKURI_PREVIEWS': str(OUT)})
        current_media |= {f'aquatic-tools/{name}.png' for name in ('paddle', 'cradle', 'boat-life', 'bite', 'capsize', 'self-rescue', 'hull', 'faces')}
        # hat boat deck: interactions/marisa-hat-vessel/preview_deck.py redraws the seat table from the exported runtime data
        deck = ROOT / 'standards/yukkuri-library/interactions/marisa-hat-vessel/preview_deck.py'
        subprocess.run([sys.executable, str(deck)], check=True, cwd=ROOT, env={**os.environ, 'MCYUKKURI_PREVIEWS': str(OUT)})
        current_media |= {'hat-deck/hat-deck.png'}
        # the hat boat as a home seen from the bank (side-well larder, crew): lore/aquatic_home_preview.py, runtime data only
        home = ROOT / 'standards/yukkuri-library/motions/variants/lore/aquatic_home_preview.py'
        subprocess.run([sys.executable, str(home)], check=True, cwd=ROOT, env={**os.environ, 'MCYUKKURI_PREVIEWS': str(OUT)})
        current_media |= {'aquatic-home/shore.png'}
        # nest fit: every growth stage resting where the collision puts it, cross-sections and the placed-block size lineup
        fit = ROOT / 'standards/yukkuri-library/drafts/care-props/preview_fit.py'
        subprocess.run([sys.executable, str(fit)], check=True, cwd=ROOT, env={**os.environ, 'MCYUKKURI_PREVIEWS': str(OUT)})
        current_media |= {'pet-goods/fit.png', 'pet-goods/lineup.png', 'wild-nests/fit.png'}
        # Site previews may be reused only after their authoring inputs were checked.
        # The preview room never invokes the Java-based site renderer.
        import settlement_site_preview as sites
        site_stamp = OUT / 'wild-settlements/source.sha256'
        if site_stamp.is_file() and site_stamp.read_text(encoding='utf-8') == sites.source_digest():
            current_media |= {f'wild-settlements/{name}.png' for name in sites.HUB_MEDIA}
        scene_stamp.parent.mkdir(parents=True, exist_ok=True)
        scene_stamp.write_text(scene_fingerprint, encoding='utf-8')
        (OUT/'_work/scene-media.json').write_text(json.dumps(sorted(current_media)), encoding='utf-8')
    elif scene_stamp.is_file() and scene_stamp.read_text() == scene_fingerprint:
        current_media = set(json.loads((OUT/'_work/scene-media.json').read_text()))
    subprocess.run([sys.executable, str(ROOT/'art/props/family-life/preview_stem.py'),
                    '--node', args.node, '--output', str(OUT/'stem-hanging')], check=True, cwd=ROOT)
    current_media.add('stem-hanging/current.png')
    # Continuous previews share one authored timeline with GIF and seekable frames.
    scene_source = ROOT/'preview/hub/scenes.json'
    subprocess.run([sys.executable, str(ROOT/'scripts/build_preview_actions.py'),
                    '--manifest', str(scene_source), '--out', str(OUT/'scenes'),
                    '--frames', '72', '--node', args.node], check=True, cwd=ROOT)
    authored = {c['id']: c for c in json.loads(scene_source.read_text())['clips']}
    clips = json.loads((OUT/'scenes/manifest.json').read_text())['clips']
    manifest['chapters'] = [c for c in manifest['chapters']
        if all(media in current_media and (OUT/media).is_file()
               for media in [*(s[k] for s in c.get('steps', []) for k in ('image','animation') if s.get(k)),
                             *(g['src'] for g in c.get('gallery', []))])]
    for clip in clips:
        source = authored[clip['id']]
        image = 'scenes/'+clip['id']+'.png'
        animation = 'scenes/'+clip['id']+'.gif'
        playback = {**clip['playback'], 'image': 'scenes/'+clip['playback']['image']}
        manifest['chapters'].append(dict(id=clip['id'], title=source['title'], label=source['title'],
            category=source['category'], summary=source['summary'],
            visual='连续场景编排', source=source['source'], points=[], gallery=[],
            steps=[dict(title=source['title'], body='当前模型与动作的连续编排；游戏 AI、碰撞和行为成败请在试玩场确认。',
                        image=image, animation=animation, playback=playback, beats=source.get('beats', []))]))
        current_media |= {image, animation, playback['image']}
    catalog = json.loads((OUT / 'models/catalog.js').read_text(encoding='utf-8').removeprefix('window.REVIEW_CATALOG=').rstrip(';\n'))
    manifest['audioSamples'] = {
        'hello': next(item for item in catalog['audio'] if item['species']=='reimu' and item['age']=='adult' and 'hello' in item['events']),
        'song': next(item for item in catalog['audio'] if item['id']=='ecology.song.reimu')}
    manifest['subtitleSamples'] = [next(item for item in catalog['subtitles'] if item['id']==ident)
                                   for ident in ('remilia.adult.o_hello', 'flandre.adult.o_hello')]
    prepare_chapters(manifest, current_media)
    build_preview_library.build(manifest, catalog, OUT)
    manifest['availableMedia'] = sorted({
        media for chapter in manifest['chapters']
        for media in [*(step[key] for step in chapter['steps'] for key in ('image', 'animation') if step.get(key)),
                      *(item['src'] for item in chapter['gallery']),
                      *(step['playback']['image'] for step in chapter['steps'] if step.get('playback'))]})
    (OUT / 'recent.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    for name in ('style.css', 'app.js', 'router.js', 'scene-player.js', 'index.html'):
        shutil.copy2(ROOT / 'preview/hub' / name, OUT / name)
    print(OUT / 'index.html')


if __name__ == '__main__':
    main()
