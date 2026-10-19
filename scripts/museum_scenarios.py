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
OWNED = 'NeoForgeData.HumanCommerce'
# an adolescent who can already live on its own (the wild-life agent's recipe: Age 40000, Nourishment 30000, Maturity 60000)
TEEN_FAMILY = ('Version:2,Initialized:1b,Stage:"JUVENILE",Age:40000L,Nourishment:30000L,Maturity:60000L,Lifespan:2208000L,AdultScale:1f,Scale:0.72f')
LATE_COMMANDS = ('yukkuri scenario home', 'yukkuri scenario birth', 'yukkuri scenario eggs', 'yukkuri scenario boat', 'yukkuri scenario shore')
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
    reads=[('读单亲觅食', f'data get entity {sel("mother")} YukkuriForageView'),
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
    env=DAY, spot='small', vantage=(8, 14), reads=[('读搬家说明', f'data get entity {sel("mother")} YukkuriHabitatView')])

# ====================================================================================================== vagrants and foraging
add('dig', 'forage', '流浪油挖穴', '流浪挖穴', '没有家的成年流浪油走到土丘边：先挖通地面，再向上挖内部第二层；入口保留一格高、内部两格高，每块约 6 秒，挖完自成一个家',
    '约 3–6 分钟（可倍速）', [
        yk('marisa', 0, 0, 'vagrant', yaw=180, lead=True, **pair_ready()),
        'gamerule mobGriefing true'], env=DAY, spot='dig', vantage=(0, 12),
    reads=[('读挖穴状态', f'yukkuri status {sel("vagrant")}')])

_fm, _ff, _fc = uid('forage', 'mother'), uid('forage', 'father'), uid('forage', 'child')
add('forage', 'forage', '外出觅食', '外出觅食', '5×5 土丘洞里的一家：储粮空、家旁的草已稀疏，父母各自向远处草场出发；实际采草、衔回并存入洞中。读数看目标半径、最远距离和送回次数',
    '数分钟（可倍速）', [
        yk('marisa', 0, 3, 'father', uuid=_ff, yaw=180, lead=True, kin=f'Partner:{uuid_nbt(_fm)}', nutrition=60, energy=100),
        yk('reimu', 2, 3, 'mother', uuid=_fm, yaw=180, kin=f'Partner:{uuid_nbt(_ff)}', nutrition=60, energy=100),
        yk('reimu', 1, 4, 'child', stage='baby', uuid=_fc, yaw=180, nutrition=50, kin=f'Mother:{uuid_nbt(_fm)},Father:{uuid_nbt(_ff)}'),
        ],
    late=[f'yukkuri scenario home {sel(n)} ~ ~ ~-1' for n in ('mother', 'father', 'child')],
    env=DAY, spot='forage', vantage=(0, 16),
    reads=[('读觅食数据（父）', f'data get entity {sel("father")} YukkuriForageView'), ('读觅食数据（母）', f'data get entity {sel("mother")} YukkuriForageView')])

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
    '橙身上有一处磕伤，旁边是认得它的伴侣爱丽丝。惊慌过去后，爱丽丝会走过来担心地哭着舔舐：完整舔舐让最老的一处轻伤降一级并回 2 点生命，深伤只安抚；治疗用橙汁和绷带',
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
    '小橙磕伤了，亲油灵梦认得它。惊慌过去后，亲油会走近担心地哭着舔舐：完整舔舐让最老的一处轻伤降一级并回 2 点生命，深伤只安抚；治疗仍用橙汁和绷带',
    '先等惊慌过去，看舔舐；再用橙汁和绷带治疗', [
        yk('reimu', 0, 0, 'mom', yaw=-90, **pair_ready()),
        yk('chen', 4, 0, 'kid', stage='child', yaw=90, lead=True, nutrition=90, energy=95),
        f'yukkuri scenario kin {sel("mom")} {sel("kid")} false',
        f'yukkuri scenario wound {sel("kid")}'], give=['mcyukkuri:orange_juice 2', 'mcyukkuri:bandage 2'], spot='hurt_kin', vantage=(0, 10))

add('growth', 'family', '一路长大', '一路长大', '一家里右侧亚成油从成长 100% 开始，留足空间后应在 5 秒内成为成体；其余赤油、子油继续按正常营养与年龄成长，可用倍速或“推进”观察',
    '100% 换阶段约 5 秒；其余可推进观察', [
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
           disposition=(30, 40, 35, 70), kin=f'Mother:{uuid_nbt(_fd["parent_bold"])}', **pair_ready())],
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

add('marks', 'weather', '脏污与伤痕', '脏污伤痕', '前三只分别是干净、脏污 3 级、预置疤痕的外观对照。另两只同种灵梦各有 1／3 级撕裂，已包扎、衣食充足；伤口最终关闭时按原伤级判疤，重撕裂必留 3 级疤。两者初始疤痕已清零，橙汁不参与本对照', '约 10–30 秒', [
    yk('reimu', -6, 0, 'clean', yaw=180, lead=True), yk('reimu', -3, 0, 'dirty', yaw=180), yk('reimu', 0, 0, 'scarred', yaw=180),
    yk('reimu', 4, 0, 'light_tear', yaw=180, **pair_ready()), yk('reimu', 8, 0, 'heavy_tear', yaw=180, **pair_ready()),
    set_status('dirty', 'marks.dirt', 3), set_status('scarred', 'marks.scars', 1),
    set_status('light_tear', 'marks.scars', 0), set_status('heavy_tear', 'marks.scars', 0),
    set_status('light_tear', 'health.wound_at', 'front:tear:1'), set_status('heavy_tear', 'health.wound_at', 'front:tear:3'),
    set_status('light_tear', 'health.treat', 'bandage'), set_status('heavy_tear', 'health.treat', 'bandage')],
    give=['mcyukkuri:bandage 2'], spot='marks', vantage=(1, 10))

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
pond('hat_custody', '暂放帽船与没收', '托帽与没收',
    '亲油站在岸上，自己的帽船停在水面并载着赤油。暂放不悲伤；站在干岸上空手蹲下右击帽船，观察赤油安全上岸、帽子进入物品栏和主人失帽反应',
    '手动操作', [captain(), yk('marisa', -2, 1, 'kid', stage='baby', aquatic=True, yaw=-90, **pair_ready()), boat('unattended', 'a', sel('kid'))],
    note='再把同一顶帽子归还主人，核对只回收一顶帽、幼体仍在、主人能继续放船。没有安全落点时没收应失败。',
    reads=[('读托管与失帽', f'yukkuri status {sel("a")} raw'), ('读没收后的赤油', f'yukkuri status {sel("kid")} raw')])
_cargo_boat = '@e[type=mcyukkuri:hat_boat,distance=..48,limit=1,sort=nearest]'
for _cargo_mode, _cargo_title, _cargo_hint, _cargo_watch in (
        ('cargo_empty', '帽内粮堆·空仓', '空仓', '亲油帽内没有粮堆；持食物逐份存入，观察少量、较多、满载的变化'),
        ('cargo_little', '帽内粮堆·较少', '较少 2 份', '帽腔底部只有一小堆，仍留出内侧空间；不同食物只改变数量，不改变粮堆造型'),
        ('cargo_much', '帽内粮堆·较多', '较多 5 份', '帽内粮堆升高并扩展；对比少量与满载时可用的帽内空间'),
        ('cargo_full', '帽内粮堆·满载', '11 粮＋1 桨', '11 份粮食和一根桨占满 12 份容量；再存粮应留在手中，亲油仍有自己的位置。保存重进核对同船与真实库存')):
    pond(_cargo_mode, _cargo_title, _cargo_hint, _cargo_watch, '随意',
         [captain(), boat(_cargo_mode, 'a', sel('a'))], give=['minecraft:apple 8', 'minecraft:carrot 8'],
         note='“读船粮”可看真实库存；读档检查请重进当前世界，不要重新生成场景。吃掉的口粮不会恢复。',
         reads=[('读船粮与实物桨', f'data get entity {_cargo_boat} Cargo')])
pond('cargo_load', '孩子在帽内·再装粮', '先载幼体', '赤油占 2 份、桨占 1 份，最多再装 9 份粮；持食物逐份存入，观察容量与落脚空间，装不下的留在手中', '随意', [
    captain(), yk('marisa', -2, 1, 'kid', stage='baby', aquatic=True, yaw=-90, **pair_ready()), boat('cargo_load', 'a', sel('kid'))],
    give=['minecraft:apple 8', 'minecraft:carrot 8'], note='对比先装满粮再接孩子的场景；还可潜行一次尝试存入多份，观察是否穿粮或挤落。')
pond('cargo_rescue', '满粮帽船·弃粮救援', '满粮救援', '亲油带着 11 份口粮与一根桨，赤油真实落水；亲油主动抛下挡住救援的粮食，腾出位置再接回孩子，船桨保留', '约 30–90 秒', [
    captain(), yk('marisa', -2, 1, 'kid', stage='baby', aquatic=True, yaw=-90, **pair_ready()), boat('cargo_rescue', 'a', sel('kid'))],
    note='看真实食物落到水里、库存随之减少；观察孩子接回后能否安稳留在帽内，不应立即捡粮重新挡住它。')
pond('cargo_stow', '满载帽船·暂放再取', '暂放再取', '7 份粮、一根桨和一只子油共占 12 份；按“夜晚”观察亲油为帽内休息把粮移到可用侧帽檐，天亮空间允许时再收回', '观察一轮昼夜', [
    captain(), yk('marisa', -2, 1, 'kid', stage='child', aquatic=True, yaw=-90, **pair_ready()), boat('cargo_stow', 'a', sel('kid'))],
    note='暂放仍计入总容量，有实物位置且总数保持；中途保存退出再重进当前世界，核对暂放位置和总粮数。再试返岸收帽、重新放帽，不能复制口粮。',
    reads=[('读暂放幼体', f'yukkuri status {sel("kid")}')])
pond('shore', '返岸收帽', '返岸收帽', '亲油划回岸边，自己跳上岸、收回帽子（至多 1200 tick）', '约 1 分钟', [captain(), boat('shore', 'a', sel('a')), SHORE_CALL])
pond('shore_child', '带幼体返岸', '带幼体返岸', '船上有一只子油时，亲油带着它划回岸边，一起上岸、收帽', '约 1 分钟', [
    captain(), yk('marisa', -2, 1, 'kid', stage='child', aquatic=True, yaw=-90, **pair_ready()), boat('shore', 'a', sel('kid')), SHORE_CALL])
pond('shore_baby', '赤油返岸', '赤油返岸', '船上是一只赤油：亲油带它返岸收帽；赤油不会爬回帽里，亲油就留在岸边陪着它', '约 1 分钟返岸，之后亲油留守', [
    captain(), yk('marisa', -2, 1, 'kid', stage='baby', aquatic=True, yaw=-90, **pair_ready()), boat('shore', 'a', sel('kid')), SHORE_CALL])
pond('shore_babies', '两只赤油返岸', '两只赤油返岸', '船上是两只赤油：亲油用辫子把它们一只只放上岸，收帽后留在岸边陪着', '约 1–2 分钟返岸，之后亲油留守', [
    captain(), yk('marisa', -2, 1, 'k1', stage='baby', aquatic=True, yaw=-90, also=('young',), **pair_ready()),
    yk('marisa', -2, -1, 'k2', stage='baby', aquatic=True, yaw=-90, also=('young',), **pair_ready()), boat('shore', 'a', sel_all('young')), SHORE_CALL])
pond('low', '两幼体低载荷', '两幼体', '两只赤油占 4 份，加两份粮和一根桨共 7 份，观察低载荷航行', '约 1–3 分钟', [
    captain(), yk('marisa', -2, 1, 'k1', stage='baby', aquatic=True, also=('young',), **pair_ready()),
    yk('marisa', -2, -1, 'k2', stage='baby', aquatic=True, also=('young',), **pair_ready()), boat('low', 'a', sel_all('young'))])
pond('high', '四幼体高载荷', '四幼体', '四只赤油占 8 份，加三份粮和一根桨共 12 份，观察满载吃水、座位与继续装粮被拒绝', '约 1–3 分钟', [
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
pond('self_far', '远帽落水自救', '远帽自救', '帽主在离自己帽船九格的水中，观察她是否持续向同一帽子靠近，接触后抓缘攀回；途中继续承受入水伤害', '约 1 分钟',
     [captain(), boat('self_far', 'a', sel('a'))], reads=[('读自救目标与阶段', f'yukkuri status {sel("a")} raw')])
pond('self_tired', '疲惫落水返帽', '疲惫弱漂', '同样离帽船九格，帽主只有 3 点体力；观察向帽子弱漂与挣扎，抓握和攀入仍需能力、体力与实际接触，不保证获救', '约 1 分钟',
     [captain(), boat('self_tired', 'a', sel('a'))], reads=[('读疲惫与自救', f'yukkuri status {sel("a")} raw')])
pond('shore_capsized', '从岸上找回翻帽', '岸上返帽', '帽主在岸内六格，自己的帽船翻在水边；观察实际走向帽子、下水扶正和重新登船，托管期间不因无帽持续悲伤', '约 1–2 分钟',
     [captain(), boat('return_capsized', 'a', sel('a'))], reads=[('读岸上返帽', f'yukkuri status {sel("a")} raw')])
for _sid, _title, _hint, _disposition in (
        ('capsize_caring', '翻船决策·重依恋', '重依恋翻船', (10, 30, 90, 70)),
        ('capsize_selfish', '翻船决策·重自保', '重自保翻船', (90, 30, 10, 70))):
    pond(_sid, _title, _hint,
         '同一只亲油和赤油真实翻船落水。按利己度与依恋度抽一次先救子或先自保；救子须先翻正原帽、游近抓握、托到受支撑帽面，体力或接触失败仍可能失手',
         '约 1 分钟', [captain(disposition=_disposition),
                      yk('marisa', -2, 1, 'kid', stage='baby', aquatic=True, yaw=-90, **pair_ready()),
                      boat('capsize_family', 'a', sel('kid'))],
         note='两组改变选择概率，不保证每次同样结果；观察孩子获救而亲油尚在水中的过程。',
         reads=[('读翻船亲油', f'yukkuri status {sel("a")} raw')])
pond('shake', '摇船', '摇船', '有孩子的船长被摇：聚拢孩子并逃离；对比：没有孩子的船长只是普通惊吓（再按一次，孩子数为 0）', '约 30 秒', [
    captain(), yk('marisa', -2, 1, 'k1', stage='baby', aquatic=True, also=('young',), **pair_ready()),
    yk('marisa', -2, -1, 'k2', stage='baby', aquatic=True, also=('young',), **pair_ready()), boat('shake', 'a', sel_all('young'))])
pond('afloat', '生成即坐船', '水上生成', '帽主直接出现在同一顶真实帽船内，赤油从初始家庭名单登船；不先把帽主送到岸上再播放出航。之后由普通水生生活接管', '约 10 秒', [
    captain(), yk('marisa', -2, 1, 'k1', stage='baby', aquatic=True, **pair_ready()), boat('afloat', 'a', sel('k1'))])
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


pond('family_skiffs', '亲子各自帽船', '亲子小船', '亲油与已完成初航的子油各在自己的真实帽船上；观察子油在亲船附近生活、跟随、受惊回亲船及恢复后再出航', '约 1–3 分钟', [
    captain('mother'), yk('marisa', _dx, _dz, 'kid', stage='child', aquatic=True, yaw=-90, **pair_ready()),
    f'data modify entity {sel("kid")} YukkuriSubspecies.AquaticLife.FirstVoyage.Completed set value 1b',
    f'yukkuri scenario kin {sel("mother")} {sel("kid")} false',
    boat('afloat', 'mother', sel('mother')),
    f'yukkuri scenario boat afloat {sel("kid")} {sel("kid")} ~{_dx} ~ ~{_dz} ~{_dx + 1} ~-1 ~{_dz}'],
    note='初始子油已学会初航，尚未独立离家；场景只预置起点，后续不固定状态或保证回船。',
    reads=[('读小船子油', f'yukkuri status {sel("kid")} raw')])


def boat_birth(sid, title, hint, outcome, watch, note=''):
    pond(sid, title, hint, watch, '约 1–3 分钟准备与分娩，之后看照护', [
        captain('mother'), yk('marisa', -2, 1, 'father', aquatic=True, yaw=-90, **pair_ready()),
        f'yukkuri scenario bond {sel("mother")} {sel("father")}',
        item('minecraft:stick', 1, 0, 0, name='mother_paddle'),
        item('minecraft:stick', 1, -2, 1, name='father_paddle'),
        item('minecraft:apple', 6, -1, -1, name='birth_food'),
        f'yukkuri scenario birth {sel("mother")} {sel("father")} {outcome}'],
        note=note, give=['minecraft:apple 8'], reads=[('读母油与产程', f'yukkuri status {sel("mother")} raw'),
                          ('读父油与陪护', f'yukkuri status {sel("father")} raw')])


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
    f'data modify entity {sel("fla")} YukkuriLife.Personality.Lessons set value 0',
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
add('shop_build', 'shop', '整座商店', '整座商店', '商店整体放下：店主、货架上的在售个体和柜台后的银章爱丽丝；右键店主开店，登记在 shop list 里',
    '建筑几秒', ['yukkuri human build shop ~8 ~ ~17 south'], give=['minecraft:emerald 16'], vantage=(0, 30, 180),
    reads=[('店铺登记（shop list）', 'yukkuri human shop list')])
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
HARSH = (50, 80, 40, 40)          # selfishness, aggression, attachment, self-control of a bully (Contempt.harsh)


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
    '四只灵梦各有 1 级、2 级、3 级、两处 3 级裂口；观察 10 秒：扣血分别为 0、0.5、1、1。地面痕迹不可拾取，包扎立即停止新增漏馅，低血量开放伤口逐渐变瘪',
    '先等 10 秒读血量，再给第 3、4 只包扎', [
        *[yk('reimu', -6 + 4 * i, 0, f'v{i}', yaw=180, lead=i == 1, label=label, nutrition=20, energy=95)
          for i, label in enumerate(('轻伤', '二级裂口', '三级裂口', '多处裂口'))],
        *[set_status(f'v{i}', 'health.wound_at', f'front:tear:{min(3, i + 1)}') for i in range(4)],
        set_status('v3', 'health.wound_at', 'back:tear:3')],
    give=['mcyukkuri:bandage 4', 'mcyukkuri:orange_juice 2'], note=NEW, vantage=(0, 10),
    reads=[('读二级裂口', f'yukkuri status {sel("v1")}'), ('读多处裂口', f'yukkuri status {sel("v3")}')])

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
    give=['minecraft:apple 4'], note=NEW + '首轮未中时没有献身动作，可观察至约50秒或重开；“读献身状态”的 home.offering_state 显示扫描数、阶段和上次拒绝原因。', vantage=(0, 10),
    reads=[('读献身状态', f'yukkuri status {sel("parent")} raw')])

_od = {k: uid('offer_dedicate', k) for k in ('parent', 'child')}
add('offer_dedicate', 'body', '献出遗体', '献出遗体',
    '约 6 秒后亲油在饿坏的子油面前中枢受损：临终说“吃了我吧”，遗体标为献给孩子（不发臭）；孩子不顾一切走去吃，吃完哀伤',
    '约 20 秒', [
        yk('reimu', 0, 0, 'parent', uuid=_od['parent'], lead=True, nutrition=70, energy=95),
        yk('reimu', 2, 1, 'child', stage='child', uuid=_od['child'], nutrition=6, energy=95, kin=kin_of(Mother=_od['parent']))],
    late=[Retry(set_status('parent', 'health.core', 'damage'), sel('parent'), tries=1, first=80)],
    note=NEW, vantage=(0, 10), reads=[('读孩子', f'yukkuri status {sel("child")}')])

# ---- predators: a parent drawing the chase, Youmu standing her ground
def protect_child(sid, title, hint, disposition, boldness, watch):
    parent, child = uid(sid, 'parent'), uid(sid, 'child')
    add(sid, 'weather', title, hint, watch,
        '约 30 秒内；概率行为可重新生成对比', [
            yk('reimu', 0, 0, 'parent', uuid=parent, yaw=-90, lead=True,
               disposition=disposition, boldness=boldness, **pair_ready()),
            yk('reimu', 3, 1, 'child', stage='child', uuid=child, yaw=-90, nutrition=95, kin=kin_of(Mother=parent)),
            yk('flandre', 12, 0, 'pred', yaw=90, nutrition=100, energy=100), set_status('pred', 'needs.nutrition', 10)],
        env=DAY, spot='pred', vantage=(6, 14), note=NEW,
        reads=[('读亲油反应', f'yukkuri status {sel("parent")} raw'), ('读护子追击', f'yukkuri status {sel("pred")} raw')])


protect_child('sacrifice', '挡捕食种', '挡捕食种', (10, 40, 90, 85), 80,
    '芙兰追小灵梦；健康、勇敢而依恋孩子的亲油较愿意挺身引开追击，之后照常逃跑或受击。护子由实际数值决定，仍有失败机会')
protect_child('sacrifice_reluctant', '挡捕食种·低意愿', '护子低意愿', (90, 30, 20, 30), 25,
    '站位与高意愿场景相同，亲油改为胆小、自私、低依恋：更可能顾着自己逃。重开可比较两组护子意愿，不强制孩子受伤或亲油牺牲')

MYON = 'YukkuriMyon:{SpeechQuirk:"%s",Kanroken:%s,Initialized:1b}'
add('myon_brave', 'weather', '妖梦拔刀', '妖梦拔刀',
    '带楼观剑的妖梦被饿芙兰盯上：不逃，喊着拔刀冲上去用棍子打（约 1.2 秒一击）；旁边的灵梦照常逃跑躲藏',
    '约 30 秒', [
        yk('myon', 0, 0, 'myon', yaw=-90, lead=True, extra=MYON % ('miyo', '1b'), **pair_ready()),
        yk('reimu', 0, 3, 'a', yaw=-90, **pair_ready()),
        yk('flandre', 12, 0, 'pred', yaw=90, nutrition=100, energy=100), set_status('pred', 'needs.nutrition', 10)],
    env=DAY, spot='pred', vantage=(6, 14), note=NEW, reads=[('读妖梦', f'yukkuri status {sel("myon")} raw')])


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

add('hatless_kin', 'headwear', '失饰的孩子·亲油认不出', '亲油认不出',
    '北边：智力 100（不到 190）的妈妈认不出没戴头饰、并不饥饿的子油，不亲近，反而生气走开；南边：同样条件下，智力 190 的妈妈认得出。两边不放食物，避免喂食打断认亲观察',
    '约 10–30 秒', [
        yk('reimu', 0, -5, 'mom_blind', yaw=90, label='智力100', **pair_ready()),
        yk('chen', 3, -5, 'kid_blind', stage='child', nutrition=90, energy=95, yaw=-90, lead=True, accessory=False),
        f'yukkuri scenario kin {sel("mom_blind")} {sel("kid_blind")} false',
        yk('reimu', 0, 5, 'mom_sees', yaw=90, label='智力190', **pair_ready()),
        yk('chen', 3, 5, 'kid_sees', stage='child', nutrition=90, energy=95, yaw=-90, accessory=False),
        f'yukkuri scenario kin {sel("mom_sees")} {sel("kid_sees")} false',
        set_status('mom_sees', 'cognition.innate', 190)],
    env=DAY, note=NEW, vantage=(-8, 0, -90), reads=[('读妈妈认知', f'yukkuri status {sel("mom_blind")} raw')])

add('hatless_scorn', 'headwear', '失饰者被鄙视', '失饰鄙视',
    '没戴头饰的灵梦：智力普通的旁观者认不出它，生气说台词、走开；它哭、不安上涨。高智力的橙（200）认得出，不鄙视也不躲',
    '约 10–30 秒出现，之后每 20–30 秒重复', [
        yk('reimu', 0, 0, 'bare', yaw=-90, lead=True, accessory=False, label='无头饰', **pair_ready()),
        yk('marisa', 4, 0, 'a', yaw=90, **pair_ready()), yk('reimu', 4, 4, 'b', yaw=90, **pair_ready()),
        yk('chen', -4, 3, 'c', yaw=180, label='高智力', **pair_ready()), set_status('c', 'cognition.innate', 200)],
    env=DAY, note=NEW, vantage=(0, 10), reads=[('读失饰者', f'yukkuri status {sel("bare")} raw')])

add('hatless_monster', 'headwear', '失饰重伤者·怪物', '失饰怪物',
    '没头饰又重伤的灵梦：认不出它的个体惊恐说“怪物”并逃开；凶悍个体发现它已经低血量就停止欺凌，不继续追着推咬',
    '约 10–60 秒；每个观察者每 30–40 秒重复', [
        yk('reimu', 0, 0, 'victim', yaw=-90, lead=True, accessory=False, nutrition=38, energy=95),
        set_status('victim', 'health.health', 3),
        yk('marisa', 4, 0, 'timid', yaw=90, **pair_ready()),
        yk('reimu', 4, 4, 'bully', yaw=90, label='凶悍', disposition=HARSH, **pair_ready())],
    env=DAY, note=NEW, vantage=(0, 10), reads=[('读失饰者', f'yukkuri status {sel("victim")} raw')])

add('hatless_sanction', 'headwear', '失饰者被欺负', '失饰欺负',
    '两只凶悍成体认不出没头饰的健康灵梦：走近宣告、接触轻咬造成真实扣血和小伤口，再推搡或抢食，最后赶走。同一案只结算一次咬伤；低血量提前结束，高智力的橙不参与',
    '2 秒后开始，约 1–3 分钟', [
        yk('reimu', 0, 0, 'bare', yaw=-90, lead=True, accessory=False, label='无头饰', **pair_ready()),
        yk('marisa', 4, 0, 'b1', yaw=90, label='凶悍', disposition=HARSH, **pair_ready()),
        yk('reimu', 4, 4, 'b2', yaw=90, label='凶悍', disposition=HARSH, **pair_ready()),
        yk('chen', -4, 3, 'friend', yaw=180, label='高智力', **pair_ready()), set_status('friend', 'cognition.innate', 200)],
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
    reads=[('读颊囊（灵梦）', f'data get entity {sel("father")} YukkuriForageView'), ('读觅食（魔理沙）', f'data get entity {sel("mother")} YukkuriForageView')])

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
                  f'data modify entity {sel("custody_pet")} YukkuriLife.Personality.Players set value [{{Trust:30,Fear:0}}]',
                  f'data modify entity {sel("custody_pet")} YukkuriLife.Personality.Players[0].Player set from entity {sel("custody_owner")} UUID',
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

# codes are the rows of the table in docs/EXPERIMENTAL_MAP.md
TOPICS = {
    'agitation_relief': 'D2', 'adult_pairing': 'F6', 'widow_repair': 'F6', 'alice_hunt': 'R4', 'alice_interrupt': 'R4',
    'aftermath_growth': 'F7', 'aftermath_burden': 'F7', 'aftermath_depletion': 'F7',
    'den_family': 'W1', 'widow_family': 'W1', 'dig': 'W2', 'indep_near': 'W3', 'indep_far': 'W3', 'den_move': 'W4', 'forage': 'W5', 'graze': 'W6', 'pet_birth': 'W7',
    'sleep': 'D1', 'meet': 'D1', 'meal': 'D1', 'toilet': 'F5', 'hurt': 'F3', 'hurt_kin': 'F3',
    'birth_land_caught': 'F1', 'birth_land_reunion': 'F1', 'birth_land_missed': 'F1', 'feed': 'F2', 'first_toilet': 'F2', 'hop': 'F2', 'growth': 'F4',
    'family_distance': 'F8', 'family_search': 'F8',
    'rain_shelter': 'R1', 'thunder': 'R1', 'rain_open': 'R1', 'pred_watch': 'R2', 'pred_hunt': 'R2', 'pred_range': 'R2', 'pred_pets': 'R2', 'marks': 'R3', 'orange': 'R3',
    'sail': 'A1', 'hat_custody': 'A2', 'shore': 'A1', 'shore_child': 'A1', 'low': 'A1', 'high': 'A1', 'voyage': 'A1', 'braid': 'A4', 'afloat': 'A2', 'shake': 'A2',
    'cargo_empty': 'A1', 'cargo_little': 'A1', 'cargo_much': 'A1', 'cargo_full': 'A1', 'cargo_load': 'A1', 'cargo_rescue': 'A4', 'cargo_stow': 'A1',
    'birth_boat_caught': 'A3', 'birth_boat_missed': 'A3', 'shore_baby': 'A4', 'shore_babies': 'A4', 'rescue_baby': 'A4', 'self_rescue': 'A4', 'self_far': 'A4', 'self_tired': 'A4', 'shore_capsized': 'A4', 'capsize_caring': 'A4', 'capsize_selfish': 'A4', 'couple_boats': 'A5', 'family_skiffs': 'A5',
    'house_empty': 'H1', 'house_pair': 'H2', 'house_eggs': 'H3',
    'shop_build': 'M1', 'factory_labor': 'M2', 'factory_manju': 'M2', 'factory_bag': 'M2', 'walker': 'M3', 'harness_walk': 'M3', 'feed_wild': 'M3', 'oniisan': 'M4', 'oniisan_prey': 'M4', 'attack_witness': 'M4',
    'wild': 'M5', 'badge': 'M5', 'exam': 'M5', 'date': 'M5', 'transport': 'M5', 'custody_death': 'M5',
    'airborne': 'R8', 'house_health': 'R9',
    'wound_parts': 'R5', 'wound_leak': 'R5', 'core_exposed': 'R5', 'core_filling': 'R5', 'remains_rot': 'R6', 'carrion': 'R6', 'cannibal_hunt': 'R6',
    'last_words': 'R7', 'offering': 'R7', 'offer_dedicate': 'R7', 'sacrifice': 'R7', 'sacrifice_reluctant': 'R7',
    'hat_damage': 'C1', 'hat_shatter': 'C1', 'hatless_kin': 'C2', 'hatless_scorn': 'C2', 'hatless_monster': 'C2', 'hatless_sanction': 'C2',
    'myon_stick': 'S1', 'myon_brave': 'S1', 'rare_forms': 'S2', 'kotatsu_winter': 'S2', 'kotatsu_summer': 'S2',
    'cheek_forage': 'S3', 'alice_chores': 'S3', 'patchouli_vomit': 'S3', 'chen_speed': 'S3', 'marisa_sumo': 'S3',
    'colony_hunt': 'B2', 'colony_ration': 'B2', 'colony_judge': 'B2', 'colony_migrate': 'B2',
    'shop_stale': 'M6', 'factory_wear': 'M6', 'exam_fail': 'M6',
}
for _s in S:
    _s.asks = TOPICS.get(_s.id) or ('B1' if _s.id.startswith('settle_') else '')
    assert _s.asks, _s.id
