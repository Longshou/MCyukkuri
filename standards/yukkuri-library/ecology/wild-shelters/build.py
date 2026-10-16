"""Original hollow shelters; editable meshes, matching collision and selected runtime exports.
Run with Python + Pillow. Coordinate units: 16/block, +Z entrance, ground Y=0.
"""
from pathlib import Path
import base64, hashlib, json, math, random, shutil, uuid, subprocess
from PIL import Image, ImageDraw, ImageFont
HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[3]
OUT=HERE/'assembled'; PRE=ROOT/'.local/previews/wild-shelters/source-views'
RUN=ROOT/'src/main/resources'; SNAP=ROOT/'art/props/wild-shelters'
for p in (OUT,PRE,SNAP,RUN/'assets/mcyukkuri/yukkuri_props',RUN/'textures',RUN/'data/mcyukkuri/shelters'):
    p.mkdir(parents=True,exist_ok=True)
COLORS={'soil':'71563d','soil_light':'897047','wood':'63503b','bark':'97744f','leaf':'62744b','leaf_dry':'9b8959','grass':'ada371','paper':'b28c59','paper_light':'ccb17e','paper_dark':'795d40','tape':'d5bd85','ink':'514536','waste':'493049'}
NAMES=list(COLORS); atlas=Image.new('RGBA',(256,256)); rng=random.Random(931)
for k,h in enumerate(COLORS.values()):
    rgb=bytes.fromhex(h)
    for y in range(64):
        for x in range(64):
            d=rng.choice([-8,-3,0,0,3,6])+(2 if y%9==0 else 0)
            atlas.putpixel(((k%4)*64+x,(k//4)*64+y),tuple(max(0,min(255,c+d)) for c in rgb)+(255,))
def close_enough(before,after):
    if isinstance(before,(int,float)) and isinstance(after,(int,float)):
        return abs(before-after)<=1e-12*max(1,abs(before),abs(after))
    if isinstance(before,list) and isinstance(after,list):
        return len(before)==len(after) and all(close_enough(a,b) for a,b in zip(before,after))
    if isinstance(before,dict) and isinstance(after,dict):
        return before.keys()==after.keys() and all(close_enough(before[key],after[key]) for key in before)
    return before==after
def write(p,d):
    p.parent.mkdir(parents=True,exist_ok=True)
    if p.exists():
        try:
            if close_enough(json.loads(p.read_text(encoding='utf-8')),d):
                return
        except (OSError,json.JSONDecodeError):
            pass
    p.write_text(json.dumps(d,ensure_ascii=False,separators=(',',':')),encoding='utf-8')
def save_image(image,p):
    """Keep byte-stable exports when the authored palette pixels did not change."""
    p.parent.mkdir(parents=True,exist_ok=True)
    if p.exists():
        try:
            with Image.open(p) as current:
                if current.convert('RGBA').size==image.size and current.convert('RGBA').tobytes()==image.tobytes():
                    return
        except OSError:
            pass
    image.save(p)
save_image(atlas,HERE/'palette.png')
PALETTE_BYTES=(HERE/'palette.png').read_bytes()
def sha(p): return hashlib.sha256(p.read_bytes()).hexdigest()
class Model:
    def __init__(self,name): self.name=name;self.meshes=[];self.collision=[]
    def mesh(self,name,points,faces,mat,unit=0):
        self.meshes.append((name,points,faces,mat,unit))
    def box(self,name,lo,hi,mat,solid=False,unit=0):
        x,y,z=lo;X,Y,Z=hi
        self.mesh(name,[(x,y,z),(X,y,z),(X,Y,z),(x,Y,z),(x,y,Z),(X,y,Z),(X,Y,Z),(x,Y,Z)],[(0,3,2,1),(4,5,6,7),(0,1,5,4),(3,7,6,2),(0,4,7,3),(1,2,6,5)],mat,unit)
        if solid:self.collision.append([*lo,*hi])
    def beam(self,name,a,b,r,mat,unit=0):
        t=[b[i]-a[i] for i in range(3)];le=math.sqrt(sum(v*v for v in t));t=[v/le for v in t]
        ref=[0,1,0] if abs(t[1])<.9 else [1,0,0]
        u=[t[1]*ref[2]-t[2]*ref[1],t[2]*ref[0]-t[0]*ref[2],t[0]*ref[1]-t[1]*ref[0]];le=math.sqrt(sum(v*v for v in u));u=[v/le for v in u]
        v=[t[1]*u[2]-t[2]*u[1],t[2]*u[0]-t[0]*u[2],t[0]*u[1]-t[1]*u[0]]
        pts=[[p[k]+r*(u[k]*math.cos(j*math.tau/6)+v[k]*math.sin(j*math.tau/6)) for k in range(3)] for p in (a,b) for j in range(6)]
        self.mesh(name,pts,[(j,(j+1)%6,(j+1)%6+6,j+6) for j in range(6)]+[tuple(range(5,-1,-1)),tuple(range(6,12))],mat,unit)
    def export(self):
        vertices=[];parts=[];elements=[]
        for name,points,faces,mat,unit in self.meshes:
            start=len(vertices)//8;fs={};idx=NAMES.index(mat);u=(idx%4)*64+3;v=(idx//4)*64+3
            for face in faces:
                for j in range(1,len(face)-1):
                    tri=[face[0],face[j],face[j+1]];a,b,c=[points[n] for n in tri]
                    ab=[b[k]-a[k] for k in range(3)];ac=[c[k]-a[k] for k in range(3)]
                    normal=[ab[1]*ac[2]-ab[2]*ac[1],ab[2]*ac[0]-ab[0]*ac[2],ab[0]*ac[1]-ab[1]*ac[0]];le=math.sqrt(sum(x*x for x in normal))
                    if le<1e-8:continue
                    normal=[x/le for x in normal];uvs=[[u,v],[u+57,v],[u+28,v+57]]
                    fs[str(len(fs))]={'vertices':list(map(str,tri)),'uv':{str(n):uv for n,uv in zip(tri,uvs)},'texture':0}
                    for n,uv in zip(tri,uvs):vertices.extend([*points[n],uv[0]/256,uv[1]/256,*normal])
            parts.append({'name':name,'start':start,'count':len(vertices)//8-start,'unit':unit})
            elements.append({'name':name,'type':'mesh','uuid':str(uuid.uuid5(uuid.NAMESPACE_URL,self.name+'/'+name+str(start))),'origin':[0,0,0],'rotation':[0,0,0],'vertices':{str(i):p for i,p in enumerate(points)},'faces':fs})
        bounds=[[min(vertices[k::8]),max(vertices[k::8])] for k in range(3)]
        model={'format':1,'units_per_block':16,'vertices':vertices,'parts':parts,'bounds':bounds}
        write(OUT/(self.name+'.json'),model)
        write(OUT/(self.name+'.bbmodel'),{'meta':{'format_version':'4.10','model_format':'free'},'name':self.name,'resolution':{'width':256,'height':256},'elements':elements,'outliner':[e['uuid'] for e in elements],'textures':[{'name':'palette.png','id':'0','width':256,'height':256,'source':'data:image/png;base64,'+base64.b64encode(PALETTE_BYTES).decode()}]})
        save_image(atlas,OUT/(self.name+'.png'))
        for suffix in ('.json','.bbmodel','.png'):shutil.copy2(OUT/(self.name+suffix),SNAP/(self.name+suffix))
        shutil.copy2(OUT/(self.name+'.json'),RUN/'assets/mcyukkuri/yukkuri_props'/(self.name+'.json'))
        tex=RUN/'assets/mcyukkuri/textures/props';tex.mkdir(parents=True,exist_ok=True);save_image(atlas,tex/(self.name+'.png'))
        if self.collision:write(RUN/'data/mcyukkuri/shelters'/(self.name+'.json'),{'units_per_block':16,'collision':self.collision})
        return model

def align_sleeping_bed(model):
    """Y=0 is the yukkuri foot plane; embed packed bedding below it so the face stays clear."""
    embed=-1.05
    model.meshes=[(name,[[x,y+embed,z] for x,y,z in points],faces,material,unit)
                  for name,points,faces,material,unit in model.meshes]

def leaf(model,name,x,z,y,angle,length,width,material,unit,fluff=0,lean=0):
    """A thick, folded leaf: raised spine, curved lobes and a visible underside."""
    c,s=math.cos(angle),math.sin(angle)
    def at(along,across,height):
        return [x+along*c-across*s,y+height+along*lean,z+along*s+across*c]
    ridge=min(.76,width*.9)+fluff
    rim=[(-length,0,.025),(-.38*length,-width,.055),(.38*length,-.78*width,.09),
         (length,0,.025),(.38*length,.78*width,.09),(-.38*length,width,.055)]
    points=[at(a,b,h) for a,b,h in rim]+[at(-.04*length,0,ridge)]
    underside=len(points);points.extend(at(a,b,h-.16) for a,b,h in rim);points.append(at(-.04*length,0,-.13))
    faces=[]
    for i in range(6):
        nxt=(i+1)%6
        faces.append((i,nxt,6))
        faces.append((underside+nxt,underside+i,underside+6))
        faces.append((i,underside+i,underside+nxt,nxt))
    model.mesh(name,points,faces,material,unit=unit)

def grass_tuft(model,name,x,z,y,unit,spread=.8,height=1.1):
    """Fine, irregular grass strands stop the bedding reading as a tiled sheet."""
    for blade in range(3):
        angle=rng.uniform(0,math.tau);radius=rng.uniform(0,spread)
        bx=x+math.cos(angle)*radius;bz=z+math.sin(angle)*radius
        lean=angle+rng.uniform(-.7,.7)
        model.beam(name+'_blade_'+str(blade),(bx,y,bz),(bx+math.cos(lean)*rng.uniform(.25,.65),y+rng.uniform(height*.55,height),bz+math.sin(lean)*rng.uniform(.25,.65)),rng.uniform(.032,.06),'grass',unit)

def packed_leaf_base(model,name,unit):
    """A low, irregular, fully covering leaf-fibre pad beneath the loose leaves."""
    divisions=8;extent=8.08;top=[]
    for row in range(divisions+1):
        for col in range(divisions+1):
            edge=row in (0,divisions) or col in (0,divisions)
            x=-extent+2*extent*col/divisions
            z=-extent+2*extent*row/divisions
            if not edge:
                x+=rng.uniform(-.28,.28);z+=rng.uniform(-.28,.28)
            top.append((x,.22+rng.uniform(-.065,.065),z))
    bottom=[(x,y-.12,z) for x,y,z in top];points=top+bottom;faces=[]
    for row in range(divisions):
        for col in range(divisions):
            a=row*(divisions+1)+col;b=a+1;c=a+divisions+1;d=c+1
            faces.extend(((a,b,d),(a,d,c)) if (row+col)%2 else ((a,b,c),(b,d,c)))
    for row in range(divisions):
        for col in range(divisions):
            a=row*(divisions+1)+col;b=a+1;c=a+divisions+1;d=c+1
            faces.extend(((len(top)+d,len(top)+b,len(top)+a),(len(top)+c,len(top)+d,len(top)+a)) if (row+col)%2 else ((len(top)+c,len(top)+b,len(top)+a),(len(top)+c,len(top)+d,len(top)+b)))
    for row in range(divisions):
        for col in range(divisions):
            if row not in (0,divisions-1) and col not in (0,divisions-1):continue
            a=row*(divisions+1)+col;b=a+1;c=a+divisions+1;d=c+1
            if row==0:faces.append((a,len(top)+a,len(top)+b,b))
            if row==divisions-1:faces.append((c,d,len(top)+d,len(top)+c))
            if col==0:faces.append((a,c,len(top)+c,len(top)+a))
            if col==divisions-1:faces.append((b,len(top)+b,len(top)+d,d))
    model.mesh(name,points,faces,'leaf_dry',unit)

def outer_rim(model,name,unit,axis,sign):
    """A raised, outward-canted rim; the renderer selects it only along exposed edges."""
    for i in range(13):
        along=-6.55+i*1.08+rng.uniform(-.18,.18);outer=sign*(7.0+rng.uniform(-.2,.2))
        x,z=(along,outer) if axis=='z' else (outer,along)
        angle=(math.pi/2 if axis=='z' else 0)+rng.uniform(-.28,.28)
        leaf(model,name+'_leaf_'+str(i),x,z,1.05+(i%3)*.2,angle,rng.uniform(1.45,2.25),rng.uniform(.72,1.06),'leaf' if i%3 else 'leaf_dry',unit,.22,.34*sign)
        if i%3==0:grass_tuft(model,name+'_tuft_'+str(i),x,z,.72,unit,.36,1.45)
    for i,along in enumerate((-4.6,0,4.35)):
        outer=sign*7.65
        a,b=((along-1.0,.75,outer),(along+1.15,1.15,outer+sign*.34)) if axis=='z' else ((outer,.75,along-1.0),(outer+sign*.34,1.15,along+1.15))
        model.beam(name+'_twig_'+str(i),a,b,.08,'bark',unit)

m=Model('forest_burrow')
# Each tile is a dense, two-course woven mat. Adjacent occupied tiles share it
# continuously; only selected exterior rim layers make the family bowl.
packed_leaf_base(m,'shared_packed_leaf_base',1)
for row in range(9):
    z=-6.35+row*1.58+rng.uniform(-.16,.16)
    for col in range(8):
        x=-6.1+col*1.74+(row%2)*.42+rng.uniform(-.2,.2);edge=min(1,(x*x/48+z*z/46))
        leaf(m,'shared_under_%02d_%02d'%(row,col),x,z,.28+.36*edge+rng.uniform(-.05,.06),rng.uniform(-.27,.27),rng.uniform(1.82,2.38),rng.uniform(.72,.94),'leaf_dry' if (row+col)%3 else 'leaf',1,rng.uniform(.04,.13))
for row in range(7):
    z=-5.65+row*1.87+rng.uniform(-.17,.17)
    for col in range(8):
        x=-6.15+col*1.76+(row%2)*.64+rng.uniform(-.2,.2);edge=min(1,(x*x/48+z*z/43))
        leaf(m,'shared_cover_%02d_%02d'%(row,col),x,z,.48+.42*edge+rng.uniform(-.05,.08),math.pi/2+rng.uniform(-.32,.32),rng.uniform(1.72,2.22),rng.uniform(.68,.9),'leaf' if (row+col)%3 else 'leaf_dry',1,rng.uniform(.08,.18))
# Long, low leaves cross every cell edge; the shared bedding stays solid at the
# joins even though its visible upper leaves retain irregular individual shapes.
for axis in ('x','z'):
    for sign in (-1,1):
        for index,along in enumerate((-4.8,-1.55,1.7,4.85)):
            outer=sign*(6.8+rng.uniform(-.16,.16))
            x,z=(outer,along+rng.uniform(-.25,.25)) if axis=='x' else (along+rng.uniform(-.25,.25),outer)
            angle=(0 if axis=='x' else math.pi/2)+rng.uniform(-.2,.2)
            leaf(m,'shared_interlock_%s_%d_%d'%(axis,sign,index),x,z,.2+rng.uniform(-.04,.06),angle,rng.uniform(2.65,3.35),rng.uniform(.9,1.18),'leaf_dry' if index%2 else 'leaf',1,rng.uniform(.02,.1))
for i in range(14):
    grass_tuft(m,'shared_grass_'+str(i),rng.uniform(-5.75,5.75),rng.uniform(-5.35,5.35),.52,1,.58,1.0)
outer_rim(m,'rim_north',2,'z',-1)
outer_rim(m,'rim_east',3,'x',1)
outer_rim(m,'rim_south',4,'z',1)
outer_rim(m,'rim_west',5,'x',-1)

personal=Model('personal_leaf_nest')
# A later juvenile's own bed stays in its assigned sleeping cell: a smaller, warm bowl without a shelter shell.
for row in range(6):
    z=-2.65+row*1.08+rng.uniform(-.13,.13)
    for col in range(6):
        x=-3.65+col*1.45+(row%2)*.34+rng.uniform(-.16,.16)
        if x*x/18+z*z/8.6>1.12:continue
        edge=min(1,x*x/15+z*z/7.6)
        leaf(personal,'personal_under_%02d_%02d'%(row,col),x,z,.3+.35*edge+rng.uniform(-.04,.06),rng.uniform(-.3,.3),rng.uniform(1.36,1.88),rng.uniform(.62,.84),'leaf_dry' if (row+col)%3 else 'leaf',1,rng.uniform(.04,.13))
for row in range(5):
    z=-2.4+row*1.2+rng.uniform(-.12,.12)
    for col in range(6):
        x=-3.6+col*1.45+(row%2)*.58+rng.uniform(-.15,.15)
        if x*x/18+z*z/8.6>1.12:continue
        edge=min(1,x*x/15+z*z/7.6)
        leaf(personal,'personal_cover_%02d_%02d'%(row,col),x,z,.52+.4*edge+rng.uniform(-.04,.07),math.pi/2+rng.uniform(-.3,.3),rng.uniform(1.22,1.68),rng.uniform(.57,.77),'leaf' if (row+col)%3 else 'leaf_dry',2,rng.uniform(.08,.18))
for i in range(22):
    angle=math.tau*i/22+rng.uniform(-.08,.08)
    x,z=math.cos(angle)*4.35,math.sin(angle)*3.25
    leaf(personal,'personal_rim_'+str(i),x,z,1.08+(i%3)*.2,angle+rng.uniform(-.18,.18),rng.uniform(1.18,1.7),rng.uniform(.7,.98),'leaf' if i%3 else 'leaf_dry',3,.22,.34)
for i in range(9):
    angle=rng.uniform(0,math.tau);radius=rng.uniform(.6,1)
    grass_tuft(personal,'personal_grass_'+str(i),math.cos(angle)*3.55*radius,math.sin(angle)*2.55*radius,.62,3,.42,1.25)
for i,angle in enumerate((.18,1.75,3.0,4.45,5.45)):
    x,z=math.cos(angle)*4.35,math.sin(angle)*3.1
    personal.beam('personal_twig_'+str(i),(x,.8,z),(x+math.cos(angle+.95)*1.45,1.22,z+math.sin(angle+.95)*1.45),.07,'bark',unit=3)
align_sleeping_bed(m)
align_sleeping_bed(personal)
models=[m,personal]
boundary=Model('nest_boundary')
for i,(a,b,r) in enumerate([((-5.8,0,-7),(-8.1,9,-8.1),.24),((5.8,0,-7),(8.1,7,-8.1),.2),((-5.5,.2,-7),(4.2,.2,-7.5),.17)]):
    boundary.beam('loose_twig_'+str(i),a,b,r,'bark')
    name,points,faces,mat,_=boundary.meshes[-1];boundary.meshes[-1]=(name,points,faces,mat,i+1)
models.append(boundary)
write(RUN/'data/mcyukkuri/shelters/forest_burrow.json',{'units_per_block':16,'collision':[]})
m=Model('cardboard_shelter')
m.box('left_wall',[-23,0,-23],[-21,27,23],'paper',True)
m.box('right_wall',[21,0,-23],[23,27,23],'paper',True)
m.box('back_wall',[-21,0,-23],[21,27,-21],'paper',True)
m.box('roof',[-23,26,-23],[23,28,23],'paper',True)
m.box('front_jamb_left',[-21,0,21],[-14,26,23],'paper',True)
m.box('front_jamb_right',[14,0,21],[21,26,23],'paper',True)
m.box('front_lintel',[-14,24,21],[14,27,23],'paper_dark',True)
m.box('floor',[-21,0,-21],[21,.18,22],'paper_dark')
m.mesh('drooping_front_flap',[[-23,27,22],[23,27,22],[21,24,24],[-18,23.6,24]],[(3,2,1,0)],'paper_light')
m.box('roof_tape',[-2,28.05,-23],[2,28.12,22],'tape')
for i in range(9):
    z=-17+i*4
    m.box('side_crease_'+str(i),[-23.12,3+i%3,z],[-23.02,3.16+i%3,z+2],'paper_dark')
m.box('back_tape',[-2,3,-23.12],[2,28,-23.02],'tape')
m.box('side_label',[23.02,9,-9],[23.12,18,6],'paper_light')
for z in (-6,-3,0,3):m.box('label_ink_'+str(z),[23.14,11,z],[23.18,16,z+.5],'ink')
for i in range(12):m.box('corrugated_edge_'+str(i),[-13+i*2,24,23.03],[-12.3+i*2,25,23.16],'paper_light')
for i in range(9):m.box('bedding_'+str(i),[-8+i*1.7,.2,-7],[-7+i*1.7,.35,8],'grass',unit=1)
for i in range(4):m.box('food_'+str(i),[-18,.2+i*.25,-16],[-12,.5+i*.25,-8],'leaf_dry',unit=2)
models.append(m)
for dirty in (False,True):
    m=Model('wild_latrine'+('_dirty' if dirty else ''))
    m.box('shallow_soil',[-6,0,-5],[6,.3,5],'soil')
    for z in (-5,5):m.beam('border_'+str(z),[-6,.5,z],[6,.5,z],.45,'wood')
    for x in (-6,6):m.beam('side_'+str(x),[x,.5,-5],[x,.5,5],.4,'wood')
    if dirty:
        for i in range(3):m.box('waste_'+str(i),[-2+i,.3,-1],[i,1.2+i*.2,1],'waste')
    models.append(m)
bundle=Model('bedding_bundle')
for i in range(7):
    leaf(bundle,'gathered_leaf_'+str(i),(i%3-1)*.65,(i//3-1)*.6,.12+i*.08,
         -.7+i*.23,2.4,.95,'leaf_dry' if i%3==0 else 'leaf',0,.16,.12)
for i in range(4):
    grass_tuft(bundle,'gathered_grass_'+str(i),-.6+i*.4,-.2,.25,0,.22,.65)
models.append(bundle)
exports={m.name:m.export() for m in models}
write(RUN/'assets/mcyukkuri/yukkuri_props/shelters-index.json',{name:{} for name in exports})
# Depth-buffered views use the library's actual triangle rasterizer.
scratch=ROOT/'.local/wild-shelters/render';scratch.mkdir(parents=True,exist_ok=True)
(scratch/'texture.rgba').write_bytes(atlas.tobytes())
node="""const fs=require('fs'),vm=require('vm');
const [runtime,folder]=process.argv.slice(1),ctx=vm.createContext({});
vm.runInContext(fs.readFileSync(runtime,'utf8'),ctx);
const data=JSON.parse(fs.readFileSync(folder+'/input.json','utf8'));
const texture=fs.readFileSync(folder+'/texture.rgba');
for(const item of data) {
 const pixels=ctx.renderModel(item.vertices,texture,460,420,item.az,item.el,item.span,{textureSize:256,centerY:item.center});
 fs.writeFileSync(folder+'/'+item.id+'.rgba',Buffer.from(pixels));
}"""
entries=[]
for name,data in exports.items():
 if name.startswith('wild_latrine') or name=='nest_boundary':continue
 if name=='forest_burrow':
  data=dict(data);twigs=list(exports['nest_boundary']['vertices'])
  for k in range(2,len(twigs),8):twigs[k]+=16
  data['vertices']=data['vertices']+twigs
 for az,el,title in [(math.pi,.15,'front'),(math.pi-.65,.3,'quarter'),(.65,.3,'back')]:
  leaf_bed=name in {'forest_burrow','personal_leaf_nest','bedding_bundle'}
  entries.append(dict(id=name+'-'+title,vertices=data['vertices'],az=az,el=el,span=16 if leaf_bed else 34,center=3 if leaf_bed else 14))

def selected_layers(data,mask,shift_x=0,shift_z=0):
    """Compose the same unit-filtered mesh that ShelterRenderer uses for a bed cell."""
    vertices=[]
    for part in data['parts']:
        unit=part['unit']
        if unit and not mask & (1 << (unit-1)):continue
        start=part['start']*8;end=(part['start']+part['count'])*8
        piece=list(data['vertices'][start:end])
        for index in range(0,len(piece),8):
            piece[index]+=shift_x;piece[index+2]+=shift_z
        vertices.extend(piece)
    return vertices

# Four adjacent occupied sleeping cells use only their exposed rim units. This is
# an authoring preview of the contiguous family bowl, rather than a tiled screenshot.
family_vertices=[]
for shift_x,shift_z,mask in ((-8,-8,19),(8,-8,7),(-8,8,25),(8,8,13)):
    family_vertices.extend(selected_layers(exports['forest_burrow'],mask,shift_x,shift_z))
entries.extend([
    dict(id='family-shared-leaf-nest',vertices=family_vertices,az=math.pi-.65,el=.52,span=20,center=.95),
    dict(id='personal-leaf-nest',vertices=exports['personal_leaf_nest']['vertices'],az=math.pi-.65,el=.52,span=6.6,center=.95),
])
for mask,label in ((1,'underlay'),(3,'spread'),(7,'finished')):
    entries.append(dict(id='nest-stage-'+label,vertices=selected_layers(exports['personal_leaf_nest'],mask),az=math.pi-.65,el=.52,span=6.6,center=.95))
entries.append(dict(id='gathered-bedding',vertices=exports['bedding_bundle']['vertices'],az=math.pi-.65,el=.4,span=3.5,center=.5))
write(scratch/'input.json',entries)
subprocess.run(['node','-e',node,str(ROOT/'standards/yukkuri-library/runtime/render.js'),str(scratch)],check=True)
sheet=Image.new('RGB',(1400,math.ceil(len(entries)/3)*450+10),'#e9e6dc');draw=ImageDraw.Draw(sheet)
rendered={}
for i,item in enumerate(entries):
 im=Image.frombytes('RGBA',(460,420),(scratch/(item['id']+'.rgba')).read_bytes())
 rendered[item['id']]=im
 x=(i%3)*466;y=(i//3)*450
 sheet.paste(im,(x,y+25),im);draw.text((x+15,y+10),item['id'],fill='#383e35')
 im.save(PRE/(item['id']+'.png'))
sheet.save(PRE/'shelters-sheet.png')
comparison=Image.new('RGB',(940,520),'#e9e6dc');comparison_draw=ImageDraw.Draw(comparison)
for x,item_id,label in ((5,'family-shared-leaf-nest','SHARED FAMILY NEST - FOUR CONNECTED BEDS'),(475,'personal-leaf-nest','PERSONAL JUVENILE NEST - ONE BED')):
    image=rendered[item_id]
    comparison.paste(image,(x,55),image)
comparison_draw.rectangle((0,0,940,48),fill='#e9e6dc')
for x,label in ((17,'SHARED FAMILY NEST - FOUR CONNECTED BEDS'),(487,'PERSONAL JUVENILE NEST - ONE BED')):
    comparison_draw.text((x,18),label,fill='#383e35')
comparison.save(PRE/'large-shared-vs-small-personal.png')
progress=Image.new('RGB',(1840,460),'#e9e6dc');progress_draw=ImageDraw.Draw(progress)
for i,(item_id,label) in enumerate((('gathered-bedding','01  CARRY A REAL PORTION'),('nest-stage-underlay','02  LAY THE UNDERLAYER'),('nest-stage-spread','03  SPREAD THE SECOND PORTION'),('nest-stage-finished','04  PRESS AND FINISH'))):
    progress.paste(rendered[item_id],(460*i,35),rendered[item_id])
    progress_draw.text((460*i+20,18),label,fill='#383e35')
progress.save(PRE/'bedding-progress.png')
manifest={'status':'pending_user_confirmation','authoring':'build.py','coordinate_system':'16 units/block, +Z entrance, origin center floor','sources':[{'url':'http://blog.livedoor.jp/yukkuri_anko/archives/29803869.html','observed':'Battered cardboard shelter with an opening; original new mesh and texture.'},{'source':'User ecological correction, 2026-09-13','observed':'Existing cave or pit; only loose leaves and a few thin entrance twigs. No constructed earth walls, roof, arch or bindings. Previous mound interpretation rejected.'}],'exports':{name:{'mesh_sha256':sha(OUT/(name+'.json')),'editable_sha256':sha(OUT/(name+'.bbmodel')),'texture_sha256':sha(OUT/(name+'.png'))} for name in exports}}
previous=json.loads((HERE/'manifest.json').read_text(encoding='utf-8')) if (HERE/'manifest.json').exists() else {}
manifest['approvals']=previous.get('approvals',[])
renderer_hash=sha(ROOT/'src/main/java/io/github/longshou/mcyukkuri/client/ShelterRenderer.java')
manifest['asset_status']={name:'pending_user_confirmation' for name in exports}
for approval in manifest['approvals']:
    if approval.get('renderer_sha256')!=renderer_hash:continue
    for name,fingerprint in approval.get('exports',{}).items():
        if manifest['exports'].get(name)==fingerprint:manifest['asset_status'][name]='confirmed'
approved_count=sum(status=='confirmed' for status in manifest['asset_status'].values())
manifest['status']='confirmed' if approved_count==len(exports) else 'partially_confirmed' if approved_count else 'pending_user_confirmation'
write(HERE/'manifest.json',manifest)
shutil.copy2(HERE/'manifest.json',SNAP/'manifest.json')
print('Exported',', '.join(exports))
