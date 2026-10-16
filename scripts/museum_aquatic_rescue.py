"""AQUATIC RESCUE STAND -- the one place to edit when the water-rescue clips change.

The three scenes below follow the aquatic documentation (docs/AQUATIC_MARISA_IMPLEMENTATION.md, "分阶段落水"):

  1. climb      a child in the water grips the parent's hat brim and climbs back aboard
  2. self       a child who can handle a hat falls in, flips her own hat and climbs in
  3. braid      a baby who cannot jump is lifted out by the parent's single braid
  4. rock       someone rocks the boat: alarm, answer, withdraw, calm (not a rescue, but the same clips family)

Those clips (water_*, hat_brim, braid_rescue_*, boat_alarm_*, boat_reassure ...) and their dialogue
events (self_rescue_*, braid_rescue_*, family_alarm_*) are being re-authored by another task. Keep the
names in this file only: build_museum_full.py validates every clip id against the current model JSON
and every event against the dialogue catalogue, so a rename shows up as a generation error, not as a
silent broken scene.
"""
import sys

sys.dont_write_bytecode = True
from museum_dsl import Ctx  # noqa: E402

# ---- scene data ---------------------------------------------------------------------------------------
# (delay in ticks, [(actor, action | None, dialogue event | None, note | None), ...])
CLIMB = [
    (50, [('kid', 'hat_brim', None, '小子油站在亲油帽檐上'), ('mom', 'boat_steady', None, None)]),
    (40, [('kid', 'hat_jostle', None, '帽檐拥挤，被挤到边上'), ('mom', 'boat_alarm_call', 'family_alarm_call', None)]),
    (36, [('kid', 'hat_stumble', None, '失去帽檐支撑')]),
    (40, [('kid', 'water_plunge', None, '落进水里'), ('mom', 'boat_alarm_wait', None, None)]),
    (50, [('kid', 'water_struggle', None, '扑腾着朝亲船游去'), ('mom', 'boat_steady', None, None)]),
    (50, [('kid', 'water_hat_grip', None, '抓住亲帽的帽缘'), ('mom', 'boat_reassure', 'family_alarm_calm', None)]),
    (50, [('kid', 'water_hat_climb', None, '用力拉身，越过帽檐'), ('mom', 'boat_steady', None, None)]),
    (80, [('kid', 'hat_brim', None, '重新站稳在帽檐上'), ('mom', 'boat_calm', None, None)]),
]
SELF = [
    (40, [('kid', 'water_plunge', 'self_rescue_slip', '失足落水')]),
    (80, [('kid', 'water_struggle', None, '扑腾，找自己的帽子')]),
    (60, [('kid', 'water_hat_flip', 'self_rescue_hat', '把倾覆的帽子翻正')]),
    (50, [('kid', 'water_hat_grip', 'self_rescue_grip', '抓住帽缘')]),
    (50, [('kid', 'water_hat_climb', 'self_rescue_climb', '爬回帽里')]),
    (80, [('kid', 'water_recover', 'self_rescue_safe', '获救后伏低喘息')]),
]
BRAID = [
    (50, [('baby', 'water_plunge', None, '还不会跳的赤油落水'), ('mom', 'boat_alarm_call', 'family_alarm_call', None)]),
    (60, [('baby', 'water_struggle', None, '浮在水面扑腾'), ('mom', 'boat_alarm_wait', None, '亲油先划近')]),
    (36, [('baby', 'water_gasp', None, None), ('mom', 'braid_rescue_reach', 'braid_rescue_offer', '单辫伸出')]),
    (36, [('mom', 'braid_rescue_hold', 'braid_rescue_grip', '辫梢托住孩子，抬过帽檐')]),
    (30, [('mom', 'braid_rescue_settle', 'braid_rescue_safe', '放回帽里')]),
    (70, [('baby', 'water_recover', None, '喘息，回看亲友'), ('mom', 'boat_reassure', None, None)]),
]
ROCK = [
    (40, [('mom', 'boat_steady', None, '有人用力摇晃帽船')]),
    (40, [('mom', 'boat_alarm_call', 'boat_rock_startle', '亲油吃惊，呼唤家人')]),
    (30, [('kid', 'boat_answer_call', 'family_alarm_answer', '孩子听到，回应')]),
    (60, [('mom', 'boat_flee', None, '稳住后，先远离摇船的人'), ('kid', 'boat_steady', None, None)]),
    (90, [('mom', 'boat_calm', 'family_alarm_calm', '确认安全，慢慢平复')]),
]
# ---- /scene data --------------------------------------------------------------------------------------


def _steps(c, rows):
    out = []
    for delay, items in rows:
        cmds = []
        for actor, action, event, note in items:
            if note:
                cmds.append(c.NOTE(note))
            if action:
                cmds.append(c.A(actor, action))
            if event:
                cmds.append(c.S(actor, event))
        out.append((delay, cmds))
    return out


def rescue(c: Ctx):
    c.actor('mom', 'marisa', 4.0, 3, 'F', '亲油魔理沙', aquatic=True)
    c.actor('kid', 'marisa', 8.2, 3, 'F', '小子油魔理沙', stage='child', aquatic=True)
    c.actor('baby', 'reimu', 1.5, 3, 'F', '赤油', stage='baby')
    c.water_floor()
    c.label(5.5, 5.2, 2.7, '落水是真实的危险：浸水每秒掉血', .35)
    c.demo('climb', '攀回帽檐', _steps(c, CLIMB))
    c.demo('self', '自己翻帽求生', _steps(c, SELF))
    c.demo('braid', '亲油用辫子救人', _steps(c, BRAID))
    c.demo('rock', '摇船受惊', _steps(c, ROCK))
