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
    return [(1, 'yukkuri scenario tool_operator enter'), (2, 'yukkuri scenario house_reset'),
            (10, f'yukkuri scenario owner_at {pet} watch'),
            (11, f'yukkuri scenario house_rules {pet} {mask}')]


_PENANCE_BEAT = re.compile(r'\[rules\][^\n]*? t=(\d+) [^\n]*penance beat=(\w+) face=(\S+) declared=(\S+) line=(\S+) spoke=(\w+) '
                           r'clip=(\S+) declaredClip=(\S+) entrance=(\w+) rule=(\S+)')
# Faces of pleasure and pout: nothing between the catch (or the bow) and the owner's answer may show one.
_PLEASED_OR_POUTING = {'happy', 'open_smile', 'satisfied', 'smug', 'closed_smile', 'pursed', 'angry', 'cheek_puff', 'puff', 'sneer',
                       'hmph', 'blank_happy', 'flushed_smile', 'sukkiri_grin', 'sukkiri_smug'}
_PENANCE_LINES = {'startle': '-|rule_gasp', 'bow': 'confess|confess_mate|exam_sorry', 'mutter': 'sulk_huff', 'afraid': '-', 'plea': 'rule_beg', 'apology': 'caught_guilty',
                  'excuse': 'rule_excuse', 'vow': 'rule_vow', 'settle': '-|rule_accept|rule_withdraw',
                  'prostrate': '-', 'shiver': '-|rule_wait', 'stiff': '-', 'punished': 'scolded|hurt_plea', 'forgiven': 'rule_thanks', 'withdraw': '-',
                  # The answer came through the child: the struggle, how it ends, and the silent ache that follows.
                  'torn': '-', 'shield': 'protect_taunt', 'plead': 'plea_spare', 'submit': '-', 'flee': '-', 'ache': '-'}


def _penance_beats(window, entrance, rule, pet=None):
    """One pet's penance beats as (tick, name, shown face, declared face, line, spoke, clip, declared clip), in log order."""
    return [(int(m[1]), *m.groups()[1:8]) for line in window.splitlines()
            if (pet is None or f'uuid={pet} ' in line) and (m := _PENANCE_BEAT.search(line)) and m[9] == entrance and m[10] == rule]


def _penance_problem(beats, first, last):
    """The beats must start with ``first``, end with one of ``last``, show the face they declared, pair each with its line, and never
    show a pleased or pouting face (the one logged mutter beat excepted). After the verdict beat the pet settles into a held posture
    (the settle beat) unless it is a parent that ached for its punished child."""
    names = [b[1] for b in beats]
    core = names[:-1] if names and names[-1] == 'settle' else names
    if names[:len(first)] != first or not names or not core or core[-1] not in last or (core == names and core[-1] != 'ache'):
        return f'Penance beats {names} must start {first}, end {last} and then settle'
    for tick, name, shown, declared, line, spoke, clip, declared_clip in beats:
        if shown != declared:
            return f'Beat {name} at {tick} declared face {declared} but showed {shown}'
        if not re.fullmatch(_PENANCE_LINES[name], line):
            return f'Beat {name} at {tick} carries line {line}, expected {_PENANCE_LINES[name]}'
        if line != '-' and spoke != 'true':
            return f'Beat {name} at {tick} line {line} was not voiced'
        if name not in ('mutter', 'settle', 'forgiven') and shown in _PLEASED_OR_POUTING:
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
    problem = _penance_problem(beats, ['startle', 'mutter', 'excuse', 'stiff'], ['punished'])
    if problem:
        return problem
    if [b[1] for b in beats[-3:]] != ['afraid', 'punished', 'settle'] or [b[1] for b in beats].count('mutter') != 1:
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
# about 280 ticks after the catch is on record (the startle, the mutter and the small excuse are over, the pet stands stiff), then the
# observer stays through the rest of the penance (the owner's answer, the settled posture) and leaves 560 ticks after the scold.
for _tick in range(100, 6400, 20):
    _rule_songs += [
        # The urge the song reads stays at full pressure until the pet has broken the rule: a leisure session of another kind (an exercise)
        # must not spend the boredom first. The beat itself (the song, the catch) is left to the pet.
        (_tick, f'execute if score #rule_armed museum matches 0 unless data entity {_rule_pet} YukkuriPet.Conscience{{Rule:"no_singing"}} run data modify entity {_rule_pet} YukkuriDailyLife.Boredom set value 100'),
        (_tick, f'execute if score #rule_armed museum matches 0 if data entity {_rule_pet} YukkuriPet.Conscience{{Rule:"no_singing"}} run yukkuri scenario owner_at {_rule_pet} watch'),
        (_tick, f'execute if score #rule_armed museum matches 14.. if score #rule_scold museum matches 0 store result score #rule_scold museum run yukkuri scenario care_click {_rule_pet} cold'),
        (_tick + 1, f'execute if score #rule_armed museum matches 0 if data entity {_rule_pet} YukkuriPet.Conscience{{Rule:"no_singing",Caught:1b}} run scoreboard players set #rule_armed museum 1'),
        (_tick + 2, 'execute if score #rule_armed museum matches 1.. run scoreboard players add #rule_armed museum 1'),
        (_tick + 3, 'execute if score #rule_scold museum matches 1.. run scoreboard players add #rule_scold museum 1'),
        (_tick + 4, 'execute if score #rule_scold museum matches 28.. run yukkuri scenario tool_operator leave')]
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
            # The first blow comes about 320 ticks after the catch is on record (the gasp and the plea are said, the pet lies low), the second 80 ticks later.
            # The observer stands in front of the pet when the blow lands (the pet may have walked to the bowl since the start).
            (tick, f'execute if score #eat_armed museum matches 16.. if score #eat_blow museum matches 0 run yukkuri scenario owner_at {_rule_pet} watch'),
            (tick, f'execute if score #eat_blow museum matches 4.. if score #eat_blow2 museum matches 0 run yukkuri scenario owner_at {_rule_pet} watch'),
            (tick, f'execute if score #eat_armed museum matches 16.. if score #eat_blow museum matches 0 store result score #eat_blow museum run yukkuri scenario care_click {_rule_pet} flick'),
            (tick, f'execute if score #eat_blow museum matches 4.. if score #eat_blow2 museum matches 0 store result score #eat_blow2 museum run yukkuri scenario care_click {_rule_pet} flick'),
            (tick + 1, f'execute if score #eat_armed museum matches 0 if data entity {_rule_pet} YukkuriPet.Conscience{{Rule:"no_eating_unasked",Caught:1b}} run scoreboard players set #eat_armed museum 1'),
            (tick + 2, 'execute if score #eat_armed museum matches 1.. run scoreboard players add #eat_armed museum 1'),
            (tick + 3, 'execute if score #eat_blow museum matches 1.. run scoreboard players add #eat_blow museum 1'),
            (tick + 4, 'execute if score #eat_blow museum matches 28.. run yukkuri scenario tool_operator leave')]
    return commands


# The pet is caught breaking the eating rule with the owner in view; a stroke (one to two steps after the catch is on record) forgives it.
_eat_commands = _rule_owner(9) + [
    (12, 'scoreboard players set #eat_armed museum 0'), (12, 'scoreboard players set #eat_rub museum 0'),
    (15, 'item replace block ~ ~ ~2 container.0 with minecraft:apple 1')]
for _tick in range(40, 2300, 20):
    _eat_commands += [
        # The stroke comes about 600 ticks after the catch is on record: the gasp, the apology and the oath are said and the pet is lying low and shivering.
        (_tick, f'execute if score #eat_armed museum matches 30.. if score #eat_rub museum matches 0 run yukkuri scenario owner_at {_rule_pet} watch'),
        (_tick, f'execute if score #eat_armed museum matches 30.. if score #eat_rub museum matches 0 store result score #eat_rub museum run yukkuri scenario care_click {_rule_pet} rub'),
        (_tick + 1, f'execute if score #eat_armed museum matches 0 if data entity {_rule_pet} YukkuriPet.Conscience{{Rule:"no_eating_unasked",Caught:1b}} run scoreboard players set #eat_armed museum 1'),
        (_tick + 2, 'execute if score #eat_armed museum matches 1.. run scoreboard players add #eat_armed museum 1'),
        (_tick + 3, 'execute if score #eat_rub museum matches 1.. run scoreboard players add #eat_rub museum 1'),
        (_tick + 4, 'execute if score #eat_rub museum matches 28.. run yukkuri scenario tool_operator leave')]
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
    withdraw = next(b[0] for b in beats if b[1] == 'withdraw')
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
            # A walk that ends BLOCKED beside the child is the body's own refusal at a crowded stand: it still stands beside the child.
            if not arrival or torn[2] == 'SHIELD' and float(arrival[2]) > 2.0:
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
    """The refused lead is tried once; every lead beat shows the face it declared; the owner's pet hops, is alarmed and relieved in order;
    every stray strains in bouts that each end, is spent once, and never strains after that; every stray answered snap-on and release."""
    refusals = re.findall(r'\[interaction\][^\n]*action=leash reason=', window)
    if len(refusals) > 1: return f'the refused lead was tried {len(refusals)} times'
    for beat in ('stranger_leashed', 'stranger_released'):
        found = len(re.findall(rf'\[lead\][^\n]*beat={beat} ', window))
        if found < 3: return f'only {found} of the three strays answered {beat}'
    if not re.search(r'\[lead\][^\n]*beat=stranger_release_look ', window): return 'the frightened stray never looked back'
    for shown in re.finditer(r'\[choreo\][^\n]* beat=((?:lead|stranger)_\w+) face=(\S+) line=\S+ declared=(\S+)', window):
        if shown[2] != shown[3]: return f'beat {shown[1]} showed {shown[2]} but declared {shown[3]}'
    pet = _uuid_of(uid('lead_walk', 'lead_pet'))
    seen = sorted((int(t), beat) for t, beat in re.findall(rf'\[lead\][^\n]*uuid={pet} t=(\d+)[^\n]* beat=(lead_hurry|lead_alarm|lead_relief) ', window))
    order = [beat for _, beat in seen]
    for beat in ('lead_hurry', 'lead_alarm', 'lead_relief'):
        if beat not in order: return f"the owner's pet never showed {beat}"
    if any('hop=true' not in line for line in re.findall(rf'\[lead\][^\n]*uuid={pet} [^\n]* beat=lead_hurry [^\n]*', window)):
        return "the owner's pet hurried without hopping"
    # One episode must run hurry, then alarm, then relief in order (a yank may skip the hurry; the catch-up after the lead goes on is an episode of its own).
    rest = iter(order)
    if not all(any(beat == want for beat in rest) for want in ('lead_hurry', 'lead_alarm', 'lead_relief')):
        return f"the owner's pet beats never ran hurry, alarm, relief in order {order}"
    # The faces are not enough: each beat's line must really be spoken (a refused line is only logged as declared).
    for event in ('lead_hurry', 'lead_alarm', 'lead_relief'):
        if not re.search(rf'\[speech\][^\n]*uuid={pet}[^\n]* line event={event} ', window): return f'the owner\'s pet never spoke {event}'
    for stray in ('lead_tame', 'lead_bold', 'lead_shy'):
        who = _uuid_of(uid('lead_walk', stray))
        bouts = [int(t) for t in re.findall(rf'\[lead\][^\n]*uuid={who} t=(\d+)[^\n]* beat=lead_struggle ', window)]
        ends = re.findall(rf'\[lead\][^\n]*uuid={who} [^\n]*struggle ended reason=(\w+)[^\n]* pulled=(-?[\d.]+)', window)
        spent = re.search(rf'\[lead\][^\n]*uuid={who} t=(\d+)[^\n]* beat=lead_spent ', window)
        if not bouts: return f'{stray} never strained against the lead'
        if not re.search(rf'\[speech\][^\n]*uuid={who}[^\n]* line event=(?:lead_struggle|afraid) ', window): return f'{stray} never spoke while it strained'
        if not re.search(rf'\[speech\][^\n]*uuid={who}[^\n]* line event=lead_spent ', window): return f'{stray} never spoke its spent line'
        snapped = re.search(rf'\[lead\][^\n]*uuid={who} t=(\d+)[^\n]* beat=stranger_leashed ', window)
        if not snapped or int(snapped[1]) > bouts[0]: return f'{stray} strained before it answered the snap-on'
        if len(ends) != len(bouts): return f'{stray} began {len(bouts)} bouts but {len(ends)} ended'
        if max(float(pulled) for _, pulled in ends) < .3: return f'{stray} never pulled away from the holder (best {max(float(p) for _, p in ends)})'
        if not spent: return f'{stray} was never spent'
        if bouts[-1] > int(spent[1]): return f'{stray} strained after it was spent'
    return None


add('lead_walk', 'town', '牵引绳：自家的宠物与无主的个体', '牵引绳：自家的宠物与无主的个体',
    '手持原版牵引绳依次右键。先拴自家的灵梦，快步走开十来格再停下：它落后会蹦跳着追（嘴上喊「等等窝」），绳绷紧时着急惊慌，追上后松口气；'
    '再把三只无主的流浪油拴在手上站定：它们累了一路，刚拴上时胆小的惊吓、胆大的鼓腮抗议、温顺的只是吃惊，随后不停转身往外挣、拽紧再放松，'
    '每次挣扎都耗体力、喊「放开窝」或哭叫，挣扎越来越短越稀，力竭后只喊没力气、被拖着走不再挣；松开后胆小的跑开几步回头看、胆大的哼一声走开、温顺的留在原地；'
    '院主的宠物会拒绝。', '随意', [
        yk('reimu', -3, 0, 'lead_pet', uuid=uid('lead_walk', 'lead_pet'), lead=True, nutrition=90, energy=100, hygiene=100, label='自家的灵梦'), *own('lead_pet'),
        yk('marisa', 0, 2, 'lead_tame', uuid=uid('lead_walk', 'lead_tame'), identity='STRAY', label='温顺的流浪魔理沙',
           friendliness=90, boldness=60, nutrition=80, energy=36),
        yk('reimu', 3, 2, 'lead_bold', uuid=uid('lead_walk', 'lead_bold'), identity='STRAY', label='胆大的流浪灵梦',
           friendliness=40, boldness=85, nutrition=80, energy=36),
        yk('alice', 6, 2, 'lead_shy', uuid=uid('lead_walk', 'lead_shy'), identity='STRAY', label='胆小的流浪爱丽丝',
           friendliness=90, boldness=10, nutrition=80, energy=36),
        human('caretaker_sister', -3, 7, 'lead_keeper', 0, yaw=0, label='院主'),
        yk('chen', -3, 4, 'lead_other', uuid=uid('lead_walk', 'lead_other'), label='院主的宠物', nutrition=80),
        f'data modify entity {sel("lead_other")} {OWNED} set value {{Use:"companion",OwnerKind:"npc"}}',
        f'data modify entity {sel("lead_other")} {OWNED}.Owner set from entity {sel("lead_keeper")} UUID',
    ], env=DAY,
    give=['minecraft:lead 6', 'minecraft:apple 6'],
    reads=[('读胆大的流浪油', f'yukkuri status {sel("lead_bold")} raw')], vantage=(0, 8),
    smoke=dict(ticks=950, verify=lead_walk_check, commands=[
        (10, 'yukkuri scenario tool_operator enter'),
        (11, f'execute as @a[name=MuseumOperator,distance=..32] run data modify entity {sel("lead_pet")} {OWNED} set value {{Use:"companion",OwnerKind:"player"}}'),
        (12, f'execute as @a[name=MuseumOperator,distance=..32] run data modify entity {sel("lead_pet")} {OWNED}.Owner set from entity @s UUID'),
        # The refused lead first. Then the three strays on one hand, at once (they roam from the first tick): the player stands two blocks
        # north of the middle one and waits while they strain until spent, and lets go.
        (20, f'execute at {sel("lead_other")} positioned ~1 ~ ~ run yukkuri scenario tool_operator move'),
        (21, f'execute as @a[name=MuseumOperator,distance=..32] run yukkuri scenario leash {sel("lead_other")}'),
        (25, f'execute at {sel("lead_bold")} positioned ~ ~ ~-2 run yukkuri scenario tool_operator move'),
        *((26 + i, f'execute as @a[name=MuseumOperator,distance=..32] run yukkuri scenario leash {sel(name)}')
          for i, name in enumerate(('lead_tame', 'lead_bold', 'lead_shy'))),
        # The player steps up to each stray (spent ones sit at the taut end of the lead, out of reach) and lets it go.
        *((560 + 10 * i, f'execute at {sel(name)} positioned ~1 ~ ~ run yukkuri scenario tool_operator move')
          for i, name in enumerate(('lead_tame', 'lead_bold', 'lead_shy'))),
        *((561 + 10 * i, f'execute as @a[name=MuseumOperator,distance=..64] run yukkuri scenario unleash {sel(name)}')
          for i, name in enumerate(('lead_tame', 'lead_bold', 'lead_shy'))),
        # The owner's pet is leashed with the strays, before it can roam (so its stand is the open anchor row, never a spot it wandered to); it
        # follows the player there. Once the strays are let go the player steps west of it and walks a dozen blocks east along the open row at
        # a quick pace (a step each 5 ticks) and stops. (A bare @a[name=...] never matches the museum's fake operator, which the level holds
        # but the server player list does not: keep the distance cap.)
        (29, f'execute as @a[name=MuseumOperator,distance=..64] run yukkuri scenario leash {sel("lead_pet")}'),
        (600, f'execute at {sel("lead_pet")} positioned ~-1 ~ ~ run yukkuri scenario tool_operator move'),
        *((620 + 5 * step, 'execute at @a[name=MuseumOperator,distance=..64] positioned ~1 ~ ~ run yukkuri scenario tool_operator move') for step in range(12)),
        (820, f'execute as @a[name=MuseumOperator,distance=..64] run yukkuri scenario unleash {sel("lead_pet")}'),
        (900, 'yukkuri scenario tool_operator leave'),
    ], forbid=[r'\[body\][^\n]* invariant\b', r'\[body\][^\n]* in wall', r'\[body\][^\n]* death settled', r'\[lead\][^\n]*hop ended reason=stuck'],
        expect=[r'\[interaction\][^\n]*action=leash reason=owner',
                r'\[scenario\][^\n]*action=leash player=[^\n]* identity=PET\b',
                r'\[scenario\][^\n]*action=unleash player=',
                r'\[lead\][^\n]*beat=lead_hurry face=(?:surprise|afraid|teary_pant) line=lead_hurry',
                r'\[lead\][^\n]*beat=lead_alarm face=(?:horrified|afraid) line=lead_alarm',
                r'\[lead\][^\n]*beat=lead_relief face=(?:teary_smile|happy) line=lead_relief',
                r'\[lead\][^\n]*beat=lead_struggle face=teary line=lead_struggle',
                r'\[lead\][^\n]*beat=lead_struggle face=angry line=lead_struggle',
                r'\[lead\][^\n]*beat=lead_struggle face=horrified line=afraid',
                r'\[lead\][^\n]*beat=lead_spent face=teary_pant line=lead_spent',
                r'\[lead\][^\n]*beat=stranger_leashed face=surprise line=wait_turn',
                r'\[lead\][^\n]*beat=stranger_leashed face=puff line=sulk_huff',
                r'\[lead\][^\n]*beat=stranger_leashed face=surprise line=harness_tug',
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
