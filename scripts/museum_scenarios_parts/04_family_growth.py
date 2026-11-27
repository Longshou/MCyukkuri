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
