"""Side reach of every fitted runtime model through the rain huddle and snuggle clips.

For each model the clip rain_huddle_right is posed over its whole loop (the game's composition: idle at 0 under the clip, the
fitted mesh, two-bone weights) and the farthest point of its surface toward +X (the body's right) is kept per cell of height and
depth (model units, front -Z to back +Z): every triangle is sampled at under half a cell, so a wide flat face such as a hat brim
fills every cell it spans, not only the cells of its corners; rain_huddle_left gives the reach toward -X. Hair, hat, braid and bow count; the anatomy and face
decals do not. The server adds two such reaches cell by cell (motor/geom/SideProfile) to stand two bodies side by side, the
second a little in front or behind when that lets their bows and hair pass each other: at the nestle they just meet and nothing
passes through. The snuggle uses finer height cells to resolve the brims of hats at different support heights.
Called after a weather or base-motions export; writes yukkuri_presentation/side_profile.json.
"""
from pathlib import Path
import json, runpy
import numpy as np

HERE = Path(__file__).resolve().parent
BAND = 1.          # model units per cell, in height and in depth
SNUGGLE_HEIGHT_BAND = .25  # model units; a 1/8-block step can put two thin brims in different bands
SAMPLE = .05       # seconds between posed samples of the loop
GRID = 64          # cells of the working grid in height and depth (the depth centred on 0)
SKIP = ('face_', 'puff_', 'mamu', 'peni')
STEP = BAND*.4     # surface sample spacing (model units), under half a cell; poses stretch the rest mesh only a little


def surface(V, keep):
    """Sample points on the kept triangles of the rest mesh as (triangle vertex indices, barycentric weights) groups: a
    triangle is split n ways along its edges, n from its longest rest edge, so the posed samples lie under STEP apart."""
    tris = keep.reshape(-1, 3)
    E = V[tris]
    longest = np.max(np.linalg.norm(E - np.roll(E, 1, axis=1), axis=2), axis=1)
    n = np.maximum(1, np.ceil(longest/STEP).astype(int))
    groups = []
    for k in np.unique(n):
        w = np.array([(i, j, k-i-j) for i in range(k+1) for j in range(k+1-i)], float)/k
        groups.append((tris[n == k], w))
    return groups


def profiles(carry, action, height_band):
    reach, z0 = {}, 0
    for ident, revision in sorted(carry['MOTION'].items()):
        if action+'_right' not in revision['clips']: continue
        body = carry['Body'](ident)
        keep = np.concatenate([np.arange(p['start'], p['start']+p['count']) for p in body.m['parts']
                               if not p.get('anatomy') and not p['name'].startswith(SKIP)])
        groups = surface(body.V, keep)
        for side, sign in (('right', 1), ('left', -1)):
            clip = action+'_'+side
            grid = np.full((int(GRID / height_band), GRID), -np.inf)   # [height][depth + GRID/2]
            for i in range(int(round(body.raw[clip]['duration']/SAMPLE))+1):
                posed = body.posed(body.palette([(body.clips[clip], i*SAMPLE)]))
                P = np.concatenate([np.einsum('kc,tcd->tkd', w, posed[t]).reshape(-1, 3) for t, w in groups])
                ys, zs = np.floor(P[:, 1]/height_band).astype(int), np.floor(P[:, 2]/BAND).astype(int)+GRID//2
                np.maximum.at(grid, (np.clip(ys, 0, grid.shape[0]-1), zs), P[:, 0]*sign)
            cells = {(int(y), int(z)-GRID//2): float(grid[y, z]) for y, z in zip(*np.nonzero(np.isfinite(grid)))}
            reach[ident, side] = cells
            z0 = min(z0, min(z for _, z in cells))
    models = {}
    for (ident, side), cells in reach.items():
        rows, cols = max(y for y, _ in cells)+1, max(z for _, z in cells)-z0+1
        models.setdefault(ident, {})[side] = [[round(max(0., cells.get((y, z+z0), 0.)), 2) for z in range(cols)] for y in range(rows)]
    return models, z0*BAND


def build():
    carry = runpy.run_path(str(HERE/'carry_hold_preview.py'))
    huddle, huddle_z0 = profiles(carry, 'rain_huddle', BAND)
    snuggle, snuggle_z0 = profiles(carry, 'snuggle', SNUGGLE_HEIGHT_BAND)
    compact = dict(ensure_ascii=False, separators=(',', ':'))
    header = json.dumps({'format': 1, 'units_per_block': 16, 'band': BAND, 'z0': huddle_z0,
                         'clips': ['rain_huddle_right', 'rain_huddle_left'],
                         'note': 'Farthest reach toward each side (model units from the centre line) per cell [height][depth from z0]. '
                                 'Zero where the pose has no surface.', 'models': huddle}, **compact)
    snuggle_header = json.dumps({'height_band': SNUGGLE_HEIGHT_BAND, 'z0': snuggle_z0}, **compact)
    lines = [json.dumps(name)+':'+json.dumps(model, **compact) for name, model in snuggle.items()]
    return header[:-1]+',\n"snuggle":'+snuggle_header[:-1]+',"models":{\n'+',\n'.join(lines)+'\n}}}\n'


if __name__ == '__main__':
    print(build()[:400])
