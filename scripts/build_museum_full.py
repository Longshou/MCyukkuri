"""Build the MCyukkuri playground (试玩场), the single in-game test world (Minecraft 1.21.1, NeoForge, Python 3.11+).

Open fields of real ground (meadow, town, pond, house, hill with caves, woods, snow, settlement meadow,
motion-clip corner), each with a raised observation deck. A lobby lists every field and scenario by name. Every scenario
creates real individuals with ordinary AI and only sets their real starting state; "clear this field" removes every mod
entity and dropped item in the field's box (and those marked as having stood in it), makes the world records forget what
the field left behind and rebuilds its terrain, so nothing is ever refused and a scenario starts again from the same state. Minecraft builds the map from the generated data pack on first entry; this script writes the pack,
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
``.local/experimental-map``. Fields are in museum_fields.py, scenarios in museum_scenarios.py, spawn vocabulary in
museum_dsl.py, buttons and labels in museum_sites.py.
"""
from pathlib import Path
import argparse
import hashlib
import json
import math
import re
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
from museum_dsl import _text, _q, yk  # noqa: E402
import museum_fields  # noqa: E402
from museum_fields import FIELDS, BY_ID, GROUPS, GROUND, FEET, SKY, DECK_TOP, big_fill, fillbiome_boxes  # noqa: E402
import museum_scenarios  # noqa: E402
import museum_sites  # noqa: E402
from museum_sites import button, display, deck, wrap, units, CONTROLS  # noqa: E402

NS = 'mcylab'
OBJ = 'museum'
WORLD = 'MCyukkuri-Museum'
SPAWN = (0, FEET, 36, 180)
LOBBY = (-42, -36, 42, 42)            # x1, z1, x2, z2 of the lobby plaza
STAGING = (ROOT / '.local/experimental-map')
ENV_TIME = {'noon': 'noon', 'dawn': '100', 'dusk': '12500', 'night': 'midnight'}
SPEEDS = (('1x', 20), ('3x', 60), ('5x', 100), ('10x', 200), ('20x', 400))
SPRINTS = (('1分钟', 1200), ('5分钟', 6000), ('20分钟', 24000))
CLIP_ACTORS = ('reimu', 'marisa', 'chen', 'alice', 'patchouli', 'remilia', 'flandre', 'house_reimu', 'house_marisa')
# categories played by the motion-clip corner; family and hat actions are driven by real behaviour in the scenarios
CLIP_CATEGORIES = ('身体与移动', '日常生活', '交流与情绪', '宠物店与表情', '运输与篮子')
BEHAVIOUR_PLACES = {'家庭与照料': '接生帽、窝与地面的接生场景、嚼糜与首便、学起跳',
                    '帽子与头饰': '放下的帽子、帽子损坏与拼回',
                    '特殊身体': '仅目录'}


def js(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':'))


def rect_chunks(rect):
    x1, z1, x2, z2 = rect
    return {(x, z) for x in range(x1 // 16 * 16, x2 // 16 * 16 + 1, 16) for z in range(z1 // 16 * 16, z2 // 16 * 16 + 1, 16)}


def field_mark(f):
    """Entity tag of everything that has stood in this field's box since its last clear (util/tag_fields)."""
    return f'fld_{f.id}'


def near_field(f, radius=110):
    return f'@a[x={f.cx},y={FEET},z={f.cz},distance=..{radius}]'


def env_commands(env):
    weather = {'rain': 'weather rain 1000000', 'thunder': 'weather thunder 1000000'}.get(env.get('weather'), 'weather clear')
    return [f'time set {ENV_TIME[env.get("time", "noon")]}', weather]


# ============================================================================== layout
def action_ticks(action):
    """Time a clip needs; looping clips get two cycles so the loop is seen."""
    seconds, loops = ACTION_META[action]
    if loops:
        return max(20, min(200, math.ceil(seconds * 20) * 2))
    return max(20, math.ceil(seconds * 20))


def clip_chains():
    """[(category, [(action, ticks)])]: the sequence each clip button plays on every cast model that has the clip."""
    clips, _ = load_clips()
    out = []
    for group, entries in CATEGORIES:
        if group not in CLIP_CATEGORIES:
            continue
        out.append((group, [(a, action_ticks(a)) for a, _ in entries if a != 'sleep' and any(a in clips[m] for m in CLIP_ACTORS)]))
    return out


def validate():
    clips, _ = load_clips()
    ids = set()
    for scenario in museum_scenarios.S:
        assert scenario.id not in ids, scenario.id
        ids.add(scenario.id)
        assert scenario.field in BY_ID, scenario.id
    for group, seq in clip_chains():
        for action, _ in seq:
            assert action in ACTION_META and any(action in clips[m] for m in CLIP_ACTORS), action


# ============================================================================== guide book
def book_command(shown):
    pages = ['油库里试玩场\n\n一块块开放的真实场地：玩法在里面按普通规则自然运行，没有玻璃罩，没有被固定的演员。\n\n'
             '1 大厅只有传送按钮：按主题分组，每个按钮去一个区的观察台。\n'
             '2 观察台上：落点面向按钮，近处是常用操作，再往里依次是倍速推进、时间天气、场景。红色清场按钮在常用排最右侧，单独隔开。\n'
             '3 生成时会把你带到场景旁；聊天栏里的“[回观察台]”“[回大厅]”可点。每个区的南边也有回观察台／回大厅按钮。\n\n回大厅：/function mcylab:home']
    for gid, gtitle in GROUPS:
        for f in FIELDS:
            if f.group != gid:
                continue
            lines = [f'{gtitle} · {f.title}', f.blurb, '']
            for s in f.scenarios:
                lines.append(f'· {s.title}' + (f'（{s.tag}）' if s.tag else ''))
            pages.append('\n'.join(lines))
    pages.append('说明\n\n场景只写真实的起始状态（饥饿、困倦、年龄、亲子、怀孕、伤、放下的帽子……），之后全由普通 AI 接手。\n'
                 '倍速与“推进”：观察台入口第二排；推进是快进若干分钟再恢复原速。第一排可恢复正常速度。\n观察标签：头顶显示行为、饥饿、困倦、年龄、成长、伤。\n'
                 '场景也可在任意平地用 /function 执行 mcylab 的 scn/场景id 生成（洞穴场景需要对应地形）；它不清场，要从同一初始状态重来请按观察台的场景按钮。')
    for group, entries in CATEGORIES:
        for start in range(0, len(entries), 7):
            pages.append(group + ' · 动作目录\n\n' + '\n'.join(('● ' if a in shown else '○ ') + name for a, name in entries[start:start + 7]) + '\n\n● 动作片段区播放  ○ 仅目录／由真实行为触发')
    items = ','.join(_q(js({'text': page})) for page in pages)
    return 'give @s minecraft:written_book[minecraft:written_book_content={title:"油库里试玩场导览",author:"MCyukkuri",pages:[' + items + ']}]'


# ============================================================================== datapack
def probe_lines(all_lines):
    """Commands, blocks and entities that the running mod must know. Each group is its own function: when the running mod
    is older than this pack, a function that mentions an unknown command or block fails to load, and the load function
    reports exactly that instead of letting parts of the map silently go missing."""
    shapes, blocks, items, entities = set(), set(), set(), set()
    for line in all_lines:
        for m in re.finditer(r'(?<![\w:"/.#])yukkuri ([a-z_]+)(?: ([a-z_]+))?', line):
            head, second = m.group(1), m.group(2)
            if head in ('scenario', 'settlement'):
                shapes.add(f'{head} {second}')
            elif head in ('clear', 'observe', 'action', 'speed', 'relay', 'body'):
                shapes.add(head)
        for m in re.finditer(r'(?:setblock|fill) [^\n]*? (mcyukkuri:[a-z_]+)', line):
            blocks.add(m.group(1))
        for m in re.finditer(r'give @s (mcyukkuri:[a-z_]+)', line):
            items.add(m.group(1))
        for m in re.finditer(r'summon (mcyukkuri:[a-z_]+)', line):
            entities.add(m.group(1))
    return shapes, sorted(blocks), sorted(items), sorted(entities)


PROBE_COMMANDS = {
    'scenario wound': 'yukkuri scenario wound @s', 'scenario bond': 'yukkuri scenario bond @s @s', 'scenario mate': 'yukkuri scenario mate @s @s', 'scenario kin': 'yukkuri scenario kin @s @s false',
    'scenario aftermath': 'yukkuri scenario aftermath @s @s',
    'scenario sumo': 'yukkuri scenario sumo @s @s', 'scenario badge': 'yukkuri scenario badge @s silver true', 'scenario stud': 'yukkuri scenario stud @s @s',
    'scenario home': 'yukkuri scenario home @s 0 0 0', 'scenario twigs': 'yukkuri scenario twigs @s 2', 'scenario birth': 'yukkuri scenario birth @s @s natural',
    'scenario eggs': 'yukkuri scenario eggs @s @s',
    'scenario afloat': 'yukkuri scenario afloat @s 0 0 0 true', 'scenario spawn': 'yukkuri scenario spawn 0 0 0 1',
    'scenario independence': 'yukkuri scenario independence @s @s 0 0 0', 'clear': 'yukkuri clear 0 0 0 0 0 0 fld_probe',
    'observe': 'yukkuri observe on', 'action': 'yukkuri action @s idle',
    'settlement spawn': 'yukkuri settlement spawn band 0 0 0',
    'settlement site': 'yukkuri settlement site vacant village 0 0 0',
    'settlement migrate': 'yukkuri settlement migrate', 'settlement slave': 'yukkuri settlement slave @s', 'settlement rules': 'yukkuri settlement rules', 'body': 'yukkuri body rot @s 0',
    'speed': 'yukkuri speed rate 20', 'relay': 'yukkuri relay yukkuri status @s'}


def build_pack(world):
    root = world / 'datapacks' / 'mcylab'
    functions = root / 'data' / NS / 'function'
    names, texts = [], {}

    def fn(name, lines):
        assert name not in names, name
        assert len(lines) <= 20000, (name, len(lines))
        names.append(name)
        texts[name] = list(lines)
        path = functions / (name + '.mcfunction')
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('\n'.join(lines) + '\n', encoding='utf-8')

    def guarded(name, chunks, lines):
        """A build step runs only when every chunk it touches is loaded; otherwise it asks to be run again in a second."""
        return [f'execute unless loaded {x + 8} {FEET} {z + 8} run return run schedule function {NS}:{name} 1s replace' for x, z in sorted(chunks)] + lines

    root.mkdir(parents=True, exist_ok=True)
    (root / 'pack.mcmeta').write_text(js({'pack': {'pack_format': 48, 'description': '油库里试玩场'}}), encoding='utf-8')
    for kind in ('load', 'tick'):
        p = root / 'data/minecraft/tags/function' / f'{kind}.json'
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_text(js({'values': [f'{NS}:{kind}']}), encoding='utf-8')
    types = root / 'data' / NS / 'tags/entity_type/yukkuri.json'
    types.parent.mkdir(parents=True, exist_ok=True)
    types.write_text(js({'values': [f'mcyukkuri:{k}' for k in ('reimu', 'marisa', 'chen', 'alice', 'patchouli', 'remilia', 'flandre', 'myon', 'house_reimu', 'house_marisa')]}),
                     encoding='utf-8')

    # ---------------------------------------------------------------- field and scenario functions (collected first, probed below)
    deck_info = {}
    for f in FIELDS:
        f.extras = []
    for f in FIELDS:
        x1, z1, x2, z2 = f.deck
        deck_lines, land = deck(x1, z1, x2, z2, DECK_TOP, 'south', f'{f.id} deck')
        # Arrive at the open edge facing the controls, rather than facing away into the field.
        land = (*land[:3], 180)
        deck_info[f.id] = (deck_lines, land)
        fn(f'field/{f.id}/terrain', f.terrain())
        bx1, by1, bz1, bx2, by2, bz2 = f.clear_box()
        cancel = []
        for scenario in f.scenarios:
            if scenario.late:
                cancel.append(f'schedule clear {NS}:scn/{scenario.id}_late')
                for ri, retry in enumerate(r for r in scenario.late if not isinstance(r, str)):
                    cancel += [f'schedule clear {NS}:scn/{scenario.id}_retry{ri}_{k}' for k in range(retry.tries)]
        if f.id == 'clips':
            cancel += [f'schedule clear {NS}:clip/{ci}/{i}' for ci, (_, seq) in enumerate(clip_chains()) for i in range(len(seq) + 1)]
            cancel.append(f'schedule clear {NS}:clip/rest')
        # The field mark (util/tag_fields) also takes the individuals and drops that wandered out of the box since the scene began.
        clear_entities = f'yukkuri clear {bx1} {by1} {bz1} {bx2} {by2} {bz2} {field_mark(f)}'
        trial_cleanup = []
        if f.id == 'body':
            # Real injury tools leave vanilla projectiles and primed explosives in this field.
            for kind in ('arrow','spectral_arrow','trident','tnt'):
                trial_cleanup += [f'kill @e[type=minecraft:{kind},tag={field_mark(f)}]',
                    f'kill @e[type=minecraft:{kind},x={bx1},y={by1},z={bz1},dx={bx2-bx1},dy={by2-by1},dz={bz2-bz1}]']
        sweep = cancel + trial_cleanup + [clear_entities,
                 f'kill @e[type=!minecraft:player,tag=scn_prop,x={bx1},y={by1},z={bz1},dx={bx2 - bx1},dy={by2 - by1},dz={bz2 - bz1}]',
                 f'function {NS}:field/{f.id}/terrain',
                 clear_entities]  # Replacing nests and containers can create fresh drops after the first sweep.
        fn(f'field/{f.id}/clear', sweep + ([f'function {NS}:clip/cast'] if f.id == 'clips' else [])
           + [f'tellraw {near_field(f)} ' + _text(f'{f.title}：已清空（本场地所有油库里、遗骸、放下的帽子、掉落物和走出场地的个体，家族与认知等记录，地形已恢复）。', 'green')])
        # Keep manual diagnostic functions available without putting readout buttons on the deck.
        fn(f'read/{f.id}/lead', [f'execute positioned {f.cx} {FEET} {f.cz} run yukkuri readout yukkuri status @e[tag=scn_lead,limit=1,sort=nearest,distance=..140] raw'])
    for s in museum_scenarios.S:
        f = BY_ID[s.field]
        ax, az = s.anchor
        body = s.body()
        if s.late:
            late_lines = [f'execute positioned {ax} {FEET} {az} run {line}' for line in s.late if isinstance(line, str)]
            for ri, retry in enumerate(r for r in s.late if not isinstance(r, str)):
                # a command the game may refuse for a while: attempt it every few ticks, only once its guard matches, a bounded number of times
                base = f'scn/{s.id}_retry{ri}'
                late_lines.append(f'schedule function {NS}:{base}_0 {retry.first}t replace')
                fn(f'{base}_try', [f'return run {retry.command}'])
                for k in range(retry.tries):
                    nxt = f'schedule function {NS}:{base}_{k + 1} %dt replace' if k + 1 < retry.tries else ''
                    fn(f'{base}_{k}', ([f'execute positioned {ax} {FEET} {az} unless entity {retry.guard} run ' + nxt % 20,
                                        f'execute positioned {ax} {FEET} {az} if entity {retry.guard} unless function {NS}:{base}_try run ' + nxt % retry.every]
                                       if nxt else [f'execute positioned {ax} {FEET} {az} if entity {retry.guard} run function {NS}:{base}_try']))
            fn(f'scn/{s.id}_late', late_lines)
            body = body + [f'schedule function {NS}:scn/{s.id}_late 40t replace']
        fn(f'scn/{s.id}', body)
        clear_fn = f'field/{f.id}/clear_{s.sub}' if s.sub else f'field/{f.id}/clear'
        start = [f'function {NS}:{clear_fn}'] + env_commands(s.env) + ['yukkuri observe on', f'execute positioned {ax} {FEET} {az} run function {NS}:scn/{s.id}']
        if s.vantage is not None:
            vdx, vdz = s.vantage[0], s.vantage[1]
            # The common two-value view is the north observation line, looking south. Explicit three-value views
            # keep their side-on angle for water routes and paired kin demonstrations.
            if len(s.vantage) > 2:
                yaw = s.vantage[2]
            else:
                vdz = -abs(vdz)
                yaw = 0
            start += [f'tp @s {ax + vdx + .5} {FEET + 2} {az + vdz + .5} {yaw} 20',
                      'tellraw @s ' + js([{'text': '到了。', 'color': 'gray'},
                                          {'text': ' [回观察台]', 'color': 'aqua', 'clickEvent': {'action': 'run_command', 'value': f'/function {NS}:go/{f.id}'}},
                                          {'text': ' [回大厅]', 'color': 'gold', 'clickEvent': {'action': 'run_command', 'value': f'/function {NS}:go/lobby'}}])]
        fn(f'start/{s.id}', start)
        for i, (caption, command) in enumerate(s.reads):
            # Manual /function diagnostics keep the original anchor and executing player.
            fn(f'read/{s.id}_{i}', [f'execute positioned {ax} {FEET} {az} run yukkuri readout {command}'])

    # extras: the house activity buttons and clips
    house = BY_ID['house']
    hx, hz = house.anchor
    for name, caption, hint, lines in (
            ('house_activity', '模拟人类活动', '住一天的痕迹', [f'execute positioned {hx} {FEET} {hz} run yukkuri household activity 24000 false true']),
            ('house_sleepover', '睡一晚', '人类睡在屋里', [f'execute positioned {hx} {FEET} {hz} run yukkuri household activity 24000 true true'])):
        # a house yukkuri only moves in while mob spawning is on (HouseholdEcology.enabled); the museum keeps it off, so open it for this one call
        fn(f'extra/{name}', ['gamerule doMobSpawning true'] + lines + ['gamerule doMobSpawning false', 'tellraw @a[distance=..60] ' + _text('已模拟一段人类活动：看屋里落下的絮尘和食屑，随后家油搬入。', 'gold')])
        house.extras.append((f'extra/{name}', caption, hint))
    clips = BY_ID['clips']
    chains = clip_chains()
    clip_lookup, _ = load_clips()
    shown = {'idle'}
    for ci, (group, seq) in enumerate(chains):
        for i, (action, length) in enumerate(seq):
            lines = [f'yukkuri action @e[tag=scn_{m}] {action}' for m in CLIP_ACTORS if action in clip_lookup[m]]
            shown.add(action)
            lines.append(f'schedule function {NS}:clip/{ci}/{i + 1} {length}t replace')
            fn(f'clip/{ci}/{i}', lines)
        fn(f'clip/{ci}/{len(seq)}', ['yukkuri action @e[tag=scn_clip_actor] idle'])
        clips.extras.append((f'clip/{ci}/0', group, f'{len(seq)} 个动作'))
    fn('clip/chorus', ['yukkuri action @e[tag=scn_clip_actor] sing', f'schedule function {NS}:clip/rest 120t replace'])
    fn('clip/taiso', ['yukkuri action @e[tag=scn_clip_actor] taiso', f'schedule function {NS}:clip/rest 200t replace'])
    fn('clip/rest', ['yukkuri action @e[tag=scn_clip_actor] idle'])
    clips.extras += [('clip/chorus', '全体合唱', '每只都唱'), ('clip/taiso', '全体体操', '广播体操')]
    # the only fixed individuals of the playground: nine clip actors that merely play motion clips
    cast = [f'execute positioned {clips.cx - 16 + 4 * i} {FEET} {clips.cz - 2} run ' + yk(model, 0, 0, model, yaw=0, also=('clip_actor',), extra='NoAI:1b,Invulnerable:1b')
            for i, model in enumerate(CLIP_ACTORS)]
    fn('clip/cast', ['kill @e[tag=scn_clip_actor]'] + cast)

    # ---------------------------------------------------------------- utilities
    for tag_, command, text in (('dawn', 'time set 100', '清晨'), ('noon', 'time set noon', '正午'), ('dusk', 'time set 12500', '黄昏'), ('night', 'time set midnight', '夜晚')):
        fn(f'util/time_{tag_}', [command, 'tellraw @a ' + _text(f'时间：{text}。', 'gray')])
    fn('util/weather_clear', ['weather clear', 'tellraw @a ' + _text('天气：晴。', 'gray')])
    fn('util/weather_rain', ['weather rain 1000000', 'tellraw @a ' + _text('天气：雨（寒冷群系下雪）。', 'gray')])
    fn('util/weather_thunder', ['weather thunder 1000000', 'tellraw @a ' + _text('天气：雷暴。', 'gray')])
    # vanilla /tick needs permission level 3 and functions run at level 2, so the speed buttons use the mod's own commands
    for label, rate in SPEEDS:
        fn(f'util/speed_{label}', [f'yukkuri speed rate {rate}'])
    for label, ticks_ in SPRINTS:
        fn(f'util/sprint_{ticks_}', [f'yukkuri speed sprint {ticks_}'])
    fn('util/spectator', ['gamemode spectator @s', 'tellraw @s ' + _text('旁观模式：可穿过实体和方块飞行；点按钮请先切回创造。', 'gray')])
    fn('util/creative', ['gamemode creative @s'])
    fn('util/observe_on', ['yukkuri observe on'])
    fn('util/observe_off', ['yukkuri observe off'])
    # Every two seconds, everything standing in a field's box gets that field's mark, so its clear can find it after it has left.
    marks = []
    for f in FIELDS:
        bx1, by1, bz1, bx2, by2, bz2 = f.clear_box()
        marks.append(f'tag @e[type=!minecraft:player,tag=!{field_mark(f)},x={bx1},y={by1},z={bz1},dx={bx2 - bx1},dy={by2 - by1},dz={bz2 - bz1}] add {field_mark(f)}')
    fn('util/tag_fields', marks + [f'schedule function {NS}:util/tag_fields 40t replace'])
    fn('util/lead', ['execute unless entity @e[tag=scn_lead,distance=..250] run return run tellraw @s ' + _text('这个场景没有主角（先生成一个场景）。', 'yellow'),
                     'tp @s @e[tag=scn_lead,distance=..250,limit=1,sort=nearest]'])
    fn('util/clear_all_do', [f'function {NS}:field/{f.id}/clear' for f in FIELDS] +
       [f'forceload remove {x} {z}' for f in FIELDS for x, z in sorted(f.chunks())] +
       ['tellraw @a ' + _text('全部清场完成。', 'green')])
    loads = [f'forceload add {x} {z}' for f in FIELDS for x, z in sorted(f.chunks())]
    fn('util/clear_all', ['tellraw @a ' + _text('全部清场：正在加载各场地（约 3 秒）……', 'yellow')] + loads + [f'schedule function {NS}:util/clear_all_do 3s replace'])
    fn('home', [f'function {NS}:go/lobby'])
    fn('guide', [book_command(shown)])

    # ---------------------------------------------------------------- teleports
    fn('go/lobby', [f'tp @s {SPAWN[0] + .5} {SPAWN[1]} {SPAWN[2] + .5} {SPAWN[3]} 0',
                    'tellraw @s ' + _text('大厅：每个按钮传送到一个区的观察台，细节控制都在观察台上。', 'aqua')])
    for f in FIELDS:
        x, y, z, yaw = deck_info[f.id][1]
        fn(f'go/{f.id}', [f'tp @s {x} {y} {z} {yaw} 15',
                          'tellraw @s ' + js([{'text': f'{f.title}', 'color': 'gold', 'bold': True},
                                              {'text': f'：{f.blurb}。面前起依次是常用操作、倍速推进、时间天气、场景；红色清场在第一排最右侧。', 'color': 'aqua'}])])

    # ---------------------------------------------------------------- welcome / waiting / alerts
    fn('waiting', ['tag @s add museum_waiting', 'tellraw @s ' + _text('正在布置试玩场（约 1 分钟），请稍候……完成后自动传送到大厅。', 'yellow')])
    fn('welcome', ['tag @s add museum_visitor', 'gamemode creative @s', 'yukkuri observe on', f'tp @s {SPAWN[0] + .5} {SPAWN[1]} {SPAWN[2] + .5} {SPAWN[3]} 0', f'function {NS}:guide',
                   'tellraw @s ' + _text('欢迎来到试玩场！大厅按主题列出各区，每个按钮传送到一个区的观察台；在台上生成场景、清场、调时间天气、倍速和推进。', 'aqua')])
    shapes, blocks, items, entities = probe_lines([line for lines in texts.values() for line in lines])
    probes = {'commands': [PROBE_COMMANDS[s] for s in sorted(shapes) if s in PROBE_COMMANDS],
              'blocks': [f'setblock 0 -64 0 {b}' for b in blocks], 'items': [f'give @s {i}' for i in items],
              'entities': [f'summon {e} 0 -64 0' for e in entities]}
    missing = shapes - set(PROBE_COMMANDS)
    assert not missing, missing
    labels = {'commands': '命令（scenario、clear、observe、settlement 子命令）', 'blocks': '方块', 'items': '物品', 'entities': '实体'}
    for kind, lines in probes.items():
        # never executed: the condition cannot hold. The point is that the function exists only if every line parses.
        fn(f'probe/{kind}', [f'execute if score #never {OBJ} matches 1 run {line}' for line in lines] + [f'scoreboard players set #probe_{kind} {OBJ} 1'])
    fn('probe', [f'scoreboard players set #probe_{k} {OBJ} 0' for k in probes] + [f'function {NS}:probe/{k}' for k in probes] +
       [f'scoreboard players set #probe {OBJ} 1'] + [f'execute unless score #probe_{k} {OBJ} matches 1 run scoreboard players set #probe {OBJ} 0' for k in probes])
    fn('outdated', ['tag @s add museum_alert', 'title @s times 10 400 30', 'title @s subtitle ' + _text('请更新运行产物（含最新 CI 构建的模组）后重新启动动作博物馆.cmd', 'yellow'),
                    'title @s title ' + _text('运行模组过旧', 'red'),
                    'tellraw @s ' + _text('试玩场没有布置：运行用的模组版本比这个数据包旧，缺少下面列出的命令／方块／物品／实体之一。', 'red')] +
       [f'execute unless score #probe_{k} {OBJ} matches 1 run tellraw @s ' + _text(f'缺失：{labels[k]}。', 'red') for k in probes] +
       ['tellraw @s ' + _text('更新 .local/experimental-map/museum-runtime.json 指向的运行产物（用含 command/Scenario*.java 的 CI 构建）。', 'yellow')])

    # ---------------------------------------------------------------- load / tick / prepare / build
    fn('load', [f'scoreboard objectives add {OBJ} dummy', f'scoreboard players set #probe {OBJ} 0', f'function {NS}:probe',
                f'execute unless score #probe {OBJ} matches 1 run scoreboard players set #ready {OBJ} 0',
                f'execute if score #probe {OBJ} matches 1 unless score #built {OBJ} matches 1 run function {NS}:prepare',
                f'execute if score #probe {OBJ} matches 1 if score #built {OBJ} matches 1 run scoreboard players set #ready {OBJ} 1',
                f'execute if score #probe {OBJ} matches 1 if score #built {OBJ} matches 1 run function {NS}:util/tag_fields'])
    fn('tick', [f'execute unless score #probe {OBJ} matches 1 as @a[tag=!museum_alert] run function {NS}:outdated',
                f'execute if score #probe {OBJ} matches 1 if score #ready {OBJ} matches 1 as @a[tag=!museum_visitor] run function {NS}:welcome',
                f'execute if score #probe {OBJ} matches 1 if score #ready {OBJ} matches 0 as @a[tag=!museum_waiting] run function {NS}:waiting'])
    initial = rect_chunks((LOBBY[0] - 2, LOBBY[1] - 2, LOBBY[2] + 2, LOBBY[3] + 2))
    prepare = [f'scoreboard players set #ready {OBJ} 0'] + [f'forceload add {x} {z}' for x, z in sorted(initial)] + [
        'gamerule doMobSpawning false', 'gamerule doDaylightCycle false', 'gamerule doWeatherCycle false', 'gamerule keepInventory true',
        'gamerule commandBlockOutput false', 'gamerule sendCommandFeedback false', 'gamerule doFireTick false', 'gamerule spawnRadius 0',
        'gamerule maxCommandChainLength 200000', 'gamerule mobGriefing true', 'difficulty peaceful', 'time set noon', 'weather clear',
        f'setworldspawn {SPAWN[0]} {SPAWN[1]} {SPAWN[2]} {SPAWN[3]}',
        f'fill {LOBBY[0]} {GROUND} 20 {LOBBY[2]} {GROUND} {LOBBY[3]} minecraft:smooth_quartz',   # the floor around the spawn first, so nobody falls
        f'schedule function {NS}:build/lobby 3s replace']
    fn('prepare', prepare)

    lobby = build_lobby()
    fn('build/lobby', guarded('build/lobby', initial, lobby + [f'schedule function {NS}:build/field_{FIELDS[0].id} 1s replace']))
    for i, f in enumerate(FIELDS):
        chunks = sorted(f.chunks())
        following = f'build/field_{FIELDS[i + 1].id}' if i + 1 < len(FIELDS) else 'complete'
        fn(f'build/field_{f.id}', [f'forceload add {x} {z}' for x, z in chunks] + [f'schedule function {NS}:build/field_{f.id}_do 3s replace'])
        lines = [f'function {NS}:field/{f.id}/terrain'] + f.biome_commands()
        deck_lines, land = deck_info[f.id]
        x1, z1, x2, z2 = f.deck
        lines += [f'fill {x1} {GROUND} {z1} {x2} {DECK_TOP - 4} {z2} minecraft:oak_planks'] + deck_lines
        lines += field_deck_controls(f, land)
        lines += field_gate(f)
        if f.id == 'clips':
            lines.append(f'function {NS}:clip/cast')
        lines += [f'forceload remove {x} {z}' for x, z in chunks]
        fn(f'build/field_{f.id}_do', guarded(f'build/field_{f.id}_do', set(chunks), lines + [f'schedule function {NS}:{following} 1s replace']))
    fn('complete', [f'scoreboard players set #built {OBJ} 1', f'scoreboard players set #ready {OBJ} 1', f'function {NS}:util/tag_fields', 'say MCYMUSEUM_BUILD_COMPLETE',
                    f'schedule function {NS}:release_load 60s replace'])
    fn('release_load', [f'forceload remove {x} {z}' for x, z in sorted(initial)])

    n_controls = len(CONTROLS)
    check = [f'scoreboard players set #controls {OBJ} 0', f'scoreboard players set #seen {OBJ} 0']
    for x, y, z in CONTROLS:
        check += [f'execute if loaded {x} {y} {z} run scoreboard players add #seen {OBJ} 1',
                  f'execute if loaded {x} {y} {z} if block {x} {y} {z} minecraft:command_block run scoreboard players add #controls {OBJ} 1']
    check += ['tellraw @a ' + js([{'text': '试玩场自检：已加载的按钮 ', 'color': 'aqua'}, {'score': {'name': '#controls', 'objective': OBJ}}, {'text': ' / '},
                                 {'score': {'name': '#seen', 'objective': OBJ}}, {'text': f'（全图共 {n_controls} 个，远处没加载的不计）'}]),
              f'execute unless score #controls {OBJ} = #seen {OBJ} run tellraw @a ' + _text('有按钮缺失：建场尚未完成，或被后来的方块覆盖。', 'red'),
              f'execute if score #controls {OBJ} = #seen {OBJ} run tellraw @a ' + _text('已加载的按钮齐全。', 'green')]
    fn('selfcheck', check)
    return dict(controls=n_controls, fields=len(FIELDS), scenarios=len(museum_scenarios.S), functions=len(names), texts=len(museum_sites.TEXTS),
                shown=sorted(shown)), shown, texts


def field_deck_controls(f, land):
    """Fixed common rows nearest the south landing, then scenarios; nine columns spaced three blocks apart.

    Readout and self-check functions remain callable manually, with no diagnostic buttons.
    """
    x1, z1, x2, z2 = f.deck
    lines = []
    lines.append(display((x1 + x2) / 2 + .5, DECK_TOP + 3.2, z1 + 1.5, [(f'{f.title}\n', 'gold', True)] + [('\n'.join(wrap(f.blurb, 36)), 'white')], 1.0, 'banner', f'{f.id} banner', billboard='vertical'))
    ax = x1 + 2
    rows = [[(f'function {NS}:go/lobby', '回大厅', '', True),
                 (f'function {NS}:util/lead', '到主角身边', '', True),
                 (f'function {NS}:util/speed_1x', '恢复正常速度', '1倍速', False),
                 (f'function {NS}:util/observe_on', '观察标签开', '头顶显状态', False),
                 (f'function {NS}:util/observe_off', '观察标签关', '', False),
                 (f'function {NS}:util/spectator', '旁观模式', '', True),
                 (f'function {NS}:util/creative', '创造模式', '', True),
                 None,  # A full empty column separates clearing from routine controls.
                 (f'function {NS}:field/{f.id}/clear', '清空本场地', '移除并复原', False)]]
    rows.append([(f'function {NS}:util/speed_{label}', f'倍速 {label}', '', False) for label, _ in SPEEDS if label != '1x']
                + [(f'function {NS}:util/sprint_{ticks_}', f'推进 {label}', '快进后恢复', False) for label, ticks_ in SPRINTS])
    rows.append([(f'function {NS}:util/time_{t}', c, '', False) for t, c in (('dawn', '清晨'), ('noon', '正午'), ('dusk', '黄昏'), ('night', '夜晚'))]
                + [(f'function {NS}:util/weather_clear', '放晴', '', False), (f'function {NS}:util/weather_rain', '下雨', '', False),
                   (f'function {NS}:util/weather_thunder', '雷暴', '', False)])
    start = [(f'function {NS}:start/{s.id}', s.hint, s.tag, True) for s in f.scenarios]
    start += [(f'function {NS}:{name}', caption, hint, True) for name, caption, hint in f.extras]
    for i in range(0, len(start), 9):
        rows.append(start[i:i + 9])
    assert 2 + 3 * (len(rows) - 1) < z2 - z1, (f.id, len(rows), f.deck)
    for r, row in enumerate(rows):
        z = z2 - 2 - 3 * r
        assert len(row) <= 9, (f.id, z, len(row))
        color, block = (('aqua', 'cyan'), ('green', 'green'), ('yellow', 'yellow'), ('gold', 'orange'))[min(r, 3)]
        lines.append(f'fill {ax} {DECK_TOP - 1} {z} {x2 - 2} {DECK_TOP - 1} {z} minecraft:{block}_concrete')
        for i, entry in enumerate(row):
            if entry is None:
                continue
            command, caption, hint, nav = entry
            clearing = command == f'function {NS}:field/{f.id}/clear'
            lines += button(ax + 3 * i, DECK_TOP, z, command, caption, hint, nav=nav, context=f'{f.id} {caption}',
                            color='red' if clearing else color)
    for px in (x1 + 1, x2 - 1):
        lines.append(f'setblock {px} {DECK_TOP - 1} {z1 + 4} minecraft:sea_lantern')
    return lines


def field_gate(f):
    """Two return buttons just outside the field's south edge (outside the box the terrain rebuild touches)."""
    gx, gz = f.gate
    lines = [f'fill {gx - 3} {GROUND} {gz - 1} {gx + 3} {GROUND} {gz + 1} minecraft:smooth_stone',
             f'fill {gx - 3} {FEET} {gz - 1} {gx + 3} {FEET + 4} {gz + 1} minecraft:air']
    lines += button(gx - 1, FEET, gz, f'function {NS}:go/{f.id}', '回观察台', '', nav=True, context=f'{f.id} gate deck', color='aqua')
    lines += button(gx + 1, FEET, gz, f'function {NS}:go/lobby', '回大厅', '', nav=True, context=f'{f.id} gate lobby', color='gold')
    return lines


def build_lobby():
    """The lobby holds teleports only: one button per field, grouped by theme, with a board of what the field offers. No time, weather,
    spawn or clearing control lives here; those are on each field's deck."""
    x1, z1, x2, z2 = LOBBY
    s = big_fill(x1, GROUND + 1, z1, x2, GROUND + 8, z2, 'minecraft:air')
    s += big_fill(x1, GROUND, z1, x2, GROUND, z2, 'minecraft:smooth_quartz')
    for x in range(x1 + 4, x2, 8):
        for z in range(z1 + 4, z2, 8):
            s.append(f'setblock {x} {GROUND} {z} minecraft:sea_lantern')
    s.append(display(0.5, FEET + 7, z1 + 3, [('油库里试玩场\n', 'gold', True), ('Yukkuri Playground', 'aqua')], 3.0, 'title', 'lobby title', billboard='vertical'))
    howto = [('怎么玩\n', 'gold', True), ('大厅只管去哪儿：每个按钮传送\n', 'white'), ('到一个区的观察台。\n', 'white'),
             ('生成场景、清场、时间、天气、\n', 'gray'), ('倍速与推进，都在\n', 'gray'), ('各区的观察台上。\n', 'gray'),
             ('每个观察台和区的南边都有\n', 'gray'), ('“回大厅”。', 'gray')]
    s.append(display(0.5, FEET + 4.0, z1 + 3, howto, .9, 'board', 'lobby how-to', billboard='vertical'))
    columns = [[g for g in GROUPS[:4]], [g for g in GROUPS[4:]]]
    for ci, groups in enumerate(columns):
        bx = -38 if ci == 0 else 4
        z = -26
        for gid, gtitle in groups:
            s.append(display(bx + 5.5, FEET + 2.4, z + .5, [(f'{gtitle}', 'gold', True)], 1.5, 'title', f'lobby group {gid}', billboard='vertical'))
            z += 3
            for f in [f for f in FIELDS if f.group == gid]:
                s += button(bx, FEET, z, f'function {NS}:go/{f.id}', f.title, '', nav=True, context=f'lobby {f.id}', color='gold')
                names = '、'.join(sc.title for sc in f.scenarios) or f.blurb
                body = [(f'{f.title}\n', 'gold', True), ('\n'.join(wrap(f.blurb, 44)) + '\n', 'white'), ('\n'.join(wrap(names, 44)[:6]), 'gray')]
                s.append(display(bx + 6.5, FEET + 1.9, z + .5, body, .85, 'board', f'lobby board {f.id}', billboard='vertical'))
                z += 6
            z += 1
    s += button(-8, FEET, 38, f'function {NS}:guide', '领取导览书', '', nav=True, context='lobby guide')
    return s


# ============================================================================== catalogue
def write_catalogue(path, shown):
    clips, internal = load_clips()
    lines = ['# 试玩场动作清单', '',
             '本页由 `scripts/build_museum_full.py` 的 `write_catalogue` 生成，不手工维护；修改动作注册、分类或适配资源后再重建。操作命令见文末。', '',
             f'当前 `YukkuriAction` 注册 {len(ACTION_META)} 个动作。此表从注册表、19 套基础模型和动作修订联合生成；“资源适配”指确实存在动画片段，不等于每个年龄、亚种都适配。'
             '水生魔理沙与普通魔理沙共用模型，场景按生态身份分开。', '',
             f'试玩场的“动作片段”场地用 9 位固定演员播放 {len(shown)} 个纯动作片段（含待机），分五类：身体与移动、日常生活、交流与情绪、宠物店与表情、运输与篮子，另有全体合唱和全体体操。'
             '家庭、照护、帽子与水边、落水与救援类动作由真实行为触发，在对应场景里自然出现，不放假按钮。', '',
             '缩壳命令只接受有壳且不在水中、不着火的个体；自然受惊时也可触发。动作片段场地没有缩壳按钮，适配资源仍列在下表。'
             '身体结构四段、失去幼体后的悲伤（`bereaved`）和家油内部片段不放按钮，但仍在下表列全。', '']
    for group, entries in CATEGORIES:
        lines += ['## ' + group, '', '| 动作 | ID | 资源适配 | 试玩位置 |', '| --- | --- | --- | --- |']
        for action, name in entries:
            supported = [MODELS[m] for m in MODELS if action in clips[m]]
            scope = '全部 19 套模型' if len(supported) == 19 else '、'.join(supported)
            if action.startswith('boat_') or action.endswith('_aq'):
                scope += '；水生情景'
            if action == 'hat_cradle':
                scope += '；只用于帽托幼体'
            if action == 'first_wake':
                scope += '；只用于初生幼体'
            place = '动作片段场地' if action in shown else BEHAVIOUR_PLACES.get(group, '仅目录')
            lines += [f'| {name} | `{action}` | {scope} | {place} |']
        lines.append('')
    lines += ['## 注册表之外的内部片段', '', '这些名称不属于 `/yukkuri action` 的可选动作，不放置假按钮。', '', '| 模型 | 内部片段 |', '| --- | --- |']
    for model, extra in internal.items():
        if extra:
            lines += [f'| {MODELS[model]} | ' + '、'.join('`' + x + '`' for x in sorted(extra)) + ' |']
    lines += ['', '家油夹取、搬运、放下、蠕动由相应行为状态调用；试玩场的家油场景让一对家油真实拾絮、筑巢、产卵，不播放这些内部片段。', '',
              '重建清单：`python scripts/build_museum_full.py --catalog docs/MOTION_MUSEUM_ACTIONS.md --pack-only --output .local/experimental-map/<新目录>`。该命令同时生成临时数据包，输出目录必须不存在；核对后删除本次临时目录。无需为更新清单使用会替换试玩世界的 `--install`。', '']
    path.write_text('\n'.join(lines), encoding='utf-8')


# ============================================================================== world
def inputs_sha256():
    """Hash of everything that decides what the generated world looks like."""
    h = hashlib.sha256()
    assets = ROOT / 'src/main/resources/assets/mcyukkuri'
    files = (sorted(HERE.glob('museum_*.py')) + [Path(__file__).resolve(), ROOT / 'src/main/java/io/github/longshou/mcyukkuri/entity/YukkuriAction.java',
                                                  ROOT / 'src/main/resources/assets/mcyukkuri/dialogue/catalog.json']
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
    """level.dat metadata of the playground world, derived from any 1.21.1 level.dat (no chunks, entities or players)."""
    import nbtlib as n
    metadata = n.load(seed)
    d = metadata['Data']
    if 'fml' in metadata:  # drop dev-only mods that an older seed world may still list
        metadata['fml']['LoadingModList'] = n.List[n.Compound]([m for m in metadata['fml']['LoadingModList'] if str(m['ModId']) != 'mcyukkuri_p0'])
    for key in ('Player', 'CustomBossEvents'):
        d.pop(key, None)
    d['ScheduledEvents'] = n.List[n.Compound]([])
    d['DragonFight'] = n.Compound({'NeedsStateScanning': n.Byte(1), 'DragonKilled': n.Byte(0), 'PreviouslyKilled': n.Byte(0)})
    for key, value in (('SpawnX', SPAWN[0]), ('SpawnY', SPAWN[1]), ('SpawnZ', SPAWN[2]), ('GameType', 1)):
        d[key] = n.Int(value)
    d['SpawnAngle'] = n.Float(SPAWN[3])
    d['Difficulty'] = n.Byte(0)
    d['allowCommands'] = n.Byte(1)
    d['LevelName'] = n.String('油库里试玩场')
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
    # Everything that can fail is done before the old world is touched: validation and the level metadata.
    CONTROLS.clear()
    museum_sites.TEXTS.clear()
    validate()
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
        # SERVER config belongs to the newly generated world; pack-only and Resume do not write it.
        serverconfig = world / 'serverconfig'
        serverconfig.mkdir(parents=True, exist_ok=True)
        (serverconfig / 'mcyukkuri-server.toml').write_text('[debug]\nentitySaveViews = true\n', encoding='utf-8')
    checks, shown, _ = build_pack(world)
    if museum_sites.VIOLATIONS:
        for violation in museum_sites.VIOLATIONS:
            print('TEXT DOES NOT FIT', violation, file=sys.stderr)
        raise SystemExit(f'{len(museum_sites.VIOLATIONS)} text displays do not fit their board or label')
    manifest = {'world': str(final / 'saves' / WORLD), 'minecraft': '1.21.1', 'inputs_sha256': inputs_sha256(),
                'fields': [{'id': f.id, 'title': f.title, 'centre': [f.cx, f.cz], 'half': [f.hx, f.hz], 'biome': f.biome, 'anchor': list(f.anchor),
                            'scenarios': [s.id for s in f.scenarios]} for f in FIELDS],
                'registered_actions': len(ACTION_META), 'clip_actions': sorted(shown), 'checks': {k: v for k, v in checks.items() if k != 'shown'}}
    (out / 'map-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    if not args.pack_only:
        (out / 'options.txt').write_text('lang:zh_cn\nguiScale:0\nrenderDistance:10\nsimulationDistance:8\nmaxFps:120\nonboardAccessibility:false\nautoJump:false\n'
                                         'pauseOnLostFocus:false\ntutorialStep:none\nnarrator:0\nsoundCategory_music:0.0\n', encoding='utf-8')
    if args.catalog:
        write_catalogue(args.catalog, shown)
    if args.install:
        if final.exists():
            shutil.rmtree(final)
        out.rename(final)
        out = final
    print(json.dumps({'output': str(out), 'fields': len(FIELDS), 'scenarios': len(museum_scenarios.S), 'registered_actions': len(ACTION_META),
                      'clip_actions': len(shown), **{k: v for k, v in checks.items() if k != 'shown'}}, ensure_ascii=False))


if __name__ == '__main__':
    main()
