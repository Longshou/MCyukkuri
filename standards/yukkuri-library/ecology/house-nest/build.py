"""Author cotton-lint props and export selected runtime snapshots. No compilation.

The egg item reuses the approved intact egg. --check never writes files.
"""
from pathlib import Path
import argparse
import base64
import hashlib
import io
import json
import math
import random
import uuid
from PIL import Image

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
SNAP = ROOT / 'art/source/house-ecology'
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'


def encoded(value):
    return (json.dumps(value, ensure_ascii=False, separators=(',', ':')) + '\n').encode()


def fingerprint(data):
    return hashlib.sha256(data).hexdigest()


class Model:
    def __init__(self, name):
        self.name, self.vertices, self.parts, self.elements = name, [], [], []

    def mesh(self, name, points, faces, color):
        start = len(self.vertices) // 8
        uv = [[color * 8 + 1, 1], [color * 8 + 6, 1], [color * 8 + 3, 6]]
        bbfaces = {}
        for face in faces:
            for j in range(1, len(face) - 1):
                tri = [face[0], face[j], face[j + 1]]
                a, b, c = [points[i] for i in tri]
                u, v = [[p[k] - a[k] for k in range(3)] for p in (b, c)]
                normal = [u[1]*v[2]-u[2]*v[1], u[2]*v[0]-u[0]*v[2], u[0]*v[1]-u[1]*v[0]]
                length = math.sqrt(sum(n*n for n in normal))
                if length < 1e-9:
                    continue
                normal = [n / length for n in normal]
                bbfaces[str(len(bbfaces))] = dict(vertices=[str(i) for i in tri], uv={str(i): t for i, t in zip(tri, uv)}, texture=0)
                for i, t in zip(tri, uv):
                    self.vertices.extend([*points[i], t[0] / 64, t[1] / 64, *normal])
        self.parts.append(dict(name=name, start=start, count=len(self.vertices)//8-start))
        self.elements.append(dict(name=name, type='mesh', uuid=str(uuid.uuid5(uuid.NAMESPACE_URL, 'house-ecology/' + self.name + '/' + name)),
                                  origin=[0,0,0], rotation=[0,0,0], vertices={str(i):p for i,p in enumerate(points)}, faces=bbfaces))

    def tuft(self, name, at, radii, color, segments=8, rings=4):
        points = []
        for row in range(rings + 1):
            latitude = math.pi * row / rings
            for col in range(segments):
                angle = math.tau * col / segments
                points.append([at[0] + radii[0]*math.sin(latitude)*math.cos(angle),
                               at[1] + radii[1]*math.cos(latitude),
                               at[2] + radii[2]*math.sin(latitude)*math.sin(angle)])
        faces = []
        for row in range(rings):
            for col in range(segments):
                nxt = (col+1) % segments
                faces.append((row*segments+col, row*segments+nxt, (row+1)*segments+nxt, (row+1)*segments+col))
        self.mesh(name, points, faces, color)

    def fiber(self, name, at, length, angle, color):
        # Thin raised strips of fiber, not paper rectangles. Width remains visible at item scale.
        c, s, w = math.cos(angle), math.sin(angle), .025
        points = [[at[0]+x*c-z*s, at[1]+y, at[2]+x*s+z*c]
                  for x,y,z in [(-length,0,-w),(length,0,-w),(length,.035,w),(-length,.035,w)]]
        self.mesh(name, points, [(3,2,1,0), (0,1,2,3)], color)

    def export(self):
        v = self.vertices
        return dict(format=1, units_per_block=16, vertices=v, parts=self.parts,
                    bounds=[[min(v[k::8]),max(v[k::8])] for k in range(3)])

    def bbmodel(self, texture, size=64):
        return dict(meta=dict(format_version='4.10', model_format='free'), name=self.name,
                    resolution=dict(width=size,height=size), elements=self.elements,
                    outliner=[e['uuid'] for e in self.elements], textures=[dict(name=self.name+'.png', id='0', width=size, height=size,
                    source='data:image/png;base64,'+base64.b64encode(texture).decode())])


def outputs():
    recipe_bytes = (HERE/'recipe.json').read_bytes()
    spec = json.loads(recipe_bytes)
    rng = random.Random(20261016)
    atlas = Image.new('RGBA', (64,64))
    for i, hexcolor in enumerate(spec['palette']):
        rgb = bytes.fromhex(hexcolor)
        for y in range(8):
            for x in range(8):
                d = (x * 7 + y * 11) % 7 - 3
                atlas.putpixel((i*8+x,y),tuple(max(0,min(255,c+d)) for c in rgb)+(255,))
    buf = io.BytesIO(); atlas.save(buf,format='PNG'); texture = buf.getvalue()
    lint = Model('nest_lint')
    lint.tuft('soft_core', [0,.32,0], spec['lint']['radius'], 1)
    for i in range(spec['lint']['tufts']):
        a = i*math.tau/spec['lint']['tufts']
        lint.tuft('lint_tuft_'+str(i), [.52*math.cos(a),.30+rng.uniform(0,.15),.34*math.sin(a)], [.32,.24,.28], i%3)
    for i in range(spec['lint']['fibers']):
        a = rng.uniform(0,math.tau)
        lint.fiber('loose_fiber_'+str(i), [.55*math.cos(a),.35+rng.uniform(0,.28),.40*math.sin(a)], rng.uniform(.22,.40),a+.8,2)
    bed = Model('house_lint_bedding')
    bed.tuft('packed_lint_floor', [0,.06,0],spec['bedding']['radius'],1,segments=16)
    for i in range(spec['bedding']['rim_tufts']):
        a = i*math.tau/spec['bedding']['rim_tufts']
        bed.tuft('rim_lint_'+str(i),[(3.10+rng.uniform(-.08,.08))*math.cos(a),.23+(i%3)*.045,(2.22+rng.uniform(-.08,.08))*math.sin(a)], [.48+rng.uniform(0,.12),.22,.35+rng.uniform(0,.12)],i%3)
    for i in range(spec['bedding']['floor_tufts']):
        a, r = i*2.4, math.sqrt((i+.5)/spec['bedding']['floor_tufts'])
        bed.tuft('floor_lint_'+str(i),[2.72*r*math.cos(a),.14,1.8*r*math.sin(a)],[.61,.14,.43],i%3)
    for i in range(spec['bedding']['fibers']):
        a = i*2.4
        bed.fiber('rim_fiber_'+str(i),[3.2*math.cos(a),.45+(i%2)*.04,2.3*math.sin(a)],.25+(i%3)*.07,a+.6,2 if i%4 else 3)

    # Derive only the empty lower cup from the user's intact approved shell.
    eggpath, eggtexpath = ROOT/spec['shell']['source'], ROOT/spec['shell']['texture']
    egg = json.loads(eggpath.read_bytes())
    shell = Model('house_eggshell')
    raw, selected = egg['vertices'], []
    for start in range(0,len(raw),24):
        triangle = raw[start:start+24]
        x = sum(triangle[k] for k in (0,8,16))/3
        y = sum(triangle[k] for k in (1,9,17))/3
        z = sum(triangle[k] for k in (2,10,18))/3
        edge = spec['shell']['rim_height']+spec['shell']['rim_wave']*math.sin(7*math.atan2(z,x))
        if y < edge:
            selected.extend(triangle)
    shell.vertices = selected[:]
    shell.parts.append(dict(name='approved_lower_shell', start=0, count=len(selected)//8))
    innerstart = len(selected)//8
    for i in range(0,len(selected),24):
        for j in (0,16,8):
            v = selected[i+j:i+j+8]
            shell.vertices.extend([*[v[k]-v[k+5]*spec['shell']['wall_thickness'] for k in range(3)],v[3],v[4],-v[5],-v[6],-v[7]])
    shell.parts.append(dict(name='inner_sugar_shell', start=innerstart, count=len(shell.vertices)//8-innerstart))
    # Preserve source UVs in the editable derivative without changing the source egg.
    tex = eggtexpath.read_bytes(); texsize = Image.open(io.BytesIO(tex)).width
    for i in range(0,len(shell.vertices),24):
        tri = shell.vertices[i:i+24]
        uid = str(uuid.uuid5(uuid.NAMESPACE_URL,'house-shell/'+str(i)))
        shell.elements.append(dict(name='shell_face_'+str(i//24),type='mesh',uuid=uid,origin=[0,0,0],rotation=[0,0,0],
            vertices={str(j):tri[j*8:j*8+3] for j in range(3)},
            faces={'0':dict(vertices=['0','1','2'],uv={str(j):[tri[j*8+3]*texsize,tri[j*8+4]*texsize] for j in range(3)},texture=0)}))

    result, catalog = {}, {}
    for model, pixels, size in ((lint,texture,64),(bed,texture,64),(shell,tex,texsize)):
        mesh = model.export()
        for base in (HERE/'assembled', SNAP):
            result[base/(model.name+'.json')] = encoded(mesh)
            result[base/(model.name+'.bbmodel')] = encoded(model.bbmodel(pixels,size))
            result[base/(model.name+'.png')] = pixels
        result[ASSETS/f'yukkuri_props/{model.name}.json'] = encoded(mesh)
        result[ASSETS/f'textures/props/{model.name}.png'] = pixels
        catalog[model.name] = dict(bounds=mesh['bounds'],triangles=len(mesh['vertices'])//24)
    result[ASSETS/'yukkuri_props/house-ecology-index.json'] = encoded(catalog)
    for name in ('nest_lint','house_egg'):
        scale = .85  # PropItemRenderer normalizes the mesh to 12 units before display transforms.
        result[ASSETS/f'models/item/{name}.json'] = encoded(dict(parent='minecraft:builtin/entity',gui_light='front',
            textures=dict(particle='mcyukkuri:props/'+name),display=dict(
            gui=dict(rotation=[25,225,0],translation=[0,0,0],scale=[scale]*3),
            ground=dict(translation=[0,1,0],scale=[1]*3),
            fixed=dict(scale=[2]*3),
            thirdperson_righthand=dict(translation=[0,2,0],scale=[1]*3),
            firstperson_righthand=dict(translation=[0,2,0],scale=[2]*3))))
    inputs = {p.relative_to(ROOT).as_posix():fingerprint(p.read_bytes()) for p in (HERE/'recipe.json',HERE/'build.py',eggpath,eggtexpath)}
    manifest = dict(status=spec['status'],source=HERE.relative_to(ROOT).as_posix(),inputs=inputs,
                    outputs={p.relative_to(ROOT).as_posix():fingerprint(v) for p,v in result.items()},
                    approved_eggs_unchanged=True, runtime_catalog='yukkuri_props/house-ecology-index.json')
    result[SNAP/'manifest.json'] = encoded(manifest)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--check',action='store_true')
    args = parser.parse_args()
    generated = outputs()
    for path,data in generated.items():
        if args.check:
            if not path.exists() or path.read_bytes()!=data:
                raise SystemExit('House ecology export differs: '+str(path.relative_to(ROOT)))
        else:
            path.parent.mkdir(parents=True,exist_ok=True)
            if not path.exists() or path.read_bytes()!=data:
                path.write_bytes(data)
    print(str(len(generated))+' house ecology exports '+('match.' if args.check else 'written.'))


if __name__ == '__main__':
    main()
