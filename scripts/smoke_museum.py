#!/usr/bin/env python
"""Run scene-owned log expectations and the actor fate check (smoke_fate.py) on a throwaway dedicated server;
a self-check, never playtest acceptance."""
import argparse
from contextlib import contextmanager
import hashlib
import importlib
import inspect
import json
import msvcrt
import os
import re
import secrets
import shutil
import subprocess
import sys
import tarfile
import time
from pathlib import Path
from zipfile import ZipFile

sys.dont_write_bytecode = True
import headless_server as server
import smoke_fate as fate
import smoke_slots as slots
import smoke_checks

RESULTS = server.COMMON / '.local/smoke/results'
BASELINE = server.COMMON / '.local/smoke/baseline'


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
    # The launcher discovers bundled libraries such as MixinExtras at runtime. javac needs them as separate jars.
    with ZipFile(merged) as archive:
        for member in archive.namelist():
            if member.startswith('META-INF/jarjar/') and member.endswith('.jar'):
                library = instance / 'compile-libs' / Path(member).name
                library.parent.mkdir(parents=True, exist_ok=True)
                library.write_bytes(archive.read(member))
                classpath.append(library)
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


def build_pack(builder, pack_root, layout=False):
    # Use the selected tree's generator API: older trees restrict CLI outputs to their own checkout.
    builder.CONTROLS.clear()
    builder.museum_sites.TEXTS.clear()
    builder.validate()
    world = pack_root / 'saves' / builder.WORLD
    world.mkdir(parents=True, exist_ok=False)
    checks, shown, _ = builder.build_pack(world)
    pack = world / 'datapacks' / builder.NS
    if not layout:
        observation = pack / 'data' / builder.NS / 'function/observe'
        observation.mkdir()
        (observation / 'first.mcfunction').write_text('yukkuri scenario geometry record\n', encoding='utf-8')
        (observation / 'loop.mcfunction').write_text(
            'yukkuri scenario geometry record\nschedule function mcylab:observe/loop 20t replace\n', encoding='utf-8')
        for path in (pack / 'data' / builder.NS / 'function/scn').glob('*.mcfunction'):
            if path.stem not in {s.id for s in builder.museum_scenarios.S}:
                continue
            with path.open('a', encoding='utf-8') as output:
                output.write('\nschedule function mcylab:observe/first 1t replace\n'
                             'schedule function mcylab:observe/loop 20t replace\n')
    if layout:
        (pack / 'data' / builder.NS / 'function/layout_ready.mcfunction').write_text('say MCYLAYOUT_READY\n', encoding='utf-8')
        # Observe creation results at the point they occur, before death/consumption can remove an actor.
        for path in (pack / 'data' / builder.NS / 'function/scn').glob('*.mcfunction'):
            lines = []
            for number, line in enumerate(path.read_text(encoding='utf-8').splitlines(), 1):
                if re.search(r'(?:^|run )(?:summon |yukkuri settlement spawn |yukkuri scenario (?:spawn|afloat) |yukkuri human build |yukkuri human walker )', line):
                    # A guarded retry writes its own success score and returns it; replacing that line would leave the
                    # score unset and the retry would summon again every attempt, so only observe the score it writes.
                    if line.startswith('execute store success score '):
                        score = ' '.join(line.split()[4:6])
                        lines += [line,
                                  f'execute if score {score} matches 0 run say MCYLAYOUT_MISSING {path.stem}:{number}',
                                  f'execute if score {score} matches 1.. run yukkuri scenario geometry record']
                        continue
                    command = line.removeprefix('return run ')
                    lines += [f'execute store result score #layout_created museum run {command}',
                              f'execute if score #layout_created museum matches 0 run say MCYLAYOUT_MISSING {path.stem}:{number}',
                              'execute if score #layout_created museum matches 1.. run yukkuri scenario geometry record']
                    if line.startswith('return run '):
                        lines.append('return run scoreboard players get #layout_created museum')
                else:
                    lines.append(line)
            path.write_text('\n'.join(lines) + '\n', encoding='utf-8')
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
    """The scene's own check: one (stable reason, printed text) per failed check -- every forbidden pattern that hit and
    every expected pattern that is missing, so one fix round sees the whole list."""
    found = []
    lines = window.splitlines()
    for pattern in smoke['forbid']:
        hits = [line for line in lines if re.search(pattern, line)]
        if hits:
            count = f' ({len(hits)} lines)' if len(hits) > 1 else ''
            found.append((f'forbidden /{pattern}/', f'forbidden line{count}: ' + hits[0]))
    for pattern in smoke['expect']:
        if not re.search(pattern, window):
            found.append((f'missing /{pattern}/', f'missing /{pattern}/'))
    problem = smoke['verify'](window) if 'verify' in smoke else None
    if problem is not None:
        found.append((fate.signature(problem)[:200], problem))
    return found


def run_seed(session_seed, run_index):
    """The seed of one run: the session seed itself for a session's first run, so a single run reproduces with
    ``--seed <that run's seed>``, and a hash of both for every later run (fixed-UUID scene actors would otherwise replay
    one random stream across repeats and overstate the pass count)."""
    if run_index == 1:
        return session_seed
    digest = hashlib.sha256(f'{session_seed}:{run_index}'.encode('utf-8')).digest()
    return int.from_bytes(digest[:8], 'big') & ((1 << 63) - 1)


# Checks that decide the run. Calibration on main 0c830e89b (31 scenes x 20) left no false positive for these two, so a fail
# verdict here is a scene problem. FATE stays with fate.fates above (the same death must not be counted twice), LAG measures
# the machine load of this instrumented run and VANTAGE the scene's fixed framing: both stay report-only.
GATING_CHECKS = frozenset({'ORPHAN', 'SPAWN'})


def gated_problems(reports, checks=GATING_CHECKS):
    """One (check, printed text) per gating-check finding that failed, with the report's own actor and tick; the reason
    key is the check name, so main and branch compare by check."""
    return [(finding['check'],
             f'{finding["check"]} {finding["detail"]} (actor={finding["actor"]} t={finding["t"]})')
            for report in reports for finding in report['findings']
            if finding['check'] in checks and finding['verdict'] == 'fail']


def run_scene(st, scene, builder, run_index, seed):
    server.run_cmd(st, 'schedule clear mcylab:observe/loop')
    server.run_cmd(st, 'schedule clear mcylab:observe/first')
    clear_all(st, builder.FIELDS)
    load_field(st, builder.BY_ID[scene.field], builder.FEET)
    server.run_cmd(st, 'yukkuri status trace dense')
    server.run_cmd(st, 'kill @e[tag=mcyv]')
    server.run_cmd(st, 'summon minecraft:marker 0 100 0 {Tags:["mcyv"]}')
    server.jfr_start(st, f'{scene.id}-{run_index}')
    offset = server.log_offset(st)
    # The run's seed before the scene's own actors exist, inside this run's window so its log names its seed.
    server.run_cmd(st, f'yukkuri status seed {seed}')
    # Execute as the virtual observer so the scene's own view sets its exact position/yaw/pitch in the same tick.
    server.run_cmd(st, f'execute as @e[tag=mcyv,limit=1] run function mcylab:start/{scene.id}')
    if scene.vantage is None:
        server.run_cmd(st, f'execute as @e[tag=mcyv,limit=1] run function mcylab:go/{scene.field}')
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
        normalize = lambda rows: re.sub(r'Yaw: [-+.\deE]+f', 'Yaw: observed', '\n'.join(rows))
        (Path(st['rundir']).parent / f'{scene.id}.reload.txt').write_text('\n'.join(before + after), encoding='utf-8')
        if normalize(before) != normalize(after):
            raise RuntimeError(f'{scene.id}: saved rider coordinates or passenger identities changed on reload')
        print(f'PASS {scene.id}: rider coordinates and passenger identities survived chunk reload', flush=True)
    server.sprint(st, scene.smoke['ticks'] - elapsed)
    readout = '\n'.join(server.run_cmd(st, f'execute positioned {x}.0 {builder.FEET} {z}.0 run {command}')
                        for command in scene.smoke.get('readout', ()))
    (Path(st['rundir']).parent / f'{scene.id}-{run_index}.readout.txt').write_text(readout, encoding='utf-8')
    end_tick = re.search(r'\d+', server.run_cmd(st, 'time query gametime'))[0]
    log = Path(st['rundir']).parent / f'{scene.id}-{run_index}.log'
    legacy = []
    nonempty = False
    end_offset = server.log_offset(st)
    with log.open('w', encoding='utf-8') as output:
        for line in server.log_lines(st, offset, end_offset):
            nonempty |= bool(line.strip())
            output.write(line)
            if '[trace]' not in line:
                legacy.append(line)
        output.write(f'\nMCYSMOKE_END_TICK {end_tick}\n')
    window = ''.join(legacy) + f'\nMCYSMOKE_END_TICK {end_tick}\n'
    problems = [] if nonempty else [('empty log', 'empty server log window')]
    problems += failure(scene.smoke, window)
    problems += fate.fates(window, scene.smoke.get('allow_fate', ()))
    rerun = subprocess.list2cmdline([sys.executable, str(Path(__file__).resolve()), '--worktree',
                    str(builder.ROOT), '--seed', str(seed), scene.id])
    observations_dir = log.with_suffix('')
    reports = smoke_checks.report_log(log, observations_dir, {scene.id: scene.smoke}, seed, rerun, dense=True,
                                      only=scene.id)
    gating_checks = GATING_CHECKS.union(scene.smoke.get('gating_checks', ()))
    problems += gated_problems(reports, gating_checks)
    for _, text in problems:
        print(f'FAIL {scene.id}: {text}', flush=True)
    if not problems:
        print(f'PASS {scene.id}: {len(scene.smoke["expect"])} expected patterns; no forbidden lines; no undeclared actor fate; '
              f'no gating check failure ({", ".join(sorted(gating_checks))})', flush=True)
    jfr = server.jfr_summary(st, f'{scene.id}-{run_index}')
    (observations_dir / 'jfr.json').write_text(json.dumps(jfr, indent=2), encoding='utf-8')
    summary = [r for r in reports if r.get('scene') == scene.id]
    for report in summary:
        print(f'REPORT {scene.id}: seed={seed} ' + ' '.join(f'{r["check"]}={r["verdict"]}' for r in report['summary']), flush=True)
    print(f'JFR {scene.id}: {jfr["verdict"]}; {jfr.get("top_methods", [])[:3]}; {observations_dir}', flush=True)
    return dict(scene=scene.id, index=run_index, log=str(log), passed=not problems,
                seed=seed, rerun=rerun, observations=str(observations_dir / 'checks.json'), jfr=jfr,
                reasons=list(dict.fromkeys(reason for reason, _ in problems)), problems=[text for _, text in problems])


def geometry_rows(reply):
    return [json.loads(row) for row in re.findall(r'MCYGEOMETRY (\{[^\n]*?\})(?=MCY|\r?\n|$)', reply)]


def geometry(st):
    reply = server.run_cmd(st, 'yukkuri scenario geometry')
    if 'MCYGEOMETRY_END' not in reply:
        raise RuntimeError('Incomplete geometry response: ' + reply)
    rows = geometry_rows(reply)
    if sum(row['player'] for row in rows) != 1:
        raise RuntimeError('Missing player geometry: ' + reply)
    return rows


def check_layout(st, builder, scene):
    """Inspect actual actors, command-created props, and the player's body after initialization."""
    problems = []
    field = builder.BY_ID[scene.field]
    rect = scene.plot
    server.run_cmd(st, 'tick freeze')
    offset = server.log_offset(st)
    server.run_cmd(st, 'summon minecraft:marker 0 100 0 {Tags:["mcyv"]}')
    existing = {row['uuid'] for row in geometry(st)}
    server.run_cmd(st, f'function {builder.NS}:start/{scene.id}', timeout=180)
    view = f'view/{scene.id}' if scene.vantage is not None else f'go/{scene.field}'
    server.run_cmd(st, f'execute as @e[tag=mcyv,limit=1] run function {builder.NS}:{view}')
    snapshots = []
    inspected = set()
    unsupported = {}
    previous_tick = 0
    geometry_offset = offset
    for tick in sorted({0, 40, scene.layout_ticks}):
        # Step in short runs: a scene waiting for a guarded summon steps a few hundred ticks, and one long step stops
        # emitting the end-of-step marker.
        while previous_tick < tick:
            step = min(40, tick - previous_tick)
            ready_offset = server.log_offset(st)
            server.run_cmd(st, f'schedule function {builder.NS}:layout_ready {step}t replace')
            server.run_cmd(st, f'tick step {step}')
            server.wait_log(st, 'MCYLAYOUT_READY', ready_offset, timeout=120)
            previous_tick += step
        # Creation records run synchronously before the actor's first physics tick (including delayed flying predators).
        created = [row for row in geometry_rows(server.log_text(st, geometry_offset)) if not row['player']]
        geometry_offset = server.log_offset(st)
        rows = [row for row in created + geometry(st) if row['player'] or row['uuid'] not in existing
                or (field.x1 <= row['box'][0] <= field.x2 and field.z1 <= row['box'][2] <= field.z2)]
        snapshots.append(dict(tick=tick, entities=rows))
        for row in rows:
            box = row['box']
            who = 'player' if row['player'] else f'{row["type"]} {row["tags"]} {row["uuid"]}'
            if not row['clear']:
                problems.append(f't={tick} {who} intersects blocks box={box}')
            # Allow ordinary settling during initialization; a supported actor's later jump is not a bad spawn.
            if row['supported']:
                unsupported.pop(row['uuid'], None)
            elif row['uuid'] not in inspected:
                unsupported[row['uuid']] = f'{who} has no support through initialization; first box={box}'
            inspected.add(row['uuid'])
            # Scenes which keep the player on the observation deck assign that deck as its viewing area.
            area = field.deck if row['player'] and scene.vantage is None else rect
            if not (area[0] <= box[0] and box[3] <= area[2] + 1 and area[1] <= box[2] and box[5] <= area[3] + 1):
                problems.append(f't={tick} {who} outside assigned plot {area} box={box}')
        # Fields can run concurrently. Their actual footprints must never enter another scene's field.
        for row in rows:
            b = row['box']
            for other in builder.FIELDS:
                if other.id != scene.field and b[0] < other.x2 + 1 and b[3] > other.x1 and b[2] < other.z2 + 1 and b[5] > other.z1:
                    problems.append(f't={tick} {row["uuid"]} overlaps field {other.id}')
    problems += unsupported.values()
    for setup in scene.late:
        if isinstance(setup, builder.museum_scenarios.Retry) and setup.command.startswith('summon '):
            expected = re.search(r'Tags:\["scn","([^"]+)"', setup.command)[1]
            if not any(expected in row['tags'] for sample in snapshots for row in sample['entities']):
                problems.append('missing expected delayed actor ' + expected)
    problems += re.findall(r'MCYLAYOUT_MISSING[^\n]*', server.log_text(st, offset))
    problems = list(dict.fromkeys(problems))
    report = Path(st['rundir']).parent / f'{scene.id}.layout.json'
    report.write_text(json.dumps(dict(scene=scene.id, plot=rect, snapshots=snapshots, problems=problems), indent=2), encoding='utf-8')
    server.run_cmd(st, 'kill @e[tag=mcyv]')
    server.run_cmd(st, f'function {builder.NS}:field/{scene.field}/clear', timeout=180)
    server.run_cmd(st, 'tick unfreeze')
    for problem in problems:
        print(f'FAIL {scene.id}: {problem}', flush=True)
    if not problems:
        print(f'PASS {scene.id}: {len(inspected) - 1} actors/props and player; initialized t={scene.layout_ticks}', flush=True)
    return not problems


def require_runtime_commit(runtime):
    """Without --worktree the mod is the installed runtime, so it must be this checkout: an older package would run
    this tree's scenes against code that lacks their intents, and that reads as a scene failure."""
    installed = json.loads((runtime / 'mcyukkuri-runtime.json').read_text(encoding='utf-8-sig'))['commit']
    current = subprocess.check_output(['git', '-C', str(server.ROOT), 'rev-parse', 'HEAD'], text=True).strip()
    if installed != current:
        raise RuntimeError(f'Installed runtime is {installed[:9]} but this checkout is {current[:9]}: update it with '
                           f'scripts/update_museum_runtime.py --run <build run id>, or pass --worktree to compile this tree')


def git(tree, *command):
    return subprocess.check_output(['git', '-C', str(tree), *command], text=True).strip()


def load_builder(source):
    """The scene definitions of ``source``; a process loads one tree (its modules are cached by name)."""
    sys.path[0] = str(source / 'scripts')
    return importlib.import_module('build_museum_full')


def smoke_spec(smoke):
    """A scene's own checks as comparable values: what must appear, what must not, the verify body and the timing."""
    if smoke is None:
        return None
    verify = smoke.get('verify')
    return dict(expect=list(smoke['expect']), forbid=list(smoke['forbid']),
                verify=None if verify is None else ' '.join(inspect.getsource(verify).split()),
                ticks=smoke.get('ticks'), reload=list(smoke['reload']) if 'reload' in smoke else None)


# The child's own copy of smoke_spec: it reads origin/main's scenes, so it cannot borrow this tree's extractor without
# importing this tree's museum_scenarios too.
SPEC_DUMP = '''
import inspect, json, sys

sys.dont_write_bytecode = True
sys.path.insert(0, sys.argv[1])
import museum_scenarios


def spec(smoke):
    if smoke is None:
        return None
    verify = smoke.get('verify')
    return dict(expect=list(smoke['expect']), forbid=list(smoke['forbid']),
                verify=None if verify is None else ' '.join(inspect.getsource(verify).split()),
                ticks=smoke.get('ticks'), reload=list(smoke['reload']) if 'reload' in smoke else None)


print(json.dumps({scene.id: spec(scene.smoke) for scene in museum_scenarios.S}))
'''

# What origin/main's scenes need to import: their own scripts tree and the one resource museum_dsl reads beside it.
MAIN_SCENE_INPUTS = ('scripts', 'src/main/resources/assets/mcyukkuri/yukkuri_presentation/care_collision.json')


def smoke_specs_on_main(source):
    """origin/main's scene checks, read in a child process from that commit's own scripts tree: loading a second
    museum_scenarios beside this one would register its scenes into this tree's fields a second time (and trip its own
    pond-tour assertion), so that commit is exported and imported on its own."""
    commit = git(source, 'rev-parse', 'origin/main')
    tree = server.COMMON / '.local/tmp' / f'museum-scripts-{commit[:12]}-{os.getpid()}'
    tree.mkdir(parents=True)
    try:
        archive = subprocess.Popen(['git', '-C', str(source), 'archive', 'origin/main', *MAIN_SCENE_INPUTS],
                                   stdout=subprocess.PIPE)
        with tarfile.open(fileobj=archive.stdout, mode='r|') as tar:
            tar.extractall(tree, filter='data')
        # Git pads tar output beyond its end marker; drain the pipe before waiting for Git to exit.
        archive.stdout.read()
        if archive.wait():
            raise subprocess.CalledProcessError(archive.returncode,
                                                'git archive origin/main ' + ' '.join(MAIN_SCENE_INPUTS))
        reply = subprocess.run([sys.executable, '-c', SPEC_DUMP, str(tree / 'scripts')], capture_output=True,
                               text=True, encoding='utf-8')
        if reply.returncode:
            reason = reply.stderr.strip().splitlines()[-1] if reply.stderr.strip() else reply.returncode
            raise RuntimeError(f'origin/main scenes did not load: {reason}')
        return json.loads(reply.stdout)
    finally:
        shutil.rmtree(tree, ignore_errors=True)


def shorten(text, limit=80):
    return text if len(text) <= limit else text[:limit - 3] + '...'


def smoke_differences(here, main):
    """Every way this tree's scene checks differ from origin/main's, in words (empty when they agree)."""
    if main is None:
        return ['no smoke checks on origin/main']
    differences = []
    for key in ('expect', 'forbid'):
        differences += [f'{key} only on origin/main /{shorten(pattern)}/'
                        for pattern in main[key] if pattern not in here[key]]
        differences += [f'{key} only here /{shorten(pattern)}/' for pattern in here[key] if pattern not in main[key]]
    if here['verify'] != main['verify']:
        differences.append('verify body differs')
    if here['ticks'] != main['ticks']:
        differences.append(f'ticks {main["ticks"]} -> {here["ticks"]}')
    if here['reload'] != main['reload']:
        differences.append(f'reload {main["reload"]} -> {here["reload"]}')
    return differences


def warn_smoke_drift(source, scenes):
    """WARNING lines naming every scene whose smoke checks (expect / forbid / verify / timing) differ from origin/main's:
    a worker once relaxed an expectation in its own tree, and its self-check then proved less than the scene asks. This
    only warns; the scene still runs and reports as its own tree defines it."""
    try:
        main = smoke_specs_on_main(source)
    except (subprocess.CalledProcessError, OSError, RuntimeError, ValueError, SyntaxError, ImportError,
            AttributeError, TypeError, KeyError) as error:
        print(f'WARNING scene checks: cannot read origin/main\'s scenes ({error}); they were not compared', flush=True)
        return
    here = {scene.id: smoke_spec(scene.smoke) for scene in scenes}
    for sid, spec in here.items():
        differences = smoke_differences(spec, main.get(sid))
        if differences:
            print(f'WARNING scene {sid}: smoke checks differ from origin/main: ' + '; '.join(differences), flush=True)


def session(source, compile_tree, builder, scenes, args, label, stop=None):
    """One server for ``scenes`` in order (repeats included), holding a machine-wide slot; one result per run."""
    instances = server.COMMON / '.local/smoke' if compile_tree else server.INSTANCES
    server.stop_stale(instances)
    run_id = secrets.token_hex(6)
    name = f'instance-{run_id}'
    with slots.slot('smoke', label, args.slots):
        server.wait_closed(not args.no_wait)
        # Read inside the slot: a runtime update while this session queued removes the runtime it would have read earlier.
        runtime, java = server.installed_runtime()
        if not compile_tree:
            require_runtime_commit(runtime)
        print(f'Self-check only; runtime={runtime.name}; scripts={source / "scripts"}; scenes={", ".join(s.id for s in scenes)}', flush=True)
        datapack = build_pack(builder, instances / f'pack-{run_id}', args.layout)
        mod = assemble_mod(source, runtime, java, instances / name) if compile_tree else runtime / 'mod'
        server.wait_closed(not args.no_wait)
        st = server.start(runtime, java, datapack, name, mod, instances)
        try:
            server.wait_log(st, r'MCYMUSEUM_BUILD_COMPLETE')
            if 'Failed to load function' in server.log_text(st):
                raise RuntimeError('Generated functions failed to load; see ' + st['rundir'] + '/logs/latest.log')
            if args.layout:
                for field in {builder.BY_ID[scene.field] for scene in scenes}:
                    load_field(st, field, builder.FEET)
                return [check_layout(st, builder, scene) for scene in scenes]
            results = []
            for index, scene in enumerate(scenes, 1):
                reason = stop() if stop else None
                if reason:
                    print(f'Stopping before {scene.id}: {reason}', flush=True)
                    break
                results.append(run_scene(st, scene, builder, index, run_seed(args.seed, index)))
            return results
        finally:
            server.stop(st)


def tally(results):
    """Per scene: runs, passes and, for each stable failure reason, the number of runs it occurred in."""
    table = {}
    for run in results:
        row = table.setdefault(run['scene'], dict(runs=0, passed=0, reasons={}))
        row['runs'] += 1
        row['passed'] += run['passed']
        for reason in run['reasons']:
            row['reasons'][reason] = row['reasons'].get(reason, 0) + 1
    return table


def reasons_text(reasons):
    """Failure reasons with the number of runs each occurred in (long patterns shortened; the JSON keeps them whole)."""
    return '; '.join(f'{reason if len(reason) <= 160 else reason[:157] + "..."} ({count})'
                     for reason, count in sorted(reasons.items(), key=lambda item: -item[1]))


def print_table(table, title):
    print(title, flush=True)
    width = max([len(sid) for sid in table] + [5])
    for sid, row in table.items():
        print(f'  {sid:<{width}}  {row["passed"]}/{row["runs"]} passed  {reasons_text(row["reasons"])}'.rstrip(), flush=True)


@contextmanager
def baseline_lock(commit):
    """One baseline run per commit across worktrees; a later one waits, then runs only what is still missing."""
    BASELINE.mkdir(parents=True, exist_ok=True)
    with (BASELINE / f'{commit}.lock').open('a+b') as lock:
        lock.seek(0)
        announced = False
        while True:
            try:
                msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
                break
            except PermissionError:
                if not announced:
                    print(f'Another baseline run of {commit[:9]} is in progress; waiting for it', flush=True)
                    announced = True
                time.sleep(5)
        try:
            yield
        finally:
            lock.seek(0)
            msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)


def export_tree(commit):
    """The commit's sources that the smoke compiles and builds from (the 4 GB of art in a full checkout are not read)."""
    tree = BASELINE / f'tree-{commit[:12]}'
    if tree.exists():
        shutil.rmtree(tree)  # left by an interrupted baseline run of this commit, whose lock we now hold
    tree.mkdir(parents=True)
    archive = subprocess.Popen(['git', '-C', str(server.COMMON), 'archive', commit, 'gradle.properties', 'scripts', 'src'],
                               stdout=subprocess.PIPE)
    with tarfile.open(fileobj=archive.stdout, mode='r|') as tar:
        tar.extractall(tree, filter='data')
    archive.stdout.read()
    if archive.wait():
        raise subprocess.CalledProcessError(archive.returncode, 'git archive ' + commit)
    return tree


def baseline(commit, ids, args, stop=None):
    """Top the cached main baseline of ``commit`` up to ``args.repeat`` runs per scene (counted for this fate checker)
    on an exported tree of that commit; ``ids`` None means every smoke scene of the commit. Returns its scene table."""
    path = BASELINE / f'{commit}.json'
    with baseline_lock(commit):
        data = json.loads(path.read_text(encoding='utf-8')) if path.is_file() else dict(commit=commit, absent=[], scenes={})

        def have(sid):
            row = data['scenes'].get(sid)
            return row['runs'] if row is not None and row['checker'] == fate.CHECKER else 0

        if ids and all(sid in data['absent'] or have(sid) >= args.repeat for sid in ids):
            return data['scenes']
        tree = export_tree(commit)
        try:
            builder = load_builder(tree)
            available = {scene.id: scene for scene in builder.museum_scenarios.S if scene.smoke is not None}
            wanted = ids or list(available)
            absent = [sid for sid in wanted if sid not in available]
            if absent:
                print(f'No smoke expectations on {commit[:9]}, so no baseline: {", ".join(absent)}', flush=True)
                data['absent'] = sorted(set(data['absent']) | set(absent))
            need = {sid: args.repeat - have(sid) for sid in wanted if sid in available and have(sid) < args.repeat}
            scenes = [available[sid] for turn in range(max(need.values(), default=0)) for sid in need if need[sid] > turn]
            for scene in scenes:
                fate.check_allowances(scene.smoke.get('allow_fate', ()))
            results = session(tree, True, builder, scenes, args, f'baseline {commit[:9]}', stop) if scenes else []
        finally:
            shutil.rmtree(tree)
        for sid, row in tally(results).items():
            if have(sid):
                old = data['scenes'][sid]
                row = dict(runs=old['runs'] + row['runs'], passed=old['passed'] + row['passed'],
                           reasons={key: old['reasons'].get(key, 0) + row['reasons'].get(key, 0)
                                    for key in old['reasons'] | row['reasons']})
            data['scenes'][sid] = dict(checker=fate.CHECKER, updated=time.strftime('%Y-%m-%d %H:%M:%S'), **row)
        pending = path.with_suffix('.tmp')
        pending.write_text(json.dumps(data, indent=2, ensure_ascii=False), encoding='utf-8')
        pending.replace(path)
    return data['scenes']


def compare(source, args, table):
    """Top up the main baseline of the merge-base (only missing runs, in its own session) and print branch vs main."""
    commit = git(source, 'merge-base', 'HEAD', 'origin/main')
    command = [sys.executable, str(Path(__file__).resolve()), '--baseline', '--base-commit', commit, '--repeat', str(args.repeat),
               *(['--no-wait'] if args.no_wait else []), *(['--slots', str(args.slots)] if args.slots else []), *table]
    baseline_ok = True
    if subprocess.run(command).returncode:
        print('The baseline run did not finish; comparing with what is cached, and failing this comparison', flush=True)
        baseline_ok = False
    path = BASELINE / f'{commit}.json'
    cached = json.loads(path.read_text(encoding='utf-8'))['scenes'] if path.is_file() else {}
    print(f'Branch vs main {commit[:9]} (fate checker {fate.CHECKER}):', flush=True)
    width = max([len(sid) for sid in table] + [5])
    for sid, row in table.items():
        base = cached.get(sid)
        if base is None or base['checker'] != fate.CHECKER:
            main_text, only = 'no baseline', row['reasons']
        else:
            main_text = f'{base["passed"]}/{base["runs"]}'
            only = {reason: count for reason, count in row['reasons'].items() if reason not in base['reasons']}
        extra = f'  only on the branch: {reasons_text(only)}' if only else ''
        print(f'  {sid:<{width}}  branch {row["passed"]}/{row["runs"]}  main {main_text}{extra}', flush=True)
    return baseline_ok


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--worktree', type=Path, help='Compile this worktree into an isolated mod (no Gradle)')
    parser.add_argument('--layout', action='store_true', help='Check the generated world of every scene (default: all scenes) instead of the smoke expectations')
    parser.add_argument('--repeat', type=int, default=1, help='Run every scene N times in one server session and print pass counts')
    parser.add_argument('--baseline', action='store_true',
                        help='Run the merge-base of this tree with origin/main instead, topping its cached counts up to --repeat runs')
    parser.add_argument('--base-commit', help='With --baseline: this commit instead of the merge-base')
    parser.add_argument('--compare', action='store_true', help='Then top up the main baseline (only missing runs) and print branch vs main')
    parser.add_argument('--seed', type=int, default=secrets.randbits(63), help='Session seed; each run derives its own seed from it and its run index (see the trace seed log)')
    parser.add_argument('--no-wait', action='store_true', help="Refuse instead of waiting while the user's museum client runs")
    parser.add_argument('--slots', type=int, help=f'Machine-wide limit of smoke/capture servers (default {slots.DEFAULT_SLOTS} or %%{slots.ENVIRONMENT}%%)')
    parser.add_argument('scenes', nargs='*', help='Scene IDs (default: all scenes with smoke metadata)')
    args = parser.parse_args()
    if (args.repeat < 1 or args.slots is not None and args.slots < 1
            or args.layout and (args.repeat > 1 or args.baseline or args.compare) or args.baseline and args.compare):
        parser.error('--repeat and --slots are at least 1; --layout takes no --repeat, --baseline or --compare; '
                     '--baseline excludes --compare')
    source = server.ROOT if args.worktree is None else args.worktree.resolve(strict=True)
    if args.baseline:
        commit = args.base_commit or git(source, 'merge-base', 'HEAD', 'origin/main')
        table = baseline(commit, args.scenes or None, args)
        print_table({sid: table[sid] for sid in dict.fromkeys(args.scenes or table) if sid in table},
                    f'Main baseline {commit[:9]} (fate checker {fate.CHECKER}):')
        return 0
    builder = load_builder(source)
    scenes = {scene.id: scene for scene in builder.museum_scenarios.S if args.layout or scene.smoke is not None}
    unknown = set(args.scenes) - scenes.keys()
    if unknown:
        parser.error('No smoke expectations for: ' + ', '.join(sorted(unknown)))
    selected = [scenes[sid] for sid in (args.scenes or scenes)]
    label = f'{source.name}: ' + ' '.join(dict.fromkeys(scene.id for scene in selected))
    if args.layout:
        return 0 if all(session(source, args.worktree is not None, builder, selected, args, label)) else 1
    for scene in selected:
        fate.check_allowances(scene.smoke.get('allow_fate', ()))
    warn_smoke_drift(source, selected)
    commit, dirty = git(source, 'rev-parse', 'HEAD'), bool(git(source, 'status', '--porcelain'))
    results = session(source, args.worktree is not None, builder, [scene for _ in range(args.repeat) for scene in selected], args, label)
    table = tally(results)
    print_table(table, f'Results {commit[:9]}{" with uncommitted changes" if dirty else ""} (fate checker {fate.CHECKER}):')
    RESULTS.mkdir(parents=True, exist_ok=True)
    record = RESULTS / f'{commit}-{time.strftime("%Y%m%d-%H%M%S")}-{secrets.token_hex(3)}.json'
    record.write_text(json.dumps(dict(commit=commit, dirty=dirty, source=str(source), checker=fate.CHECKER, repeat=args.repeat,
                                      scenes=table, runs=results), indent=2, ensure_ascii=False), encoding='utf-8')
    print(f'Results written to {record}', flush=True)
    comparison_ok = compare(source, args, table) if args.compare else True
    return 0 if all(run['passed'] for run in results) and comparison_ok else 1


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
