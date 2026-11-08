"""Negative controls for the rounded source validator, without changing assets."""
import copy
import math
from pathlib import Path
import runpy
import sys
import unittest
from unittest.mock import patch

LIB = Path(__file__).resolve().parents[1]/'standards/yukkuri-library'
sys.path.insert(0, str(LIB))


class LibraryValidationTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.namespace = runpy.run_path(str(LIB/'validate.py'))

    def setUp(self):
        self.validate = self.namespace['rounded_character']
        self.read = self.namespace['read']
        self.character = self.read('characters/alice.json')
        self.rig = self.read('assembled/alice/animation-rig.json')

    def test_nonfinite_component_and_matching_export_are_rejected(self):
        def broken(path):
            value = self.read(path)
            if path in self.character['components'] or path == 'assembled/alice/alice.bbmodel':
                element = next(e for e in value['elements'] if e.get('type') == 'mesh')
                next(iter(element['vertices'].values()))[0] = math.inf
            return value
        with patch.dict(self.validate.__globals__, read=broken), self.assertRaises(AssertionError):
            self.validate('alice', self.character, self.rig)

    def test_wrong_anatomy_track_is_rejected(self):
        rig = copy.deepcopy(self.rig)
        keys = rig['clips']['anatomy_mamu_extend']['tracks']['anatomy_mamu']['position']
        keys[next(iter(keys))][0] += .1
        with self.assertRaises(AssertionError):
            self.validate('alice', self.character, rig)

    def test_same_size_wrong_atlas_pixels_are_rejected(self):
        from PIL import Image
        original = Image.open
        def broken(path, *args, **kwargs):
            image = original(path, *args, **kwargs)
            if Path(path) == LIB/'assembled/alice/textures/angry.png':
                image = image.convert('RGBA')
                image.putpixel((0, 0), (1, 2, 3, 255))
            return image
        with patch.object(Image, 'open', side_effect=broken), self.assertRaises(AssertionError):
            self.validate('alice', self.character, self.rig)


if __name__ == '__main__':
    unittest.main()
