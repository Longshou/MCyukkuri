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
        doll('mcyukkuri:marisa_doll', 3, 5, 'doll_target'),
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
        doll('mcyukkuri:marisa_doll', 3, 5, 'doll_fright'),
        doll('mcyukkuri:marisa_doll', 7, 8, 'doll_hungry'),
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
        doll('mcyukkuri:reimu_doll', -5, 3, 'talk_doll_left'),
        doll('mcyukkuri:reimu_doll', 3, 3, 'talk_doll_mid'),
        doll('mcyukkuri:reimu_doll', 11, 3, 'talk_doll_right'),
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
        doll('mcyukkuri:reimu_doll', 0, 4, 'friend_doll'),
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
        doll('mcyukkuri:reimu_doll', 0, 3, 'giveup_doll'),
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
        doll('mcyukkuri:reimu_doll', 7.65, 8.65, 'snatch_doll_a'),
        doll('mcyukkuri:reimu_doll', 17.65, 8.65, 'snatch_doll_b'),
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
        doll('mcyukkuri:reimu_doll', 2.1, 6, 'take_doll_a'),
        doll('mcyukkuri:reimu_doll', 7.1, 6, 'take_doll_b'),
        doll('mcyukkuri:reimu_doll', 12.1, 6, 'take_doll_c'),
        doll('mcyukkuri:reimu_doll', 17.1, 6, 'take_doll_d'),
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


# --- A house rule flipped: each pet that saw or heard it answers by its own numbers (RuleReaction). Four adults of one owner, in view. ---
_BREED_CAST = (  # name, species, dx, label, pentUp, and the five axes that split the beats; obedience follows from boldness and self-control
    ('hot_bold', 'reimu', 0, '憋得慌的胆大者', 90, dict(boldness=90, self_control=40, intelligence=40, attachment=40)),
    ('hot_shy', 'marisa', -2, '憋得慌的胆小者', 70, dict(boldness=10, self_control=70, intelligence=40, attachment=40)),
    ('wise_tied', 'marisa', 2, '聪明黏人者', 60, dict(boldness=30, self_control=60, intelligence=85, attachment=85)),
    ('cool', 'reimu', 4, '没这心思的', 5, dict(boldness=55, self_control=70, intelligence=55, attachment=60)))


def _rule_beats(log, rule, change, expected=None):
    """Each pet answers once per change; the face shown on the beat (the arbiter's [badge] line) is the declared one, every beat's line
    was spoken, and (when given) the answers are the ones its numbers pick."""
    answers = re.findall(rf'\[rules\][^\n]*uuid=(\S+)[^\n]* rule reaction rule={rule} change={change} beat=(\w+) face=(\S*) line=(\S+) shown=(\w+)', log)
    if len({(uuid_, beat) for uuid_, beat, *_ in answers}) != len(answers):
        return f'a pet answered the same change twice: {answers}'
    got = sorted(beat for _, beat, *_ in answers)
    if expected is not None and got != sorted(expected):
        return f'wanted beats {sorted(expected)}, got {got}: {answers}'
    if any(shown != 'true' for *_, shown in answers):
        return f'a declared beat was refused by the arbiter: {answers}'
    spoken = re.findall(r'\[rules\][^\n]*uuid=(\S+)[^\n]* rule reaction speech beat=(\w+) event=(\S+) tick=\S+ holdEnd=\S+ spoken=(\w+)', log)
    for uuid_, beat, *_ in answers:
        if not any(u == uuid_ and b == beat and said == 'true' for u, b, _, said in spoken):
            return f'beat {beat} of {uuid_} was never spoken: {spoken}'
    seen = re.findall(r'\[badge\][^\n]* beat=rule_(\w+) face=(\S+) declared=(\S+)', log)
    shown_beats = sorted(name for name, _, _ in seen)
    if expected is not None and (shown_beats != got or any(face != declared for _, face, declared in seen)):
        return f'shown faces must equal the declared ones for {got}: {seen}'
    if any(face != declared for _, face, declared in seen):
        return f'shown faces must equal the declared ones: {seen}'


def _rule_changes(log):
    """Three changes of other rules (two tightenings, then a loosening), each answered by all four pets."""
    for rule, change in (('no_singing', 'tightened'), ('no_eating_unasked', 'tightened'), ('no_singing', 'loosened')):
        answers = re.findall(rf'\[rules\][^\n]*uuid=(\S+)[^\n]* rule reaction rule={rule} change={change} beat=(\w+) ', log)
        if len({uuid_ for uuid_, _ in answers}) != 4:
            return f'rule {rule} {change}: four pets must answer, got {answers}'
        problem = _rule_beats(log, rule, change)
        if problem:
            return f'rule {rule} {change}: {problem}'
    if re.search(r'rule reaction rule=no_sukkiri', log):
        return 'the breeding rule was never changed here'


for _sid, _title, _watch, _toggles, _beats, _rule, _change in (
        ('rule_breed_allowed', '家规放开繁殖：各有各的反应', '在任一宠物的家规页取消勾选「不准繁殖（素鸡立）」，等它们鞠躬后各自反应：憋得慌又胆大的蹦起来看伙伴再鞠躬道谢，胆小的脸红，没心思的点头道谢。',
         [(40, 0)], ('loosen_joy', 'loosen_thanks', 'loosen_shy', 'loosen_shy', 'loosen_nod'), 'no_sukkiri', 'loosened'),
        ('rule_breed_denied', '家规禁止繁殖：各有各的反应', '在任一宠物的家规页先取消勾选「不准繁殖（素鸡立）」再立刻重新勾选（一秒内，只演拒绝）；间隔十秒以上再勾，两次各演一遍。鞠躬后：不听话又憋得慌的鼓脸哼声再服软，听话的垂头，聪明黏人的羞愧接受，没心思的点头应下。',
         [(40, 0), (46, 1)], ('tighten_puff', 'tighten_yield', 'tighten_droop', 'tighten_accept', 'tighten_nod'), 'no_sukkiri', 'tightened'),
        ('rule_change_other', '改其他家规：每只都有反应', '在任一宠物的家规页依次勾选「不准唱歌」、「不准擅自吃东西」，再取消「不准唱歌」（每次隔十秒以上）。看得见或听得见的宠物鞠躬后各自反应：胆小的发抖求饶，不听话的鼓脸再服软，听话的垂头，放开时欢喜道谢。',
         [(40, 3), (400, 11), (760, 9)], None, None, None)):
    _breed_commands = [(1, 'execute positioned ~0.5 ~ ~2.5 run yukkuri scenario tool_operator enter'), (2, 'yukkuri scenario house_reset')]
    _breed_commands += [(8 + i, f'data modify entity {sel(_name)} {OWNED}.Owner set from entity @p[name=MuseumOperator,distance=..16] UUID')
                        for i, (_name, *_rest) in enumerate(_BREED_CAST)]
    _breed_commands += [(_tick, f'yukkuri scenario house_rules {sel("hot_bold")} {_mask}') for _tick, _mask in _toggles]
    _ticks = 400 if _beats else 1150
    _breed_commands.append((_ticks - 1, 'yukkuri scenario tool_operator leave'))
    add(_sid, 'town', _title, _title, _watch, '约一分钟' if _beats else '约一分半钟', [
        box(-6, -1, -4, 6, -1, 4, 'minecraft:grass_block'), box(-6, 0, -4, 6, 3, 4, 'minecraft:air'),
        *(box(-6, 0, z, 6, 1, z, 'minecraft:glass') for z in (-4, 4)),
        *(box(x, 0, -3, x, 1, 3, 'minecraft:glass') for x in (-6, 6)),
        # No roof: uncovered stands leave the four unbonded pets without a home. A roof would give each a roof home at its own feet,
        # two to four blocks apart, and they would defend it in the middle of the answers (territory warn/yield/puff changes their faces).
        *(line for _name, _kind, _dx, _label, _pent, _axes in _BREED_CAST for line in (
            yk(_kind, _dx, -2, _name, label=_label, lead=_name == 'hot_bold', kin=f'PentUp:{_pent}f', **_axes, **pair_ready(),
               # No leisure session starts in the answers' way; the song rule's pressure is the boredom (what it withholds).
               **({} if _beats else dict(extra=f'YukkuriDailyLife:{{Boredom:{_pent},Cooldown:2400}}'))), *own(_name)))],
        env=DAY, vantage=(0, 2, 180, 15), give=['mcyukkuri:field_notebook'],
        note='四只同主人的成体都在主人视野内；各自的积压、胆量、听话与聪明黏人不同，所以同一次家规变化演出不同。'
             '没有变化的家规不演；不平静的、睡着的宠物不演；没看见也没听见主人改家规的要等下次看见主人。',
        reads=[('读' + label + '的积压', f'data get entity {sel(name)} YukkuriFamily.PentUp') for name, _, _, label, _, _ in _BREED_CAST],
        smoke=dict(ticks=_ticks, commands=_breed_commands,
                   verify=(lambda log, _rule=_rule, _change=_change, _beats=_beats: _rule_beats(log, _rule, _change, _beats)) if _beats else _rule_changes,
                   expect=[rf'rule reaction rule={_rule} change={_change} beat={beat}' for beat in dict.fromkeys(_beats)] if _beats else
                          [r'rule reaction rule=no_singing change=tightened beat=', r'rule reaction rule=no_eating_unasked change=tightened beat=',
                           r'rule reaction rule=no_singing change=loosened beat='],
                   forbid=[f'rule reaction rule=no_sukkiri change={"tightened" if _change == "loosened" else "loosened"}'] if _beats else [r'rule reaction rule=no_sukkiri']))


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
