#!/usr/bin/env python
"""Headless NeoForge server launcher, adapted from .local/harness/harness.py; stdlib only, no compilation."""
import argparse
import csv
import ctypes
import json
import re
import secrets
import shutil
import socket
import struct
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
COMMON = Path(subprocess.check_output(
    ['git', 'rev-parse', '--path-format=absolute', '--git-common-dir'], cwd=ROOT, text=True).strip()).parent
INSTANCES = ROOT / '.local/smoke'


def require_closed():
    rows = csv.reader(subprocess.check_output(['tasklist', '/FO', 'CSV', '/NH'], text=True).splitlines())
    running = [row[0] + ' PID=' + row[1] for row in rows
               if row[0].lower() in ('java.exe', 'javaw.exe', 'minecraft.windows.exe')]
    if running:
        raise RuntimeError('Close Minecraft/Java before running headless checks: ' + ', '.join(running))


def read_args_file(path):
    return [line.replace('\\\\', '\\') for line in path.read_text(encoding='utf-8').splitlines()
            if line.strip() and not line.startswith('#')]


def installed_runtime():
    config_path = COMMON / '.local/experimental-map/museum-runtime.json'
    if not config_path.is_file():
        raise FileNotFoundError(f'Installed CI runtime required: {config_path}; this tool never runs Gradle.')
    config = json.loads(config_path.read_text(encoding='utf-8-sig'))
    runtime, java = COMMON / config['runtime'], COMMON / config['java']
    required = [java, runtime / 'mcyukkuri-runtime.json', runtime / 'mod/META-INF/neoforge.mods.toml',
                runtime / 'mod/io/github/longshou/mcyukkuri/MCYukkuri.class']
    required += [runtime / name for name in ('clientRunVmArgs.txt', 'clientRunClasspath.txt',
                                           'clientRunProgramArgs.txt', 'clientLegacyClasspath.txt', 'clientLog4j2.xml')]
    for path in required:
        if not path.is_file():
            raise FileNotFoundError(f'Installed CI runtime incomplete: {path}; this tool never runs Gradle.')
    vm = read_args_file(runtime / 'clientRunVmArgs.txt')
    paths = read_args_file(runtime / 'clientRunClasspath.txt')[1].split(';')
    paths += read_args_file(runtime / 'clientLegacyClasspath.txt')
    paths += vm[vm.index('-p') + 1].split(';')
    if not (COMMON / 'build/moddev/artifacts').is_dir():
        raise FileNotFoundError('Missing build/moddev/artifacts; restore prepared dependencies (never run Gradle here).')
    for name in paths:
        if not Path(name).exists():
            raise FileNotFoundError(f'Missing runtime/build/moddev dependency: {name}; this tool never runs Gradle.')
    return runtime, java


def state_path(name):
    if Path(name).name != name or name in ('.', '..'):
        raise ValueError('Instance name must be a single directory name.')
    return INSTANCES / name / 'state.json'


def load_state(name):
    return json.loads(state_path(name).read_text(encoding='utf-8'))


def pid_alive(pid):
    k = ctypes.windll.kernel32
    k.OpenProcess.restype = ctypes.c_void_p
    handle = k.OpenProcess(0x1000, False, pid)
    if not handle:
        return False
    code = ctypes.c_ulong()
    k.GetExitCodeProcess(ctypes.c_void_p(handle), ctypes.byref(code))
    k.CloseHandle(ctypes.c_void_p(handle))
    return code.value == 259


def free_port():
    with socket.socket() as sock:
        sock.bind(('127.0.0.1', 0))
        return sock.getsockname()[1]


class Rcon:
    def __init__(self, port, password, timeout=30):
        self.sock = socket.create_connection(('127.0.0.1', port), timeout=timeout)
        self.buf = b''
        try:
            self._send(1, 3, password)
            rid, _, _ = self._recv()
            if rid == -1:
                raise RuntimeError('RCON auth failed')
        except BaseException:
            self.sock.close()
            raise

    def _send(self, rid, typ, body):
        data = struct.pack('<ii', rid, typ) + body.encode('utf-8') + b'\x00\x00'
        self.sock.sendall(struct.pack('<i', len(data)) + data)

    def _recv(self):
        while len(self.buf) < 4:
            self._more()
        n = struct.unpack('<i', self.buf[:4])[0]
        while len(self.buf) < 4 + n:
            self._more()
        pkt, self.buf = self.buf[4:4 + n], self.buf[4 + n:]
        rid, typ = struct.unpack('<ii', pkt[:8])
        return rid, typ, pkt[8:-2].decode('utf-8', 'replace')

    def _more(self):
        data = self.sock.recv(65536)
        if not data:
            raise ConnectionError('RCON closed')
        self.buf += data

    def command(self, cmd):
        """Join multi-packet replies using the harness's sentinel request."""
        self._send(2, 2, cmd.lstrip('/'))
        # Vanilla can discard a second request coalesced into the same socket read.
        # Wait for the command's first reply before sending the end marker.
        _, _, first = self._recv()
        self._send(3, 100, '')
        out = [first]
        while True:
            rid, _, body = self._recv()
            if rid == 3:
                return ''.join(out)
            out.append(body)


def run_cmd(st, cmd, timeout=30):
    # The vanilla listener sometimes drops a fresh connection; never retry a command already sent.
    for attempt in range(6):
        try:
            rcon = Rcon(st['rcon_port'], st['rcon_password'], timeout)
            break
        except (ConnectionError, OSError):
            if attempt == 5:
                raise
            time.sleep(0.5)
    try:
        return rcon.command(cmd)
    finally:
        rcon.sock.close()


def build_java_cmd(runtime, java, rundir, xmx):
    vm = [a for a in read_args_file(runtime / 'clientRunVmArgs.txt') if not a.startswith('-Dneoforge.enableGameTest')]
    cp = read_args_file(runtime / 'clientRunClasspath.txt')[:2]
    prog = read_args_file(runtime / 'clientRunProgramArgs.txt')
    main = prog.pop(0)
    out, i = [], 0
    drop = {'--version', '--assetIndex', '--assetsDir', '--gameDir', '--launchTarget'}
    while i < len(prog):
        if prog[i] in drop:
            i += 2
            continue
        out.append(prog[i])
        i += 1
    return ([str(java), f'-Xmx{xmx}', '-Xms512m'] + vm + cp
            + [f'-Dfml.modFolders=mcyukkuri%%{runtime / "mod"}', 'net.neoforged.devlaunch.Main', main,
               '--launchTarget', 'forgeserverdev', '--gameDir', str(rundir)] + out + ['--nogui'])


def write_props(rundir, st):
    props = {
        'online-mode': 'false', 'enable-rcon': 'true', 'rcon.port': st['rcon_port'],
        'rcon.password': st['rcon_password'], 'broadcast-rcon-to-ops': 'false',
        'server-port': st['game_port'], 'server-ip': '127.0.0.1', 'level-name': 'world',
        'view-distance': '4', 'simulation-distance': '4', 'spawn-protection': '0',
        'generate-structures': 'false', 'spawn-monsters': 'false', 'spawn-npcs': 'false',
        'enable-command-block': 'true', 'motd': 'mcy-smoke', 'max-tick-time': '-1',
        'sync-chunk-writes': 'false', 'enable-query': 'false', 'allow-flight': 'true',
        'network-compression-threshold': '-1', 'enforce-secure-profile': 'false', 'op-permission-level': '4',
        'level-type': 'minecraft:flat',
        'generator-settings': '{"biome":"minecraft:plains","features":false,"lakes":false,"layers":'
                              '[{"block":"minecraft:bedrock","height":1},{"block":"minecraft:dirt","height":2},'
                              '{"block":"minecraft:grass_block","height":1}],"structure_overrides":[]}',
    }
    (rundir / 'server.properties').write_text(''.join(f'{k}={v}\n' for k, v in props.items()), encoding='utf-8')


def log_text(st):
    path = Path(st['rundir']) / 'logs/latest.log'
    return path.read_text(encoding='utf-8', errors='replace') if path.exists() else ''


def wait_log(st, pattern, offset=0, timeout=600):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if not pid_alive(st['pid']):
            raise RuntimeError(f'Server exited; see {Path(st["rundir"]).parent / "stdout.log"}')
        window = log_text(st)[offset:]
        if re.search(pattern, window):
            return window
        time.sleep(0.2)
    raise TimeoutError(f'Timed out waiting for /{pattern}/; see {st["rundir"]}/logs/latest.log')


def start(runtime, java, datapack, name, xmx='2G'):
    record = state_path(name)
    rundir = record.parent / 'run'
    rundir.mkdir(parents=True, exist_ok=False)
    (rundir / 'eula.txt').write_text('eula=true\n', encoding='utf-8')
    shutil.copytree(datapack, rundir / 'world/datapacks' / datapack.name)
    st = {'name': name, 'runtime': str(runtime), 'rundir': str(rundir),
          'game_port': free_port(), 'rcon_port': free_port(), 'rcon_password': secrets.token_hex(8)}
    while st['rcon_port'] == st['game_port']:
        st['rcon_port'] = free_port()
    write_props(rundir, st)
    cmd = build_java_cmd(runtime, java, rundir, xmx)
    with (record.parent / 'stdout.log').open('wb') as out:
        proc = subprocess.Popen(cmd, cwd=rundir, stdin=subprocess.DEVNULL, stdout=out, stderr=subprocess.STDOUT,
                                creationflags=subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP)
    st['pid'] = proc.pid
    try:
        record.write_text(json.dumps(st, indent=2), encoding='utf-8')
        print(f'Instance {name}: pid={proc.pid} run={rundir}', flush=True)
        wait_log(st, r'Done \([\d.,]+s\)!')
        wait_log(st, r'RCON running on')
    except BaseException:
        stop(st)
        raise
    return st


def sprint(st, ticks):
    offset = len(log_text(st))
    reply = run_cmd(st, f'tick sprint {ticks}')
    wait_log(st, r'Sprint completed|ticks per second, or', offset)
    return reply


def stop(st):
    if pid_alive(st['pid']):
        try:
            run_cmd(st, 'stop', timeout=5)
        except (ConnectionError, OSError):
            pass  # Startup failures can leave no RCON listener; the PID still needs reaping.
        deadline = time.monotonic() + 30
        while pid_alive(st['pid']) and time.monotonic() < deadline:
            time.sleep(0.2)
        if pid_alive(st['pid']):
            subprocess.run(['taskkill', '/PID', str(st['pid']), '/T', '/F'], check=True, capture_output=True)
            while pid_alive(st['pid']):
                time.sleep(0.2)
    print(f'Stopped {st["name"]}', flush=True)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--name', default='default', help='Instance under .local/smoke; put before the subcommand')
    sub = parser.add_subparsers(dest='action', required=True)
    launch = sub.add_parser('start', help='Start a new flat server with the installed CI runtime')
    launch.add_argument('--datapack', required=True, type=Path)
    launch.add_argument('--xmx', default='2G')
    sub.add_parser('cmd').add_argument('command', nargs='+')
    sub.add_parser('sprint').add_argument('ticks', type=int)
    sub.add_parser('log')
    sub.add_parser('stop')
    sub.add_parser('status')
    args = parser.parse_args()
    if args.action == 'start':
        require_closed()
        runtime, java = installed_runtime()
        start(runtime, java, args.datapack.resolve(), args.name, args.xmx)
        return
    st = load_state(args.name)
    if args.action == 'cmd':
        print(run_cmd(st, ' '.join(args.command)))
    elif args.action == 'sprint':
        print(sprint(st, args.ticks))
    elif args.action == 'log':
        print(log_text(st))
    elif args.action == 'stop':
        stop(st)
    else:
        print(f'{st["name"]}: pid={st["pid"]} alive={pid_alive(st["pid"])} run={st["rundir"]}')


if __name__ == '__main__':
    try:
        main()
    except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError) as error:
        raise SystemExit(str(error)) from error
