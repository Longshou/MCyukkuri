#!/usr/bin/env python3
"""Local P0 launcher/control. Uses generated MDG argument files; never accepts EULA.

All worlds, logs and control files live in .local/p0. Java is launched directly,
without a shell. The test mod is absent from ordinary runs and the release jar.
"""
import argparse
from datetime import datetime, timezone
import json
import os
from pathlib import Path
import shutil
import socket
import subprocess
import time
import uuid

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / '.local/p0'
RUNS = {'server': 'p0Server', 'client-a': 'p0ClientA', 'client-b': 'p0ClientB', 'preview': 'p0Preview'}


def write_json(path, value):
    path.parent.mkdir(parents=True, exist_ok=True)
    temporary = path.with_suffix('.tmp')
    temporary.write_text(json.dumps(value, indent=2, ensure_ascii=False), encoding='utf-8')
    temporary.replace(path)


def read_json(path):
    return json.loads(path.read_text(encoding='utf-8'))


def prepare():
    for target in RUNS:
        directory = WORK / target
        directory.mkdir(parents=True, exist_ok=True)
        (directory / 'p0-control').mkdir(exist_ok=True)
        if target != 'server':
            options = directory / 'options.txt'
            if not options.exists():
                options.write_text('lang:zh_cn\npauseOnLostFocus:false\nmaxFps:60\nrenderDistance:4\n'
                                   'simulationDistance:5\nenableVsync:false\nonboardAccessibility:false\n'
                                   'skipMultiplayerWarning:true\nautoJump:false\n', encoding='utf-8')
    properties = WORK / 'server/server.properties'
    if not properties.exists():
        properties.write_text('server-ip=127.0.0.1\nserver-port=25586\nonline-mode=false\n'
            'enforce-secure-profile=false\nlevel-name=p0-world\nlevel-type=minecraft:flat\n'
            'generator-settings={"layers":[{"block":"minecraft:bedrock","height":1},'
            '{"block":"minecraft:stone","height":3},{"block":"minecraft:grass_block","height":1}],'
            '"biome":"minecraft:plains"}\ngamemode=creative\nforce-gamemode=true\ndifficulty=peaceful\n'
            'spawn-protection=0\nview-distance=4\nsimulation-distance=4\nmax-players=4\n'
            'spawn-animals=false\nspawn-monsters=false\nspawn-npcs=false\nallow-nether=false\n'
            'max-tick-time=60000\nmotd=MCyukkuri isolated P0 acceptance\n', encoding='ascii')
    eula = WORK / 'server/eula.txt'
    if not eula.exists():
        eula.write_text('# Read https://aka.ms/MinecraftEULA; the developer must accept personally.\neula=false\n', encoding='ascii')
    return {'workspace': str(WORK), 'eulaAccepted': 'eula=true' in eula.read_text().splitlines()}


def java_path():
    configured = os.environ.get('JAVA_HOME')
    executable = Path(configured) / 'bin' / ('java.exe' if os.name == 'nt' else 'java') if configured else Path(shutil.which('java') or '')
    if not executable.is_file():
        raise RuntimeError('Set JAVA_HOME to an existing JDK 21; no global settings are changed.')
    version = subprocess.run([str(executable), '-version'], text=True, capture_output=True, check=True)
    if 'version "21.' not in version.stderr and 'version "21"' not in version.stderr:
        raise RuntimeError(f'JDK 21 required: {version.stderr.splitlines()[0]}')
    return executable


def launch(target):
    prepare()
    directory = WORK / target
    if target == 'server':
        if 'eula=true' not in (directory / 'eula.txt').read_text().splitlines():
            raise RuntimeError('Minecraft EULA not accepted. Read the EULA and set .local/p0/server/eula.txt yourself before starting.')
        with socket.socket() as probe:
            try:
                probe.bind(('127.0.0.1', 25586))
            except OSError as error:
                raise RuntimeError('P0 port 25586 is already in use; no existing process will be stopped.') from error
    name = RUNS[target]
    generated = ROOT / 'build/moddev'
    for suffix in ('RunClasspath.txt', 'RunVmArgs.txt', 'RunProgramArgs.txt'):
        if not (generated / (name + suffix)).is_file():
            raise RuntimeError('Run Gradle createP0ServerLaunchScript createP0ClientALaunchScript createP0ClientBLaunchScript first.')
    # Only transient controller messages are removed; world and prior evidence are retained.
    control = directory / 'p0-control'
    for marker in ('request.json', 'response.json', 'ready.json', 'joined.json', 'started.json'):
        (control / marker).unlink(missing_ok=True)
    folders = []
    for mod, source in (('mcyukkuri', 'main'), ('mcyukkuri_p0', 'p0Test')):
        for output in (ROOT / 'build/classes/java' / source, ROOT / 'build/resources' / source):
            folders.append(mod + '%%' + str(output))
    arguments = [str(java_path()), '@' + str(generated / (name + 'RunClasspath.txt')),
                 '@' + str(generated / (name + 'RunVmArgs.txt')), '-Dfml.modFolders=' + os.pathsep.join(folders),
                 'net.neoforged.devlaunch.Main', '@' + str(generated / (name + 'RunProgramArgs.txt'))]
    stamp = datetime.now(timezone.utc).strftime('%Y%m%d-%H%M%S-%f')
    logfile = WORK / 'logs' / f'{target}-{stamp}.log'
    logfile.parent.mkdir(parents=True, exist_ok=True)
    with logfile.open('wb') as log:
        process = subprocess.Popen(arguments, cwd=directory, stdin=subprocess.DEVNULL, stdout=log, stderr=subprocess.STDOUT,
                                   creationflags=subprocess.CREATE_NO_WINDOW if os.name == 'nt' else 0)
    record = {'target': target, 'pid': process.pid, 'started': stamp, 'log': str(logfile), 'java': str(java_path())}
    write_json(WORK / f'{target}-process.json', record)
    return record


def prepare_preview():
    """Copy only the generated GameTest world, never an existing player save."""
    prepare()
    source = WORK / 'gametest/world'
    target = WORK / 'preview/saves/p0-preview'
    if not (source / 'level.dat').is_file():
        raise RuntimeError('Run runP0GameTest first to create the test world.')
    if target.exists():
        raise RuntimeError('Preview world already exists; reuse it or archive it explicitly before making another copy.')
    checks = read_json(WORK / 'gametest/p0-control/server-checks.json')
    if not checks or any(not check['pass'] for check in checks):
        raise RuntimeError('The source GameTest run must pass before preparing its preview.')
    shutil.copytree(source, target, ignore=shutil.ignore_patterns('session.lock'))
    shutil.copy2(WORK / 'gametest/p0-control/display-ids.json', WORK / 'preview/p0-control/display-ids.json')
    return {'world': str(target), 'source': str(source)}


def send(destination, operation, **parameters):
    request = {'id': str(uuid.uuid4()), 'op': operation, **parameters}
    write_json(WORK / destination / 'p0-control/request.json', request)
    return request['id']


def wait(target, request_id, timeout=45):
    deadline = time.monotonic() + timeout
    response_file = WORK / target / 'p0-control/response.json'
    while time.monotonic() < deadline:
        if response_file.exists():
            response = read_json(response_file)
            if response['id'] == request_id:
                archive = WORK / 'responses' / f'{target}-{response["op"]}-{request_id}.json'
                write_json(archive, response)
                return response
        time.sleep(.2)
    raise TimeoutError(f'{target}: request {request_id} did not complete in {timeout}s; inspect its log.')


def request(destination, operation, timeout=45, **parameters):
    response = wait(destination, send(destination, operation, **parameters), timeout)
    if not response['pass']:
        raise AssertionError(json.dumps(response, ensure_ascii=False))
    return response['result']


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('operation', choices=['prepare', 'prepare-preview', 'launch', 'request', 'send', 'wait'])
    parser.add_argument('target', nargs='?', choices=list(RUNS))
    parser.add_argument('op', nargs='?')
    parser.add_argument('--json', default='{}', help='Operation parameters as JSON')
    parser.add_argument('--timeout', type=float, default=45)
    args = parser.parse_args()
    if args.operation == 'prepare':
        result = prepare()
    elif args.operation == 'prepare-preview':
        result = prepare_preview()
    elif args.operation == 'launch':
        result = launch(args.target)
    elif args.operation == 'wait':
        result = wait(args.target, args.op, args.timeout)
    elif args.operation == 'send':
        result = send(args.target, args.op, **json.loads(args.json))
    else:
        result = request(args.target, args.op, args.timeout, **json.loads(args.json))
    print(json.dumps(result, ensure_ascii=False, indent=2))


if __name__ == '__main__':
    main()
