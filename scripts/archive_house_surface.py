"""Archive matched Minecraft skin-lighting phases without changing captured pixels."""
import hashlib,json,shutil
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'docs/validation/house-surface/current'
def read(p):return json.loads(p.read_text(encoding='utf-8'))
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def main():
    run=ROOT/read(OUT/'release.json')['client_run'];cases=read(run/'house-surface.json');assert len(cases)==14
    (OUT/'frames').mkdir(parents=True,exist_ok=True);rows=[];datasets={};total=0
    def phase(c,target):
        e=next(e for e in c['state']['entities']if e['networkId']==target)
        return ((c['state']['tick']-e['start'])/20)%1.2
    def distance(a,b):return min(abs(a-b),1.2-abs(a-b))
    for case in cases:
        data=read(run/'p0-control'/case['capture']['manifest'])['captures'];total+=len(data);target=case['setup']['entity']
        data=[c for c in data if next(e for e in c['state']['entities']if e['networkId']==target)['action']=='waddle'];assert len(data)>=15
        trace=[t for t in case['trace']['samples']if t['action']=='waddle'];assert len(trace)>=20
        assert all(abs(trace[i-1]['z']-trace[i]['z']-case['speed'])<1e-5 for i in range(1,len(trace)))
        selected=[]
        for goal in [0,.3,.6,.9]:
            c=min(data,key=lambda c:distance(phase(c,target),goal));file=Path(c['file']);dest=OUT/'frames'/file.name;shutil.copy2(file,dest)
            selected.append({'file':'frames/'+file.name,'sha256':sha(dest),'phase':phase(c,target),'state':c['state']})
        rows.append({**{k:v for k,v in case.items()if k not in ['capture','trace']},'captured_frames':len(data),'trace':trace,'selected':selected})
        if case['mode']=='walk'and case['expression']=='neutral'and case['angle']==0:datasets[case['id']]=(data,target)
    font=ImageFont.truetype('C:/Windows/Fonts/arial.ttf',15)
    for ident in ['house_reimu','house_marisa']:
        canvas=Image.new('RGB',(1152,7*316),'#f2efe7');draw=ImageDraw.Draw(canvas)
        for row,case in enumerate(r for r in rows if r['id']==ident):
            for col,frame in enumerate(case['selected']):
                im=Image.open(OUT/frame['file']).crop((390,95,890,605)).resize((282,288));canvas.paste(im,(col*288,row*316+28))
                draw.text((col*288+4,row*316+4),f"{case['mode']} {case['expression']} {case['angle']} t={frame['phase']:.2f}",font=font,fill='#222222')
        canvas.save(OUT/f'{ident}.png')
    old=ROOT/'.local/personality-deibu/preview-tdzmjt8l';old_cases=read(old/'house-face-carry.json');old_data={}
    for ident in datasets:
        case=next(c for c in old_cases if c['id']==ident and c['mode']=='walk'and c['expression']=='neutral'and c['angle']==0)
        old_data[ident]=(read(old/'p0-control'/case['capture']['manifest'])['captures'],case['setup']['entity'])
    font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',18);frames=[];sources=[]
    for tick in range(24):
        canvas=Image.new('RGB',(640,700),'#f2efe7');draw=ImageDraw.Draw(canvas)
        for row,ident in enumerate(datasets):
            for col,(data,target)in enumerate([old_data[ident],datasets[ident]]):
                c=min(data,key=lambda c:distance(phase(c,target),tick*.05));source=Path(c['file'])
                im=Image.open(source).crop((390,95,890,605)).resize((320,326));canvas.paste(im,(col*320,row*350+24))
                name='家灵梦'if row==0 else'家魔理沙';draw.text((col*320+10,row*350),name+(' · 修前'if col==0 else' · 修后'),font=font,fill='#222222')
                sources.append({'id':ident,'revision':'before'if col==0 else'after','phase':phase(c,target),'goal':tick*.05,'file':source.relative_to(ROOT).as_posix(),'sha256':sha(source)})
        frames.append(canvas)
    frames[18].save(OUT/'comparison.png');frames[0].save(OUT/'comparison.gif',save_all=True,append_images=frames[1:],duration=50,loop=0,optimize=False)
    report={'status':'pending_visual_review','cases':14,'captured_frames':total,'selected_samples':56,'checks':rows,'comparison':'Nearest actual waddle phase pairing; same geometry and texture resources; no pixels repainted.','comparison_sources':sources}
    (OUT/'visual.json').write_text(json.dumps(report,indent=2),encoding='utf-8');print(json.dumps({k:report[k]for k in ['cases','captured_frames','selected_samples']}))
if __name__=='__main__':main()
