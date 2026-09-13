"""Original cuboid model, pixel atlas and reproducible previews. No game assets copied."""
from pathlib import Path
import base64, json, math, uuid, zipfile
import numpy as np
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent
ROOT.mkdir(parents=True, exist_ok=True)
def save_json(path, data):
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')
def uid(name): return str(uuid.uuid5(uuid.NAMESPACE_URL, 'yukkuri/reimu/v1/'+name))

atlas = Image.new('RGB', (128,128), '#ffe9bc')
d = ImageDraw.Draw(atlas)
palette = {'skin':'#ffe7b9','hair':'#302d2d','hair_light':'#403c3b',
           'hair_dark':'#242222','red':'#d92732','red_light':'#ee3b43',
           'red_dark':'#a91d2c','white':'#fff5df','white_shadow':'#d9d3c9'}
uv = {}
for i,(name,color) in enumerate(palette.items()):
    x=(i%8)*16; y=48+(i//8)*16
    d.rectangle((x,y,x+15,y+15),fill=color)
    uv[name]=[x,y,x+16,y+16]
    if name.startswith('hair'):
        shade={'hair':'#393535','hair_light':'#494442','hair_dark':'#2b2828'}[name]
        for xx,yy in [(2,3),(9,8),(13,1)]:
            d.rectangle((x+xx,y+yy,x+xx+1,y+min(yy+5,15)),fill=shade)

# Broad, low face: hand-authored pixel features, not a pasted reference illustration.
face = Image.new('RGB',(48,32),'#ffe7b9'); f=ImageDraw.Draw(face)
# Flat skin below the smile: remove the former horizontal chin-shadow band.
# Relaxed neutral-happy face: no white eye rings, smaller warm pupils.
ink='#48352e'
for x in (9,30):
    f.ellipse((x,11,x+8,20),fill=ink)
    f.ellipse((x+1,12,x+7,19),fill='#322923')
    f.line([(x+2,19),(x+5,19)],fill='#765239',width=1)
    f.ellipse((x+1,12,x+4,15),fill='#fff8e9')
    f.point((x+6,18),fill='#ead4ad')
    f.line([(x+1,8),(x+4,7),(x+7,8)],fill='#705349',width=1)
for x in (5,36):
    f.ellipse((x,21,x+6,23),fill='#f4bda4')
# Small upward-cornered smile, softly coloured inside rather than a red circle.
f.line([(21,21),(22,23),(25,23),(26,21)],fill='#805044',width=1)
f.line([(22,22),(25,22)],fill='#d97a72',width=1)
atlas.paste(face,(0,0)); uv['face']=[0,0,48,32]
atlas.save(ROOT/'reimu.png')

cubes=[]
def cube(name, group, lo, hi, material, face_material=None):
    faces={k:uv[material][:] for k in ('north','east','south','west','up','down')}
    if face_material: faces['north']=uv[face_material][:]
    cubes.append(dict(name=name,group=group,lo=lo,hi=hi,faces=faces))

# The selected Marisa body's four skin cuboids, at the same Y=0 baseline.
cube('soft_head','head',[-6,0,-4.6],[6,8,4.5],'skin','face')
cube('left_cheek','head',[-6.65,1,-3.6],[-6,6.5,3.7],'skin')
cube('right_cheek','head',[6,1,-3.6],[6.65,6.5,3.7],'skin')
cube('crown','head',[-5.4,8,-3.8],[5.4,9,3.8],'skin')

# Black-brown hair, closed at the back and sides. Uneven ends make distinct locks.
cube('back_hair','hair',[-6.4,1.8,3.8],[6.4,8.7,5.2],'hair')
cube('back_hair_lower','hair',[-5.6,.45,3.8],[5.6,2.5,5],'hair_dark')
for i,x in enumerate((-5.8,-3.4,-1,1.4,3.8)):
    cube('back_lock_'+str(i),'hair',[x,.55+(i%2)*.45,4.8],[x+1.7,6,5.5],
         'hair' if i%2 else 'hair_light')
cube('hair_cap','hair',[-6.4,7.8,-4.6],[6.4,9.1,4.6],'hair')

# A longer centre lock and shorter adjacent locks reproduce the source fringe.
for i,(x,w,bottom) in enumerate([(-6,1.6,5.6),(-4.4,1.7,6.45),(-2.7,1.7,6.2),
                                (-1,2,5.4),(1,1.7,6.2),(2.7,1.7,6.45),(4.4,1.6,5.6)]):
    mat='hair_light' if i in (1,5) else 'hair'
    cube('bang_'+str(i),'bangs',[x,bottom,-5.05],[x+w,8.5,-4.58],mat)
    cube('bang_tip_'+str(i),'bangs',[x+.35,bottom-.45,-5.02],[x+w-.25,bottom,-4.62],mat)
cube('centre_bang_tip','bangs',[-.3,4.6,-5],[.3,4.95,-4.64],'hair')

for side in (-1,1):
    group='side_lock_left' if side<0 else 'side_lock_right'
    x=-7.1 if side<0 else 5.9
    cube('side_hair_'+str(side),'hair',[x,2.5,-3.8],[x+1.2,8,3.5],'hair')
    cube('tied_lock_'+str(side),group,[x+.1,.8,-4.16],[x+1.1,5.7,-3.2],'hair')
    cube('cuff_'+str(side),group,[x-.02,1.75,-4.3],[x+1.22,3.15,-3.12],'red')
    cube('cuff_top_'+str(side),group,[x-.08,3.12,-4.35],[x+1.28,3.43,-3.08],'white')
    cube('cuff_hem_'+str(side),group,[x-.15,1.48,-4.38],[x+1.35,1.85,-3.04],'white')
    cube('cuff_fold_'+str(side),group,[x+.84,1.87,-4.33],[x+1.12,3.1,-4.3],'red_dark')
    cube('lock_tip_'+str(side),group,[x+.26,.45,-4.13],[x+.95,1.5,-3.21],'hair_light')

# Bow behind the crown: wide lobes, narrower waist, ivory stepped outer hems.
# Unlike a hat replacement slab, the knot and trailing ribbons read from the back.
for side in (-1,1):
    group='bow_left' if side<0 else 'bow_right'
    def mirror(name,x0,x1,y0,y1,z0,z1,mat):
        lo,hi=sorted((side*x0,side*x1))
        cube(name+'_'+str(side),group,[lo,y0,z0],[hi,y1,z1],mat)
    mirror('bow_inner',.65,2.65,7.9,9.7,5.5,6.7,'red')
    mirror('bow_middle',2.65,4.8,7.45,10.4,5.55,6.65,'red')
    mirror('bow_outer',4.8,7.15,6.95,11.15,5.6,6.6,'red_light')
    mirror('bow_hem',7.15,7.5,6.9,11.2,5.55,6.65,'white')
    mirror('bow_edge',7.5,7.72,6.95,11.15,5.6,6.6,'red_dark')
    for j,(a,b) in enumerate([(6.85,7.65),(7.85,8.65),(8.85,9.65),(9.85,10.65)]):
        mirror('bow_frill_'+str(j),7.7,8.02+(j%2)*.12,a,b,5.65,6.57,'white')
    mirror('bow_frill_top',6.75,7.7,11.15,11.55,5.65,6.57,'white')
    mirror('bow_frill_bottom',6.75,7.7,6.55,6.95,5.65,6.57,'white')
    # Fold ridges front and back; no coplanar faces.
    for j,z in enumerate((5.46,6.66)):
        mirror('bow_fold_'+str(j),1.25,4.4,8.55,8.8,z,z+.12,'red_dark')
    # Stepped diagonal tails sit over the rear hair, joined to the knot.
    mirror('bow_tail_top',.7,2.15,7.3,8.8,5.55,6.8,'red_dark')
    mirror('bow_tail_mid',1.4,2.95,5.9,7.4,5.55,5.95,'red')
    mirror('bow_tail_end',2.1,3.8,4.7,6,5.6,6,'red_light')
    mirror('bow_tail_hem',2.05,3.9,4.38,4.75,5.58,6.03,'white')
cube('bow_knot','bow_knot',[-.8,7.85,5.3],[.8,9.7,7.05],'red_light')
cube('bow_knot_fold','bow_knot',[.37,8.05,7.05],[.66,9.45,7.17],'red_dark')

groups=['head','hair','bangs','side_lock_left','side_lock_right','bow_left','bow_right','bow_knot']
group_pivots={'head':[0,4,0],'hair':[0,7,0],'bangs':[0,8,-4.6],
              'side_lock_left':[-6.5,5.7,-3.7],'side_lock_right':[6.5,5.7,-3.7],
              'bow_left':[-.7,8.8,6],'bow_right':[.7,8.8,6],'bow_knot':[0,8.8,6]}
elements=[]
for c in cubes:
    elements.append({'name':c['name'],'uuid':uid(c['name']),'type':'cube','from':c['lo'],'to':c['hi'],
      'origin':[0,0,0],'rotation':[0,0,0],'box_uv':False,'rescale':False,'autouv':0,'color':groups.index(c['group']),
      'faces':{k:{'uv':v,'texture':0} for k,v in c['faces'].items()}})
texture_source='data:image/png;base64,'+base64.b64encode((ROOT/'reimu.png').read_bytes()).decode()
bb={'meta':{'format_version':'4.10','model_format':'free','box_uv':False},'name':'Yukkuri Reimu',
    'model_identifier':'yukkuri_reimu','visible_box':[3,3,1], 'resolution':{'width':128,'height':128},
    'elements':elements,'outliner':[{'name':'root','uuid':uid('root'),'origin':[0,0,0],
      'children':[{'name':g,'uuid':uid('group_'+g),'origin':group_pivots[g],'children':[uid(c['name']) for c in cubes if c['group']==g]} for g in groups]}],
    'textures':[{'path':'reimu.png','name':'reimu.png','id':'0','uuid':uid('texture'),'source':texture_source,
                 'mode':'bitmap','width':128,'height':128,'uv_width':128,'uv_height':128,'particle':True}]}
save_json(ROOT/'reimu.bbmodel',bb)
bones=[{'name':'root','pivot':[0,0,0]}]
for g in groups:
    bones.append({'name':g,'parent':'root','pivot':group_pivots[g],'cubes':[
        {'origin':c['lo'],'size':[b-a for a,b in zip(c['lo'],c['hi'])],
         'uv':{k:{'uv':v[:2],'uv_size':[v[2]-v[0],v[3]-v[1]]} for k,v in c['faces'].items()}}
        for c in cubes if c['group']==g]})
save_json(ROOT/'reimu.geo.json',{'format_version':'1.12.0','minecraft:geometry':[{'description':{
    'identifier':'geometry.yukkuri_reimu','texture_width':128,'texture_height':128,
    'visible_bounds_width':3,'visible_bounds_height':3,'visible_bounds_offset':[0,1,0]},'bones':bones}]})

# Vanilla Java display resource pack, targeted at 1.21/1.21.1. Only CMD 98003 on paper.
pack=ROOT/'resourcepack'
save_json(pack/'pack.mcmeta',{'pack':{'pack_format':34,'description':'灵梦模型预览 · Java 1.21–1.21.1 · 纸 CMD 98003'}})
save_json(pack/'assets/minecraft/models/item/paper.json',{'parent':'minecraft:item/generated',
    'textures':{'layer0':'minecraft:item/paper'},'overrides':[
    {'predicate':{'custom_model_data':98003},'model':'yukkuri:item/reimu'},
    {'predicate':{'custom_model_data':98004},'model':'minecraft:item/paper_plain'}]})
save_json(pack/'assets/minecraft/models/item/paper_plain.json',{'parent':'minecraft:item/generated','textures':{'layer0':'minecraft:item/paper'}})
java_elements=[]
for c in cubes:
    java_elements.append({'name':c['name'],'from':[c['lo'][0]+8,c['lo'][1],c['lo'][2]+8],
       'to':[c['hi'][0]+8,c['hi'][1],c['hi'][2]+8],
       'faces':{k:{'uv':[n/8 for n in v],'texture':'#0'} for k,v in c['faces'].items()}})
save_json(pack/'assets/yukkuri/models/item/reimu.json',{'credit':'Original model: Yukkuri ModLab',
  'texture_size':[128,128],'textures':{'0':'yukkuri:entity/reimu','particle':'yukkuri:entity/reimu'},
  'elements':java_elements,'display':{'gui':{'rotation':[15,155,0],'translation':[0,-1,0],'scale':[.75,.75,.75]},
    'ground':{'translation':[0,3,0],'scale':[.5,.5,.5]},'fixed':{'rotation':[0,180,0],'scale':[.7,.7,.7]},
    'thirdperson_righthand':{'rotation':[0,180,0],'translation':[0,3,0],'scale':[.5,.5,.5]},
    'firstperson_righthand':{'rotation':[0,150,0],'translation':[1,2,0],'scale':[.6,.6,.6]}}})
(pack/'assets/yukkuri/textures/entity').mkdir(parents=True,exist_ok=True)
atlas.save(pack/'assets/yukkuri/textures/entity/reimu.png')

# Shared explicit face winding and UV coordinates for the offline renderer and WebGL viewer.
def faces(c):
    x,y,z=c['lo']; X,Y,Z=c['hi']
    quads={'north':[(X,Y,z),(x,Y,z),(x,y,z),(X,y,z)],
      'south':[(x,Y,Z),(X,Y,Z),(X,y,Z),(x,y,Z)],
      'east':[(X,Y,Z),(X,Y,z),(X,y,z),(X,y,Z)],
      'west':[(x,Y,z),(x,Y,Z),(x,y,Z),(x,y,z)],
      'up':[(x,Y,z),(X,Y,z),(X,Y,Z),(x,Y,Z)],
      'down':[(x,y,Z),(X,y,Z),(X,y,z),(x,y,z)]}
    normals={'north':(0,0,-1),'south':(0,0,1),'east':(1,0,0),'west':(-1,0,0),'up':(0,1,0),'down':(0,-1,0)}
    for k,points in quads.items():
        u,v,U,V=c['faces'][k]
        yield points,[(u,v),(U,v),(U,V),(u,V)],normals[k]

def render(angle=-25,elevation=12,size=760,span=22,center_y=5.8):
    az=math.radians(angle); el=math.radians(elevation)
    right=np.array([math.cos(az),0,math.sin(az)])
    eye=np.array([math.sin(az)*math.cos(el),math.sin(el),-math.cos(az)*math.cos(el)])
    up=np.cross(eye,right)
    # Camera right is +X in the front view; screen projects positive Y upwards.
    up=np.array([-math.sin(az)*math.sin(el),math.cos(el),math.cos(az)*math.sin(el)])
    scale=size/span; center=np.array([0,center_y,0])
    bg=np.empty((size,size,3),dtype=np.uint8); bg[:]=[234,231,222]
    yy,xx=np.mgrid[:size,:size]
    shadow=np.exp(-(((xx-size*.5)/(size*.27))**2+((yy-(size*.5+center_y*math.cos(el)*scale))/(size*.045))**2)*2)*.18
    bg=(bg*(1-shadow[:,:,None])).astype(np.uint8)
    depth=np.full((size,size),-1e10)
    tex=np.array(atlas)
    for c in cubes:
      for pts,uvs,n in faces(c):
        if np.dot(n,eye)<=0: continue
        p=np.array(pts)-center
        screen=np.stack([size*.5+p@right*scale,size*.5-p@up*scale,p@eye],axis=1)
        brightness=.78+.22*max(0,np.dot(np.array(n),np.array([-.35,.8,-.48])))
        for ids in ((0,1,2),(0,2,3)):
          tri=screen[list(ids)]; tuv=np.array(uvs)[list(ids)]
          mn=np.maximum(np.floor(tri[:,:2].min(axis=0)).astype(int),0)
          mx=np.minimum(np.ceil(tri[:,:2].max(axis=0)).astype(int),size-1)
          if np.any(mx<mn): continue
          gx,gy=np.meshgrid(np.arange(mn[0],mx[0]+1)+.5,np.arange(mn[1],mx[1]+1)+.5)
          a,b,c0=tri
          det=(b[1]-c0[1])*(a[0]-c0[0])+(c0[0]-b[0])*(a[1]-c0[1])
          if abs(det)<1e-8: continue
          wa=((b[1]-c0[1])*(gx-c0[0])+(c0[0]-b[0])*(gy-c0[1]))/det
          wb=((c0[1]-a[1])*(gx-c0[0])+(a[0]-c0[0])*(gy-c0[1]))/det
          wc=1-wa-wb; zd=wa*a[2]+wb*b[2]+wc*c0[2]
          view=depth[mn[1]:mx[1]+1,mn[0]:mx[0]+1]
          mask=(wa>=-1e-6)&(wb>=-1e-6)&(wc>=-1e-6)&(zd>view)
          uvp=wa[:,:,None]*tuv[0]+wb[:,:,None]*tuv[1]+wc[:,:,None]*tuv[2]
          tx=np.clip(np.floor(uvp[:,:,0]).astype(int),0,127); ty=np.clip(np.floor(uvp[:,:,1]).astype(int),0,127)
          col=(tex[ty,tx]*brightness).astype(np.uint8)
          bg[mn[1]:mx[1]+1,mn[0]:mx[0]+1][mask]=col[mask]; view[mask]=zd[mask]
    return Image.fromarray(bg)

if __name__=='__main__':
    render().save(ROOT/'preview.png')
    board=Image.new('RGB',(1500,650),'#eae7de'); bd=ImageDraw.Draw(board)
    font_path='C:/Windows/Fonts/msyh.ttc'
    title=ImageFont.truetype(font_path,28); small=ImageFont.truetype(font_path,18)
    bd.text((35,22),f'灵梦 · 八景 U 造型 / 魔理沙基础体型 · {len(cubes)} 方块',font=title,fill='#393540')
    for i,(a,e,label) in enumerate([(0,0,'正面'),(-32,14,'四分之三视角'),(145,12,'背面 / 蝴蝶结与垂带')]):
        board.paste(render(a,e,490),(i*500+5,90))
        bd.text((i*500+35,590),label,font=small,fill='#635c59')
    board.save(ROOT/'preview-sheet.png')
    render(0,8,128).save(pack/'pack.png')
    with zipfile.ZipFile(ROOT/'reimu-java-1.21.1-resourcepack.zip','w',zipfile.ZIP_DEFLATED) as z:
        for p in pack.rglob('*'):
            if p.is_file(): z.write(p,p.relative_to(pack).as_posix())
    vertices=[]
    for c in cubes:
      for points,texcoords,normal in faces(c):
        for i in (0,1,2,0,2,3): vertices.extend([*points[i],texcoords[i][0]/128,texcoords[i][1]/128,*normal])
    data={'vertices':vertices,'texture':texture_source,'cubes':len(cubes)}
    template=(ROOT/'viewer-template.html').read_text(encoding='utf-8').replace('__VIEWER_SCRIPT__',(ROOT/'software-viewer.js').read_text(encoding='utf-8'))
    fallback='data:image/png;base64,'+base64.b64encode((ROOT/'preview.png').read_bytes()).decode()
    (ROOT/'preview.html').write_text(template.replace('__CUBE_COUNT__',str(len(cubes))).replace('__MODEL_DATA__',json.dumps(data,separators=(',',':'))).replace('__FALLBACK_IMAGE__',fallback),encoding='utf-8')
    print(f'Generated {len(cubes)} cuboids, {len(cubes)*12} triangles, 128x128 atlas.')
