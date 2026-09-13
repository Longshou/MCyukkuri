"""Locally rebake only selected house grip curves, preserving released face motion."""
import copy, hashlib, json
from pathlib import Path
from runtime_house_interactions import add_house_interactions

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'art/source/standard-runtime'
PACKAGE=OUT/'carry-motion'
IDS=('house_reimu','house_marisa')
def read(p):return json.loads(p.read_text(encoding='utf-8'))
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def save(p,d,pretty=False):
    p.parent.mkdir(parents=True,exist_ok=True)
    p.write_text(json.dumps(d,ensure_ascii=False,indent=2 if pretty else None,separators=None if pretty else (',',':'),allow_nan=False),encoding='utf-8')
def rel(p):return p.relative_to(ROOT).as_posix()
def flatten(rig,names):
    return {key:{'duration':c['duration'],'loop':c['loop'],'tracks':[
        {'bone':names[n],'channel':ch,'keys':[[float(t),*v]for t,v in sorted(keys.items(),key=lambda p:float(p[0]))]}
        for n,channels in c['tracks'].items()for ch,keys in channels.items()]}
        for key,c in rig['behavior_clips'].items()}
def main():
    ledger=read(ROOT/'art/runtime-sources.json');models={};records={};checks={}
    for ident in IDS:
        record=copy.deepcopy(ledger['models'][ident]);runtime=ROOT/record['runtime'];selected=OUT/ident
        assert sha(runtime)==sha(selected/'runtime.model.json')==record['runtime_sha256']
        original=read(runtime);rig=read(selected/'animation-rig.json');before=copy.deepcopy(rig)
        model=copy.deepcopy(original);names={b['name']:i for i,b in enumerate(model['bones'])}
        hands={names['hand_left'],names['hand_right']}
        metadata=add_house_interactions(rig,ident.split('_')[1]);model['behavior_clips']=flatten(rig,names)
        assert model['clips']==original['clips'], 'Preserve released waddle and all other common clips'
        for key in model:
            if key!='behavior_clips':assert model[key]==original[key],key
        for key,clip in model['behavior_clips'].items():
            assert [t for t in clip['tracks']if t['bone']not in hands]==[
                t for t in original['behavior_clips'][key]['tracks']if t['bone']not in hands],key
        assert model['behavior_clips']['house_crawl']==original['behavior_clips']['house_crawl']
        for key in rig:
            if key!='behavior_clips':assert rig[key]==before[key],key
        repeat=copy.deepcopy(rig);add_house_interactions(repeat,ident.split('_')[1]);assert repeat==rig
        baseline=ROOT/'.local/house-carry/before'/ident
        if not baseline.exists():
            baseline.mkdir(parents=True)
            (baseline/'runtime.model.json').write_bytes(runtime.read_bytes())
            (baseline/'animation-rig.json').write_bytes((selected/'animation-rig.json').read_bytes())
        input_hash=sha(runtime)
        save(runtime,model);(selected/'runtime.model.json').write_bytes(runtime.read_bytes());save(selected/'animation-rig.json',rig)
        record['runtime_sha256']=sha(runtime)
        record['game_acceptance']='current_house_carry_recheck_pending'
        record.setdefault('retargeting',{})['carry_motion']={'recipe':rel(PACKAGE/'recipe.json'),'recipe_sha256':sha(PACKAGE/'recipe.json'),
            'author':'scripts/runtime_house_interactions.py','author_sha256':sha(ROOT/'scripts/runtime_house_interactions.py'),
            'manifest':rel(PACKAGE/'manifest.json'),'shape_changed':False,'affected_bones':['hand_left','hand_right'],
            'affected_clips':['house_pickup','house_carry','house_deposit'],'carry_pose':metadata['carry_pose']}
        checks[ident]={'input_runtime_sha256':input_hash,'runtime_sha256':sha(runtime),'rig_sha256':sha(selected/'animation-rig.json'),
            'unchanged':['rest_geometry','UV','textures','weights','bones','grip_points','common_clips_including_waddle','house_crawl','non_hand_behavior_tracks'],
            'idempotence':'passed'}
        records[ident]=record;models[ident]=model
    ledger=read(ROOT/'art/runtime-sources.json');ledger['models'].update(records);save(ROOT/'art/runtime-sources.json',ledger,True)
    provenance=read(OUT/'provenance.json');provenance['entities'].update(records);save(OUT/'provenance.json',provenance,True)
    preview=OUT/'preview-data.js';catalog=json.JSONDecoder().raw_decode(preview.read_text(encoding='utf-8').split('const MODELS=',1)[1])[0]
    for ident,model in models.items():catalog[ident]['model']=model
    preview.write_text('const MODELS='+json.dumps(catalog,separators=(',',':'))+';',encoding='utf-8')
    save(PACKAGE/'manifest.json',{'author':'scripts/runtime_house_interactions.py','author_sha256':sha(ROOT/'scripts/runtime_house_interactions.py'),
        'recipe_sha256':sha(PACKAGE/'recipe.json'),'exporter':rel(Path(__file__)),'exporter_sha256':sha(Path(__file__)),'models':checks},True)
    print(json.dumps(checks))
if __name__=='__main__':main()
