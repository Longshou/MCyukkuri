"""Verify current source, selected files and runtime exports; write the current report."""
from pathlib import Path
import hashlib, json, math
from datetime import datetime, timezone
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[3]
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
manifest=json.loads((HERE/'manifest.json').read_text(encoding='utf-8'));checks=[]
for name,entry in manifest['exports'].items():
    for ext,key in [('.json','mesh_sha256'),('.bbmodel','editable_sha256'),('.png','texture_sha256')]:
        source=HERE/'assembled'/(name+ext)
        assert sha(source)==entry[key],name+' manifest mismatch'
        assert source.read_bytes()==(ROOT/'art/props/wild-shelters'/(name+ext)).read_bytes(),name+' selected copy differs'
    source=HERE/'assembled'/(name+'.json');run=ROOT/'src/main/resources/assets/mcyukkuri/yukkuri_props'/(name+'.json')
    assert source.read_bytes()==run.read_bytes(),name+' runtime mesh differs'
    assert (HERE/'assembled'/(name+'.png')).read_bytes()==(ROOT/'src/main/resources/assets/mcyukkuri/textures/props'/(name+'.png')).read_bytes()
    data=json.loads(source.read_text());v=data['vertices'];assert len(v)%24==0
    assert all(math.isfinite(x) for x in v)
    for i in range(0,len(v),8):
        assert 0<=v[i+3]<=1 and 0<=v[i+4]<=1
        assert abs(sum(n*n for n in v[i+5:i+8])-1)<.001
    checks.append({'model':name,'vertices':len(v)//8,'source_selected_runtime_match':True,'finite_normalized_vertices':True,'model_sha256':sha(source)})
report={'date':datetime.now(timezone.utc).isoformat(),'checks':checks,'authoring_sha256':sha(HERE/'build.py'),
        'scope':'File identity, finite mesh values, normalized normals and valid UVs; does not establish aesthetic approval.'}
out=ROOT/'.local/reports/wild-shelters';out.mkdir(parents=True,exist_ok=True)
(out/'static-consistency.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print('Verified',len(checks),'current source/selected/runtime models')
