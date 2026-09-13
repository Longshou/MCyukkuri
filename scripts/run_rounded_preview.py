"""Launch a hidden, frozen current-build client in a new disposable rounded-body preview world."""
from pathlib import Path
import argparse,hashlib,json,os,shutil,subprocess,tempfile
import run_p0 as p0
ROOT=p0.ROOT
WORK=ROOT/'.local/personality-deibu'
def launch():
    build=WORK/'build';generated=build/'moddev'
    run=Path(tempfile.mkdtemp(prefix='preview-',dir=WORK)).resolve();snapshot=run/'snapshot';folders=[];replacements=[]
    for mod,source in [('mcyukkuri','main'),('mcyukkuri_p0','p0Test')]:
        for category in ['classes/java','resources']:
            old=build/category/source;new=snapshot/category/source;shutil.copytree(old,new)
            folders.append(mod+'%%'+str(new));replacements.append((str(old),str(new)))
    args=snapshot/'moddev';shutil.copytree(generated,args);replacements.append((str(generated),str(args)))
    for file in args.glob('*.txt'):
        contents=file.read_text(encoding='utf-8')
        for old,new in replacements:contents=contents.replace(old.replace('\\','\\\\'),new.replace('\\','\\\\')).replace(old,new)
        file.write_text(contents,encoding='utf-8')
    source=WORK/'gametest-05yyh3_t/world'
    if not source.exists():raise RuntimeError('The previously passed isolated world is required.')
    shutil.copytree(source,run/'saves/p0-preview',ignore=shutil.ignore_patterns('session.lock'))
    (run/'options.txt').write_text('lang:zh_cn\npauseOnLostFocus:false\nmaxFps:60\nrenderDistance:4\nsimulationDistance:5\nenableVsync:false\nonboardAccessibility:false\nskipMultiplayerWarning:true\nautoJump:false\n',encoding='utf-8')
    command=[str(p0.java_path()),'@'+str(args/'p0PreviewRunClasspath.txt'),'@'+str(args/'p0PreviewRunVmArgs.txt'),'-Dfml.modFolders='+os.pathsep.join(folders),'net.neoforged.devlaunch.Main','@'+str(args/'p0PreviewRunProgramArgs.txt')]
    (run/'runtime-manifest.json').write_text(json.dumps({str(p.relative_to(snapshot)):hashlib.sha256(p.read_bytes()).hexdigest()for p in snapshot.rglob('*')if p.is_file()},indent=2),encoding='utf-8')
    with (run/'launch.log').open('wb')as log:
        process=subprocess.Popen(command,cwd=run,stdin=subprocess.DEVNULL,stdout=log,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt'else 0)
    result={'run':str(run),'pid':process.pid};(WORK/'latest-preview.json').write_text(json.dumps(result,indent=2),encoding='utf-8');print(json.dumps(result))
def request(operation,**parameters):
    run=Path(json.loads((WORK/'latest-preview.json').read_text())['run']);p0.WORK=run.parent
    return p0.request(run.name,operation,**parameters)
if __name__=='__main__':
    parser=argparse.ArgumentParser();parser.add_argument('operation',choices=['launch','request']);parser.add_argument('--payload',type=Path);args=parser.parse_args()
    if args.operation=='launch':launch()
    else:
        data=json.loads(args.payload.read_text(encoding='utf-8'));print(json.dumps(request(data.pop('op'),**data),ensure_ascii=False,indent=2))
