"""Export the repellent spray item model: a vanilla generated item on its inventory icon. --check is read-only."""
from pathlib import Path
import argparse
import hashlib
import json

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
ASSETS = ROOT/'src/main/resources/assets/mcyukkuri'
SNAP = ROOT/'art/source/house-repellent'


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, indent=2)+'\n').encode('utf-8')


def outputs():
    raw = (HERE/'recipe.json').read_bytes(); recipe = json.loads(raw)
    icon = ROOT/recipe['icon']
    # The icon texture itself is shipped by scripts/export_item_icons.py as textures/item/<item>.png.
    assert recipe['model']['textures']['layer0'] == 'mcyukkuri:item/'+icon.stem == 'mcyukkuri:item/'+recipe['item_id']
    result = {ASSETS/'models/item/household_guard.json': encoded(recipe['model']), SNAP/'recipe.json': raw}
    result[SNAP/'manifest.json'] = encoded(dict(id=recipe['id'], item_id=recipe['item_id'], revision=recipe['revision'],
        status=recipe['status'], source=(HERE/'recipe.json').relative_to(ROOT).as_posix(),
        exporter=(HERE/'build.py').relative_to(ROOT).as_posix(), source_sha256=hashlib.sha256(raw).hexdigest(),
        icon=recipe['icon'], icon_sha256=hashlib.sha256(icon.read_bytes()).hexdigest(),
        scope='Vanilla item/generated model on the inventory icon (drawn by standards/yukkuri-library/drafts/item-icons/draw.py); no own geometry and no vanilla textures.',
        outputs={p.relative_to(ROOT).as_posix(): hashlib.sha256(data).hexdigest() for p,data in result.items()}))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('--check', action='store_true'); args=parser.parse_args()
    expected=outputs(); stale=[]
    for path,data in expected.items():
        if args.check:
            if not path.exists() or path.read_bytes()!=data: stale.append(path.relative_to(ROOT).as_posix())
        else:
            path.parent.mkdir(parents=True,exist_ok=True); path.write_bytes(data)
    if stale: raise SystemExit('Stale outputs: '+', '.join(stale))
    print(('Checked ' if args.check else 'Exported ')+str(len(expected))+' repellent spray files.')


if __name__=='__main__': main()
