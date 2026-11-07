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
from cases import record, state, HOST


class Calibration(unittest.TestCase):
    def test_life_goal_recovery_without_intent_is_progress(self):
        for key in ('nut', 'en'):
            rows = [state(t, int=None, goals=['LifeGoal@3'], need={key: t / 20}) for t in range(1202)]
            self.assertFalse(checks.progress(timeline.states(rows)))
        rows = [state(t, int=None, goals=['LifeGoal@3'], need={'en': 100}) for t in range(1202)]
        self.assertTrue(any(f['verdict'] == 'fail' for f in checks.progress(timeline.states(rows))))

    def test_logged_acquisition_and_stem_are_explanations(self):
        for message in ('received item=minecraft:short_grass count=1 holder=mouth',
                        'gathered item=minecraft:short_grass count=1 source=BlockPos{x=0,y=0,z=0} holder=mouth',
                        'shed stem delivered brood=fixture item=fixture existing=false'):
            rows = [state(0), state(1, hold={'mouth': [{'item': 'minecraft:short_grass', 'n': 1}]}),
                    record('ev', 1, cat='meal', msg=message)]
            self.assertFalse(checks.ledger(rows, timeline.states(rows)))
        # The same physical change with no successful transaction must remain a failure.
        rows = [state(0), state(1, hold={'mouth': [{'item': 'minecraft:short_grass', 'n': 1}]})]
        self.assertTrue(any(f['verdict'] == 'fail' for f in checks.ledger(rows, timeline.states(rows))))

    def test_acquisition_cannot_explain_a_different_item_or_a_late_change(self):
        for item, tick in (('minecraft:apple', 1), ('minecraft:short_grass', 4)):
            rows = [state(0), state(tick, hold={'mouth': [{'item': item, 'n': 1}]}),
                    record('ev', 1, cat='meal', msg='received item=minecraft:short_grass count=1 holder=mouth')]
            self.assertTrue(any(f['verdict'] == 'fail' for f in checks.ledger(rows, timeline.states(rows))))

    def test_attachment_geometry_is_not_ownership_flipping(self):
        rows = [state(t, att={'kind': 'on_host', 'host': HOST, 'place': 'cabin',
                              'spot': [0, t % 2, 0], 'frame': {'yaw': t % 2}}) for t in range(20)]
        self.assertFalse(checks.loops(rows, timeline.states(rows)))
        rows = [state(t, att={'kind': 'on_host', 'host': HOST if t % 2 else None, 'place': 'cabin'})
                for t in range(20)]
        self.assertTrue(any(f['verdict'] == 'fail' for f in checks.loops(rows, timeline.states(rows))))

    def test_evidence_index_preserves_original_window_and_order(self):
        rows = [record('win', 0, None), state(1), state(2, p=[3, 0, 0]),
                record('lag', 2, None, ms=25), record('lag', 103, None, ms=25)]
        report = checks.observations(rows)
        for finding in report['findings']:
            expected = [r for r in rows if finding['t'] is not None and abs(r['t'] - finding['t']) <= 100
                        and r['u'] in (finding['actor'], None)]
            self.assertEqual(expected, finding['timeline'])

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
