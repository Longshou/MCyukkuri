"""Scenarios of the playground: one question each, answered by real individuals under their ordinary AI.

A scenario is a list of commands run positioned at the field's anchor block (see museum_dsl). It only creates the
individuals and props and sets their real starting state (hunger, tiredness, life stage, kin, home, conception, a
wound, a hat boat afloat ...). After that nothing is scripted, fixed or invulnerable: the observer watches, speeds the
clock up, flies to the lead, or clears the field and starts again.
"""
import sys

sys.dont_write_bytecode = True
from museum_dsl import (yk, human, item, block, box, shed, home, sel, sel_all, say, _text)  # noqa: E402
from museum_fields import BY_ID  # noqa: E402

CHILDHOOD_CAN_JUMP_SOON = 'Version:1,Newborn:1b,Landed:1b,FirstToiletDone:1b,Crawled:1b,Jumped:0b,CrawlDistance:1.0d'
OWNED = 'NeoForgeData.HumanCommerce'


class Scenario:
    def __init__(self, sid, field, title, hint, watch, wait, lines, give=(), env=None, anchor=None, retry='先按“清空本场地”，再重新生成'):
        self.id, self.field, self.title, self.hint = sid, field, title, hint
        self.watch, self.wait, self.retry = watch, wait, retry
        self.lines, self.give, self.env = list(lines), list(give), env or {}
        self.anchor = anchor or BY_ID[field].anchor

    def body(self):
        """Commands of ``scn/<id>``: heading, the scenario itself, items for the player."""
        head = [say(f'【{self.title}】看点：{self.watch}', 'aqua'), say(f'预计：{self.wait}。没反应：{self.retry}。', 'yellow')]
        return head + self.lines + [f'give @s {g}' for g in self.give]


def own(name):
    """Make the executing player the owner of this scenario pet (a companion), the way the shop writes it."""
    return [f'data modify entity {sel(name)} {OWNED} set value {{Use:"companion"}}',
            f'data modify entity {sel(name)} {OWNED}.Owner set from entity @s UUID']


def pair_ready():
    return dict(nutrition=100, energy=100, hygiene=100)


S = []


def add(*args, **kw):
    scenario = Scenario(*args, **kw)
    S.append(scenario)
    BY_ID[scenario.field].scenarios.append(scenario)
    return scenario


# ====================================================================================================== meadow
add('sleep', 'meadow', '回窝睡觉', '困了回窝', '灵梦困了，自己走回有顶的窝里睡觉', '约 1 分钟', [
    *shed(7, 0), yk('reimu', 0, 0, 'a', energy=18, yaw=-90, lead=True), home('a', 7, 0)])

add('meet', 'meadow', '相遇与蹭蹭', '相遇蹭蹭', '橙和爱丽丝互相认识、想亲近，会自己走近、问候、蹭蹭', '约 1–2 分钟', [
    yk('chen', -2, 0, 'a', yaw=-90, affection=95, social_wait=0, lead=True),
    yk('alice', 2, 0, 'b', yaw=90, affection=95, social_wait=0),
    f'yukkuri scenario bond {sel("a")} {sel("b")}'])

add('hurt', 'meadow', '受伤与照料', '受伤照料', '橙身上有一处磕伤；惊慌约 5 秒后，拿橙汁和绷带对它右键', '先等惊慌过去，再治疗', [
    yk('chen', 0, 0, 'a', lead=True),
    f'yukkuri scenario wound {sel("a")}'], give=['mcyukkuri:orange_juice 2', 'mcyukkuri:bandage 2'])

add('toilet', 'meadow', '厕所排便', '厕所排便', '橙有强烈便意：看它走向右边的厕所；拆掉厕所再看它在地面排便', '约 1 分钟', [
    block(6, 0, 0, 'mcyukkuri:yukkuri_toilet'), yk('chen', 0, 0, 'a', bladder=95, yaw=-90, lead=True)])

add('meal', 'meadow', '饿了取食', '饿了取食', '灵梦饿了：自己走到苹果旁、拾起、咀嚼', '约 1 分钟', [
    yk('reimu', 0, 0, 'a', nutrition=32, yaw=-90, lead=True), item('minecraft:apple', 3, 3, 0)])

add('feed', 'meadow', '嚼糜喂食', '嚼糜喂食', '赤油饿了，还不会吃固体：亲油取苹果、嚼成糜、凑近嘴部喂它', '约 1.5 分钟', [
    yk('chen', 0, 0, 'mom', yaw=-90, **pair_ready()),
    yk('reimu', 3, 0, 'baby', stage='baby', nutrition=32, yaw=90, lead=True),
    f'yukkuri scenario kin {sel("mom")} {sel("baby")} false', item('minecraft:apple', 3, 1, 2)])

add('first_toilet', 'meadow', '首便与舔舐', '首便舔舐', '新生赤油还没排过便：亲油会舔舐它的后侧，帮它排出第一次污物', '约 1.5 分钟', [
    yk('chen', 0, 0, 'mom', yaw=-90, **pair_ready()),
    yk('reimu', 3, 0, 'baby', stage='baby', bladder=30, yaw=90, lead=True),
    f'yukkuri scenario kin {sel("mom")} {sel("baby")} true'])

add('hop', 'meadow', '学起跳', '学起跳', '子油先爬行试探，亲油示范起跳，子油跟着练到学会', '约 1–2 分钟', [
    yk('reimu', 0, 0, 'mom', yaw=-90, **pair_ready()),
    yk('chen', 3, 0, 'kid', stage='child', yaw=90, lead=True, childhood=CHILDHOOD_CAN_JUMP_SOON),
    f'yukkuri scenario kin {sel("mom")} {sel("kid")} false'])


def land_birth(sid, title, hint, outcome, father_kind, watch):
    add(sid, 'meadow', title, hint, watch, '约 1–2 分钟临产，之后看照护', [
        *shed(8, 0),
        yk('reimu', 7, 0, 'mother', yaw=-90, lead=True, **pair_ready()),
        yk(father_kind, 4, 1, 'father', yaw=-90, **pair_ready()),
        f'yukkuri scenario bond {sel("mother")} {sel("father")}',
        home('mother', 8, 0),
        f'yukkuri scenario birth {sel("mother")} {sel("father")} {outcome}'], give=[])


land_birth('birth_land_caught', '陆地接生·接住', '陆地接住', 'caught', 'marisa',
           '父油（戴帽魔理沙）察觉临产，走来摘帽翻面放好，新生儿落进帽里；之后放出幼体、戴回帽子')
land_birth('birth_land_missed', '陆地接生·漏接', '陆地漏接', 'missed', 'reimu',
           '没人用帽子垫底：新生儿直接落地，摔伤（等同摔伤）哭泣，父母赶来安抚；伤口按普通规则处理')

# ====================================================================================================== town
PEN = [box(6, 0, -3, 10, 1, 1, 'minecraft:oak_fence', 'outline')]

add('wild', 'town', '野良应对', '野良应对', '宠物（银章）在进食；饥饿的野生魔理沙走来讨吃，看宠物是不理会、报告主人，还是没忍住', '约 1–3 分钟', [
    yk('reimu', 0, 0, 'pet', grade=2, nutrition=38, yaw=-90, lead=True, boldness=60),
    *own('pet'),
    item('minecraft:apple', 2, 1, 0),
    yk('marisa', 6, 0, 'wild', nutrition=34, yaw=90, boldness=45)])

add('badge', 'town', '徽章与旁人', '徽章旁人', '把银章别在宠物灵梦身上，旁边的野生魔理沙会羡慕或嫉妒', '别上后约 1 分钟', [
    yk('reimu', 0, 0, 'pet', yaw=-90, lead=True), *own('pet'),
    yk('marisa', 6, 0, 'wild', yaw=90)], give=['mcyukkuri:silver_yukkuri_badge 1'])

add('shop', 'town', '商店与人类', '商店与人类', '右键店主开店，右键大哥哥对话；在售的爱丽丝（银章）可看；野生魔理沙会自己取大哥哥身旁的食物', '随意', [
    human('caretaker_brother', 0, 0, 'clerk', 1, yaw=-90, label='店主'),
    human('caretaker_brother', 4, 0, 'brother', 0, yaw=90, label='爱派大哥哥'),
    yk('alice', -3, 2, 'stock', grade=2, yaw=0, label='在售的爱丽丝'),
    yk('marisa', 8, 0, 'wild', nutrition=35, yaw=90), item('minecraft:apple', 2, 5, 1)], give=['minecraft:emerald 16'])

add('exam', 'town', '考场', '考场', '带宠物灵梦去考官面前考试：紧张、作答、躲猫猫，合格得铜章', '约 1–2 分钟', [
    block(0, 0, 0, 'mcyukkuri:exam_podium[facing=east]'), block(-4, 0, 0, 'mcyukkuri:exam_hide_box[facing=east]'),
    block(4, 0, 0, 'minecraft:lectern[facing=west]'),
    human('caretaker_brother', 5, 0, 'judge', 3, yaw=90, label='考官'),
    yk('reimu', 2, 2, 'pet', yaw=0, lead=True), *own('pet')])

add('date', 'town', '相亲', '相亲', '付绿宝石请饲养员牵线，宠物灵梦与种油魔理沙害羞、靠近、蹭蹭', '约 1–2 分钟', [
    human('caretaker_brother', 4, 0, 'keeper', 4, yaw=0, label='饲养员'),
    yk('reimu', 0, 0, 'bride', grade=1, yaw=-90, lead=True), *own('bride'),
    yk('marisa', 8, 0, 'stud', grade=2, yaw=90), *own('stud')], give=['minecraft:emerald 8'])

add('pred', 'town', '捕食种', '捕食种', '银章的蕾米莉亚听令忍住，没学会等待的芙兰（铜章，关在玻璃里）不听令，猎物看见捕食者会逃', '随意', [
    yk('remilia', 0, 0, 'rem', grade=2, yaw=-90, lead=True), *own('rem'),
    yk('reimu', 5, 0, 'prey', grade=1, yaw=90), *own('prey'),
    yk('flandre', 10, 0, 'fla', grade=1, yaw=90), *own('fla'),
    box(8, 0, -2, 12, 2, 2, 'minecraft:glass_pane', 'outline')], give=['mcyukkuri:field_notebook 1'])

add('transport', 'town', '运输与动手', '运输动手', '喷弹珠汽水喷雾让它睡着，再装篮、放出：仍是同一只；围栏里是野生个体', '随意', [
    yk('reimu', 0, 0, 'pet', grade=1, yaw=-90, lead=True), *own('pet'),
    *PEN, yk('reimu', 8, -1, 'wild', yaw=90, label='野生灵梦')], give=['mcyukkuri:sleep_spray 4', 'mcyukkuri:yukkuri_carrier 2'])

# ====================================================================================================== pond (anchor: the bank cell; water is one block east, one lower)
BANK, WATER = '~ ~ ~', '~1 ~-1 ~'
SHORE_CALL = 'schedule function mcylab:util/shore_call 3s replace'     # ask the owner to come back once the boat floats
DAY = {'time': 'noon'}


def boat(mode, parent, others, *extra):
    return f'yukkuri scenario boat {mode} {sel(parent)} {others} {BANK} {WATER}'


def captain(name='a', **kw):
    return yk('marisa', 0, 0, name, aquatic=True, yaw=-90, lead=True, **pair_ready(), **kw)


add('sail', 'pond', '备粮划桨', '备粮划桨', '水生魔理沙乘自己的帽船：备粮、划桨、在水面游弋', '约 1–3 分钟', [
    captain(), boat('sail', 'a', sel('a'))], env=DAY)

add('shore', 'pond', '返岸收帽', '返岸收帽', '亲油划回岸边，自己跳上岸、收回帽子（至多 1200 tick）', '约 1 分钟', [
    captain(), boat('shore', 'a', sel('a')), SHORE_CALL], env=DAY)

add('shore_child', 'pond', '带幼体返岸', '带幼体返岸', '船上有一只子油时，亲油带着它划回岸边，一起上岸、收帽', '约 1 分钟', [
    captain(), yk('marisa', -2, 1, 'kid', stage='child', aquatic=True, yaw=-90, **pair_ready()),
    boat('shore', 'a', sel('kid')), SHORE_CALL], env=DAY)

add('low', 'pond', '两幼体低载荷', '两幼体', '两只赤油坐进亲油的帽船，载荷小，航行轻快', '约 1–3 分钟', [
    captain(), yk('marisa', -2, 1, 'k1', stage='baby', aquatic=True, also=('young',), **pair_ready()),
    yk('marisa', -2, -1, 'k2', stage='baby', aquatic=True, also=('young',), **pair_ready()),
    boat('low', 'a', sel_all('young'))], env=DAY)

add('high', 'pond', '四幼体高载荷', '四幼体', '四只赤油挤进帽船，吃水变深、座位拥挤，看亲油和幼体怎么调整', '约 1–3 分钟', [
    captain(),
    *[yk('marisa', -2 - i % 2, i // 2 * 2 - 1, f'k{i}', stage='baby', aquatic=True, also=('young',), **pair_ready()) for i in range(4)],
    boat('high', 'a', sel_all('young'))], env=DAY)

add('voyage', 'pond', '第一次出航', '第一次出航', '小子油在亲油的帽船上放下帽子、跳进自己的帽船、试坐，第一次划出去再回来', '约 2–3 分钟', [
    captain(), yk('marisa', -2, 1, 'kid', stage='child', aquatic=True, yaw=-90, **pair_ready()),
    boat('voyage', 'a', sel('kid'))], env=DAY)

add('braid', 'pond', '落水·亲油救援', '亲油救援', '孩子掉进水里，亲油伸出辫子托起孩子放回帽中', '约 30–90 秒', [
    captain(), yk('marisa', -2, 1, 'kid', stage='child', aquatic=True, yaw=-90, **pair_ready()),
    boat('braid', 'a', sel('kid'))], env=DAY)

add('self_rescue', 'pond', '落水·自救', '落水自救', '小子油自己落水：抖落帽子、顶正、爬回帽上', '约 30–90 秒', [
    yk('marisa', 0, 0, 'a', stage='child', aquatic=True, yaw=-90, lead=True, **pair_ready()),
    f'yukkuri scenario boat self {sel("a")} {sel("a")} {BANK} {WATER}'], env=DAY)


def boat_birth(sid, title, hint, outcome, watch):
    add(sid, 'pond', title, hint, watch, '约 1–2 分钟临产，之后看照护', [
        captain('mother'), yk('marisa', -2, 1, 'father', aquatic=True, yaw=-90, **pair_ready()),
        f'yukkuri scenario bond {sel("mother")} {sel("father")}',
        boat('birth', 'mother', sel('father')),
        f'yukkuri scenario birth {sel("mother")} {sel("father")} {outcome}'], env=DAY)


boat_birth('birth_boat_caught', '船上接生·接住', '船上接住', 'caught', '母油坐在帽船里，岸上戴帽的父油察觉临产，用帽子接住新生儿')
boat_birth('birth_boat_missed', '船上接生·漏接', '船上漏接', 'missed', '没人接住：新生儿落进水里，等同溺水，除非亲油用辫子救起')

# ====================================================================================================== house (anchor: the middle of the room)
NEST = 'mcyukkuri:house_dust_nest{Materials:0,Food:0,Arrivals:2,Entrance:"north"}'


def house_pair():
    return [yk('house_marisa', -1, 1, 'a', yaw=0, lead=True, **pair_ready()), yk('house_reimu', 1, 1, 'b', yaw=0, **pair_ready())]


add('house_bare', 'house', '家油入住·无巢', '无巢家油', '一对家油住进房子，还没有巢：把棉絮和食屑丢在它们附近，看它们好奇、拾取、筑巢', '数分钟', [
    *house_pair(), item('mcyukkuri:nest_lint', 3, -3, -2), item('mcyukkuri:food_scrap', 5, 3, -2)],
    give=['mcyukkuri:nest_lint 8', 'mcyukkuri:food_scrap 12'])

add('house_nest', 'house', '家油的巢与卵', '家油巢与卵', '家油拾絮、把絮搬进巢里筑巢、入巢、产卵；孵化后看刚出生的家油', '筑巢数分钟，孵化更久', [
    block(-3, 0, -3, NEST), *house_pair(), item('mcyukkuri:nest_lint', 6, -1, -2), item('mcyukkuri:food_scrap', 12, 3, -2),
    f'yukkuri scenario home {sel("a")} ~-3 ~ ~-3', f'yukkuri scenario home {sel("b")} ~-3 ~ ~-3'],
    give=['mcyukkuri:nest_lint 8', 'mcyukkuri:food_scrap 12'])

# ====================================================================================================== hill (two caves)
HILL = BY_ID['hill']
add('independence', 'hill', '长大后独立', '开始独立', '亚成油带着独立计划回旧家：采叶、搬运、挖出新巢（东边八格的洞口），可能要十分钟以上', '十分钟以上（可倍速）', [
    yk('reimu', 0, -1, 'parent', yaw=0, **pair_ready()),
    yk('reimu', 0, 2, 'teen', stage='teen', share=.85, yaw=180, lead=True, **pair_ready()),
    f'yukkuri scenario kin {sel("parent")} {sel("teen")} false',
    f'yukkuri scenario independence {sel("parent")} {sel("teen")} ~ ~ ~'], env=DAY, retry='先“清空本场地”（洞口格要空）')

add('growth', 'hill', '一路长大', '一路长大', '赤油、子油、亚成油和亲油一家：体型、吃饭、学起跳、能否独立，随时间自然变化（用倍速看）', '全程约 40 分钟（倍速）', [
    yk('chen', 0, 0, 'parent', yaw=0, **pair_ready()),
    yk('reimu', -3, 2, 'baby', stage='baby', yaw=0), yk('reimu', -1, 3, 'child', stage='child', yaw=0),
    yk('reimu', 1, 3, 'teen', stage='teen', yaw=0, lead=True), yk('reimu', 3, 2, 'baby2', stage='baby', yaw=0),
    *[f'yukkuri scenario kin {sel("parent")} {sel(k)} false' for k in ('baby', 'child', 'teen', 'baby2')],
    item('minecraft:apple', 6, 0, 4)], env=DAY, anchor=HILL.family_anchor)

# ====================================================================================================== woods / snow / settle
GRASS = box(-14, 0, -14, 14, 0, 14, 'minecraft:short_grass', 'keep')
# settlements make their own members: one of them becomes the lead the 'to the lead' button flies to
LEAD_ANY = 'tag @e[type=#mcylab:yukkuri,distance=..70,limit=1,sort=random] add scn_lead'
add('autumn', 'woods', '秋：采集储粮', '秋采集', '森林里一个小营地：成员割草、衔回、存进储粮堆；秋天的储粮目标更高', '数分钟（倍速）', [
    GRASS, 'yukkuri settlement spawn band ~ ~ ~', LEAD_ANY], env=DAY)

add('winter', 'snow', '冬：居巢', '冬居巢', '雪原上的营地：下雪或夜里成员待在窝里，吃存粮，让食给孩子', '数分钟（倍速）', [
    'yukkuri settlement spawn band ~ ~ ~', LEAD_ANY], env={'time': 'dusk', 'weather': 'rain'})

add('settle_demo', 'settle', '小聚落', '小聚落', '一座小型示范站点和一小群成员：住所、广场、仓堆', '随意', [
    'yukkuri settlement spawn demo ~ ~ ~', LEAD_ANY], env=DAY)
add('settle_village', 'settle', '聚落与族长', '聚落与族长', '一座完整村落：族长、各家住所、公用仓堆；可派工、制造威胁、迁居（下方按钮）', '数分钟（倍速）', [
    GRASS, 'yukkuri settlement spawn village ~ ~ ~', LEAD_ANY], env=DAY)
add('settle_band', 'settle', '流浪小队', '流浪小队', '一支小队建起营地，看它们安顿下来', '数分钟（倍速）', [
    'yukkuri settlement spawn band ~ ~ ~', LEAD_ANY], env=DAY)

LEADER = '@e[type=mcyukkuri:patchouli,distance=..60,limit=1,sort=nearest]'
MEMBER = '@e[type=mcyukkuri:reimu,distance=..60,limit=1,sort=random]'
SETTLE_EXTRAS = [
    ('order_forage', '派工·采集', '族长派一名成员', f'yukkuri settlement order {LEADER} {MEMBER} forage'),
    ('order_scout', '派工·侦察', '族长派一名成员', f'yukkuri settlement order {LEADER} {MEMBER} scout'),
    ('order_message', '派工·传话', '族长派一名成员', f'yukkuri settlement order {LEADER} {MEMBER} message'),
    ('threat', '制造威胁', '给最近聚落 +6', 'yukkuri settlement threat 6'),
    ('migrate', '尝试迁居', '最近聚落的族长', 'yukkuri settlement migrate'),
]
