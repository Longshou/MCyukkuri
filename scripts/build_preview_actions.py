"""Build cached local animated action previews from preview/hub/actions.json.

Uses the review viewer's RuntimePose and software renderer; it never starts a browser,
Minecraft, Gradle, or Java. Final media is .local/previews/actions/<id>.{gif,png}.
"""
from __future__ import annotations

import argparse
import hashlib
import json
import re
import subprocess
from datetime import datetime, timezone
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
OUT = ROOT / '.local/previews/actions'
WORK = ROOT / '.local/previews/_work/actions'
SAFE_ID = re.compile(r'^[a-z0-9][a-z0-9_-]*$')


def sha(path: Path) -> str:
    return hashlib.sha256(path.read_bytes()).hexdigest()


def flatten(frame: Image.Image, background: str) -> Image.Image:
    match = re.fullmatch(r'#([0-9a-fA-F]{6})', background)
    if not match: raise ValueError(f'background must be #RRGGBB: {background}')
    canvas = Image.new('RGB', frame.size, f'#{match.group(1)}')
    canvas.paste(frame, mask=frame.getchannel('A'))
    return canvas


def normalise(raw: dict) -> list[dict]:
    clips = raw.get('clips')
    if not isinstance(clips, list) or not clips: raise ValueError('actions manifest needs a non-empty clips array')
    result = []
    for item in clips:
        clip = dict(item); ident = clip.get('id')
        if not isinstance(ident, str) or not SAFE_ID.fullmatch(ident): raise ValueError(f'Invalid clip id: {ident!r}')
        if not isinstance(clip.get('model'), str) or not isinstance(clip.get('action'), str): raise ValueError(f'{ident}: model and action are required')
        if not isinstance(clip.get('duration'), (int, float)) or clip['duration'] <= 0: raise ValueError(f'{ident}: positive duration is required')
        actors = clip.get('actors') or [dict(model=clip['model'], action=clip['action'])]
        for actor in actors:
            actor.setdefault('model', clip['model']); actor.setdefault('action', clip['action'])
            if not isinstance(actor['model'], str) or not isinstance(actor['action'], str): raise ValueError(f'{ident}: actor model/action must be strings')
            for name in ('speed', 'timeOffset', 'maxTime'):
                if name in actor and not isinstance(actor[name], (int, float)): raise ValueError(f'{ident}: actor.{name} must be numeric')
            if 'from' in actor and (not isinstance(actor['from'], list) or len(actor['from']) != 3): raise ValueError(f'{ident}: actor.from must be [x,y,z]')
            if 'to' in actor and (not isinstance(actor['to'], list) or len(actor['to']) != 3): raise ValueError(f'{ident}: actor.to must be [x,y,z]')
        for prop in clip.get('props', []):
            if not isinstance(prop.get('id'), str): raise ValueError(f'{ident}: prop id is required')
            if 'layers' in prop and (not isinstance(prop['layers'], int) or prop['layers'] < 0): raise ValueError(f'{ident}: prop.layers must be a non-negative integer')
            if 'from' in prop and (not isinstance(prop['from'], list) or len(prop['from']) != 3): raise ValueError(f'{ident}: prop.from must be [x,y,z]')
            if 'to' in prop and (not isinstance(prop['to'], list) or len(prop['to']) != 3): raise ValueError(f'{ident}: prop.to must be [x,y,z]')
        clip['actors'] = actors; result.append(clip)
    if len({item['id'] for item in result}) != len(result): raise ValueError('Action ids must be unique')
    return result


def prepared_textures(clips: list[dict]) -> tuple[dict, list[Path]]:
    texture_dir = WORK / 'textures'; texture_dir.mkdir(parents=True, exist_ok=True); textures = {}; sources = []
    models = {actor['model'] for clip in clips for actor in clip['actors']}
    for model in sorted(models):
        directory = ASSETS / 'textures/entity' / model
        if not directory.is_dir(): raise FileNotFoundError(f'Unknown actor model texture directory: {model}')
        for source in sorted(directory.glob('*.png')):
            image = Image.open(source).convert('RGBA'); width, height = image.size
            target = texture_dir / f'entity-{model}-{source.stem}.rgba'; target.write_bytes(image.tobytes())
            textures[f'entity:{model}:{source.stem}'] = {'file': str(target), 'width': width, 'height': height}; sources.append(source)
    for clip in clips:
        for prop in clip.get('props', []):
            ident = prop['id']; source = ASSETS / 'yukkuri_props' / f'{ident}.json'; image = ASSETS / 'textures/props' / f'{ident}.png'
            if not source.is_file() or not image.is_file(): raise FileNotFoundError(f'{clip["id"]}: unsupported prop {ident}; expected {source} and {image}')
            texture = Image.open(image).convert('RGBA'); width, height = texture.size
            target = texture_dir / f'prop-{ident}.rgba'; target.write_bytes(texture.tobytes())
            prop['source'] = str(source.relative_to(ROOT)); prop['textureKey'] = f'prop:{ident}'
            textures[prop['textureKey']] = {'file': str(target), 'width': width, 'height': height}; sources.extend((source, image))
    return textures, sources


def fingerprint(clip: dict, sources: list[Path], frames: int) -> str:
    digest = hashlib.sha256(json.dumps({'clip': clip, 'frames': frames}, ensure_ascii=False, sort_keys=True).encode())
    for source in [ROOT/'scripts/build_preview_actions.py', ROOT/'scripts/render_preview_actions.cjs', ROOT/'preview/review/pose.js', ROOT/'standards/yukkuri-library/runtime/render.js', ASSETS/'yukkuri_presentation/motion_corrections.json', ASSETS/'yukkuri_presentation/placement_fit.json', *sources]:
        digest.update(source.relative_to(ROOT).as_posix().encode()); digest.update(source.read_bytes())
    return digest.hexdigest()


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__); parser.add_argument('--manifest', type=Path, default=ROOT/'preview/hub/actions.json'); parser.add_argument('--node', default='node'); parser.add_argument('--frames', type=int, default=24); parser.add_argument('--force', action='store_true')
    args = parser.parse_args(); manifest_path = args.manifest.resolve()
    if args.frames < 2: parser.error('--frames must be at least 2')
    raw = json.loads(manifest_path.read_text(encoding='utf-8')); clips = normalise(raw)
    OUT.mkdir(parents=True, exist_ok=True); WORK.mkdir(parents=True, exist_ok=True)
    textures, resource_sources = prepared_textures(clips)
    by_model = {actor['model'] for clip in clips for actor in clip['actors']}; model_sources = [ASSETS/'yukkuri'/f'{model}.json' for model in sorted(by_model)]
    old_path = OUT/'manifest.json'; old = json.loads(old_path.read_text(encoding='utf-8')) if old_path.exists() else {}; previous = {item['id']: item for item in old.get('clips', [])}
    dirty = []; final = []
    for clip in clips:
        digest = fingerprint(clip, [*model_sources, *resource_sources], args.frames)
        saved = previous.get(clip['id']); png = OUT/f'{clip["id"]}.png'; gif = OUT/f'{clip["id"]}.gif'
        if args.force or not saved or saved.get('inputFingerprint') != digest or not png.is_file() or not gif.is_file(): dirty.append((clip, digest))
        else: final.append(saved)
    wanted = {clip['id'] for clip in clips}
    for path in OUT.glob('*'):
        if path.suffix in {'.png', '.gif'} and path.stem not in wanted: path.unlink()
    if dirty:
        task = {'width': 640, 'height': 440, 'frames': args.frames, 'work': str(WORK), 'textures': textures, 'clips': [clip for clip, _ in dirty]}
        task_path = WORK/'tasks.json'; task_path.write_text(json.dumps(task, ensure_ascii=False), encoding='utf-8')
        subprocess.run([args.node, str(ROOT/'scripts/render_preview_actions.cjs'), '--tasks', str(task_path)], check=True, cwd=ROOT)
        rendered = {item['id']: item for item in json.loads((WORK/'render-result.json').read_text())['clips']}
        for clip, digest in dirty:
            raw_frames = [Path(p).read_bytes() for p in rendered[clip['id']]['frames']]
            frames = [Image.frombytes('RGBA', (task['width'], task['height']), frame) for frame in raw_frames]
            frames[0].save(OUT/f'{clip["id"]}.png')
            flattened = [flatten(frame, clip.get('background', '#f3f0e8')) for frame in frames]
            palette = flattened[0].quantize(colors=256, method=Image.Quantize.MEDIANCUT)
            indexed = [frame.quantize(palette=palette, dither=Image.Dither.NONE) for frame in flattened]
            indexed[0].save(OUT/f'{clip["id"]}.gif', save_all=True, append_images=indexed[1:], loop=0,
                            duration=max(10, round(clip['duration']*1000/args.frames)), disposal=2, optimize=False)
            arrays = [np.asarray(frame) for frame in flattened]
            diffs = [int(np.any(arrays[i - 1] != arrays[i], axis=2).sum()) for i in range(1, len(arrays))]
            final.append({
                'id': clip['id'], 'title': clip.get('title', clip['id']), 'model': clip['model'],
                'action': clip['action'], 'duration': clip['duration'], 'frames': args.frames,
                'inputFingerprint': digest,
                'source': {'actors': clip['actors'], 'props': clip.get('props', []),
                           'camera': rendered[clip['id']]['camera']},
                'frameDifference': {
                    'changedPixelsMin': min(diffs) if diffs else 0,
                    'changedPixelsMax': max(diffs) if diffs else 0,
                    'changedPixelsTotal': sum(diffs),
                    'distinctFrames': len({hashlib.sha256(frame.tobytes()).hexdigest()
                                           for frame in flattened}),
                },
            })
    final.sort(key=lambda item: item['id'])
    output = {'schemaVersion': 1, 'generatedAt': datetime.now(timezone.utc).isoformat(), 'inputManifest': str(manifest_path.relative_to(ROOT)), 'frameWidth': 640, 'frameHeight': 440, 'clips': final}
    old_path.write_text(json.dumps(output, ensure_ascii=False, indent=2)+'\n', encoding='utf-8')
    print(f'Built {len(dirty)} action GIF(s); cache retained {len(final)-len(dirty)}.')


if __name__ == '__main__': main()
