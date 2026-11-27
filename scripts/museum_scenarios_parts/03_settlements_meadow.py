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
# it has finished. The other pile is one apple between two strays: a stack no larger than the eaters is contested, and the two
# (both fed enough to stand at it) stage a food contest over it. West: a timid, dull stray (long inspect pause) and the hungrier
# of the two, close enough to the apple to begin its look at once. East: a bold, clever one (short pause) two blocks farther; 27 above the timid one (a body at 75 or more is too fed to go for loose
# food at all), but far bolder, so it wins and the timid one gives way. A hedge row stands
# between the piles: a hungry stray (12-49, and the starving one after its first apple) that sees a peer chewing starts a
# BEG_BITE arc instead of taking its own turn, so neither group sees the other eat.
_STRANGE = {name: uid('stray_strange_food', name) for name in ('timid', 'bold', 'starving')}
add('stray_strange_food', 'forage', '流浪：陌生的食物，胆小的先看再吃，饿极的直接吃', '流浪：陌生食物前的迟疑',
    '街中间地上一个苹果，胆小的灵梦和胆大的魔理沙各站一侧；南边隔得远的另一小堆两个苹果旁是饿极的灵梦。'
    '西边胆小、迟钝的灵梦站在苹果前先盯着看一会儿（约 3 秒）；东边胆大、聪明的魔理沙只看一眼（不到半秒），一个苹果两只抢，'
    '两只在苹果旁对峙争抢：魔理沙胆大得多，灵梦很快让开，魔理沙吃掉苹果。南边饿极（营养 5，低于 30）的灵梦不看直接吃，一只接一只吃掉自己的两个苹果。'
    '约 10 秒内发生；一次没看到可在 M 菜单重开',
    '约 10–40 秒', [
        item('minecraft:apple', 1, 0, 0, name='strange_apple'),
        # The timid one stands on the apple's west contact stand (mouth in reach, facing east), so its look starts without a walk
        # that would cross the bold one's path (a pedestrian encounter would break it); the bold one starts far enough east that
        # its walk leaves the look on screen for about three seconds before the contest takes over the apple.
        street_stray('stray_strange_food', 'reimu', -.326, 0, 'timid', nutrition=45, boldness=10, intelligence=10, label='胆小·迟钝', yaw=-90, lead=True),
        street_stray('stray_strange_food', 'marisa', 4.4, 0, 'bold', nutrition=72, boldness=95, intelligence=95, label='胆大·聪明', yaw=90),
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
