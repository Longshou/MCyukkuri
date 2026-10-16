"""Export the original trigger spray as a native item model. --check is read-only."""
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
    authored = [*recipe['elements'], *recipe['badge']['elements']]
    slash = recipe['badge']['slash']
    for i in range(slash['count']):
        begin = [round(slash['start'][axis]+i*slash['step'][axis], 4) for axis in range(3)]
        authored.append(dict(name='prohibition_slash_'+str(i), **{'from': begin,
            'to': [round(begin[axis]+slash['size'][axis],4) for axis in range(3)]}, material=slash['material']))
    elements = []
    for source in authored:
        assert all(0 <= source['from'][i] < source['to'][i] <= 16 for i in range(3)), source['name']
        elements.append(dict(name=source['name'], **{'from': source['from'], 'to': source['to']},
            faces={face: dict(uv=[0,0,16,16], texture='#'+source['material'])
                   for face in ('down','up','north','south','west','east')}))
    model = dict(parent='minecraft:block/block', gui_light='front', ambientocclusion=False,
        textures=dict(particle=recipe['textures']['bottle'], **recipe['textures']), elements=elements,
        display=dict(gui=dict(rotation=[15,195,0], translation=[0,1,0], scale=[1.05]*3),
            ground=dict(translation=[0,3,0], scale=[.45]*3),
            fixed=dict(rotation=[0,180,0], translation=[0,1,0], scale=[.8]*3),
            thirdperson_righthand=dict(rotation=[0,-90,0], translation=[0,1,0], scale=[.65]*3),
            thirdperson_lefthand=dict(rotation=[0,90,0], translation=[0,1,0], scale=[.65]*3),
            firstperson_righthand=dict(rotation=[0,-90,0], translation=[1.5,1,0], scale=[.65]*3),
            firstperson_lefthand=dict(rotation=[0,90,0], translation=[1.5,1,0], scale=[.65]*3)))
    result = {ASSETS/'models/item/household_guard.json': encoded(model), SNAP/'recipe.json': raw}
    result[SNAP/'manifest.json'] = encoded(dict(id=recipe['id'], item_id=recipe['item_id'], revision=recipe['revision'],
        status=recipe['status'], source=(HERE/'recipe.json').relative_to(ROOT).as_posix(),
        exporter=(HERE/'build.py').relative_to(ROOT).as_posix(), source_sha256=hashlib.sha256(raw).hexdigest(),
        material_sources=recipe['textures'], elements=len(elements),
        bounds=[[min(e['from'][axis] for e in elements), max(e['to'][axis] for e in elements)] for axis in range(3)],
        scope='Original bottle, nozzle, trigger and barred-yukkuri label, native item geometry. Vanilla surface textures remain external references; no reused item icon.',
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
