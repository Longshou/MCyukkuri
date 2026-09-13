"""Fit the selected hair to the rounded body, preserving texture and fringe tips.

Cut existing textured triangles at common horizontal rows before bending them.
The same coordinate mapping applies to overlapping locks and their backing hair,
so the clearance does not detach the ornamented outer strands from the cap.
"""
import math


def fit(elements, ident, config, profile, profile_at, mesh, vertices, atlas_size):
    rows = sorted(set([p[0] for p in profile] +
                      [i * config['max_strip_height'] for i in range(42)] +
                      [config['deibu_root_start_y'], config['deibu_root_full_y']]))

    def clip(poly, y, above):
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

    result = []
    for e in elements:
        if e['bone'] not in ('hair', 'bangs'):
            result.append(e)
            continue
        # No independent hair/bang tracks exist in these frozen rigs. These
        # surfaces now follow the same continuous body binding as their roots.
        source = vertices(e, atlas_size)
        rear_z = max(source[2::8])

        def warp(p):
            x, y, z = p[:3]
            if e['bone'] == 'hair':
                blend = max(0, min(1, (z - config['rear_blend_start']) /
                                      (config['rear_blend_end'] - config['rear_blend_start'])))
                z += blend * max(0, profile_at(y)[1] + config['body_clearance'] -
                                 config['rear_surface_reference'])
            elif ident == 'reimu_deibu' and e['name'].startswith('bang_'):
                height = max(0, min(1, (y - config['deibu_root_start_y']) /
                                       (config['deibu_root_full_y'] - config['deibu_root_start_y'])))
                # Only the rear of the root grows inward; front texture and
                # the lower pointed fringe remain at the selected coordinates.
                front_z = min(source[2::8])
                depth = max(0, min(1, (z - front_z) / max(1e-8, rear_z - front_z)))
                z += height * depth * max(0, config['deibu_root_back_z'] - rear_z)
            return [x, y, z]

        points, triangles, uvs = [], [], []
        for offset in range(0, len(source), 24):
            tri = [source[offset + j * 8:offset + j * 8 + 5] for j in range(3)]
            a = [tri[1][k] - tri[0][k] for k in range(3)]
            b = [tri[2][k] - tri[0][k] for k in range(3)]
            n = [a[1]*b[2]-a[2]*b[1], a[2]*b[0]-a[0]*b[2], a[0]*b[1]-a[1]*b[0]]
            if sum(n[k] * source[offset + 5 + k] for k in range(3)) < 0:
                tri.reverse()
            lo, hi = min(p[1] for p in tri), max(p[1] for p in tri)
            cuts = [lo] + [y for y in rows if lo + 1e-8 < y < hi - 1e-8] + [hi]
            bands = list(zip(cuts, cuts[1:])) if hi - lo > 1e-8 else [(lo, hi)]
            for low, high in bands:
                poly = clip(clip(tri, low, True), high, False)
                for j in range(1, len(poly) - 1):
                    face = [poly[0], poly[j], poly[j + 1]]
                    xyz = [warp(p) for p in face]
                    u = [xyz[1][k]-xyz[0][k] for k in range(3)]
                    v = [xyz[2][k]-xyz[0][k] for k in range(3)]
                    if math.hypot(u[1]*v[2]-u[2]*v[1], u[2]*v[0]-u[0]*v[2], u[0]*v[1]-u[1]*v[0]) < 1e-9:
                        continue
                    start = len(points)
                    points.extend(xyz)
                    triangles.append((start, start + 1, start + 2))
                    uvs.append([[p[3]*atlas_size, p[4]*atlas_size] for p in face])
        item = mesh(e['name'], points, triangles, uvs)
        item['bone'] = e['bone']
        item['body_follow'] = True
        result.append(item)
    return result
