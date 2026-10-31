#!/usr/bin/env python
"""Run scene-owned log expectations on a throwaway dedicated server; a self-check, never playtest acceptance."""
import argparse
import re
import secrets
import subprocess
import sys
import time
from pathlib import Path

import headless_server as server
from museum_fields import BY_ID, FIELDS, FEET
from museum_scenarios import S


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
    parser.add_argument('scenes', nargs='*', help='Scene IDs (default: all scenes with smoke metadata)')
    args = parser.parse_args()
    unknown = set(args.scenes) - scenes.keys()
    if unknown:
        parser.error('No smoke expectations for: ' + ', '.join(sorted(unknown)))
    selected = [scenes[sid] for sid in (args.scenes or scenes)]
    server.stop_stale()
    server.require_closed()
    runtime, java = server.installed_runtime()
    run_id = secrets.token_hex(6)
    pack_root = server.INSTANCES / f'pack-{run_id}'
    print(f'Self-check only; runtime={runtime.name}; scenes={", ".join(s.id for s in selected)}', flush=True)
    subprocess.run([sys.executable, str(server.ROOT / 'scripts/build_museum_full.py'),
                    '--pack-only', '--output', str(pack_root)], cwd=server.ROOT, check=True)
    # Check again immediately before launch, since pack generation takes time.
    server.require_closed()
    datapack = pack_root / 'saves/MCyukkuri-Museum/datapacks/mcylab'
    st = server.start(runtime, java, datapack, f'instance-{run_id}')
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
