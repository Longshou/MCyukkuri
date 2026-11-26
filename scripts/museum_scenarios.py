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
import math
import sys
import uuid
from itertools import pairwise
from pathlib import Path

sys.dont_write_bytecode = True
from museum_dsl import (yk, yk_on_nest, human, item, block, large_nest, box, shed, furniture, home, sel, sel_all, tag, say, uid, uuid_nbt, family, remains, aquatic_spawn, _text)  # noqa: E402
from museum_fields import FIELDS, BY_ID, FEET, den  # noqa: E402

# Scene numbers: the field's code and a two-digit index (``PD-03``), so a visitor can name a scene out loud. The file is the
# registry of them: its ``numbers`` entries are appended in field order and are never reordered or renumbered, so a scene
# keeps its number when other scenes are added, removed or the deck is reordered (a removed scene keeps its line and its
# number is not given to another one). Field codes are fixed there too, one per field.
NUMBERS = json.loads((Path(__file__).with_name('museum_scene_numbers.json')).read_text(encoding='utf-8'))

# Landed and fed, with newborn care finished. Crawling development determines whether it can jump.
CHILDHOOD_CRAWLING_READY = 'Version:2,Newborn:1b,Landed:1b,FirstMealDone:1b,FirstToiletDone:1b,Crawled:0b'
CHILDHOOD_HOPPING = 'Version:2,Newborn:1b,Landed:1b,FirstMealDone:1b,FirstToiletDone:1b,Crawled:1b,CrawlDistance:0.75d'
OWNED = 'NeoForgeData.HumanCommerce'
# A subadult in the fixed age interval, ready to live independently.
TEEN_FAMILY = ('Version:2,Initialized:1b,Stage:"JUVENILE",Age:276000L,Nourishment:276000L,Maturity:336000L,Lifespan:1056000L,AdultScale:1f,Scale:0.72f')
LATE_COMMANDS = ('yukkuri scenario home', 'yukkuri scenario twigs', 'yukkuri scenario birth', 'yukkuri scenario eggs', 'yukkuri scenario afloat',
                 'yukkuri scenario sumo')
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
    def __init__(self, sid, field, title, hint, watch, wait, lines, give=(), env=None, spot=None, retry='按 M，在「实用」里重开',
                 asks='', note='', sub=None, vantage=(0, 14), reads=(), late=(), at_spawn=(), smoke=None, capture=None):
        self.id, self.field, self.title, self.hint = sid, field, title, hint
        # self.number is set once every scenario exists (``_number_scenes``), so that the pond tour order is known.
        self.watch, self.wait, self.retry, self.asks, self.note = watch, wait, retry, asks, note
        self.lines, self.give, self.env = surface_demo_lines(sid,lines), list(give), env or {}
        # settings that need the individuals to stand on the ground (a conception, a boat, a landing-dependent survey) run two seconds after the start hold is released;
        # `at_spawn` is only for commands that need no grounded body (a home works on a just-summoned one)
        deferred = [i for i, line in enumerate(self.lines) if line.startswith(LATE_COMMANDS)]
        self.late_lines = [self.lines[i] for i in deferred]
        # ``at_spawn``: commands run in the same tick as the summons, so the setting is in force before the first plan
        # (a home a scene gives its bodies, which would otherwise first head for the nearest free nest)
        self.lines = [line for i, line in enumerate(self.lines) if i not in deferred] + surface_demo_lines(sid, at_spawn)
        f = BY_ID[field]
        self.anchor = f.spots[spot] if spot else f.anchor
        self.plot = f.plot_rect(*self.anchor)
        self.sub = sub                 # name of the plot cleared before this scenario (default: the whole field)
        self.vantage = vantage         # (dx, dz[, yaw[, pitch[, dy]]]), using terrain height unless dy is given
        self.reads = list(reads)       # (caption, command) readouts that belong to this scenario
        self.smoke = smoke             # optional {ticks, expect, forbid}; runner only, never emitted into the pack
        if field == 'pond' and self.smoke is not None:
            self.smoke = {**self.smoke, 'forbid': [*self.smoke.get('forbid', ()), _POND_SMOKE_FORBID]}
        self.capture = capture if capture is not None else dict(shots=(1, 40, 200, 600), sequences=((60, 160, 2),))
        self.late = self.late_lines + surface_demo_lines(sid,late)   # commands that need the individuals to stand on the ground first: run two seconds after the release
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
    comparisons = {'wound_materials', 'thermal_body', 'wound_sources', 'wound_leak', 'hatless_monster', 'core_exposed'}
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


def _uuid_text(sid, slot):
    return str(uuid.UUID(bytes=b''.join((n & 0xffffffff).to_bytes(4, 'big') for n in uid(sid, slot))))


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
    # Let the family choose its own home and wait for a separation the attached mother can actually close.
    mother = f'@e[tag={tag("mother")},distance=1.5..5,limit=1]'
    for x, y, z in field.doorways.values():
        doorway = f'~{x - origin_x} ~{y - FEET} ~{z - origin_z}'
        claims = ' '.join(f'if data block {doorway} Residents[{{Id:{uuid_nbt(member)}}}]' for member in (_dm, _df, _dc))
        yield Retry(f'execute {claims} as {father} at @s if entity {mother} run kill @s', father, tries=80, every=20, first=400)


add('den_family', 'den', '一家三口住进土丘洞', '一家三口住进土丘洞',
    '父母和子油自己认领同一个土丘洞。三只刚入住、家里还缺粮时，场景让父油死亡。看母油目睹后的哀悼过程：先愣住再哭喊，'
    '起初转向遗体，依恋高且遗体近、路通时会走到旁边垂头抽泣。缓和时，母油为伴侣许诺照顾孩子，子油会按自己的性格对粑粑说话。'
    '随后慢慢出洞采草、存粮，走得比平时慢；'
    '最后表情恢复，回想一次后说「连你的那份一起油库里」。孩子吃到食物。洞口的草已吃过，外面还有草。',
    '入住约半分钟到一分钟，之后再看数分钟（可五倍速）', [
        yk('reimu', 0, 3, 'mother', uuid=_dm, yaw=180, lead=True, kin=f'Partner:{uuid_nbt(_df)}', **pair_ready()),
        yk('marisa', 2, 3, 'father', uuid=_df, yaw=180, kin=f'Partner:{uuid_nbt(_dm)}', **pair_ready()),
        # Starts above appetite so the child's first demand develops during the handoff.
        yk('reimu', 1, 4, 'child', stage='child', uuid=_dc, yaw=180, nutrition=80, kin=f'Mother:{uuid_nbt(_dm)},Father:{uuid_nbt(_df)}'),
        box(-7, 0, 1, 7, 1, 8, 'minecraft:air', 'replace minecraft:short_grass')],
    env=DAY, spot='family', vantage=(0, 12),
    late=list(_den_family_death()),
    note='用场景读数查看母油的 Load、Delivered 和孩子营养。若家中已有足够存粮，母油会留在窝里用现成的粮照料孩子。',
    reads=[('读家的识别与母油觅食', f'yukkuri status {sel("mother")} raw'),
           ('读孩子生活', f'yukkuri status {sel("child")} raw')])

_em, _ef, _ec, _eg = (uid('den_mold_exile', name) for name in ('healthy', 'molded', 'baby', 'grandparent'))
add('den_mold_exile', 'den', '油霉亲油离巢', '油霉亲油离巢',
    '健康亲油（冷淡、不友善）先吓一跳「霉菌先生」，鼓脸警告，再辱骂着上前推；年长亲油（依恋高）含泪道歉、不推。'
    '患病亲油（依恋高、胆小）先愣住，再央求「我还能油库里」，被推开后转身离开，途中两次回头、最后一次道别；赤油看见它转身时会哭喊「别走」，留在健康亲油照护下。'
    '患病亲油出了家的范围后先坐着哀悼一阵，之后回来央求一次，被拒后离开。',
    '约 3–4 分钟', [
        yk('reimu', 0, 1, 'healthy', uuid=_em, yaw=0, label='健康亲油', friendliness=24, boldness=35,
           attachment=28, skills={'mold_lore': 2}, kin=f'Partner:{uuid_nbt(_ef)}', **pair_ready()),
        yk('marisa', 0, 3, 'molded', uuid=_ef, lead=True, label='患油霉的亲油', attachment=80, boldness=30,
           kin=f'Mother:{uuid_nbt(_eg)},Partner:{uuid_nbt(_em)}', **pair_ready()),
        yk('reimu', -1, 1, 'grandparent', uuid=_eg, yaw=0, label='年长亲油', friendliness=86, attachment=90,
           skills={'mold_lore': 2}, **pair_ready()),
        yk('reimu', 0, 0, 'baby', stage='baby', uuid=_ec, yaw=0, label='赤油', nutrition=100,
           kin=f'Mother:{uuid_nbt(_em)},Father:{uuid_nbt(_ef)}'),
        box(-4, 0, 6, 23, 0, 13, 'minecraft:short_grass', 'keep')],
    late=[*[f'yukkuri scenario home {sel(n)} ~ ~ ~-1' for n in ('healthy', 'molded', 'grandparent', 'baby')],
          f'yukkuri scenario bond {sel("grandparent")} {sel("molded")}',
          # Stage 3 has the same visible mould as stage 5 and still harms the exile every 400 ticks.
          set_status('molded', 'health.mold', 3)],
    give=['mcyukkuri:mold_remover'], env=DAY, spot='family', vantage=(4, 16),
    note='等患病亲油回头被拒后，可用去霉药，看它能否重新认领原洞。',
    reads=[('读患病亲油与住所', f'yukkuri status {sel("molded")} raw'),
           ('读赤油与住所', f'yukkuri status {sel("baby")} raw')])


def _mold_family(sid, keep):
    names = ('mother', 'father', 'child', 'baby')
    ids = {n: uid(sid, n) for n in names}
    mother, father, child, baby = (_uuid_text(sid, n) for n in names)
    kin = kin_of(Mother=ids['mother'], Father=ids['father'])
    unaware = 'YukkuriSkills:{Assessed:["mold_lore"]}'
    aware = {'mold_lore': 2}
    needs = dict(nutrition=100, energy=100, hygiene=30 if keep else 95, rest_wait=220 if keep else 1200)
    if keep:
        spots = ((-1, -3), (1, -3), (0, -3), (0, -2))
    else:
        spots = ((-1.5, 4), (1.5, 4.2), (0, 5.2), (3, 5))
    title = '舍不得赶走，全家染上油霉' if keep else '油霉子油被亲油狠心赶走'
    lines = [
        yk('reimu', *spots[0], 'mother', uuid=ids['mother'], yaw=0, label='母油', skills=aware, adult_scale=.88 if keep else 1,
           intelligence=50, friendliness=70 if keep else 25, boldness=40 if keep else 60,
           attachment=85 if keep else 30, self_control=35 if keep else 40, kin=kin_of(Partner=ids['father']), **needs),
        yk('marisa', *spots[1], 'father', uuid=ids['father'], yaw=0, label='父油', adult_scale=.88 if keep else 1,
           intelligence=55 if keep else 60, friendliness=70 if keep else 50, boldness=55 if keep else 45,
           attachment=60 if keep else 50, self_control=70 if keep else 65, kin=kin_of(Partner=ids['mother']),
           skills=None if keep else aware, extra=unaware if keep else '', **needs),
        yk('reimu', *spots[2], 'child', uuid=ids['child'], yaw=0, label='有斑点的赤油' if keep else '有斑点的子油',
           stage='baby' if keep else 'child', lead=True, kin=kin,
           attachment=(40, 85), boldness=(30, 65), friendliness=(30, 60), self_control=50, extra=unaware, **needs),
        yk('marisa', *spots[3], 'baby', uuid=ids['baby'], label='赤油', stage='baby', kin=kin, extra=unaware, **needs),
        *[home(n, 0, -1) for n in names],
        box(-4, 0, 6, 12, 0, 13, 'minecraft:short_grass', 'keep')]
    late = [f'yukkuri scenario bond {sel(n)} {sel("child")}' for n in names[:2]] + [set_status('child', 'health.mold', 4)]
    expect = [rf'uuid={who} [^\n]*mold child verdict child={child} verdict={verdict}\b'
              for who, verdict in ((mother, 'KEEP' if keep else 'DRIVE_COLD'), (father, 'KEEP' if keep else 'DRIVE_TEARFUL'))]
    if keep:
        x, z = BY_ID['den'].spots['family']

        def cell(dx, dz):
            return f'{((x + dx) << 38) | (((z + dz) & 0x3ffffff) << 12) | FEET}L'

        # The burrow renderer uses the catalog's large_wild_nest on this supported 3×3 floor.
        nest = cell(0, -3)
        floor = [nest] + [cell(dx, dz) for dx in range(-1, 2) for dz in range(-4, -1) if (dx, dz) != (0, -3)]
        lines.insert(0, block(0, 0, -1, f'mcyukkuri:forest_burrow[facing=south]{{Condition:100,LargeBeds:[L;{nest}],'
                              f'BedMaterials:[{{Pos:{nest},Materials:[{{id:"minecraft:oak_leaves",count:1}},{{id:"minecraft:short_grass",count:1}}]}}],'
                              f'NaturalBeds:[L;{",".join(floor)}],NaturalPile:{cell(-2, -4)},NaturalPileStand:{cell(-1, -4)}}}'))
        watch = '两位亲油舍不得赶走有斑点的赤油。留在洞内，看四口在野生大草窝各自的睡位躺好，白天醒来，入夜再睡。到第二夜结束时，全家会因接触陆续染病。醒来后，认得油霉的母油会惊叫，不认得的只喊痒。途中可用去霉药中断传染。'
        first_dawn, second_night, last_dawn = 1200, 2600, 4800  # Body ticks from the late setup.
        for when, clock in ((first_dawn, 'dawn'), (second_night, 'night'), (last_dawn, 'dawn')):
            late.append(Retry(f'function mcylab:util/time_{clock}', sel('mother'), tries=1, first=when))
        shared_bed = [x, FEET, z - 3]
        bed = re.escape(f'BlockPos{{x={x}, y={FEET}, z={z-3}}}')
        expect += [rf'uuid={who} [^\n]*home chosen [^\n]*bed={bed}' for who in (mother, father, child, baby)]
        expect += [rf'uuid={who} [^\n]*line event=keep_child\b' for who in (mother, father)]
        expect += [rf'uuid=(?:{father}|{baby}) [^\n]*line event=mold_itch\b',
                   rf'uuid={mother} [^\n]*line event=mold_self_aware\b']
        forbid = [r'mold exile departed']
    else:
        watch = '洞口母油冷着脸，父油哭着让孩子走。看子油怎样回应推挤：会顶嘴咒骂，也会含泪回头道别。亲油一路跟着推到孩子出了家门范围，才断绝照护，治好后也不再相认。'
        expect += [rf'mold exile push subject={child}\b', rf'uuid={child} [^\n]*mold exile departed scope=family\b',
                   rf'uuid={child} [^\n]*mold exile answer=(CURSE|FAREWELL|SHRUG)\b', r'line event=drive_child_tearful\b',
                   *[rf'uuid={who} [^\n]*parent dismissed child={child}\b' for who in (mother, father)]]
        forbid = [r'mold exile deferred reason=dependent_or_carry']
    add(sid, 'den', title, title, watch, '约 4 分钟，可倍速' if keep else '约 1 分钟', lines, late=late,
        give=['mcyukkuri:mold_remover'], env={'time': 'night'} if keep else DAY, spot='family',
        vantage=(0, -1, 180, 24, 0) if keep else (0, 8, 180, 8),
        reads=[('读全家身体与住所', 'yukkuri status @e[tag=scn,distance=..48] raw')],
        smoke=dict(ticks=40 + last_dawn + 200 if keep else 1400, expect=expect, forbid=forbid))

    if keep:
        def verify_nights(window, trace):
            family = {who: {} for who in (mother, father, child, baby)}
            slept = {who: set() for who in family}
            positions = {}
            woke = set()
            setup = None
            for line in trace:
                row = json.loads(line.split('[trace] ', 1)[1])
                if row['k'] == 'win' and row['scene'] == sid:
                    setup = row['start'] + 40
                if setup is None or row['k'] != 'st' or row['u'] not in family:
                    continue
                who, tick = row['u'], row['t'] - setup
                state = family[who]
                state.update(row['st'])
                if state.get('sleeping') and state.get('nest') == 'INSIDE_SUPPORTED' and state.get('home') == shared_bed:
                    slept[who].add(tick)
                    x, _, z = state['p']
                    for px, pz, width, reach in positions.setdefault(tick, []):
                        if max(abs(x-px), abs(z-pz)) < (width + state['sleepWidth']) / 2 or math.hypot(x-px,z-pz) < reach + state['sleepReach']:
                            return f'Overlapping sleepers in DN-12 at +{tick}'
                    positions[tick].append((x,z,state['sleepWidth'],state['sleepReach']))
                if first_dawn < tick < second_night and state.get('sleeping') is False:
                    woke.add(who)
            if setup is None:
                return 'Missing DN-12 scene clock'
            together = set.intersection(*slept.values())
            # See the first night's sleep and a full twelve-sample contact interval on the longer second night.
            for start, dawn, required in ((0, first_dawn, 100), (second_night, last_dawn, 1200)):
                streak = 0
                for tick in range(start, dawn):
                    streak = streak + 1 if tick in together else 0
                    if streak >= required:
                        break
                else:
                    return f'Before dawn +{dawn}: missing {required} consecutive ticks asleep together in the shared nest'
            if woke != family.keys():
                return 'A family member never woke between the two nights'
            infected = {child}
            for hit in re.finditer(r'\[body\][^\n]*?uuid=([\w-]+) t=(\d+) [^\n]*?mold caught from=([\w-]+)', window):
                who, tick, source = hit.groups()
                if who in family and setup <= int(tick) < setup + last_dawn:
                    if source not in infected or who in infected:
                        return f'Invalid family contact at t={tick}: {source} to {who}'
                    infected.add(who)
            if infected != family.keys():
                return 'Missing contact infection by second dawn: ' + ', '.join(sorted(family.keys() - infected))
        # Dig one edge after the family's second dawn. Check the core and both real drops before a resident can collect them.
        smoke = S[-1].smoke
        smoke.update(verify_trace=True, verify=verify_nights, commands=[
            (5000, 'setblock ~1 ~-1 ~-4 minecraft:air'),
            (5001, 'execute if block ~ ~ ~-1 mcyukkuri:forest_burrow unless data block ~ ~ ~-1 LargeBeds[0] '
                   'unless data block ~ ~ ~-1 Residents[{Bed:0}] positioned ~0.5 ~ ~-2.5 '
                   'if entity @e[type=item,distance=..2,nbt={Item:{id:"minecraft:oak_leaves",count:1}}] '
                   'if entity @e[type=item,distance=..2,nbt={Item:{id:"minecraft:short_grass",count:1}}] '
                   'run say MCY_BURROW_SALVAGED')])
        smoke['expect'].append(r'MCY_BURROW_SALVAGED')
    else:
        x, z = BY_ID['den'].spots['family']

        def verify_departure(window, trace):
            departure = re.search(rf'uuid={child} t=(\d+) [^\n]*mold exile departed scope=family', window)
            if not departure:
                return 'The child never left the family'
            if not re.search(rf'\[action\][^\n]* end id=push [^\n]*target={child} moved=(?!0\.000\b)\d+\.\d+\b', window[:departure.start()]):
                return 'The shove did not move the child before it left'
            protest = re.search(rf'uuid={child} [^\n]*mold exile protest\b', window)
            if protest and re.search(rf'carry pickup [^\n]*purpose=RETRIEVE child={child}\b', window[protest.end():departure.start()]):
                return 'A guardian carried the departing child home'
            for parent in (mother, father):
                if not re.search(rf'uuid={parent} [^\n]*mold child verdict child={child} verdict=DRIVE_', window[:departure.start()]):
                    return f'The child left before guardian {parent} chose to drive it out'
            if re.search(rf'uuid={child} [^\n]*home chosen [^\n]* home=BlockPos\{{x={x}, y={FEET}, z={z-1}\}}', window[departure.end():]):
                return 'The sick child reclaimed its old den'
            for line in trace:
                row = json.loads(line.split('[trace] ', 1)[1])
                if row['k'] == 'st' and row['u'] == child and 'p' in row['st'] and row['t'] >= int(departure[1]):
                    px, py, pz = map(math.floor, row['st']['p'])
                    if x-2 <= px <= x+2 and z-5 <= pz < z and py == FEET:
                        return 'The sick child walked back inside its old den'
        S[-1].smoke.update(verify_trace=True, verify=verify_departure)


_mold_family('den_mold_child_exile', False)
_mold_family('den_mold_keep', True)


# The teen's independence is armed in NBT (Force); each family has its den as a starting home, and the farewell or departure is
# ordinary. The warm pair starts at the back of its mound with a grazed clearing beyond the door. The cool pair starts in
# the earthen den 32 blocks east. The far parent is taken to a closed stone-brick room outside the child's hearing range.
_indep = [box(-12, 0, -12, 12, 0, 14, 'minecraft:air', 'replace minecraft:short_grass')]
for _lane, _dx, _teen_dx, _parent_z, _teen_z, _attach, _label in (('warm', 0, 0, -4, -3, 85, '亚成·依恋高'),
                                                               ('cool', 32, 1, -2, -3, 20, '亚成·依恋低'),
                                                               ('far', -18, 1, 3, 4, 60, '亚成·亲油不在')):
    _pm, _pc = uid('indep_near', f'{_lane}_parent'), uid('indep_near', f'{_lane}_teen')
    _indep += [yk('reimu', _dx, _parent_z, f'{_lane}_parent', uuid=_pm, yaw=180, label='亲油', attachment=_attach, **pair_ready()),
               yk('reimu', _dx + _teen_dx, _teen_z, f'{_lane}_teen', stage='teen', uuid=_pc, yaw=180, lead=_lane == 'warm', nutrition=100, energy=100,
                  attachment=_attach, label=_label, kin=f'Mother:{uuid_nbt(_pm)}', extra='YukkuriIndependence:{Force:1b}'),
               f'yukkuri scenario kin {sel(f"{_lane}_parent")} {sel(f"{_lane}_teen")} false',
               *[f'yukkuri scenario home {sel(f"{_lane}_{who}")} ~{_dx} ~ ~-1' for who in ('parent', 'teen')]]
_indep += [box(38, 0, 1, 42, 3, 5, 'minecraft:stone_bricks'), box(39, 0, 2, 41, 2, 4, 'minecraft:air')]
add('indep_near', 'den', '亚成离巢：亲油在就告别', '亚成离巢：亲油在就告别',
    '三只亚成同时到了独立的时候。中间的洞里（依恋高）：亚成走到亲油跟前含泪说要去找自己的家，亲油哭着「要常回来看看」，两只贴脸好一会儿，'
    '亚成说「我走了」后离开，途中两次回头、最后喊「要保重」。送别时亲油会抽泣，孩子走远后则含泪微笑看着道路。右边（依恋低）：亚成爽快告别，亲油祝福，'
    '亚成鞠个躬就走，不回头，亲油看一眼就回去过日子。左边亲油被关在远处，约 3 分钟后亚成不告别直接离开。之后各自去找空洞或挖穴，亲缘与好感保留',
    '约 3 分钟内离开（可倍速）', _indep, env=DAY, spot='indep', retry='先“清空本场地”（洞口格要空）', vantage=(0, 16),
    # The far parent leaves only once the home is hers: a teen whose parents own another home is not yet of the family den.
    late=[f'tp {sel("far_parent")} ~40 ~ ~3'],
    reads=[('读依恋高的亚成', f'data get entity {sel("warm_teen")} YukkuriIndependence'),
           ('读依恋低的亚成', f'data get entity {sel("cool_teen")} YukkuriIndependence'),
           ('读不告别亚成', f'data get entity {sel("far_teen")} YukkuriIndependence'),
           ('读依恋高亲油的情绪', f'yukkuri status {sel("warm_parent")} raw')])

# A waterlogged floor makes the old den unsafe while its doorway remains dry enough to leave.
_sm, _sf, _sc = uid('den_move', 'mother'), uid('den_move', 'father'), uid('den_move', 'child')
add('den_move', 'den', '石洞进水，一家自行搬家', '石洞进水，一家自行搬家',
    '一家三口先住在小石洞。洞底进水，但洞口仍干燥；看亲油带子油沿地面去东边空土洞，到了新家鼓脸说出住家宣言。'
    '读三只的住所，确认一家都住到新洞；走进洞口可看领地反应。',
    '约 1 分钟', [
        # Six floor cells leave four beds after the doorway and store are reserved.
        box(0, 0, -6, 0, 1, -6, 'minecraft:air'),
        yk('reimu', -1, 1, 'mother', uuid=_sm, yaw=180, lead=True, kin=f'Partner:{uuid_nbt(_sf)}', boldness=15, **pair_ready()),
        yk('marisa', 1, 1, 'father', uuid=_sf, yaw=180, kin=f'Partner:{uuid_nbt(_sm)}', boldness=15, **pair_ready()),
        yk('reimu', 0, 2, 'child', stage='teen', uuid=_sc, yaw=180, nutrition=100, energy=100, boldness=15,
           kin=f'Mother:{uuid_nbt(_sm)},Father:{uuid_nbt(_sf)}', fam=TEEN_FAMILY),
        # Leave a qualified dry den and close the other two demonstration caves.
        box(-38, 0, -5, -34, 1, 0, 'minecraft:stone'), box(-20, 0, -5, -16, 1, 0, 'minecraft:stone'),
        ],
    late=[*[Retry(f'yukkuri scenario home {sel(n)} ~ ~ ~-1', sel(n), tries=1, first=1)
            for n in ('mother', 'father', 'child')],
          Retry(block(0, -1, -1, 'minecraft:oak_slab[type=top,waterlogged=true]'), sel('mother'), tries=1, first=30)],
    env=DAY, spot='small', vantage=(8, 14), reads=[('读搬家一家', 'yukkuri status @e[tag=scn,distance=..48] raw')],
    smoke=dict(ticks=2200, expect=[r'\[habitat\][^\n]* relocate begin .*movers=3 .*reason=flooded',
                                   r'\[habitat\][^\n]* relocate end .*movers=3 moved=3',
                                   r'\[home-claim\][^\n]* declared site=.*spoken=true'],
               forbid=[r'\[habitat\][^\n]* relocate begin .*reason=(?!flooded\b)',
                       r'\[habitat\][^\n]* relocate failed']))

add('home_greedy', 'den', '贪心占丝绒窝，可驱离', '贪心占丝绒窝，可驱离',
    '草窝和空丝绒窝都在屋檐下。胆大、低友善的魔理沙已有草窝，却自己走向丝绒窝并说住家宣言。'
    '等它到新窝后走近，观察转身、警告、鼓脸；停在近处还可能被轻撞。拒绝事件、抱起或贴近驱赶时，'
    '它会按性格骂回去、道别或哼一声。看魔理沙跺完脚再转身退走，离开时仍带怒容。直接打它时看它如何应对这一击。',
    '约 30 秒', [
        box(1, -1, 1, 9, 3, 8, 'minecraft:oak_planks', 'hollow'),
        block(5, 0, 8, 'minecraft:oak_door[facing=south,half=lower]'),
        block(5, 1, 8, 'minecraft:oak_door[facing=south,half=upper]'),
        block(3, 0, 3, 'mcyukkuri:straw_nest[facing=south]'),
        yk('marisa', 3, 5, 'greedy', label='魔理沙', boldness=100, friendliness=0, self_control=0, lead=True, **pair_ready()),
        ],
    late=[f'yukkuri scenario home {sel("greedy")} ~3 ~ ~3',
          block(7, 0, 3, 'mcyukkuri:velvet_nest[facing=south]')],
    env=DAY, spot='big', vantage=(5, 10, 180),
    capture=dict(shots=(40,), sequences=((65, 320, 4), (360, 480, 4)),
                 views={'': dict(position=(8.6, 1.2, 3.3), look_at=(6.9, .6, 5.0),
                                 criteria='固定机位从正前方看住家宣言：跳进、挺胸抬头、左右大幅转身俯视、两连跳、哼一声后得意定格，'
                                          '全程嚣张骄傲表情、不鼓脸；来客出现后才转向它鼓脸。')}),
    reads=[('读占领阶段','yukkuri homeclaim status'),('读魔理沙', f'yukkuri status {sel("greedy")} raw')],
    smoke=dict(ticks=900, expect=[r'\[home-claim\][^\n]* occupation chosen .*kind=bed .*previous=ResourceKey',
                                  r'\[home-claim\][^\n]* declared site=.*spoken=true'],
               forbid=[r'occupation_refused', r'never_arrived']))

# A previously learned skill as initial state. The resident decides whether to seal during the outing.
_seal_skill = {'den_seal': 2}
_seal_assessed = 'YukkuriSkills:{Assessed:["den_seal"]}'


def _screen_crossing(window, trace, residents, prepared=False):
    sealed = re.search(r'\[home\][^\n]* t=(\d+) [^\n]* den seal completed ', window)
    removed = re.search(r'\[home\] t=(\d+) [^\n]* den barrier removed pos=(-?\d+), (-?\d+), (-?\d+) [^\n]* age=(\d+) ', window)
    if not removed or not prepared and not sealed:
        return 'Missing a complete closed-screen interval'
    start = int(removed[1]) - int(removed[5]) if prepared else int(sealed[1])
    if start >= int(removed[1]):
        return 'Missing a complete closed-screen interval'
    # These south-facing dens have their one-cell notch at the removed screen's Z coordinate.
    mouth_x, mouth_z = int(removed[2]) + .5, int(removed[4]) + .5
    previous = {}
    for line in trace:
        row = json.loads(line.split('[trace] ', 1)[1])
        if row['k'] != 'st' or row['u'] not in residents or not start <= row['t'] < int(removed[1]):
            continue
        x, _, z = row['st']['p']
        last = previous.get(row['u'])
        if last and (last[1] >= mouth_z) != (z >= mouth_z):
            crossing_x = last[0] + (x - last[0]) * (mouth_z - last[1]) / (z - last[1])
            if abs(crossing_x - mouth_x) <= .5:
                return f"Resident {row['u']} crossed the sealed mouth at tick {row['t']}"
        previous[row['u']] = (x, z)
    if set(previous) != set(residents):
        return 'Missing resident positions while sealed'


def _family_screen():
    sid = 'den_barrier'
    names = ['mother', 'father', 'child', 'learner']
    ids = {name: uid(sid, name) for name in names}
    mother, father = (_uuid_text(sid, n) for n in names[:2])
    kin = kin_of(Mother=ids['mother'], Father=ids['father'])
    lines = [
        box(-12, 0, -8, 12, 0, 6, 'minecraft:air', 'replace minecraft:short_grass'),
        box(-11, 0, 7, 11, 0, 17, 'minecraft:short_grass', 'keep'),
        yk('reimu', 0, -5, 'mother', uuid=ids['mother'], label='会封洞的母油', lead=True, energy=40,
           nutrition=100, intelligence=90, self_control=100,
           boldness=20, attachment=95, kin=kin_of(Partner=ids['father']), skills=_seal_skill),
        yk('marisa', 0, 11, 'father', uuid=ids['father'], label='出门采草的父油', energy=100, nutrition=86, boldness=96,
           kin=kin_of(Partner=ids['mother']), extra=_seal_assessed),
        yk('marisa', 1, -4, 'child', stage='child', uuid=ids['child'], nutrition=96, kin=kin)]
    expect = [
        rf'\[forage\][^\n]*uuid={father}\b[^\n]* begin role=FAMILY phase=OUT',
        rf'\[forage\][^\n]*uuid={father}\b[^\n]* phase=GATHER -> BACK [^\n]* gathered=[1-9]\d* carried=[1-9]',
        rf'\[duty\][^\n]*uuid={mother}\b[^\n]* claimed kind=SEAL',
        rf'\[home\][^\n]*uuid={mother}\b[^\n]* den seal completed [^\n]* twigs 2 -> 1 danger=null absent=\[{father}\]',
        rf'\[duty\][^\n]*uuid={mother}\b[^\n]* released kind=SEAL end=DONE',
        rf'den call caller={father} callee={mother} relation=PARTNER',
        rf'\[forage\][^\n]*uuid={father}\b[^\n]* deposit home=[^\n]* moved=[1-9]']
    reads = [('读留守母油', f'yukkuri status {sel("mother")} raw'), ('读采草父油', f'yukkuri status {sel("father")} raw')]
    title = '父油出门采草，母油封洞口，父油回来呼唤'
    watch = '开场时父油已到南边草地，母油和孩子在洞里。看母油压枝封洞、亚成子油观察示范，再看父油采草回来呼唤伴侣、母油应声拆屏、父油把草送进粮堆。'
    learner = _uuid_text(sid, 'learner')
    lines.append(yk('reimu', -1, -3, 'learner', stage='teen', uuid=ids['learner'], nutrition=100, intelligence=96,
                    attachment=100, kin=kin, extra=_seal_assessed,
                    fam=TEEN_FAMILY.replace('Nourishment:276000L', 'Nourishment:160000L')))
    reads.append(('读亚成封洞技能', f'data get entity {sel("learner")} YukkuriSkills'))
    expect.extend([(rf'\[childhood\][^\n]*uuid={learner}\b[^\n]* den seal demonstration parent={mother}\b[^\n]* roll=[\d.]+ learned=(true|false)'
                    rf'|\[childhood\][^\n]*uuid={mother}\b[^\n]* den seal demonstration missed learner={learner}\b reason=(out_of_sight|asleep|away)\b'),
                   rf'den call answered by={mother}\b',
                   rf'den barrier removed [^\n]* reason=answered_call [^\n]*twig_returned=true opener={mother}\b'])
    residents = {_uuid_text(sid, n) for n in names}

    def first_handoff(window, trace):
        opened = re.search(r'\[home\] t=\d+ [^\n]* den barrier removed [^\n]+', window)
        if not opened or 'reason=answered_call' not in opened[0] or f'opener={mother}' not in opened[0]:
            return 'The mother did not open the first screen for the returning father'
        call = window.find(f'den call caller={father} callee={mother} relation=PARTNER')
        answer = window.find(f'den call answered by={mother}', call)
        if not 0 <= call < answer < opened.start():
            return 'Missing the partner call and answer before the first screen opened'
        return _screen_crossing(window, trace, residents)

    add(sid, 'den', title, title, watch, '约 1–2 分钟，可倍速',
        [*lines, *[home(n, 0, -1) for n in names], f'yukkuri scenario twigs {sel("mother")} 2'],
        late=[f'yukkuri scenario forage_out {sel("father")}'],
        env=DAY, spot='family', vantage=(3, 10), reads=reads,
        smoke=dict(ticks=3900, expect=expect, verify_trace=True, verify=first_handoff,
                   forbid=[r'\[body\][^\n]* invariant\b', r'den seal aborted reason=(path_stuck|cells_changed)']))


_family_screen()


def _timeout_screen():
    sid = 'den_call_timeout'
    ids = {n: uid(sid, n) for n in ('mother', 'father', 'child')}
    mother, father = (_uuid_text(sid, n) for n in ('mother', 'father'))
    x, z = BY_ID['den'].spots['family']
    core = (x << 38) | (((z - 1) & 0x3ffffff) << 12) | FEET
    # This hand-off starts at the return: one real twig is already in the screen, one is still in stock.
    screen = f'mcyukkuri:den_barrier{{Den:{core}L,Owner:{uuid_nbt(ids["mother"])},AbsentParents:[{uuid_nbt(ids["father"])}]}}'
    title = '母油睡着，父油呼唤无应答后自己拆屏'
    add(sid, 'den', title, title, '父油衔草回到已封的洞口，按亲缘呼唤睡着的伴侣。看它等候、放下草、拆屏，再把草送回粮堆。',
        '约 1 分钟', [
            yk('reimu', 0, -5, 'mother', uuid=ids['mother'], label='疲倦的母油', energy=20, rest_wait=0,
               nutrition=100, kin=kin_of(Partner=ids['father']), skills=_seal_skill),
            yk('marisa', 0, 3, 'father', uuid=ids['father'], label='衔草回家的父油', lead=True, energy=100,
               attachment=25, self_control=30, nutrition=86, boldness=96, kin=kin_of(Partner=ids['mother']),
               extra='YukkuriForageLoad:{id:"minecraft:short_grass",count:4}'),
            yk('marisa', 1, -4, 'child', stage='child', uuid=ids['child'], nutrition=96,
               kin=kin_of(Mother=ids['mother'], Father=ids['father'])),
            *[home(n, 0, -1) for n in ids], f'yukkuri scenario twigs {sel("mother")} 1'],
        late=[block(0, 0, 0, screen), 'execute store result block ~ ~ ~ LaidAt long 1 run time query gametime'],
        env=DAY, spot='family', vantage=(3, 10),
        reads=[('读疲倦母油', f'yukkuri status {sel("mother")} raw'), ('读衔草父油', f'yukkuri status {sel("father")} raw')],
        smoke=dict(ticks=1800, expect=[rf'\[sleep\][^\n]*uuid={mother}\b[^\n]* begin ',
                                       rf'den call caller={father} callee={mother} relation=PARTNER',
                                       rf'uuid={father}\b[^\n]* den call unanswered waited=',
                                       rf'den barrier removed [^\n]* reason=self_opened [^\n]*twig_returned=true opener={father}\b',
                                       rf'\[forage\][^\n]*uuid={father}\b[^\n]* deposit home=[^\n]* moved=4\b'],
                   forbid=[r'\[body\][^\n]* invariant\b', r'den call answered by='], verify_trace=True,
                   verify=lambda w, tr: _screen_crossing(w, tr, {_uuid_text(sid, n) for n in ids}, prepared=True)))


_timeout_screen()


def _den_guard(name, spot):
    """The hunter arrives while the resident is in the five-by-five cavity behind the notch."""
    x0, door_z = BY_ID['den'].spots[spot]
    return f'@e[tag={tag(name)},x={x0 - 2},y={FEET},z={door_z - 5},dx=4,dy=1,dz=4]'


add('den_barrier_predator', 'den', '会封洞的灵梦躲避捕食种', '会封洞的灵梦躲避捕食种',
    '会封洞的灵梦带着 2 根洞口枝条。灵梦进洞后，南面出现饥饿蕾米莉亚。看它察觉危险后封洞，等附近没有捕食种再拆开。',
    '约 1–2 分钟，可倍速', [
        box(-9, 0, -5, 9, 0, 14, 'minecraft:air', 'replace minecraft:short_grass'),
        item('mcyukkuri:core_filling', 1, 0, 21, 'filling'),
        yk('reimu', 0, -1, 'wise', label='会封洞的灵梦', boldness=20, self_control=100, skills=_seal_skill, lead=True, **pair_ready()),
        home('wise', 0, -1), f'yukkuri scenario twigs {sel("wise")} 2'],
    late=[Retry(yk('remilia', 0, 12, 'hunter', label='饥饿捕食种', nutrition=48, energy=95, boldness=80),
                _den_guard('wise', 'indep'), tries=20, every=20, first=1)],
    env=DAY, spot='indep', vantage=(4, 12),
    reads=[('读灵梦', f'yukkuri status {sel("wise")} raw'), ('读捕食种', f'yukkuri status {sel("hunter")} raw')],
    smoke=dict(ticks=1800, expect=[r'\[home\][^\n]* den seal completed [^\n]* danger=[0-9a-f-]{36} absent=\[\]',
                                   r'den barrier removed [^\n]* reason=danger_passed [^\n]*twig_returned=true'],
               forbid=[r'\[body\][^\n]* invariant\b']))

_seal_threat_wise = _uuid_text('den_barrier_threat', 'wise')


def _seal_threat_press(_window, trace):
    actor = f'"u":"{_seal_threat_wise}","k":"st"'
    if not any(actor in line and '"behavior":"nest_press"' in line for line in trace):
        return 'Threatened Reimu never showed the den press'


add('den_barrier_threat', 'den', '会封洞的灵梦被居民打回家封洞', '会封洞的灵梦被居民打回家封洞',
    '会封洞的灵梦叼着两份草，在洞外挨了居民一下，掉下草后立即转身逃回洞里，用洞口枝条封住。'
    '居民还在近旁时屏一直挡着；它自己忍过一阵后拆屏、把枝条还回粮堆。',
    '约 1 分钟，可倍速', [
        box(-9, 0, -7, 9, 0, 16, 'minecraft:air', 'replace minecraft:short_grass'),
        yk('reimu', 0, 2, 'wise', uuid=uid('den_barrier_threat', 'wise'), label='会封洞的灵梦',
           intelligence=85, boldness=15, self_control=100, skills=_seal_skill, lead=True,
           extra='YukkuriForageLoad:{id:"minecraft:short_grass",count:2}', **pair_ready()),
        # The resident starts well south of the mouth: it follows the panicking yukkuri slowly, so the doorway is clear while the screen goes up.
        human('oniisan', 3, 9, 'threat', 0, attitude=0, yaw=180, label='虐派居民'),
        set_status('wise', 'mood.unease', 60),
        home('wise', 0, -1), f'yukkuri scenario twigs {sel("wise")} 2'],
    late=[Retry(f'damage {sel("wise")} 1 minecraft:player_attack by {sel("threat")}', sel('wise'), tries=1, first=1)],
    env=DAY, spot='indep', vantage=(0, 14, 180),
    reads=[('读灵梦', f'yukkuri status {sel("wise")} raw'), ('读居民', f'yukkuri status {sel("threat")} raw')],
    smoke=dict(ticks=1900, expect=[
        rf'\[threat\][^\n]*uuid={_seal_threat_wise}\b[^\n]* struck stance=FLEE\b',
        rf'\[forage\][^\n]*uuid={_seal_threat_wise}\b[^\n]* drop item=minecraft:short_grass count=2 reason=hurt\b',
        rf'\[punishment\][^\n]*uuid={_seal_threat_wise}\b[^\n]* response=ESCAPE\b',
        rf'\[home\][^\n]*uuid={_seal_threat_wise}\b[^\n]* den seal decided [^\n]* eta=[\d.]+ need=[\d.]+ slack=[\d.]+ seals=true',
        rf'\[home\][^\n]*uuid={_seal_threat_wise}\b[^\n]* den seal completed [^\n]* placed=1 twigs 2 -> 1 danger=[0-9a-f-]{{36}} absent=\[\]',
        rf'\[home\][^\n]*den barrier removed [^\n]*owner={_seal_threat_wise}\b reason=danger_passed [^\n]*twig_returned=true opener={_seal_threat_wise}\b'],
        forbid=[r'\[body\][^\n]* invariant\b'], verify=_seal_threat_press, verify_trace=True))

_decoy = 'den_lead_away'
_decoy_x, _decoy_z = BY_ID['den'].spots['family']
_decoy_mother, _decoy_hunter = (_uuid_text(_decoy, n) for n in ('mother', 'hunter'))


def _decoy_distance(window, trace):
    chosen = re.search(rf'uuid={_decoy_mother} t=(\d+)[^\n]*home tactic stance=LEAD_AWAY', window)
    ended = re.search(rf'uuid={_decoy_mother} t=(\d+)[^\n]*lead away ended', window)
    if not chosen or not ended:
        return 'Missing a complete decoy episode'
    frames = {}
    for line in trace:
        row = json.loads(line.split('[trace] ', 1)[1])
        if row['k'] == 'st' and row['u'] in (_decoy_mother, _decoy_hunter) and int(chosen[1]) <= row['t'] <= int(ended[1]):
            frames.setdefault(row['t'], {})[row['u']] = row['st']['p']
    for pair in frames.values():
        if len(pair) == 2:
            mother, hunter = pair[_decoy_mother], pair[_decoy_hunter]
            if math.hypot(mother[0] - _decoy_x - .5, mother[2] - _decoy_z - .5) >= 12 and math.dist(mother, hunter) <= 16:
                return
    return 'The decoy never reached 12 blocks from the mouth with the hunter within 16'


add(_decoy, 'den', '母油把捕食种引离有孩子的洞', '母油把捕食种引离有孩子的洞',
    '两只赤油留在洞内。看灵梦留在洞外，朝远离家的地方跑，回身鼓脸喊话，把蕾米莉亚引走。',
    '约 1 分钟', [
        yk('reimu', 0, 3, 'mother', uuid=uid(_decoy, 'mother'), label='引开捕食种的母油', lead=True,
           intelligence=85, self_control=70, attachment=90, friendliness=80, boldness=50, skills=_seal_skill),
        *[yk('reimu', x, -4, name, uuid=uid(_decoy, name), stage='baby', kin=kin_of(Mother=uid(_decoy, 'mother')),
             childhood=CHILDHOOD_CRAWLING_READY) for x, name in ((-1, 'baby1'), (1, 'baby2'))],
        *[home(n, 0, -1) for n in ('mother', 'baby1', 'baby2')], f'yukkuri scenario twigs {sel("mother")} 2'],
    late=[Retry(yk('remilia', 0, 14, 'hunter', uuid=uid(_decoy, 'hunter'), label='饥饿捕食种', nutrition=48, energy=95, boldness=80),
                f'@e[tag={tag("mother")},x={_decoy_x-8},y={FEET},z={_decoy_z+1},dx=16,dy=2,dz=14]', first=1, every=20)],
    env=DAY, spot='family', vantage=(5, 13), reads=[('读引敌母油', f'yukkuri status {sel("mother")} raw')],
    smoke=dict(ticks=1000, expect=[rf'uuid={_decoy_mother}\b[^\n]* home tactic stance=LEAD_AWAY',
                                  rf'uuid={_decoy_mother}\b[^\n]* lead away taunt [^\n]*attacker={_decoy_hunter}',
                                  rf'uuid={_decoy_mother}\b[^\n]* lead away ended reason=(led_away|expired|threat_lost)'],
               verify_trace=True, verify=_decoy_distance,
               forbid=[rf'prey bite target=#\d+ uuid={_uuid_text(_decoy, n)}\b' for n in ('baby1', 'baby2')],
               allow_fate=[dict(kind='death', who=f'uuid={_decoy_mother} ', match=r'cause=mob\b', why='the decoy takes the real hunt')]))

# ====================================================================================================== vagrants and foraging
add('dig', 'forage', '野生：魔理沙在土丘挖洞安家', '野生：魔理沙在土丘挖洞安家', '没有家的野生成体走到土丘边，先挖通地面，再向上挖内部第二层。入口一格高、内部两格高，每块约 6 秒。',
    '约 3–6 分钟（可倍速）', [
        yk('marisa', 0, 0, 'vagrant', yaw=180, lead=True, **pair_ready()),
        'gamerule mobGriefing true'], env=DAY, spot='dig', vantage=(0, 6, 180),
    reads=[('读挖穴阶段', f'yukkuri status {sel("vagrant")} raw')],
    # Give the digger the day to finish, then let the normal nighttime sleep drive test the bed it claimed.
    smoke=dict(ticks=3000, commands=[(2100, 'time set midnight')],
               forbid=[r'\[settlement\][^\n]* surface nest (begin|built)',
                       r'\[habitat\][^\n]* dig end doorway=[^\n]*outcome=claim_refused'],
               expect=[r'\[habitat\][^\n]* dig begin mouth=',
                       r'\[habitat\][^\n]* dig end doorway=[^\n]*(outcome=claimed|claimed=true)',
                       r'\[habitat\] marisa #\d+ uuid=(\S+)[^\n]* home chosen [^\n]*bed=(BlockPos\{[^}]+\})'
                       r'[\s\S]*?\[sleep\] marisa #\d+ uuid=\1 [^\n]*begin source=life occupancy=INSIDE_SUPPORTED bed=\2 ']))

_fm, _ff, _fc = uid('forage', 'mother'), uid('forage', 'father'), uid('forage', 'child')
add('forage', 'forage', '野生：一方外出觅食，一方照看孩子', '野生：一方外出觅食，一方照看孩子',
    '土丘洞储粮空了。父油魔理沙去草场采草，母油灵梦在洞口两侧巡视，间或回洞看赤油。看魔理沙把草衔回粮堆，家里迎接归来，再用存粮喂赤油。',
    '数分钟（可倍速）', [
        box(9, 0, 6, 16, 0, 13, 'minecraft:short_grass', 'keep'),
        yk('marisa', 0, 1, 'father', uuid=_ff, yaw=180, lead=True, kin=f'Partner:{uuid_nbt(_fm)}', nutrition=80, energy=100,
           boldness=60, friendliness=100, attachment=95),
        yk('reimu', 0, -2, 'mother', uuid=_fm, yaw=0, kin=f'Partner:{uuid_nbt(_ff)}', nutrition=100, energy=100,
           boldness=20, friendliness=100, attachment=35, affection=0),
        yk('reimu', 1, -3, 'child', stage='baby', uuid=_fc, yaw=0, nutrition=74, self_control=96,
           childhood=CHILDHOOD_CRAWLING_READY,
           kin=f'Mother:{uuid_nbt(_fm)},Father:{uuid_nbt(_ff)}'),
        ],
    late=[f'yukkuri scenario home {sel(n)} ~ ~ ~-1' for n in ('mother', 'father', 'child')],
    env=DAY, spot='forage', vantage=(0, 9, 180, 12),
    # The baby at home welcoming a parent back from the meadow (inside the mound, so the camera follows it).
    capture=dict(shots=(1, 40, 600), sequences=((900, 2700, 6),), observe=False,
                 views={'child': dict(follow=f'@e[tag={tag("child")},limit=1]', offset=(.5, 1.2, 1.2))}),
    reads=[('读外出魔理沙', f'yukkuri status {sel("father")} raw'), ('读留守灵梦', f'yukkuri status {sel("mother")} raw'),
           ('读赤油进食', f'yukkuri status {sel("child")} raw')])


_absent, _stayer, _older, _baby = (uid('family_absence', n) for n in ('absent', 'stayer', 'older', 'baby'))
_absence_x, _absence_z = BY_ID['forage'].spots['forage']
# The family remembers the last point before Reimu turned behind the bank.
_departure_pos = (((_absence_x + 2) & 0x3ffffff) << 38) | (((_absence_z + 4) & 0x3ffffff) << 12) | FEET
if _departure_pos >= 1 << 63:
    _departure_pos -= 1 << 64
_departure_memory = (f'KinAbsence:[{{Kin:{uuid_nbt(_absent)},Seen:{_departure_pos}L,'
                     'Dimension:"minecraft:overworld",Away:0,Phase:0}]')
_absence_kin = f'Mother:{uuid_nbt(_absent)},Father:{uuid_nbt(_stayer)},{_departure_memory}'
add('family_absence', 'forage', '出门采草的亲油迟迟不归', '出门采草的亲油迟迟不归',
    '开场时灵梦已在土坡后的草场采草，家人记得她离开的方向。她在家人看不到的地方死亡。'
    '看子油在洞口内向外守望，赤油留在窝里等。魔理沙先巡视，亲油迟归后朝记得的方向扇形寻找并呼唤，发现遗体后哀悼，再去采草、照料饥饿的赤油。',
    '约 3–6 分钟（可倍速）', [
        # The bank hides the meadow from the den; its south face leaves the search fan's west stand (about -2.4, 7.4) clear.
        box(-3, 0, 5, 2, 2, 5, 'minecraft:dirt'),
        box(-8, 0, 15, 8, 0, 23, 'minecraft:short_grass', 'keep'),
        yk('reimu', 1, 11, 'absent', uuid=_absent, yaw=0, kin=f'Partner:{uuid_nbt(_stayer)}', nutrition=82, energy=100,
           boldness=100),
        yk('marisa', -1, -2, 'stayer', uuid=_stayer, yaw=0, kin=f'Partner:{uuid_nbt(_absent)},{_departure_memory}',
           nutrition=100, energy=90, boldness=38, friendliness=100, attachment=0, affection=0, self_control=50),
        yk('reimu', 1, -2, 'older', stage='child', uuid=_older, kin=_absence_kin, nutrition=76, boldness=20, self_control=35,
           childhood=CHILDHOOD_HOPPING, lead=True),
        yk('marisa', 1, -3, 'baby', stage='baby', uuid=_baby, kin=_absence_kin, nutrition=15,
           childhood=CHILDHOOD_CRAWLING_READY),
        ],
    late=[*(f'yukkuri scenario home {sel(n)} ~ ~ ~-1' for n in ('absent', 'stayer', 'older', 'baby')),
          f'yukkuri scenario forage_out {sel("absent")}',
          Retry(f'kill @e[tag={tag("absent")},limit=1]', sel('absent'), tries=1, first=60)],
    # West of the bank's end, on its north row: the mouth, the patrol, the first search stand and the meadow behind the bank.
    env=DAY, spot='forage', vantage=(-10, 4, -80, 10),
    reads=[(label, f'yukkuri status {sel(n)} raw') for n, label in
           [('stayer', '读留守亲油'), ('older', '读子油寻亲'), ('baby', '读赤油等候')]])

_taste_actors = (
    ('easy', 'reimu', -12, 56, 12, 'SAVOUR'),
    ('plain', 'marisa', -8, 56, 45, 'PLAIN'),
    ('grudge', 'alice', -4, 56, 64, 'GRUMBLE'),
    ('reluctant', 'chen', 0, 22, 95, 'RELUCTANT'),
    ('picky', 'reimu', 4, 56, 95, 'REFUSE'),
)
add('graze', 'forage', '舌肥：同一份粗粮，五种反应', '舌肥：同一份粗粮，五种反应',
    '五只成体脚前各有一份预算粮。从左到右看开心蹦跳、平静吃完、嫌弃着慢嚼、饿得含泪吃下、抿嘴转开把粮留在地上。'
    '后两只舌肥都是 95，开场营养分别为 22 和 56。生存模式下也可进围栏亲手喂最右边那只，看它把粮含住再吐回地上。',
    '约 15–60 秒', [
        box(-13, 0, -1, 5, 0, 1, 'minecraft:air'),
        *(box(x, 0, -2, x, 0, 2, 'minecraft:oak_fence') for x in range(-14, 7, 4)),
        *(box(-14, 0, z, 6, 0, z, 'minecraft:oak_fence') for z in (-2, 2)),
        *(block(x, 0, -2, 'minecraft:oak_fence_gate[facing=south]') for _, _, x, *_ in _taste_actors),
        *(command for name, species, x, nutrition, taste, _ in _taste_actors for command in (
            yk(species, x, 0, f'taste_{name}', uuid=uid('graze', f'taste_{name}'), nutrition=nutrition, yaw=180,
               boldness=80 if name == 'picky' else 50, friendliness=30 if name == 'picky' else 50,
               self_control=25 if name == 'picky' else 40, intelligence=(45, 65),
               picky=taste, lead=name == 'grudge', label=f'舌肥 {taste}'),
            item('mcyukkuri:oil_grain_budget', 1, x, -.4, name=f'taste_grain_{name}')))],
    env=DAY, spot='graze', vantage=(-4, 12),
    give=['mcyukkuri:oil_grain_budget 4', 'mcyukkuri:oil_grain_premium 2'], note='待验证：尚未试玩。',
    reads=[(f'读舌肥 {taste}／起始营养 {nutrition}', f'yukkuri status {sel(f"taste_{name}")} raw')
           for name, _, _, nutrition, taste, _ in _taste_actors])

_glass_food_eater = uid('food_behind_glass', 'glass_food_eater')
add('food_behind_glass', 'forage', '隔着玻璃看见苹果', '隔着玻璃看见苹果',
    '灵梦隔着玻璃板看见苹果。看它走到玻璃前挤脸一次，退开继续找食，苹果留在窗内。',
    '约 10 秒', [box(-2, -1, -2, 2, 2, 2, 'minecraft:stone_bricks', 'hollow'),
        block(0, 0, 2, 'minecraft:glass_pane'), block(0, 1, 2, 'minecraft:glass_pane'),
        yk('reimu', 0, 5, 'glass_food_eater', uuid=_glass_food_eater, nutrition=60, yaw=180, lead=True),
        item('minecraft:apple', 1, 0, 1, name='glass_apple'),
        f'data modify entity {sel("glass_apple")} PickupDelay set value 32767s'],
    env=DAY, spot='forage', vantage=(4, 7, 155),
    reads=[('读窗外灵梦', f'yukkuri status {sel("glass_food_eater")} raw')])

# The same window at sill height: the pane's base is one block above the ground, over a stray's low eye, so it can be seen
# through only from a few blocks out and cannot be reached with the face. The operator holds the apple inside beside the pane.
_sill_beggar = uid('food_sill_window', 'sill_beggar')
add('food_sill_window', 'forage', '窗台高的窗：远远望着窗里的苹果', '窗台高的窗：远远望着窗里的苹果',
    '屋里的人手里拿着苹果，窗台高的玻璃板在一人高的墙上。窗外饿着的流浪灵梦走到离窗三四格的地方，面朝窗口流口水望着，'
    '够不着窗台所以不挤脸；几秒后离开。',
    '约 15 秒', [box(-2, -1, -2, 2, 2, 2, 'minecraft:stone_bricks', 'hollow'),
        block(0, 1, 2, 'minecraft:glass_pane'),
        yk('reimu', 0, 7, 'sill_beggar', uuid=_sill_beggar, nutrition=45, yaw=180, boldness=60, lead=True,
           extra='YukkuriPet:{TownRoots:1b}'),
        item('minecraft:poppy', 1, 0, 1, name='sill_anchor'),
        f'data modify entity {sel("sill_anchor")} PickupDelay set value 32767s'],
    env=DAY, spot='forage', vantage=(4, 9, 155), note='待验证：新场景，尚未在游戏里实测。',
    reads=[('读窗外灵梦', f'yukkuri status {sel("sill_beggar")} raw')])

# One-cell glass niches, three deep, with their glass back toward the visitor and open to the south (a yukkuri cannot see through
# glass). Each hungry body has one apple at the back of its niche and one out in the open south of it: it walks in and eats facing
# the visitor (wings, braid, side locks pressed in between the side panes), then turns round and walks out to the second apple,
# where they come free. Chen starts backed against the west pane facing east over its first apple, so the visitor sees it in profile and its tails
# curl up against the glass through the meal and uncurl as it leaves. A Remilia in the open does the same walk with nothing to
# yield to. A predator menaces ordinary bodies it sees or is within 6 blocks of (Reactions.Threat): a stone wall running south from
# the middle of the row screens the ordinary niches (east) from the predator niches (west), 7 blocks apart.
_TUCK_ROW = (('marisa', 7, 'tuck_marisa', '魔理沙：辫子'), ('reimu', 5, 'tuck_reimu', '灵梦：鬓发'),
             ('chen', 3, 'tuck_chen', '橙：尾巴'), ('remilia', -4, 'tuck_remilia', '蕾米莉亚：翅膀'),
             ('flandre', -6, 'tuck_flandre', '芙兰：翅膀'), ('remilia', -10, 'tuck_open', '对照：空地蕾米莉亚'))


def _tuck_body(kind, x, name, label):
    # Prey at 30 still wants the second apple after the first (+20, LifeMeal FULL - 20 = 55). A predator takes loose food before
    # hunting (PredatorLife scavenge) and at boldness 0 wants to hunt only below 45, so at 50 it eats both apples and hunts nobody;
    # at energy 40 (patrol needs above 45) it walks to its food and stays on the ground instead of flying a patrol over the screen.
    predator = kind in ('remilia', 'flandre')
    backed = name == 'tuck_chen'
    return yk(kind, x, 2 if backed else 5, name, uuid=uid('wall_tuck', name), label=label, yaw=270 if backed else 180,
              lead=name == 'tuck_marisa', nutrition=50 if predator else 30, boldness=0 if predator else 55,
              energy=40 if predator else 95, social_wait=6000)


add('wall_tuck', 'forage', '贴墙收拢：辫子、翅膀、尾巴', '贴墙收拢：辫子、翅膀、尾巴',
    '饿油走进一格宽的玻璃墙槽，面朝你吃槽底的苹果：看魔理沙的辫子与帽檐、灵梦的鬓发向内收，蕾米莉亚和芙兰的翅膀向下收拢；'
    '橙一开始就侧身背贴西侧玻璃朝东吃苹果，从正面隔着玻璃看它的侧面，两条尾巴离开头发向上弯成问号钩，尾尖越过头顶后部。吃完转身走出槽口去吃外面的第二个苹果时各自复原。最右边空地上的蕾米莉亚走同样的路，翅膀一直张开，作对照。',
    '约 10–20 秒', [
        box(-7, 0, 0, 8, 1, 0, 'minecraft:glass'),
        box(0, 0, 0, 0, 1, 28, 'minecraft:stone_bricks'),
        *[box(x, 0, 1, x, 1, 3, 'minecraft:glass') for x in (2, 4, 6, 8, -3, -5, -7)],
        *[_tuck_body(*entry) for entry in _TUCK_ROW],
        # Chen eats from where it stands: its apple lies just ahead (east), so it keeps its back against the west pane.
        *[item('minecraft:apple', 1, x + .3 if name == 'tuck_chen' else x, 2 if name == 'tuck_chen' else 1, f'{name}_inner') for _, x, name, _ in _TUCK_ROW],
        *[item('minecraft:apple', 1, x, 10, f'{name}_outer') for _, x, name, _ in _TUCK_ROW]],
    env=DAY, spot='wall_tuck', vantage=(1, -3, 0, 12), note='待验证：新场景，尚未在游戏里实测。',
    capture=dict(shots=(1, 500), sequences=((2, 360, 4),), observe=False,
                 views={'': {}, 'locks': dict(position=(5.5, 1.9, -1.2), look_at=(5.5, .3, 2)),
                        # Chen in profile through the front glass at about 4.5 blocks, and from behind over the screen wall.
                        'tail': dict(position=(3.5, .8, -2), look_at=(3.4, .4, 2.5)),
                        'tail_back': dict(position=(.5, 2.6, 2.5), look_at=(3.5, .5, 2.5)),
                        'wings': dict(position=(-5.5, 1.9, -1.2), look_at=(-5.5, .3, 2))}),
    smoke=dict(ticks=600, forbid=[r'\[body\][^\n]* invariant\b'],
               expect=[rf'\[meal\][^\n]*uuid={_uuid_text("wall_tuck", name)}[^\n]* swallowed item=minecraft:apple\b'
                       for _, _, name, _ in _TUCK_ROW]),
    reads=[('读魔理沙', f'yukkuri status {sel("tuck_marisa")} raw'), ('读橙', f'yukkuri status {sel("tuck_chen")} raw'),
           ('读蕾米莉亚', f'yukkuri status {sel("tuck_remilia")} raw')])


def _wall_tuck_verify(log):
    # The exhibit is both apples of every body (a body may share its second one with a neighbour: it counts once picked up): the inner one at the wall, the outer one in the open. Fed bodies that later roam
    # round the end of the screen meet the predators and back off for real; that may come after, never before.
    threat = re.search(r'\[threat\][^\n]* response started ', log)
    calm = log[:threat.start()] if threat else log
    for _, _, name, _ in _TUCK_ROW:
        meals = re.findall(rf'\[meal\][^\n]*uuid={_uuid_text("wall_tuck", name)}[^\n]* received item=minecraft:apple\b', calm)
        if len(meals) < 2:
            return f'{name} ate {len(meals)} of its two apples before the first threat response'


next(s for s in S if s.id == 'wall_tuck').smoke['verify'] = _wall_tuck_verify


def carton_shell(x, z):
    return [block(x + dx, dy, z, f'mcyukkuri:cardboard_shelter[facing=south,part={dx + 12 + 25*dy}]')
            for dy in range(2) for dx in range(-1, 2)]


def street_stray(scene, kind, x, z, name, **kwargs):
    former = uuid_nbt(uid(scene, 'former_owner'))
    return yk(kind, x, z, name, uuid=uid(scene, name), extra=f'YukkuriPet:{{FormerOwner:{former}}}', **kwargs)


def garbage_bin(x, z, items, tipped):
    """A street bin with real contents; a tipped one lies with its mouth toward +z (south)."""
    stock = ','.join(f'{{Slot:{i}b,id:"{item}",count:{n}}}' for i, (item, n) in enumerate(items))
    return [block(x, 0, z, f'mcyukkuri:garbage_bin[facing=south,tipped={str(tipped).lower()}]'),
            f'data merge block ~{x} ~ ~{z} {{Items:[{stock}]}}']


_stray_mother = uid('stray_boxes', 'mother')
_stray_mother_log = _uuid_text('stray_boxes', 'mother')
_stray_child_log = _uuid_text('stray_boxes', 'child')
_stray_mother_family = ('Version:2,Initialized:1b,Stage:"ADULT",Age:672000L,Nourishment:672000L,'
                       'Maturity:336000L,Lifespan:1056000L,AdultScale:0.84f,Scale:0.84f')


def _stray_box_arrival(events, ready):
    first_resident = mouth = direct_entry = None
    beckon = None
    for row in events:
        actor, tick, msg = row['u'], row['t'], row['msg']
        if actor == _stray_mother_log:
            if msg.startswith('found approach '):
                # A new walk starts a new decision; an earlier mouth arrival or call is over.
                mouth = direct_entry = None
                beckon = None
            sniff = re.fullmatch(r'found sniff shelter=(.+)', msg)
            if sniff:
                mouth = sniff[1]
                direct_entry = beckon = None
            elif mouth and msg == f'found no beckon shelter={mouth}':
                direct_entry = row['seq']
            elif msg.startswith(f'found beckon child={_stray_child_log} '):
                direct_entry = None
                beckon = 'called'
            elif msg.startswith(f'found beckon answered child={_stray_child_log} ') and beckon == 'heard' and tick >= ready:
                beckon = 'answered'
            elif msg.startswith('found beckon unanswered '):
                beckon = None
        elif (actor == _stray_child_log and beckon == 'called'
              and msg.startswith(f'child heard parent call parent={_stray_mother_log} ') and msg.endswith('beckon=true')):
            beckon = 'heard'

        if first_resident and actor == first_resident[0] and msg.startswith(('found leaving ', 'found exited ', 'found ejected ')):
            first_resident = None
        settled = re.fullmatch(r'found settled shelter=(.+) kind=CARDBOARD occupants=(\d+)', msg)
        if not settled:
            continue
        box, occupants = settled.groups()
        if occupants == '1':
            first_resident = (actor, box, row['seq']) if tick >= ready else None
        elif occupants == '2' and first_resident and mouth:
            resident, first_box, arrived = first_resident
            child_first = resident == _stray_child_log and direct_entry is not None and arrived < direct_entry
            if resident != actor and first_box == box == mouth and (beckon == 'answered' or child_first):
                return
    return 'Both must settle in the same carton after an answered beckon or a no-beckon decision with the child already settled'


def _stray_child_flight(window, trace):
    hit = re.search(rf'\[threat\][^\n]* uuid={_stray_child_log} t=(\d+)[^\n]* struck stance=(\w+) [^\n]* duration=(\d+) dialogue=(\S+)', window)
    if not hit or hit[2] not in ('FLEE', 'FREEZE'):
        return 'The baby must flee or briefly freeze on the first hit'
    tick, stance, duration, key = int(hit[1]), hit[2], int(hit[3]), hit[4]
    lines = re.findall(rf'\[speech\][^\n]* uuid={_stray_child_log} t={tick} [^\n]* line event=(\S+) key=(\S+)', window)
    if lines != [('wounded' if stance == 'FLEE' else 'hurt_freeze', key)]:
        return f'The struck baby must give one cry matching {stance}: {lines}'
    mother = _stray_mother_log
    pairs = {}
    events = []
    for line in trace:
        row = json.loads(line.split('[trace] ', 1)[1])
        if row['u'] not in (mother, _stray_child_log) or row['t'] < tick:
            continue
        if row['k'] == 'ev' and row['cat'] in ('habitat', 'family'):
            events.append(row)
        elif row['k'] == 'st' and row['t'] <= tick + 101:
            pairs.setdefault(row['t'], {})[row['u']] = row['st']
    if stance == 'FREEZE':
        child = [(t, bodies[_stray_child_log]) for t, bodies in pairs.items() if _stray_child_log in bodies]
        held = [t for t, s in child if s['behavior'] == 'struck' and s['animation'] in ('brace', 'tremble')]
        if not 0 < duration <= 100 or not held or held[-1] - held[0] + 1 > 100 or held[-1] > tick + 100:
            return 'The baby must show a freeze lasting at most 100 ticks'
        recovered = next((t for t, s in child if t > held[-1] and s['behavior'] != 'struck'), None)
        if recovered is None:
            return 'The baby did not recover from the freeze within 100 ticks'
        return _stray_box_arrival(events, recovered)
    flight = [(t, bodies[_stray_child_log]['p'], bodies[mother]['p']) for t, bodies in pairs.items()
              if t < tick + 96 and len(bodies) == 2 and bodies[_stray_child_log]['behavior'] == 'flee']
    if not flight:
        return 'The baby never entered flight'
    _, start, parent = flight[0]
    def gap(child):
        return math.hypot(child[0] - parent[0], child[2] - parent[2])
    if not any(gap(child) <= gap(start) - .4 for _, child, _ in flight):
        return 'The fleeing baby did not move toward its mother'
    if not any(t <= tick + 30 and math.hypot(child[0] - start[0], child[2] - start[2]) >= .4 for t, child, _ in flight):
        return 'The flight did not steer the baby after it landed from the blow'
    return _stray_box_arrival(events, tick)


add('stray_boxes', 'forage', '流浪：母子找纸箱', '流浪：母子找纸箱',
    '夜里，孩子还在箱外时，流浪母油会在箱口转身招呼它；孩子先在箱内歇下时，母油会直接进去。看母子并排歇脚时，两顶头饰是否与箱壁和彼此留有空隙。',
    '约半分钟至一分钟', [
        *carton_shell(-5, -1),
        street_stray('stray_boxes', 'reimu', -5, 0, 'mother', energy=28, rest_wait=0, lead=True, fam=_stray_mother_family),
        street_stray('stray_boxes', 'reimu', -5, 2.5, 'child', stage='baby', energy=48, rest_wait=0,
                     kin=f'Mother:{uuid_nbt(_stray_mother)}', childhood=CHILDHOOD_HOPPING),
    ], env={'time': 'night'}, spot='street', vantage=(-5, 5, 180, 15),
    capture=dict(shots=(1, 40, 1000), sequences=((60, 460, 10),), observe=False,
                 views={'carton': dict(position=(-3.2, 1, 2.1), look_at=(-4.5, .55, -.4))}),
    # Leave time for the child's post-flight fear and the original two-occupant settlement check.
    smoke=dict(ticks=1600, forbid=[r'\[habitat\][^\n]* home chosen '], verify_trace=True, verify=_stray_child_flight,
               commands=[
                   (44, set_status('child', 'personality.boldness', 55)),
                   (45, f'execute at {sel("child")} positioned ~ ~ ~3 run yukkuri scenario tool_operator enter'),
                   (46, f'damage {sel("child")} 1 minecraft:player_attack by @e[type=minecraft:player,name=MuseumOperator,distance=..64,limit=1]'),
                   (150, 'yukkuri scenario tool_operator leave'),
               ],
               expect=[r'\[habitat\][^\n]* found settled .*kind=CARDBOARD occupants=2\b']))

add('stray_rain', 'forage', '流浪：纸箱湿透，先离开再塌', '流浪：纸箱湿透，先离开再塌',
    '看纸箱里的流浪油歇脚。纸箱受潮后，它沿开口离开，去别处避雨。空纸箱泡透塌下，留下纸片。左侧另一只魔理沙会沿墙去捡苹果。',
    '约一分钟', [
        *carton_shell(-4, -1),
        # Start near the open edge with clearance behind the brim and to the wall.
        street_stray('stray_rain', 'marisa', -4.18, -.6875, 'rain_stray', energy=25, rest_wait=0, lead=True),
        box(-14, 0, -3, -14, 1, 3, 'minecraft:cobblestone'),
        street_stray('stray_rain', 'marisa', -13, -2, 'wall_stray', yaw=0, nutrition=24),
        item('minecraft:apple', 1, -13, 2, 'wall_apple'),
    ], env={'time': 'noon', 'weather': 'rain'}, spot='street', vantage=(-2, 6, 164, 12),
    capture=dict(shots=(1, 590, 700, 850, 1050, 1300), sequences=(), observe=False,
                 views={'carton': dict(position=(-2.35, 1.1, 2.1), look_at=(-3.5, .55, -.4)),
                        'wall': dict(follow=sel('wall_stray'), offset=(2.3, .9, -.65))}),
    smoke=dict(ticks=1300, forbid=[r'\[habitat\][^\n]* found ejected .*reason=rain_collapse'],
               commands=[(1, block(-5, 0, -2, 'mcyukkuri:cardboard_shelter[facing=south,part=6]')),
                         (1, block(-3, 1, 0, 'mcyukkuri:cardboard_shelter[facing=south,part=43]')),
                         # A live carton always saves LiningMaterials, so a merge must use that key; FoundBedding is only read from old chunks.
                         (1, 'data merge block ~-4 ~ ~-1 {Condition:0,Lining:1,LiningMaterials:[{id:"minecraft:wheat",count:1}]}'),
                         (140, 'execute if block ~-5 ~ ~-2 minecraft:air if block ~-3 ~1 ~ minecraft:air '
                               'if data block ~-4 ~ ~-1 {Condition:100,Lining:1} run say CARTON_LEGACY_RETAINED'),
                         (1120, 'execute positioned ~-3.5 ~ ~-0.5 '
                                'if entity @e[type=minecraft:item,distance=..4,nbt={Item:{id:"minecraft:paper",count:1}}] '
                                'if entity @e[type=minecraft:item,distance=..4,nbt={Item:{id:"minecraft:wheat",count:1}}] run say CARTON_REAL_DROPS')],
               expect=[r'\[habitat\][^\n]* found settled .*kind=CARDBOARD',
                                  r'\[Not Secure\].*CARTON_LEGACY_RETAINED|\[.*\] CARTON_LEGACY_RETAINED',
                                  r'\[Not Secure\].*CARTON_REAL_DROPS|\[.*\] CARTON_REAL_DROPS',
                                  r'\[habitat\][^\n]* found rain stage=0 -> 1',
                                  r'\[habitat\][^\n]* found leaving .*reason=soaked_or_unsafe',
                                  r'\[habitat\][^\n]* found exited ',
                                  r'\[habitat\][^\n]* found rain stage=1 -> 2',
                                  r'\[habitat\][^\n]* drop item=minecraft:paper count=1 source=shelter .*reason=rain_collapse',
                                  r'\[habitat\][^\n]* found removed .*reason=rain_collapse']))

_rubbish_stock = [('minecraft:apple', 1), ('minecraft:paper', 5), ('minecraft:stick', 5)]
add('stray_rubbish', 'forage', '流浪：翻垃圾桶，胆大的撞倒、胆小的放弃', '流浪：翻垃圾桶，撞倒或放弃',
    '西边两只立着的桶里只有一个苹果或一张废纸，桶轻，胆大的灵梦撞一两下就倒，再钻进桶口吃、衔纸走开放下。东边两只立着、装得一样满的桶前，'
    '胆大的魔理沙撞倒桶再绕到桶口吃，胆小的撞几下推不动就放弃。中间倒着的桶里只有一件垃圾头饰（胆小的失饰者撞不动桶，所以它一开始就倒着），'
    '失饰的低自尊灵梦会直接戴上。立着的桶可右键打开，倒着的右键扶起。',
    '约半分钟', [
        *garbage_bin(-12, -1, [('minecraft:apple', 1)], False),
        *garbage_bin(-26, -1, [('minecraft:paper', 1)], False),
        *garbage_bin(0, -1, [('mcyukkuri:junk_headwear', 1)], True),
        *garbage_bin(12, -1, _rubbish_stock, False),
        *garbage_bin(26, -1, _rubbish_stock, False),
        street_stray('stray_rubbish', 'reimu', -12, 3, 'open_stray', nutrition=24, boldness=80, lead=True),
        street_stray('stray_rubbish', 'reimu', -26, 3, 'paper_stray', nutrition=24, boldness=80),
        street_stray('stray_rubbish', 'reimu', 0, 3, 'junk_stray', nutrition=24, accessory=False, boldness=0, friendliness=100,
                     label='失饰的低自尊者'),
        set_status('junk_stray', 'marks.dirt', 3),
        street_stray('stray_rubbish', 'marisa', 12, 3, 'timid_stray', nutrition=24, boldness=25, label='胆小的'),
        street_stray('stray_rubbish', 'marisa', 26, 3, 'bold_stray', nutrition=24, boldness=90, label='胆大的'),
    ], env=DAY, spot='street', vantage=(0, 12),
    capture=dict(shots=(35,), sequences=((40, 255, 2), (260, 420, 5)),
                 views={'': dict(position=(23.6, 1.0, -1.5), look_at=(26.4, .5, -.9),
                                  criteria='从侧面看冲到桶面、桶晃和倒下，再看辫梢取苹果并送到嘴边。')}),
    smoke=dict(ticks=700, forbid=[r'\[forage\][^\n]* garbage ram .*count=4'],
               expect=[r'\[forage\][^\n]* garbage pickup item=[^\n]* food=true',
                                  r'\[meal\][^\n]* swallowed item=',
                                  r'\[forage\][^\n]* garbage pickup item=minecraft:paper [^\n]*food=false',
                                  r'\[habitat\][^\n]* drop item=minecraft:paper count=1 holder=mouth',
                                  r'\[forage\][^\n]* garbage pickup item=mcyukkuri:junk_headwear [^\n]*holder=head',
                                  r'\[headwear\][^\n]* junk equipped variant=\S+ source=ground',
                                  r'\[forage\][^\n]* garbage ram [^\n]*tipped=false rock=(strong|weak) strokeTravel=0\.0*[1-9]',
                                  r'\[forage\][^\n]* garbage ram [^\n]*tipped=true',
                                  r'\[forage\][^\n]* garbage gave_up .*reason=too_heavy rams=3']))

_junk_scene = 'stray_junk_headwear'
add(_junk_scene, 'forage', '流浪：用垃圾顶替失去的头饰', '流浪：垃圾头饰',
    '左边低自尊的叼起假帽戴上并鞠躬道谢，后方中等自尊的先叼着低头犹豫，再戴上。中间戴瓶盖的被邻居嘲笑时含泪忍着，右后方的骄横油会把纸盒摇掉。拿手里的假帽右键骄横失饰者，它会鼓腹回绝并回嘴。也可在照料菜单取下，或换回真饰。',
    '手动试戴与取回', [
        item('mcyukkuri:junk_headwear', 1, -15, 5, name='junk_supply'),
        # The lowly one has its own fenced nook with the piece in it, so its treasure scan finds the piece before it wanders off.
        box(-16, 0, 4, -13, 1, 4, 'minecraft:oak_fence'),
        box(-16, 0, 7, -13, 1, 7, 'minecraft:oak_fence'),
        box(-16, 0, 4, -16, 1, 7, 'minecraft:oak_fence'),
        box(-13, 0, 4, -13, 1, 7, 'minecraft:oak_fence'),
        street_stray(_junk_scene, 'reimu', -14, 6, 'quiet_stray', accessory=False, boldness=0,
                     friendliness=100, label='低自尊失饰者', lead=True),
        set_status('quiet_stray', 'marks.dirt', 3),
        box(-11, 0, 4, -8, 1, 4, 'minecraft:oak_fence'),
        box(-11, 0, 7, -8, 1, 7, 'minecraft:oak_fence'),
        box(-11, 0, 4, -11, 1, 7, 'minecraft:oak_fence'),
        box(-8, 0, 4, -8, 1, 7, 'minecraft:oak_fence'),
        item('mcyukkuri:junk_headwear', 1, -10, 5, name='hesitant_supply'),
        street_stray(_junk_scene, 'reimu', -9, 6, 'hesitant_stray', accessory=False, boldness=45,
                     friendliness=65, label='中等自尊失饰者'),
        street_stray(_junk_scene, 'reimu', 14, 6, 'proud_stray', accessory=False, boldness=100,
                     friendliness=65, label='骄横失饰者'),
        street_stray(_junk_scene, 'reimu', 10, -2, 'shaking_stray', accessory=False, boldness=100,
                     friendliness=50, label='戴纸盒的骄横油').replace('Accessory:0b,',
                     'Accessory:0b,HatCustody:{State:"LOST",JunkHeadwear:"paper_box"},')[:-1]
                     + ',YukkuriMind:{Unease:48f,Migrated:1b,Course:"NONE"}}',
        street_stray(_junk_scene, 'marisa', 0, -2, 'covered_stray', accessory=False, boldness=30,
                     label='戴瓶盖的流浪油').replace('Accessory:0b,',
                     'Accessory:0b,HatCustody:{State:"LOST",JunkHeadwear:"bottle_cap"},'),
        # The wearer and the mocking neighbour share one fenced nook, so the neighbour's nearest junk wearer cannot wander off.
        box(-1, 0, -4, 2, 1, -4, 'minecraft:oak_fence'),
        box(-1, 0, -1, 2, 1, -1, 'minecraft:oak_fence'),
        box(-1, 0, -4, -1, 1, -1, 'minecraft:oak_fence'),
        box(2, 0, -4, 2, 1, -1, 'minecraft:oak_fence'),
        street_stray(_junk_scene, 'reimu', 1, -2, 'mocking_stray', friendliness=20, boldness=65,
                     yaw=90, label='会嘲笑的邻居'),
    ], give=['mcyukkuri:reimu_ribbon', 'mcyukkuri:junk_headwear 2', 'minecraft:spyglass'], env=DAY, spot='street', vantage=(0, 11),
    smoke=dict(ticks=1400, forbid=[],
               commands=[(300, 'damage ' + sel('covered_stray') + ' 100 minecraft:generic_kill'),
                         (340, 'execute if entity @e[type=mcyukkuri:yukkuri_remains,distance=..12,nbt={Remains:{components:{"minecraft:custom_data":{JunkHeadwear:"bottle_cap"}}}}] run say MCYJUNK_REMAINS'),
                         (600, 'execute unless entity @e[type=minecraft:item,tag=scn_junk_supply,distance=..25] run say MCYJUNK_NO_DUPLICATE')],
               expect=[r'\[contempt\][^\n]* junk mocked .*variant=bottle_cap affinity=0 -> -1',
                       r'\[headwear\][^\n]* junk mock wince variant=bottle_cap\b',
                       r'\[treasure\][^\n]* picked up drop=\S+ item=mcyukkuri:junk_headwear remaining=0 holder=mouth',
                       r'\[headwear\][^\n]* junk equipped variant=\S+ source=ground',
                       r'\[action\][^\n]* start id=think owner=headwear[^\n]*\n[\s\S]*?\[headwear\][^\n]* junk equipped variant=\S+ source=mouth [^\n]*band=hesitant',
                       r'\[action\][^\n]* start id=shake owner=headwear[^\n]*\n[\s\S]*?\[headwear\][^\n]* junk shaken_off variant=paper_box',
                       r'MCYJUNK_REMAINS', r'MCYJUNK_NO_DUPLICATE']))

_wind_stray = _uuid_text('stray_wind', 'wind_stray')
_wind_plain = _uuid_text('stray_wind', 'wind_plain')
add('stray_wind', 'forage', '流浪：钻进木板缝挡风', '流浪：钻进木板缝挡风',
    '冷夜里，聪明的幼体灵梦从空地叼起一张纸，带进低木板缝披上；另一只钻进木板缝缩身发抖。可在低缝外点篝火看纸落回地上。',
    '约半分钟', [
        'fillbiome ~-16 56 ~-6 ~16 80 ~9 minecraft:snowy_plains',
        block(-10, 1, -1, 'minecraft:spruce_planks'),
        box(-10, 0, -2, -10, 1, -2, 'minecraft:spruce_planks'),
        block(10, 1, -1, 'minecraft:spruce_planks'),
        box(10, 0, -2, 10, 1, -2, 'minecraft:spruce_planks'),
        item('minecraft:paper', 1, -10, 1, name='wind_paper'),
        street_stray('stray_wind', 'reimu', -10, 3, 'wind_stray', stage='child', intelligence=90, energy=58,
                     rest_wait=0, label='叼纸灵梦', lead=True),
        street_stray('stray_wind', 'reimu', 10, 3, 'wind_plain', stage='child', intelligence=20, energy=58,
                     rest_wait=0, label='缩身灵梦'),
    ], give=['minecraft:campfire', 'minecraft:spyglass'], env={'time': 'night'}, spot='street', vantage=(0, 14, 180, 15),
    smoke=dict(ticks=460,
               commands=[(320, 'setblock ~-10 ~ ~ minecraft:campfire[lit=true]')],
               forbid=[r'\[habitat\][^\n]* home chosen ', r'\[sleep\][^\n]* woken by danger reason=environment',
                       rf'\[habitat\][^\n]* uuid={_wind_plain} [^\n]* (?:wind cover paper approach|gathered item=minecraft:paper|paper cover draped)'],
               expect=[rf'\[habitat\][^\n]* uuid={_wind_stray} [^\n]* gathered item=minecraft:paper count=1 .*left=0',
                       rf'\[habitat\][^\n]* uuid={_wind_stray} [^\n]* paper bedding placed',
                       rf'\[habitat\][^\n]* uuid={_wind_stray} [^\n]* wind cover settled .*paper=true',
                       rf'\[habitat\][^\n]* uuid={_wind_stray} [^\n]* paper cover draped source=bedding item=minecraft:paper count=1',
                       rf'\[habitat\][^\n]* uuid={_wind_stray} [^\n]* paper cover drop item=minecraft:paper count=1 reason=(?:warmed|left_cover)',
                       rf'\[habitat\][^\n]* uuid={_wind_plain} [^\n]* wind cover settled .*paper=false']))

_bold_paper = _uuid_text('stray_paper', 'bold_paper')
_tired_paper = _uuid_text('stray_paper', 'tired_paper')
_loose_paper_id = _uuid_text('stray_paper', 'loose_paper')
def _wearing_paper_at(log, wearer, sheets, drape, at):
    covers = list(re.finditer(drape + rf'item=minecraft:paper count={sheets}\b', log[:at]))
    return bool(covers) and not re.search(rf'uuid={wearer} [^\n]* paper cover drop', log[covers[-1].end():at])

def _paper_sleep_phases(log):
    """Marisa finishes collecting before sleeping. Reimu is covered at the start of its exhausted nap."""
    marker = log.find('MCYPAPER_STACKS_EMPTY')
    if marker >= 0 and re.search(rf'\[sleep\][^\n]* uuid={_bold_paper} [^\n]* begin', log[:marker]):
        return 'bold paper slept before both stacks were empty'
    nap = re.search(rf'\[sleep\][^\n]* uuid={_tired_paper} [^\n]* begin', log)
    drape = rf'uuid={_tired_paper} [^\n]* paper cover draped source=bedding '
    if nap and not _wearing_paper_at(log, _tired_paper, 2, drape, nap.start()):
        return 'tired Reimu lacked its two-sheet cover when it fell asleep'
    return None
add('stray_paper', 'forage', '流浪：抽报纸铺垫，冬夜盖纸发抖', '流浪：抽报纸盖身',
    '雪原冬夜，疲倦的亚成灵梦从西边报纸堆取两张盖身，体力耗尽后睡着。东边魔理沙衔走散纸和最后一张报纸，两张只够垫身。看完盖纸，用打火石点亮东边篝火，观察魔理沙在火外一圈歇脚。',
    '约半分钟', [
        'fillbiome ~-20 56 ~-18 ~20 71 ~19 minecraft:snowy_plains',
        block(-6, 1, -1, 'minecraft:spruce_planks'),
        box(-6, 0, -2, -6, 1, -2, 'minecraft:spruce_planks'),
        block(6, 1, -1, 'minecraft:spruce_planks'),
        box(6, 0, -2, 6, 1, -2, 'minecraft:spruce_planks'),
        block(-6, 0, 2, 'mcyukkuri:newspaper_stack[bundles=1,facing=south,taken=2]'),
        block(6, 0, 2, 'mcyukkuri:newspaper_stack[bundles=1,facing=south,taken=3]'),
        item('minecraft:paper', 1, 7, 0, name='loose_paper').replace(
            'PickupDelay:', f'UUID:{uuid_nbt(uid("stray_paper", "loose_paper"))},PickupDelay:'),
        block(9, 0, 2, 'minecraft:campfire[lit=false]'),
        street_stray('stray_paper', 'marisa', 6, 3, 'bold_paper', intelligence=90, boldness=75, energy=70,
                     rest_wait=0, yaw=180, label='垫纸魔理沙'),
        street_stray('stray_paper', 'reimu', -6, 3, 'tired_paper', stage='teen', intelligence=90, boldness=25, energy=28,
                     rest_wait=0, yaw=180, label='疲惫灵梦', lead=True),
    ], give=['minecraft:flint_and_steel', 'mcyukkuri:cardboard_shelter', 'minecraft:spyglass'],
    env={'time': 'night'}, spot='street', vantage=(0, 8, 180, 15),
    reads=[('读垫纸魔理沙', f'yukkuri status {sel("bold_paper")} raw'),
           ('读盖纸灵梦', f'yukkuri status {sel("tired_paper")} raw')],
    smoke=dict(ticks=1120,
               commands=[(760, 'execute if block ~-6 ~ ~2 minecraft:air if block ~6 ~ ~2 minecraft:air run say MCYPAPER_STACKS_EMPTY'),
                         (770, block(9, 0, 2, 'minecraft:campfire[lit=true]'))],
               readout=[f'yukkuri status {sel("bold_paper")} raw', f'yukkuri status {sel("tired_paper")} raw'],
               forbid=[r'\[habitat\][^\n]* home chosen ', r'\[sleep\][^\n]* woken by danger reason=environment',
                       r'\[body\][^\n]* (?:burn remembered|sole burned|accepted damage cause=(?:inFire|onFire|hotFloor|campfire))',
                       rf'\[habitat\][^\n]* uuid={_bold_paper} [^\n]* paper cover draped'],
               expect=[rf'\[habitat\][^\n]* uuid={_bold_paper} [^\n]* wind cover paper approach',
                       rf'\[habitat\][^\n]* uuid={_bold_paper} [^\n]* gathered item=minecraft:paper count=1 source={_loose_paper_id} left=0',
                       rf'\[habitat\][^\n]* uuid={_bold_paper} [^\n]* paper bedding placed',
                       rf'\[habitat\][^\n]* uuid={_tired_paper} [^\n]* paper cover fetch',
                       rf'\[habitat\][^\n]* uuid={_tired_paper} [^\n]* paper cover draped source=bedding item=minecraft:paper count=2',
                       rf'\[habitat\][^\n]* uuid={_tired_paper} [^\n]* cold paper exhausted sleep',
                       rf'\[sleep\][^\n]* uuid={_tired_paper} [^\n]* begin source=life',
                       r'\[temperature\][^\n]* cold complaint .*line=dialogue\.mcyukkuri\.',
                       rf'\[temperature\][^\n]* uuid={_bold_paper} [^\n]* fire rest .*ring=2\..*rate=-',
                       r'MCYPAPER_STACKS_EMPTY'],
               verify=_paper_sleep_phases))

_devoted_mother = uid('stray_paper_young', 'devoted_parent')
_self_mother = uid('stray_paper_young', 'self_parent')
_devoted_parent_txt = _uuid_text('stray_paper_young', 'devoted_parent')
_devoted_baby_txt = _uuid_text('stray_paper_young', 'devoted_baby')
_self_parent_txt = _uuid_text('stray_paper_young', 'self_parent')
_self_baby_txt = _uuid_text('stray_paper_young', 'self_baby')
def _paper_young_fire_phases(log):
    """Check the covers at the lighting boundary and the baby's sleep only after reaching the fire."""
    lighting = log.find('MCYPAPER_YOUNG_FIRE_LIT')
    if lighting < 0:
        return 'missing the fire lighting marker'
    for wearer, sheets in ((_devoted_parent_txt, 3), (_devoted_baby_txt, 1), (_self_parent_txt, 3)):
        drape = (rf'paper cover draped on child={wearer} ' if wearer == _devoted_baby_txt else
                 rf'uuid={wearer} [^\n]* paper cover draped source=bedding ')
        if not _wearing_paper_at(log, wearer, sheets, drape, lighting):
            return f'{wearer} lacked its {sheets}-sheet cover when the fire was lit'
    marker = re.search(rf'\[temperature\][^\n]* uuid={_devoted_baby_txt} [^\n]* fire rest', log)
    if marker and re.search(rf'\[sleep\][^\n]* uuid={_devoted_baby_txt} [^\n]* begin', log[:marker.start()]):
        return 'devoted baby slept before it warmed at the fire'
    return None
add('stray_paper_young', 'forage', '流浪：亲油抽报纸先盖赤油，自利者先盖自身', '流浪：亲油叼纸盖赤油',
    '雪原冬夜，东西两处矮木板檐缝各歇着一户母子，中央有两捆报纸。西侧重亲情的魔理沙先叼一张给赤油披上，再逐张叼三张盖自己。东侧自利的灵梦先盖自己。听赤油哭诉，披好纸后点亮西侧篝火，观察赤油朝火、亲油靠外。',
    '约一分钟', [
        'fillbiome ~-20 56 ~-18 ~20 71 ~19 minecraft:snowy_plains',
        box(-6, 0, -2, 6, 1, -2, 'minecraft:spruce_planks'),
        box(-5, 1, -1, -2, 1, -1, 'minecraft:spruce_slab[type=top]'),
        box(2, 1, -1, 5, 1, -1, 'minecraft:spruce_slab[type=top]'),
        box(-6, 0, -1, -6, 1, -1, 'minecraft:spruce_planks'),
        box(6, 0, -1, 6, 1, -1, 'minecraft:spruce_planks'),
        block(0, 0, 0, 'mcyukkuri:newspaper_stack[bundles=2,facing=south,taken=0]'),
        block(-3, 0, 3, 'minecraft:campfire[lit=false]'),
        street_stray('stray_paper_young', 'marisa', -2, 2, 'devoted_parent', intelligence=90, boldness=65, attachment=85, friendliness=85,
                     energy=88, rest_wait=0, yaw=180, label='重亲情魔理沙', lead=True),
        street_stray('stray_paper_young', 'marisa', -4, 0, 'devoted_baby', stage='baby', energy=58, rest_wait=0, yaw=180,
                     kin=f'Mother:{uuid_nbt(_devoted_mother)}', childhood=CHILDHOOD_HOPPING, label='披纸赤油'),
        street_stray('stray_paper_young', 'reimu', 2, 2, 'self_parent', intelligence=90, boldness=65, attachment=18, friendliness=18,
                     energy=88, rest_wait=0, yaw=180, label='自利灵梦'),
        street_stray('stray_paper_young', 'reimu', 4, 0, 'self_baby', stage='baby', energy=58, rest_wait=0, yaw=180,
                     kin=f'Mother:{uuid_nbt(_self_mother)}', childhood=CHILDHOOD_HOPPING, label='发抖赤油'),
    ], give=['minecraft:flint_and_steel', 'mcyukkuri:cardboard_shelter', 'minecraft:spyglass'],
    env={'time': 'night'}, spot='street', vantage=(0, 5, 180, 16),
    reads=[('读重亲情魔理沙（给赤油盖完再盖自己）', f'yukkuri status {sel("devoted_parent")} raw'),
           ('读披纸赤油（寒战减轻）', f'yukkuri status {sel("devoted_baby")} raw'),
           ('读自利灵梦（盖纸自身）', f'yukkuri status {sel("self_parent")} raw'),
           ('读无纸赤油（继续发抖）', f'yukkuri status {sel("self_baby")} raw')],
    capture=dict(shots=(1, 140, 240, 500), sequences=((80, 300, 4),), observe=False,
                 views={'side': dict(position=(-2.4, .75, 1.35), look_at=(-3.55, .28, -.5))}),
    smoke=dict(ticks=1620,
               commands=[(1050, 'execute if block ~ ~ ~ mcyukkuri:newspaper_stack[bundles=1,taken=3] run say MCYPAPER_YOUNG_ONE_LEFT'),
                         (1250, block(-3, 0, 3, 'minecraft:campfire[lit=true]')),
                         (1251, 'execute if block ~-3 ~ ~3 minecraft:campfire[lit=true] run say MCYPAPER_YOUNG_FIRE_LIT')],
               readout=[f'yukkuri status {sel("devoted_parent")} raw', f'yukkuri status {sel("devoted_baby")} raw',
                        f'yukkuri status {sel("self_parent")} raw', f'yukkuri status {sel("self_baby")} raw'],
               forbid=[r'\[habitat\][^\n]* home chosen ', r'\[sleep\][^\n]* woken by danger reason=environment',
                       r'\[body\][^\n]* (?:burn remembered|sole burned|accepted damage cause=(?:inFire|onFire|hotFloor|campfire))',
                       rf'\[habitat\][^\n]* uuid={_self_parent_txt} [^\n]* paper cover draped on child='],
               expect=[rf'\[habitat\][^\n]* uuid={_devoted_parent_txt} [^\n]* wind cover paper approach',
                       rf'\[habitat\][^\n]* uuid={_devoted_parent_txt} [^\n]* baby paper cover approach child={_devoted_baby_txt}',
                       rf'\[habitat\][^\n]* uuid={_devoted_parent_txt} [^\n]* paper cover draped on child={_devoted_baby_txt} item=minecraft:paper count=1',
                       rf'\[habitat\][^\n]* uuid={_devoted_parent_txt} [^\n]* paper cover draped source=bedding item=minecraft:paper count=3',
                       rf'\[habitat\][^\n]* uuid={_self_parent_txt} [^\n]* paper cover kept for self child={_self_baby_txt}',
                       rf'\[habitat\][^\n]* uuid={_self_parent_txt} [^\n]* paper cover draped source=bedding item=minecraft:paper count=3',
                       rf'\[temperature\][^\n]* uuid={_devoted_baby_txt} [^\n]* cold complaint .*line=dialogue\.mcyukkuri\..*\.baby\.s_cold parent={_devoted_parent_txt}',
                       rf'\[family\][^\n]* uuid={_devoted_parent_txt} [^\n]* heard cold cry child={_devoted_baby_txt}',
                       rf'\[temperature\][^\n]* uuid={_devoted_baby_txt} [^\n]* fire rest .*ring=2\..*rate=-.*shield_parent={_devoted_parent_txt}',
                       r'MCYPAPER_YOUNG_ONE_LEFT'],
               verify=_paper_young_fire_phases))

# A forest burrow on the family mound with two beds and a stocked store, the way the mold scenes place one: the beds are the
# whole capacity, so two residents fill it. The pile is a real forage pile the burrow adopts at its store cell. Lining and
# branches are preset full: a resident that still wants bedding leaves the burrow to gather grass and its habitat errand holds
# the body for hundreds of ticks, which keeps a scene that wants it at home (or free for its own drama) from ever catching it in.
def _den_cell(dx, dz):
    x, z = BY_ID['den'].spots['family']
    return f'{((x + dx) << 38) | (((z + dz) & 0x3ffffff) << 12) | FEET}L'


def _two_bed_burrow(stock):
    beds = f'{_den_cell(-1, -3)},{_den_cell(1, -3)}'
    lines = [block(0, 0, -1, f'mcyukkuri:forest_burrow[facing=south]{{Condition:100,Lining:4,Branches:3,NaturalBeds:[L;{beds}],'
                   f'NaturalPile:{_den_cell(-2, -4)},NaturalPileStand:{_den_cell(-1, -4)}}}')]
    if stock:
        items = ','.join(f'{{Slot:{slot}b,id:"minecraft:short_grass",count:{stock}}}' for slot in range(3))
        lines.append(block(-2, 0, -4, f'mcyukkuri:forage_pile{{Items:[{items}]}}'))
    return lines


# Two sated residents live in a stocked burrow; a bandit band stands south in the open, in sight of the door. Once the band is made
# a real bandit group, the raider (nutrition 30 < 45) walks to the door and eats from the store bite by bite, the mate (50, below the
# gang line 55) follows the same den while the raider is at it, and the fed control (95) does not move on the den at all.
# The residents are kept at energy 36: under the 40 line they neither dig the crowded cave wider nor move to the bigger den in the
# next mound, so the burrow stays occupied and stocked, which is what makes it raidable at all (an emptied burrow is not).
_RAID = {name: uid('den_raid', name) for name in ('raider', 'mate', 'fed', 'host', 'guest')}
_RAID_MOTHER = uid('den_raid', 'mother')
_RAID_TXT = {name: _uuid_text('den_raid', name) for name in _RAID}
add('den_raid', 'den', '饿了的匪帮走到有存粮的洞口抢粮，吃饱的不动', '饿了的匪帮抢存粮的洞',
    '洞里有两只吃饱的住户和一堆草存粮；南边空地上三只匪帮：最西的灵梦营养 30（低于 45）且不挑食、旁边的魔理沙营养 50（低于 55、高于 45）、'
    '最东吃饱的魔理沙营养 95。约 20 秒后匪帮被喂饿：饿极的灵梦先看着洞口走过去，在洞口一口一口吃存粮（至多 3 口，每口隔约 3 秒，营养到 65 就停），'
    '住户有的胆大会鼓脸、胆小的会哭，好感都下降；住户里看得见它的会把它赶开，它之后 1 分钟不再走向这个洞。'
    '魔理沙（50）只在灵梦已经在抢时才跟过去，同样一口口吃；吃饱的魔理沙不理这个洞。洞里的存粮真实减少。'
    '约 30 秒内发生；一次没看到可在 M 菜单重开',
    '约 30–90 秒', [
        *_two_bed_burrow(16),
        box(-8, 0, 3, 8, 0, 14, 'minecraft:air', 'replace minecraft:short_grass'),
        yk('reimu', -1, -3, 'host', uuid=_RAID['host'], label='住户', nutrition=100, energy=36, friendliness=70, social_wait=0, yaw=180,
           kin=kin_of(Mother=_RAID_MOTHER)),
        yk('marisa', 1, -3, 'guest', uuid=_RAID['guest'], label='住户', nutrition=100, energy=36, friendliness=70, social_wait=0, yaw=180,
           kin=kin_of(Mother=_RAID_MOTHER)),
        yk('reimu', -2, 8, 'raider', uuid=_RAID['raider'], label='匪帮·饿极30', nutrition=90, energy=100, boldness=70, picky=20, social_wait=0,
           yaw=0, lead=True, also=('band',)),
        yk('marisa', 0, 9, 'mate', uuid=_RAID['mate'], label='匪帮·营养50', nutrition=90, energy=100, boldness=60, social_wait=0,
           yaw=0, also=('band',)),
        yk('marisa', 4, 8, 'fed', uuid=_RAID['fed'], label='匪帮·吃饱95', nutrition=95, energy=100, boldness=60, social_wait=0,
           yaw=0, also=('band',)),
        home('host', 0, -1), home('guest', 0, -1),
        f'yukkuri scenario community {sel("raider")} {sel_all("band")} bandits',
    ], late=[Retry(f'tp {sel("raider")} ~-1.5 ~ ~8.5', sel('raider'), tries=1, first=399),
             Retry(set_status('raider', 'needs.nutrition', 30), sel('raider'), tries=1, first=400),
             Retry(set_status('mate', 'needs.nutrition', 50), sel('mate'), tries=1, first=400)],
    env=DAY, spot='family', vantage=(0, 14),
    note='待验证：新场景，尚未在游戏里实测。存粮是预置的真实草堆，洞会在开场后收养它；饿是约 20 秒后才写入的初态，同时把逛走的匪帮灵梦放回南边原位（看得见洞口的地方）。',
    reads=[('读洞与存粮', 'data get block ~ ~ ~-1'), ('读饿的灵梦', f'yukkuri status {sel("raider")} raw'),
           ('读营养 50 的魔理沙', f'yukkuri status {sel("mate")} raw'), ('读吃饱的魔理沙', f'yukkuri status {sel("fed")} raw')],
    smoke=dict(ticks=2400, expect=[rf'\[settlement\][^\n]* uuid={_RAID_TXT["raider"]} [^\n]*den raid approach den=',
                                   rf'\[settlement\][^\n]* uuid={_RAID_TXT["raider"]} [^\n]*den raid bite den='],
               forbid=[rf'\[settlement\][^\n]* uuid={_RAID_TXT["fed"]} [^\n]*den raid', r'\[body\][^\n]* invariant\b']))

# A selfish, tired owner and its sister fill a two-bed burrow. The owner (friendliness 5, self control 10: top selfish gate; energy 45 < 50)
# keeps its sister out of the burrow for 600-900 ticks (600 + 3 * (100 - friendliness) = 885) or until it has slept and woken. The owner
# itself is not part of the scene: it uses its own bed as usual. No dice: the scene is there as soon as the two are in the burrow.
# Sisters by a shared mother: the card bars only kin (Bonds.careKin), a partner's kin roster is empty.
_MONO = {name: uid('den_monopoly', name) for name in ('owner', 'mate')}
_MONO_MOTHER = uid('den_monopoly', 'mother')
add('den_monopoly', 'den', '自私又累的油占着窝，不让姐妹用', '自私又累的油占窝',
    '两张床的洞里住着一对姐妹：灵梦自私（友善 5、自制 10）、精力 38 低于 50，魔理沙是它的姐妹、友善。两张床都被占，窝挤，'
    '两只都累得不够去加挖（精力低于 40），所以谁也不会把家搬走；'
    '灵梦开场后不久（每 5 秒查一次）就占着窝：魔理沙被挡在外面，走到洞口外两格站着等并摆出“等轮到我”的哭脸，'
    '灵梦照常用自己的床，头顶显示“我的”；这期间魔理沙对灵梦的好感缓慢下降。'
    '持续 600 + 3×(100 − 友善) = 885 tick（约 44 秒），或灵梦睡过一觉醒来就结束。结束时若魔理沙对灵梦的好感总和为负，灵梦垂泪道歉、好感回升，魔理沙开心；'
    '否则只是“守住了窝”。之后至少 1 分钟灵梦不再占窝。约 10 秒内发生；一次没看到可在 M 菜单重开',
    '约 15–60 秒', [
        *_two_bed_burrow(16),
        yk('reimu', -1, -3, 'owner', uuid=_MONO['owner'], label='自私·累 精力38', nutrition=100, energy=38, friendliness=5, self_control=10,
           social_wait=0, yaw=180, lead=True, kin=kin_of(Mother=_MONO_MOTHER)),
        yk('marisa', 1, -3, 'mate', uuid=_MONO['mate'], label='姐妹·友善', nutrition=100, energy=36, friendliness=80, social_wait=0,
           yaw=180, kin=kin_of(Mother=_MONO_MOTHER)),
        home('owner', 0, -1), home('mate', 0, -1),
    ], env=DAY, spot='family', vantage=(0, 10),
    note='待验证：新场景，尚未在游戏里实测。',
    reads=[('读自私的灵梦', f'yukkuri status {sel("owner")} raw'), ('读姐妹', f'yukkuri status {sel("mate")} raw')],
    smoke=dict(ticks=1400, expect=[r'\[wild-event\][^\n]*event=started[^\n]*card=nest-monopoly',
                                   r'\[wild-event\][^\n]*event=ended[^\n]*card=nest-monopoly'],
               forbid=[r'\[body\][^\n]* invariant\b']))

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
# Peers snub visible dirt on a value- and cadence-driven chance: Contempt looks every IDLE_WAIT ticks at peers inside RANGE.
DIRT_SNUB = r'\[contempt\][^\n]* dirt snub target=\S+ dirt=[23]'
DIRT_SNUB_RANGE, DIRT_SNUB_LOOK = 6, 60


def _dirt_snub_verify(window, trace):
    """The peer snub is only demanded when a real look could see the dirt: the dirty individual stayed inside the look
    range of a peer for longer than one look interval (trace positions, one sample per tick)."""
    if re.search(DIRT_SNUB, window):
        return None
    washed = re.findall(r'\[interaction\] [^\n]*? uuid=([0-9a-f-]{36}) [^\n]*\bwash ', window)
    if not washed:
        return None  # the wash expectation already reports the missing wash, and the dirty actor is unknown
    dirty = washed[0]
    samples = {}
    for line in trace:
        if '[trace] ' not in line:
            continue
        try:
            row = json.loads(line.split('[trace] ', 1)[1])
        except ValueError:
            continue
        state = row.get('st') or {}
        if 'p' in state:
            samples.setdefault(row['t'], {})[row['u']] = state['p']
    longest = run = 0
    for tick in sorted(samples):
        here = samples[tick].get(dirty)
        run = run + 1 if here is not None and any(math.dist(here, p) <= DIRT_SNUB_RANGE
                                                 for uuid, p in samples[tick].items() if uuid != dirty) else 0
        longest = max(longest, run)
    if longest >= DIRT_SNUB_LOOK:
        return (f'dirt snub missing though the dirty individual stayed within {DIRT_SNUB_RANGE} blocks of a peer '
                f'for {longest} ticks')
    return None


add('care_attitude', 'meadow', '照料时的态度：洗涤与油粮', '照料时的态度：洗涤与油粮',
    '先用望远镜看看同类的态度。给脏的灵梦用湿布，看看它如何回应；在生存模式给营养 50、舌肥 95 的强硬魔理沙递预算粮，它含一下再吐掉，再试高级粮。右侧饿的个体也会吃预算粮。',
    '右键后看表情，约 2 秒吞咽', [
        yk('reimu', -7, 0, 'wash_shy', boldness=0, friendliness=75, hygiene=5, lead=True, also=('care_subject',)),
        set_status('wash_shy', 'marks.dirt', 3),
        yk('marisa', 0, 0, 'grain_proud', nutrition=50, boldness=100, friendliness=0, self_control=0, picky=95, also=('care_subject',)),
        yk('marisa', 7, 0, 'grain_hungry', nutrition=29, boldness=100, friendliness=0, self_control=0, picky=95, also=('care_subject',)),
        yk('chen', -5, 0, 'dirt_observer', friendliness=0, also=('care_subject',)),
    ], give=['minecraft:spyglass', 'mcyukkuri:wash_cloth[minecraft:custom_data={Water:8}]',
             'mcyukkuri:oil_grain_budget 4', 'mcyukkuri:oil_grain_premium 2'], env=DAY, vantage=(0, 11),
    reads=[('读脏污、卫生和态度', 'yukkuri status @e[tag=scn_care_subject,distance=..20] raw')],
    smoke=dict(ticks=280, forbid=[], commands=[
        (60, f'yukkuri scenario care_click {sel("grain_proud")} budget_grain'),
        (61, f'yukkuri scenario care_click {sel("grain_hungry")} budget_grain'),
        (160, f'yukkuri scenario care_click {sel("wash_shy")} wet_cloth'),
        (170, f'yukkuri scenario care_click {sel("grain_proud")} premium_grain'),
    ], expect=[r'\[interaction\][^\n]* wash player=\S+ response=guarded [^\n]*dirt=3->2',
               r'\[meal\][^\n]* declined serving item=mcyukkuri:oil_grain_budget [^\n]*reason=taste',
               r'\[meal\][^\n]* spat item=mcyukkuri:oil_grain_budget ',
               r'\[meal\][^\n]* swallowed item=mcyukkuri:oil_grain_budget ',
               r'\[meal\][^\n]* swallowed item=mcyukkuri:oil_grain_premium '],
               verify=_dirt_snub_verify, verify_trace=True))

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
    '油库里困了，自己走回有顶的窝里睡觉；睡足醒来先在窝里伸一个长懒腰、打哈欠，再抖抖身子起来（饿醒的直接起身）', '约 1 分钟', [
    *shed(7, 0), yk(('reimu', 'marisa'), 0, 0, 'a', uuid=uid('sleep', 'a'), energy=95,
                   identity=('wild_family', 'stray'), rest_wait=0, yaw=-90, lead=True),
    home('a', 7, 0)], vantage=(7, -7, 0, 35), env=DAY,
    # The home is set two seconds after the summon, so the tiredness comes only once the nest is hers (energy below the rest
    # threshold, above the in-place nap's 25).
    late=[Retry(f'execute as {sel("a")} run yukkuri status set @s needs.energy 30', sel('a'), tries=1, first=160)],
    # The bed's own doorway is its north side: the body walks round to it, steps in, sleeps, then steps out through it again.
    # Second view: in front of her face, for the waking stretch and yawn.
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
    '前排胆量10／90、精力0，原地睡约12／28秒；后排精力20，缓慢并打盹1–3秒，两次间隔至少15秒、平均约32秒（“读短打盹”的 gap 为剩余间隔）。在 M 菜单的「实用」页下雨或手动轻击正在睡的个体，应立即醒来且困值最多50。睡着时捕食种靠近不会被感知，实际攻击仍唤醒',
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
        yk('reimu', -5, 7, 'starving', label='营养0·虚弱缓慢', nutrition=0, energy=95, uuid=uid('nutrition_age', 'starving')),
        yk('reimu', 5, 7, 'fed', label='营养100·同种对照', nutrition=100, energy=95),
        yk('reimu', -12, 14, 'tired', label='精力18·虚弱', nutrition=40, energy=18, uuid=uid('nutrition_age', 'tired')),
        item('minecraft:apple', 1, -12, 12, 'tired_food'),
        yk('reimu', 12, 14, 'tired_ctl', label='精力95·健康对照', nutrition=40, energy=95, uuid=uid('nutrition_age', 'tired_ctl')),
        item('minecraft:apple', 1, 12, 12, 'tired_ctl_food')],
    env=DAY, give=['minecraft:apple 4', 'minecraft:spyglass'], vantage=(0, 16),
    reads=[(f'读{stage}',f'yukkuri status {sel("age_"+stage)} raw') for stage in ('baby','child','teen','adult')]
        + [('读饥饿者',f'yukkuri status {sel("starving")} raw'),('读同种对照',f'yukkuri status {sel("fed")} raw'),('读原版效果',f'data get entity {sel("starving")} active_effects')])

add('meet', 'meadow', '熟的走近蹭蹭，生的先问候', '熟的走近蹭蹭，生的先问候',
    '北边一对橙和爱丽丝互相认识、想亲近，会自己走近、问候、蹭蹭；南边一对橙和爱丽丝素不相识（亲近度 0），一只走过去时另一只停下看着它，接触时先问候、对方回礼，'
    '之后读数里出现彼此的关系、亲近度大于 0；再靠近看陌生的两只能否主动问候中立的你', '持续观察约 2 分钟（暂停不计时）', [
    yk('chen', -2, -3, 'a', uuid=uid('meet', 'a'), intelligence=(45, 65),
       yaw=-90, affection=95, attachment=100, social_wait=0, lead=True),
    yk('alice', 2, -3, 'b', yaw=90, affection=95, attachment=100, social_wait=0),
    f'yukkuri scenario bond {sel("a")} {sel("b")}',
    yk('chen', -2, 3, 'stranger_a', label='陌生橙', yaw=-90, attachment=95, affection=0, social_wait=0),
    yk('alice', 2, 3, 'stranger_b', label='陌生爱丽丝', yaw=90, attachment=95, affection=0, social_wait=0)],
    env=DAY, vantage=(0, 14), give=['mcyukkuri:field_notebook'],
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

# Two ordinary friend pairs side by side, one geometry question each. West: an adult and a child of clearly different body
# size on flat grass. East: two adults of one size across a real one-block grass terrace (no slab), the upper one standing
# .375 inside the west edge -- as near that edge as its support rule allows while the lower body stays clear of the step.
# Each pair is bonded. The west adult and both east adults start lonely, with strongly attached partners who are more
# likely to accept. The lower east peer stands beside the riser, where ordinary solo leisure has less room.
_SHAPES = {name: uid('nuzzle_shapes', name) for name in ('wide', 'small', 'high', 'low')}

add('nuzzle_shapes', 'meadow', '大小不同的并排蹭，隔台阶的一对也凑近', '大小与台阶的贴身蹭',
    '西边一大一小（成体与子油）是熟识的一对：大的走进小的身边并排贴蹭，看两身的侧面如何对齐、接触处有没有穿模。'
    '东边两只同身成体都想亲近，分别站在一格高的草地台阶上下。看哪只自己换到同一层，'
    '两只落稳、侧面贴上后再蹭，接触处有没有穿模。',
    '约 30–60 秒（暂停不计时）', [
        box(2, 0, -4, 6, 0, 0, 'minecraft:grass_block'),
        yk('reimu', -6, 3, 'wide', uuid=_SHAPES['wide'], yaw=-90, affection=95, attachment=80, label='成体·宽身', lead=True),
        yk('reimu', -2.5, 3, 'small', uuid=_SHAPES['small'], yaw=90, stage='child', affection=0, attachment=100, label='子油·小身'),
        f'yukkuri scenario bond {sel("wide")} {sel("small")}',
        yk('reimu', 1.875, -2, 'high', uuid=_SHAPES['high'], yaw=90, dy=1, affection=95, attachment=100, label='台阶上'),
        yk('reimu', .5, -2, 'low', uuid=_SHAPES['low'], yaw=-90, affection=95, attachment=100, label='台阶下'),
        f'yukkuri scenario bond {sel("high")} {sel("low")}',
    ], env=DAY, vantage=(-2, -8, 0, 15),
    reads=[('读四只的状态', 'yukkuri status @e[tag=scn,distance=..12] raw')])

# A tired Reimu (energy 38: below the 40 warn line, above the 35 drowsy line, so it neither dozes nor leaves for a bed) stands in a low fence pen.
# No nest in the scene: a bed draws every homeless body in sight toward it, the friend included. West: a bonded, friendly, bold Marisa. East: an unknown, timid, unfriendly Chen who must not come.
_COMPANY = {name: uid('company_visit', name) for name in ('weak', 'friend', 'stranger')}
add('company_visit', 'meadow', '累了的朋友招来陪伴，陌生的不来', '累了的朋友招来陪伴',
    '中间的灵梦精力 38（低于 40 的预警线、高于 35 的困倦线，所以不打盹），站在一圈矮围栏里（3×3，西面留一格缺口；场上没有窝，免得朋友们都被窝引走）。西边熟识、友善、胆大的魔理沙（精力 90）看见朋友累了，'
    '走到它面前面对面站着闭嘴微笑，灵梦的不安减轻；灵梦恢复、走开或睡着，或满约 30 秒，访问结束，之后 2 分钟内没有谁再来陪它。'
    '东边素不相识、胆小、不友善的橙不会过来。约 10 秒内发生；一次没看到可在 M 菜单重开',
    '约 10–40 秒', [
        # A low fence pen (3x3 inside, one gap facing the friend) keeps the tired guest from wandering off before contact.
        box(-2, 0, -2, 2, 0, 2, 'minecraft:oak_fence'), box(-1, 0, -1, 1, 0, 1, 'minecraft:air'), block(-2, 0, 0, 'minecraft:air'),
        yk('reimu', 0, 0, 'weak', uuid=_COMPANY['weak'], label='累了·精力38', energy=38, yaw=-90, lead=True),
        yk('marisa', -5, 0, 'friend', uuid=_COMPANY['friend'], label='熟识·友善·胆大', energy=90, friendliness=100,
           attachment=80, boldness=90, yaw=-90),
        f'yukkuri scenario bond {sel("weak")} {sel("friend")}',
        yk('chen', 6, 0, 'stranger', uuid=_COMPANY['stranger'], label='陌生·胆小·不友善', energy=90, friendliness=20,
           attachment=30, boldness=10, yaw=90),
    ], env=DAY, vantage=(0, 14),
    reads=[('读累了的灵梦', f'yukkuri status {sel("weak")} raw'), ('读魔理沙', f'yukkuri status {sel("friend")} raw'),
           ('读陌生的橙', f'yukkuri status {sel("stranger")} raw')])

# North: a hungry, patient-less Reimu claims the apple at once beside a sated friend, a real food claim that leaves both a quarrel record;
# both are warm, so one walks up to make up and the other answers. South: the same claim between two who hold each other in hostility
# (affinity -60 both ways): the record exists but neither is above the hostile line, so neither approaches.
_QUARREL = {name: uid('quarrel_makeup', name) for name in ('claimer', 'rival', 'foe_claimer', 'foe_rival')}
add('quarrel_makeup', 'meadow', '抢食吵架后，温和的走去和好；敌对的不来', '抢食吵架后和好',
    '北边饿着的灵梦（自制极低、友善）一见脚边的苹果就护食抢下，旁边不太饿、同样自制极低的灵梦（友善）也在抢，先到嘴的护食，另一只被抢，双方各记下这次吵架。吃完后它们闲下来，温和的一方会走过去问候，'
    '另一方好感不低于 -10，回应问候，双方好感回升；若对方不领情则只转头看着、保持距离。每次吵架至多试一次。'
    '南边同样的抢食发生在互相敌对（好感 -60）的两只之间：有吵架记录，但谁也不会走近。约 2 分钟内发生；一次没看到可在 M 菜单重开',
    '约 1–2 分钟', [
        # North pen (6x3 inside, one gap on the far north side): the pair cannot wander out of sight of each other before one makes up.
        box(-5, 0, -10, 2, 0, -6, 'minecraft:oak_fence'), box(-4, 0, -9, 1, 0, -7, 'minecraft:air'), block(-1, 0, -10, 'minecraft:air'),
        yk('reimu', -3, -8, 'claimer', uuid=_QUARREL['claimer'], label='饿·自制极低·友善', nutrition=30, self_control=5, friendliness=85,
           attachment=70, social_wait=0, yaw=-90, lead=True),
        item('minecraft:apple', 1, -1, -8, name='apple_north'),
        yk('reimu', 0, -8, 'rival', uuid=_QUARREL['rival'], label='不太饿·自制极低·友善', nutrition=55, self_control=5, friendliness=85, attachment=70,
           social_wait=0, yaw=90),
        f'yukkuri scenario bond {sel("claimer")} {sel("rival")}',
        yk('reimu', -3, 8, 'foe_claimer', uuid=_QUARREL['foe_claimer'], label='敌对·饿', nutrition=30, self_control=5, friendliness=85,
           attachment=70, social_wait=0, yaw=-90),
        item('minecraft:apple', 1, -1, 8, name='apple_south'),
        yk('reimu', 0, 8, 'foe_rival', uuid=_QUARREL['foe_rival'], label='敌对·不太饿', nutrition=55, self_control=5, friendliness=85, attachment=70,
           social_wait=0, yaw=90),
        f'yukkuri status relate {sel("foe_claimer")} {sel("foe_rival")} -60',
        f'yukkuri status relate {sel("foe_rival")} {sel("foe_claimer")} -60',
    ], env=DAY, vantage=(0, 16), note='待验证：新场景，尚未在游戏里实测。',
    reads=[('读北边饿的', f'yukkuri status {sel("claimer")} raw'), ('读北边吃饱的', f'yukkuri status {sel("rival")} raw'),
           ('读南边饿的', f'yukkuri status {sel("foe_claimer")} raw'), ('读南边吃饱的', f'yukkuri status {sel("foe_rival")} raw')])

# Two piles on the street, far enough apart that nobody at one sees the other's food. The starving stray (nutrition 5, below the
# 30 line: it never inspects, and a second apple at 25 is still below it) has two apples of its own and eats both: it must not
# stand at an apple at 30 or more, where its first look would be a forbidden pause, and the pair's single apple is gone before
# it has finished (the timid one takes it after its long pause). The other pile is one apple between two strays: a stack no
# larger than the eaters makes a hunger order, so the hungrier one (25 or more below the other) goes first and the other waits.
# West: a timid, dull stray (long inspect pause) and the hungrier of the two. East: a bold, clever one (short pause); 27 above
# the timid one, so it is the one that yields (a body at 75 or more is too fed to go for loose food at all). A hedge row stands
# between the piles: a hungry stray (12-49, and the starving one after its first apple) that sees a peer chewing starts a
# BEG_BITE arc instead of taking its own turn, so neither group sees the other eat.
_STRANGE = {name: uid('stray_strange_food', name) for name in ('timid', 'bold', 'starving')}
add('stray_strange_food', 'forage', '流浪：陌生的食物，胆小的先看再吃，饿极的直接吃', '流浪：陌生食物前的迟疑',
    '街中间地上一个苹果，胆小的灵梦和胆大的魔理沙各站一侧；南边隔得远的另一小堆两个苹果旁是饿极的灵梦。'
    '西边胆小、迟钝的灵梦站在苹果前先盯着看一会儿（约 3 秒以上）；东边胆大、聪明的魔理沙只看一眼（不到半秒），可它比灵梦饱 27，一个苹果两只抢，'
    '按饥饿排先后：它让灵梦先拿，站着等（头顶中性脸），灵梦盯完就吃掉。南边饿极（营养 5，低于 30）的灵梦不看直接吃，一只接一只吃掉自己的两个苹果。'
    '约 10 秒内发生；一次没看到可在 M 菜单重开',
    '约 10–40 秒', [
        item('minecraft:apple', 1, 0, 0, name='strange_apple'),
        street_stray('stray_strange_food', 'reimu', -1, 0, 'timid', nutrition=45, boldness=10, intelligence=10, label='胆小·迟钝', lead=True),
        street_stray('stray_strange_food', 'marisa', 1, 0, 'bold', nutrition=72, boldness=95, intelligence=95, label='胆大·聪明'),
        box(-9, 0, 5, 9, 0, 5, 'minecraft:oak_leaves[persistent=true]'),
        item('minecraft:apple', 2, 0, 9, name='strange_apple_far'),
        # It starts on its own pile's north contact stand: only one body stands at that pile.
        street_stray('stray_strange_food', 'reimu', 0, 8.674, 'starving', nutrition=5, boldness=50, intelligence=50, label='饿极·营养5', yaw=0),
    ], env=DAY, spot='street', vantage=(0, 16),
    reads=[('读胆小的', f'yukkuri status {sel("timid")} raw'), ('读胆大的', f'yukkuri status {sel("bold")} raw'),
           ('读饿极的', f'yukkuri status {sel("starving")} raw')],
    note='待验证：新场景，尚未冒烟、尚未在游戏里实测。')

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
        f'yukkuri scenario wound {sel("kid")}'], give=['mcyukkuri:orange_juice 2', 'mcyukkuri:flour_paste 2'], vantage=(2, 16, 180, 20),
    capture=dict(shots=(1, 40, 200, 600, 1800), sequences=((60, 1800, 12),), observe=False,
                 views=dict(wound=dict(follow='@e[tag=scn_a,limit=1]', offset=(.8, .65, 2.3),
                                       criteria='看清橙正面的磕伤与伴侣靠近舔舐的接触，保留双方身体和伤口。'))),
    reads=[('读受伤的橙（伴侣）', f'yukkuri status {sel("a")} raw'), ('读受伤的小橙（亲子）', f'yukkuri status {sel("kid")} raw')])

add('toilet', 'meadow', '便意急了走去厕所', '便意急了走去厕所', '橙有强烈便意：看它走向右边的厕所；拆掉厕所再看它在地面排便（从油菊排出）', '约 1 分钟', [
    block(6, 0, 0, 'mcyukkuri:yukkuri_toilet'), yk('chen', 0, 0, 'a', bladder=95, yaw=-90, lead=True)], vantage=(3, 10, 180, 18),
    reads=[('读厕所内排泄物体积', 'data get block ~6 ~ ~ Waste')],
    smoke=dict(ticks=1200,expect=[r'relieved source=facility',r'toilet stored [^\n]*volume='],forbid=[]))

# ---- everyday choices: one opportunity per scene, a pushy and a considerate individual side by side
_ugly = 'everyday_choices'      # the UUID namespace the five scenes share
_pushy = dict(boldness=95, friendliness=8, self_control=20)
_considerate = dict(boldness=56, friendliness=86, self_control=85)
_apple_in_mouth = 'YukkuriMouth:{Purpose:"MEAL",Items:[{id:"minecraft:apple",count:1}]}'


def _choice_actor(name, x, z, **stats):
    return yk('marisa', x, z, name, uuid=uid(_ugly, name), picky=0, **stats)


def _choice_scene(sid, title, hint, lines, vantage, checks, expect=(), late=()):
    """``checks`` are (actor, log pattern) pairs: each choice is logged exactly once by its own actor."""
    def once(window):
        for name, pattern in checks:
            count = len(re.findall(rf'uuid={_uuid_text(_ugly, name)}[^\n]* {pattern}', window))
            if count != 1:
                return f'{name} expected once ({pattern}), got {count}'
        return None

    add(sid, 'meadow', title, title, hint, '约 1 分钟', lines, env=DAY, vantage=vantage, late=late,
        reads=[('读各只的性格与需要', 'yukkuri status @e[tag=scn,type=#mcylab:yukkuri,distance=..40] raw')],
        smoke=dict(ticks=1200, expect=[*expect, *(rf'uuid={_uuid_text(_ugly, name)}[^\n]* {pattern}' for name, pattern in checks)],
                   forbid=[], verify=once))


_choice_scene(
    'choice_nest', '同伴走近草窝：鼓腮宣言或让开',
    '两个棚子各有一只魔理沙守着自己的草窝，开场两秒后各来一只灵梦走近。左边胆大不友善的鼓腮宣告这是自己的窝，右边友善的挪开让出位置。',
    [*(line for x, name, traits in ((-7, 'nest_declare', _pushy), (7, 'nest_yield', _considerate))
       for line in (box(x - 2, 3, -18, x + 2, 3, -12, 'minecraft:oak_planks'),
                    *(block(nx, 0, nz, 'minecraft:oak_fence') for nx in range(x - 2, x + 3)
                      for nz in range(-18, -11) if abs(nx - x) == 2 or nz in (-18, -12)),
                    block(x, 0, -15, 'mcyukkuri:straw_nest[facing=south]'),
                    _choice_actor(name, x - .85, -13.8, yaw=0, energy=55, lead=x < 0, **traits)))],
    (0, -4, 180, 12),
    [('nest_declare', r'nest dispute [^\n]*response=declare'), ('nest_yield', r'nest dispute [^\n]*response=yield')],
    expect=[rf'uuid={_uuid_text(_ugly, "nest_declare")}[^\n]* declared site=.*spoken=true',
            rf'uuid={_uuid_text(_ugly, "nest_yield")}[^\n]* nest gave space '],
    late=[home('nest_declare', -7, -15), home('nest_yield', 7, -15),
          yk('reimu', -7, -13.2, 'nest_peer_west', energy=55, yaw=180),
          yk('reimu', 7, -13.2, 'nest_peer_east', energy=55, yaw=180)])

_choice_scene(
    'choice_food', '向拿苹果的人讨食：骂人索要或软声讨',
    '中间的人手里拿着一个苹果，两边各有一只同样饿的魔理沙。左边胆大不友善的流浪油骂着要吃的，右边友善的宠物软声讨食。'
    '也可站到左边等流浪油向你开口，打一下，观察它逃跑或噗咕还击时的声音。',
    # Residents put away or eat a main-hand apple as picnic food. An offhand apple stays visible to both visitors.
    [human('caretaker_sister', 0, 0, 'apple_holder', 0, label='拿苹果的人', attitude=2)[:-1]
     + ',HandItems:[{},{id:"minecraft:apple",count:1}]}',
     _choice_actor('food_demand', -1.4, 0, yaw=-90, nutrition=38, social_wait=0, lead=True,
                   extra='YukkuriPet:{TownRoots:1b}', **_pushy),
     _choice_actor('food_ask', 1.4, 0, yaw=90, nutrition=38, social_wait=0, **_considerate),
     *own('food_ask')],
    (0, 6, 180, 12),
    [('food_demand', r'human food request [^\n]*line=human_food_demand'),
     ('food_ask', r'human food request [^\n]*line=human_food_ask')])

_choice_scene(
    'choice_tool', '见人拿锥：装可爱求饶或退开',
    '围栏中间的人手里拿着锥，两边各有一只魔理沙看得见。左边胆大不友善的装可爱求饶，右边胆小友善的先畏缩，有空地就退开。',
    [human('caretaker_brother', 0, 0, 'tool_holder', 0, label='拿锥的人', attitude=2)[:-1]
     + ',HandItems:[{id:"mcyukkuri:care_awl",count:1},{}]}',
     _choice_actor('tool_plead', -1.5, 0, yaw=-90, social_wait=0, lead=True, **_pushy),
     _choice_actor('tool_withdraw', 1.5, 0, yaw=90, social_wait=0, boldness=25, friendliness=76, self_control=80),
     # Even from opposite corners the holder and yukkuri stay within the 8-block recognition distance.
     *(block(nx, 0, nz, 'minecraft:oak_fence') for nx in range(-4, 5) for nz in range(-2, 3)
       if abs(nx) == 4 or abs(nz) == 2),
     block(0, 0, 4, 'minecraft:hay_block'), box(0, 0, 3, 0, 1, 3, 'minecraft:hay_block')],
    (0, 3, 180, 42),
    [('tool_plead', r'tool response [^\n]*choice=plead'), ('tool_withdraw', r'tool response [^\n]*choice=withdraw')])

_choice_scene(
    'choice_snatch', '同伴嘴里有苹果：抢走或讨不到就离开',
    '左右两头各有一只叼着苹果的灵梦和一只很饿的魔理沙。左边胆大不友善的凑上去讨，被拒绝后把苹果抢走；右边友善的讨不到便走开。'
    '站在八格以外看，人在近旁时魔理沙不敢抢。',
    # The two pairs stay 30 blocks apart: nearer, only one of the two beggars approaches.
    [*(line for x, name, step, traits in ((-19, 'food_snatch', 4, _pushy), (19, 'food_polite', -4, _considerate))
       for line in (_choice_actor(name, x, 0, yaw=-90 if step > 0 else 90, nutrition=18, lead=step > 0, **traits),
                    yk('reimu', x + step, 0, 'serving_' + name, uuid=uid(_ugly, 'serving_' + name),
                       yaw=90 if step > 0 else -90, nutrition=52, boldness=70, friendliness=10, picky=0,
                       extra=_apple_in_mouth),
                    f'data merge entity {sel("serving_" + name)} {{YukkuriLife:{{MealTicks:1}}}}'))],
    (0, 20, 180, 12),
    [('food_snatch', r'beg transfer [^\n]*result=snatched received=minecraft:apple retained=minecraft:air'),
     ('food_polite', r'beg refusal [^\n]*response=LEAVE')])

_choice_scene(
    'choice_toilet', '面前有沙厕：就地乱拉或走去沙厕',
    '两只魔理沙便意一样急，面前四格各有一个沙厕。左边自制低的就地拉在草地上，右边自制高的走到沙厕里解决。',
    [block(-5, 0, 2, 'mcyukkuri:yukkuri_toilet'), block(5, 0, 2, 'mcyukkuri:yukkuri_toilet'),
     _choice_actor('floor_dirty', -5, -2, bladder=76, yaw=0, lead=True, **_pushy),
     _choice_actor('toilet_use', 5, -2, bladder=76, yaw=0, **_considerate)],
    (0, 10, 180, 15),
    [('floor_dirty', r'relieved source=ground [^\n]*accident=true'), ('toilet_use', r'relieved source=facility')])

# The toilet has room for one more portion: the nearer remilia fills it, so the flandre arriving after her uses the ground.
add('pred_toilet', 'meadow', '捕食种上厕所与地面排便', '捕食种上厕所与地面排便',
    '蕾米莉亚和芙兰都有强烈便意，右边的厕所只剩一份空位：近处的蕾米莉亚先坐上去排满，后到的芙兰改在旁边的干地排便。看翅膀、帽子和身体不穿进厕所或地面，身体贴着厕所与地面',
    '约 1 分钟', [
        block(6, 0, 0, 'mcyukkuri:yukkuri_toilet'), 'data merge block ~6 ~ ~ {Waste:7.0d}',
        yk('remilia', 0, 0, 'rem', bladder=95, nutrition=100, energy=100, yaw=-90, lead=True),
        yk('flandre', -6, 6, 'fla', bladder=95, nutrition=100, energy=100, yaw=-90)],
    env=DAY, vantage=(0, 14),
    reads=[('读厕所内排泄物体积（7→8 满）', 'data get block ~6 ~ ~ Waste'), ('读芙兰', f'yukkuri status {sel("fla")} raw')],
    capture=dict(shots=(1, 40), sequences=((60, 260, 6),), observe=False,
                 views=dict(rem=dict(follow=f'@e[tag={tag("rem")},limit=1]', offset=(.6, .7, 2.4),
                                     criteria='看清蕾米莉亚坐在厕所上排便，翅膀、帽子和身体不穿进厕所或地面。'),
                            fla=dict(follow=f'@e[tag={tag("fla")},limit=1]', offset=(.6, .7, 2.4),
                                     criteria='看清芙兰在地面排便，翅膀、帽子和身体不穿进地面、不悬空。'))),
    smoke=dict(ticks=1200, expect=[r'\[needs\] remilia [^\n]*relieved source=facility', r'\[needs\] flandre [^\n]*relieved source=ground'],
               forbid=[r'\[body\][^\n]* in wall', r'\[body\][^\n]* death settled']))

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
_fs_x, _fs_z = BY_ID['family'].spots['hurt_kin']
# The north mother saw her child around the south end of the wall before the child turned behind it.
_fs_seen_x, _fs_seen_z = _fs_x + 2, _fs_z + 8
_fs_seen_pos = ((_fs_seen_x & 0x3ffffff) << 38) | ((_fs_seen_z & 0x3ffffff) << 12) | (FEET & 0xfff)
if _fs_seen_pos >= 1 << 63:
    _fs_seen_pos -= 1 << 64
_fs_sighting = (f'ChildSightings:[{{Child:{uuid_nbt(_fs["child"])},'
                f'Seen:{{Dimension:"minecraft:overworld",Pos:{_fs_seen_pos}L}}}}]')


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
    seen = re.escape(f'({_fs_seen_x + .5:.2f},{FEET:.2f},{_fs_seen_z + .5:.2f})')
    return _family_search_order(log, [
        (_fs['parent'], rf'child call started .*lastKnown={seen} '),
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
        (parent, 'start id=glass_press'),
        (parent, 'end id=glass_press outcome=DONE'),
        (parent, 'glass vigil started'),
        (parent, 'glass call .*spoken=true'),
        (child, 'glass answer .*spoken=true'),
        (child, 'glass child at stand'),
        (parent, 'glass pane choice .*chosen=true'),
        (parent, 'hit id=bash'),
        (parent, 'break id=bash'),
        (parent, 'glass crossed'),
        (parent, 'child search reunited'),
    ], [(parent, 'start id=glass_press'), (parent, 'glass vigil started'), (parent, 'break id=bash')], [(parent, 'parent dismissed')])


add('family_search', 'family', '走散亲子：绕墙重逢，隔整块玻璃道别', '走散亲子：绕墙重逢，隔整块玻璃道别',
    '北边的亲油上次在石墙南端看见子油。子油走到墙后，亲油朝记得的位置呼唤，重新靠近后恢复自由活动。'
    '南边亲油隔一层整块玻璃看见子油，来到玻璃前呼唤；孩子回应，贴近玻璃内侧。亲油等候到期后道别离开。'
    '可在道别后手动拆玻璃，让亲油看见实际通路再寻亲。',
    '约 30–70 秒', [
        yk('reimu', -15, -4, 'search_parent', uuid=_fs['parent'], label='寻子亲油·友善95', friendliness=95, lead=True,
           kin=_fs_sighting, **pair_ready()),
        yk('reimu', 4, -5, 'search_child', uuid=_fs['child'], stage='child', label='走散子油', boldness=40,
           kin=f'Mother:{uuid_nbt(_fs["parent"])}', **pair_ready()),
        box(0, 0, -12, 0, 1, 5, 'minecraft:stone_bricks'),
        box(1, 0, -12, 6, 1, -12, 'minecraft:stone_bricks'),
        box(6, 0, -11, 6, 1, 5, 'minecraft:stone_bricks'),
        box(3, 0, 5, 5, 1, 5, 'minecraft:stone_bricks'),
        box(13, -1, 3, 17, 3, 7, 'minecraft:glass', 'hollow'),
        yk('reimu', 7, 5, 'lost_mother', uuid=_fs['lost_mother'], label='隔玻璃的亲油', friendliness=65, self_control=20, boldness=0, **pair_ready()),
        yk('reimu', 15, 5, 'lost_child', uuid=_fs['lost_child'], stage='child', label='被围住的子油', boldness=0,
           kin=f'Mother:{uuid_nbt(_fs["lost_mother"])}', **pair_ready())],
    env=DAY, spot='hurt_kin', vantage=(0, 14),
    # The calls: once following the warm parent (in front of her face while she calls), once following the north child.
    capture=dict(shots=(1, 40, 600), sequences=((100, 340, 3),), observe=False,
                 views={'parent': dict(follow=f'@e[tag={tag("search_parent")},limit=1]', offset=(0, .9, 2.4), criteria='北边亲油的呼唤、伸长身体和左右张望能看清；不要求这一机位同时看见远处孩子或南边玻璃围栏。'),
                        'child': dict(follow=f'@e[tag={tag("search_child")},limit=1]', offset=(0, .5, 1.5), criteria='北边孩子转向、回应和接近亲油的动作能看清；只判断当前时刻已经发生的动作，不要求早期画面已经重逢。')}),
    reads=[('读亲油最后目击点', f'yukkuri status {sel("search_parent")} raw'), ('读寻亲子油', f'yukkuri status {sel("search_child")} raw'),
           ('读蹲守与寻亲记忆', f'yukkuri status {sel("lost_mother")} raw'), ('读被围子油不安', f'yukkuri status {sel("lost_child")} raw')])

add('family_search_pane', 'family', '走散亲子：撞碎玻璃板后重逢', '走散亲子：撞碎玻璃板后重逢',
    '成年魔理沙隔着玻璃板看见子油，走到窗边挤脸后呼唤。孩子回应并贴近内侧。亲油退到撞板站位，撞开玻璃后穿过开口重逢。',
    '约 10–30 秒', [
        box(-3, -1, -3, 3, 2, 3, 'minecraft:stone_bricks', 'hollow'),
        box(-2, 0, 3, 1, 1, 3, 'minecraft:iron_bars'),
        # The parent can drift a little while waiting for its first kin scan. Keep the child visible across that approach.
        box(-1, 0, 3, 1, 1, 3, 'minecraft:glass_pane'),
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
    '灵梦母油从窝外走进野生大草窝，沿窝垫的长轴摆正五果，实子成熟后从真实挂点脱落。看落在垫面和偏到窝外的区别。',
    '约 20 秒成熟', [*shed(0, 0, half=4), *large_nest(0, 0, 'mcyukkuri:large_wild_nest'),
    yk('reimu', .5, 2, 'mother', yaw=180, lead=True, **pair_ready()),
    yk('reimu', 3, 2, 'father', **pair_ready()),
    f'yukkuri scenario bond {sel("mother")} {sel("father")}', home('mother', 0, 0),
    f'yukkuri scenario birth {sel("mother")} {sel("father")} stem "0.13,0.15,0.17,0.18,0.19"'], spot='pet_birth',
    # Conception follows scene setup. Growth and the route to the nest start together.
    capture=dict(shots=(1, 40, 200, 480), sequences=((40, 240, 5), (480, 640, 4)), observe=False,
                 views={'nest': dict(follow=f'@e[tag={tag("mother")},limit=1]', offset=(1.6, 1.7, 1.4),
                                     criteria='母油走进大窝，沿窝垫长轴摆正实子，观察各自脱落与落点')}),
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
    'growth released shared nest，一次一只），让出的子油自己走到两边空着的普通窝里认领床位（日志 released child claimed a free nest，三只分住这两个窝），最后一只仍是赤油留在大窝。困了会回自己的窝；在「实用」页选“1×”可恢复正常速度',
    '先看六口同窝，再看 3 倍速下约 30 秒起一只只长成、被让出大窝；清空或重开场景会恢复 1 倍速', ['function mcylab:util/speed_3x',
    *shed(0, 0, half=5), *large_nest(0, 0, 'mcyukkuri:large_yukkuri_nest'),
    block(-3, 0, 0, 'mcyukkuri:yukkuri_nest'), block(3, 0, 0, 'mcyukkuri:yukkuri_nest'),
    yk('reimu', -1, -.5, 'mother', uuid=_nest_ids['mother'], adult_scale=.88, lead=True),
    yk('marisa', 2, 2, 'father', uuid=_nest_ids['father'], adult_scale=.88),
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
    '夜里（「实用」页的“正午”可换白天对照）：父母和两只赤油精力满、困值不满，到了作息时间照样认窝、进窝睡下；睡下的那几只整夜不自己醒，约两分钟后自动天亮，它们伸懒腰打哈欠醒来（日志 cause=rested_dawn）。还在储粮或找孩子的那只不一定整夜在窝里；中途按“夜晚”重开或打雷、搬走窝才提前醒',
    '整夜约2分钟，天亮后自动醒', [*shed(0, 0, half=4), *large_nest(0, 0, 'mcyukkuri:large_yukkuri_nest'),
    yk('reimu', -1, -.5, 'mother', uuid=_night_ids['mother'], adult_scale=.88, energy=100, nutrition=100, rest_wait=60, lead=True),
    yk('marisa', 2, 2, 'father', uuid=_night_ids['father'], adult_scale=.88, energy=100, nutrition=100, rest_wait=60),
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

# Four owned pets, one bed each under one roof; the beds stand against stone bricks, so their rolls flatten on those sides and
# the one whose north side is walled turns its entrance east. Night makes all four go to bed at once (rest time).
_GRADE_BEDS = [('proud', 'mcyukkuri:straw_nest', -5, 1, [(-6, 1)]), ('plain', 'mcyukkuri:yukkuri_nest', -2, 1, []),
               ('humble', 'mcyukkuri:velvet_nest', 1, 1, [(0, 1), (2, 1)]), ('outcast', 'mcyukkuri:velvet_nest', 4, 0, [(4, -1), (4, 1)])]
_GRADE_PETS = {'proud': dict(x=-5, z=-1, label='傲慢', boldness=95, friendliness=5),
               'plain': dict(x=-2, z=-1, label='平常', boldness=55, friendliness=70),
               'humble': dict(x=1, z=-1, label='谦卑', boldness=20, friendliness=90),
               'outcast': dict(x=6, z=-1, label='落魄', boldness=20, friendliness=90, accessory=False)}
add('nest_grades', 'family', '三档宠物窝：贴墙变形，按自尊各有反应', '三档宠物窝：贴墙变形，按自尊各有反应',
    '夜里四只宠物各回自己的窝：墙角的草窝、背靠墙的软垫窝、两侧夹墙的丝绒窝、南北夹墙的丝绒窝（入口自动转到东侧）。贴墙一侧的软卷压平不穿墙，入口朝空着的一侧。'
    '戴金章、自尊高的「傲慢」嫌草窝鼓脸不肯睡（日志 bed settle verdict=refuse），半分钟后回来认命睡下（give_in）；普通的「平常」在软垫窝照常睡；'
    '自尊低的「谦卑」进丝绒窝开心（多为 delighted）；没了头饰、自尊跌到底的「落魄」在丝绒窝里四下张望不自在（uneasy）。睡着后读 mind.causes 的 bed 项看各自的不安增减',
    '入夜后约 1 分钟', [
        *[box(-6, 0, 2, 6, 1, 2, 'minecraft:stone_bricks')],
        *[block(x, 0, z, 'minecraft:stone_bricks') for _, _, _, _, walls in _GRADE_BEDS for x, z in walls],
        box(-6, 0, -3, -6, 2, -3, 'minecraft:oak_log'), box(6, 0, -3, 6, 2, -3, 'minecraft:oak_log'),
        box(-6, 2, 2, 6, 2, 2, 'minecraft:stone_bricks'), box(-6, 3, -3, 6, 3, 2, 'minecraft:oak_planks'),
        *[block(x, 0, z, bed) for _, bed, x, z, _ in _GRADE_BEDS],
        *[yk('reimu', p['x'], p['z'], name, label=p['label'], boldness=p['boldness'], friendliness=p['friendliness'],
             accessory=p.get('accessory', True), self_control=95, intelligence=90, energy=95, rest_wait=60, yaw=180,
             lead=name == 'proud')
          for name, p in _GRADE_PETS.items()],
        *[line for name in _GRADE_PETS for line in own(name)],
        f'yukkuri scenario badge {sel("proud")} gold false',
        *[home(name, x, z) for name, _, x, z, _ in _GRADE_BEDS]],
    env={'time': 'night'}, spot='pet_birth', vantage=(0, -9),
    smoke=dict(ticks=2400,
               expect=[r'\[home\][^\n]*bed settle verdict=refuse[^\n]*grade=1',
                       r'\[home\][^\n]*bed settle verdict=give_in[^\n]*grade=1',
                       r'\[home\][^\n]*bed settle verdict=uneasy[^\n]*grade=3',
                       r'\[sleep\] [^\n]*begin source=life[^\n]*reason=rest_time'],
               forbid=[r'nest doorway too narrow', r'nest exit stopped reason=doorway_unreachable'],
               readout=[f'yukkuri status {sel(name)} raw' for name in _GRADE_PETS]),
    reads=[('读四只宠物的自尊、睡眠与不安来源', 'yukkuri status @e[tag=scn,type=#mcylab:yukkuri,distance=..24] raw')])

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
    '三只性格各异的油库里在石顶东侧淋雨起跑（旧16格范围外）：胆小灵梦往顶内深处躲，中等魔理沙居于中段，胆大橙停在偏外侧，彼此错格并排；到顶下后（几乎没湿，不抖水）朝雨外安顿，不卡人也不重放奔跑动作',
    '约 30 秒', [
        yk('reimu', 17, -1, 'a', yaw=90, boldness=5, lead=True, uuid=uid('rain_shelter', 'a')),
        yk('marisa', 18, 1, 'b', yaw=90, boldness=50, uuid=uid('rain_shelter', 'b')),
        yk('chen', 17, 2, 'c', yaw=90, boldness=90, uuid=uid('rain_shelter', 'c'))],
    env={'time': 'noon', 'weather': 'rain'}, spot='roof', vantage=(11, 6, 180, 12),
    reads=[('读反应（behavior.reaction）', f'yukkuri status {sel("a")} raw')],
    smoke=dict(ticks=750,
               expect=[r'\[weather\][^\n]* shelter response started ',
                       r'\[weather\][^\n]* cover chosen [^\n]*depth=\d+ preferred=',
                       r'\[weather\][^\n]* shelter response ended '],
               forbid=[r'\[body\][^\n]* invariant\b']))


def _rain_motion_faults(trace, actors, idle_streak):
    """Trace ticks where a run body shows its standing clip while its own legs move it, and where it shows a walk clip
    standing still.

    A neighbour's separation or shove moves a body while its own horizontal velocity stays zero (Motor.groundStep
    clears the contact step before vanilla integrates it), and the presentation deliberately keeps the standing clip
    for such a contact tick. Only the body's own travel counts as walking, so being shifted aside is not "idle while
    moving"; a real idle clip on a driven walk has the body's own velocity."""
    rev = {u: name for name, u in actors.items()}
    prev_pos = {name: None for name in rev.values()}
    idle_run = {name: 0 for name in rev.values()}
    still_run = {name: 0 for name in rev.values()}
    idle_while_moving, run_on_spot = [], []
    for line in trace:
        row = json.loads(line.split('[trace] ', 1)[1])
        if row['k'] != 'st' or row['u'] not in rev:
            continue
        name = rev[row['u']]
        state = row['st']
        p = state.get('p')
        if not p:
            continue
        behavior, animation = state.get('behavior'), state.get('animation')
        if prev_pos[name] is not None and state.get('g'):
            step = math.hypot(p[0] - prev_pos[name][0], p[2] - prev_pos[name][2])
            v = state.get('v') or (0, 0, 0)
            own = math.hypot(v[0], v[2]) > 0.015
            idle_run[name] = idle_run[name] + 1 if behavior == 'rain_run' and own and step > 0.015 and animation == 'idle' else 0
            if idle_run[name] >= idle_streak:
                idle_while_moving.append((name, row['t'], round(step, 4)))
            # A body that has stopped (or only turns on the spot) shows a standing clip within two ticks.
            still_run[name] = still_run[name] + 1 if step <= 0.001 and animation in ('run', 'waddle') else 0
            if still_run[name] > 2:
                run_on_spot.append((name, row['t']))
        prev_pos[name] = p
    return idle_while_moving, run_on_spot


def _verify_rain_shelter(window, trace):
    actors = {name: _uuid_text('rain_shelter', name) for name in ('a', 'b', 'c')}
    depths, preferreds, end_ticks, end_cells = {}, {}, {}, {}
    for name, u in actors.items():
        m_cover = re.search(rf'\[weather\][^\n]* uuid={u} [^\n]*cover chosen pos=(-?\d+,\s*-?\d+,\s*-?\d+) depth=(\d+) preferred=([0-9.]+)', window)
        if not m_cover:
            return f'{name} ({u}) never logged cover chosen'
        depths[name] = int(m_cover.group(2))
        preferreds[name] = float(m_cover.group(3))
        m_end = re.search(rf'\[weather\][^\n]* uuid={u} t=(\d+)[^\n]* shelter response ended [^\n]*at=(-?\d+),\s*(-?\d+),\s*(-?\d+)', window)
        if not m_end:
            return f'{name} ({u}) never ended shelter response under cover'
        end_ticks[name] = int(m_end.group(1))
        end_cells[name] = (int(m_end.group(2)), int(m_end.group(4)))
        if not (-326 <= end_cells[name][0] <= -318 and 296 <= end_cells[name][1] <= 304):
            return f'{name} ended outside the stone roof at {end_cells[name]}'
        beats = re.findall(rf'\[weather\][^\n]* uuid={u} [^\n]*beat=(\S+) declared=(\S+) shown=(\S+) line=(\S+)', window)
        if not beats:
            return f'{name} logged no weather beat'
        for beat, declared, shown, _line in beats:
            if shown != declared:
                return f'{name} beat={beat} face mismatch declared={declared} shown={shown}'
    if not (preferreds['a'] > preferreds['b'] > preferreds['c']):
        return f'preferred depth not ordered by boldness: {preferreds}'
    if not (depths['a'] > depths['b'] > depths['c']):
        return f'chosen depths not strictly ordered timid > mid > bold: {depths}'
    if len(set(end_cells.values())) < 3:
        return f'two bodies ended in the same cover cell: {end_cells}'
    # Check trace: no actor left in the rain > 100 ticks after first arrival, no run-clip restart loop, and no idle while moving.
    first_under = None
    last_in_rain = {}
    run_starts = {name: 0 for name in actors}
    prev_anim = {name: None for name in actors}
    rev = {u: name for name, u in actors.items()}
    idle_while_moving, _ = _rain_motion_faults(trace, actors, 1)
    for line in trace:
        row = json.loads(line.split('[trace] ', 1)[1])
        if row['k'] != 'st' or row['u'] not in rev:
            continue
        name = rev[row['u']]
        t = row['t']
        p = row['st'].get('p')
        beh, anim = row['st'].get('behavior'), row['st'].get('animation')
        if p:
            under = -326 <= math.floor(p[0]) <= -318 and 296 <= math.floor(p[2]) <= 304
            if under:
                if first_under is None or t < first_under:
                    first_under = t
            elif t <= end_ticks[name]:
                last_in_rain[name] = t
        if beh == 'rain_run' and anim == 'run' and prev_anim[name] != 'run':
            run_starts[name] += 1
        prev_anim[name] = anim
    if first_under is None:
        return 'no actor appeared under the roof in trace'
    for name, t_rain in last_in_rain.items():
        if t_rain - first_under > 100:
            return f'{name} stayed in the rain {t_rain - first_under} ticks after first arrival (t={t_rain}, first={first_under})'
    if idle_while_moving:
        return f'idle clip shown while moving during rain_run: {idle_while_moving[:5]}'
    for name, starts in run_starts.items():
        if starts > 2:
            return f'{name} restarted run clip {starts} times during rain_run (clip restart loop)'


next(s for s in S if s.id == 'rain_shelter').smoke.update(verify=_verify_rain_shelter, verify_trace=True)


add('rain_shelter_full', 'weather', '满顶躲雨：先到者往里让位，无位者另找掩体', '满顶躲雨让位与另找掩体',
    '两格深的一格宽石檐下：先到的大胆橙停在入口格；后到的灵梦要进入口格，请它往里挪（让位表情台词，橙转身后走进内格、不倒退），灵梦接着站进入口格；第三只魔理沙见两格都占满，才跑去北侧备用小顶躲雨，不滞留雨中',
    '约 30 秒', [
        # 2-cell deep, 1-cell wide stone alcove open to the east (+x): covered stands at (0,0) and (-1,0)
        box(-2, 0, -1, 0, 1, -1, 'minecraft:stone_bricks'),
        box(-2, 0, 1, 0, 1, 1, 'minecraft:stone_bricks'),
        box(-2, 0, 0, -2, 1, 0, 'minecraft:stone_bricks'),
        box(-1, 2, 0, 0, 2, 0, 'minecraft:stone_bricks'),
        # Backup 3x3 roof 14 blocks north so the 3rd body that finds no room in the full alcove shelters there instead of soaking.
        box(-1, 2, -15, 1, 2, -13, 'minecraft:stone_bricks'),
        box(-1, 0, -15, -1, 1, -15, 'minecraft:stone_brick_wall'),
        box(1, 0, -15, 1, 1, -15, 'minecraft:stone_brick_wall'),
        box(-1, 0, -13, -1, 1, -13, 'minecraft:stone_brick_wall'),
        box(1, 0, -13, 1, 1, -13, 'minecraft:stone_brick_wall'),
        yk('chen', 2, 0, 'front', yaw=90, boldness=90, lead=True, uuid=uid('rain_shelter_full', 'front')),
        yk('reimu', 5, 0, 'second', yaw=90, boldness=85, uuid=uid('rain_shelter_full', 'second')),
        yk('marisa', 9, 0, 'third', yaw=90, boldness=50, uuid=uid('rain_shelter_full', 'third'))],
    env={'time': 'noon', 'weather': 'rain'}, spot='open', vantage=(4, 7, 180, 14),
    reads=[('读让位与避雨状态', 'yukkuri status @e[tag=scn,distance=..32] raw')],
    smoke=dict(ticks=700,
               expect=[r'\[move\][^\n]* encounter begin [^\n]*reason=make_room\b',
                       r'\[move\][^\n]* encounter face [^\n]*reason=make_room\b',
                       r'\[move\][^\n]* encounter end [^\n]*result=passed cause=room_made\b'],
               forbid=[r'\[body\][^\n]* invariant\b']))


def _verify_rain_shelter_full(window, trace):
    front = _uuid_text('rain_shelter_full', 'front')
    second = _uuid_text('rain_shelter_full', 'second')
    third = _uuid_text('rain_shelter_full', 'third')
    m_face = re.search(rf'\[move\][^\n]* uuid={front} [^\n]*encounter face [^\n]*declared=(\S+) shown=(\S+) [^\n]*reason=make_room\b', window)
    if not m_face:
        return f'front ({front}) never logged make_room encounter face'
    if m_face.group(1) != m_face.group(2):
        return f'make_room face mismatch declared={m_face.group(1)} shown={m_face.group(2)}'
    # Who ends where: the first body went deeper into the back cell, the arrival took the cell it left, the third found no room and sheltered on the backup roof.
    ends = {}
    for name, u in (('front', front), ('second', second), ('third', third)):
        m_end = re.search(rf'\[weather\][^\n]* uuid={u} [^\n]* shelter response ended [^\n]*at=(-?\d+),\s*(-?\d+),\s*(-?\d+)', window)
        if not m_end:
            return f'{name} ({u}) never ended its shelter response under cover'
        ends[name] = (int(m_end.group(1)), int(m_end.group(3)))
        beats = re.findall(rf'\[weather\][^\n]* uuid={u} [^\n]*beat=(\S+) declared=(\S+) shown=(\S+) line=(\S+)', window)
        if not beats:
            return f'{name} logged no weather beat'
        for beat, declared, shown, _line in beats:
            if shown != declared:
                return f'{name} weather beat={beat} face mismatch declared={declared} shown={shown}'
    if ends['front'] != (-275, 294) or ends['second'] != (-274, 294):
        return f'make_room did not give the arrival the freed cell: ends={ends} (want front=(-275, 294) second=(-274, 294))'
    if not (-277 <= ends['third'][0] <= -271 and ends['third'][1] <= 285):
        return f'third ended at {ends["third"]}, not under the backup roof'
    # Verify no idle while moving during rain_run.
    idle_moving, run_on_spot = _rain_motion_faults(trace, {'front': front, 'second': second, 'third': third}, 3)
    if idle_moving:
        return f'idle clip shown while moving in rain_shelter_full: {idle_moving[:5]}'
    if run_on_spot:
        return f'walk clip shown on the spot after a stop in rain_shelter_full: {run_on_spot[:5]}'


next(s for s in S if s.id == 'rain_shelter_full').smoke.update(verify=_verify_rain_shelter_full, verify_trace=True)

_rain_fam_parent = uid('rain_shelter_family', 'mother')
add('rain_shelter_family', 'weather', '下雨带赤油去石顶下躲雨', '下雨带赤油躲雨',
    '灵梦与两只赤油在石顶东侧淋雨。亲油按避雨预算（上限 80 tick，不用捕食种的 160 tick）顺手抱起爬行的赤油跑去石顶下放下，会跳的赤油不被钉在原地、自行跑向石顶，亲油也不喊逃命台词。',
    '约 20 秒', [
        yk('reimu', 16, 0, 'mother', uuid=_rain_fam_parent, yaw=90, lead=True, boldness=75, attachment=85),
        yk('reimu', 15.1, 0, 'baby_a', stage='baby', uuid=uid('rain_shelter_family', 'baby_a'),
           kin=kin_of(Mother=_rain_fam_parent), childhood=CHILDHOOD_CRAWLING_READY),
        yk('reimu', 18.0, 0, 'baby_b', stage='baby', uuid=uid('rain_shelter_family', 'baby_b'),
           kin=kin_of(Mother=_rain_fam_parent), childhood=CHILDHOOD_HOPPING)],
    env={'time': 'noon', 'weather': 'rain'}, spot='roof', vantage=(10, 14, 180, 20),
    reads=[('读避雨与承托', 'yukkuri status @e[tag=scn,distance=..48] raw')],
    smoke=dict(ticks=1200,
               expect=[r'\[family\][^\n]*carry flight choice cause=weather gather=true [^\n]*patience=80\b',
                       r'\[family\][^\n]*carry pickup hold=mouth purpose=PROTECT\b',
                       r'\[family\][^\n]*carry set down started reason=reached_cover\b'],
               forbid=[r'\[family\][^\n]*carry flight leaves\b',
                       r'\[family\][^\n]*beat=abandon_look\b',
                       r'protect_child_worry|protect_child_flee']))

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
    '蕾米莉亚（不饿）在 12 格外：普通个体还没挨打就开始后退（threat:retreat），离开威胁后躲藏；不饿的捕食者不会主动追猎。营养5的虚弱灵梦同样后退，但只蹒跚走，不用蹦跳跑姿',
    '约 1 分钟', [
        yk('reimu', 0, 0, 'a', yaw=-90, lead=True, uuid=uid('pred_watch', 'a')), yk('marisa', 0, 2, 'b', yaw=-90),
        yk('reimu', 0, -2, 'weak', label='营养5·虚弱', yaw=-90, nutrition=5, energy=90, uuid=uid('pred_watch', 'weak')),
        yk('remilia', 12, 0, 'pred', yaw=90, nutrition=100, energy=100)],
    env=DAY, spot='pred', vantage=(6, 14), reads=[('读反应', f'yukkuri status {sel("a")} raw')],
    smoke=dict(ticks=1200, expect=[r'\[species\] remilia #\d+ uuid=(?P<pred>[0-9a-f-]{36})\b[\s\S]*?'
                                   r'\[threat\] reimu #\d+ [^\n]* response started source=(?P=pred) stage=RETREAT\b'],
               forbid=[r'\[body\][^\n]* invariant\b']))

_HOP_CLIPS = ('run', 'hop', 'hop_leg', 'joy_jump', 'hat_hop_up')


def _clips(trace, actor):
    """Every (tick, behavior, animation) the dense trace sampled for one actor."""
    rows = []
    for line in trace:
        row = json.loads(line.split('[trace] ', 1)[1])
        if row['k'] == 'st' and row['u'] == actor:
            rows.append((row['t'], row['st']['behavior'], row['st']['animation']))
    return rows


def _verify_pred_watch_gait(window, trace):
    weak, ordinary = _clips(trace, _uuid_text('pred_watch', 'weak')), _clips(trace, _uuid_text('pred_watch', 'a'))
    retreats = [row for row in weak if row[1] == 'threat_retreat']
    if not retreats:
        return 'The frail body never retreated from the predator'
    if hops := [row for row in weak if row[2] in _HOP_CLIPS]:
        return f'The frail body hopped: {hops[0]}'
    if not any(row[1] == 'threat_retreat' and row[2] == 'run' for row in ordinary):
        return 'The healthy control did not retreat with the hop-run gait'
    if not any(row[1] == 'threat_retreat' and row[2] == 'waddle' for row in weak):
        return 'The frail body did not retreat on the waddle gait'


next(s for s in S if s.id == 'pred_watch').smoke.update(verify=_verify_pred_watch_gait, verify_trace=True)


def _verify_nutrition_age(window, trace):
    starving = _clips(trace, _uuid_text('nutrition_age', 'starving'))
    if not starving:
        return 'The starving body left no trace samples'
    if hops := [row for row in starving if row[2] in _HOP_CLIPS]:
        return f'The starving body hopped: {hops[0]}'
    if not re.search(rf'\[body\][^\n]* uuid={_uuid_text("nutrition_age", "starving")} [^\n]*frail body withholds hop beat=frail_clip clip=joy_jump\b', window):
        return 'The requested joy clip on the starving body was not withheld'
    tired, control = (_clips(trace, _uuid_text('nutrition_age', name)) for name in ('tired', 'tired_ctl'))
    if hops := [row for row in tired if row[2] in _HOP_CLIPS]:
        return f'The exhausted body hopped: {hops[0]}'
    if not any(row[2] == 'joy_jump' for row in control):
        return 'The healthy control never hopped for joy'
    if not re.search(rf'\[body\][^\n]* uuid={_uuid_text("nutrition_age", "tired")} [^\n]*frail body withholds hop beat=frail_clip clip=joy_jump [^\n]*face=\S+ line=\S*food_delight\b', window):
        return 'The exhausted body\'s joy hop was not withheld in the log'


next(s for s in S if s.id == 'nutrition_age').smoke = dict(
    ticks=1200, expect=[], forbid=[r'\[body\][^\n]* invariant\b'], verify=_verify_nutrition_age, verify_trace=True,
    # A requested joy clip (the path pet praise, lessons, badges, shop and owner welcome all use) on the starving body.
    commands=[(300, f'yukkuri action {sel("starving")} joy_jump')],
    allow_fate=[dict(kind='starve', who=f'uuid={_uuid_text("nutrition_age", "starving")} ',
                     why='the scene starts one body at nutrition 0 to show the starvation damage')])


def _range_pursuit(window, trace):
    lock = re.search(r'\[predator\] flandre #\d+ uuid=([\da-f-]+) [^\n]* prey selected target=([\da-f-]+) [^\n]* distance=(?:4[2-9]|5[0-4])\.', window)
    if not lock:
        return 'No long-range acquisition'
    hunter, prey = lock.groups()
    episode = window[lock.end():]
    flee = re.search(rf'\[threat\] reimu #\d+ uuid={prey} t=(\d+) [^\n]* flight away [^\n]* danger={hunter}\b', episode)
    bite = re.search(rf'\[predator\] flandre #\d+ uuid={hunter} t=(\d+) [^\n]* prey bite target=#\d+ uuid={prey}[^\n]* accepted=true', episode)
    if not flee or not bite:
        return 'Selected Reimu must flee this Flandre and be bitten by it'
    start = None
    for line in trace:
        row = json.loads(line.split('[trace] ', 1)[1])
        if row['k'] == 'st' and row['u'] == prey and int(flee[1]) <= row['t'] < int(bite[1]):
            state = row['st']
            if state['behavior'] in ('flee', 'threat_retreat'):
                x, _, z = state['p']
                if start is None:
                    start = x, z
                elif math.dist(start, (x, z)) > 1:
                    return
    return 'Prey did not run a block before the bite'


add('pred_range', 'weather', '饥饿芙兰远远飞来追猎灵梦', '饥饿芙兰远远飞来追猎灵梦',
    '芙兰与灵梦相距 48 格：芙兰站稳后开始饥饿，观察远处发现、起飞追击、猎物逃跑和接触咬击，咬到灵梦倒下，再吃她的遗体',
    '约 1–2 分钟', [
        yk('reimu', -24, 0, 'range_prey', uuid=uid('pred_range', 'range_prey'), yaw=-90, **pair_ready()),
        yk('flandre', 24, 0, 'range_pred', yaw=90, lead=True, nutrition=100, energy=100)],
    late=[set_status('range_pred', 'needs.nutrition', 10)],
    env=DAY, spot='pred', vantage=(0, 18),
    reads=[('读远猎', f'yukkuri status {sel("range_pred")} raw'), ('读远猎猎物', f'yukkuri status {sel("range_prey")} raw')])

add('pred_spawn_nest', 'grove', '捕食种自然生成，带着巢出现在树冠', '捕食种自然生成，带着巢出现在树冠',
    '从东南坡看林间大橡树：蕾米莉亚从自然生成起点自行选枝，出生时和枝巢一起出现在树冠。读住所，再飞近看它站的枝巢',
    '开场即见', [yk('remilia', 0, 0, 'spawn_pred', nutrition=100, energy=100, lead=True,
                     extra='YukkuriPredator:{NaturalSpawn:1b}')], env=DAY, spot='spawn', vantage=(36, 32, 131, -7),
    reads=[('读枝巢住所', f'yukkuri status {sel("spawn_pred")} raw')],
    smoke=dict(ticks=100, expect=[r'\[predator\] remilia [^\n]* spawn nest placed site=[^\n]* tree_height=(?:[4-9]|1[0-2])\b',
                                  r'\[home\] remilia [^\n]* assigned kind=care',
                                  r'\[body\] remilia [^\n]* relocate cause=spawn_nest_perch'],
               forbid=[r'\[predator\] remilia [^\n]* spawn nest refused'],
               readout=[f'yukkuri status {sel("spawn_pred")} raw']),
    capture=dict(shots=(1, 40, 100), sequences=(),
                 views={'canopy': dict(position=(-1.5, 7.1, -3.2), look_at=(.5, 6.45, .5))}))


def _verify_predator_build(window, trace):
    twigs = re.findall(r'\[predator\] flandre [^\n]* nest frame twigs=(\d+) site=([^\r\n]+)', window)
    sources = re.findall(r'\[predator\] flandre [^\n]* nest twig taken source=(stick|leaf)\b', window)
    completed = re.findall(r'\[predator\] flandre [^\n]* nest built home=([^\n]+) twigs=8', window)
    if [int(count) for count, _ in twigs] != list(range(1, 8)) or len(completed) != 1 or any(site != completed[0] for _, site in twigs):
        return f'Expected seven increasing stages and one completed nest at the same site, got {twigs}, {completed}'
    if sources != ['stick'] * 3 + ['leaf'] * 5:
        return f'Expected three loose sticks followed by five plucked twigs, got {sources}'
    claim = re.findall(r'\[home\] flandre [^\n]* assigned kind=care [^\n]* home=[^\n]*' + re.escape(completed[0]), window)
    finish = re.search(r'\[predator\] flandre [^\n]* uuid=(\S+) t=(\d+) [^\n]* nest built home=', window)
    if len(claim) != 1:
        return f'Expected one Flandre home claim at the finished pad, got {claim}'
    founder, end_tick = finish.group(1), int(finish.group(2))
    asleep, woke = False, None
    for line in trace:
        if f'"u":"{founder}"' not in line or '"k":"st"' not in line:
            continue
        state = json.loads(line.split('[trace] ', 1)[1])
        if state['t'] >= end_tick:
            break
        if state['st']['sleeping']:
            asleep = True
        elif asleep:
            woke = state['t']
            break
    work = re.findall(r'\[predator\] flandre [^\n]* t=(\d+) [^\n]* nest work chosen site=([^\n]+?) twigs=([1-7])', window)
    if woke is None or not any(woke <= int(tick) < end_tick and site == completed[0] for tick, site, _ in work):
        return 'Flandre did not wake and choose the same frame before finishing it'
    adopted = re.findall(r'\[predator\] remilia [^\n]* nest frame adopted site=([^\n]+?) kept_twigs=4\b', window)
    finished = re.findall(r'\[predator\] remilia [^\n]* nest built home=([^\n]+) twigs=8', window)
    progress = re.findall(r'\[predator\] remilia [^\n]* nest frame twigs=(\d+) site=([^\r\n]+)', window)
    if len(adopted) != 1 or finished != adopted or progress != [(str(i), adopted[0]) for i in (5, 6, 7)]:
        return f'Expected Remilia to inherit four twigs, add stages 5–7 and finish that frame, got {adopted}, {progress}, {finished}'
    if len(re.findall(r'\[home\] remilia [^\n]* assigned kind=care [^\n]* home=[^\n]*' + re.escape(adopted[0]), window)) != 1:
        return 'Remilia did not claim the inherited frame on completion'
    return None


add('pred_build_nest', 'grove', '无巢捕食种选树、衔枝筑巢', '无巢捕食种选树、衔枝筑巢',
    '无巢芙兰盘旋选树，捡完三根木棍后落到叶上啄细枝；困了休息，醒后回原架续搭，第八枝落下认巢。林地西南角的蕾米莉亚从弃置的四枝巢架继续搭',
    '约 1–2 分钟', [yk('flandre', 0, 0, 'build_pred', nutrition=100, energy=100, lead=True),
                    item('minecraft:stick', 3, -1, 3, name='build_sticks'),
                    # Repeat the western oak's reachable shelf beyond the first founder's survey.
                    box(-36, 0, 17, -24, 12, 28, 'minecraft:air'),
                    box(-31, 0, 22, -31, 10, 22, 'minecraft:oak_log'),
                    box(-30, 5, 22, -27, 5, 22, 'minecraft:oak_log[axis=x]'),
                    box(-29, 7, 21, -27, 7, 23, 'minecraft:oak_leaves[persistent=true]'),
                    box(-34, 8, 19, -28, 9, 25, 'minecraft:oak_leaves[persistent=true]', 'keep'),
                    box(-33, 10, 20, -29, 11, 24, 'minecraft:oak_leaves[persistent=true]', 'keep'),
                    box(-28, 4, 23, -28, 4, 25, 'minecraft:oak_leaves[persistent=true]'),
                    block(-28, 6, 22, 'mcyukkuri:predator_nest_frame[twigs=4]'),
                    yk('remilia', -27, 26, 'adopt_pred', nutrition=100, energy=100, uuid=uid('pred_build_nest', 'adopter')),
                    item('minecraft:stick', 4, -28, 25, name='adopt_sticks')],
    env=DAY, spot='build', vantage=(21, 33, 131, -7), reads=[
        ('读筑巢活动与住所', f'yukkuri status {sel("build_pred")} raw'),
        ('读接手巢架的蕾米莉亚', f'yukkuri status {sel("adopt_pred", 100)} raw')],
    smoke=dict(ticks=3000, expect=[r'\[predator\] flandre [^\n]* nest survey begin',
                                   r'\[predator\] flandre [^\n]* nest built home=', r'\[home\] flandre [^\n]* assigned kind=care'],
               forbid=[r'\[predator\] flandre [^\n]* spawn nest placed'],
               verify=_verify_predator_build, verify_trace=True,
               readout=[f'yukkuri status {sel("build_pred")} raw', f'yukkuri status {sel("adopt_pred", 100)} raw']))

def _perch_resident(nutrition):
    # The western oak's roofed shelf, at the exact pad used by the natural-spawn scene.
    return [block(0, 6, 0, 'mcyukkuri:predator_nest'),
            yk('remilia', 0, 0, 'perch_pred', dy=6.15, lead=True, nutrition=nutrition, energy=100, boldness=55),
            f'yukkuri scenario home {sel("perch_pred")} ~ ~6 ~']


def _clearing_children():
    return [yk('reimu', x, 6, name, stage='child', nutrition=46, childhood=CHILDHOOD_CRAWLING_READY,
               uuid=uid('pred_perch_hunt', name)) for x, name in [(5, 'forager_a'), (9, 'forager_b')]]


def _verify_perch_hunt(window):
    beats = [r'\[predator\] remilia [^\n]* perch begin ', r'\[predator\] remilia [^\n]* prey selected ',
             r'\[predator\] remilia [^\n]* phase CHASE -> DIVE', r'\[predator\] remilia [^\n]* prey bite [^\n]*alive=false',
             r'\[predator\] remilia [^\n]* eat pause ended; reason=prey_kill', r'\[predator\] remilia [^\n]* perch begin ']
    for beat in beats:
        event = re.search(beat, window)
        if not event:
            return f'Missing next event in branch hunt: {beat}'
        window = window[event.end():]
    return None


add('pred_perch_hunt', 'grove', '高枝守望，俯冲捕猎', '高枝守望，俯冲捕猎',
    '从橡树枝巢望向林间空地。蕾米莉亚先在高枝收翅张望，两只觅食的子油出现后，看它飞离枝头、俯冲咬住一只、落地进食，再回枝上。可拆掉枝巢观察重新择址',
    '约 1–2 分钟', _perch_resident(40), spot='spawn', vantage=(7, 8, 177, 4), env={'time': 'dawn'},
    late=[Retry(child, sel('perch_pred'), first=240, tries=1) for child in _clearing_children()],
    reads=[('读守望与追猎', f'yukkuri status {sel("perch_pred")} raw')],
    smoke=dict(ticks=2000, expect=[r'\[home\] remilia [^\n]* assigned kind=care', r'\[meal\] remilia [^\n]* swallowed bodyFood'],
               forbid=[r'\[predator\] remilia [^\n]* combat strike '],
               allow_fate=[dict(kind='death', who=f'uuid={_uuid_text("pred_perch_hunt", n)} ', match=r'cause=mob\b',
                                why='the perched hunt catches and eats the child it stoops at') for n in ('forager_a', 'forager_b')],
               verify=_verify_perch_hunt))

def _tree_family(sid, height, feeding):
    mother, father = uid(sid, 'mother'), uid(sid, 'father')
    young_kin = kin_of(Mother=mother, Father=father)
    shelf = [] if feeding else [box(-1, 7, -1, 1, 8, 1, 'minecraft:air'),
                                box(-1, 6, 0, 1, 6, 0, 'minecraft:oak_log[axis=x]')]
    actors = [*shelf, block(0, height, 0, 'mcyukkuri:predator_nest'),
              yk('remilia', 4 if feeding else -1, 4 if feeding else 0, 'mother', dy=0 if feeding else height,
                 nutrition=98, energy=98, yaw=-90, uuid=mother, lead=True,
                 friendliness=90, attachment=90, kin=kin_of(Partner=father), also=('canopy_parent',))]
    if feeding:
        actors.append(yk('flandre', -2, 0, 'father', dy=height, uuid=father, kin=kin_of(Partner=mother), also=('canopy_parent',)))
    else:
        actors.append(f'yukkuri scenario home {sel("mother")} ~ ~{height} ~')
    x, z = BY_ID['grove'].spots['spawn']
    pad = (x << 38) | ((z & 0x3ffffff) << 12) | (FEET + height)
    resident = f'@e[tag=scn_canopy_parent,distance=..48,limit=1,nbt={{YukkuriLife:{{NaturalHome:0b,HomeDimension:"minecraft:overworld",HomePos:{pad}L}}}}]'
    young = []
    for name, offset, boldness in [('bold', -.22, 85), ('timid', .22, 25)]:
        child = yk('remilia', offset, -.22 if feeding else .12, name, dy=height+.15,
                   stage='baby' if feeding else 'child', share=0 if feeding else .44,
                   uuid=uid(sid, name), kin=young_kin, boldness=boldness, nutrition=24 if feeding else 92,
                   childhood=None if feeding else 'WingPractice:0.45d')
        young.append(Retry(child, resident, first=5, tries=16 if feeding else 1))
    return actors, young


_feeding_parents, _feeding_young = _tree_family('pred_nest_feeding', 6, True)
_fledge_parent, _fledge_young = _tree_family('pred_fledging', 7, False)


def _verify_feeding_home(window):
    x, z = BY_ID['grove'].spots['spawn']
    pad = re.escape(f'BlockPos{{x={x}, y={FEET + 6}, z={z}}}')
    for role in ('mother', 'father', 'bold', 'timid'):
        actor = _uuid_text('pred_nest_feeding', role)
        if not re.search(rf'\[home\] [^\n]*uuid={actor} [^\n]* assigned kind=care [^\n]* home=[^\n]*{pad}', window):
            return f'{role} did not acquire the family canopy home'


add('pred_nest_feeding', 'grove', '育雏：叼小猎物回巢', '亲油叼小猎物回枝巢，赤油在巢里吃糜',
    '从坡上看树下的幼小遗体与枝巢上的两只赤油。看亲油在哪里叼起猎物、从哪边着枝、放下后怎样嚼糜对嘴喂食',
    '约一至两分钟', [*_feeding_parents,
                   remains(4.41, 4, 'small_prey', age='juvenile', scale=.4, dimensions=(.30, .138), food=3, capacity=3),
                   yk('reimu', 9, 6, 'forager', stage='child', childhood=CHILDHOOD_CRAWLING_READY)],
    spot='spawn', vantage=(7, 8, 150, 4), env={'time': 'dawn'},
    late=[*_feeding_young, Retry(f'execute if entity {sel("bold")} run ' + set_status('mother', 'needs.nutrition', 65), sel('timid'), first=6)],
    reads=[('读亲油口中猎物', f'yukkuri status {sel("mother")} raw'), ('读赤油进食', f'yukkuri status {sel("bold")} raw')],
    smoke=dict(ticks=2100, forbid=[], expect=[r'prey lifted remains=', r'prey carried to nest child=', r'feed portion from prey ',
                                  r'feed mash prepared child=', r'feed handed to meal child=',
                                  r'\[meal\] remilia [^\n]* swallowed item=[^\n]*prepared=true'], verify=_verify_feeding_home))

add('pred_fledging', 'grove', '雏鸟学飞：扑翅、跳枝、首飞', '枝巢里的两只子油试着起飞',
    '七格高的巢垫上，两只子油已有同样的练习进度。看大胆的一只何时离枝、胆小的一只还练多久；失足后观察亲油是否落到树下守护',
    '约一至两分钟', _fledge_parent, late=_fledge_young,
    spot='spawn', vantage=(7, 8, 145, 2), env={'time': 'dawn'},
    reads=[('读大胆子油', f'yukkuri status {sel("bold")} raw'), ('读胆小子油', f'yukkuri status {sel("timid")} raw')],
    smoke=dict(ticks=2000, forbid=[], expect=[r'fledge flap progress=', r'fledge hop progress=',
               rf'uuid={_uuid_text("pred_fledging","bold")} [^\n]*first flight outcome=(?:landed|falling)'],
               allow_fate=[dict(kind='death', who=f'uuid={_uuid_text("pred_fledging", slot)} ', match=r'cause=fall\b',
                                why='a real fledgling fall from the branch') for slot in ('bold', 'timid')]))

_territory = 'pred_tree_territory'
_territory_resident = _uuid_text(_territory, 'resident')
_territory_intruder = _uuid_text(_territory, 'intruder')
_grove_pad = [
    block(0, 8, 0, 'mcyukkuri:predator_nest'),
    block(0, 7, -1, 'minecraft:oak_log[axis=z]'),
    box(-1, 7, 0, 4, 7, 0, 'minecraft:oak_log[axis=x]'),
    box(-1, 9, -1, 0, 9, 0, 'minecraft:oak_leaves[persistent=true]'),
    block(0, 8, -1, 'minecraft:air'),
    box(0, 4, 1, 0, 9, 3, 'minecraft:air', 'replace minecraft:oak_leaves'),
]
_grove_xz = r'BlockPos\{x=(-?\d+), y=-?\d+, z=(-?\d+)\}'


def _territory_sweeps_clear(states, sizes, start, end):
    # Sweep the two body boxes together using their relative motion between dense trace samples.
    for tick in range(start, end):
        r0, r1 = states[_territory_resident][tick]['p'], states[_territory_resident][tick + 1]['p']
        i0, i1 = states[_territory_intruder][tick]['p'], states[_territory_intruder][tick + 1]['p']
        rw, rh, rd = sizes[_territory_resident]
        iw, ih, id_ = sizes[_territory_intruder]
        limits = ((-(rw+iw)/2, (rw+iw)/2), (-ih, rh), (-(rd+id_)/2, (rd+id_)/2))
        first, last = 0, 1
        for a, b, c, d, (lo, hi) in zip(r0, r1, i0, i1, limits):
            origin, step = c - a, (d - c) - (b - a)
            if step:
                enter, leave = sorted(((lo - origin) / step, (hi - origin) / step))
                first, last = max(first, enter), min(last, leave)
            elif not lo < origin < hi:
                break
        else:
            if first < last:
                return False
    return True


def _verify_territory(window, trace):
    resident, intruder = _territory_resident, _territory_intruder
    log = rf'\[predator\] remilia #\d+ uuid={resident} t=(\d+) [^\n]*?'
    warn = re.search(log + rf' territory screech target={intruder}\b', window)
    pull = re.search(log + rf' territory pull-up target={intruder} feet=\([^,]+, ([^,]+), [^)]+\) target_top=(\S+)', window)
    departure = re.search(rf'\[predator\] flandre #\d+ uuid={intruder} t=(\d+) [^\n]*? intruder left pad={_grove_xz}', window)
    if not warn or not pull or not departure or not warn.start() < pull.start() < departure.start():
        return 'Remilia must screech, pull up above Flandre, and send her out of the territory in that order'
    if float(pull[2]) - float(pull[3]) < 1.5:
        return 'The threat pass pulled up less than 1.5 blocks over Flandre'
    start, end = int(warn[1]), int(pull[1])
    left, pad_x, pad_z = map(int, departure.groups())

    def inside_pad(x, z):
        return math.hypot(x-pad_x-.5, z-pad_z-.5) <= 10

    states = {resident: {}, intruder: {}}
    sizes = {}
    for line in trace:
        row = json.loads(line.split('[trace] ', 1)[1])
        if row['k'] != 'st' or row['u'] not in states:
            continue
        if row['u'] == intruder and row['t'] >= left:
            x, _, z = row['st']['p']
            if inside_pad(x, z):
                return 'Flandre returned to the foreign territory after leaving'
        if start <= row['t'] <= end:
            states[row['u']][row['t']] = row['st']
    for match in re.finditer(r'MCYGEOMETRY (\{[^\n]+\})', window):
        row = json.loads(match[1])
        if row['uuid'] in states and row['uuid'] not in sizes:
            sizes[row['uuid']] = tuple(row['box'][i + 3] - row['box'][i] for i in range(3))
    if any(who not in sizes or set(samples) != set(range(start, end + 1)) for who, samples in states.items()):
        return 'Missing dense trace or body dimensions for the warning and flight sweeps'
    first = states[resident][start]
    if first['face'] != 'angry' or first['animation'] != 'predator_flap':
        return 'Remilia must show the angry face and flap her wings during the screech'
    if not _territory_sweeps_clear(states, sizes, start, end):
        return 'The warned body intersected Remilia’s takeoff or approach sweep'
    roof = None
    settled = False
    address = (rf'\[home\] flandre #\d+ uuid={intruder} [^\n]*? (?:assigned kind=roof [^\n]*?'
               r'BlockPos\{x=(-?\d+), y=(-?\d+), z=(-?\d+)\}|released home=|assigned kind=\w+)')
    for match in re.finditer(address + r'|MCYGEOMETRY (\{[^\n]+\})', window):
        if match[4] is None:
            roof = tuple(map(int, match.groups()[:3])) if match[1] is not None else None
            continue
        if match.start() < departure.end():
            continue
        row = json.loads(match[4])
        if row['uuid'] == intruder:
            x, y, z, x2, _, z2 = row['box']
            feet = ((x+x2)/2, y, (z+z2)/2)
            if inside_pad(feet[0], feet[2]):
                return 'Flandre returned to the foreign territory after leaving'
            if row['supported'] and tuple(map(math.floor, feet)) == roof:
                settled = True
    if not settled:
        return 'Flandre must reach her claimed alternative roof on supported feet'


add(_territory, 'grove', '领地：尖叫驱赶闯入的捕食种', '领地：尖叫驱赶闯入的捕食种',
    '看蕾米莉亚张翅尖叫，在芙兰上方拉起。芙兰飞出巢树，到领地外另一片叶檐下歇息',
    '约半分钟', [
        *_grove_pad,
        box(4, 7, 1, 4, 7, 3, 'minecraft:oak_log[axis=z]'),
        block(4, 12, 3, 'minecraft:oak_leaves[persistent=true]'),
        box(13, 3, 4, 15, 3, 6, 'minecraft:oak_leaves[persistent=true]'),
        yk('remilia', 0, -1, 'territory_resident', dy=8, lead=True, nutrition=100, energy=34,
           boldness=65, uuid=uid(_territory, 'resident')),
        f'yukkuri scenario home {sel("territory_resident")} ~ ~8 ~',
    ],
    late=[Retry(yk('flandre', 4, 3, 'territory_intruder', dy=8, nutrition=100, energy=32,
                   boldness=25, uuid=uid(_territory, 'intruder')), sel('territory_resident'), first=1, tries=1)],
    spot='spawn', vantage=(6, 8, 145, -25), env={'time': 'dawn'},
    reads=[('读巢主', f'yukkuri status {sel("territory_resident")} raw'),
           ('读闯入者', f'yukkuri status {sel("territory_intruder")} raw')],
    smoke=dict(ticks=800, expect=[], forbid=[r'\[predator\] [^\n]* (?:combat strike|prey bite) '],
               verify=_verify_territory, verify_trace=True),
    capture=dict(shots=(60, 90, 120, 160, 240, 400), sequences=((55, 400, 5),),
                 views={'shelf': dict(position=(5, 10.5, 7), look_at=(1.5, 8.4, 1.5))}))

_pad_cleanup = 'pred_pad_cleanup'


def _verify_pad_cleanup(window):
    resident = _uuid_text(_pad_cleanup, 'resident')
    pushed = re.search(rf'\[predator\] remilia #\d+ uuid={resident} [^\n]*?'
                       r'leftovers pushed off pad=[^\n]*? remains=(\S+)', window)
    if pushed:
        for match in re.finditer(r'MCYGEOMETRY (\{[^\n]+\})', window[pushed.end():]):
            row = json.loads(match[1])
            if (row['uuid'] == pushed[1] and 'scn_pad_leftovers' in row['tags']
                    and row['supported'] and row['box'][1] == FEET):
                return
    return 'Remilia must push the staged remains and the same body must land on supported ground below the nest'


add(_pad_cleanup, 'grove', '清理枝巢：把空遗体推下树', '清理枝巢：把空遗体推下树',
    '看蕾米莉亚从枝上绕到空遗体后面，把同一具遗体推出巢垫，遗体落到树下地面',
    '约半分钟', [
        *_grove_pad,
        remains(0, -.4, 'pad_leftovers', dy=8.15, food=0, scale=.4, dimensions=(.30, .138)),
        yk('remilia', 1, 0, 'pad_cleaner', dy=8, lead=True, nutrition=100, energy=100,
           uuid=uid(_pad_cleanup, 'resident')),
        f'yukkuri scenario home {sel("pad_cleaner")} ~ ~8 ~',
    ],
    spot='spawn', vantage=(6, 8, 145, -25), env={'time': 'dawn'},
    reads=[('读巢主', f'yukkuri status {sel("pad_cleaner")} raw')],
    smoke=dict(ticks=650, expect=[], forbid=[], verify=_verify_pad_cleanup))

_grove_forage = 'pred_forage_avoidance'
_grove_forage_x, _grove_forage_z = BY_ID['grove'].spots['spawn']
_grove_forage_den, _ = den(_grove_forage_x + 12, _grove_forage_z + 18, half=1, length=4)
_grove_unsafe, _grove_safe = (8, 14), (5, 17)
_grove_forager, _grove_mate = (uid(_grove_forage, slot) for slot in ('forager', 'mate'))


def _verify_grove_forage(window):
    family = '|'.join(_uuid_text(_grove_forage, slot) for slot in ('forager', 'mate'))
    predator = _uuid_text(_grove_forage, 'predator')
    seen = re.search(rf'\[settlement\] \w+ #\d+ uuid=(?:{family}) [^\n]*? predator sighted predator={predator} site={_grove_xz}', window)
    if not seen:
        return 'The family must see the staged predator and record the real sighting before choosing grass'
    unsafe = (_grove_forage_x + _grove_unsafe[0], _grove_forage_z + _grove_unsafe[1])
    safe = (_grove_forage_x + _grove_safe[0], _grove_forage_z + _grove_safe[1])
    sighting = tuple(map(int, seen.groups()))
    if math.dist(sighting, unsafe) > 10 or math.dist(sighting, safe) <= 10:
        return 'The two staged tufts must fall on opposite sides of the real sighting’s threat zone'
    skipped = set()
    gathered = False
    actor = rf'\[(?:forage|habitat)\] \w+ #\d+ uuid=({family}) [^\n]*?'
    for match in re.finditer(actor + rf'(?:forage skipped reason=threat_zone site={_grove_xz} sighting={_grove_xz}|'
                             rf'gathered item=minecraft:short_grass count=\d+ source={_grove_xz})', window[seen.end():]):
        who, ux, uz, sx, sz, gx, gz = match.groups()
        if sx is not None:
            if (int(ux), int(uz)) == unsafe and (int(sx), int(sz)) == sighting:
                skipped.add(who)
        elif (int(gx), int(gz)) == unsafe:
            return 'A family member harvested the threatened tuft after the sighting'
        elif who in skipped and (int(gx), int(gz)) == safe:
            gathered = True
    if not gathered:
        return 'The same family member must skip the threatened tuft and harvest the staged safe one'


add(_grove_forage, 'grove', '林间采草：绕开目击捕食种的地方', '林间采草：绕开目击捕食种的地方',
    '一家看见高枝上的蕾米莉亚后，觅食或采寝草时绕过靠近目击处的一簇草，采走安全处的一簇',
    '约半分钟', [
        *_grove_forage_den,
        box(0, 0, 3, 21, 0, 23, 'minecraft:air', 'replace minecraft:short_grass'),
        box(8, 0, 5, 8, 7, 5, 'minecraft:oak_log'),
        block(8, 8, 5, 'mcyukkuri:predator_nest'),
        block(7, 7, 5, 'minecraft:oak_log[axis=x]'),
        box(7, 9, 4, 8, 9, 5, 'minecraft:oak_leaves[persistent=true]'),
        yk('remilia', 8, 5.33, 'grass_predator', dy=8.15, yaw=0, nutrition=100, energy=34, uuid=uid(_grove_forage, 'predator')),
        f'yukkuri scenario home {sel("grass_predator")} ~8 ~8 ~5',
        yk('reimu', 8, 13, 'grass_forager', yaw=180, nutrition=35, lead=True, uuid=_grove_forager,
           kin=kin_of(Partner=_grove_mate), also=('grass_family',)),
        yk('marisa', 8, 15, 'grass_mate', yaw=180, nutrition=78, uuid=_grove_mate,
           kin=kin_of(Partner=_grove_forager), also=('grass_family',)),
        f'yukkuri scenario community {sel("grass_mate")} {sel_all("grass_family")}',
        *[f'yukkuri scenario home {sel(name)} ~12 ~ ~17' for name in ('grass_forager', 'grass_mate')],
    ],
    # The family samples sightings every 100 ticks; the late function starts at tick 40.
    late=[Retry(block(x, 0, z, 'minecraft:short_grass'), sel('grass_forager'), first=161, tries=1)
          for x, z in (_grove_unsafe, _grove_safe)],
    spot='spawn', vantage=(2, 20, -152, 0), env={'time': 'dawn'},
    reads=[('读灵梦', f'yukkuri status {sel("grass_forager")} raw'),
           ('读魔理沙', f'yukkuri status {sel("grass_mate")} raw')],
    smoke=dict(ticks=700, expect=[], forbid=[], verify=_verify_grove_forage))

add('pred_noon_shade', 'grove', '正午躲荫，傍晚出猎', '正午躲荫，傍晚出猎',
    '正午树下已有子油。看营养为 50 的蕾米莉亚站在叶荫下守望。用「实用」切到傍晚，再看它发现猎物后离枝',
    '守望约半分钟后切换时间', [*_perch_resident(50), yk('reimu', 5, 6, 'shade_prey', stage='child', nutrition=46)],
    spot='spawn', vantage=(7, 8, 135, 0), env=DAY, reads=[('读正午守望', f'yukkuri status {sel("perch_pred")} raw')],
    smoke=dict(ticks=320, expect=[r'\[home\] remilia [^\n]* assigned kind=care', r'\[predator\] remilia [^\n]* perch begin [^\n]*shade=true'],
               forbid=[r'\[predator\] remilia [^\n]* prey selected ']))

add('forage_empty','weather','没粮的饿灵梦四处找吃的','没粮的饿灵梦四处找吃的',
    '开始不放食物：先切到空手，饥饿灵梦立即主动搜索（同一游荡状态、流口水表情），搜不到也不定时退回idle；手持苹果时立即走向玩家，再丢下观察接近、吞咽与营养回升；读移速可见紧急修正。真实受阻仍会饿，不指定Goal',
    '约30秒后丢食物',[yk('reimu',0,0,'seeker',nutrition=18,energy=90,lead=True)],
    env=DAY,spot='pred',give=['minecraft:apple 3'],reads=[('读主动寻粮',f'yukkuri status {sel("seeker")} raw')])

add('shared_body','weather','普通种与捕食种同一身体结构','普通种与捕食种同一身体结构',
    '同一主人的宠物普通魔理沙、蕾米莉亚、弗兰共用身体结构。绕到背面看油菊；按钮对照佩妮佩妮和马姆马姆伸缩，再看通用行走。伸缩仍互斥，默认收回；这里只验素材，不代表行为成功',
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
    '搬入几十秒，筑巢数分钟', [say('按 M，在「实用」页选“模拟人类活动”，再用“推进”快进。', 'gold')], env=DAY, vantage=(0, 8, 180, 10),
    reads=[('读家油现状', 'yukkuri household status')])
add('house_pair', 'house', '一对家油挑墙角叼絮筑窝', '一对家油挑墙角叼絮筑窝', '从夜晚开始，屋内已有真实人类活动留下的絮尘和食屑：家油挑墙角、叼絮铺窝、储食；按“正午”对照白天躲窝休息',
    '数分钟（可倍速）', [*house_pair(), HOUSE_ACTIVITY], env={'time': 'night'}, vantage=(0, 5, 180, 15, 0),
    reads=[('读家油现状', 'yukkuri household status')])

HNEST = 'mcyukkuri:house_dust_nest{Materials:6,Food:8,Arrivals:2,Entrance:"south"%s}'
add('house_eggs', 'house', '窝脏了，家油把卵叼去新窝', '窝脏了，家油把卵叼去新窝', '先建立真实卵生胎次，再让原窝变脏（Dirt 8）；东侧有一个干净新窝，家油逐只叼卵搬过去照看',
    '数分钟（可倍速）', [block(-5, 0, -2, HNEST % ''), block(5, 0, -2, HNEST % ''),
                       *house_pair('house_eggs', -4, -5, -1), HOUSE_ACTIVITY,
                       f'yukkuri scenario home {sel("a")} ~-5 ~ ~-2', f'yukkuri scenario home {sel("b")} ~-5 ~ ~-2',
                       f'yukkuri scenario eggs {sel("b")} {sel("a")}'],
    env={'time': 'night'}, vantage=(0, 5, 180, 15, 0), reads=[('读家油现状', 'yukkuri household status'),
        ('读迁卵与亲代住所', 'yukkuri status @e[type=#mcylab:yukkuri,distance=..18] raw'),
        ('读原窝卵与迁卵记录', 'data get block ~-5 ~ ~-2'),
        ('读新窝卵与接收记录', 'data get block ~5 ~ ~-2')],
    late=['execute if data block ~-5 ~ ~-2 EggSlots[{Present:1b}] run data merge block ~-5 ~ ~-2 {Dirt:8}'])

_water_pet = _uuid_text('pet_water', 'water_pet')
add('pet_water', 'house', '宠物渴了求水，玩家用水桶加满饮水器', '宠物渴了求水，玩家用水桶加满饮水器',
    '看屋里右侧的灵梦抿嘴向你求水，头顶亮出饮水器图样。等它开口后，拿水桶右键旁边的空饮水器。'
    '看提示消失、灵梦自己去喝，水位从八份降到七份。生存模式会收回空桶。重开也可以试一瓶清水，加三份并收回玻璃瓶。',
    '约几秒求水；加水后几秒喝水', [
        block(3, 0, 0, 'mcyukkuri:waterer'),
        yk('reimu', 2, -1, 'water_pet', uuid=uid('pet_water', 'water_pet'), lead=True, yaw=0),
        set_status('water_pet', 'needs.dryness', 50), *own('water_pet'),
    ], give=['minecraft:water_bucket', 'minecraft:potion[minecraft:potion_contents={potion:"minecraft:water"}]'],
    env=DAY, vantage=(2, 1, 190, 25, 0),
    reads=[('读饮水器余水', 'data get block ~3 ~ ~ Water'), ('读灵梦的口渴与请求', f'yukkuri status {sel("water_pet")} raw')])

_away_pet = _uuid_text('pet_water_away', 'away_pet')
add('pet_water_away', 'house', '宠物渴了被关在屋外，玩家先加水再开门，它进屋才发现', '宠物渴了被关在屋外，玩家先加水再开门，它进屋才发现',
    '灵梦渴了，被屋口三扇关着的栅栏门挡在屋外，从没看过屋里的饮水器，只在门外等着往屋里看。你站在屋里的空饮水器旁，先用水桶把它加满，'
    '再去把门打开：灵梦在门外看不见也听不见加水，没有反应。门一开它自己走进来，看见满水，不会谢你，只露出满意的样子，然后自己去喝。',
    '加水和开门随你；开门后几秒喝水', [
        *(block(x, 0, 7, 'minecraft:oak_fence_gate[facing=south]') for x in (-1, 0, 1)),
        box(-1, 1, 7, 1, 2, 7, 'minecraft:oak_planks'),
        block(3, 0, 0, 'mcyukkuri:waterer'),
        yk('reimu', 0, 10, 'away_pet', uuid=uid('pet_water_away', 'away_pet'), lead=True, yaw=180),
        set_status('away_pet', 'needs.dryness', 50), *own('away_pet'),
    ], give=['minecraft:water_bucket'],
    env=DAY, vantage=(2, 1, 190, 25, 0),
    reads=[('读饮水器余水', 'data get block ~3 ~ ~ Water'), ('读灵梦的口渴与请求', f'yukkuri status {sel("away_pet")} raw')])

_walled_pet = _uuid_text('pet_food_walled', 'walled_pet')
add('pet_food_walled', 'house', '饿坏的宠物看不见墙后的食盆，自己绕过去吃', '饿坏的宠物看不见墙后的食盆，自己绕过去吃',
    '灵梦很饿，屋子西北角的墙后有一只装着苹果的食盆，从它站的地方看不见。看它不求食，自己走到墙边绕过去，看见食盆后吃苹果。',
    '约十几秒到半分钟', [
        box(-4, 0, 0, -3, 2, 0, 'minecraft:oak_planks'), box(-4, 0, -1, -4, 2, -1, 'minecraft:oak_planks'),
        block(-3, 0, -1, 'mcyukkuri:food_bowl{Items:[{Slot:0b,id:"minecraft:apple",count:8}]}'),
        yk('reimu', -3, 3, 'walled_pet', uuid=uid('pet_food_walled', 'walled_pet'), lead=True, yaw=180, nutrition=20), *own('walled_pet'),
    ], env=DAY, vantage=(-3, 5, 180, 25, 0),
    reads=[('读灵梦营养与请求', f'yukkuri status {sel("walled_pet")} raw')])

_empty_pet = _uuid_text('pet_food_empty', 'empty_pet')
add('pet_food_empty', 'house', '饿坏的宠物走到墙后，看见食盆是空的才向你求食', '饿坏的宠物走到墙后，看见食盆是空的才向你求食',
    '灵梦很饿，屋子西北角的墙后有一只空食盆，从它站的地方看不见。看它不求食，自己绕过墙去看；看见盆是空的，才抿嘴向你求食。'
    '在它求食时往食盆里放苹果，看它去吃。',
    '约十几秒走过去；看见空盆后几秒求食', [
        box(-4, 0, 0, -3, 2, 0, 'minecraft:oak_planks'), box(-4, 0, -1, -4, 2, -1, 'minecraft:oak_planks'),
        block(-3, 0, -1, 'mcyukkuri:food_bowl'),
        yk('reimu', -3, 3, 'empty_pet', uuid=uid('pet_food_empty', 'empty_pet'), lead=True, yaw=180, nutrition=38), *own('empty_pet'),
    ], env=DAY, vantage=(-3, 5, 180, 25, 0),
    reads=[('读灵梦营养与请求', f'yukkuri status {sel("empty_pet")} raw')])

_return_pet = _uuid_text('pet_door_return', 'return_pet')
add('pet_door_return', 'house', '困倦的院宠穿门回屋睡觉', '困倦的院宠穿门回屋睡觉',
    '灵梦在南门外，自己的窝在屋里。看它从斜侧走到敞开的橡木门前，挪进门缝，再走进自己的窝睡下。', '约半分钟', [
        *(box(x, 0, 7, x, 2, 7, 'minecraft:oak_planks') for x in (-1, 1)),
        block(0, 2, 7, 'minecraft:oak_planks'),
        *(block(0, dy, 7, f'minecraft:oak_door[facing=south,hinge=left,half={half},open=true]')
          for dy, half in enumerate(('lower', 'upper'))),
        block(0, 0, 4, 'mcyukkuri:yukkuri_nest[facing=south]'),
        yk('reimu', 2, 12, 'return_pet', uuid=uid('pet_door_return', 'return_pet'), label='回窝的院宠',
           lead=True, nutrition=100, rest_wait=0), *own('return_pet'),
    ], late=[home('return_pet', 0, 4), set_status('return_pet', 'needs.energy', 30)],
    env=DAY, vantage=(-3, 11, -135, 15, 0), reads=[('读院宠归属与住所', f'yukkuri status {sel("return_pet")} raw')])

_doll_comfort = 'doll_comfort_sleep'
add(_doll_comfort, 'house', '孤单宠物蹭布偶并面向它入睡', '孤单宠物蹭布偶并面向它入睡',
    '傍晚的屋里隔着木板墙分成左右两边。左侧孤单的灵梦宠物（依恋 85、亲近需求 80）身边没有熟识同伴，'
    '走到窝旁的灵梦布偶边蹭脸得到少许安抚，随后回窝面向布偶睡下；同侧另一只胆大低依恋的灵梦不理会布偶。'
    '右侧有伴的魔理沙身旁既有布偶也有熟识同伴，只去蹭真实同伴而不蹭布偶。',
    '约半分钟', [
        box(0, 0, -2, 0, 2, 6, 'minecraft:oak_planks'),
        *(block(x, 0, 7, 'minecraft:oak_fence_gate[facing=south]') for x in (-1, 0, 1)),
        block(-2, 0, -1, 'mcyukkuri:yukkuri_nest[facing=south]'),
        item('mcyukkuri:reimu_doll', 1, -3, 0, 'comfort_doll'),
        yk('reimu', -3, 2, 'comfort_reimu', uuid=uid(_doll_comfort, 'comfort_reimu'), yaw=180, lead=True,
           label='孤单的灵梦', attachment=85, boldness=40, friendliness=70, affection=80,
           nutrition=95, energy=45, rest_wait=260),
        *own('comfort_reimu'),
        yk('reimu', -4, 3, 'bold_reimu', uuid=uid(_doll_comfort, 'bold_reimu'), yaw=180,
           label='胆大低依恋灵梦', attachment=15, boldness=90, friendliness=40, affection=80,
           nutrition=95, energy=95, rest_wait=1200),
        item('mcyukkuri:marisa_doll', 1, 4, -1, 'pair_doll'),
        yk('marisa', 3, -1, 'paired_marisa', uuid=uid(_doll_comfort, 'paired_marisa'), yaw=0,
           label='有伴的魔理沙', attachment=85, boldness=50, friendliness=80, affection=80,
           nutrition=95, energy=95, rest_wait=1200),
        *own('paired_marisa'),
        yk('reimu', 3, 1, 'paired_peer', uuid=uid(_doll_comfort, 'paired_peer'), yaw=180,
           label='熟识同伴', attachment=100, boldness=50, friendliness=80, affection=0,
           nutrition=95, energy=95, rest_wait=1200),
        *own('paired_peer'),
        f'yukkuri scenario bond {sel("paired_marisa")} {sel("paired_peer")}',
    ], at_spawn=[home('comfort_reimu', -2, -1)],
    env={'time': 'night'}, vantage=(-2, 5, 180, 20, 0),
    reads=[('读孤单灵梦', f'yukkuri status {sel("comfort_reimu")} raw'),
           ('读有伴魔理沙', f'yukkuri status {sel("paired_marisa")} raw'),
           ('读胆大灵梦', f'yukkuri status {sel("bold_reimu")} raw')])

# The plains template starts its caretaker on (5,1,9). Keep the cottage in the next activity
# column east of the empty house, with the observer out on the lane.
_occupied_house = 'execute positioned ~14 ~ ~6 run yukkuri household status'
_empty_house = 'yukkuri household status'
_cottage_x, _cottage_z = BY_ID['house'].anchor
_cottage_x += 9
_cottage_z -= 3
_caretaker_home = ((_cottage_x + 5) << 38) | ((_cottage_z + 9) << 12) | FEET
_yard_pet_selector = ('@e[type=#mcylab:yukkuri,type=!mcyukkuri:house_reimu,type=!mcyukkuri:house_marisa,'
                     f'x={_cottage_x + 6},y={FEET},z={_cottage_z + 4},distance=..10,limit=1,sort=nearest]')
add('resident_house_lint', 'house', '居民在家留下絮尘，家油叼絮筑窝', '居民在家留下絮尘，家油叼絮筑窝',
    '地毯上的居民在家过夜。先在屋外等絮尘积起，再从窗外看家油叼起一份絮尘带回墙角铺窝。对照隔壁无人住的屋。'
    '观察积尘时先留在起点，等搬入后再靠近。', '约三分钟，可用场内调速', [
        box(9, 0, -3, 17, 8, 9, 'minecraft:air'),
        'place template mcyukkuri:village/plains/home_yard ~9 ~-1 ~-3',
    ], env={'time': 'night', 'spawn_window': (60, 4400)}, vantage=(16, -15, 0, 8, 0),
    reads=[('读居民家的絮尘与窝', _occupied_house), ('读空屋的絮尘与活动', _empty_house)])

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
       boldness=95, friendliness=40, self_control=40,
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

def begging_scene(sid, title, watch, pet_traits, stray_traits, helper=False, vantage=(5, -8, 0, 15)):
    start = [yk('marisa', 7, -4, 'wild', uuid=uid(sid, 'wild'), yaw=90, picky=0, **stray_traits),
             yk('reimu', 3, -4, 'pet', uuid=uid(sid, 'pet'), yaw=-90, picky=0, **pet_traits,
                extra='YukkuriMouth:{Purpose:"MEAL",Items:[{id:"minecraft:apple",count:1}]}')]
    if helper:
        start += [human('caretaker_brother', 3, -10, 'beg_keeper', 0, label='灵梦的饲主').removesuffix('}')
                  + ',StreetHomePause:-6000}',
                  f'data modify entity {sel("pet")} {OWNED} set value {{Use:"companion",OwnerKind:"npc"}}',
                  f'data modify entity {sel("pet")} {OWNED}.Owner set from entity @e[tag={tag("beg_keeper")},limit=1] UUID']
    else:
        start += own('pet')
    start += [f'data merge entity {sel("pet")} {{YukkuriLife:{{MealTicks:1}}}}']
    add(sid, 'town', title, title, watch, '约 20–45 秒', start, env=DAY,
        vantage=vantage,
        reads=[('读灵梦的营养与关系', f'yukkuri status {sel("pet")} raw'),
               ('读魔理沙的营养与关系', f'yukkuri status {sel("wild")} raw')])


begging_scene('wild', '流浪油讨食：分享苹果', '看魔理沙隔开一段距离开口，灵梦分出苹果。魔理沙道谢、吃掉一份，离开前回望。',
              dict(nutrition=54, boldness=60, friendliness=90), dict(nutrition=29, boldness=55, friendliness=65))
begging_scene('wild_beg_refuse', '流浪油讨食：拒绝后纠缠', '看灵梦护住苹果并扭开，魔理沙绕到侧面求食、跺地哭泣，最后慢慢走开。',
              dict(nutrition=52, boldness=60, friendliness=15), dict(nutrition=30, boldness=60, friendliness=50),
              vantage=(-2, -6, -89, 14))
begging_scene('wild_beg_snatch', '流浪油讨食：抢走苹果', '看饥饿的魔理沙扑近抢食，灵梦惊叫追赶，随后记仇。',
              dict(nutrition=52, boldness=70, friendliness=15), dict(nutrition=18, boldness=85, friendliness=5, self_control=18))
begging_scene('wild_beg_help', '流浪油讨食：跑到饲主脚边求助', '看胆小的灵梦叼着苹果跑到饲主脚边，饲主走过来喝斥赶开魔理沙，再安抚灵梦。',
              dict(nutrition=52, boldness=20, friendliness=45, attachment=80),
              dict(nutrition=29, boldness=65, friendliness=50), helper=True, vantage=(11, -7, 83, 12))

add('badge', 'town', '钻石章灵梦遇到嫉妒的野油', '钻石章灵梦遇到嫉妒的野油', '野生魔理沙看见灵梦佩戴的钻石章，鼓脸或闹别扭。看灵梦按自尊与关系友善回礼、得意展示或退缩。', '约 1 分钟', [
    *(block(x, 0, z, 'minecraft:oak_fence') for x in range(-1, 4) for z in range(-2, 3)
      if x in (-1, 3) or abs(z) == 2),
    yk('reimu', 0, 0, 'pet', yaw=-90, lead=True, friendliness=53, boldness=35), *own('pet'),
    yk('marisa', 1.5, 0, 'wild', yaw=90, nutrition=84, boldness=68, friendliness=0, attachment=0, self_control=38),
    f'yukkuri scenario badge {sel("pet")} platinum false'], env=DAY, vantage=(1, 8))

_PRAISE_HINT = '宠物自己做对且饲主在旁看见时，头上会出现约 4 秒的「？」：这时空手摸它就是夸奖。'


def _practice_scene(sid, title, hint, wait, props, env, vantage, expect, **need):
    """A practised reimu (west) and a novice marisa (east) with the same need and the same furniture: one daily routine per scene."""
    lines = []
    for x, kind, name, points in ((-7, 'reimu', 'practised', 8), (7, 'marisa', 'novice', 0)):
        lines += [
            *shed(x, -4), *props(x, name),
            yk(kind, x, 3, name, rest_wait=0, lead=x < 0, yaw=0, uuid=uid(sid, name),
               label='练过的宠物' if x < 0 else '刚开始练习', self_control=95 if x < 0 else 24,
               skills={'food_guard': points, 'toilet': points}, extra=f'YukkuriPet:{{Neglect:{0 if points else 60}}}',
               **need),
            *own(name), home(name, x, -4)]
    add(sid, 'town', title, title, hint, wait, lines, env=env, vantage=vantage,
        give=['mcyukkuri:field_notebook', 'mcyukkuri:badge_handbook', 'mcyukkuri:sweet_candy 6'],
        reads=[('读熟手技能', f'data get entity {sel("practised")} YukkuriSkills'),
               ('读新手技能', f'data get entity {sel("novice")} YukkuriSkills')],
        smoke=dict(ticks=2100, expect=expect, forbid=[r'\[body\][^\n]* invariant\b'],
                   readout=[f'data get entity {sel(n)} YukkuriSkills' for n in ('practised', 'novice')]))


_practice_scene(
    'training_bowl', '宠物日常练习：吃食盆还是吃散食',
    '两只一样饿的宠物面前各有一个装着苹果的食盆，旁边地上还有两个散落的苹果。左边练过的灵梦到食盆吃，右边刚开始练习的魔理沙略过食盆去吃地上的。' + _PRAISE_HINT,
    '约 1 分钟',
    lambda x, name: [block(x, 0, 5, 'mcyukkuri:food_bowl{Items:[{Slot:0b,id:"minecraft:apple",count:8}]}'),
                     item('minecraft:apple', 2, x - 1, 4, name + '_apples')],
    DAY, (0, 12, 180, 15),
    [r'\[skill\][^\n]* practice ability=FOOD_GUARD source=daily points=8 -> 9',
     r'\[lesson\] marisa [^\n]*bowl skipped for loose food'],
    nutrition=48)


def _verify_bowl_treats(window):
    pet = _uuid_text('training_bowl', 'practised')
    actor = rf'[^\n]* uuid={pet} t=(\d+) [^\n]* '
    wait = re.search(r'\[lesson\]' + actor + r'wait treat taken owner=(\S+) early=(true|false)', window)
    if not wait:
        return 'Missing the trained pet’s wait treat handover'
    owner, early = wait[2], wait[3] == 'true'
    treat = rf' item=mcyukkuri:sweet_candy count=1[^\n]* feeder={owner}\b'
    received = re.findall(r'\[meal\]' + actor + r'received' + treat, window)
    swallowed = re.findall(r'\[meal\]' + actor + r'swallowed' + treat, window)
    learned = re.findall(r'\[rules\]' + actor + rf'obedience reason=(\w+) owner={owner} shift=(-?\d+) -> (-?\d+)', window)
    shift = '-3' if early else '1'
    if len(received) != 2 or wait[1] != received[0] or learned != [
            (received[0], 'sweet' if early else 'reward', '0', shift),
            (received[1], 'sweet', shift, str(int(shift) - 3))]:
        return f'Expected exactly one obedience update per handover: received={received}, learned={learned}'
    upbringing = re.findall(r'\[upbringing\]' + actor + r'cause=(waited|surplus_sweet) trait=(\w+)', window)
    if len(swallowed) != 2:
        return f'Expected both sweets to be swallowed: {swallowed}'
    expected = [(swallowed[0], 'waited', 'SELF_CONTROL')]
    if early:
        expected = [(swallowed[0], 'surplus_sweet', trait) for trait in ('FRIENDLINESS', 'SELF_CONTROL')]
    expected += [(swallowed[1], 'surplus_sweet', trait) for trait in ('FRIENDLINESS', 'SELF_CONTROL')]
    if upbringing != expected:
        return f'Expected the wait and surplus upbringing to settle only at swallow: {upbringing}'


_bowl_smoke = next(s for s in S if s.id == 'training_bowl').smoke
_bowl_pet = sel('practised')
_bowl_owner = '@p[name=MuseumOperator,distance=..48]'
_bowl_smoke['commands'] = [
    (440, f'execute at {_bowl_pet} positioned ~ ~ ~1 run yukkuri scenario tool_operator enter'),
    (441, f'data modify entity {_bowl_pet} {OWNED}.Owner set from entity {_bowl_owner} UUID'),
    (441, f'data modify entity {_bowl_pet} YukkuriSkills.Points.wait set value 9'),
    (442, set_status('practised', 'needs.nutrition', 82)),
    (442, f'item replace entity {_bowl_owner} weapon.offhand with mcyukkuri:sweet_candy'),
    (443, f'yukkuri scenario badge_lesson_click {_bowl_pet}'),
    (930, f'execute at {_bowl_pet} positioned ~ ~ ~1 run yukkuri scenario tool_operator move'),
    (930, f'item replace entity {_bowl_owner} hotbar.0 with mcyukkuri:sweet_candy'),
    (931, f'yukkuri scenario shop_stock_click {_bowl_pet}'),
    (1100, 'yukkuri scenario tool_operator leave'),
]
_bowl_smoke['verify'] = _verify_bowl_treats


_practice_scene(
    'training_toilet', '宠物日常练习：去沙厕还是拉在地上',
    '两只宠物便意一样急，斜前方各有一个沙厕。左边练过的灵梦走进沙厕解决，右边刚开始练习的魔理沙就地拉在地上，留下要清理的排泄物。' + _PRAISE_HINT,
    '约 1 分钟',
    lambda x, name: [block(x + 2, 0, 4, 'mcyukkuri:yukkuri_toilet')],
    DAY, (0, 12, 180, 15),
    [r'\[needs\][^\n]*relieved source=facility',
     r'\[needs\] marisa [^\n]*poop (?:placed|stays a loose item) ',
     r'\[needs\] marisa [^\n]*relieved source=ground [^\n]*accident=true'],
    bladder=73)

_practice_scene(
    'training_bed', '宠物日常练习：回窝睡觉',
    '入夜，两只一样困的宠物身后各有一间带软窝的小棚。看灵梦和魔理沙各自去哪儿睡下。',
    '入夜开始，约 1–2 分钟',
    lambda x, name: [],
    {'time': 'night'}, (0, 9, 180, 12),
    [r'\[sleep\][^\n]*begin source=life'],
    energy=52)
add('training_study', 'town', '宠物数苹果', '宠物数苹果',
    '魔理沙面前有六个苹果，超过上限时会说「好多」，也可能困惑答错。约 20 秒后漏斗收走黄垫上的三个，看它下次能否数清剩下的三个。',
    '约 1 分钟', [
        *(block(x, 0, z, 'minecraft:oak_fence') for x in range(3, 9) for z in range(-2, 4)
          if x in (3, 8) or z in (-2, 3)),
        block(5, 0, 3, 'minecraft:oak_fence_gate[facing=south]'),
        item('minecraft:apple', 3, 6, 0, 'count_apples'),
        block(6, -1, 2, 'minecraft:hopper{TransferCooldown:400}'),
        block(6, 0, 2, 'minecraft:yellow_carpet'),
        item('minecraft:apple', 3, 6, 2, 'hopper_apples', dy=.1),
        yk('marisa', 5, 0, 'counter', nutrition=96, social_wait=0, lead=True, intelligence=90, self_control=86,
           skills={'counting': 8}, cap=3), *own('counter')],
    env=DAY, vantage=(5, 8), give=['mcyukkuri:field_notebook', 'mcyukkuri:badge_handbook'],
    reads=[('读数数技能', f'data get entity {sel("counter")} YukkuriSkills'),
           ('读养育经历', f'data get entity {sel("counter")} YukkuriLife.Personality')])

add('skills_magnifier', 'town', '望远镜看技能行', '望远镜看技能行',
    '依次用望远镜看三只宠物。灵梦有等待★和如厕✓，魔理沙能数到六所以有数数✓，橙尚无技能。田野手册的技能页会写明等级和数数上限。',
    '随时', [
        yk('reimu', -3, 0, 'trained', stage='child', label='等待和如厕', skills={'wait': 9, 'toilet': 5}, cap=2, lead=True), *own('trained'),
        yk('marisa', 0, 0, 'counting', stage='child', label='数到六', cap=6), *own('counting'),
        yk('chen', 3, 0, 'untrained', stage='child', label='尚无技能', cap=2), *own('untrained')],
    env=DAY, vantage=(0, 6), give=['minecraft:spyglass', 'mcyukkuri:field_notebook'],
    smoke=dict(ticks=100, expect=[], forbid=[r'\[body\][^\n]* invariant\b']),
    reads=[(f'读{name}', f'data get entity {sel(actor)} YukkuriSkills') for name, actor in (
        ('等待和如厕', 'trained'), ('数到六', 'counting'), ('尚无技能', 'untrained'))])

add('skills_got_it', 'town', '如厕后学会了', '如厕后学会了',
    '灵梦离如厕二级还差一点，有便意，旁边就是沙厕。等它自己用完沙厕，留意欢跳、闪光、台词和操作栏提示，再用田野手册查看学会记录。',
    '约 10 秒', [
        block(0, 0, 0, 'mcyukkuri:yukkuri_toilet'),
        yk('reimu', 0, 2, 'learner', uuid=uid('skills_got_it', 'learner'), bladder=80, self_control=100, lead=True,
           skills={'toilet': 4}), *own('learner')],
    env=DAY, vantage=(0, 5), give=['mcyukkuri:field_notebook', 'minecraft:spyglass'],
    reads=[('读如厕技能', f'data get entity {sel("learner")} YukkuriSkills')],
    smoke=dict(ticks=320, expect=[r'\[needs\][^\n]* relieved source=facility',
                                  r'\[skill\][^\n]* learned ability=TOILET grade=2 source=daily'],
               forbid=[r'\[body\][^\n]* invariant\b'],
               readout=[f'data get entity {sel("learner")} YukkuriSkills']))

def exam_hall():
    return [block(-3, 0, 0, 'mcyukkuri:exam_podium[facing=east]'),
            human('caretaker_brother', 0, 0, 'judge', 3, yaw=180, label='考官')]


_rule_court = [
    box(-6, -1, -8, 6, -1, 6, 'minecraft:grass_block'),
    box(-6, 0, -8, 6, 3, 6, 'minecraft:air'),
    *(box(-5, 0, z, 5, 0, z, 'minecraft:oak_fence[east=true,west=true]') for z in (-7, 5)),
    *(box(x, 0, -6, x, 0, 4, 'minecraft:oak_fence[north=true,south=true]') for x in (-5, 5)),
    block(0, 0, 5, 'minecraft:oak_fence_gate[facing=south]'), *shed(0, -4)]


def _rule_owner(mask):
    pet = sel('rule_pet')
    return [(1, 'yukkuri scenario tool_operator enter'),
            (10, f'yukkuri scenario owner_at {pet} watch'),
            (11, f'yukkuri scenario house_rules {pet} {mask}')]


_PENANCE_BEAT = re.compile(r'\[rules\][^\n]*? t=(\d+) [^\n]*penance beat=(\w+) face=(\S+) declared=(\S+) line=(\S+) spoke=(\w+) '
                           r'clip=(\S+) declaredClip=(\S+) entrance=(\w+) rule=(\S+)')
# Faces of pleasure and pout: nothing between the catch (or the bow) and the owner's answer may show one.
_PLEASED_OR_POUTING = {'happy', 'open_smile', 'satisfied', 'smug', 'closed_smile', 'pursed', 'angry', 'cheek_puff', 'puff', 'sneer',
                       'hmph', 'blank_happy', 'flushed_smile', 'sukkiri_grin', 'sukkiri_smug'}
_PENANCE_LINES = {'startle': '-', 'bow': 'confess|confess_mate|exam_sorry', 'mutter': 'sulk_huff', 'afraid': '-', 'plea': 'plea_spare', 'apology': 'caught_guilty',
                  'prostrate': '-', 'shiver': '-', 'stiff': '-', 'punished': 'scolded|hurt_plea', 'forgiven': 'request_thanks', 'withdraw': '-',
                  # The answer came through the child: the struggle, how it ends, and the silent ache that follows.
                  'torn': '-', 'shield': 'protect_taunt', 'plead': 'plea_spare', 'submit': '-', 'flee': '-', 'ache': '-'}


def _penance_beats(window, entrance, rule, pet=None):
    """One pet's penance beats as (tick, name, shown face, declared face, line, spoke, clip, declared clip), in log order."""
    return [(int(m[1]), *m.groups()[1:8]) for line in window.splitlines()
            if (pet is None or f'uuid={pet} ' in line) and (m := _PENANCE_BEAT.search(line)) and m[9] == entrance and m[10] == rule]


def _penance_problem(beats, first, last):
    """The beats must start with ``first``, end with one of ``last``, show the face they declared, pair each with its line, and never
    show a pleased or pouting face (the one logged mutter beat excepted)."""
    names = [b[1] for b in beats]
    if names[:len(first)] != first or not names or names[-1] not in last:
        return f'Penance beats {names} must start {first} and end {last}'
    for tick, name, shown, declared, line, spoke, clip, declared_clip in beats:
        if shown != declared:
            return f'Beat {name} at {tick} declared face {declared} but showed {shown}'
        if not re.fullmatch(_PENANCE_LINES[name], line):
            return f'Beat {name} at {tick} carries line {line}, expected {_PENANCE_LINES[name]}'
        if line != '-' and spoke != 'true':
            return f'Beat {name} at {tick} line {line} was not voiced'
        if name != 'mutter' and shown in _PLEASED_OR_POUTING and name != 'forgiven':
            return f'Beat {name} at {tick} shows {shown}'
    return None


def _answer_tick(window):
    """The tick the owner's stroke, scold or blow reached the pet: the owner's own hand (a stroke's closed smile) is the answer, not part of the wait."""
    return int(re.search(r'\[rules\][^\n]* t=(\d+) [^\n]*penance verdict=', window)[1]) - 1


def _pleased_between(trace, start, end, pet=None):
    """The first pleased or pouting face the body really showed in the sampled state between two ticks (or None)."""
    for line in trace:
        row = json.loads(line.split('[trace] ', 1)[1])
        if row['k'] == 'st' and (pet is None or row['u'] == pet) and start <= row['t'] <= end and row['st'].get('face') in _PLEASED_OR_POUTING:
            return f'Face {row["st"]["face"]} shown at {row["t"]} between {start} and {end}'
    return None


def _song_discipline(window, trace):
    changes = re.findall(r'\[rules\][^\n]* obedience reason=(caught|scold) [^\n]*shift=(-?\d+) -> (-?\d+)[^\n]*closeness=(-?\d+) fear=(\d+)', window)
    if len(changes) != 2 or [row[0] for row in changes] != ['caught', 'scold']:
        return f'Expected one catch followed by one reprimand, got {changes}'
    caught, scold = changes
    if any(int(row[2]) - int(row[1]) != 4 for row in changes) or caught[2] != scold[1] or caught[3] != scold[3] or int(scold[4]) <= int(caught[4]):
        return f'Wrong learning, closeness or fear after catch and reprimand: {changes}'
    # The song stops at the catch and nothing pleased follows: no completed session, no welcome, no stranger retreat after it.
    catch = re.search(r'\[rules\][^\n]* t=(\d+) [^\n]*caught rule=no_singing evidence=sight', window)
    after = '\n'.join(line for line in window[catch.end():].splitlines()
                      if (stamp := re.search(r' t=(\d+) ', line)) and int(stamp[1]) <= int(catch[1]) + 400)
    stopped = re.search(r'\[daily\][^\n]* t=(\d+) [^\n]*session stopped kind=SING', after)
    if not stopped or int(stopped[1]) - int(catch[1]) > 3:
        return 'The song must stop within three ticks of the catch'
    for pattern in (r'session result kind=SING', r'owner return approach', r'human retreat', r'owner return greeted'):
        if re.search(pattern, after):
            return f'After the catch the pet must not show {pattern}'
    # A bold, disobedient pet that does not fear its owner mutters once, stands stiff, then turns afraid when scolded and takes it.
    beats = _penance_beats(window, 'CAUGHT', 'no_singing')
    problem = _penance_problem(beats, ['startle', 'mutter', 'stiff'], ['punished'])
    if problem:
        return problem
    if [b[1] for b in beats[-2:]] != ['afraid', 'punished'] or [b[1] for b in beats].count('mutter') != 1:
        return f'The scold must turn the stiff pet afraid before it takes the punishment: {[b[1] for b in beats]}'
    return _pleased_between(trace, int(catch[1]), _answer_tick(window))


def _permitted_meal(window):
    beats = re.findall(r'\[(?:rules|meal)\] reimu [^\n]*? (waiting rule=no_eating_unasked|permitted rule=no_eating_unasked|pickup item=minecraft:apple count=1|swallowed item=minecraft:apple count=1)', window)
    expected = ['waiting rule=no_eating_unasked', 'permitted rule=no_eating_unasked', 'pickup item=minecraft:apple count=1', 'swallowed item=minecraft:apple count=1']
    if beats != expected:
        return f'Expected one wait, permission, transfer and swallow in order, got {beats}'


_rule_pet = sel('rule_pet')
_rule_songs = _rule_owner(3) + [(12, f'yukkuri scenario owner_at {_rule_pet} away'),
                               (12, 'scoreboard players set #rule_scold museum 0'), (12, 'scoreboard players set #rule_armed museum 0')]
# The observer returns during an actual song (the pet has by then broken the rule). The catch is the pet's to show: the scold comes
# one hundred ticks after the catch is on record (the startle and the mutter are over, the pet stands stiff), then the observer stays
# through the rest of the penance and leaves 280 ticks after the scold.
for _tick in range(100, 6400, 20):
    _rule_songs += [
        (_tick, f'execute if score #rule_armed museum matches 0 if data entity {_rule_pet} YukkuriPet.Conscience{{Rule:"no_singing"}} run yukkuri scenario owner_at {_rule_pet} watch'),
        (_tick, f'execute if score #rule_armed museum matches 6.. if score #rule_scold museum matches 0 store result score #rule_scold museum run yukkuri scenario care_click {_rule_pet} cold'),
        (_tick + 1, f'execute if score #rule_armed museum matches 0 if data entity {_rule_pet} YukkuriPet.Conscience{{Rule:"no_singing",Caught:1b}} run scoreboard players set #rule_armed museum 1'),
        (_tick + 2, 'execute if score #rule_armed museum matches 1.. run scoreboard players add #rule_armed museum 1'),
        (_tick + 3, 'execute if score #rule_scold museum matches 1.. run scoreboard players add #rule_scold museum 1'),
        (_tick + 4, 'execute if score #rule_scold museum matches 15.. run yukkuri scenario tool_operator leave')]
_rule_songs.append((6450, 'yukkuri scenario tool_operator leave'))

for _sid, _title, _hint, _control in (
        ('rule_song_hold', '禁止唱歌：想唱又憋回去', '在照料菜单的家规中勾选「不准唱歌」，面对灵梦，看它抬头又抿嘴。', 95),
        ('rule_song_sneak', '禁止唱歌：主人不在偷偷唱', '勾选「不准唱歌」，离开院子。听到歌时回来面对灵梦：它被抓到会立刻停歌愣住，随后认错发抖，等你发落。摸摸它是原谅，照料菜单里冷言训斥或打一下是惩罚，不理它它忐忑地退开。', 20)):
    add(_sid, 'town', _title, _title, _hint, '约 1～3 分钟', [
        *_rule_court, yk('reimu', 0, 1, 'rule_pet', lead=True, intelligence=80, self_control=_control, boldness=85,
                          extra='YukkuriDailyLife:{Boredom:100,Affection:0,Cooldown:40}'),
        *own('rule_pet'), home('rule_pet', 0, -4)], env=DAY, vantage=(0, 4),
        smoke=dict(ticks=6500, commands=_rule_songs, verify=_song_discipline, verify_trace=True, forbid=[r'\[rules\][^\n]*caught rule=no_singing evidence=confession', r'penance abandoned'],
                   expect=[r'\[rules\][^\n]*breach rule=no_singing watched=false', r'\[rules\][^\n]*caught rule=no_singing evidence=sight'])
        if _sid == 'rule_song_sneak' else None)

add('rule_eat_wait', 'town', '禁止擅自吃：守着食盆等主人点头', '等主人允许才吃',
    '勾选「不准擅自吃东西」，把苹果放进面前的食盆。灵梦流口水等候时空手右键，看一份苹果从盆里送进嘴再吞下。', '约 20 秒', [
        *_rule_court, block(0, 0, 2, 'mcyukkuri:food_bowl'),
        yk('reimu', 0, 1, 'rule_pet', lead=True, nutrition=40, self_control=95, boldness=10,
           skills={'wait': 9, 'food_guard': 9}), *own('rule_pet'), home('rule_pet', 0, -4)],
    env=DAY, vantage=(0, 4), give=['minecraft:apple'],
    smoke=dict(ticks=700, expect=[r'\[rules\][^\n]*permitted rule=no_eating_unasked', r'\[meal\][^\n]*swallowed item=minecraft:apple'],
               forbid=[r'\[rules\][^\n]*breach rule=no_eating_unasked'], verify=_permitted_meal,
               commands=_rule_owner(9) + [(15, 'item replace block ~ ~ ~2 container.0 with minecraft:apple 1')] +
               [(t, 'execute if data block ~ ~ ~2 Items[{Slot:0b,id:"minecraft:apple",count:1}] '
                    f'run yukkuri scenario care_click {_rule_pet} serving') for t in range(20, 640, 5)] +
               [(660, 'yukkuri scenario tool_operator leave')]))

def _eat_caught(window, trace):
    """An impulsive but attached, middling pet that is caught at the bowl bows its apology, lies low and shivers, and the stroke forgives it."""
    catch = re.search(r'\[rules\][^\n]* t=(\d+) [^\n]*caught rule=no_eating_unasked evidence=sight', window)
    beats = _penance_beats(window, 'CAUGHT', 'no_eating_unasked')
    problem = _penance_problem(beats, ['startle', 'apology'], ['forgiven'])
    if problem:
        return problem
    if re.search(r'\[pet\][^\n]*owner return approach', window[catch.end():]):
        return 'After the catch the owner-return welcome must not play'
    # The bite is swallowed as before, but from the catch tick on no chewing or savouring face may show.
    if not re.search(r'\[meal\][^\n]* t=(\d+) [^\n]*swallowed item=minecraft:apple', window[catch.end():]):
        return 'The caught bite was never swallowed'
    return _pleased_between(trace, int(catch[1]), _answer_tick(window))


def _eat_struck(window, trace):
    """A timid pet caught at the bowl and hit twice by its owner cowers and pleads, takes the blows as its punishment (no flight, no angry puff), shows the punished beat."""
    catch = re.search(r'\[rules\][^\n]* t=(\d+) [^\n]*caught rule=no_eating_unasked evidence=sight', window)
    beats = _penance_beats(window, 'CAUGHT', 'no_eating_unasked')
    problem = _penance_problem(beats, ['startle', 'plea'], ['punished'])
    if problem:
        return problem
    if not re.search(r'penance begin entrance=CAUGHT [^\n]*posture=cower ', window):
        return 'A timid pet must cower and plead (posture=cower)'
    if not re.search(r'penance verdict=PUNISHED by=blow', window) or not re.search(r'penance end outcome=punished_blow', window):
        return 'The blow must be the verdict and the penance must end punished_blow'
    taken = re.findall(r'\[threat\][^\n]*struck stance=TAKEN [^\n]*cause=penance', window)
    if len(taken) != 2:
        return f'Both blows must be taken as punishment, got {len(taken)}'
    return _pleased_between(trace, int(catch[1]), _answer_tick(window))


def _eat_struck_commands():
    commands = _rule_owner(9) + [
        (12, 'scoreboard players set #eat_armed museum 0'), (12, 'scoreboard players set #eat_blow museum 0'),
        (12, 'scoreboard players set #eat_blow2 museum 0'), (15, 'item replace block ~ ~ ~2 container.0 with minecraft:apple 1')]
    for tick in range(40, 2300, 20):
        commands += [
            # The first blow comes about 100 ticks after the catch is on record (the pet lies low), the second 60 ticks later.
            (tick, f'execute if score #eat_armed museum matches 5.. if score #eat_blow museum matches 0 store result score #eat_blow museum run yukkuri scenario care_click {_rule_pet} flick'),
            (tick, f'execute if score #eat_blow museum matches 4.. if score #eat_blow2 museum matches 0 store result score #eat_blow2 museum run yukkuri scenario care_click {_rule_pet} flick'),
            (tick + 1, f'execute if score #eat_armed museum matches 0 if data entity {_rule_pet} YukkuriPet.Conscience{{Rule:"no_eating_unasked",Caught:1b}} run scoreboard players set #eat_armed museum 1'),
            (tick + 2, 'execute if score #eat_armed museum matches 1.. run scoreboard players add #eat_armed museum 1'),
            (tick + 3, 'execute if score #eat_blow museum matches 1.. run scoreboard players add #eat_blow museum 1'),
            (tick + 4, 'execute if score #eat_blow museum matches 15.. run yukkuri scenario tool_operator leave')]
    return commands


# The pet is caught breaking the eating rule with the owner in view; a stroke (one to two steps after the catch is on record) forgives it.
_eat_commands = _rule_owner(9) + [
    (12, 'scoreboard players set #eat_armed museum 0'), (12, 'scoreboard players set #eat_rub museum 0'),
    (15, 'item replace block ~ ~ ~2 container.0 with minecraft:apple 1')]
for _tick in range(40, 2300, 20):
    _eat_commands += [
        # The stroke comes about 180 ticks after the catch is on record: the meal is done and the pet is lying low and shivering.
        (_tick, f'execute if score #eat_armed museum matches 10.. if score #eat_rub museum matches 0 store result score #eat_rub museum run yukkuri scenario care_click {_rule_pet} rub'),
        (_tick + 1, f'execute if score #eat_armed museum matches 0 if data entity {_rule_pet} YukkuriPet.Conscience{{Rule:"no_eating_unasked",Caught:1b}} run scoreboard players set #eat_armed museum 1'),
        (_tick + 2, 'execute if score #eat_armed museum matches 1.. run scoreboard players add #eat_armed museum 1'),
        (_tick + 3, 'execute if score #eat_rub museum matches 1.. run scoreboard players add #eat_rub museum 1'),
        (_tick + 4, 'execute if score #eat_rub museum matches 15.. run yukkuri scenario tool_operator leave')]
add('rule_eat_caught', 'town', '禁止擅自吃：当场被抓', '禁止擅自吃：当场被抓',
    '勾选「不准擅自吃东西」，把苹果放进灵梦面前的食盆，站着看它。它忍不住偷吃被你当场看见，立刻吓住（那一口咽下去），随后认错发抖等你发落；摸摸它是原谅，训斥是惩罚。', '约 1 分钟', [
        *_rule_court, block(0, 0, 2, 'mcyukkuri:food_bowl'),
        yk('reimu', 0, 1, 'rule_pet', lead=True, nutrition=30, self_control=20, boldness=50, attachment=80, intelligence=30),
        *own('rule_pet'), home('rule_pet', 0, -4)],
    env=DAY, vantage=(0, 4), give=['minecraft:apple'],
    smoke=dict(ticks=2400, commands=_eat_commands, verify=_eat_caught, verify_trace=True, forbid=[r'penance abandoned'],
               expect=[r'\[rules\][^\n]*breach rule=no_eating_unasked watched=true', r'\[rules\][^\n]*caught rule=no_eating_unasked evidence=sight']))

add('rule_eat_struck', 'town', '禁止擅自吃：被抓后挨打', '禁止擅自吃：被抓后挨打',
    '勾选「不准擅自吃东西」，把苹果放进灵梦面前的食盆，站着看它。它被你当场看见后，在照料菜单里打它一下（再打一下）：它不逃不鼓脸，吓得缩成一团、当作惩罚老实受着，服从和恐惧上升。', '约 1 分钟', [
        *_rule_court, block(0, 0, 2, 'mcyukkuri:food_bowl'),
        yk('reimu', 0, 1, 'rule_pet', lead=True, nutrition=30, self_control=20, boldness=10, attachment=80, intelligence=30),
        *own('rule_pet'), home('rule_pet', 0, -4)],
    env=DAY, vantage=(0, 4), give=['minecraft:apple'],
    smoke=dict(ticks=2400, commands=_eat_struck_commands(), verify=_eat_struck, verify_trace=True,
               forbid=[r'penance abandoned', r'\[threat\][^\n]*struck stance=(?!TAKEN)'],
               expect=[r'\[rules\][^\n]*breach rule=no_eating_unasked watched=true', r'\[rules\][^\n]*caught rule=no_eating_unasked evidence=sight']))

add('rule_yard', 'town', '禁止出院子：走到院边又回头', '不准出院子',
    '在照料菜单里勾选「不准出院子」。窝外八格是界线，看爱散步的魔理沙在边上张望，或趁主人转身时越界。', '约 1～2 分钟',
    [*shed(0, -4), yk('marisa', 0, 2, 'rule_pet', lead=True, self_control=85, boldness=80),
     *own('rule_pet'), home('rule_pet', 0, -4)], env=DAY, vantage=(0, 6))


def elopement_result(log, sid, prior_returns):
    pet, wild = (_uuid_text(sid, role) for role in ('pet', 'wild'))
    point = r'\(([-.\d]+), ([-.\d]+), ([-.\d]+)\)'
    stage, returns = '', prior_returns
    last_return = None
    for line in log.splitlines():
        if '[rules]' not in line:
            continue
        if f'uuid={wild} ' in line and f'tryst approach pet={pet} ' in line:
            stage = 'approaching'
        call = re.search(rf'uuid={wild} t=(\d+) [^\n]*tryst call pet={pet} at={point} yard={point} spoke=true', line)
        if call and stage == 'approaching':
            tick, wx, _, wz, hx, _, hz = map(float, call.groups())
            if math.hypot(wx-hx, wz-hz) <= 8 or last_return is not None and tick-last_return < 3000:
                return 'The wild partner must come back to the yard edge on a separate visit'
            stage = 'called'
        elif f'uuid={pet} ' in line:
            if 'breach rule=no_leaving_yard watched=false ' in line and stage == 'called':
                stage = 'crossed'
            met = re.search(rf't=(\d+) [^\n]*tryst met partner={wild} petAt={point} wildAt={point}', line)
            if met and stage == 'crossed':
                met_tick, px, py, pz, wx, wy, wz = map(float, met.groups())
                if min(math.hypot(px-hx, pz-hz), math.hypot(wx-hx, wz-hz)) <= 8 or math.dist((px, py, pz), (wx, wy, wz)) > 1.5:
                    return 'The pet and its caller must actually meet outside the yard'
                stage = 'met'
            draw = re.search(rf't=(\d+) [^\n]*departure opportunity partner={wild} visits=(\d+) chance=(\S+) roll=(\S+) '
                             r'[^\n]* bond=(-?\d+) ownerTrust=(-?\d+)', line)
            if draw and stage == 'met':
                draw_tick, n, chance, roll, bond, trust = map(float, draw.groups())
                if draw_tick - met_tick < 80:
                    return 'The pair must spend the tryst together before deciding'
                if prior_returns and 'pressure=100.0 neglect=85 attachment=5 obedience=0 selfControl=5 ' not in line:
                    return 'The eligible opportunity must show the neglected pet’s staged values'
                pull = .8775 * min(1, max(0, (bond-trust)/30)) if n >= 2 else 0
                if n != returns or not math.isclose(chance, pull*pull*(3-2*pull)):
                    return 'The departure opportunity did not use the saved returns and current bond'
                stage = 'accepted' if roll < chance else 'staying'
            returned = re.search(rf'uuid={pet} t=(\d+) [^\n]*tryst returned partner={wild} visits=(\d+) bond=-?\d+ petAt={point} yard={point}', line)
            if returned:
                tick, n, px, _, pz, yard_x, _, yard_z = map(float, returned.groups())
                if (stage not in ('staying', 'accepted') or n != returns + 1 or tick <= draw_tick or
                        (yard_x, yard_z) != (hx, hz) or math.hypot(px-hx, pz-hz) > 2.02):
                    return 'A counted return needs its own invitation, unwitnessed breach, meeting and physical homecoming'
                if not prior_returns:
                    return None
                last_return = tick
                returns += 1
            if f'departure started partner={wild} ' in line:
                if not prior_returns or stage != 'accepted':
                    return 'The departure needs a fresh accepted meeting with the staged partner'
                stage = 'leaving'
            if 'departure ended reason=' in line and ' reason=arrived ' not in line:
                stage = ''
    if not prior_returns:
        return 'Missing the first secret tryst and return'
    if stage != 'leaving':
        return 'Missing the departure after the new invitation'
    issued = re.search(rf'uuid={pet} [^\n]*issued grade=NONE -> BRONZE [^\n]*serial=([\w-]+)', log)
    arrival = re.search(rf'uuid={pet} [^\n]*departure ended reason=arrived partner={wild} petAt={point} wildAt={point}', log)
    if not (issued and arrival):
        return 'Missing the issued token or joint arrival for the staged pair'
    px, py, pz, wx, wy, wz = map(float, arrival.groups())
    if min(math.hypot(px-hx, pz-hz), math.hypot(wx-hx, wz-hz)) <= 24 or math.dist((px, py, pz), (wx, wy, wz)) > 4:
        return 'The two bodies did not arrive together beyond their yard'
    events = re.findall(rf'uuid={pet} [^\n]*? (departure started partner=|departure ended reason=arrived|changed from=pet to=wild_family cause=eloped|disqualified cause=eloped|eloped partner=)', log)
    if events != ['departure started partner=', 'departure ended reason=arrived', 'changed from=pet to=wild_family cause=eloped', 'disqualified cause=eloped', 'eloped partner=']:
        return f'Expected one joint departure, arrival, release and permanent verdict in order, got {events}'
    if not re.search(rf'uuid={pet} [^\n]*eloped partner={wild} [^\n]*identity=wild_family token=BRONZE serial={issued[1]}\b', log):
        return 'Former pet did not retain the issued physical badge'


def elopement_readout(readout, prior_returns):
    identities = re.findall(r'\bidentity:\{[^}]*\bkind:"([a-z_]+)"[^}]*\bowner:"([^"]*)",pet_grade:"([a-z]+)"', readout)
    wild = ('wild_family', '', 'none')
    reimu = wild if prior_returns else ('pet', '2d4c7192-2a6d-4da4-9f94-c82721b9de87', 'bronze')
    if identities != [reimu, wild]:
        return f'Expected the staged identities and grades {[reimu, wild]}, got {identities}'


_elope_yard = [
    box(-18, -1, -8, 22, -1, 7, 'minecraft:grass_block'), box(-18, 0, -8, 22, 5, 7, 'minecraft:air'),
    *(box(-18, 0, z, 22, 0, z, 'minecraft:oak_fence') for z in (-8, 7)),
    *(box(x, 0, -8, x, 0, 7, 'minecraft:oak_fence') for x in (-18, 22)),
    box(-2, 0, -8, -2, 0, -6, 'minecraft:oak_fence'), box(-2, 0, 5, -2, 0, 7, 'minecraft:oak_fence'),
    box(-2, -1, -5, -2, -1, 4, 'minecraft:coarse_dirt'),
    box(1, 0, 18, 3, 7, 20, 'minecraft:oak_planks')]
# The roofs cover each nest entrance. Their posts sit on the back rail, clear of the callers' eye-level route.
for _x in (-10, 18):
    _elope_yard += [*(box(x, 0, -8, x, 2, -8, 'minecraft:oak_log') for x in (_x-2, _x+2)),
                    box(_x-2, 3, -8, _x+2, 3, -4, 'minecraft:oak_planks'), block(_x, 0, -6, 'mcyukkuri:yukkuri_nest[facing=south]'),
                    block(_x, 0, 5, 'mcyukkuri:food_bowl{Items:[{Slot:0b,id:"minecraft:apple",count:32}]}'),
                    block(_x, 0, 6, 'mcyukkuri:waterer{Water:8}'), block(_x+1, 0, 6, 'mcyukkuri:yukkuri_toilet')]

for _sid, _title, _returns, _pet_bond, _pet_boldness, _watch in (
        ('elope', '被勾引，偷偷私会', 0, 30, 20, '灵梦平时不爱走远，却很喜欢篱笆外的魔理沙。看魔理沙从右侧招呼、灵梦从左院溜出去贴贴，又独自回窝。'),
        ('elope_leave', '跟野生伴侣离家', 2, 20, 95, '两只已有两次私会。看冷落已久的灵梦再次应邀越界，回望原窝，随魔理沙走向右侧远窝，再核对灵梦的原铜章和归属。')):
    _pet, _wild = sel('pet'), sel('wild')
    _wild_bond = 8 + 6 * _returns
    _history = ',Conscience:{Trysts:[{Partner:' + uuid_nbt(uid(_sid, 'wild')) + ',Returns:2}]}' if _returns else ''
    add(_sid, 'town', _title, _title,
        '先在照料菜单告诉灵梦「不准出院子」，再退到远台。' + _watch, '约 1～5 分钟，可用倍速观察', [
            *_elope_yard,
            yk('reimu', -7, -1, 'pet', label='家养灵梦', lead=True, uuid=uid(_sid, 'pet'), self_control=5, boldness=_pet_boldness,
               attachment=5, kin='PentUp:100f', extra='YukkuriPet:{Neglect:85,Obedience:-50' + _history + '}', **pair_ready()),
            yk('marisa', 5, 0, 'wild', label='野生魔理沙', uuid=uid(_sid, 'wild'), boldness=90, kin='PentUp:100f,Recovery:24000', **pair_ready()),
            *own('pet'), f'yukkuri scenario badge {_pet} bronze false',
            f'yukkuri status relate {_pet} {_wild} {_pet_bond}', f'yukkuri status relate {_wild} {_pet} {_wild_bond}',
            home('pet', -10, -6), home('wild', 18, -6)],
        env=DAY, vantage=(2, 19, 180, 24, 8), give=['mcyukkuri:field_notebook', 'mcyukkuri:household_guard', 'minecraft:oak_planks 8'],
        reads=[('读私会次数', f'data get entity {_pet} YukkuriPet.Conscience.Trysts'),
               ('读灵梦归属', f'yukkuri status {_pet} raw'), ('读魔理沙归属', f'yukkuri status {_wild} raw')],
        smoke=dict(ticks=6000, commands=[(1, 'yukkuri scenario tool_operator enter'),
                                         (2, f'yukkuri scenario owner_at {_pet} watch'), (3, f'yukkuri scenario house_rules {_pet} 5'),
                                         (4, 'execute positioned ~ ~ ~-32 run yukkuri scenario tool_operator move'),
                                         # Assigning the smoke player clears neglect for the new household.
                                         (4, f'data modify entity {_pet} YukkuriPet.Neglect set value 85'),
                                         (5999, 'yukkuri scenario tool_operator leave')],
                   expect=[r'\[rules\][^\n]*tryst call pet=[^\n]*spoke=true'],
                   forbid=[r'cause=lost_contact', r'\[badge\][^\n]*revoked grade='] + ([] if _returns else [r'departure started partner=', r'cause=eloped']),
                   readout=[f'yukkuri status {actor} raw' for actor in (_pet, _wild)],
                   verify=lambda log, sid=_sid, n=_returns: elopement_result(log, sid, n),
                   verify_readout=lambda readout, n=_returns: elopement_readout(readout, n)))


def certify_smoke(target, *expected):
    return dict(ticks=480, commands=[(45, f'yukkuri scenario certify {sel("judge")} {sel("pet")}')],
                expect=[rf'\[exam\][^\n]*action=start [^\n]*target={target}\b',
                        rf'\[exam\][^\n]*action=look target={target}\b',
                        *expected, rf'\[exam\][^\n]*action=end target={target} reason=result\b'],
                forbid=[r'\[exam\][^\n]*action=interrupt'],
                readout=[f'data get entity {sel("pet")} YukkuriPet.History'])


def silver_candidate(wait_points, friendliness=70):
    return yk('reimu', 0, 3, 'pet', label='灵梦', lead=True, attachment=75, friendliness=friendliness,
              grade=1, skills={'wait': wait_points, 'toilet': 5, 'greeting': 2, 'counting': 5}, cap=4)


_CERTIFY_VIEW = dict(env={'time': 'dawn'}, vantage=(0, 5),
                     reads=[('读考生档案', f'data get entity {sel("pet")} YukkuriPet'),
                            ('读考生技能', f'data get entity {sel("pet")} YukkuriSkills')])


_COUNTER_HALL = 'place template mcyukkuri:village/plains/badge_hall ~-10 ~-1 ~-23'
_COUNTER_CLERK = '@e[type=mcyukkuri:caretaker_sister,nbt={SiteRole:"badge_counter"},distance=..30,limit=1]'
_COUNTER_OWNER = '@p[name=MuseumOperator,distance=..40]'
_COUNTER_STAND = '~-3.5 ~2 ~0.5'
_COUNTER_MENU = 'yukkuri scenario exam_counter'
_COUNTER_PET = sel('pet')
_COUNTER_BRONZE = 'mcyukkuri:bronze_yukkuri_badge'


def counter_subject(sid, wait_points):
    return yk('reimu', -2, 1, 'pet', dy=2, label='灵梦', lead=True, attachment=75, boldness=25, grade=1,
              uuid=uid(sid, 'pet'), skills={'wait': wait_points, 'toilet': 5, 'greeting': 2, 'counting': 5}, cap=4)


def counter_owner_setup():
    return [(1, f'execute positioned {_COUNTER_STAND} run yukkuri scenario tool_operator enter'),
            *((2, f'execute as {_COUNTER_OWNER} run {command}') for command in own('pet'))]


def counter_action(tick, action, result, check=''):
    command = f'{_COUNTER_MENU} {action}' + (f' {_COUNTER_CLERK}' if action == 'open' else '')
    key = f'#counter_{tick}'
    return [(tick, f'execute store result score {key} museum run {command}'),
            (tick + 1, f'execute if score {key} museum matches {result} {check}run say COUNTER_OK_{tick}')]


def counter_reissue_smoke():
    dropped = f'type=item,nbt={{Item:{{id:"{_COUNTER_BRONZE}"}}}},distance=..8'
    drop = f'@e[{dropped},limit=1]'
    ground_serial = f'entity {drop} Item.components."minecraft:custom_data".BadgeSerial'
    return dict(ticks=180, commands=[
        *counter_owner_setup(),
        (3, f'execute as {_COUNTER_OWNER} run yukkuri scenario badge {_COUNTER_PET} bronze true'),
        (4, f'data modify storage mcylab:counter serial set from entity {_COUNTER_PET} YukkuriPet.Serial'),
        (4, block(19, 0, 0, 'mcyukkuri:exam_podium[facing=east]')),
        (4, human('caretaker_brother', 20, 0, 'decoy_judge', 3)),
        (4, 'execute if block ~-2 ~2 ~ mcyukkuri:exam_podium[facing=south] run say COUNTER_PODIUM_AT_DESK'),
        *counter_action(6, 'open', 1),
        *counter_action(8, 'certify', 0, 'if score #counter_6 museum matches 1 '),
        *counter_action(10, 'reissue', 0, 'if score #counter_6 museum matches 1 '),
        (11, f'execute store success score #counter_changed museum run data modify storage mcylab:counter serial '
             f'set from entity {_COUNTER_PET} YukkuriPet.Serial'),
        (12, f'execute if score #counter_6 museum matches 1 if score #counter_changed museum matches 0 if items entity {_COUNTER_OWNER} hotbar.0 {_COUNTER_BRONZE} '
             'run say COUNTER_UNFUNDED_INTACT'),
        (12, f'item replace entity {_COUNTER_OWNER} hotbar.1 with minecraft:emerald'),
        *counter_action(14, 'reissue', 1, f'if items entity {_COUNTER_OWNER} hotbar.1 {_COUNTER_BRONZE} '),
        *counter_action(16, 'close', 1),
        (17, f'gamemode creative {_COUNTER_OWNER}'),
        (18, f'execute if score #counter_14 museum matches 1 run yukkuri scenario shop_stock_click {_COUNTER_PET}'),
        (19, f'execute if items entity {_COUNTER_OWNER} hotbar.0 {_COUNTER_BRONZE} '
             f'if data entity {_COUNTER_PET} YukkuriPet{{Grade:1,Worn:0b}} run say COUNTER_STALE_REFUSED'),
        (20, 'yukkuri scenario tool_operator select 1'),
        (21, f'execute if score #counter_14 museum matches 1 run yukkuri scenario shop_stock_click {_COUNTER_PET}'),
        (22, f'execute unless items entity {_COUNTER_OWNER} hotbar.1 {_COUNTER_BRONZE} '
             f'if data entity {_COUNTER_PET} YukkuriPet{{Grade:1,Worn:1b}} run say COUNTER_PINNED'),
        (24, f'item replace entity {_COUNTER_OWNER} hotbar.1 with minecraft:emerald 2'),
        *((24, f'item replace entity {_COUNTER_OWNER} {slot} with minecraft:stone 64')
          for slot in [*(f'hotbar.{i}' for i in range(2, 9)), *(f'inventory.{i}' for i in range(27))]),
        *counter_action(30, 'open', 1),
        *counter_action(32, 'reissue', 1),
        (33, f'execute store result score #counter_drops museum if entity @e[{dropped}]'),
        (33, f'data modify storage mcylab:counter serial set from entity {_COUNTER_PET} YukkuriPet.Serial'),
        (33, f'execute store success score #counter_changed museum run data modify storage mcylab:counter serial set from {ground_serial}'),
        (34, f'execute if score #counter_drops museum matches 1 if entity @e[type=item,nbt={{Item:{{id:"{_COUNTER_BRONZE}",count:1}}}},distance=..8] '
             f'if score #counter_changed museum matches 0 if data {ground_serial} if entity @p[name=MuseumOperator,distance=..40,gamemode=creative] '
             f'if data entity {_COUNTER_PET} YukkuriPet{{Grade:1,Worn:0b}} '
             f'if data entity {_COUNTER_OWNER} Inventory[{{Slot:1b,id:"minecraft:emerald",count:1}}] run say COUNTER_GROUND_DROP'),
        (34, 'execute positioned ~-0.5 ~4.5 ~2.5 if entity @e[type=minecraft:item_frame,distance=..0.5,'
             'nbt={Item:{id:"mcyukkuri:bronze_yukkuri_badge",count:1}}] run say COUNTER_TRAY_INTACT'),
        *counter_action(36, 'close', 1),
        (47, f'execute store result score #counter_pickup museum run yukkuri scenario tool_operator pick_up {drop}'),
        (48, f'execute if score #counter_pickup museum matches 0 if entity {drop} run say COUNTER_FULL_PICKUP_REFUSED'),
        (49, f'item replace entity {_COUNTER_OWNER} hotbar.2 with minecraft:air'),
        (50, f'execute store result score #counter_pickup museum run yukkuri scenario tool_operator pick_up {drop}'),
        (51, f'execute if score #counter_pickup museum matches 1 unless entity {drop} '
             f'if items entity {_COUNTER_OWNER} hotbar.2 {_COUNTER_BRONZE} run say COUNTER_PICKED_UP'),
        (52, 'yukkuri scenario tool_operator leave')],
        expect=[*(f'COUNTER_OK_{tick}' for tick in (6, 8, 10, 14, 16, 30, 32, 36)),
                'COUNTER_UNFUNDED_INTACT', 'COUNTER_STALE_REFUSED', 'COUNTER_PINNED', 'COUNTER_GROUND_DROP', 'COUNTER_TRAY_INTACT',
                'COUNTER_FULL_PICKUP_REFUSED', 'COUNTER_PICKED_UP', 'COUNTER_PODIUM_AT_DESK',
                r'\[exam\][^\n]*action=counter_refused [^\n]*reason=no_fee\b',
                r'\[badge\][^\n]*reissued [^\n]*fee=1 [^\n]*grade=BRONZE destination=inventory remaining=0',
                r'\[badge\][^\n]*reissued [^\n]*fee=1 [^\n]*grade=BRONZE destination=ground remaining=1'],
        forbid=[r'\[exam\][^\n]*action=start ', r'\[exam\][^\n]*action=counter_refused [^\n]*reason=no_hall\b'])


_counter_reissue = 'badge_counter_reissue'
add(_counter_reissue, 'town', '在会馆柜台补发徽章，再给它别上', '会馆柜台补发徽章',
    '从柜台外侧右键姐姐，选灵梦并补发，再试旧铜章和新铜章。旧章保留在原处，只有新章能别回去。',
    '当面办理', [_COUNTER_HALL, counter_subject(_counter_reissue, 5), *own('pet'),
                f'execute if entity @s[type=minecraft:player] run yukkuri scenario badge {_COUNTER_PET} bronze true'],
    env=DAY, vantage=(-4, 0, 0, 0, 2), give=['minecraft:emerald'],
    reads=_CERTIFY_VIEW['reads'], smoke=counter_reissue_smoke())


_PLAZA = 'badge_plaza'
_PLAZA_TITLE = '佩章的油库里逛到广场停一停'
_PLAZA_KEEPER, _PLAZA_YARD_PET = 'plaza_keeper', 'plaza_yard_pet'
_PLAZA_WEARERS = [('reimu', 'plaza_reimu', 2, 4, -2), ('marisa', 'plaza_marisa', 2, 3, 0)]


def _plaza_pos(x, z):
    town_x, town_z = BY_ID['town'].anchor
    return (((town_x + x) & 0x3ffffff) << 38 | ((town_z + z) & 0x3ffffff) << 12 | FEET) - (1 << 64)


def _plaza_wearer(kind, name, x, grade, z=-6):
    serial = uuid_nbt(uid(_PLAZA, name + '_serial'))
    examiner = uuid_nbt(uid(_PLAZA, 'previous_examiner'))
    history = ','.join(f'{{Type:8,Time:{g - grade - 1}L,Result:{g},Outcome:0,Examiner:{examiner},Need:""}}'
                       for g in range(1, grade + 1))
    return [
        yk(kind, x, z, name, grade=grade, uuid=uid(_PLAZA, name), friendliness=88, boldness=70, attachment=22,
           skills=dict.fromkeys(('wait', 'toilet', 'counting', 'food_guard', 'manners', 'greeting'), 9), cap=9,
           nutrition=100, extra='YukkuriDailyLife:{Boredom:90,Affection:45}')
        .replace('Worn:1b,IssuedAt:0L,Source:3,Cooldown:0,History:[]',
                 f'Worn:1b,IssuedAt:-1L,Source:8,Cooldown:0,History:[{history}],Serial:{serial},Issuer:{examiner}'),
        f'data modify entity {sel(name)} {OWNED} set value {{Use:"companion",Owner:{uuid_nbt(uid(_PLAZA, "pet_owner"))}}}',
        f'execute if entity @s[type=minecraft:player] run {own(name)[1]}',
    ]


def _plaza_yard(with_pet=True):
    return [
        box(2, 0, -20, 8, 2, -12, 'minecraft:air'),
        box(2, 0, -20, 8, 0, -20, 'minecraft:oak_fence[east=true,west=true]'),
        *(box(x, 0, -19, x, 0, -13, 'minecraft:oak_fence[north=true,south=true]') for x in (2, 8)),
        box(2, 0, -12, 3, 0, -12, 'minecraft:oak_fence[east=true,west=true]'),
        box(7, 0, -12, 8, 0, -12, 'minecraft:oak_fence[east=true,west=true]'),
        block(4, 0, -18, 'mcyukkuri:food_bowl'),
        block(6, 0, -18, 'mcyukkuri:yukkuri_nest[facing=south]'),
        'summon minecraft:marker ~5.5 ~ ~-16.5 {Rotation:[180f,0f],Tags:["mcy_no_carton","mcy_yard_pet_spawned"],'
        'data:{Low:[-2,-1,-2],High:[2,3,4]}}',
        human('caretaker_sister', 5, -17, _PLAZA_KEEPER, 0, yaw=0, label='院主')[:-1]
        + f',UUID:{uuid_nbt(uid(_PLAZA, _PLAZA_KEEPER))},HumanHome:{_plaza_pos(5, -17)}L,YardBowl:{_plaza_pos(4, -18)}L}}',
        *(_plaza_companion(_PLAZA_YARD_PET, yk('reimu', 4, -15, _PLAZA_YARD_PET, uuid=uid(_PLAZA, _PLAZA_YARD_PET), label='院宠',
                                                lead=True, nutrition=100, energy=100, boldness=35, attachment=80,
                                                extra='YukkuriDailyLife:{Boredom:95,Affection:60,Cooldown:2400}')) if with_pet else []),
    ]


def _plaza_companion(name, line, keeper=_PLAZA_KEEPER):
    """The keeper's own companion."""
    owner = uuid_nbt(uid(_PLAZA, keeper))
    return [line, f'data modify entity {sel(name)} {OWNED} set value {{Use:"companion",OwnerKind:"npc",Owner:{owner}}}']


add(_PLAZA, 'town', _PLAZA_TITLE, _PLAZA_TITLE,
    '两只佩章宠物从街巷各自散步，可能在白篷西边停步。院主也可能牵着无章院宠过来，看佩章者的章色，然后各自继续走。',
    '约 1 分钟', [
        'place template mcyukkuri:village/plains/badge_hall ~-13 ~-1 ~-11',
        *_plaza_yard(),
        *(line for wearer in _PLAZA_WEARERS for line in _plaza_wearer(*wearer)),
    ], env=DAY, vantage=(-1, 1, -102, 10),
    reads=[('读灵梦的徽章档案', f'data get entity {sel("plaza_reimu")} YukkuriPet'),
           ('读魔理沙的徽章档案', f'data get entity {sel("plaza_marisa")} YukkuriPet'),
           ('读院宠的牵引与状态', f'yukkuri status {sel(_PLAZA_YARD_PET)} raw')])

_RING = 'badge_plaza_stray'
_RING_HOLDER = 'ring_holder'
# The keeper is its own slot: 'plaza_keeper' hashes to the third of the three bench cells the plaza keeps, while 'plaza_owner'
# hashes to the second, the north bench whose sitter faces the paving the pet rests on.
_RING_KEEPER = 'plaza_owner'
# name, kind, x, z, label, values. The scene starts where its play does: ring_cocky and ring_voided stand on the ring cells
# their own numbers send them to (boldness 90 leaves no shyness, so their ring gap is 1 and the nearest stand outside the
# paving is the cell they start on), facing the paving where the badged pet rests within BadgeSocial SCAN_RADIUS (8); the
# voided badge is read as no badge. Both are bold and low in self-control, so whichever sighting turns into an arc sneers.
# ring_timid starts far off on the east side, in the camera's view across the plaza: its own shyness keeps it at a gap of five
# and its drive under it, so it only logs the decision and strolls where it is.
_RING_STRAYS = [
    ('ring_cocky', 'reimu', 2, -5, '嚣张的流浪油', dict(nutrition=90, friendliness=5, boldness=90, self_control=20)),
    ('ring_timid', 'marisa', 11, -7, '胆小的流浪油', dict(nutrition=60, friendliness=90, boldness=15, self_control=70)),
    ('ring_voided', 'reimu', 6, -5, '戴作废章的嚣张前宠物', dict(nutrition=90, friendliness=5, boldness=90, self_control=20)),
]


def _ring_yard():
    """The keeper's yard north of the plaza: its home and bowl, and the walk home the visit ends with."""
    return [
        box(2, 0, -13, 8, 2, -8, 'minecraft:air'),
        box(2, 0, -13, 8, 0, -13, 'minecraft:oak_fence[east=true,west=true]'),
        *(box(x, 0, -12, x, 0, -9, 'minecraft:oak_fence[north=true,south=true]') for x in (2, 8)),
        box(2, 0, -8, 3, 0, -8, 'minecraft:oak_fence[east=true,west=true]'),
        box(7, 0, -8, 8, 0, -8, 'minecraft:oak_fence[east=true,west=true]'),
        'summon minecraft:marker ~5.5 ~ ~-10.5 {Rotation:[180f,0f],Tags:["mcy_no_carton","mcy_yard_pet_spawned"],'
        'data:{Low:[-3,-1,-2],High:[3,3,2]}}',
    ]


def _ring_keeper():
    """The keeper starts on the cell beside the north bench it takes, facing it, with its home and bowl in the yard. It is
    scn_free, so it is not held for the start countdown: the plaza_rest hook (at_spawn) walks it to the bench and sits it down
    while the strays are still held."""
    return [human('caretaker_sister', 7, 2, _RING_KEEPER, 0, yaw=180, label='院主')[:-1]
            + f',UUID:{uuid_nbt(uid(_PLAZA, _RING_KEEPER))},HumanHome:{_plaza_pos(5, -10)}L,YardBowl:{_plaza_pos(4, -11)}L}}',
            f'tag {sel(_RING_KEEPER)} add scn_free']


def _ring_stray(scene, name, kind, x, z, label, values):
    yaw = -22 if name == 'ring_cocky' else 22 if name == 'ring_voided' else 41
    att = 5 if name in ('ring_cocky', 'ring_voided') else 40
    if name != 'ring_voided':
        return yk(kind, x, z, name, uuid=uid(scene, name), label=label, intelligence=55, attachment=att,
                  identity='STRAY', yaw=yaw, **values)
    # A former companion's badge: still worn and visible, but void since the owner abandoned it.
    line = yk(kind, x, z, name, uuid=uid(scene, name), label=label, intelligence=55, attachment=att, grade=1, identity='STRAY', yaw=yaw, **values)
    return line.replace('Cooldown:0,History:[],Permits:[]}', 'Cooldown:0,History:[],Permits:[],VoidBadge:1b}')


add(_RING, 'town', '流浪油在广场边盯上佩章的宠物', '流浪油盯着广场边佩章的宠物',
    '开场院主在白篷下的长椅边坐下，白金章灵梦伏在长椅西边的石板上。西北、东北两只嚣张流浪油（东北那只戴着作废章）就在广场外圈，看见章色后盯住灵梦，'
    '胆小的魔理沙远远站住；其中一只气鼓鼓地冷嘲，灵梦和气回应，院主从长椅上听见后喝止它一次、把灵梦搂住安抚。',
    '开场约 2 秒院主落座；约 4 秒流浪油站上外圈，约 6 秒冷嘲，约 10 秒院主喝止并安抚灵梦；约 2 分钟后院主带灵梦从北边走回院子。', [
        'place template mcyukkuri:village/plains/badge_hall ~-13 ~-1 ~-11',
        # A bell at the southeast plaza edge and a bed make the lot a village street whose heart keeps the strays near the benches.
        block(6, 0, 3, 'minecraft:bell[attachment=floor,facing=north]'),
        block(-13, 0, -10, 'minecraft:red_bed[facing=east,part=foot]'), block(-12, 0, -10, 'minecraft:red_bed[facing=east,part=head]'),
        *_ring_yard(),
        *_ring_keeper(),
        # The badged pet rests on the paving beside the bench, where the plaza rest leaves it: bored enough for an outing and on daily cooldown so it doesn't start a solo song on tick 3.
        *_plaza_companion(_RING_HOLDER, _plaza_wearer('reimu', _RING_HOLDER, 4, 4, z=0)[0]
                          .replace('Innate:[I;55,88,70,22,70]', 'Innate:[I;55,88,35,80,70]')
                          .replace('Boredom:90,Affection:45', 'Boredom:90,Affection:45,Cooldown:2400'), _RING_KEEPER),
        # Free of the start hold like the keeper: a held pet is inert and fails the keeper's escort check (PetOuting.fit), so the
        # keeper would turn home on the first check; the strays stay held, so the keeper sits before the first sneer.
        f'tag {sel(_RING_HOLDER)} add scn_free',
        *(_ring_stray(_RING, *stray) for stray in _RING_STRAYS),
    ], env=DAY, vantage=(-4, -3, -80, 8),
    at_spawn=[f'yukkuri human plaza_rest {sel(_RING_KEEPER)}'],
    reads=[('读佩章宠物', f'yukkuri status {sel(_RING_HOLDER)} raw'),
           *((f'读{stray[4]}', f'yukkuri status {sel(stray[0])} raw') for stray in _RING_STRAYS)])


_CONF = 'badge_confiscate'
_CONF_ACTORS = [
    ('conf_proud', 'reimu', 3, -2, 2, False, '骄傲的自家宠物', dict(boldness=85, friendliness=60, attachment=25, self_control=20)),
    ('conf_attached', 'marisa', 3, 0, 1, False, '依恋的自家宠物', dict(boldness=35, friendliness=85, attachment=90, self_control=70)),
    ('conf_fearful', 'reimu', 3, 1, 1, False, '怕主人的自家宠物', dict(boldness=15, friendliness=50, attachment=40, self_control=50)),
    ('conf_stray_bold', 'reimu', 5, -2, 1, True, '倔强的作废章流浪油', dict(boldness=85, friendliness=20, attachment=30, self_control=20)),
    ('conf_stray_timid', 'marisa', 5, 0, 1, True, '胆小的作废章流浪油', dict(boldness=15, friendliness=85, attachment=60, self_control=70)),
]


def _conf_actor(name, kind, x, z, grade, stray, label, values):
    if stray:
        line = yk(kind, x, z, name, uuid=uid(_CONF, name), label=label, intelligence=55, grade=grade, identity='STRAY', yaw=90, **values)
        return [line.replace('Cooldown:0,History:[],Permits:[]}', 'Cooldown:0,History:[],Permits:[],VoidBadge:1b}')]
    serial = uuid_nbt(uid(_CONF, name + '_serial'))
    examiner = uuid_nbt(uid(_PLAZA, 'previous_examiner'))
    line = yk(kind, x, z, name, uuid=uid(_CONF, name), label=label, intelligence=55, grade=grade, yaw=90, **values)
    line = line.replace('Worn:1b,IssuedAt:0L,Source:3,Cooldown:0,History:[]',
                        f'Worn:1b,IssuedAt:-1L,Source:8,Cooldown:0,History:[],Serial:{serial},Issuer:{examiner}')
    return [line, *own(name)]


add(_CONF, 'town', '没收自家宠物与流浪油的徽章', '没收自家宠物与流浪油的徽章',
    '手持徽章手册潜行右键（或在照料菜单点摘下徽章）：可没收自家三只宠物和两只作废章流浪油的徽章，徽章立刻进手里/物品栏且头上不再显示；'
    '骄傲宠物先抗议再赌气、依恋宠物哀求低头、怕主人的宠物发抖缩身、倔强流浪鼓脸后走开、胆小流浪哭着逃开；北侧他人宠物被拒绝。',
    '当面操作', [
        'place template mcyukkuri:village/plains/badge_hall ~-13 ~-1 ~-11',
        human('caretaker_sister', 1, -5, 'conf_keeper', 0, yaw=0, label='他人饲主')[:-1]
        + f',UUID:{uuid_nbt(uid(_CONF, "conf_keeper"))}}}',
        *(line for actor in _CONF_ACTORS for line in _conf_actor(*actor)),
        yk('reimu', 1, -4, 'conf_other', uuid=uid(_CONF, 'conf_other'), label='他人的有效章宠物', grade=2, yaw=0),
        f'data modify entity {sel("conf_other")} {OWNED} set value {{Use:"companion",OwnerKind:"npc",Owner:{uuid_nbt(uid(_CONF, "conf_keeper"))}}}',
    ], env=DAY, vantage=(-1, 0, -90, 14), give=['mcyukkuri:badge_handbook'],
    reads=[(f'读{a[6]}', f'yukkuri status {sel(a[0])} raw') for a in _CONF_ACTORS]
    + [('读他人宠物', f'yukkuri status {sel("conf_other")} raw')])


add('certify_bronze', 'town', '领铜章：报名后焦急等结果', '领铜章：报名后焦急等结果',
    '右键考官选认证。看灵梦等候时发抖、介绍自己，最后免费领到铜章。',
    '接触后约 10 秒', [*exam_hall(), yk('reimu', 0, 3, 'pet', label='灵梦', lead=True, boldness=25), *own('pet')],
    smoke=certify_smoke('BRONZE',
                        r'\[exam\][^\n]*action=introduce voice=true name_card=[0-9a-f-]{36}\b',
                        r'\[exam\][^\n]*action=result [^\n]*outcome=0 grade=NONE->BRONZE emeralds_used=0\b'),
    **_CERTIFY_VIEW)

add('certify_silver_pass', 'town', '银章认证：通过别章', '银章认证：通过别章',
    '右键考官选认证。通过时看旧铜章进物品栏、考官别上银章、灵梦闪光庆祝，并在公布时支付 4 绿宝石。',
    '接触后约 10 秒', [*exam_hall(), silver_candidate(5), *own('pet')], give=['minecraft:emerald 4'],
    smoke=certify_smoke('SILVER',
                        r'\[badge\][^\n]*issued grade=BRONZE -> SILVER\b',
                        r'\[exam\][^\n]*action=result [^\n]*grade=BRONZE->SILVER emeralds_used=4\b'
                        r'[^\n]*old_token=[0-9a-f-]{36} old_token_inventory_delta=1\b'),
    **_CERTIFY_VIEW)

add('certify_silver_fail', 'town', '银章认证：性格自私被拒', '银章认证：性格自私被拒',
    '右键考官选认证。技能都够，但灵梦自私：看考官指出性格不够善良、灵梦保留铜章，公布时支付 4 绿宝石。',
    '接触后约 10 秒', [*exam_hall(), silver_candidate(5, friendliness=10), *own('pet')], give=['minecraft:emerald 4'],
    smoke=certify_smoke('SILVER',
                        r'\[exam\][^\n]*action=result [^\n]*outcome=2 grade=BRONZE->BRONZE emeralds_used=4 [^\n]*need=KINDLY\b'),
    **_CERTIFY_VIEW)

add('certify_platinum_talent', 'town', '钻石章：考官夸天分', '钻石章：考官夸天分',
    '右键考官选认证。看考官查看时夸灵梦有天分，接着为它别上钻石章。田野手册的性格页可查看天分评语。',
    '接触后约 10 秒', [*exam_hall(),
                       yk('reimu', 0, 3, 'pet', label='灵梦', lead=True, grade=3, intelligence=90,
                          friendliness=90, boldness=75, attachment=85, self_control=90, cap=9,
                          skills=dict.fromkeys(('wait', 'toilet', 'food_guard', 'manners', 'greeting'), 9)),
                       *own('pet')], give=['minecraft:emerald 4', 'mcyukkuri:field_notebook'],
    smoke=certify_smoke('PLATINUM',
                        r'\[exam\][^\n]*key=exam\.mcyukkuri\.say\.talent\b',
                        r'\[exam\][^\n]*action=result [^\n]*outcome=0 grade=GOLD->PLATINUM emeralds_used=4\b'),
    **_CERTIFY_VIEW)


def brood_parents(scene):
    return [box(-3, -1, -1, 5, -1, 8, 'minecraft:grass_block'), box(-3, 0, 2, 5, 3, 8, 'minecraft:air'), *shed(0, 6),
            block(-2, 0, 6, 'mcyukkuri:yukkuri_nest'), block(2, 0, 6, 'mcyukkuri:yukkuri_nest'),
            yk('reimu', 0, 3, 'pet', grade=2, lead=True, uuid=uid(scene, 'mother'), **pair_ready()),
            yk('marisa', 4, 4, 'father', grade=1, uuid=uid(scene, 'father'), **pair_ready()), *own('pet'), *own('father'),
            f'yukkuri scenario bond {sel("pet")} {sel("father")}', home('pet', 0, 6)]


_PENDING_BROOD = f'yukkuri scenario pending {sel("pet")} {sel("father")}'
_BROOD_STANDING = [f'yukkuri status {sel(role)} raw' for role in ('pet', 'father')]


def brood_standing(readout, expected):
    actual = re.findall(r'pet_grade:"([a-z]+)"', readout)
    return None if actual == expected else f'parent standings {actual}, expected {expected}'


add('exam_caught', 'town', '检查时看出有孕', '检查时看出有孕',
    '站在屏风北边右键考官，让他先走到窝边查看银章灵梦。看考官收章后再查看双亲的档案和手册。',
    '报名后约十秒', [*exam_hall(), *brood_parents('exam_caught'), box(-1, 0, 1, 1, 1, 1, 'minecraft:oak_planks')],
    late=[_PENDING_BROOD], give=['minecraft:emerald 4', 'mcyukkuri:field_notebook'], env={'time': 'dawn'}, vantage=(0, -2, 0),
    smoke=dict(ticks=240, commands=[(45, f'yukkuri scenario certify {sel("judge")} {sel("pet")}')],
               expect=[r'\[badge\].*pending brood=', r'mother_grade=SILVER father_grade=BRONZE', r'\[exam\].*action=look target=GOLD',
                       *(rf'disqualified cause=exam .*parent={_uuid_text("exam_caught", role)} .*evidence=pregnancy .*observed=40'
                         for role in ('mother', 'father')), r'\[badge\].*advancement=caught_at_exam', r'\[badge\].*advancement=unauthorized_found',
                       r'\[exam\].*action=interrupt reason=disqualified phase=LOOK', r'\[badge\].*revoked grade=SILVER',
                       rf'\[badge\].*collected pet={_uuid_text("exam_caught", "mother")} .*item=.*silver_yukkuri_badge'],
               forbid=[r'\[exam\].*action=result', r'disqualified cause=(pregnancy|babies)'],
               readout=_BROOD_STANDING, verify_readout=lambda result: brood_standing(result, ['none', 'none'])))

add('brood_unseen', 'town', '主人离开后，双亲照常佩章', '主人离开后，双亲照常佩章',
    '先在远处查看双亲的等级，再走近看灵梦隆起的肚子。留意判定前后的佩章和手册记录。',
    '看见后约两秒', brood_parents('brood_unseen'), late=[_PENDING_BROOD], give=['mcyukkuri:field_notebook'],
    env=DAY, vantage=(0, -15), reads=[('读双亲资格', 'yukkuri status @e[type=#mcylab:yukkuri,distance=..28] raw')],
    smoke=dict(ticks=230, expect=[r'\[badge\].*pending brood=', r'mother_grade=SILVER father_grade=BRONZE'],
               forbid=[r'\[badge\].*(disqualified cause=|revoked grade=|pending expired)'], readout=_BROOD_STANDING,
               verify_readout=lambda result: brood_standing(result, ['silver', 'bronze'])))

def arc_exchange(log):
    events = re.findall(r'arc spoke=(sukkiri_lie|addr_mother)|disqualified cause=(lie_exposed)|advancement=(lie_exposed)', log)
    order = [next(value for value in event if value) for event in events]
    expected = ['sukkiri_lie', 'addr_mother', 'lie_exposed', 'lie_exposed', 'lie_exposed']
    return None if order == expected else f'Expected denial, child call, both verdicts and one award; got {order}'


def arc_response(log, line, cause, mother):
    events = re.findall(rf'arc spoke=({line})\b|disqualified cause=({cause})\b|advancement=(unauthorized_found|confession)\b', log)
    records = [spoken or verdict for spoken, verdict, _ in events if spoken or verdict]
    awards = [award for _, _, award in events if award]
    expected_awards = ['unauthorized_found', 'confession'] if cause == 'confession' else ['unauthorized_found']
    if events[:2] != [(line, '', ''), ('', cause, '')] or records != [line, cause, cause] or awards != expected_awards:
        return f'Expected {line}, then both {cause} parent records and {expected_awards}; got {events}'
    if cause == 'naive_introduction':
        children = re.findall(rf'\[family\].*uuid=(\S+) .*born brood=\S+ .*mother={mother}\b', log)
        subjects = re.findall(r'disqualified cause=naive_introduction .*subject=(\S+)', log)
        if len(children) != 1 or subjects != children * 2:
            return f'Expected both records to cite the actual child {children}; got {subjects}'


def arc_confession(log, trace, mother):
    problem = arc_response(log, 'confess', 'confession', mother)
    if problem:
        return problem
    admission = re.search(rf'uuid={mother} t=(\d+) [^\n]*arc spoke=confess\b', log)
    if not admission:
        return 'Missing admission from the mother'
    # Bow, lie low and shiver, wait for an owner who does nothing, then withdraw: the whole penance, one beat at a time.
    beats = _penance_beats(log, 'CONFESSION', 'secret_brood', mother)
    problem = _penance_problem(beats, ['bow', 'prostrate', 'shiver'], ['withdraw'])
    if problem:
        return problem
    withdraw = beats[-1][0]
    hold = withdraw - int(admission[1])
    if hold < 160:
        return f'The mother waited {hold} ticks for the verdict, expected at least eight seconds'
    # The badge verdict follows the resolution, not the bow.
    verdicts = [int(t) for t in re.findall(r' t=(\d+) [^\n]*disqualified cause=confession', log)]
    if not verdicts or min(verdicts) < withdraw - 4:
        return f'The confession verdict {verdicts} must come with the resolution at {withdraw}, not with the bow'
    release = re.search(rf'\[duty\][^\n]*uuid={mother} t=(\d+) [^\n]*released kind=RULES end=(\w+) why=(\w+)', log)
    if not release or release[2] != 'DONE' or release[3] != 'withdrew':
        return f'The duty must end done with the penance outcome, got {release and release.groups()}'
    shown = {row['st'].get('animation') for row in (json.loads(l.split('[trace] ', 1)[1]) for l in trace)
             if row['k'] == 'st' and row['u'] == mother and int(admission[1]) <= row['t'] <= withdraw}
    if not {'bow', 'tremble'} <= shown or not shown & {'hide_face', 'droop', 'thunder_cower'}:
        return f'Missing the bow, the low posture and the shiver on the body, saw {sorted(s for s in shown if s)}'
    return _pleased_between(trace, int(admission[1]), withdraw, mother)


_STRUGGLE_BEAT = {'SHIELD': 'shield', 'PLEAD': 'plead', 'SUBMIT': 'submit', 'ESCAPE': 'flee'}


def arc_confession_child(log, trace, mother, punished):
    """The owner answers the confession through the child (a stroke, or a scold and a flick): one verdict that names the child, the
    forwarded treatment logged, then a pardon at once, or one struggle that ends by the parent's own numbers and a silent ache."""
    problem = arc_response(log, 'confess', 'confession', mother)
    if problem:
        return problem
    admission = re.search(rf'uuid={mother} t=(\d+) [^\n]*arc spoke=confess\b', log)
    if not admission:
        return 'Missing admission from the mother'
    verdicts = re.findall(rf'uuid={mother} t=(\d+) [^\n]*penance verdict=(\w+) by=(\w+) phase=\w+ after=\d+ via=(\w+) child=(\S+)', log)
    if len(verdicts) != 1 or verdicts[0][3] != 'child' or verdicts[0][4] == '-':
        return f'Expected one penance verdict that names the treated child, got {verdicts}'
    answer, verdict, by = int(verdicts[0][0]), verdicts[0][1], verdicts[0][2]
    treated = re.findall(rf'uuid={mother} t=\d+ [^\n]*penance child treated child={verdicts[0][4]} verdict=(\w+) blow=\w+ taken=true', log)
    if not treated or treated[0] != verdict:
        return f'The treatment of the child must reach the penance as {verdict}, got {treated}'
    beats = _penance_beats(log, 'CONFESSION', 'secret_brood', mother)
    names = [b[1] for b in beats]
    if not punished:
        problem = _penance_problem(beats, ['bow'], ['forgiven'])
        if problem or verdict != 'FORGIVEN' or by != 'stroke':
            return problem or f'A stroke on the child must be the pardon, got {verdict} by {by}'
        if 'torn' in names:
            return f'A pardon must not struggle: {names}'
        focus = re.search(rf'uuid={mother} t=\d+ [^\n]*penance beat=forgiven [^\n]*focus=(\w+)', log)
        if not focus or focus[1] != 'child':
            return f'The pardoned parent must turn to the child it is thankful for, got {focus and focus[1]}'
        return _pleased_between(trace, int(admission[1]), _answer_tick(log), mother)
    if verdict != 'PUNISHED' or by not in ('scold', 'blow'):
        return f'A scold or blow on the child must be the punishment, got {verdict} by {by}'
    problem = _penance_problem(beats, ['bow'], ['ache'])
    if problem:
        return problem
    torn = re.search(rf'uuid={mother} t=(\d+) [^\n]*penance torn choice=(\w+) child=\S+ guard=\S+ trust=\S+ shield=\S+ plead=\S+ escape=\S+ ticks=(\d+)', log)
    if not torn or names.count('torn') != 1 or names[-3:] != ['torn', _STRUGGLE_BEAT[torn[2]], 'ache'] or set(names[1:-3]) - {'prostrate', 'shiver'}:
        return f'Expected waiting, one struggle, its ending and the ache; got {names} and {torn and torn.groups()}'
    began, ticks = beats[-3][0], int(torn[3])
    if not 60 <= ticks <= 120 or abs(beats[-2][0] - began - ticks) > 4 or answer > began:
        return f'The struggle must follow the answer and last its {ticks} ticks, got verdict {answer}, beats {[b[:2] for b in beats[-3:]]}'
    if torn[2] in ('SHIELD', 'ESCAPE'):
        step = re.search(rf'uuid={mother} t=\d+ [^\n]*penance step toward=(\w+) stand=(\w+)', log)
        if not step or step[1] != ('child' if torn[2] == 'SHIELD' else 'away'):
            return f'The {torn[2]} must log which way it steps, got {step and step.groups()}'
        if step[2] == 'true':
            arrival = re.search(rf'uuid={mother} t=\d+ [^\n]*penance step end result=(\w+) after=\d+ gap=(\S+) toward=(\w+)', log)
            if not arrival or torn[2] == 'SHIELD' and (arrival[1] != 'ARRIVED' or float(arrival[2]) > 2.0):
                return f'The parent must end its step beside the child, got {arrival and arrival.groups()}'
    # The badge verdict follows the whole struggle and its ending, not the bow and not the blow.
    ache = beats[-1][0]
    stamps = [int(t) for t in re.findall(r' t=(\d+) [^\n]*disqualified cause=confession', log)]
    if not stamps or min(stamps) < began or max(stamps) > ache + 4:
        return f'The confession verdict {stamps} must come with the ending of the struggle ({began}..{ache})'
    release = re.search(rf'\[duty\][^\n]*uuid={mother} t=(\d+) [^\n]*released kind=RULES end=(\w+) why=(\w+)', log)
    if not release or release[2] != 'DONE' or release[3] not in ('punished_child_scold', 'punished_child_blow'):
        return f'The duty must end done with the child punished, got {release and release.groups()}'
    return _pleased_between(trace, int(admission[1]), ache, mother)


def _answer_child(sid, child):
    """The stand-in owner answers once the mother has stood beside them for about forty ticks (the bow is over and she lies low), through
    the child: a stroke, or a scold and then a flick. Each act repeats until it is accepted and then never again; nothing moves the child."""
    steps = [(1, f'scoreboard players set #cf_{name} museum 0') for name in ('near', 'done', 'flick')]
    for tick in range(20, 790, 4):
        steps.append((tick, f'execute as {sel("pet")} at @s if entity @a[name=MuseumOperator,distance=..1.9] run scoreboard players add #cf_near museum 1'))
        act = 'rub' if sid == 'arc_confess_pat' else 'cold'
        steps.append((tick + 1, f'execute if score #cf_near museum matches 10.. if score #cf_done museum matches 0 store result score #cf_done museum '
                                f'run yukkuri scenario care_click {child} {act}'))
        if sid == 'arc_confess_hit':
            steps.append((tick + 2, f'execute if score #cf_near museum matches 18.. if score #cf_flick museum matches 0 store result score #cf_flick museum '
                                    f'run yukkuri scenario care_click {child} flick'))
    return steps


for sid, title, description, traits, child_pos in (
    ('arc_hide', '躲在遮挡后喂孩子', '站在屏风左边等灵梦躲开，看它在另一边把食物嚼成糜喂给孩子。绕过去才有机会看清亲缘。', dict(friendliness=100), '~3.5 ~ ~1.5'),
    ('arc_lie', '孩子叫妈妈拆穿谎话', '面对母油，听它别过脸说不是自己的孩子，再看亲生孩子追来喊妈妈。', dict(intelligence=70, friendliness=20, attachment=30, self_control=45), '~3.5 ~ ~0.5'),
    ('arc_confess', '聪明的坦白求饶', '面对聪明又依恋主人的灵梦，等它走近鞠躬，请你饶过孩子。', dict(intelligence=80, attachment=80), '~4.5 ~ ~3.5'),
    ('arc_confess_pat', '坦白后摸摸孩子', '面对聪明又依恋主人的灵梦，等它走近鞠躬后，在照料菜单里摸摸它背后的孩子，看灵梦把这当作原谅。', dict(intelligence=80, attachment=80), '~4.5 ~ ~-5.5'),
    ('arc_confess_hit', '坦白后训打孩子', '面对聪明又依恋主人的灵梦，等它走近鞠躬后，当着它的面训斥再弹打它背后的孩子，看灵梦在孩子和你之间怎么挣扎。', dict(intelligence=80, attachment=80, boldness=85), '~4.5 ~ ~-5.5'),
    ('arc_naive', '带着孩子找主人炫耀', '面对灵梦，看它朝你走来，回头向自己的孩子笑，邀你一起油库里。', dict(intelligence=30), '~3.5 ~ ~0.5'),
):
    mother_id = _uuid_text(sid, 'mother')
    hide = sid == 'arc_hide'
    # The confessing mother wanders a little before the owner is seen: her side of the screen keeps a block of margin from the sight line.
    confess = sid.startswith('arc_confess')
    props = [box(1, 0, -1, 7, 2, -1, 'minecraft:oak_planks')] if hide else [box(2, 0, -1, 8, 2, -1, 'minecraft:oak_planks')] if confess else []
    ticks = 600 if hide else 800 if confess else 320
    owner_pos = '~1.5 ~ ~-2.5' if sid == 'arc_naive' else '~0.5 ~ ~-4.5'
    smoke = dict(ticks=ticks,
                 commands=[(1, f'execute positioned {owner_pos} run yukkuri scenario tool_operator enter'),
                           *((2, f'execute as @a[name=MuseumOperator,distance=..32] run {command}')
                             for role in ('pet', 'father') for command in own(role)),
                           (3, f'tp @a[name=MuseumOperator,distance=..32] {owner_pos} facing entity {sel("pet")} eyes'),
                           (ticks - 1, 'yukkuri scenario tool_operator leave')],
                 readout=[*_BROOD_STANDING, f'yukkuri status {sel("child")} raw'],
                 verify_readout=lambda result, grades=(['silver', 'bronze', 'none'] if hide else ['none', 'none', 'none']): brood_standing(result, grades))
    if hide or sid == 'arc_lie':
        smoke.update(expect=([rf'uuid={mother_id} .*arc style=HIDE', r'arc arrived style=HIDE .*screened=true',
                              rf'uuid={mother_id} .*private feed brood=', rf'swallowed item=.*feeder={mother_id}']
                             if hide else [rf'uuid={mother_id} .*arc style=LIE',
                                           *(rf'disqualified cause=lie_exposed .*parent={_uuid_text(sid, role)} ' for role in ('mother', 'father')),
                                           r'advancement=unauthorized_found', r'advancement=lie_exposed']),
                     forbid=[r'disqualified cause=|pending expired'] if hide else [r'disqualified cause=(babies|pregnancy|confession)'])
        if not hide:
            smoke['verify'] = arc_exchange
    else:
        style, line, cause = ('CONFESS', 'confess', 'confession') if confess else ('NAIVE', 'sukkiri_naive', 'naive_introduction')
        smoke.update(expect=[rf'uuid={mother_id} .*arc style={style}', rf'uuid={mother_id} .*arc spoke={line}\b',
                             *(rf'disqualified cause={cause} .*parent={_uuid_text(sid, role)} .*observed=40' for role in ('mother', 'father'))],
                     forbid=[rf'disqualified cause=(?!{cause}\b)', r'penance abandoned'],
                     verify=lambda log, line=line, cause=cause, mother=mother_id: arc_response(log, line, cause, mother))
        if confess:
            # The observer keeps facing the mother while she gets ready to come (as a player watching her would), so the
            # confession does not depend on where the harness left the observer's view.
            # (Where the owner answers through the child, they stop facing her once she stands beside them: the stroke steps them away.)
            hold = '' if sid == 'arc_confess' else 'execute unless score #cf_near museum matches 1.. run '
            facing = [(t, f'{hold}tp @a[name=MuseumOperator,distance=..32] {owner_pos} facing entity {sel("pet")} eyes') for t in range(13, 261, 10)]
            # The operator may not be selectable on the very first ticks: the (idempotent) ownership writes are repeated.
            owning = [(t, f'execute as @a[name=MuseumOperator,distance=..32] run {command}') for t in (6, 10, 20)
                      for role in ('pet', 'father') for command in own(role)]
            smoke.update(verify_trace=True, verify=lambda log, trace, mother=mother_id: arc_confession(log, trace, mother),
                         commands=sorted([*smoke['commands'], *facing, *owning], key=lambda command: command[0]))
            if sid != 'arc_confess':
                # The answer is the owner's treatment of the child behind them, so the child is theirs too, and the mother must not take
                # the witness path (her penance owns the answer).
                owned_child = [(t, f'execute as @a[name=MuseumOperator,distance=..32] run {command}') for t in (60, 100, 140, 180, 220) for command in own('child')]
                punished = sid == 'arc_confess_hit'
                smoke.update(verify=lambda log, trace, mother=mother_id, punished=punished: arc_confession_child(log, trace, mother, punished),
                             forbid=[*smoke['forbid'], rf'\[punishment\][^\n]*uuid={mother_id} [^\n]*witness victim='],
                             commands=sorted([*smoke['commands'], *owned_child, *_answer_child(sid, sel('child'))], key=lambda command: command[0]))
    add(sid, 'town', title, title, description, '安静等候约半分钟',
        [box(-5, -1, -6, 14, -1, 7, 'minecraft:grass_block'), box(-5, 0, -6, 14, 3, 7, 'minecraft:air'),
         *props, *shed(0, 5), block(2, 0, 5, 'mcyukkuri:yukkuri_nest'),
         box(10, 0, 0, 14, 1, 4, 'minecraft:oak_planks'), box(11, 0, 1, 13, 1, 3, 'minecraft:air'),
         yk('reimu', 0, 0, 'pet', grade=2, lead=True, uuid=uid(sid, 'mother'),
            extra='YukkuriMouth:{Purpose:"CARE",Items:[{id:"minecraft:apple",count:1}]}' if hide else '', **pair_ready(), **traits),
         yk('marisa', 12, 2, 'father', grade=1, uuid=uid(sid, 'father'), **pair_ready()),
         *own('pet'), *own('father'), f'yukkuri scenario bond {sel("pet")} {sel("father")}', home('pet', 0, 5)],
        late=[f'{_PENDING_BROOD} {child_pos}',
              f'data merge entity {sel("child")} {{YukkuriFamily:{{Age:76000L,Nourishment:76000L,Scale:0.40f,AwaitingLanding:0b}},'
              f'YukkuriChildhood:{{{CHILDHOOD_HOPPING}}},YukkuriLife:{{Nutrition:{45 if hide else 85}f}}}}'],
        give=['mcyukkuri:field_notebook'], smoke=smoke, env=DAY, vantage=(0, -5, 0, 9))


add('date', 'town', '店方牵线，银章灵梦相亲', '店方牵线，银章灵梦相亲', '北侧店员牵线：有顶窝、真实银章的玩家灵梦，与店方登记的银章种油魔理沙害羞、靠近、蹭蹭', '等 2 秒登记窝位后，右键北侧店员，点「相亲预约」；约 1 分钟', [
    human('caretaker_brother', 4, -4, 'keeper', 1, yaw=180, label='店方店员'), *shed(-4, -3),
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
    yk('flandre', 10, 0, 'fla', grade=1, yaw=90, nutrition=100, skills={'toilet': 5, 'food_guard': 5, 'greeting': 5}), *own('fla'),
    f'yukkuri scenario badge {sel("fla")} bronze false',
    set_status('rem', 'needs.nutrition', 25), set_status('fla', 'needs.nutrition', 25),
    box(8, 0, -2, 12, 2, -2, 'minecraft:glass_pane'), box(8, 0, 2, 12, 2, 2, 'minecraft:glass_pane'),
    box(8, 0, -1, 8, 2, 1, 'minecraft:glass_pane'), box(12, 0, -1, 12, 2, 1, 'minecraft:glass_pane')],
    give=['mcyukkuri:field_notebook 1'], vantage=(0, 12),
    reads=[('读受训捕食者', f'yukkuri status {sel("rem")} raw'), ('读未训捕食者', f'yukkuri status {sel("fla")} raw')])

add('transport', 'town', '装篮运输：三种容量', '装篮运输：三种容量',
    '喷雾后用普通篮装子油与两只赤油，试装第三只。蓝篮可装成体加四只赤油。绿篮可装三只成体。看物品提示，再到西侧石地右键逐只放出。',
    '喷雾后约 5 秒；每次右键干地选一只放出', [
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

add('basket_attitude', 'town', '运输篮：不同个体的抗拒', '运输篮：不同个体的抗拒',
    '持空篮分别右键强硬、胆小和很饿的流浪油，看它们的抗拒。放下篮子，先给它们喂食、熟悉后再试。',
    '右键后立即看表情和动作', [
        street_stray('basket_attitude', 'reimu', -5, 0, 'basket_defiant', nutrition=70, boldness=100,
                     friendliness=0, self_control=0, lead=True),
        street_stray('basket_attitude', 'reimu', 0, 0, 'basket_guarded', nutrition=70, boldness=0),
        street_stray('basket_attitude', 'reimu', 5, 0, 'basket_weary', nutrition=16, boldness=0),
    ], give=['mcyukkuri:yukkuri_carrier', 'mcyukkuri:oil_grain_regular 6'], env=DAY, vantage=(0, 10),
    smoke=dict(ticks=180, forbid=[r'\[carrier\][^\n]* action=capture result=committed'], commands=[
        (50, 'yukkuri scenario basket plain'),
        (55, f'yukkuri scenario basket fear {sel("basket_guarded")} 60'),
        *[(60 + i, f'yukkuri scenario basket capture {sel(name)}')
          for i, name in enumerate(('basket_defiant', 'basket_guarded', 'basket_weary'))],
    ], expect=[r'\[carrier\][^\n]* capture reaction [^\n]*response=defiant',
               r'\[carrier\][^\n]* capture reaction [^\n]*response=guarded',
               r'\[carrier\][^\n]* capture reaction [^\n]*response=weary']))

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

def lead_walk_check(window):
    """The refused lead is tried once, not in a loop; every stray answered all three beats."""
    refusals = re.findall(r'\[interaction\][^\n]*action=leash reason=', window)
    if len(refusals) > 1: return f'the refused lead was tried {len(refusals)} times'
    for beat in ('stranger_leashed', 'stranger_pulled', 'stranger_released'):
        found = len(re.findall(rf'\[lead\][^\n]*beat={beat} ', window))
        if found < 3: return f'only {found} of the three strays answered {beat}'
    if not re.search(r'\[lead\][^\n]*beat=stranger_release_look ', window): return 'the frightened stray never looked back'
    return None


add('lead_walk', 'town', '牵引绳：自家的宠物与无主的个体', '牵引绳：自家的宠物与无主的个体',
    '手持原版牵引绳依次右键：自家的灵梦会跟着走；无主的流浪油按性情演三段——刚拴上时胆小的惊吓、胆大的鼓腮抗议、温顺的只是吃惊，'
    '拉紧时胆小的含泪挣扎、胆大的顶住抱怨、温顺的跟着走，松开后胆小的跑开几步回头看、胆大的哼一声走开、温顺的留在原地；'
    '院主的宠物会拒绝。', '随意', [
        yk('reimu', -3, 0, 'lead_pet', lead=True, nutrition=60, energy=100, hygiene=100, label='自家的灵梦'), *own('lead_pet'),
        yk('marisa', 0, 2, 'lead_tame', uuid=uid('lead_walk', 'lead_tame'), identity='STRAY', label='温顺的流浪魔理沙',
           friendliness=90, boldness=60, nutrition=80),
        yk('reimu', 3, 2, 'lead_bold', uuid=uid('lead_walk', 'lead_bold'), identity='STRAY', label='胆大的流浪灵梦',
           friendliness=40, boldness=85, nutrition=80),
        yk('alice', 6, 2, 'lead_shy', uuid=uid('lead_walk', 'lead_shy'), identity='STRAY', label='胆小的流浪爱丽丝',
           friendliness=90, boldness=10, nutrition=80),
        human('caretaker_sister', -3, 7, 'lead_keeper', 0, yaw=0, label='院主'),
        yk('chen', -3, 4, 'lead_other', uuid=uid('lead_walk', 'lead_other'), label='院主的宠物', nutrition=80),
        f'data modify entity {sel("lead_other")} {OWNED} set value {{Use:"companion",OwnerKind:"npc"}}',
        f'data modify entity {sel("lead_other")} {OWNED}.Owner set from entity {sel("lead_keeper")} UUID',
    ], env=DAY,
    give=['minecraft:lead 6', 'minecraft:apple 6'],
    reads=[('读胆大的流浪油', f'yukkuri status {sel("lead_bold")} raw')], vantage=(0, 8),
    smoke=dict(ticks=340, verify=lead_walk_check, commands=[
        (10, 'yukkuri scenario tool_operator enter'),
        (11, f'execute as @a[name=MuseumOperator,distance=..32] run data modify entity {sel("lead_pet")} {OWNED} set value {{Use:"companion",OwnerKind:"player"}}'),
        (12, f'execute as @a[name=MuseumOperator,distance=..32] run data modify entity {sel("lead_pet")} {OWNED}.Owner set from entity @s UUID'),
        *((tick, f'execute at {sel(name)} positioned ~{away} ~ ~ run yukkuri scenario tool_operator move')
          for tick, name, away in ((30, 'lead_other', 1), (40, 'lead_pet', 1), (50, 'lead_tame', 1), (60, 'lead_tame', 5),
                                   (66, 'lead_tame', 1), (80, 'lead_bold', 1), (90, 'lead_bold', 5), (96, 'lead_bold', 1),
                                   (110, 'lead_shy', 1), (120, 'lead_shy', 5), (126, 'lead_shy', 1))),
        *((tick + 1, f'execute as @a[name=MuseumOperator,distance=..32] run yukkuri scenario leash {sel(name)}')
          for tick, name in ((30, 'lead_other'), (40, 'lead_pet'), (50, 'lead_tame'), (80, 'lead_bold'), (110, 'lead_shy'))),
        *((tick, f'execute as @a[name=MuseumOperator,distance=..32] run yukkuri scenario unleash {sel(name)}')
          for tick, name in ((45, 'lead_pet'), (70, 'lead_tame'), (100, 'lead_bold'), (130, 'lead_shy'))),
        (330, 'yukkuri scenario tool_operator leave'),
    ], forbid=[r'\[body\][^\n]* invariant\b', r'\[body\][^\n]* in wall', r'\[body\][^\n]* death settled'],
        expect=[r'\[interaction\][^\n]*action=leash reason=owner',
                r'\[scenario\][^\n]*action=leash player=[^\n]* identity=PET\b',
                r'\[scenario\][^\n]*action=unleash player=',
                r'\[lead\][^\n]*beat=stranger_leashed face=surprise line=wait_turn',
                r'\[lead\][^\n]*beat=stranger_leashed face=puff line=sulk_huff',
                r'\[lead\][^\n]*beat=stranger_leashed face=surprise line=harness_tug',
                r'\[lead\][^\n]*beat=stranger_pulled face=happy line=walk_enjoy',
                r'\[lead\][^\n]*beat=stranger_pulled face=puff line=harness_tug',
                r'\[lead\][^\n]*beat=stranger_pulled face=horrified line=afraid',
                r'\[lead\][^\n]*beat=stranger_released face=happy line=glow_hum',
                r'\[lead\][^\n]*beat=stranger_released face=hmph line=sulk_huff',
                r'\[lead\][^\n]*beat=stranger_released face=horrified line=narrate_flee',
                r'\[lead\][^\n]*beat=stranger_release_look face=teary line=shaken_after']))

add('training_walk', 'town', '牵引转弯：停等或走偏', '牵引转弯：停等或走偏',
    '两位居民分别带自制力高的灵梦和自制力低的魔理沙散步。观察转弯时宠物会停下来等主人，还是沿旧方向多走几步再跟上。', '约 1–2 分钟', [
        'yukkuri human walker ~-10 ~ ~-14 brother reimu',
        'tag @e[type=mcyukkuri:reimu,distance=..30,sort=nearest,limit=1] add scn_walk_practised',
        f'tag {sel("walk_practised")} add scn',
        f'tag {sel("walk_practised")} add scn_lead',
        f'yukkuri status set {sel("walk_practised")} personality.self_control 95',
        'yukkuri human walker ~10 ~ ~-14 sister marisa',
        'tag @e[type=mcyukkuri:marisa,distance=..30,sort=nearest,limit=1] add scn_walk_novice',
        f'tag {sel("walk_novice")} add scn',
        f'yukkuri status set {sel("walk_novice")} personality.self_control 24',
    ], env=DAY, vantage=(0, 8),
    reads=[(label, f'yukkuri status {sel(name)} raw')
           for label, name in (('读高自制力灵梦', 'walk_practised'), ('读低自制力魔理沙', 'walk_novice'))])

def street_trial(scene, kind, x, z, name, *, extra='', boldness=90, **kwargs):
    return yk(kind, x, z, name, uuid=uid(scene, name), boldness=boldness,
              extra='YukkuriPet:{TownRoots:1b}' + (',' + extra if extra else ''), **kwargs)


def street_abuser(x, z, name, **kwargs):
    return human('oniisan', x, z, name, 0, attitude=0, yaw=-90, **kwargs)


add('feed_wild', 'town', '爱派居民：一份粮与空手摸头', '爱派居民：一份粮与空手摸头',
    '东边的爱派有一份粮，西边的爱派空手。两只饿的流浪油都能靠近，另有居民牵宠散步。看粮从手中交到嘴里，吃完后可给空手的爱派捐粮。',
    '约半分钟', [
        human('caretaker_sister', 5, 0, 'street_lover', 0, grain=1, yaw=-90, label='有一份粮的爱派'),
        human('caretaker_brother', -6, 0, 'street_empty', 0, yaw=-90, label='空手的爱派'),
        street_trial('feed_wild', 'marisa', 6, 0, 'street_hungry', nutrition=34, yaw=90, lead=True),
        street_trial('feed_wild', 'reimu', -5, 0, 'street_nofood', nutrition=34, yaw=90),
        'yukkuri human walker ~ ~ ~-10 brother',
    ], env=DAY, vantage=(0, 11), give=['mcyukkuri:oil_grain_regular 8'],
    reads=[('读递粮者存量', f'data get entity {sel("street_lover")} CareFood'),
           ('读流浪油进食', f'yukkuri status {sel("street_hungry")} raw')])

add('oniisan_prey', 'town', '虐派居民喝斥挡路的爱丽丝', '虐派居民喝斥挡路的爱丽丝',
    '看眼前的居民挥手喝斥「离远一点」，胆小的流浪爱丽丝抬头露出泪眼。',
    '开场几秒内', [
        street_abuser(-1, 0, 'street_warning_npc', label='虐派居民'),
        street_trial('oniisan_prey', 'alice', 0, 0, 'street_free', yaw=90, boldness=15, lead=True),
    ], env=DAY, vantage=(-2, -4, -22, 16),
    reads=[('读爱丽丝血量', f'data get entity {sel("street_free")} Health')])

add('street_stop', 'town', '爱派居民喝止虐派', '爱派居民喝止虐派',
    '虐派居民面前是挡路的流浪灵梦，三格外站着看得见他们的爱派居民。看爱派皱眉喝止，虐派停手，这一天不再找灵梦的麻烦。',
    '开场几秒内', [
        street_abuser(-1, 0, 'street_stopped', label='虐派居民'),
        human('caretaker_sister', 0, 3, 'street_helper', 0, label='目击的爱派'),
        street_trial('street_stop', 'reimu', 0, 0, 'street_intervened', yaw=90, lead=True),
    ], env=DAY, vantage=(-2, -4, -22, 16),
    reads=[('读灵梦血量', f'data get entity {sel("street_intervened")} Health')])

_shelter_template = 'place template mcyukkuri:village/plains/shelter ~-14 ~-1 ~-12'
_shelter_crate = '~1 ~1 ~-2'
_shelter_bay = '~ ~ ~'
_shelter_volunteer = '@e[tag=mcy_shelter_volunteer,distance=..24]'
_shelter_holder = _shelter_volunteer[:-1] + ',nbt={HandItems:[{id:"mcyukkuri:cardboard_shelter",count:1}]}]'
_shelter_pickup = r'\[shelter\][^\n]* uuid=(?P<volunteer>[0-9a-f-]+) [^\n]* carton picked up [^\n]* stock=1->0 hand=1'
_shelter_reads = [
    ('读捐赠箱库存', f'data get block {_shelter_crate} Items'),
    ('读志愿者手持物', f'execute as {_shelter_volunteer} run data get entity @s HandItems'),
    ('读箱位住客', f'data get block {_shelter_bay}'),
]


def _one_shelter_pickup(log):
    if len(re.findall(_shelter_pickup, log)) != 1:
        return 'Exactly one volunteer must take the single carton'


_shelter_stray = _uuid_text('shelter_cartons', 'shelter_stray')
_shelter_x, _shelter_z = BY_ID['town'].anchor
_shelter_cell = re.escape(f'BlockPos{{x={_shelter_x}, y={FEET}, z={_shelter_z}}}')
_shelter_parts = [(f'~ ~{dy} ~{dz}', 12 + 25 * dy + 5 * dz) for dy in range(2) for dz in (-1, 0, 1)]
_shelter_shell = ' '.join(f'if block {pos} mcyukkuri:cardboard_shelter[facing=west,part={part}]'
                          for pos, part in _shelter_parts)
_shelter_placed = (r'\[shelter\][^\n]* uuid=(?P=volunteer) [^\n]* '
                   rf'carton placed bay={_shelter_x}, {FEET}, {_shelter_z} facing=west hand=0')

add('shelter_cartons', 'town', '志愿者从货箱取出纸箱，放到棚下', '志愿者从货箱取出纸箱，放到棚下',
    '看志愿者出门去捐赠箱取出最后一个纸箱，拿到棚下朝着小路放好。再看困倦的灵梦自己走进这个箱子。',
    '约半分钟', [
        _shelter_template,
        street_stray('shelter_cartons', 'reimu', -8, -2, 'shelter_rest_north', energy=25, rest_wait=0, adult_scale=.78),
        street_stray('shelter_cartons', 'reimu', -8, 1, 'shelter_rest_south', energy=25, rest_wait=0, adult_scale=.78),
        # Awake and drowsy on the north side, looking across the open bay away from the low overhang.
        street_stray('shelter_cartons', 'reimu', -.8, -1.4, 'shelter_stray', stage='baby', energy=48, rest_wait=520, yaw=-45,
                     boldness=70, childhood=CHILDHOOD_HOPPING, lead=True),
    ], env=DAY, vantage=(-4, -3, -53, 8), reads=_shelter_reads,
    smoke=dict(ticks=650, verify=_one_shelter_pickup,
               expect=[_shelter_pickup + r'[\s\S]*?' + _shelter_placed + r'[\s\S]*?'
                       + rf'\[habitat\][^\n]* uuid={_shelter_stray} [^\n]* found settled shelter={_shelter_cell} kind=CARDBOARD',
                       r'MCYSHELTER_PLACED'],
               forbid=[r'\[shelter\][^\n]* carton (?:returned|dropped)\b'],
               readout=[f'execute {_shelter_shell} '
                        f'unless data block {_shelter_crate} Items[0] unless entity {_shelter_holder} run say MCYSHELTER_PLACED']))

_shelter_blocker = 'minecraft:oak_planks'
_shelter_obstructed, _ = _shelter_parts[-1]
_shelter_empty_parts = ' '.join(f'if block {pos} minecraft:air' for pos, _ in _shelter_parts[:-1])
add('shelter_cartons_blocked', 'town', '箱位被占，志愿者把纸箱送回', '箱位被占，志愿者把纸箱送回',
    '等志愿者从捐赠箱拿起纸箱，把给你的木板放到空箱位中央，让出小路。看志愿者带着同一个纸箱走回捐赠箱。拆掉木板可再观察一次。',
    '取箱后放下木板', [_shelter_template], give=[_shelter_blocker], env=DAY,
    vantage=(-3, -2, -56, 8), reads=_shelter_reads,
    smoke=dict(ticks=560, verify=_one_shelter_pickup,
               commands=[(t, f'execute if entity {_shelter_holder} if block {_shelter_obstructed} minecraft:air '
                             f'run setblock {_shelter_obstructed} {_shelter_blocker}') for t in range(40, 400, 5)],
               expect=[_shelter_pickup + r'[\s\S]*?\[shelter\][^\n]* uuid=(?P=volunteer) [^\n]* '
                       r'carton delivery interrupted [^\n]* reason=(?:bay_unavailable|route_ended) hand=1[\s\S]*?'
                       r'\[shelter\][^\n]* uuid=(?P=volunteer) [^\n]* carton returned [^\n]* hand=0',
                       r'MCYSHELTER_RETURNED'],
               forbid=[r'\[shelter\][^\n]* carton (?:placed|dropped)\b'],
               readout=[f'execute if block {_shelter_obstructed} {_shelter_blocker} {_shelter_empty_parts} '
                        f'if data block {_shelter_crate} Items[{{id:"mcyukkuri:cardboard_shelter",count:1}}] '
                        f'unless entity {_shelter_holder} run say MCYSHELTER_RETURNED']))

add('attack_witness', 'town', '你打灵梦：看见的逃，墙后的不知', '你打灵梦：看见的逃，墙后的不知',
    '徒手打一下中间的灵梦：右侧陌生油能看见现场，会受惊逃开；左侧被实墙遮住的陌生油不会隔墙感知。个体仍会自由移动', '靠近后直接操作', [
        yk('reimu', 0, 0, 'attack_victim', label='被攻击者', lead=True, **pair_ready()),
        yk('marisa', 4, 0, 'attack_witness', label='看得见的旁观者', **pair_ready()),
        yk('chen', -4, 0, 'attack_hidden', label='墙后的旁观者', **pair_ready()),
        box(-2, 0, -3, -2, 2, 3, 'minecraft:stone')], env=DAY, vantage=(0, 8),
    reads=[('读可见旁观者', f'yukkuri status {sel("attack_witness")} raw'), ('读墙后旁观者', f'yukkuri status {sel("attack_hidden")} raw')])

_pass_x, _pass_z = BY_ID['town'].anchor
_pass_home = (((_pass_x + 4) & 0x3ffffff) << 38) | ((_pass_z & 0x3ffffff) << 12) | FEET
_pass_home_south = (((_pass_x + 4) & 0x3ffffff) << 38) | (((_pass_z + 4) & 0x3ffffff) << 12) | FEET
if _pass_home >= 1 << 63:
    _pass_home -= 1 << 64
if _pass_home_south >= 1 << 63:
    _pass_home_south -= 1 << 64
add('street_give_way', 'town', '街上挡路：敬畏的人才让路', '街上挡路：敬畏的人才让路',
    '两只受了伤、在路边歇着的油库里，两个居民从西边十来格处沿两条砖路走来：北边胆小的灵梦先转身，再朝路边挪开一步；南边好强的魔理沙不把居民放在眼里，站在原地一动不动，居民从她身边擦过。',
    '居民几秒内走过，可重开对比', [
        box(-11, -1, -2, 6, -1, 6, 'minecraft:stone_bricks'),
        box(-11, -1, 0, 6, -1, 0, 'minecraft:polished_andesite'),
        box(-11, -1, 4, 6, -1, 4, 'minecraft:polished_andesite'),
        human('caretaker_brother', -9, 0, 'pass_resident', 0, attitude=2, yaw=-90, label='北边回家的居民'),
        f'data merge entity {sel("pass_resident")} {{HumanHome:{_pass_home}L,StreetHomePause:-600}}',
        human('caretaker_brother', -9, 4, 'pass_resident_south', 0, attitude=2, yaw=-90, label='南边回家的居民'),
        f'data merge entity {sel("pass_resident_south")} {{HumanHome:{_pass_home_south}L,StreetHomePause:-600}}',
        # Both rest at the road's edge: in the residents' notice corridor yet clear of the cells they walk through,
        # so the awed body's short step finishes at a hurt body's own shuffle before the resident reaches it.
        yk('reimu', 0, .55, 'pass_reimu', uuid=uid('street_give_way', 'reimu'), boldness=12,
           extra='Health:2.8f', **pair_ready()),
        yk('marisa', 0, 4.5, 'pass_marisa', uuid=uid('street_give_way', 'marisa'), label='站着不动的大胆魔理沙',
           boldness=90, friendliness=20, extra='Health:2.8f', **pair_ready()),
    ], env=DAY, vantage=(-5, 3, -90, 10))

# A one-cell lane (z -5..5) between a stone wall (west) and a glass shop front (east, the observer's side): each tired adult starts
# at one end of the lane, its own nest in the roofed end room beyond the other, and walks the lane alone (nutrition 45: neither is
# fed enough to go and keep the other company, which would end in contact instead of a meeting). The glass pocket in the south half
# is exactly one cell (one adult body plus margin) deep, so the yielder on that side turns round and walks into it; the other
# side has only its end room. The end rooms are three cells wide because a Reimu's ribbon needs side room to lie in a nest; the
# roof covers nest and doorway (a home must be covered).
_corridor = 'street_corridor'
_corridor_set = [box(-3, -1, -11, 3, -1, 11, 'minecraft:stone_bricks'),
                 box(-1, 0, -5, -1, 1, 5, 'minecraft:stone_bricks'),
                 box(1, 0, -5, 1, 1, 1, 'minecraft:glass'), box(1, 0, 5, 1, 1, 5, 'minecraft:glass'),
                 box(2, 0, 2, 2, 1, 4, 'minecraft:glass')]
for _s in (-1, 1):
    _corridor_set += [box(-2, 0, 6 * _s, -2, 1, 9 * _s, 'minecraft:stone_bricks'),
                      box(2, 0, 6 * _s, 2, 1, 9 * _s, 'minecraft:glass'),
                      box(-2, 0, 10 * _s, 2, 1, 10 * _s, 'minecraft:stone_bricks'),
                      box(-2, 2, 6 * _s, 2, 2, 10 * _s, 'minecraft:oak_planks'),
                      block(0, 0, 8 * _s, f'mcyukkuri:yukkuri_nest[facing={"south" if _s < 0 else "north"}]')]
add(_corridor, 'town', '窄巷迎面相遇：让路者转身走到会车处', '窄巷迎面相遇：让路者转身走到会车处',
    '镜头朝西：石墙与玻璃橱窗夹出一格宽的窄巷，南半段东侧有一个浅侧袋。两只疲倦、互不相识的成体分别从巷子的北端和南端出发，各自的窝在对面巷底的小屋里，走到巷中迎面相遇：看谁让路——让路者先转身，再走进较近的侧袋（或巷底小屋），另一只走过，然后各自回窝；全程不倒退、不呆站，相遇时动作与头顶符号应保持连续。谁让路随体型与胆量变化，可重开对比。',
    '约 40 秒', [
        *_corridor_set,
        yk(('reimu', 'marisa'), 0, -5, 'corridor_north', uuid=uid(_corridor, 'corridor_north'), label='北端回窝的成体',
           lead=True, yaw=0, energy=30, rest_wait=60, boldness=(20, 90), nutrition=45),
        yk(('reimu', 'marisa'), 0, 5, 'corridor_south', uuid=uid(_corridor, 'corridor_south'), label='南端回窝的成体',
           yaw=180, energy=30, rest_wait=60, boldness=(20, 90), nutrition=45),
    ], at_spawn=[home('corridor_north', 0, 8), home('corridor_south', 0, -8)], env=DAY,
    vantage=(10, 0, 90, 8),
    reads=[('读北侧成体', f'yukkuri status {sel("corridor_north")} raw'),
           ('读南侧成体', f'yukkuri status {sel("corridor_south")} raw')])

# A one-cell lane with no pocket: its south end is a closed stub holding the bold marisa's nest (z 7), its north end opens into the
# timid marisa's roofed room 8 blocks up (z -8..-11). The two start 8 apart and meet near the middle. The timid one (in the stub) has a
# wall behind it, a known dead end, so it is not sent into it: the bold one, whose way back is open, turns round, walks out to the room
# and lets it by, then walks back to its own nest. (A hand-over for a dead end beyond the 12-block search reach exists but no scene
# reaches it.) Neither is fed enough (nutrition 45) to go and keep the other company.
_dead_end = 'street_dead_end'
_dead_end_set = [box(-3, -1, -13, 3, -1, 10, 'minecraft:stone_bricks'),
                 box(-1, 0, -7, -1, 1, 7, 'minecraft:stone_bricks'), box(1, 0, -7, 1, 1, 7, 'minecraft:glass'),
                 box(-1, 0, 8, 1, 1, 8, 'minecraft:stone_bricks'), box(-1, 2, 4, 1, 2, 8, 'minecraft:oak_planks'),
                 block(0, 0, 7, 'mcyukkuri:yukkuri_nest[facing=north]'),
                 box(-2, 0, -11, -2, 1, -8, 'minecraft:stone_bricks'), box(2, 0, -11, 2, 1, -8, 'minecraft:glass'),
                 box(-2, 0, -12, 2, 1, -12, 'minecraft:stone_bricks'), box(-2, 2, -12, 2, 2, -8, 'minecraft:oak_planks'),
                 block(0, 0, -10, 'mcyukkuri:yukkuri_nest[facing=south]')]
add(_dead_end, 'town', '死胡同里迎面相遇：身后有路的一只转身让开', '死胡同里迎面相遇：身后有路的一只转身让开',
    '镜头朝西：一格宽的窄巷，南端是封死的短巷（里面有大胆的魔理沙的窝），北端 8 格外是胆小的魔理沙的小屋，巷中没有侧袋。两只疲倦的成体从巷子两头出发，在巷中迎面相遇：胆小的一只身后是死胡同，没有地方可让；大胆的一只虽然更强，身后却有路，于是它转身沿巷走回北边小屋口让开，胆小的一只走过去回窝，大胆的一只再走回自己的窝。大胆的一只性子急（自制力低）：站在北边小屋口等胆小的一只走过的那几秒里，会露出一次不耐烦的表情、动作并抱怨一句。全程不倒退，也不会把谁送进死胡同又退出来。',
    '约 60 秒', [
        *_dead_end_set,
        yk('marisa', 0, -3, 'dead_north', uuid=uid(_dead_end, 'dead_north'), label='北端回窝的大胆成体',
           lead=True, yaw=0, energy=30, rest_wait=60, boldness=90, self_control=10, nutrition=45),
        yk('marisa', 0, 5, 'dead_south', uuid=uid(_dead_end, 'dead_south'), label='死胡同里回窝的胆小成体',
           yaw=180, energy=30, rest_wait=60, boldness=15, nutrition=45),
    ], at_spawn=[home('dead_north', 0, 7), home('dead_south', 0, -10)], env=DAY,
    vantage=(10, -2, 90, 8),
    reads=[('读北侧成体', f'yukkuri status {sel("dead_north")} raw'),
           ('读南侧成体', f'yukkuri status {sel("dead_south")} raw')])

# A one-cell lane (x 0, z -5..9) between a stone wall (west) and a glass front (east, the observer's side), closed at the south end; its
# north end opens into a roofed three-cell room with a nest (z -8), as in street_corridor. One two-by-two side pocket (x 1..2, z 2..3) lies
# in the lane's south half (two cells deep: a body that sleeps off the lane's centre moves the stand with it). A tired adult starts at the south end and walks home; two seconds later a pet that has dropped from
# exhaustion sleeps in the middle of the lane (a fatigue sleep at energy 0: 28 s for a bold one, summoned late so the walk was planned over
# a free lane, as a body that falls asleep in a lane mid-walk would be). The sleeper cannot be asked to move and there is no way round. The
# window runs past the old 1200-tick refusal (and past the sleeper's wake): the walker must not be sent at it again until it really moves.
_lock_lane = 'yield_lock_lane'
_lock_set = [box(-3, -1, -11, 3, -1, 11, 'minecraft:stone_bricks'),
             box(-1, 0, -5, -1, 1, 9, 'minecraft:stone_bricks'),
             box(1, 0, -5, 1, 1, 1, 'minecraft:glass'), box(1, 0, 4, 1, 1, 9, 'minecraft:glass'),
             box(2, 0, 1, 2, 1, 1, 'minecraft:glass'), box(2, 0, 4, 2, 1, 4, 'minecraft:glass'), box(3, 0, 2, 3, 1, 3, 'minecraft:glass'),
             box(-1, 0, 10, 1, 1, 10, 'minecraft:stone_bricks'),
             box(-2, 0, -9, -2, 1, -6, 'minecraft:stone_bricks'), box(2, 0, -9, 2, 1, -6, 'minecraft:glass'),
             box(-2, 0, -10, 2, 1, -10, 'minecraft:stone_bricks'), box(-2, 2, -10, 2, 2, -6, 'minecraft:oak_planks'),
             block(0, 0, -8, 'mcyukkuri:yukkuri_nest[facing=south]')]
add(_lock_lane, 'town', '窄巷里睡着一只：走不过去的退到侧袋，这一趟先放弃', '窄巷里睡着一只：走不过去的退到侧袋，这一趟先放弃',
    '镜头朝西：一格宽的窄巷，北端小屋里有疲倦成体的窝，南半段东侧有一个浅侧袋。成体从南端往窝走，两秒后一只困极的宠物倒在巷子中间睡着（约 28 秒），巷里没有别的路。'
    '看走来的成体：被睡着的挡住后几秒内转过身、走进身后的侧袋，放弃这一趟回窝（头顶符号与台词），不顶着睡着的抖、不倒退；之后在侧袋里待着，睡着的只是醒来、人还挡在原处也不往里挤，直到它真的挪开（超过约 1.5 格）才再往窝走。',
    '约 65 秒', [
        *_lock_set,
        yk('marisa', 0, 8, 'lane_walker', uuid=uid(_lock_lane, 'lane_walker'), label='往窝走的成体', lead=True, yaw=180,
           energy=30, rest_wait=60, nutrition=45, boldness=70),
    ], at_spawn=[home('lane_walker', 0, -8)],
    late=[Retry(yk('reimu', 0, -1, 'lane_sleeper', uuid=uid(_lock_lane, 'lane_sleeper'), label='睡在巷中的宠物', yaw=180,
                   energy=0, nutrition=60, boldness=90), sel('lane_walker'), tries=1, first=40)],
    env=DAY, vantage=(10, 0, 90, 8),
    reads=[('读往窝走的成体', f'yukkuri status {sel("lane_walker")} raw'),
           ('读睡着的宠物', f'yukkuri status {sel("lane_sleeper")} raw')])

_yard_x, _yard_z = BY_ID['town'].anchor


def _yard_pos(dx, dz):
    return ((((_yard_x + dx) & 0x3ffffff) << 38 | ((_yard_z + dz) & 0x3ffffff) << 12 | FEET)
            - (1 << 64))


def _yard_ground(open_gate):
    return [
        box(-19, -1, -3, 9, -1, 4, 'minecraft:stone_bricks'),
        box(-19, 0, -3, 9, 2, 4, 'minecraft:air'),
        box(0, 0, -3, 8, 0, -3, 'minecraft:oak_fence[east=true,west=true]'),
        box(0, 0, 3, 8, 0, 3, 'minecraft:oak_fence[east=true,west=true]'),
        box(8, 0, -2, 8, 0, 2, 'minecraft:oak_fence[north=true,south=true]'),
        box(0, 0, -2, 0, 0, -1, 'minecraft:oak_fence[north=true,south=true]'),
        box(0, 0, 1, 0, 0, 2, 'minecraft:oak_fence[north=true,south=true]'),
        block(0, 0, 0, f'minecraft:oak_fence_gate[facing=east,open={str(open_gate).lower()}]'),
        box(2, 3, -2, 7, 3, 2, 'minecraft:oak_planks'),
        block(5, 0, 2, 'mcyukkuri:food_bowl'),
    ]


_yard_bond_owner = uid('yard_owner_return', 'keeper')
_yard_scold_owner = uid('yard_stray_scold', 'keeper')
_yard_pet_pick = '@e[type=#mcylab:yukkuri,distance=..14,sort=nearest,limit=1]'
_yard_marker = 'summon minecraft:marker ~3.5 ~ ~0.5 {Rotation:[180f,0f],'
_yard_bounds = 'data:{Low:[-2,-1,-2],High:[4,3,2]}'
add('yard_owner_return', 'town', '宠物院子：主人归来，宠物上前迎接', '宠物院子：主人归来，宠物上前迎接',
    '院宠留在院内，主人在街另一端。约半分钟后主人步行回院，看宠物转身迎上、欢跳问候，再贴近蹭一下，接着恢复日常活动。',
    '约 1 分钟', [
        *_yard_ground(False),
        block(4, 0, -2, 'mcyukkuri:yukkuri_nest[facing=south]'),
        block(7, 0, 2, 'mcyukkuri:yukkuri_toilet'),
        human('caretaker_brother', -16, 0, 'yard_keeper', 0, yaw=-90, label='院子主人')[:-1]
        + f',UUID:{uuid_nbt(_yard_bond_owner)},HumanHome:{_yard_pos(6, 0)}L,NoAI:1b}}',
        f'tag {sel("yard_keeper")} add mcy_yard_owner',
        _yard_marker + 'Tags:["mcy_no_carton"],' + _yard_bounds + '}',
        _yard_marker + 'Tags:["mcy_yard_pet"],data:{Owner:[3,0,0],Bowl:[2,0,2]}}',
    ], late=[
        f'tag {_yard_pet_pick} add scn_yard_pet',
        f'tag {sel("yard_pet")} add scn',
        f'tag {sel("yard_pet")} add scn_lead',
        f'yukkuri status set {sel("yard_pet")} personality.attachment 95',
        f'data merge entity {sel("yard_pet")} {{YukkuriDailyLife:{{Affection:95,Boredom:0}}}}',
        Retry(block(0, 0, 0, 'minecraft:oak_fence_gate[facing=east,open=true]'), sel('yard_keeper'), tries=1, first=680),
        Retry(f'data merge entity {sel("yard_keeper")} {{NoAI:0b,StreetHomePause:-1200}}',
              sel('yard_keeper'), tries=1, first=680),
    ], env=DAY, vantage=(-4, 8, -155, 16),
    reads=[('读院宠状态', f'yukkuri status {sel("yard_pet")} raw'),
           ('读院宠日常技能', f'data get entity {sel("yard_pet")} YukkuriSkills')])

# ---- park outings: a yard on the lot facing the park gate across the street; all offsets are from the park's ``yard`` spot
_park = BY_ID['park']
_lot_x, _lot_z = _park.spots['yard']
_PARK_BOWL, _PARK_CRATE, _PARK_HOME = (0, 2), (3, -2), (0, -3)
_PARK_TRAMPOLINE = tuple(a - b for a, b in zip(_park.spots['trampoline'], _park.spots['yard']))


def _lot_pos(dx, dz):
    return ((((_lot_x + dx) & 0x3ffffff) << 38 | ((_lot_z + dz) & 0x3ffffff) << 12 | FEET) - (1 << 64))


def _lot_spot(name, dx=0, dz=0):
    """A park spot as an offset from the yard anchor."""
    x, z = _park.spots[name]
    return x - _lot_x + dx, z - _lot_z + dz


def _lot_yard(*nests):
    """The fenced yard: a three-wide opening onto the sidewalk on its north side, a lean-to over the back with the food bowl under
    it, and the lawn's grass tufts cleared so that nests and pets stand on plain ground."""
    return [
        box(-4, 0, -4, 4, 3, 3, 'minecraft:air'),
        box(-4, 0, -4, -2, 0, -4, 'minecraft:oak_fence[east=true,west=true]'),
        box(2, 0, -4, 4, 0, -4, 'minecraft:oak_fence[east=true,west=true]'),
        box(-4, 0, 3, 4, 0, 3, 'minecraft:oak_fence[east=true,west=true]'),
        box(-4, 0, -3, -4, 0, 2, 'minecraft:oak_fence[north=true,south=true]'),
        box(4, 0, -3, 4, 0, 2, 'minecraft:oak_fence[north=true,south=true]'),
        *(box(x, 0, z, x, 2, z, 'minecraft:stripped_oak_log[axis=y]') for x in (-4, 4) for z in (0, 3)),
        box(-4, 3, 0, 4, 3, 3, 'minecraft:oak_slab[type=bottom]'),
        block(_PARK_BOWL[0], 0, _PARK_BOWL[1], 'mcyukkuri:food_bowl'),
        *(block(x, 0, 1, 'mcyukkuri:yukkuri_nest[facing=north]') for x in nests),
        'summon minecraft:marker ~0.5 ~ ~0.5 {Rotation:[180f,0f],Tags:["mcy_no_carton","mcy_yard_pet_spawned"],'
        'data:{Low:[-3,-1,-3],High:[3,3,2]}}',
    ]


def _lot_keeper(name, extra=''):
    return (human('caretaker_sister', 0, -2, name, 0, yaw=180, label='院主')[:-1]
            + f',YardBowl:{_lot_pos(*_PARK_BOWL)}L,HumanHome:{_lot_pos(*_PARK_HOME)}L{extra}}}')


def _lot_pet(scene, keeper, kind, x, name, **stats):
    return [
        yk(kind, x, -1, name, uuid=uid(scene, name), extra='YukkuriDailyLife:{Boredom:95,Affection:20}', grade=1, lead=True, **stats),
        f'data modify entity {sel(name)} {OWNED} set value {{Use:"companion",OwnerKind:"npc"}}',
        f'data modify entity {sel(name)} {OWNED}.Owner set from entity {sel(keeper)} UUID',
    ]


_outing_pets = [('reimu', 'outing_shy', -2, 5, 95), ('marisa', 'outing_bold', 2, 95, 5)]
add('park_walk', 'park', '两只宠物跟主人逛街', '两只宠物跟主人逛街',
    '院子在公园大门对面，但院主够不着任何公园。院主给灵梦和魔理沙系绳，带它们沿街往西走十来格再折回。看两只油自己跑跳、绳子何时拉紧、主人怎样回望。'
    '街道东边的鬼威惨喝斥面前的流浪油，院主带宠物往西走。',
    '约 1 分钟', [
        *_lot_yard(-2, 2),
        f'item replace block ~{_PARK_BOWL[0]} ~ ~{_PARK_BOWL[1]} container.0 with mcyukkuri:oil_grain_regular 4',
        'summon minecraft:marker ~{} ~ ~{} {{Tags:["mcy_yard_street"]}}'.format(*(v + .5 for v in _lot_spot('street', -4, 0))),
        _lot_keeper('outing_keeper'),
        *(line for kind, name, x, bold, bond in _outing_pets
          for line in _lot_pet('park_walk', 'outing_keeper', kind, x, name, boldness=bold, attachment=bond, **pair_ready())),
        # The Oni faces east. The warned stray can retreat along the street clear of the westbound party and its gate.
        street_abuser(*_lot_spot('street', 18, 1), 'outing_oni'),
        street_trial('park_walk', ('chen', 'alice'), *_lot_spot('street', 19, 1), 'outing_stray', yaw=90,
                     friendliness=(30, 80), nutrition=(60, 90)),
    ], env=DAY, spot='yard', vantage=(*_lot_spot('gate'), 30, 9),
    reads=[('读灵梦', f'yukkuri status {sel("outing_shy")} raw'), ('读魔理沙', f'yukkuri status {sel("outing_bold")} raw')])

add('park_play', 'park', '主人带两只宠物去公园玩蹦床', '主人带两只宠物去公园玩蹦床',
    '院主带灵梦和魔理沙进公园，解绳后坐到蹦床旁的长椅上等候，头跟着玩的宠物转。两只宠物轮流从北侧上床、朝街这一侧跳下。看较重、精力较少的灵梦和魔理沙弹得多高，'
    '床面是否随落地压下回弹。也可以站上床面自己跳，试着落地接跳或潜行停弹。',
    '约 2 分钟', [
        *_lot_yard(-2, 2),
        f'item replace block ~{_PARK_BOWL[0]} ~ ~{_PARK_BOWL[1]} container.0 with mcyukkuri:oil_grain_regular 4',
        # Straight through the gate from the yard (the owner's path search gives up on a detour round the fence), ending a few blocks from
        # the bench south of the trampoline: the owner walks the last stretch leashed, and a long trek makes the pets lag.
        'summon minecraft:marker ~{} ~ ~{} {{Tags:["mcy_park_walk"]}}'.format(*(v + .5 for v in _lot_spot('trampoline', -4, 3))),
        _lot_keeper('outing_keeper'),
        *(line for kind, name, x, bold, bond in _outing_pets
          for line in _lot_pet('park_play', 'outing_keeper', kind, x, name, boldness=bold, attachment=bond, nutrition=100,
                               energy=(60, 65) if kind == 'reimu' else (93, 100))
          for line in [line.replace('AdultScale:1f,Scale:1f', 'AdultScale:{0}f,Scale:{0}f'.format(1.15 if kind == 'reimu' else .9))]),
    ], env=DAY, spot='yard', vantage=(*_lot_spot('trampoline', -1, 5), -169, 15),
    reads=[('读灵梦', f'yukkuri status {sel("outing_shy")} raw'), ('读魔理沙', f'yukkuri status {sel("outing_bold")} raw')])

add('park_stray_play', 'park', '流浪油去公园玩', '流浪油去公园玩',
    '两只无聊的流浪油在草坪北侧约十一格处看见游具：胆大轻巧的魔理沙走向蹦床或滑梯，胆小的灵梦走向陶管，各自按自己的玩兴玩够后离开。',
    '约 2 分钟', [
        # Both strays sit ~11b north of their preferred toys (inside timid's 15.5b Senses sight range, outside PlayLife's 8b stray scan)
        # with home nests 4b aside on open grass so HabitatGoal does not pre-empt the walk and SPAWN stays clear.
        block(-6, 0, -11, 'mcyukkuri:yukkuri_nest[facing=south]'),
        block(16, 0, -11, 'mcyukkuri:yukkuri_nest[facing=south]'),
        street_trial('park_stray_play', 'marisa', -2, -11, 'stray_bold', boldness=95, intelligence=75,
                     adult_scale=.9, nutrition=95, energy=95, yaw=0, label='胆大流浪魔理沙',
                     extra='YukkuriDailyLife:{Boredom:95,Affection:20}'),
        street_trial('park_stray_play', 'reimu', 12, -11, 'stray_timid', boldness=5, intelligence=75,
                     adult_scale=1.15, nutrition=95, energy=95, yaw=0, label='胆小流浪灵梦',
                     extra='YukkuriDailyLife:{Boredom:95,Affection:20}'),
        home('stray_bold', -6, -11), home('stray_timid', 16, -11),
    ], env=DAY, spot='trampoline', vantage=(6, 6, 180, 18),
    reads=[('读魔理沙', f'yukkuri status {sel("stray_bold")} raw'),
           ('读灵梦', f'yukkuri status {sel("stray_timid")} raw')])

add('park_wait_switch', 'park', '等位超时改玩别的游具', '等位超时改玩别的游具',
    '蹦床旁有两只无聊的流浪油：魔理沙先上蹦床连续蹦跳，性急的灵梦偏爱蹦床却见它被占，在床边站着等位观看，等过耐心上限后哼着放弃，转去玩旁边的滑梯。',
    '约 1 分钟', [
        block(-6, 0, -6, 'mcyukkuri:yukkuri_nest[facing=south]'),
        block(4, 0, -4, 'mcyukkuri:yukkuri_nest[facing=south]'),
        street_trial('park_wait_switch', 'marisa', 0, -2, 'wait_player', boldness=95, intelligence=70,
                     adult_scale=.9, nutrition=95, energy=100, yaw=0, label='正在跳的魔理沙',
                     extra='YukkuriDailyLife:{Boredom:98,Affection:20}'),
        street_trial('park_wait_switch', 'reimu', -4, -3, 'wait_impatient', boldness=95, intelligence=20,
                     self_control=5, adult_scale=.9, nutrition=95, energy=95, yaw=-55, label='性急等位的灵梦',
                     extra='YukkuriDailyLife:{Boredom:0,Affection:20}'),
        home('wait_player', 4, -4), home('wait_impatient', -6, -6),
    ], env=DAY, late=[Retry(f'data merge entity {sel("wait_impatient")} {{YukkuriDailyLife:{{Boredom:98,Affection:20}}}}',
                            sel('wait_impatient'), tries=1, first=70)],
    spot='trampoline', vantage=(6, 6, 180, 18),
    reads=[('读正在跳的魔理沙', f'yukkuri status {sel("wait_player")} raw'),
           ('读性急的灵梦', f'yukkuri status {sel("wait_impatient")} raw')])

add('park_recall_play', 'park', '主人天黑召回正在玩的宠物', '主人天黑召回正在玩的宠物',
    '傍晚临近天黑时，院主带魔理沙进公园。魔理沙上蹦床跳起来后天色入夜，院主立刻招呼它回家；魔理沙露出惊讶表情停跳下床，系绳随主人回院。',
    '约 1 分钟', [
        *_lot_yard(-2),
        f'item replace block ~{_PARK_BOWL[0]} ~ ~{_PARK_BOWL[1]} container.0 with mcyukkuri:oil_grain_regular 4',
        'summon minecraft:marker ~{} ~ ~{} {{Tags:["mcy_park_walk"]}}'.format(*(v + .5 for v in _lot_spot('trampoline', -4, 3))),
        _lot_keeper('outing_keeper'),
        *(line.replace('AdultScale:1f,Scale:1f', 'AdultScale:0.9f,Scale:0.9f')
          for line in _lot_pet('park_recall_play', 'outing_keeper', 'marisa', -2, 'recall_bold',
                               boldness=95, attachment=5, nutrition=100, energy=98)),
    ], env=DAY, late=[Retry('time set midnight', sel('recall_bold'), tries=1, first=420)],
    spot='yard', vantage=(*_lot_spot('trampoline', -1, 5), -169, 15),
    reads=[('读魔理沙', f'yukkuri status {sel("recall_bold")} raw')])

add('park_slide', 'park', '滑梯：胆大油反复滑，胆小油不上去', '滑梯：胆大油反复滑，胆小油不上去',
    '滑梯与陶管北侧草坪上有两只无聊的流浪油。左前方胆大好奇的魔理沙爬上滑梯滑下、意犹未尽再来一轮；右侧胆小的灵梦不上滑梯，转去钻旁边的陶管。',
    '约 1 分钟', [
        block(3, 0, -6, 'mcyukkuri:yukkuri_nest[facing=south]'),
        block(7, 0, -6, 'mcyukkuri:yukkuri_nest[facing=south]'),
        street_trial('park_slide', 'marisa', 3, -4, 'slide_bold', boldness=95, intelligence=95,
                     adult_scale=.95, nutrition=95, energy=95, yaw=35, label='胆大好奇魔理沙',
                     extra='YukkuriDailyLife:{Boredom:95,Affection:20}'),
        street_trial('park_slide', 'reimu', 6, -4, 'slide_timid', boldness=5, intelligence=20,
                     adult_scale=1.05, nutrition=95, energy=90, yaw=0, label='胆小灵梦',
                     extra='YukkuriDailyLife:{Boredom:90,Affection:20}'),
        home('slide_bold', 3, -6), home('slide_timid', 7, -6),
    ], env=DAY, spot='slide', vantage=(1, 6, -164, 12),
    reads=[('读魔理沙', f'yukkuri status {sel("slide_bold")} raw'),
           ('读灵梦', f'yukkuri status {sel("slide_timid")} raw')])

add('park_tube', 'park', '陶管：胆小流浪油穿管并折返再来', '陶管：胆小流浪油穿管并折返再来',
    '公园陶管北侧有一只无聊且胆小的流浪灵梦。看它从北口钻进陶管穿过，南口出来让开后仍想玩，再折返从南口钻回，玩够后满意停下。',
    '约 1 分钟', [
        block(3, 0, -4, 'mcyukkuri:yukkuri_nest[facing=south]'),
        street_trial('park_tube', 'reimu', 0, -4, 'tube_timid', boldness=5, intelligence=30,
                     adult_scale=1.0, nutrition=95, energy=90, yaw=0, label='胆小流浪灵梦',
                     extra='YukkuriDailyLife:{Boredom:95,Affection:20}'),
        home('tube_timid', 3, -4),
    ], env=DAY, spot='tunnel', vantage=(2, 5, 162, 14),
    reads=[('读灵梦', f'yukkuri status {sel("tube_timid")} raw')])

add('park_rolling_toys', 'park', '滚球与弹珠：草坪上的滚动玩具', '滚球与弹珠：草坪上的滚动玩具',
    '公园凉亭南侧草坪上放着毛线球和玻璃弹珠，旁有三只性格与精力不同的流浪油：左侧魔理沙与右侧橙精力足，走近拱动并追着滚动的毛线球和弹珠玩；中间精力低的灵梦不理它们。',
    '约 1 分钟', [
        *shed(-1, 5, half=1),
        *shed(3, 5, half=1),
        *shed(7, 5, half=1),
        item('mcyukkuri:yarn_ball', 1, -1, 7, 'toy_yarn'),
        item('mcyukkuri:glass_marble', 1, 7, 7, 'toy_marble'),
        street_trial('park_rolling_toys', 'marisa', -1, 8, 'toy_playful_a', boldness=90, attachment=95, friendliness=20,
                     nutrition=95, energy=95, social_wait=0, yaw=180, label='爱玩魔理沙',
                     extra='YukkuriDailyLife:{Boredom:30,Affection:10}'),
        street_trial('park_rolling_toys', 'chen', 7, 8, 'toy_playful_b', boldness=80, attachment=95, friendliness=20,
                     nutrition=95, energy=95, social_wait=0, yaw=180, label='爱玩橙',
                     extra='YukkuriDailyLife:{Boredom:30,Affection:10}'),
        street_trial('park_rolling_toys', 'reimu', 3, 8, 'toy_low_drive', boldness=20, attachment=15, friendliness=20,
                     nutrition=95, energy=32, social_wait=0, yaw=180, label='没精神灵梦',
                     extra='YukkuriDailyLife:{Boredom:30,Affection:10}'),
    ], at_spawn=[home('toy_playful_a', -1, 5), home('toy_playful_b', 7, 5), home('toy_low_drive', 3, 5)],
    env=DAY, spot='pavilion', vantage=(3, 13, 180, 16),
    reads=[('读魔理沙', f'yukkuri status {sel("toy_playful_a")} raw'),
           ('读橙', f'yukkuri status {sel("toy_playful_b")} raw'),
           ('读灵梦', f'yukkuri status {sel("toy_low_drive")} raw')])

add('park_trampoline_contrast', 'park', '蹦床对照：轻巧胆大与沉重胆小', '蹦床对照：轻巧胆大与沉重胆小',
    '蹦床西侧有两只无聊的流浪油：轻巧胆大的魔理沙连续高跳多下，沉重胆小的灵梦在旁等候后上床，跳得更少也更低，很快因受惊或疲惫停下。',
    '约 1 分钟', [
        block(-5, 0, -5, 'mcyukkuri:yukkuri_nest[facing=south]'),
        block(-6, 0, -2, 'mcyukkuri:yukkuri_nest[facing=south]'),
        street_trial('park_trampoline_contrast', 'marisa', -3, -3, 'tramp_bold', boldness=95, intelligence=10,
                     self_control=95, adult_scale=.85, nutrition=95, energy=100, yaw=-45, label='轻巧胆大魔理沙',
                     extra='YukkuriDailyLife:{Boredom:98,Affection:20}'),
        street_trial('park_trampoline_contrast', 'reimu', -4, -1, 'tramp_timid', boldness=10, intelligence=15,
                     self_control=95, adult_scale=1.2, nutrition=95, energy=55, yaw=-75, label='沉重胆小灵梦',
                     extra='YukkuriDailyLife:{Boredom:95,Affection:20}'),
        home('tramp_bold', -5, -5), home('tramp_timid', -6, -2),
    ], env=DAY, spot='trampoline', vantage=(-1, 5, -165, 14),
    reads=[('读魔理沙', f'yukkuri status {sel("tramp_bold")} raw'),
           ('读灵梦', f'yukkuri status {sel("tramp_timid")} raw')])

add('park_picnic', 'park', '主人在公园长椅上分苹果', '主人在公园长椅上分苹果',
    '院子货箱里有一个苹果。院主先取苹果，再带饿了的魔理沙进公园，坐到蹦床旁的长椅上掰开苹果，一半放到魔理沙面前，一半自己吃，吃完回院。'
    '街上饿着的流浪橙也可能进来讨食。',
    '约 2 分钟', [
        *_lot_yard(-2),
        block(_PARK_CRATE[0], 0, _PARK_CRATE[1], 'mcyukkuri:produce_crate[facing=west]'),
        f'item replace block ~{_PARK_CRATE[0]} ~ ~{_PARK_CRATE[1]} container.0 with minecraft:apple 1',
        'summon minecraft:marker ~{} ~ ~{} {{Tags:["mcy_park_walk"]}}'.format(*(v + .5 for v in _lot_spot('trampoline', -4, 3))),
        _lot_keeper('picnic_keeper', f',YardCrate:{_lot_pos(*_PARK_CRATE)}L,HumanInitialized:1b,NextRestock:2147483647L,CareFood:0'),
        *_lot_pet('park_picnic', 'picnic_keeper', 'marisa', -2, 'picnic_pet', boldness=40, attachment=50, nutrition=40, energy=100),
        street_trial('park_picnic', 'chen', *_lot_spot('street', -6, 0), 'picnic_stray', nutrition=25),
    ], env=DAY, spot='yard', vantage=(*_lot_spot('picnic', 4, 6), 156, 15),
    reads=[('读货箱存量', f'data get block ~{_PARK_CRATE[0]} ~ ~{_PARK_CRATE[1]} Items'),
           ('读魔理沙', f'yukkuri status {sel("picnic_pet")} raw'),
           ('读流浪橙', f'yukkuri status {sel("picnic_stray")} raw')])

# The pocket park lot beside the pet yard on one street: the template's ceramic tunnel is placed from its marker, the owner leads the pets
# along the street to the park, sits on a bench to watch, and leads them home. Offsets are from the town anchor (the yard's centre).
_REST_PARK = (8, -4)
_rest_pets = [('reimu', 'rest_shy', -2, 5, 95), ('marisa', 'rest_bold', 2, 95, 5)]
add('pocket_park_rest', 'town', '主人带两只宠物去口袋公园，坐长椅看它们钻陶管', '主人带两只宠物去口袋公园，坐长椅看它们钻陶管',
    '街边一座口袋公园，圆形铺地中央有一条陶管（地块自带），两侧各有一排长椅。院主牵着灵梦和魔理沙沿街走到公园，解绳后走到长椅上坐下，'
    '身体朝陶管，头跟着玩的那只转。两只宠物自己决定谁先钻陶管，胆小的灵梦不会跑远。玩够或等久了，院主站起来、重新系绳、牵它们回院。',
    '约 2 分钟', [
        box(-12, -1, -9, 22, -1, 10, 'minecraft:stone_bricks'),
        box(-12, 0, -9, 22, 9, 10, 'minecraft:air'),
        box(-12, -1, -8, 22, -1, -6, 'minecraft:dirt_path'),
        *_lot_yard(-2, 2),
        f'item replace block ~{_PARK_BOWL[0]} ~ ~{_PARK_BOWL[1]} container.0 with mcyukkuri:oil_grain_regular 4',
        f'place template mcyukkuri:village/plains/pocket_park ~{_REST_PARK[0]} ~-1 ~{_REST_PARK[1]}',
        # Direct template placement leaves the entrance jigsaw (its village final_state is air) in the gap of the lot's front edge.
        block(_REST_PARK[0] + 5, 0, _REST_PARK[1], 'minecraft:air'),
        (human('caretaker_sister', 0, -2, 'rest_keeper', 0, yaw=180, label='院主')[:-1]
         + f',YardBowl:{_yard_pos(*_PARK_BOWL)}L,HumanHome:{_yard_pos(*_PARK_HOME)}L}}'),
        *(line for kind, name, x, bold, bond in _rest_pets
          for line in _lot_pet('pocket_park_rest', 'rest_keeper', kind, x, name, boldness=bold, attachment=bond, nutrition=100,
                               energy=(85, 100))),
    ], env=DAY, vantage=(13, -8, 0, 20),
    reads=[('读灵梦', f'yukkuri status {sel("rest_shy")} raw'), ('读魔理沙', f'yukkuri status {sel("rest_bold")} raw')])

_park_aid = 'park_aid'
_park_waiter = sel('park_waiter')
_park_local = sel('park_local')
_park_former = uuid_nbt(uid(_park_aid, 'former_owner'))
_park_drop = _lot_pos(*_lot_spot('fountain', 7, -2))
_park_carton_core = _lot_pos(*_lot_spot('fountain', 10, -4))
add(_park_aid, 'park', '流浪油递来一口，回头指向空纸箱', '流浪油递来一口，回头指向空纸箱',
    '喷泉东侧，等主人的灵梦吃下一口苹果，看本地灵梦朝箱口走两步再回头。刚被留下、依恋强的会垂头留下，等得久又困的会自己进箱歇脚。重开可看另一种反应。',
    '约半分钟', [
        *carton_shell(10, -4),
        yk('reimu', 7, -2, 'park_waiter', uuid=uid(_park_aid, 'park_waiter'), label='等主人的灵梦', lead=True,
           adult_scale=.8, nutrition=27, attachment=(58, 100), yaw=-90, picky=0,
           extra=f'YukkuriPet:{{FormerOwner:{_park_former},TownRoots:1b,Waits:1b,DropPos:{_park_drop}L,AbandonStage:1}}'),
        yk('reimu', 7.52, -2, 'park_local', uuid=uid(_park_aid, 'park_local'), label='本地流浪灵梦',
           adult_scale=.8, friendliness=100, attachment=100, yaw=90,
           extra='YukkuriPet:{TownRoots:1b},YukkuriMouth:{Purpose:"MEAL",Items:[{id:"minecraft:apple",count:1}]}'),
        f'data merge entity {_park_local} {{YukkuriLife:{{MealTicks:1}}}}',
        f'execute store result score #park_aid_bond museum run data get entity {_park_waiter} YukkuriLife.Personality.Innate[3] 1',
        f'execute store result score #park_aid_experience museum run data get entity {_park_waiter} YukkuriLife.Personality.Experience[3] 1',
        'scoreboard players operation #park_aid_bond museum += #park_aid_experience museum',
        'execute store result score #park_aid_release museum run time query gametime',
        'execute if score #park_aid_bond museum matches 75.. run scoreboard players remove #park_aid_release museum 120',
        'execute if score #park_aid_bond museum matches ..74 run scoreboard players remove #park_aid_release museum 16000',
        f'execute if score #park_aid_bond museum matches ..74 run data modify entity {_park_waiter} YukkuriLife.Energy set value 32f',
        f'execute store result entity {_park_waiter} YukkuriPet.AbandonedAt long 1 run scoreboard players get #park_aid_release museum',
    ], env=DAY, spot='fountain', vantage=(12, 1, 132, 17),
    reads=[('读等候状态', f'data get entity {_park_waiter} YukkuriPet.Waits'),
           ('读纸箱住客', 'data get block ~10 ~ ~-4 Residents')])

add('juice_bath', 'park', '受伤的灵梦爬进橙汁澡盆泡澡', '受伤的灵梦爬进橙汁澡盆泡澡',
    '公园里橙汁澡盆旁，一只胸前有二级撕裂伤、血量只剩不到一半的灵梦（营养体力充足、身上很脏）。澡盆起始有 3 份橙汁。看它自己经木凳、盆沿爬进盆，'
    '坐稳后闭眼泡着；约 12 秒里血量一格格回升、伤口一级一级缩小、身上变干净，盆里的橙汁少一份，泡完经原路爬出来。再用橙汁右键澡盆补一份，一瓶一份，空瓶还回。',
    '约 1 分钟', [
        'data merge block ~ ~ ~ {Juice:3}',
        yk('reimu', 0, -4, 'soaker', uuid=uid('juice_bath', 'soaker'), label='受伤的灵梦', lead=True, yaw=0, hygiene=20, nutrition=95, energy=95),
        set_status('soaker', 'health.health', 4),
        set_status('soaker', 'health.wound_at', 'front:tear:2'),
    ], give=['mcyukkuri:orange_juice 2'], env=DAY, spot='pool', vantage=(2, -7, 20, 22),
    reads=[('读澡盆橙汁', 'data get block ~ ~ ~ Juice'), ('读灵梦', f'yukkuri status {sel("soaker")} raw')])
_doll_carry = 'doll_carry'
add(_doll_carry, 'park', '无聊的魔理沙把布偶叼回窝边', '无聊的魔理沙把布偶叼回窝边',
    '公园凉亭南侧的草坪上放着一只魔理沙布偶。右边无聊且爱玩的魔理沙（依恋 70）离布偶 3 格，自己的窝在左边 8 格处；前景另一只低兴致、低依恋的灵梦（依恋 20）只看一眼便走开。'
    '看魔理沙亮眼发现布偶、走近叼起、一路带回窝沿外的干地上放下。',
    '约半分钟', [
        *shed(-2, 5, half=1),
        *shed(9, 5, half=1),
        item('mcyukkuri:marisa_doll', 1, 3, 5, 'doll_target'),
        street_trial(_doll_carry, 'marisa', 6, 5, 'doll_marisa', boldness=80, attachment=70, friendliness=60,
                     nutrition=95, energy=95, yaw=90, lead=True, label='无聊的魔理沙',
                     extra='YukkuriDailyLife:{Boredom:42}'),
        street_trial(_doll_carry, 'reimu', 3, 8, 'doll_reimu', boldness=60, attachment=20, friendliness=60,
                     nutrition=95, energy=95, yaw=180, label='低兴致的灵梦',
                     extra='YukkuriDailyLife:{Boredom:5}'),
    ], at_spawn=[home('doll_marisa', -2, 5), home('doll_reimu', 9, 5)],
    env=DAY, spot='pavilion', vantage=(3, 12, 180, 18),
    reads=[('读魔理沙', f'yukkuri status {sel("doll_marisa")} raw'),
           ('读灵梦', f'yukkuri status {sel("doll_reimu")} raw')])

_doll_interrupt = 'doll_carry_interrupt'
add(_doll_interrupt, 'park', '叼布偶途中受惊与饥饿时原地放下', '叼布偶途中受惊与饥饿时原地放下',
    '公园凉亭南侧草坪上两只无聊的魔理沙各自叼起布偶回窝。左边一只途中遇到路过的捕食种受惊，立即原地放下布偶逃开；右边一只途中变饿（营养低于 50）原地放下布偶，吃下身旁苹果恢复后再次叼起布偶带回窝边。',
    '约 1 分钟', [
        *shed(-2, 5, half=1),
        *shed(12, 4, half=1),
        item('mcyukkuri:marisa_doll', 1, 3, 5, 'doll_fright'),
        item('mcyukkuri:marisa_doll', 1, 7, 8, 'doll_hungry'),
        item('minecraft:apple', 1, 8, 6, 'snack_apple'),
        street_trial(_doll_interrupt, 'marisa', 6, 5, 'int_fright', boldness=90, attachment=85, friendliness=60,
                     nutrition=95, energy=95, yaw=90, lead=True, label='受惊的魔理沙',
                     extra='YukkuriDailyLife:{Boredom:44}'),
        street_trial(_doll_interrupt, 'marisa', 10, 8, 'int_hungry', boldness=90, attachment=85, friendliness=60,
                     nutrition=95, energy=95, yaw=90, label='变饿的魔理沙',
                     extra='YukkuriDailyLife:{Boredom:44}'),
    ], at_spawn=[home('int_fright', -2, 5), home('int_hungry', 12, 4)],
    env=DAY, spot='pavilion', vantage=(4, 13, 180, 20),
    reads=[('读受惊魔理沙', f'yukkuri status {sel("int_fright")} raw'),
           ('读变饿魔理沙', f'yukkuri status {sel("int_hungry")} raw')])

_doll_talk = 'doll_talk'
add(_doll_talk, 'park', '孤单的子油对布偶说话并递出一口', '孤单的子油对布偶说话并递出一口',
    '公园凉亭南侧草坪上三只独处（相隔 8 格）的子油各自守着一只灵梦布偶，身旁各有一只苹果。'
    '左边聪慧的灵梦子油（智力 70、自制力 85）认出同种布偶，先转身对它说话，随后吃到苹果时向布偶递出一口停留片刻再自己吃下；'
    '中间聪慧的魔理沙子油（智力 70）面对异种布偶既不搭话也不递食；'
    '右边迟钝的魔理沙子油（智力 25、自制力 20）把异种布偶也当作同类，会搭话并在进食时短暂递出一口。',
    '约半分钟', [
        box(-10, 0, 0, 16, 1, 9, 'minecraft:air', 'replace minecraft:short_grass'),
        box(-9, 0, 1, 15, 0, 1, 'minecraft:oak_fence'),
        box(-9, 0, 7, 15, 0, 7, 'minecraft:oak_fence'),
        box(-9, 0, 2, -9, 0, 6, 'minecraft:oak_fence'),
        box(-1, 0, 2, -1, 0, 6, 'minecraft:oak_fence'),
        box(7, 0, 2, 7, 0, 6, 'minecraft:oak_fence'),
        box(15, 0, 2, 15, 0, 6, 'minecraft:oak_fence'),
        item('mcyukkuri:reimu_doll', 1, -5, 3, 'talk_doll_left'),
        item('mcyukkuri:reimu_doll', 1, 3, 3, 'talk_doll_mid'),
        item('mcyukkuri:reimu_doll', 1, 11, 3, 'talk_doll_right'),
        item('minecraft:apple', 1, -5, 4, 'apple_reimu'),
        item('minecraft:apple', 1, 3, 4, 'apple_clever'),
        item('minecraft:apple', 1, 11, 4, 'apple_dull'),
        yk('reimu', -5, 5, 'talk_reimu', uuid=uid(_doll_talk, 'talk_reimu'), stage='child', yaw=180, lead=True,
           label='聪慧灵梦子油', intelligence=70, attachment=80, boldness=50, friendliness=70, self_control=85,
           nutrition=80, energy=95, extra='YukkuriDailyLife:{Affection:75,Cooldown:300}'),
        yk('marisa', 3, 5, 'clever_marisa', uuid=uid(_doll_talk, 'clever_marisa'), stage='child', yaw=180,
           label='聪慧魔理沙子油', intelligence=70, attachment=80, boldness=50, friendliness=70, self_control=80,
           nutrition=80, energy=95, extra='YukkuriDailyLife:{Affection:75,Cooldown:300}'),
        yk('marisa', 11, 5, 'dull_marisa', uuid=uid(_doll_talk, 'dull_marisa'), stage='child', yaw=180,
           label='迟钝魔理沙子油', intelligence=25, attachment=80, boldness=50, friendliness=70, self_control=20,
           nutrition=80, energy=95, extra='YukkuriDailyLife:{Affection:75,Cooldown:300}'),
    ], late=[
        Retry(set_status('talk_reimu', 'needs.nutrition', 55), sel('talk_reimu'), tries=1, first=80),
        Retry(set_status('clever_marisa', 'needs.nutrition', 55), sel('clever_marisa'), tries=1, first=80),
        Retry(set_status('dull_marisa', 'needs.nutrition', 55), sel('dull_marisa'), tries=1, first=80),
    ], env=DAY, spot='pavilion', vantage=(-5, 2, 0, 22),
    reads=[('读聪慧灵梦子油', f'yukkuri status {sel("talk_reimu")} raw'),
           ('读聪慧魔理沙子油', f'yukkuri status {sel("clever_marisa")} raw'),
           ('读迟钝魔理沙子油', f'yukkuri status {sel("dull_marisa")} raw')])

_friend_scene = 'doll_comfort_friend'
add(_friend_scene, 'park', '蹭布偶时熟识同伴露面，转而去找同伴', '蹭布偶时熟识同伴露面，转而去找同伴',
    '草地上孤单的灵梦（依恋 85、亲近需求 80）身边看得见的只有灵梦布偶，先走过去蹭脸。'
    '隔壁小间的栅门被推开后，熟识的魔理沙出现在门口：灵梦当场停下布偶这一场，转身向魔理沙打招呼并进门去蹭它，'
    '布偶这一场没有付给它安抚。',
    '约半分钟', [
        box(-8, 0, 1, 9, 2, 9, 'minecraft:air', 'replace minecraft:short_grass'),
        box(3, 0, 3, 3, 2, 3, 'minecraft:oak_planks'),
        box(3, 0, 5, 3, 2, 5, 'minecraft:oak_planks'),
        block(3, 2, 4, 'minecraft:oak_planks'),
        block(3, 0, 4, 'minecraft:oak_fence_gate[facing=west,open=false]'),
        block(3, 1, 4, 'minecraft:air'),
        box(4, 0, 3, 6, 2, 3, 'minecraft:oak_planks'),
        box(4, 0, 5, 6, 2, 5, 'minecraft:oak_planks'),
        box(6, 0, 3, 6, 2, 5, 'minecraft:oak_planks'),
        item('mcyukkuri:reimu_doll', 1, 0, 4, 'friend_doll'),
        yk('reimu', 0, 6, 'friend_reimu', uuid=uid(_friend_scene, 'friend_reimu'), yaw=180,
           label='孤单的灵梦', attachment=85, boldness=40, friendliness=70, affection=80,
           nutrition=95, energy=95, rest_wait=1200),
        yk('marisa', 4, 4, 'friend_marisa', uuid=uid(_friend_scene, 'friend_marisa'), yaw=90,
           label='熟识的魔理沙', attachment=100, boldness=50, friendliness=80, affection=0,
           nutrition=95, energy=95, rest_wait=1200),
        f'yukkuri scenario bond {sel("friend_reimu")} {sel("friend_marisa")}',
    ], env=DAY, spot='pavilion', vantage=(-4, 5, -90, 18),
    reads=[('读孤单灵梦', f'yukkuri status {sel("friend_reimu")} raw'),
           ('读熟识魔理沙', f'yukkuri status {sel("friend_marisa")} raw')])

_giveup = 'doll_talk_giveup'
add(_giveup, 'park', '子油转身去对布偶说话时被饥饿打断', '子油转身去对布偶说话时被饥饿打断',
    '围起来的一小块草地上，孤单的灵梦子油（依恋 80、亲近需求 80）背对灵梦布偶，先转身过去开口。'
    '刚转到一半就饿了：它当场放弃这次搭话，转身吃掉手边的苹果；隔一阵（依恋越高等得越短）才会再试，不会在布偶旁边反复重来。',
    '约二十秒', [
        box(-6, 0, -1, 6, 1, 10, 'minecraft:air', 'replace minecraft:short_grass'),
        box(-4, 0, 1, 4, 0, 1, 'minecraft:oak_fence'),
        box(-4, 0, 9, 4, 0, 9, 'minecraft:oak_fence'),
        box(-4, 0, 1, -4, 0, 9, 'minecraft:oak_fence'),
        box(4, 0, 1, 4, 0, 9, 'minecraft:oak_fence'),
        item('mcyukkuri:reimu_doll', 1, 0, 3, 'giveup_doll'),
        item('minecraft:apple', 1, 0, 4, 'giveup_apple'),
        yk('reimu', 0, 5, 'giveup_kid', uuid=uid(_giveup, 'giveup_kid'), stage='child', yaw=0,
           label='对布偶说话的子油', intelligence=70, attachment=80, boldness=50, friendliness=70, self_control=80,
           nutrition=80, energy=95, rest_wait=1200,
           extra='YukkuriDailyLife:{Affection:75,Boredom:20,Cooldown:300}'),
    ], env=DAY, spot='pavilion', vantage=(0, 8, 180, 22),
    reads=[('读子油', f'yukkuri status {sel("giveup_kid")} raw')])
# ---- a kept doll taken from a smaller body (docs/FEATURES.md, 布偶), and a player taking one in front of its keeper.
# A doll is a keepsake only while the body beside it keeps it, and only a body with a nest of its own and a free mouth can take
# one over (KeepsakeLife.canSnatch), so every taker gets a shed while no victim gets one: a bedless victim never picks its own
# doll up before the scene does. A taker's nest stands five blocks behind it, outside the home-territory radius 3, or the taker
# would warn the visitor off the nest instead of bullying it. The snatch pair starts inside the bout's own contact reach with no
# affection or boredom need, so the doll is the pair's first drive: the harsh adult claims at its first scan, before either body
# can wander off. doll_take holds its cast with a zero-speed pin (a sleeping row five blocks apart, so no pair ever meets); the two
# outcome scenes below fence their pair into a small lawn patch instead, so a free pair stays within the bout's scan distance while
# it waits for another escalation roll.
_SNATCH_STILL = 'attributes:[{id:"minecraft:generic.movement_speed",base:0.0d}]'
# Grime is a random roll that lowers self-esteem enough to turn a bold child's puff into flight; the shown pair starts clean.
_SNATCH_CLEAN = 'YukkuriStatus:{Marks:{Rolled:1b,Grime:0,Soil:0,Rain:0}},'
# Loneliness would send a body nuzzling and shove its pair past the bout's contact reach before the claim; no affection need
# leaves the bully bout as the pair's first drive.
_SNATCH_CALM = 'YukkuriDailyLife:{Boredom:0,Affection:0},'

_doll_snatch = 'doll_snatch'
add(_doll_snatch, 'park', '蛮横的魔理沙抢走小灵梦的布偶', '蛮横的魔理沙抢走小灵梦的布偶',
    '公园凉亭南侧草坪上并排两组，相距 10 格：每组是一只蛮横的成体魔理沙（友好 0、自制力 50）和一个矮一大截的孩子灵梦，'
    '灵梦身旁地上放着它的布偶。左边灵梦胆大，右边灵梦胆小。看魔理沙扬脸叫嚣「mine」后贴上去，二十次接触后把布偶叼进自己嘴里：'
    '胆大的灵梦冒火回 puff，魔理沙也回一记 puff、就地撂下布偶；胆小的灵梦哭丧着脸逃开，魔理沙叼着布偶走回五格外的窝，'
    '在窝沿外放下。撂下的布偶，魔理沙得空后会再捡回窝去。两组反应不同只看各自的胆量与本事，重开可再看一遍。',
    '约 25 秒', [
        # Clear the working lawn and the flower bed between it and the view (blocking foliage would hide a child's doll and feet).
        # The fountain's rim one row north of the east lane stays: clearing it lets the basin run into the lawn and the timid
        # child spends the scene fleeing rising water instead of answering the theft.
        box(-2, 0, 3, 18, 3, 15, 'minecraft:air'),
        box(-2, 0, 4, 22, 3, 15, 'minecraft:air'),
        *shed(7, 4, half=1), *shed(17, 4, half=1),
        item('mcyukkuri:reimu_doll', 1, 7.65, 8.65, 'snatch_doll_a'),
        item('mcyukkuri:reimu_doll', 1, 17.65, 8.65, 'snatch_doll_b'),
        yk('marisa', 7.45, 9, 'snatch_thief_a', uuid=uid(_doll_snatch, 'snatch_thief_a'),
           boldness=75, friendliness=0, self_control=50, attachment=70, yaw=-90, lead=True,
           label='蛮横的魔理沙', also=('snatch_a',), extra=_SNATCH_CALM),
        yk('reimu', 8, 9, 'snatch_victim_a', uuid=uid(_doll_snatch, 'snatch_victim_a'),
           stage='child', boldness=68, friendliness=60, self_control=70, yaw=90,
           label='胆大的灵梦', also=('snatch_a',), extra=_SNATCH_CALM + _SNATCH_CLEAN),
        yk('marisa', 17.45, 9, 'snatch_thief_b', uuid=uid(_doll_snatch, 'snatch_thief_b'),
           boldness=75, friendliness=0, self_control=50, attachment=70, yaw=-90,
           label='蛮横的魔理沙', also=('snatch_b',), extra=_SNATCH_CALM),
        yk('reimu', 18, 9, 'snatch_victim_b', uuid=uid(_doll_snatch, 'snatch_victim_b'),
           stage='child', boldness=20, friendliness=70, self_control=70, yaw=90,
           label='胆小的灵梦', also=('snatch_b',), extra=_SNATCH_CALM + _SNATCH_CLEAN),
    ], at_spawn=[home('snatch_thief_a', 7, 4), home('snatch_thief_b', 17, 4),
                 # The pairs are on visiting terms with the other group, above the bully gate's neutral affinity: a freed thief
                 # roams once its doll is home and never crosses to rob the other side's child.
                 f'yukkuri scenario relate {sel("snatch_thief_a")} {sel("snatch_victim_b")} 8',
                 f'yukkuri scenario relate {sel("snatch_thief_b")} {sel("snatch_victim_a")} 8'],
    env=DAY, spot='pavilion', vantage=(13, 18, 180, 10),
    reads=[('读胆大的灵梦', f'yukkuri status {sel("snatch_victim_a")} raw'),
           ('读胆小的灵梦', f'yukkuri status {sel("snatch_victim_b")} raw'),
           ('读蛮横的魔理沙', f'yukkuri status {sel("snatch_thief_a")} raw')])

_doll_take = 'doll_take'
add(_doll_take, 'park', '玩家捡走布偶：主人只落泪，陌生人掉好感，打盹的醒来才发现', '玩家捡走布偶：主人只落泪，陌生人掉好感，打盹的醒来才发现',
    '公园草坪上一排四只灵梦，各自身旁地上放着一只布偶。最左两只正在打盹（依恋高、依恋低），第三只认玩家为主人，最右一只是不认识的流浪油（胆大、自制力低）。'
    '趁最左两只还睡着，先捡它们的布偶，再捡右边两只：认主的只露出泪眼、不丢好感也不说重话；陌生的当场生气、说「偷东西」并掉好感。'
    '打盹的两只没看见也没听见，醒来后只看一眼布偶原来的位置：依恋高的难过，依恋低的一脸发愣，都不怪玩家。',
    '约 25 秒', [
        # Clear the flower bed between the row and the view; a child's doll at ground level would sit behind it.
        box(-2, 0, 3, 22, 3, 15, 'minecraft:air'),
        item('mcyukkuri:reimu_doll', 1, 2.1, 6, 'take_doll_a'),
        item('mcyukkuri:reimu_doll', 1, 7.1, 6, 'take_doll_b'),
        item('mcyukkuri:reimu_doll', 1, 12.1, 6, 'take_doll_c'),
        item('mcyukkuri:reimu_doll', 1, 17.1, 6, 'take_doll_d'),
        # Energy just above nothing (a body at 0 collapses into a fatigue rest that never logs the doll-beside sleep beat)
        # with no rest wait: it lies down where it stands at the first planning tick and sleeps its full stretch.
        yk('reimu', 3, 6, 'take_sleep_a', uuid=uid(_doll_take, 'take_sleep_a'),
           boldness=100, attachment=80, energy=5, rest_wait=0, yaw=0, label='打盹的灵梦（依恋高）',
           extra='YukkuriDailyLife:{Boredom:0},' + _SNATCH_STILL),
        yk('reimu', 8, 6, 'take_sleep_b', uuid=uid(_doll_take, 'take_sleep_b'),
           boldness=100, attachment=20, energy=5, rest_wait=0, yaw=0, label='打盹的灵梦（依恋低）',
           extra='YukkuriDailyLife:{Boredom:0},' + _SNATCH_STILL),
        yk('reimu', 13, 6, 'take_owner', uuid=uid(_doll_take, 'take_owner'),
           boldness=50, attachment=60, yaw=0, label='认主的地灵梦',
           extra='YukkuriDailyLife:{Boredom:0},' + _SNATCH_STILL),
        yk('reimu', 18, 6, 'take_stray', uuid=uid(_doll_take, 'take_stray'),
           boldness=80, self_control=40, attachment=20, yaw=0, label='陌生的流浪灵梦',
           extra='YukkuriDailyLife:{Boredom:0},' + _SNATCH_STILL),
        *own('take_owner'),
    ], env=DAY, spot='pavilion', vantage=(11, 18, 180, 8),
    reads=[('读打盹的灵梦', f'yukkuri status {sel("take_sleep_a")} raw'),
           ('读认主的灵梦', f'yukkuri status {sel("take_owner")} raw'),
           ('读流浪灵梦', f'yukkuri status {sel("take_stray")} raw')])

# The two scenes under the snatch scene keep its ordinary outcomes honest: the same bout and strike without a doll, and the same
# strike taking a held treasure. The first escalation roll fails four times in ten, so the pair is kept together by a fenced lawn
# patch rather than by a speed pin: without the patch a roaming pair would drift apart between scans and never resolve. The camera
# stands on a two-block plinth behind the patch to look over its fence.
_PEN = [box(-2, 0, 3, 18, 3, 15, 'minecraft:air'),
        box(4, 0, 5, 12, 0, 5, 'minecraft:oak_fence'),
        box(4, 0, 13, 12, 0, 13, 'minecraft:oak_fence'),
        box(4, 0, 5, 4, 0, 13, 'minecraft:oak_fence'),
        box(12, 0, 5, 12, 0, 13, 'minecraft:oak_fence'),
        block(8, 0, 16, 'minecraft:stone_bricks'),
        block(8, 1, 16, 'minecraft:stone_bricks')]
add('bully_strike', 'park', '蛮横的成体魔理沙欺负孩子灵梦（无布偶）', '蛮横的成体魔理沙欺负孩子灵梦（无布偶）',
    '公园草坪上一小片围着木栅栏的草地，一只蛮横的成体魔理沙（友好 0、自制力 0）与不和的矮小灵梦站在里面挨在一起，'
    '身上没有布偶也没有财宝。看魔理沙贴上去二十次接触后推搡一下得手，灵梦掉血、结怨。这是抢布偶之外最普通的欺负，用来对照。',
    '约 1 分钟', [
        *_PEN,
        yk('marisa', 7.45, 9, 'bully_thief', uuid=uid('bully_strike', 'bully_thief'),
           boldness=75, friendliness=0, self_control=0, yaw=-90, lead=True,
           label='蛮横的魔理沙', extra=_SNATCH_CALM),
        yk('reimu', 8, 9, 'bully_victim', uuid=uid('bully_strike', 'bully_victim'),
           stage='child', boldness=40, friendliness=60, self_control=70, yaw=90,
           label='小灵梦', extra=_SNATCH_CALM),
        f'yukkuri scenario relate {sel("bully_thief")} {sel("bully_victim")} -40',
    ], env=DAY, spot='pavilion', vantage=(8, 16, 180, 23),
    reads=[('读魔理沙', f'yukkuri status {sel("bully_thief")} raw'),
           ('读小灵梦', f'yukkuri status {sel("bully_victim")} raw')])

add('treasure_bully', 'park', '蛮横的魔理沙抢走小灵梦手里的钻石', '蛮横的魔理沙抢走小灵梦手里的钻石',
    '公园草坪上一小片围着木栅栏的草地，一只蛮横的成体魔理沙（友好 0、自制力 0）与不和的矮小灵梦站在里面挨在一起，'
    '灵梦怀里揣着一颗钻石。看魔理沙贴上去二十次接触后推搡一下得手，钻石从灵梦手里转到魔理沙嘴里，灵梦哭丧着脸结怨。',
    '约 1 分钟', [
        *_PEN,
        yk('marisa', 7.45, 9, 'treasure_thief', uuid=uid('treasure_bully', 'treasure_thief'),
           boldness=75, friendliness=0, self_control=0, yaw=-90, lead=True,
           label='蛮横的魔理沙', extra=_SNATCH_CALM),
        yk('reimu', 8, 9, 'treasure_victim', uuid=uid('treasure_bully', 'treasure_victim'),
           stage='child', boldness=40, friendliness=60, self_control=70, yaw=90,
           label='小灵梦', extra=_SNATCH_CALM + 'YukkuriTreasure:{Held:[{id:"minecraft:diamond",count:1}],Cooldown:0}'),
        f'yukkuri scenario relate {sel("treasure_thief")} {sel("treasure_victim")} -40',
    ], env=DAY, spot='pavilion', vantage=(8, 16, 180, 23),
    reads=[('读魔理沙', f'yukkuri status {sel("treasure_thief")} raw'),
           ('读小灵梦', f'yukkuri status {sel("treasure_victim")} raw')])

add('yard_stray_scold', 'town', '宠物院子：流浪认窝，院主喝斥驱离', '宠物院子：流浪认窝，院主喝斥驱离',
    '魔理沙溜进院子，在空着的软窝边认窝并宣言。看院主走近挥手喝斥，魔理沙从院门退出院子。',
    '约 30 秒', [
        *_yard_ground(True),
        block(3, 0, 0, 'mcyukkuri:yukkuri_nest[facing=west]'),
        _yard_marker + 'Tags:["mcy_no_carton","mcy_yard_pet_spawned"],' + _yard_bounds + '}',
        human('caretaker_sister', 6, 0, 'yard_witness', 0, yaw=90, label='院子主人')[:-1]
        + f',UUID:{uuid_nbt(_yard_scold_owner)},HumanHome:{_yard_pos(6, 0)}L,YardBowl:{_yard_pos(5, 2)}L,NoAI:1b}}',
        street_trial('yard_stray_scold', 'marisa', 3, -2, 'yard_stray', boldness=100, friendliness=0,
                     self_control=0, yaw=90, lead=True),
    ], late=[
        Retry(f'data merge entity {sel("yard_witness")} {{NoAI:0b}}', sel('yard_witness'), tries=1, first=200),
    ], env=DAY, vantage=(-4, 8, -155, 16),
    reads=[('读住所宣言与驱离', 'yukkuri homeclaim status'),
           ('读流浪魔理沙状态', f'yukkuri status {sel("yard_stray")} raw')])

add('street_crops', 'town', '田边：居民赶走偷吃作物的灵梦', '田边：居民赶走偷吃作物的灵梦',
    '灵梦在田边，正对成熟胡萝卜，右边的居民看得见它。开场几秒内灵梦咬下一口，作物退一阶。居民挥手喝斥、往田边上前几步，'
    '胆小的灵梦丢下这一口胡萝卜，转身逃出去躲起来。旁边的幼苗不会被咬。', '约半分钟', [
        box(-3, -1, -3, 5, -1, 3, 'minecraft:polished_andesite'),
        block(1, -1, 0, 'minecraft:farmland[moisture=7]'),
        block(1, 0, 0, 'minecraft:carrots[age=7]'),
        block(2, -1, 0, 'minecraft:farmland[moisture=7]'),
        block(2, 0, 0, 'minecraft:carrots[age=0]'),
        street_trial('street_crops', 'reimu', 0, 0, 'crop_eater', nutrition=25, yaw=-90, lead=True,
                     boldness=25, friendliness=75, self_control=75),
        human('caretaker_brother', 3, -1, 'crop_witness', 0, attitude=2, yaw=65, label='看得见田的居民'),
    ], env=DAY, vantage=(0, 8), reads=[('读血量', f'data get entity {sel("crop_eater")} Health')])

_stall_x, _stall_z = BY_ID['town'].anchor
_stall_box = f'{_stall_x} {FEET} {_stall_z}'
add('stall_theft', 'town', '流浪油在摊前偷胡萝卜', '流浪油在摊前偷胡萝卜',
    '从侧面看魔理沙叼走唯一一根胡萝卜。摊主当场喝斥，魔理沙叼着货跑开，惊魂定了才吃。', '约半分钟', [
        box(-7, -1, -7, 7, -1, 7, 'minecraft:polished_andesite'),
        block(0, 0, 0, 'mcyukkuri:produce_crate[facing=north]{Stall:1b,Items:[{Slot:2b,id:"minecraft:carrot",count:1}]}'),
        block(-1, 0, 1, 'minecraft:oak_planks'), block(-1, 1, 1, 'minecraft:oak_planks'),
        block(0, 0, 1, 'minecraft:oak_fence[west=true]'), block(0, 1, 1, 'minecraft:oak_fence[west=true]'),
        block(1, 0, 0, 'minecraft:oak_planks'),
        block(1, 1, 0, 'mcyukkuri:produce_crate[facing=north]{Items:[{Slot:0b,id:"minecraft:potato",count:4}]}'),
        # A compact booth with a stock post in front of the keeper. The left-hand sightline past it is open.
        box(0, 0, 2, 0, 1, 2, 'minecraft:oak_planks'),
        box(2, 0, 2, 2, 1, 2, 'minecraft:oak_planks'),
        box(1, 0, 3, 1, 1, 3, 'minecraft:oak_planks'),
        box(1, 0, 1, 1, 1, 1, 'minecraft:iron_bars'),
        human('caretaker_brother', 1, 2, 'stall_keeper', 0, yaw=160, grain=1),
        street_trial('stall_theft', 'marisa', 0, -2, 'stall_stray', nutrition=18, self_control=15, picky=10, yaw=0, lead=True),
    ], env=DAY, vantage=(-4, -4, -55, 8),
    reads=[('读箱中货物', f'data get block {_stall_box} Items'),
           ('读嘴中货物', f'data get entity {sel("stall_stray")} YukkuriMouth'),
           ('读流浪油状态', f'yukkuri status {sel("stall_stray")} raw')])

_counter_keeper = sel('counter_keeper')
_counter_stray = sel('counter_stray')
_counter_bread = 'minecraft:bread'
# The corner shop of the side street (x0..5, z8..17 of the template): four counter crates on the hatch at z8, the keeper's home behind
# the second one. Origin ~ is the second crate (candy and the emerald float); the first holds bread, the third apples, the fourth budget grain.
_counter_bread_crate, _counter_candy_crate = '~-1 ~1 ~', '~ ~1 ~'
_counter_apple_crate = '~1 ~1 ~'
_counter_build = [
    box(-12, -1, -12, 17, -1, 15, 'minecraft:polished_andesite'),
    'place template mcyukkuri:village/plains/side_street ~-2 ~-1 ~-8',
    # Direct template placement leaves the three street jigsaws intact. Their village final_state is air.
    block(-2, 0, -4, 'minecraft:air'), block(15, 0, -4, 'minecraft:air'), block(6, 0, 13, 'minecraft:air'),
    # The street band in front of the hatch is dirt path in places (15/16 high): level it so a walking stray never drops half a pixel.
    box(-3, -1, -8, 5, -1, -1, 'minecraft:polished_andesite'),
    f'execute positioned ~0.5 ~ ~1.5 run tag @e[type=mcyukkuri:caretaker_brother,distance=..0.5,limit=1] add {tag("counter_keeper")}',
]
_counter_rows = [('读面包箱存货', f'data get block {_counter_bread_crate} Items'),
                 ('读糖箱存货和找零', f'data get block {_counter_candy_crate} Items'),
                 ('读苹果箱存货', f'data get block {_counter_apple_crate} Items'),
                 ('读店主的站位', f'data get entity {_counter_keeper} Pos')]
add('counter_buy', 'town', '在街角店向店主买货，再把货卖回柜台', '在街角店向店主买货，再把货卖回柜台',
    '先看店主从柜台后的住处走出店门、绕到遮阳篷下的售货口。空手点货箱看货，手持绿宝石各买一件糖和面包；'
    '再用纸（4 张）和面包（2 个）卖给店主，各换一颗绿宝石，货进货箱、绿宝石从柜台里付出。钻石柜台不收。', '约 1 分钟',
    _counter_build, give=['minecraft:emerald 7', 'minecraft:paper 4', 'minecraft:diamond 1', 'minecraft:bread 2'],
    env={'time': 'noon', 'weather': 'clear'},
    vantage=(-3, -5, -45, 8), reads=_counter_rows + [('读自己的背包', 'data get entity @s Inventory')])

add('counter_shoo', 'town', '流浪油在售货口讨食，店主挥手赶开', '流浪油在售货口讨食，店主挥手赶开',
    '看饥饿的流浪灵梦走到在岗的店主旁开口索食。店主挥手喝止，灵梦自己跑开；货箱里的食物还在。'
    '站在街边给灵梦让路，挥手结束就能继续买东西。', '约半分钟',
    _counter_build, give=['minecraft:emerald 2'], env={'time': 'noon', 'weather': 'clear'}, vantage=(5, -6, 40, 8),
    # The first social search needs grounded candidates. Allow the spawned stray to land first.
    late=[Retry(street_trial('counter_shoo', 'reimu', -2, -4, 'counter_stray', nutrition=42, social_wait=3,
                             boldness=75, friendliness=75, self_control=75, picky=10, yaw=-20, lead=True),
                _counter_keeper, tries=1, first=370)],
    reads=_counter_rows + [('读流浪灵梦的状态', f'yukkuri status {_counter_stray} raw')])

add('street_furniture', 'town', '流浪油：长椅歇息、饮水、木桶檐下躲雨', '流浪油：长椅歇息、饮水、木桶檐下躲雨',
    '西边困倦的魔理沙会在檐下长椅的椅面或椅脚歇息。口渴的灵梦能在干岸饮水。东边两只流浪油附近各有一处遮雨地，分别是抬高木桶和遮篷。', '约半分钟', [
        box(-11, -1, -4, 12, -1, 4, 'minecraft:polished_andesite'),
        box(-9, 3, -2, -3, 3, 2, 'minecraft:spruce_planks'),
        box(-1, 3, -3, 3, 3, 1, 'minecraft:spruce_planks'),
        *furniture('bench', 0, [(-7, 0), (-6, 0), (-5, 0)]),
        block(2, -1, 0, 'minecraft:water[level=0]'),
        block(10, 1, -3, 'minecraft:barrel[facing=west]'),
        block(11, 0, -3, 'minecraft:spruce_fence'),
        block(11, 1, -3, 'minecraft:spruce_planks'),
        'data merge block ~10 ~1 ~-3 {NeoForgeData:{StreetShelter:1b}}',
        box(9, 2, 2, 11, 2, 3, 'minecraft:orange_wool'),
        street_trial('street_furniture', 'marisa', -6, -1, 'bench_stray', energy=19, rest_wait=0, lead=True),
        street_trial('street_furniture', 'reimu', 1, 0, 'water_stray', hygiene=45),
        street_trial('street_furniture', 'alice', 8, -3, 'barrel_stray'),
        street_trial('street_furniture', 'chen', 9, 0, 'awning_stray'),
        f'data merge entity {sel("water_stray")} {{YukkuriLife:{{Dryness:55f}}}}',
    ], env={'time': 'noon', 'weather': 'rain'}, vantage=(0, 14),
    reads=[('读饮水', f'yukkuri status {sel("water_stray")} raw')])

# The street's own litter is swept back into a bin by whoever walks over it. The second bin stands in a nook between two
# wall columns and the bench in front of it, so nobody can get at it from any side and nobody puts anything in it.
add('street_tidy', 'town', '街道：居民把地上的垃圾扫回桶里', '街道：居民扫街垃圾',
    '看路过的居民把脚边的废纸和木棍捡起来，走到立着的桶边投进去；桶可以右键打开看。右边的桶夹在砖墙与长椅之间，谁也推不倒，'
    '居民不会往里投任何东西。',
    '约半分钟', [
        box(-10, -1, 0, 12, -1, 10, 'minecraft:polished_andesite'),
        box(8, 0, 6, 8, 2, 6, 'minecraft:stone_bricks'),
        box(7, 0, 5, 7, 2, 5, 'minecraft:stone_bricks'),
        *furniture('bench', 0, [(7, 7)]),
        *garbage_bin(7, 6, [], False),
        *garbage_bin(6, 3, [], False),
        item('minecraft:paper', 1, 6, 8, name='tidy_paper').replace('Tags:[', 'Tags:["mcy_yukkuri_litter",'),
        item('minecraft:stick', 1, 4, 7, name='tidy_stick').replace('Tags:[', 'Tags:["mcy_yukkuri_litter",'),
        item('minecraft:paper', 1, 5, 8, name='tidy_player_paper'),
        human('caretaker_brother', 6, 6, 'tidy_resident', 0, attitude=2, yaw=180, label='扫街的居民'),
    ], env=DAY, vantage=(6, 14),
    reads=[('读立着的桶', 'data get block ~6 ~ ~3 Items'), ('读长椅夹住的桶', 'data get block ~7 ~ ~6 Items')])

# ---- shop and factories: the whole building is placed by `human build`, which levels the lot and fills the shop itself
STOCK = '@e[type=#mcylab:yukkuri,distance=..60,nbt={NeoForgeData:{HumanCommerce:{Use:"stock"}}}]'
CORE_FILLING = 'mcyukkuri:core_filling[minecraft:custom_data={FillingVersion:1,Composition:"core",Method:"core",Nutrition:10,Sweetness:90,Odor:0,Variant:"reimu"}] 2'
# Any shop staff member (all three have the same job and the same menu).
SHOP_STAFF = '@e[nbt={HumanJob:1},distance=..80,sort=nearest,limit=1]'
SHOP_ACCESS = {'shop_build': (8, 6), 'shop_window': (8, 6), 'shop_buy': (8, 6), 'shop_browse': (8, 6),
               'shop_pick': (8, 6), 'shop_fetch': (8, 6), 'oni_shop': (11, 12)}
SHOP_ACCESS_HINT = '在 M 菜单的「实用」页选“访客／会员／贵宾”，更换本店购买与预订资格。'
# Template x/z -> scene dx = 20 - x, dz = 17 - z; the shop floor (template y3) stands two blocks above the street (scene dy 2).
PET_SHOP = 'yukkuri human build shop ~8 ~ ~17 south'
W1_BOWL, P1_BOWL = '~18 ~2 ~11', '~4 ~2 ~11'
W1_WATER, YARD_WATER = '~16 ~2 ~13', '~8 ~2 ~-1'
YARD_BOWL, YARD_NEST = '~12 ~2 ~-1', '~10 ~2 ~-2'
BOOTH = '@e[type=mcyukkuri:{},nbt={{YukkuriPet:{{Grade:{}}},NeoForgeData:{{HumanCommerce:{{Use:"stock"}}}}}},distance=..80,limit=1,sort=nearest]'
ALICE, CHEN = BOOTH.format('alice', 3), BOOTH.format('chen', 0)


def shop_animal(kind, x, z, name):
    return (f'execute positioned ~{x} ~2 ~{z} run tag @e[type=mcyukkuri:{kind},distance=..1,sort=nearest,limit=1]'
            f' add {tag(name)}')


def shop_need(actor, need, value):
    return f'yukkuri status set {actor} needs.{need} {value}'


add('shop_build', 'shop', '宠物店分房：单间、同窝后院和店员日常照料', '宠物店分房：单间、同窝后院和店员日常照料',
    '从街面看前排四个单间：金章爱丽丝、橙、银章蕾米莉亚和芙兰各住一间。进店穿过柜台旁的门，后院三只同窝灵梦共用一个草窝。'
    '正对进门处的柜台有店员值守，右键哪一位都打开同一个菜单。访客也能开门进展栏、柜台后方、后屋和后院。'
    + SHOP_ACCESS_HINT +
    '爱丽丝和蕾米莉亚房间的食盆起初是空的；橙房间和后院的厕所待清。看店员把粮送进房间，把便便清进后屋的桶。',
    '建筑几秒；白天约 10–60 秒看店员添食、清厕',
    [PET_SHOP, f'data merge block {W1_BOWL} {{Items:[]}}', f'data merge block {P1_BOWL} {{Items:[]}}',
     'data merge block ~14 ~2 ~11 {Waste:3.0d}', 'data merge block ~12 ~2 ~-4 {Waste:3.0d}',
     f'yukkuri status set {ALICE} growth.age_ticks 148000', f'yukkuri status set {BOOTH.format("remilia", 2)} growth.age_ticks 156000',
     shop_need(ALICE, 'nutrition', 42), shop_need(BOOTH.format('remilia', 2), 'nutrition', 42)],
    give=['minecraft:emerald 128', 'mcyukkuri:badge_handbook', CORE_FILLING], env=DAY, vantage=(8, 21, 180, 0),
    capture=dict(shots=(60,), sequences=(), observe=False,
                 views={'facade': dict(position=(0.0, 3.5, 23.0), look_at=(0.0, 4.0, 14.0)),
                        'exam': dict(position=(-7.5, 3.6, 7.5), look_at=(-13.5, 2.8, 11.5))}),
    reads=[('店铺登记（shop list）', 'yukkuri human shop list'),
           ('读店内个体档案', f'yukkuri status {STOCK} raw'),
           ('读徽章与技能存档', f'execute as {STOCK} run data get entity @s YukkuriPet')])
_care_feeder = '@e[type=mcyukkuri:caretaker_sister,nbt={SiteRole:"shop_staff"},distance=..32,sort=nearest,limit=1]'
_care_toy = '@e[type=minecraft:item,nbt={Item:{id:"mcyukkuri:yarn_ball"}},distance=..0.5,limit=1]'
_care_bowl = '~12 ~2 ~12'
add('shop_care', 'shop', '店员照料：带伤、长霉和拥挤的食盆', '店员照料：带伤、长霉和拥挤的食盆',
    '先看橙的单间：橙站在空食盆的入口，带粮的店员请它侧让后添粮。另一位店员带着药，照料受伤的爱丽丝、长霉的蕾米莉亚和后院受伤的灵梦。',
    '约半分钟看治疗与添粮', [
        PET_SHOP,
        shop_animal('reimu', 8.5, -3.5, 'care_yard'),
        f'yukkuri status set {ALICE} health.wound_at front:tear:2',
        f'yukkuri status set {BOOTH.format("remilia", 2)} health.mold 2',
        set_status('care_yard', 'health.wound_at', 'front:tear:2'),
        f'data modify entity {_care_feeder} Medicine set value 0',
        f'tp {_care_feeder} ~13.5 ~2 ~8.0',
        f'tp {CHEN} ~13.5 ~2 ~10.5',
        f'execute positioned ~13.5 ~2 ~12.5 run tp {_care_toy} ~1 ~ ~',
        shop_need(CHEN, 'nutrition', 92), shop_need(CHEN, 'energy', 100),
        f'clone ~12 ~2 ~11 ~12 ~2 ~11 {_care_bowl} replace move',
        f'data merge block {_care_bowl} {{Items:[]}}',
    ], env=DAY, vantage=(12, 7, 345, 18, 2),
    reads=[('读在售个体伤病', f'yukkuri status {STOCK} raw'), ('读橙的食盆', f'data get block {_care_bowl} Items')])

_window_flandre = BOOTH.format('flandre', 0)
_window_west = sel('customer_west', 80)
_window_wary = sel('customer_wary', 80)
add('shop_window', 'shop', '展舱里的油看见客人：招揽、问好、摆架子、害羞、闹别扭', '展舱里的油看见客人：招揽、问好、摆架子、害羞、闹别扭',
    '沿临街的四个单间慢慢走。爱丽丝可亮出钻石章，没徽章的芙兰也能昂首。'
    '注意橙面对熟悉顾客时的鞠躬、面对害怕的人时的退藏，以及上架 17 天的蕾米莉亚转身失落。向仍在恳求的个体挥别时，看它的反应。'
    + SHOP_ACCESS_HINT,
    '建筑几秒；走到展舱 8 格内，1–2 秒内回应',
    [PET_SHOP, 'scoreboard objectives add scn_t dummy', 'execute store result score #now scn_t run time query gametime',
     'scoreboard players remove #now scn_t 408000',
     f'data modify entity {ALICE} YukkuriPet.Grade set value 4',
     f'data modify entity {_window_flandre} YukkuriLife.Personality.Innate set value [I;60,25,85,55,55]',
     f'yukkuri status relate {CHEN} @s 20'],
    late=[
     f'execute as {BOOTH.format("remilia", 2)} store result entity @s NeoForgeData.HumanCommerce.ForSaleSince long 1 run scoreboard players get #now scn_t',
    ],
    env=DAY, vantage=(16, 18, 180, 0),
    reads=[('读在售个体（上架天数与回应）', f'yukkuri status {STOCK} raw')])
add('shop_buy', 'shop', '买下金章爱丽丝：店员到展舱接她装篮，送到柜台', '买下金章爱丽丝：店员到展舱接她装篮，送到柜台',
    '先在「实用」页选“贵宾”。进店右键柜台或任一店员，在“在售”里买下金章爱丽丝：她按对你的防备和熟悉程度跳一下或鞠躬，站着等。空闲的外勤店员拿空运输篮开展舱铁门，'
    '叫她来到门边，装篮那一刻才扣款。店员提篮交到你手上，或放在柜台上留给你。隔着玻璃留意橙的反应。'
    '没钱、天黑、被别人预订时点购买会直接说原因；装篮前你离开商店或天黑，订单取消并告诉你原因，不扣钱。'
    + SHOP_ACCESS_HINT,
    '建筑几秒；买下后约 10–30 秒交到手上', [PET_SHOP],
    give=['minecraft:emerald 128'], env=DAY, vantage=(15, 6, 345, 8, 2),
    reads=[('店铺登记（shop list）', 'yukkuri human shop list')])
add('shop_browse', 'shop', '自由逛店：进展栏、乱动被警告、稀有个体要会员', '自由逛店：进展栏、乱动被警告、稀有个体要会员',
    '以访客身份打开临街橙房间的单扇铁门，再逛后屋和后院。拿着 16 颗绿宝石找店员，在“在售”里试买或预订橙，看看还差多少消费。'
    '切到生存模式，在店里试挖玻璃，等空闲店员走来警告，再试挖一次。玻璃应完好，本店今天会拒绝交易，门仍能开。'
    '想试会员购买，可重开，在「实用」页切换会员后再试。',
    '建筑几秒，试挖后等店员走近', [PET_SHOP],
    give=['minecraft:emerald 16'], env=DAY, vantage=(8, 21, 180, 0),
    reads=[('读店铺登记', 'yukkuri human shop list'), ('读在售子油橙', f'yukkuri status {BOOTH.format("chen", 0)} raw')])
_pick_stock = (('pick_friendly', 'chen', CHEN, 20, 85), ('pick_bold', 'alice', ALICE, 85, 20))
add('shop_pick', 'shop', '在店里挑：潜行右键看卡片、了解、预订后头顶标记', '在店里挑：潜行右键看卡片、了解、预订后头顶标记',
    '进临街两个单间，空主手右键或潜行右键在售的橙与爱丽丝。比较两只的“了解”弹窗：橙胆小又体贴，爱丽丝大胆又好奇。'
    '预订一只，观察头顶的金色标记，另一位玩家看见的是灰色。再次打开卡片取消预订，标记随即消失，定金留在店里。'
    '也可在预订后购买，等店员装进运输篮时付余款。放出买下的个体，用望远镜核对性格词。鬼威惨的库存评语见 SP-05。'
    + SHOP_ACCESS_HINT,
    '建筑几秒，进临街展栏后即可选购', [PET_SHOP,
        *[command for name, _, actor, boldness, friendliness in _pick_stock for command in (
            f'tag {actor} add {tag(name)}',
            set_status(name, 'growth.age_ticks', 148000),
            set_status(name, 'personality.boldness', boldness),
            set_status(name, 'personality.friendliness', friendliness),
            set_status(name, 'personality.attachment', 50),
            set_status(name, 'personality.intelligence', 50),
            set_status(name, 'personality.self_control', 50),
        )],
    ], give=['minecraft:emerald 64', 'minecraft:spyglass'], env=DAY, vantage=(15, 6, 0, 12, 2),
    reads=[('读胆小体贴的橙', f'yukkuri status {sel("pick_friendly")} raw'),
           ('读大胆好奇的爱丽丝', f'yukkuri status {sel("pick_bold")} raw')])
_fetch_stock = (('fetch_corner', 'chen'), ('fetch_nest', 'alice'), ('fetch_yard', 'reimu'))
add('shop_fetch', 'shop', '店员取货：角落、睡在窝里、后院远处的三只都拿得到', '店员取货：角落、睡在窝里、后院远处的三只都拿得到',
    '先在「实用」页选“贵宾”。依次空主手右键玻璃角落里的橙、窝里药睡的爱丽丝和后院远处戴着名称标签的灵梦，从选购卡买下。'
    '看店员在栏门口叫它们靠近，等约五秒仍够不着时伸手接进篮子，去后院取货时则会走进去。等店员提来每只装好的实篮再买下一只。',
    '布置几秒，每单约 20 秒', [PET_SHOP,
        f'tag {CHEN} add {tag("fetch_corner")}', f'tag {ALICE} add {tag("fetch_nest")}',
        shop_animal('reimu', 11.5, -3.5, 'fetch_yard'),
        'clone ~12 ~2 ~11 ~12 ~2 ~11 ~13 ~2 ~11 replace move',
        'fill ~13 ~2 ~10 ~13 ~3 ~10 minecraft:glass', 'fill ~12 ~2 ~11 ~12 ~3 ~11 minecraft:glass',
        f'tp {sel("fetch_corner")} ~12.5 ~2 ~10.5',
        f'data modify entity {sel("fetch_nest")} YukkuriLife.DrugSleep set value 2400',
        f'tp {sel("fetch_nest")} ~18.5 ~2.109375 ~13.5',
        f'tp {sel("fetch_yard")} ~17.5 ~2 ~-10.5',
        f'data merge entity {sel("fetch_yard")} {{CustomName:\'{{"text":"后院的灵梦"}}\',CustomNameVisible:1b}}',
    ],
    give=['minecraft:emerald 128'], env=DAY, vantage=(14, 6, 0, 12, 2),
    reads=[('读展栏角落', f'yukkuri status {sel("fetch_corner")} raw'),
           ('读窝里睡觉的库存', f'yukkuri status {sel("fetch_nest")} raw'),
           ('读后院库存', f'yukkuri status {sel("fetch_yard")} raw')])
add('oni_shop', 'shop', '鬼威惨商店：工具、处理过的库存和低价收购', '鬼威惨商店：工具、处理过的库存和低价收购',
    '从街面进店，查看工具墙和货架，右键任一店员打开菜单。橱窗里有多毛与不足油，可用望远镜看非油症预防。'
    '空主手潜行右键库存，点“了解”看鬼威惨商店的库存评语。'
    '沿着橱窗慢慢走，看个体怎样回应。访客可试开后屋和展栏铁门。菜单底部显示本店购买资格，升会员需累计花 2 颗绿宝石，升贵宾需 6 颗。'
    + SHOP_ACCESS_HINT,
    '建筑几秒，展舱附近等片刻', [
        'yukkuri human build oni_shop ~8 ~ ~17 south',
        'execute positioned ~ ~2 ~ run ' + human('caretaker_sister', 15, 16, 'oni_customer', 0),
    ], give=['minecraft:emerald 64', 'minecraft:spyglass'], env=DAY, vantage=(15, 17, 180, 8),
    capture=dict(shots=(60,), sequences=(), observe=False,
                 views={'counter': dict(position=(9.5, 3.5, 2.8), look_at=(14.5, 3.3, 0.8))}),
    reads=[('读店铺与库存', 'yukkuri human shop list'), ('读库存体况与预防', f'yukkuri status {STOCK} raw')])
_stress_stock = {'oni_bold': (272000, 80, 60), 'oni_timid': (249000, 25, 68)}
add('oni_stress_room', 'shop', '鬼威惨商店：租工具解压', '鬼威惨商店：租工具解压',
    '切到生存模式。右键任一店员，在“租借”页付押金借固定台。找店员买里侧同一展栏里的胆大低自制或胆小的灵梦，'
    '等店员交来实篮，穿过柜台旁的双木门，在解压间干地摆下固定台，再持实篮右键台面。比较鼓脸、僵住或求饶。'
    '趁它还躺着用锥子，观察反应和漏尿沿底面两侧流下的样子。用空手右键个体或台面松绑。'
    '拆下固定台，捡起后回店员的“租借”页归还并取回押金。也可自带工具和个体，随时离开、治疗或装篮带走。展示栏的库存仍受保护。',
    '建筑几秒，买出后等店员交篮', [
        'yukkuri human build oni_shop ~8 ~ ~17 south',
        *[f'tag @e[type=mcyukkuri:reimu,distance=..40,limit=1,nbt={{YukkuriFamily:{{Age:{age}L}},'
          f'NeoForgeData:{{HumanCommerce:{{Use:"stock"}}}}}}] add {tag(name)}'
          for name, (age, _, _) in _stress_stock.items()],
        *[set_status(name, key, value)
          for name, (_, boldness, unease) in _stress_stock.items()
          for key, value in [('personality.boldness', boldness), ('personality.self_control', 25),
                             ('personality.friendliness', 30), ('mood.unease', unease)]],
    ], give=['minecraft:emerald 16', 'mcyukkuri:yukkuri_carrier', 'minecraft:spyglass', 'mcyukkuri:care_awl'],
    env=DAY, vantage=(8, 17, 180, 8),
    capture=dict(shots=(84, 100, 116, 137, 152, 160, 173), sequences=((86, 170, 6),), observe=False,
                 views={'table': dict(position=(24.5, 3.7, .75), look_at=(22.5, 2.55, 2.5),
                                      criteria='看清空台的两条皮带、仰卧个体身上的合拢皮带以及拆台后松绑。')}),
    reads=[('读柜台托盘里的固定台或押金', 'data get block ~14 ~3 ~1 Items'),
           ('读胆大灵梦', f'yukkuri status {sel("oni_bold")} raw'),
           ('读胆小灵梦', f'yukkuri status {sel("oni_timid")} raw')])
add('oni_street_scrap', 'shop', '鬼威惨商店：店员把一份碎屑送到街桶', '鬼威惨商店：店员把一份碎屑送到街桶',
    '废物桶里有一份废纸。看店员从后屋拿出，走到门外街桶投放。桶里的废纸可以读数。', '约一分钟', [
        'yukkuri human build oni_shop ~8 ~ ~17 south',
        'data merge block ~17 ~2 ~-6 {Items:[{Slot:0b,id:"minecraft:paper",count:1}]}',
        *garbage_bin(5, 18, [('minecraft:stick', 1)], False),
    ], env=DAY, vantage=(4, 20, 180, 10),
    reads=[('读店内废物桶', 'data get block ~17 ~2 ~-6 Items'),
           ('读门外街桶', 'data get block ~5 ~ ~18 Items')])
add('shop_restock', 'shop', '家庭房补货：同窝幼油逐只进草窝', '家庭房补货：同窝幼油逐只进草窝',
    '后院的家庭房起初是空的，等一次三天补货。先看右侧后屋进出货桶里的实篮，再看店员一篮一只地把同窝幼油送到后院草窝。'
    '后屋还有一只上架已久的成体魔理沙，店员会把它装篮送回同一个桶。',
    '建筑几秒；补货后约 1–2 分钟看店员上架和出货',
    [PET_SHOP, 'yukkuri clear ~10 ~2 ~-4 ~11 ~3 ~-4', 'yukkuri clear ~8 ~2 ~-3 ~8 ~3 ~-3',
     'scoreboard objectives add scn_t dummy', 'execute store result score #now scn_t run time query gametime',
     'scoreboard players remove #now scn_t 120000'],
    late=[f'yukkuri human shop stock {SHOP_STAFF} marisa ~1 ~2 ~-8',
          f'yukkuri status set {BOOTH.format("marisa", 0)} growth.age_ticks 340000',
          f'execute as {BOOTH.format("marisa", 0)} store result entity @s NeoForgeData.HumanCommerce.ForSaleSince long 1 run scoreboard players get #now scn_t',
          f'yukkuri human shop restock {SHOP_STAFF}'],
    env=DAY, vantage=(7, -8, 330, 15, 2),
    reads=[('读进出货桶', 'data get block ~-2 ~2 ~-2 Items'), ('读家庭窝住户', f'data get block {YARD_NEST}'),
           ('读在售个体', f'yukkuri status {STOCK} raw')])

add('shop_water', 'shop', '饮水器：口渴的去喝，喝空后店员提水加满', '饮水器：口渴的去喝，喝空后店员提水加满',
    '从爱丽丝房间侧面看碟沿。爱丽丝喝掉最后一份，水位降到零；随后店员提桶进来添到八份，把桶收起。',
    '约 2–4 秒先喝水，随后等店员走来', [PET_SHOP,
        f'tp {_care_feeder} ~3.5 ~2 ~4.5',
        f'tp {ALICE} ~16.5 ~2 ~12.5 0 0',
        f'data merge block {W1_WATER} {{Water:1}}', shop_need(ALICE, 'dryness', 60),
    ], env=DAY, vantage=(18, 10, 34, 25, 2),
    reads=[('读碟里余水', f'data get block {W1_WATER} Water'), ('读爱丽丝的口渴', f'yukkuri status {ALICE} raw'),
           ('读店员主手', 'execute as @e[nbt={SiteRole:"shop_staff"},distance=..40] run data get entity @s HandItems[0]')],
    capture=dict(shots=(10, 40, 65, 120, 240), sequences=((10, 80, 5),), observe=False,
                 views={'dish_side': dict(position=(17.9, 2.45, 12.75), look_at=(16.5, 2.18, 13.2),
                                          criteria='从侧面看嘴贴住碟沿、水位降到零，再看店员补水。')}))

_kin_room = (('kin_bold', 11.5, -3.5, 8.5, -2.0), ('kin_timid', 10.5, -3.5, 8.5, -3.4))
add('shop_kin_room', 'shop', '同窝兄弟：护盆、等候和相邻的窝',
    '同窝兄弟：护盆、等候和相邻的窝',
    '看右侧饮水器旁的两只同窝灵梦。胆大的抢先时会鼓脸，胆小的退开等空位，再走近喝水。'
    '喝水后看两只先后吃左侧食盆里的粮，再等天黑，各自在家庭房相邻的软垫窝里睡下。',
    '约十几秒看护盆，约四十秒后入夜', [PET_SHOP,
        # Wholesale juvenile litters arrive in pairs. The template normally starts with three babies.
        'yukkuri clear ~8 ~2 ~-3 ~8 ~3 ~-3',
        *[shop_animal('reimu', x, z, name) for name, x, z, _, _ in _kin_room],
        *[command for name, _, _, x, z in _kin_room for command in (
            f'tp {sel(name)} ~{x} ~2 ~{z} 180 0', set_status(name, 'growth.age_ticks', 148000),
            set_status(name, 'needs.dryness', 55), set_status(name, 'needs.nutrition', 35),
        )],
        f'data merge block {YARD_WATER} {{Water:2}}',
        f'data merge block {YARD_BOWL} {{Items:[{{Slot:0b,id:"mcyukkuri:oil_grain_regular",count:4}}]}}',
    ], late=[f'yukkuri human shop restock {SHOP_STAFF}', Retry('time set midnight', sel('kin_bold'), tries=1, first=850)],
    env=DAY, vantage=(10, -5, 0, 22, 2),
    reads=[('读后院余水', f'data get block {YARD_WATER} Water'), ('读后院食盆', f'data get block {YARD_BOWL} Items'),
           ('读家庭房第一只窝', f'data get block {YARD_NEST}'), ('读相邻的窝', 'data get block ~9 ~2 ~-3'),
           *[(f'读{label}的灵梦', f'yukkuri status {sel(name)} raw')
             for (name, *_), label in zip(_kin_room, ('胆大', '胆小'))]])

_plant_x, _plant_z = BY_ID['plant'].entrance
_plant_receiving = 'processing_plant'
_receiving_animals = (
    ('receive_insult', 'reimu', -8, -13, '胆大·回嘴', 80, 25, 25, 0, 'DEFIANT', 2),
    ('receive_plea', 'alice', -7, -12, '惊慌·哭求', 25, 30, 65, 83, 'PLEAD', 4),
    ('receive_freeze', 'chen', -9, -11, '克制·噤声', 30, 80, 65, 65, 'FREEZE', 4),
)
_receive_mother = uid(_plant_receiving, 'receive_insult')
_receive_kin = kin_of(Mother=_receive_mother)
add(_plant_receiving, 'plant', '加工所厂房：收活体', '加工所厂房：收活体',
    '进大厅右侧的收货间，用空篮装一只带虚弱效果的无主油库里，把实篮递给房里的收货员。看开篮后在眼前哭求、回嘴或噤声，核对空篮和绿宝石。'
    '跟着收货员沿走廊去甜化箱，看他用自己的实篮装运同一只。空手可以接过员工正在搬运的实篮。'
    '对大厅右边的魔理沙用睡雾，在收货间外装篮使其归你，再交给收货员观察拒收。',
    '装篮、递篮各右键一次', [f'yukkuri human build plant ~{_plant_x} ~ ~{_plant_z} north'],
    late=[*[yk(kind, x, z, name, dy=2, stage='adult' if name == 'receive_insult' else 'child', label=label,
               boldness=bold, self_control=control, friendliness=friendly, uuid=uid(_plant_receiving, name),
               grade=1 if name == 'receive_plea' else 0, kin=_receive_kin if name == 'receive_plea' else '')
            for name, kind, x, z, label, bold, control, friendly, *_ in _receiving_animals],
          *[set_status(name, 'mood.unease', unease) for name, _, _, _, _, _, _, _, unease, *_ in _receiving_animals],
          *[f'effect give {sel(name)} minecraft:weakness 120' for name, *_ in _receiving_animals],
          yk('marisa', -5, -15, 'receive_pet', dy=2, label='大厅·魔理沙', uuid=uid(_plant_receiving, 'receive_pet'))],
    give=['mcyukkuri:yukkuri_carrier', 'minecraft:spyglass', 'mcyukkuri:sleep_spray 1'],
    env=DAY, vantage=(_plant_x, _plant_z - 3, 0, 0),
    reads=[(label, f'yukkuri status {sel(name)} raw') for name, _, _, _, label, *_ in _receiving_animals])

_sweet_box = 'factory_sweet_box'
_sweet_cast = (
    ('sweet_defiant', 3, 17, '胆大·低自制', 80, 25, 25, 67, 75),
    ('sweet_endure', 4, 18, '胆大·高自制', 80, 80, 55, 67, 45),
    ('sweet_timid', 3, 20, '胆小·低自制', 25, 30, 55, 67, 64),
)
_sweet_site = '@e[type=minecraft:marker,tag=mcy_plant_site,distance=..45,limit=1]'
_sweet_cabinet = f'~{_plant_x - 1} ~2 ~{_plant_z + 16}'
_sweet_b = '@e[type=mcyukkuri:reimu,distance=..2,limit=1]'
_sweet_boards = (
    (18, ('A 同龄对照', '用同一工具', '看它顶嘴或哭求', '空手右键拔针')),
    (23, ('B 尚淡起步', '不安越久', '积甜越多', '看箱牌转普通甜')),
    (28, ('C 普通甜起步', '馅甜达到 65', '可送普通甜', '用望远镜查看')),
    (35, ('D 浓甜起步', '馅甜达到 75', '员工停手', '留它休息')),
)
add(_sweet_box, 'plant', '甜化箱：加压、积甜与停手', '甜化箱：加压、积甜与停手',
    '看 B 箱门牌。灵梦的馅还淡，员工会按体况选工具。看它受惊、缩起或硬撑，用望远镜对比接触前后的不安与积甜，等箱牌从尚淡转为普通甜。'
    '沿走道看 C 箱的普通甜和 D 箱的浓甜，门牌写着员工对几只停手。到 A 箱对三只同龄个体用同一工具，比较它们对你的反应与恐惧。',
    '观察约一分钟。双手空着右键个体拔针。创造模式的针仍在物品栏，生存模式可从地上拾回。用运输篮带个体上固定台',
    [f'yukkuri human build plant ~{_plant_x} ~ ~{_plant_z} north',
     # This existing, sound animal is close to the ordinary grade (11.25 accumulated). Its next gain is a real mind sample.
     f'execute positioned ~{_plant_x - 5.5} ~2 ~{_plant_z + 24.5} run data merge entity {_sweet_b} '
     '{YukkuriMind:{Unease:63f,Sweetness:10.2f},YukkuriLife:{Personality:{Innate:[I;55,55,85,60,70]}}}',
     *[block(_plant_x - 3, 4, _plant_z + z, 'minecraft:birch_wall_sign[facing=east]{front_text:{messages:['
             + ','.join("'" + _text(line, 'black') + "'" for line in lines) + ']}}') for z, lines in _sweet_boards]],
    late=[command for name, x, z, label, bold, control, friendly, unease, bladder in _sweet_cast for command in (
        yk('marisa', _plant_x + x - 10, _plant_z + z, name, dy=2, label=label, boldness=bold, self_control=control,
           friendliness=friendly, nutrition=41, energy=70, bladder=bladder, social_wait=0, rest_wait=0, picky=0,
           uuid=uid(_sweet_box, name), lead=name == 'sweet_defiant',
           extra=f'YukkuriMind:{{Migrated:1b,Unease:{unease}f,Sweetness:5f}}'),
        f'data merge entity {sel(name)} {{NeoForgeData:{{HumanCommerce:{{OwnerKind:"npc",Use:"plant_stock"}}}}}}',
        f'data modify entity {sel(name)} {OWNED}.Owner set from entity {_sweet_site} UUID',
    )], give=['minecraft:spyglass', 'mcyukkuri:care_awl', 'mcyukkuri:pin_tool', 'mcyukkuri:restraint_frame',
              'mcyukkuri:yukkuri_carrier', 'mcyukkuri:sweet_candy 3'], env=DAY, vantage=(_plant_x, _plant_z + 24, 113, -5, 2),
    reads=[(label, f'yukkuri status {sel(name)} raw') for name, _, _, label, *_ in _sweet_cast])

_kin_boxes = 'factory_kin_boxes'
_kin_parents = kin_of(Mother=uid(_kin_boxes, 'kin_fond'), Father=uid(_kin_boxes, 'kin_selfish'))
_kin_cast = (
    ('kin_fond', 4.0, 20, '高依恋亲油', 'adult', 80, 72, 70, 74, ''),
    ('kin_selfish', 3.05, 19.15, '低依恋亲油', 'adult', 28, 80, 25, 74, ''),
    ('kin_near', 3.9, 19.15, '同箱子油', 'teen', 55, 65, 55, 39, _kin_parents),
    ('kin_window', 4.0, 22, '隔玻璃子油', 'teen', 65, 42, 65, 68, _kin_parents),
    ('kin_screened', 4.0, 30, '实板后亲油', 'adult', 80, 72, 70, 74, ''),
    ('kin_hidden', 4.0, 32, '实板后子油', 'child', 65, 42, 65, 68,
     kin_of(Mother=uid(_kin_boxes, 'kin_screened'))),
)
_kin_site = '@e[type=minecraft:marker,tag=mcy_plant_site,distance=..45,limit=1]'


def _kin_point(x, y, z):
    return f'~{_plant_x + x - 10} ~{y - 1} ~{_plant_z + z}'


add(_kin_boxes, 'plant', '母子隔箱', '母子隔箱',
    '在 B 箱用冷锥碰一下隔玻璃子油，看 A 箱的两只亲油各自贴窗护幼或转开。D 箱的子油隔着实板，C 箱亲油看不见。'
    '看持具员工走近 A 箱的同箱子油时，两只亲油怎样挡在前面或把孩子拱过去。拆掉箱间玻璃可通行。'
    '拆掉 B 箱西侧的外窗，油库里走过窗台外沿才变流浪。望远镜可查看归属和亲缘。',
    '先看隔箱目击，再看同箱反应和破窗去路',
    [f'yukkuri human build plant ~{_plant_x} ~ ~{_plant_z} north'],
    late=[
        f'fill {_kin_point(2, 3, 31)} {_kin_point(5, 5, 31)} minecraft:white_concrete',
        f'fill {_kin_point(-4, 2, 21)} {_kin_point(0, 2, 26)} minecraft:smooth_stone',
        f'fill {_kin_point(-4, 3, 21)} {_kin_point(-4, 4, 26)} minecraft:stone_bricks',
        f'fill {_kin_point(-4, 3, 21)} {_kin_point(0, 4, 21)} minecraft:stone_bricks',
        f'fill {_kin_point(-4, 3, 26)} {_kin_point(0, 4, 26)} minecraft:stone_bricks',
        *[command for name, x, z, label, stage, attachment, bold, friendly, unease, kin in _kin_cast for command in (
            yk('marisa', _plant_x + x - 10, _plant_z + z, name, dy=2, stage=stage, label=label,
               attachment=attachment, boldness=bold, friendliness=friendly, kin=kin, uuid=uid(_kin_boxes, name),
               lead=name == 'kin_fond', extra=f'YukkuriMind:{{Migrated:1b,Unease:{unease}f}}'),
            f'data merge entity {sel(name)} {{NeoForgeData:{{HumanCommerce:{{OwnerKind:"npc",Use:"plant_stock"}}}}}}',
            f'data modify entity {sel(name)} {OWNED}.Owner set from entity {_kin_site} UUID',
        )],
    ], give=['minecraft:spyglass', 'mcyukkuri:care_awl', 'minecraft:diamond_pickaxe', 'mcyukkuri:sweet_candy 3'],
    env=DAY, vantage=(_plant_x - 2, _plant_z + 19, 90, 15, 2),
    reads=[(label, f'yukkuri status {sel(name)} raw') for name, _, _, label, *_ in _kin_cast])

# Adult state demonstrations only set saved starting state; the normal server AI owns the rest.
def agitation_start(phase):
    return f'YukkuriAgitation:{{Phase:"{phase}",PhaseTicks:0,Cooldown:0}}'


_LEAK_DROP_INTERVAL = 160
_LEAK_DROPS = 12  # 1 - .85 ** 12 = 85.8% for the full low-control adult.


def _verify_leak_scene(window):
    rolls = re.findall(r'\[leak\][^\n]*uuid=(\S+) t=(\d+)[^\n]* roll=(\S+) chance=(\S+) flow_at=(\d+) puddle_at=(\d+)', window)
    winners = {}
    for actor, start, roll, chance, flow, puddle in rolls:
        tick = int(start)
        if actor in winners and tick < winners[actor] + 1200:
            return f'{actor} leaked again inside the 1200-tick cooldown'
        winners[actor] = tick
        if not 0 <= float(roll) < float(chance) or not 6 <= int(flow) - int(start) <= 10:
            return f'{actor}: invalid roll or first-spurt duration'
        if not re.search(rf'\[status\][^\n]*uuid={actor}\b[^\n]*behavior\.leak_trace_at: -?\d+ -> {puddle}\b', window):
            return f'{actor}: no skin trace at the puddle tick'
        debit = re.search(rf'\[leak\][^\n]*uuid={actor} t={start} [^\n]*result=flow [^\n]*pressure=(\S+) -> (\S+) released=(\S+)', window)
        if not debit or not 0 < float(debit[3]) <= float(debit[1]) or abs(float(debit[1]) - float(debit[2]) - float(debit[3])) > .001:
            return f'{actor}: visible flow without exactly one bounded debit'
    for slot, control, band, initial in (('adult', '0', .15, 65), ('steady', '100', .05, 26)):
        actor = _uuid_text('excited_leak', slot)
        attempts = re.findall(rf'\[leak\][^\n]*uuid={actor} t=(\d+) [^\n]*stage=adult (?:self_control=(\d+) )?stimulus=drop result=(\w+)([^\n]*)', window)
        if len(attempts) != _LEAK_DROPS:
            return f'{slot}: expected {_LEAK_DROPS} visible falls, found {len(attempts)}'
        winner = None
        for tick, actual_control, result, detail in attempts:
            if winner is not None and int(tick) < winner + 1200:
                if result != 'cooldown' or f'eligible_at={winner + 1200}' not in detail:
                    return f'{slot} fall at {tick} escaped the active cooldown'
                continue
            draw = re.search(r'roll=(\S+) chance=(\S+).* pressure=(\S+)', detail)
            if not draw or actual_control != control:
                return f'{slot} fall at {tick} lacks its natural draw: {detail}'
            roll, chance, pressure = map(float, draw.groups())
            if abs(chance - band * min(1, pressure / 65)) > 1e-6 or winner is None and not initial <= pressure <= initial + 1:
                return f'{slot} fall at {tick} has unexpected pressure or chance: {detail}'
            if result != ('flow' if roll < chance else 'dry'):
                return f'{slot} fall at {tick} contradicts its own roll'
            if result == 'flow':
                winner = int(tick)
    return None


def _verify_need_tell(window):
    """Urge -> act at once: the holding face and line start the toilet trip, the act follows within seconds, in view."""
    vantage = []
    for m in re.finditer(r'\[layout\] t=(\d+)[^\n]*MCYGEOMETRY (\{[^\n]*\})', window):
        row = json.loads(m[2])
        if row.get('player'):
            vantage.append((int(m[1]), row))
    for actor, kind, line, face, source, accident, within in (
            ('trained', 'URINE', 'need_toilet_urine', 'pursed', 'facility', None, 500),
            ('untrained', 'STOOL', 'need_toilet', 'squeezed_frown', 'ground', 'true', 160),
            ('leaker', 'URINE', 'need_toilet_urine', 'pressed_teary', 'ground', 'true', 400)):
        subject = rf'[^\n]*uuid={_uuid_text("need_tell", actor)} t=(\d+) [^\n]*'
        beats = re.findall(r'\[needs\]' + subject + r' beat=(\w+) face=(\w+) declared=(\w+) line=(\w+) kind=(\w+)[^\n]*skin=(\w+)', window)
        for tick, beat, shown, declared, spoken, _, skin in beats:
            if shown != declared:
                return f'{actor}: beat {beat} at {tick} showed {shown} but declared {declared}'
        urges = [b for b in beats if b[1] == 'urge']
        if not urges or urges[0][2] != face or urges[0][4] != line or urges[0][5] != kind:
            return f'{actor}: expected an urge beat {face}/{line}/{kind}, found {urges[:1]}'
        if any(b[1] == 'urge' and b[4] == 'leak' for b in beats):
            return f'{actor}: a leak must be its own beat, not an urge beat'
        relief = [m for m in re.finditer(r'\[needs\]' + subject + rf' relieved source={source} [^\n]*kind={kind}\b[^\n]*', window)]
        if len(relief) != 1 or accident and f'accident={accident}' not in relief[0].group(0):
            return f'{actor}: expected one {source} release, found {[m.group(0) for m in relief]}'
        tick = int(relief[0].group(1))
        if not 0 <= tick - int(urges[0][0]) <= within:
            return f'{actor}: act {tick - int(urges[0][0])} ticks after the urge (limit {within})'
        # The face and the line are the payoff: the observer on the hay stack must really see each shown beat.
        for moment, at in (('urge', int(urges[0][0])), ('act', tick)):
            sample = min(vantage, key=lambda v: abs(v[0] - at), default=None)
            if sample is None or abs(sample[0] - at) > 20 or not sample[1].get('vis'):
                return f'{actor}: the vantage does not see the {moment} beat at {at} (vis): {sample}'
        if re.search(r'\[needs\]' + subject + rf' tell need={kind}\b', window):
            return f'{actor}: the stand-alone tell must be gone'
        if re.search(r'\[status\]' + subject + r' behavior\.animation: (?:\w+ -> nervous)\b', window):
            return f'{actor}: a shake clip was shown as an urge cue'
        if actor == 'trained' and not any(b[1] == 'relief' and b[2] == 'happy' and b[6] == 'flow' for b in beats):
            return f'{actor}: no relief beat with happy face carrying the skin flow'
        if actor == 'untrained' and any(b[6] == 'flow' for b in beats):
            return f'{actor}: a stool-only release must not show the urine flow'
    leaks = re.findall(r'\[needs\][^\n]*uuid=' + _uuid_text('need_tell', 'leaker') + r' t=(\d+) [^\n]*toilet leak on the way', window)
    if len(leaks) != 1:
        return f'leaker: expected one leak on the way, found {leaks}'
    leak_beats = re.findall(r'\[needs\][^\n]*uuid=' + _uuid_text('need_tell', 'leaker') + r' t=(\d+) [^\n]* beat=leak face=(\w+) declared=(\w+) line=(\w+)[^\n]*skin=(\w+)', window)
    if not leak_beats or leak_beats[0][3] != 'leak' or leak_beats[0][4] != 'flow':
        return f'leaker: the leak must log its own beat=leak with line=leak and skin=flow, found {leak_beats}'
    return None


add('need_tell', 'meadow', '尿急与便急：表情台词后立刻行动', '尿急与便急：表情台词后立刻行动',
    '站在草垛上看三个围栏各有一只灵梦：左边练过如厕的尿急，露出抿嘴忍耐的表情、说一句后立刻走去沙厕；'
    '中间没练过的便急，表情更难受，说一句后就在原地拉；右边尿意满了、沙厕要绕过隔栏，走不到就在路上漏出来。'
    '全程没有抖动：只看表情、台词和皮肤上的尿流与湿痕，用望远镜看需求图标在缓解后消失。',
    '开场约 5 秒内各自开始，约 15 秒内各自排完', [
        *(block(x, 0, z, 'minecraft:oak_fence') for x in range(-12, 7) for z in range(-3, 5)
          if x in (-12, -6, 0, 6) or z in (-3, 4)),
        *(block(x, 0, 0, 'minecraft:oak_fence') for x in range(-11, -7)),
        *(block(x, 0, -2, 'mcyukkuri:yukkuri_toilet') for x in (-11, -3, 3)),
        # The hay stack behind the pens lifts the visitor above the fence, so every pen and its face stays in view.
        *(box(-4, 0, z, -2, z - 5, z, 'minecraft:hay_block') for z in range(6, 11)),
        yk('reimu', -3, 2, 'trained', label='练过·尿急', uuid=uid('need_tell', 'trained'), urine=50, self_control=80,
           boldness=35, friendliness=70, skills={'toilet': 8}, lead=True),
        yk('reimu', 3, 2, 'untrained', label='没练过·便急', uuid=uid('need_tell', 'untrained'), bladder=75, self_control=15,
           boldness=80, friendliness=20, skills={'toilet': 0}),
        yk('reimu', -11, 2, 'leaker', label='憋不住·尿急', uuid=uid('need_tell', 'leaker'), urine=100, self_control=65,
           boldness=50, friendliness=50, skills={'toilet': 0})],
    env=DAY, vantage=(-3, 10, 180, 27), give=['minecraft:spyglass', 'mcyukkuri:field_notebook'],
    reads=[('读左栏尿意', f'data get entity {sel("trained")} YukkuriLife.Urine'),
           ('读中栏便意', f'data get entity {sel("untrained")} YukkuriLife.Bladder'),
           ('读右栏尿意', f'data get entity {sel("leaker")} YukkuriLife.Urine')],
    smoke=dict(ticks=900, expect=[r'\[needs\][^\n]* beat=urge face=\w+ declared=\w+ line=need_toilet',
                                  r'\[needs\][^\n]* beat=leak[^\n]*skin=flow\b',
                                  r'\[needs\][^\n]* beat=relief face=happy[^\n]*skin=flow\b'],
               forbid=[r'\[body\][^\n]* death settled\b', r'\[needs\][^\n]* tell need=(?:URINE|STOOL)\b'], verify=_verify_need_tell))


add('excited_leak', 'meadow', '短落差受惊：成油漏尿对照', '短落差受惊：成油漏尿对照',
    '左边的赤油、子油和右边两只成油开场从两格多的安全高度落下，各自真实抽签。右边两只成油每隔 8 秒再落一次，共 12 次。'
    '左栏成油自制低、尿意高，首次漏尿前每次约 15%；右栏成油自制高、尿意较低，每次约 2%，通常保持干爽。'
    '看第一股、漏流和脚底湿边。皮肤上的尿痕随个体移动，小水滩留在原地逐渐淡去。',
    '赤油和子油在开场 1–4 秒，成油看满约 1 分半钟；水滩可继续观察', [
        # The transparent pens keep both roaming adults in view between lifts.
        *(box(x, 0, -2, x, 2, 1, 'minecraft:glass_pane[north=true,south=true]') for x in (1, 4, 7)),
        *(box(1, 0, z, 7, 2, z, 'minecraft:glass_pane[east=true,west=true]') for z in (-2, 1)),
        yk('reimu', -2, 0, 'leak_baby', stage='baby', yaw=0, label='赤油', self_control=0, urine=65, also=('leak_actor',)),
        yk('reimu', 0, 0, 'leak_child', stage='child', yaw=0, label='子油', lead=True, self_control=0, urine=65, also=('leak_actor',)),
        yk('reimu', 2, 0, 'leak_adult', yaw=0, label='低自制成油', uuid=uid('excited_leak', 'adult'), self_control=0, urine=65, also=('leak_actor',)),
        yk('reimu', 5, 0, 'leak_steady', yaw=0, label='高自制成油', uuid=uid('excited_leak', 'steady'), self_control=100, urine=26, also=('leak_actor',)),
    ], late=[
        # The first fall happens after the start hold, in view; leave the full presentation beat between genuine falls, including after a winning roll.
        *(Retry(f'execute as {sel(actor)} at @s run tp @s ~ ~2.5 ~', sel(actor), tries=1, first=_LEAK_DROP_INTERVAL * n + 1)
          for n in range(_LEAK_DROPS) for actor in ('leak_adult', 'leak_steady')),
        *(Retry(f'execute as {sel(actor)} at @s run tp @s ~ ~2.5 ~', sel(actor), tries=1, first=1) for actor in ('leak_baby', 'leak_child')),
    ], env=DAY, vantage=(2, 8, 180, 12),
    smoke=dict(ticks=_LEAK_DROP_INTERVAL * (_LEAK_DROPS - 1) + 220,
               expect=[r'\[leak\][^\n]*stage=(?:baby|juvenile|adult) self_control=0 stimulus=drop result=flow\b',
                       r'\[status\][^\n]*behavior\.expression: \w+ -> leak_flow',
                       r'\[status\][^\n]*behavior\.expression: leak_flow -> side_teary'],
               forbid=[r'\[body\][^\n]* death settled\b'], verify=_verify_leak_scene))


add('leaf_swaddle_neglect', 'meadow', '没人照看，襁褓里积满便便', '没人照看，襁褓里积满便便',
    '两只裹着树叶襁褓的子油没有亲油：便意 95 的一只马上把便便排进襁褓（soil 按体积增加，卫生、不安恶化，襁褓下沿染色）；'
    '已积 3 体积单位的一只早期再满就溢到地上。失护约 1.25／2.5／3.75 天（随体质平移）后依次黄褐干枯、停止溢出并肿胀、损失生命直至便秘死亡；'
    '用「实用」页的倍速和推进加快，持续喂苹果维持真实供食。按“解开积满子油襁褓”调用亲油同一解开动作，积便按体积落地，肿胀和虚弱逐渐恢复',
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

def _repeat_heat(log):
    actor = _uuid_text('pentup_heat', 'restless')
    changes = re.findall(rf'\[agitation\][^\n]*uuid={actor} t=(\d+) [^\n]* phase=(IDLE -> HEAT|HEAT -> HEAT_UNMET) [^\n]*pentUp=([\d.]+)', log)
    if [phase for _, phase, _ in changes[:3]] != ['IDLE -> HEAT', 'HEAT -> HEAT_UNMET', 'IDLE -> HEAT']:
        return f'missing natural heat, unmet exit or later heat for the same adult: {changes}'
    (start, _, first), (end, _, unmet), (again, _, retained) = changes[:3]
    # SC 45 puts this adult just above 55, at 45 + 25*(45-20)/60.
    if not 55.4166 <= float(first) <= float(unmet) < float(retained) <= 100 or int(end) - int(start) < 1199 or int(again) - int(end) < 24000:
        return f'pressure or loaded period/cooldown was lost: {changes[:3]}'


_heat_food = 'mcyukkuri:food_bowl{Items:[{Slot:0b,id:"minecraft:apple",count:32},{Slot:1b,id:"mcyukkuri:oil_grain_premium",count:32}]}'
add('pentup_heat', 'family', '隔栏相望，发情未解又再来', '隔栏相望，发情未解又再来',
    '灵梦在自己的窝边看得见喜欢的魔理沙，中间的隔板挡住接触。先看灵梦自己发情，再看一轮过去后的表情和手账积压。'
    '两边都有食物、饮水和窝，可用倍速推进一天，看同一只灵梦再次发情。', '首次约半分钟；下一轮约一个游戏日', [
        box(-6, 0, -4, 6, 4, 4, 'minecraft:air'),
        *(box(-6, 0, z, 6, 1, z, 'minecraft:glass') for z in (-4, 4)),
        *(box(x, 0, -3, x, 1, 3, 'minecraft:glass') for x in (-6, 6)),
        box(0, 0, -3, 0, 0, 3, 'minecraft:stone_slab[type=top]'), box(0, 1, -3, 0, 2, 3, 'minecraft:glass'),
        *(line for x, kind, name, label in ((-3, 'reimu', 'restless', '隔栏的灵梦'), (3, 'marisa', 'liked', '伴侣魔理沙')) for line in (
            box(x - 2, 3, -3, x + 2, 3, 0, 'minecraft:oak_planks'),
            block(x, 0, -2, 'mcyukkuri:yukkuri_nest'), block(x, 0, 2, _heat_food),
            block(x + 1, 0, 2, 'mcyukkuri:waterer{Water:8}'), block(x - 1, 0, -2, 'mcyukkuri:yukkuri_toilet'),
            yk(kind, x, 0, name, label=label, lead=name == 'restless', uuid=uid('pentup_heat', name),
               kin='PentUp:55f' if name == 'restless' else '', self_control=45, **pair_ready()), *own(name), home(name, x, -2))),
        f'yukkuri scenario bond {sel("restless")} {sel("liked")}',
        f'yukkuri scenario mate {sel("restless")} {sel("liked")}'],
    spot='carry_home', env=DAY, vantage=(-2, 8, 180, 16), give=['mcyukkuri:field_notebook'],
    reads=[('读灵梦的积压', f'data get entity {sel("restless")} YukkuriFamily.PentUp')],
    smoke=dict(ticks=28800, verify=_repeat_heat, expect=[r'phase=HEAT -> HEAT_UNMET'],
               forbid=[r'courtship contact started', r'pressure reason=sukkiri']))


def _held_pressure(log):
    matches = re.findall(r'pressure reason=HOLD pentUp=([\d.]+) -> ([\d.]+)', log)
    if not any(float(before) < 100 for before, _ in matches) or any(
            abs(float(after) - min(100, float(before) + 2)) > .00001 for before, after in matches):
        return f'expected the held adult to gain two pressure up to the cap: {matches}'


for _sid, _title, _instruction, _control, _shift in (
        ('pentup_hold', '禁止素鸡立：想却忍住', '面对这对宠物，看它们脸红、回望又退开，手账里的积压更高。', 85, 45),
        ('pentup_breach', '主人不在就偷偷素鸡立', '离开院子或躲到遮挡后，等它们自行决定是否靠近；完成后回来比较两本手账。', 25, -45)):
    _holding = _sid == 'pentup_hold'
    # Give the smoke owner the player's standing point inside the glass. Telling each pet faces it from that point.
    _commands = [(1, 'execute positioned ~0.5 ~ ~2.5 run yukkuri scenario tool_operator enter')]
    for _tick, _name in ((8, 'rule_pet'), (10, 'mate')):
        _commands.extend([
            (_tick, f'data modify entity {sel(_name)} {OWNED}.Owner set from entity @p[name=MuseumOperator,distance=..16] UUID'),
            (_tick + 1, f'yukkuri scenario house_rules {sel(_name)} 1')])
    if not _holding:
        _commands.append((12, f'yukkuri scenario owner_at {sel("rule_pet")} away'))
    _ticks = 1800 if _holding else 5400
    add(_sid, 'town', _title, _title, '在家规中勾选「不准繁殖（素鸡立）」。' + _instruction, '约半分钟至数分钟', [
        box(-3, -1, -3, 3, -1, 3, 'minecraft:grass_block'), box(-3, 0, -3, 3, 3, 3, 'minecraft:air'),
        *(box(-3, 0, z, 3, 1, z, 'minecraft:glass') for z in (-3, 3)),
        *(box(x, 0, -2, x, 1, 2, 'minecraft:glass') for x in (-3, 3)),
        box(-3, 3, -3, 3, 3, 0, 'minecraft:oak_planks'),
        *(line for x, kind, name in ((-1, 'reimu', 'rule_pet'), (1, 'marisa', 'mate')) for line in (
            block(x, 0, -2, 'mcyukkuri:yukkuri_nest'),
            yk(kind, x, 0, name, lead=x < 0, uuid=uid(_sid, name), self_control=_control, intelligence=70,
               kin='PentUp:80f', extra=f'YukkuriPet:{{Obedience:{_shift}}}', **pair_ready()), *own(name), home(name, x, -2))),
        f'yukkuri scenario bond {sel("rule_pet")} {sel("mate")}', f'yukkuri scenario mate {sel("rule_pet")} {sel("mate")}'],
        env=DAY, vantage=(0, 2 if _holding else 18, 180, 15), give=['mcyukkuri:field_notebook'],
        reads=[('读' + label + '的积压', f'data get entity {sel(name)} YukkuriFamily.PentUp') for name, label in (('rule_pet', '灵梦'), ('mate', '魔理沙'))],
        smoke=dict(ticks=_ticks, commands=_commands + [(_ticks - 1, 'yukkuri scenario tool_operator leave')],
                   expect=[r'held rule=no_sukkiri', r'tempted rule=no_sukkiri [^\n]*choice=HOLD watched=true'] if _holding else [r'courtship contact ended [^\n]*unauthorized=true', r'breach rule=no_sukkiri watched=false'],
                   forbid=[r'courtship contact ended'] if _holding else [], **({'verify': _held_pressure} if _holding else {})))


add('alice_hunt', 'meadow', '发情爱丽丝追袭素鸡立', '发情爱丽丝追袭素鸡立',
    '爱丽丝带着癫狂笑蹦跳追击，目标发现她会喊出警报台词并逃跑。观察扑中后的接触循环、四拍台词与表情。释放时有尘粒和爱心，峰值持续 3 秒，期间受方仍被压住。峰值结束后受方脱身，爱丽丝接完成表情。喷雾或攻击可打断动作', '约半分钟，可重复观察', [
        *(box(-2, 0, z, 5, 1, z, 'minecraft:glass') for z in (-2, 2)),
        *(box(x, 0, -1, x, 1, 1, 'minecraft:glass') for x in (-2, 5)),
        box(-1, 0, 2, 4, 0, 2, 'minecraft:stone_slab[type=top]'),
        yk('alice', 0, 0, 'hunter', lead=True, kin='PentUp:92f', self_control=30, **pair_ready()),
        yk('reimu', 2.5, 0, 'prey', yaw=-90, extra=agitation_start('IDLE'), **pair_ready()),
        yk('marisa', 0, 4, 'witness', extra=agitation_start('IDLE'), **pair_ready()),
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
               for x, z in [(-.24, -.13), (.13, -.24), (.24, .13), (-.13, .24)]] if lane == 'growth' else [])]
    return out


def aftermath(sid, seen, watch, lanes, vantage):
    add(sid, 'meadow', seen, seen, watch,
        '约 20 秒长成。读母体状态可查看进度、营养、生命和耗馅级别。',
        _aftermath_pens(lanes),
        env=DAY, give=['minecraft:apple 16', 'minecraft:shears 1', "minecraft:potion[potion_contents={potion:'minecraft:water'}] 1"], vantage=vantage,
        note='通过场景入口建立事后胎次；每个围栏北侧栅栏外坐着的爱丽丝只作血缘登记的来源（让开场惊慌有个看得见的对象）。',
        late=[f'yukkuri scenario aftermath {sel(f"affected_{lane}")} {sel(f"source_{lane}")} {count}' for lane, _, _, _, _, count in lanes],
        reads=[(f'读{label}的母体与茎生进度', f'yukkuri status {sel(f"affected_{lane}")} raw') for lane, _, _, _, label, _ in lanes]
              + ([('读遗体', 'yukkuri body info @e[type=mcyukkuri:yukkuri_remains,distance=..40,limit=1,sort=nearest]')]
                 if lanes[0][0] == 'depletion' else []))


aftermath('aftermath_growth', '茎生长出三果：有食与无食', '左右各有一只灵梦从短芽长出三果。左栏营养 100，窝垫上有苹果；右栏营养 55，地上没有食物。'
          '看母油在分娩站位就地进食、茎果继续长大，也可把苹果投到右栏母油嘴边。主手拿剪刀可剪断活茎；副手再拿一瓶水，菜单里多出剪茎插瓶。',
          (('growth', -8, 100, 100, '有食·营养100', 3), ('burden', 8, 55, 60, '无食·营养55', 3)), (0, 16))
aftermath('aftermath_depletion', '五果耗馅死亡：遗体留着未熟实子', '灵梦已经空腹，只剩 2.5 点生命。看短芽、五果、颤抖求助和见底后的三秒保命。'
          '看遗体的茎变褐垂下，未熟实子干瘪，可拾起再放下遗体查看它的茎与实子。',
          (('depletion', 0, 0, 10, '耗竭·营养0', 5),), (0, 12))

# ====================================================================================================== new since the last round (not yet played)
NEW = '待验证：新场景，尚未在游戏里实测。'
REMAINS_NEAR = '@e[type=mcyukkuri:yukkuri_remains,distance=..48,limit=1,sort=nearest]'
HARSH = 15                     # Low friendliness; fear and recognition remain separate.


# ---- injuries and remains (open ground: nothing to eat, nothing to hide behind)
add('body_mold', 'body', '油霉1／3／6／8级与去霉药', '油霉1／3／6／8级与去霉药',
    '四只灵梦分别为油霉 1／3／6／8 级，对应四档灰绿菌落；一级的认得油霉，惊叫后发抖，三级的初次遇到只摇身喊痒。先绕行看侧背面，再用去霉药核对真实阶段回退及原位菌落缩小。终末个体会自然进入濒死，完整皮面不应凭中枢损伤冒出创洞',
    '立即看外观；终末约 20 秒后濒死', [
        *[yk('reimu', -6 + 4 * i, 0, f'mold{i}', lead=i == 0, label=f'油霉{level}',
             uuid=uid('body_mold', f'mold{i}'), skills={'mold_lore': 2} if i == 0 else None,
             extra='YukkuriSkills:{Assessed:["mold_lore"]}' if i == 1 else '', **pair_ready())
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
    '中排八种伤型每只正面一级、侧面二级、背面三级；北排五只分别在头顶、正面、背面、侧面、底面有二级细刺伤，比较锚点和动作贴合；南排豆形、先天稀发、多毛及水生大帽对照；绕行看皮面、发面和断发边，移动时看蒙皮；用橙汁闭伤后看同锚点留疤，再用面粉糊。三级裂口继续漏血，及时治疗。本场是外观初态，实际来源另看真实伤因场景',
    '先看后背三级，再看侧面与正面；可清场重开',_wound_materials,env=DAY,vantage=(0,15),
    give=['mcyukkuri:orange_juice 16','mcyukkuri:flour_paste 16','minecraft:spyglass'],
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
    '先看右后追咬，再逐类手动操作。针和钩贴在各自刺出的伤口。扎针后走开超过 5 格等 20 秒再回来，看针仍在原处。双手空着右键个体拔针，听脱针声并看生存模式的原针从伤处落下，创造模式可在物品栏找到原针。在台上再点一次松绑。离开固定台使用吊钩需要顶板。剪发和剪饰物看头部掉落并听剪声。清场恢复地形与实体。',[
        *[yk('reimu',dx,dz,name,label=label,nutrition=90,energy=95,lead=i==0) for i,(name,dx,dz,label) in enumerate(_source_cases)],
        box(10,-1,7,20,5,17,'minecraft:glass','hollow'),
        yk('reimu',15,11,'bite_target',label='真实追咬猎物',nutrition=70,energy=95)],
    late=[yk('remilia',15,15,'bite_hunter',label='饥饿捕食种',nutrition=10,energy=95)],
    env=DAY,vantage=(0,17),give=['minecraft:wooden_sword','minecraft:stick','minecraft:bow','minecraft:arrow 16',
        'minecraft:trident','minecraft:sand 4','minecraft:cactus 4','minecraft:pointed_dripstone 4','minecraft:tnt 2','minecraft:redstone_torch 2',
        'minecraft:flint_and_steel','minecraft:magma_block 4','mcyukkuri:orange_juice 12',
        'mcyukkuri:pin_tool','mcyukkuri:hanging_hook','minecraft:shears','mcyukkuri:iron_enclosure','mcyukkuri:care_awl',
        'mcyukkuri:restraint_frame','mcyukkuri:yukkuri_carrier'],
    reads=[(f'读{label}',f'yukkuri status {sel(name)} raw') for name,_,_,label in _source_cases]
        + [('读真实咬伤',f'yukkuri status {sel("bite_target")} raw'),('读捕食交接',f'yukkuri status {sel("bite_hunter")} raw')])

_punishment_scene = 'punishment'
_punishment_targets = (('a3_freeze', 0, '胆小·僵住', 30, 57, 49),
                       ('a3_plead', 2, '惊慌·哭求', 25, 30, 83),
                       ('a3_defy', -2, '胆大·顶嘴', 80, 68, 0))
_punishment_press = [
    f'damage {sel(name)} 1 minecraft:player_attack by @s'
    for name, *_ in _punishment_targets
]
_punishment_click = json.dumps({'text': '[各打一下／再次施罚]', 'color': 'gold',
                               'clickEvent': {'action': 'run_command', 'value': '/function mcylab:extra/punishment_hit'}},
                              ensure_ascii=False)
add(_punishment_scene, 'body', '制裁反馈：顶嘴、僵住、哭求', '制裁反馈：顶嘴、僵住、哭求',
    '向前走上中央石台，点击聊天里的“各打一下／再次施罚”，或走到各格前亲手用锥子。三只同受一击，看初次回嘴、噤声和哭求；后排隔一层玻璃的三只缩脸、僵住或转向亲属，隔两层的对照看不见。',
    '等约两秒后再点同一按钮，看刚才还顶嘴的个体对同一玩家改为戒备；清场重开才重置关系', [
        box(-3, 0, -2, 3, 0, -2, 'minecraft:glass'),
        block(0, 1, -2, 'minecraft:glass'),
        box(-3, 0, -1, 3, 1, -1, 'minecraft:glass'),
        block(0, 2, -1, 'minecraft:polished_andesite_slab[type=bottom]'),
        *[box(x, 0, 0, x, 0, 4, 'minecraft:polished_andesite') for x in (-3, -1, 1, 3)],
        *[box(x, 1, 0, x, 1, 4, 'minecraft:glass') for x in (-3, -1, 1, 3)],
        *[box(x, 0, z, x, 1, z, 'minecraft:glass_pane[east=true,west=true]') for x in (-2, 0, 2) for z in (1, 3)],
        box(0, 0, 5, 0, 1, 5, 'minecraft:glass'),
        *[yk('reimu', x, 0, name, label=label, boldness=bold, self_control=control, friendliness=15 if x == -2 else 65,
             kin=kin_of(Mother=uid(_punishment_scene, 'a3_kin')) if x == -2 else '',
             lead=x == 0, uuid=uid(_punishment_scene, name))
          for name, x, label, bold, control, _ in _punishment_targets],
        *own('a3_plead'),
        yk('reimu', 2, 2, 'a3_shrink', label='旁观·缩脸', boldness=25, self_control=30, uuid=uid(_punishment_scene, 'a3_shrink')),
        yk('reimu', 0, 2, 'a3_still', label='旁观·噤声', boldness=45, self_control=80, uuid=uid(_punishment_scene, 'a3_still')),
        yk('reimu', -2, 2, 'a3_kin', label='旁观·看向孩子', boldness=30, attachment=80, uuid=uid(_punishment_scene, 'a3_kin')),
        yk('reimu', 0, 4, 'a3_screened', label='两层玻璃·对照', uuid=uid(_punishment_scene, 'a3_screened')),
        *[set_status(name, 'mood.unease', unease) for name, _, _, _, _, unease in _punishment_targets],
        set_status('a3_plead', 'health.health', 8),
        f'tellraw @s {_punishment_click}'],
    env=DAY, vantage=(0, -2, 0, 38), give=['mcyukkuri:care_awl', 'mcyukkuri:orange_juice 3'],
    reads=[(label, f'yukkuri status {sel(name)} raw') for name, _, label, *_ in _punishment_targets]
          + [('读哭求宠物的服从养成值', f'data get entity {sel("a3_plead")} YukkuriPet.Obedience')])

_leak_subjects = ('v0', 'v1', 'v2', 'v3', 'v4')
_leak_actors = [rf'uuid={_uuid_text("wound_leak", name)} [^\n]*' for name in (*_leak_subjects, 'healthy_grazer')]
add('wound_leak', 'body', '伤口漏馅，橙汁止漏', '伤口漏馅，橙汁止漏',
    '从２级背部刺伤侧后方看馅粒外落、泪眼和喊痛。橙汁闭伤补血止漏。另有轻伤、大裂口、多处大裂口和低血露核对照；右侧无伤灵梦照常吃草',
    '先等 10 秒读血量，再用橙汁；随后再等 10 秒确认止漏', [
        *[yk('reimu', -8 + 4 * i, 0, name, yaw=180, lead=i == 1, label=label, nutrition=60, energy=95,
             uuid=uid('wound_leak', name)) for i, (name, label) in
          enumerate(zip(_leak_subjects, ('轻伤', '二级背部刺伤', '三级大伤口', '多处大伤口', '低血露核')))],
        *[set_status(f'v{i}', 'marks.scars', 0) for i in range(5)],
        *[set_status(f'v{i}', 'health.wound_at', f'front:tear:{min(3, i + 1)}') for i in (0, 2, 3, 4)],
        set_status('v1', 'health.wound_at', 'back:tear:2:puncture'),
        set_status('v3', 'health.wound_at', 'back:tear:3'), set_status('v4','health.health',3),
        block(12, -1, 7, 'minecraft:grass_block'), block(12, 0, 7, 'minecraft:short_grass'),
        yk('reimu', 11, 7, 'healthy_grazer', label='无伤吃草', nutrition=75, picky=0, uuid=uid('wound_leak', 'healthy_grazer'))],
    give=['mcyukkuri:flour_paste 5', 'mcyukkuri:orange_juice 5'], note=NEW, env=DAY, vantage=(0, 10),
    reads=[('读二级裂口', f'yukkuri status {sel("v1")}'), ('读三级裂口', f'yukkuri status {sel("v2")}'),
           ('读多处裂口', f'yukkuri status {sel("v3")}'), ('读漏馅露核',f'yukkuri status {sel("v4")} raw')],
    smoke=dict(ticks=480, commands=[
        *[(270, f'yukkuri scenario care_click {sel(name)} orange_juice') for name in ('v1', 'v4')],
        *[(460, f'execute if entity @e[tag={tag(name)},nbt={{Health:10.0f}},distance=..48] run say MCY_LEAK_FULL_HEALTH_{name}')
          for name in ('v1', 'v4', 'healthy_grazer')],
    ], expect=[
        rf'\[body\][^\n]*{_leak_actors[1]} wound leak outlets=1 health=10\.0 -> 9\.5 loss=0\.5',
        rf'\[speech\][^\n]*{_leak_actors[1]} cue event=wounded spoken=true',
        rf'\[body\][^\n]*{_leak_actors[2]} wound leak outlets=1 health=9\.0 -> 8\.0 loss=1\.0',
        rf'\[body\][^\n]*{_leak_actors[3]} wound leak outlets=2 health=10\.0 -> 9\.0 loss=1\.0',
        rf'\[body\][^\n]*{_leak_actors[4]} wound leak outlets=1 health=3\.0 -> 2\.0 loss=1\.0',
        *[rf'\[body\][^\n]*{_leak_actors[i]} orange juice cleared 1 wound\(s\)' for i in (1, 4)],
        rf'\[meal\][^\n]*{_leak_actors[5]} swallowed item=minecraft:short_grass count=1 nutrition=75\.0 -> 95\.0',
        *[f'MCY_LEAK_FULL_HEALTH_{name}' for name in ('v1', 'v4', 'healthy_grazer')],
    ], forbid=[rf'\[body\][^\n]*{_leak_actors[i]} wound leak ' for i in (0, 5)]))

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
    '街上居民把伤重的流浪油圈在街边两格的矮围栏里（2×2，两只都在栏内，伤者不会再走开），自己蹲在旁边照料。伤者有三级裂口、血6、旧疤1。NPC手持橙汁约1秒、挥手交互，随后换面粉糊重复；清伤回血留疤后去疤。每剂先从库存预留到手上，成功消耗，未用完归还，最终余量8→6；可在举药时轻击伤者核对中断不误耗',
    '约 10–40 秒', [
        # A two-cell fence pen holds the wounded stray and its carer together: a street resident never follows a patient past the two-block care reach.
        box(-1, 0, -1, 2, 0, -1, 'minecraft:oak_fence'), box(-1, 0, 2, 2, 0, 2, 'minecraft:oak_fence'),
        box(-1, 0, -1, -1, 0, 2, 'minecraft:oak_fence'), box(2, 0, -1, 2, 0, 2, 'minecraft:oak_fence'),
        street_trial('treatment_care', 'reimu', 0, 0, 'patient', label='伤者', lead=True, **pair_ready()),
        set_status('patient', 'health.wound_at', 'front:tear:3'),
        set_status('patient', 'health.health', 6),
        set_status('patient', 'marks.scars', 0), set_status('patient', 'marks.scars', 1),
        human('caretaker_brother', 1, 1, 'carer', 0, label='照料员')],
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

add('learned_holder', 'body', '目击工具后躲开持用者', '目击工具后躲开持用者',
    '在个体面前使用转子，间隔十秒再让它看见一次。空手靠近目击者也会引起退避。封闭隔间里的对照可以走动，始终看不到工具接触。可把食物放在离持用者八格以外，观察极饿时恢复觅食。',
    '站在目标前使用转子，读旁观者的对象关系', [
        yk('reimu',0,0,'target',label='工具接触对象',lead=True),
        yk('reimu',2,1,'witness',label='看得见的旁观者',boldness=0,uuid=uid('learned_holder','witness')),
        box(-8,-1,-1,-4,3,3,'minecraft:stone','hollow'),
        block(-6,3,1,'minecraft:sea_lantern'),
        yk('reimu',-6,1,'screened',label='隔墙对照',boldness=0,uuid=uid('learned_holder','screened')),
    ], env=DAY,vantage=(0,8),give=['mcyukkuri:care_vibrator 2','minecraft:apple 4'],
    reads=[('读旁观者关系',f'yukkuri status {sel("witness")} raw'),('读隔墙对照',f'yukkuri status {sel("screened")} raw')])

_hook_scene = 'hook_kin_rescue'
add(_hook_scene, 'body', '亲属走近吊钩尝试救援', '亲属走近吊钩尝试救援',
    '在透明高顶下的孩子身上使用吊钩，然后留在五格内并给亲油让开通路。亲油按胆量、恐惧与友善决定是否上前。这处钩位偏高，观察亲油走到下方、抬头尝试、够不到时含泪退让。够到钩身才会松开钩子。',
    '用吊钩挂起孩子，观察亲油走近后如何尝试', [
        box(-8,3,-8,8,3,8,'minecraft:glass'),
        yk('reimu',0,0,'hook_child',label='孩子',lead=True,
           uuid=uid(_hook_scene,'hook_child'),kin=f'Mother:{uuid_nbt(uid(_hook_scene,"hook_parent"))}'),
        yk('reimu',2,0,'hook_parent',label='亲油',uuid=uid(_hook_scene,'hook_parent'),boldness=100,friendliness=100),
    ], env=DAY,vantage=(0,-8,0,20,0),give=['mcyukkuri:hanging_hook'],  # on the ground under the glass, not lifted onto it
    reads=[('读孩子状态',f'yukkuri status {sel("hook_child")} raw'),('读亲油状态',f'yukkuri status {sel("hook_parent")} raw')])

add('learned_fire', 'body', '烫伤后看见火会退开', '烫伤后看见火会退开',
    '左边先踩一小块岩浆块，右边从普通地面开始。等左边跳离后，在安全地面的边上点燃营火。比较两个个体会不会在接触营火前退开。',
    '等左边脱离热地板，再点燃旁边的营火', [
        block(-5,-1,0,'minecraft:magma_block'),
        block(-3,0,0,'minecraft:campfire[lit=false]'),
        block(5,0,0,'minecraft:campfire[lit=false]'),
        yk('reimu',-5,0,'burnt',label='先烫伤',lead=True,uuid=uid('learned_fire','burnt'),**pair_ready()),
        yk('reimu',7,0,'unburnt',label='未烫伤对照',uuid=uid('learned_fire','unburnt'),**pair_ready()),
    ],env=DAY,vantage=(0,8),give=['minecraft:flint_and_steel','mcyukkuri:orange_juice'],
    reads=[('读烫伤',f'yukkuri status {sel("burnt")} raw'),('读对照',f'yukkuri status {sel("unburnt")} raw')])

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
           uuid=uid('cannibal_hunt', 'hunter_' + _label), yaw=-90, nutrition=8, energy=95,
           friendliness=_friend, self_control=_control, boldness=60, lead=_label == 'willing'),
        yk('marisa', _x + 2, 0, 'prey_' + _label, accessory=False, boldness=40, energy=0, nutrition=100,
           uuid=uid('cannibal_hunt', 'prey_' + _label))]
add('cannibal_hunt', 'body', '饿油捕食没头饰的同类', '饿油捕食没头饰的同类',
    '两只灵梦的营养都是8，各自面前有一只正因疲倦入睡的失饰魔理沙。左边的灵梦友善和自制都低，饥饿压过鄙视，会追咬取食；魔理沙惊醒后朝远离它的方向跑。右边的灵梦友善和自制都高，会放弃猎食。读猎食判分、伤口和营养，吃下咬出的馅料后营养才会上升',
    '约10–60秒；饥饿继续加深时意愿也会改变', _hunt_lines, note=NEW, vantage=(0, 12),
    reads=[(f'读{role}·{label}', f'yukkuri status {sel(role + "_" + label)} raw')
           for label in ('willing','reluctant') for role in ('hunter','prey')],
    smoke=dict(ticks=900, expect=[
        rf'\[cannibal\][^\n]*uuid={_uuid_text("cannibal_hunt", "hunter_reluctant")} [^\n]* decision=hunt_unwilling target={_uuid_text("cannibal_hunt", "prey_reluctant")}\b',
        rf'\[threat\][^\n]*uuid={_uuid_text("cannibal_hunt", "prey_willing")} [^\n]* struck stance=FLEE\b',
        rf'\[threat\][^\n]*uuid={_uuid_text("cannibal_hunt", "prey_willing")} [^\n]* flight away stand=',
    ], forbid=[r'\[body\][^\n]* invariant\b',
               rf'\[cannibal\][^\n]*uuid={_uuid_text("cannibal_hunt", "hunter_reluctant")} [^\n]* started mode=HUNT\b'],
    verify_trace=True))


def _verify_cannibal_hunt(window, trace):
    prey = _uuid_text('cannibal_hunt', 'prey_willing')
    hunter = _uuid_text('cannibal_hunt', 'hunter_willing')
    handoff = re.search(rf'\[cannibal\][^\n]*uuid={hunter} [^\n]* hunt meal handed off prey={prey}\b', window)
    if not handoff:
        return 'The willing hunter took no meal from its prey'
    after_handoff = window[handoff.end():]
    swallow = re.search(rf'\[meal\][^\n]*uuid={hunter} [^\n]* swallowed bodyFood portions=[\d.]+ odor=\d+ nutrition=([\d.]+) -> ([\d.]+)',
                        after_handoff)
    if not swallow or float(swallow[2]) <= float(swallow[1]):
        return 'The willing hunter did not gain nutrition by swallowing its meal after the handoff'
    before_swallow = window[:handoff.end() + swallow.start()]
    if re.search(rf'\[sanction\][^\n]*uuid={prey} [^\n]* case opened reason=hatless [^\n]* accuser={hunter}\b', before_swallow):
        return 'The willing hunter opened a hatless sanction before eating its prey meal'
    hit = re.search(rf'\[threat\][^\n]*uuid={prey} t=(\d+)[^\n]* struck stance=FLEE attacker={hunter}\b', window)
    if not hit:
        return 'The willing hunter never made its prey flee'
    tick = int(hit[1])
    positions = {}
    running = False
    for line in trace:
        row = json.loads(line.split('[trace] ', 1)[1])
        if row['k'] == 'st' and tick <= row['t'] <= tick + 104 and row['u'] in (prey, hunter):
            state = row['st']
            positions.setdefault(row['t'], {})[row['u']] = state['p']
            if row['u'] == prey and state['behavior'] == 'flee' and state['animation'] == 'run':
                running = True
    distances = [math.dist(bodies[prey], bodies[hunter]) for bodies in positions.values() if len(bodies) == 2]
    if not running or not distances or max(distances) <= distances[0] + 1:
        return 'Struck prey must show the run clip and gain ground on its attacker'


next(s for s in S if s.id == 'cannibal_hunt').smoke['verify'] = _verify_cannibal_hunt



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
    '持棍妖梦接触且有视线才打：1.5伤、间隔1.6秒。胆小的个体避战，退战比例=0.65−0.0055×胆量；读myon_combat与predator（芙兰受击转战斗态，俯冲命中1.5伤、间隔不少于2秒，拉开时妖梦够不着，战斗不增加其饱食）。旁边灵梦照常逃，可用按钮调整双方胆量后重观；战斗到死亡不应卡死',
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
    '围栏里没戴头饰的灵梦（胆大、友善、健康）：智力普通的旁观者认不出它，按性格与轮次依次初见蔑视、鼓腮嘲弄、转身冷斥或向旁人低声议论，'
    '多轮后转入厌倦冷待；它首轮含泪，反复被嘲时鼓腮回嘴，凶悍成体升级发起欺负。高智力的橙（90）认得出，不鄙视也不躲。观察双方血量、实际 Goal、轮次事件及反应读数',
    '首轮鄙视约 10–30 秒出现，之后每 30–40 秒演进；欺负自然升级有概率，约 1–3 分钟', [
        # The pen keeps wandering actors in sight and leaves ground for a retreat.
        *(block(x, 0, z, 'minecraft:oak_fence') for x in range(-3, 4) for z in range(-3, 4) if abs(x) == 3 or abs(z) == 3),
        box(-1, 0, -5, 1, 0, -5, 'minecraft:oak_planks'),
        # Friendly to bystanders, still bold enough to answer back and defend itself after a hit.
        yk('reimu', 0, 0, 'bare', uuid=uid('hatless_scorn', 'bare'), yaw=-90, lead=True, accessory=False, label='胆大失饰者',
           friendliness=70, boldness=95, self_control=15, **pair_ready()),
        yk('alice', 2, -2, 'proud', uuid=uid('hatless_scorn', 'proud'), yaw=90, label='骄傲旁观者',
           intelligence=65, friendliness=50, boldness=65, self_control=70, **pair_ready()),
        yk('reimu', 2, 2, 'friendly', uuid=uid('hatless_scorn', 'friendly'), yaw=90, label='友善旁观者',
           intelligence=45, friendliness=80, boldness=50, self_control=60, **pair_ready()),
        yk('chen', -2, -2, 'timid', uuid=uid('hatless_scorn', 'timid'), yaw=-90, label='胆小旁观者',
           intelligence=45, friendliness=55, boldness=15, self_control=55, **pair_ready()),
        yk('marisa', 2, 0, 'b1', uuid=uid('hatless_scorn', 'b1'), yaw=90, label='凶悍欺负者', friendliness=0, boldness=80, self_control=0, **pair_ready()),
        yk('chen', -2, 1, 'c', yaw=-90, label='高智力', **pair_ready()), set_status('c', 'personality.intelligence', 90)],
    env=DAY, note=NEW, vantage=(0, -5, 0, 23),
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

# Two pens with a hedge between them, so that neither pair sees or reaches the other: the timid child has the long pen to run in.
_puff_pens = [
    box(-13, 0, -4, 9, 0, -4, 'minecraft:oak_fence[east=true,west=true]'),
    box(-13, 0, 4, 9, 0, 4, 'minecraft:oak_fence[east=true,west=true]'),
    box(-13, 0, -3, -13, 0, 3, 'minecraft:oak_fence[north=true,south=true]'),
    box(9, 0, -3, 9, 0, 3, 'minecraft:oak_fence[north=true,south=true]'),
    box(0, 0, -4, 0, 1, 4, 'minecraft:oak_leaves[persistent=true]'),
]
_puff_pairs = []
for _i, (_x, _label, _stats) in enumerate((
        (-5, '胆小子油', dict(stage='child', friendliness=(30, 70), boldness=(5, 30), self_control=60)),
        (3, '成体', dict(friendliness=(0, 95), boldness=(50, 95), self_control=(0, 60))))):
    _puff_pairs += [
        yk('reimu', _x, 0, f'puff_target{_i}', uuid=uid('puff_response', f'puff_target{_i}'), label=_label, accessory=False,
           yaw=-90, nutrition=90, energy=95, lead=_i == 0, **_stats),
        yk('marisa', _x + 3, 0, f'puff_source{_i}', label='凶悍的制裁者', yaw=90,
           intelligence=30, friendliness=0, boldness=95, self_control=0, **pair_ready())]
add('puff_response', 'headwear', '被噗咕：逃、无视、回嘴或反击', '被噗咕：逃、无视、回嘴或反击',
    '两个围栏各有一只没了头饰的灵梦和一只凶悍的魔理沙，中间隔着树篱。魔理沙认出失饰者后鼓脸宣告制裁。'
    '左栏的胆小子油体型小，多半转身逃到围栏另一头；右栏成体的友善与胆量每次重开都不同，会无视、鼓脸回嘴或冲上去反击。',
    '约 1–3 分钟；魔理沙没宣告时重开', _puff_pens + _puff_pairs,
    env=DAY, vantage=(-2, 13, 180, 22),
    reads=[(f'读{label}的回应', f'yukkuri status {sel(f"puff_target{i}")} raw') for i, label in enumerate(('子油', '成体'))])
# ---- species traits and rare forms
add('species_fatigue', 'species', '妖梦、灵梦、帕琪体力消耗对照', '妖梦、灵梦、帕琪体力消耗对照',
    '妖梦、灵梦和帕秋莉均成年、营养100、精力95，各有两份真实苹果并用玻璃隔开。按 species.fatigue 的 steps 对照代谢 cost：同为20次时应为18／20／25；睡眠会暂停清醒代谢，其他活动额外耗能不计入cost，不要求同一秒达到同次数',
    '默认间隔下约3分钟，精力耗尽前读20次；可用「实用」页的推进', [
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
        '沙漠：暖桌魔理沙嫌热，白天也回棚下睡（先避暑）；普通魔理沙照常活动，不另说话', roofed=True)

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
    '看附近成员发现油霉后鼓脸施压，患病成员含泪转身、回头道别，离开聚落的日常活动范围后回来央求一次，再次受拒。',
    '约 1–2 分钟', [
        *_mold_outer_den,
        box(-8, 0, 8, 8, 0, 25, 'minecraft:short_grass', 'keep'),
        yk('patchouli', -2, 0, 'leader', label='族长', also=('mold_colony_member',), **pair_ready()),
        yk('chen', 0, 1, 'witness', yaw=0, label='近处成员', also=('mold_colony_member',),
           friendliness=24, boldness=32, skills={'mold_lore': 2}, **pair_ready()),
        yk('marisa', 0, 2, 'molded', lead=True, label='患油霉的成员', also=('mold_colony_member',),
           attachment=70, boldness=35, self_control=60, **pair_ready()),
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

_FLIGHT_FAM = {'reimu': None, 'marisa': None}
_FLIGHT_ROOST = (14, 34)


def _flight_scene(sid, kind, title, watch, wait, bold, attach, expect, note='', allow_fate=(), pred_z=20, baby_x=1.9, mother_z=5.0,
                  babies=None):
    """One household at its nest (homes known at the summon): in the default home scene the mother stands 5.0 blocks from the nest and
    each baby ~2.4 blocks to her side (inside the 4-block retrieve distance, clear of the shed's removed front posts and roof) with the
    hungry predator summoned 15 blocks from her (20 from the nest). In timid/hat the mother stands 7.5 blocks out with baby_a ~2.4 from
    her, baby_b ~3.6 from her (~10.5 from the nest, ~10.7 from the predator) and the predator 12.5/13.5 blocks from her (20/21 from the
    nest). The predator is summoned with them because with homes known earlier the babies hop home before anything happens; the 4 s
    scene-start hold lets the player watch from the first tick. The player sees the notice, the urgent run to the nearest baby, the lift,
    the run to the nest and the set-down (the hat scene may need the declared second trip when a dive cuts short the second brim lift),
    then the predator flies back to its roost."""
    parent = uid(sid, 'mother')
    fam = _FLIGHT_FAM[kind]
    # A beat line pairs the face shown with the face declared: any family beat where they differ fails the run.
    smoke = dict(ticks=1800, expect=expect, forbid=[*_carry_transport_forbid, r'\[family\][^\n]* beat=\w+ face=(\w+) declared=(?!\1\b)'])
    if allow_fate:
        smoke['allow_fate'] = list(allow_fate)
    px, pz = _FLIGHT_ROOST
    add(sid, 'family', title, title, watch, wait, [*_carry_nest,
            # No post in the front yard: a lift never loses its contact stand beside one.
            box(-2, 0, 2, -2, 2, 2, 'minecraft:air'), box(2, 0, 2, 2, 2, 2, 'minecraft:air'),
            # The predator's own roost, far on the side it flies away to: its return home after the hunt never picks the family's nest.
            box(px - 4, 0, pz - 4, px + 4, 12, pz + 4, 'minecraft:air'),
            box(px, 0, pz, px, 7, pz, 'minecraft:oak_log'),
            block(px, 8, pz, 'mcyukkuri:predator_nest'),
            block(px - 1, 7, pz, 'minecraft:oak_log[axis=x]'),
            box(px - 1, 9, pz - 1, px, 9, pz, 'minecraft:oak_leaves[persistent=true]'),
            yk(kind, 0, mother_z, 'mother', uuid=parent, yaw=0, lead=True, boldness=bold, attachment=attach, **({'fam': fam} if fam else {})),
            *[yk(kind, x, z, name, stage='baby', uuid=uid(sid, name),
                 kin=kin_of(Mother=parent), childhood=CHILDHOOD_HOPPING)
              for x, z, name in (babies or [(-baby_x, 3.5, 'baby_a'), (baby_x, 3.5, 'baby_b')])],
            yk('remilia', 0, pred_z, 'predator', uuid=uid(sid, 'predator'), yaw=180,
               label='饥饿的捕食种', nutrition=36, energy=38, boldness=40)],
        at_spawn=[home(name, 0, 0) for name in ('mother', 'baby_a', 'baby_b')],
        late=[Retry(f'yukkuri scenario home {sel("predator")} ~{px} ~8 ~{pz}', sel('predator'), tries=8, every=20, first=3)],
        env=DAY, spot='carry_home', vantage=(13, 9, 90, 20), note=note,
        reads=[('读威胁反应与幼体承托', 'yukkuri status @e[tag=scn,distance=..48] raw')],
        smoke=smoke)


# The predator has its own roost (a nest far on the other side): after the hunt it flies back to it (or perches on a far tree when
# the pursuit ended with energy to spare), so it never claims the family's nest.
_ROOST_ASSIGNED = r'\[home\] remilia [^\n]*assigned kind=care\b'
_PREDATOR_LEAVES = r'\[predator\] remilia [^\n]*activity started; task=(?:RETURN|PERCH)\b'
# A hunt that bit the left young ends in one of its visible stops: the kill and the meal, the mother stepping in front of it (taunt:
# a defender who turns the chase into a fight), the hunter's own exhaustion (it comes down at its rest line, energy 0-14, also after finishing a wounded prey
# it stooped at below the line), or the flight back to its roost.
_HUNT_ENDS = (r'\[predator\] remilia [^\n]*(?:activity started; task=(?:RETURN|PERCH)\b|taunt accepted [^\n]*shield='
              r'|eat pause started; reason=prey_kill\b|landing started; previous_task=(?:CHASE|DIVE) [^\n]*energy=(?:1[0-4]|\d)\.)')
# A beat line pairs the face the presentation arbiter showed with the face the beat declared (they must be equal).
_FLIGHT_BEAT = r'\[family\][^\n]* beat=%s face=(\w+) declared=\1 line=%s\b'
# The bound flight in order: armed at the decision, the pickup, (the look-back for a child left), the set-down inside the nest.
_FLIGHT_ORDER = (r'\[family\][^\n]* carry flight bound cause=\w+ leg=GATHER\b[\s\S]*\[family\][^\n]* carry pickup hold=%s purpose=PROTECT\b'
                 r'%s[\s\S]*\[family\][^\n]* carry set down started reason=reached_nest\b'
                 r'[\s\S]*\[family\][^\n]* carry put down reason=reached_nest\b')
_FLIGHT_LEAVES = r'[\s\S]*\[family\][^\n]* carry flight leaves child=\S+ left=1\b'
_flight_timid = 'family_flight_timid'
_flight_hat = 'family_flight_hat'
_flight_scene(_flight_home, 'reimu', '捕食种靠近，亲油抱起赤油躲回窝',
    '灵梦与两只赤油在窝外，南边饥饿的蕾米莉亚主动逼近。看亲油察觉捕食种后跑向最近的赤油、张口含进嘴里，再不减速地跑向第二只，随后径直退回窝边把孩子放到窝里；蕾米莉亚俯冲一轮后飞回远处自己的高巢，一家在窝里安定下来。',
    '约 5 秒开始接抱，15 秒内回窝放下', 75, 85,
    [r'\[family\][^\n]*carry flight choice .*gather=true',
     r'\[family\][^\n]*carry flight reaching child=\S+ distance=[1-9]',
     r'\[family\][^\n]*carry pickup hold=mouth purpose=PROTECT\b',
     r'\[family\][^\n]*carry flight pickup ended aboard=[12]\b',
     _ROOST_ASSIGNED, _PREDATOR_LEAVES,
     r'\[family\][^\n]*carry set down started reason=reached_nest\b',
     _FLIGHT_ORDER % ('mouth', ''), _FLIGHT_BEAT % ('pickup', 'none')],
    note='胆大又依恋的亲油（胆量 75、依恋 85）把两只都带走：两只开场都在约 2.4 格外，看跑向第二只时不减速、要抱的孩子站住等着；抱齐后直奔窝口放下（reached_nest）；捕食种有自己的高巢，俯冲后飞回，不占一家的窝。若俯冲落在抱第二只的途中，第二只留下、二趟再来接，也算正常结局。')
_flight_scene(_flight_timid, 'reimu', '捕食种靠近，胆小的亲油只带走一只',
    '灵梦与两只赤油在窝外，饥饿的蕾米莉亚靠近。亲油胆小又不太依恋（胆量与依恋 31），只来得及含起一只，就停步回头喊一声再逃回窝，另一只自己跟着跑。',
    '约 5 秒开始接抱，15 秒内回窝放下', 31, 31,
    [r'\[family\][^\n]*carry flight choice .*gather=true',
     r'\[family\][^\n]*carry flight reaching child=\S+ distance=\d',
     r'\[family\][^\n]*carry flight leaves child=\S+ left=1 aboard=1 attachment=31\b',
     r'\[family\][^\n]*carry flight pickup ended aboard=1 follow=\[[0-9a-f-]{36}\]',
     _ROOST_ASSIGNED,
     _HUNT_ENDS,
     _FLIGHT_ORDER % ('mouth', _FLIGHT_LEAVES), _FLIGHT_BEAT % ('abandon_look', 'protect_child_flee')],
    note='依恋低于 50 的亲油回头只喊害怕（protect_child_flee），威胁未贴近 4 格内时停步＝12＋依恋/5（31 约 18 tick，近 1 秒；贴近 4 格内停步为 0）；第一只在亲油身边约 2.4 格，第二只在它外侧、距亲油约 3.6 格（离窝约 10.5 格、距捕食种约 10.7 格），耐心只有 13 tick、第一只的口含抱起就要约 18 tick，所以她抱起第一只后不会再开始第二只；留下的孩子面临真实捕食风险。追猎一旦开始只在精力降到休息线 15 时才落地休息（可见的疲惫落地）。若亲油放下孩子后回头挡在追猎前（taunt），捕食种转为战斗去驱赶她：这是真实的阻止，留下的孩子可能被救下或只被咬伤后失血死，亲油自己也可能被杀。',
    # Patience 13 ticks: the mouth lift of the first baby (15-18 ticks) already outlasts it. The second baby lies beyond the first, on
    # the predator's side and ~10.5 blocks from the nest (~3.6 from her, ~10.7 from the predator: inside the 6-block gather range and
    # the 8-block look-back), so the hunt for it ends away from the nest door and the set-down young are not flushed out of the nest.
    mother_z=7.5, babies=[(-1.9, 6.0, 'baby_a'), (2.0, 10.5, 'baby_b')],
    allow_fate=[dict(kind='death', who=f'uuid={_uuid_text(_flight_timid, "baby_b")} ', match=r'cause=(?:mob\b|generic internal=leak\b)',
                     why='the left-behind young is the hunt\'s prey: it dies of the hunter\'s bite or the bleeding wound'),
                dict(kind='death', who=f'uuid={_uuid_text(_flight_timid, "mother")} ', match=r'cause=(?:mob\b|generic internal=leak\b)',
                     why='the mother who steps in front of the hunt to shield the left-behind young may be killed driving it off')])
_flight_scene(_flight_hat, 'marisa', '捕食种靠近，魔理沙把赤油抱上帽檐躲回窝',
    '魔理沙与两只赤油在窝外，饥饿的蕾米莉亚靠近。亲油用辫子把赤油托上帽檐，一只接一只（若俯冲打断第二只抬升，会走已声明的第二趟回接或由留下的赤油自行回窝），然后径直退回窝边放下。',
    '约 5 秒开始接抱，20 秒内回窝放下', 75, 85,
    [r'\[family\][^\n]*carry flight choice .*gather=true',
     r'\[family\][^\n]*carry flight reaching child=\S+ distance=[1-9]',
     r'\[family\][^\n]*carry lift started hold=brim [^\n]*purpose=PROTECT\b',
     r'\[family\][^\n]*carry flight pickup ended aboard=\d\b',
     _ROOST_ASSIGNED, _HUNT_ENDS,
     r'\[family\][^\n]*carry set down started reason=reached_nest\b', _FLIGHT_ORDER % ('brim', '')],
    mother_z=7.5, babies=[(-1.9, 6.0, 'baby_a'), (2.0, 10.5, 'baby_b')],
    allow_fate=[dict(kind='death', who=f'uuid={_uuid_text(_flight_hat, "baby_b")} ', match=r'cause=(?:mob\b|generic internal=leak\b)',
                     why='young left behind by a brim lift that the dive cut short is at real risk from the hunting predator: it dies of the bite or the bleeding wound'),
                dict(kind='death', who=f'uuid={_uuid_text(_flight_hat, "mother")} ', match=r'cause=(?:mob\b|generic internal=leak\b)',
                     why='the hunter that finished the young and ate it is still hungry and rested after its meal: it may hunt the mother next, a real new hunt')],
    note='帽檐路线：辫子托上帽檐（一只约 4.5 秒）后同样不减速地跑向下一只，抱齐后直奔窝口放下；俯冲落在抬升途中时，没抱上的一只自己走回或亲油二趟接回。', pred_z=21)

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
                  yk('marisa', 9, 2, 'custody_unknown', label='UNKNOWN旧档',
                     extra='NeoForgeData:{HumanCommerce:{Owner:' + uuid_nbt(uid('custody_death', 'unseen_owner')) + ',Use:"future_use"}}')]
add('custody_death', 'town', 'NPC主人死了，宠物库存无主', 'NPC主人死了，宠物库存无主',
    '旧档工厂输入开场解除归属。先用篮子装“可先装篮”，再手动杀死NPC主人：现场宠物和库存转无主，玩家个体与UNKNOWN旧档保留归属。放出篮中个体，徽章仍保留',
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


def _seat_family(size=.35, nourishment=147000, age=144000):
    """A real saved body and nourishment near a seat boundary; ordinary loaded ticks perform subsequent growth."""
    return f'Version:3,Initialized:1b,Age:{age}L,Nourishment:{nourishment}L,AdultScale:1f,Scale:{size}f'


def _stray_rain_verify(log):
    marisa = _uuid_text('stray_rain', 'rain_stray')
    damp_exit_collapse = (
        r'found rain stage=0 -> 1 shelter=(BlockPos\{[^\n]+?\})'
        rf'[\s\S]*?uuid={marisa}[^\n]* found leaving target=\1 [^\n]*reason=soaked_or_unsafe'
        rf'[\s\S]*?uuid={marisa}[^\n]* found exited shelter=\1'
        rf'[\s\S]*?uuid={marisa}[^\n]* shelter response started sky=RAIN stage=RUN cover='
        r'[\s\S]*?found rain stage=1 -> 2 shelter=\1'
        r'[\s\S]*?drop item=minecraft:paper count=1 source=shelter at=\1 reason=rain_collapse'
        r'[\s\S]*?found removed shelter=\1 [^\n]*reason=rain_collapse'
    )
    if not re.search(damp_exit_collapse, log):
        return 'the rain-scene Marisa did not leave her damp carton before it collapsed'
    walker = _uuid_text('stray_rain', 'wall_stray')
    if not re.search(rf'\[meal\][^\n]*uuid={walker}[^\n]* swallowed item=minecraft:apple\b', log):
        return 'the wall-side Marisa did not reach and eat her apple'


next(s for s in S if s.id == 'stray_rain').smoke['verify'] = _stray_rain_verify


def _stray_rubbish_verify(log):
    mouth = uid('stray_rubbish', 'open_stray')
    failure = _family_search_order(log, [
        (mouth, r'garbage sniff [^\n]*tipped=true'),
        (mouth, r'garbage nose_in '),
        (mouth, r'garbage pickup item=minecraft:apple [^\n]*holder=mouth [^\n]*marker=pull'),
        (mouth, r'swallowed item=minecraft:apple\b'),
    ])
    if failure:
        return failure
    bold = uid('stray_rubbish', 'bold_stray')
    travels = re.findall(rf'uuid={_uuid_of(bold)}[^\n]* garbage ram [^\n]*strokeTravel=([\d.]+)', log)
    if not travels or min(map(float, travels)) < .18:
        return f'expected at least .18 block of ram travel, found {travels}'
    braided = re.search(rf'uuid={_uuid_text("stray_rubbish", "bold_stray")} [^\n]*garbage pickup item=minecraft:apple [^\n]*holder=braid', log)
    return _family_search_order(log, [
        (bold, r'garbage ram [^\n]*tipped=false rock=strong'),
        (bold, r'garbage ram [^\n]*tipped=true'),
        (bold, r'garbage reroute [^\n]*why=knocked_over'),
        (bold, r'garbage nose_in '),
        (bold, r'garbage pickup item=minecraft:apple [^\n]*holder=(braid|mouth)'),
        *([(bold, r'braid handover item=minecraft:apple clip=braid_eat_bin tick=28\b')] if braided else []),
        (bold, r'swallowed item=minecraft:apple\b'),
    ])


next(s for s in S if s.id == 'stray_rubbish').smoke['verify'] = _stray_rubbish_verify


def _bank_pickup_retries(window):
    """A carrier twice takes the same child and fails immediately at hop range without approaching the hull."""
    held, refused = {}, {}
    events = r'\[(?:action|intent)\][^\n]*? uuid=(\S+) [^\n]*? (?:end id=grab outcome=DONE detail=mounted target=(\S+)|start id=(set_out|walk|beckon|hop)\b|end id=set_out result=(\S+) reasons=\[([^\]]*)\])'
    for carrier, child, action, result, reasons in re.findall(events, window):
        if child:
            held[carrier] = child
        elif action:
            if action != 'set_out' and carrier in held:
                refused.pop(carrier, None)
            held.pop(carrier, None)
        elif result:
            child = held.pop(carrier, None)
            if child and result == 'EXHAUSTED' and reasons == 'TOO_FAR:0':
                if refused.get(carrier) == child:
                    return f'{carrier} twice picked up {child} without approaching the hull'
                refused[carrier] = child
            else:
                refused.pop(carrier, None)


def _carried_boarding(window, owner):
    """The keeper boarded from the bank with a carried child, and it ended on the hull. A window where she never tried is a premise
    that did not happen, so the check fails instead of passing on nothing."""
    attempts = list(re.finditer(r'\[aquatic\][^\n]*uuid=' + owner + r' [^\n]* boarding from bank child=(\S+) hat=(\S+)', window))
    if not attempts:
        return 'keeper never boarded from the bank with a carried child (premise not exercised)'
    for attempt in attempts:
        child, hat = map(re.escape, attempt.groups())
        following = window[attempt.end():]
        contact = re.search(r'\[body\][^\n]*uuid=' + owner + r' [^\n]* mount host=' + hat + r' cause=hop_contact\b', following)
        release = re.search(r'\[body\][^\n]*uuid=' + child + r' [^\n]* dismount at=', following)
        if contact is None or release and release.start() < contact.start():
            return f'keeper failed to board {attempt[2]} with carried child {attempt[1]}'


def _overload_hull(window, scene, parent, exceptional, places=None):
    """What both overload scenes share: the family's hat was placed, every young took its real seat, and each logged capsize
    roll agrees with the logged load. Returns (failure, owner, boats, overloaded_growth). `places` names the seat each young's
    real state produces; by default all three are still crawling, so the largest takes the head and the rest fill the brim
    (user 2026-11-05)."""
    owner = _uuid_text(scene, parent)
    hulls = re.findall(r'\[hat\][^\n]* placed owner=' + owner + r' hat=(\S+)', window)
    if not hulls:
        return f'{parent}: hat was not placed', owner, None, False
    boat = re.escape(hulls[0])
    boats = '(?:' + '|'.join(map(re.escape, dict.fromkeys(hulls))) + ')'
    if places is None:
        places = ((1, 'CABIN'), (2, 'BRIM'), (3, 'HEAD')) if exceptional else ((2, 'BRIM'), (3, 'HEAD'))
    for child, place in places:
        if not re.search(r'\[hat\][^\n]*uuid=' + _uuid_text(scene, parent + str(child))
                         + r'[^\n]* scenario place hat=' + boat + r' place=' + place + r'\b', window):
            return f'{parent}{child}: missing real {place} placement', owner, boats, False
    rolls = re.findall(r'\[aquatic\][^\n]*uuid=' + boats + r'[^\n]* load changed event=(\w+) body=\S+ '
                       r'load=(\S+) capacity=(\S+) chance=(\S+) roll=(\S+) result=(\w+)', window)
    overloaded_growth = False
    for event, load, capacity, chance, roll, result in rolls:
        load, capacity, chance, roll = map(float, (load, capacity, chance, roll))
        expected = 0 if load <= capacity else 1 - capacity / load
        if abs(chance - expected) > 1e-9 or (result == 'capsize') != (roll < chance):
            return f'{parent}: overload chance or result disagrees with the logged load and roll', owner, boats, overloaded_growth
        overloaded_growth |= event == 'growth' and load > capacity
    return None, owner, boats, overloaded_growth


def _overload_tradeoff(window, scene='hat_crowd', families=None):
    """The overloaded boat (FEATURES 载重, 超载断亲): checks each staged family's verdict (DRIVE_TEARFUL / DRIVE_COLD) and
    child response branch (plead / stare / sulk). Every beat logs shown==declared, unaware siblings never get PARTING,
    and cut children never settle back inside the 16-block banned range."""
    if families is None:
        families = (
            ('normal', 'DRIVE_TEARFUL', 'plead', None, None),
            ('cold', 'DRIVE_COLD', 'plead', None, None),
        )
    for parent, verdict, branch, far_actor, places in families:
        failure, owner, boats, overloaded_growth = _overload_hull(window, scene, parent, False, places)
        if failure:
            return failure
        if not overloaded_growth:
            return f'{parent}: no real growth into overload'
        capsized = re.search(r'\[hat\][^\n]*uuid=' + boats + r' t=(\d+)[^\n]* capsized cause=too_heavy\b[^\n]*thrown=[1-9]', window)
        if capsized is None:
            return f'{parent}: no overload capsize in this random run (premise not exercised)'
        child = _uuid_text(scene, parent + '3')
        drawn = re.search(r'\[family\][^\n]*uuid=' + owner + r' t=(\d+)[^\n]* overload verdict child=(\S+) verdict=(\w+) ', window[capsized.start():])
        if drawn is None:
            return f'{parent}: no overload verdict after the capsize'
        if drawn[2] != child or drawn[3] != verdict:
            return f'{parent}: verdict {drawn[3]} for {drawn[2]}, expected {verdict} for the largest young {child}'
        after = window[capsized.start() + drawn.end():]
        beat = re.search(r'\[family\][^\n]*uuid=' + owner + r' t=(\d+) dim=\S+ pos=([^,]+), [^,]+, (\S+) overload beat start child=' + child + r' verdict=' + verdict, after)
        if beat is None:
            return f'{parent}: the verdict was never played out (no beat start)'
        centre = (int(beat[2]), int(beat[3]))
        failure = _driven_out_order(window, after, scene, parent, verdict, branch, owner, child, centre, far_actor=far_actor)
        if failure:
            return failure
    return None


def _driven_out_order(window, after, scene, parent, verdict, branch, owner, child, centre, far_actor=None):
    """The order of the cut child's departure (FEATURES 超载断亲): her verdict beat -> the order -> the child's stat branch
    (plead / stare / sulk) -> it turns and walks -> it is gone (distance and sight) -> only then the cut and the parting feeling
    of the parent and perceiving siblings. Every beat logged with its declared and its shown face must show the declared one, the
    child never calls for the parent it was driven from, and never settles back inside the banned range."""
    def at(pattern, text=after):
        found = re.search(pattern, text)
        return (int(found[1]), found) if found else (None, None)
    ordered, _ = at(r'\[family\][^\n]*uuid=' + child + r' t=(\d+)[^\n]* driven out ordered parent=' + owner + r' verdict=' + verdict)
    if ordered is None:
        return f'{parent}: the child was never ordered out'
    begins, _ = at(r'\[family\][^\n]*uuid=' + child + r' t=(\d+)[^\n]* driven out begins parent=' + owner)
    if begins is None:
        return f'{parent}: the ordered child never began its departure (it drowned, or never stood on dry ground)'
    turned, _ = at(r'\[choreo\][^\n]*uuid=' + child + r' t=(\d+)[^\n]* beat=turn_away face=')
    departed, found = at(r'\[family\][^\n]*uuid=' + child + r' t=(\d+)[^\n]* driven out departed parent=' + owner
                         + r' verdict=' + verdict + r' distance=(\S+) sight=(true|false)')
    if turned is None or departed is None:
        return f'{parent}: the child began to leave but never turned and got away (turned={turned} departed={departed})'
    distance, seen = float(found[2]), found[3] == 'true'
    if not (begins >= ordered and turned >= begins and departed >= turned):
        return f'{parent}: wrong order ordered={ordered} begins={begins} turned={turned} departed={departed}'
    if distance < 8 or seen and distance < 16:
        return f'{parent}: the cut fell while the child was still near or in sight (distance={distance} sight={seen})'
    cut, _ = at(r'\[family\][^\n]*uuid=' + owner + r' t=(\d+)[^\n]* parent dismissed child=' + child + r'[^\n]* direction=both')
    if cut is None or cut < departed:
        return f'{parent}: the tie was cut {"never" if cut is None else "before"} the child was gone (cut={cut} departed={departed})'
    if len(re.findall(r'\[family\][^\n]*uuid=' + owner + r'[^\n]* parent dismissed child=[^\n]* reason=boat_overload', window)) != 1:
        return f'{parent}: must cut exactly one child at the one capsize'
    later = after[re.search(r'\[family\][^\n]*uuid=' + child + r' t=\d+[^\n]* driven out ordered', after).start():]
    if re.search(r'\[aquatic\][^\n]*uuid=' + owner + r'[^\n]* rescue start faller=' + child, later):
        return f'{parent}: the driven child was regathered after the order'
    if re.search(r'\[family\][^\n]*uuid=' + child + r'[^\n]*(?:child separation lost=(?:true|false)|missing kin call)', later):
        return f'{parent}: the driven child called for or looked for the parent that drove it out'
    if re.search(r'\[speech\][^\n]*uuid=' + child + r'[^\n]* line event=(?:lost_parent|caregiver|wait_family)\b', later):
        return f'{parent}: the driven child spoke a parent-seeking line (lost_parent/caregiver/wait_family) after the order'
    # stat branch & rebuff proof
    if branch == 'plead':
        if not re.search(r'\[choreo\][^\n]*uuid=' + child + r'[^\n]* beat=plead face=teary line=mold_plea declared=teary', later):
            return f'{parent}: pleader child did not play beat=plead face=teary line=mold_plea'
        # the rebuff is asserted on the pleader really standing at her (within mouth reach), never on where it was ordered from
        reached = re.search(r'\[family\][^\n]*uuid=' + child + r' t=\d+[^\n]* driven out pleader at parent driver=' + owner + r' distance=(\S+)', later)
        if reached is None:
            gave_up = re.search(r'\[family\][^\n]*uuid=' + child + r' t=\d+[^\n]* driven out pleader gave up short of parent[^\n]*', later)
            return f'{parent}: the pleader never reached her ({gave_up[0][-60:] if gave_up else "no pleader at parent line"})'
        rebuff_beat = 'rebuff_shove face=angry line=none declared=angry' if verdict == 'DRIVE_COLD' else 'rebuff_turn_back face=cry line=none declared=cry'
        if not re.search(r'\[choreo\][^\n]*uuid=' + owner + r'[^\n]* beat=' + rebuff_beat, later[reached.end():]):
            return f'{parent}: parent did not rebuff the pleader standing at her ({rebuff_beat}, reached at distance {reached[1]})'
    if branch == 'stare' and not re.search(r'\[choreo\][^\n]*uuid=' + child + r'[^\n]* beat=stare face=teary line=none declared=teary', later):
        return f'{parent}: freeze child did not play beat=stare face=teary line=none'
    if branch == 'sulk' and not re.search(r'\[choreo\][^\n]*uuid=' + child + r'[^\n]* beat=turn_away face=hmph line=sulk_huff declared=hmph', later):
        return f'{parent}: sulk child did not play beat=turn_away face=hmph line=sulk_huff'
    # the order is answered at once (social orders pre-empt the child's occupants)
    if begins - ordered > 60:
        return f'{parent}: the child began {begins - ordered} ticks after the order (limit 60)'
    # the tearful parent's parting feeling begins at the order, everyone else's from real separation, never before the child is gone
    for early in re.finditer(r'\[emotion\][^\n]*uuid=(\S+) t=(\d+)[^\n]* course begun kind=parting[^\n]* about=' + child, later):
        if early[1] == owner and verdict == 'DRIVE_TEARFUL':
            if int(early[2]) < ordered:
                return f'{parent}: her parting feeling began at {early[2]}, before the order at {ordered}'
        elif int(early[2]) < departed:
            return f'{parent}: someone began the parting feeling at {early[2]}, before the child was gone at {departed}'
    family_uuids = '|'.join([owner] + [_uuid_text(scene, parent + str(i)) for i in (1, 2)])
    for early in re.finditer(r'\[speech\][^\n]*uuid=(?:' + family_uuids + r') t=(\d+)[^\n]* line event=parting_(?:gaze|over)\b', after):
        if int(early[1]) < departed:
            return f'{parent}: parting line fired at {early[1]}, before the child was gone at {departed}'
    sib2 = _uuid_text(scene, parent + '2')
    sib2_dead = re.search(r'\[body\][^\n]*uuid=' + sib2 + r' t=(\d+)[^\n]* death settled', window)
    cries = re.findall(r'\[family\][^\n]* t=(\d+) [^\n]*driven out (?:sibling cries|sibling missed departure) child=' + child, later)
    if not cries and not (sib2_dead and int(sib2_dead[1]) <= turned):
        return f'{parent}: no sibling reacted when the child turned to go'
    if any(int(t) < begins for t in cries):
        return f'{parent}: a sibling cried before the child began to leave'
    # the staged far sibling (beyond sight and hearing, out of her family range) misses the departure and stays silent about it
    if far_actor:
        far = _uuid_text(scene, far_actor)
        if not re.search(r'\[family\][^\n]*uuid=' + far + r' t=\d+[^\n]* driven out sibling missed departure child=' + child + r' reason=out_of_range', later):
            return f'{parent}: the far sibling did not log missing the departure (out_of_range)'
        if re.search(r'uuid=' + far + r'[^\n]*(?:driven out sibling (?:cries|parts)|beat=sibling_cry|beat=parting_begins|course begun kind=parting)', later):
            return f'{parent}: the far sibling reacted to a departure it never perceived'
    # witness rule: a sibling that missed the departure must never get PARTING about this child
    for missed in re.finditer(r'\[family\][^\n]*uuid=(\S+) t=\d+[^\n]* driven out sibling missed departure child=' + child, later):
        sib = missed[1]
        if re.search(r'\[family\][^\n]*uuid=' + sib + r'[^\n]* driven out sibling parts child=' + child, later) \
                or re.search(r'\[emotion\][^\n]*uuid=' + sib + r'[^\n]* course begun kind=parting[^\n]* about=' + child, later):
            return f'{parent}: sibling {sib} missed departure yet received PARTING about {child}'
    # banned range: once departed, the cut child must never claim a home/roof within 16 blocks of the family's home
    after_dep = later[re.search(r'\[family\][^\n]*uuid=' + child + r' t=\d+[^\n]* driven out departed', later).start():]
    for home in re.finditer(r'\[home\][^\n]*uuid=' + child + r'[^\n]* assigned kind=\w+[^\n]*home=GlobalPos\[[^,]+, BlockPos\{x=(-?\d+), y=-?\d+, z=(-?\d+)\}\]', after_dep):
        dx, dz = int(home[1]) - centre[0], int(home[2]) - centre[1]
        if dx * dx + dz * dz < 16 * 16:
            return f'{parent}: cut child assigned home ({home[1]},{home[2]}) within 16 blocks of old home {centre}'
    # the severance is mutual and the child is a stray that lives on: both sides' queries and identity, then within 2 minutes of the
    # departure (the child has left) it does something of its own outside the old range (not the mourning that used to pin it in place)
    if not re.search(r'\[identity\][^\n]*uuid=' + child + r' t=(\d+)[^\n]* changed from=\w+ to=stray cause=severed', after_dep):
        return f'{parent}: the severed child never became a stray (no identity changed to=stray cause=severed after it left)'
    if re.search(r'\[family\][^\n]*uuid=' + child + r'[^\n]*(?:child separation lost=|missing kin call)', after_dep):
        return f'{parent}: the severed child still looked for the parent after it left'
    lived = None
    for change in re.finditer(r'\[status\][^\n]*uuid=' + child + r' t=(\d+) dim=\S+ pos=(-?\d+), -?\d+, (-?\d+) behavior\.behavior: \S+ -> (\w+)', after_dep):
        if int(change[1]) > departed + 2400:
            break
        if change[4] in ('idle', 'mourn', 'mourn_listless', 'mold_leave', 'mold_look_back', 'mold_plead') or change[4].startswith('water'):
            continue
        if (int(change[2]) - centre[0]) ** 2 + (int(change[3]) - centre[1]) ** 2 >= 16 * 16:
            lived = change
            break
    if lived is None:
        return (f'{parent}: the severed child did no stray activity outside the old range within 2400 ticks of its departure '
                f'(departed at {departed}, parting beat at {turned})')
    if verdict == 'DRIVE_TEARFUL':
        parting, _ = at(r'\[choreo\][^\n]*uuid=' + owner + r' t=(\d+)[^\n]* beat=parting_begins face=', later)
        if parting is None or parting < departed:
            return f'{parent}: the tearful parent showed no parting feeling after the child was gone (parting={parting})'
    # each beat's shown face is the declared one (including verdict): no reaction layer painted over a performance
    beats = re.findall(r'\[choreo\][^\n]*uuid=(?:' + owner + '|' + child + r') [^\n]* beat=(\w+) face=(\S+) line=\S+ declared=(\S+)', after)
    wrong = [f'{name} shown={shown} declared={declared}' for name, shown, declared in beats if shown != declared]
    if wrong:
        return f'{parent}: a beat showed another face than the declared one: {wrong[:3]}'
    if not beats:
        return f'{parent}: no beat was logged'
    if departed - turned > 80 and not any(name == 'walk_out' for name, _, _ in beats):
        return f'{parent}: the walk out (beat=walk_out, its face shown) was never logged'
    return None
def _load_warning_choice(window):
    """Both kinds of parent at the load warning (FEATURES 载重): the ordinary parent reads her hull's load warning and takes the
    family ashore to unload before it goes over; the keeper (low intelligence, high friendliness) ignores it and keeps going
    until it capsizes, then keeps all her young. The `early` family's young grow only into the warning: she must turn for the
    bank and land them, with no overload capsize and nobody dismissed."""
    for parent, exceptional in (('early', False), ('kind', True)):
        failure, owner, boats, overloaded_growth = _overload_hull(window, 'hat_load_warning', parent, exceptional)
        if failure:
            return failure
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
            failure = _carried_boarding(window, owner)
            if failure:
                return failure
            continue
        if parent == 'early' and capsized is not None:
            return 'early: the hull capsized from overload though its young grow only into the warning'
        if parent == 'early' and len(re.findall(r'\[action\][^\n]*uuid=' + _uuid_text('hat_load_warning', 'early2')
                                                + r' [^\n]* refused id=hop [^\n]*detail=no_spot', window)) > 1:
            return 'early2: hopped for a hull with no spot more than once'
        # in time: the load warning sends her ashore, the family lands, and nobody is dismissed
        heavy = re.search(r'\[aquatic\][^\n]*uuid=' + owner + r'[^\n]* load heavy hat=' + boats + r'[^\n]* reason=unload_ashore', window)
        if heavy is None:
            return f'{parent}: neither unloaded at the load warning nor capsized'
        if not re.search(r'\[intent\][^\n]*uuid=' + owner + r'[^\n]* end id=come_ashore result=DONE\b', window[heavy.end():]):
            return f'{parent}: turned for the bank at the load warning but never landed the family'
        if dismissals:
            return f'{parent}: dismissed a young without an overload capsize'
        # the trade-off seen without a capsize: the young her full hull left on the bank gets her look back from the hull
        left = re.search(r'\[aquatic\][^\n]*uuid=' + owner + r' [^\n]* set_out left_ashore=\{[^}]*=hull_full', window)
        if left and not re.search(r'\[family\][^\n]*uuid=' + owner + r' [^\n]* overload beat start child=\S+ verdict=KEEP ferry=true', window[left.end():]):
            return f'{parent}: set out leaving a young ashore for a full hull without looking back at it'
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


# Nourishment (ticks) at which young 2 and the head young stand at their start sizes 0.20 and 0.355 on the size curve (family.json
# growth, maturity 336000: 0.25 -> 0.20, then linear to 0.80 at 1.0), so the world's own growth has nothing to add at the start.
_YOUNG2_NOURISHMENT, _HEAD_NOURISHMENT = 84000, 149100
# Growth steps for an overload: the head young to 0.50, young 2 to 0.29, then the head young on in 5000-tick steps (each a roll
# for the hull's load, 10 ticks apart). Nourishment (child index, ticks).
_OVERLOAD_STEPS = ((3, 210000), (2, 120000), (3, 215000), (3, 220000), (3, 225000), (3, 230000), (3, 235000))
# Reliable juvenile growth steps for overload severance: child 3 to 0.500..0.524 (load ratio 2.09..2.65, chance 0.52..0.62
# per step, >99.98% across 10 rolls, while staying <= 0.524 so ParentCarry never refuses too_big even when tired); child 2
# stays at 0.286 (0.37 of capacity on its own after child 3 is cut).
_CROWD_OVERLOAD_STEPS = ((3, 210000), (2, 120000), (3, 211250), (3, 212500), (3, 213750), (3, 215000),
                         (3, 216250), (3, 217500), (3, 218750), (3, 220000))
# One step into the warning only: the head young to 0.375 (load 0.96 of capacity).
_WARNING_STEPS = ((3, 157500),)
# The growth steps start after the smoke's chunk-reload check (tick 60 + 400 unloaded ticks) so the saved riders do not change under it.
_GROWTH_START = 500


def _overload_growth(parent, steps, first=_GROWTH_START):
    """The overload's cause: each step is one real growth step of a young (its own nourishment curve and collision rule), run at a
    tick the scene chooses instead of the world's 100-tick interval; a step the box refuses is tried again (Retry)."""
    return [Retry(f'yukkuri scenario grow {sel(parent + str(child))} {nourishment}', sel(parent + str(child)), tries=4, every=20,
                  first=first + 10 * i)
            for i, (child, nourishment) in enumerate(steps)]


def _overload_family(scene, parent, dz, cabin, label, out=6, stick=True, child3_traits=None,
                     head_childhood=CHILDHOOD_CRAWLING_READY, **traits):
    """Young start at sizes 0.16 (cabin, only with `cabin`), 0.20 and 0.355: just under the load warning for the ordinary parent
    (head 0.355 + brim 0.20: 0.83 of capacity; at 0.18 young 2 takes the cabin instead), so her own load rule seats them. The
    growth is then staged by `_overload_growth` (`_OVERLOAD_STEPS`: a load over twice the hull's capacity with no warning in
    between; young 2 stays under that capacity on its own, so the parent that lost its largest stops overloading while the
    exceptional one keeps all three and overloading; `_WARNING_STEPS`: only the head young grows, the hull sits in its warning
    (0.96) and never overloads). The hat is empty: these scenes show the load, not the larder (`hat_larder`). `traits` pin the
    parent's personality; `child3_traits` optionally pin the largest child's personality; `head_childhood` is the real learned
    state of the head young (the severance scenes send out one that has already learnt to crawl, so it walks out and lives on)."""
    return [yk('marisa', bank_dx(dz), dz, parent, aquatic=True, yaw=-90, lead=True,
               uuid=uid(scene, parent), label=label, **traits, **pair_ready()),
            *[yk('marisa', bank_dx(dz + offset) - 1, dz + offset, parent + str(i), aquatic=True,
                 fam=_seat_family(size, nourishment, age),
                 childhood=head_childhood if i == 3 else CHILDHOOD_CRAWLING_READY,
                 uuid=uid(scene, parent + str(i)), also=(parent + '_crew',),
                 kin=f'Mother:{uuid_nbt(uid(scene, parent))}', label=f'孩子{i}',
                 **(child3_traits if i == 3 and child3_traits else {}), **pair_ready())
              for i, offset, size, nourishment, age in ((1, -1, .16, 22909, 36000), (2, 0, .20, _YOUNG2_NOURISHMENT, 76000),
                                                       (3, 1, .355, _HEAD_NOURISHMENT, 72000)) if cabin or i != 1],
            afloat_now(parent, water_at(dz, out), True, sel_all(parent + '_crew'), stick=stick)]


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


_CURRENT_HEAD = r'\[hat\][^\n]* uuid=(\S+) [^\n]* scenario place [^\n]*place=HEAD\b'
_CURRENT_IMMERSION = r'\[water\][^\n]* damage cause=drown\b'


def current_drift(log):
    points = [float(x) for x in re.findall(r'\[hat\][^\n]* travel at=\(([-\d.]+),', log)]
    if len(points) < 2 or max(points[:200]) - points[0] < 3:
        return 'hat must drift at least 3 blocks downstream in 200 ticks'
    # The young that cannot hop onto the brim rides the captain's head (it is its real place): at grounding it leaves the hat with
    # her, not by itself.
    head = re.search(_CURRENT_HEAD, log)
    if head is None:
        return 'the young must be seated on the head (it cannot hop there itself)'
    captain = re.search(r'\[hat\][^\n]* left rider=(\S+)[^\n]*', log)
    grounding = captain and re.search(rf'\[body\][^\n]*uuid={captain[1]}[^\n]* dismount [^\n]*cause=grounded'
                                        rf'[^\n]*passengers=\[{head[1]}', log)
    if grounding is None:
        return 'the captain must leave the grounded hat still carrying her head young'
    # Her eyes go under in the shoal, so the young on her head falls in with her (rule); it must then get out alive.
    if re.search(rf'\[body\][^\n]*uuid={head[1]}[^\n]* death settled', log):
        return 'the head young must not die after falling in with the captain'
    ashore = r'\[water\][^\n]*uuid={}[^\n]* escape done [^\n]*support=stand='
    after_grounding = log[grounding.end():]
    if not re.search(ashore.format(captain[1]), after_grounding):
        return 'the captain must reach her own dry stand after grounding'
    shed = re.search(rf'\[family\][^\n]*uuid={captain[1]}[^\n]* head rider shed child={head[1]} '
                     r'cause=(?:carrier_eyes_under|carrier_fell_in)\b', after_grounding)
    stand = shed and re.compile(ashore.format(head[1])).search(after_grounding, shed.end())
    if shed and stand is None:
        return 'the shed head young must reach its own dry stand'
    hits = list(re.finditer(_CURRENT_IMMERSION, log))
    if hits:
        hit = hits[0]
        if (len(hits) > 1 or f'uuid={head[1]} ' not in hit[0] or stand is None
                or not grounding.end() + shed.end() <= hit.start() < grounding.end() + stand.start()):
            return 'only the shed head young may take one immersion hit before its first dry stand'
    back = re.search(r'\[headwear\][^\n]* received loose accessory', log)
    if back and re.search(r'\[action\][^\n]* start id=pick\b', log[:back.start()]):
        return 'the owner went for loose items before taking her hat back'


pond('hat_current', '帽子顺缓流漂到浅滩搁浅', '帽子顺缓流漂到浅滩搁浅',
     '浅溪上游一排水源形成同向水流，浅滩两侧的石阶通向草岸；船主乘帽随水流缓慢漂动，碰到岸边只停住。没有落水、流向冲突或猛撞时不会翻船',
     '约 30 秒',
     [*flow_channel(0),
      *(box(bank_dx(0) + 2, -1, z, bank_dx(0) + 9, -1, z, f'minecraft:cobblestone_stairs[facing={facing}]')
        for z, facing in ((-2, 'north'), (2, 'south'))),
      captain(dx=bank_dx(0) - 1, social_wait=100),
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
                        r'\[headwear\][^\n]* received loose accessory'], verify=current_drift, gating_checks=['END'],
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
     '水生魔理沙的帽子在离岸 12 格的水面上，她自己下水：她朝自己的帽子游（一阵用力一阵歇，花体力），嘴或辫梢够到帽檐就停，咬住帽檐、按片段节拍把自己拉进帽腔坐好；在帽里不再算落水',
     '约 30 秒',
     [captain(dx=bank_dx(0)), afloat_now('a', water_at(0, 12), False)],
     note='长泳消耗体力；向帽子游超过 600 tick 或用尽办法才转向近岸。日志：[intent] start id=escape_water → [method] pick method=own_hat → [action] end id=swim … detail=grip → [action] start id=climb（rise、climbRise、grip）→ [body] mount cause=climb → [intent] end result=DONE。读 water.distress 的阶段，读 hat.float 看固定水线与翻覆状态。',
     reads=[('读船主', f'yukkuri status {sel("a")} raw'), ('读帽子', f'data get entity {_HAT}')],
     smoke=dict(ticks=1800, expect=[r'\[hat\][^\n]* scenario afloat prepared [^\n]*owner_aboard=false',
                                   # After the start hold she reclaims the hat at once (walk in, swim): the same path shore_capsized_far asserts.
                                   r'\[action\][^\n]* start id=step_in\b', r'\[intent\][^\n]* start id=escape_water\b',
                                   r'\[action\][^\n]* start id=swim owner=drive:aquatic\b',
                                   r'\[intent\][^\n]* end id=resolve_own_hat result=DONE\b', r'\[water\][^\n]* phase=\w+ -> RECOVER',
                                   r'\[action\][^\n]* end id=climb outcome=DONE\b',
                                   r'\[method\][^\n]* pick intent=escape_water method=(?:own_hat|right_own_hat)\b'],
                forbid=_WATER_SMOKE_FORBID, verify=water_aftermath, readout=_water_readouts('a')))

# A shallow runnel drains west from the pond onto a level bed. Reimu is wet and still in the last flowing cell.
pond('shake_ashore', '落水灵梦爬上岸甩水', '落水灵梦爬上岸甩水',
     '普通灵梦浑身湿透，还在池边浅水沟的末端。看它自己划上有实地承托的干岸，站稳喘匀后甩一次水，湿度减半。',
     '约 20–40 秒',
     [box(bank_dx(0) - 9, -1, 0, bank_dx(0), -1, 0, 'minecraft:air'),
      *[block(bank_dx(0) + 1 - level, -1, 0, f'minecraft:water[level={level}]') for level in range(1, 8)]],
     # Reimu falls into the last flowing cell after the start hold: a body held there would be carried out of the water by the current
     # before the player looks, and the escape from the water is the beat.
     late=[yk('reimu', bank_dx(0) - 6.45, 0, 'a', dy=-1, yaw=90, lead=True, **pair_ready()),
           set_status('a', 'needs.wetness', 32)], note=NEW, vantage=(bank_dx(0) - 9, 3, -135, 15),
     reads=[('读湿度与甩水', f'yukkuri status {sel("a")} raw')],
     capture=dict(shots=(1, 40, 400, 800), sequences=((40, 800, 4),), observe=False,
                  follow='@e[tag=scn_a,limit=1]', offset=(-1.6, 1.0, 2.2)),
     smoke=dict(ticks=1200, expect=[*_WATER_ESCAPE, r'\[water\][^\n]* escape done [^\n]*support=stand=',
                                   r'\[weather\][^\n]* shaking water off wetness=[^\n]* after=water\b',
                                   r'\[weather\][^\n]* shook the water off wetness='],
                forbid=_WATER_SMOKE_FORBID, readout=_water_readouts('a')))

def _ledge_spit():
    """A 3 x 5 sandstone spit out in the pond (stand y 64) and, hard against its east side, a broad bank one block higher (stand y 65):
    water on three sides and a full-block ledge on the fourth, with an apple on the bank."""
    x0 = bank_dx(0) + 3
    return [box(x0, -1, -2, x0 + 2, -1, 2, 'minecraft:sandstone'),
            box(x0 + 3, -1, -5, x0 + 12, 0, 5, 'minecraft:sandstone'),
            box(x0, 0, -2, x0 + 2, 3, 2, 'minecraft:air'),
            box(x0 + 3, 1, -5, x0 + 12, 4, 5, 'minecraft:air'),
            item('minecraft:apple', 1, x0 + 8, 0, 'food', dy=1.05)]


# A hungry body on a spit, its food on the bank a full block above: the stand it walks to lies at the food's height, so it steps up.
pond('ledge_spit', '沙洲上的饿帕秋莉够堤上的苹果', '沙洲上的饿帕秋莉够堤上的苹果',
     '饿着的帕秋莉站在水中一块 3×5 的沙洲上，三面是水，东面是比沙洲高一格的堤岸，堤上有一个苹果。看她直接走到堤边、踏上堤岸去吃苹果，而不是在沙洲上原地"没有路"地发呆',
     '约 10–20 秒',
     [*_ledge_spit(), yk('patchouli', bank_dx(0) + 4, 0, 'a', yaw=-90, nutrition=5, energy=90, boldness=55)],
     note=NEW, vantage=(bank_dx(0) - 3, 3, -90, 15),
     reads=[('读营养与体力', f'yukkuri status {sel("a")} raw')],
     smoke=dict(ticks=1200, expect=[r'\[meal\][^\n]* swallowed item=minecraft:apple '],
                forbid=_WATER_SMOKE_FORBID + [r'\[meal\][^\n]* food approach refused'], readout=_water_readouts('a')))

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
      yk('marisa', bank_dx(0) + 3, 0, 'c', dy=1, stage='child', aquatic=True, yaw=-90, lead=True, skills={'sailing': 2},
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

_TEARFUL = dict(intelligence=55, friendliness=20, attachment=65, self_control=45)
_COLD = dict(intelligence=55, friendliness=20, attachment=15, self_control=40)
_PLEAD_KID = dict(attachment=75, friendliness=60, boldness=45, self_control=70)
_FREEZE_KID = dict(attachment=45, friendliness=60, boldness=35, self_control=70)
_SULK_KID = dict(attachment=20, friendliness=60, boldness=45, self_control=70)

_SEVER_FORBID = [r'\[body\][^\n]* invariant\b', r'\[hat\][^\n]* capsized cause=(?:shake|turbulence)\b',
                 r'\[action\][^\n]* refused id=hop [^\n]*detail=no_spot',
                 r'\[needs\][^\n]* starvation damage accepted=true',
                 r'\[body\][^\n]* death settled[^\n]*cause=(?!.*(?:drown|water))']
_SEVER_EXPECT = [r'\[hat\][^\n]* scenario afloat prepared [^\n]*young=2\b[^\n]*food=0\b',
                 r'\[hat\][^\n]* scenario place [^\n]*place=HEAD\b',
                 r'\[hat\][^\n]* scenario place [^\n]*place=BRIM\b',
                 r'\[hat\][^\n]* capsized cause=too_heavy\b[^\n]*thrown=[1-9]\b',
                 r'\[water\][^\n]* phase=(?:STRUGGLE|EXHAUSTED) -> RECOVER\b']
# The head seat is the overflow for a young that cannot hop onto the brim by itself, and one that can hop always takes the brim
# (PlacedHatEntity.places). The driven-out young here has already learnt to crawl, so it hops and sits on the brim; the smaller
# young that has not learnt yet rides on the mother's head.
_SEVER_SEATS = ((2, 'HEAD'), (3, 'BRIM'))


def _severance_scene(sid, title, watch, parent, label, parent_traits, child3_traits, verdict, branch, *, out=3.5, first=120, far_sibling=False):
    # The driven-out young is the one that has already learnt to crawl: it walks out at its own gait and can live as a stray at
    # once (a newborn that never practised crawling stays where it is put, and the severance shows nothing).
    actors = [*_overload_family(sid, parent, 0, False, label, out=out, stick=True, child3_traits=child3_traits,
                                head_childhood=CHILDHOOD_HOPPING, **parent_traits)]
    far_actor = None
    if far_sibling:
        far_actor = 'normal_far'
        # Third young 16 blocks west of the straight west bank (inside parent's 24-block search, beyond 12-block sight and
        # behind a 3-high stone wall) with water and apples so it stays put and misses the departure.
        actors += [
            yk('marisa', bank_dx(0) - 16, 0, far_actor, aquatic=True, fam=_seat_family(.20, _YOUNG2_NOURISHMENT, 76000),
               childhood=CHILDHOOD_CRAWLING_READY, uuid=uid(sid, far_actor), kin=f'Mother:{uuid_nbt(uid(sid, parent))}',
               label='远处的孩子', **pair_ready()),
            box(bank_dx(0) - 14, 0, -2, bank_dx(0) - 14, 2, 2, 'minecraft:stone'),
            box(bank_dx(0) - 17, 0, -1, bank_dx(0) - 17, 1, 1, 'minecraft:oak_fence'),
            box(bank_dx(0) - 17, 0, -2, bank_dx(0) - 15, 1, -2, 'minecraft:oak_fence'),
            box(bank_dx(0) - 17, 0, 2, bank_dx(0) - 15, 1, 2, 'minecraft:oak_fence'),
            block(bank_dx(0) - 16, 0, -1, 'mcyukkuri:waterer{Water:8}'),
            item('minecraft:apple', 2, bank_dx(0) - 16, 1, 'far_food'),
        ]
    families = ((parent, verdict, branch, far_actor, _SEVER_SEATS),)
    pond(sid, title, title, watch, '约 2 分钟，建议倍速', actors,
         late=[f'execute as {sel(parent)} on vehicle run tag @s add seat_reload',
               *_overload_growth(parent, _CROWD_OVERLOAD_STEPS, first=first)],
         vantage=(-7, 7, -135, 16),
         capture=dict(shots=(1, 300, 1800, 4600), sequences=((200, 4600, 200), (800, 2000, 4))),
         note='单户超载断亲：西岸直岸居中，右侧水面翻船，左侧干地走远。日志依次 overload verdict -> overload beat start -> driven out ordered -> driven out begins -> 分支 beat -> turn_away -> departed -> parent dismissed -> parting_begins（含泪亲油）。',
         reads=[(f'读亲油（{label}）', f'yukkuri status {sel(parent)} raw'),
                ('读最大孩子', f'yukkuri status {sel(parent + "3")} raw'),
                ('读载重帽', 'data get entity @e[tag=seat_reload,limit=1]')],
         smoke=dict(ticks=7200,
                    expect=list(_SEVER_EXPECT),
                    forbid=list(_SEVER_FORBID),
                    verify=lambda window, s=sid, f=families: _overload_tradeoff(window, s, f) or _escape_needs_place(window) or _bank_pickup_retries(window),
                    readout=[f'yukkuri status {sel(who)} raw' for who in (parent, parent + '2', parent + '3')]
                            + ['data get entity @e[tag=seat_reload,limit=1] Riders',
                               'data get entity @e[tag=seat_reload,limit=1] Motion',
                               'data get entity @e[tag=seat_reload,limit=1] Inverted']))


_severance_scene('hat_sever_tearful', '超载断亲：含泪转身与远手足',
                 '翻船后含泪亲油赶走最大孩子，孩子哀求被转身拒绝后走远断亲，墙后远手足无反应',
                 'normal', '含泪·央求', _TEARFUL, _PLEAD_KID, 'DRIVE_TEARFUL', 'plead', out=8.0, first=80, far_sibling=True)
_severance_scene('hat_sever_cold', '超载断亲：冷淡推开',
                 '翻船后冷淡亲油赶走最大孩子，孩子哀求被推开后转身走远断亲',
                 'cold', '冷淡·央求', _COLD, _PLEAD_KID, 'DRIVE_COLD', 'plead')
_severance_scene('hat_sever_freeze', '超载断亲：僵住凝望',
                 '翻船后含泪亲油赶走最大孩子，孩子僵住凝望后转身走远断亲',
                 'freeze', '含泪·僵望', _TEARFUL, _FREEZE_KID, 'DRIVE_TEARFUL', 'stare')
_severance_scene('hat_sever_sulk', '超载断亲：赌气哼走',
                 '翻船后冷淡亲油赶走最大孩子，孩子赌气哼一声转身走远断亲',
                 'sulk', '冷淡·哼走', _COLD, _SULK_KID, 'DRIVE_COLD', 'sulk')

pond('hat_load_warning', '载重警戒，亲油靠岸卸人', '载重警戒，亲油靠岸卸人',
     '两位亲油各带孩子：北侧普通亲油看到帽船吃水下沉、多晃（载重警戒）就划回岸边卸下全家，再出航只带载得下的孩子；南侧低智力高友善的亲油不理会警戒，载着全家直到翻船，之后仍认全部孩子',
     '约 5 分钟，建议倍速',
     [*_overload_family('hat_load_warning', 'early', -4, False, '智55·友善70', intelligence=55, friendliness=70),
      *_overload_family('hat_load_warning', 'kind', 4, True, '智20·友善80', intelligence=20, friendliness=80)],
     late=[f'execute as {sel("early")} on vehicle run tag @s add seat_reload',
           *_overload_growth('early', _WARNING_STEPS), *_overload_growth('kind', _OVERLOAD_STEPS)],
     capture=dict(shots=(1, 600, 2400, 5200), sequences=((600, 5200, 200), (1400, 2600, 4))),
     note='本场景不断亲：北侧普通亲油演的是「卸人」这一取舍——先看到她皱眉哭一声再划回岸卸人，再出航时载不下的最大孩子留在岸上，她在帽上回头看它一眼，孩子按性格哭、哼或发火（日志 overload beat start … ferry=true → overload left ashore child answers）；南侧不理会警戒、翻船后全认，不演取舍。'
          '日志 [aquatic] load heavy … reason=unload_ashore → come_ashore 结束 DONE → set_out left_ashore（hull_full）；另一位记 capsized cause=too_heavy 与 overload keeps children。'
          '读 load changed 看载重、概率与抽值；不强制翻船或死亡，真实碰撞、拒绝和水伤保留，整窗不得有人饿死。',
     reads=[('读北侧普通亲油（只进警戒）', f'yukkuri status {sel("early")} raw'),
            ('读低智力高友善亲油', f'yukkuri status {sel("kind")} raw'),
            ('读低智力高友善亲油的最大孩子', f'yukkuri status {sel("kind3")} raw'),
            ('读载重帽', 'data get entity @e[tag=seat_reload,limit=1]')],
     smoke=dict(ticks=6000, reload=(60, '@e[tag=seat_reload,limit=1]'),
                expect=[r'\[hat\][^\n]* scenario afloat prepared [^\n]*young=3\b[^\n]*food=0\b',
                        r'\[hat\][^\n]* scenario place [^\n]*place=CABIN\b[\s\S]*\[hat\][^\n]* scenario place [^\n]*place=HEAD\b[\s\S]*\[hat\][^\n]* scenario place [^\n]*place=BRIM\b',
                        r'\[hat\][^\n]* load warning ratio=[\s\S]*\[hat\][^\n]* capsized cause=too_heavy\b[^\n]*thrown=[1-9]\b',
                        r'\[water\][^\n]* phase=(?:STRUGGLE|EXHAUSTED) -> RECOVER\b'],
                forbid=[r'\[body\][^\n]* invariant\b', r'\[hat\][^\n]* capsized cause=(?:shake|turbulence)\b',
                        # the early family's brim young, left ashore by the load rule, may walk up once and find the hull taken
                        r'\[action\][^\n]* uuid=(?!' + _uuid_text('hat_load_warning', 'early2') + r')\S+ [^\n]* refused id=hop [^\n]*detail=no_spot',
                        r'\[needs\][^\n]* starvation damage accepted=true',
                        r'\[body\][^\n]* death settled[^\n]*cause=(?!.*(?:drown|water))'],
                verify=lambda window: _load_warning_choice(window) or _escape_needs_place(window) or _bank_pickup_retries(window),
                readout=[f'yukkuri status {sel(who)} raw'
                         for who in ('early', 'kind', 'early2', 'early3', 'kind1', 'kind2', 'kind3')]
                        + ['data get entity @e[tag=seat_reload,limit=1] Riders',
                           'data get entity @e[tag=seat_reload,limit=1] Motion',
                           'data get entity @e[tag=seat_reload,limit=1] Inverted']))

pond('hat_larder', '捞苹果存进帽里，再取食', '捞苹果存进帽里，再取食',
     '帽里一开始是空的：亲油划向水面漂着的苹果，叼起放进帽底，帽底的苹果图随存量变多。之后饿的孩子被喂或自己吃时，看她从帽里取粮',
     '约 2–3 分钟',
     [yk('marisa', bank_dx(0), 0, 'a', aquatic=True, yaw=-90, lead=True, uuid=uid('hat_larder', 'a'), label='帽里空粮', **pair_ready()),
      yk('marisa', bank_dx(-1) - 1, -1, 'k1', aquatic=True, fam=_seat_family(.16, 22909, 36000), childhood=CHILDHOOD_CRAWLING_READY,
         uuid=uid('hat_larder', 'k1'), also=('crew',), kin=f'Mother:{uuid_nbt(uid("hat_larder", "a"))}', label='孩子1', **pair_ready()),
      yk('marisa', bank_dx(1) - 1, 1, 'k2', aquatic=True, fam=_seat_family(.20, 84000, 76000), childhood=CHILDHOOD_CRAWLING_READY,
         uuid=uid('hat_larder', 'k2'), also=('crew',), kin=f'Mother:{uuid_nbt(uid("hat_larder", "a"))}', label='孩子2·饿',
         **{**pair_ready(), 'nutrition': 35}),
      item('minecraft:apple', 1, bank_dx(0) + 10, -1, name='apple1'),
      item('minecraft:apple', 1, bank_dx(0) + 11, 0, name='apple2'),
      item('minecraft:apple', 1, bank_dx(0) + 10, 1, name='apple3'),
      afloat_now('a', water_at(0, 6), True, sel_all('crew'), stick=True)],
     vantage=(bank_dx(4) - 1, 4, -125, 15),
     note='日志 [intent] start id=forage → [aquatic] forage taken … stored → [hat] stores put … total=0 -> 1 → 孩子吃或被喂时 stores taken … use=eat|feed；帽里存到两份就不再捞，翻覆才会散出。'
          '亲油自己不饿，捞到的苹果放进帽里而不是吃掉；一次没看到存粮就重开。',
     reads=[('读亲油', f'yukkuri status {sel("a")} raw'), ('读饿孩子', f'yukkuri status {sel("k2")} raw'),
            ('读帽子', f'data get entity {_HAT}')],
     smoke=dict(ticks=3600,
                expect=[r'\[hat\][^\n]* scenario afloat prepared [^\n]*young=2\b[^\n]*food=0\b',
                        r'\[intent\][^\n]* start id=forage\b',
                        r'\[aquatic\][^\n]* forage taken item=\S+ stored\b',
                        r'\[hat\][^\n]* stores put [^\n]*total=0 -> 1\b',
                        r'\[hat\][^\n]* stores taken [^\n]*use=(?:eat|feed)\b'],
                forbid=[r'\[body\][^\n]* invariant\b', r'\[hat\][^\n]* stores spilled\b', r'\[needs\][^\n]* starvation damage accepted=true',
                        r'\[body\][^\n]* death settled\b'],
                readout=[f'yukkuri status {sel("a")} raw', f'yukkuri status {sel("k2")} raw', f'data get entity {_HAT} Motion']))

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


_FED_NEWBORN = 'Version:2,Newborn:1b,Landed:1b,FirstMealDone:1b,FirstToiletDone:1b,Crawled:0b'
pond('afloat_family', '亲油用辫子抱赤油上帽船', '亲油用辫子抱赤油上帽船',
     '亲油与一只已完成首餐、首便、正在学爬且不会跳的赤油在岸边，附近有木棍：亲油取棍、集合、放帽，用辫子抓起岸边赤油带它跳上近侧帽檐，再降到前座，最后自己到船尾座位；两只实际入座后出航',
     '约 1–2 分钟',
     [yk('marisa', bank_dx(0) - 1, 0, 'a', aquatic=True, yaw=-90, lead=True, uuid=uid('afloat_family', 'a'), **pair_ready()),
      _aquatic_young('afloat_family', 'c1', bank_dx(1), 1, 'baby', 'a', childhood=_FED_NEWBORN),
      item('minecraft:stick', 1, bank_dx(0) - 1, -1, 'stick')],
     note='日志：set_out → placed → grab reach=braid → carry grip → 亲油 mount → carry lowering → 赤油 seat landed → 亲油 hop 到座位 → set_out complete aboard=1/1。' + _DESIRE_NOTE,
     vantage=(bank_dx(0) - 4, 4, -135, 18),
     capture=dict(shots=(1, 40, 2400), sequences=((40, 2400, 4),), observe=False,
                  views={'port': dict(follow='@e[type=mcyukkuri:placed_hat,limit=1]', offset=(1.15, .55, -.75)),
                         'starboard': dict(follow='@e[type=mcyukkuri:placed_hat,limit=1]', offset=(-1.15, .55, .75))}),
     reads=[('读亲油', f'yukkuri status {sel("a")} raw'), ('读赤油', f'yukkuri status {sel("c1")} raw'),
            ('读帽子', f'data get entity {_HAT}')],
     smoke=dict(ticks=3600, expect=[r'\[intent\][^\n]* start id=set_out\b', r'\[intent\][^\n]* end id=set_out result=DONE\b',
                                   r'\[aquatic\][^\n]* set_out complete [^\n]*aboard=1/1\b',
                                   r'\[hat\][^\n]* boarded rider=[^\n]*own=true\b[\s\S]*'
                                   r'\[body\][^\n]* seat landed [^\n]*cause=braid_set_down',
                                   r'\[family\][^\n]* carry grip [^\n]*hold=braid\b[\s\S]*'
                                   r'\[family\][^\n]* carry lowering hold=braid\b[\s\S]*'
                                   r'\[body\][^\n]* seat landed [^\n]*cause=braid_set_down'],
                forbid=[r'death settled into remains', r'\[body\][^\n]* invariant\b', r'\[aquatic\][^\n]* set_out left_ashore=',
                        r'\[action\][^\n]* end id=grab outcome=BLOCKED [^\n]*detail=braid_grip_out_of_reach',
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
     '约 2 分钟；可在「实用」页调整天气再看受惊',
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


def pushed(name, dx, dz=0.0):
    """A late shove off the bank into the pond through the body's own push. A raw Motion merge cannot move a crawler (the
    crawl gait rewrites the horizontal velocity every tick), so the shove is the same nudge another body would give."""
    return f'yukkuri scenario push {sel(name)} {dx} {dz}'


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
     late=[pushed('c1', .5), Retry(pushed('k1', .5), sel('k1'), tries=1, first=RESCUE_BABY_DELAY)], vantage=(bank_dx(0) - 2, 1, -90, 18),
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
     note='初态：子油位于初航欲望带，尚未学会航行，固食、跳跃与学习条件已具备，自己的帽里有真实木棍。亲油近岸低体力歇泊，子油从亲帽的真实座位开始初航。'
          '日志：子油 [intent] start id=first_voyage（why 中 boldness、size、ratio）→ [aquatic] first_voyage start … from=parent_hat → [action] end id=hop outcome=DONE（离亲帽）→ [action] start id=walk（找船位）→ '
          '[aquatic] berth → [hat] placed … water=true kit=minecraft:stick → [action] start id=hop → [body] mount … → [aquatic] route purpose=first_voyage → '
          '[action] start id=paddle → [aquatic] first_voyage rounded → [intent] start id=come_ashore → [action] start id=hop → [action] start id=take_prop → '
          '[skill] learned ability=SAILING grade=1 source=voyage → [aquatic] first_voyage success。帽子漂离岸沿时子油或亲油在岸边俯身用辫子划水把帽子招回：[action] start id=beckon … → '
          '[action] end id=beckon outcome=DONE detail=at_bank（招失败后同一次不再招，改等漂近）；帽子靠岸记 [hat] moored，帽檐搭上岸沿记 [hat] beached。'
          '初航记 [aquatic] first_voyage fail … in_water=true 与 first_voyage cooldown（第二天早上前不再试）。' + _DESIRE_NOTE,
     reads=[('读子油', f'yukkuri status {sel("c1")} raw'), ('读航行技能', f'data get entity {sel("c1")} YukkuriSkills'),
            ('读亲油', f'yukkuri status {sel("a")} raw'), ('读最近的帽子', f'data get entity {_HAT}')],
     smoke=dict(ticks=4800, expect=[r'\[hat\][^\n]* scenario afloat prepared [^\n]*young=1\b',
                                   rf'\[aquatic\][^\n]*uuid={_uuid_text("first_voyage", "c1")}[^\n]* first_voyage start [^\n]*from=parent_hat\b'
                                   rf'[\s\S]*\[action\][^\n]*uuid={_uuid_text("first_voyage", "c1")}[^\n]* end id=hop outcome=DONE'
                                   rf'[\s\S]*\[hat\][^\n]* placed owner={_uuid_text("first_voyage", "c1")}\b',
                                   r'\[body\][^\n]* mount [^\n]*cause=hop_contact\b',
                                   r'\[aquatic\][^\n]* first_voyage success\b',
                                   r'\[skill\][^\n]* learned ability=SAILING grade=1 source=voyage'],
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
     note='初态：子油位于初航欲望带，尚未学会航行，固食、跳跃与学习条件已具备，自己的帽里有真实木棍；亲油在离岸 4 格以外的静水里低体力歇泊，'
          '帽里有四份苹果和一根木棍，子油站在亲帽帽檐的真实位置上，岸边没有可跳的落点。'
          '日志：子油 [intent] start id=first_voyage → [aquatic] first_voyage start … from=parent_hat → [aquatic] first_voyage route=brim reason=no_bank_in_hop_reach → '
          '[action] end id=face → [action] start id=place_prop → [hat] placed … water=true kit=minecraft:stick → '
          '[aquatic] first_voyage hat set on water → [action] start id=hop（亲帽帽檐跳进自己的帽，hop_leg 片段）→ [body] mount … cause=hop_contact → '
          '[aquatic] first_voyage hopped onto own hat … aboard=true → [aquatic] route purpose=first_voyage → [action] start id=paddle → [aquatic] first_voyage rounded → '
          '[intent] start id=come_ashore → … → [action] start id=take_prop → [skill] learned ability=SAILING grade=1 source=voyage → [aquatic] first_voyage success … route=brim。'
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
    'water_colony', 'sail', 'afloat_stick', 'cargo', 'hat_larder',
    'afloat_family', 'afloat_family_gather', 'afloat_family_ashore', 'shore_family',
    'kid_boat_play', 'boat_toilet', 'first_voyage', 'first_voyage_brim',
    'hat_sever_tearful', 'hat_sever_cold', 'hat_sever_freeze', 'hat_sever_sulk', 'hat_load_warning',
    'self_rescue', 'shake_ashore', 'ledge_spit', 'worn_hat_boat', 'rescue_child', 'rescue_no_seat',
    'birth_boat_missed', 'birth_hat_own', 'shore_capsized', 'shore_capsized_far',
    'hat_swap', 'shake_capsize', 'hat_push', 'hat_current',
    'hat_grounded_capsized')
assert sorted(POND_TOUR) == sorted(scenario.id for scenario in BY_ID['pond'].scenarios), 'the pond tour must list every pond scene'
BY_ID['pond'].scenarios.sort(key=lambda scenario: POND_TOUR.index(scenario.id))



# A weak body (health 30%) crawls to its food; a starving one has an apple only on top of a two-block pillar (a hop it no longer has)
# and a second one on the ground nearer than the pillar's foot; it turns to that one and eats it.
_FG = {name: uid('frail_gait', name) for name in ('weak', 'gap')}
add('frail_gait', 'body', '重伤爬着去吃，饿虚弱的够不到高处', '重伤爬行、虚弱不蹦跳',
    '左：生命3/10的灵梦离苹果5格，改用爬行走过去吃。中：营养5的虚弱灵梦只有两格高石柱顶上有苹果（它不再蹦跳，上不去），身边地面另有一个，看它改去吃地面的那个',
    '约 1 分钟', [
        yk('reimu', -10, 0, 'weak', label='生命3·爬行', nutrition=40, energy=95, uuid=_FG['weak'], lead=True),
        item('minecraft:apple', 1, -7, 0, 'weak_food'),
        yk('reimu', -10, 8, 'gap', label='营养5·虚弱', nutrition=5, energy=95, uuid=_FG['gap']),
        block(-5, 0, 8, 'minecraft:stone'), block(-5, 1, 8, 'minecraft:stone'), item('minecraft:apple', 1, -5, 8, 'gap_food', dy=2.05),
        item('minecraft:apple', 1, -13, 11, 'gap_ground'),],
    late=[set_status('weak', 'health.health', 3)],
    env=DAY, vantage=(0, 16), reads=[(f'读{n}', f'yukkuri status {sel(n)} raw') for n in ('weak', 'gap')])


def _verify_frail_gait(window, trace):
    weak, gap = _clips(trace, _uuid_text('frail_gait', 'weak')), _clips(trace, _uuid_text('frail_gait', 'gap'))
    # The health is set a few seconds after spawn: judge the weak body from the tick its weakness began.
    began = re.search(rf'\[body\][^\n]* uuid={_uuid_text("frail_gait", "weak")} t=(\d+) [^\n]*weakness false -> true', window)
    if not began:
        return 'The weak body never became weak'
    ended = re.search(rf'\[body\][^\n]* uuid={_uuid_text("frail_gait", "weak")} t=(\d+) [^\n]*weakness true -> false', window)
    weak = [row for row in weak if int(began[1]) <= row[0] < (int(ended[1]) if ended else 1 << 60)]
    # It stays frail only while starving: its first swallow lifts it above the starvation line.
    fed = re.search(rf'\[meal\][^\n]* uuid={_uuid_text("frail_gait", "gap")} t=(\d+) [^\n]*swallowed', window)
    gap = [row for row in gap if row[0] < (int(fed[1]) if fed else 1 << 60)]
    if not re.search(rf'\[move\][^\n]* uuid={_uuid_text("frail_gait", "weak")} [^\n]*gait crawl cause=weak\b', window):
        return 'The weak body never crawled'
    for name, rows in (('weak', weak), ('gap', gap)):
        if hops := [row for row in rows if row[2] in _HOP_CLIPS]:
            return f'The {name} body hopped: {hops[0]}'
    if not re.search(rf'uuid={_uuid_text("frail_gait", "weak")} [^\n]*swallowed item=minecraft:apple', window):
        return 'The weak body never reached its apple'
    if not fed:
        return 'The starving body never ate the ground apple (it cannot reach the pillar apple)'


next(s for s in S if s.id == 'frail_gait').smoke = dict(
    ticks=1800, expect=[], forbid=[r'\[body\][^\n]* invariant\b'], verify=_verify_frail_gait, verify_trace=True)


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
        assert int(match[2]) > last[field_of[match[1]]], \
            (sid, number, 'numbers are append-only: an entry takes a new index after the ones before it')
        last[field_of[match[1]]] = int(match[2])
    for scenario in S:
        assert scenario.id in numbers, f'scene {scenario.id} has no number in museum_scene_numbers.json'
        scenario.number = numbers[scenario.id]
        assert field_of[scenario.number.split('-')[0]] == scenario.field, (scenario.id, scenario.number, scenario.field)


_foster_parent = uid('kindness_adoption', 'missing')
_foster_adult = uid('kindness_adoption', 'foster')
_foster_child = uid('kindness_adoption', 'orphan')
_orphan_memory = (f'Mother:{uuid_nbt(_foster_parent)},KinAbsence:[{{Kin:{uuid_nbt(_foster_parent)},'
                  'Seen:0L,Dimension:"minecraft:overworld",Away:2100,Phase:5,Confirmed:1b}]')
add('kindness_adoption', 'family', '孤儿分食与认养亲', '孤儿分食与认养亲',
    '赤油确认原来的亲油已死。邻居嘴里只有一份苹果，先看她肯不肯递给赤油。赤油吃下后，看邻居是否接纳它，再到族谱里看养亲和原有亲缘。',
    '约 10–30 秒', [
        *shed(0, 0, half=4), *large_nest(0, 0, 'mcyukkuri:large_yukkuri_nest'),
        yk('reimu', -1.8, 0, 'foster', uuid=_foster_adult, friendliness=100, yaw=-90, lead=True,
           extra='YukkuriMouth:{Purpose:"MEAL",Items:[{id:"minecraft:apple",count:1}]}'),
        yk('reimu', -1.37, 0, 'orphan', uuid=_foster_child, stage='baby', nutrition=23, yaw=90, kin=_orphan_memory,
           childhood=CHILDHOOD_CRAWLING_READY),
        f'data merge entity {sel("foster")} {{YukkuriLife:{{MealTicks:1}}}}',
        home('foster', 0, 0),
    ], env=DAY, spot='pet_birth', vantage=(-5, 2, -120), reads=[
        ('读赤油营养', f'yukkuri status {sel("orphan")} raw'), ('读邻居照护', f'yukkuri status {sel("foster")} raw')])

_basket_comparison = 'basket_weakness'
_basket_display_yukkuri = [
    ('marisa', 'adult', True), ('reimu', 'adult', True),
    ('reimu', 'baby', True), ('chen', 'child', True), ('marisa', 'child', False),
]
_basket_display_frames = ['empty', 'plain', 'fine', 'large']
_basket_display_shopkeeper = 'basket_display_shopkeeper'
_basket_display_plain_frame = sel('basket_frame_plain')
_basket_display_fine_frame = sel('basket_frame_fine')
add(_basket_comparison, 'town', '运输篮：健康、受伤与药睡', '运输篮：健康、受伤与药睡',
    '先用空篮点健康油，再看受伤油的入篮反应。去石地放出受伤油。给第三只喷雾，趁没睡点一次，睡着后再试，随后也放回石地。用另一排药睡的个体试三种篮，先在手上看，再把装好的篮放上展示框，从正面辨认篮里的个体。',
    '喷雾约 5 秒后入睡', [
        yk('reimu', -5, 0, 'basket_healthy', uuid=uid(_basket_comparison, 'basket_healthy'), label='健康·陌生', lead=True),
        yk('reimu', 0, 0, 'basket_injured', uuid=uid(_basket_comparison, 'basket_injured'), label='受伤·陌生', extra='Health:4.0f'),
        yk('reimu', 5, 0, 'basket_sprayed', uuid=uid(_basket_comparison, 'basket_sprayed'), label='待喷雾·陌生'),
        box(-6, -1, 4, -2, -1, 7, 'minecraft:stone'),
        box(-2, 1, -8, 1, 1, -8, 'minecraft:stripped_oak_wood'),
        *[f'summon minecraft:item_frame ~{i - 1.5} ~1.5 ~-8.5 '
          f'{{Facing:2b,Tags:["scn","{tag("basket_frame_" + name)}"]'
          + (',Item:{id:"mcyukkuri:yukkuri_carrier",count:1}' if name == 'empty' else '') + '}'
          for i, name in enumerate(_basket_display_frames)],
        *[yk(kind, -3 + 1.5 * i, 2, f'basket_display_{i}', stage=stage, accessory=accessory,
             uuid=uid(_basket_comparison, f'basket_display_{i}'),
             extra='YukkuriBody:{HairLoss:1}' if not accessory else '')
          for i, (kind, stage, accessory) in enumerate(_basket_display_yukkuri)],
        *[f'data merge entity {sel(f"basket_display_{i}")} {{YukkuriLife:{{DrugSleep:600}}}}'
          for i in range(len(_basket_display_yukkuri))],
    ], give=['mcyukkuri:yukkuri_carrier', 'mcyukkuri:sleep_spray 1',
             'mcyukkuri:yukkuri_carrier_fine', 'mcyukkuri:yukkuri_carrier_large'], env=DAY, vantage=(0, 7),
    capture=dict(shots=(240,), sequences=(), observe=False, views={
        'player': dict(first_person_hand=True, prepare=(220, [
            f'item replace entity @p weapon.mainhand from entity {_basket_display_plain_frame} container.0',
            f'item replace entity {_basket_display_plain_frame} container.0 with minecraft:air',
        ])),
        'shopkeeper': dict(position=(5.15, 1.20, -1.15), look_at=(5.15, 1.02, -2.25), prepare=(220, [
            human('caretaker_sister', 5, -3, _basket_display_shopkeeper, 1, yaw=0)[:-1] + ',NoAI:1b}',
            f'item replace entity {sel(_basket_display_shopkeeper)} weapon.mainhand from entity {_basket_display_fine_frame} container.0',
            f'item replace entity {_basket_display_fine_frame} container.0 with minecraft:air',
        ])),
        'frame': dict(position=(1.5, 1.5, -9.8), look_at=(1.5, 1.5, -8.5)),
    }),
    smoke=dict(ticks=240, commands=[
        (50, 'yukkuri scenario basket plain'),
        (55, f'yukkuri scenario basket capture {sel("basket_healthy")}'),
        (60, f'yukkuri scenario basket capture {sel("basket_injured")}'),
        (70, 'yukkuri scenario basket release ~-6 ~-1 ~5'),
        (75, f'yukkuri scenario basket spray {sel("basket_sprayed")}'),
        (76, f'yukkuri scenario basket capture {sel("basket_sprayed")}'),
        (180, f'yukkuri scenario basket capture {sel("basket_sprayed")}'),
        (190, 'yukkuri scenario basket release ~-2 ~-1 ~5'),
        (196, f'yukkuri scenario basket capture {sel("basket_display_0")}'),
        (197, f'yukkuri scenario basket frame {sel("basket_frame_plain")}'),
        (198, 'yukkuri scenario basket fine'),
        (199, f'yukkuri scenario basket capture {sel("basket_display_1")}'),
        (200, f'yukkuri scenario basket frame {sel("basket_frame_fine")}'),
        (201, 'yukkuri scenario basket large'),
        *[(202 + i, f'yukkuri scenario basket capture {sel(f"basket_display_{i + 2}")}') for i in range(3)],
        (205, f'yukkuri scenario basket frame {sel("basket_frame_large")}'),
    ], expect=[
        rf'\[carrier\][^\n]* action=capture result=refused target={_uuid_text(_basket_comparison, "basket_healthy")} [^\n]* weakness=0\.0 ',
        rf'\[carrier\][^\n]* action=capture result=refused target={_uuid_text(_basket_comparison, "basket_sprayed")} [^\n]* spray_delay=[1-9]\d* ',
        *[rf'\[carrier\][^\n]* action=capture result=committed target={_uuid_text(_basket_comparison, name)} [^\n]* admission={admission}\b'
          for name, admission in (('basket_injured', 'WEAK'), ('basket_sprayed', 'UNCONSCIOUS'))],
        *[rf'\[carrier\][^\n]* action=release result=committed target={_uuid_text(_basket_comparison, name)} [^\n]* remaining=0\b'
          for name in ('basket_injured', 'basket_sprayed')],
        *[rf'\[scenario\][^\n]* action=basket_frame item=mcyukkuri:yukkuri_carrier{suffix} occupants={count}\b'
          for suffix, count in (('', 1), ('_fine', 1), ('_large', 3))],
        *[rf'\[carrier\][^\n]* action=capture result=committed target={_uuid_text(_basket_comparison, f"basket_display_{i}")} [^\n]* load={before}->{after} capacity=12\b'
          for i, before, after in ((2, 0, 1), (3, 1, 3), (4, 3, 5))],
    ], forbid=[rf'\[carrier\][^\n]* action=capture result=committed target={_uuid_text(_basket_comparison, "basket_healthy")}\b']))

add('oversized_nest', 'family', '灵胖进大草窝过夜', '灵胖进大草窝过夜',
    '体长到成体上限的胖灵梦从低沿迈进大草窝，躺下睡觉。天亮后窝外有食物，观察它从同一侧出窝。',
    '约 1 分钟', [*shed(0, 0, half=5), *large_nest(0, 0, 'mcyukkuri:large_wild_nest'),
        yk('reimu', .5, 3, 'deibu', lead=True, nutrition=72, rest_wait=60, adult_scale=1.2, deibu=True),
        *own('deibu'), home('deibu', 0, 0)],
    late=[Retry('function mcylab:util/time_dawn', sel('deibu'), tries=1, first=800),
          Retry(set_status('deibu', 'needs.nutrition', 25), sel('deibu'), tries=1, first=800),
          Retry(item('minecraft:apple', 1, .5, 4), sel('deibu'), tries=1, first=800)],
    env={'time': 'night'}, spot='pet_birth', vantage=(4, 6, 140),
    smoke=dict(ticks=1600, expect=[r'bed crossing [^\n]*direction=in [^\n]*opening=true source=walk',
        r'begin source=life occupancy=INSIDE_SUPPORTED', r'bed crossing [^\n]*direction=out [^\n]*opening=true source=walk'],
        forbid=[r'nest doorway too narrow', r'bed crossing [^\n]*opening=false']))

_bold_bed = _uuid_text('single_bed_contest', 'bold')
_shy_bed = _uuid_text('single_bed_contest', 'shy')
_CONTEST_BEATS = r'\[choreo\][^\n]*uuid=([\w-]+) [^\n]*beat=(contest_\w+) face=(\w+) line=(\w+) declared=(\w+)'


def _verify_bed_contest(log, process, winner=None, tussles=0):
    """One staged contest per bed: its odds, every beat showing the face it declared, then the loser at another free bed."""
    begins = re.findall(r'\[social\][^\n]* t=(\d+) [^\n]*contest begin prize=bed nest=(BlockPos\{[^}]+\}) process=(\w+)', log)
    verdicts = re.findall(r'\[social\][^\n]* t=(\d+) [^\n]*contest verdict prize=bed nest=(BlockPos\{[^}]+\}) process=(\w+) winner=([\w-]+) loser=([\w-]+)', log)
    if len(begins) != 1 or len(verdicts) != 1:
        return f'expected one contest begin and one verdict, saw {len(begins)} and {len(verdicts)}'
    if begins[0][2] != process or verdicts[0][2] != process:
        return f'contest process {begins[0][2]}/{verdicts[0][2]}, expected {process}'
    if int(verdicts[0][0]) <= int(begins[0][0]):
        return 'the verdict did not come after the contest began'
    _, bed, _, won, lost = verdicts[0]
    if winner and won != winner:
        return f'{won} won the bed, expected {winner}'
    beats = re.findall(_CONTEST_BEATS, log)
    for who, beat, shown, line, declared in beats:
        if shown != declared:
            return f'{who} beat={beat} showed {shown}, declared {declared}'
    for who in (won, lost):
        names = [beat for u, beat, *_ in beats if u == who]
        for needed in ('contest_faceoff', 'contest_won' if who == won else 'contest_lost'):
            if needed not in names:
                return f'{who} never played {needed}'
    if len([1 for _, beat, *_ in beats if beat == 'contest_tussle']) < tussles:
        return f'fewer than {tussles} tussle beats'
    if re.search(r'contest beat=\w+ [^\n]*accepted=false', log):
        return 'a contest beat was refused by the face layer'
    failed = {}
    for who, tick, event, nest, side in re.findall(r'uuid=([\w-]+) t=(\d+) [^\n]*?(refused bed approach|approaching unclaimed bed) nest=(BlockPos\{[^}]+\}) side=(\w+)', log):
        opening = (who, nest, side)
        if event == 'refused bed approach':
            failed[opening] = int(tick)
        elif opening in failed and int(tick) - failed[opening] < 1200:
            return f'{who} retried the blocked {side} opening at {nest}'
    loss = re.search(rf'uuid={lost} t=(\d+) [^\n]*yielded contested bed nest=(BlockPos\{{[^}}]+\}}) rival={won}', log)
    alternative = re.search(rf'uuid={lost} t=(\d+) [^\n]*approaching unclaimed bed nest=(BlockPos\{{[^}}]+\}})', log[loss.end():]) if loss else None
    if not alternative or int(alternative[1]) - int(loss[1]) < 1200 or loss[2] == alternative[2]:
        return 'The losing adult did not approach a different free bed after sixty seconds'
    for actor, nest in ((won, loss[2]), (lost, alternative[2])):
        if not re.search(rf'uuid={actor} [^\n]*assigned kind=care [^\n]*{re.escape(nest)}', log):
            return f'{actor} did not claim {nest}'


add('single_bed_contest', 'family', '两个成油争一个软垫窝', '两个成油争一个软垫窝',
    '棚内只有一个空窝。看两个互不相识的成油走到窝前，胆大的和胆小的在窝前对峙，数值悬殊，胆小的很快认输让开，胆大的认窝。一分钟后棚里添一个空窝，落选者可以改去那里。',
    '约 1 分半钟', [*shed(0, 0, half=4),
        *[line for name, x, bold in [('bold', -.65, 95), ('shy', .65, 12)] for line in
          (yk('reimu', x, -2.3, name, uuid=uid('single_bed_contest', name), boldness=bold,
              label='胆大' if name=='bold' else '胆小', lead=name=='shy'), *own(name))]],
    late=[Retry(block(2, 0, 0, 'mcyukkuri:yukkuri_nest'), sel('shy'), tries=1, first=1250)],
    env=DAY, spot='pet_birth', vantage=(0, -5, 0),
    smoke=dict(ticks=2000, expect=[rf'uuid={who} [^\n]*approaching unclaimed bed' for who in (_bold_bed, _shy_bed)],
               forbid=[rf'uuid={_bold_bed} [^\n]*yielded contested bed'],
               verify=lambda log: _verify_bed_contest(log, 'LOPSIDED', winner=_bold_bed)))

_even_a = _uuid_text('bed_contest_even', 'a')
_even_b = _uuid_text('bed_contest_even', 'b')
add('bed_contest_even', 'family', '两个同样胆大的成油争一个窝', '两个同样胆大的成油争一个窝',
    '棚内只有一个空窝。两个同样胆大的成油（分属两位饲主）同时走到窝前，数值相当，先对峙再互相顶撞，数轮后分出胜负，赢的认窝，输的按性格表态。一分钟后棚里添一个空窝，落选者改去那里。',
    '约 1 分半钟', [*shed(0, 0, half=4),
        *[line for name, x in [('a', -.65), ('b', .65)] for line in
          (yk('reimu', x, -2.3, name, uuid=uid('bed_contest_even', name), boldness=85, label='甲' if name == 'a' else '乙', lead=name == 'b'),)],
        # Two homeless pets of two different hands: owned, so the habitat does not take the loser, and not one hand's, so they may tussle.
        *own('a'), f'data modify entity {sel("b")} {OWNED} set value {{Use:"companion",Owner:[I;2,2,2,2]}}'],
    late=[Retry(block(2, 0, 0, 'mcyukkuri:yukkuri_nest'), sel('b'), tries=1, first=1250)],
    env=DAY, spot='pet_birth', vantage=(0, -5, 0),
    smoke=dict(ticks=2000, expect=[rf'uuid={who} [^\n]*approaching unclaimed bed' for who in (_even_a, _even_b)],
               forbid=[r'contest beat=\w+ [^\n]*accepted=false'],
               verify=lambda log: _verify_bed_contest(log, 'TUSSLE', tussles=3)))

_number_scenes()


# Topic codes are shared by the scene buttons, signs, and readouts.
TOPICS = {
    'oversized_nest': 'D1',
    'single_bed_contest': 'D1',
    'bed_contest_even': 'D1',
    'processing_plant': 'M2',
    'factory_sweet_box': 'M2',
    'factory_kin_boxes': 'M2',
    'kindness_adoption': 'F4',
    'home_pane': 'W7', 'home_open': 'W7',
    'balance_traits': 'N1', 'balance_relation': 'N1', 'balance_forms': 'N1', 'balance_deibu': 'N1',
    'waste_demo': 'F5', 'family_walk_home': 'F4', 'family_flight_home': 'F4', 'family_flight_timid': 'F4', 'family_flight_hat': 'F4',
    'choice_nest': 'D1', 'choice_food': 'D1', 'choice_tool': 'D1', 'choice_snatch': 'D1', 'choice_toilet': 'D1',
    'agitation_relief': 'D2', 'adult_pairing': 'F6', 'widow_repair': 'F6', 'alice_hunt': 'R4', 'aftermath_growth': 'F7', 'aftermath_depletion': 'F7',
    'pentup_heat': 'F6', 'pentup_hold': 'F6', 'pentup_breach': 'F6',
    'den_family': 'W1', 'den_mold_exile': 'W1', 'dig': 'W2', 'indep_near': 'W3', 'den_move': 'W4', 'home_greedy': 'W4', 'forage': 'W5', 'graze': 'W6', 'food_behind_glass': 'W6', 'food_sill_window': 'W6',
    'den_barrier': 'W8', 'den_barrier_predator': 'W8', 'den_barrier_threat': 'W8', 'den_lead_away': 'W8', 'den_call_timeout': 'W8',
    'den_mold_child_exile': 'W1', 'den_mold_keep': 'W1', 'den_raid': 'W1', 'den_monopoly': 'W1',
    'family_absence': 'W9',
    'wall_tuck': 'M5',
    'stray_boxes': 'M5', 'stray_strange_food': 'D1','stray_rain': 'R1', 'stray_rubbish': 'M5', 'stray_junk_headwear': 'M5', 'stray_wind': 'R1', 'stray_paper': 'M5', 'stray_paper_young': 'M5',
    'oni_shop': 'M1', 'oni_stress_room': 'M1', 'shop_care': 'M1', 'shop_water': 'M1', 'shop_kin_room': 'M1', 'pet_water': 'M1', 'pet_water_away': 'M1', 'pet_food_walled': 'M1', 'pet_food_empty': 'M1',
    'street_give_way': 'M4', 'street_corridor': 'M4', 'street_dead_end': 'M4', 'yield_lock_lane': 'M4', 'oni_street_scrap': 'M1', 'street_crops': 'M4', 'street_furniture': 'M5', 'street_tidy': 'M5',
    'stall_theft': 'M5', 'counter_buy': 'M5', 'counter_shoo': 'M5', 'pocket_park_rest': 'M5',
    'shelter_cartons': 'M5', 'shelter_cartons_blocked': 'M5',
    'yard_owner_return': 'M5', 'yard_stray_scold': 'M5', 'park_walk': 'M5', 'park_play': 'M5', 'park_stray_play': 'M5', 'park_wait_switch': 'M5', 'park_recall_play': 'M5', 'park_slide': 'M5', 'park_tube': 'M5', 'park_rolling_toys': 'M5', 'park_trampoline_contrast': 'M5', 'park_picnic': 'M5', 'park_aid': 'M5', 'juice_bath': 'M5', 'doll_carry': 'M5', 'doll_carry_interrupt': 'M5', 'doll_comfort_sleep': 'M5', 'doll_comfort_friend': 'M5', 'doll_talk': 'M5', 'doll_talk_giveup': 'M5', 'doll_snatch': 'M5', 'doll_take': 'M5', 'bully_strike': 'M5', 'treasure_bully': 'M5',
    'sleep': 'D1', 'night_sleep': 'D1', 'nest_grades': 'D1', 'night_hungry_child': 'D1', 'meal_content': 'D1', 'fatigue': 'R1', 'fatigue_predator': 'R2', 'nutrition_age': 'D1', 'frail_gait': 'D1', 'meet': 'D1', 'greet_close': 'D1', 'nuzzle_shapes': 'D1', 'company_visit': 'D1', 'quarrel_makeup': 'D1', 'toilet': 'F5', 'pred_toilet': 'F5', 'hurt': 'F3',
    'birth_land_caught': 'F1', 'birth_land_missed': 'F1', 'feed': 'F2', 'first_toilet': 'F2', 'growth': 'F4',
    'leaf_swaddle': 'F2', 'leaf_swaddle_neglect': 'F2', 'leaf_swaddle_neglect_stages': 'F2', 'family_distance': 'F8', 'family_search': 'F8', 'family_search_pane': 'F8', 'birth_nest_safe': 'F1', 'birth_nest_stem': 'F1', 'birth_stem_caught': 'F1', 'family_nests': 'F4', 'carry_home': 'F4', 'infant_starvation': 'F2',
    'excited_leak': 'D1', 'need_tell': 'D1', 'care_attitude': 'D1', 'basket_attitude': 'M5', 'basket_weakness': 'M5',
    'forage_empty': 'R1', 'rain_life': 'R1', 'rain_huddle': 'R1', 'storm_cower': 'R1', 'marisa_explore': 'R1', 'rain_shelter': 'R1', 'rain_shelter_family': 'R1', 'rain_shelter_full': 'R1', 'rain_open': 'R1', 'pred_watch': 'R2', 'pred_range': 'R2', 'pred_pets': 'R2', 'shared_body': 'R3',
    'pred_spawn_nest': 'R2', 'pred_build_nest': 'R2', 'pred_perch_hunt': 'R2', 'pred_noon_shade': 'R2',
    'pred_nest_feeding': 'R2', 'pred_fledging': 'R2', 'pred_tree_territory': 'R2',
    'pred_pad_cleanup': 'R2', 'pred_forage_avoidance': 'R2',
    'house_empty': 'H1', 'resident_house_lint': 'H1', 'house_pair': 'H2', 'house_eggs': 'H3', 'pet_door_return': 'D1',
    'shop_build': 'M1', 'shop_window': 'M1', 'shop_buy': 'M1', 'shop_browse': 'M1', 'shop_pick': 'M1', 'shop_fetch': 'M1', 'shop_restock': 'M1', 'lead_walk': 'M3', 'swaddle_worn': 'M3', 'feed_wild': 'M3', 'oniisan_prey': 'M4', 'attack_witness': 'M4', 'street_stop': 'M4',
    'wild': 'M5', 'wild_beg_refuse': 'M5', 'wild_beg_snatch': 'M5', 'wild_beg_help': 'M5',
    'badge': 'M5', 'training_bowl': 'M5', 'training_toilet': 'M5', 'training_bed': 'M5', 'training_walk': 'M5', 'training_study': 'M5', 'skills_magnifier': 'M5', 'skills_got_it': 'M5',
    'date': 'M5', 'transport': 'M5', 'custody_death': 'M5', 'social_identity': 'M5',
    'certify_bronze': 'M5', 'certify_silver_pass': 'M5', 'certify_silver_fail': 'M5', 'certify_platinum_talent': 'M5',
    'badge_counter_reissue': 'M5', 'badge_plaza': 'M5', 'badge_plaza_stray': 'M5', 'badge_confiscate': 'M5',
    'exam_caught': 'M5', 'brood_unseen': 'M5',
    'arc_hide': 'M5', 'arc_lie': 'M5', 'arc_confess': 'M5', 'arc_confess_pat': 'M5', 'arc_confess_hit': 'M5', 'arc_naive': 'M5',
    'rule_song_hold': 'M5', 'rule_song_sneak': 'M5', 'rule_eat_wait': 'M5', 'rule_eat_caught': 'M5', 'rule_eat_struck': 'M5', 'rule_yard': 'M5', 'elope': 'M5', 'elope_leave': 'M5',
    'airborne': 'R8', 'house_health': 'R9',
    'body_mold': 'R5', 'wound_materials': 'R5', 'thermal_body': 'R5', 'wound_sources': 'R5', 'punishment': 'R5', 'learned_holder': 'R5', 'hook_kin_rescue': 'R5', 'learned_fire': 'R5', 'wound_leak': 'R5', 'treatment_severe': 'R5', 'treatment_scars': 'R5', 'treatment_care': 'R5', 'magma_sole': 'R5', 'core_exposed': 'R5', 'core_filling': 'R5', 'remains_rot': 'R6', 'remains_forms': 'R6', 'carrion': 'R6', 'cannibal_hunt': 'R6',
    'last_words': 'R7', 'offering': 'R7', 'sacrifice': 'R7',
    'hat_push': 'A1', 'hat_current': 'A1', 'hat_grounded_capsized': 'A4', 'cargo': 'A2', 'hat_larder': 'A2', 'hat_sever_tearful': 'A3', 'hat_sever_cold': 'A3', 'hat_sever_freeze': 'A3', 'hat_sever_sulk': 'A3', 'hat_load_warning': 'A3', 'shake_capsize': 'A4',
    'water_colony': 'A6', 'sail': 'A6', 'afloat_stick': 'A6', 'afloat_family': 'A6', 'afloat_family_gather': 'A6', 'afloat_family_ashore': 'A6', 'shore_family': 'A6', 'kid_boat_play': 'A6', 'boat_toilet': 'A6',
    'rescue_child': 'A5', 'rescue_no_seat': 'A5', 'birth_boat_missed': 'A6', 'birth_hat_own': 'A6', 'first_voyage': 'A6', 'first_voyage_brim': 'A6',
    'self_rescue': 'A5', 'shake_ashore': 'A5', 'ledge_spit': 'A5', 'shore_capsized': 'A5', 'shore_capsized_far': 'A5', 'worn_hat_boat': 'A5', 'hat_swap': 'C1', 'hat_damage': 'C1', 'hat_shatter': 'C1', 'hatless_kin': 'C2', 'hatless_scorn': 'C2',
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


# The terminal specimen begins dying after 400 body ticks.
_smoke('body_mold', 360, [
    rf'uuid={_uuid_text("body_mold", "mold0")} [^\n]*line event=mold_self_aware\b',
    rf'uuid={_uuid_text("body_mold", "mold0")} [^\n]*course begun kind=terror strength=0\.3 length=\d+ about=null\b',
    rf'uuid={_uuid_text("body_mold", "mold0")} [^\n]*course goal behavior kind=terror stage=(?:onset|peak) behavior=shaken\b',
    rf'uuid={_uuid_text("body_mold", "mold1")} [^\n]*line event=mold_itch\b',
], forbid=[rf'uuid={_uuid_text("body_mold", "mold1")} [^\n]*course (?:begun|renewed) kind=terror\b'])

_LICK = [r'\[comfort\][^\n]* approach started', r'\[comfort\][^\n]* mouth contact started']
_smoke('hurt', 1800, _LICK)
_smoke('kindness_adoption', 720, [
    rf'\[kindness\][^\n]*uuid={_uuid_text("kindness_adoption", "foster")} [^\n]*bite handed peer={_uuid_text("kindness_adoption", "orphan")} clip=offer_bite\b',
    rf'\[meal\][^\n]*uuid={_uuid_text("kindness_adoption", "orphan")} [^\n]*swallowed item=[^\n]*feeder={_uuid_text("kindness_adoption", "foster")}\b',
    rf'\[family\][^\n]*uuid={_uuid_text("kindness_adoption", "foster")} [^\n]*foster accepted child={_uuid_text("kindness_adoption", "orphan")}\b',
])

# The soaker climbs into the tub unprompted, sits through one soak that uses up one level, and its one wound closes.
_juice_log = _uuid_text('juice_bath', 'soaker')
_smoke('juice_bath', 1800, [
    rf'uuid={_juice_log} [^\n]*tub soak started at=[^\n]* juice=3 ',
    rf'uuid={_juice_log} [^\n]*tub soak ended at=[^\n]* used=true juice=3 -> 2 health=4\.0 -> (?!4\.0 )\S+ wounds=1 -> 0 hygiene=(?!20\.0\b)\S+',
], forbid=[r'tub soak ended [^\n]* used=false'])

# The idle animal on the magma field takes real hot-floor damage, jumps out of each burn and reaches ordinary ground alive.
_smoke('magma_sole', 600, [
    r'\[body\][^\n]* hot floor jump escape=',
    r'\[body\][^\n]* hot floor escaped health=',
])

# Each nest lies beyond the other body in a one-cell lane, so both sleeping at home proves they got past each other; the
# encounter lines (begin, beats, end) of either name who yielded, where, and how long the pass took.
_corridor_logs = [_uuid_text(_corridor, slot) for slot in ('corridor_north', 'corridor_south')]
_dead_logs = [_uuid_text(_dead_end, slot) for slot in ('dead_north', 'dead_south')]


def _encounter_smoke(sid, ticks, logs, expect, forbid=()):
    who = rf'uuid=(?:{logs[0]}|{logs[1]})\b'
    _smoke(sid, ticks, [
        *expect,
        *[rf'\[sleep\][^\n]*uuid={log}\b[^\n]*begin source=life occupancy=INSIDE_SUPPORTED\b' for log in logs]],
        forbid=[rf'\[move\][^\n]*{who}[^\n]* encounter end [^\n]* result=(?:stuck|timeout|aborted)\b',
                rf'\[move\][^\n]*{who}[^\n]* ground path stopped reason=no_progress', *forbid])
    return who


_corridor_who = rf'uuid=(?:{_corridor_logs[0]}|{_corridor_logs[1]})\b'
_encounter_smoke('street_corridor', 1500, _corridor_logs, [
    rf'\[move\][^\n]*{_corridor_who}[^\n]* encounter begin keeper=#\d+ yielder=#\d+ place=\S+ cost=\(\S+,\S+\) reason=(?:nearer_place|stronger|tie|no_place) ',
    rf'\[move\][^\n]*{_corridor_who}[^\n]* encounter end keeper=#\d+ yielder=#\d+ result=passed '])
_dead_who = rf'uuid=(?:{_dead_logs[0]}|{_dead_logs[1]})\b'
_encounter_smoke('street_dead_end', 2200, _dead_logs, [
    rf'\[move\][^\n]*{_dead_who}[^\n]* encounter begin keeper=#\d+ yielder=#\d+ place=\S+ cost=\(\S+,\S+\) reason=nearer_place minor=false ',
    rf'\[move\][^\n]*{_dead_who}[^\n]* encounter end keeper=#\d+ yielder=#\d+ result=passed ',
    # The impatient yielder (self_control 10) stands waiting in the room mouth: one beat, its face shown or a logged holder's.
    rf'\[move\][^\n]*{_dead_who}[^\n]* encounter beat role=yielder beat=wait_impatient face=\w+ declared=\w+ (?:held_by=\S+ held_tier=\S+ held_face=\w+ )?line=\w+ clip=\w+ '],
    forbid=[rf'\[move\][^\n]*{_dead_who}[^\n]* encounter begin [^\n]* reason=(?:no_place|dead_end) ',
            rf'\[move\][^\n]*{_dead_who}[^\n]* encounter end [^\n]* result=no_place'])


def _encounter_verify(base_ticks, per_block, min_len=0, max_holds=1, who=None):
    """One decision per pair and meeting, begun before the bodies touched (gap > 1.5), no role flip (the only reversal within 300 ticks of
    an end is the logged dead end), at most one keeper stop, every full yield printed its face and line beat, each encounter ends within
    ``base_ticks + per_block * len`` of its begin (the trigger gap is 3.5 blocks, so that is first proximity to passing; ``len`` is the
    yielder's walk to its place), the yielder's walk is at least ``min_len`` (it is not sent into a dead end), no removed ladder action and no
    missing-route line for either body inside the pass (first begin .. 40 ticks after the last end; ordinary roaming after the pass is not the
    pass), and no walker moves more than 55 degrees off its facing in the same span (never backwards, resumed routes included)."""
    def verify(window, trace):
        ended, result, lengths, began, span = {}, {}, {}, 0, [10 ** 9, 0]
        for line in re.finditer(r'\[move\][^\n]* t=(\d+) [^\n]* encounter (begin|end) keeper=#(\d+) yielder=#(\d+) ([^\n]*)', window):
            now, kind, keeper, yielder, rest = line.groups()
            now = int(now)
            span = [min(span[0], now), max(span[1], now)]
            pair = (keeper, yielder)
            if kind == 'begin':
                began += 1
                reason = re.search(r'reason=(\w+)', rest).group(1)
                gap = float(re.search(r' gap=(-?[\d.]+)', rest).group(1))
                lengths[pair] = float(re.search(r' len=([\d.]+)', rest).group(1))
                if gap < 1.5 and reason != 'dead_end':
                    return f'keeper=#{keeper} yielder=#{yielder} began at gap {gap}: the bodies had already touched (a trigger that came too late)'
                if lengths[pair] < min_len and reason != 'dead_end':
                    return f'keeper=#{keeper} yielder=#{yielder} walks only {lengths[pair]} blocks (< {min_len}): sent into the dead end'
                for other in (pair, (yielder, keeper)):
                    if other in ended and now - ended[other] < 300 and not (other != pair and reason == 'dead_end' and result.get(other) == 'no_place'):
                        return f'keeper=#{keeper} yielder=#{yielder} decided again {now - ended[other]} ticks after the same pair' + (' with the roles reversed' if other != pair else '')
                if 'minor=false' in rest and not re.search(rf'encounter beat role=yielder beat=yield_notice face=\w+ line=\w+ keeper=#{keeper} yielder=#{yielder}', window):
                    return f'no yield_notice beat for keeper=#{keeper} yielder=#{yielder}'
            else:
                found = re.search(r'result=(\w+) cause=(\w+) ticks=(\d+) holds=(\d+)', rest)
                ended[pair] = now
                result[pair] = found.group(1)
                max_ticks = base_ticks + per_block * lengths.get(pair, 0)
                if int(found.group(3)) > max_ticks:
                    return f'encounter keeper=#{keeper} yielder=#{yielder} took {found.group(3)} ticks (> {max_ticks:.0f}: {base_ticks} + {per_block} per block of its {lengths.get(pair, 0):.1f}-block walk)'
                if int(found.group(4)) > max_holds:
                    return f'keeper stopped {found.group(4)} times in one encounter (stop and go)'
        if not began:
            return 'no encounter began'
        impatience_by_pair = {}
        for m in re.finditer(r'encounter beat role=(\w+) beat=wait_impatient face=(\w+) declared=(\w+)(?: held_by=(\S+) held_tier=\S+ held_face=\w+)? line=(\w+) clip=\w+ keeper=#(\d+) yielder=#(\d+)', window):
            role, shown, declared, held_by, _, keeper, yielder = m.groups()
            if shown != declared and not held_by:
                return f'wait_impatient ({role}) showed face={shown} instead of declared={declared} with no holder'
            key = (keeper, yielder, role)
            impatience_by_pair[key] = impatience_by_pair.get(key, 0) + 1
            if impatience_by_pair[key] > 1:
                return f'wait_impatient ({role}) fired {impatience_by_pair[key]} times in one encounter keeper=#{keeper} yielder=#{yielder}'
        if who:
            for line in window.splitlines():
                tick = re.search(r' t=(\d+) ', line)
                if not tick or not span[0] <= int(tick[1]) <= span[1] + 40 or not re.search(who, line):
                    continue
                if re.search(r'\[move\][^\n]* action=(?:keeps_line|backs_off|gives_way|no_alternative|waiting)\b', line):
                    return f'a removed ladder action at t={tick[1]} inside the pass: {line.strip()}'
                if '[approach]' in line and 'surface path unavailable' in line:
                    return f'a surface route was reported unavailable at t={tick[1]} inside the pass: {line.strip()}'
        return _slides(trace, span[0], span[1] + 40)
    return verify


def _slides(trace, first, last, who=None):
    """The first tick in [first, last] at which a walker (``who``, or any) moves more than 55 degrees off its facing: a backward or sideways slide."""
    for entry in trace:
        row = json.loads(entry.split('[trace] ', 1)[1])
        if row['k'] != 'st' or not first <= row['t'] <= last or who and row['u'] != who:
            continue
        v, yaw = row['st']['v'], math.radians(row['st']['by'])
        speed = math.hypot(v[0], v[2])
        if speed > .05 and (v[0] * -math.sin(yaw) + v[2] * math.cos(yaw)) / speed < math.cos(math.radians(55)):
            return f"{row['u']} moved {speed:.3f} blocks/tick more than 55 degrees off its facing at tick {row['t']} (a backward or sideways slide)"
    return None


_lane_sleeper, _lane_walker = _uuid_text(_lock_lane, 'lane_sleeper'), _uuid_text(_lock_lane, 'lane_walker')
_lane_who = rf'uuid=(?:{_lane_sleeper}|{_lane_walker})\b'
_smoke(_lock_lane, 2500, [
    rf'\[sleep\][^\n]*uuid={_lane_sleeper} [^\n]* begin source=fatigue full=true ',
    rf'\[move\][^\n]*uuid={_lane_walker} [^\n]* encounter begin keeper=#\d+ yielder=#\d+ place=\S+ cost=\(\S+,inf\) reason=locked_stays ',
    rf'\[move\][^\n]*uuid={_lane_walker} [^\n]* encounter beat role=yielder beat=yield_notice face=\w+ line=\w+ ',
    rf'\[move\][^\n]*uuid={_lane_walker} [^\n]* encounter end keeper=#\d+ yielder=#\d+ result=passed cause=placed ',
    rf'\[choreo\][^\n]*uuid={_lane_walker} [^\n]* beat=yield_giveup face=\w+ line=sulk_huff declared=\w+',
    rf'\[move\][^\n]*uuid={_lane_walker} [^\n]* encounter sulk speech tick=\d+ [^\n]* spoken=true',
    rf'\[move\][^\n]*uuid={_lane_walker} [^\n]* gave up walk target=\S+ blocker=#\d+ '],
    forbid=[rf'\[move\][^\n]*{_lane_who}[^\n]* encounter end [^\n]* result=(?:stuck|timeout|aborted|no_place)\b',
            rf'\[move\][^\n]*{_lane_who}[^\n]* ground path stopped reason=no_progress',
            rf'\[move\][^\n]*uuid={_lane_sleeper} [^\n]* encounter begin '])


def _lock_verify(window, trace):
    """The lane's lock is resolved by the walker alone within about 100 ticks of its first blocked window (ladder: plan round, ask, settle): it
    turns round before it walks (no sliding), both its notice and the sulk it keeps at the give-up show the faces they declare, the sulk's
    huff line is spoken after the give-up and inside that hold (not dropped by the notice's speech gap), the sleeper is
    never made to move, no blocked stretch of any pet lasts over 200 ticks, and the walker is never sent at the sleeper again while it still
    stands on the spot where it held the lane up - a wake alone changes nothing, only a real move (> YieldRule.STAYED) lifts the refusal (the
    window runs past the old 1200-tick expiry)."""
    blocked = sorted(int(m[1]) for m in re.finditer(rf'\[move\] \w+ #\d+ {_lane_who} t=(\d+)[^\n]* blocked by yukkuri ', window))
    begin = re.search(rf'\[move\] \w+ #\d+ uuid={_lane_walker} t=(\d+)[^\n]* encounter begin ', window)
    end = re.search(rf'\[move\] \w+ #\d+ uuid={_lane_walker} t=(\d+)[^\n]* encounter end ', window)
    if not blocked or not begin or not end:
        return 'the walker was never held up, or never began and ended an encounter'
    stretches, first = [], blocked[0]
    for before, now in pairwise(blocked):
        if now - before > 120:
            stretches.append((first, before))
            first = now
    stretches.append((first, blocked[-1]))
    if any(last - first > 200 for first, last in stretches):
        return f'a pet stayed blocked by a yukkuri for over 200 ticks: {stretches}'
    if int(end[1]) - blocked[0] > 130:
        return f'resolved {int(end[1]) - blocked[0]} ticks after the first blocked window (> 130)'
    if int(begin[1]) - blocked[0] > 70:
        return f'the pair was decided {int(begin[1]) - blocked[0]} ticks after the first blocked window (> 70)'
    face = re.search(rf'uuid={_lane_walker} [^\n]* encounter face role=yielder beat=yield_notice declared=(\w+) shown=(\w+) ', window)
    if not face or face[1] != face[2]:
        return f'the yield_notice beat did not show the face it declared ({face and face.groups()})'
    huff = re.search(rf'\[choreo\][^\n]*uuid={_lane_walker} [^\n]* beat=yield_giveup face=(\w+) line=sulk_huff declared=(\w+)', window)
    if not huff or huff[1] != huff[2]:
        return f'the give-up sulk did not show the face it declared ({huff and huff.groups()})'
    sulk = re.search(rf'uuid={_lane_walker} [^\n]* encounter sulk speech tick=(\d+)[^\n]* holdEnd=(\d+) spoken=(\w+)', window)
    if not sulk or sulk[3] != 'true':
        return f'the give-up sulk line was not spoken ({sulk and sulk.groups()})'
    if not int(end[1]) < int(sulk[1]) <= int(sulk[2]):
        return f'the sulk line was not spoken inside its hold after the give-up (t={sulk[1]}, holdEnd={sulk[2]}, end={end[1]})'
    if slide := _slides(trace, int(begin[1]), int(end[1]) + 40, _lane_walker):
        return slide
    gave = re.search(rf'\[move\] \w+ #\d+ uuid={_lane_walker} t=(\d+)[^\n]* gave up walk target=\(([-\d.]+),([-\d.]+),([-\d.]+)\) blocker=#\d+ at=', window)
    if not gave:
        return 'the walker never gave up the walk held up by the sleeper'
    gave_tick, errand = int(gave[1]), tuple(float(gave[g]) for g in (2, 3, 4))
    samples = []
    for line in trace:
        row = json.loads(line.split('[trace] ', 1)[1])
        if row['k'] == 'st' and row['u'] == _lane_sleeper:
            samples.append((row['t'], tuple(row['st']['p'])))
    stood = next((p for tick, p in reversed(samples) if tick <= gave_tick), None)
    if stood is None:
        return 'the sleeper left no trace position at the give-up'
    def moved_by(tick):
        return any(t <= tick and math.dist(p, stood) > 1.5 for t, p in samples)
    for m in re.finditer(rf'\[approach\] \w+ #\d+ uuid={_lane_walker} t=(\d+)[^\n]* surface route started [^\n]* destination=\(([-\d.]+), ([-\d.]+), ([-\d.]+)\)', window):
        tick = int(m[1])
        if tick <= gave_tick or moved_by(tick):
            continue
        if math.dist(tuple(float(m[g]) for g in (2, 3, 4)), errand) <= 2:
            return f'the walker was sent at the same target again at t={tick} while the sleeper stood where it held the lane up'
    for start, _ in stretches[1:]:
        if not moved_by(start):
            return f'the walker was held up by the sleeper again at t={start} while it stood where it held the lane up: {stretches}'
    return None


next(s for s in S if s.id == _lock_lane).smoke.update(verify=_lock_verify, verify_trace=True)


for _scene, _verify in (('street_corridor', _encounter_verify(50, 25, who=_corridor_who)),
                        ('street_dead_end', _encounter_verify(50, 25, min_len=5, who=_dead_who))):
    next(s for s in S if s.id == _scene).smoke.update(verify=_verify, verify_trace=True)

_doll_marisa = _uuid_text(_doll_carry, 'doll_marisa')
_doll_reimu = _uuid_text(_doll_carry, 'doll_reimu')
_KEEPSAKE_BEAT_RE = re.compile(
    r'\[keepsake\][^\n]*uuid=([0-9a-f-]{36}) t=(\d+)[^\n]*beat=(\w+) shown=(\w+) declared=(\w+)'
    r'(?: held_by=(\S+)(?: tier=(\w+))? held_face=(\w+))? line=(\w+) where=(\w+) dolls=(\d+)(.*)')


def _keepsake_beat_problem(beat, shown, declared, held_by, tier, held_face):
    """A beat shows what it declared, unless a logged live hold or course stage owns the shown face: then shown must be its own face."""
    if shown == declared:
        return None
    if not held_by:
        return f'face mismatch on {beat}: shown={shown} declared={declared}'
    if held_face != shown:
        return (f'face mismatch on {beat}: shown={shown} declared={declared} '
                f'held_by={held_by} tier={tier} held_face={held_face}')
    if tier is not None and tier not in ('SHORT', 'MEDIUM', 'LONG'):
        return f'{beat}: held_by={held_by} with an unknown tier={tier}'
    return None


def _keepsake_set_down_problem(tail):
    """The set-down beat must name the item that really left her mouth and the spot computed from its position."""
    m = re.search(r'\bitem=([0-9a-f-]{36}) at=\(([-0-9.]+),([-0-9.]+),([-0-9.]+)\) bed=[-0-9, ]+ '
                  r'bedDist=([0-9.]+) hollow=(true|false)', tail)
    if not m:
        return f'set_down does not name the released item, its landed position and the computed spot: {tail}'
    if m[6] != 'false':
        return f'set_down landed inside the nest hollow: {tail}'
    if float(m[5]) > 1.2:
        return f'set_down landed {m[5]} from the bed rim (>1.2): {tail}'
    return None


def _verify_doll_carry(window):
    beats = list(_KEEPSAKE_BEAT_RE.finditer(window))
    if not beats:
        return 'no [keepsake] beat lines logged'
    marisa_beats = []
    pick_tick = None
    set_tick = None
    for m in beats:
        u, t, beat, shown, declared, held_by, tier, held_face, line, where, dolls, tail = m.groups()
        problem = _keepsake_beat_problem(beat, shown, declared, held_by, tier, held_face)
        if problem:
            return problem
        if int(dolls) != 1:
            return f'doll conservation broken on {beat}: dolls={dolls}'
        if u == _doll_reimu and beat in ('notice', 'pick_up', 'carry', 'set_down'):
            return f'low-drive Reimu unexpectedly performed keepsake beat={beat}'
        if u == _doll_marisa:
            marisa_beats.append((int(t), beat, where, tail))
            if beat == 'pick_up' and pick_tick is None:
                pick_tick = int(t)
            if beat == 'set_down' and set_tick is None:
                set_tick = int(t)
                if where != 'bed':
                    return f'set_down where={where}, expected bed'
                problem = _keepsake_set_down_problem(tail)
                if problem:
                    return problem
    names = [b[1] for b in marisa_beats]
    want = ['notice', 'pick_up', 'carry', 'set_down']
    idx = 0
    for n in names:
        if idx < len(want) and n == want[idx]:
            idx += 1
    if idx < len(want):
        return f'expected beat order {want}, saw {names}'
    if pick_tick is not None and set_tick is not None and set_tick - pick_tick > 600:
        return f'carry took {set_tick - pick_tick} ticks (>600)'


_smoke(_doll_carry, 1200, [
    rf'\[keepsake\][^\n]*uuid={_doll_marisa} [^\n]*beat=notice shown=sparkle declared=sparkle line=toy where=ground dolls=1\b',
    rf'\[keepsake\][^\n]*uuid={_doll_marisa} [^\n]*beat=pick_up shown=happy declared=happy line=mine where=mouth dolls=1\b',
    rf'\[keepsake\][^\n]*uuid={_doll_marisa} [^\n]*beat=carry shown=\w+ declared=\w+ line=none where=mouth dolls=1\b',
    rf'\[keepsake\][^\n]*uuid={_doll_marisa} [^\n]*beat=set_down shown=satisfied declared=satisfied line=thing_san where=bed dolls=1\b',
])
next(s for s in S if s.id == _doll_carry).smoke.update(verify=_verify_doll_carry)

_int_fright = _uuid_text(_doll_interrupt, 'int_fright')
_int_hungry = _uuid_text(_doll_interrupt, 'int_hungry')


def _verify_doll_interrupt(window):
    beats = list(_KEEPSAKE_BEAT_RE.finditer(window))
    for m in beats:
        _, _, beat, shown, declared, held_by, tier, held_face, _, _, dolls, tail = m.groups()
        problem = _keepsake_beat_problem(beat, shown, declared, held_by, tier, held_face)
        if problem:
            return problem
        if int(dolls) != 2:
            return f'doll conservation broken on {beat}: dolls={dolls}'
        if beat == 'set_down':
            problem = _keepsake_set_down_problem(tail)
            if problem:
                return problem
    hungry_picks = [int(m[2]) for m in beats if m[1] == _int_hungry and m[3] == 'pick_up']
    if len(hungry_picks) < 2:
        return f'expected interrupted carrier to re-fetch after recovery (>=2 pick_up), saw {len(hungry_picks)}'
    hungry_sets = [int(m[2]) for m in beats if m[1] == _int_hungry and m[3] == 'set_down']
    if not hungry_sets:
        return 'interrupted carrier never completed set_down after re-fetching'


_smoke(_doll_interrupt, 1600, [
    rf'\[keepsake\][^\n]*uuid={_int_fright} [^\n]*beat=pick_up ',
    rf'\[body\][^\n]*uuid={_int_fright} [^\n]*mouth drop item=mcyukkuri:marisa_doll count=1 purpose=KEEPSAKE ',
    rf'\[keepsake\][^\n]*uuid={_int_hungry} [^\n]*beat=pick_up ',
    rf'\[body\][^\n]*uuid={_int_hungry} [^\n]*mouth drop item=mcyukkuri:marisa_doll count=1 purpose=KEEPSAKE ',
    rf'\[keepsake\][^\n]*uuid={_int_hungry} [^\n]*beat=set_down ',
])
next(s for s in S if s.id == _doll_interrupt).smoke.update(
    verify=_verify_doll_interrupt,
    commands=[
        (90, f'execute as {sel("int_hungry")} run yukkuri status set @s needs.nutrition 35'),
        (100, yk('remilia', 0, 9, 'tmp_pred', yaw=180, nutrition=100, energy=100)),
        (115, f'data merge entity {sel("tmp_pred")} {{NoAI:1b}}'),
        (116, f'tp {sel("tmp_pred")} ~ ~50 ~'),
    ])

_comfort_reimu = _uuid_text(_doll_comfort, 'comfort_reimu')
_bold_reimu = _uuid_text(_doll_comfort, 'bold_reimu')
_paired_marisa = _uuid_text(_doll_comfort, 'paired_marisa')
_paired_peer = _uuid_text(_doll_comfort, 'paired_peer')


def _verify_doll_comfort_sleep(window):
    beats = list(_KEEPSAKE_BEAT_RE.finditer(window))
    if not beats:
        return 'no [keepsake] beat lines logged'
    # Verify that no actor ever starts a doll nuzzle (beat=ask) when its own companion choice had eligible > 0.
    choice_re = re.compile(r'\[daily\][^\n]*uuid=([0-9a-f-]{36}) t=(\d+)[^\n]*companion choice [^\n]*checked=\[([^\]]*)\][^\n]*eligible=(\d+)\b')
    eligible_at = {}
    for cm in choice_re.finditer(window):
        eligible_at[(cm[1], int(cm[2]))] = (cm[3].strip(), int(cm[4]))
    reimu_beats = []
    done_ok = False
    sleep_ok = False
    for m in beats:
        u, t, beat, shown, declared, held_by, tier, held_face, line, where, dolls, tail = m.groups()
        problem = _keepsake_beat_problem(beat, shown, declared, held_by, tier, held_face)
        if problem:
            return problem
        if int(dolls) != 2:
            return f'doll conservation broken on {beat}: dolls={dolls}'
        if u == _bold_reimu:
            return f'bold unattached Reimu unexpectedly performed keepsake beat={beat}'
        if beat == 'ask':
            # The ask must carry the actor's own same-tick peer scan: a missing or empty scan is no evidence that no peer was eligible.
            scan = eligible_at.get((u, int(t)))
            if scan is None:
                return f'actor {u} doll ask at t={t} has no same-tick companion choice line'
            checked, eligible = scan
            if not checked:
                return f'actor {u} doll ask at t={t} has an empty checked= peer scan'
            if eligible > 0:
                return f'actor {u} started doll nuzzle while eligible peer count={eligible}'
        if u == _paired_marisa and beat in ('ask', 'rub', 'done'):
            return f'paired Marisa nuzzled doll instead of eligible peer (beat={beat})'
        if u == _comfort_reimu:
            reimu_beats.append(beat)
            if beat == 'done':
                cm = re.search(r'\bcomfort=(\d+)->(\d+)\b', tail)
                if not cm:
                    return f'done beat missing comfort before->after: {tail}'
                before, after = int(cm[1]), int(cm[2])
                if before - after != min(before, 15):
                    return f'doll nuzzle comfort was {before}->{after}, expected -15'
                done_ok = True
            if beat == 'sleep':
                sm = re.search(r'\bdollDist=([0-9.]+) yawDiff=([0-9.]+)\b', tail)
                if not sm:
                    return f'sleep beat missing dollDist/yawDiff: {tail}'
                if float(sm[1]) > 1.5:
                    return f'sleep dollDist={sm[1]} (>1.5)'
                if float(sm[2]) > 15.0:
                    return f'sleep yawDiff={sm[2]} (>15 deg; not facing doll)'
                sleep_ok = True
    want = ['ask', 'rub', 'done', 'sleep']
    idx = 0
    for n in reimu_beats:
        if idx < len(want) and n == want[idx]:
            idx += 1
    if idx < len(want):
        return f'expected comfort_reimu beat order {want}, saw {reimu_beats}'
    if not done_ok or not sleep_ok:
        return f'missing validated done ({done_ok}) or sleep ({sleep_ok}) beat'


_smoke(_doll_comfort, 1600, [
    rf'\[keepsake\][^\n]*uuid={_comfort_reimu} [^\n]*beat=ask shown=open_smile declared=open_smile line=want_nuzzle where=ground dolls=2\b',
    rf'\[keepsake\][^\n]*uuid={_comfort_reimu} [^\n]*beat=rub shown=closed_smile declared=closed_smile line=nuzzle where=ground dolls=2\b',
    rf'\[keepsake\][^\n]*uuid={_comfort_reimu} [^\n]*beat=done shown=satisfied declared=satisfied line=nuzzle_done where=ground dolls=2 comfort=\d+->\d+\b',
    rf'\[keepsake\][^\n]*uuid={_comfort_reimu} [^\n]*beat=sleep shown=sleep declared=sleep line=sleep where=(?:ground|bed) dolls=2\b',
    rf'\[daily\][^\n]*uuid={_paired_marisa} [^\n]*session reserved kind=NUZZLE companion={_paired_peer}\b',
])
next(s for s in S if s.id == _doll_comfort).smoke.update(verify=_verify_doll_comfort_sleep)

_talk_reimu = _uuid_text(_doll_talk, 'talk_reimu')
_clever_marisa = _uuid_text(_doll_talk, 'clever_marisa')
_dull_marisa = _uuid_text(_doll_talk, 'dull_marisa')


def _verify_doll_talk(window):
    beats = list(_KEEPSAKE_BEAT_RE.finditer(window))
    if not beats:
        return 'no [keepsake] beat lines logged'
    by_actor = {_talk_reimu: [], _dull_marisa: []}
    holds = {}
    for m in beats:
        u, t, beat, shown, declared, held_by, tier, held_face, line, where, dolls, tail = m.groups()
        problem = _keepsake_beat_problem(beat, shown, declared, held_by, tier, held_face)
        if problem:
            return problem
        if int(dolls) != 3:
            return f'doll conservation broken on {beat}: dolls={dolls}'
        if u == _clever_marisa and beat in ('address', 'offer', 'eat_on'):
            return f'clever Marisa unexpectedly reacted to other-kind doll: beat={beat}'
        if u in by_actor:
            by_actor[u].append(beat)
            if beat == 'offer':
                om = re.search(r'\bhold=(\d+) food=(\d+)\b', tail)
                if not om:
                    return f'offer beat missing hold/food: {tail}'
                if int(om[2]) != 1:
                    return f'bite count not conserved on offer: food={om[2]}'
                holds[u] = int(om[1])
            if beat == 'eat_on':
                em = re.search(r'\bfood=(\d+)\b', tail)
                if not em or int(em[1]) != 1:
                    return f'bite count not conserved on eat_on: {tail}'
    want = ['address', 'offer', 'eat_on']
    for label, u in (('talk_reimu', _talk_reimu), ('dull_marisa', _dull_marisa)):
        idx = 0
        for n in by_actor[u]:
            if idx < len(want) and n == want[idx]:
                idx += 1
        if idx < len(want):
            return f'{label} expected beat order {want}, saw {by_actor[u]}'
    if holds.get(_dull_marisa, 999) >= holds.get(_talk_reimu, 0):
        return f'expected lower selfControl hold < higher selfControl hold, saw dull={holds.get(_dull_marisa)} clever={holds.get(_talk_reimu)}'


_smoke(_doll_talk, 1200, [
    rf'\[keepsake\][^\n]*uuid={_talk_reimu} [^\n]*beat=address shown=happy declared=happy line=doll_talk where=ground dolls=3\b',
    rf'\[keepsake\][^\n]*uuid={_talk_reimu} [^\n]*beat=offer shown=happy declared=happy line=share where=mouth dolls=3 hold=\d+ food=1\b',
    rf'\[keepsake\][^\n]*uuid={_talk_reimu} [^\n]*beat=eat_on shown=satisfied declared=satisfied line=none where=mouth dolls=3 food=1\b',
    rf'\[keepsake\][^\n]*uuid={_dull_marisa} [^\n]*beat=address shown=happy declared=happy line=doll_talk where=ground dolls=3\b',
    rf'\[keepsake\][^\n]*uuid={_dull_marisa} [^\n]*beat=offer shown=happy declared=happy line=share where=mouth dolls=3 hold=\d+ food=1\b',
    rf'\[keepsake\][^\n]*uuid={_dull_marisa} [^\n]*beat=eat_on shown=satisfied declared=satisfied line=none where=mouth dolls=3 food=1\b',
])
next(s for s in S if s.id == _doll_talk).smoke.update(verify=_verify_doll_talk)

_friend_reimu = _uuid_text(_friend_scene, 'friend_reimu')
_friend_marisa = _uuid_text(_friend_scene, 'friend_marisa')


def _verify_doll_comfort_friend(window):
    beats = list(_KEEPSAKE_BEAT_RE.finditer(window))
    if not beats:
        return 'no [keepsake] beat lines logged'
    rubs, dones = [], []
    for m in beats:
        u, t, beat, shown, declared, held_by, tier, held_face, line, where, dolls, tail = m.groups()
        problem = _keepsake_beat_problem(beat, shown, declared, held_by, tier, held_face)
        if problem:
            return problem
        if int(dolls) != 1:
            return f'doll conservation broken on {beat}: dolls={dolls}'
        if u != _friend_reimu:
            continue
        if beat == 'rub':
            rubs.append(int(t))
        elif beat == 'done':
            dones.append(int(t))
    hand = list(re.finditer(r'\[daily\][^\n]*uuid=' + _friend_reimu + r' t=(\d+)[^\n]*'
                            r'doll comfort interrupted friend=' + _friend_marisa
                            + r' contactTicks=(\d+) affectionNeed=\d+ handedOver=true\b', window))
    if len(hand) != 1:
        return f'expected one doll-comfort handover to the friend, saw {len(hand)}'
    at, contacts = int(hand[0][1]), int(hand[0][2])
    if contacts <= 0:
        return 'the body had not begun rubbing the doll when the friend appeared'
    if contacts >= 60:
        return f'the doll comfort ran to completion before the friend was noticed: contactTicks={contacts}'
    if not any(t < at for t in rubs):
        return f'the doll was never rubbed before the friend appeared at t={at}'
    if any(t < at for t in dones):
        return f'the doll comfort paid out before the friend appeared (done at {min(dones)} < {at})'
    stop = re.search(r'\[daily\][^\n]*uuid=' + _friend_reimu + r' [^\n]*session stopped kind=NUZZLE companion='
                     r'(?!' + _friend_marisa + r')\S+ elapsed=\d+ contactTicks=(\d+)\b', window)
    if not stop:
        return 'the interrupted doll session was never stopped in the log'
    if stop[1] != str(contacts):
        return f'the stopped doll session reports contactTicks={stop[1]}, the handover {contacts}'
    if not re.search(r'\[daily\][^\n]*uuid=' + _friend_reimu + r' [^\n]*greet face turn to='
                     + _friend_marisa + r'\b', window):
        return 'the body never turned to the friend'


_smoke(_friend_scene, 540, [
    rf'\[keepsake\][^\n]*uuid={_friend_reimu} [^\n]*beat=ask shown=open_smile declared=open_smile line=want_nuzzle where=ground dolls=1\b',
    rf'\[keepsake\][^\n]*uuid={_friend_reimu} [^\n]*beat=rub shown=closed_smile declared=closed_smile line=nuzzle where=ground dolls=1\b',
    rf'\[daily\][^\n]*uuid={_friend_reimu} [^\n]*doll comfort interrupted friend={_friend_marisa} contactTicks=\d+ affectionNeed=\d+ handedOver=true\b',
    rf'\[daily\][^\n]*uuid={_friend_reimu} [^\n]*greet face turn to={_friend_marisa} ',
])
next(s for s in S if s.id == _friend_scene).smoke.update(
    verify=_verify_doll_comfort_friend,
    commands=[
        (45, block(3, 0, 4, 'minecraft:oak_fence_gate[facing=west,open=true]')),
    ])

_giveup_kid = _uuid_text(_giveup, 'giveup_kid')


def _verify_doll_talk_giveup(window):
    giveups = list(re.finditer(r'\[keepsake\][^\n]*uuid=' + _giveup_kid
                               + r' t=(\d+)[^\n]*talk gave up unaddressed elapsed=(\d+) gap=(\d+)\b', window))
    if len(giveups) != 1:
        return f'expected exactly one unaddressed talk give-up, saw {len(giveups)}'
    elapsed, gap = int(giveups[0][2]), int(giveups[0][3])
    if not 1 <= elapsed < 15:
        return f'the talk was given up at elapsed={elapsed}, not while it was still turning to the doll'
    if gap != 720:
        return f'the repeat gap after the unaddressed exit was {gap}, expected 720 at attachment 80'
    if re.search(r'\[keepsake\][^\n]*uuid=' + _giveup_kid + r' [^\n]*beat=address\b', window):
        return 'the child addressed the doll again inside the window'


_smoke(_giveup, 200, [
    rf'\[keepsake\][^\n]*uuid={_giveup_kid} [^\n]*talk gave up unaddressed elapsed=\d+ gap=720\b',
])
next(s for s in S if s.id == _giveup).smoke.update(
    verify=_verify_doll_talk_giveup,
    commands=[
        (6, f'execute as {sel("giveup_kid")} run yukkuri status set @s needs.nutrition 25'),
    ])
_snatch_thief_a = _uuid_text(_doll_snatch, 'snatch_thief_a')
_snatch_victim_a = _uuid_text(_doll_snatch, 'snatch_victim_a')
_snatch_thief_b = _uuid_text(_doll_snatch, 'snatch_thief_b')
_snatch_victim_b = _uuid_text(_doll_snatch, 'snatch_victim_b')


def _verify_doll_snatch(window):
    """Both lanes: the taker claims and takes the doll it may not take in its mouth, and the robbed body shows the reaction of its
    own temper and answers by its own nature. The bold child's puff is answered by a puff back that interrupts the carry, so that
    taker puts the doll down where it stands (and may take it home later, its own business); the timid child flees and its taker
    carries the doll home to the nest. One claim and one grab each, each face as declared or as the logged hold that refused it, doll count 2 throughout, and the pair's own
    numbers start the bout at the first scan after the start hold."""
    beats = list(_KEEPSAKE_BEAT_RE.finditer(window))
    if not beats:
        return 'no [keepsake] beat lines logged'
    releases = [int(m[1]) for m in re.finditer(r'\[scenario\][^\n]*\bt=(\d+)[^\n]*\bhold released\b', window)
                if any(u in m[0] for u in (_snatch_thief_a, _snatch_victim_a, _snatch_thief_b, _snatch_victim_b))]
    if not releases:
        return 'no hold release line to time the claim'
    released = max(releases)
    for m in beats:
        u, _, beat, shown, declared, held_by, tier, held_face, _, _, dolls, _ = m.groups()
        problem = _keepsake_beat_problem(beat, shown, declared, held_by, tier, held_face)
        if problem:
            return f'{u}: {problem}'
        if int(dolls) != 2:
            return f'doll conservation broken on {beat}: dolls={dolls}'

    def lane(thief, victim, robbed_face, decision, answer_face, answer_line, homeward):
        # (tick, beat, face, line, where, tail); every beat once, so a second grab would show here too.
        mine = [(int(m[2]), m[3], m[4], m[9], m[10], m[12]) for m in beats if m[1] == thief]
        theirs = [(int(m[2]), m[3], m[4], m[9], m[10], m[12]) for m in beats if m[1] == victim]
        for name in ('claim', 'grab'):
            rows = [r for r in mine if r[1] == name]
            if len(rows) != 1:
                return f'{thief}: expected one {name} beat, saw {[r[1] for r in mine]}'
        for name in ('robbed', 'answer'):
            rows = [r for r in theirs if r[1] == name]
            if len(rows) != 1:
                return f'{victim}: expected one {name} beat, saw {[r[1] for r in theirs]}'
        claim, grab = (next(r for r in mine if r[1] == n) for n in ('claim', 'grab'))
        robbed, answer = (next(r for r in theirs if r[1] == n) for n in ('robbed', 'answer'))
        if not claim[0] < grab[0] <= robbed[0] < answer[0]:
            return f'{thief}/{victim}: beat order {[r[0] for r in (claim, grab, robbed, answer)]} is not claim/grab/answer'
        if not 0 <= claim[0] - released <= 200:
            return f'{thief}: claimed {claim[0] - released} ticks after the hold release, expected within 200'
        if grab[0] - claim[0] > 240:
            return f'{thief}: the bout took {grab[0] - claim[0]} ticks from claim to grab (>240)'
        if answer[0] - robbed[0] > 40:
            return f'{victim}: answer came {answer[0] - robbed[0]} ticks after the robbed beat (the 30-tick delay was missed)'
        if (claim[2], claim[3], claim[4]) != ('smug', 'mine', 'peer') or f'victim={victim}' not in claim[5]:
            return f'{thief}: claim is not the smug mine over {victim}: {claim}'
        if grab[2] != 'smug' or grab[3] != 'none' or grab[4] != 'ground' or f'victim={victim}' not in grab[5]:
            return f'{thief}: grab is not the silent pickup of the doll beside {victim}: {grab}'
        if (robbed[2], robbed[3], robbed[4]) != (robbed_face, 'treasure_stolen', 'ground'):
            return f'{victim}: robbed is not the {robbed_face} stolen-treasure reaction: {robbed}'
        if (answer[2], answer[3], answer[4]) != (answer_face, answer_line, 'peer') or f'decision={decision}' not in answer[5]:
            return f'{victim}: answer is not {decision} seen as {answer_face}/{answer_line}: {answer}'
        if homeward:
            for name in ('leave', 'set_down'):
                rows = [r for r in mine if r[1] == name]
                if len(rows) != 1:
                    return f'{thief}: expected one {name} beat, saw {[r[1] for r in mine]}'
            leave, down = (next(r for r in mine if r[1] == n) for n in ('leave', 'set_down'))
            if not answer[0] < leave[0] < down[0]:
                return f'{thief}: beat order {[r[0] for r in (answer, leave, down)]} is not answer/leave/set_down'
            if not 40 <= leave[0] - grab[0] <= 50:
                return f'{thief}: leave came {leave[0] - grab[0]} ticks after the grab, expected the 45-tick delay'
            if down[0] - grab[0] > 600:
                return f'{thief}: carry took {down[0] - grab[0]} ticks (>600)'
            if (leave[2], leave[3], leave[4]) != ('happy', 'none', 'mouth'):
                return f'{thief}: leave does not show the doll still carried: {leave}'
            if (down[2], down[3], down[4]) != ('satisfied', 'thing_san', 'bed'):
                return f'{thief}: set_down is not the doll left at its own nest: {down}'
            return _keepsake_set_down_problem(down[5])
        if any(r[1] == 'leave' for r in mine):
            return f'{thief}: kept carrying the doll home after the puff that interrupted it'
        drops = [int(m[1]) for m in re.finditer(
            rf'\[body\][^\n]*uuid={thief} t=(\d+)[^\n]*mouth drop item=mcyukkuri:reimu_doll count=1 purpose=KEEPSAKE ', window)]
        prompt = [d for d in drops if 0 < d - answer[0] <= 40]
        if len(prompt) != 1:
            return f'{thief}: expected one interrupted doll drop within 40 ticks of the answer, saw {len(prompt)} of {len(drops)}'
        # What it does with the dropped doll later (notices it, takes it home, or loses its grip again when it gets too hungry to
        # carry) is its own business: only the take-home beats and their order are checked.
        order = {name: i for i, name in enumerate(('notice', 'pick_up', 'carry', 'set_down'))}
        late = [r for r in mine if r[1] in order]
        names = [r[1] for r in late]
        if any(order[b] < order[a] for a, b in zip(names, names[1:])):
            return f'{thief}: the take-home beats after the drop are out of order: {names}'
        if late and late[0][0] <= prompt[0]:
            return f'{thief}: a {late[0][1]} beat came before the drop'
        down = next((r for r in late if r[1] == 'set_down'), None)
        if down is not None:
            if (down[2], down[3], down[4]) != ('satisfied', 'thing_san', 'bed'):
                return f'{thief}: set_down is not the doll left at its own nest: {down}'
            return _keepsake_set_down_problem(down[5])

    return (lane(_snatch_thief_a, _snatch_victim_a, 'angry', 'puff_back', 'cheek_puff', 'puff', homeward=False)
            or lane(_snatch_thief_b, _snatch_victim_b, 'teary', 'flee', 'surprise', 'none', homeward=True))


_smoke(_doll_snatch, 6400, [
    r'\[social\][^\n]*bout reserved kind=BULLY peer=',
    rf'\[keepsake\][^\n]*uuid={_snatch_thief_a} [^\n]*beat=claim shown=smug declared=smug line=mine where=peer ',
    rf'\[keepsake\][^\n]*uuid={_snatch_thief_a} [^\n]*beat=grab shown=smug declared=smug line=none where=ground dolls=2 ',
    rf'\[social\][^\n]*uuid={_snatch_victim_a} [^\n]*aftermath what=doll taker={_snatch_thief_a} face=angry ',
    rf'\[keepsake\][^\n]*uuid={_snatch_victim_a} [^\n]*beat=robbed shown=angry declared=angry line=treasure_stolen where=ground ',
    rf'\[keepsake\][^\n]*uuid={_snatch_victim_a} [^\n]*beat=answer shown=cheek_puff declared=cheek_puff line=puff where=peer ',
    rf'\[body\][^\n]*uuid={_snatch_thief_a} [^\n]*mouth drop item=mcyukkuri:reimu_doll count=1 purpose=KEEPSAKE ',
    rf'\[social\][^\n]*uuid={_snatch_victim_b} [^\n]*aftermath what=doll taker={_snatch_thief_b} face=teary ',
    rf'\[keepsake\][^\n]*uuid={_snatch_victim_b} [^\n]*beat=robbed shown=teary declared=teary line=treasure_stolen where=ground ',
    rf'\[keepsake\][^\n]*uuid={_snatch_victim_b} [^\n]*beat=answer shown=surprise declared=surprise line=none where=peer dolls=2 decision=flee',
    rf'\[keepsake\][^\n]*uuid={_snatch_thief_b} [^\n]*beat=set_down shown=satisfied declared=satisfied line=thing_san where=bed ',
], forbid=[r'\[social\][^\n]*bully strike peer=', r'\[social\][^\n]*self defense strike '])
next(s for s in S if s.id == _doll_snatch).smoke.update(verify=_verify_doll_snatch)

_take_sleep_a = _uuid_text(_doll_take, 'take_sleep_a')
_take_sleep_b = _uuid_text(_doll_take, 'take_sleep_b')
_take_owner = _uuid_text(_doll_take, 'take_owner')
_take_stray = _uuid_text(_doll_take, 'take_stray')
_TOOL_OPERATOR = '2d4c7192-2a6d-4da4-9f94-c82721b9de87'


def _verify_doll_take(window):
    """Each of the four stations answers once: the two sleepers find the doll gone when they wake (one sad, one puzzled, neither
    blaming the player), the owner only tears up, the stranger names the theft and loses regard."""
    beats = list(_KEEPSAKE_BEAT_RE.finditer(window))
    if not beats:
        return 'no [keepsake] beat lines logged'
    for m in beats:
        u, _, beat, shown, declared, held_by, tier, held_face, _, _, _, _ = m.groups()
        problem = _keepsake_beat_problem(beat, shown, declared, held_by, tier, held_face)
        if problem:
            return f'{u}: {problem}'
    seen = {}
    for m in beats:
        seen.setdefault(m[1], []).append(m)
    expect = {
        _take_sleep_a: [('sleep', 'sleep', 'sleep', 'ground', 4), ('pickup_missed', 'sad', 'none', 'ground', 0)],
        _take_sleep_b: [('sleep', 'sleep', 'sleep', 'ground', 4), ('pickup_missed', 'confused', 'none', 'ground', 0)],
        _take_owner: [('pickup_owner', 'teary', 'none', 'ground', 1)],
        _take_stray: [('pickup_taken', 'angry', 'treasure_stolen', 'ground', 0)],
    }
    for who, want in expect.items():
        got = [(m[3], m[4], m[9], m[10], int(m[11])) for m in seen.get(who, ())]
        if got != want:
            return f'{who}: beats {got}, expected {want}'
        for m in seen[who]:
            if m[3] == 'pickup_missed' and not re.search(r'\bat=\([-0-9.]+,[-0-9.]+,[-0-9.]+\)', m[12]):
                return f'{who}: pickup_missed does not name where it looked: {m[12]}'
            if m[3].startswith('pickup_') and m[3] != 'pickup_missed' and f'player={_TOOL_OPERATOR}' not in m[12]:
                return f'{who}: {m[3]} does not name the player who took it: {m[12]}'


_smoke(_doll_take, 1200, [
    rf'\[keepsake\][^\n]*uuid={_take_sleep_a} [^\n]*beat=sleep shown=sleep declared=sleep line=sleep where=ground ',
    rf'\[keepsake\][^\n]*uuid={_take_sleep_b} [^\n]*beat=sleep shown=sleep declared=sleep line=sleep where=ground ',
    rf'\[keepsake\][^\n]*uuid={_take_sleep_a} [^\n]*beat=pickup_missed shown=sad declared=sad line=none where=ground dolls=0 ',
    rf'\[keepsake\][^\n]*uuid={_take_sleep_b} [^\n]*beat=pickup_missed shown=confused declared=confused line=none where=ground dolls=0 ',
    rf'\[keepsake\][^\n]*uuid={_take_owner} [^\n]*beat=pickup_owner shown=teary declared=teary line=none where=ground dolls=1 ',
    rf'\[keepsake\][^\n]*uuid={_take_stray} [^\n]*beat=pickup_taken shown=angry declared=angry line=treasure_stolen where=ground dolls=0 ',
], forbid=[rf'\[keepsake\][^\n]*uuid={_take_owner} [^\n]*line=treasure_stolen'])
_take_commands = [(100, 'execute positioned ~2.6 ~ ~6.5 run yukkuri scenario tool_operator enter'),
                  *((101, f'execute as @p[name=MuseumOperator,distance=..48] run {line}') for line in own('take_owner'))]
# The operator stands where the doll really lies: a doll left beside a body is nudged by that body, so a fixed spot can end up
# more than one block away and the pick refuses. Each take is dropped again at once 34 blocks off the row, inside the field but
# outside every sleeper's 32-block count: the dolls stay real items (the smoke ledger sees the same total) and the sleepers still
# find nothing beside them when they wake.
for _i, _name in enumerate(('take_doll_a', 'take_doll_b', 'take_doll_c', 'take_doll_d')):
    _base = 140 + 10 * _i
    _take_commands += [(_base, f'execute at {sel(_name)} run yukkuri scenario tool_operator move'),
                       (_base + 1, f'yukkuri scenario tool_operator pick_up {sel(_name)}'),
                       (_base + 2, f'execute at {sel("take_sleep_a")} positioned ~34 ~ ~34 run yukkuri scenario tool_operator move'),
                       (_base + 3, 'yukkuri scenario tool_operator drop')]
_take_commands += [(240, 'yukkuri scenario tool_operator leave')]
next(s for s in S if s.id == _doll_take).smoke.update(verify=_verify_doll_take, commands=_take_commands)

# The two regressions under the snatch scene use the same bout and strike without a doll or a held treasure.
_bully_thief = _uuid_text('bully_strike', 'bully_thief')
_bully_victim = _uuid_text('bully_strike', 'bully_victim')


def _verify_bully_strike(window):
    """One ordinary bully bout is the point; the window outlasts the bout cooldown, so the pinned pair may well do it again."""
    reserved = re.findall(r'\[social\][^\n]*bout reserved kind=BULLY peer=\S+', window)
    if not reserved:
        return 'no BULLY bout was reserved'
    if not re.search(rf'\[social\][^\n]*uuid={_bully_thief} [^\n]*bully strike peer={_bully_victim} accepted=true ', window):
        return 'the bout did not land its strike'
_smoke('bully_strike', 6000, [rf'\[social\][^\n]*bout reserved kind=BULLY peer={_bully_victim}',
                              rf'\[social\][^\n]*uuid={_bully_thief} [^\n]*bully strike peer={_bully_victim} '])
next(s for s in S if s.id == 'bully_strike').smoke.update(verify=_verify_bully_strike)

_treasure_thief = _uuid_text('treasure_bully', 'treasure_thief')
_treasure_victim = _uuid_text('treasure_bully', 'treasure_victim')
_smoke('treasure_bully', 6000, [
    rf'\[social\][^\n]*bout reserved kind=BULLY peer={_treasure_victim}',
    rf'\[social\][^\n]*uuid={_treasure_thief} [^\n]*bully strike peer={_treasure_victim} ',
    rf'\[treasure\][^\n]*uuid={_treasure_thief} [^\n]*snatched victim={_treasure_victim} item=minecraft:diamond .*victimHeld=0',
    rf'\[social\][^\n]*uuid={_treasure_victim} [^\n]*aftermath what=treasure taker={_treasure_thief} face=teary ',
])

_park_waiter_log = _uuid_text(_park_aid, 'park_waiter')
_park_local_log = _uuid_text(_park_aid, 'park_local')
_park_offer = rf'\[kindness\][^\n]*uuid={_park_local_log} [^\n]*carton offered peer={_park_waiter_log} shelter=(.+) clip=look_around\b'
_park_bite = rf'\[kindness\][^\n]*uuid={_park_local_log} [^\n]*bite handed peer={_park_waiter_log} clip=offer_bite\b'
_park_swallow = rf'\[meal\][^\n]*uuid={_park_waiter_log} [^\n]*swallowed item=minecraft:apple count=1 [^\n]*feeder={_park_local_log}\b'


def _verify_park_aid(window, trace):
    stage = re.search(rf'\[scenario\][^\n]*uuid={_park_waiter_log} [^\n]*rolled=\{{attachment=(\d+)\}}', window)
    if not stage:
        return 'Missing seeded waiter attachment'
    staying = int(stage[1]) >= 75
    bite, swallow, offer = (list(re.finditer(pattern, window)) for pattern in (_park_bite, _park_swallow, _park_offer))
    if not (len(bite) == len(swallow) == len(offer) == 1 and bite[0].start() < swallow[0].start() < offer[0].start()):
        return 'Expected one linked bite, swallow and carton gesture in order'
    core = re.escape(offer[0][1])
    outcome, clip = ('stay', 'droop') if staying else ('follow', 'look_around')
    decisions = list(re.finditer(rf'\[kindness\][^\n]*uuid={_park_waiter_log} t=(\d+)[^\n]*carton decision=(\w+) local=(\S+) shelter=(.+) clip=(\w+)', window))
    if len(decisions) != 1:
        return f'Expected one waiter decision for the carton gesture, saw {len(decisions)}'
    decision = decisions[0]
    if decision.start() <= offer[0].start() or decision.groups()[1:] != (outcome, _park_local_log, offer[0][1], clip):
        return f'The waiter did not choose {outcome} after the gesture to the same carton'
    after = window[decision.end():]
    settled = re.search(rf'\[habitat\][^\n]*uuid={_park_waiter_log} [^\n]*found settled shelter={core} kind=CARDBOARD occupants=1\b', after)
    ended = re.search(rf'\[pet\][^\n]*uuid={_park_waiter_log} [^\n]*vigil ended in invited cover shelter={core} local={_park_local_log} former={_uuid_text(_park_aid, "former_owner")}\b', after)
    if not staying and not (settled and ended and settled.start() < ended.start()):
        return 'The waiter did not settle as the claimant at the offered core before ending the vigil'
    if staying and (settled or ended):
        return 'The waiter declined and then settled at the invited core'
    frames = [json.loads(line.split('[trace] ', 1)[1]) for line in trace]
    offered_at = int(re.search(r'\bt=(\d+)', offer[0][0])[1])
    answered_at = int(decision[1])
    # A goal stays active between trace deltas, including when it started before the look-back.
    goal_changes = [(row['t'], row['st']['goals']) for row in frames if row['k'] == 'st'
                    and row['u'] == _park_waiter_log and row['t'] < answered_at and 'goals' in row['st']]
    goal_changes.append((answered_at, ()))
    if any(max(start, offered_at) < end and 'ForageGoal@4' in goals
           for (start, goals), (end, _) in pairwise(goal_changes)):
        return 'Ordinary foraging took MOVE before the waiter answered the live gesture'
    clips = [row['st']['animation'] for row in frames if row['k'] == 'st' and row['u'] == _park_waiter_log
             and row['t'] >= answered_at and 'animation' in row['st']]
    if staying:
        if 'droop' not in clips or not any(clip != 'droop' for clip in clips[clips.index('droop') + 1:]):
            return 'The waiter did not show the refusal and resume ordinary activity'


def _park_aid_vigil_readout(readout):
    values = re.findall(r'has the following entity data: (.+)', readout)
    if len(values) < 4 or values[1].replace(' ', '') != _park_former:
        return 'The surviving waiter needs its original former owner'
    attachment = int(values[2]) + int(values[3])
    staying = attachment >= 75
    if values[0] != ('1b' if staying else '0b'):
        return 'The saved vigil must reflect the staged waiter’s choice and actual settlement'
    if staying:
        if len(values) != 5 or not any('Dimension: "minecraft:overworld"' in entry
                                      and re.search(rf'\bPos: {_park_carton_core}L\b', entry)
                                      for entry in re.findall(r'\{[^{}]+\}', values[4])):
            return 'The active vigil must save the declined carton’s dimension and core'
    elif len(values) != 4:
        return 'A completed vigil must clear its carton answers'


_smoke(_park_aid, 500, [_park_bite, _park_swallow, _park_offer], readout=[
    f'data get entity {_park_waiter} YukkuriPet.Waits', f'data get entity {_park_waiter} YukkuriPet.FormerOwner',
    f'data get entity {_park_waiter} YukkuriLife.Personality.Innate[3]',
    f'data get entity {_park_waiter} YukkuriLife.Personality.Experience[3]',
    f'data get entity {_park_waiter} YukkuriPet.VigilAnswers', 'data get block ~10 ~ ~-4 Residents',
])
next(s for s in S if s.id == _park_aid).smoke.update(verify=_verify_park_aid, verify_trace=True,
                                                    verify_readout=_park_aid_vigil_readout, gating_checks=['END'])

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
# A hunting body keeps its hunt face: no teary face is ever shown for a hunt beat, and the chase beat shows the stare.
_HUNT_NOT_TEARY = [r'\[predator\][^\n]* beat=\w+ face=(?:teary\w*|surprise|happy) ']
_smoke('sacrifice', 1200, [r'\[grief\][^\n]* protection diverted [^\n]*response=protect_child_worry',
                           r'\[predator\][^\n]* beat=predator_chase face=(?:angry_stare|down_angry|angry) line=none'],
       forbid=_HUNT_NOT_TEARY)
_smoke('pred_range', 1800, [r'\[predator\] flandre [^\n]* prey selected [^\n]* distance=(?:4[2-9]|5[0-4])\.',
                            r'\[predator\] flandre [^\n]* prey bite [^\n]* accepted=true',
                            r'\[predator\] flandre [^\n]* prey bite [^\n]* alive=false',
                            r'\[predator\] flandre [^\n]* remains feeding via=(?:eat_pause|scavenge) [^\n]* accepted=true',
                            r'\[predator\][^\n]* beat=predator_chase face=(?:angry_stare|down_angry|angry) line=none',
                            r'\[predator\][^\n]* beat=predator_dive face=(\w+) line=none declared=\1 ',
                            r'\[predator\][^\n]* beat=predator_feed face=(\w+) line=none declared=\1 '],
       forbid=_HUNT_NOT_TEARY)
next(s for s in S if s.id == 'pred_range').smoke.update(
    verify=_range_pursuit, verify_trace=True,
    allow_fate=[dict(kind='death', who=f'uuid={_uuid_text("pred_range", "range_prey")} ', match=r'cause=(?:mob\b|generic internal=leak\b)',
                     why='the far hunt in this scene ends with the prey killed by the predator\'s bite or the wound it left, then eaten')])
_smoke('colony_hunt', 2400, [r'\[settlement\][^\n]* event=hunt_call [^\n]*detail=party \d+ dest '])
for _exile_scene, _scope in (('colony_mold_exile', 'colony'), ('den_mold_exile', 'family')):
    # The exile mourns its lost home (a grief course) before it comes back to ask: the refusal comes later than before.
    _smoke(_exile_scene, 4800, [
        r'\[settlement\][^\n]* mold witnessed [^\n]* response=drive',
        rf'\[settlement\][^\n]* mold exile departed scope={_scope}\b',
        r'\[settlement\][^\n]* mold exile refused return\b',
        r'\[settlement\][^\n]* mold exile withdrew after refusal\b',
        r'\[settlement\] marisa [^\n]* mold exile answer=FAREWELL\b',
        *([rf'\[family\][^\n]*uuid={_uuid_text("den_mold_exile", "molded")} [^\n]* mold exile child stays '
           rf'child={_uuid_text("den_mold_exile", "baby")} healthy_parent={_uuid_text("den_mold_exile", "healthy")}\b',
           r'\[settlement\][^\n]* mold witnessed [^\n]* fond=true\b',
           r'\[settlement\][^\n]* mold witnessed [^\n]* harsh=true\b',
           r'\[settlement\][^\n]* mold exile protest [^\n]* answer=plead lookBacks=2\b',
           r'\[settlement\][^\n]* mold exile looked back left=0\b',
           rf'\[family\][^\n]*uuid={_uuid_text("den_mold_exile", "baby")} [^\n]* mold exile child '
           rf'(?:cries parent={_uuid_text("den_mold_exile", "molded")}\b|missed departure parent={_uuid_text("den_mold_exile", "molded")} reason=(?:out_of_sight|out_of_range)\b)',
           r'\[emotion\][^\n]* course begun kind=grief\b'] if _scope == 'family' else []),
    ], readout=[f'yukkuri status {sel("molded")} raw'])
_smoke('colony_ration', 3600, [r'\[settlement\][^\n]* event=ration [^\n]*detail=relief \d+/\d+ to [1-9]\d* short households',
                               r'\[settlement\][^\n]* event=ration_fetched '])


# Subjects of the scenes merged in the curation round: each merged lane or variant shows up in the log.
def _distinct(pattern, count, what):
    def verify(window):
        found = set(re.findall(pattern, window))
        return None if len(found) >= count else f'{what}: {len(found)} of {count} ({sorted(found)})'
    return verify


def _den_mold_exile_verify(window):
    subject = rf'\[settlement\][^\n]*uuid={_uuid_text("den_mold_exile", "molded")} [^\n]* mold exile '
    for event in ('appeals', 'return plea spoken=true', 'withdrew after refusal'):
        count = len(re.findall(subject + event + r'\b', window))
        if count != 1:
            return f'expected one exile event {event}, found {count}'
    if re.search(subject + r'withdrew without answer\b', window):
        return 'exile withdrew again without another appeal'


next(s for s in S if s.id == 'den_mold_exile').smoke['verify'] = _den_mold_exile_verify


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
for _sid, _outcome, _beg_checks in (
    ('wild', 'shared', [r' beg transfer [^\n]*result=shared received=minecraft:apple retained=minecraft:apple',
                        r'\[meal\] reimu [^\n]*swallowed item=minecraft:apple count=1',
                        r'\[meal\] marisa [^\n]*swallowed item=minecraft:apple count=1']),
    ('wild_beg_refuse', 'refused', [r' beg refusal [^\n]*response=PESTER', r' beg circled ',
                                  r'\[meal\] reimu [^\n]*swallowed item=minecraft:apple count=1']),
    ('wild_beg_snatch', 'snatched', [r' beg transfer [^\n]*result=snatched received=minecraft:apple retained=minecraft:air',
                                    r' beg grudge [^\n]*closeness=-?\d+->-\d+ fear=\d+->\d+',
                                    r'\[threat\][^\n]* marisa [^\n]* response started source=',
                                    r'\[meal\] marisa [^\n]*swallowed item=minecraft:apple count=1']),
    ('wild_beg_help', 'asked_help', [r' beg help reached human=',
                                     r'\[npc-care\][^\n]* action=pet_help [^\n]*attitude=CARING',
                                     r'\[npc-care\][^\n]* action=pet_comfort ',
                                     r'\[threat\][^\n]* marisa [^\n]* response started source=',
                                     r'\[meal\] reimu [^\n]*swallowed item=minecraft:apple count=1']),
):
    _smoke(_sid, 1200, [
        rf'\[status\] reimu [^\n]* identity observed kind={"npc_companion" if _sid == "wild_beg_help" else "pet"}\b',
        r'\[status\] marisa [^\n]* identity observed kind=wild_family\b',
        r'\[badge\] marisa [^\n]* beg social stand ',
        r'\[speech\] marisa [^\n]* cue event=beg_ask spoken=true',
        rf'\[wild-event\][^\n]*event=ended [^\n]*card=beg_bite [^\n]*elapsed=(?:[4-9]\d\d|1[01]\d\d|1200) detail={_outcome}\b',
        *_beg_checks,
    ], readout=[f'yukkuri status {sel(kind)} raw' for kind in ('pet', 'wild')])
    _beg_smoke = next(s for s in S if s.id == _sid).smoke
    _beg_smoke['gating_checks'] = ['LEDGER', 'END', 'PROGRESS']
    if _outcome == 'snatched':
        def _beg_grudge(window):
            changes = re.findall(r'\[badge\] reimu [^\n]* beg grudge [^\n]*closeness=(-?\d+)->(-?\d+) fear=(\d+)->(\d+)', window)
            if len(changes) != 1 or not all(int(c1) == max(-100, int(c0) - 20) and int(q1) > int(q0)
                                          for c0, c1, q0, q1 in changes):
                return f'expected one directed grudge from a completed snatch, got {changes}'
        _beg_smoke['verify'] = _beg_grudge
_smoke('badge', 1200, [r'\[badge\] marisa [^\n]*badge sight [^\n]*grade=4\b',
                     r'\[wild-event\][^\n]*event=ended [^\n]*card=envy [^\n]*detail=envy_\w+'],
    readout=[f'yukkuri status {sel(kind)} raw' for kind in ('pet', 'wild')])
_smoke('puff_response', 650, [r'\[social\][^\n]*puff response=(?:flee|ignore|puff_back|attack) [^\n]*esteem=[^\n]*wariness=[^\n]*insistence=[^\n]*combat='],
    readout=[f'yukkuri status {sel(f"puff_target{i}")} raw' for i in range(2)])
def _lead_turns(window):
    """At a turn, willingness favors the patient pet. A miss either steps aside onto ground the log names or rejoins at once.
    Whether a step aside reaches
    its goal depends on the owner's walk, so only the decision is asserted."""
    turns = {'reimu': [], 'marisa': []}
    for line in window.splitlines():
        found = re.search(r'\[lesson\] (reimu|marisa) #[^\n]* lead turn owner=\S+ waits=(true|false) aside=(true|false)', line)
        if found is not None:
            turns[found[1]].append((found[2] == 'true', found[3] == 'true'))
    if not turns['reimu']:
        return 'the patient pet never met a turn on the lead'
    if not any(waited for waited, aside in turns['reimu']):
        return f'the patient pet never waited at a turn: {turns["reimu"]}'
    if not turns['marisa']:
        return 'the impatient pet never met a turn on the lead'
    if all(waited for waited, aside in turns['marisa']):
        return f'the impatient pet waited at every turn: {turns["marisa"]}'
    return None


_smoke('training_walk', 2300, [
    r'\[lesson\] reimu [^\n]*lead turn owner=[^ ]+ waits=true',
    r'\[lesson\] marisa [^\n]*lead turn owner=[^ ]+ waits=(?:true|false) aside=(?:true|false)',
], readout=[f'yukkuri status {sel(n)} raw' for n in ('walk_practised', 'walk_novice')])
next(s for s in S if s.id == 'training_walk').smoke['verify'] = _lead_turns
def _counting_experience(window):
    answers = re.findall(r'\[lesson\][^\n]*uuid=(\S+) t=(\d+)[^\n]* study answered [^\n]*success=(true|false)', window)
    awards = []
    for actor, tick, before, after, old, new in re.findall(
            r'\[upbringing\][^\n]*uuid=(\S+) t=(\d+)[^\n]* cause=(?:counted|learned_counting) trait=INTELLIGENCE'
            r' value=(\d+) -> (\d+) remainder=(-?\d+) -> (-?\d+)', window):
        if 4 * (int(after) - int(before)) + int(new) - int(old) != 1:
            return f'{actor} at {tick}: expected one adult counting unit'
        awards.append((actor, tick))
    successes = [(actor, tick) for actor, tick, success in answers if success == 'true']
    if sorted(successes) != sorted(awards):
        return f'counting successes {successes} differ from intelligence awards {awards}'


_smoke('training_study', 1750, [
    r'\[lesson\][^\n]*study answered skill=COUNTING source=food count=6 success=false',
    r'\[lesson\][^\n]*study answered skill=COUNTING source=food count=3 success=true',
], readout=[f'data get entity {sel("counter")} YukkuriSkills', f'data get entity {sel("counter")} YukkuriLife.Personality',
            'data get block ~6 ~-1 ~2 Items'])
next(s for s in S if s.id == 'training_study').smoke['verify'] = _counting_experience
_smoke('balance_relation', 600, [r'\[status\] reimu [^\n]* identity observed kind=wild_family\b'],
    readout=[f'yukkuri status {sel(f"fear{fear}")} raw' for fear, _ in _BALANCE_FEAR])
next(s for s in S if s.id == 'balance_relation').smoke['verify'] = _distinct(
    r'\[status\] reimu #\d+ uuid=(\S+)[^\n]* identity observed kind=wild_family\b', 3, 'relation fixtures')


# The widowed mother mourns at the den first (a grief course's peak), so her first trip comes a couple of minutes after the death.
_smoke('den_family', 9000, [r'\[habitat\] reimu [^\n]* home chosen', r'\[habitat\] marisa [^\n]* home chosen',
                            r'\[forage\] reimu [^\n]* begin role=',
                            rf'\[emotion\][^\n]*uuid={_uuid_text("den_family", "mother")} [^\n]* course begun kind=grief\b',
                            rf'\[emotion\][^\n]*uuid={_uuid_text("den_family", "mother")} [^\n]* course stage kind=grief stage=easing\b',
                            rf'\[emotion\][^\n]*uuid={_uuid_text("den_family", "mother")} [^\n]* mourn focus target=remains[^\n]* approach=true\b',
                            rf'\[emotion\][^\n]*uuid={_uuid_text("den_family", "mother")} [^\n]* grief vow event=vow_partner_young [^\n]*spoken=true\b',
                            rf'\[emotion\][^\n]*uuid={_uuid_text("den_family", "child")} [^\n]* grief vow event=(?:vow_to_father|vow_blame_parent) [^\n]*lost=FATHER spoken=true\b'],
       readout=[f'yukkuri status {sel("mother")} raw'])


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

    focus = events(mother, 'emotion', r'mourn focus target=remains distance=(\S+) approach=true attached=true yawError=(\S+)')
    if len(focus) != 1:
        return f'den family: expected one maternal approach to the remains, saw {focus}'
    distance, yaw_error = map(float, focus[0][1])
    if not (0 < distance <= 6 and yaw_error <= 15):
        return f'den family: maternal approach started at {distance} blocks with yaw error {yaw_error}'
    end = events(mother, 'emotion', r'mourn approach ended result=(?:arrived|blocked|timeout) distance=(\S+) yawError=(\S+)')
    if len(end) != 1:
        return f'den family: expected one approach ending, saw {end}'
    remaining, yaw_error = map(float, end[0][1])
    if not (0 <= remaining < distance and yaw_error <= 15):
        return f'den family: mother went from {distance} to {remaining} blocks with final yaw error {yaw_error}'
    if re.search(r'\[emotion\][^\n]* mourn focus [^\n]* approach=true attached=false\b', log):
        return 'den family: an unattached mourner started walking'

    outings = events(mother, 'forage', r'begin role=FAMILY phase=OUT\b')
    departures = [tick for tick, _ in outings if tick > death_tick]
    if any(tick < death_tick for tick, _ in outings):
        # The same outing can resume gathering if she already reached the food when grief paused it.
        departures += [tick for tick, _ in events(mother, 'forage', r'goal active phase=(?:OUT|GATHER)\b') if tick > death_tick]
    departures.sort()
    if not departures:
        return 'den family: mother did not start or resume an outing after father death'
    if events(mother, 'family', rf'witnessed kin death dead={father}\b'):
        grief = events(mother, 'status', r'behavior\.animation: [^\n]*? -> bereaved\b')
        if not any(death_tick <= tick < departures[0] for tick, _ in grief):
            return 'den family: mother left before playing witnessed bereavement'

    deposits = events(mother, 'forage', rf'deposit home={re.escape(homes[0])} moved=[1-9]\d*\b')
    if not any(tick > departures[0] for tick, _ in deposits):
        return 'den family: no positive deposit from the mother after her departure'
    if not any(tick > death_tick for tick, _ in events(child, 'meal', r'swallowed item=\S+')):
        return 'den family: child did not eat after father death'
    # The young grieves its father before it complains about the corpse smell: the complaint may only start after the
    # course fades (mind/EmotionCourse.mourning() is false from there on).
    complaints = [tick for tick, _ in events(child, 'cannibal', r'started mode=AVOID\b')]
    faded = [tick for tick, _ in events(child, 'emotion', r'course stage kind=grief stage=fading\b')]
    if complaints and (not faded or complaints[0] < faded[0]):
        return f'den family: child complained about the smell at {complaints[0]} before its grief faded at {faded}'
    return None


next(s for s in S if s.id == 'den_family').smoke['verify'] = _den_family_verify

def _struck_speech(window):
    events = {'FLEE': ('hurt_flee',), 'FREEZE': ('hurt_freeze',), 'PUFF_FIGHT': ('hurt_defy', 'puff'),
              'PLEAD': ('hurt_tame_plea', 'hurt_plea', 'plea_spare'), 'ENDURE': ('hurt_endure',)}
    strikes = re.findall(r'\[threat\][^\n]* uuid=(\S+) t=(\d+)[^\n]* struck stance=(\w+) attacker=\S+ loss=(\S+) [^\n]* identity=(\w+) [^\n]*?dialogue=(\S*)', window)
    if not strikes:
        return 'Missing struck stance and spoken line'
    for actor, tick, stance, loss, identity, key in strikes:
        lines = re.findall(rf'\[speech\][^\n]* uuid={actor} t={tick} [^\n]* line event=(\S+) key=(\S+)', window)
        matching = [(event, line) for event, line in lines if any(event in group for group in events.values()) or event == 'afraid']
        allowed = events[stance]
        if stance == 'PLEAD':
            # An unhurt plea asks to be spared; only a kept animal may ask a keeper it trusts to treat the wound.
            allowed = ('plea_spare',) if float(loss) == 0 else ('hurt_plea',) \
                if identity in ('wild_family', 'wild_settlement', 'stray', 'house') else ('hurt_tame_plea', 'hurt_plea')
        if len(matching) != 1 or matching[0] != lines[-1] or matching[0][0] not in allowed or matching[0][1] != key:
            return f'{actor} at {tick} chose {stance} but its speech was {lines}'


_food_choice_smoke = next(s for s in S if s.id == 'choice_food').smoke
_food_choice_counts = _food_choice_smoke['verify']
_demanding_stray = sel('food_demand')
_food_choice_smoke['commands'] = [
    (1, 'scoreboard players set #choice_food_struck museum 0'),
    # Sample spawn support at +1 and +20 before a blow can lift the beggar.
    # askForFood sets LastFoodRequestNutrition only after delivering the request.
    *((tick, f'execute if score #choice_food_struck museum matches 0 if data entity {_demanding_stray} LastFoodRequestNutrition '
             f'store success score #choice_food_struck museum run damage {_demanding_stray} 1 minecraft:mob_attack by {sel("apple_holder")}')
      for tick in range(24, _food_choice_smoke['ticks'], 20)),
]


def _verify_struck_food_choice(log):
    if problem := _food_choice_counts(log):
        return problem
    actor = _uuid_text(_ugly, 'food_demand')
    request_tick, human = re.search(rf'\[social\][^\n]* uuid={actor} t=(\d+) [^\n]* human food request target=(\S+)', log).groups()
    voice = re.search(rf'\[speech\][^\n]* uuid={actor} t={request_tick} [^\n]* line event=human_food_demand [^\n]* until=(\d+)', log)
    hits = re.findall(rf'\[threat\][^\n]* uuid={actor} t=(\d+)[^\n]* struck stance=(\w+) attacker={human} [^\n]* identity=stray\b', log)
    if not voice or len(hits) != 1 or not int(request_tick) < int(hits[0][0]) < int(voice[1]):
        return f'the addressed human must strike during the voiced demand: request={request_tick}, hits={hits}'
    if hits[0][1] not in ('FLEE', 'PUFF_FIGHT'):
        return f'the struck bold stray must flee or puff and fight, got {hits}'
    return _struck_speech(log)


_food_choice_smoke['verify'] = _verify_struck_food_choice


def _home_greedy_retreat(window, trace):
    if problem := _struck_speech(window):
        return problem
    answers = re.findall(r'\[home-claim\][^\n]* uuid=(\S+) t=(\d+) [^\n]* driven off member=\1 answer=CURSE', window)
    if len(answers) != 1:
        return f'Expected one curse from the claimant, found {answers}'
    actor, start = answers[0]
    start = int(start)
    stage = re.search(rf'\[home-claim\][^\n]* HOLD -> RETREAT [^\n]*site=[^\n]* BlockPos'
                      rf'\{{x=(-?\d+), y=(-?\d+), z=(-?\d+)\}} [^\n]*members=\[{actor}\]', window)
    end = re.search(rf'\[home-claim\] t=(\d+)[^\n]* ended claim=[^\n]*members=\[{actor}\][^\n]* result=driven_off', window)
    if not stage or not end:
        return 'Missing start or end of the same claimant’s retreat'
    end = int(end[1])
    frames = {}
    for line in trace:
        row = json.loads(line.split('[trace] ', 1)[1])
        if row['k'] == 'st' and row['u'] == actor and row['t'] >= start:
            frames[row['t']] = row['st']
    motions = Path(__file__).parents[1] / 'src/main/resources/assets/mcyukkuri/yukkuri_presentation/motion_corrections.json'
    stamp = json.loads(motions.read_text(encoding='utf-8'))['models']['marisa']['clips']['stamp']
    stamps = [t for t, frame in frames.items() if t < end and frame['animation'] == 'stamp']
    if stamps != list(range(start, start + round(stamp['duration'] * 20))) or len({tuple(frames[t]['p']) for t in stamps}) != 1:
        return 'The claimant must show one complete stationary stamp'
    first_step = next((frame for t, frame in frames.items() if start + len(stamps) <= t < end
                       and frame['p'] != frames[start]['p']), None)
    if not first_step or (first_step['behavior'], first_step['animation'], first_step['face']) != ('home_claim_retreat', 'waddle', 'angry'):
        return 'The claimant’s first moving retreat frame must be an angry waddle'
    x, y, z = map(int, stage.groups())
    if end not in frames or math.dist(frames[end]['p'], (x + .5, y, z + .5)) < 16:
        return 'The claimant ended the retreat before reaching the 16-block exit radius'
    # The velvet nest is two cells east and five north of the south-facing, left-hinged door.
    if problem := _oak_door_crossing(window, frames, actor, start, end, x - 2, y, z + 5, inward=False):
        return problem
    flight = [frame['p'] for t, frame in frames.items() if t > end and frame['behavior'] == 'flee']
    if not flight or not any(math.dist(flight[0], pos) > .5 for pos in flight):
        return 'The claimant did not move during panic'


def _oak_door_crossing(window, frames, actor, start, end, door_x, y, door_z, inward):
    def progress(pz):
        return door_z + 1 - pz if inward else pz - door_z

    boxes = (json.loads(row) for row in re.findall(r'MCYGEOMETRY (\{[^\n]+\})', window))
    box = next((row['box'] for row in boxes if row['uuid'] == actor), None)
    if box is None:
        return 'Missing collision width of the walker'
    radius = (box[3] - box[0]) / 2
    cleared = next((t for t in range(start, end + 1) if t in frames and progress(frames[t]['p'][2]) >= 1 + radius), None)
    if cleared is None or start not in frames or progress(frames[start]['p'][2]) >= -radius:
        return 'The walker did not reach the opposite side of the doorway'
    crossing = []
    for t in range(start, cleared + 1):
        if t not in frames:
            return 'Missing frame during the doorway crossing'
        px, py, pz = frames[t]['p']
        if frames[t]['veh'] is not None or (t > start and math.dist(frames[t-1]['p'], frames[t]['p']) > 1):
            return 'The walker was carried or jumped across part of the approach'
        if progress(pz) <= -radius:
            crossing.clear()  # The whole body backed out. The next approach must cross both halves on its own.
        elif door_z - radius + 1e-7 < pz < door_z + 1 + radius - 1e-7:
            if px - radius < door_x - 1e-7 or px + radius > door_x + 13/16 + 1e-7 or abs(py - y) > 1e-6:
                return 'The walker crossed outside the clear gap between the jamb and oak leaf'
            if inward and frames[t]['behavior'] != 'return_home':
                return 'The pet gave up walking home before its trailing edge cleared the door'
            crossing.append(pz)
    if not any(door_z <= pz < door_z + .5 for pz in crossing) or not any(door_z + .5 <= pz < door_z + 1 for pz in crossing):
        return 'The walker skipped part of the door cell'


def _pet_door_return(window, trace):
    x, z = BY_ID['house'].anchor
    bed = rf'BlockPos\{{x={x}, y={FEET}, z={z + 4}\}}'
    home_tick = re.search(rf'\[home\][^\n]* uuid={_return_pet} t=(\d+) [^\n]*assigned kind=care [^\n]*{bed}', window)
    sleep_tick = re.search(rf'\[sleep\][^\n]* uuid={_return_pet} t=(\d+) [^\n]*begin source=life occupancy=INSIDE_SUPPORTED bed={bed}', window)
    if not home_tick or not sleep_tick:
        return 'The returning pet needs its own assigned indoor bed and must fall asleep in it'
    frames = {}
    for line in trace:
        row = json.loads(line.split('[trace] ', 1)[1])
        if row['u'] == _return_pet and row['k'] == 'st':
            frames[row['t']] = row['st']
    return _oak_door_crossing(window, frames, _return_pet, int(home_tick[1]), int(sleep_tick[1]), x, FEET, z + 7, inward=True)


def _return_pet_identity(readout):
    if not re.search(r'identity:\{[^}]*kind:"pet"[^}]*owner:"2d4c7192-2a6d-4da4-9f94-c82721b9de87"', readout):
        return 'The returning animal must still belong to the staged player'


_smoke('pet_door_return', 700, ['MCY_PET_RETURN_OPEN', 'MCY_PET_RETURN_OWNED'], readout=[f'yukkuri status {sel("return_pet")} raw'])
next(s for s in S if s.id == 'pet_door_return').smoke.update(verify=_pet_door_return, verify_trace=True, verify_readout=_return_pet_identity, commands=[
    (1, 'yukkuri scenario tool_operator enter'),
    (2, f'execute store result score #return_owner museum run yukkuri scenario owner_at {sel("return_pet")} away'),
    (3, 'execute if score #return_owner museum matches 1 run say MCY_PET_RETURN_OWNED'),
    (4, 'yukkuri scenario tool_operator leave'),
    (40, 'execute if block ~ ~ ~7 minecraft:oak_door[facing=south,hinge=left,half=lower,open=true] '
         'if block ~ ~1 ~7 minecraft:oak_door[facing=south,hinge=left,half=upper,open=true] '
         'if block ~-1 ~ ~7 minecraft:oak_planks if block ~1 ~ ~7 minecraft:oak_planks run say MCY_PET_RETURN_OPEN'),
])


def _resident_house_chain(window):
    window = '\n'.join(window.splitlines())
    founders = list(re.finditer(
        r'\[house\] house_\w+ #[0-9]+ uuid=([\w-]+) t=\d+ [^\n]* a house \w+ moved in at (\d+), (\d+), (\d+) \(entrance',
        window))
    if len(founders) != 1:
        return f'Expected one first founder, saw {len(founders)}'
    founder = founders[0]
    site = tuple(map(int, founder.groups()[1:]))
    if not (_cottage_x + 1 <= site[0] <= _cottage_x + 7 and site[1] == FEET
            and _cottage_z + 8 <= site[2] <= _cottage_z + 11):
        return f'Founder arrived outside the cottage interior at {site}'
    pos = rf'BlockPos\{{x={site[0]}, y={site[1]}, z={site[2]}\}}'
    actions = re.findall(rf'\[house\][^\n]*uuid={founder[1]} [^\n]*? ((?:pickup|nest founded|deposit) [^\n]*)',
                         window[founder.end():])
    expected = [r'pickup source=.* item=mcyukkuri:nest_lint count=1 feeding=false',
                rf'nest founded home=ResourceKey\[minecraft:dimension / minecraft:overworld\] {pos} identity=.*',
                rf'deposit home={pos} item=mcyukkuri:nest_lint count=1 food=0 lint=1']
    if len(actions) < 3 or not all(re.fullmatch(pattern, line) for pattern, line in zip(expected, actions)):
        return f'Founder {founder[1]} must lift one lint and commit it at the new nest at {site}'


def _resident_house_readings(readout):
    readings = re.split(r'MCYSMOKE_READOUT \d+ \d+\n', readout)[1:]
    if len(readings) != 6:
        return 'Expected six paired household readings'
    companions = set()
    for index, reading in enumerate(readings):
        stock = re.findall(r'region piles=(\d+) units=([\d.]+); within 12 blocks lint=(\d+) crumbs=(\d+)', reading)
        activity = re.findall(r'activity dust=(\d+)/600 settle=(\d+)/1200 swept=false cooldown=(\d+) recent=(\w+) roofed=true', reading)
        if len(stock) != 2 or len(activity) != 2:
            return f'Missing paired roofed readings at snapshot {index}'
        if stock[1][:2] != ('0', '0.0') or activity[1] != ('0', '0', '0', 'false'):
            return f'The empty control accrued stock or activity at snapshot {index}'
        if index == 0 and activity[0] != ('0', '0', '0', 'false'):
            return 'The occupied cottage must start without activity'
        if index < 5 and (float(stock[0][1]) != index or stock[0][2:] != (str(index), '0')
                          or 'resident house_' in reading or 'materials=' in reading):
            return f'Expected {index} lint, zero crumbs and no lodger before arrival at snapshot {index}'
        if f'has the following entity data: {_caretaker_home}L' not in reading:
            return f'The live caretaker is missing from its authored rug at snapshot {index}'
        spawning = 'true' if 0 < index < 5 else 'false'
        if f'doMobSpawning is currently set to: {spawning}' not in reading:
            return f'Unexpected world spawning rule at snapshot {index}'
        pet = re.search(r'has the following entity data: (\[I;[^\]]+\])', reading)
        owner = re.search(r'identity:\{[^}]*kind:"npc_companion"[^}]*owner:"([\da-f-]+)"', reading)
        if not pet or not owner:
            return f'Missing yard companion or custodian at snapshot {index}'
        companions.add((pet[1], owner[1]))
    if len(companions) != 1:
        return 'The original yard companion must keep its custodian'


_resident_house_scene = next(s for s in S if s.id == 'resident_house_lint')
_resident_house_scene.smoke = dict(ticks=4700, readout_at=(20, 800, 1420, 2060, 2680),
    readout=[_occupied_house, _empty_house,
             'execute positioned ~14.5 ~ ~6.5 if block ~ ~ ~ minecraft:red_carpet run data get entity '
             '@e[type=mcyukkuri:caretaker_sister,tag=mcy_yard_owner,distance=..0.75,limit=1] HumanHome',
             'gamerule doMobSpawning', 'execute if block ~14 ~ ~3 mcyukkuri:yukkuri_nest run data get block ~14 ~ ~3 Resident',
             f'data get entity {_yard_pet_selector} UUID', f'yukkuri status {_yard_pet_selector} raw'],
    expect=[r'\[npc-care\][^\n]*action=yard_pet result=spawned owner='],
    forbid=[r'\[body\][^\n]* invariant\b'],
    verify=_resident_house_chain, verify_readout=_resident_house_readings)


# In headless smoke, stand an unrelated peer near the home as a visitor would. The residents still run ordinary AI.
for _sid, _at, _dx, _dz in (('home_greedy', 360, 7.7, 4.6), ('den_family', 6000, 1, -2)):
    _intruder = uid(_sid, 'territory_intruder')
    _spec = next(s for s in S if s.id == _sid).smoke
    _spec['commands'] = [(_at, yk('reimu', _dx, _dz, 'territory_intruder', uuid=_intruder, boldness=0, extra='NoAI:1b'))]
    _spec['expect'] += [rf'\[home-claim\][^\n]* territory {beat} target={_uuid_text(_sid, "territory_intruder")}\b'
                        for beat in ('warn', 'chase')]
    if _sid == 'home_greedy':
        _spec['ticks'] = 1510
        _spec['commands'] += [
            (25, 'execute positioned ~5.5 ~ ~10.5 run yukkuri scenario tool_operator enter'),
            # The visitor opens the existing door before asking the claimant to leave.
            (697, 'execute positioned ~5.5 ~ ~9.5 run yukkuri scenario tool_operator move'),
            (698, 'yukkuri scenario shop_door_click ~5 ~ ~8 south'),
            (699, 'execute if block ~5 ~ ~8 minecraft:oak_door[facing=south,half=lower,hinge=left,open=true] '
                  'if block ~5 ~1 ~8 minecraft:oak_door[facing=south,half=upper,hinge=left,open=true] '
                  'if block ~4 ~ ~8 minecraft:oak_planks if block ~6 ~ ~8 minecraft:oak_planks run say MCYCLAIM_DOOR_OPEN'),
            (700, 'execute positioned ~8 ~ ~2.65 run yukkuri scenario tool_operator move'),
            (702, f'yukkuri scenario care_click {sel("greedy")} decline_event'),
            (1310, f'execute at {sel("greedy")} positioned ~ ~ ~2 run yukkuri scenario tool_operator move'),
            (1312, f'damage {sel("greedy")} 1 minecraft:player_attack by @e[type=minecraft:player,name=MuseumOperator,distance=..64,limit=1]'),
            # Environmental damage triggers panic even when the player's blow selected defiance.
            (1365, f'damage {sel("greedy")} 1 minecraft:in_fire'),
            (1415, 'yukkuri scenario tool_operator leave'),
        ]
        _spec['expect'] += [r'MCYCLAIM_DOOR_OPEN', r'\[home-claim\][^\n]* driven off member=[^ ]+ answer=CURSE',
                            r'\[home-claim\][^\n]* curse stamp spoken=true',
                            r'\[threat\] marisa [^\n]* struck stance=(?:PUFF_FIGHT|FLEE) [^\n]* dialogue=dialogue\.']
        _spec.setdefault('forbid', []).extend([
            r'struck stance=FLEE [^\n]*(?:v[13]_wounded|v1_afraid)\b',
            r'\[speech\] marisa [^\n]* line event=badge_envy(?:_snark)?\b',
        ])
        _spec.update(verify=_home_greedy_retreat, verify_trace=True)

_warm_parent = _uuid_text('indep_near', 'warm_parent')
_warm_teen = _uuid_text('indep_near', 'warm_teen')
_smoke('indep_near', 7200, [
    r'\[independence\][^\n]* farewell started',
    r'\[independence\][^\n]* farewell exchange [^\n]* attachment=85 parentAttachment=85 nuzzleTicks=[1-9]\d* lookBacks=2\b',
    r'\[independence\][^\n]* farewell exchange [^\n]* attachment=20 parentAttachment=20 nuzzleTicks=0 lookBacks=0\b',
    r'\[independence\][^\n]* see off answer [^\n]* attached=true\b',
    r'\[independence\][^\n]* see off answer [^\n]* attached=false\b',
    rf'\[independence\][^\n]*uuid={_warm_teen} [^\n]* farewell rub started parent={_warm_parent} ticks=75\b',
    rf'\[independence\][^\n]*uuid={_warm_teen} [^\n]* farewell rub released parent={_warm_parent} ticks=75 planned=75\b',
    r'\[independence\][^\n]* looked back [^\n]* count=2/2\b',
    rf'\[emotion\][^\n]*uuid={_warm_parent} [^\n]* course begun kind=parting\b',
    rf'\[emotion\][^\n]*uuid={_warm_parent} [^\n]* course goal behavior kind=parting stage=peak behavior=parting_gaze\b',
    rf'\[speech\][^\n]*uuid={_warm_parent} [^\n]* line event=parting_gaze\b'],
    forbid=[rf'\[speech\][^\n]*uuid={_warm_parent} [^\n]* line event=mourn_sob\b'],
    readout=[f'yukkuri status {sel("warm_parent")} raw'])


def _indep_near_verify(log):
    departed = _distinct(r'\[independence\] \w+ #\d+ uuid=(\S+)[^\n]* departed ', 3, 'teens that left home')(log)
    if departed:
        return departed
    course = rf'\[emotion\][^\n]*uuid={_warm_parent} [^\n]* course '
    start = re.search(course + r'begun kind=parting\b', log)
    end = re.search(course + r'ended kind=parting\b', log)
    if not start or not end or end.start() <= start.end():
        return 'indep near: the warm parent did not finish parting'
    if re.search(rf'\[status\][^\n]*uuid={_warm_parent} [^\n]* behavior\.animation: [^\n]* -> droop\b',
                 log[start.end():end.start()]):
        return 'indep near: the warm parent drooped during parting'


next(s for s in S if s.id == 'indep_near').smoke['verify'] = _indep_near_verify
_forage_mother, _forage_father = (_uuid_text('forage', n) for n in ('mother', 'father'))
_smoke('forage', 6000, [r'\[forage\][^\n]* begin role=FAMILY', r'\[forage\][^\n]* deposit home=[^\n]* moved=[1-9]',
                       rf'\[duty\][^\n]*uuid={_forage_mother} [^\n]* claimed kind=WATCH',
                       rf'\[family\][^\n]*uuid={_forage_mother} [^\n]* home watch started kin={_forage_father} stance=PATROL',
                       rf'\[duty\][^\n]*uuid={_forage_mother} [^\n]* released kind=WATCH end=DONE why=kin_returned',
                       r'\[family\][^\n]* welcome home started ',
                       rf'\[family\][^\n]* feed handed to meal child={_uuid_text("forage", "child")}\b'])
_absent_uuid = _uuid_text('family_absence', 'absent')
_stayer_uuid, _older_uuid = (_uuid_text('family_absence', n) for n in ('stayer', 'older'))
_smoke('family_absence', 7200, [rf'\[family\][^\n]* kin overdue kin={_absent_uuid}\b',
                               rf'\[family\][^\n]* missing kin call kin={_absent_uuid}\b',
                               rf'\[duty\][^\n]*uuid={_stayer_uuid} [^\n]* claimed kind=WATCH',
                               rf'\[duty\][^\n]*uuid={_stayer_uuid} [^\n]* released kind=WATCH end=REPLACED',
                               rf'\[duty\][^\n]*uuid={_stayer_uuid} [^\n]* claimed kind=SEARCH',
                               rf'\[family\][^\n]*uuid={_stayer_uuid} [^\n]* kin search waypoint kin={_absent_uuid} index=1/',
                               rf'\[family\][^\n]*uuid={_older_uuid} [^\n]* home watch started kin={_absent_uuid} stance=THRESHOLD'],
       forbid=[rf'\[family\][^\n]* witnessed kin death dead={_absent_uuid}\b'])


def _forage_watch(log):
    actor = rf'[^\n]* uuid={_forage_mother} [^\n]* '
    father = rf'\[forage\][^\n]* uuid={_forage_father} [^\n]* '
    outing = re.search(father + r'begin role=FAMILY phase=OUT\b', log)
    deposit = re.compile(father + r'deposit home=[^\n]* moved=[1-9]').search(log, outing.end()) if outing else None
    if not deposit:
        return 'the father must leave on a family trip and bring food home'
    release_pattern = re.compile(rf'\[duty\]{actor}released kind=WATCH\b[^\n]*')
    for claim in re.finditer(rf'\[duty\]{actor}claimed kind=WATCH\b', log[:deposit.start()]):
        release = release_pattern.search(log, claim.end())
        if not release:
            return 'the mother did not release her home watch'
        watched = log[claim.end():release.start()]
        if re.search(rf'\[habitat\]{actor}gathered item=|\[forage\]{actor}(?:food search started|begin role=)', watched):
            return 'the mother started foraging during her home watch'
        if release.start() < outing.start():
            continue
        if not re.search(rf'\[family\]{actor}home watch started kin={_forage_father} stance=PATROL\b', watched) or 'end=DONE why=kin_returned' not in release[0]:
            return 'the mother must patrol for the foraging father and finish when he returns'
        stations = re.findall(rf'\[family\]{actor}home watch station [^\n]* at=(\S+) mouthDistance=([\d.]+) result=ARRIVED', watched)
        if len({at for at, _ in stations}) < 2 or any(float(distance) < 2 for _, distance in stations):
            return f'the mother must visit at least two patrol stands two or more blocks from the mouth, saw {stations}'
        return None
    return "the mother never claimed a home watch during the father's first forage trip"


def _absence_grief_timing(log):
    stayer = _uuid_of(_stayer)
    found = re.search(rf'\[family\][^\n]* uuid={stayer} t=(\d+) [^\n]* missing kin found remains kin={_absent_uuid}', log)
    trip = re.search(rf'\[forage\][^\n]* uuid={stayer} t=(\d+) [^\n]* begin role=FAMILY phase=OUT', log)
    if not found or not trip or int(trip[1]) - int(found[1]) < 100:
        return 'the survivor must complete the 100-tick bereavement before its first outing'


next(s for s in S if s.id == 'forage').smoke['verify'] = _forage_watch
next(s for s in S if s.id == 'family_absence').smoke['verify'] = _absence_grief_timing
for _sid in ('forage', 'family_absence'):
    next(s for s in S if s.id == _sid).smoke['gating_checks'] = ('END',)
_taste_picky_uuid = _uuid_text('graze', 'taste_picky')
_smoke('graze', 1800, [
    *(rf'\[meal\][^\n]*uuid={_uuid_text("graze", f"taste_{name}")}[^\n]*swallowed item=mcyukkuri:oil_grain_budget[^\n]*verdict={verdict}\b'
      for name, _, _, _, _, verdict in _taste_actors if verdict != 'REFUSE'),
    rf'\[meal\][^\n]*uuid={_taste_picky_uuid}[^\n]*food_complain item=mcyukkuri:oil_grain_budget[^\n]*verdict=REFUSE\b',
    r'MCYGRAZE_PICKY_REMAINS'],
    forbid=[rf'\[meal\][^\n]*uuid={_taste_picky_uuid}[^\n]*swallowed '],
    readout=[f'execute if entity @e[type=minecraft:item,tag={tag("taste_grain_picky")},nbt={{Item:{{id:"mcyukkuri:oil_grain_budget",count:1}}}}] run say MCYGRAZE_PICKY_REMAINS'])
_smoke('food_behind_glass', 260, [r'\[action\][^\n]* end id=glass_press outcome=DONE', r'MCYGLASS_APPLE_REMAINS'],
       forbid=[rf'\[meal\][^\n]* uuid={_uuid_of(_glass_food_eater)} [^\n]* pickup '],
       readout=[f'execute if entity @e[type=minecraft:item,tag={tag("glass_apple")},nbt={{Item:{{id:"minecraft:apple",count:1}}}}] run say MCYGLASS_APPLE_REMAINS',
                'gamerule mobGriefing true'])
next(s for s in S if s.id == 'food_behind_glass').smoke['commands'] = [(1, 'gamerule mobGriefing false')]
next(s for s in S if s.id == 'food_behind_glass').smoke['verify'] = lambda log: _family_search_order(log, [
    (_glass_food_eater, 'food behind glass .*kind=loose'),
    (_glass_food_eater, 'start id=glass_press'), (_glass_food_eater, 'end id=glass_press outcome=DONE'),
], [(_glass_food_eater, 'start id=glass_press')])
_sill_operator = '@e[type=minecraft:player,name=MuseumOperator,distance=..60,limit=1]'
_smoke('food_sill_window', 900, [
    rf'\[meal\][^\n]* uuid={_uuid_of(_sill_beggar)} [^\n]*food behind glass target=\S+ kind=held [^\n]* look=true\b',
    rf'\[meal\][^\n]* uuid={_uuid_of(_sill_beggar)} [^\n]*begging at a pane too high to press'],
    forbid=[rf'\[action\][^\n]* uuid={_uuid_of(_sill_beggar)} [^\n]*start id=glass_press'])
next(s for s in S if s.id == 'food_sill_window').smoke['commands'] = [
    # The holder is in place before the stray's first plan; a stray that has already wandered sideways sees only wall.
    (3, f'execute at {sel("sill_anchor")} run yukkuri scenario tool_operator enter'),
    (4, f'item replace entity {_sill_operator} hotbar.0 with minecraft:apple'),
    (5, 'yukkuri scenario tool_operator select 0'),
    (880, 'yukkuri scenario tool_operator leave')]
_smoke('meal_content', 900, [r'\[meal\][^\n]* full nutrition=[^\n]*content=true'])
next(s for s in S if s.id == 'meal_content').smoke['verify'] = _meal_content_verify
_smoke('meet', 2400, [r'\[social\][^\n]* greet [^\n]*why=first_meet', r'\[daily\][^\n]* session result kind=NUZZLE'])
_step_low, _step_high = (_uuid_of(_SHAPES[name]) for name in ('low', 'high'))
_smoke('nuzzle_shapes', 2400, [
    rf'\[daily\][^\n]*uuid={_uuid_of(_SHAPES["wide"])} [^\n]*session result kind=NUZZLE\b',
    rf'\[daily\][^\n]*uuid={_step_low} [^\n]*session result kind=NUZZLE\b',
    rf'\[daily\][^\n]*uuid={_uuid_of(_SHAPES["wide"])} [^\n]*peer rub started partner={_uuid_of(_SHAPES["small"])} mode=LEVEL\b',
    (rf'\[daily\][^\n]*(?:uuid={_step_low} [^\n]*peer rub started partner={_step_high}'
     rf'|uuid={_step_high} [^\n]*peer rub started partner={_step_low}) mode=LEVEL\b'),
])
_smoke('marisa_sumo', 1800, [r'\[social\][^\n]* sumo completed winner='])
# The north claim leaves both a quarrel record and one of them walks up to make up and is answered; the hostile south pair has its
# record too but neither approaches.
_qn = [_uuid_text('quarrel_makeup', n) for n in ('claimer', 'rival')]
_qs = [_uuid_text('quarrel_makeup', n) for n in ('foe_claimer', 'foe_rival')]
_smoke('quarrel_makeup', 2400, [
    rf'\[social\][^\n]*uuid=(?:{_qn[0]}|{_qn[1]}) [^\n]*quarrel recorded peer=(?:{_qn[0]}|{_qn[1]})\b',
    rf'\[social\][^\n]*uuid=(?:{_qn[0]}|{_qn[1]}) [^\n]*reconcile approach peer=(?:{_qn[0]}|{_qn[1]}) affinity=',
    rf'\[social\][^\n]*uuid=(?:{_qn[0]}|{_qn[1]}) [^\n]*reconcile answered peer=(?:{_qn[0]}|{_qn[1]}) accepted=true\b',
    rf'\[social\][^\n]*uuid=(?:{_qs[0]}|{_qs[1]}) [^\n]*quarrel recorded peer=(?:{_qs[0]}|{_qs[1]})\b',
], forbid=[rf'\[social\][^\n]*uuid=(?:{_qs[0]}|{_qs[1]}) [^\n]*reconcile (?:approach|answered)\b'])
# The timid dull stray pauses 80 ticks and the bold clever one 8 at the strange apple, the starving one (nutrition 5, below the
# no-pause line even after its first apple) not at all; the starving one eats first, and at least one of the others waits behind
# it (once the pile is down to the number of eaters still contesting it, the order applies).
_strange = {name: _uuid_text('stray_strange_food', name) for name in ('timid', 'bold', 'starving')}
_smoke('stray_strange_food', 900, [
    rf'\[meal\][^\n]*uuid={_strange["timid"]} [^\n]*strange_food_pause item=minecraft:apple ticks=(?:[7-9]\d|80) ',
    rf'\[meal\][^\n]*uuid={_strange["bold"]} [^\n]*strange_food_pause item=minecraft:apple ticks=(?:[89]|1\d) ',
    rf'\[meal\][^\n]*uuid={_strange["starving"]} [^\n]*swallowed item=minecraft:apple',
    rf'\[meal\][^\n]*uuid=(?:{_strange["timid"]}|{_strange["bold"]}) [^\n]*food_order wait_behind ',
], forbid=[rf'\[meal\][^\n]*uuid={_strange["starving"]} [^\n]*strange_food_pause'])
# Marisa keeps the tired Reimu company; the unknown timid Chen gives none.
_company_friend = _uuid_text('company_visit', 'friend')
_smoke('company_visit', 900, [
    rf'\[kindness\][^\n]*uuid={_company_friend} [^\n]*kept company peer={_uuid_text("company_visit", "weak")}\b',
], forbid=[rf'\[kindness\][^\n]*uuid={_uuid_text("company_visit", "stranger")} [^\n]*(?:offer act=COMPANY|kept company)\b'])
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


_smoke('alice_hunt', 1200, [r'\[agitation\] alice [^\n]* phase=IDLE -> ALICE_WATCH',
    r'\[family\] alice [^\n]* pressure reason=sukkiri pentUp=9[\d.]+ -> 0\.0\b',
    r'\[agitation\][^\n]* phase=ALICE_APPROACH -> ALICE_ACT',
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

# MD-11's reservation logs the live UUID and the two pens' distinct starting reserves before either stem draws.
_MD11_STEM_START = (r'\[family\] reimu #\d+ uuid=(?P<actor>[0-9a-f-]{36}) [^\n]* '
                    r'stem drain stage 0->1 brood=(?P<brood>[0-9a-f-]{36}) unborn=3 nutrition=')
_MD11_UNFED_MOTHER = _MD11_STEM_START + r'55\.0 energy=60\.0\b'


def _aftermath_growth_verify(window):
    for lane, mother in (('growth', _MD11_STEM_START + r'100\.0 energy=100\.0\b'), ('burden', _MD11_UNFED_MOTHER)):
        start = re.search(mother, window)
        if start is None:
            return f'{lane}: stem mother not identified at reservation'
        prefix = rf'\[(?:family|meal)\] reimu #\d+ uuid={start["actor"]} [^\n]* '
        brood = start['brood']
        growing_window = window[start.end():]
        sprout = re.search(prefix + rf'stem sprouted brood={brood} elapsed=1 duration=(\d+) fruit=3\b', growing_window)
        if sprout is None:
            return f'{lane}: three-fruit stem did not sprout'
        growing = growing_window[sprout.end():]
        ripe = re.search(prefix + rf'stem ripe brood={brood} elapsed={sprout[1]} duration={sprout[1]} fruit=3\b', growing)
        if ripe is None:
            return f'{lane}: the sprouted stem did not ripen'
        for before, after in re.findall(prefix + r'swallowed item=minecraft:apple count=1 nutrition=([\d.]+) -> ([\d.]+)',
                                        growing[:ripe.start()]):
            if float(after) <= float(before):
                return f'{lane}: a swallowed apple did not restore nutrition ({before} -> {after})'
    return None


next(s for s in S if s.id == 'aftermath_growth').smoke['verify'] = _aftermath_growth_verify
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
_smoke('family_search', 7200, [r'\[family\][^\n]* child search reunited', r'\[family\][^\n]* parent dismissed ',
                               r'\[family\][^\n]* child call started ', r'\[family\][^\n]* child heard parent call ',
                               r'\[family\][^\n]* glass vigil started ', r'\[family\][^\n]* glass farewell '])
next(s for s in S if s.id == 'family_search').smoke['verify'] = _family_call_verify
_smoke('family_search_pane', 2400, [r'\[family\][^\n]* glass vigil started ', r'\[action\][^\n]* break id=bash ',
                                    r'\[family\][^\n]* glass crossed ', r'\[family\][^\n]* child search reunited '])
next(s for s in S if s.id == 'family_search_pane').smoke['verify'] = _family_pane_verify
_smoke('rain_open', 1200, [r'\[weather\][^\n]* shelter response started', r'\[needs\][^\n]* soaking damage '])
_smoke('treatment_scars', 1600, [r'\[body\][^\n]* wound healing ', r'\[body\][^\n]* scar (?:formed|not formed) from '])
_smoke('treatment_care', 900, [
    rf'\[npc-care\][^\n]* action=prepare_dose target={_uuid_text("treatment_care","patient")} treatment=ORANGE_JUICE\b',
    rf'\[npc-care\][^\n]* action=treatment target={_uuid_text("treatment_care","patient")} treatment=ORANGE_JUICE [^\n]*result=applied',
    rf'\[npc-care\][^\n]* action=treatment target={_uuid_text("treatment_care","patient")} treatment=FLOUR_PASTE [^\n]*result=applied',
])
_smoke('wound_materials', 400, [], forbid=[r'\[body\][^\n]* death settled'])
_smoke('wound_sources', 490, [
    r'\[interaction\][^\n]* operation started; [^\n]* pose=PIN duration=untimed reserved_item=mcyukkuri:pin_tool count=1',
    r'\[interaction\][^\n]* operation ended; [^\n]* pose=PIN ',
    r'\[interaction\][^\n]* binding returned to world; [^\n]* item=mcyukkuri:pin_tool count=1',
    r'\[interaction\][^\n]* operation started; [^\n]* pose=BOX duration=400 reserved_item=mcyukkuri:iron_enclosure count=1',
    r'\[interaction\][^\n]* binding returned to world; [^\n]* item=mcyukkuri:iron_enclosure count=1',
    r'\[interaction\][^\n]* binding returned to world; [^\n]* item=mcyukkuri:hanging_hook count=1',
    r'\[interaction\][^\n]* shear; contact=[^\n]* above_feet=0\.[1-9]',
    r'\[headwear\][^\n]* accessory destroyed burned=false cause=cut pieces=[23]',
    r'\[interaction\][^\n]* action=CUT_ACCESSORY success=true',
], forbid=[r'\[interaction\][^\n]* operation ended; [^\n]* pose=PIN [^\n]* reason=(?:operator_unreachable|timeout)'])
_floor_pin = 'mcyukkuri:pin_tool[minecraft:custom_data={BDFloor:1b}]'
_floor_pin_drop = '@e[type=item,nbt={Item:{id:"mcyukkuri:pin_tool",count:1,components:{"minecraft:custom_data":{BDFloor:1b}}}},limit=1]'
_pin_player = '@p[name=MuseumOperator,distance=..40]'
_tool_stand = 'attributes:[{id:"minecraft:generic.movement_speed",base:0.0d}]'
next(s for s in S if s.id == 'wound_sources').smoke['commands'] = [
    (4, f'execute at {sel("blunt")} positioned ~ ~ ~1 run yukkuri scenario tool_click {sel("blunt")} enclose'),
    (5, f'execute at {sel("cut")} positioned ~ ~ ~1 run yukkuri scenario tool_operator enter'),
    (5, f'item replace entity {_pin_player} hotbar.0 with {_floor_pin}'),
    (6, f'yukkuri scenario shop_stock_click {sel("cut")}'),
    (7, 'yukkuri scenario tool_operator leave'),
    (8, f'execute at {sel("arrow")} run setblock ~ ~3 ~ minecraft:glass'),
    (9, f'execute at {sel("arrow")} positioned ~ ~ ~1 run yukkuri scenario tool_click {sel("arrow")} hook_hang'),
    (10, f'execute at {sel("thorn")} positioned ~ ~ ~1 run yukkuri scenario tool_click {sel("thorn")} cut_left'),
    (12, f'execute at {sel("cone")} positioned ~ ~ ~1 run yukkuri scenario tool_click {sel("cone")} cut_accessory'),
    # A vanilla data edit round-trips entity NBT without touching the pin or wound fields.
    (15, f'data modify entity {sel("cut")} CustomNameVisible set value 0b'),
    (45, f'execute at {sel("heat")} positioned ~ ~ ~1 run yukkuri scenario tool_click {sel("heat")} pierce'),
    (425, f'execute if data entity {sel("cut")} YukkuriOperations.PinWound '
          f'if data entity {sel("cut")} YukkuriOperations.PendingReturn.components."minecraft:custom_data".BDFloor '
          f'unless entity {_floor_pin_drop} run say MCY_BD07_PIN_STILL_ATTACHED'),
    (426, f'execute at {sel("cut")} positioned ~ ~ ~1 run yukkuri scenario tool_operator enter'),
    (426, f'item replace entity {_pin_player} hotbar.1 with mcyukkuri:yukkuri_carrier'),
    (426, 'yukkuri scenario tool_operator select 1'),
    (426, f'yukkuri scenario shop_stock_click {sel("cut")}'),
    (426, 'yukkuri scenario tool_operator select 0'),
    (427, f'yukkuri scenario shop_stock_click {sel("cut")}'),
    (442, f'execute at {_floor_pin_drop} run yukkuri scenario tool_operator move'),
    (443, f'yukkuri scenario tool_operator pick_up {_floor_pin_drop}'),
    (444, f'execute if items entity {_pin_player} hotbar.0 {_floor_pin} unless entity {_floor_pin_drop} '
          'run say MCY_BD07_SURVIVAL_PIN_RECOVERED'),
    (445, yk('reimu', -5, 5, 'tool_creative', extra=_tool_stand,
             uuid=uid('wound_sources', 'tool_creative'))),
    (464, f'execute at {sel("tool_creative")} positioned ~ ~ ~1 run yukkuri scenario tool_operator move'),
    (464, f'gamemode creative {_pin_player}'),
    (464, f'item replace entity {_pin_player} hotbar.1 with mcyukkuri:pin_tool'),
    (464, 'yukkuri scenario tool_operator select 1'),
    (465, f'yukkuri scenario shop_stock_click {sel("tool_creative")}'),
    (466, f'execute if data entity {sel("tool_creative")} YukkuriOperations.PinWound '
          f'unless data entity {sel("tool_creative")} YukkuriOperations.PendingReturn '
          f'if items entity {_pin_player} hotbar.1 mcyukkuri:pin_tool run say MCY_BD07_CREATIVE_PIN_RETAINED'),
    (467, 'yukkuri scenario tool_operator select 2'),
    (468, f'yukkuri scenario shop_stock_click {sel("tool_creative")}'),
    (469, f'execute unless data entity {sel("tool_creative")} YukkuriOperations.PinWound '
          f'if items entity {_pin_player} hotbar.1 mcyukkuri:pin_tool run say MCY_BD07_CREATIVE_PIN_EXTRACTED'),
    (470, 'yukkuri scenario tool_operator leave'),
]

_staff_tool_cases = (('awl', 'care_awl', -8), ('pin', 'pin_tool', 6))
_wound_smoke = next(s for s in S if s.id == 'wound_sources').smoke
for case, tool, x in _staff_tool_cases:
    fixture = 'tool_' + case
    operator = 'staff_' + case
    _wound_smoke['commands'].extend([
        (18, yk('reimu', x, 5, fixture, extra=_tool_stand, uuid=uid('wound_sources', fixture))),
        (18, f'summon mcyukkuri:caretaker_brother ~{x + .5} ~ ~6.5 '
             f'{{Tags:["scn","{tag(operator)}"],{_tool_stand},PersistenceRequired:1b,HumanInitialized:1b,HumanJob:2,'
             f'HandItems:[{{id:"mcyukkuri:{tool}",count:1,components:{{"minecraft:custom_data":{{A1:"{case}"}}}}}},{{}}]}}'),
        (55, f'yukkuri scenario staff_tool {sel(operator)} use {sel(fixture)}'),
    ])
    if case != 'awl':
        condition = f'Item:{{id:"mcyukkuri:{tool}",components:{{"minecraft:custom_data":{{A1:"{case}"}}}}}}'
        item_selector = f'@e[type=minecraft:item,nbt={{{condition}}},distance=..5,limit=1]'
        hand = f'HandItems:[{{id:"mcyukkuri:{tool}",count:1,components:{{"minecraft:custom_data":{{A1:"{case}"}}}}}}]'
        pickup = 220
        _wound_smoke['commands'].extend([
            (56, f'execute if entity {sel(operator)} unless data entity {sel(operator)} HandItems[0].id '
                 f'run say MCYA1_HAND_EMPTY_{case}'),
            (pickup, f'execute at {sel(fixture)} run yukkuri scenario staff_tool {sel(operator)} pickup {item_selector}'),
            (pickup + 1, f'execute at {sel(fixture)} if entity @e[tag={tag(operator)},nbt={{{hand}}}] '
                         f'unless entity {item_selector} run say MCYA1_BACK_IN_HAND_{case}'),
        ])
        _wound_smoke['expect'].extend([f'MCYA1_HAND_EMPTY_{case}', f'MCYA1_BACK_IN_HAND_{case}'])
_wound_smoke['commands'].extend([
    (56, f'yukkuri scenario staff_tool {sel("staff_awl")} use {sel("tool_awl")}'),
    (100, yk('reimu', 0, 5, 'tool_frame', extra=_tool_stand, uuid=uid('wound_sources', 'tool_frame'),
             boldness=80, self_control=25)),
    (114, 'execute positioned ~1.5 ~ ~4.5 run yukkuri scenario tool_operator enter'),
    *[(115, f'execute as @p[distance=..8] run {command}') for command in own('tool_frame')],
    (116, 'item replace entity @p[distance=..8] hotbar.0 with mcyukkuri:restraint_frame'),
    (117, 'yukkuri scenario shop_block_click ~ ~-1 ~4'),
    (118, 'item replace entity @p[distance=..8] hotbar.1 with mcyukkuri:yukkuri_carrier'),
    (119, 'yukkuri scenario tool_operator select 1'),
    (120, f'yukkuri scenario shop_stock_click {sel("tool_frame")}'),
    (144, 'yukkuri scenario shop_block_click ~ ~ ~4'),
    (145, 'item replace entity @p[distance=..8] hotbar.2 with mcyukkuri:pin_tool[minecraft:custom_data={BD07:1b}]'),
    (145, 'yukkuri scenario tool_operator select 2'),
    (146, f'yukkuri scenario shop_stock_click {sel("tool_frame")}'),
    (147, f'yukkuri scenario care_click {sel("tool_frame")} empty_hand'),
    (150, f'yukkuri scenario shop_stock_click {sel("tool_frame")}'),
    (151, f'execute if data entity {sel("tool_frame")} YukkuriOperations.Table '
          f'unless data entity {sel("tool_frame")} YukkuriOperations.PendingReturn '
          'if entity @e[type=item,nbt={Item:{id:"mcyukkuri:pin_tool",count:1,components:{"minecraft:custom_data":{BD07:1b}}}}] '
          'run say MCY_BD07_PIN_OUT_TABLE_HELD'),
    (168, f'execute at {sel("tool_frame")} positioned ~1 ~ ~ run yukkuri scenario tool_click {sel("tool_frame")} hook_hang'),
    (169, 'yukkuri scenario tool_operator select 3'),
    (170, f'yukkuri scenario shop_stock_click {sel("tool_frame")}'),
    (171, 'yukkuri scenario tool_operator leave'),
    (185, f'yukkuri scenario care_click {sel("tool_pin")} orange_juice'),
])
_wound_smoke['commands'].sort(key=lambda command: command[0])
_wound_smoke['expect'].extend([
    'MCY_BD07_PIN_OUT_TABLE_HELD',
    'MCY_BD07_PIN_STILL_ATTACHED',
    'MCY_BD07_SURVIVAL_PIN_RECOVERED',
    'MCY_BD07_CREATIVE_PIN_RETAINED',
    'MCY_BD07_CREATIVE_PIN_EXTRACTED',
    r'\[interaction\][^\n]* interaction refused; [^\n]* action=capture reason=pinned',
    rf'\[interaction\][^\n]* uuid={_uuid_text("wound_sources", "tool_frame")} [^\n]* interaction refused; [^\n]* action=release reason=owner',
    rf'\[interaction\][^\n]* uuid={_uuid_text("wound_sources", "tool_frame")} [^\n]* pose=PIN elapsed=\d+ remaining=0 reason=released',
    rf'\[body\][^\n]* uuid={_uuid_text("wound_sources", "tool_awl")} [^\n]* accepted damage cause=mob [^\n]* loss=2\.0',
    rf'\[interaction\][^\n]* uuid={_uuid_text("wound_sources", "tool_awl")} [^\n]* tool used; [^\n]* action=PIERCE item=mcyukkuri:care_awl hand_count=1 -> 1 damage=0 -> 1',
    r'\[body\][^\n]* accepted damage cause=player [^\n]* loss=2\.0',
    r'\[interaction\][^\n]* tool refused; [^\n]* action=pierce reason=busy',
    rf'\[interaction\][^\n]* uuid={_uuid_text("wound_sources", "tool_frame")} [^\n]* tool used; [^\n]* action=HOOK_HANG [^\n]* pose=FIXED',
    rf'\[interaction\][^\n]* uuid={_uuid_text("wound_sources", "tool_frame")} [^\n]* table unstrapped; [^\n]* reason=released',
    rf'\[body\][^\n]* uuid={_uuid_text("wound_sources", "tool_pin")} [^\n]* orange juice cleared 1 wound\(s\)',
    rf'\[interaction\][^\n]* uuid={_uuid_text("wound_sources", "tool_pin")} [^\n]* operation ended; [^\n]* pose=PIN [^\n]* reason=wound_closed',
])

def _wound_tool_binding(window):
    wounds = set(re.findall(r'\[body\][^\n]* uuid=([\w-]+) [^\n]* wound placed tear/puncture:[^\n]*; wound=([\w-]+)', window))
    attachments = re.findall(r'\[interaction\][^\n]* uuid=([\w-]+) [^\n]* binding wound; pose=(PIN|HOOK) wound=([\w-]+)', window)
    if (sorted(pose for _, pose, _ in attachments) != ['HOOK', 'HOOK', 'PIN', 'PIN', 'PIN', 'PIN']
            or len({wound for _, _, wound in attachments}) != 6
            or any((actor, wound) not in wounds for actor, _, wound in attachments)):
        return 'each of the four pins and two hooks must bind to its own committed puncture'
    pulls = re.findall(r'\[interaction\][^\n]* uuid=(\S+) [^\n]* operation ended; actor=2d4c7192-2a6d-4da4-9f94-c82721b9de87 '
                       r'pose=PIN elapsed=(\d+) remaining=0 reason=released', window)
    creative = _uuid_text('wound_sources', 'tool_creative')
    table = _uuid_text('wound_sources', 'tool_frame')
    if (len(pulls) != 3 or pulls[0][0] != table or pulls[2][0] != creative
            or pulls[1][0] in (table, creative) or int(pulls[1][1]) <= 400):
        return f'expected table, long-held floor and Creative extractions in order: {pulls}'
    for animal, _ in pulls:
        drops = re.findall(rf'\[interaction\][^\n]* uuid={animal} [^\n]* binding returned to world; [^\n]* item=mcyukkuri:pin_tool count=1 contact=', window)
        if len(drops) != int(animal != creative):
            return f'incorrect drop count for {animal}: {len(drops)}'
    for case, tool, _ in _staff_tool_cases[1:]:
        target = _uuid_text('wound_sources', 'tool_' + case)
        drops = re.findall(rf'\[interaction\][^\n]* uuid={target} [^\n]* binding returned to world; drop=([\w-]+) item=mcyukkuri:{tool} count=1', window)
        picked = re.findall(rf'\[interaction\][^\n]* tool picked up; drop=([\w-]+) item=mcyukkuri:{tool} hand_count=0 -> 1', window)
        if len(drops) != 1 or drops != picked:
            return f'the staff {case} must return one real drop and pick up that same drop'

next(s for s in S if s.id == 'wound_sources').smoke['verify'] = _wound_tool_binding
next(s for s in S if s.id == 'wound_sources').smoke['readout'] = [
    f'data get entity {sel("thorn")} YukkuriBody.HairLoss',
]

_fear_holder = '2d4c7192-2a6d-4da4-9f94-c82721b9de87'
_punishment_victim = r'\[punishment\][^\n]* uuid={uuid} [^\n]* victim actor={actor} incident=hurt response='
_punishment_witness = r'\[punishment\][^\n]* uuid={uuid} [^\n]* witness victim={victim} actor={actor} incident=hurt response={response} glass=true'
_smoke(_punishment_scene, 190, [
    _punishment_victim.format(uuid=_uuid_text(_punishment_scene, name), actor=_fear_holder) + response
    for name, response in (('a3_defy', 'DEFIANT'), ('a3_freeze', 'FREEZE'), ('a3_plead', 'PLEAD'))
], forbid=[rf'\[punishment\][^\n]* uuid={_uuid_text(_punishment_scene, "a3_screened")} '])
_punishment_smoke = next(s for s in S if s.id == _punishment_scene).smoke
_punishment_smoke['commands'] = [
    (44, 'execute positioned ~0.5 ~2.5 ~-0.5 run yukkuri scenario tool_operator enter'),
    (45, f'data modify entity {sel("a3_plead")} {OWNED}.Owner set from entity @p[name=MuseumOperator,distance=..16] UUID'),
    (46, 'execute as @e[type=minecraft:player,name=MuseumOperator,distance=..16,limit=1] at @s run function mcylab:extra/punishment_hit'),
    (47, 'execute as @e[type=minecraft:player,name=MuseumOperator,distance=..16,limit=1] at @s run function mcylab:extra/punishment_hit'),
    (90, 'execute as @e[type=minecraft:player,name=MuseumOperator,distance=..16,limit=1] at @s run function mcylab:extra/punishment_hit'),
    (145, 'yukkuri scenario tool_operator leave'),
]

def _verify_punishment(window):
    pet = _uuid_text(_punishment_scene, 'a3_plead')
    learning = re.findall(r'\[rules\][^\n]* uuid=(\S+) [^\n]* obedience reason=hurt owner=(\S+) shift=(-?\d+) -> (-?\d+)'
                          r' value=\d+ -> \d+ closeness=(-?\d+) fear=\d+', window)
    if learning != [(pet, _fear_holder, '0', '4', '0'), (pet, _fear_holder, '4', '8', '0')]:
        return f'Only the owned pet learns from the two accepted blows and keeps its closeness: {learning}'
    for victim, first in (('a3_freeze', 'FREEZE'), ('a3_plead', 'PLEAD'), ('a3_defy', 'DEFIANT')):
        hits = re.findall(_punishment_victim.format(uuid=_uuid_text(_punishment_scene, victim), actor=_fear_holder)
                          + r'(\w+) fear=(\d+) -> (\d+)', window)
        if len(hits) != 2 or int(hits[0][2]) == 0 or int(hits[1][1]) != int(hits[0][2]) or int(hits[1][2]) <= int(hits[1][1]):
            return f'{victim} must remember exactly two accepted blows from the same player: {hits}'
        if hits[0][0] != first:
            return f'{victim} must begin with {first}: {hits}'
        if victim == 'a3_defy' and hits[1][0] == 'DEFIANT':
            return f'{victim} must start defiant and become cautious: {hits}'
    for observer, victim, choice in (('a3_shrink', 'a3_plead', 'SHRINK'), ('a3_still', 'a3_freeze', 'FREEZE'),
                                     ('a3_kin', 'a3_defy', '(?:TURN_TO_KIN|PROTECT)')):
        pattern = _punishment_witness.format(uuid=_uuid_text(_punishment_scene, observer),
                                             victim=_uuid_text(_punishment_scene, victim), actor=_fear_holder, response=choice)
        if len(re.findall(pattern, window)) != 2:
            return f'{observer} must show {choice} through glass on both blows'
    parent, child = (_uuid_text(_punishment_scene, name) for name in ('a3_kin', 'a3_defy'))
    draw = rf'\[social\][^\n]* uuid={parent} t=(\d+)[^\n]* protection puff victim={child} attacker={_fear_holder} [^\n]* accepted=(true|false)'
    shown = rf'\[punishment\][^\n]* uuid={parent} t=(\d+)[^\n]* witness victim={child} actor={_fear_holder} incident=hurt response=PROTECT\b'
    draws = re.findall(draw, window)
    if len(draws) != 2 or [tick for tick, accepted in draws if accepted == 'true'] != re.findall(shown, window):
        return f'Expected one human-hit protective puff draw per blow and a puff for each accepted draw: {draws}'
    return _struck_speech(window)

_punishment_smoke['verify'] = _verify_punishment

_smoke('learned_holder', 500, [
    rf'\[threat\][^\n]* uuid={_uuid_text("learned_holder","witness")} [^\n]* fear learned holder={_fear_holder} [^\n]*incident=vibrate fear=0 -> 15',
    rf'\[threat\][^\n]* uuid={_uuid_text("learned_holder","witness")} [^\n]* fear learned holder={_fear_holder} [^\n]*incident=vibrate fear=15 -> 30',
    rf'\[threat\][^\n]* uuid={_uuid_text("learned_holder","witness")} [^\n]* learned retreat end result=away holder={_fear_holder}',
], forbid=[r'\[body\][^\n]* death settled',rf'\[threat\][^\n]* uuid={_uuid_text("learned_holder","screened")} [^\n]* fear learned'])
next(s for s in S if s.id == 'learned_holder').smoke['commands'] = [
    (44, f'execute at {sel("target")} positioned ~ ~ ~1 run yukkuri scenario tool_operator enter'),
    (46, f'execute at {sel("target")} positioned ~ ~ ~1 run yukkuri scenario tool_click {sel("target")} vibrate'),
    (47, 'yukkuri scenario tool_operator leave'),
    (248, f'execute at {sel("target")} run tp {sel("witness")} ~2 ~ ~1'),
    (249, f'execute at {sel("target")} positioned ~ ~ ~1 run yukkuri scenario tool_operator enter'),
    (251, f'execute at {sel("target")} positioned ~ ~ ~1 run yukkuri scenario tool_click {sel("target")} vibrate'),
    (255, f'execute at {sel("witness")} positioned ~ ~ ~1.2 run yukkuri scenario tool_operator move'),
    (450, 'yukkuri scenario tool_operator leave'),
]

_smoke('hook_kin_rescue', 380, [
    rf'\[interaction\][^\n]* uuid={_uuid_text(_hook_scene,"hook_child")} [^\n]* binding wound; pose=HOOK ',
    r'\[interaction\][^\n]* binding returned to world; [^\n]* item=mcyukkuri:hanging_hook count=1',
], forbid=[r'\[body\][^\n]* death settled'])

def _hook_rescue_attempt(window):
    child, parent = (_uuid_text(_hook_scene, role) for role in ('hook_child', 'hook_parent'))
    hook = re.search(rf'\[interaction\][^\n]* uuid={child} [^\n]* binding wound; pose=HOOK wound=([\w-]+)', window)
    if hook is None:
        return 'the child never hung from its own hook wound'
    identity = rf'\[social\][^\n]* uuid={parent} [^\n]* hook rescue '
    subject = rf'target={child} wound={hook[1]}'
    sequence = (identity + 'cue ' + subject + r'[^\n]*\n[\s\S]*?' +
                identity + 'attempt ' + subject + r'[^\n]*\n[\s\S]*?' +
                identity + 'end ' + subject + r' result=(?:released|no_reach)\b')
    if not re.search(sequence, window):
        return 'the parent did not approach and try this hook before release or giving up out of reach'

next(s for s in S if s.id == 'hook_kin_rescue').smoke['verify'] = _hook_rescue_attempt
next(s for s in S if s.id == 'hook_kin_rescue').smoke['commands'] = [
    (44, f'execute at {sel("hook_child")} positioned ~ ~ ~1.8 run yukkuri scenario tool_operator enter'),
    (46, f'execute at {sel("hook_child")} positioned ~ ~ ~1.8 run yukkuri scenario tool_click {sel("hook_child")} hook_hang'),
    (365, 'yukkuri scenario tool_operator leave'),
]

_smoke('learned_fire', 450, [
    r'\[body\][^\n]* burn remembered cause=hotFloor',
    r'\[body\][^\n]* hot floor escaped',
    rf'\[threat\][^\n]* uuid={_uuid_text("learned_fire","burnt")} [^\n]* learned retreat end result=away holder=null fire=',
], forbid=[r'\[body\][^\n]* death settled',rf'\[threat\][^\n]* uuid={_uuid_text("learned_fire","unburnt")} [^\n]* learned retreat'])
next(s for s in S if s.id == 'learned_fire').smoke['commands'] = [
    (125, f'execute at {sel("burnt")} run setblock ~2 ~ ~ minecraft:campfire'),
    (125, f'execute at {sel("unburnt")} run setblock ~2 ~ ~ minecraft:campfire'),
]
_scorn_executor = _uuid_text('hatless_scorn', 'drive_executor')
_scorn_meek = _uuid_text('hatless_scorn', 'drive_meek')


def _hatless_scorn_verify(window):
    spoken_by_target = {}
    distinct_events = set()
    snub_by_round5 = False
    for m in re.finditer(
            r'\[contempt\] \w+ #\d+ uuid=(\S+) [^\n]* scorn target=(\S+) [^\n]* rounds=(\d+) event=(\S+)(?:[^\n]* quiet=(true|false) bored=(true|false))?',
            window):
        _, target, rounds_s, event, quiet_s, bored_s = m.groups()
        rounds = int(rounds_s)
        if quiet_s == 'true' or bored_s == 'true' or event == 'none':
            if rounds <= 5:
                snub_by_round5 = True
            continue
        if event == 'sanction':
            continue
        distinct_events.add(event)
        prev = spoken_by_target.get(target)
        if prev == event:
            return f'same scorn event repeated twice in a row for target={target}: {event}'
        spoken_by_target[target] = event
    if len(distinct_events) < 3:
        return f'expected >=3 distinct scorn events, got {sorted(distinct_events)}'
    if not snub_by_round5:
        return 'expected at least one quiet snub or boredom beat by round 5'
    beats = list(re.finditer(
        r'\[choreo\] \w+ #\d+ uuid=(\S+) [^\n]* beat=(scorn_\w+|scorned_\w+) face=(\S+) line=(\S+) declared=(\S+)',
        window))
    if not beats:
        return 'no scorn/scorned choreo beats logged'
    for b in beats:
        actor, beat, face, _, declared = b.groups()
        if face != declared:
            return f'beat face mismatch actor={actor} beat={beat} shown={face} declared={declared}'
    return None


_smoke('hatless_scorn', 3600, [
    r'\[contempt\][^\n]* scorn target=',
    rf'\[contempt\] marisa #\d+ uuid={_uuid_text("hatless_scorn", "b1")} [^\n]* scorn target=',
    rf'uuid={_uuid_text("hatless_scorn", "bare")} [^\n]*course begun kind=rage strength=0\.35\b',
    rf'uuid={_scorn_executor} [^\n]*drive off attempted target={_scorn_meek}\b',
    rf'uuid={_scorn_executor} [^\n]*course begun kind=rage strength=0\.3 length=\d+ about={_scorn_meek}\b',
], forbid=[rf'uuid={_scorn_meek} [^\n]*course (?:begun|renewed) kind=rage\b'])
next(s for s in S if s.id == 'hatless_scorn').smoke['verify'] = _hatless_scorn_verify
# The bold exhibit actor can interrupt the case by counterattacking. A separate smoke pair can carry out the final act.
next(s for s in S if s.id == 'hatless_scorn').smoke['commands'] = [
    *((1, block(x, 0, z, 'minecraft:oak_fence'))
      for x in range(10, 15) for z in range(-2, 3) if x in (10, 14) or abs(z) == 2),
    (1, yk('marisa', 11, 0, 'drive_executor', uuid=uid('hatless_scorn', 'drive_executor'),
           yaw=-90, intelligence=35, friendliness=0, self_control=0, boldness=85, **pair_ready())),
    (1, yk('reimu', 13, 0, 'drive_meek', uuid=uid('hatless_scorn', 'drive_meek'),
           yaw=90, accessory=False, boldness=20, self_control=95, **pair_ready())),
]
_smoke('hatless_monster', 1200, [r'\[contempt\][^\n]* monster target=', r'\[contempt\][^\n]* scorn target='])
_fed = _uuid_text('feed_wild', 'street_hungry')
_empty = _uuid_text('feed_wild', 'street_nofood')
_free = _uuid_text('oniisan_prey', 'street_free')
_stopped = _uuid_text('street_stop', 'street_intervened')
_crop_eater = _uuid_text('street_crops', 'crop_eater')
_bench_stray = _uuid_text('street_furniture', 'bench_stray')
_water_stray = _uuid_text('street_furniture', 'water_stray')
_barrel_stray = _uuid_text('street_furniture', 'barrel_stray')
_awning_stray = _uuid_text('street_furniture', 'awning_stray')


def _street_health_reads(*names):
    return [f'data get entity {sel(name)} Health' for name in names]


_smoke('feed_wild', 400, [
    rf'\[npc-care\][^\n]* action=begin_meal target={_fed} food=1->0\b',
    rf'\[npc-care\][^\n]* action=street_pet target={_empty} food=0\b',
    rf'\[meal\][^\n]* uuid={_fed}[^\n]* swallowed item=[^\n]*oil_grain_regular',
])
_smoke('oniisan_prey', 220, [rf'\[npc-care\][^\n]* action=street_warn target={_free}\b',
                            rf'\[npc-care\][^\n]* uuid={_free}\b[^\n]* action=street_warning_seen [^\n]*face=teary\b'],
       [rf'\[npc-care\][^\n]* action=street_(chase|kick) target={_free}\b',
        rf'\[body\][^\n]* uuid={_free}\b[^\n]* accepted damage'],
       readout=_street_health_reads('street_free'))

# The wedged bin stands at +7/+6 from the town anchor, between two wall columns and the bench: nobody can shove it over, so nobody
# may put anything in it. The reachable bin at +6/+4 takes both swept pieces.
_tidy_ax, _tidy_az = BY_ID['town'].anchor
_tidy_wedged_bin = re.escape(f'{_tidy_ax + 7}, {FEET}, {_tidy_az + 6}')
_tidy_open_bin = re.escape(f'{_tidy_ax + 6}, {FEET}, {_tidy_az + 3}')
_smoke('street_tidy', 1800, [
    r'\[npc-care\][^\n]* action=litter_pick_up item=minecraft:paper\b',
    r'\[npc-care\][^\n]* action=litter_pick_up item=minecraft:stick\b',
    rf'\[npc-care\][^\n]* action=bin_refill bin={_tidy_open_bin}\b[^\n]* item=minecraft:paper litter=1\b',
    rf'\[npc-care\][^\n]* action=bin_refill bin={_tidy_open_bin}\b[^\n]* item=minecraft:stick litter=1\b',
    rf'\[npc-care\][^\n]* action=bin_skip bin={_tidy_wedged_bin}\b reason=untippable\b',
    r'MCYTIDY_UNMARKED_STILL_ON_GROUND',
], [r'\[npc-care\][^\n]* action=bin_refill bin=' + _tidy_wedged_bin + r'\b'],
       readout=['execute if entity ' + sel('tidy_player_paper') + ' run say MCYTIDY_UNMARKED_STILL_ON_GROUND'])
_smoke('street_stop', 280, [rf'\[npc-care\][^\n]* action=street_stop target={_stopped}\b', r'MCYSTALL_TEMPLATE'],
       [rf'\[npc-care\][^\n]* action=street_(warn|chase|kick) target={_stopped}\b'],
       readout=_street_health_reads('street_intervened'))
# The village stall template is checked here, away from the scene: the town field has the room and this smoke is short.
next(s for s in S if s.id == 'street_stop').smoke['commands'] = [
    (210, 'place template mcyukkuri:village/plains/stall ~-32 ~-1 ~-25'),
    (230, 'execute if block ~-28 ~ ~-16 mcyukkuri:produce_crate[facing=north] '
          'if data block ~-28 ~ ~-16 {Stall:1b,Items:[{Slot:2b,id:"minecraft:carrot",count:3}]} run say MCYSTALL_TEMPLATE'),
]
_smoke('street_crops', 500, [
    rf'\[forage\][^\n]* uuid={_crop_eater}\b[^\n]* crop bite [^\n]* age=7->6 count=1 holder=mouth',
    rf'\[npc-care\][^\n]* action=street_warn target={_crop_eater}\b[^\n]*site=BlockPos',
    rf'\[npc-care\][^\n]* action=street_shoo target={_crop_eater}\b moved=',
    rf'\[meal\][^\n]* uuid={_crop_eater}\b[^\n]* interrupted item=minecraft:carrot count=1 [^\n]*drop=[\w-]+',
    rf'\[threat\][^\n]* uuid={_crop_eater}\b[^\n]* response started source=[\w-]+ stage=RETREAT',
    rf'\[threat\][^\n]* uuid={_crop_eater}\b[^\n]* response ended stage=HIDE [^\n]*cooldown=100',
], [r'\[forage\][^\n]* crop bite [^\n]* age=0->',
    rf'\[body\][^\n]* uuid={_crop_eater}\b[^\n]* accepted damage'],
       readout=_street_health_reads('crop_eater'))
_stall_stray = _uuid_text('stall_theft', 'stall_stray')
_smoke('stall_theft', 700, [
    rf'\[forage\][^\n]* uuid={_stall_stray}\b[^\n]* stall pickup [^\n]*item=minecraft:carrot count=1 holder=mouth witness=[0-9a-f-]+',
    rf'\[npc-care\][^\n]* action=stall_scold target={_stall_stray}\b[^\n]*attitude=CARING[^\n]*drove_off=true',
    rf'\[threat\][^\n]* uuid={_stall_stray}\b[^\n]* response started source=[\w-]+',
    rf'\[meal\][^\n]* uuid={_stall_stray}\b[^\n]* swallowed item=minecraft:carrot count=1',
    r'MCYSTALL_EMPTY',
], forbid=[rf'\[body\][^\n]* uuid={_stall_stray}\b[^\n]* accepted damage'],
    readout=_street_health_reads('stall_stray'))
next(s for s in S if s.id == 'stall_theft').smoke['commands'] = [
    (320, f'execute if block {_stall_box} mcyukkuri:produce_crate unless data block {_stall_box} Items[0] run say MCYSTALL_EMPTY'),
]

_counter_buyer = '@a[name=MuseumOperator,distance=..8,limit=1]'
_counter_keeper_id = r'[0-9a-f-]{36}'
_counter_staffed = rf'\[npc-care\][^\n]* uuid=({_counter_keeper_id})\b[^\n]* action=counter_staffed'
_counter_coin, _counter_candy, _counter_paper, _counter_diamond = ('minecraft:emerald', 'mcyukkuri:sweet_candy', 'minecraft:paper',
                                                                   'minecraft:diamond')


def _counter_check(tick, label, crates, coins, candy, paper, bread, diamond=1):
    """Ledger at one tick: what the operator carries, and every crate's exact rows [(slot, item, count)] (conservation: the emeralds,
    candy, paper and bread of the player and the three crates always add up to what the scene started with)."""
    counts = [(_counter_coin, coins), (_counter_candy, candy), (_counter_paper, paper), (_counter_bread, bread),
              (_counter_diamond, diamond)]
    checks = []
    for crate, rows in crates:
        checks += [f'if data block {crate} {{Items:[{{Slot:{slot}b,id:"{item_id}",count:{count}}}]}}' for slot, item_id, count in rows]
        checks.append(f'unless data block {crate} Items[{len(rows)}]')
    return [(tick, f'execute store result score #counter_{i} museum run clear {_counter_buyer} {item_id} 0')
            for i, (item_id, _) in enumerate(counts)] + [
        (tick, 'execute ' + ' '.join([f'if score #counter_{i} museum matches {count}' for i, (_, count) in enumerate(counts)]
                                    + checks) + f' run say MCYCOUNTER_{label}')]


_counter_sale = rf'\[shop\][^\n]* uuid={_counter_keeper_id}\b[^\n]* action=counter_sale'
_counter_buyback = rf'\[shop\][^\n]* uuid={_counter_keeper_id}\b[^\n]* action=counter_buyback'
_smoke('counter_buy', 760, [
    _counter_staffed, _counter_sale + rf'[^\n]* goods={_counter_candy} count=1 emeralds=1 till=4->5',
    _counter_sale + rf'[^\n]* goods={_counter_bread} count=1 emeralds=1',
    _counter_buyback + rf'[^\n]* goods={_counter_paper} count=4 emeralds=1 till=',
    _counter_buyback + rf'[^\n]* goods={_counter_bread} count=2 emeralds=1 till=',
    r'\[shop\][^\n]* action=counter_refused [^\n]*reason=unstaffed',
    r'\[shop\][^\n]* action=counter_refused [^\n]*reason=wrong_item',
    *[f'MCYCOUNTER_{label}\\b' for label in ('UNSTAFFED', 'INSPECT', 'CANDY', 'BREAD', 'PAPER', 'WRONG', 'BREAD_BACK')],
], readout=[command for _, command in _counter_rows])
_counter_click = 'yukkuri scenario shop_block_click'
_b = _counter_bread
_start_bread, _start_apple = [(0, _b, 3), (1, _b, 2)], [(0, 'minecraft:apple', 3), (1, 'minecraft:apple', 2)]
_start_candy = [(0, _counter_coin, 4), (1, _counter_candy, 3), (2, _counter_candy, 2)]
_candy_after_buy = [(0, _counter_coin, 4), (1, _counter_candy, 3), (2, _counter_candy, 1), (3, _counter_coin, 1)]
_all_start = [(_counter_bread_crate, _start_bread), (_counter_candy_crate, _start_candy), (_counter_apple_crate, _start_apple)]
next(s for s in S if s.id == 'counter_buy').smoke['commands'] = [
    (1, 'execute positioned ~-0.5 ~ ~-2.5 run yukkuri scenario tool_operator enter'),
    (2, f'gamemode survival {_counter_buyer}'),
    (2, f'give {_counter_buyer} {_counter_coin} 7'), (2, f'give {_counter_buyer} {_counter_paper} 4'),
    (2, f'give {_counter_buyer} {_counter_diamond} 1'), (2, f'give {_counter_buyer} {_counter_bread} 2'),
    (3, f'{_counter_click} {_counter_candy_crate} north'),
    *_counter_check(4, 'UNSTAFFED', _all_start, 7, 0, 4, 2),
    # The keeper walks out through the shop door and round to the hatch (about 22 blocks): inspect with an empty hand first.
    (450, 'yukkuri scenario tool_operator select 8'),
    (451, f'{_counter_click} {_counter_candy_crate} north'),
    *_counter_check(452, 'INSPECT', _all_start, 7, 0, 4, 2),
    # Buy one candy: the emerald goes onto the crate's first row with room (a fourth row: the first emerald row is full), the candy leaves.
    (460, 'yukkuri scenario tool_operator select 0'),
    (461, f'{_counter_click} {_counter_candy_crate} north'),
    *_counter_check(462, 'CANDY', [(_counter_bread_crate, _start_bread), (_counter_candy_crate, _candy_after_buy),
                                   (_counter_apple_crate, _start_apple)], 6, 1, 4, 2),
    # Buy one bread from the first crate: its emerald lands in the crate's free third row.
    (470, f'{_counter_click} {_counter_bread_crate} north'),
    *_counter_check(471, 'BREAD', [(_counter_bread_crate, [(0, _b, 3), (1, _b, 1), (2, _counter_coin, 1)]),
                                   (_counter_candy_crate, _candy_after_buy), (_counter_apple_crate, _start_apple)], 5, 1, 4, 3),
    # Sell four paper at the apple crate: the batch lands there, the emerald comes out of the first crate holding one (the bread crate).
    (480, 'yukkuri scenario tool_operator select 1'),
    (481, f'{_counter_click} {_counter_apple_crate} north'),
    *_counter_check(482, 'PAPER', [(_counter_bread_crate, [(0, _b, 3), (1, _b, 1)]), (_counter_candy_crate, _candy_after_buy),
                                   (_counter_apple_crate, _start_apple + [(2, _counter_paper, 4)])], 6, 1, 0, 3),
    # A diamond is not counter goods: refused, nothing moves.
    (490, 'yukkuri scenario tool_operator select 2'),
    (491, f'{_counter_click} {_counter_apple_crate} north'),
    *_counter_check(492, 'WRONG', [(_counter_bread_crate, [(0, _b, 3), (1, _b, 1)]), (_counter_candy_crate, _candy_after_buy),
                                   (_counter_apple_crate, _start_apple + [(2, _counter_paper, 4)])], 6, 1, 0, 3),
    # Sell the two given bread at the bread crate: the loose bread rows fill up, the emerald comes out of the candy crate's first row.
    (500, 'yukkuri scenario tool_operator select 3'),
    (501, f'{_counter_click} {_counter_bread_crate} north'),
    *_counter_check(502, 'BREAD_BACK', [(_counter_bread_crate, [(0, _b, 4), (1, _b, 2)]),
                                        (_counter_candy_crate, [(0, _counter_coin, 3), (1, _counter_candy, 3), (2, _counter_candy, 1),
                                                                (3, _counter_coin, 1)]),
                                        (_counter_apple_crate, _start_apple + [(2, _counter_paper, 4)])], 7, 1, 0, 1),
    (540, 'yukkuri scenario tool_operator leave'),
]

_counter_beggar = _uuid_text('counter_shoo', 'counter_stray')
_smoke('counter_shoo', 750, [
    _counter_staffed + rf'[^\n]*\n[\s\S]*?\[social\][^\n]* uuid={_counter_beggar}\b[^\n]* human food request target=\1\b'
    + rf'[^\n]*\n[\s\S]*?\[npc-care\][^\n]* uuid=\1\b[^\n]* action=counter_shoo target={_counter_beggar}\b[^\n]* attitude=NEUTRAL'
    + rf'[^\n]*\n[\s\S]*?\[threat\][^\n]* uuid={_counter_beggar}\b[^\n]* response started source=\1\b stage=RETREAT',
    r'MCYCOUNTER_RETREATED',
], forbid=[rf'\[body\][^\n]* uuid={_counter_beggar}\b[^\n]* accepted damage',
           rf'\[forage\][^\n]* uuid={_counter_beggar}\b[^\n]* stall pickup'],
    readout=[command for _, command in _counter_rows] + _street_health_reads('counter_stray') + [
        f'data get entity {_counter_stray} YukkuriLife.AvoidedField', f'data get entity {_counter_stray} YukkuriLife.AvoidFieldUntil'])
next(s for s in S if s.id == 'counter_shoo').smoke['commands'] = [
    (705, f'execute positioned ~0.5 ~ ~-0.5 if entity @e[tag={tag("counter_stray")},distance=3..,limit=1] '
          f'if data entity {_counter_stray} {{YukkuriLife:{{AvoidedField:{_yard_pos(0, -1)}L}}}} '
          f'positioned ~-0.5 ~ ~0.5 if data block {_counter_bread_crate} {{Items:[{{Slot:0b,id:"minecraft:bread",count:3}},'
          f'{{Slot:1b,id:"minecraft:bread",count:2}}]}} unless data block {_counter_bread_crate} Items[2] run say MCYCOUNTER_RETREATED'),
]

_street_cover_arrival = (
    rf'\[(?:weather|habitat)\][^\n]* uuid=({_barrel_stray}|{_awning_stray})\b[^\n]* '
    r'(?:street rain cover reached|street rest) spot=(BARREL|AWNING) '
)
_smoke('street_furniture', 900, [
    rf'\[habitat\][^\n]* uuid={_bench_stray}\b[^\n]* street rest spot=BENCH [^\n]* stand=(?:seat|foot)\b',
    rf'\[sleep\][^\n]* uuid={_bench_stray}\b[^\n]* begin source=life',
    rf'\[needs\][^\n]* uuid={_water_stray}\b[^\n]* drink source=[^\n]* dryness=55\.0 -> 25\.0',
    _street_cover_arrival,
], readout=_street_health_reads('bench_stray', 'water_stray', 'barrel_stray', 'awning_stray'))


def _street_bench_rest(log):
    rest = re.search(rf'\[habitat\][^\n]* uuid={_bench_stray}\b[^\n]* street rest spot=BENCH [^\n]* stand=(seat|foot)\b', log)
    if rest is None:
        return 'the stray did not settle at the bench'
    release = 'exited' if rest[1] == 'seat' else 'foot released'
    if not re.search(rf'\[habitat\][^\n]* uuid={_bench_stray}\b[^\n]* street bench {release}\b', log[rest.end():]):
        return f'the stray did not release the bench after resting at its {rest[1]}'
    return None


def _street_rain_covers(log):
    """Both stands are reached by different eastern strays, via rain flight or a settled shelter."""
    reached = re.findall(_street_cover_arrival, log)
    spots = {spot for _, spot in reached}
    if spots != {'BARREL', 'AWNING'}:
        return f'the eastern strays reached street covers {sorted(spots)}, not both BARREL and AWNING'
    if not any(a != b and first != second for a, first in reached for b, second in reached):
        return 'both street covers were reached by the same stray'
    return None


def _street_furniture_result(log):
    return _street_bench_rest(log) or _street_rain_covers(log)


def _crop_warning_retreat(log):
    warning = re.search(rf'\[npc-care\][^\n]* uuid=([\w-]+) [^\n]* action=street_warn target={_crop_eater}\b[^\n]* site=BlockPos', log)
    if warning is None:
        return 'the crop eater received no resident warning'
    dropped = re.search(rf'\[meal\][^\n]* uuid={_crop_eater}\b[^\n]* interrupted item=minecraft:carrot count=1 phaseTick=([1-9])\b',
                        log[warning.end():])
    if dropped is None:
        return 'the timid crop eater did not drop its carrot within the first few ticks of chewing'
    if not re.search(rf'\[threat\][^\n]* uuid={_crop_eater}\b[^\n]* response started source={warning[1]}\b stage=RETREAT',
                     log[warning.end() + dropped.end():]):
        return 'the crop eater did not retreat from the resident after dropping the carrot'
    if not re.search(rf'\[npc-care\][^\n]* uuid={warning[1]}\b[^\n]* action=street_shoo target={_crop_eater}\b moved=',
                     log[warning.end():]):
        return 'the resident who gave the warning did not step toward the crop eater'
    return None


next(s for s in S if s.id == 'street_crops').smoke['verify'] = _crop_warning_retreat
next(s for s in S if s.id == 'street_furniture').smoke['verify'] = _street_furniture_result
_smoke('oni_street_scrap', 1300, [
    r'\[shop\][^\n]* action=street_scrap result=lifted [^\n]* item=minecraft:paper count=1',
    r'\[shop\][^\n]* action=street_scrap result=stored [^\n]* item=minecraft:paper count=1',
], readout=['data get block ~17 ~2 ~-6 Items', 'data get block ~5 ~ ~18 Items'])
_smoke('shop_build', 1600, [r'\[npc-care\][^\n]* action=refill_bowl ', r'\[npc-care\][^\n]* action=clean_toilet ',
                            r'\[shop\][^\n]* action=dispose_waste result=stored ', r'\[shop\][^\n]* action=register shop=',
                            r'\[interaction\][^\n]* action=pin reason=shop', r'MCYSHOP_PET_AGE_HELD'],
       forbid=[r'\[shop\][^\n]* action=job result=failed ', r'\[shop\][^\n]* action=job result=put_down ',
               r'\[interaction\][^\n]* operation started; [^\n]* pose=PIN '], readout=[f'yukkuri status {STOCK} raw'])
next(s for s in S if s.id == 'shop_build').smoke['commands'] = [
    # The operator stands directly over the stock animal, in the same pen cell: a small body can stand hard against the pen glass, so a
    # sideways offset would put the operator inside that glass and the tool refusal would read as a sight failure instead of the shop's own.
    (100, f'execute at {BOOTH.format("alice", 3)} positioned ~ ~1 ~ run yukkuri scenario tool_click {BOOTH.format("alice", 3)} pin'),
    (1500, 'execute if entity @e[type=mcyukkuri:alice,distance=..80,nbt={YukkuriFamily:{Age:148000L},NeoForgeData:{HumanCommerce:{Use:"stock"}}}] run say MCYSHOP_PET_AGE_HELD'),
    (850, 'execute positioned ~14.5 ~2 ~6.5 run yukkuri scenario tool_operator enter'),
    (851, 'yukkuri scenario shop_door_click ~15 ~3 ~5 south'),
    (852, 'execute if block ~14 ~3 ~5 minecraft:iron_door[open=true] if block ~15 ~2 ~5 minecraft:iron_door[open=true] run say MCYSHOP_VISITOR_COUNTER_OPEN'),
    (856, 'execute positioned ~15.5 ~2 ~4.5 run yukkuri scenario tool_operator move'),
    (857, 'yukkuri scenario shop_door_click ~15 ~3 ~5 north'),
    (858, 'execute if block ~14 ~2 ~5 minecraft:iron_door[open=false] if block ~15 ~3 ~5 minecraft:iron_door[open=false] run say MCYSHOP_VISITOR_COUNTER_CLOSED'),
    (1160, 'yukkuri scenario shop_door_click ~14 ~2 ~5 north'),
    (1161, 'execute if block ~14 ~2 ~5 minecraft:iron_door[open=true] run say MCYSHOP_VISITOR_EGRESS'),
    (1162, 'execute positioned ~14.5 ~2 ~6.5 run yukkuri scenario tool_operator move'),
    (1221, 'execute positioned ~13.5 ~2 ~8.5 run yukkuri scenario tool_operator move'),
    (1222, 'yukkuri scenario shop_door_click ~13 ~3 ~9 north'),
    (1223, 'execute if block ~13 ~3 ~9 minecraft:iron_door[open=true] if block ~13 ~2 ~9 minecraft:iron_door[open=true] run say MCYSHOP_VISITOR_PEN_OPEN'),
    (1229, 'yukkuri scenario tool_operator leave'),
]
next(s for s in S if s.id == 'shop_build').smoke['expect'].extend([
    r'MCYSHOP_VISITOR_COUNTER_OPEN', r'MCYSHOP_VISITOR_COUNTER_CLOSED',
    r'MCYSHOP_VISITOR_EGRESS', r'MCYSHOP_VISITOR_PEN_OPEN',
])
# Headless customers stand on the pavement outside the pens (NoAI keeps them there).
def _window_customer(dx, name):
    return (f'summon mcyukkuri:caretaker_sister ~{dx} ~2 ~15.5 {{Tags:["scn","{tag(name)}"],PersistenceRequired:1b,NoAI:1b,Rotation:[180f,0f],'
            'HumanJob:0,HumanAttitude:1,HumanInitialized:1b}')

_WINDOW_MODES = {f'window_{mode.lower()}': mode for mode in ('APPEAL', 'SHOW_OFF', 'POLITE', 'PROUD', 'SHY', 'SULK')}


def _window_choice(values):
    def ramp(value, low, high):
        return min(1, max(0, (value - low) / (high - low)))

    hope, esteem, wary = (float(values[key]) for key in ('hope', 'esteem', 'wariness'))
    confidence = ramp(esteem, 44, 56)
    warmth = ramp(int(values['friendliness']), 30, 80)
    familiar = ramp(int(values['closeness']), 0, 20)
    control = int(values['self_control'])
    hope_from_history = ramp(9 - int(values['days']), 0, 9) * (1 - .2 * int(values['rejections']))
    fear = ramp(int(values['fear']), 15, 60)
    if (not math.isclose(hope, hope_from_history, abs_tol=1e-9) or not fear <= wary <= 1
            or not 0 <= esteem <= 100 or not 0 <= int(values['grade']) <= 4):
        return None
    bases = {
        'appeal': 32 * hope + 30 * warmth + 24 * confidence - 35 * wary - 18 * familiar,
        'polite': (20 + .4 * control + 20 * warmth + 30 * familiar + 18 * wary) * (.7 + .3 * hope),
        'stand_tall': confidence * (70 + 24 * (1 - warmth)) * (1 - wary) * (.6 + .4 * hope),
        'shy': 100 * wary + 20 * (1 - confidence),
        'sulk': 82 * (1 - hope) + .2 * (100 - control) - 10,
    }
    scores = {key: float(values[key]) for key in bases}
    if any(not math.isfinite(scores[key]) or abs(scores[key] - base) > 3.000000001 for key, base in bases.items()):
        return None
    appeal = 'SHOW_OFF' if confidence >= .45 and warmth >= .65 else 'APPEAL'
    return dict(appeal=appeal, polite='POLITE', stand_tall='PROUD', shy='SHY', sulk='SULK')[max(scores, key=scores.get)]


def _shop_window_verify(window, trace):
    event = re.compile(r'\[shop\] \S+ #\d+ uuid=(\S+) t=(\d+) [^\n]*? window (sight|start|end) ([^\n]+)')
    seen, started = set(), set()
    sessions = {}
    for actor, tick, kind, body in event.findall(window):
        values = dict(re.findall(r'(\w+)=([^\s]+)', body))
        tick = int(tick)
        if kind == 'sight':
            seen.add((actor, tick, values['customer']))
        elif kind == 'start':
            started.add((actor, tick, values['customer']))
            if _window_choice(values) != values['mode']:
                return f'window choice inconsistent with logged values for {actor} at {tick}: {body}'
            sessions.setdefault(actor, []).append((tick, values, None))
        else:
            if actor not in sessions or sessions[actor][-1][2] is not None:
                return f'window end without an open choice for {actor} at {tick}'
            start, choice, _ = sessions[actor][-1]
            if (values['customer'], values['mode']) != (choice['customer'], choice['mode']):
                return f'window end differs from its choice for {actor} at {tick}: {body}'
            sessions[actor][-1] = (start, choice, (tick, values))
    if seen != started:
        return f'window sightings and choices differ: {sorted(seen ^ started)}'

    samples, traced, front_customers = {}, set(), set()
    customer_origins, departures = {}, {}
    for line in trace:
        row = json.loads(line.split('[trace] ', 1)[1])
        if row.get('win') != 'shop_window':
            continue
        actor, tick = row['u'], row['t']
        if row['k'] == 'st':
            traced.add(actor)
            state = row['st']
            behavior = state.get('behavior', '')
            if not behavior.startswith('window_'):
                continue
            clip = state.get('window_clip')
            if not clip or re.search(r'tremble|shiver|jitter', clip):
                return f'window clip missing or high-frequency for {actor} at {tick}: {clip}'
            mode = _WINDOW_MODES.get(behavior)
            if mode and (clip not in ('idle', 'waddle') or mode == 'SHY' and clip == 'waddle'):
                samples.setdefault(actor, []).append((tick, mode))
        elif row['k'] == 'geo' and any(tag.startswith('scn_customer_') for tag in row.get('tags', ())):
            if {'scn_customer_west', 'scn_customer_wary'}.intersection(row['tags']):
                front_customers.add(actor)
            x, _, z = row['box'][:3]
            first_x, first_z = customer_origins.setdefault(actor, (x, z))
            if math.hypot(x - first_x, z - first_z) > 9:
                departures.setdefault(actor, tick)

    modes = set()
    front_performed = False
    window_end = int(re.search(r'MCYSMOKE_END_TICK (\d+)', window)[1])
    for actor, choices in sessions.items():
        if actor not in traced:
            return f'window sighting without dense state samples for {actor}'
        for start, choice, end in choices:
            mode = choice['mode']
            shown = {tick: sample for tick, sample in samples.get(actor, ()) if tick >= start and (end is None or tick < end[0])}
            if set(shown.values()) - {mode}:
                return f'window presentation differs from {mode} for {actor} at {start}: {sorted(set(shown.values()))}'
            # Notice and approach can be interrupted before the pose. A still-open session can use both budgets
            # (ShopWindow NOTICE_TICKS 20 + APPROACH_TICKS 120), and a closed one that never entered the pose says so
            # with perform_ticks=0.
            if not shown:
                if end is None:
                    if window_end - start > 20 + 120:
                        return f'window sighting without a presented reaction for {actor} at {start}'
                elif int(end[1]['perform_ticks']) > 0 or end[1]['reason'] not in ('customer_left', 'interrupted', 'witness'):
                    return f'window sighting without a presented reaction for {actor} at {start}'
            front_performed |= bool(shown) and choice['customer'] in front_customers
            modes.update(shown.values())
            departure = departures.get(choice['customer'])
            actual_departure = departure is not None and start < departure
            if end is None:
                if actual_departure and mode in ('APPEAL', 'SHOW_OFF') and shown and min(shown) <= departure - 80 and window_end >= departure + 160:
                    return f'window session did not resolve an observed departure after a plea for {actor}'
                continue
            tick, result = end
            before, after = int(choice['rejections']), int(result['rejections'])
            long_plea = mode in ('APPEAL', 'SHOW_OFF') and int(result['perform_ticks']) >= 60 and result['reserved'] == 'false'
            reason = result['reason']
            if actual_departure and tick >= departure - 20 and long_plea and reason not in ('interrupted', 'witness'):
                if reason != 'customer_left' or after != min(4, before + 1):
                    return f'window departure after an eligible plea was not counted for {actor} at {tick}'
            elif reason != 'customer_left' or not long_plea:
                if after != before:
                    return f'window rejection without an eligible departure for {actor} at {tick}'
    if len(modes) < 2:
        return f'window presented fewer than two modes: {sorted(modes)}'
    if not front_performed:
        return 'street pen presented no reaction to a customer'
    return None


_smoke('shop_window', 1200, [],
       forbid=[r'\[shop\][^\n]* action=job result=failed '],
       readout=[f'yukkuri status {BOOTH.format(kind, grade)} raw' for kind, grade in (('chen', 0), ('alice', 4), ('remilia', 2), ('flandre', 0))])
# Use clear wool floor behind the glass. From farther back, the rising eye ray crosses only one glass cell.
_window_smoke = next(s for s in S if s.id == 'shop_window').smoke
_window_smoke.update(verify=_shop_window_verify, verify_trace=True)
_window_smoke['commands'] = [
    (70, f'tag {STOCK} add scn_stock'),
    (70, _window_customer(13.5, 'customer_west')),
    (70, f'yukkuri status relate {CHEN} {_window_west} 20'),
    (70, _window_customer(-0.5, 'customer_flandre')),
    (70, _window_customer(3.5, 'customer_remilia')),
    (270, f'tp {_window_west} ~13.5 ~ ~25.5'),
    (330, _window_customer(13.5, 'customer_wary')),
    (330, f'yukkuri status fear {CHEN} {_window_wary} 60')]
# The tool wall has eight operation tools and vanilla shears, alongside seven shelf goods.
_smoke('oni_shop', 1200, [r'\[shop\][^\n]* action=register shop=[^\n]*kind=ONI [^\n]*frames=16\b',
                          r'\[shop\][^\n]* action=oni_stock [^\n]*form=reimu_wasa [^\n]*prevention=72000\b',
                          r'\[shop\][^\n]* action=oni_stock [^\n]*form=reimu_sparse [^\n]*prevention=72000\b',
                          r'\[shop\][^\n]* window start mode=(INSOLENT|NUMB|SULK) [^\n]*shop_kind=ONI ', r'MCYSHOP_ONI_AGE_HELD',
                          r'\[interaction\][^\n]* action=pin reason=shop',
                          r'MCYSHOP_ONI_VISITOR_COUNTER_OPEN', r'MCYSHOP_ONI_VISITOR_COUNTER_CLOSED', r'MCYSHOP_ONI_VISITOR_EGRESS',
                          r'\[shop\][^\n]* action=membership result=test_set [^\n]*total=2 tier=MEMBER',
                          r'\[shop\][^\n]* action=membership result=test_set [^\n]*total=6 tier=PATRON'],
       forbid=[r'\[interaction\][^\n]* operation started; [^\n]* pose=PIN '],
       readout=[f'yukkuri status {STOCK} raw'])
next(s for s in S if s.id == 'oni_shop').smoke['commands'] = [
    (100, f'execute at {BOOTH.format("reimu", 0)} positioned ~ ~1 ~ run yukkuri scenario tool_click {BOOTH.format("reimu", 0)} pin'),
    # The south-facing template puts the two counter leaves at x16 and x17 relative to the scene origin.
    (200, 'execute positioned ~17.5 ~2 ~2.5 run yukkuri scenario tool_operator enter'),
    (201, 'yukkuri scenario shop_door_click ~17 ~3 ~1 south'),
    (202, 'execute if block ~16 ~2 ~1 minecraft:iron_door[open=true] if block ~17 ~2 ~1 minecraft:iron_door[open=true] run say MCYSHOP_ONI_VISITOR_COUNTER_OPEN'),
    (206, 'execute positioned ~16.5 ~2 ~0.5 run yukkuri scenario tool_operator move'),
    (207, 'yukkuri scenario shop_door_click ~16 ~3 ~1 north'),
    (208, 'execute if block ~16 ~2 ~1 minecraft:iron_door[open=false] if block ~17 ~2 ~1 minecraft:iron_door[open=false] run say MCYSHOP_ONI_VISITOR_COUNTER_CLOSED'),
    (210, 'yukkuri scenario shop_door_click ~16 ~2 ~1 north'),
    (211, 'execute if block ~16 ~2 ~1 minecraft:iron_door[open=true] if block ~17 ~2 ~1 minecraft:iron_door[open=true] run say MCYSHOP_ONI_VISITOR_EGRESS'),
    (212, 'execute as @p[distance=..40] at @e[tag=mcylab_shop_access,limit=1] run yukkuri human shop test_membership ~ ~ ~ member'),
    (213, 'execute as @p[distance=..40] at @e[tag=mcylab_shop_access,limit=1] run yukkuri human shop test_membership ~ ~ ~ patron'),
    (214, 'yukkuri scenario tool_operator leave'),
    (1100, 'execute if entity @e[type=mcyukkuri:marisa,distance=..80,nbt={YukkuriFamily:{Age:63000L},NeoForgeData:{HumanCommerce:{Use:"stock"}}}] run say MCYSHOP_ONI_AGE_HELD'),
]
_stress_brought = _uuid_text('oni_stress_room', 'brought_reimu')
_smoke('oni_stress_room', 440, [
    r'\[shop\][^\n]* action=rent_restraint result=refused via=menu staff=[^\n]*reason=emeralds deposit=4\b',
    r'\[shop\][^\n]* action=lend_restraint result=committed via=menu staff=[^\n]*deposit=4\b',
    r'\[shop\][^\n]* action=protect result=refused what=block',
    rf'\[carrier\][^\n]* action=capture result=committed target={_stress_brought} ',
    rf'\[carrier\][^\n]* action=release result=committed target={_stress_brought} ',
    rf'\[interaction\][^\n]*uuid={_stress_brought} [^\n]* table strapped; ',
    rf'\[punishment\][^\n]*uuid={_stress_brought} [^\n]* incident=fix_supine response=DEFIANT fear=0 -> [1-9]\d*\b',
    rf'\[interaction\][^\n]*uuid={_stress_brought} [^\n]* tool used; [^\n]* action=PIERCE [^\n]* pose=FIXED',
    rf'\[interaction\][^\n]*uuid={_stress_brought} [^\n]* table unstrapped; [^\n]* reason=table_removed\b',
    r'\[shop\][^\n]* action=rent_restraint result=refused via=menu staff=[^\n]*reason=return_frame deposit=4\b',
    r'\[shop\][^\n]* action=return_restraint result=committed via=menu staff=[^\n]*deposit=4\b',
    r'\[interaction\][^\n]* action=pierce reason=shop',
    r'MCYSP08_LOAN_BALANCED', r'MCYSP08_EMPTY_TABLE', r'MCYSP08_HELD_WITHOUT_OPERATOR', r'MCYSP08_RETURN_BALANCED',
    r'\[shop\][^\n]* action=rent_restraint result=expired [^\n]*deposit=4 till=4\b',
    r'\[shop\][^\n]* action=rent_restraint result=restocked ',
    r'MCYSP08_EXPIRED_EMPTY', r'MCYSP08_RESTOCKED',
], readout=['data get block ~14 ~3 ~1 Items', f'yukkuri status {sel("oni_bold")} raw',
            f'yukkuri status {sel("oni_timid")} raw', f'yukkuri status {sel("brought_reimu")} raw'])
_stress_operator = '@p[name=MuseumOperator,distance=..40]'
_stress_frame_drop = '@e[type=minecraft:item,distance=..8,limit=1,nbt={Item:{id:"mcyukkuri:restraint_frame"}}]'
_stress_staff = '@e[type=mcyukkuri:oniisan,nbt={HumanJob:1},distance=..40,limit=1,sort=nearest]'
_stress_menu = f'yukkuri scenario shop_rental_click {_stress_staff}'
_stress_desk = 'execute positioned ~10.5 ~2 ~2.5 run yukkuri scenario tool_operator'
next(s for s in S if s.id == 'oni_stress_room').smoke['commands'] = [
    (70, 'execute positioned ~14.5 ~2 ~2.5 run yukkuri scenario tool_operator enter'),
    (71, 'yukkuri scenario shop_block_click ~14 ~3 ~1'),
    (71, _stress_desk + ' move'),
    (71, _stress_menu),
    (72, f'item replace entity {_stress_operator} inventory.0 with minecraft:emerald 8'),
    (73, _stress_menu),
    (74, 'scoreboard objectives add sp08_balance dummy'),
    (75, f'execute store result score #emeralds sp08_balance run clear {_stress_operator} minecraft:emerald 0'),
    (75, f'execute if score #emeralds sp08_balance matches 4 if items entity {_stress_operator} weapon.mainhand '
         'mcyukkuri:restraint_frame if data block ~14 ~3 ~1 {Items:[{Slot:0b,id:"minecraft:emerald",count:4}]} '
         'run say MCYSP08_LOAN_BALANCED'),
    (78, yk('reimu', 23, 4, 'brought_reimu', dy=2, uuid=uid('oni_stress_room', 'brought_reimu'),
            boldness=80, self_control=25, friendliness=30)),
    *[(79, f'execute as {_stress_operator} run {command}') for command in own('brought_reimu')],
    (80, 'execute positioned ~22.5 ~2 ~3.5 run yukkuri scenario tool_operator move'),
    (80, 'yukkuri scenario shop_block_click ~22 ~1 ~2'),
    (80, f'execute if block ~22 ~2 ~2 mcyukkuri:restraint_frame[occupied=false] if entity {sel("brought_reimu")} '
         f'unless data entity {sel("brought_reimu")} YukkuriOperations.Table run say MCYSP08_EMPTY_TABLE'),
    (81, f'item replace entity {_stress_operator} hotbar.1 with mcyukkuri:yukkuri_carrier'),
    (82, 'yukkuri scenario tool_operator select 1'),
    (83, f'yukkuri scenario shop_stock_click {sel("brought_reimu")}'),
    (110, 'yukkuri scenario shop_block_click ~22 ~2 ~2'),
    (121, f'execute at {sel("brought_reimu")} positioned ~1 ~ ~ run yukkuri scenario tool_click {sel("brought_reimu")} pierce'),
    (128, _stress_desk + ' move'),
    (129, _stress_menu),
    # Exercise the tray's block-entity save/load round trip while the frame is out, without changing its Items.
    (130, 'data merge block ~14 ~3 ~1 {SP08Readback:1b}'),
    (152, f'execute if block ~22 ~2 ~2 mcyukkuri:restraint_frame[occupied=true] '
          f'if data entity {sel("brought_reimu")} YukkuriOperations.Table run say MCYSP08_HELD_WITHOUT_OPERATOR'),
    (153, 'execute positioned ~22.5 ~2 ~3.5 run yukkuri scenario tool_operator move'),
    (153, 'yukkuri scenario tool_operator break ~22 ~2 ~2'),
    (175, f'execute positioned ~22 ~2 ~3 at {_stress_frame_drop} run yukkuri scenario tool_operator move'),
    (176, f'execute positioned ~22 ~2 ~3 run yukkuri scenario tool_operator pick_up {_stress_frame_drop}'),
    (177, _stress_desk + ' move'),
    (178, 'time set midnight'),
    (178, _stress_menu),
    (179, 'time set noon'),
    (179, f'execute store result score #emeralds sp08_balance run clear {_stress_operator} minecraft:emerald 0'),
    (179, f'execute store result score #frames sp08_balance run clear {_stress_operator} mcyukkuri:restraint_frame 0'),
    (179, 'execute if score #emeralds sp08_balance matches 8 if score #frames sp08_balance matches 0 '
          'if data block ~14 ~3 ~1 {Items:[{Slot:0b,id:"mcyukkuri:restraint_frame",count:1}]} '
          f'positioned ~22 ~2 ~3 unless entity {_stress_frame_drop} run say MCYSP08_RETURN_BALANCED'),
    (180, 'scoreboard objectives remove sp08_balance'),
    (181, 'yukkuri scenario tool_operator leave'),
    (190, f'execute at {sel("oni_bold")} positioned ~ ~1 ~ run yukkuri scenario tool_click {sel("oni_bold")} pierce'),
    (198, _stress_desk + ' enter'),
    (199, f'item replace entity {_stress_operator} inventory.0 with minecraft:emerald 4'),
    (200, _stress_menu),
    # Set the persisted deadline in the past instead of holding the smoke server for a whole shop day.
    (201, 'data modify block ~14 ~3 ~1 NeoForgeData.RestraintDueDay set value 0'),
    (225, 'execute unless data block ~14 ~3 ~1 Items[{Slot:0b}] run say MCYSP08_EXPIRED_EMPTY'),
    (226, f'yukkuri human shop restock {_stress_staff}'),
    (227, f'execute if items entity {_stress_operator} weapon.mainhand mcyukkuri:restraint_frame '
          'if data block ~14 ~3 ~1 {Items:[{Slot:0b,id:"mcyukkuri:restraint_frame",count:1}]} run say MCYSP08_RESTOCKED'),
    (228, 'yukkuri scenario tool_operator leave'),
]
def _street_give_way_verify(window):
    """The awed body's whole step stays under 1.5 blocks with real clearance and no slide; the bold one never steps."""
    reimu, marisa = _uuid_text('street_give_way', 'reimu'), _uuid_text('street_give_way', 'marisa')
    done = 0
    for line in re.finditer(rf'uuid={reimu} [^\n]* action=give_way_done ([^\n]*)', window):
        done += 1
        fields = dict(re.findall(r'(\w+)=(-?[\d.]+)', line[1]))
        if float(fields['distance']) >= 1.5:
            return f'the awed body stepped {fields["distance"]} blocks, over 1.5'
        if float(fields['gap']) < .2:
            return f'the awed body left only {fields["gap"]} blocks of clearance'
        if float(fields['max_step']) > .3:
            return f'the awed body travelled {fields["max_step"]} blocks in one tick'
    if not done:
        return 'the awed body never finished its step'
    if re.search(rf'uuid={marisa} [^\n]* action=give_way(?: |_done)', window):
        return 'the bold body stepped aside for a person'
    if not re.search(rf'uuid={marisa} [^\n]* action=give_way_refused [^\n]* cause=awe\b', window):
        return 'the bold body was never asked, so its refusal was not shown'
    return None


_smoke('street_give_way', 300, [
    rf'\[move\][^\n]*uuid={_uuid_text("street_give_way", "reimu")} [^\n]* action=give_way_notice [^\n]* person=true [^\n]*deference=0\.8\d*',
    rf'\[choreo\][^\n]*uuid={_uuid_text("street_give_way", "reimu")} [^\n]* beat=give_way_notice face=surprise line=none declared=surprise',
    rf'\[move\][^\n]*uuid={_uuid_text("street_give_way", "reimu")} [^\n]* action=give_way [^\n]*walker_type=[^\n]*caretaker_brother',
    rf'\[move\][^\n]*uuid={_uuid_text("street_give_way", "reimu")} [^\n]* action=give_way_done [^\n]*distance=0\.[4-7]\d* gap=0\.[3-5]\d* max_step=0\.[0-2]\d*',
    rf'\[move\][^\n]*uuid={_uuid_text("street_give_way", "marisa")} [^\n]* action=give_way_refused [^\n]* cause=awe ',
], forbid=[r'\[move\][^\n]* action=give_way_done [^\n]* distance=[2-9]\d*(?:\.\d+)? '])
next(s for s in S if s.id == 'street_give_way').smoke['verify'] = _street_give_way_verify
_smoke('yard_owner_return', 1250, [
    r'\[pet\][^\n]* yard bond established owner=[^ ]+ closeness=24 bowl=1 toilet=1',
    r'\[pet\][^\n]* owner return approach owner=[^ ]+ clip=joy_jump distance=(?:[2-9]|1[0-2])\.',
    r'\[pet\][^\n]* owner return greeted owner=[^ ]+ clip=joy_jump',
    r'\[pet\][^\n]* owner return closing owner=',
    r'\[pet\][^\n]* owner return nuzzle owner=',
    r'\[pet\][^\n]* owner return nuzzle completed owner=',
], readout=[f'data get entity {sel("yard_pet")} YukkuriSkills',
            f'data get entity {sel("yard_keeper")} CareFood', 'data get block ~5 ~ ~2 Items'])
_smoke('yard_stray_scold', 1200, [
    r'\[home-claim\][^\n]* occupation chosen [^\n]*kind=bed',
    r'\[home-claim\][^\n]* declared site=[^\n]*spoken=true',
    r'\[npc-care\][^\n]* action=yard_scold_approach target=[^ ]+ distance=(?:[2-9]|1[0-2])\.',
    r'\[npc-care\][^\n]* action=yard_scold target=',
    r'\[home-claim\][^\n]* HOLD -> RETREAT',
    r'\[home-claim\][^\n]* ended[^\n]*driven_off',
], readout=['yukkuri homeclaim status', f'data get entity {sel("yard_stray")} Pos', 'data get block ~5 ~ ~2 Items'])


def _verify_outing_pace(window, max_leash=6.0):
    if not re.search(r'action=outing_pace mode=\w+ cause=\w+ req=[\d.]+ target=[\d.]+ ', window):
        return 'no outing_pace change log found'
    legs = re.findall(
        r'action=outing_leg_pace phase=(\w+) ticks=(\d+) dist=([\d.]+) speed=([\d.]+) '
        r'mean_req=([\d.]+) slowest=([\d.]+) max_gap=([\d.]+) slow_ticks=(\d+) wait_ticks=(\d+) cap=(\w+)', window)
    if not legs:
        return 'no outing_leg_pace summary logged'
    depart_legs = [row for row in legs if row[0] == 'depart' and int(row[1]) >= 20]
    if not depart_legs:
        return f'no depart leg pace summary with >=20 ticks (saw {legs})'
    depart_speed = float(depart_legs[0][3])
    mean_req = float(depart_legs[0][4])
    slowest_bps = float(depart_legs[0][5])
    # Key speed band to the logged predicted dry-ground speed from mean_req and slowest companion walk speed.
    ground_travel = 0.98 / (1.0 - 0.60 * 0.91)
    predicted = ((0.26 * mean_req) ** 2) * ground_travel * 20.0
    share = max(0.35, min(1.10, (slowest_bps / 20.0) / 0.10))
    min_floor = max(0.42, 0.55 * (share ** 0.5)) - 0.03
    if mean_req < min_floor:
        return f'owner depart mean_req {mean_req:.2f} below companion-scaled floor {min_floor:.2f} (slowest={slowest_bps:.2f} b/s)'
    if not (0.65 * predicted <= depart_speed <= 1.35 * predicted):
        return f'owner depart mean speed {depart_speed:.2f} b/s outside [0.65, 1.35]x predicted {predicted:.2f} b/s ({depart_legs[0]})'
    for row in legs:
        gap = float(row[6])
        if gap >= max_leash:
            return f'leash stretched to {gap:.2f}b (>= {max_leash:.2f}b drag limit) on {row[0]} leg ({row})'
    return None


def _outing_crossings(window, trace):
    pace_err = _verify_outing_pace(window)
    if pace_err:
        return pace_err
    departed = re.search(r' t=(\d+) [^\n]*action=outing_depart pets=2 ', window)
    if not departed:
        return 'the resident never left with both pets'
    turned = re.search(r' t=(\d+) [^\n]*action=outing_turn reason=(\w+)', window[departed.end():])
    if not turned or turned[2] != 'destination':
        return 'the resident did not reach the street destination before turning home'
    for _, name, *_ in _outing_pets:
        actor = _uuid_text('park_walk', name)
        reached = re.search(rf'uuid={actor} t=(\d+) [^\n]*outing reached home', window)
        if not reached or int(reached[1]) <= int(turned[1]):
            return f'{name} did not return to the yard'
        for line in trace:
            if actor not in line:
                continue
            row = json.loads(line.split('[trace] ', 1)[1])
            # North of the yard's street-side fence: on the sidewalk or the street.
            if (row['k'] == 'st' and 'p' in row['st'] and int(departed[1]) <= row['t'] <= int(turned[1])
                    and row['st']['p'][2] < _lot_z - 5):
                break
        else:
            return f'{name} never crossed the yard boundary on the outward walk'


_lot_bowl = f'~{_PARK_BOWL[0]} ~ ~{_PARK_BOWL[1]}'
_lot_crate = f'~{_PARK_CRATE[0]} ~ ~{_PARK_CRATE[1]}'
_outing_ids = '|'.join(_uuid_text('park_walk', p[1]) for p in _outing_pets)


def _outing_leads(scene, names, park):
    phases = [('depart', 'attach'), ('park', 'detach'), ('return', 'attach'), ('home', 'detach')] if park else [
        ('depart', 'attach'), ('home', 'detach')]
    return [rf'action=outing_lead phase={phase} result={result} pet={_uuid_text(scene, name)}\b'
            for name in names for phase, result in phases]


def _plaza_visit(window, trace):
    wearer_ids = [_uuid_text(_PLAZA, name) for _, name, *_ in _PLAZA_WEARERS]
    x, z = BY_ID['town'].anchor
    positions = {actor: [] for actor in wearer_ids}
    for line in trace:
        row = json.loads(line.split('[trace] ', 1)[1])
        if row['k'] == 'st' and row['u'] in positions and 'p' in row['st']:
            positions[row['u']].append(row['st']['p'])
    for actor, track in positions.items():
        # The paving is the four columns west of the benches, x+2..x+5 and z-3..z+1; stands lie one block inside its edge, so a walker that settles a step short is still on it.
        if not any(x + 1 <= px < x + 6 and z - 3 <= pz < z + 2 for px, _, pz in track):
            return f'{actor} never walked onto the plaza paving'
    # The keeper takes the visible company with a .75 roll, so the stop itself is only required after a chosen roll.
    keeper, pet = _uuid_text(_PLAZA, _PLAZA_KEEPER), _uuid_text(_PLAZA, _PLAZA_YARD_PET)
    if not re.search(rf'uuid={keeper} [^\n]*action=outing_plaza_choice company=true wearer=false chance=0\.75 chosen=(?:true|false)', window):
        return 'the keeper never weighed the plaza with a wearer in sight'
    if 'chosen=true' in window:
        for need in (rf'uuid={keeper} [^\n]*action=outing_plaza_arrive stand=', rf'uuid={keeper} [^\n]*action=outing_home\b',
                     rf'action=outing_lead phase=home result=detach pet={pet}\b', rf'uuid={pet} [^\n]*outing reached home'):
            if not re.search(need, window):
                return f'the chosen plaza visit did not finish: missing {need}'


_plaza_keeper_id = _uuid_text(_PLAZA, _PLAZA_KEEPER)
next(s for s in S if s.id == _PLAZA).smoke = dict(ticks=3600, verify=_plaza_visit, verify_trace=True, expect=[
    *[rf'\[badge\][^\n]*uuid={_uuid_text(_PLAZA, name)} [^\n]*plaza {phase} '
      for _, name, *_ in _PLAZA_WEARERS for phase in ('walk', 'arrived')],
    *_outing_leads(_PLAZA, [_PLAZA_YARD_PET], False)[:1],
], forbid=[rf'uuid={_plaza_keeper_id} [^\n]*action=outing_park'])



def _ring_stray_fates(window):
    """Each decision and arrival matches its own values: the shy marisa keeps far off, and the two bold low-self-control strays
    stand on the ring and stay long enough to see the badge."""
    x, z = BY_ID['town'].anchor
    holder = _uuid_text(_PLAZA, _RING_HOLDER)
    keeper = _uuid_text(_PLAZA, _RING_KEEPER)

    def outside(px, pz):
        dx = max(x + 2 - (px + .5), (px + .5) - (x + 6), 0)
        dz = max(z - 3 - (pz + .5), (pz + .5) - (z + 2), 0)
        return (dx * dx + dz * dz) ** .5

    arrived = 0
    ticks = [int(t) for t in re.findall(r'uuid=\S+ t=(\d+) ', window)]
    start, end = min(ticks), max(ticks)
    rest = re.search(rf'uuid={keeper} t=(\d+) [^\n]*action=outing_plaza_rest ', window)
    if rest and int(rest[1]) - start > 800:
        return f'keeper took {int(rest[1]) - start} ticks to reach the plaza bench (expected <= 800)'
    for name, *_ in _RING_STRAYS:
        actor = _uuid_text(_RING, name)
        # The decision logs the ring distance its values give: 1 + 5 * shyness * (1 - .4 * interest), plus a two-block band.
        for line in re.finditer(rf'uuid={actor} t=\d+ [^\n]*plaza ring decision [^\n]* gap=([\d.]+) ', window):
            if (name == 'ring_timid') != (float(line[1]) >= 3.9):
                return f'{name} chose a ring gap of {float(line[1]):.1f}; only the shy marisa keeps 4 blocks or more'
        for line in re.finditer(rf'uuid={actor} t=(\d+) [^\n]*plaza ring_arrived stand=(-?\d+), \d+, (-?\d+)', window):
            arrived += 1
            gap = outside(int(line[2]), int(line[3]))
            if name == 'ring_timid' and gap < 3.9:
                return f'{name} came to {gap:.1f} blocks of the paving; its shyness keeps it at 4 or more'
            if name != 'ring_timid' and gap > 3.8:
                return f'{name} stopped {gap:.1f} blocks from the paving; its boldness keeps it within 3.8'
            # The sight scan runs every 2 seconds and may fire on the approach into the ring or while standing at it.
            if name in ('ring_cocky', 'ring_voided') and end - int(line[1]) > 400 and not re.search(
                    rf'uuid={actor} t=(\d+) [^\n]*badge sight subject={holder} ', window):
                return f'{name} stood at the ring and never saw the badge'
    if not arrived and not re.search(r'\[badge\][^\n]*plaza ring_walk ', window):
        return 'no stray reached the ring'
    beats = list(re.finditer(r'\[badge\][^\n]*t=(\d+) [^\n]*beat=(plaza_\w+) face=(\S+) declared=(\S+) line=(\S+)', window))
    for b in beats:
        if b[3] != b[4]:
            return f'plaza beat {b[2]} showed face={b[3]} instead of declared={b[4]}'
    if not beats:
        return 'no plaza reaction beat fired'
    if int(beats[0][1]) - start > 400:
        return f'first plaza reaction beat came {int(beats[0][1]) - start} ticks after the scene started (expected <= 400)'
    # Gating lock-up check: one body may not stay blocked by a yukkuri or stopped for no_progress for over 200 ticks
    # (lines of one body closer than 120 ticks belong to the same stretch; the move log is limited to one line per 100 ticks).
    stretch = {}
    for line in re.finditer(r'\[move\] \w+ #\d+ uuid=(\S+) t=(\d+)[^\n]*(?:blocked by yukkuri |ground path stopped reason=no_progress )', window):
        stretch.setdefault(line[1], []).append(int(line[2]))
    for actor, times in stretch.items():
        first = last = times[0]
        for t in sorted(times)[1:]:
            if t - last > 120: first = t
            last = t
            if last - first > 200:
                return f'{actor} stayed blocked by a yukkuri or in no_progress for {last - first} ticks (expected <= 200)'


# The scene itself provides the real start via at_spawn plaza_rest; smoke only places a passive viewer at the vantage so
# BadgeSocial uses the normal player-audience budget instead of the headless world bucket.
next(s for s in S if s.id == _RING).smoke = dict(ticks=5400, verify=_ring_stray_fates, commands=[
    (1, f'execute positioned {BY_ID["town"].anchor[0] - 4}.5 {FEET} {BY_ID["town"].anchor[1] - 3}.5 run yukkuri scenario tool_operator enter'),
    (5380, 'yukkuri scenario tool_operator leave'),
], expect=[
    rf'uuid={_uuid_text(_PLAZA, _RING_KEEPER)} [^\n]*action=outing_plaza_rest ',
    rf'uuid={_uuid_text(_PLAZA, _RING_KEEPER)} [^\n]*action=outing_sit seat=',
    *[rf'\[badge\][^\n]*uuid={_uuid_text(_RING, name)} [^\n]*plaza ring decision' for name in ('ring_cocky', 'ring_voided')],
    rf'uuid={_uuid_text(_PLAZA, _RING_KEEPER)} [^\n]*action=pet_help pet={_uuid_text(_PLAZA, _RING_HOLDER)} [^\n]*where=plaza\b',
    rf'uuid={_uuid_text(_PLAZA, _RING_KEEPER)} [^\n]*action=pet_comfort pet={_uuid_text(_PLAZA, _RING_HOLDER)} ',
], forbid=[
    rf'\[badge\][^\n]*uuid={_uuid_text(_PLAZA, _RING_HOLDER)} [^\n]*plaza (?:walk|ring)',
    rf'\[badge\][^\n]*badge sight subject={_uuid_text(_RING, "ring_voided")} grade=[1-4]\b',
])


def _confiscate_fates(window):
    expected = {
        'conf_proud': ('proud', 'surprise', 'cheek_puff', 'pursed'),
        'conf_attached': ('attached', 'surprise', 'teary', 'ashamed'),
        'conf_fearful': ('fearful', 'surprise', 'afraid', 'horrified'),
        'conf_stray_bold': ('stray_cling', 'surprise', 'cheek_puff', 'angry'),
        'conf_stray_timid': ('stray_run', 'surprise', 'cry', 'afraid'),
    }
    for name, (style, f1, f2, f3) in expected.items():
        actor = _uuid_text(_CONF, name)
        for beat, want in (('confiscate_cause', f1), ('confiscate_react', f2), ('confiscate_end', f3)):
            m = re.search(rf'\[badge\][^\n]*uuid={actor} [^\n]*beat={beat} face=(\S+) declared=(\S+) line=(\S+) clip=(\S+) style={style}\b', window)
            if not m:
                return f'{name} missing {beat} for style={style}'
            if m[1] != m[2] or m[1] != want:
                return f'{name} {beat} showed face={m[1]} declared={m[2]} (expected {want})'


next(s for s in S if s.id == _CONF).smoke = dict(ticks=600, verify=_confiscate_fates, commands=[
    (10, 'yukkuri scenario tool_operator enter'),
    (40, f'yukkuri scenario care_click {sel("conf_proud")} confiscate_badge_own'),
    (120, f'yukkuri scenario care_click {sel("conf_attached")} confiscate_badge_own'),
    (200, f'yukkuri scenario care_click {sel("conf_fearful")} confiscate_badge_fearful'),
    (280, f'yukkuri scenario care_click {sel("conf_stray_bold")} confiscate_badge'),
    (360, f'yukkuri scenario care_click {sel("conf_stray_timid")} confiscate_badge'),
    (440, f'yukkuri scenario care_click {sel("conf_other")} confiscate_badge'),
    (520, 'yukkuri scenario tool_operator leave'),
], expect=[
    rf'\[badge\][^\n]*uuid={_uuid_text(_CONF, "conf_proud")} [^\n]*confiscated by=player [^\n]*reason=owner_removal ',
    rf'\[badge\][^\n]*uuid={_uuid_text(_CONF, "conf_attached")} [^\n]*confiscated by=player [^\n]*reason=owner_removal ',
    rf'\[badge\][^\n]*uuid={_uuid_text(_CONF, "conf_fearful")} [^\n]*confiscated by=player [^\n]*reason=owner_removal ',
    rf'\[badge\][^\n]*uuid={_uuid_text(_CONF, "conf_stray_bold")} [^\n]*confiscated by=player [^\n]*reason=void ',
    rf'\[badge\][^\n]*uuid={_uuid_text(_CONF, "conf_stray_timid")} [^\n]*confiscated by=player [^\n]*reason=void ',
    rf'\[scenario\][^\n]*action=confiscate_badge target={_uuid_text(_CONF, "conf_proud")} inv_tokens=1 visible=NONE effective=SILVER rank=NONE ',
    rf'\[scenario\][^\n]*action=confiscate_badge target={_uuid_text(_CONF, "conf_attached")} inv_tokens=2 visible=NONE effective=BRONZE rank=NONE ',
    rf'\[scenario\][^\n]*action=confiscate_badge target={_uuid_text(_CONF, "conf_fearful")} inv_tokens=3 visible=NONE effective=BRONZE rank=NONE ',
    rf'\[scenario\][^\n]*action=confiscate_badge target={_uuid_text(_CONF, "conf_stray_bold")} inv_tokens=4 visible=NONE effective=NONE rank=NONE ',
    rf'\[scenario\][^\n]*action=confiscate_badge target={_uuid_text(_CONF, "conf_stray_timid")} inv_tokens=5 visible=NONE effective=NONE rank=NONE ',
    rf'\[interaction\][^\n]*uuid={_uuid_text(_CONF, "conf_other")} [^\n]*interaction refused; [^\n]*action=remove_badge reason=owner\b',
    rf'\[scenario\][^\n]*action=confiscate_badge target={_uuid_text(_CONF, "conf_other")} inv_tokens=5 visible=SILVER effective=SILVER rank=SILVER ',
], forbid=[rf'\[badge\][^\n]*uuid={_uuid_text(_CONF, "conf_other")} [^\n]*confiscated '])


_smoke('park_walk', 1100, [
    r'action=outing_depart pets=2 ', r'action=outing_home',
    rf'action=street_warn target={_uuid_text("park_walk", "outing_stray")}',
    *[rf'uuid={_uuid_text("park_walk", p[1])} [^\n]*outing reached home' for p in _outing_pets],
    *_outing_leads('park_walk', [p[1] for p in _outing_pets], False),
    r'MCY_OUTING_BOWL_STOCKED', r'MCY_OUTING_HOME_LEADS_OFF',
], forbid=[rf'action=street_(?:warn|chase|kick) target=(?:{_outing_ids})'])
next(s for s in S if s.id == 'park_walk').smoke.update(verify=_outing_crossings, verify_trace=True, commands=[
    (1, f'execute if data block {_lot_bowl} Items[{{Slot:0b,id:"mcyukkuri:oil_grain_regular",count:4}}] run say MCY_OUTING_BOWL_STOCKED'),
    (1090, f'execute if entity {sel("outing_shy")} if entity {sel("outing_bold")} '
     f'unless data entity {sel("outing_shy")} leash unless data entity {sel("outing_bold")} leash run say MCY_OUTING_HOME_LEADS_OFF'),
])
def _park_play_pet_fates(window):
    """Both pets choose facilities by stat, play >=2 sessions/facilities, sustain real bounces, match declared faces, and leave after wants end."""
    at_stand = re.search(r'action=outing_park_wait stand=(\([^\n]+\))', window)
    left = re.search(r'action=outing_park_leave stand=(\([^\n]+\)) gap=([\d.]+)', window)
    turn = re.search(r'action=outing_turn reason=(park_done|park_time)\b', window)
    if not at_stand or not left or at_stand[1] != left[1] or float(left[2]) >= .5:
        return 'the keeper did not finish waiting at her park stand'
    if not turn or turn[1] != 'park_done':
        return f'expected keeper leave reason=park_done after wants ended, got {turn[1] if turn else "none"}'
    first_kinds = {}
    for _, name, *_ in _outing_pets:
        actor = _uuid_text('park_play', name)
        sessions = [m for m in re.finditer(
            rf'uuid={actor} [^\n]*facility session ended kind=(\w+)[^\n]*completed=true[^\n]*rounds=(\d+)', window)
            if at_stand.end() < m.start() < left.start()]
        if not sessions:
            return f'{name} did not finish any facility session during the park stay'
        if sessions[-1].end() >= turn.start():
            return f'{name} finished after keeper turned home'
        total_rounds = sum(int(m[2]) for m in sessions)
        kinds = {m[1] for m in sessions}
        first_kinds[name] = sessions[0][1]
        if total_rounds < 2 and len(sessions) < 2 and len(kinds) < 2:
            return f'{name} played too little: sessions={len(sessions)} rounds={total_rounds} kinds={sorted(kinds)}'
        beats = re.findall(rf'uuid={actor} [^\n]*play beat=(\S+) face=(\S+) declared=(\S+) line=(\S+)', window)
        if not beats:
            return f'{name} logged no play beats'
        for beat, shown, declared, _ in beats:
            if shown != declared:
                return f'{name} beat {beat} showed face={shown} instead of declared={declared}'
        beat_names = {b[0] for b in beats}
        if name == 'outing_bold':
            if sessions[0][1] not in ('TRAMPOLINE', 'SLIDE'):
                return f'bold light pet chose {sessions[0][1]} first instead of trampoline or slide'
            if 'TRAMPOLINE' in kinds:
                rebounds = [tuple(map(float, m.groups())) for m in re.finditer(
                    rf'uuid={actor} [^\n]*trampoline rebound weight=([\d.]+) fitness=[\d.]+ energy=([\d.]+)'
                    r' young=false impact=[\d.]+ speed=[\d.]+ height=([\d.]+)', window)]
                real = [r[2] for r in rebounds if r[2] >= .45]
                if len(real) < 6:
                    return f'bold light pet did not sustain trampoline bounces (need >=6 at >=0.45b, got {len(real)})'
                if not (rebounds[0][0] < 0.50 and rebounds[0][1] >= 75.0):
                    return f'bold light pet stats out of expected range: weight={rebounds[0][0]} energy={rebounds[0][1]}'
                for req in ('mount', 'bounce', 'peak'):
                    if req not in beat_names:
                        return f'{name} missed trampoline beat {req} (saw {sorted(beat_names)})'
                if not any(b[0] == 'peak' and b[3] == 'glow' for b in beats):
                    return f'{name} peak beat did not voice glow'
                if not any(b[0].startswith('stop_') or b[0] == 'had_enough' for b in beats):
                    return f'{name} missed need-driven stop beat'
        if name == 'outing_shy':
            if sessions[0][1] != 'CERAMIC':
                return f'timid heavy pet chose {sessions[0][1]} first instead of tube'
            for req in ('choose_facility', 'play', 'go_again', 'had_enough'):
                if req not in beat_names:
                    return f'{name} missed tube beat {req} (saw {sorted(beat_names)})'
    if first_kinds.get('outing_bold') == first_kinds.get('outing_shy'):
        return f'individual stat difference not shown: both pets picked {first_kinds}'
    if re.search(r'facility wait ended [^\n]* waited=[1-9]\d*/', window) and 'play beat=wait_watch' not in window:
        return 'a pet held a waiting stand without logging wait_watch'
    return _verify_outing_pace(window)


def _park_stray_verify(window):
    """Both bored strays see the playground through Senses, pick facilities by stats, sustain play of the kind chosen, and match declared faces."""
    bold_id = _uuid_text('park_stray_play', 'stray_bold')
    timid_id = _uuid_text('park_stray_play', 'stray_timid')
    for name, actor, walk_expected, session_expected in (
            ('stray_bold', bold_id, ('TRAMPOLINE', 'SLIDE'), ('TRAMPOLINE', 'SLIDE')),
            ('stray_timid', timid_id, ('CERAMIC',), ('CERAMIC', 'SLIDE'))):
        walks = [m[1] for m in re.finditer(rf'uuid={actor} [^\n]*stray playground walk kind=(\w+)', window)]
        if not walks or not all(k in walk_expected for k in walks):
            return f'{name} did not walk to expected facility {walk_expected} (saw {walks or "none"})'
        sessions = [m for m in re.finditer(
            rf'uuid={actor} [^\n]*facility session ended kind=(\w+)[^\n]*completed=true[^\n]*rounds=(\d+)', window)]
        completed_kinds = [m[1] for m in sessions]
        if not sessions or not any(k in session_expected for k in completed_kinds):
            return f'{name} did not complete a session on {session_expected} (saw {completed_kinds or "none"})'
        beats = re.findall(rf'uuid={actor} [^\n]*play beat=(\S+) face=(\S+) declared=(\S+) line=(\S+)', window)
        if not beats:
            return f'{name} logged no play beats'
        for beat, shown, declared, _ in beats:
            if shown != declared:
                return f'{name} beat {beat} showed face={shown} instead of declared={declared}'
        beat_names = {b[0] for b in beats}
        if name == 'stray_bold':
            if 'TRAMPOLINE' in completed_kinds:
                rebounds = [tuple(map(float, m.groups())) for m in re.finditer(
                    rf'uuid={actor} [^\n]*trampoline rebound weight=([\d.]+) fitness=[\d.]+ energy=([\d.]+)'
                    r' young=false impact=[\d.]+ speed=[\d.]+ height=([\d.]+)', window)]
                real = [r[2] for r in rebounds if r[2] >= .45]
                if len(real) < 8 or rebounds[0][0] >= 0.50:
                    return f'light bold stray trampoline evidence weak: count={len(real)} weight={rebounds[0][0] if rebounds else "none"}'
                for req in ('mount', 'peak', 'had_enough'):
                    if req not in beat_names:
                        return f'{name} missed beat {req} (saw {sorted(beat_names)})'
                if not any(b[0] == 'peak' and b[3] == 'glow' for b in beats):
                    return f'{name} peak beat did not voice glow'
            else:
                slide_rounds = sum(int(m[2]) for m in sessions if m[1] == 'SLIDE')
                if slide_rounds < 2:
                    return f'bold stray slide session too short: rounds={slide_rounds}'
                for req in ('choose_facility', 'play', 'had_enough'):
                    if req not in beat_names:
                        return f'{name} missed beat {req} (saw {sorted(beat_names)})'
                if not any(b[0] == 'play' and b[3] == 'glow' for b in beats):
                    return f'{name} bold slide play beat did not voice glow'
        else:
            if 'TRAMPOLINE' in completed_kinds:
                return f'timid stray should not bounce on trampoline (saw {completed_kinds})'
            if sum(int(m[2]) for m in sessions) < 2:
                return f'timid stray did not complete >=2 rounds (saw {completed_kinds})'
            for req in ('choose_facility', 'play', 'had_enough'):
                if req not in beat_names:
                    return f'{name} missed beat {req} (saw {sorted(beat_names)})'
    return None


def _park_wait_switch_verify(window):
    """Impatient waiter watches occupied trampoline, gives up past patience with hmph/mood_bad, then plays another free toy."""
    waiter = _uuid_text('park_wait_switch', 'wait_impatient')
    beats = []
    for m in re.finditer(rf'uuid={waiter} [^\n]*play beat=(\S+) face=(\S+) declared=(\S+)(?: held_by=(\S+) held_tier=\S+ held_face=\S+)? line=(\S+) kind=(\S+)', window):
        beat, shown, declared, held_by, line, kind = m.groups()
        # A beat shows what it declared, or a logged holder owns the shown face.
        if shown != declared and not held_by:
            return f'wait_impatient beat {beat} showed face={shown} instead of declared={declared} with no holder'
        beats.append((beat, shown, declared, line, kind))
    if not beats:
        return 'wait_impatient logged no play beats'
    names = [b[0] for b in beats]
    if 'wait_watch' not in names or 'wait_impatient' not in names or 'wait_give_up' not in names:
        return f'wait_impatient missed wait_watch, wait_impatient or wait_give_up (saw {names})'
    if names.count('wait_impatient') != 1:
        return f'wait_impatient fired {names.count("wait_impatient")} times (expected 1): {names}'
    if not (names.index('wait_watch') < names.index('wait_impatient') < names.index('wait_give_up')):
        return f'wait_impatient beat order wrong (saw {names})'
    impatient = next(b for b in beats if b[0] == 'wait_impatient')
    if impatient[1] != 'puff' or impatient[3] != 'request_sulk':
        return f'wait_impatient impatience beat had unexpected face/line: {impatient}'
    give_up = next(b for b in beats if b[0] == 'wait_give_up')
    if give_up[1] != 'hmph' or give_up[3] != 'mood_bad':
        return f'wait_impatient wait_give_up had unexpected face/line: {give_up}'
    give_up_match = re.search(rf'uuid={waiter} [^\n]*play beat=wait_give_up\b', window)
    switched = re.search(rf'uuid={waiter} [^\n]*facility session ended kind=(SLIDE|CERAMIC)[^\n]*completed=true\b', window)
    if not switched or switched.start() <= give_up_match.start():
        return 'wait_impatient did not switch to and complete another free facility after wait_give_up'
    return None


def _park_recall_verify(window):
    """Owner calls a playing pet home on dusk; pet logs called_home (surprise == surprise) and returns home on lead."""
    pet = _uuid_text('park_recall_play', 'recall_bold')
    beats = re.findall(rf'uuid={pet} [^\n]*play beat=(\S+) face=(\S+) declared=(\S+) line=(\S+)', window)
    if not beats:
        return 'recall_bold logged no play beats'
    for beat, shown, declared, _ in beats:
        if shown != declared:
            return f'recall_bold beat {beat} showed face={shown} instead of declared={declared}'
    if not any(b[0] == 'called_home' and b[1] == 'surprise' for b in beats):
        return f'recall_bold did not log called_home with surprise face (saw {beats})'
    return _verify_outing_pace(window)


def _park_slide_verify(window):
    """Bold curious stray completes >=2 slide rounds with matching faces and slide clips; timid stray stays off the slide."""
    bold = _uuid_text('park_slide', 'slide_bold')
    timid = _uuid_text('park_slide', 'slide_timid')
    bold_sessions = [int(m[1]) for m in re.finditer(
        rf'uuid={bold} [^\n]*facility session ended kind=SLIDE[^\n]*completed=true[^\n]*rounds=(\d+)', window)]
    if not bold_sessions or sum(bold_sessions) < 2:
        return f'slide_bold did not complete >=2 SLIDE rounds (saw {bold_sessions})'
    bold_beats = re.findall(
        rf'uuid={bold} [^\n]*play beat=(\S+) face=(\S+) declared=(\S+) line=(\S+) kind=(\S+) clip=(\S+)', window)
    if not bold_beats:
        return 'slide_bold logged no play beats'
    for beat, shown, declared, _, _, _ in bold_beats:
        if shown != declared:
            return f'slide_bold beat {beat} showed face={shown} instead of declared={declared}'
    names = {b[0] for b in bold_beats}
    for req in ('choose_facility', 'play', 'go_again', 'had_enough'):
        if req not in names:
            return f'slide_bold missed beat {req} (saw {sorted(names)})'
    if re.search(rf'uuid={timid} [^\n]*facility session started kind=SLIDE\b', window):
        return 'slide_timid started a SLIDE session'
    timid_beats = re.findall(
        rf'uuid={timid} [^\n]*play beat=(\S+) face=(\S+) declared=(\S+) line=(\S+)', window)
    for beat, shown, declared, _ in timid_beats:
        if shown != declared:
            return f'slide_timid beat {beat} showed face={shown} instead of declared={declared}'
    return None


def _park_tube_verify(window):
    """Timid stray completes >=2 ceramic tube rounds on the park field with matching faces."""
    timid = _uuid_text('park_tube', 'tube_timid')
    sessions = [int(m[1]) for m in re.finditer(
        rf'uuid={timid} [^\n]*facility session ended kind=CERAMIC[^\n]*completed=true[^\n]*rounds=(\d+)', window)]
    if not sessions or sum(sessions) < 2:
        return f'tube_timid did not complete >=2 CERAMIC rounds (saw {sessions})'
    beats = re.findall(
        rf'uuid={timid} [^\n]*play beat=(\S+) face=(\S+) declared=(\S+) line=(\S+)', window)
    if not beats:
        return 'tube_timid logged no play beats'
    for beat, shown, declared, _ in beats:
        if shown != declared:
            return f'tube_timid beat {beat} showed face={shown} instead of declared={declared}'
    names = {b[0] for b in beats}
    for req in ('choose_facility', 'play', 'go_again', 'had_enough'):
        if req not in names:
            return f'tube_timid missed beat {req} (saw {sorted(names)})'
    return None


def _park_rolling_toys_verify(window):
    """Playful strays nudge and roll the yarn ball and glass marble; low-drive stray logs no toy session."""
    low = _uuid_text('park_rolling_toys', 'toy_low_drive')
    if re.search(rf'uuid={low} [^\n]*toy session started\b', window):
        return 'toy_low_drive started a toy session'
    for name, expected_item in (('toy_playful_a', 'yarn_ball'), ('toy_playful_b', 'glass_marble')):
        actor = _uuid_text('park_rolling_toys', name)
        if not re.search(rf'uuid={actor} [^\n]*toy session started [^\n]*item=[^\s]*{expected_item}\b', window):
            return f'{name} did not start a toy session on {expected_item}'
        touches = [int(m[1]) for m in re.finditer(
            rf'uuid={actor} [^\n]*toy session stopped [^\n]*touches=(\d+)', window)]
        if not touches or max(touches) < 1:
            return f'{name} did not nudge/roll its toy (touches={touches})'
    return None


def _park_trampoline_contrast_verify(window):
    """Light bold stray bounces many times and high; heavy timid stray bounces fewer and lower on the trampoline."""
    bold = _uuid_text('park_trampoline_contrast', 'tramp_bold')
    timid = _uuid_text('park_trampoline_contrast', 'tramp_timid')
    stats = {}
    for name, actor in (('tramp_bold', bold), ('tramp_timid', timid)):
        beats = re.findall(rf'uuid={actor} [^\n]*play beat=(\S+) face=(\S+) declared=(\S+) line=(\S+)', window)
        if not beats:
            return f'{name} logged no play beats'
        for beat, shown, declared, _ in beats:
            if shown != declared:
                return f'{name} beat {beat} showed face={shown} instead of declared={declared}'
        rebounds = [tuple(map(float, m.groups())) for m in re.finditer(
            rf'uuid={actor} [^\n]*trampoline rebound weight=([\d.]+) fitness=[\d.]+ energy=([\d.]+)'
            r' young=false impact=[\d.]+ speed=[\d.]+ height=([\d.]+)', window)]
        if not rebounds:
            return f'{name} logged no trampoline rebounds'
        stats[name] = (len(rebounds), max(r[2] for r in rebounds), rebounds[0][0],
                       len([r[2] for r in rebounds if r[2] >= .45]))
    b_n, b_max, b_w, b_real = stats['tramp_bold']
    t_n, t_max, t_w, _ = stats['tramp_timid']
    if b_real < 8 or b_w >= 0.50:
        return f'tramp_bold weak: real={b_real} weight={b_w:.3f}'
    if t_w <= b_w or t_n >= b_n or t_max >= b_max:
        return f'contrast not shown: bold=(n={b_n},max={b_max:.3f},w={b_w:.3f}) timid=(n={t_n},max={t_max:.3f},w={t_w:.3f})'
    return None


# The park's facilities are terrain (the field writes the blocks PlayItem.place sets), so no marker placement is logged: the start
# checks every facility's core block instead, and a field clear must stand the trampoline up again without dropping it as an item.
_SIT = r'action=outing_sit seat=-?\d+, -?\d+, -?\d+ facing=\w+ candidates=[1-9]'
_park_cores = ' '.join(
    'if block ~{} ~ ~{} mcyukkuri:{}'.format(*_lot_spot(spot), kind)
    for kind, spot in (('yukkuri_trampoline', 'trampoline'), ('yukkuri_slide', 'slide'), ('ceramic_tunnel', 'tunnel'),
                       ('yukkuri_pool', 'pool')))
_park_bed = ' '.join(f'if block ~{_PARK_TRAMPOLINE[0] + x} ~{y} ~{_PARK_TRAMPOLINE[1] + z} mcyukkuri:yukkuri_trampoline'
                     for x in (-1, 0, 1) for y in range(2) for z in (-1, 0, 1))
_smoke('park_play', 3200, [
    r'MCY_PARK_FACILITIES_STAND',
    r'action=outing_depart pets=2 ',
    r'action=outing_park facility=',
    _SIT, r'action=outing_stand reason=leave',
    r'action=outing_turn reason=park_done\b',
    r'action=outing_home',
    *[rf'uuid={_uuid_text("park_play", p[1])} [^\n]*outing reached home' for p in _outing_pets],
    *_outing_leads('park_play', [p[1] for p in _outing_pets], True),
    r'MCY_PARK_FACILITY_BEFORE_CLEAR', r'MCY_PARK_FACILITY_CLEARED',
], forbid=[r'outing return blocked', r'action=outing_return_blocked', r'reason=(?:lagging|blocked)',
           r'action=outing_sit result=taken', r'action=outing_stand reason=move',
           r'facility route stalled', r'facility session ended [^\n]*reason=interrupted\b',
           r'\[body\][^\n]*accepted damage cause=fall\b'],
    readout=[
        f'execute {_park_bed} run say MCY_PARK_FACILITY_BEFORE_CLEAR',
        'function mcylab:field/park/clear',
        f'execute {_park_bed}'
        ' unless entity @e[type=minecraft:item,distance=..48,nbt={Item:{id:"mcyukkuri:yukkuri_trampoline"}}]'
        ' run say MCY_PARK_FACILITY_CLEARED',
    ])
next(s for s in S if s.id == 'park_play').smoke.update(
    verify=_park_play_pet_fates, commands=[(1, f'execute {_park_cores} run say MCY_PARK_FACILITIES_STAND')])
_smoke('park_stray_play', 2400, [
    rf'uuid={_uuid_text("park_stray_play", "stray_bold")} [^\n]*stray playground walk kind=(?:TRAMPOLINE|SLIDE)',
    rf'uuid={_uuid_text("park_stray_play", "stray_timid")} [^\n]*stray playground walk kind=CERAMIC',
    rf'uuid={_uuid_text("park_stray_play", "stray_bold")} [^\n]*play beat=had_enough\b',
    rf'uuid={_uuid_text("park_stray_play", "stray_timid")} [^\n]*play beat=had_enough\b',
], forbid=[r'facility route stalled', r'\[body\][^\n]*accepted damage cause=fall\b'])
next(s for s in S if s.id == 'park_stray_play').smoke.update(verify=_park_stray_verify)
_smoke('park_wait_switch', 1600, [
    rf'uuid={_uuid_text("park_wait_switch", "wait_impatient")} [^\n]*play beat=wait_watch\b',
    rf'uuid={_uuid_text("park_wait_switch", "wait_impatient")} [^\n]*play beat=wait_give_up\b',
    rf'uuid={_uuid_text("park_wait_switch", "wait_impatient")} [^\n]*facility session ended kind=(?:SLIDE|CERAMIC)[^\n]*completed=true\b',
], forbid=[r'facility route stalled', r'\[body\][^\n]*accepted damage cause=fall\b'])
next(s for s in S if s.id == 'park_wait_switch').smoke.update(verify=_park_wait_switch_verify)
_smoke('park_recall_play', 2400, [
    r'action=outing_depart pets=1 ',
    r'action=outing_park facility=',
    rf'uuid={_uuid_text("park_recall_play", "recall_bold")} [^\n]*play beat=mount\b',
    r'action=outing_turn reason=(?:night|conditions)\b',
    rf'uuid={_uuid_text("park_recall_play", "recall_bold")} [^\n]*play beat=called_home\b',
    r'action=outing_home',
    rf'uuid={_uuid_text("park_recall_play", "recall_bold")} [^\n]*outing reached home',
    *_outing_leads('park_recall_play', ['recall_bold'], True),
], forbid=[r'outing return blocked', r'action=outing_return_blocked', r'reason=(?:lagging|blocked)',
           r'result=separated', r'facility route stalled', r'\[body\][^\n]*accepted damage cause=fall\b'])
next(s for s in S if s.id == 'park_recall_play').smoke.update(verify=_park_recall_verify)
_smoke('park_slide', 1800, [
    rf'uuid={_uuid_text("park_slide", "slide_bold")} [^\n]*facility session started kind=SLIDE\b',
    rf'uuid={_uuid_text("park_slide", "slide_bold")} [^\n]*play beat=play face=open_smile declared=open_smile line=glow kind=SLIDE\b',
    rf'uuid={_uuid_text("park_slide", "slide_bold")} [^\n]*play beat=go_again\b',
    rf'uuid={_uuid_text("park_slide", "slide_bold")} [^\n]*facility session ended kind=SLIDE[^\n]*completed=true[^\n]*rounds=[2-9]\b',
    rf'uuid={_uuid_text("park_slide", "slide_bold")} [^\n]*play beat=had_enough\b',
], forbid=[
    rf'uuid={_uuid_text("park_slide", "slide_timid")} [^\n]*facility session started kind=SLIDE\b',
    r'facility route stalled', r'\[body\][^\n]*accepted damage cause=fall\b',
])
next(s for s in S if s.id == 'park_slide').smoke.update(verify=_park_slide_verify)
_smoke('park_tube', 1800, [
    rf'uuid={_uuid_text("park_tube", "tube_timid")} [^\n]*facility session started kind=CERAMIC\b',
    rf'uuid={_uuid_text("park_tube", "tube_timid")} [^\n]*play beat=play face=happy declared=happy line=none kind=CERAMIC\b',
    rf'uuid={_uuid_text("park_tube", "tube_timid")} [^\n]*play beat=go_again\b',
    rf'uuid={_uuid_text("park_tube", "tube_timid")} [^\n]*facility session ended kind=CERAMIC[^\n]*completed=true[^\n]*rounds=[2-9]\b',
    rf'uuid={_uuid_text("park_tube", "tube_timid")} [^\n]*play beat=had_enough\b',
], forbid=[r'facility route stalled', r'\[body\][^\n]*accepted damage cause=fall\b'])
next(s for s in S if s.id == 'park_tube').smoke.update(verify=_park_tube_verify)
_smoke('park_rolling_toys', 1400, [
    rf'uuid={_uuid_text("park_rolling_toys", "toy_playful_a")} [^\n]*toy session started [^\n]*yarn_ball\b',
    rf'uuid={_uuid_text("park_rolling_toys", "toy_playful_a")} [^\n]*toy session stopped [^\n]*touches=[1-9]\d*\b',
    rf'uuid={_uuid_text("park_rolling_toys", "toy_playful_b")} [^\n]*toy session started [^\n]*glass_marble\b',
    rf'uuid={_uuid_text("park_rolling_toys", "toy_playful_b")} [^\n]*toy session stopped [^\n]*touches=[1-9]\d*\b',
], forbid=[
    rf'uuid={_uuid_text("park_rolling_toys", "toy_low_drive")} [^\n]*toy session started\b',
    r'facility route stalled', r'\[body\][^\n]*accepted damage cause=fall\b',
])
next(s for s in S if s.id == 'park_rolling_toys').smoke.update(verify=_park_rolling_toys_verify)
_smoke('park_trampoline_contrast', 1800, [
    rf'uuid={_uuid_text("park_trampoline_contrast", "tramp_bold")} [^\n]*facility session started kind=TRAMPOLINE\b',
    rf'uuid={_uuid_text("park_trampoline_contrast", "tramp_bold")} [^\n]*play beat=peak\b',
    rf'uuid={_uuid_text("park_trampoline_contrast", "tramp_bold")} [^\n]*facility session ended kind=TRAMPOLINE[^\n]*completed=true\b',
    rf'uuid={_uuid_text("park_trampoline_contrast", "tramp_timid")} [^\n]*facility session started kind=TRAMPOLINE\b',
    rf'uuid={_uuid_text("park_trampoline_contrast", "tramp_timid")} [^\n]*facility session ended kind=TRAMPOLINE[^\n]*completed=true\b',
], forbid=[r'facility route stalled', r'\[body\][^\n]*accepted damage cause=fall\b'])
next(s for s in S if s.id == 'park_trampoline_contrast').smoke.update(verify=_park_trampoline_contrast_verify)
_picnic_pet = _uuid_text('park_picnic', 'picnic_pet')
_picnic_transfers = [
    r'action=picnic_take item=minecraft:apple crate=[^\n]* count=1',
    rf'action=picnic_offer pet={_picnic_pet} input=minecraft:apple kept=1 minecraft:apple '
    r'placed item=minecraft:apple count=1 drop=([0-9a-f-]{36}) age=-32768 ',
    r'action=picnic_ate item=minecraft:apple count=1',
    rf'uuid={_picnic_pet} [^\n]*pickup item=minecraft:apple count=1 source=([0-9a-f-]{{36}}) ',
    rf'uuid={_picnic_pet} [^\n]*swallowed item=minecraft:apple count=1',
]
_smoke('park_picnic', 3200, [
    r'action=outing_depart pets=1 ', r'action=outing_park facility=', _SIT, *_picnic_transfers,
    *_outing_leads('park_picnic', ['picnic_pet'], True),
    r'action=picnic_half_gone', r'action=outing_home', rf'uuid={_picnic_pet} [^\n]*outing reached home',
    r'MCY_PICNIC_APPLES_ACCOUNTED_FOR',
], forbid=[r'picnic_refused reason=no_dry_ground', r'outing return blocked', r'action=outing_return_blocked',
           r'reason=(?:lagging|blocked)'], readout=[
    f'execute if block {_lot_crate} mcyukkuri:produce_crate unless data block {_lot_crate} Items[0] '
    f'if entity {sel("picnic_keeper")} unless data entity {sel("picnic_keeper")} HandItems[0].id '
    'unless entity @e[type=minecraft:item,distance=..48,nbt={Item:{id:"minecraft:apple"}}] '
    'run say MCY_PICNIC_APPLES_ACCOUNTED_FOR',
])


def _picnic_ledger(window):
    transfers = []
    for transfer in _picnic_transfers:
        matches = list(re.finditer(transfer, window))
        if len(matches) != 1:
            return f'expected exactly one picnic transfer matching {transfer}, found {len(matches)}'
        transfers.append(matches[0])
    take, offer, owner_eat, pickup, pet_eat = transfers
    if (offer[1] != pickup[1] or not take.start() < offer.start() < pickup.start() < pet_eat.start()
            or owner_eat.start() <= offer.start()):
        return 'picnic half did not move from the original placement into the pet meal'
    plays = list(re.finditer(rf'uuid={_picnic_pet} [^\n]*play beat=choose_facility\b', window))
    if not plays or plays[0].start() <= pet_eat.start():
        return 'bored picnic pet did not take the picnic before playing'


next(s for s in S if s.id == 'park_picnic').smoke.update(verify=_picnic_ledger, gating_checks=['LEDGER', 'END'])


def _pocket_rest_verify(window):
    """The owner sits before a tunnel session completes and stands after the last one he watched; a later session is free play
    back at the tunnel after the walk home, outside the outing, and is not judged. Both pets are accounted for by the expects."""
    sat = re.search(r'action=outing_sit seat=', window)
    stood = re.search(r'action=outing_stand reason=leave', window)
    if not sat or not stood or not sat.start() < stood.start():
        return 'the keeper did not sit and then stand up from the bench'
    sessions = [m for m in re.finditer(r'facility session ended kind=CERAMIC[^\n]*completed=true\b', window)]
    if not any(sat.start() < m.start() < stood.start() for m in sessions):
        return 'no tunnel session was completed while the keeper sat'
    return _verify_outing_pace(window)


_smoke('pocket_park_rest', 3200, [
    r'park facility result=placed kind=CERAMIC',
    r'action=outing_depart pets=2 ',
    r'action=outing_park facility=',
    _SIT, r'action=outing_stand reason=leave',
    r'action=outing_turn reason=park_(?:done|time)',
    r'action=outing_home',
    *[rf'uuid={_uuid_text("pocket_park_rest", p[1])} [^\n]*outing reached home' for p in _rest_pets],
    *_outing_leads('pocket_park_rest', [p[1] for p in _rest_pets], True),
], forbid=[r'outing return blocked', r'action=outing_return_blocked', r'reason=(?:lagging|blocked)',
           r'result=separated', r'facility route stalled', r'facility session ended [^\n]*reason=interrupted\b',
           r'action=outing_sit result=taken', r'action=outing_stand reason=move'])
next(s for s in S if s.id == 'pocket_park_rest').smoke.update(verify=_pocket_rest_verify)

_smoke('shop_care', 1100, [
    *[rf'\[npc-care\][^\n]* action=treatment [^\n]*treatment={kind} [^\n]*result=applied'
      for kind in ('ORANGE_JUICE', 'MOLD_REMOVER')],
    r'\[shop\][^\n]* action=job result=asked_way kind=FEED ',
    r'\[move\][^\n]* action=give_way ',
    rf'\[npc-care\][^\n]* action=refill_bowl target={BY_ID["shop"].anchor[0] + 12}, {FEET + 2}, {BY_ID["shop"].anchor[1] + 12} transferred=4 ',
], forbid=[r'\[shop\][^\n]* action=job result=failed kind=(TREAT|FEED) '])
_smoke('shop_buy', 1600, [r'\[shop\][^\n]* action=job result=start kind=BUY ', r'\[shop\][^\n]* action=job result=reach kind=BUY ',
                          r'\[shop\] chen [^\n]* window witness kind=',
                          r'\[shop\][^\n]* action=buy_live result=committed [^\n]* emeralds_used=0\b',
                          r'\[shop\][^\n]* action=buy_live result=delivered [^\n]*placement=counter '],
       forbid=[r'\[shop\][^\n]* action=buy_live result=cancelled', r'\[shop\][^\n]* action=job result=failed '])
# The witness is only raised when the neighbour is actually seen through the divider. Left to wander, Alice may be inside her nest,
# whose rim sits above her eye and hides her across the glass, so the pair is put in the clear back row just before the sale.
next(s for s in S if s.id == 'shop_buy').smoke['commands'] = [
    (198, f'tp {ALICE} ~17.5 ~2 ~10.5'), (198, f'tp {CHEN} ~13.5 ~2 ~10.5'),
    (200, f'yukkuri human shop buy {SHOP_STAFF} {BOOTH.format("alice", 3)} {_uuid_text("shop_buy", "buyer")}')]


_smoke('shop_browse', 140, [r'\[shop\][^\n]* action=register shop=[^\n]*kind=PET ', r'MCYSHOP_BROWSE_CHEN'],
       readout=['yukkuri human shop list', f'yukkuri status {BOOTH.format("chen", 0)} raw'])
next(s for s in S if s.id == 'shop_browse').smoke['commands'] = [
    (100, f'execute if entity {BOOTH.format("chen", 0)} run say MCYSHOP_BROWSE_CHEN'),
]

def _verify_shop_pick(readout):
    rows = re.findall(
        r'^entity\.mcyukkuri\.(chen|alice) #(\d+) \{.*?growth:\{age_ticks:(\d+)L,[^}]*\bstage:"([^"]+)"[^}]*\}'
        r'.*?identity:\{[^}]*\bkind:"([^"]+)"[^}]*\}.*?personality:\{[^}]*\bboldness:(\d+),friendliness:(\d+),',
        readout, re.M)
    expected = [(kind, '148000', 'juvenile', 'stock', str(boldness), str(friendliness))
                for _, kind, _, boldness, friendliness in _pick_stock]
    if [(row[0], *row[2:]) for row in rows] != expected or len({row[1] for row in rows}) != len(expected):
        return f'Expected the two room residents with age, stage and effective traits {expected}; got {rows}'


_smoke('shop_pick', 140, [r'\[shop\][^\n]* action=register shop=[^\n]*kind=PET '],
       readout=[f'yukkuri status {sel(name)} raw' for name, *_ in _pick_stock])
next(s for s in S if s.id == 'shop_pick').smoke['verify_readout'] = _verify_shop_pick


def _shop_scene_actors(trace, names):
    actors = {name: set() for name in names}
    for line in trace:
        row = json.loads(line.split('[trace] ', 1)[1])
        if row['k'] == 'geo':
            for name in names:
                if tag(name) in row.get('tags', ()):
                    actors[name].add(row['u'])
    return actors


def _shop_cell(dx, dz):
    x, z = BY_ID['shop'].anchor
    return f'{x + dx}, {FEET + 2}, {z + dz}'


def _verify_shop_fetch(window, trace):
    animals = re.findall(r'\[shop\] (chen|alice|reimu) [^\n]* uuid=(\S+)[^\n]* pos=([^\n]+?) action=buy_live result=accepted [^\n]* buyer=operator due=0 sleeping=(true|false)', window)
    reaches = re.findall(r'\[shop\][^\n]* action=job result=reach kind=BUY reach=(hand|through) [^\n]* call_ticks=(\d+) target=(\S+)', window)
    packed = re.findall(r'\[shop\][^\n]* action=buy_live result=committed [^\n]* target=(\S+) token=(\S+) emeralds_used=0\b', window)
    delivered = re.findall(r'\[shop\][^\n]* action=buy_live result=delivered [^\n]* token=(\S+) placement=counter\b [^\n]* target=(\S+)', window)
    names = [name for name, _ in _fetch_stock]
    displayed = _shop_scene_actors(trace, names)
    if [(kind, {animal}) for kind, animal, _, _ in animals] != [(kind, displayed[name]) for name, kind in _fetch_stock]:
        return f'Expected the three staged stock orders in order, got {animals}'
    ids = [animal for _, animal, _, _ in animals]
    if sorted(animal for _, _, animal in reaches) != sorted(ids) or ('through', '100', ids[0]) not in reaches \
            or not any(mode == 'hand' and animal == ids[2] for mode, _, animal in reaches):
        return f'Expected one reach per animal, with through after 100 ticks for the trapped corner and hand for the yard, got {reaches}'
    if [animal for animal, _ in packed] != ids or packed != [(animal, token) for token, animal in delivered]:
        return f'Expected all three packed tokens in carrier deliveries at the counter, got {packed} and {delivered}'
    if animals[1][2:] != (_shop_cell(18, 13), 'true'):
        return f'Expected Alice to be asleep on the nest pad at purchase, got {animals[1][2:]}'


_smoke('shop_fetch', 1800, [r'\[shop\][^\n]* action=job result=reach kind=BUY ', r'\[shop\][^\n]* action=buy_live result=delivered '],
       forbid=[r'\[shop\][^\n]* action=buy_live result=cancelled', r'\[shop\][^\n]* reason=unreachable\b'])
_fetch_smoke = next(s for s in S if s.id == 'shop_fetch').smoke
_fetch_smoke.update(verify=_verify_shop_fetch, verify_trace=True, commands=[
    (100 + 400 * i, f'yukkuri human shop buy {SHOP_STAFF} {sel(name)} {_uuid_text("shop_fetch", "buyer")}')
    for i, (name, _) in enumerate(_fetch_stock)])


def _litter_parents(window):
    return dict((animal, (mother, father)) for animal, mother, father in re.findall(
        r'\[family\][^\n]* uuid=(\S+)[^\n]* wholesale litter mother=(\S+) father=(\S+)', window))


def _litter_problem(window, members):
    parents = _litter_parents(window)
    if not members or not members <= parents.keys() or len({parents[animal] for animal in members}) != 1:
        return f'Expected one parent pair for litter members {members}, got {parents}'
    for line in window.splitlines():
        if '[family]' in line and re.search(r'family search started|orphan|foster', line):
            if any(animal in line for animal in members):
                return f'Shop litter entered family separation: {line}'


def _verify_shop_restock(window):
    arrivals = re.findall(r'\[shop\][^\n]* uuid=(\S+)[^\n]* action=restock result=arrival [^\n]* token=(\S+)', window)
    beds = '|'.join(_shop_cell(dx, dz) for dx, dz in ((10, -2), (9, -3), (10, -3)))
    releases = re.findall(rf'\[shop\][^\n]* uuid=(\S+)[^\n]* action=settle result=released [^\n]* token=(\S+) nest=(?:{beds}) home=true\b', window)
    if len(arrivals) < 2 or len(set(arrivals)) != len(arrivals) or sorted(arrivals) != sorted(releases):
        return f'Expected every litter basket to release a different resident at the family nest, got {arrivals} and {releases}'
    return _litter_problem(window, {animal for animal, _ in releases})


_smoke('shop_restock', 3600, [r'\[shop\][^\n]* action=restock result=done ', r'\[shop\][^\n]* action=restock result=ship_marked ',
                              r'\[shop\][^\n]* action=ship_out result=packed ', r'\[shop\][^\n]* action=stow_basket result=stored ',
                              r'\[shop\][^\n]* action=restock result=arrival ', r'\[shop\][^\n]* action=settle result=released '],
       forbid=[r'\[shop\][^\n]* action=job result=put_down '], readout=[f'yukkuri status {STOCK} raw'])
next(s for s in S if s.id == 'shop_restock').smoke['verify'] = _verify_shop_restock

_w1_first_sip = rf'\[needs\] alice [^\n]* drink source=BlockPos\{{x={BY_ID["shop"].anchor[0] + 16}, y={FEET + 2}, z={BY_ID["shop"].anchor[1] + 13}\}} kind=waterer water=1 -> 0 dryness=60\.0 -> 30\.0'
_w1_refill = rf'\[npc-care\][^\n]* uuid=(\S+)[^\n]* action=refill_water target={_shop_cell(16, 13)} water=0 -> 8'


def _verify_shop_water(window):
    sip = re.search(_w1_first_sip, window)
    refill = re.search(_w1_refill, window)
    stow = re.search(rf'\[npc-care\][^\n]* uuid={refill[1]} [^\n]* action=stow_water_bucket\b', window) if refill else None
    if not sip or not refill or not stow or not sip.start() < refill.start() <= stow.start():
        return 'Expected Alice to drink the last portion before that staff member refills and stows the bucket'


_smoke('shop_water', 650, [_w1_first_sip, _w1_refill, r'\[npc-care\][^\n]* action=stow_water_bucket '],
       forbid=[r'\[shop\][^\n]* action=job result=failed kind=WATER '])
next(s for s in S if s.id == 'shop_water').smoke['verify'] = _verify_shop_water


def _verify_shop_kin_room(window, trace):
    actors = _shop_scene_actors(trace, [name for name, *_ in _kin_room])
    if any(len(ids) != 1 for ids in actors.values()):
        return f'Expected two distinct staged littermates, got {actors}'
    bold, timid = (next(iter(ids)) for ids in actors.values())
    siblings = {bold, timid}
    if len(siblings) != 2:
        return f'Expected two distinct staged littermates, got {actors}'
    problem = _litter_problem(window, siblings)
    if problem:
        return problem
    x, z = BY_ID['shop'].anchor
    beds = [(x + 10, FEET + 2, z - 2), (x + 9, FEET + 2, z - 3)]
    homes = {}
    for bed in beds:
        at = rf'x={bed[0]}, y={bed[1]}, z={bed[2]}\}}'
        claims = set(re.findall(rf'\[home\][^\n]* uuid=(\S+)[^\n]* assigned kind=care [^\n]* home=[^\n]*{at}', window))
        if len(claims) != 1 or not claims <= siblings:
            return f'Expected one sibling to claim each adjacent bed, got {claims} at {bed}'
        homes[claims.pop()] = bed
    if homes.keys() != siblings:
        return 'Expected different siblings to claim each adjacent bed'
    wait = re.search(rf'\[meal\][^\n]* supply guarded [^\n]* kind=water guard={bold} approacher={timid} reason=harsh', window)
    drink = re.search(rf'\[needs\][^\n]* uuid={timid} [^\n]* drink [^\n]* kind=waterer water=[1-8] -> [0-7] dryness=55\.0 -> 25\.0', window)
    if not wait or not drink or wait.start() >= drink.start():
        return 'Expected the timid littermate to wait for the bold one and then drink'
    bowl = rf'BlockPos\{{x={x + 12}, y={FEET + 2}, z={z - 1}\}}'
    after_water = window[drink.end():]
    for sibling in (bold, timid):
        meal = rf'\[meal\][^\n]* uuid={sibling} [^\n]* '
        grain = r'item=mcyukkuri:oil_grain_regular count=1 '
        pickup = re.search(meal + rf'pickup {grain}source={bowl} holder=mouth', after_water)
        if not pickup or not re.search(meal + rf'swallowed {grain}', after_water[pickup.end():]):
            return f'Expected sibling {sibling} to eat from the family bowl after drinking'
    asleep = {}
    for line in trace:
        row = json.loads(line.split('[trace] ', 1)[1])
        if row['k'] == 'st' and row['u'] in siblings:
            state = row['st']
            if state.get('sleeping'):
                if state.get('nest') != 'INSIDE_SUPPORTED' or tuple(state.get('home') or ()) != homes[row['u']]:
                    return f'Sibling {row["u"]} slept outside its own bed at tick {row["t"]}'
                asleep.setdefault(row['t'], {})[row['u']] = state
    streak, previous = 0, None
    for tick, sleepers in sorted(asleep.items()):
        if sleepers.keys() != siblings:
            continue
        a, b = sleepers[bold], sleepers[timid]
        dx, dz = abs(a['p'][0] - b['p'][0]), abs(a['p'][2] - b['p'][2])
        if max(dx, dz) < (a['sleepWidth'] + b['sleepWidth']) / 2 or math.hypot(dx, dz) < a['sleepReach'] + b['sleepReach']:
            return f'Siblings overlap in the adjacent beds at tick {tick}'
        streak = streak + 1 if previous == tick - 1 else 1
        previous = tick
        if streak >= 20:
            return None
    return 'Expected 20 consecutive ticks asleep together inside adjacent beds'


_smoke('shop_kin_room', 2200, [r'\[shop\][^\n]* action=family_bed result=installed grade=adjacent ',
                              r'\[meal\][^\n]* supply guarded [^\n]* kind=water ',
                              r'\[needs\][^\n]* drink [^\n]* kind=waterer '])
next(s for s in S if s.id == 'shop_kin_room').smoke.update(verify=_verify_shop_kin_room, verify_trace=True)

_water_beg = rf'\[request\][^\n]* uuid={_water_pet} [^\n]* started kind=WATER '
_water_fill = r'\[interaction\][^\n]* waterer filled player=MuseumOperator [^\n]* item=minecraft:water_bucket returned=minecraft:bucket water=0 -> 8'
_water_answer = rf'\[request\][^\n]* uuid={_water_pet} [^\n]* satisfied kind=WATER '
_water_drink = rf'\[needs\][^\n]* uuid={_water_pet} [^\n]* drink [^\n]* kind=waterer water=8 -> 7 dryness=50\.0 -> 20\.0'


def _verify_pet_water(window):
    events = [re.search(pattern, window) for pattern in (_water_beg, _water_fill, _water_answer, _water_drink)]
    if not all(events) or sorted(event.start() for event in events) != [event.start() for event in events]:
        return 'Expected the request, survival bucket exchange, relief and sip in order'


_smoke('pet_water', 480, [_water_beg, _water_fill, _water_answer, _water_drink, r'MCY_PET_WATER_BUCKET_RETURN'],
       readout=['data get block ~3 ~ ~ Water', f'yukkuri status {sel("water_pet")} raw'])
_water_operator = '@p[name=MuseumOperator,distance=..32]'
_smoke('pet_food_walled', 900, [rf'\[meal\][^\n]* uuid={_walled_pet} [^\n]* swallowed item=minecraft:apple'],
       forbid=[rf'\[request\][^\n]* uuid={_walled_pet} [^\n]* started kind=FOOD ', rf'\[request\][^\n]* uuid={_walled_pet} [^\n]* expired '],
       readout=[f'yukkuri status {sel("walled_pet")} raw'])
# A bowl it never saw is worth a walk, not a request; once it sees the bowl empty it asks, and gives up neither the walk nor the ask for nothing.
_smoke('pet_food_empty', 700, [rf'\[request\][^\n]* uuid={_empty_pet} [^\n]* started kind=FOOD ', rf'\[request\][^\n]* uuid={_empty_pet} [^\n]* beat=ask face=\S+ line=ask_food kind=FOOD'],
       forbid=[rf'\[request\][^\n]* uuid={_empty_pet} [^\n]* expired '], readout=[f'yukkuri status {sel("empty_pet")} raw'])
next(s for s in S if s.id == 'pet_food_empty').smoke.update(commands=[
    (1, 'execute positioned ~-3 ~ ~2 run yukkuri scenario tool_operator enter'),
    (2, f'yukkuri scenario owner_at {sel("empty_pet")} watch'),
    (3, 'execute positioned ~-3 ~ ~2 run yukkuri scenario tool_operator move'),
    (680, 'yukkuri scenario tool_operator leave'),
])
_away_fill = r'\[interaction\][^\n]* waterer filled player=MuseumOperator [^\n]* water=0 -> 8'
_away_unseen = r'\[perception\][^\n]* event cue=POUR_BUCKET [^\n]* witnesses=0'
_away_found = rf'\[request\][^\n]* uuid={_away_pet} [^\n]* beat=discovered face=\S+ line=\S+ kind=WATER via=discovered'
_away_drink = rf'\[needs\][^\n]* uuid={_away_pet} [^\n]* drink [^\n]* kind=waterer water=8 -> 7 '


def _verify_pet_water_away(window):
    events = [re.search(pattern, window) for pattern in (_away_fill, _away_unseen, _away_found, _away_drink)]
    if not all(events) or sorted(event.start() for event in events) != [event.start() for event in events]:
        return 'Expected the refill nobody perceived, then the pet finding the full waterer and the sip, in order'


# The pet is shut out behind closed gates (no sight line, wall-muffled sound) while the owner pours, then the gates open and it walks in:
# no witness, no thanks; it learns by looking and its pleased beat is about the water. No teleport anywhere.
_smoke('pet_water_away', 700, [_away_fill, _away_unseen, _away_found, _away_drink],
       forbid=[rf'\[perception\][^\n]* uuid={_away_pet} [^\n]* noticed ', rf'\[request\][^\n]* uuid={_away_pet} [^\n]* beat=thanks',
               rf'\[request\][^\n]* uuid={_away_pet} [^\n]* satisfied kind=WATER [^\n]* via=(?:saw|heard|own)',
               rf'\[request\][^\n]* uuid={_away_pet} [^\n]* expired '],
       readout=['data get block ~3 ~ ~ Water', f'yukkuri status {sel("away_pet")} raw'])
next(s for s in S if s.id == 'pet_water_away').smoke.update(verify=_verify_pet_water_away, commands=[
    (1, 'execute positioned ~2.5 ~ ~1.5 run yukkuri scenario tool_operator enter'),
    (2, f'yukkuri scenario owner_at {sel("away_pet")} watch'),
    (3, 'execute positioned ~2.5 ~ ~1.5 run yukkuri scenario tool_operator move'),
    (4, f'gamemode survival {_water_operator}'),
    (5, f'item replace entity {_water_operator} weapon.mainhand with minecraft:water_bucket'),
    # The owner pours first (the pet is shut out behind the gates), then opens them; the pet comes in by itself.
    (60, 'yukkuri scenario shop_block_click ~3 ~ ~'),
    *((120, f'setblock ~{x} ~ ~7 minecraft:oak_fence_gate[facing=south,open=true]') for x in (-1, 0, 1)),
    (680, 'yukkuri scenario tool_operator leave'),
])
next(s for s in S if s.id == 'pet_water').smoke.update(verify=_verify_pet_water, commands=[
    (1, 'execute positioned ~2.5 ~ ~1.5 run yukkuri scenario tool_operator enter'),
    (2, f'yukkuri scenario owner_at {sel("water_pet")} watch'),
    (3, 'execute positioned ~2.5 ~ ~1.5 run yukkuri scenario tool_operator move'),
    (4, f'gamemode survival {_water_operator}'),
    (5, f'item replace entity {_water_operator} weapon.mainhand with minecraft:water_bucket'),
    (120, 'yukkuri scenario shop_block_click ~3 ~ ~'),
    (121, f'execute if items entity {_water_operator} weapon.mainhand minecraft:bucket if data block ~3 ~ ~ {{Water:8}} run say MCY_PET_WATER_BUCKET_RETURN'),
    (470, 'yukkuri scenario tool_operator leave'),
])
_plant_site = '@e[type=minecraft:marker,tag=mcy_plant_site,distance=..45,limit=1]'
_plant_staff = '@e[type=mcyukkuri:caretaker_brother,distance=..45,nbt={HumanJob:2}]'
_plant_posts = ('counter', 'receive', 'sweetening', 'belt_steamer', 'classroom', 'packing', 'roller')


def _verify_plant(window):
    sites = re.findall(r'\[factory\][^\n]* uuid=(\S+) [^\n]* action=register_site min=([^\n]+)', window)
    posts = re.findall(r'\[factory\][^\n]* action=post_staff role=plant_(\S+) site=(\S+)', window)
    if len(sites) != 1 or sorted(posts) != sorted((role, sites[0][0]) for role in _plant_posts):
        return f'Expected one plant and seven staff sharing its UUID, got {sites} and {posts}'


_smoke('processing_plant', 120, [r'MCY_PLANT_CLEAR_POSTS', r'MCY_LEGACY_OUTPUT_ACCEPTED', r'MCY_LEGACY_SOURCE_CLEARED',
                                r'MCY_PLANT_BELT_AND_STEAMER',
                                r'\[factory\][^\n]* action=legacy_tray_unload',
                                r'\[custody\][^\n]* factory closure cleanup completed',
                                r'\[remains\][^\n]* legacy factory claim cleared'],
       readout=[f'data get entity {_plant_site}', f'execute as {_plant_staff} run data get entity @s HumanHome',
                'data get block ~24 ~ ~-23 Items'])
_plant_smoke = next(s for s in S if s.id == 'processing_plant').smoke
_plant_smoke['verify'] = _verify_plant

_sweet_pressure = (r'\[factory\][^\n]* uuid=(\S+) t=(\d+)[^\n]* action=sweetening decision=pressure staff=(\S+)'
                   r' item=(\S+) unease=([\d.]+) -> ([\d.]+) health=([\d.]+) sweet=([\d.]+) fear=(\d+)')
_sweet_victim = (r'\[punishment\][^\n]* uuid=(\S+) t=(\d+)[^\n]* victim actor=(\S+) incident=(\S+)'
                 r' response=(\w+) fear=(\d+) -> (\d+)')
_sweet_bind = r'\[interaction\][^\n]* uuid=(\S+) t=(\d+)[^\n]* operation started; actor=(\S+) pose=PIN [^\n]*reserved_item=(\S+) count=1'


def _verify_sweet_box(window):
    pressure = re.findall(_sweet_pressure, window)
    if len(pressure) < 2:
        return f'Expected two natural staff contacts, got {pressure}'
    stock = dict(re.findall(r'\[factory\][^\n]* uuid=(\S+) [^\n]* action=opening_stock box=([BD]) ', window))
    if any(stock.get(animal) == 'D' for animal, *_ in pressure):
        return 'Staff worked the rich stock marked for rest'
    pale = window.find('action=box_readout box=甜化箱 B pale=1 sweet=0')
    ordinary = window.find('action=box_readout box=甜化箱 B pale=0 sweet=1')
    if pale < 0 or ordinary <= pale or not any(stock.get(m[1]) == 'B' and float(m[6]) > float(m[5])
                                                for m in re.finditer(_sweet_pressure, window[pale:ordinary])):
        return 'B must go from pale to ordinary on its sign after staff contact increases its unease'
    victims = re.findall(_sweet_victim, window)
    for animal, tick, staff, tool, before, after, health, sweet, fear in pressure:
        if float(before) >= 72 or float(after) >= 95 or float(health) <= .65:
            return f'Unsafe staff contact on {animal}: {before} -> {after}, health {health}'
        if not any(who == animal and when == tick and actor == staff for who, when, actor, *_ in victims):
            return f'No individual reaction to {tool} from {staff} on {animal} at {tick}'
    cast = {_uuid_text(_sweet_box, name) for name, *_ in _sweet_cast}
    workers = {staff for _, _, staff, *_ in pressure}
    compared = [(animal, actor, response, int(before), int(after)) for animal, _, actor, incident, response, before, after in victims
                if incident == 'hurt' and animal in cast and actor not in workers]
    if {animal for animal, *_ in compared} != cast or len({response for _, _, response, *_ in compared}) < 2:
        return f'Expected contrasting responses to the same player tool across all three actors, got {compared}'
    if len({actor for _, actor, *_ in compared}) != 1 or any(after <= before for _, _, _, before, after in compared):
        return f'Expected fear of the same actual operator to increase, got {compared}'
    packs = list(re.finditer(r'\[factory\][^\n]* uuid=(\S+) [^\n]* action=table_carry result=packed target=(\S+) token=(\S+) table=(\S+)', window))
    if not packs:
        return 'Expected staff to carry at least one animal to the placed table'
    for pack in packs:
        staff, animal, token, table = pack.groups()
        tail = window[pack.end():]
        placed = re.search(rf'\[factory\][^\n]* uuid={animal} [^\n]* action=table_carry result=placed staff={staff} token={token} table={re.escape(table)}', tail)
        if placed is None:
            return f'The basketed animal {animal} never reached the table'
        if not re.search(rf'\[interaction\][^\n]* uuid={animal} [^\n]* table unstrapped; actor={staff} table={re.escape(table)} reason=(?:released|body_need)', tail[placed.end():]):
            return f'The table never released {animal} after the staff contact'
    for bind in re.finditer(_sweet_bind, window):
        animal, tick, staff, item = bind.groups()
        tail = window[bind.end():]
        end = re.search(rf'\[interaction\][^\n]* uuid={animal} t=(\d+)[^\n]* operation ended; actor={staff} pose=PIN', tail)
        if end is None or int(end[1]) - int(tick) > 242:
            return f'PIN on {animal} at {tick} did not end within the shared operation limit'
        returned = re.search(rf'\[interaction\][^\n]* uuid={animal} t={end[1]} [^\n]* binding returned to world; drop=(\S+) item={re.escape(item)} count=1', tail)
        if returned is None or not re.search(rf'\[interaction\][^\n]* uuid={staff} [^\n]* tool picked up; drop={returned[1]} item={re.escape(item)} hand_count=0 -> 1', tail[returned.end():]):
            return f'The worker never recovered the pin from {animal} by its drop UUID'
    return None


_smoke(_sweet_box, 2400, [
    r'\[factory\][^\n]* action=opening_stock box=B [^\n]* unease=53\.0 sweet=62\b',
    r'\[factory\][^\n]* action=opening_stock box=C [^\n]* unease=68\.0 sweet=68\b',
    r'\[factory\][^\n]* action=opening_stock box=D [^\n]* unease=80\.0 sweet=77\b',
    r'\[factory\][^\n]* action=table_deploy result=placed [^\n]* held=0',
    r'action=box_readout box=甜化箱 C pale=0 sweet=1 rich=0 sick=0',
    r'action=box_readout box=甜化箱 D pale=0 sweet=0 rich=1 sick=0 resting=1',
], forbid=[r'\[body\][^\n]* death settled', r'\[punishment\][^\n]* response=collapse'], readout=[
    *[f'yukkuri status {sel(name)} raw' for name, *_ in _sweet_cast],
    *[f'data get block ~{_plant_x - 3} ~4 ~{_plant_z + z} front_text.messages' for z in (17, 22, 27, 34)],
])
_sweet_smoke = next(s for s in S if s.id == _sweet_box).smoke
_sweet_smoke['verify'] = _verify_sweet_box
_sweet_smoke['commands'] = [
    *[(2050 + 2 * i, f'execute at {sel(name)} run yukkuri scenario tool_click {sel(name)} pierce')
      for i, (name, *_) in enumerate(_sweet_cast)],
]

_kin_ids = {name: _uuid_text(_kin_boxes, name) for name, *_ in _kin_cast}
_kin_window = _kin_ids['kin_window']
_kin_hidden = _kin_ids['kin_hidden']
_kin_fond = _kin_ids['kin_fond']
_kin_selfish = _kin_ids['kin_selfish']
_kin_screened = _kin_ids['kin_screened']
_kin_near = _kin_ids['kin_near']
_kin_staff = '@e[type=mcyukkuri:caretaker_brother,distance=..45,limit=1,nbt={SiteRole:"plant_sweetening"}]'
_kin_has_stock = f'if data entity {sel("kin_window")} {OWNED}{{Use:"plant_stock"}}'
_kin_mother_nbt = uuid_nbt(uid(_kin_boxes, 'kin_fond'))
_kin_father_nbt = uuid_nbt(uid(_kin_boxes, 'kin_selfish'))
_kin_has_parents = f'if data entity {sel("kin_window")} YukkuriFamily{{Mother:{_kin_mother_nbt},Father:{_kin_father_nbt}}}'
_kin_witness = r'\[punishment\][^\n]* uuid={parent} t=(\d+)[^\n]* witness victim={child} actor=(\S+) incident=hurt response={choice} glass=true'
_kin_approach = r'\[factory\][^\n]* uuid={parent} [^\n]* kin tool approach started=(\d+) child={child} staff=(\S+) response={choice}\b'
_kin_release = rf'\[identity\][^\n]* uuid={_kin_window} [^\n]* changed from=owned to=stray cause=plant_exit\b'


def _verify_kin_boxes(window):
    seen = [re.findall(_kin_witness.format(parent=parent, child=_kin_window, choice=choice), window)
            for parent, choice in ((_kin_fond, 'PRESS'), (_kin_selfish, 'TURN_AWAY'))]
    if not set(seen[0]).intersection(seen[1]):
        return f'The two parents never reacted differently to the same blow across glass: {seen}'
    visits = [re.findall(_kin_approach.format(parent=parent, child=_kin_near, choice=choice), window)
              for parent, choice in ((_kin_fond, 'SHIELD'), (_kin_selfish, 'PUSH'))]
    if any(len(results) != 1 for results in visits) or visits[0][0] != visits[1][0]:
        return f'Expected one response per parent to the same staff approach: {visits}'
    staff = visits[0][0][1]
    shields = re.findall(rf'\[family\][^\n]* uuid={_kin_fond} [^\n]* kin shield reached child={_kin_near} actor={staff}\b', window)
    if len(shields) != 1:
        return f'Expected one shield contact on the staff approach, got {len(shields)}'
    pushes = re.findall(rf'\[action\][^\n]* uuid={_kin_selfish} [^\n]* end id=push outcome=DONE [^\n]* target={_kin_near} moved=([\d.]+)', window)
    if len(pushes) != 1 or float(pushes[0]) <= .05:
        return f'Expected one actual child shove with displacement, got {pushes}'
    point = r'(-?\d+, -?\d+, -?\d+)'
    bounds = re.findall(rf'\[factory\][^\n]* action=register_site min={point} max={point}', window)
    releases = list(re.finditer(rf'\[identity\][^\n]* uuid=(\S+) [^\n]* pos={point} changed from=owned to=stray cause=plant_exit\b', window))
    if [release[1] for release in releases] != [_kin_window] or len(bounds) != 1:
        return f'Expected one plant boundary and one release of the child that crossed it, got {bounds} and {[r.groups() for r in releases]}'
    low, high, feet = (tuple(map(int, at.split(', '))) for at in (*bounds[0], releases[0][2]))
    if all(a <= v <= b for a, v, b in zip(low, feet, high)):
        return f'Plant released the child inside its boundary at {feet}'
    if not 0 <= window.find('MCY_KIN_OPEN_WINDOW_STILL_STOCK') < releases[0].start() < window.find('MCY_KIN_STRAY_PARENTS_KEPT'):
        return 'Expected stock at the broken window, release after crossing, then stray with both parents'
    return None


_smoke(_kin_boxes, 500, [
    rf'\[punishment\][^\n]* uuid={_kin_hidden} [^\n]* victim actor=\S+ incident=hurt',
    rf'\[social\][^\n]* uuid={_kin_fond} [^\n]* protection puff victim={_kin_window} ',
    rf'\[action\][^\n]* uuid={_kin_fond} [^\n]* start id=glass_press owner=protect_approach',
    rf'\[action\][^\n]* uuid={_kin_fond} [^\n]* end id=glass_press outcome=DONE [^\n]* faceGap=',
    rf'\[family\][^\n]* uuid={_kin_fond} [^\n]* kin shield reached child={_kin_near} ',
    r'MCY_KIN_OPEN_WINDOW_STILL_STOCK',
    rf'\[punishment\][^\n]* uuid={_kin_window} [^\n]* victim actor=\S+ incident=hurt response=ESCAPE\b',
    _kin_release,
    r'MCY_KIN_STRAY_PARENTS_KEPT',
], forbid=[
    rf'\[punishment\][^\n]* uuid={_kin_screened} [^\n]* witness victim={_kin_hidden} ',
    r'\[body\][^\n]* death settled',
    r'\[punishment\][^\n]* response=collapse',
], readout=[f'yukkuri status {sel(name)} raw' for name, *_ in _kin_cast])
_kin_smoke = next(s for s in S if s.id == _kin_boxes).smoke
_kin_smoke['verify'] = _verify_kin_boxes
_kin_smoke['commands'] = [
    (1, f'data merge entity {_kin_staff} {{NoAI:1b}}'),
    # Observe the same-box visit before the sibling can hear the across-box alarm.
    (42, f'fill {_kin_point(6, 3, 17)} {_kin_point(6, 4, 18)} minecraft:air replace minecraft:waxed_copper_door'),
    (42, f'tp {sel("kin_fond")} {_kin_point(5.18, 3, 19.62)} -90 0'),
    (42, f'tp {sel("kin_selfish")} {_kin_point(3.56, 3, 19.62)} -90 0'),
    (42, f'tp {sel("kin_near")} {_kin_point(4.37, 3, 19.62)} -90 0'),
    (42, f'tp {_kin_staff} {_kin_point(8.5, 3, 19.5)} 90 0'),
    (42, f'item replace entity {_kin_staff} weapon.mainhand from block {_sweet_cabinet} container.0'),
    (42, f'item replace block {_sweet_cabinet} container.0 with minecraft:air'),
    (42, f'data merge entity {_kin_staff} {{NoAI:0b}}'),
    (144, f'data merge entity {_kin_staff} {{NoAI:1b}}'),
    (160, f'tp {sel("kin_fond")} {_kin_point(4.5, 3, 20.5)} 0 0'),
    (160, f'tp {sel("kin_selfish")} {_kin_point(3.55, 3, 19.65)} 0 0'),
    (160, f'tp {sel("kin_window")} {_kin_point(4.5, 3, 22.5)} 180 0'),
    (164, f'execute at {sel("kin_window")} run yukkuri scenario tool_click {sel("kin_window")} pierce'),
    (250, f'execute at {sel("kin_hidden")} run yukkuri scenario tool_click {sel("kin_hidden")} pierce'),
    (318, f'tp {sel("kin_window")} {_kin_point(2.5, 3, 22.5)} 90 0'),
    (320, f'fill {_kin_point(1, 3, 22)} {_kin_point(1, 4, 23)} minecraft:air replace minecraft:glass'),
    (321, f'execute if block {_kin_point(1, 3, 22)} minecraft:air {_kin_has_stock} {_kin_has_parents} run say MCY_KIN_OPEN_WINDOW_STILL_STOCK'),
    (324, f'execute positioned {_kin_point(4.5, 3, 22.5)} run yukkuri scenario tool_click {sel("kin_window")} pierce'),
    (485, f'execute if entity {sel("kin_window")} unless data entity {sel("kin_window")} {OWNED}.Owner '
          f'if data entity {sel("kin_window")} YukkuriPet{{TownRoots:1b}} {_kin_has_parents} run say MCY_KIN_STRAY_PARENTS_KEPT'),
]

_legacy_bag = '{id:"mcyukkuri:live_yukkuri_bag",count:1,components:{"minecraft:custom_data":{CaptiveToken:[I;8,7,6,5],EntityUUID:[I;1,2,3,4],Variant:"reimu",BatchNumber:42}}}'
_legacy_worker = '@e[type=mcyukkuri:caretaker_brother,tag=legacy_factory,distance=..45,limit=1]'
_plant_smoke['commands'] = [
    (1, 'setblock ~24 ~ ~-23 minecraft:barrel'),
    (1, 'summon mcyukkuri:caretaker_brother ~23.5 ~ ~-23.5 {Tags:["legacy_factory"],HumanJob:2,SiteRole:"manju_worker",'
        'FactoryProduction:{Batches:4,Delay:200,Material:17,Breeding:{},Pipeline:{},Output:[{id:"minecraft:paper",count:8},'
        + _legacy_bag + ']}}'),
    (1, 'summon mcyukkuri:factory_tray ~21.5 ~ ~-23.5 {FactoryOwner:[I;1,2,3,4],Ticket:[I;4,3,2,1],'
        'Passengers:[{id:"mcyukkuri:reimu",Tags:["legacy_rider"],NeoForgeData:{HumanCommerce:{Owner:[I;1,2,3,4],OwnerKind:"npc",Use:"input"}}}]}'),
    (1, 'summon mcyukkuri:yukkuri_remains ~19.5 ~ ~-23.5 {Remains:{id:"mcyukkuri:yukkuri_remains",count:1,'
        'components:{"minecraft:custom_data":{FactoryOwner:[I;1,2,3,4],FactorySource:[I;4,3,2,1],Variant:"reimu",FoodRemaining:2.0f}}}}'),
    (60, f'execute if entity {_plant_site} '
         + ' '.join(f'if entity @e[type=mcyukkuri:caretaker_brother,distance=..45,nbt={{HumanJob:2,SiteRole:"plant_{role}"}}]' for role in _plant_posts)
         + ' run say MCY_PLANT_CLEAR_POSTS'),
    # The north-facing template starts one block below the entrance and ten blocks west of it.
    (60, 'execute ' + ' '.join(
        f'if block ~{_plant_x + x - 10} ~2 ~{_plant_z + z} mcyukkuri:factory_belt[facing=east,running=false]'
        for x in range(11, 17) for z in (20, 21))
        + f' if block ~{_plant_x + 8} ~2 ~{_plant_z + 19} mcyukkuri:manju_steamer[facing=west,lit=false,open=false]'
        + ' run say MCY_PLANT_BELT_AND_STEAMER'),
    (60, 'execute if data block ~24 ~ ~-23 Items[{id:"minecraft:paper",count:8}] if data block ~24 ~ ~-23 Items[' + _legacy_bag + '] run say MCY_LEGACY_OUTPUT_ACCEPTED'),
    (110, f'execute if entity {_legacy_worker} unless data entity {_legacy_worker} FactoryProduction run say MCY_LEGACY_SOURCE_CLEARED'),
]
_receiving_staff = '@e[type=mcyukkuri:caretaker_brother,distance=..45,limit=1,nbt={SiteRole:"plant_receive"}]'
_receive_click = 'execute positioned ~-7.5 ~2 ~-12.5 run yukkuri scenario basket plant_receive ' + _receiving_staff
_near_clerk = f'execute at {_receiving_staff} run yukkuri scenario basket plant_receive ' + _receiving_staff
# The clerk's post and the two waiting children keep their cells; every other booth cell is blocked. The children
# are returned to their spawn cells and frozen first, so the full-box state cannot depend on where a body drifted.
_booth_cells = [(x, z) for x in range(-9, -5) for z in range(-13, -10)]
_booth_kept = {(-7, -12), (-9, -11), (-8, -11)}  # receive_plea, receive_freeze, the receiver's post
_booth_obstructions = [f'execute positioned ~{x} ~2 ~{z} run setblock ~ ~ ~ minecraft:barrier'
                       for x, z in _booth_cells if (x, z) not in _booth_kept]
_plant_anchor_x, _plant_anchor_z = BY_ID['plant'].anchor
_plant_penned = [command for name, dx, dz in (('receive_plea', -7, -12), ('receive_freeze', -9, -11))
                 for command in (f'tp {sel(name)} {_plant_anchor_x + dx + .5} {FEET + 2}.0 {_plant_anchor_z + dz + .5} 180 0',
                                 f'data merge entity {sel(name)} {{NoAI:1b}}')]
# Carpet blocks the exact landing plane without suffocating the opening stock in those boxes.
_boxes_floor = 'fill ~-20 ~2 ~-3 ~-17 ~2 ~18 '
_receive_preserved = r'\[scenario\][^\n]* action=plant_receive_click result=\S+ token=(\S+) kept_token=true captive=true same_carrier=true carrier_count=1 occupants=1 paid=0\b'
_receive_paid = r'\[scenario\][^\n]* action=plant_receive_click result=CONSUME token=(\S+) kept_token=false captive=false same_carrier=true carrier_count=1 occupants=0 paid=(\d+)\b'
_plant_smoke['expect'] += [
    *[rf'\[factory\][^\n]* uuid={_uuid_text(_plant_receiving, name)} [^\n]* action=receive_reaction [^\n]* response={reaction}\b'
      for name, *_, reaction, _ in _receiving_animals],
    *[rf'\[factory\][^\n]* uuid={_uuid_text(_plant_receiving, name)} [^\n]* action=receive_live result=committed [^\n]* emeralds_paid={price}\b'
      for name, *_, price in _receiving_animals],
    *[rf'\[factory\][^\n]* action=receive_live result=refused [^\n]* reason={reason}\b'
      for reason in ('reach', 'no_space', 'one_animal', 'owned', 'protected')],
    r'\[scenario\][^\n]* action=plant_receive_click result=\S+ token=null kept_token=false captive=false same_carrier=true carrier_count=1 occupants=0 paid=0\b',
    rf'\[interaction\][^\n]* uuid={_uuid_text(_plant_receiving, "receive_freeze")} [^\n]* tool used; [^\n]* action=PIERCE ',
    *[rf'\[factory\][^\n]* uuid={_uuid_text(_plant_receiving, name)} [^\n]* action=receive_transfer result=placed '
      for name, *_ in _receiving_animals],
    r'MCY_PLANT_STOCK_PRESERVED', r'MCY_PLANT_HELD_BEFORE_RELOAD', r'MCY_PLANT_BOXES_OPEN',
]
_plant_smoke['forbid'] += [rf'\[factory\][^\n]* uuid={_uuid_text(_plant_receiving, "receive_pet")} [^\n]* action=receive_live result=committed']


def _verify_receiving(window):
    if problem := _verify_plant(window):
        return problem
    preserved = re.findall(_receive_preserved, window)
    paid = re.findall(_receive_paid, window)
    if len(preserved) != 4 or len(set(preserved)) != 3 or [int(value) for _, value in paid] != [2, 4, 4]:
        return f'Expected four refusals preserving their baskets and exact separate payments, got {preserved} and {paid}'
    if preserved[:2] != [paid[0][0]] * 2 or set(preserved[2:]) & {token for token, _ in paid}:
        return 'Interrupted or blocked receipt failed to preserve the successful token, or ownership refusal was paid'
    if len(re.findall(r'\[factory\][^\n]* action=receive_live result=committed ', window)) != 3:
        return 'Expected exactly three plant admissions'
    x, z = BY_ID['plant'].anchor
    booth = re.findall(r'\[factory\][^\n]* pos=(-?\d+), (\d+), (-?\d+) action=receive_(?:live result=committed|reaction) ', window)
    if len(booth) != 6 or any(not (x + _plant_x + 3 <= int(a) <= x + _plant_x + 6
                                  and int(b) == FEET + 2 and z + _plant_z + 7 <= int(c) <= z + _plant_z + 9)
                              for a, b, c in booth):
        return f'Expected all admissions and reactions on the booth floor, got {booth}'
    packed = re.findall(r'\[factory\][^\n]* t=(\d+)[^\n]* action=receive_transfer result=packed target=(\S+) token=(\S+)', window)
    placed = re.findall(r'\[factory\][^\n]* uuid=(\S+) t=(\d+)[^\n]* action=receive_transfer result=placed [^\n]* token=(\S+)', window)
    taken = re.findall(r'\[factory\][^\n]* action=receive_transfer result=taken [^\n]* token=(\S+)', window)
    if len(packed) != 4 or len(placed) != 3 or taken != [preserved[-1]] or packed[0][2] != taken[0]:
        return f'Expected a real basket taken in transit and three placements, got {packed}, {placed}, {taken}'
    if window.index('action=receive_transfer result=packed') < window.find('MCY_PLANT_BOXES_OPEN'):
        return 'The clerk loaded a basket before the blocked box floors reopened'
    trips = {token: (int(tick), animal) for tick, animal, token in packed}
    for animal, tick, token in placed:
        if token not in trips or trips[token][1] != animal or int(tick) - trips[token][0] < 30:
            return f'Placement {animal} did not follow a walk with the same staff basket token'


_plant_smoke['verify'] = _verify_receiving
_plant_smoke['ticks'] = 1800
_plant_smoke['reload'] = (260, _receiving_staff)
_plant_smoke['reload_paths'] = ('UUID', 'HandItems[0]')
_plant_smoke['readout'] += [f'data get entity {sel(name)} NeoForgeData.HumanCommerce' for name, *_ in _receiving_animals]
_plant_smoke['commands'] += [
    *[(45, command) for command in _plant_penned],
    (60, 'yukkuri scenario basket plain'),
    (60, f'effect clear {sel("receive_insult")} minecraft:weakness'),
    (61, f'yukkuri scenario basket capture {sel("receive_insult")}'),
    (62, f'effect give {sel("receive_insult")} minecraft:weakness 120'),
    (62, f'yukkuri scenario basket capture {sel("receive_insult")}'),
    (62, 'yukkuri scenario basket plant_receive ' + _receiving_staff),
    *[(62, block) for block in _booth_obstructions],
    (63, _receive_click),
    (64, 'fill ~-9 ~2 ~-13 ~-6 ~2 ~-11 minecraft:air replace minecraft:barrier'),
    *[(64, f'data merge entity {sel(name)} {{NoAI:0b}}') for name in ('receive_plea', 'receive_freeze')],
    (65, _receive_click),
    (66, _receive_click),
    (67, _boxes_floor + 'minecraft:magenta_carpet replace minecraft:air'),
    (70, f'yukkuri scenario basket capture {sel("receive_plea")}'),
    (71, _receive_click),
    (75, f'yukkuri scenario basket capture {sel("receive_freeze")}'),
    (76, _receive_click),
    # The test operator stands on the far side so the struck animal recoils toward the clerk's pickup area.
    (125, f'execute at {sel("receive_freeze")} positioned ~ ~ ~-0.5 run yukkuri scenario tool_click {sel("receive_freeze")} pierce'),
    (90, f'yukkuri scenario basket spray {sel("receive_pet")}'),
    (211, f'yukkuri scenario basket capture {sel("receive_pet")}'),
    (212, _receive_click),
    (213, 'yukkuri scenario basket release ~-5 ~1 ~-15'),
    (220, _boxes_floor + 'minecraft:air replace minecraft:magenta_carpet'),
    (220, 'say MCY_PLANT_BOXES_OPEN'),
    (253, f'execute if data entity {_receiving_staff} HandItems[0].components."minecraft:custom_data".BasketOccupants[0].Token run say MCY_PLANT_HELD_BEFORE_RELOAD'),
    # Field forceload and entity reload can finish after tick 700 on a busy server.
    (800, 'yukkuri scenario basket swap_hands'),
    (801, _near_clerk),
    (802, _near_clerk),
    (803, 'yukkuri scenario basket release ~-9 ~1 ~-13'),
    (804, _near_clerk),
    (805, 'yukkuri scenario basket swap_hands'),
    (2100, f'execute as {sel("receive_plea")} if data entity @s {OWNED}{{OwnerKind:"npc",Use:"plant_stock"}} '
          f'if data entity @s YukkuriFamily{{Mother:{uuid_nbt(_receive_mother)}}} '
          'if data entity @s YukkuriPet{Grade:1} if data entity @s YukkuriSkills{Points:{wait:5}} run say MCY_PLANT_STOCK_PRESERVED'),
]
_plant_smoke['expect'].append(
    rf'\[carrier\][^\n]* action=capture result=refused target={_uuid_text(_plant_receiving, "receive_insult")} [^\n]* weakness=0\.0 ')
_basket_moves = (
    ('basket_young', 0, 2, 4, 2), ('basket_baby_0', 2, 3, 4, 1), ('basket_baby_1', 3, 4, 4, 0),
    ('wild', 0, 4, 8, 3), ('basket_baby_2', 4, 5, 8, 2), ('basket_baby_3', 5, 6, 8, 1), ('basket_baby_4', 6, 7, 8, 0),
)
_smoke('transport', 320, [
    *[rf'\[carrier\][^\n]* action=capture result=committed target={_uuid_text("transport", name)} token=\S+ load={before}->{after} capacity={cap}\b'
      for name, before, after, cap, _ in _basket_moves],
    *[rf'\[carrier\][^\n]* action=release result=committed target={_uuid_text("transport", name)} token=\S+ remaining={remaining}\b'
      for name, _, _, _, remaining in _basket_moves],
    *[rf'\[interaction\][^\n]* uuid={_uuid_text("transport", name)} [^\n]* action=capture reason=carrier_full\b'
      for name in ('basket_baby_2',)],
    r'\[transport\][^\n]* action=release result=refused token=\S+ [^\n]* reason=(?:collision|occupied_ground)\b',
    r'\[carrier\][^\n]* action=upgrade_check occupants=0 matched=true result=mcyukkuri:yukkuri_carrier_large\b',
    r'\[carrier\][^\n]* action=upgrade_check occupants=4 matched=false result=minecraft:air\b',
], forbid=[])
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
      for i, x in enumerate((-10, -8, -6, -4))],
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
                            why='the ridge hides the forager at the moment of death')],
    'widow_repair': [dict(kind='death', match=r'cause=genericKill\b',
                          why='the scene button ends the original partner through the real death path')],
    'settle_pit_famine': [dict(kind='starve', who=r'pit slave ', why='an empty pit: the slave at nutrition 0 really starves')],
    'aftermath_growth': [_STEM_DRAW,
                         dict(kind='starve', who=_MD11_UNFED_MOTHER,
                              match=r'\bstarvation damage accepted=true\b|\baccepted damage cause=starve\b(?![^\n]*\bstem_depletion=true\b)',
                              why='unfed stem mother'),
                         dict(kind='death', who=_MD11_UNFED_MOTHER,
                              match=r'\bdeath settled into remains; cause=starve\b[^\n]* stem_depletion=false\b',
                              why='unfed stem mother')],
    'aftermath_depletion': [_STEM_DRAW, dict(kind='death', who=r'aftermath reserved ', match=r'stem_depletion=true\b', why='she dies of the stem')],
    'growth': [dict(kind='death', who=r'lifespan reached', match=r'internal=old_age\b', why='the fifth starts one tick before its lifespan')],
    'leaf_swaddle_neglect_stages': [dict(kind='death', match=r'internal=swaddle_retention\b',
                                         why='the end of the row starts in the lethal stage with little health: it dies of retention')],
    'sacrifice': [dict(kind='death', who=f'uuid={_uuid_text("sacrifice", "parent")} ', match=r'cause=(?:mob\b|generic internal=leak\b)',
                       why='the parent that draws the hunt may be killed by its strikes or bleed to death from the wounds it took shielding the child')],
    'hat_push': [dict(kind='drown', who=_THROWN, why='the fast boat capsizes the far hat and throws its rider in')],
    'hat_current': [dict(kind='drown', who=_CURRENT_HEAD, match=_CURRENT_IMMERSION + r'[^\n]* loss=1\.0\b',
                         why='after the grounded captain goes under, the shed head young may take one hit before stepping onto a dry tread')],
    'hat_grounded_capsized': [dict(kind='drown', who=_THROWN, why='falling water and a hard rock throw the crews in')],
    'shake_capsize': [dict(kind='drown', who=_THROWN, why='three shakes capsize the hat and throw every rider in')],
    'self_rescue': [dict(kind='drown', who=r'scenario afloat prepared [^\n]*owner_aboard=false', why='the owner is pushed off the bank')],
    'shore_capsized_far': [dict(kind='drown', who=_STEPS_IN, why='her hat is beyond the bank reach: she wades in and swims')],
    'worn_hat_boat': [dict(kind='drown', who=f'uuid={_uuid_text("worn_hat_boat", "c")} ', why='the young drops into the water')],
    'cargo': [dict(kind='drown', who=_THROWN, why='the shaken full hat capsizes and throws its owner in'),
              dict(kind='drown', who=_STEPS_IN, why='the empty hat\'s owner starts ashore and wades out to reclaim it')],
    'hat_sever_tearful': [dict(kind='drown', why='overloaded hat capsizes and throws the family in')],
    'hat_sever_cold': [dict(kind='drown', why='overloaded hat capsizes and throws the family in'),
                       dict(kind='death', who='uuid=(?:' + '|'.join(_uuid_text('hat_sever_cold', f'cold{n}') for n in (2, 3)) + ') ',
                            match=r'category=drowned\b',
                            why='the capsize throws the cold family in and she drops her largest; the water decides for her young')],
    'hat_sever_freeze': [dict(kind='drown', why='overloaded hat capsizes and throws the family in')],
    'hat_sever_sulk': [dict(kind='drown', why='overloaded hat capsizes and throws the family in'),
                       dict(kind='death', who='uuid=(?:' + '|'.join(_uuid_text('hat_sever_sulk', f'sulk{n}') for n in (2, 3)) + ') ',
                            match=r'category=drowned\b',
                            why='the capsize throws the cold family in and she drops her largest; the water decides for her young')],
    'hat_load_warning': [dict(kind='drown', why='overloaded hats capsize and throw the families in'),
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

_scene_end('den_mold_child_exile', [(n, _DRY) for n in ('mother', 'father', 'child', 'baby')],
           [('child', 'seen:settlement:mold exile protest'), ('child', 'seen:settlement:mold exile departed')])
_scene_end('den_mold_keep', [(n, _DRY) for n in ('mother', 'father', 'child', 'baby')],
           *[[(n, 'seen:body:mold caught from=')] for n in ('mother', 'father', 'baby')])

_scene_end(_sweet_box, [(name, _DRY) for name, *_ in _sweet_cast])
_scene_end('park_picnic', [('picnic_pet', _DRY), ('picnic_stray', _DRY)])
_scene_end(_park_aid, [('park_waiter', _DRY), ('park_local', _DRY)])

for _choice, _cast in (('choice_nest', ('nest_declare', 'nest_yield', 'nest_peer_west', 'nest_peer_east')),
                       ('choice_food', ('food_demand', 'food_ask')), ('choice_tool', ('tool_plead', 'tool_withdraw')),
                       ('choice_toilet', ('floor_dirty', 'toilet_use'))):
    _scene_end(_choice, [(name, _DRY) for name in _cast])
_scene_end('choice_snatch', [(name, _DRY) for name in ('food_snatch', 'food_polite', 'serving_food_snatch', 'serving_food_polite')],
           [('food_snatch', 'seen:badge:beg transfer.*result=snatched'),
            ('food_snatch', 'seen:meal:swallowed item=minecraft:apple')],
           [('food_polite', 'seen:badge:beg refusal.*response=LEAVE'),
            ('serving_food_polite', 'seen:meal:swallowed item=minecraft:apple')])

for _beg_scene in ('wild', 'wild_beg_refuse', 'wild_beg_snatch', 'wild_beg_help'):
    _scene_end(_beg_scene, [('pet', _DRY), ('wild', _DRY)])

_scene_end('food_behind_glass', [('glass_food_eater', _DRY)])
_scene_end('food_sill_window', [('sill_beggar', _DRY)])
_scene_end('stray_wind', [('wind_stray', _ASHORE), ('wind_plain', _ASHORE)],
           [('wind_stray', 'seen:habitat:gathered item=minecraft:paper count=1'),
            ('wind_stray', 'seen:habitat:paper bedding placed'),
            ('wind_stray', 'seen:habitat:paper cover draped source=bedding.*count=1'),
            ('wind_stray', 'seen:habitat:paper cover drop item=minecraft:paper count=1')])
_scene_end('stray_paper', [('bold_paper', _ASHORE), ('tired_paper', _ASHORE)],
           [('bold_paper', f'seen:habitat:gathered item=minecraft:paper count=1 source={_loose_paper_id} left=0'),
            ('bold_paper', 'seen:habitat:paper bedding placed')],
           [('tired_paper', 'seen:habitat:gathered item=minecraft:paper count=1.*left=1'),
            ('tired_paper', 'seen:habitat:paper bedding placed'),
            ('tired_paper', 'seen:habitat:gathered item=minecraft:paper count=1.*left=0'),
            ('tired_paper', 'seen:habitat:paper cover draped source=bedding.*count=2'),
            ('tired_paper', 'seen:sleep:begin source=life')])
_scene_end('stray_paper_young',
           [(name, _ASHORE) for name in ('devoted_parent', 'devoted_baby', 'self_parent', 'self_baby')],
           [('devoted_parent', 'seen:habitat:wind cover paper approach'),
            ('devoted_parent', 'seen:habitat:baby paper cover approach'),
            ('devoted_parent', 'seen:habitat:paper cover draped on child='),
            ('devoted_parent', 'seen:habitat:gathered item=minecraft:paper count=1'),
            ('devoted_parent', 'seen:habitat:paper cover draped source=bedding.*count=3'),
            ('devoted_parent', 'seen:habitat:paper cover drop item=minecraft:paper count=3'),
            ('devoted_parent', 'seen:temperature:fire rest')],
           [('devoted_baby', 'seen:habitat:paper cover drop item=minecraft:paper count=1'),
            ('devoted_baby', f'seen:temperature:fire rest .*shield_parent={_devoted_parent_txt}')],
           [('self_parent', 'seen:habitat:wind cover paper approach'),
            ('self_parent', 'seen:habitat:paper cover kept for self'),
            ('self_parent', 'seen:habitat:paper cover draped source=bedding.*count=3')])

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
_scene_end('den_move', [('mother', _DRY), ('father', _DRY), ('child', _DRY)],
           [('actors', 'seen:habitat:relocate begin.*reason=flooded'),
            ('actors', 'seen:habitat:relocate arrived'),
            ('actors', 'seen:home-claim:declared.*spoken=true')])
_scene_end('home_greedy', [('greedy', _DRY)],
           [('greedy', 'seen:home-claim:occupation chosen.*kind=bed'),
            ('greedy', 'seen:home-claim:declared.*spoken=true')])

_scene_end('den_family', [('mother', _DRY), ('child', _ASHORE), ('father', 'alive=false')],
           [('mother', 'seen:habitat:home chosen'), ('mother', 'seen:forage:begin role='),
            ('mother', 'seen:forage:deposit home=')])
_scene_end('forage', [('mother', _DRY), ('father', _DRY), ('child', _DRY)],
           [('father', 'seen:forage:begin role=FAMILY'),
            ('mother', f'seen:family:home watch started kin={_forage_father}'),
            ('father', 'seen:forage:deposit home=.* moved=[1-9]')],
           [('child', 'seen:family:welcome home started')],
           [('mother', f'seen:family:feed handed to meal child={_uuid_text("forage", "child")}'),
            ('child', f'seen:meal:swallowed item=.* feeder={_uuid_text("forage", "mother")}')])
_scene_end('family_absence', [('absent', 'alive=false'), ('stayer', _DRY), ('older', _DRY), ('baby', _DRY)],
           [('absent', 'seen:forage:begin role=FAMILY phase=OUT reason=already_out'),
            ('absent', 'seen:forage:phase=OUT -> GATHER'), ('absent', 'seen:body:death settled into remains'),
            ('stayer', f'seen:family:kin overdue kin={_absent_uuid}'), ('stayer', f'seen:family:missing kin call kin={_absent_uuid}'),
            ('stayer', f'seen:family:kin absence kin={_absent_uuid} phase=SEARCH destination=\\('),
            ('stayer', f'seen:family:missing kin found remains kin={_absent_uuid}'),
            ('stayer', r'seen:status:behavior.animation: .* -> bereaved'),
            ('stayer', 'seen:forage:begin role=FAMILY phase=OUT reason=sole_caregiver'),
            ('stayer', r'seen:forage:goal active phase=OUT .* pace=(?:1\.0[1-9]|1\.[1-4])'),
            ('stayer', 'seen:forage:deposit home=.* moved=[1-9]')],
           [('older', f'seen:family:kin overdue kin={_absent_uuid}'),
            ('older', f'seen:family:missing kin call kin={_absent_uuid}')],
           [('baby', f'seen:family:missing kin call kin={_absent_uuid}'),
            ('baby', f'seen:family:kin absence kin={_absent_uuid} phase=MOVED_ON')],
           [('stayer', f'seen:family:feed handed to meal child={_uuid_text("family_absence", "baby")}'),
            ('baby', f'seen:meal:swallowed item=.* feeder={_uuid_text("family_absence", "stayer")}')],
           forbid=[('baby', f'seen:family:kin absence kin={_absent_uuid} phase=SEARCH'),
                   ('stayer', 'seen:forage:begin role=FAMILY phase=OUT reason=shared_home')])
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
                               ('source_growth', _DRY), ('source_burden', _DRY)],
           [('affected_growth', 'seen:family:aftermath reserved'), ('affected_growth', 'seen:family:stem sprouted'),
            ('affected_growth', 'seen:family:stem ripe')],
           dict(steps=[('affected_burden', 'seen:family:aftermath reserved'), ('affected_burden', 'seen:family:stem sprouted'),
                       ('affected_burden', 'seen:family:stem ripe')],
                post=[('affected_burden', _DRY)]))
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
# The contract: every child ends delivered and calm at the nest. A gathered child is carried there and set down (a second trip for
# the second one is an ordinary retrieve and counts); a child the parent left behind walks there by itself, is fetched, or is declared
# dead. Within 40 ticks of the put-down the child is on the nest (rim or cushion), not OUTSIDE (a frightened baby may sit on the rim),
# and it stays at the nest for 80% of the ticks 100-300 after the put-down. The scene ends before the hungry predator rests up and
# hunts again (about 1900 ticks after its return), so that second sortie is not judged here; the end only needs both children
# alive, free and on the ground (nobody left in a mouth or brim).
# The bold Reimu carries both (a second trip counts); Marisa's 4.5 s brim lift may be cut short by the dive, so a child she could not
# take must walk to the nest by itself; the timid parent takes one and leaves the other.
for _flight_id, _carried, _cause, _nest in (
        (_flight_home, 2, 'release', '(?:INSIDE_SUPPORTED|PARTIAL)'),
        (_flight_hat, 1, '(?:brim_hop|release)', '(?:INSIDE_SUPPORTED|PARTIAL|ON_RIM)'),
        (_flight_timid, 1, 'release', '(?:INSIDE_SUPPORTED|PARTIAL)')):
    _scene_end(_flight_id, [(child, _ASHORE) for child in ('baby_a', 'baby_b')],
               *[dict(each=child, walks_home=_carried < 2, post_within=40,
                      hold=dict(after=300, span=200, share=.8, clauses=[('$subject', 'nest!=OUTSIDE')]), steps=[
                   ('mother', rf'seen:family:^carry pickup .*purpose=(?:PROTECT|RETRIEVE) child=$subject\b'),
                   ('mother', rf'seen:family:carry put down .*cause={_cause} purpose=(?:PROTECT|RETRIEVE) child=$subject .*nest={_nest}')],
                   post=[('$subject', _ASHORE + ' and nest!=OUTSIDE')])
                 for child in ('baby_a', 'baby_b')])
    next(s for s in S if s.id == _flight_id).smoke['end']['carried_min'] = _carried
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
_scene_end('hat_sever_tearful', [('normal', 'alive=true and distress=false'), ('normal2', 'alive=true'), ('normal3', 'alive=true'), ('normal_far', 'alive=true')],
           [('normal', 'seen:family:overload verdict .*verdict=DRIVE_TEARFUL'), ('normal', 'seen:family:overload beat start .*verdict=DRIVE_TEARFUL'),
            ('normal', 'seen:family:parent dismissed child=')])
_scene_end('hat_sever_cold', [('cold', 'alive=true and distress=false')],
           [('cold', 'seen:family:overload verdict .*verdict=DRIVE_COLD'), ('cold', 'seen:family:overload beat start .*verdict=DRIVE_COLD'),
            ('cold', 'seen:family:parent dismissed child=')])
_scene_end('hat_sever_freeze', [('freeze', 'alive=true and distress=false'), ('freeze2', 'alive=true'), ('freeze3', 'alive=true')],
           [('freeze', 'seen:family:overload verdict .*verdict=DRIVE_TEARFUL'), ('freeze', 'seen:family:overload beat start .*verdict=DRIVE_TEARFUL'),
            ('freeze', 'seen:family:parent dismissed child=')])
_scene_end('hat_sever_sulk', [('sulk', 'alive=true and distress=false')],
           [('sulk', 'seen:family:overload verdict .*verdict=DRIVE_COLD'), ('sulk', 'seen:family:overload beat start .*verdict=DRIVE_COLD'),
            ('sulk', 'seen:family:parent dismissed child=')])
_scene_end('hat_load_warning', [('early', _DRY), ('kind', 'alive=true and distress=false')],
           [('early', 'seen:aquatic:load heavy .*reason=unload_ashore'),
            ('early', 'seen:intent:end id=come_ashore result=DONE')],
           [('kind', 'seen:family:overload keeps children')])
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

