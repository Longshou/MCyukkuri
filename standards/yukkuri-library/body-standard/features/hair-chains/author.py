"""Segment Reimu's sideburn locks and Patchouli's long cheek locks into multi-bone chains.

Keeps every rest-pose vertex and silhouette identical while allowing natural gravity drape,
progressive curvature along the lock, and tip-driven motion instead of rigid single-bone swings.
"""
import math


CHAIN_SPECS = {
    'reimu': {
        'joints': [('lock_1', 7.80), ('lock_2', 6.55), ('lock_3', 5.25), ('lock_4', 4.00), ('tip', 1.75)],
        'subdivide_prefixes': ('side_hair_', 'tied_lock_'),
        'subdivide_step': 0.45,
        'blend_radius': 0.62,
        'cuff_blend': 0.75,
        'cuff_bone': 'lock_4',
    },
    'reimu_face': {
        'joints': [('lock_1', 7.80), ('lock_2', 6.55), ('lock_3', 5.25), ('lock_4', 4.00), ('tip', 1.75)],
        'subdivide_prefixes': ('side_hair_', 'tied_lock_'),
        'subdivide_step': 0.45,
        'blend_radius': 0.62,
        'cuff_blend': 0.75,
        'cuff_bone': 'lock_4',
    },
    'reimu_deibu': {
        'joints': [('lock_1', 7.80), ('lock_2', 6.55), ('lock_3', 5.25), ('lock_4', 4.00), ('tip', 1.75)],
        'subdivide_prefixes': (),  # already tapered into 14 horizontal layers
        'subdivide_step': 0.45,
        'blend_radius': 0.62,
        'cuff_blend': 0.75,
        'cuff_bone': 'lock_4',
    },
    'patchouli': {
        'joints': [('lock_1', 7.45), ('lock_2', 5.50), ('lock_3', 3.55), ('lock_4', 1.90), ('tip', 0.80)],
        'subdivide_prefixes': (),  # already 36 fine horizontal layers
        'subdivide_step': 0.55,
        'blend_radius': 0.78,
    },
}


def has_chain(ident):
    return ident in CHAIN_SPECS


def _clip(poly, y, above):
    result = []
    for a, b in zip(poly, poly[1:] + poly[:1]):
        inside_a = a[1] >= y if above else a[1] <= y
        inside_b = b[1] >= y if above else b[1] <= y
        if inside_a:
            result.append(a)
        if inside_a != inside_b:
            q = (y - a[1]) / (b[1] - a[1])
            result.append([a[k] + (b[k] - a[k]) * q for k in range(5)])
    return result


def _subdivide_element(e, rows, mesh, vertices, atlas_size):
    """Cut an element's triangles along horizontal Y rows, preserving exact geometry and UVs."""
    source = vertices(e, atlas_size)
    points, triangles, uvs = [], [], []
    for offset in range(0, len(source), 24):
        tri = [source[offset + j * 8:offset + j * 8 + 5] for j in range(3)]
        a = [tri[1][k] - tri[0][k] for k in range(3)]
        b = [tri[2][k] - tri[0][k] for k in range(3)]
        n = [a[1] * b[2] - a[2] * b[1], a[2] * b[0] - a[0] * b[2], a[0] * b[1] - a[1] * b[0]]
        if sum(n[k] * source[offset + 5 + k] for k in range(3)) < 0:
            tri.reverse()
        lo, hi = min(p[1] for p in tri), max(p[1] for p in tri)
        cuts = [lo] + [y for y in rows if lo + 1e-6 < y < hi - 1e-6] + [hi]
        bands = list(zip(cuts, cuts[1:])) if hi - lo > 1e-6 else [(lo, hi)]
        for low, high in bands:
            poly = _clip(_clip(tri, low, True), high, False)
            for j in range(1, len(poly) - 1):
                face = [poly[0], poly[j], poly[j + 1]]
                xyz = [p[:3] for p in face]
                u = [xyz[1][k] - xyz[0][k] for k in range(3)]
                v = [xyz[2][k] - xyz[0][k] for k in range(3)]
                if math.hypot(u[1] * v[2] - u[2] * v[1], u[2] * v[0] - u[0] * v[2], u[0] * v[1] - u[1] * v[0]) < 1e-9:
                    continue
                start = len(points)
                points.extend(xyz)
                triangles.append((start, start + 1, start + 2))
                uvs.append([[p[3] * atlas_size, p[4] * atlas_size] for p in face])
    item = mesh(e['name'], points, triangles, uvs)
    item['bone'] = e['bone']
    item['chain_subdivided'] = True
    if 'uuid' in e:
        item['uuid'] = e['uuid']
    return item


def _band_center(pts, y_target, half_band, fallback):
    sel = [p for p in pts if abs(p[1] - y_target) <= half_band]
    if not sel:
        sel = [p for p in pts if abs(p[1] - y_target) <= half_band * 3.0]
    if not sel:
        return [fallback[0], y_target, fallback[2]]
    cx = sum(p[0] for p in sel) / len(sel)
    cz = sum(p[2] for p in sel) / len(sel)
    return [round(cx, 7), round(y_target, 7), round(cz, 7)]


def segment(ident, elements, rig, mesh, vertices, atlas_size):
    """Subdivide long cube locks where needed and insert multi-bone chains under hand_left / hand_right."""
    spec = CHAIN_SPECS.get(ident)
    if spec is None:
        return elements
    joints = spec['joints']
    sub_prefixes = spec['subdivide_prefixes']
    step = spec['subdivide_step']
    joint_ys = [y for _, y in joints]
    grid = [round(i * step, 4) for i in range(1, int(10.5 / step) + 1)
            if all(abs(round(i * step, 4) - jy) >= 0.10 for jy in joint_ys)]
    rows = sorted(set(joint_ys + grid))

    out_elements = []
    side_pts = {'left': [], 'right': []}
    for e in elements:
        bone = e['bone']
        if bone in ('hand_left', 'hand_right'):
            if sub_prefixes and e['name'].startswith(sub_prefixes) and e.get('type', 'cube') != 'mesh':
                e = _subdivide_element(e, rows, mesh, vertices, atlas_size)
            vv = vertices(e, atlas_size)
            side = 'left' if bone == 'hand_left' else 'right'
            for i in range(0, len(vv), 8):
                side_pts[side].append((vv[i], vv[i + 1], vv[i + 2]))
        out_elements.append(e)

    # Append child chain bones at the end of rig['bones'] (topological parent-before-child order,
    # keeping all existing bone indices unchanged for donor-copied weights and downstream references).
    existing = {b['name'] for b in rig['bones']}
    by_name = {b['name']: b for b in rig['bones']}
    appended = []
    for side in ('left', 'right'):
        root_name = f'hand_{side}'
        if root_name not in by_name:
            continue
        parent = root_name
        prev_pivot = by_name[root_name]['pivot']
        pts = side_pts[side]
        for suffix, y_target in joints:
            name = f'hand_{side}_{suffix}'
            if name in existing:
                parent = name
                continue
            pivot = _band_center(pts, y_target, 0.35, prev_pivot)
            appended.append({'name': name, 'parent': parent, 'pivot': pivot})
            parent = name
            prev_pivot = pivot
    rig['bones'] = list(rig['bones']) + appended
    return out_elements


def _smoothstep(q):
    q = min(1.0, max(0.0, q))
    return q * q * (3.0 - 2.0 * q)


def _joint_intervals(joints, chain_ids, radius, cuff_y=None, cuff_blend=None):
    """Non-overlapping Y blend intervals centered on each joint pivot (prevents root counter-swing and LBS pinch)."""
    intervals = []
    for j, (_, yj) in enumerate(joints):
        if cuff_y is not None and abs(yj - cuff_y) < 1e-4:
            # Keep hair inside/below the cuff top rigid on the cuff bone so hair never pokes through the cuff.
            lo = cuff_y
            hi = cuff_y + (cuff_blend if cuff_blend is not None else radius)
        else:
            lo = yj - radius
            hi = yj + radius
        if intervals and hi > intervals[-1][1]:
            mid = 0.5 * (intervals[-1][1] + hi)
            intervals[-1] = (intervals[-1][0], mid, intervals[-1][2], intervals[-1][3])
            hi = mid
        intervals.append((hi, lo, chain_ids[j], chain_ids[j + 1]))
    return intervals


def binding(ident, part_name, hand_bone, y, names):
    """Continuous two-bone skinning with joint-centered smoothstep falloff along a segmented hair chain."""
    spec = CHAIN_SPECS.get(ident)
    if spec is None or hand_bone not in ('hand_left', 'hand_right'):
        return None
    side = 'left' if hand_bone == 'hand_left' else 'right'
    joints = spec['joints']
    chain_ids = [names[hand_bone]] + [names[f'hand_{side}_{suf}'] for suf, _ in joints]
    tip = names[f'hand_{side}_tip']
    if ident in ('reimu', 'reimu_face', 'reimu_deibu'):
        cuff_bone = names[f'hand_{side}_{spec["cuff_bone"]}']
        # Paper cuff stays completely rigid on the cuff bone; hair passing through the cuff matches it.
        if part_name.startswith('cuff_'):
            return [cuff_bone, cuff_bone, 0.0]
        if part_name.startswith('lock_tip_'):
            q = _smoothstep((1.75 - y) / 0.35)
            return [cuff_bone, tip, round(q, 7)]
        if y < 1.75:
            q = _smoothstep((1.75 - y) / 0.45)
            return [cuff_bone, tip, round(q, 7)]
        intervals = _joint_intervals(joints[:-1], chain_ids[:-1], spec['blend_radius'],
                                     cuff_y=4.00, cuff_blend=spec['cuff_blend'])
        for hi, lo, bp, bc in intervals:
            if y >= hi:
                return [bp, bp, 0.0]
            if y >= lo:
                q = _smoothstep((hi - y) / max(1e-6, hi - lo))
                return [bp, bc, round(q, 7)]
        return [cuff_bone, cuff_bone, 0.0]
    if ident == 'patchouli':
        if part_name.startswith(('patchouli_terminal_', 'patchouli_resting_tuft')):
            return [tip, tip, 0.0]
        intervals = _joint_intervals(joints, chain_ids, spec['blend_radius'])
        for hi, lo, bp, bc in intervals:
            if y >= hi:
                return [bp, bp, 0.0]
            if y >= lo:
                q = _smoothstep((hi - y) / max(1e-6, hi - lo))
                return [bp, bc, round(q, 7)]
        return [tip, tip, 0.0]
    return None
