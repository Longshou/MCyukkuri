"""Read-only checks of current selections, geometry, textures, wounds and attachment fingerprints."""
from runtime_model_fingerprint import matches_model_fingerprint
import argparse,gzip,hashlib,json,math
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'art/source/rounded-runtime'
ASSETS=ROOT/'src/main/resources/assets/mcyukkuri'
def read(p):return json.loads(p.read_text(encoding='utf-8'))
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main(output='.local/validation/personality-deibu/current'):
    selected=read(OUT/'manifest.json');checks=[]
    assert sha(ROOT/selected['exporter'])==selected['exporter_sha256']
    assert sha(ROOT/selected['standard_recipe'])==selected['standard_recipe_sha256']
    for ident,r in selected['models'].items():
        for p,h in [('selected_model','selected_model_sha256'),('selected_rig','selected_rig_sha256'),('runtime','runtime_sha256')]:assert sha(ROOT/r[p])==r[h],(ident,p)
        assert sha(OUT/ident/'runtime.model.json')==r['runtime_sha256']
        for name,t in r['textures'].items():
            assert sha(ROOT/t['runtime'])==t['sha256']==sha(ROOT/t['snapshot']),(ident,name)
            assert sha(ROOT/t['source'])==t['source_sha256'],(ident,name,'source')
        checks.append({'id':ident,'selected_sources_textures_snapshot':True})
    for file in sorted((ASSETS/'yukkuri').glob('*.json')):
        model=read(file)
        if 'vertices'not in model:continue
        ident=file.stem;vertices=model['vertices'];weights=model['weights'];bones=model['bones']
        assert len(vertices)==len(weights)*8 and all(math.isfinite(v)for v in vertices),ident
        assert all(0<=a<len(bones)and 0<=b<len(bones)and 0<=q<=1 for a,b,q in weights),ident
        for part in model['parts']:assert part['count']%3==0 and part['start']+part['count']<=len(weights),ident
        for clip in model['clips'].values():
            assert clip['duration']>0,ident
            for track in clip['tracks']:assert 0<=track['bone']<len(bones)and all(all(math.isfinite(v)for v in key)for key in track['keys']),ident
        with gzip.open(ASSETS/'yukkuri_wounds'/f'{ident}.json.gz','rt')as f:surface=json.load(f)
        assert matches_model_fingerprint(file.read_bytes(),surface['runtimeSourceHash']),ident
        assert len(surface['points'])==len(surface['weights'])*5 and surface['skinCount']%3==0,ident
        assert all(math.isfinite(v)for v in surface['points']),ident
        assert all(abs(sum(w for b,w in row)-1)<.00003 and all(0<=b<len(bones)and w>=0 for b,w in row)for row in surface['weights']),ident
        checks.append({'id':ident,'runtime_vertices':len(weights),'wound_vertices':len(surface['weights']),'clips':len(model['clips']),'valid':True})
    for p,h in read(OUT/'attachments.json')['sources'].items():assert sha(ROOT/p)==h,p
    mounts=read(ASSETS/'yukkuri/badge_mounts.json')
    for key,folder in [('variants','yukkuri'),('detached','yukkuri_props')]:
        for ident,r in mounts[key].items():assert sha(ASSETS/folder/f'{ident}.json')==r['model_sha256'],('badge',ident)
    target=ROOT/output;target.mkdir(parents=True,exist_ok=True)
    (target/'assets.json').write_text(json.dumps({'passed':True,'checks':checks},indent=2),encoding='utf-8')
    print(json.dumps({'passed':True,'checks':len(checks)}))
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('--output',default='.local/validation/personality-deibu/current');main(parser.parse_args().output)
