"""Capture current client-rendered wounds; assumes run_wound_checks.py preview is ready."""
import json,time
from pathlib import Path
import run_p0 as control
control.WORK=control.ROOT/'.local/wound-integration'
def request(op,**kwargs):return control.request('preview',op,timeout=60,**kwargs)
def view(name,x,y,z,yaw,pitch):
    request('preview',command='wound-view',x=x,y=y,z=z,yaw=yaw,pitch=pitch);time.sleep(.3)
    request('capture',name=name,hideGui=True);print(name,flush=True)
if __name__=='__main__':
    # Actual body yaw settles toward head yaw in this static no-AI fixture: front faces +Z.
    for variant,x in [('reimu',901),('marisa',906),('house_reimu',911),('house_marisa',916),('chen',921),('alice',926),('patchouli',931)]:
        house=variant.startswith('house_')
        view('wound-'+variant+'-front',x,64.05 if not house else 63.55,7.6 if not house else 6.9,180,10)
    view('wound-reimu-oblique',899.5,64.1,7.2,230,12)
    view('wound-reimu-back',901,64.2,3.7,0,12)
    request('preview',command='wound-cover',cover=False)
    view('wound-reimu-side-skin',898.8,64.1,6,270,8)
    view('wound-reimu-top-skin',901,66.2,6,180,90)
    request('preview',command='wound-contact',variant='reimu',pose='SUPINE')
    view('wound-reimu-bottom-skin',901,64.0,4.3,0,3)
    request('preview',command='wound-contact',variant='reimu',pose='NONE')
    view('wound-marisa-back-skin',906,64.1,3.8,0,10)
    view('wound-house-side-skin',910.2,63.55,6,270,5)
    view('wound-reimu-motion',899.5,64.2,7.5,225,16)
    for action in ['side_bend','stretch_shape','waddle','press']:
        request('preview',command='wound-animate',variant='reimu',action=action)
        request('record',name='wound-reimu-'+action,ticks=45,every=8,hideGui=True);print(action,flush=True)
    request('preview',command='wound-release',variant='reimu')
    request('record',name='wound-reimu-release',ticks=28,every=4,hideGui=True)
    server=request('preview',command='wound-state');client=request('snapshot')
    by_id={e['networkId']:e for e in client['entities']}
    checks=[{'variant':e['variant'],'pass':e['wounds']==by_id[e['id']]['wounds'] and e['filling']==by_id[e['id']]['filling']} for e in server]
    (control.WORK/'client-sync-checks.json').write_text(json.dumps(checks,ensure_ascii=False,indent=2))
    print('client sync',all(c['pass'] for c in checks),flush=True)
