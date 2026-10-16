"""AQUATIC RESCUE STAND -- the one place to edit when the water-rescue clips change.

The scenes follow the redesigned flows (docs/AQUATIC_MARISA_IMPLEMENTATION.md; brim dip and biting the brim
are project-original, not canon):

  1. climb      parent dips the near brim (hat_brim_offer), child hops up; in the water: bite, two lunges, slide in
  2. self       child falls in, shakes the hat off, bites the brim, pushes the capsized hat over three times, climbs in
  3. braid      parent paddles over, dips the brim, child bites, parent nudges, child slides in, nuzzle afterwards
  4. rock       someone rocks the boat: alarm, answer, withdraw, calm

Those clips (water_*, hat_brim*, hat_hop_up, hat_slide_over, boat_nudge_young ...) and their dialogue
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
    (60, [('mom', 'hat_brim_offer', None, '亲油移开重心，近缘的帽檐低下来')]),
    (30, [('kid', 'hat_hop_up', None, '小子油蹿上帽檐')]),
    (60, [('kid', 'hat_brim', None, '站稳'), ('mom', 'boat_steady', None, None)]),
    (40, [('kid', 'hat_stumble', None, '拥挤失衡'), ('mom', 'boat_alarm_call', 'family_alarm_call', None)]),
    (40, [('kid', 'water_plunge', None, '落进水里')]),
    (50, [('kid', 'water_struggle', None, '朝亲船游去'), ('mom', 'hat_brim_offer', None, '亲油再次低下帽缘')]),
    (50, [('kid', 'water_mouth_grip', None, '咬住帽缘')]),
    (24, [('kid', 'water_bob_lunge', None, '蹿身')]),
    (24, [('kid', 'water_bob_lunge', None, None)]),
    (40, [('kid', 'hat_slide_over', None, '贴着帽缘滑进帽里')]),
    (80, [('kid', 'water_recover', None, '喘息'), ('mom', 'boat_calm', 'family_alarm_calm', None)]),
]
SELF = [
    (40, [('kid', 'water_plunge', 'self_rescue_slip', '失足落水')]),
    (70, [('kid', 'water_struggle', None, '扑腾，帽子也翻了')]),
    (36, [('kid', 'water_hat_shake', 'self_rescue_hat', '把头上的帽子抖落')]),
    (50, [('kid', 'water_mouth_grip', 'self_rescue_grip', '咬住倾覆的帽缘')]),
    (20, [('kid', 'water_hat_push', None, '用身体顶帽子（一）')]),
    (20, [('kid', 'water_hat_push', None, '（二）')]),
    (20, [('kid', 'water_hat_push', None, '（三），帽子翻正')]),
    (24, [('kid', 'water_bob_lunge', None, '蹿身')]),
    (40, [('kid', 'hat_slide_over', 'self_rescue_climb', '贴着帽缘滑进帽里')]),
    (80, [('kid', 'water_recover', 'self_rescue_safe', '获救后伏低喘息')]),
]
BRAID = [
    (50, [('kid', 'water_plunge', None, '小子油落水'), ('mom', 'boat_alarm_call', 'family_alarm_call', None)]),
    (60, [('kid', 'water_struggle', 'water_help', None), ('mom', 'boat_paddle', None, '亲油划近')]),
    (50, [('mom', 'hat_brim_offer', 'braid_rescue_offer', '亲油下沉近缘，辫子只轻触帽缘')]),
    (50, [('kid', 'water_mouth_grip', None, '孩子咬住帽缘')]),
    (20, [('mom', 'boat_nudge_young', 'braid_rescue_grip', '亲油贴身一顶')]),
    (40, [('kid', 'hat_slide_over', None, '孩子滑进帽里')]),
    (80, [('mom', 'boat_calm', 'braid_rescue_safe', '平复'), ('kid', 'water_recover', None, None)]),
    (50, [('mom', 'boat_nuzzle', None, '蹭蹭安慰')]),
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
    c.water_floor()
    c.label(5.5, 5.2, 2.7, '落水是真实的危险：浸水每秒掉血', .35)
    c.demo('climb', '上亲油帽檐', _steps(c, CLIMB))
    c.demo('self', '落水自救', _steps(c, SELF))
    c.demo('braid', '亲油救助', _steps(c, BRAID))
    c.demo('rock', '摇船受惊', _steps(c, ROCK))
