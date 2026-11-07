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
from cases import record, state, log, HOST, ACTOR


class Calibration(unittest.TestCase):
    def test_reviewed_vantage_size_controls_keep_occlusion_and_offscreen_warnings(self):
        for visible, share, expected in ((9, .0697396, 'pass'), (9, .0337174, 'suspect'),
                                         (0, .13, 'suspect'), (9, 0, 'suspect')):
            rows = [record('geo', 1, player=True, vis=visible, scr=share)]
            report = checks.observations(rows)
            self.assertEqual(expected, next(f['verdict'] for f in report['summary'] if f['check']=='VANTAGE'))

    def test_verified_contact_advances_even_when_bodies_stop_and_push(self):
        # H birth_boat_missed-29: SUMO completes with contact=80 while the
        # bodies intentionally push/stop. Only actual contact increments count.
        rows = [state(t, p=[(t // 2) * .02, 0, 0], contactProgress=t) for t in range(80)]
        self.assertFalse(checks.progress(timeline.states(rows)))
        for row in rows:
            row['st']['contactProgress'] = 0
        self.assertTrue(any(f['verdict'] == 'fail' for f in checks.progress(timeline.states(rows))))

    def test_startup_refusal_with_real_progress_is_not_a_setup_block(self):
        # H: den_family-1/forage-2/carry_home-15 refuse optional leaf wrapping,
        # then walk 1.7–2.4 blocks within the same first 40 ticks.
        base = [record('win', 0, None), record('ev', 1, cat='family',
                    msg='leaf swaddle refused reason=no_reachable_leaf_block', pos=[0,0,0]),
                record('geo', 1, clear=True, supported=True, drop=0),
                record('geo', 20, clear=True, supported=True, drop=0)]
        for moved, expected in ((2, False), (0, True)):
            rows = base + [state(1, goals=['RoamingGoal']), state(40, p=[moved,0,0], goals=['RoamingGoal'])]
            findings = checks.observations(rows, [])['findings']
            self.assertEqual(expected, any(f['check']=='SPAWN' and f['detail']=='early scene refusal' for f in findings))
        rows = base + [state(1, goals=['RoamingGoal'])]
        self.assertTrue(any(f['check']=='SPAWN' and f['verdict']=='no-evidence' for f in checks.observations(rows, [])['findings']))

    def test_post_tick_velocity_explains_real_takeoff_acceleration(self):
        # H: sail-23 t160696, dp=(.26581,.08,.01418), v0=(0,-.0784,0),
        # v1 equals the actual takeoff displacement. Self-rescue's impulse is
        # sampled after ordinary drag, leaving .159 blocks of residual.
        for delta, velocity in (([.26581, .08, .01418], [.26581, .08, .01418]),
                                ([.35, .3, 0], [.1911, .2156, 0])):
            rows = [state(0, v=[0, -.0784, 0]), state(1, p=delta, v=velocity, g=False)]
            self.assertFalse(checks.jumps(timeline.states(rows)))
            rows[1]['st']['p'][0] += 2
            self.assertTrue(any(f['verdict'] == 'fail' for f in checks.jumps(timeline.states(rows))))

    def test_slow_continuous_motion_does_not_create_stops(self):
        # H: forage-2 father 01d69f8d, t12843..12867: continuously advances
        # .005..028 blocks/tick while the old .01 threshold counts six stops.
        rows, x = [], 0
        for t in range(100):
            rows.append(state(t, p=[x, 0, 0], goals=['DailyGoal']))
            x += .02 if t % 4 == 0 else .005
        self.assertFalse(any(f['detail'] == 'dense stutter cycles' for f in checks.progress(timeline.states(rows))))
        rows, x = [], 0
        for t in range(100):
            rows.append(state(t, p=[x, 0, 0], goals=['DailyGoal']))
            x += .02 if t % 4 == 0 else 0
        self.assertTrue(any(f['verdict'] == 'fail' for f in checks.progress(timeline.states(rows))))

    def test_completed_carries_are_not_ownership_churn(self):
        # H: colony_migrate_babies-45, child de4405b8: released at x=-675.46,
        # picked up there, then released at x=-649.28. Birth-land's father
        # also unloads three different newborns, rather than toggling one child.
        rows = [state(t, p=[t * 2, 0, 0], att={'kind': 'held' if t % 2 else 'free',
                      'host': HOST if t % 2 else None, 'place': 'ground'}) for t in range(20)]
        self.assertFalse(checks.loops(rows, timeline.states(rows)))
        rows = [state(t, hold={'carry': [{'child': str(t), 'hold': 'braid'}] if t % 2 else []}) for t in range(20)]
        self.assertFalse(checks.loops(rows, timeline.states(rows)))
        for row in rows:
            if row['st']['hold']['carry']:
                row['st']['hold']['carry'][0]['child'] = HOST
        self.assertTrue(any(f['verdict'] == 'fail' for f in checks.loops(rows, timeline.states(rows))))

    def test_actual_item_expiry_is_distinct_from_unexplained_discard(self):
        rows = [record('st', 0, st={'p': [0, 0, 0], 'item': 'minecraft:lily_pad', 'n': 1}),
                state(0), state(1), record('life', 1, out='discarded', item='minecraft:lily_pad', age=6000, lifespan=6000)]
        rows[1]['u'] = rows[2]['u'] = HOST
        self.assertFalse(checks.ledger(rows, timeline.states(rows)))
        for changed in (dict(age=5999), dict(item='minecraft:apple')):
            broken = rows[:-1] + [rows[-1] | changed]
            self.assertTrue(any(f['verdict'] == 'fail' for f in checks.ledger(broken, timeline.states(broken))))
        broken = rows[:-1] + [record('life', 1, out='discarded')]
        self.assertTrue(any(f['verdict'] == 'fail' for f in checks.ledger(broken, timeline.states(broken))))

    def test_ledger_evidence_names_a_changed_holder(self):
        rows = [state(0), state(1, hold={'mouth': [{'item': 'minecraft:apple', 'n': 1}]}), state(0), state(1)]
        for row in rows[2:]:
            row['u'] = HOST
        rows.sort(key=lambda r: r['t'])
        finding = next(f for f in checks.ledger(rows, timeline.states(rows)) if f['verdict'] == 'fail')
        self.assertEqual(finding['actor'], ACTOR)
        self.assertEqual(finding['items'], {'minecraft:apple': 1})

    def test_carrier_turn_is_not_child_displacement(self):
        rows = [state(0, by=180), state(1, by=90),
                state(0, p=[1, 1, 0], att={'kind': 'held', 'host': HOST}),
                state(1, p=[0, 1, -1], att={'kind': 'held', 'host': HOST})]
        for row in rows[:2]:
            row['u'] = HOST
        rows.sort(key=lambda r: r['t'])
        self.assertFalse(checks.jumps(timeline.states(rows)))
        next(r for r in rows if r['u'] == ACTOR and r['t'] == 1)['st']['p'] = [0, 1, -3]
        self.assertTrue(any(f['verdict'] == 'fail' for f in checks.jumps(timeline.states(rows))))

    def test_observed_idle_and_look_goals_are_not_stalled_jobs(self):
        for fields in (dict(behavior='idle', goals=['PitSlaveGoal@4']), dict(goals=['RandomLookAroundGoal@9'])):
            rows = [state(t, int=None, **fields) for t in range(1202)]
            self.assertFalse(checks.progress(timeline.states(rows)))
        rows = [state(t, behavior='idle', int={'id': 'travel'}) for t in range(1202)]
        self.assertTrue(any(f['verdict'] == 'fail' for f in checks.progress(timeline.states(rows))))

    def test_bounded_flight_timer_and_normal_airborne_contacts(self):
        rows = [state(t, act={'id': 'launch', 'owner': 'childhood_practice', 'metric': 30 - t},
                      att={'kind': 'airborne' if t % 2 else 'free', 'host': None, 'place': 'ground'},
                      p=[0, .02 * ((t // 2) % 2), 0]) for t in range(30)]
        self.assertFalse(checks.progress(timeline.states(rows)))
        self.assertFalse(checks.loops(rows, timeline.states(rows)))
        for row in rows:
            row['st']['act']['metric'] = 30
        self.assertTrue(any(f['verdict'] == 'fail' for f in checks.progress(timeline.states(rows))))

    def test_counted_store_transfer_explains_only_recorded_kinds(self):
        for msg in ('stores taken by=fixture use=feed kind=minecraft:apple count=4 -> 3',
                    'stores spilled by=fixture kinds={minecraft:apple=4} -> {} total=4 -> 0'):
            rows = [state(0), state(1, hold={'mouth': [{'item': 'minecraft:apple', 'n': 1}]}),
                    record('ev', 1, cat='hat', msg=msg)]
            self.assertFalse(checks.ledger(rows, timeline.states(rows)))
            rows[1]['st']['hold']['mouth'][0]['item'] = 'minecraft:stick'
            self.assertTrue(any(f['verdict'] == 'fail' for f in checks.ledger(rows, timeline.states(rows))))

    def test_status_and_diagnostic_fields_are_not_refusal_attempts(self):
        for category, message in (
                ('aquatic', 'drive evaluated current=none failed=[]'),
                ('aquatic', 'drive evaluated current=none failed=[take_seat]'),
                ('family', 'carry put down rejected=null'),
                ('status', 'behavior.protection: decision=waiting -> decision=unavailable'),
                ('status', 'body.last: outcome=BLOCKED -> outcome=BLOCKED')):
            self.assertFalse(checks.is_refusal(category, message))
            raw = f'[{category}] reimu #1 uuid={ACTOR} t=1 dim=minecraft:overworld pos=0, 0, 0 {message} (suppressed=499)'
            self.assertFalse(checks.fates(raw))
        raw = f'[approach] reimu #1 uuid={ACTOR} t=1 dim=minecraft:overworld pos=0, 0, 0 contact stand unavailable (suppressed=399)'
        self.assertTrue(checks.fates(raw))

    def test_recorded_step_capability_and_overshoot(self):
        rows = [state(0, step=1, v=[0, -.0784, 0]), state(1, step=1, p=[0, 1, 0])]
        self.assertFalse(checks.jumps(timeline.states(rows)))
        rows[1]['st']['p'][1] = 2.5
        self.assertTrue(any(f['verdict'] == 'fail' for f in checks.jumps(timeline.states(rows))))

    def test_retained_velocity_while_held_still_is_not_a_position_jump(self):
        # birth_stem_caught-39 t303143: take_prop holds position while
        # the post-tick velocity remains (.266, -.0784, -.160).
        rows = [state(0, v=[.232, -.0784, -.151]), state(1, v=[.266, -.0784, -.160])]
        self.assertFalse(checks.jumps(timeline.states(rows)))
        rows[1]['st']['p'] = [0, 0, 3]
        self.assertTrue(any(f['verdict'] == 'fail' for f in checks.jumps(timeline.states(rows))))
        rows = [state(0, step=1, g=False), state(1, step=1, g=False, p=[0, 1.1, 0])]
        self.assertTrue(any(f['verdict'] == 'fail' for f in checks.jumps(timeline.states(rows))))

    def test_explicit_setup_placement_does_not_exempt_other_jumps(self):
        rows = [state(0), state(1, p=[3, 0, 0]), state(2, p=[6, 0, 0])]
        placement = record('ev', 1, cat='body', msg='relocate cause=scenario_initial_seat from=(0.00,0.00,0.00) to=(3.00,0.00,0.00)')
        findings = checks.jumps(timeline.states(rows), [placement])
        self.assertEqual([(f['t'], f['verdict']) for f in findings], [(2, 'fail')])
        for changed in (dict(u=HOST), dict(t=2), dict(msg=placement['msg'].replace('3.00', '4.00')),
                        dict(msg=placement['msg'].replace('scenario_initial_seat', 'agitation_pin'))):
            self.assertTrue(any(f['t'] == 1 for f in checks.jumps(timeline.states(rows), [placement | changed])))

    def test_observed_wait_counter_must_actually_advance(self):
        rows = [state(t, goals=['BirthGoal@2'], phase='term', waitElapsed=t) for t in range(1202)]
        self.assertFalse(checks.progress(timeline.states(rows)))
        # R rain_huddle: the leader first walks, then starts the bounded huddle
        # clock. An earlier null anchor must not hide subsequent clock progress.
        rows = [state(0, goals=['WeatherGoal'], waitElapsed=None)] + [
            state(t, goals=['WeatherGoal'], waitElapsed=t) for t in range(1, 1202)]
        self.assertFalse(checks.progress(timeline.states(rows)))
        rows = [state(t, goals=['BirthGoal@2'], phase='term', waitElapsed=0) for t in range(1202)]
        self.assertTrue(any(f['verdict'] == 'fail' for f in checks.progress(timeline.states(rows))))
        _, issues = timeline.parse([log([state(0, waitElapsed='bad counter')])])
        self.assertTrue(issues)

    def test_declared_death_does_not_duplicate_as_unexpected_terminal_damage(self):
        death = 'death settled into remains; cause=genericKill'
        damage = 'accepted damage cause=genericKill health=10.0 -> 0.0 loss=10.0'
        rows = [record('ev', 1, cat='body', msg=msg) for msg in (death, damage)]
        raw = '\n'.join(f'[body] reimu #1 uuid={ACTOR} t=1 dim=minecraft:overworld pos=0, 0, 0 {msg}'
                        for msg in (death, damage))
        allow = [{'kind': 'death', 'who': ACTOR, 'match': 'cause=genericKill', 'why': 'declared smoke widowhood'}]
        report = checks.observations(rows, raw=raw, allow=allow)
        self.assertEqual(next(f['verdict'] for f in report['summary'] if f['check'] == 'FATE'), 'pass')
        report = checks.observations(rows, raw=raw)
        self.assertEqual(next(f['verdict'] for f in report['summary'] if f['check'] == 'FATE'), 'fail')
        # The allowance is for death, not an arbitrary hit without a matching death event.
        report = checks.observations(rows[1:], raw=raw.splitlines()[1], allow=allow)
        self.assertEqual(next(f['verdict'] for f in report['summary'] if f['check'] == 'FATE'), 'suspect')

    def test_lifecycle_before_inventory_baseline_and_missing_snapshots(self):
        rows = [record('life', 0, **{'in': 'spawn'}), state(1), state(2)]
        self.assertFalse(checks.ledger(rows, timeline.states(rows)))
        rows = [record('life', t, **{'in': 'spawn'}) for t in range(3)]
        self.assertTrue(any(f['verdict'] == 'no-evidence' for f in checks.ledger(rows, [])))

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

    def test_separate_completed_jobs_do_not_accumulate_stutter(self):
        rows = [state(t, p=[(t // 2) * .1, 0, 0], phase=str(t // 4)) for t in range(40)]
        self.assertFalse(checks.progress(timeline.states(rows)))

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
