"""Build the full MCyukkuri showcase museum (Minecraft 1.21.1, NeoForge, Python 3.11+, nbtlib).

Five halls on one west corridor, 27 curated stands, scripted scenes that play real clips and real
dialogue events on fixed invulnerable actors, plus two real-behaviour corners (a hands-on transport pen
and an outdoor settlement meadow). Minecraft builds everything from the generated data pack on first
entry; this script only writes the pack, a seed level.dat (metadata only) and options.

    python scripts/build_museum_full.py --seed-level .local/p0/gametest/world/level.dat \
        --output .local/experimental-map/museum-full-YYYYMMDD [--catalog docs/MOTION_MUSEUM_ACTIONS.md]

No Java compilation and no UI automation. The output directory must be a new directory below a
``.local/experimental-map`` folder. Scene data lives in museum_scenes.py / museum_aquatic_rescue.py.
"""
from pathlib import Path
import argparse
import hashlib
import json
import math
import sys
import time

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(ROOT / '.local/setup/python'))
sys.path.insert(0, r'F:\yukkuri\.local\setup\python')
import nbtlib as n  # noqa: E402
from museum_actions import CATEGORIES, NAMES, MODELS, ACTION_META, load_clips  # noqa: E402
from museum_dsl import Ctx, summon_command, _text, _q, _label  # noqa: E402
import museum_scenes  # noqa: E402
import museum_hat_boats  # noqa: E402

NS = 'mcylab'
OBJ = 'museum'
WORLD = 'MCyukkuri-Museum'
SPAWN = (-6, 65, -5, 0)
HALL_PITCH = 40
SPINE_X0, SPINE_X1 = -11, 0
LOBBY_Z0 = -16


def js(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':'))


# ============================================================================== layout
class Hall:
    def __init__(self, index, hid, title, color, stands):
        self.index, self.id, self.title, self.color = index, hid, title, color
        self.z0 = index * HALL_PITCH
        self.k = max(2, math.ceil(len(stands) / 2))
        self.xe = 15 * self.k - 2
        self.stands = stands
        self.contexts = []


def slot(hall, i):
    """Clockwise: north row west to east, then south row east to west."""
    if i < hall.k:
        return 'N', 15 * i
    return 'S', 15 * (2 * hall.k - 1 - i)


def build_layout():
    halls, number = [], 1
    for index, (hid, title, color, stands) in enumerate(museum_scenes.HALLS):
        hall = Hall(index, hid, title, color, stands)
        for i, (sid, stand_title, question, stand_color, builder) in enumerate(stands):
            row, x0 = slot(hall, i)
            ctx = Ctx(sid, row, x0, hall.z0, stand_color)
            ctx.number, ctx.title, ctx.question, ctx.hall = number, stand_title, question, hall
            number += 1
            if sid == 'settle':
                ctx.meadow = (hall.xe + 18, 65, hall.z0 + 16)
            builder(ctx)
            museum_hat_boats.configure(ctx)
            hall.contexts.append(ctx)
        halls.append(hall)
    return halls


# ============================================================================== command helpers
def fill(x1, y1, z1, x2, y2, z2, block, mode=''):
    return f'fill {x1} {y1} {z1} {x2} {y2} {z2} {block}' + (' ' + mode if mode else '')


def big_fill(x1, y1, z1, x2, y2, z2, block, mode='', limit=30000):
    """Split along z so that every fill stays below the vanilla 32768 block limit."""
    per_slice = (x2 - x1 + 1) * (y2 - y1 + 1)
    step = max(1, limit // per_slice)
    out, z = [], z1
    while z <= z2:
        zz = min(z2, z + step - 1)
        out.append(fill(x1, y1, z, x2, y2, zz, block, mode))
        z = zz + 1
    return out


def sign_nbt(lines):
    lines = list(lines)
    assert len(lines) <= 4 and all(len(line) <= 10 for line in lines), lines
    content = '{messages:[' + ','.join(_q(_text(t, 'black')) for t in lines + [''] * (4 - len(lines))) + '],has_glowing_text:0b}'
    return f'{{front_text:{content},back_text:{content},is_waxed:1b}}'


def post_sign(x, y, z, lines, rotation):
    return f'setblock {x} {y} {z} minecraft:birch_sign[rotation={rotation}]{sign_nbt(lines)}'


CONTROLS = []   # (x, z) of every hidden command block


def control(x, z, command, caption, south=False, hint='右键播放'):
    """Hidden command block under the floor; a button on the block above powers it. Sign behind the button."""
    CONTROLS.append((x, z))
    return [f'setblock {x} 63 {z} minecraft:command_block{{Command:{_q(command)},TrackOutput:0b}}',
            f'setblock {x} 64 {z} minecraft:polished_andesite',
            f'setblock {x} 65 {z} minecraft:stone_button[face=floor]',
            post_sign(x, 65, z + (1 if south else -1), [caption, hint], 8 if south else 0)]


def control_side(x, z, command, caption, hint='右键传送'):
    """Same, for a button against the west wall of the corridor; the sign (facing east) sits behind it."""
    CONTROLS.append((x, z))
    return [f'setblock {x} 63 {z} minecraft:command_block{{Command:{_q(command)},TrackOutput:0b}}',
            f'setblock {x} 64 {z} minecraft:polished_andesite',
            f'setblock {x} 65 {z} minecraft:stone_button[face=floor]',
            post_sign(x - 1, 65, z, [caption, hint], 12)]


def ring_absolute(x1, z1, x2, z2, gate=None, y=65, gate_facing='west'):
    """Fence rectangle with explicit connections (fill/setblock do not connect fences)."""
    cells = {(x, z) for x in range(x1, x2 + 1) for z in range(z1, z2 + 1) if x in (x1, x2) or z in (z1, z2)}
    out = []
    for (x, z) in sorted(cells):
        if gate == (x, z):
            out.append(f'setblock {x} {y} {z} minecraft:oak_fence_gate[facing={gate_facing}]')
            continue
        flags = [f'{name}=true' for name, (dx, dz) in (('north', (0, -1)), ('south', (0, 1)), ('east', (1, 0)), ('west', (-1, 0)))
                 if (x + dx, z + dz) in cells]
        out.append(f'setblock {x} {y} {z} minecraft:oak_fence[{",".join(flags)}]')
    return out


# ============================================================================== guide book
def book_command(halls, shown):
    pages = ['油库里展馆\n\n一条西侧走廊，五个展厅，27 个展台。\n走近展台，右键地面按钮播放场景；\n每组展台旁都有“停止演示”。\n\n大厅西墙的按钮可直接传送到各厅。\n回大厅：/function mcylab:home\n停止全部：/function mcylab:stop_all']
    for hall in halls:
        lines = [f'{hall.id}厅 · {hall.title}', '']
        for ctx in hall.contexts:
            lines.append(f'{ctx.number:02d} {ctx.title}')
        extra = {'D': '\n尽头的“动手角”是真实的野生个体：领喷雾和篮，亲手试一试。', 'E': '\n“聚落”展台的草地在东门外，是真实的聚落生成。'}.get(hall.id, '')
        pages.append('\n'.join(lines) + extra)
    pages.append('说明\n\n大多数展台是“指令演出”：用 /yukkuri action 和 /yukkuri say 播放真实的动作与台词，不等于自然行为。\n\n动手角与聚落是真实游戏逻辑。\n\n水上救援三个场景仍在重做，之后会更新。')
    for group, entries in CATEGORIES:
        for start in range(0, len(entries), 7):
            pages.append(group + ' · 动作目录\n\n' + '\n'.join(('● ' if a in shown else '○ ') + name for a, name in entries[start:start + 7]) + '\n\n● 馆内演示  ○ 仅目录')
    items = ','.join(_q(js({'text': page})) for page in pages)
    return 'give @s minecraft:written_book[minecraft:written_book_content={title:"油库里展馆导览",author:"MCyukkuri",pages:[' + items + ']}]'


# ============================================================================== validation
def validate(halls):
    clips, _ = load_clips()
    catalog = json.loads((HERE.parent / 'src/main/resources/assets/mcyukkuri/dialogue/catalog.json').read_text(encoding='utf-8'))
    shown, speech = {'idle'}, set()
    for hall in halls:
        for ctx in hall.contexts:
            ids = set()
            for did, caption, steps in ctx.demos:
                assert did not in ids, (ctx.sid, did)
                ids.add(did)
                for step_index, (delay, cmds) in enumerate(steps):
                    action_keys = [cmd[1] for cmd in cmds if cmd[0] == 'action']
                    assert len(action_keys) == len(set(action_keys)), (ctx.sid, did, 'two actions overwrite one actor in the same step')
                    for kind, key, name, command in cmds:
                        if kind == 'contact':
                            peer, contact = name
                            assert key != peer and peer in ctx.actors and not ctx.actors[key]['human'] and not ctx.actors[peer]['human']
                            for actor, clip in ctx.contact_clips(key, peer, contact):
                                assert clip in clips[ctx.actors[actor]['model']], (ctx.sid, did, actor, clip)
                                shown.add(clip)
                            continue
                        if kind not in ('action', 'scene_action', 'say'):
                            continue
                        spec = ctx.actors[key]
                        assert not spec['human'], (ctx.sid, key)
                        model = spec['model']
                        if kind in ('action', 'scene_action'):
                            assert name in ACTION_META, name
                            assert name in clips[model], (ctx.sid, did, key, model, name)
                            if (name.startswith('boat_') or name.endswith('_aq')) and not name.startswith('boat_rock'):
                                assert spec['aquatic'] or name in ('boat_reassure', 'boat_alarm_call', 'boat_alarm_wait', 'boat_flee', 'boat_calm',
                                                                  'boat_answer_call'), (ctx.sid, did, key, name)
                            if name.startswith("braid_") or name in ("water_hat_flip", "water_hat_grip", "water_hat_climb", "hat_brim_offer", "hat_hop_up", "water_bob_lunge", "hat_slide_over", "water_mouth_grip", "water_hat_shake", "water_hat_push", "boat_nudge_young"):
                                assert model == 'marisa', (ctx.sid, did, key, name)
                            if kind == 'action' and not ACTION_META[name][1] and name != 'waddle':
                                available = delay
                                for later_delay, later in steps[step_index + 1:]:
                                    if any(k == 'action' and actor == key or k == 'contact' and (actor == key or value[0] == key)
                                           for k, actor, value, _ in later):
                                        break
                                    available += later_delay
                                assert available >= math.ceil(ACTION_META[name][0] * 20), (ctx.sid, did, key, name, available)
                            shown.add(name)
                        else:
                            age = 'adult' if spec['stage'] == 'adult' else 'young'
                            assert any(line['species'] == model.replace('house_', '') and line['age'] == age and name in line['events'] for line in catalog), \
                                (ctx.sid, did, key, model, age, name)
                            speech.add((ctx.sid, key, name))
    return shown, speech


# ============================================================================== datapack
def build_pack(world, halls, shown):
    root = world / 'datapacks' / 'mcylab'
    functions = root / 'data' / NS / 'function'

    def fn(name, lines):
        path = functions / (name + '.mcfunction')
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('\n'.join(lines) + '\n', encoding='utf-8')

    root.mkdir(parents=True, exist_ok=True)
    (root / 'pack.mcmeta').write_text(js({'pack': {'pack_format': 48, 'description': '油库里展馆 · 五厅'}}), encoding='utf-8')
    for kind in ('load', 'tick'):
        p = root / 'data/minecraft/tags/function' / f'{kind}.json'
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(js({'values': [f'{NS}:{kind}']}), encoding='utf-8')
    types = root / 'data' / NS / 'tags/entity_type/yukkuri.json'
    types.parent.mkdir(parents=True, exist_ok=True)
    types.write_text(js({'values': [f'mcyukkuri:{k}' for k in ('reimu', 'marisa', 'chen', 'alice', 'patchouli', 'remilia', 'flandre', 'house_reimu', 'house_marisa')]}),
                     encoding='utf-8')

    zlast = halls[-1].z0 + 31
    xmax = max(h.xe for h in halls) + 40
    forceload = f'forceload add -12 {LOBBY_Z0 - 1} {xmax} {zlast + 2}'

    fn('load', [f'scoreboard objectives add {OBJ} dummy', f'execute unless score #built {OBJ} matches 1 run function {NS}:prepare',
                # a save made in the middle of a scene: put every stand back once the nearby chunks are loaded
                f'execute if score #built {OBJ} matches 1 run schedule function {NS}:stop_all 5s replace',
                f'execute if score #built {OBJ} matches 1 run schedule function {NS}:stop_all_late 45s replace'])
    fn('stop_all_late', [f'function {NS}:stop_all'])
    prepare = [f'scoreboard players set #ready {OBJ} 0', forceload,
               'gamerule doMobSpawning false', 'gamerule doDaylightCycle false', 'gamerule doWeatherCycle false',
               'gamerule keepInventory true', 'gamerule commandBlockOutput false', 'gamerule sendCommandFeedback false',
               'gamerule doFireTick false', 'gamerule spawnRadius 0', 'difficulty peaceful', 'time set noon', 'weather clear',
               f'setworldspawn {SPAWN[0]} {SPAWN[1]} {SPAWN[2]} {SPAWN[3]}',
               # the lobby floor first, so the first player never drops into the unfinished map
               fill(SPINE_X0, 63, LOBBY_Z0, 0, 64, 20, 'minecraft:smooth_quartz'),
               f'schedule function {NS}:build/spine 2s replace']
    fn('prepare', prepare)
    fn('tick', [f'execute if score #ready {OBJ} matches 1 as @a[tag=!museum_visitor] run function {NS}:welcome',
                f'execute if score #ready {OBJ} matches 0 as @a[tag=!museum_waiting] run function {NS}:waiting'])
    fn('waiting', ['tag @s add museum_waiting', 'tellraw @s ' + _text('正在布置展馆（约 30 秒），请稍候……完成后自动传送到大厅。', 'yellow')])
    fn('welcome', ['tag @s add museum_visitor', 'gamemode adventure @s', f'tp @s {SPAWN[0] + .5} {SPAWN[1]} {SPAWN[2] + .5} {SPAWN[3]} 0', f'function {NS}:guide',
                   'tellraw @s ' + _text('欢迎入馆：大厅西墙的按钮可直接传送到各厅；走近展台，右键地面按钮播放场景。', 'aqua')])
    fn('guide', [book_command(halls, shown)])
    fn('home', [f'tp @s {SPAWN[0] + .5} {SPAWN[1]} {SPAWN[2] + .5} {SPAWN[3]} 0'])
    fn('noop', ['say noop'])

    # ---- spine (lobby + corridor)
    spine = []
    spine += big_fill(SPINE_X0 - 1, 65, LOBBY_Z0 - 1, 1, 74, zlast + 2, 'minecraft:air')
    spine += big_fill(SPINE_X0, 63, LOBBY_Z0, 0, 64, zlast + 1, 'minecraft:smooth_quartz')
    spine += big_fill(SPINE_X0, 65, LOBBY_Z0 - 1, SPINE_X0, 72, zlast + 1, 'minecraft:calcite')
    spine += big_fill(0, 65, LOBBY_Z0 - 1, 0, 72, zlast + 1, 'minecraft:calcite')
    spine += [fill(SPINE_X0, 65, LOBBY_Z0 - 1, 0, 72, LOBBY_Z0 - 1, 'minecraft:calcite'), fill(SPINE_X0, 65, zlast + 1, 0, 72, zlast + 1, 'minecraft:calcite'),
              fill(SPINE_X0, 72, LOBBY_Z0, 0, 72, zlast, 'minecraft:smooth_quartz')]
    spine += big_fill(SPINE_X0 + 3, 72, LOBBY_Z0, -3, 72, zlast, 'minecraft:glass')
    spine += big_fill(-7, 64, LOBBY_Z0, -5, 64, zlast, 'minecraft:polished_diorite')
    spine += big_fill(-6, 64, LOBBY_Z0, -6, 64, zlast, 'minecraft:yellow_terracotta')
    for z in range(LOBBY_Z0 + 3, zlast, 8):
        spine.append(f'setblock -5 72 {z} minecraft:sea_lantern')
    spine += [_label(-5.5, 70.2, -8, '油库里展馆\n五个展厅 · 27 个展台', 1.2),
              _label(-5.5, 68.6, -5.2, '站在这里：沿西墙的按钮可以直接去各厅\n（或沿走廊一路走下去）', .42)]
    # directory buttons along the west wall of the lobby (stop_all first: the audit pokes the first control)
    spine += control_side(-9, LOBBY_Z0 + 2, f'function {NS}:stop_all', '全部停止', '回到待机')
    for i, hall in enumerate(halls):
        spine += control_side(-9, LOBBY_Z0 + 3 + i, f'function {NS}:go/{hall.id.lower()}', f'{hall.id}厅', '右键传送')
    spine += control_side(-9, LOBBY_Z0 + 3 + len(halls), f'execute as @p run function {NS}:guide', '补领导览', '右键领取')
    for hall in halls:
        z = hall.z0
        # doorway into the hall, a large sign and previous/next buttons
        spine += [_label(-1.3, 69.4, z + 16, f'{hall.id}厅 · {hall.title}\n{len(hall.contexts)} 个展台', .8)]
        prev_target = 'lobby' if hall.index == 0 else halls[hall.index - 1].id.lower()
        spine += control_side(-9, z + 14, f'function {NS}:go/{prev_target}', '上一厅' if hall.index else '回大厅', '右键传送')
        if hall.index + 1 < len(halls):
            spine += control_side(-9, z + 16, f'function {NS}:go/{halls[hall.index + 1].id.lower()}', '下一厅', '右键传送')
        spine += control_side(-9, z + 18, f'function {NS}:go/lobby', '回大厅', '右键传送')
    spine += [f'schedule function {NS}:build/hall_{halls[0].id.lower()} 1s replace']
    fn('build/spine', spine)

    # ---- teleports
    fn('go/lobby', [f'tp @p {SPAWN[0] + .5} {SPAWN[1]} {SPAWN[2] + .5} {SPAWN[3]} 0'])
    for hall in halls:
        fn(f'go/{hall.id.lower()}', [f'tp @p 1.5 65 {hall.z0 + 16.5} -90 0', 'tellraw @p ' + _text(f'{hall.id}厅 · {hall.title}：顺时针看展台。', 'aqua')])

    # ---- halls
    all_demos, speakers, step_total = [], {}, 0
    badge_actors = set()
    for hall in halls:
        z = hall.z0
        xe = hall.xe
        lines = []
        lines += [fill(0, 65, z, xe, 74, z + 31, 'minecraft:air'), fill(0, 63, z, xe, 64, z + 31, 'minecraft:smooth_quartz'),
                  fill(0, 65, z, xe, 72, z + 31, 'minecraft:calcite', 'outline'), fill(1, 65, z + 1, xe - 1, 71, z + 30, 'minecraft:air'),
                  fill(1, 72, z + 1, xe - 1, 72, z + 30, 'minecraft:smooth_quartz'), fill(1, 72, z + 13, xe - 1, 72, z + 18, 'minecraft:glass'),
                  fill(0, 65, z + 14, 0, 68, z + 18, 'minecraft:air'), fill(1, 64, z + 13, xe - 1, 64, z + 18, 'minecraft:polished_diorite'),
                  fill(2, 64, z + 15, xe - 2, 64, z + 16, f'minecraft:{hall.color}_terracotta'),
                  _label(3, 69.8, z + 16, f'{hall.id}厅 · {hall.title}', .65), _label(xe - 8, 69.5, z + 16, '沿走廊顺时针参观', .55)]
        for col in range(1, hall.k):
            x = 15 * col - 1
            for z1, z2 in ((1, 11), (20, 30)):
                lines.append(fill(x, 65, z + z1, x, 69, z + z2, 'minecraft:calcite'))
        for col in range(hall.k):
            for lx in (4, 11):
                lines += [f'setblock {15 * col + lx} 72 {z + 7} minecraft:sea_lantern', f'setblock {15 * col + lx} 72 {z + 25} minecraft:sea_lantern']
        if hall.id == 'E':   # east gate to the settlement meadow
            lines.append(fill(xe, 65, z + 14, xe, 68, z + 18, 'minecraft:air'))
        for ctx in hall.contexts:
            south = ctx.row == 'S'
            x = ctx.x0
            z1, z2 = (24, 29) if south else (2, 7)
            front = z + (21 if south else 10)
            stage_edge = z + (z1 if south else z2)
            lines += [fill(x + 2, 65, z + z1, x + 12, 65, z + z2, 'minecraft:cyan_stained_glass' if ctx.water else 'minecraft:smooth_quartz')]
            if ctx.water:
                lines.append(fill(x + 2, 64, z + z1, x + 12, 64, z + z2, 'minecraft:light_blue_concrete'))
            lines += [fill(x + 2, 65, stage_edge, x + 12, 65, stage_edge, f'minecraft:{ctx.color}_concrete'),
                      _label(x + 7, 69.5, z + (25 if south else 6), f'{ctx.number:02d} {ctx.title}', .7),
                      _label(x + 7, 68.4, z + (25 if south else 6), ctx.question, .36),
                      fill(x + 2, 64, z + (19 if south else 12), x + 12, 64, z + (19 if south else 12), f'minecraft:{ctx.color}_terracotta')]
            lines += ctx.props
            for key, spec in ctx.actors.items():
                lines.append(summon_command(spec))
            index = 0
            for did, caption, steps in ctx.demos:
                lines += control(x + 3 + index * 3, front, f'function {NS}:demo/{ctx.sid}_{did}', caption, south)
                index += 1
            for caption, hint, fname in ctx.buttons:
                lines += control(x + 3 + index * 3, front, f'function {NS}:stand/{fname}', caption, south, hint)
                index += 1
            assert index <= 4, (ctx.sid, index)
            if ctx.demos:
                lines += control(x + 12, z + (18 if south else 13), f'function {NS}:stop/{ctx.sid}', '停止演示', south, '回到待机')
            # functions
            stop = []
            for did, caption, steps in ctx.demos:
                stop += [f'schedule clear {NS}:scene/{ctx.sid}_{did}/{i}' for i in range(len(steps) + 1)]
                for delay, cmds in steps:
                    for kind, key, name, command in cmds:
                        if kind == 'badge':
                            badge_actors.add((ctx.sid, key))
            stop += ctx.stop_before
            stop += getattr(ctx, 'hat_cleanup', [])
            for key, spec in ctx.actors.items():
                if not spec['human'] and (ctx.sid, key) in badge_actors:
                    stop.append(f'execute as {ctx.sel(key)} unless data entity @s YukkuriPet{{Grade:{spec["grade"]}}} run data modify entity @s YukkuriPet.Grade set value {spec["grade"]}')
            if any(not s['human'] for s in ctx.actors.values()):
                stop.append(f'yukkuri action @e[tag=room_{ctx.sid}] idle')
            for key in ctx.actors:
                stop.append('tp ' + ctx.sel(key) + ' {} {} {} {} 0'.format(*ctx.home(key)))
            stop += [f'kill @e[tag=pm_{ctx.sid}_fx]'] + ctx.stop_extra
            fn(f'stop/{ctx.sid}', stop)
            for did, caption, steps in ctx.demos:
                fn(f'demo/{ctx.sid}_{did}', [f'function {NS}:stop/{ctx.sid}', 'tellraw @a[distance=..60] ' + _text(caption, 'gold'),
                                              f'function {NS}:scene/{ctx.sid}_{did}/0'])
                for i, (ticks_, cmds) in enumerate(steps):
                    fn(f'scene/{ctx.sid}_{did}/{i}', [c[3] for c in cmds] + [f'schedule function {NS}:scene/{ctx.sid}_{did}/{i + 1} {ticks_}t replace'])
                fn(f'scene/{ctx.sid}_{did}/{len(steps)}', [f'function {NS}:stop/{ctx.sid}'])
                all_demos.append((hall.id, ctx.sid, did, caption, sum(t for t, _ in steps), steps))
                step_total += len(steps)
            for fname, body in ctx.functions.items():
                fn(f'stand/{fname}', body)
        if hall.id == 'E':
            mx, my, mz = hall.contexts[2].meadow
            x1, x2 = xe + 2, xe + 36
            lines += [fill(x1 - 1, 63, z, x2 + 1, 64, z + 31, 'minecraft:grass_block'), fill(xe, 63, z + 14, x1, 64, z + 18, 'minecraft:stone_bricks'),
                      fill(x1, 65, z, x2, 75, z + 31, 'minecraft:air')]
            lines += ring_absolute(x1, z, x2, z + 31, gate=None)
            lines += [fill(x1, 65, z + 14, x1, 66, z + 18, 'minecraft:air'), _label(x1 + 3, 68.5, z + 16, '聚落草地\n在这里点“建立聚落”', .8)]
            # forest burrows need natural cavities: shallow 5x5 pits in the turf, 8 blocks apart (see NaturalShelter.survey)
            for i in range(4):
                for j in range(3):
                    px, pz = x1 + 5 + 8 * i, z + 6 + 8 * j
                    lines.append(fill(px - 2, 64, pz - 2, px + 2, 64, pz + 2, 'minecraft:air'))
        lines.append(f'schedule function {NS}:build/hall_{halls[hall.index + 1].id.lower()} 1s replace' if hall.index + 1 < len(halls)
                     else f'schedule function {NS}:complete 1s replace')
        fn(f'build/hall_{hall.id.lower()}', lines)

    # ---- global stop / complete / check
    fn('stop_all', [f'function {NS}:stop/{ctx.sid}' for hall in halls for ctx in hall.contexts])
    fn('complete', [f'scoreboard players set #built {OBJ} 1', f'scoreboard players set #ready {OBJ} 1', f'function {NS}:stop_all',
                    f'schedule function {NS}:check 1s replace', 'say MCYMUSEUM_BUILD_COMPLETE',
                    f'schedule function {NS}:release_load 120s replace'])
    fn('release_load', ['forceload remove all'])

    check = [f'scoreboard players set #actors {OBJ} 0', f'scoreboard players set #controls {OBJ} 0', f'scoreboard players set #calls {OBJ} 0',
             f'scoreboard players set #says {OBJ} 0', f'scoreboard players set #hands {OBJ} 0',
             f'execute as @e[tag=pm_actor] run scoreboard players add #actors {OBJ} 1',
             f'execute as @e[tag=pm_hands] run scoreboard players add #hands {OBJ} 1']
    for x, zc in CONTROLS:
        check.append(f'execute if block {x} 63 {zc} minecraft:command_block if block {x} 65 {zc} minecraft:stone_button run scoreboard players add #controls {OBJ} 1')
    pairs = sorted({(ctx.sid, c[1], c[2]) for hall in halls for ctx in hall.contexts for _, _, steps in ctx.demos for _, cmds in steps for c in cmds if c[0] in ('action', 'scene_action')})
    ctx_by = {ctx.sid: ctx for hall in halls for ctx in hall.contexts}
    for sid, key, action in pairs:
        check += [f'execute store success score #last {OBJ} run yukkuri action {ctx_by[sid].sel(key)} {action}',
                  f'scoreboard players operation #calls {OBJ} += #last {OBJ}']
    first = {}
    for sid, key, event in sorted(speakers_of(halls)):
        first.setdefault((sid, key), event)
    for (sid, key), event in first.items():
        check += [f'execute store success score #last {OBJ} run yukkuri say {ctx_by[sid].sel(key)} {event}',
                  f'scoreboard players operation #says {OBJ} += #last {OBJ}']
    n_actors = sum(len(ctx.actors) for ctx in ctx_by.values())
    check += [f'data modify entity @e[tag=pm_exam_kid,limit=1] YukkuriPet.Grade set value 2',
              f'execute store result score #grade {OBJ} run data get entity @e[tag=pm_exam_kid,limit=1] YukkuriPet.Grade',
              f'function {NS}:stop_all',
              f'execute store result score #grade0 {OBJ} run data get entity @e[tag=pm_exam_kid,limit=1] YukkuriPet.Grade',
              # a raw setblock does not notify the hidden command block the way a click does: poke it like the button would
              f'setblock {CONTROLS[0][0]} 65 {CONTROLS[0][1]} minecraft:stone_button[face=floor,powered=true]',
              f'setblock {CONTROLS[0][0]} 62 {CONTROLS[0][1]} minecraft:coarse_dirt',
              f'schedule function {NS}:check_button 4t replace']
    fn('check', check)
    details = [('actors', '#actors'), ('controls', '#controls'), ('actionCalls', '#calls'), ('speakers', '#says'),
               ('badgeSet', '#grade'), ('badgeReset', '#grade0'), ('buttonWire', '#button'), ('handsOn', '#hands')]
    extra = []
    for label_, obj in details:
        extra += [{'text': f' {label_}='}, {'score': {'name': obj, 'objective': OBJ}}]
    ok = (f'execute if score #actors {OBJ} matches {n_actors} if score #controls {OBJ} matches {len(CONTROLS)} if score #calls {OBJ} matches {len(pairs)} '
          f'if score #says {OBJ} matches {len(first)} if score #grade {OBJ} matches 2 if score #grade0 {OBJ} matches 0 '
          f'if score #hands {OBJ} matches 1 if score #button {OBJ} matches 1..')
    fn('check_button', [
        f'execute store result score #button {OBJ} run data get block {CONTROLS[0][0]} 63 {CONTROLS[0][1]} powered',
        ok + ' run say MCYMUSEUM_CHECK_PASS',
        f'execute unless score #actors {OBJ} matches {n_actors} run say MCYMUSEUM_CHECK_FAIL actors',
        f'execute unless score #controls {OBJ} matches {len(CONTROLS)} run say MCYMUSEUM_CHECK_FAIL controls',
        f'execute unless score #calls {OBJ} matches {len(pairs)} run say MCYMUSEUM_CHECK_FAIL actions',
        f'execute unless score #says {OBJ} matches {len(first)} run say MCYMUSEUM_CHECK_FAIL speech',
        f'execute unless score #grade {OBJ} matches 2 run say MCYMUSEUM_CHECK_FAIL badge',
        f'execute unless score #grade0 {OBJ} matches 0 run say MCYMUSEUM_CHECK_FAIL badge_reset',
        f'execute unless score #hands {OBJ} matches 1 run say MCYMUSEUM_CHECK_FAIL hands',
        f'execute unless score #button {OBJ} matches 1.. run say MCYMUSEUM_CHECK_FAIL button',
        'tellraw @a ' + js({'text': 'MCYMUSEUM_CHECK details:', 'extra': extra}),
        f'setblock {CONTROLS[0][0]} 65 {CONTROLS[0][1]} minecraft:stone_button[face=floor,powered=false]',
        f'setblock {CONTROLS[0][0]} 62 {CONTROLS[0][1]} minecraft:dirt',
        f'function {NS}:stop_all'])
    return dict(actors=n_actors, controls=len(CONTROLS), action_pairs=len(pairs), speakers=len(first), scenes=len(all_demos),
                scene_steps=step_total, demos=all_demos)


def speakers_of(halls):
    for hall in halls:
        for ctx in hall.contexts:
            for _, _, steps in ctx.demos:
                for _, cmds in steps:
                    for c in cmds:
                        if c[0] == 'say':
                            yield ctx.sid, c[1], c[2]


# ============================================================================== catalogue
def write_catalogue(path, halls, shown):
    clips, internal = load_clips()
    where = {}
    for hall in halls:
        for ctx in hall.contexts:
            for _, _, steps in ctx.demos:
                for _, cmds in steps:
                    for c in cmds:
                        if c[0] in ('action', 'scene_action'):
                            where.setdefault(c[2], set()).add(f'{ctx.number:02d} {ctx.title}')
                        elif c[0] == 'contact':
                            for actor, clip in ctx.contact_clips(c[1], *c[2]):
                                where.setdefault(clip, set()).add(f'{ctx.number:02d} {ctx.title}')
    lines = ['# 展馆动作清单', '',
             f'当前 `YukkuriAction` 注册 {len(ACTION_META)} 个动作。此表从注册表、19 套基础模型和动作修订联合生成；“资源适配”指确实存在动画片段，不等于每个年龄、亚种都适合该情景。'
             '水生魔理沙与普通魔理沙共用模型，展台按生态身份分开。', '',
             f'展馆（五厅，{sum(len(h.contexts) for h in halls)} 个展台）按情景演示其中 {len(shown)} 个动作（含待机），其余收录在目录，不强行放入参观路线。'
             '一般展台使用 `/yukkuri action` 与 `/yukkuri say`；出生与接生使用 `/yukkuri birth_scene` 服务端成对演出，包含走位、孕体、逐只产出和帽托交接。自然行为只在“动手角”和“聚落”两处。', '',
             '缩壳虽然有专属资源，但当前命令将所有 `_retract` 当作成对收回动作，导致 `shell_retract` 回到待机。展馆暂不放置无效缩壳按钮；自然受惊时的缩壳由另一条游戏逻辑触发。', '',
             '身体结构四段、失去幼体后的悲伤（`bereaved`）和家油内部片段不纳入参观路线，但仍在下表列全。', '']
    for group, entries in CATEGORIES:
        lines += ['## ' + group, '', '| 动作 | ID | 资源适配 | 展台 |', '| --- | --- | --- | --- |']
        for action, name in entries:
            supported = [MODELS[m] for m in MODELS if action in clips[m]]
            scope = '全部 19 套模型' if len(supported) == 19 else '、'.join(supported)
            if action.startswith('boat_') or action.endswith('_aq'):
                scope += '；水生情景'
            if action == 'hat_cradle':
                scope += '；只用于帽托幼体'
            if action == 'first_wake':
                scope += '；只用于初生幼体'
            lines += [f'| {name} | `{action}` | {scope} | ' + ('、'.join(sorted(where[action])) if action in where else '仅目录') + ' |']
        lines.append('')
    lines += ['## 注册表之外的内部片段', '', '这些名称不属于 `/yukkuri action` 的可选动作，不放置假按钮。', '', '| 模型 | 内部片段 |', '| --- | --- |']
    for model, extra in internal.items():
        if extra:
            lines += [f'| {MODELS[model]} | ' + '、'.join('`' + x + '`' for x in sorted(extra)) + ' |']
    lines += ['', '家油夹取、搬运、放下、蠕动由相应行为状态调用；展馆的家油展台用标准动作与台词事件演示，不播放这些内部片段。', '',
              '维护入口：`scripts/build_museum_full.py --catalog docs/MOTION_MUSEUM_ACTIONS.md`（同时需提供种子 level.dat 与新的输出目录）。', '']
    path.write_text('\n'.join(lines), encoding='utf-8')


# ============================================================================== world
def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--seed-level', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--catalog', type=Path)
    args = parser.parse_args()
    out = args.output.resolve()
    if '.local/experimental-map' not in out.as_posix():
        raise ValueError('Output must be a new directory below a .local/experimental-map folder')
    halls = build_layout()
    shown, speech = validate(halls)
    out.mkdir(parents=True, exist_ok=False)
    world = out / 'saves' / WORLD
    world.mkdir(parents=True)
    metadata = n.load(args.seed_level)
    d = metadata['Data']
    if 'fml' in metadata:
        metadata['fml']['LoadingModList'] = n.List[n.Compound]([m for m in metadata['fml']['LoadingModList'] if str(m['ModId']) != 'mcyukkuri_p0'])
    for key in ('Player', 'CustomBossEvents'):
        d.pop(key, None)
    d['ScheduledEvents'] = n.List[n.Compound]([])
    d['DragonFight'] = n.Compound({'NeedsStateScanning': n.Byte(1), 'DragonKilled': n.Byte(0), 'PreviouslyKilled': n.Byte(0)})
    for key, value in (('SpawnX', SPAWN[0]), ('SpawnY', SPAWN[1]), ('SpawnZ', SPAWN[2]), ('GameType', 2)):
        d[key] = n.Int(value)
    d['SpawnAngle'] = n.Float(SPAWN[3])
    d['Difficulty'] = n.Byte(0)
    d['allowCommands'] = n.Byte(1)
    d['LevelName'] = n.String('油库里展馆 · 五厅')
    d['Time'] = n.Long(0)
    d['DayTime'] = n.Long(6000)
    d['LastPlayed'] = n.Long(int(time.time() * 1000))
    d['DataPacks']['Enabled'] = n.List[n.String](['vanilla', 'mod_data', 'file/mcylab'])
    d['DataPacks']['Disabled'] = n.List[n.String]([])
    d['enabled_features'] = n.List[n.String](['minecraft:vanilla'])
    for key in ('wasModded', 'confirmedExperimentalSettings'):
        d[key] = n.Byte(1)
    settings = d['WorldGenSettings']['dimensions']['minecraft:overworld']['generator']['settings']
    settings['layers'] = n.List[n.Compound]([n.Compound({'block': n.String(b), 'height': n.Int(h)}) for b, h in
                                             (('minecraft:bedrock', 1), ('minecraft:stone', 124), ('minecraft:dirt', 2), ('minecraft:grass_block', 1))])
    settings['structure_overrides'] = n.List[n.String]([])
    for k, v in {'doMobSpawning': 'false', 'doDaylightCycle': 'false', 'doWeatherCycle': 'false', 'keepInventory': 'true', 'spawnRadius': '0',
                 'commandBlockOutput': 'false', 'sendCommandFeedback': 'false', 'doFireTick': 'false'}.items():
        d['GameRules'][k] = n.String(v)
    metadata.save(world / 'level.dat')
    CONTROLS.clear()
    checks = build_pack(world, halls, shown)
    manifest = {'world': str(world), 'minecraft': '1.21.1', 'halls': [{'id': h.id, 'title': h.title, 'z0': h.z0, 'width': h.xe + 2,
                'stands': [{'number': c.number, 'id': c.sid, 'title': c.title, 'actors': list(c.actors), 'demos': [(d[0], d[1]) for d in c.demos]} for c in h.contexts]}
                for h in halls],
                'registered_actions': len(ACTION_META), 'displayed_actions': sorted(shown), 'speech_events': sorted({s[2] for s in speech}),
                'checks': {k: v for k, v in checks.items() if k != 'demos'},
                'scenes': [{'hall': h, 'stand': s, 'id': i, 'caption': c, 'ticks': t} for h, s, i, c, t, _ in checks['demos']],
                'generator_sha256': hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
    (out / 'map-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    (out / 'options.txt').write_text('lang:zh_cn\nguiScale:0\nrenderDistance:10\nsimulationDistance:8\nmaxFps:120\nonboardAccessibility:false\nautoJump:false\n'
                                     'pauseOnLostFocus:false\ntutorialStep:none\nnarrator:0\n', encoding='utf-8')
    if args.catalog:
        write_catalogue(args.catalog, halls, shown)
    print(json.dumps({'output': str(out), 'halls': len(halls), 'stands': sum(len(h.contexts) for h in halls), 'registered_actions': len(ACTION_META),
                      'displayed_actions': len(shown), **{k: v for k, v in checks.items() if k != 'demos'}}, ensure_ascii=False))


if __name__ == '__main__':
    main()
