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

# Cold stiffening (docs/features/wild-ecology.md, 低温僵直). A body is started with a cold backlog just under the 2400 that freezes it
# (status needs.cold_exposure) on open snow at night, where nothing warms it: the exposure then climbs for real, the body shivers as
# ever, freezes where it stands, and from then on only the temperature layer runs. The scene writes the backlog, never the freeze.
_CS_ROW = re.compile(r'\[(?P<cat>[\w-]+)\] \S+ #\d+ uuid=(?P<uuid>[0-9a-f-]{36}) t=(?P<t>\d+) dim=\S+ '
                     r'pos=(?P<pos>-?\d+, -?\d+, -?\d+) (?P<msg>[^\n]*)')
# Lines a body that decides, moves or eats prints; a stiff body prints none of them.
_CS_ACTING = ('action', 'meal', 'duty', 'habitat', 'sleep', 'social', 'family', 'home', 'needs')


def _cs_still(log, uuid, ended=True):
    """From the stiff begin to its end (a thaw or the freeze death; with ``ended`` False the body may still be stiff when the scene ends)
    the body stays on one block, starts no action and eats nothing: only the temperature layer prints for it. A problem text, or None."""
    rows = [m for m in _CS_ROW.finditer(log) if m['uuid'] == uuid]
    first = next((i for i, m in enumerate(rows) if m['cat'] == 'temperature' and m['msg'].startswith('stiff begin ')), None)
    if first is None:
        return f'{uuid} never froze stiff'
    last = next((i for i, m in enumerate(rows) if i > first and m['cat'] == 'temperature'
                 and m['msg'].startswith(('stiff end ', 'freeze death '))), None)
    if ended and last is None:
        return f'{uuid} stayed stiff to the end of the scene'
    for m in rows[first + 1:last]:
        if int(m['t']) > int(rows[first]['t']) + 2 and m['pos'] != rows[first]['pos']:
            return f'{uuid} moved while stiff: {rows[first]["pos"]} -> {m["pos"]} ({m["msg"][:60]})'
        if m['cat'] in _CS_ACTING:
            return f'{uuid} acted while stiff: [{m["cat"]}] {m["msg"][:80]}'
    return None


_cs_alone = _uuid_text('cold_stiff_stray', 'stiff_alone')
_cs_warmed = _uuid_text('cold_stiff_stray', 'stiff_warmed')


def _cs_stray_verify(log):
    """The lone body freezes stiff, stays put and dies with its window; the one by the fire freezes first, then thaws only after the fire is lit."""
    for uuid in (_cs_alone, _cs_warmed):
        problem = _cs_still(log, uuid)
        if problem:
            return problem
    lit = log.find('MCYSTIFF_LIT')
    begin = re.search(rf'\[temperature\][^\n]* uuid={_cs_warmed} [^\n]* stiff begin ', log)
    end = re.search(rf'\[temperature\][^\n]* uuid={_cs_warmed} [^\n]* stiff end reason=warmed ', log)
    if lit < 0 or not begin or not end:
        return 'missing the fire-lit marker, or the warmed body never froze or never thawed'
    if not begin.start() < lit < end.start():
        return 'the body by the fire must freeze before the fire is lit and thaw only after it'
    return None


add('cold_stiff_stray', 'forage', '流浪：雪夜冻僵，无人管冻死，点火的解冻', '流浪：雪夜冻僵',
    '雪原冬夜，两只成年灵梦冷暴露已预设到快冻僵（2250／2400）。开场几秒后各自先发抖，随即在原地闭眼定住不动（冻僵）。'
    '西边那只孤零零的，一分钟左右窗口耗尽冻死，留下冻死的遗体；东边那只会在附近找暖，也在原地冻僵：走到它身边放一堆篝火，用打火石点燃，'
    '冻僵的灵梦在几十秒内解冻，继续发抖找暖。用望远镜看状态卡，写着“低温僵直，剩余约 N 秒”，点火后改为“回暖中”。'
    '冻僵时喂食、摸、开照护菜单都会被拒，提示“它冻僵了”。',
    '开场约 6 秒后冻僵，约 1 分钟内冻死或解冻', [
        'fillbiome ~-20 56 ~-18 ~20 71 ~19 minecraft:snowy_plains',
        street_stray('cold_stiff_stray', 'reimu', -8, 3, 'stiff_alone', nutrition=60, energy=70, rest_wait=0, yaw=180,
                     label='孤零零灵梦', lead=True),
        set_status('stiff_alone', 'needs.cold_exposure', 2250),
        street_stray('cold_stiff_stray', 'reimu', 8, 3, 'stiff_warmed', nutrition=60, energy=70, rest_wait=0, yaw=180,
                     label='火边灵梦'),
        set_status('stiff_warmed', 'needs.cold_exposure', 2250),
    ], give=['minecraft:campfire', 'minecraft:flint_and_steel', 'minecraft:spyglass'], env={'time': 'night'}, spot='street', vantage=(0, 9, 180, 15),
    note='待验证：新场景，尚未在游戏里实测。',
    reads=[('读孤零零灵梦', f'yukkuri status {sel("stiff_alone")} raw'), ('读火边灵梦', f'yukkuri status {sel("stiff_warmed")} raw')],
    smoke=dict(ticks=1900,
               commands=[(420, f'execute at {sel("stiff_warmed")} run setblock ~1 ~ ~ minecraft:campfire[lit=true]'),
                         (421, f'execute at {sel("stiff_warmed")} if block ~1 ~ ~ minecraft:campfire[lit=true] run say MCYSTIFF_LIT')],
               readout=[f'yukkuri status {sel("stiff_alone")} raw', f'yukkuri status {sel("stiff_warmed")} raw'],
               allow_fate=[dict(kind='death', who=f'uuid={_cs_alone} ', match=r'cause=freeze\b',
                                why='nothing warms the lone body: its stiff window runs out and the cold kills it')],
               forbid=[rf'\[temperature\][^\n]* uuid={_cs_warmed} [^\n]* freeze death', rf'\[body\][^\n]* uuid={_cs_warmed} [^\n]* death settled',
                       rf'\[temperature\][^\n]* uuid={_cs_alone} [^\n]* stiff end', r'\[body\][^\n]* invariant\b'],
               expect=[rf'\[temperature\][^\n]* uuid={_cs_alone} [^\n]* stiff begin ',
                       rf'\[choreo\][^\n]* uuid={_cs_alone} [^\n]* beat=stiff_enter face=sleep line=none declared=sleep',
                       rf'\[temperature\][^\n]* uuid={_cs_alone} [^\n]* freeze death reason=window_spent stiff=true ',
                       rf'\[body\][^\n]* uuid={_cs_alone} [^\n]* death settled into remains; cause=freeze [^\n]*category=frozen',
                       rf'\[temperature\][^\n]* uuid={_cs_warmed} [^\n]* stiff begin ',
                       rf'\[choreo\][^\n]* uuid={_cs_warmed} [^\n]* beat=stiff_enter face=sleep line=none declared=sleep',
                       rf'\[temperature\][^\n]* uuid={_cs_warmed} [^\n]* stiff end reason=warmed ',
                       r'MCYSTIFF_LIT'],
               verify=_cs_stray_verify))

# The reload check unloads the field and loads it back: a stiff body must keep its stiffness and its window across it, and still run
# its window out afterwards (a body whose stiffness the load cleared would walk off and never die of the cold).
_cs_reload = _uuid_text('cold_stiff_reload', 'stiff_reload')
add('cold_stiff_reload', 'forage', '流浪：冻僵后存档重载，仍僵着', '流浪：冻僵后重载',
    '雪原冬夜，一只成年灵梦冷暴露预设到快冻僵，开场几秒后冻僵定住。试玩时可存档退出再进：它仍僵在原地，状态卡剩余秒数接着走，'
    '窗口耗尽后冻死。冒烟会卸载本场地并重新加载，比对存下的僵直状态。',
    '开场约 6 秒后冻僵，之后一分钟左右冻死', [
        'fillbiome ~-20 56 ~-18 ~20 71 ~19 minecraft:snowy_plains',
        street_stray('cold_stiff_reload', 'reimu', 0, 3, 'stiff_reload', nutrition=60, energy=70, rest_wait=0, yaw=180,
                     label='冻僵灵梦', lead=True),
        set_status('stiff_reload', 'needs.cold_exposure', 2250),
    ], give=['minecraft:spyglass'], env={'time': 'night'}, spot='street', vantage=(0, 9, 180, 15),
    note='待验证：新场景，尚未在游戏里实测。',
    reads=[('读冻僵灵梦', f'yukkuri status {sel("stiff_reload")} raw')],
    smoke=dict(ticks=2400, reload=(400, sel('stiff_reload')), reload_paths=('YukkuriLife.Temperature.Stiff', 'YukkuriLife.Temperature.StiffMax', 'YukkuriLife.Temperature.ColdExposure'),
               readout=[f'yukkuri status {sel("stiff_reload")} raw'],
               allow_fate=[dict(kind='death', who=f'uuid={_cs_reload} ', match=r'cause=freeze\b',
                                why='the reloaded body keeps its window and the cold kills it when it runs out')],
               forbid=[r'\[body\][^\n]* invariant\b'],
               expect=[rf'\[temperature\][^\n]* uuid={_cs_reload} [^\n]* stiff begin ',
                       rf'\[temperature\][^\n]* uuid={_cs_reload} [^\n]* freeze death reason=window_spent stiff=true '],
               verify=lambda log: _cs_still(log, _cs_reload)))

# Negatives: a body by a lit fire is never frozen however much cold it carries; a stiff body stays on its block and starts nothing,
# and is still stiff when the scene ends. (The refusal of a player's hand, food and care menu sits on the interaction event, which no scenario command raises.)
_cs_fire = _uuid_text('cold_stiff_negative', 'stiff_fire')
_cs_hungry = _uuid_text('cold_stiff_negative', 'stiff_hungry')


def _cs_negative_verify(log):
    if re.search(rf'\[temperature\][^\n]* uuid={_cs_fire} [^\n]* stiff begin ', log):
        return 'the body beside the lit fire froze stiff'
    return _cs_still(log, _cs_hungry, ended=False)


add('cold_stiff_negative', 'forage', '流浪：暖处不冻僵，冻僵的不动不进食', '流浪：暖处与冻僵对照',
    '雪原冬夜，两只成年灵梦冷暴露都预设到快冻僵。东边那只身旁点着篝火，暖和，一直不会冻僵。'
    '西边那只在空地上冻僵，约 6 秒后冻僵，之后不动、不说话，也不进食。',
    '开场约 6 秒后冻僵，之后看 15 秒', [
        'fillbiome ~-20 56 ~-18 ~20 71 ~19 minecraft:snowy_plains',
        block(9, 0, 3, 'minecraft:campfire[lit=true]'),
        street_stray('cold_stiff_negative', 'reimu', 8, 3, 'stiff_fire', nutrition=60, energy=70, rest_wait=0, yaw=180,
                     label='火边灵梦'),
        set_status('stiff_fire', 'needs.cold_exposure', 2300),
        street_stray('cold_stiff_negative', 'reimu', -8, 3, 'stiff_hungry', nutrition=45, energy=70, rest_wait=0, yaw=180,
                     label='冻僵灵梦', lead=True),
        set_status('stiff_hungry', 'needs.cold_exposure', 2250),
    ], give=['minecraft:spyglass'], env={'time': 'night'}, spot='street', vantage=(0, 9, 180, 15),
    note='待验证：新场景，尚未在游戏里实测。',
    reads=[('读火边灵梦', f'yukkuri status {sel("stiff_fire")} raw'), ('读冻僵灵梦', f'yukkuri status {sel("stiff_hungry")} raw')],
    smoke=dict(ticks=900,
               readout=[f'yukkuri status {sel("stiff_fire")} raw', f'yukkuri status {sel("stiff_hungry")} raw'],
               forbid=[rf'\[temperature\][^\n]* uuid={_cs_fire} [^\n]* stiff begin ', rf'\[meal\][^\n]* uuid={_cs_hungry} ',
                       rf'\[temperature\][^\n]* uuid={_cs_hungry} [^\n]* (?:stiff end|freeze death)',
                       r'\[body\][^\n]* invariant\b'],
               expect=[rf'\[temperature\][^\n]* uuid={_cs_hungry} [^\n]* stiff begin '],
               verify=_cs_negative_verify))


# ---- Cold stiffening P3: a body frozen stiff is lifted in the basket or chiselled to pieces (docs/features/wild-ecology.md, 低温僵直). ----
# Every body is started with a cold backlog just under the 2400 that freezes it (as in cold_stiff_stray); the player's part is a real
# swing (`scenario chisel` raises the AttackEntityEvent through Player#attack) or the real basket click, once the bodies are stiff.
# The same 41 x 16 x 38 field as the first three scenes: fillbiome refuses more than 32768 blocks and the scene would stay temperate.
_CS_FIELD = 'fillbiome ~-20 56 ~-18 ~20 71 ~19 minecraft:snowy_plains'
_CS_HITS = 'yukkuri scenario chisel'


def _cs_swings(name, tool, first, count, gap):
    return [(first + i * gap, f'{_CS_HITS} {sel(name)} {tool}') for i in range(count)]


def _cs_shattered(log, uuid):
    """The shell broke: a shatter line, a death without remains, and not one accepted blow on the body (the swings did no damage)."""
    for pattern, what in ((rf'\[temperature\][^\n]* uuid={uuid} [^\n]* chisel shatter ', 'a chisel shatter line'),
                          (rf'\[body\][^\n]* uuid={uuid} [^\n]* death settled without remains; cause=player ', 'a death without remains by the player')):
        if not re.search(pattern, log):
            return f'{uuid}: missing {what}'
    if re.search(rf'\[body\][^\n]* uuid={uuid} [^\n]* death settled into remains', log):
        return f'{uuid}: left remains'
    if re.search(rf'\[body\][^\n]* uuid={uuid} [^\n]* accepted damage cause=player', log):
        return f'{uuid}: a swing did health damage'
    return None


_cm_pick = _uuid_text('cold_stiff_mined', 'mine_pick')
_cm_hand = _uuid_text('cold_stiff_mined', 'mine_hand')
_cm_sword = _uuid_text('cold_stiff_mined', 'mine_sword')
_cm_junk = _uuid_text('cold_stiff_mined', 'mine_junk')


def _cs_mined_verify(log):
    for uuid in (_cm_pick, _cm_hand, _cm_junk):
        problem = _cs_shattered(log, uuid)
        if problem:
            return problem
    # Swing counts are read from the hits themselves: the pickaxe never breaks a shell in fewer than three, a bare hand needs nine.
    for uuid, swings in ((_cm_pick, 3), (_cm_junk, 3), (_cm_hand, 9)):
        hits = re.findall(rf'\[temperature\][^\n]* uuid={uuid} [^\n]* chisel hit ', log)
        if len(hits) != swings:
            return f'{uuid}: {len(hits)} chisel swings, expected {swings}'
    # The sword body is cracked, then mends with no further swing, and is still stiff when the scene ends.
    if not re.search(rf'\[temperature\][^\n]* uuid={_cm_sword} [^\n]* chisel hit [^\n]*crack=1 ', log):
        return 'the sword body never showed a first crack'
    if not re.search(rf'\[temperature\][^\n]* uuid={_cm_sword} [^\n]* chisel mended progress=[^\n]* crack=0\b', log):
        return 'the idle cracks of the sword body never mended'
    if re.search(rf'\[(?:temperature|body)\][^\n]* uuid={_cm_sword} [^\n]* (?:chisel shatter|death settled)', log):
        return 'the sword body died'
    return _cs_still(log, _cm_sword, ended=False)


add('cold_stiff_mined', 'forage', '流浪：冻僵的被镐、拳、剑凿，镐三下碎，空手九下碎', '流浪：凿冻僵的',
    '雪原冬夜，四只成年灵梦冷暴露预设到快冻僵，开场约 6 秒后冻僵。之后各有一位玩家挥击：左一只挨三下铁镐，碎裂；左二只空手挥九下，'
    '每满四分之一多一级裂纹（冰屑与玻璃声），碎裂；右二只只挨两下铁剑，一级裂纹，停手一百刻后裂纹退回去，仍僵着；'
    '右一只挨三下铁镐，它失去了头饰、头上戴着垃圾头饰，碎裂时垃圾头饰作为真实掉落物留在原处。碎裂的都直接死，没有遗体，挥击不扣血。'
    '亲手试：拿镐、剑、空手左键打冻僵的灵梦，用望远镜看状态卡（无裂纹读数），创造模式一下碎。',
    '开场约 6 秒冻僵，凿击在约 6 秒后，之后看 15 秒', [
        _CS_FIELD,
        street_stray('cold_stiff_mined', 'reimu', -12, 3, 'mine_pick', nutrition=60, energy=70, rest_wait=0, yaw=180, label='挨镐灵梦', lead=True),
        set_status('mine_pick', 'needs.cold_exposure', 2250),
        street_stray('cold_stiff_mined', 'reimu', -6, 3, 'mine_hand', nutrition=60, energy=70, rest_wait=0, yaw=180, label='挨拳灵梦'),
        set_status('mine_hand', 'needs.cold_exposure', 2250),
        street_stray('cold_stiff_mined', 'reimu', 0, 3, 'mine_sword', nutrition=60, energy=70, rest_wait=0, yaw=180, label='挨剑灵梦'),
        set_status('mine_sword', 'needs.cold_exposure', 2250),
        street_stray('cold_stiff_mined', 'reimu', 6, 3, 'mine_junk', nutrition=60, energy=70, rest_wait=0, yaw=180, accessory=False,
                     label='戴垃圾头饰灵梦'),
        f'data merge entity {sel("mine_junk")} {{YukkuriLife:{{HatCustody:{{State:"LOST",JunkHeadwear:"paper_box"}}}}}}',
        set_status('mine_junk', 'needs.cold_exposure', 2250),
    ], give=['minecraft:spyglass', 'minecraft:iron_pickaxe', 'minecraft:iron_sword'], env={'time': 'night'}, spot='street', vantage=(0, 9, 180, 15),
    note='待验证：新场景，尚未在游戏里实测。',
    reads=[(f'读{label}', f'yukkuri status {sel(name)} raw') for name, label in
           (('mine_pick', '挨镐灵梦'), ('mine_hand', '挨拳灵梦'), ('mine_sword', '挨剑灵梦'), ('mine_junk', '戴垃圾头饰灵梦'))],
    smoke=dict(ticks=900,
               commands=[*_cs_swings('mine_pick', 'iron_pickaxe', 330, 3, 15), *_cs_swings('mine_junk', 'iron_pickaxe', 340, 3, 15),
                         *_cs_swings('mine_hand', 'hand', 330, 9, 12), *_cs_swings('mine_sword', 'iron_sword', 330, 2, 15)],
               readout=[f'yukkuri status {sel("mine_sword")} raw'],
               allow_fate=[dict(kind='death', who=f'uuid={uuid} ', match=r'cause=player\b',
                                why='the player chiselled the stiff body to pieces: the real result of the scene')
                           for uuid in (_cm_pick, _cm_hand, _cm_junk)],
               forbid=[r'\[body\][^\n]* invariant\b', rf'\[temperature\][^\n]* uuid={_cm_sword} [^\n]* freeze death',
                       r'\[scenario\][^\n]* action=chisel [^\n]*stiff=false'],
               expect=[*(rf'\[temperature\][^\n]* uuid={uuid} [^\n]* stiff begin ' for uuid in (_cm_pick, _cm_hand, _cm_sword, _cm_junk)),
                       rf'\[choreo\][^\n]* uuid={_cm_hand} [^\n]* beat=chisel_crack_1 face=sleep line=none declared=sleep',
                       rf'\[choreo\][^\n]* uuid={_cm_hand} [^\n]* beat=chisel_crack_2 face=sleep line=none declared=sleep',
                       rf'\[choreo\][^\n]* uuid={_cm_hand} [^\n]* beat=chisel_crack_3 face=sleep line=none declared=sleep',
                       rf'\[choreo\][^\n]* uuid={_cm_pick} [^\n]* beat=chisel_shatter face=sleep line=none declared=sleep',
                       rf'\[headwear\][^\n]* uuid={_cm_junk} [^\n]* junk death transfer item=\S*junk_headwear destination=world'],
               verify=_cs_mined_verify))

# Kin witness: the mate beside a fire sees the break and grieves; the mate far away does not (the scene reads that, not a wait for its search to end).
_cw_seen = _uuid_text('cold_stiff_witness', 'witness_body')
_cw_mate = _uuid_text('cold_stiff_witness', 'witness_mate')
_cw_far = _uuid_text('cold_stiff_witness', 'far_body')
_cw_far_mate = _uuid_text('cold_stiff_witness', 'far_mate')
_cw_seen_id, _cw_mate_id = uid('cold_stiff_witness', 'witness_body'), uid('cold_stiff_witness', 'witness_mate')
_cw_far_id, _cw_far_mate_id = uid('cold_stiff_witness', 'far_body'), uid('cold_stiff_witness', 'far_mate')


def _cs_witness_verify(log):
    for uuid in (_cw_seen, _cw_far):
        problem = _cs_shattered(log, uuid)
        if problem:
            return problem
    if not re.search(rf'\[family\][^\n]* uuid={_cw_mate} [^\n]* witnessed kin death dead={_cw_seen} ', log):
        return 'the mate beside the fire did not witness the break'
    if re.search(rf'\[family\][^\n]* uuid={_cw_far_mate} [^\n]* witnessed kin death', log):
        return 'the mate out of sight grieved at a death it did not see'
    for uuid in (_cw_mate, _cw_far_mate):
        if re.search(rf'\[temperature\][^\n]* uuid={uuid} [^\n]* stiff begin ', log):
            return f'{uuid}: the warm mate froze stiff'
    return None


add('cold_stiff_witness', 'forage', '流浪：伴侣眼前冻僵的被凿碎，伴侣悲痛；远处的不知道', '流浪：凿碎时伴侣在场与不在场',
    '雪原冬夜。西边一只冷暴露预设到快冻僵的灵梦，旁边六格外的篝火边是它的伴侣魔理沙；东边另一只同样的灵梦，伴侣在十五格外自己的篝火边。'
    '开场约 6 秒后两只灵梦冻僵，玩家挥三下铁镐把它们都凿碎：西边的伴侣亲眼看到，悲痛并对玩家有戒心；东边的伴侣没看见，不会悲痛。',
    '开场约 6 秒冻僵，凿击在约 6 秒后，之后看 20 秒', [
        _CS_FIELD,
        block(-2, 0, 3, 'minecraft:campfire[lit=true]'),
        block(14, 0, -13, 'minecraft:campfire[lit=true]'),
        yk('reimu', -8, 3, 'witness_body', uuid=_cw_seen_id, yaw=180, nutrition=60, energy=70, rest_wait=0, lead=True, label='西边冻僵灵梦',
           kin=f'Partner:{uuid_nbt(_cw_mate_id)}'),
        # Just under the line: the body near a fire would otherwise walk toward it and freeze late, after the swings (it must be stiff first).
        set_status('witness_body', 'needs.cold_exposure', 2390),
        yk('marisa', -3, 3, 'witness_mate', uuid=_cw_mate_id, yaw=90, nutrition=80, energy=100, rest_wait=2400, label='西边伴侣',
           kin=f'Partner:{uuid_nbt(_cw_seen_id)}'),
        yk('reimu', 14, 3, 'far_body', uuid=_cw_far_id, yaw=180, nutrition=60, energy=70, rest_wait=0, label='东边冻僵灵梦',
           kin=f'Partner:{uuid_nbt(_cw_far_mate_id)}'),
        set_status('far_body', 'needs.cold_exposure', 2390),
        yk('marisa', 14, -12, 'far_mate', uuid=_cw_far_mate_id, yaw=0, nutrition=80, energy=100, rest_wait=2400, label='东边伴侣',
           kin=f'Partner:{uuid_nbt(_cw_far_id)}'),
    ], give=['minecraft:spyglass', 'minecraft:iron_pickaxe'], env={'time': 'night'}, spot='street', vantage=(0, 9, 180, 15),
    note='待验证：新场景，尚未在游戏里实测。',
    reads=[(f'读{label}', f'yukkuri status {sel(name)} raw') for name, label in
           (('witness_body', '西边冻僵灵梦'), ('witness_mate', '西边伴侣'), ('far_body', '东边冻僵灵梦'), ('far_mate', '东边伴侣'))],
    smoke=dict(ticks=900,
               commands=[*_cs_swings('witness_body', 'iron_pickaxe', 330, 3, 15), *_cs_swings('far_body', 'iron_pickaxe', 335, 3, 15)],
               readout=[f'yukkuri status {sel("witness_mate")} raw', f'yukkuri status {sel("far_mate")} raw'],
               allow_fate=[dict(kind='death', who=f'uuid={uuid} ', match=r'cause=player\b',
                                why='the player chiselled the stiff body to pieces: the real result of the scene')
                           for uuid in (_cw_seen, _cw_far)],
               forbid=[r'\[body\][^\n]* invariant\b', r'\[scenario\][^\n]* action=chisel [^\n]*stiff=false',
                       rf'\[emotion\][^\n]* uuid={_cw_far_mate} [^\n]* course begun kind=grief'],
               expect=[rf'\[temperature\][^\n]* uuid={_cw_seen} [^\n]* stiff begin ', rf'\[temperature\][^\n]* uuid={_cw_far} [^\n]* stiff begin ',
                       rf'\[family\][^\n]* uuid={_cw_mate} [^\n]* witnessed kin death dead={_cw_seen} ',
                       rf'\[threat\][^\n]* uuid={_cw_mate} [^\n]* response started source=',
                       rf'\[emotion\][^\n]* uuid={_cw_mate} [^\n]* course begun kind=grief [^\n]*about={_cw_seen}'],
               verify=_cs_witness_verify))

# The basket lifts a stiff body like one out cold and sets it down beside a lit fire, where it thaws; the same body is first refused a flame
# to be set down in. A stiff body left 6 blocks from the same fire (outside the 4-block reach of its warmth) stays stiff.
_cf_lift = _uuid_text('cold_stiff_rewarm_fire', 'lift_body')
_cf_left = _uuid_text('cold_stiff_rewarm_fire', 'left_body')


def _cs_rewarm_verify(log):
    begin = re.search(rf'\[temperature\][^\n]* uuid={_cf_lift} [^\n]* stiff begin ', log)
    lift = re.search(rf'\[carrier\][^\n]* action=capture result=committed target={_cf_lift} [^\n]*admission=UNCONSCIOUS stiff=true', log)
    refused = re.search(r'\[transport\][^\n]* action=release result=refused [^\n]*reason=fire', log)
    released = re.search(rf'\[transport\][^\n]* uuid={_cf_lift} [^\n]* action=release result=committed ', log)
    thawed = re.search(rf'\[temperature\][^\n]* uuid={_cf_lift} [^\n]* stiff end reason=warmed ', log)
    if not (begin and lift and refused and released and thawed):
        return f'missing a step: begin={bool(begin)} lift={bool(lift)} fire refusal={bool(refused)} release={bool(released)} thaw={bool(thawed)}'
    if not begin.start() < lift.start() < refused.start() < released.start() < thawed.start():
        return 'the steps are out of order (freeze, lift, refused flame, release, thaw)'
    if re.search(rf'\[(?:choreo|speech)\][^\n]* uuid={_cf_lift} [^\n]*(?:basket_out|basket_exit|basket_enter|request_thanks)', log):
        return 'the lifted stiff body performed a basket reaction'
    return _cs_still(log, _cf_left, ended=False)


add('cold_stiff_rewarm_fire', 'forage', '流浪：篮子端起冻僵的放到篝火边解冻，不放进火里，留在冷处的仍僵', '流浪：端到火边解冻',
    '雪原冬夜，两只成年灵梦冷暴露预设到快冻僵，开场约 6 秒后冻僵。篝火点在北边，西边一只离火很远；玩家用篮子端起西边那只'
    '（它不跳进篮子、不笑、不道谢），先试着放进西南角的一格火焰里（被拒绝，仍在篮里），再放在篝火边：它仍僵着，几十秒后壳化开解冻，继续发抖找暖。'
    '东边那只离火八九格，留在原处一直僵着。',
    '开场约 6 秒冻僵，端起放下在约 6 秒后，之后看半分钟', [
        _CS_FIELD,
        block(0, 0, 8, 'minecraft:campfire[lit=true]'),
        block(-16, -1, 12, 'minecraft:netherrack'),
        block(-16, 0, 12, 'minecraft:fire'),
        street_stray('cold_stiff_rewarm_fire', 'reimu', -10, 3, 'lift_body', nutrition=60, energy=70, rest_wait=0, yaw=180, label='被端起的灵梦', lead=True),
        set_status('lift_body', 'needs.cold_exposure', 2250),
        street_stray('cold_stiff_rewarm_fire', 'reimu', 6, 14, 'left_body', nutrition=60, energy=70, rest_wait=0, yaw=180, label='留在冷处的灵梦'),
        set_status('left_body', 'needs.cold_exposure', 2250),
    ], give=['minecraft:spyglass', 'mcyukkuri:yukkuri_carrier'], env={'time': 'night'}, spot='street', vantage=(0, 9, 180, 15),
    note='待验证：新场景，尚未在游戏里实测。',
    reads=[('读被端起的灵梦', f'yukkuri status {sel("lift_body")} raw'), ('读留在冷处的灵梦', f'yukkuri status {sel("left_body")} raw')],
    smoke=dict(ticks=1300,
               commands=[(300, 'yukkuri scenario basket plain'), (330, f'yukkuri scenario basket capture {sel("lift_body")}'),
                         (345, 'yukkuri scenario basket release ~-16 ~-1 ~12'), (360, 'yukkuri scenario basket release ~1 ~-1 ~8')],
               readout=[f'yukkuri status {sel("lift_body")} raw', f'yukkuri status {sel("left_body")} raw'],
               forbid=[r'\[body\][^\n]* invariant\b', rf'\[temperature\][^\n]* uuid={_cf_left} [^\n]* (?:stiff end|freeze death)',
                       rf'\[temperature\][^\n]* uuid={_cf_lift} [^\n]* freeze death'],
               expect=[rf'\[temperature\][^\n]* uuid={_cf_lift} [^\n]* stiff begin ', rf'\[temperature\][^\n]* uuid={_cf_left} [^\n]* stiff begin ',
                       rf'\[temperature\][^\n]* uuid={_cf_lift} [^\n]* stiff end reason=warmed ',
                       rf'\[choreo\][^\n]* uuid={_cf_lift} [^\n]* beat=stiff_lift face=sleep line=none declared=sleep'],
               verify=_cs_rewarm_verify))
