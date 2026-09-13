"""Record real house travel, expressions and carried food in the isolated preview."""
import json,time
from pathlib import Path
import run_rounded_preview as preview

def main():
    run=Path(json.loads((preview.WORK/'latest-preview.json').read_text())['run']);results=[]
    for ident in ['house_reimu','house_marisa']:
        for expression in ['neutral','happy','cry']:
            for mode,speed,carry in [('walk',.02,False),('slow',.004,False),('carry',.02,True)]:
                for angle in [0,35]:
                    name=f'{ident}-{expression}-{mode}-{angle}'
                    setup=preview.request('preview',command='waddle-setup',variant=ident,expression=expression,speed=speed,carry=carry,angle=angle)
                    time.sleep(.65)
                    capture=preview.request('record',name=name,ticks=28,every=1)
                    trace=preview.request('preview',command='waddle-setup',trace=True)
                    results.append(dict(id=ident,expression=expression,mode=mode,speed=speed,carry=carry,angle=angle,setup=setup,capture=capture,trace=trace))
                    (run/'house-face-carry.json').write_text(json.dumps(results,indent=2),encoding='utf-8')
                    print(name,capture['count'],flush=True)
    print('Completed',len(results),'cases',flush=True)
if __name__=='__main__':main()
