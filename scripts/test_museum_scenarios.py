"""Deliberate-break log fixtures for museum scenario verifiers; no game."""
import unittest

from museum_scenarios import _held_pressure, _reimu_release_verify, _repeat_heat, _uuid_text, elopement_result


PARENT = '11111111-1111-1111-1111-111111111111'
OTHER_PARENT = '22222222-2222-2222-2222-222222222222'
CHILD = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
OTHER_CHILD = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'
PICKUP = f'[family] reimu #1 uuid={PARENT} t=1 carry pickup hold=mouth purpose=RETRIEVE child={CHILD}\n'


def event(tick, message, parent=PARENT):
    return f'[family] reimu #1 uuid={parent} t={tick} dim=minecraft:overworld pos=0,64,0 carry {message}\n'


def lower(tick, child=CHILD, parent=PARENT):
    return event(tick, f'lowering hold=mouth child={child} from=(0,1,0) ticks=26 clip=', parent)


def release(tick, child=CHILD, parent=PARENT):
    return event(tick, f'put down reason=arrived_home hold=mouth cause=release purpose=RETRIEVE child={child} at=(0,0,0)', parent)


class ReimuReleaseTransactions(unittest.TestCase):
    def test_full_32_tick_clip_is_accepted(self):
        self.assertIsNone(_reimu_release_verify(PICKUP + lower(100) + release(132)))

    def test_deliberate_31_tick_release_is_rejected(self):
        self.assertIn('31', _reimu_release_verify(PICKUP + lower(100) + release(131)))

    def test_deliberate_immediate_release_is_rejected(self):
        self.assertIn('before her clip ended', _reimu_release_verify(PICKUP + lower(100) + release(100)))

    def test_two_completed_transactions_for_same_child(self):
        self.assertIsNone(_reimu_release_verify(PICKUP + lower(100) + release(132) + lower(1000) + release(1040)))

    def test_deliberate_early_first_release_cannot_be_hidden_by_later_good_release(self):
        result = _reimu_release_verify(PICKUP + lower(100) + release(131) + lower(1000) + release(1032))
        self.assertIn('31', result)
        self.assertIn('100, 131', result)

    def test_deliberate_early_second_release_is_rejected(self):
        result = _reimu_release_verify(PICKUP + lower(100) + release(132) + lower(1000) + release(1031))
        self.assertIn('1000, 1031', result)

    def test_carry_home_18_completed_release_then_window_end_lowering(self):
        self.assertIsNone(_reimu_release_verify(PICKUP + lower(106692) + release(106724) + lower(109944)))

    def test_interleaved_children_keep_their_own_transaction(self):
        log = PICKUP + lower(100) + lower(110, OTHER_CHILD) + release(142, OTHER_CHILD) + release(144)
        self.assertIsNone(_reimu_release_verify(log))

    def test_deliberate_cross_parent_pair_is_rejected(self):
        result = _reimu_release_verify(PICKUP + lower(100) + release(132, parent=OTHER_PARENT))
        self.assertIn('without its own lowering', result)

    def test_deliberate_cross_child_pair_is_rejected(self):
        result = _reimu_release_verify(PICKUP + lower(100) + release(132, OTHER_CHILD))
        self.assertIn('without its own lowering', result)

    def test_only_unfinished_lowering_does_not_prove_release(self):
        self.assertIn('expected a Reimu mouth release', _reimu_release_verify(PICKUP + lower(100)))

    def test_restart_after_interruption_uses_the_restarted_clip(self):
        self.assertIsNone(_reimu_release_verify(PICKUP + lower(10) + lower(100) + release(132)))

    def test_non_reimu_scene_is_outside_this_check(self):
        self.assertIsNone(_reimu_release_verify(''))


class PressureEpisodes(unittest.TestCase):
    actor = _uuid_text('pentup_heat', 'restless')

    def heat(self, tick, phase, pressure):
        return f'[agitation] reimu #1 uuid={self.actor} t={tick} dim=overworld pos=0 phase={phase} energy=80 pentUp={pressure}\n'

    def test_only_the_same_adult_reopens_after_a_full_unmet_period_and_cooldown(self):
        first = self.heat(200, 'IDLE -> HEAT', 55.45)
        unmet = self.heat(1400, 'HEAT -> HEAT_UNMET', 58)
        later = self.heat(25600, 'IDLE -> HEAT', 92)
        self.assertIsNone(_repeat_heat(first + unmet + later))
        for broken in (first + later, first + unmet, first + unmet + later.replace(self.actor, 'other'),
                       first + unmet + later.replace('25600', '25399'), first + unmet.replace('1400', '1000') + later,
                       first + unmet + later.replace('92', '46'), first.replace('55.45', '55.1') + unmet + later):
            self.assertIsNotNone(_repeat_heat(broken))

    def test_an_actual_hold_must_raise_pressure(self):
        held = 'pressure reason=HOLD pentUp=80.25 -> 82.25\n'
        self.assertIsNone(_held_pressure(held))
        self.assertIsNotNone(_held_pressure(held.replace('HOLD', 'WAIT')))
        self.assertIsNotNone(_held_pressure(held + held.replace('82.25', '80.25')))


class ElopementHistoryTests(unittest.TestCase):
    pet, wild = (_uuid_text('elope', role) for role in ('pet', 'wild'))

    def visit(self, tick, returns):
        p, w = self.pet, self.wild
        lines = [
            f'[rules] uuid={w} t={tick} dim=x tryst call pet={p} at=(10, 0, 0) yard=(0, 0, 0) spoke=true',
            f'[rules] uuid={p} t={tick+50} dim=x breach rule=no_leaving_yard watched=false',
            f'[rules] uuid={p} t={tick+70} dim=x tryst met partner={w} petAt=(9, 0, 0) wildAt=(10, 0, 0)',
        ]
        if returns:
            lines += [f'[rules] uuid={p} t={tick+240} dim=x tryst returned partner={w} visits={returns} bond=42 petAt=(2, 0, 0) yard=(0, 0, 0)',
                      f'[rules] uuid={p} t={tick+240} dim=x departure ended reason=returned partner={w}']
        return '\n'.join(lines) + '\n'

    def departure(self):
        p, w = self.pet, self.wild
        return '\n'.join([
            f'[badge] uuid={p} t=2 dim=x issued grade=NONE -> BRONZE serial=token-12',
            f'[rules] uuid={p} t=8999 dim=x departure started partner={w} from=(9, 0, 0) to=(26, 0, 0) yard=(0, 0, 0)',
            f'[rules] uuid={p} t=9500 dim=x departure ended reason=arrived partner={w} petAt=(26, 0, 0) wildAt=(28, 0, 0)',
            f'[custody] uuid={p} t=9500 dim=x changed from=pet to=wild_family cause=eloped',
            f'[badge] uuid={p} t=9500 dim=x disqualified cause=eloped',
            f'[rules] uuid={p} t=9500 dim=x eloped partner={w} wildAt=(28, 0, 0) identity=wild_family token=BRONZE serial=token-12'])

    def test_two_returns_and_another_meeting_precede_arrival(self):
        history = self.visit(100, 1) + self.visit(4000, 2) + self.visit(8000, 0)
        self.assertIsNone(elopement_result(history + self.departure()))
        for broken in [self.departure(), history.replace('no_leaving_yard', 'no_singing') + self.departure(),
                       history.replace('petAt=(9, 0, 0)', 'petAt=(7, 0, 0)') + self.departure(),
                       history.replace('petAt=(2, 0, 0)', 'petAt=(9, 0, 0)') + self.departure(),
                       history.replace('visits=2', 'visits=1') + self.departure(),
                       self.visit(100, 1) + self.visit(500, 2) + self.visit(8000, 0) + self.departure()]:
            self.assertIsNotNone(elopement_result(broken))


if __name__ == '__main__':
    unittest.main(verbosity=2)
