"""Verify selected source, output fingerprints and finite shared skin data without modifying them."""
from runtime_model_fingerprint import matches_model_fingerprint
import gzip,hashlib,json,math
from pathlib import Path
ROOT=Path(__file__).resolve().parents[1]
def sha(p):return hashlib.sha256((ROOT/p).read_bytes()).hexdigest()
def main():
    manifest=json.loads((ROOT/'art/props/body-wounds/provenance.json').read_text())
    assert sha(manifest['source'])==manifest['source_sha256']
    assert sha(manifest['exporter']['path'])==manifest['exporter']['sha256']
    assert sha(manifest['palette']['source'])==manifest['palette']['source_sha256']==sha(manifest['palette']['output'])
    checks=[]
    for name,m in manifest['models'].items():
        assert sha(m['runtime_source'])==m['source_sha256']
        assert sha(m['output'])==m['output_sha256']
        with gzip.open(ROOT/m['output'],'rt') as f:data=json.load(f)
        assert matches_model_fingerprint((ROOT/m['runtime_source']).read_bytes(),data['runtimeSourceHash'])
        assert len(data['points'])==len(data['weights'])*5 and data['skinCount']%3==0
        assert all(math.isfinite(x) for x in data['points'])
        assert all(all(0<=b<len(data['bones']) and math.isfinite(w) and w>=0 for b,w in weights) and abs(sum(w for b,w in weights)-1)<.00002 for weights in data['weights'])
        shared={}
        for i in range(data['skinCount']):
            key=tuple(data['points'][i*5:i*5+3]);weights=tuple((b,w) for b,w in data['weights'][i])
            if key in shared:assert shared[key]==weights,(name,'inconsistent coincident binding',key)
            else:shared[key]=weights
        checks.append({'variant':name,'skinVertices':data['skinCount'],'sharedBindings':True,'fingerprints':True})
    print(json.dumps(checks,indent=2))
if __name__=='__main__':main()
