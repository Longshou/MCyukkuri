"""Build the Marisa toilet performance from the existing editable expression rig."""
from pathlib import Path
import base64, copy, json, math, uuid
from PIL import Image, ImageDraw

ROOT = Path(__file__).resolve().parent
SOURCE = ROOT.parent / 'marisa-expressions'
SHARED = ROOT.parent / 'marisa-puff'
def read(p): return json.loads(p.read_text(encoding='utf-8-sig'))
def save(p, v): p.write_text(json.dumps(v, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
def uid(n): return str(uuid.uuid5(uuid.NAMESPACE_URL, 'marisa-poop/' + n))
def smooth(q):
    q = max(0, min(1, q))
    return q*q*(3-2*q)
def curve(keys, t):
    for (a,x),(b,y) in zip(keys, keys[1:]):
        if t <= b: return x+(y-x)*smooth((t-a)/(b-a))
    return keys[-1][1]

bb = read(SOURCE/'marisa.bbmodel')
source_rig = read(SOURCE/'animation-rig.json')
bones = source_rig['bones']
groups = source_rig['groups']
nodes = {}
def walk(node):
    nodes[node['name']] = node
    for child in node['children']:
        if isinstance(child, dict): walk(child)
walk(bb['outliner'][0])
# A scene parent keeps the released prop independent of the recovering character.
scene = dict(name='scene', uuid=uid('scene'), origin=[0,0,0], children=bb['outliner'])
bb['outliner'] = [scene]
bones.insert(0, dict(name='scene', parent=None, pivot=[0,0,0]))
next(b for b in bones if b['name']=='root').update(parent='scene', pivot=[0,0,-4.65])
nodes['root']['origin'] = [0,0,-4.65]
nodes['scene'] = scene

atlas = Image.open(SOURCE/'marisa.png').convert('RGBA')
d = ImageDraw.Draw(atlas)
# Previously unused atlas rows. Original face and outfit pixels remain untouched.
d.rectangle((0,80,127,127), fill=(0,0,0,0))
d.rectangle((96,80,103,87), fill='#392038')
d.rectangle((104,80,111,87), fill='#4c2b48')
d.rectangle((112,80,119,87), fill='#5d3855')
# Restrained bean-paste grain, not a shiny, striped brown object.
for x,y in [(98,82),(101,85),(106,84),(115,82)]: d.point((x,y), fill='#68435e')
d.line([(74,82),(82,90)], fill='#765049',width=2)
d.line([(82,82),(74,90)], fill='#765049',width=2)

def add_bone(name, parent, pivot):
    node=dict(name=name, uuid=uid(name), origin=pivot, children=[])
    nodes[parent]['children'].append(node); nodes[name]=node
    bones.append(dict(name=name,parent=parent,pivot=pivot))
def add_cube(name, group, lo, hi, uv, front_only=False):
    faces={k:dict(uv=([127,127,127.01,127.01] if front_only else uv[:]), texture=0) for k in ('north','south','east','west','up','down')}
    if front_only: faces['north']['uv']=uv[:]
    e=dict(name=name,uuid=uid('cube/'+name),type='cube',box_uv=False,from_=lo,to=hi,origin=[0,0,0],rotation=[0,0,0],rescale=False,autouv=0,color=3,faces=faces)
    e['from']=e.pop('from_')
    bb['elements'].append(e); groups.append(group); nodes[group]['children'].append(e['uuid'])

original = {e['name']:e for e in bb['elements']}
# Pixel expressions use geometry tracks, so Blockbench exports the same performance.
for j, part in enumerate(('eye_left','eye_right','mouth')):
    src=original['face_'+part]; u=j*24
    width=round((src['to'][0]-src['from'][0])*4); height=round((src['to'][1]-src['from'][1])*4)
    if part.startswith('eye'):
        points=[(u+3,3),(u+10,7),(u+3,10)] if part=='eye_left' else [(u+11,3),(u+4,7),(u+11,10)]
        d.line([(x,80+y) for x,y in points], fill='#48352e',width=2)
    else:
        # Rounded open mouth from the inspected strain face: dark cavity and red lower lip.
        d.ellipse((u+3,81,u+11,90), fill='#563033')
        d.ellipse((u+4,82,u+10,89), fill='#981e35')
        d.ellipse((u+4,86,u+10,89), fill='#e05060')
    lo=src['from'][:]; hi=src['to'][:]; lo[2]=-4.3;hi[2]=-4.299
    name='strain_'+part
    add_bone(name,'face',[(a+b)/2 for a,b in zip(lo,hi)])
    add_cube(name,name,lo,hi,[u,80,u+width,80+height],True)

# An understated X on the rear surface follows the exact same rump bone as its skin.
add_bone('rear_mark','rump',[0,1.4,6.108])
add_cube('rear_mark','rear_mark',[-.64,.76,6.108],[.64,2.04,6.118],[73,81,84,92],True)
bb['elements'][-1]['faces']['north']['uv']=[127,127,127.01,127.01]
bb['elements'][-1]['faces']['south']['uv']=[73,81,84,92]

# One continuous teardrop of filling: narrow attachment, plump end, stepped round section.
PROP_PIVOT=[0,2.2,3]
add_bone('deposit','scene',PROP_PIVOT)
sections=[(-1.45,-1.15,.32),(-1.15,-.80,.51),(-.80,-.4,.72),(-.4,0,.94),(0,.4,1.07),(.4,.8,1.12),(.8,1.15,1.02),(1.15,1.43,.78),(1.43,1.62,.46)]
for i,(z0,z1,radius) in enumerate(sections):
    for band in range(5):
        # Overlapping bands yield a solid rounded cross-section with no disconnected grains.
        a=-1+band*.4;b=a+.4
        half=radius*math.sqrt(1-((a+b)/2)**2)
        height=.78*radius
        u=104 if band<3 else 112
        add_cube(f'deposit_slice_{i}_{band}','deposit',[-half,2.2+height*a,3+z0],[half,2.2+height*b,3+z1],[u,80,u+8,88])
deposit_start=next(i for i,g in enumerate(groups) if g=='deposit')
deposit_count=len(groups)-deposit_start

tracks={}
def put(n,ch,t,v):
    tracks.setdefault(n,{}).setdefault(ch,{})[str(t)]=[round(x,7) for x in v]
def character_point(p, t):
    prep=curve([(0,0),(.35,0),(1.1,1),(3.55,1),(4.65,0),(6.4,0)],t)
    effort=curve([(0,0),(.5,0),(1.1,.7),(1.45,1),(1.8,.65),(2.15,1),(2.5,.65),(2.85,1),(3.3,1),(3.65,0),(6.4,0)],t)
    sx=1+.045*effort; sy=1-.095*effort; sz=1+.025*effort
    angle=math.radians(-7*prep)
    x=p[0]*sx;y=p[1]*sy;z=(p[2]+4.65)*sz
    return [x,math.cos(angle)*y-math.sin(angle)*z,math.sin(angle)*y+math.cos(angle)*z-4.65],prep,effort

release=3.55; land=4.13
def rear_point(t):
    _,prep,effort=character_point([0,1.4,6.118],t)
    tremble=math.sin(t*math.pi*12)*.035*effort*smooth((t-.95)/.3)*(1-smooth((t-3.2)/.35))
    return character_point([0,1.4+tremble,6.118+.16*effort],t)[0]
def attached_center(t,grow):
    # Root scaling and pitch are included: the proximal tip stays at the X, even while trembling.
    point=rear_point(t);_,prep,_=character_point([0,0,0],t)
    theta=math.radians(-7*prep);distance=1.45*grow-.06
    return [0,point[1]-math.sin(theta)*distance,point[2]+math.cos(theta)*distance]
release_center=attached_center(release,1)
for i in range(321):
    t=round(i*.02,2)
    exit_point,prep,effort=character_point([0,1.4,6.15],t)
    put('root','rotation',t,[-7*prep,0,0])
    put('root','scale',t,[1+.045*effort,1-.095*effort,1+.025*effort])
    tremble=math.sin(t*math.pi*12)*.035*effort*smooth((t-.95)/.3)*(1-smooth((t-3.2)/.35))
    put('rump_roll','position',t,[0,tremble,.16*effort])
    put('hat_follow','rotation',t,[1.8*prep+.7*math.sin(t*8)*effort,0,.35*math.sin(t*5)*effort])
    facial=curve([(0,0),(.65,0),(1.0,1),(3.5,1),(3.75,0),(6.4,0)],t)
    active=1 if .98<=t<3.65 else 0
    blink=curve([(0,0),(4.2,0),(4.4,1),(4.65,0),(5.05,0),(5.2,1),(5.4,0),(6.4,0)],t)
    for side in ('left','right'):
        put('face_eye_'+side,'scale',t,[1,max(.10,1-.90*max(facial,blink)),1])
        put('face_eye_'+side,'position',t,[0,0,.7*active])
        put('strain_eye_'+side,'position',t,[0,.12,-.38*active])
        put('face_brow_'+side,'rotation',t,[0,0,(9 if side=='left' else -9)*effort])
        put('face_blush_'+side,'scale',t,[1+.12*effort,1+.35*effort,1])
    put('face_mouth','position',t,[0,0,.7*active])
    put('strain_mouth','position',t,[0,0,-.39*active])
    put('strain_mouth','scale',t,[.78+.22*effort,.8+.24*effort,1])
    put('face_mouth','scale',t,[1+.25*smooth((t-3.6)/.25)*(1-smooth((t-4.7)/.5)),1,1])
    if t<1.55:
        center=PROP_PIVOT;scale=[.001]*3;rot=0
    elif t<release:
        grow=curve([(1.55,0),(2.0,.27),(2.35,.22),(2.8,.62),(3.12,.58),(3.55,1)],t)
        scale=[max(.001,.25+.75*grow)]*2+[max(.001,grow)]
        center=attached_center(t,grow)
        rot=-7*prep
    else:
        q=max(0,min(1,(t-release)/(land-release)))
        # Continuous release followed by a gentle landing; bottom never crosses Y=0.
        center=[0,release_center[1]+(.90-release_center[1])*q*q,release_center[2]+1.1*q]
        bounce=curve([(4.13,0),(4.20,1),(4.38,0),(6.4,0)],t) if t>=land else 0
        scale=[1+.16*bounce,1-.16*bounce,1+.10*bounce]
        rot=-7*(1-smooth(q))
    put('deposit','position',t,[a-b for a,b in zip(center,PROP_PIVOT)])
    put('deposit','scale',t,scale);put('deposit','rotation',t,[rot,0,0])

clip=dict(duration=6.4,loop=False,tracks=tracks)
rig=dict(duration=6.4,loop=False,tracks=tracks,clips={'poop':clip},bones=bones,groups=groups,axes=source_rig['axes'])
save(ROOT/'animation-rig.json',rig)
bb['name']='Marisa - 排嗯嗯';bb['animations']=[]
animators={};bedrock={}
for name,channels in tracks.items():
    keys=[];bedrock[name]={}
    for ch,values in channels.items():
        bedrock[name][ch]={}
        for t,v in values.items():
            v=([-v[0],-v[1],v[2]] if ch=='rotation' else [-v[0],v[1],v[2]] if ch=='position' else v)
            bedrock[name][ch][t]=v
            keys.append(dict(channel=ch,time=float(t),interpolation='linear',data_points=[dict(zip('xyz',map(str,v)))],uuid=uid(name+ch+t)))
    animators[nodes[name]['uuid']]=dict(name=name,type='bone',keyframes=keys)
bb['animations'].append(dict(uuid=uid('poop'),name='animation.marisa.poop',loop='once',length=6.4,snapping=50,animators=animators))
atlas.save(ROOT/'marisa.png')
(ROOT/'texture.rgba').write_bytes(atlas.tobytes())
texture='data:image/png;base64,'+base64.b64encode((ROOT/'marisa.png').read_bytes()).decode()
bb['textures'][0].update(source=texture,path='marisa.png')
bb['visible_box']=[4,4,1]
save(ROOT/'marisa.bbmodel',bb)
save(ROOT/'marisa.animation.json',dict(format_version='1.8.0',animations={'animation.marisa.poop':dict(loop=False,animation_length=6.4,bones=bedrock)}))
geometry=[]
for b in bones:
    out=dict(name=b['name'],pivot=b['pivot'],cubes=[])
    if b['parent']:out['parent']=b['parent']
    for e,g in zip(bb['elements'],groups):
        if g==b['name']:
            out['cubes'].append(dict(origin=e['from'],size=[v-u for u,v in zip(e['from'],e['to'])],uv={k:dict(uv=f['uv'][:2],uv_size=[f['uv'][2]-f['uv'][0],f['uv'][3]-f['uv'][1]]) for k,f in e['faces'].items()}))
    geometry.append(out)
save(ROOT/'marisa.geo.json',{'format_version':'1.12.0','minecraft:geometry':[{'description':dict(identifier='geometry.marisa_poop',texture_width=128,texture_height=128,visible_bounds_width=4,visible_bounds_height=4,visible_bounds_offset=[0,1,0]),'bones':geometry}]})

# Standalone filling asset, centered and resting on Y=0 for reuse as a separate object.
filling=copy.deepcopy(bb)
filling.update(name='Azuki filling - 馅料团',model_identifier='azuki_filling',animations=[],visible_box=[1,1,0])
filling['elements']=copy.deepcopy(bb['elements'][deposit_start:])
for e in filling['elements']:
    for key in ('from','to'):
        e[key][1]+=0.8736-PROP_PIVOT[1];e[key][2]-=PROP_PIVOT[2]
filling['outliner']=[dict(name='filling',uuid=uid('filling'),origin=[0,0,0],children=[e['uuid'] for e in filling['elements']])]
filling['textures'][0]['path']='filling.png';filling['textures'][0]['name']='filling.png'
save(ROOT/'filling.bbmodel',filling)
atlas.save(ROOT/'filling.png')
filling_geo=copy.deepcopy(geometry[-1]);filling_geo.update(name='filling',pivot=[0,0,0]);filling_geo.pop('parent',None)
for c in filling_geo['cubes']:
    c['origin'][1]+=0.8736-PROP_PIVOT[1];c['origin'][2]-=PROP_PIVOT[2]
save(ROOT/'filling.geo.json',{'format_version':'1.12.0','minecraft:geometry':[{'description':dict(identifier='geometry.azuki_filling',texture_width=128,texture_height=128,visible_bounds_width=1,visible_bounds_height=1,visible_bounds_offset=[0,0,0]),'bones':[filling_geo]}]})

vertices=[]
for e in bb['elements']:
    x,y,z=e['from'];X,Y,Z=e['to']
    quads={'north':[(X,Y,z),(x,Y,z),(x,y,z),(X,y,z)],'south':[(x,Y,Z),(X,Y,Z),(X,y,Z),(x,y,Z)],'east':[(X,Y,Z),(X,Y,z),(X,y,z),(X,y,Z)],'west':[(x,Y,z),(x,Y,Z),(x,y,Z),(x,y,z)],'up':[(x,Y,z),(X,Y,z),(X,Y,Z),(x,Y,Z)],'down':[(x,y,Z),(X,y,Z),(X,y,z),(x,y,z)]}
    normals={'north':[0,0,-1],'south':[0,0,1],'east':[1,0,0],'west':[-1,0,0],'up':[0,1,0],'down':[0,-1,0]}
    for face,pts in quads.items():
        u,v,U,V=e['faces'][face]['uv'];uvs=[(u,v),(U,v),(U,V),(u,V)]
        for j in (0,1,2,0,2,3):vertices.extend([*pts[j],uvs[j][0]/128,uvs[j][1]/128,*normals[face]])
data=dict(vertices=vertices,texture=texture,cubes=len(bb['elements']),depositStart=deposit_start,depositCount=deposit_count,rig=rig)
save(ROOT/'preview-data.json',data)
(ROOT/'motion.js').write_text((SHARED/'motion.js').read_text(encoding='utf-8'),encoding='utf-8')
renderer=(SHARED/'software-viewer.js').read_text(encoding='utf-8').split("if(typeof document!=='undefined'){")[0]
(ROOT/'renderer.js').write_text(renderer,encoding='utf-8')
template=(ROOT/'viewer-template.html').read_text(encoding='utf-8')
script=(ROOT/'motion.js').read_text(encoding='utf-8')+'\n'+renderer+'\n'+(ROOT/'viewer.js').read_text(encoding='utf-8')
(ROOT/'preview.html').write_text(template.replace('__DATA__',json.dumps(data,separators=(',',':'))).replace('__SCRIPT__',script),encoding='utf-8')
print(f'Built {len(bb["elements"])} cubes, {len(bones)} bones, one 6.4-second performance.')
