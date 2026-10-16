"""Convert the current native box model into a local mesh for software preview."""
from pathlib import Path
import io
import json
import os
import zipfile
from PIL import Image

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]


def minecraft_resources():
    explicit = os.environ.get('MCYUKKURI_VANILLA_JAR')
    if explicit:
        return Path(explicit)
    roots = [ROOT]
    if ROOT.parent.name == '.worktrees':
        roots.append(ROOT.parent.parent)
    for root in roots:
        for directory in ('build/moddev/artifacts', '.local/setup/preload/build/moddev/artifacts'):
            matches = sorted((root/directory).glob('*minecraft-resources.jar'))
            if matches:
                return matches[0]
    raise FileNotFoundError('Set MCYUKKURI_VANILLA_JAR to a local Minecraft resources JAR for the offline preview.')


def prepare(out):
    out = Path(out); out.mkdir(parents=True, exist_ok=True)
    source = ROOT/'src/main/resources/assets/mcyukkuri/models/block/household_guard.json'
    model = json.loads(source.read_text(encoding='utf-8'))
    keys = [key for key in model['textures'] if key != 'particle']
    atlas = Image.new('RGBA', (64, 64)); resource_hashes = {}
    import hashlib
    jar = minecraft_resources()
    with zipfile.ZipFile(jar) as package:
        for index, key in enumerate(keys):
            namespace, name = model['textures'][key].split(':')
            resource = 'assets/'+namespace+'/textures/'+name+'.png'
            data = package.read(resource); image = Image.open(io.BytesIO(data)).convert('RGBA')
            if image.size != (16, 16):
                raise ValueError('Expected a 16-pixel vanilla surface texture: '+resource)
            atlas.paste(image, (index*16, 0)); resource_hashes[resource] = hashlib.sha256(data).hexdigest()
    vertices = []; parts = []
    for element in model['elements']:
        x1, y1, z1 = element['from']; x2, y2, z2 = element['to']
        quads = {
            'north': ([(x1,y1,z1),(x1,y2,z1),(x2,y2,z1),(x2,y1,z1)], (0,0,-1)),
            'south': ([(x2,y1,z2),(x2,y2,z2),(x1,y2,z2),(x1,y1,z2)], (0,0,1)),
            'west': ([(x1,y1,z2),(x1,y2,z2),(x1,y2,z1),(x1,y1,z1)], (-1,0,0)),
            'east': ([(x2,y1,z1),(x2,y2,z1),(x2,y2,z2),(x2,y1,z2)], (1,0,0)),
            'up': ([(x1,y2,z1),(x1,y2,z2),(x2,y2,z2),(x2,y2,z1)], (0,1,0)),
            'down': ([(x1,y1,z2),(x1,y1,z1),(x2,y1,z1),(x2,y1,z2)], (0,-1,0)),
        }
        start = len(vertices)//8
        for face, settings in element['faces'].items():
            points, normal = quads[face]
            u1, v1, u2, v2 = settings['uv']; index = keys.index(settings['texture'][1:])
            uvs = [(u1,v2),(u1,v1),(u2,v1),(u2,v2)]
            for corner in (0,1,2,0,2,3):
                x,y,z = points[corner]; u,v = uvs[corner]
                # Keep texels inside their material's atlas tile, including the right/bottom edge.
                vertices.extend((x-8,y,z-8,(index*16+min(15.999,u))/64,min(15.999,v)/64,*normal))
        parts.append(dict(name=element['name'], start=start, count=len(vertices)//8-start))
    mesh = out/'household_guard.mesh.json'; texture = out/'household_guard.texture.png'
    mesh.write_text(json.dumps(dict(format=1, units_per_block=16, vertices=vertices, parts=parts,
        bounds=[[-5,5],[0,5],[-4,4]])), encoding='utf-8'); atlas.save(texture)
    (out/'household_guard.materials.json').write_text(json.dumps(dict(source_model=source.relative_to(ROOT).as_posix(),
        vanilla_resources=resource_hashes), indent=2), encoding='utf-8')
    return mesh, texture
