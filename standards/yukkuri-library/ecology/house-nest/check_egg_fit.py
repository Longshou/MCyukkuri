"""Check selected egg/newborn geometry without compiling Java or launching the game.

Uses the closed convex intact egg's triangle planes, including the authored shell
thickness. This is a neutral geometry check, not animation or game validation.
"""
from pathlib import Path
import json
import re
import numpy as np

ROOT = Path(__file__).resolve().parents[4]
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'


def read(path):
    return json.loads(path.read_text(encoding='utf-8'))


def main():
    recipe = read(Path(__file__).with_name('recipe.json'))
    spec = read(ASSETS / 'yukkuri_presentation/house_egg_geometry.json')
    growth = read(ROOT / 'standards/yukkuri-v1/profiles/growth.json')['birth']['egg']['scale']
    assert growth == read(ASSETS / 'yukkuri_presentation/family.json')['growth']['birth']['egg']['scale'], 'Runtime birth scale differs from authoring source'
    assert spec['model_scale'] == recipe['egg_geometry']['model_scale']
    assert spec['slots'] == recipe['display']['egg_slots']
    scale = spec['model_scale']
    egg = np.array(read(ASSETS / 'yukkuri_props/house_egg.json')['vertices']).reshape(-1, 3, 8)
    a, b, c = (egg[:, i, :3] * scale for i in range(3))
    normals = np.cross(b-a, c-a)
    lengths = np.linalg.norm(normals, axis=1)
    valid = lengths > 1e-9
    normals, a = normals[valid] / lengths[valid, None], a[valid]
    offsets = np.sum(normals*a, axis=1)[:, None]
    wall = recipe['shell']['wall_thickness'] * scale
    for ident in ('house_reimu', 'house_marisa'):
        model = read(ASSETS / f'yukkuri/{ident}.json')
        points = np.unique(np.array(model['vertices']).reshape(-1, 8)[:, :3], axis=0) * growth
        points[:, 1] += spec['hatch_foot_lift']
        clearance = -max((normals @ chunk.T - offsets).max() for chunk in np.array_split(points, 10)) - wall
        assert clearance > 0, f'{ident} does not fit inside the egg: {clearance}'
        print(f'{ident}: newborn scale {growth}; minimum inner clearance {clearance / 16:.5f} blocks')
    bounds = np.array(read(ASSETS / 'yukkuri_props/house_egg.json')['bounds']) * scale / 16
    slots = np.array(spec['slots'])
    for i, first in enumerate(slots):
        for second in slots[i+1:]:
            assert any(abs(first[k]-second[k]) > bounds[k, 1]-bounds[k, 0] for k in (0, 2)), 'Egg slots overlap'
    # Even the widest selected adult at the sleeping centre clears the egg column.
    for ident in ('house_reimu', 'house_marisa'):
        vertices = np.array(read(ASSETS / f'yukkuri/{ident}.json')['vertices']).reshape(-1, 8)
        adult_right = recipe['display']['sleeping_center'][0] + vertices[:, 0].max()*1.2/16
        assert adult_right < slots[:, 0].min() + bounds[0, 0], 'Adult sleeping outline overlaps egg column'
    life = (ROOT / 'src/main/java/io/github/longshou/mcyukkuri/house/HouseLife.java').read_text(encoding='utf-8')
    rests = re.findall(r'localPosition\((\.[0-9]+),\s*\.025,\s*(\.[0-9]+)\)', life)
    assert len(rests) == 2, 'Expected two real sleeping positions'
    for x, z in rests:
        assert float(x) + .162 < slots[:, 0].min() + bounds[0, 0], 'Adult sleeping collision overlaps egg column'
        for slot in slots:
            assert any(abs(float(value)-slot[axis]) > .162 + .27*growth/2 for value, axis in ((x, 0), (z, 2))), 'Newborn collision overlaps sleeper'
    cracked = np.array(read(ASSETS / 'yukkuri_props/house_egg_cracked.json')['bounds'])
    assert np.allclose(cracked, bounds*16/scale), 'Cracked egg changes envelope'
    shell = np.array(read(ASSETS / 'yukkuri_props/house_eggshell.json')['bounds']) * scale / 16
    assert abs(shell[1, 0]-bounds[1, 0]) < 1e-6, 'Empty shell changes foot plane'
    print('Four slots separated; selected adult sleeping outline clear; cracked/empty shell keep egg scale and floor.')


if __name__ == '__main__':
    main()
