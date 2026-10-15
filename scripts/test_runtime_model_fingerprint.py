"""Focused LF/CRLF compatibility checks, including actual packaged resources."""
import gzip
import hashlib
import json
from pathlib import Path
import unittest
from unittest.mock import patch

import export_badges
from runtime_model_fingerprint import matches_model_fingerprint


class ModelFingerprintTests(unittest.TestCase):
    def test_newlines_and_content_drift(self):
        lf = '{"id":"灵梦",\n"point":1.25,"escaped":"\\\\r\\\\n"}\n'.encode()
        for current in (lf, lf.replace(b"\n", b"\r\n"),
                        lf.replace(b"\n", b"\r\n", 1)):
            for exported in (lf, lf.replace(b"\n", b"\r\n"), current):
                self.assertTrue(matches_model_fingerprint(current, hashlib.sha256(exported).hexdigest()))
            for changed in (lf.replace(b"1.25", b"1.26"), lf.rstrip(),
                            lf.replace(b"\n", b"\r"), lf.replace(b":", b": ")):
                self.assertFalse(matches_model_fingerprint(current, hashlib.sha256(changed).hexdigest()))

    def test_packaged_models_and_real_content_mutation(self):
        assets = Path(__file__).resolve().parents[1] / "src/main/resources/assets/mcyukkuri"
        surfaces = list((assets / "yukkuri_wounds").glob("*.json.gz"))
        self.assertEqual(19, len(surfaces))
        for path in surfaces:
            with self.subTest(variant=path.stem):
                data = json.loads(gzip.decompress(path.read_bytes()))
                model = (assets / "yukkuri" / (data["id"] + ".json")).read_bytes()
                self.assertTrue(matches_model_fingerprint(model, data["runtimeSourceHash"]))
                self.assertFalse(matches_model_fingerprint(model + b" ", data["runtimeSourceHash"]))


    def test_badge_export_preserves_newlines_but_rejects_geometry_drift(self):
        name = 'accessory_marisa_sparse'
        assets = export_badges.ASSETS
        model_path = assets / 'yukkuri_props' / (name + '.json')
        raw = model_path.read_bytes().replace(b'\r\n', b'\n')
        specs = json.loads((export_badges.ROOT / 'art/source/rounded-runtime/attachments.json').read_text())['badge_mounts']['detached']
        previous = json.loads((assets / 'yukkuri/badge_mounts.json').read_text())['detached']
        read_bytes = Path.read_bytes
        for current in (raw, raw.replace(b'\n', b'\r\n')):
            def read(path):
                return current if path == model_path else read_bytes(path)
            with patch.object(Path, 'read_bytes', read):
                actual = export_badges.export_mount_group({name: specs[name]}, 'yukkuri_props', previous)
            self.assertEqual(previous[name], actual[name])
        changed = json.loads(raw)
        changed['vertices'][0] += .125
        def read(path):
            return json.dumps(changed).encode() if path == model_path else read_bytes(path)
        with patch.object(Path, 'read_bytes', read):
            actual = export_badges.export_mount_group({name: specs[name]}, 'yukkuri_props', previous)
        self.assertNotEqual(previous[name]['model_sha256'], actual[name]['model_sha256'])

    def test_all_detached_badges_and_geometry_drift(self):
        assets = Path(__file__).resolve().parents[1] / "src/main/resources/assets/mcyukkuri"
        mounts = json.loads((assets / "yukkuri/badge_mounts.json").read_text())["detached"]
        self.assertEqual(23, len(mounts))
        for name, mount in mounts.items():
            with self.subTest(accessory=name):
                model = (assets / "yukkuri_props" / (name + ".json")).read_bytes()
                lf = model.replace(b"\r\n", b"\n")
                for current in (lf, lf.replace(b"\n", b"\r\n"),
                                lf.replace(b"\n", b"\r\n", 1)):
                    self.assertTrue(matches_model_fingerprint(current, mount["model_sha256"]))
                    self.assertFalse(matches_model_fingerprint(current + b" ", mount["model_sha256"]))
                data = json.loads(model)
                data["vertices"][0] += 0.125
                changed = json.dumps(data, ensure_ascii=False).encode()
                self.assertFalse(matches_model_fingerprint(changed, mount["model_sha256"]))


if __name__ == "__main__":
    unittest.main()

