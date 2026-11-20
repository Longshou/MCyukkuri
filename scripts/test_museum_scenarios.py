"""Deliberate-break log fixtures for museum scenario verifiers; no game."""
import json
import re
import unittest

from museum_scenarios import BY_ID, FEET, S, _bank_pickup_retries, _held_pressure, _reimu_release_verify, _repeat_heat, _uuid_text, bank_dx, current_drift, elopement_result, sel, uid, uuid_nbt
from smoke_fate import fates


PARENT = '11111111-1111-1111-1111-111111111111'
OTHER_PARENT = '22222222-2222-2222-2222-222222222222'
CHILD = 'aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa'
OTHER_CHILD = 'bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb'
PICKUP = f'[family] reimu #1 uuid={PARENT} t=1 carry pickup hold=mouth purpose=RETRIEVE child={CHILD}\n'


class PondShakeStart(unittest.TestCase):
    def test_wet_reimu_starts_in_the_last_flowing_cell_next_to_a_dry_bed(self):
        scene = next(s for s in S if s.id == 'shake_ashore')
        bank = bank_dx(0)
        self.assertIn(f'fill ~{bank - 9} ~-1 ~0 ~{bank} ~-1 ~0 minecraft:air', scene.lines)
        for level in range(1, 8):
            self.assertIn(f'setblock ~{bank + 1 - level} ~-1 ~0 minecraft:water[level={level}]', scene.lines)
        summon = next(line for line in scene.lines if line.startswith('summon mcyukkuri:reimu '))
        feet_x = float(summon.split()[2][1:])
        self.assertGreater(feet_x, bank - 6)
        self.assertLess(feet_x + .75 / 2, bank - 6 + 1)
        self.assertEqual(summon.split()[3:5], ['~-1', '~0.5'])
        self.assertIn(f'yukkuri status set {sel("a")} needs.wetness 32', scene.lines)


class WindGapStart(unittest.TestCase):
    def test_control_gap_and_spawn_are_outside_the_collectors_paper_search(self):
        scene = next(s for s in S if s.id == 'stray_wind')

        def spawn_column(kind, name):
            line = next(line for line in scene.lines if line.startswith(f'summon {kind} ') and f'scn_{name}"' in line)
            return int(float(line.split()[2][1:]) // 1)

        collector = spawn_column('mcyukkuri:reimu', 'wind_stray')
        control = spawn_column('mcyukkuri:reimu', 'wind_plain')
        paper = spawn_column('minecraft:item', 'wind_paper')
        search = 16  # FoundShelter's eight-block search doubles for a post-baby child.
        self.assertEqual(collector, paper)
        self.assertGreater(abs(control - paper), search + 1)  # The drop's bounding box can cross a cell edge.
        for column in (collector, control):
            self.assertIn(f'setblock ~{column} ~1 ~-1 minecraft:spruce_planks', scene.lines)
        self.assertIn((320, f'setblock ~{collector} ~ ~ minecraft:campfire[lit=true]'), scene.smoke['commands'])
        line = f'[habitat] reimu uuid={_uuid_text(scene.id, "wind_plain")} t=1 wind cover paper approach'
        self.assertTrue(any(re.search(pattern, line) for pattern in scene.smoke['forbid']))


class PondBankPickup(unittest.TestCase):
    def test_a_second_lift_needs_a_real_approach_to_the_hull(self):
        lift = f'[action] marisa uuid={PARENT} t=12616 end id=grab outcome=DONE detail=mounted target={CHILD}\n'
        blocked = f'[intent] marisa uuid={PARENT} t=12617 end id=set_out result=EXHAUSTED reasons=[TOO_FAR:0]\n'
        walk = f'[action] marisa uuid={PARENT} t=12700 start id=walk owner=drive:aquatic\n'
        self.assertIsNone(_bank_pickup_retries(lift + blocked))
        self.assertIn(CHILD, _bank_pickup_retries((lift + blocked) * 2))
        self.assertIsNone(_bank_pickup_retries(lift + blocked + lift + walk + blocked))


class PondCurrentBanks(unittest.TestCase):
    def test_both_shoal_banks_rise_away_from_the_three_wide_current(self):
        scene = next(s for s in S if s.id == 'hat_current')
        bank = bank_dx(0)
        for z in (-1, 0, 1):
            self.assertIn(f'setblock ~{bank} ~-1 ~{z} minecraft:water', scene.lines)
        for z, facing in ((-2, 'north'), (2, 'south')):
            self.assertIn(f'fill ~{bank + 2} ~-1 ~{z} ~{bank + 9} ~-1 ~{z} minecraft:cobblestone_stairs[facing={facing}]', scene.lines)

    def test_only_one_hit_to_the_grounded_captains_shed_child_before_reaching_a_tread(self):
        def entry(category, actor, tick, message):
            return f'[{category}] marisa #1 uuid={actor} t={tick} dim=minecraft:overworld pos=0, 64, 0 {message}\n'

        start = ('[hat] hat travel at=(0,0,0)\n[hat] hat travel at=(4,0,0)\n'
                 + entry('hat', CHILD, 1, 'scenario place hat=hat place=HEAD')
                 + f'[hat] hat left rider={PARENT}\n'
                 + entry('body', PARENT, 2, f'dismount cause=grounded passengers=[{CHILD}]'))
        shed = entry('family', PARENT, 3, f'head rider shed child={CHILD} cause=carrier_eyes_under')
        hit = entry('water', CHILD, 4, 'damage cause=drown health=10.0 -> 9.0 loss=1.0')
        captain_dry = entry('water', PARENT, 5, 'escape done at=(0,0,0) support=stand=(0,0,0)')
        finish = captain_dry + captain_dry.replace(PARENT, CHILD)
        self.assertIsNone(current_drift(start + shed + hit + finish))
        self.assertIsNotNone(current_drift(start + shed + hit * 2 + finish))
        self.assertIsNotNone(current_drift(start + hit + shed + finish))
        self.assertIsNotNone(current_drift(start + shed + finish + hit))
        self.assertIsNotNone(current_drift(start + shed + hit + captain_dry))
        scene = next(s for s in S if s.id == 'hat_current')
        self.assertEqual([], fates(start + shed + hit + finish, scene.smoke['allow_fate']))
        self.assertTrue(fates(start + shed + hit.replace(CHILD, PARENT) + finish, scene.smoke['allow_fate']))


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


class ElopementScenes(unittest.TestCase):
    def test_pair_history_and_affinity_are_start_conditions(self):
        for sid, pet_bond, wild_bond, boldness, visits in [('elope', 30, 8, 20, 0), ('elope_leave', 20, 20, 95, 2)]:
            scene = next(s for s in S if s.id == sid)
            pet = next(l for l in scene.lines if l.startswith('summon mcyukkuri:reimu '))
            if visits:
                partner = uuid_nbt(uid(sid, 'wild'))
                self.assertIn('Conscience:{Trysts:[{Partner:' + partner + ',Returns:2}]}', pet)
                self.assertIn('UUID:' + partner, next(l for l in scene.lines if l.startswith('summon mcyukkuri:marisa ')))
            else:
                self.assertNotIn('Trysts:', pet)
            self.assertIn(f'Innate:[I;55,70,{boldness},5,5]', pet)
            self.assertIn(f'yukkuri status relate {sel("pet")} {sel("wild")} {pet_bond}', scene.lines)
            self.assertIn(f'yukkuri status relate {sel("wild")} {sel("pet")} {wild_bond}', scene.lines)

    def meeting(self, sid, visits, chance):
        p, w = (_uuid_text(sid, role) for role in ('pet', 'wild'))
        return '\n'.join([
            f'[rules] uuid={w} t=50 dim=x tryst approach pet={p} from=(15, 0, 0) edge=(10, 0, 0)',
            f'[rules] uuid={w} t=100 dim=x tryst call pet={p} at=(10, 0, 0) yard=(0, 0, 0) spoke=true',
            f'[rules] uuid={p} t=150 dim=x breach rule=no_leaving_yard watched=false owner=player',
            f'[rules] uuid={p} t=170 dim=x tryst met partner={w} petAt=(9, 0, 0) wildAt=(10, 0, 0)',
            f'[rules] uuid={p} t=250 dim=x departure opportunity partner={w} visits={visits} chance={chance} '
            'roll=0.2 pressure=100.0 neglect=85 attachment=5 obedience=0 selfControl=5 bond=20 ownerTrust=0']) + '\n'

    def test_first_tryst_needs_a_real_return(self):
        p, w = (_uuid_text('elope', role) for role in ('pet', 'wild'))
        log = self.meeting('elope', 0, 0) + f'[rules] uuid={p} t=340 dim=x tryst returned partner={w} visits=1 bond=26 petAt=(2, 0, 0) yard=(0, 0, 0)'
        self.assertIsNone(elopement_result(log, 'elope', 0))
        for broken in (log.replace('tryst approach', 'wander'), log.replace('spoke=true', 'spoke=false'),
                       log.replace('watched=false', 'watched=true'), log.replace('t=250', 't=171'),
                       log.replace('petAt=(9', 'petAt=(7'), log.replace('petAt=(2', 'petAt=(7'),
                       log.replace('visits=1', 'visits=2'), log.replace('tryst returned', 'returning')):
            self.assertIsNotNone(elopement_result(broken, 'elope', 0))

    def test_saved_pair_must_leave_together_and_keep_its_token(self):
        sid = 'elope_leave'
        p, w = (_uuid_text(sid, role) for role in ('pet', 'wild'))
        pull = .8775 * 20 / 30
        log = (f'[badge] uuid={p} t=2 dim=x issued grade=NONE -> BRONZE serial=token-12\n'
               + self.meeting(sid, 2, pull*pull*(3-2*pull)) + '\n'.join([
                   f'[rules] uuid={p} t=250 dim=x departure started partner={w} from=(9, 0, 0) to=(26, 0, 0) yard=(0, 0, 0)',
                   f'[rules] uuid={p} t=500 dim=x departure ended reason=arrived partner={w} petAt=(26, 0, 0) wildAt=(28, 0, 0)',
                   f'[custody] uuid={p} t=500 dim=x changed from=pet to=wild_family cause=eloped',
                   f'[badge] uuid={p} t=500 dim=x disqualified cause=eloped',
                   f'[rules] uuid={p} t=500 dim=x eloped partner={w} wildAt=(28, 0, 0) identity=wild_family token=BRONZE serial=token-12']))
        self.assertIsNone(elopement_result(log, sid, 2))
        release = next(l for l in log.splitlines() if '[custody]' in l)
        for broken in (log.replace('visits=2', 'visits=0'), log.replace('roll=0.2', 'roll=0.99'),
                       log.replace('petAt=(26', 'petAt=(24'), log.replace('wildAt=(28, 0', 'wildAt=(28, 5'),
                       log.replace('tryst met', 'approaching'), log.replace(f'partner={w}', f'partner={p}', 1),
                       log.replace(release, ''), log + '\n' + release, log.replace('disqualified cause=eloped', ''),
                       log.replace('token=BRONZE serial=token-12', 'token=BRONZE serial=token-13')):
            self.assertIsNotNone(elopement_result(broken, sid, 2))


class ShopKinRoom(unittest.TestCase):
    def test_requires_one_littermate_claim_and_timid_drink_after_waiting(self):
        scene = next(s for s in S if s.id == 'shop_kin_room')
        verify = scene.smoke['verify']
        actors = dict(zip(('kin_bold', 'kin_timid', 'kin_kind'), (CHILD, OTHER_CHILD, PARENT)))
        trace = ['[trace] ' + json.dumps(dict(k='geo', u=actor, tags=[f'scn_{name}']))
                 for name, actor in actors.items()]
        litter = ''.join(f'[family] reimu #1 uuid={actor} t=1 wholesale litter mother=m father=f\n'
                         for actor in actors.values())
        x, z = BY_ID['shop'].anchor
        claim = (f'[home] reimu #1 uuid={CHILD} t=2 assigned kind=care old=null '
                 f'home=ResourceKey[minecraft:dimension / minecraft:overworld] BlockPos{{x={x + 10}, y={FEET + 2}, z={z - 2}}}\n')
        guard = (f'[meal] reimu #1 uuid={CHILD} t=3 supply guarded supply=waterer kind=water '
                 f'guard={CHILD} approacher={OTHER_CHILD} reason=harsh\n')
        drink = (f'[needs] reimu #2 uuid={OTHER_CHILD} t=4 drink source=waterer kind=waterer '
                 'water=1 -> 0 dryness=55.0 -> 25.0\n')
        self.assertIsNone(verify(litter + claim + guard + drink, trace))
        for wrong_claim in ('', claim + claim.replace(CHILD, OTHER_CHILD), claim.replace(CHILD, OTHER_PARENT)):
            self.assertIn('claim the cushion', verify(litter + wrong_claim + guard + drink, trace))
        self.assertIn('one parent pair', verify(litter.replace('father=f', 'father=g', 1) + claim + guard + drink, trace))
        for wrong_order in (guard, drink, drink + guard):
            self.assertIn('wait for the bold one', verify(litter + claim + wrong_order, trace))


if __name__ == '__main__':
    unittest.main(verbosity=2)
