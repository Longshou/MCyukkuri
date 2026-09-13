"""Freeze isolated outputs and launch joint acceptance; never changes EULA consent."""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
import re
from pathlib import Path
import shutil
import subprocess
import sys

from run_p0 import ROOT, java_path

WORK = ROOT / '.local/port-integration'
BUILD = WORK / 'build'
NAMES = {'gametest':'p0GameTest','server':'p0Server','client-a':'p0ClientA','client-b':'p0ClientB'}
REQUIRED_REPORTS = set('badge-checks cognition-checks consumable-birth-checks consumable-live-birth-checks consumable-live-checks consumable-morph-checks consumable-rule-checks core-checks ecology-live ecology-rules family-checks filling-checks freezer-checks group-live-checks group-rule-checks house-motion-checks human-expansion-checks human-world-checks instrument-checks integration-event-checks living-checks operation-checks playground-live-checks playground-rule-checks predator-checks server-checks settlement-checks settlement-movement-checks shelter-checks shelter-resource-checks subspecies-checks waddle-checks wild-event-checks wound-checks'.split())

def write(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')

def hashes(folder):
    return {p.relative_to(folder).as_posix():hashlib.sha256(p.read_bytes()).hexdigest()
            for p in sorted(folder.rglob('*')) if p.is_file()}

def freeze(activate=True):
    if 'BUILD SUCCESSFUL' not in (WORK/'build.log').read_text(encoding='utf-8-sig'):
        raise RuntimeError('Latest isolated build must succeed before freezing.')
    run = WORK / datetime.now(timezone.utc).strftime('runtime-%Y%m%d-%H%M%S')
    run.mkdir()
    for folder in ('classes','resources','moddev','libs'):
        shutil.copytree(BUILD/folder,run/folder)
    for p in (run/'moddev').glob('*.txt'):
        content=p.read_text(encoding='utf-8')
        content=content.replace(str(BUILD).replace('\\','\\\\'),str(run).replace('\\','\\\\')).replace(str(BUILD),str(run))
        for spelling in (str(ROOT/'build'),str(ROOT/'build').replace('\\','\\\\'),(ROOT/'build').as_posix()):
            if spelling in content: raise RuntimeError(f'Root build leaked: {p}')
        p.write_text(content,encoding='utf-8')
    write(run/'source-manifest.json',hashes(ROOT/'src'))
    write(run/'runtime-manifest.json',{k:hashes(run/k) for k in ('classes','resources','libs')})
    shutil.copy2(WORK/'isolation.json',run/'isolation.json')
    shutil.copy2(WORK/'build.log',run/'build.log')
    write(WORK/('latest.json' if activate else 'latest-gametest.json'),{'runtime':str(run)})
    print(run)

def launch(target, run=None):
    run=Path(run) if run else Path(json.loads((WORK/'latest.json').read_text())['runtime'])
    directory=run/'runs'/target
    if target=='gametest' and directory.exists():
        raise RuntimeError('This frozen run already has a GameTest world; freeze a new runtime for a fresh joint run.')
    directory.mkdir(parents=True,exist_ok=True)
    expected=json.loads((run/'runtime-manifest.json').read_text())
    for category,manifest in expected.items():
        if hashes(run/category)!=manifest: raise RuntimeError(f'Frozen {category} changed since build')
    control=directory/'p0-control'
    control.mkdir(exist_ok=True)
    for marker in ('request.json','response.json','ready.json','started.json','joined.json'):
        (control/marker).unlink(missing_ok=True)
    if target=='server':
        eula=ROOT/'.local/p0/server/eula.txt'
        if 'eula=true' not in eula.read_text().splitlines(): raise RuntimeError('Existing user EULA acceptance is required.')
        shutil.copy2(eula,directory/'eula.txt')
        if not (directory/'server.properties').exists():
            original=(ROOT/'.local/p0/server/server.properties').read_text()
            original=original.replace('25586','25619')
            (directory/'server.properties').write_text(original,encoding='utf-8')
        import socket
        with socket.socket() as sock: sock.bind(('127.0.0.1',25619))
    elif target.startswith('client'):
        (directory/'options.txt').write_text('lang:zh_cn\npauseOnLostFocus:false\nmaxFps:30\nrenderDistance:4\nsimulationDistance:5\nenableVsync:false\nonboardAccessibility:false\nskipMultiplayerWarning:true\nautoJump:false\n',encoding='utf-8')
    name=NAMES[target]
    folders=[mod+'%%'+str(run/category/source) for mod,source in [('mcyukkuri','main'),('mcyukkuri_p0','p0Test')] for category in ('classes/java','resources')]
    command=[str(java_path()),'@'+str(run/'moddev'/f'{name}RunClasspath.txt'),
             '@'+str(run/'moddev'/f'{name}RunVmArgs.txt'),'-Dfml.modFolders='+os.pathsep.join(folders),
             'net.neoforged.devlaunch.Main','@'+str(run/'moddev'/f'{name}RunProgramArgs.txt')]
    logpath=directory/datetime.now(timezone.utc).strftime('console-%Y%m%d-%H%M%S.log')
    with logpath.open('wb') as log:
        process=subprocess.Popen(command,cwd=directory,stdin=subprocess.DEVNULL,stdout=log,stderr=subprocess.STDOUT,
                                 creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
    record={'pid':process.pid,'directory':str(directory),'log':str(logpath),'command':command}
    write(run/f'{target}-process.json',record)
    write(run/'process-history'/f'{target}-{process.pid}.json',record)
    print(json.dumps(record,indent=2))
    if target=='gametest':
        exit_code=process.wait()
        reports={}
        for path in (directory/'p0-control').glob('*.json'):
            rows=json.loads(path.read_text())
            if isinstance(rows,list) and rows and all(isinstance(r,dict) and 'pass' in r for r in rows):
                reports[path.stem]={'count':len(rows),'failed':[r for r in rows if not r['pass']]}
        result={'exit':exit_code,'reports':reports,'count':sum(r['count'] for r in reports.values()),
                'failed':sum(len(r['failed']) for r in reports.values()),'missingReports':sorted(REQUIRED_REPORTS-reports.keys())}
        completed=re.search(r'(\d+) GAME TESTS COMPLETE',logpath.read_text(encoding='utf-8',errors='replace'))
        result['gameTests']=int(completed.group(1)) if completed else 0
        write(run/'gametest-summary.json',result)
        print(json.dumps(result,indent=2))
        sys.exit(0 if exit_code==0 and result['failed']==0 and result['gameTests']==36 and not result['missingReports'] else 1)

if __name__=='__main__':
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation',choices=['freeze',*NAMES])
    parser.add_argument('--no-activate',action='store_true',help='Keep the active network runtime unchanged when freezing final GameTests')
    parser.add_argument('--runtime',type=Path,help='Explicit frozen runtime for this launch')
    args=parser.parse_args()
    freeze(not args.no_activate) if args.operation=='freeze' else launch(args.operation,args.runtime)
