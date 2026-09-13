"""Original shop/factory block plans. Emits deterministic vanilla 1.21.1 structure NBT."""
from pathlib import Path
import gzip, json, struct

ROOT = Path(__file__).resolve().parents[3]
OUT = ROOT / 'src/main/resources/data/mcyukkuri'

# Small typed NBT writer: no external package or Minecraft installation needed.
def byte(v): return (1, v)
def integer(v): return (3, v)
def real(v): return (5, v)
def string(v): return (8, v)
def sequence(kind, values): return (9, (kind, values))
def compound(v): return (10, v)
def text(v):
    b = v.encode('utf-8'); return struct.pack('>H', len(b)) + b
def payload(t, v):
    if t in (1, 3, 5, 6): return struct.pack({1:'>b',3:'>i',5:'>f',6:'>d'}[t], v)
    if t == 8: return text(v)
    if t == 9:
        kind, values = v
        return bytes([kind]) + struct.pack('>i', len(values)) + b''.join(payload(kind, x) for x in values)
    if t == 10: return b''.join(bytes([kind]) + text(k) + payload(kind, x) for k, (kind, x) in v.items()) + b'\0'
    raise ValueError(t)

class Building:
    def __init__(self, name, size): self.name=name; self.size=size; self.blocks={}; self.entities=[]
    def block(self, x,y,z, name, props=None, nbt=None):
        if ':' not in name: name='minecraft:'+name
        self.blocks[x,y,z]=(name,props or {},nbt)
    def box(self, a,b,name,props=None):
        for x in range(a[0],b[0]+1):
            for y in range(a[1],b[1]+1):
                for z in range(a[2],b[2]+1): self.block(x,y,z,name,props)
    def door(self,x,y,z,facing='north'):
        for dy,half in [(0,'lower'),(1,'upper')]: self.block(x,y+dy,z,'oak_door',{'facing':facing,'half':half,'hinge':'left','open':'false','powered':'false'})
    def entrance(self,x):
        self.box((x-1,2,0),(x+1,2,1),'air')
        for z,y in [(0,1),(1,2)]:
            for dx in (-1,0,1):
                self.block(x+dx,y,z,'stone_brick_stairs',{'facing':'south','half':'bottom','shape':'straight','waterlogged':'false'})
    def sign(self,x,y,z,lines,rotation='8'):
        messages=[json.dumps({'text':s},ensure_ascii=False) for s in (lines+['']*4)[:4]]
        self.block(x,y,z,'oak_sign',{'rotation':rotation,'waterlogged':'false'}, {'id':string('minecraft:sign'),
            'front_text':compound({'messages':sequence(8,messages),'color':string('black'),'has_glowing_text':byte(0)})})
    def human(self,x,z,kind,job,attitude,role):
        self.entity(x,3,z,'mcyukkuri:'+kind,{'HumanJob':integer(job),'HumanAttitude':integer(attitude),'SiteRole':string(role)})
    def animal(self,x,z,kind,role,use='stock',medal=0,group=0):
        self.entity(x,3,z,'mcyukkuri:'+kind,{'NeoForgeData':compound({'HumanCommerce':compound({'PendingRole':string(role),'Use':string(use),'Medal':integer(medal),'FactoryGroup':integer(group)})})})
    def display(self,x,y,z,item):
        self.block(x,y,z+1,'dark_oak_planks')
        self.entity(x+.5,y+.5,z+.94,'minecraft:item_display',{'item_display':string('fixed'),'item':compound({'id':string(item if ':' in item else 'mcyukkuri:'+item),'count':integer(1)}),
            'transformation':compound({'translation':sequence(5,[0.,0.,0.]),'scale':sequence(5,[.65,.65,.65]),'left_rotation':sequence(5,[0.,0.,0.,1.]),'right_rotation':sequence(5,[0.,0.,0.,1.])})})
    def entity(self,x,y,z,kind,extra):
        self.entities.append({'pos':sequence(6,[x,y,z]),'blockPos':sequence(3,[int(x),int(y),int(z)]),
            'nbt':compound({'id':string(kind),'Pos':sequence(6,[x,y,z]),'Rotation':sequence(5,[180.,0.]),
                'PersistenceRequired':byte(1),'Invulnerable':byte(0),**extra})})
    def bowl(self,x,z):
        self.block(x,3,z,'mcyukkuri:food_bowl',nbt={'id':string('mcyukkuri:care')})
    def pen(self,x,z,kind,role,use='stock',medal=0):
        self.box((x,3,z),(x+4,5,z+4),'glass')
        self.box((x+1,3,z+1),(x+3,5,z+3),'air')
        self.box((x+1,2,z+1),(x+3,2,z+3),'oak_planks')
        self.bowl(x+1,z+2); self.block(x+3,3,z+2,'mcyukkuri:yukkuri_nest')
        self.animal(x+2.5,z+2.5,kind,role,use,medal)
    def bed(self,x,z,color):
        self.block(x,3,z,f'{color}_bed',{'facing':'south','part':'foot','occupied':'false'})
        self.block(x,3,z+1,f'{color}_bed',{'facing':'south','part':'head','occupied':'false'})
    def save(self):
        palette=[]; indices={}; blocks=[]
        for pos,(name,props,nbt) in sorted(self.blocks.items()):
            key=(name,tuple(sorted(props.items())))
            if key not in indices:
                indices[key]=len(palette); p={'Name':string(name)}
                if props: p['Properties']=compound({k:string(v) for k,v in sorted(props.items())})
                palette.append(p)
            value={'pos':sequence(3,list(pos)),'state':integer(indices[key])}
            if nbt: value['nbt']=compound(nbt)
            blocks.append(value)
        nbt={'DataVersion':integer(3955),'size':sequence(3,self.size),'palette':sequence(10,palette),'blocks':sequence(10,blocks),'entities':sequence(10,self.entities)}
        path=OUT/'structure'/f'{self.name}.nbt'; path.parent.mkdir(parents=True,exist_ok=True)
        raw=b'\x0a\x00\x00'+payload(10,nbt); path.write_bytes(gzip.compress(raw,mtime=0))
        return {'name':self.name,'size':self.size,'blocks':len(blocks),'entities':len(self.entities),'source':'art/structures/human-world/build.py'}

def shell(b,w,d,wall):
    b.box((0,0,0),(w-1,1,d-1),'stone_bricks')
    b.box((0,2,0),(w-1,2,d-1),'gravel')
    b.box((0,3,0),(w-1,b.size[1]-1,d-1),'air')
    b.box((2,2,2),(w-3,2,d-3),'polished_andesite')
    b.box((2,3,2),(w-3,7,d-3),wall)
    b.box((3,3,3),(w-4,7,d-4),'air')
    for x in (2,w-3):
        for z in (2,d-3): b.box((x,3,z),(x,7,z),'stripped_dark_oak_log',{'axis':'y'})
    for x in (4,w-5):
        b.box((x,4,2),(x+2,5,2),'glass')
    for x in (2,w-3): b.box((x,4,5),(x,5,8),'glass')
    for x in range(5,w-4,6):
        for z in range(5,d-4,6): b.block(x,7,z,'lantern',{'hanging':'true','waterlogged':'false'})

def shop():
    b=Building('yukkuri_shop',[25,13,25]); shell(b,25,25,'white_terracotta')
    for inset in range(5):
        b.box((1+inset,8+inset,1),(23-inset,8+inset,23),'dark_oak_planks')
        if inset<4: b.box((2+inset,8+inset,2),(22-inset,8+inset,22),'air')
    # Roof cavity is hollow: give hanging lamps solid beams so block updates cannot drop them.
    for z in (5,11,20):
        b.box((3,7,z),(21,7,z),'dark_oak_planks')
        for x in (5,11,17): b.block(x,6,z,'lantern',{'hanging':'true','waterlogged':'false'})
    b.door(12,3,2); b.box((11,2,0),(13,2,2),'stone_bricks')
    b.entrance(12)
    b.box((8,6,1),(16,6,1),'green_concrete')
    b.sign(12,7,1,['油库里商店','YUKKURI SHOP','活体 / 用品','欢迎光临'])
    b.pen(3,4,'reimu','shopkeeper'); b.pen(3,10,'marisa','shopkeeper'); b.pen(17,4,'chen','shopkeeper',medal=1); b.pen(17,10,'alice','shopkeeper',medal=2)
    for x,z,label in [(5,3,'普通宠物 · 灵梦'),(5,9,'普通宠物 · 魔理沙'),(19,3,'银章宠物 · 橙'),(19,9,'金章宠物 · 爱丽丝')]:
        b.sign(x,3,z,[label,'健康 / 教育档案','向柜台店员购买','含专用运输箱'])
    b.box((10,3,7),(14,3,7),'dark_oak_slab',{'type':'bottom','waterlogged':'false'})
    b.block(12,3,8,'lectern',{'facing':'north','has_book':'false','powered':'false'})
    b.box((3,3,17),(21,6,17),'white_terracotta'); b.door(12,3,17)
    # Real care props and product displays, with a clear central customer aisle.
    for x,item in [(8,'oil_grain_regular'),(9,'oil_grain_premium'),(10,'herbal_medicine'),(14,'bandage'),(15,'teaching_whistle'),(16,'yukkuri_carrier')]:
        b.block(x,3,16,'barrel',{'facing':'north','open':'false'});b.display(x,4,16,item)
    for x,prop in [(9,'food_bowl'),(15,'yukkuri_nest')]:
        b.block(x,3,12,'oak_slab',{'type':'top','waterlogged':'false'});b.block(x,4,12,'mcyukkuri:'+prop)
    b.sign(9,3,11,['食盆 / 油粮','清洁 / 医疗','衣饰 / 教育','柜台五页用品'])
    b.sign(15,3,11,['捕食种 · 实体出售','金银章与教育可控','无章保留野外个性','活体列表翻页购买'])
    # Two roofed pens retain the actual stock identities and give flying animals solid boundaries.
    for x,kind,medal in [(3,'remilia',1),(17,'flandre',0)]:
        b.box((x,3,12),(x+4,6,16),'glass')
        b.box((x+1,3,13),(x+3,5,15),'air')
        b.block(x+2,3,14,'mcyukkuri:predator_nest')
        b.bowl(x+1,14)
        b.animal(x+2.5,15.0,kind,'shopkeeper','stock',medal)
    b.sign(5,3,11,['蕾米莉亚 · 银章','振翅飞行 / 归巢','饲喂实际馅料','向店员购买'])
    b.sign(12,5,16,['用品柜台','食 / 住 / 清洁','护理 / 教育 / 饰物','宠物保留成长档案'])
    b.bed(5,20,'green'); b.bed(18,20,'pink'); b.block(12,3,21,'crafting_table')
    for x in (8,15): b.block(x,3,21,'barrel',{'facing':'up','open':'false'})
    b.human(12.5,6.5,'caretaker_brother',1,2,'shopkeeper')
    b.human(7.5,20.5,'caretaker_brother',0,1,'shop_brother')
    b.human(17.5,20.5,'caretaker_sister',0,1,'shop_sister')
    b.animal(10.5,20.5,'reimu','shop_brother','companion'); b.bowl(10,21)
    # A sheltered hedge nook outside the public shop, for a persistent missing-companion quest.
    b.box((0,3,18),(1,5,22),'oak_leaves',{'persistent':'true','distance':'1'})
    b.box((0,3,19),(1,4,21),'air'); b.block(0,3,18,'air')
    b.animal(.5,20.5,'marisa','shop_sister','lost')
    return b

def factory():
    b=Building('yukkuri_factory',[37,13,31]); shell(b,37,31,'light_gray_concrete')
    b.box((1,8,1),(35,8,29),'stone_brick_slab',{'type':'bottom','waterlogged':'false'})
    for x in range(4,34,7): b.box((x,8,4),(x+2,8,25),'glass')
    b.box((30,9,23),(32,12,25),'bricks'); b.block(31,12,24,'air')
    b.door(8,3,2); b.box((7,2,0),(9,2,2),'stone_bricks')
    b.entrance(8)
    b.sign(8,6,1,['油库里加工所','PROCESSING WORKS','参观 / 交货','请走玻璃廊道'])
    # Public gallery and isolated stock/production zones.
    b.box((3,3,10),(33,6,10),'glass'); b.door(16,3,10)
    b.pen(3,3,'reimu','factory_worker','reserve'); b.pen(25,3,'marisa','factory_worker','reserve')
    b.box((3,2,13),(11,2,24),'moss_block'); b.box((3,3,13),(11,3,24),'oak_fence')
    b.box((4,3,14),(10,3,23),'air'); b.block(7,3,13,'oak_fence_gate',{'facing':'south','open':'false','powered':'false','in_wall':'false'})
    b.animal(6.5,18.5,'reimu','factory_worker','reserve'); b.animal(9.5,21.5,'chen','factory_worker','reserve'); b.bowl(5,19)
    # Roller beds, machinery housing, receiving table and material storage.
    b.box((18,2,13),(22,2,23),'polished_andesite')
    for z in range(13,24):
        b.block(22,3,z,'rail',{'shape':'north_south','waterlogged':'false'})
        b.block(21,3,z,'iron_trapdoor',{'facing':'east','half':'bottom','open':'false','powered':'false','waterlogged':'false'})
    b.box((20,3,22),(24,5,24),'iron_block'); b.box((21,3,22),(23,4,22),'air')
    for x in (26,29,32):
        b.block(x,3,23,'barrel',{'facing':'north','open':'false'}); b.block(x,4,23,'barrel',{'facing':'north','open':'false'})
    b.block(17,3,14,'lectern',{'facing':'west','has_book':'false','powered':'false'})
    b.sign(17,3,12,['交货与加工','持装有个体的箱','每笔 3 绿宝石','每日限量'])
    b.sign(11,3,8,['参观走廊','前方：加工间','左侧：育成区','右侧：成品库'])
    b.human(18.5,16.5,'caretaker_brother',2,2,'factory_worker')
    b.human(30.5,16.5,'oniisan',0,0,'factory_oniisan')
    b.box((27,3,12),(33,6,12),'light_gray_concrete'); b.door(30,3,12)
    b.bed(30,19,'red'); b.block(32,3,19,'crafting_table')
    return b

def specialized_factory(kind):
    """Separate footprints share service conventions, not an interchangeable empty warehouse."""
    role=kind+'_worker';colors={'labor':'yellow','manju':'orange','bag':'cyan'};color=colors[kind]
    title={'labor':'油库里劳力工场','manju':'油库里馒头工厂','bag':'活体袋装加工厂'}[kind]
    b=Building('yukkuri_'+kind+'_factory',[41,15,35]);shell(b,41,35,'light_gray_concrete')
    b.box((1,8,1),(39,8,33),'smooth_stone')
    for x in (5,17,29):b.box((x,8,5),(x+6,8,29),'glass')
    for z in (9,19,29):b.box((2,8,z),(38,8,z),'polished_andesite')
    b.box((2,6,2),(38,6,2),color+'_concrete');b.door(20,3,2);b.entrance(20)
    b.sign(20,7,1,[title,'接待 / 原料 / 成品','白天营业','请沿黄色访客线'])
    # Public gallery at the front and a broad service corridor down the middle.
    b.box((3,2,3),(37,2,8),'white_concrete')
    b.box((3,2,8),(37,2,8),'yellow_concrete')
    b.box((3,3,9),(37,6,9),'glass');b.door(20,3,9)
    for x in (7,31):
        b.box((x,3,4),(x+3,3,4),'dark_oak_stairs',{'facing':'south','half':'bottom','shape':'straight','waterlogged':'false'})
    b.sign(12,3,6,['收货 / 成品采购','交付运输箱内个体','每笔 3 绿宝石','原料补给 ×8'])
    b.sign(28,3,6,['生产须消耗原料','成品库存最多 16','育成遵守成长周期' if kind!='labor' else '油库里劳动 · 不发工资','成品向工人购买'])
    # Open staff office, material cages and sleeping/service rooms at the east side.
    b.box((30,3,10),(30,6,31),'glass');b.door(30,3,20,'east')
    b.box((31,3,22),(37,6,22),'light_gray_concrete');b.door(34,3,22)
    b.bed(32,27,'gray');b.bed(36,27,'red')
    b.block(35,3,24,'crafting_table');b.block(33,3,24,'lectern',{'facing':'south','has_book':'false','powered':'false'})
    for x in (32,34,36):
        for z in (12,15):b.block(x,3,z,'barrel',{'facing':'north','open':'false'});b.block(x,4,z,'barrel',{'facing':'north','open':'false'})
    b.sign(34,3,18,['原料与成品库',{'labor':'甘蔗 → 纸张','manju':'小麦 + 实际馅料','bag':'纸袋 + 同一活体'}[kind],'库存由工人登记','原料耗尽即停工'])
    # Machine banks have their own authored models; vanilla blocks form housings and rails only.
    machine={'labor':'labor_bench','manju':'manju_steamer','bag':'live_packing_table'}[kind]
    for x in ((18,24) if kind=='labor' else ()):
        for z in (14,20,26):b.block(x,3,z,'mcyukkuri:'+machine)
        b.box((x+1,3,12),(x+1,3,28),'smooth_stone_slab',{'type':'top','waterlogged':'false'})
        for z in range(12,29,2):b.block(x+1,4,z,'heavy_weighted_pressure_plate',{'power':'0'})
    for x,z in [(17,17),(27,25)]:b.bowl(x,z)
    b.human(21.5,18.5,'caretaker_brother',2,2,role)
    b.human(35.5,25.5,'oniisan',0,0,kind+'_foreman')
    # End-of-line inspection and labeled storage make the material flow legible from the gallery.
    for x in (17,20,23,26):
        b.block(x,3,31,'barrel',{'facing':'north','open':'false'})
        b.display(x,4,31,'yukkuri_manju' if kind=='manju' else 'live_yukkuri_bag' if kind=='bag' else 'minecraft:paper')
    for z in (14,26):
        b.box((16,7,z),(28,7,z),'polished_andesite_slab',{'type':'top','waterlogged':'false'})
    if kind=='manju':
        for x in (18,24):b.box((x,5,14),(x,7,14),'iron_bars')
    if kind=='labor':
        b.box((3,2,11),(13,2,30),'oak_planks')
        for z in (12,19,26):
            b.block(5,3,z,'mcyukkuri:yukkuri_nest');b.bowl(8,z)
            b.block(11,3,z,'mcyukkuri:yukkuri_toilet')
        b.sign(9,3,10,['集体宿舍 / 食堂','低成本油库里劳力','供粮耗尽需要补给','休息后继续上工'])
        for x,z,variant in [(17.5,13.5,'reimu'),(23.5,13.5,'marisa'),(17.5,19.5,'chen'),(23.5,19.5,'alice')]:b.animal(x,z,variant,role,'labor')
        b.box((35,9,27),(36,11,28),'iron_bars')
    else:
        b.box((14,3,10),(14,6,31),'glass');b.box((14,3,18),(14,4,19),'air')
        b.box((3,2,11),(13,2,30),'pink_terracotta')
        b.sign(8,3,10,['专用子油生产车间','亲代配对 → 子油育成','子油分流 → 加工线','缺粮 / 超量时暂停'])
        for x,z in [(5,14),(9,14),(5,18),(9,18),(5,24),(11,27)]:
            b.block(x,3,z,'mcyukkuri:yukkuri_nest');b.bowl(x,z+2)
        b.block(8,3,25,'mcyukkuri:nursery_station');b.block(4,3,20,'mcyukkuri:yukkuri_toilet')
        for x,z,device in [(16,14,'factory_receiving_table'),(20,19,'factory_inspection_table'),(24,23,'factory_washing_table'),(5,29,'factory_observation_station'),(24,28,machine)]:
            b.block(x,3,z,'mcyukkuri:'+device,{'facing':'north'})
        for x,z,lines in [(17,11,['01 落地接收','批次 / 出生检查','周转托盘搬运','禁止堆放堵道']),
                (21,17,['02 出货分选','种类 / 体型 / 健康','油虐用 / 饲料用','按订单分流']),
                (25,21,['03 清洁处理','工人接触作业','完成后送往封装','保持通道畅通']),
                (6,27,['异常观察区','护理后重新检查','不合格继续暂存','现场档案可查']),
                (8,22,['两栏子油育成','同胎同批次','年龄与营养达标','再送出货分选']),
                (5,11,['亲代工位 A / B','轮换 / 休养 / 替换','后备亲代交给工人','单胎仍为 1–5 只'])]:
            b.sign(x,3,z,lines)
        b.box((15,2,12),(17,2,28),'yellow_terracotta')
        b.box((18,2,18),(27,2,18),'yellow_terracotta')
        b.box((22,2,19),(22,2,28),'yellow_terracotta')
        b.human(11.5,18.5,'caretaker_sister',0,1,kind+'_nursery_attendant')
        # Actual parents receive nearby nests and relationship records on structure binding.
        b.animal(6.5,15.5,'reimu',role,'breeder');b.animal(8.5,15.5,'marisa',role,'breeder')
        b.animal(6.5,19.5,'reimu',role,'breeder',group=1);b.animal(8.5,19.5,'marisa',role,'breeder',group=1)
        for x,z,variant in [(17.5,13.5,'reimu'),(23.5,13.5,'chen')]:b.animal(x,z,variant,role,'input')
        if kind=='manju':
            b.box((34,9,27),(36,14,29),'bricks');b.block(35,14,28,'air')
            b.sign(21,3,30,['馅料处理 / 蒸制','每份消耗实际馅料','成品：油库里馒头','原料：小麦'])
        else:
            b.box((5,9,3),(35,9,3),'cyan_concrete')
            b.sign(21,3,30,['04 活体封装 / 出货','油虐用 / 饲料用规格','批次 / 体型 / 状态','同一个体 · 单次拆袋'])
    return b

def export():
    summary=[shop().save(),factory().save()]+[specialized_factory(k).save() for k in ('labor','manju','bag')]
    for name,spacing,separation,salt in [('yukkuri_shop',24,10,17923041),('yukkuri_factory',48,20,17923042),
            ('yukkuri_labor_factory',48,20,17923043),('yukkuri_manju_factory',48,20,17923044),('yukkuri_bag_factory',48,20,17923045)]:
        files={
            f'worldgen/template_pool/{name}.json':{'name':'mcyukkuri:'+name,'fallback':'minecraft:empty','elements':[{'weight':1,'element':{'element_type':'minecraft:single_pool_element','location':'mcyukkuri:'+name,'processors':'minecraft:empty','projection':'rigid'}}]},
            f'worldgen/structure/{name}.json':{'type':'mcyukkuri:human_building','biomes':'#mcyukkuri:human_buildings','step':'surface_structures','spawn_overrides':{},'terrain_adaptation':'beard_thin','start_pool':'mcyukkuri:'+name},
            f'worldgen/structure_set/{name}.json':{'structures':[{'structure':'mcyukkuri:'+name,'weight':1}],'placement':{'type':'minecraft:random_spread','spacing':spacing,'separation':separation,'salt':salt}}
        }
        for name_,data in files.items():
            # Keep the old /place and saved structure ID, but new worlds choose the three explicit types.
            if name=='yukkuri_factory' and 'structure_set' in name_:
                (OUT/name_).unlink(missing_ok=True)
                continue
            p=OUT/name_; p.parent.mkdir(parents=True,exist_ok=True); p.write_text(json.dumps(data,indent=2)+'\n',encoding='utf-8')
    p=OUT/'tags/worldgen/biome/human_buildings.json';p.parent.mkdir(parents=True,exist_ok=True)
    p.write_text(json.dumps({'replace':False,'values':['minecraft:plains','minecraft:sunflower_plains','minecraft:meadow','minecraft:savanna']},indent=2)+'\n',encoding='utf-8')
    p=OUT/'neoforge/biome_modifier/human_spawns.json'
    p.write_text(json.dumps({'type':'neoforge:add_spawns','biomes':'#mcyukkuri:human_buildings','spawners':[{'type':'mcyukkuri:'+v,'weight':1,'minCount':1,'maxCount':1} for v in ['oniisan','caretaker_brother','caretaker_sister']]},indent=2)+'\n',encoding='utf-8')
    (Path(__file__).parent/'manifest.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
    return summary

if __name__=='__main__': print(json.dumps(export(),ensure_ascii=False))
