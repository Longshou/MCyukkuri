"""Generate a NEW disposable 1.21.1 living showcase. No Java, Gradle, game or UI is run.

Only level metadata is read from a supplied template, never chunks/entities/SavedData.
All generated material remains under .local/living-showcase/. Requires nbtlib.
"""
from pathlib import Path
import argparse
import json
import re
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / '.local/living-showcase'
sys.path.insert(0, str(ROOT / '.local/setup/python'))
import nbtlib as n

WORLD = 'MCyukkuri-Living-Showcase'
NS = 'mcyliving'
ZONES = [
    ('land', '01 山根家庭 · 从出生到另筑新居', 0, 48, 'minecraft:plains'),
    ('heights', '02 台地家庭 · 高差与亲子追赶', 96, 48, 'minecraft:plains'),
    ('water', '03 河湾家庭 · 帽中出生到初航独立', 192, 48, 'minecraft:plains'),
    ('autumn', '04 秋林聚落 · 采集与有限储粮', 0, 144, 'minecraft:taiga'),
    ('winter', '05 雪林聚落 · 本窝取食与闭巢', 96, 144, 'minecraft:snowy_taiga'),
    ('spring', '06 春林聚落 · 职位、共享与迁徙', 192, 144, 'minecraft:plains'),
    ('summer', '07 夏林聚落 · 高温与午间歇息', 288, 144, 'minecraft:jungle'),
]

def js(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':'))

def quoted(value):
    return "'" + value.replace('\\', '\\\\').replace("'", "\\'") + "'"

def component(value, color='white'):
    return js({'text': value, 'color': color})

def tell(value):
    return 'tellraw @a ' + component(value, 'gold')

def pos(x, y, z):
    value = ((x & 0x3ffffff) << 38) | ((z & 0x3ffffff) << 12) | (y & 0xfff)
    return n.Long(value if value < (1 << 63) else value - (1 << 64))

class Pack:
    def __init__(self, world):
        self.root = world / ('datapacks/' + NS)
        self.functions = self.root / ('data/' + NS + '/function')
        self.functions.mkdir(parents=True)
        (self.root / 'pack.mcmeta').write_text(js({'pack': {'pack_format': 48, 'description': '生活展示 · 连续家庭与四季聚落'}}), encoding='utf-8')
        for kind in ('load', 'tick'):
            path = self.root / f'data/minecraft/tags/function/{kind}.json'
            path.parent.mkdir(parents=True, exist_ok=True)
            path.write_text(js({'values': [NS + ':' + kind]}), encoding='utf-8')

    def fn(self, name, lines):
        path = self.functions / (name + '.mcfunction')
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text('\n'.join(lines) + '\n', encoding='utf-8')

def label(x, y, z, title, scale=0.8):
    return (f'summon minecraft:text_display {x} {y} {z} {{Tags:["living_label"],text:{quoted(component(title))},'
            f'billboard:"center",alignment:"center",line_width:330,background:1600000000,'
            f'transformation:{{scale:[{scale}f,{scale}f,{scale}f]}}}}')

def control(x, z, command, title, sub='右键按钮'):
    messages = ','.join(quoted(component(s, 'black')) for s in (title, sub, '', ''))
    return [f'setblock {x} 64 {z} minecraft:command_block{{Command:{quoted(command)},TrackOutput:0b}}',
            f'setblock {x} 65 {z} minecraft:stone_button[face=floor]',
            f'setblock {x} 65 {z+1} minecraft:birch_sign[rotation=0]{{front_text:{{messages:[{messages}]}},is_waxed:1b}}']

def cave(x, z, initialized=False):
    """Pre-existing, level-mouth cavity; no creature is credited with building its earth walls."""
    lines = [f'fill {x-2} 65 {z-3} {x+2} 67 {z-3} minecraft:dirt',
             f'fill {x-2} 65 {z-2} {x-2} 67 {z} minecraft:dirt',
             f'fill {x+2} 65 {z-2} {x+2} 67 {z} minecraft:dirt',
             f'fill {x-2} 68 {z-3} {x+2} 68 {z} minecraft:moss_block',
             f'fill {x-1} 65 {z-2} {x+1} 67 {z} minecraft:air']
    if initialized:
        beds = [(xx, 65, zz) for zz in (z, z-1, z-2) for xx in (x, x+1, x-1)]
        data = n.Compound({'Version': n.Int(2), 'NaturalBeds': n.LongArray([pos(*p) for p in beds]),
                           'NaturalEntrance': pos(x, 65, z+1), 'Branches': n.Int(2), 'Condition': n.Int(100),
                           'Moisture': n.Int(0), 'Lining': n.Int(4), 'Dirt': n.Int(0),
                           'Items': n.List[n.Compound]([n.Compound({'Slot': n.Byte(0), 'id': n.String('minecraft:short_grass'), 'count': n.Int(24)})])})
        lines += [f'setblock {x} 65 {z} mcyukkuri:forest_burrow[facing=south,part=12]{data.snbt()}']
    return lines

def parent(zone, role, species, x, z, maturity, aquatic=False):
    data = n.Compound({'Tags': n.List[n.String](['living_showcase', 'living_'+zone, f'living_{zone}_{role}']),
                       'PersistenceRequired': n.Byte(1), 'CustomName': n.String(component('母油' if role=='mother' else '父油')),
                       'YukkuriLife': n.Compound({'Nutrition': n.Float(95), 'Energy': n.Float(95), 'Hygiene': n.Float(100), 'Accessory': n.Byte(1)}),
                       'YukkuriFamily': n.Compound({'Version': n.Int(2), 'Initialized': n.Byte(1), 'Stage': n.String('ADULT'),
                           'Age': n.Long(maturity), 'Nourishment': n.Long(maturity), 'Maturity': n.Long(maturity),
                           'Lifespan': n.Long(maturity+2160000), 'AdultScale': n.Float(1), 'Scale': n.Float(1)}),
                       'YukkuriSubspecies': n.Compound({'Initialized': n.Byte(1), 'Kind': n.String('AQUATIC_MARISA' if aquatic else 'NORMAL'), 'Congenital': n.String('NORMAL')})})
    return f'summon mcyukkuri:{species} {x+.5} 65 {z+.5} {data.snbt()}'

def terrain(zone, x, z, biome):
    lines = [f'fill {x+1} 65 {z+9} {x+62} 71 {z+54} minecraft:air',
             f'fill {x+1} 72 {z+9} {x+62} 78 {z+54} minecraft:air',
             f'fill {x} 63 {z+8} {x+63} 64 {z+55} minecraft:grass_block',
             f'fillbiome {x} 60 {z} {x+63} 67 {z+63} {biome}',
             f'fill {x} 65 {z+8} {x+63} 67 {z+8} minecraft:glass',
             f'fill {x} 65 {z+55} {x+63} 67 {z+55} minecraft:glass',
             f'fill {x} 65 {z+9} {x} 67 {z+54} minecraft:glass',
             f'fill {x+63} 65 {z+9} {x+63} 67 {z+54} minecraft:glass']
    for dz in range(13, 52, 3):
        for dx in range(4, 61, 3):
            lines.append(f'setblock {x+dx} 65 {z+dz} minecraft:short_grass')
    # Main home and several unoccupied natural cavities permit actual independence and migration.
    sites = ([(16, 25), (40, 25), (16, 46), (40, 46)] if zone in ('land', 'heights', 'water') else
             [(14, 24), (28, 24), (42, 24), (14, 38), (28, 38), (42, 38), (14, 50), (42, 50), (56, 49)])
    for dx, dz in sites:
        lines += cave(x+dx, z+dz, zone in ('land', 'heights', 'water') and (dx, dz)==(16, 25))
        for px, pz in [(dx-1, dz+1), (dx+1, dz+1), (dx-2, dz+2), (dx+2, dz+2)]:
            lines.append(f'setblock {x+px} 65 {z+pz} minecraft:short_grass')
        if zone != 'water':
            lines += [f'setblock {x+dx+4} 64 {z+dz+1} minecraft:water',
                      f'setblock {x+dx+4} 65 {z+dz+1} minecraft:air']
    if zone == 'heights':
        lines += [f'fill {x+25} 65 {z+29} {x+43} 65 {z+39} minecraft:grass_block',
                  f'fill {x+25} 65 {z+27} {x+43} 65 {z+28} minecraft:oak_stairs[facing=south]',
                  f'fill {x+25} 65 {z+40} {x+43} 65 {z+41} minecraft:oak_stairs[facing=north]',
                  f'fill {x+31} 66 {z+33} {x+36} 66 {z+34} minecraft:short_grass']
    if zone == 'water':
        lines += [f'fill {x+22} 63 {z+15} {x+56} 63 {z+48} minecraft:clay',
                  f'fill {x+22} 64 {z+15} {x+56} 64 {z+48} minecraft:water',
                  f'fill {x+22} 65 {z+15} {x+56} 73 {z+48} minecraft:air',
                  f'fill {x+18} 64 {z+16} {x+21} 64 {z+45} minecraft:sand',
                  f'fill {x+22} 64 {z+11} {x+56} 64 {z+13} minecraft:oak_planks',
                  f'fill {x+22} 65 {z+13} {x+56} 65 {z+13} minecraft:glass_pane']
        for dz in (23, 28):
            lines += [f'setblock {x+20} 65 {z+dz} minecraft:dead_bush',
                      f'setblock {x+19} 65 {z+dz+1} minecraft:sweet_berry_bush[age=3]']
        for dz in (18, 22, 30, 34, 38, 42, 46):
            lines += [f'setblock {x+20} 65 {z+dz} minecraft:oak_leaves[persistent=true]',
                      f'setblock {x+21} 65 {z+dz} minecraft:sweet_berry_bush[age=3]']
        for dz in (17, 21, 25, 29, 33, 37, 41, 45):
            lines += [f'setblock {x+57} 65 {z+dz} minecraft:sweet_berry_bush[age=3]',
                      f'setblock {x+58} 65 {z+dz} minecraft:oak_leaves[persistent=true]']
    for dx, dz in [(6, 13), (58, 13), (6, 44)]:
        lines += [f'fill {x+dx} 65 {z+dz} {x+dx} 68 {z+dz} minecraft:oak_log',
                  f'fill {x+dx-2} 69 {z+dz-2} {x+dx+2} 70 {z+dz+2} minecraft:oak_leaves[persistent=true]']
    return lines

def build(world, maturity):
    p = Pack(world)
    p.fn('load', ['scoreboard objectives add living dummy',
                  'execute unless score #built living matches 1 run function mcyliving:prepare'])
    p.fn('prepare', ['scoreboard players set #built living 1', 'scoreboard players set #ready living 0',
                     'gamerule doMobSpawning false', 'gamerule mobGriefing true', 'gamerule doDaylightCycle true',
                     'gamerule doWeatherCycle false', 'gamerule doFireTick false', 'gamerule commandBlockOutput false',
                     'gamerule sendCommandFeedback true', 'gamerule keepInventory true', 'gamerule spawnRadius 0',
                     'difficulty peaceful', 'time set 2000', 'weather clear', 'setworldspawn 32 65 12 0',
                     'forceload add 0 0 63 31', 'schedule function mcyliving:build/hub 2s replace'])
    hub = ['fill 0 63 0 79 64 31 minecraft:smooth_sandstone', 'fill 2 64 3 76 64 28 minecraft:oak_planks',
           label(37, 70, 3, '从出生，到自己的生活\n连续家庭  /  自然取材  /  四季聚落', 1.25),
           label(37, 68, 15, '先到场，再按“开始”\n每区可重置重播；没有自动跑完的样本\n观察笔记右键个体、住所或帽船', .75)]
    for i, (zone, title, x, z, biome) in enumerate(ZONES):
        hx = 7+i*10
        hub += control(hx, 23, 'function mcyliving:visit/'+zone, title[3:11], '前往本区')
        p.fn('visit/'+zone, [f'forceload add {x} {z} {x+63} {z+63}', f'tp @p {x+31.5} 65 {z+4.5} 0 4', tell(title+'：在此按开始；观测通道在玻璃围栏外。')])
        permanent = [f'fill {x-3} 63 {z} {x+66} 64 {z+7} minecraft:smooth_sandstone',
                     f'fill {x+2} 64 {z+1} {x+61} 64 {z+6} minecraft:oak_planks',
                     f'fill {x-3} 64 {z+8} {x-1} 64 {z+58} minecraft:oak_planks',
                     f'fill {x+64} 64 {z+8} {x+66} 64 {z+58} minecraft:oak_planks',
                     f'fill {x-3} 64 {z+56} {x+66} 64 {z+58} minecraft:oak_planks',
                     label(x+31, 70, z+8, title, .9)]
        for bx, fn, caption in [(7, 'start', '开始 / 继续'), (17, 'reset', '本区重置'), (27, 'inspect', '真实状态说明')]:
            permanent += control(x+bx, z+3, f'function mcyliving:{zone}/{fn}', caption)
        permanent += control(x+39, z+3, f'function mcyliving:{zone}/observe', '近距离旁观')
        permanent += control(x+49, z+3, 'execute as @p run function mcyliving:home', '返回总入口')
        p.fn(zone+'/terrain', terrain(zone, x, z, biome))
        p.fn('build/'+zone, [f'forceload add {x} {z} {x+63} {z+63}'] + permanent + [f'function mcyliving:{zone}/terrain',
            f'scoreboard players set #{zone} living 0', f'forceload remove {x} {z} {x+63} {z+63}',
            ('schedule function mcyliving:build/'+ZONES[i+1][0]+' 2s replace') if i+1<len(ZONES) else 'function mcyliving:ready'])
        p.fn(zone+'/start', [f'execute unless score #{zone} living matches 1 unless entity @e[tag=living_{zone}] run function mcyliving:{zone}/begin',
                            f'execute if score #{zone} living matches 1 run '+tell('本区正在连续生活。重置才重新出生；加速改变全世界tick速度。'),
                            f'execute unless score #{zone} living matches 1 if entity @e[tag=living_{zone}] run '+tell('上次初始化未完成，请按本区重置后再开始；不会重复生成父母。')])
        p.fn(zone+'/reset', [f'forceload add {x} {z} {x+63} {z+63}',
                            f'execute store success score #reset living run yukkuri showcase reset {zone}',
                            f'execute if score #reset living matches 1 run scoreboard players set #{zone} living 0',
                            f'execute if score #reset living matches 1 run function mcyliving:{zone}/terrain',
                            'execute if score #reset living matches 1 run '+tell('仅本区展示家庭及其真实后代已重置。按开始重新观看。'),
                            'execute unless score #reset living matches 1 run '+tell('重置未执行：请先带回并放出已卸载/收容的展示成员。地形与其他家庭没有清理。')])
        p.fn(zone+'/observe', ['gamemode spectator @p', f'tp @p {x+25.5} 68 {z+29.5} 90 30', tell('旁观模式可贴近嘴部与帽沿；返回入口按钮需先用 /function mcyliving:home 恢复冒险模式。')])
        if zone in ('land', 'heights', 'water'):
            water = zone == 'water'
            mother = f'@e[tag=living_{zone}_mother,limit=1]'; father = f'@e[tag=living_{zone}_father,limit=1]'
            begin = [f'forceload add {x} {z} {x+63} {z+63}',
                     parent(zone, 'mother', 'marisa' if water else 'reimu', x+16, z+25, maturity, water),
                     parent(zone, 'father', 'marisa', x+17, z+25, maturity, water),
                     f'execute store success score #{zone} living run yukkuri showcase pair {mother} {father} {x+16} 65 {z+25} live',
                     f'yukkuri showcase track {zone}',
                     f'execute if score #{zone} living matches 1 run '+tell('初始化：一对成油、现存家庭叶巢、24份有限旧粮、真实植物。新生子代、护理、学习、铺床与独立全部由玩法产生。'),
                     f'execute unless score #{zone} living matches 1 run '+tell('受孕初始化失败；没有生成孩子。请检查住所与可达性并重置本区。')]
            if water:
                # A real embark uses the owner's existing hat and the actual adjacent water; no boat/passenger NBT shortcut.
                begin += [f'execute if score #{zone} living matches 1 run tp {mother} {x+21.5} 65 {z+24.5}',
                          f'execute if score #{zone} living matches 1 run tp {father} {x+21.5} 65 {z+27.5}',
                          f'execute if score #{zone} living matches 1 run yukkuri showcase launch {mother} {x+22} 64 {z+24}',
                          f'execute if score #{zone} living matches 1 run yukkuri showcase launch {father} {x+22} 64 {z+27}',
                          tell('水上初始化把已受孕亲油放在岸边，从原巢24份粮中各转8份到帽内，消费各一株实际枯枝作桨，再正常脱帽下水。此后靠近真实岸莓持续采食；树叶供子代取桨。')]
            p.fn(zone+'/begin', begin)
            p.fn(zone+'/inspect', [tell('连续顺序：胎生 → 喂糜/首便 → 实际爬行与起落 → 自主取食 → 两份植物铺床 → 告别与真实新居；水生为实际初航往返和驶离。'),
                                  'tellraw @p '+js({'text':'亲油真实家庭记录：', 'color':'aqua', 'extra':[{'nbt':'YukkuriFamily', 'entity':mother}]}),
                                  'tellraw @p '+js({'text':'原巢真实库存：', 'color':'green', 'extra':[{'nbt':'Items', 'block':f'{x+16} 65 {z+25}'}]}),
                                  'tellraw @p '+js({'text':'子代护理与运动记录：', 'color':'yellow', 'extra':[{'nbt':'YukkuriChildhood', 'entity':f'@e[tag=living_{zone},tag=!living_{zone}_mother,tag=!living_{zone}_father,type=!mcyukkuri:hat_boat]'}]}),
                                  tell('观察笔记能查看每只子代和住所；这里没有把计数器当成通过结论。状态命令没有对象时会直接报告。')])
        else:
            begin = [f'forceload add {x} {z} {x+63} {z+63}',
                     f'execute store success score #{zone} living run yukkuri settlement spawn forest_led {x+28} 65 {z+32}',
                     f'execute as @e[x={x},y=60,z={z},dx=63,dy=25,dz=63,tag=!living_showcase] if data entity @s YukkuriFamily run tag @s add living_{zone}',
                     f'tag @e[tag=living_{zone}] add living_showcase',
                     tell('由现有聚落生成器初始化真实族长、家庭、职位、住所与有限存粮。观察采集、搬运、入库和分粮；不会凭空填满库存。')]
            p.fn(zone+'/begin', begin)
            p.fn(zone+'/inspect', [f'execute positioned {x+28} 65 {z+32} run yukkuri settlement info',
                                  f'yukkuri season {x+28} 65 {z+32}', tell('四季由群系决定，不随日期轮转。秋储、冬闭巢/本窝吃粮、春共享和夏午休按真实条件发生；不是按按钮立即授予结果。')])
    hub += control(7, 8, 'function mcyliving:speed/normal', '正常20TPS')
    hub += control(17, 8, 'function mcyliving:speed/fast', '快进80TPS', '动画也会加速')
    hub += control(27, 8, 'function mcyliving:pause', '暂停世界', '聊天点击继续')
    hub += control(47, 8, 'tick unfreeze', '继续世界')
    hub += control(57, 8, 'time set 13000', '夜晚章节', '只改变日夜')
    hub += control(67, 8, 'time set 6000', '白天章节')
    hub += ['schedule function mcyliving:build/land 2s replace']
    p.fn('build/hub', hub)
    p.fn('speed/normal', ['tick rate 20', tell('恢复20TPS。'+('演示成熟基准10分钟、妊娠1分钟；' if maturity==12000 else '使用默认成长时钟；')+'年龄、营养和实际行动分别判断。')])
    p.fn('speed/fast', ['tick rate 80', tell('全世界4倍tick加速：需求、成长、寻路和动作一起变快；不会写入护理/运动/巢/初航结果。细看请恢复20TPS。')])
    p.fn('pause', ['tellraw @a '+js({'text':'世界暂停后按钮也暂停，请点击聊天： ', 'color':'yellow', 'extra':[
        {'text':'[继续世界]', 'color':'green', 'bold':True, 'clickEvent':{'action':'run_command','value':'/tick unfreeze'}},
        {'text':'   [单步20 tick]', 'color':'aqua', 'clickEvent':{'action':'run_command','value':'/tick step 20t'}}]}), 'tick freeze'])
    p.fn('home', ['gamemode adventure @s', 'tp @s 32 65 12 0 0'])
    p.fn('ready', ['scoreboard players set #ready living 1', tell('生活展示建造完成。先到场再开始；所有区初始没有活体，支持本区重播。')])
    tick = ['execute if score #ready living matches 1 as @a[tag=!living_visitor] run function mcyliving:welcome',
            'scoreboard players add #clock living 1',
            'execute if score #clock living matches 20.. run scoreboard players set #clock living 0']
    for zone, *_ in ZONES:
        tick.append(f'execute if score #clock living matches 0 if score #{zone} living matches 1 run yukkuri showcase track {zone}')
    p.fn('tick', tick)
    pages = [
        '生活展示\n\n先到场，再开始。每区开始前不生成活体，所以不会在你到场前完成首便。按本区重置可重复观看；清理按展示身份和真实血缘追踪。',
        '连续家庭\n\n只初始化成油、现存巢、24份有限旧粮及自然地形。真实受孕后由玩法完成出生、亲代嚼糜递喂、首便、活动学习、成长、取材铺床和独立。没有预置不同年龄的孩子。',
        ('时间\n\n本档采用默认成熟48000 tick（40分钟）、妊娠6000 tick（5分钟）。' if maturity==48000 else
         '时间\n\n本档成熟基准12000 tick（10分钟），妊娠1200 tick（1分钟）；是明确的演示参数，普通世界默认48000/6000。')+
         '80TPS还会把真实等待压缩至约四分之一；20TPS细看照料。',
        '陆上与台地\n\n真实短草和低矮植物提供口粮和铺材；不会召唤树叶掉落物来冒充采集。远处空洞穴用于真实另筑新居。台地有完整一格高差、宽阶梯和绕行路线。',
        '河湾\n\n亲油使用自己的帽子正常下水。初始化从原巢有限旧粮各转8份到帽内，消费真实枯枝作桨；之后划向岸边实际成熟莓丛，近身采果入船。子代从岸边真实树叶取桨。不会补发初航成功。',
        '四季聚落\n\n季节来自群系：针叶林秋、雪林冬、平原春、丛林夏。按钮查看真实季节及聚落。全世界日夜和雨雪联动；切换需留意其他已开始的区。',
        '旁观与交互\n\n观察笔记右键个体、巢和帽船。近距离旁观切为旁观模式；/function mcyliving:home 返回冒险。暂停世界后请点聊天[继续世界]，冻结期间按钮也停。\n\n/function mcyliving:land/reset 等可重置本区。',
    ]
    content = n.Compound({'title': n.String('生活展示导览'), 'author': n.String('MCyukkuri'), 'pages': n.List[n.String]([component(s) for s in pages])})
    p.fn('welcome', ['tag @s add living_visitor', 'function mcyliving:home', 'give @s mcyukkuri:field_notebook',
                     'give @s minecraft:written_book[minecraft:written_book_content='+content.snbt()+']',
                     tell('从出生，到自己的生活。入口导览书说明时间加速和初始化边界；选择一区，到场后按开始。')])
    # Optional environmental chapters affect conditions, never stored biological milestones.
    for name, commands in {'rain': ['weather rain'], 'clear': ['weather clear'], 'morning': ['time set 1000'],
                           'migration': ['execute positioned 220 65 176 run yukkuri settlement migrate'],
                           'threat': ['execute positioned 220 65 176 run yukkuri settlement threat 6']}.items():
        p.fn('chapter/'+name, commands + [tell('章节只改变外界条件或调用现有决策入口；观察实际行动与失败原因。')])
    validate(p)
    return p

def validate(pack):
    files = list(pack.functions.rglob('*.mcfunction'))
    for file in files:
        text = file.read_text(encoding='utf-8')
        for function in re.findall(r'(?:function|schedule function) mcyliving:([a-z0-9_/]+)', text):
            if not (pack.functions/(function+'.mcfunction')).is_file():
                raise ValueError(f'{file}: missing function {function}')
        for line in text.splitlines():
            if line.startswith('fill '):
                tokens = line.split(); a = list(map(int, tokens[1:4])); b = list(map(int, tokens[4:7]))
                if (abs(a[0]-b[0])+1)*(abs(a[1]-b[1])+1)*(abs(a[2]-b[2])+1)>32768:
                    raise ValueError(f'Fill exceeds vanilla limit: {line}')
        if 'FirstToiletDone:1' in text or 'PrivateNests' in text or 'summon minecraft:item' in text:
            raise ValueError(f'{file}: fabricated milestone/material')
    return len(files)

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--template-level', type=Path, required=True)
    parser.add_argument('--output', type=Path, required=True)
    parser.add_argument('--normal-timing', action='store_true')
    args = parser.parse_args(); output = args.output.resolve()
    if not output.is_relative_to(WORK.resolve()):
        raise ValueError('Output must be within .local/living-showcase')
    if not args.template_level.is_file():
        raise FileNotFoundError(args.template_level)
    output.mkdir(parents=True, exist_ok=False)
    world = output/('saves/'+WORLD); world.mkdir(parents=True)
    metadata = n.load(args.template_level); data = metadata['Data']
    for key in ('Player', 'CustomBossEvents'):
        data.pop(key, None)
    data['ScheduledEvents'] = n.List[n.Compound]([])
    data['LevelName'] = n.String('从出生，到自己的生活 · 连续展示')
    data['Time'] = n.Long(0); data['DayTime'] = n.Long(2000)
    data['LastPlayed'] = n.Long(int(time.time()*1000))
    for key, value in {'SpawnX':32, 'SpawnY':65, 'SpawnZ':12, 'GameType':2}.items():
        data[key] = n.Int(value)
    data['SpawnAngle'] = n.Float(0); data['Difficulty'] = n.Byte(0); data['allowCommands'] = n.Byte(1)
    data['DataPacks'] = n.Compound({'Enabled': n.List[n.String](['vanilla', 'mod_data', 'file/'+NS]), 'Disabled': n.List[n.String]([])})
    data['enabled_features'] = n.List[n.String](['minecraft:vanilla'])
    settings = data['WorldGenSettings']['dimensions']['minecraft:overworld']['generator']['settings']
    settings['layers'] = n.List[n.Compound]([n.Compound({'block': n.String(block), 'height': n.Int(height)})
        for block, height in [('minecraft:bedrock',1), ('minecraft:stone',124), ('minecraft:dirt',3), ('minecraft:grass_block',1)]])
    settings['biome'] = n.String('minecraft:plains'); settings['structure_overrides'] = n.List[n.String]([])
    for key, value in {'doMobSpawning':'false', 'mobGriefing':'true', 'doDaylightCycle':'true', 'doWeatherCycle':'false', 'keepInventory':'true',
                       'spawnRadius':'0', 'commandBlockOutput':'false', 'sendCommandFeedback':'true'}.items():
        data['GameRules'][key] = n.String(value)
    if 'fml' in metadata:
        metadata['fml'].pop('LoadingModList', None)
    metadata.save(world/'level.dat')
    maturity = 48000 if args.normal_timing else 12000
    # SERVER configs belong in the save's serverconfig, not a client-only config directory.
    config = world/'serverconfig'; config.mkdir()
    (config/'mcyukkuri-family.toml').write_text(
        '[family]\nnaturalPairing = false\naging = true\nhouseArrivals = false\n'
        f'maturityTicks = {maturity}\ngestationTicks = {6000 if args.normal_timing else 1200}\n', encoding='utf-8')
    (config/'mcyukkuri-server.toml').write_text(
        '[discovery]\nnaturalSpawns = false\nforestMinHouseholds = 3\nforestMaxHouseholds = 3\nhouseholdMaxChildren = 2\n', encoding='utf-8')
    pack = build(world, maturity)
    (output/'options.txt').write_text('lang:zh_cn\nrenderDistance:10\nsimulationDistance:8\nmaxFps:90\nonboardAccessibility:false\nautoJump:false\npauseOnLostFocus:false\n', encoding='utf-8')
    revision = subprocess.check_output(['git','rev-parse','HEAD'], cwd=ROOT, text=True).strip()
    manifest = {'world': WORLD, 'sourceRevision': revision, 'generatedAt': int(time.time()),
                'maturityTicks': maturity, 'gestationTicks':6000 if args.normal_timing else 1200,
                'functions': validate(pack), 'zones': [z[0] for z in ZONES], 'startsAutomatically':False,
                'validation':'Generated data and function references only; no game or compilation was run.'}
    (output/'map-manifest.json').write_text(json.dumps(manifest, ensure_ascii=False, indent=2), encoding='utf-8')
    print(js({'game':str(output), 'world':WORLD, 'functions':manifest['functions']}))

if __name__ == '__main__':
    main()
