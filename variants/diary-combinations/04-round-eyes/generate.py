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
def uid(name): return str(uuid.uuid5(uuid.NAMESPACE_URL, 'yukkuri/marisa/v1/'+name))

atlas = Image.new('RGB', (128,128), '#ffe9bc')
d = ImageDraw.Draw(atlas)
palette = {'skin':'#ffe7b9','gold':'#efc252','gold_light':'#ffdc72','gold_dark':'#c89436',
           'hat':'#34333e','hat_light':'#43424e','hat_dark':'#272730','white':'#fff5df',
           'ribbon_shadow':'#d9d3c9','ribbon_fold':'#e9e2d6'}
uv = {}
for i,(name,color) in enumerate(palette.items()):
    x=(i%8)*16; y=96+(i//8)*16
    d.rectangle((x,y,x+15,y+15),fill=color)
    uv[name]=[x,y,x+16,y+16]
    if name.startswith('gold'):
        c={'gold':'#e4b447','gold_light':'#f3cd60','gold_dark':'#bb872f'}[name]
        for xx,yy in [(9,8)]: d.rectangle((x+xx,y+yy,x+xx+1,y+min(yy+5,15)),fill=c)
    elif name in ('hat','hat_light'):
        d.rectangle((x+2,y+2,x+6,y+3), fill='#45444f' if name=='hat' else '#4b4a55')

# Original layered pixel expression; see ../face_layers.js.
face=Image.open(ROOT/'face-swatch.png').crop((0,0,96,64)).convert('RGB')
atlas.paste(face,(0,0)); uv['face']=[0,0,96,64]
atlas.save(ROOT/'marisa.png')

cubes=[]
def cube(name, group, lo, hi, material, face_material=None):
    faces={k:uv[material][:] for k in ('north','east','south','west','up','down')}
    if face_material: faces['north']=uv[face_material][:]
    cubes.append(dict(name=name,group=group,lo=lo,hi=hi,faces=faces))

# 16 model units = 1 Minecraft block. Front is -Z; soles at Y=0.
cube('soft_head','head',[-6,1,-4.6],[6,9,4.5],'skin','face')
cube('left_cheek','head',[-6.65,2,-3.6],[-6,7.5,3.7],'skin')
cube('right_cheek','head',[6,2,-3.6],[6.65,7.5,3.7],'skin')
cube('crown','head',[-5.4,9,-3.8],[5.4,10,3.8],'skin')
cube('back_hair','hair',[-6.4,2.8,3.8],[6.4,9.7,5.2],'gold')
cube('back_hair_lower','hair',[-5.6,1.2,3.8],[5.6,3.5,5],'gold_dark')
for i,x in enumerate((-5.8,-3.4,-1,1.4,3.8)):
    cube('back_lock_'+str(i),'hair',[x,1.3+(i%2)*.7,4.8],[x+1.7,7,5.5],'gold' if i%2 else 'gold_light')
cube('hair_cap','hair',[-6.4,8.8,-4.6],[6.4,10.1,4.6],'gold_light')
for i,(x,w,bottom) in enumerate([(-6,1.6,6.7),(-4.4,1.7,7.35),(-2.7,1.8,7.8),(-.9,2,6.3),(1.1,1.7,7.7),(2.8,1.6,7.3),(4.4,1.6,6.6)]):
    cube('bang_'+str(i),'bangs',[x,bottom+.55,-5.05],[x+w,9.5,-4.58],'gold_light' if i%3 else 'gold')
    cube('bang_tip_'+str(i),'bangs',[x+.4,bottom-.5,-5.02],[x+w-.35,bottom+.55,-4.62],'gold_light' if i%3 else 'gold')
for side in (-1,1):
    x=-7.1 if side<0 else 5.9
    cube('side_lock_'+str(side),'hair',[x,3,-3.8],[x+1.2,9,3.5],'gold')
    cube('side_tip_'+str(side),'hair',[x+.15,1.9,-3.5],[x+1,4,-1.9],'gold_light')
    cube('side_ribbon_'+str(side),'hair',[x-.08,3.4,-3.88],[x+1.28,4.1,-2.1],'white')

# Wide stepped brim; bent tip reads as a soft witch hat with Minecraft cuboids.
cube('brim_center','hat',[-8.6,10,-6],[8.6,10.7,6],'hat')
cube('brim_sides','hat',[-9.5,10.05,-4.7],[9.5,10.65,4.7],'hat_dark')
cube('brim_front','hat',[-7.3,9.9,-6.65],[7.3,10.55,-6],'hat_dark')
cube('brim_back','hat',[-7.3,10,6],[7.3,10.6,6.6],'hat')
cube('hat_base','hat',[-6.15,10.7,-4.5],[6.15,12.3,4.5],'hat_light')
cube('hat_band','hat',[-6.22,11,-4.58],[6.22,11.85,4.58],'white')
cube('hat_taper_1','hat',[-5,12.3,-3.65],[5,14.5,3.65],'hat')
cube('hat_taper_2','hat',[-3.8,14.5,-2.8],[4,16.4,2.8],'hat')
cube('hat_taper_3','hat',[-2.2,16.4,-2],[3.4,18,2],'hat_light')
cube('hat_tip_bend','hat',[-.4,18,-1.3],[3.6,19.25,1.3],'hat')
cube('hat_tip','hat',[3.1,17.6,-1],[5.1,18.8,1],'hat')
cube('hat_tip_end','hat',[4.7,17,-.8],[5.8,18,.8],'hat_dark')
# Broad ivory bow on the front of the band, visible from the gameplay angle.
cube('bow_left_outer','bow',[-4.9,11.2,-5.35],[-3.3,14.15,-4.55],'white')
cube('bow_left_inner','bow',[-3.3,11.65,-5.5],[-1.7,13.65,-4.55],'white')
cube('bow_right_inner','bow',[-.2,11.65,-5.5],[1.4,13.65,-4.55],'white')
cube('bow_right_outer','bow',[1.4,11.2,-5.35],[3,14.15,-4.55],'white')
cube('bow_left_fold','bow',[-2.7,12.3,-5.6],[-1.7,12.75,-5.48],'white')
cube('bow_right_fold','bow',[-.2,12.3,-5.6],[.8,12.75,-5.48],'white')
cube('bow_knot','bow',[-1.75,11.9,-5.85],[-.15,13.35,-4.65],'ribbon_fold')
cube('bow_left_tail','bow',[-3.7,10.4,-5],[-2.4,11.7,-4.6],'ribbon_fold')
cube('bow_right_tail','bow',[.4,10.4,-5],[1.7,11.7,-4.6],'ribbon_fold')

# Use the approved base without the separate bottom tier.
for c in cubes:
    c['lo'][1]-=1
    c['hi'][1]-=1

groups=['head','hair','bangs','hat','bow']
elements=[]
for c in cubes:
    elements.append({'name':c['name'],'uuid':uid(c['name']),'type':'cube','from':c['lo'],'to':c['hi'],
      'origin':[0,0,0],'rotation':[0,0,0],'box_uv':False,'rescale':False,'autouv':0,'color':groups.index(c['group']),
      'faces':{k:{'uv':v,'texture':0} for k,v in c['faces'].items()}})
texture_source='data:image/png;base64,'+base64.b64encode((ROOT/'marisa.png').read_bytes()).decode()
bb={'meta':{'format_version':'4.10','model_format':'free','box_uv':False},'name':'Yukkuri Marisa',
    'model_identifier':'yukkuri_marisa','visible_box':[3,3,1], 'resolution':{'width':128,'height':128},
    'elements':elements,'outliner':[{'name':'root','uuid':uid('root'),'origin':[0,0,0],
      'children':[{'name':g,'uuid':uid('group_'+g),'origin':[0,7,0],'children':[uid(c['name']) for c in cubes if c['group']==g]} for g in groups]}],
    'textures':[{'path':'marisa.png','name':'marisa.png','id':'0','uuid':uid('texture'),'source':texture_source,
                 'mode':'bitmap','width':128,'height':128,'uv_width':128,'uv_height':128,'particle':True}]}
save_json(ROOT/'marisa.bbmodel',bb)
bones=[{'name':'root','pivot':[0,0,0]}]
for g in groups:
    bones.append({'name':g,'parent':'root','pivot':[0,7,0],'cubes':[
        {'origin':c['lo'],'size':[b-a for a,b in zip(c['lo'],c['hi'])],
         'uv':{k:{'uv':v[:2],'uv_size':[v[2]-v[0],v[3]-v[1]]} for k,v in c['faces'].items()}}
        for c in cubes if c['group']==g]})
save_json(ROOT/'marisa.geo.json',{'format_version':'1.12.0','minecraft:geometry':[{'description':{
    'identifier':'geometry.yukkuri_marisa','texture_width':128,'texture_height':128,
    'visible_bounds_width':3,'visible_bounds_height':3,'visible_bounds_offset':[0,1,0]},'bones':bones}]})

# Vanilla Java display resource pack, targeted at 1.21/1.21.1. Only CMD 98001 on paper.
pack=ROOT/'resourcepack'
save_json(pack/'pack.mcmeta',{'pack':{'pack_format':34,'description':'魔理沙模型预览 · Java 1.21–1.21.1 · 纸 CMD 98001'}})
save_json(pack/'assets/minecraft/models/item/paper.json',{'parent':'minecraft:item/generated',
    'textures':{'layer0':'minecraft:item/paper'},'overrides':[
    {'predicate':{'custom_model_data':98001},'model':'yukkuri:item/marisa'},
    {'predicate':{'custom_model_data':98002},'model':'minecraft:item/paper_plain'}]})
save_json(pack/'assets/minecraft/models/item/paper_plain.json',{'parent':'minecraft:item/generated','textures':{'layer0':'minecraft:item/paper'}})
java_elements=[]
for c in cubes:
    java_elements.append({'name':c['name'],'from':[c['lo'][0]+8,c['lo'][1],c['lo'][2]+8],
       'to':[c['hi'][0]+8,c['hi'][1],c['hi'][2]+8],
       'faces':{k:{'uv':[n/8 for n in v],'texture':'#0'} for k,v in c['faces'].items()}})
save_json(pack/'assets/yukkuri/models/item/marisa.json',{'credit':'Original model: Yukkuri ModLab',
  'texture_size':[128,128],'textures':{'0':'yukkuri:entity/marisa','particle':'yukkuri:entity/marisa'},
  'elements':java_elements,'display':{'gui':{'rotation':[15,155,0],'translation':[0,-1,0],'scale':[.75,.75,.75]},
    'ground':{'translation':[0,3,0],'scale':[.5,.5,.5]},'fixed':{'rotation':[0,180,0],'scale':[.7,.7,.7]},
    'thirdperson_righthand':{'rotation':[0,180,0],'translation':[0,3,0],'scale':[.5,.5,.5]},
    'firstperson_righthand':{'rotation':[0,150,0],'translation':[1,2,0],'scale':[.6,.6,.6]}}})
(pack/'assets/yukkuri/textures/entity').mkdir(parents=True,exist_ok=True)
atlas.save(pack/'assets/yukkuri/textures/entity/marisa.png')

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

def render(angle=-25,elevation=12,size=760):
    az=math.radians(angle); el=math.radians(elevation)
    right=np.array([math.cos(az),0,math.sin(az)])
    eye=np.array([math.sin(az)*math.cos(el),math.sin(el),-math.cos(az)*math.cos(el)])
    up=np.cross(eye,right)
    # Camera right is +X in the front view; screen projects positive Y upwards.
    up=np.array([-math.sin(az)*math.sin(el),math.cos(el),math.cos(az)*math.sin(el)])
    scale=size/27; center=np.array([0,8.8,0])
    bg=np.empty((size,size,3),dtype=np.uint8); bg[:]=[234,231,222]
    yy,xx=np.mgrid[:size,:size]
    shadow=np.exp(-(((xx-size*.5)/(size*.27))**2+((yy-size*.85)/(size*.045))**2)*2)*.18
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
    bd.text((35,22),'04 · 圆眼开心',font=title,fill='#393540')
    for i,(a,e,label) in enumerate([(0,0,'正面'),(-32,14,'四分之三视角'),(145,12,'背面 / 侧发与帽型')]):
        board.paste(render(a,e,490),(i*500+5,90))
        bd.text((i*500+35,590),label,font=small,fill='#635c59')
    board.save(ROOT/'preview-sheet.png')
    render(0,8,128).save(pack/'pack.png')
    with zipfile.ZipFile(ROOT/'marisa-java-1.21.1-resourcepack.zip','w',zipfile.ZIP_DEFLATED) as z:
        for p in pack.rglob('*'):
            if p.is_file(): z.write(p,p.relative_to(pack).as_posix())
    vertices=[]
    for c in cubes:
      for points,texcoords,normal in faces(c):
        for i in (0,1,2,0,2,3): vertices.extend([*points[i],texcoords[i][0]/128,texcoords[i][1]/128,*normal])
    data={'vertices':vertices,'texture':texture_source,'cubes':len(cubes)}
    template=(ROOT/'viewer-template.html').read_text(encoding='utf-8').replace('__VIEWER_SCRIPT__',(ROOT/'software-viewer.js').read_text(encoding='utf-8'))
    fallback='data:image/png;base64,'+base64.b64encode((ROOT/'preview.png').read_bytes()).decode()
    (ROOT/'preview.html').write_text(template.replace('__MODEL_DATA__',json.dumps(data,separators=(',',':'))).replace('__FALLBACK_IMAGE__',fallback),encoding='utf-8')
    print(f'Generated {len(cubes)} cuboids, {len(cubes)*12} triangles, 128x128 atlas.')
