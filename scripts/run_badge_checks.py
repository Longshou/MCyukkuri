#!/usr/bin/env python3
"""Run only badge GameTests in .local/badges/gametest-clean.

First build with badge_checks.init.gradle and an isolated project cache (docs/BADGES.md). Reuses the
generated NeoForge launch arguments; does not launch a normal server or accept EULA.
"""
import os
import argparse
import re
import json
import hashlib
from datetime import datetime, timezone
from pathlib import Path
import shutil
import subprocess
import tempfile

from run_p0 import ROOT, java_path


def main(build_directory):
    java = java_path()
    build = (ROOT / build_directory).resolve()
    generated = build / 'moddev'
    run = 'p0GameTest'
    for suffix in ('RunClasspath.txt', 'RunVmArgs.txt', 'RunProgramArgs.txt', 'LegacyClasspath.txt'):
        if not (generated / (run + suffix)).is_file():
            raise RuntimeError('Run Gradle build createP0GameTestLaunchScript first.')
    # Refuse stale task classes even if a concurrent, incomplete build left old .class files behind.
    sources = list((ROOT / 'src/main/java/io/github/longshou/mcyukkuri/badge').glob('*.java'))
    sources.append(ROOT / 'src/p0Test/java/io/github/longshou/mcyukkuri/p0/BadgeGameTests.java')
    for source in sources:
        group = 'p0Test' if 'p0Test' in source.parts else 'main'
        relative = source.relative_to(ROOT / 'src' / group / 'java').with_suffix('.class')
        compiled = build / 'classes/java' / group / relative
        if not compiled.exists() or compiled.stat().st_mtime_ns < source.stat().st_mtime_ns:
            raise RuntimeError('Build the current badge code successfully before testing: ' + str(source))
    work = ROOT / '.local/badges/gametest-clean'
    work.mkdir(parents=True, exist_ok=True)
    logfile = work / 'acceptance.log'
    # Freeze both module folders and classpaths. Each run also gets a clean disposable world.
    with tempfile.TemporaryDirectory(prefix='runtime-', dir=work) as temporary:
        snapshot = Path(temporary).resolve()
        assert snapshot.is_relative_to(work.resolve())
        folders = []
        replacements = []
        for mod, source in (('mcyukkuri', 'main'), ('mcyukkuri_p0', 'p0Test')):
            for category in ('classes/java', 'resources'):
                destination = snapshot / category / source
                shutil.copytree(build / category / source, destination)
                folders.append(mod + '%%' + str(destination))
                replacements.append((str(build / category / source), str(destination)))
        hashes = {str(p.relative_to(snapshot)).replace('\\', '/'): hashlib.sha256(p.read_bytes()).hexdigest()
                  for category in ('classes', 'resources') for p in (snapshot / category).rglob('*') if p.is_file()}
        (work / 'runtime-manifest.json').write_text(json.dumps({'captured_at': datetime.now(timezone.utc).isoformat(),
             'files': hashes}, indent=2), encoding='utf-8')
        legacy = snapshot / (run + 'LegacyClasspath.txt')
        replacements.append((str(generated / (run + 'LegacyClasspath.txt')), str(legacy)))
        for suffix in ('RunClasspath.txt', 'RunVmArgs.txt', 'LegacyClasspath.txt'):
            contents = re.sub(r'\\+', '/', (generated / (run + suffix)).read_text(encoding='utf-8'))
            for original, replacement in replacements:
                contents = contents.replace(original.replace('\\', '/'), replacement.replace('\\', '/'))
            # Log4j parses a normalized Windows drive path as URI scheme E:; pass a real file URI.
            contents = re.sub(r'(-Dlog4j2.configurationFile=)([A-Za-z]:/[^\r\n]+)',
                              lambda match: match.group(1) + Path(match.group(2)).as_uri(), contents)
            (snapshot / (run + suffix)).write_text(contents, encoding='utf-8')
        reports = work / 'p0-control'
        reports.mkdir(exist_ok=True)
        for name in ('badge-checks','badge-timing-checks'):
            (reports / (name + '.json')).unlink(missing_ok=True)
        command = [str(java), '@' + str(snapshot / (run + 'RunClasspath.txt')),
                   '@' + str(snapshot / (run + 'RunVmArgs.txt')),
                   '-Dmcyukkuri.p0.suite=badges', '-Dfml.modFolders=' + os.pathsep.join(folders),
                   'net.neoforged.devlaunch.Main', '@' + str(generated / (run + 'RunProgramArgs.txt'))]
        with logfile.open('wb') as output:
            result = subprocess.run(command, cwd=snapshot, stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT,
                                    creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        if (snapshot / 'p0-control').is_dir():
            shutil.copytree(snapshot / 'p0-control', reports, dirs_exist_ok=True)
    collected = {name:json.loads((reports / (name+'.json')).read_text(encoding='utf-8'))
                 if (reports / (name+'.json')).exists() else [] for name in ('badge-checks','badge-timing-checks')}
    checks = [row for rows in collected.values() for row in rows]
    passed = all(collected.values()) and all(c.get('pass') for c in checks) and result.returncode == 0
    print(f'Badge GameTest: {len(checks)} checks, passed={passed}; log: {logfile}')
    raise SystemExit(0 if passed else 1)


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument("--build-dir", default="build", help="Actual isolated Gradle output directory")
    main(parser.parse_args().build_dir)
