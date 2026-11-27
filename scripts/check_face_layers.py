#!/usr/bin/env python
"""Read-only lint: no two face slots may draw ink on the same depth (z-fighting "line segment" flicker on the face).

Every runtime yukkuri model bakes its ten face slots (eyes, brows, mouth, blush, tears, mouth effect) onto one surface.
Where two slots of one expression both have opaque ink at the same point, only their depth decides which one shows; on
the same depth the depth test flips between them from pixel to pixel and frame to frame. The renderer therefore lifts each
slot by its own layer (Skin.faceCardLift: FACE_CARD_LIFT plus the slot's FACE_LAYERS rank times FACE_LAYER_STEP along the
card normal; house models take the layer without FACE_CARD_LIFT). This check rebuilds that rest-pose geometry from the runtime data for every
model and every expression texture, and reports each pair of slots whose ink overlaps while
  - same-layer: both slots sit on the same layer (or a slot has no layer), or
  - too-close: the slot on the higher layer is not at least MIN_SEPARATION in front of the lower one.

Usage:
  python scripts/check_face_layers.py --check [--model ID ...]   # findings above scripts/face_layer_allow.json exit 1
  python scripts/check_face_layers.py --list [--model ID ...]    # every finding, largest overlap first
  python scripts/check_face_layers.py --write-baseline           # tighten the baseline (never adds entries)
"""
import argparse
import json
import re
import sys
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ASSETS_REL = Path('src/main/resources/assets/mcyukkuri')
SKIN_REL = Path('src/main/java/io/github/longshou/mcyukkuri/pose/Skin.java')
ALLOW_PATH = Path(__file__).resolve().parent / 'face_layer_allow.json'

GRID = 0.04             # sample spacing on the face plane, model units
ALPHA_INK = 26          # entityCutoutNoCull discards alpha below 0.1
MIN_SEPARATION = 0.02   # model units the upper layer must stand in front of the lower one
MIN_AREA = 0.01         # overlap area (model units^2) below this is sampling noise at ink edges
FRONT_NZ = -0.15        # the renderer draws only face triangles whose normal z is below this


def read(path):
    return json.loads(Path(path).read_text(encoding='utf-8'))


def layer_table(root):
    """FACE_CARD_LIFT, FACE_LAYER_STEP and the slot -> rank table, read from the one Java owner."""
    text = (root / SKIN_REL).read_text(encoding='utf-8')
    card = float(re.search(r'FACE_CARD_LIFT\s*=\s*([0-9.]+)f', text).group(1))
    step = float(re.search(r'FACE_LAYER_STEP\s*=\s*([0-9.]+)f', text).group(1))
    layers = re.findall(r'"([^"]+)"', re.search(r'FACE_LAYERS\s*=\s*\{([^}]*)\}', text).group(1))
    ranks = {slot: rank for rank, group in enumerate(layers) for slot in group.split('|')}
    return card, step, ranks


def slot_of(part):
    for side in ('_left', '_right'):
        if part.endswith(side):
            return part[:-len(side)]
    return part


def front_triangles(values):
    tris = np.asarray(values, dtype=np.float64).reshape(-1, 3, 8)
    return tris[tris[:, 0, 7] < FRONT_NZ]


def card_normal(tris):
    if not len(tris):
        return None
    total = np.cross(tris[:, 1, :3] - tris[:, 0, :3], tris[:, 2, :3] - tris[:, 0, :3]).sum(axis=0)
    length = np.linalg.norm(total)
    return total / length if length > 1e-8 else None


def rasterize(tris):
    """Grid samples covered by the triangles: keys, front depth (-z), u, v."""
    keys, depth, us, vs = [], [], [], []
    for tri in tris:
        p = tri[:, :2]
        lo = np.floor(p.min(axis=0) / GRID).astype(int)
        hi = np.ceil(p.max(axis=0) / GRID).astype(int)
        gx, gy = np.meshgrid(np.arange(lo[0], hi[0] + 1), np.arange(lo[1], hi[1] + 1), indexing='ij')
        gx, gy = gx.ravel(), gy.ravel()
        x, y = (gx + 0.5) * GRID, (gy + 0.5) * GRID
        (ax, ay), (bx, by), (cx, cy) = p
        det = (by - cy) * (ax - cx) + (cx - bx) * (ay - cy)
        if abs(det) < 1e-12:
            continue
        l1 = ((by - cy) * (x - cx) + (cx - bx) * (y - cy)) / det
        l2 = ((cy - ay) * (x - cx) + (ax - cx) * (y - cy)) / det
        l3 = 1 - l1 - l2
        inside = (l1 >= -1e-9) & (l2 >= -1e-9) & (l3 >= -1e-9)
        if not inside.any():
            continue
        w = np.stack([l1[inside], l2[inside], l3[inside]], axis=1)
        keys.append(gx[inside] * 1_000_003 + gy[inside])
        depth.append(-(w @ tri[:, 2]))
        us.append(w @ tri[:, 3])
        vs.append(w @ tri[:, 4])
    if not keys:
        empty = np.zeros(0)
        return empty.astype(np.int64), empty, empty, empty
    keys = np.concatenate(keys)
    keys, first = np.unique(keys, return_index=True)  # a sample on a shared edge counts once
    return keys, np.concatenate(depth)[first], np.concatenate(us)[first], np.concatenate(vs)[first]


def model_faces(path, library):
    """Runtime model, its face parts and the merged expression patches, as the renderer loads them."""
    data = read(path)
    faces = [(index, part['name']) for index, part in enumerate(data.get('parts', []))
             if part['name'].startswith('face_') and not part.get('anatomy')]
    patches = dict(data.get('expression_patches', {}))
    patches.update(library.get('models', {}).get(data['id'], {}).get('expression_patches', {}))
    return data, faces, patches


def check_model(path, root, layers, library):
    card_lift, step, ranks = layers
    data, faces, patches = model_faces(path, library)
    if not faces:
        return []
    ident = data['id']
    house = bool(data.get('house'))
    textures = root / ASSETS_REL / 'textures/entity' / ident
    if not textures.is_dir():
        return []
    vertices = data['vertices']
    cache = {}

    def samples(index, name, values, patched):
        # Patch lists stay alive in `patches`, so their identity names one geometry; the base slice is per part.
        key = (index, id(values) if patched else None)
        if key not in cache:
            tris = front_triangles(values).copy()
            normal = card_normal(tris)
            if normal is not None:
                rank = ranks.get(slot_of(name))
                tris[:, :, :3] += ((0 if house else card_lift) + (rank or 0) * step) * normal
            cache[key] = rasterize(tris)
        return cache[key]

    findings = []
    for png in sorted(textures.glob('*.png')):
        expression = png.stem
        with Image.open(png) as image:
            alpha = np.asarray(image.convert('RGBA'))[:, :, 3]
        height, width = alpha.shape
        patch = {entry['index']: entry['vertices'] for entry in patches.get(expression, [])}
        ink = {}
        for index, name in faces:
            part = data['parts'][index]
            patched = index in patch
            values = patch[index] if patched else vertices[part['start'] * 8:(part['start'] + part['count']) * 8]
            keys, depth, u, v = samples(index, name, values, patched)
            tx = np.clip((u * width).astype(int), 0, width - 1)
            ty = np.clip((v * height).astype(int), 0, height - 1)
            opaque = alpha[ty, tx] >= ALPHA_INK
            ink[name] = (keys[opaque], depth[opaque])
        names = [name for _, name in faces]
        for i, a in enumerate(names):
            for b in names[i + 1:]:
                common, ia, ib = np.intersect1d(ink[a][0], ink[b][0], assume_unique=True, return_indices=True)
                if not len(common):
                    continue
                ra, rb = ranks.get(slot_of(a)), ranks.get(slot_of(b))
                if ra is None or rb is None or ra == rb:
                    kind, bad = 'same-layer', np.ones(len(common), bool)
                else:
                    upper, lower = (ia, ib) if ra > rb else (ib, ia)
                    da, db = (ink[a][1], ink[b][1]) if ra > rb else (ink[b][1], ink[a][1])
                    kind, bad = 'too-close', da[upper] - db[lower] < MIN_SEPARATION
                area = float(bad.sum()) * GRID * GRID
                if area >= MIN_AREA:
                    findings.append(dict(model=ident, expression=expression, slots=f'{a}+{b}', kind=kind, area=round(area, 3)))
    return findings


def run(root=ROOT, models=None, layers=None):
    layers = layers or layer_table(root)
    assets = root / ASSETS_REL
    library_path = assets / 'yukkuri_presentation/face_library.json'
    library = read(library_path) if library_path.is_file() else {}
    findings = []
    for path in sorted((assets / 'yukkuri').glob('*.json')):
        if models and path.stem not in models:
            continue
        data = read(path)
        if 'parts' not in data or 'vertices' not in data:
            continue
        findings += check_model(path, root, layers, library)
    return findings


def finding_key(f):
    return f"{f['model']}|{f['expression']}|{f['slots']}|{f['kind']}"


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    mode = ap.add_mutually_exclusive_group(required=True)
    mode.add_argument('--check', action='store_true')
    mode.add_argument('--list', action='store_true')
    mode.add_argument('--write-baseline', action='store_true')
    ap.add_argument('--model', nargs='+')
    args = ap.parse_args(argv)
    if args.write_baseline and args.model:
        ap.error('--write-baseline covers every model')
    findings = run(models=args.model)
    allowed = set(read(ALLOW_PATH)['allow']) if ALLOW_PATH.is_file() else set()
    if args.list:
        for f in sorted(findings, key=lambda f: -f['area']):
            print(f"{f['model']:18} {f['expression']:22} {f['slots']:34} {f['kind']:10} area {f['area']}")
        print(f'{len(findings)} finding(s)')
        return 0
    keys = sorted({finding_key(f) for f in findings})
    if args.write_baseline:
        added = [k for k in keys if k not in allowed]
        if ALLOW_PATH.is_file() and added:
            print('refusing to add new findings to the baseline (only-down); fix them instead:')
            for key in added:
                print('  ' + key)
            return 1
        ALLOW_PATH.write_text(json.dumps(dict(
            note='Face slot ink overlaps on one depth that existed when the lint landed; only ever shrinks (scripts/check_face_layers.py).',
            allow=keys), indent=1, ensure_ascii=False) + '\n', encoding='utf-8', newline='\n')
        print(f'baseline: {len(keys)} entr(ies), {len(allowed - set(keys))} removed')
        return 0
    new = [f for f in findings if finding_key(f) not in allowed]
    for f in new:
        print(f"FAIL {f['model']}/{f['expression']}: {f['slots']} {f['kind']} overlap area {f['area']} "
              f"(two face slots draw ink on one depth; see Skin.FACE_LAYERS)")
    stale = allowed - set(keys) if not args.model else set()
    if stale:
        print(f'{len(stale)} baseline entr(ies) fixed; tighten with --write-baseline')
    print(f'face layers: {len(findings)} finding(s), {len(new)} above baseline')
    return 1 if new else 0


if __name__ == '__main__':
    sys.exit(main())
