#!/usr/bin/env python
"""Read-only lint for baked prop meshes (src/main/resources/assets/mcyukkuri/yukkuri_props/*.json).

Checks three flickering / z-fighting defect classes:
  1. uv_boundary: triangle UVs collapsed onto an integer texel boundary where neighbouring texels differ
     under nearest-neighbour sampling.
  2. coplanar_overlap: two triangles (same or opposite normal) lying on the same plane with overlapping area.
  3. boundary_plane: horizontal faces coincident with the ground plane (y = 0) on ground-placed props or
     outward faces coincident with a neighbour-block boundary plane (x/z = +/-8, +/-24, etc.) that can show.

Usage:
  python scripts/check_prop_meshes.py --check [--all | --changed [REV] | --model ID ...]
  python scripts/check_prop_meshes.py --write-baseline   # refresh/tighten scripts/prop_mesh_allow.json
"""
import argparse
import json
import math
import os
import subprocess
import sys
from collections import defaultdict
from pathlib import Path

import numpy as np
from PIL import Image

ROOT = Path(__file__).resolve().parents[1]
ALLOWLIST_PATH = Path(__file__).resolve().parent / 'prop_mesh_allow.json'
ASSETS_REL = Path('src/main/resources/assets/mcyukkuri')

UV_COLLAPSE_EPS = 0.002
UV_INT_EPS = 0.002
COPLANAR_DOT_MIN = 0.999
COPLANAR_DIST_MAX = 0.02
COPLANAR_AREA_MIN = 0.01
PLANE_EPS = 0.02
NORMAL_AXIS_MIN = 0.99
MIN_TRI_AREA = 1e-6


def _shared_root(root):
    try:
        common = subprocess.check_output(
            ['git', 'rev-parse', '--path-format=absolute', '--git-common-dir'],
            cwd=root, text=True, stderr=subprocess.DEVNULL
        ).strip()
        return Path(common).parent
    except (OSError, subprocess.CalledProcessError):
        return root


def _vanilla_root(root):
    env = os.environ.get('MCYUKKURI_VANILLA')
    if env:
        return Path(env)
    return _shared_root(root) / '.local/vanilla-ref/tex/assets/minecraft'


def _placed_block_ids(assets_dir):
    """Prop IDs that correspond to placed blocks (have a blockstate or block model)."""
    ids = set()
    for folder in ('blockstates', 'models/block'):
        d = assets_dir / folder
        if d.is_dir():
            for p in d.glob('*.json'):
                ids.add(p.stem)
    return ids


def _load_image_rgba(path, cache):
    key = str(path)
    if key not in cache:
        if not path.is_file():
            cache[key] = None
        else:
            with Image.open(path) as im:
                cache[key] = np.asarray(im.convert('RGBA'), dtype=np.uint8)
    return cache[key]


def _resolve_texture(part, model_id, assets_dir, vanilla_dir):
    ref = part.get('texture')
    if not ref:
        return assets_dir / 'textures/props' / f'{model_id}.png', True
    if ':' in ref:
        ns, rel = ref.split(':', 1)
    else:
        ns, rel = 'mcyukkuri', ref
    if ns == 'mcyukkuri':
        return assets_dir / rel, True
    if ns == 'minecraft':
        return vanilla_dir / rel, False
    return assets_dir / rel, False


def _texel_range(lo, hi, size):
    """Integer texel indices covered by [lo, hi] along an axis."""
    if hi - lo < UV_COLLAPSE_EPS:
        mid = 0.5 * (lo + hi)
        k = round(mid)
        if abs(mid - k) < UV_INT_EPS:
            return sorted({(k - 1) % size, k % size})
        return [int(math.floor(mid)) % size]
    start = math.floor(lo + 1e-4)
    end = math.ceil(hi - 1e-4)
    if end <= start:
        return [start % size]
    span = min(end - start, size)
    return [(start + i) % size for i in range(span)]


def check_uv_boundary(data, model_id, assets_dir, vanilla_dir, img_cache):
    """Return list of dicts describing triangles whose UV sits on a texel boundary with differing neighbours."""
    verts = np.asarray(data['vertices'], dtype=np.float64).reshape(-1, 8)
    hits = []
    missing_textures = []
    for part in data['parts']:
        tex_path, required = _resolve_texture(part, model_id, assets_dir, vanilla_dir)
        arr = _load_image_rgba(tex_path, img_cache)
        if arr is None:
            if required:
                missing_textures.append((part['name'], str(tex_path)))
            continue
        H, W = arr.shape[:2]
        start = part['start']
        count = part['count']
        p_verts = verts[start:start + count]
        n_tris = len(p_verts) // 3
        if n_tris == 0:
            continue
        uvs = p_verts[:n_tris * 3, 3:5].reshape(n_tris, 3, 2)
        tu = uvs[:, :, 0] * W
        tv = uvs[:, :, 1] * H
        u_min, u_max = tu.min(axis=1), tu.max(axis=1)
        v_min, v_max = tv.min(axis=1), tv.max(axis=1)
        u_col = (u_max - u_min) < UV_COLLAPSE_EPS
        v_col = (v_max - v_min) < UV_COLLAPSE_EPS
        cand = np.nonzero(u_col | v_col)[0]
        for ti in cand:
            flagged = False
            alpha_flip = False
            axis_hit = None
            boundary_val = None
            if u_col[ti]:
                u_mid = 0.5 * (u_min[ti] + u_max[ti])
                k = round(u_mid)
                if abs(u_mid - k) < UV_INT_EPS:
                    c0, c1 = (k - 1) % W, k % W
                    rows = _texel_range(v_min[ti], v_max[ti], H)
                    left_px = arr[rows, c0]
                    right_px = arr[rows, c1]
                    diff = np.any(left_px != right_px, axis=1)
                    if np.any(diff):
                        flagged = True
                        axis_hit = 'u'
                        boundary_val = int(k)
                        alpha_flip = bool(np.any((left_px[:, 3] == 0) != (right_px[:, 3] == 0)))
            if not flagged and v_col[ti]:
                v_mid = 0.5 * (v_min[ti] + v_max[ti])
                k = round(v_mid)
                if abs(v_mid - k) < UV_INT_EPS:
                    r0, r1 = (k - 1) % H, k % H
                    cols = _texel_range(u_min[ti], u_max[ti], W)
                    top_px = arr[r0, cols]
                    bot_px = arr[r1, cols]
                    diff = np.any(top_px != bot_px, axis=1)
                    if np.any(diff):
                        flagged = True
                        axis_hit = 'v'
                        boundary_val = int(k)
                        alpha_flip = bool(np.any((top_px[:, 3] == 0) != (bot_px[:, 3] == 0)))
            if flagged:
                hits.append({
                    'part': part['name'],
                    'tri': int(start // 3 + ti),
                    'axis': axis_hit,
                    'texel': boundary_val,
                    'alpha_flip': alpha_flip,
                })
    return hits, missing_textures


def _clip_poly_halfplane(poly, ax, ay, bx, by):
    """Clip 2D polygon to left side of directed edge a -> b."""
    if not poly:
        return []
    out = []
    dx, dy = bx - ax, by - ay
    n = len(poly)
    for i in range(n):
        sx, sy = poly[i - 1]
        ex, ey = poly[i]
        sd = dx * (sy - ay) - dy * (sx - ax)
        ed = dx * (ey - ay) - dy * (ex - ax)
        s_in = sd >= -1e-9
        e_in = ed >= -1e-9
        if e_in:
            if not s_in:
                denom = sd - ed
                if abs(denom) > 1e-12:
                    t = sd / denom
                    out.append((sx + t * (ex - sx), sy + t * (ey - sy)))
            out.append((ex, ey))
        elif s_in:
            denom = sd - ed
            if abs(denom) > 1e-12:
                t = sd / denom
                out.append((sx + t * (ex - sx), sy + t * (ey - sy)))
    return out


def _tri_overlap_area_2d(p0, p1, p2, q0, q1, q2):
    """Exact intersection area of two 2D triangles."""
    # Ensure both triangles are CCW
    if (p1[0] - p0[0]) * (p2[1] - p0[1]) - (p1[1] - p0[1]) * (p2[0] - p0[0]) < 0:
        p1, p2 = p2, p1
    if (q1[0] - q0[0]) * (q2[1] - q0[1]) - (q1[1] - q0[1]) * (q2[0] - q0[0]) < 0:
        q1, q2 = q2, q1
    poly = [p0, p1, p2]
    for a, b in ((q0, q1), (q1, q2), (q2, q0)):
        poly = _clip_poly_halfplane(poly, a[0], a[1], b[0], b[1])
        if len(poly) < 3:
            return 0.0
    area2 = 0.0
    for i in range(len(poly)):
        x0, y0 = poly[i - 1]
        x1, y1 = poly[i]
        area2 += x0 * y1 - x1 * y0
    return abs(area2) * 0.5


def _plane_basis(n):
    """Orthonormal basis (e1, e2) in the plane perpendicular to unit normal n."""
    ref = np.array([1.0, 0.0, 0.0]) if abs(n[0]) < 0.9 else np.array([0.0, 1.0, 0.0])
    e1 = np.cross(n, ref)
    e1 /= np.linalg.norm(e1)
    e2 = np.cross(n, e1)
    return e1, e2


def check_coplanar_overlap(data):
    """Detect coplanar overlapping triangle pairs (same or opposite normal)."""
    verts = np.asarray(data['vertices'], dtype=np.float64).reshape(-1, 8)
    n_tris = len(verts) // 3
    if n_tris < 2:
        return []
    tris = verts[:n_tris * 3, :3].reshape(n_tris, 3, 3)

    # Map triangle index -> part name
    part_names = [''] * n_tris
    for part in data['parts']:
        s = part['start'] // 3
        c = part['count'] // 3
        for idx in range(s, min(s + c, n_tris)):
            part_names[idx] = part['name']

    e0 = tris[:, 1] - tris[:, 0]
    e1 = tris[:, 2] - tris[:, 0]
    cross = np.cross(e0, e1)
    norms = np.linalg.norm(cross, axis=1)
    valid = norms > 2.0 * MIN_TRI_AREA
    valid_idx = np.nonzero(valid)[0]
    if len(valid_idx) < 2:
        return []

    unit_n = cross[valid_idx] / norms[valid_idx, None]
    # Canonicalize normal direction so opposite normals share the same plane bucket
    sign = np.ones(len(valid_idx), dtype=np.float64)
    for k in range(len(valid_idx)):
        nx, ny, nz = unit_n[k]
        if abs(nx) > 1e-5:
            if nx < 0:
                sign[k] = -1.0
        elif abs(ny) > 1e-5:
            if ny < 0:
                sign[k] = -1.0
        elif nz < 0:
            sign[k] = -1.0
    canon_n = unit_n * sign[:, None]
    plane_d = np.einsum('ij,ij->i', canon_n, tris[valid_idx, 0])

    # Spatial quantization on canonical normal (bin width 0.05; dot>0.999 implies L_inf diff < 0.045)
    nq = np.floor(canon_n * 20.0).astype(np.int32)
    buckets = defaultdict(list)
    for local_i in range(len(valid_idx)):
        buckets[(int(nq[local_i, 0]), int(nq[local_i, 1]), int(nq[local_i, 2]))].append(local_i)

    # Sort each bucket by plane_d for fast sweep-line / binary search
    sorted_buckets = {}
    for key, idxs in buckets.items():
        arr = np.array(idxs, dtype=np.int32)
        order = np.argsort(plane_d[arr])
        arr = arr[order]
        sorted_buckets[key] = (arr, plane_d[arr])

    hits = []
    seen_pairs = set()
    # Check pairs within same bucket and neighbouring normal buckets
    bucket_keys = sorted(sorted_buckets.keys())
    for key in bucket_keys:
        arr_a, d_a = sorted_buckets[key]
        kx, ky, kz = key
        # Neighbour keys >= key to avoid duplicate cross-bucket work
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                for dz in (-1, 0, 1):
                    nkey = (kx + dx, ky + dy, kz + dz)
                    if nkey < key or nkey not in sorted_buckets:
                        continue
                    arr_b, d_b = sorted_buckets[nkey]
                    same_bucket = (nkey == key)
                    # Sweep along d_a against d_b
                    lo = np.searchsorted(d_b, d_a - COPLANAR_DIST_MAX, side='left')
                    hi = np.searchsorted(d_b, d_a + COPLANAR_DIST_MAX, side='right')
                    for pos_a in range(len(arr_a)):
                        ia = int(arr_a[pos_a])
                        start_b = pos_a + 1 if same_bucket else int(lo[pos_a])
                        end_b = int(hi[pos_a])
                        if start_b >= end_b:
                            continue
                        na = canon_n[ia]
                        da = plane_d[ia]
                        ta = tris[valid_idx[ia]]
                        # Lazily compute basis/projection only if any candidate passes normal+plane check
                        cand_b = arr_b[start_b:end_b]
                        dots = canon_n[cand_b] @ na
                        mask = np.abs(dots) > COPLANAR_DOT_MIN
                        if not np.any(mask):
                            continue
                        sub_b = cand_b[mask]
                        # Verify all 3 vertices of candidate lie within COPLANAR_DIST_MAX of plane a
                        tb_all = tris[valid_idx[sub_b]]
                        v_dist = np.abs(np.einsum('ijk,k->ij', tb_all, na) - da).max(axis=1)
                        sub_b = sub_b[v_dist < COPLANAR_DIST_MAX]
                        if len(sub_b) == 0:
                            continue
                        b1, b2 = _plane_basis(na)
                        pa_u = ta @ b1
                        pa_v = ta @ b2
                        a_umin, a_umax = pa_u.min(), pa_u.max()
                        a_vmin, a_vmax = pa_v.min(), pa_v.max()
                        p2d = [(float(pa_u[m]), float(pa_v[m])) for m in range(3)]
                        tb_sub = tris[valid_idx[sub_b]]
                        pb_u = tb_sub @ b1
                        pb_v = tb_sub @ b2
                        # Vectorized 2D AABB filter
                        aabb_ok = (
                            (pb_u.max(axis=1) > a_umin + 1e-4) &
                            (pb_u.min(axis=1) < a_umax - 1e-4) &
                            (pb_v.max(axis=1) > a_vmin + 1e-4) &
                            (pb_v.min(axis=1) < a_vmax - 1e-4)
                        )
                        for idx_in_sub in np.nonzero(aabb_ok)[0]:
                            ib = int(sub_b[idx_in_sub])
                            ga, gb = int(valid_idx[ia]), int(valid_idx[ib])
                            pair_key = (ga, gb) if ga < gb else (gb, ga)
                            if pair_key in seen_pairs:
                                continue
                            q2d = [(float(pb_u[idx_in_sub, m]), float(pb_v[idx_in_sub, m])) for m in range(3)]
                            area = _tri_overlap_area_2d(p2d[0], p2d[1], p2d[2], q2d[0], q2d[1], q2d[2])
                            if area > COPLANAR_AREA_MIN:
                                seen_pairs.add(pair_key)
                                same_ori = bool(np.dot(unit_n[ia], unit_n[ib]) > 0)
                                hits.append({
                                    'tri_a': ga,
                                    'tri_b': gb,
                                    'part_a': part_names[ga],
                                    'part_b': part_names[gb],
                                    'area': round(area, 4),
                                    'same_normal': same_ori,
                                })
    return hits


def check_boundary_plane(data, model_id, is_placed_block):
    """Detect faces coincident with the ground plane (y = 0) or exterior neighbour-block planes on placed blocks."""
    if not is_placed_block:
        return []
    verts = np.asarray(data['vertices'], dtype=np.float64).reshape(-1, 8)
    n_tris = len(verts) // 3
    if n_tris == 0:
        return []
    tris = verts[:n_tris * 3, :3].reshape(n_tris, 3, 3)
    part_names = [''] * n_tris
    for part in data['parts']:
        s = part['start'] // 3
        c = part['count'] // 3
        for idx in range(s, min(s + c, n_tris)):
            part_names[idx] = part['name']

    e0 = tris[:, 1] - tris[:, 0]
    e1 = tris[:, 2] - tris[:, 0]
    cross = np.cross(e0, e1)
    norms = np.linalg.norm(cross, axis=1)
    valid = norms > 2.0 * MIN_TRI_AREA
    unit_n = np.zeros_like(cross)
    unit_n[valid] = cross[valid] / norms[valid, None]

    hits = []
    # 1. Ground plane y = 0 (horizontal faces facing down or up right on y = 0)
    on_y0 = valid & (np.abs(tris[:, :, 1]).max(axis=1) < PLANE_EPS) & (np.abs(unit_n[:, 1]) > NORMAL_AXIS_MIN)
    for ti in np.nonzero(on_y0)[0]:
        hits.append({
            'part': part_names[int(ti)],
            'tri': int(ti),
            'plane': 'y=0',
            'facing': 'down' if unit_n[ti, 1] < 0 else 'up',
        })

    # 2. Exterior neighbour-block planes: x/z at the prop's outer block boundary (+/-(8 + 16k)) or y at top (16k)
    bounds = data.get('bounds')
    if bounds and len(bounds) == 3:
        for ax, ax_name in ((0, 'x'), (2, 'z')):
            lo, hi = bounds[ax]
            for bound_val, sign in ((lo, -1), (hi, 1)):
                # Block boundaries around origin 0 are at ..., -24, -8, +8, +24, ...
                k = round((abs(bound_val) - 8.0) / 16.0)
                if k < 0:
                    continue
                plane = sign * (8.0 + 16.0 * k)
                if abs(bound_val - plane) > 0.05:
                    continue
                on_p = (
                    valid
                    & (np.abs(tris[:, :, ax] - plane).max(axis=1) < PLANE_EPS)
                    & (np.abs(unit_n[:, ax]) > NORMAL_AXIS_MIN)
                )
                for ti in np.nonzero(on_p)[0]:
                    hits.append({
                        'part': part_names[int(ti)],
                        'tri': int(ti),
                        'plane': f'{ax_name}={int(plane)}',
                        'facing': '+' if unit_n[ti, ax] > 0 else '-',
                    })
        y_hi = bounds[1][1]
        ky = round(y_hi / 16.0)
        if ky >= 1 and abs(y_hi - 16.0 * ky) <= 0.05:
            plane = 16.0 * ky
            on_top = (
                valid
                & (np.abs(tris[:, :, 1] - plane).max(axis=1) < PLANE_EPS)
                & (np.abs(unit_n[:, 1]) > NORMAL_AXIS_MIN)
            )
            for ti in np.nonzero(on_top)[0]:
                hits.append({
                    'part': part_names[int(ti)],
                    'tri': int(ti),
                    'plane': f'y={int(plane)}',
                    'facing': 'up' if unit_n[ti, 1] > 0 else 'down',
                })
    return hits


def lint_model(path, assets_dir, vanilla_dir, placed_ids, img_cache):
    data = json.loads(path.read_text(encoding='utf-8'))
    if 'vertices' not in data or 'parts' not in data:
        return None
    model_id = path.stem
    uv_hits, missing_tex = check_uv_boundary(data, model_id, assets_dir, vanilla_dir, img_cache)
    cop_hits = check_coplanar_overlap(data)
    bnd_hits = check_boundary_plane(data, model_id, model_id in placed_ids)
    return {
        'id': model_id,
        'uv_boundary': uv_hits,
        'coplanar_overlap': cop_hits,
        'boundary_plane': bnd_hits,
        'missing_textures': missing_tex,
    }


def _changed_models(root, rev):
    out = subprocess.check_output(['git', 'diff', '--name-only', '-z', rev, '--'], cwd=root).decode('utf-8').split('\0')
    untracked = subprocess.check_output(['git', 'ls-files', '--others', '--exclude-standard', '-z'], cwd=root).decode('utf-8').split('\0')
    prefix = (ASSETS_REL / 'yukkuri_props').as_posix() + '/'
    ids = set()
    for f in out + untracked:
        if f.startswith(prefix) and f.endswith('.json'):
            ids.add(Path(f).stem)
    return sorted(ids)


def load_allowlist(path):
    if path is None or not path.is_file():
        return {}
    return json.loads(path.read_text(encoding='utf-8')).get('models', {})


def main(argv=None):
    ap = argparse.ArgumentParser(description=__doc__)
    ap.add_argument('--check', action='store_true', help='Exit non-zero if any model exceeds the allowlist.')
    ap.add_argument('--all', action='store_true', help='Check all prop meshes (default when no --model/--changed).')
    ap.add_argument('--model', nargs='+', metavar='ID', help='Check only these model IDs.')
    ap.add_argument('--changed', nargs='?', const='HEAD', metavar='REV', help='Check prop meshes changed since REV.')
    ap.add_argument('--root', type=Path, default=ROOT, help='Repository root to scan.')
    ap.add_argument('--allowlist', type=str, default=str(ALLOWLIST_PATH), help='Allowlist JSON path, or "none".')
    ap.add_argument('--write-baseline', action='store_true', help='Write current offending counts to the allowlist file.')
    ap.add_argument('--verbose', action='store_true', help='Print per-part breakdown for models with findings.')
    ap.add_argument('--json', action='store_true', help='Emit JSON report to stdout.')
    args = ap.parse_args(argv)

    root = args.root.resolve()
    assets_dir = root / ASSETS_REL
    props_dir = assets_dir / 'yukkuri_props'
    if not props_dir.is_dir():
        print(f'check_prop_meshes: no yukkuri_props directory at {props_dir}', file=sys.stderr)
        return 2

    vanilla_dir = _vanilla_root(root)
    placed_ids = _placed_block_ids(assets_dir)
    allow_path = None if args.allowlist.lower() == 'none' else Path(args.allowlist)
    allow = load_allowlist(allow_path)

    if args.model:
        paths = [props_dir / f'{m}.json' for m in args.model]
        missing = [p for p in paths if not p.is_file()]
        if missing:
            print('check_prop_meshes: missing model(s): ' + ', '.join(str(p) for p in missing), file=sys.stderr)
            return 2
    elif args.changed is not None:
        ids = _changed_models(root, args.changed)
        paths = [props_dir / f'{m}.json' for m in ids if (props_dir / f'{m}.json').is_file()]
    else:
        paths = sorted(props_dir.glob('*.json'))

    img_cache = {}
    results = []
    for p in paths:
        res = lint_model(p, assets_dir, vanilla_dir, placed_ids, img_cache)
        if res is not None:
            results.append(res)

    if args.write_baseline:
        if allow_path is None:
            print('check_prop_meshes: cannot --write-baseline with --allowlist none', file=sys.stderr)
            return 2
        baseline = {}
        for r in results:
            entry = {}
            if r['uv_boundary']:
                entry['uv_boundary'] = len(r['uv_boundary'])
            if r['coplanar_overlap']:
                entry['coplanar_overlap'] = len(r['coplanar_overlap'])
            if r['boundary_plane']:
                entry['boundary_plane'] = len(r['boundary_plane'])
            if entry:
                baseline[r['id']] = entry
        payload = {
            'note': 'Baseline allowlist for existing prop mesh defects. Counts may only go down; new models must be 0.',
            'models': baseline,
        }
        allow_path.write_text(json.dumps(payload, indent=2, sort_keys=True) + '\n', encoding='utf-8', newline='\n')
        print(f'Wrote baseline allowlist ({len(baseline)} models) to {allow_path}')
        return 0

    failures = []
    summary = {'scanned': len(results), 'uv_boundary': 0, 'coplanar_overlap': 0, 'boundary_plane': 0, 'failures': []}
    for r in results:
        mid = r['id']
        caps = allow.get(mid, {})
        counts = {
            'uv_boundary': len(r['uv_boundary']),
            'coplanar_overlap': len(r['coplanar_overlap']),
            'boundary_plane': len(r['boundary_plane']),
        }
        for kind, cnt in counts.items():
            summary[kind] += cnt
            cap = caps.get(kind, 0)
            if cnt > cap:
                failures.append((mid, kind, cnt, cap, r[kind]))
        if r['missing_textures']:
            for part_name, tex_path in r['missing_textures']:
                failures.append((mid, 'missing_texture', 1, 0, [{'part': part_name, 'path': tex_path}]))
        if args.verbose and any(counts.values()):
            parts_info = []
            for kind in ('uv_boundary', 'coplanar_overlap', 'boundary_plane'):
                if counts[kind]:
                    parts_info.append(f'{kind}={counts[kind]} (cap={caps.get(kind, 0)})')
            print(f'  {mid}: ' + ', '.join(parts_info))

    summary['failures'] = [
        {'model': mid, 'kind': kind, 'count': cnt, 'allow': cap}
        for mid, kind, cnt, cap, _ in failures
    ]

    if args.json:
        print(json.dumps(summary, indent=2))
    else:
        for mid, kind, cnt, cap, items in failures:
            sample = items[0] if items else {}
            print(f'FAIL {mid}: {kind} count={cnt} > allow={cap} (e.g. {sample})')
        print(
            f'Prop mesh lint: {summary["scanned"]} models scanned; '
            f'uv_boundary={summary["uv_boundary"]}, coplanar_overlap={summary["coplanar_overlap"]}, '
            f'boundary_plane={summary["boundary_plane"]}; {len(failures)} failure(s).'
        )

    return 1 if (args.check and failures) else 0


if __name__ == '__main__':
    sys.exit(main())
