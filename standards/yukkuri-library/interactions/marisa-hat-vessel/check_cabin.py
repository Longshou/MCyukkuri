"""Non-compiling checks against exported triangles: support and cavity continuity."""
from pathlib import Path
import json
import math
import numpy as np
from delivery import Hat

ROOT = Path(__file__).resolve().parents[4]
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'


def main():
    data = json.loads((ASSETS.parents[4] / 'standards/yukkuri-library/interactions/marisa-hat-vessel/geometry.json').read_text())
    mesh = json.loads((ASSETS / 'yukkuri_props/marisa_hat_turned.json').read_text())
    triangles = np.array(mesh['vertices']).reshape(-1, 3, 8)
    # Exact vertical ray intersection against the exported horizontal triangles, not its metadata bounds.
    upward = triangles[np.all(triangles[:, :, 6] > .99, axis=1), :, :3]

    def hit(x, z):
        hits = []
        for tri in upward:
            a, b, c = tri[:, [0, 2]]
            matrix = np.column_stack((b-a, c-a))
            if abs(np.linalg.det(matrix)) < 1e-10:
                continue
            u, v = np.linalg.solve(matrix, np.array([x, z])-a)
            if u >= -1e-7 and v >= -1e-7 and u+v <= 1+1e-7:
                hits.append(tri[0, 1])
        assert hits, ('missing support', x, z)
        return max(hits)

    checked = 0
    for mode in ('nest', 'bowl'):
        hat = Hat(data, mode)
        x0, x1, z0, z1 = data['opening']
        for x in np.linspace(x0+.04, x1-.04, 21):
            for z in np.linspace(z0+.04, z1-.04, 25):
                actual = hat.top+hit(x, z)*data['squash'][mode]
                assert abs(actual-hat.surface(x, z)) < .0002, ('surface mismatch', mode, x, z, actual, hat.surface(x, z))
                checked += 1
    sections = data['cavity_sections']
    assert sections[-1]['depth'] == data['floor']
    for outer, inner in zip(sections, sections[1:]):
        a, b = outer['rect'], inner['rect']
        assert a[0] <= b[0] < b[1] <= a[1] and a[2] <= b[2] < b[3] <= a[3]
        assert outer['depth'] < inner['depth'] < data['keel']
    print(f'Checked {checked} mesh support rays, {len(sections)} nested cavity levels; no game/Java compilation.')


if __name__ == '__main__':
    main()
