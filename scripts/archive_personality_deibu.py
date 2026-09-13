"""Archive a successful fixed runtime and verify the development Jar against its current sources."""
from pathlib import Path
import hashlib,json,shutil,sys,zipfile
ROOT=Path(__file__).resolve().parents[1]
WORK=ROOT/'.local/personality-deibu'
OUT=ROOT/'docs/validation/personality-deibu/current'
def sha(p):return hashlib.sha256(p.read_bytes()).hexdigest()
def read(p):return json.loads(p.read_text(encoding='utf-8'))
def save(p,d):p.parent.mkdir(parents=True,exist_ok=True);p.write_text(json.dumps(d,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
def main():
    run=Path(read(WORK/'latest-test.json')['run']);summary=read(run/'summary.json')
    assert summary['passed'] and summary['exit']==0
    for name in ['summary.json','source-manifest.json','runtime-manifest.json','acceptance.log']:shutil.copy2(run/name,OUT/name)
    (OUT/'checks').mkdir(parents=True,exist_ok=True)
    for name in summary['reports']:shutil.copy2(run/'p0-control'/f'{name}.json',OUT/'checks'/f'{name}.json')
    source=read(run/'source-manifest.json')
    frozen_only='--frozen-only'in sys.argv
    if not frozen_only:
        for p,h in source.items():
            if p.startswith('src/main/'):assert sha(ROOT/p)==h,p
    jar=WORK/'release/mcyukkuri-0.1.0-dev.jar';count=0
    with zipfile.ZipFile(jar)as z:
        resource_root=run/'snapshot/resources/main' if frozen_only else ROOT/'src/main/resources'
        for p in resource_root.rglob('*'):
            if not p.is_file():continue
            name=p.relative_to(resource_root).as_posix()
            assert z.read(name)==p.read_bytes(),('jar resource',name)
            assert (run/'snapshot/resources/main'/name).read_bytes()==p.read_bytes(),('tested resource',name)
            count+=1
        for p in (run/'snapshot/classes/java/main').rglob('*.class'):
            assert z.read(p.relative_to(run/'snapshot/classes/java/main').as_posix())==p.read_bytes(),p
    save(OUT/'release.json',{'jar':jar.relative_to(ROOT).as_posix(),'sha256':sha(jar),'bytes':jar.stat().st_size,'verified_resource_files':count,'all_packaged_main_classes_match_tested_snapshot':True,'current_main_sources_match_tested_snapshot':None if frozen_only else True,'scope':'fixed_tested_snapshot_only'if frozen_only else'current_workspace_and_tested_snapshot','test_run':run.relative_to(ROOT).as_posix(),'harness_scope':'Preview and GameTest fixtures are separate development classes and do not enter the Jar.'})
    print(json.dumps({'archived':summary['count'],'jar_sha256':sha(jar),'resource_files':count}))
if __name__=='__main__':main()
