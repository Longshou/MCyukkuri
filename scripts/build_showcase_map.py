"""Build a fresh ecology museum (Python 3.11+, nbtlib, Minecraft 1.21.1).

All interactions use normal server AI and persisted relationships. No animation
commands, invented newborns, Java compilation, or UI automation. Output must be
a new directory below .local/experimental-map. --verify starts real scenarios
automatically and records completion from native counters for CLI inspection.
"""
from pathlib import Path
import argparse
import hashlib
import json
import sys
import time
import uuid

ROOT=Path(__file__).resolve().parents[1]
WORK=ROOT/'.local/experimental-map'
sys.path.insert(0,str(ROOT/'.local/setup/python'))
import nbtlib as n
from build_experimental_map import Pack,js,quoted,text_component,label
from museum_actions import CATEGORIES,NAMES,MODELS,ACTION_META,load_clips

NAMESPACE=uuid.UUID('df344405-7cab-44bc-8d73-b224ca48f851')
def uid(name):
    value=uuid.uuid5(NAMESPACE,name).int
    return n.IntArray([((value>>shift)&0xffffffff)-(1<<32) if ((value>>shift)&0x80000000) else ((value>>shift)&0xffffffff) for shift in (96,64,32,0)])

ACTORS={
    'eater':('reimu',17,17,'食事灵梦'),
    'sleeper':('patchouli',25,12,'休息帕秋莉'),
    'friend':('chen',49,17,'橙'),
    'companion':('alice',52,17,'爱丽丝'),
    'mother':('reimu',49,40,'亲本灵梦'),
    'father':('marisa',51,40,'接生魔理沙'),
    'sailor':('marisa',17,44,'水生魔理沙'),
}
ROOMS=[('daily','01 好好生活','lime',2,2,32,23),
       ('social','02 与同伴相处','pink',39,2,69,23),
       ('family','03 组成一个家','orange',39,34,69,55),
       ('water','04 在水边生活','light_blue',2,34,32,55)]

def packed_pos(x,y,z):return n.Long((x&0x3ffffff)<<38|(z&0x3ffffff)<<12|(y&0xfff))

def actor_data(name,active=False):
    species,x,z,title=ACTORS[name];fertile=name in ('mother','father')
    home=(49,40) if name=='mother' else (53,40) if name=='father' else (x,z)
    life=n.Compound({'Nutrition':n.Float(95),'Energy':n.Float(95),'Hygiene':n.Float(100),
        'Bladder':n.Float(0),'Dryness':n.Float(0),'Accessory':n.Byte(1),'RestWait':n.Int(1200),
        'SocialWait':n.Int(2400),'HomeDimension':n.String('minecraft:overworld'),
        'HomePos':packed_pos(home[0],65,home[1]),'NaturalHome':n.Byte(1)})
    family=n.Compound({'Version':n.Int(2),'Initialized':n.Byte(1),'Stage':n.String('ADULT'),
        'Age':n.Long(48000),'Nourishment':n.Long(48000),'Maturity':n.Long(48000),'Lifespan':n.Long(2208000),
        'AdultScale':n.Float(1),'Scale':n.Float(1),'Sterile':n.Byte(not fertile),'Recovery':n.Int(0),'SexDrive':n.Int(0)})
    if fertile:family['Partner']=uid('father' if name=='mother' else 'mother')
    return n.Compound({'UUID':uid(name),'Tags':n.List[n.String](['eco_actor','eco_'+name]),
        'PersistenceRequired':n.Byte(1),'NoAI':n.Byte(not active),'Invulnerable':n.Byte(1),
        'Rotation':n.List[n.Float]([180 if z>30 else 0,0]),'CustomName':n.String(text_component(title)),
        'CustomNameVisible':n.Byte(0),'YukkuriLife':life,'YukkuriFamily':family,
        'YukkuriDailyLife':n.Compound({'Affection':n.Int(0),'Boredom':n.Int(0),'Cooldown':n.Int(2400)}),
        'YukkuriGroupInteraction':n.Compound({'Cooldown':n.Int(2400)}),
        'YukkuriSubspecies':n.Compound({'Initialized':n.Byte(1),'Kind':n.String('AQUATIC_MARISA' if name=='sailor' else 'NORMAL'),
            'Congenital':n.String('NORMAL'),'Deibu':n.Byte(0)})})

def spawn(name):
    kind,x,z,_=ACTORS[name]
    return f'summon mcyukkuri:{kind} {x} 65 {z} {actor_data(name).snbt()}'

def merge(name,nbt):return f'data merge entity @e[tag=eco_{name},limit=1] {nbt}'
def say(message):return 'tellraw @a '+text_component(message,'gold')

def controls(x,z,fn,title,south=False):
    messages=','.join(quoted(text_component(t,'black')) for t in [title,'右键开始','',''])
    content='{messages:['+messages+']}'
    return [f'setblock {x} 64 {z} minecraft:command_block{{Command:"function mcylab:{fn}",TrackOutput:0b}}',
        f'setblock {x} 65 {z} minecraft:polished_andesite',
        f'setblock {x} 66 {z} minecraft:stone_button[face=floor]',
        f'setblock {x} 66 {z+(1 if south else -1)} minecraft:birch_sign[rotation={8 if south else 0}]{{front_text:{content},back_text:{content},is_waxed:1b}}']

def guide():
    pages=[
        '油库里生活馆\n\n01 生活 → 02 同伴\n04 水边 ← 03 家庭\n\n按钮准备场景条件，油库里会自己寻路、接触、进食和生育。\n\n中央走廊与侧面观察窗都能观看。',
        '01 好好生活\n\n开饭：饥饿灵梦自己走向食盆，食物真的被取走、咀嚼和吞下。\n\n休息：疲劳帕秋莉实际恢复体力；等体力恢复后醒来。',
        '02 与同伴相处\n\n蹭蹭：两位相熟同伴从分开的位置靠近、转向并接触，再开始蹭蹭。\n\n自由活动：根据日常 AI 唱歌或体操。\n\n可重复准备；不会把两只站着同步播动画。',
        '03 组成一个家\n\n开始一胎：两只同伴求偶、妊娠，灵梦生下真实幼体。魔理沙走近协助，按实际条件使用帽托。\n\n本馆孕期缩至约 20 秒；寻路和接生会增加时间。幼体保留亲缘并继续生活。',
        '家庭观察\n\n请先看完当前一胎，再开始下一胎。已经出生的幼体会留下来；不是反复生成的演示道具。\n\n“查看状态”会报告受孕次数和当前幼体数量。场地拥挤时保留家庭，不继续增加。',
        '04 在水边生活\n\n池中是实际水和帽船，水生魔理沙是船的真实乘员。\n\n水流按钮改变真实水流，让帽船受物理作用漂移。静水按钮恢复平静水面。\n\n这里只展示已经登船的生活，不冒充完整登船过程。',
    ]
    for group,items in CATEGORIES:
        for i in range(0,len(items),6):pages.append(group+' · 动作目录\n\n'+'\n'.join(name for _,name in items[i:i+6]))
    pages.append('完整清单含 56 个动作。\n\n这里按真实情境选展，不要求每个动作都有按钮。家油、不足形态和捕食种的生育限制保留。\n\n完整 ID、资源适配和内部片段见仓库文档。')
    book=n.Compound({'title':n.String('油库里生活馆导览'),'author':n.String('MCyukkuri'),
        'pages':n.List[n.String]([text_component(page) for page in pages])})
    return 'give @s minecraft:written_book[minecraft:written_book_content='+book.snbt()+']'

def build(world,verify=False):
    p=Pack(world)
    (p.root/'pack.mcmeta').write_text(js({'pack':{'pack_format':48,'description':'油库里生活馆 · 真实互动场景'}}),encoding='utf-8')
    p.fn('load',['scoreboard objectives add eco dummy',
        'execute unless score #built eco matches 1 run function mcylab:prepare'])
    p.fn('prepare',['forceload add 0 0 71 57','gamerule doMobSpawning false','gamerule doDaylightCycle false',
        'gamerule doWeatherCycle false','gamerule doFireTick false','gamerule commandBlockOutput false',
        'gamerule sendCommandFeedback false','gamerule keepInventory true','gamerule spawnRadius 0','gamerule mobGriefing true',
        'time set noon','weather clear','difficulty peaceful','setworldspawn 35 66 29 180',
        'schedule function mcylab:build 2s replace'])
    p.fn('tick',['execute if score #built eco matches 1 as @a[tag=!eco_visitor] run function mcylab:welcome'])
    p.fn('welcome',['tag @s add eco_visitor','gamemode adventure @s','tp @s 35 66 29 180 0','function mcylab:guide',
        say('生活馆：按场景准备条件，观看真实互动。先生活，再同伴、家庭和水边；按钮旁有观察提示。')])
    p.fn('guide',[guide()]);p.fn('home',['tp @s 35 66 29 180 0'])
    lines=[]
    for a,b in ((0,28),(29,57)):
        lines += [f'fill 0 65 {a} 71 75 {b} minecraft:air',f'fill 0 63 {a} 71 64 {b} minecraft:smooth_quartz']
    lines += ['fill 0 65 0 71 73 0 minecraft:calcite','fill 0 65 57 71 73 57 minecraft:calcite',
        'fill 0 65 0 0 73 57 minecraft:calcite','fill 71 65 0 71 73 57 minecraft:calcite',
        'fill 0 74 0 71 74 57 minecraft:glass','fill 1 65 24 70 65 33 minecraft:polished_diorite',
        'fill 33 65 1 38 65 56 minecraft:polished_diorite','fill 1 65 28 70 65 29 minecraft:yellow_terracotta',
        'fill 34 65 1 35 65 56 minecraft:yellow_terracotta',
        label(35,71,29,'油库里 · 生活馆\n生活 → 同伴 → 家庭 → 水边',.8)]
    for ident,title,color,x1,z1,x2,z2 in ROOMS:
        front=z1 if z1>30 else z2
        lines += [f'fill {x1} 64 {z1} {x2} 64 {z2} minecraft:grass_block',
            f'fill {x1} 65 {z1} {x2} 67 {z1} minecraft:glass',
            f'fill {x1} 65 {z2} {x2} 67 {z2} minecraft:glass',
            f'fill {x1} 65 {z1} {x1} 67 {z2} minecraft:glass',
            f'fill {x2} 65 {z1} {x2} 67 {z2} minecraft:glass',
            f'fill {x1} 74 {z1} {x2} 74 {z2} minecraft:birch_planks',
            f'fill {x1+2} 64 {front} {x2-2} 64 {front} minecraft:{color}_concrete',
            label((x1+x2)/2,70,front,title,.9)]
        for x in range(x1+3,x2,6):lines += [f'setblock {x} 74 {z1+4} minecraft:sea_lantern',f'setblock {x} 74 {z2-4} minecraft:sea_lantern']
    lines += [
        'setblock 17 65 13 mcyukkuri:food_bowl{Items:[{Slot:0b,id:"mcyukkuri:oil_grain_premium",count:64}]}',
        'fill 23 64 10 27 64 14 minecraft:moss_block','setblock 27 65 10 mcyukkuri:yukkuri_nest',
        'setblock 9 65 10 mcyukkuri:yukkuri_toilet','setblock 30 65 4 minecraft:potted_azalea_bush',
        'setblock 43 65 4 minecraft:potted_azalea_bush','setblock 66 65 4 minecraft:potted_azalea_bush',
        'fill 45 64 37 57 64 46 minecraft:moss_block','setblock 59 65 45 mcyukkuri:yukkuri_nest',
        'setblock 57 65 41 mcyukkuri:food_bowl{Items:[{Slot:0b,id:"mcyukkuri:oil_grain_premium",count:64}]}',
        'setblock 62 65 49 mcyukkuri:yukkuri_toilet',
        'fill 8 63 38 27 63 52 minecraft:clay','fill 8 64 38 27 64 52 minecraft:water',
        'fill 9 64 38 26 64 38 minecraft:stone_bricks',
        label(17,68,22,'自己走到食盆 → 咀嚼吞下\n体力不足 → 休息恢复',.5),
        label(54,68,22,'相熟同伴：先靠近，再面对面接触\n看它们真正蹭到一起',.5),
        label(54,68,35,'真实求偶、妊娠、接生与幼体\n幼体会留下，成为这个家庭的一员',.5),
        label(17,68,35,'真实水面 · 真实帽船与乘员\n水流会推动船体',.5),
    ]
    lines += [spawn(a) for a in ACTORS if a!='sailor']
    sailor=actor_data('sailor',True);sailor['id']=n.String('mcyukkuri:marisa')
    sailor['YukkuriLife']['Accessory']=n.Byte(0);sailor['YukkuriLife']['AccessoryToken']=uid('hat-token')
    sailor['YukkuriSubspecies']['Boat']=uid('boat')
    boat=n.Compound({'UUID':uid('boat'),'Tags':n.List[n.String](['eco_boat']),'OwnerYukkuri':uid('sailor'),
        'AccessoryToken':uid('hat-token'),'State':n.String('AFLOAT'),'HullScale':n.Float(1.6),
        'Invulnerable':n.Byte(1),'Passengers':n.List[n.Compound]([sailor])})
    lines += [f'summon mcyukkuri:hat_boat 17 65.025 44 {boat.snbt()}']
    buttons=[(11,26,'meal','准备开饭',False),(23,26,'rest','准备休息',False),
        (46,26,'nuzzle','同伴蹭蹭',False),(58,26,'leisure','自由活动',False),
        (46,31,'family_start','开始一胎',True),(58,31,'status','查看状态',True),
        (11,31,'water_flow','打开水流',True),(23,31,'water_still','恢复静水',True),
        (36,26,'guide_player','补领导览',False)]
    for x,z,fn,title,south in buttons:lines += controls(x,z,fn,title,south)
    lines += ['scoreboard players set #built eco 1','schedule function mcylab:monitor 1s replace','say MCYECO_BUILD_COMPLETE']
    if verify:lines += ['schedule function mcylab:verification_start 3s replace']
    p.fn('build',lines)
    p.fn('guide_player',['execute as @p run function mcylab:guide'])
    p.fn('meal',['tp @e[tag=eco_eater] 17 65 18 180 0',
        'data merge block 17 65 13 {Items:[{Slot:0b,id:"mcyukkuri:oil_grain_premium",count:64}]}',
        merge('eater','{NoAI:0b,YukkuriLife:{Nutrition:25f,Energy:95f,Hygiene:100f,Bladder:0f,Dryness:0f,RestWait:1200}}'),
        say('开饭了：观察灵梦自行走向食盆，取食、咀嚼并吞下。')])
    p.fn('rest',[merge('sleeper','{NoAI:0b,YukkuriLife:{Nutrition:95f,Energy:8f,Hygiene:100f,Bladder:0f,Dryness:0f,RestWait:0}}'),
        say('休息时间：帕秋莉会在住处睡下，体力恢复后自己醒来。')])
    for name,affection,boredom,title in [('nuzzle',100,0,'它们会靠近、面对面接触，再蹭蹭。'),('leisure',0,100,'同伴自由活动：日常 AI 会选择唱歌或体操。')]:
        actions=[]
        for a,x in [('friend',49),('companion',52)]:
            actions += [merge(a,'{NoAI:1b}'),f'tp @e[tag=eco_{a}] {x} 65 17 0 0',
                merge(a,'{NoAI:0b,YukkuriLife:{Nutrition:95f,Energy:95f,Hygiene:100f,Bladder:0f,Dryness:0f,RestWait:1200,SocialWait:2400},'
                    f'YukkuriDailyLife:{{Affection:{affection},Boredom:{boredom},Cooldown:0}},YukkuriGroupInteraction:{{Cooldown:2400}}}}')]
        p.fn(name,actions+[say(title)])
    mother=str(uid('mother').snbt())
    child_selector=f'@e[type=#mcylab:yukkuri,nbt={{YukkuriFamily:{{Mother:{mother}}}}}]'
    types=p.root/'data/mcylab/tags/entity_type/yukkuri.json';types.parent.mkdir(parents=True,exist_ok=True)
    types.write_text(js({'values':['mcyukkuri:reimu','mcyukkuri:marisa']}),encoding='utf-8')
    p.fn('family_start',['execute if data entity @e[tag=eco_mother,limit=1] YukkuriFamily.Gestation run '+say('这一胎还在进行，请继续观察。'),
        'execute unless data entity @e[tag=eco_mother,limit=1] YukkuriFamily.Gestation if score #children eco matches ..9 run function mcylab:family_prepare',
        'execute if score #children eco matches 10.. run '+say('这个家庭已经很热闹了；先观察幼体，不再增加新的一胎。')])
    actions=[]
    for a,x in [('mother',49),('father',51)]:
        actions += [f'tp @e[tag=eco_{a}] {x} 65 40 180 0',merge(a,
            '{NoAI:0b,YukkuriLife:{Nutrition:100f,Energy:100f,Hygiene:100f,Bladder:0f,Dryness:0f,RestWait:1200,SocialWait:2400},'
            'YukkuriDailyLife:{Affection:0,Boredom:0,Cooldown:2400},YukkuriGroupInteraction:{Cooldown:2400},'
            'YukkuriFamily:{Recovery:0,SexDrive:1200}}')]
    p.fn('family_prepare',actions+[say('两只同伴准备组成家庭：先求偶，再妊娠、接生。请留意魔理沙的位置和真正出生的幼体。')])
    p.fn('water_flow',['fill 8 64 39 27 64 52 minecraft:air','fill 8 64 39 27 64 39 minecraft:water',say('水流已打开，帽船会受真实水流推动。')])
    p.fn('water_still',['fill 8 64 39 27 64 52 minecraft:water',say('水面恢复平静。')])
    p.fn('status',['tellraw @p '+js({'text':'已完成蹭蹭：','extra':[{'score':{'name':'#nuzzles','objective':'eco'}},
        {'text':' 次；受孕：'},{'score':{'name':'#conceptions','objective':'eco'}},{'text':' 胎；已出生幼体：'},
        {'score':{'name':'#children','objective':'eco'}},{'text':' 只。'}]})])
    monitor=['scoreboard players set #children eco 0',
        f'execute as {child_selector} run scoreboard players add #children eco 1',
        f'tag {child_selector} add eco_child',
        'execute store result score #nuzzles eco run data get entity @e[tag=eco_friend,limit=1] YukkuriDailyLife.Nuzzles',
        'execute store result score #conceptions eco run data get entity @e[tag=eco_mother,limit=1] YukkuriFamily.Conceptions',
        'execute store result score #nutrition eco run data get entity @e[tag=eco_eater,limit=1] YukkuriLife.Nutrition',
        'execute if score #nuzzles eco matches 1.. unless score #nuzzle_pass eco matches 1 run say MCYECO_REAL_NUZZLE_COMPLETED',
        'execute if score #nuzzles eco matches 1.. run scoreboard players set #nuzzle_pass eco 1',
        'execute if score #children eco matches 1.. unless score #birth_pass eco matches 1 run say MCYECO_REAL_BIRTH_COMPLETED',
        'execute if score #children eco matches 1.. run scoreboard players set #birth_pass eco 1',
        'execute if entity @e[type=mcyukkuri:hat_boat,tag=!eco_boat] unless score #hat_pass eco matches 1 run say MCYECO_REAL_MIDWIFE_HAT',
        'execute if entity @e[type=mcyukkuri:hat_boat,tag=!eco_boat] run scoreboard players set #hat_pass eco 1',
    ]
    for key in ['children','nuzzles','conceptions','nutrition','nuzzle_pass','birth_pass','hat_pass']:
        monitor += [f'execute store result storage mcylab:ecology {key} int 1 run scoreboard players get #{key} eco']
    monitor += ['schedule function mcylab:monitor 1s replace']
    p.fn('monitor',monitor)
    p.fn('verification_start',['function mcylab:nuzzle','function mcylab:meal','function mcylab:family_start'])
    return buttons

def write_catalogue(path):
    clips,internal=load_clips()
    lines=['# 油库里动作完整清单','',
        '共有 56 个注册动作。资源适配由当前19套基础模型和动作修订联合读取；有片段不代表适合所有年龄或情境。水生魔理沙共用普通魔理沙模型，但按生态身份区分使用。','',
        '生活馆按真实情景选展：寻路进食、休息、靠近蹭蹭、自由活动、求偶、妊娠、接生、幼体出生及实际帽船。没有为每段动画单独设置播放按钮。','',
        '家油与不足形态不展示普通安胎或胎生；捕食种不展示胎生分娩；初醒与帽托休息只用于幼体；船上动作只用于实际乘船者。','']
    for group,items in CATEGORIES:
        lines += ['## '+group,'','| 名称 | ID | 有对应片段的模型 |','| --- | --- | --- |']
        for action,title in items:
            names=[MODELS[m] for m in MODELS if action in clips[m]]
            scope='全部19套模型' if len(names)==19 else '、'.join(names)
            if action.startswith('boat_') or action.endswith('_aq'):scope+='；按水生情境使用'
            lines += [f'| {title} | `{action}` | {scope} |']
        lines.append('')
    lines += ['## 内部行为片段','','这些片段不在56个注册动作内，由真实行为调用，不制作假播放按钮。','',
        '| 模型 | 片段 |','| --- | --- |']
    for model,extra in internal.items():
        if extra:lines += [f'| {MODELS[model]} | '+ '、'.join('`'+x+'`' for x in sorted(extra))+' |']
    lines += ['','当前 `shell_retract` 的直接命令会被通用收回判断转成待机；自然受惊缩壳走另一条逻辑。生活馆不设置这个无效命令按钮。','']
    path.write_text('\n'.join(lines),encoding='utf-8')

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--seed-level',type=Path,required=True);parser.add_argument('--output',type=Path,required=True)
    parser.add_argument('--catalog',type=Path);parser.add_argument('--verify',action='store_true')
    a=parser.parse_args();out=a.output.resolve()
    if not out.is_relative_to(WORK.resolve()):raise ValueError('Output must be inside .local/experimental-map')
    out.mkdir(parents=True,exist_ok=False);world=out/'saves/MCyukkuri-Ecology';world.mkdir(parents=True)
    metadata=n.load(a.seed_level);d=metadata['Data']
    if 'fml' in metadata:metadata['fml']['LoadingModList']=n.List[n.Compound]([m for m in metadata['fml']['LoadingModList'] if str(m['ModId'])!='mcyukkuri_p0'])
    for key in ['Player','CustomBossEvents']:d.pop(key,None)
    d['ScheduledEvents']=n.List[n.Compound]([]);d['DragonFight']=n.Compound({'NeedsStateScanning':n.Byte(1)})
    for k,v in [('SpawnX',35),('SpawnY',66),('SpawnZ',29),('GameType',2)]:d[k]=n.Int(v)
    d['SpawnAngle']=n.Float(180);d['Difficulty']=n.Byte(0);d['allowCommands']=n.Byte(1)
    d['LevelName']=n.String('油库里生活馆 · 真实互动');d['Time']=n.Long(0);d['DayTime']=n.Long(6000);d['LastPlayed']=n.Long(int(time.time()*1000))
    d['DataPacks']['Enabled']=n.List[n.String](['vanilla','mod_data','file/mcylab']);d['DataPacks']['Disabled']=n.List[n.String]([])
    d['enabled_features']=n.List[n.String](['minecraft:vanilla'])
    settings=d['WorldGenSettings']['dimensions']['minecraft:overworld']['generator']['settings']
    settings['layers']=n.List[n.Compound]([n.Compound({'block':n.String(b),'height':n.Int(h)}) for b,h in
        [('minecraft:bedrock',1),('minecraft:stone',124),('minecraft:dirt',2),('minecraft:grass_block',1)]])
    settings['structure_overrides']=n.List[n.String]([])
    for k,v in {'doMobSpawning':'false','doDaylightCycle':'false','doWeatherCycle':'false','keepInventory':'true','spawnRadius':'0','commandBlockOutput':'false'}.items():d['GameRules'][k]=n.String(v)
    metadata.save(world/'level.dat')
    relations=[]
    for left,right in [('friend','companion'),('mother','father')]:
        for f,t in [(left,right),(right,left)]:relations.append(n.Compound({'From':uid(f),'To':uid(t),'Affinity':n.Int(60)}))
    (world/'data').mkdir()
    n.File({'DataVersion':n.Int(3955),'data':n.Compound({'Version':n.Int(1),'Relations':n.List[n.Compound](relations)})},gzipped=True).save(world/'data/mcyukkuri_living.dat')
    config=out/'config';config.mkdir()
    (config/'mcyukkuri-family.toml').write_text('[family]\ngestationTicks = 400\nreproductionRecoveryTicks = 2400000\nlocalPopulationCap = 64\nnaturalPairing = false\nordinaryLiveBirthChance = 1.0\naging = false\nhouseArrivals = false\n',encoding='utf-8')
    buttons=build(world,a.verify)
    manifest={'world':str(world),'footprint':[72,58],'actors':list(ACTORS),'buttons':buttons,'verification':a.verify,
        'actions':list(ACTION_META),'generator_sha256':hashlib.sha256(Path(__file__).read_bytes()).hexdigest()}
    (out/'map-manifest.json').write_text(json.dumps(manifest,ensure_ascii=False,indent=2),encoding='utf-8')
    (out/'options.txt').write_text('lang:zh_cn\nrenderDistance:7\nsimulationDistance:6\nmaxFps:90\nonboardAccessibility:false\nautoJump:false\npauseOnLostFocus:false\n',encoding='utf-8')
    if a.catalog:write_catalogue(a.catalog)
    print(json.dumps({'output':str(out),'actors':len(ACTORS),'buttons':len(buttons),'real_ai':True},ensure_ascii=False))

if __name__=='__main__':main()
