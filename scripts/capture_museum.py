#!/usr/bin/env python
"""Capture museum scenes in an invisible client; visual self-check, never playtest acceptance."""
import argparse
from contextlib import contextmanager
import importlib
import json
import msvcrt
import secrets
import subprocess
import sys
import time
from pathlib import Path

sys.dont_write_bytecode = True
import smoke_museum as smoke
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
    chosen = [frames[round(i * (len(frames) - 1) / min(23, len(frames) - 1))]
              for i in range(min(24, len(frames)))] if len(frames) > 1 else frames
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
    parser.add_argument('scenes', nargs='+', help='Museum scene IDs')
    args = parser.parse_args()
    server.require_closed()
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
             for name, view in scene.capture.get('views', {'': dict(follow=scene.capture.get('follow'))}).items()]
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
        start_lines = (functions / f'start/{scene.id}.mcfunction').read_text(encoding='utf-8').splitlines()
        view = next((line for line in start_lines if line.startswith(('tp @s ', 'function mcylab:view/'))), f'function mcylab:go/{scene.field}')
        interactions = (f'function mcylab:smoke/{scene.id}/start\n'
                        if args.smoke_commands and scene.smoke and scene.smoke.get('commands') else '')
        camera = ''
        follow = settings.get('follow')
        if follow:
            offset = ' '.join(f'^{v}' for v in settings.get('offset', (-1.4, .65, .8)))
            camera = f'execute as @a run yukkuri observe off\nfunction mcylab:capture/{view_id}_camera\n'
            (functions / f'capture/{view_id}_camera.mcfunction').write_text(
                f'gamemode spectator @a\nexecute at {follow} rotated as {follow} positioned {offset} anchored eyes run tp @a ~ ~-1.62 ~ facing entity {follow} eyes\n'
                f'schedule function mcylab:capture/{view_id}_camera 1t replace\n', encoding='utf-8')
            clear = functions / f'field/{scene.field}/clear.mcfunction'
            with clear.open('a', encoding='utf-8') as output_file:
                output_file.write(f'\nschedule clear mcylab:capture/{view_id}_camera\n')
        (functions / f'capture/{view_id}.mcfunction').write_text(
            f'execute as @a at @s run function mcylab:start/{scene.id}\n{camera}' + interactions
            + ('execute as @a run yukkuri observe off\n' if scene.capture.get('observe') is False else ''), encoding='utf-8')
        plan.append(dict(id=view_id, field=scene.field, ticks=capture_ticks(scene.capture),
                         chunks=sorted(builder.BY_ID[scene.field].chunks()), view=view,
                         reads=[f'execute positioned {scene.anchor[0]}.0 {builder.FEET} {scene.anchor[1]}.0 run {command}'
                                for _, command in scene.reads]))
    (output / 'plan.json').write_text(json.dumps(plan), encoding='utf-8')
    mod = runtime / 'mod' if args.worktree is None else smoke.assemble_mod(source, runtime, java, instance)
    revision = subprocess.check_output(['git', '-C', str(source), 'rev-parse', 'HEAD'], text=True).strip()
    (output / 'run.json').write_text(json.dumps(dict(source=str(source), commit=revision, runtime=str(runtime),
        dirty=bool(subprocess.check_output(['git', '-C', str(source), 'status', '--porcelain'])),
        scenes=args.scenes, smoke_commands=args.smoke_commands, self_check=True), indent=2), encoding='utf-8')
    command = client_command(runtime, java, instance, mod, output)
    server.require_closed()
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
            timeout = 600 + sum(entry['ticks'][-1] / 20 * 3 for entry in plan)
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
