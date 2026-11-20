"""Build the live preview home and catalog; --render also refreshes specialist sheets."""
from pathlib import Path
import argparse
import hashlib
import json
import os
import shutil
import subprocess
import sys

import build_review_catalog
import build_preview_library
from build_player_entries import write_entries

ROOT = Path(__file__).resolve().parents[1]
OUT = ROOT / '.local/previews'


def font(size):
    from preview import font as preview_font
    return preview_font(size)


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
    library = build_preview_library.build(catalog, out)
    data = write_entries(catalog, (entry for entry in library['entries'] if entry['kind'] == 'item'), out)
    shutil.copy2(ROOT/'preview/hub/index.html', out/'index.html')
    shutil.copy2(ROOT/'preview/review/player.html', out/'player.html')
    refresh_inbox(out, data['entries'])
    print(out/'index.html')
    return data


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--render', action='store_true')
    parser.add_argument('--node', default='node')
    args = parser.parse_args()
    build(OUT, args.node, args.render)


if __name__ == '__main__':
    main()
