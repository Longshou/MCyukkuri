"""Export the legacy sealed-box block for save compatibility; --check is read-only.

The current household_guard item belongs to house-repellent and is never written here.
"""
from pathlib import Path
import argparse
import hashlib
import json

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
ASSETS = ROOT/'src/main/resources/assets/mcyukkuri'
SNAP = ROOT/'art/source/house-guard'


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, indent=2)+'\n').encode('utf-8')


def outputs():
    raw = (HERE/'recipe.json').read_bytes(); recipe = json.loads(raw)
    elements = []
    for source in recipe['elements']:
        assert all(0 <= source['from'][i] < source['to'][i] <= 16 for i in range(3)), source['name']
        elements.append(dict(name=source['name'], **{'from': source['from'], 'to': source['to']},
            faces={face: dict(uv=[0, 0, 16, 16], texture='#'+source['material'])
                   for face in ('down', 'up', 'north', 'south', 'west', 'east')}))
    bounds = [[min(e['from'][axis] for e in elements), max(e['to'][axis] for e in elements)] for axis in range(3)]
    assert bounds == recipe['bounds'], bounds
    model = dict(parent='minecraft:block/block', ambientocclusion=True,
        textures=dict(particle=recipe['textures']['metal'], **recipe['textures']), elements=elements,
        display=dict(gui=dict(rotation=[30, 225, 0], translation=[0, 2, 0], scale=[1.2, 1.2, 1.2]),
            ground=dict(translation=[0, 3, 0], scale=[.5, .5, .5]),
            fixed=dict(rotation=[0, 0, 0], translation=[0, 2, 0], scale=[1, 1, 1]),
            thirdperson_righthand=dict(rotation=[75, 45, 0], translation=[0, 2.5, 0], scale=[.5, .5, .5]),
            firstperson_righthand=dict(rotation=[0, 45, 0], translation=[0, 2, 0], scale=[.7, .7, .7])))
    result = {
        ASSETS/'models/block/household_guard.json': encoded(model),
        ASSETS/'blockstates/household_guard.json': encoded(dict(variants={'': dict(model='mcyukkuri:block/household_guard')})),
        SNAP/'recipe.json': raw,
    }
    result[SNAP/'manifest.json'] = encoded(dict(id=recipe['id'], revision=recipe['revision'], status=recipe['status'],
        source=(HERE/'recipe.json').relative_to(ROOT).as_posix(),
        exporter=(HERE/'build.py').relative_to(ROOT).as_posix(),
        source_sha256=hashlib.sha256(raw).hexdigest(),
        material_sources=recipe['textures'],
        scope='Original cuboid geometry; vanilla block surface textures remain external runtime references, no borrowed item icons.',
        outputs={p.relative_to(ROOT).as_posix(): hashlib.sha256(data).hexdigest() for p, data in result.items()}))
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('--check', action='store_true'); args = parser.parse_args()
    expected = outputs(); stale = []
    for path, data in expected.items():
        if args.check:
            if not path.exists() or path.read_bytes() != data:
                stale.append(path.relative_to(ROOT).as_posix())
        else:
            path.parent.mkdir(parents=True, exist_ok=True); path.write_bytes(data)
    if stale:
        raise SystemExit('Stale outputs: '+', '.join(stale))
    print(('Checked ' if args.check else 'Exported ')+str(len(expected))+' household guard files.')


if __name__ == '__main__':
    main()
