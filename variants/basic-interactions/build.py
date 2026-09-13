"""Build a portable local animation preview from the selected authored models."""
from pathlib import Path
import json, hashlib, shutil

ROOT = Path(__file__).resolve().parent
REPO = ROOT.parents[1]
sources = {'marisa': REPO/'variants/marisa-b-refined/marisa.bbmodel',
           'reimu': REPO/'variants/reimu-exercise/reimu.bbmodel'}
data = {}
records = []
for species, path in sources.items():
    if not path.exists():
        path = ROOT/f'{species}.bbmodel'
    bb = json.loads(path.read_text(encoding='utf-8'))
    elements = []
    for e in bb['elements']:
        name = e['name']
        if name.startswith('puff') or name.startswith('face_effort'):
            continue
        if name.startswith(('face_tear', 'face_effect')):
            continue
        # Each original cube keeps its dimensions, UVs and authoring rotation.
        elements.append(e)
    data[species] = {'elements': elements, 'texture': bb['textures'][0]['source']}
    if path.resolve() != (ROOT/f'{species}.bbmodel').resolve():
        shutil.copyfile(path, ROOT/f'{species}.bbmodel')
    records.append({'file': str(path.relative_to(REPO)), 'sha256': hashlib.sha256(path.read_bytes()).hexdigest(), 'cubes': len(elements)})
three = REPO/'preview/vendor/three.min.js'
if three.exists():
    shutil.copyfile(three, ROOT/'three.min.js')
(ROOT/'model-data.js').write_text('const MODEL_DATA='+json.dumps(data, ensure_ascii=False, separators=(',', ':'))+';\n', encoding='utf-8')
html = (ROOT/'viewer.html').read_text(encoding='utf-8')
for filename in ['three.min.js', 'model-data.js', 'interactions.js', 'viewer.js']:
    html = html.replace(f'<script src="{filename}"></script>', '<script>\n'+(ROOT/filename).read_text(encoding='utf-8').replace('</script>', '<\\/script>')+'\n</script>')
(ROOT/'index.html').write_text(html, encoding='utf-8')
(ROOT/'build-info.json').write_text(json.dumps({'sources': records, 'renderer': 'local Three.js, authored geometry and procedural choreography', 'reference_sprites_in_preview': False}, ensure_ascii=False, indent=2), encoding='utf-8')
print('Built standalone index.html with both selected models')
