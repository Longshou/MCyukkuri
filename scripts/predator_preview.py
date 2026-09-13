"""Isolated predator preview control: prepare a frozen build, launch, then issue P0 requests."""
from pathlib import Path
import argparse,json,os,shutil,subprocess,time,uuid,hashlib
from run_p0 import ROOT,java_path
BASE=ROOT/'.local/predators';WORK=BASE/'preview'
def prepare():
    if 'BUILD SUCCESSFUL' not in (BASE/'build.log').read_text(encoding='utf-8-sig'):
        raise RuntimeError('The latest isolated build must succeed before freezing a client.')
    build=BASE/'build';frozen=BASE/('preview-runtime-'+str(int(time.time())))
    replacements=[];folders=[]
    for mod,source in [('mcyukkuri','main'),('mcyukkuri_p0','p0Test')]:
        for cat in ['classes/java','resources']:
            old=build/cat/source;new=frozen/cat/source;shutil.copytree(old,new);replacements.append((str(old),str(new)));folders.append(mod+'%%'+str(new))
    generated=build/'moddev';run='p0Preview'
    replacements.append((str(generated/(run+'LegacyClasspath.txt')),str(frozen/(run+'LegacyClasspath.txt'))))
    for suffix in ['RunClasspath.txt','RunVmArgs.txt','RunProgramArgs.txt','LegacyClasspath.txt']:
        s=(generated/(run+suffix)).read_text(encoding='utf-8')
        for old,new in replacements:s=s.replace(old.replace('\\','\\\\'),new.replace('\\','\\\\')).replace(old,new).replace(old.replace('\\','/'),new.replace('\\','/'))
        (frozen/(run+suffix)).write_text(s,encoding='utf-8')
    (frozen/'manifest.json').write_text(json.dumps({p.relative_to(frozen).as_posix():hashlib.sha256(p.read_bytes()).hexdigest() for p in frozen.rglob('*') if p.is_file()},indent=2))
    world=WORK/'saves/p0-preview'
    if not world.exists():
        passed=[]
        for p in sorted((BASE/'predators').glob('*/p0-control/predator-checks.json'),reverse=True):
            if all(c['pass'] for c in json.loads(p.read_text())):passed.append(p.parent.parent/'world')
        if not passed:raise RuntimeError('A passing predator GameTest world is required.')
        shutil.copytree(passed[0],world,ignore=shutil.ignore_patterns('session.lock'))
    (WORK/'p0-control').mkdir(exist_ok=True)
    (WORK/'options.txt').write_text('lang:zh_cn\npauseOnLostFocus:false\nmaxFps:60\nrenderDistance:4\nsimulationDistance:5\nenableVsync:false\nonboardAccessibility:false\nskipMultiplayerWarning:true\nautoJump:false\nguiScale:2\n',encoding='utf-8')
    (BASE/'preview-snapshot.json').write_text(json.dumps({'path':str(frozen),'folders':folders}))
    print(frozen)
def launch():
    state=json.loads((BASE/'preview-snapshot.json').read_text());frozen=Path(state['path']);run='p0Preview'
    cmd=[str(java_path()),'@'+str(frozen/(run+'RunClasspath.txt')),'@'+str(frozen/(run+'RunVmArgs.txt')),
         '-Dfml.modFolders='+os.pathsep.join(state['folders']),'net.neoforged.devlaunch.Main','@'+str(frozen/(run+'RunProgramArgs.txt'))]
    for name in ['request.json','response.json','started.json','joined.json']:(WORK/'p0-control'/name).unlink(missing_ok=True)
    log=WORK/('launch-'+str(int(time.time()))+'.log')
    with log.open('wb') as f:p=subprocess.Popen(cmd,cwd=WORK,stdin=subprocess.DEVNULL,stdout=f,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
    state={'pid':p.pid,'log':str(log)};(WORK/'process.json').write_text(json.dumps(state));print(json.dumps(state))
def request(op,**parameters):
    data={'id':str(uuid.uuid4()),'op':op,**parameters};control=WORK/'p0-control';tmp=control/'request.tmp';tmp.write_text(json.dumps(data));tmp.replace(control/'request.json')
    deadline=time.monotonic()+45
    while time.monotonic()<deadline:
        try:
            result=json.loads((control/'response.json').read_text(encoding='utf-8'))
            if result['id']==data['id']:
                (WORK/(op+'-last-response.json')).write_text(json.dumps(result,ensure_ascii=False,indent=2),encoding='utf-8')
                if not result['pass']:raise RuntimeError(result)
                return result
        except (FileNotFoundError,json.JSONDecodeError):pass
        time.sleep(.2)
    raise TimeoutError(op)
if __name__=='__main__':
    p=argparse.ArgumentParser();p.add_argument('op');p.add_argument('--json',default='{}');a=p.parse_args()
    if a.op=='prepare':prepare()
    elif a.op=='launch':launch()
    else:print(json.dumps(request(a.op,**json.loads(a.json)),ensure_ascii=True))
