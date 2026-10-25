"""Install a successful CI runtime for this checkout, removing the previous runtime after switching.

Run with the playground closed: python scripts/update_museum_runtime.py --run <GitHub run ID>
Requires git, gh, and an existing museum-runtime.json with a prepared NeoForge runtime. Never compiles or starts the game.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path, PurePosixPath
import shutil
import subprocess
import tarfile
import uuid
import zipfile


ROOT = Path(__file__).resolve().parent.parent
CONFIG = ROOT / '.local/experimental-map/museum-runtime.json'


def command(*args):
    return subprocess.check_output(args, cwd=ROOT, text=True, encoding='utf-8').strip()


def read_json(path):
    return json.loads(path.read_text(encoding='utf-8-sig'))


def inside(root, name):
    relative = PurePosixPath(name)
    if relative.is_absolute() or '..' in relative.parts or '\\' in name or ':' in name:
        raise ValueError(f'Unsafe archive path: {name}')
    target = root.joinpath(*relative.parts)
    if not target.resolve().is_relative_to(root.resolve()):
        raise ValueError(f'Archive path leaves its destination: {name}')
    return target


def require_closed():
    record = ROOT / '.local/experimental-map/play-museum/process.json'
    if not record.exists():
        return
    previous = read_json(record)
    pid = int(previous['pid'])
    if os.name == 'nt':
        # exit 0: when the recorded process is gone, Get-Process leaves $? false and PowerShell would exit 1.
        script = (f'$p=Get-Process -Id {pid} -ErrorAction SilentlyContinue; '
                  'if($p){[string]$p.StartTime.ToUniversalTime().Ticks}; exit 0')
        started = command('powershell', '-NoProfile', '-Command', script)
        alive = bool(started) and started == str(previous.get('startedTicks', ''))
    else:
        try:
            os.kill(pid, 0)
            alive = True
        except ProcessLookupError:
            alive = False
    if alive:
        raise ValueError('Close the running playground before updating its runtime.')


def unpack_code(archive, mod):
    with zipfile.ZipFile(archive) as source:
        for entry in source.infolist():
            target = inside(mod, entry.filename)
            if entry.is_dir():
                target.mkdir(parents=True, exist_ok=True)
                continue
            if entry.filename.startswith(('assets/', 'data/')):
                raise ValueError('Compiled archive unexpectedly contains source resources.')
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(source.read(entry))


def unpack_resources(commit, hashes, mod):
    missing = set(hashes)
    for name, digest in hashes.items():
        inside(mod, name)
        if not name.startswith(('assets/', 'data/')) or len(digest) != 64:
            raise ValueError(f'Invalid resource manifest entry: {name}')
    # Archiving a subtree leaves the root .gitattributes (eol=lf) behind, so core.autocrlf=true would turn text into CRLF
    # and every hash would miss; keep the stored bytes.
    process = subprocess.Popen(['git', '-c', 'core.autocrlf=false', 'archive', f'{commit}:src/main/resources'], cwd=ROOT, stdout=subprocess.PIPE)
    try:
        with tarfile.open(fileobj=process.stdout, mode='r|') as source:
            for entry in source:
                if entry.name not in missing:
                    continue
                if not entry.isfile():
                    raise ValueError(f'Resource is not a regular file: {entry.name}')
                data = source.extractfile(entry).read()
                if hashlib.sha256(data).hexdigest() != hashes[entry.name]:
                    raise ValueError(f'CI resource hash mismatch: {entry.name}')
                target = inside(mod, entry.name)
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_bytes(data)
                missing.remove(entry.name)
        if process.wait() != 0:
            raise ValueError('Could not read the matching resource tree from git.')
    finally:
        process.stdout.close()
        if process.poll() is None:
            process.terminate()
            process.wait()
    if missing:
        raise ValueError(f'{len(missing)} CI resources are absent from the matching commit.')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--run', required=True, type=int, help='Successful GitHub Build run for the current main checkout')
    args = parser.parse_args()
    if not CONFIG.is_file():
        parser.error('Existing .local/experimental-map/museum-runtime.json is required on the gameplay machine.')
    require_closed()
    if command('git', 'diff', '--name-only', 'HEAD', '--', 'scripts', 'src'):
        parser.error('Commit or preserve script/source changes before selecting a matching CI runtime.')
    commit = command('git', 'rev-parse', 'HEAD')
    run = json.loads(command('gh', 'run', 'view', str(args.run), '--json', 'status,conclusion,headSha,headBranch,name,url'))
    if run['status'] != 'completed' or run['conclusion'] != 'success' or run['headSha'] != commit or run['headBranch'] != 'main' or run['name'] != 'Build':
        parser.error('The selected run must be a successful main Build for the exact current checkout commit.')
    original_config = CONFIG.read_bytes()
    config = json.loads(original_config.decode('utf-8-sig'))
    old_runtime = (ROOT / config['runtime']).resolve()
    if old_runtime.parent != (ROOT / '.local/setup').resolve() or not old_runtime.name.startswith('runtime-'):
        parser.error('The existing prepared runtime must be a runtime-* directory directly inside .local/setup.')
    bootstrap = ('clientRunClasspath.txt', 'clientRunVmArgs.txt', 'clientRunProgramArgs.txt')
    if not all((old_runtime / name).is_file() for name in bootstrap):
        parser.error('The existing NeoForge runtime is incomplete; prepare its dependencies first.')
    suffix = f'{commit[:12]}-{uuid.uuid4().hex[:8]}'
    update = ROOT / '.local/experimental-map/updates' / suffix
    artifact = update / 'artifact'
    artifact.mkdir(parents=True)
    subprocess.run(['gh', 'run', 'download', str(args.run), '--name', f'mcyukkuri-runtime-{commit}', '--dir', str(artifact)], cwd=ROOT, check=True)
    manifest = read_json(artifact / 'runtime-manifest.json')
    if manifest.get('commit') != commit or not manifest.get('resources'):
        parser.error('The downloaded runtime manifest does not match the selected commit.')
    new_runtime = ROOT / '.local/setup' / f'runtime-{suffix}'
    if new_runtime.resolve().is_relative_to(old_runtime):
        parser.error('The new runtime cannot be created inside the previous runtime.')
    # Preserve dependency/launcher files, but replace the complete mod tree rather than overlaying obsolete classes.
    shutil.copytree(old_runtime, new_runtime, ignore=lambda path, names: ['mod'] if Path(path) == old_runtime else [])
    for name in bootstrap:
        path = new_runtime / name
        content = path.read_text(encoding='utf-8-sig')
        for old, new in ((str(old_runtime).replace('\\', '\\\\'), str(new_runtime).replace('\\', '\\\\')),
                         (str(old_runtime), str(new_runtime)), (old_runtime.as_posix(), new_runtime.as_posix()),
                         (str(config['runtime']), new_runtime.relative_to(ROOT).as_posix())):
            content = content.replace(old, new)
        path.write_text(content, encoding='utf-8')
    mod = new_runtime / 'mod'
    mod.mkdir()
    unpack_code(artifact / 'compiled-runtime.zip', mod)
    unpack_resources(commit, manifest['resources'], mod)
    if not (mod / 'META-INF/neoforge.mods.toml').is_file() or not any(mod.rglob('*.class')):
        raise ValueError('CI runtime has no NeoForge mod metadata or compiled classes.')
    receipt = {'commit': commit, 'run': args.run, 'url': run['url'], 'resources_verified': len(manifest['resources']),
               'compiled_archive_sha256': hashlib.sha256((artifact / 'compiled-runtime.zip').read_bytes()).hexdigest()}
    (new_runtime / 'mcyukkuri-runtime.json').write_text(json.dumps(receipt, indent=2) + '\n', encoding='utf-8')
    require_closed()
    if CONFIG.read_bytes() != original_config:
        raise ValueError('Playground configuration changed during preparation; the new runtime is preserved but not selected.')
    # launch_museum.ps1 uses this fixed game root, even for older configs whose game field is stale.
    options = ROOT / '.local/experimental-map/museum/options.txt'
    options.parent.mkdir(parents=True, exist_ok=True)
    lines = options.read_text(encoding='utf-8-sig').splitlines() if options.exists() else []
    lines = [line for line in lines if not line.startswith('soundCategory_music:')]
    lines.append('soundCategory_music:0.0')
    options.write_text('\n'.join(lines) + '\n', encoding='utf-8')
    config.update(runtime=new_runtime.relative_to(ROOT).as_posix(), commit=commit, run=args.run)
    pending = update / 'museum-runtime.json'
    pending.write_text(json.dumps(config, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
    os.replace(pending, CONFIG)
    readback = read_json(CONFIG)
    if readback['commit'] != commit or (ROOT / readback['runtime']).resolve() != new_runtime.resolve():
        raise ValueError('Runtime configuration readback did not match the installed runtime.')
    # Delete only the validated previous runtime after the new configuration has been read back.
    shutil.rmtree(old_runtime)
    shutil.rmtree(update)
    print(f'Installed CI runtime {commit}; verified {len(manifest["resources"])} resources.')
    print(f'Previous runtime removed: {old_runtime}')
    print('Background music muted; other sound categories preserved.')
    print('Launch with 启动动作博物馆.cmd. In-game behavior still needs your playthrough.')


if __name__ == '__main__':
    try:
        main()
    except (ValueError, OSError, subprocess.CalledProcessError, KeyError, zipfile.BadZipFile, tarfile.TarError) as error:
        raise SystemExit(str(error)) from error
