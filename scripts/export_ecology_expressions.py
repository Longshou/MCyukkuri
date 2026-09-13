"""Select approved expression atlases for the existing seven runtime rigs; never rebuild geometry.

House authoring copies the ordinary atlas unchanged (house-yukkuri/build.py).
The selected additions have the same 128x128 UV layout. --check is read-only.
"""
import argparse
import hashlib
import json
from pathlib import Path

ROOT=Path(__file__).resolve().parents[1]
LIB=ROOT/'standards/yukkuri-library'
IDS=['closed_smile','chewing','chewing_closed','satisfied','open_smile']
VARIANTS=['reimu','marisa','alice','chen','patchouli','house_reimu','house_marisa']


def digest(data): return hashlib.sha256(data).hexdigest()


def outputs():
    approved=json.loads((LIB/'provenance/standard-expressions.json').read_text(encoding='utf-8'))
    accepted={entry['id'] for entry in approved['entries']}
    files={}; records={}
    for variant in VARIANTS:
        if (ROOT/'art/source/rounded-runtime/manifest.json').exists() and not variant.startswith('house_'):continue
        species=variant.removeprefix('house_'); entries={}
        for expression in IDS:
            assert expression in accepted
            source=LIB/'assembled'/species/'textures'/(expression+'.png'); data=source.read_bytes()
            preset=json.loads((LIB/'expressions/presets'/(expression+'.json')).read_text(encoding='utf-8'))
            assert preset['status']=='accepted' and not preset.get('geometry_offsets'), 'Requires geometry adaptation: '+expression
            assert digest(data)==approved['exports'][source.relative_to(LIB).as_posix()], 'Changed since standard confirmation: '+str(source)
            # PNG width/height, checked without altering image bytes.
            assert int.from_bytes(data[16:20],'big')==128 and int.from_bytes(data[20:24],'big')==128
            runtime=ROOT/'src/main/resources/assets/mcyukkuri/textures/entity'/variant/(expression+'.png')
            snapshot=ROOT/'art/source/ecology/textures'/variant/(expression+'.png')
            files[runtime]=data;files[snapshot]=data
            entries[expression]={'source':source.relative_to(ROOT).as_posix(),'snapshot':snapshot.relative_to(ROOT).as_posix(),
                                 'runtime':runtime.relative_to(ROOT).as_posix(),'sha256':digest(data)}
        model=ROOT/'src/main/resources/assets/mcyukkuri/yukkuri'/(variant+'.json')
        records[variant]={'model':model.relative_to(ROOT).as_posix(),'model_sha256':digest(model.read_bytes()),'textures':entries}
    manifest={'status':'selected-approved-expressions-runtime-adaptation','expressions':IDS,
              'standard_confirmation':'standards/yukkuri-library/provenance/standard-expressions.json',
              'standard_confirmation_sha256':digest((LIB/'provenance/standard-expressions.json').read_bytes()),'entities':records}
    files[ROOT/'art/source/ecology/expressions.json']=(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n').encode()
    return files


def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check',action='store_true');args=parser.parse_args()
    for path,data in outputs().items():
        if args.check: assert path.is_file() and path.read_bytes()==data,'Outdated ecology expression: '+str(path)
        else:path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(data)
    print('5 approved expressions x 7 runtime variants: '+('verified' if args.check else 'selected and exported'))


if __name__=='__main__':main()
