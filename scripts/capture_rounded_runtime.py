"""Capture current nine models and affected states from the isolated running Minecraft framebuffer."""
import json,time
from pathlib import Path
import run_rounded_preview as preview
IDS=['reimu','marisa','chen','alice','patchouli','marisa_tsumuri','reimu_wasa','reimu_deibu','reimu_wasa_deibu']
def main():
    run=Path(json.loads((preview.WORK/'latest-preview.json').read_text())['run']);results=[]
    cases=[(ident,'idle',angle)for ident in IDS for angle in [0,35,90,180]]
    cases += [('reimu_deibu',state,35)for state in ['pregnant','stem','wound','badge','FIXED','BOX','bare']]
    cases += [('reimu_wasa_deibu','wound',35),('patchouli','FIXED',35),('marisa_tsumuri','BOX',35)]
    for ident,state,angle in cases:
        name=f'{ident}-{state}-{angle}'
        try:
            setup=preview.request('preview',command='rounded-setup',model=ident,state=state,angle=angle)
            time.sleep(2)
            capture=preview.request('capture',name=name,ticks=5,every=5)
            results.append({'name':name,'pass':True,'setup':setup,'capture':capture})
        except Exception as error:results.append({'name':name,'pass':False,'error':str(error)})
        (run/'capture-summary.json').write_text(json.dumps(results,ensure_ascii=False,indent=2),encoding='utf-8')
        print(name,results[-1]['pass'],flush=True)
    if not all(r['pass']for r in results):raise SystemExit(1)
if __name__=='__main__':main()
