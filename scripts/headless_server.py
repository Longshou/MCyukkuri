#!/usr/bin/env python
"""Headless NeoForge server launcher, adapted from .local/harness/harness.py; stdlib only, no compilation."""
from contextlib import contextmanager
import ctypes
import json
import os
import re
import secrets
import shutil
import socket
import struct
import subprocess
import time
from pathlib import Path


def git_common_root(worktree):
    return Path(subprocess.check_output(
        ['git', 'rev-parse', '--path-format=absolute', '--git-common-dir'], cwd=worktree, text=True).strip()).parent


ROOT = Path(__file__).resolve().parent.parent
COMMON = git_common_root(ROOT)
INSTANCES = ROOT / '.local/smoke'


def is_client(command):
    # The museum launcher passes the client launch target through a Java argument file.
    return re.search(r'--launchTarget["\s=]+\w*client\w*\b'
                     r'|(?:^|["\s])@[^"\r\n]*clientRunProgramArgs\.txt(?:["\s]|$)'
                     r'|\bnet\.minecraft\.client\.main\.Main\b', command or '', re.IGNORECASE) is not None


def require_closed():
    query = ('$ErrorActionPreference = "Stop"; '
             '[Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false); '
             'ConvertTo-Json -Compress -InputObject @(Get-CimInstance Win32_Process '
             '-Filter "Name = \'java.exe\' OR Name = \'javaw.exe\'" '
             '| Select-Object Name,ProcessId,CommandLine)')
    rows = json.loads(subprocess.check_output(
        ['powershell.exe', '-NoProfile', '-Command', query], encoding='utf-8'))
    running = [f'{row["Name"]} PID={row["ProcessId"]}' for row in rows if is_client(row['CommandLine'])]
    if running:
        raise RuntimeError('Close Minecraft client before running headless checks: ' + ', '.join(running))


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


def process_started(pid):
    k = ctypes.windll.kernel32
    k.OpenProcess.restype = ctypes.c_void_p
    handle = k.OpenProcess(0x1000, False, pid)
    if not handle:
        return None
    code = ctypes.c_ulong()
    times = (ctypes.c_ulonglong * 4)()
    k.GetExitCodeProcess(ctypes.c_void_p(handle), ctypes.byref(code))
    k.GetProcessTimes(ctypes.c_void_p(handle), *(ctypes.byref(times, i * 8) for i in range(4)))
    k.CloseHandle(ctypes.c_void_p(handle))
    return times[0] if code.value == 259 else None


@contextmanager
def instance_lock():
    # Share port handoff and stale recovery across worktrees; Windows releases abandoned mutexes.
    k = ctypes.windll.kernel32
    k.CreateMutexW.restype = ctypes.c_void_p
    handle = k.CreateMutexW(None, False, 'Local\\MCyukkuriSmokeLaunch')
    if not handle:
        raise ctypes.WinError()
    try:
        result = k.WaitForSingleObject(ctypes.c_void_p(handle), 0xFFFFFFFF)
        if result not in (0, 0x80):  # Acquired normally, or inherited from a terminated runner.
            raise ctypes.WinError()
        try:
            yield
        finally:
            k.ReleaseMutex(ctypes.c_void_p(handle))
    finally:
        k.CloseHandle(ctypes.c_void_p(handle))


@instance_lock()
def stop_stale(instances):
    for root in {instances, INSTANCES, COMMON / '.local/smoke'}:
        for record in root.glob('instance-*/server.json'):
            st = json.loads(record.read_text(encoding='utf-8'))
            if (process_started(st['pid']) == st['started']
                    and process_started(st['runner_pid']) != st['runner_started']):
                print(f'Stopping stale instance {st["name"]}', flush=True)
                stop(st)


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
        except OSError:
            if attempt == 5:
                raise
            time.sleep(0.5)
    try:
        return rcon.command(cmd)
    finally:
        rcon.sock.close()


def build_java_cmd(runtime, java, rundir, mod):
    vm = [a for a in read_args_file(runtime / 'clientRunVmArgs.txt') if not a.startswith('-Dneoforge.enableGameTest')]
    cp = read_args_file(runtime / 'clientRunClasspath.txt')[:2]
    cp[1] = ';'.join(str(mod) if Path(entry) == runtime / 'mod' else entry for entry in cp[1].split(';'))
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
    return ([str(java), '-Xmx2G', '-Xms512m'] + vm + cp
            + [f'-Dfml.modFolders=mcyukkuri%%{mod}', 'net.neoforged.devlaunch.Main', main,
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


def log_offset(st):
    return (Path(st['rundir']) / 'logs/latest.log').stat().st_size


def log_text(st, offset=0):
    path = Path(st['rundir']) / 'logs/latest.log'
    if not path.exists():
        return ''
    with path.open('rb') as log:
        log.seek(offset)
        return log.read().decode('utf-8', errors='replace')


def wait_log(st, pattern, offset=0, timeout=600):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if process_started(st['pid']) != st['started']:
            raise RuntimeError(f'Server exited; see {Path(st["rundir"]).parent / "stdout.log"}')
        window = log_text(st, offset)
        if re.search(pattern, window):
            return window
        time.sleep(0.2)
    raise TimeoutError(f'Timed out waiting for /{pattern}/; see {st["rundir"]}/logs/latest.log')


@instance_lock()
def start(runtime, java, datapack, name, mod, instances):
    record = instances / name / 'server.json'
    rundir = record.parent / 'run'
    rundir.mkdir(parents=True, exist_ok=False)
    (rundir / 'eula.txt').write_text('eula=true\n', encoding='utf-8')
    shutil.copytree(datapack, rundir / 'world/datapacks' / datapack.name)
    st = {'name': name, 'runtime': str(runtime), 'mod': str(mod), 'rundir': str(rundir),
          'game_port': free_port(), 'rcon_port': free_port(), 'rcon_password': secrets.token_hex(8)}
    while st['rcon_port'] == st['game_port']:
        st['rcon_port'] = free_port()
    write_props(rundir, st)
    cmd = build_java_cmd(runtime, java, rundir, mod)
    require_closed()
    with (record.parent / 'stdout.log').open('wb') as out:
        proc = subprocess.Popen(cmd, cwd=rundir, stdin=subprocess.DEVNULL, stdout=out, stderr=subprocess.STDOUT,
                                creationflags=subprocess.DETACHED_PROCESS | subprocess.CREATE_NEW_PROCESS_GROUP)
    st.update(pid=proc.pid, started=process_started(proc.pid),
              runner_pid=os.getpid(), runner_started=process_started(os.getpid()))
    if st['started'] is None:
        raise RuntimeError(f'Server exited during launch; see {record.parent / "stdout.log"}')
    try:
        pending = record.with_suffix('.tmp')
        pending.write_text(json.dumps(st, indent=2), encoding='utf-8')
        pending.replace(record)
        print(f'Instance {name}: pid={proc.pid} run={rundir}', flush=True)
        wait_log(st, r'Done \([\d.,]+s\)!')
        wait_log(st, r'RCON running on')
    except BaseException:
        stop(st)
        raise
    return st


def sprint(st, ticks):
    offset = log_offset(st)
    run_cmd(st, f'tick sprint {ticks}')
    wait_log(st, r'Sprint completed|ticks per second, or', offset)


def stop(st):
    if process_started(st['pid']) == st['started']:
        try:
            run_cmd(st, 'stop', timeout=5)
        except OSError:
            pass  # Startup failures can leave no RCON listener; the PID still needs reaping.
        deadline = time.monotonic() + 30
        while process_started(st['pid']) == st['started'] and time.monotonic() < deadline:
            time.sleep(0.2)
        if process_started(st['pid']) == st['started']:
            subprocess.run(['taskkill', '/PID', str(st['pid']), '/T', '/F'], check=False, capture_output=True)
            while process_started(st['pid']) == st['started']:
                time.sleep(0.2)
    print(f'Stopped {st["name"]}', flush=True)
