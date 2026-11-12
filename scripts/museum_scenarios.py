"""Scenarios of the playground: one question each, answered by real individuals under their ordinary AI.

A scenario is a list of commands run positioned at the scenario's anchor block (see museum_dsl). It only creates the
individuals and props and sets their real starting state (hunger, tiredness, life stage, kin, home, conception, a
wound, a hat set down ...). After that nothing is scripted, fixed or invulnerable: the observer watches, speeds the
clock up, flies to the lead, or clears the field and starts again.

Fields hold the terrain (museum_fields.py); each scenario names its field and the spot of that field it stands around.
The ``TOPICS`` mapping at the end connects user concerns to scenarios; docs/TODO.md owns progress and completion criteria.
Scenario definitions and readouts live here; AGENTS.md covers navigation and shared operations.
"""
import re
import json
import sys
import uuid
from pathlib import Path

sys.dont_write_bytecode = True
from museum_dsl import (yk, yk_on_nest, human, item, block, large_nest, box, shed, home, sel, sel_all, tag, say, uid, uuid_nbt, family, remains, aquatic_spawn, _text)  # noqa: E402
from museum_fields import FIELDS, BY_ID, FEET, den  # noqa: E402

# Scene numbers: the field's code and a two-digit index (``PD-03``), so a visitor can name a scene out loud. The file is the
# registry of them: its ``numbers`` entries are appended in field order and are never reordered or renumbered, so a scene
# keeps its number when other scenes are added, removed or the deck is reordered (a removed scene keeps its line and its
# number is not given to another one). Field codes are fixed there too, one per field.
NUMBERS = json.loads((Path(__file__).with_name('museum_scene_numbers.json')).read_text(encoding='utf-8'))

CHILDHOOD_CAN_JUMP_SOON = 'Version:1,Newborn:1b,Landed:1b,FirstToiletDone:1b,Crawled:1b,Jumped:0b,CrawlDistance:1.0d'
# A real infant ready to follow its parent to the bank: it has already eaten, relieved itself and crawled, but cannot jump.
CHILDHOOD_CRAWLING_READY = 'Version:1,Newborn:1b,Landed:1b,FirstMealDone:1b,FirstToiletDone:1b,Crawled:1b,Jumped:0b,CrawlDistance:1.0d'
# The same infant that has since learned its short hop (Jumped, landings): it can now climb up to its parent's brim by itself.
CHILDHOOD_HOPPING = 'Version:1,Newborn:1b,Landed:1b,FirstMealDone:1b,FirstToiletDone:1b,Crawled:1b,Jumped:1b,JumpLandings:5,CrawlDistance:1.0d'
# A young that has already sailed its own hat (its first voyage came back): it may climb, right and boat its own hat out of the water.
CHILDHOOD_SAILED = 'Version:2,Newborn:0b,Sailed:1b'
OWNED = 'NeoForgeData.HumanCommerce'
# A subadult in the fixed age interval, ready to live independently.
TEEN_FAMILY = ('Version:2,Initialized:1b,Stage:"JUVENILE",Age:276000L,Nourishment:276000L,Maturity:336000L,Lifespan:1056000L,AdultScale:1f,Scale:0.72f')
LATE_COMMANDS = ('yukkuri scenario home', 'yukkuri scenario twigs', 'yukkuri scenario birth', 'yukkuri scenario eggs', 'yukkuri scenario afloat')
PENDING = '修复中'     # a scenario whose helper behaviour is not wired in yet: the sign says so, the button and terrain stay
# A rider crushed into a block is never a pond outcome: whichever scene has a young on a hat brim, its smoke window forbids the death.
_POND_SMOKE_FORBID = r'\[body\][^\n]* death settled [^\n]*cause=inWall\b'


class Retry:
    """A late command that a refusal does not end: after ``first`` ticks it is tried every ``every`` ticks, at most ``tries`` times,
    until the game accepts it. ``guard`` is an entity selector that must match before an attempt is made (the way a refusal is
    kept off the chat: the attempt waits until the thing it needs, say a mother seated in her boat, is really there)."""
    def __init__(self, command, guard, tries=16, every=50, first=60):
        self.command, self.guard, self.tries, self.every, self.first = command, guard, tries, every, first


class Scenario:
    def __init__(self, sid, field, title, hint, watch, wait, lines, give=(), env=None, spot=None, retry='先按“清空本场地”，再重新生成',
                 asks='', note='', sub=None, vantage=(0, 14), reads=(), late=(), smoke=None, capture=None):
        self.id, self.field, self.title, self.hint = sid, field, title, hint
        # self.number is set once every scenario exists (``_number_scenes``), so that the pond tour order is known.
        self.watch, self.wait, self.retry, self.asks, self.note = watch, wait, retry, asks, note
        self.lines, self.give, self.env = surface_demo_lines(sid,lines), list(give), env or {}
        # settings that need the individuals to stand on the ground (a home, a conception, a boat) run two seconds after the summons
        deferred = [i for i, line in enumerate(self.lines) if line.startswith(LATE_COMMANDS)]
        self.late_lines = [self.lines[i] for i in deferred]
        self.lines = [line for i, line in enumerate(self.lines) if i not in deferred]
        f = BY_ID[field]
        self.anchor = f.spots[spot] if spot else f.anchor
        self.plot = f.plot_rect(*self.anchor)
        self.sub = sub                 # name of the plot cleared before this scenario (default: the whole field)
        self.vantage = vantage         # (dx, dz) from the anchor where the starting player is put (None: stay on the deck)
        self.reads = list(reads)       # (caption, command) readouts that belong to this scenario
        self.smoke = smoke             # optional {ticks, expect, forbid}; runner only, never emitted into the pack
        if field == 'pond' and self.smoke is not None:
            self.smoke = {**self.smoke, 'forbid': [*self.smoke.get('forbid', ()), _POND_SMOKE_FORBID]}
        self.capture = capture if capture is not None else dict(shots=(1, 40, 200, 600), sequences=((60, 160, 2),))
        self.late = self.late_lines + surface_demo_lines(sid,late)   # commands that need the individuals to stand on the ground first: run two seconds after the start
        self.layout_ticks = max([40] + [40 + r.first + (r.tries - 1) * 20 for r in self.late if isinstance(r, Retry) and r.command.startswith('summon ')])

    @property
    def label(self):
        """How the scene is written wherever the player reads it: its number, then the name."""
        return f'{self.number} {self.title}'

    @property
    def tag(self):
        return PENDING if self.note.startswith(PENDING) else ('待验证' if self.note.startswith('待验证') else '')

    def body(self):
        """Commands of ``scn/<id>``: heading, the scenario itself, items for the player."""
        head = [say(f'【{self.label}】看点：{self.watch}', 'aqua'), say(f'预计：{self.wait}。没反应：{self.retry}。', 'yellow')]
        if self.note:
            head.append(say(f'注意：{self.note}', 'red'))
        return head + self.lines + [f'give @s {g}' for g in self.give]


def surface_demo_lines(sid, lines):
    """Preset skin demonstrations face the observer; explicit body-region comparisons retain their regions."""
    result = []
    comparisons = {'wound_materials', 'thermal_body', 'wound_sources', 'hatless_monster', 'core_exposed'}
    for line in (part for entry in lines for part in (entry.splitlines() if isinstance(entry, str) else [entry])):
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


def kin_of(**parents):
    return ','.join(f'{role}:{uuid_nbt(u)}' for role, u in parents.items())


def set_status(name, key, value):
    return f'yukkuri status set {sel(name)} {key} {value}'


# ====================================================================================================== wild shelters
# Wait for all three real claims on one mound, then use the ordinary death path before the first stores come in.
_dm, _df, _dc = (uid('den_family', name) for name in ('mother', 'father', 'child'))


def _den_family_death():
    field = BY_ID['den']
    origin_x, origin_z = field.spots['family']
    father = sel('father', 64)
    for x, y, z in field.doorways.values():
        doorway = f'~{x - origin_x} ~{y - FEET} ~{z - origin_z}'
        claims = ' '.join(f'if data block {doorway} Residents[{{Id:{uuid_nbt(member)}}}]' for member in (_dm, _df, _dc))
        yield Retry(f'execute {claims} run kill {father}', father, tries=80, every=20, first=400)


add('den_family', 'den', '一家三口住进土丘洞', '一家三口住进土丘洞',
    '父母和子油自己认领同一个土丘洞。三只刚入住、家里还缺粮时，场景让父油死亡。看母油目睹后的哀悼、出洞采草、存粮，以及孩子吃到食物。洞口的草已吃过，外面还有草。',
    '入住约半分钟到一分钟，之后再看数分钟（可五倍速）', [
        yk('reimu', 0, 3, 'mother', uuid=_dm, yaw=180, lead=True, kin=f'Partner:{uuid_nbt(_df)}', **pair_ready()),
        yk('marisa', 2, 3, 'father', uuid=_df, yaw=180, kin=f'Partner:{uuid_nbt(_dm)}', **pair_ready()),
        # Starts above appetite so the child's first demand develops during the handoff.
        yk('reimu', 1, 4, 'child', stage='child', uuid=_dc, yaw=180, nutrition=80, kin=f'Mother:{uuid_nbt(_dm)},Father:{uuid_nbt(_df)}'),
        box(-7, 0, 1, 7, 1, 8, 'minecraft:air', 'replace minecraft:short_grass')],
    env=DAY, spot='family', vantage=(0, 12),
    late=list(_den_family_death()),
    note='观察台可读母油的 Load、Delivered 和孩子营养。若家中已有足够存粮，母油会留在窝里用现成的粮照料孩子。',
    reads=[('读家的识别与母油觅食', f'yukkuri status {sel("mother")} raw'),
           ('读孩子生活', f'yukkuri status {sel("child")} raw')])

_em, _ef, _ec, _eg = (uid('den_mold_exile', name) for name in ('healthy', 'molded', 'baby', 'grandparent'))
add('den_mold_exile', 'den', '油霉亲油离巢', '油霉亲油离巢',
    '看亲油鼓脸驱赶、年长亲油含泪犹豫，以及患病亲油回头央求时赤油是否留在健康亲油身边。',
    '约 1–2 分钟', [
        yk('reimu', 0, 1, 'healthy', uuid=_em, yaw=0, label='健康亲油', friendliness=24, boldness=35,
           attachment=28, kin=f'Partner:{uuid_nbt(_ef)}', **pair_ready()),
        yk('marisa', 0, 3, 'molded', uuid=_ef, lead=True, label='患油霉的亲油',
           kin=f'Mother:{uuid_nbt(_eg)},Partner:{uuid_nbt(_em)}', **pair_ready()),
        yk('reimu', -1, 1, 'grandparent', uuid=_eg, yaw=0, label='年长亲油', friendliness=86, attachment=90, **pair_ready()),
        yk('reimu', 0, 0, 'baby', stage='baby', uuid=_ec, yaw=0, label='赤油', nutrition=100,
           kin=f'Mother:{uuid_nbt(_em)},Father:{uuid_nbt(_ef)}'),
        box(-4, 0, 6, 23, 0, 13, 'minecraft:short_grass', 'keep')],
    late=[*[f'yukkuri scenario home {sel(n)} ~ ~ ~-1' for n in ('healthy', 'molded', 'grandparent', 'baby')],
          f'yukkuri scenario bond {sel("grandparent")} {sel("molded")}',
          set_status('molded', 'health.mold', 5)],
    give=['mcyukkuri:mold_remover'], env=DAY, spot='family', vantage=(4, 16),
    note='等患病亲油回头被拒后，可用去霉药，看它能否重新认领原洞。',
    reads=[('读患病亲油与住所', f'yukkuri status {sel("molded")} raw'),
           ('读赤油与住所', f'yukkuri status {sel("baby")} raw')])


# The teen's independence is armed in NBT (Force); each family has its den as a starting home, and the farewell or departure is
# ordinary. The near family lives in the independence mound; the far one in the family mound 18 blocks west, its parent walled in a
# closed stone-brick room far out of PARENT_RANGE (24), so it cannot wander back during the wait.
_indep = []
for _lane, _dx, _stays in (('near', 0, True), ('far', -18, False)):
    _pm, _pc = uid('indep_near', f'{_lane}_parent'), uid('indep_near', f'{_lane}_teen')
    _indep += [yk('reimu', _dx, 3, f'{_lane}_parent', uuid=_pm, yaw=180, label='亲油', **pair_ready()),
               yk('reimu', _dx + 1, 4, f'{_lane}_teen', stage='teen', uuid=_pc, yaw=180, lead=_stays, nutrition=100, energy=100,
                  label='亚成·亲油在' if _stays else '亚成·亲油不在', kin=f'Mother:{uuid_nbt(_pm)}', extra='YukkuriIndependence:{Force:1b}'),
               f'yukkuri scenario kin {sel(f"{_lane}_parent")} {sel(f"{_lane}_teen")} false',
               *[f'yukkuri scenario home {sel(f"{_lane}_{who}")} ~{_dx} ~ ~-1' for who in ('parent', 'teen')]]
_indep += [box(38, 0, 1, 42, 3, 5, 'minecraft:stone_bricks'), box(39, 0, 2, 41, 2, 4, 'minecraft:air')]
add('indep_near', 'den', '亚成离巢：亲油在就告别', '亚成离巢：亲油在就告别',
    '两只亚成同时到了独立的时候：右边亲油在家，亚成先告别再离巢；左边亲油被关在远处，约 3 分钟后亚成不告别直接离开。之后各自去找空洞或挖穴，亲缘与好感保留',
    '约 3 分钟内离开（可倍速）', _indep, env=DAY, spot='indep', retry='先“清空本场地”（洞口格要空）', vantage=(-9, 14),
    # The far parent leaves only once the home is hers: a teen whose parents own another home is not yet of the family den.
    late=[f'tp {sel("far_parent")} ~40 ~ ~3'],
    reads=[('读告别亚成', f'data get entity {sel("near_teen")} YukkuriIndependence'),
           ('读不告别亚成', f'data get entity {sel("far_teen")} YukkuriIndependence')])

# the family of three in a 5-cell stone hole with a big empty earth cave 16 blocks east
_sm, _sf, _sc = uid('den_move', 'mother'), uid('den_move', 'father'), uid('den_move', 'child')
add('den_move', 'den', '小石洞太挤，一家搬去大土洞', '小石洞太挤，一家搬去大土洞',
    '一家两成体加一只能独立的子油挤在石头围的小洞里（5 格，墙是石头挖不动）；东边 16 格有一个空的大土洞，看家庭搬过去',
    '数分钟（可倍速）', [
        yk('reimu', 0, 3, 'mother', uuid=_sm, yaw=180, lead=True, kin=f'Partner:{uuid_nbt(_sf)}', **pair_ready()),
        yk('marisa', 2, 3, 'father', uuid=_sf, yaw=180, kin=f'Partner:{uuid_nbt(_sm)}', **pair_ready()),
        yk('reimu', 1, 4, 'child', stage='teen', uuid=_sc, yaw=180, nutrition=100, energy=100, kin=f'Mother:{uuid_nbt(_sm)},Father:{uuid_nbt(_sf)}', fam=TEEN_FAMILY),
        # The field's family and independence mounds (36 and 18 blocks west) are filled shut: the big cave is the only roomy vacant den.
        box(-38, 0, -5, -34, 1, 0, 'minecraft:stone'), box(-20, 0, -5, -16, 1, 0, 'minecraft:stone'),
        ],
    late=[f'yukkuri scenario home {sel(n)} ~ ~ ~-1' for n in ('mother', 'father', 'child')],
    env=DAY, spot='small', vantage=(8, 14), reads=[('读搬家说明', f'yukkuri status {sel("mother")} raw')])

# Two wild Reimu, each with a den of her own (the family and indep mounds, 18 apart) holding the two sticks a natural den starts with;
# only their five-axis personality differs. A hungry predator is summoned 20+ blocks south of each only after they have settled,
# and chooses and approaches by the ordinary hunt rules; nothing here names a Goal, a target or a result.
def _den_guard(name, spot):
    """The scenario individual standing in the cavity of that mound (5 wide, 5 deep behind the notch row): each hunter comes only
    once its own reimu is really in her own den."""
    x0, door_z = BY_ID['den'].spots[spot]
    return f'@e[tag={tag(name)},x={x0 - 2},y={FEET},z={door_z - 5},dx=4,dy=1,dz=4]'


add('den_barrier', 'den', '灵梦用树枝封洞口挡捕食种', '灵梦用树枝封洞口挡捕食种',
    '左洞灵梦智力90、自制90，右洞灵梦智力40、自制30，各自洞里有 2 根结界枝。各自的灵梦走进自己洞里后，该洞南面 20 格外出现饥饿蕾米莉亚，按正常捕食逻辑选目标追来：'
    '灵梦逃回自己的洞，聪明且自制的那只在洞口转身用约 2 秒把洞口格封上树枝叶屏（用掉 1 根枝，每格 1 根），右洞那只只逃进窝。'
    '捕食种不再把封好的洞里的个体当猎物，日志 [predator] target rejected reason=barrier；屏障挡住碰撞和寻路，玩家可徒手打掉（掉 1 根木棍）。'
    '捕食种离开约 10 秒后灵梦自己拆下屏障、把枝条放回洞口库存，最长 4 分钟自行朽散。读数看各灵梦 threat 的 seal=',
    '约 1–2 分钟，可倍速', [
        yk('reimu', 0, 6, 'wise', label='智力90·自制90', intelligence=90, self_control=90, lead=True, **pair_ready()),
        yk('reimu', 18, 6, 'plain', label='智力40·自制30', intelligence=40, self_control=30, **pair_ready()),
        home('wise', 0, -1), home('plain', 18, -1),
        f'yukkuri scenario twigs {sel("wise")} 2', f'yukkuri scenario twigs {sel("plain")} 2'],
    late=[Retry(yk('remilia', 0, 26, 'hunter_wise', label='饥饿捕食种', nutrition=10, energy=95), _den_guard('wise', 'family'), tries=40, every=20, first=240),
          Retry(yk('remilia', 18, 26, 'hunter_plain', label='饥饿捕食种', nutrition=10, energy=95), _den_guard('plain', 'indep'), tries=40, every=20, first=240)],
    env=DAY, spot='family', vantage=(9, 16), retry='先“清空本场地”（洞口格要空）再按一次',
    note='待验证：新场景，尚未在游戏里实测。',
    reads=[('读聪明灵梦', f'yukkuri status {sel("wise")} raw'), ('读普通灵梦', f'yukkuri status {sel("plain")} raw'),
           ('读左捕食者', f'yukkuri status {sel("hunter_wise")} raw'), ('读右捕食者', f'yukkuri status {sel("hunter_plain")} raw')])

# ====================================================================================================== vagrants and foraging
add('dig', 'forage', '野生：魔理沙在土丘挖洞安家', '野生：魔理沙在土丘挖洞安家', '没有家的野生成体走到土丘边，先挖通地面，再向上挖内部第二层。入口一格高、内部两格高，每块约 6 秒。',
    '约 3–6 分钟（可倍速）', [
        yk('marisa', 0, 0, 'vagrant', yaw=180, lead=True, **pair_ready()),
        'gamerule mobGriefing true'], env=DAY, spot='dig', vantage=(0, 6, 180),
    reads=[('读挖穴阶段', f'yukkuri status {sel("vagrant")} raw')])

_fm, _ff, _fc = uid('forage', 'mother'), uid('forage', 'father'), uid('forage', 'child')
add('forage', 'forage', '野生：一方外出觅食，一方照看孩子', '野生：一方外出觅食，一方照看孩子',
    '土丘洞储粮空了。较饿、胆大的灵梦去远处草场采草，魔理沙守洞口、照看赤油。看灵梦把草实际衔回放进粮堆，家里迎接归来，再用存粮喂赤油。',
    '数分钟（可倍速）', [
        yk('marisa', 0, -2, 'father', uuid=_ff, yaw=0, kin=f'Partner:{uuid_nbt(_fm)}', nutrition=98, energy=100,
           boldness=20, friendliness=100, attachment=95),
        yk('reimu', 0, 1, 'mother', uuid=_fm, yaw=180, lead=True, kin=f'Partner:{uuid_nbt(_ff)}', nutrition=88, energy=100,
           boldness=96, friendliness=100, attachment=95),
        yk('reimu', 1, -3, 'child', stage='baby', uuid=_fc, yaw=0, nutrition=50, self_control=96,
           kin=f'Mother:{uuid_nbt(_fm)},Father:{uuid_nbt(_ff)}'),
        ],
    late=[f'yukkuri scenario home {sel(n)} ~ ~ ~-1' for n in ('mother', 'father', 'child')],
    env=DAY, spot='forage', vantage=(0, 9, 180, 12),
    # The baby at home welcoming a parent back from the meadow (inside the mound, so the camera follows it).
    capture=dict(shots=(1, 40, 600), sequences=((900, 2700, 6),), observe=False,
                 views={'child': dict(follow=f'@e[tag={tag("child")},limit=1]', offset=(.5, 1.2, 1.2))}),
    reads=[('读留守魔理沙', f'yukkuri status {sel("father")} raw'), ('读外出灵梦', f'yukkuri status {sel("mother")} raw'),
           ('读赤油进食', f'yukkuri status {sel("child")} raw')])


_absent, _stayer, _older, _baby = (uid('family_absence', n) for n in ('absent', 'stayer', 'older', 'baby'))
_absence_kin = f'Mother:{uuid_nbt(_absent)},Father:{uuid_nbt(_stayer)}'
_absence_x, _absence_z = BY_ID['forage'].spots['forage']
# The death can occur only on the meadow side of the ridge, after a real departure. The ridge blocks sight from home.
_far_parent = (f'@e[tag={tag("absent")},x={_absence_x + 17},y={FEET - 1},z={_absence_z - 12},'
               'dx=18,dy=8,dz=24,limit=1]')
add('family_absence', 'forage', '出门采草的亲油迟迟不归', '出门采草的亲油迟迟不归',
    '灵梦亲油去土坡后的草场采草，在家人看不到的地方死亡。看魔理沙和孩子等多久才到洞口呼唤。'
    '能走远的子油沿记得的方向找一段再回头，赤油留在洞旁等。找不到后，看看两种年龄各自怎样继续生活。',
    '约 2–5 分钟（可倍速）', [
        box(9, 0, -5, 10, 2, 8, 'minecraft:dirt'),
        yk('reimu', 0, 3, 'absent', uuid=_absent, kin=f'Partner:{uuid_nbt(_stayer)}', nutrition=63, boldness=98),
        yk('marisa', 1, 3, 'stayer', uuid=_stayer, kin=f'Partner:{uuid_nbt(_absent)}', nutrition=92, energy=53, boldness=20,
           attachment=95, self_control=35),
        yk('reimu', 0, 4, 'older', stage='child', uuid=_older, kin=_absence_kin, nutrition=76, boldness=20, self_control=35,
           childhood=CHILDHOOD_HOPPING, lead=True),
        yk('marisa', 1, 4, 'baby', stage='baby', uuid=_baby, kin=_absence_kin, nutrition=75, childhood=CHILDHOOD_CRAWLING_READY),
        ],
    late=[*(f'yukkuri scenario home {sel(n)} ~ ~ ~-1' for n in ('absent', 'stayer', 'older', 'baby')),
          Retry(f'kill {_far_parent}', _far_parent, tries=150, every=20, first=120)],
    env=DAY, spot='forage', vantage=(0, 10, 180, 12),
    reads=[(label, f'yukkuri status {sel(n)} raw') for n, label in
           [('stayer', '读留守亲油'), ('older', '读子油寻亲'), ('baby', '读赤油等候')]])

add('graze', 'forage', '野生：饿了先吃苹果，只有草就啃草', '野生：饿了先吃苹果，只有草就啃草', '左边的饿灵梦身边既有苹果又有草，会先取普通食物；右边的饿魔理沙在八格外、只见草，啃下后会露出苦味反应。草仍会退回上一阶、日后重新长出',
    '约 10–60 秒', [
        yk('reimu', -6, 0, 'a', nutrition=28, yaw=180, lead=True), item('minecraft:apple', 1, -4, 0, name='apple'),
        yk('marisa', 6, 0, 'b', nutrition=34, yaw=180)], env=DAY, spot='graze', vantage=(0, 12))


def found_shell(kind, x, z):
    return [block(x + dx, dy, z + dz, f'mcyukkuri:{kind}[facing=south,part={dx + 2 + 5*(dz + 2) + 25*dy}]')
            for dy in range(2) for dz in range(-1, 2) for dx in range(-1, 2)]


def street_stray(scene, kind, x, z, name, **kwargs):
    former = uuid_nbt(uid(scene, 'former_owner'))
    return yk(kind, x, z, name, uuid=uid(scene, name), extra=f'YukkuriPet:{{FormerOwner:{former}}}', **kwargs)


def garbage_barrel(x, z, food, opened):
    return [
        block(x, 0, z, 'minecraft:barrel[facing=south,open=false]'),
        f'data merge block ~{x} ~ ~{z} {{NeoForgeData:{{YukkuriGarbage:1b}},Items:[{{Slot:0b,id:"{food}",count:1}}]}}',
        block(x, 1, z+1, f'minecraft:oak_trapdoor[facing=north,half=top,open={str(opened).lower()}]'),
    ]


_stray_mother = uid('stray_boxes', 'mother')
add('stray_boxes', 'forage', '流浪：母子找纸箱，木箱也能歇脚', '流浪：母子找纸箱，木箱也能歇脚',
    '夜里母子试着在纸箱落脚，赤油往里、亲油在开口。另一只流浪油可选旁边的旧木箱。',
    '约半分钟至一分钟', [
        *found_shell('cardboard_shelter', -5, -1), *found_shell('crate_shelter', 6, -1),
        street_stray('stray_boxes', 'reimu', -5, 3, 'mother', energy=28, rest_wait=0, lead=True),
        street_stray('stray_boxes', 'reimu', -5, 1, 'child', stage='baby', energy=48, rest_wait=0,
                     kin=f'Mother:{uuid_nbt(_stray_mother)}', childhood=CHILDHOOD_HOPPING),
        street_stray('stray_boxes', 'marisa', 6, 2, 'crate_stray', stage='child', energy=28, rest_wait=0),
    ], env={'time': 'night'}, spot='street', vantage=(0, 9),
    smoke=dict(ticks=1000, forbid=[r'\[habitat\][^\n]* home chosen '],
               expect=[r'\[habitat\][^\n]* found settled .*kind=CARDBOARD occupants=2',
                                  r'\[habitat\][^\n]* found settled .*kind=CRATE']))

add('stray_rain', 'forage', '流浪：纸箱湿透，先离开再塌', '流浪：纸箱湿透，先离开再塌',
    '看已经躲在箱里的流浪油歇脚。纸箱变湿、下陷，流浪油退到外面，最后留下真纸片。也可以手动拆箱看住户原地脱身。',
    '约一分钟', [
        *found_shell('cardboard_shelter', -4, -1),
        street_stray('stray_rain', 'marisa', -4, -1, 'rain_stray', energy=25, rest_wait=0, lead=True),
    ], env={'time': 'noon', 'weather': 'rain'}, spot='street', vantage=(-2, 6, 164, 12),
    smoke=dict(ticks=1300, forbid=[r'\[habitat\][^\n]* found ejected .*reason=rain_collapse'],
               expect=[r'\[habitat\][^\n]* found settled .*kind=CARDBOARD',
                                  r'\[habitat\][^\n]* found rain stage=1 -> 2',
                                  r'\[habitat\][^\n]* found leaving .*reason=soaked_or_unsafe',
                                  r'\[habitat\][^\n]* found exited ',
                                  r'\[habitat\][^\n]* found removed .*reason=rain_collapse']))

add('stray_rubbish', 'forage', '流浪：翻一口垃圾，盖住就放弃', '流浪：翻一口垃圾，盖住就放弃',
    '开盖桶里有苹果或废纸。看进食和衔纸放下。盖住的桶撞几下就放弃，可以手动开关盖板。',
    '约半分钟', [
        *garbage_barrel(-12, -1, 'minecraft:apple', True),
        *garbage_barrel(-26, -1, 'minecraft:paper', True),
        *garbage_barrel(12, -1, 'minecraft:apple', False),
        street_stray('stray_rubbish', 'reimu', -12, 3, 'open_stray', nutrition=24, boldness=80, lead=True),
        street_stray('stray_rubbish', 'reimu', -26, 3, 'paper_stray', nutrition=24, boldness=80),
        street_stray('stray_rubbish', 'marisa', 12, 3, 'closed_stray', nutrition=24, boldness=80),
    ], env=DAY, spot='street', vantage=(0, 10),
    smoke=dict(ticks=600, forbid=[r'\[forage\][^\n]* garbage ram .*count=4'],
               expect=[r'\[forage\][^\n]* garbage pickup item=[^\n]* food=true',
                                  r'\[meal\][^\n]* swallowed item=',
                                  r'\[forage\][^\n]* garbage pickup item=minecraft:paper [^\n]*food=false',
                                  r'\[habitat\][^\n]* drop item=minecraft:paper count=1 holder=mouth',
                                  r'\[forage\][^\n]* garbage gave_up .*reason=closed rams=3']))

_junk_scene = 'stray_junk_headwear'
add(_junk_scene, 'forage', '流浪：用垃圾顶替失去的头饰', '流浪：垃圾头饰',
    '捡起地上的垃圾头饰，分别右键低自尊与骄横的失饰者。观察戴假帽的同类被鼓腹嘲笑。照料菜单可取下假帽，真饰也可换回；遗体可潜行空手取下假帽。',
    '手动试戴与取回', [
        item('mcyukkuri:junk_headwear', 3, 0, 5, name='junk_supply'),
        street_stray(_junk_scene, 'reimu', -10, 7, 'quiet_stray', accessory=False, boldness=0,
                     friendliness=100, label='低自尊失饰者', lead=True),
        street_stray(_junk_scene, 'reimu', 10, 7, 'proud_stray', accessory=False, boldness=100,
                     friendliness=65, label='骄横失饰者'),
        street_stray(_junk_scene, 'marisa', 0, -2, 'covered_stray', accessory=False, boldness=30,
                     label='戴瓶盖的流浪油').replace('Accessory:0b,',
                     'Accessory:0b,HatCustody:{State:"LOST",JunkHeadwear:"bottle_cap"},'),
        street_stray(_junk_scene, 'reimu', 1, -2, 'mocking_stray', friendliness=20, boldness=65,
                     yaw=90, label='会嘲笑的邻居'),
    ], give=['mcyukkuri:reimu_ribbon'], env=DAY, spot='street', vantage=(0, 11),
    smoke=dict(ticks=310, forbid=[],
               commands=[(130, 'damage ' + sel('covered_stray') + ' 100 minecraft:generic_kill'),
                         (180, 'execute if entity @e[type=mcyukkuri:yukkuri_remains,distance=..12,nbt={Remains:{components:{"minecraft:custom_data":{JunkHeadwear:"bottle_cap"}}}}] run say MCYJUNK_REMAINS'),
                         (210, 'execute unless entity @e[type=minecraft:item,distance=..25,tag=!scn_junk_supply,nbt={Item:{id:"mcyukkuri:junk_headwear"}}] run say MCYJUNK_NO_DUPLICATE')],
               expect=[r'\[contempt\][^\n]* junk mocked .*variant=bottle_cap affinity=0 -> -1',
                       r'MCYJUNK_REMAINS', r'MCYJUNK_NO_DUPLICATE']))

add('stray_wind', 'forage', '流浪：钻进木板缝挡风', '流浪：钻进木板缝挡风',
    '冷夜里，流浪油可以缩进低木板下。纸片是地上的真物品。饿了再出来。',
    '约半分钟', [
        'fillbiome ~-10 56 ~-6 ~10 80 ~9 minecraft:snowy_plains',
        box(-4, 1, -1, -1, 1, 0, 'minecraft:spruce_planks'),
        box(-4, 0, -2, -1, 0, -2, 'minecraft:spruce_planks'),
        item('minecraft:paper', 1, -3, -1, name='wind_paper'),
        street_stray('stray_wind', 'reimu', -3, 3, 'wind_stray', energy=34, rest_wait=0, lead=True),
    ], env={'time': 'night'}, spot='street', vantage=(0, 9),
    smoke=dict(ticks=550, forbid=[r'\[habitat\][^\n]* home chosen ', r'\[sleep\][^\n]* woken by danger reason=environment'],
               expect=[r'\[habitat\][^\n]* wind cover settled .*paper=true']))

# ====================================================================================================== biome settlements
def settlement(plot, kind, seen):
    from museum_fields import BIOME_PLOTS
    entry = next(p for p in BIOME_PLOTS if p[0] == plot)
    title = entry[5]
    where = ('洞穴挖进北侧山坡：洞口朝南对着空地，洞顶是山坡本身的土石' if entry[6]
             else '该群系不自然生成聚落（群系适居裁定），此处用管理员命令放在平地上，只用来看寒冷／炎热季节生活，洞岸由土堆补起'
             if plot in ('snow', 'desert') else '平地上没有山坡，洞口两侧和洞顶由土堆补起（对照）')
    add(f'settle_{plot}', 'biomes', seen, seen,
        f'{title}的{"村落" if kind == "village" else "小群"}：{where}；南边低处有一处浅浅的嗯嗯坑（裸土凹地，无厕所方块）。季节随群系而定，成员、住所、仓堆随群系特色而不同',
        '数分钟（可倍速）', [f'yukkuri settlement spawn {kind} ~ ~ ~', LEAD_ANY], env=DAY, spot=plot,
        vantage=(0, 26), retry='先“清空本场地”再按一次',
        reads=[('读聚落场地与嗯嗯坑', 'yukkuri settlement site info'), ('读场地勘测', 'yukkuri settlement site check')],
        smoke=dict(ticks=3000, expect=[r'\[habitat\][^\n]* home chosen'], forbid=[r'\[body\][^\n]* invariant\b']))


for _plot, _kind, _seen in (('forest', 'village', '秋·森林村落，洞挖进山坡'), ('plains', 'village', '春·平原村落，平地补土做洞'),
                            ('taiga', 'band', '秋·针叶林：深洞小群，存粮更多'), ('cherry', 'band', '春·樱花林：落花瓣的软土洞岸'),
                            ('hills', 'band', '秋·风袭丘陵：岩石洞穴小群'), ('savanna', 'band', '夏·热带草原：干土洞岸小群'),
                            ('snow', 'band', '冬·雪原小群的寒冷生活'), ('desert', 'band', '夏·沙漠小群的炎热生活')):
    settlement(_plot, _kind, _seen)

_mixed_x, _mixed_z = BY_ID['biomes'].spots['plains']
add('settle_mixed_dens', 'biomes', '同一聚落的山洞与平地土丘洞', '山洞与平地土丘洞',
    '同一个聚落里，西边家洞挖进坡面，东边家洞在平地的圆土丘里。看两户从空地进出各自的洞口，在洞里休息和存粮',
    '数分钟（可倍速）', [
        # Keep the clearing and the east side level. The north bank backs the hall; the west bank backs one household.
        *[box(-3, y, -12, 3, y, -6 - y, 'minecraft:grass_block') for y in range(3)],
        *[box(-12, y, -2, -5 - y, y, 2, 'minecraft:grass_block') for y in range(3)],
        'yukkuri settlement spawn demo ~ ~ ~', LEAD_ANY], env=DAY, spot='plains', vantage=(0, 17),
    retry='先“清空本场地”再按一次', reads=[('读两种住所的场地勘测', 'yukkuri settlement site check')],
    smoke=dict(ticks=3000, expect=[r'\[habitat\][^\n]* home chosen',
               *[rf'\[habitat\][^\n]* home chosen [^\n]*home=BlockPos\{{x={_mixed_x + dx}, y={FEET}, z={_mixed_z}\}} bed='
                 for dx in (-4, 4)], r'MCYSMOKE_MIXED_DEN_ROOFS', r'MCYSMOKE_MIXED_DEN_SITE_OK'],
               forbid=[r'\[body\][^\n]* invariant\b'],
               commands=[(1, 'execute if block ~-5 ~2 ~ minecraft:grass_block if block ~-5 ~3 ~ minecraft:air '
                             'if block ~5 ~4 ~ minecraft:grass_block run say MCYSMOKE_MIXED_DEN_ROOFS'),
                         (2, 'execute store success score #mixed_den_site museum run yukkuri settlement site check'),
                         (3, 'execute if score #mixed_den_site museum matches 1 '
                             'if entity @e[tag=scn_lead,distance=..12] run say MCYSMOKE_MIXED_DEN_SITE_OK')],
               readout=['yukkuri settlement site check']))

# Three demonstrations on separate ground south of a vacant demo site, each with its own target: the size comparison, the
# merge/spill pair, and a stall that holds only the toilet and its user, so no actor can reach another's target.
add('waste_demo', 'biomes', '粪便管理：排量、合并、溢出、衰减与马桶', '粪便管理',
    '①排量：左边空地上，一只成油自己排一份（读数 1.0），身旁的新生赤油自己排的那份只有它的一小部分'
    '（读数约 0.006，走近看是一粒极小的屎）。'
    '②合并与溢出：右边那格先存着 7 份，两只成油各排一份：第一份把格子填满到 8 份，第二份溢到紧挨着的西邻格（1 份）。'
    '③马桶：最右边玻璃小间里的橙自己走进去坐下，存量由 7 份增至 8 份满。'
    '④衰减：约 30 秒时本场把随机刻速度临时调到 200，露天屎堆（前面那几处）随后陆续缩小消失；嗯嗯坑里那 1.5 份不动。'
    '⑤收取：走到坑边，潜行＋空手右键坑里那堆，只收走整份，坑内留下 0.5 份。',
    '约 2 分钟', [
        'yukkuri settlement site vacant demo ~ ~ ~',
        block(3, 0, 18, 'mcyukkuri:yukkuri_poop'), 'data merge block ~3 ~ ~18 {Volume:7.0d}',
        block(-7, 0, 14, 'mcyukkuri:yukkuri_poop'), 'data merge block ~-7 ~ ~14 {Volume:1.0d}',
        block(6, -1, 6, 'mcyukkuri:yukkuri_poop'), 'data merge block ~6 ~-1 ~6 {Volume:1.5d}',
        block(8, 0, 16, 'mcyukkuri:yukkuri_toilet'), 'data merge block ~8 ~ ~16 {Waste:7.0d}',
        # A three-high glass stall closes the toilet to every other actor and still lets the player watch in.
        box(7, 0, 15, 9, 2, 15, 'minecraft:glass_pane'),
        box(7, 0, 19, 9, 2, 19, 'minecraft:glass_pane'),
        box(7, 0, 16, 7, 2, 18, 'minecraft:glass_pane'),
        box(9, 0, 16, 9, 2, 18, 'minecraft:glass_pane'),
        # The adult stands nearer the viewpoint than the infant, so the shot's own measure is taken on a full-size body.
        yk('reimu', -2, 18, 'waste_demo_adult', bladder=95, yaw=180),
        yk('reimu', -4, 18, 'baby', stage='baby', bladder=95, yaw=180),
        # Each merge actor stands in its own cell just short of the shared edge, facing away: its rear outlet hangs over the heap cell.
        yk('reimu', 3.0, 17.2, 'merge_n', bladder=95, yaw=180),
        yk('chen', 8.0, 18.0, 'user', bladder=95, yaw=180)],
    env={**DAY, 'random_tick_window': (580, 1220, 200)}, spot='plains', vantage=(1, 25, 180, 8),
    retry='先“清空本场地”再按一次',
    note='约 30 秒至 90 秒提高随机刻速度演示露天衰减，随后恢复默认速度；随机刻只在你站在场边时才走。',
    late=[yk('reimu', 3.6, 18.0, 'merge_w', bladder=95, yaw=270)],
    reads=[('读合并那格（7→8 满格）', 'data get block ~3 ~ ~18 Volume'),
           ('读溢出的西邻格（1 份）', 'data get block ~2 ~ ~18 Volume'),
           ('读成油裸地那份（1.0）', 'data get block ~-2 ~ ~18 Volume'),
           ('读新生赤油那份（约 0.006）', 'data get block ~-4 ~ ~18 Volume'),
           ('读露天野堆（会衰减消失）', 'data get block ~-7 ~ ~14 Volume'),
           ('读嗯嗯坑存量（收取前 1.5，收取后 0.5）', 'yukkuri settlement site info'),
           ('读马桶存量（7→8 满）', 'data get block ~8 ~ ~16 Waste')],
    capture=dict(shots=(1, 40, 200, 600, 1400, 2000), sequences=((20, 200, 4),)),
    smoke=dict(ticks=900,
               expect=[r'\[needs\][^\n]*poop placed [^\n]*overflow=false', r'\[needs\][^\n]*poop placed [^\n]*overflow=true',
                       r'\[needs\] chen [^\n]*relieved source=facility',
                       r'MCYSMOKE_MERGE_GROWN', r'MCYSMOKE_SPILL_PLACED', r'MCYSMOKE_ADULT_UNIT', r'MCYSMOKE_BABY_SPEC',
                       r'MCYSMOKE_TOILET_FULL'],
               forbid=[r'\[body\][^\n]* invariant\b', r'\[body\][^\n]* in wall', r'\[body\][^\n]* death settled',
                       r'\[needs\] reimu [^\n]*relieved source=facility'],
               commands=[(500, 'execute store result score #waste_m museum run data get block ~3 ~ ~18 Volume 1000'),
                         (501, 'execute if score #waste_m museum matches 8000 run say MCYSMOKE_MERGE_GROWN'),
                         (502, 'execute store result score #waste_w museum run data get block ~2 ~ ~18 Volume 1000'),
                         (503, 'execute if score #waste_w museum matches 1000 run say MCYSMOKE_SPILL_PLACED'),
                         (504, 'execute store result score #waste_a museum run data get block ~-2 ~ ~18 Volume 1000'),
                         (505, 'execute if score #waste_a museum matches 900..1000 run say MCYSMOKE_ADULT_UNIT'),
                         (506, 'execute store result score #waste_b museum run data get block ~-4 ~ ~18 Volume 1000000'),
                         (507, 'execute if score #waste_b museum matches 1000..40000 run say MCYSMOKE_BABY_SPEC'),
                         (508, 'execute store result score #waste_t museum run data get block ~8 ~ ~16 Waste 10'),
                         (509, 'execute if score #waste_t museum matches 80 run say MCYSMOKE_TOILET_FULL')],
               readout=['data get block ~-7 ~ ~14 Volume', 'yukkuri settlement site info', 'gamerule randomTickSpeed']))

# The waste pit in use: a hill-foot band whose adults all need to go soon after they have moved in.
_PIT_ADULTS = '@e[type=#mcylab:yukkuri,distance=..30]'
_PIT_READS = [('读聚落场地、嗯嗯坑存量与奴隶', 'yukkuri settlement site info'), ('读群体成员与奴隶数', 'yukkuri settlement info')]
_TRIP = re.compile(r'(?:toilet pit trip start|pit slave return start) pit=BlockPos\{x=-?\d+, y=(-?\d+), z=-?\d+\}')


def pit_level(window):
    """The pit must sit at the field's standing level: 634e32879 dropped the scene's plot anchor, which planned the pit on a
    neighbouring plot's hill crest eight blocks up (y=72), so every trip and every slave walk ended in 'reason=no_path'."""
    for line in window.splitlines():
        match = _TRIP.search(line)
        if match and not FEET - 2 <= int(match[1]) <= FEET:
            return 'pit off the field level: ' + line.strip()
    return None


def pit_trip(window):
    problem = pit_level(window)
    if problem: return problem
    if re.search(r'toilet pit refused reason=no_path', window): return 'a member could not walk from its burrow or the slope to the pit'
    return None if re.search(r'toilet pit edge reached\b', window) else 'no member reached the pit edge'


def slave_token(window):
    found = re.search(r'\[settlement\] (\w+) #(\d+)[^\n]*pit slave ', window)
    return None if found is None else f'{found[1]} #{found[2]} '


def colony_death(window):
    found = re.search(r'\[body\][^\n]* death settled[^\n]*', window)
    return None if found is None else 'a colony member died: ' + found.group(0).strip()


def slave_survives(window):
    """Three real days with a supplied pit: the slave itself must not sweeten, break, starve or die, and no colony member dies."""
    problem = pit_level(window)
    if problem: return problem
    if not re.search(r'pit slave (?:eats droppings [^\n]*nutrition=|hungry reason=no_droppings)', window):
        return 'the slave neither took a portion nor reported an empty pit'
    token = slave_token(window)
    if token is None: return 'no slave line to identify the slave'
    ticks = [int(tick) for tick in re.findall(r' t=(\d+) ', window)]
    start, end = min(ticks), max(ticks)
    meals = [int(tick) for tick in re.findall(token.replace('#', r'\#') + r'uuid=\S+ t=(\d+)[^\n]*pit slave eats droppings', window)]
    for line in window.splitlines():
        if token in line and re.search(r'non-yukkuri onset|died of non-yukkuri|stage \w+ -> (?:sweetened|non_yukkuri_\w+)|starvation damage|death settled', line):
            return 'the slave fell: ' + line.strip()
    if not meals: return 'the slave never took a portion'
    if meals[-1] < start + (end - start) * 2 // 3: return f'the slave stopped eating at t={meals[-1]} of {start}..{end}'
    return colony_death(window)


def starves_without_pit_food(window):
    """No droppings in the pit: the slave must really go hungry and take real starvation damage (no conjured food)."""
    token = slave_token(window)
    if token is None: return 'no slave line to identify the slave'
    if not re.search(token.replace('#', r'\#') + r'uuid=\S+ t=\d+[^\n]*pit slave hungry reason=no_droppings', window):
        return 'the slave did not report an empty pit'
    if not re.search(token.replace('#', r'\#') + r'uuid=\S+ t=\d+[^\n]*starvation damage', window):
        return 'an empty pit cost the slave nothing'
    return colony_death(window)


add('settle_pit', 'biomes', '成员走到嗯嗯坑沿排泄', '成员走到嗯嗯坑沿排泄',
    '森林山脚的小群入住约 10 秒后，全员便意升到 90，约 80 秒时再升一次：看它们各自从洞里走到南边低处的嗯嗯坑，只站在坑沿、背对坑口排泄，'
    '不跳进凹地；每份排泄物真实落成坑格上的“嗯嗯”方块，堆的大小随格内体积连续变化（一格最多 8 份，满格后落到相邻的坑格、坑满再落到坑沿外一格）。'
    '不在洞口或空地上排。读数看坑内各格体积；日志 [needs] toilet pit trip start → toilet pit edge reached → poop placed → relieved source=pit',
    '约 1–3 分钟', ['yukkuri settlement spawn band ~ ~ ~', LEAD_ANY],
    env=DAY, spot='forest', vantage=(0, 16, 180), retry='先“清空本场地”再按一次', note='待验证：新场景，尚未在游戏里实测。',
    late=[Retry(f'execute as {_PIT_ADULTS} run yukkuri status set @s needs.bladder 90', _PIT_ADULTS, tries=1, first=200),
          Retry(f'execute as {_PIT_ADULTS} run yukkuri status set @s needs.bladder 90', _PIT_ADULTS, tries=1, first=1600)],
    reads=[*_PIT_READS, ('读一只成员的便意', 'yukkuri status @e[tag=scn_lead,limit=1,distance=..40] raw')],
    capture=dict(shots=(1, 40, 200, 600, 1600, 2000), sequences=((240, 440, 2), (1640, 1740, 2))),
    smoke=dict(ticks=2600, expect=[r'\[needs\][^\n]* toilet pit trip start\b'],
               forbid=[r'\[body\][^\n]* invariant\b'], verify=pit_trip))

# The same pit with one grown member already its slave (record set the way five rejections leave it, hat gone for good):
# nothing is scripted after that: the goal, the walk back, the meals and the others' reactions are the ordinary ones.
_DEMO_SUBJECT = '@e[type=#mcylab:yukkuri,tag=mcyukkuri.demo_subject,distance=..30,limit=1,sort=nearest]'
_SLAVE = '@e[tag=scn_slave,distance=..70,limit=1]'
_HARSH = '@e[type=#mcylab:yukkuri,tag=!scn_slave,distance=..70]'
_COLONY_RELIEVES = 'execute as @e[type=#mcylab:yukkuri,distance=..30,limit=1,sort=random] run yukkuri status set @s needs.bladder 90'
# Food for a whole multi-day window cannot be dropped items (they despawn after five minutes): it is the real meadow of the
# plot's bare ground outside the settlement's own little grazing ring. One tuft gives one 20-point meal and grows back in
# half a day, so about two thousand tufts carry five or six adults (one meal each ~500 ticks) through the run; nothing is
# refilled by a command. The burrow hill stays bare, so a member that walks up it still has to come down to eat.
_PIT_MEADOW = ['fill ~-30 64 ~12 ~30 64 ~30 minecraft:short_grass keep',
               'fill ~-30 64 ~-11 ~-12 64 ~11 minecraft:short_grass keep',
               'fill ~12 64 ~-11 ~30 64 ~11 minecraft:short_grass keep']
add('settle_pit_slave', 'biomes', '嗯嗯奴隶在坑里吃排泄物', '嗯嗯奴隶在坑里吃排泄物',
    '同一处山脚小群（首领、一家三口、一家两口），第一户的第二位成体在入住约 6 秒后失去头饰并成为嗯嗯奴隶（只改群内身份，不指定行为）：看它被留在南边的嗯嗯坑里，被带出坑（如清扫指派）后自己走回；'
    '它在坑里时，自己的家事（找孩子、安抚）让位给坑，幼体由其他成员照看；饿了吗（营养≤50）才从坑里的嗯嗯方块取走一份吃掉，没有排泄物就真的挨饿；其他成员去坑边排泄时，在场的严厉成员会露出嫌恶、奴隶含泪并弄脏。'
    '这只奴隶长期不改身份：不安停在“不安”一档（含泪、脏），不会甜化、疯狂致死；没有排泄物时才真的挨饿。'
    '读数看奴隶营养、卫生与不安；日志 [settlement] pit slave …、pit mockery；[meal] swallowed item=mcyukkuri:yukkuri_waste',
    '约 2–5 分钟（可倍速）', ['fillbiome ~-22 56 ~-22 ~22 100 ~22 minecraft:forest', 'yukkuri settlement spawn demo ~ ~ ~',
                       f'tag {_DEMO_SUBJECT} add scn_slave', *_PIT_MEADOW, LEAD_ANY],
    env=DAY, spot='forest', vantage=(0, 16, 180), retry='先“清空本场地”再按一次', note='待验证：新场景，尚未在游戏里实测。',
    late=[Retry(f'yukkuri status set {_SLAVE} accessory.state burn', _SLAVE, tries=1, first=140),
          Retry(f'yukkuri settlement slave {_SLAVE}', _SLAVE, tries=1, first=180),
          # The colony that keeps a slave is the harsh, dim-witted one that cannot see past the missing hat.
          Retry(f'execute as {_HARSH} run yukkuri status set @s personality.friendliness 20', _PIT_ADULTS, tries=1, first=200),
          Retry(f'execute as {_HARSH} run yukkuri status set @s personality.intelligence 30', _PIT_ADULTS, tries=1, first=210),
          Retry(_COLONY_RELIEVES, _PIT_ADULTS, tries=1, first=600),
          Retry(_COLONY_RELIEVES, _PIT_ADULTS, tries=1, first=1600),
          Retry(_COLONY_RELIEVES, _PIT_ADULTS, tries=1, first=2600)],
    reads=[*_PIT_READS, ('读奴隶（营养、便意、卫生、不安）', f'yukkuri status {_SLAVE} raw')],
    # The meal only follows a real heap: let the others fill the pit first, then make the slave hungry. Three in-game days
    # of real days follow, with the colony relieving at the pit as it does by itself; the slave must still be alive and sane.
    smoke=dict(ticks=72000,
               commands=[(300, f'execute as {_SLAVE} run yukkuri status set @s needs.nutrition 20')]
                        + [(tick, _COLONY_RELIEVES) for tick in range(1200, 72000, 2400)],
               expect=[r'\[settlement\][^\n]* pit slave back in pit\b', r'\[mind\][^\n]* stage calm -> uneasy \(unease 3'],
               forbid=[r'pit slave meal refused', r'\[body\][^\n]* invariant\b', r'hatless outcome role=MEMBER'],
               verify=slave_survives))

# The honest other half: a pit nobody has used yet holds nothing, and the slave set to an empty stomach really starves.
add('settle_pit_famine', 'biomes', '坑里没排泄物，奴隶挨饿', '坑里没排泄物，奴隶挨饿',
    '同一处山脚小群（首领与三名成年成员，无幼体）刚入场：第一户的第二位成体为奴隶，坑里还没有任何排泄物，奴隶的营养为 0（就像它已把最后一份吃完）。'
    '看它在坑里找不到食物（pit slave hungry reason=no_droppings）、营养停在 0，约一分钟后开始真实饿伤（starvation damage，生命逐点下降）；'
    '没有人给它补食，也没有凭空生成的一份。',
    '约 1–2 分钟（可倍速）', ['fillbiome ~-22 56 ~-22 ~22 100 ~22 minecraft:forest', 'yukkuri settlement spawn demo_adults ~ ~ ~',
                       f'tag {_DEMO_SUBJECT} add scn_slave', *_PIT_MEADOW, LEAD_ANY],
    env=DAY, spot='forest', vantage=(0, 18), retry='先“清空本场地”再按一次', note='待验证：新场景，尚未在游戏里实测。',
    late=[Retry(f'yukkuri status set {_SLAVE} accessory.state burn', _SLAVE, tries=1, first=140),
          Retry(f'yukkuri settlement slave {_SLAVE}', _SLAVE, tries=1, first=180),
          Retry(f'execute as {_HARSH} run yukkuri status set @s personality.friendliness 20', _PIT_ADULTS, tries=1, first=200),
          Retry(f'execute as {_HARSH} run yukkuri status set @s personality.intelligence 30', _PIT_ADULTS, tries=1, first=210)],
    reads=[*_PIT_READS, ('读奴隶（营养、生命、坑内份数）', f'yukkuri status {_SLAVE} raw')],
    smoke=dict(ticks=6000,
               commands=[(300, f'execute as {_SLAVE} run yukkuri status set @s needs.nutrition 0')],
               expect=[r'pit slave hungry reason=no_droppings', r'starvation damage'],
               forbid=[r'pit slave eats droppings', r'\[body\][^\n]* invariant\b'],
               verify=lambda window: pit_level(window) or starves_without_pit_food(window)))

# The origin loop with no scene special case: one grown member loses its accessory for good and the colony's own harsh
# members reject it, the fifth rejection makes it the colony's 嗯嗯奴隶. Nothing is scripted after the hat is gone.
_TARGET = '@e[tag=scn_target,distance=..70,limit=1]'
_OTHERS = '@e[type=#mcylab:yukkuri,tag=!scn_target,distance=..70]'
add('settle_pit_drive', 'biomes', '失饰成员被拒五次成奴隶', '失饰成员被拒五次成奴隶',
    '同一处山脚小群（首领与三名成年成员，无幼体）：约 6 秒后第一户的第二位成体失去头饰（永久），其余成年成员都是严厉、认不出无头饰者的性格。'
    '看它被同群成员一次次拒斥（读数里 role 从 MEMBER 到 SHUNNED），第五次后成为嗯嗯奴隶并自己走进南边嗯嗯坑；'
    '每群至多一只：已有奴隶的聚落里再失饰的成员只会被驱离。'
    '读数看目标与群体的角色，以及成为坑奴时升到 3 级的脏污；日志 [settlement] hatless outcome role=UNUN_SLAVE …、pit slave …',
    '约 2–4 分钟（可倍速）', ['fillbiome ~-22 56 ~-22 ~22 100 ~22 minecraft:forest', 'yukkuri settlement spawn demo_adults ~ ~ ~',
                       f'tag {_DEMO_SUBJECT} add scn_target', *_PIT_MEADOW, LEAD_ANY],
    env=DAY, spot='forest', vantage=(0, 18), retry='先“清空本场地”再按一次', note='待验证：新场景，尚未在游戏里实测。',
    late=[Retry(f'yukkuri status set {_TARGET} personality.friendliness 70', _TARGET, tries=1, first=130),
          Retry(f'yukkuri status set {_TARGET} personality.self_control 60', _TARGET, tries=1, first=135),
          Retry(f'yukkuri status set {_TARGET} personality.attachment 60', _TARGET, tries=1, first=140),
          Retry(f'execute as {_OTHERS} run yukkuri status set @s personality.friendliness 44', _PIT_ADULTS, tries=1, first=145),
          Retry(f'execute as {_OTHERS} run yukkuri status set @s personality.self_control 100', _PIT_ADULTS, tries=1, first=146),
          Retry(f'execute as {_OTHERS} run yukkuri status set @s personality.intelligence 30', _PIT_ADULTS, tries=1, first=150),
          Retry(f'yukkuri status set {_TARGET} accessory.state burn', _TARGET, tries=1, first=160),
          Retry(_COLONY_RELIEVES, _PIT_ADULTS, tries=1, first=400),
          Retry(_COLONY_RELIEVES, _PIT_ADULTS, tries=1, first=1400),
          Retry(_COLONY_RELIEVES, _PIT_ADULTS, tries=1, first=2400),
          Retry(_COLONY_RELIEVES, _PIT_ADULTS, tries=1, first=3400)],
    reads=[*_PIT_READS, ('读目标的角色、脏污与身份', f'yukkuri status {_TARGET} raw')],
    smoke=dict(ticks=20000,
               commands=[(tick, _COLONY_RELIEVES) for tick in range(400, 20000, 600)],
               readout=[f'yukkuri status {_TARGET} raw'],
               expect=[r'\[settlement\][^\n]* hatless outcome role=UNUN_SLAVE\b', r'\[settlement\][^\n]* pit slave (?:return start|back in pit)\b'],
               forbid=[r'hatless outcome role=OUTCAST', r'\[body\][^\n]* invariant\b'],
               # The drive's own subject is the role and the walk into the pit; a colony death is reported last.
               verify=lambda window: pit_level(window) or colony_death(window)))

# ====================================================================================================== daily life meadow
def _bed_walk_verify(log, crossing_limit=4, crossing_window=None):
    crossings = {}
    for actor, tick in re.findall(r'\[home\] \w+ #\d+ uuid=(\S+) t=(\d+)[^\n]*bed crossing[^\n]*source=walk', log):
        recent = crossings.setdefault(actor, [])
        recent[:] = [at for at in recent if crossing_window is None or int(tick) - at <= crossing_window]
        recent.append(int(tick))
        if len(recent) > crossing_limit:
            return f'walker {actor} exceeded {crossing_limit} pad crossings' + (f' within {crossing_window} ticks' if crossing_window is not None else '')
    stalls = {}
    for actor, tick, point in re.findall(r'\[move\] \w+ #\d+ uuid=(\S+) t=(\d+)[^\n]*ground path stopped reason=no_progress[^\n]* at=\(([^)]+)\)', log):
        at = tuple(float(value.strip()) for value in point.split(','))
        previous = stalls.get(actor)
        if previous and sum((a-b)**2 for a, b in zip(at, previous[1])) < .03**2:
            if int(tick) - previous[0] >= 600:
                return f'walker {actor} repeatedly stalled at {at} for at least 600 ticks'
        else:
            stalls[actor] = (int(tick), at)
    return None


def _sleep_doorway_verify(log):
    stalled = _bed_walk_verify(log, 2)
    if stalled:
        return stalled
    sleepers = re.findall(r'\[sleep\] \w+ #\d+ uuid=(\S+)[^\n]*begin source=life occupancy=INSIDE_SUPPORTED', log)
    for actor in sleepers:
        directions = set(re.findall(rf'\[home\] \w+ #\d+ uuid={re.escape(actor)}[^\n]*bed crossing[^\n]*direction=(in|out) side=north opening=true source=walk', log))
        if directions != {'in', 'out'}:
            return f'sleeper {actor} lacks its own observed north doorway entry/exit: {directions}'
    return None if sleepers else 'no sleeper inside its bed'


add('sleep', 'meadow', '困了走回有顶的窝睡觉', '困了走回有顶的窝睡觉',
    '灵梦困了，自己走回有顶的窝里睡觉；睡足醒来先在窝里伸一个长懒腰、打哈欠，再抖抖身子起来（饿醒的直接起身）', '约 1 分钟', [
    *shed(7, 0), yk('reimu', 0, 0, 'a', energy=95, rest_wait=0, yaw=-90, lead=True), home('a', 7, 0)], vantage=(7, -7, 0, 35), env=DAY,
    # The home is set two seconds after the summon, so the tiredness comes only once the nest is hers (energy below the rest
    # threshold, above the in-place nap's 25).
    late=[Retry(f'execute as {sel("a")} run yukkuri status set @s needs.energy 30', sel('a'), tries=1, first=160)],
    # The bed's own doorway is its north side: the body walks round to it, steps in, sleeps, then steps out through it again.
    # Second view: in front of her face, for the waking stretch and yawn (from the deck she lies with her back to it).
    capture=dict(shots=(1, 40, 2600), sequences=((200, 1500, 5),), observe=False,
                 views={'': dict(), 'face': dict(follow=f'@e[tag={tag("a")},limit=1]', offset=(0, .7, 1.3))}),
    smoke=dict(ticks=2600, forbid=[r'\[home\][^\n]*nest_not_inside\b', r'\[home\][^\n]*reason=doorway_unreachable\b',
                                  r'\[home\][^\n]*bed crossing[^\n]*opening=false source=walk'],
               expect=[r'\[sleep\] [^\n]*begin source=life occupancy=INSIDE_SUPPORTED',
                       r'\[sleep\] [^\n]*end source=life[^\n]*stretched=true',
                       r'\[home\][^\n]*bed crossing[^\n]*direction=in side=north opening=true',
                       r'\[home\][^\n]*bed crossing[^\n]*direction=out side=north opening=true'],
               readout=[f'yukkuri status {sel("a")} raw'], verify=_sleep_doorway_verify))

add('fatigue', 'weather', '困极原地睡着，打盹与惊醒', '困极原地睡着，打盹与惊醒',
    '前排胆量10／90、精力0，原地睡约12／28秒；后排精力20，缓慢并打盹1–3秒，两次间隔至少15秒、平均约32秒（“读短打盹”的 gap 为剩余间隔）。用天气按钮下雨或手动轻击正在睡的个体，应立即醒来且困值最多50。睡着时捕食种靠近不会被感知，实际攻击仍唤醒',
    '先看两只睡眠时长；再观察后排约1–2分钟，可清场后试雨或轻击', [
        yk('reimu', -5, 0, 'timid', label='胆小·困值100', energy=0, boldness=10, lead=True),
        yk('reimu', 5, 0, 'bold', label='胆大·困值100', energy=0, boldness=90),
        yk('chen', 0, 5, 'drowsy', label='困值80·短打盹', energy=20)],
    env=DAY, vantage=(0, 14), reads=[(label, f'yukkuri status {sel(name)} raw') for label,name in
        (('读胆小睡眠','timid'),('读胆大睡眠','bold'),('读短打盹','drowsy'))])

add('fatigue_predator', 'weather', '睡着时捕食种靠近，咬了才醒', '睡着时捕食种靠近，咬了才醒',
    '先传送到睡者附近观察自然入睡；12秒后20格外生成饥饿蕾米莉亚，按正常捕食逻辑选目标和接近。睡眠期间不因接近而惊醒，真实咬伤应立即唤醒，困值高于50时退到50；没有强制指定猎物或Goal',
    '看睡眠→真实接近→咬伤与惊醒；捕食未发生时读捕食者拒绝原因', [
        yk('reimu', 0, 0, 'sleeper', label='困值100·睡者', energy=0, boldness=90, lead=True)],
    env=DAY, vantage=(8, 14),
    late=[Retry(yk('remilia',0,20,'hunter',label='饥饿捕食种',nutrition=10,energy=95),sel('sleeper'),tries=1,first=240)],
    reads=[('读睡者',f'yukkuri status {sel("sleeper")} raw'),('读捕食者',f'yukkuri status {sel("hunter")} raw')])

add('nutrition_age', 'body', '各年龄饿得快慢，饿到0变虚弱', '各年龄饿得快慢，饿到0变虚弱',
    '前排赤／子／亚成／成体均营养50，无食物；同一代谢周期扣0.12／0.36／1／2。后排同种同体型灵梦对照：左营养0获得虚弱I与缓慢II（移速属性减30%），右营养100。左每60秒掉1血但不受击惊跳；补食后效果自然到期。读移速属性、原版效果与需求倍率；不要拿天生较快的橙和灵梦比较',
    '12秒比较代谢；至少60秒看饥饿掉血', [
        *[yk('reimu', -9+6*i, 0, 'age_'+stage, label=label, stage=stage, nutrition=50, energy=95, lead=i==0)
          for i,(stage,label) in enumerate((('baby','赤油'),('child','子油'),('teen','亚成体'),('adult','成体')))],
        yk('reimu', -5, 7, 'starving', label='营养0·虚弱缓慢', nutrition=0, energy=95),
        yk('reimu', 5, 7, 'fed', label='营养100·同种对照', nutrition=100, energy=95)],
    env=DAY, give=['minecraft:apple 4', 'mcyukkuri:magnifying_glass'], vantage=(0, 16),
    reads=[(f'读{stage}',f'yukkuri status {sel("age_"+stage)} raw') for stage in ('baby','child','teen','adult')]
        + [('读饥饿者',f'yukkuri status {sel("starving")} raw'),('读同种对照',f'yukkuri status {sel("fed")} raw'),('读原版效果',f'data get entity {sel("starving")} active_effects')])

add('meet', 'meadow', '熟的走近蹭蹭，生的先问候', '熟的走近蹭蹭，生的先问候',
    '北边一对橙和爱丽丝互相认识、想亲近，会自己走近、问候、蹭蹭；南边一对橙和爱丽丝素不相识（亲近度 0），一只走过去时另一只停下看着它，接触时先问候、对方回礼，'
    '之后读数里出现彼此的关系、亲近度大于 0；再靠近看陌生的两只能否主动问候中立的你', '持续观察约 2 分钟（暂停不计时）', [
    yk('chen', -2, -3, 'a', yaw=-90, affection=95, social_wait=0, lead=True),
    yk('alice', 2, -3, 'b', yaw=90, affection=95, social_wait=0),
    f'yukkuri scenario bond {sel("a")} {sel("b")}',
    yk('chen', -2, 3, 'stranger_a', label='陌生橙', yaw=-90, attachment=95, affection=0, social_wait=0),
    yk('alice', 2, 3, 'stranger_b', label='陌生爱丽丝', yaw=90, attachment=95, affection=0, social_wait=0)],
    env=DAY, vantage=None, give=['mcyukkuri:field_notebook'],
    capture=dict(shots=(1, 40, 600), sequences=((20, 400, 4),), observe=False,
                 views={'bonded': dict(follow=f'@e[tag={tag("a")},limit=1]', offset=(3, 1.6, 3),
                                      criteria='熟识的一对连续走近、轮流问候，侧贴蹭蹭时身体接触而不重叠。'),
                        'strangers': dict(follow=f'@e[tag={tag("stranger_a")},limit=1]', offset=(3, 1.6, 3),
                                         criteria='陌生的一对接触时发起者问候，对方问候回礼；正在休闲时点头后继续。')}),
    reads=[('读熟识一对', f'execute as {sel("a")} run yukkuri status @s raw'),
           ('读陌生橙的首次接触', f'yukkuri status {sel("stranger_a")} raw'), ('读陌生爱丽丝的首次接触', f'yukkuri status {sel("stranger_b")} raw')])

_CLOSE_GREETERS = {name: uid('greet_close', name) for name in ('wide', 'bean')}

add('greet_close', 'meadow', '贴近的豆形子油与成油打招呼', '异体型近距问候',
    '帕秋莉成油和豆形灵梦子油站得太近。看成油先腾出一点位置，再前倾问候。'
    '子油原地回礼，两只的脚底在问候和回礼时都应停在原处。',
    '约半分钟', [
        yk('patchouli', 0, 0, 'greet_wide', yaw=-90, affection=0, attachment=95, social_wait=0,
           lead=True, uuid=_CLOSE_GREETERS['wide']),
        yk('reimu', .64, 0, 'greet_bean', yaw=90, stage='teen', congenital='BEAN_BODY', affection=0,
           uuid=_CLOSE_GREETERS['bean'])],
    env=DAY, vantage=(0, 4, 180, 15),
    reads=[('读两只的站位和动作', 'yukkuri status @e[tag=scn,distance=..12] raw')])

# Three hungry-ish bodies, each with an apple at its feet: the bite takes each past full (nutrition 60 + 20 > 75). The adult and the
# child melt in contentment; the composed adult (self-control 95) keeps to the ordinary after-meal face.
_content_ids = {name: uid('meal_content', name) for name in ('adult', 'child', 'composed')}


def _meal_content_verify(log):
    """The adult and the child melt after the filling bite; the composed adult goes full without melting."""
    ids = {k: _uuid_of(v) for k, v in _content_ids.items()}
    for name in ('adult', 'child'):
        if not re.search(rf'\[meal\][^\n]*uuid={ids[name]}[^\n]*full nutrition=[^\n]*content=true', log):
            return f'{name} never melted after a filling bite'
    if not re.search(rf'\[meal\][^\n]*uuid={ids["composed"]}[^\n]*full nutrition=[^\n]*content=false reason=composed', log):
        return 'the composed adult did not go full with composure'
    return None


add('meal_content', 'meadow', '吃饱了原地瘫软一会儿', '吃饱了原地瘫软一会儿',
    '三只脚边各有一个苹果：左边营养 60 的成年灵梦吃下后营养过了 75（饱了），原地慢慢压扁、眯眼满足一会儿再起身；中间营养相同的子油也会；'
    '右边自制 95 的稳重灵梦吃饱后只做普通的饭后表情、不瘫软。刚瘫软过的个体隔一会儿才会再瘫软；苦草、辣、有毒或坐在帽船里吃饱不瘫软',
    '约 10–30 秒', [
        yk('reimu', -3, 0, 'adult', uuid=_content_ids['adult'], label='成年·自制70', nutrition=60, lead=True),
        item('minecraft:apple', 1, -3, -1, name='apple_adult'),
        yk('reimu', 0, 0, 'child', uuid=_content_ids['child'], stage='child', label='子油', nutrition=60),
        item('minecraft:apple', 1, 0, -1, name='apple_child'),
        yk('reimu', 3, 0, 'composed', uuid=_content_ids['composed'], label='成年·自制95', nutrition=60, self_control=95),
        item('minecraft:apple', 1, 3, -1, name='apple_composed')],
    env=DAY, vantage=(0, 4), note='待验证：新场景，尚未在游戏里实测。',
    capture=dict(shots=(1, 40, 300), sequences=((50, 190, 3),), observe=False,
                 views={'': dict(), 'adult': dict(follow=f'@e[tag={tag("adult")},limit=1]', offset=(0, .9, 2.4)),
                        'composed': dict(follow=f'@e[tag={tag("composed")},limit=1]', offset=(0, .9, 2.4))}),
    reads=[('读成年灵梦', f'yukkuri status {sel("adult")} raw'), ('读稳重灵梦', f'yukkuri status {sel("composed")} raw')])

# Two wounded young or mates, each beside the one who knows it: a mate (north, each with a nest of her own: homeless adults are
# busy looking for a den and would not come to lick) and a mother with her child (south).
_hurt_victims = {'a': uid('hurt', 'a'), 'kid': uid('hurt', 'kid')}


def _two_lickers(window):
    # Helping is the helper's own roll (`help declined` is a real refusal): each wounded one gets that decision or a lick.
    decided = set(re.findall(r'\[comfort\][^\n]* (?:mouth contact started|help declined); victim=([0-9a-f-]{36})', window))
    wanted = {_uuid_text('hurt', slot) for slot in _hurt_victims}
    return None if wanted <= decided else f'no lick or refusal for: {sorted(wanted - decided)}'


add('hurt', 'meadow', '受伤后伴侣和亲油来舔伤', '受伤后伴侣和亲油来舔伤',
    '北边的橙身上有一处磕伤，旁边是认得它的伴侣爱丽丝；南边小橙磕伤了，旁边是它的亲油灵梦。惊慌过去后，伴侣和亲油都会走近舔舐：完整舔舐让最老的一处 1–2 级伤降一级并回 2 血，三级伤只安抚。橙汁治伤补满血；面粉糊只修疤',
    '先看舔舐；橙汁治伤，若愈合留疤再用面粉糊', [
        *shed(0, -4, half=1), *shed(4, -4, half=1),
        yk('chen', 0, 0, 'a', uuid=_hurt_victims['a'], lead=True),
        yk('alice', 4, 0, 'mate', yaw=90, **pair_ready()),
        home('a', 0, -4), home('mate', 4, -4),
        f'yukkuri scenario bond {sel("a")} {sel("mate")}',
        f'yukkuri scenario mate {sel("a")} {sel("mate")}',
        f'yukkuri scenario wound {sel("a")}',
        yk('reimu', 0, 7, 'mom', yaw=-90, **pair_ready()),
        yk('chen', 4, 7, 'kid', stage='child', uuid=_hurt_victims['kid'], yaw=90, nutrition=90, energy=95),
        f'yukkuri scenario kin {sel("mom")} {sel("kid")} false',
        f'yukkuri scenario wound {sel("kid")}'], give=['mcyukkuri:orange_juice 2', 'mcyukkuri:flour_paste 2'], vantage=None,
    capture=dict(shots=(1, 40, 200, 600, 1800), sequences=((60, 1800, 12),), observe=False,
                 views=dict(wound=dict(follow='@e[tag=scn_a,limit=1]', offset=(.8, .65, 2.3),
                                       criteria='看清橙正面的磕伤与伴侣靠近舔舐的接触，保留双方身体和伤口。'))),
    reads=[('读受伤的橙（伴侣）', f'yukkuri status {sel("a")} raw'), ('读受伤的小橙（亲子）', f'yukkuri status {sel("kid")} raw')])

add('toilet', 'meadow', '便意急了走去厕所', '便意急了走去厕所', '橙有强烈便意：看它走向右边的厕所；拆掉厕所再看它在地面排便（从油菊排出）', '约 1 分钟', [
    block(6, 0, 0, 'mcyukkuri:yukkuri_toilet'), yk('chen', 0, 0, 'a', bladder=95, yaw=-90, lead=True)], vantage=None,
    reads=[('读厕所内排泄物体积', 'data get block ~6 ~ ~ Waste')],
    smoke=dict(ticks=1200,expect=[r'relieved source=facility',r'toilet stored [^\n]*volume='],forbid=[]))

# ====================================================================================================== family and growth
# Newborns carry no scn tag; the selector finds them by stage instead (the function runs positioned at the anchor).
NEWBORNS = '@e[type=#mcylab:yukkuri,distance=..48,nbt={YukkuriFamily:{Stage:"BABY"}}]'


def _caught_birth_release_verify(log):
    events = list(re.finditer(r'\[(family|headwear)\] \w+ #\d+ uuid=(\S+)[^\n]*', log))
    caught = [e for e in events if ' birth landed ' in e[0] and 'outcome=HAT' in e[0]]
    if not caught:
        return 'no newborn caught in hat'
    for birth in caught:
        child = birth[2]
        drops = [e for e in events if e.start() > birth.start() and ' carry put down ' in e[0]
                 and re.search(r'\bchild=' + re.escape(child) + r'\b', e[0])]
        if not drops or not re.search(r'\bcause=release\b', drops[0][0]):
            return f'caught newborn lacks physical release: {child}'
        release = drops[0]
        recovered = next((e for e in events if e.start() > birth.start() and e[2] == release[2]
                          and 'custody DEPLOYED -> WORN' in e[0]), None)
        if recovered is None or recovered.start() < release.start():
            return f'caught newborn not released before first hat recovery: {child}'


_LAND_BIRTH_SMOKE = dict(ticks=6000, verify=_caught_birth_release_verify,
    expect=[r'\[family\][^\n]* birth landed [^\n]*outcome=HAT\b',
            r'\[action\][^\n]* end id=take_prop outcome=DONE detail=prop=hat worn=true\b',
            r'\[intent\][^\n]* end id=take_hat result=DONE\b'],
    forbid=[r'\[body\][^\n]* invariant\b', r'\[family\][^\n]* carry put down [^\n]*\bforward=-[0-9]',
            r'\[hat\][^\n]* settled rider=', r'catch cleanup gave up', r'death settled', r'starvation damage accepted=true'],
    readout=[f'yukkuri status {sel("mother")} raw', f'yukkuri status {sel("father")} raw',
             f'yukkuri status {NEWBORNS} raw'])


def land_birth(sid, title, hint, outcome, father_kind, watch, spot, smoke=None, wait='约 1–2 分钟临产，之后看照护', capture=None, sizes=''):
    add(sid, 'family', title, hint, watch, wait, [
        *shed(8, 0),
        yk_on_nest('reimu', 8, 0, 'mother', yaw=-90, lead=True, **pair_ready()),
        yk(father_kind, 4, 1, 'father', yaw=-90, **pair_ready()),
        f'yukkuri scenario bond {sel("mother")} {sel("father")}',
        home('mother', 8, 0),
        f'yukkuri scenario birth {sel("mother")} {sel("father")} {outcome}{sizes}'], spot=spot, vantage=(4, 12),
        reads=[('读母油产程', f'yukkuri status {sel("mother")} raw'), ('读父油托帽', f'yukkuri status {sel("father")} raw'),
               ('读所有赤油', f'yukkuri status {NEWBORNS} raw')], smoke=smoke, capture=capture)


land_birth('birth_land_caught', '父油摘帽接住新生儿再戴回', '父油摘帽接住新生儿再戴回', 'caught', 'marisa',
           '父油（戴帽魔理沙）察觉临产，先走到母油身边、双方走到共同选定的安全位置（途中不被普通觅食或闲逛抢走），父油守在母油身旁看着她，快生了才摘帽翻面放好，放帽后站定看着母油、不来回抖动（日志 [choreo] catcher attending）；母油走到实际帽后方并转向，到位后生产，新生儿落进帽里；之后逐只取出幼体（嘴够得着就叼，够不着的转身伸辫卷起，日志 [action] start id=grab reach=mouth／braid）、放到帽外地面，再走回放帽站位戴回帽子（摘帽、戴帽时帽子挂在辫梢随动作移动，地上的帽在辫子托起时消失、放下时出现，不重复、不瞬移）', 'land_caught', smoke=_LAND_BIRTH_SMOKE, wait='约 30 秒临产，之后看照护',
           # Include attending, birth, supported releases and empty-hat recovery.
           capture=dict(shots=(1, 40, 200, 420, _LAND_BIRTH_SMOKE['ticks']),
                        sequences=((40, 2400, 4), (2400, _LAND_BIRTH_SMOKE['ticks'], 20)), observe=False,
                        follow='@e[tag=scn_father,limit=1]', offset=(1.8, 1.4, 1.8),
                        views={'catch': dict(follow=f'@e[tag={tag("father")},limit=1]', offset=(2.4, 1.1, .8),
                                             criteria='父油守在母油身旁看着她，快生了才摘帽；放帽后站定看着母油，不来回抖动；帽子接住新生儿')}))
land_birth('birth_land_missed', '没帽接生，新生儿落地摔伤', '没帽接生，新生儿落地摔伤', 'natural', 'reimu',
           '灵梦父油没有帽子、不接生，单人软垫窝也接不住新生儿：母油临产先走出软垫窝，新生儿直接落地，摔伤哭泣，父母赶来安抚；伤口按普通规则处理。[choreo] 节拍为 site → term → exit → push → linger，没有 meet／place', 'land_missed')
land_birth('birth_stem_caught', '茎生实子落进父油的帽子', '茎生实子落进父油的帽子', 'stem', 'marisa',
           '魔理沙父油把帽子放到茎下（帽口对准实子，近侧帽檐可伸到母油身前）：实子带一点随机横向力脱落，落在帽上的都坐上帽子、不会穿过帽子掉到帽下地面：落在帽檐上的先坐上帽檐再滚进帽腔（日志 [body] contact … occupancy=ON_RIM、newborn on hat rim rolls inward、[action] start id=gesture … brim=into_hollow），帽腔挤满时从帽檐外侧滚落、落地摔伤；只有落到帽外（或只擦到帽檐外缘）的才受摔伤（日志 birth landed outcome=HAT/GROUND，birth impact injury；没接住看 falling newborn not caught reason=）；母油静止时花梗竖直不摆；多重复几次看比例',
           'land_caught', smoke=dict(ticks=6000,
               expect=[r'birth landed [^\n]*outcome=HAT\b'],
               forbid=[r'birth landed [^\n]*outcome=WATER\b', r'\[body\][^\n]* invariant\b']),
           wait='约 20 秒成熟。五果比帽口宽，接住的进帽，外侧的落地。', sizes=' "0.13,0.15,0.17,0.18,0.19"')

add('feed', 'family', '饿赤油求食，亲油找粮嚼糜喂', '饿赤油求食，亲油找粮嚼糜喂',
    '三家饥饿赤油：前排孩子在窝、后排亲油在窝、最右低友善对照。左侧16格外只有两份苹果，另有一株草；看接受后找粮、真实采食、嚼糜和窝内外交接。低友善仍可能接受；无粮须显示搜索结束原因，可手动补苹果再观察',
    '约 1–3 分钟；读双方与实际粮源', [
    block(3, 0, 0, 'mcyukkuri:yukkuri_nest'),
    yk('chen', 0, 0, 'mom', yaw=-90, friendliness=95, **pair_ready()),
    yk_on_nest('reimu', 3, 0, 'baby', stage='baby', nutrition=25, yaw=90, lead=True),
    f'yukkuri scenario kin {sel("mom")} {sel("baby")} false',
    block(0, 0, 7, 'mcyukkuri:yukkuri_nest'),
    yk_on_nest('chen', 0, 7, 'mom_in', friendliness=95, **pair_ready()),
    yk('reimu', 3, 7, 'baby_out', stage='baby', nutrition=25),
    f'yukkuri scenario kin {sel("mom_in")} {sel("baby_out")} false',
    yk('chen', -5, 12, 'reluctant', friendliness=0, self_control=0, **pair_ready()),
    yk('reimu', -2, 12, 'requester', stage='baby', nutrition=25),
    f'yukkuri scenario kin {sel("reluctant")} {sel("requester")} false',
    item('minecraft:apple', 2, -16, 0), block(-10, 0, 7, 'minecraft:short_grass')],
    spot='feed', vantage=(0, 12), give=['minecraft:apple 4'],
    reads=[(label, f'yukkuri status {sel(name)} raw') for label,name in
           [('读窝外亲油','mom'),('读窝内赤油','baby'),('读窝内亲油','mom_in'),('读窝外赤油','baby_out'),
            ('读低友善亲油','reluctant'),('读低友善子代','requester')]])

add('first_toilet', 'family', '新生儿吃第一口后才首次排便', '新生儿吃第一口后才首次排便', '新生赤油尚未吃过第一餐：先观察它求食而不排便，再把手里的苹果丢到亲油身边。亲油嚼糜、近身递喂，孩子实际吞下后才请求首便辅助', '丢下苹果后约 1.5 分钟', [
    yk('chen', 0, 0, 'mom', yaw=-90, **pair_ready()),
    yk('reimu', 3, 0, 'baby', stage='baby', nutrition=32, bladder=30, yaw=90, lead=True),
    f'yukkuri scenario kin {sel("mom")} {sel("baby")} true'], give=['minecraft:apple 3'], spot='first_toilet', vantage=(0, 10),
    reads=[('读首餐与首便', f'data get entity {sel("baby")} YukkuriChildhood'),
           ('读亲代照护决定', f'yukkuri status {sel("mom")} raw')],
    smoke=dict(ticks=2400,commands=[(100,item('minecraft:apple',3,0,0))],
               expect=[r'first toilet completed',r'poop placed [^\n]*volume='],forbid=[]))

add('hop', 'family', '子油跟着亲油学会起跳', '子油跟着亲油学会起跳', '子油先爬行试探，亲油示范起跳，子油跟着练到学会', '约 1–2 分钟', [
    yk('reimu', 0, 0, 'mom', yaw=-90, **pair_ready()),
    yk('chen', 3, 0, 'kid', stage='child', yaw=90, lead=True, childhood=CHILDHOOD_CAN_JUMP_SOON),
    f'yukkuri scenario kin {sel("mom")} {sel("kid")} false'], spot='hop', vantage=(0, 10))

add('growth', 'family', '五只差1 tick到龄：长大、变老、寿终', '五只差1 tick到龄：长大、变老、寿终',
    '五只灵梦从赤油、子油、亚成、老年、寿命边界前1 tick开始。下一tick依次成为子油、亚成、成油、老年和自然死亡；营养低不会延后年龄资格。之后按固定年龄继续成长，可用倍速观察',
    '下一模拟tick', [
        *[command for n, age in enumerate((71999, 215999, 335999, 947999, 1055999))
          for command in (yk('reimu', (n - 2) * 3, 0, f'age{n}', label=str(age), lead=n == 2, nutrition=70),
                          set_status(f'age{n}', 'growth.age_ticks', age))],
        item('minecraft:apple', 8, 0, 4)], env=DAY, spot='growth',
    reads=[('读年龄与阶段', 'yukkuri status @e[tag=scn,type=mcyukkuri:reimu,distance=..40] raw')])

# Leaf swaddle (docs/FEATURES.md): leaf bushes are the real material; a worn wrap is a starting state only.
LEAF_BUSH = 'minecraft:oak_leaves[persistent=true]'


def leaf_wrap(soil=0, open_=False, neglect=0):
    """A wrap already on the child: one real oak leaf inside, ``soil`` stool loads held, ``neglect`` ticks of dirty closed wear so far."""
    return (f'YukkuriLeafSwaddle:{{Worn:1b,Open:{1 if open_ else 0}b,Soil:{soil},Neglect:{neglect},'
            'Material:[{id:"minecraft:oak_leaves",count:1}]}')


def leaf_reads(*names):
    return [(f'读{label}襁褓与需要', f'yukkuri status {sel(name)} raw') for label, name in names]


add('leaf_swaddle', 'family', '亲油采叶包襁褓、解开换便', '亲油采叶包襁褓、解开换便',
    '友善、聪明的亲油先照护两只已裹着树叶襁褓的子油：便意 55 的一只被解开（这片叶摊在身下）、舔舐后排出一份便便、舔净、重新包好；'
    '已积 2 体积单位的一只解开时实际积便落地、脏叶作废，舔净后要另采叶子。随后亲油从左侧叶丛衔下一片叶（叶块真实消失）、原地整理，'
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
    reads=leaf_reads(('亲油', 'mom'), ('赤油', 'baby'), ('便意子油', 'kid'), ('积便子油', 'kid_soiled'), ('亚成油', 'teen'), ('低友善亲油', 'cold')),
    smoke=dict(ticks=2400,expect=[r'leaf swaddle opened [^\n]*soil=2',r'poop placed [^\n]*volume='],forbid=[]))

_fd = {k: uid('family_distance', k) for k in ('parent_near', 'child_near', 'parent_bold', 'child_bold')}
add('family_distance', 'family', '胆大子油跑得远，普通的早回来', '胆大子油跑得远，普通的早回来',
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

_fs = {k: uid('family_search', k) for k in ('parent', 'child', 'lost_mother', 'lost_child')}
_fsp = {k: uid('family_search_pane', k) for k in ('parent', 'child')}


def _uuid_of(ints):
    return str(uuid.UUID(bytes=b''.join((n & 0xffffffff).to_bytes(4, 'big') for n in ints)))


def _family_search_order(log, steps, once=(), absent=()):
    def pattern(actor, event):
        return rf'uuid={_uuid_of(actor)}[^\n]* {event}'

    after = 0
    for actor, event in steps:
        found = re.search(pattern(actor, event), log[after:])
        if found is None:
            return f'{_uuid_of(actor)} never reached {event} in order'
        after += found.end()
    for actor, event in once:
        count = len(re.findall(pattern(actor, event), log))
        if count != 1:
            return f'{_uuid_of(actor)} reached {event} {count} times'
    for actor, event in absent:
        if re.search(pattern(actor, event), log):
            return f'{_uuid_of(actor)} unexpectedly reached {event}'


def _family_call_verify(log):
    full = _fs['lost_mother']
    return _family_search_order(log, [
        (_fs['parent'], 'child call started'),
        (_fs['child'], 'child heard parent call'),
        (_fs['parent'], 'child search reunited'),
    ]) or _family_search_order(log, [
        (full, 'glass approach'),
        (full, 'glass vigil started'),
        (full, 'glass call .*spoken=true'),
        (_fs['lost_child'], 'glass answer .*spoken=true'),
        (_fs['lost_child'], 'glass child at stand'),
        (full, 'glass farewell .*reason=vigil_elapsed spoken=true'),
        (full, 'parent dismissed .*reason=vigil_elapsed'),
    ], [(full, 'glass vigil started'), (full, 'glass farewell')], [(full, 'child search reunited'), (full, 'start id=bash')])


def _family_pane_verify(log):
    parent, child = _fsp['parent'], _fsp['child']
    return _family_search_order(log, [
        (parent, 'glass approach'),
        (parent, 'glass vigil started'),
        (parent, 'glass call .*spoken=true'),
        (child, 'glass answer .*spoken=true'),
        (child, 'glass child at stand'),
        (parent, 'glass pane choice .*chosen=true'),
        (parent, 'hit id=bash'),
        (parent, 'break id=bash'),
        (parent, 'glass crossed'),
        (parent, 'child search reunited'),
    ], [(parent, 'glass vigil started'), (parent, 'break id=bash')], [(parent, 'parent dismissed')])


add('family_search', 'family', '走散亲子：绕墙重逢，隔整块玻璃道别', '走散亲子：绕墙重逢，隔整块玻璃道别',
    '北边一对无巢亲子隔着可绕行的石砖矮墙，彼此遮住时呼唤，重新靠近后恢复自由活动。'
    '南边亲油隔一层整块玻璃看见子油，来到玻璃前呼唤；孩子回应，贴近玻璃内侧。亲油等候到期后道别离开。'
    '可在道别后手动拆玻璃，让亲油看见实际通路再寻亲。',
    '约 30–70 秒', [
        yk('reimu', -15, -4, 'search_parent', uuid=_fs['parent'], label='寻子亲油·友善95', friendliness=95, lead=True, **pair_ready()),
        yk('reimu', 15, -4, 'search_child', uuid=_fs['child'], stage='child', label='走散子油', boldness=40,
           kin=f'Mother:{uuid_nbt(_fs["parent"])}', **pair_ready()),
        box(0, 0, -8, 0, 1, 0, 'minecraft:stone_bricks'),
        box(13, -1, 3, 17, 3, 7, 'minecraft:glass', 'hollow'),
        yk('reimu', 7, 5, 'lost_mother', uuid=_fs['lost_mother'], label='隔玻璃的亲油', friendliness=65, self_control=20, boldness=0, **pair_ready()),
        yk('reimu', 15, 5, 'lost_child', uuid=_fs['lost_child'], stage='child', label='被围住的子油', boldness=0,
           kin=f'Mother:{uuid_nbt(_fs["lost_mother"])}', **pair_ready())],
    env=DAY, spot='hurt_kin', vantage=(0, 14),
    # The calls: once following the warm parent (in front of her face while she calls), once following the north child.
    capture=dict(shots=(1, 40, 600), sequences=((100, 340, 3),), observe=False,
                 views={'parent': dict(follow=f'@e[tag={tag("search_parent")},limit=1]', offset=(0, .9, 2.4), criteria='北边亲油的呼唤、伸长身体和左右张望能看清；不要求这一机位同时看见远处孩子或南边玻璃围栏。'),
                        'child': dict(follow=f'@e[tag={tag("search_child")},limit=1]', offset=(0, .5, 1.5), criteria='北边孩子转向、回应和接近亲油的动作能看清；只判断当前时刻已经发生的动作，不要求早期画面已经重逢。')}),
    reads=[('读寻子亲油', f'yukkuri status {sel("search_parent")} raw'), ('读寻亲子油', f'yukkuri status {sel("search_child")} raw'),
           ('读蹲守与寻亲记忆', f'yukkuri status {sel("lost_mother")} raw'), ('读被围子油不安', f'yukkuri status {sel("lost_child")} raw')])

add('family_search_pane', 'family', '走散亲子：撞碎玻璃板后重逢', '走散亲子：撞碎玻璃板后重逢',
    '胆量 100 的成年魔理沙与子油隔着铁栏窗中间的一列玻璃板相望。亲油在窗外呼唤，孩子回应并贴近内侧；亲油转正撞板，玻璃板碎后走过开口重逢。',
    '约 10–30 秒', [
        box(-3, -1, -3, 3, 2, 3, 'minecraft:stone_bricks', 'hollow'),
        box(-2, 0, 3, 1, 1, 3, 'minecraft:iron_bars'),
        box(0, 0, 3, 0, 1, 3, 'minecraft:glass_pane'),
        yk('marisa', 0, 7, 'pane_parent', uuid=_fsp['parent'], label='撞玻璃板的亲油', yaw=180, boldness=100, lead=True, **pair_ready()),
        yk('marisa', 0, 0, 'pane_child', uuid=_fsp['child'], label='窗内的子油', stage='child', boldness=0,
           kin=f'Mother:{uuid_nbt(_fsp["parent"])}', **pair_ready())],
    env=DAY, spot='hurt_kin', vantage=(0, 12, 180),
    reads=[('读窗外亲油', f'yukkuri status {sel("pane_parent")} raw'), ('读窗内子油', f'yukkuri status {sel("pane_child")} raw')])

# Real initial conditions for the retained birth/growth rules; no goal or successful outcome is assigned.
add('birth_nest_safe', 'family', '母油走进大窝生产，赤油落垫上', '母油走进大窝生产，赤油落垫上',
    '母油从窝外出发，不跳地走进野生大草窝（窝沿一格以内直接迈过，不停在窝沿上），躺到休息位（有空时在窝心）后生产，伴侣也认领同窝挨着躺。每只赤油带随机推力：多数落在垫面（outcome=CUSHION，无伤），落在窝沿上的先滚向窝心（rolls inward），少数飞出窝外落地（GROUND，日志 birth impact injury，摔伤哭泣）。读产程、birth launch / birth landed 日志与伤势',
    '约 2 分钟', [*shed(0, 0, half=4), *large_nest(0, 0, 'mcyukkuri:large_wild_nest'),
    yk('reimu', .5, 2, 'mother', yaw=180, lead=True, **pair_ready()),
    yk('reimu', 3, 2, 'father', **pair_ready()),
    f'yukkuri scenario bond {sel("mother")} {sel("father")}', home('mother', 0, 0),
    f'yukkuri scenario birth {sel("mother")} {sel("father")} natural'], spot='pet_birth',
    reads=[('读自然产程', f'yukkuri status {sel("mother")} raw'), ('读所有赤油', f'yukkuri status {NEWBORNS} raw')])

add('birth_nest_stem', 'family', '大窝里茎生，实子落在垫上', '大窝里茎生，实子落在垫上',
    '灵梦父母，母油从窝外走进野生大草窝，先转身让茎上实子都悬在窝内（日志 stem site facing），再茎生：实子脱落时带随机横向力，多数落在垫面（CUSHION，无伤），少数落到窝外受摔伤。读产程、birth launch / birth landed 日志',
    '约 2 分钟', [*shed(0, 0, half=4), *large_nest(0, 0, 'mcyukkuri:large_wild_nest'),
    yk('reimu', .5, 2, 'mother', yaw=180, lead=True, **pair_ready()),
    yk('reimu', 3, 2, 'father', **pair_ready()),
    f'yukkuri scenario bond {sel("mother")} {sel("father")}', home('mother', 0, 0),
    f'yukkuri scenario birth {sel("mother")} {sel("father")} stem "0.13,0.15,0.17,0.18,0.19"'], spot='pet_birth',
    # Her walk into the nest and turn (from about 40 ticks), then the drops 400 ticks after she is in place (about 520).
    capture=dict(shots=(1, 40, 200, 480), sequences=((40, 240, 5), (480, 640, 4)), observe=False,
                 views={'nest': dict(follow=f'@e[tag={tag("mother")},limit=1]', offset=(1.6, 1.7, 1.4),
                                     criteria='母油走进大窝后先转身，茎上实子都悬在窝内，脱落后落在窝垫上')}),
    reads=[('读母油产程', f'yukkuri status {sel("mother")} raw'), ('读所有赤油', f'yukkuri status {NEWBORNS} raw')])

_nest_ids={name:uid('family_nests',name) for name in ['mother','father','b0','b1','b2','b3']}
# Ages that cross BABY_END (72000) about 27 s / 45 s / 64 s into the scene's 3x clock, so three of the four young are released
# from the shared nest one after another while the observer watches; the fourth stays a baby in the same nest.
_NEST_SHARE={'b0':.2095,'b1':.2062,'b2':.2028,'b3':.18}
# The four young start on the four cells of the one large nest (an awake baby more than 1.5 blocks from its bed counts as lost and
# sends both parents off searching). yk_on_nest places a body on the real bed surface; the +1 offset is its large-nest centring.
_NEST_CELLS=((-0.5, -0.5), (0.5, -0.5), (-0.5, 0.5), (0.5, 0.5))
add('family_nests', 'family', '一家六口同窝，长大再分窝', '一家六口同窝，长大再分窝',
    '六口开场都不困：四只赤油平平地躺在大窝的四个格上，父母贴着窝边（读“同居与床位”看六口床位都在这一个窝）。场景切到 3 倍速：约半分钟内开始有子油长成、被大窝按容量让出（日志 '
    'growth released shared nest，一次一只），让出的子油自己走到两边空着的普通窝里认领床位（日志 released child claimed a free nest，三只分住这两个窝），最后一只仍是赤油留在大窝。困了会回自己的窝；按观察台“1倍速”可恢复正常速度',
    '先看六口同窝，再看 3 倍速下约 30 秒起一只只长成、被让出大窝；清空或重开场景会恢复 1 倍速', ['function mcylab:util/speed_3x',
    *shed(0, 0, half=5), *large_nest(0, 0, 'mcyukkuri:large_yukkuri_nest'),
    block(-3, 0, 0, 'mcyukkuri:yukkuri_nest'), block(3, 0, 0, 'mcyukkuri:yukkuri_nest'),
    yk('reimu', -1, -.5, 'mother', uuid=_nest_ids['mother'], lead=True),
    yk('marisa', 2, 2, 'father', uuid=_nest_ids['father']),
    f'yukkuri scenario bond {sel("mother")} {sel("father")}',
    *[yk_on_nest('reimu', x, z, f'b{i}', large=True, stage='baby', share=_NEST_SHARE[f'b{i}'], uuid=_nest_ids[f'b{i}'],
                 kin=f'Mother:{uuid_nbt(_nest_ids["mother"])},Father:{uuid_nbt(_nest_ids["father"])}') for i,(x,z) in enumerate(_NEST_CELLS)],
    *[home(name, 0, 0) for name in _nest_ids]], spot='pet_birth',
    give=['mcyukkuri:large_yukkuri_nest 2','mcyukkuri:large_wild_nest 2','mcyukkuri:yukkuri_nest 6'],
    reads=[('读同居与床位', f'yukkuri status @e[tag=scn,distance=..48] raw')])

_night_ids = {name: uid('night_sleep', name) for name in ('mother', 'father', 'b0', 'b1')}


def _night_sleep_verify(log):
    """A night in the nest: each one that lies down sleeps from the night rest time until dawn and never wakes by itself. A member
    still keeping its home or looking for a child is another module's work; the scene shows at least a pair sleeping."""
    begins = re.findall(r'\[sleep\] \w+ #\d+ uuid=(\S+)[^\n]*begin source=life[^\n]*reason=(\w+)', log)
    actors = [actor for actor, _ in begins]
    if len(set(actors)) < 2:
        return f'only {len(set(actors))} of the four fell asleep at night'
    if len(actors) != len(set(actors)):
        return 'a night sleep began twice: sleep/wake repeating'
    for actor, reason in begins:
        if reason != 'rest_time':
            return f'sleeper {actor} began with reason={reason}, not the night rest time'
    woken = {}
    for actor, cause, night in re.findall(r'\[sleep\] \w+ #\d+ uuid=(\S+)[^\n]*end source=life cause=(\w+) night=(\w+)', log):
        if cause != 'rested_dawn' or night != 'false':
            return f'sleeper {actor} woke cause={cause} night={night}, not rested at dawn'
        woken[actor] = cause
    asleep = [actor for actor in actors if actor not in woken]
    if asleep:
        return f'{len(asleep)} sleeper(s) never woke from the night sleep'
    return None


add('night_sleep', 'family', '夜里睡到天亮，惊扰才醒', '夜里睡到天亮，惊扰才醒',
    '夜里（正午按钮可换白天对照）：父母和两只赤油精力满、困值不满，到了作息时间照样认窝、进窝睡下；睡下的那几只整夜不自己醒，约两分钟后自动天亮，它们伸懒腰打哈欠醒来（日志 cause=rested_dawn）。还在储粮或找孩子的那只不一定整夜在窝里；中途按“夜晚”重开或打雷、搬走窝才提前醒',
    '整夜约2分钟，天亮后自动醒', [*shed(0, 0, half=4), *large_nest(0, 0, 'mcyukkuri:large_yukkuri_nest'),
    yk('reimu', -1, -.5, 'mother', uuid=_night_ids['mother'], energy=100, nutrition=100, rest_wait=60, lead=True),
    yk('marisa', 2, 2, 'father', uuid=_night_ids['father'], energy=100, nutrition=100, rest_wait=60),
    f'yukkuri scenario bond {sel("mother")} {sel("father")}',
    *[yk('reimu', x, z, f'b{i}', stage='baby', uuid=_night_ids[f'b{i}'], energy=100, nutrition=100, rest_wait=60,
         kin=f'Mother:{uuid_nbt(_night_ids["mother"])},Father:{uuid_nbt(_night_ids["father"])}')
      for i, (x, z) in enumerate([(-.9, 2.1), (1.2, 2)])],
    *[home(name, 0, 0) for name in _night_ids]],
    env={'time': 'night'}, spot='pet_birth', vantage=(0, -7, 0, 10),
    late=['schedule function mcylab:util/time_dawn 2400t replace'],
    smoke=dict(ticks=3000,
               expect=[r'\[sleep\] [^\n]*begin source=life[^\n]*reason=rest_time',
                       r'\[sleep\] [^\n]*end source=life cause=rested_dawn night=false'],
               forbid=[r'\[sleep\] [^\n]*end source=life cause=rested night=true',
                       r'\[sleep\] [^\n]*begin source=life[^\n]*reason=energy'],
               readout=[f'yukkuri status {sel("mother")} raw'], verify=_night_sleep_verify),
    reads=[('读一家四口的睡眠与精力', f'yukkuri status @e[tag=scn,distance=..48] raw')])

_night_feed_ids = {name: uid('night_hungry_child', name) for name in ('mother', 'baby')}
add('night_hungry_child', 'family', '夜里赤油饿了，睡着的亲油起来喂', '夜里赤油饿了，睡着的亲油起来喂',
    '夜里：亲油精力耗尽，就地躺下睡（这本是睡几百 tick 就自己醒的一觉，现在一直睡）；赤油不困、守在旁边。约 10 秒后孩子饿到 12 开始求食，睡着的亲油因为自家孩子等着喂而醒来（日志 cause=child_hungry），再照常找粮、嚼糜、递到孩子嘴里。看亲油有没有自己提前醒、是否真弄到粮并真喂进去',
    '约 1–2 分钟', [*shed(0, 0, half=4), block(0, 0, 0, 'mcyukkuri:yukkuri_nest'),
    yk('chen', -1.5, .5, 'mother', uuid=_night_feed_ids['mother'], energy=0, nutrition=100, friendliness=95, lead=True),
    yk('reimu', 1.5, .5, 'baby', stage='baby', uuid=_night_feed_ids['baby'], energy=100, nutrition=100, rest_wait=1200,
       kin=f'Mother:{uuid_nbt(_night_feed_ids["mother"])}'),
    item('minecraft:apple', 2, 0, 3)],
    env={'time': 'night'}, spot='pet_birth', vantage=(0, -7, 0, 10),
    late=[Retry(f'yukkuri status set {sel("baby")} needs.nutrition 12', sel('baby'), tries=1, first=260)],
    smoke=dict(ticks=1800,
               expect=[r'\[sleep\] [^\n]*end source=fatigue[^\n]*cause=child_hungry',
                       r'\[family\][^\n]*feed started'],
               forbid=[r'\[sleep\] [^\n]*end source=fatigue[^\n]*cause=rested'],
               readout=[f'yukkuri status {sel("mother")} raw']),
    reads=[('读亲油与孩子的需要', f'yukkuri status @e[tag=scn,distance=..48] raw')])

add('infant_starvation', 'family', '饿极赤油：胆大的自己爬去吃草', '饿极赤油：胆大的自己爬去吃草',
    '三只赤油：普通饥饿不能自食；极饿胆大者自行走出3格范围取食；极饿胆小低智者拒绝冒险。每只前方8格有一株真实草，可读决策与营养变化',
    '约 1–3 分钟', [
    *[yk('reimu',-8+i*8,0,f'infant{i}',stage='baby',nutrition=25 if i==0 else 5,
         boldness=5 if i==2 else 95,intelligence=5 if i==2 else 95,self_control=95 if i==2 else 20,
         childhood=CHILDHOOD_CRAWLING_READY,lead=i==0) for i in range(3)],
    *[block(-8+i*8,0,8,'minecraft:short_grass') for i in range(3)]],spot='feed',
    reads=[('读取食决定',f'yukkuri status @e[tag=scn,distance=..48] raw')])

# ====================================================================================================== weather and predators
add('rain_shelter', 'weather', '下雨跑去远处石顶下躲雨', '下雨跑去远处石顶下躲雨',
    '石顶在旧16格搜索范围外：观察32格搜索与实际抵达；进入干燥处后释放避雨Goal，恢复普通活动，读behavior.weather和mood.unease。按观察台“雷暴”对照：同样的石顶，反应更强烈',
    '约 30 秒', [
        yk('reimu', 0, 0, 'a', yaw=-90, lead=True), yk('marisa', 2, 3, 'b', yaw=-90), yk('chen', -2, 2, 'c', yaw=-90)],
    env={'time': 'noon', 'weather': 'rain'}, spot='shelter', vantage=(0, 14),
    reads=[('读反应（behavior.reaction）', f'yukkuri status {sel("a")} raw')])

# No roof in reach: two dry ones search while running; two already near-soaked ones (wetness 97 and 98.5, +3 a second in rain)
# show the damage that only comes at 100.
add('rain_open', 'weather', '空地淋雨找掩体，湿透掉血', '空地淋雨找掩体，湿透掉血',
    '下雨时附近没有屋顶：北边两只边跑边找，发现可达屋顶后转向避雨，雨停后抖掉水。南边两只湿度 97 与 98.5 起步，下雨每秒 +3，满 100 才掉血；伤害不弹起不横移。可中途搭顶或放晴，干燥后雨伤停止',
    '约 30 秒看雨伤；找掩体持续', [
        yk('reimu', 0, 0, 'a', yaw=-90, lead=True), yk('marisa', 2, 3, 'b', yaw=-90),
        yk('reimu', -3, 7, 'wet_ordinary', label='湿度97', nutrition=90, energy=90),
        yk('marisa', 3, 7, 'wet_control', label='湿度98.5', nutrition=90, energy=90),
        set_status('wet_ordinary', 'needs.wetness', 97), set_status('wet_control', 'needs.wetness', 98.5)],
    env={'time': 'noon', 'weather': 'rain'}, spot='open', vantage=(0, 16),
    reads=[('读找掩体状态', f'yukkuri status {sel("a")} raw'), ('读湿度97的雨伤', f'yukkuri status {sel("wet_ordinary")} raw'),
           ('读湿度98.5的雨伤', f'yukkuri status {sel("wet_control")} raw')])

add('pred_watch', 'weather', '不饿的捕食种在旁，普通油后退', '不饿的捕食种在旁，普通油后退',
    '蕾米莉亚（不饿）在 12 格外：普通个体还没挨打就开始后退（threat:retreat），离开威胁后躲藏；不饿的捕食者不会主动追猎',
    '约 1 分钟', [
        yk('reimu', 0, 0, 'a', yaw=-90, lead=True), yk('marisa', 0, 2, 'b', yaw=-90),
        yk('remilia', 12, 0, 'pred', yaw=90, nutrition=100, energy=100)],
    env=DAY, spot='pred', vantage=(6, 14), reads=[('读反应', f'yukkuri status {sel("a")} raw')])

add('pred_range', 'weather', '饥饿芙兰远远飞来追猎灵梦', '饥饿芙兰远远飞来追猎灵梦',
    '芙兰与灵梦相距 48 格：芙兰站稳后开始饥饿，观察远处发现、起飞追击、猎物逃跑和接触咬击；一口咽完仍饿时继续觅食',
    '约 1–2 分钟', [
        yk('reimu', -24, 0, 'range_prey', yaw=-90, **pair_ready()),
        yk('flandre', 24, 0, 'range_pred', yaw=90, lead=True, nutrition=100, energy=100)],
    late=[set_status('range_pred', 'needs.nutrition', 10)],
    env=DAY, spot='pred', vantage=(0, 18),
    reads=[('读远猎', f'yukkuri status {sel("range_pred")} raw'), ('读远猎猎物', f'yukkuri status {sel("range_prey")} raw')])

add('forage_empty','weather','没粮的饿灵梦四处找吃的','没粮的饿灵梦四处找吃的',
    '开始不放食物：先切到空手，饥饿灵梦立即主动搜索（同一游荡状态、流口水表情），搜不到也不定时退回idle；手持苹果时立即走向玩家，再丢下观察接近、吞咽与营养回升；读移速可见紧急修正。真实受阻仍会饿，不指定Goal',
    '约30秒后丢食物',[yk('reimu',0,0,'seeker',nutrition=18,energy=90,lead=True)],
    env=DAY,spot='pred',give=['minecraft:apple 3'],reads=[('读主动寻粮',f'yukkuri status {sel("seeker")} raw')])

add('shared_body','weather','普通种与捕食种同一身体结构','普通种与捕食种同一身体结构',
    '同一主人的宠物普通魔理沙、蕾米莉亚、弗兰共用身体结构。绕到背面看油菊；按钮对照佩妮佩妮和马姆马姆伸缩，再看通用行走。伸缩仍互斥，默认收回；动作片段只验素材，不代表行为成功',
    '手动切换观察',[
        yk('marisa',-9,0,'ordinary',nutrition=100,also=('sharedbody',),lead=True),
        yk('marisa',-3,0,'second',nutrition=100,also=('sharedbody',)),
        yk('remilia',3,0,'remilia',nutrition=100,also=('sharedbody',)),
        yk('flandre',9,0,'flandre',nutrition=100,also=('sharedbody',)),
        # One keeper's pets: a predator does not menace its keeper's own, so the lineup stays together.
        *[line for name in ('ordinary','second','remilia','flandre') for line in own(name)]],env=DAY,vantage=(0,12),
    reads=[(label,f'yukkuri action @e[tag=scn_sharedbody] {action}') for label,action in
           [('伸出佩妮佩妮','anatomy_peni_extend'),('收回佩妮佩妮','anatomy_peni_retract'),
            ('伸出马姆马姆','anatomy_mamu_extend'),('收回马姆马姆','anatomy_mamu_retract'),
            ('行走片段','waddle'),('结束片段','idle')]])

# Sheltered actors are a legitimate start for testing activity after the run has ended.
_rain_life = []
for _name, _x, _nutrition in (('ordinary', -12, 90), ('hungry', 12, 10)):
    _rain_life += [box(_x-3, 3, -3, _x+3, 3, 3, 'minecraft:stone_bricks'),
                   yk('reimu', _x, 0, _name, nutrition=_nutrition, energy=95, lead=_name=='ordinary'),
                   item('minecraft:apple', 2, _x+7, 0, _name+'_food')]
add('rain_life', 'weather', '屋檐下躲雨，饿的冒雨吃苹果', '屋檐下躲雨，饿的冒雨吃苹果',
    '左右都有真实屋顶，屋外各两枚苹果：左营养90保持干燥区活动，右营养10可冒雨觅食；同场可手动切雷暴对照不安原因，不指定Goal',
    '约1–2分钟', _rain_life, env={'time':'noon','weather':'rain'}, spot='dry_life', vantage=(0,14),
    reads=[('读普通雨天活动', f'yukkuri status {sel("ordinary")} raw'), ('读饿油冒雨', f'yukkuri status {sel("hungry")} raw')])

# Under the field's stone roof in the rain: a timid, already uneasy Marisa and her calm mate, and a timid stranger alone.
# Nothing is told to huddle: the uneasy one seeks its kin, the mate has her, the stranger has nobody (trust 0) and trembles.
_hm, _hr = uid('rain_huddle', 'shy'), uid('rain_huddle', 'mate')
_hm_t, _hr_t = (str(uuid.UUID(bytes=b''.join((n & 0xffffffff).to_bytes(4, 'big') for n in u))) for u in (_hm, _hr))
add('rain_huddle', 'weather', '屋檐下怕雨的贴着伴侣发抖', '屋檐下怕雨的贴着伴侣发抖',
    '下雨，石顶下：胆小、已经不安（mood.unease=30）的魔理沙走到伴侣灵梦身边侧身贴紧，两只同步发抖（behavior.reaction 含 huddle partner=… pressed=true，日志 [weather] huddle pressed），贴着时不安不再上升（mood.unease，独处的那只会升）；'
    '按“附近落雷”：两只一起受惊再贴紧（[weather] thunder clap heard）。后排独处的胆小灵梦没有亲友，只自己发抖（hunker.start pose=tremble）',
    '约 10–40 秒', [
        yk('marisa', 1, 0, 'shy', uuid=_hm, yaw=180, lead=True, boldness=20, kin=kin_of(Partner=_hr), **pair_ready()),
        yk('reimu', -1, 0, 'mate', uuid=_hr, yaw=180, boldness=70, kin=kin_of(Partner=_hm), **pair_ready()),
        yk('reimu', 0, -3, 'alone', yaw=180, boldness=20, **pair_ready()),
        set_status('shy', 'mood.unease', 30), set_status('alone', 'mood.unease', 30)],
    env={'time': 'noon', 'weather': 'rain'}, spot='roof', vantage=(0, 9),
    reads=[('附近落雷', 'summon minecraft:lightning_bolt ~14 ~ ~6'), ('读贴靠的魔理沙', f'yukkuri status {sel("shy")} raw'),
           ('读伴侣灵梦', f'yukkuri status {sel("mate")} raw'), ('读独处灵梦', f'yukkuri status {sel("alone")} raw')],
    capture=dict(shots=(1, 40, 200, 400, 700, 900), sequences=((60, 900, 6),),
                 views={'front': dict(follow='@e[tag=scn_shy,limit=1]', offset=(.6, 1.0, 3.0), criteria='看清屋檐下魔理沙靠向伴侣、两者表情和发抖；独处灵梦不必在此镜头内。'),
                        'above': dict(follow='@e[tag=scn_shy,limit=1]', offset=(1.0, 1.7, 1.6), criteria='看清两者侧面贴靠关系、帽和身体轮廓；屋顶遮挡时只报机位问题。'),
                        'roof': dict(look_at=(0, 3, 0), criteria='看清遮雨屋顶与屋檐的空间关系；此机位不要求看见屋顶下个体的细小表情。')}),
    smoke=dict(ticks=1200, commands=[(700, 'summon minecraft:lightning_bolt ~14 ~ ~6')],
               expect=[rf'\[weather\] marisa [^\n]*uuid={_hm_t} [^\n]* huddle sought partner={_hr_t} kin=true',
                       rf'\[weather\] marisa [^\n]*uuid={_hm_t} [^\n]* huddle pressed partner={_hr_t} ',
                       r'\[weather\] reimu [^\n]* sheltered alone pose=tremble',
                       rf'\[weather\] marisa [^\n]*uuid={_hm_t} [^\n]* thunder clap heard [^\n]*stage=HUDDLE partner={_hr_t}'],
               forbid=[r'\[body\][^\n]* invariant\b', r'\[weather\][^\n]* huddle stand unavailable']))

# A thunderstorm: a timid one under the roof cowers flat; a bold one under the same roof only waits; one caught out in the open
# runs in under the roof, flinching at the claps.
add('storm_cower', 'weather', '雷暴：胆小的缩成一团，淋雨的跑进石顶', '雷暴：胆小的缩成一团，淋雨的跑进石顶',
    '雷暴，石顶下：胆小灵梦压扁发抖、偷看一眼又缩回（hunker.start pose=cower）；同在顶下的胆大魔理沙只是等雨停。'
    '东边空地上的灵梦冒雨跑向石顶（shelter response started sky=STORM），落雷时原地一缩再跑。按“附近落雷”看受惊；很饿的仍会冒雨找吃的（见“屋檐下躲雨”场景）',
    '约 10–40 秒', [
        yk('reimu', -1, 0, 'timid', yaw=180, lead=True, boldness=15, **pair_ready()),
        yk('marisa', 2, -2, 'bold', yaw=180, boldness=85, **pair_ready()),
        yk('reimu', 12, 0, 'runner', yaw=90, boldness=55, **pair_ready())],
    env={'time': 'noon', 'weather': 'thunder'}, spot='roof', vantage=(-1, -3),
    reads=[('附近落雷', 'summon minecraft:lightning_bolt ~16 ~ ~8'), ('读缩身的灵梦', f'yukkuri status {sel("timid")} raw'),
           ('读胆大魔理沙', f'yukkuri status {sel("bold")} raw'), ('读冒雨跑来的', f'yukkuri status {sel("runner")} raw')],
    capture=dict(shots=(1, 40, 200, 400, 600, 800), sequences=((60, 800, 6),),
                 views={'front': dict(follow='@e[tag=scn_timid,limit=1]', offset=(0, .8, 2.6)),
                        'back': dict(follow='@e[tag=scn_timid,limit=1]', offset=(0, .8, -2.6))}),
    smoke=dict(ticks=1000, commands=[(300, 'summon minecraft:lightning_bolt ~16 ~ ~8')],
               expect=[r'\[weather\] reimu [^\n]* sheltered alone pose=cower',
                       # vanilla ramps the thunder level up over a few seconds after `weather thunder`: the first run may still read RAIN
                       r'\[weather\] reimu [^\n]* shelter response started sky=(?:RAIN|STORM)',
                       r'\[weather\][^\n]* thunder clap heard '],
               forbid=[r'\[body\][^\n]* invariant\b']))

_roaming = [*shed(-16, 0, half=4), yk('marisa', -16, 1, 'roam_baby', stage='baby', lead=True), home('roam_baby', -16, 0)]
for _i in range(4):
    _roaming.append(yk('marisa', _i*4-4, 6, f'explorer{_i}', stage='child', boldness=85, intelligence=70, nutrition=95, energy=95))
_roaming += [yk('marisa', 16, 0, 'roam_cautious', stage='child', boldness=30, intelligence=70),
             yk('reimu', 16, 6, 'roam_control', stage='child', boldness=85, intelligence=70)]
add('marisa_explore', 'weather', '胆大子油魔理沙结伴远行', '胆大子油魔理沙结伴远行',
    '四只胆量85智力70子油魔理沙，各自每60秒至多一次5%抽签；普通半径27、探险54格，真实走到远点再返回。低胆量魔理沙与灵梦不触发；左侧赤油有真实巢，只在巢附近活动。读behavior.roaming，不保证单轮触发，缺台词动作最后补',
    '数分钟，可用场内调速；饥饿与危险照常中断', _roaming, env=DAY, spot='roaming', vantage=(0,18),
    reads=[(f'读探险子油{i+1}', f'yukkuri status {sel(f"explorer{i}")} raw') for i in range(4)]
          + [('读赤油边界', f'yukkuri status {sel("roam_baby")} raw'), ('读低胆量对照', f'yukkuri status {sel("roam_cautious")} raw')])

# ====================================================================================================== house (anchor: the middle of the room)
def house_pair(sid='house_pair', ax=-1, bx=1, dz=1):
    """A mated pair: each writes the other as Partner (strangers would greet as first_meet and be refused as a pair)."""
    a, b = uid(sid, 'a'), uid(sid, 'b')
    return [yk('house_marisa', ax, dz, 'a', uuid=a, yaw=180, lead=True, kin=kin_of(Partner=b), **pair_ready()),
            yk('house_reimu', bx, dz, 'b', uuid=b, yaw=180, kin=kin_of(Partner=a), **pair_ready())]


HOUSE_ACTIVITY = 'yukkuri household activity 24000 false true'
add('house_empty', 'house', '空屋住人一天后，家油搬进来', '空屋住人一天后，家油搬进来', '屋里没有家油：按“模拟人类活动”相当于住了一天（落下絮尘和食屑），随后有一只家油搬进来、选墙角、筑巢',
    '搬入几十秒，筑巢数分钟', [say('按观察台第一排右边的“模拟人类活动”，再用“推进”快进。', 'gold')], env=DAY, vantage=(0, 8, 180, 10),
    reads=[('读家油现状', 'yukkuri household status')])
add('house_pair', 'house', '一对家油挑墙角叼絮筑窝', '一对家油挑墙角叼絮筑窝', '从夜晚开始，屋内已有真实人类活动留下的絮尘和食屑：家油挑墙角、叼絮铺窝、储食；按“正午”对照白天躲窝休息',
    '数分钟（可倍速）', [*house_pair(), HOUSE_ACTIVITY], env={'time': 'night'}, vantage=None,
    reads=[('读家油现状', 'yukkuri household status')])

HNEST = 'mcyukkuri:house_dust_nest{Materials:6,Food:8,Arrivals:2,Entrance:"south"%s}'
add('house_eggs', 'house', '窝脏了，家油把卵叼去新窝', '窝脏了，家油把卵叼去新窝', '先建立真实卵生胎次，再让原窝变脏（Dirt 8）；东侧有一个干净新窝，家油逐只叼卵搬过去照看',
    '数分钟（可倍速）', [block(-5, 0, -2, HNEST % ''), block(5, 0, -2, HNEST % ''),
                       *house_pair('house_eggs', -4, -5, -1), HOUSE_ACTIVITY,
                       f'yukkuri scenario home {sel("a")} ~-5 ~ ~-2', f'yukkuri scenario home {sel("b")} ~-5 ~ ~-2',
                       f'yukkuri scenario eggs {sel("b")} {sel("a")}'],
    env={'time': 'night'}, vantage=None, reads=[('读家油现状', 'yukkuri household status'),
        ('读迁卵与亲代住所', 'yukkuri status @e[type=#mcylab:yukkuri,distance=..18] raw'),
        ('读原窝卵与迁卵记录', 'data get block ~-5 ~ ~-2'),
        ('读新窝卵与接收记录', 'data get block ~5 ~ ~-2')],
    late=['execute if data block ~-5 ~ ~-2 EggSlots[{Present:1b}] run data merge block ~-5 ~ ~-2 {Dirt:8}'])

# ====================================================================================================== town
add('social_identity', 'town', '宠物、流浪与野生四类对照', '宠物、流浪与野生四类对照',
    '四角分别是玩家的宠物、胆大的饥饿流浪油、胆小的聚落成员、胆大的独居野生油。空手站定，转身观察迎人、辱骂索食、退避与停看。',
    '站在四只之间，等它们自己选', [
    *shed(-4, -7), *shed(-4, 7, half=1),
    # Headless observers are markers. Give the shy pair a stationary stranger in view.
    'execute if entity @s[type=minecraft:marker] run ' + human('caretaker_sister', 0, 0, 'identity_listener', 0)[:-1] + ',NoAI:1b}',
    yk('reimu', -4, -4, 'identity_pet', label='灵梦', nutrition=90, social_wait=0, lead=True),
    *own('identity_pet'),
    f'yukkuri status relate {sel("identity_pet")} @s 30',
    yk('marisa', -4, 4, 'identity_stray', label='魔理沙', nutrition=38, social_wait=0,
       boldness=60, friendliness=40, self_control=40,
       extra='YukkuriPet:{TownRoots:1b}'),
    yk('patchouli', 4, -4, 'identity_settlement', label='帕秋莉', social_wait=0, boldness=40),
    f'yukkuri scenario community {sel("identity_settlement")} {sel("identity_settlement")}',
    yk('chen', 4, 4, 'identity_family', label='橙', social_wait=0, boldness=95)],
    env=DAY, vantage=(0, 0),
    late=[home('identity_pet', -4, -7), home('identity_stray', -4, 7)],
    give=['mcyukkuri:field_notebook', 'minecraft:apple 4'],
    note='魔理沙开口后可直接喂，或双手空着潜行右键，在「照料」中选「拒绝／赶走」。也可走开，等它停止请求。清场重开后可手持苹果走近，对照诱食接手。提高信任后还可收养魔理沙，或在照料栏对灵梦选择「弃养」。',
    reads=[('读四只的身份', 'yukkuri status @e[tag=scn,type=#mcylab:yukkuri,distance=..24]'),
           ('读魔理沙的自尊与关系', f'yukkuri status {sel("identity_stray")} raw')])

add('wild', 'town', '饿野油来讨宠物的苹果', '饿野油来讨宠物的苹果', '银章宠物就地吃苹果，旁边的野生魔理沙看见嘴里的食物才上前讨吃。看对方面向它、分一口或鼓脸拒绝。', '约半分钟', [
    yk('reimu', 0, 0, 'pet', nutrition=38, yaw=-90, lead=True, boldness=60), *own('pet'),
    f'yukkuri scenario badge {sel("pet")} silver false', item('minecraft:apple', 1, 1, 0),
    yk('marisa', 4, 0, 'wild', nutrition=32, yaw=90, boldness=25)], env=DAY, vantage=(1, 9))

add('badge', 'town', '铂金章灵梦遇到嫉妒的野油', '铂金章灵梦遇到嫉妒的野油', '野生魔理沙看见灵梦佩戴的铂金章，鼓脸或闹别扭。看灵梦按自尊与关系友善回礼、得意展示或退缩。', '约 1 分钟', [
    *(block(x, 0, z, 'minecraft:oak_fence') for x in range(-1, 4) for z in range(-2, 3)
      if x in (-1, 3) or abs(z) == 2),
    yk('reimu', 0, 0, 'pet', yaw=-90, lead=True, friendliness=53, boldness=35), *own('pet'),
    yk('marisa', 1.5, 0, 'wild', yaw=90, nutrition=84, boldness=68, friendliness=0, attachment=0, self_control=38),
    f'yukkuri scenario badge {sel("pet")} platinum false'], env=DAY, vantage=(1, 8))

_practice_home = []
for _x, _kind, _name, _skill in ((-7, 'reimu', 'practised', 76), (7, 'marisa', 'novice', 0)):
    _practice_home += [
        *shed(_x, -4), block(_x, 0, 2, 'mcyukkuri:food_bowl{Items:[{Slot:0b,id:"minecraft:apple",count:8}]}'),
        block(_x + 2, 0, -1, 'mcyukkuri:yukkuri_toilet'), item('minecraft:apple', 2, _x - 2, 2, _name + '_apples'),
        yk(_kind, _x, 0, _name, nutrition=48, energy=52, bladder=73, rest_wait=0, lead=_x < 0, yaw=0,
           label='练过的宠物' if _x < 0 else '刚开始练习',
           self_control=95 if _x < 0 else 24,
           extra=f'YukkuriPet:{{SkillExperience:{{food_bowl:{_skill},toilet:{_skill},bedtime:{_skill}}}}}'),
        *own(_name), home(_name, _x, -4)]
add('training_home', 'town', '宠物日常练习：吃饭、如厕、睡觉', '宠物日常练习：吃饭、如厕、睡觉',
    '两只同样饿、困并有便意的宠物，分别有食盆、沙厕和窝。看取食或打翻、走去沙厕或当场解决、回窝或就地睡下。用手册查看经验变化。',
    '入夜开始，约 1–2 分钟', _practice_home, env={'time': 'night'}, vantage=(0, 13),
    give=['mcyukkuri:badge_handbook', 'mcyukkuri:teaching_whistle', 'mcyukkuri:sweet_candy 6'],
    reads=[('读训练经验', f'data get entity {sel("practised")} YukkuriPet.SkillExperience'),
           ('读新手经验', f'data get entity {sel("novice")} YukkuriPet.SkillExperience')])

add('training_study', 'town', '宠物读告示牌、数苹果', '宠物读告示牌、数苹果',
    '灵梦能看见写有「苹果」的告示牌，魔理沙面前有六个苹果。看它们念出字、数到四以上说好多，或困惑答错。拿有字的书也可以从手册开课。',
    '约 1 分钟', [
        block(-5, 0, -2, 'minecraft:oak_sign[rotation=0]{front_text:{messages:[\'{"text":"苹果"}\',\'""\',\'""\',\'""\']}}'),
        item('minecraft:apple', 6, 6, 0, 'count_apples'),
        yk('reimu', -5, 0, 'reader', nutrition=96, social_wait=0, lead=True, intelligence=90, self_control=86,
           extra='YukkuriPet:{SkillExperience:{reading:76}}'), *own('reader'),
        yk('marisa', 5, 0, 'counter', nutrition=96, social_wait=0, intelligence=90, self_control=86,
           extra='YukkuriPet:{SkillExperience:{arithmetic:76}}'), *own('counter')],
    env=DAY, vantage=(0, 8), give=['mcyukkuri:badge_handbook', 'minecraft:writable_book'],
    reads=[('读读字经验', f'data get entity {sel("reader")} YukkuriPet.SkillExperience'),
           ('读算术经验', f'data get entity {sel("counter")} YukkuriPet.SkillExperience')])

def exam_hall():
    return [block(-3, 0, 0, 'mcyukkuri:exam_podium[facing=east]'),
            human('caretaker_brother', 0, 0, 'judge', 3, yaw=180, label='考官')]


def exam_study(pickup_delay=0):
    return [block(-2, 0, 1, 'minecraft:oak_sign[rotation=4]{front_text:{messages:[\'{"text":"苹果"}\',\'""\',\'""\',\'""\']}}'),
            item('minecraft:apple', 3, 1, 2, 'count_apples').replace('PickupDelay:0s', f'PickupDelay:{pickup_delay}s')]


def exam_candidate(grade, nutrition=72, bladder=0):
    return [yk('reimu', 0, 2, 'pet', yaw=180, lead=True, nutrition=nutrition, intelligence=95,
               friendliness=95, attachment=95, self_control=95, energy=95, social_wait=0, bladder=bladder,
               extra='YukkuriPet:{SkillExperience:{reading:80,arithmetic:80,wait:80,food_bowl:80,toilet:80,greeting:80,harness:80}}'),
            *own('pet'), f'yukkuri scenario badge {sel("pet")} {grade} false']


add('exam', 'town', '铜章考试：自我介绍', '铜章考试：自我介绍',
    '右键考官选简易考试，观察考官问姓名、灵梦作答、考官复述，再看铜章结果。',
    '约 10 秒', [*exam_hall(), yk('reimu', 0, 2, 'pet', label='灵梦', lead=True), *own('pet')],
    env={'time': 'dawn'}, vantage=(0, 8), reads=[('读考生', f'yukkuri status {sel("pet")} raw')],
    smoke=dict(ticks=340, commands=[(5, f'yukkuri scenario exam_bronze {sel("judge")} {sel("pet")}')],
               expect=[r'(?s)action=line [^\n]*key=exam\.mcyukkuri\.say\.simple_name.*action=answer task=simple_check success=true.*key=exam\.mcyukkuri\.comment\.name.*action=result [^\n]*grade=NONE->BRONZE'],
               forbid=[r'action=interrupt']))

add('exam_obstructed', 'town', '铜章考试：视线被挡', '铜章考试：视线被挡',
    '从考官一侧报名，考生在墙后。观察考官找路；一直无法接触时考试中断。',
    '约 10 秒', [*exam_hall(), yk('reimu', 0, 2, 'pet', lead=True), *own('pet'),
                  'fill ~-3 ~ ~1 ~3 ~2 ~1 minecraft:stone'], env={'time': 'dawn'}, vantage=(4, 6),
    smoke=dict(ticks=250, commands=[(5, f'data merge entity {sel("judge")} {{NoAI:1b}}'),
                                   (6, f'yukkuri scenario exam_bronze {sel("judge")} {sel("pet")}')],
               expect=[r'action=start [^\n]*target=BRONZE', r'action=interrupt reason=site [^\n]*emeralds_used=0'],
               forbid=[r'action=answer', r'action=result']))

add('exam_silver', 'town', '银章考试：读数、等待与照料', '银章考试：读数、等待与照料',
    '拿一份甜食在手上，右键考官选晋级。保持告示牌和散食可见，让考生演示日常如厕。',
    '约 1 分钟', [*exam_hall(), *exam_study(),
                  block(-1, 0, 2, 'mcyukkuri:yukkuri_toilet'),
                  *exam_candidate('bronze', 84, bladder=74)], env={'time': 'dawn'}, vantage=(0, 7),
    give=['mcyukkuri:sweet_candy', 'minecraft:emerald 4'],
    smoke=dict(ticks=1400, commands=[(5, f'yukkuri scenario exam_silver {sel("judge")} {sel("pet")}')],
               expect=[r'action=answer task=read success=true', r'action=answer task=count success=true',
                       r'action=answer task=toilet success=true', r'action=answer task=wait success=true',
                       r'action=result [^\n]*grade=BRONZE->SILVER emeralds_used=4'],
               forbid=[r'action=interrupt']))

def exam_promotion_expectation(before, target, *questions):
    """Check each rolled answer and its comment, then the grade earned by those answers."""
    exchange = [rf'(?s)action=start [^\n]*target={target} [^\n]*peer=(?P<exam_peer>[a-f0-9-]{{36}})']
    result = f'outcome=0 grade={before}->{target}'
    retained = f'outcome=2 grade={before}->{before}'
    for question in questions:
        failed = f'{question}_failed'
        answer = f'action=answer task={question} success='
        exchange.append(rf'.*?key=exam\.mcyukkuri\.question\.{question}\b')
        if question in ('help', 'share'):
            swallow = r'\[meal\][^\n]*uuid=(?P=exam_peer)[^\n]*swallowed item=minecraft:apple\b'
            exchange.append(rf'.*?(?:{swallow}.*?{answer}true observed=received_(?:owner_)?food\b|{answer}(?P<{failed}>false)\b)')
        else:
            exchange.append(rf'.*?{answer}(?:true|(?P<{failed}>false))\b')
        exchange.append(rf'.*?key=exam\.mcyukkuri\.comment\.{question}_(?({failed})fail|ok)\b')
        result = f'(?({failed}){retained}|{result})'
    return ''.join(exchange) + rf'.*?action=result [^\n]*{result} emeralds_used=4\b'


add('exam_gold', 'town', '金章考试：牵引、迎客与求助', '金章考试：牵引、迎客与求助',
    '给银章考生系胴带和拴绳，右键考官选晋级。牵引题实际走一个转弯，随后观察迎客；考生求助时可亲手喂饿着的同伴。',
    '约 1 分钟', [*exam_hall(), *exam_candidate('silver', 92), human('caretaker_sister', 2, 1, 'visitor', 0),
                  f'data merge entity {sel("visitor")} {{CareFood:0,NextRestock:24000L}}',
                  yk('marisa', -2, 3, 'peer', nutrition=32)], env={'time': 'dawn'}, vantage=(0, 7),
    give=['mcyukkuri:pet_harness', 'minecraft:lead', 'minecraft:apple', 'minecraft:emerald 4'],
    smoke=dict(ticks=1050, commands=[(5, f'yukkuri scenario exam_gold {sel("judge")} {sel("pet")}'),
                                    (78, 'yukkuri scenario exam_step_owner ~0.9 ~-0.0625 ~3.5'),
                                    (83, 'yukkuri scenario exam_step_owner ~0.9 ~-0.0625 ~3.0'),
                                    (440, f'yukkuri scenario exam_feed_peer {sel("peer")}')],
               expect=[r'harness turn owner=[^ ]+ waits=true', r'action=answer task=walk success=true',
                       r'action=answer task=greet success=true',
                       exam_promotion_expectation('SILVER', 'GOLD', 'help')],
               forbid=[r'action=interrupt']))

add('exam_platinum', 'town', '铂金章考试：说明与分享', '铂金章考试：说明与分享',
    '右键考官选晋级。听考官请考生从数堆改为一份一份数。宣布第二部分后，若考生向你求助，就把自己的一份食物喂给报名时选中的同伴。',
    '约 1 分钟', [*exam_hall(), *exam_study(pickup_delay=900),
                  *exam_candidate('gold', 90),
                  yk('marisa', -2, 3, 'peer', yaw=-90, nutrition=32)], env={'time': 'dawn'}, vantage=(0, 7),
    give=['minecraft:emerald 4', 'minecraft:apple'], reads=[('读考生', f'yukkuri status {sel("pet")} raw'), ('读同伴', f'yukkuri status {sel("peer")} raw')],
    smoke=dict(ticks=1050, commands=[(5, f'yukkuri scenario exam_platinum {sel("judge")} {sel("pet")}'),
                                    (700, f'yukkuri scenario exam_feed_peer {sel("peer")}')],
               expect=[r'(?s)action=answer task=clarify_groups success=true observed=1.*?key=exam\.mcyukkuri\.say\.count_portions'
                       r'.*?action=answer task=clarify success=(?:true|false)\b',
                       exam_promotion_expectation('GOLD', 'PLATINUM', 'explain', 'clarify', 'share')],
               forbid=[r'action=interrupt']))

add('date', 'town', '店方牵线，银章灵梦相亲', '店方牵线，银章灵梦相亲', '北侧店方饲养员牵线：有顶窝、真实银章的玩家灵梦，与店方真实登记的银章种油魔理沙害羞、靠近、蹭蹭', '等 2 秒登记窝位后，右键北侧饲养员；约 1 分钟', [
    human('caretaker_brother', 4, -4, 'keeper', 4, yaw=180, label='店方饲养员'), *shed(-4, -3),
    # Five is the largest ordinary live litter; two spare nests beside the mother's (centres two blocks apart, so the bolsters do not
    # overlap) cover extraCells(5) == 2 while the centre remains the mother's home.
    block(-6, 0, -3, 'mcyukkuri:yukkuri_nest'), block(-2, 0, -3, 'mcyukkuri:yukkuri_nest'),
    yk('reimu', 0, 0, 'bride', yaw=-90, lead=True), *own('bride'), yk('marisa', 8, 0, 'stud', yaw=90),
    f'yukkuri scenario badge {sel("bride")} silver false', f'yukkuri scenario stud {sel("keeper")} {sel("stud")}', home('bride', -4, -3)],
    give=['minecraft:emerald 6'], vantage=(0, 12))

add('pred_pets', 'town', '饿了的宠物捕食种：受训的忍住', '饿了的宠物捕食种：受训的忍住', '两只捕食者都饿了：受过教育的银章蕾米莉亚面对受保护宠物忍住；未学等待的铜章芙兰不听令。可用徽章菜单下达停止／允许捕猎，观察资格差别', '约 30 秒；指令可反复操作', [
    yk('remilia', 0, 0, 'rem', grade=2, yaw=-90, lead=True, nutrition=100), *own('rem'),
    f'yukkuri scenario badge {sel("rem")} silver false',
    yk('reimu', 5, 0, 'prey', grade=1, yaw=90), *own('prey'),
    f'yukkuri scenario badge {sel("prey")} bronze false',
    yk('flandre', 10, 0, 'fla', grade=1, yaw=90, nutrition=100), *own('fla'),
    f'yukkuri scenario badge {sel("fla")} bronze false',
    f'data remove entity {sel("fla")} YukkuriPet.SkillExperience.wait',
    set_status('rem', 'needs.nutrition', 25), set_status('fla', 'needs.nutrition', 25),
    box(8, 0, -2, 12, 2, -2, 'minecraft:glass_pane'), box(8, 0, 2, 12, 2, 2, 'minecraft:glass_pane'),
    box(8, 0, -1, 8, 2, 1, 'minecraft:glass_pane'), box(12, 0, -1, 12, 2, 1, 'minecraft:glass_pane')],
    give=['mcyukkuri:field_notebook 1'], vantage=(0, 12),
    reads=[('读受训捕食者', f'yukkuri status {sel("rem")} raw'), ('读未训捕食者', f'yukkuri status {sel("fla")} raw')])

add('transport', 'town', '装篮运输：三种容量', '装篮运输：三种容量',
    '喷雾后用普通篮装子油与两只赤油，试装第三只。蓝篮可装成体与两只赤油。绿篮可混装两只成体、子油与两只赤油。看物品提示，再到西侧石地逐只放出。',
    '喷雾后约 5 秒；每次点击干地放出一只', [
    yk('reimu', 0, 0, 'pet', grade=1, yaw=-90, lead=True), *own('pet'),
    box(6, 0, -3, 10, 1, -3, 'minecraft:oak_fence'), box(6, 0, 1, 10, 1, 1, 'minecraft:oak_fence'),
    box(6, 0, -2, 6, 1, 0, 'minecraft:oak_fence'), box(10, 0, -2, 10, 1, 0, 'minecraft:oak_fence'),
    yk('reimu', 8, -1, 'wild', yaw=90, label='野生灵梦', uuid=uid('transport', 'wild')),
    yk('marisa', -5, -4, 'basket_young', stage='child', label='小魔理沙', nutrition=27, grade=1,
       uuid=uid('transport', 'basket_young')),
    *[yk(kind, -9 + i * 2, -7, f'basket_baby_{i}', stage='baby', label=f'赤油 {i+1}',
         uuid=uid('transport', f'basket_baby_{i}'))
      for i, kind in enumerate(('reimu', 'marisa', 'alice', 'chen', 'patchouli'))],
    box(-10, -1, 4, -3, -1, 7, 'minecraft:stone')],
    give=['mcyukkuri:sleep_spray 8', 'mcyukkuri:yukkuri_carrier 1', 'mcyukkuri:yukkuri_carrier_fine 1',
          'mcyukkuri:yukkuri_carrier_large 1'], vantage=(0, 12))

# Eight pet young already wrapped, one style each (the cream knit is the plain cloth), side by side; the ninth, unwrapped, takes a cloth from the chest by hand.
_SWADDLE_LOOKS = (('knit', '奶油针织'), ('frill', '荷叶边蝴蝶结'), ('letter', '首字母'), ('tiger', '虎纹'),
                  ('outing', '外出'), ('fleece', '厚绒'), ('gingham', '格纹'), ('floral', '碎花'))
_swaddle_chest = ','.join(f'{{Slot:{i}b,id:"mcyukkuri:swaddle_cloth",count:1,components:{{"minecraft:custom_data":{{SwaddleStyle:"{style}"}}}}}}'
                          for i, (style, _) in enumerate(_SWADDLE_LOOKS))
_swaddle_pets, _swaddle_wear = [], []
for _i, (_style, _label) in enumerate(_SWADDLE_LOOKS):
    _pet = f'swaddle_{_style}'
    _swaddle_pets += [yk(('reimu', 'marisa', 'alice')[_i % 3], -3 + 2 * (_i % 4), 3 * (_i // 4), _pet, stage='baby' if _i % 2 == 0 else 'child', grade=1, label=_label,
                         lead=_i == 0, nutrition=90, energy=100, hygiene=100), *own(_pet)]
    _swaddle_wear += [f'data modify entity {sel(_pet)} YukkuriPet.Care merge value {{Swaddle:1b,SwaddleStyle:"{_style}"}}',
                      f'data modify entity {sel(_pet)} YukkuriPet.Care.WearOwner set from entity @s UUID']
add('swaddle_worn', 'town', '八款布襁褓穿上并排，可亲手包', '八款布襁褓穿上并排，可亲手包',
    '八只自家宠物赤油／子油（灵梦、魔理沙、爱丽丝轮流）已穿着八款襁褓并排站开，头顶名字是款式；最右一只赤油没包，右边木箱里有八款襁褓布，'
    '手持右键它包上，双手空着潜行右键后选「取下襁褓布」，也可手持襁褓布潜行右键取回同款。看各款外观与图标同款、首字母款背后是本物种首字母、发辫尾巴在布外不穿模。'
    '另给两块原色襁褓布和八种染料，在工作台用“襁褓布＋染料”合成改款',
    '随意', [*_swaddle_pets, *_swaddle_wear,
             yk('reimu', -5, 0, 'swaddle_bare', stage='baby', grade=1, label='未包', nutrition=90, energy=100, hygiene=100), *own('swaddle_bare'),
             block(-7, 0, 3, f'minecraft:chest[facing=east]{{Items:[{_swaddle_chest}]}}')],
    give=['mcyukkuri:swaddle_cloth 2', 'minecraft:crafting_table 1',
          *[f'minecraft:{dye}_dye 1' for dye in ('white', 'pink', 'red', 'orange', 'light_blue', 'brown', 'blue', 'green')]],
    reads=[(f'读{label}款襁褓', f'data get entity {sel("swaddle_" + style)} YukkuriPet.Care') for style, label in _SWADDLE_LOOKS], vantage=(0, 5))

add('harness_walk', 'town', '亲手给宠物系胴带牵着走', '亲手给宠物系胴带牵着走',
    '先用胴带右键宠物，再用拴绳牵引。走动后空手右键解绳，再潜行空手右键选「摘下胴带」；观察拴绳与胴带各归还一次，宠物恢复日常', '随意', [
        yk('reimu', 0, 0, 'harness_pet', grade=1, lead=True, nutrition=60, energy=100, hygiene=100), *own('harness_pet')],
    give=['mcyukkuri:pet_harness 1', 'minecraft:lead 1', 'minecraft:apple 6'],
    reads=[('读牵引宠物', f'yukkuri status {sel("harness_pet")} raw')], vantage=(0, 8))

add('feed_wild', 'town', '居民牵宠散步，喂饿坏的野油', '居民牵宠散步，喂饿坏的野油',
    '爱派居民（哥哥和姐姐）牵着拴了绳的宠物灵梦在街上散步，遇到饿坏的野生魔理沙：会拿出食物喂它', '约 1–3 分钟', [
    'yukkuri human walker ~ ~ ~ brother', 'yukkuri human walker ~-6 ~ ~ sister', yk('marisa', 6, 3, 'wild', nutrition=20, yaw=90, lead=True)],
    vantage=(0, 12))

_oniisan_victim = uid('oniisan_prey', 'victim')
add('oniisan_prey', 'town', '鬼威惨在街上，灵梦母女与旁观者见了就逃', '鬼威惨在街上，灵梦母女与旁观者见了就逃',
    '鬼威惨照常在街上走动，东侧有野生灵梦，她的孩子与陌生魔理沙都在旁边；看见威胁会逃，附近目击伤害也会受惊', '持续', [
    'yukkuri human oniisan ~ ~ ~',
    yk('reimu', 5, 0, 'oniisan_victim', uuid=_oniisan_victim, label='被盯上的灵梦', lead=True, **pair_ready()),
    yk('reimu', 9, 1, 'oniisan_child', stage='child', label='她的孩子', kin=f'Mother:{uuid_nbt(_oniisan_victim)}', **pair_ready()),
    yk('marisa', 8, -4, 'oniisan_witness', label='陌生旁观者', **pair_ready())], vantage=(0, 12),
    reads=[('读猎物反应', f'yukkuri status {sel("oniisan_victim")} raw'), ('读旁观反应', f'yukkuri status {sel("oniisan_witness")} raw')])

add('attack_witness', 'town', '你打灵梦：看见的逃，墙后的不知', '你打灵梦：看见的逃，墙后的不知',
    '徒手打一下中间的灵梦：右侧陌生油能看见现场，会受惊逃开；左侧被实墙遮住的陌生油不会隔墙感知。个体仍会自由移动', '靠近后直接操作', [
        yk('reimu', 0, 0, 'attack_victim', label='被攻击者', lead=True, **pair_ready()),
        yk('marisa', 4, 0, 'attack_witness', label='看得见的旁观者', **pair_ready()),
        yk('chen', -4, 0, 'attack_hidden', label='墙后的旁观者', **pair_ready()),
        box(-2, 0, -3, -2, 2, 3, 'minecraft:stone')], env=DAY, vantage=(0, 8),
    reads=[('读可见旁观者', f'yukkuri status {sel("attack_witness")} raw'), ('读墙后旁观者', f'yukkuri status {sel("attack_hidden")} raw')])

# ---- shop and factories: the whole building is placed by `human build`, which levels the lot and fills the shop itself
STOCK = '@e[type=#mcylab:yukkuri,distance=..60,nbt={NeoForgeData:{HumanCommerce:{Use:"stock"}}}]'
CORE_FILLING = 'mcyukkuri:core_filling[minecraft:custom_data={FillingVersion:1,Composition:"core",Method:"core",Nutrition:10,Sweetness:90,Odor:0,Variant:"reimu"}] 2'
# Any shop staff member (all three have the same job and the same menu).
SHOP_STAFF = '@e[nbt={HumanJob:1},distance=..80,sort=nearest,limit=1]'
# Template x/z -> scene dx = 20 - x, dz = 17 - z; the shop floor (template y3) stands two blocks above the street (scene dy 2).
add('shop_build', 'shop', '整座商店：任一店员同一菜单，后院放养，店员添食清厕', '整座商店：任一店员同一菜单，后院放养，店员添食清厕',
    '商店整体放下：临街展舱（金章爱丽丝、子油灵梦与橙）、单独隔开的捕食种房（银章蕾米莉亚、子油芙兰），后院放养三只子油。'
    '三位店员没有区别，右键哪一位都打开同一个菜单：在售页列出店里此刻真实在场的全部个体，可购买或交定金预订；收购页可把自己的油卖给店里。'
    '展舱和捕食种房的食盆清空了、两个厕所放了便便：店员会开铁门走进栏里添粮、把便便清进后屋的桶，出门随手关门。店内方块、展示框和在售个体都不能动',
    '建筑几秒；白天约 10–60 秒看店员添食、清厕',
    ['yukkuri human build shop ~8 ~ ~17 south'],
    # The shop opens with its bowls served; the street pen's and the predator room's are emptied so feeding starts in two pens at once
    # (not three staff crossing the yard's one door together).
    late=['data merge block ~18 ~2 ~10 {Items:[]}', 'data merge block ~4 ~2 ~10 {Items:[]}',
          'data merge block ~12 ~2 ~10 {Waste:3.0d}', 'data merge block ~5 ~2 ~-12 {Waste:3.0d}'],
    give=['minecraft:emerald 128', 'mcyukkuri:badge_handbook', CORE_FILLING], env=DAY, vantage=(8, 21, 180, 0),
    reads=[('店铺登记（shop list）', 'yukkuri human shop list'),
           ('读店内个体档案', f'yukkuri status {STOCK} raw'),
           ('读徽章与技能存档', f'execute as {STOCK} run data get entity @s YukkuriPet')])
# The window pen's authored temperaments (art/structures/human-world/build.py): Chen shy, gold Alice proud, the young Reimu shows off;
# 17 days on the shelf (past the 9 over which hope fades) turn the predator room's Remilia's answer into a sulk.
BOOTH = '@e[type=mcyukkuri:{},nbt={{YukkuriPet:{{Grade:{}}},NeoForgeData:{{HumanCommerce:{{Use:"stock"}}}}}},distance=..80,limit=1]'
add('shop_window', 'shop', '展舱里的油看见客人：招揽、问好、摆架子、害羞、闹别扭', '展舱里的油看见客人：招揽、问好、摆架子、害羞、闹别扭',
    '站在街面橱窗前或店内过道，沿临街展舱和捕食种房慢慢走：每只隔着玻璃按自己的脾气回应你——子油灵梦凑到玻璃前卖力表现，金章爱丽丝端着徽章摆架子，'
    '子油橙往后躲，上架 17 天的蕾米莉亚背过身闹别扭；规矩好的个体会有点紧张地鞠躬问好。没有高频抖动，只有动作和表情。你走开时恳求过的会失落',
    '建筑几秒；走到展舱 8 格内，1–2 秒内回应',
    ['yukkuri human build shop ~8 ~ ~17 south', 'scoreboard objectives add scn_t dummy', 'execute store result score #now scn_t run time query gametime',
     'scoreboard players remove #now scn_t 408000',
     f'execute as {BOOTH.format("remilia", 2)} store result entity @s NeoForgeData.HumanCommerce.ForSaleSince long 1 run scoreboard players get #now scn_t'],
    env=DAY, vantage=(9, 14, 180, 10),
    reads=[('读在售个体（上架天数与回应）', f'yukkuri status {STOCK} raw')])
add('shop_buy', 'shop', '买下金章爱丽丝：店员开铁门进展舱抱她装篮，送到柜台', '买下金章爱丽丝：店员开铁门进展舱抱她装篮，送到柜台',
    '进店右键任一店员，在“在售”里买下金章爱丽丝：她高兴地跳一下，站着等。最先空下来的店员拿空运输篮走到展舱铁门前，开门进去，'
    '她钻进篮子的那一刻才扣款，店员出门关门，提篮回到柜台交到你手上（不在柜台边就放在柜台上，只有你能拿）。隔壁的子油橙看着她被带走。'
    '没钱、天黑、被别人预订时点购买会直接说原因；装篮前你离开商店或天黑，订单取消并告诉你原因，不扣钱',
    '建筑几秒；买下后约 10–30 秒交到手上', ['yukkuri human build shop ~8 ~ ~17 south'],
    give=['minecraft:emerald 128'], env=DAY, vantage=(5, 14, 180, 10),
    reads=[('店铺登记（shop list）', 'yukkuri human shop list')])
add('shop_restock', 'shop', '三天补货：多余的成体装篮送走，新到的子油进窝', '三天补货：多余的成体装篮送走，新到的子油进窝',
    '后院多放了两只上架已久的成体魔理沙，场景立刻触发一次三天补货：店里只留两只成体，最久没卖出去的两只被店员装篮送进后屋的进出货桶；'
    '新到的子油装在篮子里放进桶，等有空窝时店员提篮进后院放进窝里。交了定金预订的个体不会被送走。下一次补货在三天后',
    '建筑几秒；补货后约 1–2 分钟看店员出货、上架',
    ['yukkuri human build shop ~8 ~ ~17 south', 'scoreboard objectives add scn_t dummy', 'execute store result score #now scn_t run time query gametime',
     'scoreboard players remove #now scn_t 120000'],
    late=[f'yukkuri human shop stock {SHOP_STAFF} marisa ~13 ~2 ~-8', f'yukkuri human shop stock {SHOP_STAFF} marisa ~9 ~2 ~-9',
          'execute as @e[type=mcyukkuri:marisa,distance=..80,nbt={YukkuriFamily:{Stage:"ADULT"},NeoForgeData:{HumanCommerce:{Use:"stock"}}}] '
          'store result entity @s NeoForgeData.HumanCommerce.ForSaleSince long 1 run scoreboard players get #now scn_t',
          f'yukkuri human shop restock {SHOP_STAFF}'],
    env=DAY, vantage=(12, 0, 180, 30),
    reads=[('店铺登记（shop list）', 'yukkuri human shop list'), ('读在售个体', f'yukkuri status {STOCK} raw')])

# ====================================================================================================== clips (buttons are built in build_museum_full.py)


# Adult state demonstrations only set saved starting state; the normal server AI owns the rest.
def agitation_start(phase):
    return f'YukkuriAgitation:{{Phase:"{phase}",PhaseTicks:0,Cooldown:0,NextCheck:2400}}'


def _verify_leak_scene(window):
    rolls = re.findall(r'\[leak\][^\n]*uuid=(\S+) t=(\d+)[^\n]* roll=(\S+) chance=(\S+) flow_at=(\d+) puddle_at=(\d+)', window)
    if len({row[0] for row in rolls}) != len(rolls):
        return 'an actor leaked again inside the 1200-tick cooldown'
    for actor, start, roll, chance, flow, puddle in rolls:
        if not 0 <= float(roll) < float(chance) or not 6 <= int(flow) - int(start) <= 10:
            return f'{actor}: invalid roll or first-spurt duration'
        if not re.search(rf'\[status\][^\n]*uuid={actor}\b[^\n]*behavior\.leak_trace_at: -1 -> {puddle}\b', window):
            return f'{actor}: no skin trace at the puddle tick'
    return None


add('excited_leak', 'meadow', '短落差受惊：赤油、子油、成油漏尿', '短落差受惊：赤油、子油、成油漏尿',
    '从左到右是自制较低的赤油、子油和成油各一只，前排另有一只矮身豆油。开场从两格多的安全高度落下，各自真实抽签。'
    '看第一股、漏 A 与脚底湿边，矮身豆油可对照脸部是否干净。皮肤上的尿痕随个体移动，小水滩留在原地逐渐淡去。'
    '成油较少漏尿，没看到可清场重开。',
    '开场约 1–4 秒，水滩可继续观察', [
        yk('reimu', -2, 0, 'leak_baby', dy=2.5, stage='baby', yaw=0, label='赤油', self_control=0, also=('leak_actor',)),
        yk('reimu', 0, 0, 'leak_child', dy=2.5, stage='child', yaw=0, label='子油', lead=True, self_control=0, also=('leak_actor',)),
        yk('reimu', 2, 0, 'leak_adult', dy=2.5, yaw=0, label='成油', self_control=0, also=('leak_actor',)),
        yk('reimu', -1, 2, 'leak_bean', dy=2.5, stage='baby', yaw=0, label='豆油', congenital='BEAN_BODY',
           self_control=0, also=('leak_actor',)),
    ], env=DAY, vantage=(0, 6, 180, 15),
    # A single adult is unlikely to leak even on two genuine falls. Check a real winning drop and its
    # presentation, then validate the timing and skin trace of every logged winner.
    smoke=dict(ticks=390,
               commands=[(180, 'execute as @e[tag=scn_leak_actor] at @s run tp @s ~ ~2.5 ~')],
               expect=[r'\[leak\][^\n]*stage=(?:baby|juvenile|adult) self_control=0 stimulus=drop\b',
                       r'\[status\][^\n]*behavior\.expression: \w+ -> leak_flow',
                       r'\[status\][^\n]*behavior\.expression: leak_flow -> side_teary'],
               forbid=[r'\[body\][^\n]* death settled\b'], verify=_verify_leak_scene))


add('leaf_swaddle_neglect', 'meadow', '没人照看，襁褓里积满便便', '没人照看，襁褓里积满便便',
    '两只裹着树叶襁褓的子油没有亲油：便意 95 的一只马上把便便排进襁褓（soil 按体积增加，卫生、不安恶化，襁褓下沿染色）；'
    '已积 3 体积单位的一只早期再满就溢到地上。失护约 1.25／2.5／3.75 天（随体质平移）后依次黄褐干枯、停止溢出并肿胀、损失生命直至便秘死亡；'
    '用观察台倍速和推进按钮加快，持续喂苹果维持真实供食。按“解开积满子油襁褓”调用亲油同一解开动作，积便按体积落地，肿胀和虚弱逐渐恢复',
    '立即积便；长期后果用倍速观察', [
        # Early children have enough real childhood left to reach late neglect before outgrowing the leaf.
        yk('reimu', 0, 0, 'kid', stage='child', share=.23, yaw=180, lead=True, nutrition=80, bladder=95, hygiene=80, extra=leaf_wrap()),
        yk('marisa', 3, 0, 'kid_full', stage='child', share=.23, yaw=180, nutrition=80, bladder=92, hygiene=50, extra=leaf_wrap(soil=3))],
    give=['minecraft:apple 64'], env=DAY, vantage=(1.5, 3.5, 180, 20),
    reads=[*leaf_reads(('刚积便子油（含失护阶段）', 'kid'), ('积满子油（含失护阶段）', 'kid_full')),
           ('解开积满子油襁褓', f'yukkuri scenario unwrap {sel("kid_full")}')],
    capture=dict(shots=(1, 40, 200, 600, 1000), sequences=((60, 1200, 20),), observe=False,
                 views={'': dict(), 'kid_side': dict(follow=f'@e[tag={tag("kid")},limit=1]', offset=(.9, .3, 0)),
                        'full_rear': dict(follow=f'@e[tag={tag("kid_full")},limit=1]', offset=(.55, .35, -.75))}),
    smoke=dict(ticks=1200,expect=[r'leaf swaddle soiled [^\n]*volume=',r'leaf swaddle overflowed',r'poop placed [^\n]*volume='],forbid=[]))

# Art proof of the later neglect stages (art decision swaddle-neglect): the wraps start at real neglect values one band apart, so the
# leaf colours, the swelling at increasing amounts and its recovery after an unwrap, and a retention death are all on screen at once.
# The neglect chain itself (soiling, overflow, the stages advancing) is leaf_swaddle_neglect; this scene only starts later along it.
_NEGLECT_ROW = (('s0', 0, 0), ('s1', 1, 15000), ('s2', 2, 45000), ('s3a', 3, 62000), ('s3', 3, 80000))
add('leaf_swaddle_neglect_stages', 'meadow', '襁褓失护各段：变色、肿胀、便秘死', '襁褓失护各段：变色、肿胀、便秘死',
    '一排赤油的树叶襁褓按失护程度排开：新叶、积便渍斑、黄褐干枯（开始渐肿）、硬褐潴留（肿胀渐大到满）；右端一只已到致死段且生命很低，'
    '约一分钟内便秘死：遗体苍白、仍裹着硬褐叶片并保留肿胀，和一只熟睡的赤油同在小围栏里可对照脸。后排一只满肿的按“解开后排赤油襁褓”（冒烟 200 tick 自动）'
    '解开：积便按体积落地、脏叶作废，身体仍肿、随后逐渐消肿。失护值只是起点，之后照常推进',
    '约一分钟', [
        *(yk('reimu', round(i * 1.2, 1), 0, name, stage='baby', yaw=180, lead=name == 's3', nutrition=85, bladder=0, hygiene=40 if soil else 90,
             extra=leaf_wrap(soil=soil, neglect=neglect)) for i, (name, soil, neglect) in enumerate(_NEGLECT_ROW)),
        # a fence pen keeps the dying baby next to the sleeping one, so the remains and a sleeping face share the frame
        *(block(x, 0, z, 'minecraft:oak_fence') for x in range(7, 11) for z in (-1, 0, 1) if x in (7, 10) or z != 0),
        yk('reimu', 8, 0, 'dying', stage='baby', yaw=180, nutrition=85, bladder=0, hygiene=30,
           extra=leaf_wrap(soil=3, neglect=120000) + ',Health:2f'),
        yk('reimu', 9, 0, 'sleeper', stage='baby', yaw=180, nutrition=85, energy=3, hygiene=90),
        yk('reimu', 2, 2, 'unwrapped', stage='baby', yaw=180, nutrition=85, bladder=0, hygiene=30, extra=leaf_wrap(soil=3, neglect=80000))],
    env=DAY, vantage=(3.5, 3, 180, 25),
    reads=[*leaf_reads(('致死段赤油', 'dying'), ('后排赤油', 'unwrapped'), ('硬褐赤油', 's3')),
           ('解开后排赤油襁褓', f'yukkuri scenario unwrap {sel("unwrapped")}')],
    capture=dict(shots=(1, 40, 200, 600, 1300, 1500), sequences=((60, 1560, 30),), observe=False,
                 # a baby is about a sixth of a block tall: the cameras stand about half a block off, near its eye level
                 views={'': dict(follow=f'@e[tag={tag("s3a")},limit=1]', offset=(.45, .25, .45)),
                        's1_front': dict(follow=f'@e[tag={tag("s1")},limit=1]', offset=(.2, .2, .6)),
                        's2_rear': dict(follow=f'@e[tag={tag("s2")},limit=1]', offset=(.35, .22, -.5)),
                        's3_side': dict(follow=f'@e[tag={tag("s3")},limit=1]', offset=(.65, .18, -.1)),
                        's3_rear': dict(follow=f'@e[tag={tag("s3")},limit=1]', offset=(.35, .22, -.5)),
                        'unwrap_side': dict(follow=f'@e[tag={tag("unwrapped")},limit=1]', offset=(.6, .2, -.15)),
                        'remains': dict(follow=f'@e[tag={tag("sleeper")},limit=1]', offset=(0, .3, 1.1)),
                        'remains_side': dict(follow=f'@e[tag={tag("sleeper")},limit=1]', offset=(1.0, .3, .3)),
                        'remains_rear': dict(follow=f'@e[tag={tag("sleeper")},limit=1]', offset=(0, .35, -1.1))}),
    smoke=dict(ticks=1600, commands=[(200, f'yukkuri scenario unwrap {sel("unwrapped")}')],
               expect=[r'leaf swaddle kept on remains reason=retention', r'death settled into remains[^\n]*internal=swaddle_retention category=retained',
                       r'swaddle neglect stage=0 [^\n]*releasedVolume=3'], forbid=[]))

add('agitation_relief', 'meadow', '发情期的爱丽丝想素鸡立', '发情期的爱丽丝想素鸡立',
    '爱丽丝处于持续发情期：空闲观察时偶尔说想素鸡立；喂食、避险会暂停表现但保留剩余期，期满未配对改播heat_unmet未解台词。给对照个体喂催情剂可现场听heat_start开始台词', '约一分钟', [
        yk('alice', 0, 0, 'restless', lead=True, kin='SexDrive:1200', extra=agitation_start('HEAT'), **pair_ready()),
        yk('alice', 6, 0, 'ordinary', extra=agitation_start('IDLE'), **pair_ready()),
        *own('restless'), *own('ordinary')], env=DAY,
    give=['mcyukkuri:stimulant 2'],
    reads=[('读成体状态', f'yukkuri status {sel("restless")}')])

_pair_mother, _pair_father = uid('adult_pairing', 'mother'), uid('adult_pairing', 'father')


def pairing_permission(partner):
    return agitation_start('IDLE') + ',YukkuriPet:{Permits:[{Partner:' + uuid_nbt(partner) + ',Until:24000000L}]}'


add('adult_pairing', 'meadow', '两只伴侣走近擦脸后素鸡立', '两只伴侣走近擦脸后素鸡立',
    '两只健康成体互相接纳，近距互见后同步回应 1.4 秒（头顶爱心），再由父方绕到母方后方。观察双方接触循环、四拍台词和释放时的爱心。峰值持续 3 秒后，魔理沙露出得意脸，灵梦温和闭眼。喷雾、带走或失去接触可打断', '约半分钟至数分钟', [
        *shed(5, 0), *shed(5, 6),
        block(4, 0, 0, 'mcyukkuri:yukkuri_nest'), block(6, 0, 0, 'mcyukkuri:yukkuri_nest'),
        block(4, 0, 6, 'mcyukkuri:yukkuri_nest'), block(6, 0, 6, 'mcyukkuri:yukkuri_nest'),
        yk('reimu', 0, 1, 'mother', lead=True, uuid=_pair_mother, kin='SexDrive:1200', extra=pairing_permission(_pair_father), **pair_ready()),
        yk('marisa', 0, 3, 'father', uuid=_pair_father, kin='SexDrive:1200', extra=pairing_permission(_pair_mother), **pair_ready()),
        *own('mother'), *own('father'),
        f'yukkuri scenario bond {sel("mother")} {sel("father")}',
        f'yukkuri scenario mate {sel("mother")} {sel("father")}',
        home('mother', 5, 0), home('father', 5, 6)], env=DAY, vantage=(-5, -2, -52, 13),
    give=['mcyukkuri:sleep_spray 2', 'mcyukkuri:yukkuri_carrier 1'],
    capture=dict(shots=(1, 40, 1920), sequences=((80, 1920, 8),), observe=False,
                 views={'': dict(follow=sel('mother'), offset=(-3.6, .9, 1.3)),
                        'side': dict(follow=sel('mother'), offset=(2.8, 1.1, .15))}),
    reads=[('读母方配对阶段', f'yukkuri status {sel("mother")} raw'), ('读父方配对阶段', f'yukkuri status {sel("father")} raw')])

_widow, _new_partner = (uid('widow_repair', slot) for slot in ('widow', 'new_partner'))
# A real heat period long enough to watch the couple and press the button: three minutes at 1x, one at 3x. Every 200 ticks of heat costs
# the bearer 1 nutrition, so a much longer one would leave her too hungry to conceive after the wait.
_WIDOW_HEAT = 1200      # the saved breeding period the game writes on its own (FamilyLife.BREEDING_PERIOD_TICKS); load clamps it


def widow_death_prompt():
    """A player-pressed, real death of the original partner: the ledger records DEAD and only then may the candidate pair with her."""
    kill = f'/execute positioned ~ ~ ~ run kill @e[tag={tag("old_partner")},distance=..140,limit=1,sort=nearest]'
    return 'tellraw @s ' + json.dumps([
        {'text': '看过寡妇没跟候选走后点 ', 'color': 'gray'},
        {'text': '【结束原伴侣】', 'color': 'red', 'bold': True, 'clickEvent': {'action': 'run_command', 'value': kill}},
        {'text': '（真实死亡，账本记 DEAD），再等候选自己求偶。', 'color': 'gray'}], ensure_ascii=False, separators=(',', ':'))


add('widow_repair', 'meadow', '原伴侣死后才肯和新候选配对', '原伴侣死后才肯和新候选配对',
    '东边玻璃院子里寡妇守着自己的大窝，候选在小棚的窝里，两只都在发情期（1 分钟）；西边小围栏里是她的原伴侣（读数里寡妇的 partner 指向它）。'
    '交配许可寡妇和候选彼此都给了，但没有给原伴侣。'
    '看点：①寡妇不跟候选走：候选走近求偶，她转过身背对着他、满脸没兴趣（日志 court refused reason=partner_alive），他随即放弃、一阵子不再找她；'
    '②按聊天里的【结束原伴侣】按钮——原伴侣走真实死亡，账本记 DEAD（日志 death settled … cause=genericKill），地上留下遗骸；'
    '③寡妇先露出丧失亲属的呆住、哭泣反应，反应结束后候选自己走近求偶；受孕时写 partnership formed … allowWidow=true。读数看双方 partner、发情与求偶阶段',
    '先看寡妇没跟候选走约半分钟，再点按钮；按下后约十秒到半分钟（发情期刚好结束时求偶会中断一次，之后会重来）', [
        # A glass yard keeps the three beside their own nests (a loose pet wanders off on its own); glass does not hide the bodies.
        box(1, 0, -2, 6, 2, -2, 'minecraft:glass_pane'), box(1, 0, 6, 6, 2, 6, 'minecraft:glass_pane'),
        box(1, 0, -1, 1, 2, 5, 'minecraft:glass_pane'), box(6, 0, -1, 6, 2, 5, 'minecraft:glass_pane'),
        # A single roof keeps real homes covered without pillars between the pair or the contact camera.
        box(1, 3, -2, 6, 3, 6, 'minecraft:oak_planks'),
        # Low wild bedding leaves the actual eye-to-eye ray clear; cushion bolsters hid a death across the yard.
        *large_nest(3, 0, 'mcyukkuri:large_wild_nest'), *large_nest(4, 4, 'mcyukkuri:large_wild_nest'),
        # Close enough to witness the real death through glass. The mate's body is grieved, not fled from.
        box(-3, 0, -1, 0, 2, -1, 'minecraft:glass_pane'), box(-3, 0, 4, 0, 2, 4, 'minecraft:glass_pane'),
        box(-3, 0, 0, -3, 2, 3, 'minecraft:glass_pane'), box(0, 0, 0, 0, 2, 3, 'minecraft:glass_pane'),
        # Minecraft sight rays collide with glass. Top slabs leave a real low viewing slit:
        # selected adult bodies are .6 high, with eyes about .32 above their feet: sight fits below .5, bodies do not.
        box(-3, 0, -1, 0, 0, -1, 'minecraft:stone_slab[type=top]'), box(-3, 0, 4, 0, 0, 4, 'minecraft:stone_slab[type=top]'),
        box(-3, 0, 0, -3, 0, 3, 'minecraft:stone_slab[type=top]'), box(0, 0, 0, 0, 0, 3, 'minecraft:stone_slab[type=top]'),
        box(1, 0, -1, 1, 0, 5, 'minecraft:stone_slab[type=top]'),
        box(-3, 3, -1, 0, 3, 4, 'minecraft:oak_planks'), *large_nest(-2, 0, 'mcyukkuri:large_wild_nest'),
        yk('reimu', 4, 2, 'widow', lead=True, uuid=_widow, label='待再配对者', boldness=15, attachment=90,
           kin=f'SexDrive:{_WIDOW_HEAT}', extra=pairing_permission(_new_partner), **pair_ready()),
        # A fresh UUID allows restarting this scene after the ledger has recorded the previous original partner's death.
        yk('marisa', -2, 3, 'old_partner', label='原伴侣', boldness=15, attachment=90,
           kin=f'Partner:{uuid_nbt(_widow)}', extra=pairing_permission(_widow), **pair_ready()),
        yk('marisa', 3, 4, 'new_partner', uuid=_new_partner, label='新候选', boldness=15, attachment=90,
           kin=f'SexDrive:{_WIDOW_HEAT}', extra=pairing_permission(_widow), **pair_ready()),
        f'data modify entity {sel("widow")} YukkuriFamily.Partner set from entity {sel("old_partner")} UUID',
        *own('widow'), *own('old_partner'), *own('new_partner'),
        f'yukkuri scenario bond {sel("widow")} {sel("new_partner")}',
        f'yukkuri scenario bond {sel("widow")} {sel("old_partner")}',
        home('widow', 3, 0), home('old_partner', -2, 0), home('new_partner', 4, 4),
        widow_death_prompt()], env=DAY,
    give=['mcyukkuri:yukkuri_carrier 1', 'minecraft:apple 16'],
    note='不调用 mate、不预设胎次。三只都很胆小（活动半径约 10 格），所以就在自己窝边活动；寡妇的交配许可只写了候选，原伴侣因此不会在场景里立刻怀上。'
         '原伴侣单独关在近处的玻璃围栏，寡妇能看见它死亡；气味仍会影响候选，配偶自己不逃离遗骸。'
         '原伴侣在确认死亡后仍保留，直到正常受孕写入新伴侣；REMOVED 对照操作和存档重载步骤见试玩场说明。',
    capture=dict(shots=(1, 40, 200, 600, 900, 1200, 1800), sequences=((60, 300, 4), (600, 720, 4), (740, 1200, 10)),
                 follow=sel('widow'), offset=(2.5, 1.25, 2.8), observe=False),
    reads=[('读寡妇的关系与求偶阶段', f'yukkuri status {sel("widow")} raw'),
           ('读候选的关系与求偶阶段', f'yukkuri status {sel("new_partner")} raw')])

add('alice_hunt', 'meadow', '发情爱丽丝追袭素鸡立', '发情爱丽丝追袭素鸡立',
    '爱丽丝带着癫狂笑蹦跳追击，目标发现她会喊出警报台词并逃跑。观察扑中后的接触循环、四拍台词与表情。释放时有尘粒和爱心，峰值持续 3 秒，期间受方仍被压住。峰值结束后受方脱身，爱丽丝接完成表情。喷雾或攻击可打断动作', '约半分钟，可重复观察', [
        yk('alice', 0, 0, 'hunter', lead=True, extra=agitation_start('ALICE_WATCH'), **pair_ready()),
        yk('reimu', 4, 0, 'prey', extra=agitation_start('IDLE'), **pair_ready()),
        yk('marisa', 9, 3, 'witness', extra=agitation_start('IDLE'), **pair_ready()),
        *own('hunter'), *own('prey'), *own('witness')], env=DAY,
    give=['mcyukkuri:sleep_spray 2', 'mcyukkuri:yukkuri_carrier 1'],
    capture=dict(shots=(1, 40, 700), sequences=((60, 660, 2),),
                 views={'': dict(follow=sel('prey'), offset=(2.4, 1.15, 2.1)),
                        'side': dict(follow=sel('hunter'), offset=(-2.8, 1.05, .5))}),
    note='可在抽签前用喷雾或攻击阻止结算。抽签成功后，受方在压制中表现出受惊，松开后才触发逃跑反应。已经建立的胎次会保留。',
    reads=[('读攻击魔状态（含中断结果）', f'yukkuri status {sel("hunter")} raw'), ('读受方状态', f'yukkuri status {sel("prey")} raw')])

def _aftermath_pens(lanes):
    """One fenced pen per lane with a nest under a shed; the mother's nutrition, energy and health and the lane's label differ.
    The source Alice stands right behind the pen's north fence, so the panic has a visible cause while the fence still blocks sight
    (a predator in plain view would keep the mother in a threat reaction)."""
    out = []
    for lane, dx, nutrition, energy, label, _ in lanes:
        nest = ([block(dx + 1, 0, 2, 'mcyukkuri:yukkuri_nest'), block(dx + 5, 0, 2, 'mcyukkuri:yukkuri_nest')]
                if lane == 'depletion' else large_nest(dx + 2, 2, 'mcyukkuri:large_wild_nest'))
        out += [
            box(dx - 6, -1, -5, dx + 6, -1, 7, 'minecraft:oak_planks'),
            box(dx - 6, 0, -5, dx + 6, 0, -5, 'minecraft:oak_fence'), box(dx - 6, 0, 7, dx + 6, 0, 7, 'minecraft:oak_fence'),
            box(dx - 6, 0, -4, dx - 6, 0, 6, 'minecraft:oak_fence'), box(dx + 6, 0, -4, dx + 6, 0, 6, 'minecraft:oak_fence'),
            *shed(dx + 3, 2), *nest,
            yk('reimu', dx, 0, f'affected_{lane}', label=label, lead=lane == lanes[0][0], nutrition=nutrition, energy=energy,
               extra=agitation_start('IDLE') + (',Health:2.5f' if lane == 'depletion' else '')),
            yk('alice', dx, -7, f'source_{lane}', extra=agitation_start('IDLE'), **pair_ready()),
            *own(f'affected_{lane}'), *own(f'source_{lane}'), home(f'affected_{lane}', dx + (3 if lane == 'depletion' else 2), 2),
            *([item('minecraft:apple', 1, dx + 2.5 + x, 2.5 + z, dy=.08)
               for x, z in [(.24, .24), (-.24, .24), (.24, -.24), (-.24, -.24)]] if lane == 'growth' else [])]
    return out


def aftermath(sid, seen, watch, lanes, vantage):
    add(sid, 'meadow', seen, seen, watch,
        '约 20 秒长成。读母体状态可查看进度、营养、生命和耗馅级别。',
        _aftermath_pens(lanes),
        env=DAY, give=['minecraft:apple 16', 'mcyukkuri:stem_shears 1', 'mcyukkuri:bottle_stem_shears 1'], vantage=vantage,
        note='通过场景入口建立事后胎次；每个围栏北侧栅栏外坐着的爱丽丝只作血缘登记的来源（让开场惊慌有个看得见的对象）。',
        late=[f'yukkuri scenario aftermath {sel(f"affected_{lane}")} {sel(f"source_{lane}")} {count}' for lane, _, _, _, _, count in lanes],
        reads=[(f'读{label}的母体与茎生进度', f'yukkuri status {sel(f"affected_{lane}")} raw') for lane, _, _, _, label, _ in lanes]
              + ([('读遗体', 'yukkuri body info @e[type=mcyukkuri:yukkuri_remains,distance=..40,limit=1,sort=nearest]')]
                 if lanes[0][0] == 'depletion' else []))


aftermath('aftermath_growth', '茎生长出三果：有食与无食', '左右各有一只灵梦从短芽长出三果。左栏营养 100，窝垫上有苹果；右栏营养 55，地上没有食物。'
          '看母油在分娩站位就地进食、茎果继续长大，也可把苹果投到右栏母油嘴边。',
          (('growth', -8, 100, 100, '有食·营养100', 3), ('burden', 8, 55, 60, '无食·营养55', 3)), (0, 16))
aftermath('aftermath_depletion', '五果耗馅死亡：遗体留着未熟实子', '灵梦已经空腹，只剩 2.5 点生命。看短芽、五果、颤抖求助和见底后的三秒保命。'
          '遗体上的实子停在死时的大小。可拾起再放下遗体，用茎剪检查是否还能救活实子。',
          (('depletion', 0, 0, 10, '耗竭·营养0', 5),), (0, 12))

# ====================================================================================================== new since the last round (not yet played)
NEW = '待验证：新场景，尚未在游戏里实测。'
REMAINS_NEAR = '@e[type=mcyukkuri:yukkuri_remains,distance=..48,limit=1,sort=nearest]'
HARSH = 15                     # Low friendliness; fear and recognition remain separate.


# ---- injuries and remains (open ground: nothing to eat, nothing to hide behind)
add('body_mold', 'body', '油霉1／3／6／8级与去霉药', '油霉1／3／6／8级与去霉药',
    '四只灵梦分别为油霉 1／3／6／8 级，对应四档灰绿菌落；先绕行看侧背面，再用去霉药核对真实阶段回退及原位菌落缩小。终末个体会自然进入濒死，完整皮面不应凭中枢损伤冒出创洞',
    '立即看外观；终末约 20 秒后濒死', [
        *[yk('reimu', -6 + 4 * i, 0, f'mold{i}', lead=i == 0, label=f'油霉{level}', **pair_ready())
          for i, level in enumerate((1, 3, 6, 8))],
        *[set_status(f'mold{i}', 'health.mold', level) for i, level in enumerate((1, 3, 6, 8))]],
    give=['mcyukkuri:mold_remover 8'], env=DAY, vantage=(0, 10),
    reads=[(f'读油霉{level}', f'yukkuri status {sel(f"mold{i}")} raw') for i, level in enumerate((1, 3, 6, 8))])

add('airborne', 'body', '抛起时普通油惊叫，飞行种不叫', '抛起时普通油惊叫，飞行种不叫',
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

add('house_health', 'body', '家油赤油、子油、成体有多脆', '家油赤油、子油、成体有多脆',
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
_wound_shapes = (('scratch','scratch','擦伤'),('tear','tear','刀割'),('dent','dent','钝击'),
                 ('puncture','tear','窄刺'),('thorn','scratch','细刺'),('impale','tear','尖锥'),
                 ('bite','tear','啃咬'),('blast','tear','爆裂'))
_wound_materials=[]
for i,(shape,kind,label) in enumerate(_wound_shapes):
    name=f'shape{i}'
    _wound_materials += [yk('reimu',-14+4*i,0,name,label=label+'1/2/3',nutrition=60,energy=95,lead=i==0,also=('wound_shapes',)),
        set_status(name,'marks.scars',0),
        *[set_status(name,'health.wound_at',f'{part}:{kind}:{grade}:{shape}')
          for part,grade in (('front',1),('side',2),('back',3))]]
for i,(species,extra,label) in enumerate((('reimu',{'congenital':'BEAN_BODY'},'豆形'),
        ('reimu',{'congenital':'SPARSE_HAIR'},'先天稀发'),('reimu',{'form':'WASA_REIMU'},'多毛后发'),
        ('marisa',{'aquatic':True},'水生与大帽'))):
    name=f'hair{i}'
    _wound_materials += [yk(species,-9+6*i,8,name,label=label,nutrition=60,energy=95,**extra),
        set_status(name,'marks.scars',0),set_status(name,'health.wound_at','top:dent:2:dent'),
        set_status(name,'health.wound_at','back:tear:3:bite')]
_wound_materials += [*[yk('reimu',-8+4*i,-8,f'part{i}',yaw=0,label=label,**pair_ready()) for i,(label,_) in enumerate(_parts)],
    *[set_status(f'part{i}','marks.scars',0) for i in range(5)],
    *[set_status(f'part{i}','health.wound_at',f'{part}:scratch:2:thorn') for i,(_,part) in enumerate(_parts)]]
add('wound_materials','body','伤口外观：八伤型、五部位与发面','伤口外观：八伤型、五部位与发面',
    '中排八种伤型每只正面一级、侧面二级、背面三级；北排五只分别在头顶、正面、背面、侧面、底面有二级细刺伤，比较锚点和动作贴合；南排豆形、先天稀发、多毛及水生大帽对照。绕行看皮面、发面和断发边，移动时看蒙皮；用橙汁闭伤后看同锚点留疤，再用面粉糊。三级裂口继续漏血，及时治疗。本场是外观初态，实际来源另看真实伤因场景',
    '先看后背三级，再看侧面与正面；可清场重开',_wound_materials,env=DAY,vantage=(0,15),
    give=['mcyukkuri:orange_juice 16','mcyukkuri:flour_paste 16'],
    reads=[('读八类伤型与锚点',f'execute as {sel_all("wound_shapes")} run yukkuri status @s raw')]
        + [(f'读{label}伤口',f'yukkuri status {sel(f"part{i}")} raw') for i,(label,_) in enumerate(_parts)]
        + [(f'读{label}',f'yukkuri status {sel(f"hair{i}")} raw') for i,label in enumerate(('豆形','先天稀发','多毛后发','水生大帽'))])
_thermal=[]
for i,(scope,grade) in enumerate((('sole',1),('sole',2),('sole',3),('whole',1),('whole',2),('whole',3))):
    name=f'thermal{i}'
    _thermal += [yk('reimu',-15+6*i,0,name,label=('足部' if scope=='sole' else '全身')+str(grade),nutrition=60,energy=95,lead=i==0),
        set_status(name,'marks.scars',0),set_status(name,'health.wound_at',f'{"bottom" if scope=="sole" else "front"}:scorch:{grade}:{scope}')]
for i,scope in enumerate(('sole','whole')):
    name=f'house_heat{i}'
    _thermal += [yk('house_reimu',-6+6*i,8,name,label='家油'+('足部' if i==0 else '全身'),nutrition=60,energy=95),
        set_status(name,'health.wound_at',f'{"bottom" if scope=="sole" else "front"}:scorch:1:{scope}')]
_thermal += [yk('reimu',6,8,'thermal_control',label='健康对照',nutrition=60,energy=95)]
add('thermal_body','body','烧伤外观：足底与全身1–3级','烧伤外观：足底与全身1–3级',
    '前排足部1/2/3级与全身1/2/3级，后排家油两种范围及健康对照。家油伤级初态为1但外观直接三级；读thermal_state区分真实等级和显示等级。全身仅横向干瘦，保持高度与深度，头发五官一起变化、头饰保尺寸；足烧只足底，不露核。用橙汁观察恢复与原位疤',
    '立即绕行；真实受火另用伤因场景',_thermal,env=DAY,vantage=(0,15),
    give=['mcyukkuri:orange_juice 12','mcyukkuri:flour_paste 12'],
    reads=[(f'读热损{i+1}',f'yukkuri status {sel(f"thermal{i}")} raw') for i in range(6)]
        + [('读家油足烧',f'yukkuri status {sel("house_heat0")} raw'),('读家油全身',f'yukkuri status {sel("house_heat1")} raw')])

_source_cases=(('cut',-12,0,'木剑刀割'),('blunt',-6,0,'空手钝击'),('arrow',0,0,'箭与三叉戟'),
               ('thorn',6,0,'仙人掌细刺'),('cone',12,0,'滴水石尖锥'),('blast',-12,10,'真实爆炸'),
               ('heat',-6,10,'火烧与热地板'),('friction',0,10,'真实滚拖摩擦'))
add('wound_sources','body','用真实武器和环境造成伤','用真实武器和环境造成伤',
    '目标从健康无伤开始，玩家用有限工具真实命中，读实际扣血、wound_sites/anchors的shape与伤级；弓箭和三叉戟用真实投射物，仙人掌/石笋按实际接触，爆炸用TNT。右后玻璃围区有饥饿捕食种和猎物，普通AI自行追咬，观察bite。热地板只足烧，着火整体焦枯；滚拖从空手潜行右键的照料菜单操作。一次MC命中不虚构逐齿逐刺落点',
    '先看右后追咬，再逐类手动操作；可用钉针或铁箱，潜行空手右键选解除；清场恢复地形与实体',[
        *[yk('reimu',dx,dz,name,label=label,nutrition=90,energy=95,lead=i==0) for i,(name,dx,dz,label) in enumerate(_source_cases)],
        box(10,-1,7,20,5,17,'minecraft:glass','hollow'),
        yk('reimu',15,11,'bite_target',label='真实追咬猎物',nutrition=70,energy=95)],
    late=[yk('remilia',15,15,'bite_hunter',label='饥饿捕食种',nutrition=10,energy=95)],
    env=DAY,vantage=(0,17),give=['minecraft:wooden_sword','minecraft:stick','minecraft:bow','minecraft:arrow 16',
        'minecraft:trident','minecraft:sand 4','minecraft:cactus 4','minecraft:pointed_dripstone 4','minecraft:tnt 2','minecraft:redstone_torch 2',
        'minecraft:flint_and_steel','minecraft:magma_block 4','mcyukkuri:orange_juice 12',
        'mcyukkuri:pin_tool','mcyukkuri:iron_enclosure','mcyukkuri:care_awl','mcyukkuri:sandpaper_mat'],
    reads=[(f'读{label}',f'yukkuri status {sel(name)} raw') for name,_,_,label in _source_cases]
        + [('读真实咬伤',f'yukkuri status {sel("bite_target")} raw'),('读捕食交接',f'yukkuri status {sel("bite_hunter")} raw')])

add('wound_leak', 'body', '伤口漏馅，橙汁止漏', '伤口漏馅，橙汁止漏',
    '五只灵梦：1／2／3级裂口、多处三级裂口、血3且三级大裂口露核；每10秒漏血0／0.5／1／1／1。绕行看大伤口普通馅与唯一中枢创腔，橙汁闭伤补血止漏并原位留疤，面粉糊只去疤；露核者真实漏血可致死，及时治疗',
    '先等 10 秒读血量，再用橙汁；随后再等 10 秒确认止漏', [
        *[yk('reimu', -8 + 4 * i, 0, f'v{i}', yaw=180, lead=i == 1, label=label, nutrition=60, energy=95)
          for i, label in enumerate(('轻伤', '二级裂口', '三级大伤口', '多处大伤口', '低血露核'))],
        *[set_status(f'v{i}', 'marks.scars', 0) for i in range(5)],
        *[set_status(f'v{i}', 'health.wound_at', f'front:tear:{min(3, i + 1)}') for i in range(5)],
        set_status('v3', 'health.wound_at', 'back:tear:3'), set_status('v4','health.health',3)],
    give=['mcyukkuri:flour_paste 5', 'mcyukkuri:orange_juice 5'], note=NEW, vantage=(0, 10),
    reads=[('读二级裂口', f'yukkuri status {sel("v1")}'), ('读三级裂口', f'yukkuri status {sel("v2")}'),
           ('读多处裂口', f'yukkuri status {sel("v3")}'), ('读漏馅露核',f'yukkuri status {sel("v4")} raw')])

add('treatment_severe', 'body', '重伤不自愈，橙汁治好留疤', '重伤不自愈，橙汁治好留疤',
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

add('treatment_scars', 'body', '伤口自愈留疤，面粉糊去疤', '伤口自愈留疤，面粉糊去疤',
    '北排五只：前两只都有疤 1、三级凹伤、血 6：左先面粉糊，只去疤不改伤血；右先橙汁，清伤补满血、保留旧疤并新增三级伤原位疤，再交换用药。第三只二级裂伤概率留疤；第四只健康无疤，两药都无效；第五只有油霉2级与轻伤，橙汁可治伤但油霉等级不变。'
    '南排是干净、脏污、疤痕外观对照和一只一级裂伤：衣食充足时自然闭合，再按原伤级概率留疤；面粉糊只去已有疤痕，不治未闭合伤口',
    '先读数再用药；自然愈合约 40 秒。核对扣料请切生存，创造模式不耗物品', [
        yk('reimu', -6, 0, 'paste_first', label='先去疤', lead=True, nutrition=60, energy=100),
        yk('reimu', -2, 0, 'orange_first', label='先治伤', nutrition=60, energy=100),
        *[command for name in ('paste_first', 'orange_first') for command in (
            set_status(name, 'marks.scars', 0), set_status(name, 'marks.scars', 1), set_status(name, 'health.wound_at', 'side:dent:3'),
            set_status(name, 'health.health', 6))],
        yk('reimu', 2, 0, 'healing', label='伤后留疤', **pair_ready()),
        set_status('healing', 'marks.scars', 0), set_status('healing', 'health.wound_at', 'front:tear:2'),
        yk('reimu', 6, 0, 'healthy', label='无伤无疤', **pair_ready()),
        set_status('healthy', 'marks.scars', 0),
        yk('reimu', 10, 0, 'moldy', label='油霉对照', **pair_ready()),
        set_status('moldy', 'health.mold', 2), set_status('moldy', 'health.wound_at', 'side:dent:1'),
        yk('reimu', -6, 6, 'clean', label='干净'), yk('reimu', -2, 6, 'dirty', label='脏污'), yk('reimu', 2, 6, 'scarred', label='疤痕'),
        yk('reimu', 6, 6, 'light_tear', label='一级裂伤', **pair_ready()),
        *[set_status(name, 'marks.scars', 0) for name in ('clean', 'dirty', 'scarred', 'light_tear')],
        set_status('dirty', 'marks.dirt', 3), set_status('scarred', 'marks.scars', 1), set_status('light_tear', 'health.wound_at', 'front:tear:1')],
    env=DAY, give=['mcyukkuri:flour_paste 8', 'mcyukkuri:orange_juice 8'], vantage=(0, 14),
    reads=[(caption, f'yukkuri status {sel(name)} raw') for caption, name in (
        ('读先去疤', 'paste_first'), ('读先治伤', 'orange_first'), ('读伤后留疤', 'healing'), ('读无效对照', 'healthy'), ('读油霉对照', 'moldy'),
        ('读一级伤留疤', 'light_tear'), ('读预置疤痕', 'scarred'))])

add('treatment_care', 'body', '照料员用橙汁、面粉糊治伤', '照料员用橙汁、面粉糊治伤',
    '伤者有三级裂口、血6、旧疤1、无感染。NPC先手持橙汁约1秒、挥手交互，再换面粉糊重复；清伤回血留疤后去疤。每剂先从库存预留到手上，成功消耗，未用完归还，最终余量8→6；可在举药时轻击伤者核对中断不误耗',
    '约 10–40 秒', [
        yk('reimu', 0, 0, 'patient', label='伤者', lead=True, **pair_ready()),
        set_status('patient', 'health.wound_at', 'front:tear:3'),
        set_status('patient', 'health.health', 6), set_status('patient', 'health.infection', 0),
        set_status('patient', 'marks.scars', 0), set_status('patient', 'marks.scars', 1),
        human('caretaker_brother', 2, 0, 'carer', 0, label='照料员')],
    env=DAY, vantage=(0, 10), reads=[('读伤者', f'yukkuri status {sel("patient")} raw'),
                                    ('读医疗余量', f'data get entity {sel("carer")} Medicine')])

add('core_exposed', 'body', '深伤露出中枢，瞄准才打中', '深伤露出中枢，瞄准才打中',
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

add('magma_sole', 'body', '踩岩浆块烫脚，跳着逃开', '踩岩浆块烫脚，跳着逃开',
    '左边位于半径4格岩浆块区中心，右边普通石头对照；不预置伤口。热地板伤害沿用玩家相同的原版判定与受伤冷却；每次实际受伤跳起，高度约为此前高跳的一半，并连续逃向安全地面。健康个体应活着离开；离开后橙汁治伤，核对脚底疤和烤足',
    '先观察数秒；离开过快可清场重开', [
        box(-13, -1, -4, -5, -1, 4, 'minecraft:magma_block'),
        box(3, -1, -2, 7, -1, 2, 'minecraft:stone'),
        yk('reimu', -9, 0, 'hot', label='半径4格岩浆块', lead=True, **pair_ready()),
        yk('reimu', 5, 0, 'cool', label='普通石头', **pair_ready()),
        set_status('hot', 'marks.scars', 0), set_status('cool', 'marks.scars', 0)],
    env=DAY, give=['mcyukkuri:orange_juice 4'], note=NEW, vantage=(0, 10),
    reads=[('读脚底灼伤', f'yukkuri status {sel("hot")} raw'), ('读普通地面对照', f'yukkuri status {sel("cool")} raw')])

add('core_filling', 'body', '遗体取出中枢馅，伤者吃下', '遗体取出中枢馅，伤者吃下',
    '一只有正面深伤的灵梦身体耗尽（核心完好）留下空壳，2 秒后割出独立中枢馅，遗体创腔保留但不再画核心；旁边重伤又饿的魔理沙吃下它：血 +4、营养 +10、不安下降。普通食用量为零仍可取一次核心，搬起放下不能让核心重新出现',
    '约 10–30 秒', [
        yk('reimu', 0, 0, 'dead', **pair_ready()),
        set_status('dead', 'health.wound_at', 'front:dent:3'),
        yk('marisa', 5, 0, 'hurt', lead=True, nutrition=30, energy=95),
        set_status('hurt', 'health.wound_at', 'side:dent:3'), set_status('hurt', 'health.health', 4),
        f'damage {sel("dead")} 100 minecraft:generic'],
    late=[f'yukkuri body core {REMAINS_NEAR}'], note=NEW, vantage=(0, 10), reads=[('读受伤者', f'yukkuri status {sel("hurt")}')])

_rot = [uid('remains_rot', k) for k in ('fresh', 'stale', 'rotten')]
add('remains_rot', 'body', '遗体腐败发臭，旁人走开', '遗体腐败发臭，旁人走开',
    '三具灵梦遗体：新鲜／陈旧（偏平、霉斑）／腐坏（塌陷、冒烟），臭味半径 5／9／14 格；旁边吃饱的油闻到会说“臭”并走开、不安上涨。放着不管约 20 分钟变陈、60 分钟腐坏',
    '2–5 秒后走开', [
        remains(-16, 0, 'fresh', ident=_rot[0]), remains(0, 0, 'stale', ident=_rot[1], rot=24000), remains(16, 0, 'rotten', ident=_rot[2], rot=72000),
        yk('reimu', -14, 0, 'o1', lead=True, **pair_ready()), yk('marisa', 2, 0, 'o2', **pair_ready()), yk('chen', 18, 0, 'o3', **pair_ready())],
    note=NEW, vantage=(0, 14), reads=[('读闻臭者', f'yukkuri status {sel("o2")}')])

# Each victim dies of one real cause: a vanilla damage type through the ordinary hurt path, or the body's own internal cause
# (terminal mould, the end of its lifespan). The pool holds a drowning and two remains with a water clock already running; the dry
# control keeps the same clock on land, where it does not advance. A starved baby is already buried (the same burial as a shovel,
# by someone standing north of it) so its marker can be read at once; the shovel buries the others.
OLD_AGE_FAMILY = f'Version:2,Initialized:1b,Stage:"ADULT",Age:{1056000 - 200}L,Nourishment:{1056000 - 200}L,Maturity:336000L,Lifespan:1056000L,AdultScale:1f,Scale:1f'
_forms = [('old', -20, '寿终'), ('starve', -14, '饿死'), ('fire', -8, '烧死'), ('anvil', -2, '压死'), ('struck', 4, '打死'),
          ('frozen', 10, '冻死'), ('mold', 16, '油霉病死')]
_form_lines = [box(-8, -1, 7, 8, -1, 13, 'minecraft:water')]
for _name, _x, _label in _forms:
    _form_lines.append(yk('reimu', _x, 0, _name, label=_label, lead=_name == 'old', fam=OLD_AGE_FAMILY if _name == 'old' else None, **pair_ready()))
_form_lines += [
    f'damage {sel("starve")} 100 minecraft:starve', f'damage {sel("fire")} 100 minecraft:in_fire',
    f'damage {sel("anvil")} 100 minecraft:falling_anvil', f'damage {sel("struck")} 100 minecraft:mob_attack',
    f'damage {sel("frozen")} 100 minecraft:freeze', set_status('mold', 'health.mold', 8),
    yk('reimu', 0, 10, 'drowned', dy=-1, label='溺死', **pair_ready()), f'damage {sel("drowned")} 100 minecraft:drown',
    remains(-5, 10, 'soaking', dy=-1, soak=5400, cause='minecraft:drown', category='drowned', food=0),
    remains(5, 10, 'melting', dy=-1, soak=23400, cause='minecraft:mob_attack', category='violent'),
    remains(12, -10, 'dry_control', soak=23400, cause='minecraft:mob_attack', category='violent'),
    yk('reimu', -3, -7, 'buried_baby', stage='baby', **pair_ready()), f'damage {sel("buried_baby")} 100 minecraft:starve']
_buried_remains = '@e[type=mcyukkuri:yukkuri_remains,distance=..2,limit=1,sort=nearest]'
_buried_at = 'execute positioned ~-3 ~ ~-7'
_legacy_remains_path = 'Remains.components."minecraft:custom_data".FactoryOwner'
add('remains_forms', 'body', '八种死因遗体，溶化与立碑', '八种死因遗体，溶化与立碑',
    '七只灵梦各死于真实死因：寿命（约10秒）、饥饿、着火、落砧、近战击打、冻伤、终末油霉（约25秒内）；南边水池里一只溺死。'
    '遗体按存下的死因类别换死亡脸、姿态和表层：寿终凹缝眼灰白、饿死干瘪、烧死焦黑带灰、压死压扁带馅渍、打死歪倒垂舌且身下渗馅、冻死挺圆结霜、病死发黄起霉点、溺死白眼泡胀冷白滴水。'
    '水池另有两具已泡水的遗体：左边溺死遗体约30–60秒后泡软（更胀、发白斑）；右边被打死后落水、尚有6份余量，约30–60秒后溶化成沉底残渣，余量6→0、不能取核。'
    '浮在水面时只有实际浸水的 tick 计入水钟，漂起的瞬间不计，所以时间会比 30 秒长。'
    '岸上同样水钟的打死遗体不再前进，仍为泡软、余量6。用“读遗体”核对 category、water(soak)、food 和 core。'
    '观察点前方约 5 格偏右，一只饿死的赤油已安葬：小土冢头端立着小石碑，正面朝北对着你；右键土冢或石碑，像告示牌一样读出“无名之墓”、种类与年龄段、死因和安葬日。'
    '给了一把铁锹：在草地上右键其他遗体即可安葬，石碑正面朝向你，生前有名字（这里是头顶的标签）的写作“某某之墓”。退出重进后读到的仍是同一死者',
    '约 30–60 秒；泡软与溶化也可等真实浸水 5／20 分钟', _form_lines,
    give=['minecraft:iron_shovel'], note=NEW, vantage=(0, 12),
    late=[f'{_buried_at} run data modify entity {_buried_remains} {_legacy_remains_path} set value '
          + uuid_nbt(uid('remains_forms', 'legacy_factory')),
          f'{_buried_at} rotated 0 0 run yukkuri body bury {_buried_remains}'],
    reads=[('读遗体', 'yukkuri body info @e[type=mcyukkuri:yukkuri_remains,distance=..40,sort=nearest]')],
    smoke=dict(ticks=200, expect=[r'\[remains\][^\n]*legacy factory claim cleared',
                                 r'\[remains\][^\n]* buried by \S+ grave=[^\n]* facing=north [^\n]* stage=baby category=starved'],
               forbid=[r'\[body\][^\n]* invariant\b']),
    capture=dict(shots=(1, 30, 60, 100, 200, 400), sequences=()))

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
add('carrion', 'body', '饿极：护尸、食尸或拒食', '饿极：护尸、食尸或拒食',
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
add('cannibal_hunt', 'body', '饿油捕食没头饰的同类', '饿油捕食没头饰的同类',
    '两组都是营养9、看见失饰成年目标；左低友善低自制，右高友善高自制。读猎食判分与真实追咬：数值拒绝不算卡住；失饰识别不等于必攻击，低血和体力不足会退避。只有实际伤害形成食物、吞下才加营养',
    '约10–60秒；饥饿继续加深时意愿也会改变', _hunt_lines, note=NEW, vantage=(0, 12),
    reads=[(f'读{role}·{label}', f'yukkuri status {sel(role + "_" + label)} raw')
           for label in ('willing','reluctant') for role in ('hunter','prey')])



_lw = {k: uid('last_words', k) for k in ('dying', 'mate', 'kid')}
add('last_words', 'body', '濒死灵梦对家人留遗言', '濒死灵梦对家人留遗言',
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
add('offering', 'body', '绝境亲油请孩子吃了自己', '绝境亲油请孩子吃了自己',
    '三组均无食物：左高友善亲油与濒饿低血孩子，另有同亲子油可共享献身遗体；中间只降低亲油友善；右边孩子一般饥饿。连续六次检查绝境孩子才按概率决定，拒绝冷却60秒；投喂或真实照护可中止。自然献身后两子共扣有限余额，吞咽才加营养',
    '约30–35秒首次抽签；未中可重开，孩子可能因真实缺粮死亡', _offering_lines,
    give=['minecraft:apple 4'], note=NEW, vantage=(0, 12),
    reads=[(f'读{role}·{label}', f'yukkuri status {sel(role + "_" + label)} raw')
           for label in ('desperate','reluctant','ordinary') for role in ('parent','child')]
          + [('读同亲子油',f'yukkuri status {sel("other_child")} raw')])

# ---- predators: a parent drawing the chase, Youmu standing her ground
_guard_parent, _guard_child = uid('sacrifice', 'parent'), uid('sacrifice', 'child')
add('sacrifice', 'weather', '亲油引开捕食种保护孩子', '亲油引开捕食种保护孩子',
    '芙兰先选小灵梦，亲油决定帮助后靠近、噗咕挑衅，芙兰转怒追亲油，亲油再逃跑；读protection阶段与真实目标，子油按亲密度和友善度分支说话，保留真实拒绝及危险。转怒后为战斗态：俯冲命中1.5伤、两次命中至少隔2秒、不产生食物；亲油被杀后芙兰原地稳住约5秒；读predator的mode、rest。'
    '低意愿对照：生成后立刻点“亲油友善降到10”，仍有机会引开追击；读 behavior.protection 的 chance、decision，不能以一次仍追亲油判失败',
    '约 30 秒内；概率行为可重新生成对比', [
        yk('reimu', 0, 0, 'parent', uuid=_guard_parent, yaw=-90, lead=True, label='亲油', friendliness=90, boldness=60, attachment=60, **pair_ready()),
        yk('reimu', 3, 1, 'child', stage='child', uuid=_guard_child, yaw=-90, label='子油', nutrition=95, kin=kin_of(Mother=_guard_parent)),
        yk('flandre', 12, 0, 'pred', yaw=90, label='捕食者', nutrition=100, energy=100), set_status('pred', 'needs.nutrition', 10),
        # A child raised by her is close to her (the worry reaction needs affinity >= 40).
        f'yukkuri status relate {sel("child")} {sel("parent")} 80', f'yukkuri status relate {sel("parent")} {sel("child")} 80'],
    env=DAY, spot='pred', vantage=(6, 14), note=NEW,
    reads=[('读亲油反应', f'yukkuri status {sel("parent")} raw'), ('读护子追击', f'yukkuri status {sel("pred")} raw'),
           ('读被护子油', f'yukkuri status {sel("child")} raw'), ('亲油友善降到10（低意愿）', set_status('parent', 'personality.friendliness', 10))])

MYON = 'YukkuriMyon:{SpeechQuirk:"%s",Kanroken:%s,Initialized:1b}'
add('myon_brave', 'weather', '持棍妖梦迎战饥饿芙兰', '持棍妖梦迎战饥饿芙兰',
    '持棍妖梦接触且有视线才打：1.5伤、间隔1.6秒。双方胆量至少35参战，退战比例=0.65−0.0055×胆量；读myon_combat与predator（芙兰受击转战斗态，俯冲命中1.5伤、间隔不少于2秒，拉开时妖梦够不着，战斗不增加其饱食）。旁边灵梦照常逃，可用按钮调整双方胆量后重观；战斗到死亡不应卡死',
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


add('hat_damage', 'headwear', '头饰破了，安心时自己长好', '头饰破了，安心时自己长好',
    '四只灵梦：完好／轻损（毛边小缺口）／撕裂（缺一大块）／撕裂但一直不安。安心时头饰一级级自己长好，每升一级开心说台词；不安的那只不自愈',
    '轻损→完好、撕裂→轻损各约 2.5 分钟（可倍速）', [
        yk('reimu', -5, 0, 'ok', yaw=180, lead=True, label='完好', **pair_ready()),
        yk('reimu', -2, 0, 'frayed', yaw=180, label='轻损', extra=hat(1), **pair_ready()),
        yk('reimu', 1, 0, 'torn', yaw=180, label='撕裂', extra=hat(2, 6000), **pair_ready()),
        yk('reimu', 4, 0, 'stuck', yaw=180, label='不安', extra=hat(2), **pair_ready()),
        set_status('stuck', 'mood.unease', 70)],
    env=DAY, note=NEW, vantage=(0, 8), reads=[('读撕裂头饰', f'yukkuri status {sel("torn")} raw')])

add('hat_shatter', 'headwear', '头饰碎成几块，自己舔回拼好', '头饰碎成几块，自己舔回拼好',
    '2 秒后灵梦的头饰碎成 2–3 块落在身边（哭）：它自己走过去一块块舔起，凑齐后头上是拼起来的碎饰，约 5 分钟长合成撕裂的头饰，再慢慢修好。你也可以捡碎片右键还给它',
    '拾碎片 10–40 秒；拼合约 5 分钟（可倍速）', [
        yk('reimu', 0, 0, 'owner', yaw=180, lead=True, **pair_ready())],
    env=DAY, late=[set_status('owner', 'accessory.state', 'shatter')], note=NEW, vantage=(0, 8),
    reads=[('读头饰', f'yukkuri status {sel("owner")} raw')])

add('hatless_kin', 'headwear', '失饰子油：笨亲油认不出', '失饰子油：笨亲油认不出',
    '两位亲油都凶悍、胆大且低自制，北边智力 40、南边 80。前者认不出失饰子油，鄙视后可能发起欺负和驱赶；后者仍认亲。读 known、last、accessory.sanction 与孩子位置，亲缘不豁免识别',
    '首次鄙视约 10–30 秒；自然升级有概率，可观察 1–3 分钟', [
        yk('reimu', 0, -5, 'mom_blind', yaw=90, label='凶悍亲油·智力40', intelligence=40, friendliness=0, boldness=95, self_control=0, **pair_ready()),
        yk('chen', 3, -5, 'kid_blind', stage='child', nutrition=90, energy=95, yaw=-90, lead=True, accessory=False),
        f'yukkuri scenario kin {sel("mom_blind")} {sel("kid_blind")} false',
        yk('reimu', 0, 5, 'mom_sees', yaw=90, label='凶悍亲油·智力80', intelligence=80, friendliness=0, boldness=95, self_control=0, **pair_ready()),
        yk('chen', 3, 5, 'kid_sees', stage='child', nutrition=90, energy=95, yaw=-90, accessory=False),
        f'yukkuri scenario kin {sel("mom_sees")} {sel("kid_sees")} false'],
    env=DAY, note=NEW, vantage=(-8, 0, -90), reads=[('读低智力亲油', f'yukkuri status {sel("mom_blind")} raw'), ('读高智力亲油', f'yukkuri status {sel("mom_sees")} raw'), ('读被驱赶孩子', f'yukkuri status {sel("kid_blind")} raw')])

add('hatless_scorn', 'headwear', '没头饰：被鄙视、被凶的欺负', '没头饰：被鄙视、被凶的欺负',
    '没戴头饰的灵梦（胆大、健康）：智力普通的旁观者认不出它，生气说台词、走开，它哭、不安上涨；凶悍成体自然鄙视后发起欺负，'
    '它遇到咬、抢、推时按自身状态选择反击或逃跑，欺负者被反击也重新选择，低血量一律逃跑。高智力的橙（90）认得出，不鄙视也不躲。观察双方血量、实际 Goal、位置及反应读数',
    '鄙视约 10–30 秒出现，之后每 30–40 秒重复；欺负自然升级有概率，约 1–3 分钟', [
        yk('reimu', 0, 0, 'bare', yaw=-90, lead=True, accessory=False, label='胆大失饰者', friendliness=15, boldness=95, self_control=15, **pair_ready()),
        yk('marisa', 4, 0, 'a', yaw=90, **pair_ready()), yk('reimu', 4, 4, 'b', yaw=90, **pair_ready()),
        yk('marisa', 3, -3, 'b1', uuid=uid('hatless_scorn', 'b1'), yaw=90, label='凶悍欺负者', friendliness=0, boldness=80, self_control=0, **pair_ready()),
        yk('chen', -4, 3, 'c', yaw=180, label='高智力', **pair_ready()), set_status('c', 'personality.intelligence', 90)],
    env=DAY, note=NEW, vantage=(0, 10),
    reads=[('读失饰者（含欺凌伤口）', f'yukkuri status {sel("bare")} raw'), ('读欺负者', f'yukkuri status {sel("b1")} raw')])

add('hatless_monster', 'headwear', '失饰重伤才像怪物，普通伤不算', '失饰重伤才像怪物，普通伤不算',
    '左北失饰灵梦有三处面部二级凹伤，左南只有一处背部三级严重凹伤（凹伤不漏馅，主角撑得过观察时间），两者均符合怪物阈值。读 face_wounds、severe_wounds、grotesque 与观察者 contacts。'
    '右边远处的失饰灵梦前后各一处二级伤且低血量：面部不足三伤，也没有三级严重伤，仍是普通鄙视，读 face_wounds=1、severe_wounds=0、grotesque=false；低血量本身不构成怪物',
    '约 10–60 秒；每个观察者每 30–40 秒重复', [
        yk('reimu', 0, -5, 'victim', yaw=-90, lead=True, accessory=False, label='面部三伤', **pair_ready()),
        set_status('victim', 'health.wound_at', 'front:dent:2'),
        set_status('victim', 'health.wound_at', 'front:dent:2'),
        set_status('victim', 'health.wound_at', 'front:dent:2'),
        yk('marisa', 3, -5, 'timid', yaw=90, intelligence=40, **pair_ready()),
        yk('reimu', 0, 5, 'severe', yaw=-90, accessory=False, label='背部严重伤', **pair_ready()),
        set_status('severe', 'health.wound_at', 'back:dent:3'),
        yk('marisa', 3, 5, 'severe_observer', yaw=90, intelligence=40, **pair_ready()),
        yk('reimu', 20, 0, 'single', yaw=-90, accessory=False, label='两处普通伤', **pair_ready()),
        set_status('single', 'health.wound_at', 'front:dent:2'),
        set_status('single', 'health.wound_at', 'back:dent:2'), set_status('single', 'health.health', 3),
        yk('chen', 23, 0, 'observer', yaw=90, intelligence=40, **pair_ready())],
    env=DAY, note=NEW, vantage=(10, 16, 180),
    reads=[('读面部三伤', f'yukkuri status {sel("victim")} raw'), ('读单处严重伤', f'yukkuri status {sel("severe")} raw'),
           ('读左北反应', f'yukkuri status {sel("timid")} raw'), ('读左南反应', f'yukkuri status {sel("severe_observer")} raw'),
           ('读两处普通伤', f'yukkuri status {sel("single")} raw'), ('读普通鄙视', f'yukkuri status {sel("observer")} raw')])

_puff_pairs = []
for _i, (_x, _z, _label, _stage, _friend, _bold) in enumerate((
        (-14, -10, '胆小子油', 'child', 50, 5), (14, -10, '友善成体', 'adult', 95, 80),
        (-14, 10, '普通成体', 'adult', 50, 50), (14, 10, '凶悍大胆成体', 'adult', 0, 95))):
    _puff_pairs += [
        yk('reimu', _x, _z, f'puff_target{_i}', label=_label, stage=_stage, accessory=False,
           nutrition=90, energy=95, friendliness=_friend, boldness=_bold, self_control=0 if _i==3 else 60, lead=_i==0),
        yk('marisa', _x+3, _z, f'puff_source{_i}', label='凶悍失饰制裁者', yaw=90,
           intelligence=30, friendliness=0, boldness=95, self_control=0, **pair_ready())]
add('puff_response', 'headwear', '被噗咕：逃、无视、回嘴或反击', '被噗咕：逃、无视、回嘴或反击',
    '四组真实失饰初态，由凶悍者自行识别并发起制裁宣告。对照小体型胆小、友善、普通与凶悍大胆目标的逃跑／无视／噗咕回应／激怒攻击；自然升级有概率，不直接指定行为。日志记录当前自尊、防备、强硬程度、战力和选择',
    '约1–3分钟；读实际制裁与反应，不将未发生宣告算作无视', _puff_pairs,
    env=DAY, vantage=(0, 22), reads=[(f'读回应{i+1}',f'yukkuri status {sel(f"puff_target{i}")} raw') for i in range(4)])

# ---- species traits and rare forms
add('species_fatigue', 'species', '妖梦、灵梦、帕琪体力消耗对照', '妖梦、灵梦、帕琪体力消耗对照',
    '妖梦、灵梦和帕秋莉均成年、营养100、精力95，各有两份真实苹果并用玻璃隔开。按 species.fatigue 的 steps 对照代谢 cost：同为20次时应为18／20／25；睡眠会暂停清醒代谢，其他活动额外耗能不计入cost，不要求同一秒达到同次数',
    '默认间隔下约3分钟，精力耗尽前读20次；可用已有推进按钮', [
        box(-3, 0, -4, -3, 2, 4, 'minecraft:glass'), box(3, 0, -4, 3, 2, 4, 'minecraft:glass'),
        *[command for species,dx,name,label in (('myon',-7,'myon','妖梦·0.9'),('reimu',0,'neutral','灵梦·1'),('patchouli',7,'frail','帕秋莉·1.25'))
          for command in (yk(species,dx,0,name,label=label,nutrition=100,energy=95,lead=name=='myon'),item('minecraft:apple',2,dx,2))]],
    env=DAY, spot='race', vantage=(0, 12),
    reads=[(label,f'yukkuri status {sel(name)} raw') for name,label in
           (('myon','读妖梦代谢'),('neutral','读中性代谢'),('frail','读体弱代谢'))])

add('myon_stick', 'species', '妖梦捡木棍或折树枝当剑', '妖梦捡木棍或折树枝当剑',
    '两只还没有楼观剑的妖梦：左边脚下有根木棍，马上捡起来当楼观剑（开心台词）；右边只有一棵树，过一阵从树上折一根（平均约 20 秒）。两只口癖不同',
    '立即／约 20 秒', [
        yk('myon', -4, 0, 'm1', yaw=180, lead=True, extra=MYON % ('miyo', '0b'), **pair_ready()), item('minecraft:stick', 1, -4, 0, name='stick'),
        yk('myon', 4, 0, 'm2', yaw=180, extra=MYON % ('kinbao', '0b'), **pair_ready()),
        box(6, 0, 1, 6, 3, 1, 'minecraft:oak_log'), box(5, 3, 0, 7, 4, 2, 'minecraft:oak_leaves[persistent=true]', 'keep')],
    env=DAY, spot='race', note=NEW, vantage=(0, 10), reads=[('读折枝妖梦', f'yukkuri status {sel("m2")} raw')])

add('rare_forms', 'species', '稀有形态：长尾橙、红眼妖梦等', '稀有形态：长尾橙、红眼妖梦等',
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
    season = '夏' if roofed else '冬'   # the summer kotatsu sleeps under its shed
    add(sid, 'species', title, hint, watch, '约 1–3 分钟', lines, env=DAY, spot='kotatsu', note=NEW, vantage=(0, 12),
        reads=[(f'读暖桌（{season}）', f'yukkuri status {sel("kotatsu")} raw'), (f'读对照（{season}）', f'yukkuri status {sel("plain")} raw')])


kotatsu('kotatsu_winter', '暖桌魔理沙在雪原不怕冷', '暖桌魔理沙在雪原不怕冷', 'minecraft:snowy_plains',
        '雪原：普通魔理沙觉得冷（needs.temperature=cold），暖桌魔理沙不冷、也不进洞冬居（identity.kotatsu=winter_active），时不时说“暖和”')
kotatsu('kotatsu_summer', '暖桌魔理沙在沙漠躲热', '暖桌魔理沙在沙漠躲热', 'minecraft:desert',
        '沙漠：暖桌魔理沙嫌热，白天也回棚下睡（先避暑）；普通魔理沙照常活动，时不时说“热”', roofed=True)

# A clear cold (autumn taiga) noon on the kotatsu plot with no heat source: the chilled one settles in its own sunny spot.
add('sunbathe', 'species', '寒冷晴天怕冷的魔理沙晒太阳', '寒冷晴天怕冷的魔理沙晒太阳',
    '寒冷的针叶林（秋）晴天正午、附近没有火和暖桌：普通魔理沙觉得冷（needs.temperature=cold），在阳光下停下、压扁闭眼、脸朝上晒太阳（behavior=sunbathe，日志 refuge route selected … sun=true），'
    '一轮晒完暖和了圆回来，约一分钟内不再找太阳；树荫下不算阳光，阴天、下雨和夜里也不晒；冬天雪原太冷，晒太阳不够，只能找真正的热源',
    '约 10–30 秒', [
        'fillbiome ~-12 56 ~-12 ~12 100 ~12 minecraft:taiga',
        box(-4, 0, -3, -4, 3, -3, 'minecraft:spruce_log'), box(-6, 4, -5, -2, 5, -1, 'minecraft:spruce_leaves[persistent=true]', 'keep'),
        yk('marisa', 0, 1, 'basker', yaw=180, lead=True, **pair_ready())],
    env=DAY, spot='kotatsu', note=NEW, vantage=(0, 8),
    reads=[('读晒太阳', f'yukkuri status {sel("basker")} raw')],
    capture=dict(shots=(1, 40, 200, 400, 600), sequences=((40, 600, 6),), follow='@e[tag=scn_basker,limit=1]', offset=(0, .9, 2.8)),
    smoke=dict(ticks=900, expect=[r'\[temperature\][^\n]* refuge route selected warming=true [^\n]*sun=true',
                                  r'\[temperature\][^\n]* refuge stopped warming=true sun=true [^\n]*restTicks=(?:1\d\d|[2-9]\d\d)'],
               forbid=[r'\[body\][^\n]* invariant\b']))

add('patchouli_vomit', 'species', '饿帕琪狼吞后呕吐', '饿帕琪狼吞后呕吐',
    '四只饿坏的帕琪（营养 10）面前各有苹果：饿极狼吞或两餐挨太近时，约四成概率把馅吐出来（苦脸、馅 -1、不安 +3）；同样饿的灵梦照常吃',
    '约 10–60 秒', [
        *[yk('patchouli', -4 + 2 * i, 0, f'p{i}', yaw=180, lead=i == 0, nutrition=10, energy=95) for i in range(4)],
        *[item('minecraft:apple', 3, -4 + 2 * i, 2, name=f'apple{i}') for i in range(4)],
        yk('reimu', 6, 0, 'reimu', yaw=180, nutrition=10, energy=95), item('minecraft:apple', 3, 6, 2, name='apple_r')],
    env=DAY, spot='patchouli', note=NEW, vantage=(0, 10), reads=[('读帕琪', f'yukkuri status {sel("p0")} raw')])

add('chen_speed', 'species', '橙和灵梦赛跑抢苹果', '橙和灵梦赛跑抢苹果',
    '同样饿的橙和灵梦离一个苹果一样远（约 7 格）：橙移动速度是 1.2 倍，先到；读数 species.move_speed 橙 0.216、灵梦 0.18',
    '约 10 秒，可再按一次', [
        yk('chen', -3, -3, 'chen', yaw=180, lead=True, nutrition=30, energy=95),
        yk('reimu', 3, -3, 'reimu', yaw=180, nutrition=30, energy=95),
        item('minecraft:apple', 1, 0, 3)],
    env=DAY, spot='race', note=NEW, vantage=(0, 10), reads=[('读橙速度', f'yukkuri status {sel("chen")} raw')])

add('marisa_sumo', 'species', '魔理沙相扑，总赢帕琪和橙', '魔理沙相扑，总赢帕琪和橙',
    '三对要好的成体。生成时立即按普通相扑规则确定首局，之后的靠近、推挤、胜负与冷却照常发生；魔理沙×帕琪、魔理沙×橙交手时，总是魔理沙把对方推出去（力气 1.1 对 0.8／0.75）',
    '首局立即；之后约 10–60 秒', [
        yk('marisa', -8, 0, 'm1', yaw=-90, lead=True, **pair_ready()), yk('marisa', -6, 0, 'm2', yaw=90, **pair_ready()),
        yk('marisa', -1, 0, 'm3', yaw=-90, **pair_ready()), yk('patchouli', 1, 0, 'pa', yaw=90, **pair_ready()),
        yk('marisa', 6, 0, 'm4', yaw=-90, **pair_ready()), yk('chen', 8, 0, 'ch', yaw=90, **pair_ready()),
        *[f'yukkuri scenario bond {sel(a)} {sel(b)}' for a, b in (('m1', 'm2'), ('m3', 'pa'), ('m4', 'ch'))],
        *[f'yukkuri scenario sumo {sel(a)} {sel(b)}' for a, b in (('m1', 'm2'), ('m3', 'pa'), ('m4', 'ch'))]],
    env=DAY, spot='sumo', note=NEW, vantage=(0, 10))

# Alice's housekeeping needs the forage field's burrow
_ac = {k: uid('alice_chores', k) for k in ('alice', 'reimu')}
add('alice_chores', 'forage', '爱丽丝和灵梦一起做家务', '爱丽丝和灵梦一起做家务',
    '同一个空洞里住着一对伴侣爱丽丝和灵梦（没有孩子，不外出）：两只都会割草、铺窝、捡垫料、清理污物；爱丽丝家务快 1.5 倍，读数 species.chores 涨得更快',
    '约 3 分钟对比', [
        yk('alice', 0, 3, 'alice', uuid=_ac['alice'], yaw=180, lead=True, kin=kin_of(Partner=_ac['reimu']), nutrition=90, energy=95),
        yk('reimu', 2, 3, 'reimu', uuid=_ac['reimu'], yaw=180, kin=kin_of(Partner=_ac['alice']), nutrition=90, energy=95)],
    late=[f'yukkuri scenario home {sel(n)} ~ ~ ~-1' for n in ('alice', 'reimu')],
    env=DAY, spot='forage', vantage=(0, 16), note=NEW,
    reads=[('读爱丽丝家务', f'yukkuri status {sel("alice")} raw'), ('读灵梦家务', f'yukkuri status {sel("reimu")} raw')])

# ---- home declaration: one real opening, with an open-door control for the same caller.
def home_pane_result(window):
    protected = window.find('HOME_PANE_GRIEF_OFF_CHECK')
    if protected < 0 or 'start id=bash' in window[:protected]:
        return 'mobGriefing=false window missing or allowed a bash'
    if 'HOME_PANE_FULL_GLASS_INTACT' not in window:
        return 'full glass did not survive'
    hits = re.findall(r'\[action\][^\n]* hit id=bash block=([^\n]*?) hits=(\d+)', window)
    broken = re.findall(r'\[action\][^\n]* break id=bash block=([^\n]*?) hits=(\d+)', window)
    if len(broken) != 1:
        return f'expected one broken pane, observed {len(broken)}'
    if not hits or max(int(n) for _, n in hits) != int(broken[0][1]):
        return 'break was not preceded by its real strokes'
    if any(block != broken[0][0] for block, _ in hits):
        return 'strokes and break name different panes'
    if window.find('GATHER -> HOLD') < window.find('break id=bash'):
        return 'claim declared before opening its route'
    if re.search(r'\[action\][^\n]* start id=bash', window[window.find('break id=bash') + 1:]):
        return 'a second bash started after the one opening was enough'


# The glass door is one pane column set in iron bars of the same face: an adult's brim, side hair and hat are wider and
# taller than one block, so at impact they meet that common face instead of entering stone jambs and a lintel.
for _sid, _window, _title in (
        ('home_pane', ('fill ~-2 ~ ~3 ~1 ~1 ~3 minecraft:iron_bars', 'fill ~ ~ ~3 ~ ~1 ~3 minecraft:glass_pane'),
         '成体撞碎玻璃板，入屋宣言'),
        ('home_open', ('setblock ~ ~ ~3 minecraft:air',), '野生一家进入敞口空屋宣言')):
    _bold, _timid = uid(_sid, 'bold'), uid(_sid, 'timid')
    add(_sid, 'forage', _title, _title,
        '空石屋南面只有一处落地入口（撞玻璃场景为铁栏杆窗中间一列两块玻璃板，敞口场景为一格空门）；'
        '野生成年魔理沙胆量100、成年女儿灵梦胆量0。家园事件自己选择入口：'
        '魔理沙走近玻璃板，转正，逐次用身体撞击，出现裂纹、碎屑、声音与轻微反冲；玻璃板碎后进入才宣言，洞口保持开放。'
        '亲属安抚会暂停撞击，安抚后保留已有裂纹继续。整块玻璃作旁边的封闭窗，始终保留。胆小女儿可以从已经打开的入口跟进。',
        '约 10–60 秒', [
            'fill ~-6 ~ ~-6 ~6 ~5 ~10 minecraft:air',
            'fill ~-3 ~-1 ~-3 ~3 ~2 ~3 minecraft:stone_bricks hollow',
            'setblock ~ ~ ~ minecraft:crafting_table',
            *_window,
            'setblock ~2 ~ ~3 minecraft:glass',
            yk('marisa',0,6,'bold',uuid=_bold,yaw=180,lead=True,boldness=100,**pair_ready()),
            yk('reimu',2,7,'timid',uuid=_timid,yaw=180,boldness=0,kin=kin_of(Mother=_bold),**pair_ready())],
        late=['yukkuri homeclaim start'], env=DAY, spot='graze', vantage=(0,11,180),
        reads=[('读家园阶段','yukkuri homeclaim status'),('读两只状态','yukkuri status @e[type=#mcylab:yukkuri,distance=..24] raw')],
        smoke=dict(ticks=1200, expect=[r'\[home-claim\][^\n]* GATHER -> HOLD'] +
                   ([r'\[action\][^\n]* start id=bash',r'\[action\][^\n]* hit id=bash',
                     r'\[home\][^\n]* pane opened block=.* hits='] if _sid=='home_pane' else []),
                   forbid=[r'\[body\][^\n]* invariant',r'family_collapsed',r'never_arrived'],
                   **({'verify':home_pane_result,
                       'commands':[(1,'gamerule mobGriefing false'),
                                   (10,'yukkuri homeclaim start'),
                                   (20,'execute if block ~ ~ ~3 minecraft:glass_pane run say HOME_PANE_GRIEF_OFF_CHECK'),
                                   (21,'gamerule mobGriefing true'),
                                   (1190,'execute if block ~2 ~ ~3 minecraft:glass run say HOME_PANE_FULL_GLASS_INTACT')]}
                      if _sid=='home_pane' else {})),
        # Fixed world views: the outside profile runs along the glass in front of the bars, clear of the stone;
        # the inside three-quarter view keeps crack stages in front of the actor; the far inside corner sees the window and
        # the declaration spot together. Never follows its yaw.
        capture=(dict(shots=(40,120,200,400,800,1200), sequences=((40,1200,8),), observe=False,
                      views={'side':dict(position=(-1.9,.6,3.8),look_at=(.5,.6,3.8),
                                         criteria='侧面约两格，检查魔理沙身体与帽檐不穿玻璃、蓄力、撞击接触和反冲。'),
                             'crack':dict(position=(-1.0,.85,1.7),look_at=(.5,.65,3.5625),
                                          criteria='看清撞击后裂纹增长，安慰期间裂纹保留，碎开一块玻璃板、真实进入空屋。'),
                             'home':dict(position=(2.6,1.3,-1.9),look_at=(.5,.4,.6),
                                         criteria='屋内远角同时看到窗和宣言处：破窗后魔理沙真实进屋，在工作台旁宣言。')}) if _sid=='home_pane'
                 else dict(shots=(40,80,120,200,400), sequences=((40,200,4),))))

# ---- colony rules: a village that is sure to have a leader (village_led), on the colony field's west plot
COLONY = BY_ID['colony']
COLONY_LEAD = '@e[tag=scn_lead,distance=..70]'

_mold_colony_x, _mold_colony_z = COLONY.spots['colony']
_mold_outer_den, _ = den(_mold_colony_x, _mold_colony_z + 28)
add('colony_mold_exile', 'colony', '聚落驱逐油霉成员', '聚落驱逐油霉成员',
    '看附近成员发现油霉后鼓脸施压，患病成员退到聚落日常活动范围外，回头央求时再次受拒。',
    '约 1–2 分钟', [
        *_mold_outer_den,
        box(-8, 0, 8, 8, 0, 25, 'minecraft:short_grass', 'keep'),
        yk('patchouli', -2, 0, 'leader', label='族长', also=('mold_colony_member',), **pair_ready()),
        yk('chen', 0, 1, 'witness', yaw=0, label='近处成员', also=('mold_colony_member',),
           friendliness=24, boldness=32, **pair_ready()),
        yk('marisa', 0, 2, 'molded', lead=True, label='患油霉的成员', also=('mold_colony_member',), **pair_ready()),
        f'yukkuri scenario community {sel("leader")} {sel_all("mold_colony_member")}',
        *[f'yukkuri scenario bond {sel(n)} {sel("molded")}' for n in ('leader', 'witness')],
        set_status('molded', 'health.mold', 5)],
    give=['mcyukkuri:mold_remover'], env=DAY, spot='colony', vantage=(0, 28),
    note='等成员回头被拒后，可用去霉药，再看认识它的成员是否友好接纳。',
    reads=[('读成员名册', 'yukkuri settlement info'),
           ('读患病成员与住所', f'yukkuri status {sel("molded")} raw')])


def colony_loss_prompt(sid, members_read, rules_read):
    """A player-chosen, real death after the colony has reached the phase being observed."""
    x, z = COLONY.spots['colony']
    kill = f'/execute positioned {x}.0 {FEET} {z}.0 run kill @e[tag=scn_lead,distance=..140,limit=1,sort=nearest]'
    controls = [
        {'text': '要核验族长死亡后的交接，请先观察当前组织或行军阶段，再点 ', 'color': 'gray'},
        {'text': '[杀死族长]', 'color': 'red', 'bold': True,
         'clickEvent': {'action': 'run_command', 'value': kill}},
        {'text': '；随后点 ', 'color': 'gray'},
        {'text': '[读全体身份与实际Goal]', 'color': 'aqua',
         'clickEvent': {'action': 'run_command', 'value': f'/function mcylab:read/{sid}_{members_read}'}},
        {'text': ' 和 ', 'color': 'gray'},
        {'text': '[读族规、公仓与继任]', 'color': 'gold',
         'clickEvent': {'action': 'run_command', 'value': f'/function mcylab:read/{sid}_{rules_read}'}}
    ]
    if sid == 'colony_ration':
        controls += [{'text': '。若当天已盘点，族长死亡后点 ', 'color': 'gray'},
                     {'text': '[下一天同一时刻]', 'color': 'yellow',
                      'clickEvent': {'action': 'run_command', 'value': '/time add 24000'}}]
    return 'tellraw @s ' + json.dumps(controls, ensure_ascii=False, separators=(',', ':'))


def colony(sid, title, hint, watch, wait, late, env=DAY, lines=(), note='', reads=(), leader_loss=False, village='village_led'):
    reads = list(reads)
    controls = []
    if leader_loss:
        indexes = []
        for caption, command in [('读全体成员身份与实际Goal', 'yukkuri status @e[type=#mcylab:yukkuri,distance=..110] raw'),
                                 ('读族规、公仓与继任', 'yukkuri settlement rules')]:
            index = next((i for i, (_, existing) in enumerate(reads) if existing == command), None)
            if index is None:
                index = len(reads)
                reads.append((caption, command))
            indexes.append(index)
        controls.append(colony_loss_prompt(sid, *indexes))
    population = [f'yukkuri settlement spawn {village} ~ ~ ~',
                  'tag @e[type=mcyukkuri:patchouli,distance=..40,limit=1,sort=nearest] add scn_lead']
    add(sid, 'colony', title, hint, watch, wait,
        [*lines, *population, *controls],
        env=env, spot='colony', vantage=(0, 26), late=late, note=NEW + note, reads=reads, retry='先“清空本场地”再按一次')


def _colony_meadow():
    """A real hunting ground: the leader picks a meadow 32-64 blocks out (CommonHunt.RADIUS), so the ring of the colony plot is
    carpeted with grass wherever the field reaches (only air cells; every clear restores the ground)."""
    px, pz = COLONY.spots['colony']
    x1, x2, z1, z2 = (max(-64, COLONY.x1 + 1 - px), min(64, COLONY.x2 - 1 - px), max(-64, COLONY.z1 + 1 - pz), min(64, COLONY.z2 - 1 - pz))
    rects = [(x1, z1, x2, min(z2, -32)), (x1, max(z1, 32), x2, z2), (x1, max(z1, -31), min(x2, -32), min(z2, 31)), (max(x1, 32), max(z1, -31), x2, min(z2, 31))]
    return [box(a, 0, b, c, 0, d, 'minecraft:short_grass', 'keep') for a, b, c, d in rects if a <= c and b <= d]


colony('colony_hunt', '族长召集狩猎，猎物上缴公仓', '族长召集狩猎，猎物上缴公仓',
       '有族长（帕琪）的村落：2 秒后族长召集公共狩猎，集合→出发→每只割 6 株草→返回；收获一部分上缴公仓（狩猎 1/3、家庭觅食 1/5）',
       '约 2–3 分钟', [Retry('yukkuri settlement rules hunt', COLONY_LEAD)],
       lines=_colony_meadow(),
       reads=[('读族规与公仓', 'yukkuri settlement rules')], leader_loss=True)
colony('colony_ration', '黄昏族长给缺粮户分粮', '黄昏族长给缺粮户分粮',
       '黄昏时族长清点公仓：只给自家粮堆不够一天的“短缺户”分粮，其余不分；读数里看 SHORT 户和分配记录',
       '约 1.5 分钟后（家家吃掉一些存粮，才有短缺户）', [Retry('yukkuri settlement rules ration', COLONY_LEAD, first=1800)], env={'time': 'dusk'}, leader_loss=True)
# The empty village 64 blocks east stands on the colony_dest plot.
_migration_young = '@e[type=#mcylab:yukkuri,distance=..40,sort=nearest,limit=4,nbt={YukkuriFamily:{Stage:"BABY"}}]'
_migration_babies = '@e[type=#mcylab:yukkuri,tag=scn_migrant_baby,distance=..110]'
colony('colony_migrate', '族群迁徙：全族搬去东边空村', '族群迁徙：全族搬去东边空村',
       '族长决定迁徙：讨论 30 秒→准备 30 秒→先锋（有魔理沙时由魔理沙担任，体弱的帕琪不领队）先行，其余稍后跟上。看几户亲油如何把四只赤油带到东边 64 格外的空聚落，落地后如何认领新窝。',
       '约 2–5 分钟', [f'tag {_migration_young} add scn_migrant_baby',
                       Retry('yukkuri settlement migrate', COLONY_LEAD)],
       lines=['yukkuri settlement site vacant village ~64 ~ ~'],
       note='赤油与亲油同住村里的野生住所。观察先锋是否先到、掉队时是否集合换路，以及亲油在哪儿放下赤油。全体到达地面后才更改聚落中心。',
       reads=[('读迁徙与压力（含先锋）', 'yukkuri settlement rules'),
              ('读族长新住所', 'yukkuri status @e[tag=scn_lead,limit=1,sort=nearest] raw'),
              ('读赤油随迁', f'yukkuri status {_migration_babies} raw'),
              ('读全体成员安置', 'yukkuri status @e[type=#mcylab:yukkuri,distance=..110] raw'),
              ('读新聚落状态', 'execute positioned ~64 ~ ~ run yukkuri settlement site info')],
       leader_loss=True, village='village_led_babies')

def _carry_outcome_verify(log):
    # A physical refusal may recover after replanning; count each finished Release once, by its child.
    refusals, carried = {}, {}
    for tick, event, child in re.findall(r'\[family\][^\n]*? t=(\d+)[^\n]* (carry (?:pickup hold=|put down reason=|put down refused )[^\n]*?)child=([0-9a-f-]{36})', log):
        if event.startswith('carry put down refused '):
            refusals[child] = refusals.get(child, 0) + 1
            if refusals[child] > 2:
                return f'child {child} exceeded 2 release refusals'
        elif event.startswith('carry pickup '):
            carried[child] = int(tick)
        else:
            carried.pop(child, None)
    if carried:
        # A later family retrieval or nest-rim recovery can still be under way; measure this child's current hold, not the carrier's first pickup.
        end_tick = int(re.search(r'MCYSMOKE_END_TICK (\d+)', log)[1])
        stuck = {child: end_tick - tick for child, tick in carried.items() if end_tick - tick > 600}
        if stuck:
            return f'children held more than 600 ticks at end: {stuck}'
    return None


# Three real households with their nest, the babies ~8 blocks out: the RETRIEVE carry (ParentCarry.purposeFor), no march needed.
_carry_families = [('marisa', -10, 4, 1.2), ('reimu', 0, 3, 1.0), ('chen', 10, 2, 1.0)]
_carry_lines = []
for _kind, _fx, _n, _size in _carry_families:
    _par = uid('carry_home', _kind)
    _fam = f'Version:2,Initialized:1b,Stage:"ADULT",Age:672000L,Nourishment:672000L,Maturity:336000L,Lifespan:1056000L,AdultScale:{_size}f,Scale:{_size}f'
    _carry_lines += [*shed(_fx, 0), *large_nest(_fx, 0, 'mcyukkuri:large_yukkuri_nest'),
                     yk_on_nest(_kind, _fx, 0, f'{_kind}_parent', large=True, fam=_fam, yaw=0, uuid=_par, lead=_kind == 'reimu', nutrition=95),
                     home(f'{_kind}_parent', _fx, 0)]
    for _i in range(_n):
        _carry_lines += [yk(_kind, _fx - (_n - 1) / 2 * 1.2 + _i * 1.2, 8, f'{_kind}_baby', stage='baby', uuid=uid('carry_home', f'{_kind}_{_i}'),
                            yaw=180, nutrition=95, kin=f'Mother:{uuid_nbt(_par)}'),
                         home(f'{_kind}_baby', _fx, 0)]

def _carry_verify(log):
    if not re.search(r'\[family\] marisa[^\n]* brim hop child=[0-9a-f-]+ hops=[1-9]\d*\b', log):
        return 'Marisa never completed the brim hop move'
    for walk in re.finditer(r'\[family\] marisa[^\n]* brim hop walk child=([0-9a-f-]{36})\b', log):
        if not re.search(rf'carry pickup hold=brim [^\n]*child={walk[1]}\b', log[walk.end():]):
            return f'brim walk alternative never completed for {walk[1]}'
    # Detect doorway ping-pong, not a later real retrieval with another entry/exit.
    stalled = _bed_walk_verify(log, crossing_window=300) or _carry_outcome_verify(log)
    if stalled:
        return stalled
    grips = {}
    for child, seat in re.findall(r'carry grip child=\d+ uuid=([0-9a-f-]{36})\b[^\n]*brimLocal=\(([^)]+)\)', log):
        grips.setdefault(child, set()).add(seat)
    checked = 0
    for match in re.finditer(r'carry lowering hold=brim child=([0-9a-f-]{36})\b[^\n]*brimLocal=\(([^)]+)\)', log):
        child, seat = match.groups()
        if child not in grips:
            continue
        checked += 1
        if seat not in grips[child]:
            return f'brim-local seat changed for {child}: {grips[child]} -> {seat}'
        if not re.search(rf'carry put down [^\n]*hold=brim cause=release [^\n]*child={child}\b', log[match.end():]):
            return f'brim child {child} was not released after lowering'
    if not checked and re.search(r'carry pickup hold=brim\b', log):
        return 'expected a brim grip/release pair with a constant brim-local seat'
    return _carry_chains_verify(log) or _reimu_release_verify(log)


def _carry_chains_verify(log):
    # Distinct initial children carried home; autonomous STAY_HOME returns are checked by END.
    carried = set()
    for kind, _, count, _ in _carry_families:
        for i in range(count):
            child = _uuid_text('carry_home', f'{kind}_{i}')
            pickup = re.search(rf'carry pickup hold=\S+ [^\n]*child={child}\b', log)
            if re.search(rf'carry put down [^\n]*child={child}\b[^\n]*nest=OUTSIDE\b', log):
                return f'initial child {child} released OUTSIDE'
            if pickup:
                if not re.search(rf'carry put down reason=\S+ [^\n]*cause=release [^\n]*child={child}\b[^\n]*nest=(?:INSIDE_SUPPORTED|PARTIAL)\b', log[pickup.end():]):
                    return f'initial carried child {child} has no supported nest release'
                carried.add(child)
    return f'fewer than two distinct initial children carried home: {len(carried)}' if len(carried) < 2 else None


def _reimu_release_verify(log):
    # Reimu lets each baby out by her whole mouth_release clip (1.6 s = 32 ticks), not in the first ticks.
    if not re.search(r'\[family\] reimu #[^\n]* carry pickup hold=mouth\b', log):
        return None
    opened, quick, completed = {}, [], 0
    events = re.finditer(
        r'\[family\] reimu #\d+ uuid=(\S+) t=(\d+) [^\n]*?carry '
        r'(lowering hold=mouth|put down reason=\w+ hold=mouth cause=release purpose=\w+) '
        r'child=([0-9a-f-]{36})\b', log)
    for event in events:
        parent, tick, action, child = event.groups()
        tick = int(tick)
        key = parent, child
        if action.startswith('lowering'):
            # A resumed lowering begins its own clip; a later release belongs to this transaction.
            opened[key] = tick
        else:
            if key not in opened:
                return f'Reimu mouth release without its own lowering: parent={parent} child={child} tick={tick}'
            start = opened.pop(key)
            completed += 1
            if tick - start < 32:
                quick.append((child, start, tick, tick - start))
    if not completed:
        return 'expected a Reimu mouth release (carry lowering hold=mouth, then carry put down hold=mouth)'
    return f'Reimu released before her clip ended (ticks): {quick}' if quick else None


add('carry_home', 'family', '亲油把走远的赤油带回窝', '亲油把走远的赤油带回窝',
    '三家各有自己的大窝，赤油在约 8 格外：魔理沙（1.2 倍，4 只）、灵梦（3 只）、橙（2 只）各自走去，先把身边够得着、承托还放得下的孩子一只只都抬起，再一起带回窝内；魔理沙用辫子托上帽檐，灵梦口含，橙两尾各托一只，到窝后一只一只放下（灵梦张嘴放出约 1.6 秒）',
    '约 30 秒内开始抬起', _carry_lines, env=DAY, spot='carry_home', vantage=(.5, 14, 180, 25),
    note='孩子离窝超过 4 格即触发找回（purpose=RETRIEVE）；是否接起由亲油自己的照护决定，不指定 Goal。离开前一趟抬起几只看 carry pickup 的 aboard=（橙 2；魔理沙按帽檐空间再口含一只；灵梦按口腔容量），放不下的留给下一趟。可右键问候／开照料菜单、攻击被抬着的孩子（灵梦口里看不见的除外）：孩子不会因此被放下，也不会自己走；攻击亲油则每次受击掷一次摔落（概率＝伤害占最大生命的比例×（1−自制/100），日志 [family] carry hit drop chance= roll= dropped=），命中则最后抱上的一只在原处掉到地面。日志 [family] carry lift started／carry pickup hold=brim／mouth／tail_left／tail_right／carry put down。',
    reads=[('读承托状态（home.family_state 的 carry=）', 'yukkuri status @e[type=#mcylab:yukkuri,tag=scn,distance=..48] raw')],
    capture=dict(shots=(1, 40, 1200, 2400, 3600), sequences=((2, 1000, 4), (2500, 3300, 10)), observe=False,
                 views={kind: dict(follow=f'@e[tag=scn_{kind}_parent,limit=1]',
                                   offset={'marisa':(-2.2,1.15,1.2),'reimu':(-1.4,.65,.8),'chen':(-1.8,.9,-1)}[kind],
                                   criteria={'marisa':'看清魔理沙用辫子抬幼体、帽檐承托以及到窝放下；按当前阶段判断，不要求抬起前已经携带。',
                                             'reimu':'看清灵梦张嘴接幼体与到窝张嘴放出；闭嘴时口含幼体本来不可见，不能当成消失。',
                                             'chen':'看清橙的两条尾巴承托幼体、带回窝并放下；只要求此家庭出现在画面。'}[kind]) for kind, _, _, _ in _carry_families}),
    smoke=dict(ticks=3600,
               expect=[r'\[family\][^\n]* carry lift started\b',
                       r'\[family\][^\n]* carry pickup hold=(?:brim|mouth|tail_left|tail_right)\b',
                       r'\[family\][^\n]* carry put down reason=\S+ [^\n]*cause=release [^\n]*nest=(?:INSIDE_SUPPORTED|PARTIAL)\b'],
               forbid=[r'\[home\][^\n]* assignment refused\b', r'\[family\][^\n]* task stopped task=SEARCH target=null\b',
                       r'\[home\][^\n]*bed crossing[^\n]*opening=false source=walk', r'\[home\][^\n]*nest_not_inside\b',
                       r'\[home\][^\n]*reason=doorway_unreachable\b',
                       r'\[family\][^\n]* carry put down forced\b', r'\[family\][^\n]* cause=carry_release_blocked\b'],
               readout=['yukkuri status @e[type=#mcylab:yukkuri,tag=scn] raw'],
               verify=_carry_verify))

# Both families start outdoors with unmounted children and dry beds nearby.
_walk_home = 'family_walk_home'
_flight_home = 'family_flight_home'
_carry_nest = [*shed(0, 0), *large_nest(0, 0, 'mcyukkuri:large_yukkuri_nest')]
_carry_transport_forbid = [r'\[family\][^\n]* carry put down forced\b',
                           r'\[family\][^\n]* cause=carry_release_blocked\b',
                           r'\[family\][^\n]* carry put down [^\n]*cause=carry_fright\b']
_walk_parent = uid(_walk_home, 'mother')
_flight_parent = uid(_flight_home, 'mother')

add(_walk_home, 'family', '天晚了，亲油抱赤油和小子油回窝', '天晚了，亲油抱赤油和小子油回窝',
    '魔理沙和两只赤油、一只刚长成的子油离窝几格。看亲油把赤油放回家窝，再把小子油送进旁边的子油窝。',
    '约半分钟开始接抱', [*_carry_nest, *shed(10, 0), *large_nest(10, 0, 'mcyukkuri:large_yukkuri_nest'),
        yk('marisa', 0, 6, 'mother', uuid=_walk_parent, yaw=0, lead=True, rest_wait=60,
           fam='Version:2,Initialized:1b,Stage:"ADULT",Age:672000L,Nourishment:672000L,Maturity:336000L,Lifespan:1056000L,AdultScale:1.2f,Scale:1.2f'),
        *[yk('marisa', x, 8, name, stage='baby', uuid=uid(_walk_home, name), yaw=180,
             kin=kin_of(Mother=_walk_parent), childhood=CHILDHOOD_HOPPING)
          for x, name in [(-.8, 'baby_a'), (.8, 'baby_b')]],
        *[home(name, 0, 0) for name in ('mother', 'baby_a', 'baby_b')]],
    late=[Retry(yk('marisa', .1, 8.6, 'small_child', stage='child', share=.215, energy=60,
                   uuid=uid(_walk_home, 'small_child'), yaw=180, kin=kin_of(Mother=_walk_parent),
                   childhood=CHILDHOOD_HOPPING), sel('mother'), tries=1, first=1),
          Retry(home('small_child', 10, 0), sel('small_child'), tries=1, first=41)],
    env={'time': 'night'}, spot='carry_home', vantage=(.5, 13, 180, 20),
    note='看会跳的赤油在帽檐座位上转向落点、蹲身蓄力，再从帽檐跳下并落稳。没有安全跳路时由亲油用辫子托放。',
    reads=[('读亲油与幼体的承托', 'yukkuri status @e[tag=scn,distance=..48] raw')],
    smoke=dict(ticks=2200, expect=[r'\[family\][^\n]*carry pickup .*purpose=RETRIEVE .*childStage=BABY\b',
                                  r'\[action\][^\n]*uuid=(\S+) [^\n]* start id=hop owner=brim_release [^\n]*departure=worn_brim\b'
                                  r'[\s\S]*\[body\][^\n]*uuid=\1 [^\n]* dismount [^\n]*cause=hop_takeoff\b'
                                  r'[\s\S]*\[action\][^\n]*uuid=\1 [^\n]* end id=hop outcome=DONE [^\n]*legs=1 peak=(?!0\.00)'],
               forbid=_carry_transport_forbid))

add(_flight_home, 'family', '捕食种靠近，亲油抱起赤油躲回窝', '捕食种靠近，亲油抱起赤油躲回窝',
    '灵梦与两只赤油在窝外，附近有路过的蕾米莉亚。看亲油察觉威胁后就近接抱，随后退回窝边把孩子放到窝里。',
    '几秒内接抱，约半分钟回窝', [*_carry_nest,
        yk('reimu', 0, 8, 'mother', uuid=_flight_parent, yaw=180, lead=True, boldness=75, attachment=85),
        *[yk('reimu', x, 7.5, name, stage='baby', uuid=uid(_flight_home, name),
             kin=kin_of(Mother=_flight_parent), childhood=CHILDHOOD_HOPPING)
          for x, name in [(-.45, 'baby_a'), (.65, 'baby_b')]],
        yk('remilia', 0, 18, 'predator', uuid=uid(_flight_home, 'predator'), yaw=180,
           label='路过的捕食种', nutrition=100, energy=100),
        *[home(name, 0, 0) for name in ('mother', 'baby_a', 'baby_b')]],
    env=DAY, spot='carry_home', vantage=(5, 12, 120, 20),
    reads=[('读威胁反应与幼体承托', 'yukkuri status @e[tag=scn,distance=..48] raw')],
    smoke=dict(ticks=1600, expect=[r'\[family\][^\n]*carry flight choice .*gather=true',
                                  r'\[family\][^\n]*carry flight pickup ended aboard=[1-9]\d*\b'],
               forbid=_carry_transport_forbid))

# ====================================================================================================== which concern each scenario answers
# Fresh NPC UUID on every start: confirmed death records are intentionally never reset by scene cleanup.
_custody_lines = [human('caretaker_brother', 0, -3, 'custody_owner', 1, label='NPC主人')]
for _kind, _dx, _role, _name, _label in (
        ('reimu', -6, 'companion', 'custody_pet', '带章宠物'),
        ('marisa', -3, 'stock', 'custody_stock', '库存个体'),
        ('alice', 0, 'input', 'custody_input', '旧档工厂输入'),
        ('chen', 3, 'lost', 'custody_lost', '可先装篮'),
        ('reimu', 6, 'companion', 'custody_buyer', '已归玩家')):
    _commerce = 'OwnerKind:"npc"' if _role == 'input' else f'Use:"{_role}",OwnerKind:"npc"'
    if _role == 'stock':
        _commerce += ',ForSaleSince:1L,StaleCuts:1,ShopCandidate:1b'
    _custody_lines += [yk(_kind, _dx, 2, _name, label=_label, grade=1 if _role in ('companion', 'lost') else None,
                          lead=_name == 'custody_pet', extra='NeoForgeData:{HumanCommerce:{' + _commerce + '}}'),
                       f'data modify entity {sel(_name)} {OWNED}.Owner set from entity {sel("custody_owner")} UUID']
    if _role == 'input':
        _custody_lines += [f'data merge entity {sel(_name)} '
                           '{NeoForgeData:{HumanCommerce:{Use:"input",FactoryStage:"observe",StageWork:25,'
                           'FactoryYoung:1b,HoldReason:"nutrition",FactoryCycles:3}}}']
_custody_lines += [*own('custody_buyer'),
                  f'yukkuri status relate {sel("custody_pet")} {sel("custody_owner")} 30',
                  f'data modify entity {sel("custody_lost")} YukkuriPet.Care.Harness set value 1b',
                  f'data modify entity {sel("custody_lost")} YukkuriPet.Care.WearOwner set from entity {sel("custody_owner")} UUID',
                  yk('marisa', 9, 2, 'custody_unknown', label='UNKNOWN旧档',
                     extra='NeoForgeData:{HumanCommerce:{Owner:' + uuid_nbt(uid('custody_death', 'unseen_owner')) + ',Use:"future_use"}}')]
add('custody_death', 'town', 'NPC主人死了，宠物库存无主', 'NPC主人死了，宠物库存无主',
    '旧档工厂输入开场解除归属。先用篮子装“可先装篮”，再手动杀死NPC主人：现场宠物和库存转无主，玩家个体与UNKNOWN旧档保留归属。放出篮中个体，胸背带只归还一次，徽章仍保留',
    '工厂输入开场解除；其余在死亡后或从篮中放出时', _custody_lines, env=DAY, vantage=(0, 14), note=NEW,
    give=['mcyukkuri:yukkuri_carrier 2', 'mcyukkuri:sleep_spray 2', 'minecraft:emerald 16'],
    reads=[('读宠物归属', f'data get entity {sel("custody_pet")} NeoForgeData.HumanCommerce'),
           ('读旧厂输入归属', f'data get entity {sel("custody_input")} NeoForgeData.HumanCommerce'),
           ('读玩家归属', f'data get entity {sel("custody_buyer")} NeoForgeData.HumanCommerce'),
           ('读旧档归属', f'data get entity {sel("custody_unknown")} NeoForgeData.HumanCommerce')],
    smoke=dict(ticks=180, commands=[(60, f'kill {sel("custody_owner")}')],
               expect=[r'\[custody\] alice[^\n]*factory closure cleanup completed formerOwner=[a-f0-9-]+ identity=stray',
                       r'\[custody\] reimu[^\n]*owner death cleanup completed formerOwner=[a-f0-9-]+',
                       r'\[custody\] marisa[^\n]*owner death cleanup completed formerOwner=[a-f0-9-]+'],
               forbid=[r'\[custody\] alice[^\n]*owner death cleanup completed']))

# Fixed numerical fixtures; ordinary AI owns every action after these initial conditions.
add('balance_traits', 'meadow', '五属性20／50／80／90对照', '五属性20／50／80／90对照',
    '四只普通油的五属性分别为20、50、80、90。看学习概率、遵守规则概率、等待tick和任务段数；先教会具体技能，再观察执行',
    '读数立即可看；行为需实际互动', [
        *[yk('reimu', (n - 1.5) * 3, 0, f'axis{v}', label=f'五属性{v}', lead=n == 0,
             intelligence=v, friendliness=v, boldness=v, attachment=v, self_control=v, social_wait=0)
          for n, v in enumerate((20, 50, 80, 90))],
        item('minecraft:apple', 8, 0, 4)], env=DAY,
    give=['mcyukkuri:field_notebook', 'minecraft:cookie 16'],
    reads=[('读五属性与行为参数', 'yukkuri status @e[tag=scn,type=mcyukkuri:reimu,distance=..40] raw')])

# The three stand on an arc in front of the arrival point (vantage below): nine to ten blocks from it and at least eight apart, so a peer
# is never inside the six-block social scan and no floor food competes with the apple the player holds.
_BALANCE_ARC = ((0, -5), (-8, -8), (8, -8))
_BALANCE_FEAR = ((0, '0'), (45, '45'), (70, '70'))
add('balance_relation', 'meadow', '亲近玩家但对玩家的恐惧0／45／70', '亲近玩家但对玩家的恐惧0／45／70',
    '三只对玩家好感都是80，恐惧分别是 0／45／70（恰好落在代码分档 30／60 的两侧），彼此相距八格以上、地上没有苹果。按顺序试：'
    '①站住不动等它靠近→恐惧 0 自己走过来打招呼；45／70 先站着看几秒（胆子越大越快）再靠近问候。'
    '②朝一只走近（进到六格内）→45／70 在观察期里被逼近就后退，0 迎着走过来。'
    '③手里拿着苹果（十格内）→只有 0 会过来（<30）；45／70 不来。'
    '④右键递苹果→三只都吃（进食不看恐惧，吃完好感 +8、恐惧 −5）。'
    '⑤双手空着潜行右键，在「身体动作」找「轻抱」和「抚摸」→只有 0 可选（轻抱 <40、抚摸 <25，抚摸还需靠到 1.5 格内）；45／70 不显示。'
    '70 还有更细的差别：靠近到 1.5 格内会缩开、不能一起休闲／贴贴／舔伤（≥60）；45 只是谨慎。读“对象关系”看 closeness／fear／fear_band 真实值',
    '约30–60秒', [
        *[command for n, (fear, text) in enumerate(_BALANCE_FEAR)
          for command in (yk('reimu', *_BALANCE_ARC[n], f'fear{fear}', label=f'好感80 恐惧{text}',
                             lead=n == 0, nutrition=90),
                          f'yukkuri status relate {sel(f"fear{fear}")} @s 80',
                          f'yukkuri status fear {sel(f"fear{fear}")} @s {fear}')]],
    env=DAY, give=['minecraft:apple 6'],
    reads=[('读三只关系', 'yukkuri status @e[tag=scn,type=mcyukkuri:reimu,distance=..40] raw')])

add('balance_forms', 'meadow', '不足油与多毛灵梦的属性上下限', '不足油与多毛灵梦的属性上下限',
    '不足油仅含混发声，智力不超过25、友善不低于75；多毛友善和自制不超过25、依恋不低于75。正常灵梦作对照；真实学习仍可改善合法区间内的属性',
    '读数立即可看', [
        yk('reimu', -4, 0, 'deficient', congenital='BEAN_BODY', intelligence=90, friendliness=10, label='不足', lead=True),
        yk('reimu', 0, 0, 'wasa', form='WASA_REIMU', friendliness=90, attachment=10, self_control=90, label='多毛'),
        yk('reimu', 4, 0, 'ordinary', label='普通'), item('minecraft:apple', 6, 0, 4)], env=DAY,
    give=['mcyukkuri:field_notebook'],
    reads=[('读形态与五属性', 'yukkuri status @e[tag=scn,type=mcyukkuri:reimu,distance=..40] raw')])

add('balance_deibu', 'family', '吃得好压力大的亚成长成灵胖', '吃得好压力大的亚成长成灵胖',
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

# ====================================================================================================== pond (anchor: the bank cell at feet level; water is one row down)
def captain(name='a', *, dx=0, dz=0, **kw):
    return yk('marisa', dx, dz, name, aquatic=True, yaw=-90, lead=True, **pair_ready(), **kw)


def pond(sid, title, hint, watch, wait, lines, note='', vantage=(-6, 6, -90), **kw):
    # Restore the entire shared field before actors spawn; specialised scenes may override it below.
    add(sid, 'pond', title, hint, watch, wait, [*BY_ID['pond'].biome_commands(), *lines],
        env=DAY, note=note, vantage=vantage, **kw)


def bank_dx(dz):
    """x offset (from the anchor bank cell) of the bank cell on the row dz south of it: the pond's shore is not straight."""
    pond_ = BY_ID['pond']
    z = pond_.pz + dz
    x = pond_.px - pond_.RX - 3
    while pond_.depth(x, z) == 0:
        x += 1
    return x - 1 - pond_.bank[0]


def water_at(dz, out):
    """Water position ``out`` blocks east of the bank cell's centre; fractional berths retain their real clearance."""
    return f'~{bank_dx(dz) + out + .5} ~-1 ~{dz + .5}'


def _bank_food(item_id='minecraft:apple'):
    """One real portion on the dry bank at each side of the pond (west, east, north, south), a block back from the rim: from
    anywhere on the water a hungry aquatic owner has food of hers within her meal search of some bank (FEATURES 食物)."""
    pond_ = BY_ID['pond']
    out = []
    for ux, uz in ((-1, 0), (1, 0), (0, -1), (0, 1)):
        x, z = pond_.px, pond_.pz
        while pond_.depth(x, z):
            x, z = x + ux, z + uz
        out.append(item(item_id, 1, x + ux - pond_.bank[0], z + uz - pond_.bank[2], 'food'))
    return out


def pond_rim_seagrass(every=2):
    """Real finite seagrass along the pond's shallow rim (depth 1), so the shore an aquatic family comes ashore on always has the
    water plant it picks from the bank above: vanilla never regrows it, and clearing the field puts the water back."""
    pond_ = BY_ID['pond']
    return [block(x - pond_.bank[0], -1, z - pond_.bank[2], 'minecraft:seagrass')
            for x in range(pond_.px - pond_.RX, pond_.px + pond_.RX + 1)
            for z in range(pond_.pz - pond_.RZ, pond_.pz + pond_.RZ + 1)
            if pond_.depth(x, z) == 1 and (x + z) % every == 0]


def afloat(owner, water, aboard=True, crew=None, food=0, middle=False, stick=False, facing=0):
    """`scenario afloat`: the owner sets her real hat down at that water position with ``food`` apples (0..8)
    (and, with ``stick``, one real stick to paddle with). The captain takes her seat first, then each young takes an admitted
    seat. A stick needs a crew argument (the owner's own selector when she is alone)."""
    tail = f' {food}' + (' middle' if middle else ' stick' if stick else '') if food or middle or stick else ''
    if facing:
        tail += f' {facing}'
    return f'yukkuri scenario afloat {sel(owner)} {water} {"true" if aboard else "false"}' + (f' {crew}' if crew else '') + tail


def afloat_now(owner, water, aboard=True, crew=None, **kw):
    """`afloat` in the same tick as the summons (not two seconds later): the owner has had no tick of her own, so her hat is never
    set down by her own place_prop while the setup seats her (the line is wrapped so it is not deferred like the others)."""
    return f'execute as {sel(owner)} run ' + afloat(owner, water, aboard, crew, **kw)


_HAT = '@e[type=mcyukkuri:placed_hat,distance=..48,limit=1,sort=nearest]'
_DESIRE_NOTE_EARLY = '读 aquatic.desire 看每个欲望的得分与来由。'


def shoved(name, push=.35):
    """A late shove off the bank into the pond (vanilla Motion, east): the body really falls in."""
    return f'data merge entity {sel(name)} {{Motion:[{push}d,0.3d,0.0d]}}'


def _seat_family(size=.35, nourishment=147000, age=144000):
    """A real saved body and nourishment near a seat boundary; ordinary loaded ticks perform subsequent growth."""
    return f'Version:3,Initialized:1b,Age:{age}L,Nourishment:{nourishment}L,AdultScale:1f,Scale:{size}f'


def _uuid_text(sid, slot):
    return str(uuid.UUID(bytes=b''.join((n & 0xffffffff).to_bytes(4, 'big') for n in uid(sid, slot))))


def _overload_family_choice(window):
    """Both kinds of parent on overload (FEATURES 载重, 超载断亲): the ordinary parent reads her hull's load warning and takes the
    family ashore to unload before it goes over (only a real overload capsize when she is too late dismisses her largest young);
    the keeper (low intelligence, high friendliness) keeps going until it capsizes and keeps all her young. The `early` family's
    young grow only into the warning: she must turn for the bank and land them, with no overload capsize."""
    for parent, exceptional in (('early', False), ('normal', False), ('kind', True)):
        owner = _uuid_text('hat_crowd', parent)
        hulls = re.findall(r'\[hat\][^\n]* placed owner=' + owner + r' hat=(\S+)', window)
        if not hulls:
            return f'{parent}: hat was not placed'
        boat = re.escape(hulls[0])
        boats = '(?:' + '|'.join(map(re.escape, dict.fromkeys(hulls))) + ')'
        # the largest young that fits takes the head, the rest fill the brim (user 2026-11-05)
        places = ((1, 'CABIN'), (2, 'BRIM'), (3, 'HEAD')) if exceptional else ((2, 'BRIM'), (3, 'HEAD'))
        for child, place in places:
            if not re.search(r'\[hat\][^\n]*uuid=' + _uuid_text('hat_crowd', parent + str(child))
                             + r'[^\n]* scenario place hat=' + boat + r' place=' + place + r'\b', window):
                return f'{parent}{child}: missing real {place} placement'
        rolls = re.findall(r'\[aquatic\][^\n]*uuid=' + boats + r'[^\n]* load changed event=(\w+) body=\S+ '
                           r'load=(\S+) capacity=(\S+) chance=(\S+) roll=(\S+) result=(\w+)', window)
        overloaded_growth = False
        for event, load, capacity, chance, roll, result in rolls:
            load, capacity, chance, roll = map(float, (load, capacity, chance, roll))
            expected = 0 if load <= capacity else 1 - capacity / load
            if abs(chance - expected) > 1e-9 or (result == 'capsize') != (roll < chance):
                return f'{parent}: overload chance or result disagrees with the logged load and roll'
            overloaded_growth |= event == 'growth' and load > capacity
        capsized = re.search(r'\[hat\][^\n]*uuid=' + boats + r'[^\n]* capsized cause=too_heavy\b[^\n]*thrown=[1-9]', window)
        dismissals = list(re.finditer(r'\[family\][^\n]*uuid=' + owner + r'[^\n]* parent dismissed child=(\S+)', window))
        if exceptional:
            if not overloaded_growth:
                return f'{parent}: no real growth into overload'
            if capsized is None:
                return f'{parent}: no overload capsize in this random run'
            if dismissals or not re.search(r'\[family\][^\n]*uuid=' + owner + r'[^\n]* overload keeps children', window[capsized.end():]):
                return 'exceptional parent did not keep all children after overload'
            if re.search(r'\[aquatic\][^\n]*uuid=' + owner + r'[^\n]* load heavy ', window):
                return 'exceptional parent reacted to the load warning'
            continue
        if parent == 'early' and capsized is not None:
            return 'early: the hull capsized from overload though its young grow only into the warning'
        if parent == 'early' and len(re.findall(r'\[action\][^\n]*uuid=' + _uuid_text('hat_crowd', 'early2')
                                                + r' [^\n]* refused id=hop [^\n]*detail=no_spot', window)) > 1:
            return 'early2: hopped for a hull with no spot more than once'
        if capsized is None:
            # in time: the load warning sends her ashore, the family lands, and nobody is dismissed without a capsize
            heavy = re.search(r'\[aquatic\][^\n]*uuid=' + owner + r'[^\n]* load heavy hat=' + boats + r'[^\n]* reason=unload_ashore', window)
            if heavy is None:
                return f'{parent}: neither unloaded at the load warning nor capsized'
            if not re.search(r'\[intent\][^\n]*uuid=' + owner + r'[^\n]* end id=come_ashore result=DONE\b', window[heavy.end():]):
                return f'{parent}: turned for the bank at the load warning but never landed the family'
            if dismissals:
                return f'{parent}: dismissed a young without an overload capsize'
            continue
        if not dismissals or dismissals[0].start() < capsized.end() or dismissals[0][1] != _uuid_text('hat_crowd', parent + '3'):
            return 'normal parent did not dismiss the largest young after the real overload capsize'
        capsize_ticks = re.findall(r'\[hat\][^\n]*uuid=' + boats + r' t=(\d+)[^\n]* capsized cause=too_heavy\b', window)
        dismissal_ticks = [re.search(r' t=(\d+)', dismissal[0])[1] for dismissal in dismissals]
        if dismissal_ticks != capsize_ticks:
            return 'normal parent must dismiss exactly once at each overload capsize, never for another cause'
        for dismissal in dismissals:
            if re.search(r'\[aquatic\][^\n]*uuid=' + owner + r'[^\n]* rescue start faller=' + dismissal[1], window[dismissal.end():]):
                return 'dismissed young was regathered by its former carer'
    return None


def _escape_needs_place(window):
    """A climb that ended rest_place_lost while the body held no place on the hull (its last attach is not on_host) is a failed
    climb: an `escape done support=aboard` right after it is a false success. A body still on the host's spot is settled."""
    attach = {}
    for line in window.splitlines():
        who = re.search(r'\[body\][^\n]* uuid=(\S+) t=\d+ [^\n]* attach [^\n]*current=(\S+)', line)
        if who:
            attach[who[1]] = who[2]
        lost = re.search(r'\[action\][^\n]* uuid=(\S+) t=(\d+) [^\n]* end id=climb outcome=BLOCKED [^\n]*detail=rest_place_lost', line)
        if lost and not attach.get(lost[1], '').startswith('on_host:'):
            done = re.search(r'\[water\][^\n]* uuid=' + lost[1] + r' t=(\d+) [^\n]* escape done [^\n]*support=aboard',
                             window[window.index(line):])
            if done and int(done[1]) - int(lost[2]) <= 3:
                return 'escape done support=aboard right after a climb that held no place (rest_place_lost): ' + lost[1]
    return None


def _overload_family(parent, dz, intelligence, friendliness, cabin, grown=(120000, 210000)):
    """Saved young below the size their stored nourishment (`grown`, young 2 and 3) already sustains: the ordinary loaded ticks
    grow them aboard. They start just under the load warning for the ordinary parent (head 0.355 + brim 0.20: 0.83 of capacity;
    at 0.18 young 2 takes the cabin instead), so her own load rule seats them. By default the head young grows at once to 0.50
    and young 2 to 0.29, a load over twice the hull's capacity with no warning in between; young 2 stays under that capacity on
    its own, so the parent that lost its largest stops overloading while the exceptional one keeps all three and overloading.
    `grown=(84000, 157500)` grows only the head young, to 0.375: the hull sits in its warning (0.96) and never overloads.
    One real pile of apples floats in the open water beyond each berth, next to the eight in the hat."""
    return [yk('marisa', bank_dx(dz), dz, parent, aquatic=True, yaw=-90, lead=True,
               uuid=uid('hat_crowd', parent), intelligence=intelligence, friendliness=friendliness,
               label=f'智{intelligence}·友善{friendliness}', **pair_ready()),
            *[yk('marisa', bank_dx(dz + offset) - 1, dz + offset, parent + str(i), aquatic=True,
                 fam=_seat_family(size, nourishment, age), childhood=CHILDHOOD_CRAWLING_READY,
                 uuid=uid('hat_crowd', parent + str(i)), also=(parent + '_crew',),
                 kin=f'Mother:{uuid_nbt(uid("hat_crowd", parent))}', label=f'孩子{i}', **pair_ready())
              for i, offset, size, nourishment, age in ((1, -1, .16, 22909, 36000), (2, 0, .20, grown[0], 76000),
                                                       (3, 1, .355, grown[1], 72000)) if cabin or i != 1],
            item('minecraft:apple', 8, bank_dx(dz) + 9, dz, name=parent + '_food'),
            afloat_now(parent, water_at(dz, 6), True, sel_all(parent + '_crew'), food=8, stick=True)]


def _babies():
    return [yk('marisa', bank_dx(dz), dz, name, stage='baby', aquatic=True, childhood=CHILDHOOD_CRAWLING_READY,
               fam=_seat_family(size, nourishment, 36000), also=('crew',), **pair_ready())
            for name, dz, size, nourishment in (('k1', 1, .16, 22909), ('k2', -1, .19, 68727))]


_HULL_READS = [('读漂浮、触发与扶正', f'yukkuri status {sel("a")} raw'), ('读帽子状态', f'data get entity {_HAT}')]
_HULL_READOUT = [f'yukkuri status {sel("a")} raw', f'data get entity {_HAT} Riders', f'data get entity {_HAT} Pos', f'data get entity {_HAT} Motion', f'data get entity {_HAT} Inverted']


def push_smooth(log):
    """The light contact slides a hull away without capsizing it; only the fast impact capsizes, and bank contact stays stopped."""
    capsized = re.findall(r'\[hat\][^\n]* uuid=(\S+) t=\d+[^\n]* capsized\b', log)
    if len(capsized) != 1:
        return 'expected only the fast impact to capsize'
    travel = re.compile(r'\[hat\][^\n]* uuid=(\S+) t=\d+[^\n]* travel at=\([^)]*\) delta=\(([-\d.E]+),[^)]*\)[^\n]* impact=([-\d.E]+)')
    by_hat = {}
    for hat, dx, impact in travel.findall(log):
        by_hat.setdefault(hat, []).append((float(dx), float(impact)))
    pushes = [[next_dx for next_dx, _ in rows[i + 1:i + 13]] for rows in by_hat.values()
              for i, (_, impact) in enumerate(rows) if 0 < impact < .16 and len(rows[i + 1:i + 13]) == 12]
    if not any(all(dx <= 0 for dx in push) and sum(push) <= -.1 for push in pushes):
        return 'light contact must slide the hull at least 0.1 blocks away without coming back'
    for rows in by_hat.values():
        for i in range(1, len(rows)):
            if rows[i - 1][0] < 0 and rows[i][1] > .16 and any(abs(dx) > 1e-9 for dx, _ in rows[i + 1:i + 6]):
                return 'bank contact rebounded instead of staying stopped'
    return None


pond('hat_push', '木船轻碰只推开，猛撞才翻', '木船轻碰只推开，猛撞才翻',
     '两顶载人的帽船旁各有一艘真实木船：近处木船低速碰帽，只推开或碰停；远处木船快速撞帽，帽子先猛烈摇摆再翻覆。正常划船撞岸或碰另一顶帽不会翻覆',
     '约 30 秒',
     [captain(dx=bank_dx(0)), yk('marisa', bank_dx(5), 5, 'fast', aquatic=True, yaw=-90, label='猛撞边界', **pair_ready()),
      afloat_now('a', water_at(0, 3)), afloat_now('fast', water_at(5, 3))],
     late=[f'execute as {sel("a")} on vehicle at @s run summon minecraft:boat ~1.8 ~ ~ {{Type:"oak",Tags:["scn_prop","low_push"]}}',
           f'execute as {sel("fast")} on vehicle at @s run summon minecraft:boat ~2 ~ ~ {{Type:"oak",Tags:["scn_prop","fast_push"]}}',
           Retry('data merge entity @e[tag=low_push,limit=1] {Motion:[-0.08d,0d,0d]}', sel('a'), tries=1, first=40),
           Retry('data merge entity @e[tag=fast_push,limit=1] {Motion:[-0.65d,0d,0d]}', sel('fast'), tries=1, first=40)],
     note='木船只在初态给速度，之后按真实碰撞运动；读 trigger=impact、value 与 capsizes。近处帽船 capsizes=0，远处帽船只记一次猛撞翻覆。',
     reads=_HULL_READS + [('读猛撞船主', f'yukkuri status {sel("fast")} raw')],
     capture=dict(shots=(1, 40, 300, 600), sequences=((60, 200, 2),)),
     smoke=dict(ticks=600,
                expect=[r'\[hat\][^\n]* capsized cause=impact value=[^\n]*count=1'],
                forbid=[r'\[hat\][^\n]* capsized cause=(?:shake|turbulence)\b', r'\[body\][^\n]* invariant\b'],
                verify=push_smooth,
                readout=_HULL_READOUT + [f'yukkuri status {sel("fast")} raw']))


def flow_channel(*lanes, lake=()):
    """Gravel stream beds (one per ``lanes`` row, each three wide) and still lakes (``lake`` rows) on one low grassy plateau;
    fluid updates supply all flow and turbulence. Each stream's water starts at the plateau edge, so a hull centred one cell in
    has its whole brim (radius .89) over flowing water and no longer overlaps the west bank; downstream the same one-block flow
    still shallows out and strands it. The dry bank stays one cell west of the water."""
    x = bank_dx(0)
    rows = (*lanes, *lake)
    lo, hi = min(rows) - 4, max(rows) + 4
    out = [box(x, -3, lo, x + 13, -1, hi, 'minecraft:dirt'),
           box(x, -1, lo, x + 13, -1, hi, 'minecraft:grass_block'),
           box(x, 0, lo, x + 13, 4, hi, 'minecraft:air')]
    for z in rows:
        out += [box(x, -2, z - 1, x + 9, -2, z + 1, 'minecraft:gravel'),
                box(x, -1, z - 1, x + 9, 4, z + 1, 'minecraft:air'),
                *([box(x, -1, z - 1, x + 9, -1, z + 1, 'minecraft:water')] if z in lake else
                  [block(x, -1, zz, 'minecraft:water') for zz in (z - 1, z, z + 1)])]
    return out


def lane_at(dz, out):
    """Water position ``out`` blocks east of the plateau edge on row ``dz`` (the plateau is flat, unlike the natural shore)."""
    return f'~{bank_dx(0) + out + .5} ~-1 ~{dz + .5}'


def current_drift(log):
    points = [float(x) for x in re.findall(r'\[hat\][^\n]* travel at=\(([-\d.]+),', log)]
    if len(points) < 2 or max(points[:200]) - points[0] < 3:
        return 'hat must drift at least 3 blocks downstream in 200 ticks'
    # The young that cannot hop onto the brim rides the captain's head (it is its real place): at grounding it leaves the hat with
    # her, not by itself.
    head = re.search(r'uuid=(\S+)[^\n]* scenario place [^\n]*place=HEAD\b', log)
    if head is None:
        return 'the young must be seated on the head (it cannot hop there itself)'
    captain = re.search(r'\[hat\][^\n]* left rider=(\S+)[^\n]*', log)
    if captain is None or not re.search(rf'\[body\][^\n]*uuid={captain[1]}[^\n]* dismount [^\n]*cause=grounded'
                                         rf'[^\n]*passengers=\[{head[1]}', log):
        return 'the captain must leave the grounded hat still carrying her head young'
    # Her eyes go under in the shoal, so the young on her head falls in with her (rule); it must then get out alive.
    if re.search(rf'\[body\][^\n]*uuid={head[1]}[^\n]* death settled', log):
        return 'the head young must not die after falling in with the captain'
    ashore = set(re.findall(r'\[water\][^\n]*uuid=(\S+)[^\n]* escape done [^\n]*support=stand=', log))
    if captain[1] not in ashore:
        return 'the captain must reach her own dry stand after grounding'
    back = re.search(r'\[headwear\][^\n]* received loose accessory', log)
    if back and re.search(r'\[action\][^\n]* start id=pick\b', log[:back.start()]):
        return 'the owner went for loose items before taking her hat back'


pond('hat_current', '帽子顺缓流漂到浅滩搁浅', '帽子顺缓流漂到浅滩搁浅',
     '浅溪上游一排水源形成同向水流，两侧低草岸可爬上；船主乘帽随水流缓慢漂动，碰到岸边只停住。没有落水、流向冲突或猛撞时不会翻船',
     '约 30 秒',
     [*flow_channel(0), captain(dx=bank_dx(0) - 1, social_wait=100),
      yk('marisa', bank_dx(1) - 1, 1, 'child', stage='child', aquatic=True, fam=_seat_family(), childhood=CHILDHOOD_CRAWLING_READY,
         **pair_ready()),
      afloat('a', water_at(0, 1), True, sel('child'), food=4, stick=True)],
     vantage=(bank_dx(0) + 11, -3, 40, 18),
     note='观察船长带着头顶的子油离帽、自己走上干岸（头顶子油不会自己跳，随亲体离帽），四份苹果和一根木棍各掉出，帽主接触头饰即戴回；帽船搁浅不算翻覆。倒扣边界与载人翻覆见“落水掀翻帽子与帽子搁浅”。',
     reads=_HULL_READS + [('读子油', f'yukkuri status {sel("child")} raw')],
     capture=dict(shots=(1, 40, 300, 600, 900), sequences=((60, 200, 2), (220, 1100, 20))),
     smoke=dict(ticks=1100,
                expect=[r'\[hat\][^\n]* scenario afloat prepared[^\n]*young=1[^\n]*food=4',
                        r'\[body\][^\n]* dismount [^\n]*cause=grounded',
                        r'\[hat\][^\n]* stores spilled [^\n]*cause=grounded', r'\[hat\][^\n]* kit dropped cause=grounded',
                        r'\[hat\][^\n]* pickup cause=grounded',
                        r'\[headwear\][^\n]* received loose accessory'], verify=current_drift,
                forbid=[r'\[hat\][^\n]* capsized\b', r'\[body\][^\n]* invariant\b', r'\[body\][^\n]* cause=drown',
                        r'\[body\][^\n]* death settled into remains'],
                readout=_HULL_READOUT + [f'yukkuri status {sel("child")} raw',
                    'execute as @e[type=minecraft:item,distance=..48] run data get entity @s Item']))


def capsized_crew(log):
    """Each rider of the loaded hat leaves it into the water by a logged cause: the brim's hard-rock fall, then the capsize throw."""
    crew = {_uuid_text('hat_grounded_capsized', slot) for slot in ('a', 'child')}
    left = {}
    for line in log.splitlines():
        dismount = re.search(r'\[body\][^\n]* uuid=(\S+)[^\n]* dismount [^\n]*cause=(brim_hard_rock|thrown)', line)
        if dismount and dismount[1] in crew:
            left[dismount[1]] = (dismount[2], int(re.search(r' t=(\d+) ', line)[1]))
    if sorted(cause for cause, _ in left.values()) != ['brim_hard_rock', 'thrown']:
        return f'each rider must leave the hull by a real cause, saw {left}'
    for uuid, (cause, tick) in left.items():
        entries = [int(m[1]) for m in re.finditer(r'\[water\][^\n]* uuid=' + uuid + r'[^\n]* t=(\d+)[^\n]* entered at=', log)]
        if not any(entry >= tick for entry in entries):
            return f'rider {uuid} ({cause}) left the hull without entering the water'
    return None


def _grounded_smoke():
    """Three hats on three lanes, each told apart by its owner: the loaded hat (capsize, thrown riders, grounding), the empty
    inverted hat (grounding) and the hat under the waterfall (turbulence)."""
    a, child, inverted, falling = (_uuid_text('hat_grounded_capsized', slot) for slot in ('a', 'child', 'inverted', 'falling'))
    return dict(
        ticks=1800,
        expect=[rf'\[hat\][^\n]*uuid={a}[^\n]* scenario afloat prepared [^\n]*owner_aboard=true[^\n]*young=1[^\n]*food=4',
                rf'\[hat\][^\n]*uuid={inverted}[^\n]* scenario afloat prepared [^\n]*owner_aboard=false',
                rf'\[hat\][^\n]*uuid={falling}[^\n]* scenario afloat prepared [^\n]*owner_aboard=true[^\n]*young=0',
                rf'\[hat\][^\n]* capsized cause=(?:turbulence|impact)[^\n]*thrown=1\b[^\n]*owner={a}',
                r'\[body\][^\n]* dismount [^\n]*cause=brim_hard_rock',
                r'\[body\][^\n]* dismount [^\n]*cause=thrown',
                rf'\[hat\][^\n]* stores spilled by={a} cause=(?:turbulence|impact)',
                rf'\[hat\][^\n]* pickup cause=grounded [^\n]*owner={a}\b',
                rf'\[headwear\][^\n]*uuid={a}[^\n]* received loose accessory',
                rf'\[hat\][^\n]* capsized cause=turbulence value=[^\n]*falling=(?:true|false)[^\n]*owner={falling}\b',
                rf'\[hat\][^\n]* pickup cause=grounded [^\n]*owner={inverted} inverted=true'],
        forbid=[r'\[body\][^\n]* invariant\b', r'\[body\][^\n]* death settled into remains',
                rf'\[hat\][^\n]* capsized cause=(?:shake|impact)\b[^\n]*owner={falling}\b',
                r'\[body\][^\n]* cause=drown'],
        verify=capsized_crew,
        readout=_HULL_READOUT + [f'yukkuri status {sel("child")} raw',
                                 'execute as @e[type=minecraft:item,distance=..48] run data get entity @s Item'])


_GC = 'hat_grounded_capsized'
pond(_GC, '落水掀翻帽子与帽子搁浅', '落水掀翻帽子与帽子搁浅',
     '浅溪边三条并排的水道。中间一条：船主带着一只子油和四份苹果坐在帽上随流漂下（不带木棍，只随水漂），帽子所在一格落下一片水把它掀翻：两名乘员被甩进水里，余粮漂散；倒扣的帽体继续漂到水浅处落底，变成帽主的头饰，她游过去碰到就戴上。'
     '一侧水道里一顶倒扣的空帽顺流漂到下游浅水，水不再托住帽体时变成一件真实头饰，帽主在干岸上。另一侧是静水湖：船主坐在帽上，头顶落下一片真实的水冲击水面，帽子先猛烈摇摆再翻覆，船主落水',
     '约 1 分钟',
     [*flow_channel(0, 4, lake=(-4,)),
      captain(dx=bank_dx(0) - 5, social_wait=100, uuid=uid(_GC, 'a')),
      yk('marisa', bank_dx(1) - 5, 1, 'child', stage='child', aquatic=True, fam=_seat_family(), uuid=uid(_GC, 'child'), **pair_ready()),
      afloat('a', lane_at(0, 1), True, sel('child'), food=4),
      yk('marisa', bank_dx(0) - 5, 4, 'inverted', aquatic=True, yaw=-90, social_wait=0, uuid=uid(_GC, 'inverted'), label='倒扣空帽的帽主', **pair_ready()),
      afloat('inverted', lane_at(4, 1), False),
      yk('marisa', bank_dx(0) - 5, -4, 'falling', aquatic=True, yaw=-90, uuid=uid(_GC, 'falling'), label='瀑流下的船主', **pair_ready()),
      afloat('falling', lane_at(-4, 4))],
     vantage=(bank_dx(0) + 12, -2, 74, 20),
     late=[block(bank_dx(0) + 1, 2, 0, 'minecraft:water'), block(bank_dx(0) + 4, 2, -4, 'minecraft:water'),
           f'execute at {sel("inverted")} run data merge entity {_HAT} {{Roll:180f,Inverted:1b,Motion:{{Phase:"INVERTED"}}}}'],
     note='中间水道的翻覆来自场景里真实落下的一格水，不是脚本改帽子状态；帽体撞上硬石时帽檐上的子油先按帽檐自身的落石判定被甩下（dismount cause=brim_hard_rock），稍后帽子才因撞击翻覆把舱内的帽主甩出（capsized cause=impact thrown=1），两名乘员各经一次真实原因落水后，帽体才搁浅成物品。帽主可能先游回去把帽子扶正，所以这条水道不要求落底时仍倒扣；倒扣空帽的搁浅看另一条水道（pickup cause=grounded inverted=true）。'
          '静水湖的帽子被落水冲翻：读 hat.float 的 trigger=turbulence、falling、value、capsizes 与 inverted，日志只记一次触发。水与地形在清场后恢复。',
     reads=_HULL_READS + [('读子油', f'yukkuri status {sel("child")} raw'), ('读倒扣空帽的帽主', f'yukkuri status {sel("inverted")} raw'),
                          ('读瀑流下的船主', f'yukkuri status {sel("falling")} raw')],
     smoke=_grounded_smoke())


def water_aftermath(window):
    """A supported landing and re-entry are valid; a rapid hull reversal or a pause restart is not."""
    grounded, escapes = {}, {}
    for line in window.splitlines():
        entity = re.search(r'\[(hat|intent|incident|action|water)\] .*?#(\d+) uuid=\S+ t=(\d+)', line)
        if entity is None:
            continue
        kind, who, tick = entity[1], entity[2], int(entity[3])
        if kind == 'hat':
            if ' entered ground from afloat ' in line:
                grounded[who] = tick
            elif ' entered afloat from ground ' in line and who in grounded and tick - grounded[who] <= 20:
                return f'hull #{who} returned afloat {tick - grounded[who]} ticks after entering ground'
        elif kind == 'incident' and ' close id=water#' in line:
            escapes.pop(who, None)
        elif kind == 'water' and (' entered at=' in line or re.search(r'phase=(?:IDLE|RECOVER) -> IMPACT ', line)):
            escapes.pop(who, None)
        elif kind == 'action' and re.search(r'outcome=INTERRUPTED why=(?:hurt|fright|command)\b', line):
            escapes.pop(who, None)
        elif kind == 'intent' and ' start id=escape_water ' in line:
            if who in escapes:
                return f'escape #{who} restarted after {tick - escapes[who]} ticks without a new water entry or interruption'
            escapes[who] = tick


def _seat_load_aftermath(window):
    """The hull was full by load (past the ordinary parent's load limit, its load warning), not by missing places, and the same
    child is set down ashore and lives."""
    refusal = re.search(r'refused id=release [^\n]*NO_ROOM[^\n]*hull_full load=(\S+) capacity=\S+ limit=(\S+)', window)
    if refusal is None:
        return 'no load-full release refusal'
    if float(refusal[1]) <= float(refusal[2]):
        return 'the logged load does not exceed her load limit'
    return water_aftermath(window)


def seat_unload_result(window):
    """The ferry's child is set down ashore and the whole family lands with her (FEATURES 水生魔理沙, 登船 and 落水救助): every own
    young's last hull event is leaving it, she wears her emptied hat again before the rescue ends, and no young of hers is logged
    lost while she ferries them (from the faller's grip to the rescue's end)."""
    problem = _seat_load_aftermath(window)
    if problem:
        return problem
    parent, faller = _uuid_text('rescue_no_seat', 'a'), _uuid_text('rescue_no_seat', 'c1')
    # Every rider but the parent herself: only she is named by a fixed UUID, the crew's are drawn when they spawn.
    aboard = {}
    for event in re.finditer(r'\[hat\][^\n]* (boarded|left) rider=(\S+)', window):
        aboard[event[2]] = event[1] == 'boarded'
    stuck = sorted(rider for rider, on in aboard.items() if on and rider != parent)
    if stuck:
        return f'{len(stuck)} young of hers still ride the hull at the end: {stuck}'
    grip = re.search(rf'carry grip [^\n]*uuid={faller}', window)
    end = re.search(rf'rescue end result=DONE outcome=set_down faller={faller}', window)
    if grip is None or end is None:
        return 'no grip of the faller followed by its set-down rescue end'
    ferry = window[grip.end():end.start()]
    if not re.search(rf'\[headwear\][^\n]*uuid={parent}[^\n]* custody DEPLOYED -> WORN\b', ferry):
        return 'she did not take her emptied hat back before the rescue ended'
    if re.search(r'child separation lost=true', ferry):
        return 'a child was logged as separated and lost while she ferried the family'
    return None

_WATER_SMOKE_FORBID = [r'\[action\][^\n]* end id=swim outcome=INTERRUPTED why=(?:water_pause|escape_stop)\b']
def _water_readouts(*bodies):
    """Each named body's own status (water distress phase, aims, its hat's float detail and its incident's outcome), then the hat's riders."""
    return [f'yukkuri status {sel(b)} raw' for b in bodies] + [f'data get entity {_HAT} Riders']
_WATER_ESCAPE = [r'\[intent\][^\n]* start id=escape_water\b',
                 r'\[action\][^\n]* start id=swim owner=escape_water\b',
                 r'\[intent\][^\n]* end id=escape_water result=(?:DONE|EXHAUSTED|LOST)\b']

pond('shake_capsize', '右击晃翻帽子，船主游回扶正', '右击晃翻帽子，船主游回扶正',
     '空手反复右击水面的帽子：单击只会摇一下，三秒内三次摇晃后先剧烈摇摆再翻覆，所有乘员沿倾斜方向被甩进水里，帽子倒扣浮着（不再是容器）。随后船主慢慢游回帽边（很弱的挣扎前进），咬住帽檐按一段动作推正帽子（播咬檐推帽的扶正动作，不是挣扎），再爬回帽里；泡水伤照常扣血但不打断游、扶正与爬；两只赤油不会攀爬，只能朝岸挣扎',
     '约 1 分钟',
     [captain(dx=bank_dx(0)), *_babies(),
      afloat_now('a', water_at(0, 3), True, sel_all('crew'), food=4, stick=True)],
     note='载人帽有四份粮与木棍；日志 capsized → righted → mount cause=climb，粮与木棍各漂出一次。扶正是一段咬檐推帽动作，水伤照常扣血。读 hat.float 的 trigger、value、capsizes、inverted 与 righting。',
     reads=[('读船主', f'yukkuri status {sel("a")} raw'), ('读赤油一', f'yukkuri status {sel("k1")} raw'),
            ('读赤油二', f'yukkuri status {sel("k2")} raw'), ('读帽子', f'data get entity {_HAT}')],
     smoke=dict(ticks=1800, commands=[(tick, f'yukkuri scenario shake {_HAT}') for tick in (100, 110, 120)],
                expect=[r'\[hat\][^\n]* scenario afloat prepared [^\n]*owner_aboard=true[^\n]*young=2\b',
                        r'\[hat\][^\n]* shake player=[^\n]*count=',
                        r'\[aquatic\][^\n]* rocked hat=',
                        r'\[water\][^\n]* phase=\w+ -> RECOVER',
                        r'\[hat\][^\n]* capsized cause=shake value=3.0[^\n]*thrown=[1-9]',
                        r'\[hat\][^\n]* kit dropped cause=capsized', *_WATER_ESCAPE],
                forbid=_WATER_SMOKE_FORBID + [r'\[body\][^\n]* death settled\b', r'\[needs\][^\n]* starvation damage accepted=true'],
                verify=water_aftermath, readout=_HULL_READOUT))

pond('self_rescue', '落水后游回自己的帽子', '落水后游回自己的帽子',
     '水生魔理沙把帽子放在离岸 12 格的水面上，随后被推下岸落水：她朝自己的帽子游（一阵用力一阵歇，花体力），嘴或辫梢够到帽檐就停，咬住帽檐、按片段节拍把自己拉进帽腔坐好；在帽里不再算落水',
     '约 30 秒',
     [captain(dx=bank_dx(0)), afloat_now('a', water_at(0, 12), False)], late=[shoved('a')],
     note='长泳消耗体力；向帽子游超过 600 tick 或用尽办法才转向近岸。日志：[intent] start id=escape_water → [method] pick method=own_hat → [action] end id=swim … detail=grip → [action] start id=climb（rise、climbRise、grip）→ [body] mount cause=climb → [intent] end result=DONE。读 water.distress 的阶段，读 hat.float 看固定水线与翻覆状态。',
     reads=[('读船主', f'yukkuri status {sel("a")} raw'), ('读帽子', f'data get entity {_HAT}')],
     smoke=dict(ticks=1800, expect=[r'\[hat\][^\n]* scenario afloat prepared [^\n]*owner_aboard=false',
                                   *_WATER_ESCAPE, r'\[water\][^\n]* phase=\w+ -> RECOVER',
                                   r'\[action\][^\n]* end id=climb outcome=DONE\b',
                                   r'\[method\][^\n]* pick intent=escape_water method=(?:own_hat|right_own_hat)\b'],
                forbid=_WATER_SMOKE_FORBID, verify=water_aftermath, readout=_water_readouts('a')))

# An ordinary land Reimu just fallen into the water two blocks off the bank: nobody fetches it, it gets out by itself and, wet on dry ground, shakes the water off.
pond('shake_ashore', '落水灵梦爬上岸甩水', '落水灵梦爬上岸甩水',
     '普通灵梦刚掉进岸边两格外的池水里：自己游回岸上（[intent] end id=escape_water），在干地上停下甩水（[weather] shaking water off … after=water，播甩水片段），甩完湿度减半（shook the water off wetness=A -> A/2）；之后自然晾干，不会反复甩',
     '约 20–40 秒',
     [yk('reimu', bank_dx(0) + 2, 0, 'a', dy=-1, yaw=90, lead=True, **pair_ready())], note=NEW, vantage=(bank_dx(0) - 5, 3, -120, 15),
     reads=[('读湿度与甩水', f'yukkuri status {sel("a")} raw')],
     capture=dict(shots=(1, 40, 400, 800), sequences=((40, 800, 4),), observe=False,
                  follow='@e[tag=scn_a,limit=1]', offset=(-1.6, 1.0, 2.2)),
     smoke=dict(ticks=1200, expect=[*_WATER_ESCAPE, r'\[weather\][^\n]* shaking water off wetness=[^\n]* after=water\b',
                                   r'\[weather\][^\n]* shook the water off wetness='],
                forbid=_WATER_SMOKE_FORBID, readout=_water_readouts('a')))

def reclaim_facing_once(window):
    """The bank reclaim turns to the hat once: a turn Face has already completed is never issued again (the beckon that comes
    after it is the real proof, and a loop in front of it keeps it from starting)."""
    turns = len(re.findall(r'\[action\][^\n]* start id=face\b', window))
    if turns > 3:
        return f'the facing was issued {turns} times: a turn already completed is issued again'
    return water_aftermath(window)


pond('shore_capsized', '岸边用辫子拨回漂远的帽子', '岸边用辫子拨回漂远的帽子',
     '船主的帽子倒扣漂在离岸不到三格的静水里：她站在岸边面向帽子，侧身探出岸沿用辫梢一下一下拨水（片段的水下拨动段才推动帽子，其余时间帽子顺水面的阻力和真实水流滑行），把帽子拨到辫梢／嘴够得着的地方后咬住帽檐推正，再收回戴上；三格内不下水',
     '约 20–40 秒',
     [captain(dx=bank_dx(0)), afloat_now('a', water_at(0, 2.5), False)],
     late=[f'data merge entity {_HAT} {{Roll:180f,Inverted:1b,Motion:{{Phase:"INVERTED"}}}}'],
     note='倒扣是帽子真实的漂浮姿态（帽冠朝上），不是被测环节。日志：[action] start id=beckon … wet=false stroke=2..22/32（每周期只在这 20 tick 推动帽子）→ 帽檐被拨到够得着的地方 → '
          '[action] end id=beckon outcome=DONE detail=at_bank → [action] start id=right … push= → [hat] righted cause=reclaim → '
          '[headwear] reclaim done how=worn；三格内不下水，不出现 step_in／swim（三格外的远帽见“远处的帽子游过去扶正”）；水伤不打断，死亡、帽子离开或玩家干预会终止。',
     reads=[('读船主', f'yukkuri status {sel("a")} raw'), ('读帽子', f'data get entity {_HAT}')],
     smoke=dict(ticks=2400,
                expect=[r'\[action\][^\n]* start id=beckon[^\n]*wet=false',
                        r'\[action\][^\n]* end id=beckon outcome=DONE\b[^\n]*detail=at_bank',
                        r'\[action\][^\n]* start id=right[^\n]*push=', r'\[hat\][^\n]* righted cause=reclaim',
                        r'\[headwear\][^\n]* reclaim done how=worn',
                        r'\[intent\][^\n]* end id=resolve_own_hat result=DONE\b'],
                forbid=[r'\[action\][^\n]* start id=step_in\b', r'\[action\][^\n]* start id=swim\b',
                        r'\[headwear\][^\n]* reclaim waiting',
                        r'\[body\][^\n]* death settled into remains', r'\[body\][^\n]* cause=drown'],
                verify=reclaim_facing_once, readout=_HULL_READOUT + [f'data get entity {_HAT} Pos']))

pond('shore_capsized_far', '远处的帽子游过去扶正', '远处的帽子游过去扶正',
     '离岸 8 格的水面上，船主的帽子倒扣漂着，岸边拨水够不着（三格外）：她从岸上走进水里（普通走路跨过岸沿落水，不跳跃），游到帽檐外侧，咬住帽檐按一段片段的推帽标记把帽子推正，帽子正过来后再爬进去；扶正时播咬檐推帽的动作，泡水伤不打断',
     '约 30–60 秒',
     [captain(dx=bank_dx(0)), afloat_now('a', water_at(0, 8), False)],
     late=[f'data merge entity {_HAT} {{Roll:180f,Inverted:1b,Motion:{{Phase:"INVERTED"}}}}'],
     note='倒扣是帽子真实的漂浮姿态（帽冠朝上），不是被测环节。日志：拨水够不着（三格外）就 [action] start id=step_in（普通走路跨过岸沿落水）→ '
          '[intent] start id=escape_water（同一 reclaim 的子意图）→ [method] pick method=right_own_hat → [action] start id=swim … grip → '
          '[action] start id=right … push= → [hat] righted cause=self_rescue → [action] start id=climb → [body] mount cause=climb → [action] end id=climb outcome=DONE → [intent] end id=resolve_own_hat result=DONE；到船长座位才完成；接触后轻摆与水伤不中断；死亡、帽子离开或玩家干预会终止。',
     reads=[('读船主', f'yukkuri status {sel("a")} raw'), ('读帽子', f'data get entity {_HAT}')],
     smoke=dict(ticks=2400, expect=[r'\[action\][^\n]* start id=step_in\b',
                                   r'\[intent\][^\n]* start id=escape_water\b',
                                   r'\[action\][^\n]* start id=swim owner=drive:aquatic\b',
                                   r'\[action\][^\n]* start id=right[^\n]*push=', r'\[hat\][^\n]* righted cause=self_rescue',
                                   r'\[body\][^\n]* mount [^\n]*cause=climb',
                                   r'\[action\][^\n]* end id=climb outcome=DONE\b',
                                   r'\[intent\][^\n]* end id=resolve_own_hat result=DONE\b'],
                forbid=[*_WATER_SMOKE_FORBID, r'\[headwear\][^\n]* reclaim waiting',
                        r'\[body\][^\n]* death settled into remains'], verify=water_aftermath, readout=_HULL_READOUT))

pond('worn_hat_boat', '戴着的帽子摘下当船自救', '戴着的帽子摘下当船自救',
     '戴着帽子、已经初航过的水生魔理沙子油从岸旁水面上方落下：她先把自己的帽子摘下放到身旁的开阔水面上，帽子展开漂着，再游到帽檐、咬住爬进帽里坐好；高友善亲油只在附近面向她含泪围观，不再把她救上亲船。回到自帽后没有木棍也用辫子划水',
     '约 30–90 秒',
     [yk('marisa', bank_dx(3), 3, 'p', aquatic=True, friendliness=100, uuid=uid('worn_hat_boat', 'p'), **pair_ready()),
      yk('marisa', bank_dx(0) + 3, 0, 'c', dy=1, stage='child', aquatic=True, yaw=-90, lead=True, childhood=CHILDHOOD_SAILED,
         uuid=uid('worn_hat_boat', 'c'), kin=f'Mother:{uuid_nbt(uid("worn_hat_boat", "p"))}', **pair_ready()),
      afloat('p', water_at(3, 4), True, sel('p'))],
     vantage=(bank_dx(0) - 2, 1, -90, 18),
     note='谁会这样做由能力数据 hat_boat 决定（只有水生魔理沙），还要戴着帽子、会攀爬、已会操帽。日志：[intent] start id=escape_water → [method] pick method=hat_boat → [action] start id=place_prop … prop=hat → [hat] placed … water=true → [action] start id=climb → [body] mount cause=climb → [intent] end result=DONE；亲油记 rescue refused reason=can_sail protected=true 与 watch start，不能出现 rescue start。自帽上的后续 paddle 记 tool=braid。泡水伤照常扣血，但不打断摘帽、游与爬',
     reads=[('读子油', f'yukkuri status {sel("c")} raw'), ('读亲油', f'yukkuri status {sel("p")} raw'), ('读帽子', f'data get entity {_HAT}')],
     smoke=dict(ticks=2400, expect=[r'\[aquatic\][^\n]* rescue refused [^\n]*reason=can_sail protected=true\b',
                                   r'\[aquatic\][^\n]* watch start [^\n]*reason=can_sail\b',
                                   r'\[action\][^\n]* start id=place_prop\b', r'\[hat\][^\n]* placed [^\n]*water=true\b',
                                   r'\[action\][^\n]* start id=climb\b', r'\[body\][^\n]* mount [^\n]*cause=climb\b',
                                   r'\[action\][^\n]* start id=paddle\b[^\n]*tool=braid\b'],
                forbid=_WATER_SMOKE_FORBID + [r'\[aquatic\][^\n]* rescue start\b', r'\[intent\][^\n]* start id=rescue\b']))

pond('cargo', '帽里存粮取食，空帽打下', '帽里存粮取食，空帽打下',
     '近岸的饱腹船主帽里已有八份苹果，用手中食物右击它应提示已满，手中不扣、帽子不晃。另一只饿船主有三份苹果，会原地低头取粮吃，数量下降时帽底贴图缩小。手持食物右击放一份、潜行最多八份，只扣实际收下的数量；不同食物合计最多八份。贴图贴在帽底、少量靠船头，食物不占位也不增重。空手摇到翻覆后，余粮按种类散成水上物品，可捡起核对数量。另一顶没有乘员的空帽装着八份苹果，用左键连打：每打一下晃一下，三秒内第三下直接变成一顶可拾取的帽子、八份粮全掉出',
     '约 1 分钟',
     [yk('marisa', bank_dx(0), 0, 'a', aquatic=True, yaw=-90, lead=True, nutrition=48, energy=100, hygiene=100, label='饿船主'),
      yk('marisa', bank_dx(5), 5, 'full', aquatic=True, yaw=0, label='满粮船主', **pair_ready()),
      yk('marisa', bank_dx(-5) - 5, -5, 'empty', aquatic=True, label='空帽船主', **pair_ready()),
      afloat_now('a', water_at(0, 3), True, sel('a'), food=3),
      afloat_now('full', water_at(5, 3), True, sel('full'), food=8),
      afloat_now('empty', water_at(-5, 3), False, sel('empty'), food=8)],
     late=[f'execute at {sel("empty")} run tag {_HAT} add empty_hull'],
     give=['minecraft:apple 8', 'minecraft:bread 4'],
     note='先给近岸满粮帽放一份，看拒绝与手中余量；再给饿船主补粮，看帽底贴图换档、原地取食。读 hat.float 的 stores、tier、eats、refusals；读 Motion.Stores 核对按种类保存，重载后余量一致。'
          '日志：[hat] stores put / stores refused reason=full / stores taken use=eat → [meal] swallowed；翻覆只有一条 stores spilled，逐种核对余量；空帽左键打三下是 `pickup cause=hit_empty`，每下一条 `hit ... count=`。' + _DESIRE_NOTE_EARLY,
     reads=[('读饿船主储粮', f'yukkuri status {sel("a")} raw'), ('读满粮船主', f'yukkuri status {sel("full")} raw'),
            ('读帽子存档', f'data get entity {_HAT}')],
     smoke=dict(ticks=1200, commands=[(tick, f'execute as {sel("full")} on vehicle run yukkuri scenario shake @s') for tick in range(600, 700)]
                + [(tick, 'yukkuri scenario hit @e[tag=empty_hull,limit=1]') for tick in (70, 80, 90)],
                expect=[r'\[hat\][^\n]* stores put by=scenario [^\n]*accepted=3[^\n]*total=0 -> 3 tier=2',
                        r'\[hat\][^\n]* stores put by=scenario [^\n]*accepted=8[^\n]*total=0 -> 8 tier=3',
                        r'\[hat\][^\n]* stores taken [^\n]*use=eat [^\n]*total=3 -> 2 tier=1',
                        r'\[hat\][^\n]* stores spilled [^\n]*cause=shake [^\n]*kinds=\{minecraft:apple=8\} -> \{\} total=8 -> 0',
                        r'\[hat\][^\n]* hit player=[^\n]*count=3\b',
                        r'\[hat\][^\n]* pickup cause=hit_empty[^\n]*count=1'],
                forbid=[r'\[body\][^\n]* invariant\b'],
                readout=[f'yukkuri status {sel("a")} raw', f'yukkuri status {sel("full")} raw']))

pond('hat_crowd', '孩子长大超载翻船，亲油取舍', '孩子长大超载翻船，亲油取舍',
     '三位亲油并排，两位普通亲油（智力55、友善70）各带一只头顶赤油和一只帽檐子油，低智力高友善的一位（智力20、友善80）还带一只帽内小赤油，按舱内、裸露头顶、帽檐顺序找真实承托。北侧普通亲油的头顶孩子只长大一点，帽船进入载重警戒而不超载；中间普通亲油的孩子一下长大到超载，来不及应对。各有八份帽粮，帽旁水里另漂着八份真实苹果；孩子靠实际成长增加载重；超载只在登船、离船或成长时抽一次翻船概率。普通亲油看到帽船吃水下沉、多晃（载重警戒）就带全家划回岸边卸下，再出航只带帽船载得下的孩子、载不下的留岸；来不及靠岸而真正超载翻船时，才断绝最大的一只。低智力高友善亲油不理会警戒，照样载着全家，超载翻船后继续承认全部孩子。落水后照常自救或救幼，不能保证存活',
     '约 5 分钟，建议倍速',
     [*_overload_family('early', -6, 55, 70, False, grown=(84000, 157500)), *_overload_family('normal', 0, 55, 70, False),
      *_overload_family('kind', 6, 20, 80, True)],
     late=[f'execute as {sel("normal")} on vehicle run tag @s add seat_reload'],
     capture=dict(shots=(1, 600, 2400, 5200), sequences=((600, 5200, 200), (1400, 2600, 4))),
     note='普通亲油的头顶与帽檐乘员用于保存重载核对，另一位的帽内赤油可自行转身和走动；冒烟会卸载再加载场地区块核对第一顶帽的位置与乘员。'
          '逐只记 scenario place 的 CABIN／HEAD／BRIM；[aquatic] load changed 给出事件、载重、容量、概率、抽值和结果，静止不重抽。'
          '先看 load warning：普通亲油（北侧必然，中间只在超载没抽中翻船时）随即记 [aquatic] load heavy … reason=unload_ashore，come_ashore 的 why 中 heavy=true，靠岸卸下全家，再出航时载不下的记 set_out left_ashore（hull_full）；来不及而真正翻覆时才记 parent dismissed child，孩子仍认亲、族谱不变，亲油不再救它。另一位不理会警戒，capsized cause=too_heavy 与真实落水后记 overload keeps children。'
          '子油没有自己的木棍而推迟初航；每家的帽旁另漂着一堆八份真实苹果可捞，帽内八份翻覆后照常散出，不补粮。不强制翻船或死亡，真实碰撞、拒绝和水伤保留，逐只读存亡；整窗不得有人饿死。',
     reads=[('读普通亲油与族谱', f'yukkuri status {sel("normal")} raw'),
            ('读低智力高友善亲油', f'yukkuri status {sel("kind")} raw'),
            ('读普通亲油最大孩子', f'yukkuri status {sel("normal3")} raw'),
            ('读北侧普通亲油（只进警戒）', f'yukkuri status {sel("early")} raw'),
            ('读另一位最大孩子', f'yukkuri status {sel("kind3")} raw'),
            ('读载重帽', 'data get entity @e[tag=seat_reload,limit=1]')],
     smoke=dict(ticks=6000, reload=(60, '@e[tag=seat_reload,limit=1]'),
                expect=[r'\[hat\][^\n]* scenario afloat prepared [^\n]*young=3\b[^\n]*food=8\b',
                        r'\[hat\][^\n]* scenario place [^\n]*place=CABIN\b[\s\S]*\[hat\][^\n]* scenario place [^\n]*place=HEAD\b[\s\S]*\[hat\][^\n]* scenario place [^\n]*place=BRIM\b',
                        r'\[hat\][^\n]* load warning ratio=[\s\S]*\[hat\][^\n]* capsized cause=too_heavy\b[^\n]*thrown=[1-9]\b',
                        r'\[water\][^\n]* phase=(?:STRUGGLE|EXHAUSTED) -> RECOVER\b'],
                forbid=[r'\[body\][^\n]* invariant\b', r'\[hat\][^\n]* capsized cause=(?:shake|turbulence)\b',
                        # the early family's brim young, left ashore by the load rule, may walk up once and find the hull taken
                        r'\[action\][^\n]* uuid=(?!' + _uuid_text('hat_crowd', 'early2') + r')\S+ [^\n]* refused id=hop [^\n]*detail=no_spot',
                        r'\[needs\][^\n]* starvation damage accepted=true',
                        r'\[body\][^\n]* death settled[^\n]*cause=(?!.*(?:drown|water))'],
                verify=lambda window: _overload_family_choice(window) or _escape_needs_place(window),
                readout=[f'yukkuri status {sel(who)} raw'
                         for who in ('early', 'normal', 'kind', 'early2', 'early3', 'normal2', 'normal3', 'kind1', 'kind2', 'kind3')]
                        + ['data get entity @e[tag=seat_reload,limit=1] Riders',
                           'data get entity @e[tag=seat_reload,limit=1] Motion',
                           'data get entity @e[tag=seat_reload,limit=1] Inverted']))

pond('hat_swap', '陆地帽与水生帽并排比大小', '陆地帽与水生帽并排比大小',
     '岸上并排站着戴帽的陆地魔理沙和水生魔理沙，另有两位成体与一只子油把各自的空帽放在面前的水面上。成体水生帽是陆地帽的 1.4 倍；子油帽按自己的体型缩小；戴着的与放下的同样大小；水生空帽倒扣漂着时仍同大，陆地帽落水会下沉，不作帽船',
     '约 30 秒',
     [yk('marisa', -4, -1, 'land_worn', yaw=-90, lead=True, label='陆地·戴着', **pair_ready()),
      yk('marisa', -4, 1, 'aqua_worn', aquatic=True, yaw=-90, label='水生·戴着', **pair_ready()),
      yk('marisa', bank_dx(-3), -3, 'land_owner', yaw=-90, label='陆地·放下', **pair_ready()),
      yk('marisa', bank_dx(3), 3, 'aqua_owner', aquatic=True, yaw=-90, label='水生·放下', **pair_ready()),
      yk('marisa', bank_dx(0), 0, 'kid_owner', stage='child', aquatic=True, fam=_seat_family(), yaw=-90,
         uuid=uid('hat_swap', 'kid_owner'), label='子油·放下', **pair_ready()),
      afloat('land_owner', water_at(-3, 3), False), afloat('aqua_owner', water_at(3, 3), False),
      afloat('kid_owner', water_at(0, 2), False)],
     vantage=(0, 8, 180, 15),
     late=[f'execute at {sel("aqua_owner")} run data merge entity {_HAT} {{Roll:180f,Inverted:1b,Motion:{{Phase:"INVERTED"}}}}'],
     note='两顶成体空帽相距 6 格，各在岸边 3 格外；子油空帽在两者之间，读 [hat] placed 的 scale 与 hullBox 核对实际帽体。水生空帽倒扣漂着（倒扣是它真实的漂浮姿态，不是被测环节）。'
          '水生放帽者从岸上先招帽靠岸、再咬檐推正、最后收回戴上（日志 [action] start id=beckon → end … at_bank → start id=right … push= → [hat] righted cause=reclaim → [headwear] reclaim done）；'
          '成体招不到就直接下水游去；尚不会出航的子油只在岸边真能够到时处理帽子，否则继续日常。陆地魔理沙没有这个欲望，陆地帽落水会下沉。',
     reads=[('读水生放帽者', f'yukkuri status {sel("aqua_owner")} raw'), ('读子油放帽者', f'yukkuri status {sel("kid_owner")} raw'),
            ('读陆地放帽者', f'yukkuri status {sel("land_owner")} raw'), ('读最近的帽子', f'data get entity {_HAT}')],
     smoke=dict(ticks=1800, expect=[r'\[hat\][^\n]* placed [^\n]* scale=0\.48999998[^\n]* hullBox=AABB'],
                forbid=[r'\[body\][^\n]* invariant\b', r'\[body\][^\n]* death settled into remains'],
                readout=[f'yukkuri status {sel(who)} raw' for who in ('aqua_owner', 'kid_owner', 'land_owner', 'aqua_worn', 'land_worn')]))


# ---- daily life on the water (refactor WP6a): one aquatic goal runs one drive over real desires; no scene names a goal or an intent.
_DESIRE_NOTE = '读 aquatic.desire 看每个欲望的得分与来由、正在做的意图和刚失败的意图；[intent] start 行带 top=[前三名] 与 why=。'


def _aquatic_young(sid, slot, dx, dz, stage, parent, **kw):
    """A young of ``parent`` (kin written into NBT): a child that hops, or a crawling newborn (baby) that has to be carried."""
    extra = dict(childhood=CHILDHOOD_CRAWLING_READY) if stage == 'baby' else {}
    extra.update(kw)
    return yk('marisa', dx, dz, slot, stage=stage, aquatic=True, yaw=-90, uuid=uid(sid, slot),
              kin=f'Mother:{uuid_nbt(uid(sid, parent))}', **extra, **pair_ready())


pond('water_colony', '自然生成就坐在自己帽里', '自然生成就坐在自己帽里',
     '四只成体水生魔理沙按自然生成的方式加入世界：水边的三只第 0 tick 就坐在自己放在身旁静水上的帽子里漂着（不先站在岸上、没有延迟的上船）；离水 5 格以外的第四只没有水面可放帽，留在岸上并在日志里说明原因。帽里没有木棍和粮，她们之后在水上漂着歇息',
     '约 30 秒',
     [*[aquatic_spawn(bank_dx(dz), dz) for dz in (-4, 0, 4)], aquatic_spawn(-6, 0)],
     note='日志：[aquatic] spawn afloat hat=… own=true cause=natural，[hat] placed … water=true，[hat] boarded … own=true；第四只 [aquatic] spawn refused reason=no_water。'
          '自然生成只放帽并坐上，不另生成聚落；场景按自然生成路径调用同一钩子。' + _DESIRE_NOTE,
     reads=[('读最近的水生魔理沙', 'yukkuri status @e[type=mcyukkuri:marisa,distance=..24,limit=1,sort=nearest] raw'),
            ('读最近的帽子', f'data get entity {_HAT}')],
     smoke=dict(ticks=200, expect=[r'(?:\[aquatic\][^\n]* spawn afloat [^\n]*own=true[^\n]*cause=natural[\s\S]*?){3}',
                                   r'\[aquatic\][^\n]* spawn refused reason=no_water\b'],
                forbid=[r'\[body\][^\n]* invariant\b', r'\[hat\][^\n]* capsized [^\n]*form=cone_basin\b']))

pond('sail', '捡棍放帽出航，饿了返岸', '捡棍放帽出航，饿了返岸',
     '成体水生魔理沙在岸上，几格外有一根木棍，帽里没有粮，池塘四边的岸上各放着一个苹果：她捡起木棍收进帽里，走到有静水和船位的岸边把帽子放到水面上，跳进帽里；之后在已加载的静水上划桨（每划一下花体力）、漂着歇息。饿到一定程度、帽里和水面都没吃的而岸上有，就划回最近能靠的岸边跳上岸，再从岸边把空帽子收回戴上（木棍跟着帽子回来），上岸去吃；岸上没有她的食物时饥饿不拉她回岸',
     '约 2–3 分钟（可倍速）',
     [yk('marisa', bank_dx(0) - 5, 0, 'a', aquatic=True, yaw=-90, lead=True, nutrition=56, energy=100, hygiene=100),
      item('minecraft:stick', 1, bank_dx(0) - 3, 2, 'stick'),
      *_bank_food()],
     # On the south bank behind her first strokes north along the shore: her braid side and the stick toward the open water.
     vantage=(2, 6, 180, 15),
     capture=dict(shots=(1, 40, 200, 600), sequences=((220, 420, 2),)),
     note='每阶段一条日志：[headwear] kit stowed → [aquatic] berth → [hat] placed … water=true kit=… → [hat] boarded → [intent] start id=cruise → '
          '[aquatic] route purpose=cruise → [intent] start id=come_ashore（why 中 hunger、food_aboard=false、land_food=true）→ [aquatic] route purpose=come_ashore → [hat] left → '
          '[action] end id=take_prop outcome=DONE。取到棍后木棍始终握在辫梢（划桨、坐着、待机都不松手），读 aquatic.stick_in_braid=true，帽内腔不再另画一根。上岸后饥饿仍压住出航欲望，不会马上再上船。' + _DESIRE_NOTE,
     reads=[('读船主', f'yukkuri status {sel("a")} raw'), ('读帽子', f'data get entity {_HAT}')],
     smoke=dict(ticks=3600,
                expect=[r'\[intent\][^\n]* start id=set_out\b',
                                    r'\[hat\][^\n]* placed [^\n]*water=true kit=minecraft:stick\b',
                                    r'\[body\][^\n]* mount [^\n]*cause=hop_contact\b',
                                    r'\[intent\][^\n]* end id=set_out result=DONE\b',
                                    r'\[status\][^\n]* aquatic\.stick_in_braid: false -> true\b',
                                    r'\[intent\][^\n]* end id=come_ashore result=DONE\b',
                                    # Ordinary paddling in calm water must reach cruise without capsizing.
                                    r'\A(?:(?!\[hat\][^\n]* capsized [^\n]*form=cone_basin\b)[\s\S])*\[intent\][^\n]* start id=cruise\b'],
                forbid=[r'\[body\][^\n]* invariant\b', r'\[hat\][^\n]* pickup cause=grounded',
                        r'\[body\][^\n]* death settled into remains'], readout=_HULL_READOUT))

def bank_boarding_result(window):
    """After the parent boards her hull the young is lifted from the bank into its seat: no hop ashore and no blocked step in
    between, and the set-out completes with it aboard."""
    board = re.search(r'\[hat\][^\n]* boarded rider=\S+[^\n]*own=true', window)
    if board is None:
        return 'the parent never boarded her own hull'
    after = window[board.end():]
    end = re.search(r'\[aquatic\][^\n]* set_out complete', after)
    if end is None:
        return 'set_out never completed after the boarding'
    between = after[:end.start()]
    ashore = re.search(r'\[action\][^\n]* start id=hop owner=[^\n]* bank=', between)
    if ashore:
        return 'a hop ashore was taken between boarding and the seat: ' + ashore[0][-90:]
    if 'NO_PROGRESS' in between:
        return 'a step ended BLOCKED(NO_PROGRESS) between boarding and the seat'
    return water_aftermath(window)


def _to_water_chain(sid, may_hop=False):
    """Reunion, then the lift to the water, the set-down at the berth, the hat, the lift aboard and the launch: one parent, one child, in order.

    ``may_hop``: a young left alone for a while teaches itself the hop before the parent reaches it, and then boards as a guest by
    itself instead of being carried; the reunion, set_out, the placed hat and the full launch stay required either way."""
    a, c = _uuid_text(sid, 'a'), _uuid_text(sid, 'c1')
    carried = (rf'\[aquatic\][^\n]*uuid={a}[^\n]* set_out carry child={c} purpose=to_water\b'
               rf'[\s\S]*\[family\][^\n]*uuid={a}[^\n]* carry grip [^\n]*uuid={c}'
               rf'[\s\S]*\[action\][^\n]*uuid={a}[^\n]* end id=release outcome=DONE'
               rf'[\s\S]*\[hat\][^\n]* placed owner={a}\b[^\n]*water=true'
               rf'[\s\S]*\[family\][^\n]*uuid={a}[^\n]* carry grip [^\n]*uuid={c}[^\n]*hold=braid')
    hopped = (rf'\[hat\][^\n]* placed owner={a}\b[^\n]*water=true'
              rf'[\s\S]*\[hat\][^\n]* boarded rider={c} own=false\b')
    return (rf'\[family\][^\n]*uuid={a}[^\n]* child search reunited child={c}\b'
            rf'[\s\S]*\[intent\][^\n]*uuid={a}[^\n]* start id=set_out\b'
            rf'[\s\S]*' + (rf'(?:{carried}|{hopped})' if may_hop else carried)
            + rf'[\s\S]*\[aquatic\][^\n]*uuid={a}[^\n]* set_out complete [^\n]*aboard=1/1\b')


_FED_NEWBORN = 'Version:1,Newborn:1b,Landed:1b,FirstMealDone:1b,FirstToiletDone:1b,Crawled:0b,Jumped:0b'
pond('afloat_family', '亲油用辫子抱赤油上帽船', '亲油用辫子抱赤油上帽船',
     '亲油与一只已完成首餐、首便、正在学爬且不会跳的赤油在岸边，附近有木棍：亲油取棍、集合、放帽，先跳上近侧帽檐，用辫子抓起岸边赤油并降低到前座，最后自己到船尾座位；两只实际入座后出航',
     '约 1–2 分钟',
     [yk('marisa', bank_dx(0) - 1, 0, 'a', aquatic=True, yaw=-90, lead=True, uuid=uid('afloat_family', 'a'), **pair_ready()),
      _aquatic_young('afloat_family', 'c1', bank_dx(1), 1, 'baby', 'a', childhood=_FED_NEWBORN),
      item('minecraft:stick', 1, bank_dx(0) - 1, -1, 'stick')],
     note='日志：set_out → placed → 亲油 mount → grab reach=braid → carry grip → carry lowering → 赤油 seat landed → 亲油 hop 到座位 → set_out complete aboard=1/1。' + _DESIRE_NOTE,
     vantage=(bank_dx(0) - 4, 4, -135, 18),
     capture=dict(shots=(1, 40, 2400), sequences=((40, 2400, 4),), observe=False,
                  views={'port': dict(follow='@e[type=mcyukkuri:placed_hat,limit=1]', offset=(1.15, .55, -.75)),
                         'starboard': dict(follow='@e[type=mcyukkuri:placed_hat,limit=1]', offset=(-1.15, .55, .75))}),
     reads=[('读亲油', f'yukkuri status {sel("a")} raw'), ('读赤油', f'yukkuri status {sel("c1")} raw'),
            ('读帽子', f'data get entity {_HAT}')],
     smoke=dict(ticks=3600, expect=[r'\[intent\][^\n]* start id=set_out\b', r'\[intent\][^\n]* end id=set_out result=DONE\b',
                                   r'\[aquatic\][^\n]* set_out complete [^\n]*aboard=1/1\b',
                                   r'\[family\][^\n]* carry grip [^\n]*hold=braid',
                                   r'\[body\][^\n]* seat landed [^\n]*cause=braid_set_down'],
                forbid=[r'death settled into remains', r'\[body\][^\n]* invariant\b', r'\[aquatic\][^\n]* set_out left_ashore=',
                        r'\[intent\][^\n]* start id=set_out\b[\s\S]*\[intent\][^\n]* start id=set_out\b',
                        r'\[action\][^\n]* start id=take_prop\b', r'\[intent\][^\n]* end id=set_out result=EXHAUSTED\b',
                        r'\[hat\][^\n]* pickup cause=grounded'],
                verify=bank_boarding_result,
                readout=_HULL_READOUT + [f'yukkuri status {sel("c1")} raw']))

pond('afloat_family_gather', '内陆赤油爬不来，亲油抱去上船', '内陆赤油爬不来，亲油抱去上船',
     '亲油在南岸泊位旁，赤油在岸边内陆、来不及在集合期爬到：亲油先走到泊位呼叫，再走近抱回赤油，在泊位放下后放帽、跳上近侧帽檐把它托入前座，最后自己到船长座位；帽子漂远时先招帽',
     '约 1–2 分钟',
     [yk('marisa', bank_dx(0) - 1, 0, 'a', aquatic=True, yaw=-90, lead=True, uuid=uid('afloat_family_gather', 'a'), **pair_ready()),
      _aquatic_young('afloat_family_gather', 'c1', bank_dx(-6) - 16, -6, 'baby', 'a', childhood=_FED_NEWBORN),
      item('minecraft:stick', 1, bank_dx(0) - 1, -1, 'stick')],
     note='日志先有 set_out gather start、set_out carry … purpose=to_water、carry grip、gathered、release DONE，再有 placed、亲油 mount、carry grip、seat landed、亲油 hop 与 set_out complete aboard=1/1；集合超时不丢下爬不来的孩子。' + _DESIRE_NOTE,
     reads=[('读亲油', f'yukkuri status {sel("a")} raw'), ('读赤油', f'yukkuri status {sel("c1")} raw'),
            ('读帽子', f'data get entity {_HAT}')],
     smoke=dict(ticks=3600, expect=[r'\[aquatic\][^\n]* set_out gather start\b', r'\[aquatic\][^\n]* set_out gathered\b',
                                   r'\[aquatic\][^\n]* set_out carry [^\n]*purpose=to_water\b[\s\S]*\[family\][^\n]* carry grip [\s\S]*\[action\][^\n]* end id=release outcome=DONE[\s\S]*\[hat\][^\n]* placed [^\n]*water=true',
                                   r'\[intent\][^\n]* end id=set_out result=DONE\b',
                                   r'\[aquatic\][^\n]* set_out complete [^\n]*aboard=1/1\b',
                                   r'\[family\][^\n]* carry grip [^\n]*hold=braid',
                                   r'\[body\][^\n]* seat landed [^\n]*cause=braid_set_down'],
                forbid=[r'death settled into remains', r'\[body\][^\n]* invariant\b', r'\[aquatic\][^\n]* set_out left_ashore=',
                        r'\[intent\][^\n]* start id=set_out\b[\s\S]*\[intent\][^\n]* start id=set_out\b',
                        r'\[action\][^\n]* start id=take_prop\b', r'\[intent\][^\n]* end id=set_out result=EXHAUSTED\b'],
                verify=bank_boarding_result))

pond('afloat_stick', '划去捞水面上的木棍', '划去捞水面上的木棍',
     '亲油坐在自己的帽船里漂在静水上，帽里没有木棍；几格外的水面上漂着一根真木棍。没有木棍（从没有或翻船失去是同一状态）的亲油看到水上的木棍就划过去、用辫子或嘴取到收进帽里；水上没有木棍时则不会专门去找',
     '约 1 分钟',
     [captain(dx=bank_dx(0)),
      afloat_now('a', water_at(0, 3), True),
      item('minecraft:stick', 1, bank_dx(0) + 8, 0, 'stick', dy=-.15)],
     note='日志：[intent] start id=fetch_stick（why 里 item at=）→ [aquatic] stick target chosen … afloat=true → [action] start id=paddle → [action] start id=pick → [aquatic] stick taken … stowed_in=hat；'
          '取到后 aquatic.stick_in_braid 在取到并收进帽后变 true。木棍是原版掉落物，浮在水面上；准备放帽成功不算取棍成功。' + _DESIRE_NOTE,
     reads=[('读船主', f'yukkuri status {sel("a")} raw'), ('读帽子', f'data get entity {_HAT}'),
            ('读水上物品', 'execute as @e[type=minecraft:item,distance=..48] run data get entity @s Pos')],
     smoke=dict(ticks=1200, expect=[r'\[intent\][^\n]* start id=fetch_stick\b', r'\[aquatic\][^\n]* stick target chosen [^\n]*afloat=true',
                                   r'\[aquatic\][^\n]* stick taken [^\n]*stowed_in=hat'],
                forbid=[r'\[body\][^\n]* invariant\b', r'\[hat\][^\n]* capsized\b', r'\[body\][^\n]* death settled into remains',
                        r'\[action\][^\n]* start id=place_prop\b', r'prop_not_worn']))

def kid_play_shelter(window):
    """A free head after the cabin refusal must lead to a real parental lift and head placement."""
    for request in re.finditer(r'uuid=(\S+)[^\n]* play stays on brim [^\n]*cabin=none head=free reason=fear:thunder', window):
        child = request[1]
        after = window[request.end():]
        if not re.search(rf'carry lowering [^\n]*child={child}[^\n]*clip=braid_head_set'
                         rf'[\s\S]*uuid={child}[^\n]* seat landed [^\n]*place=HEAD cause=braid_set_down'
                         rf'[\s\S]*uuid={child}[^\n]* play kind=kid_hide_behind start spot=head reason=fear:thunder', after):
            return f'frightened brim child {child} was not lifted to the free head and sheltered there'


pond('kid_boat_play', '赤油在帽檐玩耍，打雷躲避', '赤油在帽檐玩耍，打雷躲避',
     '静水亲帽停在离岸三格的水面，亲油坐在船长位置面朝船首，帽里有一根木棍和四份苹果；两只赤油都已学会短跳（会玩才会探头），一在舱内、一在头顶，都已吃饱。静水里帽子只轻摆起伏，不翻。'
     '舱内那只自己跳上帽檐，辫子朝外对着水面拍水（亲油划桨时改成学划水）；头顶那只只做不碰水的探头。'
     '第 13 秒打雷：舱内有空位就跳回舱内缩身，舱内无位而头顶有位时由亲油辫子托上头顶，两处都无位才留在帽檐上抓紧缩身；平静后继续玩耍。空手右击赤油看问候',
     '约 2 分钟；用天气按钮可再看受惊',
     [yk('marisa', bank_dx(0), 0, 'a', aquatic=True, yaw=-90, lead=True, friendliness=100,
         uuid=uid('kid_boat_play', 'a'), **pair_ready()),
      _aquatic_young('kid_boat_play', 'c1', bank_dx(1), 1, 'baby', 'a', childhood=CHILDHOOD_HOPPING, also=('crew',)),
      _aquatic_young('kid_boat_play', 'c2', bank_dx(2), 2, 'baby', 'a', childhood=CHILDHOOD_HOPPING, also=('crew',)),
      afloat_now('a', water_at(0, 3), True, sel_all('crew'), food=4, stick=True)],
     note='日志：舱内赤油自己 hop 上帽檐 → [aquatic] play kind=kid_brim_splash start spot=brim reason=brim_water braid_out=true（亲油在划桨时是 kind=kid_mimic_paddle reason=captain_paddle，两者都是水上动作）'
          '→ 之后 kind=hat_cradle 原地静玩；头顶那只 play kind=hat_cradle start spot=head reason=head_dry（静坐，不回头转身；boat_rim_peek 的 60° 回望只在帽檐/舱内），不做水上动作。'
          '两只都已学会短跳：新生油不能玩耍，头顶那只不会探头。'
          '受惊：舱内有空位时 play return inside place=cabin reason=fear:thunder，再 play kind=kid_hide_behind start spot=cabin；舱内无位且 head=free 时先在帽檐缩身，亲油 shelter_young → carry lowering clip=braid_head_set → seat landed place=HEAD，随后在头顶缩身。'
          '舱内、头顶都无位才一直在帽檐缩身；平静后继续玩耍。帽檐上真被晃到硬翻时仍会摔进水里（play fall_in）。'
          '读 hat.float 的 play：各类演出开始次数、fear_* 原因次数；body.attach 看实际帽内坐标。点击赤油与点击空帽檐不同：前者问候，后者摇船；准备入座不算行为成功。' + _DESIRE_NOTE,
     reads=[('读舱内赤油动作与点击', f'yukkuri status {sel("c1")} raw'), ('读头顶赤油', f'yukkuri status {sel("c2")} raw'),
            ('读演出与受惊计数', f'yukkuri status {sel("a")} raw'), ('读帽子乘员', f'data get entity {_HAT} Riders')],
     capture=dict(shots=(1, 40, 2400), sequences=((40, 2400, 4),), observe=False,
                  views={'port': dict(follow='@e[type=mcyukkuri:placed_hat,limit=1]', offset=(1.15, .55, -.75)),
                         'starboard': dict(follow='@e[type=mcyukkuri:placed_hat,limit=1]', offset=(-1.15, .55, .75))}),
     smoke=dict(ticks=2400, commands=[(260, 'weather thunder'), (520, 'weather clear')],
                expect=[r'\[hat\][^\n]* scenario afloat prepared [^\n]*owner_aboard=true[^\n]*young=2\b',
                        r'\[hat\][^\n]* scenario place [^\n]*place=CABIN\b',
                        r'\[hat\][^\n]* scenario place [^\n]*place=HEAD\b',
                        r'\[aquatic\][^\n]* play kind=(?:kid_brim_splash|kid_mimic_paddle) start spot=brim [^\n]*braid_out=true\b',
                        r'\[aquatic\][^\n]* play kind=hat_cradle start spot=head reason=head_dry\b',
                        r'\[aquatic\][^\n]* play kind=kid_hide_behind start spot=\w+ reason=fear:thunder\b',
                        r'\[aquatic\][^\n]*(?: play (?:return inside [^\n]*place=cabin|stays on brim [^\n]*cabin=none head=none) [^\n]*reason=fear:thunder\b| care transfer end kind=shelter_young [^\n]*result=DONE released=true [^\n]*head=true)'],
                forbid=[r'\[body\][^\n]* invariant\b', r'\[body\][^\n]* death settled into remains', r'\[aquatic\][^\n]* play fall_in\b',
                        r'\[hat\][^\n]* capsized\b',
                        r'\[aquatic\][^\n]* play kind=(?:kid_brim_splash|kid_mimic_paddle) start spot=head\b',
                        r'\[aquatic\][^\n]* play kind=kid_hide_behind end result=(?:EXHAUSTED|LOST)\b'],
                verify=kid_play_shelter,
                readout=[f'yukkuri status {sel("a")} raw', f'yukkuri status {sel("c1")} raw',
                         f'yukkuri status {sel("c2")} raw', f'data get entity {_HAT} Riders']))

def boat_toilet_result(window):
    """Every stool leaves the body's own rear (the release point is the drawn stool, inside the rim for the hollow), really comes down
    in the water outside the rim when the clip ends, and the young goes back into the hollow afterwards."""
    drops = re.findall(r'uuid=(\S+)[^\n]* boat_toilet dropped hat=\S+ place=(\w+) at=[^\n]*? release_distance=([\d.]+)/([\d.]+)'
                       r' push=([\d.]+) lands=[^\n]*? land_distance=([\d.]+)/[\d.]+', window)
    if len({d[0] for d in drops}) < 2:
        return f'expected the owner and the young to relieve themselves, got {len(drops)} drop(s)'
    landed = {who: (float(at), float(rim), wet == 'true')
              for who, at, rim, wet in re.findall(r'uuid=(\S+)[^\n]* boat_toilet stool hat=\S+ at=[^\n]*? hull_distance=([\d.]+)/([\d.]+)'
                                                  r' in_water=(\w+)', window)}
    for who, place, release, rim, push, plan in drops:
        if float(plan) < float(rim):
            return f'{who} planned its stool to come down inside the rim ({plan} < {rim})'
        if who not in landed:
            return f'{who} released a stool that was never seen after the clip'
        at, edge, wet = landed[who]
        # The real item, not the plan: where vanilla item motion actually took it by the clip's end.
        if at < edge or not wet:
            return f'{who} stool ended at {at:.2f} of rim {edge:.2f} in_water={wet}'
        # From the hollow the rear is well inside the rim: the stool has to fly out, never be placed outside.
        if place == 'cabin' and not (float(release) < float(rim) and float(push) > 0):
            return f'{who} released at {release} of rim {rim} with push {push}: not from the body in the hollow'
    if not any(place == 'brim' for _, place, *_ in drops):
        return 'the young never relieved itself on the brim'
    if not any(place == 'cabin' for _, place, *_ in drops):
        return 'the owner never relieved herself from the hollow'
    if not re.search(r'\[aquatic\][^\n]* boat_toilet back [^\n]*place=cabin', window):
        return 'the young stayed on the brim after relieving itself'
    return None


pond('boat_toilet', '帽船上朝帽外排便', '帽船上朝帽外排便',
     '亲油坐在静水上的帽船里，帽里有一只会跳的赤油，两只的膀胱都已很满。亲油在舱里转身，把屁股朝向帽外的水面撅起来排便，粪便落进帽外的水里；赤油先跳到亲油前方的帽檐上，转身朝外撅起屁股排便，之后回到舱里。',
     '约 1 分钟',
     [captain(dx=bank_dx(0), bladder=92, uuid=uid('boat_toilet', 'a')),
      _aquatic_young('boat_toilet', 'c1', bank_dx(1), 1, 'baby', 'a', childhood=CHILDHOOD_HOPPING, bladder=90),
      afloat_now('a', water_at(0, 3), True, sel('c1'), food=4, stick=True)],
     note='日志：[aquatic] boat_toilet turn（rear_out 是朝水的方向）→ boat_toilet dropped place=cabin|brim at=… release_distance=出粪点/船沿（就是画面上屁股排出的位置，舱内 < 船沿）push=推出速度 lands=… land_distance=落水点/船沿 air_ticks=飞行 tick'
          ' → boat_toilet stool at=… hull_distance=动作结束时粪便的实际位置/船沿 in_water=true（≥ 船沿且在水里才是落到帽外水中）→ boat_toilet back place=cabin → [needs] relieved source=boat bladder=前 -> 后；'
          '赤油先有 hop 上帽檐，place=brim，排完再跳回舱内。读 hat 乘员与膀胱数值。粪便是真实掉落物，落点在水里；准备入座不算行为成功。' + _DESIRE_NOTE,
     reads=[('读亲油', f'yukkuri status {sel("a")} raw'), ('读子油', f'yukkuri status {sel("c1")} raw'), ('读帽子乘员', f'data get entity {_HAT} Riders'),
            ('读水上物品', 'execute as @e[type=minecraft:item,distance=..48] run data get entity @s Pos')],
     # Side views of the hull: the owner raises her rear and the stool flies over the stern rim (ticks 1-40); the young does the same on
     # the bow brim (about 40-80); one frame a tick so the few-tick flights are seen. The wide view goes first (the first view's opening
     # frames can stall while the client loads); port shows her left profile, bow looks from off the bow quarter at the young's hop out,
     # its raised rear and its stool.
     capture=dict(shots=(1, 200), sequences=((2, 110, 1),), observe=False,
                  views={'wide': dict(follow='@e[type=mcyukkuri:placed_hat,limit=1]', offset=(3.2, 1.3, 0)),
                         'port': dict(follow='@e[type=mcyukkuri:placed_hat,limit=1]', offset=(1.5, .55, 0)),
                         'bow': dict(follow='@e[type=mcyukkuri:placed_hat,limit=1]', offset=(1.1, .45, 1.5))}),
     smoke=dict(ticks=1800, expect=[r'\[hat\][^\n]* scenario afloat prepared [^\n]*owner_aboard=true[^\n]*young=1\b',
                                   r'\[aquatic\][^\n]* boat_toilet turn [^\n]*place=brim',
                                   r'\[aquatic\][^\n]* boat_toilet dropped [^\n]*place=brim',
                                   r'\[needs\][^\n]* relieved source=boat\b'],
                forbid=[r'\[body\][^\n]* invariant\b', r'\[body\][^\n]* death settled', r'\[hat\][^\n]* capsized\b',
                        r'\[aquatic\][^\n]* boat_toilet refused\b'],
                verify=boat_toilet_result,
                readout=[f'yukkuri status {sel("a")} raw', f'yukkuri status {sel("c1")} raw', f'data get entity {_HAT} Riders']))

pond('shore_family', '饿了带孩子划回岸边', '饿了带孩子划回岸边',
     '亲油和一只小子油在离岸 3 格的帽子上，帽里有木棍、没有粮，水上也没有吃的，岸边草地上放着一个苹果；亲油已经饿了：她划回岸边；孩子坐在她头顶随她上岸，辫子把它放到干地，亲油随后把空帽子收回戴上，走去吃那个苹果，不往内陆游荡找草（她不吃陆地草）',
     '约 1 分钟',
     [yk('marisa', bank_dx(0), 0, 'a', aquatic=True, yaw=-90, lead=True, nutrition=35, energy=100, hygiene=100),
      item('minecraft:apple', 1, bank_dx(0) - 7, 0, 'food'),
      yk('marisa', bank_dx(1), 1, 'c1', aquatic=True, fam=_seat_family(), childhood=CHILDHOOD_CRAWLING_READY, yaw=-90, **pair_ready()),
      afloat_now('a', water_at(0, 3), True, sel('c1'), stick=True)],
     note='饥饿只在岸上有她的食物时才拉她回岸（come_ashore 的 why 中 land_food=true）。日志 come_ashore → paddle → 亲油 hop（孩子坐在头顶随她一起离帽）→ 上岸后辫子 carry put down reason=release / release DONE 把孩子放到干地 → take_prop → [meal] swallowed item=minecraft:apple。'
          '舱里放不下的最大一只坐头顶（用户 2026-11-05 的排座规则），头顶的孩子由亲油带着走、落地后由辫子放下，不自己跳；观察孩子与亲油都真实离帽，船长最后收帽。' + _DESIRE_NOTE,
     reads=[('读亲油', f'yukkuri status {sel("a")} raw'), ('读子油', f'yukkuri status {sel("c1")} raw'), ('读帽子', f'data get entity {_HAT}')],
     capture=dict(shots=(1, 40, 600), sequences=((170, 260, 3),)),
     smoke=dict(ticks=1800, expect=[r'\[hat\][^\n]* scenario afloat prepared [^\n]*young=1\b',
                                   r'\[family\][^\n]* carry put down [^\n]*reason=release\b',
                                   r'\[action\][^\n]* end id=release outcome=DONE\b',
                                   r'\[action\][^\n]* end id=take_prop outcome=DONE\b',
                                   r'\[intent\][^\n]* end id=come_ashore result=DONE\b',
                                   r'\[intent\][^\n]* start id=come_ashore [^\n]*land_food=true\b',
                                   r'\[meal\][^\n]* swallowed item=minecraft:apple\b'],
                forbid=[r'\[body\][^\n]* invariant\b', r'\[intent\][^\n]* end id=come_ashore result=EXHAUSTED\b',
                        r'\[forage\][^\n]* food search started\b'],
                readout=[f'yukkuri status {sel("a")} raw', f'yukkuri status {sel("c1")} raw']))

# ---- food, care, rescue and birth on the hat boat (refactor WP6b): real starting states only; who helps and when is the helpers' own decision.
_INCIDENT_NOTE = ('每次落水由落水者自己开一个事件（[incident] open id=water#…），近旁的亲油各自只决定一次救不救（[incident] decide … chance= roll= accepted=）；'
                  '不能自救的孩子亲油必定接下（chance=1），会操帽的孩子一律不救（chance=0，含泪围观），落水者上船、被叼出水、上岸或死亡时关闭（[incident] close … reason= decided=[…]）。'
                  '读 aquatic.rescue 看这件事与每个救助者的回答、那一次运行的结果（如 accepted/DONE rescued）。')


def _kin_young(sid, slot, dx, dz, stage, mother, father, **kw):
    """A young of both parents (kin written into NBT), tagged ``kids``."""
    extra = dict(childhood=CHILDHOOD_CRAWLING_READY) if stage == 'baby' else {}
    return yk('marisa', dx, dz, slot, stage=stage, aquatic=True, yaw=-90, uuid=uid(sid, slot), also=('kids',),
              kin=f'Mother:{uuid_nbt(uid(sid, mother))},Father:{uuid_nbt(uid(sid, father))}', **extra, **pair_ready(), **kw)


RESCUE_BABY_DELAY = 190     # ticks the infant waits on the bank, so the first rescue is over before the second fall


def _rescue_smoke(sid, slot):
    """The same child's rescue chain: lifted by the braid and lowered into a seat aboard, or (when she stands on the bank and it is
    within a hold's reach of a dry stand, FEATURES 落水救助) taken out over the bank and set down ashore."""
    child, parent = _uuid_text(sid, slot), _uuid_text(sid, 'a')
    return [rf'\[incident\][^\n]* open id=water#[^\n]*subject={child}',
            rf'\A(?=[\s\S]*?\[incident\][^\n]* open id=(?P<incident>water#\S+) [^\n]*subject={child})'
            rf'[\s\S]*\[incident\][^\n]* decide id=(?P=incident) helper={parent} requester={child} [^\n]*accepted=true'
            rf'[\s\S]*rescue start faller={child} incident=(?P=incident)\b'
            rf'[\s\S]*\[action\][^\n]* start id=grab [^\n]*target={child}'
            rf'[\s\S]*\[family\][^\n]* carry grip [^\n]*uuid={child}'
            rf'[\s\S]*\[action\][^\n]* start id=release [^\n]*child={child}'
            rf'(?:[\s\S]*\[body\][^\n]* uuid={child} [^\n]* seat landed '
            rf'[\s\S]*rescue end result=DONE outcome=rescued faller={child} incident=(?P=incident) aboard=true'
            rf'|[\s\S]*rescue end result=DONE outcome=set_down faller={child} incident=(?P=incident) aboard=false)']


pond('rescue_child', '亲油用辫子救起落水的子油和赤油', '亲油用辫子救起落水的子油和赤油',
     '亲油坐在近岸的自己的帽船里，帽里有木棍。先有一只 0.35 体型、未学会跳的小子油从岸边落水：亲油接下救援后连续划近，转身伸辫，抓住孩子提出水面，提过帽檐再降低到帽内净空。救完后岸边的一只赤油也被推下水，亲油同样划近救起，降低到前座；她这时若已在岸上，赤油又在岸边站位的辫子够得着处（辫长约 0.6 格，沉到池底的够不着），就直接从岸边捞出水面放到干地，否则跳进泊在岸边的帽船划过去救。真实阻挡仍会失败，孩子自行上岸不算救起',
     '约 1–3 分钟',
     [yk('marisa', bank_dx(0), 0, 'a', aquatic=True, yaw=-90, lead=True, friendliness=100, uuid=uid('rescue_child', 'a'), **pair_ready()),
      _aquatic_young('rescue_child', 'c1', bank_dx(1), 1, 'child', 'a', fam=_seat_family(), childhood=CHILDHOOD_CRAWLING_READY),
      _aquatic_young('rescue_child', 'k1', bank_dx(-1), -1, 'baby', 'a'),
      afloat_now('a', water_at(0, 3), True, sel('a'), stick=True)],
     late=[shoved('c1', .5), Retry(shoved('k1', .5), sel('k1'), tries=1, first=RESCUE_BABY_DELAY)], vantage=(bank_dx(0) - 2, 1, -90, 18),
     note=_INCIDENT_NOTE + '0.35 子油按真实身体净空与载重找落点。同一孩子 UUID：rescue start → grab reach=braid → carry grip → release → seat landed → rescue end result=DONE outcome=rescued aboard=true；self_climbed 只算自行脱险。赤油在子油救完之后才被推下水，走同一条链；亲油那时若已站在岸上、赤油在岸边站位够得着，则 grab → carry grip → release（放到干地）→ rescue end result=DONE outcome=set_down aboard=false，不再 start id=launch。' + _DESIRE_NOTE,
     reads=[('读亲油', f'yukkuri status {sel("a")} raw'), ('读子油', f'yukkuri status {sel("c1")} raw'),
            ('读赤油', f'yukkuri status {sel("k1")} raw'), ('读帽子', f'data get entity {_HAT}')],
     capture=dict(shots=(1, 40, 1800), sequences=((40, 1800, 4),), observe=False,
                  views={'': dict(follow='@e[type=mcyukkuri:placed_hat,limit=1]', offset=(-1.5, 1.4, 1.5)),
                         'parent': dict(follow='@e[tag=scn_a,limit=1]', offset=(-1.5, 1.1, 1.4))}),
     smoke=dict(ticks=RESCUE_BABY_DELAY + 1800, expect=[r'\[hat\][^\n]* scenario afloat prepared [^\n]*owner_aboard=true',
                                   r'\[water\][^\n]* phase=\w+ -> RECOVER',
                                   *_rescue_smoke('rescue_child', 'c1'), *_rescue_smoke('rescue_child', 'k1')],
                forbid=_WATER_SMOKE_FORBID, verify=water_aftermath, readout=_water_readouts('a', 'c1', 'k1')))

pond('afloat_family_ashore', '回岸寻回远处赤油再出航', '回岸寻回远处赤油再出航',
     '亲油坐在近岸的自己的帽船里，帽里有木棍；赤油独自在南岸内陆近十八格外的岸上（落水后自己爬上岸又走远），超出普通水上亲子范围、仍在陆地搜寻范围内：亲油不划开，划回近岸上岸、取回帽子，再接着做家庭搜寻与出航（走到赤油身边打招呼，抱不动的抱起带去水边放下，已学会跳的跟着走，然后放帽、上船）',
     '约 2–3 分钟',
     [_aquatic_young('afloat_family_ashore', 'c1', -12, 13, 'baby', 'a', childhood=_FED_NEWBORN)],
     late=[yk('marisa', bank_dx(0), 0, 'a', aquatic=True, yaw=-90, lead=True, uuid=uid('afloat_family_ashore', 'a'), **pair_ready()),
           afloat('a', water_at(0, 3), True, sel('a'), stick=True)],
     vantage=(bank_dx(0) - 2, 1, -90, 18),
     note='日志顺序：[intent] start id=come_ashore（why 里 young_ashore=1，赤油在 16 格外也算）→ end id=come_ashore result=DONE（上岸并取回帽子）→ family search started／child search reunited → start id=set_out；重逢后不得再来回搜寻（child search reunited 只出现一次）；之后抱起带到泊位放下（set_out carry … purpose=to_water、carry grip、release DONE），放帽，辫托上帽，set_out complete aboard=1/1。亲油途中不划去闲游；上岸落地失败后重来是登陆动作自己的事。上岸这段时间里赤油可能已学会跳，那就不必抱。准备成功不算行为成功。' + _DESIRE_NOTE,
     reads=[('读亲油', f'yukkuri status {sel("a")} raw'), ('读赤油', f'yukkuri status {sel("c1")} raw')],
     smoke=dict(ticks=3600, expect=[rf'\[intent\][^\n]*uuid={_uuid_text("afloat_family_ashore", "a")}[^\n]* start id=come_ashore [^\n]*young_ashore=1\b'
                                   rf'[\s\S]*\[intent\][^\n]*uuid={_uuid_text("afloat_family_ashore", "a")}[^\n]* end id=come_ashore result=DONE\b'
                                   rf'[\s\S]*\[intent\][^\n]*uuid={_uuid_text("afloat_family_ashore", "a")}[^\n]* start id=set_out\b',
                                   r'\[family\][^\n]* family search started\b',
                                   _to_water_chain('afloat_family_ashore', may_hop=True)],
                forbid=[r'child search reunited [\s\S]*child search reunited ', r'\[body\][^\n]* invariant\b', r'death settled into remains']))

pond('rescue_no_seat', '满载亲帽救起新生儿送上岸', '满载亲帽救起新生儿送上岸',
     '1.0 体型亲油的帽船载着四只自己的孩子：0.10 舱内赤油、0.19 和 0.285 幼体在头顶、0.28 会跳的子油在帽檐，载重 0.0530，在普通亲油的载重警戒线 0.0540（容量 0.0635 的 85%）之下，只差一只新生赤油的重量。尚未学爬的 0.19 体型新生赤油在离岸约九格的开阔水面上漂着，离最近的干岸七格开外，自己爬不出去；亲油划近后帽内、头顶、帽檐虽各有空位，普通亲油的载重规则不接受再多一只（0.0530+0.0069 越过警戒线），按满船处理：辫子托着它不放，把整只帽船划到岸边，靠岸后船停稳，帽檐上的子油先自己跳上岸，亲油再嘴叼起舱内赤油，带着辫中、嘴里和头顶的孩子经帽檐跳上岸，逐只放到干地，最后收回空帽，全家都在岸上。必须看见真实接起、离船和放下，孩子自己上岸不算救起',
     '约 30–90 秒',
     [],
     late=[yk('marisa', bank_dx(0), 0, 'a', aquatic=True, yaw=90, lead=True, uuid=uid('rescue_no_seat', 'a'),
              fam='Version:3,Initialized:1b,Age:400000L,Nourishment:400000L,AdultScale:1f,Scale:1f', **pair_ready()),
           *[yk('marisa', bank_dx(0) - 2 - i, -i, f'crew{i}', stage='baby', aquatic=True,
                childhood=CHILDHOOD_HOPPING if size == .28 else 'Version:2,Newborn:1b,Landed:1b', also=('crew',),
                fam=_seat_family(size, 0, 0) + f',BirthScale:{size}f', **pair_ready())
             for i, size in enumerate((.10, .19, .285, .28))],
           f'execute as {sel_all("crew")} run yukkuri status set @s needs.nutrition 40',
           afloat('a', water_at(0, 3), True, sel_all('crew'), stick=True),
           _aquatic_young('rescue_no_seat', 'c1', bank_dx(0) + 9.5, 0, 'baby', 'a', dy=-.5,
                          fam=_seat_family(.19, 0, 0) + ',BirthScale:0.19f', childhood='Version:2,Newborn:1b,Landed:1b')],
     note=_INCIDENT_NOTE + '满船按载重判定：除船长外每名乘员按体型^3 计重，普通亲油把抱着的孩子放到帽上时要求当前载重加这名孩子不进入警戒（1.0 体型帽容量 0.06352，警戒线 0.0540）。这只家庭载重 0.0530（0.10+0.19+0.285+0.28），再加 0.19 孩子 0.0069 = 0.0599 越过警戒线，日志给 refused id=release … NO_ROOM detail=hull_full load=… capacity=… limit=…；智力≤25 且友善≥75 的亲油不受载重限制，照旧放置并照旧抽翻船概率。载重留一只孩子的余量，是因为抱起孩子本身也算一次载重变化：再往上加，抓取那一刻的翻船概率就会升到几个百分点，真实翻船又会把帽檐幼体甩进水里。乘员营养 40、无储粮，不靠成长撑满。逐只 scenario place 显示 CABIN/HEAD/BRIM。新生赤油在亲帽放下后才入水。观察同一孩子 grab → 划回岸边、船停稳 → 帽檐子油 follow_ashore 自己 hop 落岸 → 舱内赤油 carry grip hold=mouth → 亲油 hop → 四次 release → take_prop（custody DEPLOYED -> WORN）→ rescue end outcome=set_down；帽上不留孩子，送岸期间无 child separation lost=true。',
     reads=[('读亲油', f'yukkuri status {sel("a")} raw'), ('读子油', f'yukkuri status {sel("c1")} raw')],
     capture=dict(shots=(1, 40, 200, 400, 600, 800, 1000), sequences=((140, 1000, 5),), observe=False,
                  views={'wide': dict(follow='@e[type=mcyukkuri:placed_hat,limit=1]', offset=(3.0, 1.6, 0)),
                         'parent': dict(follow='@e[tag=scn_a,limit=1]', offset=(-1.5, 1.1, 1.4))}),
     smoke=dict(ticks=1800, expect=[r'\[hat\][^\n]* scenario place [^\n]*place=CABIN\b[\s\S]*\[hat\][^\n]* scenario place [^\n]*place=HEAD\b[\s\S]*\[hat\][^\n]* scenario place [^\n]*place=BRIM\b',
                                    r'\[hat\][^\n]* scenario afloat prepared [^\n]*young=4\b',
                                    r'refused id=release [^\n]*NO_ROOM[^\n]*hull_full load=\S+ capacity=\S+',
                                    r'\[incident\][^\n]* decide [^\n]*chance=1 [^\n]*accepted=true',
                                    rf'carry grip [^\n]*uuid={_uuid_text("rescue_no_seat", "c1")}[^\n]*hold=braid'
                                    r'[\s\S]*\[family\][^\n]* carry grip [^\n]*hold=mouth',
                                    rf'carry grip [^\n]*uuid={_uuid_text("rescue_no_seat", "c1")}'
                                    rf'[\s\S]*\[action\][^\n]*uuid={_uuid_text("rescue_no_seat", "a")}[^\n]* end id=hop outcome=DONE'
                                    rf'[\s\S]*\[action\][^\n]*uuid={_uuid_text("rescue_no_seat", "a")}[^\n]* end id=release outcome=DONE detail=released'
                                    rf'[\s\S]*rescue end result=DONE outcome=set_down faller={_uuid_text("rescue_no_seat", "c1")}',
                                    r'\[water\][^\n]* phase=\w+ -> RECOVER',
                                    # The brim young hops ashore by itself, before the parent follows with the carried ones.
                                    rf'end id=follow_ashore result=DONE[\s\S]*\[action\][^\n]*uuid={_uuid_text("rescue_no_seat", "a")}[^\n]* end id=hop outcome=DONE'],
                forbid=[*_WATER_SMOKE_FORBID, r'rescue refused', r'\[body\][^\n]* death settled\b', r'cause=inWall'],
                verify=seat_unload_result, readout=_water_readouts('a', 'c1')))



# Smoke windows allow 1.5 times the three-minute upper bound, at 20 ticks per second.
_BOAT_SMOKE_START = (r'\[hat\][^\n]* scenario afloat prepared [^\n]*owner_aboard=true'
                     r'[\s\S]*\[hat\][^\n]* scenario afloat prepared [^\n]*owner_aboard=true')
_BOAT_SMOKE_FORBID = [r'\[hat\][^\n]* scenario afloat prepared [^\n]*owner_aboard=false',
                      r'\[aquatic\][^\n]* spawn refused reason=(?:no_hat|no_water|no_floor)\b']


def shore_birth_result(window):
    """Every child is aquatic, born clear on land after the mother's exit, and survives."""
    problem = water_aftermath(window)
    if problem:
        return problem
    expected = re.search(r'uuid=(\S+)[^\n]*conceived brood=(\S+)[^\n]*children=(\d+)', window)
    born = re.findall(r'\[family\][^\n]*uuid=(\S+)[^\n]* born brood=(\S+)[^\n]*', window)
    if expected is None or len(born) != int(expected[3]) or any(brood != expected[2] for _, brood in born):
        return 'birth count differs from the conceived brood'
    mother = expected[1]
    late = re.search(r'\[aquatic\][^\n]* birth shore return brood=\S+ remaining=(\d+)', window)
    if late is None:
        return 'the late interval never announced her shore return'
    first_departure = re.search(rf'uuid={mother}[^\n]* start id=come_ashore\b', window)
    if first_departure is None or first_departure.start() < late.start():
        return 'the mother left the water before the late interval'
    if not re.search(rf'uuid={mother}[^\n]* dismount [^\n]*cause=hop_takeoff'
                     rf'[\s\S]*uuid={mother}[^\n]* end id=hop outcome=DONE'
                     rf'[\s\S]*uuid={mother}[^\n]* delivery labor started', window):
        return 'mother did not hop ashore before labour'
    completed = re.search(rf'uuid={mother}[^\n]* delivery complete', window)
    if re.search(rf'uuid={mother}[^\n]* start id=set_out\b', window[late.end():completed.start()]):
        return 'mother tried to set out again after the shore-birth trigger'
    for child, _ in born:
        line = re.search(rf'\[family\][^\n]*uuid={child}[^\n]* born [^\n]*', window)[0]
        if 'subspecies=AQUATIC_MARISA' not in line or 'motherGrounded=true' not in line or 'bodyClear=true' not in line:
            return f'newborn {child} is not aquatic, ashore and clear of bodies'
        if not re.search(rf'uuid={child}[^\n]* birth landed [^\n]*water=false[^\n]*outcome=(?:GROUND|HAT|NEST)\b', window):
            return f'newborn {child} never landed safely'
        if re.search(rf'\[water\][^\n]*uuid={child}[^\n]* entered at=', window):
            return f'newborn {child} entered water after its dry birth'
        for health in re.findall(rf'uuid={child}[^\n]* damage cause=drown health=[\d.]+ -> ([\d.]+)', window):
            if float(health) < 8:
                return f'newborn {child} fell below health 8 from water'
        if not re.search(rf'uuid={child}[^\n]* first meal swallowed', window):
            return f'newborn {child} did not swallow its first meal'
        if not re.search(rf'uuid={child}[^\n]* first toilet completed', window):
            return f'newborn {child} did not complete newborn care'
    if not re.search(rf'scenario afloat prepared [^\n]*young=2\b', window):
        return 'initial rider count differs'
    parents = re.search(r' born brood=[^\n]* mother=(\S+) father=(\S+)', window).groups()
    labour = re.search(rf'uuid={mother}[^\n]* delivery labor started', window)
    for child, place in re.findall(r'uuid=(\S+)[^\n]* scenario place [^\n]*place=(HEAD|CABIN|BRIM)\b', window):
        if child in parents:
            continue
        if place == 'HEAD':
            if not re.search(rf'carry lowering hold=head child={child}'
                             rf'[\s\S]*carry put down [^\n]*cause=release[^\n]*child={child}', window[:labour.start()]):
                return f'head rider {child} never completed ordinary ground release before labour'
        elif not re.search(rf'uuid={child}[^\n]* dismount [^\n]*cause=hop_takeoff'
                           rf'[\s\S]*uuid={child}[^\n]* end id=hop outcome=DONE', window[:labour.start()]):
            return f'initial rider {child} never hopped ashore before labour'
    if re.search(r'\[body\][^\n]* death settled|cause=starv|brood cancelled', window):
        return 'birth family died, starved or lost its brood'
    postpartum = window[completed.end():]
    if not re.search(rf'uuid={mother}[^\n]* set_out held reason=newborn_care', postpartum):
        return 'mother did not remain ashore for newborn care'
    for departure in re.finditer(rf'uuid={mother}[^\n]* start id=(?:set_out|resolve_own_hat)\b', postpartum):
        if any(not re.search(rf'uuid={child}[^\n]* first toilet completed', postpartum[:departure.start()]) for child, _ in born):
            return 'mother tried to leave the newborn care phase for her boat'
        if 'id=resolve_own_hat' in departure[0]:
            for child, _ in born:
                attachments = re.findall(rf'uuid={child}[^\n]* (mount host=\S+|dismount at=\S+)', postpartum[:departure.start()])
                if not attachments or not attachments[-1].startswith('mount '):
                    return 'mother reclaimed alone while a newborn was still unboarded'
    if any(re.search(rf'set_out left_ashore=[^\n]*{child}', postpartum) for child, _ in born):
        return 'a newborn was excused from family loading and left ashore'


pond('birth_boat_missed', '临产划回岸边，放下孩子再生产', '临产划回岸边，放下孩子再生产',
     '水生母油先照常在水上活动（划船、吃帽里的粮、出航），临产准备时才停出航、划回岸边，经帽檐下船，'
     '随后按陆地接生帽／窝／地面规则分娩。'
     '初始一只较大的子油（坐头顶）和一只较小会跳的子油（站帽檐船首）随母船返岸：能自行下船的跟着上岸，头顶幼体随亲油落岸后由辫子放下。'
     '每只新生儿都应戴水生圆帽、在身体外出生并落在岸上；本窗口观察出生与留岸照护。幼体完成首餐、首便、能集合后才走普通亲子装载，全部新生儿上船才算出航完成。',
     '约 3–5 分钟（先水上生活，进入临产准备后返岸，留岸继续孕程）',
     [yk('marisa', bank_dx(0), 0, 'mother', aquatic=True, yaw=-90, lead=True, **pair_ready()),
      yk('marisa', bank_dx(4), 4, 'father', aquatic=True, yaw=-90, **pair_ready()),
      *[yk('marisa', bank_dx(dz), dz, name, stage=stage, aquatic=True,
           childhood=readiness, fam=_seat_family(size, nourishment, age), also=('crew',), label=label, **pair_ready())
        for name, dz, stage, size, nourishment, age, readiness, label in (
            ('k1', 1, 'child', .35, 147000, 144000, CHILDHOOD_CRAWLING_READY, '待托放的子油'),
            # its own size's nourishment (84000, not the 0.35 body's 147000): else it grows to 0.35 on the first tick and the
            # two young overload her hull, so the scene shows a random too_heavy capsize instead of the shore return. With k1
            # the two weigh 0.80 of her hull's capacity, under its load warning (0.85), so she has no reason to unload them early.
            ('k2', -1, 'child', .20, 84000, 144000, CHILDHOOD_HOPPING, '会跳的小子油'))],
      item('minecraft:apple', 8, bank_dx(0) - 2, 0),
      *pond_rim_seagrass(),
      f'yukkuri scenario bond {sel("mother")} {sel("father")}',
      afloat_now('mother', water_at(0, 3), True, sel_all('crew'), stick=True),
      afloat_now('father', water_at(4, 3), True, sel('father'), stick=True)],
     late=[*[f'yukkuri scenario kin {sel("mother")} {sel(k)} false' for k in ('k1', 'k2')],
           f'yukkuri scenario birth {sel("mother")} {sel("father")} near_term "0.16,0.17"'],
     note='开场实体全部可见，准备后坐进各自浮着的帽子；岸上有八个真实苹果供孕期取食，整圈岸边浅水里是真实海草，是产后水生的食物。'
          '本胎两只出生体型 0.16、0.17，不符合自帽分娩空间；读 birth route=shore 的 growth/growth_max。先看母油照常在水上活动，`[aquatic] birth shore return` 之后才 come_ashore '
          '→ hop 离船落地 → delivery labor started → born → birth landed；'
          '出生数对照 conceived children 与 born index，“读本胎新生儿”排除初始 crew；产后应见 set_out held reason=newborn_care，母油留岸，不重新登船离开新生儿。'
          '产后喂食只看水草与帽内存粮：`feed source selected … source=plant 1 minecraft:seagrass`（她落在别处够不到水草时 `source=hat larder`），'
          '不得走回岸上苹果那头。' + _DESIRE_NOTE,
     reads=[('读母油与产程', f'yukkuri status {sel("mother")} raw'), ('读父油', f'yukkuri status {sel("father")} raw'),
            ('读本胎新生儿', f'yukkuri status {NEWBORNS[:-1]},tag=!crew] raw'),
            ('读初始乘员', f'yukkuri status {sel_all("crew")} raw')],
     smoke=dict(ticks=7200, expect=[_BOAT_SMOKE_START, r'\[choreo\][^\n]* beat id=live_birth\b',
                                   r'\[aquatic\][^\n]* birth shore return brood=\S+ remaining=\d+',
                                   r'\[intent\][^\n]* start id=come_ashore[^\n]*birth=[1-9]',
                                   r'\[family\][^\n]* feed source selected [^\n]*source=(?:plant 1 minecraft:seagrass|hat larder)\b',
                                   r'\[family\][^\n]* delivery complete\b',
                                   r'\[family\][^\n]* birth landed [^\n]*outcome=(?:HAT|GROUND|NEST)\b'],
                forbid=_BOAT_SMOKE_FORBID + [r'\[body\][^\n]* death settled\b', r'birth landed [^\n]*outcome=WATER\b',
                                            r'\[water\][^\n]* entered at=[^\n]*action=walk owner=drive:aquatic\b'],
                verify=shore_birth_result,
                readout=_water_readouts('mother', 'father', 'k1', 'k2') + [f'yukkuri status {NEWBORNS[:-1]},tag=!crew] raw']),
     capture=dict(shots=(1, 40, 600, 1200, 1800, 2400, 4800), sequences=((600, 1800, 5),),
                  views={'': dict(follow='@e[tag=scn_mother,limit=1]', offset=(-1.0, 1.8, 1.2))}))


def hat_birth_result(window):
    born = re.findall(r'\[family\][^\n]*uuid=(\S+)[^\n]* born [^\n]*index=(\d+)[^\n]*kind=LIVE', window)
    if len(born) != 2:
        return f'expected two newborns, got {len(born)}'
    mother = re.search(r' born brood=[^\n]* mother=(\S+)', window)[1]
    care_done = []
    for child, index in born:
        landing = re.search(rf'uuid={child}[^\n]* birth landed [^\n]*outcome=(HAT|WATER)\b', window)
        if not landing:
            return f'newborn {index} never landed'
        # A newborn may miss the hollow and come down in the water (the user's rule); the ordinary rescue then takes it.
        if landing[1] == 'HAT':
            if not re.search(rf'uuid={child}[^\n]* birth landed [^\n]*water=false[^\n]*outcome=HAT\b', window):
                return f'newborn {index} HAT landing was not dry'
            if not re.search(rf'uuid={child}[^\n]* contact host=', window):
                return f'newborn {index} has no real hull contact'
            if re.search(rf'\[water\][^\n]*uuid={child}[^\n]* (?:entered at=|damage cause=drown)', window):
                return f'newborn {index} entered the water after a hat landing'
        else:
            if not re.search(rf'\[aquatic\][^\n]* rescue start [^\n]*faller={child}\b', window):
                return f'newborn {index} landed in the water and no rescue started'
            if not re.search(rf'\[aquatic\][^\n]* rescue end [^\n]*outcome=\S+ [^\n]*faller={child}\b', window):
                return f'newborn {index} rescue never ended'
        if not re.search(rf'uuid={child}[^\n]* first meal swallowed', window):
            return f'newborn {index} did not swallow its first meal'
        toilet = re.search(rf'uuid={child}[^\n]* first toilet completed', window)
        if not toilet:
            return f'newborn {index} did not complete newborn care'
        care_done.append(toilet.end())
    if re.search(rf'turn push [^\n]*(?:by={mother}|rider={mother})\b', window[:max(care_done)]):
        return 'mother moved or pushed a rider during cabin care'
    if re.search(r'\[body\][^\n]* death settled|starvation damage|brood cancelled', window):
        return 'birth family died, starved or lost its brood'


pond('birth_hat_own', '独自在自己帽里生下两只赤油', '独自在自己帽里生下两只赤油',
     '临产水生母油独坐自己的浮帽；本胎两只出生体型 0.12、0.125，按真实空间判定留在水上。'
     '母油面向船头分娩，赤油从出口落到帽内空处，触布后才附着；落点偏了也可落水，落水赤油走正常救援。'
     '父油有自己的浮帽，产程里划到她帽旁守候。产后用真实粮食嚼糜喂幼、护理首便。',
     '约 3–6 分钟',
     [yk('marisa', bank_dx(0), 0, 'mother', aquatic=True, yaw=-90, lead=True, **pair_ready()),
      yk('marisa', bank_dx(4), 4, 'father', aquatic=True, yaw=-90, **pair_ready()),
      *pond_rim_seagrass(),
      f'yukkuri scenario bond {sel("mother")} {sel("father")}',
      afloat_now('mother', water_at(0, 3), True, sel('mother'), food=8, stick=True),
      afloat_now('father', water_at(4, 3), True, sel('father'), food=4, stick=True)],
     late=[
           f'yukkuri scenario birth {sel("mother")} {sel("father")} near_term "0.12,0.125"'],
     note='看 birth route=own_hat 的胎数、体型与空间判定；hat birth exit 的产程与片段时刻；'
          '父油 [aquatic] guard start → guard arrive（划到她帽旁、不在出口那侧）→ guard end；'
          '新生儿 birth landed outcome=HAT 触布附着，或落水 outcome=WATER 后走正常救援（rescue start/end），然后首餐首便。'
          '帽形和普通分娩时钟不变，初态没有新生儿。'
          '五秒后三下摇翻父油那只帽（母亲的分娩不受影响）：木棍与粮随翻覆漂出，父油游回扶正、爬回帽里，再没木棍就去水上把漂着的木棍取回帽里。'
          '岸边浅水里有真实海草，是本场景水生家庭上岸后唯一的水草食源。',
     reads=[('读母油与产程', f'yukkuri status {sel("mother")} raw'),
            ('读本胎新生儿', f'yukkuri status {NEWBORNS} raw')],
     smoke=dict(ticks=7200,
                commands=[(tick, f'execute at {sel("father")} run yukkuri scenario shake '
                                '@e[type=mcyukkuri:placed_hat,distance=..8,sort=nearest,limit=1]') for tick in (60, 70, 80)],
                expect=[_BOAT_SMOKE_START, r'scenario afloat prepared [^\n]*food=8', r'birth route=own_hat reason=fit',
                                  r'\[aquatic\][^\n]* guard start mate=\S+ mother=\S+',
                                  r'\[aquatic\][^\n]* guard arrive mate=\S+ mother=\S+',
                                  r'hat birth exit index=0', r'hat birth exit index=1', r'delivery complete',
                                  r'departure held reason=newborn_care',
                                  r'\[hat\][^\n]* capsized cause=shake[\s\S]*\[hat\][^\n]* righted cause=\w+'
                                  r'[\s\S]*\[hat\][^\n]* boarded rider=\S+[^\n]*own=true'
                                  r'[\s\S]*\[intent\][^\n]* start id=fetch_stick\b'
                                  r'[\s\S]*\[aquatic\][^\n]* stick taken [^\n]*stowed_in=hat'],
                forbid=_BOAT_SMOKE_FORBID + [r'\[body\][^\n]* death settled'],
                verify=hat_birth_result,
                readout=_water_readouts('mother', 'father') + [f'yukkuri status {NEWBORNS} raw']))

pond('first_voyage', '子油跳上岸，自己放帽划一圈', '子油跳上岸，自己放帽划一圈',
     '亲油与处于初航欲望带的子油已坐在近岸亲帽里；子油能自己吃、会跳和学习，戴着夹有一根木棍的帽子，'
     '还没自己操过帽。晴天静水里，子油想试试自己的帽子（越大胆越想）：先从亲帽跳上岸，再走到有静水和船位的地方把自己的帽子放到水面上、'
     '跳进去，用木棍划到亲船旁两三格外的开阔水面绕一下，再划回岸边跳上岸，从岸沿把帽子收回戴上；回到岸上戴好帽子才算学会操帽，之后只乘自己的帽子，落水能自己爬回、'
     '扶正帽子。路上翻帽或滑落是真实的，这次初航失败；落水救助按功能说明的水生规则执行',
     '约 2–4 分钟（可倍速）',
     [yk('marisa', bank_dx(0), 0, 'a', aquatic=True, yaw=0, lead=True, uuid=uid('first_voyage', 'a'), kit='minecraft:stick',
         **{**pair_ready(), 'energy': 20}),
      _aquatic_young('first_voyage', 'c1', bank_dx(1), 1, 'child', 'a', fam=_seat_family(.34), kit='minecraft:stick', boldness=75),
      afloat('a', water_at(0, 1.45), True, sel('c1'))],
     note='初态：子油位于初航欲望带，childhood 无 Sailed（读子油 childhood 看 Sailed），固食、跳跃与学习条件已具备，自己的帽里有真实木棍。亲油近岸低体力歇泊，子油从亲帽的真实座位开始初航。'
          '日志：子油 [intent] start id=first_voyage（why 中 boldness、size、ratio）→ [aquatic] first_voyage start … from=parent_hat → [action] end id=hop outcome=DONE（离亲帽）→ [action] start id=walk（找船位）→ '
          '[aquatic] berth → [hat] placed … water=true kit=minecraft:stick → [action] start id=hop → [body] mount … → [aquatic] route purpose=first_voyage → '
          '[action] start id=paddle → [aquatic] first_voyage rounded → [intent] start id=come_ashore → [action] start id=hop → [action] start id=take_prop → '
          '[aquatic] first_voyage success → [childhood] sailing learned how=first_voyage。帽子漂离岸沿时子油或亲油在岸边俯身用辫子划水把帽子招回：[action] start id=beckon … → '
          '[action] end id=beckon outcome=DONE detail=at_bank（招失败后同一次不再招，改等漂近）；帽子靠岸记 [hat] moored，帽檐搭上岸沿记 [hat] beached。'
          '初航记 [aquatic] first_voyage fail … in_water=true 与 first_voyage cooldown（第二天早上前不再试）。' + _DESIRE_NOTE,
     reads=[('读子油', f'yukkuri status {sel("c1")} raw'), ('读亲油', f'yukkuri status {sel("a")} raw'), ('读最近的帽子', f'data get entity {_HAT}')],
     smoke=dict(ticks=4800, expect=[r'\[hat\][^\n]* scenario afloat prepared [^\n]*young=1\b',
                                   rf'\[aquatic\][^\n]*uuid={_uuid_text("first_voyage", "c1")}[^\n]* first_voyage start [^\n]*from=parent_hat\b'
                                   rf'[\s\S]*\[action\][^\n]*uuid={_uuid_text("first_voyage", "c1")}[^\n]* end id=hop outcome=DONE'
                                   rf'[\s\S]*\[hat\][^\n]* placed owner={_uuid_text("first_voyage", "c1")}\b',
                                   r'\[body\][^\n]* mount [^\n]*cause=hop_contact\b',
                                   r'\[aquatic\][^\n]* first_voyage success\b'],
                forbid=[r'\[body\][^\n]* invariant\b', r'\[body\][^\n]* death settled\b'], readout=[f'yukkuri status {sel("c1")} raw']))


pond('first_voyage_brim', '子油在帽檐脱帽放水划一圈', '子油在帽檐脱帽放水划一圈',
     '亲油坐在离岸四格以外的开阔静水里歇泊，帽里有粮和木棍；处于初航欲望带的子油站在亲帽的帽檐上（它太大，帽里容不下），能自己吃、会跳和学习，戴着夹有一根木棍的帽子，'
     '还没自己操过帽。周围没有一跳够得着的岸，子油想试试自己的帽子（越大胆越想）：在帽檐上面向水面脱下自己的帽子放到亲船旁的静水上，'
     '再一步从亲帽帽檐跳进自己的帽子（跳向与朝向一致），用木棍划到亲船旁两三格外的开阔水面绕一下，再划回岸边跳上岸，从岸沿把帽子收回戴上；'
     '回到岸上戴好帽子才算学会操帽，之后只乘自己的帽子。路上翻帽或滑落是真实的，这次初航失败；落水救助按功能说明的水生规则执行',
     '约 2–4 分钟（可倍速）',
     [yk('marisa', bank_dx(0), 0, 'a', aquatic=True, yaw=0, lead=True, uuid=uid('first_voyage_brim', 'a'), kit='minecraft:stick',
         **{**pair_ready(), 'energy': 20}),
      _aquatic_young('first_voyage_brim', 'c1', bank_dx(1), 1, 'child', 'a', fam=_seat_family(.34), kit='minecraft:stick', boldness=75),
      afloat('a', water_at(0, 6), True, sel('c1'), food=4)],
     note='初态：子油位于初航欲望带，childhood 无 Sailed（读子油 childhood 看 Sailed），固食、跳跃与学习条件已具备，自己的帽里有真实木棍；亲油在离岸 4 格以外的静水里低体力歇泊，'
          '帽里有四份苹果和一根木棍，子油站在亲帽帽檐的真实位置上，岸边没有可跳的落点。'
          '日志：子油 [intent] start id=first_voyage → [aquatic] first_voyage start … from=parent_hat → [aquatic] first_voyage route=brim reason=no_bank_in_hop_reach → '
          '[action] end id=face → [action] start id=place_prop → [hat] placed … water=true kit=minecraft:stick → '
          '[aquatic] first_voyage hat set on water → [action] start id=hop（亲帽帽檐跳进自己的帽，hop_leg 片段）→ [body] mount … cause=hop_contact → '
          '[aquatic] first_voyage hopped onto own hat … aboard=true → [aquatic] route purpose=first_voyage → [action] start id=paddle → [aquatic] first_voyage rounded → '
          '[intent] start id=come_ashore → … → [action] start id=take_prop → [aquatic] first_voyage success … route=brim → [childhood] sailing learned how=first_voyage。'
          '两顶帽子的帽檐相距 0.05 格并排漂着，不重叠；亲船静止（速度极小）时才出发。' + _DESIRE_NOTE,
     reads=[('读子油', f'yukkuri status {sel("c1")} raw'), ('读亲油', f'yukkuri status {sel("a")} raw'), ('读最近的帽子', f'data get entity {_HAT}')],
     smoke=dict(ticks=4800, expect=[r'\[hat\][^\n]* scenario afloat prepared [^\n]*young=1\b',
                                   rf'\[aquatic\][^\n]*uuid={_uuid_text("first_voyage_brim", "c1")}[^\n]* first_voyage start [^\n]*from=parent_hat\b'
                                   rf'[\s\S]*\[aquatic\][^\n]*uuid={_uuid_text("first_voyage_brim", "c1")}[^\n]* first_voyage route=brim\b'
                                   rf'[\s\S]*\[hat\][^\n]* placed owner={_uuid_text("first_voyage_brim", "c1")} hat=(\S+)[^\n]* water=true'
                                   rf'[\s\S]*\[body\][^\n]*uuid={_uuid_text("first_voyage_brim", "c1")}[^\n]* mount host=\1 cause=hop_contact'
                                   rf'[\s\S]*\[aquatic\][^\n]*uuid={_uuid_text("first_voyage_brim", "c1")}[^\n]* first_voyage hopped onto own hat [^\n]*aboard=true\b',
                                   r'\[aquatic\][^\n]* first_voyage success [^\n]*route=brim\b'],
                forbid=[r'\[body\][^\n]* invariant\b', r'\[body\][^\n]* death settled\b', r'\[aquatic\][^\n]* first_voyage route=bank\b'],
                readout=[f'yukkuri status {sel("c1")} raw']))


# Deck order of the pond field: solo life, family on the boat, falling in and rescue, birth, then hat handling and hull physics.
POND_TOUR = (
    'water_colony', 'sail', 'afloat_stick', 'cargo',
    'afloat_family', 'afloat_family_gather', 'afloat_family_ashore', 'shore_family',
    'kid_boat_play', 'boat_toilet', 'first_voyage', 'first_voyage_brim', 'hat_crowd',
    'self_rescue', 'shake_ashore', 'worn_hat_boat', 'rescue_child', 'rescue_no_seat',
    'birth_boat_missed', 'birth_hat_own', 'shore_capsized', 'shore_capsized_far',
    'hat_swap', 'shake_capsize', 'hat_push', 'hat_current',
    'hat_grounded_capsized')
assert sorted(POND_TOUR) == sorted(scenario.id for scenario in BY_ID['pond'].scenarios), 'the pond tour must list every pond scene'
BY_ID['pond'].scenarios.sort(key=lambda scenario: POND_TOUR.index(scenario.id))


def _number_scenes():
    """Give every scene the number the registry holds, and refuse a registry that breaks the append-only rule."""
    codes, numbers = NUMBERS['fields'], NUMBERS['numbers']
    # Retired field codes stay reserved with their existing scene numbers.
    assert {field.id for field in FIELDS} <= set(codes), 'every active field needs a scene-number code'
    field_of = {code: fid for fid, code in codes.items()}
    assert len(field_of) == len(codes), 'two fields share a scene-number code'
    last = dict.fromkeys(codes, 0)
    used = {}
    for sid, number in numbers.items():
        match = re.fullmatch(r'([A-Z]{1,2})-(\d\d)', number)
        assert match and match[1] in field_of, (sid, number, 'a number is a known field code, a dash and two digits')
        assert number not in used, (sid, number, 'two scenes share a number', used.get(number))
        used[number] = sid
        assert int(match[2]) == last[field_of[match[1]]] + 1, \
            (sid, number, 'numbers are append-only: an entry takes the next free index of its field')
        last[field_of[match[1]]] = int(match[2])
    for scenario in S:
        assert scenario.id in numbers, f'scene {scenario.id} has no number in museum_scene_numbers.json'
        scenario.number = numbers[scenario.id]
        assert field_of[scenario.number.split('-')[0]] == scenario.field, (scenario.id, scenario.number, scenario.field)


_number_scenes()


# Topic codes are shared by the scene buttons, signs, and readouts.
TOPICS = {
    'home_pane': 'W7', 'home_open': 'W7',
    'balance_traits': 'N1', 'balance_relation': 'N1', 'balance_forms': 'N1', 'balance_deibu': 'N1',
    'waste_demo': 'F5', 'family_walk_home': 'F4', 'family_flight_home': 'F4',
    'agitation_relief': 'D2', 'adult_pairing': 'F6', 'widow_repair': 'F6', 'alice_hunt': 'R4', 'aftermath_growth': 'F7', 'aftermath_depletion': 'F7',
    'den_family': 'W1', 'den_mold_exile': 'W1', 'dig': 'W2', 'indep_near': 'W3', 'den_move': 'W4', 'forage': 'W5', 'graze': 'W6', 'den_barrier': 'W8',
    'family_absence': 'W9',
    'stray_boxes': 'M5', 'stray_rain': 'R1', 'stray_rubbish': 'M5', 'stray_junk_headwear': 'M5', 'stray_wind': 'R1',
    'sleep': 'D1', 'night_sleep': 'D1', 'night_hungry_child': 'D1', 'meal_content': 'D1', 'fatigue': 'R1', 'fatigue_predator': 'R2', 'nutrition_age': 'D1', 'meet': 'D1', 'greet_close': 'D1', 'toilet': 'F5', 'hurt': 'F3',
    'birth_land_caught': 'F1', 'birth_land_missed': 'F1', 'feed': 'F2', 'first_toilet': 'F2', 'hop': 'F2', 'growth': 'F4',
    'leaf_swaddle': 'F2', 'leaf_swaddle_neglect': 'F2', 'leaf_swaddle_neglect_stages': 'F2', 'family_distance': 'F8', 'family_search': 'F8', 'family_search_pane': 'F8', 'birth_nest_safe': 'F1', 'birth_nest_stem': 'F1', 'birth_stem_caught': 'F1', 'family_nests': 'F4', 'carry_home': 'F4', 'infant_starvation': 'F2',
    'excited_leak': 'D1',
    'forage_empty': 'R1', 'rain_life': 'R1', 'rain_huddle': 'R1', 'storm_cower': 'R1', 'marisa_explore': 'R1', 'rain_shelter': 'R1', 'rain_open': 'R1', 'pred_watch': 'R2', 'pred_range': 'R2', 'pred_pets': 'R2', 'shared_body': 'R3',
    'house_empty': 'H1', 'house_pair': 'H2', 'house_eggs': 'H3',
    'shop_build': 'M1', 'shop_window': 'M1', 'shop_buy': 'M1', 'shop_restock': 'M1', 'harness_walk': 'M3', 'swaddle_worn': 'M3', 'feed_wild': 'M3', 'oniisan_prey': 'M4', 'attack_witness': 'M4',
    'wild': 'M5', 'badge': 'M5', 'training_home': 'M5', 'training_study': 'M5', 'exam': 'M5', 'date': 'M5', 'transport': 'M5', 'custody_death': 'M5', 'social_identity': 'M5',
    'exam_obstructed': 'M5', 'exam_silver': 'M5', 'exam_gold': 'M5', 'exam_platinum': 'M5',
    'airborne': 'R8', 'house_health': 'R9',
    'body_mold': 'R5', 'wound_materials': 'R5', 'thermal_body': 'R5', 'wound_sources': 'R5', 'wound_leak': 'R5', 'treatment_severe': 'R5', 'treatment_scars': 'R5', 'treatment_care': 'R5', 'magma_sole': 'R5', 'core_exposed': 'R5', 'core_filling': 'R5', 'remains_rot': 'R6', 'remains_forms': 'R6', 'carrion': 'R6', 'cannibal_hunt': 'R6',
    'last_words': 'R7', 'offering': 'R7', 'sacrifice': 'R7',
    'hat_push': 'A1', 'hat_current': 'A1', 'hat_grounded_capsized': 'A4', 'cargo': 'A2', 'hat_crowd': 'A3', 'shake_capsize': 'A4',
    'water_colony': 'A6', 'sail': 'A6', 'afloat_stick': 'A6', 'afloat_family': 'A6', 'afloat_family_gather': 'A6', 'afloat_family_ashore': 'A6', 'shore_family': 'A6', 'kid_boat_play': 'A6', 'boat_toilet': 'A6',
    'rescue_child': 'A5', 'rescue_no_seat': 'A5', 'birth_boat_missed': 'A6', 'birth_hat_own': 'A6', 'first_voyage': 'A6', 'first_voyage_brim': 'A6',
    'self_rescue': 'A5', 'shake_ashore': 'A5', 'shore_capsized': 'A5', 'shore_capsized_far': 'A5', 'worn_hat_boat': 'A5', 'hat_swap': 'C1', 'hat_damage': 'C1', 'hat_shatter': 'C1', 'hatless_kin': 'C2', 'hatless_scorn': 'C2',
    'hatless_monster': 'C2', 'puff_response': 'C2',
    'myon_stick': 'S1', 'myon_brave': 'S1', 'species_fatigue': 'S1', 'rare_forms': 'S2', 'kotatsu_winter': 'S2', 'kotatsu_summer': 'S2', 'sunbathe': 'S2',
    'alice_chores': 'S3', 'patchouli_vomit': 'S3', 'chen_speed': 'S3', 'marisa_sumo': 'S3',
    'colony_hunt': 'B2', 'colony_ration': 'B2', 'colony_migrate': 'B2', 'colony_mold_exile': 'B2',
}
for _s in S:
    _s.asks = TOPICS.get(_s.id) or ('B1' if _s.id.startswith('settle_') else '')
    assert _s.asks, _s.id


# Smoke expectations of the starts the land audit (P6) corrected: each names the subject the scene exists to show.
def _smoke(sid, ticks, expect, forbid=(), readout=()):
    scene = next(s for s in S if s.id == sid)
    scene.smoke = dict(ticks=ticks, expect=list(expect), forbid=[r'\[body\][^\n]* invariant\b', *forbid], readout=list(readout))


_LICK = [r'\[comfort\][^\n]* approach started', r'\[comfort\][^\n]* mouth contact started']
_smoke('hurt', 1800, _LICK)
next(s for s in S if s.id == 'hurt').smoke['verify'] = _two_lickers
_smoke('birth_nest_safe', 3000, [r'\[family\][^\n]* birth landed [^\n]*outcome=CUSHION\b'])
_smoke('birth_nest_stem', 3000, [r'\[family\][^\n]* stem site facing ', r'\[family\][^\n]* birth landed [^\n]*outcome=CUSHION\b'])


def _alice_share_home(window):
    homes = re.findall(r'\[habitat\] (?:alice|reimu) [^\n]* home chosen [^\n]*?home=(BlockPos\{[^}]*\})', window)
    if len(homes) < 2:
        return f'alice and reimu did not both choose a home: {homes}'
    return None if len(set(homes)) == 1 else f'the partners chose different homes: {homes}'


_smoke('alice_chores', 600, [r'\[habitat\] alice [^\n]* home chosen', r'\[habitat\] reimu [^\n]* home chosen'])
next(s for s in S if s.id == 'alice_chores').smoke['verify'] = _alice_share_home
_smoke('sacrifice', 1200, [r'\[grief\][^\n]* protection diverted [^\n]*response=protect_child_worry'])
_smoke('pred_range', 1800, [r'\[predator\] flandre [^\n]* prey selected [^\n]* distance=(?:4[2-9]|5[0-4])\.',
                            r'\[predator\] flandre [^\n]* feeding bite [^\n]* mouthful_accepted=true'])
_smoke('colony_hunt', 2400, [r'\[settlement\][^\n]* event=hunt_call [^\n]*detail=party \d+ dest '])
for _exile_scene, _scope in (('colony_mold_exile', 'colony'), ('den_mold_exile', 'family')):
    _smoke(_exile_scene, 2400, [
        r'\[settlement\][^\n]* mold witnessed [^\n]* response=drive',
        rf'\[settlement\][^\n]* mold exile departed scope={_scope}\b',
        r'\[settlement\][^\n]* mold exile refused return\b',
        r'\[settlement\][^\n]* mold exile withdrew after refusal\b',
        *([r'\[family\][^\n]* mold exile child stays child='] if _scope == 'family' else []),
    ], readout=[f'yukkuri status {sel("molded")} raw'])
_smoke('colony_ration', 3600, [r'\[settlement\][^\n]* event=ration [^\n]*detail=relief \d+/\d+ to [1-9]\d* short households',
                               r'\[settlement\][^\n]* event=ration_fetched '])


# Subjects of the scenes merged in the curation round: each merged lane or variant shows up in the log.
def _distinct(pattern, count, what):
    def verify(window):
        found = set(re.findall(pattern, window))
        return None if len(found) >= count else f'{what}: {len(found)} of {count} ({sorted(found)})'
    return verify


_smoke('social_identity', 440, [
    rf'\[status\] {species} #[^\n]* identity observed kind={kind}\b'
    for species, kind in (('reimu', 'pet'), ('marisa', 'stray'), ('patchouli', 'wild_settlement'), ('chen', 'wild_family'))] + [
        r'\[social\] marisa #[^\n]* human food request [^\n]* line=human_food_demand\b',
        r'\[social\] patchouli #[^\n]* human retreat [^\n]* moving=true\b',
        r'\[social\] chen #[^\n]* human watch '],
    readout=[f'yukkuri status {sel("identity_" + kind)} raw' for kind in ('pet', 'stray', 'settlement', 'family')] +
            [f'data get entity @e[tag={tag("identity_stray")},distance=..24,limit=1] LastFoodRequestNutrition'])
_human_contact_smoke = next(s for s in S if s.id == 'social_identity').smoke


def _one_human_food_request(log):
    requests = re.findall(r'\[social\] marisa #[^\n]* human food request target=', log)
    return None if len(requests) == 1 else f'expected one voiced request from Marisa, found {len(requests)}'


_human_contact_smoke['verify'] = _one_human_food_request
_smoke('wild', 600, [r'\[status\] reimu [^\n]* identity observed kind=pet\b',
                    r'\[status\] marisa [^\n]* identity observed kind=wild_family\b',
                    r'\[wild-event\][^\n]*event=ended [^\n]*card=beg_bite [^\n]*detail=(?:shared|refused)\b'],
    readout=[f'yukkuri status {sel(kind)} raw' for kind in ('pet', 'wild')])
_smoke('badge', 1200, [r'\[badge\] marisa [^\n]*badge sight [^\n]*grade=4\b',
                     r'\[wild-event\][^\n]*event=ended [^\n]*card=envy [^\n]*detail=envy_\w+'],
    readout=[f'yukkuri status {sel(kind)} raw' for kind in ('pet', 'wild')])
_smoke('puff_response', 650, [r'\[social\][^\n]*puff response=(?:flee|ignore|puff_back|attack) [^\n]*esteem=[^\n]*wariness=[^\n]*insistence=[^\n]*combat='],
    readout=[f'yukkuri status {sel(f"puff_target{i}")} raw' for i in range(4)])
_smoke('training_home', 2100, [
    r'\[lesson\][^\n]*skill practice skill=FOOD_BOWL source=daily xp=76 -> 78',
    r'\[needs\][^\n]*relieved source=facility',
    r'\[sleep\][^\n]*begin source=life',
], readout=[f'data get entity {sel(n)} YukkuriPet.SkillExperience' for n in ('practised', 'novice')])
_smoke('training_study', 1750, [
    r'\[lesson\][^\n]*study answered skill=READING',
    r'\[lesson\][^\n]*study answered skill=ARITHMETIC source=food count=6',
], readout=[f'data get entity {sel(n)} YukkuriPet.SkillExperience' for n in ('reader', 'counter')])
_smoke('balance_relation', 600, [r'\[status\] reimu [^\n]* identity observed kind=wild_family\b'],
    readout=[f'yukkuri status {sel(f"fear{fear}")} raw' for fear, _ in _BALANCE_FEAR])
next(s for s in S if s.id == 'balance_relation').smoke['verify'] = _distinct(
    r'\[status\] reimu #\d+ uuid=(\S+)[^\n]* identity observed kind=wild_family\b', 3, 'relation fixtures')


_smoke('den_family', 6000, [r'\[habitat\] reimu [^\n]* home chosen', r'\[habitat\] marisa [^\n]* home chosen',
                            r'\[forage\] reimu [^\n]* begin role='])


def _den_family_verify(log):
    mother, father, child = (_uuid_text('den_family', name) for name in ('mother', 'father', 'child'))

    def events(actor, category, message):
        pattern = rf'\[{category}\] \w+ #\d+ uuid={actor} t=(\d+) [^\n]*? {message}'
        return [(int(match[1]), match.groups()[1:]) for match in re.finditer(pattern, log)]

    death = events(father, 'body', r'death settled into remains; cause=genericKill\b')
    if not death:
        return 'den family: missing scripted father death'
    death_tick = death[0][0]
    homes = []
    for actor in (mother, father, child):
        claims = [values[0] for tick, values in events(actor, 'habitat', r'home chosen old=[^\n]*? home=(BlockPos\{[^}]+\})')
                  if tick <= death_tick]
        homes.append(claims[-1] if claims else None)
    if not homes[0] or len(set(homes)) != 1:
        return 'den family: parents and child did not claim one home before the death'

    departures = [tick for tick, _ in events(mother, 'forage', r'begin role=FAMILY phase=OUT\b') if tick > death_tick]
    if not departures:
        return 'den family: mother did not start a trip after father death'
    if events(mother, 'family', rf'witnessed kin death dead={father}\b'):
        grief = events(mother, 'status', r'behavior\.animation: [^\n]*? -> bereaved\b')
        if not any(death_tick <= tick < departures[0] for tick, _ in grief):
            return 'den family: mother left before playing witnessed bereavement'

    deposits = events(mother, 'forage', rf'deposit home={re.escape(homes[0])} moved=[1-9]\d*\b')
    if not any(tick > departures[0] for tick, _ in deposits):
        return 'den family: no positive deposit from the mother after her departure'
    if not any(tick > death_tick for tick, _ in events(child, 'meal', r'swallowed item=\S+')):
        return 'den family: child did not eat after father death'
    return None


next(s for s in S if s.id == 'den_family').smoke['verify'] = _den_family_verify
_smoke('indep_near', 7200, [r'\[independence\][^\n]* farewell started'])
next(s for s in S if s.id == 'indep_near').smoke['verify'] = _distinct(r'\[independence\] \w+ #\d+ uuid=(\S+)[^\n]* departed ', 2, 'teens that left home')
_smoke('forage', 6000, [r'\[forage\][^\n]* begin role=FAMILY', r'\[forage\][^\n]* deposit home=[^\n]* moved=[1-9]',
                         r'\[family\][^\n]* household watched entrance', r'\[family\][^\n]* welcome home started ',
                         rf'\[family\][^\n]* feed handed to meal child={_uuid_text("forage", "child")}\b'])
_absent_uuid = _uuid_text('family_absence', 'absent')
_smoke('family_absence', 6000, [rf'\[family\][^\n]* kin overdue kin={_absent_uuid}\b',
                                 rf'\[family\][^\n]* missing kin call kin={_absent_uuid}\b'],
       forbid=[rf'\[family\][^\n]* witnessed kin death dead={_absent_uuid}\b'])
for _sid in ('forage', 'family_absence'):
    next(s for s in S if s.id == _sid).smoke['gating_checks'] = ('END',)
_smoke('graze', 1200, [r'\[meal\][^\n]* swallowed item=minecraft:apple\b', r'\[meal\][^\n]* swallowed item=minecraft:short_grass\b'])
_smoke('meal_content', 900, [r'\[meal\][^\n]* full nutrition=[^\n]*content=true'])
next(s for s in S if s.id == 'meal_content').smoke['verify'] = _meal_content_verify
_smoke('meet', 2400, [r'\[social\][^\n]* greet [^\n]*why=first_meet', r'\[daily\][^\n]* session result kind=NUZZLE'])
_wide_greeter = _uuid_of(_CLOSE_GREETERS['wide'])
_bean_greeter = _uuid_of(_CLOSE_GREETERS['bean'])
_smoke('greet_close', 620, [
    rf'\[social\][^\n]* greet from={_wide_greeter} to={_bean_greeter} why=first_meet',
    rf'\[social\][^\n]* greet answer from={_bean_greeter} to={_wide_greeter} why=first_meet answer=greet'])
_sukkiri_beats = (('build', 1, 0), ('su', 2, 30), ('su_again', 2, 52), ('release', 3, 70))


def _sukkiri_timing(log, *, alice=False):
    start = re.search(r'(?:act timeline target=\S+|courtship contact started partner=\S+) loopTicks=(\d+) requiredTicks=(\d+)', log)
    if not start:
        return 'missing contact timeline'
    loop, required = map(int, start.groups())
    minimum, maximum, cycle = (100, 160, 20) if alice else (100, 600, 32)
    if not minimum <= loop <= maximum or loop % cycle or required != loop + 130:
        return f'invalid contact timeline: loop={loop}, total={required}, cycle={cycle}'
    release = re.search(r'act release effect target=\S+ ticks=(\d+)' if alice
                        else r'courtship release partner=\S+ tick=(\d+)', log)
    finish = re.search(r'contact ended target=[^\n]* ticks=(\d+)' if alice
                       else r'courtship contact ended partner=[^\n]* ticks=(\d+)', log)
    if not release or not finish:
        return 'missing release or contact end'
    if int(release[1]) != loop + 70 or int(finish[1]) != required:
        return f'contact timeline mismatch: loop={loop}, release={release[1]}, finish={finish[1]}'
    if alice:
        released = re.search(r'released attacker=\S+ settled=\w+ completed=true', log)
        if not released or released.start() <= release.start() or released.start() >= finish.start():
            return 'held partner did not release at contact completion'
    return None


_smoke('alice_hunt', 1200, [r'\[agitation\][^\n]* phase=ALICE_APPROACH -> ALICE_ACT',
    r'\[agitation\][^\n]* act release effect target=\S+ ticks=\d+ droplets=8 hearts=2\b',
    r'\[agitation\][^\n]* outcome (?:target=|refused)', r'\[agitation\][^\n]* contact ended target=',
    *(rf'\[agitation\][^\n]* finale beat=beat_{beat} line=heat_relief_{line} admitted=true ticks=\d+ climaxTick={tick}\b'
      for beat, line, tick in _sukkiri_beats)],
    forbid=[r'\[agitation\][^\n]* contact interrupted target=[^\n]* settled=true'])
next(s for s in S if s.id == 'alice_hunt').smoke['verify'] = lambda log: _sukkiri_timing(log, alice=True)
# The shared nest releases the young it may no longer hold as each one grows: three of the four cross BABY_END inside the window,
# and each released child then claims one of the two free ordinary nests standing beside the shared one.
def _family_nests_release(log):
    released = set(re.findall(r'\[home\] \w+ #\d+ uuid=(\S+)[^\n]* growth released shared nest ', log))
    if len(released) < 3:
        return f'young released from the shared nest: {len(released)} of 3 ({sorted(released)})'
    bedded = set(re.findall(r'\[home\] \w+ #\d+ uuid=(\S+)[^\n]* released child claimed a free nest ', log))
    left = sorted(released - bedded)
    return f'{len(left)} released young stayed bedless ({left})' if left else None


_smoke('family_nests', 4200, [r'\[home\][^\n]* growth released shared nest ', r'\[home\][^\n]* released child claimed a free nest '])
next(s for s in S if s.id == 'family_nests').smoke['verify'] = _family_nests_release
next(s for s in S if s.id == 'family_nests').smoke['readout'] = ['yukkuri status @e[tag=scn,type=mcyukkuri:reimu,distance=..48] raw']
_pair_release = [
    *(rf'\[family\][^\n]* courtship beat=beat_{beat} line=heat_relief_{line} [^\n]*climaxTick={tick} fatherSpoke=true motherSpoke=true\b'
      for beat, line, tick in _sukkiri_beats),
    r'\[family\][^\n]* courtship release partner=\S+ tick=\d+ requiredTicks=\d+ droplets=8 hearts=2\b',
    r'\[family\][^\n]* courtship release tail partner=\S+ tick=\d+ requiredTicks=\d+ droplets=4\b']
_smoke('adult_pairing', 1800, [r'\[family\][^\n]* courtship acceptance started ', *_pair_release,
                                 r'\[family\][^\n]* courtship contact ended [^\n]*conceived=true'])
next(s for s in S if s.id == 'adult_pairing').smoke['readout'] = [f'yukkuri status {sel("mother")} raw',
                                                                  f'yukkuri status {sel("father")} raw']
next(s for s in S if s.id == 'adult_pairing').smoke['verify'] = _sukkiri_timing
# The scene's own button ends the original partner; the widow may accept the candidate only once the ledger holds that death.
_smoke('widow_repair', 3600, [r'\[family\][^\n]* court refused reason=partner_alive\b',
                                r'\[family\] reimu[^\n]* witnessed kin death [^\n]*mate=true',
                                r'\[body\][^\n]* death settled[^\n]*cause=genericKill\b',
                                r'\[family\][^\n]* partnership formed [^\n]*allowWidow=true', *_pair_release,
                                r'\[family\][^\n]* courtship contact ended [^\n]*conceived=true'])
next(s for s in S if s.id == 'widow_repair').smoke['commands'] = [(600, f'kill {sel("old_partner")}')]
next(s for s in S if s.id == 'widow_repair').smoke['readout'] = [f'yukkuri status {sel("widow")} raw',
                                                                 f'yukkuri status {sel("new_partner")} raw']
_smoke('aftermath_growth', 3600, [r'\[family\][^\n]* stem drain stage', r'\[family\][^\n]* stem sprouted ',
                                r'\[family\][^\n]* stem ripe [^\n]*fruit=3\b'])
next(s for s in S if s.id == 'aftermath_growth').smoke['verify'] = _distinct(r'\[family\] reimu #\d+ uuid=(\S+)[^\n]* aftermath reserved ', 2, 'aftermath broods')
next(s for s in S if s.id == 'aftermath_growth').smoke['readout'] = [f'yukkuri status {sel("affected_growth")} raw',
                                                                   f'yukkuri status {sel("affected_burden")} raw']
next(s for s in S if s.id == 'aftermath_growth').smoke['gating_checks'] = ('END', 'FATE')
_smoke('aftermath_depletion', 3600, [r'\[family\][^\n]* aftermath reserved ', r'\[family\][^\n]* stem sprouted ',
                                    r'\[family\][^\n]* stem depletion warning ',
                                    r'\[family\][^\n]* stem drew body [^\n]*health=[\d.]+->0\.0\b',
                                    r'\[body\][^\n]* death settled[^\n]* stem_depletion=true',
                                    r'\[body\][^\n]* stem remains [^\n]*progress=0\.\d+ count=5 absent=0 placement=body'])
next(s for s in S if s.id == 'aftermath_depletion').smoke['readout'] = [
    f'yukkuri status {sel("affected_depletion")} raw', 'yukkuri body info @e[type=mcyukkuri:yukkuri_remains,distance=..40,limit=1,sort=nearest]']
next(s for s in S if s.id == 'aftermath_depletion').smoke['gating_checks'] = ('END', 'FATE')
_smoke('growth', 200, [r'\[growth\][^\n]* stage=BABY -> JUVENILE', r'\[growth\][^\n]* age boundary [^\n]*subadult=true',
                       r'\[growth\][^\n]* stage=JUVENILE -> ADULT', r'\[growth\][^\n]* age boundary [^\n]*elder=true',
                       r'\[growth\][^\n]* lifespan reached'])
_smoke('hop', 2400, [r'\[childhood\] chen[^\n]* jump learned landings=\d+ demonstrationSeen=\w+'])
_smoke('family_search', 7200, [r'\[family\][^\n]* child search reunited', r'\[family\][^\n]* parent dismissed ',
                               r'\[family\][^\n]* child call started ', r'\[family\][^\n]* child heard parent call ',
                               r'\[family\][^\n]* glass vigil started ', r'\[family\][^\n]* glass farewell '])
next(s for s in S if s.id == 'family_search').smoke['verify'] = _family_call_verify
_smoke('family_search_pane', 2400, [r'\[family\][^\n]* glass vigil started ', r'\[action\][^\n]* break id=bash ',
                                    r'\[family\][^\n]* glass crossed ', r'\[family\][^\n]* child search reunited '])
next(s for s in S if s.id == 'family_search_pane').smoke['verify'] = _family_pane_verify
_smoke('rain_open', 1200, [r'\[weather\][^\n]* shelter response started', r'\[needs\][^\n]* soaking damage '])
_smoke('treatment_scars', 1600, [r'\[body\][^\n]* wound healing ', r'\[body\][^\n]* scar (?:formed|not formed) from '])
_smoke('wound_materials', 400, [], forbid=[r'\[body\][^\n]* death settled'])
_smoke('wound_sources', 320, [
    r'\[interaction\][^\n]* operation started; [^\n]* pose=PIN duration=240 reserved_item=mcyukkuri:pin_tool count=1',
    r'\[interaction\][^\n]* operation ended; [^\n]* pose=PIN ',
    r'\[interaction\][^\n]* binding returned to world; [^\n]* item=mcyukkuri:pin_tool count=1',
    r'\[interaction\][^\n]* operation started; [^\n]* pose=BOX duration=400 reserved_item=mcyukkuri:iron_enclosure count=1',
    r'\[interaction\][^\n]* binding returned to world; [^\n]* item=mcyukkuri:iron_enclosure count=1',
])
next(s for s in S if s.id == 'wound_sources').smoke['commands'] = [
    # The headless operator is not a level player, so the ordinary operator-departure exit returns each tool on the next tick.
    (4, f'execute at {sel("blunt")} positioned ~ ~ ~1 run yukkuri scenario tool_click {sel("blunt")} enclose'),
    (6, f'execute at {sel("cut")} positioned ~ ~ ~1 run yukkuri scenario tool_click {sel("cut")} pin'),
]
_smoke('hatless_scorn', 3600, [r'\[contempt\][^\n]* scorn target=',
                                 rf'\[contempt\] marisa #\d+ uuid={_uuid_text("hatless_scorn", "b1")} [^\n]* scorn target='])
_smoke('hatless_monster', 1200, [r'\[contempt\][^\n]* monster target=', r'\[contempt\][^\n]* scorn target='])
_smoke('feed_wild', 2400, [r'\[npc-care\][^\n]* action=begin_meal '])
_smoke('shop_build', 1600, [r'\[npc-care\][^\n]* action=refill_bowl ', r'\[npc-care\][^\n]* action=clean_toilet ',
                            r'\[shop\][^\n]* action=dispose_waste result=stored ', r'\[shop\][^\n]* action=register shop=',
                            r'\[interaction\][^\n]* action=pin reason=shop'],
       forbid=[r'\[shop\][^\n]* action=job result=failed ', r'\[shop\][^\n]* action=job result=put_down ',
               r'\[interaction\][^\n]* operation started; [^\n]* pose=PIN '])
next(s for s in S if s.id == 'shop_build').smoke['commands'] = [
    (100, f'execute at @e[type=mcyukkuri:alice,distance=..60,nbt={{NeoForgeData:{{HumanCommerce:{{Use:"stock"}}}}}},limit=1] positioned ~ ~ ~.6 run yukkuri scenario tool_click {BOOTH.format("alice", 3)} pin'),
]
# Headless runs have no player at the window: two residents stop in the aisle in front of the street pen and the predator room (smoke only; NoAI keeps them there).
def _aisle_customer(dx, name):
    return (f'summon mcyukkuri:caretaker_sister ~{dx} ~2 ~6.5 {{Tags:["scn","{tag(name)}"],PersistenceRequired:1b,NoAI:1b,Rotation:[0f,0f],'
            'HumanJob:0,HumanAttitude:1,HumanInitialized:1b}')
_smoke('shop_window', 1200, [rf'\[shop\] {kind} [^\n]* window start mode={mode} ' for kind, mode in
                             (('chen', 'SHY'), ('alice', 'PROUD'), ('remilia', 'SULK'))],
       forbid=[r'\[shop\][^\n]* action=job result=failed '],
       readout=[f'yukkuri status {BOOTH.format(kind, grade)} raw' for kind, grade in (('chen', 0), ('alice', 3), ('remilia', 2))])
# Each customer stands in front of a pen's glass clear of its door frame (posts and doors block the view).
next(s for s in S if s.id == 'shop_window').smoke['commands'] = [(100, _aisle_customer(16.5, 'customer_west')), (100, _aisle_customer(3.5, 'customer_east'))]
_smoke('shop_buy', 1600, [r'\[shop\][^\n]* action=job result=start kind=BUY ', r'\[shop\][^\n]* action=job result=reach kind=BUY ',
                          r'\[shop\] chen [^\n]* window witness kind=',
                          r'\[shop\][^\n]* action=buy_live result=committed [^\n]* emeralds_used=0\b',
                          r'\[shop\][^\n]* action=buy_live result=delivered [^\n]*placement=counter '],
       forbid=[r'\[shop\][^\n]* action=buy_live result=cancelled', r'\[shop\][^\n]* action=job result=failed '])
next(s for s in S if s.id == 'shop_buy').smoke['commands'] = [
    (200, f'yukkuri human shop buy {SHOP_STAFF} {BOOTH.format("alice", 3)} {_uuid_text("shop_buy", "buyer")}')]
_smoke('shop_restock', 3600, [r'\[shop\][^\n]* action=restock result=done ', r'\[shop\][^\n]* action=restock result=ship_marked ',
                              r'\[shop\][^\n]* action=ship_out result=packed ', r'\[shop\][^\n]* action=stow_basket result=stored ',
                              r'\[shop\][^\n]* action=restock result=arrival ', r'\[shop\][^\n]* action=settle result=released '],
       forbid=[r'\[shop\][^\n]* action=job result=put_down '])
_basket_moves = (
    ('basket_young', 0, 2, 4, 2), ('basket_baby_0', 2, 3, 4, 1), ('basket_baby_1', 3, 4, 4, 0),
    ('wild', 0, 4, 6, 2), ('basket_baby_2', 4, 5, 6, 1), ('basket_baby_3', 5, 6, 6, 0),
)
_smoke('transport', 320, [
    *[rf'\[carrier\][^\n]* action=capture result=committed target={_uuid_text("transport", name)} token=\S+ load={before}->{after} capacity={cap}\b'
      for name, before, after, cap, _ in _basket_moves],
    *[rf'\[carrier\][^\n]* action=release result=committed target={_uuid_text("transport", name)} token=\S+ remaining={remaining}\b'
      for name, _, _, _, remaining in _basket_moves],
    *[rf'\[interaction\][^\n]* uuid={_uuid_text("transport", name)} [^\n]* action=capture reason=carrier_full\b'
      for name in ('basket_baby_2', 'basket_baby_4')],
    r'\[transport\][^\n]* action=release result=refused token=\S+ [^\n]* reason=(?:collision|occupied_ground)\b',
    r'\[carrier\][^\n]* action=upgrade_check occupants=0 matched=true result=mcyukkuri:yukkuri_carrier_large\b',
    r'\[carrier\][^\n]* action=upgrade_check occupants=3 matched=false result=minecraft:air\b',
], forbid=[rf'\[carrier\][^\n]* action=capture result=committed target={_uuid_text("transport", "basket_baby_4")}\b'])
next(s for s in S if s.id == 'transport').smoke['commands'] = [
    (60, 'yukkuri scenario basket plain'),
    *[(61+i, f'yukkuri scenario basket spray {sel(name)}')
      for i, name in enumerate([row[0] for row in _basket_moves] + ['basket_baby_4'])],
    *[(180+i, f'yukkuri scenario basket capture {sel(name)}')
      for i, name in enumerate(('basket_young', 'basket_baby_0', 'basket_baby_1', 'basket_baby_2'))],
    (190, 'yukkuri scenario basket release ~-10 ~-1 ~4'),
    (191, 'yukkuri scenario basket release ~-10 ~-1 ~4'),
    (192, 'yukkuri scenario basket release ~-8 ~-1 ~4'),
    (193, 'yukkuri scenario basket release ~-6 ~-1 ~4'),
    (199, 'yukkuri scenario basket fine'),
    (200, 'yukkuri scenario basket upgrade_check'),
    *[(201+i, f'yukkuri scenario basket capture {sel(name)}')
      for i, name in enumerate(('wild', 'basket_baby_2', 'basket_baby_3', 'basket_baby_4'))],
    (205, 'yukkuri scenario basket upgrade_check'),
    *[(210+i, f'yukkuri scenario basket release ~{x} ~-1 ~7')
      for i, x in enumerate((-10, -8, -6))],
]

def _migration_housing(log):
    done = re.search(r'\[settlement\][^\n]* event=migrate_done\b[^\n]* households=(\d+)', log)
    if done:
        households = int(done[1])
        housed = len(set(re.findall(r'\[settlement\][^\n]* migration family housed [^\n]* family=(\S+)', log)))
        if 2 * housed <= households:
            return f'migration housed {housed}/{households} families by window end'
    return None


_smoke('colony_migrate', 7200, [r'\[settlement\][^\n]* event=migrate_done\b'])
next(s for s in S if s.id == 'colony_migrate').smoke['verify'] = _migration_housing

# Actor fates that are a scene's real path; scripts/smoke_fate.py fails every other death, drowning, suffocation, starvation and
# refusal loop. who: a pattern of a line the individual itself prints (omitted: any individual); match: a pattern of the fate line.
_THROWN = r'\[body\][^\n]* dismount [^\n]*cause=(?:thrown|hull_rock|brim_hard_rock)\b'   # a rider the hull really threw off
_STEPS_IN = r'\[action\][^\n]* start id=step_in\b'                                       # an owner wading out to her hat
_STEM_DRAW = dict(kind='starve', who=r'aftermath reserved ', match=r'accepted damage cause=starve\b.* stem_depletion=true\b',
                  why='the marked hit is an actual draw by the growing stem')
_FATES = {
    'stray_junk_headwear': [dict(kind='death', who=f'uuid={_uuid_text("stray_junk_headwear", "covered_stray")} ',
                                why='the smoke kills only the covered control to inspect its real remains')],
    'remains_forms': [dict(kind='death', match=r'cause=(?:starve|inFire|anvil|mob|freeze|drown)\b|internal=(?:old_age|mold)\b|core hurt \(mold\)',
                           why='all live actors are the eight death-form victims and the starved baby buried for its marker'),
                      dict(kind='drown', why='the pool victim is deliberately killed by drowning')],
    'den_family': [dict(kind='death', who=f'uuid={_uuid_text("den_family", "father")} ', match=r'cause=genericKill\b',
                        why='the smoke kills the father to widow the mother')],
    'family_absence': [dict(kind='death', who=f'uuid={_absent_uuid} ', match=r'cause=genericKill\b',
                            why='the meadow is outside the family’s sight when the real death occurs')],
    'widow_repair': [dict(kind='death', match=r'cause=genericKill\b',
                          why='the scene button ends the original partner through the real death path')],
    'settle_pit_famine': [dict(kind='starve', who=r'pit slave ', why='an empty pit: the slave at nutrition 0 really starves')],
    'aftermath_growth': [_STEM_DRAW],
    'aftermath_depletion': [_STEM_DRAW, dict(kind='death', who=r'aftermath reserved ', match=r'stem_depletion=true\b', why='she dies of the stem')],
    'growth': [dict(kind='death', who=r'lifespan reached', match=r'internal=old_age\b', why='the fifth starts one tick before its lifespan')],
    'leaf_swaddle_neglect_stages': [dict(kind='death', match=r'internal=swaddle_retention\b',
                                         why='the end of the row starts in the lethal stage with little health: it dies of retention')],
    'sacrifice': [dict(kind='death', who=f'uuid={_uuid_text("sacrifice", "parent")} ', match=r'cause=mob\b',
                       why='the parent that draws the hunt may be killed by it')],
    'hat_push': [dict(kind='drown', who=_THROWN, why='the fast boat capsizes the far hat and throws its rider in')],
    'hat_grounded_capsized': [dict(kind='drown', who=_THROWN, why='falling water and a hard rock throw the crews in')],
    'shake_capsize': [dict(kind='drown', who=_THROWN, why='three shakes capsize the hat and throw every rider in')],
    'self_rescue': [dict(kind='drown', who=r'scenario afloat prepared [^\n]*owner_aboard=false', why='the owner is pushed off the bank')],
    'shore_capsized_far': [dict(kind='drown', who=_STEPS_IN, why='her hat is beyond the bank reach: she wades in and swims')],
    'worn_hat_boat': [dict(kind='drown', who=f'uuid={_uuid_text("worn_hat_boat", "c")} ', why='the young drops into the water')],
    'cargo': [dict(kind='drown', who=_THROWN, why='the shaken full hat capsizes and throws its owner in'),
              dict(kind='drown', who=_STEPS_IN, why='the empty hat\'s owner starts ashore and wades out to reclaim it')],
    'hat_crowd': [dict(kind='drown', why='overloaded hats capsize and throw the families in'),
                  dict(kind='death', match=r'category=drowned\b', why='the scene keeps real water damage: survival is not guaranteed')],
    'rescue_child': [dict(kind='drown', who=f'uuid=(?:{_uuid_text("rescue_child", "c1")}|{_uuid_text("rescue_child", "k1")}) ',
                          why='both young are shoved off the bank to be rescued')],
    'rescue_no_seat': [dict(kind='drown', who=f'uuid={_uuid_text("rescue_no_seat", "c1")} ', why='the newborn floats in open water')],
    'birth_hat_own': [dict(kind='drown', who=_THROWN, why='the father\'s hat is shaken over'),
                      dict(kind='drown', who=r'birth landed [^\n]*outcome=WATER\b', why='a newborn may land in the water and be rescued')],
}
for _sid, _allow in _FATES.items():
    next(s for s in S if s.id == _sid).smoke['allow_fate'] = _allow


# Observation contracts only. Each path has ordered (emitter selector, seen predicate) steps;
# named regex groups bind identities and $subject is the actor selected by each.
def _scene_end(sid, final, *paths, forbid=()):
    scene = next(s for s in S if s.id == sid)
    paths = [path if isinstance(path, dict) else dict(steps=path) for path in paths]
    scene.smoke['milestones'] = paths
    scene.smoke['end'] = dict(final=final, paths=paths, forbid=forbid, bounds=scene.plot)
    views = scene.capture.get('views')
    if views is None:
        views = {'': {key: scene.capture[key] for key in ('follow', 'offset') if key in scene.capture}}
        scene.capture['views'] = views
    for view in views.values():
        view.setdefault('criteria', scene.watch)


_DRY = 'alive=true and w=false and distress=false and iwr=false'
_ASHORE = _DRY + ' and veh=false and att.kind=free and g=true'

for _exile_scene, _witnesses in (('colony_mold_exile', ['leader', 'witness']),
                                 ('den_mold_exile', ['healthy', 'grandparent', 'baby'])):
    _scene_end(_exile_scene, [('molded', _ASHORE), *((name, _DRY) for name in _witnesses)],
               [('molded', 'seen:settlement:mold exile protest'),
                ('molded', 'seen:settlement:mold exile departed'),
                ('molded', 'seen:settlement:mold exile appeals'),
                ('molded', 'seen:settlement:mold exile refused return'),
                ('molded', 'seen:settlement:mold exile withdrew after refusal')])

_scene_end('home_pane', [('bold',_ASHORE),('timid',_ASHORE)],
           [('bold','seen:action:start id=bash'),('bold','seen:action:hit id=bash'),
            ('bold','seen:home:pane opened'),('world','seen:home-claim:.*GATHER -> HOLD')])
_scene_end('home_open', [('bold',_ASHORE),('timid',_ASHORE)],
           [('world','seen:home-claim:.*GATHER -> HOLD')])

_scene_end('den_family', [('mother', _DRY), ('child', _ASHORE), ('father', 'alive=false')],
           [('mother', 'seen:habitat:home chosen'), ('mother', 'seen:forage:begin role='),
            ('mother', 'seen:forage:deposit home=')])
_scene_end('forage', [('mother', _DRY), ('father', _DRY), ('child', _DRY)],
           [('mother', 'seen:forage:begin role=FAMILY'),
            ('father', f'seen:family:household watched entrance provider={_uuid_text("forage", "mother")}'),
            ('mother', 'seen:forage:deposit home=.* moved=[1-9]'), ('child', 'seen:family:welcome home started')],
           [('father', f'seen:family:feed handed to meal child={_uuid_text("forage", "child")}'),
            ('child', f'seen:meal:swallowed item=.* feeder={_uuid_text("forage", "father")}')])
_scene_end('family_absence', [('absent', 'alive=false'), ('stayer', _DRY), ('older', _DRY), ('baby', _DRY)],
           [('absent', 'seen:forage:begin role=FAMILY'), ('absent', 'seen:body:death settled into remains'),
            ('stayer', f'seen:family:kin overdue kin={_absent_uuid}'), ('stayer', f'seen:family:missing kin call kin={_absent_uuid}')],
           [('older', f'seen:family:missing kin call kin={_absent_uuid}'),
            *[('older', f'seen:family:kin absence kin={_absent_uuid} phase={phase}') for phase in ('SEARCH', 'RETURN', 'MOVED_ON')],
            ('older', 'seen:meal:swallowed item=.* feeder=null')],
           [('baby', f'seen:family:missing kin call kin={_absent_uuid}'),
            ('baby', f'seen:family:kin absence kin={_absent_uuid} phase=MOVED_ON')],
           forbid=[('baby', f'seen:family:kin absence kin={_absent_uuid} phase=SEARCH')])
_scene_end('settle_snow', [('lead', _DRY)], [('actors', 'seen:habitat:home chosen')])
_scene_end('settle_pit_slave', [('slave', _DRY)],
           [('slave', 'seen:settlement:pit slave back in pit'),
            ('slave', 'seen:settlement:pit slave eats droppings')],
           forbid=[('slave', 'seen:mind:stage uneasy -> sweetened|non-yukkuri onset|died of non-yukkuri')])
_scene_end('settle_pit_famine', [('slave', 'alive=true and need.nut=0 and hp<10')],
           [('slave', 'seen:settlement:pit slave hungry reason=no_droppings'),
            ('slave', 'seen:needs:starvation damage')])
_scene_end('sleep', [('a', _DRY)],
           [('a', 'seen:home:bed crossing .*direction=in .*opening=true'),
            ('a', 'seen:sleep:begin source=life occupancy=INSIDE_SUPPORTED'),
            ('a', 'seen:sleep:end source=life.*stretched=true'),
            ('a', 'seen:home:bed crossing .*direction=out .*opening=true')])
_scene_end('birth_land_caught', [('mother', _DRY), ('father', _DRY + ' and hold.hat.state=worn'),
                                 ('child_of:mother', _ASHORE)],
           [('child_of:mother', 'seen:family:birth landed .*outcome=(?:HAT|GROUND)')],
           dict(each='caught_child_of:mother', steps=[
               ('$subject', 'seen:family:birth landed .*outcome=HAT'),
               ('father', r'seen:family:carry put down .*cause=release .*child=$subject\b')],
               post=[('$subject', _ASHORE)]),
           dict(each='caught_child_of:mother', steps=[
               ('$subject', 'seen:family:birth landed .*outcome=HAT'),
               ('father', r'seen:family:carry put down .*cause=release .*child=$subject\b'),
               ('father', 'seen:action:end id=take_prop outcome=DONE detail=prop=hat worn=true')]))
_scene_end('birth_stem_caught', [('mother', _DRY), ('father', _DRY), ('child_of:mother', _DRY)],
           [('child_of:mother', 'seen:family:birth landed .*outcome=(?:HAT|GROUND)')])
_scene_end('birth_nest_safe', [('mother', _ASHORE), ('child_of:mother', _ASHORE)],
           [('mother', 'seen:family:delivery complete')],
           [('child_of:mother', 'seen:family:birth landed .*outcome=(?:CUSHION|GROUND)')])
# No artificial survival requirement for the exposed damage controls: the wetness boundary and real damage are the path.
_scene_end('rain_open', [('a', _DRY), ('b', _DRY)],
           [('a', 'seen:weather:shelter response started')],
           [('wet_ordinary', 'seen:needs:soaking damage')], [('wet_control', 'seen:needs:soaking damage')])
_scene_end('rain_huddle', [('shy', _DRY), ('mate', _DRY), ('alone', _DRY)],
           [('shy', 'seen:weather:huddle sought partner='), ('shy', 'seen:weather:huddle pressed partner='),
            ('shy', 'seen:weather:thunder clap heard .*stage=HUDDLE')],
           [('alone', 'seen:weather:sheltered alone pose=tremble')])
_scene_end('aftermath_growth', [('affected_growth', _DRY),
                               ('affected_burden', _DRY),
                               ('source_growth', _DRY), ('source_burden', _DRY)],
           [('affected_growth', 'seen:family:aftermath reserved'), ('affected_growth', 'seen:family:stem sprouted'),
            ('affected_growth', 'seen:meal:swallowed item=minecraft:apple'), ('affected_growth', 'seen:family:stem ripe')],
           [('affected_burden', 'seen:family:aftermath reserved'), ('affected_burden', 'seen:family:stem sprouted'),
            ('affected_burden', 'seen:family:stem drain stage 2->3'), ('affected_burden', 'seen:family:stem ripe')])
_scene_end('aftermath_depletion', [('affected_depletion', 'alive=false'), ('source_depletion', _DRY)],
           [('affected_depletion', 'seen:family:aftermath reserved'),
            ('affected_depletion', 'seen:family:stem sprouted'),
            ('affected_depletion', 'seen:family:stem drew body'),
            ('affected_depletion', 'seen:body:death settled.*stem_depletion=true'),
            ('affected_depletion', 'seen:body:stem remains.*count=5 absent=0 placement=body')])
# World phase events belong to the same captured group; arrival must name this scene's leader.
_scene_end('colony_migrate', [('lead', _DRY), ('migrant_baby', _ASHORE)],
           [('world', r'seen:settlement:migration phase=DISCUSS group=(?P<group>\S+)'),
            ('world', r'seen:settlement:migration phase=PREPARE group=$group\b'),
            ('world', r'seen:settlement:migration phase=MARCH group=$group\b'),
            ('world', r'seen:settlement:migration pivot arrived group=$group .*leader=$lead\b')],
           dict(each='migrant_baby', steps=[
               ('parent_of:$subject', r'seen:family:carry pickup .*purpose=MIGRATE child=$subject childStage=BABY\b'),
               ('parent_of:$subject', r'seen:family:carry put down reason=arrived_destination .*purpose=MIGRATE child=$subject\b'),
               ('$subject', r'seen:settlement:migration reached .*dependent=true')],
               post=[('$subject', _ASHORE)]))
_scene_end('carry_home', [(f'{kind}_baby', _ASHORE) for kind in ('marisa', 'reimu', 'chen')],
           *[dict(each=f'{kind}_baby', autonomous_home=True, steps=[
              (f'{kind}_parent', r'seen:family:^carry pickup hold=\S+ .*child=$subject\b'),
              (f'{kind}_parent', r'seen:family:carry (?:set down started reason=arrived_home child=$subject\b|release target child=$subject placement=nest|lowering hold=\S+ child=$subject\b)'),
              (f'{kind}_parent', r'seen:family:carry put down reason=\S+ .*cause=release .*child=$subject .*nest=(?:INSIDE_SUPPORTED|PARTIAL)')],
              post=[('$subject', _ASHORE + f' and nest_of.{kind}_parent=true and nest=INSIDE_SUPPORTED')])
             for kind in ('marisa', 'reimu', 'chen')])
next(s for s in S if s.id == 'carry_home').smoke['end']['carried_min'] = 2
_scene_end(_walk_home, [(child, _ASHORE) for child in ('baby_a', 'baby_b', 'small_child')],
           *[dict(each=child, steps=[
               ('mother', rf'seen:family:^carry pickup .*purpose=RETRIEVE child=$subject\b'),
               ('mother', rf'seen:family:carry put down .*cause=release purpose=RETRIEVE child=$subject .*nest=(?:INSIDE_SUPPORTED|PARTIAL)')],
               post=[('$subject', _ASHORE + ' and nest_of.mother=true and nest=INSIDE_SUPPORTED')])
             for child in ('baby_a', 'baby_b')],
           dict(each='small_child', autonomous_home=True, steps=[
               ('mother', r'seen:family:^carry pickup .*purpose=RETRIEVE child=$subject\b'),
               ('mother', r'seen:family:carry put down .*cause=release purpose=RETRIEVE child=$subject .*nest=(?:INSIDE_SUPPORTED|PARTIAL)')],
               post=[('$subject', _ASHORE + ' and nest_of.mother=true and nest=INSIDE_SUPPORTED')]))
_scene_end(_flight_home, [(child, _DRY) for child in ('baby_a', 'baby_b')],
           *[dict(each=child, autonomous_home=True, steps=[
               ('mother', rf'seen:family:^carry pickup .*purpose=PROTECT child=$subject\b'),
               ('mother', rf'seen:family:carry put down .*cause=release purpose=PROTECT child=$subject .*nest=(?:INSIDE_SUPPORTED|PARTIAL)')],
               post=[('$subject', _ASHORE + ' and nest_of.mother=true and nest=INSIDE_SUPPORTED')])
             for child in ('baby_a', 'baby_b')])
next(s for s in S if s.id == _flight_home).smoke['end']['carried_min'] = 1
_scene_end('hat_push', [('a', 'alive=true and distress=false'), ('fast', 'alive=true and distress=false')],
           [('hat_of:fast', 'seen:hat:capsized cause=impact .*count=1')],
           forbid=[('hat_of:a', 'seen:hat:capsized')])
_scene_end('hat_current', [('a', _DRY), ('child', _DRY)],
           dict(steps=[('a', 'seen:body:dismount .*cause=grounded'),
                       ('a', 'seen:headwear:received loose accessory')], post=[('a', _ASHORE), ('child', _ASHORE)]))
_scene_end('shake_capsize', [('a', _DRY + ' and place=cabin'), ('k1', _DRY), ('k2', _DRY)],
           [('a', 'seen:body:dismount .*cause=thrown'), ('a', 'seen:action:end id=right outcome=DONE'),
            ('a', 'seen:body:mount .*cause=climb')])
_scene_end('self_rescue', [('a', _DRY + ' and place=cabin')],
           [('a', 'seen:water:escape start'), ('a', 'seen:body:mount .*cause=climb'),
            ('a', 'seen:water:escape done')])
_scene_end('shore_capsized', [('a', _DRY)],
           dict(steps=[('a', 'seen:action:end id=beckon outcome=DONE detail=at_bank'),
                       ('a', 'seen:action:end id=right outcome=DONE'), ('a', 'seen:headwear:reclaim done how=worn')],
                post=[('a', _ASHORE + ' and hold.hat.state=worn')]))
# Overloaded families retain real risk; no prescribed survival for a deliberate capsize. Surviving young
# must still be safe (ORPHAN remains independent); the load response itself is the observable path.
_scene_end('hat_crowd', [('early', _DRY), ('normal', 'alive=true and distress=false')],
           [('early', 'seen:aquatic:load heavy .*reason=unload_ashore'),
            ('early', 'seen:intent:end id=come_ashore result=DONE')],
           [('normal', 'seen:family:parent dismissed child=')])
_scene_end('hat_swap', [(name, _DRY) for name in ('land_worn', 'aqua_worn', 'land_owner', 'aqua_owner', 'kid_owner')],
           [('aqua_owner', 'seen:headwear:reclaim done how=worn')])
_scene_end('sail', [('a', _DRY)],
           dict(steps=[('a', 'seen:aquatic:stick taken'), ('a', 'seen:aquatic:set_out complete'),
                       ('a', 'seen:action:end id=paddle outcome=DONE'),
                       ('a', 'seen:intent:end id=come_ashore result=DONE'), ('a', 'seen:meal:swallowed item=minecraft:apple')],
                post=[('a', _ASHORE + ' and hold.hat.state=worn')]))
_scene_end('afloat_family', [('a', _DRY), ('c1', _DRY)],
           dict(steps=[('a', 'seen:family:carry grip .*hold=braid'), ('c1', 'seen:body:seat landed .*cause=braid_set_down'),
                       ('a', 'seen:aquatic:set_out complete .*aboard=1/1')],
                post=[('c1', _DRY + ' and aboard.a=true')]))
_scene_end('afloat_stick', [('a', _DRY + ' and hold.kit.item=minecraft:stick')],
           [('a', 'seen:aquatic:stick target chosen'), ('a', 'seen:aquatic:stick taken .*stowed_in=hat')])
_scene_end('kid_boat_play', [('a', _DRY), ('c1', _DRY), ('c2', _DRY)],
           [('c1', 'seen:aquatic:play kind=(?:kid_brim_splash|kid_mimic_paddle) start spot=brim'),
            ('c1', 'seen:aquatic:play (?:return inside|stays on brim).*reason=fear:thunder')],
           [('c2', 'seen:aquatic:play kind=hat_cradle start spot=head reason=head_dry')])
_scene_end('rescue_child', [('a', _DRY), ('c1', _DRY), ('k1', _DRY)],
           *[[('a', f'seen:family:carry grip .*uuid={_uuid_text("rescue_child", child)}'),
              ('a', f'seen:aquatic:rescue end result=DONE outcome=(?:rescued|set_down) faller={_uuid_text("rescue_child", child)}')]
             for child in ('c1', 'k1')])
_scene_end('rescue_no_seat', [('a', _DRY), ('child_of:a', _DRY)],
           dict(steps=[('a', f'seen:family:carry grip .*uuid={_uuid_text("rescue_no_seat", "c1")}'),
            ('a', 'seen:action:end id=hop outcome=DONE'), ('a', 'seen:action:end id=release outcome=DONE detail=released'),
            ('a', f'seen:aquatic:rescue end result=DONE outcome=set_down faller={_uuid_text("rescue_no_seat", "c1")}')],
                post=[('a', _ASHORE + ' and hold.hat.state=worn and carry_empty=true'), ('child_of:a', _ASHORE)]))
_scene_end('birth_boat_missed', [('mother', _DRY), ('child_of:mother', _DRY)],
           [('mother', 'seen:aquatic:birth shore return'), ('mother', 'seen:action:end id=hop outcome=DONE'),
            ('mother', 'seen:family:delivery complete')],
           dict(each='newborn_of:mother', steps=[('$subject', 'seen:family:birth landed .*outcome=(?:HAT|GROUND|NEST)'),
                                                ('$subject', 'seen:meal:swallowed .*prepared=true')],
                post=[('mother', _ASHORE), ('$subject', _DRY + ' and near.parents<=4')]))
_scene_end('birth_hat_own', [('mother', _DRY), ('father', _DRY), ('child_of:mother', _DRY)],
           [('mother', 'seen:aquatic:birth route=own_hat reason=fit'), ('mother', 'seen:family:delivery complete')],
           [('father', 'seen:aquatic:guard start'), ('father', 'seen:aquatic:guard arrive')],
           [('father', 'seen:aquatic:stick taken .*stowed_in=hat')],
           [('child_of:mother', 'seen:meal:swallowed .*prepared=true')])
_scene_end('first_voyage', [('c1', _DRY)],
           dict(steps=[('c1', 'seen:aquatic:first_voyage start'), ('c1', 'seen:aquatic:first_voyage rounded'),
                       ('c1', 'seen:childhood:sailing learned how=first_voyage'), ('c1', 'seen:aquatic:first_voyage success')],
                post=[('c1', _ASHORE + ' and hold.hat.state=worn')]))
_scene_end('family_search', [('search_parent', _DRY), ('search_child', _DRY), ('lost_mother', _DRY), ('lost_child', _DRY)],
           [('search_parent', 'seen:family:child call started'), ('search_child', 'seen:family:child heard parent call'),
            ('search_parent', 'seen:family:child search reunited')],
           [('lost_mother', 'seen:family:glass vigil started'), ('lost_child', 'seen:family:glass child at stand'),
            ('lost_mother', 'seen:family:glass farewell'), ('lost_mother', 'seen:family:parent dismissed')])
_scene_end('family_search_pane', [('pane_parent', _DRY), ('pane_child', _DRY)],
           [('pane_parent', 'seen:family:glass vigil started'), ('pane_child', 'seen:family:glass child at stand'),
            ('pane_parent', 'seen:action:break id=bash'), ('pane_parent', 'seen:family:glass crossed'),
            ('pane_parent', 'seen:family:child search reunited')])
