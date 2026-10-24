"""Adapt native item/block elements to the existing offline prop renderer.

Only local model JSON and textures are read. No Minecraft, browser or exports.
"""
from pathlib import Path
import math

import numpy as np


def mesh(model, textures):
    vertices, parts = [], []
    normals = {'down':(0,-1,0), 'up':(0,1,0), 'north':(0,0,-1),
               'south':(0,0,1), 'west':(-1,0,0), 'east':(1,0,0)}
    for element in model['elements']:
        x0,y0,z0 = element['from']; x1,y1,z1 = element['to']
        faces = {
            'down':[(x0,y0,z1),(x0,y0,z0),(x1,y0,z0),(x1,y0,z1)],
            'up':[(x0,y1,z0),(x0,y1,z1),(x1,y1,z1),(x1,y1,z0)],
            'north':[(x1,y1,z0),(x1,y0,z0),(x0,y0,z0),(x0,y1,z0)],
            'south':[(x0,y1,z1),(x0,y0,z1),(x1,y0,z1),(x1,y1,z1)],
            'west':[(x0,y1,z0),(x0,y0,z0),(x0,y0,z1),(x0,y1,z1)],
            'east':[(x1,y1,z1),(x1,y0,z1),(x1,y0,z0),(x1,y1,z0)],
        }
        default_uv = {'down':[x0,16-z1,x1,16-z0], 'up':[x0,z0,x1,z1],
                      'north':[16-x1,16-y1,16-x0,16-y0], 'south':[x0,16-y1,x1,16-y0],
                      'west':[z0,16-y1,z1,16-y0], 'east':[16-z1,16-y1,16-z0,16-y0]}
        for face, info in element['faces'].items():
            ref = info['texture']; seen = set()
            while ref.startswith('#'):
                if ref in seen: raise ValueError(f'Cyclic block texture: {ref}')
                seen.add(ref); ref = textures[ref[1:]]
            if ':' not in ref: ref = 'minecraft:'+ref
            ns, path = ref.split(':', 1)
            texture = ns+':textures/'+path+'.png'
            uv = info.get('uv', default_uv[face]); u0,v0,u1,v1 = np.asarray(uv)/16
            coords = [(u0,v0),(u0,v1),(u1,v1),(u1,v0)]
            shift = info.get('rotation', 0)//90
            coords = coords[shift:]+coords[:shift]
            positions = np.asarray(faces[face], dtype=float)
            normal = np.asarray(normals[face], dtype=float)
            if rotation := element.get('rotation'):
                axis = 'xyz'.index(rotation['axis']); angle = math.radians(rotation['angle'])
                a, b = [(1,2),(2,0),(0,1)][axis]
                matrix = np.eye(3); matrix[a,a]=matrix[b,b]=math.cos(angle)
                matrix[a,b]=-math.sin(angle); matrix[b,a]=math.sin(angle)
                origin = np.asarray(rotation['origin'])
                positions = (positions-origin) @ matrix.T
                if rotation.get('rescale'): positions[:,[a,b]] /= abs(math.cos(angle))
                positions += origin; normal = matrix @ normal
            positions -= [8,0,8]
            start = len(vertices)
            for index in (0,1,2,0,2,3): vertices.append([*positions[index], *coords[index], *normal])
            parts.append(dict(start=start,count=6,texture=texture))
    if not vertices: raise ValueError('Native model has no visible faces')
    return dict(parts=parts), np.asarray(vertices, dtype=float)


def render_native(model, textures, root):
    from render_preview import Source, render
    class NativeSource(Source):
        def load(self, name):
            return mesh(model, textures)
    return render(NativeSource(root), [('native',8,0,0,0,1)], ppu=18, az=220, el=28,
                  bg=(243,240,232,255))[0]
