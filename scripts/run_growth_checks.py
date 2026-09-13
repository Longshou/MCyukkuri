#!/usr/bin/env python3
"""Freeze compiled outputs and run growth, migration and family/live-birth GameTests in a fresh project-local world.

Use growth_checks.init.gradle and an isolated Gradle project cache first.
Does not build, start a client, delete prior evidence, or accept a server EULA.
"""
import argparse
from datetime import datetime, timezone
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

from run_p0 import ROOT, java_path

REPORTS = ('growth-checks', 'family-checks', 'consumable-rule-checks', 'consumable-live-checks',
           'consumable-birth-checks', 'consumable-live-birth-checks', 'consumable-morph-checks', 'fertility-checks')


def main(build_directory):
    java = java_path()
    build = (ROOT / build_directory).resolve()
    generated = build / 'moddev'
    for suffix in ('RunClasspath.txt', 'RunVmArgs.txt', 'RunProgramArgs.txt'):
        if not (generated / ('p0GameTest' + suffix)).is_file():
            raise RuntimeError('Compile the current code and generate createP0GameTestLaunchScript first.')
    work = ROOT / '.local/deibu-life-design'
    work.mkdir(parents=True, exist_ok=True)
    run = Path(tempfile.mkdtemp(prefix='gametest-', dir=work)).resolve()
    folders = []
    replacements = []
    for mod, source in (('mcyukkuri', 'main'), ('mcyukkuri_p0', 'p0Test')):
        for category in ('classes/java', 'resources'):
            original = build / category / source
            destination = run / 'snapshot' / category / source
            shutil.copytree(original, destination)
            folders.append(mod + '%%' + str(destination))
            replacements.append((str(original), str(destination)))
    frozen_args = run / 'snapshot/moddev'
    shutil.copytree(generated, frozen_args)
    replacements.append((str(generated), str(frozen_args)))
    for file in frozen_args.glob('*.txt'):
        contents = file.read_text(encoding='utf-8')
        for original, replacement in replacements:
            contents = contents.replace(original.replace('\\', '\\\\'), replacement.replace('\\', '\\\\')).replace(original, replacement)
        file.write_text(contents, encoding='utf-8')
    source_hashes = {p.relative_to(ROOT).as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                     for p in (ROOT / 'src').rglob('*') if p.is_file() and p.suffix in ('.java', '.json')}
    runtime_hashes = {p.relative_to(run / 'snapshot').as_posix(): hashlib.sha256(p.read_bytes()).hexdigest()
                      for category in ('classes', 'resources') for p in (run / 'snapshot' / category).rglob('*') if p.is_file()}
    (run / 'source-manifest.json').write_text(json.dumps(source_hashes, indent=2), encoding='utf-8')
    (run / 'runtime-manifest.json').write_text(json.dumps(runtime_hashes, indent=2), encoding='utf-8')
    (work / 'latest-test.json').write_text(json.dumps({'run': str(run)}, indent=2), encoding='utf-8')
    command = [str(java), '@' + str(frozen_args / 'p0GameTestRunClasspath.txt'),
               '@' + str(frozen_args / 'p0GameTestRunVmArgs.txt'), '-Dmcyukkuri.p0.suite=growth',
               '-Dfml.modFolders=' + os.pathsep.join(folders), 'net.neoforged.devlaunch.Main',
               '@' + str(frozen_args / 'p0GameTestRunProgramArgs.txt')]
    with (run / 'acceptance.log').open('wb') as log:
        result = subprocess.run(command, cwd=run, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                                creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
    checks = {}
    for name in REPORTS:
        file = run / 'p0-control' / (name + '.json')
        checks[name] = json.loads(file.read_text(encoding='utf-8')) if file.exists() else []
    passed = result.returncode == 0 and all(rows and all(row.get('pass') for row in rows) for rows in checks.values())
    summary = {'time': datetime.now(timezone.utc).isoformat(), 'run': str(run), 'exit': result.returncode,
               'passed': passed, 'count': sum(map(len, checks.values())),
               'reports': {name: len(rows) for name, rows in checks.items()}}
    (run / 'summary.json').write_text(json.dumps(summary, indent=2), encoding='utf-8')
    print(json.dumps(summary, indent=2))
    raise SystemExit(0 if passed else 1)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('--build-dir', default='.local/deibu-life-design/build')
    main(parser.parse_args().build_dir)
