"""Targeted real-movement captures for house skin lighting, on the frozen preview."""
import json,time
from pathlib import Path
import run_rounded_preview as preview
def main():
    run=Path(json.loads((preview.WORK/'latest-preview.json').read_text())['run']);results=[]
    cases=[('walk','neutral',.02,False,0),('walk','neutral',.02,False,35),
           ('carry','neutral',.02,True,0),('carry','neutral',.02,True,35),
           ('walk','happy',.02,False,0),('walk','cry',.02,False,0),('slow','neutral',.004,False,0)]
    for ident in ['house_reimu','house_marisa']:
        for mode,expression,speed,carry,angle in cases:
            name=f'surface-{ident}-{expression}-{mode}-{angle}'
            setup=preview.request('preview',command='waddle-setup',variant=ident,expression=expression,speed=speed,carry=carry,angle=angle)
            time.sleep(.75)
            capture=preview.request('record',name=name,ticks=30,every=1)
            trace=preview.request('preview',command='waddle-setup',trace=True)
            results.append(dict(id=ident,mode=mode,expression=expression,speed=speed,carry=carry,angle=angle,setup=setup,capture=capture,trace=trace))
            (run/'house-surface.json').write_text(json.dumps(results,indent=2),encoding='utf-8');print(name,capture['count'],flush=True)
if __name__=='__main__':main()
