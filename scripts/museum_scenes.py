"""Stands of the showcase museum, grouped into halls.

Every stand is a small function that describes its actors, props and scripted scenes through ``Ctx``
(see museum_dsl.py). Body/expression scenes play authored clips. Real life fixtures hand control
to ordinary server goals and are never stopped by the museum when their observation window ends.
Courtyards (birth, house, boats) are configured afterwards by museum_birth, museum_house and
museum_hat_boats. What each station tells the visitor lives in museum_info.py.
"""
import sys

sys.dont_write_bytecode = True
from museum_dsl import Ctx, ticks, wild_command, _text as _t  # noqa: E402
import museum_aquatic_rescue  # noqa: E402
import museum_sites  # noqa: E402


# ======================================================================== HALL A  body and everyday life
def body(c):
    c.actor('body', 'reimu', 5.5, 3, 'F', '灵梦', named=False)
    c.demo('walk', '走路与跳跃', c.seq('body', 'waddle', 'turn', 'hop'))
    c.demo('stretch', '伸展与挤压', c.seq('body', 'stretch_shape', 'side_bend', 'stretch', 'press'))
    c.demo('exercise', '一起做体操', c.seq('body', 'exercise_vertical', 'exercise_sides', 'taiso'))


def daily(c):
    c.reset_life = True
    c.actor('daily', 'reimu', 5.5, 3, 'F', '灵梦', named=False)
    c.block(1, 3, 'mcyukkuri:food_bowl')
    c.block(9, 4, 'mcyukkuri:yukkuri_nest')
    c.demo('meal', '取食（真实）', c.live_run([c.LIVE('daily', 'meal'), c.SUPPLY('meal', 'minecraft:apple', 2.25, 3, 2)], 60,
        '灵梦饿了：看她自己走到苹果旁、拾起、咀嚼。', '看灵梦吃苹果'))
    c.demo('rest', '回窝休息（真实）', c.live_run([c.LIVE('daily', 'sleep'), c.HOME('daily', 9, 4)], 90,
        '灵梦困了：看她自己走回右边的窝睡觉；路被挡住时不会瞬移入窝。', '看灵梦回窝'))
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
    c.kind = 'open'
    c.reset_life = True
    c.actor('pat', 'chen', 4.5, 3, 'F', '橙')
    c.block(8, 3, 'mcyukkuri:yukkuri_toilet')
    toilet_x, toilet_z = c.cell(8, 3)
    c.demo('hurt', '受伤与照料', c.live_run([c.LIVE('pat', 'observe'),
        c.raw(f'yukkuri museum wound {c.sel("pat")}'),
        c.raw(f'execute at {c.sel("pat")} run give @p[distance=..20] mcyukkuri:orange_juice 1'),
        c.raw(f'execute at {c.sel("pat")} run give @p[distance=..20] mcyukkuri:bandage 1')], 90,
        '橙身上有了一处磕伤；橙汁和绷带已放进你的背包——拿在手上，对橙右键使用。', '给橙用橙汁和绷带'))
    c.demo('toilet', '厕所排便（真实）', c.live_run([c.LIVE('pat', 'toilet')], 90,
        '橙有便意了：看她自己走向右边的厕所、坐上去排便，然后看厕所里的污物。', '看橙上厕所'))
    c.demo('ground', '就地排便（真实）', c.live_run([c.LIVE('pat', 'toilet'),
        c.raw('setblock {} 66 {} minecraft:air'.format(toilet_x, toilet_z))], 90,
        '这次厕所被暂时移走了：看橙找不到厕所时在地面排便。按“重置本站”会放回厕所并清掉污物。', '看地面排泄物'))
    c.stop_extra.append('setblock {} 66 {} mcyukkuri:yukkuri_toilet keep'.format(toilet_x, toilet_z))
    c.stop_extra.append('data merge block {} 66 {} {{Waste:0,WastePortions:[]}}'.format(toilet_x, toilet_z))


# ======================================================================== HALL B  family and growth
def birth(c):
    """Portal stand: the real families live in the courtyards that museum_birth builds."""
    c.kind = 'portal'
    c.batch = False
    c.block(5, 3, 'mcyukkuri:yukkuri_nest')
    c.item('minecraft:leather_helmet', 3, 3, 1.1, .8)
    c.item('minecraft:leather_helmet', 7.5, 3, 1.1, .8)


def feeding(c):
    """Two independent pairs, so that one demonstration never inherits the food or hunger of the other."""
    c.reset_life = True
    c.actor('mom', 'chen', 1.7, 3, 'R', '橙（亲油·嚼糜）')
    c.actor('baby', 'reimu', 4.5, 3, 'L', '赤油（饿）', stage='baby')
    c.actor('mom2', 'chen', 7.0, 3, 'R', '橙（亲油·舔舐）')
    c.actor('baby2', 'reimu', 9.8, 3, 'L', '赤油（首便）', stage='baby')
    c.demo('feed', '左：嚼糜喂养（真实）', c.live_run([c.LIVE('mom'), c.LIVE('baby', 'meal'),
        c.PARENT('mom', 'baby'), c.SUPPLY('food', 'minecraft:apple', 3.1, 3, 3)], 90,
        '左边的赤油饿了：看亲油取苹果、嚼成糜、靠近嘴部喂给赤油。', '看亲油嚼糜喂食'))
    c.demo('toilet', '右：舔舐首便（真实）', c.live_run([c.LIVE('mom2'), c.LIVE('baby2', 'toilet'),
        c.PARENT('mom2', 'baby2', True)], 90,
        '右边的赤油还没有排过便：看亲油舔舐它的后侧，帮它排出第一次污物。', '看亲油舔舐首便'))


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


# Independence courtyard (west of the corridor, beside hall B). The cave shapes are fixed: the server-side
# independence setup surveys a south-facing mouth at HOME and another eight blocks east of it.
INDEP_HOME = (-32, 66)     # x of the old cave mouth, y of the floor


def growth(c):
    c.guard_live = True
    c.batch = False
    c.actor('baby', 'reimu', 1.4, 3, 'F', '赤油', stage='baby')
    c.actor('child', 'reimu', 3.8, 3, 'F', '子油', stage='child')
    c.actor('teen', 'reimu', 6.6, 3, 'F', '亚成油', stage='teen')
    c.actor('adult', 'reimu', 9.4, 3, 'F', '成油')
    c.block(0, 1, 'mcyukkuri:yukkuri_nest')
    hx, hz = INDEP_HOME[0], c.z0 + 6
    x1, x2, z1, z2 = hx - 6, hx + 17, hz - 7, hz + 8        # fence ring: -38..-15, 39..54
    c.regions.append((x1 - 2, z1 - 2, x2 + 6, z2 + 2))
    c.keep_loaded.append((x1, z1, x2 + 6, z2))     # independence takes ten minutes or more: the yard stays loaded
    c.prop(f'forceload add {x1} {z1} {x2 + 6} {z2}')
    c.prop(f'fill {x1} 64 {z1} {x2 + 3} 64 {z2} minecraft:dirt')
    c.prop(f'fill {x1} 65 {z1} {x2} 65 {z2} minecraft:grass_block')
    c.prop(f'fill {x1 + 1} 66 {z1 + 1} {x2 - 1} 69 {z2 - 1} minecraft:air')
    cells = [(x, z) for x in range(x1, x2 + 1) for z in range(z1, z2 + 1) if x in (x1, x2) or z in (z1, z2)]
    c.props.extend(museum_sites.fence_ring(cells, 66))
    for mouth in (hx, hx + 8):
        for dx in range(-2, 3):
            for depth in range(5):
                for dy in range(3):
                    solid = abs(dx) == 2 or depth == 4 or dy == 2 or depth == 0 and dx != 0
                    if solid:
                        c.prop(f'setblock {mouth + dx} {66 + dy} {hz - depth} minecraft:' + ('grass_block' if dy == 2 else 'dirt'))
    deck_lines, land = museum_sites.deck(x2 + 1, hz - 3, x2 + 3, hz + 3, 68, 'west', 'growth deck')
    c.props.extend(deck_lines)
    # the deck is far from the stage, so the answer goes to whoever pressed the button
    c.function('growth_deck_reset', ['execute store result score #r museum run function mcylab:stop/growth',
                                     'execute if score #r museum matches 1 run tellraw @s ' + _t('独立已重置：演员回到展台。', 'green'),
                                     'execute unless score #r museum matches 1 run tellraw @s ' + _t(
                                         '现在不能重置：独立中的两只还没有都回到安全的位置，或有成员未加载。稍后再试。', 'red')])
    c.props += museum_sites.deck_controls(c, x2 + 2, 68, hz, [('function mcylab:stand/growth_deck_reset', '重置独立', '演员回展台', True)])
    c.props.append(museum_sites.display(hx + 3.5, 70.5, hz + 3, [('独立庭院\n', 'gold', True), ('两处洞口：旧家（左）→ 新巢（右）', 'white')],
                                        .8, 'banner', 'indep yard'))
    c.sites.append(dict(id='indep', caption='去观察台', hint='独立庭院', land=land, visit='growth_visit', release=None))
    c.function('growth_visit', [f'tp @p {land[0]} {land[1]} {land[2]} {land[3]} 15',
                                'tellraw @p ' + _t('独立庭院观察台：左边是旧家，右边是即将建的新巢。回展台、回大厅的按钮在台上。', 'aqua')])
    c.demo('sizes', '一路长大', [
        (60, [c.NOTE('赤油：约 0.1–0.2 倍体型'), c.A('baby', 'look_around')]),
        (60, [c.NOTE('子油：学会起跳'), c.A('child', 'hop')]),
        (90, [c.NOTE('亚成油：能自己吃、自己睡'), c.A('teen', 'exercise_vertical')]),
        (90, [c.NOTE('成油：约 0.8–1.2 倍'), c.A('adult', 'stretch')])])
    fail = '独立没有开始：成年与亚成两只都要在场、是亲子、亚成已能独立生活，两个洞口要完好——请先按“重置本站”再试。'
    c.demo('leave', '开始独立（真实）', [
        (10, [c.LIVE('adult'), c.LIVE('teen'), c.PARENT('adult', 'teen'),
              c.raw(f'tp {c.sel("adult")} {hx + .5} 66 {hz - 1.5} 0 0'),
              c.raw(f'tp {c.sel("teen")} {hx + .5} 66 {hz + 1.5} 180 0')]),
        (10, [c.raw('execute store result score #ind museum run yukkuri museum independence '
                    f'@e[tag={c.tag("adult")},limit=1] @e[tag={c.tag("teen")},limit=1] {hx} 66 {hz}'),
              c.raw(f'execute unless score #ind museum matches 1 run tellraw {c.near()} ' + _t(fail, 'red')),
              c.raw(f'execute if score #ind museum matches 1 run tellraw {c.near()} ' + _t(
                  '成油和亚成油已被安置在独立庭院。去观察台（按 3）看：采叶、搬运、挖新巢，可能要十分钟以上。', 'aqua'))])] +
        c.live_run([], 600, None, '等待新巢完工', every=30))


def house(c):
    """Portal stand: the house-yukkuri courtyard is far to the south-west (museum_house)."""
    c.kind = 'portal'
    c.batch = False
    c.item('mcyukkuri:nest_lint', 3, 3, 1.0, .9)
    c.item('mcyukkuri:food_scrap', 5.5, 3, 1.0, .9)
    c.item('mcyukkuri:house_egg', 8, 3, 1.0, .9)


# ======================================================================== HALL C  aquatic Marisa
def boatlife(c):
    c.actor('sailor', 'marisa', 5.5, 3, 'F', '水生魔理沙', aquatic=True)
    c.water_floor()
    c.item('minecraft:stick', 2.0, 1.4, 1.0, .6)
    c.label(2.0, 1.4, 1.9, '桨：真实木棍', .5)


def voyage(c):
    c.actor('dad', 'marisa', 3.0, 3, 'R', '亲船魔理沙', aquatic=True)
    c.actor('kid', 'marisa', 7.8, 3, 'R', '小子油', stage='child', aquatic=True)
    c.water_floor()


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
    c.label(5.75, 3, 1.9, '银章：能与人同居', .5)
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
    c.label(6.0, 4.6, 1.9, '相亲 6 绿宝石', .5)
    walk = c.walk('bride', 3.2, 6.0, 3, 'R') + c.walk('stud', 9.0, 6.82, 3, 'L')
    c.demo('date', '饲养员相亲', [
        (40, [c.NOTE('双方已经到场；这是表情与接近片段')]),
        (60, [c.NOTE('和种油见面：害羞'), c.A('bride', 'shy'), c.S('bride', 'date_meet'), c.A('stud', 'shy')]),
        (50, [c.S('stud', 'date_meet')]),
        (4, [c.NOTE('慢慢走近')])] + walk + [
        (80, [c.NOTE('蹭蹭'), c.CONTACT('bride', 'stud'), c.FX('heart', 6.5, 3, .6, (.2, .2, .2), 0, 6)]),
        (80, [c.NOTE('相处融洽'), c.S('stud', 'date_fond'), c.A('bride', 'shiawase'), c.S('bride', 'expecting'),
              c.FX('heart', 6.2, 3, 1.2, (.5, .3, .3), 0, 8)])])


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
    c.label(9.6, 1.0, 1.9, '单独关着', .5)
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


def shop(c):
    c.kind = 'open'
    c.actor('stock', 'alice', 1.6, 3, 'F', '在售的爱丽丝（银章）', grade=2)
    c.actor('clerk', 'caretaker_brother', 4.2, 3, 'L', '店主', job=1)
    c.actor('brother', 'caretaker_brother', 7.0, 3, 'R', '爱派大哥哥', job=0)
    c.actor('wild', 'marisa', 9.0, 3, 'L', '野生魔理沙')
    c.basket(3.0, 1.4)
    c.reset_life = True
    c.demo('kind', '取食（真实）', c.live_run([c.LIVE('wild', 'meal'),
        c.SUPPLY('food', 'minecraft:apple', 7.35, 3, 1)], 60,
        '大哥哥身旁有一份食物：看野生魔理沙走过去拾起、咀嚼。', '看野生魔理沙取食'))


def transport(c):
    """Hands-on corner: a real, free wild yukkuri behind a fence, and a pet that can be basketed."""
    c.kind = 'open'
    c.reset_life = True
    c.actor('pet', 'reimu', 9.0, 3, 'F', '灵梦', grade=1)
    c.ring(0, 1, 6, 5, gates={(3, 1): 'F'})
    c.fill_cells(1, 2, 5, 4, 'minecraft:moss_block', dy=-1)
    x, z = c.xz(3.5, 3.2)
    c.function('hands_spawn', ['execute unless entity @e[tag=pm_hands] run ' + wild_command('reimu', x, 66, z, ['pm_hands'], '野生灵梦（可以试试）')])
    c.function('hands_reset', ['kill @e[tag=pm_hands]', 'schedule function mcylab:stand/hands_spawn 1s replace',
                               'tellraw ' + c.near() + ' ' + _t('已换上新的野生个体（围栏里）。', 'green')])
    can_place = ('minecraft:can_place_on={predicates:[{blocks:"minecraft:polished_diorite"},{blocks:"minecraft:moss_block"},'
                 '{blocks:"minecraft:smooth_quartz"}],show_in_tooltip:false}')
    kit = ['give @p mcyukkuri:sleep_spray 4', f'give @p mcyukkuri:yukkuri_carrier[{can_place}]',
           f'give @p mcyukkuri:yukkuri_carrier[{can_place}]']
    c.function('hands_kit', kit + ['tellraw @p ' + _t('给你弹珠汽水喷雾和运输篮：空手拿篮右键它会挣扎；先喷雾，睡着后再装篮；对地面右键放出。', 'aqua')])
    c.button('领喷雾和篮', '右键领取', 'hands_kit')
    c.demo('pet', '灵梦装篮', c.live_run([c.LIVE('pet'),
        c.raw(f'execute at {c.sel("pet")} run give @p[distance=..20] mcyukkuri:sleep_spray 2'),
        c.raw(f'execute at {c.sel("pet")} run give @p[distance=..20] mcyukkuri:yukkuri_carrier[{can_place}] 1')], 90,
        '右边的宠物灵梦自由走动：喷雾后装篮，再放出——它仍是同一只，身份不变。', '看装篮后身份'))
    c.button('换新野生', '放一只新的', 'hands_reset')
    c.prop('function mcylab:stand/hands_spawn')
    c.label(3.5, 5.5, 2.4, '动手角：围栏里是真实的野生个体', .55)


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
    c.reset_life = True
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
    c.label(5.5, 5.0, 2.8, '秋：森林里采草和果实，往本窝存粮', .5)
    c.demo('gather', '采集储粮（真实）', c.live_run([c.LIVE('forager', 'gather'),
        c.HOME('forager', 3, 3), c.LIVE('resident')], 120,
        '灵梦会割下右边的草、衔回洞口，把嘴贴在储粮堆上存入；看堆随库存长高。橙留守在窝里。', '看灵梦采草储粮'))


def winter(c):
    c.reset_life = True
    c.actor('mom', 'reimu', 8.5, 1, 'L', '灵梦')
    c.actor('kid', 'alice', 2.5, 3, 'F', '爱丽丝', stage='child')
    # Open-front cutaway: snow belongs below the actors, never around their bodies.
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
    c.label(3.0, 4.7, 2.4, '敞开剖面 · 共巢叶垫与门口的储粮堆', .5)
    for i in range(interior_start, len(c.props)):
        c.props[i] = c.props[i].replace('Tags:[', 'Tags:["pm_winter_interior",', 1)
    layout = c.props[:]
    c.props.clear()
    c.function('winter_layout', layout)
    c.prop('function mcylab:stand/winter_layout')
    c.label(5.5, 5.0, 2.8, '冬：回本窝，吃存粮，有限让食', .5)
    c.demo('stay', '回窝取粮（真实）', c.live_run([c.LIVE('mom', 'meal'), c.LIVE('kid', 'meal'),
        c.PARENT('mom', 'kid'), c.HOME('mom', 3, 3), c.HOME('kid', 3, 3)], 120,
        '本站正在下雪（群系是雪原）：看两只待在窝里，走到门口的储粮堆边用嘴取食，观察让食。', '看冬季回窝取粮'))
    near = c.near()
    c.function('winter_snow', ['weather rain 1000000', 'tellraw ' + near + ' ' + _t('开始下雪（全馆天气）。冬季的油在雪天不会外出，会待在窝里。', 'aqua')])
    c.function('winter_night', ['time set midnight', 'tellraw ' + near + ' ' + _t('改为深夜。冬季的油在夜里回窝；离站请按“放晴”。', 'aqua')])
    c.function('winter_clear', ['weather clear', 'time set noon', 'tellraw ' + near + ' ' + _t('已恢复正午晴天。', 'green')])
    c.button('下雪', '全馆天气', 'winter_snow')
    c.button('夜晚', '全馆时间', 'winter_night')
    c.button('放晴', '恢复正午', 'winter_clear')


def settlement(c):
    """Persistently owned real settlement; replay never deletes unregistered meadow residents."""
    mx, my, mz = c.meadow
    c.function('settle_spawn', [f'yukkuri museum settlement start {mx} {my} {mz}'])
    c.function('settle_forage', ['yukkuri museum settlement order forage'])
    c.function('settle_migrate', ['yukkuri museum settlement migrate'])
    c.function('settle_clear', ['yukkuri museum settlement reset'])
    c.button('建立聚落', '真实生成', 'settle_spawn')
    c.button('执行采集', '真实派工', 'settle_forage')
    c.button('尝试迁居', '真实事件', 'settle_migrate')
    c.button('安全重置', '仅本轮成员', 'settle_clear')
    c.kind = 'portal'
    c.batch = False


# Season demonstration: one pad on the 4x4 biome grid whose biome can be switched; Seasons.at reads the biome tags.
SEASONS = (('spring', '春', 'minecraft:plains', 'minecraft:grass_block', '平原'),
           ('summer', '夏', 'minecraft:desert', 'minecraft:sand', '沙漠'),
           ('autumn', '秋', 'minecraft:forest', 'minecraft:podzol', '森林'),
           ('winter', '冬', 'minecraft:snowy_plains', 'minecraft:snow_block', '雪原'))


def biomes(c):
    c.kind = 'view'
    cx, cz = c.centre()
    px, pz = int(cx // 4 * 4), int(cz // 4 * 4)
    sz1, sz2 = (c.z0 + 24, c.z0 + 29) if c.row == 'S' else (c.z0 + 2, c.z0 + 7)
    fx1, fx2, fz1, fz2 = max(px, c.sx), min(px + 3, c.sx + 10), max(pz, sz1), min(pz + 3, sz2)
    report = []
    for tag, label, _, _, _ in (SEASONS[3], SEASONS[2], SEASONS[1], SEASONS[0]):
        report.append(f'execute if biome {px + 1} 66 {pz + 1} #mcyukkuri:season_{tag} run return run tellraw {c.near()} '
                      + _t(f'这块地现在判定为【{label}季】（由群系标签决定）。', 'gold'))
    report.append(f'tellraw {c.near()} ' + _t('这个群系没有季节标签，按基础温度判断。', 'gray'))
    c.function('season_report', report)
    for tag, label, biome, ground, name in SEASONS:
        c.function(f'season_{tag}', [
            f'fillbiome {px} 60 {pz} {px + 3} 79 {pz + 3} {biome}',
            f'fill {fx1} 65 {fz1} {fx2} 65 {fz2} {ground}',
            f'tellraw {c.near()} ' + _t(f'群系改成【{name}】。', 'aqua'),
            'function mcylab:stand/season_report'])
        c.button(f'{label}（{name}）', '改群系', f'season_{tag}')
    c.prop(f'fillbiome {px} 60 {pz} {px + 3} 79 {pz + 3} minecraft:plains')
    c.prop(f'fill {fx1} 65 {fz1} {fx2} 65 {fz2} minecraft:grass_block')
    c.label(5.5, 3.2, 2.6, '季节由群系决定，不随时钟轮转', .55)


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
        ('birth', '迎接新生命', '陆地与船上的真实生产：接住，或漏接。', 'orange', birth),
        ('feeding', '嚼糜与首便', '新生赤油要亲油嚼糜递喂、帮忙排便。', 'orange', feeding),
        ('hopping', '学起跳', '亲油示范，子油跟着练。', 'orange', hopping),
        ('growth', '长大与独立', '赤油、子油、亚成油、成油，以及离家那天。', 'orange', growth),
        ('house', '家油的巢与卵', '絮尘、窝、卵和刚孵出的家油。', 'yellow', house),
    ]),
    ('C', '水生魔理沙', 'light_blue', [
        ('boatlife', '水边与帽船', '水边适应、备粮划桨、回岸收帽，以及不同载荷。', 'cyan', boatlife),
        ('voyage', '第一次出航', '放帽、跳进自己的帽船、试坐、划出去又回来。', 'lime', voyage),
        ('rescue', '落水与救援', '亲油低下帽缘、孩子咬缘滑进来；落水后自己抖落帽子、顶正再爬回。', 'red', museum_aquatic_rescue.rescue),
    ]),
    ('D', '人类社会', 'purple', [
        ('exam', '考场', '第一次考试：紧张、作答、躲猫猫，再看结果。', 'cyan', exam),
        ('badge', '徽章与旁人', '别上徽章之后，旁边的同类怎么想？', 'yellow', badges),
        ('wild', '野良应对', '野生个体来讨吃或挑衅，宠物如何应对？', 'lime', wild),
        ('pred', '捕食种', '银章的蕾米莉亚听令，没学会的芙兰不听。', 'red', predators),
        ('date', '相亲', '饲养员牵线：害羞、靠近、蹭蹭。', 'pink', date),
        ('shop', '商店与人类', '店主、大哥哥，与在售的油。', 'purple', shop),
        ('transport', '运输与动手角', '不肯进篮怎么办？喷弹珠汽水喷雾，再亲手试一试。', 'light_blue', transport),
    ]),
    ('E', '季节与聚落', 'green', [
        ('biomes', '季节从哪来', '春夏秋冬由群系决定，不由时钟决定。', 'white', biomes),
        ('autumn', '秋：储粮', '森林里采集，搬回本窝。', 'orange', autumn),
        ('winter', '冬：居巢', '寒冷时回窝，吃存粮，让食给孩子。', 'light_blue', winter),
        ('settle', '聚落与族长', '真实的森林聚落：住所、族长、威胁与迁居。', 'green', settlement),
    ]),
]
