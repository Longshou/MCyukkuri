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
    if not any(row[1] == 'threat_retreat' and row[2] == 'frail_walk' for row in weak):
        return 'The frail body did not retreat on the frail-walk gait'


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
