"""Offline preview of the stem growing from root to tip before its fruit set, and of the withered stem kept on starved remains.

Poses come from stem_growth.py (the data contract), bodies from the runtime meshes through the shared pose core,
drawn by render_growth.cjs. Writes .local/previews/stem-growth/ in the main checkout: a sample sheet (sprout, stem grown,
small fruit, half ripe, full ripe), a remains sheet (the living pose beside its withered remains at the same progress)
and one looping growth animation per sample case. Never starts a browser or game; sway, birth and the in-world look
still need the museum playthrough.
"""
from pathlib import Path
import argparse
import html
import shutil
import json
import subprocess
import sys

import numpy as np
from PIL import Image, ImageDraw

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[2]
sys.path.insert(0, str(HERE))
import stem_growth as S  # noqa: E402
font = S.P.preview_font

ASSETS = ROOT/'src/main/resources/assets/mcyukkuri'
REMAINS_ART = ROOT/'standards/yukkuri-library/drafts/remains/assembled'
MAIN = ROOT.parent.parent if ROOT.parent.name == '.worktrees' else ROOT
GREEN = [66, 186, 57]
# Sample litters: mixed kinds as a pair's litter can be; the crowded end carries the largest births.
CASES = [
    ('reimu3', 'reimu', ['reimu', 'marisa', 'reimu'], [0.17, 0.12, 0.15], '灵梦 · 3 果'),
    ('marisa3', 'marisa', ['marisa', 'reimu', 'marisa'], [0.15, 0.18, 0.11], '魔理沙 · 3 果'),
    ('reimu5', 'reimu', ['marisa', 'reimu', 'marisa', 'reimu', 'marisa'], [0.19, 0.18, 0.19, 0.17, 0.19], '灵梦 · 5 果拥挤端'),
]
VIEWS = [('side', 1.3, 0.12), ('three', 0.62, 0.3)]


def tube(points, radii, sides=8):
    """FamilyRenderer.sweep: rings by parallel transport, outward winding, smooth radial normals, closed ends."""
    points = np.asarray(points, float)
    n = len(points)
    tangent = np.asarray([points[min(n-1, i+1)]-points[max(0, i-1)] for i in range(n)])
    tangent /= np.maximum(1e-9, np.linalg.norm(tangent, axis=1))[:, None]
    u = np.cross(tangent[0], [0, 0, 1] if abs(tangent[0, 2]) < .9 else [1, 0, 0])
    u /= np.linalg.norm(u)
    rings, normals = [], []
    for i in range(n):
        if i:
            u = u-tangent[i]*np.dot(u, tangent[i])
            u /= np.linalg.norm(u)
        v = np.cross(tangent[i], u)
        dirs = [u*np.cos(a)+v*np.sin(a) for a in np.arange(sides)*2*np.pi/sides]
        rings.append([points[i]+radii[i]*d for d in dirs])
        normals.append(dirs)
    out = []

    def tri(a, b, c, na, nb, nc, outward):
        if np.dot(np.cross(b-a, c-a), outward) < 0:
            b, c, nb, nc = c, b, nc, nb
        for p, q in ((a, na), (b, nb), (c, nc)):
            out.extend([*p, .5, .5, *q])
    for i in range(1, n):
        for j in range(sides):
            k = (j+1) % sides
            a, b, c, d = rings[i-1][j], rings[i][j], rings[i][k], rings[i-1][k]
            na, nb, nc, nd = normals[i-1][j], normals[i][j], normals[i][k], normals[i-1][k]
            tri(a, b, c, na, nb, nc, na+nc)
            tri(a, c, d, na, nc, nd, na+nc)
    for end, ring, direction in ((points[0], rings[0], -tangent[0]), (points[-1], rings[-1], tangent[-1])):
        for j in range(sides):
            tri(end, ring[j], ring[(j+1) % sides], direction, direction, direction, direction)
    return out


def pedicel_radii(G, count, variance, mother, socket, index, q):
    """FamilyRenderer.pedicelRadii: at most 60% of the local stalk, never thinner than a third of it; a growing pedicel
    narrows toward its end."""
    arc = G.arc(count, mother, variance)
    nearest = int(np.argmin(np.sum((arc-socket)**2, axis=1)))
    stalk = G.radius(count, nearest/(len(arc)-1), variance)
    twig = min(.0075*16, G.radius(count, (index+.5)/count, variance)*.6)
    return max(twig*1.15, stalk*.55), max(twig*.72, stalk/3)*(.5+.5*q)


def stem_meshes(G, pose, mother, colour_tile, thickness=1.0):
    if len(pose['arc']) < 2:
        return []
    count, var = pose['count'], pose['variance']
    meshes = [dict(vertices=tube(pose['arc'], pose['radii']), texture=colour_tile)]
    living = G.sockets(count, mother, var)
    for f in pose['fruit']:
        if f['q'] <= 0:
            continue
        index = f['index']
        r0, r1 = pedicel_radii(G, count, var, mother, living[index], index, f['q'])
        path = f['path'] if len(f['path']) > 2 else np.linspace(f['path'][0], f['path'][-1], 8)
        meshes.append(dict(vertices=tube(path, np.linspace(r0, r1, len(path))*thickness), texture=colour_tile))
    return meshes


class Textures:
    def __init__(self, work):
        self.work, self.files, self.index = work, [], {}

    def add(self, key, image):
        if key not in self.index:
            image = image.convert('RGBA')
            path = self.work/(key.replace('/', '_')+'.rgba')
            path.write_bytes(image.tobytes())
            self.index[key] = len(self.files)
            self.files.append(dict(file=str(path), width=image.size[0], height=image.size[1]))
        return self.index[key]

    def png(self, key, path):
        return self.add(key, Image.open(path))

    def colour(self, rgb):
        return self.add('colour-'+'-'.join(map(str, rgb)), Image.new('RGBA', (4, 4), (*rgb, 255)))


def scene(G, T, case, p, remains_form=None):
    _, mother, kinds, births, _ = case
    count = len(kinds)
    living = G.living(count, p, mother, kinds, births)
    actors = []
    if remains_form is None:
        pose = living
        actors.append(dict(model=mother, expression='neutral', texture=T.png(mother+'/neutral', ASSETS/f'textures/entity/{mother}/neutral.png'),
                           transform=dict(sx=1, sy=1, sz=1, roll=0)))
        meshes = stem_meshes(G, pose, mother, T.colour(GREEN))
    else:
        pose = G.remains_pose(living, mother, remains_form)
        sx, sy, sz = remains_form['scale']
        actors.append(dict(model=mother, expression=remains_form['face'], texture=T.png(mother+'/remains_starved', REMAINS_ART/mother/'remains_starved.png'),
                           transform=dict(sx=sx, sy=sy, sz=sz, roll=remains_form.get('roll', 0))))
        meshes = stem_meshes(G, pose, mother, T.colour(G.remains['colour']), G.remains['thickness'])
    for f in pose['fruit']:
        if f['size'] <= 0:
            continue
        if remains_form is None:
            texture = T.png(f['kind']+'/sleep', ASSETS/f'textures/entity/{f["kind"]}/sleep.png')
        else:
            texture = T.png(f['kind']+'/remains_stem_fruit', REMAINS_ART/f['kind']/'remains_stem_fruit.png')
        sx, sy, sz = (f['size']*f['shrivel']).tolist()
        actors.append(dict(model=f['kind'], expression='sleep', texture=texture, transform=dict(sx=sx, sy=sy, sz=sz, roll=0),
                           offset=[float(v) for v in f['base']]))
    return actors, meshes, pose


def render(jobs, T, work, node):
    tasks = work/'tasks.json'
    tasks.write_text(json.dumps(dict(tile=256, jobs=jobs)), encoding='utf-8')
    subprocess.run([node, str(HERE/'render_growth.cjs'), str(tasks)], check=True)


# Framing: the scene moves back so the face and the stem's far end share the frame (model units).
LIVING_FRAME = dict(span=15.5, centerY=13, shift=[0, 0, 5])
REMAINS_FRAME = dict(span=15.5, centerY=7, shift=[0, 0, 7])
CLOSE = ('close', 0.95, 0.25)


def close_frame(pose, span):
    """A close view centred on the stalk and its fruit."""
    points = np.concatenate([pose['arc'], [f['base'] for f in pose['fruit'] if f['size'] > 0] or pose['arc'][-1:]])
    c = (points.min(0)+points.max(0))/2
    return dict(span=span, centerY=float(c[1]), shift=[float(-c[0]), 0, float(-c[2])])


def job(textures, actors, meshes, out, view, size=300, span=12.5, centerY=11.5, shift=(0, 0, 2.5)):
    _, az, el = view
    return dict(textures=textures, actors=actors, meshes=meshes, width=size, height=size, az=az, el=el, span=span, centerY=centerY,
                shift=list(shift), out=str(out))


def timeline(frames):
    """Animation samples: real time (0.1 s apart) through the stalk, pedicels and fruit set, then 1 s apart to ripe."""
    early = [round(t, 2) for t in np.arange(0, 4.01, 0.1)]
    late = [float(t) for t in range(5, 21)]
    return [(t/20, 100) for t in early]+[(t/20, 250) for t in late][:max(0, frames)]


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--node', default='node')
    ap.add_argument('--out', type=Path, default=MAIN/'.local/previews/stem-growth')
    ap.add_argument('--frames', type=int, default=16, help='one-second frames after the first four seconds')
    args = ap.parse_args()
    out = args.out
    work = out.parent/'_work'/'stem-growth'
    work.mkdir(parents=True, exist_ok=True)
    out.mkdir(parents=True, exist_ok=True)
    G = S.Growth()
    T = Textures(work)
    form = S.read(S.REMAINS)['forms']['starved']
    samples = G.g['samples']
    columns = [(k, samples[k]) for k in ('sprout', 'stem_grown', 'small_fruit', 'half_ripe', 'full_ripe')]
    labels = {'sprout': '茎往外伸', 'stem_grown': '茎长成、短梗冒出', 'small_fruit': '结出小果', 'half_ripe': '半熟', 'full_ripe': '足熟'}
    seconds = lambda p: f'{p*20:.1f} 秒'
    jobs, cells = [], {}
    for case in CASES:
        for key, p in columns:
            actors, meshes, pose = scene(G, T, case, p)
            for view in VIEWS+[CLOSE]:
                path = work/f'{case[0]}-{key}-{view[0]}.rgba'
                jobs.append((actors, meshes, path, view, close_frame(pose, 8) if view is CLOSE else LIVING_FRAME))
                cells[(case[0], key, view[0])] = path
    remains_cases = [(CASES[0], 0.06), (CASES[0], 0.25), (CASES[1], 0.25), (CASES[2], 0.25), (CASES[0], 0.5)]
    remains_rows = [('living', VIEWS[1]), ('remains', VIEWS[0]), ('remains', VIEWS[1]), ('remains', CLOSE)]
    for case, p in remains_cases:
        for phase, view in remains_rows:
            actors, meshes, pose = scene(G, T, case, p, form if phase == 'remains' else None)
            path = work/f'{phase}-{case[0]}-{p}-{view[0]}.rgba'
            frame = close_frame(pose, 6) if view is CLOSE else REMAINS_FRAME if phase == 'remains' else LIVING_FRAME
            jobs.append((actors, meshes, path, view, frame))
            cells[(phase, case[0], p, view[0])] = path
    anim = {}
    for case in CASES:
        anim[case[0]] = []
        for k, (p, duration) in enumerate(timeline(args.frames)):
            actors, meshes, _ = scene(G, T, case, p)
            path = work/f'anim-{case[0]}-{k:03d}.rgba'
            jobs.append((actors, meshes, path, VIEWS[1], LIVING_FRAME))
            anim[case[0]].append((path, p, duration))
    render([job(T.files, *j[:4], **j[4]) for j in jobs], T, work, args.node)

    tile = 230
    title, small, tiny = font(30), font(18), font(15)

    def cell(path, bg, size=tile):
        image = Image.frombytes('RGBA', (300, 300), path.read_bytes())
        c = Image.new('RGB', (300, 300), bg)
        c.paste(image, mask=image.getchannel('A'))
        return c.resize((size-4, size-4))
    rows = [(c, v) for c in CASES for v in VIEWS+[CLOSE]]
    names = {'side': '侧面', 'three': '斜前方', 'close': '近看'}
    width = 170+tile*len(columns)
    height = 110+len(rows)*tile+20
    sheet = Image.new('RGB', (width, height), (241, 244, 235))
    d = ImageDraw.Draw(sheet)
    d.text((24, 14), '茎生 · 先从根到梢长出茎，再结出实子（约 20 秒）', font=title, fill='#293e2c')
    d.text((24, 56), '茎是成熟时的同一根，从额头根部向梢头伸出，已长出的部分保持最终粗细、只有梢头渐尖；短梗按从根到梢依次冒出；茎和短梗长成后实子才鼓出并长大。', font=tiny, fill='#586c5b')
    for c, (key, p) in enumerate(columns):
        d.text((170+c*tile+8, 80), labels[key]+'  '+seconds(p), font=small, fill='#293e2c')
    for r, (case, view) in enumerate(rows):
        y = 110+r*tile
        d.text((14, y+tile//2-20), case[4]+'\n'+names[view[0]], font=small, fill='#293e2c')
        for c, (key, _) in enumerate(columns):
            sheet.paste(cell(cells[(case[0], key, view[0])], (232, 237, 225) if c % 2 else (236, 240, 230)), (170+c*tile, y))
    sheet.save(out/'growth.png')

    rtile = 250
    width = 170+rtile*len(remains_cases)
    height = 110+len(remains_rows)*rtile+50
    sheet = Image.new('RGB', (width, height), (238, 234, 224))
    d = ImageDraw.Draw(sheet)
    d.text((24, 14), '耗尽营养饿死 · 遗体上的茎枯萎', font=title, fill='#2b2b26')
    d.text((24, 56), '第一行是同一进度的活茎；下面是遗体：茎变褐、缩细，沿全长垂软倒伏，碰地就贴地；短梗松垂；未熟实子停在死前大小、干瘪发暗。', font=tiny, fill='#55554c')
    for c, (case, p) in enumerate(remains_cases):
        stage = '茎未长成' if p < G.reached(1.0) else '小果' if p < .3 else '半熟'
        d.text((170+c*rtile+8, 80), f'{case[4].split(" · ")[0]} {len(case[2])} 果 · {stage} {seconds(p)}', font=tiny, fill='#34352e')
    row_names = {('living', 'three'): '生前 斜前方', ('remains', 'side'): '遗体 侧面', ('remains', 'three'): '遗体 斜前方', ('remains', 'close'): '遗体 近看'}
    for r, (phase, view) in enumerate(remains_rows):
        y = 110+r*rtile
        d.text((14, y+rtile//2-10), row_names[(phase, view[0])], font=small, fill='#2b2b26')
        for c, (case, p) in enumerate(remains_cases):
            bg = (232, 237, 225) if phase == 'living' else (226, 220, 206) if c % 2 else (232, 227, 214)
            sheet.paste(cell(cells[(phase, case[0], p, view[0])], bg, rtile), (170+c*rtile, y))
    d.text((24, height-36), '遗体按现有饿死形态（缩放 0.86/0.6/0.86、N044 干枯脸）；腐败与饱腹塌陷在游戏内另乘，根点跟随。', font=tiny, fill='#55554c')
    sheet.save(out/'remains.png')

    for name, frames in anim.items():
        images, durations = [], []
        for path, p, duration in frames:
            image = Image.frombytes('RGBA', (300, 300), path.read_bytes())
            c = Image.new('RGB', (300, 330), (236, 240, 230))
            c.paste(image, (0, 30), image.getchannel('A'))
            ImageDraw.Draw(c).text((10, 6), f'{p*20:4.1f} 秒  {"1×" if duration == 100 else "4×"}', font=small, fill='#293e2c')
            images.append(c)
            durations.append(duration)
        durations[-1] = 1500
        images[0].save(out/f'{name}.gif', save_all=True, append_images=images[1:], duration=durations, loop=0)
    case_titles = {c[0]: c[4] for c in CASES}
    page = ['<!doctype html><html lang="zh-CN"><meta charset="utf-8"><title>茎生：先长茎再结果</title>',
            '<style>body{font-family:sans-serif;background:#f1f4eb;color:#293e2c;margin:24px}img{max-width:100%}figure{display:inline-block;margin:8px}</style>',
            '<h1>茎生：从根到梢长出茎，再结出实子；饿死后茎枯萎</h1>',
            '<p>动画前 4 秒按游戏内实际速度播放（茎伸出、短梗冒出、实子鼓出），之后每秒一帧、约 4 倍速到足熟；左上角是游戏内秒数。</p>']
    for name in anim:
        page.append(f'<figure><img src="{name}.gif" width="300"><figcaption>{html.escape(case_titles[name])}</figcaption></figure>')
    page += ['<h2>采样姿态</h2><img src="growth.png">', '<h2>饿死遗体：茎枯萎（第一行为生前对照）</h2><img src="remains.png">',
             '<p>离线渲染，仅示意几何；游戏内行走摆动、出生与遗体拾放需试玩确认。</p></html>']
    (out/'index.html').write_text('\n'.join(page)+'\n', encoding='utf-8')
    shutil.rmtree(work)  # raw frames and textures are only render inputs
    print(out/'index.html')


if __name__ == '__main__':
    main()
