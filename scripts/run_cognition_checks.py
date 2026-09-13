"""Freeze an isolated cognition build and run its GameTest suite or a minimal book preview.

Requires JDK 21 and a successful build with cognition_checks.init.gradle.
All worlds, frozen classes, resources, launch arguments and reports stay in .local/cognition.
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

WORK = ROOT / '.local/cognition'


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('target', choices=['gametest', 'preview'])
    parser.add_argument('--suite', default='cognition')
    args = parser.parse_args()
    build = WORK / 'build'
    run = 'p0GameTest' if args.target == 'gametest' else 'p0Preview'
    stamp = datetime.now(timezone.utc).strftime('%Y%m%d-%H%M%S-%f')
    work = WORK / args.target / stamp
    runtime = work / 'runtime'
    runtime.mkdir(parents=True)
    replacements, folders, fingerprints = [], [], {}
    for mod, source in [('mcyukkuri', 'main'), ('mcyukkuri_p0', 'p0Test')]:
        for category in ['classes/java', 'resources']:
            original = build / category / source
            destination = runtime / category / source
            shutil.copytree(original, destination)
            replacements.append((str(original), str(destination)))
            folders.append(mod + '%%' + str(destination))
            for path in destination.rglob('*'):
                if path.is_file():
                    fingerprints[path.relative_to(runtime).as_posix()] = hashlib.sha256(path.read_bytes()).hexdigest()
    generated = build / 'moddev'
    replacements.append((str(generated / (run + 'LegacyClasspath.txt')), str(runtime / (run + 'LegacyClasspath.txt'))))
    for suffix in ['RunClasspath.txt', 'RunVmArgs.txt', 'LegacyClasspath.txt', 'RunProgramArgs.txt']:
        contents = (generated / (run + suffix)).read_text(encoding='utf-8')
        for original, replacement in replacements:
            contents = contents.replace(original.replace('\\', '\\\\'), replacement.replace('\\', '\\\\')).replace(original, replacement)
        if str(ROOT / 'build').lower() in contents.lower().replace('\\\\', '\\'):
            raise RuntimeError('Launch still refers to shared root build: ' + suffix)
        (runtime / (run + suffix)).write_text(contents, encoding='utf-8')
    (runtime / 'fingerprints.json').write_text(json.dumps(fingerprints, indent=2), encoding='utf-8')
    (work / 'p0-control').mkdir()
    if args.target == 'preview':
        (work / 'options.txt').write_text('lang:zh_cn\npauseOnLostFocus:false\nmaxFps:30\nrenderDistance:3\n'
            'simulationDistance:5\nenableVsync:false\nonboardAccessibility:false\nskipMultiplayerWarning:true\nautoJump:false\n', encoding='utf-8')
    command = [str(java_path()), '@' + str(runtime / (run + 'RunClasspath.txt')), '@' + str(runtime / (run + 'RunVmArgs.txt'))]
    if args.target == 'gametest':
        command += ['-Dmcyukkuri.p0.suite=' + args.suite]
    else:
        command += ['-Dmcyukkuri.freezer.preview=true']
    command += ['-Dfml.modFolders=' + os.pathsep.join(folders), 'net.neoforged.devlaunch.Main', '@' + str(runtime / (run + 'RunProgramArgs.txt'))]
    with (work / 'launch.log').open('wb') as log:
        process = subprocess.Popen(command, cwd=work, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
    record = {'pid': process.pid, 'directory': str(work), 'runtime': str(runtime), 'suite': args.suite,
              'target': args.target, 'command': command}
    latest = WORK / ('last-' + args.target + '.json')
    latest.write_text(json.dumps(record, indent=2), encoding='utf-8')
    print(json.dumps(record), flush=True)
    if args.target == 'gametest':
        record['exit_code'] = process.wait()
        latest.write_text(json.dumps(record, indent=2), encoding='utf-8')
        print('GameTest exit:', record['exit_code'], flush=True)
        raise SystemExit(record['exit_code'])


if __name__ == '__main__':
    main()
