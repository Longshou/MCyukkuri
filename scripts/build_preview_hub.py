"""Build the live preview home and catalog; --render also refreshes specialist sheets."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys
from datetime import datetime

import build_review_catalog
import build_preview_library
from build_player_entries import write_entries

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / '.local/previews'


def font(size):
    from preview import font as preview_font
    return preview_font(size)


def register_topics(out):
    """Publish local pages independently of the specialized renderer freshness gates.

    These entries describe saved local artifacts, not verified current runtime media.
    A full hub build and the lightweight preview command use the same registration.
    """
    from preview import page, media
    out.mkdir(parents=True, exist_ok=True)
    library_path, recent_path = out/'library.json', out/'recent.json'
    library = json.loads(library_path.read_text(encoding='utf-8')) if library_path.is_file() else dict(
        categories=json.loads((ROOT/'preview/hub/categories.json').read_text(encoding='utf-8'))['categories'], entries=[])
    recent = json.loads(recent_path.read_text(encoding='utf-8')) if recent_path.is_file() else dict(chapters=[])
    topics = []
    for folder in sorted(out.iterdir()):
        if not folder.is_dir() or folder.name.startswith('_') or folder.name in ('models', 'library', 'scenes', 'playtest'):
            continue
        images = sorted(p for p in folder.iterdir() if p.suffix.lower() in ('.png', '.jpg', '.gif', '.mp4', '.webm'))
        index = folder/'index.html'
        if not index.is_file() and images:
            index.write_text(page(folder.name, '<p>本地留存预览；制作版本以所属材料为准。</p>' +
                             ''.join(media(p.name, p.stem) for p in images)), encoding='utf-8')
        if not index.is_file():
            continue
        metadata = folder/'topic.json'
        title = json.loads(metadata.read_text(encoding='utf-8'))['title'] if metadata.is_file() else folder.name
        topics.append(dict(id=folder.name, title=title, page=f'{folder.name}/index.html'))
    library['entries'] = [e for e in library['entries'] if not e['id'].startswith('page:')]
    for topic in topics:
        folder = out/topic['id']
        thumb = next((p for p in (folder/'contact.png', folder/'strip.png', folder/'current.png') if p.is_file()), None)
        library['entries'].append(dict(id='page:'+topic['id'], category='other', kind='page',
            title=topic['title'], summary='本地预览页面', page=topic['page'],
            image=thumb.relative_to(out).as_posix() if thumb else None))
    recent['topics'] = topics
    for path, data in ((library_path, library), (recent_path, recent)):
        temporary = path.with_suffix('.tmp')
        temporary.write_text(json.dumps(data, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
        temporary.replace(path)
    print(f'Registered {len(topics)} local preview topics.')


def render_models(node, out):
    from PIL import Image
    work = out/'_work'
    work.mkdir(parents=True, exist_ok=True)
    tasks = []
    for ident, name, group in build_review_catalog.current_roles():
        texture = ROOT / 'src/main/resources/assets/mcyukkuri/textures/entity' / ident / 'neutral.png'
        (work / f'{ident}.texture').write_bytes(Image.open(texture).convert('RGBA').tobytes())
        tasks.append(dict(id=ident, model=ident, action='idle', time=0, face='neutral',
                          texture=f'{ident}.texture', width=400, height=360))
    (work / 'render-tasks.json').write_text(json.dumps(tasks), encoding='utf-8')
    subprocess.run([node, str(ROOT/'scripts/render_preview_models.cjs'), str(out)], check=True, cwd=ROOT)
    thumbs = out/'models/thumbs'
    thumbs.mkdir(parents=True, exist_ok=True)
    for task in tasks:
        Image.frombytes('RGBA', (task['width'], task['height']), (work / (task['id'] + '.rgba')).read_bytes()).save(thumbs / (task['id'] + '.png'))


def refresh_model_thumbs(catalog, out, node, force):
    from pose_check import preview_sources

    digest = hashlib.sha256((out/'models/catalog.js').read_bytes())
    for source in (*preview_sources(), ROOT/'scripts/build_preview_hub.py',
                   ROOT/'scripts/render_preview_models.cjs', ROOT/'preview/review/pose.js',
                   ROOT/'standards/yukkuri-library/runtime/render.js'):
        digest.update(source.relative_to(ROOT).as_posix().encode())
        digest.update(source.read_bytes())
    for model in catalog['models']:
        digest.update((ROOT/model['textures']['neutral'].lstrip('/')).read_bytes())
    stamp = out/'_work/model-preview.sha256'
    fingerprint = digest.hexdigest()
    missing = any(not (out/'models/thumbs'/f"{model['id']}.png").is_file() for model in catalog['models'])
    if force or missing or not stamp.is_file() or stamp.read_text() != fingerprint:
        render_models(node, out)
        stamp.write_text(fingerprint)


def render_specialists(out, node):
    jobs = [
        ('scripts/build_preview_props.py',),
        ('standards/yukkuri-library/drafts/care-props/preview_vanilla_care.py',
         'vanilla', 'bowls', 'fills', 'nests', 'large', 'toilets'),
        ('standards/yukkuri-library/drafts/care-props/preview_wear.py',),
        ('standards/yukkuri-library/drafts/care-props/preview_wild_nests.py',),
        ('standards/yukkuri-library/drafts/care-props/preview_fit.py',),
        ('standards/yukkuri-library/motions/variants/lore/aquatic_paddle_preview.py',),
        ('standards/yukkuri-library/motions/variants/lore/aquatic_strokes_preview.py',),
        ('art/props/family-life/preview_stem.py', '--node', node, '--out', str(out/'stem-growth')),
    ]
    for script, *args in jobs:
        subprocess.run([sys.executable, str(ROOT/script), *args], check=True, cwd=ROOT,
                       env={**os.environ, 'MCYUKKURI_PREVIEWS': str(out)})


def build(out, node='node', render=False):
    from preview import refresh_inbox

    catalog = build_review_catalog.catalog()
    build_review_catalog.write(catalog, out/'models')
    refresh_model_thumbs(catalog, out, node, render)
    if render:
        render_specialists(out, node)
    archive = out/'recent.json'
    manifest = build_review_catalog.read(archive) if archive.is_file() else dict(chapters=[])
    manifest['generatedAt'] = datetime.now().astimezone().isoformat(timespec='seconds')
    library = build_preview_library.build(manifest, catalog, out)
    data = write_entries(catalog, (entry for entry in library['entries'] if entry['kind'] == 'item'), out)
    shutil.copy2(ROOT/'preview/hub/index.html', out/'index.html')
    shutil.copy2(ROOT/'preview/review/player.html', out/'player.html')
    refresh_inbox(out, data['entries'])
    register_topics(out)
    print(out/'index.html')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--render', action='store_true')
    parser.add_argument('--node', default='node')
    args = parser.parse_args()
    build(OUT, args.node, args.render)


if __name__ == '__main__':
    main()
