"""Regression checks for read-only current-owner asset exports; no game launch.

Uses the checked-in selected sources and mocks corruption without modifying them.
Temporary files for byte-comparison fixtures stay under .local/.
"""
import contextlib
import hashlib
import io
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from asset_export_checks import check_output, fingerprint
import export_family_assets as family
import export_house_assets as house
import export_living_assets as living
import export_peripheral_assets as peripheral


ROOT = Path(__file__).resolve().parents[1]
EXPORTS = (
    (living, 'src/main/resources/assets/mcyukkuri/yukkuri_presentation/living.json',
     'art/source/rounded-runtime/reimu/runtime.model.json'),
    (family, 'src/main/resources/assets/mcyukkuri/yukkuri_presentation/family.json',
     'art/props/family-life/recipe.json'),
    (house, 'src/main/resources/assets/mcyukkuri/models/item/yukkuri_remains.json',
     'art/props/house-care/yukkuri_remains.item.json'),
    (peripheral, 'src/main/resources/assets/mcyukkuri/yukkuri_props/food_bowl.json',
     'art/props/runtime-peripheral/selected/food_bowl/food_bowl.mesh.json'),
)


class AssetExportChecks(unittest.TestCase):
    def test_only_json_line_endings_are_equivalent(self):
        work = ROOT / '.local/asset-export-tests'
        work.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(dir=work) as directory:
            path = Path(directory) / 'model.json'
            data = b'{"value": 1}\n'
            path.write_bytes(data.replace(b'\n', b'\r\n'))
            self.assertTrue(check_output(path, data))
            self.assertFalse(check_output(path, b'{"value": 2}\n'))
            self.assertFalse(check_output(path, b'{"value":1}\n'))
            self.assertFalse(check_output(path, data.rstrip()))
            binary = path.with_suffix('.png')
            binary.write_bytes(b'a\r\nb')
            self.assertFalse(check_output(binary, b'a\nb'))
            self.assertFalse(check_output(path.with_name('missing.json'), data))

    def test_fingerprint_preserves_only_verified_representation(self):
        data = b'{"value": 1}\n'
        old = hashlib.sha256(data.replace(b'\n', b'\r\n')).hexdigest()
        self.assertEqual(old, fingerprint(Path('model.json'), data, old))
        self.assertNotEqual(old, fingerprint(Path('model.json'), data + b' ', old))
        self.assertNotEqual(old, fingerprint(Path('model.json'), data.replace(b'1', b'2'), old))
        self.assertNotEqual(old, fingerprint(Path('texture.png'), data, old))

    def test_bowl_palette_preserves_bytes_and_rejects_pixel_drift(self):
        def png(image):
            buffer = io.BytesIO()
            image.save(buffer, format='PNG')
            return buffer.getvalue()
        atlas = peripheral.Image.new('RGBA', (512, 512), (30, 60, 90, 255))
        palette = atlas.crop((0, 0, 256, 256))
        original = png(palette)
        self.assertEqual(original, peripheral.selected_bowl_palette(png(atlas), original))
        atlas.putpixel((10, 10), (255, 60, 90, 255))
        with self.assertRaises(ValueError):
            peripheral.selected_bowl_palette(png(atlas), original)

    def run_check(self, exporter):
        with patch('sys.argv', [exporter.__file__, '--check']), \
                patch.object(Path, 'write_bytes', side_effect=AssertionError('check wrote bytes')), \
                patch.object(Path, 'write_text', side_effect=AssertionError('check wrote text')), \
                patch.object(Path, 'mkdir', side_effect=AssertionError('check created a directory')), \
                contextlib.redirect_stdout(io.StringIO()):
            exporter.main()

    def test_current_exports_match_without_writes(self):
        for exporter, _, _ in EXPORTS:
            with self.subTest(exporter=exporter.__name__):
                self.run_check(exporter)

    def test_crlf_checkout_matches_without_rewriting_provenance(self):
        read_bytes = Path.read_bytes
        def crlf(path):
            data = read_bytes(path)
            return data.replace(b'\r\n', b'\n').replace(b'\n', b'\r\n') if path.suffix == '.json' else data
        for exporter, _, _ in EXPORTS:
            with self.subTest(exporter=exporter.__name__), patch.object(Path, 'read_bytes', crlf):
                self.run_check(exporter)

    def test_runtime_corruption_is_rejected_without_writes(self):
        read_bytes = Path.read_bytes
        for exporter, output, _ in EXPORTS:
            def corrupted(path):
                data = read_bytes(path)
                return data + b' ' if path == ROOT / output else data
            with self.subTest(exporter=exporter.__name__), \
                    patch.object(Path, 'read_bytes', corrupted), self.assertRaises(SystemExit):
                self.run_check(exporter)

    def test_source_corruption_is_rejected_without_writes(self):
        read_bytes = Path.read_bytes
        for exporter, _, source in EXPORTS:
            def corrupted(path):
                data = read_bytes(path)
                return data + b' ' if path == ROOT / source else data
            with self.subTest(exporter=exporter.__name__), \
                    patch.object(Path, 'read_bytes', corrupted), \
                    self.assertRaises((SystemExit, ValueError)):
                self.run_check(exporter)


if __name__ == '__main__':
    unittest.main()
