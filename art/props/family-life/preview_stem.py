"""Offline rest-pose preview of the selected stem recipe and runtime character meshes.

Uses the shared software rasterizer; never starts a browser or game. Movement and
birth behavior still need the user's museum playthrough.
"""
from pathlib import Path
import argparse
import json
import subprocess
import sys
import tempfile

import numpy as np
from PIL import Image, ImageDraw

from fit_stem import Stem

ROOT = Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT/'standards/yukkuri-library/motions/variants/lore'))
import births as B
import preview as P


def mesh(pos, uv, normals):
    return np.concatenate([pos, uv, np.broadcast_to(normals[:, None, :], pos.shape)], axis=2).reshape(-1).tolist()


def smooth(points):
    result = []
    for i in range(len(points)-1):
        a, b, c, d = [points[max(0, min(len(points)-1, j))] for j in (i-1, i, i+1, i+2)]
        for t in np.arange(4)/4:
            result.append((2*b+(c-a)*t+(2*a-5*b+4*c-d)*t*t+(3*b-a-3*c+d)*t*t*t)/2)
    return np.asarray([*result, points[-1]])


def tube(points, r0, r1):
    """Continuous tapered rings, with the same transported frame as FamilyRenderer.sweep."""
    tangent = np.asarray([points[min(len(points)-1, i+1)]-points[max(0, i-1)] for i in range(len(points))])
    tangent /= np.linalg.norm(tangent, axis=1)[:, None]
    u = np.cross(tangent[0], [0, 0, 1] if abs(tangent[0, 2]) < .9 else [1, 0, 0])
    u /= np.linalg.norm(u)
    rings, triangles = [], []
    for i, (point, direction) in enumerate(zip(points, tangent)):
        if i:
            u -= direction*np.dot(u, direction)
            u /= np.linalg.norm(u)
        v = np.cross(direction, u)
        radius = r0+(r1-r0)*i/(len(points)-1)
        rings.append([point+radius*(u*np.cos(a)+v*np.sin(a)) for a in np.arange(8)*np.pi/4])
    for i in range(1, len(rings)):
        for j in range(8):
            a, b, c, d = rings[i-1][j], rings[i][j], rings[i][(j+1)%8], rings[i-1][(j+1)%8]
            triangles.extend([[a, b, c], [a, c, d]])
    pos = np.asarray(triangles)
    normals = np.cross(pos[:, 1]-pos[:, 0], pos[:, 2]-pos[:, 0])
    normals /= np.linalg.norm(normals, axis=1)[:, None]
    return mesh(pos, np.zeros((*pos.shape[:2], 2)), normals)


def main():
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--node', default='node')
    ap.add_argument('--output', type=Path, default=ROOT/'.local/previews/stem-hanging')
    args = ap.parse_args()
    args.output.mkdir(parents=True, exist_ok=True)
    family = json.loads((P.ASSETS/'yukkuri_presentation/family.json').read_text(encoding='utf-8'))
    recipe = json.loads((Path(__file__).parent/'recipe.json').read_text(encoding='utf-8'))
    if family['pedicelReach'] != 0:
        raise ValueError('This preview expects the vertical hanging recipe.')
    stem, count, mother = Stem(recipe, family['stem']), 3, 'reimu'
    length = family['lengthScale'][count-1]
    arc = smooth(np.asarray([stem.point(p, length, mother) for p in family['stem']['centerline']]))
    radius = np.asarray(family['stem']['radius'])*family['radiusScale'][count-1]
    items = [dict(v=tube(arc, *radius), color=[66, 186, 57], size=1)]
    corrections = json.loads((P.ASSETS/'yukkuri_presentation/motion_corrections.json').read_text(encoding='utf-8'))
    poses = {kind: P.Posed(kind, corrections) for kind in ('reimu', 'marisa')}
    items.append(dict(v=mesh(*poses[mother].frame('idle', 0)), texture='reimu-neutral'))
    size = family['growth']['fruit']['to']
    for i, ref in enumerate(family['stem']['socket_layouts'][str(count)]):
        kind = 'marisa' if i%2 == 0 else 'reimu'
        shape = family['variants'][kind]
        socket = stem.point(ref, length, mother)
        anchor = np.asarray(shape.get('hanging_anchor', [0, shape['crown_height'], 0]))*size
        drop = family['stem']['pendant_length_reference']*family['stem']['reference_units_to_model']
        clearance = shape.get('hang_clearance_height', shape['crown_height'])*size
        base = socket+[-anchor[0], -drop-clearance, -anchor[2]]
        head = base+anchor
        nearest = np.argmin(np.sum((arc-socket)**2, axis=1))
        stalk = radius[0]+(radius[1]-radius[0])*nearest/(len(arc)-1)
        twig = min(.12, (radius[0]+(radius[1]-radius[0])*(i+.5)/count)*.6)
        items.append(dict(v=tube(np.linspace(socket, head, 11), max(twig*1.15, stalk*.55), max(twig*.72, stalk/3)), color=[66, 186, 57], size=1))
        pos, uv, normals = poses[kind].frame('idle', 0)
        items.append(dict(v=mesh(pos*size+base, uv, normals), texture=kind+'-sleep'))
    width, height = 600, 560
    with tempfile.TemporaryDirectory(prefix='_render-', dir=args.output) as directory:
        work = Path(directory)
        (work/'scene.json').write_text(json.dumps(items), encoding='utf-8')
        sizes = {}
        for key in ('reimu-neutral', 'reimu-sleep', 'marisa-sleep'):
            kind, face = key.split('-')
            texture = poses[kind].texture(face)
            sizes[key] = texture.shape[0]
            (work/(key+'.rgba')).write_bytes(texture.tobytes())
        engine = 'function renderScene'+B.PAGE.split('function renderScene', 1)[1].split('// ---- end engine ----')[0]
        script = "const fs=require('fs');const dir="+json.dumps(str(work))+";\n"+engine
        script += "\nconst items=JSON.parse(fs.readFileSync(dir+'/scene.json'));const sizes="+json.dumps(sizes)+";"
        script += "for(const it of items)if(it.texture){it.tex=fs.readFileSync(dir+'/'+it.texture+'.rgba');it.size=sizes[it.texture];}"
        script += f"[1.25,-.85].forEach((az,i)=>fs.writeFileSync(dir+'/'+i+'.rgba',renderScene(items,{width},{height},az,.12,18,13,0,[-1.53,-8])));"
        (work/'render.cjs').write_text(script, encoding='utf-8')
        subprocess.run([args.node, str(work/'render.cjs')], check=True)
        sheet = Image.new('RGB', (width*2, height+145), '#f1f4eb')
        draw = ImageDraw.Draw(sheet)
        draw.text((24, 15), '茎生 · 静止时从挂点竖直悬下', font=P.preview_font(30), fill='#293e2c')
        draw.text((24, 58), '当前制作配方与运行网格的离线预览；移动摆动、出生落点仍需游戏内确认。', font=P.preview_font(20), fill='#586c5b')
        for i, title in enumerate(('侧面：每根小茎竖直承重', '斜前方：没有固定的左右斜伸')):
            draw.text((i*width+24, 104), title, font=P.preview_font(23), fill='#293e2c')
            im = Image.frombytes('RGBA', (width, height), (work/f'{i}.rgba').read_bytes())
            sheet.paste(im, (i*width, 145), im)
        sheet.save(args.output/'current.png')
    print(args.output/'current.png')


if __name__ == '__main__':
    main()
