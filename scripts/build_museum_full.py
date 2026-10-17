"""Build the MCyukkuri showcase museum, the single in-game test map (Minecraft 1.21.1, NeoForge, Python 3.11+).

Five halls on one west corridor behind a lobby with a station wall, 23 stations, hands-on corners,
isolated courtyards with observation decks (births, house yukkuri, independence) and an outdoor settlement
meadow. Minecraft builds everything from the generated data pack on first entry; this script writes the pack,
a seed-derived level.dat (metadata only) and options.

Normal use is through the root launcher ``启动动作博物馆.cmd``, which runs

    python scripts/build_museum_full.py --install        # fresh world in .local/experimental-map/museum
    python scripts/build_museum_full.py --stale          # exit status 1 when the installed world is out of date

``--install`` replaces ``.local/experimental-map/museum`` completely (the map is generated, never repaired in
place) and takes its level.dat from ``.local/experimental-map/museum-seed/level.dat`` (copied once from any
earlier museum world). Other modes:

    python scripts/build_museum_full.py --pack-only --output .local/experimental-map/<new-dir>   # data pack only
    python scripts/build_museum_full.py --seed-level <level.dat> --output .local/experimental-map/<new-dir>

No Java compilation and no UI automation. An explicit output directory must be a new directory below
``.local/experimental-map``. Scene data lives in museum_scenes.py and its helper modules; the text on the
boards lives in museum_info.py.
"""
from pathlib import Path
import argparse
import hashlib
import json
import math
import random
import shutil
import sys
import time

sys.dont_write_bytecode = True
HERE = Path(__file__).resolve().parent
ROOT = HERE.parent
sys.path.insert(0, str(HERE))
sys.path.insert(0, str(ROOT / '.local/setup/python'))
sys.path.insert(0, str(ROOT.parent.parent / '.local/setup/python'))   # a worktree under .worktrees/ shares the main checkout's tools
from museum_actions import CATEGORIES, NAMES, MODELS, ACTION_META, load_clips  # noqa: E402
from museum_dsl import Ctx, summon_command, _text, _q, _label  # noqa: E402
import museum_scenes  # noqa: E402
import museum_hat_boats  # noqa: E402
import museum_birth  # noqa: E402
import museum_house  # noqa: E402
import museum_info  # noqa: E402
import museum_sites  # noqa: E402
from museum_sites import button, wall_label, display, board_parts, wrap, fence_ring, deck, CONTROLS  # noqa: E402

NS = 'mcylab'
OBJ = 'museum'
WORLD = 'MCyukkuri-Museum'
SPAWN = (-6, 65, -7, 180)
HALL_PITCH = 40
SPINE_X0, SPINE_X1 = -11, 0
LOBBY_Z0 = -40
STAGING = (ROOT / '.local/experimental-map')
HALL_HEX = {'cyan': '#3fd0e0', 'orange': '#ff9f33', 'light_blue': '#6fb7ff', 'purple': '#c08aff', 'green': '#6fe06f'}


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
        self.lounges = []      # (row, x0) of unused stand slots
        self.hex = HALL_HEX.get(color, '#ffffff')


def slot(hall, i):
    """Clockwise: north row west to east, then south row east to west."""
    if i < hall.k:
        return 'N', 15 * i
    return 'S', 15 * (2 * hall.k - 1 - i)


def build_layout():
    CONTROLS.clear()
    museum_sites.TEXTS.clear()
    halls, number = [], 1
    for index, (hid, title, color, stands) in enumerate(museum_scenes.HALLS):
        hall = Hall(index, hid, title, color, stands)
        for i, (sid, stand_title, question, stand_color, builder) in enumerate(stands):
            row, x0 = slot(hall, i)
            ctx = Ctx(sid, row, x0, hall.z0, stand_color)
            ctx.number, ctx.title, ctx.question, ctx.hall = number, stand_title, question, hall
            ctx.info = museum_info.INFO[sid]
            ctx.env.update(ctx.info.get('env', {}))
            ctx.yard_cast = False
            number += 1
            if sid == 'settle':
                ctx.meadow = (hall.xe + 14, 65, hall.z0 + 16)   # centre (plaza) of the demo site; its twin lies 36 blocks east
            builder(ctx)
            museum_hat_boats.configure(ctx)
            museum_birth.configure(ctx)
            museum_house.configure(ctx)
            if sid == 'settle':
                settle_gate(hall, ctx)
            hall.contexts.append(ctx)
        for i in range(len(stands), 2 * hall.k):
            hall.lounges.append(slot(hall, i))
        halls.append(hall)
    stations = [ctx for hall in halls for ctx in hall.contexts]
    for i, ctx in enumerate(stations):
        ctx.prev = stations[i - 1] if i else None
        ctx.next = stations[i + 1] if i + 1 < len(stations) else None
        south = ctx.row == 'S'
        ctx.land = (ctx.x0 + 7.5, 65, ctx.z0 + (19.5 if south else 11.5), 0 if south else 180)
    return halls


def resettable(ctx):
    """Stage cast that the museum may put back: not the real families living in courtyards."""
    return bool(ctx.actors) and not ctx.yard_cast


def is_live(steps):
    return any(c[3].startswith('yukkuri museum live') for _, cmds in steps for c in cmds)


def kind_tag(ctx):
    if ctx.sites:
        return '含远处观察台'
    if ctx.water or any(is_live(steps) for _, _, steps in ctx.demos):
        return '真实行为'
    if ctx.kind == 'open' or (not ctx.demos and ctx.buttons):
        return '可动手'
    return '动作片段'


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


def ring_absolute(x1, z1, x2, z2, y=65):
    cells = [(x, z) for x in range(x1, x2 + 1) for z in range(z1, z2 + 1) if x in (x1, x2) or z in (z1, z2)]
    return fence_ring(cells, y)


def fillbiome_boxes(x1, z1, x2, z2, biome, y1=60, y2=79, limit=30000):
    """fillbiome is capped at 32768 blocks per command (commandModificationBlockLimit)."""
    per_slice = (x2 - x1 + 1) * (y2 - y1 + 1)
    step = max(1, limit // per_slice)
    out, z = [], z1
    while z <= z2:
        zz = min(z2, z + step - 1)
        out.append(f'fillbiome {x1} {y1} {z} {x2} {y2} {zz} {biome}')
        z = zz + 1
    return out


def env_commands(env, demo=False):
    """Time and weather presets. Every station without a preset restores noon and clear weather. A demo started at a
    station that sets ``keep_time`` leaves the time alone (the visitor may have chosen night with the station's button)."""
    time_value = {'noon': 'noon', 'morning': '100', 'midnight': 'midnight'}[env.get('time', 'noon')]
    weather = 'weather rain 1000000' if env.get('weather') == 'rain' else 'weather clear'
    return ([] if demo and env.get('keep_time') else [f'time set {time_value}']) + [weather]


def rect_chunks(rect):
    x1, z1, x2, z2 = rect
    return {(x, z) for x in range(x1 // 16 * 16, x2 // 16 * 16 + 1, 16) for z in range(z1 // 16 * 16, z2 // 16 * 16 + 1, 16)}


# ============================================================================== guide book
def book_command(halls, shown):
    pages = ['油库里展馆\n\n一条走廊，五个展厅，23 个展台。\n\n1 大厅两侧的墙是“站点墙”：按钮按名字传送到展台。\n2 到了展台，读头顶的牌子：看点／操作／预计／没反应。\n3 按地上的按钮开始；“重置本站”可以重来。\n\n回大厅：/function mcylab:home\n重置各展台：大厅东墙按钮']
    for hall in halls:
        lines = [f'{hall.id}厅 · {hall.title}', '']
        for ctx in hall.contexts:
            lines.append(f'{ctx.number:02d} {ctx.title}（{kind_tag(ctx)}）')
        pages.append('\n'.join(lines))
    pages.append('说明\n\n“动作片段”播放制作好的动作，播完自动归位。\n“真实行为”只设置起始条件，之后由普通玩法运行，窗口结束后不会被打断，要回到初始请按“重置本站”。\n庭院观察台上有回展台、回大厅、换新一对的按钮。\n每个庭院的出生只发生一次。')
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
    sids = set()
    for hall in halls:
        for ctx in hall.contexts:
            assert ctx.sid not in sids and ctx.sid in museum_info.INFO, ctx.sid
            sids.add(ctx.sid)
            ids = set()
            row1 = len(ctx.demos) + len(ctx.buttons) + sum(1 for s in ctx.sites if not s.get('button_done'))
            assert 1 <= row1 <= 6, (ctx.sid, 'row-1 buttons', row1)
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
                        if kind not in ('action', 'say'):
                            continue
                        spec = ctx.actors[key]
                        assert not spec['human'], (ctx.sid, key)
                        model = spec['model']
                        if kind == 'action':
                            assert name in ACTION_META, name
                            assert name in clips[model], (ctx.sid, did, key, model, name)
                            if (name.startswith('boat_') or name.endswith('_aq')) and not name.startswith('boat_rock'):
                                assert spec['aquatic'] or name in ('boat_reassure', 'boat_alarm_call', 'boat_alarm_wait', 'boat_flee', 'boat_calm',
                                                                  'boat_answer_call'), (ctx.sid, did, key, name)
                            if name.startswith("braid_") or name in ("water_hat_flip", "water_hat_grip", "water_hat_climb", "hat_brim_offer", "hat_hop_up", "water_bob_lunge", "hat_slide_over", "water_mouth_grip", "water_hat_shake", "water_hat_push", "boat_nudge_young"):
                                assert model == 'marisa', (ctx.sid, did, key, name)
                            if not ACTION_META[name][1] and name != 'waddle':
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
            for site in ctx.sites:
                assert site['visit'] in ctx.functions, (ctx.sid, site['id'])
    return shown, speech


# ============================================================================== datapack
def build_pack(world, halls, shown):
    root = world / 'datapacks' / 'mcylab'
    functions = root / 'data' / NS / 'function'
    stations = [ctx for hall in halls for ctx in hall.contexts]
    names = []

    def fn(name, lines):
        assert name not in names, name
        assert len(lines) <= 20000, (name, len(lines))
        names.append(name)
        path = functions / (name + '.mcfunction')
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('\n'.join(lines) + '\n', encoding='utf-8')

    def guarded(name, chunks, lines):
        """A build step runs only when every chunk it touches is loaded; otherwise it asks to be run again in a second.
        (A fill into an unloaded chunk is rejected and the part of the map it covers would silently stay missing.)"""
        return [f'execute unless loaded {x + 8} 65 {z + 8} run return run schedule function {NS}:{name} 1s replace' for x, z in sorted(chunks)] + lines

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
    # chunks that must be loaded while the map is built (released two minutes after completion)
    rects = [(-48, LOBBY_Z0 - 16, 15, zlast + 16)]
    for hall in halls:
        rects.append((0, hall.z0, hall.xe + 1, hall.z0 + 31))
        for ctx in hall.contexts:
            rects.extend(ctx.regions)
    east = halls[-1]
    rects.append((east.xe + 1, east.z0, east.xe + 68, east.z0 + 33))
    initial_chunks = set()
    for rect in rects:
        initial_chunks |= rect_chunks(rect)
    assert len(initial_chunks) <= 400, len(initial_chunks)
    kept = set()
    for hall in halls:
        for ctx in hall.contexts:
            for rect in ctx.keep_loaded:
                kept |= rect_chunks(rect)
    initial_load, release_load = [], []
    for i, (x, z) in enumerate(sorted(initial_chunks)):
        initial_load += [f'execute unless score #build_chunk_{i} {OBJ} matches 0..1 store success score #build_chunk_{i} {OBJ} run forceload query {x} {z}',
                         f'execute unless score #build_chunk_{i} {OBJ} matches 1 run forceload add {x} {z}']
        if (x, z) not in kept:
            release_load.append(f'execute unless score #build_chunk_{i} {OBJ} matches 1 run forceload remove {x} {z}')

    # ---- load / tick / welcome
    fn('load', [f'scoreboard objectives add {OBJ} dummy', 'scoreboard objectives add mus_at dummy',
                f'execute unless score #built {OBJ} matches 1 run function {NS}:prepare',
                # A pack reload cancels playback without teleporting actors or rebuilding inventories.
                f'execute if score #built {OBJ} matches 1 run function {NS}:pause_all'])
    prepare = [f'scoreboard players set #ready {OBJ} 0'] + initial_load + [
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
                   'tellraw @s ' + _text('欢迎入馆！看大厅左右两面墙：每个按钮传送到一个展台。到了展台先读头顶的牌子。', 'aqua')])
    fn('guide', [book_command(halls, shown)])
    fn('home', [f'function {NS}:go/lobby'])

    # ---- environment presets (time, weather); stations without a preset restore noon / clear
    fn('env/default', env_commands({}))
    for ctx in stations:
        preset = 'time' in ctx.env or 'weather' in ctx.env
        fn(f'env/{ctx.sid}', env_commands(ctx.env) if preset else [f'function {NS}:env/default'])
        fn(f'env/{ctx.sid}_demo', env_commands(ctx.env, demo=True) if preset else [f'function {NS}:env/default'])

    # ---- spine: lobby + corridor
    spine = build_spine(halls)
    fn('build/spine', guarded('build/spine', rect_chunks((SPINE_X0 - 1, LOBBY_Z0 - 1, 1, zlast + 2)), spine + [f'schedule function {NS}:build/hall_{halls[0].id.lower()} 1s replace']))

    # ---- teleports
    site_releases = [f'function {NS}:stand/{s["release"]}' for ctx in stations for s in ctx.sites if s.get('release')]
    fn('release_sites', site_releases or ['# no remote site holds chunk tickets'])
    fn('go/lobby', [f'function {NS}:release_sites', f'function {NS}:env/default',
                    f'tp @s {SPAWN[0] + .5} {SPAWN[1]} {SPAWN[2] + .5} {SPAWN[3]} 0',
                    'tellraw @s ' + _text('大厅：左右墙上按名字选展台；“重置各展台”在东墙。', 'aqua')])
    for hall in halls:
        fn(f'go/{hall.id.lower()}', [f'function {NS}:release_sites', f'function {NS}:env/default', f'tp @s 1.5 65 {hall.z0 + 16.5} -90 0',
                                     'tellraw @s ' + js([{'text': f'{hall.id}厅 · {hall.title}', 'color': 'gold', 'bold': True},
                                                         {'text': f'：{len(hall.contexts)} 个展台，先看北排由西向东，再看南排由东向西。门口两侧有“上一厅／回大厅”。', 'color': 'aqua'}])])
    for ctx in stations:
        info = ctx.info
        x, y, z, yaw = ctx.land
        enter = [f'function {NS}:release_sites', f'function {NS}:env/{ctx.sid}', f'tp @s {x} {y} {z} {yaw} 0',
                 f'scoreboard players set @s mus_at {ctx.number}']
        if resettable(ctx):
            enter.append(f'execute unless entity @e[tag=room_{ctx.sid},tag=pm_live] run function {NS}:clean/{ctx.sid}')
        enter.append('tellraw @s ' + js([{'text': f'{ctx.number:02d} {ctx.title}', 'color': 'gold', 'bold': True},
                                         {'text': f'（{ctx.hall.id}厅）\n看点：{info["watch"]}\n操作：{info["press"]}', 'color': 'white'}]))
        fn(f'go/s/{ctx.sid}', enter)
        for site in ctx.sites:
            fn(f'go/site/{site["id"]}', [f'function {NS}:stand/{site["visit"]}'])

    # ---- halls
    all_demos, badge_actors = [], set()
    for hall in halls:
        lines = build_hall(hall, stations, fn)
        last = hall.index + 1 == len(halls)
        lines.append(f'schedule function {NS}:build/{"biomes" if last else "hall_" + halls[hall.index + 1].id.lower()} 1s replace')
        chunks = rect_chunks((0, hall.z0, hall.xe + 1, hall.z0 + 31))
        for ctx in hall.contexts:
            for rect in ctx.regions:
                chunks |= rect_chunks(rect)
        if hall.id == 'E':
            chunks |= rect_chunks((hall.xe + 1, hall.z0, hall.xe + 68, hall.z0 + 33))
        fn(f'build/hall_{hall.id.lower()}', guarded(f'build/hall_{hall.id.lower()}', chunks, lines))

    for hall in halls:
        for ctx in hall.contexts:
            for did, caption, steps in ctx.demos:
                all_demos.append((hall.id, ctx.sid, did, caption, sum(t for t, _ in steps), steps))
                for delay, cmds in steps:
                    for kind, key, name, command in cmds:
                        if kind == 'badge':
                            badge_actors.add((ctx.sid, key))

    fn('util/life_reset', [f'data modify entity @s YukkuriLife.{k} set value {v}f' for k, v in
                           (('Nutrition', 90), ('Energy', 95), ('Hygiene', 100), ('Bladder', 0), ('Wetness', 0), ('Dryness', 0))])
    for hall in halls:
        for ctx in hall.contexts:
            build_station_functions(fn, ctx, badge_actors)

    # actors that can be installed again (fresh courtyard pairs, boat crews)
    install = []
    for ctx in stations:
        for key, spec in ctx.actors.items():
            if not spec.get('installable'):
                continue
            name = f'install_actor/{ctx.sid}_{key}'
            marker = f'#actor_{ctx.sid}_{key}'
            at = f'{math.floor(spec["x"])} {math.floor(spec["y"])} {math.floor(spec["z"])}'
            fn(name, [f'scoreboard players set {marker} {OBJ} 1',
                      f'execute unless entity {ctx.sel(key)} run ' + summon_command(spec)])
            install.append(f'execute if loaded {at} unless score {marker} {OBJ} matches 1 run function {NS}:{name}')
    fn('install_new_actors', install)

    # ---- biomes (built once; seasons follow the biome tags)
    biome_lines = []
    for ctx in stations:
        biome = ctx.env.get('biome')
        if biome:
            z1, z2 = (ctx.z0 + 20, ctx.z0 + 31) if ctx.row == 'S' else (ctx.z0, ctx.z0 + 11)
            biome_lines += fillbiome_boxes(ctx.x0, z1, ctx.x0 + 14, z2, biome)
    # hall E's meadow is a forest settlement site (forest burrows, autumn store)
    biome_lines += fillbiome_boxes(east.xe + 1, east.z0 + 1, east.xe + 66, east.z0 + 30, 'minecraft:forest')
    fn('build/biomes', guarded('build/biomes', rect_chunks((0, east.z0, east.xe + 68, east.z0 + 33)), biome_lines + [f'schedule function {NS}:complete 1s replace']))

    # ---- global reset / complete / self-check
    pause = [f'schedule clear {NS}:scene/{ctx.sid}_{did}/{i}' for ctx in stations for did, _, steps in ctx.demos for i in range(len(steps) + 1)]
    pause += ['yukkuri museum pause @e[tag=pm_actor]']
    pause += [line for ctx in stations for line in ctx.stop_before if line.startswith('schedule clear ')]
    fn('pause_all', pause)
    batch = [ctx for ctx in stations if resettable(ctx) and ctx.batch]
    fn('stop_all', [f'function {NS}:stop/{ctx.sid}' for ctx in batch] +
       ['tellraw @a ' + _text('已向各展台发出重置（远处没加载的展台请走近后按它的“重置本站”）。庭院里的家庭、独立、船只和聚落是真实的，不受影响；它们有各自的重置按钮。', 'green')])
    fn('complete', [f'scoreboard players set #built {OBJ} 1', f'scoreboard players set #ready {OBJ} 1',
                    f'function {NS}:stop_all', f'function {NS}:env/default',
                    'say MCYMUSEUM_BUILD_COMPLETE', f'schedule function {NS}:release_load 120s replace'])
    fn('release_load', release_load)

    n_actors = sum(len(ctx.actors) for ctx in stations)
    check = [f'scoreboard players set #actors {OBJ} 0', f'scoreboard players set #controls {OBJ} 0',
             f'execute as @e[tag=pm_actor] run scoreboard players add #actors {OBJ} 1']
    for x, y, z in CONTROLS:
        check.append(f'execute if block {x} {y} {z} minecraft:command_block run scoreboard players add #controls {OBJ} 1')
    check += ['tellraw @a ' + js([{'text': '展馆自检：演员 ', 'color': 'aqua'}, {'score': {'name': '#actors', 'objective': OBJ}}, {'text': f' / {n_actors}'},
                                 {'text': '　按钮 ', 'color': 'aqua'}, {'score': {'name': '#controls', 'objective': OBJ}}, {'text': f' / {len(CONTROLS)}'}]),
              f'execute unless score #actors {OBJ} matches {n_actors} run tellraw @a ' + _text('演员数量不符：有演员被收容、死亡或未加载（庭院双亲换新后数量也会变）。', 'yellow'),
              f'execute unless score #controls {OBJ} matches {len(CONTROLS)} run tellraw @a ' + _text('按钮数量不符：区块未加载，或建馆尚未完成。', 'red'),
              f'execute if score #controls {OBJ} matches {len(CONTROLS)} run tellraw @a ' + _text('按钮齐全。', 'green')]
    fn('selfcheck', check)
    return dict(actors=n_actors, controls=len(CONTROLS), stations=len(stations), scenes=len(all_demos),
                functions=len(names), chunks=len(initial_chunks), texts=len(museum_sites.TEXTS), demos=all_demos)


# ---------------------------------------------------------------------------- spine
def directory_entries(hall_group, xb, xl, face, z_start):
    """Header + one button per station of each hall, labels flat on the wall. Returns (lines, next free z)."""
    out, z = [], z_start
    for hall in hall_group:
        out += button(xb, 65, z, f'function {NS}:go/{hall.id.lower()}', f'{hall.id}厅', nav=True, label=False)
        out.append(wall_label(xl, 66.35, z + .5, [(f'{hall.id}厅 {hall.title}\n', hall.hex, True), (f'{len(hall.contexts)} 个展台 · 进入本厅', 'gray')],
                              .75, face, 'wall', f'directory {hall.id}'))
        for ctx in hall.contexts:
            z += 2
            out += button(xb, 65, z, f'function {NS}:go/s/{ctx.sid}', ctx.title, nav=True, label=False)
            out.append(wall_label(xl, 66.2, z + .5, [(f'{ctx.number:02d} {ctx.title}\n', 'white', True), (kind_tag(ctx), 'gray')],
                                  .55, face, 'wall', f'directory {ctx.sid}'))
        z += 3
    return out, z


def build_spine(halls):
    zlast = halls[-1].z0 + 31
    s = []
    s += big_fill(SPINE_X0 - 1, 65, LOBBY_Z0 - 1, 1, 74, zlast + 2, 'minecraft:air')
    s += big_fill(SPINE_X0, 63, LOBBY_Z0, 0, 64, zlast + 1, 'minecraft:smooth_quartz')
    s += big_fill(SPINE_X0, 65, LOBBY_Z0 - 1, SPINE_X0, 72, zlast + 1, 'minecraft:calcite')
    s += big_fill(0, 65, LOBBY_Z0 - 1, 0, 72, zlast + 1, 'minecraft:calcite')
    s += [fill(SPINE_X0, 65, LOBBY_Z0 - 1, 0, 72, LOBBY_Z0 - 1, 'minecraft:calcite'), fill(SPINE_X0, 65, zlast + 1, 0, 72, zlast + 1, 'minecraft:calcite'),
          fill(SPINE_X0, 72, LOBBY_Z0, 0, 72, zlast, 'minecraft:smooth_quartz')]
    s += big_fill(SPINE_X0 + 3, 72, LOBBY_Z0, -3, 72, zlast, 'minecraft:glass')
    s += big_fill(-7, 64, LOBBY_Z0, -5, 64, zlast, 'minecraft:polished_diorite')
    s += big_fill(-6, 64, LOBBY_Z0, -6, 64, zlast, 'minecraft:yellow_terracotta')
    for z in range(LOBBY_Z0 + 3, zlast, 8):
        s.append(f'setblock -5 72 {z} minecraft:sea_lantern')
    # lobby: title wall and how-to board (north end), station wall on both side walls, services on the east wall
    nstations = sum(len(h.contexts) for h in halls)
    s.append(display(-5.5, 69.6, LOBBY_Z0 + .7, [('油库里展馆', 'gold', True)], 3.0, 'title', 'lobby title', billboard='vertical'))
    s.append(display(-5.5, 67.9, LOBBY_Z0 + .7, [('Yukkuri Museum · ゆっくり展示館\n', 'aqua'), (f'五个展厅 · {nstations} 个展台', 'white')], 1.0, 'banner', 'lobby subtitle',
                     billboard='vertical'))
    howto = [('怎么逛\n', 'gold', True),
             ('1 看左右两面墙：每个按钮传送到一个展台\n', 'white'),
             ('2 到展台后读头顶牌子：看点／操作／预计\n', 'white'),
             ('3 按地上的按钮开始；“重置本站”可以重来\n', 'white'),
             ('4 每站都有 回大厅／上一站／下一站\n', 'white'),
             ('Pick a station on a wall, then read its board.\n', 'gray'),
             ('壁のボタンで展示へ。看板を読んで操作。', 'gray')]
    s.append(display(-5.5, 66.6, LOBBY_Z0 + .7, howto, .6, 'board', 'lobby how-to', billboard='vertical'))
    west, east = halls[:3], halls[3:]
    lines, _ = directory_entries(west, -9, -9.95, 'east', LOBBY_Z0 + 1)
    s += lines
    lines, end = directory_entries(east, -2, -.05, 'west', LOBBY_Z0 + 1)
    s += lines
    services = [(f'function {NS}:guide', '领取导览书', '补发一本', True),
                (f'function {NS}:stop_all', '重置各展台', '不动庭院家庭', False),
                (f'function {NS}:env/default', '恢复正午晴天', '时间与天气', False),
                (f'function {NS}:selfcheck', '展馆自检', '数按钮与演员', False)]
    z = -11
    for command, caption, hint, nav in services:
        s += button(-2, 65, z, command, caption, hint, nav=nav, label=False)
        s.append(wall_label(-.05, 66.25, z + .5, [(caption + '\n', 'white', True), (hint, 'gray')], .55, 'west', 'wall', f'service {caption}'))
        z += 2
    for hall in halls:
        z = hall.z0
        s.append(display(-1.3, 69.4, z + 16, [(f'{hall.id}厅 · {hall.title}\n', hall.hex, True), (f'{len(hall.contexts)} 个展台', 'white')], 1.0, 'banner', f'door {hall.id}'))
        # the hall's stations along the corridor wall next to its door
        first = math.ceil(len(hall.contexts) / 2)
        for i, ctx in enumerate(hall.contexts):
            zz = z + (3 + 2 * i if i < first else 21 + 2 * (i - first))
            s += button(-2, 65, zz, f'function {NS}:go/s/{ctx.sid}', ctx.title, nav=True, label=False)
            s.append(wall_label(-.05, 66.2, zz + .5, [(f'{ctx.number:02d} {ctx.title}\n', 'white', True), (kind_tag(ctx), 'gray')],
                                .55, 'west', 'wall', f'door list {ctx.sid}'))
    return s


# ---------------------------------------------------------------------------- halls
def stand_enclosure(hall, ctx):
    """Glass or fence front, glass sides and a solid back, so that live actors stay on their stage."""
    x, z = ctx.x0, hall.z0
    south = ctx.row == 'S'
    rail = z + (23 if south else 8)
    back = z + (30 if south else 1)
    right = min(x + 13, hall.xe - 1)
    zlo, zhi = (rail, back) if south else (back, rail)
    out = [fill(x + 1, 65, back, right, 69, back, 'minecraft:calcite'),
           fill(x + 1, 65, zlo, x + 1, 67, zhi, 'minecraft:glass')]
    if x + 13 <= right:
        out.append(fill(x + 13, 65, zlo, x + 13, 67, zhi, 'minecraft:glass'))
    if ctx.kind == 'open':
        # a fence the visitor walks through (a double oak door in the middle, one click opens both halves); actors stay behind it
        line = {(px, rail) for px in range(x + 1, right + 1)}
        doors = {(x + 6, rail): 'left', (x + 7, rail): 'right'}
        for px, pz in sorted(line):
            if (px, pz) in doors:
                for half, y in (('lower', 65), ('upper', 66)):
                    out.append(f'setblock {px} {y} {pz} minecraft:oak_door[facing=north,half={half},hinge={doors[(px, pz)]},open=false]')
                continue
            for y in (65, 66):
                flags = [f'{n}=true' for n, d in (('west', -1), ('east', 1)) if (px + d, pz) in line and (px + d, pz) not in doors]
                out.append(f'setblock {px} {y} {pz} minecraft:oak_fence[{",".join(flags)}]')
    else:
        out.append(fill(x + 1, 65, rail, right, 67, rail, 'minecraft:glass'))
    return out


def stand_lines(hall, ctx):
    """Everything built in the world for one stand: stage, enclosure, boards, buttons."""
    z = hall.z0
    x = ctx.x0
    south = ctx.row == 'S'
    z1, z2 = (24, 29) if south else (2, 7)
    front = z + (21 if south else 10)
    row2 = z + (18 if south else 13)
    stage_edge = z + (z1 if south else z2)
    lines = [fill(x + 2, 65, z + z1, x + 12, 65, z + z2, 'minecraft:cyan_stained_glass' if ctx.water else 'minecraft:smooth_quartz')]
    if ctx.water:
        lines.append(fill(x + 2, 64, z + z1, x + 12, 64, z + z2, 'minecraft:light_blue_concrete'))
    lines += [fill(x + 2, 65, stage_edge, x + 12, 65, stage_edge, f'minecraft:{ctx.color}_concrete'),
              fill(x + 2, 64, z + (19 if south else 12), x + 11, 64, z + (19 if south else 12), f'minecraft:{ctx.color}_terracotta')]
    lines += stand_enclosure(hall, ctx)
    cz = z + (27 if south else 5)
    lines.append(display(x + 7.5, 70.7, cz, [(f'{ctx.number:02d} {ctx.title}', 'gold', True)], 1.1, 'title', f'title {ctx.sid}', billboard='vertical'))
    question = wrap(ctx.question, 44)
    lines.append(display(x + 7.5, 69.6, cz, [('\n'.join(question), 'white')], .55, 'board', f'question {ctx.sid}', billboard='vertical'))
    board_z = z + (22.4 if south else 8.6)
    lines.append(display(x + 7.5, 68.35, board_z, board_parts(ctx.info), .55, 'board', f'board {ctx.sid}', billboard='vertical', align='left', bg=0x99101820))
    lines += ctx.props
    for key, spec in ctx.actors.items():
        lines.append(summon_command(spec))
        lines.append(f'scoreboard players set #actor_{ctx.sid}_{key} {OBJ} 1')
    # row 1: demonstrations, stand buttons, remote sites
    items = []
    for did, caption, steps in ctx.demos:
        items.append((f'function {NS}:demo/{ctx.sid}_{did}', caption, '真实行为' if is_live(steps) else '动作片段', False))
    for caption, hint, fname, nav in ctx.buttons:
        items.append((f'function {NS}:stand/{fname}', caption, hint, nav))
    for site in ctx.sites:
        if not site.get('button_done'):
            items.append((f'function {NS}:stand/{site["visit"]}', site['caption'], site['hint'], True))
    for i, (command, caption, hint, nav) in enumerate(items):
        lines += button(x + 2 + 2 * i, 65, front, command, f'{i + 1}.{caption}', hint, nav=nav, context=f'{ctx.sid} {caption}')
    # row 2: station navigation
    nav_items = [(f'function {NS}:go/lobby', '回大厅', '', True)]
    if ctx.prev:
        nav_items.append((f'function {NS}:go/s/{ctx.prev.sid}', '上一站', f'{ctx.prev.number:02d} {ctx.prev.title}'[:14], True))
    if ctx.next:
        nav_items.append((f'function {NS}:go/s/{ctx.next.sid}', '下一站', f'{ctx.next.number:02d} {ctx.next.title}'[:14], True))
    if resettable(ctx):
        nav_items.append((f'function {NS}:reset/{ctx.sid}', '重置本站', '回到初始状态', False))
    for i, (command, caption, hint, nav) in enumerate(nav_items):
        lines += button(x + 3 + 2 * i, 65, row2, command, caption, hint, nav=nav, context=f'{ctx.sid} nav {caption}', color='aqua')
    return lines


def build_hall(hall, stations, fn):
    z, xe = hall.z0, hall.xe
    lines = [fill(0, 65, z, xe, 74, z + 31, 'minecraft:air'), fill(0, 63, z, xe, 64, z + 31, 'minecraft:smooth_quartz'),
             fill(0, 65, z, xe, 72, z + 31, 'minecraft:calcite', 'outline'), fill(1, 65, z + 1, xe - 1, 71, z + 30, 'minecraft:air'),
             fill(1, 72, z + 1, xe - 1, 72, z + 30, 'minecraft:smooth_quartz'), fill(1, 72, z + 13, xe - 1, 72, z + 18, 'minecraft:glass'),
             fill(0, 65, z + 14, 0, 68, z + 18, 'minecraft:air'), fill(1, 64, z + 13, xe - 1, 64, z + 18, 'minecraft:polished_diorite'),
             fill(2, 64, z + 15, xe - 2, 64, z + 16, f'minecraft:{hall.color}_terracotta'),
             display(3.5, 70.0, z + 16, [(f'{hall.id}厅 · {hall.title}', hall.hex, True)], 1.0, 'title', f'hall {hall.id}', billboard='vertical'),
             display(xe - 6, 69.6, z + 16, [('顺时针参观：先北排，后南排', 'white')], .7, 'banner', f'hall {hall.id} hint', billboard='vertical')]
    for col in range(1, hall.k):
        x = 15 * col - 1
        for z1, z2 in ((1, 11), (20, 30)):
            lines.append(fill(x, 65, z + z1, x, 69, z + z2, 'minecraft:calcite'))
    for col in range(hall.k):
        for lx in (4, 11):
            lines += [f'setblock {15 * col + lx} 72 {z + 7} minecraft:sea_lantern', f'setblock {15 * col + lx} 72 {z + 25} minecraft:sea_lantern']
    if hall.id == 'E':   # east gate to the settlement meadow
        lines.append(fill(xe, 65, z + 14, xe, 71, z + 18, 'minecraft:air'))   # tall gate: the settlement deck looks out over it
    # hall entrance (west) and tail (east) columns: previous / next hall and lobby
    prev_hall = hall.index and (f'function {NS}:go/{museum_scenes.HALLS[hall.index - 1][0].lower()}', '上一厅', museum_scenes.HALLS[hall.index - 1][1])
    next_hall = hall.index + 1 < len(museum_scenes.HALLS) and (f'function {NS}:go/{museum_scenes.HALLS[hall.index + 1][0].lower()}', '下一厅', museum_scenes.HALLS[hall.index + 1][1])
    lobby = (f'function {NS}:go/lobby', '回大厅', '')
    # E's east gate and deck (z+12..z+20 at the east end) need the middle free, so its tail buttons sit one row further out
    top, bottom = z + 12, z + 19
    tail_top, tail_bottom = (z + 10, z + 21) if hall.id == 'E' else (top, bottom)
    for (xb, zz, item) in ((1, top, prev_hall or lobby), (1, bottom, next_hall or lobby), (xe - 1, tail_top, lobby), (xe - 1, tail_bottom, next_hall or lobby)):
        lines += button(xb, 65, zz, item[0], item[1], item[2][:14], nav=True, context=f'hall {hall.id} nav {item[1]}', color='aqua')
    for ctx in hall.contexts:
        lines += stand_lines(hall, ctx)
    for row, x0 in hall.lounges:
        lines += lounge_lines(hall, row, x0)
    if hall.id == 'E':
        lines += meadow_lines(hall)
    return lines


def meadow_lines(hall):
    """The settlement meadow east of hall E. OWNED BY THE SETTLEMENT EXHIBIT: the station entry, deck and time buttons are
    built elsewhere (settle_gate) and only point at this region."""
    z, xe = hall.z0, hall.xe
    mx, my, mz = hall.contexts[3].meadow
    # 64 x 28 lawn: the demo settlement site (15 x 15 plus meadow) at the west end and an empty twin 36 blocks east
    # that the community can move to. MuseumSettlementSetup builds both sites on demand and restores the lawn on reset.
    x1, x2 = xe + 2, xe + 65
    mz0, mz1 = z + 2, z + 29
    lines = [fill(x1 - 1, 63, mz0 - 1, x2 + 1, 64, mz1 + 1, 'minecraft:grass_block'), fill(xe, 63, z + 14, x1, 64, z + 18, 'minecraft:stone_bricks')]
    lines += big_fill(x1, 65, mz0, x2, 75, mz1, 'minecraft:air')
    lines += ring_absolute(x1, mz0, x2, mz1)
    lines += [fill(x1, 65, z + 14, x1, 66, z + 18, 'minecraft:air'), _label(x1 + 3, 68.5, z + 16, '聚落草地\n展台按钮在 E 厅里，东门观察台能看到这里', .8)]
    # Extra forage between and around the two site boxes (the sites bring their own meadow). Gathering consumes
    # these plants; the settlement stands never re-plant them.
    rng = random.Random(1717)
    site_centres = [(mx, mz), (mx + 36, mz)]
    planted = 0
    for _ in range(600):
        px, pz = rng.randint(x1 + 1, x2 - 1), rng.randint(mz0 + 1, mz1 - 1)
        if any(abs(px - sx) <= 11 and abs(pz - sz) <= 11 for sx, sz in site_centres):
            continue
        lines.append(f'setblock {px} 65 {pz} minecraft:' + ('fern' if rng.random() < .12 else 'short_grass'))
        planted += 1
        if planted >= 90:
            break
    return lines


def lounge_lines(hall, row, x0):
    """An unused stand slot becomes a quiet hall map: the stations of this hall and what they are."""
    z = hall.z0
    south = row == 'S'
    z1, z2 = (24, 29) if south else (2, 7)
    cz = z + (27 if south else 5)
    parts = [(f'{hall.id}厅 展台一览\n', hall.hex, True)]
    for ctx in hall.contexts:
        parts.append((f'{ctx.number:02d} {ctx.title}  ', 'white'))
        parts.append((f'{kind_tag(ctx)}\n', 'gray'))
    parts[-1] = (parts[-1][0].rstrip('\n'), parts[-1][1])
    return [fill(x0 + 2, 65, z + z1, x0 + 12, 65, z + z2, f'minecraft:{hall.color}_concrete'),
            display(x0 + 7.5, 67.4, cz, parts, .6, 'board', f'lounge {hall.id}', billboard='vertical')]


def settle_gate(hall, ctx):
    """Station entry for the settlement meadow: a raised observation deck at the hall's east gate, with the way home
    and the two time presets the settlement can use. The deck stands four blocks up so that the demo site (centre 15 east
    of the gate) and its empty twin 36 blocks further east are both in view; the exhibit's own buttons and the meadow are
    not touched here."""
    assert ctx.sid == 'settle'
    z, xe = hall.z0, hall.xe
    top = 69
    deck_lines, land = deck(xe - 3, z + 12, xe - 1, z + 20, top, None, 'settle deck')
    near = f'@a[x={xe - 2},y={top},z={z + 16},distance=..30]'
    ctx.sites.append(dict(id='settle', caption='去东门观察台', hint='看聚落草地', land=land, visit='settle_visit', release=None))
    ctx.functions['settle_visit'] = [f'tp @s {land[0]} {land[1]} {land[2]} {land[3]} 10',
                                     'tellraw @s ' + _text('东门观察台：向东望出东门，近处是演示站点，再往东 36 格是迁居用的空站点。台上有回展台、回大厅，以及“清晨集会”和“正午”两个时间按钮。', 'aqua')]
    ctx.functions['settle_morning'] = ['time set 100', 'weather clear',
                                       f'tellraw {near} ' + _text('已设为清晨：春季清晨聚落可能集合；其他季节不一定。看完请按“正午”。', 'aqua')]
    ctx.functions['settle_noon'] = [f'function {NS}:env/default', f'tellraw {near} ' + _text('已恢复正午晴天。', 'green')]
    # the stage is a small turf model of the meadow, so the station is not an empty platform
    ctx.fill_cells(0, 0, 10, 5, 'minecraft:grass_block', dy=-1)
    for bu, bv in ((1, 1), (3, 4), (5, 2), (7, 4), (9, 1), (4, 0), (8, 3)):
        ctx.block(bu, bv, 'minecraft:short_grass')
    ctx.label(5.5, 3, 2.2, '聚落草地在东门外：走到大厅东端的观察台看', .6)
    ctx.props.append(fill(xe - 3, 65, z + 12, xe - 1, 65, z + 20, 'minecraft:oak_planks'))
    ctx.props.extend(deck_lines)
    ctx.props += museum_sites.deck_controls(ctx, xe - 2, top, z + 16, [
        (f'function {NS}:stand/settle_morning', '清晨集会', '时间设为清晨'),
        (f'function {NS}:stand/settle_noon', '正午', '恢复正午晴天')])
    ctx.props.append(display(xe - 1.5, top + 3.2, z + 16, [('聚落观察台\n', 'gold', True), ('向东望出东门', 'white')], .8, 'banner', 'settle deck banner', billboard='vertical'))


# ---------------------------------------------------------------------------- per-station functions
def build_station_functions(fn, ctx, badge_actors):
    sid = ctx.sid
    near = ctx.near()
    for name, body in ctx.functions.items():
        fn(f'stand/{name}', body)
    if not resettable(ctx):
        return
    x, z = ctx.centre()
    south = ctx.row == 'S'
    zlo, zhi = (ctx.z0 + 24, ctx.z0 + 29) if south else (ctx.z0 + 2, ctx.z0 + 7)
    # stray items on the stage (apples, droppings, tools); aquatic supplies belong to the boats and are left alone
    clean = [f'kill @e[type=minecraft:item,x={ctx.x0 + 1},y=65,z={zlo},dx=13,dy=8,dz={zhi - zlo},tag=!pm_aquatic_supply]']
    fn(f'clean/{sid}', clean)
    stop = [line for line in ctx.stop_before if line.startswith('schedule clear ')]
    for did, caption, steps in ctx.demos:
        stop += [f'schedule clear {NS}:scene/{sid}_{did}/{i}' for i in range(len(steps) + 1)]
    # Never reset the remaining cast while one actor is captured, dead or unloaded.
    for key in ctx.actors:
        stop += [f'execute unless entity {ctx.sel(key)} run tellraw {near} ' + _text(f'无法重置“{ctx.title}”：有演员缺失、被收容或未加载。把它带回来，或靠近展台后再试。', 'red'),
                 f'execute unless entity {ctx.sel(key)} run return 0']
    stop += [f'yukkuri museum stop @e[tag=room_{sid}]',
             f'execute if entity @e[tag=room_{sid},tag=pm_stop_failed] run tellraw {near} ' + _text(
                 f'“{ctx.title}”还有真实过程没有结束（生产、接生、落水、上岸或独立进行中），暂时不能重置：等它结束，或先处理后再试。', 'red'),
             f'execute if entity @e[tag=room_{sid},tag=pm_stop_failed] run return 0']
    stop += [line for line in ctx.stop_before if not line.startswith('schedule clear ')]
    for key, spec in ctx.actors.items():
        if not spec['human'] and (sid, key) in badge_actors:
            stop.append(f'execute as {ctx.sel(key)} unless data entity @s YukkuriPet{{Grade:{spec["grade"]}}} run data modify entity @s YukkuriPet.Grade set value {spec["grade"]}')
    if any(not s['human'] for s in ctx.actors.values()):
        stop.append(f'yukkuri action @e[tag=room_{sid}] idle')
    for key in ctx.actors:
        stop.append('tp ' + ctx.sel(key) + ' {} {} {} {} 0'.format(*ctx.home(key)))
    stop += [f'kill @e[type=minecraft:item,tag=pm_{sid}_fx]'] + ctx.stop_extra
    if ctx.reset_life:
        stop += [f'execute as {ctx.sel(key)} run function {NS}:util/life_reset' for key, s in ctx.actors.items() if not s['human']]
    stop.append(f'function {NS}:clean/{sid}')
    stop.append('return 1')
    fn(f'stop/{sid}', stop)
    fn(f'reset/{sid}', [f'execute store result score #r {OBJ} run function {NS}:stop/{sid}',
                        f'execute if score #r {OBJ} matches 1 run tellraw {near} ' + _text(f'已重置：{ctx.title}。', 'green')])
    fn(f'failed/{sid}', [f'tellraw {near} ' + _text('展台移动未到达目标，演示已停止；请按“重置本站”后重试。', 'red'),
                         f'function {NS}:stop/{sid}'])
    for did, caption, steps in ctx.demos:
        live = is_live(steps)
        demo = []
        if ctx.guard_live:
            demo.append(f'execute if entity @e[tag=room_{sid},tag=pm_live] run return run tellraw {near} ' + _text(
                '这里有真实行为正在进行；再按按钮会把它重置。要重来请先按“重置本站”。', 'yellow'))
        demo += [f'execute store result score #reset {OBJ} run function {NS}:stop/{sid}',
                 f'execute unless score #reset {OBJ} matches 1 run return 0',
                 f'function {NS}:env/{sid}_demo',
                 f'tellraw {near} ' + _text(f'▶ {caption}' + (' · 观察窗口内可随时离开，结束后行为继续' if live else ''), 'gold'),
                 f'function {NS}:scene/{sid}_{did}/0']
        fn(f'demo/{sid}_{did}', demo)
        for i, (ticks_, cmds) in enumerate(steps):
            step = f'scene/{sid}_{did}/{i}'
            guard = [f'execute if entity @e[tag=room_{sid},tag=pm_walk_failed] run return run function {NS}:failed/{sid}',
                     f'execute if entity @e[tag=room_{sid},tag=pm_walking] run schedule function {NS}:{step} 2t replace',
                     f'execute if entity @e[tag=room_{sid},tag=pm_walking] run return 0']
            fn(step, guard + [c[3] for c in cmds] + [f'schedule function {NS}:scene/{sid}_{did}/{i + 1} {ticks_}t replace'])
        last = f'scene/{sid}_{did}/{len(steps)}'
        guard = [f'execute if entity @e[tag=room_{sid},tag=pm_walk_failed] run return run function {NS}:failed/{sid}',
                 f'execute if entity @e[tag=room_{sid},tag=pm_walking] run schedule function {NS}:{last} 2t replace',
                 f'execute if entity @e[tag=room_{sid},tag=pm_walking] run return 0']
        if live:
            # A real process is never rolled back by the museum: only say that the window is over.
            fn(last, guard + [f'tellraw {near} ' + _text('观察窗口结束：行为仍在继续，结果留在台上。想回到初始状态请按“重置本站”。', 'yellow')])
        else:
            fn(last, guard + [f'function {NS}:stop/{sid}'])


# ============================================================================== catalogue
def write_catalogue(path, halls, shown):
    clips, internal = load_clips()
    where = {}
    for hall in halls:
        for ctx in hall.contexts:
            for _, _, steps in ctx.demos:
                for _, cmds in steps:
                    for c in cmds:
                        if c[0] == 'action':
                            where.setdefault(c[2], set()).add(f'{ctx.number:02d} {ctx.title}')
                        elif c[0] == 'contact':
                            for actor, clip in ctx.contact_clips(c[1], *c[2]):
                                where.setdefault(clip, set()).add(f'{ctx.number:02d} {ctx.title}')
    lines = ['# 展馆动作清单', '',
             f'当前 `YukkuriAction` 注册 {len(ACTION_META)} 个动作。此表从注册表、19 套基础模型和动作修订联合生成；“资源适配”指确实存在动画片段，不等于每个年龄、亚种都适合该情景。'
             '水生魔理沙与普通魔理沙共用模型，展台按生态身份分开。', '',
             f'展馆（五厅，{sum(len(h.contexts) for h in halls)} 个展台）按情景演示其中 {len(shown)} 个动作（含待机），其余收录在目录，不强行放入参观路线。'
             '身体与表情保留动作片段展示；生活、照护、独立、水生与聚落入口设置有限初始条件，交给普通服务器玩法。出生庭院使用真实双亲及唯一后代，不预放幼体、不回滚胎次。按钮清单包含指令片段与真实玩法入口；动作覆盖数只统计显式片段，不代表自主行为缺失。', '',
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
              '维护入口：`scripts/build_museum_full.py --install`（生成新世界）；本清单用 `--catalog docs/MOTION_MUSEUM_ACTIONS.md --pack-only --output <新目录>` 重新生成。', '']
    path.write_text('\n'.join(lines), encoding='utf-8')


# ============================================================================== world
def inputs_sha256():
    """Hash of everything that decides what the generated world looks like."""
    h = hashlib.sha256()
    assets = ROOT / 'src/main/resources/assets/mcyukkuri'
    files = (sorted(HERE.glob('museum_*.py')) + [Path(__file__).resolve(), ROOT / 'src/main/java/io/github/longshou/mcyukkuri/entity/YukkuriAction.java',
                                                  ROOT / 'src/main/resources/assets/mcyukkuri/dialogue/catalog.json',
                                                  ROOT / 'art/props/runtime-peripheral/selected/yukkuri_carrier/yukkuri_carrier.mesh.json']
             + sorted((assets / 'yukkuri').glob('*.json')) + sorted((assets / 'yukkuri_presentation').glob('*.json')))
    for path in files:
        h.update(path.name.encode())
        h.update(path.read_bytes().replace(b'\r\n', b'\n'))
    return h.hexdigest()


def find_seed(target):
    """The seed level.dat only supplies world metadata. Copy it once from any earlier museum world."""
    if target.exists():
        return target
    candidates = []
    runtime = STAGING / 'museum-runtime.json'
    if runtime.exists():
        config = json.loads(runtime.read_text(encoding='utf-8'))
        candidates.append(ROOT / config['game'] / 'saves' / config['world'] / 'level.dat')
    candidates += sorted(STAGING.glob('*/saves/*/level.dat'), key=lambda p: p.stat().st_mtime, reverse=True)
    for candidate in candidates:
        if candidate.exists():
            target.parent.mkdir(parents=True, exist_ok=True)
            shutil.copyfile(candidate, target)
            return target
    raise SystemExit(f'No seed level.dat: put any Minecraft 1.21.1 level.dat at {target}')


def level_metadata(seed):
    """level.dat metadata of the museum world, derived from any 1.21.1 level.dat (no chunks, entities or players)."""
    import nbtlib as n
    metadata = n.load(seed)
    d = metadata['Data']
    if 'fml' in metadata:  # drop dev-only mods that an older seed world may still list
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
    # a flat world whatever the seed was: the generator is replaced as a whole
    generator = d['WorldGenSettings']['dimensions']['minecraft:overworld']['generator']
    generator['type'] = n.String('minecraft:flat')
    generator['settings'] = n.Compound({
        'biome': n.String('minecraft:plains'), 'features': n.Byte(0), 'lakes': n.Byte(0),
        'structure_overrides': n.List[n.String]([]),
        'layers': n.List[n.Compound]([n.Compound({'block': n.String(b), 'height': n.Int(h)}) for b, h in
                                      (('minecraft:bedrock', 1), ('minecraft:stone', 124), ('minecraft:dirt', 2), ('minecraft:grass_block', 1))])})
    for k, v in {'doMobSpawning': 'false', 'doDaylightCycle': 'false', 'doWeatherCycle': 'false', 'keepInventory': 'true', 'spawnRadius': '0',
                 'commandBlockOutput': 'false', 'sendCommandFeedback': 'false', 'doFireTick': 'false'}.items():
        d['GameRules'][k] = n.String(v)
    return metadata


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument('--seed-level', type=Path)
    parser.add_argument('--pack-only', action='store_true', help='Generate a staged data pack only, without level.dat or options.txt')
    parser.add_argument('--output', type=Path)
    parser.add_argument('--catalog', type=Path)
    parser.add_argument('--install', action='store_true', help='Replace .local/experimental-map/museum with a freshly generated world')
    parser.add_argument('--stale', action='store_true', help='Exit with status 1 when the installed world does not match the generator inputs')
    args = parser.parse_args()
    installed = STAGING / 'museum'
    if args.stale:
        manifest = installed / 'map-manifest.json'
        current = json.loads(manifest.read_text(encoding='utf-8')).get('inputs_sha256') if manifest.exists() else None
        print('current' if current == inputs_sha256() else 'stale')
        raise SystemExit(0 if current == inputs_sha256() else 1)
    if args.install:
        out = installed.resolve()
        assert out.parent == STAGING.resolve() and out.name == 'museum', out
        args.seed_level = args.seed_level or find_seed(STAGING / 'museum-seed' / 'level.dat')
    else:
        if not args.output:
            parser.error('--output is required unless --install is selected')
        if not args.pack_only and not args.seed_level:
            parser.error('--seed-level is required unless --pack-only or --install is selected')
        out = args.output.resolve()
        staging_root = STAGING.resolve()
        if out == staging_root or not out.is_relative_to(staging_root):
            raise ValueError('Output must be a new directory below a .local/experimental-map folder')
    # Everything that can fail is done before the old world is touched: layout, validation and the level metadata.
    halls = build_layout()
    shown, speech = validate(halls)
    metadata = level_metadata(args.seed_level) if not args.pack_only else None
    final = out
    if args.install:
        # built beside the old world and swapped in only when everything succeeded
        out = final.with_name('museum-building')
        if out.exists():
            shutil.rmtree(out)
    out.mkdir(parents=True, exist_ok=False)
    world = out / 'saves' / WORLD
    world.mkdir(parents=True)
    if metadata is not None:
        metadata.save(world / 'level.dat')
    checks = build_pack(world, halls, shown)
    if museum_sites.VIOLATIONS:
        for violation in museum_sites.VIOLATIONS:
            print('TEXT DOES NOT FIT', violation, file=sys.stderr)
        raise SystemExit(f'{len(museum_sites.VIOLATIONS)} text displays do not fit their board or label')
    stations = [ctx for hall in halls for ctx in hall.contexts]
    manifest = {'world': str(final / 'saves' / WORLD), 'minecraft': '1.21.1', 'inputs_sha256': inputs_sha256(),
                'halls': [{'id': h.id, 'title': h.title, 'z0': h.z0, 'width': h.xe + 2,
                           'stands': [{'number': c.number, 'id': c.sid, 'title': c.title, 'row': c.row, 'x0': c.x0, 'landing': list(c.land),
                                       'actors': list(c.actors), 'demos': [(d[0], d[1]) for d in c.demos],
                                       'sites': [{'id': s['id'], 'landing': list(s['land'])} for s in c.sites]} for c in h.contexts]}
                          for h in halls],
                'registered_actions': len(ACTION_META), 'displayed_actions': sorted(shown), 'speech_events': sorted({s[2] for s in speech}),
                'checks': {k: v for k, v in checks.items() if k != 'demos'},
                'scenes': [{'hall': h, 'stand': s, 'id': i, 'caption': c, 'ticks': t} for h, s, i, c, t, _ in checks['demos']]}
    (out / 'map-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    if not args.pack_only:
        (out / 'options.txt').write_text('lang:zh_cn\nguiScale:0\nrenderDistance:10\nsimulationDistance:8\nmaxFps:120\nonboardAccessibility:false\nautoJump:false\n'
                                         'pauseOnLostFocus:false\ntutorialStep:none\nnarrator:0\n', encoding='utf-8')
    if args.catalog:
        write_catalogue(args.catalog, halls, shown)
    if args.install:
        if final.exists():
            shutil.rmtree(final)
        out.rename(final)
        out = final
    print(json.dumps({'output': str(out), 'halls': len(halls), 'stands': len(stations), 'registered_actions': len(ACTION_META),
                      'displayed_actions': len(shown), **{k: v for k, v in checks.items() if k != 'demos'}}, ensure_ascii=False))


if __name__ == '__main__':
    main()
