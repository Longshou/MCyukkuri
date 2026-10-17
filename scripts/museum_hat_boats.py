"""Physical hat boats for the scripted aquatic stands (no autonomous family simulation).

The actors still play authored clips, but water, hulls and passenger seats are real.
Birth scenes own their own hats; clear these props before handing control to them.
"""
import json
import math
from pathlib import Path

from museum_dsl import GROWTH, STAGE_Y

HAT_DATA = json.loads((Path(__file__).resolve().parents[1] /
                       'src/main/resources/assets/mcyukkuri/yukkuri_presentation/hat_vessel.json')
                      .read_text(encoding='utf-8'))
HAT_SCALE = HAT_DATA['aquatic_scale']
SURFACE_Y = round(STAGE_Y - 1 / 9 + .025, 6)  # source-water height + HatBoatEntity's waterline


def configure(c):
    if not c.water:
        return

    # Keep the existing bank overrides above this water layer.
    previous = c.props[:]
    c.props.clear()
    c.fill_cells(1, 1, 9, 4, 'minecraft:water', dy=-1)
    water_floor = c.props[:]
    c.props.extend(previous)
    if c.sid == 'boatlife':
        c.fill_cells(0, 1, 1, 5, 'minecraft:smooth_quartz', dy=-1)

    def actor(key):
        return f'@e[tag={c.tag(key)},limit=1]'

    def hull(key):
        return f'@e[tag={c.tag(key)}_hat,limit=1]'

    def wear(key, value):
        return f'data modify entity {actor(key)} YukkuriLife.Accessory set value {int(value)}b'

    def board(key, owner):
        return [f'ride {actor(key)} mount {hull(owner)}']

    def boat(key, mount=True, pose=None):
        a = c.actors[key]
        x, y, z, yaw = pose or (a['x'], SURFACE_Y, a['z'], a['yaw'])
        scale = HAT_SCALE * (GROWTH[a['stage']][2] if a['stage'] in GROWTH else 1)
        out = [f'summon mcyukkuri:hat_boat {x} {y} {z} '
               f'{{Tags:["pm_scene_hat","pm_{c.sid}_hat","{c.tag(key)}_hat"],'
               f'Invulnerable:1b,NoGravity:1b,State:"AFLOAT",Role:"SELF",HullScale:{scale}f,'
               f'Rotation:[{yaw}f,0f],Cargo:{{Paddle:1b}}}}',
               f'data modify entity {hull(key)} OwnerYukkuri set from entity {actor(key)} UUID',
               wear(key, False)]
        if mount:
            out += board(key, key)
        return out

    aquatic = [key for key, a in c.actors.items() if a['aquatic']]
    visible = [key for key in aquatic if not c.actors[key]['hidden']]
    if c.sid == 'boatfamily':
        visible.remove('help')  # the helper waits on the dry front bank
    cleanup = [f'execute as @e[tag=room_{c.sid}] run ride @s dismount',
               f'kill @e[tag=pm_{c.sid}_hat]']
    for key in aquatic:
        cleanup += [wear(key, True), f'data modify entity {actor(key)} NoGravity set value 0b']
    # Run after any server-driven birth has stopped, but before home teleports.
    c.hat_cleanup = cleanup
    # Stops, reloads and first entry all leave the water exhibits aboard their hats.
    # Also replace the old glass surface when an existing museum reloads this pack.
    c.stop_extra.extend(water_floor + [line for line in c.props if line.startswith('fill ') and 'minecraft:smooth_quartz' in line])
    c.stop_extra.extend(line for key in visible for line in boat(key))
    c.function(f'{c.sid}_clear_hats', cleanup)

    def raw(lines):
        return [c.raw(line) for line in lines]

    def seat(key, x, z):
        return f'yukkuri museum seat {actor(key)} {x} {z}'

    def at(u, v, y, d):
        x, z = c.xz(u, v)
        return (x, y, z, c.yaw(d))

    def teleport(selector, point):
        return 'tp ' + selector + ' ' + ' '.join(str(round(value, 6)) for value in point) + ' 0'

    def travel(delay, commands, moves, finish=()):
        """One running clip, continuous four-tick position samples; never restart the pose per sample."""
        count = max(2, math.ceil(delay / 4))
        result = []
        for frame in range(count):
            q = frame / (count - 1)
            lines = []
            for selector, start, end, lift in moves:
                point = [a + (b - a) * q for a, b in zip(start, end)]
                point[1] += 4 * lift * q * (1 - q)
                lines.append(teleport(selector, point))
            result.append((min(4, delay - frame * 4), (commands if frame == 0 else []) + raw(lines)
                           + (raw(finish) if frame == count - 1 else [])))
        return result

    unit = HAT_SCALE / 16
    rim = HAT_DATA['brim'][1] * unit
    ledge_y = SURFACE_Y + HAT_DATA['bowl']['owner_y'] * unit
    brim_y = SURFACE_Y + HAT_DATA['bowl']['freeboard'] * unit
    child_scale = GROWTH[c.actors['kid']['stage']][2] if 'kid' in c.actors else .18
    mouth_reach = 5.215 / 16 * child_scale
    mouth_y = 2.0034 / 16 * child_scale

    def water(key):
        a = c.actors[key]
        return [f'ride {actor(key)} dismount',
                f'tp {actor(key)} {a["x"]} {STAGE_Y - .25} {a["z"]} {a["yaw"]} 0',
                f'data modify entity {actor(key)} NoGravity set value 1b']

    demos = []
    for did, caption, steps in c.demos:
        # The paired birth controller creates and removes its own hats.
        birth = any('yukkuri birth_scene start ' in cmd[3] for _, cmds in steps for cmd in cmds)
        setup = [f'function mcylab:stand/{c.sid}_clear_hats']
        if birth:
            # stop() left actors on seats: restore their authored starting locations.
            setup += ['tp ' + c.sel(key) + ' {} {} {} {} 0'.format(*c.home(key)) for key in c.actors]
        elif c.sid == 'waterside' and did == 'self':
            # Taking one's hat off/on starts on the actual dry bank.
            setup.append(c.TP('aq', 2, 1, 'R')[3])
        elif c.sid == 'boatlife' and did == 'hat':
            setup.append(c.TP('sailor', 1, 3, 'F')[3])
        elif c.sid == 'voyage':
            setup += boat('dad') + board('kid', 'dad') + [seat('kid', 0, -8)]
        elif c.sid == 'rescue':
            setup += boat('mom')
            setup += board('kid', 'mom') if did in ('climb', 'rock') else water('kid')
        else:
            setup += [line for key in visible for line in boat(key)]

        fixed = []
        for i, (delay, commands) in enumerate(steps):
            before, after = [], []
            moves = []
            actions = {(key, name) for kind, key, name, _ in commands if kind == 'action'}
            if c.sid == 'boatfamily' and not birth and any(cmd[3].startswith('tp ' + c.sel('baby') + ' ') for cmd in commands):
                after += board('baby', 'mom') + [seat('mom', 0, 1), seat('baby', 0, -4.25)]
            if c.sid == 'boatlife' and did == 'work':
                if ('sailor', 'boat_gather') in actions:
                    # Approach the bank before starting the gathering clip.
                    fixed += travel(64, [c.A('sailor', 'boat_paddle'), c.FACE('sailor', 'L')],
                                    [(hull('sailor'), at(5.5, 3, SURFACE_Y, 'L'), at(2.95, 3, SURFACE_Y, 'L'), 0)])
                    before += [c.FACE('sailor', 'L')[3]]
                if ('sailor', 'boat_paddle') in actions:
                    before += [c.FACE('sailor', 'R')[3]]
                    moves += [(hull('sailor'), at(2.95, 3, SURFACE_Y, 'R'), at(5.5, 3, SURFACE_Y, 'R'), 0)]
            if c.sid == 'voyage':
                if ('kid', 'boat_board') in actions:
                    before += [f'ride {actor("kid")} dismount', f'data modify entity {actor("kid")} NoGravity set value 1b']
                    before += boat('kid', mount=False, pose=at(4.4, 3, SURFACE_Y, 'R'))
                    own_rear = HAT_DATA['opening'][3] * unit * child_scale - 5.9 / 16 * child_scale
                    moves += [(actor('kid'), at(3 + 8 * unit, 3, brim_y, 'R'),
                               at(4.4 - own_rear, 3, SURFACE_Y + HAT_DATA['bowl']['owner_y'] * unit * child_scale, 'R'), .15)]
                    after += board('kid', 'kid')
                if ('kid', 'boat_paddle') in actions:
                    moves += [(hull('kid'), at(4.4, 3, SURFACE_Y, 'R'), at(7, 3, SURFACE_Y, 'R'), 0)]
                if ('kid', 'hat_on_self_aq') in actions:
                    fixed += travel(80, [c.A('kid', 'boat_paddle'), c.FACE('kid', 'L')],
                                    [(hull('kid'), at(7, 3, SURFACE_Y, 'L'), at(4.4, 3, SURFACE_Y, 'L'), 0)])
                    before += [f'ride {actor("kid")} dismount', f'data modify entity {actor("kid")} NoGravity set value 1b']
                    moves += [(actor('kid'), at(4.4, 3, ledge_y, 'L'), at(3 + 8 * unit, 3, brim_y, 'L'), .18)]
                    after += [f'kill {hull("kid")}', wear('kid', True)] + board('kid', 'dad') + [seat('kid', 0, -8)]
            if c.sid == 'rescue':
                parent_u = 8.2 - rim - mouth_reach if did == 'braid' else 4
                self_u = 8.2 - rim * child_scale - mouth_reach
                owner = 'kid' if did == 'self' else 'mom'
                center = self_u if did == 'self' else parent_u
                reach = rim * child_scale if did == 'self' else rim
                bite = at(center + reach + mouth_reach, 3,
                          SURFACE_Y + HAT_DATA['bowl']['freeboard'] * unit * (child_scale if did == 'self' else 1) - mouth_y, 'L')
                if i == 0 and did == 'climb':
                    before += [seat('kid', 0, -4.4)]
                if ('kid', 'hat_hop_up') in actions:
                    before += [f'ride {actor("kid")} dismount', f'data modify entity {actor("kid")} NoGravity set value 1b']
                    moves += [(actor('kid'), at(4 + 4.4 * unit, 3, ledge_y, 'R'), at(4 + 8 * unit, 3, brim_y, 'R'), .2)]
                    after += board('kid', 'mom') + [seat('kid', 0, -8)]
                if ('kid', 'water_plunge') in actions:
                    before += water('kid')
                    if did == 'climb':
                        moves += [(actor('kid'), at(4 + 8 * unit, 3, brim_y, 'R'), at(4 + rim + .3, 3, STAGE_Y - .25, 'L'), .1)]
                    else:
                        before += [teleport(actor('kid'), at(8.2, 3, STAGE_Y - .25, 'L'))]
                if did == 'braid' and ('mom', 'boat_paddle') in actions:
                    moves += [(hull('mom'), at(4, 3, SURFACE_Y, 'R'), at(parent_u, 3, SURFACE_Y, 'R'), 0)]
                if did == 'climb' and ('kid', 'water_struggle') in actions:
                    moves += [(actor('kid'), at(4 + rim + .3, 3, STAGE_Y - .25, 'L'), bite, 0)]
                if did == 'braid' and ('mom', 'hat_brim_offer') in actions:
                    moves += [(actor('kid'), at(8.2, 3, STAGE_Y - .25, 'L'), bite, 0)]
                if did == 'self' and ('kid', 'water_mouth_grip') in actions:
                    before += boat('kid', mount=False, pose=at(self_u, 3, SURFACE_Y, 'R'))
                    before += [f'data modify entity {hull("kid")} State set value "CAPSIZED"', teleport(actor('kid'), bite)]
                if did == 'self' and ('kid', 'water_bob_lunge') in actions:
                    before += [f'data modify entity {hull("kid")} State set value "AFLOAT"']
                if ('kid', 'water_recover') in actions:
                    before += board('kid', owner)
                if ('mom', 'boat_nudge_young') in actions:
                    before += [seat('mom', 0, -5.5)]
                if ('kid', 'hat_slide_over') in actions:
                    # Cross the raised brim before mounting, so riding cannot snap the child through it.
                    end = at(center + (0 if did == 'self' else 4.4 * unit), 3,
                             SURFACE_Y + HAT_DATA['bowl']['owner_y'] * unit * child_scale if did == 'self' else ledge_y, 'L')
                    moves += [(actor('kid'), bite, end, .22)]
                    after += board('kid', owner)
                    if did != 'self':
                        after += [seat('mom', 0, 1), seat('kid', 0, -4.4)]
                if ('mom', 'boat_nuzzle') in actions:
                    before += [seat('mom', 0, 1), seat('kid', 0, -5.1)]
                    after += [c.CONTACT('mom', 'kid')[3]]
                if ('mom', 'boat_flee') in actions:
                    before += [c.FACE('mom', 'L')[3]]
                    moves += [(hull('mom'), at(4, 3, SURFACE_Y, 'L'), at(2.5, 3, SURFACE_Y, 'L'), 0)]
            combined = (raw(setup) if i == 0 else []) + raw(before) + commands
            if moves:
                fixed += travel(delay, combined, moves, after)
            else:
                fixed.append((delay, combined + raw(after)))
        demos.append((did, caption, fixed))
    c.demos = demos
