"""Focused LF/CRLF compatibility checks, including actual packaged resources."""
import gzip
import hashlib
import json
from pathlib import Path
import unittest
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

