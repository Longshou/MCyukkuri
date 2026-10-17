"""Offline close-ups of the actual selected mamu and newborn triangle meshes.

Mirrors LiveBirthRenderer's authored-mamu sampling, section clipping and deformation.
Only the camera and stage samples are preview choices; this is not a game capture.
"""
from pathlib import Path
import argparse
import hashlib
import json
import math
import subprocess

import numpy as np
from PIL import Image, ImageDraw

import births as B
import preview as P

OUT = P.ROOT / '.local/previews/birth-catching'
ASSETS = P.ASSETS


def smooth(q):
    q = np.clip(q, 0, 1)
    return q*q*(3-2*q)


def deform(vertices, belly, contraction):
    result = vertices.copy()
    x, y, z = result[:, :3].T.copy()
    low = smooth(y/1.5)*(1-smooth((y-1.5)/4.5))
    front = np.where(z < 0, smooth(-z/4), 0)
    scale = 1+.26*belly
    result[:, 0] = x*scale*(1+.16*belly*low)*(1+.035*contraction)
    result[:, 1] = y*scale*(1-.04*contraction)
    result[:, 2] = z*scale*(1+(.06+.28*front)*belly*low)*(1+.035*contraction)
    return result


def selected(model, predicate):
    vertices = np.asarray(model['vertices'], float).reshape(-1, 8)
    ids = [i for p in model['parts'] if predicate(p) for i in range(p['start'], p['start']+p['count'])]
    return vertices[ids].copy(), ids


def mamu_pose(model):
    """YukkuriModel.deliveryMamuVertices: anatomy translation/scale only; a birth has no rigid hinge rotation."""
    clip = model['clips']['anatomy_mamu_extend']
    sampled = {}
    for track in clip['tracks']:
        if model['bones'][track['bone']]['name'].startswith('anatomy_') and track['channel'] != 'rotation':
            sampled.setdefault(track['bone'], {})[track['channel']] = P.sample(track['keys'], clip['duration'])
    matrices = []
    for i, bone in enumerate(model['bones']):
        values = sampled.get(i, {})
        matrix = np.eye(4)
        matrix[:3, :3] = P.euler(values.get('rotation', [0, 0, 0]))*values.get('scale', [1, 1, 1])
        pivot = np.asarray(bone['pivot'])
        matrix[:3, 3] = pivot+values.get('position', [0, 0, 0])-matrix[:3, :3]@pivot
        if bone['parent'] >= 0:
            matrix = matrices[bone['parent']]@matrix
        matrices.append(matrix)
    vertices, ids = selected(model, lambda p: p.get('anatomy') == 'mamu')
    weights = np.asarray(model['weights'])[ids]
    for i, (first, second, blend) in enumerate(weights):
        point = np.r_[vertices[i, :3], 1]
        vertices[i, :3] = ((matrices[int(first)]@point)*(1-blend)+(matrices[int(second)]@point)*blend)[:3]
    return vertices


def side(a, b, p):
    return (b[0]-a[0])*(p[1]-a[1])-(b[1]-a[1])*(p[0]-a[0])


def hull(points):
    if len(points) < 3:
        return []
    points = sorted({tuple(p) for p in points})
    lower, upper = [], []
    for seq, target in ((points, lower), (reversed(points), upper)):
        for p in seq:
            while len(target) >= 2 and side(target[-2], target[-1], p) <= 0:
                target.pop()
            target.append(p)
    return [np.asarray(p) for p in lower[:-1]+upper[:-1]]


def clip(vertices, distance):
    if not len(vertices):
        return []
    result = []
    previous = vertices[-1]
    pd = distance(previous)
    for current in vertices:
        cd = distance(current)
        if (pd >= 0) != (cd >= 0):
            result.append(previous+(current-previous)*(pd/(pd-cd)))
        if cd >= 0:
            result.append(current)
        previous, pd = current, cd
    return result


def triangles(polygon):
    return [np.asarray([polygon[0], polygon[i], polygon[i+1]]) for i in range(1, len(polygon)-1)]


def subtract(mesh, hole, plane, all_depth=False):
    if len(hole) < 3:
        return mesh.reshape(-1, 3, 8).copy()
    limits = np.asarray(hole)[:, :2]
    lo, hi = limits.min(axis=0), limits.max(axis=0)
    output = []
    for tri in mesh.reshape(-1, 3, 8):
        if (not all_depth and tri[:, 2].mean() > plane+1.6) or np.any(tri[:, :2].min(axis=0) > hi) or np.any(tri[:, :2].max(axis=0) < lo):
            output.append(tri)
            continue
        remaining = list(tri)
        for a, b in zip(hole, hole[1:]+hole[:1]):
            output.extend(triangles(clip(remaining, lambda p: -side(a, b, p))))
            remaining = clip(remaining, lambda p: side(a, b, p))
            if not remaining:
                break
    return np.asarray(output)


def surface(mesh, x, y, fallback, bias=0):
    tri = mesh.reshape(-1, 3, 8)
    a, b, c = tri[:, 0], tri[:, 1], tri[:, 2]
    det = (b[:, 0]-a[:, 0])*(c[:, 1]-a[:, 1])-(b[:, 1]-a[:, 1])*(c[:, 0]-a[:, 0])
    valid = np.abs(det) > 1e-8
    safe = np.where(valid, det, 1)
    u = ((x-a[:, 0])*(c[:, 1]-a[:, 1])-(y-a[:, 1])*(c[:, 0]-a[:, 0]))/safe
    v = ((b[:, 0]-a[:, 0])*(y-a[:, 1])-(b[:, 1]-a[:, 1])*(x-a[:, 0]))/safe
    valid &= (u >= -.001) & (v >= -.001) & (u+v <= 1.001)
    z = a[:, 2]+(b[:, 2]-a[:, 2])*u+(c[:, 2]-a[:, 2])*v
    return (z[valid].min() if valid.any() else fallback)+bias


def mesh_item(mesh, texture):
    mesh = np.asarray(mesh).reshape(-1, 3, 8).copy()
    normals = np.cross(mesh[:, 1, :3]-mesh[:, 0, :3], mesh[:, 2, :3]-mesh[:, 0, :3])
    lengths = np.linalg.norm(normals, axis=1)
    valid = lengths > 1e-10
    mesh = mesh[valid]
    mesh[:, :, 5:8] = (normals[valid]/lengths[valid, None])[:, None, :]
    # The game's entity material is no-cull; duplicate reverse winding for the preview rasterizer.
    reverse = mesh[:, ::-1].copy()
    reverse[:, :, 5:8] *= -1
    return dict(v=np.concatenate([mesh, reverse]).reshape(-1).tolist(), texture=texture)


def frame(model, belly, contraction, progress, extension=1, detached=False):
    """One rest-space Delivery frame, without the full-body root animation."""
    if detached:
        contraction = 0
        extension = float(smooth(belly/.22*160/13))
    body, _ = selected(model, lambda p: p['name'].startswith('soft_head_slice_') or p['name'] in (
        'house_body_surface', 'predator_body_surface', 'deficient_body_surface', 'rounded_body_surface'))
    assert len(body), 'The selected maternal surface must match YukkuriModel.bodySurface'
    body = deform(body, belly, contraction)
    mamu = deform(mamu_pose(model), belly, contraction)
    geometry = json.loads((ASSETS/'yukkuri_presentation/body_geometry.json').read_text(encoding='utf-8'))['variants']['reimu']
    outlet = deform(np.asarray([[*geometry['outlet'], 0, 0, 0, 0, 0]]), belly, contraction)[0, :3]
    plane = surface(mamu, outlet[0], outlet[1], outlet[2])-.015
    newborn, _ = selected(model, lambda p: not p.get('anatomy') and 'puff' not in p['name'])
    family = json.loads((ASSETS/'yukkuri_presentation/family.json').read_text(encoding='utf-8'))
    scale, height = family['growth']['birth']['live']['scale'], geometry['height']
    min_z = min(0, newborn[:, 2].min()*scale)
    max_z = max(0, newborn[:, 2].max()*scale)
    start_z, clear_z = plane-min_z, plane-max_z-.03
    width = geometry['bounds'][0][1]-geometry['bounds'][0][0]
    end_z = -max(-outlet[2]+2.4, width*(1+.26*belly)*(1+.16*belly)*.5+2.4)
    z = start_z+(clear_z-start_z)*progress/.8 if progress <= .8 else clear_z+(end_z-clear_z)*smooth((progress-.8)/.2)
    px, py = newborn[:, 0].copy(), newborn[:, 1].copy()
    pz = newborn[:, 2]*scale+z
    pressure = smooth(progress/.12)*(1-smooth((progress-.78)/.22))*np.exp(-((pz-plane)/.60)**2)
    nx, ny = np.minimum(1, np.abs(px/6)), np.minimum(1, np.abs((py-height*.5)/(height*.5)))
    sx = (1-.24*pressure)*np.sqrt(1-.32*ny*ny*pressure)
    sy = (1-.18*pressure)*np.sqrt(1-.32*nx*nx*pressure)
    newborn[:, 0] = px*scale*sx
    newborn[:, 1] = ((py-height*.5)*sy+height*.5)*scale+max(.24, outlet[1]-height*scale*.5)
    newborn[:, 2] = pz
    section = []
    if 0 < progress < 1 and not detached:
        for tri in newborn.reshape(-1, 3, 8):
            for a, b in zip(tri, np.roll(tri, -1, axis=0)):
                if (a[2] <= plane < b[2]) or (b[2] <= plane < a[2]):
                    section.append((a+(b-a)*(plane-a[2])/(b[2]-a[2]))[:3])
    hole = hull(section)
    center = np.asarray(hole).mean(axis=0) if len(hole) else np.array([*outlet[:2], plane])
    for p in hole:
        radial = p-center
        radial[2] = 0
        p[:2] += .018*radial[:2]/max(1e-10, np.linalg.norm(radial))
        p[2] = plane
    half = np.abs(mamu[:, :2]-outlet[:2]).max(axis=0)
    hole_half = np.abs(np.asarray(hole)[:, :2]-outlet[:2]).max(axis=0) if len(hole) else np.zeros(2)
    swell = smooth(progress/.2)*(1-smooth((progress-.8)/.2)) if not detached else 0
    scales = np.maximum([1+.35*swell, 1+.65*swell], (hole_half+.10)/np.maximum(.01, half))
    for p in mamu:
        if np.any(np.abs(scales-1) > 1e-4):
            depth = p[2]-surface(body, p[0], p[1], plane+.025, -.005)
            delta = p[:2]-outlet[:2]
            radial = np.linalg.norm(delta/np.maximum(.01,half))
            flex = 1-smooth((radial-.35)/.65)
            p[:2] = outlet[:2]+delta*(1+(scales-1)*flex)
            p[2] = surface(body, p[0], p[1], plane+.025, -.005)+depth
        p[2] += .6*(1-extension)
    items = [mesh_item(subtract(body, hole, plane), 'neutral'), mesh_item(subtract(mamu, hole, plane, True), 'neutral')]
    mark, _ = selected(model, lambda p: 'mamu_mark' in p['name'])
    mark_uv = mark[:, 3:5].mean(axis=0)
    walls = []
    def ink(point):
        return np.r_[point, mark_uv, 0, 0, -1]
    for a, b in zip(hole, hole[1:]+hole[:1]):
        lip_a, lip_b = a.copy(), b.copy()
        lip_a[2] = surface(mamu, a[0], a[1], plane)
        lip_b[2] = surface(mamu, b[0], b[1], plane)
        back_a, back_b = a+[0, 0, .16], b+[0, 0, .16]
        walls.extend(triangles([ink(lip_a), ink(lip_b), ink(back_b), ink(back_a)]))
        walls.extend(triangles([ink(center+[0, 0, .16]), ink(back_b), ink(back_a)]))
    if walls:
        items.append(mesh_item(walls, 'neutral'))
    if progress > 0:
        visible = []
        for tri in newborn.reshape(-1, 3, 8):
            visible.extend(triangles(clip(list(tri), lambda p: plane-p[2])))
        items.append(mesh_item(visible, 'sleep'))
    return items, outlet, dict(hole_vertices=len(hole), mamu_vertices=len(mamu), progress=progress)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--node', default='node')
    args = parser.parse_args()
    OUT.mkdir(parents=True, exist_ok=True)
    work = OUT/'_mamu_render'
    work.mkdir(exist_ok=True)
    model_path = ASSETS/'yukkuri/reimu.json'
    model = json.loads(model_path.read_text(encoding='utf-8'))
    stages = [('临产闭合', 1, .3, 0, 1, False), ('第一段用力 · 局部撑开', 1, .9, .22, 1, False),
              ('半显露 · 喘息停留', 1, .2, .42, 1, False), ('第二段用力 · 穿过出口', 1, .9, .65, 1, False),
              ('脱离 · 原部件闭合', .22, 0, 1, 1, True), ('产后回缩 · 收回体表', .008, 0, 0, .08, True)]
    frames, records = [], []
    for title, belly, contraction, progress, extension, detached in stages:
        items, outlet, stats = frame(model, belly, contraction, progress, extension, detached)
        frames.append(dict(items=items, center=float(outlet[1]+.25), focus=[0, float(outlet[2])]))
        records.append(dict(title=title, **stats))
    (work/'frames.json').write_text(json.dumps(frames), encoding='utf-8')
    for face in ('neutral', 'sleep'):
        Image.open(ASSETS/f'textures/entity/reimu/{face}.png').convert('RGBA').save(work/f'{face}.png')
        with Image.open(work/f'{face}.png') as image:
            (work/f'{face}.rgba').write_bytes(image.tobytes())
    width, height = 600, 440
    renderer = 'function renderScene'+B.PAGE.split('function renderScene', 1)[1].split('// ---- end engine ----')[0]
    script = "const fs=require('fs'); const dir="+json.dumps(str(work))+";\n"+renderer
    script += "\nconst frames=JSON.parse(fs.readFileSync(dir+'/frames.json')); const textures={}; for(const face of ['neutral','sleep'])textures[face]=fs.readFileSync(dir+'/'+face+'.rgba');\n"
    script += f"frames.forEach((f,i)=>{{for(const it of f.items){{it.tex=textures[it.texture];it.size={model['texture_size']};}}fs.writeFileSync(dir+'/'+i+'.rgba',renderScene(f.items,{width},{height},.42,-.16,2.2,f.center,0,f.focus));}});"
    (work/'render.cjs').write_text(script, encoding='utf-8')
    subprocess.run([args.node, str(work/'render.cjs')], check=True)
    sheet = Image.new('RGB', (width*3, (height+58)*2+120), '#f4f1e9')
    draw = ImageDraw.Draw(sheet)
    draw.text((24, 14), '麻姆麻姆生产特写 · 同一部件闭合、撑开、回缩', font=P.preview_font(30), fill='#3d342c')
    draw.text((24, 59), '真实运行网格 mamu_base / mamu_mark 与幼体；离线几何示意，游戏显示与碰撞仍待实测。', font=P.preview_font(21), fill='#6e6256')
    for i, stage in enumerate(stages):
        x, y = i%3*width, 110+i//3*(height+58)
        draw.text((x+16, y+8), stage[0], font=P.preview_font(24), fill='#3d342c')
        image = Image.frombytes('RGBA', (width, height), (work/f'{i}.rgba').read_bytes())
        sheet.paste(image, (x, y+50), image)
    sheet.save(OUT/'current.png')
    (OUT/'index.html').write_text('<!doctype html><meta charset="utf-8"><title>麻姆麻姆生产特写</title><style>body{background:#f4f1e9;font:18px system-ui;margin:30px;color:#3d342c}img{max-width:100%}</style><h1>麻姆麻姆生产特写</h1><p>读取当前运行网格中的 mamu_base、mamu_mark 与幼体，复现客户端的部件拉伸和截面裁剪。图中为离线局部几何示意，不是游戏截图；省略母体全身动作，游戏中的显示、碰撞与多人同步仍待实测。</p><img src="current.png"><p><a href="../index.html">返回预览总入口</a></p>', encoding='utf-8')
    reports = P.ROOT/'.local/reports'
    reports.mkdir(parents=True, exist_ok=True)
    (reports/'mamu-birth-geometry.json').write_text(json.dumps(dict(model_sha256=hashlib.sha256(model_path.read_bytes()).hexdigest(), stages=records), ensure_ascii=False, indent=2), encoding='utf-8')
    obsolete = OUT/'geometry-check.json'
    if obsolete.is_file():
        obsolete.unlink()
    assert work.resolve().is_relative_to(OUT.resolve())
    for path in work.iterdir():
        path.unlink()
    work.rmdir()
    print(OUT/'current.png')


if __name__ == '__main__':
    main()
