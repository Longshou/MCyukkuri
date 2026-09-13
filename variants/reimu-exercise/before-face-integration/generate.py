"""Retarget the approved Marisa curves onto Reimu, with an editable full routine.

Only reads the frozen Reimu base and local snapshots of the shared motion inputs.
No Unity artwork is embedded in the distributed model or atlas.
"""
from pathlib import Path
import base64, copy, hashlib, json, math, uuid
from PIL import Image, ImageDraw

ROOT=Path(__file__).resolve().parent
BASE=ROOT.parent/'reimu-hakkei'
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
def save(name,data):
    (ROOT/name).write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
def uid(name):return str(uuid.uuid5(uuid.NAMESPACE_URL,'yukkuri/reimu-exercise/'+name))
def smooth(q):
    q=max(0,min(1,q));return q*q*(3-2*q)
def sample(keys,t,default):
    if not keys:return default[:]
    pairs=sorted((float(k),v) for k,v in keys.items())
    if t<=pairs[0][0]:return pairs[0][1][:]
    for (a,x),(b,y) in zip(pairs,pairs[1:]):
        if t<=b:
            q=(t-a)/(b-a);return [v+(w-v)*q for v,w in zip(x,y)]
    return pairs[-1][1][:]
def put(tracks,n,ch,t,v):
    tracks.setdefault(n,{}).setdefault(ch,{})[str(round(t,4))]=[0 if abs(x)<1e-8 else round(x,7) for x in v]

model=read(BASE/'reimu.bbmodel')
source=read(ROOT/'reuse/marisa-animation-rig.json')
source_tracks=source['clips']
anatomy=read(ROOT/'reuse/marisa-body-animation.bbmodel')
anatomy_bones={b['name']:b for b in source['bones']}
original_members={child:g['name'] for g in model['outliner'][0]['children'] for child in g['children']}
original_pivots={g['name']:g['origin'] for g in model['outliner'][0]['children']}
atlas=Image.open(BASE/'reimu.png').convert('RGB');face=atlas.crop((0,0,48,24));d=ImageDraw.Draw(face)
d.rectangle((0,0,47,23),fill='#ffe7b9')
# Broad, tapered double arcs observed in Hakkei U sprite 6604, redrawn as pixels.
for x in (6,27):
    d.polygon([(x,17),(x+2,14),(x+4,12),(x+9,12),(x+12,14),(x+14,17),
               (x+11,16),(x+8,15),(x+5,15),(x+2,16)],fill='#382c26')
    d.line([(x+3,15),(x+5,14),(x+8,14),(x+11,15)],fill='#fff2d5',width=1)
    d.line([(x+2,10),(x+5,9),(x+9,9),(x+12,10)],fill='#705349',width=1)
for x in (5,36):d.ellipse((x,20,x+6,22),fill='#f4bda4')
d.ellipse((20,19,27,23),fill='#805044');d.ellipse((21,20,26,23),fill='#d97a72')
atlas.paste(face,(48,0))
# Rear mark is part of the rump's skin UVs, so it cannot float off during bending.
d=ImageDraw.Draw(atlas);d.rectangle((96,0,127,31),fill='#ffe7b9')
d.line([(111,15),(114,24)],fill='#805b4e',width=1)
d.line([(114,15),(111,24)],fill='#805b4e',width=1)
atlas.save(ROOT/'reimu.png')
(ROOT/'texture.rgba').write_bytes(atlas.convert('RGBA').tobytes())
texture='data:image/png;base64,'+base64.b64encode((ROOT/'reimu.png').read_bytes()).decode()

specs=[('root',None,[0,0,0]),('body_motion','root',[0,0,-4.6]),
       ('body_stretch','body_motion',[0,0,-4.6]),('features_lift','body_motion',[0,8,0]),
       ('face_rest','features_lift',[0,5,-4.62]),('face_effort','features_lift',[0,5,-4.62])]
for name in original_pivots:
    if name.startswith('side_lock_'):
        specs.append((name,'body_motion',[0,2.625,.5]))
    elif name!='head':specs.append((name,'features_lift',original_pivots[name]))
specs.append(('crown','features_lift',[0,8,0]))
for n in ('rump_sway','rump_roll','rump'):
    b=anatomy_bones[n];specs.append((n,b['parent'],b['pivot']))
elements=[];groups=[];sections={};source_bone={}
# Use the approved front/upper-rear/rump anatomy, with Reimu's own skin palette.
skin_faces=copy.deepcopy(model['elements'][0]['faces'])
skin_faces['north']=copy.deepcopy(skin_faces['south'])
for e,n in zip(anatomy['elements'],source['groups']):
    if '_slice_' not in e['name']:continue
    part=copy.deepcopy(e);part['uuid']=uid(part['name']);part['faces']=copy.deepcopy(skin_faces)
    a,b=part['from'][1],part['to'][1]
    if e['name'].startswith('rump_round_end_'):
        part['faces']['south']['uv']=[96,(3.7-b)/3.35*32,128,(3.7-a)/3.35*32]
    bone=anatomy_bones[n]
    parent='rump' if e['name'].startswith('rump_') else 'body_stretch'
    specs.append((n,parent,bone['pivot']));sections[n]=(a,b);source_bone[n]=n
    elements.append(part);groups.append(n)
for e in model['elements']:
    group=original_members[e['uuid']]
    if group=='head' and e['name']!='crown':
        continue
    elif e['name'].startswith('side_hair_'):
        # Let the long side hair follow the body's curve instead of intersecting
        # it as a rigid board. Reuse the existing skin bones at identical heights.
        low,high=e['from'][1],e['to'][1]
        for j in range(round(low*4),round(high*4)):
            a,b=j/4,(j+1)/4;part=copy.deepcopy(e)
            part['name']=e['name']+'_layer_'+str(j);part['uuid']=uid(part['name'])
            part['from'][1]=a;part['to'][1]=b
            for side in ('north','south','east','west'):
                u,v,U,V=e['faces'][side]['uv']
                part['faces'][side]['uv']=[u,v+(high-b)/(high-low)*(V-v),U,v+(high-a)/(high-low)*(V-v)]
            elements.append(part);groups.append('soft_head_slice_'+str(j)+'_bend')
    else:
        part=copy.deepcopy(e)
        if e['name']=='back_hair':part['from'][1]=3.2
        elif e['name']=='back_hair_lower':part['from'][1]=3.1;part['to'][1]=4.0
        elif e['name'].startswith('back_lock_'):part['from'][1]=3.0+(int(e['name'][-1])%2)*.35
        elements.append(part);groups.append('crown' if e['name']=='crown' else group)
for n,rect in [('face_rest',[0,0,48,24]),('face_effort',[48,0,96,24])]:
    e=copy.deepcopy(model['elements'][0]);e.update(name=n,uuid=uid(n),**{'from':[-6,2,-4.63],'to':[6,8,-4.60]})
    if n=='face_effort':
        e['from'][2]+=.02;e['to'][2]+=.02
    e['faces']={k:copy.deepcopy(model['elements'][0]['faces']['south']) for k in e['faces']}
    e['faces']['north']['uv']=rect
    elements.append(e);groups.append(n)
nodes={n:dict(name=n,uuid=uid('bone/'+n),origin=p,children=[]) for n,parent,p in specs}
for e,n in zip(elements,groups):nodes[n]['children'].append(e['uuid'])
for n,parent,p in specs:
    if parent:nodes[parent]['children'].append(nodes[n])

defaults={'position':[0,0,0],'rotation':[0,0,0],'scale':[1,1,1]}
hidden=[.000001]*3
rest_tracks={'face_effort':{'scale':{'0.0':hidden}}}
def retarget(label):
    c=source_tracks[label];tracks=copy.deepcopy(rest_tracks)
    for n in sections:
        assert n in c['tracks'],n
        tracks[n]=copy.deepcopy(c['tracks'][n])
        if n.startswith('soft_head_slice_'):
            # Keep the lower front flush in depth and soften its width taper.
            # This removes the repeated forward-facing ledges during extension.
            for v in tracks[n].get('scale',{}).values():v[0]=1-(1-v[0])*.5;v[2]=1
    tracks['features_lift']=copy.deepcopy(c['tracks']['features_lift'])
    for n in ('side_lock_left','side_lock_right'):
        tracks[n]={ch:copy.deepcopy(keys) for ch,keys in c['tracks']['soft_head_slice_10_bend'].items() if ch!='scale'}
    return dict(duration=c['duration'],loop=c['loop'],tracks=tracks)
clips={name:retarget(name) for name in ('exercise_vertical','exercise_sides','stretch')}

# Press/raise the rear as seen in the source exercise layers. The front contact
# is the pitch pivot; the overall yaw is a 3D adaptation of the source mirroring.
press={}
for i in range(241):
    t=round(i*.02,4);cycle=min(1,int(t/2.4));q=t-cycle*2.4
    def envelope(keys):
        for (a,x),(b,y) in zip(keys,keys[1:]):
            if q<=b:return x+(y-x)*smooth((q-a)/(b-a))
        return keys[-1][1]
    turn=envelope([(0,0),(.4,1),(1.85,1),(2.4,0)])
    effort=envelope([(0,0),(.4,0),(.75,.5),(1,1),(1.5,1),(1.85,0),(2.4,0)])
    wave=source_tracks['waddle']['tracks']
    wt=(q*.75)%1.2
    sway=sample(wave['rump_sway']['rotation'],wt,[0,0,0])[1]
    rear=sample(wave['rear_hair']['rotation'],wt,[0,0,0])
    ribbon=sample(wave['hat_follow']['rotation'],wt,[0,0,0])
    put(press,'root','rotation',t,[0,(1 if cycle==0 else -1)*32*turn,0])
    put(press,'body_motion','rotation',t,[-12*effort,effort*sway*.2,0])
    put(press,'body_stretch','scale',t,[1+.025*effort,1-.10*effort,1+.02*effort])
    put(press,'features_lift','position',t,[0,-.65*effort,0])
    for n in ('rump_sway','rump_roll','rump'):
        for ch,keys in wave.get(n,{}).items():
            default=defaults[ch];v=sample(keys,wt,default)
            put(press,n,ch,t,[a+(b-a)*effort*.65 for a,b in zip(default,v)])
    # Ribbons inherit the head's motion, then use the existing small follow-through.
    for n in ('bow_left','bow_right','bow_knot'):
        put(press,n,'rotation',t,[0,0,ribbon[2]*effort*2])
    for n in ('side_lock_left','side_lock_right'):
        put(press,n,'rotation',t,[0,rear[1]*effort,0])
        put(press,n,'position',t,[0,-.25*effort,0])
    active=.60<q<1.76
    put(press,'face_rest','scale',t,hidden if active else [1,1,1])
    put(press,'face_effort','scale',t,[1,1,1] if active else hidden)
    put(press,'face_effort','position',t,[0,0,-.03 if active else 0])
clips['press']=dict(duration=4.8,loop=True,tracks=press)

# A 14 s demonstration arrangement, using the approved component timing intact.
# 0..4.8 press L/R, 4.8..7.42 vertical, rise, two side cycles, settle.
all_channels={n:set(channels) for n,channels in rest_tracks.items()}
for c in clips.values():
    for n,channels in c['tracks'].items():all_channels.setdefault(n,set()).update(channels)
def state(c,t):
    return {n:{ch:sample(c['tracks'].get(n,{}).get(ch,{}),t,hidden if n=='face_effort' and ch=='scale' else defaults[ch])
               for ch in channels} for n,channels in all_channels.items()}
rest=state(dict(tracks=rest_tracks),0);high=state(clips['exercise_sides'],0)
def blend(a,b,q):return {n:{ch:[x+(y-x)*q for x,y in zip(a[n][ch],b[n][ch])] for ch in channels} for n,channels in all_channels.items()}
full={}
for i in range(701):
    t=round(i*.02,4)
    if t<=4.8:pose=state(clips['press'],t)
    elif t<=7.42:pose=state(clips['exercise_vertical'],t-4.8)
    elif t<8:pose=blend(rest,high,smooth((t-7.42)/.58))
    elif t<12.72:pose=state(clips['exercise_sides'],(t-8)%2.36)
    elif t<13.4:pose=blend(high,rest,smooth((t-12.72)/.68))
    else:pose=rest
    for n,channels in pose.items():
        for ch,v in channels.items():put(full,n,ch,t,v)
clips={'taiso':dict(duration=14.0,loop=True,tracks=full),**clips}

def simplify(keys,tol=0.00001):
    """Remove redundant linear keys with a bounded per-channel numeric error."""
    points=sorted((float(t),v) for t,v in keys.items())
    if len(points)<3:return keys
    keep={0,len(points)-1};stack=[(0,len(points)-1)]
    while stack:
        a,b=stack.pop();ta,x=points[a];tb,y=points[b];worst=tol;index=None
        for j in range(a+1,b):
            t,v=points[j];q=(t-ta)/(tb-ta);err=max(abs(z-(u+(w-u)*q)) for z,u,w in zip(v,x,y))
            if err>worst:worst=err;index=j
        if index is not None:keep.add(index);stack.extend(((a,index),(index,b)))
    return {str(points[j][0]):points[j][1] for j in sorted(keep)}
for c in clips.values():
    for n,channels in c['tracks'].items():
        for ch,keys in channels.items():channels[ch]=simplify(keys)

model.update(name='Reimu - Un-un exercise',elements=elements,outliner=[nodes['root']],visible_box=[3,3,1])
model['textures'][0].update(source=texture,path='reimu.png',name='reimu.png')
model['animations']=[];bedrock={}
for label,c in clips.items():
    animators={};bones={}
    for n,channels in c['tracks'].items():
        kfs=[];bones[n]={}
        for ch,keys in channels.items():
            bones[n][ch]={}
            for t,v in keys.items():
                values=[-v[0],-v[1],v[2]] if ch=='rotation' else ([-v[0],v[1],v[2]] if ch=='position' else v)
                values=[round(x,7) for x in values]
                bones[n][ch][t]=values
                kfs.append(dict(channel=ch,time=float(t),interpolation='linear',data_points=[dict(zip('xyz',[format(x,'.7f') for x in values]))],uuid=uid(label+n+ch+t)))
        animators[nodes[n]['uuid']]=dict(name=n,type='bone',keyframes=kfs)
    name='animation.reimu.'+label
    model['animations'].append(dict(uuid=uid(name),name=name,loop='loop' if c['loop'] else 'once',length=c['duration'],snapping=100,animators=animators))
    bedrock[name]=dict(loop=c['loop'],animation_length=c['duration'],bones=bones)
save('reimu.bbmodel',model)
geo=read(BASE/'reimu.geo.json');bones=[]
for n,parent,p in specs:
    bone=dict(name=n,pivot=p,cubes=[])
    if parent:bone['parent']=parent
    for e,g in zip(elements,groups):
        if g!=n:continue
        bone['cubes'].append(dict(origin=e['from'],size=[b-a for a,b in zip(e['from'],e['to'])],uv={side:dict(uv=f['uv'][:2],uv_size=[f['uv'][2]-f['uv'][0],f['uv'][3]-f['uv'][1]]) for side,f in e['faces'].items()}))
    bones.append(bone)
geo['minecraft:geometry'][0]['bones']=bones
geo['minecraft:geometry'][0]['description'].update(visible_bounds_width=3,visible_bounds_height=3,visible_bounds_offset=[0,1,0])
save('reimu.geo.json',geo);save('reimu.animation.json',dict(format_version='1.8.0',animations=bedrock))
rig=dict(duration=14,loop=True,tracks=full,clips=clips,bones=[dict(name=n,parent=parent,pivot=p) for n,parent,p in specs],groups=groups,stride_units=0,
         default_hidden=['face_effort'],axes='right-handed; front=-Z; editor X/Y rotations and X positions negated')
save('animation-rig.json',rig)

def faces(e):
    x,y,z=e['from'];X,Y,Z=e['to']
    quads={'north':[(X,Y,z),(x,Y,z),(x,y,z),(X,y,z)],'south':[(x,Y,Z),(X,Y,Z),(X,y,Z),(x,y,Z)],
           'east':[(X,Y,Z),(X,Y,z),(X,y,z),(X,y,Z)],'west':[(x,Y,z),(x,Y,Z),(x,y,Z),(x,y,z)],
           'up':[(x,Y,z),(X,Y,z),(X,Y,Z),(x,Y,Z)],'down':[(x,y,Z),(X,y,Z),(X,y,z),(x,y,z)]}
    normals={'north':(0,0,-1),'south':(0,0,1),'east':(1,0,0),'west':(-1,0,0),'up':(0,1,0),'down':(0,-1,0)}
    for side,points in quads.items():
        u,v,U,V=e['faces'][side]['uv'];yield points,[(u,v),(U,v),(U,V),(u,V)],normals[side]
vertices=[]
for e in elements:
    for points,texcoords,n in faces(e):
        for i in (0,1,2,0,2,3):vertices.extend([*points[i],texcoords[i][0]/128,texcoords[i][1]/128,*n])
data=dict(vertices=vertices,texture=texture,cubes=len(elements),rig=rig)
template=(ROOT/'viewer.html.template').read_text(encoding='utf-8')
html=template.replace('__MODEL_DATA__',json.dumps(data,separators=(',',':'))).replace('__VIEWER_SCRIPT__',(ROOT/'motion.js').read_text(encoding='utf-8')+'\n'+(ROOT/'software-viewer.js').read_text(encoding='utf-8'))
fallback='data:image/png;base64,'+base64.b64encode((BASE/'preview.png').read_bytes()).decode()
(ROOT/'preview.html').write_text(html.replace('__FALLBACK_IMAGE__',fallback),encoding='utf-8')
save('build-info.json',dict(cubes=len(elements),bones=len(specs),clips={n:{'duration':c['duration'],'loop':c['loop']} for n,c in clips.items()},
                          sources={str(p.relative_to(ROOT.parent.parent)):hashlib.sha256(p.read_bytes()).hexdigest() for p in [BASE/'reimu.bbmodel',BASE/'reimu.png',ROOT/'reuse/marisa-animation-rig.json',ROOT/'reuse/marisa-body-animation.bbmodel']}))
print(f'Built {len(elements)} cubes, {len(specs)} bones, {len(clips)} animations.')
