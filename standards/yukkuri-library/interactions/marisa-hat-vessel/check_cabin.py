"""Non-compiling checks against exported triangles: support, cargo and cavity continuity."""
from pathlib import Path
import json
import math
import numpy as np
from delivery import Hat

ROOT = Path(__file__).resolve().parents[4]
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'


def main():
    data = json.loads((ASSETS / 'yukkuri_presentation/hat_vessel.json').read_text())
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
    berths = data['cargo']['berths']
    assert len(berths) == 8
    brim = data['brim_rects']
    x0, x1, z0, z1 = data['opening']
    # The captain's complete square body (runtime body, hat-boat frame): half its width on each side of the centre line.
    body = json.loads((ASSETS / 'yukkuri_presentation/body_geometry.json').read_text())['variants']['marisa']
    half = (body['bounds'][0][1]-body['bounds'][0][0]) / data['aquatic_scale'] / 2
    for i, berth in enumerate(berths):
        x, z, r = (berth[k] for k in ('x', 'z', 'radius'))
        for angle in np.linspace(0, 2*math.pi, 33):
            px, pz = x+r*math.cos(angle), z+r*math.sin(angle)
            # on the brim, outside the opening, and on the flat the young never stand on (their brim places lie at z <= -2)
            assert not (x0-.025 < px < x1+.025 and z0-.025 < pz < z1+.025), ('cargo over the opening', i, px, pz)
            assert any(a <= px <= b and c <= pz <= d for a, b, c, d in brim), ('cargo off the brim', i, px, pz)
        assert z-r > -2+.05, ('cargo reaches the front brim of the young', i)
        assert abs(x)-r > half+.025, ('cargo crosses the captain body', i, half)
        assert berth['lift'] >= .16 and (berth['pad'] > 0) == (berth['lift'] < .5)
    floor = [b for b in berths if b['pad'] > 0]
    for i, berth in enumerate(floor):
        for other in floor[:i]:
            assert math.hypot(berth['x']-other['x'], berth['z']-other['z']) > berth['radius']+other['radius']-.15, ('floor servings crowd', berth, other)
    for berth in berths:
        if berth['pad'] == 0:   # a nested serving rests in the groove between two floor servings of its own side
            near = [b for b in floor if b['x']*berth['x'] > 0 and abs(b['z']-berth['z']) < 1.3]
            assert len(near) >= 2, ('nested serving has no groove', berth)
    sections = data['cavity_sections']
    assert sections[-1]['depth'] == data['floor']
    for outer, inner in zip(sections, sections[1:]):
        a, b = outer['rect'], inner['rect']
        assert a[0] <= b[0] < b[1] <= a[1] and a[2] <= b[2] < b[3] <= a[3]
        assert outer['depth'] < inner['depth'] < data['keel']
    print(f'Checked {checked} mesh support rays, {len(berths)} brim cargo envelopes, {len(sections)} nested cavity levels; no game/Java compilation.')


if __name__ == '__main__':
    main()
