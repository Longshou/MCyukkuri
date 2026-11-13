"""Original hollow shelters; editable meshes, matching collision and selected runtime exports.
Run with Python + Pillow. Coordinate units: 16/block, +Z entrance, ground Y=0.
"""
from pathlib import Path
import argparse, base64, hashlib, json, math, random, shutil, uuid, subprocess
from PIL import Image, ImageDraw, ImageFont
HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[3]
import sys
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--check',action='store_true',help='verify the authored carton exports without writing files')
args=parser.parse_args()
import cardboard
if args.check:
    cardboard.check(ROOT)
    raise SystemExit(0)
sys.path.insert(0,str(ROOT/'standards/yukkuri-library/drafts/care-props'))
import kit as care_kit   # shared care-prop palette (art/props/palette.json)
care_kit.NOISE_DEFAULT=False   # the crate keeps flat swatches; self-built nests below are vanilla-textured
# The cardboard carton is authored in cardboard.py (block-style cuboids on a pixel atlas); the confirmed leaf-bed atlas stays untouched.
# Wild settlements have no latrine model: their waste pit is bare terrain (settlement/site/SiteBuilder.pit).
FLAT_MATERIALS={'paper':('cardboard',2),'paper_light':('cardboard',3),'paper_dark':('cardboard',0),'tape':('straw',2),'ink':('wood',0),
                'grass':('straw',1),'leaf_dry':('straw',0),'soil':('wood',1),'wood':('wood',2),'waste':('anko',1),
                'waste0':('anko',0),'waste1':('anko',1),'waste2':('anko',2),'waste3':('anko',3)}
FLAT_MODELS={'crate_shelter'}
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
    def __init__(self,name): self.name=name;self.meshes=[];self.collision=[];self.flat=name in FLAT_MODELS
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
        flat=self.flat
        if flat:
            flat_prop=care_kit.Prop(self.name);tex_image=flat_prop.img;tex_bytes=flat_prop.png();tw,th=flat_prop.W,flat_prop.H
        else:
            tex_image=atlas;tex_bytes=PALETTE_BYTES;tw,th=256,256
        for name,points,faces,mat,unit in self.meshes:
            start=len(vertices)//8;fs={}
            if flat:u,v=flat_prop.swatch_uv(*FLAT_MATERIALS[mat])
            else:idx=NAMES.index(mat);u=(idx%4)*64+3;v=(idx//4)*64+3
            for face in faces:
                for j in range(1,len(face)-1):
                    tri=[face[0],face[j],face[j+1]];a,b,c=[points[n] for n in tri]
                    ab=[b[k]-a[k] for k in range(3)];ac=[c[k]-a[k] for k in range(3)]
                    normal=[ab[1]*ac[2]-ab[2]*ac[1],ab[2]*ac[0]-ab[0]*ac[2],ab[0]*ac[1]-ab[1]*ac[0]];le=math.sqrt(sum(x*x for x in normal))
                    if le<1e-8:continue
                    normal=[x/le for x in normal];uvs=[[u,v]]*3 if flat else [[u,v],[u+57,v],[u+28,v+57]]
                    fs[str(len(fs))]={'vertices':list(map(str,tri)),'uv':{str(n):uv for n,uv in zip(tri,uvs)},'texture':0}
                    for n,uv in zip(tri,uvs):vertices.extend([*points[n],uv[0]/tw,uv[1]/th,*normal])
            parts.append({'name':name,'start':start,'count':len(vertices)//8-start,'unit':unit})
            elements.append({'name':name,'type':'mesh','uuid':str(uuid.uuid5(uuid.NAMESPACE_URL,self.name+'/'+name+str(start))),'origin':[0,0,0],'rotation':[0,0,0],'vertices':{str(i):p for i,p in enumerate(points)},'faces':fs})
        bounds=[[min(vertices[k::8]),max(vertices[k::8])] for k in range(3)]
        model={'format':1,'units_per_block':16,'vertices':vertices,'parts':parts,'bounds':bounds}
        write(OUT/(self.name+'.json'),model)
        write(OUT/(self.name+'.bbmodel'),{'meta':{'format_version':'4.10','model_format':'free'},'name':self.name,'resolution':{'width':tw,'height':th},'elements':elements,'outliner':[e['uuid'] for e in elements],'textures':[{'name':'palette.png','id':'0','width':tw,'height':th,'source':'data:image/png;base64,'+base64.b64encode(tex_bytes).decode()}]})
        assembled_texture = OUT/(self.name+'.png')
        selected_texture = SNAP/assembled_texture.name
        if not assembled_texture.exists() and selected_texture.exists():
            shutil.copy2(selected_texture, assembled_texture)
        save_image(tex_image, assembled_texture)
        for suffix in ('.json','.bbmodel','.png'):shutil.copy2(OUT/(self.name+suffix),SNAP/(self.name+suffix))
        shutil.copy2(OUT/(self.name+'.json'),RUN/'assets/mcyukkuri/yukkuri_props'/(self.name+'.json'))
        tex=RUN/'assets/mcyukkuri/textures/props';tex.mkdir(parents=True,exist_ok=True);save_image(tex_image,tex/(self.name+'.png'))
        if self.collision:write(RUN/'data/mcyukkuri/shelters'/(self.name+'.json'),{'units_per_block':16,'collision':self.collision})
        return model

models=[]
boundary=Model('nest_boundary')
for i,(a,b,r) in enumerate([((-5.8,0,-7),(-8.1,9,-8.1),.24),((5.8,0,-7),(8.1,7,-8.1),.2),((-5.5,.2,-7),(4.2,.2,-7.5),.17)]):
    boundary.beam('loose_twig_'+str(i),a,b,r,'bark')
    name,points,faces,mat,_=boundary.meshes[-1];boundary.meshes[-1]=(name,points,faces,mat,i+1)
models.append(boundary)
write(RUN/'data/mcyukkuri/shelters/forest_burrow.json',{'units_per_block':16,'collision':[]})
m=Model('crate_shelter')
m.box('left_boards',[-23,0,-23],[-20,17,23],'ink',True)
m.box('right_boards',[20,0,-23],[23,17,23],'ink',True)
m.box('back_boards',[-20,0,-23],[20,17,-20],'ink',True)
m.box('top_boards',[-23,16,-23],[23,19,23],'wood',True)
m.box('floor_boards',[-20,0,-20],[20,.15,23],'ink')
for x in (-21,-13,-5,3,11,19):
    m.box('board_seam_'+str(x),[x,19,-23],[x+.2,19.09,23],'ink')
for x in (-23,20):
    m.box('front_batten_'+str(x),[x,0,23],[x+3,16,24],'ink')
models.append(m)

# The same invisible shell dispatches the shared renderer at the crate's root.
assets = RUN/'assets/mcyukkuri'
write(assets/'blockstates/crate_shelter.json', {'variants': {'': {'model': 'mcyukkuri:block/crate_shelter'}}})
write(assets/'models/block/crate_shelter.json', {'textures': {'particle': 'minecraft:block/oak_planks'}})
cardboard_item = json.loads((assets/'models/item/cardboard_shelter.json').read_text(encoding='utf-8'))
cardboard_item['textures'] = {'particle': 'mcyukkuri:props/crate_shelter'}
write(assets/'models/item/crate_shelter.json', cardboard_item)
write(RUN/'data/mcyukkuri/recipe/crate_shelter.json', {'type': 'minecraft:crafting_shapeless', 'category': 'misc',
      'ingredients': [{'item': 'minecraft:barrel'}, {'item': 'minecraft:oak_planks'}],
      'result': {'id': 'mcyukkuri:crate_shelter', 'count': 1}})
# Self-built nests (wild yukkuri) are authored in the care-props kit with vanilla texture references (wild.py).

import wild as care_wild
KIT_NESTS=('forest_burrow','personal_leaf_nest','bedding_bundle')
def export_kit(prop):
    name=prop.name;mesh=prop.mesh();texture=prop.png()
    write(OUT/(name+'.json'),mesh)
    (OUT/(name+'.bbmodel')).write_bytes(care_kit.dumps(prop.bbmodel()))
    (OUT/(name+'.png')).write_bytes(texture)
    for suffix in ('.json','.bbmodel','.png'):shutil.copy2(OUT/(name+suffix),SNAP/(name+suffix))
    shutil.copy2(OUT/(name+'.json'),RUN/'assets/mcyukkuri/yukkuri_props'/(name+'.json'))
    tex=RUN/'assets/mcyukkuri/textures/props';tex.mkdir(parents=True,exist_ok=True);(tex/(name+'.png')).write_bytes(texture)
    return mesh
exports={m.name:m.export() for m in models}
def export_carton(level):
    carton=cardboard.build(level);mesh,elements,image=carton.export();name=carton.name
    write(OUT/(name+'.json'),mesh)
    tex_bytes=__import__('io').BytesIO();image.save(tex_bytes,format='PNG')
    write(OUT/(name+'.bbmodel'),{'meta':{'format_version':'4.10','model_format':'free'},'name':name,'resolution':{'width':cardboard.SIZE,'height':cardboard.SIZE},
          'elements':elements,'outliner':[e['uuid'] for e in elements],'textures':[{'name':name+'.png','id':'0','width':cardboard.SIZE,'height':cardboard.SIZE,
          'source':'data:image/png;base64,'+base64.b64encode(tex_bytes.getvalue()).decode()}]})
    save_image(image,OUT/(name+'.png'))
    for suffix in ('.json','.bbmodel','.png'):shutil.copy2(OUT/(name+suffix),SNAP/(name+suffix))
    shutil.copy2(OUT/(name+'.json'),RUN/'assets/mcyukkuri/yukkuri_props'/(name+'.json'))
    save_image(image,RUN/'assets/mcyukkuri/textures/props'/(name+'.png'))
    if level==0:write(RUN/'data/mcyukkuri/shelters'/(name+'.json'),{'units_per_block':16,'collision':carton.collision})
    return mesh
for _level,_name in enumerate(('cardboard_shelter','cardboard_shelter_damp','cardboard_shelter_soaked')):exports[_name]=export_carton(_level)
for _name in KIT_NESTS:exports[_name]=export_kit(care_wild.WILD[_name]())
write(RUN/'assets/mcyukkuri/yukkuri_props/shelters-index.json',{name:{} for name in exports})
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
