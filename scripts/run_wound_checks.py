"""Wound-only GameTests / isolated client, using the installed JDK and generated arguments."""
import argparse,json,os,shutil,subprocess,sys,time,gzip,hashlib,zipfile
from pathlib import Path
from run_p0 import ROOT,java_path

WORK=ROOT/'.local/wound-integration'
def launch(target,snapshot=None):
    run='p0GameTest' if target=='gametest' else 'p0Preview'
    work=WORK/target;work.mkdir(parents=True,exist_ok=True)
    control=work/'p0-control';control.mkdir(exist_ok=True)
    for name in ['request.json','response.json','ready.json','joined.json','started.json']:(control/name).unlink(missing_ok=True)
    generated=ROOT/'build/moddev'
    if target=='preview':
        world=work/'saves/p0-preview'
        if not world.exists():shutil.copytree(ROOT/'.local/p0/gametest/world',world,ignore=shutil.ignore_patterns('session.lock'))
        shutil.copy2(ROOT/'.local/p0/preview/options.txt',work/'options.txt')
    # Freeze this launch's outputs so another authorized task's Gradle build cannot replace them mid-scan.
    runtime=work/'runtime';runtime.mkdir(exist_ok=True)
    jar=Path(snapshot)/'mcyukkuri.jar' if snapshot else next((ROOT/'build/libs').glob('*.jar'));shutil.copy2(jar,runtime/'mcyukkuri.jar')
    with zipfile.ZipFile(runtime/'mcyukkuri.jar') as packed:
        for variant in ['reimu','marisa','chen','alice','patchouli','house_reimu','house_marisa']:
            body=packed.read('assets/mcyukkuri/yukkuri/'+variant+'.json')
            wound=json.loads(gzip.decompress(packed.read('assets/mcyukkuri/yukkuri_wounds/'+variant+'.json.gz')))
            if hashlib.sha256(body).hexdigest()!=wound['runtimeSourceHash']:raise RuntimeError('Re-export wounds and rebuild: incompatible packaged '+variant)
    fixture=runtime/'p0'
    if snapshot:shutil.copytree(Path(snapshot)/'p0',fixture,dirs_exist_ok=True)
    else:shutil.copytree(ROOT/'build/classes/java/p0Test',fixture,dirs_exist_ok=True);shutil.copytree(ROOT/'build/resources/p0Test',fixture,dirs_exist_ok=True)
    folders=['mcyukkuri%%'+str(runtime/'mcyukkuri.jar'),'mcyukkuri_p0%%'+str(fixture)]
    command=[str(java_path()),'@'+str(generated/(run+'RunClasspath.txt')),'@'+str(generated/(run+'RunVmArgs.txt'))]
    if target=='gametest':command+=['-Dmcyukkuri.p0.suite=wounds']
    command+=['-Dfml.modFolders='+os.pathsep.join(folders),'net.neoforged.devlaunch.Main','@'+str(generated/(run+'RunProgramArgs.txt'))]
    with (work/'launch.log').open('wb') as log:
        process=subprocess.Popen(command,cwd=work,stdin=subprocess.DEVNULL,stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
    record={'pid':process.pid,'target':target,'log':str(work/'launch.log')};(work/'process.json').write_text(json.dumps(record,indent=2));print(json.dumps(record),flush=True)
    if target=='gametest':
        result=process.wait();print('GameTest exit',result);raise SystemExit(result)
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('target',choices=['gametest','preview']);parser.add_argument('--snapshot',type=Path);args=parser.parse_args();launch(args.target,args.snapshot)
