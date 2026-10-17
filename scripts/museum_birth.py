"""Real family births in isolated observation yards; no template infants or timed rewind.

Call configure after museum_hat_boats.configure. The 32-block family population budget
is respected by separating these yards from the dense museum cast. Only the visitor
is teleported; parents are authored at their actual starting places and thereafter
move, conceive, deliver, catch and nurse through ordinary server behavior.
"""
from museum_dsl import STAGE_Y, _text, _q, _label

NS = 'mcylab'
OBJ = 'museum'
YARDS = {'birth': -96, 'hats': -256, 'boatfamily': -416}


def _actor(c, key):
    return f'@e[tag={c.tag(key)},limit=1]'


def _say(text, color='yellow'):
    return 'tellraw @a ' + _text(text, color)


def _fn(c, name):
    return f'{NS}:stand/{c.sid}_{name}'


def _loading(c, suffix, x, z):
    """Remember exactly which chunk tickets this visit owns, including repeated clicks."""
    active = f'#bl_{c.sid}_{suffix}'
    load, release = [], []
    chunks = [(cx, cz) for cx in range((x-8)//16*16, (x+8)//16*16+1, 16)
              for cz in range((z-7)//16*16, (z+7)//16*16+1, 16)]
    for index, (cx, cz) in enumerate(chunks):
        saved = f'#bf_{c.sid}_{suffix}_{index}'
        load += [f'execute unless score {active} {OBJ} matches 1 store success score {saved} {OBJ} run forceload query {cx} {cz}',
                 f'forceload add {cx} {cz}']
        release.append(f'execute if score {active} {OBJ} matches 1 unless score {saved} {OBJ} matches 1 run forceload remove {cx} {cz}')
    load.append(f'scoreboard players set {active} {OBJ} 1')
    release.append(f'scoreboard players set {active} {OBJ} 0')
    c.function(f'{c.sid}_{suffix}_load', load)
    c.function(f'{c.sid}_{suffix}_release', release)


def _court(c, suffix, x, z, aquatic):
    """Author a finite supported enclosure and the same two persistent adult identities."""
    c.prop(f'forceload add {x-8} {z-7} {x+8} {z+7}')
    c.prop(f'fill {x-8} {STAGE_Y-2} {z-7} {x+8} {STAGE_Y-2} {z+7} minecraft:dirt')
    c.prop(f'fill {x-8} {STAGE_Y-1} {z-7} {x+8} {STAGE_Y-1} {z+7} minecraft:grass_block')
    for px in range(x-8, x+9):
        for pz in range(z-7, z+8):
            if px not in (x-8, x+8) and pz not in (z-7, z+7):
                continue
            connections = []
            for name, dx, dz in (('east', 1, 0), ('west', -1, 0), ('south', 0, 1), ('north', 0, -1)):
                nx, nz = px+dx, pz+dz
                if x-8 <= nx <= x+8 and z-7 <= nz <= z+7 and (nx in (x-8, x+8) or nz in (z-7, z+7)):
                    connections.append(f'{name}=true')
            c.prop(f'setblock {px} {STAGE_Y} {pz} minecraft:oak_fence[{",".join(connections)}]')
    for px in (x-7, x+7):
        for pz in (z-6, z+6):
            c.prop(f'setblock {px} {STAGE_Y-1} {pz} minecraft:sea_lantern')
    mother, father = f'{suffix}_mother', f'{suffix}_father'
    c.actor(mother, 'marisa' if aquatic else 'reimu', 1, 3, 'R',
            f'{"水生" if aquatic else ""}{"接生" if suffix == "caught" else "漏接"}庭院母油', aquatic=aquatic)
    c.actor(father, 'marisa', 4, 3, 'L',
            f'{"水生" if aquatic else ""}{"接生" if suffix == "caught" else "漏接"}庭院父油', aquatic=aquatic)
    c.actors[mother].update(x=x-2.5, y=STAGE_Y, z=z+3.5 if aquatic else z+.5, yaw=-90, installable=True)
    c.actors[father].update(x=x+1.5 if aquatic else x+2.5, y=STAGE_Y, z=z+3.5 if aquatic else z+.5, yaw=90, installable=True)
    if aquatic:
        c.prop(f'fill {x-5} {STAGE_Y-1} {z-4} {x+4} {STAGE_Y-1} {z+2} minecraft:water')
    else:
        # YukkuriLife.entrance requires a dry, covered adjacent standing block.
        # A high pavilion leaves the full birth/catching arc unobstructed below it.
        c.prop(f'fill {x-5} {STAGE_Y+4} {z-3} {x} {STAGE_Y+4} {z+2} minecraft:oak_planks')
        for px in (x-5, x):
            for pz in (z-3, z+2):
                c.prop(f'fill {px} {STAGE_Y} {pz} {px} {STAGE_Y+3} {pz} minecraft:oak_log')
        c.prop(f'setblock {x-3} {STAGE_Y} {z-1} mcyukkuri:yukkuri_nest')
    _loading(c, suffix, x, z)
    back_x, back_z = c.xz(5.5, -4)
    c.function(f'{c.sid}_{suffix}_return', [f'tp @p {back_x} {STAGE_Y-1} {back_z} {c.yaw("B")} 0',
                                          f'function {_fn(c,suffix+"_release")}'])
    bx, bz = x+6, z+5
    c.prop(f'setblock {bx} {STAGE_Y-2} {bz} minecraft:command_block{{Command:{_q("function "+_fn(c,suffix+"_return"))},TrackOutput:0b}}')
    c.prop(f'setblock {bx} {STAGE_Y-1} {bz} minecraft:polished_andesite')
    c.prop(f'setblock {bx} {STAGE_Y} {bz} minecraft:stone_button[face=floor]')
    c.prop(_label(bx+.5, STAGE_Y+1.4, bz+.5, '返回展台（保留真实家庭）', .32))
    c.prop(f'setblock {bx-2} {STAGE_Y-2} {bz} minecraft:command_block{{Command:{_q("function "+_fn(c,suffix+"_food"))},TrackOutput:0b}}')
    c.prop(f'setblock {bx-2} {STAGE_Y-1} {bz} minecraft:polished_andesite')
    c.prop(f'setblock {bx-2} {STAGE_Y} {bz} minecraft:stone_button[face=floor]')
    c.prop(_label(bx-1.5, STAGE_Y+1.4, bz+.5, '补四份真实口粮', .28))
    c.prop(f'setblock {bx-4} {STAGE_Y-2} {bz} minecraft:command_block{{Command:{_q("function "+_fn(c,suffix+"_renew"))},TrackOutput:0b}}')
    c.prop(f'setblock {bx-4} {STAGE_Y-1} {bz} minecraft:polished_andesite')
    c.prop(f'setblock {bx-4} {STAGE_Y} {bz} minecraft:stone_button[face=floor]')
    c.prop(_label(bx-3.5, STAGE_Y+1.4, bz+.5, '换一对新油（清空本庭院）', .28))
    _renew(c, suffix, x, z, mother, father)
    return mother, father


def _renew(c, suffix, x, z, mother, father):
    """Operator button inside one yard: remove this yard's own family, hat and left-overs, then install a fresh pair.

    A real family is never rewound; this ends it (the ordinary death and ledger paths run) and starts another one, so the
    station can show a catch or a miss again. Only the yard's own box is touched.
    """
    box = f'x={x-8},y={STAGE_Y-2},z={z-7},dx=16,dy=10,dz=14'
    lines = [f'schedule clear {_fn(c, suffix+"_begin")}', f'schedule clear {_fn(c, suffix+"_deliver")}']
    lines += [f'kill @e[type={kind},{box}]' for kind in
              ('mcyukkuri:reimu', 'mcyukkuri:marisa', 'mcyukkuri:hat_boat', 'minecraft:item', 'mcyukkuri:yukkuri_remains')]
    lines += [f'scoreboard players set #actor_{c.sid}_{key} {OBJ} 0' for key in (mother, father)]
    lines += [f'scoreboard players set #done_{c.sid}_{suffix} {OBJ} 0', f'scoreboard players set #birth_{c.sid}_{suffix} {OBJ} 0',
              f'function {NS}:install_new_actors',
              _say('本庭院已换成一对新油（旧的一家、帽子和地上物品已清空）；再按展台上的按钮开始。', 'aqua')]
    c.function(f'{c.sid}_{suffix}_renew', lines)


def _food(c, suffix, x, z, aquatic, initial=False):
    """Every offered portion is a real stack; only an explicit operator action adds food."""
    tag = f'pm_{c.sid}_{suffix}_food'
    common = f'"pm_supply","room_{c.sid}","{tag}"'
    if aquatic:
        common += ',"pm_aquatic_supply"'
    result = [f'summon minecraft:item {x-2.0} {STAGE_Y+.05} {z+4.0 if aquatic else z+1.5} '
              f'{{Tags:[{common}],PickupDelay:0s,Motion:[0d,0d,0d],Item:{{id:"minecraft:apple",count:{2 if initial else 4}}}}}']
    if aquatic and initial:
        result.append(f'summon minecraft:item {x-2.0} {STAGE_Y+.05} {z+4.0} '
                      f'{{Tags:[{common}],PickupDelay:0s,Motion:[0d,0d,0d],Item:{{id:"minecraft:stick",count:1}}}}')
    return result


def _delivery(c, suffix, x, z, aquatic, mother, father):
    name = f'{suffix}_begin'
    score = f'#birth_{c.sid}_{suffix}'
    m, f = _actor(c, mother), _actor(c, father)
    refusal = _say('未开始：庭院角色或实际窝位不可用；已有胎次、恢复期及真实物资不会被重置。', 'red')
    begin = [f'execute if entity @e[tag=room_{c.sid},tag=pm_stop_failed] run return 0',
             f'execute unless loaded {x} {STAGE_Y} {z} run return fail',
             f'function {NS}:install_new_actors',
             f'execute unless entity {m} run return run {refusal}',
             f'execute unless entity {f} run return run {refusal}']
    if aquatic:
        # Supplies are seeded once per real pair, never recreated to refill an already afloat hat.
        supplied = f'pm_{c.sid}_{suffix}_birth_supply'
        for line in _food(c, suffix, x, z, True, initial=True):
            begin.append(f'execute unless entity @e[tag={c.tag(mother)},tag={supplied}] run {line}')
        begin += [f'tag {m} add {supplied}',
                  f'execute unless entity @e[tag={c.tag(mother)},tag=pm_museum_aquatic] store result score {score} {OBJ} run '
                  f'yukkuri museum aquatic birth {m} {f} {x-3} {STAGE_Y} {z+3} {x-3} {STAGE_Y-1} {z+2}',
                  f'execute unless entity @e[tag={c.tag(mother)},tag=pm_museum_aquatic] run return run {refusal}']
    else:
        for key in (mother, father):
            begin += [f'execute store result score {score} {OBJ} run yukkuri museum live {_actor(c,key)} observe',
                      f'execute unless score {score} {OBJ} matches 1 run return run {refusal}']
        begin += [f'execute store result score {score} {OBJ} run yukkuri museum home {m} {x-3} {STAGE_Y} {z-1}',
                  f'execute unless score {score} {OBJ} matches 1 run return run {refusal}']
    # Let physical support and boat waterline update before the genuine family preconditions run.
    begin.append(f'schedule function {_fn(c, suffix+"_deliver")} 20t replace')
    c.function(f'{c.sid}_{name}', begin)
    done = f'#done_{c.sid}_{suffix}'
    if suffix == 'caught':
        look = ('父油会察觉临产、走到母油面前摘下自己的帽子翻面放好，新生儿落进帽里；之后放出幼体、戴回帽子。'
                '再往后：先舔舐促进首便，再喂糜。屏幕聊天栏的灰色“[接生]”行会报告服务器实际走到的每一步。')
    else:
        look = ('父油不会及时察觉：没有帽子垫底，新生儿直接落地、弹一下、磕伤哭泣；父母惊慌赶来蹭脸舔舐安抚，伤口按既有规则处理（不致命）。'
                '屏幕聊天栏的灰色“[接生]”行会报告实际发生的每一步。')
    c.function(f'{c.sid}_{suffix}_deliver', [
        f'execute if entity @e[tag=room_{c.sid},tag=pm_stop_failed] run return 0',
        # The reason for a refusal is sent to the players by the command itself; this line only says nothing started.
        f'execute store result score {score} {OBJ} run yukkuri birth_scene deliver {m} {f} {suffix}',
        f'execute unless score {score} {OBJ} matches 1 run return run {_say("没有开始接生（原因见上一行红字）。条件满足后重按展台按钮即可，不会重复触发已开始的胎次。", "red")}',
        f'scoreboard players set {done} {OBJ} 1',
        _say(look, 'aqua'),
        _say('这是这一对双亲唯一的真实胎次：留在庭院观察；要再看一次，请在庭院内按“换一对新油”。')])
    # These are deliberate action buttons, not timed demos: there is no automatic stop/teleport.
    caption = '真实接生庭院' if suffix == 'caught' else '真实漏接庭院'
    warning = '父油摘帽接生；仍受碰撞影响' if suffix == 'caught' else '无垫漏接：新生儿落地磕伤（不致命）'
    box = f'x={x-8},y={STAGE_Y-2},z={z-7},dx=16,dy=10,dz=14'
    c.function(f'{c.sid}_{suffix}_visit', [
        f'function {_fn(c,suffix+"_load")}',
        f'tp @p {x+.5} {STAGE_Y} {z+5.5} 180 0', _say(warning),
        # Pressing again only brings the visitor back; a pair that has already delivered is never started twice.
        f'execute if score {done} {OBJ} matches 1 run return run {_say("这个庭院已经完成过一次接生，继续观察后续照护。要再看一次请在庭院内按“换一对新油”。", "yellow")}',
        # First entry only: sweep up stray items that are not this yard's own ration.
        f'kill @e[type=minecraft:item,{box},tag=!pm_{c.sid}_{suffix}_food]',
        f'schedule function {_fn(c,name)} 20t replace'])
    c.button(caption, warning, f'{c.sid}_{suffix}_visit')


def configure(c):
    if c.sid == 'waterside':
        # This stand belongs to shore visits. A terrestrial mother is never paired with aquatic Marisa.
        removed = {key for key in ('mom', 'baby', 'baby2') if key in c.actors and
                   (key != 'mom' or not c.actors[key]['aquatic'])}
        for key in removed:
            c.actors.pop(key)
        c.demos = [(did, caption, steps) for did, caption, steps in c.demos
                   if not any('birth_scene ' in command[3] or command[1] in removed
                              for _, commands in steps for command in commands)]
        c.stop_before = [line for line in c.stop_before if 'birth_scene ' not in line]
        return
    if c.sid not in YARDS:
        return
    aquatic = c.sid == 'boatfamily'
    # Discard only obsolete authoring definitions, never live world entities or their descendants.
    c.actors.clear(); c.demos.clear(); c.buttons.clear(); c.functions.clear()
    c.props.clear(); c.stop_before.clear(); c.stop_extra.clear()
    c.hat_cleanup = []
    c.water = False
    c.question = '两处真实家庭庭院：接住（父油摘帽接生）与漏接（无垫落地）；幼体不预放、不倒放。'
    c.label(5.5, 3.0, 1.5, '按钮前往真实出生庭院；留在庭院观察后续照护', .34)
    z = c.z0 + 16
    yards = []
    for suffix, x in (('caught', YARDS[c.sid]), ('missed', YARDS[c.sid]-72)):
        mother, father = _court(c, suffix, x, z, aquatic)
        _delivery(c, suffix, x, z, aquatic, mother, father)
        yards.append((suffix, x, mother, father))
    pending = f'#food_{c.sid}'
    request = [f'execute if score {pending} {OBJ} matches 1 run return 0',
               f'scoreboard players set {pending} {OBJ} 1']
    food = [_say('各已加载庭院补入四份真实口粮；只补食物，不改饥饿值、胎次或照护成果。')]
    releases = []
    chunk_index = 0
    for suffix, x, _, _ in yards:
        for cx in range((x-8)//16*16, (x+8)//16*16+1, 16):
            for cz in range((z-7)//16*16, (z+7)//16*16+1, 16):
                saved = f'#food_{c.sid}_{chunk_index}'
                request += [f'execute store success score {saved} {OBJ} run forceload query {cx} {cz}',
                            f'forceload add {cx} {cz}']
                releases.append(f'execute unless score {saved} {OBJ} matches 1 run forceload remove {cx} {cz}')
                chunk_index += 1
        food.append(f'execute if loaded {x} {STAGE_Y} {z} run function {_fn(c,suffix+"_food")}')
        c.function(f'{c.sid}_{suffix}_food', _food(c, suffix, x, z, aquatic))
    food += releases + [f'scoreboard players set {pending} {OBJ} 0']
    request.append(f'schedule function {_fn(c,"feed_loaded")} 20t replace')
    c.function(f'{c.sid}_feed_real', request)
    c.function(f'{c.sid}_feed_loaded', food)
    c.button('补真实口粮', '各庭院四份食物，不重置生命', f'{c.sid}_feed_real')
    x = YARDS[c.sid]
    c.function(f'{c.sid}_observe_real', [f'function {_fn(c,"caught_load")}',
                                       f'tp @p {x+.5} {STAGE_Y} {z+5.5} 180 0',
                                       _say('观察同一批真实幼体、帽托拾幼与亲油照护；本按钮不触发新胎次。')])
    c.button('观察真实幼体', '延续出生后的正常生活', f'{c.sid}_observe_real')
    # Cancel only pending requests; a committed birth remains owned by FamilyData.
    for suffix, _, _, _ in yards:
        c.stop_before += [f'schedule clear {_fn(c,suffix+"_begin")}',
                          f'schedule clear {_fn(c,suffix+"_deliver")}']
