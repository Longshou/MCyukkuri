"""Stands of the showcase museum, grouped into halls.

Every stand is a small function that describes its actors, props and scripted scenes through ``Ctx``
(see museum_dsl.py). Scenes are *scripted demonstrations*: they play real clips with ``/yukkuri action``
and real dialogue events with ``/yukkuri say`` and never claim to be the natural behaviour. The two
real-behaviour corners (the hands-on pen and the settlement meadow) are built by the generator.

The water-rescue stand lives in museum_aquatic_rescue.py on purpose: those three scenes are being
re-authored upstream and only that file has to change.
"""
import sys

sys.dont_write_bytecode = True
from museum_dsl import Ctx, ticks, wild_command, _text as _t  # noqa: E402
import museum_aquatic_rescue  # noqa: E402


# ======================================================================== HALL A  body and everyday life
def body(c):
    c.actor('body', 'reimu', 5.5, 3, 'F', '灵梦', named=False)
    c.demo('walk', '走路与跳跃', c.seq('body', 'waddle', 'turn', 'hop'))
    c.demo('stretch', '伸展与挤压', c.seq('body', 'stretch_shape', 'side_bend', 'stretch', 'press'))
    c.demo('exercise', '一起做体操', c.seq('body', 'exercise_vertical', 'exercise_sides', 'taiso'))


def daily(c):
    c.actor('daily', 'reimu', 5.5, 3, 'F', '灵梦', named=False)
    c.block(1, 3, 'mcyukkuri:food_bowl')
    c.block(9, 4, 'mcyukkuri:yukkuri_nest')
    c.demo('meal', '好好吃一餐', c.seq('daily', 'eat', 'shiawase', 'drink'))
    c.demo('rest', '睡觉与醒来', c.seq('daily', 'sleep', 'wake'))
    c.demo('wash', '洗后甩甩水', c.seq('daily', 'shake'))


def social(c):
    c.actor('friend', 'chen', 3.5, 3, 'F', '橙', named=False)
    c.actor('mate', 'alice', 7.5, 3, 'F', '爱丽丝', named=False)
    c.demo('hello', '相遇与蹭蹭', [
        (80, [c.A('friend', 'look_around'), c.A('mate', 'idle')]),
        (32, [c.A('friend', 'greet'), c.A('mate', 'greet'), c.S('friend', 'hello'), c.S('mate', 'peer')]),
        (64, [c.A('friend', 'nuzzle'), c.A('mate', 'nuzzle'), c.S('friend', 'nuzzle')])])
    c.demo('song', '唱歌与伴奏', [
        (120, [c.A('friend', 'sing'), c.A('mate', 'sway')]),
        (120, [c.A('friend', 'sway'), c.A('mate', 'sing')])])
    c.demo('mood', '鼓腮·受惊·低落', c.seq('friend', 'puff', 'startle', 'tremble', 'droop'))
    c.demo('feel', '紧张·害羞·得意', [
        (60, [c.NOTE('紧张'), c.A('friend', 'nervous')]),
        (70, [c.NOTE('害羞'), c.A('friend', 'shy'), c.S('friend', 'shy')]),
        (50, [c.NOTE('得意'), c.A('friend', 'proud')]),
        (44, [c.NOTE('欢呼'), c.A('friend', 'joy_jump')]),
        (80, [c.NOTE('闹别扭'), c.A('friend', 'sulk')])])


def care(c):
    c.actor('pat', 'chen', 4.5, 3, 'F', '橙')
    c.block(8, 3, 'mcyukkuri:yukkuri_toilet')
    c.item('mcyukkuri:bandage', 2.0, 1.2, 1.1, .7)
    c.item('mcyukkuri:orange_juice', 3.0, 1.2, 1.1, .7)
    c.demo('hurt', '轻伤与治疗', [
        (60, [c.NOTE('受了轻伤：发抖'), c.A('pat', 'tremble'), c.S('pat', 'wounded')]),
        (80, [c.NOTE('低落地缩着'), c.A('pat', 'droop')]),
        (60, [c.NOTE('主人敷上绷带、喂橙汁'), c.S('pat', 'treated'), c.FX('happy_villager', 4.5, 3, 1.0, (.3, .2, .3), 0, 6)]),
        (50, [c.NOTE('缓过来了'), c.A('pat', 'shiawase')])])
    walk = c.walk('pat', 4.5, 7.3, 3, 'R', steps=8)
    c.demo('toilet', '去厕所', [
        (50, [c.NOTE('便意来了'), c.A('pat', 'tremble'), c.S('pat', 'need_toilet')])] + walk + [
        (60, [c.NOTE('在厕所里排泄'), c.A('pat', 'brace'), c.S('pat', 'toilet_done')]),
        (50, [c.A('pat', 'shiawase')])])


# ======================================================================== HALL B  family and growth
def _birth_scene(c, mother, helper, children, mode='land'):
    """The server owns approach, contractions, emergence, landing and recovery as one sequence.

    Wiki adoption: front/lower-face outlet, sequential births, and a soft inverted hat cradle.
    The fixed two-child cast and the stage timing are museum presentation choices.
    """
    mother_actions = ('boat_brood', 'boat_labor', 'postpartum') if mode == 'boat' else ('brood', 'labor', 'postpartum')
    helper_actions = ('waddle', 'hat_off', 'hat_hold', 'nuzzle', 'hat_on') if mode == 'land' else (
        ('waddle', 'hat_off_aq', 'hat_hold_aq', 'nuzzle', 'hat_on_aq') if mode == 'bank' else ('waddle', 'midwife'))
    clips = [c.SCENE_ACTION(mother, action) for action in mother_actions]
    clips += [c.SCENE_ACTION(helper, action) for action in helper_actions]
    for child in children:
        clips += [c.SCENE_ACTION(child, action) for action in ('sleep', 'first_wake', 'look_around')]
    return [(900, [c.NOTE('观察：亲油准备、帮手靠近、阵痛、逐只出生与产后照护')] +
             clips + c.BIRTH(mother, helper, children, mode))]


def birth(c):
    c.actor('mom', 'reimu', 3.5, 3, 'R', '亲本灵梦')
    c.actor('help', 'marisa', 8, 3, 'L', '接生魔理沙')
    c.actor('baby', 'reimu', 5.5, 2.4, 'F', '初生赤油', stage='baby', hidden=True)
    c.actor('baby2', 'marisa', 5.8, 2.4, 'F', '第二只赤油', stage='baby', hidden=True)
    c.block(3, 5, 'mcyukkuri:yukkuri_nest')
    c.demo('birth', '安胎到产后', _birth_scene(c, 'mom', 'help', ('baby', 'baby2')))
    c.demo('newborn', '初生的醒来', [
        (20, [c.SHOW('baby', 5.5, 2.4, 'F')]),
        (50, [c.NOTE('赤油睁开眼睛'), c.A('baby', 'first_wake'), c.S('baby', 'newborn')]),
        (80, [c.A('baby', 'look_around')])])


def feeding(c):
    c.actor('mom', 'chen', 4.0, 3, 'R', '橙（亲油）')
    c.actor('baby', 'reimu', 7.2, 3, 'L', '赤油', stage='baby')
    c.demo('feed', '嚼糜喂养', [
        (50, [c.NOTE('赤油牙齿脆弱，不会自己啃食'), c.A('baby', 'look_around'), c.S('baby', 'hungry')]),
        (60, [c.NOTE('亲油先把食物嚼成糜'), c.A('mom', 'eat'), c.S('mom', 'chew'), c.PROP_ON('mash', 'minecraft:wheat', 4.6, 3, .55, .35)]),
        ] + c.walk('mom', 4.0, 5.9, 3, 'R', steps=4, carry='mash', carry_dy=.55) + [
        (60, [c.NOTE('近身递喂，赤油才吞咽'), c.PROP_MOVE('mash', 6.6, 3, .45), c.A('baby', 'eat'), c.S('baby', 'fed')]),
        (50, [c.PROP_OFF('mash'), c.A('baby', 'shiawase'), c.A('mom', 'nuzzle')])])
    c.demo('toilet', '首次排便', [
        (50, [c.NOTE('首次排便要亲油帮忙'), c.A('baby', 'tremble'), c.S('baby', 'need_toilet')])] +
        c.walk('mom', 4.0, 8.0, 3, 'R', steps=6, v1=1.7) + [
        (6, [c.TP('mom', 8.4, 3, 'L')]),
        (50, [c.NOTE('亲油舔舐刺激'), c.A('mom', 'nuzzle')]),
        (50, [c.NOTE('排出一份真实的嗯嗯'), c.A('baby', 'brace'), c.S('baby', 'toilet_done'), c.PROP_ON('waste', 'mcyukkuri:yukkuri_waste', 7.9, 3, .25, .4)]),
        (50, [c.PROP_OFF('waste'), c.A('baby', 'shiawase')])])
    c.stop_extra += [c.PROP_OFF('mash')[3], c.PROP_OFF('waste')[3]]


def hopping(c):
    c.actor('mom', 'reimu', 3.5, 3, 'F', '亲本灵梦')
    c.actor('kid', 'chen', 7.5, 3, 'F', '子油橙', stage='child')
    c.demo('hop', '学起跳', [
        (50, [c.NOTE('子油先爬行、试探'), c.A('kid', 'look_around')]),
        (60, [c.NOTE('亲油示范起跳'), c.A('mom', 'hop'), c.S('mom', 'lesson_start')]),
        (60, [c.NOTE('子油跟着练'), c.A('kid', 'hop')]),
        (60, [c.A('kid', 'hop'), c.A('mom', 'hop')]),
        (50, [c.NOTE('学会了，亲油夸奖'), c.A('kid', 'joy_jump'), c.S('mom', 'lesson_praised'), c.S('kid', 'lesson_learned')])])
    c.demo('play', '一起活动', [
        (120, [c.NOTE('亲子一起体操'), c.A('mom', 'exercise_vertical'), c.A('kid', 'exercise_vertical')]),
        (80, [c.A('mom', 'sway'), c.A('kid', 'sway')])])


def growth(c):
    c.actor('baby', 'reimu', 1.4, 3, 'F', '赤油', stage='baby')
    c.actor('child', 'reimu', 3.8, 3, 'F', '子油', stage='child')
    c.actor('teen', 'reimu', 6.6, 3, 'F', '亚成油', stage='teen')
    c.actor('adult', 'reimu', 9.4, 3, 'F', '成油')
    c.block(0, 1, 'mcyukkuri:yukkuri_nest')
    c.demo('sizes', '一路长大', [
        (60, [c.NOTE('赤油：约 0.1–0.2 倍体型'), c.A('baby', 'look_around')]),
        (60, [c.NOTE('子油：学会起跳'), c.A('child', 'hop')]),
        (90, [c.NOTE('亚成油：能自己吃、自己睡'), c.A('teen', 'exercise_vertical')]),
        (90, [c.NOTE('成油：约 0.8–1.2 倍'), c.A('adult', 'stretch')])])
    c.demo('leave', '告别与独立', [
        (50, [c.NOTE('亚成油营养和能力都够了'), c.A('teen', 'look_around')]),
        (40, [c.NOTE('向家人告别'), c.A('teen', 'greet'), c.S('teen', 'hello'), c.A('adult', 'bow')])] +
        c.walk('teen', 6.6, 1.9, 3, 'L', steps=9, v1=1.4) + [
        (60, [c.NOTE('认领新居，另立门户'), c.A('teen', 'shiawase')]),
        (80, [c.A('teen', 'sleep'), c.A('adult', 'look_around'), c.S('adult', 'lost_parent')])])


def house(c):
    c.actor('hm', 'house_marisa', 1.5, 4.6, 'F', '家油魔理沙', hidden=True)
    c.actor('hr', 'house_reimu', 8.0, 3.2, 'F', '家油灵梦')
    c.actor('chick', 'house_reimu', 8.9, 2.6, 'F', '新孵的家油', stage='house_baby', hidden=True)
    c.block(1, 4, 'mcyukkuri:house_crevice')
    c.block(7, 4, 'mcyukkuri:house_dust_nest')
    c.item('mcyukkuri:nest_lint', 4.0, 3.0, .35, .5)
    c.item('mcyukkuri:house_egg', 8.8, 4.0, .45, .6)
    c.label(5.5, 5.0, 2.7, '家油在屋檐下：絮尘、巢和卵', .35)
    c.demo('arrive', '入住与筑巢', [
        (20, [c.NOTE('家油从缝隙里探头'), c.SHOW('hm', 1.5, 4.0, 'F'), c.S('hm', 'house_peek')]),
        (60, [c.A('hm', 'look_around'), c.S('hm', 'house_search_home')])] +
        c.walk('hm', 1.5, 3.8, 4.0, 'R', steps=5, v1=3.1) + [
        (40, [c.NOTE('夹起一份絮尘'), c.S('hm', 'house_pickup'), c.PROP_ON('lint', 'mcyukkuri:nest_lint', 3.8, 3.1, .4, .35)])] +
        c.walk('hm', 3.8, 6.4, 3.1, 'R', steps=5, v1=3.6, carry='lint', carry_dy=.3) + [
        (50, [c.NOTE('放进巢里'), c.PROP_OFF('lint'), c.S('hm', 'house_deposit'), c.A('hm', 'shiawase')])])
    c.demo('egg', '照卵与孵化', [
        (60, [c.NOTE('亲油凑近照看巢里的卵'), c.A('hr', 'nuzzle'), c.S('hr', 'house_listen')]),
        (60, [c.NOTE('侧头倾听'), c.A('hr', 'look_around')]),
        (30, [c.NOTE('卵裂开了'), c.FX('happy_villager', 8.8, 4.0, .5, (.2, .2, .2), 0, 8)]),
        (70, [c.NOTE('新孵出的家油睁开眼'), c.SHOW('chick', 8.9, 2.6, 'F'), c.A('chick', 'first_wake'), c.S('hr', 'house_hatched'), c.S('chick', 'newborn')]),
        (60, [c.A('hr', 'nuzzle')])])


# ======================================================================== HALL C  aquatic Marisa
def hats(c):
    c.actor('hatter', 'marisa', 8, 3, 'L', '接生魔理沙', named=False)
    c.actor('mom', 'reimu', 3.5, 3, 'R', '待产灵梦')
    c.actor('baby', 'reimu', 5.5, 2.4, 'F', '初生赤油', stage='baby', hidden=True)
    c.actor('baby2', 'marisa', 5.8, 2.4, 'F', '第二只赤油', stage='baby', hidden=True)
    c.demo('assist', '摘帽·扶帽·戴回', _birth_scene(c, 'mom', 'hatter', ('baby', 'baby2')))
    c.demo('self', '独自摘戴帽子', c.seq('hatter', 'hat_off_self', 'hat_on_self'))


def waterside(c):
    c.actor('aq', 'marisa', 8, 3, 'L', '水生接生魔理沙', aquatic=True, named=False)
    c.actor('mom', 'reimu', 3.5, 3, 'R', '岸边待产灵梦')
    c.actor('baby', 'reimu', 5.5, 2.4, 'F', '初生赤油', stage='baby', hidden=True)
    c.actor('baby2', 'marisa', 5.8, 2.4, 'F', '第二只赤油', stage='baby', hidden=True)
    c.water_floor()
    c.fill_cells(0, 1, 3, 5, 'minecraft:smooth_quartz', dy=-1)
    c.label(1.8, 5, .15, '干岸 → 水面', .3)
    c.demo('balance', '水上姿态', c.seq('aq', 'boat_drift', 'boat_steady', 'boat_call'))
    c.demo('assist', '水边扶帽', _birth_scene(c, 'mom', 'aq', ('baby', 'baby2'), 'bank'))
    c.demo('self', '水边独自摘戴', c.seq('aq', 'hat_off_self_aq', 'hat_on_self_aq'))


def boatlife(c):
    c.actor('sailor', 'marisa', 5.5, 3, 'F', '水生魔理沙', aquatic=True)
    c.water_floor()
    c.item('minecraft:stick', 2.0, 1.4, 1.0, .6)
    c.label(2.0, 1.4, 1.7, '桨：真实木棍', .35)
    c.demo('work', '备粮与划桨', [
        (40, [c.NOTE('登上自己的帽船'), c.A('sailor', 'boat_board')]),
        (40, [c.NOTE('收集岸上口粮'), c.A('sailor', 'boat_gather')]),
        (100, [c.NOTE('划桨'), c.A('sailor', 'boat_paddle')]),
        (60, [c.NOTE('在船上吃饭'), c.A('sailor', 'boat_eat')]),
        (60, [c.NOTE('在船边喝水'), c.A('sailor', 'boat_drink')])])
    c.demo('night', '船上睡觉', [
        (120, [c.NOTE('夜里停泊，在帽船里睡'), c.A('sailor', 'boat_sleep')]),
        (50, [c.NOTE('醒来'), c.A('sailor', 'boat_wake')])])
    c.demo('hat', '返岸理帽', [
        (60, [c.NOTE('回到干岸，用辫子理帽甩水'), c.A('sailor', 'aquatic_hat_care'), c.A('sailor', 'shake')]),
        (40, [c.A('sailor', 'shiawase')])])


def boatfamily(c):
    c.actor('mom', 'marisa', 4.0, 3, 'R', '水生魔理沙（亲）', aquatic=True)
    c.actor('help', 'marisa', 8.5, .5, 'L', '陪产水生魔理沙', aquatic=True)
    c.actor('baby', 'marisa', 7.0, 2.6, 'F', '帽里的赤油', stage='baby', aquatic=True, hidden=True)
    c.actor('baby2', 'marisa', 7.3, 2.6, 'F', '第二只赤油', stage='baby', aquatic=True, hidden=True)
    c.water_floor()
    c.fill_cells(0, 0, 10, 0, 'minecraft:smooth_quartz', dy=-1)
    c.demo('birth', '船上安胎与分娩', _birth_scene(c, 'mom', 'help', ('baby', 'baby2'), 'boat'))
    c.demo('feed', '递食与亲昵', [
        (40, [c.SHOW('baby', 6.2, 3, 'L'), c.NOTE('赤油饿了'), c.S('baby', 'hungry')]),
        (60, [c.NOTE('亲油先嚼成糜'), c.A('mom', 'boat_eat'), c.S('mom', 'chew')]),
        (60, [c.NOTE('近身递食'), c.A('mom', 'boat_feed_young'), c.A('baby', 'eat'), c.S('baby', 'fed')]),
        (60, [c.NOTE('亲昵'), c.A('mom', 'boat_nuzzle'), c.A('baby', 'shiawase')])])
    c.demo('lullaby', '摇篮曲', [
        (120, [c.NOTE('夜里哼起摇篮曲'), c.SHOW('baby', 6.2, 3, 'L'), c.A('mom', 'boat_lullaby'), c.A('baby', 'sway')]),
        (100, [c.NOTE('赤油睡着了'), c.A('baby', 'sleep')])])


def voyage(c):
    c.actor('dad', 'marisa', 3.0, 3, 'F', '亲船魔理沙', aquatic=True)
    c.actor('kid', 'marisa', 7.8, 3, 'F', '小子油', stage='child', aquatic=True)
    c.water_floor()
    c.demo('first', '第一次出航', [
        (40, [c.NOTE('小子油已有固食、起跳和学习能力，才能初航'), c.A('kid', 'look_around')]),
        (60, [c.NOTE('走到亲船帽檐边，把自己的帽子放到水面'), c.A('kid', 'hat_off_self_aq')]),
        (40, [c.NOTE('跳进自己的帽船'), c.A('kid', 'boat_board'), c.A('dad', 'boat_steady')]),
        (50, [c.NOTE('试坐，保持平衡'), c.A('kid', 'boat_first_balance')]),
        (100, [c.NOTE('划出两三格，亲油守在旁边'), c.A('kid', 'boat_paddle'), c.A('dad', 'boat_reassure')]),
        (60, [c.NOTE('回到亲船旁，收回帽子'), c.A('kid', 'hat_on_self_aq')]),
        (60, [c.NOTE('亲油鼓励'), c.A('dad', 'greet'), c.A('kid', 'shiawase'), c.S('dad', 'peer')])])


# ======================================================================== HALL D  human society
def exam(c):
    c.actor('kid', 'reimu', 4.5, 3, 'R', '考生灵梦', y=66.4375)
    c.actor('judge', 'caretaker_brother', 9.2, 3, 'L', '考官', job=3)
    c.block_facing(4, 3, 'mcyukkuri:exam_podium', 'R')
    c.block_facing(0, 3, 'mcyukkuri:exam_hide_box', 'R')
    c.block_facing(8, 3, 'minecraft:lectern', 'L')
    c.card('exam', 8.5, 3, 1.5, '考试')
    c.stop_extra.append(c.CARD('exam', '考试', 'yellow')[3])
    c.demo('pass', '考试·合格', _exam_scene(c, True))
    c.demo('fail', '考试·不合格', _exam_scene(c, False))


def _exam_scene(c, passed):
    steps = [
        (60, [c.NOTE('考官在场，考生有点紧张……'), c.CARD('exam', '考试中', 'yellow'), c.A('kid', 'nervous'), c.S('kid', 'exam_nervous')]),
        (70, [c.NOTE('开始答题：思考作答'), c.A('kid', 'think'), c.S('kid', 'exam_answer')]),
        (10, [c.NOTE('他者视角：考官背过身，考生要找躲藏箱'), c.FACE('judge', 'R')]),
    ]
    steps += c.walk('kid', 4.5, 1.3, 3, 'L', steps=8, y=66)
    steps += [
        (60, [c.NOTE('躲猫猫：低伏、脸顶进箱子（新的小幅姿态）'), c.A('kid', 'hide_face')] + ([c.S('kid', 'exam_hide_fail')] if not passed else [])),
        (40, [c.NOTE('考官转回来找人' if passed else '屁股露在外面，被找到了'), c.FACE('judge', 'L')]),
    ]
    steps += c.walk('kid', 1.3, 4.5, 3, 'R', steps=8, y=66)
    steps.append((2, [c.TP('kid', 4.5, 3, 'R', 66.4375)]))
    if passed:
        steps += [
            (50, [c.CARD('exam', '合格！铜章', 'green'), c.NOTE('合格'), c.A('kid', 'joy_jump'), c.S('kid', 'exam_pass'),
                  c.FX('happy_villager', 4.5, 3, 1.4, (.4, .3, .4), 0, 10)]),
            (30, [c.NOTE('考官把铜章别上'), c.BADGE('kid', 1), c.FX('glow', 4.5, 3, 1.0, (.3, .2, .3), .02, 8)]),
            (60, [c.NOTE('得意：竖向拉伸、挺胸'), c.A('kid', 'proud'), c.S('kid', 'badge_proud')]),
            (100, [c.NOTE('铜章戴在饰物上，代表登记过的宠物')]),
        ]
    else:
        steps += [
            (50, [c.CARD('exam', '不合格', 'red'), c.NOTE('不合格：圆眼发抖'), c.A('kid', 'tremble'), c.S('kid', 'exam_fail'),
                  c.FX('angry_villager', 4.5, 3, 1.3, (.3, .2, .3), 0, 3)]),
            (110, [c.NOTE('背对主人缩成一团'), c.A('kid', 'sulk')]),
            (40, [c.NOTE('可以再练习，重新报名')]),
        ]
    return steps


def badges(c):
    c.actor('pet', 'reimu', 3.5, 3, 'F', '宠物灵梦')
    c.actor('wild', 'marisa', 8.0, 3, 'F', '野生魔理沙')
    c.item('mcyukkuri:silver_yukkuri_badge', 5.75, 3, .9, 1.0)
    c.label(5.75, 3, 1.7, '银章：能与人同居', .4)
    c.demo('admire', '别上银章', [
        (30, [c.NOTE('主人给宠物别上银章'), c.BADGE('pet', 2), c.FX('glow', 3.5, 3, 1.0, (.3, .2, .3), .02, 8)]),
        (44, [c.A('pet', 'joy_jump'), c.S('pet', 'badge_pinned')]),
        (50, [c.NOTE('得意地亮出徽章'), c.A('pet', 'proud'), c.S('pet', 'badge_proud')]),
        (60, [c.NOTE('旁边的野生个体注意到了'), c.A('wild', 'nervous')]),
        (70, [c.NOTE('羡慕'), c.A('wild', 'beg'), c.S('wild', 'badge_admire')]),
        (70, [c.NOTE('银章：能与人同居')])])
    c.demo('envy', '嫉妒与摘章', [
        (50, [c.NOTE('戴着银章的宠物'), c.BADGE('pet', 2), c.A('pet', 'proud'), c.S('pet', 'badge_proud')]),
        (50, [c.NOTE('野生个体盯着徽章'), c.A('wild', 'nervous')]),
        (70, [c.NOTE('嫉妒'), c.A('wild', 'puff'), c.S('wild', 'badge_envy')]),
        (70, [c.NOTE('主人摘下徽章（登记仍属于它）'), c.BADGE('pet', 0), c.A('pet', 'droop'), c.S('pet', 'badge_taken')])])


def date(c):
    c.actor('bride', 'reimu', 3.2, 3, 'R', '宠物灵梦', grade=1)
    c.actor('stud', 'marisa', 9.0, 3, 'L', '种油魔理沙', grade=2)
    c.actor('keeper', 'caretaker_brother', 6.0, 4.6, 'F', '饲养员', job=4)
    c.item('mcyukkuri:yukkuri_carrier', 4.6, 4.4, .6, .9)
    c.label(6.0, 4.6, 1.9, '相亲 6 绿宝石', .4)
    walk = []
    for k in range(1, 9):
        cmds = [c.TP('bride', round(3.2 + 2.9 * k / 8, 3), 3, 'R'), c.TP('stud', round(9.0 - 1.5 * k / 8, 3), 3, 'L')]
        if k in (1, 4, 7):
            cmds = [c.A('bride', 'waddle'), c.A('stud', 'waddle')] + cmds
        walk.append((4, cmds))
    c.demo('date', '饲养员相亲', [
        (40, [c.NOTE('饲养员把宠物从篮里放出来'), c.A('bride', 'basket_out'), c.S('bride', 'basket_exit')]),
        (60, [c.NOTE('和种油见面：害羞'), c.A('bride', 'shy'), c.S('bride', 'date_meet'), c.A('stud', 'shy')]),
        (50, [c.S('stud', 'date_meet')]),
        (4, [c.NOTE('慢慢走近')])] + walk + [
        (80, [c.NOTE('蹭蹭'), c.A('bride', 'nuzzle'), c.A('stud', 'nuzzle'), c.FX('heart', 6.2, 3, 1.0, (.4, .3, .3), 0, 6)]),
        (80, [c.NOTE('相处融洽'), c.S('stud', 'date_fond'), c.A('bride', 'shiawase'), c.S('bride', 'expecting'),
              c.FX('heart', 6.2, 3, 1.2, (.5, .3, .3), 0, 8)])])


def carry(c):
    c.actor('wild', 'marisa', 3.0, 3, 'F', '野生魔理沙')
    c.actor('pet', 'reimu', 8.0, 3, 'F', '宠物灵梦', grade=1)
    c.item('mcyukkuri:yukkuri_carrier', 3.0, 1.6, .7, 1.1)
    c.item('mcyukkuri:yukkuri_carrier', 8.0, 1.6, .7, 1.1)
    c.item('mcyukkuri:sleep_spray', 5.2, 1.6, .9, .9)
    c.label(5.2, 1.6, 1.5, '弹珠汽水喷雾', .4)
    c.demo('wild', '不肯进篮', [
        (70, [c.NOTE('野生个体不肯进篮：挣扎'), c.A('wild', 'struggle'), c.S('wild', 'basket_refuse')]),
        (100, [c.NOTE('先喷弹珠汽水喷雾'), c.FX('cloud', 3.4, 2.0, .8, (.25, .2, .25), .01, 18), c.A('wild', 'doze_off'), c.S('wild', 'spray_drowsy')]),
        (40, [c.NOTE('瘫软睡着，不再抵抗'), c.A('wild', 'sleep')]),
        (50, [c.NOTE('这时装篮：钻进篮子'), c.A('wild', 'basket_in')]),
        (30, [c.NOTE('（放出时会出篮张望）')]),
        (50, [c.A('wild', 'basket_out'), c.S('wild', 'basket_exit')])])
    c.demo('pet', '自己钻进篮', [
        (50, [c.NOTE('自己的宠物：不用喷雾，自己钻进篮子'), c.A('pet', 'basket_in'), c.S('pet', 'basket_enter')]),
        (34, [c.NOTE('放出时：出篮张望'), c.A('pet', 'basket_out'), c.S('pet', 'basket_exit')]),
        (40, [c.A('pet', 'bow')])])


def wild(c):
    c.actor('beggar', 'marisa', 2.2, 3, 'R', '野生魔理沙')
    c.actor('guard', 'reimu', 7.5, 3, 'L', '宠物灵梦', grade=2)
    c.block(6, 3, 'mcyukkuri:food_bowl')
    approach = c.walk('beggar', 2.2, 4.6, 3, 'R', steps=6)
    c.demo('ignore', '不理会', [(2, [c.NOTE('饥饿的野生个体走过来')])] + approach + [
        (80, [c.NOTE('向宠物讨吃'), c.A('beggar', 'beg'), c.S('beggar', 'wild_role_beg')]),
        (46, [c.NOTE('宠物按应对力判断：不理会'), c.A('guard', 'ignore')]),
        (50, [c.NOTE('向主人鞠躬报告'), c.FACE('guard', 'F'), c.A('guard', 'bow'), c.S('guard', 'pet_report_owner')]),
        (50, [c.NOTE('应对 +1（每天至多 3 次）'), c.FX('happy_villager', 7.5, 3, 1.0, (.4, .3, .4), 0, 8)])])
    c.demo('fail', '没忍住', [(2, [c.NOTE('野生个体走过来，挑衅')])] + c.walk('beggar', 2.2, 4.6, 3, 'R', steps=6) + [
        (60, [c.A('beggar', 'puff'), c.S('beggar', 'wild_role_provoke'), c.FX('angry_villager', 4.6, 3, 1.0, (.3, .2, .3), 0, 3)]),
        (50, [c.NOTE('宠物没忍住：鼓腮'), c.A('guard', 'puff')]),
        (14, [c.NOTE('推挤（压力 +2）'), c.TP('guard', 7.1, 3, 'L'), c.A('guard', 'push')]),
        (40, [c.TP('guard', 7.5, 3, 'L')]),
        (30, [c.NOTE('失败也会记在心里')])])


def predators(c):
    c.actor('rem', 'remilia', 2.5, 3, 'R', '蕾米莉亚（银章）', grade=2)
    c.actor('prey', 'reimu', 6.4, 3, 'L', '受保护的灵梦', grade=1)
    c.actor('fla', 'flandre', 10.0, 3, 'L', '芙兰（铜章）', grade=1)
    for dy in range(3):
        c.fill_cells(8, 0, 8, 5, 'minecraft:glass_pane', dy=dy)
    c.label(9.6, 1.0, 1.7, '单独关着', .4)
    c.demo('obey', '听令与忍住', [
        (50, [c.NOTE('主人在手册里下令：蕾米莉亚低头应答'), c.A('rem', 'bow'), c.S('rem', 'predator_obey')]),
        (110, [c.NOTE('路过受保护的猎物：忍住'), c.A('rem', 'restrain'), c.S('rem', 'predator_restrain')]),
        (30, [c.NOTE('银章 + 学会等待 + 知力够，才会服从')])])
    c.demo('defy', '不服从', [
        (70, [c.NOTE('芙兰：同样的命令，她不听'), c.A('fla', 'ignore'), c.S('fla', 'predator_defy')]),
        (50, [c.NOTE('铜章、没学会等待——所以单独关着')])])
    c.demo('flee', '猎物逃跑', [
        (50, [c.NOTE('没有保护的猎物看见捕食者'), c.A('prey', 'startle')]),
        (60, [c.A('prey', 'flee'), c.S('prey', 'afraid')]),
        (50, [c.NOTE('捕食者追捕时，主人会收到警报')])])


def society(c):
    c.actor('stock', 'alice', 1.6, 3, 'F', '在售的爱丽丝（银章）', grade=2)
    c.actor('clerk', 'caretaker_brother', 4.2, 3, 'L', '店主', job=1)
    c.actor('brother', 'caretaker_brother', 7.0, 3, 'R', '爱派大哥哥', job=0)
    c.actor('wild', 'marisa', 9.0, 3, 'L', '野生魔理沙')
    c.item('mcyukkuri:yukkuri_carrier', 3.0, 1.4, .7, 1.0)
    c.demo('shop', '展示求买', [
        (80, [c.NOTE('展示栏里的银章宠物向客人求买'), c.A('stock', 'beg'), c.S('stock', 'shop_appeal')]),
        (44, [c.NOTE('成交：开心跳起'), c.A('stock', 'joy_jump'), c.S('stock', 'shop_bought')]),
        (34, [c.NOTE('店主递出运输篮，宠物钻进去'), c.A('stock', 'basket_in')]),
        (50, [c.NOTE('带回家：价格看种族、徽章、馅统和能力'), c.A('stock', 'basket_out')])])
    c.demo('kind', '遇到爱派', [
        (60, [c.NOTE('爱派大哥哥路过：喂食、治疗'), c.A('wild', 'look_around')]),
        (60, [c.NOTE('饿着的野生个体凑过去'), c.A('wild', 'eat'), c.S('wild', 'fed')]),
        (60, [c.NOTE('吃饱了，放心地蹭一蹭'), c.A('wild', 'shiawase'), c.S('wild', 'treated')])])


# ======================================================================== HALL E  seasons and settlements
def autumn(c):
    c.actor('forager', 'reimu', 8.0, 3, 'F', '采集的灵梦')
    c.actor('resident', 'chen', 3.0, 3, 'F', '留守的橙')
    c.block(0, 4, 'mcyukkuri:cardboard_shelter')
    c.fill_cells(8, 0, 10, 0, 'minecraft:short_grass', dy=0)
    c.block(9, 1, 'minecraft:fern')
    c.block(10, 2, 'minecraft:short_grass')
    c.label(5.5, 5.0, 2.7, '秋：森林里采草和果实，往本窝存粮', .35)
    c.demo('gather', '采集与储粮', [
        (50, [c.NOTE('秋天：出门找草和果实'), c.A('forager', 'look_around'), c.S('forager', 'food_found')]),
        (40, [c.NOTE('采下一份'), c.A('forager', 'hop'), c.PROP_ON('haul', 'minecraft:wheat', 8.0, 3, 1.05, .4)])] +
        c.walk('forager', 8.0, 1.2, 3, 'L', steps=9, carry='haul') + [
        (50, [c.NOTE('搬回本窝，存进九格库存'), c.PROP_OFF('haul'), c.S('forager', 'house_deposit'), c.A('forager', 'shiawase')]),
        (60, [c.NOTE('留守的伙伴也分到一口'), c.A('resident', 'eat'), c.S('resident', 'take_food')])])
    c.stop_extra.append(c.PROP_OFF('haul')[3])


def winter(c):
    c.actor('mom', 'reimu', 8.5, 3, 'L', '灵梦')
    c.actor('kid', 'alice', 2.0, 3, 'F', '爱丽丝', stage='child')
    c.fill_cells(0, 0, 10, 5, 'minecraft:snow_block', dy=0)
    c.block(2, 4, 'mcyukkuri:forest_burrow')
    c.block(8, 5, 'minecraft:packed_ice')
    c.label(5.5, 5.0, 2.7, '冬：回本窝，吃存粮，有限让食', .35)
    c.demo('stay', '冬天的本窝', [
        (50, [c.NOTE('寒冷：发抖'), c.A('mom', 'tremble'), c.S('mom', 'cold')])] +
        c.walk('mom', 8.5, 3.6, 3, 'L', steps=8) + [
        (80, [c.NOTE('回到本窝，吃存粮'), c.A('mom', 'eat'), c.S('mom', 'take_food')]),
        (50, [c.NOTE('孩子比自己更饿：让食'), c.A('kid', 'beg'), c.S('mom', 'share')]),
        (60, [c.A('kid', 'eat'), c.S('kid', 'fed')]),
        (100, [c.NOTE('各自睡下，等春天'), c.A('mom', 'sleep'), c.A('kid', 'sleep')])])


def handson(c):
    """Real, free wild yukkuri behind a fence: spray it, basket it, let it out again."""
    c.ring(1, 1, 9, 5, gates={(5, 1): 'F'})
    c.fill_cells(2, 2, 8, 4, 'minecraft:moss_block', dy=-1)
    x, z = c.xz(5.5, 3.2)
    c.function('hands_spawn', ['execute unless entity @e[tag=pm_hands] run ' + wild_command('reimu', x, 66, z, ['pm_hands'], '野生灵梦（可以试试）')])
    c.function('hands_reset', ['kill @e[tag=pm_hands]', 'schedule function mcylab:stand/hands_spawn 1s replace',
                               'tellraw @a ' + _t('已换上新的野生个体。', 'green')])
    can_place = ('minecraft:can_place_on={predicates:[{blocks:"minecraft:polished_diorite"},{blocks:"minecraft:moss_block"},'
                 '{blocks:"minecraft:smooth_quartz"}],show_in_tooltip:false}')
    c.function('hands_kit', ['give @p mcyukkuri:sleep_spray 4', f'give @p mcyukkuri:yukkuri_carrier[{can_place}]',
                             f'give @p mcyukkuri:yukkuri_carrier[{can_place}]',
                             'tellraw @p ' + _t('给你弹珠汽水喷雾和运输篮：空手拿篮右键它会挣扎；先喷雾，睡着后再装篮；对地面右键放出。', 'aqua')])
    c.button('领喷雾和篮', '右键领取', 'hands_kit')
    c.button('换新野生', '右键补充', 'hands_reset')
    c.prop('function mcylab:stand/hands_spawn')
    c.label(5.5, 5.5, 2.4, '动手角：真实的野生个体', .5)


def settlement(c):
    """Real settlement through the operator command, in the fenced meadow east of hall E."""
    mx, my, mz = c.meadow
    c.function('settle_spawn', ['tellraw @a ' + _t('正在草地上建立一个有族长的森林聚落……（条件不够会提示失败）', 'yellow'),
                                f'yukkuri settlement spawn forest_led {mx} {my} {mz}'])
    c.function('settle_threat', [f'execute positioned {mx} {my} {mz} run yukkuri settlement threat 6',
                                 'tellraw @a ' + _t('聚落收到了威胁报告：看它们怎么应对。', 'yellow')])
    c.function('settle_migrate', [f'execute positioned {mx} {my} {mz} run yukkuri settlement migrate',
                                  'tellraw @a ' + _t('族长尝试迁居（需要真实可用的新址，失败属于正常）。', 'yellow')])
    c.function('settle_clear', [f'kill @e[type=#mcylab:yukkuri,x={mx},y={my},z={mz},distance=..30,tag=!pm_actor,tag=!pm_hands]',
                                'tellraw @a ' + _t('已清走草地上的个体。', 'yellow')])
    c.button('建立聚落', '真实生成', 'settle_spawn')
    c.button('增加威胁', '真实事件', 'settle_threat')
    c.button('尝试迁居', '真实事件', 'settle_migrate')
    c.button('清空草地', '重新开始', 'settle_clear')
    c.label(5.5, 3, 2.4, '聚落：走出东门，到草地上看\n族长、住户与真实的洞穴', .5)


def biomes(c):
    """Static board: the season follows the biome, not the clock."""
    for i, (block, text) in enumerate([('minecraft:grass_block', '春·平原'), ('minecraft:sand', '夏·炎热'),
                                       ('minecraft:oak_leaves[persistent=true]', '秋·森林'), ('minecraft:snow_block', '冬·雪原')]):
        u0 = i * 2.75
        c.fill_cells(int(u0), 1, int(u0) + 1, 3, block, dy=0)
        c.label(u0 + 1.0, 2.0, 1.4, text, .5)
    c.label(5.5, 4.8, 2.6, '季节由群系决定，不随时间轮转', .5)


# ======================================================================== registry
# (hall id, hall title, [(stand id, title, question, colour, builder)])
HALLS = [
    ('A', '身体与日常', 'cyan', [
        ('body', '身体如何活动', '柔软的身体，怎样走路、伸展和做体操？', 'cyan', body),
        ('daily', '悠闲的一天', '一餐、一觉、一次清洗：日常生活的节奏。', 'lime', daily),
        ('social', '表达与陪伴', '问候、蹭蹭和合唱，也有害怕、害羞与得意。', 'pink', social),
        ('care', '排泄与照料', '受伤时怎么被照料，便意来了去哪里。', 'red', care),
    ]),
    ('B', '家庭与成长', 'orange', [
        ('birth', '迎接新生命', '从安胎到初醒：亲本和帮手各自做什么。', 'orange', birth),
        ('feeding', '嚼糜与首便', '新生赤油要亲油嚼糜递喂、帮忙排便。', 'orange', feeding),
        ('hopping', '学起跳', '亲油示范，子油跟着练。', 'orange', hopping),
        ('growth', '长大与独立', '赤油、子油、亚成油、成油，以及离家那天。', 'orange', growth),
        ('house', '家油的巢与卵', '絮尘、窝、卵和刚孵出的家油。', 'yellow', house),
    ]),
    ('C', '水生魔理沙', 'light_blue', [
        ('hats', '魔理沙的帽子', '辫子取下、扶住、拿回：帽子也是照料工具。', 'yellow', hats),
        ('waterside', '水边的适应', '大帽与水边姿态；动作适合各自的形态。', 'light_blue', waterside),
        ('boatlife', '帽船上的一天', '备粮、划桨、吃喝、睡觉、返岸理帽。', 'cyan', boatlife),
        ('boatfamily', '船上育儿', '安胎、分娩、递食、亲昵与摇篮曲。', 'pink', boatfamily),
        ('voyage', '第一次出航', '放帽、跳进自己的帽船、试坐、划出去又回来。', 'lime', voyage),
        ('rescue', '落水与救援', '亲油低下帽缘、孩子咬缘滑进来；落水后自己抖落帽子、顶正再爬回。', 'red', museum_aquatic_rescue.rescue),
    ]),
    ('D', '人类社会', 'purple', [
        ('exam', '考场', '第一次考试：紧张、作答、躲猫猫，再看结果。', 'cyan', exam),
        ('badge', '徽章与旁人', '别上徽章之后，旁边的同类怎么想？', 'yellow', badges),
        ('date', '相亲', '饲养员牵线：害羞、靠近、蹭蹭。', 'pink', date),
        ('carry', '运输', '不肯进篮怎么办？喷弹珠汽水喷雾。', 'light_blue', carry),
        ('wild', '野良应对', '野生个体来讨吃或挑衅，宠物如何应对？', 'lime', wild),
        ('pred', '捕食种', '银章的蕾米莉亚听令，没学会的芙兰不听。', 'red', predators),
        ('society', '商店与人类', '展示求买、成交，以及不同立场的人类。', 'purple', society),
        ('hands', '动手角', '空手拿篮会被拒绝，喷雾之后才能装篮。', 'green', handson),
    ]),
    ('E', '季节与聚落', 'green', [
        ('autumn', '秋：储粮', '森林里采集，搬回本窝。', 'orange', autumn),
        ('winter', '冬：居巢', '寒冷时回窝，吃存粮，让食给孩子。', 'light_blue', winter),
        ('settle', '聚落与族长', '真实的森林聚落：住所、族长、威胁与迁居。', 'green', settlement),
        ('biomes', '季节从哪来', '春夏秋冬由群系决定，不由时钟决定。', 'white', biomes),
    ]),
]
