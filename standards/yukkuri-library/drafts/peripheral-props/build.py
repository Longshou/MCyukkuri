"""Author editable peripheral art candidates. Does not replace Minecraft resources.
Run from any cwd with Python + Pillow. 16 units = one Minecraft block.
"""
from pathlib import Path
import base64, copy, hashlib, io, json, math, random, sys, uuid
from PIL import Image, ImageDraw

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[3]
LIB=ROOT/'standards/yukkuri-library'
sys.path.insert(0,str(LIB))
sys.path.insert(0,str(LIB/'ecology/house-yukkuri'))
from mesh_geometry import vertices as element_vertices
OUT=HERE/'assembled'; OUT.mkdir(parents=True,exist_ok=True)
PRE=HERE/'previews/current'; PRE.mkdir(parents=True,exist_ok=True)
REPORT=HERE/'reports/current'; REPORT.mkdir(parents=True,exist_ok=True)
TAU=math.tau
def write(p,d): p.write_text(json.dumps(d,ensure_ascii=False,separators=(',',':')),encoding='utf-8')
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
def uri(im):
    b=io.BytesIO();im.save(b,format='PNG');return 'data:image/png;base64,'+base64.b64encode(b.getvalue()).decode()
PALETTE={
 'ceramic':'e9e3cd','glaze':'638a80','glaze_dark':'365d57','grain':'be8544','grain_light':'d9ac64',
 'straw':'bba574','straw_light':'d2c18d','straw_dark':'8a784f','sand':'d9c497','wood':'95704b',
 'wood_light':'b49568','wood_dark':'604b37','cotton':'d8d5c5','cotton_light':'eeecdf','cotton_dark':'abaea0',
 'waste':'362039','waste_light':'4d304d','filling':'623941','filling_light':'855158','cloth':'ddd6be',
 'cloth_light':'f2ecda','cloth_seam':'aaa48e','copper':'b78045','copper_light':'d4a467','black':'313033',
 'yarn':'a95469','yarn_light':'ce8292','cord':'bba680','leaf':'577250','leaf_light':'859765',
 'leaf_dark':'3e543c','red':'ad4b4e','paper':'dfd4b7','soil':'775940','soil_light':'937457',
 'shell':'eee5c9','shell_shade':'d3c49f','green':'6f914f','green_light':'9baa69','stem_dry':'8d7049',
 'skin':'e5c598','wound':'774a43','ink':'45433b','cover':'506c65',
 'steel':'b8c4c5','steel_light':'dce3e1','steel_dark':'899c9d','rubber':'454d4a',
 'bag_regular':'598374','bag_budget':'ba9c64','bag_premium':'443e55','orange':'e5983f',
 'orange_light':'f4c26a','medicine':'d6e0d9','medicine_cap':'466d82','trash':'948b73',
 'trash_paper':'c5bca4','premium_meat':'a66140'}
NAMES=list(PALETTE)
atlas=Image.new('RGBA',(256,256)); dr=ImageDraw.Draw(atlas);rng=random.Random(914)
for idx,(name,h) in enumerate(PALETTE.items()):
    ox=(idx%8)*32;oy=(idx//8)*32;rgb=tuple(bytes.fromhex(h))
    for y in range(32):
        for x in range(32):
            delta=rng.choice([-5,-2,0,0,0,2,4])
            if name.startswith(('wood','straw')):delta+=3 if y%6==0 else 0
            if name.startswith(('cloth','cotton')):delta+=2 if (x+y)%4==0 else 0
            dr.point((ox+x,oy+y),fill=tuple(max(0,min(255,c+delta)) for c in rgb)+(255,))
atlas.save(HERE/'palette.png')

class Model:
    def __init__(self,id,label,category,note,state='常态'):
        self.id=id;self.label=label;self.category=category;self.note=note;self.state=state
        self.parts=[];self.texture=atlas;self.sources=[];self.controls={};self.family=None
    def mesh(self,name,points,faces,mat):
        idx=NAMES.index(mat);u=(idx%8)*32+2;v=(idx//8)*32+2
        fs={}
        for n,f in enumerate(faces):
            for j in range(1,len(f)-1):
                t=[f[0],f[j],f[j+1]]
                ps=[points[k] for k in t]
                a=[ps[1][i]-ps[0][i] for i in range(3)];b=[ps[2][i]-ps[0][i] for i in range(3)]
                if sum(x*x for x in [a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]])<1e-14:continue
                fs[str(len(fs))]={'vertices':list(map(str,t)),'uv':{str(k):uv for k,uv in zip(t,[[u,v],[u+27,v],[u+14,v+27]])},'texture':0}
        self.parts.append(dict(name=name,type='mesh',origin=[0,0,0],rotation=[0,0,0],vertices={str(i):list(p) for i,p in enumerate(points)},faces=fs))
    def box(self,name,c,s,mat,rz=0,ry=0):
        pts=[]
        for x,y,z in [(-1,-1,-1),(1,-1,-1),(1,1,-1),(-1,1,-1),(-1,-1,1),(1,-1,1),(1,1,1),(-1,1,1)]:
            x,y,z=x*s[0]/2,y*s[1]/2,z*s[2]/2
            x,y=x*math.cos(rz)-y*math.sin(rz),x*math.sin(rz)+y*math.cos(rz)
            x,z=x*math.cos(ry)+z*math.sin(ry),-x*math.sin(ry)+z*math.cos(ry)
            pts.append([x+c[0],y+c[1],z+c[2]])
        self.mesh(name,pts,[[0,3,2,1],[4,5,6,7],[0,1,5,4],[3,7,6,2],[0,4,7,3],[1,2,6,5]],mat)
    def lathe(self,name,profile,mat,c=(0,0,0),n=20,sz=1):
        # Ordered from bottom center up exterior and back down interior to center.
        pts=[]
        for row in profile:
            r,y,*off=row;dx=off[0] if off else 0
            pts.extend([[c[0]+dx+r*math.cos(i*TAU/n),c[1]+y,c[2]+r*math.sin(i*TAU/n)*sz] for i in range(n)])
        faces=[]
        for k in range(len(profile)-1):
            for i in range(n):
                a=k*n+i;b=k*n+(i+1)%n;faces.append([a,a+n,b+n,b])
        self.mesh(name,pts,faces,mat)
    def ellipsoid(self,name,c,s,mat,n=12,rings=7):
        p=[(0,-s[1])]+[(s[0]*math.sin(j*math.pi/rings),-s[1]*math.cos(j*math.pi/rings)) for j in range(1,rings)]+[(0,s[1])]
        self.lathe(name,p,mat,c,n,s[2]/s[0])
    def tube(self,name,path,r,mat,sides=6):
        pts=[];last_u=None
        for i,p in enumerate(path):
            a=path[max(0,i-1)];b=path[min(len(path)-1,i+1)];t=[b[k]-a[k] for k in range(3)];le=math.sqrt(sum(x*x for x in t));t=[x/le for x in t]
            if last_u is None:
                ref=[0,1,0] if abs(t[1])<.9 else [1,0,0]
                u=[t[1]*ref[2]-t[2]*ref[1],t[2]*ref[0]-t[0]*ref[2],t[0]*ref[1]-t[1]*ref[0]]
            else:
                dot=sum(last_u[k]*t[k] for k in range(3));u=[last_u[k]-dot*t[k] for k in range(3)]
            le=math.sqrt(sum(x*x for x in u));u=[x/le for x in u];last_u=u
            v=[t[1]*u[2]-t[2]*u[1],t[2]*u[0]-t[0]*u[2],t[0]*u[1]-t[1]*u[0]]
            rr=r[i] if isinstance(r,list) else r
            pts.extend([[p[k]+rr*(u[k]*math.cos(j*TAU/sides)+v[k]*math.sin(j*TAU/sides)) for k in range(3)] for j in range(sides)])
        fs=[]
        for i in range(len(path)-1):
            for j in range(sides):a=i*sides+j;b=i*sides+(j+1)%sides;fs.append([a,b,b+sides,a+sides])
        fs.extend([list(reversed(range(sides))),list(range((len(path)-1)*sides,len(path)*sides))]);self.mesh(name,pts,fs,mat)
    def ring(self,name,rx,rz,y,r,mat,c=(0,0,0),n=32):
        self.tube(name,[[c[0]+rx*math.cos(i*TAU/n),c[1]+y,c[2]+rz*math.sin(i*TAU/n)] for i in range(n+1)],r,mat)
    def finish(self):
        # Center at horizontal bounds and ground the authored object.
        vs=[p for e in self.parts for p in e['vertices'].values()]
        lo=[min(p[k] for p in vs) for k in range(3)];hi=[max(p[k] for p in vs) for k in range(3)]
        shift=[0,0,0] if getattr(self,'preserve_origin',False) else [(lo[0]+hi[0])/2,lo[1],(lo[2]+hi[2])/2]
        for e in self.parts:
            e['vertices']={k:[round(p[i]-shift[i],6) for i in range(3)] for k,p in e['vertices'].items()}
        self.dims=[round(hi[i]-lo[i],4) for i in range(3)]
        return self

models=[]
def add(id,label,category,note,state='常态'):
    m=Model(id,label,category,note,state);models.append(m);return m
def grain(m,c,s=1,i=0):m.ellipsoid('grain_'+str(i),c,[.5*s,.32*s,.35*s],'grain_light' if i%3==0 else 'grain',8,5)
def bowl(m,filled=0):
    m.lathe('ceramic_bottom',[(0,0),(4.9,0)],'ceramic',n=24,sz=.82)
    m.lathe('glazed_foot',[(4.9,0),(5.2,.3)],'glaze_dark',n=24,sz=.82)
    m.lathe('ceramic_outer',[(5.2,.3),(6.5,1.55)],'ceramic',n=24,sz=.82)
    m.lathe('glazed_lip',[(6.5,1.55),(6.65,1.85),(6.4,2.05),(6.05,1.8),(5.87,1.58)],'glaze',n=24,sz=.82)
    m.lathe('ceramic_inner',[(5.87,1.58),(5,.6),(0,.6)],'ceramic',n=24,sz=.82)
    for i in range(filled):
        a=i*2.39996;r=math.sqrt((i+.5)/max(1,filled))*4.4;grain(m,[r*math.cos(a),.9+.25*(1-r/5),r*math.sin(a)*.82],1,i)
for id,label,count in [('food_bowl','食盆',0),('food_bowl_full','食盆 · 满粮',62),('food_bowl_low','食盆 · 少量',10)]:
    m=add(id,label,'block','浅陶盆、釉边和低入口；沿用现有浅盆用途，重做连续盆壁。','空盆' if not count else '满粮' if count>10 else '少量');bowl(m,count)

m=add('yukkuri_nest','草窝','block','椭圆凹窝，编草围边和交错垫草。')
m.lathe('woven_bed',[(0,0),(5.9,0),(6.8,.4),(6.5,.8),(0,.8)],'straw_dark',sz=.82)
for i in range(3):m.ring('woven_rim_'+str(i),6.15-i*.22,5.1-i*.16,.6+i*.48,.35,'straw' if i%2 else 'straw_light')
for i in range(42):
    a=i*2.39996;r=math.sqrt((i+.5)/42)*4.9;x=r*math.cos(a);z=r*math.sin(a)*.8
    m.tube('bedding_'+str(i),[[x-.6,.87,z-.3],[x,1,z],[x+.65,.88,z+.35]],.095,'straw_light' if i%3 else 'straw',5)
for i in range(24):
    a=i*TAU/24;m.tube('rim_lashing_'+str(i),[[r*math.cos(a),y,r*.83*math.sin(a)] for r,y in [(6.2,.25),(6.7,.85),(6.2,1.65),(5.55,1.3)]],.1,'straw_dark',5)

for dirty in [False,True]:
    m=add('yukkuri_toilet'+('_dirty' if dirty else ''),'沙厕'+(' · 待清理' if dirty else ''),'block','浅釉盆与内凹沙面，前侧降低便于进入。','脏污' if dirty else '干净')
    m.box('tray_floor',[0,.25,0],[11.7,.5,9.6],'glaze')
    for x in [-5.7,5.7]:m.box('side',[x,1.3,0],[.65,2.1,9.6],'glaze')
    m.box('back',[0,1.3,4.5],[11.4,2.1,.65],'glaze');m.box('low_entry',[0,.7,-4.5],[11.4,.9,.65],'glaze')
    m.box('sand',[0,.55,0],[10.5,.26,8.1],'sand')
    for i in range(10):m.box('sand_rake_'+str(i),[-4+i*.88,.7,.2],[.055,.035,5.8],'grain_light')
    if dirty:
        m.lathe('waste',[(0,0),(.9,0),(1.25,.25),(1,.7),(.35,.95),(.1,1.2,.2),(0,1.3,.25)],'waste',c=(1,.7,.2),n=12,sz=.7)

m=add('house_crevice','家油缝隙','block','家具踢脚板后的低入口，内部有底板和棉絮；观察用单格切段。')
m.box('floor',[0,.2,1],[15,.4,13],'wood_dark');m.box('back',[0,5,6.7],[15,9.6,.6],'wood')
m.box('left_jamb',[-6,4.5,0],[3,9,13],'wood');m.box('right_jamb',[6,4.5,0],[3,9,13],'wood')
m.box('lintel',[0,7.9,0],[9,2.2,13],'wood');m.box('front_trim',[0,9.1,-6.1],[15,.45,.6],'wood_light')
for x in [-6,6]:
    for yy in [1.1,4,6.9]:m.box('grain_mark',[x,yy,-6.53],[2.45,.07,.035],'wood_dark')
for i in range(9):
    a=i*TAU/9;m.ellipsoid('cotton_'+str(i),[2.5*math.cos(a),.7,2+1.9*math.sin(a)],[.85,.4,.7],'cotton',8,5)

m=add('burial_mound','埋葬土堆','block','低矮覆土、少量土块和落叶；专用泥土材质。')
m.lathe('soil_mound',[(0,0),(6.8,0),(6.5,.5),(5.4,1.4),(3.3,2.25),(0,2.5)],'soil',n=16,sz=.74)
for i in range(13):
    a=i*2.4;r=3.8+i%3*.8;m.ellipsoid('clod_'+str(i),[r*math.cos(a),.55+(5-r)*.3,r*.7*math.sin(a)],[.5,.22,.36],'soil_light',7,4)
m.box('fallen_leaf',[1.1,2.4,.2],[1.4,.12,.6],'straw',ry=.6)

m=add('oil_grain','油粮','item','独立颗粒份量，浅金与烘烤褐色颗粒；配套盆内显示。')
for i in range(19):
    a=i*2.4;r=math.sqrt((i+.5)/19)*2.4;grain(m,[r*math.cos(a),.38+.35*(1-r/2.4),r*math.sin(a)*.75],1.25,i)
m=add('food_scrap','食物残渣','item','不规则碎屑，浅色内部和烤色外皮，区别于完整油粮。')
for i,(x,z,s) in enumerate([(-1.2,0,.85),(.6,.1,1),(1.7,-.8,.4),(-.2,-1.1,.35),(-1.5,-1,.25)]):
    m.ellipsoid('crumb_'+str(i),[x,.48*s,z],[s,.5*s,.68*s],'grain_light',7,4)
    m.box('crust_'+str(i),[x,.78*s,z],[1.1*s,.16*s,.8*s],'grain',rz=.15,ry=i)
m=add('yukkuri_waste','排泄物','item','依据八景 U9.8 原始图层：低矮深紫褐团块，尾端略翘。')
m.lathe('continuous_waste',[(0,0),(1.7,0),(2.5,.35),(2.35,.8),(1.5,1.1),(.65,1.25,.4),(.18,1.65,1),(0,1.95,1.2)],'waste',n=18,sz=.64)
m=add('leaked_filling','漏出馅料','item','松散豆沙与少量碎团；和完整排泄团的轮廓分开。')
m.ellipsoid('spread',[0,.18,0],[2.4,.18,1.4],'filling',14,5)
for i in range(11):
    a=i*2.4;r=(i%4)*.48;m.ellipsoid('paste_'+str(i),[r*math.cos(a),.35+i%3*.14,r*.7*math.sin(a)],[.65,.42,.5],'filling_light' if i%3==0 else 'filling',8,5)

m=add('yukkuri_carrier','携带篮','item','编织篮、拱形提手和软衬垫；内腔保持开放。')
m.lathe('elliptic_base',[(0,0),(4.9,0),(5.25,.22),(5.3,.55),(0,.55)],'wood_dark',n=24,sz=4/5.2)
for j in range(8):m.ring('weave_'+str(j),5.2+j*.04,4+j*.035,.7+j*.48,.22,'wood_light' if j%2 else 'wood',n=24)
for i in range(20):
    a=i*TAU/20;m.tube('upright_'+str(i),[[5.15*math.cos(a),.35,4*math.sin(a)],[5.52*math.cos(a),4.4,4.25*math.sin(a)]],.17,'wood_dark')
m.ring('basket_lip',5.52,4.25,4.3,.27,'wood_light')
m.tube('handle',[[5.3*math.cos(i*math.pi/24),4.2+6*math.sin(i*math.pi/24),0] for i in range(25)],.32,'wood_light',8)
m.ellipsoid('soft_lining',[0,.7,0],[4.6,.3,3.4],'cloth',16,5)

m=add('wash_cloth','清洗布','item','折叠布片、翻角和织线边。')
for i in range(3):m.box('fold_'+str(i),[i*.06,.18+i*.23,0],[5.4,.3,3.8],'cloth_light' if i==2 else 'cloth',ry=i*.025)
m.box('turned_corner',[1.95,.91,-1.25],[1.25,.15,1.1],'cloth',rz=.17,ry=.25)
for z in [-1.65,1.65]:m.box('hem',[0,.795,z],[5,.025,.09],'cloth_seam')
for i in range(10):m.box('stitch_'+str(i),[-2.2+i*.48,.81,-1.64],[.07,.027,.18],'cloth_light')

m=add('teaching_whistle','教导哨','item','圆鼓哨腔、扁吹口和系绳环。')
m.ellipsoid('chamber',[0,1.05,0],[1.6,1,1.1],'copper',16,7)
m.box('mouthpiece',[0,.8,-1.7],[1.4,.7,1.8],'copper_light')
m.box('air_slot',[0,.82,-2.615],[.83,.21,.04],'black')
m.box('sound_window',[0,1.96,-.1],[.75,.06,.45],'black')
m.ring('lanyard_eye',.43,.43,.4,.13,'copper',c=(0,0,1.4),n=16)

m=add('yarn_ball','毛线球','item','圆线团、交错绕线和短线尾。')
m.ellipsoid('core',[0,2.05,0],[2,2,1.9],'yarn',18,10)
for i in range(11):
    a=i*math.pi/7;tilt=.35+(i%4)*.25;path=[]
    for j in range(49):
        t=j*TAU/48;x=2.04*math.cos(t);y=2.04*math.sin(t);z=y*math.sin(tilt);y=y*math.cos(tilt)
        path.append([x*math.cos(a)+z*math.sin(a),2.05+y,(-x*math.sin(a)+z*math.cos(a))*.95])
    m.tube('winding_'+str(i),path,.075,'yarn_light' if i%3==0 else 'yarn',5)
m.tube('loose_end',[[1.1,.75,.35],[1.6,.5,.5],[2.1,.2,1],[2.8,.13,1.4],[3.4,.13,1],[3.1,.13,.5]],.1,'yarn_light')

m=add('companion_cord','结伴绳','item','两个连结绳圈，中间打结并露出绳尾。')
for x in [-1.4,1.4]:m.ring('paired_loop',1.6,1.25,.25,.15,'cord',c=(x,0,0),n=24)
m.ellipsoid('knot',[0,.35,0],[.65,.35,.42],'cord',10,5)
for x in [-1,1]:m.tube('end',[[0,.2,0],[x*.6,.17,-1.2],[x*1.1,.15,-2]],.14,'cord')

m=add('bandage','绷带','item','空心布卷和展开带尾；不用纸图标占位。')
m.lathe('roll',[(.6,0),(1.65,0),(1.7,.1),(1.7,2),(1.6,2.15),(.65,2.15),(.6,2),(.6,0)],'cloth_light',n=24)
for y in [.25,.65,1.05,1.45,1.85]:m.ring('woven_edge',1.707,1.707,y,.023,'cloth_seam',n=24)
m.box('unrolled',[2.5,.075,.15],[3,.15,1.8],'cloth')
m.box('hem',[3.7,.16,.15],[.07,.025,1.7],'cloth_seam')

def leaf(m,name,a,y,length=4):
    def rot(p):x,y,z=p;return [x*math.cos(a)+z*math.sin(a),y,-x*math.sin(a)+z*math.cos(a)]
    p=[rot(v) for v in [[0,1.1,.12],[-.52,1.1+length*.4,.68],[0,1.1+length*.8,1.25],[.52,1.1+length*.4,.68],[0,1.1+length*.4,.5]]]
    m.mesh(name,p,[[0,4,1],[1,4,2],[2,4,3],[3,4,0],[1,2,3,0]],'leaf_light' if int(a*10)%2 else 'leaf')
    m.tube(name+'_petiole',[rot([0,.15,.03]),p[0],p[4]],.055,'leaf_dark',5)
    m.tube(name+'_vein',[p[0],p[4],p[2]],.025,'leaf_dark',4)
m=add('herbal_medicine','草药束','item','束起的叶片与系带，叶形和配色为本次适配设计。')
for i in range(6):leaf(m,'leaf_'+str(i),i*TAU/6,.4,3.3+i%3*.45)
m.ring('binding',.45,.45,1,.13,'cord',n=16)
m.box('label',[.5,1,-.5],[.6,.95,.09],'paper',rz=-.2)

m=add('field_notebook','观察手册','item','绿布封面、独立书脊、纸页、系扣和油库里脸形标记。')
m.box('pages',[0,.52,0],[4.4,.72,5.8],'paper')
for y in [.1,.95]:m.box('cover',[0,y,0],[4.9,.2,6.2],'cover')
m.box('spine',[-2.3,.53,0],[.28,.8,6.2],'cover')
for y in [.28,.43,.58,.73]:m.box('page_line',[2.215,y,0],[.02,.025,5.65],'cloth_seam')
m.box('strap',[1.5,1.09,0],[.62,.1,6.15],'wood')
m.ellipsoid('face_emblem',[-.5,1.12,-.25],[1,.025,.78],'paper',16,6)
for x in [-.86,-.2]:m.box('emblem_eye',[x,1.15,-.35],[.12,.025,.12],'ink')
m.tube('emblem_smile',[[-.76,1.15,.04],[-.52,1.15,.2],[-.26,1.15,.04]],.035,'ink',4)

m=add('nest_lining','棉絮巢垫','attachment','家油缝隙内部附属物，松软纤维围出凹窝；非独立方块。')
for i in range(20):
    a=i*TAU/20;m.ellipsoid('tuft_'+str(i),[3*math.cos(a),.5+i%3*.08,2.3*math.sin(a)],[.78,.5,.62],'cotton_light' if i%3 else 'cotton',9,5)
m.ellipsoid('base',[0,.25,0],[2.8,.25,2.15],'cotton_dark',16,5)
for i in range(12):
    a=i*2.4;r=1.8;m.tube('fiber_'+str(i),[[r*math.cos(a),.53,r*.7*math.sin(a)],[(r+.5)*math.cos(a+.15),.59,(r+.5)*.7*math.sin(a+.15)]],.04,'cotton_light',4)

for cracked in [False,True]:
    m=add('house_egg'+('_cracked' if cracked else ''),'家油卵'+(' · 裂壳' if cracked else ''),'attachment','圆润略扁的浅色卵，沿用家油参考方向；三维壳厚为适配设计。','裂壳' if cracked else '完整')
    m.lathe('shell',[(0,0),(.43,.03),(.6,.2),(.64,.38),(.54,.6),(.3,.77),(0,.82)],'shell',n=16,sz=.86)
    if cracked:
        profile=[(0,0),(.43,.03),(.6,.2),(.64,.38),(.54,.6),(.3,.77),(0,.82)]
        def shell_point(x,y):
            r=next(ra+(rb-ra)*(y-ya)/(yb-ya) for (ra,ya),(rb,yb) in zip(profile,profile[1:]) if ya<=y<=yb)
            ring=[[r*math.cos(i*TAU/16),r*math.sin(i*TAU/16)*.86] for i in range(8,17)]
            z=next(za+(zb-za)*(x-xa)/(xb-xa) for (xa,za),(xb,zb) in zip(ring,ring[1:]) if xa<=x<=xb)
            return [x,y,z-.002]
        path=[[-.37,.51],[-.18,.6],[-.02,.51],[.15,.59],[.3,.47],[.43,.52]];ps=[];fs=[]
        for a,b in zip(path,path[1:]):
            dx=b[0]-a[0];dy=b[1]-a[1];le=math.hypot(dx,dy);ox=dy/le*.007;oy=-dx/le*.007
            for j in range(6):
                x=a[0]+dx*j/6;y=a[1]+dy*j/6;X=a[0]+dx*(j+1)/6;Y=a[1]+dy*(j+1)/6
                start=len(ps);q=[shell_point(x-ox,y-oy),shell_point(X-ox,Y-oy),shell_point(X+ox,Y+oy),shell_point(x+ox,y+oy)]
                ps+=q;f=list(range(start,start+4));cross=(q[1][0]-q[0][0])*(q[2][1]-q[0][1])-(q[1][1]-q[0][1])*(q[2][0]-q[0][0])
                if cross>0:f.reverse()
                fs.append(f)
        m.mesh('surface_crack',ps,fs,'shell_shade')

for dry in [False,True]:
    m=add('spent_stem' if dry else 'birth_stem','枯茎' if dry else '繁殖茎','attachment','独立茎材质候选；角色挂接和胎数布局仍使用家庭配方。')
    path=[[.8*math.sin(t*math.pi),t*12,2.2*math.sin(t*math.pi/2)] for t in [i/24 for i in range(25)]]
    m.tube('stem',path,[.27-.12*i/24 for i in range(25)],'stem_dry' if dry else 'green',8)
    if not dry:
        m.tube('top_support',[[0,12,2.2],[1.4,12.6,2.2],[2.7,12.3,2.2],[3.4,11.5,2.2]],.13,'green_light',6)

m=add('wound_patch','伤口与馅料贴面','attachment','薄曲面伤口与小馅料团；展示为局部部件，接入时需贴合身体。')
m.ellipsoid('tear',[0,.14,0],[1.8,.14,.8],'wound',12,5)
for i in range(5):m.ellipsoid('leak_'+str(i),[-.9+i*.45,.24,-.1],[.35,.22,.24],'filling',8,5)
m=add('body_bandage','身体绷带','attachment','有厚度的弧形布带及缝边；接入时按身体表面适配挂点。')
path=[[x,.8-.08*x*x,0] for x in [-2,-1.5,-1,-.5,0,.5,1,1.5,2]]
pts=[]
for x,y,z in path:pts.extend([[x,y,-.75],[x,y,.75],[x,y-.1,-.75],[x,y-.1,.75]])
faces=[]
for i in range(len(path)-1):
    a=i*4;b=a+4;faces.extend([[a,b,b+1,a+1],[a+2,a+3,b+3,b+2],[a,a+2,b+2,b],[a+1,b+1,b+3,a+3]])
faces.extend([[0,1,3,2],[32,34,35,33]]);m.mesh('curved_bandage',pts,faces,'cloth_light')
for z in [-.62,.62]:m.tube('hem',[[x,y+.025,z] for x,y,_ in path],.025,'cloth_seam',4)

def import_triangles(m,verts,texture,source,transform=lambda p:p):
    points=[];faces={}
    for j in range(0,len(verts),24):
        keys=[];uv={}
        for k in range(3):
            v=verts[j+k*8:j+k*8+8];key=str(len(points));keys.append(key);points.append(transform(v[:3]));uv[key]=[v[3]*texture.width,v[4]*texture.height]
        # Runtime faces can carry authored normals opposite their vertex winding.
        a,b,c=points[-3:];ab=[b[k]-a[k] for k in range(3)];ac=[c[k]-a[k] for k in range(3)]
        n=[ab[1]*ac[2]-ab[2]*ac[1],ab[2]*ac[0]-ab[0]*ac[2],ab[0]*ac[1]-ab[1]*ac[0]]
        orig=verts[j+5:j+8]
        if sum(n[k]*orig[k] for k in range(3))<0:keys.reverse()
        faces[str(len(faces))]=dict(vertices=keys,uv=uv,texture=0)
    m.parts=[dict(name=m.id,type='mesh',origin=[0,0,0],rotation=[0,0,0],vertices={str(i):p for i,p in enumerate(points)},faces=faces)]
    m.texture=texture;m.sources=[dict(path=source.relative_to(ROOT).as_posix(),sha256=sha(source))]

for species,id,label,prefixes in [('reimu','reimu_ribbon','脱落蝴蝶结',('bow_',)),('marisa','marisa_hat','脱落魔理沙帽',('brim_','hat_','bow_'))]:
    source=ROOT/f'art/source/standard-runtime/{species}/runtime.model.json';data=json.loads(source.read_text(encoding='utf-8'))
    m=add(id,label,'item','从当前角色接入模型的对应饰物提取，保持佩戴与掉落时的几何和贴图一致。')
    verts=[]
    for part in data['parts']:
        if part['name'].startswith(prefixes):verts+=data['vertices'][part['start']*8:(part['start']+part['count'])*8]
    if not verts:raise ValueError('No accessory '+id)
    tex_path=source.parent/'textures/neutral.png';tex=Image.open(tex_path).convert('RGBA');import_triangles(m,verts,tex,source)
    m.sources.append(dict(path=tex_path.relative_to(ROOT).as_posix(),sha256=sha(tex_path)))

source=ROOT/'art/source/standard-runtime/reimu/runtime.model.json';data=json.loads(source.read_text(encoding='utf-8'));verts=[]
for part in data['parts']:
    if not part.get('anatomy'):verts+=data['vertices'][part['start']*8:(part['start']+part['count'])*8]
m=add('yukkuri_remains','油库里遗体','item','当前灵梦模型的侧卧静态候选，闭眼、无呼吸；保留可识别轮廓，魔理沙外观另需对应派生。')
tex=Image.open(source.parent/'textures/sleep.png').convert('RGBA')
import_triangles(m,verts,tex,source)
m.sources.append(dict(path=(source.parent/'textures/sleep.png').relative_to(ROOT).as_posix(),sha256=sha(source.parent/'textures/sleep.png')))
# Rotate the complete mesh after winding conversion so normals follow geometry.
for e in m.parts:
    for k,p in e['vertices'].items():e['vertices'][k]=[p[1],-p[0]*.82,p[2]]

from revision2 import revise
revise(globals())

egg_approval_path=LIB/'ecology/house-egg/approval.json'
egg_approval=json.loads(egg_approval_path.read_text(encoding='utf-8')) if egg_approval_path.exists() else {}
def export_model(m,folder,bb_relative):
    m.finish();folder.mkdir(parents=True,exist_ok=True)
    im=m.texture;im.save(folder/'texture.png');elements=copy.deepcopy(m.parts);flat=[];parts=[]
    for i,e in enumerate(elements):
        e['uuid']=str(uuid.uuid5(uuid.NAMESPACE_URL,'mcyukkuri/peripheral/'+m.id+'/'+str(i)))
        start=len(flat)//8;flat+=element_vertices(e,im.width)
        part=dict(name=e['name'],start=start,count=len(flat)//8-start,unit=e.get('unit',0))
        if 'motion' in e:part['motion']=e['motion']
        parts.append(part)
    bb=dict(meta=dict(format_version='4.10',model_format='free',box_uv=False),name=m.id,resolution=dict(width=im.width,height=im.height),elements=elements,outliner=[dict(name='root',uuid=str(uuid.uuid5(uuid.NAMESPACE_URL,m.id)),origin=[0,0,0],children=[e['uuid'] for e in elements])],textures=[dict(path='texture.png',name='texture.png',id='0',uuid=str(uuid.uuid5(uuid.NAMESPACE_URL,m.id+'/texture')),source=uri(im),mode='bitmap',width=im.width,height=im.height,uv_width=im.width,uv_height=im.height)],animations=[])
    write(folder/(m.id+'.bbmodel'),bb)
    mesh=dict(id=m.id,units_per_block=16,vertices=[round(x,6) for x in flat],texture_size=im.width,parts=parts)
    write(folder/(m.id+'.mesh.json'),mesh)
    # Portable Wavefront geometry; mesh JSON is preview interchange, not a Java block model.
    obj=['mtllib '+m.id+'.mtl','o '+m.id,'usemtl surface']
    for i in range(0,len(flat),8):obj.append('v '+' '.join(str(round(x,6)) for x in flat[i:i+3]))
    for i in range(0,len(flat),8):obj.append('vt '+str(flat[i+3])+' '+str(1-flat[i+4]))
    for i in range(0,len(flat)//8,3):obj.append('f '+' '.join(f'{j+1}/{j+1}' for j in range(i,i+3)))
    (folder/(m.id+'.obj')).write_text('\n'.join(obj)+'\n',encoding='utf-8')
    (folder/(m.id+'.mtl')).write_text('newmtl surface\nKd 1 1 1\nmap_Kd texture.png\n',encoding='utf-8')
    entry=dict(id=m.id,label=m.label,category=m.category,note=m.note,state=m.state,dims=m.dims,triangles=len(flat)//24,model=mesh,texture=uri(im),source=m.sources,controls=m.controls,family=m.family,bbmodel=bb_relative)
    if getattr(m,'body_effects',None):entry['bodyEffects']=m.body_effects
    approved=egg_approval.get('assets',{}).get(m.id)
    if approved:
        matches=all(sha(folder/name)==approved[key] for name,key in [(m.id+'.bbmodel','bbmodel_sha256'),(m.id+'.mesh.json','mesh_sha256'),('texture.png','texture_sha256')])
        entry['confirmation']=dict(status='user_approved' if matches else 'modified_pending_confirmation',record='../../ecology/house-egg/approval.json',approved_on=egg_approval['approved_on'])
    check=dict(id=m.id,triangles=len(flat)//24,parts=len(elements),dims=m.dims,bbmodel_sha256=sha(folder/(m.id+'.bbmodel')),mesh_sha256=sha(folder/(m.id+'.mesh.json')),texture_sha256=sha(folder/'texture.png'))
    assert len(flat)%24==0 and all(math.isfinite(x) for x in flat)
    assert all(0<=flat[i]<=1 for i in range(len(flat)) if i%8 in (3,4))
    assert all(x>0 for x in m.dims)
    return entry,check

from body_effects import extend_body_effects
BODY_EFFECTS,body_checks=extend_body_effects(globals())
catalog=[];checks=[]
for m in models:
    entry,check=export_model(m,OUT/m.id,f'../../assembled/{m.id}/{m.id}.bbmodel')
    catalog.append(entry);checks.append(check)
import subprocess
subprocess.run(['node',str(HERE/'export-wound-scenes.cjs')],check=True)
wound_exports=json.loads((HERE/'wound-exports.json').read_text(encoding='utf-8'))
catalog.append(wound_exports['entry'])
write(HERE/'catalog.json',[{k:v for k,v in a.items() if k not in ('model','texture')} for a in catalog])
(PRE/'data.js').write_text('const BODY_EFFECTS='+json.dumps(BODY_EFFECTS,ensure_ascii=False,separators=(',',':'))+';\nconst ASSETS='+json.dumps(catalog,ensure_ascii=False,separators=(',',':'))+';\n',encoding='utf-8')
write(REPORT/'exports.json',dict(status='partially_user_approved',revision='3.1',generator_sha256=sha(Path(__file__)),authoring_sources={p:sha(HERE/p) for p in ['build.py','revision2.py','native_forage.py','body_effects.py','body-effects-recipe.json','presentation-rules.json','wound-surface.js','export-wound-scenes.cjs','wound-source.json']},palette_sha256=sha(HERE/'palette.png'),assets=checks,body_variants=body_checks,wound_scenes=wound_exports['checks'],checks=['finite triangle vertices','UVs in texture bounds','nonzero dimensions','source and exports generated together'],not_checked=['Blockbench application import','Minecraft mesh adapter and runtime','user confirmation of remaining candidates']))
print(f'Exported {len(catalog)} catalog entries; '+str({c:sum(a["category"]==c for a in catalog) for c in ['block','item','attachment','body']}))
