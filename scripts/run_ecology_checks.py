"""Freeze the current build, then run ecology GameTests or a hidden isolated preview.

Worlds and process records stay in .local/ecology. No existing player save is used.
Use run_p0.request with WORK=.local/ecology to control the preview and stop it.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess

from run_p0 import ROOT, java_path

WORK = ROOT / '.local/ecology'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('target', choices=['gametest', 'preview'])
    parser.add_argument('--suite', default='ecology')
    parser.add_argument('--world-from', type=Path, help='Ecology GameTest directory with passing ecology reports')
    args = parser.parse_args()
    build = WORK / 'build'
    generated = build / 'moddev'
    run = 'p0GameTest' if args.target == 'gametest' else 'p0Preview'
    stamp = datetime.now(timezone.utc).strftime('%Y%m%d-%H%M%S-%f')
    work = WORK / ('gametest/'+stamp if args.target == 'gametest' else 'preview')
    work.mkdir(parents=True, exist_ok=True)
    runtime = work / ('runtime-'+stamp)
    runtime.mkdir()
    replacements, folders, fingerprints = [], [], {}
    for mod, source in [('mcyukkuri', 'main'), ('mcyukkuri_p0', 'p0Test')]:
        for category in ['classes/java', 'resources']:
            original = build / category / source
            destination = runtime / category / source
            shutil.copytree(original, destination)
            replacements.append((str(original), str(destination)))
            folders.append(mod+'%%'+str(destination))
            for path in destination.rglob('*'):
                if path.is_file(): fingerprints[path.relative_to(runtime).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
    legacy = runtime / (run+'LegacyClasspath.txt')
    replacements.append((str(generated/(run+'LegacyClasspath.txt')),str(legacy)))
    for suffix in ['RunClasspath.txt','RunVmArgs.txt','LegacyClasspath.txt','RunProgramArgs.txt']:
        contents=(generated/(run+suffix)).read_text(encoding='utf-8')
        for original, replacement in replacements:
            contents=contents.replace(original.replace('\\','\\\\'),replacement.replace('\\','\\\\')).replace(original,replacement)
        if str(ROOT / 'build').lower() in contents.lower().replace('\\\\', '\\'):
            raise RuntimeError('Launch arguments still refer to shared root build: '+suffix)
        (runtime/(run+suffix)).write_text(contents,encoding='utf-8')
    (runtime/'fingerprints.json').write_text(json.dumps(fingerprints,indent=2),encoding='utf-8')
    if args.target == 'preview':
        world=work/'saves/p0-preview'
        if not world.exists():
            latest=json.loads((WORK/'last-gametest.json').read_text(encoding='utf-8'))
            source_run=args.world_from or Path(latest['directory'])
            for report in ['ecology-rules','ecology-live']:
                checks=json.loads((source_run/'p0-control'/f'{report}.json').read_text(encoding='utf-8'))
                if not checks or not all(c['pass'] for c in checks): raise RuntimeError('Ecology checks must pass: '+report)
            source=source_run/'world'
            shutil.copytree(source,world,ignore=shutil.ignore_patterns('session.lock'))
        options=ROOT/'.local/p0/preview/options.txt'
        if options.exists() and not (work/'options.txt').exists(): shutil.copy2(options,work/'options.txt')
    control=work/'p0-control';control.mkdir(exist_ok=True)
    for marker in ['request.json','response.json','joined.json','started.json']:(control/marker).unlink(missing_ok=True)
    command=[str(java_path()),'@'+str(runtime/(run+'RunClasspath.txt')),'@'+str(runtime/(run+'RunVmArgs.txt'))]
    if args.target == 'gametest': command += ['-Dmcyukkuri.p0.suite='+args.suite]
    command+=['-Dfml.modFolders='+os.pathsep.join(folders),'net.neoforged.devlaunch.Main','@'+str(runtime/(run+'RunProgramArgs.txt'))]
    with (work/'launch.log').open('wb') as log:
        process=subprocess.Popen(command,cwd=work,stdin=subprocess.DEVNULL,stdout=log,stderr=subprocess.STDOUT,
                                 creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
    record={'pid':process.pid,'directory':str(work),'runtime':str(runtime),'suite':args.suite,'target':args.target}
    (WORK/('last-'+args.target+'.json')).write_text(json.dumps(record,indent=2),encoding='utf-8')
    print(json.dumps(record),flush=True)
    if args.target == 'gametest':
        result=process.wait();record['exit_code']=result
        (WORK/'last-gametest.json').write_text(json.dumps(record,indent=2),encoding='utf-8')
        print('GameTest exit:',result,flush=True);raise SystemExit(result)


if __name__ == '__main__':
    main()
