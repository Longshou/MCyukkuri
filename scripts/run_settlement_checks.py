#!/usr/bin/env python3
"""Run only community GameTests in .local/settlements/gametest.

First run Gradle build createP0GameTestLaunchScript with JDK 21. Reuses the
generated NeoForge launch arguments; does not launch a normal server or accept EULA.
"""
import os
from pathlib import Path
import shutil
import subprocess
import tempfile

from run_p0 import ROOT, java_path


def main():
    build = Path(os.environ.get('MCYUKKURI_BUILD_DIR',str(ROOT / 'build'))).resolve()
    generated = build / 'moddev'
    run = 'p0GameTest'
    for suffix in ('RunClasspath.txt', 'RunVmArgs.txt', 'RunProgramArgs.txt', 'LegacyClasspath.txt'):
        if not (generated / (run + suffix)).is_file():
            raise RuntimeError('Run Gradle build createP0GameTestLaunchScript first.')
    work = ROOT / '.local/settlements/gametest'
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
        legacy = snapshot / (run + 'LegacyClasspath.txt')
        replacements.append((str(generated / (run + 'LegacyClasspath.txt')), str(legacy)))
        for suffix in ('RunClasspath.txt', 'RunVmArgs.txt', 'LegacyClasspath.txt'):
            contents = (generated / (run + suffix)).read_text(encoding='utf-8')
            for original, replacement in replacements:
                contents = contents.replace(original.replace('\\', '\\\\'), replacement.replace('\\', '\\\\'))
                contents = contents.replace(original, replacement)
            (snapshot / (run + suffix)).write_text(contents, encoding='utf-8')
        reports = work / 'p0-control'
        reports.mkdir(exist_ok=True)
        for name in ('settlement-checks', 'settlement-movement-checks', 'shelter-checks', 'shelter-resource-checks'):
            (reports / (name + '.json')).unlink(missing_ok=True)
        command = [str(java_path()), '@' + str(snapshot / (run + 'RunClasspath.txt')),
                   '@' + str(snapshot / (run + 'RunVmArgs.txt')),
                   '-Dmcyukkuri.p0.suite=settlements', '-Dfml.modFolders=' + os.pathsep.join(folders),
                   'net.neoforged.devlaunch.Main', '@' + str(generated / (run + 'RunProgramArgs.txt'))]
        with logfile.open('wb') as output:
            result = subprocess.run(command, cwd=snapshot, stdin=subprocess.DEVNULL, stdout=output, stderr=subprocess.STDOUT,
                                    creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
        if (snapshot / 'p0-control').is_dir():
            shutil.copytree(snapshot / 'p0-control', reports, dirs_exist_ok=True)
    print(f'Community GameTest exit code: {result.returncode}; log: {logfile}')
    raise SystemExit(result.returncode)


if __name__ == '__main__':
    main()
