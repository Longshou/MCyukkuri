"""Freeze this task's isolated build and run only freezer GameTests; no normal server or EULA changes."""
import json
import os
from pathlib import Path
import shutil
import subprocess
import time
from run_p0 import ROOT, java_path

WORK = ROOT / '.local/p7-freezer-notebook'
BUILD = WORK / 'build'

def freeze(run, name):
    snapshot=WORK/f'{name}-{time.time_ns()}'
    snapshot.mkdir(parents=True)
    generated=BUILD/'moddev'
    folders=[]; replacements=[]
    for mod, source in (('mcyukkuri','main'),('mcyukkuri_p0','p0Test')):
        for category in ('classes/java','resources'):
            destination=snapshot/category/source
            shutil.copytree(BUILD/category/source,destination)
            folders.append(mod+'%%'+str(destination))
            replacements.append((str(BUILD/category/source),str(destination)))
    legacy=snapshot/(run+'LegacyClasspath.txt')
    replacements.append((str(generated/(run+'LegacyClasspath.txt')),str(legacy)))
    for suffix in ('RunClasspath.txt','RunVmArgs.txt','LegacyClasspath.txt','RunProgramArgs.txt'):
        contents=(generated/(run+suffix)).read_text(encoding='utf-8')
        for original,replacement in replacements:
            contents=contents.replace(original.replace('\\','\\\\'),replacement.replace('\\','\\\\')).replace(original,replacement)
        (snapshot/(run+suffix)).write_text(contents,encoding='utf-8')
    command=[str(java_path()),'@'+str(snapshot/(run+'RunClasspath.txt')),'@'+str(snapshot/(run+'RunVmArgs.txt')),
             '-Dfml.modFolders='+os.pathsep.join(folders),'-Dmcyukkuri.freezer.preview=true',
             'net.neoforged.devlaunch.Main','@'+str(snapshot/(run+'RunProgramArgs.txt'))]
    return snapshot,command

def main():
    snapshot,command=freeze('p0GameTest','gametest')
    with (snapshot/'acceptance.log').open('wb') as log:
        process=subprocess.run(command,cwd=snapshot,stdout=log,stderr=subprocess.STDOUT,stdin=subprocess.DEVNULL,
                               creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
    reports=snapshot/'p0-control/freezer-checks.json'
    (WORK/'latest-test.json').write_text(json.dumps({'snapshot':str(snapshot),'exit':process.returncode,'report':str(reports)},indent=2),encoding='utf-8')
    print('Freezer GameTest exit:',process.returncode,'snapshot:',snapshot)
    if reports.exists():
        results=json.loads(reports.read_text(encoding='utf-8'))
        print('Checks:',len(results),'failures:',[r for r in results if not r['pass']])
        if not results or any(not r['pass'] for r in results):raise SystemExit(1)
    else:raise SystemExit('No freezer report was produced; inspect acceptance.log')
    raise SystemExit(process.returncode)

if __name__=='__main__':main()
