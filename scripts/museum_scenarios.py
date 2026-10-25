"""Scenarios of the playground: one question each, answered by real individuals under their ordinary AI.

A scenario is a list of commands run positioned at the scenario's anchor block (see museum_dsl). It only creates the
individuals and props and sets their real starting state (hunger, tiredness, life stage, kin, home, conception, a
wound, a hat boat afloat ...). After that nothing is scripted, fixed or invulnerable: the observer watches, speeds the
clock up, flies to the lead, or clears the field and starts again.

Fields hold the terrain (museum_fields.py); each scenario names its field and the spot of that field it stands around.
The ``TOPICS`` mapping at the end connects user concerns to scenarios; docs/TODO.md owns progress and completion criteria.
Scenario definitions and readouts live here; docs/DEVELOPMENT.md covers navigation and shared operations.
"""
import sys
import re

sys.dont_write_bytecode = True
from museum_dsl import (yk, human, item, block, large_nest, box, shed, home, sel, sel_all, say, uid, uuid_nbt, family, remains, _text)  # noqa: E402
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
        self.lines, self.give, self.env = surface_demo_lines(sid,lines), list(give), env or {}
        # settings that need the individuals to stand on the ground (a home, a conception, a boat) run two seconds after the summons
        deferred = [i for i, line in enumerate(self.lines) if line.startswith(LATE_COMMANDS)]
        self.late_lines = [self.lines[i] for i in deferred]
        self.lines = [line for i, line in enumerate(self.lines) if i not in deferred]
        f = BY_ID[field]
        self.anchor = f.spots[spot] if spot else f.anchor
        self.sub = sub                 # name of the plot cleared before this scenario (default: the whole field)
        self.vantage = vantage         # (dx, dz) from the anchor where the starting player is put (None: stay on the deck)
        self.reads = list(reads)       # (caption, command) readouts that belong to this scenario
        self.late = self.late_lines + surface_demo_lines(sid,late)   # commands that need the individuals to stand on the ground first: run two seconds after the start

    @property
    def tag(self):
        return PENDING if self.note.startswith(PENDING) else ('待验证' if self.note.startswith('待验证') else '')

    def body(self):
        """Commands of ``scn/<id>``: heading, the scenario itself, items for the player."""
        head = [say(f'【{self.title}】看点：{self.watch}', 'aqua'), say(f'预计：{self.wait}。没反应：{self.retry}。', 'yellow')]
        if self.note:
            head.append(say(f'注意：{self.note}', 'red'))
        return head + self.lines + [f'give @s {g}' for g in self.give]


def surface_demo_lines(sid, lines):
    """Preset skin demonstrations face the observer; explicit body-region comparisons retain their regions."""
    result = []
    comparisons = {'wound_parts', 'wound_materials', 'thermal_body', 'wound_sources', 'hatless_monster', 'hatless_boundary', 'core_exposed'}
    for line in lines:
        if not isinstance(line,str):
            result.append(line)
            continue
        match = re.match(r'(yukkuri status set .+?) (marks\.(?:dirt|scars)|health\.mold) ',line)
        if match:
            result.append(match[1] + ' marks.face true')
        if sid not in comparisons and ' health.wound_at ' in line:
            line = re.sub(r'( health.wound_at )(side|back|top):',r'\1front:',line)
        result.append(line)
    return result


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
    entry = next(p for p in BIOME_PLOTS if p[0] == plot)
    # A plains village reaches into the plots next door: its whole footprint becomes plains ground first.
    ground = BIOMES.plains_site(*BIOMES.spots[plot]) if plot == 'plains' else [f'fillbiome ~-22 56 ~-22 ~22 100 ~22 {entry[1]}']
    where = ('洞穴挖进北侧山坡：洞口朝南对着空地，洞顶是山坡本身的土石' if entry[6]
             else '该群系不自然生成聚落（群系适居裁定），此处用管理员命令放在平地上，只用来看寒冷／炎热季节生活，洞岸由土堆补起'
             if plot in ('snow', 'desert') else '平地上没有山坡，洞口两侧和洞顶由土堆补起（对照）')
    add(f'settle_{plot}', 'biomes', f'{title}聚落', title,
        f'{title}的{"村落" if kind == "village" else "小群"}：{where}；南边低处有一处浅浅的嗯嗯坑（裸土凹地，无厕所方块）。成员、住所、仓堆随群系特色而不同',
        wait, [*ground, f'yukkuri settlement spawn {kind} ~ ~ ~', LEAD_ANY], env=DAY, spot=plot,
        vantage=(0, 26), retry='先“清空本场地”再按一次',
        reads=[('读聚落场地与嗯嗯坑', 'yukkuri settlement site info'), ('读场地勘测', 'yukkuri settlement site check')])


for _plot, _title, _kind in (('forest', '森林', 'village'), ('plains', '平原', 'village'), ('taiga', '针叶林', 'band'), ('cherry', '樱花林', 'band'),
                             ('hills', '风袭丘陵', 'band'), ('savanna', '热带草原', 'band'), ('snow', '雪原', 'band'), ('desert', '沙漠', 'band')):
    settlement(_plot, _title, _kind, '数分钟（可倍速）')

# The waste pit in use: a hill-foot band whose adults all need to go soon after they have moved in.
_PIT_ADULTS = '@e[type=#mcylab:yukkuri,distance=..30]'
add('settle_pit', 'biomes', '聚落嗯嗯坑', '嗯嗯坑',
    '森林山脚的小群入住约 10 秒后，全员便意升到 80：看它们各自从洞里走到南边低处的嗯嗯坑，站进凹地里排泄，排泄物真实落在坑里；'
    '不在洞口或空地上排。读数看坑的位置；日志 [needs] toilet pit trip start → relieved source=pit',
    '约 1–2 分钟', ['fillbiome ~-22 56 ~-22 ~22 100 ~22 minecraft:forest', 'yukkuri settlement spawn band ~ ~ ~', LEAD_ANY],
    env=DAY, spot='forest', vantage=(0, 18), retry='先“清空本场地”再按一次', note='待验证：新场景，尚未在游戏里实测。',
    late=[Retry(f'execute as {_PIT_ADULTS} run yukkuri status set @s needs.bladder 80', _PIT_ADULTS, tries=1, first=200)],
    reads=[('读聚落场地与嗯嗯坑', 'yukkuri settlement site info'), ('读一只成员的便意', 'yukkuri status @e[tag=scn_lead,limit=1,distance=..40] raw')])

# ====================================================================================================== daily life meadow
add('sleep', 'meadow', '回窝睡觉', '困了回窝', '灵梦困了，自己走回有顶的窝里睡觉', '约 1 分钟', [
    *shed(7, 0), yk('reimu', 0, 0, 'a', energy=18, yaw=-90, lead=True), home('a', 7, 0)], vantage=None)

add('fatigue', 'weather', '困倦、原地打盹与惊醒', '困倦惊醒',
    '前排胆量10／90、精力0，原地睡约12／28秒；后排精力20，缓慢并平均每30秒打盹1–3秒。用天气按钮下雨或手动轻击正在睡的个体，应立即醒来且困值最多50。睡着时捕食种靠近不会被感知，实际攻击仍唤醒',
    '先看两只睡眠时长；再观察后排约1–2分钟，可清场后试雨或轻击', [
        yk('reimu', -5, 0, 'timid', label='胆小·困值100', energy=0, boldness=10, lead=True),
        yk('reimu', 5, 0, 'bold', label='胆大·困值100', energy=0, boldness=90),
        yk('chen', 0, 5, 'drowsy', label='困值80·短打盹', energy=20)],
    env=DAY, vantage=(0, 14), reads=[(label, f'yukkuri status {sel(name)} raw') for label,name in
        (('读胆小睡眠','timid'),('读胆大睡眠','bold'),('读短打盹','drowsy'))])

add('fatigue_predator', 'weather', '睡眠中捕食种接近', '睡眠与捕食',
    '先传送到睡者附近观察自然入睡；12秒后20格外生成饥饿蕾米莉亚，按正常捕食逻辑选目标和接近。睡眠期间不因接近而惊醒，真实咬伤应立即唤醒，困值高于50时退到50；没有强制指定猎物或Goal',
    '看睡眠→真实接近→咬伤与惊醒；捕食未发生时读捕食者拒绝原因', [
        yk('reimu', 0, 0, 'sleeper', label='困值100·睡者', energy=0, boldness=90, lead=True)],
    env=DAY, vantage=(8, 14),
    late=[Retry(yk('remilia',0,20,'hunter',label='饥饿捕食种',nutrition=10,energy=95),sel('sleeper'),tries=1,first=240)],
    reads=[('读睡者',f'yukkuri status {sel("sleeper")} raw'),('读捕食者',f'yukkuri status {sel("hunter")} raw')])

add('nutrition_age', 'body', '成长营养与饥饿虚弱', '营养与虚弱',
    '前排赤／子／亚成／成体均营养50，无食物；同一代谢周期扣0.12／0.36／1／2。后排同种同体型灵梦对照：左营养0获得虚弱I与缓慢II（移速属性减30%），右营养100。左每60秒掉1血但不受击惊跳；补食后效果自然到期。读移速属性、原版效果与需求倍率；不要拿天生较快的橙和灵梦比较',
    '12秒比较代谢；至少60秒看饥饿掉血', [
        *[yk('reimu', -9+6*i, 0, 'age_'+stage, label=label, stage=stage, nutrition=50, energy=95, lead=i==0)
          for i,(stage,label) in enumerate((('baby','赤油'),('child','子油'),('teen','亚成体'),('adult','成体')))],
        yk('reimu', -5, 7, 'starving', label='营养0·虚弱缓慢', nutrition=0, energy=95),
        yk('reimu', 5, 7, 'fed', label='营养100·同种对照', nutrition=100, energy=95)],
    env=DAY, give=['minecraft:apple 4'], vantage=(0, 16),
    reads=[(f'读{stage}',f'yukkuri status {sel("age_"+stage)} raw') for stage in ('baby','child','teen','adult')]
        + [('读饥饿者',f'yukkuri status {sel("starving")} raw'),('读同种对照',f'yukkuri status {sel("fed")} raw'),('读原版效果',f'data get entity {sel("starving")} active_effects')])

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
    '你的宠物母油（伴侣在旁）要生了：真实双亲大窝在棚下；母油临产自行出窝与魔理沙父油接生，之后观察赤油安置与同窝数值', '约 1–2 分钟临产，之后看照护', [
        *shed(8, 0, half=3),
        *large_nest(8, 0, 'mcyukkuri:large_yukkuri_nest'),
        yk('reimu', 8.5, .5, 'mother', yaw=-90, lead=True, grade=1, **pair_ready()), *own('mother'),
        yk('marisa', 6, 2, 'father', yaw=-90, **pair_ready()),
        f'yukkuri scenario bond {sel("mother")} {sel("father")}',
        home('mother', 8, 0),
        f'yukkuri scenario birth {sel("mother")} {sel("father")} natural'], spot='pet_birth', vantage=(4, 12))

add('feed', 'family', '嚼糜喂食', '嚼糜喂食',
    '三家饥饿赤油：前排孩子在窝、后排亲油在窝、最右低友善对照。左侧16格外只有两份苹果，另有一株草；看接受后找粮、真实采食、嚼糜和窝内外交接。低友善仍可能接受；无粮须显示搜索结束原因，可手动补苹果再观察',
    '约 1–3 分钟；读双方与实际粮源', [
    block(3, 0, 0, 'mcyukkuri:yukkuri_nest'),
    yk('chen', 0, 0, 'mom', yaw=-90, friendliness=95, **pair_ready()),
    yk('reimu', 3, 0, 'baby', dy=.2, stage='baby', nutrition=25, yaw=90, lead=True),
    f'yukkuri scenario kin {sel("mom")} {sel("baby")} false',
    block(0, 0, 7, 'mcyukkuri:yukkuri_nest'),
    yk('chen', 0, 7, 'mom_in', dy=.2, friendliness=95, **pair_ready()),
    yk('reimu', 3, 7, 'baby_out', stage='baby', nutrition=25),
    f'yukkuri scenario kin {sel("mom_in")} {sel("baby_out")} false',
    yk('chen', -5, 12, 'reluctant', friendliness=0, self_control=0, **pair_ready()),
    yk('reimu', -2, 12, 'requester', stage='baby', nutrition=25),
    f'yukkuri scenario kin {sel("reluctant")} {sel("requester")} false',
    item('minecraft:apple', 2, -16, 0), block(-10, 0, 7, 'minecraft:short_grass')],
    spot='feed', vantage=(0, 17), give=['minecraft:apple 4'],
    reads=[(label, f'yukkuri status {sel(name)} raw') for label,name in
           [('读窝外亲油','mom'),('读窝内赤油','baby'),('读窝内亲油','mom_in'),('读窝外赤油','baby_out'),
            ('读低友善亲油','reluctant'),('读低友善子代','requester')]])

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

# Leaf swaddle (docs/CHILDHOOD_LIFECYCLE.md#树叶襁褓与幼体排便照护): leaf bushes are the real material; a worn wrap is a starting state only.
LEAF_BUSH = 'minecraft:oak_leaves[persistent=true]'


def leaf_wrap(soil=0, open_=False):
    """A wrap already on the child: one real oak leaf inside, ``soil`` stool loads held."""
    return (f'YukkuriLeafSwaddle:{{Worn:1b,Open:{1 if open_ else 0}b,Soil:{soil},'
            'Material:[{id:"minecraft:oak_leaves",count:1}]}')


def leaf_reads(*names):
    return [(f'读{label}襁褓与需要', f'yukkuri status {sel(name)} raw') for label, name in names]


add('leaf_swaddle', 'family', '树叶襁褓·制作、排便照护', '树叶襁褓',
    '友善、聪明的亲油先照护两只已裹着树叶襁褓的子油：便意 55 的一只被解开（这片叶摊在身下）、舔舐后排出一份便便、舔净、重新包好；'
    '已积 2 份的一只解开时两份便便落地、脏叶作废，舔净后要另采叶子。随后亲油从左侧叶丛衔下一片叶（叶块真实消失）、原地整理，'
    '走到赤油和脏襁褓子油身后包上：叶柄在身后油菊处，叶片兜过底部包到前下方排泄口。亚成油不包；右侧低友善亲油不制作。丢苹果喂子油可再次积起便意',
    '约 2–4 分钟（可倍速）', [
        yk('reimu', 0, 0, 'mom', yaw=-90, friendliness=85, intelligence=75, **pair_ready()),
        yk('reimu', 2, -2, 'baby', stage='baby', yaw=90, nutrition=90, hygiene=70),
        yk('reimu', 2, 1, 'kid', stage='child', yaw=90, lead=True, nutrition=80, bladder=55, hygiene=60, extra=leaf_wrap()),
        yk('reimu', 2, 3, 'kid_soiled', stage='child', yaw=90, nutrition=80, bladder=20, hygiene=45, extra=leaf_wrap(soil=2)),
        yk('reimu', 2, 5, 'teen', stage='teen', yaw=90, nutrition=80, bladder=10, hygiene=70),
        *[f'yukkuri scenario kin {sel("mom")} {sel(k)} false' for k in ('baby', 'kid', 'kid_soiled', 'teen')],
        *[block(-4, 0, dz, LEAF_BUSH) for dz in (1, 2, 3, 4)], block(-3, 0, 4, LEAF_BUSH),
        yk('marisa', 10, 0, 'cold', yaw=-90, friendliness=20, intelligence=30, **pair_ready()),
        yk('reimu', 12, 0, 'cold_baby', stage='baby', yaw=90, nutrition=90, hygiene=70),
        f'yukkuri scenario kin {sel("cold")} {sel("cold_baby")} false',
        block(9, 0, 3, LEAF_BUSH), block(10, 0, 3, LEAF_BUSH)],
    give=['minecraft:apple 4'], env=DAY, spot='leaf_swaddle', vantage=(5, 10),
    reads=leaf_reads(('亲油', 'mom'), ('赤油', 'baby'), ('便意子油', 'kid'), ('积便子油', 'kid_soiled'), ('亚成油', 'teen'), ('低友善亲油', 'cold')))

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
    '无巢的一对亲子相隔 30 格：观察孩子寻亲、亲油主动接近，重新靠近后恢复自由活动；不会传送或生成替代亲人',
    '约 30–60 秒', [
        yk('reimu', -15, 0, 'search_parent', uuid=_fs['parent'], label='寻子亲油', lead=True, **pair_ready()),
        yk('reimu', 15, 0, 'search_child', uuid=_fs['child'], stage='child', label='走散子油', boldness=40,
           kin=f'Mother:{uuid_nbt(_fs["parent"])}', **pair_ready())],
    env=DAY, spot='hurt_kin', vantage=(0, 12),
    reads=[('读寻子亲油', f'yukkuri status {sel("search_parent")} raw'), ('读寻亲子油', f'yukkuri status {sel("search_child")} raw')])

# Real initial conditions for the retained birth/growth rules; no goal or successful outcome is assigned.
add('birth_nest_safe', 'family', '大窝自然生产', '大窝生产',
    '灵梦父母不能用帽接生；母油回到野生大草窝生产，只有赤油实际落到垫面才免出生摔伤。读产程、出生落点和伤势',
    '约 2 分钟', [*shed(0, 0, half=4), *large_nest(0, 0, 'mcyukkuri:large_wild_nest'),
    yk('reimu', .5, 2, 'mother', yaw=180, lead=True, **pair_ready()),
    yk('reimu', 3, 2, 'father', **pair_ready()),
    f'yukkuri scenario bond {sel("mother")} {sel("father")}', home('mother', 0, 0),
    f'yukkuri scenario birth {sel("mother")} {sel("father")} natural'], spot='pet_birth',
    reads=[('读自然产程', f'yukkuri status {sel("mother")} raw'), ('读所有赤油', f'yukkuri status @e[tag=scn,distance=..48] raw')])

_nest_ids={name:uid('family_nests',name) for name in ['mother','father','b0','b1','b2','b3']}
add('family_nests', 'family', '窝容量与成长分窝', '大小窝同居',
    '父母和四赤油认领同一大窝；旁边普通窝提供长成子油后的真实空位。读床位、亲密与不安；物品栏可摆放两种大窝。成长用本区已有时间倍率，不直接写成功状态',
    '先看认窝，再观察成长', [*shed(0, 0, half=5), *large_nest(0, 0, 'mcyukkuri:large_yukkuri_nest'),
    block(-3, 0, 0, 'mcyukkuri:yukkuri_nest'), block(3, 0, 0, 'mcyukkuri:yukkuri_nest'),
    yk('reimu', -1, -.5, 'mother', uuid=_nest_ids['mother'], energy=35, rest_wait=0, lead=True),
    yk('marisa', 2.2, .1, 'father', uuid=_nest_ids['father'], energy=35, rest_wait=0),
    f'yukkuri scenario bond {sel("mother")} {sel("father")}',
    *[yk('reimu', x, z, f'b{i}', stage='baby', uuid=_nest_ids[f'b{i}'], energy=35, rest_wait=0,
         kin=f'Mother:{uuid_nbt(_nest_ids["mother"])},Father:{uuid_nbt(_nest_ids["father"])}') for i,(x,z) in enumerate([(-.9,2.1),(.2,2.6),(1.2,2),(2.2,2.8)])],
    *[home(name, 0, 0) for name in _nest_ids]], spot='pet_birth',
    give=['mcyukkuri:large_yukkuri_nest 2','mcyukkuri:large_wild_nest 2','mcyukkuri:yukkuri_nest 6'],
    reads=[('读同居与床位', f'yukkuri status @e[tag=scn,distance=..48] raw')])

_lost_m=uid('family_search_blocked','mother');_lost_c=uid('family_search_blocked','child')
add('family_search_blocked', 'family', '走失无法抵达', '走失与放弃',
    '母子相距30格，子油被真实玻璃围住；观察搜寻受阻、母油计时与单向放弃。低胆量孩子不安可越过非油症阈值；可手动拆玻璃对照重逢，不能把放弃误认成寻回',
    '母油约2分钟以上；孩子不安需更久', [
    box(13,0,-2,17,3,2,'minecraft:glass','hollow'),
    yk('reimu',-15,0,'lost_mother',uuid=_lost_m,friendliness=0,self_control=0,boldness=0,lead=True),
    yk('reimu',15,0,'lost_child',uuid=_lost_c,stage='child',boldness=0,kin=f'Mother:{uuid_nbt(_lost_m)}')],
    spot='hurt_kin',reads=[('读搜索时长与断亲',f'yukkuri status {sel("lost_mother")} raw'),('读子油不安',f'yukkuri status {sel("lost_child")} raw')])

add('infant_starvation', 'family', '赤油取食边界', '赤油极饿取食',
    '三只赤油：普通饥饿不能自食；极饿胆大者自行走出3格范围取食；极饿胆小低智者拒绝冒险。每只前方8格有一株真实草，可读决策与营养变化',
    '约 1–3 分钟', [
    *[yk('reimu',-8+i*8,0,f'infant{i}',stage='baby',nutrition=25 if i==0 else 5,
         boldness=5 if i==2 else 95,intelligence=5 if i==2 else 95,self_control=95 if i==2 else 20,
         childhood=CHILDHOOD_CRAWLING_READY,lead=i==0) for i in range(3)],
    *[block(-8+i*8,0,8,'minecraft:short_grass') for i in range(3)]],spot='feed',
    reads=[('读取食决定',f'yukkuri status @e[tag=scn,distance=..48] raw')])

# ====================================================================================================== weather and predators
add('rain_shelter', 'weather', '下雨躲雨', '下雨躲雨', '石顶在旧16格搜索范围外：观察32格搜索与实际抵达；进入干燥处后释放避雨Goal，恢复普通活动，读behavior.weather和mood.unease',
    '约 30 秒', [
        yk('reimu', 0, 0, 'a', yaw=-90, lead=True), yk('marisa', 2, 3, 'b', aquatic=True, yaw=-90), yk('chen', -2, 2, 'c', yaw=-90)],
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
    '饥饿芙兰与灵梦相距 48 格：观察远处发现、起飞追击、猎物逃跑和接触咬击；一口咽完仍饿时继续觅食',
    '约 1–2 分钟', [
        yk('reimu', -24, 0, 'range_prey', yaw=-90, **pair_ready()),
        yk('flandre', 24, 0, 'range_pred', yaw=90, lead=True, nutrition=100, energy=100),
        set_status('range_pred', 'needs.nutrition', 10)],
    env=DAY, spot='pred', vantage=(0, 18),
    reads=[('读远猎', f'yukkuri status {sel("range_pred")} raw'), ('读远猎猎物', f'yukkuri status {sel("range_prey")} raw')])

add('forage_empty','weather','无粮主动搜索','无粮寻食',
    '开始不放食物：先切到空手，饥饿灵梦立即主动搜索，搜不到也不定时退回idle；手持苹果时会向玩家寻食，再丢下观察接近、吞咽与营养回升。真实受阻仍会饿，不指定Goal',
    '约30秒后丢食物',[yk('reimu',0,0,'seeker',nutrition=18,energy=90,lead=True)],
    env=DAY,spot='pred',give=['minecraft:apple 3'],reads=[('读主动寻粮',f'yukkuri status {sel("seeker")} raw')])

add('shared_body','weather','普通、水生与捕食种身体对照','身体结构对照',
    '普通魔理沙、水生魔理沙、蕾米莉亚、弗兰共用身体结构。绕到背面看油菊；按钮对照佩妮佩妮和马姆马姆伸缩，再看通用行走。伸缩仍互斥，默认收回；动作片段只验素材，不代表行为成功',
    '手动切换观察',[
        yk('marisa',-9,0,'ordinary',nutrition=100,also=('sharedbody',),lead=True),
        yk('marisa',-3,0,'aquatic',aquatic=True,nutrition=100,also=('sharedbody',)),
        yk('remilia',3,0,'remilia',nutrition=100,also=('sharedbody',)),
        yk('flandre',9,0,'flandre',nutrition=100,also=('sharedbody',))],env=DAY,vantage=(0,12),
    reads=[(label,f'yukkuri action @e[tag=scn_sharedbody] {action}') for label,action in
           [('伸出佩妮佩妮','anatomy_peni_extend'),('收回佩妮佩妮','anatomy_peni_retract'),
            ('伸出马姆马姆','anatomy_mamu_extend'),('收回马姆马姆','anatomy_mamu_retract'),
            ('行走片段','waddle'),('结束片段','idle')]])

add('rain_damage','weather','满湿伤害与耐雨','满湿雨伤',
    '普通种湿度97、水生种98.5起步；下雨每秒分别+3与+1.5，满100才伤害。无屋顶，观察搜索、生命、湿度，伤害不弹起不横移；可手动晴天观察干燥后停止雨伤',
    '约30秒',[yk('reimu',-3,0,'wet_ordinary',nutrition=90,energy=90,lead=True),
    yk('marisa',3,0,'wet_aquatic',aquatic=True,nutrition=90,energy=90),
    set_status('wet_ordinary','needs.wetness',97),set_status('wet_aquatic','needs.wetness',98.5)],
    env={'time':'noon','weather':'rain'},spot='open',
    reads=[('读普通雨伤',f'yukkuri status {sel("wet_ordinary")} raw'),('读水生雨伤',f'yukkuri status {sel("wet_aquatic")} raw')])

# Sheltered actors are a legitimate start for testing activity after the run has ended.
_rain_life = []
for _name, _x, _nutrition in (('ordinary', -12, 90), ('hungry', 12, 10)):
    _rain_life += [box(_x-3, 3, -3, _x+3, 3, 3, 'minecraft:stone_bricks'),
                   yk('reimu', _x, 0, _name, nutrition=_nutrition, energy=95, lead=_name=='ordinary'),
                   item('minecraft:apple', 2, _x+7, 0, _name+'_food')]
add('rain_life', 'weather', '雨天干燥区活动', '干燥区活动',
    '左右都有真实屋顶，屋外各两枚苹果：左营养90保持干燥区活动，右营养10可冒雨觅食；同场可手动切雷暴对照不安原因，不指定Goal',
    '约1–2分钟', _rain_life, env={'time':'noon','weather':'rain'}, spot='dry_life', vantage=(0,14),
    reads=[('读普通雨天活动', f'yukkuri status {sel("ordinary")} raw'), ('读饿油冒雨', f'yukkuri status {sel("hungry")} raw')])

_roaming = [*shed(-16, 0, half=4), yk('marisa', -16, 1, 'roam_baby', stage='baby', lead=True), home('roam_baby', -16, 0)]
for _i in range(4):
    _roaming.append(yk('marisa', _i*4-4, 6, f'explorer{_i}', stage='child', boldness=85, intelligence=70, nutrition=95, energy=95))
_roaming += [yk('marisa', 16, 0, 'roam_cautious', stage='child', boldness=30, intelligence=70),
             yk('reimu', 16, 6, 'roam_control', stage='child', boldness=85, intelligence=70)]
add('marisa_explore', 'weather', '魔理沙探险队', '子油远行',
    '四只胆量85智力70子油魔理沙，各自每60秒至多一次5%抽签；普通半径27、探险54格，真实走到远点再返回。低胆量魔理沙与灵梦不触发；左侧赤油有真实巢，只在巢附近活动。读behavior.roaming，不保证单轮触发，缺台词动作最后补',
    '数分钟，可用场内调速；饥饿与危险照常中断', _roaming, env=DAY, spot='roaming', vantage=(0,18),
    reads=[(f'读探险子油{i+1}', f'yukkuri status {sel(f"explorer{i}")} raw') for i in range(4)]
          + [('读赤油边界', f'yukkuri status {sel("roam_baby")} raw'), ('读低胆量对照', f'yukkuri status {sel("roam_cautious")} raw')])

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
pond('hat_custody_burn', '暂放帽船·烧毁边界', '实船烧毁',
    '亲油暂放的真实帽船受到一次火焰伤害，走正常烧毁结算；帽主失去原帽、赤油落水、库存只散落一次，不生成可回收的新帽',
    '约 10 秒', [captain(), yk('marisa', -2, 1, 'kid', stage='baby', aquatic=True, yaw=-90, **pair_ready()), boat('unattended', 'a', sel('kid'))],
    late=[f'damage {_cargo_boat} 6 minecraft:in_fire'],
    note='这次外力只设置真实起因，不固定后续状态。重复试前先清场；不可把普通卸载或清场当作烧毁。',
    reads=[('读烧毁后的帽主', f'yukkuri status {sel("a")} raw'), ('读落水赤油', f'yukkuri status {sel("kid")} raw'),
           ('读剩余实船', f'data get entity {_cargo_boat}')])
for _cargo_mode, _cargo_title, _cargo_hint, _cargo_watch in (
        ('cargo_empty', '帽内粮堆·空仓', '空仓', '亲油帽内起初无粮；持食物右击船体逐份存入至 11 份，继续存入会提示满载且不晃船'),
        ('cargo_full', '帽内粮堆·满载', '11 粮＋1 桨', '11 份粮食和一根桨占满 12 份容量；继续存粮只提示并留在手中，不晃船。保存重进核对同船与真实库存')):
    pond(_cargo_mode, _cargo_title, _cargo_hint, _cargo_watch, '随意',
         [captain(), boat(_cargo_mode, 'a', sel('a'))],
         give=['minecraft:apple 8', 'minecraft:carrot 8', 'minecraft:cookie 1', 'minecraft:bread 1', 'minecraft:melon_slice 1'],
         note='“读船粮”可看真实库存；读档检查请重进当前世界，不要重新生成场景。先各存 1 份四种食物，再用西瓜片验证种类已满提示；清场重开后测满载。食物右击船体只入储粮，空手或非食物右击才晃船。',
         reads=[('读船主与容量', f'yukkuri status {sel("a")} raw'), ('读船粮与实物桨', f'data get entity {_cargo_boat} Cargo')])
pond('cargo_load', '孩子在帽内·再装粮', '先载幼体', '赤油占 2 份、桨占 1 份；持食物右击船体最多再装 9 份，继续存入只提示不晃船，幼体座位不被粮堆覆盖', '随意', [
    captain(), yk('marisa', -2, 1, 'kid', stage='baby', aquatic=True, yaw=-90, **pair_ready()), boat('cargo_load', 'a', sel('kid'))],
    give=['minecraft:apple 8', 'minecraft:carrot 8', 'minecraft:cookie 1', 'minecraft:bread 1', 'minecraft:melon_slice 1'],
    note='对比先装满粮再接孩子的场景；潜行右击一次最多存入 8 份。各存 1 份四种食物后，西瓜片只提示种类已满；继续装满也只提示，不晃动。',
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
pond('high', '四幼体高载荷', '四幼体', '四只赤油占 8 份，加三份粮和一根桨共 12 份，观察满载吃水与座位；再存食物只提示不晃船', '约 1–3 分钟', [
    captain(), *[yk('marisa', -2 - i % 2, i // 2 * 2 - 1, f'k{i}', stage='baby', aquatic=True, also=('young',), **pair_ready()) for i in range(4)],
    boat('high', 'a', sel_all('young'))], give=['minecraft:apple 2'],
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
    # Five is the largest ordinary live litter; two spare nests beside the mother's (centres two blocks apart, so the bolsters do not
    # overlap) cover extraCells(5) == 2 while the centre remains the mother's home.
    block(-6, 0, -3, 'mcyukkuri:yukkuri_nest'), block(-2, 0, -3, 'mcyukkuri:yukkuri_nest'),
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

SWADDLE_STYLES = ('frill', 'letter', 'tiger', 'outing', 'fleece', 'gingham', 'floral')
add('swaddle_styles', 'town', '布襁褓八款', '布襁褓款式',
    '自家赤油、子油（灵梦、魔理沙、爱丽丝）各一只：用不同款式的襁褓布右键给它们包上，护理菜单“解开”取回同款；'
    '看八款的外观（奶油针织交叠襟、荷叶边蝴蝶结、背后首字母、虎纹、外出胶底挎包、厚绒绒球、格纹、碎花）与图标是否一致。'
    '另给两块原色襁褓布和八种染料，在工作台用“襁褓布＋染料”合成改款', '随意', [
        yk('reimu', 0, 0, 'wrap_baby', stage='baby', lead=True, nutrition=90, energy=100, hygiene=100),
        yk('marisa', 2, 0, 'wrap_kid', stage='child', nutrition=90, energy=100, hygiene=100),
        yk('alice', 4, 0, 'wrap_alice', stage='child', nutrition=90, energy=100, hygiene=100),
        *own('wrap_baby'), *own('wrap_kid'), *own('wrap_alice')],
    give=['mcyukkuri:swaddle_cloth 2', 'minecraft:crafting_table 1',
          *[f'mcyukkuri:swaddle_cloth[minecraft:custom_data={{SwaddleStyle:"{style}"}}] 1' for style in SWADDLE_STYLES],
          *[f'minecraft:{dye}_dye 1' for dye in ('white', 'pink', 'red', 'orange', 'light_blue', 'brown', 'blue', 'green')]],
    reads=[(f"读{label}襁褓", f"data get entity {sel(name)} YukkuriPet.Care") for label, name in (("赤油", "wrap_baby"), ("子油", "wrap_kid"), ("爱丽丝", "wrap_alice"))], vantage=(2, 8))

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


add('leaf_swaddle_neglect', 'meadow', '树叶襁褓·无人照护积便', '襁褓积便',
    '两只裹着树叶襁褓的子油没有亲油：便意 95 的一只马上把便便排进襁褓（soil 加 1，卫生、不安恶化，襁褓下沿染色）；'
    '已积 3 份的一只再满就从肛门溢到地上。之后每 30 秒按积便扣卫生、加不安；丢苹果喂食可让便意继续积累',
    '立即；之后每 30 秒一次变化', [
        yk('reimu', 0, 0, 'kid', stage='child', yaw=180, lead=True, nutrition=80, bladder=95, hygiene=80, extra=leaf_wrap()),
        yk('marisa', 3, 0, 'kid_full', stage='child', yaw=180, nutrition=80, bladder=92, hygiene=50, extra=leaf_wrap(soil=3))],
    give=['minecraft:apple 4'], env=DAY, vantage=(1, 8),
    reads=leaf_reads(('刚积便子油', 'kid'), ('积满子油', 'kid_full')))

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
            *shed(3, 2), block(1, 0, 2, 'mcyukkuri:yukkuri_nest'), block(5, 0, 2, 'mcyukkuri:yukkuri_nest'),
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
add('body_mold', 'body', '油霉阶段与去霉', '油霉阶段',
    '四只灵梦分别为油霉 1／3／6／8 级，对应四档灰绿菌落；先绕行看侧背面，再用去霉药核对真实阶段回退及原位菌落缩小。终末个体会自然进入濒死，完整皮面不应凭中枢损伤冒出创洞',
    '立即看外观；终末约 20 秒后濒死', [
        *[yk('reimu', -6 + 4 * i, 0, f'mold{i}', lead=i == 0, label=f'油霉{level}', **pair_ready())
          for i, level in enumerate((1, 3, 6, 8))],
        *[set_status(f'mold{i}', 'health.mold', level) for i, level in enumerate((1, 3, 6, 8))]],
    give=['mcyukkuri:mold_remover 8'], env=DAY, vantage=(0, 10),
    reads=[(f'读油霉{level}', f'yukkuri status {sel(f"mold{i}")} raw') for i, level in enumerate((1, 3, 6, 8))])

add('airborne', 'body', '腾空与小跳', '腾空与小跳',
    '左侧灵梦对照高抛与小跳，右侧蕾米莉亚和弗兰同样高抛；石墙隔开视线，避免捕食威胁打断普通组。普通高抛只感叹一次，小跳与两种原生会飞捕食种不说飞起感叹；落地后仍按普通规则活动',
    '生成后约 2–6 秒', [
        box(-10, -1, -4, 12, -1, 4, 'minecraft:hay_block'),
        box(1,0,-28,1,5,28,'minecraft:stone'),
        yk('reimu', -7, 0, 'high', label='普通·高抛', lead=True, **pair_ready()),
        yk('reimu', -3, 0, 'low', label='普通·小跳', **pair_ready()),
        yk('remilia', 5, 0, 'native_remilia', label='原生飞行·蕾米莉亚', **pair_ready()),
        yk('flandre', 10, 0, 'native_flandre', label='原生飞行·弗兰', **pair_ready())],
    late=[f'data modify entity {sel("high")} Motion set value [0.0d,0.9d,0.0d]',
          f'data modify entity {sel("low")} Motion set value [0.0d,0.25d,0.0d]',
          *[f'data modify entity {sel(name)} Motion set value [0.0d,0.9d,0.0d]' for name in ('native_remilia', 'native_flandre')]],
    env=DAY, vantage=(0, 14), note=NEW,
    reads=[(label, f'yukkuri status {sel(name)} raw') for name,label in
           (('high','读普通高抛'),('low','读普通小跳'),('native_remilia','读蕾米莉亚'),('native_flandre','读弗兰'))])

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

_parts = (('头顶','top'),('正面','front'),('背面','back'),('侧面','side'),('底面','bottom'))
add('wound_parts', 'body', '伤口部位对照', '伤口部位',
    '五只灵梦分别预置头顶、正面、背面、侧面、底面的二级细刺伤，只比较锚点和动作贴合；这不是实际受击证明。真实仙人掌须接触方块，另用真实伤因场景；读wound_sites与wound_anchors',
    '立即绕行；可用橙汁观察原位闭合留疤', [
        *[yk('reimu',-8+4*i,0,f'v{i}',yaw=0,lead=i==1,label=label,**pair_ready()) for i,(label,_) in enumerate(_parts)],
        *[set_status(f'v{i}','marks.scars',0) for i in range(5)],
        *[set_status(f'v{i}','health.wound_at',f'{part}:scratch:2:thorn') for i,(_,part) in enumerate(_parts)]],
    note=NEW, vantage=(0,10,180), give=['mcyukkuri:orange_juice 6'],
    reads=[(f'读{label}伤口',f'yukkuri status {sel(f"v{i}")} raw') for i,(label,_) in enumerate(_parts)])

_wound_shapes = (('scratch','scratch','擦伤'),('tear','tear','刀割'),('dent','dent','钝击'),
                 ('puncture','tear','窄刺'),('thorn','scratch','细刺'),('impale','tear','尖锥'),
                 ('bite','tear','啃咬'),('blast','tear','爆裂'))
_wound_materials=[]
for i,(shape,kind,label) in enumerate(_wound_shapes):
    name=f'shape{i}'
    _wound_materials += [yk('reimu',-14+4*i,0,name,label=label+'1/2/3',nutrition=20,energy=95,lead=i==0,also=('wound_shapes',)),
        set_status(name,'marks.scars',0),
        *[set_status(name,'health.wound_at',f'{part}:{kind}:{grade}:{shape}')
          for part,grade in (('front',1),('side',2),('back',3))]]
for i,(species,extra,label) in enumerate((('reimu',{'congenital':'BEAN_BODY'},'豆形'),
        ('reimu',{'congenital':'SPARSE_HAIR'},'先天稀发'),('reimu',{'form':'WASA_REIMU'},'多毛后发'),
        ('marisa',{'aquatic':True},'水生与大帽'))):
    name=f'hair{i}'
    _wound_materials += [yk(species,-9+6*i,8,name,label=label,nutrition=20,energy=95,**extra),
        set_status(name,'marks.scars',0),set_status(name,'health.wound_at','top:dent:2:dent'),
        set_status(name,'health.wound_at','back:tear:3:bite')]
add('wound_materials','body','八伤型与发面适配','伤型与发面',
    '前排八种伤型每只正面一级、侧面二级、背面三级；后排豆形、先天稀发、多毛及水生大帽对照。绕行看皮面、发面和断发边，移动时看蒙皮；用橙汁闭伤后看同锚点留疤，再用面粉糊。三级裂口继续漏血，及时治疗。本场是外观初态，实际来源另看真实伤因场景',
    '先看后背三级，再看侧面与正面；可清场重开',_wound_materials,env=DAY,vantage=(0,15),
    give=['mcyukkuri:orange_juice 16','mcyukkuri:flour_paste 16'],
    reads=[('读八类伤型与锚点',f'execute as {sel_all("wound_shapes")} run yukkuri status @s raw')]
        + [(f'读{label}',f'yukkuri status {sel(f"hair{i}")} raw') for i,label in enumerate(('豆形','先天稀发','多毛后发','水生大帽'))])
_thermal=[]
for i,(scope,grade) in enumerate((('sole',1),('sole',2),('sole',3),('whole',1),('whole',2),('whole',3))):
    name=f'thermal{i}'
    _thermal += [yk('reimu',-15+6*i,0,name,label=('足部' if scope=='sole' else '全身')+str(grade),nutrition=20,energy=95,lead=i==0),
        set_status(name,'marks.scars',0),set_status(name,'health.wound_at',f'{"bottom" if scope=="sole" else "front"}:scorch:{grade}:{scope}')]
for i,scope in enumerate(('sole','whole')):
    name=f'house_heat{i}'
    _thermal += [yk('house_reimu',-6+6*i,8,name,label='家油'+('足部' if i==0 else '全身'),nutrition=20,energy=95),
        set_status(name,'health.wound_at',f'{"bottom" if scope=="sole" else "front"}:scorch:1:{scope}')]
_thermal += [yk('reimu',6,8,'thermal_control',label='健康对照',nutrition=20,energy=95)]
add('thermal_body','body','足部与全身烧伤','烧伤范围',
    '前排足部1/2/3级与全身1/2/3级，后排家油两种范围及健康对照。家油伤级初态为1但外观直接三级；读thermal_state区分真实等级和显示等级。全身仅横向干瘦，保持高度与深度，头发五官一起变化、头饰保尺寸；足烧只足底，不露核。用橙汁观察恢复与原位疤',
    '立即绕行；真实受火另用伤因场景',_thermal,env=DAY,vantage=(0,15),
    give=['mcyukkuri:orange_juice 12','mcyukkuri:flour_paste 12'],
    reads=[(f'读热损{i+1}',f'yukkuri status {sel(f"thermal{i}")} raw') for i in range(6)]
        + [('读家油足烧',f'yukkuri status {sel("house_heat0")} raw'),('读家油全身',f'yukkuri status {sel("house_heat1")} raw')])

_source_cases=(('cut',-12,0,'木剑刀割'),('blunt',-6,0,'空手钝击'),('arrow',0,0,'箭与三叉戟'),
               ('thorn',6,0,'仙人掌细刺'),('cone',12,0,'滴水石尖锥'),('blast',-12,10,'真实爆炸'),
               ('heat',-6,10,'火烧与热地板'),('friction',0,10,'真实滚拖摩擦'))
add('wound_sources','body','真实伤因与实际扣血','真实伤因',
    '目标从健康无伤开始，玩家用有限工具真实命中，读实际扣血、wound_sites/anchors的shape与伤级；弓箭和三叉戟用真实投射物，仙人掌/石笋按实际接触，爆炸用TNT。右后玻璃围区有饥饿捕食种和猎物，普通AI自行追咬，观察bite。热地板只足烧，着火整体焦枯；滚拖用原生空手操作。一次MC命中不虚构逐齿逐刺落点',
    '先看右后追咬，再逐类手动操作；清场恢复地形与实体',[
        *[yk('reimu',dx,dz,name,label=label,nutrition=90,energy=95,lead=i==0) for i,(name,dx,dz,label) in enumerate(_source_cases)],
        box(10,-1,7,20,5,17,'minecraft:glass','hollow'),
        yk('reimu',15,11,'bite_target',label='真实追咬猎物',nutrition=70,energy=95),
        yk('remilia',15,15,'bite_hunter',label='饥饿捕食种',nutrition=10,energy=95)],
    env=DAY,vantage=(0,17),give=['minecraft:wooden_sword','minecraft:stick','minecraft:bow','minecraft:arrow 16',
        'minecraft:trident','minecraft:sand 4','minecraft:cactus 4','minecraft:pointed_dripstone 4','minecraft:tnt 2','minecraft:redstone_torch 2',
        'minecraft:flint_and_steel','minecraft:magma_block 4','mcyukkuri:orange_juice 12'],
    reads=[(f'读{label}',f'yukkuri status {sel(name)} raw') for name,_,_,label in _source_cases]
        + [('读真实咬伤',f'yukkuri status {sel("bite_target")} raw'),('读捕食交接',f'yukkuri status {sel("bite_hunter")} raw')])

add('wound_leak', 'body', '漏馅与止漏对照', '漏馅止漏',
    '五只灵梦：1／2／3级裂口、多处三级裂口、血3且三级大裂口露核；每10秒漏血0／0.5／1／1／1。绕行看大伤口普通馅与唯一中枢创腔，橙汁闭伤补血止漏并原位留疤，面粉糊只去疤；露核者真实漏血可致死，及时治疗',
    '先等 10 秒读血量，再用橙汁；随后再等 10 秒确认止漏', [
        *[yk('reimu', -8 + 4 * i, 0, f'v{i}', yaw=180, lead=i == 1, label=label, nutrition=20, energy=95)
          for i, label in enumerate(('轻伤', '二级裂口', '三级大伤口', '多处大伤口', '低血露核'))],
        *[set_status(f'v{i}', 'marks.scars', 0) for i in range(5)],
        *[set_status(f'v{i}', 'health.wound_at', f'front:tear:{min(3, i + 1)}') for i in range(5)],
        set_status('v3', 'health.wound_at', 'back:tear:3'), set_status('v4','health.health',3)],
    give=['mcyukkuri:flour_paste 5', 'mcyukkuri:orange_juice 5'], note=NEW, vantage=(0, 10),
    reads=[('读二级裂口', f'yukkuri status {sel("v1")}'), ('读三级裂口', f'yukkuri status {sel("v2")}'),
           ('读多处裂口', f'yukkuri status {sel("v3")}'), ('读漏馅露核',f'yukkuri status {sel("v4")} raw')])

add('treatment_severe', 'body', '橙汁治伤与烤足', '橙汁治伤',
    '四类三级伤、旧包扎裂伤、烤足共六只：自然等 40 秒三级伤仍在，旧包扎仍漏血。橙汁一剂清伤补满血，三级伤在原处必留疤，烤足也恢复；满血有伤照样能治。先读 wound_anchors，治后对照 scar_sites，面粉糊只去疤',
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
    '前两只都有疤 1、三级凹伤、血 6：左先面粉糊，只去疤不改伤血；右先橙汁，清伤补满血、保留旧疤并新增三级伤原位疤，再交换用药。第三只二级裂伤概率留疤；第四只健康无疤，两药都无效；第五只有油霉2级与轻伤，橙汁可治伤但油霉等级不变',
    '先读数再用药；自然愈合约 40 秒。核对扣料请切生存，创造模式不耗物品', [
        yk('reimu', -6, 0, 'paste_first', label='先去疤', lead=True, nutrition=20, energy=100),
        yk('reimu', -2, 0, 'orange_first', label='先治伤', nutrition=20, energy=100),
        *[command for name in ('paste_first', 'orange_first') for command in (
            set_status(name, 'marks.scars', 0), set_status(name, 'marks.scars', 1), set_status(name, 'health.wound_at', 'side:dent:3'),
            set_status(name, 'health.health', 6))],
        yk('reimu', 2, 0, 'healing', label='伤后留疤', **pair_ready()),
        set_status('healing', 'marks.scars', 0), set_status('healing', 'health.wound_at', 'front:tear:2'),
        yk('reimu', 6, 0, 'healthy', label='无伤无疤', **pair_ready()),
        set_status('healthy', 'marks.scars', 0),
        yk('reimu', 10, 0, 'moldy', label='油霉对照', **pair_ready()),
        set_status('moldy', 'health.mold', 2), set_status('moldy', 'health.wound_at', 'side:dent:1')],
    env=DAY, give=['mcyukkuri:flour_paste 8', 'mcyukkuri:orange_juice 8'], vantage=(0, 10),
    reads=[(caption, f'yukkuri status {sel(name)} raw') for caption, name in (
        ('读先去疤', 'paste_first'), ('读先治伤', 'orange_first'), ('读伤后留疤', 'healing'), ('读无效对照', 'healthy'), ('读油霉对照', 'moldy'))])

add('treatment_care', 'body', 'NPC治疗用料', 'NPC治疗',
    '伤者有三级裂口、血6、旧疤1、无感染。NPC先手持橙汁约1秒、挥手交互，再换面粉糊重复；清伤回血留疤后去疤。每剂先从库存预留到手上，成功消耗，未用完归还，最终余量8→6；可在举药时轻击伤者核对中断不误耗',
    '约 10–40 秒', [
        yk('reimu', 0, 0, 'patient', label='伤者', lead=True, **pair_ready()),
        set_status('patient', 'health.wound_at', 'front:tear:3'),
        set_status('patient', 'health.health', 6), set_status('patient', 'health.infection', 0),
        set_status('patient', 'marks.scars', 0), set_status('patient', 'marks.scars', 1),
        human('caretaker_brother', 2, 0, 'carer', 0, label='照料员')],
    env=DAY, vantage=(0, 10), reads=[('读伤者', f'yukkuri status {sel("patient")} raw'),
                                    ('读医疗余量', f'data get entity {sel("carer")} Medicine')])

add('core_exposed', 'body', '中枢外露与准确命中', '中枢外露',
    '前排依次是低血多处深伤、厚后发深伤、满血爆裂、低血小伤、低血完整皮面、低血窄刺、低血尖锥、低血烧伤；后排是头顶深伤。仅满足真实大缺损与低血通道者露出唯一中央中枢，窄刺/尖锥/烧伤不自动露核。绕到后发与头顶看连续创缘；玩家瞄准创口及其外缘一圈宽松范围即算命中中枢（头发、帽饰不挡），远离创口的旁侧只受普通伤，橙汁闭口并原位留疤',
    '先绕行看外观；再手动瞄准，清场后可重复', [
        yk('reimu', -14, 0, 'exposed', label='低血多处深伤', lead=True, **pair_ready()),
        set_status('exposed', 'health.wound_at', 'front:dent:3'), set_status('exposed', 'health.health', 3),
        set_status('exposed', 'health.wound_at', 'back:dent:3'),
        yk('marisa', -10, 0, 'struck', label='厚后发·绕背瞄准', **pair_ready()),
        set_status('struck', 'health.wound_at', 'back:dent:3'), set_status('struck', 'health.health', 3),
        yk('reimu', -6, 0, 'deep_full', label='满血爆裂·不露核', **pair_ready()),
        set_status('deep_full', 'health.wound_at', 'front:tear:3:blast'),
        yk('reimu', -2, 0, 'small', label='低血小裂口', **pair_ready()),
        set_status('small', 'health.wound_at', 'front:tear:2'), set_status('small', 'health.health', 3),
        yk('reimu', 2, 0, 'closed', label='低血完整皮面', **pair_ready()), set_status('closed', 'health.health', 3),
        *[command for name,dx,kind,shape,label in (('pointed',6,'tear','puncture','低血窄刺'),
                ('cone',10,'tear','impale','低血尖锥'),('burnt',14,'scorch','whole','低血烧伤'))
          for command in (yk('reimu',dx,0,name,label=label,**pair_ready()),
              set_status(name,'health.wound_at',f'front:{kind}:3:{shape}'),set_status(name,'health.health',3))],
        yk('reimu',0,7,'hairline',label='头顶深伤',**pair_ready()),
        set_status('hairline','health.wound_at','top:dent:3'),set_status('hairline','health.health',3)],
    give=['mcyukkuri:orange_juice 12','mcyukkuri:flour_paste 12'], note=NEW, vantage=(0, 16),
    reads=[(caption, f'yukkuri status {sel(name)} raw') for caption, name in (
        ('读中枢外露', 'exposed'), ('读厚后发', 'struck'), ('读满血深伤', 'deep_full'), ('读小裂口', 'small'), ('读完整皮面', 'closed'),
        ('读窄刺', 'pointed'), ('读尖锥', 'cone'), ('读烧伤', 'burnt'), ('读头顶深伤', 'hairline'))])

add('magma_sole', 'body', '岩浆块烫脚', '岩浆块烫脚',
    '左边位于半径4格岩浆块区中心，右边普通石头对照；不预置伤口。热地板伤害沿用玩家相同的原版判定与受伤冷却；每次实际受伤跳起，高度约为此前高跳的一半，并连续逃向安全地面。健康个体应活着离开；离开后橙汁治伤，核对脚底疤和烤足',
    '先观察数秒；离开过快可清场重开', [
        box(-13, -1, -4, -5, -1, 4, 'minecraft:magma_block'),
        box(3, -1, -2, 7, -1, 2, 'minecraft:stone'),
        yk('reimu', -9, 0, 'hot', label='半径4格岩浆块', lead=True, **pair_ready()),
        yk('reimu', 5, 0, 'cool', label='普通石头', **pair_ready()),
        set_status('hot', 'marks.scars', 0), set_status('cool', 'marks.scars', 0)],
    env=DAY, give=['mcyukkuri:orange_juice 4'], note=NEW, vantage=(0, 10),
    reads=[('读脚底灼伤', f'yukkuri status {sel("hot")} raw'), ('读普通地面对照', f'yukkuri status {sel("cool")} raw')])

add('core_filling', 'body', '中枢馅', '中枢馅',
    '一只有正面深伤的灵梦身体耗尽（核心完好）留下空壳，2 秒后割出独立中枢馅，遗体创腔保留但不再画核心；旁边重伤又饿的魔理沙吃下它：血 +4、营养 +10、不安下降。普通食用量为零仍可取一次核心，搬起放下不能让核心重新出现',
    '约 10–30 秒', [
        yk('reimu', 0, 0, 'dead', **pair_ready()),
        set_status('dead', 'health.wound_at', 'front:dent:3'),
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

# Each victim dies of one real cause: a vanilla damage type through the ordinary hurt path, or the body's own internal cause
# (terminal mould, the end of its lifespan). The pool holds a drowning and two remains with a water clock already running; the dry
# control keeps the same clock on land, where it does not advance.
OLD_AGE_FAMILY = f'Version:2,Initialized:1b,Stage:"ADULT",Age:{1056000 - 200}L,Nourishment:{1056000 - 200}L,Maturity:336000L,Lifespan:1056000L,AdultScale:1f,Scale:1f'
_forms = [('old', -20, '寿终'), ('starve', -14, '饿死'), ('fire', -8, '烧死'), ('anvil', -2, '压死'), ('struck', 4, '打死'),
          ('frozen', 10, '冻死'), ('mold', 16, '油霉病死')]
_form_lines = [box(-8, -2, -13, 8, -1, -7, 'minecraft:water')]
for _name, _x, _label in _forms:
    _form_lines.append(yk('reimu', _x, 0, _name, label=_label, lead=_name == 'old', fam=OLD_AGE_FAMILY if _name == 'old' else None, **pair_ready()))
_form_lines += [
    f'damage {sel("starve")} 100 minecraft:starve', f'damage {sel("fire")} 100 minecraft:in_fire',
    f'damage {sel("anvil")} 100 minecraft:falling_anvil', f'damage {sel("struck")} 100 minecraft:mob_attack',
    f'damage {sel("frozen")} 100 minecraft:freeze', set_status('mold', 'health.mold', 8),
    yk('reimu', 0, -10, 'drowned', dy=-1, label='溺死', **pair_ready()), f'damage {sel("drowned")} 100 minecraft:drown',
    remains(-5, -10, 'soaking', dy=-1, soak=5400, cause='minecraft:drown', category='drowned', food=0),
    remains(5, -10, 'melting', dy=-1, soak=23400, cause='minecraft:mob_attack', category='violent'),
    remains(12, -10, 'dry_control', soak=23400, cause='minecraft:mob_attack', category='violent')]
add('remains_forms', 'body', '死因遗体与水中溶化', '死因遗体',
    '七只灵梦各死于真实死因：寿命（约10秒）、饥饿、着火、落砧、近战击打、冻伤、终末油霉（约25秒内）；北边水池里一只溺死。'
    '遗体按存下的死因类别换死亡脸、姿态和表层：寿终凹缝眼灰白、饿死干瘪、烧死焦黑带灰、压死压扁带馅渍、打死歪倒垂舌且身下渗馅、冻死挺圆结霜、病死发黄起霉点、溺死白眼泡胀冷白滴水。'
    '水池另有两具已泡水的遗体：左边溺死遗体约30–60秒后泡软（更胀、发白斑）；右边被打死后落水、尚有6份余量，约30–60秒后溶化成沉底残渣，余量6→0、不能取核。'
    '浮在水面时只有实际浸水的 tick 计入水钟，漂起的瞬间不计，所以时间会比 30 秒长。'
    '岸上同样水钟的打死遗体不再前进，仍为泡软、余量6。用“读遗体”核对 category、water(soak)、food 和 core',
    '约 30–60 秒；泡软与溶化也可等真实浸水 5／20 分钟', _form_lines,
    note=NEW, vantage=(0, 12),
    reads=[('读遗体', 'yukkuri body info @e[type=mcyukkuri:yukkuri_remains,distance=..40,sort=nearest]')])

_carrion_lines = []
for _label, _x, _z, _friend, _control, _bold in (
        ('guard', -18, 0, 95, 95, 95), ('eat', 18, 0, 10, 10, 15), ('refuse', 0, -20, 95, 95, 10)):
    _dead = uid('carrion', _label)
    _carrion_lines += [
        remains(_x, _z, 'body_' + _label, ident=_dead, food=6),
        yk('reimu', _x + 5, _z, 'starving_' + _label, label='饿油', nutrition=2, energy=95,
           friendliness=10, self_control=10, boldness=50, lead=_label == 'guard'),
        yk('chen', _x + 1, _z - 2, 'mate_' + _label, label={'guard':'大胆护尸','eat':'食尸倾向','refuse':'胆小噗咕护尸'}[_label],
           nutrition=2, energy=95, friendliness=_friend, self_control=_control, boldness=_bold,
           kin=f'Partner:{uuid_nbt(_dead)}')]
add('carrion', 'body', '饿极食尸', '饿极食尸',
    '三组同样饥饿，各有有限遗体和食尸者。伴侣只改友善、自制、胆量：左大胆护尸走近攻击，右较愿食尸，北边胆小拒食但在尸边噗咕。读decision、score、last和实际Goal；攻击须真实接触，目标停止食尸后短暂观察再结束，吃空留壳、搬运不补资源',
    '约5–20秒，长期饥饿和伤势可能改变选择', _carrion_lines, note=NEW, vantage=(0, 14),
    reads=[(f'读{role}·{label}', f'yukkuri status {sel(role + "_" + label)} raw')
           for label in ('guard','eat','refuse') for role in ('mate','starving')])

_hunt_lines = []
for _label, _x, _friend, _control in (('willing', -16, 10, 10), ('reluctant', 16, 95, 95)):
    _hunt_lines += [
        yk('reimu', _x, 0, 'hunter_' + _label, label='低克制' if _label == 'willing' else '高克制',
           nutrition=9, energy=95, friendliness=_friend, self_control=_control, boldness=60, lead=_label == 'willing'),
        yk('marisa', _x + 4, 0, 'prey_' + _label, accessory=False, **pair_ready())]
add('cannibal_hunt', 'body', '捕食失饰者', '捕食失饰者',
    '两组都是营养9、看见失饰成年目标；左低友善低自制，右高友善高自制。读猎食判分与真实追咬：数值拒绝不算卡住；失饰识别不等于必攻击，低血和体力不足会退避。只有实际伤害形成食物、吞下才加营养',
    '约10–60秒；饥饿继续加深时意愿也会改变', _hunt_lines, note=NEW, vantage=(0, 12),
    reads=[(f'读{role}·{label}', f'yukkuri status {sel(role + "_" + label)} raw')
           for label in ('willing','reluctant') for role in ('hunter','prey')])



_lw = {k: uid('last_words', k) for k in ('dying', 'mate', 'kid')}
add('last_words', 'body', '临终遗言', '临终遗言',
    '约 6 秒后灵梦中枢受损、濒死 4 秒：先说临终台词，再对伴侣和孩子说遗言；亲属含泪围拢；死后孩子得到“家人去世”（不安 +30）',
    '约 10 秒（6 秒后触发，等亲缘登记）', [
        yk('reimu', 0, 0, 'dying', uuid=_lw['dying'], lead=True, kin=kin_of(Partner=_lw['mate']), **pair_ready()),
        yk('marisa', 3, 1, 'mate', uuid=_lw['mate'], kin=kin_of(Partner=_lw['dying']), **pair_ready()),
        yk('reimu', -3, 1, 'kid', stage='child', uuid=_lw['kid'], nutrition=95, kin=kin_of(Mother=_lw['dying'], Father=_lw['mate']))],
    late=[Retry(set_status('dying', 'health.core', 'damage'), sel('dying'), tries=1, first=80)],
    note=NEW, vantage=(0, 10), reads=[('读孩子', f'yukkuri status {sel("kid")}')])

_offering_lines = []
for _label, _x, _friend, _nutrition, _health in (
        ('desperate', -20, 95, 0, 4), ('reluctant', 0, 30, 0, 4), ('ordinary', 20, 95, 8, 10)):
    _parent, _child = uid('offering', _label + '_parent'), uid('offering', _label + '_child')
    _offering_lines += [
        yk('reimu', _x, 0, 'parent_' + _label, uuid=_parent, label=_label, lead=_label == 'desperate',
           nutrition=12, energy=95, friendliness=_friend, boldness=70),
        yk('reimu', _x + 3, 1, 'child_' + _label, stage='child', uuid=_child,
           nutrition=_nutrition, energy=95, kin=kin_of(Mother=_parent)),
        set_status('child_' + _label, 'health.health', _health)]
_offering_lines += [yk('reimu', -17, 4, 'other_child', stage='child', label='同亲另一只子油', nutrition=20, energy=95,
                       kin=kin_of(Mother=uid('offering','desperate_parent')))]
add('offering', 'body', '请吃了我', '请吃了我',
    '三组均无食物：左高友善亲油与濒饿低血孩子，另有同亲子油可共享献身遗体；中间只降低亲油友善；右边孩子一般饥饿。连续六次检查绝境孩子才按概率决定，拒绝冷却60秒；投喂或真实照护可中止。自然献身后两子共扣有限余额，吞咽才加营养',
    '约30–35秒首次抽签；未中可重开，孩子可能因真实缺粮死亡', _offering_lines,
    give=['minecraft:apple 4'], note=NEW, vantage=(0, 12),
    reads=[(f'读{role}·{label}', f'yukkuri status {sel(role + "_" + label)} raw')
           for label in ('desperate','reluctant','ordinary') for role in ('parent','child')]
          + [('读同亲子油',f'yukkuri status {sel("other_child")} raw')])

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
    '芙兰先选小灵梦，亲油决定帮助后靠近、噗咕挑衅，芙兰转怒追亲油，亲油再逃跑；读protection阶段与真实目标，子油按亲密度和友善度分支说话，保留真实拒绝及危险')
protect_child('sacrifice_reluctant', '挡捕食种·低意愿', '护子低意愿', 10,
    '与高意愿组只差友善：仍有机会引开追击。读 behavior.protection 的 chance、decision，再对照捕食目标；不能以一次仍追亲油判失败')

MYON = 'YukkuriMyon:{SpeechQuirk:"%s",Kanroken:%s,Initialized:1b}'
add('myon_brave', 'weather', '妖梦拔刀', '妖梦拔刀',
    '持棍妖梦接触且有视线才打：1.5伤、间隔1.6秒。双方胆量至少35参战，退战比例=0.65−0.0055×胆量；读myon_combat与predator。旁边灵梦照常逃，可用按钮调整双方胆量后重观；战斗到死亡不应卡死',
    '约 30 秒', [
        yk('myon', 0, 0, 'myon', yaw=-90, lead=True, extra=MYON % ('miyo', '1b'), **pair_ready()),
        yk('reimu', 0, 3, 'a', yaw=-90, **pair_ready()),
        yk('flandre', 12, 0, 'pred', yaw=90, nutrition=100, energy=100), set_status('pred', 'needs.nutrition', 10)],
    env=DAY, spot='pred', vantage=(6, 14), note=NEW, reads=[('读妖梦', f'yukkuri status {sel("myon")} raw'), ('读捕食反击', f'yukkuri status {sel("pred")} raw'),
        ('妖梦胆量20',set_status('myon','personality.boldness',20)),('妖梦胆量90',set_status('myon','personality.boldness',90)),
        ('捕食种胆量20',set_status('pred','personality.boldness',20)),('捕食种胆量90',set_status('pred','personality.boldness',90))])


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

add('hat_swap', 'headwear', '饰品通用、堆叠与转交', '同种饰品转交',
    '摘取前排完好或受损帽，交给同种缺帽个体，子油也可接收；异种拒绝。相同状态的同种饰品与同种碎片可堆叠。后排两只碎饰者会自行拾取兼容碎片，也可手动逐片交还，凑齐后仍须慢慢长合',
    '手动操作；拼回继续观察原修补过程', [
        yk('marisa', -5, 0, 'donor', aquatic=True, label='水生成体·受损帽', extra=hat(2), yaw=180, lead=True, **pair_ready()),
        yk('marisa', -1, 0, 'receiver', aquatic=True, accessory=False, label='水生成体·接收', yaw=180, **pair_ready()),
        yk('marisa', 3, 0, 'land', accessory=False, label='普通成体', yaw=180, **pair_ready()),
        yk('marisa', 6, 0, 'young', aquatic=True, stage='child', accessory=False, label='水生子油', yaw=180, **pair_ready()),
        yk('reimu', 6, -4, 'other_species', accessory=False, label='异种灵梦', yaw=180, **pair_ready()),
        yk('marisa', -3, -4, 'pieces_a', aquatic=True, label='碎片甲', yaw=180, **pair_ready()),
        yk('marisa', 2, -4, 'pieces_b', aquatic=True, label='碎片乙', yaw=180, **pair_ready())],
    env=DAY, vantage=(0, 9), late=[set_status('pieces_a', 'accessory.state', 'shatter'),
                                               set_status('pieces_b', 'accessory.state', 'shatter')],
    note='本场是无水空地，水生仍应正常走向碎片。摘下受损帽后缺口保留；同种碎片合堆、每次只耗一片。读 accessory.detail 的 gatherCheck、candidates、reachable 和 accessory.shards。',
    reads=[('读来源身份', f'yukkuri status {sel("donor")} raw'),
           ('读接收身份', f'yukkuri status {sel("receiver")} raw'),
           ('读碎片甲', f'yukkuri status {sel("pieces_a")} raw'),
           ('读碎片乙', f'yukkuri status {sel("pieces_b")} raw')])

add('hatless_kin', 'headwear', '失饰的孩子·亲油认不出', '亲油认不出',
    '两位亲油都凶悍、胆大且低自制，北边智力 40、南边 80。前者认不出失饰子油，鄙视后可能发起欺负和驱赶；后者仍认亲。读 known、last、accessory.sanction 与孩子位置，亲缘不豁免识别',
    '首次鄙视约 10–30 秒；自然升级有概率，可观察 1–3 分钟', [
        yk('reimu', 0, -5, 'mom_blind', yaw=90, label='凶悍亲油·智力40', intelligence=40, friendliness=0, boldness=95, self_control=0, **pair_ready()),
        yk('chen', 3, -5, 'kid_blind', stage='child', nutrition=90, energy=95, yaw=-90, lead=True, accessory=False),
        f'yukkuri scenario kin {sel("mom_blind")} {sel("kid_blind")} false',
        yk('reimu', 0, 5, 'mom_sees', yaw=90, label='凶悍亲油·智力80', intelligence=80, friendliness=0, boldness=95, self_control=0, **pair_ready()),
        yk('chen', 3, 5, 'kid_sees', stage='child', nutrition=90, energy=95, yaw=-90, accessory=False),
        f'yukkuri scenario kin {sel("mom_sees")} {sel("kid_sees")} false'],
    env=DAY, note=NEW, vantage=(-8, 0, -90), reads=[('读低智力亲油', f'yukkuri status {sel("mom_blind")} raw'), ('读高智力亲油', f'yukkuri status {sel("mom_sees")} raw'), ('读被驱赶孩子', f'yukkuri status {sel("kid_blind")} raw')])

add('hatless_scorn', 'headwear', '失饰者被鄙视', '失饰鄙视',
    '没戴头饰的灵梦：智力普通的旁观者认不出它，生气说台词、走开；它哭、不安上涨。高智力的橙（90）认得出，不鄙视也不躲',
    '约 10–30 秒出现，之后每 30–40 秒重复', [
        yk('reimu', 0, 0, 'bare', yaw=-90, lead=True, accessory=False, label='无头饰', **pair_ready()),
        yk('marisa', 4, 0, 'a', yaw=90, **pair_ready()), yk('reimu', 4, 4, 'b', yaw=90, **pair_ready()),
        yk('chen', -4, 3, 'c', yaw=180, label='高智力', **pair_ready()), set_status('c', 'personality.intelligence', 90)],
    env=DAY, note=NEW, vantage=(0, 10), reads=[('读失饰者', f'yukkuri status {sel("bare")} raw')])

add('hatless_monster', 'headwear', '失饰重伤者·怪物', '失饰怪物',
    '北边失饰灵梦有三处面部二级伤，南边只有一处背部三级严重伤，两者均符合怪物阈值。读 face_wounds、severe_wounds、grotesque 与观察者 contacts；前后两处普通伤的反例见边界场景',
    '约 10–60 秒；每个观察者每 30–40 秒重复', [
        yk('reimu', 0, -5, 'victim', yaw=-90, lead=True, accessory=False, label='面部三伤', **pair_ready()),
        set_status('victim', 'health.wound_at', 'front:tear:2'),
        set_status('victim', 'health.wound_at', 'front:tear:2'),
        set_status('victim', 'health.wound_at', 'front:tear:2'),
        yk('marisa', 3, -5, 'timid', yaw=90, intelligence=40, **pair_ready()),
        yk('reimu', 0, 5, 'severe', yaw=-90, accessory=False, label='背部严重伤', **pair_ready()),
        set_status('severe', 'health.wound_at', 'back:tear:3'),
        yk('marisa', 3, 5, 'severe_observer', yaw=90, intelligence=40, **pair_ready())],
    env=DAY, note=NEW, vantage=(-8, 0, -90), reads=[('读面部三伤', f'yukkuri status {sel("victim")} raw'), ('读单处严重伤', f'yukkuri status {sel("severe")} raw'), ('读北侧反应', f'yukkuri status {sel("timid")} raw'), ('读南侧反应', f'yukkuri status {sel("severe_observer")} raw')])

add('hatless_boundary', 'headwear', '怪物判定·普通伤低血量', '普通伤非怪物',
    '失饰灵梦前后各一处二级伤且低血量：面部不足三伤，也没有三级严重伤，仍是普通鄙视，读 face_wounds=1、severe_wounds=0、grotesque=false；低血量本身不构成怪物',
    '约 10–30 秒', [
        yk('reimu', 0, 0, 'single', lead=True, accessory=False, **pair_ready()),
        set_status('single', 'health.wound_at', 'front:tear:2'),
        set_status('single', 'health.wound_at', 'back:tear:2'), set_status('single', 'health.health', 3),
        yk('chen', 3, 0, 'observer', intelligence=40, **pair_ready())],
    env=DAY, vantage=(0, 10),
    reads=[('读单伤者', f'yukkuri status {sel("single")} raw'), ('读普通鄙视', f'yukkuri status {sel("observer")} raw')])

add('hatless_sanction', 'headwear', '失饰者被欺负', '失饰欺负',
    '凶悍成体自然鄙视并欺负失饰灵梦。受害者胆大且健康，遇到咬、抢、推时按自身状态选择反击或逃跑；欺负者被反击也重新选择。低血量一律逃跑，观察双方血量、实际 Goal、位置及反应读数',
    '自然升级有概率，约 1–3 分钟', [
        yk('reimu', 0, 0, 'bare', yaw=-90, lead=True, accessory=False, label='胆大失饰者', friendliness=15, boldness=95, self_control=15, **pair_ready()),
        yk('marisa', 3, 0, 'b1', yaw=90, label='凶悍欺负者', friendliness=0, boldness=80, self_control=0, **pair_ready()),
        yk('chen', -4, 3, 'friend', yaw=180, label='高智力', **pair_ready()), set_status('friend', 'personality.intelligence', 90)],
    env=DAY, note=NEW, vantage=(0, 10),
    reads=[('读欺负者', f'yukkuri status {sel("b1")} raw'), ('读欺凌伤口', f'yukkuri status {sel("bare")} raw')])

_puff_pairs = []
for _i, (_x, _z, _label, _stage, _friend, _bold) in enumerate((
        (-14, -10, '胆小子油', 'child', 50, 5), (14, -10, '友善成体', 'adult', 95, 80),
        (-14, 10, '普通成体', 'adult', 50, 50), (14, 10, '凶悍大胆成体', 'adult', 0, 95))):
    _puff_pairs += [
        yk('reimu', _x, _z, f'puff_target{_i}', label=_label, stage=_stage, accessory=False,
           nutrition=90, energy=95, friendliness=_friend, boldness=_bold, self_control=60, lead=_i==0),
        yk('marisa', _x+3, _z, f'puff_source{_i}', label='凶悍失饰制裁者', yaw=90,
           intelligence=30, friendliness=0, boldness=95, self_control=0, **pair_ready())]
add('puff_response', 'headwear', '被噗咕的数值反应', '噗咕反应',
    '四组真实失饰初态，由凶悍者自行识别并发起制裁宣告。对照小体型胆小、友善、普通与凶悍大胆目标的逃跑／无视／噗咕回应／激怒攻击；自然升级有概率，不直接指定行为。受伤、恐惧及当时任务会改变决定；日志 social puff 记录当前体型比、友善、胆量、任务重要程度和选择',
    '约1–3分钟；读实际制裁与反应，不将未发生宣告算作无视', _puff_pairs,
    env=DAY, vantage=(0, 26), reads=[(f'读回应{i+1}',f'yukkuri status {sel(f"puff_target{i}")} raw') for i in range(4)])

# ---- species traits and rare forms
add('species_fatigue', 'species', '物种体力消耗对照', '体力消耗',
    '妖梦、灵梦和帕秋莉均成年、营养100、精力95，各有两份真实苹果并用玻璃隔开。按 species.fatigue 的 steps 对照代谢 cost：同为20次时应为18／20／25；睡眠会暂停清醒代谢，其他活动额外耗能不计入cost，不要求同一秒达到同次数',
    '默认间隔下约3分钟，精力耗尽前读20次；可用已有推进按钮', [
        box(-3, 0, -4, -3, 2, 4, 'minecraft:glass'), box(3, 0, -4, 3, 2, 4, 'minecraft:glass'),
        *[command for species,dx,name,label in (('myon',-7,'myon','妖梦·0.9'),('reimu',0,'neutral','灵梦·1'),('patchouli',7,'frail','帕秋莉·1.25'))
          for command in (yk(species,dx,0,name,label=label,nutrition=100,energy=95,lead=name=='myon'),item('minecraft:apple',2,dx,2))]],
    env=DAY, spot='race', vantage=(0, 12),
    reads=[(label,f'yukkuri status {sel(name)} raw') for name,label in
           (('myon','读妖梦代谢'),('neutral','读中性代谢'),('frail','读体弱代谢'))])

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

# The same real households and stores, with dependent crawling infants as an explicit initial condition.
_migrant_babies = '@e[type=#mcylab:yukkuri,tag=scn_migrant_baby,distance=..110]'
colony('colony_migrate_babies', '迁徙·赤油跟亲', '赤油随迁',
       '村中孩子从赤油阶段开始：亲油备真实口粮，赤油爬行跟亲，亲油回找掉队孩子并按友善决定喂养；到新址后一起认家庭新窝',
       '约3–6分钟', [
           'tag @e[type=#mcylab:yukkuri,nbt={YukkuriFamily:{Stage:"JUVENILE"}},distance=..45] add scn_migrant_baby',
           f'yukkuri status set {_migrant_babies} growth.age_ticks 36000',
           f'execute as {_migrant_babies} run data modify entity @s YukkuriChildhood set value {{{CHILDHOOD_CRAWLING_READY}}}',
           'yukkuri status set @e[type=#mcylab:yukkuri,distance=..45] personality.friendliness 90',
           'yukkuri status set @e[type=#mcylab:yukkuri,distance=..45] needs.nutrition 80',
           f'yukkuri status set {_migrant_babies} needs.nutrition 40',
           Retry('yukkuri settlement migrate', COLONY_LEAD)],
       lines=_migration_site,
       note='初始亲油营养80、赤油40；高友善不保证照护必定成功。检查同一孩子实际移动、食物减少与营养回升、住所切换；不能以中心改变代替亲子抵达。',
       reads=[('读赤油随迁', f'yukkuri status {_migrant_babies} raw'),
              ('读全体亲子', 'yukkuri status @e[type=#mcylab:yukkuri,distance=..110] raw'),
              ('读迁徙完成条件', 'yukkuri settlement rules')])

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

# Topic codes are shared by the scene buttons, signs, and readouts.
TOPICS = {
    'balance_traits': 'N1', 'balance_growth': 'N1', 'balance_relation': 'N1', 'balance_first_contact': 'N1', 'balance_forms': 'N1', 'balance_deibu': 'N1',
    'agitation_relief': 'D2', 'adult_pairing': 'F6', 'widow_repair': 'F6', 'alice_hunt': 'R4', 'alice_interrupt': 'R4',
    'aftermath_growth': 'F7', 'aftermath_burden': 'F7', 'aftermath_depletion': 'F7',
    'den_family': 'W1', 'widow_family': 'W1', 'dig': 'W2', 'indep_near': 'W3', 'indep_far': 'W3', 'den_move': 'W4', 'forage': 'W5', 'graze': 'W6', 'pet_birth': 'W7',
    'sleep': 'D1', 'fatigue': 'R1', 'fatigue_predator': 'R2', 'nutrition_age': 'D1', 'meet': 'D1', 'meal': 'D1', 'toilet': 'F5', 'hurt': 'F3', 'hurt_kin': 'F3',
    'birth_land_caught': 'F1', 'birth_land_reunion': 'F1', 'birth_land_missed': 'F1', 'feed': 'F2', 'first_toilet': 'F2', 'hop': 'F2', 'growth': 'F4',
    'leaf_swaddle': 'F2', 'leaf_swaddle_neglect': 'F2', 'family_distance': 'F8', 'family_search': 'F8', 'family_search_blocked': 'F8', 'birth_nest_safe': 'F1', 'family_nests': 'F4', 'infant_starvation': 'F2',
    'rain_damage': 'R1', 'forage_empty': 'R1', 'rain_life': 'R1', 'marisa_explore': 'R1', 'rain_shelter': 'R1', 'thunder': 'R1', 'rain_open': 'R1', 'pred_watch': 'R2', 'pred_hunt': 'R2', 'pred_range': 'R2', 'pred_pets': 'R2', 'marks': 'R3', 'shared_body': 'R3',
    'sail': 'A1', 'hat_custody_burn': 'A2', 'shore': 'A1', 'shore_child': 'A1', 'high': 'A1', 'voyage': 'A1', 'braid': 'A4', 'afloat': 'A2', 'shake': 'A2',
    'water_life': 'A6', 'water_winter': 'A6', 'water_colony': 'A8', 'water_neighbours': 'A7', 'water_threat': 'A7', 'water_dock': 'A8',
    'cargo_empty': 'A1', 'cargo_full': 'A1', 'cargo_load': 'A1', 'cargo_rescue': 'A4', 'cargo_stow': 'A1',
    'birth_boat_caught': 'A3', 'birth_boat_missed': 'A3', 'shore_babies': 'A4', 'rescue_baby': 'A4', 'self_rescue': 'A4', 'self_far': 'A4', 'self_tired': 'A4', 'shore_capsized': 'A4', 'capsize_caring': 'A4', 'capsize_selfish': 'A4', 'capsize_siblings': 'A4', 'couple_boats': 'A5', 'family_skiffs': 'A5',
    'house_empty': 'H1', 'house_pair': 'H2', 'house_eggs': 'H3',
    'shop_build': 'M1', 'factory_labor': 'M2', 'factory_manju': 'M2', 'factory_bag': 'M2', 'walker': 'M3', 'harness_walk': 'M3', 'swaddle_styles': 'M3', 'feed_wild': 'M3', 'oniisan': 'M4', 'oniisan_prey': 'M4', 'attack_witness': 'M4',
    'wild': 'M5', 'badge': 'M5', 'exam': 'M5', 'date': 'M5', 'transport': 'M5', 'custody_death': 'M5',
    'airborne': 'R8', 'house_health': 'R9',
    'body_mold': 'R5', 'wound_parts': 'R5', 'wound_materials': 'R5', 'thermal_body': 'R5', 'wound_sources': 'R5', 'wound_leak': 'R5', 'treatment_severe': 'R5', 'treatment_scars': 'R5', 'treatment_care': 'R5', 'magma_sole': 'R5', 'core_exposed': 'R5', 'core_filling': 'R5', 'remains_rot': 'R6', 'remains_forms': 'R6', 'carrion': 'R6', 'cannibal_hunt': 'R6',
    'last_words': 'R7', 'offering': 'R7', 'sacrifice': 'R7', 'sacrifice_reluctant': 'R7',
    'hat_swap': 'C1', 'hat_damage': 'C1', 'hat_shatter': 'C1', 'hatless_kin': 'C2', 'hatless_scorn': 'C2', 'hatless_boundary': 'C2',
    'hatless_monster': 'C2', 'hatless_sanction': 'C2', 'puff_response': 'C2',
    'myon_stick': 'S1', 'myon_brave': 'S1', 'species_fatigue': 'S1', 'rare_forms': 'S2', 'kotatsu_winter': 'S2', 'kotatsu_summer': 'S2',
    'cheek_forage': 'S3', 'alice_chores': 'S3', 'patchouli_vomit': 'S3', 'chen_speed': 'S3', 'marisa_sumo': 'S3',
    'colony_hunt': 'B2', 'colony_ration': 'B2', 'colony_judge': 'B2', 'colony_migrate': 'B2', 'colony_migrate_babies': 'B2',
    'shop_stale': 'M6', 'factory_wear': 'M6', 'exam_fail': 'M6',
}
for _s in S:
    _s.asks = TOPICS.get(_s.id) or ('B1' if _s.id.startswith('settle_') else '')
    assert _s.asks, _s.id
