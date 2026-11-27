#!/usr/bin/env python
"""Negative and positive tests for scripts/check_face_layers.py on the real runtime data.

The original defect: reimu `impact_teary` (toilet_accident, first_toilet_stimulated) stretches its tear card over the
squeezed eye, and with every slot on one depth the eye lines flickered as thin segments. Without layers the lint must
report it; with the Skin.FACE_LAYERS layers it must be clean; a slot moved onto another slot's layer or left without a
layer must be reported again.
"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_face_layers as lint  # noqa: E402

CARD, STEP, RANKS = lint.layer_table(lint.ROOT)


def pairs(findings, expression='impact_teary'):
    return {(f['slots'], f['kind']) for f in findings if f['expression'] == expression}


class TestFaceLayers(unittest.TestCase):
    def test_flat_faces_reproduce_the_toilet_flicker(self):
        found = pairs(lint.run(models=['reimu'], layers=(CARD, 0.0, RANKS)))
        self.assertIn(('face_eye_left+face_tear_left', 'too-close'), found)
        self.assertIn(('face_eye_right+face_tear_right', 'too-close'), found)

    def test_layers_clear_every_reimu_face(self):
        self.assertEqual(lint.run(models=['reimu']), [])

    def test_same_layer_is_reported(self):
        ranks = dict(RANKS, face_tear=RANKS['face_eye'])
        self.assertIn(('face_eye_left+face_tear_left', 'same-layer'),
                      pairs(lint.run(models=['reimu'], layers=(CARD, STEP, ranks))))

    def test_slot_without_layer_is_reported(self):
        ranks = {slot: rank for slot, rank in RANKS.items() if slot != 'face_tear'}
        self.assertIn(('face_eye_left+face_tear_left', 'same-layer'),
                      pairs(lint.run(models=['reimu'], layers=(CARD, STEP, ranks))))

    def test_house_faces_take_layers_too(self):
        self.assertTrue(lint.run(models=['house_reimu'], layers=(CARD, 0.0, RANKS)))
        self.assertEqual(lint.run(models=['house_reimu']), [])


if __name__ == '__main__':
    unittest.main()
