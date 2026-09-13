"""Derive removable-hat motion scales and real brim support from current game geometry."""
from pathlib import Path
import hashlib,json

ROOT=Path(__file__).resolve().parents[1]
ASSETS=ROOT/'src/main/resources/assets/mcyukkuri'
OUT=ROOT/'art/source/hat-interactions'
IDS=['marisa','marisa_sparse','marisa_face','marisa_bean','house_marisa']
def read(p):return json.loads(p.read_text(encoding='utf-8'))
def bounds(m,p):
    v=m['vertices'][p['start']*8:(p['start']+p['count'])*8]
    return [min(v[k::8]) for k in range(3)]+[max(v[k::8]) for k in range(3)]
def main():
    result={'version':1,'variants':{},'children':{}}
    fingerprints={}
    for path in (ASSETS/'yukkuri').glob('*.json'):
        m=read(path)
        if 'vertices' not in m:continue
        ident=path.stem
        skin=next((p for p in m['parts'] if p['name'] in ('rounded_body_surface','deficient_body_surface','house_body_surface','predator_body_surface')),None)
        if skin:
            v=m['vertices'][skin['start']*8:(skin['start']+skin['count'])*8]
            low=min(v[1::8]);points=[v[i:i+3] for i in range(0,len(v),8) if v[i+1]<=low+.001]
            result['children'][ident]={'foot':[min(p[k] for p in points) for k in range(3)]+[max(p[k] for p in points) for k in range(3)],
                'parts':[bounds(m,p) for p in m['parts'] if not p['anatomy'] and 'puff' not in p['name']]}
        if ident not in IDS:continue
        parts={p['name']:p for p in m['parts']}
        brim=bounds(m,parts['brim_center']);band=bounds(m,parts['hat_band'])
        unit=(brim[3]-brim[0])/18.3466666
        seat=[brim[3]-.8*unit,brim[4]+.02*unit,0]
        support=[band[3]+.10*unit,brim[1],-2*unit,brim[3]-.08*unit,brim[4],2*unit]
        seat[0]=(support[0]+support[3])/2
        result['variants'][ident]={'bone':next(i for i,b in enumerate(m['bones']) if b['name']=='hat_follow'),
            'bone_name':'hat_follow','lift':3*unit,'forward':-3*unit,'seat':seat,'support':support,'margin':.10*unit,
            'obstacles':[bounds(m,p) for p in m['parts'] if p['name'].startswith(('hat_','bow_'))],
            'runtime_sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
        fingerprints[path.relative_to(ROOT).as_posix()]=hashlib.sha256(path.read_bytes()).hexdigest()
    target=ASSETS/'yukkuri_presentation/hat_interactions.json'
    target.write_text(json.dumps(result,separators=(',',':'))+'\n',encoding='utf-8')
    OUT.mkdir(parents=True,exist_ok=True)
    (OUT/'recipe.json').write_text(json.dumps({'status':'pending_user_confirmation','scope':IDS,
        'motion':'Raise the hat independently, then move forward; return reverses the same motion. Hair stays on the body.',
        'carry':'One small young entity on the right brim; foot margin and full model part bounds must fit without intersecting the hat or bow.',
        'exporter':'scripts/export_hat_interactions.py','exporter_sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        'runtime':target.relative_to(ROOT).as_posix(),'runtime_sha256':hashlib.sha256(target.read_bytes()).hexdigest(),
        'sources':fingerprints},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Exported five hat roots, motion scales and supported seats;',len(result['children']),'child footprints.')
if __name__=='__main__':main()
