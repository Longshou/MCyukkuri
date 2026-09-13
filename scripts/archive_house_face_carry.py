"""Keep actual house-travel frames, phase samples and trace evidence for visual review."""
import hashlib,json,math,shutil
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'docs/validation/house-face-carry/current'
def read(p):return json.loads(p.read_text(encoding='utf-8'))
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    run=Path(read(ROOT/'.local/personality-deibu/latest-preview.json')['run'])
    cases=read(run/'house-face-carry.json');assert len(cases)==36
    (OUT/'frames').mkdir(parents=True,exist_ok=True);rows=[];total=0
    for case in cases:
        data=read(run/'p0-control'/case['capture']['manifest']);captures=data['captures'];total+=len(captures)
        target=case['setup']['entity']
        def entity(c):return next(e for e in c['state']['entities']if e['networkId']==target)
        valid=[c for c in captures if entity(c)['action']=='waddle']
        assert len(valid)>=15,(case['id'],case['mode'],len(valid))
        for c in valid:
            e=entity(c);assert e['expression']==case['expression'],e
            assert ('food_scrap'in e['carrying'])==case['carry'],e
        trace=[t for t in case['trace']['samples']if t['action']=='waddle']
        assert len(trace)>=20 and trace[0]['z']-trace[-1]['z']>.06,case
        deltas=[trace[i-1]['z']-trace[i]['z']for i in range(1,len(trace))]
        assert all(abs(d-case['speed'])<1e-5 for d in deltas),deltas
        phase=lambda c:((c['state']['tick']-entity(c)['start'])/20)%1.2
        chosen=[]
        for goal in [0,.3,.6,.9]:
            best=min(valid,key=lambda c:min(abs(phase(c)-goal),1.2-abs(phase(c)-goal)))
            source=Path(best['file']);target_file=OUT/'frames'/source.name;shutil.copy2(source,target_file)
            chosen.append({'file':'frames/'+source.name,'sha256':sha(target_file),'phase':phase(best),'state':best['state'],'client_tick':best['clientTick']})
        rows.append({**{k:v for k,v in case.items()if k not in ['trace','capture']},'captured_frames':len(captures),'walking_frames':len(valid),
            'trace':trace,'selected':chosen,'distance':trace[0]['z']-trace[-1]['z'],'constant_requested_speed_verified':True})
    font=ImageFont.truetype('C:/Windows/Fonts/arial.ttf',15)
    sheets=[]
    for ident in ['house_reimu','house_marisa']:
        for mode in ['walk','slow','carry']:
            group=[r for r in rows if r['id']==ident and r['mode']==mode]
            canvas=Image.new('RGB',(1152,6*316),'#f3efe6');draw=ImageDraw.Draw(canvas)
            for y,row in enumerate(group):
                for x,frame in enumerate(row['selected']):
                    im=Image.open(OUT/frame['file']).crop((390,95,890,605));im.thumbnail((288,288));canvas.paste(im,(x*288,y*316+28))
                    draw.text((x*288+4,y*316+4),f"{row['expression']} {row['angle']}deg t={frame['phase']:.2f}",fill='#222222',font=font)
            path=OUT/f'{ident}-{mode}.png';canvas.save(path);sheets.append(path.name)
    report={'status':'ready_for_visual_review','cases':len(rows),'captured_frames':total,'selected_samples':sum(len(r['selected'])for r in rows),
        'client_run':run.relative_to(ROOT).as_posix(),'sheets':sheets,'checks':rows,
        'scope':'Two current house models; actual physics selects waddle at 0.02 and 0.004 blocks/tick. No playAction used.'}
    (OUT/'visual.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
    print(json.dumps({k:report[k]for k in ['cases','captured_frames','selected_samples','sheets']}))
if __name__=='__main__':main()
