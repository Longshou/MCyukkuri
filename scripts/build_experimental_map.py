"""Build a Minecraft 1.21.1 lab datapack and a fresh, isolated seed world.

Reads the current local frozen runtime and its level metadata, never its entities,
chunks or SavedData. Writes only a NEW directory under .local/experimental-map.
Requires nbtlib (the task-local Python library directory is supported). The game
itself builds and saves the arena; this script does not fabricate region files.
"""
from pathlib import Path
import argparse
import json
import sys
import time

ROOT = Path(__file__).resolve().parents[1]
WORK = ROOT / '.local/experimental-map'
sys.path.insert(0, str(WORK / 'python'))
import nbtlib as n

def js(value):
    return json.dumps(value, ensure_ascii=False, separators=(',', ':'))

def quoted(value):
    return "'" + value.replace('\\', '\\\\').replace("'", "\\'") + "'"

def text_component(value, color='white'):
    return js({'text': value, 'color': color})

ZONES = [
    ('gallery_a', '01 普通种与家油', 'cyan', ['reimu_spawn_egg','marisa_spawn_egg','chen_spawn_egg','alice_spawn_egg','patchouli_spawn_egg','house_reimu_spawn_egg','house_marisa_spawn_egg','remilia_spawn_egg','flandre_spawn_egg'], '19种运行形态分两区。此区固定展示；活体实验请到对应区按补样按钮。持观察笔记右键查看。'),
    ('gallery_b', '02 亚种与灵胖', 'purple', ['reimu_spawn_egg','marisa_spawn_egg','marisa_shell','reimu_ribbon','marisa_hat'], '此区为指定形态的展示样本。壳种、多毛、六种不足形态、两种灵胖；DOS尚未实装。'),
    ('care', '03 进食与日常照料', 'lime', ['food_bowl','oil_grain_budget','oil_grain_regular','oil_grain_premium','wash_cloth','yukkuri_toilet','newspaper_mat','soil_mat','covered_toilet','minecraft:apple','minecraft:wheat','minecraft:sugar'], '向食盆放食物或手持食物喂食，观察进食、咀嚼、睡眠、清洁与排泄。观察笔记右键个体看数值。'),
    ('family', '04 家庭与生育', 'pink', ['companion_cord','reproductive_filling','yukkuri_nest','food_bowl','oil_grain_premium','stem_shears','bottle_stem_shears','minecraft:sugar'], '笔记右键巢，再潜行右键个体分配住所；放足食物，用同伴绳配对。观察茎生/胎生和子代，妊娠需真实时间与条件。不足形态不可育，多毛可育。'),
    ('house', '05 家油生活', 'yellow', ['house_reimu_spawn_egg','house_marisa_spawn_egg','house_crevice','food_scrap','wash_cloth','bandage','yukkuri_carrier','minecraft:paper','minecraft:wheat','minecraft:stick'], '投放食屑、纸和材料，观察家油搬运、巢穴维护、巢卵与幼体。家油体型很小，请靠近地面查看。'),
    ('teaching', '06 宠物培育与徽章', 'orange', ['field_notebook','teaching_whistle','badge_handbook',('minecraft:emerald',64),'minecraft:cookie','oil_grain_premium','oil_grain_regular','yarn_ball','yukkuri_carrier'], '照料建立信任后，到油库里商店（13区）的考官处报名简易考试登记为宠物。徽章手册可开课；课后潜行右键打开照料菜单夸奖或责骂。繁殖需饲养员相亲许可。'),
    ('operation', '07 治疗与器具', 'red', ['orange_juice','mold_remover','bandage','herbal_medicine','yogurt_drink','stimulant','sitataran','sleep_spray','spicy_grain','pin_tool','care_mallet','care_awl','heated_awl','restraint_frame','wrap_sling','hanging_hook','hair_clippers','cutting_knife','care_vibrator','sterilizing_shears','iron_enclosure','stem_shears','bottle_stem_shears'], '仅在本区补充样本后操作。空手/器具右键打开照料交互；用笔记前后对照伤口和状态。治疗及投药会消耗对应物品。'),
    ('transport', '08 收纳与冷冻', 'light_blue', ['yukkuri_carrier','cold_storage','field_notebook','orange_juice','minecraft:ice','minecraft:packed_ice'], '运输盒右键收纳，再对地面放出。使用冷冻柜测试存入/取回；比较笔记身份和状态，并退出重进检查持久保存。'),
    ('play', '09 游具与地面', 'magenta', ['yarn_ball','glass_marble','rubber_ball','reimu_doll','marisa_doll','pretty_stone','clear_dice','paper_tunnel','ceramic_tunnel','yukkuri_slide','yukkuri_trampoline','yukkuri_pool','newspaper_mat','soil_mat','sandpaper_mat'], '投放玩具，观察追逐和协作。游具由专用物品放置，箱中可取完整设施；比较软垫、土面与砂纸摩擦。'),
    ('water', '10 帽船与雨水', 'blue', ['marisa_hat','marisa_shell','yukkuri_carrier','orange_juice','wash_cloth','minecraft:water_bucket','minecraft:sponge'], '水池边使用帽子测试帽船投放、乘坐、翻覆和救援。补样含普通魔理沙与壳种。中庭可切换雨天，屋檐可对照避雨。'),
    ('predator', '11 捕食隔离箱', 'black', ['remilia_spawn_egg','flandre_spawn_egg','predator_nest','remilia_bonnet','flandre_bonnet','badge_handbook','reimu_spawn_egg','marisa_spawn_egg'], '玻璃顶封闭区。默认不放捕食者；按补样生成两种捕食者和猎物。可在箱外观察飞行、捕猎与枝巢。'),
    ('ecology', '12 聚落与事件', 'green', ['forest_burrow','cardboard_shelter','wild_latrine','food_bowl','oil_grain_regular','minecraft:stick','minecraft:wheat','minecraft:paper','minecraft:apple'], '按钮只生成一次真实有族长聚落。补材料观察返家、修巢和群体生活；遭遇/情景事件按真实条件触发，不保证立即发生。'),
]
FACTORIES = [('shop','13 油库里商店','yukkuri_shop'),('labor','14 劳力制纸工厂','yukkuri_labor_factory'),('manju','15 馒头工厂','yukkuri_manju_factory'),('bag','16 活体袋工厂','yukkuri_bag_factory')]

class Pack:
    def __init__(self, world):
        self.root = world/'datapacks/mcylab'
        self.functions = self.root/'data/mcylab/function'
        self.functions.mkdir(parents=True)
        (self.root/'pack.mcmeta').write_text(js({'pack':{'pack_format':48,'description':'MCyukkuri 实验地图 · 1.21.1 · 16区'}}), encoding='utf-8')
        for kind in ('load','tick'):
            p=self.root/f'data/minecraft/tags/function/{kind}.json';p.parent.mkdir(parents=True,exist_ok=True)
            p.write_text(js({'values':[f'mcylab:{kind}']}),encoding='utf-8')
    def fn(self, name, lines):
        p=self.functions/(name+'.mcfunction');p.parent.mkdir(parents=True,exist_ok=True)
        p.write_text('\n'.join(lines)+'\n',encoding='utf-8')

def sign(x,y,z,lines):
    messages=','.join(quoted(text_component(t)) for t in (lines+['']*4)[:4])
    return f'setblock {x} {y} {z} minecraft:oak_sign[rotation=0]{{front_text:{{messages:[{messages}],has_glowing_text:1b}},is_waxed:1b}}'

def label(x,y,z,value,scale=1):
    return f'summon minecraft:text_display {x} {y} {z} {{Tags:["mcylab_label"],text:{quoted(text_component(value))},billboard:"center",alignment:"center",line_width:300,background:1600000000,transformation:{{translation:[0f,0f,0f],left_rotation:[0f,0f,0f,1f],right_rotation:[0f,0f,0f,1f],scale:[{scale}f,{scale}f,{scale}f]}}}}'

def button(x,z,command,caption):
    return [f'setblock {x} 65 {z} minecraft:command_block{{Command:{quoted(command)},TrackOutput:0b}}',f'setblock {x} 66 {z} minecraft:stone_button[face=floor]',sign(x,66,z+1,[caption,'右键按钮'])]

def barrel(x,z,items,title):
    stacks=[]
    for slot,item in enumerate(items):
        fixed=None
        if isinstance(item,tuple): item,fixed=item
        ident=item if ':' in item else 'mcyukkuri:'+item
        count=32 if ident.startswith('minecraft:') or any(word in item for word in ['grain','filling','scrap']) else 1
        if fixed: count=fixed
        stacks.append(f'{{Slot:{slot}b,id:"{ident}",count:{count}}}')
    return f'setblock {x} 65 {z} minecraft:barrel[facing=up]{{CustomName:{quoted(text_component(title))},Items:[{",".join(stacks)}]}}'

def specimen(kind,x,z,tag,name=None,shape='NORMAL',ancestry='NORMAL',deibu=False,display=False):
    return f'summon mcyukkuri:{kind} {x} 65 {z} {{Tags:["mcylab_subject","{tag}"],PersistenceRequired:1b,NoAI:{int(display)}b,Invulnerable:{int(display)}b,CustomName:{quoted(text_component(name or "实验样本"))},CustomNameVisible:1b,YukkuriLife:{{Nutrition:80f,Energy:90f,Hygiene:100f,Accessory:1b}},YukkuriFamily:{{Version:2,Initialized:1b,Stage:"ADULT",Age:24000L,Nourishment:24000L,Maturity:24000L,Lifespan:2184000L,AdultScale:1f,Scale:1f}},YukkuriSubspecies:{{Initialized:1b,Kind:"{ancestry}",Congenital:"{shape}",Deibu:{int(deibu)}b}}}}'

def build(world):
    p=Pack(world)
    p.fn('load',['scoreboard objectives add mcylab dummy','execute unless score #built mcylab matches 1 run function mcylab:prepare'])
    p.fn('prepare',['scoreboard players set #built mcylab 1','scoreboard players set #ready mcylab 0','gamerule doMobSpawning false','gamerule doDaylightCycle false','gamerule doWeatherCycle false','gamerule keepInventory true','gamerule commandBlockOutput false','gamerule sendCommandFeedback false','gamerule doFireTick false','gamerule spawnRadius 0','difficulty normal','time set noon','weather clear','setworldspawn 65 65 15 180','forceload add 0 0 191 239','schedule function mcylab:build/hub 4s replace'])
    p.fn('tick',['execute if score #ready mcylab matches 1 as @a[tag=!mcylab_visitor] run function mcylab:welcome'])
    p.fn('welcome',['tag @s add mcylab_visitor','gamemode creative @s','tp @s 65 65 15 180 0','give @s mcyukkuri:field_notebook','give @s mcyukkuri:yukkuri_carrier','give @s mcyukkuri:wash_cloth','give @s mcyukkuri:teaching_whistle','give @s mcyukkuri:badge_handbook','give @s mcyukkuri:oil_grain_premium 64','give @s minecraft:emerald 64','tellraw @s '+text_component('欢迎来到油库里实验场！中庭按钮直达16区；各区木桶备好物资。空手右键个体打开交互，观察笔记查看状态。','aqua')])
    hub=['fill 0 63 0 191 64 31 minecraft:smooth_quartz','fill 1 64 1 190 64 30 minecraft:light_gray_concrete','fill 1 64 13 190 64 17 minecraft:cyan_concrete',label(65,70,5,'油 库 里  ·  实 验 场\nMCyukkuri  /  当前实装  /  16区',1.5)]
    for x in range(4,192,12):
        hub.append(f'setblock {x} 64 15 minecraft:sea_lantern')
    hub+=button(58,20,'time set noon','固定白天')+button(61,20,'time set midnight','切换夜晚')+button(64,20,'weather clear','晴天')+button(67,20,'weather rain','下雨')
    hub+=button(70,20,'gamemode survival @p','生存交互测试')+button(73,20,'gamemode creative @p','返回创造模式')
    hub+=button(76,20,'give @p mcyukkuri:field_notebook','补充观察笔记')
    actions=[('idle','恢复待机'),('waddle','行走'),('stretch_shape','伸缩'),('side_bend','侧摆'),('puff','鼓腮'),('brace','撑起'),('startle','惊吓'),('turn','转身'),('hop','跳跃'),('stretch','伸展'),('exercise_vertical','纵向体操'),('exercise_sides','横向体操'),('press','挤压'),('taiso','广播体操'),('flee','逃跑'),('anatomy_mamu_extend','产道伸出'),('anatomy_mamu_retract','产道收回'),('anatomy_peni_extend','生殖部伸出'),('anatomy_peni_retract','生殖部收回')]
    for j,(action,title) in enumerate(actions):
        hub+=button(7+j*9,26,f'yukkuri action @e[tag=mcylab_subject,nbt={{NoAI:1b}}] {action}',title)
    all_zones=[]
    for i,(ident,title,color,items,help_text) in enumerate(ZONES):
        x=(i%3)*48;z=40+(i//3)*48
        all_zones.append((ident,title,x,z))
        lines=[f'fill {x} 63 {z} {x+43} 64 {z+39} minecraft:quartz_block',f'fill {x+1} 64 {z+1} {x+42} 64 {z+38} minecraft:grass_block',f'fill {x} 65 {z} {x+43} 67 {z} minecraft:{color}_stained_glass',f'fill {x} 65 {z+39} {x+43} 67 {z+39} minecraft:{color}_stained_glass',f'fill {x} 65 {z} {x} 67 {z+39} minecraft:{color}_stained_glass',f'fill {x+43} 65 {z} {x+43} 67 {z+39} minecraft:{color}_stained_glass',f'fill {x+19} 65 {z} {x+23} 66 {z} minecraft:air',f'fill {x+18} 64 {z+1} {x+24} 64 {z+9} minecraft:smooth_quartz',label(x+21,69,z+1,title,1.2)]
        for dx,dz in [(2,2),(41,2),(2,37),(41,37),(21,20)]:
            lines.append(f'setblock {x+dx} 64 {z+dz} minecraft:sea_lantern')
        lines+=button(x+3,z+5,'tp @p 65 65 15 180 0','返回中庭')
        lines+=button(x+7,z+5,f'tellraw @p {text_component(help_text,"yellow")}','本区操作说明')
        lines+=button(x+11,z+5,f'function mcylab:supply/{ident}','补充物资')
        supply=[barrel(x+15,z+5,items,title+'物资')]
        p.fn('supply/'+ident,supply);lines+=supply
        if ident.startswith('gallery'):
            shapes=([('reimu','灵梦',{}),('marisa','魔理沙',{}),('chen','橙',{}),('alice','爱丽丝',{}),('patchouli','帕秋莉',{}),('house_reimu','家油灵梦',{}),('house_marisa','家油魔理沙',{}),('remilia','蕾米莉亚',{}),('flandre','弗兰',{})] if ident=='gallery_a' else [('marisa','壳种魔理沙',{'ancestry':'SHELL_MARISA'}),('reimu','多毛灵梦',{'ancestry':'WASA_REIMU'}),('reimu','灵胖',{'deibu':True}),('reimu','多毛灵胖',{'ancestry':'WASA_REIMU','deibu':True})]+[(k,('灵梦' if k=='reimu' else '魔理沙')+name,{'shape':shape}) for shape,name in [('SPARSE_HAIR','稀毛'),('FACE_ASYMMETRY','歪脸'),('BEAN_BODY','豆油')] for k in ['reimu','marisa']])
            for j,(kind,name,kwargs) in enumerate(shapes):
                sx=x+5+(j%5)*8;sz=z+16+(j//5)*15
                lines+=[f'fill {sx-2} 64 {sz-2} {sx+2} 64 {sz+2} minecraft:white_concrete',specimen(kind,sx,sz,f'lab_{ident}_{j}',name,display=True,**kwargs),sign(sx,65,sz+3,[name,'固定展示样本'])]
                form=f'{ident}_{j}'
                lines+=button(sx,sz+4,f'function mcylab:form/{form}','复制到器具区')
                p.fn('form/'+form,['tp @p 21 65 144 0 0','forceload add 0 136 43 175',f'schedule function mcylab:form_spawn/{form} 1s replace'])
                p.fn('form_spawn/'+form,[f'execute unless entity @e[tag=lab_copy_{form}] run '+specimen(kind,22,158,'lab_copy_'+form,name,**kwargs),'forceload remove 0 136 43 175'])
        else:
            samples=[('reimu',{}),('marisa',{})]
            if ident=='house':samples=[('house_reimu',{}),('house_marisa',{})]
            if ident=='water':samples=[('marisa',{}),('marisa',{'ancestry':'SHELL_MARISA'})]
            if ident=='predator':samples=[('remilia',{}),('flandre',{}),('reimu',{}),('marisa',{})]
            spawn=[]
            for j,(kind,kwargs) in enumerate(samples):
                tag=f'lab_{ident}_{j}'
                spawn.append(f'execute unless entity @e[tag={tag}] run '+specimen(kind,x+17+j*3,z+20,tag,**kwargs))
            if ident=='ecology':
                spawn=[f'execute unless score #settlement mcylab matches 1 store success score #settlement mcylab run yukkuri settlement spawn forest_led {x+23} 65 {z+23}']
            p.fn('sample/'+ident,spawn)
            lines+=button(x+27,z+5,f'function mcylab:sample/{ident}','补充缺失样本' if ident!='ecology' else '生成一次聚落')
            if ident in ['care','family','house','teaching']:lines+=spawn
            if ident in ['care','family','house','teaching','ecology']:
                lines += [f'fill {x+3} 68 {z+26} {x+13} 68 {z+36} minecraft:oak_slab[type=bottom]',f'fill {x+3} 65 {z+36} {x+3} 67 {z+36} minecraft:oak_log',f'fill {x+13} 65 {z+36} {x+13} 67 {z+36} minecraft:oak_log',f'setblock {x+7} 65 {z+30} mcyukkuri:'+('house_crevice' if ident=='house' else 'yukkuri_nest'),f'setblock {x+10} 65 {z+30} mcyukkuri:food_bowl',f'setblock {x+12} 65 {z+33} mcyukkuri:yukkuri_toilet']
            if ident=='teaching':
                lines += [sign(x+9,65,z+34,['宠物培育区','课程:手册->课程','夸奖/责骂:潜行','右键开照料菜单']),sign(x+19,65,z+17,['两只成年样本','未登记,野生状态','到13区简易考试','登记后开课/繁殖'])]
            if ident=='water':
                lines += [f'fill {x+5} 63 {z+13} {x+37} 63 {z+33} minecraft:prismarine',f'fill {x+6} 64 {z+14} {x+36} 64 {z+32} minecraft:water',f'fill {x+18} 64 {z+14} {x+20} 64 {z+22} minecraft:oak_planks']
            if ident=='predator':
                lines += [f'fill {x+8} 65 {z+13} {x+36} 72 {z+35} minecraft:glass hollow',f'fill {x+9} 65 {z+14} {x+35} 65 {z+34} minecraft:air',f'setblock {x+22} 65 {z+30} mcyukkuri:predator_nest']
            if ident=='transport':lines += [f'setblock {x+12} 65 {z+24} mcyukkuri:cold_storage']
            if ident=='play':
                for j,mat in enumerate(['newspaper_mat','soil_mat','sandpaper_mat']):lines+=[f'fill {x+4+j*12} 65 {z+29} {x+10+j*12} 65 {z+33} mcyukkuri:{mat}']
        p.fn('build/'+ident,lines)
    for i,(ident,title,template) in enumerate(FACTORIES):
        x=144;z=40+i*48;all_zones.append((ident,title,x,z))
        lines=[f'fill {x} 63 {z} {x+46} 64 {z+44} minecraft:stone_bricks',f'place template mcyukkuri:{template} {x+2} 65 {z+8}',label(x+23,70,z+2,title,1.2)]
        lines+=button(x+3,z+3,'tp @p 65 65 15 180 0','返回中庭')
        lines+=button(x+7,z+3,f'tellraw @p {text_component("右键现场NPC和机台，使用真实库存与工序。绿宝石用于交易，空白徽章不能授予资格。箱内提供原料和接入设施。","yellow")}','本区操作说明')
        supply=[barrel(x+12,z+3,[('minecraft:emerald',64),'minecraft:paper','minecraft:sugar','minecraft:wheat','oil_grain_premium','yukkuri_carrier','badge_handbook','labor_bench','manju_steamer','live_packing_table','nursery_station','factory_receiving_table','factory_inspection_table','factory_washing_table','factory_observation_station','factory_transfer_tray'],title+'原料')]
        p.fn('supply/'+ident,supply);lines+=supply+button(x+17,z+3,f'function mcylab:supply/{ident}','补充原料')
        if ident=='shop':
            lines.append(sign(x+12,66,z+4,['店主:买卖活体','商品/回收/咨询','考官:考试 饲养员','相亲/补货']))
            lines.append(sign(x+14,66,z+4,['简易考试:找考官','交报名费,带宠物','晋级/判定需考场','徽章手册开课']))
        p.fn('build/'+ident,lines)
    for i,(ident,title,x,z) in enumerate(all_zones):
        hx=7+i*11
        hub+=button(hx,7,f'function mcylab:visit/{ident}',title)
        p.fn('visit/'+ident,[f'tp @p {x+21} 65 {z+8} 0 0',f'tellraw @p {text_component(title+"：说明按钮和物资桶在入口两侧。","aqua")}'])
    hub+=['schedule function mcylab:build/gallery_a 1s replace']
    p.fn('build/hub',hub)
    for i,(ident,*_) in enumerate(all_zones):
        path=p.functions/f'build/{ident}.mcfunction'
        with path.open('a',encoding='utf-8') as f:
            f.write(f'schedule function mcylab:build/{all_zones[i+1][0]} 1s replace\n' if i+1<len(all_zones) else 'schedule function mcylab:complete 2s replace\n')
    p.fn('complete',['scoreboard players set #ready mcylab 1','function mcylab:audit','forceload remove all','tellraw @a '+text_component('实验地图建造完成。16区已开放。','green')])
    # Read-only acceptance counters executed by the real server after construction.
    checks=[]
    for ident,title,x,z in all_zones:
        checks += [f'execute if block {x+3} 65 {z+5 if ident not in [f[0] for f in FACTORIES] else z+3} minecraft:command_block run scoreboard players add #buttons mcylab 1']
    p.fn('audit',['scoreboard players set #buttons mcylab 0']+checks+['execute store result score #subjects mcylab run execute if entity @e[tag=mcylab_subject]','data modify storage mcylab:audit completed set value 1b'])
    p.fn('overview',['tp @a 90 145 265 180 42'])
    return all_zones

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--runtime',type=Path,required=True)
    parser.add_argument('--output',type=Path,required=True)
    args=parser.parse_args();out=args.output.resolve();runtime=args.runtime.resolve()
    if not out.is_relative_to(WORK.resolve()):raise ValueError('Output must be inside .local/experimental-map')
    out.mkdir(parents=True,exist_ok=False)
    world=out/'saves/p0-preview';world.mkdir(parents=True)
    metadata=n.load(runtime/'runs/gametest/world/level.dat');d=metadata['Data']
    if 'fml' in metadata:
        metadata['fml']['LoadingModList']=n.List[n.Compound]([m for m in metadata['fml']['LoadingModList'] if str(m['ModId'])!='mcyukkuri_p0'])
    for key in ['Player','CustomBossEvents']:d.pop(key,None)
    d['DragonFight']=n.Compound({'NeedsStateScanning':n.Byte(1),'DragonKilled':n.Byte(0),'PreviouslyKilled':n.Byte(0)})
    d['ScheduledEvents']=n.List[n.Compound]([])
    for key,value in [('SpawnX',65),('SpawnY',65),('SpawnZ',15),('GameType',1)]:d[key]=n.Int(value)
    d['SpawnAngle']=n.Float(180);d['Difficulty']=n.Byte(2);d['allowCommands']=n.Byte(1)
    d['LevelName']=n.String('油库里实验场 · 当前实装');d['Time']=n.Long(0);d['DayTime']=n.Long(6000);d['LastPlayed']=n.Long(int(time.time()*1000))
    d['DataPacks']['Enabled']=n.List[n.String](['vanilla','mod_data','file/mcylab'])
    d['DataPacks']['Disabled']=n.List[n.String]([])
    d['enabled_features']=n.List[n.String](['minecraft:vanilla'])
    settings=d['WorldGenSettings']['dimensions']['minecraft:overworld']['generator']['settings']
    settings['layers']=n.List[n.Compound]([n.Compound({'block':n.String(b),'height':n.Int(h)}) for b,h in [('minecraft:bedrock',1),('minecraft:stone',124),('minecraft:dirt',2),('minecraft:grass_block',1)]])
    settings['structure_overrides']=n.List[n.String]([])
    rules=d['GameRules']
    for k,v in {'doMobSpawning':'false','doDaylightCycle':'false','doWeatherCycle':'false','keepInventory':'true','spawnRadius':'0','commandBlockOutput':'false','randomTickSpeed':'3'}.items():rules[k]=n.String(v)
    metadata.save(world/'level.dat')
    zones=build(world)
    (out/'map-manifest.json').write_text(json.dumps({'runtime':str(runtime),'world':str(world),'minecraft':'1.21.1','zones':zones,'generated_at':time.strftime('%Y-%m-%d %H:%M:%S')},ensure_ascii=False,indent=2),encoding='utf-8')
    print(str(out))

if __name__=='__main__':main()
