"""Author the shared rounded reproductive mesh and refresh the common component.

The old five cuboids produced a stalagmite silhouette. This is a continuous
low-poly ring surface with a full root, a mostly even shaft and a short rounded tip.
Run from the repository root; no runtime assets are written here.
"""
from pathlib import Path
import json
import math

HERE = Path(__file__).resolve().parent
TARGET = HERE / 'components/common/anatomy.json'
SIDES = 10
# z, horizontal radius, vertical radius. The shaft stays full; only the cap rounds in.
RINGS = [
    (-.68, .34, .30),
    (-1.25, .48, .42),
    (-2.10, .47, .41),
    (-3.00, .43, .38),
    (-3.52, .54, .46),
    (-3.92, .56, .47),
    (-4.25, .42, .36),
    (-4.48, .10, .09),
]


def peni_element():
    points, triangles, uvs = [], [], []
    for z, rx, ry in RINGS:
        for side in range(SIDES):
            angle = math.tau * side / SIDES
            points.append([round(rx * math.cos(angle), 6), round(1.15 + ry * math.sin(angle), 6), z])
    for row in range(len(RINGS) - 1):
        for side in range(SIDES):
            a, b = row * SIDES + side, row * SIDES + (side + 1) % SIDES
            c, d = b + SIDES, a + SIDES
            triangles.extend([(a, b, c), (a, c, d)])
            uvs.extend([[[1, 49], [1, 49], [1, 49]], [[1, 49], [1, 49], [1, 49]]])
    # Closed root and tip. The root cap is normally inside the body but keeps the mesh complete.
    for row, reverse in ((0, True), (len(RINGS) - 1, False)):
        for side in range(1, SIDES - 1):
            tri = (row * SIDES, row * SIDES + side, row * SIDES + side + 1)
            triangles.append(tuple(reversed(tri)) if reverse else tri)
            uvs.append([[1, 49], [1, 49], [1, 49]])
    lo = [min(p[k] for p in points) for k in range(3)]
    hi = [max(p[k] for p in points) for k in range(3)]
    return {
        'name': 'peni_taper_00', 'type': 'mesh', 'from': lo, 'to': hi,
        'origin': [0, 0, 0], 'rotation': [0, 0, 0], 'bone': 'anatomy_peni',
        'vertices': {str(i): p for i, p in enumerate(points)},
        'faces': {str(i): {'vertices': [str(k) for k in tri],
                           'uv': {str(k): uv for k, uv in zip(tri, uvs[i])}, 'texture': 0}
                  for i, tri in enumerate(triangles)},
    }


def main():
    data = json.loads(TARGET.read_text(encoding='utf-8'))
    data['elements'] = [e for e in data['elements'] if not e['name'].startswith('peni_taper_')]
    data['elements'].append(peni_element())
    data['description'] = ('Shared standard components: mamu below rear anus; peni below front mouth, '
                           'skin color, a continuous rounded low-poly shaft and short rounded tip. '
                           'Retracted by default; only one may extend at a time.')
    data['status'] = 'pending_visual_review'
    data['profile_revision'] = {
        'segments': 8, 'radial_sides': SIDES, 'closed_ring_surface': True,
        'shape': 'full root and even shaft with a short rounded cap; no stacked cuboids',
        'root_y': 1.15, 'root_z': -.88,
        'note': 'The previous five taper boxes and their stepped silhouette were replaced by one shared mesh.'
    }
    TARGET.write_text(json.dumps(data, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')


if __name__ == '__main__':
    main()
