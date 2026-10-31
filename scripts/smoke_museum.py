#!/usr/bin/env python
"""Run scene-owned log expectations on a throwaway dedicated server; a self-check, never playtest acceptance."""
import argparse
import re
import secrets
import shutil
import subprocess
import sys
import time
from pathlib import Path
from string import Template

sys.dont_write_bytecode = True
import headless_server as server
from museum_fields import BY_ID, FIELDS, FEET
from museum_scenarios import S


def assemble_mod(worktree, runtime, java, instance):
    properties = dict(line.strip().split('=', 1) for line in
                      (worktree / 'gradle.properties').read_text(encoding='utf-8').splitlines()
                      if line.strip() and not line.lstrip().startswith(('#', '!')))
    merged = server.COMMON / f'build/moddev/artifacts/neoforge-{properties["neo_version"]}-merged.jar'
    if not merged.is_file():
        raise FileNotFoundError(f'Missing javac dependency: {merged}; this tool never runs Gradle.')
    # Match javac_check.sh's merged Minecraft/NeoForge classes, using the installed runtime's dependencies.
    classpath = [merged] + [Path(entry) for entry in server.read_args_file(runtime / 'clientRunClasspath.txt')[1].split(';')
                            if Path(entry) != runtime / 'mod']
    mod = instance / 'mod'
    shutil.copytree(worktree / 'src/main/resources', mod, ignore=shutil.ignore_patterns('.cache', '*.bbmodel'))
    metadata = mod / 'META-INF/neoforge.mods.toml'
    metadata.parent.mkdir(parents=True, exist_ok=True)
    template = (worktree / 'src/main/templates/META-INF/neoforge.mods.toml').read_text(encoding='utf-8')
    metadata.write_text(Template(template).substitute(properties), encoding='utf-8')
    args = instance / 'javac.args'
    cp = ';'.join(path.as_posix() for path in classpath)
    args.write_text(f'-cp "{cp}"\n' + '\n'.join(f'"{path.as_posix()}"' for path in
                    sorted((worktree / 'src/main/java').rglob('*.java'))), encoding='utf-8')
    print(f'Compiling worktree {worktree} -> {mod}', flush=True)
    subprocess.run([str(java.with_name('javac.exe')), '-proc:none', '-nowarn', '-encoding', 'UTF-8',
                    '-Xmaxerrs', '300', '-d', str(mod), '@' + str(args)], cwd=instance, check=True)
    return mod


def wait_command(st, command, marker, timeout=120):
    offset = server.log_offset(st)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        server.run_cmd(st, command)
        if marker in server.log_text(st, offset):
            return
        time.sleep(0.2)
    raise TimeoutError(f'Timed out waiting for {marker}; see {st["rundir"]}/logs/latest.log')


def clear_all(st):
    offset = server.log_offset(st)
    server.run_cmd(st, 'function mcylab:util/clear_all')
    # The generated reset removes every field's forced chunks only after all field clears return.
    # Its tellraw @a completion is invisible without players; observe the last removal instead.
    x, z = sorted(FIELDS[-1].chunks())[-1]
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        server.run_cmd(st, f'execute store success score #smoke_reset museum run forceload query {x} {z}')
        server.run_cmd(st, 'execute if score #smoke_reset museum matches 0 run say MCYSMOKE_RESET_COMPLETE')
        if 'MCYSMOKE_RESET_COMPLETE' in server.log_text(st, offset):
            return
        time.sleep(0.2)
    raise TimeoutError('Museum clear_all did not finish; see ' + st['rundir'] + '/logs/latest.log')


def load_field(st, field):
    chunks = sorted(field.chunks())
    for x, z in chunks:
        server.run_cmd(st, f'forceload add {x} {z}')
    loaded = ' '.join(f'if loaded {x} {FEET} {z}' for x, z in chunks)
    wait_command(st, f'execute {loaded} run say MCYSMOKE_FIELD_READY', 'MCYSMOKE_FIELD_READY')


def failure(smoke, window):
    for line in window.splitlines():
        if any(re.search(pattern, line) for pattern in smoke['forbid']):
            return 'forbidden line: ' + line
    for pattern in smoke['expect']:
        if not re.search(pattern, window):
            return f'missing /{pattern}/'
    return None


def run_scene(st, scene):
    clear_all(st)
    load_field(st, BY_ID[scene.field])
    offset = server.log_offset(st)
    server.run_cmd(st, f'function mcylab:start/{scene.id}')
    server.sprint(st, scene.smoke['ticks'])
    window = server.log_text(st, offset)
    (Path(st['rundir']).parent / f'{scene.id}.log').write_text(window, encoding='utf-8')
    problem = failure(scene.smoke, window)
    if problem:
        print(f'FAIL {scene.id}: {problem}', flush=True)
    else:
        print(f'PASS {scene.id}: {len(scene.smoke["expect"])} expected patterns; no forbidden lines', flush=True)
    return problem is None


def main():
    scenes = {scene.id: scene for scene in S if scene.smoke is not None}
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--worktree', type=Path, help='Compile this worktree into an isolated mod (no Gradle)')
    parser.add_argument('scenes', nargs='*', help='Scene IDs (default: all scenes with smoke metadata)')
    args = parser.parse_args()
    unknown = set(args.scenes) - scenes.keys()
    if unknown:
        parser.error('No smoke expectations for: ' + ', '.join(sorted(unknown)))
    selected = [scenes[sid] for sid in (args.scenes or scenes)]
    if args.worktree is not None:
        args.worktree = args.worktree.resolve(strict=True)
        server.INSTANCES = server.COMMON / '.local/smoke'
        if server.INSTANCES.resolve().is_relative_to(args.worktree):
            parser.error('--worktree must not contain the main checkout smoke output directory; use a linked worktree')
    server.stop_stale()
    server.require_closed()
    runtime, java = server.installed_runtime()
    run_id = secrets.token_hex(6)
    name = f'instance-{run_id}'
    mod = (runtime / 'mod' if args.worktree is None else
           assemble_mod(args.worktree, runtime, java, server.INSTANCES / name))
    pack_root = server.INSTANCES / f'pack-{run_id}'
    print(f'Self-check only; runtime={runtime.name}; mod={mod}; scenes={", ".join(s.id for s in selected)}', flush=True)
    subprocess.run([sys.executable, str(server.ROOT / 'scripts/build_museum_full.py'),
                    '--pack-only', '--output', str(pack_root)], cwd=server.ROOT, check=True)
    # Check again immediately before launch, since pack generation takes time.
    server.require_closed()
    datapack = pack_root / 'saves/MCyukkuri-Museum/datapacks/mcylab'
    st = server.start(runtime, java, datapack, name, mod)
    try:
        server.wait_log(st, r'MCYMUSEUM_BUILD_COMPLETE')
        results = [run_scene(st, scene) for scene in selected]
        return 0 if all(results) else 1
    finally:
        server.stop(st)


if __name__ == '__main__':
    started = time.monotonic()
    try:
        raise SystemExit(main())
    except KeyboardInterrupt:
        print('Interrupted; server cleanup completed.', file=sys.stderr)
        raise SystemExit(130)
    except (OSError, ValueError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f'ERROR: {error}', file=sys.stderr)
        raise SystemExit(1)
    finally:
        print(f'Wall time: {time.monotonic() - started:.1f}s', flush=True)
