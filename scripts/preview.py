"""One offline preview command; run a subcommand with --help for its inputs."""
import argparse
import base64
from contextlib import contextmanager
import html
import hashlib
from html.parser import HTMLParser
import json
import math
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import tarfile
import tempfile
from urllib.parse import urlencode

from PIL import Image, ImageDraw, ImageFont, ImageOps

ROOT = Path(__file__).resolve().parents[1]
SHARED = Path(subprocess.check_output(
    ['git', 'rev-parse', '--path-format=absolute', '--git-common-dir'], cwd=ROOT,
    text=True).strip()).parent
OUT = Path(os.environ.get('MCYUKKURI_PREVIEWS', SHARED / '.local/previews')).resolve()
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
STYLE = 'body{font:16px/1.6 sans-serif;margin:24px;background:#222;color:#eee}a{color:#8cf}img,video{max-width:100%}figure{margin:16px 0}pre{white-space:pre-wrap}iframe{width:100%;height:80vh}small{color:#bbb}'
E = html.escape


def font(size):
    """Shared CJK lookup for preview layouts."""
    for name in ('C:/Windows/Fonts/msyh.ttc', '/System/Library/Fonts/STHeiti Medium.ttc',
                 '/Library/Fonts/Arial Unicode.ttf', '/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc'):
        if Path(name).is_file():
            return ImageFont.truetype(name, size)
    raise RuntimeError('Install a CJK font for preview labels')


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def write_json(path, value):
    path.write_text(json.dumps(value, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


def page(title, body):
    return (f'<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>{E(title)}</title>'
            f'<style>{STYLE}</style><body><a href="../index.html">预览总入口</a><h1>{E(title)}</h1>{body}</body></html>')


def media(path, caption=''):
    name = E(path, quote=True)
    tag = (f'<video controls preload="metadata" src="{name}"></video>' if Path(path).suffix.lower() in ('.mp4', '.webm')
           else f'<img loading="lazy" src="{name}" alt="{E(caption)}">')
    return f'<figure>{tag}<figcaption>{E(caption)}</figcaption></figure>'


def valid_topic(value):
    if not re.fullmatch(r'[a-zA-Z0-9][a-zA-Z0-9_-]*', value) or value in ('models', 'library', 'scenes', 'inbox', 'playtest'):
        raise argparse.ArgumentTypeError('topic must be a non-reserved English ID')
    return value


@contextmanager
def staging(topic):
    """Keep the current topic intact on any input/render/encode failure."""
    tmp = ROOT / '.local/tmp'
    tmp.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix='preview-', dir=tmp) as directory:
        stage = Path(directory)
        yield stage
        target = OUT / topic
        target.mkdir(parents=True, exist_ok=True)
        # Keep authoring inputs and original references; replace only this invocation's outputs.
        for source in stage.iterdir():
            if source.is_file():
                temporary = target / (source.name + '.tmp')
                shutil.copy2(source, temporary)
                os.replace(temporary, target / source.name)


def sheet(images, labels, target, columns=4, cell=(360, 260)):
    w, h = cell
    result = Image.new('RGB', (w * min(columns, len(images)), (h + 32) * math.ceil(len(images) / columns)), '#eee8dd')
    draw = ImageDraw.Draw(result)
    label_font = font(17)
    for i, (image, label) in enumerate(zip(images, labels)):
        x, y = i % columns * w, i // columns * (h + 32)
        thumb = ImageOps.contain(image, cell)
        result.paste(thumb, (x + (w-thumb.width)//2, y), thumb if thumb.mode == 'RGBA' else None)
        draw.text((x+6, y+h+4), label, font=label_font, fill='#222')
    result.save(target)


def timed(value):
    match = re.fullmatch(r'(\d+(?:\.\d+)?)(s|t|ticks?)?', value.strip())
    if not match:
        raise ValueError(f'Bad beat time: {value!r}; use seconds (0.5s) or ticks (10t)')
    number = float(match[1])
    return number / 20 if match[2] and match[2] != 's' else number


def beat_times(args):
    if args.beats:
        rows = [line.split('#', 1)[0].strip() for line in Path(args.beats).read_text(encoding='utf-8').splitlines()]
        values = [row.split(maxsplit=1)[0] for row in rows if row]
    else:
        values = args.times.split(',')
    times = [timed(v) for v in values]
    if not times or any(b <= a for a, b in zip(times, times[1:])):
        raise ValueError('Bad beat time: times must be nonempty and strictly increasing')
    return times


def actor(model, prop, clip, offset, root=ROOT):
    assets = root/'src/main/resources/assets/mcyukkuri'
    path = assets / ('yukkuri_props' if prop else 'yukkuri') / f'{prop or model}.json'
    data = read(path)
    if prop:
        textures = []
        for part in data['parts']:
            ref = part.get('texture', f'mcyukkuri:textures/props/{prop}.png')
            namespace, relative = ref.split(':', 1)
            base = assets if namespace == 'mcyukkuri' else Path(os.environ.get(
                'MCYUKKURI_VANILLA', SHARED / '.local/vanilla-ref/tex/assets/minecraft'))
            uv = [data['vertices'][i*8+3:i*8+5] for i in range(part['start'], part['start']+part['count'])]
            low = [math.floor(min(v[c] for v in uv)) for c in range(2)]
            extent = [max(1, math.ceil(max(v[c] for v in uv))-low[c]) for c in range(2)]
            textures.append((base / relative, part.get('tint'), low, extent))
    else:
        textures = [(assets / 'textures/entity' / model / 'neutral.png', None, [0, 0], [1, 1])]
    return dict(id=prop or model, prop=bool(prop), path=str(path), clip=clip, offset=offset,
                scale=1.5 if model in ('reimu_deibu', 'reimu_wasa_deibu') and not prop else 1), textures


def before_checkout(value, stage):
    """Resolve a before checkout, or archive just the renderer inputs from a Git ref."""
    path = Path(value).resolve()
    if path.is_dir():
        return path
    result = subprocess.run(['git', 'rev-parse', '--verify', value+'^{commit}'], cwd=ROOT,
                            text=True, capture_output=True)
    if result.returncode:
        raise ValueError(f'--before is neither a checkout nor a Git commit: {value}')
    ref = result.stdout.strip()
    work = stage/'before-source'
    work.mkdir()
    archive = work/'source.tar'
    subprocess.run(['git', 'archive', '--format=tar', '--output', str(archive), ref, '--',
                    'src/main/resources/assets/mcyukkuri', 'standards/yukkuri-library/runtime/render.js'],
                   cwd=ROOT, check=True)
    root = work/'checkout'
    root.mkdir()
    with tarfile.open(archive) as source:
        source.extractall(root, filter='data')
    return root


def render_strip(args, stage, times, before=False):
    source_root = Path(args.before).resolve() if before else ROOT
    # A before reference is another checkout's actual model, fit and motion inputs.
    actors, textures = [], []
    specs = [(args.model, args.prop, [0, 0, 0])]
    if args.partner:
        specs.append((args.partner, None, args.partner_offset))
    for model, prop, offset in specs:
        a, images = actor(model, prop, args.clip, offset, source_root)
        if getattr(args, 'face_preset', None) and not before and not prop:
            from runtime_face_library import compose_preset
            preset_path = Path(args.face_preset).resolve()
            preset = read(preset_path)
            selection = read(ROOT/'standards/yukkuri-library/expressions/runtime.json')
            row = selection['models'][model]
            library = preset_path.parents[2]
            common = {e['name']: e for e in read(library/'components/common/face.json')['elements']}
            atlas, patches = compose_preset(read(a['path']), preset, ROOT/row['neutral_source'], common, library=library)
            a['face'] = preset_path.stem
            a['draftFace'] = patches
            draft_textures = stage/'draft-textures'
            draft_textures.mkdir(exist_ok=True)
            images = [(draft_textures/f'{model}.png',None,[0,0],[1,1])]
            atlas.save(images[0][0])
        elif getattr(args, 'face', None) and not prop:
            from face_usage import resolve
            library = read(source_root/'src/main/resources/assets/mcyukkuri/yukkuri_presentation/face_library.json')
            face = resolve(library,model,args.face)
            a['face'] = face
            images = [(source_root/'src/main/resources/assets/mcyukkuri/textures/entity'/model/(face+'.png'),None,[0,0],[1,1])]
            animation = library['animations'].get(face)
            if animation:
                images.append((images[0][0].with_name(animation['frames'][1]+'.png'),None,[0,0],[1,1]))
        if getattr(args, 'moment', None) and not prop:
            from face_usage import resolve
            a['moment'] = args.moment
            library = read(ASSETS/'yukkuri_presentation/face_library.json')
            names = {'neutral'} | {face for step in args.moment['steps'] for _, face in step['faces']}
            names = {resolve(library, model, face) for face in names}
            names |= {phase for face in list(names) for phase in library['animations'].get(face, {}).get('frames', [])}
            names = sorted(names)
            a['faceSlots'] = {face: len(textures)+i for i,face in enumerate(names)}
            images = [(ASSETS/'textures/entity'/model/(face+'.png'),None,[0,0],[1,1]) for face in names]
        a['slots'] = list(range(len(textures), len(textures)+len(images)))
        textures.extend(images)
        actors.append(a)
    loaded = []
    for path, tint, low, extent in textures:
        with Image.open(path) as image:
            image = image.convert('RGBA')
            if image.width != image.height:
                raise ValueError(f'Non-square texture: {path}')
            if tint:
                channels = image.split()
                image = Image.merge('RGBA', tuple(channels[i].point(lambda v, c=tint[i]: round(v*c/255)) for i in range(3)) + (channels[3],))
            repeated = Image.new('RGBA', (image.width*extent[0], image.height*extent[1]))
            for x in range(extent[0]):
                for y in range(extent[1]):
                    repeated.paste(image, (x*image.width, y*image.height))
            loaded.append((repeated, image.width, low))
    tile = max(max(im.size) for im, _, _ in loaded)
    side = math.ceil(math.sqrt(len(loaded))) * tile
    atlas = Image.new('RGBA', (side, side))
    slots = []
    for i, (image, texture_size, low) in enumerate(loaded):
        x, y = i % (side//tile)*tile, i // (side//tile)*tile
        atlas.paste(image, (x, y))
        slots.append(dict(x=x, y=y, size=texture_size, low=low))
    with tempfile.TemporaryDirectory(dir=stage) as directory:
        work = Path(directory)
        texture = work/'texture.rgba'
        texture.write_bytes(atlas.tobytes())
        job = dict(root=str(source_root), actors=actors, times=times, slots=slots, textureSize=side,
                   texture=str(texture), width=args.size[0], height=args.size[1], work=str(work),
                   az=math.radians(args.view[0]), el=math.radians(args.view[1]))
        if before:
            job['camera'] = read(stage/'camera.json')
        write_json(work/'job.json', job)
        subprocess.run([args.node, str(ROOT/'preview/hub/render.cjs'), str(work/'job.json')], check=True)
        if not before:
            shutil.copy2(work/'camera.json', stage/'camera.json')
        frames = [Image.frombytes('RGBA', tuple(args.size), (work/f'{i}.rgba').read_bytes()) for i in range(len(times))]
    return frames


def strip(args, stage):
    times = beat_times(args)
    views = args.view or [[26, 10]]
    before_root = before_checkout(args.before, stage) if args.before else None
    frames, labels, pairs, comparison_labels = [], [], [], []
    for view in views:
        view_args = argparse.Namespace(**{**vars(args), 'view': view,
                                       'before': str(before_root) if before_root else None})
        current = render_strip(view_args, stage, times)
        view_labels = [f'{t:g}s / {t*20:g} tick' for t in times]
        if len(views) > 1:
            view_labels = [f'AZ {view[0]:g}° EL {view[1]:g}° · {label}' for label in view_labels]
        frames.extend(current)
        labels.extend(view_labels)
        if before_root:
            old = render_strip(view_args, stage, times, before=True)
            pairs.extend(im for pair in zip(old, current) for im in pair)
            comparison_labels.extend(f'{label} {version}' for label in view_labels for version in ('原版本', '当前'))
    columns = len(times) if len(views) > 1 else args.columns
    sheet(frames, labels, stage/'strip.png', columns, tuple(args.size))
    atlas = Image.new('RGBA', (args.size[0]*len(frames), args.size[1]))
    for i, image in enumerate(frames):
        atlas.paste(image, (i*args.size[0], 0))
    atlas.save(stage/'atlas.png')
    write_json(stage/'atlas.json', dict(image='atlas.png', width=args.size[0], height=args.size[1], times=times,
               views=[dict(az=v[0], el=v[1]) for v in views]))
    body = f'<p>{E(args.model or args.prop)} / {E(args.clip)}；离线片段，行为效果由试玩确认。</p>' + media('strip.png')
    if before_root:
        sheet(pairs, comparison_labels, stage/'compare.png', 2*len(times) if len(views) > 1 else 2, tuple(args.size))
        body += media('compare.png', '相同视角与时刻：原版本 / 当前')
    body += '<a href="atlas.png">帧图集</a> · <a href="atlas.json">时刻数据</a>'
    return body


def capture_rows(directory, bounds):
    rows = read(directory/'frames.json')
    if not rows:
        raise ValueError(f'No capture frames: {directory}')
    if any(not isinstance(r['tick'], int) for r in rows):
        raise ValueError(f'Capture ticks must be integers: {directory}')
    # The client clock is corrected backwards to the server's mid-scene, so a scene tick can repeat (same animation
    # occurrence, later render). The frames stay in the recorded render order and the timing follows that order.
    if all('seconds' in r for r in rows) and any(b['seconds'] <= a['seconds'] for a, b in zip(rows, rows[1:])):
        raise ValueError(f'Capture frames must be in render order: {directory}')
    for row in rows:
        path = directory/row['file']
        if not path.is_file():
            raise ValueError(f'Missing frame: {path}')
        with Image.open(path) as image:
            image.verify()
    selected = [r for r in rows if not bounds or bounds[0] <= r['tick'] <= bounds[1]]
    if len(selected) < 2:
        raise ValueError(f'Tick range needs at least two frames: {directory}')
    return selected


def crop_frame(directory, row, crop, zoom):
    with Image.open(directory/row['file']) as original:
        image = original.convert('RGB')
    if crop:
        x, y, w, h = crop
        if min(x, y) < 0 or min(w, h) <= 0 or x+w > image.width or y+h > image.height:
            raise ValueError(f'Crop outside frame: {row["file"]}')
        image = image.crop((x, y, x+w, y+h))
    if zoom != 1:
        image = image.resize((round(image.width*zoom), round(image.height*zoom)))
    return image


def ffmpeg_path():
    matches = sorted(p for p in (SHARED/'.local/tools/video-decode/imageio_ffmpeg/binaries').glob('ffmpeg*') if p.is_file())
    if not matches:
        raise ValueError('ffmpeg missing in .local/tools/video-decode/imageio_ffmpeg/binaries/')
    return matches[0]


def capture(args, stage):
    directory = Path(args.source).resolve()
    rows = capture_rows(directory, args.ticks)
    other = Path(args.compare).resolve() if args.compare else None
    other_rows = capture_rows(other, None) if other else []
    # Tick identity is necessary for an honest side-by-side comparison.
    by_tick = {r['tick']: r for r in other_rows}
    if other and any(r['tick'] not in by_tick for r in rows):
        raise ValueError('Comparison run is missing a selected tick')
    exe = ffmpeg_path()
    samples, labels, durations = [], [], []
    # Contact sheets sample evenly; the video and full-size crops retain every selected frame.
    indices = set(round(i*(len(rows)-1)/max(1, min(args.count, len(rows))-1)) for i in range(min(args.count, len(rows))))
    with tempfile.TemporaryDirectory(dir=stage) as directory_name:
        work = Path(directory_name)
        lines = ['ffconcat version 1.0']
        for i, row in enumerate(rows):
            image = crop_frame(directory, row, args.crop, args.zoom)
            if other:
                right = crop_frame(other, by_tick[row['tick']], args.crop, args.zoom)
                joined = Image.new('RGB', (image.width+right.width, max(image.height, right.height)))
                joined.paste(image, (0, 0)); joined.paste(right, (image.width, 0)); image = joined
            image.save(work/f'{i}.png')
            # A repeated scene tick still shows for one tick instead of a zero or negative frame duration.
            duration = max(rows[i+1]['tick']-row['tick'], 1)/20 if i+1 < len(rows) else 1/20
            durations.append(duration)
            lines += [f"file '{i}.png'", 'option framerate 20', f'duration {duration:.9f}']
            if i in indices:
                samples.append(image)
                labels.append(f'#{i+1} · tick {row["tick"]}')
            if args.crop or args.zoom != 1:
                shutil.copy2(work/f'{i}.png', stage/f'frame-{i+1:03d}-tick-{row["tick"]:06d}.png')
        lines += [f"file '{len(rows)-1}.png'", 'option framerate 20']
        (work/'frames.txt').write_text('\n'.join(lines)+'\n', encoding='utf-8')
        subprocess.run([str(exe), '-hide_banner', '-loglevel', 'error', '-y', '-f', 'concat', '-safe', '0',
                        '-i', str(work/'frames.txt'), '-vf', 'pad=ceil(iw/2)*2:ceil(ih/2)*2',
                        '-r', '20', '-fps_mode', 'cfr', '-c:v', 'libx264', '-pix_fmt', 'yuv420p', '-movflags', '+faststart',
                        '-t', str(sum(durations)), str(stage/'capture.mp4')], check=True)
    sheet(samples, labels, stage/'contact.png', args.columns)
    write_json(stage/'frames.json', dict(source=str(directory), compare=str(other) if other else None,
               frames=[dict(tick=r['tick'], duration=d) for r, d in zip(rows, durations)]))
    body = f'<p>{len(rows)} 帧；tick {rows[0]["tick"]}–{rows[-1]["tick"]}；按 tick 间隔 / 20 秒播放，末帧 1 tick。</p>'
    body += media('capture.mp4', '左：当前输入；右：对比输入' if other else '1 倍速') + media('contact.png', '帧号与实际 tick')
    body += '<a href="frames.json">帧与时长数据</a>'
    if args.crop or args.zoom != 1:
        body += '<p>原尺寸裁切／缩放帧：</p>' + ' '.join(f'<a href="frame-{i:03d}-tick-{r["tick"]:06d}.png">{r["tick"]}</a>' for i, r in enumerate(rows, start=1))
    return body


def copy_media(source, stage, prefix):
    path = Path(source).resolve()
    if not path.is_file():
        raise ValueError(f'Missing media: {path}')
    name = prefix+path.suffix
    shutil.copy2(path, stage/name)
    return name


def motion(args, stage):
    existing = OUT/args.topic
    body = ''
    for key, title, pattern in [('storyboard', '分镜', 'storyboard*.png'), ('animatic', '动态分镜', 'animatic.gif'),
                                ('strip', '真实片段渲染条', 'strip.png'), ('capture', '游戏截图／录像', 'capture*.png')]:
        value = getattr(args, key)
        paths = [Path(v) for v in value] if value else sorted(existing.glob(pattern))
        body += f'<h2>{title}</h2>'
        body += ''.join(media(copy_media(p, stage, f'{key}-{i}'), p.stem) for i, p in enumerate(paths)) if paths else '<p>待提供</p>'
    review = Path(args.review) if args.review else existing/'review.json'
    body += '<h2>审图结果</h2>'
    if review.is_file():
        result = read(review)
        write_json(stage/'review.json', result)
        body += '<pre>'+E(json.dumps(result, ensure_ascii=False, indent=2))+'</pre>'
    else:
        body += '<p>待提供</p>'
    return body


def viewer_query(args):
    return urlencode(dict(model=args.model, clip=args.clip, expression=args.expression, autoplay='1'))


def viewer(args, stage):
    """Snapshot the game resources into the existing core-backed review viewer."""
    from pose_client import request
    from build_review_catalog import current_roles
    roles = {ident: (name, group) for ident, name, group in current_roles()}
    if args.model not in roles:
        raise ValueError(f'Unknown model: {args.model}')
    raw = (ASSETS/'yukkuri'/f'{args.model}.json').read_bytes()
    model = json.loads(raw)
    # Follow YukkuriModelLoader's fit/revision order through the shared splice core.
    fit = read(ASSETS/'yukkuri_presentation/placement_fit.json')['models'].get(args.model)
    hashes = {hashlib.sha256(value).hexdigest() for value in (raw, raw.replace(b'\r\n', b'\n'))}
    if fit and fit['base_sha256'] not in hashes:
        fit = None
    for file in ('motion_corrections', 'house_ecology', 'toilet_contact', 'face_library'):
        revision = read(ASSETS/'yukkuri_presentation'/f'{file}.json')['models'].get(args.model)
        # The loader skips a revision authored for another skeleton; so does this snapshot.
        if revision is not None and revision['bone_names'] != [bone['name'] for bone in model['bones']]:
            revision = None
        if revision is not None or fit is not None:
            result = request(dict(op='register', model=model, fit=fit, revision=revision))
            model = result['model']
            request(dict(op='release', id=result['id']))
        fit = None
    clips = {**model['clips'], **model.get('behavior_clips', {})}
    if args.clip not in clips:
        raise ValueError(f'Unknown clip {args.model}/{args.clip}')
    textures = {p.stem: 'data:image/png;base64,' + base64.b64encode(p.read_bytes()).decode('ascii')
                for p in sorted((ASSETS/'textures/entity'/args.model).glob('*.png'))}
    if args.expression != 'auto' and args.expression not in textures:
        raise ValueError(f'Unknown expression {args.model}/{args.expression}')
    write_json(stage/'model.json', model)
    # Already spliced above: the viewer must not apply these a second time.
    write_json(stage/'motions.json', dict(models={}))
    write_json(stage/'fit.json', dict(models={}))
    name, group = roles[args.model]
    entry = dict(id=args.model, name=name, group=group, url='model.json', textures=textures,
                 formScale=1.5 if args.model in ('reimu_deibu', 'reimu_wasa_deibu') else 1,
                 actions=list(clips), textureSize=model.get('texture_size', 128))
    catalog = dict(models=[entry], audio=[], motions='motions.json', fit='fit.json')
    (stage/'catalog.js').write_text('window.REVIEW_CATALOG='+json.dumps(catalog, ensure_ascii=False)+';\n', encoding='utf-8')
    for source, target in [('index.html', 'viewer.html'), ('style.css', 'style.css'),
                           ('app.js', 'app.js'), ('pose.js', 'pose.js'), ('route.js', 'route.js')]:
        shutil.copy2(ROOT/'preview/review'/source, stage/target)
    url = 'viewer.html?' + viewer_query(args)
    return f'<p><a href="{E(url, quote=True)}">打开动作查看器：{E(name)} · {E(args.clip)}</a></p><iframe title="动作查看器" src="{E(url, quote=True)}"></iframe>'


def collection(args, stage):
    """Page for an existing set of videos, e.g. the life-review batch."""
    source = Path(args.source).resolve()
    videos = sorted(source.glob('*.mp4'))
    if not videos:
        raise ValueError(f'No mp4 files: {source}')
    return ''.join(media(copy_media(p, stage, p.stem), p.stem) for p in videos)


def moment(args, stage):
    from export_moments import validate
    data = validate(read(ROOT/'standards/yukkuri-library/presentation/moments.json'))
    row = data['moments'][args.topic]
    model = read(ASSETS/'yukkuri'/(args.model+'.json'))
    clips = {**model['clips'], **model.get('behavior_clips', {}),
             **read(ASSETS/'yukkuri_presentation/motion_corrections.json')['models'][args.model]['clips']}
    durations = [clips[step['clip']]['duration']/step['tempo'] for step in row['steps']]
    total = sum(durations)
    opts = argparse.Namespace(model=args.model, prop=None, clip=row['steps'][0]['clip'], face=None,
            partner=None, before=None, size=[360,360], view=[26,10], node=args.node, moment=row)
    times = [i/20 for i in range(math.ceil(total*20))]
    frames = render_strip(opts, stage, times)
    atlas = Image.open(ASSETS/'textures/emote/atlas.png').convert('RGBA')
    metadata = read(ASSETS/'textures/emote/emotes.json')
    icons = {r['id']: r['cell'] for r in metadata['emotes']}
    rendered = []
    for elapsed, frame in zip(times, frames):
        time = elapsed
        for step, duration in zip(row['steps'], durations):
            if time < duration: break
            time -= duration
        # The right panel exercises the name-tag see-through pass against an opaque ceiling.
        panels = []
        for blocked in (False, True):
            panel = Image.new('RGBA', (360,420), '#ddd8cf')
            panel.alpha_composite(frame, (0,60))
            draw = ImageDraw.Draw(panel)
            draw.text((10,8), '头顶遮挡' if blocked else '无遮挡', font=font(18), fill='#30271d')
            if blocked: draw.rectangle((80,32,280,96), fill='#55514d')
            if step.get('emote') and time < 2:
                x,y = icons[step['emote']]
                cell = metadata['cell_size']
                icon = atlas.crop((x*cell,y*cell,(x+1)*cell,(y+1)*cell)).resize((48,48), Image.Resampling.NEAREST)
                if blocked: icon.putalpha(icon.getchannel('A').point(lambda a: a//4))
                panel.alpha_composite(icon, (156,40))
            panels.append(panel)
        joined = Image.new('RGB',(720,420))
        for i,panel in enumerate(panels): joined.paste(panel,(i*360,0))
        rendered.append(joined)
    with tempfile.TemporaryDirectory(dir=stage) as directory:
        work = Path(directory)
        for i,frame in enumerate(rendered): frame.save(work/f'{i:05d}.png')
        subprocess.run([str(ffmpeg_path()),'-hide_banner','-loglevel','error','-y','-framerate','20',
                        '-i',str(work/'%05d.png'),'-c:v','libx264','-pix_fmt','yuv420p','-movflags','+faststart',str(stage/'moment.mp4')],check=True)
    indices = sorted({round(i*(len(rendered)-1)/7) for i in range(8)})
    sheet([rendered[i] for i in indices],[f'{times[i]:.2f}s' for i in indices],stage/'strip.png',2,(720,420))
    write_json(stage/'moment.json',row)
    return '<p>真实运行模型与共享离线 pose 路径；右侧以不透明遮挡示意名牌的淡色透视通道。实际屋顶效果以游戏截图为准。</p>'+media('moment.mp4')+media('strip.png')


def kit(args, stage):
    library = ROOT/'standards/yukkuri-library'
    entries = read(library/'provenance/standard-expressions.json')['entries']
    selection = read(library/'expressions/runtime.json')
    java = '\n'.join(p.read_text(encoding='utf-8') for p in (ROOT/'src/main/java').rglob('*.java'))
    model = read(ASSETS/'yukkuri/reimu.json')
    clips = {**model['clips'], **read(ASSETS/'yukkuri_presentation/motion_corrections.json')['models']['reimu']['clips']}
    revisions = read(ASSETS/'yukkuri_presentation/motion_corrections.json')['models']
    spans = set()
    for species in selection['models']:
        runtime = read(ASSETS/'yukkuri'/(species+'.json'))
        selected = {**runtime['clips'], **runtime.get('behavior_clips',{}), **revisions.get(species,{}).get('clips',{})}
        spans.update((clip,a,b,face) for clip,row in selected.items() for a,b,face in row.get('expression_spans',[]))
    moments = read(ROOT/'standards/yukkuri-library/presentation/moments.json')['moments']
    usage = {}
    cards = []
    for row in entries:
        ident = row['id']
        count = len(re.findall(r'"'+re.escape(ident)+r'"',java))
        span_count = sum(s[3]==ident for s in spans)
        moment_count = sum(any(face==ident for step in row['steps'] for _,face in step['faces'])
                           or row.get('condition_face')==ident for row in moments.values())
        usage[ident] = dict(java=count,face_spans=span_count,moments=moment_count)
        shutil.copy2(library/'expressions/thumbnails'/(ident+'.png'),stage/(ident+'.png'))
        cards.append(f'<figure><img loading="lazy" src="{ident}.png"><figcaption>{E(row["label"])} · {ident}<br>Java {count} / 片段表情段 {span_count} / 演出行 {moment_count}'+(' · <strong>未使用</strong>' if count+span_count+moment_count==0 else '')+'</figcaption></figure>')
    write_json(stage/'usage.json',usage)
    body = '<p>认可表情；引用数为 Java 字符串出现次数和全部模型运行片段的表情段数（相同片段及表情段跨模型去重），不代表游玩频率。A/B 相位单列；客户端自动轮播基础 ID。</p><div style="display:flex;flex-wrap:wrap">'+''.join(cards)+'</div>'
    proof = [r['id'] for r in entries if sum(usage[r['id']].values())==0 and not r['id'].endswith('_b')][:6]
    body += '<h2>运行资源实绘：灵梦／魔理沙</h2>'
    for species in ('reimu','marisa'):
        images=[]
        for face in proof:
            opts=argparse.Namespace(model=species,prop=None,clip='idle',face=face,partner=None,before=None,
                                    size=[240,220],view=[0,5],node=args.node)
            images.append(render_strip(opts,stage,[0])[0])
        sheet(images,proof,stage/(species+'-faces.png'),6,(240,220))
        body += media(species+'-faces.png',species+' / 六种此前未使用表情（真实运行贴图与补丁）')
    body += '<h2>基本／通用动作</h2>'
    for ident in selection['kit_clips']:
        clip = clips[ident]
        opts=argparse.Namespace(model='reimu',prop=None,clip=ident,face=None,partner=None,before=None,
                                size=[160,150],view=[26,10],node=args.node)
        frames=render_strip(opts,stage,[clip['duration']*i/12 for i in range(12)])
        frames[0].save(stage/(ident+'.gif'),save_all=True,append_images=frames[1:],duration=max(20,round(clip['duration']*1000/12)),loop=0)
        count=len(re.findall(r'"'+re.escape(ident)+r'"|\b'+re.escape(ident.upper())+r'\b',java))
        body += f'<figure><img loading="lazy" src="{ident}.gif"><figcaption>{ident} · {clip["duration"]:g} 秒 · Java {count}'+(' · <strong>未使用</strong>' if count==0 else '')+'</figcaption></figure>'
    body += '<h2>演出数据行</h2><ul>'+''.join('<li>'+E(ident)+'：'+E(' → '.join(s['clip'] for s in row['steps']))+'</li>' for ident,row in moments.items())+'</ul>'
    emotes = read(ASSETS/'textures/emote/emotes.json')
    shutil.copy2(ASSETS/'textures/emote/atlas.png',stage/'emotes.png')
    body += '<h2>头顶符号</h2>'+media('emotes.png')+'<ul>'+''.join('<li>'+E(row['id']+'：'+row['meaning'])+'</li>' for row in emotes['emotes'])+'</ul>'
    return body


class InboxReader(HTMLParser):
    """Import existing human-written inbox links once, keeping their decision text."""
    def __init__(self):
        super().__init__()
        self.items, self.item, self.field = [], None, None

    def handle_starttag(self, tag, attrs):
        if tag == 'li':
            self.item = dict(title='', decision='')
        if self.item is not None:
            if tag == 'a':
                self.item['url'] = dict(attrs)['href']
                self.field = 'title'
            elif tag == 'small':
                self.field = 'decision'

    def handle_data(self, data):
        if self.item is not None and self.field:
            self.item[self.field] += data

    def handle_endtag(self, tag):
        if tag in ('a', 'small'):
            self.field = None
        if tag == 'li' and self.item is not None:
            if 'url' not in self.item:
                raise ValueError('Existing inbox item has no link; cannot import it')
            self.item['id'] = Path(self.item['url']).parent.name
            self.item['decision'] = self.item['decision'].strip().removeprefix('要你决定：')
            self.items.append(self.item)
            self.item = None


def inbox(args):
    target = OUT/'inbox'
    target.mkdir(parents=True, exist_ok=True)
    database = target/'items.json'
    items = read(database) if database.is_file() else []
    if not database.exists() and (target/'index.html').is_file():
        reader = InboxReader()
        reader.feed((target/'index.html').read_text(encoding='utf-8'))
        items = reader.items
    if args.action == 'add':
        items = [item for item in items if item['id'] != args.id]
        items.append(dict(id=args.id, title=args.title, url=args.url, decision=args.decision))
    else:
        items = [item for item in items if item['id'] != args.id]
    write_json(database, items)
    body = '<ul>'+''.join(f'<li><a href="{E(i["url"], quote=True)}">{E(i["title"])}</a><br><small>要你决定：{E(i["decision"])}</small></li>' for i in items)+'</ul>'
    (target/'index.html').write_text(page('待你查看', body), encoding='utf-8')


def parser():
    ap = argparse.ArgumentParser(description=__doc__)
    commands = ap.add_subparsers(dest='command', required=True)
    for name in ('strip', 'capture', 'motion', 'collection'):
        p = commands.add_parser(name)
        p.add_argument('topic', type=valid_topic)
        p.add_argument('--title')
        p.add_argument('--decision', help='also add to the review inbox with this decision')
        if name in ('strip', 'capture'):
            p.add_argument('--columns', type=int, default=4)
        if name == 'strip':
            group = p.add_mutually_exclusive_group(required=True)
            group.add_argument('--model'); group.add_argument('--prop')
            p.add_argument('--clip', default='idle')
            p.add_argument('--face', help='runtime expression id; with --before and --face-preset, the before-side face')
            p.add_argument('--face-preset', help='preset JSON composed as a draft with the runtime face library')
            time = p.add_mutually_exclusive_group()
            time.add_argument('--times', default='0s,0.25s,0.5s,0.75s')
            time.add_argument('--beats', help='one time (seconds or ticks), then optional label per line')
            p.add_argument('--partner'); p.add_argument('--partner-offset', type=float, nargs=3, default=[20, 0, 0])
            p.add_argument('--before', help='before checkout path or Git ref, using the same camera and times')
            p.add_argument('--size', type=int, nargs=2, default=[360, 300])
            p.add_argument('--view', type=float, nargs=2, action='append', metavar=('AZ', 'EL'),
                           help='camera azimuth/elevation; repeat for one row per view')
            p.add_argument('--node', default='node')
        elif name == 'capture':
            p.add_argument('--source', required=True, help='.local/captures/<run>/<view>')
            p.add_argument('--compare', help='second view directory, matched by actual tick')
            p.add_argument('--ticks', type=int, nargs=2)
            p.add_argument('--crop', type=int, nargs=4, metavar=('X', 'Y', 'W', 'H'))
            p.add_argument('--zoom', type=float, default=1)
            p.add_argument('--count', type=int, default=16, help='contact sheet samples; video retains all frames')
        elif name == 'motion':
            for key in ('storyboard', 'animatic', 'strip', 'capture'):
                p.add_argument('--'+key, nargs='+')
            p.add_argument('--review', help='capture-review JSON; displayed without interpreting verdicts')
        else:
            p.add_argument('--source', required=True)
    ib = commands.add_parser('inbox').add_subparsers(dest='action', required=True)
    for action in ('add', 'remove'):
        p = ib.add_parser(action)
        p.add_argument('id')
        if action == 'add':
            for key in ('title', 'url', 'decision'):
                p.add_argument('--'+key, required=True)
    commands.add_parser('hub', help='register existing topic pages/media without rerendering')
    mp = commands.add_parser('moment',help='render a presentation data row and its blocked-above emote pass')
    mp.add_argument('topic',type=valid_topic)
    mp.add_argument('--model',default='reimu')
    mp.add_argument('--node',default='node')
    mp.add_argument('--title')
    mp.add_argument('--decision')
    kp = commands.add_parser('kit',help='approved faces and base/generic runtime clips')
    kp.set_defaults(topic='kit',title='表情与基本动作目录',decision=None)
    kp.add_argument('--node',default='node')
    vp = commands.add_parser('viewer', help='one autoplaying link to an exported runtime clip')
    vp.add_argument('topic', type=valid_topic)
    vp.add_argument('--model', required=True)
    vp.add_argument('--clip', required=True)
    vp.add_argument('--expression', default='auto')
    vp.add_argument('--title')
    vp.add_argument('--decision')
    return ap


def main():
    args = parser().parse_args()
    try:
        if args.command not in ('inbox', 'hub'):
            if hasattr(args, 'columns') and args.columns < 1:
                raise ValueError('columns must be positive')
            if args.command == 'strip' and min(args.size) < 1:
                raise ValueError('size must be positive')
            if args.command == 'strip' and args.face_preset and not args.model:
                raise ValueError('--face-preset requires --model')
            if args.command == 'strip' and args.face and args.face_preset and not args.before:
                raise ValueError('--face only applies to the old side of --before when --face-preset is given')
            if args.command == 'capture' and (args.zoom <= 0 or args.count < 2):
                raise ValueError('zoom must be positive and count at least 2')
            with staging(args.topic) as stage:
                body = globals()[args.command](args, stage)
                title = args.title or args.topic
                (stage/'index.html').write_text(page(title, body), encoding='utf-8')
                write_json(stage/'topic.json', dict(title=title, command=args.command, inputs=vars(args)))
            if args.decision:
                inbox(argparse.Namespace(action='add', id=args.topic, title=title,
                       url=(f'../{args.topic}/viewer.html?'+viewer_query(args) if args.command == 'viewer'
                            else f'../{args.topic}/index.html'), decision=args.decision))
        elif args.command == 'inbox':
            inbox(args)
        from build_preview_hub import register_topics
        register_topics(OUT)
        relative = f'{args.topic}/index.html' if hasattr(args, 'topic') else ('inbox/index.html' if args.command == 'inbox' else 'index.html')
        if args.command == 'viewer':
            relative = f'{args.topic}/viewer.html?' + viewer_query(args)
        print('http://127.0.0.1:8766/.local/previews/'+relative)
    except (ValueError, OSError, RuntimeError, subprocess.CalledProcessError) as error:
        print(f'preview: {error}', file=sys.stderr)
        return 1
    return 0


if __name__ == '__main__':
    sys.exit(main())
