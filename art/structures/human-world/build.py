"""Original shop/factory block plans. Emits deterministic vanilla 1.21.1 structure NBT."""
from pathlib import Path
import gzip, json, struct, sys
sys.path.insert(0,str(Path(__file__).parent))

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
    def __init__(self, name, size):
        self.name=name; self.size=size; self.blocks={}; self.entities=[]
        # Furnishing phase bookkeeping (used by check_structure.py): after `strict` is switched on, a write that replaces
        # a different non-air block is recorded unless the caller passes over=True.
        self.strict=False; self.conflicts=[]; self.pens=[]; self.doors=[]
    def block(self, x,y,z, name, props=None, nbt=None, over=False):
        if ':' not in name: name='minecraft:'+name
        props=props or {}; old=self.blocks.get((x,y,z))
        if self.strict and not over and old and old[0]!='minecraft:air' and (old[0],old[1])!=(name,props): self.conflicts.append(((x,y,z),old[0],name))
        self.blocks[x,y,z]=(name,props,nbt)
    def box(self, a,b,name,props=None,over=False):
        for x in range(a[0],b[0]+1):
            for y in range(a[1],b[1]+1):
                for z in range(a[2],b[2]+1): self.block(x,y,z,name,props,over=over)
    def door(self,x,y,z,facing='north'):
        self.doors.append((x,y,z))
        for dy,half in [(0,'lower'),(1,'upper')]: self.block(x,y+dy,z,'oak_door',{'facing':facing,'half':half,'hinge':'left','open':'false','powered':'false'},over=True)
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
    def pet(self,x,z,kind,role,use,grade,pedigree):
        """Shop animal: HumanCommerce Grade 0..3 (none/bronze/silver/gold) and Pedigree; legacy Medal = Grade-1 is kept for silver and gold."""
        data={'PendingRole':string(role),'Use':string(use),'Grade':integer(grade),'Pedigree':integer(pedigree),'FactoryGroup':integer(0)}
        if grade>=2: data['Medal']=integer(grade-1)
        self.entity(x,3,z,'mcyukkuri:'+kind,{'NeoForgeData':compound({'HumanCommerce':compound(data)})})
    def display(self,x,y,z,item,over=False):
        self.block(x,y,z+1,'dark_oak_planks',over=over)
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

def stairs(b,x,y,z,name,facing,half='bottom'):
    b.block(x,y,z,name+'_stairs',{'facing':facing,'half':half,'shape':'straight','waterlogged':'false'})
def slab(b,x,y,z,name,kind='bottom'):
    b.block(x,y,z,name+'_slab',{'type':kind,'waterlogged':'false'})
def barrel(b,x,y,z,facing='up'): b.block(x,y,z,'barrel',{'facing':facing,'open':'false'})
def mark(b,x,z,kind): b.block(x,3,z,'mcyukkuri:exam_mark',{'kind':kind})
def glass(b,a,c,y0=3,y1=7,full=False):
    """Pen wall along a line of cells: full height glass (street, predator booth) or a birch base with glass panes (y4..5)."""
    for x in range(a[0],c[0]+1):
        for z in range(a[1],c[1]+1):
            if full: b.box((x,y0,z),(x,y1,z),'glass'); continue
            if name_at(b,x,4,z)=='minecraft:glass': continue   # a shared full-height wall wins
            b.block(x,3,z,'birch_planks'); b.box((x,4,z),(x,5,z),'glass_pane',{'north':'false','south':'false','east':'false','west':'false','waterlogged':'false'})
def name_at(b,x,y,z): return b.blocks.get((x,y,z),('minecraft:air',))[0]
def pen_rect(b,x0,z0,x1,z1,y0=3,y1=7,skip='',full=False):
    """Wall ring around an interior rectangle (walls sit just outside the interior); skip = sides n/s/w/e that already have a wall."""
    if 'n' not in skip: glass(b,(x0-1,z0-1),(x1+1,z0-1),y0,y1,full)
    if 's' not in skip: glass(b,(x0-1,z1+1),(x1+1,z1+1),y0,y1,full)
    if 'w' not in skip: glass(b,(x0-1,z0),(x0-1,z1),y0,y1,full)
    if 'e' not in skip: glass(b,(x1+1,z0),(x1+1,z1),y0,y1,full)
def connect_panes(b):
    """Pane connection flags as vanilla would update them: panes, glass and opaque blocks."""
    from check_structure import transparent
    for (x,y,z),(n,p,_) in list(b.blocks.items()):
        if n!='minecraft:glass_pane': continue
        props=dict(p)
        for d,(dx,dz) in {'north':(0,-1),'south':(0,1),'east':(1,0),'west':(-1,0)}.items():
            m=name_at(b,x+dx,y,z+dz); props[d]='true' if m in ('minecraft:glass_pane','minecraft:glass') or (m!='minecraft:air' and not transparent(m)) else 'false'
        b.blocks[x,y,z]=(n,props,None)
def register_pen(b,name,interior,bowl,nests,minimum=(3,3)):
    b.pens.append({'name':name,'interior':interior,'bowl':bowl,'nests':nests,'min':minimum})
def carpet(b,x0,z0,x1,z1,color):
    """Rug on free floor cells only."""
    for x in range(x0,x1+1):
        for z in range(z0,z1+1):
            if name_at(b,x,3,z)=='minecraft:air': b.block(x,3,z,color+'_carpet')
def roof(b,z0,z1):
    """Low pitched roof running along x: slab/stair pairs rise 1 per 2 blocks from both eaves to a flat ridge, gable ends filled.
    Dark-oak eave trim and ridge cap split each slope into 5-row mud-brick bands; three skylight patches per slope break it up."""
    dmax=max(min(z-z0,z1-z) for z in range(z0,z1+1))
    for z in range(z0,z1+1):
        front=z-z0<=z1-z; d=min(z-z0,z1-z); y=9+d//2
        for x in range(0,41):
            mat='dark_oak' if d in (0,dmax) or x in (0,40) else 'mud_brick'
            if 2<=d<=4 and (x//1 in (6,7,8,20,21,22,34,35,36)): b.block(x,y,z,'glass'); continue
            if d%2==0: slab(b,x,y,z,mat)
            else: stairs(b,x,y,z,mat,'south' if front else 'north')
        for x in (1,39):
            for yy in range(9,y): b.block(x,yy,z,'smooth_quartz')
def shop():
    from check_structure import light_levels, walk_cells
    b=Building('yukkuri_shop',[41,16,33])
    Q='smooth_quartz'; LOG={'axis':'y'}
    # ---- Shell: foundation, paving, 39x28 walls (outer x 1..39, z 3..30), 5-high rooms (y 3..7) under a y=8 ceiling.
    b.box((0,0,0),(40,1,32),'stone_bricks'); b.box((0,2,0),(40,2,32),'polished_andesite')
    b.box((1,3,3),(39,8,30),Q); b.box((2,3,4),(38,7,29),'air')
    b.box((2,2,4),(38,2,29),'birch_planks'); b.box((2,2,18),(22,2,29),'oak_planks')
    for x0,x1,z0,z1 in [(29,35,14,18),(24,27,14,18),(29,31,23,25),(33,35,23,25)]: b.box((x0,2,z0),(x1,2,z1),'oak_planks')
    b.box((2,2,8),(22,2,9),'smooth_quartz'); b.box((11,2,4),(13,2,7),'smooth_quartz')
    b.box((23,3,4),(23,8,29),Q); b.box((2,3,17),(22,8,17),Q); b.box((12,3,18),(12,8,29),Q)
    # plinth and sign band wrap the building; pillars every 6 blocks keep every wall patch within 6x6.
    for x in range(1,40):
        for z in (3,30): b.block(x,3,z,'mud_bricks'); b.block(x,8,z,'dark_oak_planks')
    for z in range(3,31):
        for x in (1,39): b.block(x,3,z,'mud_bricks'); b.block(x,8,z,'dark_oak_planks')
    for x in (1,9,15,23,31,39): b.box((x,3,3),(x,8,3),'stripped_birch_log',LOG)
    for x in (1,7,13,19,25,31,37,39): b.box((x,3,30),(x,8,30),'stripped_birch_log',LOG)
    for z in (3,9,15,21,27,30): b.box((1,3,z),(1,8,z),'stripped_birch_log',LOG); b.box((39,3,z),(39,8,z),'stripped_birch_log',LOG)
    for a,c in zip((1,7,13,19,25,31,37),(7,13,19,25,31,37,39)):
        gap=c-a-1
        if gap>=4:
            s=a+1+(gap-3)//2
            for t in range(s,s+3): b.box((t,5,30),(t,6,30),'glass')
    zl=[3,9,15,21,27,30]
    for a,c in zip(zl,zl[1:]):
        gap=c-a-1
        if gap>=4:
            s=a+1+(gap-3)//2
            for t in range(s,s+3): b.box((1,5,t),(1,6,t),'glass'); b.box((39,5,t),(39,6,t),'glass')
    # Street facade: big display windows y 4..7 with birch mullions at the booth glass lines, doors for shop and exam hall.
    for a,c in [(2,8),(10,14),(16,22),(24,30),(32,38)]: b.box((a,4,3),(c,7,3),'glass')
    for x in (5,19,35): b.box((x,4,3),(x,7,3),'stripped_birch_log',LOG)
    b.door(12,3,3); b.door(27,3,3)
    # Red/white awning (2-block stripes) stepping out over the pavement, shop banners on the pillars, paw-print sign board.
    for x in range(1,40):
        wool='red_wool' if (x//2)%2==0 else 'white_wool'
        b.block(x,8,2,wool); b.block(x,7,2,wool); b.block(x,7,1,wool)
    for x in (9,15,23,31): b.block(x,5,2,'red_wall_banner',{'facing':'north'})
    b.box((6,8,2),(18,13,2),'dark_oak_planks'); b.box((7,9,2),(17,12,2),'green_terracotta')
    emblem=['...RRR.....','.WWWWWWWWW.','.WWKKWKKWW.','..WWWWWWW..']   # yukkuri head: white bun, red bow, black eyes
    for r,row in enumerate(emblem):
        for c,ch in enumerate(row):
            if ch!='.': b.block(7+c,12-r,2,{'W':'white_concrete','R':'red_concrete','K':'black_concrete'}[ch])
    # Planting and benches on the pavement.
    for x0,x1 in [(1,7),(33,39)]: b.box((x0,3,0),(x1,3,0),'flowering_azalea_leaves',{'persistent':'true','distance':'7'})
    for x in (9,15,23,31): b.block(x,3,1,'potted_azalea_bush')
    for x in (3,5,35,37): stairs(b,x,3,1,'birch','north')
    # Gabled roofs over the front and rear blocks; roof is 4 rows on a 6-row facade (y 3..8).
    roof(b,3,16); roof(b,17,30)
    b.box((6,9,3),(18,12,3),Q)   # false front carries the sign board
    b.sign(26,3,2,['考核处','EXAM HALL','口试 / 实技'],'8')
    for x in (11,12,13,26,27,28):   # street-edge ramp: two half-height steps up to the pavement
        b.block(x,2,0,'air'); stairs(b,x,1,0,'stone_brick','south'); stairs(b,x,2,1,'stone_brick','south')
    for z in (9,24):
        for x in (4,12,20,28,36): b.block(x,9,z,'light',{'level':'10','waterlogged':'false'})   # invisible attic light: nothing spawns under the roof
    # ---- Furnishing phase: every overwrite of a different block is now an error unless explicitly allowed.
    b.strict=True
    for x,z in [(23,9)]: b.door(x,3,z,'east')
    b.door(10,3,17); b.door(14,3,17); b.door(12,3,24,'east')
    # Sales hall: four display booths (interior 3x3, glass to street window and aisle). The window wall is the street glass.
    def booth(x0,kind,grade,ped,label,predator=False):
        for x in (x0-1,x0+3):
            if 2<=x<=22: glass(b,(x,4),(x,7),full=predator)
        glass(b,(max(x0-1,2),7),(min(x0+3,22),7),full=predator)
        b.bowl(x0,4); b.block(x0+2,3,4,'mcyukkuri:predator_nest' if predator else 'mcyukkuri:yukkuri_nest')
        b.sign(x0+1,3,6,label,'0'); b.pet(x0+1.5,5.5,kind,'shopkeeper','stock',grade,ped)
        register_pen(b,'booth_'+kind,(x0,4,x0+2,6),(x0,4),[(x0+2,4)])
    booth(20,'remilia',2,75,['银章 · 蕾米莉亚','捕食种 · 单独展舱','展示个体','向店员购买'],predator=True)   # full-height glass; written first so the shared wall at x=19 stays full
    booth(2,'chen',2,72,['银章 · 橙','餡統 72','展示个体','向店员购买'])
    booth(6,'alice',3,85,['金章 · 爱丽丝','餡統 85','展示个体','向店员购买'])
    booth(16,'reimu',2,70,['银章 · 灵梦','餡統 70','展示个体','向店员购买'])
    # Counter (customer side north, clerk behind), register lectern, goods wall on the clerk's back.
    b.box((8,3,12),(16,3,12),'dark_oak_planks'); b.box((16,3,13),(16,3,16),'dark_oak_planks')
    b.block(12,3,12,'lectern',{'facing':'north','has_book':'false','powered':'false'},over=True)
    goods=['oil_grain_regular','oil_grain_premium','orange_juice','wash_cloth','teaching_whistle','yukkuri_carrier']   # columns 10 and 14 stay free: staff doors to the back rooms
    for x,item in zip((8,9,11,12,13,15),goods): barrel(b,x,3,16); b.display(x,4,16,item,over=True)
    b.human(11.5,14.5,'caretaker_brother',1,2,'shopkeeper')
    # Free-standing gondola shelves (backing planks on the south side) and care-goods tables.
    for xs,items in [((3,4,5),['yarn_ball','companion_cord','oil_grain_budget']),((19,20,21),['reimu_ribbon','marisa_hat','mold_remover'])]:
        for x,item in zip(xs,items): barrel(b,x,3,11); b.display(x,4,11,item); b.block(x,3,12,'dark_oak_planks')
    for x,prop in [(3,'food_bowl'),(4,'yukkuri_nest'),(5,'yukkuri_toilet')]:
        slab(b,x,3,15,'oak','top'); b.block(x,4,15,'mcyukkuri:'+prop)
    # Consultation corner: small table and two seats.
    b.block(20,3,15,'spruce_fence',{'north':'false','south':'false','east':'false','west':'false','waterlogged':'false'}); b.block(20,4,15,'spruce_pressure_plate',{'powered':'false'})
    stairs(b,19,3,15,'spruce','east'); stairs(b,21,3,15,'spruce','west')
    b.block(22,3,14,'potted_fern'); b.block(10,3,4,'potted_azalea_bush'); b.block(14,3,4,'potted_azalea_bush')
    # ---- Back room (x 2..11, z 18..29): four low glass tanks (3x3), aisle z 22..25, corridor x 10..11, feed barrels, keeper spot.
    for x0,z0,kind,ped,aisle in [(2,18,'reimu',30,21),(6,18,'marisa',25,21),(2,27,'patchouli',35,26),(6,27,'chen',40,26)]:
        for x in (x0-1,x0+3):
            if x>=2: glass(b,(x,z0),(x,z0+2))
        glass(b,(max(x0-1,2),aisle),(x0+3,aisle))
        bz=z0+2 if aisle==26 else z0   # bowl and nest on the side away from the aisle
        b.bowl(x0,bz); b.block(x0+2,3,bz,'mcyukkuri:yukkuri_nest'); b.pet(x0+1.5,z0+1.5,kind,'shopkeeper','stock',0,ped)
        register_pen(b,'tank_'+kind,(x0,z0,x0+2,z0+2),(x0,bz),[(x0+2,bz)])
    for z in range(22,26): barrel(b,2,3,z)
    for z in (22,25): barrel(b,3,3,z)
    b.block(5,3,22,'crafting_table'); b.block(7,3,22,'lectern',{'facing':'south','has_book':'false','powered':'false'})
    b.human(6.5,24.5,'caretaker_brother',4,1,'shop_keeper')
    # ---- Breeding room (x 13..22): corridor x 13..15, stud pen (5x5) and nursery pen (5x5), keeper doors in the west glass.
    pen_rect(b,17,18,21,22,skip='n')
    b.door(16,3,21,'east'); b.box((17,2,18),(21,2,22),'oak_planks',over=True); b.box((17,2,24),(21,2,28),'oak_planks',over=True)
    pen_rect(b,17,24,21,28); b.door(16,3,26,'east')
    b.bowl(19,22); b.block(17,3,22,'mcyukkuri:yukkuri_nest'); b.block(21,3,22,'mcyukkuri:yukkuri_nest'); b.block(21,3,18,'mcyukkuri:yukkuri_toilet')
    b.pet(18.5,19.5,'reimu','shop_keeper','stud',2,80); b.pet(20.5,19.5,'marisa','shop_keeper','stud',2,80)
    register_pen(b,'stud',(17,18,21,22),(19,22),[(17,22),(21,22)],(4,4))
    b.bowl(19,28); b.block(17,3,28,'mcyukkuri:yukkuri_nest'); b.block(21,3,28,'mcyukkuri:yukkuri_nest')
    register_pen(b,'nursery',(17,24,21,28),(19,28),[(17,28),(21,28)],(3,3))
    b.sign(14,3,19,['繁育室','种油栏 / 幼体栏','饲养员专用','请勿喧哗'],'0')
    # ---- Exam hall (x 24..38, z 4..29).
    # Oral area: podium faces the examiner desk; hide corner; two promise seats.
    b.block(34,3,5,'mcyukkuri:exam_podium',{'facing':'south'})
    b.box((33,3,7),(35,3,7),'dark_oak_planks'); b.block(34,4,7,'oak_pressure_plate',{'powered':'false'})
    b.human(34.5,8.5,'caretaker_sister',3,1,'shop_examiner')
    b.block(38,3,6,'mcyukkuri:exam_hide_box',{'facing':'west'})
    mark(b,30,5,'promise'); mark(b,31,5,'promise')
    for z in (5,6): stairs(b,24,3,z,'birch','east')
    b.sign(33,3,5,['答题台','考生站上台面','考官在对面桌后'],'8')
    # Mock living room (7x5 interior) with glass walls, window observation spot and entrance door.
    pen_rect(b,29,14,35,18); b.door(30,3,13)
    b.bowl(29,14); b.bowl(35,14); b.block(35,3,18,'mcyukkuri:yukkuri_toilet')
    for x in (29,31,33): b.block(x,3,18,'mcyukkuri:yukkuri_nest')
    mark(b,32,16,'room'); mark(b,33,12,'examiner')
    register_pen(b,'mock_room',(29,14,35,18),(29,14),[(29,18),(31,18),(33,18)],(5,5))
    # Trainee pen (west of the mock room, gate in the shared glass wall).
    glass(b,(24,13),(28,13)); glass(b,(24,19),(28,19)); b.door(28,3,16,'east')
    b.bowl(25,14); b.block(24,3,18,'mcyukkuri:yukkuri_nest'); b.block(26,3,18,'mcyukkuri:yukkuri_nest')
    for x in (25,26,27): mark(b,x,16,'trainee')
    for x,kind in [(25.5,'marisa'),(26.5,'alice'),(27.5,'reimu')]: b.pet(x,16.5,kind,'shop_examiner','trainee',1,55)
    register_pen(b,'trainee',(24,14,27,18),(25,14),[(24,18),(26,18)],(3,3))
    b.sign(26,3,12,['陪考生','正在受训','请勿喂食'],'8')
    # Gold booth: two 3x3 cells split by a glass wall. Candidate (gold mark) and actor (wild-role yukkuri).
    pen_rect(b,29,23,35,25); glass(b,(32,23),(32,25)); b.door(30,3,22); b.door(34,3,22)
    mark(b,30,24,'gold'); mark(b,34,24,'actor')
    b.bowl(33,25); b.block(35,3,25,'mcyukkuri:yukkuri_nest'); b.pet(34.5,24.5,'reimu','shop_examiner','exam_actor',2,30)
    register_pen(b,'actor',(33,23,35,25),(33,25),[(35,25)],(3,3))
    b.sign(31,3,21,['金章考核间','左：考生席','右：野良役'],'8')
    # Records corner at the south end.
    b.block(30,3,28,'lectern',{'facing':'north','has_book':'false','powered':'false'}); barrel(b,36,3,28); barrel(b,37,3,28); stairs(b,26,3,28,'birch','east'); stairs(b,27,3,28,'birch','west')
    # Rugs, plants and shelves keep the rooms warm and lived-in.
    for x,z in [(24,10),(25,4),(37,4),(37,12),(24,22),(27,27),(22,8),(2,10),(22,16)]: b.block(x,3,z,'potted_azalea_bush' if (x+z)%2 else 'potted_fern')
    carpet(b,11,4,13,5,'red'); carpet(b,18,13,22,16,'light_blue'); carpet(b,2,13,7,14,'orange'); carpet(b,25,5,29,10,'green'); carpet(b,33,8,35,9,'red')
    for z in (28,29): b.box((38,3,z),(38,4,z),'bookshelf')
    # ---- Lighting: hanging lanterns on a 5x5 grid per room; greedy completion until every covered walkable cell has light >= 8.
    pen_cells=set()
    for p in b.pens:
        x0,z0,x1,z1=p['interior']
        for x in range(x0,x1+1):
            for z in range(z0,z1+1): pen_cells.add((x,z))
    def hang(x,z):
        for y in range(5,9):
            n=name_at(b,x,y,z)
            if n!='minecraft:air': break
        else: return False
        if y<=5 or n in ('minecraft:glass','minecraft:glass_pane','minecraft:lantern') or n.endswith(('door','_leaves','_banner','_sign')) or n.startswith('mcyukkuri:'): return False
        b.block(x,y-1,z,'lantern',{'hanging':'true','waterlogged':'false'}); return True   # right under the ceiling or awning, no chain
    order=sorted(((abs(dx)+abs(dz),dx,dz) for dx in range(-2,3) for dz in range(-2,3)))
    for gx in range(3,38,4):
        for gz in range(5,30,4):
            for pass_ in (0,1):
                if any(hang(gx+dx,gz+dz) for _,dx,dz in order if pass_==1 or (gx+dx,gz+dz) not in pen_cells): break
    for _ in range(400):
        level=light_levels(b); dark=[(level.get((x,3,z),0),(x,z)) for x,z in walk_cells(b)]
        dark=[t for t in dark if t[0]<8]
        if not dark: break
        dark.sort(); x,z=dark[0][1]
        placed=False
        for pass_ in (0,1):
            for _,dx,dz in order:
                if pass_==0 and (x+dx,z+dz) in pen_cells: continue
                if hang(x+dx,z+dz): placed=True; break
            if placed: break
        if not placed: break
    connect_panes(b)
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
