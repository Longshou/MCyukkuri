"""Side reach of every runtime model through its rain huddle clips: how far it reaches toward a partner at its side.

For each model the clip rain_huddle_right is posed over its whole loop (the game's composition: idle at 0 under the clip, the
fitted mesh, two-bone weights) and the farthest vertex toward +X (the body's right) is kept per cell of height and depth (model
units, front -Z to back +Z); rain_huddle_left gives the reach toward -X. Hair, hat, braid and bow count; the anatomy and face
decals do not. The server adds two such reaches cell by cell (motor/geom/SideProfile) to stand two bodies side by side, the
second a little in front or behind when that lets their bows and hair pass each other: at the nestle they just meet and nothing
passes through. Called by author.py --export-weather after the clips are written; writes yukkuri_presentation/side_profile.json.
"""
from pathlib import Path
import json, runpy
import numpy as np

HERE = Path(__file__).resolve().parent
BAND = 1.          # model units per cell, in height and in depth
SAMPLE = .05       # seconds between posed samples of the loop
GRID = 64          # cells of the working grid in height and depth (the depth centred on 0)
SKIP = ('face_', 'puff_', 'mamu', 'peni')


def build():
    carry = runpy.run_path(str(HERE/'carry_hold_preview.py'))   # loads the clips just written
    Body = carry['Body']
    reach, z0 = {}, 0
    for ident in sorted(carry['MOTION']):
        body = Body(ident)
        if 'rain_huddle_right' not in body.clips: continue
        keep = np.concatenate([np.arange(p['start'], p['start']+p['count']) for p in body.m['parts']
                               if not p.get('anatomy') and not p['name'].startswith(SKIP)])
        for side, clip, sign in (('right', 'rain_huddle_right', 1), ('left', 'rain_huddle_left', -1)):
            grid = np.full((GRID, GRID), -np.inf)   # [height][depth + GRID/2]
            for i in range(int(round(body.raw[clip]['duration']/SAMPLE))+1):
                P = body.posed(body.palette([(body.clips[clip], i*SAMPLE)]))[keep]
                ys, zs = np.floor(P[:, 1]/BAND).astype(int), np.floor(P[:, 2]/BAND).astype(int)+GRID//2
                np.maximum.at(grid, (np.clip(ys, 0, GRID-1), zs), P[:, 0]*sign)
            cells = {(int(y), int(z)-GRID//2): float(grid[y, z]) for y, z in zip(*np.nonzero(np.isfinite(grid)))}
            reach[ident, side] = cells
            z0 = min(z0, min(z for _, z in cells))
    models = {}
    for (ident, side), cells in reach.items():
        rows, cols = max(y for y, _ in cells)+1, max(z for _, z in cells)-z0+1
        models.setdefault(ident, {})[side] = [[round(max(0., cells.get((y, z+z0), 0.)), 2) for z in range(cols)] for y in range(rows)]
    return json.dumps({'format': 1, 'units_per_block': 16, 'band': BAND, 'z0': z0, 'clips': ['rain_huddle_right', 'rain_huddle_left'],
                       'note': 'Farthest reach toward each side (model units from the centre line) per cell [height][depth from z0] '
                               'over the posed huddle loop; 0 where the body has nothing.',
                       'models': dict(sorted(models.items()))}, ensure_ascii=False, separators=(',', ':'))+'\n'


if __name__ == '__main__':
    print(build()[:400])
