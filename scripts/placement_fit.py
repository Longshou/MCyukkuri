"""Splice the placement fit into a runtime model, as YukkuriModel.applyFit and pose.js do.

Exporters that derive data from the shapes the game shows (cheek puff, hat lift and
seat) call fitted(); the base model files themselves are never rewritten. A fit whose
base fingerprint differs from the model file is ignored, matching the game.
"""
from pathlib import Path
import hashlib, json, math

ROOT = Path(__file__).resolve().parents[1]
RESOURCE = ROOT/'src/main/resources/assets/mcyukkuri/yukkuri_presentation/placement_fit.json'


def load(path=RESOURCE):
    data = json.loads(Path(path).read_text(encoding='utf-8'))
    assert data['format'] == 1, 'Unsupported placement fit format'
    return data['models']


def splice(model, fit):
    """Return a copy of model with the fit's meshes written in place or appended."""
    vertices = list(model['vertices'])
    weights = [list(w) for w in model['weights']]
    parts = [dict(p) for p in model['parts']]
    bones = [dict(b) for b in model['bones']]
    for entry in fit['parts']:
        part = parts[entry['part_index']]
        assert part['name'] == entry['name'], 'Placement fit part mismatch'
        src, bind, idx = entry['vertices'], entry['weights'], entry['indices']
        count = len(idx)
        assert count % 3 == 0 and (not entry['inplace'] or count == part['count']), 'Invalid placement fit mesh'
        start = part['start'] if entry['inplace'] else len(vertices)//8
        if not entry['inplace']:
            vertices.extend([0.0]*(count*8)); weights.extend([None]*count)
        for t in range(0, count, 3):
            c = idx[t:t+3]
            p = [src[k*5:k*5+3] for k in c]
            a = [p[1][i]-p[0][i] for i in range(3)]; b = [p[2][i]-p[0][i] for i in range(3)]
            n = [a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]]
            length = math.sqrt(sum(v*v for v in n))
            n = [v/length for v in n] if length > 1e-12 else [0.0, 0.0, -1.0]
            for j, k in enumerate(c):
                o = (start+t+j)*8
                vertices[o:o+8] = src[k*5:k*5+5]+n
                weights[start+t+j] = bind[k*3:k*3+3]
        if not entry['inplace']:
            part['start'], part['count'] = start, count
    for moved in fit['bone_pivots']:
        assert bones[moved['index']]['name'] == moved['name'], 'Placement fit bone mismatch'
        bones[moved['index']]['pivot'] = list(moved['pivot'])
    return {**model, 'vertices': vertices, 'weights': weights, 'parts': parts, 'bones': bones}


def fitted(path, fits=None):
    """Read a runtime model file and apply its placement fit when the fingerprint matches."""
    raw = Path(path).read_bytes(); model = json.loads(raw)
    fits = load() if fits is None else fits
    fit = fits.get(model['id'])
    if fit and fit['base_sha256'] == hashlib.sha256(raw).hexdigest():
        return splice(model, fit)
    return model
