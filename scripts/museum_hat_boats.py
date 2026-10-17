"""Physical hat boats for the scripted aquatic stands (no autonomous family simulation).

The actors still play authored clips, but water, hulls and passenger seats are real.
Birth scenes own their own hats; clear these props before handing control to them.
"""
import json
from pathlib import Path

from museum_dsl import GROWTH, STAGE_Y

HAT_SCALE = json.loads((Path(__file__).resolve().parents[1] /
                       'src/main/resources/assets/mcyukkuri/yukkuri_presentation/hat_vessel.json')
                      .read_text(encoding='utf-8'))['aquatic_scale']
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

    def boat(key, mount=True):
        a = c.actors[key]
        scale = HAT_SCALE * (GROWTH[a['stage']][2] if a['stage'] in GROWTH else 1)
        out = [f'summon mcyukkuri:hat_boat {a["x"]} {SURFACE_Y} {a["z"]} '
               f'{{Tags:["pm_scene_hat","pm_{c.sid}_hat","{c.tag(key)}_hat"],'
               f'Invulnerable:1b,NoGravity:1b,State:"AFLOAT",Role:"SELF",HullScale:{scale}f,'
               f'Rotation:[{a["yaw"]}f,0f],Cargo:{{Paddle:1b}}}}',
               f'data modify entity {hull(key)} OwnerYukkuri set from entity {actor(key)} UUID',
               wear(key, False)]
        if mount:
            out += board(key, key)
        return out

    aquatic = [key for key, a in c.actors.items() if a['aquatic']]
    visible = [key for key in aquatic if not c.actors[key]['hidden']]
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
            setup += boat('dad') + board('kid', 'dad')
        elif c.sid == 'rescue':
            setup += boat('mom')
            setup += board('kid', 'mom') if did in ('climb', 'rock') else water('kid')
        else:
            setup += [line for key in visible for line in boat(key)]

        fixed = []
        for i, (delay, commands) in enumerate(steps):
            before, after = [], []
            actions = {(key, name) for kind, key, name, _ in commands if kind == 'action'}
            if c.sid == 'boatfamily' and not birth and any(cmd[3].startswith('tp ' + c.sel('baby') + ' ') for cmd in commands):
                after += board('baby', 'mom')
            if c.sid == 'voyage':
                if ('kid', 'boat_board') in actions:
                    before += [f'ride {actor("kid")} dismount'] + boat('kid')
                if ('kid', 'hat_on_self_aq') in actions:
                    before += [f'ride {actor("kid")} dismount', f'kill {hull("kid")}', wear('kid', True)] + board('kid', 'dad')
            if c.sid == 'rescue':
                if ('kid', 'water_plunge') in actions:
                    before += water('kid')
                if did == 'self' and ('kid', 'water_mouth_grip') in actions:
                    before += boat('kid', mount=False)
                    before += [f'data modify entity {hull("kid")} State set value "CAPSIZED"']
                if did == 'self' and ('kid', 'water_bob_lunge') in actions:
                    before += [f'data modify entity {hull("kid")} State set value "AFLOAT"']
                if ('kid', 'water_recover') in actions:
                    before += board('kid', 'kid' if did == 'self' else 'mom')
            fixed.append((delay, (raw(setup) if i == 0 else []) + raw(before) + commands + raw(after)))
        demos.append((did, caption, fixed))
    c.demos = demos
