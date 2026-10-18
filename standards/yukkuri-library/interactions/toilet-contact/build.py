"""Export this contact/defecation clip family only. No Java, game or UI launch."""
from pathlib import Path
import argparse, hashlib, json, math, shutil, sys
import numpy as np
from PIL import Image
HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[3]
sys.path.insert(0,str(ROOT/'scripts'))
import placement_fit
RESOURCE=ROOT/'src/main/resources/assets/mcyukkuri/yukkuri_presentation/toilet_contact.json'
SELECTED=ROOT/'art/source/toilet-contact'
def encode(data):return json.dumps(data,ensure_ascii=False,separators=(',',':'))+'\n'
def sample(keys,t):
    a,b=keys[0],keys[-1]
    for i in range(1,len(keys)):
        if t<=keys[i][0]:a,b=keys[i-1],keys[i];break
    q=max(0,min(1,(t-a[0])/(b[0]-a[0])));q=q*q*(3-2*q)
    return np.array(a[1:])+(np.array(b[1:])-a[1:])*q
def anus_site(model,raw):
    """Centre of the rear rump mark (the anus, 油菊) in rest-pose model pixels. Forms without the mark use the rear body surface at the same height."""
    marks=[p for p in model['parts'] if p['name']=='rounded_rump_mark']
    if marks:return np.concatenate([raw[p['start']:p['start']+p['count'],:3] for p in marks]).mean(axis=0)
    body=np.concatenate([raw[p['start']:p['start']+p['count'],:3] for p in model['parts'] if p['name'].endswith('body_surface')])
    height=body[:,1].max();band=body[(body[:,1]>=.18*height)&(body[:,1]<=.30*height)];rear=band[:,2].max()
    return np.array([0.,float(band[:,1].mean()),float(rear)])
def outlet(model,raw,pivot,entry):
    """Where the stool leaves the body: the anus site carried by the root track of the straining clip, tick by tick.
    Positions are blocks above the feet and behind the body centre at scale 1 (+z is the rear, as in the model)."""
    site=anus_site(model,raw);points=np.unique(np.concatenate([raw[p['start']:p['start']+p['count'],:3] for p in model['parts'] if not p.get('anatomy') and not p['name'].startswith('puff_')]),axis=0)
    floor=points[:,1].min();track=[]
    for tick in range(entry['duration_ticks']+1):
        pose=sample(entry['keys'],tick);x,y,z=np.radians(pose[:3]);scale=pose[3:]
        cx,sx,cy,sy,cz,sz=math.cos(x),math.sin(x),math.cos(y),math.sin(y),math.cos(z),math.sin(z)
        rot=np.array([[cz*cy,cz*sy*sx-sz*cx,cz*sy*cx+sz*sx],[sz*cy,sz*sy*sx+cz*cx,sz*sy*cx-cz*sx],[-sy,cy*sx,cy*cx]])
        support=floor-((points-pivot)@(rot*scale)[1]+pivot[1]).min()
        moved=rot@((site-pivot)*scale)+pivot+np.array([0,support,0])
        track.append([round(float(v)/16,5) for v in moved])
    return {'site_px':[round(float(v),4) for v in site],'track':track}
def build():
    cfg=json.loads((HERE/'recipe.json').read_text(encoding='utf8'));fits=placement_fit.load()
    ids=json.loads((ROOT/'src/main/resources/assets/mcyukkuri/yukkuri_presentation/motion_corrections.json').read_text(encoding='utf8'))['models']
    out={'format':1,'source':str((HERE/'recipe.json').relative_to(ROOT)).replace('\\','/'),'tongue':cfg['tongue'],'models':{}}
    for ident in ids:
        model=placement_fit.fitted(ROOT/f'src/main/resources/assets/mcyukkuri/yukkuri/{ident}.json',fits)
        root=next(i for i,b in enumerate(model['bones']) if b['name']=='root');pivot=np.array(model['bones'][root]['pivot'])
        raw=np.array(model['vertices']).reshape(-1,8)
        points=np.unique(np.concatenate([raw[p['start']:p['start']+p['count'],:3] for p in model['parts'] if not p.get('anatomy') and not p['name'].startswith('puff_')]),axis=0)
        floor=points[:,1].min();clips={}
        def resolve(face):
            for f in [face]+cfg['face_fallback'].get(face,[])+['neutral']:
                if (ROOT/f'src/main/resources/assets/mcyukkuri/textures/entity/{ident}/{f}.png').exists():return f
            raise ValueError('No expression texture '+ident)
        for name,entry in cfg['clips'].items():
            assert entry['keys'][0][0]==0 and entry['keys'][-1][0]==entry['duration_ticks']
            if entry['loop']:assert entry['keys'][0][1:]==entry['keys'][-1][1:]
            tracks=[{'bone':root,'channel':ch,'keys':[]} for ch in ['position','rotation','scale']]
            for tick in range(entry['duration_ticks']+1):
                pose=sample(entry['keys'],tick);rotation=pose[:3];scale=pose[3:]
                x,y,z=np.radians(rotation);cx,sx,cy,sy,cz,sz=math.cos(x),math.sin(x),math.cos(y),math.sin(y),math.cos(z),math.sin(z)
                row=np.array([sz*cy,sz*sy*sx+cz*cx,sz*sy*cx-cz*sx])*scale
                support=floor-((points-pivot)@row+pivot[1]).min()
                assert np.isfinite(pose).all() and math.isfinite(support)
                for track,values in zip(tracks,([0,support,0],rotation,scale)):
                    track['keys'].append([tick/20,*[round(float(v),7) for v in values]])
            spans=[[a/20,b/20,resolve(f)] for a,b,f in entry['faces']]
            assert spans[0][0]==0 and spans[-1][1]==entry['duration_ticks']/20
            clips[name]={'duration':entry['duration_ticks']/20,'loop':entry['loop'],'tracks':tracks,'expression_spans':spans,'expression_yield':cfg['expression_yield']}
        out['models'][ident]={'bone_names':[b['name'] for b in model['bones']],'clips':clips,'outlet':outlet(model,raw,pivot,cfg['clips']['ground_toilet'])}
    return out
if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('--check',action='store_true');args=parser.parse_args()
    result=build();content=encode(result)
    tongue=result['tongue'];assert tongue['placement']=='face_plane' and 'thickness' not in tongue
    assert 0<=tongue['face_offset']<=.005 and tongue['profile'][0][0]==0 and tongue['profile'][-1][0]==1
    assert all(0<w<=1 for t,w in tongue['profile'])
    assert all(a[0]<b[0] for a,b in zip(tongue['profile'],tongue['profile'][1:]))
    if args.check:
        assert RESOURCE.read_text(encoding='utf8')==content,'Runtime differs from source'
        assert (SELECTED/'recipe.json').read_bytes()==(HERE/'recipe.json').read_bytes(),'Selected snapshot differs'
        texture=Image.open(ROOT/'src/main/resources/assets/mcyukkuri/textures/entity/care_tongue.png').convert('RGBA')
        assert texture.size==(16,16)
        assert texture.getpixel((8,0))==tuple(result['tongue']['tip_rgba'])
        assert texture.getpixel((8,8))==tuple(result['tongue']['rgba'])
    else:
        RESOURCE.write_text(content,encoding='utf8',newline='\n');SELECTED.mkdir(parents=True,exist_ok=True)
        shutil.copyfile(HERE/'recipe.json',SELECTED/'recipe.json')
        texture=Image.new('RGBA',(16,16),tuple(result['tongue']['rgba']))
        for y in range(16):
            for x in range(16):
                if y<4:texture.putpixel((x,y),tuple(result['tongue']['tip_rgba']))
        texture.save(ROOT/'src/main/resources/assets/mcyukkuri/textures/entity/care_tongue.png')
        manifest={'status':'pending_user_review','source':str(HERE.relative_to(ROOT)).replace('\\','/'),'runtime':str(RESOURCE.relative_to(ROOT)).replace('\\','/'),'sha256':hashlib.sha256(RESOURCE.read_bytes()).hexdigest(),'models':list(result['models']),'clips':list(next(iter(result['models'].values()))['clips'])}
        (SELECTED/'manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2)+'\n',encoding='utf8')
    print(('Verified' if args.check else 'Exported')+f" {len(result['models'])} rigs x 7 clips; grounded samples, expression files and loop seams checked.")
