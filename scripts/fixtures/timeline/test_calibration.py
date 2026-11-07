"""Calibration controls derived from saved S2 timelines; injected breaks retain detection.

H source: instance-5a0da4a1c104, sleep-6 t99423..99432 and hat_push-16 t143807.
These minimal F reproductions preserve the relevant fields, not entire game recordings.
"""
import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
import smoke_checks as checks
import timeline
from cases import record, state


class Calibration(unittest.TestCase):
    def test_small_turns_do_not_repeat_one_yaw_reversal(self):
        # Sleep's roaming actor kept moving: twelve reported reversals, only two >1 degree.
        rows = [state(0, yaw=0), state(1, yaw=2)]
        rows += [state(t, yaw=2 - (t - 1) * .2, p=[t * .05, 0, 0]) for t in range(2, 31)]
        self.assertFalse(checks.progress(timeline.states(rows)))

    def test_real_large_yaw_reversals_still_fail(self):
        rows = [state(t, yaw=10 * (t % 2)) for t in range(20)]
        self.assertTrue(any(f['verdict'] == 'fail' for f in checks.progress(timeline.states(rows))))

    def test_path_steering_while_advancing_is_not_in_place_oscillation(self):
        # sleep-6 t101012..101112 advances about seven blocks through HabitatGoal path nodes.
        rows = [state(t, yaw=225 + 2 * ((t // 10) % 2), p=[t * .07, 0, 0]) for t in range(101)]
        self.assertFalse(checks.progress(timeline.states(rows)))

    def test_translating_stop_go_still_fails(self):
        rows = [state(t, p=[(t // 2) * .1, 0, 0]) for t in range(40)]
        self.assertTrue(any(f['verdict'] == 'fail' for f in checks.progress(timeline.states(rows))))

    def test_surface_support_is_not_depth_to_underwater_floor(self):
        rows = [record('win', 0, None), state(1), state(20)]
        rows += [record('geo', t, clear=True, supported=True, drop=1.47943115234375)
                 for t in (1, 20)]
        report = checks.observations(rows)
        self.assertEqual(next(f['verdict'] for f in report['summary'] if f['check'] == 'SPAWN'), 'pass')

    def test_unsupported_drop_and_obstruction_still_fail(self):
        for clear, supported, drop in ((True, False, .0626), (False, True, 0)):
            with self.subTest(clear=clear, supported=supported, drop=drop):
                rows = [record('win', 0, None), state(1), state(20)]
                rows += [record('geo', t, clear=clear, supported=supported, drop=drop) for t in (1, 20)]
                report = checks.observations(rows)
                self.assertEqual(next(f['verdict'] for f in report['summary'] if f['check'] == 'SPAWN'), 'fail')


if __name__ == '__main__':
    unittest.main(verbosity=2)
