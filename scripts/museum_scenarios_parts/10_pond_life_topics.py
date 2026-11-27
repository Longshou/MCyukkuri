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
    """Verify the full PD-09 thunder arc: brim play -> perceived lightning clap -> boldness-scaled hide beats (shown==declared)
    -> real cabin/head shelter -> storm passes -> bold calms before timid -> timid returns to brim play."""
    for request in re.finditer(r'uuid=(\S+)[^\n]* play stays on brim [^\n]*cabin=none head=free reason=fear:thunder', window):
        child = request[1]
        after = window[request.end():]
        if not re.search(rf'carry lowering [^\n]*child={child}[^\n]*clip=braid_head_set'
                         rf'[\s\S]*uuid={child}[^\n]* seat landed [^\n]*place=HEAD cause=braid_set_down'
                         rf'[\s\S]*uuid={child}[^\n]* play kind=kid_hide_behind start spot=head reason=fear:thunder', after):
            return f'frightened brim child {child} was not lifted to the free head and sheltered there'
    for shown, declared in re.findall(r'\[choreo\][^\n]* beat=thunder_(?:hide|calm) face=(\S+) line=\S+ declared=(\S+)', window):
        if shown != declared:
            return f'thunder beat face mismatch: shown={shown} declared={declared}'
    c1, c2 = _uuid_text('kid_boat_play', 'c1'), _uuid_text('kid_boat_play', 'c2')
    def find_tick(pattern, text=window):
        m = re.search(pattern, text)
        return (int(m[1]), m) if m else (None, None)
    brim_before, m_brim = find_tick(rf'\[aquatic\][^\n]*uuid={c1} t=(\d+)[^\n]* play kind=(?:kid_brim_splash|kid_mimic_paddle) start spot=brim\b')
    if brim_before is None:
        return 'c1 never played on the brim before thunder'
    c1_heard, m_c1_heard = find_tick(rf'\[weather\][^\n]*uuid={c1} t=(\d+)[^\n]* thunder clap heard distance=\d+ via=(?:HEARD|SAW)\b')
    c2_heard, m_c2_heard = find_tick(rf'\[weather\][^\n]*uuid={c2} t=(\d+)[^\n]* thunder clap heard distance=\d+ via=(?:HEARD|SAW)\b')
    if c1_heard is None or c2_heard is None:
        return f'thunder clap not perceived by both kids (c1={c1_heard} c2={c2_heard})'
    if c1_heard < brim_before:
        return f'thunder clap ({c1_heard}) fired before c1 brim play ({brim_before})'
    c1_hide, _ = find_tick(rf'\[choreo\][^\n]*uuid={c1} t=(\d+)[^\n]* beat=thunder_hide face=horrified line=afraid declared=horrified\b', window[m_c1_heard.end():])
    c2_hide, _ = find_tick(rf'\[choreo\][^\n]*uuid={c2} t=(\d+)[^\n]* beat=thunder_hide face=surprise line=thunder declared=surprise\b', window[m_c2_heard.end():])
    if c1_hide is None or c2_hide is None:
        return f'missing thunder_hide beat with exact face/line (c1={c1_hide} c2={c2_hide})'
    c1_calm, m_c1_calm = find_tick(rf'\[choreo\][^\n]*uuid={c1} t=(\d+)[^\n]* beat=thunder_calm face=closed_smile line=none declared=closed_smile\b', window[m_c1_heard.end():])
    c2_calm, _ = find_tick(rf'\[choreo\][^\n]*uuid={c2} t=(\d+)[^\n]* beat=thunder_calm face=happy line=none declared=happy\b', window[m_c2_heard.end():])
    if c1_calm is None or c2_calm is None:
        return f'missing thunder_calm beat after storm cleared (c1={c1_calm} c2={c2_calm})'
    if not (c2_calm < c1_calm):
        return f'bold c2 did not calm before timid c1 (c2={c2_calm} c1={c1_calm})'
    after_calm = window[m_c1_heard.end() + m_c1_calm.end():]
    lifted_to_head = re.search(rf'\[aquatic\][^\n]* care transfer end kind=shelter_young child={c1} result=DONE released=true ground=false head=true\b', window[m_c1_heard.end():])
    if lifted_to_head:
        if not re.search(rf'\[aquatic\][^\n]*uuid={c1}[^\n]* play kind=hat_cradle start spot=head reason=head_dry\b', after_calm):
            return 'c1 sheltered on head did not resume head play after the storm passed'
    elif not re.search(rf'\[aquatic\][^\n]*uuid={c1}[^\n]* play kind=(?:kid_brim_splash|kid_mimic_paddle) start spot=brim\b', after_calm):
        return 'c1 did not return to brim play after the storm passed'


pond('kid_boat_play', '赤油在帽檐玩耍，打雷躲避', '赤油在帽檐玩耍，打雷躲避',
     '静水亲帽停在离岸三格的水面，沉稳的亲油坐在船长位置面朝船首，帽里有一根木棍和四份苹果；两只赤油都已学会短跳，个头小些的胆小那只在舱内（舱内能转身的位置只容得下它这么大）、胆大的在头顶，都已吃饱。'
     '舱内那只先跳上帽檐，辫子朝外对着水面拍水（亲油划桨时改成学划水）；头顶那只安静静坐。'
     '第 8 秒起天暗下雨，第 13 秒远处池塘水面真实落雷（不伤人不引火）：两只听到雷声后按各自胆量做出受惊表情与台词，胆小那只跳回舱内缩身、胆大那只在头顶缩身；第 20 秒雨停转晴后，胆大的先舒气平静，胆小的稍晚平静后再跳回帽檐继续玩耍。空手右击赤油看问候',
     '约 40 秒；可按「附近落雷」再看受惊',
     [yk('marisa', bank_dx(0), 0, 'a', aquatic=True, yaw=-90, lead=True, friendliness=100, boldness=80,
         uuid=uid('kid_boat_play', 'a'), **pair_ready()),
      # 0.16: the cabin in front of the seated captain has a place where a young can turn round (hair included) up to about this
      # size; the default 0.18 newborn could only stand pressed to the bow wall there, so it is seated on the head instead.
      _aquatic_young('kid_boat_play', 'c1', bank_dx(1), 1, 'baby', 'a', boldness=20, childhood=CHILDHOOD_HOPPING, also=('crew',),
                     fam=_seat_family(.16, 22909, 36000)),
      _aquatic_young('kid_boat_play', 'c2', bank_dx(2), 2, 'baby', 'a', boldness=75, childhood=CHILDHOOD_HOPPING, also=('crew',)),
      afloat_now('a', water_at(0, 3), True, sel_all('crew'), food=4, stick=True)],
     vantage=(-2, 2, -105, 16),
     late=['schedule function mcylab:util/weather_thunder 120t replace',
           Retry('execute positioned ~25 ~-1 ~ unless entity @e[distance=..8] run summon minecraft:lightning_bolt ~ ~ ~',
                 sel('a'), tries=1, first=220),
           'schedule function mcylab:util/weather_clear 360t replace'],
     note='日志：舱内赤油自己 hop 上帽檐 → [aquatic] play kind=kid_brim_splash start spot=brim reason=brim_water braid_out=true（亲油在划桨时是 kind=kid_mimic_paddle reason=captain_paddle，两者都是水上动作）'
          '→ 头顶那只 play kind=hat_cradle start spot=head reason=head_dry（静坐，不做水上动作）。'
          '第 8 秒转雷暴（起雨预警）、第 13 秒池塘远水真实落雷：[weather] thunder clap heard via=HEARD|SAW（共享感知层）→ [choreo] beat=thunder_hide（胆小 horrified/afraid，胆大 surprise/thunder，face==declared）'
          '→ 舱内有空位时 play return inside place=cabin reason=fear:thunder，再 play kind=kid_hide_behind start spot=cabin；舱内无位且 head=free 时先在帽檐缩身，亲油 shelter_young → carry lowering clip=braid_head_set → seat landed place=HEAD，随后在头顶缩身。'
          '第 20 秒转晴：play kind=kid_hide_behind end result=DONE → [choreo] beat=thunder_calm（胆大 happy 先平静，胆小 closed_smile 稍晚平静）→ 胆小赤油再次跳回帽檐玩耍。'
          '读 hat.float 的 play：各类演出开始次数、fear_* 原因次数；body.attach 看实际帽内坐标。点击赤油与点击空帽檐不同：前者问候，后者摇船；准备入座不算行为成功。' + _DESIRE_NOTE,
     reads=[('附近落雷', 'execute positioned ~25 ~-1 ~ unless entity @e[distance=..8] run summon minecraft:lightning_bolt ~ ~ ~'),
            ('读舱内赤油动作与点击', f'yukkuri status {sel("c1")} raw'), ('读头顶赤油', f'yukkuri status {sel("c2")} raw'),
            ('读演出与受惊计数', f'yukkuri status {sel("a")} raw'), ('读帽子乘员', f'data get entity {_HAT} Riders')],
     capture=dict(shots=(1, 40, 2400), sequences=((40, 2400, 4),), observe=False,
                  views={'port': dict(follow='@e[type=mcyukkuri:placed_hat,limit=1]', offset=(1.15, .55, -.75)),
                         'starboard': dict(follow='@e[type=mcyukkuri:placed_hat,limit=1]', offset=(-1.15, .55, .75))}),
     smoke=dict(ticks=1800,
                expect=[r'\[hat\][^\n]* scenario afloat prepared [^\n]*owner_aboard=true[^\n]*young=2\b',
                        r'\[hat\][^\n]* scenario place [^\n]*place=CABIN\b',
                        r'\[hat\][^\n]* scenario place [^\n]*place=HEAD\b',
                        r'\[aquatic\][^\n]* play kind=(?:kid_brim_splash|kid_mimic_paddle) start spot=brim [^\n]*braid_out=true\b',
                        r'\[aquatic\][^\n]* play kind=hat_cradle start spot=head reason=head_dry\b',
                        r'\[weather\][^\n]* thunder clap heard distance=\d+ via=(?:HEARD|SAW)\b',
                        r'\[choreo\][^\n]* beat=thunder_hide face=horrified line=afraid declared=horrified\b',
                        r'\[choreo\][^\n]* beat=thunder_hide face=surprise line=thunder declared=surprise\b',
                        r'\[aquatic\][^\n]* play kind=kid_hide_behind start spot=\w+ reason=fear:thunder\b',
                        r'\[aquatic\][^\n]*(?: play (?:return inside [^\n]*place=cabin|stays on brim [^\n]*cabin=none head=none) [^\n]*reason=fear:thunder\b| care transfer end kind=shelter_young [^\n]*result=DONE released=true [^\n]*head=true)',
                        r'\[aquatic\][^\n]* play kind=kid_hide_behind end [^\n]*result=DONE\b',
                        r'\[choreo\][^\n]* beat=thunder_calm face=happy line=none declared=happy\b',
                        r'\[choreo\][^\n]* beat=thunder_calm face=closed_smile line=none declared=closed_smile\b'],
                forbid=[r'\[body\][^\n]* invariant\b', r'\[body\][^\n]* death settled into remains', r'\[aquatic\][^\n]* play fall_in\b',
                        r'\[hat\][^\n]* capsized\b', r'damage cause=(?:inFire|onFire|lightningBolt)\b', r'\bon_fire\b',
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


# R6 (ruling 2026-11-27): the family sleeps on the water. Dusk starts the scene; the one time command lapses the night.
_NB = 'night_boat'
add(_NB, 'pond', '黄昏帽船泊静水，全家睡到天亮', '黄昏帽船泊静水，全家睡到天亮',
    '黄昏，亲油和两只赤油坐在离岸 3 格的帽船上（帽里有木棍），池中央是静水：亲油不上岸，场景几秒后天黑，她在原处等孩子坐稳、帽船停稳，然后躺下睡；两只赤油各自在舱里躺下睡。'
    '过一阵（场景用一条时间命令快进整夜）天亮，亲油先醒、说早安，赤油也醒，一家照常继续白天',
    '约 2–3 分钟（夜里的睡眠已快进）',
    [*BY_ID['pond'].biome_commands(),
     yk('marisa', bank_dx(0), 0, 'a', aquatic=True, yaw=-90, lead=True, uuid=uid(_NB, 'a'), nutrition=100, energy=60, hygiene=100),
     *[yk('marisa', bank_dx(dz), dz, name, stage='baby', aquatic=True, yaw=-90, uuid=uid(_NB, name), also=('crew',),
          kin=f'Mother:{uuid_nbt(uid(_NB, "a"))}', fam=_seat_family(size, nourishment, age), childhood=CHILDHOOD_CRAWLING_READY, **pair_ready())
       for name, dz, size, nourishment, age in (('k1', 1, .16, 22909, 36000), ('k2', -1, .20, 84000, 76000))],
     afloat_now('a', water_at(0, 3), True, sel_all('crew'), stick=True)],
    env={'time': 'dusk'}, vantage=(-6, 6, -90),
    # the museum clock stands still: 12500 is dusk (still day for the body), 13000 is night, 23800 is the next morning
    late=[Retry('time set 13000', sel('a'), tries=1, first=200), Retry('time set 23800', sel('a'), tries=1, first=2200)],
    note='日志：[aquatic] night_rest berth=…（stay=true：她这格本就是静水，原处不动）→ night_rest asked captain=true（亲油，要等帽船停稳）与 captain=false（赤油）→ '
         '[sleep] begin source=fatigue full=true … carrier=true → [sleep] beat=lie_down face=sleep line=sleep；天亮 [sleep] end source=fatigue full=true cause=rested → '
         '[sleep] beat=wake face=neutral line=awake。下雨、打雷、帽船被晃或孩子落水会先把亲油叫醒（[sleep] woken by danger reason=…），之后按白天的欲望处理（回岸或救孩子）。'
         + _DESIRE_NOTE,
    reads=[('读亲油', f'yukkuri status {sel("a")} raw'), ('读赤油一', f'yukkuri status {sel("k1")} raw'), ('读赤油二', f'yukkuri status {sel("k2")} raw'),
           ('读帽子', f'data get entity {_HAT}')],
    capture=dict(shots=(1, 600, 1500, 2000), sequences=((300, 2000, 100),)),
    smoke=dict(ticks=3200,
               expect=[r'\[aquatic\][^\n]* night_rest berth=[^\n]*stay=true',
                       r'\[aquatic\][^\n]* night_rest asked captain=true\b',
                       r'\[aquatic\][^\n]* night_rest asked captain=false\b',
                       r'\[sleep\][^\n]* begin source=fatigue full=true [^\n]*carrier=true',
                       r'\[sleep\][^\n]* beat=lie_down face=sleep line=sleep carrier=true',
                       r'\[sleep\][^\n]* end source=fatigue full=true cause=rested\b',
                       r'\[sleep\][^\n]* beat=wake face=neutral line=awake carrier=true cause=rested'],
               forbid=[r'\[body\][^\n]* invariant\b', r'\[body\][^\n]* death settled', r'\[sleep\][^\n]* woken by danger',
                       r'\[intent\][^\n]* start id=come_ashore\b', r'\[intent\][^\n]* start id=set_out\b',
                       r'\[aquatic\][^\n]* night_rest end result=(?:EXHAUSTED|LOST)',
                       r'\[needs\][^\n]* starvation damage accepted=true'],
               readout=[f'yukkuri status {sel(who)} raw' for who in ('a', 'k1', 'k2')] + [f'data get entity {_HAT} Riders']))

# Deck order of the pond field: solo life, family on the boat, falling in and rescue, birth, then hat handling and hull physics.
POND_TOUR = (
    'water_colony', 'sail', 'afloat_stick', 'cargo', 'hat_larder',
    'afloat_family', 'afloat_family_gather', 'afloat_family_ashore', 'shore_family', 'night_boat',
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


def _contest_core(log, prize, process, winner=None, tussles=0):
    """One staged contest per prize (the shared ``Contest`` log lines): its odds, every beat showing the face it declared, a verdict
    after the begin. Returns ``(error, None)`` or ``(None, (begin tick, verdict tick, winner, loser, where))``."""
    begins = re.findall(rf'\[social\][^\n]* t=(\d+) [^\n]*contest begin prize={prize} at=(.+?) process=(\w+) ', log)
    verdicts = re.findall(rf'\[social\][^\n]* t=(\d+) [^\n]*contest verdict prize={prize} at=(.+?) process=(\w+) winner=([\w-]+) loser=([\w-]+)', log)
    if len(begins) != 1 or len(verdicts) != 1:
        return f'expected one contest begin and one verdict, saw {len(begins)} and {len(verdicts)}', None
    if begins[0][2] != process or verdicts[0][2] != process:
        return f'contest process {begins[0][2]}/{verdicts[0][2]}, expected {process}', None
    if int(verdicts[0][0]) <= int(begins[0][0]):
        return 'the verdict did not come after the contest began', None
    began, where, _, won, lost = verdicts[0]
    if winner and won != winner:
        return f'{won} won the {prize}, expected {winner}', None
    beats = re.findall(_CONTEST_BEATS, log)
    for who, beat, shown, line, declared in beats:
        if shown != declared:
            return f'{who} beat={beat} showed {shown}, declared {declared}', None
    for who in (won, lost):
        names = [beat for u, beat, *_ in beats if u == who]
        for needed in ('contest_faceoff', 'contest_won' if who == won else 'contest_lost'):
            if needed not in names:
                return f'{who} never played {needed}', None
    if len([1 for _, beat, *_ in beats if beat == 'contest_tussle']) < tussles:
        return f'fewer than {tussles} tussle beats', None
    if re.search(r'contest beat=\w+ [^\n]*accepted=false', log):
        return 'a contest beat was refused by the face layer', None
    return None, (int(begins[0][0]), int(verdicts[0][0]), won, lost, where)


def _verify_bed_contest(log, process, winner=None, tussles=0):
    """A bed contest, then the loser at another free bed."""
    error, facts = _contest_core(log, 'bed', process, winner, tussles)
    if error:
        return error
    _, _, won, lost, bed = facts
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


def _verify_food_contest(log):
    """One contest over the one apple; the winner takes and eats it, the loser never touches that portion and walks off alive."""
    error, facts = _contest_core(log, 'food', 'TUSSLE', tussles=3)
    if error:
        return error
    _, verdict_at, won, lost, _ = facts
    taken = re.findall(rf'\[meal\][^\n]* uuid={won} t=(\d+) [^\n]*pickup item=minecraft:apple count=1 source=([\w-]+)', log)
    taken = [(int(t), source) for t, source in taken if int(t) > verdict_at]
    if not taken:
        return 'the winner did not pick up the apple after the verdict'
    if not [1 for t in re.findall(rf'\[meal\][^\n]* uuid={won} t=(\d+) [^\n]*swallowed item=minecraft:apple', log) if int(t) > verdict_at]:
        return 'the winner did not eat the apple'
    if re.search(rf'\[meal\][^\n]* uuid={lost} [^\n]*pickup item=minecraft:apple count=1 source={taken[0][1]}', log):
        return 'the loser took the contested apple'
    if not re.search(rf'\[meal\][^\n]* uuid={lost} [^\n]*yielded contested food [^\n]*rival={won}\b', log):
        return 'the loser did not give the portion way'


add('food_contest', 'forage', '两个同样饿的成油争一个苹果', '两个同样饿的成油争一个苹果',
    '街中间地上只有一个苹果，两个同样饿的流浪成油（营养 38 与 44）从两侧走来。看它们先看一眼苹果，再在苹果旁面对面对峙，数值相当就互相顶撞几轮，'
    '赢的认下苹果吃掉，输的按胆量鼓腮瞪着或垂泪让开、不碰这个苹果，转身走远。',
    '约 40 秒', [
        item('minecraft:apple', 1, 0, 0, name='contest_apple'),
        street_stray('food_contest', 'reimu', -3, 0, 'a', nutrition=38, boldness=85, yaw=-90, label='甲', lead=True),
        street_stray('food_contest', 'reimu', 3, 0, 'b', nutrition=44, boldness=85, yaw=90, label='乙'),
    ], env=DAY, spot='street', vantage=(0, 9),
    reads=[('读甲', f'yukkuri status {sel("a")} raw'), ('读乙', f'yukkuri status {sel("b")} raw')],
    smoke=dict(ticks=900, expect=[], forbid=[r'contest beat=\w+ [^\n]*accepted=false', r'food_order wait_behind'], verify=_verify_food_contest))

_toy_a = _uuid_text('toy_contest', 'a')
_toy_b = _uuid_text('toy_contest', 'b')


def _verify_toy_contest(log):
    """One contest over the free station; the winner plays it first, the loser gives it way and takes its own turn after."""
    error, facts = _contest_core(log, 'toy', 'TUSSLE', tussles=3)
    if error:
        return error
    _, verdict_at, won, lost, _ = facts
    started = {who: [int(t) for t in re.findall(rf'\[play\][^\n]* uuid={who} t=(\d+) [^\n]*facility session started', log)] for who in (won, lost)}
    ended = [int(t) for t in re.findall(rf'\[play\][^\n]* uuid={won} t=(\d+) [^\n]*facility session ended', log)]
    first = [t for t in started[won] if t > verdict_at]
    if not first:
        return 'the winner did not start playing after the verdict'
    if [t for t in started[lost] if t <= verdict_at]:
        return 'the loser started a session before the verdict'
    later = [t for t in started[lost] if t > verdict_at]
    if not later or min(later) <= min(first):
        return 'the loser did not take its own turn after the winner'
    if not re.search(rf'\[play\][^\n]* uuid={lost} [^\n]*contested station yielded [^\n]*rival={won}\b', log):
        return 'the loser did not give the station way'


add('toy_contest', 'park', '两个无聊的成油争一个游具', '两个无聊的成油争一个游具',
    '蹦床前的空地上两个同样无聊的流浪成油并肩站着，都想玩蹦床。看它们互相对峙，数值相当就顶撞几轮，赢的先上床玩，'
    '输的按胆量鼓腮瞪着或垂泪让开，歇一会儿再去玩自己的那一轮。',
    '约 1 分半钟', [
        block(-6, 0, -7, 'mcyukkuri:yukkuri_nest[facing=south]'),
        block(6, 0, -7, 'mcyukkuri:yukkuri_nest[facing=south]'),
        street_trial('toy_contest', 'marisa', -.7, -3, 'a', boldness=85, intelligence=70, adult_scale=.9, nutrition=95, energy=100, yaw=0,
                     label='甲', lead=True, extra='YukkuriDailyLife:{Boredom:98,Affection:20}'),
        street_trial('toy_contest', 'marisa', .7, -3, 'b', boldness=85, intelligence=70, adult_scale=.9, nutrition=95, energy=96, yaw=0,
                     label='乙', extra='YukkuriDailyLife:{Boredom:95,Affection:20}'),
        home('a', -6, -7), home('b', 6, -7),
    ], env=DAY, spot='trampoline', vantage=(6, 6, 180, 18),
    reads=[('读甲', f'yukkuri status {sel("a")} raw'), ('读乙', f'yukkuri status {sel("b")} raw')],
    smoke=dict(ticks=2400, expect=[rf'uuid={who} [^\n]*facility session started' for who in (_toy_a, _toy_b)],
               forbid=[r'contest beat=\w+ [^\n]*accepted=false'], verify=_verify_toy_contest))

_number_scenes()


# Topic codes are shared by the scene buttons, signs, and readouts.
TOPICS = {
    'oversized_nest': 'D1',
    'single_bed_contest': 'D1',
    'bed_contest_even': 'D1',
    'food_contest': 'D1',
    'toy_contest': 'D1',
    'processing_plant': 'M2',
    'factory_sweet_box': 'M2',
    'factory_kin_boxes': 'M2',
    'kindness_adoption': 'F4',
    'home_pane': 'W7', 'home_open': 'W7',
    'balance_traits': 'N1', 'balance_relation': 'N1', 'balance_forms': 'N1', 'balance_deibu': 'N1',
    'waste_demo': 'F5', 'family_walk_home': 'F4', 'family_flight_home': 'F4', 'family_flight_timid': 'F4', 'family_flight_hat': 'F4',
    'choice_nest': 'D1', 'choice_food': 'D1', 'choice_tool': 'D1', 'choice_snatch': 'D1', 'choice_toilet': 'D1',
    'agitation_relief': 'D2', 'adult_pairing': 'F6', 'widow_repair': 'F6', 'alice_hunt': 'R4', 'aftermath_growth': 'F7', 'aftermath_depletion': 'F7',
    'pentup_heat': 'F6', 'pentup_hold': 'F6', 'pentup_breach': 'F6', 'rule_breed_allowed': 'F6', 'rule_breed_denied': 'F6', 'rule_change_other': 'F6',
    'den_family': 'W1', 'den_mold_exile': 'W1', 'dig': 'W2', 'indep_near': 'W3', 'den_move': 'W4', 'home_greedy': 'W4', 'forage': 'W5', 'graze': 'W6', 'food_behind_glass': 'W6', 'food_sill_window': 'W6',
    'den_barrier': 'W8', 'den_barrier_predator': 'W8', 'den_barrier_threat': 'W8', 'den_lead_away': 'W8', 'den_call_timeout': 'W8',
    'den_mold_child_exile': 'W1', 'den_mold_keep': 'W1', 'den_raid': 'W1', 'den_monopoly': 'W1',
    'family_absence': 'W9',
    'wall_tuck': 'M5',
    'stray_boxes': 'M5', 'stray_strange_food': 'D1','stray_rain': 'R1', 'stray_rubbish': 'M5', 'stray_junk_headwear': 'M5', 'stray_wind': 'R1', 'stray_paper': 'M5', 'stray_paper_young': 'M5',
    'cold_stiff_stray': 'R1', 'cold_stiff_reload': 'R1', 'cold_stiff_negative': 'R1',
    'cold_stiff_mined': 'R1', 'cold_stiff_witness': 'R1', 'cold_stiff_rewarm_fire': 'R1',
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
    'water_colony': 'A6', 'sail': 'A6', 'afloat_stick': 'A6', 'afloat_family': 'A6', 'afloat_family_gather': 'A6', 'afloat_family_ashore': 'A6', 'shore_family': 'A6', 'night_boat': 'A6', 'kid_boat_play': 'A6', 'boat_toilet': 'A6',
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
