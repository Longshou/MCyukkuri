"""Freeze an isolated build and run predator or shop engine checks in a fresh world."""
from pathlib import Path
import argparse,os,shutil,subprocess,time,json,hashlib
from run_p0 import ROOT,java_path
parser=argparse.ArgumentParser();parser.add_argument('--suite',choices=['predators','humans'],default='predators');args=parser.parse_args()
build=ROOT/'.local/predators/build';generated=build/'moddev';run='p0GameTest'
if 'BUILD SUCCESSFUL' not in (ROOT/'.local/predators/build.log').read_text(encoding='utf-8-sig'):
    raise RuntimeError('The latest isolated build must succeed before running checks.')
work=ROOT/'.local/predators'/args.suite/str(int(time.time()));work.mkdir(parents=True,exist_ok=True)
folders=[];replacements=[];manifest={}
for mod,source in [('mcyukkuri','main'),('mcyukkuri_p0','p0Test')]:
    for category in ('classes/java','resources'):
        original=build/category/source;dest=work/category/source
        if not original.is_dir():raise RuntimeError('Missing isolated output: '+str(original))
        shutil.copytree(original,dest);folders.append(mod+'%%'+str(dest));replacements.append((str(original),str(dest)))
        for p in dest.rglob('*'):
            if p.is_file():manifest[p.relative_to(work).as_posix()]=hashlib.sha256(p.read_bytes()).hexdigest()
replacements.append((str(generated/(run+'LegacyClasspath.txt')),str(work/(run+'LegacyClasspath.txt'))))
for suffix in ('RunClasspath.txt','RunVmArgs.txt','RunProgramArgs.txt','LegacyClasspath.txt'):
    content=(generated/(run+suffix)).read_text(encoding='utf-8')
    for original,dest in replacements:
        content=content.replace(original.replace('\\','\\\\'),dest.replace('\\','\\\\')).replace(original,dest)
        content=content.replace(original.replace('\\','/'),dest.replace('\\','/'))
    (work/(run+suffix)).write_text(content,encoding='utf-8')
(work/'runtime-manifest.json').write_text(json.dumps(manifest,indent=2),encoding='utf-8')
cmd=[str(java_path()),'@'+str(work/(run+'RunClasspath.txt')),'@'+str(work/(run+'RunVmArgs.txt')),
     '-Dmcyukkuri.p0.suite='+args.suite,'-Dfml.modFolders='+os.pathsep.join(folders),'net.neoforged.devlaunch.Main','@'+str(work/(run+'RunProgramArgs.txt'))]
print('Frozen runtime: '+str(work),flush=True)
with (work/'acceptance.log').open('wb') as f:
    result=subprocess.run(cmd,cwd=work,stdin=subprocess.DEVNULL,stdout=f,stderr=subprocess.STDOUT,creationflags=subprocess.CREATE_NO_WINDOW if os.name=='nt' else 0)
print('Exit: '+str(result.returncode)+'; '+str(work/'acceptance.log'))
raise SystemExit(result.returncode)
