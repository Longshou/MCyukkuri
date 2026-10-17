"""Stands of the showcase museum, grouped into halls.

Every stand is a small function that describes its actors, props and scripted scenes through ``Ctx``
(see museum_dsl.py). Body/expression scenes play authored clips. Real life fixtures hand control
to ordinary server goals. The final layout configures aquatic, birth and house courtyards through
museum_hat_boats, museum_birth and museum_house after collecting these base stand definitions.
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
    c.demo('meal', '真实取食', [(1200, [c.LIVE('daily', 'meal'),
        c.SUPPLY('meal', 'minecraft:apple', 2.25, 3, 2),
        c.NOTE('两份真实苹果：自行靠近、拾取、咀嚼；以剩余物品和饱食状态为准')])])
    c.demo('rest', '真实回窝休息', [(1600, [c.LIVE('daily', 'sleep'), c.HOME('daily', 9, 4),
        c.NOTE('体力不足后自行回窝；路径受阻时不会传送入窝')])])
    c.demo('wash', '洗后甩甩水', c.seq('daily', 'shake'))


def social(c):
    c.actor('friend', 'chen', 3.5, 3, 'F', '橙', named=False)
    c.actor('mate', 'alice', 7.5, 3, 'F', '爱丽丝', named=False)
    c.demo('hello', '相遇与蹭蹭', [
        (80, [c.A('friend', 'look_around'), c.A('mate', 'idle')]),
        (32, [c.FACE('friend', 'R'), c.FACE('mate', 'L'), c.A('friend', 'greet'), c.A('mate', 'greet'), c.S('friend', 'hello'), c.S('mate', 'peer')])] +
        c.walk('friend', 3.5, 7.5 - c.contact_distance('friend', 'mate'), 3, 'R', steps=16) + [
        (64, [c.CONTACT('friend', 'mate'), c.S('friend', 'nuzzle')])])
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
    c.demo('hurt', '实际工具照护', [(1200, [c.LIVE('pat', 'meal'),
        c.raw(f'execute at {c.sel("pat")} run give @p[distance=..20] mcyukkuri:orange_juice 1'),
        c.raw(f'execute at {c.sel("pat")} run give @p[distance=..20] mcyukkuri:bandage 1'),
        c.NOTE('已提供真实道具：持工具对橙使用；只有实际接触与消耗才会产生效果')])])
    c.demo('toilet', '真实厕所排便', [(1200, [c.LIVE('pat', 'toilet'),
        c.NOTE('便意已设置；自行走向厕所、接触坐位并排便，观察厕所污物')])])
    c.demo('ground', '真实地面排便', [(1200, [c.LIVE('pat', 'toilet'),
        c.raw('setblock {} 66 {} minecraft:air'.format(*c.cell(8, 3))),
        c.NOTE('本次暂移去厕所，观察真实地面排泄物')])])
    c.stop_extra.append('setblock {} 66 {} mcyukkuri:yukkuri_toilet keep'.format(*c.cell(8, 3)))


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
    c.demo('feed', '真实嚼糜喂养', [(1800, [c.LIVE('mom'), c.LIVE('baby', 'meal'),
        c.PARENT('mom', 'baby'), c.SUPPLY('food', 'minecraft:apple', 3.4, 3, 3),
        c.NOTE('真实亲子与三份口粮：亲油取粮、嚼糜、靠近嘴部后转移；不预报成功')])])
    c.demo('toilet', '真实舔舐首便', [(1800, [c.LIVE('mom'), c.LIVE('baby', 'toilet'),
        c.PARENT('mom', 'baby', True),
        c.NOTE('幼体尚未完成首便：等待亲油实际舔舐后侧并排出真实污物')])])


def hopping(c):
    c.actor('mom', 'reimu', 3.5, 3, 'F', '亲本灵梦')
    c.actor('kid', 'chen', 7.5, 3, 'F', '子油橙', stage='child')
    c.demo('hop', '学起跳', [
        (50, [c.NOTE('子油先爬行、试探'), c.A('kid', 'look_around')]),
        (60, [c.NOTE('亲油示范起跳'), c.FACE('kid', 'L'), c.A('mom', 'hop'), c.S('mom', 'lesson_start')]),
        (60, [c.NOTE('子油跟着练'), c.FACE('kid', 'F'), c.FACE('mom', 'R'), c.A('kid', 'hop')]),
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
    hx, hz = -32, c.z0 + 6
    c.prop(f'fill {hx-3} 65 {hz-5} -12 65 {hz+5} minecraft:grass_block')
    c.prop(f'forceload add {hx-3} {hz-5} {hx+11} {hz+5}')
    for mouth in (hx, hx+8):
        for dx in range(-2, 3):
            for depth in range(5):
                for dy in range(3):
                    solid = abs(dx) == 2 or depth == 4 or dy == 2 or depth == 0 and dx != 0
                    if solid:
                        c.prop(f'setblock {mouth+dx} {66+dy} {hz-depth} minecraft:' + ('grass_block' if dy == 2 else 'dirt'))
    c.function('growth_visit', [f'tp @p {hx+4} 66 {hz+4} 180 0',
                                'tellraw @p ' + _t('西侧独立庭院：两处真实洞口，三份叶料。点击展台“开始独立”后观察。', 'aqua')])
    c.button('前往独立庭院', '真实寻路与施工', 'growth_visit')
    c.demo('leave', '开始真实独立', [
        (10, [c.LIVE('adult'), c.LIVE('teen'), c.PARENT('adult', 'teen'),
              c.raw(f'tp {c.sel("adult")} {hx+.5} 66 {hz-1.5} 0 0'),
              c.raw(f'tp {c.sel("teen")} {hx+.5} 66 {hz+1.5} 180 0'),
              c.NOTE('初始演员已安置到西侧庭院；随后全程真实导航、采集和施工')]),
        (12000, [c.raw(f'yukkuri museum independence @e[tag={c.tag("adult")},limit=1] '
                       f'@e[tag={c.tag("teen")},limit=1] {hx} 66 {hz}'),
                 c.NOTE('等待实际材料到达、施工和新巢认领；受阻保留旧巢与物资，不传送补完')])])


def house(c):
    c.actor('hm', 'house_marisa', 3.5, 3, 'F', '家油魔理沙')
    c.actor('hr', 'house_reimu', 8.0, 3.2, 'F', '家油灵梦')
    for u in (1, 7):
        c.block(u - 1, 4, 'minecraft:oak_planks')
        c.block(u - 1, 5, 'minecraft:oak_planks')
        c.block(u, 5, 'minecraft:oak_planks')
        c.block(u, 4, 'mcyukkuri:house_dust_nest',
                state='{Materials:0,Food:3,Arrivals:2,Entrance:"' + c.facing('F') + '"}')
    c.label(5.5, 5.0, 2.7, '真实墙角巢：入口、库存与卵均由玩法管理', .35)
    c.demo('arrive', '拾絮与真实入巢', [(2400, [c.LIVE('hm', 'house'), c.HOME('hm', 1, 4),
        c.SUPPLY('lint', 'mcyukkuri:nest_lint', 4.0, 3, 4),
        c.NOTE('四份真实棉絮：夹起、衔持、沿入口回巢，巢中材料数实际增加')])])
    c.demo('egg', '实际巢卵照护', [(2400, [c.LIVE('hr', 'house'), c.HOME('hr', 7, 4),
        c.SUPPLY('food', 'minecraft:apple', 6.0, 3, 3),
        c.NOTE('持续家油生态：已有巢卵才会照护和孵化；没有卵时先观察筑巢与取食')])])


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
        (60, [c.NOTE('回到干岸，用辫子理帽'), c.A('sailor', 'aquatic_hat_care')]),
        (40, [c.NOTE('甩去水滴'), c.A('sailor', 'shake')]),
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
        (60, [c.NOTE('近身递食'), c.A('mom', 'boat_feed_young'), c.A('baby', 'eat'), c.CONTACT('mom', 'baby', 'feed'), c.S('baby', 'fed')]),
        (60, [c.NOTE('亲昵'), c.A('mom', 'boat_nuzzle'), c.CONTACT('mom', 'baby')])])
    c.demo('lullaby', '摇篮曲', [
        (120, [c.NOTE('夜里哼起摇篮曲'), c.SHOW('baby', 6.2, 3, 'L'), c.A('mom', 'boat_lullaby'), c.A('baby', 'sway')]),
        (100, [c.NOTE('赤油睡着了'), c.A('baby', 'sleep')])])


def voyage(c):
    c.actor('dad', 'marisa', 3.0, 3, 'R', '亲船魔理沙', aquatic=True)
    c.actor('kid', 'marisa', 7.8, 3, 'R', '小子油', stage='child', aquatic=True)
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
    hide_u = .6 if passed else 1.05
    steps = [
        (60, [c.NOTE('考官在场，考生有点紧张……'), c.CARD('exam', '考试中', 'yellow'), c.A('kid', 'nervous'), c.S('kid', 'exam_nervous')]),
        (70, [c.NOTE('开始答题：思考作答'), c.A('kid', 'think'), c.S('kid', 'exam_answer')]),
        (10, [c.NOTE('他者视角：考官背过身，考生要找躲藏箱'), c.FACE('judge', 'R')]),
    ]
    steps += c.walk('kid', 4.5, hide_u, 3, 'L', steps=8, y=66)
    steps += [
        (60, [c.NOTE('躲猫猫：低伏、脸顶进箱子（新的小幅姿态）'), c.A('kid', 'hide_face')] + ([c.S('kid', 'exam_hide_fail')] if not passed else [])),
        (40, [c.NOTE('考官转回来找人' if passed else '屁股露在外面，被找到了'), c.FACE('judge', 'L')]),
    ]
    steps += c.walk('kid', hide_u, 4.5, 3, 'R', steps=8, y=66.4375)
    if passed:
        steps += [
            (50, [c.CARD('exam', '合格！铜章', 'green'), c.NOTE('合格'), c.A('kid', 'joy_jump'), c.S('kid', 'exam_pass'),
                  c.FX('happy_villager', 4.5, 3, 1.4, (.4, .3, .4), 0, 10)])]
        steps += (
            c.walk('judge', 9.2, 9.2, 3, 'F', v1=1.5) +
            c.walk('judge', 9.2, 5.4, 1.5, 'L') +
            c.walk('judge', 5.4, 5.4, 1.5, 'B', v1=3) + [
            (30, [c.NOTE('考官走到身边，把铜章别上'), c.FACE('judge', 'L'), c.BADGE('kid', 1), c.FX('glow', 4.5, 3, 1.0, (.3, .2, .3), .02, 8)]),
            (60, [c.NOTE('得意：竖向拉伸、挺胸'), c.A('kid', 'proud'), c.S('kid', 'badge_proud')]),
            (100, [c.NOTE('铜章戴在饰物上，代表登记过的宠物')]),
        ])
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
        (60, [c.NOTE('旁边的野生个体注意到了'), c.FACE('wild', 'L'), c.A('wild', 'nervous')]),
        (70, [c.NOTE('羡慕'), c.A('wild', 'beg'), c.S('wild', 'badge_admire')]),
        (70, [c.NOTE('银章：能与人同居')])])
    c.demo('envy', '嫉妒与摘章', [
        (50, [c.NOTE('戴着银章的宠物'), c.BADGE('pet', 2), c.A('pet', 'proud'), c.S('pet', 'badge_proud')]),
        (50, [c.NOTE('野生个体盯着徽章'), c.FACE('wild', 'L'), c.A('wild', 'nervous')]),
        (70, [c.NOTE('嫉妒'), c.A('wild', 'puff'), c.S('wild', 'badge_envy')]),
        (70, [c.NOTE('主人摘下徽章（登记仍属于它）'), c.BADGE('pet', 0), c.A('pet', 'droop'), c.S('pet', 'badge_taken')])])


def date(c):
    c.actor('bride', 'reimu', 3.2, 3, 'R', '宠物灵梦', grade=1)
    c.actor('stud', 'marisa', 9.0, 3, 'L', '种油魔理沙', grade=2)
    c.actor('keeper', 'caretaker_brother', 6.0, 4.6, 'F', '饲养员', job=4)
    c.label(6.0, 4.6, 1.9, '相亲 6 绿宝石', .4)
    walk = c.walk('bride', 3.2, 6.0, 3, 'R') + c.walk('stud', 9.0, 6.82, 3, 'L')
    c.demo('date', '饲养员相亲', [
        (40, [c.NOTE('双方已经到场；这是表情与接近片段，真实收容请用运输展台')]),
        (60, [c.NOTE('和种油见面：害羞'), c.A('bride', 'shy'), c.S('bride', 'date_meet'), c.A('stud', 'shy')]),
        (50, [c.S('stud', 'date_meet')]),
        (4, [c.NOTE('慢慢走近')])] + walk + [
        (80, [c.NOTE('蹭蹭'), c.CONTACT('bride', 'stud'), c.FX('heart', 6.5, 3, .6, (.2, .2, .2), 0, 6)]),
        (80, [c.NOTE('相处融洽'), c.S('stud', 'date_fond'), c.A('bride', 'shiawase'), c.S('bride', 'expecting'),
              c.FX('heart', 6.2, 3, 1.2, (.5, .3, .3), 0, 8)])])


def carry(c):
    c.actor('wild', 'marisa', 3.0, 3, 'F', '野生魔理沙')
    c.actor('pet', 'reimu', 8.0, 3, 'F', '灵梦', grade=1)
    for key, label in (('wild', '野生收容实操'), ('pet', '真实篮内身份')):
        c.demo(key, label, [(2400, [c.LIVE(key),
            c.raw(f'execute at {c.sel(key)} run give @p[distance=..20] mcyukkuri:yukkuri_carrier[minecraft:can_place_on={{predicates:[{{blocks:"minecraft:polished_diorite"}},{{blocks:"minecraft:smooth_quartz"}}],show_in_tooltip:false}}] 1'),
            c.raw(f'execute at {c.sel(key)} run give @p[distance=..20] mcyukkuri:sleep_spray 1'),
            c.NOTE('使用实际喷雾与收容篮；收容、放出均保留同一只实体身份。停止前先放回展台')])])


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
        (50, [c.NOTE('宠物没忍住：鼓腮'), c.A('guard', 'puff')])] +
        c.walk('guard', 7.5, 7.5, 3, 'F', steps=8, v1=1.5) +
        c.walk('guard', 7.5, 4.6 + c.contact_distance('guard', 'beggar'), 1.5, 'L', steps=12) +
        c.walk('guard', 5.4, 5.4, 1.5, 'B', steps=8, v1=3) + [
        (14, [c.NOTE('近身推挤（压力 +2）'), c.FACE('guard', 'L'), c.A('guard', 'push')]),
        (40, [c.A('beggar', 'startle')]),
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
    c.basket(3.0, 1.4)
    c.demo('shop', '实际商店交互', [(1200, [c.NOTE('与店主交互打开真实商店；商品、成交与收容使用实际库存')])])
    c.demo('kind', '真实取食', [(1200, [c.LIVE('wild', 'meal'),
        c.SUPPLY('food', 'minecraft:apple', 7.35, 3, 1),
        c.NOTE('大哥哥身旁的一份实际食物：靠近拾取后才咀嚼')])])



# ======================================================================== HALL E  seasons and settlements
FACING_2D = {'south': 0, 'west': 1, 'north': 2, 'east': 3}
OPPOSITE = {'south': 'north', 'north': 'south', 'east': 'west', 'west': 'east'}


def _packed_pos(c, bu, bv, y=66):
    """BlockPos.asLong of a stage cell, for block-entity data."""
    x, z = c.cell(bu, bv)
    value = ((x & 0x3ffffff) << 38) | ((z & 0x3ffffff) << 12) | y
    return value if value < 1 << 63 else value - (1 << 64)


def _den(c, lining=4, branches=2, items=''):
    """Open-front earth cutaway with one forest burrow and its forage pile (储粮堆).

    Beds are cells (3,3) and (2,3); the mouth is (3,2) and the pile sits one cell further toward the viewer at (3,1),
    its feeding side facing the mouth. The pile is the nest's only food store: nothing is dropped on the ground. ``items``
    is optional block-entity data for the pile's starting contents."""
    c.fill_cells(1, 2, 4, 4, 'minecraft:coarse_dirt', dy=-1)
    c.fill_cells(0, 3, 0, 4, 'minecraft:dirt')
    c.fill_cells(5, 3, 5, 4, 'minecraft:dirt')
    c.fill_cells(0, 5, 5, 5, 'minecraft:dirt')
    # A bare forest_burrow has neither beds nor lining and dismantles itself, so the site data is written too.
    c.block_facing(3, 3, 'mcyukkuri:forest_burrow', 'F')
    bx, bz = c.cell(3, 3)
    px, pz = c.cell(3, 1)
    mouth_side = FACING_2D[OPPOSITE[c.facing('F')]]
    c.prop(f'setblock {px} 66 {pz} mcyukkuri:forage_pile')
    c.prop(f'data merge block {px} 66 {pz} {{Approach:{mouth_side},Owner:{_packed_pos(c, 3, 3)}L{items}}}')
    c.prop(f'data merge block {bx} 66 {bz} {{Condition:100,Lining:{lining},Branches:{branches},'
           f'NaturalBeds:[L;{_packed_pos(c, 3, 3)}L,{_packed_pos(c, 2, 3)}L],NaturalEntrance:{_packed_pos(c, 3, 2)}L,'
           f'Pile:{_packed_pos(c, 3, 1)}L}}')
    return (bx, bz), (px, pz)


def autumn(c):
    c.actor('forager', 'reimu', 8.5, 3, 'F', '采集的灵梦')
    c.actor('resident', 'chen', 6.5, 4, 'F', '留守的橙')
    # Gathering runs in any season while the store is below its target; the biome only raises that target in autumn.
    _, pile = _den(c)
    grass = [(7, 0), (8, 0), (9, 0), (10, 0), (7, 1), (8, 1), (10, 1), (7, 2), (8, 2), (9, 2), (10, 2)]
    c.fill_cells(7, 0, 10, 2, 'minecraft:grass_block', dy=-1)
    for bu, bv in grass:
        c.block(bu, bv, 'minecraft:short_grass')
    c.block(9, 1, 'minecraft:fern')
    # Every run starts from the same meadow and an empty pile; replacing only air never touches a block someone placed.
    for bu, bv in grass:
        x, z = c.cell(bu, bv)
        c.stop_extra.append(f'setblock {x} 66 {z} minecraft:short_grass keep')
    x, z = c.cell(9, 1)
    c.stop_extra.append(f'setblock {x} 66 {z} minecraft:fern keep')
    c.stop_extra.append(f'data modify block {pile[0]} 66 {pile[1]} Items set value []')
    c.label(5.5, 5.0, 2.7, '秋：森林里采草和果实，往本窝存粮', .35)
    c.demo('gather', '真实采集与储粮', [(2400, [c.LIVE('forager', 'gather'),
        c.HOME('forager', 3, 3), c.LIVE('resident'),
        c.NOTE('割下真实的草、衔着走回洞口，把嘴贴在储粮堆上存入；看堆随库存长高')])])



def winter(c):
    c.actor('mom', 'reimu', 8.5, 1, 'L', '灵梦')
    c.actor('kid', 'alice', 2.5, 3, 'F', '爱丽丝', stage='child')
    # Open-front cutaway: snow belongs below the actors, never around their bodies.
    # Reapply on stop/reload so existing museums lose the old full-block snow cover.
    for dy in range(3):
        c.fill_cells(0, 0, 10, 5, 'minecraft:air', dy=dy)
    c.fill_cells(0, 0, 10, 5, 'minecraft:snow_block', dy=-1)
    c.fill_cells(1, 2, 4, 4, 'minecraft:coarse_dirt', dy=-1)
    c.fill_cells(0, 3, 0, 4, 'minecraft:dirt')
    c.fill_cells(5, 3, 5, 4, 'minecraft:dirt')
    c.fill_cells(0, 5, 5, 5, 'minecraft:dirt')
    c.fill_cells(0, 5, 5, 5, 'minecraft:snow_block', dy=1)
    c.block(2, 5, 'minecraft:shroomlight', dy=1)
    c.block(8, 5, 'minecraft:packed_ice')

    # A bare forest_burrow has neither beds nor lining and dismantles itself. Give the two reusable actors adjacent
    # shared leaf beds and a real forage pile at the mouth holding the winter stock.
    (bx, bz), (px, pz) = _den(c, branches=0, items=',Items:[{Slot:0b,id:"minecraft:apple",count:3},{Slot:1b,id:"minecraft:wheat",count:5}]')
    at = f'{bx} 66 {bz}'
    c.prop(f'data merge block {at} {{Residents:[{{Id:[I;0,0,0,1],Bed:0}},{{Id:[I;0,0,0,2],Bed:1}}]}}')
    for i, key in enumerate(('mom', 'kid')):
        c.prop(f'execute if entity {c.sel(key)} run data modify block {at} Residents[{i}].Id '
               f'set from entity @e[tag={c.tag(key)},limit=1] UUID')
    c.prop('kill @e[tag=pm_winter_interior]')
    interior_start = len(c.props)
    c.label(3.0, 4.7, 2.2, '敞开剖面 · 共巢叶垫与门口的储粮堆', .3)
    for i in range(interior_start, len(c.props)):
        c.props[i] = c.props[i].replace('Tags:[', 'Tags:["pm_winter_interior",', 1)
    layout = c.props[:]
    c.props.clear()
    c.function('winter_layout', layout)
    c.prop('function mcylab:stand/winter_layout')
    c.label(5.5, 5.0, 2.7, '冬：回本窝，吃存粮，有限让食', .35)
    c.demo('stay', '真实回窝与取粮', [(2400, [c.LIVE('mom', 'meal'), c.LIVE('kid', 'meal'),
        c.PARENT('mom', 'kid'), c.HOME('mom', 3, 3), c.HOME('kid', 3, 3),
        c.NOTE('有限口粮在门口的储粮堆里：走到堆边用嘴取食，观察让食条件；不会用字幕授予食物')])])



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
    """Persistently owned real settlement; replay never deletes unregistered meadow residents."""
    mx, my, mz = c.meadow
    c.function('settle_spawn', [f'yukkuri museum settlement start {mx} {my} {mz}'])
    c.function('settle_forage', ['yukkuri museum settlement order forage'])
    c.function('settle_scout', ['yukkuri museum settlement order scout'])
    c.function('settle_message', ['yukkuri museum settlement order message'])
    c.function('settle_threat', ['yukkuri museum settlement threat 6'])
    c.function('settle_status', ['yukkuri museum settlement status'])
    c.function('settle_migrate', ['yukkuri museum settlement migrate'])
    c.function('settle_clear', ['yukkuri museum settlement reset'])
    c.button('建立聚落', '真实生成', 'settle_spawn')
    c.button('执行采集', '真实派工', 'settle_forage')
    c.button('尝试迁居', '真实事件', 'settle_migrate')
    c.button('安全重置', '仅本轮成员', 'settle_clear')
    c.label(5.5, 3, 2.4, '聚落：走出东门，到草地上看\n采集结果看各户门口的储粮堆；成员未加载或收容时不能重置', .5)


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
