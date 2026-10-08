"""Read-only operation export regressions; no game launch or asset writes.

Checks current selected geometry plus deliberately changed values, pixels,
fingerprints and missing files. Portable PNG/JSON representations are equivalent;
only unrounded Blockbench positions allow one model-unit-scaled ULP of libm noise.
"""
import base64
import contextlib
import copy
import hashlib
import importlib.util
import io
import json
import math
from pathlib import Path
import unittest
from unittest.mock import patch

from PIL import Image


ROOT = Path(__file__).resolve().parents[1]
SCRIPT = ROOT / 'standards/yukkuri-library/drafts/operation-tools/build.py'
SPEC = importlib.util.spec_from_file_location('operation_build', SCRIPT)
operation = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(operation)


def encoded(value):
    return json.dumps(value, separators=(',', ':')).encode()


def png(color=(30, 60, 90, 120), level=6, size=(2, 2)):
    stream = io.BytesIO()
    Image.new('RGBA', size, color).save(stream, format='PNG', compress_level=level)
    return stream.getvalue()


def bbmodel(texture):
    return {'elements': [{'vertices': {'0': [0.08660254037844385, 2.0, 0.0]},
                          'faces': {'0': {'vertices': ['0'], 'uv': {'0': [2.0, 8.0]}}}}],
            'textures': [{'source': 'data:image/png;base64,' + base64.b64encode(texture).decode()}]}


class PortableRepresentation(unittest.TestCase):
    def test_json_values_match_across_formatting_and_line_endings(self):
        actual = b'{\r\n  "parts": [1, 2.0], "id": "pin"\r\n}\r\n'
        self.assertTrue(operation.equivalent(Path('mesh.json'), actual,
                                             b'{"id":"pin","parts":[1,2]}\n'))

    def test_runtime_values_remain_exact(self):
        expected = {'vertices': [2.0, 0, 0], 'bounds': [[0, 2.0]], 'parts': [0, 1]}
        for changed in ({**expected, 'vertices': [math.nextafter(2.0, math.inf), 0, 0]},
                        {**expected, 'bounds': [[0, math.nextafter(2.0, math.inf)]]},
                        {**expected, 'parts': [1, 0]},
                        {**expected, 'extra': 1},
                        {**expected, 'parts': [False, 1]}):
            with self.subTest(changed=changed):
                self.assertFalse(operation.equivalent(Path('mesh.json'), encoded(changed), encoded(expected)))

    def test_bad_json_and_nonfinite_values_fail(self):
        for bad in (b'{', b'{"v":NaN}', b'{"v":Infinity}', b'{"v":1e400}'):
            self.assertFalse(operation.equivalent(Path('mesh.json'), bad, b'{"v":1}'))

    def test_png_compression_is_ignored_but_pixels_alpha_and_size_are_not(self):
        expected = png(level=0)
        self.assertNotEqual(expected, png(level=9))
        self.assertTrue(operation.equivalent(Path('texture.png'), png(level=9), expected))
        for different in (png((31, 60, 90, 120)), png((30, 60, 90, 121)),
                          png(size=(1, 4)), b'not a PNG'):
            self.assertFalse(operation.equivalent(Path('texture.png'), different, expected))

    def test_embedded_png_uses_the_same_pixel_rules(self):
        expected = encoded(bbmodel(png(level=0)))
        self.assertTrue(operation.equivalent(Path('model.bbmodel'), encoded(bbmodel(png(level=9))), expected))
        self.assertFalse(operation.equivalent(Path('model.bbmodel'),
                                              encoded(bbmodel(png((30, 60, 90, 121)))), expected))

    def test_authoring_position_allows_only_one_scaled_ulp(self):
        expected = bbmodel(png())
        changed = copy.deepcopy(expected)
        changed['elements'][0]['vertices']['0'][0] = 0.08660254037844384
        self.assertTrue(operation.equivalent(Path('model.bbmodel'), encoded(changed), encoded(expected)))
        changed['elements'][0]['vertices']['0'][1] = math.nextafter(2.0, math.inf)
        self.assertTrue(operation.equivalent(Path('model.bbmodel'), encoded(changed), encoded(expected)))
        changed['elements'][0]['vertices']['0'][1] = math.nextafter(changed['elements'][0]['vertices']['0'][1], math.inf)
        self.assertFalse(operation.equivalent(Path('model.bbmodel'), encoded(changed), encoded(expected)))

    def test_authoring_uv_and_topology_stay_exact(self):
        expected = bbmodel(png())
        changed = copy.deepcopy(expected)
        changed['elements'][0]['faces']['0']['uv']['0'][0] = math.nextafter(2.0, math.inf)
        self.assertFalse(operation.equivalent(Path('model.bbmodel'), encoded(changed), encoded(expected)))
        changed = copy.deepcopy(expected)
        changed['elements'][0]['faces']['0']['vertices'].append('0')
        self.assertFalse(operation.equivalent(Path('model.bbmodel'), encoded(changed), encoded(expected)))

    def test_fingerprints_only_retain_verified_line_endings(self):
        data = b'{"value":1}\n'
        old = hashlib.sha256(data.replace(b'\n', b'\r\n')).hexdigest()
        for suffix in ('.json', '.bbmodel', '.py'):
            self.assertEqual(old, operation.fingerprint(Path('source' + suffix), data, old))
        self.assertNotEqual(old, operation.fingerprint(Path('texture.png'), data, old))
        self.assertNotEqual(old, operation.fingerprint(Path('source.json'), data + b' ', old))

    def test_fitting_uses_current_body_geometry(self):
        source = operation.ASSETS / 'yukkuri/reimu_sparse.json'
        read_text = Path.read_text
        model = json.loads(read_text(source, encoding='utf-8'))
        # Exercise the real fitter with one current body, independently of the
        # provenance hash check. A geometric source change must change fitting.
        for first in range(0, len(model['vertices']), 8):
            model['vertices'][first] *= 1.1
        with patch.object(Path, 'glob', return_value=[source]):
            before = operation.fitted()[0]
            with patch.object(Path, 'read_text', lambda path, *args, **kwargs:
                    json.dumps(model) if path == source else read_text(path, *args, **kwargs)):
                after = operation.fitted()[0]
        for kind in ('restraint_frame', 'wrap_sling', 'iron_enclosure'):
            self.assertNotEqual(before[kind + '_reimu_sparse'], after[kind + '_reimu_sparse'])


class CurrentOperationExports(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.generated = operation.outputs()

    def run_check(self, generated=None):
        with contextlib.ExitStack() as stack:
            stack.enter_context(patch('sys.argv', [str(SCRIPT), '--check']))
            for method in ('write_bytes', 'write_text', 'mkdir'):
                stack.enter_context(patch.object(Path, method, side_effect=AssertionError('--check wrote files')))
            stack.enter_context(contextlib.redirect_stdout(io.StringIO()))
            if generated is not None:
                stack.enter_context(patch.object(operation, 'outputs', return_value=generated))
            operation.main()

    def test_current_exports_match_without_writes(self):
        self.run_check(self.generated)

    def test_missing_output_fails_without_writes(self):
        missing = operation.ASSETS / 'yukkuri_props/pin_tool.json'
        is_file = Path.is_file
        with patch.object(Path, 'is_file', lambda path: False if path == missing else is_file(path)):
            with self.assertRaises(SystemExit):
                self.run_check(self.generated)

    def test_runtime_geometry_texture_and_manifest_mutations_fail_without_writes(self):
        read_bytes = Path.read_bytes
        mesh = operation.ASSETS / 'yukkuri_props/restraint_frame_reimu_sparse.json'
        texture = operation.ASSETS / 'textures/props/pin_tool.png'
        manifest = operation.SNAPSHOT / 'manifest.json'
        model = json.loads(read_bytes(mesh))
        model['vertices'][0] += .000001
        altered = {mesh: encoded(model), texture: png(), manifest: b'{}'}
        for target, replacement in altered.items():
            with self.subTest(target=target), patch.object(Path, 'read_bytes',
                    lambda path: replacement if path == target else read_bytes(path)):
                with self.assertRaises(SystemExit):
                    self.run_check(self.generated)

    def test_body_source_fingerprint_drift_fails_without_writes(self):
        source = operation.ASSETS / 'yukkuri/reimu_sparse.json'
        read_bytes = Path.read_bytes
        with patch.object(Path, 'read_bytes',
                lambda path: read_bytes(path) + b' ' if path == source else read_bytes(path)):
            with self.assertRaises(SystemExit):
                self.run_check()


if __name__ == '__main__':
    unittest.main()
