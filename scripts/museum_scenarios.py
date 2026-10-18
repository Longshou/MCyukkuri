"""Scenarios of the playground: one question each, answered by real individuals under their ordinary AI.

A scenario is a list of commands run positioned at the scenario's anchor block (see museum_dsl). It only creates the
individuals and props and sets their real starting state (hunger, tiredness, life stage, kin, home, conception, a
wound, a hat boat afloat ...). After that nothing is scripted, fixed or invulnerable: the observer watches, speeds the
clock up, flies to the lead, or clears the field and starts again.

Fields hold the terrain (museum_fields.py); each scenario names its field and the spot of that field it stands around.
Every scenario carries the code of the user concern it answers (``TOPICS`` at the end), which docs/EXPERIMENTAL_MAP.md tabulates.
"""
import sys

sys.dont_write_bytecode = True
from museum_dsl import (yk, human, item, block, box, shed, home, sel, sel_all, say, uid, uuid_nbt, family, _text)  # noqa: E402
from museum_fields import BY_ID, FEET  # noqa: E402

CHILDHOOD_CAN_JUMP_SOON = 'Version:1,Newborn:1b,Landed:1b,FirstToiletDone:1b,Crawled:1b,Jumped:0b,CrawlDistance:1.0d'
OWNED = 'NeoForgeData.HumanCommerce'
# an adolescent who can already live on its own (the wild-life agent's recipe: Age 40000, Nourishment 30000, Maturity 60000)
TEEN_FAMILY = ('Version:2,Initialized:1b,Stage:"JUVENILE",Age:40000L,Nourishment:30000L,Maturity:60000L,Lifespan:2208000L,AdultScale:1f,Scale:0.72f')
LATE_COMMANDS = ('yukkuri scenario home', 'yukkuri scenario birth', 'yukkuri scenario boat', 'yukkuri scenario shore')
PENDING = '修复中'     # a scenario whose helper behaviour is not wired in yet: the sign says so, the button and terrain stay


class Retry:
    """A late command that a refusal does not end: after ``first`` ticks it is tried every ``every`` ticks, at most ``tries`` times,
    until the game accepts it. ``guard`` is an entity selector that must match before an attempt is made (the way a refusal is
    kept off the chat: the attempt waits until the thing it needs, say a mother seated in her boat, is really there)."""
    def __init__(self, command, guard, tries=16, every=50, first=60):
        self.command, self.guard, self.tries, self.every, self.first = command, guard, tries, every, first


class Scenario:
    def __init__(self, sid, field, title, hint, watch, wait, lines, give=(), env=None, spot=None, retry='先按“清空本场地”，再重新生成',
                 asks='', note='', sub=None, vantage=(0, 14), reads=(), late=()):
        self.id, self.field, self.title, self.hint = sid, field, title, hint
        self.watch, self.wait, self.retry, self.asks, self.note = watch, wait, retry, asks, note
        self.lines, self.give, self.env = list(lines), list(give), env or {}
        # settings that need the individuals to stand on the ground (a home, a conception, a boat) run two seconds after the summons
        deferred = [i for i, line in enumerate(self.lines) if line.startswith(LATE_COMMANDS)]
        self.late_lines = [self.lines[i] for i in deferred]
        self.lines = [line for i, line in enumerate(self.lines) if i not in deferred]
        f = BY_ID[field]
        self.anchor = f.spots[spot] if spot else f.anchor
        self.sub = sub                 # name of the plot cleared before this scenario (default: the whole field)
        self.vantage = vantage         # (dx, dz) from the anchor where the starting player is put (None: stay on the deck)
        self.reads = list(reads)       # (caption, command) readouts that belong to this scenario
        self.late = self.late_lines + list(late)   # commands that need the individuals to stand on the ground first: run two seconds after the start

    @property
    def tag(self):
        return PENDING if self.note.startswith(PENDING) else ('待验证' if self.note.startswith('待验证') else '')

    def body(self):
        """Commands of ``scn/<id>``: heading, the scenario itself, items for the player."""
        head = [say(f'【{self.title}】看点：{self.watch}', 'aqua'), say(f'预计：{self.wait}。没反应：{self.retry}。', 'yellow')]
        if self.note:
            head.append(say(f'注意：{self.note}', 'red'))
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


DAY = {'time': 'noon'}
LEAD_ANY = 'tag @e[type=#mcylab:yukkuri,distance=..70,limit=1,sort=random] add scn_lead'


def set_status(name, key, value):
    return f'yukkuri status set {sel(name)} {key} {value}'


# ====================================================================================================== wild shelters
def adult_pair(sid, mother_kind='reimu', father_kind='marisa', dz=3, baby=True, teen=False, lead_baby=False):
    """A father, a mother and one child with real parents written into NBT (their UUIDs are fixed per scenario)."""
    m, f, c = uid(sid, 'mother'), uid(sid, 'father'), uid(sid, 'child')
    out = [yk(mother_kind, 0, dz, 'mother', uuid=m, yaw=0, lead=not lead_baby, kin=f'Partner:{uuid_nbt(f)}', **pair_ready()),
           yk(father_kind, 2, dz, 'father', uuid=f, yaw=0, kin=f'Partner:{uuid_nbt(m)}', **pair_ready())]
    if baby:
        out.append(yk('reimu', 1, dz + 1, 'child', stage='baby', uuid=c, yaw=0, lead=lead_baby, nutrition=95, kin=f'Mother:{uuid_nbt(m)},Father:{uuid_nbt(f)}'))
    return out, (m, f, c)


_o, _ = adult_pair('den_family')
add('den_family', 'den', '家庭洞·一家三口', '家庭洞', '父母和赤油住进土丘洞：识别为同一个家（status 的 home.kind 是 burrow），夜里回洞、储粮、外出',
    '数分钟（可倍速）', _o, env=DAY, spot='family', vantage=(0, 12),
    reads=[('读家的识别', 'yukkuri status @e[tag=scn_mother,limit=1,sort=nearest,distance=..60]')])


def independence(sid, title, hint, watch, parent_stays):
    pm, pc = uid(sid, 'parent'), uid(sid, 'teen')
    # the teen's independence is armed in NBT (Force); the family finds the den by itself like any family, and the farewell/departure is ordinary
    lines = [yk('reimu', 0, 3, 'parent', uuid=pm, yaw=0, lead=False, **pair_ready()),
             yk('reimu', 1, 4, 'teen', stage='teen', uuid=pc, yaw=0, lead=True, nutrition=100, energy=100, kin=f'Mother:{uuid_nbt(pm)}',
                extra='YukkuriIndependence:{Force:1b}'),
             f'yukkuri scenario kin {sel("parent")} {sel("teen")} false']
    if not parent_stays:
        lines.append(f'tp {sel("parent")} ~ ~ ~40')
    add(sid, 'den', title, hint, watch, '约 3 分钟内离开（可倍速）', lines, env=DAY, spot='indep', retry='先“清空本场地”（洞口格要空）', vantage=(0, 12),
        reads=[('读独立状态', f'data get entity {sel("teen")} YukkuriIndependence')])


independence('indep_near', '独立·亲油在场', '独立有告别', '亚成独立：亲油在场，先告别再离巢，之后自己找洞或挖穴', True)
independence('indep_far', '独立·亲油不在', '独立无告别', '亲油不在：约 3 分钟后亚成直接离开，去找新的家', False)

# the family of three in a 5-cell stone hole with a big empty earth cave 16 blocks east
_sm, _sf, _sc = uid('den_move', 'mother'), uid('den_move', 'father'), uid('den_move', 'child')
add('den_move', 'den', '搬往大洞', '搬往大洞',
    '一家两成体加一只能独立的子油挤在石头围的小洞里（5 格，墙是石头挖不动）；东边 16 格有一个空的大土洞，看家庭搬过去',
    '数分钟（可倍速）', [
        yk('reimu', 0, 3, 'mother', uuid=_sm, yaw=0, lead=True, kin=f'Partner:{uuid_nbt(_sf)}', **pair_ready()),
        yk('marisa', 2, 3, 'father', uuid=_sf, yaw=0, kin=f'Partner:{uuid_nbt(_sm)}', **pair_ready()),
        yk('reimu', 1, 4, 'child', stage='teen', uuid=_sc, yaw=0, nutrition=100, energy=100, kin=f'Mother:{uuid_nbt(_sm)},Father:{uuid_nbt(_sf)}', fam=TEEN_FAMILY),
        ],
    late=[f'yukkuri scenario home {sel(n)} ~ ~ ~-1' for n in ('mother', 'father', 'child')],
    env=DAY, spot='small', vantage=(8, 14), reads=[('读搬家说明', f'data get entity {sel("mother")} YukkuriHabitatView')])

# ====================================================================================================== vagrants and foraging
add('dig', 'forage', '流浪油挖穴', '流浪挖穴', '没有家的成年流浪油走到土丘边：规划一条通道，一块块挖出洞（每块约 6 秒），挖完自成一个家',
    '约 3–6 分钟（可倍速）', [
        yk('marisa', 0, 0, 'vagrant', yaw=0, lead=True, **pair_ready()),
        'gamerule mobGriefing true'], env=DAY, spot='dig', vantage=(0, 12),
    reads=[('读挖穴状态', f'yukkuri status {sel("vagrant")}')])

_fm, _ff, _fc = uid('forage', 'mother'), uid('forage', 'father'), uid('forage', 'child')
add('forage', 'forage', '外出觅食', '外出觅食', '5×5 土丘洞里的一家：储粮是空的，父亲先出去、母亲随后；去草地割草、衔回、放进储粮堆',
    '数分钟（可倍速）', [
        yk('marisa', 0, 3, 'father', uuid=_ff, yaw=0, lead=True, kin=f'Partner:{uuid_nbt(_fm)}', nutrition=60, energy=100),
        yk('reimu', 2, 3, 'mother', uuid=_fm, yaw=0, kin=f'Partner:{uuid_nbt(_ff)}', nutrition=60, energy=100),
        yk('reimu', 1, 4, 'child', stage='baby', uuid=_fc, yaw=0, nutrition=50, kin=f'Mother:{uuid_nbt(_fm)},Father:{uuid_nbt(_ff)}'),
        ],
    late=[f'yukkuri scenario home {sel(n)} ~ ~ ~-1' for n in ('mother', 'father', 'child')],
    env=DAY, spot='forage', vantage=(0, 16),
    reads=[('读觅食数据（父）', f'data get entity {sel("father")} YukkuriForageView'), ('读觅食数据（母）', f'data get entity {sel("mother")} YukkuriForageView')])

add('graze', 'forage', '吃草', '吃草', '饿了的成年油走进草地，一株株啃（草退回上一阶、日后重新长出）；没有家的它会在附近觅食',
    '约 1–3 分钟', [
        yk('reimu', 0, 0, 'a', nutrition=28, yaw=0, lead=True), yk('marisa', 3, 2, 'b', nutrition=34, yaw=0)], env=DAY, spot='graze', vantage=(0, 12))

# ====================================================================================================== biome settlements
def settlement(plot, title, kind, wait):
    from museum_fields import BIOME_PLOTS
    biome = next(p[1] for p in BIOME_PLOTS if p[0] == plot)
    add(f'settle_{plot}', 'biomes', f'{title}聚落', title, f'{title}群系里自然生成的{"村落" if kind == "village" else "营地"}：成员、住所、仓堆随群系特色而不同', wait,
        [f'fillbiome ~-22 56 ~-22 ~22 100 ~22 {biome}', f'yukkuri settlement spawn {kind} ~ ~ ~', LEAD_ANY], env=DAY, spot=plot,
        vantage=(0, 26), retry=f'先“清空本场地”再按一次')


for _plot, _title, _kind in (('forest', '森林', 'village'), ('plains', '平原', 'village'), ('snow', '雪原', 'band'), ('taiga', '针叶林', 'band'),
                             ('swamp', '沼泽', 'band'), ('savanna', '热带草原', 'band'), ('desert', '沙漠', 'band'), ('badlands', '恶地', 'band')):
    settlement(_plot, _title, _kind, '数分钟（可倍速）')

# ====================================================================================================== daily life meadow
add('sleep', 'meadow', '回窝睡觉', '困了回窝', '灵梦困了，自己走回有顶的窝里睡觉', '约 1 分钟', [
    *shed(7, 0), yk('reimu', 0, 0, 'a', energy=18, yaw=-90, lead=True), home('a', 7, 0)], vantage=None)

add('meet', 'meadow', '相遇与蹭蹭', '相遇蹭蹭', '橙和爱丽丝互相认识、想亲近，会自己走近、问候、蹭蹭', '约 1–2 分钟', [
    yk('chen', -2, 0, 'a', yaw=-90, affection=95, social_wait=0, lead=True),
    yk('alice', 2, 0, 'b', yaw=90, affection=95, social_wait=0),
    f'yukkuri scenario bond {sel("a")} {sel("b")}'], vantage=None)

add('hurt', 'meadow', '受伤与照料', '受伤照料',
    '橙身上有一处磕伤，旁边是认得它的伴侣爱丽丝。惊慌过去后，爱丽丝会走过来担心地哭着舔舐，只安抚情绪和压力（不治疗）；治疗用橙汁和绷带',
    '先等惊慌过去，看舔舐；再用橙汁和绷带治疗', [
        yk('chen', 0, 0, 'a', lead=True),
        yk('alice', 4, 0, 'mate', yaw=90, **pair_ready()),
        f'yukkuri scenario bond {sel("a")} {sel("mate")}',
        f'yukkuri scenario mate {sel("a")} {sel("mate")}',
        f'yukkuri scenario wound {sel("a")}'], give=['mcyukkuri:orange_juice 2', 'mcyukkuri:bandage 2'], vantage=None)

add('meal', 'meadow', '饿了取食', '饿了取食', '灵梦饿了：自己走到苹果旁、拾起、咀嚼', '约 1 分钟', [
    yk('reimu', 0, 0, 'a', nutrition=32, yaw=-90, lead=True), item('minecraft:apple', 3, 3, 0)], vantage=None)

add('toilet', 'meadow', '厕所排便', '厕所排便', '橙有强烈便意：看它走向右边的厕所；拆掉厕所再看它在地面排便（从油菊排出）', '约 1 分钟', [
    block(6, 0, 0, 'mcyukkuri:yukkuri_toilet'), yk('chen', 0, 0, 'a', bladder=95, yaw=-90, lead=True)], vantage=None)

# ====================================================================================================== family and growth
def land_birth(sid, title, hint, outcome, father_kind, watch, spot):
    add(sid, 'family', title, hint, watch, '约 1–2 分钟临产，之后看照护', [
        *shed(8, 0),
        yk('reimu', 7, 0, 'mother', yaw=-90, lead=True, **pair_ready()),
        yk(father_kind, 4, 1, 'father', yaw=-90, **pair_ready()),
        f'yukkuri scenario bond {sel("mother")} {sel("father")}',
        home('mother', 8, 0),
        f'yukkuri scenario birth {sel("mother")} {sel("father")} {outcome}'], spot=spot, vantage=(4, 12))


land_birth('birth_land_caught', '陆地接生·接住', '陆地接住', 'caught', 'marisa',
           '父油（戴帽魔理沙）察觉临产，走来摘帽翻面放好，新生儿落进帽里；之后放出幼体、戴回帽子', 'land_caught')
land_birth('birth_land_missed', '陆地接生·漏接', '陆地漏接', 'missed', 'reimu',
           '没人用帽子垫底：新生儿直接落地，摔伤（等同摔伤）哭泣，父母赶来安抚；伤口按普通规则处理', 'land_missed')

add('pet_birth', 'family', '宠物生产窝', '宠物生产窝',
    '你的宠物母油（伴侣在旁）要生了：三块相邻的宠物窝在有顶的棚下，家庭大窝按幼体数占用相邻的空窝', '约 1–2 分钟临产，之后看照护', [
        *shed(8, 0, half=3),
        block(7, 0, 0, 'mcyukkuri:yukkuri_nest'), block(9, 0, 0, 'mcyukkuri:yukkuri_nest'), block(8, 0, 1, 'mcyukkuri:yukkuri_nest'),
        yk('reimu', 8, 0, 'mother', yaw=-90, lead=True, grade=1, **pair_ready()), *own('mother'),
        yk('marisa', 6, 2, 'father', yaw=-90, **pair_ready()),
        f'yukkuri scenario bond {sel("mother")} {sel("father")}',
        home('mother', 8, 0),
        f'yukkuri scenario birth {sel("mother")} {sel("father")} natural'], spot='pet_birth', vantage=(4, 12))

add('feed', 'family', '嚼糜喂食', '嚼糜喂食', '赤油饿了，还不会吃固体：亲油取苹果、嚼成糜、凑近嘴部喂它', '约 1.5 分钟', [
    yk('chen', 0, 0, 'mom', yaw=-90, **pair_ready()),
    yk('reimu', 3, 0, 'baby', stage='baby', nutrition=32, yaw=90, lead=True),
    f'yukkuri scenario kin {sel("mom")} {sel("baby")} false', item('minecraft:apple', 3, 1, 2)], spot='feed', vantage=(0, 10))

add('first_toilet', 'family', '首便与舔舐', '首便舔舐', '新生赤油还没排过便：亲油会舔舐它的后侧，帮它排出第一次污物', '约 1.5 分钟', [
    yk('chen', 0, 0, 'mom', yaw=-90, **pair_ready()),
    yk('reimu', 3, 0, 'baby', stage='baby', bladder=30, yaw=90, lead=True),
    f'yukkuri scenario kin {sel("mom")} {sel("baby")} true'], spot='first_toilet', vantage=(0, 10))

add('hop', 'family', '学起跳', '学起跳', '子油先爬行试探，亲油示范起跳，子油跟着练到学会', '约 1–2 分钟', [
    yk('reimu', 0, 0, 'mom', yaw=-90, **pair_ready()),
    yk('chen', 3, 0, 'kid', stage='child', yaw=90, lead=True, childhood=CHILDHOOD_CAN_JUMP_SOON),
    f'yukkuri scenario kin {sel("mom")} {sel("kid")} false'], spot='hop', vantage=(0, 10))

add('hurt_kin', 'family', '亲子受伤舔舐', '亲子舔舐',
    '小橙磕伤了，亲油灵梦认得它。惊慌过去后，亲油会走近担心地哭着舔舐安抚（不治疗，不加速愈合、不降感染）；治疗仍用橙汁和绷带',
    '先等惊慌过去，看舔舐；再用橙汁和绷带治疗', [
        yk('reimu', 0, 0, 'mom', yaw=-90, **pair_ready()),
        yk('chen', 4, 0, 'kid', stage='child', yaw=90, lead=True, nutrition=90, energy=95),
        f'yukkuri scenario kin {sel("mom")} {sel("kid")} false',
        f'yukkuri scenario wound {sel("kid")}'], give=['mcyukkuri:orange_juice 2', 'mcyukkuri:bandage 2'], spot='hurt_kin', vantage=(0, 10))

add('growth', 'family', '一路长大', '一路长大', '赤油、子油、亚成油和亲油一家：头顶显示成长百分比和本阶段剩余时间；用倍速或“推进”快进',
    '全程约 40 分钟（用推进 20 分钟）', [
        yk('chen', 0, 0, 'parent', yaw=0, **pair_ready()),
        yk('reimu', -3, 2, 'baby', stage='baby', yaw=0), yk('reimu', -1, 3, 'child', stage='child', yaw=0),
        yk('reimu', 1, 3, 'teen', stage='teen', yaw=0, lead=True), yk('reimu', 3, 2, 'baby2', stage='baby', yaw=0),
        *[f'yukkuri scenario kin {sel("parent")} {sel(k)} false' for k in ('baby', 'child', 'teen', 'baby2')],
        item('minecraft:apple', 6, 0, 4)], env=DAY, spot='growth', vantage=(0, 10))

# ====================================================================================================== weather and predators
add('rain_shelter', 'weather', '下雨躲雨', '下雨躲雨', '下雨了：6–16 格外有一块石顶，三只油先“跑向”遮雨处（weather:run），到了就“等待”（weather:wait）',
    '约 30 秒', [
        yk('reimu', 0, 0, 'a', yaw=-90, lead=True), yk('marisa', 2, 3, 'b', yaw=-90), yk('chen', -2, 2, 'c', yaw=-90)],
    env={'time': 'noon', 'weather': 'rain'}, spot='shelter', vantage=(0, 14),
    reads=[('读反应（behavior.reaction）', f'yukkuri status {sel("a")} raw')])

add('thunder', 'weather', '雷暴', '雷暴', '雷暴天气：同样的石顶，反应更强烈', '约 30 秒', [
    yk('reimu', 0, 0, 'a', yaw=-90, lead=True), yk('marisa', 2, 3, 'b', yaw=-90)],
    env={'time': 'noon', 'weather': 'thunder'}, spot='thunder', vantage=(0, 14))

add('rain_open', 'weather', '空地淋雨', '空地淋雨', '下雨，但附近（48 格内）没有顶：油缩成一团（weather:huddle），不会瞎跑', '约 30 秒', [
    yk('reimu', 0, 0, 'a', yaw=-90, lead=True), yk('marisa', 2, 3, 'b', yaw=-90)],
    env={'time': 'noon', 'weather': 'rain'}, spot='open', vantage=(0, 14))

add('pred_watch', 'weather', '看见捕食种', '看见捕食种',
    '蕾米莉亚（不饿）在 12 格外：普通个体依次“观望（threat:watch）→后退（threat:retreat）→躲藏（threat:hide）”；它们不会被追',
    '约 1 分钟', [
        yk('reimu', 0, 0, 'a', yaw=-90, lead=True), yk('marisa', 0, 2, 'b', yaw=-90),
        yk('remilia', 12, 0, 'pred', yaw=90, nutrition=100, energy=100)],
    env=DAY, spot='pred', vantage=(6, 14), reads=[('读反应', f'yukkuri status {sel("a")} raw')])

add('pred_hunt', 'weather', '被捕食种追猎', '被追猎', '芙兰饿了（营养 10）盯上 12 格外的普通个体：被追猎时的逃跑与躲藏', '约 1 分钟', [
    yk('reimu', 0, 0, 'a', yaw=-90, lead=True), yk('flandre', 12, 0, 'pred', yaw=90, nutrition=100, energy=100),
    set_status('pred', 'needs.nutrition', 10)],
    env=DAY, spot='pred', vantage=(6, 14), reads=[('读反应', f'yukkuri status {sel("a")} raw')])

add('marks', 'weather', '脏污与伤痕', '脏污伤痕', '三只油：干净的、脏污 3 级的、带伤痕的（外观由 status 的 marks 决定）；头顶标签同步显示', '随意', [
    yk('reimu', 0, 0, 'clean', yaw=-90, lead=True), yk('reimu', 3, 0, 'dirty', yaw=-90), yk('reimu', 6, 0, 'scarred', yaw=-90),
    set_status('dirty', 'marks.dirt', 3), set_status('scarred', 'marks.scars', 1)], spot='marks', vantage=(3, 10))

add('orange', 'weather', '橙汁治疗', '橙汁治疗', '一只受伤的橙喝橙汁：治疗效果与粒子；另一只被设成“已涂橙汁”看粒子；你也领到橙汁和绷带', '随意', [
    yk('chen', 0, 0, 'hurt', yaw=-90, lead=True), yk('chen', 3, 0, 'treated', yaw=-90),
    set_status('hurt', 'health.wound', 'tear:2'), set_status('treated', 'health.burns', 2), set_status('treated', 'health.treat', 'orange')],
    give=['mcyukkuri:orange_juice 4', 'mcyukkuri:bandage 4'], spot='orange', vantage=(2, 10))

# ====================================================================================================== pond (anchor: the bank cell; water is one block east, one lower)
BANK, WATER = '~ ~ ~', '~1 ~-1 ~'
SHORE_CALL = 'schedule function mcylab:util/shore_call 3s replace'     # ask the owner to come back once the boat floats


def boat(mode, parent, others):
    return f'yukkuri scenario boat {mode} {sel(parent)} {others} {BANK} {WATER}'


def captain(name='a', **kw):
    return yk('marisa', 0, 0, name, aquatic=True, yaw=-90, lead=True, **pair_ready(), **kw)


def pond(sid, title, hint, watch, wait, lines, note='', **kw):
    add(sid, 'pond', title, hint, watch, wait, lines, env=DAY, note=note, vantage=(-6, 6, -90), **kw)


pond('sail', '备粮划桨', '备粮划桨', '水生魔理沙乘自己的帽船：备粮、划桨、在水面游弋', '约 1–3 分钟', [captain(), boat('sail', 'a', sel('a'))])
pond('shore', '返岸收帽', '返岸收帽', '亲油划回岸边，自己跳上岸、收回帽子（至多 1200 tick）', '约 1 分钟', [captain(), boat('shore', 'a', sel('a')), SHORE_CALL])
pond('shore_child', '带幼体返岸', '带幼体返岸', '船上有一只子油时，亲油带着它划回岸边，一起上岸、收帽', '约 1 分钟', [
    captain(), yk('marisa', -2, 1, 'kid', stage='child', aquatic=True, yaw=-90, **pair_ready()), boat('shore', 'a', sel('kid')), SHORE_CALL])
pond('shore_baby', '赤油返岸', '赤油返岸', '船上是一只赤油：亲油带它返岸收帽；赤油不会爬回帽里，亲油就留在岸边陪着它', '约 1 分钟返岸，之后亲油留守', [
    captain(), yk('marisa', -2, 1, 'kid', stage='baby', aquatic=True, yaw=-90, **pair_ready()), boat('shore', 'a', sel('kid')), SHORE_CALL])
pond('shore_babies', '两只赤油返岸', '两只赤油返岸', '船上是两只赤油：亲油用辫子把它们一只只放上岸，收帽后留在岸边陪着', '约 1–2 分钟返岸，之后亲油留守', [
    captain(), yk('marisa', -2, 1, 'k1', stage='baby', aquatic=True, yaw=-90, also=('young',), **pair_ready()),
    yk('marisa', -2, -1, 'k2', stage='baby', aquatic=True, yaw=-90, also=('young',), **pair_ready()), boat('shore', 'a', sel_all('young')), SHORE_CALL])
pond('low', '两幼体低载荷', '两幼体', '两只赤油坐进亲油的帽船，载荷小，航行轻快', '约 1–3 分钟', [
    captain(), yk('marisa', -2, 1, 'k1', stage='baby', aquatic=True, also=('young',), **pair_ready()),
    yk('marisa', -2, -1, 'k2', stage='baby', aquatic=True, also=('young',), **pair_ready()), boat('low', 'a', sel_all('young'))])
pond('high', '四幼体高载荷', '四幼体', '四只赤油挤进帽船，吃水变深、座位拥挤，看亲油和幼体怎么调整', '约 1–3 分钟', [
    captain(), *[yk('marisa', -2 - i % 2, i // 2 * 2 - 1, f'k{i}', stage='baby', aquatic=True, also=('young',), **pair_ready()) for i in range(4)],
    boat('high', 'a', sel_all('young'))])
pond('voyage', '第一次出航', '第一次出航', '小子油在亲油的帽船上放下帽子、跳进自己的帽船、试坐，第一次划出去再回来', '约 2–3 分钟', [
    captain(), yk('marisa', -2, 1, 'kid', stage='child', aquatic=True, yaw=-90, **pair_ready()), boat('voyage', 'a', sel('kid'))])
pond('braid', '落水·亲油救援', '亲油救援', '孩子掉进水里，亲油伸出辫子托起孩子放回帽中（孩子留在船上）', '约 30–90 秒', [
    captain(), yk('marisa', -2, 1, 'kid', stage='child', aquatic=True, yaw=-90, **pair_ready()), boat('braid', 'a', sel('kid'))])
pond('rescue_baby', '赤油落水救援', '赤油落水', '船上的赤油被摇落水：亲油救援', '约 1–2 分钟', [
    captain(), yk('marisa', -2, 1, 'kid', stage='baby', aquatic=True, yaw=-90, **pair_ready()), boat('drop', 'a', sel('kid'))])
pond('self_rescue', '落水自救', '落水自救', '小子油自己落水：抖落帽子、顶正、爬回帽上', '约 30–90 秒', [
    yk('marisa', 0, 0, 'a', stage='child', aquatic=True, yaw=-90, lead=True, **pair_ready()), boat('self', 'a', sel('a'))],
     note='演示时可开 /yukkuri aqdiag on，在日志里看甩帽、咬缘、攀入各阶段。')
pond('shake', '摇船', '摇船', '有孩子的船长被摇：聚拢孩子并逃离；对比：没有孩子的船长只是普通惊吓（再按一次，孩子数为 0）', '约 30 秒', [
    captain(), yk('marisa', -2, 1, 'k1', stage='baby', aquatic=True, also=('young',), **pair_ready()),
    yk('marisa', -2, -1, 'k2', stage='baby', aquatic=True, also=('young',), **pair_ready()), boat('shake', 'a', sel_all('young'))])
pond('afloat', '生成即坐船', '水上生成', '水生家庭在水面生成：约 1 秒后亲油从头上取下帽子放成船，坐进去，附近的幼体也上船', '约 10 秒', [
    captain(), yk('marisa', -2, 1, 'k1', stage='baby', aquatic=True, **pair_ready()), boat('afloat', 'a', sel('a'))])
def bank_dx(dz):
    """x offset (from the anchor bank cell) of the bank cell on the row dz south of it: the pond's shore is not straight."""
    pond_ = BY_ID['pond']
    z = pond_.pz + dz
    x = pond_.px - pond_.RX - 3
    while pond_.depth(x, z) == 0:
        x += 1
    return x - 1 - pond_.bank[0]


_dz = 4
_dx = bank_dx(_dz)
pond('couple_boats', '夫妻各一艘船', '夫妻分担', '母油和父油各有一艘浮着的帽船：看他们如何分担航行与照料', '约 1–3 分钟', [
    captain('mother'), yk('marisa', _dx, _dz, 'father', aquatic=True, yaw=-90, **pair_ready()),
    f'yukkuri scenario bond {sel("mother")} {sel("father")}',
    boat('sail', 'mother', sel('mother')),
    f'yukkuri scenario boat sail {sel("father")} {sel("father")} ~{_dx} ~ ~{_dz} ~{_dx + 1} ~-1 ~{_dz}'],
     note='待验证：两艘船同时在水面的行为尚未实测。')


def boat_birth(sid, title, hint, outcome, watch, note=''):
    # The conception needs the mother really seated in her hat boat, which takes a moment after the boat is made: ask for it
    # only once she is, and keep asking (a refusal is not the end of the scenario).
    seated = '@e[type=mcyukkuri:hat_boat,distance=..48,nbt={Passengers:[{Tags:["scn_mother"]}]}]'
    pond(sid, title, hint, watch, '约 1–2 分钟临产，之后看照护', [
        captain('mother'), yk('marisa', -2, 1, 'father', aquatic=True, yaw=-90, **pair_ready()),
        f'yukkuri scenario bond {sel("mother")} {sel("father")}',
        boat('birth', 'mother', sel('father'))],
        note=note, late=[Retry(f'yukkuri scenario birth {sel("mother")} {sel("father")} {outcome}', seated)])


boat_birth('birth_boat_caught', '船上接生·接住', '船上接住', 'caught', '母油坐在帽船里，岸上戴帽的父油察觉临产，用帽子接住新生儿')
boat_birth('birth_boat_missed', '船上接生·漏接', '船上漏接', 'missed',
           '没人接住：新生儿落进水里，等同溺水；母油产程中不能离开，两胎之间若孩子就在辫子够得着的地方才能救，产程结束后依次救起仍在水中的，父油若有自己的船会划过来；这一窝里约一半是普通魔理沙，同样要救',
           note='一胎可能不止一只；落水的新生儿不一定都救得及，每只的结局以现场为准。')

# ====================================================================================================== house (anchor: the middle of the room)
def house_pair():
    return [yk('house_marisa', -1, 1, 'a', yaw=0, lead=True, **pair_ready()), yk('house_reimu', 1, 1, 'b', yaw=0, **pair_ready())]


HOUSE_ACTIVITY = 'yukkuri household activity 24000 false true'
add('house_empty', 'house', '空屋·模拟人类活动', '空屋待搬入', '屋里没有家油：按“模拟人类活动”相当于住了一天（落下絮尘和食屑），随后有一只家油搬进来、选墙角、筑巢',
    '搬入几十秒，筑巢数分钟', [say('按观察台第一排右边的“模拟人类活动”，再用“推进”快进。', 'gold')], env=DAY, vantage=None,
    reads=[('读家油现状', 'yukkuri household status')])
add('house_pair', 'house', '一对家油入住', '一对家油', '一对家油住进房子：自己挑隐蔽墙角（两侧是墙或家具）、白天躲着睡、夜里找絮建窝、叼絮、铺窝、储食',
    '数分钟（可倍速）', house_pair(), env=DAY, vantage=None, reads=[('读家油现状', 'yukkuri household status')])

HNEST = 'mcyukkuri:house_dust_nest{Materials:6,Food:8,Arrivals:2,Entrance:"south"%s}'
add('house_eggs', 'house', '迁卵', '迁卵', '原窝已脏（Dirt 8）、东北角另有一个铺好的新窝：家油搬走卵，放进干净的窝里照看',
    '数分钟（可倍速）', [block(-5, 0, -2, HNEST % ',Dirt:8'), block(5, 0, -2, HNEST % ''), *house_pair(),
                       f'yukkuri scenario home {sel("a")} ~-5 ~ ~-2', f'yukkuri scenario home {sel("b")} ~-5 ~ ~-2'],
    env=DAY, vantage=None, reads=[('读家油现状', 'yukkuri household status')])

# ====================================================================================================== town
add('wild', 'town', '野良应对', '野良应对', '宠物（银章）在进食；饥饿的野生魔理沙走来讨吃，看宠物是不理会、报告主人，还是没忍住', '约 1–3 分钟', [
    yk('reimu', 0, 0, 'pet', grade=2, nutrition=38, yaw=-90, lead=True, boldness=60), *own('pet'), item('minecraft:apple', 2, 1, 0),
    yk('marisa', 6, 0, 'wild', nutrition=34, yaw=90, boldness=45)], vantage=(0, 12))

add('badge', 'town', '徽章与旁人', '徽章旁人', '把银章别在宠物灵梦身上，旁边的野生魔理沙会羡慕或嫉妒', '别上后约 1 分钟', [
    yk('reimu', 0, 0, 'pet', yaw=-90, lead=True), *own('pet'), yk('marisa', 6, 0, 'wild', yaw=90)], give=['mcyukkuri:silver_yukkuri_badge 1'], vantage=(0, 12))

add('exam', 'town', '考场', '考场', '带宠物灵梦去考官面前考试：紧张、作答、躲猫猫，合格得铜章', '约 1–2 分钟', [
    block(0, 0, 0, 'mcyukkuri:exam_podium[facing=east]'), block(-4, 0, 0, 'mcyukkuri:exam_hide_box[facing=east]'), block(4, 0, 0, 'minecraft:lectern[facing=west]'),
    human('caretaker_brother', 5, 0, 'judge', 3, yaw=90, label='考官'), yk('reimu', 2, 2, 'pet', yaw=0, lead=True), *own('pet')], vantage=(0, 12))

add('date', 'town', '相亲', '相亲', '付绿宝石请饲养员牵线，宠物灵梦与种油魔理沙害羞、靠近、蹭蹭', '约 1–2 分钟', [
    human('caretaker_brother', 4, 0, 'keeper', 4, yaw=0, label='饲养员'), yk('reimu', 0, 0, 'bride', grade=1, yaw=-90, lead=True), *own('bride'),
    yk('marisa', 8, 0, 'stud', grade=2, yaw=90), *own('stud')], give=['minecraft:emerald 8'], vantage=(0, 12))

add('pred_pets', 'town', '捕食种宠物', '捕食种宠物', '银章的蕾米莉亚听令忍住，没学会等待的芙兰（铜章，关在玻璃里）不听令，猎物看见捕食者会逃', '随意', [
    yk('remilia', 0, 0, 'rem', grade=2, yaw=-90, lead=True), *own('rem'), yk('reimu', 5, 0, 'prey', grade=1, yaw=90), *own('prey'),
    yk('flandre', 10, 0, 'fla', grade=1, yaw=90), *own('fla'), box(8, 0, -2, 12, 2, 2, 'minecraft:glass_pane', 'outline')], give=['mcyukkuri:field_notebook 1'], vantage=(0, 12))

add('transport', 'town', '运输与动手', '运输动手', '喷弹珠汽水喷雾让它睡着，再装篮、放出：仍是同一只；围栏里是野生个体', '随意', [
    yk('reimu', 0, 0, 'pet', grade=1, yaw=-90, lead=True), *own('pet'), box(6, 0, -3, 10, 1, 1, 'minecraft:oak_fence', 'outline'), yk('reimu', 8, -1, 'wild', yaw=90, label='野生灵梦')],
    give=['mcyukkuri:sleep_spray 4', 'mcyukkuri:yukkuri_carrier 2'], vantage=(0, 12))

add('walker', 'town', '牵宠散步', '牵宠散步', '爱派居民（哥哥和姐姐）牵着拴了绳的宠物灵梦在街上散步', '持续', [
    'yukkuri human walker ~ ~ ~ brother', 'yukkuri human walker ~6 ~ ~ sister', LEAD_ANY], vantage=(0, 12))

add('feed_wild', 'town', '喂野生油', '喂野生油', '爱派居民牵着宠物散步，遇到饿坏的野生魔理沙：会拿出食物喂它', '约 1–3 分钟', [
    'yukkuri human walker ~ ~ ~ brother', yk('marisa', 6, 3, 'wild', nutrition=20, yaw=90, lead=True)], vantage=(0, 12))

add('oniisan', 'town', '鬼威惨', '鬼威惨', '一名“鬼威惨”走在街上：油见到他会害怕、躲开、逃走', '持续', [
    'yukkuri human oniisan ~ ~ ~', yk('reimu', 8, 3, 'wild', yaw=90, lead=True)], vantage=(0, 12))

add('oniisan_prey', 'town', '鬼威惨·有猎物', '鬼威惨有猎物', '鬼威惨东侧 5 格放着一只无主野生灵梦：他会盯上它，旁边的油旁观或逃开', '持续', [
    'yukkuri human oniisan ~ ~ ~ prey'], vantage=(0, 12))

# ---- shop and factories: the whole building is placed by `human build`, which levels the lot and fills the shop itself
add('shop_build', 'shop', '整座商店', '整座商店', '商店整体放下：店主、货架上的在售个体和柜台后的银章爱丽丝；右键店主开店，登记在 shop list 里',
    '建筑几秒', ['yukkuri human build shop ~8 ~ ~17 south'], give=['minecraft:emerald 16'], vantage=(0, 30),
    reads=[('店铺登记（shop list）', 'yukkuri human shop list')])
for _kind, _caption, _pad in (('labor', '劳动工厂', -44), ('manju', '馒头工厂', 0), ('bag', '袋子工厂', 44)):
    add(f'factory_{_kind}', 'factory', _caption, _caption, f'{_caption}整体放下：监工、流水线工位和流水线上的个体', '建筑几秒',
        [f'yukkuri human build {_kind} ~{_pad} ~ ~17 south'], vantage=(_pad, 40), spot=None)

# ====================================================================================================== seasons and clips (buttons are built in build_museum_full.py)


# ====================================================================================================== which concern each scenario answers
# codes are the rows of the table in docs/EXPERIMENTAL_MAP.md
TOPICS = {
    'den_family': 'W1', 'dig': 'W2', 'indep_near': 'W3', 'indep_far': 'W3', 'den_move': 'W4', 'forage': 'W5', 'graze': 'W6', 'pet_birth': 'W7',
    'sleep': 'D1', 'meet': 'D1', 'meal': 'D1', 'toilet': 'F5', 'hurt': 'F3', 'hurt_kin': 'F3',
    'birth_land_caught': 'F1', 'birth_land_missed': 'F1', 'feed': 'F2', 'first_toilet': 'F2', 'hop': 'F2', 'growth': 'F4',
    'rain_shelter': 'R1', 'thunder': 'R1', 'rain_open': 'R1', 'pred_watch': 'R2', 'pred_hunt': 'R2', 'pred_pets': 'R2', 'marks': 'R3', 'orange': 'R3',
    'sail': 'A1', 'shore': 'A1', 'shore_child': 'A1', 'low': 'A1', 'high': 'A1', 'voyage': 'A1', 'braid': 'A4', 'afloat': 'A2', 'shake': 'A2',
    'birth_boat_caught': 'A3', 'birth_boat_missed': 'A3', 'shore_baby': 'A4', 'shore_babies': 'A4', 'rescue_baby': 'A4', 'self_rescue': 'A4', 'couple_boats': 'A5',
    'house_empty': 'H1', 'house_pair': 'H2', 'house_eggs': 'H3',
    'shop_build': 'M1', 'factory_labor': 'M2', 'factory_manju': 'M2', 'factory_bag': 'M2', 'walker': 'M3', 'feed_wild': 'M3', 'oniisan': 'M4', 'oniisan_prey': 'M4',
    'wild': 'M5', 'badge': 'M5', 'exam': 'M5', 'date': 'M5', 'transport': 'M5',
}
for _s in S:
    _s.asks = TOPICS.get(_s.id) or ('B1' if _s.id.startswith('settle_') else '')
    assert _s.asks, _s.id
