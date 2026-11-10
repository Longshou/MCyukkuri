"""END grammar controls and deliberate breaks; no gameplay simulation or other-check calibration."""
import copy
import re
from pathlib import Path
import sys
import unittest

sys.path.insert(0, str(Path(__file__).resolve().parents[2]))
import smoke_checks as checks
import timeline
from cases import ACTOR, HOST, record, state, log

CHILD = '00000000-0000-0000-0000-000000000003'
OTHER = '00000000-0000-0000-0000-000000000004'
HAT = '00000000-0000-0000-0000-000000000005'


def actor(t, uid, name, **values):
    row = state(t, tags=['scn_' + name], **values)
    row['u'] = uid
    return row


def event(t, uid, cat, msg):
    return record('ev', t, uid, cat=cat, msg=msg)


def evaluate(rows, end):
    records, issues = timeline.parse([log(rows)])
    assert not issues, issues
    return checks.endings(records, timeline.states(records), end)


def verdict(rows, end):
    return max((r['verdict'] for r in evaluate(rows, end)), key=checks.RANK.get)


class EndChecks(unittest.TestCase):
    def setUp(self):
        self.rows = [actor(t, ACTOR, 'parent') for t in (0, 200)]
        self.rows += [actor(t, CHILD, 'child', parents={'mother': ACTOR}, stage='baby') for t in (0, 200)]
        self.events = [event(20, ACTOR, 'family', 'carry pickup hold=brim child=' + CHILD),
                       event(30, ACTOR, 'family', 'carry set down started reason=arrived_home child=' + CHILD),
                       event(40, ACTOR, 'family', 'carry put down reason=arrived_home cause=release child=' + CHILD + ' nest=INSIDE_SUPPORTED')]
        self.end = dict(final=[('child_of:parent', 'alive=true and w=false')], paths=[dict(
            each='child_of:parent', steps=[('parent', 'seen:family:carry pickup .*child=$subject'),
                                         ('parent', 'seen:family:carry set down started .*child=$subject'),
                                         ('parent', 'seen:family:carry put down .*child=$subject .*nest=INSIDE_SUPPORTED')],
            post=[('$subject', 'att.kind=free and nest_of.parent=true')])])
        # Actual recorder uses free, not the older fixture's ground kind.
        for row in self.rows:
            row['st']['att']['kind'] = 'free'

    def test_carry_complete_and_stopped_at_every_handoff(self):
        self.assertEqual(verdict(self.rows + self.events, self.end), 'pass')
        for count in range(3):
            self.assertEqual(verdict(self.rows + self.events[:count], self.end), 'fail')

    def test_reversed_events_and_different_child_cannot_complete_path(self):
        wrong = copy.deepcopy(self.events)
        wrong[0]['t'], wrong[2]['t'] = 40, 20
        self.assertEqual(verdict(self.rows + wrong, self.end), 'fail')
        wrong = copy.deepcopy(self.events)
        wrong[-1]['msg'] = wrong[-1]['msg'].replace(CHILD, OTHER)
        self.assertEqual(verdict(self.rows + wrong, self.end), 'fail')

    def test_emitter_is_not_the_carried_subject_and_nest_is_real_occupancy(self):
        wrong = copy.deepcopy(self.events)
        wrong[-1]['u'] = CHILD
        self.assertEqual(verdict(self.rows + wrong, self.end), 'fail')
        wrong = copy.deepcopy(self.events)
        wrong[-1]['msg'] = wrong[-1]['msg'].replace('INSIDE_SUPPORTED', 'OUTSIDE')
        self.assertEqual(verdict(self.rows + wrong, self.end), 'fail')

    def test_each_child_requires_its_own_whole_chain(self):
        rows = self.rows + [actor(t, OTHER, 'sibling', parents={'mother': ACTOR}) for t in (0, 200)]
        self.assertEqual(verdict(rows + self.events, self.end), 'fail')

    def test_newborn_excludes_existing_sibling_and_requires_birth_evidence(self):
        rows = self.rows + [actor(t, OTHER, 'newborn', parents={'mother': ACTOR}) for t in (50, 200)]
        rows += [record('life', 49, OTHER, **{'in': 'birth', 'parent': ACTOR}),
                 event(60, OTHER, 'meal', 'swallowed prepared=true')]
        end = dict(paths=[dict(each='newborn_of:parent', steps=[('$subject', 'seen:meal:swallowed prepared=true')])])
        self.assertEqual(verdict(rows, end), 'pass')
        self.assertEqual(verdict([r for r in rows if r['k'] != 'life'], end), 'fail')

    def test_every_caught_child_requires_supported_release(self):
        rows = self.rows + [actor(t, OTHER, 'sibling', parents={'mother': ACTOR},
                                 att={'kind': 'free'}) for t in (0, 200)]
        births = [event(10, child, 'family', 'birth landed outcome=HAT') for child in (CHILD, OTHER)]
        end = dict(paths=[dict(each='caught_child_of:parent', steps=[
            ('$subject', 'seen:family:birth landed .*outcome=HAT'),
            ('parent', r'seen:family:carry put down .*cause=release child=$subject\b')],
            post=[('$subject', 'att.kind=free and g=true')])])
        drops = [event(40, ACTOR, 'family', 'carry put down cause=release child=' + child)
                 for child in (CHILD, OTHER)]
        self.assertEqual(verdict(rows + births + drops, end), 'pass')
        self.assertEqual(verdict(rows + births + drops[:1], end), 'fail')
        self.assertEqual(verdict(rows + drops, end), 'fail')
        broken = copy.deepcopy(drops)
        broken[1]['msg'] = broken[1]['msg'].replace('cause=release', 'cause=fetch_ended')
        self.assertEqual(verdict(rows + births + broken, end), 'fail')
        airborne = copy.deepcopy(rows)
        for r in airborne:
            if r['u'] == OTHER:
                r['st']['g'] = False
        self.assertEqual(verdict(airborne + births + drops, end), 'fail')

    def test_caught_birth_rejects_missing_forced_and_late_release(self):
        import museum_scenarios as scenes
        def line(cat, who, msg):
            return f'[{cat}] marisa #1 uuid={who} t=10 {msg}\n'
        births = ''.join(line('family', child, 'birth landed outcome=HAT') for child in (CHILD, OTHER))
        drops = [line('family', ACTOR, 'carry put down cause=release child=' + child)
                 for child in (CHILD, OTHER)]
        worn = line('headwear', ACTOR, 'custody DEPLOYED -> WORN')
        verify = scenes._caught_birth_release_verify
        self.assertIsNone(verify(births + ''.join(drops) + worn))
        for broken in (births + drops[0] + worn,
                       births + ''.join(drops).replace('cause=release', 'cause=fetch_ended', 1) + worn,
                       births + drops[0] + worn + drops[1] + worn,
                       births + ''.join(drops), ''.join(drops) + worn):
            self.assertIsNotNone(verify(broken))

    def test_world_events_bound_to_same_group_and_scene_leader(self):
        rows = self.rows + [event(10, None, 'settlement', 'migration phase=MARCH group=group-a'),
                            event(20, None, 'settlement', 'migration pivot arrived group=group-a leader=' + ACTOR)]
        end = dict(paths=[dict(steps=[('world', r'seen:settlement:migration phase=MARCH group=(?P<group>\S+)'),
                                     ('world', r'seen:settlement:migration pivot arrived group=$group leader=$parent')])])
        self.assertEqual(verdict(rows, end), 'pass')
        for old, new in [('group-a leader', 'group-b leader'), (ACTOR, OTHER)]:
            wrong = copy.deepcopy(rows)
            wrong[-1]['msg'] = wrong[-1]['msg'].replace(old, new)
            self.assertEqual(verdict(wrong, end), 'fail')

    def test_backtracks_over_incomplete_earlier_attempt(self):
        rows = self.rows + [event(10, ACTOR, 'incident', 'open incident=old'),
                            event(20, ACTOR, 'incident', 'open incident=new'),
                            event(30, ACTOR, 'incident', 'done incident=new')]
        end = dict(paths=[dict(steps=[('parent', r'seen:incident:open incident=(?P<incident>\S+)'),
                                     ('parent', r'seen:incident:done incident=$incident')])])
        self.assertEqual(verdict(rows, end), 'pass')

    def test_completion_then_legitimate_relaunch_and_later_unsafe_young(self):
        end = dict(final=[('child', 'alive=true and distress=false')], paths=[dict(
            steps=[('parent', 'seen:headwear:reclaim done')], post=[('parent', 'veh=false and hold.hat.state=worn')])])
        rows = self.rows + [actor(40, ACTOR, 'parent', veh=None, hold={'hat': {'state': 'worn'}}),
                            event(40, ACTOR, 'headwear', 'reclaim done'),
                            actor(60, ACTOR, 'parent', veh=HAT)]
        self.assertEqual(verdict(rows, end), 'pass')
        rows += [actor(200, CHILD, 'child', distress=True)]
        self.assertEqual(verdict(rows, end), 'fail')

    def test_holder_and_location_relations(self):
        end = dict(final=[('child', 'near.parent<=4 and home_of.parent=true and carry_empty=true')])
        self.assertEqual(verdict(self.rows, end), 'pass')
        for values in (dict(p=[10, 0, 0]), dict(home=[10, 0, 0]), dict(hold={'carry': [{'child': OTHER}]})):
            rows = self.rows + [actor(t, CHILD, 'child', **values) for t in (1, 200)]
            self.assertEqual(verdict(rows, end), 'fail')

    def test_owned_hat_and_unrelated_hat_forbidden_event(self):
        rows = self.rows + [event(10, HAT, 'hat', f'placed owner={ACTOR} hat={HAT}'),
                            event(20, OTHER, 'hat', 'capsized')]
        end = dict(final=[('parent', 'alive=true')], forbid=[('hat_of:parent', 'seen:hat:capsized')])
        self.assertEqual(verdict(rows, end), 'pass')
        rows += [event(30, HAT, 'hat', 'capsized')]
        self.assertEqual(verdict(rows, end), 'fail')

    def test_known_missing_unloaded_and_dead_subjects(self):
        for reason in ('unloaded', 'killed'):
            end = dict(final=[('child', 'alive=true')])
            self.assertEqual(verdict(self.rows + [record('life', 201, CHILD, out=reason)], end), 'fail')
        self.assertEqual(verdict(self.rows, dict(final=[('missing', 'alive=true')])), 'fail')
        self.assertEqual(verdict([], self.end), 'no-evidence')
        rows = [r for r in self.rows if not (r['u'] == CHILD and r['t'] == 200)] + [record('life', 100, CHILD, out='killed')]
        self.assertEqual(verdict(rows, dict(final=[('child', 'alive=false')])), 'pass')

    def test_unknown_relation_is_not_certified(self):
        rows = self.rows + [actor(t, CHILD, 'child', home=None) for t in (1, 200)]
        self.assertEqual(verdict(rows, dict(final=[('child', 'home_of.parent=true')])), 'no-evidence')

    def test_legacy_event_is_bounded_and_keeps_same_tick_order(self):
        rows, issues = timeline.parse([log(self.rows + [event(20, ACTOR, 'family', 'answer')])])
        end = dict(bounds=[-2, -2, 2, 2], paths=[dict(steps=[('actors', 'seen:family:call'), ('parent', 'seen:family:answer')])])
        prefix = f'[family] reimu #3 uuid={OTHER} t=20 dim=minecraft:overworld pos=0, 0, 0 '
        answer = f'[family] marisa #1 uuid={ACTOR} t=20 dim=minecraft:overworld pos=0, 0, 0 answer'
        def check(raw):
            return max((r['verdict'] for r in checks.endings(rows, timeline.states(rows), end, raw)), key=checks.RANK.get)
        self.assertEqual(check(prefix + 'call\n' + answer), 'pass')
        self.assertEqual(check(answer + '\n' + prefix + 'call'), 'fail')
        self.assertEqual(check(prefix.replace('pos=0, 0, 0', 'pos=30, 0, 0') + 'call\n' + answer), 'fail')
        self.assertEqual(check(prefix.replace('overworld', 'the_nether') + 'call\n' + answer), 'fail')
        self.assertEqual(check(prefix.replace('t=20 ', 't=201 ') + 'call\n' + answer), 'fail')

    def test_head_carried_child_aboard_the_correct_owned_hat(self):
        rows = [actor(t, ACTOR, 'parent', att={'kind': 'on_host', 'host': HAT}) for t in (0, 200)]
        rows += [actor(t, CHILD, 'child', att={'kind': 'held', 'host': ACTOR}) for t in (0, 200)]
        rows += [event(0, HAT, 'hat', f'placed owner={ACTOR} hat={HAT}')]
        end = dict(final=[('child', 'aboard.parent=true')])
        self.assertEqual(verdict(rows, end), 'pass')
        rows += [actor(t, ACTOR, 'parent', att={'kind': 'free', 'host': None}) for t in (1, 200)]
        self.assertEqual(verdict(rows, end), 'fail')

    def test_ground_contact_on_tick_after_release(self):
        rows = self.rows + [actor(40, CHILD, 'child', g=False, att={'kind': 'free'}),
                            actor(41, CHILD, 'child', g=True, att={'kind': 'free'})]
        end = copy.deepcopy(self.end)
        end['paths'][0]['post'] = [('$subject', 'g=true and att.kind=free')]
        self.assertEqual(verdict(rows + self.events, end), 'pass')
        self.assertEqual(verdict(rows[:-1] + self.events, end), 'fail')

    def test_scene_pickup_refusal_is_not_a_successful_lift(self):
        import museum_scenarios
        scene = next(s for s in museum_scenarios.S if s.id == 'carry_home')
        end = dict(paths=[scene.smoke['end']['paths'][0]])
        rows = [actor(t, ACTOR, 'marisa_parent') for t in (0, 200)]
        rows += [actor(t, CHILD, 'marisa_baby', att={'kind': 'free'}) for t in (0, 200)]
        rows += [actor(41, CHILD, 'marisa_baby', att={'kind': 'free'}, nest='INSIDE_SUPPORTED')]
        rows += copy.deepcopy(self.events)
        rows[-3]['msg'] = 'carry pickup stand blocked child=' + CHILD + ' why=no_complete_surface_route'
        self.assertEqual(verdict(rows, end), 'fail')
        rows[-3]['msg'] = 'carry pickup hold=brim purpose=RETRIEVE child=' + CHILD
        self.assertEqual(verdict(rows, end), 'pass')

    def carry_home_case(self, carried=2, autonomous=True):
        import museum_scenarios
        scene = next(s for s in museum_scenarios.S if s.id == 'carry_home')
        path = copy.deepcopy(scene.smoke['end']['paths'][0])
        end = dict(paths=[path], carried_min=2,
                   final=[('marisa_baby', 'alive=true and g=true and w=false and att.kind=free')])
        rows = [actor(t, ACTOR, 'marisa_parent') for t in (0, 200)]
        for index, child in enumerate((CHILD, OTHER, HAT)):
            rows += [actor(t, child, 'marisa_baby', stage='baby', nest='INSIDE_SUPPORTED' if t else 'OUTSIDE',
                           phase='STAY_HOME' if autonomous and index >= carried else 'NONE',
                           att={'kind': 'free'}) for t in (0, 41, 60, 200)]
            if index < carried:
                rows += [dict(e, msg=e['msg'].replace(CHILD, child)) for e in self.events]
        return rows, end

    def test_carry_home_autonomous_pass_and_missing_return_failure(self):
        rows, end = self.carry_home_case()
        self.assertEqual(verdict(rows, end), 'pass')
        broken = copy.deepcopy(rows)
        for r in broken:
            if r['k'] == 'st' and r['u'] == HAT:
                r['st']['nest'] = 'OUTSIDE'
        self.assertEqual(verdict(broken, end), 'fail')
        rows, end = self.carry_home_case(autonomous=False)
        self.assertEqual(verdict(rows, end), 'fail')

    def test_carry_home_outside_then_walk_in_still_fails(self):
        rows, end = self.carry_home_case()
        rows += [event(10, ACTOR, 'family', 'carry pickup hold=brim child=' + HAT),
                 event(40, ACTOR, 'family', 'carry put down reason=release cause=release child=' + HAT + ' nest=OUTSIDE')]
        self.assertEqual(verdict(rows, end), 'fail')

    def test_carry_home_reaches_then_roams_out_passes(self):
        rows, end = self.carry_home_case()
        # Both carried children and the autonomous sibling may leave after their own witnessed return.
        for r in rows:
            if r['k'] == 'st' and r['u'] in (CHILD, OTHER, HAT) and r['t'] == 200:
                r['st']['nest'] = 'OUTSIDE'
                r['st']['phase'] = 'EXPLORE'
        self.assertEqual(verdict(rows, end), 'pass')
        for r in rows:
            if r['k'] == 'st' and r['u'] == HAT and r['t'] in (41, 60):
                r['st']['nest'] = 'OUTSIDE'
        self.assertEqual(verdict(rows, end), 'fail')

    def test_carry_home_release_log_without_supported_occupancy_fails(self):
        rows, end = self.carry_home_case()
        for r in rows:
            if r['k'] == 'st' and r['u'] == CHILD:
                r['st']['nest'] = 'PARTIAL'
        self.assertEqual(verdict(rows, end), 'fail')

    def test_carry_home_physical_nest_release_not_reason_name(self):
        rows, end = self.carry_home_case()
        for r in rows:
            if r['k'] == 'ev' and r['msg'].startswith('carry set down started'):
                child = r['msg'].split('child=')[1]
                r['msg'] = f'carry release target child={child} placement=nest'
            if r['k'] == 'ev' and r['msg'].startswith('carry put down'):
                r['msg'] = r['msg'].replace('reason=arrived_home', 'reason=nest_rim_to_cushion')
        self.assertEqual(verdict(rows, end), 'pass')
        for r in rows:
            if r['k'] == 'ev':
                r['msg'] = r['msg'].replace('cause=release', 'cause=carry_child_hurt')
        self.assertEqual(verdict(rows, end), 'fail')

    def test_carry_home_nest_before_stay_home_is_not_return(self):
        rows, end = self.carry_home_case()
        for r in rows:
            if r['k'] == 'st' and r['u'] == HAT:
                r['st']['phase'] = 'STAY_HOME' if r['t'] == 200 else 'EXPLORE'
                r['st']['nest'] = 'INSIDE_SUPPORTED' if r['t'] == 60 else 'OUTSIDE'
        self.assertEqual(verdict(rows, end), 'fail')

    def test_carry_home_requires_two_distinct_carried_children(self):
        rows, end = self.carry_home_case(carried=1)
        self.assertEqual(verdict(rows, end), 'fail')
        rows += [dict(e, t=e['t'] + 50) for e in self.events]
        self.assertEqual(verdict(rows, end), 'fail')
        rows, end = self.carry_home_case(carried=2)
        self.assertEqual(verdict(rows, end), 'pass')

    def test_legacy_carry_minimum_and_outside_rejection(self):
        import museum_scenarios as scenes
        children = [scenes._uuid_text('carry_home', 'marisa_' + str(i)) for i in range(2)]
        def chain(child, nest='INSIDE_SUPPORTED'):
            return ('carry pickup hold=brim purpose=RETRIEVE child=' + child + '\n'
                    'carry put down reason=arrived_home hold=brim cause=release child=' + child + ' nest=' + nest + '\n')
        self.assertIsNone(scenes._carry_chains_verify(''.join(map(chain, children))))
        self.assertIsNotNone(scenes._carry_chains_verify(chain(children[0]) * 2))
        self.assertIsNotNone(scenes._carry_chains_verify(chain(children[0], 'OUTSIDE') + ''.join(map(chain, children))))

    def test_legacy_carry_home_does_not_require_each_species_to_be_carried(self):
        import museum_scenarios as scenes
        smoke = next(s.smoke for s in scenes.S if s.id == 'carry_home')
        children = [scenes._uuid_text('carry_home', 'chen_' + str(i)) for i in range(2)]
        prefix = '[family] chen #1 uuid=' + ACTOR + ' t=40 dim=minecraft:overworld '
        def chain(child):
            return '\n'.join(prefix + msg for msg in (
                'carry lift started child=' + child,
                'carry pickup hold=tail_left purpose=RETRIEVE child=' + child,
                'carry put down reason=nest_rim_to_cushion hold=tail_left cause=release child=' + child + ' nest=INSIDE_SUPPORTED')) + '\n'
        text = ''.join(map(chain, children)) + 'MCYSMOKE_END_TICK 100\n'
        self.assertTrue(all(re.search(pattern, text) for pattern in smoke['expect']))
        self.assertIsNone(smoke['verify'](text))
        self.assertIsNotNone(smoke['verify'](text.replace('nest=INSIDE_SUPPORTED', 'nest=OUTSIDE', 1)))
        self.assertIsNotNone(smoke['verify'](chain(children[0]) + 'MCYSMOKE_END_TICK 100\n'))
        self.assertIsNotNone(smoke['verify'](text.replace('cause=release', 'cause=fetch_ended', 1)))
        self.assertIsNotNone(smoke['verify'](text + '[family] marisa #2 carry pickup hold=brim child=' + HAT))
        self.assertIsNotNone(scenes._reimu_release_verify('[family] reimu #3 carry pickup hold=mouth child=' + HAT))


if __name__ == '__main__':
    unittest.main(verbosity=2)
