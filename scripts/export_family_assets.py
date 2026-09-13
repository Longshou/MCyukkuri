#!/usr/bin/env python3
"""Export the selected stem/growth recipe for deterministic server and client sampling."""
import argparse
import hashlib
import json
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
SOURCE=ROOT/'art/props/family-life'
def outputs():
    recipe=json.loads((SOURCE/'recipe.json').read_text(encoding='utf8'))
    read=lambda key:json.loads((ROOT/recipe[key]).read_text(encoding='utf8'))
    data={'format':1,'stem':read('stem_source'),'growth':read('growth_source'),'action':read('action_source'),
          'lengthScale':recipe['count_length_scale'],'radiusScale':recipe['count_radius_scale'],'fruitForwardClearance':recipe['fruit_forward_clearance'],'variants':recipe['variants']}
    encode=lambda data:(json.dumps(data,ensure_ascii=False,indent=2)+'\n').encode('utf8')
    files={ROOT/'src/main/resources/assets/mcyukkuri/yukkuri_presentation/family.json':encode(data)}
    sources=['art/props/family-life/recipe.json']+[recipe[key] for key in ['stem_source','growth_source','action_source']]
    files[SOURCE/'provenance.json']=encode({'role':'game integration; source models unchanged; new procedural presentation pending user review',
        'inputs':{p:hashlib.sha256((ROOT/p).read_bytes()).hexdigest() for p in sources},
        'outputs':{p.relative_to(ROOT).as_posix():hashlib.sha256(data).hexdigest() for p,data in files.items()}})
    return files
def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check',action='store_true');args=parser.parse_args()
    files=outputs()
    for path,data in files.items():
        if args.check:
            if not path.is_file() or path.read_bytes()!=data:raise SystemExit(f'Family export differs: {path.relative_to(ROOT)}')
        else:path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
    print(f'{len(files)} family asset files '+('match their sources.' if args.check else 'exported.'))
if __name__=='__main__':main()
