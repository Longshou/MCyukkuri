"""Expression geometry-offset contract; offline and independent of Minecraft."""
import copy
import unittest
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
from runtime_face_library import LIB, ROOT, compose_preset, read
from expression_geometry import offset_element, offset_vertices, preset_changes, profile_point
from mesh_geometry import vertices as mesh_vertices


class ExpressionGeometryTests(unittest.TestCase):
    def test_puff_teary_mouth_vertex_offsets(self):
        preset = read(LIB/'expressions/presets/puff_teary.json')
        values = []
        for point in [(-1, 0, 7), (1, 0, 7), (1, 2, 7), (-1, 0, 7), (1, 2, 7), (-1, 2, 7)]:
            values.extend([*point, 0, 0, 0, 0, -1])
        actual = offset_vertices(values, preset_changes(preset)['face_mouth'], [2, 3])
        expected = [[-.95, .46, 7], [.95, .46, 7], [.95, 2.26, 7],
                    [-.95, .46, 7], [.95, 2.26, 7], [-.95, 2.26, 7]]
        self.assertEqual([actual[index:index+3] for index in range(0, len(actual), 8)], expected)

    def test_baked_reimu_puff_teary_is_authoritative(self):
        model = read(ROOT/'src/main/resources/assets/mcyukkuri/yukkuri/reimu.json')
        preset = read(LIB/'expressions/presets/puff_teary.json')
        common = {e['name']: e for e in read(LIB/'components/common/face.json')['elements']}
        neutral = ROOT/'art/source/rounded-runtime/reimu/textures/neutral.png'
        _, entries = compose_preset(model, preset, neutral, common, with_texture=False)
        self.assertEqual(entries, model['expression_patches']['puff_teary'])
        delta = max(abs(entry['vertices'][i+axis]-model['expression_patches']['puff_teary'][row]['vertices'][i+axis])
                    for row, entry in enumerate(entries) for i in range(0, len(entry['vertices']), 8)
                    for axis in range(3))
        print(f'reimu/puff_teary geometry delta: {delta}')

    def test_changed_baked_preset_matches_scratch_rebake(self):
        model = read(ROOT/'src/main/resources/assets/mcyukkuri/yukkuri/reimu.json')
        preset = copy.deepcopy(read(LIB/'expressions/presets/puff_teary.json'))
        preset['geometry_offsets']['face_mouth']['position'][1] += .25
        common = {e['name']: e for e in read(LIB/'components/common/face.json')['elements']}
        neutral = ROOT/'art/source/rounded-runtime/reimu/textures/neutral.png'
        _, actual = compose_preset(model, preset, neutral, common, with_texture=False)

        config = read(LIB/'body-standard/recipe.json')
        source = LIB/config['sources']['reimu']['export']
        bb = read(source/'reimu.bbmodel')
        profile = read(LIB/'body-standard/profile.json')['body_profile']
        changes = preset_changes(preset)
        expected = []
        for entry in model['expression_patches']['puff_teary']:
            index = entry['index']
            name = model['parts'][index]['name']
            patched = offset_element(bb['elements'][index], changes[name], config['face_scale'],
                                     lambda y: -profile_point(profile, y)[2]-config['face_clearance'])
            values = mesh_vertices(patched, bb['resolution']['width'])
            for i in range(0, len(values), 8):
                values[i:i+3] = [round(value, 7) for value in values[i:i+3]]
            expected.append(dict(index=index, vertices=values))
        self.assertEqual(actual, expected)
        delta = max(abs(a-b) for entry, other in zip(actual, expected)
                    for a, b in zip(entry['vertices'], other['vertices']))
        mouth = next(i for i, part in enumerate(model['parts']) if part['name'] == 'face_mouth')
        moved = next(entry for entry in actual if entry['index'] == mouth)
        registered = next(entry for entry in model['expression_patches']['puff_teary'] if entry['index'] == mouth)
        self.assertAlmostEqual(moved['vertices'][1]-registered['vertices'][1], .27, places=7)
        print(f'changed puff_teary mouth movement: {moved["vertices"][1]-registered["vertices"][1]:.7f}; scratch rebake delta: {delta}')


if __name__ == '__main__':
    unittest.main(verbosity=2)
