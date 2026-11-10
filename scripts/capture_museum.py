#!/usr/bin/env python
"""Capture museum scenes in an invisible client; visual self-check, never playtest acceptance."""
import argparse
from contextlib import contextmanager
import importlib
import json
import math
import msvcrt
import secrets
import subprocess
import sys
import time
from pathlib import Path

sys.dont_write_bytecode = True
import smoke_museum as smoke
import smoke_slots as slots
from capture_readout import postprocess, select_frames, geometry_report
from PIL import Image, ImageDraw

server = smoke.server


@contextmanager
def capture_lock():
    """One capture across all worktrees; Windows releases the file lock if its runner exits."""
    path = server.COMMON / '.local/captures/capture.lock'
    path.parent.mkdir(parents=True, exist_ok=True)
    with path.open('a+b') as lock:
        lock.seek(0)
        print(f'Acquiring capture lock (waiting if busy): {path}', flush=True)
        while True:
            try:
                msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
                break
            except PermissionError:
                time.sleep(1)
        try:
            yield
        finally:
            lock.seek(0)
            msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)


def capture_ticks(capture):
    """Scene-owned single ticks and (first, last, step) sequences; endpoints included."""
    ticks = set(capture['shots'])
    for first, last, step in capture['sequences']:
        ticks.update(range(first, last + 1, step))
    return sorted(ticks)


def client_command(runtime, java, instance, mod, output):
    vm = [arg for arg in server.read_args_file(runtime / 'clientRunVmArgs.txt')
          if not arg.startswith('-Dneoforge.enableGameTest')]
    cp = server.read_args_file(runtime / 'clientRunClasspath.txt')[:2]
    cp[1] = ';'.join(str(mod) if Path(entry) == runtime / 'mod' else entry for entry in cp[1].split(';'))
    prog = server.read_args_file(runtime / 'clientRunProgramArgs.txt')
    prog[prog.index('--gameDir') + 1] = str(instance)
    return ([str(java), '-Xmx6G'] + vm + cp + [f'-Dfml.modFolders=mcyukkuri%%{mod}',
            '-Dmcyukkuri.capture=true', f'-Dmcyukkuri.capture.output={output}',
            'net.neoforged.devlaunch.Main'] + prog + ['--mixin', 'mcyukkuri.capture.mixins.json',
            '--username', 'CapturePlayer', '--width', '1280', '--height', '720',
            '--quickPlaySingleplayer', 'MCyukkuri-Museum'])


def contact_sheet(directory):
    frames = json.loads((directory / 'frames.json').read_text(encoding='utf-8'))
    # Keep a readable overview; all full-resolution frames remain alongside it.
    poses = postprocess(directory, frames)
    chosen = select_frames(frames, poses)
    (directory / 'selected-frames.json').write_text(json.dumps(chosen, indent=2), encoding='utf-8')
    (directory / 'geometry.json').write_text(json.dumps(geometry_report(poses, frames), indent=2), encoding='utf-8')
    sheet = Image.new('RGB', (4 * 320, ((len(chosen) + 3) // 4) * 204), '#20252b')
    draw = ImageDraw.Draw(sheet)
    for i, frame in enumerate(chosen):
        x, y = i % 4 * 320, i // 4 * 204
        with Image.open(directory / frame['file']) as image:
            image.thumbnail((320, 180))
            sheet.paste(image.convert('RGB'), (x, y))
        draw.text((x + 5, y + 183), f"tick {frame['tick']} (asked {frame['requested']}) / {frame['seconds']:.2f}s", fill='white')
    path = directory / 'contact-sheet.jpg'
    sheet.save(path, quality=92)
    return frames, path


@capture_lock()
def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--worktree', type=Path, help='Assemble this tree with javac against installed CI dependencies')
    parser.add_argument('--smoke-commands', action='store_true', help='Run each scene\'s timed smoke interactions during capture')
    parser.add_argument('--vantage', action='store_true', help='Use the fixed visitor view checked by the server, without follow/observe cameras')
    parser.add_argument('--no-wait', action='store_true', help="Refuse instead of waiting while the user's museum client runs")
    parser.add_argument('--view', action='append', help='Capture only this declared full view ID; repeatable')
    parser.add_argument('--slots', type=int, help=f'Machine-wide limit of smoke/capture servers (default {slots.DEFAULT_SLOTS})')
    parser.add_argument('--sample-window', type=int, nargs=3, metavar=('FIRST', 'LAST', 'STEP'),
                        help='Capture only this tick interval; does not change scene preparation or behaviour')
    parser.add_argument('--tick-rate', type=float, default=20, help='Capture server ticks per second (1 <= rate <= 20)')
    parser.add_argument('scenes', nargs='+', help='Museum scene IDs')
    args = parser.parse_args()
    if not math.isfinite(args.tick_rate) or not 1 <= args.tick_rate <= 20:
        parser.error('--tick-rate must be finite and between 1 and 20')
    if args.sample_window and (args.sample_window[0] < 0 or args.sample_window[1] < args.sample_window[0] or args.sample_window[2] <= 0):
        parser.error('--sample-window requires 0 <= FIRST <= LAST and STEP > 0')
    with slots.slot('capture', ' '.join(args.scenes), args.slots):
        return capture(parser, args)


def capture(parser, args):
    server.wait_closed(not args.no_wait)
    runtime, java = server.installed_runtime()
    source = server.ROOT if args.worktree is None else args.worktree.resolve(strict=True)
    if args.worktree is None:
        smoke.require_runtime_commit(runtime)
    sys.path[0] = str(source / 'scripts')
    builder = importlib.import_module('build_museum_full')
    scenes = {scene.id: scene for scene in builder.museum_scenarios.S}
    unknown = set(args.scenes) - scenes.keys()
    if unknown:
        parser.error('Unknown scenes: ' + ', '.join(sorted(unknown)))
    selected = [scenes[sid] for sid in dict.fromkeys(args.scenes)]
    views = [(scene, f'{scene.id}_{name}' if name else scene.id, view)
             for scene in selected
             for name, view in ({'': {}} if args.vantage else scene.capture.get('views', {'': dict(follow=scene.capture.get('follow'))})).items()]
    if args.view:
        unknown_views=set(args.view)-{view_id for _,view_id,_ in views}
        if unknown_views:
            parser.error('Unknown views: '+', '.join(sorted(unknown_views)))
        views=[entry for entry in views if entry[1] in args.view]
    output = server.COMMON / '.local/captures' / (time.strftime('%Y%m%d-%H%M%S') + '-' + secrets.token_hex(3))
    instance = output / 'instance'
    pack = smoke.build_pack(builder, instance)
    # Read only the saved metadata seed, never the user's live world or its options.
    seed = server.COMMON / '.local/experimental-map/museum-seed/level.dat'
    builder.level_metadata(seed).save(pack.parent.parent / 'level.dat')
    (instance / 'options.txt').write_text(
        'lang:zh_cn\nguiScale:2\nrenderDistance:10\nsimulationDistance:8\nmaxFps:60\nenableVsync:false\n'
        'onboardAccessibility:false\nautoJump:false\npauseOnLostFocus:false\ntutorialStep:none\n'
        'narrator:0\nsoundCategory_master:0.0\nchatVisibility:2\n', encoding='utf-8')
    (instance / 'config').mkdir()
    (instance / 'config/fml.toml').write_text('earlyWindowControl=false\n', encoding='utf-8')
    plan = []
    functions = pack / 'data/mcylab/function'
    (functions / 'capture').mkdir()
    for scene, view_id, settings in views:
        settings = {key: value for key, value in settings.items() if value is not None}
        settings['tick_rate'] = args.tick_rate
        if 'look_at' in settings:
            x, y, z = settings['look_at']
            settings = dict(settings, look_at=(scene.anchor[0]+x, builder.FEET+y, scene.anchor[1]+z))
        start_lines = (functions / f'start/{scene.id}.mcfunction').read_text(encoding='utf-8').splitlines()
        view = next((line for line in start_lines if line.startswith(('tp @s ', 'function mcylab:view/'))), f'function mcylab:go/{scene.field}')
        interactions = (f'function mcylab:smoke/{scene.id}/start\n'
                        if args.smoke_commands and scene.smoke and scene.smoke.get('commands') else '')
        camera = ''
        geometry = ''
        if args.vantage:
            geometry = f'tag @a add mcyv\nfunction mcylab:capture/{view_id}_geometry\n'
            (functions / f'capture/{view_id}_geometry.mcfunction').write_text(
                f'yukkuri scenario geometry record\nschedule function mcylab:capture/{view_id}_geometry 20t replace\n', encoding='utf-8')
            with (functions / f'field/{scene.field}/clear.mcfunction').open('a', encoding='utf-8') as output_file:
                output_file.write(f'\nschedule clear mcylab:capture/{view_id}_geometry\n')
        follow = settings.get('follow')
        if follow:
            settings = dict(settings, offset=settings.get('offset', (-1.4, .65, .8)))
            camera = f'execute as @a run yukkuri observe off\nfunction mcylab:capture/{view_id}_camera\n'
            (functions / f'capture/{view_id}_camera.mcfunction').write_text(
                f'gamemode spectator @a\nexecute at {follow} run tp @a ~4 ~2 ~4 facing entity {follow} eyes\n'
                f'schedule function mcylab:capture/{view_id}_camera 1t replace\n', encoding='utf-8')
            clear = functions / f'field/{scene.field}/clear.mcfunction'
            with clear.open('a', encoding='utf-8') as output_file:
                output_file.write(f'\nschedule clear mcylab:capture/{view_id}_camera\n')
        (functions / f'capture/{view_id}.mcfunction').write_text(
            f'yukkuri status trace dense\nexecute as @a at @s run function mcylab:start/{scene.id}\n{camera}' + interactions
            + ('execute as @a run yukkuri observe off\n' if args.vantage or scene.capture.get('observe') is False else '')
            + geometry, encoding='utf-8')
        ticks = capture_ticks(scene.capture) if args.sample_window is None else list(range(
            args.sample_window[0], args.sample_window[1] + 1, args.sample_window[2]))
        plan.append(dict(id=view_id, field=scene.field, ticks=ticks, settings=settings,
                         milestones=scene.smoke.get('milestones', []) if scene.smoke else [],
                         chunks=sorted(builder.BY_ID[scene.field].chunks()), view=view,
                         reads=[f'execute positioned {scene.anchor[0]}.0 {builder.FEET} {scene.anchor[1]}.0 run {command}'
                                for _, command in scene.reads]))
    (output / 'plan.json').write_text(json.dumps(plan), encoding='utf-8')
    mod = runtime / 'mod' if args.worktree is None else smoke.assemble_mod(source, runtime, java, instance)
    revision = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
    (output / 'run.json').write_text(json.dumps(dict(source=str(source), commit=revision, runtime=str(runtime),
        dirty=bool(subprocess.check_output(['git', '-C', str(source), 'status', '--porcelain'])),
        scenes=args.scenes, smoke_commands=args.smoke_commands, vantage=args.vantage, self_check=True), indent=2), encoding='utf-8')
    command = client_command(runtime, java, instance, mod, output)
    server.wait_closed(not args.no_wait)
    startup = subprocess.STARTUPINFO()
    startup.dwFlags |= subprocess.STARTF_USESHOWWINDOW
    startup.wShowWindow = 0
    print(f'Capture output: {output}', flush=True)
    with (output / 'stdout.log').open('wb') as log:
        proc = subprocess.Popen(command, cwd=instance, stdin=subprocess.DEVNULL, stdout=log,
                                stderr=subprocess.STDOUT, startupinfo=startup,
                                creationflags=subprocess.CREATE_NO_WINDOW)
        try:
            # World generation/loading plus real-time scene duration; no tick sprint during visual capture.
            timeout = 600 + sum(entry['ticks'][-1] / args.tick_rate * 3 for entry in plan)
            deadline = time.monotonic() + timeout
            while proc.poll() is None:
                if list((instance / 'crash-reports').glob('*.txt')):
                    raise RuntimeError(f'Client crash report: {instance / "crash-reports"}')
                if time.monotonic() > deadline:
                    raise TimeoutError(f'Capture timed out; see {output / "stdout.log"}')
                time.sleep(1)
        finally:
            if proc.poll() is None:
                proc.kill()
                proc.wait()
    if proc.returncode or not (output / 'complete.json').is_file():
        raise RuntimeError(f'Capture failed (exit {proc.returncode}); see {output / "stdout.log"}')
    for scene, view_id, follow in views:
        frames, sheet = contact_sheet(output / view_id)
        if any(frame['visible'] or frame['focused'] for frame in frames):
            raise RuntimeError(f'{view_id}: capture window was visible or focused')
        print(f'{view_id}: {len(frames)} frames, {frames[-1]["seconds"]:.2f}s; hidden/unfocused; {sheet}', flush=True)
    return 0


if __name__ == '__main__':
    started = time.monotonic()
    try:
        raise SystemExit(main())
    except (OSError, ValueError, RuntimeError, subprocess.SubprocessError) as error:
        print(f'ERROR: {error}', file=sys.stderr)
        raise SystemExit(1)
    finally:
        print(f'Wall time: {time.monotonic() - started:.1f}s', flush=True)
