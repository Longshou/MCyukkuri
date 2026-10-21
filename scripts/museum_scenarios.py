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
from museum_dsl import (yk, human, item, block, box, shed, home, sel, sel_all, say, uid, uuid_nbt, family, remains, _text)  # noqa: E402
from museum_fields import BY_ID, FEET  # noqa: E402

CHILDHOOD_CAN_JUMP_SOON = 'Version:1,Newborn:1b,Landed:1b,FirstToiletDone:1b,Crawled:1b,Jumped:0b,CrawlDistance:1.0d'
# A real infant ready to follow its parent to the bank: it has already eaten, relieved itself and crawled, but cannot jump.
CHILDHOOD_CRAWLING_READY = 'Version:1,Newborn:1b,Landed:1b,FirstMealDone:1b,FirstToiletDone:1b,Crawled:1b,Jumped:0b,CrawlDistance:1.0d'
OWNED = 'NeoForgeData.HumanCommerce'
# A subadult in the fixed age interval, ready to live independently.
TEEN_FAMILY = ('Version:2,Initialized:1b,Stage:"JUVENILE",Age:276000L,Nourishment:276000L,Maturity:336000L,Lifespan:1056000L,AdultScale:1f,Scale:0.72f')
LATE_COMMANDS = ('yukkuri scenario aquatic_colony', 'yukkuri scenario home', 'yukkuri scenario birth', 'yukkuri scenario eggs', 'yukkuri scenario boat', 'yukkuri scenario shore')
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
    out = [yk(mother_kind, 0, dz, 'mother', uuid=m, yaw=180, lead=not lead_baby, kin=f'Partner:{uuid_nbt(f)}', **pair_ready()),
           yk(father_kind, 2, dz, 'father', uuid=f, yaw=180, kin=f'Partner:{uuid_nbt(m)}', **pair_ready())]
    if baby:
        out.append(yk('reimu', 1, dz + 1, 'child', stage='baby', uuid=c, yaw=180, lead=lead_baby, nutrition=95, kin=f'Mother:{uuid_nbt(m)},Father:{uuid_nbt(f)}'))
    return out, (m, f, c)


_o, _ = adult_pair('den_family')
add('den_family', 'den', '家庭洞·一家三口', '家庭洞', '父母和赤油住进土丘洞：识别为同一个家（status 的 home.kind 是 burrow），夜里回洞、储粮、外出',
    '数分钟（可倍速）', _o, env=DAY, spot='family', vantage=(0, 12),
    reads=[('读家的识别', 'yukkuri status @e[tag=scn_mother,limit=1,sort=nearest,distance=..60]')])

_wm, _wf, _wc = (uid('widow_family', name) for name in ('mother', 'father', 'child'))
add('widow_family', 'den', '丧偶家庭·继续生活', '丧偶家庭',
    '先等一家三口入住，再亲手杀死父油；五倍速观察母油采草、存粮、喂子，孩子留在家中，不能只反复进出洞。观察台可读母油的 Load、Delivered 和孩子营养',
    '入住后数分钟（可五倍速）', [
        yk('reimu', 0, 3, 'mother', uuid=_wm, yaw=180, lead=True, nutrition=90, energy=100, kin=f'Partner:{uuid_nbt(_wf)}'),
        yk('marisa', 2, 3, 'father', uuid=_wf, yaw=180, nutrition=90, energy=100, kin=f'Partner:{uuid_nbt(_wm)}'),
        yk('reimu', 1, 4, 'child', stage='child', uuid=_wc, yaw=180, nutrition=55, kin=f'Mother:{uuid_nbt(_wm)},Father:{uuid_nbt(_wf)}')],
    late=[f'yukkuri scenario home {sel(n)} ~ ~ ~-1' for n in ('mother', 'father', 'child')],
    env=DAY, spot='family', vantage=(0, 12),
    reads=[('读单亲觅食', f'yukkuri status {sel("mother")} raw'),
           ('读孩子生活', f'yukkuri status {sel("child")} raw')])


def independence(sid, title, hint, watch, parent_stays):
    pm, pc = uid(sid, 'parent'), uid(sid, 'teen')
    # the teen's independence is armed in NBT (Force); the family finds the den by itself like any family, and the farewell/departure is ordinary
    lines = [yk('reimu', 0, 3, 'parent', uuid=pm, yaw=180, lead=False, **pair_ready()),
             yk('reimu', 1, 4, 'teen', stage='teen', uuid=pc, yaw=180, lead=True, nutrition=100, energy=100, kin=f'Mother:{uuid_nbt(pm)}',
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
        yk('reimu', 0, 3, 'mother', uuid=_sm, yaw=180, lead=True, kin=f'Partner:{uuid_nbt(_sf)}', **pair_ready()),
        yk('marisa', 2, 3, 'father', uuid=_sf, yaw=180, kin=f'Partner:{uuid_nbt(_sm)}', **pair_ready()),
        yk('reimu', 1, 4, 'child', stage='teen', uuid=_sc, yaw=180, nutrition=100, energy=100, kin=f'Mother:{uuid_nbt(_sm)},Father:{uuid_nbt(_sf)}', fam=TEEN_FAMILY),
        ],
    late=[f'yukkuri scenario home {sel(n)} ~ ~ ~-1' for n in ('mother', 'father', 'child')],
    env=DAY, spot='small', vantage=(8, 14), reads=[('读搬家说明', f'yukkuri status {sel("mother")} raw')])

# ====================================================================================================== vagrants and foraging
add('dig', 'forage', '流浪油挖穴', '流浪挖穴', '没有家的成年流浪油走到土丘边：先挖通地面，再向上挖内部第二层；入口保留一格高、内部两格高，每块约 6 秒，挖完自成一个家',
    '约 3–6 分钟（可倍速）', [
        yk('marisa', 0, 0, 'vagrant', yaw=180, lead=True, **pair_ready()),
        'gamerule mobGriefing true'], env=DAY, spot='dig', vantage=(0, 12),
    reads=[('读挖穴阶段', f'yukkuri status {sel("vagrant")} raw')])

_fm, _ff, _fc = uid('forage', 'mother'), uid('forage', 'father'), uid('forage', 'child')
add('forage', 'forage', '外出觅食', '外出觅食', '5×5 土丘洞里的一家：储粮空、家旁的草已稀疏，父母各自向远处草场出发；实际采草、衔回并存入洞中。读数看目标半径、最远距离和送回次数',
    '数分钟（可倍速）', [
        yk('marisa', 0, 3, 'father', uuid=_ff, yaw=180, lead=True, kin=f'Partner:{uuid_nbt(_fm)}', nutrition=60, energy=100),
        yk('reimu', 2, 3, 'mother', uuid=_fm, yaw=180, kin=f'Partner:{uuid_nbt(_ff)}', nutrition=60, energy=100),
        yk('reimu', 1, 4, 'child', stage='baby', uuid=_fc, yaw=180, nutrition=50, kin=f'Mother:{uuid_nbt(_fm)},Father:{uuid_nbt(_ff)}'),
        ],
    late=[f'yukkuri scenario home {sel(n)} ~ ~ ~-1' for n in ('mother', 'father', 'child')],
    env=DAY, spot='forage', vantage=(0, 16),
    reads=[('读觅食数据（父）', f'yukkuri status {sel("father")} raw'), ('读觅食数据（母）', f'yukkuri status {sel("mother")} raw')])

add('graze', 'forage', '吃草', '吃草', '左边的饿灵梦身边既有苹果又有草，会先取普通食物；右边的饿魔理沙在八格外、只见草，啃下后会露出苦味反应。草仍会退回上一阶、日后重新长出',
    '约 10–60 秒', [
        yk('reimu', -6, 0, 'a', nutrition=28, yaw=180, lead=True), item('minecraft:apple', 1, -4, 0, name='apple'),
        yk('marisa', 6, 0, 'b', nutrition=34, yaw=180)], env=DAY, spot='graze', vantage=(0, 12))

# ====================================================================================================== biome settlements
BIOMES = BY_ID['biomes']


def settlement(plot, title, kind, wait):
    from museum_fields import BIOME_PLOTS
    biome = next(p[1] for p in BIOME_PLOTS if p[0] == plot)
    # A plains village reaches four blocks into the snow plot next door: its whole footprint becomes plains ground first.
    ground = BIOMES.plains_site(*BIOMES.spots[plot]) if plot == 'plains' else [f'fillbiome ~-22 56 ~-22 ~22 100 ~22 {biome}']
    add(f'settle_{plot}', 'biomes', f'{title}聚落', title, f'{title}群系里自然生成的{"村落" if kind == "village" else "营地"}：成员、住所、仓堆随群系特色而不同', wait,
        [*ground, f'yukkuri settlement spawn {kind} ~ ~ ~', LEAD_ANY], env=DAY, spot=plot,
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
    '橙身上有一处磕伤，旁边是认得它的伴侣爱丽丝。惊慌过去后，爱丽丝会走近舔舐：完整舔舐让最老的一处 1–2 级伤降一级并回 2 血，三级伤只安抚。橙汁治伤补满血；面粉糊只修疤',
    '先看舔舐；橙汁治伤，若愈合留疤再用面粉糊', [
        yk('chen', 0, 0, 'a', lead=True),
        yk('alice', 4, 0, 'mate', yaw=90, **pair_ready()),
        f'yukkuri scenario bond {sel("a")} {sel("mate")}',
        f'yukkuri scenario mate {sel("a")} {sel("mate")}',
        f'yukkuri scenario wound {sel("a")}'], give=['mcyukkuri:orange_juice 2', 'mcyukkuri:flour_paste 2'], vantage=None)

add('meal', 'meadow', '饿了取食', '饿了取食', '灵梦饿了：自己走到苹果旁、拾起、咀嚼', '约 1 分钟', [
    yk('reimu', 0, 0, 'a', nutrition=32, yaw=-90, lead=True), item('minecraft:apple', 3, 3, 0)], vantage=None)

add('toilet', 'meadow', '厕所排便', '厕所排便', '橙有强烈便意：看它走向右边的厕所；拆掉厕所再看它在地面排便（从油菊排出）', '约 1 分钟', [
    block(6, 0, 0, 'mcyukkuri:yukkuri_toilet'), yk('chen', 0, 0, 'a', bladder=95, yaw=-90, lead=True)], vantage=None)

# ====================================================================================================== family and growth
def land_birth(sid, title, hint, outcome, father_kind, watch, spot, father_x=4):
    add(sid, 'family', title, hint, watch, '约 1–2 分钟临产，之后看照护', [
        *shed(8, 0),
        yk('reimu', 7, 0, 'mother', yaw=-90, lead=True, **pair_ready()),
        yk(father_kind, 4, 1, 'father', yaw=-90, **pair_ready()),
        f'yukkuri scenario bond {sel("mother")} {sel("father")}',
        home('mother', 8, 0),
        f'yukkuri scenario birth {sel("mother")} {sel("father")} {outcome}'], spot=spot, vantage=(4, 12),
        late=[f'tp {sel("father")} ~{father_x} ~ ~1'] if father_x != 4 else [],
        reads=[('读母油产程', f'yukkuri status {sel("mother")} raw'), ('读父油托帽', f'yukkuri status {sel("father")} raw')])


land_birth('birth_land_caught', '陆地接生·接住', '陆地接住', 'caught', 'marisa',
           '父油（戴帽魔理沙）察觉临产，走来摘帽翻面放好；母油走到实际帽后方并转向，到位后生产，新生儿落进帽里；之后放出幼体、戴回帽子', 'land_caught')
land_birth('birth_land_reunion', '陆地接生·远处会合', '接生会合', 'caught', 'marisa',
           '父油从母油十余格外出发，先寻母、双方走到共同选定的安全位置，再摘帽放好；观察途中不会被普通觅食或闲逛抢走，母油对准真实帽子后生产',
           'land_caught', father_x=-10)
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

add('first_toilet', 'family', '首餐后首便', '首餐后首便', '新生赤油尚未吃过第一餐：先观察它求食而不排便，再把手里的苹果丢到亲油身边。亲油嚼糜、近身递喂，孩子实际吞下后才请求首便辅助', '丢下苹果后约 1.5 分钟', [
    yk('chen', 0, 0, 'mom', yaw=-90, **pair_ready()),
    yk('reimu', 3, 0, 'baby', stage='baby', nutrition=32, bladder=30, yaw=90, lead=True),
    f'yukkuri scenario kin {sel("mom")} {sel("baby")} true'], give=['minecraft:apple 3'], spot='first_toilet', vantage=(0, 10),
    reads=[('读首餐与首便', f'data get entity {sel("baby")} YukkuriChildhood')])

add('hop', 'family', '学起跳', '学起跳', '子油先爬行试探，亲油示范起跳，子油跟着练到学会', '约 1–2 分钟', [
    yk('reimu', 0, 0, 'mom', yaw=-90, **pair_ready()),
    yk('chen', 3, 0, 'kid', stage='child', yaw=90, lead=True, childhood=CHILDHOOD_CAN_JUMP_SOON),
    f'yukkuri scenario kin {sel("mom")} {sel("kid")} false'], spot='hop', vantage=(0, 10))

add('hurt_kin', 'family', '亲子受伤舔舐', '亲子舔舐',
    '小橙磕伤了，亲油灵梦认得它。惊慌过去后，亲油会走近舔舐：完整舔舐让最老的一处 1–2 级伤降一级并回 2 血，三级伤只安抚。橙汁治伤补满血；面粉糊只修疤',
    '先看舔舐；橙汁治伤，若愈合留疤再用面粉糊', [
        yk('reimu', 0, 0, 'mom', yaw=-90, **pair_ready()),
        yk('chen', 4, 0, 'kid', stage='child', yaw=90, lead=True, nutrition=90, energy=95),
        f'yukkuri scenario kin {sel("mom")} {sel("kid")} false',
        f'yukkuri scenario wound {sel("kid")}'], give=['mcyukkuri:orange_juice 2', 'mcyukkuri:flour_paste 2'], spot='hurt_kin', vantage=(0, 10))

add('growth', 'family', '一路长大', '一路长大', '右侧亚成从335999 tick开始，下一模拟tick成年；其余赤油、子油按固定年龄成长，营养不成为第二条时钟，可用倍速观察',
    '成年边界1 tick；其余可倍速观察', [
        yk('chen', 0, 0, 'parent', yaw=180, **pair_ready()),
        yk('reimu', -3, 2, 'baby', stage='baby', yaw=180), yk('reimu', -1, 3, 'child', stage='child', yaw=180),
        yk('reimu', 1, 3, 'teen', stage='teen', share=1, yaw=180, lead=True), yk('reimu', 3, 2, 'baby2', stage='baby', yaw=180),
        *[f'yukkuri scenario kin {sel("parent")} {sel(k)} false' for k in ('baby', 'child', 'teen', 'baby2')],
        item('minecraft:apple', 6, 0, 4)], env=DAY, spot='growth', vantage=(0, 10),
    reads=[('读成长与阶段', f'yukkuri status {sel("teen")}')])

_fd = {k: uid('family_distance', k) for k in ('parent_near', 'child_near', 'parent_bold', 'child_bold')}
add('family_distance', 'family', '亲子距离·性格对照', '亲子距离',
    '两家亲子都相距 4 格：普通子油更早回到亲油身边，大胆子油可以多活动一段；亲油尊重孩子的活动范围，离得过远才主动寻回',
    '约 30–60 秒', [
        yk('reimu', -7, -5, 'parent_near', uuid=_fd['parent_near'], label='普通亲油', **pair_ready()),
        yk('reimu', -3, -5, 'child_near', uuid=_fd['child_near'], stage='child', label='普通子油', boldness=40,
           kin=f'Mother:{uuid_nbt(_fd["parent_near"])}', lead=True, **pair_ready()),
        yk('reimu', -7, 5, 'parent_bold', uuid=_fd['parent_bold'], label='另一亲油', **pair_ready()),
        yk('reimu', -3, 5, 'child_bold', uuid=_fd['child_bold'], stage='child', label='大胆子油', boldness=95,
           friendliness=70, attachment=35, self_control=70, kin=f'Mother:{uuid_nbt(_fd["parent_bold"])}', **pair_ready())],
    env=DAY, spot='hurt_kin', vantage=(-6, 14),
    reads=[('读普通子油', f'yukkuri status {sel("child_near")} raw'), ('读大胆子油', f'yukkuri status {sel("child_bold")} raw')])

_fs = {k: uid('family_search', k) for k in ('parent', 'child')}
add('family_search', 'family', '亲子走散后寻回', '走散寻回',
    '无巢的一对亲子相隔 14 格：观察孩子寻亲、亲油主动接近，重新靠近后恢复自由活动；不会传送或生成替代亲人',
    '约 30–60 秒', [
        yk('reimu', -7, 0, 'search_parent', uuid=_fs['parent'], label='寻子亲油', lead=True, **pair_ready()),
        yk('reimu', 7, 0, 'search_child', uuid=_fs['child'], stage='child', label='走散子油', boldness=40,
           kin=f'Mother:{uuid_nbt(_fs["parent"])}', **pair_ready())],
    env=DAY, spot='hurt_kin', vantage=(0, 12),
    reads=[('读寻子亲油', f'yukkuri status {sel("search_parent")} raw'), ('读寻亲子油', f'yukkuri status {sel("search_child")} raw')])

# ====================================================================================================== weather and predators
add('rain_shelter', 'weather', '下雨躲雨', '下雨躲雨', '下雨了：6–16 格外有一块石顶，三只油先“跑向”遮雨处（weather:run），到了就“等待”（weather:wait）',
    '约 30 秒', [
        yk('reimu', 0, 0, 'a', yaw=-90, lead=True), yk('marisa', 2, 3, 'b', yaw=-90), yk('chen', -2, 2, 'c', yaw=-90)],
    env={'time': 'noon', 'weather': 'rain'}, spot='shelter', vantage=(0, 14),
    reads=[('读反应（behavior.reaction）', f'yukkuri status {sel("a")} raw')])

add('thunder', 'weather', '雷暴', '雷暴', '雷暴天气：同样的石顶，反应更强烈', '约 30 秒', [
    yk('reimu', 0, 0, 'a', yaw=-90, lead=True), yk('marisa', 2, 3, 'b', yaw=-90)],
    env={'time': 'noon', 'weather': 'thunder'}, spot='thunder', vantage=(0, 14))

add('rain_open', 'weather', '空地淋雨', '空地找掩体', '下雨时初始附近没有屋顶：油会边跑边找；发现可达屋顶后转向避雨，雨停后抖掉水', '持续，可中途搭顶或放晴', [
    yk('reimu', 0, 0, 'a', yaw=-90, lead=True), yk('marisa', 2, 3, 'b', yaw=-90)],
    env={'time': 'noon', 'weather': 'rain'}, spot='open', vantage=(0, 14),
    reads=[('读找掩体状态', f'yukkuri status {sel("a")} raw')])

add('pred_watch', 'weather', '看见捕食种', '看见捕食种',
    '蕾米莉亚（不饿）在 12 格外：普通个体还没挨打就开始后退（threat:retreat），离开威胁后躲藏；不饿的捕食者不会主动追猎',
    '约 1 分钟', [
        yk('reimu', 0, 0, 'a', yaw=-90, lead=True), yk('marisa', 0, 2, 'b', yaw=-90),
        yk('remilia', 12, 0, 'pred', yaw=90, nutrition=100, energy=100)],
    env=DAY, spot='pred', vantage=(6, 14), reads=[('读反应', f'yukkuri status {sel("a")} raw')])

add('pred_hunt', 'weather', '被捕食种追猎', '被追猎', '芙兰饿了（营养 10）盯上 12 格外的普通个体：被追猎时的逃跑与躲藏', '约 1 分钟', [
    yk('reimu', 0, 0, 'a', yaw=-90, lead=True), yk('flandre', 12, 0, 'pred', yaw=90, nutrition=100, energy=100),
    set_status('pred', 'needs.nutrition', 10)],
    env=DAY, spot='pred', vantage=(6, 14), reads=[('读反应', f'yukkuri status {sel("a")} raw')])

add('pred_range', 'weather', '捕食种远处觅食', '远处觅食',
    '饥饿芙兰与灵梦相距 30 格：观察远处发现、起飞追击、猎物逃跑和接触咬击；一口咽完仍饿时继续觅食',
    '约 1–2 分钟', [
        yk('reimu', -15, 0, 'range_prey', yaw=-90, **pair_ready()),
        yk('flandre', 15, 0, 'range_pred', yaw=90, lead=True, nutrition=100, energy=100),
        set_status('range_pred', 'needs.nutrition', 10)],
    env=DAY, spot='pred', vantage=(0, 18),
    reads=[('读远猎', f'yukkuri status {sel("range_pred")} raw'), ('读远猎猎物', f'yukkuri status {sel("range_prey")} raw')])

add('marks', 'weather', '脏污与伤痕', '脏污伤痕', '前三只是干净、脏污、疤痕对照；另两只有 1／2 级裂伤，衣食充足时自然闭合，再按原伤级概率留疤。面粉糊只去已有疤痕，不治未闭合伤口；橙汁不参与', '约 20–40 秒；未留疤可重开观察概率差异', [
    yk('reimu', -6, 0, 'clean', yaw=180, lead=True), yk('reimu', -3, 0, 'dirty', yaw=180), yk('reimu', 0, 0, 'scarred', yaw=180),
    yk('reimu', 4, 0, 'light_tear', yaw=180, **pair_ready()), yk('reimu', 8, 0, 'heavy_tear', yaw=180, **pair_ready()),
    *[set_status(name, 'marks.scars', 0) for name in ('clean', 'dirty', 'scarred')],
    set_status('dirty', 'marks.dirt', 3), set_status('scarred', 'marks.scars', 1),
    set_status('light_tear', 'marks.scars', 0), set_status('heavy_tear', 'marks.scars', 0),
    set_status('light_tear', 'health.wound_at', 'front:tear:1'), set_status('heavy_tear', 'health.wound_at', 'front:tear:2')],
    give=['mcyukkuri:flour_paste 4'], spot='marks', vantage=(1, 10),
    reads=[('读轻伤留疤', f'yukkuri status {sel("light_tear")} raw'),
           ('读二级伤留疤', f'yukkuri status {sel("heavy_tear")} raw'),
           ('读预置疤痕', f'yukkuri status {sel("scarred")} raw')])

# ====================================================================================================== pond (anchor: the bank cell; hull water is four blocks east, one lower)
BANK, WATER = '~ ~ ~', '~4 ~-1 ~'
SHORE_CALL = 'schedule function mcylab:util/shore_call 3s replace'     # ask the owner to come back once the boat floats


def boat(mode, parent, others):
    return f'yukkuri scenario boat {mode} {sel(parent)} {others} {BANK} {WATER}'


def captain(name='a', *, dx=0, dz=0, **kw):
    return yk('marisa', dx, dz, name, aquatic=True, yaw=-90, lead=True, **pair_ready(), **kw)


def pond(sid, title, hint, watch, wait, lines, note='', **kw):
    add(sid, 'pond', title, hint, watch, wait, lines, env=DAY, note=note, vantage=(-6, 6, -90), **kw)


_cargo_boat = '@e[type=mcyukkuri:hat_boat,distance=..48,limit=1,sort=nearest]'

def shore_supplies(dx=-4, dz=0):
    # Finite real stacks on dry ground. Ordinary pickup/provision goals must acquire them.
    return [item('minecraft:stick', 1, dx, dz, 'paddle_supply'),
            item('minecraft:apple', 3, dx + 1, dz - 1, 'food_supply')]


pond('sail', '备粮划桨', '自主出航', '成体从干岸自主拾桨、备粮、找放船位置、放帽并登船；现场只有一根木棍和三份苹果，不预先造船或指定动作', '约 1–3 分钟',
    [captain(dx=-5), *shore_supplies()],
    note='动机沿用水生种回水上生活的普通规则。没有出航时读 water.launch 的最近判定和实际阶段；失败保留现场。',
    reads=[('读船主与容量', f'yukkuri status {sel("a")} raw'),
           ('读实船库存', f'data get entity {_cargo_boat} Cargo')])
pond('hat_custody', '暂放帽船与没收', '托帽与没收',
    '亲油站在岸上，自己的帽船停在水面并载着赤油。暂放不悲伤；站在干岸上空手蹲下右击帽船，观察赤油安全上岸、帽子进入物品栏和主人失帽反应',
    '手动操作', [captain(), yk('marisa', -2, 1, 'kid', stage='baby', aquatic=True, yaw=-90, **pair_ready()), boat('unattended', 'a', sel('kid'))],
    note='再把同一顶帽子归还主人，核对只回收一顶帽、幼体仍在、主人能继续放船。没有安全落点时没收应失败。',
    reads=[('读托管与失帽', f'yukkuri status {sel("a")} raw'), ('读没收后的赤油', f'yukkuri status {sel("kid")} raw'),
           ('读实船凭据与库存', f'data get entity {_cargo_boat}')])
pond('hat_custody_burn', '暂放帽船·烧毁边界', '实船烧毁',
    '亲油暂放的真实帽船受到一次火焰伤害，走正常烧毁结算；帽主失去原帽、赤油落水、库存只散落一次，不生成可回收的新帽',
    '约 10 秒', [captain(), yk('marisa', -2, 1, 'kid', stage='baby', aquatic=True, yaw=-90, **pair_ready()), boat('unattended', 'a', sel('kid'))],
    late=[f'damage {_cargo_boat} 6 minecraft:in_fire'],
    note='这次外力只设置真实起因，不固定后续状态。重复试前先清场；不可把普通卸载或清场当作烧毁。',
    reads=[('读烧毁后的帽主', f'yukkuri status {sel("a")} raw'), ('读落水赤油', f'yukkuri status {sel("kid")} raw'),
           ('读剩余实船', f'data get entity {_cargo_boat}')])
for _cargo_mode, _cargo_title, _cargo_hint, _cargo_watch in (
        ('cargo_empty', '帽内粮堆·空仓', '空仓', '亲油帽内没有粮堆；持食物逐份存入，观察粮堆从空仓到满载的变化'),
        ('cargo_full', '帽内粮堆·满载', '11 粮＋1 桨', '11 份粮食和一根桨占满 12 份容量；再存粮应留在手中，亲油仍有自己的位置。保存重进核对同船与真实库存')):
    pond(_cargo_mode, _cargo_title, _cargo_hint, _cargo_watch, '随意',
         [captain(), boat(_cargo_mode, 'a', sel('a'))], give=['minecraft:apple 8', 'minecraft:carrot 8'],
         note='“读船粮”可看真实库存；读档检查请重进当前世界，不要重新生成场景。吃掉的口粮不会恢复。',
         reads=[('读船主与容量', f'yukkuri status {sel("a")} raw'), ('读船粮与实物桨', f'data get entity {_cargo_boat} Cargo')])
pond('cargo_load', '孩子在帽内·再装粮', '先载幼体', '赤油占 2 份、桨占 1 份，最多再装 9 份粮；持食物逐份存入，观察容量与落脚空间，装不下的留在手中', '随意', [
    captain(), yk('marisa', -2, 1, 'kid', stage='baby', aquatic=True, yaw=-90, **pair_ready()), boat('cargo_load', 'a', sel('kid'))],
    give=['minecraft:apple 8', 'minecraft:carrot 8'], note='对比先装满粮再接孩子的场景；还可潜行一次尝试存入多份，观察是否穿粮或挤落。',
    reads=[('读船主与容量', f'yukkuri status {sel("a")} raw'),
           ('读幼体与初航', f'yukkuri status {sel("kid")} raw'),
           ('读实船库存', f'data get entity {_cargo_boat} Cargo')])
pond('cargo_rescue', '满粮帽船·弃粮救援', '满粮救援', '亲油带着 11 份口粮与一根桨，赤油真实落水；亲油主动抛下挡住救援的粮食，腾出位置再接回孩子，船桨保留', '约 30–90 秒', [
    captain(), yk('marisa', -2, 1, 'kid', stage='baby', aquatic=True, yaw=-90, **pair_ready()), boat('cargo_rescue', 'a', sel('kid'))],
    note='看真实食物落到水里、库存随之减少；观察孩子接回后能否安稳留在帽内，不应立即捡粮重新挡住它。',
    reads=[('读船主与容量', f'yukkuri status {sel("a")} raw'),
           ('读幼体与初航', f'yukkuri status {sel("kid")} raw'),
           ('读实船库存', f'data get entity {_cargo_boat} Cargo')])
pond('cargo_stow', '满载帽船·暂放再取', '暂放再取', '7 份粮、一根桨和一只子油共占 12 份；按“夜晚”观察亲油为帽内休息把粮移到可用侧帽檐，天亮空间允许时再收回', '观察一轮昼夜', [
    captain(), yk('marisa', -2, 1, 'kid', stage='child', aquatic=True, yaw=-90, **pair_ready()), boat('cargo_stow', 'a', sel('kid'))],
    note='暂放仍计入总容量，有实物位置且总数保持；中途保存退出再重进当前世界，核对暂放位置和总粮数。再试返岸收帽、重新放帽，不能复制口粮。',
    reads=[('读船主与容量', f'yukkuri status {sel("a")} raw'),
           ('读幼体与初航', f'yukkuri status {sel("kid")} raw'),
           ('读实船库存', f'data get entity {_cargo_boat} Cargo')])
pond('shore', '返岸收帽', '返岸收帽', '亲油划回岸边，自己跳上岸、收回帽子（至多 1200 tick）', '约 1 分钟', [captain(), boat('shore', 'a', sel('a')), SHORE_CALL],
    reads=[('读船主与容量', f'yukkuri status {sel("a")} raw'),
           ('读实船库存', f'data get entity {_cargo_boat} Cargo')])
pond('shore_child', '带幼体返岸', '带幼体返岸', '船上有一只子油时，亲油带着它划回岸边，一起上岸、收帽', '约 1 分钟', [
    captain(), yk('marisa', -2, 1, 'kid', stage='child', aquatic=True, yaw=-90, **pair_ready()), boat('shore', 'a', sel('kid')), SHORE_CALL],
    reads=[('读船主与容量', f'yukkuri status {sel("a")} raw'),
           ('读幼体与初航', f'yukkuri status {sel("kid")} raw')])
pond('shore_babies', '两只赤油返岸', '两只赤油返岸', '船上是两只赤油：亲油用辫子把它们一只只放上岸，收帽后留在岸边陪着', '约 1–2 分钟返岸，之后亲油留守', [
    captain(), yk('marisa', -2, 1, 'k1', stage='baby', aquatic=True, yaw=-90, also=('young',), **pair_ready()),
    yk('marisa', -2, -1, 'k2', stage='baby', aquatic=True, yaw=-90, also=('young',), **pair_ready()), boat('shore', 'a', sel_all('young')), SHORE_CALL],
    reads=[('读船主与容量', f'yukkuri status {sel("a")} raw'),
           ('读全部幼体', f'yukkuri status {sel_all("young")} raw')])
pond('high', '四幼体高载荷', '四幼体', '四只赤油占 8 份，加三份粮和一根桨共 12 份，观察满载吃水、座位与继续装粮被拒绝', '约 1–3 分钟', [
    captain(), *[yk('marisa', -2 - i % 2, i // 2 * 2 - 1, f'k{i}', stage='baby', aquatic=True, also=('young',), **pair_ready()) for i in range(4)],
    boat('high', 'a', sel_all('young'))],
    reads=[('读船主与容量', f'yukkuri status {sel("a")} raw'),
           ('读全部幼体', f'yukkuri status {sel_all("young")} raw'),
           ('读实船库存', f'data get entity {_cargo_boat} Cargo')])
pond('voyage', '第一次出航', '第一次出航', '小子油在亲油的帽船上放下帽子、跳进自己的帽船、试坐，第一次划出去再回来', '约 2–3 分钟', [
    captain(), yk('marisa', -2, 1, 'kid', stage='child', aquatic=True, yaw=-90, **pair_ready()), boat('voyage', 'a', sel('kid'))],
    reads=[('读船主与容量', f'yukkuri status {sel("a")} raw'),
           ('读幼体与初航', f'yukkuri status {sel("kid")} raw')])
pond('braid', '落水·亲油救援', '亲油救援', '孩子掉进水里，亲油伸出辫子托起孩子放回帽中（孩子留在船上）', '约 30–90 秒', [
    captain(), yk('marisa', -2, 1, 'kid', stage='child', aquatic=True, yaw=-90, **pair_ready()), boat('braid', 'a', sel('kid'))],
    reads=[('读亲油救援与自救', f'yukkuri status {sel("a")} raw'),
           ('读落水幼体', f'yukkuri status {sel("kid")} raw')])
pond('rescue_baby', '赤油落水救援', '赤油落水', '船上的赤油被摇落水：亲油救援', '约 1–2 分钟', [
    captain(), yk('marisa', -2, 1, 'kid', stage='baby', aquatic=True, yaw=-90, **pair_ready()), boat('drop', 'a', sel('kid'))],
    reads=[('读亲油救援与自救', f'yukkuri status {sel("a")} raw'),
           ('读落水幼体', f'yukkuri status {sel("kid")} raw')])
pond('self_rescue', '落水自救', '落水自救', '小子油自己落水：抖落帽子、顶正、爬回帽上', '约 30–90 秒', [
    yk('marisa', 0, 0, 'a', stage='child', aquatic=True, yaw=-90, lead=True, **pair_ready()), boat('self', 'a', sel('a'))],
     note='演示时可开 /yukkuri aqdiag on，在日志里看甩帽、咬缘、攀入各阶段。',
    reads=[('读自救阶段与失败', f'yukkuri status {sel("a")} raw')])
pond('self_far', '远帽落水自救', '远帽自救', '帽主在离自己帽船九格的水中，观察她是否持续向同一帽子靠近，接触后抓缘攀回；途中继续承受入水伤害', '约 1 分钟',
     [captain(), boat('self_far', 'a', sel('a'))], reads=[('读自救目标与阶段', f'yukkuri status {sel("a")} raw')])
pond('self_tired', '疲惫落水返帽', '疲惫弱漂', '同样离帽船九格，帽主只有 3 点体力；观察向帽子弱漂与挣扎，抓握和攀入仍需能力、体力与实际接触，不保证获救', '约 1 分钟',
     [captain(), boat('self_tired', 'a', sel('a'))], reads=[('读疲惫与自救', f'yukkuri status {sel("a")} raw')])
pond('shore_capsized', '从岸上找回翻帽', '岸上返帽', '帽主在岸内六格，自己的帽船翻在水边；观察实际走向帽子、下水扶正和重新登船，托管期间不因无帽持续悲伤', '约 1–2 分钟',
     [captain(), boat('return_capsized', 'a', sel('a'))], reads=[('读岸上返帽', f'yukkuri status {sel("a")} raw')])
for _sid, _title, _hint, _friendliness in (
        ('capsize_caring', '翻船决策·高友善', '友善翻船', 90),
        ('capsize_selfish', '翻船决策·低友善', '自保翻船', 10)):
    pond(_sid, _title, _hint,
         '同一只亲油和赤油真实翻船落水。按友善和熟悉、紧急加成抽一次先救子或先自保；救子须先翻正原帽、游近抓握、托到受支撑帽面，体力或接触失败仍可能失手',
         '约 1 分钟', [captain(friendliness=_friendliness, attachment=60),
                      yk('marisa', -2, 1, 'kid', stage='baby', aquatic=True, yaw=-90, **pair_ready()),
                      boat('capsize_family', 'a', sel('kid'))],
         note='两组改变选择概率，不保证每次同样结果；观察孩子获救而亲油尚在水中的过程。',
         reads=[('读翻船亲油', f'yukkuri status {sel("a")} raw'), ('读翻船赤油', f'yukkuri status {sel("kid")} raw')])
pond('capsize_siblings', '翻船救援·两个孩子', '连续救子',
    '亲油与两只赤油从同一帽船翻落水中；若本次选择先救子，成功托回一只后继续原名单中另一只，接触或座位失败后按同一次决定冷却重试',
    '约 1 分钟', [captain(friendliness=90, attachment=60),
    yk('marisa', -2, 1, 'k1', stage='baby', aquatic=True, also=('young',), **pair_ready()),
    yk('marisa', -2, -1, 'k2', stage='baby', aquatic=True, also=('young',), **pair_ready()), boat('capsize_family', 'a', sel_all('young'))],
    note='高友善提高救子概率，不保证每次救援或成功；体积无法托举者不能让亲油无限等待，危及自身生存仍转自保。',
    reads=[('读亲油本次决定与重试', f'yukkuri status {sel("a")} raw'),
           ('读两只落水赤油', f'yukkuri status {sel_all("young")} raw')])
pond('shake', '摇船', '摇船', '有孩子的船长被摇：聚拢孩子并逃离；对比：没有孩子的船长只是普通惊吓（再按一次，孩子数为 0）', '约 30 秒', [
    captain(), yk('marisa', -2, 1, 'k1', stage='baby', aquatic=True, also=('young',), **pair_ready()),
    yk('marisa', -2, -1, 'k2', stage='baby', aquatic=True, also=('young',), **pair_ready()), boat('shake', 'a', sel_all('young'))],
    reads=[('读亲油救援与自救', f'yukkuri status {sel("a")} raw'),
           ('读全部落水幼体', f'yukkuri status {sel_all("young")} raw')])
pond('afloat', '亲子自主出航', '亲子岸边', '亲油和真实赤油从岸边开始，观察照护与出航如何衔接；现场有木棍和苹果，不强行放船或把孩子塞入帽中', '约 1–3 分钟', [
    captain(dx=-5, uuid=uid('afloat', 'a')),
    yk('marisa', -2, 1, 'k1', stage='baby', aquatic=True, uuid=uid('afloat', 'k1'), childhood=CHILDHOOD_CRAWLING_READY,
       kin=f'Mother:{uuid_nbt(uid("afloat", "a"))}', **pair_ready()),
    *shore_supplies()],
    note='赤油已实际完成首餐、首便与爬行，仍不能跳；亲油应从干岸取桨和粮，放帽后让赤油走到岸边，再以辫子实际接上船。读 water.launch 与双方状态，不把等待算作出航成功。',
    reads=[('读出航阶段与阻塞', f'yukkuri status {sel("a")} raw'),
           ('读初始赤油', f'yukkuri status {sel("k1")} raw'),
           ('读实际帽船', f'data get entity {_cargo_boat}')])
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
    f'yukkuri scenario boat sail {sel("father")} {sel("father")} ~{_dx} ~ ~{_dz} ~{_dx + 4} ~-1 ~{_dz}'],
     note='待验证：两艘船同时在水面的行为尚未实测。',
    reads=[('读母油返岸与陪护', f'yukkuri status {sel("mother")} raw'),
           ('读父油返岸与陪护', f'yukkuri status {sel("father")} raw'),
           ('读两艘实船', 'execute as @e[type=mcyukkuri:hat_boat,distance=..48] run data get entity @s')])


_teen_dx, _teen_dz = bank_dx(-4), -4
pond('family_skiffs', '亲子各自帽船', '亲子小船', '亲油、会操帽的小子油与达到独立门槛的亚成各有一船；观察小子油跟随、受惊返亲帽及复航，亚成则真实告别并驶离，亲缘保留', '约 1–3 分钟', [
    captain('mother'), yk('marisa', _dx, _dz, 'kid', stage='child', aquatic=True, yaw=-90, **pair_ready()),
    yk('marisa', _teen_dx, _teen_dz, 'teen', fam=TEEN_FAMILY, aquatic=True, yaw=-90, **pair_ready()),
    f'data modify entity {sel("kid")} YukkuriSubspecies.AquaticLife.FirstVoyage.Completed set value 1b',
    f'data modify entity {sel("teen")} YukkuriSubspecies.AquaticLife.FirstVoyage.Completed set value 1b',
    f'yukkuri scenario kin {sel("mother")} {sel("kid")} false',
    f'yukkuri scenario kin {sel("mother")} {sel("teen")} false',
    boat('afloat', 'mother', sel('mother')),
    f'yukkuri scenario boat afloat {sel("kid")} {sel("kid")} ~{_dx} ~ ~{_dz} ~{_dx + 4} ~-1 ~{_dz}',
    f'yukkuri scenario boat afloat {sel("teen")} {sel("teen")} ~{_teen_dx} ~ ~{_teen_dz} ~{_teen_dx + 4} ~-1 ~{_teen_dz}'],
    note='两位子代初始均学会初航、均未独立。亚成的年龄和营养成长是初态，独立标记只能由实际离船距离与原有能力判定写入。',
    reads=[('读亲油与亲船', f'yukkuri status {sel("mother")} raw'),
           ('读子油与小船', f'yukkuri status {sel("kid")} raw'),
           ('读亚成告别与独立', f'yukkuri status {sel("teen")} raw'),
           ('读三艘实船', 'execute as @e[type=mcyukkuri:hat_boat,distance=..48] run data get entity @s')])


# The ecology scenes combine related transitions; weather/time controls reuse the same live individuals.
pond('water_life', '水上日常·采集与午休', '采集与午休',
    '空粮船从水上开始，岸边只有真实草和成熟作物；观察岸缘采食、缺粮靠岸、携粮复航。夏日午间体力下降后在阴凉处休息，夜晚继续读同一家庭的睡眠与库存',
    '约 2–4 分钟，可切换昼夜', [
        captain(), boat('cargo_empty', 'a', sel('a')),
        'fillbiome ~-12 56 ~-10 ~16 82 ~10 minecraft:jungle',
        'fill ~4 ~3 ~-3 ~9 ~3 ~3 minecraft:oak_planks',
        'fill ~-7 ~ ~-4 ~-3 ~ ~4 minecraft:short_grass replace minecraft:air',
        block(-2, -1, -3, 'minecraft:farmland[moisture=7]'), block(-2, 0, -3, 'minecraft:carrots[age=7]')],
    late=[set_status('a', 'needs.nutrition', 50), set_status('a', 'needs.energy', 72)],
    note='棚顶只有遮阳避雨作用，不会生成食物或保温奖励。清场会还原地形、群系与世界账本。',
    reads=[('读生活、温度与岸上工作', f'yukkuri status {sel("a")} raw'),
           ('读实际库存与腐坏计时', f'data get entity {_cargo_boat} Cargo')])

add('water_winter', 'pond', '水上冬季·暖处与储粮', '冬季避寒',
    '雪地中的真实帽船起初有粮，岸边棚下有篝火；观察寻找暖水面或正常返岸取暖。可下雪、等待结冰，核对睡眠会因冷暖中断、帽船搁浅后走真实脱困',
    '约 1–4 分钟，结冰由世界决定', [
        captain(), boat('sail', 'a', sel('a')),
        'fillbiome ~-12 56 ~-10 ~16 82 ~10 minecraft:snowy_plains',
        *shed(-4, 0, half=2), block(-4, 0, 0, 'minecraft:campfire[lit=true]'),
        item('minecraft:apple', 6, -6, 2)], env={'time': 'night', 'weather': 'rain'},
    vantage=(-6, 6, -90), note='没有锁定天气后果；暖处不可达时读实际拒绝原因，不能以无伤害或不下雪推断通过。',
    reads=[('读温度、暴露与返岸', f'yukkuri status {sel("a")} raw'),
           ('读实船状态与冬粮', f'data get entity {_cargo_boat}')])

pond('water_neighbours', '邻船·照料与接手', '邻船照料',
    '两位相识成体各驾一船，亲帽内的子油有轻伤；先观察邻船招呼和亲子实际接触舔舐。随后可亲手杀死亲油，观察熟人是否愿意接手、真实转移与照料，遗体留在实际位置',
    '照料约 1–2 分钟；接手取决于意愿与可达性', [
        captain('mother', affection=95, social_wait=0),
        yk('marisa', _dx, _dz, 'neighbour', aquatic=True, yaw=-90, friendliness=95, affection=95, social_wait=0, **pair_ready()),
        yk('marisa', -2, 1, 'kid', aquatic=True, stage='child', **pair_ready()),
        f'yukkuri scenario bond {sel("mother")} {sel("neighbour")}',
        f'yukkuri scenario bond {sel("neighbour")} {sel("kid")}',
        boat('afloat', 'mother', sel('kid')),
        f'yukkuri scenario boat sail {sel("neighbour")} {sel("neighbour")} ~{_dx} ~ ~{_dz} ~{_dx + 4} ~-1 ~{_dz}'],
    late=[set_status('kid', 'health.wound_at', 'front:tear:1')],
    give=['minecraft:diamond_sword', 'mcyukkuri:orange_juice 2'],
    note='接手不是自动收养，不能改写亲缘。没有合适照护者或接触路径时保留等待/拒绝读数。',
    reads=[('读亲体社交与照料', f'yukkuri status {sel("mother")} raw'),
           ('读邻船接手', f'yukkuri status {sel("neighbour")} raw'),
           ('读孩子伤势与监护', f'yukkuri status {sel("kid")} raw')])

pond('water_threat', '水上捕食·避险', '水上捕食',
    '真实亲子帽船旁出现饥饿芙兰；观察猎物选择、船长停止睡眠或社交、护子与逃离。飞行追击和咬击仍要实际到达目标',
    '约 1–2 分钟', [captain('mother'),
        yk('marisa', -2, 1, 'kid', aquatic=True, stage='child', **pair_ready()),
        boat('afloat', 'mother', sel('kid'))],
    late=[yk('flandre', -5, 5, 'hunter', nutrition=10, energy=100, yaw=-90)],
    reads=[('读船长避险', f'yukkuri status {sel("mother")} raw'),
           ('读捕食目标', f'yukkuri status {sel("hunter")} raw')])

pond('water_colony', '水边聚落·公仓与迁居', '水边聚落',
    '初始化真实水生家庭、岸边公仓与领袖职位；成员按自然生成规则建立各自帽船后，观察公仓配给、同船家庭粮食只计一次、岸上会议与正常迁居',
    '约 3–6 分钟；迁居由资源与群体决策触发', [
        captain('founder', dx=-5),
        f'yukkuri scenario aquatic_colony {sel("founder")} ~ ~ ~',
        item('minecraft:stick', 8, -3, 0), item('minecraft:apple', 12, -4, -2),
        'fill ~-8 ~ ~-6 ~-2 ~ ~6 minecraft:short_grass replace minecraft:air'],
    note='岸边是公仓与集会地点，水生家庭实际住所是各自帽船。初始资源有限，不直接指定航路、角色行为或迁居结果。',
    reads=[('读领袖、公仓与迁居', f'yukkuri status {sel("founder")} raw'),
           ('读各家庭实际帽船', 'execute as @e[type=mcyukkuri:hat_boat,distance=..48] run data get entity @s')])

pond('water_dock', '宠物返岸·课程与考试', '宠物返岸',
    '玩家拥有的水生魔理沙从帽船上开始；在岸上考官处报名，观察先真实靠岸再参加原有考试。也可用原有照护/训练菜单发起课程，离船不等于已经完成课程',
    '手动报名后约 2–3 分钟', [captain('pet'), *own('pet'), boat('sail', 'pet', sel('pet')),
        block(-5, 0, 0, 'mcyukkuri:exam_podium[facing=east]'),
        block(-9, 0, 0, 'mcyukkuri:exam_hide_box[facing=east]'),
        block(-1, 0, -3, 'minecraft:lectern[facing=west]'),
        human('caretaker_brother', -2, -3, 'judge', 3, yaw=90, label='岸边考官')],
    give=['minecraft:emerald 12', 'minecraft:apple 4', 'mcyukkuri:pet_harness 1', 'minecraft:lead 1'],
    reads=[('读返岸、课程、考试与牵引', f'yukkuri status {sel("pet")} raw')])


def boat_birth(sid, title, hint, outcome, watch, note=''):
    pond(sid, title, hint, watch, '约 1–3 分钟准备与分娩，之后看照护', [
        captain('mother'), yk('marisa', -2, 1, 'father', aquatic=True, yaw=-90, **pair_ready()),
        f'yukkuri scenario bond {sel("mother")} {sel("father")}',
        item('minecraft:stick', 1, 0, 0, name='mother_paddle'),
        item('minecraft:stick', 1, -2, 1, name='father_paddle'),
        item('minecraft:apple', 6, -1, -1, name='birth_food'),
        f'yukkuri scenario birth {sel("mother")} {sel("father")} {outcome}'],
        note=note, give=['minecraft:apple 8'], reads=[('读母油与产程', f'yukkuri status {sel("mother")} raw'),
                          ('读父油与陪护', f'yukkuri status {sel("father")} raw'),
                          ('读两艘接生帽船', 'execute as @e[type=mcyukkuri:hat_boat,distance=..48] run data get entity @s')])


boat_birth('birth_boat_caught', '船上接生·接住', '船上接住', 'caught',
           '双亲在干岸受孕，临产母油走向水面、放下自己的帽船；父油放船陪护，孩子按两船余量接生与转移，之后继续照护',
           note='出生后持苹果右击母船补粮，让母船比父船更重；保留父船余量，观察父船靠近、母油把赤油连续托到父帽。两船同样满或转移不能减小负载差时不应搬运。')
boat_birth('birth_boat_missed', '船上接生·漏接', '船上漏接', 'missed',
           '双亲在干岸受孕后各自准备帽船；本胎预置漏接，新生儿实际落水，观察双亲能否靠近救回。水生与普通魔理沙子代都要救，结局取决于实际距离、体力与余量',
           note='一胎可能不止一只；落水的新生儿不一定都救得及，每只的结局以现场为准。')

# ====================================================================================================== house (anchor: the middle of the room)
def house_pair():
    return [yk('house_marisa', -1, 1, 'a', yaw=180, lead=True, **pair_ready()), yk('house_reimu', 1, 1, 'b', yaw=180, **pair_ready())]


HOUSE_ACTIVITY = 'yukkuri household activity 24000 false true'
add('house_empty', 'house', '空屋·模拟人类活动', '空屋待搬入', '屋里没有家油：按“模拟人类活动”相当于住了一天（落下絮尘和食屑），随后有一只家油搬进来、选墙角、筑巢',
    '搬入几十秒，筑巢数分钟', [say('按观察台第一排右边的“模拟人类活动”，再用“推进”快进。', 'gold')], env=DAY, vantage=None,
    reads=[('读家油现状', 'yukkuri household status')])
add('house_pair', 'house', '一对家油入住', '一对家油', '从夜晚开始，屋内已有真实人类活动留下的絮尘和食屑：家油挑墙角、叼絮铺窝、储食；按“正午”对照白天躲窝休息',
    '数分钟（可倍速）', [*house_pair(), HOUSE_ACTIVITY], env={'time': 'night'}, vantage=None,
    reads=[('读家油现状', 'yukkuri household status')])

HNEST = 'mcyukkuri:house_dust_nest{Materials:6,Food:8,Arrivals:2,Entrance:"south"%s}'
add('house_eggs', 'house', '迁卵', '迁卵', '先建立真实卵生胎次，再让原窝变脏（Dirt 8）；东侧有一个干净新窝，家油逐只叼卵搬过去照看',
    '数分钟（可倍速）', [block(-5, 0, -2, HNEST % ''), block(5, 0, -2, HNEST % ''),
                       yk('house_marisa', -4, -1, 'a', yaw=180, lead=True, **pair_ready()),
                       yk('house_reimu', -5, -1, 'b', yaw=180, **pair_ready()), HOUSE_ACTIVITY,
                       f'yukkuri scenario home {sel("a")} ~-5 ~ ~-2', f'yukkuri scenario home {sel("b")} ~-5 ~ ~-2',
                       f'yukkuri scenario eggs {sel("b")} {sel("a")}'],
    env={'time': 'night'}, vantage=None, reads=[('读家油现状', 'yukkuri household status')],
    late=['execute if data block ~-5 ~ ~-2 EggSlots[{Present:1b}] run data merge block ~-5 ~ ~-2 {Dirt:8}'])

# ====================================================================================================== town
add('wild', 'town', '野良应对', '野良应对', '宠物（银章）在进食；饥饿的野生魔理沙走来讨吃，看宠物是不理会、报告主人，还是没忍住', '约 1–3 分钟', [
    yk('reimu', 0, 0, 'pet', grade=2, nutrition=38, yaw=-90, lead=True, boldness=60), *own('pet'), item('minecraft:apple', 2, 1, 0),
    yk('marisa', 6, 0, 'wild', nutrition=34, yaw=90, boldness=45)], vantage=(0, 12))

add('badge', 'town', '徽章与旁人', '徽章旁人', '场景给这只宠物灵梦签发的银章，只能由它佩戴；把它别上后，旁边的野生魔理沙会羡慕或嫉妒', '别上后约 1 分钟', [
    yk('reimu', 0, 0, 'pet', yaw=-90, lead=True), *own('pet'), yk('marisa', 6, 0, 'wild', yaw=90),
    f'yukkuri scenario badge {sel("pet")} silver true'], vantage=(0, 12))

add('exam', 'town', '考场', '考场', '带宠物灵梦去考官面前考试：紧张、作答、躲猫猫，合格得铜章', '约 1–2 分钟', [
    block(0, 0, 0, 'mcyukkuri:exam_podium[facing=east]'), block(-4, 0, 0, 'mcyukkuri:exam_hide_box[facing=east]'), block(4, 0, 0, 'minecraft:lectern[facing=west]'),
    human('caretaker_brother', 5, 0, 'judge', 3, yaw=90, label='考官'), yk('reimu', 2, 2, 'pet', yaw=180, lead=True), *own('pet')], vantage=(0, 12))

add('date', 'town', '相亲', '相亲', '北侧店方饲养员牵线：有顶窝、真实银章的玩家灵梦，与店方真实登记的银章种油魔理沙害羞、靠近、蹭蹭', '等 2 秒登记窝位后，右键北侧饲养员；约 1 分钟', [
    human('caretaker_brother', 4, -4, 'keeper', 4, yaw=180, label='店方饲养员'), *shed(-4, -3),
    # Five is the largest ordinary live litter; two adjacent spare nests cover extraCells(5) == 2 while the centre remains the mother's home.
    block(-5, 0, -3, 'mcyukkuri:yukkuri_nest'), block(-3, 0, -3, 'mcyukkuri:yukkuri_nest'),
    yk('reimu', 0, 0, 'bride', yaw=-90, lead=True), *own('bride'), yk('marisa', 8, 0, 'stud', yaw=90),
    f'yukkuri scenario badge {sel("bride")} silver false', f'yukkuri scenario stud {sel("keeper")} {sel("stud")}', home('bride', -4, -3)],
    give=['minecraft:emerald 6'], vantage=(0, 12))

add('pred_pets', 'town', '捕食种宠物', '捕食种宠物', '两只捕食者都饿了：受过教育的银章蕾米莉亚面对受保护宠物忍住；未学等待的铜章芙兰不听令。可用徽章菜单下达停止／允许捕猎，观察资格差别', '约 30 秒；指令可反复操作', [
    yk('remilia', 0, 0, 'rem', grade=2, yaw=-90, lead=True, nutrition=100), *own('rem'),
    f'yukkuri scenario badge {sel("rem")} silver false',
    yk('reimu', 5, 0, 'prey', grade=1, yaw=90), *own('prey'),
    f'yukkuri scenario badge {sel("prey")} bronze false',
    yk('flandre', 10, 0, 'fla', grade=1, yaw=90, nutrition=100), *own('fla'),
    f'yukkuri scenario badge {sel("fla")} bronze false',
    f'data modify entity {sel("fla")} YukkuriPet.KnownSkills set value 0',
    set_status('rem', 'needs.nutrition', 25), set_status('fla', 'needs.nutrition', 25),
    box(8, 0, -2, 12, 2, -2, 'minecraft:glass_pane'), box(8, 0, 2, 12, 2, 2, 'minecraft:glass_pane'),
    box(8, 0, -1, 8, 2, 1, 'minecraft:glass_pane'), box(12, 0, -1, 12, 2, 1, 'minecraft:glass_pane')],
    give=['mcyukkuri:field_notebook 1'], vantage=(0, 12),
    reads=[('读受训捕食者', f'yukkuri status {sel("rem")} raw'), ('读未训捕食者', f'yukkuri status {sel("fla")} raw')])

add('transport', 'town', '运输与动手', '运输动手', '喷弹珠汽水喷雾让它睡着，再装篮、放出：仍是同一只；围栏里是野生个体', '随意', [
    yk('reimu', 0, 0, 'pet', grade=1, yaw=-90, lead=True), *own('pet'),
    box(6, 0, -3, 10, 1, -3, 'minecraft:oak_fence'), box(6, 0, 1, 10, 1, 1, 'minecraft:oak_fence'),
    box(6, 0, -2, 6, 1, 0, 'minecraft:oak_fence'), box(10, 0, -2, 10, 1, 0, 'minecraft:oak_fence'),
    yk('reimu', 8, -1, 'wild', yaw=90, label='野生灵梦')],
    give=['mcyukkuri:sleep_spray 4', 'mcyukkuri:yukkuri_carrier 2'], vantage=(0, 12))

add('walker', 'town', '牵宠散步', '牵宠散步', '爱派居民（哥哥和姐姐）牵着拴了绳的宠物灵梦在街上散步', '持续', [
    'yukkuri human walker ~ ~ ~ brother', 'yukkuri human walker ~6 ~ ~ sister', LEAD_ANY], vantage=(0, 12))

add('harness_walk', 'town', '亲手牵引与照料', '亲手牵引',
    '先用胴带右键宠物，再用拴绳牵引；戴上后短暂回应即恢复活动。走动、停下喂食，再解绳观察它恢复日常', '随意', [
        yk('reimu', 0, 0, 'harness_pet', grade=1, lead=True, nutrition=60, energy=100, hygiene=100), *own('harness_pet')],
    give=['mcyukkuri:pet_harness 1', 'minecraft:lead 1', 'minecraft:apple 6'],
    reads=[('读牵引宠物', f'yukkuri status {sel("harness_pet")} raw')], vantage=(0, 8))

add('feed_wild', 'town', '喂野生油', '喂野生油', '爱派居民牵着宠物散步，遇到饿坏的野生魔理沙：会拿出食物喂它', '约 1–3 分钟', [
    'yukkuri human walker ~ ~ ~ brother', yk('marisa', 6, 3, 'wild', nutrition=20, yaw=90, lead=True)], vantage=(0, 12))

add('oniisan', 'town', '鬼威惨', '鬼威惨', '街上只有一名鬼威惨，观察其日常移动；需要猎物与旁观反应时选择“鬼威惨·有猎物”', '持续', [
    'yukkuri human oniisan ~ ~ ~'], vantage=(0, 12))

_oniisan_victim = uid('oniisan_prey', 'victim')
add('oniisan_prey', 'town', '鬼威惨·有猎物', '鬼威惨有猎物', '鬼威惨东侧有野生灵梦，她的孩子与陌生魔理沙都在旁边；看见威胁会逃，附近目击伤害也会受惊', '持续', [
    'yukkuri human oniisan ~ ~ ~',
    yk('reimu', 5, 0, 'oniisan_victim', uuid=_oniisan_victim, label='被盯上的灵梦', lead=True, **pair_ready()),
    yk('reimu', 9, 1, 'oniisan_child', stage='child', label='她的孩子', kin=f'Mother:{uuid_nbt(_oniisan_victim)}', **pair_ready()),
    yk('marisa', 8, -4, 'oniisan_witness', label='陌生旁观者', **pair_ready())], vantage=(0, 12),
    reads=[('读猎物反应', f'yukkuri status {sel("oniisan_victim")} raw'), ('读旁观反应', f'yukkuri status {sel("oniisan_witness")} raw')])

add('attack_witness', 'town', '目击玩家攻击', '目击攻击',
    '徒手打一下中间的灵梦：右侧陌生油能看见现场，会受惊逃开；左侧被实墙遮住的陌生油不会隔墙感知。个体仍会自由移动', '靠近后直接操作', [
        yk('reimu', 0, 0, 'attack_victim', label='被攻击者', lead=True, **pair_ready()),
        yk('marisa', 4, 0, 'attack_witness', label='看得见的旁观者', **pair_ready()),
        yk('chen', -4, 0, 'attack_hidden', label='墙后的旁观者', **pair_ready()),
        box(-2, 0, -3, -2, 2, 3, 'minecraft:stone')], env=DAY, vantage=(0, 8),
    reads=[('读可见旁观者', f'yukkuri status {sel("attack_witness")} raw'), ('读墙后旁观者', f'yukkuri status {sel("attack_hidden")} raw')])

# ---- shop and factories: the whole building is placed by `human build`, which levels the lot and fills the shop itself
add('shop_build', 'shop', '整座商店', '整座商店', '商店整体放下：展舱有银章与金章个体，后场有无章库存；右键店主开店。读档案确认受训库存六项技能、徽章编号和有效期，购买放出后保持',
    '建筑几秒', ['yukkuri human build shop ~8 ~ ~17 south'], give=['minecraft:emerald 128', 'mcyukkuri:badge_handbook'], vantage=(0, 30, 180),
    reads=[('店铺登记（shop list）', 'yukkuri human shop list'),
           ('读店内个体档案', 'yukkuri status @e[type=#mcylab:yukkuri,distance=..80] raw'),
           ('读徽章与技能存档', 'execute as @e[type=#mcylab:yukkuri,distance=..80] run data get entity @s YukkuriPet')])
for _kind, _caption, _pad in (('labor', '劳动工厂', -44), ('manju', '馒头工厂', 0), ('bag', '袋子工厂', 44)):
    add(f'factory_{_kind}', 'factory', _caption, _caption, f'{_caption}整体放下：监工、流水线工位和流水线上的个体', '建筑几秒',
        [f'yukkuri human build {_kind} ~{_pad} ~ ~17 south'], vantage=(_pad, 40, 180), spot=None)

# ====================================================================================================== clips (buttons are built in build_museum_full.py)


# Adult state demonstrations only set saved starting state; the normal server AI owns the rest.
def agitation_start(phase):
    return f'YukkuriAgitation:{{Phase:"{phase}",PhaseTicks:0,Cooldown:0,NextCheck:2400}}'


add('agitation_relief', 'meadow', '成体发情期与日常活动', '成体发情期',
    '爱丽丝处于持续发情期：空闲观察时偶尔说想素鸡立；喂食、避险会暂停表现但保留剩余期，期满未配对改播heat_unmet未解台词。给对照个体喂催情剂可现场听heat_start开始台词', '约一分钟', [
        yk('alice', 0, 0, 'restless', lead=True, kin='SexDrive:1200', extra=agitation_start('HEAT'), **pair_ready()),
        yk('alice', 6, 0, 'ordinary', extra=agitation_start('IDLE'), **pair_ready()),
        *own('restless'), *own('ordinary')], env=DAY,
    give=['mcyukkuri:stimulant 2'],
    reads=[('读成体状态', f'yukkuri status {sel("restless")}')])

_pair_mother, _pair_father = uid('adult_pairing', 'mother'), uid('adult_pairing', 'father')


def pairing_permission(partner):
    return agitation_start('IDLE') + ',YukkuriPet:{Permits:[{Partner:' + uuid_nbt(partner) + ',Until:24000000L}]}'


add('adult_pairing', 'meadow', '成体配对与双方反应', '成体配对',
    '两只健康成体互相接纳，近距互见后同步回应1.4秒、亲近1.6秒再尝试受孕；双方均在发情期内时受孕改播三段素鸡立完成台词与弹动，未在期内才播普通期待反应。喷雾、带走或遮挡会重置配对', '约半分钟至数分钟', [
        *shed(5, 0), *shed(5, 6),
        block(4, 0, 0, 'mcyukkuri:yukkuri_nest'), block(6, 0, 0, 'mcyukkuri:yukkuri_nest'),
        block(4, 0, 6, 'mcyukkuri:yukkuri_nest'), block(6, 0, 6, 'mcyukkuri:yukkuri_nest'),
        yk('reimu', 0, 1, 'mother', lead=True, uuid=_pair_mother, kin='SexDrive:1200', extra=pairing_permission(_pair_father), **pair_ready()),
        yk('marisa', 0, 3, 'father', uuid=_pair_father, kin='SexDrive:1200', extra=pairing_permission(_pair_mother), **pair_ready()),
        *own('mother'), *own('father'),
        f'yukkuri scenario bond {sel("mother")} {sel("father")}',
        f'yukkuri scenario mate {sel("mother")} {sel("father")}',
        home('mother', 5, 0), home('father', 5, 6)], env=DAY,
    give=['mcyukkuri:sleep_spray 2', 'mcyukkuri:yukkuri_carrier 1'],
    reads=[('读繁殖状态', f'yukkuri status {sel("mother")}')])

_widow, _new_partner = (uid('widow_repair', slot) for slot in ('widow', 'new_partner'))
add('widow_repair', 'meadow', '丧偶后自然再配对', '丧偶再配对',
    '原伴侣在场时不接受候选；先用收纳篮带走原伴侣，仍不能再配对。放回后输入 /kill @e[tag=scn_old_partner,limit=1]，死亡账本确认后才允许与候选自然求偶、受孕',
    '先观察约半分钟，再分步操作；确认死亡后约半分钟至数分钟', [
        *shed(5, 0), *shed(5, 6),
        block(4, 0, 0, 'mcyukkuri:yukkuri_nest'), block(6, 0, 0, 'mcyukkuri:yukkuri_nest'),
        block(4, 0, 6, 'mcyukkuri:yukkuri_nest'), block(6, 0, 6, 'mcyukkuri:yukkuri_nest'),
        yk('reimu', 0, 1, 'widow', lead=True, uuid=_widow, label='待再配对者',
           kin='SexDrive:1200', extra=pairing_permission(_new_partner), **pair_ready()),
        # A fresh UUID allows restarting this scene after the ledger has recorded the previous original partner's death.
        yk('marisa', 10, 1, 'old_partner', label='原伴侣',
           kin=f'Partner:{uuid_nbt(_widow)}', extra=agitation_start('IDLE'), **pair_ready()),
        yk('marisa', 0, 3, 'new_partner', uuid=_new_partner, label='新候选',
           kin='SexDrive:1200', extra=pairing_permission(_widow), **pair_ready()),
        f'data modify entity {sel("widow")} YukkuriFamily.Partner set from entity {sel("old_partner")} UUID',
        *own('widow'), *own('old_partner'), *own('new_partner'),
        f'yukkuri scenario bond {sel("widow")} {sel("new_partner")}',
        home('widow', 5, 0), home('new_partner', 5, 6)], env=DAY,
    give=['mcyukkuri:yukkuri_carrier 1', 'minecraft:apple 16'],
    note='不调用 mate、不预设胎次。原 Partner 在确认死亡后仍保留，直到正常受孕写入新伴侣；REMOVED 对照操作和存档重载步骤见试玩场说明。',
    reads=[('读再配对状态', f'yukkuri status {sel("widow")}'), ('读候选状态', f'yukkuri status {sel("new_partner")}')])

add('alice_hunt', 'meadow', '攻击素鸡立 · 完整流程', '攻击素鸡立',
    '爱丽丝从搜寻宣言开始，锁定目标后追击；目标发现她会喊出警报台词并逃跑。接触后双方贴身进入素鸡立：攻方前倾反复摩擦并伸出器官，受方挣扎喊叫；末尾三段完成台词、可能结算茎生胎次，随后可能立刻寻找下一个目标', '约半分钟，可重复观察', [
        yk('alice', 0, 0, 'hunter', lead=True, extra=agitation_start('ALICE_WATCH'), **pair_ready()),
        yk('reimu', 4, 0, 'prey', extra=agitation_start('IDLE'), **pair_ready()),
        yk('marisa', 9, 3, 'witness', extra=agitation_start('IDLE'), **pair_ready()),
        *own('hunter'), *own('prey'), *own('witness')], env=DAY,
    give=['mcyukkuri:sleep_spray 2', 'mcyukkuri:yukkuri_carrier 1'],
    reads=[('读攻击魔状态', f'yukkuri status {sel("hunter")}')])

add('alice_interrupt', 'meadow', '攻击素鸡立 · 中断与怒火', '素鸡立中断',
    '同上预置接近与接触；玩家可在动作中用喷雾或攻击打断：攻方会喊出发情中断怒火台词并退入恢复，受方脱身后说出得救台词', '约半分钟', [
        yk('alice', 0, 0, 'hunter', lead=True, extra=agitation_start('ALICE_WATCH'), **pair_ready()),
        yk('reimu', 4, 0, 'prey', extra=agitation_start('IDLE'), **pair_ready()),
        *own('hunter'), *own('prey')], env=DAY,
    give=['mcyukkuri:sleep_spray 2'],
    note='中断路径与自然完成共用同一入口；被打断的一轮不会结算胎次，受方只受惊吓与体力损耗。',
    reads=[('读中断结果', f'yukkuri status {sel("hunter")}')])

for _sid, _title, _nutrition, _energy in (
        ('aftermath_growth', '事后茎生·生长', 75, 60),
        ('aftermath_burden', '事后茎生·营养消耗', 45, 60),
        ('aftermath_depletion', '事后茎生·耗竭末期', 0, 10)):
    _depleted = _sid == 'aftermath_depletion'
    _growth = _sid == 'aftermath_growth'
    add(_sid, 'meadow', _title, '茎生生长' if _growth else ('耗竭末期' if _depleted else '茎生消耗'),
        ('从营养已耗尽、健康偏低开始，观察濒死耗馅与死亡，死亡后释放未出生胎次预留' if _depleted else
         '设置事后真实茎生胎次：从无茎开始萌生，首次萌茎会触发受袭者aftermath_realize困惑台词；茎持续汲取母体营养，营养下降后低垂、停育，负担仍继续'),
        '数分钟，可倍速；生长与存活由营养、体力和健康决定', [
            box(-6, -1, -5, 6, -1, 7, 'minecraft:oak_planks'),
            box(-6, 0, -5, 6, 0, -5, 'minecraft:oak_fence'), box(-6, 0, 7, 6, 0, 7, 'minecraft:oak_fence'),
            box(-6, 0, -4, -6, 0, 6, 'minecraft:oak_fence'), box(6, 0, -4, 6, 0, 6, 'minecraft:oak_fence'),
            *shed(3, 2), block(2, 0, 2, 'mcyukkuri:yukkuri_nest'), block(4, 0, 2, 'mcyukkuri:yukkuri_nest'),
            yk('reimu', 0, 0, 'affected', lead=True, nutrition=_nutrition, energy=_energy,
               extra=agitation_start('IDLE') + (',Health:2f' if _depleted else '')),
            yk('alice', 10, 0, 'source', extra=agitation_start('IDLE'), **pair_ready()),
            *own('affected'), *own('source'), home('affected', 3, 2),
            *([item('minecraft:apple', 16, 0, 3)] if _growth else [])],
        env=DAY, give=['minecraft:apple 16'],
        note='通过场景入口建立事后胎次；来源仅作血缘登记。生长组观察发育，消耗组观察茎生负担逐步耗尽营养，末期组观察已有耗竭的结算。',
        late=[f'yukkuri scenario aftermath {sel("affected")} {sel("source")}'],
        reads=[('读事后负担', f'yukkuri status {sel("affected")}')])

# ====================================================================================================== new since the last round (not yet played)
NEW = '待验证：新场景，尚未在游戏里实测。'
REMAINS_NEAR = '@e[type=mcyukkuri:yukkuri_remains,distance=..48,limit=1,sort=nearest]'
HARSH = 15                     # Low friendliness; fear and recognition remain separate.


def kin_of(**parents):
    return ','.join(f'{role}:{uuid_nbt(u)}' for role, u in parents.items())


# ---- injuries and remains (open ground: nothing to eat, nothing to hide behind)
add('airborne', 'body', '腾空与小跳', '腾空与小跳',
    '两只灵梦落地站稳后，高抛个体腾空，小跳个体轻跳；观察高抛时的飞起感叹，以及同一次腾空不会重复刷屏。落地后仍按普通规则活动',
    '生成后约 2–6 秒', [
        box(-6, -1, -2, 6, -1, 2, 'minecraft:hay_block'),
        yk('reimu', -3, 0, 'high', label='高抛', lead=True, **pair_ready()),
        yk('reimu', 3, 0, 'low', label='小跳', **pair_ready())],
    late=[f'data modify entity {sel("high")} Motion set value [0.0d,0.9d,0.0d]',
          f'data modify entity {sel("low")} Motion set value [0.0d,0.25d,0.0d]'],
    env=DAY, vantage=(0, 10), note=NEW)

add('house_health', 'body', '家油血量', '家油血量',
    '三只家油分别处于赤油、子油和成体阶段，旁边是普通成体灵梦。先读各自血量，再空手攻击家油，观察幼体的脆弱程度；家油受击不显示伤口，仍会真实扣血、死亡并留下遗体',
    '读数后自行操作', [
        yk('house_reimu', -6, 0, 'house_baby', stage='baby', label='家油赤油', **pair_ready()),
        yk('house_reimu', -2, 0, 'house_child', stage='child', label='家油子油', **pair_ready()),
        yk('house_reimu', 2, 0, 'house_adult', label='家油成体', lead=True, **pair_ready()),
        yk('reimu', 6, 0, 'ordinary', label='普通成体', **pair_ready())],
    env=DAY, vantage=(0, 10), note=NEW,
    reads=[(title, f'yukkuri status {sel(name)}') for title, name in (
        ('读家油赤油', 'house_baby'), ('读家油子油', 'house_child'), ('读家油成体', 'house_adult'), ('读普通成体', 'ordinary'))])

_parts = (('头顶', 0, 2, 0), ('正面', 0, 0, 3), ('背面', 0, 0, -3), ('侧面', 3, 0, 0), ('底面', 0, -1.2, 0))
add('wound_parts', 'body', '受击部位', '伤口部位',
    '五只面朝南的灵梦各从一个方向挨一记仙人掌刺：伤口落在头顶、正面、背面、侧面、底面；读数 health.wound_sites 是“部位:种类+级”',
    '立即；惊慌约 6 秒', [
        *[yk('reimu', -8 + 4 * i, 0, f'v{i}', yaw=0, lead=i == 1, label=part, **pair_ready()) for i, (part, *_) in enumerate(_parts)],
        *[f'damage {sel(f"v{i}")} 5 minecraft:cactus at ~{-7.5 + 4 * i + ox:g} ~{.4 + oy:g} ~{.5 + oz:g}' for i, (_, ox, oy, oz) in enumerate(_parts)]],
    note=NEW, vantage=(0, 10, 180), reads=[('读正面伤口', f'yukkuri status {sel("v1")}')])

add('wound_leak', 'body', '漏馅与止漏对照', '漏馅止漏',
    '四只灵梦有 1／2／3 级及两处 3 级裂口；每 10 秒各漏血 0／0.5／1／1。橙汁直接关闭全部裂口、补满血并停止漏血；面粉糊只去疤，对这些无疤伤者无效',
    '先等 10 秒读血量，再用橙汁；随后再等 10 秒确认止漏', [
        *[yk('reimu', -6 + 4 * i, 0, f'v{i}', yaw=180, lead=i == 1, label=label, nutrition=20, energy=95)
          for i, label in enumerate(('轻伤', '二级裂口', '三级裂口', '多处裂口'))],
        *[set_status(f'v{i}', 'marks.scars', 0) for i in range(4)],
        *[set_status(f'v{i}', 'health.wound_at', f'front:tear:{min(3, i + 1)}') for i in range(4)],
        set_status('v3', 'health.wound_at', 'back:tear:3')],
    give=['mcyukkuri:flour_paste 4', 'mcyukkuri:orange_juice 4'], note=NEW, vantage=(0, 10),
    reads=[('读二级裂口', f'yukkuri status {sel("v1")}'), ('读三级裂口', f'yukkuri status {sel("v2")}'),
           ('读多处裂口', f'yukkuri status {sel("v3")}')])

add('treatment_severe', 'body', '橙汁治伤与烤足', '橙汁治伤',
    '四类三级伤、旧包扎裂伤、烤足共六只：自然等 40 秒三级伤仍在，旧包扎仍漏血。橙汁一剂清伤补满血，烤足也恢复；满血有伤照样能治。面粉糊不能治这些无疤伤口',
    '先读初值；可推进 800 tick，再逐只用橙汁核对', [
        *[yk('reimu', -10 + 4 * i, 0, f'v{i}', label=label, lead=i == 0, also=(f'treatment_pair{i // 2}',), **pair_ready(),
             extra='YukkuriBody:{Bandage:1200}' if i == 4 else '')
          for i, label in enumerate(('裂伤3·低血', '凹伤3·满血', '擦伤3·低血', '烧伤3·满血', '旧包扎', '烤足'))],
        *[set_status(f'v{i}', 'marks.scars', 0) for i in range(6)],
        *[set_status(f'v{i}', 'health.wound_at', f'side:{kind}:3')
          for i, kind in enumerate(('tear', 'dent', 'scratch', 'scorch', 'tear'))],
        set_status('v0', 'health.health', 6), set_status('v2', 'health.health', 6),
        set_status('v5', 'health.wound_at', 'bottom:scorch:3')],
    env=DAY, give=['mcyukkuri:flour_paste 6', 'mcyukkuri:orange_juice 8'],
    vantage=(0, 12), reads=[(f'读{label}', f'execute as {sel_all(f"treatment_pair{i}")} run yukkuri status @s raw')
                           for i, label in enumerate(('裂伤与凹伤', '擦伤与烧伤', '旧包扎与烤足'))]
                          + [('读旧包扎值', f'data get entity {sel("v4")} YukkuriBody.Bandage')])

add('treatment_scars', 'body', '去疤与治伤分工', '去疤治伤',
    '前两只都有疤 1、三级凹伤、血 6：左先面粉糊，只去疤不改伤血；右先橙汁，清伤补满血但留疤，再交换用药。第三只二级裂伤自然闭合后可能留疤，可用面粉糊去掉；第四只健康无疤，两药都无效',
    '先读数再用药；自然愈合约 40 秒。核对扣料请切生存，创造模式不耗物品', [
        yk('reimu', -6, 0, 'paste_first', label='先去疤', lead=True, nutrition=20, energy=100),
        yk('reimu', -2, 0, 'orange_first', label='先治伤', nutrition=20, energy=100),
        *[command for name in ('paste_first', 'orange_first') for command in (
            set_status(name, 'marks.scars', 0), set_status(name, 'marks.scars', 1), set_status(name, 'health.wound_at', 'side:dent:3'),
            set_status(name, 'health.health', 6))],
        yk('reimu', 2, 0, 'healing', label='伤后留疤', **pair_ready()),
        set_status('healing', 'marks.scars', 0), set_status('healing', 'health.wound_at', 'front:tear:2'),
        yk('reimu', 6, 0, 'healthy', label='无伤无疤', **pair_ready()),
        set_status('healthy', 'marks.scars', 0)],
    env=DAY, give=['mcyukkuri:flour_paste 8', 'mcyukkuri:orange_juice 8'], vantage=(0, 10),
    reads=[(caption, f'yukkuri status {sel(name)} raw') for caption, name in (
        ('读先去疤', 'paste_first'), ('读先治伤', 'orange_first'), ('读伤后留疤', 'healing'), ('读无效对照', 'healthy'))])

add('treatment_care', 'body', 'NPC治疗用料', 'NPC治疗',
    '伤者有三级裂口、血 6、感染 4、疤 1。近身连续照料应按橙汁→草药→面粉糊处理，余量 8→7→6→5；每实际成功一剂减 1，恢复后再等两轮不再扣。走散久候时感染可能先自然消退',
    '约 10–40 秒', [
        yk('reimu', 0, 0, 'patient', label='伤者', lead=True, **pair_ready()),
        set_status('patient', 'health.wound_at', 'front:tear:3'),
        set_status('patient', 'health.health', 6), set_status('patient', 'health.infection', 4),
        set_status('patient', 'marks.scars', 0), set_status('patient', 'marks.scars', 1),
        human('caretaker_brother', 2, 0, 'carer', 0, label='照料员')],
    env=DAY, vantage=(0, 10), reads=[('读伤者', f'yukkuri status {sel("patient")} raw'),
                                    ('读医疗余量', f'data get entity {sel("carer")} Medicine')])

add('core_exposed', 'body', '中枢外露与濒死', '中枢外露',
    '左：正面深凹伤、剩 3 血，露出暗红中枢；右：同样伤势的魔理沙 2 秒后挨 2 点三叉戟，剩 1 血进入 4 秒濒死，随后遗体保留 0.6 份食用量',
    '立即；濒死 80 tick', [
        yk('reimu', -3, 0, 'exposed', lead=True, **pair_ready()),
        set_status('exposed', 'health.wound_at', 'front:dent:3'), set_status('exposed', 'health.health', 3),
        yk('marisa', 3, 0, 'struck', **pair_ready()),
        set_status('struck', 'health.wound_at', 'front:tear:3'), set_status('struck', 'health.health', 3)],
    late=[f'damage {sel("struck")} 2 minecraft:trident at ~3.5 ~0.4 ~3.5'],
    note=NEW, vantage=(0, 10), reads=[('读中枢外露', f'yukkuri status {sel("exposed")}')])

add('core_filling', 'body', '中枢馅', '中枢馅',
    '一只灵梦身体耗尽（核心完好）留下空壳，2 秒后割出独立中枢馅；旁边重伤又饿的魔理沙吃下它：血 +4、营养 +10、不安下降。普通食用量为零仍可取一次核心',
    '约 10–30 秒', [
        yk('reimu', 0, 0, 'dead', **pair_ready()),
        yk('marisa', 5, 0, 'hurt', lead=True, nutrition=30, energy=95),
        set_status('hurt', 'health.wound_at', 'side:dent:3'), set_status('hurt', 'health.health', 4),
        f'damage {sel("dead")} 100 minecraft:generic'],
    late=[f'yukkuri body core {REMAINS_NEAR}'], note=NEW, vantage=(0, 10), reads=[('读受伤者', f'yukkuri status {sel("hurt")}')])

_rot = [uid('remains_rot', k) for k in ('fresh', 'stale', 'rotten')]
add('remains_rot', 'body', '遗体腐败与死臭', '遗体腐败',
    '三具灵梦遗体：新鲜／陈旧（偏平、霉斑）／腐坏（塌陷、冒烟），臭味半径 5／9／14 格；旁边吃饱的油闻到会说“臭”并走开、不安上涨。放着不管约 20 分钟变陈、60 分钟腐坏',
    '2–5 秒后走开', [
        remains(-16, 0, 'fresh', ident=_rot[0]), remains(0, 0, 'stale', ident=_rot[1], rot=24000), remains(16, 0, 'rotten', ident=_rot[2], rot=72000),
        yk('reimu', -14, 0, 'o1', lead=True, **pair_ready()), yk('marisa', 2, 0, 'o2', **pair_ready()), yk('chen', 18, 0, 'o3', **pair_ready())],
    note=NEW, vantage=(0, 14), reads=[('读闻臭者', f'yukkuri status {sel("o2")}')])

_carrion = uid('carrion', 'dead')
add('carrion', 'body', '饿极食尸', '饿极食尸',
    '一具剩 1.5 份的新鲜遗体：饿灵梦先吃 1 份再吃半份，吞咽时才得营养，吃空留壳；吃饱的魔理沙闻臭走开；死者伴侣宁可挨饿也不吃。搬起放下不回满',
    '约 5 秒后走去，吃 1–2 口约 10 秒', [
        remains(0, 0, 'body', ident=_carrion, food=1.5),
        yk('reimu', 5, 0, 'starving', nutrition=2, energy=95, lead=True),
        yk('marisa', -5, 3, 'fed', **pair_ready()),
        yk('chen', 5, -4, 'mate', nutrition=2, energy=95, kin=f'Partner:{uuid_nbt(_carrion)}')],
    note=NEW, vantage=(0, 10), reads=[('读食尸者', f'yukkuri status {sel("starving")}')])

add('cannibal_hunt', 'body', '捕食失饰者', '捕食失饰者',
    '营养 9 的饿灵梦追咬无头饰成年魔理沙：每次实际损失 2 血对应 1.2 份，吞下才加 6 营养；咬空、免伤期和濒死者不产食物，打断咀嚼只吐出不可拾取的痕迹',
    '约 10–60 秒', [
        yk('reimu', 0, 0, 'hunter', nutrition=9, energy=95, lead=True),
        yk('marisa', 4, 0, 'prey', accessory=False, **pair_ready()),
        yk('chen', -4, 2, 'hatted', **pair_ready())],
    note=NEW, vantage=(0, 10), reads=[('读捕食者', f'yukkuri status {sel("hunter")}')])

_lw = {k: uid('last_words', k) for k in ('dying', 'mate', 'kid')}
add('last_words', 'body', '临终遗言', '临终遗言',
    '约 6 秒后灵梦中枢受损、濒死 4 秒：先说临终台词，再对伴侣和孩子说遗言；亲属含泪围拢；死后孩子得到“家人去世”（不安 +30）',
    '约 10 秒（6 秒后触发，等亲缘登记）', [
        yk('reimu', 0, 0, 'dying', uuid=_lw['dying'], lead=True, kin=kin_of(Partner=_lw['mate']), **pair_ready()),
        yk('marisa', 3, 1, 'mate', uuid=_lw['mate'], kin=kin_of(Partner=_lw['dying']), **pair_ready()),
        yk('reimu', -3, 1, 'kid', stage='child', uuid=_lw['kid'], nutrition=95, kin=kin_of(Mother=_lw['dying'], Father=_lw['mate']))],
    late=[Retry(set_status('dying', 'health.core', 'damage'), sel('dying'), tries=1, first=80)],
    note=NEW, vantage=(0, 10), reads=[('读孩子', f'yukkuri status {sel("kid")}')])

_of = {k: uid('offering', k) for k in ('parent', 'child')}
add('offering', 'body', '请吃了我', '请吃了我',
    '空地上没有食物，饿坏的子油和同样饥饿的亲油：亲油按性格、好感和双方饥饿抽签（这里约五成）决定是否献身：走近→恳求→坚持→献出自己；孩子拒绝、哭着吃。中途喂孩子（营养 ≥25）可中止',
    '约 15–20 秒首次抽签，没抽中约 30 秒后复判；抽中后约 15 秒', [
        yk('reimu', 0, 0, 'parent', uuid=_of['parent'], lead=True, nutrition=12, energy=95),
        yk('reimu', 3, 1, 'child', stage='child', uuid=_of['child'], nutrition=5, energy=95, kin=kin_of(Mother=_of['parent']))],
    give=['minecraft:apple 4'], note=NEW + '首轮未中时可观察至约50秒或重开；重开后可写亲油的 home.offering_state=offer 跳过抽签，或在子油仍饥饿时写 health.core=damage，验证同一献身链的临终献体。', vantage=(0, 10),
    reads=[('读献身状态', f'yukkuri status {sel("parent")} raw'),
           ('读受赠子油', f'yukkuri status {sel("child")} raw')])

# ---- predators: a parent drawing the chase, Youmu standing her ground
def protect_child(sid, title, hint, friendliness, watch):
    parent, child = uid(sid, 'parent'), uid(sid, 'child')
    add(sid, 'weather', title, hint, watch,
        '约 30 秒内；概率行为可重新生成对比', [
            yk('reimu', 0, 0, 'parent', uuid=parent, yaw=-90, lead=True, label='亲油',
               friendliness=friendliness, boldness=60, attachment=60, **pair_ready()),
            yk('reimu', 3, 1, 'child', stage='child', uuid=child, yaw=-90, label='子油', nutrition=95, kin=kin_of(Mother=parent)),
            yk('flandre', 12, 0, 'pred', yaw=90, label='捕食者', nutrition=100, energy=100), set_status('pred', 'needs.nutrition', 10)],
        env=DAY, spot='pred', vantage=(6, 14), note=NEW,
        reads=[('读亲油反应', f'yukkuri status {sel("parent")} raw'), ('读护子追击', f'yukkuri status {sel("pred")} raw'),
               ('读被护子油', f'yukkuri status {sel("child")} raw')])


protect_child('sacrifice', '挡捕食种', '挡捕食种', 90,
    '芙兰先选小灵梦，亲油高友善较愿意引开；转向很快，读亲油 behavior.protection 的 danger 与 decision=diverted，再对照捕食目标和孩子血量。走近挡护的演出待补')
protect_child('sacrifice_reluctant', '挡捕食种·低意愿', '护子低意愿', 10,
    '与高意愿组只差友善：仍有机会引开追击。读 behavior.protection 的 chance、decision，再对照捕食目标；不能以一次仍追亲油判失败')

MYON = 'YukkuriMyon:{SpeechQuirk:"%s",Kanroken:%s,Initialized:1b}'
add('myon_brave', 'weather', '妖梦拔刀', '妖梦拔刀',
    '持棍妖梦接触且有视线才打：1.5 伤、间隔 1.6 秒。看 behavior.myon_combat、双方血量与捕食种反击；妖梦应略弱，低血量退避；旁边灵梦照常逃',
    '约 30 秒', [
        yk('myon', 0, 0, 'myon', yaw=-90, lead=True, extra=MYON % ('miyo', '1b'), **pair_ready()),
        yk('reimu', 0, 3, 'a', yaw=-90, **pair_ready()),
        yk('flandre', 12, 0, 'pred', yaw=90, nutrition=100, energy=100), set_status('pred', 'needs.nutrition', 10)],
    env=DAY, spot='pred', vantage=(6, 14), note=NEW, reads=[('读妖梦', f'yukkuri status {sel("myon")} raw'), ('读捕食反击', f'yukkuri status {sel("pred")} raw')])


# ---- headwear and recognition
def hat(damage, mend=0):
    return f'YukkuriHeadwear:{{Damage:{damage},Mend:{mend}}}'


add('hat_damage', 'headwear', '头饰损坏与自愈', '头饰损坏',
    '四只灵梦：完好／轻损（毛边小缺口）／撕裂（缺一大块）／撕裂但一直不安。安心时头饰一级级自己长好，每升一级开心说台词；不安的那只不自愈',
    '轻损→完好、撕裂→轻损各约 2.5 分钟（可倍速）', [
        yk('reimu', -5, 0, 'ok', yaw=180, lead=True, label='完好', **pair_ready()),
        yk('reimu', -2, 0, 'frayed', yaw=180, label='轻损', extra=hat(1), **pair_ready()),
        yk('reimu', 1, 0, 'torn', yaw=180, label='撕裂', extra=hat(2, 6000), **pair_ready()),
        yk('reimu', 4, 0, 'stuck', yaw=180, label='不安', extra=hat(2), **pair_ready()),
        set_status('stuck', 'mood.unease', 70)],
    env=DAY, note=NEW, vantage=(0, 8), reads=[('读撕裂头饰', f'yukkuri status {sel("torn")} raw')])

add('hat_shatter', 'headwear', '头饰碎裂与拼回', '头饰碎裂',
    '2 秒后灵梦的头饰碎成 2–3 块落在身边（哭）：它自己走过去一块块舔起，凑齐后头上是拼起来的碎饰，约 5 分钟长合成撕裂的头饰，再慢慢修好。你也可以捡碎片右键还给它',
    '拾碎片 10–40 秒；拼合约 5 分钟（可倍速）', [
        yk('reimu', 0, 0, 'owner', yaw=180, lead=True, **pair_ready())],
    env=DAY, late=[set_status('owner', 'accessory.state', 'shatter')], note=NEW, vantage=(0, 8),
    reads=[('读头饰', f'yukkuri status {sel("owner")} raw')])

add('hat_swap', 'headwear', '帽子分型与转交', '同类同龄转交',
    '两只成体水生魔理沙、普通魔理沙与水生子油作对照。用照护菜单摘取完整帽子，交给同类同阶段的缺帽个体；普通帽和水生帽不能互换，子油不能戴成体帽。后排两只已碎饰成体用于逐片交叉修补，混用兼容碎片仍须凑齐并慢慢长合',
    '手动操作；拼回继续观察原修补过程', [
        yk('marisa', -5, 0, 'donor', aquatic=True, label='水生成体·来源', yaw=180, lead=True, **pair_ready()),
        yk('marisa', -1, 0, 'receiver', aquatic=True, accessory=False, label='水生成体·接收', yaw=180, **pair_ready()),
        yk('marisa', 3, 0, 'land', accessory=False, label='普通成体', yaw=180, **pair_ready()),
        yk('marisa', 6, 0, 'young', aquatic=True, stage='child', accessory=False, label='水生子油', yaw=180, **pair_ready()),
        yk('marisa', -3, -4, 'pieces_a', aquatic=True, label='碎片甲', yaw=180, **pair_ready()),
        yk('marisa', 2, -4, 'pieces_b', aquatic=True, label='碎片乙', yaw=180, **pair_ready())],
    env=DAY, vantage=(0, 9), late=[set_status('pieces_a', 'accessory.state', 'shatter'),
                                               set_status('pieces_b', 'accessory.state', 'shatter')],
    note='完整帽转交后读新持有者的 accessory.detail；后排碎片应逐片消耗，原主已持有的其余碎片保留。完整帽与修补材料分别核对。',
    reads=[('读来源身份', f'yukkuri status {sel("donor")} raw'),
           ('读接收身份', f'yukkuri status {sel("receiver")} raw'),
           ('读碎片甲', f'yukkuri status {sel("pieces_a")} raw'),
           ('读碎片乙', f'yukkuri status {sel("pieces_b")} raw')])

add('hatless_kin', 'headwear', '失饰的孩子·亲油认不出', '亲油认不出',
    '北边妈妈智力 40，南边 80：前者认不出失饰子油并鄙视，后者仍认亲。读 accessory.contacts 的 known、last 和亲子关系变化；两边孩子均不饿，无食物干扰',
    '约 10–30 秒', [
        yk('reimu', 0, -5, 'mom_blind', yaw=90, label='智力40', intelligence=40, **pair_ready()),
        yk('chen', 3, -5, 'kid_blind', stage='child', nutrition=90, energy=95, yaw=-90, lead=True, accessory=False),
        f'yukkuri scenario kin {sel("mom_blind")} {sel("kid_blind")} false',
        yk('reimu', 0, 5, 'mom_sees', yaw=90, label='智力80', intelligence=80, **pair_ready()),
        yk('chen', 3, 5, 'kid_sees', stage='child', nutrition=90, energy=95, yaw=-90, accessory=False),
        f'yukkuri scenario kin {sel("mom_sees")} {sel("kid_sees")} false'],
    env=DAY, note=NEW, vantage=(-8, 0, -90), reads=[('读低智力亲油', f'yukkuri status {sel("mom_blind")} raw'), ('读高智力亲油', f'yukkuri status {sel("mom_sees")} raw')])

add('hatless_scorn', 'headwear', '失饰者被鄙视', '失饰鄙视',
    '没戴头饰的灵梦：智力普通的旁观者认不出它，生气说台词、走开；它哭、不安上涨。高智力的橙（90）认得出，不鄙视也不躲',
    '约 10–30 秒出现，之后每 30–40 秒重复', [
        yk('reimu', 0, 0, 'bare', yaw=-90, lead=True, accessory=False, label='无头饰', **pair_ready()),
        yk('marisa', 4, 0, 'a', yaw=90, **pair_ready()), yk('reimu', 4, 4, 'b', yaw=90, **pair_ready()),
        yk('chen', -4, 3, 'c', yaw=180, label='高智力', **pair_ready()), set_status('c', 'personality.intelligence', 90)],
    env=DAY, note=NEW, vantage=(0, 10), reads=[('读失饰者', f'yukkuri status {sel("bare")} raw')])

add('hatless_monster', 'headwear', '失饰重伤者·怪物', '失饰怪物',
    '没头饰且前后两处二级伤口的灵梦才符合怪物阈值。读 damaged_regions=2、grotesque=true；观察者产生 monster 接触记录，低血量停止欺凌',
    '约 10–60 秒；每个观察者每 30–40 秒重复', [
        yk('reimu', 0, 0, 'victim', yaw=-90, lead=True, accessory=False, nutrition=38, energy=95),
        set_status('victim', 'health.wound_at', 'front:tear:2'),
        set_status('victim', 'health.wound_at', 'back:tear:2'), set_status('victim', 'health.health', 3),
        yk('marisa', 4, 0, 'timid', yaw=90, **pair_ready()),
        yk('reimu', 4, 4, 'bully', yaw=90, label='凶悍', friendliness=HARSH, self_control=40, **pair_ready())],
    env=DAY, note=NEW, vantage=(0, 10), reads=[('读多伤失饰者', f'yukkuri status {sel("victim")} raw'), ('读观察者反应', f'yukkuri status {sel("timid")} raw')])

add('hatless_boundary', 'headwear', '怪物判定·单伤低血量', '单伤不算怪物',
    '失饰灵梦只有一处三级伤口且低血量：仍是普通鄙视，读 damaged_regions=1、grotesque=false；与多伤怪物场景对照',
    '约 10–30 秒', [
        yk('reimu', 0, 0, 'single', lead=True, accessory=False, **pair_ready()),
        set_status('single', 'health.wound_at', 'front:tear:3'), set_status('single', 'health.health', 3),
        yk('chen', 3, 0, 'observer', intelligence=40, **pair_ready())],
    env=DAY, vantage=(0, 10),
    reads=[('读单伤者', f'yukkuri status {sel("single")} raw'), ('读普通鄙视', f'yukkuri status {sel("observer")} raw')])

add('hatless_sanction', 'headwear', '失饰者被欺负', '失饰欺负',
    '两只凶悍成体认不出没头饰的健康灵梦：走近宣告、接触轻咬造成真实扣血和小伤口，再推搡或抢食，最后赶走。同一案只结算一次咬伤；低血量提前结束，高智力的橙不参与',
    '2 秒后开始，约 1–3 分钟', [
        yk('reimu', 0, 0, 'bare', yaw=-90, lead=True, accessory=False, label='无头饰', **pair_ready()),
        yk('marisa', 4, 0, 'b1', yaw=90, label='凶悍', friendliness=HARSH, self_control=40, **pair_ready()),
        yk('reimu', 4, 4, 'b2', yaw=90, label='凶悍', friendliness=HARSH, self_control=40, **pair_ready()),
        yk('chen', -4, 3, 'friend', yaw=180, label='高智力', **pair_ready()), set_status('friend', 'personality.intelligence', 90)],
    env=DAY, late=[set_status('bare', 'accessory.sanction', 'hatless')], note=NEW, vantage=(0, 10),
    reads=[('读欺负者', f'yukkuri status {sel("b1")} raw'), ('读欺凌伤口', f'yukkuri status {sel("bare")} raw')])

# ---- species traits and rare forms
add('myon_stick', 'species', '妖梦与楼观剑', '妖梦楼观剑',
    '两只还没有楼观剑的妖梦：左边脚下有根木棍，马上捡起来当楼观剑（开心台词）；右边只有一棵树，过一阵从树上折一根（平均约 20 秒）。两只口癖不同',
    '立即／约 20 秒', [
        yk('myon', -4, 0, 'm1', yaw=180, lead=True, extra=MYON % ('miyo', '0b'), **pair_ready()), item('minecraft:stick', 1, -4, 0, name='stick'),
        yk('myon', 4, 0, 'm2', yaw=180, extra=MYON % ('kinbao', '0b'), **pair_ready()),
        box(6, 0, 1, 6, 3, 1, 'minecraft:oak_log'), box(5, 3, 0, 7, 4, 2, 'minecraft:oak_leaves[persistent=true]', 'keep')],
    env=DAY, spot='race', note=NEW, vantage=(0, 10), reads=[('读折枝妖梦', f'yukkuri status {sel("m2")} raw')])

add('rare_forms', 'species', '稀有形态一览', '稀有形态',
    '从左到右：普通橙、长尾橙、圆尾橙；普通妖梦、红眼妖梦；普通魔理沙、暖桌魔理沙。长尾、圆尾只是外观；红眼妖梦被聚落成员看见是凶兆；暖桌魔理沙的冬夏见右边两个场景',
    '随意', [
        yk('chen', -9, 0, 'chen', yaw=180, lead=True, label='普通橙', **pair_ready()),
        yk('chen', -6, 0, 'longtail', yaw=180, form='LONGTAIL_CHEN', label='长尾橙', **pair_ready()),
        yk('chen', -3, 0, 'roundtail', yaw=180, form='ROUNDTAIL_CHEN', label='圆尾橙', **pair_ready()),
        yk('myon', 0, 0, 'myon', yaw=180, label='妖梦', **pair_ready()),
        yk('myon', 3, 0, 'redeye', yaw=180, form='REDEYE_MYON', label='红眼妖梦', **pair_ready()),
        yk('marisa', 6, 0, 'marisa', yaw=180, label='魔理沙', **pair_ready()),
        yk('marisa', 9, 0, 'kotatsu', yaw=180, form='KOTATSU_MARISA', label='暖桌魔理沙', **pair_ready())],
    env=DAY, spot='lineup', note=NEW, vantage=(0, 8), reads=[('读红眼妖梦', f'yukkuri status {sel("redeye")} raw')])


def kotatsu(sid, title, hint, biome, watch, roofed=False):
    lines = [f'fillbiome ~-12 56 ~-12 ~12 100 ~12 {biome}']
    if roofed:
        lines += [*shed(-4, 5), *shed(6, 5)]
    rest = 0 if roofed else 1200
    lines += [yk('marisa', 0, 0, 'kotatsu', yaw=180, lead=True, form='KOTATSU_MARISA', label='暖桌魔理沙', rest_wait=rest, **pair_ready()),
              yk('marisa', 3, 0, 'plain', yaw=180, label='普通魔理沙', rest_wait=rest, **pair_ready())]
    if roofed:
        lines += [home('kotatsu', -4, 5), home('plain', 6, 5)]
    season = title[-1]
    add(sid, 'species', title, hint, watch, '约 1–3 分钟', lines, env=DAY, spot='kotatsu', note=NEW, vantage=(0, 12),
        reads=[(f'读暖桌（{season}）', f'yukkuri status {sel("kotatsu")} raw'), (f'读对照（{season}）', f'yukkuri status {sel("plain")} raw')])


kotatsu('kotatsu_winter', '暖桌魔理沙·冬', '暖桌·冬', 'minecraft:snowy_plains',
        '雪原：普通魔理沙觉得冷（needs.temperature=cold），暖桌魔理沙不冷、也不进洞冬居（identity.kotatsu=winter_active），时不时说“暖和”')
kotatsu('kotatsu_summer', '暖桌魔理沙·夏', '暖桌·夏', 'minecraft:desert',
        '沙漠：暖桌魔理沙嫌热，白天也回棚下睡（先避暑）；普通魔理沙照常活动，时不时说“热”', roofed=True)

add('patchouli_vomit', 'species', '帕琪狼吞后呕吐', '帕琪呕吐',
    '四只饿坏的帕琪（营养 10）面前各有苹果：饿极狼吞或两餐挨太近时，约四成概率把馅吐出来（苦脸、馅 -1、不安 +3）；同样饿的灵梦照常吃',
    '约 10–60 秒', [
        *[yk('patchouli', -4 + 2 * i, 0, f'p{i}', yaw=180, lead=i == 0, nutrition=10, energy=95) for i in range(4)],
        *[item('minecraft:apple', 3, -4 + 2 * i, 2, name=f'apple{i}') for i in range(4)],
        yk('reimu', 6, 0, 'reimu', yaw=180, nutrition=10, energy=95), item('minecraft:apple', 3, 6, 2, name='apple_r')],
    env=DAY, spot='patchouli', note=NEW, vantage=(0, 10), reads=[('读帕琪', f'yukkuri status {sel("p0")} raw')])

add('chen_speed', 'species', '橙跑得快', '橙跑得快',
    '同样饿的橙和灵梦离一个苹果一样远（约 7 格）：橙移动速度是 1.2 倍，先到；读数 species.move_speed 橙 0.216、灵梦 0.18',
    '约 10 秒，可再按一次', [
        yk('chen', -3, -3, 'chen', yaw=180, lead=True, nutrition=30, energy=95),
        yk('reimu', 3, -3, 'reimu', yaw=180, nutrition=30, energy=95),
        item('minecraft:apple', 1, 0, 3)],
    env=DAY, spot='race', note=NEW, vantage=(0, 10), reads=[('读橙速度', f'yukkuri status {sel("chen")} raw')])

add('marisa_sumo', 'species', '魔理沙好斗', '魔理沙相扑',
    '三对要好的成体。生成时立即按普通相扑规则确定首局，之后的靠近、推挤、胜负与冷却照常发生；魔理沙×帕琪、魔理沙×橙交手时，总是魔理沙把对方推出去（力气 1.1 对 0.8／0.75）',
    '首局立即；之后约 10–60 秒', [
        yk('marisa', -8, 0, 'm1', yaw=-90, lead=True, **pair_ready()), yk('marisa', -6, 0, 'm2', yaw=90, **pair_ready()),
        yk('marisa', -1, 0, 'm3', yaw=-90, **pair_ready()), yk('patchouli', 1, 0, 'pa', yaw=90, **pair_ready()),
        yk('marisa', 6, 0, 'm4', yaw=-90, **pair_ready()), yk('chen', 8, 0, 'ch', yaw=90, **pair_ready()),
        *[f'yukkuri scenario bond {sel(a)} {sel(b)}' for a, b in (('m1', 'm2'), ('m3', 'pa'), ('m4', 'ch'))],
        *[f'yukkuri scenario sumo {sel(a)} {sel(b)}' for a, b in (('m1', 'm2'), ('m3', 'pa'), ('m4', 'ch'))]],
    env=DAY, spot='sumo', note=NEW, vantage=(0, 10))

# Reimu's cheek pouch and Alice's housekeeping need the forage field's burrow and grass
_ck = {k: uid('cheek_forage', k) for k in ('father', 'mother', 'child')}
add('cheek_forage', 'forage', '灵梦颊囊·外出觅食', '灵梦颊囊',
    '洞里一家，储粮空：父亲灵梦、母亲魔理沙一起出去割草带回。灵梦颊囊能多含 2 份，一趟上限比同角色多 2；读数看 Load 与 species.cheek_load',
    '一趟约 3–6 分钟（可倍速）', [
        yk('reimu', 0, 3, 'father', uuid=_ck['father'], yaw=180, lead=True, kin=kin_of(Partner=_ck['mother']), nutrition=95, energy=100),
        yk('marisa', 2, 3, 'mother', uuid=_ck['mother'], yaw=180, kin=kin_of(Partner=_ck['father']), nutrition=95, energy=100),
        yk('reimu', 1, 4, 'child', stage='baby', uuid=_ck['child'], yaw=180, nutrition=60, kin=kin_of(Mother=_ck['mother'], Father=_ck['father']))],
    late=[f'yukkuri scenario home {sel(n)} ~ ~ ~-1' for n in ('father', 'mother', 'child')],
    env=DAY, spot='forage', vantage=(0, 16), note=NEW,
    reads=[('读颊囊（灵梦）', f'yukkuri status {sel("father")} raw'), ('读觅食（魔理沙）', f'yukkuri status {sel("mother")} raw')])

add('alice_chores', 'forage', '爱丽丝持家', '爱丽丝持家',
    '同一个空洞里住着爱丽丝和灵梦（没有孩子，不外出）：两只都会割草、铺窝、捡垫料、清理污物；爱丽丝家务快 1.5 倍，读数 species.chores 涨得更快',
    '约 3 分钟对比', [
        yk('alice', 0, 3, 'alice', yaw=180, lead=True, nutrition=90, energy=95),
        yk('reimu', 2, 3, 'reimu', yaw=180, nutrition=90, energy=95)],
    late=[f'yukkuri scenario home {sel(n)} ~ ~ ~-1' for n in ('alice', 'reimu')],
    env=DAY, spot='forage', vantage=(0, 16), note=NEW,
    reads=[('读爱丽丝家务', f'yukkuri status {sel("alice")} raw'), ('读灵梦家务', f'yukkuri status {sel("reimu")} raw')])

# ---- colony rules: a village that is sure to have a leader (village_led), on the plains plot
COLONY_LEAD = '@e[tag=scn_lead,distance=..70]'


def colony(sid, title, hint, watch, wait, late, env=DAY, lines=(), note='', reads=()):
    add(sid, 'biomes', title, hint, watch, wait,
        [*BIOMES.plains_site(*BIOMES.spots['plains']), *lines, 'yukkuri settlement spawn village_led ~ ~ ~',
         'tag @e[type=mcyukkuri:patchouli,distance=..40,limit=1,sort=nearest] add scn_lead'],
        env=env, spot='plains', vantage=(0, 26), late=late, note=NEW + note, reads=list(reads), retry='先“清空本场地”再按一次')


colony('colony_hunt', '族规·公共狩猎与上缴', '公共狩猎',
       '有族长（帕琪）的村落：2 秒后族长召集公共狩猎，集合→出发→每只割 6 株草→返回；收获一部分上缴公仓（狩猎 1/3、家庭觅食 1/5）',
       '约 2–3 分钟', [Retry('yukkuri settlement rules hunt', COLONY_LEAD)],
       reads=[('读族规与公仓', 'yukkuri settlement rules')])
colony('colony_ration', '族规·黄昏分粮', '黄昏分粮',
       '黄昏时族长清点公仓：只给自家粮堆不够一天的“短缺户”分粮，其余不分；读数里看 SHORT 户和分配记录',
       '几秒', [Retry('yukkuri settlement rules ration', COLONY_LEAD)], env={'time': 'dusk'})
_judge = []
for _i, _off in enumerate(('theft', 'hatless', 'returned_exile', 'cannibal'), 1):
    _judge += [f'tag @e[type=#mcylab:yukkuri,tag=!scn_lead,tag=!scn_picked,nbt={{YukkuriFamily:{{Stage:"ADULT"}}}},distance=..45,limit=1,sort=random] add scn_off{_i}',
               f'tag @e[tag=scn_off{_i},limit=1] add scn_picked',
               f'execute as @e[tag=scn_off{_i},limit=1] at @e[tag=scn_lead,limit=1] run tp @s ~{_i} ~ ~1',
               f'execute as @e[tag=scn_off{_i},limit=1] run yukkuri settlement rules offend @s {_off}']
colony('colony_judge', '族规·审判', '族规审判',
       '四只成员被记录不同罪名，族长逐案走近、停步相向、听取与训斥，再按罪重、前科和族长性格执行处罚；附近目击者作出反应',
       '约 30–90 秒', _judge + ['yukkuri settlement rules judge'],
       note='每案经过真实移动与短暂停留，不固定四只个体分别受哪一档处罚。欺负需要附近有凶悍成员，没有时只驱逐。',
       reads=[('读待审阶段与判决', 'yukkuri settlement rules')])
# The empty village 64 blocks east lies across the snow, taiga, desert and badlands plots: plains ground and biome for its whole
# footprint first (the field clear restores the plots), otherwise its homes and meadow ring stand on snow block and sand.
_mx, _mz = BIOMES.spots['plains']
_migration_site = [*BIOMES.plains_site(_mx + 64, _mz), 'yukkuri settlement site vacant village ~64 ~ ~']
colony('colony_migrate', '族规·迁徙', '族群迁徙',
       '族长决定迁徙：讨论 30 秒→准备 30 秒→前往东侧 64 格外的真实空聚落，抵达者认领新窝、存放携粮；掉队者继续赶往新址',
       '约 2–4 分钟', [Retry('yukkuri settlement migrate', COLONY_LEAD)],
       lines=_migration_site,
       note='观察实际行走和新窝，读数核对中心、目的地与成员住所。行军超时且未到达会记失败，中心不会直接跳到新址。',
       reads=[('读迁徙与压力', 'yukkuri settlement rules'),
              ('读族长新住所', 'yukkuri status @e[tag=scn_lead,limit=1,sort=nearest] raw'),
              ('读全体成员安置', 'yukkuri status @e[type=#mcylab:yukkuri,distance=..110] raw'),
              ('读新聚落状态', 'execute positioned ~64 ~ ~ run yukkuri settlement site info')])

# ---- commodification
STOCK = '@e[type=#mcylab:yukkuri,distance=..60,nbt={NeoForgeData:{HumanCommerce:{Use:"stock"}}}]'
CORE_FILLING = 'mcyukkuri:core_filling[minecraft:custom_data={FillingVersion:1,Composition:"core",Method:"core",Nutrition:10,Sweetness:90,Odor:0,Variant:"reimu"}] 2'
add('shop_stale', 'shop', '滞销库存照料', '滞销库存照料',
    '整座商店放下后，货架上的个体被记成已上架 11 天且营养偏低；店员按自己的口粮预算照常照料、喂食。手里的中枢馅潜行右键店主可卖',
    '建筑几秒；白天约 10–30 秒看店员照料', ['yukkuri human build shop ~8 ~ ~17 south'],
    late=['scoreboard objectives add scn_t dummy', 'execute store result score #now scn_t run time query gametime',
          'scoreboard players remove #now scn_t 264000',
          f'execute as {STOCK} store result entity @s NeoForgeData.HumanCommerce.ForSaleSince long 1 run scoreboard players get #now scn_t',
          f'yukkuri status set {STOCK} needs.nutrition 24'],
    give=['minecraft:emerald 16', CORE_FILLING], env=DAY, vantage=(0, 30, 180), note=NEW,
    reads=[('读滞销个体', f'yukkuri status {STOCK[:-1]},limit=1,sort=nearest]')])
add('factory_wear', 'factory', '种油损耗', '种油损耗',
    '馒头工厂的种油已生产 5 轮：每 5 秒压力把不安推向 32、卫生拉向 60；再生一窝（不安 +5、哭）满 6 轮就退役进流水线',
    '约 1–3 分钟（人要待在附近）', ['yukkuri human build manju ~0 ~ ~17 south'],
    late=['execute as @e[type=#mcylab:yukkuri,distance=..60,nbt={NeoForgeData:{HumanCommerce:{Use:"breeder"}}}] '
          'run data modify entity @s NeoForgeData.HumanCommerce.FactoryCycles set value 5'],
    env=DAY, vantage=(0, 40, 180), note=NEW,
    reads=[('读种油', 'yukkuri status @e[type=#mcylab:yukkuri,distance=..60,nbt={NeoForgeData:{HumanCommerce:{Use:"breeder"}}},limit=1,sort=nearest]')])
add('exam_fail', 'town', '考试不及格', '考试不及格',
    '和“考场”一样，但宠物灵梦饿得只剩营养 4：考试必然不及格，之后被管教（不安 +10、营养清零），比原来的压力更重',
    '约 1–2 分钟', [
        block(0, 0, 0, 'mcyukkuri:exam_podium[facing=east]'), block(-4, 0, 0, 'mcyukkuri:exam_hide_box[facing=east]'), block(4, 0, 0, 'minecraft:lectern[facing=west]'),
        human('caretaker_brother', 5, 0, 'judge', 3, yaw=90, label='考官'), yk('reimu', 2, 2, 'pet', yaw=180, lead=True, nutrition=4, energy=95), *own('pet')],
    note=NEW, vantage=(0, 12), reads=[('读考生', f'yukkuri status {sel("pet")}')])

# ====================================================================================================== which concern each scenario answers
# Fresh NPC UUID on every start: confirmed death records are intentionally never reset by scene cleanup.
_custody_lines = [human('caretaker_brother', 0, -3, 'custody_owner', 1, label='NPC主人')]
for _kind, _dx, _role, _name, _label in (
        ('reimu', -6, 'companion', 'custody_pet', '带章宠物'),
        ('marisa', -3, 'stock', 'custody_stock', '库存个体'),
        ('alice', 0, 'input', 'custody_input', '工厂输入'),
        ('chen', 3, 'lost', 'custody_lost', '可先装篮'),
        ('reimu', 6, 'companion', 'custody_buyer', '已归玩家')):
    _commerce = f'Use:"{_role}",OwnerKind:"npc"'
    if _role == 'input':
        _commerce += ',FactoryStage:"observe",StageWork:25,FactoryYoung:1b,HoldReason:"nutrition",FactoryCycles:3'
    if _role == 'stock':
        _commerce += ',ForSaleSince:1L,StaleCuts:1,ShopCandidate:1b'
    _custody_lines += [yk(_kind, _dx, 2, _name, label=_label, grade=1 if _role in ('companion', 'lost') else None,
                          lead=_name == 'custody_pet', extra='NeoForgeData:{HumanCommerce:{' + _commerce + '}}'),
                       f'data modify entity {sel(_name)} {OWNED}.Owner set from entity {sel("custody_owner")} UUID']
_custody_lines += [*own('custody_buyer'),
                  f'yukkuri status relate {sel("custody_pet")} {sel("custody_owner")} 30',
                  f'data modify entity {sel("custody_lost")} YukkuriPet.Care.Harness set value 1b',
                  f'data modify entity {sel("custody_lost")} YukkuriPet.Care.WearOwner set from entity {sel("custody_owner")} UUID',
                  yk('marisa', 9, 2, 'custody_unknown', label='UNKNOWN旧档',
                     extra='NeoForgeData:{HumanCommerce:{Owner:' + uuid_nbt(uid('custody_death', 'unseen_owner')) + ',Use:"future_use"}}')]
add('custody_death', 'town', 'NPC主人死亡', '主人死亡',
    '先用篮子装“可先装篮”，再手动杀死NPC主人：现场宠物、库存、工厂输入转无主，玩家个体与UNKNOWN旧档不变；放出篮中个体，胸背带只归还一次，徽章仍保留',
    '死亡后立即；篮中个体放出后', _custody_lines, env=DAY, vantage=(0, 14), note=NEW,
    give=['mcyukkuri:yukkuri_carrier 2', 'mcyukkuri:sleep_spray 2', 'minecraft:emerald 16'],
    reads=[('读宠物归属', f'data get entity {sel("custody_pet")} NeoForgeData.HumanCommerce'),
           ('读工厂归属', f'data get entity {sel("custody_input")} NeoForgeData.HumanCommerce'),
           ('读玩家归属', f'data get entity {sel("custody_buyer")} NeoForgeData.HumanCommerce'),
           ('读旧档归属', f'data get entity {sel("custody_unknown")} NeoForgeData.HumanCommerce')])

# Fixed numerical fixtures; ordinary AI owns every action after these initial conditions.
add('balance_traits', 'meadow', '五属性读数对照', '属性读数',
    '四只普通油的五属性分别为20、50、80、90。看学习概率、遵守规则概率、等待tick和任务段数；先教会具体技能，再观察执行',
    '读数立即可看；行为需实际互动', [
        *[yk('reimu', (n - 1.5) * 3, 0, f'axis{v}', label=f'五属性{v}', lead=n == 0,
             intelligence=v, friendliness=v, boldness=v, attachment=v, self_control=v, social_wait=0)
          for n, v in enumerate((20, 50, 80, 90))],
        item('minecraft:apple', 8, 0, 4)], env=DAY,
    give=['mcyukkuri:field_notebook', 'minecraft:cookie 16'],
    reads=[('读五属性与行为参数', 'yukkuri status @e[tag=scn,type=mcyukkuri:reimu,distance=..40] raw')])

add('balance_growth', 'family', '固定成长边界', '年龄边界',
    '五只从赤油、子油、亚成、老年、寿命边界前1 tick开始。下一tick依次成为子油、亚成、成油、老年和自然死亡；营养低不会延后年龄资格',
    '下一模拟tick', [
        *[command for n, age in enumerate((71999, 215999, 335999, 947999, 1055999))
          for command in (yk('reimu', (n - 2) * 3, 0, f'age{n}', label=str(age), lead=n == 2, nutrition=70),
                          set_status(f'age{n}', 'growth.age_ticks', age))],
        item('minecraft:apple', 8, 0, 4)], env=DAY, spot='growth',
    reads=[('读年龄与阶段', 'yukkuri status @e[tag=scn,type=mcyukkuri:reimu,distance=..40] raw')])

add('balance_relation', 'meadow', '亲近与恐惧独立', '亲近和恐惧',
    '三只对玩家亲近80，恐惧分别0、40、80。观察正常亲近、谨慎等待和高恐惧下保持距离；真实进食和排便后再读关系，亲近不会自动抹掉恐惧',
    '约30–60秒', [
        *[command for n, fear in enumerate((0, 40, 80))
          for command in (yk('reimu', (n - 1) * 4, 0, f'fear{fear}', label=f'亲近80 恐惧{fear}',
                             lead=n == 0, social_wait=0, bladder=80, nutrition=65),
                          f'yukkuri status relate {sel(f"fear{fear}")} @s 80',
                          f'yukkuri status fear {sel(f"fear{fear}")} @s {fear}')],
        item('minecraft:apple', 6, 0, 3)], env=DAY, give=['minecraft:apple 6'],
    reads=[('读对象关系', 'yukkuri status @e[tag=scn,type=mcyukkuri:reimu,distance=..40] raw')])

add('balance_first_contact', 'meadow', '陌生个体首次问候', '首次问候',
    '橙与爱丽丝初始无关系，亲近度为0。留在观察台等待普通问候；成功后读数应出现彼此关系且亲近度大于0，再靠近观察它们能否主动问候中立玩家',
    '约1–2分钟', [
        yk('chen', -2, 0, 'stranger_a', label='陌生橙', attachment=95, social_wait=0, lead=True),
        yk('alice', 2, 0, 'stranger_b', label='陌生爱丽丝', attachment=95, social_wait=0)],
    env=DAY, give=['mcyukkuri:field_notebook'],
    reads=[('读首次接触关系', 'yukkuri status @e[tag=scn,type=#mcylab:yukkuri,distance=..40] raw')])

add('balance_forms', 'meadow', '特殊形态边界', '形态边界',
    '不足油仅含混发声，智力不超过25、友善不低于75；多毛友善和自制不超过25、依恋不低于75。正常灵梦作对照；真实学习仍可改善合法区间内的属性',
    '读数立即可看', [
        yk('reimu', -4, 0, 'deficient', congenital='BEAN_BODY', intelligence=90, friendliness=10, label='不足', lead=True),
        yk('reimu', 0, 0, 'wasa', form='WASA_REIMU', friendliness=90, attachment=10, self_control=90, label='多毛'),
        yk('reimu', 4, 0, 'ordinary', label='普通'), item('minecraft:apple', 6, 0, 4)], env=DAY,
    give=['mcyukkuri:field_notebook'],
    reads=[('读形态与五属性', 'yukkuri status @e[tag=scn,type=mcyukkuri:reimu,distance=..40] raw')])

add('balance_deibu', 'family', '灵胖成年锁定', '灵胖锁定',
    '两只亚成从成年前1 tick和累计23999 tick开始；高营养高压力者锁定灵胖，营养70者清零后普通成年。锁定后降压不撤回，扩体等待安全接缝',
    '年龄1 tick；体型在安全时变化', [
        *[command for n, nutrition in enumerate((90, 70))
          for command in (yk('reimu', n * 4 - 2, 0, f'deibu{n}', stage='teen', share=1, nutrition=nutrition,
                             label='满足条件' if n == 0 else '营养中断', lead=n == 0),
                          set_status(f'deibu{n}', 'mood.stress', 80),
                          f'data modify entity {sel(f"deibu{n}")} YukkuriSubspecies.DeibuExposure set value 23999')],
        item('minecraft:apple', 6, 0, 4)], env=DAY, spot='growth',
    note='累计23999 tick为明确设置的边界起点；完整持续一天可另在亚成期用倍速观察，未用脚本代替成长结算。',
    reads=[('读灵胖锁定与空间状态', 'yukkuri status @e[tag=scn,type=mcyukkuri:reimu,distance=..40] raw')])

# codes are the rows of the table in docs/EXPERIMENTAL_MAP.md
TOPICS = {
    'balance_traits': 'N1', 'balance_growth': 'N1', 'balance_relation': 'N1', 'balance_first_contact': 'N1', 'balance_forms': 'N1', 'balance_deibu': 'N1',
    'agitation_relief': 'D2', 'adult_pairing': 'F6', 'widow_repair': 'F6', 'alice_hunt': 'R4', 'alice_interrupt': 'R4',
    'aftermath_growth': 'F7', 'aftermath_burden': 'F7', 'aftermath_depletion': 'F7',
    'den_family': 'W1', 'widow_family': 'W1', 'dig': 'W2', 'indep_near': 'W3', 'indep_far': 'W3', 'den_move': 'W4', 'forage': 'W5', 'graze': 'W6', 'pet_birth': 'W7',
    'sleep': 'D1', 'meet': 'D1', 'meal': 'D1', 'toilet': 'F5', 'hurt': 'F3', 'hurt_kin': 'F3',
    'birth_land_caught': 'F1', 'birth_land_reunion': 'F1', 'birth_land_missed': 'F1', 'feed': 'F2', 'first_toilet': 'F2', 'hop': 'F2', 'growth': 'F4',
    'family_distance': 'F8', 'family_search': 'F8',
    'rain_shelter': 'R1', 'thunder': 'R1', 'rain_open': 'R1', 'pred_watch': 'R2', 'pred_hunt': 'R2', 'pred_range': 'R2', 'pred_pets': 'R2', 'marks': 'R3',
    'sail': 'A1', 'hat_custody': 'A2', 'hat_custody_burn': 'A2', 'shore': 'A1', 'shore_child': 'A1', 'high': 'A1', 'voyage': 'A1', 'braid': 'A4', 'afloat': 'A2', 'shake': 'A2',
    'water_life': 'A6', 'water_winter': 'A6', 'water_colony': 'A8', 'water_neighbours': 'A7', 'water_threat': 'A7', 'water_dock': 'A8',
    'cargo_empty': 'A1', 'cargo_full': 'A1', 'cargo_load': 'A1', 'cargo_rescue': 'A4', 'cargo_stow': 'A1',
    'birth_boat_caught': 'A3', 'birth_boat_missed': 'A3', 'shore_babies': 'A4', 'rescue_baby': 'A4', 'self_rescue': 'A4', 'self_far': 'A4', 'self_tired': 'A4', 'shore_capsized': 'A4', 'capsize_caring': 'A4', 'capsize_selfish': 'A4', 'capsize_siblings': 'A4', 'couple_boats': 'A5', 'family_skiffs': 'A5',
    'house_empty': 'H1', 'house_pair': 'H2', 'house_eggs': 'H3',
    'shop_build': 'M1', 'factory_labor': 'M2', 'factory_manju': 'M2', 'factory_bag': 'M2', 'walker': 'M3', 'harness_walk': 'M3', 'feed_wild': 'M3', 'oniisan': 'M4', 'oniisan_prey': 'M4', 'attack_witness': 'M4',
    'wild': 'M5', 'badge': 'M5', 'exam': 'M5', 'date': 'M5', 'transport': 'M5', 'custody_death': 'M5',
    'airborne': 'R8', 'house_health': 'R9',
    'wound_parts': 'R5', 'wound_leak': 'R5', 'treatment_severe': 'R5', 'treatment_scars': 'R5', 'treatment_care': 'R5', 'core_exposed': 'R5', 'core_filling': 'R5', 'remains_rot': 'R6', 'carrion': 'R6', 'cannibal_hunt': 'R6',
    'last_words': 'R7', 'offering': 'R7', 'sacrifice': 'R7', 'sacrifice_reluctant': 'R7',
    'hat_swap': 'C1', 'hat_damage': 'C1', 'hat_shatter': 'C1', 'hatless_kin': 'C2', 'hatless_scorn': 'C2', 'hatless_boundary': 'C2',
    'hatless_monster': 'C2', 'hatless_sanction': 'C2',
    'myon_stick': 'S1', 'myon_brave': 'S1', 'rare_forms': 'S2', 'kotatsu_winter': 'S2', 'kotatsu_summer': 'S2',
    'cheek_forage': 'S3', 'alice_chores': 'S3', 'patchouli_vomit': 'S3', 'chen_speed': 'S3', 'marisa_sumo': 'S3',
    'colony_hunt': 'B2', 'colony_ration': 'B2', 'colony_judge': 'B2', 'colony_migrate': 'B2',
    'shop_stale': 'M6', 'factory_wear': 'M6', 'exam_fail': 'M6',
}
for _s in S:
    _s.asks = TOPICS.get(_s.id) or ('B1' if _s.id.startswith('settle_') else '')
    assert _s.asks, _s.id
