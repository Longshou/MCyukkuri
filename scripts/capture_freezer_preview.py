"""Own one hidden client for real cabinet controls and vanilla notebook screenshots, then stop it."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
import uuid
from run_freezer_checks import WORK, freeze

def read(path):return json.loads(path.read_text(encoding='utf-8'))
def request(directory, action, own=False, timeout=40, **arguments):
    identifier=str(uuid.uuid4());prefix='freezer-' if own else ''
    path=directory/(prefix+'request.json');temporary=path.with_suffix('.tmp')
    value={'id':identifier,('action' if own else 'op'):action,**arguments}
    temporary.write_text(json.dumps(value),encoding='utf-8');temporary.replace(path)
    end=time.monotonic()+timeout;response=directory/(prefix+'response.json')
    while time.monotonic()<end:
        if response.exists():
            result=read(response)
            if result.get('id')==identifier:
                if not result.get('pass'):raise RuntimeError(result)
                return result
        time.sleep(.15)
    raise TimeoutError(action)

def main():
    snapshot,command=freeze('p0Preview','preview')
    evidence=read(WORK/'latest-test.json');source=Path(evidence['snapshot'])/'world'
    results=read(Path(evidence['report']))
    if not results or any(not check['pass'] for check in results):raise RuntimeError('Freezer rules must pass before preview')
    shutil.copytree(source,snapshot/'saves/p0-preview',ignore=shutil.ignore_patterns('session.lock'))
    (snapshot/'options.txt').write_text('lang:zh_cn\npauseOnLostFocus:false\nmaxFps:40\nrenderDistance:4\nsimulationDistance:4\nenableVsync:false\nonboardAccessibility:false\nskipMultiplayerWarning:true\nguiScale:2\n',encoding='utf-8')
    directory=snapshot/'p0-control';directory.mkdir(exist_ok=True)
    log=(snapshot/'client.log').open('wb')
    process=subprocess.Popen(command,cwd=snapshot,stdout=log,stderr=subprocess.STDOUT,stdin=subprocess.DEVNULL,
                             creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
    (WORK/'active-preview.json').write_text(json.dumps({'pid':process.pid,'snapshot':str(snapshot)},indent=2),encoding='utf-8')
    print('Started owned freezer client',process.pid,snapshot,flush=True)
    evidence=[]
    try:
        end=time.monotonic()+180
        while not (directory/'joined.json').exists():
            if process.poll() is not None:raise RuntimeError('Client stopped: '+str(process.returncode))
            if time.monotonic()>end:raise TimeoutError('Client join')
            if (directory/'started.json').exists():
                screen=request(directory,'screen-info')['result']['screen']
                if screen=='BackupConfirmScreen':request(directory,'preview-backup',button=0)
            time.sleep(2)
        evidence.append(request(directory,'setup',own=True));time.sleep(2)
        def capture(name,**extra):
            response=request(directory,'capture',name=name,ticks=1,hideGui=True,**extra)
            evidence.append(response);print('Captured',name,flush=True)
        for view in ('overview','front','side','back'):
            request(directory,'camera',own=True,view=view);time.sleep(.7);capture('freezer-'+view)
        request(directory,'camera',own=True,view='interact');time.sleep(.5)
        state=request(directory,'state',own=True);target=state['targetId']
        evidence.append(request(directory,'interact',target=target));time.sleep(.5)
        state=request(directory,'state',own=True);assert state['heldToken'] and state['targetId']==-1
        evidence.append(request(directory,'block-interact',x=3270,y=65,z=8));time.sleep(.5)
        state=request(directory,'state',own=True);assert state['stored']==1 and not state['heldToken'];evidence.append(state)
        request(directory,'break',own=True);assert request(directory,'state',own=True)['stored']==1
        request(directory,'rebuild',own=True)
        evidence.append(request(directory,'block-interact',x=3270,y=65,z=8));time.sleep(.5)
        state=request(directory,'state',own=True);assert state['stored']==0 and state['heldToken'];evidence.append(state)
        evidence.append(request(directory,'block-interact',x=3268,y=64,z=8));time.sleep(.5)
        state=request(directory,'state',own=True);assert state['targetId']!=-1 and not state['heldToken'];evidence.append(state)
        request(directory,'notebook',own=True);time.sleep(.5)
        evidence.append(request(directory,'use-item'));time.sleep(.5)
        # This client check measures every page with the actual selected font.
        pages=request(directory,'book-page',page=0);evidence.append(pages);capture('notebook-guide',keepScreen=True)
        request(directory,'book-page',page=13);capture('notebook-storage',keepScreen=True)
        request(directory,'close-screen');time.sleep(.25)
        evidence.append(request(directory,'interact',target=state['targetId']));time.sleep(.5)
        observation=request(directory,'book-page',page=0);evidence.append(observation);capture('notebook-observation',keepScreen=True)
        print('Real carrier capture / store / break / rebuild / retrieve / release and book checks passed',flush=True)
    finally:
        (snapshot/'client-evidence.json').write_text(json.dumps(evidence,ensure_ascii=False,indent=2),encoding='utf-8')
        if process.poll() is None:
            try:request(directory,'stop',timeout=15);process.wait(timeout=30)
            except Exception:process.terminate();process.wait(timeout=15)
        log.close()
        print('Stopped owned freezer client',process.pid,flush=True)

if __name__=='__main__':main()
