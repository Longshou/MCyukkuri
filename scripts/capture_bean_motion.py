"""Record full waddle cycles from the current frozen Minecraft client and select requested phases."""
import json,time
from pathlib import Path
import run_rounded_preview as preview
def main():
    run=Path(json.loads((preview.WORK/'latest-preview.json').read_text())['run']);results=[]
    for ident in ['reimu_bean','marisa_bean']:
        for expression in ['neutral','happy','cry']:
            setup=preview.request('preview',command='rounded-setup',model=ident,state='idle',action='waddle',expression=expression,angle=0)
            assert setup['asset']==ident,setup
            time.sleep(2)
            name=f'{ident}-waddle-{expression}'
            capture=preview.request('record',name=name,ticks=36,every=1)
            data=json.loads((run/'p0-control'/capture['manifest']).read_text());frames=[]
            for shot in data['captures']:
                state=shot['state'];actor=next(e for e in state['entities']if e['networkId']==setup['id'])
                assert actor['action']=='waddle' and actor['expression']==expression,(ident,expression,actor)
                frames.append({'file':shot['file'],'phase':round(((state['tick']-actor['start'])/20)%1.2,5)})
            selected=[]
            for phase in [0,.1,.25,.5,.75,.9,1.1]:
                closest=min(frames,key=lambda f:min(abs(f['phase']-phase),1.2-abs(f['phase']-phase)))
                error=min(abs(closest['phase']-phase),1.2-abs(closest['phase']-phase));assert error<=.051,(ident,expression,phase,error)
                selected.append({'requested':phase,'tick_phase':closest['phase'],'file':closest['file']})
            results.append({'id':ident,'expression':expression,'frames':len(frames),'selected':selected,'manifest':capture['manifest']})
            (run/'bean-motion.json').write_text(json.dumps({'sampling':'Actual framebuffer, 20 Hz tick phase; render interpolation can add less than .05 seconds.','cases':results},indent=2),encoding='utf-8')
            print(ident,expression,len(frames),'frames',flush=True)
if __name__=='__main__':main()
