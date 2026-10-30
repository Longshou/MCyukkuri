"""The aquatic Marisa's paddle stick and waterline wet band: withdrawn from the export.

`paddle_stick` (the plain picked-up stick the braid tuft holds or stows on the brim) and `marisa_hat_wetline` (the
translucent wet band on the turned hat's outer hull) are not drawn by the game: no code reads either prop, and their
runtime meshes, textures and `subspecies-index.json` rows are deleted. A paddle or waterline look that the game uses is
authored afresh in this package; until then this script only guards that neither prop comes back without an exporter.

  python build.py [--check]
"""
from pathlib import Path
import argparse, json

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
ASSETS = ROOT/'src/main/resources/assets/mcyukkuri'
WITHDRAWN = ('paddle_stick', 'marisa_hat_wetline')


def main():
    ap = argparse.ArgumentParser(description=__doc__); ap.add_argument('--check', action='store_true'); ap.parse_args()
    left = [p.relative_to(ROOT).as_posix() for name in WITHDRAWN
            for p in (ASSETS/'yukkuri_props'/f'{name}.json', ASSETS/'textures/props'/f'{name}.png') if p.exists()]
    index = json.loads((ASSETS/'yukkuri_props/subspecies-index.json').read_text(encoding='utf-8'))
    left += [f'subspecies-index.json: {name}' for name in WITHDRAWN if name in index]
    if left: raise SystemExit('Withdrawn prop still exported: ' + ', '.join(left))
    print('paddle_stick and marisa_hat_wetline: no prop exported (withdrawn)')


if __name__ == '__main__':
    main()
