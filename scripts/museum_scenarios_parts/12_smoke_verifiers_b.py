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
    off = _bench_sit_check(window)
    if off:
        return off
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
_NUM = r'(-?\d+(?:\.\d+)?)'
_POINT = rf'\({_NUM},{_NUM},{_NUM}\)'


def _bench_sit_check(window):
    """Every sit starts on the ground in front of the seat (the entity is never on the board before it sits), takes the seat half a block
    up and about a block back, and stands up again at the spot it sat from; the drawn legs rest on the board and reach past its edge."""
    sits = re.findall(r'action=outing_sit seat=-?\d+, -?\d+, -?\d+ facing=\w+ candidates=\d+ board_top=' + _NUM + ' hip_y=' + _NUM
                      + ' thigh_gap=' + _NUM + ' foot_past_edge=' + _NUM + ' front_gap=' + _NUM, window)
    if not sits:
        return 'no sit began from the spot in front of a bench'
    for board, _, gap, past, front in sits:
        if abs(float(board) - .5) > .01 or float(gap) < 0 or float(gap) > .05 or float(past) <= 0 or float(front) > .5:
            return f'sit geometry off: board_top={board} thigh_gap={gap} foot_past_edge={past} front_gap={front}'
    settles = re.findall(rf'action=seat_settle phase=(sit|rise) from={_POINT} to={_POINT} ticks=(\d+)', window)
    sat = [m for m in settles if m[0] == 'sit']
    if not sat:
        return 'the owner never took the seat (no sit settle)'
    for phase, fx, fy, fz, tx, ty, tz, ticks in settles:
        rise = float(ty) - float(fy)
        back = ((float(tx) - float(fx)) ** 2 + (float(tz) - float(fz)) ** 2) ** .5
        if abs(rise - (.5 if phase == 'sit' else -.5)) > .05 or not .5 <= back <= 1.6:
            return f'{phase} settle moved {rise:+.2f} up and {back:.2f} across; expected half a block and about one across'
    return None


def _park_play_pet_fates(window):
    """Both pets choose facilities by stat, play >=2 sessions/facilities, sustain real bounces, match declared faces, and leave after wants end."""
    at_stand = re.search(r'action=outing_park_wait stand=(\([^\n]+\))', window)
    left = re.search(r'action=outing_park_leave stand=(\([^\n]+\)) gap=([\d.]+)', window)
    turn = re.search(r'action=outing_turn reason=(park_done|park_time)\b', window)
    if not at_stand or not left or at_stand[1] != left[1] or float(left[2]) >= .5:
        return 'the keeper did not finish waiting at her park stand'
    off = _bench_sit_check(window)
    if off:
        return off
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
    off = _bench_sit_check(window)
    if off:
        return off
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
