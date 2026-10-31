#!/usr/bin/env python
"""Run scene-owned log expectations on a throwaway dedicated server; a self-check, never playtest acceptance."""
import argparse
import importlib
import json
import re
import secrets
import shutil
import subprocess
import sys
import time
from pathlib import Path

sys.dont_write_bytecode = True
import headless_server as server


def read_properties(path):
    def unescape(value):
        def replacement(match):
            escape = match[1]
            if escape.startswith('u'):
                if len(escape) != 5:
                    raise ValueError(f'Incomplete Unicode escape in {path}: {escape}')
                return chr(int(escape[1:], 16))
            return {'t': '\t', 'n': '\n', 'r': '\r', 'f': '\f'}.get(escape, escape)
        return re.sub(r'\\(u.{0,4}|.)', replacement, value)

    properties = {}
    lines = iter(re.split(r'\r\n?|\n', path.read_text(encoding='utf-8')))
    for line in lines:
        line = line.lstrip(' \t\f')
        if not line or line.startswith(('#', '!')):
            continue
        # Comments do not continue; an odd trailing backslash joins the next physical line.
        while (len(line) - len(line.rstrip('\\'))) % 2:
            line = line[:-1] + next(lines, '').lstrip(' \t\f')
        key, value = re.fullmatch(r'((?:\\.|[^=:\s\\])*)[ \t\f]*(?:[=:][ \t\f]*)?(.*)', line).groups()
        properties[unescape(key)] = unescape(value.strip())
    return properties


def assemble_mod(worktree, runtime, java, instance):
    properties = read_properties(worktree / 'gradle.properties')
    merged = server.COMMON / f'build/moddev/artifacts/neoforge-{properties["neo_version"]}-merged.jar'
    if not merged.is_file():
        raise FileNotFoundError(f'Missing javac dependency: {merged}; this tool never runs Gradle.')
    # Match javac_check.sh's merged Minecraft/NeoForge classes, using the installed runtime's dependencies.
    classpath = [merged] + [Path(entry) for entry in server.read_args_file(runtime / 'clientRunClasspath.txt')[1].split(';')
                            if Path(entry) != runtime / 'mod']
    mod = instance / 'mod'
    ignore = shutil.ignore_patterns('.cache', '*.bbmodel')
    shutil.copytree(worktree / 'src/main/resources', mod, ignore=ignore)
    generated = worktree / 'src/generated/resources'
    if generated.is_dir():
        shutil.copytree(generated, mod, dirs_exist_ok=True, ignore=ignore)
    metadata = mod / 'META-INF/neoforge.mods.toml'
    metadata.parent.mkdir(parents=True, exist_ok=True)
    template = (worktree / 'src/main/templates/META-INF/neoforge.mods.toml').read_text(encoding='utf-8')
    metadata.write_text(re.sub(r'\$\{(\w+)\}', lambda match: properties[match[1]], template), encoding='utf-8')
    args = instance / 'javac.args'
    cp = ';'.join(path.as_posix() for path in classpath)
    args.write_text(f'-cp "{cp}"\n' + '\n'.join(f'"{path.as_posix()}"' for path in
                    sorted((worktree / 'src/main/java').rglob('*.java'))), encoding='utf-8')
    print(f'Compiling worktree {worktree} -> {mod}', flush=True)
    subprocess.run([str(java.with_name('javac.exe')), '-proc:none', '-nowarn', '-encoding', 'UTF-8',
                    '-Xmaxerrs', '300', '-d', str(mod), '@' + str(args)], cwd=instance, check=True)
    return mod


def build_pack(builder, pack_root):
    # Use the selected tree's generator API: older trees restrict CLI outputs to their own checkout.
    builder.CONTROLS.clear()
    builder.museum_sites.TEXTS.clear()
    builder.validate()
    world = pack_root / 'saves' / builder.WORLD
    world.mkdir(parents=True, exist_ok=False)
    checks, shown, _ = builder.build_pack(world)
    if builder.museum_sites.VIOLATIONS:
        raise ValueError(f'Text displays do not fit: {builder.museum_sites.VIOLATIONS}')
    print(json.dumps({'output': str(pack_root), 'fields': len(builder.FIELDS),
                      'scenarios': len(builder.museum_scenarios.S), 'clip_actions': len(shown),
                      **{key: value for key, value in checks.items() if key != 'shown'}}, ensure_ascii=False), flush=True)
    return world / 'datapacks' / builder.NS


def wait_command(st, command, marker, timeout=120):
    offset = server.log_offset(st)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        server.run_cmd(st, command)
        if marker in server.log_text(st, offset):
            return
        time.sleep(0.2)
    raise TimeoutError(f'Timed out waiting for {marker}; see {st["rundir"]}/logs/latest.log')


def clear_all(st, fields):
    offset = server.log_offset(st)
    server.run_cmd(st, 'function mcylab:util/clear_all')
    # The generated reset removes every field's forced chunks only after all field clears return.
    # Its tellraw @a completion is invisible without players; observe the last removal instead.
    x, z = sorted(fields[-1].chunks())[-1]
    deadline = time.monotonic() + 120
    while time.monotonic() < deadline:
        server.run_cmd(st, f'execute store success score #smoke_reset museum run forceload query {x} {z}')
        server.run_cmd(st, 'execute if score #smoke_reset museum matches 0 run say MCYSMOKE_RESET_COMPLETE')
        if 'MCYSMOKE_RESET_COMPLETE' in server.log_text(st, offset):
            return
        time.sleep(0.2)
    raise TimeoutError('Museum clear_all did not finish; see ' + st['rundir'] + '/logs/latest.log')


def load_field(st, field, feet):
    chunks = sorted(field.chunks())
    for x, z in chunks:
        server.run_cmd(st, f'forceload add {x} {z}')
    loaded = ' '.join(f'if loaded {x} {feet} {z}' for x, z in chunks)
    wait_command(st, f'execute {loaded} run say MCYSMOKE_FIELD_READY', 'MCYSMOKE_FIELD_READY')


def failure(smoke, window):
    for line in window.splitlines():
        if any(re.search(pattern, line) for pattern in smoke['forbid']):
            return 'forbidden line: ' + line
    for pattern in smoke['expect']:
        if not re.search(pattern, window):
            return f'missing /{pattern}/'
    return None


def run_scene(st, scene, builder):
    clear_all(st, builder.FIELDS)
    load_field(st, builder.BY_ID[scene.field], builder.FEET)
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
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--worktree', type=Path, help='Compile this worktree into an isolated mod (no Gradle)')
    parser.add_argument('scenes', nargs='*', help='Scene IDs (default: all scenes with smoke metadata)')
    args = parser.parse_args()
    source = server.ROOT if args.worktree is None else args.worktree.resolve(strict=True)
    instances = server.INSTANCES if args.worktree is None else server.COMMON / '.local/smoke'
    if args.worktree is not None and instances.resolve().is_relative_to(source):
        parser.error('--worktree must not contain the main checkout smoke output directory; use a linked worktree')
    server.stop_stale(instances)
    server.require_closed()
    runtime, java = server.installed_runtime()
    sys.path[0] = str(source / 'scripts')
    builder = importlib.import_module('build_museum_full')
    scenes = {scene.id: scene for scene in builder.museum_scenarios.S if scene.smoke is not None}
    unknown = set(args.scenes) - scenes.keys()
    if unknown:
        parser.error('No smoke expectations for: ' + ', '.join(sorted(unknown)))
    selected = [scenes[sid] for sid in (args.scenes or scenes)]
    run_id = secrets.token_hex(6)
    name = f'instance-{run_id}'
    print(f'Self-check only; runtime={runtime.name}; scripts={source / "scripts"}; scenes={", ".join(s.id for s in selected)}', flush=True)
    datapack = build_pack(builder, instances / f'pack-{run_id}')
    server.require_closed()
    mod = (runtime / 'mod' if args.worktree is None else
           assemble_mod(source, runtime, java, instances / name))
    st = server.start(runtime, java, datapack, name, mod, instances)
    try:
        server.wait_log(st, r'MCYMUSEUM_BUILD_COMPLETE')
        results = [run_scene(st, scene, builder) for scene in selected]
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
