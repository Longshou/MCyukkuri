"""Negative and positive fixture tests for scripts/check_face_fit.py: every checked property has a case that must be flagged
and a case that must pass."""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import check_face_fit as fit


def kinds(duration, loop, spans):
    return sorted({row[0] for row in fit.findings(fit.Timeline('t', 'x', duration, loop, [list(s) for s in spans]))})


class FaceFitTests(unittest.TestCase):
    def test_short_span_flagged(self):
        self.assertIn('short-span', kinds(3.0, False, [[0, 0.2, 'smug'], [0.2, 3.0, 'angry']]))

    def test_floor_span_passes(self):
        self.assertEqual(kinds(3.0, False, [[0, 0.5, 'smug'], [0.5, 3.0, 'angry']]), [])

    def test_switch_rate_flagged(self):
        spans = [[i*0.5, i*0.5+0.5, f] for i, f in enumerate(['smug', 'angry', 'smug', 'angry', 'smug'])]
        self.assertIn('switch-rate', kinds(2.5, False, spans))

    def test_loop_wrap_counts_as_a_change(self):
        self.assertIn('switch-rate', kinds(1.0, True, [[0, 0.5, 'smug'], [0.5, 1.0, 'angry']]))
        self.assertEqual(kinds(1.0, False, [[0, 0.5, 'smug'], [0.5, 1.0, 'angry']]), [])

    def test_wrap_joins_same_look(self):
        # smug 0.8-1.0 and 0-0.3 are one 0.5 s run in a loop: the run is measured with the wrap, not as two spans
        spans = [[0, 0.3, 'smug'], [0.3, 0.8, 'angry'], [0.8, 1.0, 'smug']]
        runs, sizes = fit.lengths(fit.Timeline('t', 'x', 1.0, True, spans))
        self.assertEqual([r[2] for r in runs], ['smug', 'angry'])
        self.assertAlmostEqual(sizes[0], 0.5)

    def test_eye_jump_flagged(self):
        self.assertIn('eye-jump', kinds(4.0, False, [[0, 2, 'smug'], [2, 4, 'puff_open']]))
        self.assertIn('eye-jump', kinds(4.0, False, [[0, 2, 'angry'], [2, 4, 'puff']]))

    def test_closed_eye_bridge_passes(self):
        self.assertEqual(kinds(6.0, False, [[0, 2, 'smug'], [2, 3, 'closed_smile'], [3, 6, 'puff_open']]), [])

    def test_small_anchor_shift_passes(self):
        self.assertEqual(kinds(4.0, False, [[0, 2, 'smug'], [2, 4, 'endure']]), [])

    def test_eye_size_flagged(self):
        self.assertIn('eye-size', kinds(4.0, False, [[0, 2, 'surprise'], [2, 4, 'cry']]))

    def test_mouth_change_is_a_change(self):
        # smug and angry share eyes and brows and differ in the mouth: a viewer still reads a new expression
        self.assertIn('short-span', kinds(3.0, False, [[0, 0.2, 'smug'], [0.2, 3.0, 'angry']]))

    def test_closed_eye_chew_cycle_passes(self):
        spans = [[i*0.2, i*0.2+0.2, f] for i, f in enumerate(['chewing', 'chewing_closed']*4)]
        self.assertEqual(kinds(1.6, True, spans), [])

    def test_blink_between_one_look_passes(self):
        self.assertEqual(kinds(4.0, False, [[0, 2, 'smug'], [2, 2.15, 'closed_smile'], [2.15, 4, 'smug']]), [])

    def test_long_closed_span_is_not_a_blink(self):
        self.assertIn('short-span', kinds(4.0, False, [[0, 2, 'smug'], [2, 2.4, 'closed_smile'], [2.4, 4, 'smug']]))

    def test_registry_b_frames_are_the_same_face(self):
        self.assertEqual(kinds(4.0, False, [[0, 2, 'cry'], [2, 4, 'cry_b']]), [])

    def test_face_clock_longer_than_the_loop_never_wraps(self):
        spans = [[0, 2.4, 'smug'], [2.4, 63.0, 'angry']]
        self.assertEqual(kinds(1.0, True, spans), [])

    def test_sumo_clips_are_clean_now(self):
        for t in fit.timelines():
            if t.ident in ('sumo_ready', 'sumo_shove', 'sumo_push_out', 'sumo_win', 'group_sumo_push', 'behavior_sumo_push'):
                self.assertEqual(fit.findings(t), [], t.key())


if __name__ == '__main__':
    unittest.main(verbosity=2)
