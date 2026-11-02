"""Write the browser preview data of the seven selected standard runtime models.

Reads only the committed snapshots and their textures; nothing is authored here.
The data is a local preview artifact, so it goes to .local/previews/ and never
enters git.
"""
import base64
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
RUNTIME = ROOT / 'art/source/standard-runtime'
OUT = ROOT / '.local/previews/standard-runtime/preview-data.js'
IDS = ('reimu', 'marisa', 'chen', 'alice', 'patchouli', 'house_reimu', 'house_marisa')


def write():
    catalog = {ident: {'model': json.loads((RUNTIME / ident / 'runtime.model.json').read_text(encoding='utf-8')),
                       'textures': {p.stem: 'data:image/png;base64,' + base64.b64encode(p.read_bytes()).decode('ascii')
                                    for p in sorted((RUNTIME / ident / 'textures').glob('*.png'))}}
               for ident in IDS}
    OUT.parent.mkdir(parents=True, exist_ok=True)
    OUT.write_text('const MODELS=' + json.dumps(catalog, separators=(',', ':')) + ';', encoding='utf-8')
    return OUT


if __name__ == '__main__':
    print(write().relative_to(ROOT).as_posix())
