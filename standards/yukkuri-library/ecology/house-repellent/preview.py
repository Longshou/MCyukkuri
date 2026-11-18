"""The spray as the offline software preview draws it: its inventory icon extruded one pixel thick, as the game does."""
from pathlib import Path
import importlib.util
import json
import sys

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
HEIGHT = 12   # units: the item fills most of a 16-pixel slot


def prepare(out):
    """Write the preview mesh and texture into `out`; returns their paths."""
    out = Path(out); out.mkdir(parents=True, exist_ok=True)
    if 'block_props_kit' not in sys.modules:
        spec = importlib.util.spec_from_file_location('block_props_kit', ROOT/'standards/yukkuri-library/drafts/block-props/kit.py')
        sys.modules['block_props_kit'] = module = importlib.util.module_from_spec(spec); spec.loader.exec_module(module)
    recipe = json.loads((HERE/'recipe.json').read_text(encoding='utf-8'))
    data, _, png = sys.modules['block_props_kit'].extrude('household_guard', (ROOT/recipe['icon']).read_bytes(), HEIGHT)
    mesh = out/'house_repellent.mesh.json'; texture = out/'house_repellent.texture.png'
    mesh.write_text(json.dumps(data), encoding='utf-8'); texture.write_bytes(png)
    return mesh, texture
