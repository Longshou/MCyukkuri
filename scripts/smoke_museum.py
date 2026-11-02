#!/usr/bin/env python
"""Run scene-owned log expectations on a throwaway dedicated server; a self-check, never playtest acceptance."""
import argparse
import importlib
import json
import math
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


# Blocks a body may occupy (air, plants, the cushion of a nest); anything else is a wall.
STAND = ['minecraft:air', 'minecraft:cave_air', 'minecraft:void_air', 'minecraft:short_grass', 'minecraft:tall_grass', 'minecraft:fern',
         'minecraft:large_fern', 'minecraft:dead_bush', 'minecraft:snow', 'minecraft:water', '#minecraft:flowers', '#minecraft:wool_carpets',
         'mcyukkuri:yukkuri_nest', 'mcyukkuri:large_yukkuri_nest']


def build_pack(builder, pack_root):
    # Use the selected tree's generator API: older trees restrict CLI outputs to their own checkout.
    builder.CONTROLS.clear()
    builder.museum_sites.TEXTS.clear()
    builder.validate()
    world = pack_root / 'saves' / builder.WORLD
    world.mkdir(parents=True, exist_ok=False)
    checks, shown, _ = builder.build_pack(world)
    pack = world / 'datapacks' / builder.NS
    for scene in builder.museum_scenarios.S:
        commands = scene.smoke.get('commands', ()) if scene.smoke is not None else ()
        if not commands:
            continue
        directory = pack / 'data' / builder.NS / 'function' / 'smoke' / scene.id
        directory.mkdir(parents=True)
        x, z = scene.anchor
        for i, (tick, command) in enumerate(commands):
            (directory / f'{i}.mcfunction').write_text(
                f'execute positioned {x}.0 {builder.FEET} {z}.0 run {command}\n', encoding='utf-8')
        (directory / 'start.mcfunction').write_text(''.join(
            f'schedule function {builder.NS}:smoke/{scene.id}/{i} {tick}t\n'
            for i, (tick, _) in enumerate(commands)), encoding='utf-8')
    tags = pack / 'data' / builder.NS / 'tags/block'
    tags.mkdir(parents=True)
    (tags / 'stand.json').write_text(json.dumps({'values': STAND}), encoding='utf-8')
    if builder.museum_sites.VIOLATIONS:
        raise ValueError(f'Text displays do not fit: {builder.museum_sites.VIOLATIONS}')
    print(json.dumps({'output': str(pack_root), 'fields': len(builder.FIELDS),
                      'scenarios': len(builder.museum_scenarios.S), 'clip_actions': len(shown),
                      **{key: value for key, value in checks.items() if key != 'shown'}}, ensure_ascii=False), flush=True)
    return pack


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
    # Vanilla RCON reads at most 1460 bytes per packet and drops the connection on a longer command,
    # so the readiness questions go out in bounded batches, one marker each.
    batch = 40
    for start in range(0, len(chunks), batch):
        group = chunks[start:start + batch]
        marker = f'MCYSMOKE_FIELD_READY_{start // batch}'
        loaded = ' '.join(f'if loaded {x} {feet} {z}' for x, z in group)
        wait_command(st, f'execute {loaded} run say {marker}', marker)


def failure(smoke, window):
    for line in window.splitlines():
        if any(re.search(pattern, line) for pattern in smoke['forbid']):
            return 'forbidden line: ' + line
    for pattern in smoke['expect']:
        if not re.search(pattern, window):
            return f'missing /{pattern}/'
    if 'verify' in smoke:
        return smoke['verify'](window)
    return None


def run_scene(st, scene, builder):
    clear_all(st, builder.FIELDS)
    load_field(st, builder.BY_ID[scene.field], builder.FEET)
    offset = server.log_offset(st)
    server.run_cmd(st, f'function mcylab:start/{scene.id}')
    if 'commands' in scene.smoke:
        server.run_cmd(st, f'function mcylab:smoke/{scene.id}/start')
    x, z = scene.anchor
    elapsed = 0
    if 'reload' in scene.smoke:
        elapsed, selector = scene.smoke['reload']
        server.sprint(st, elapsed)
        command = f'execute positioned {x}.0 {builder.FEET} {z}.0 run data get entity {selector}'
        paths = ('Riders', 'Passengers[0].UUID', 'Passengers[1].UUID')
        before = [server.run_cmd(st, f'{command} {path}') for path in paths]
        server.run_cmd(st, 'save-all flush')
        for cx, cz in sorted(builder.BY_ID[scene.field].chunks()):
            server.run_cmd(st, f'forceload remove {cx} {cz}')
        server.sprint(st, 400)
        if 'No entity was found' not in server.run_cmd(st, f'{command} UUID'):
            raise RuntimeError(f'{scene.id}: reload fixture did not unload')
        load_field(st, builder.BY_ID[scene.field], builder.FEET)
        server.sprint(st, 1)
        after = [server.run_cmd(st, f'{command} {path}') for path in paths]
        normalize = lambda rows: re.sub(r'Yaw: [-.\d]+f', 'Yaw: observed', '\n'.join(rows))
        (Path(st['rundir']).parent / f'{scene.id}.reload.txt').write_text('\n'.join(before + after), encoding='utf-8')
        if normalize(before) != normalize(after):
            raise RuntimeError(f'{scene.id}: saved rider coordinates or passenger identities changed on reload')
        print(f'PASS {scene.id}: rider coordinates and passenger identities survived chunk reload', flush=True)
    server.sprint(st, scene.smoke['ticks'] - elapsed)
    readout = '\n'.join(server.run_cmd(st, f'execute positioned {x}.0 {builder.FEET} {z}.0 run {command}')
                        for command in scene.smoke.get('readout', ()))
    (Path(st['rundir']).parent / f'{scene.id}.readout.txt').write_text(readout, encoding='utf-8')
    window = server.log_text(st, offset)
    (Path(st['rundir']).parent / f'{scene.id}.log').write_text(window, encoding='utf-8')
    problem = failure(scene.smoke, window)
    if problem:
        print(f'FAIL {scene.id}: {problem}', flush=True)
    else:
        print(f'PASS {scene.id}: {len(scene.smoke["expect"])} expected patterns; no forbidden lines', flush=True)
    return problem is None


def numbers(text):
    return [float(v) for v in re.findall(r'(-?\d+(?:\.\d+)?(?:E-?\d+)?)[df]', text)]


def standing(st, builder, x, y, z, reach=.3):
    """Two open cells under every corner of the body (``reach`` each way), over a solid floor unless the body floats in water."""
    stand = f'#{builder.NS}:stand'
    cells = {(math.floor(x + dx), math.floor(z + dz)) for dx in (-reach, reach) for dz in (-reach, reach)}
    y = math.floor(y)
    open_cells = lambda cx, cz: f'execute if block {cx} {y} {cz} {stand} if block {cx} {y + 1} {cz} {stand}'
    return all('passed' in server.run_cmd(st, f'{open_cells(cx, cz)} unless block {cx} {y - 1} {cz} {stand} unless block {cx} {y - 1} {cz} #minecraft:leaves')
               or 'passed' in server.run_cmd(st, f'{open_cells(cx, cz)} if block {cx} {y} {cz} minecraft:water') for cx, cz in cells)


def check_layout(st, builder, scene):
    """Start the scene the way its button does and read the generated world: every scenario body and the player's vantage must
    stand in open air (or shallow water) on a solid floor, and the bodies must lie inside the scene's field. Time is frozen so nothing has moved."""
    problems = []
    server.run_cmd(st, 'tick freeze')
    server.run_cmd(st, f'function {builder.NS}:start/{scene.id}', timeout=180)
    read = lambda path: server.run_cmd(st, f'execute as @e[tag=scn] run data get entity @s {path}')
    positions = [numbers(row) for row in re.findall(r'\[[^\]]*\]', read('Pos'))]
    names = re.findall(r'\[[^\]]*\]', read('Tags'))
    assert len(positions) == len(names), (scene.id, len(positions), len(names))
    field = builder.BY_ID[scene.field]
    for (x, y, z), who in zip(positions, names):
        if not standing(st, builder, x, y, z):
            problems.append(f'{who} at ({x:.1f}, {y:.1f}, {z:.1f}) is not in open air on a solid floor')
        if not (field.x1 <= x <= field.x2 + 1 and field.z1 <= z <= field.z2 + 1):
            problems.append(f'{who} at ({x:.1f}, {z:.1f}) lies outside field {field.id}')
    if scene.vantage is not None:
        server.run_cmd(st, 'summon minecraft:marker 0 100 0 {Tags:["mcyv"]}')
        server.run_cmd(st, f'execute as @e[tag=mcyv,limit=1] run function {builder.NS}:view/{scene.id}')
        x, y, z = numbers(server.run_cmd(st, 'data get entity @e[tag=mcyv,limit=1] Pos'))
        server.run_cmd(st, 'kill @e[tag=mcyv]')
        if not standing(st, builder, x, y, z):
            problems.append(f'player vantage ({x:.1f}, {y:.1f}, {z:.1f}) is not in open air on a solid floor')
    server.run_cmd(st, f'function {builder.NS}:field/{scene.field}/clear', timeout=180)
    server.run_cmd(st, 'tick unfreeze')
    for problem in problems:
        print(f'FAIL {scene.id}: {problem}', flush=True)
    if not problems:
        print(f'PASS {scene.id}: {len(positions)} bodies and the vantage stand free inside the field', flush=True)
    return not problems


def require_runtime_commit(runtime):
    """Without --worktree the mod is the installed runtime, so it must be this checkout: an older package would run
    this tree's scenes against code that lacks their intents, and that reads as a scene failure."""
    installed = json.loads((runtime / 'mcyukkuri-runtime.json').read_text(encoding='utf-8-sig'))['commit']
    current = subprocess.check_output(['git', '-C', str(server.ROOT), 'rev-parse', 'HEAD'], text=True).strip()
    if installed != current:
        raise RuntimeError(f'Installed runtime is {installed[:9]} but this checkout is {current[:9]}: update it with '
                           f'scripts/update_museum_runtime.py --run <build run id>, or pass --worktree to compile this tree')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--worktree', type=Path, help='Compile this worktree into an isolated mod (no Gradle)')
    parser.add_argument('--layout', action='store_true', help='Check the generated world of every scene (default: all scenes) instead of the smoke expectations')
    parser.add_argument('scenes', nargs='*', help='Scene IDs (default: all scenes with smoke metadata)')
    args = parser.parse_args()
    source = server.ROOT if args.worktree is None else args.worktree.resolve(strict=True)
    instances = server.INSTANCES if args.worktree is None else server.COMMON / '.local/smoke'
    if args.worktree is not None and instances.resolve().is_relative_to(source):
        parser.error('--worktree must not contain the main checkout smoke output directory; use a linked worktree')
    server.stop_stale(instances)
    server.require_closed()
    runtime, java = server.installed_runtime()
    if args.worktree is None:
        require_runtime_commit(runtime)
    sys.path[0] = str(source / 'scripts')
    builder = importlib.import_module('build_museum_full')
    scenes = {scene.id: scene for scene in builder.museum_scenarios.S if args.layout or scene.smoke is not None}
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
        if args.layout:
            for field in {builder.BY_ID[scene.field] for scene in selected}:
                load_field(st, field, builder.FEET)
            results = [check_layout(st, builder, scene) for scene in selected]
        else:
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
