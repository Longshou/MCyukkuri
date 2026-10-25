"""Derive removable-hat motion scales and real brim support from current game geometry.

Hats and child footprints are measured on the placement-fitted meshes the game shows."""
from pathlib import Path
import hashlib,json
import placement_fit

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
    fingerprints={};fits=placement_fit.load()
    for path in (ASSETS/'yukkuri').glob('*.json'):
        m=read(path)
        if 'vertices' not in m:continue
        m=placement_fit.fitted(path,fits)
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
        # The brim ring around the crown: the top faces children can stand on and one seat line per side, placed
        # 42% of the way in from the outer edge towards the crown (project design; HatCarry.layout fills it).
        brims=[bounds(m,parts[n]) for n in ('brim_center','brim_sides','brim_front','brim_back') if n in parts]
        base=bounds(m,parts['hat_base']);crown=[min(base[0],band[0]),min(base[2],band[2]),max(base[3],band[3]),max(base[5],band[5])]
        outer=[min(b[0] for b in brims),min(b[2] for b in brims),max(b[3] for b in brims),max(b[5] for b in brims)]
        ring={'front':outer[1]+.42*(crown[1]-outer[1]),'back':outer[3]-.42*(outer[3]-crown[3]),'side':outer[2]-.42*(outer[2]-crown[2]),
              'supports':[[b[0],b[2],b[3],b[5],b[4]] for b in brims]}
        result['variants'][ident]={'bone':next(i for i,b in enumerate(m['bones']) if b['name']=='hat_follow'),
            'bone_name':'hat_follow','lift':3*unit,'forward':-3*unit,'seat':seat,'support':support,'ring':ring,'margin':.10*unit,
            'obstacles':[bounds(m,p) for p in m['parts'] if p['name'].startswith(('hat_','bow_'))],
            'runtime_sha256':hashlib.sha256(path.read_bytes()).hexdigest()}
        fingerprints[path.relative_to(ROOT).as_posix()]=hashlib.sha256(path.read_bytes()).hexdigest()
    target=ASSETS/'yukkuri_presentation/hat_interactions.json'
    target.write_text(json.dumps(result,separators=(',',':'))+'\n',encoding='utf-8')
    OUT.mkdir(parents=True,exist_ok=True)
    (OUT/'recipe.json').write_text(json.dumps({'status':'pending_user_confirmation','scope':IDS,
        'motion':'Raise the hat independently, then move forward; return reverses the same motion. Hair stays on the body.',
        'carry':'Young entities around the brim ring (front, then sides, then back), facing outward; feet on the real brim top, whole bodies apart and clear of the hat and bow.',
        'exporter':'scripts/export_hat_interactions.py','exporter_sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest(),
        'runtime':target.relative_to(ROOT).as_posix(),'runtime_sha256':hashlib.sha256(target.read_bytes()).hexdigest(),
        'sources':fingerprints,'placement_fit':placement_fit.RESOURCE.relative_to(ROOT).as_posix(),
        'placement_fit_sha256':hashlib.sha256(placement_fit.RESOURCE.read_bytes()).hexdigest()},ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    print('Exported five hat roots, motion scales and supported seats;',len(result['children']),'child footprints.')
if __name__=='__main__':main()
