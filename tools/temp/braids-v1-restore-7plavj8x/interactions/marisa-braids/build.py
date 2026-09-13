"""Compile seven deterministic interactions from the current ordinary Marisa library.

Source recipes + a reusable reach solver; sampled tracks are exports, never sources.
Run the parent library build first when standard components change.
"""
from pathlib import Path
import sys, json, copy, math, uuid, base64, io, hashlib
from PIL import Image

HERE = Path(__file__).resolve().parent
LIB = HERE.parents[1]
sys.path.insert(0, str(LIB))
from geometry import vertices

def read(p): return json.loads(p.read_text(encoding='utf-8-sig'))
def save(p, data): p.write_text(json.dumps(data, ensure_ascii=False, separators=(',', ':')), encoding='utf-8')
def add(a,b): return [x+y for x,y in zip(a,b)]
def sub(a,b): return [x-y for x,y in zip(a,b)]
def mul(a,s): return [x*s for x in a]
def length(a): return math.sqrt(sum(x*x for x in a))
def mix(a,b,u): return add(mul(a,1-u),mul(b,u))
def smooth(t): t=max(0,min(1,t)); return t*t*(3-2*t)
def curve(keys,t):
    if t<=keys[0][0]: return keys[0][1]
    for (a,x),(b,y) in zip(keys,keys[1:]):
        if t<=b: return mix(x,y,smooth((t-a)/(b-a)))
    return keys[-1][1]
def rotate(v,angles):
    x,y,z=[math.radians(x) for x in angles]
    X,Y,Z=v;Y,Z=Y*math.cos(x)-Z*math.sin(x),Y*math.sin(x)+Z*math.cos(x)
    X,Z=X*math.cos(y)+Z*math.sin(y),-X*math.sin(y)+Z*math.cos(y)
    return [X*math.cos(z)-Y*math.sin(z),X*math.sin(z)+Y*math.cos(z),Z]
def aim(a,b):
    # Shortest-arc swing from -Y, avoiding roll when a forward-pointing lock
    # crosses the root's X/Y plane during hat care.
    d=sub(b,a);d=mul(d,1/max(length(d),1e-8))
    q=[-d[2],0,d[0],1-d[1]];norm=length(q)
    if norm<1e-8:return [0,0,180]
    x,y,z,w=mul(q,1/norm)
    return [math.degrees(math.atan2(2*(w*x+y*z),1-2*(x*x+y*y))),
            math.degrees(math.asin(max(-1,min(1,2*(w*y-z*x))))),
            math.degrees(math.atan2(2*(w*z+x*y),1-2*(y*y+z*z)))]

def reach_blend(side,target,weight):
    """Sweep a taut lock about its root instead of passing through the root."""
    root=[-6.5 if side=='left' else 6.5,8.48,-2.4]
    delta=sub(target,root);distance=length(delta);direction=mul(delta,1/max(distance,1e-8))
    angle=math.acos(max(-1,min(1,-direction[1])))
    if angle<1e-6:return mix(rest_ends[side],target,weight)
    axis=[direction[0],0,direction[2]];axis=mul(axis,1/max(length(axis),1e-8))
    heading=add(mul([0,-1,0],math.cos(angle*weight)),mul(axis,math.sin(angle*weight)))
    return add(root,mul(heading,7.526*(1-weight)+distance*weight))

recipes=read(HERE/'recipes.json')
base_html=(LIB/'assembled/marisa/index.html').read_text(encoding='utf-8')
base_data=json.JSONDecoder().raw_decode(base_html.split('const DATA=',1)[1])[0]
bb=read(LIB/'assembled/marisa/marisa.bbmodel')
base_rig=base_data['model']['rig']
source_elements=bb['elements']
elements=[]; bones=[]; groups=[]
def bone(name,pivot=(0,0,0),parent=None):
    bones.append(dict(name=name,pivot=list(pivot),parent=parent))
def element(e,group):
    e=copy.deepcopy(e);e.pop('uuid',None);elements.append(e);groups.append(group)
def box(name,lo,hi,color,group):
    u=128+color*4
    element(dict(name=name,type='cube',**{'from':lo,'to':hi},origin=[0,0,0],rotation=[0,0,0],box_uv=False,
        faces={side:dict(uv=[u,128,u+3,131],texture=0) for side in ['north','south','east','west','up','down']}),group)

# Keep all standard body, hair, face and accessory geometry. Only articulated locks
# are subdivided, retaining their original outer bounds and cropped side UVs.
bone('adult')
bone('hat',[0,10,-3],'adult')
bone('hat_front',[0,10,-6],'hat')
bone('bow',[0,12,-5],'hat')
for e,g in zip(source_elements,base_rig['groups']):
    if g.startswith(('hand_','puff_','anatomy_')) or 'contact_' in g: continue
    element(e,'hat_front' if e['name']=='brim_front' else 'bow' if g=='bow' else 'hat' if g=='hat' else 'adult')
rest_ends={}
for side,sign in [('left',-1),('right',1)]:
    x=sign*6.5;root=[x,8.48,-2.4];end=[x,.954,-2.4];rest_ends[side]=end
    src=next(e for e in source_elements if e['name']==f'side_lock_{sign}')
    for i in range(6):
        top=8.48-i*6.36/6;bottom=8.48-(i+1)*6.36/6
        n=f'braid_{side}_{i}';bone(n,[x,top,-2.4],'adult');e=copy.deepcopy(src);e['name']=n
        e['from'][1]=bottom;e['to'][1]=top
        for f in ['north','south','east','west']:
            u,v,U,V=src['faces'][f]['uv'];e['faces'][f]['uv']=[u,v+(V-v)*i/6,U,v+(V-v)*(i+1)/6]
        element(e,n)
    for part in ['tip','ribbon']:
        n=f'braid_{side}_{part}';src=next(e for e in source_elements if e['name']==f'side_{part}_{sign}')
        bone(n,[x,3.18 if part=='tip' else 3.286,-2.4],'adult');element(src,n)
    bone(f'grip_{side}',end,'adult')

for side,sign in [('left',-1),('right',1)]:
    p=rest_ends[side];bone('coil_'+side,p,'grip_'+side)
    hair=next(e for e in source_elements if e['name']==f'side_tip_{sign}')
    for i,(lo,hi) in enumerate([([-.72,-.3,-.5],[-.43,.35,.35]),([.43,-.3,-.5],[.72,.35,.35]),([-.72,-.4,-.6],[.72,-.15,-.3])]):
        e=copy.deepcopy(hair);e['name']=f'grasp_{side}_{i}';e['from']=add(p,lo);e['to']=add(p,hi);element(e,'coil_'+side)

palette=[[113,120,127],[155,161,158],[79,89,96],[176,208,220],[230,241,235],[184,137,53],[99,66,37],[64,115,50],[99,150,58],[121,83,49],[197,169,117],[231,222,197],[158,149,123]]
bone('stone');box('stone_body',[-.8,-.6,-.65],[.8,.6,.65],0,'stone');box('stone_top',[-.55,.6,-.4],[.4,.8,.45],1,'stone')
bone('sword');box('sword_grip',[-.22,-.8,-.22],[.22,.8,.22],6,'sword');box('sword_guard',[-1,.8,-.32],[1,1.1,.32],5,'sword');box('sword_blade',[-.38,1.1,-.12],[.38,5.5,.12],3,'sword');box('sword_edge',[-.13,1.1,-.15],[.13,5.6,.15],4,'sword');box('sword_tip',[-.22,5.5,-.10],[.22,6,.10],4,'sword');box('sword_pommel',[-.34,-1.05,-.3],[.34,-.7,.3],5,'sword')
bone('grass');box('grass_stem',[-.15,-.5,-.15],[.15,1.5,.15],7,'grass')
for i in range(5):
    box(f'grass_leaf_{i}',[-.8+i*.32,.2+(i%2)*.3,-.25],[-.6+i*.32,1.8+(i%3)*.3,.18],8 if i%2 else 7,'grass')
bone('grass_root',(0,0,0),'grass');box('grass_roots',[-.45,-.85,-.4],[.45,-.5,.4],9,'grass_root')
for i in range(5):
    bone(f'dirt_{i}');box(f'dirt_{i}',[-.12]*3,[.12]*3,9,f'dirt_{i}')
for i in range(4):
    bone(f'hat_dust_{i}',[0,10,-3],'hat')
    box(f'hat_dust_{i}',[.2+i*1.2,10.125,-6.5],[.7+i*1.2,10.19,-6.1],9,f'hat_dust_{i}')
bone('child')
for e,g in zip(source_elements,base_rig['groups']):
    if g.startswith(('puff_','anatomy_')) or 'contact_' in g: continue
    e=copy.deepcopy(e);e['name']='child_'+e['name'];element(e,'child')
bone('floor');box('ground',[-15,-.23,-14],[15,-.05,11],11,'floor')
for i in range(5):
    box(f'ground_line_{i}',[-14+i*6,-.049,-14],[-13.98+i*6,-.04,11],12,'floor')

def reach(side,target):
    """Taut strand with a shallow outward bow, never a length-preserving loop."""
    sign=-1 if side=='left' else 1;start=[sign*6.5,8.48,-2.4]
    # When reaching across the face, bring the attachment forward within the
    # existing side hair. The tip/prop trajectory and the taut strand stay intact.
    clearance=smooth((6.5-sign*target[0]-.3)/2)*smooth((-target[2]-3)/2)
    start=add(start,[sign*.2*clearance,0,-2.8*clearance])
    dist=length(sub(target,start))
    bow=min(.08,dist*.0125)*smooth(length(sub(target,rest_ends[side]))/2)
    def at(u):
        return add(mix(start,target,u),[sign*bow*4*u*(1-u),0,0])
    result={}
    for i in range(6):
        u=i*1.06/7.526;v=(i+1)*1.06/7.526;a,b=at(u),at(v)
        result[f'braid_{side}_{i}']=dict(position=sub(a,[sign*6.5,8.48-i*1.06,-2.4]),rotation=aim(a,b),scale=[1-.1*clearance,length(sub(b,a))/1.06,1-.1*clearance])
    a,b=at(5.3/7.526),at(1)
    result[f'braid_{side}_tip']=dict(position=sub(a,[sign*6.5,3.18,-2.4]),rotation=aim(a,b),scale=[1-.1*clearance,length(sub(b,a))/2.226,1-.1*clearance])
    a,b=at(5.194/7.526),at(5.5/7.526)
    result[f'braid_{side}_ribbon']=dict(position=sub(a,[sign*6.5,3.286,-2.4]),rotation=aim(a,b))
    result[f'grip_{side}']=dict(position=sub(target,rest_ends[side]))
    return result

HAT_GRIPS=[[-8.35,10,-3],[8.35,10,-3]]
HAT_CENTER=[0,10,1.1]
def hat_pose(action,t):
    if action=='remove_hat':
        keys=[(0,[0,0,0,0]),(1.7,[0,0,0,0]),(2.7,[0,2.7,-1,0]),(4.9,[0,-1.95,-5.15,-50]),(5.6,[0,-1.95,-5.15,-50]),(7.7,[0,2.7,-1,0]),(8.2,[0,0,0,0])]
        p=curve(keys,t);return p[:3],[p[3],0,0]
    # Turn above the body, then lower an upright hat in front, with its bow
    # facing Marisa. Reverse only after wiping; never rotate through the body.
    center=curve([(0,HAT_CENTER),(1.7,HAT_CENTER),(2.8,[0,13.4,.1]),(4.4,[0,11.5,-10.5]),(9.9,[0,11.5,-10.5]),(11.8,[0,.566,-13.7]),(15.2,[0,.566,-13.7]),(17.2,[0,11.5,-10.5]),(22.7,[0,11.5,-10.5]),(24.3,[0,13.4,.1]),(25.3,HAT_CENTER)],t)
    rotation=curve([(0,[0,0,0]),(4.4,[0,0,0]),(9.9,[0,180,0]),(17.2,[0,180,0]),(22.7,[0,0,0])],t)
    pivot=[0,10,-3]
    return sub(sub(center,pivot),rotate(sub(HAT_CENTER,pivot),rotation)),rotation
def hat_point(p,position,rotation):return add(add(rotate(sub(p,[0,10,-3]),rotation),[0,10,-3]),position)

def care_rim_point(side,t,rotation):
    """Slide along a smooth path inside the rim, without crossing braids."""
    sign=-1 if side=='left' else 1
    direction=rotate([sign*.78,0,.626],[0,-rotation[1],0])
    # Tracing the stepped cuboid outline itself introduces jumps at its notches.
    # This inset ellipse remains on solid brim geometry through the whole turn.
    radius=1/math.sqrt((direction[0]/8.7)**2+(direction[2]/6.8)**2)
    point=add(HAT_CENTER,mul(direction,radius))
    weight=smooth((t-2.8)/1.6)*(1-smooth((t-22.7)/1.6))
    return mix(HAT_GRIPS[0 if side=='left' else 1],point,weight)

def ordinary_state(action,t):
    targets=copy.deepcopy(rest_ends);o={};contacts={};visible=recipes['actions'][action]['prop']
    for n in ['stone','sword','grass','child']+[f'dirt_{i}' for i in range(5)]+[f'hat_dust_{i}' for i in range(4)]:o[n]={'scale':[.000001]*3}
    if action in ['pick_stone','swing_sword','pull_grass']:
        n={'pick_stone':'stone','swing_sword':'sword','pull_grass':'grass'}[action]
        ground=[8,.85,-6];end=[10,.85,-6] if n=='grass' else ground
        if n=='stone':ks=[(0,ground),(1.6,ground),(2.7,[9,5.7,-6]),(4,[9,5.7,-6]),(5.5,end)]
        elif n=='sword':ks=[(0,ground),(1.6,ground),(2.6,[9.4,6.5,-5]),(3.15,[8.5,5,-7]),(3.6,[9,6.1,-5]),(4.2,[8.7,4.9,-7]),(4.8,[9.5,5.7,-5]),(5.8,end)]
        else:ks=[(0,ground),(2.5,ground),(2.85,[8.4,3.6,-6]),(3.25,[8.5,3.9,-6]),(3.5,[8.7,3.5,-6]),(3.8,[8.4,3.9,-6]),(4.1,[8.7,3.6,-6]),(4.6,[8.6,3.8,-6]),(5.6,end)]
        p=curve(ks,t);o[n]={'position':p,'scale':[1]*3}
        if n=='sword':o[n]['rotation']=curve([(0,[0,0,-68]),(1.6,[0,0,-68]),(2.6,[-15,0,25]),(3.15,[25,0,-65]),(3.6,[-15,0,25]),(4.2,[25,0,-65]),(4.8,[0,0,-10]),(5.8,[0,0,-68])],t)
        grab=1.6 if n!='grass' else 1.8;release=5.5 if n=='stone' else 5.8 if n=='sword' else 5.6
        blend=smooth((t-.4)/(grab-.4))*(1-smooth((t-release)/.65))
        targets['right']=reach_blend('right',p,blend)
        contacts['right']=n if grab<=t<=release else None
        if n=='grass':
            o['grass_root']={'scale':[1]*3 if t>=2.6 else [.000001]*3}
            for i in range(5):
                age=t-(2.8+i*.10)
                if 0<=age<=1.5:o[f'dirt_{i}']={'position':[8+(i-2)*age*.4,max(.1,3.2-age*age*3.8),-6+(i%2-.5)*age],'scale':[1]*3}
    elif action in ['remove_hat','care_hat']:
        pos,rot=hat_pose(action,t);o['hat']={'position':pos,'rotation':rot}
        end=recipes['actions'][action]['release_time']
        weight=smooth((t-.4)/1.3)*(1-smooth((t-end)/1.0))
        for side,point in zip(['left','right'],HAT_GRIPS):
            if action=='care_hat':point=care_rim_point(side,t,rot)
            target=hat_point(point,pos,rot)
            if action=='care_hat' and side=='right':
                # Front surface now faces the worker. Wipe the band, then the bow;
                # the left braid steadies the rim while the ground bears the hat.
                local=curve([(0,point),(11.8,point),(12.3,[-5.5,11.3,-4.85]),(13.5,[-4.5,11.3,-4.85]),(14,[-4,12.3,-5.7]),(14.7,[-2.8,12.3,-5.7]),(15.2,point)],t)
                target=hat_point(local,pos,rot)
            targets[side]=reach_blend(side,target,weight)
            contacts[side]='hat' if 1.7<=t<=end else None
    elif action=='wave_goodbye':
        targets['right']=curve([(0,rest_ends['right']),(.6,rest_ends['right']),(1.4,[11.2,10,-3.5]),(1.8,[9.7,12,-3.5]),(2.2,[12,10,-3.5]),(2.6,[9.7,12,-3.5]),(3,[12,10,-3.5]),(3.4,[9.7,12,-3.5]),(3.8,[11.2,10,-3.5]),(4.8,rest_ends['right'])],t)
    for side in ['left','right']:
        o.update(reach(side,targets[side]))
        amount=1 if contacts.get(side) else .000001
        o['coil_'+side]={'scale':[amount]*3}
    if action=='care_hat':
        o['floor']={'scale':[1,1,1.3],'position':[0,0,-4]}
        tidy=smooth((t-14)/.3)*(1-smooth((t-14.7)/.5))
        o['bow']={'rotation':[0,0,tidy*2]}
        for i in range(4):
            remaining=max(.000001,1-smooth((t-12.3-i*.25)/.3))
            # Reposition the source flecks on the exposed band that is wiped.
            o[f'hat_dust_{i}']={'scale':[remaining]*3,'position':[-5.8-i*.85,1.15,1.45]}
    if action=='wave_goodbye':
        nod=smooth((t-3.8)/.25)*(1-smooth((t-4.15)/.35))
        o['adult']={'scale':[1,1-.035*nod,1]}
    return o,dict(targets=targets,contacts=contacts)

# Child starts in front, where both side locks can support it. Land on the front
# brim, using the shelf clear of the crown. The per-action target override below
# is shared by exports and preview, so scrubbing cannot detach a carried child.
def state(action,t):
    if action!='lift_child':return ordinary_state(action,t)
    o,meta=ordinary_state('wave_goodbye',0)
    p=curve([(0,[0,0,-7]),(2,[0,0,-7]),(3.8,[0,6.5,-8]),(5.1,[0,11,-8]),(6.2,[0,10.282,-7.5])],t)
    shelf=smooth((t-3.5)/1.5)
    o['hat_front']={'scale':[1,1,1+3.65*shelf],'position':[0,.159*shelf,0]}
    o['child']={'position':p,'scale':[recipes['child_scale']]*3}
    for side,sign in [('left',-1),('right',1)]:
        target=add(p,[sign*1.7,.5,0]);w=smooth((t-.5)/1.5)*(1-smooth((t-6.2)/1))
        target=reach_blend(side,target,w);o.update(reach(side,target));meta['targets'][side]=target;meta['contacts'][side]='child' if 2<=t<=6.2 else None
        o['coil_'+side]={'scale':[1]*3 if 2<=t<=6.2 else [.000001]*3}
    return o,meta

clips={}; samples={}
for action,recipe in recipes['actions'].items():
    tracks={};duration=recipe['duration'];samples[action]=[]
    for i in range(round(duration*recipes['sample_rate'])+1):
        t=round(i/recipes['sample_rate'],6);pose,meta=state(action,t);samples[action].append(dict(time=t,**meta))
        for b in bones:
            values=pose.get(b['name'],{})
            for ch,default in [('position',[0]*3),('rotation',[0]*3),('scale',[1]*3)]:
                tracks.setdefault(b['name'],{}).setdefault(ch,{})[str(t)]=[round(v,7) for v in values.get(ch,default)]
    # Euler equivalent angles must stay continuous before linear interpolation in
    # both the viewer and exported engines; otherwise +/-180 makes a full spin.
    for channels in tracks.values():
        previous=None
        for values in channels['rotation'].values():
            if previous is not None:
                candidates=[values[:],[values[0]+180,180-values[1],values[2]+180]]
                for candidate in candidates:
                    for axis in range(3):candidate[axis]+=360*round((previous[axis]-candidate[axis])/360)
                values[:]=min(candidates,key=lambda v:sum((a-b)**2 for a,b in zip(v,previous)))
            previous=values
    clips[action]=dict(label=recipe['label'],duration=duration,loop=False,tracks=tracks)

atlas=Image.new('RGBA',(256,256));atlas.paste(Image.open(LIB/'assembled/marisa/marisa.png'),(0,0))
for i,color in enumerate(palette):atlas.paste((*color,255),(128+i*4,128,132+i*4,132))
OUT=HERE/'assembled';OUT.mkdir(exist_ok=True);atlas.save(OUT/'marisa-braids.png')
buf=io.BytesIO();atlas.save(buf,format='PNG');texture='data:image/png;base64,'+base64.b64encode(buf.getvalue()).decode()
uid=lambda n:str(uuid.uuid5(uuid.NAMESPACE_URL,'mcyukkuri/marisa-braids/'+n))
nodes={b['name']:dict(name=b['name'],uuid=uid('bone/'+b['name']),origin=b['pivot'],children=[]) for b in bones}
for e,g in zip(elements,groups):e['uuid']=uid(e['name']);nodes[g]['children'].append(e['uuid'])
for b in bones:
    if b['parent']:nodes[b['parent']]['children'].append(nodes[b['name']])
animations=[];exports={}
for action,clip in clips.items():
    animators={};channels={}
    for n,chs in clip['tracks'].items():
        animators[uid('bone/'+n)]=dict(name=n,type='bone',keyframes=[]);channels[n]={}
        for ch,keys in chs.items():
            # Omit constant default channels, preserving explicit visibility.
            if all(v==([1]*3 if ch=='scale' else [0]*3) for v in keys.values()):continue
            channels[n][ch]={}
            for t,v in keys.items():
                val=[-v[0],-v[1],v[2]] if ch=='rotation' else [-v[0],v[1],v[2]] if ch=='position' else v
                channels[n][ch][t]=val;animators[uid('bone/'+n)]['keyframes'].append(dict(channel=ch,time=float(t),interpolation='linear',data_points=[dict(zip('xyz',map(str,val)))],uuid=uid(action+n+ch+t)))
    name='animation.marisa.'+action
    animations.append(dict(name=name,uuid=uid(name),loop='once',length=clip['duration'],snapping=20,animators=animators))
    exports[name]=dict(loop=False,animation_length=clip['duration'],bones=channels)
model=dict(meta=dict(format_version='4.10',model_format='free',box_uv=False),name='普通魔理沙 · 辫子互动',resolution=dict(width=256,height=256),elements=elements,outliner=[nodes[b['name']] for b in bones if b['parent'] is None],textures=[dict(name='marisa-braids.png',path='marisa-braids.png',id='0',uuid=uid('texture'),source=texture,mode='bitmap',width=256,height=256,uv_width=256,uv_height=256)],animations=animations)
save(OUT/'marisa-braids.bbmodel',model)
save(OUT/'marisa-braids.animation.json',dict(format_version='1.8.0',animations=exports))
geo=[]
for b in bones:
    g=dict(name=b['name'],pivot=b['pivot'],cubes=[])
    if b['parent']:g['parent']=b['parent']
    for e,n in zip(elements,groups):
        if n==b['name']:g['cubes'].append(dict(origin=e['from'],size=sub(e['to'],e['from']),uv={k:dict(uv=f['uv'][:2],uv_size=sub(f['uv'][2:],f['uv'][:2])) for k,f in e['faces'].items()}))
    geo.append(g)
save(OUT/'marisa-braids.geo.json',{'format_version':'1.12.0','minecraft:geometry':[dict(description=dict(identifier='geometry.marisa_braids',texture_width=256,texture_height=256,visible_bounds_width=5,visible_bounds_height=5,visible_bounds_offset=[0,1,0]),bones=geo)]})
rig=dict(bones=bones,groups=groups,clips=clips)
save(OUT/'animation-rig.json',rig)
data=dict(model=dict(vertices=[v for e in elements for v in vertices(e,256)],rig=rig),texture=texture,actions=recipes['actions'],samples=samples)
runtime='const DATA='+json.dumps(data,ensure_ascii=False,separators=(',',':'))+';\n'+(LIB/'runtime/motion.js').read_text(encoding='utf-8')+'\n'+(LIB/'runtime/render.js').read_text(encoding='utf-8')
html=(HERE/'viewer.html').read_text(encoding='utf-8').replace('__RUNTIME__',runtime)
(HERE/'index.html').write_text(html,encoding='utf-8')
save(OUT/'provenance.json',dict(base='assembled/marisa/marisa.bbmodel',sha256=hashlib.sha256((LIB/'assembled/marisa/marisa.bbmodel').read_bytes()).hexdigest(),recipes='../recipes.json',references='../REFERENCES.md',sample_rate=recipes['sample_rate'],actions=list(clips)))
print(f'Built {len(clips)} braid interactions, {len(elements)} cubes, {len(bones)} bones.')

