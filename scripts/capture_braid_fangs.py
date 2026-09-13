"""Targeted Minecraft frames for released bean braids, hat seating and predator teeth."""
import json,time
from pathlib import Path
import run_rounded_preview as preview
def main():
    run=Path(json.loads((preview.WORK/'latest-preview.json').read_text())['run']);results=[]
    cases=[(i,e,a,'idle','waddle')for i in ['reimu_bean','marisa_bean']for e,a in [('neutral',0),('neutral',90),('neutral',135),('cry',0)]]
    cases += [(i,e,a,'idle','idle')for i in ['remilia','flandre']for e in ['neutral','happy','biting']for a in [0,35]]
    cases += [(i,'neutral',35,state,'idle')for i in ['reimu_bean','marisa_bean']for state in ['bare','FIXED']]
    for ident,expression,angle,state,action in cases:
        setup=preview.request('preview',command='rounded-setup',model=ident,state=state,action=action,expression=expression,angle=angle)
        assert setup['asset']==ident,setup
        time.sleep(2);name=f'{ident}-{expression}-{angle}-{state}-{action}'
        capture=preview.request('record'if action=='waddle'else'capture',name=name,ticks=36 if action=='waddle'else 1,every=1)
        results.append({'id':ident,'expression':expression,'angle':angle,'state':state,'action':action,'setup':setup,'capture':capture})
        (run/'braid-fangs.json').write_text(json.dumps(results,indent=2),encoding='utf-8');print(name,capture['count'],flush=True)
if __name__=='__main__':main()
