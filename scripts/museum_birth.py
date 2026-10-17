"""Real family births in isolated observation yards; no template infants or timed rewind.

One station ("迎接新生命") reaches four yards: on land and on a boat, each with a forced branch --
``caught`` (the father notices, takes off his hat and the newborn lands in it) and ``missed`` (no hat:
the newborn lands on the ground and is hurt, not fatally). The branch is forced through
``yukkuri birth_scene deliver <mother> <father> caught|missed`` and never left to chance.

Each yard is a fenced pad with a raised observation deck outside the fence; the visitor is teleported
onto the deck, never into the yard. Parents are authored at their actual starting places and thereafter
move, conceive, deliver, catch and nurse through ordinary server behaviour. The 32-block family
population budget is respected by separating the yards from the dense museum cast and from each other.
A pair delivers once; "换一对新油" on the deck removes this yard's family and installs a fresh pair.
"""
from museum_dsl import STAGE_Y, _text, _q, _label
import museum_sites

NS = 'mcylab'
OBJ = 'museum'
# yard id, forced branch, aquatic, x of the yard centre
YARDS = (('land_caught', 'caught', False, -96), ('land_missed', 'missed', False, -168),
         ('boat_caught', 'caught', True, -240), ('boat_missed', 'missed', True, -312))
TITLES = {'land_caught': ('陆地 · 接住', '父油摘帽接生'), 'land_missed': ('陆地 · 漏接', '无垫落地磕伤'),
          'boat_caught': ('船上 · 接住', '帽船上接生'), 'boat_missed': ('船上 · 漏接', '落到船底磕伤')}


def _actor(c, key):
    return f'@e[tag={c.tag(key)},limit=1]'


def _fn(name):
    return f'{NS}:stand/{name}'


def _near(x, z, radius=40):
    return f'@a[x={x},y={STAGE_Y},z={z},distance=..{radius}]'


def _say(x, z, text, color='yellow'):
    return f'tellraw {_near(x, z)} ' + _text(text, color)


def _loading(c, yid, x, z):
    """Remember exactly which chunk tickets this visit owns, including repeated clicks."""
    active = f'#bl_{yid}'
    load, release = [], []
    chunks = [(cx, cz) for cx in range((x - 8) // 16 * 16, (x + 12) // 16 * 16 + 1, 16)
              for cz in range((z - 8) // 16 * 16, (z + 8) // 16 * 16 + 1, 16)]
    for index, (cx, cz) in enumerate(chunks):
        saved = f'#bf_{yid}_{index}'
        load += [f'execute unless score {active} {OBJ} matches 1 store success score {saved} {OBJ} run forceload query {cx} {cz}',
                 f'forceload add {cx} {cz}']
        release.append(f'execute if score {active} {OBJ} matches 1 unless score {saved} {OBJ} matches 1 run forceload remove {cx} {cz}')
    load.append(f'scoreboard players set {active} {OBJ} 1')
    release.append(f'scoreboard players set {active} {OBJ} 0')
    c.function(f'{yid}_load', load)
    c.function(f'{yid}_release', release)


def _food(c, yid, x, z, aquatic, initial=False):
    """Every offered portion is a real stack; only an explicit operator action adds food."""
    tag = f'pm_birth_{yid}_food'
    common = f'"pm_supply","room_birth","{tag}"'
    if aquatic:
        common += ',"pm_aquatic_supply"'
    result = [f'summon minecraft:item {x - 2.0} {STAGE_Y + .05} {z + 4.0 if aquatic else z + 1.5} '
              f'{{Tags:[{common}],PickupDelay:0s,Motion:[0d,0d,0d],Item:{{id:"minecraft:apple",count:{2 if initial else 4}}}}}']
    if aquatic and initial:
        result.append(f'summon minecraft:item {x - 2.0} {STAGE_Y + .05} {z + 4.0} '
                      f'{{Tags:[{common}],PickupDelay:0s,Motion:[0d,0d,0d],Item:{{id:"minecraft:stick",count:1}}}}')
    return result


def _court(c, yid, outcome, x, z, aquatic):
    """Author a finite supported enclosure, an observation deck and the same two persistent adult identities."""
    c.regions.append((x - 10, z - 9, x + 13, z + 9))
    c.prop(f'forceload add {x - 8} {z - 7} {x + 12} {z + 7}')
    c.prop(f'fill {x - 8} {STAGE_Y - 2} {z - 7} {x + 8} {STAGE_Y - 2} {z + 7} minecraft:dirt')
    c.prop(f'fill {x - 8} {STAGE_Y - 1} {z - 7} {x + 8} {STAGE_Y - 1} {z + 7} minecraft:grass_block')
    ring = [(px, pz) for px in range(x - 8, x + 9) for pz in range(z - 7, z + 8) if px in (x - 8, x + 8) or pz in (z - 7, z + 7)]
    c.props.extend(museum_sites.fence_ring(ring, STAGE_Y))
    for px in (x - 7, x + 7):
        for pz in (z - 6, z + 6):
            c.prop(f'setblock {px} {STAGE_Y - 1} {pz} minecraft:sea_lantern')
    kind = '水生' if aquatic else ''
    word = '接生' if outcome == 'caught' else '漏接'
    mother, father = f'{yid}_mother', f'{yid}_father'
    c.actor(mother, 'marisa' if aquatic else 'reimu', 1, 3, 'R', f'{kind}{word}庭院母油', aquatic=aquatic)
    c.actor(father, 'marisa', 4, 3, 'L', f'{kind}{word}庭院父油', aquatic=aquatic)
    c.actors[mother].update(x=x - 2.5, y=STAGE_Y, z=z + 3.5 if aquatic else z + .5, yaw=-90, installable=True)
    c.actors[father].update(x=x + 1.5 if aquatic else x + 2.5, y=STAGE_Y, z=z + 3.5 if aquatic else z + .5, yaw=90, installable=True)
    if aquatic:
        c.prop(f'fill {x - 5} {STAGE_Y - 1} {z - 4} {x + 4} {STAGE_Y - 1} {z + 2} minecraft:water')
    else:
        # YukkuriLife.entrance requires a dry, covered adjacent standing block.
        # A high pavilion leaves the full birth/catching arc unobstructed below it.
        c.prop(f'fill {x - 5} {STAGE_Y + 4} {z - 3} {x} {STAGE_Y + 4} {z + 2} minecraft:oak_planks')
        for px in (x - 5, x):
            for pz in (z - 3, z + 2):
                c.prop(f'fill {px} {STAGE_Y} {pz} {px} {STAGE_Y + 3} {pz} minecraft:oak_log')
        c.prop(f'setblock {x - 3} {STAGE_Y} {z - 1} mcyukkuri:yukkuri_nest')
    title, sub = TITLES[yid]
    c.prop(museum_sites.display(x, STAGE_Y + 5.2, z, [(title + '\n', 'gold', True), (sub, 'white')], 1.0, 'banner', yid))
    _loading(c, yid, x, z)
    # deck outside the east fence: three planks wide, level with the visitor's eyes above the fence
    c.prop(f'fill {x + 9} {STAGE_Y - 2} {z - 4} {x + 11} {STAGE_Y - 2} {z + 4} minecraft:dirt')
    deck_lines, land = museum_sites.deck(x + 9, z - 4, x + 11, z + 4, STAGE_Y + 2, 'west', yid)
    c.props.extend(deck_lines)
    c.props += museum_sites.deck_controls(c, x + 10, STAGE_Y + 2, z, [
        (f'function {_fn(yid + "_renew")}', '换一对新油', '清空本庭院重来'),
        (f'function {_fn(yid + "_food")}', '补四份口粮', '只补食物')])
    _renew(c, yid, x, z, mother, father)
    return mother, father, land


def _renew(c, yid, x, z, mother, father):
    """Operator button on the deck: remove this yard's own family, hat and left-overs, then install a fresh pair.

    A real family is never rewound; this ends it (the ordinary death and ledger paths run) and starts another one, so the
    station can show a catch or a miss again. Only the yard's own box is touched.
    """
    box = f'x={x - 8},y={STAGE_Y - 2},z={z - 7},dx=16,dy=10,dz=14'
    lines = [f'schedule clear {_fn(yid + "_begin")}', f'schedule clear {_fn(yid + "_deliver")}']
    lines += [f'kill @e[type={kind},{box}]' for kind in
              ('mcyukkuri:reimu', 'mcyukkuri:marisa', 'mcyukkuri:hat_boat', 'minecraft:item', 'mcyukkuri:yukkuri_remains')]
    lines += [f'scoreboard players set #actor_birth_{key} {OBJ} 0' for key in (mother, father)]
    lines += [f'scoreboard players set #done_{yid} {OBJ} 0',
              f'function {NS}:install_new_actors',
              _say(x, z, '本庭院已换成一对新油（旧的一家、帽子和地上物品已清空）；回展台再按这个庭院的按钮开始。', 'aqua')]
    c.function(f'{yid}_renew', lines)


def _delivery(c, yid, outcome, x, z, aquatic, mother, father, land):
    score = f'#birth_{yid}'
    m, f = _actor(c, mother), _actor(c, father)
    refusal = _say(x, z, '未开始：庭院角色或实际窝位不可用；已有胎次、恢复期及真实物资不会被重置。要重来请到观察台按“换一对新油”。', 'red')
    begin = [f'execute unless loaded {x} {STAGE_Y} {z} run return fail',
             f'function {NS}:install_new_actors',
             f'execute unless entity {m} run return run {refusal}',
             f'execute unless entity {f} run return run {refusal}']
    if aquatic:
        # Supplies are seeded once per real pair, never recreated to refill an already afloat hat.
        supplied = f'pm_birth_{yid}_supply'
        for line in _food(c, yid, x, z, True, initial=True):
            begin.append(f'execute unless entity @e[tag={c.tag(mother)},tag={supplied}] run {line}')
        begin += [f'tag {m} add {supplied}',
                  f'execute unless entity @e[tag={c.tag(mother)},tag=pm_museum_aquatic] store result score {score} {OBJ} run '
                  f'yukkuri museum aquatic birth {m} {f} {x - 3} {STAGE_Y} {z + 3} {x - 3} {STAGE_Y - 1} {z + 2}',
                  f'execute unless entity @e[tag={c.tag(mother)},tag=pm_museum_aquatic] run return run {refusal}']
    else:
        for key in (mother, father):
            begin += [f'execute store result score {score} {OBJ} run yukkuri museum live {_actor(c, key)} observe',
                      f'execute unless score {score} {OBJ} matches 1 run return run {refusal}']
        begin += [f'execute store result score {score} {OBJ} run yukkuri museum home {m} {x - 3} {STAGE_Y} {z - 1}',
                  f'execute unless score {score} {OBJ} matches 1 run return run {refusal}']
    # Let physical support and boat waterline update before the genuine family preconditions run.
    begin.append(f'schedule function {_fn(yid + "_deliver")} 20t replace')
    c.function(f'{yid}_begin', begin)
    done = f'#done_{yid}'
    if outcome == 'caught':
        look = ('看点：父油会察觉临产、走到母油面前摘下自己的帽子翻面放好，新生儿落进帽里；之后放出幼体、戴回帽子。'
                '再往后：先舔舐促进首便，再喂糜。聊天栏的灰色“[接生]”行会报告服务器实际走到的每一步。')
    else:
        look = ('看点：父油来不及，没有帽子垫底，新生儿直接落地、弹一下、磕伤哭泣；父母惊慌赶来蹭脸舔舐安抚，伤口按既有规则处理（不致命）。'
                '聊天栏的灰色“[接生]”行会报告实际发生的每一步。')
    c.function(f'{yid}_deliver', [
        # The reason for a refusal is sent to the players by the command itself; this line only says nothing started.
        f'execute store result score {score} {OBJ} run yukkuri birth_scene deliver {m} {f} {outcome}',
        f'execute unless score {score} {OBJ} matches 1 run return run {_say(x, z, "没有开始接生（原因见上一行红字）。条件满足后回展台重按这个庭院的按钮，不会重复触发已开始的胎次。", "red")}',
        f'scoreboard players set {done} {OBJ} 1',
        _say(x, z, look, 'aqua'),
        _say(x, z, '这是这一对双亲唯一的真实胎次：留在观察台看后续照护（约 1–2 分钟出生）；要再看一次，请按“换一对新油”。')])
    # These are deliberate action buttons, not timed demos: there is no automatic stop or teleport.
    title, sub = TITLES[yid]
    box = f'x={x - 8},y={STAGE_Y - 2},z={z - 7},dx=16,dy=10,dz=14'
    c.function(f'{yid}_visit', [
        f'function {_fn(yid + "_load")}',
        f'tp @s {land[0]} {land[1]} {land[2]} {land[3]} 15',
        f'tellraw @s ' + _text(f'观察台：{title}（{sub}）。看点见聊天栏；回展台、回大厅、换新油的按钮在台上。', 'aqua'),
        # Pressing again only brings the visitor back; a pair that has already delivered is never started twice.
        f'execute if score {done} {OBJ} matches 1 run return run tellraw @s ' + _text('这个庭院已经开始过接生：继续观察后续照护。要再看一次请按“换一对新油”。', 'yellow'),
        # First entry only: sweep up stray items that are not this yard's own ration.
        f'kill @e[type=minecraft:item,{box},tag=!pm_birth_{yid}_food]',
        f'schedule function {_fn(yid + "_begin")} 20t replace'])
    c.button(title, sub, f'{yid}_visit', nav=True)


def configure(c):
    if c.sid != 'birth':
        return
    # Discard only obsolete authoring definitions, never live world entities or their descendants.
    c.actors.clear(); c.demos.clear(); c.buttons.clear(); c.functions.clear()
    c.props.clear(); c.stop_before.clear(); c.stop_extra.clear()
    c.water = False
    c.kind = 'portal'
    c.batch = False
    c.yard_cast = True
    c.block(5, 3, 'mcyukkuri:yukkuri_nest')
    c.item('minecraft:leather_helmet', 3, 3, 1.1, .8)
    c.item('minecraft:leather_helmet', 7.5, 3, 1.1, .8)
    z = c.z0 + 16
    yards = []
    for yid, outcome, aquatic, x in YARDS:
        mother, father, land = _court(c, yid, outcome, x, z, aquatic)
        _delivery(c, yid, outcome, x, z, aquatic, mother, father, land)
        yards.append((yid, x, aquatic))
        c.sites.append(dict(id=yid, caption=TITLES[yid][0], hint=TITLES[yid][1], land=land, visit=f'{yid}_visit', release=f'{yid}_release',
                            button_done=True))
    pending = f'#food_{c.sid}'
    request = [f'execute if score {pending} {OBJ} matches 1 run return 0',
               f'scoreboard players set {pending} {OBJ} 1']
    food = [f'tellraw @a[x={yards[0][1]},y={STAGE_Y},z={z},distance=..400] ' + _text('各已加载庭院补入四份真实口粮；只补食物，不改饥饿值、胎次或照护成果。', 'green')]
    releases = []
    chunk_index = 0
    for yid, x, aquatic in yards:
        for cx in range((x - 8) // 16 * 16, (x + 12) // 16 * 16 + 1, 16):
            for cz in range((z - 7) // 16 * 16, (z + 7) // 16 * 16 + 1, 16):
                saved = f'#food_{c.sid}_{chunk_index}'
                request += [f'execute store success score {saved} {OBJ} run forceload query {cx} {cz}',
                            f'forceload add {cx} {cz}']
                releases.append(f'execute unless score {saved} {OBJ} matches 1 run forceload remove {cx} {cz}')
                chunk_index += 1
        food.append(f'execute if loaded {x} {STAGE_Y} {z} run function {_fn(yid + "_food")}')
        c.function(f'{yid}_food', _food(c, yid, x, z, aquatic))
    food += releases + [f'scoreboard players set {pending} {OBJ} 0']
    request.append(f'schedule function {_fn("birth_feed_loaded")} 20t replace')
    c.function('birth_feed_real', request)
    c.function('birth_feed_loaded', food)
    c.button('补口粮', '四个庭院各四份', 'birth_feed_real')
    # Cancel only pending requests; a committed birth remains owned by FamilyData.
    for yid, _, _ in yards:
        c.stop_before += [f'schedule clear {_fn(yid + "_begin")}', f'schedule clear {_fn(yid + "_deliver")}']
