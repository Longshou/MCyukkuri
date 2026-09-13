#!/usr/bin/env python3
"""Adapt only the two currently selected house walking rigs.

Default stages under .local/house-face. --publish synchronizes both selected
runtime snapshots, game models, provenance and their existing preview entries.
Never rebuild the five ordinary species or replace house rest geometry/textures.
"""
from pathlib import Path
import argparse
import copy
import hashlib
import json
from runtime_house_face import fit

ROOT = Path(__file__).resolve().parents[1]
SELECTED = ROOT/'art/source/standard-runtime'
PACKAGE = SELECTED/'face-motion'
IDS = ('house_reimu', 'house_marisa')


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def save(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    pretty = path.name in ('runtime-sources.json', 'provenance.json')
    data = json.dumps(value, ensure_ascii=False, indent=2 if pretty else None,
                      separators=None if pretty else (',', ':'), allow_nan=False)
    temporary = path.with_name(path.name+'.house-face.tmp')
    temporary.write_text(data, encoding='utf-8')
    temporary.replace(path)


def sha(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def relative(path):
    return path.relative_to(ROOT).as_posix()


def sync_rig(model, rig, anchors):
    # Preserve unrelated key maps and all existing vertex bindings verbatim.
    for name in anchors:
        aid = next(i for i,bone in enumerate(model['bones']) if bone['name']==name)
        rig['clips']['waddle']['tracks'][name] = {
            track['channel']:{str(key[0]):key[1:] for key in track['keys']}
            for track in model['clips']['waddle']['tracks'] if track['bone']==aid}


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--publish', action='store_true')
    args = parser.parse_args()
    recipe = read(PACKAGE/'recipe.json')
    ledger_path = ROOT/'art/runtime-sources.json'
    ledger = read(ledger_path)
    results, models, provenance = {}, {}, {}
    staged = ROOT/'.local/house-face/staged'
    for ident in IDS:
        record = copy.deepcopy(ledger['models'][ident])
        runtime = ROOT/record['runtime']
        selected = SELECTED/ident
        assert sha(runtime)==record['runtime_sha256']==sha(selected/'runtime.model.json')
        original = read(runtime)
        model, rig = copy.deepcopy(original), read(selected/'animation-rig.json')
        assert rig['vertex_weights']==model['weights']
        anchors = fit(model, recipe)
        names = {record['anchor'] for record in anchors}
        ids = {i for i,bone in enumerate(model['bones']) if bone['name'] in names}
        assert all(original[key]==model[key] for key in original if key!='clips')
        assert all(original['clips'][key]==model['clips'][key] for key in model['clips'] if key!='waddle')
        assert [t for t in original['clips']['waddle']['tracks'] if t['bone'] not in ids] == [
            t for t in model['clips']['waddle']['tracks'] if t['bone'] not in ids]
        again = copy.deepcopy(model)
        fit(again, recipe)
        assert again==model, 'Repeated adaptation must not accumulate offsets'
        sync_rig(model, rig, names)
        save(staged/ident/'runtime.model.json', model)
        save(staged/ident/'animation-rig.json', rig)
        output_sha = sha(staged/ident/'runtime.model.json')
        results[ident] = {'input_runtime_sha256':sha(runtime), 'runtime_sha256':output_sha,
            'rig_sha256':sha(staged/ident/'animation-rig.json'), 'anchors':anchors,
            'preserved':['rest_vertices','UVs','normals','weights','bones','facial_child_controls',
                         'non_waddle_clips','behavior_clips','grip','textures'],
            'idempotence':'passed', 'runtime':record['runtime']}
        record['runtime_sha256'] = output_sha
        record['game_acceptance'] = 'current_face_motion_recheck_pending'
        record.setdefault('retargeting', {})['face_motion'] = {
            'recipe':relative(PACKAGE/'recipe.json'), 'recipe_sha256':sha(PACKAGE/'recipe.json'),
            'author':'scripts/runtime_house_face.py', 'author_sha256':sha(ROOT/'scripts/runtime_house_face.py'),
            'manifest':relative(PACKAGE/'manifest.json'), 'shape_changed':False,
            'affected_clip':'waddle', 'affected_bones':sorted(names)}
        models[ident], provenance[ident] = model, record
        if args.publish:
            for filename in ('runtime.model.json','animation-rig.json'):
                (selected/filename).write_bytes((staged/ident/filename).read_bytes())
            runtime.write_bytes((staged/ident/'runtime.model.json').read_bytes())
    manifest = {'author':'scripts/runtime_house_face.py', 'author_sha256':sha(ROOT/'scripts/runtime_house_face.py'),
        'exporter':relative(Path(__file__)), 'exporter_sha256':sha(Path(__file__)),
        'recipe_sha256':sha(PACKAGE/'recipe.json'), 'published':args.publish, 'models':results}
    save(staged/'manifest.json', manifest)
    if args.publish:
        # Re-read shared files after staging, then merge only the two owned entries.
        ledger = read(ledger_path)
        ledger['models'].update(provenance)
        save(ledger_path, ledger)
        old = read(SELECTED/'provenance.json')
        old['entities'].update(provenance)
        save(SELECTED/'provenance.json', old)
        preview = SELECTED/'preview-data.js'
        catalog = json.JSONDecoder().raw_decode(preview.read_text(encoding='utf-8').split('const MODELS=',1)[1])[0]
        for ident, model in models.items():
            catalog[ident]['model'] = model
        preview.write_text('const MODELS='+json.dumps(catalog,separators=(',',':'))+';', encoding='utf-8')
        save(PACKAGE/'manifest.json', manifest)
    print(json.dumps({'published':args.publish,'models':{ident:record['runtime_sha256'] for ident,record in results.items()}}))


if __name__=='__main__':
    main()
