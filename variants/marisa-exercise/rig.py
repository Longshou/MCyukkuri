"""Independent skin extension and rigid feature lift; retains the prior rump animation."""
import json, math, uuid, re

def build(root):
    read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
    save=lambda p,v:p.write_text(json.dumps(v,ensure_ascii=False,indent=2),encoding='utf-8')
    uid=lambda name:str(uuid.uuid5(uuid.NAMESPACE_URL,'marisa-stretch/'+name))
    bb=read(root/'marisa.bbmodel')
    old={g['name']:g for g in bb['outliner'][0]['children']}
    specs=[('root',None,[0,0,0]),('body_stretch','root',[0,0,0]),
      ('upper_body','body_stretch',[0,0,0]),('head','upper_body',[0,7,0]),
      ('rump_sway','body_stretch',[0,2.1,.6]),('rump_roll','rump_sway',[0,2.1,2.8]),('rump','rump_roll',[0,2.1,2.8]),
      ('features_lift','root',[0,8,0]),('features_squash','features_lift',[0,0,0]),
      ('face','features_squash',[0,0,0]),('crown','features_squash',[0,8,0]),
      ('hair','features_squash',[0,7,0]),('bangs','features_squash',[0,7,0]),
      ('rear_hair','features_squash',[0,5,3.8]),('hat_follow','features_squash',[0,9,0]),
      ('hat','hat_follow',[0,7,0]),('bow','hat_follow',[0,7,0])]
    sections={}
    for e in bb['elements']:
        if '_slice_' in e['name']:
            a,b=e['from'][1],e['to'][1]
            parent='rump' if e['name'].startswith('rump_') else 'head'
            name=e['name']+'_bend'
            specs.append((name,parent,[0,(a+b)/2,.5]))
            sections[name]=(a,b)
    nodes={n:dict(name=n,uuid=uid(n),origin=p,children=[]) for n,parent,p in specs}
    membership={child:n for n,g in old.items() for child in g['children']}
    for e in bb['elements']:
        group=membership[e['uuid']]
        if e['name']=='crown':group='crown'
        if e['name'].startswith(('back_lock_','side_tip_')) or e['name']=='back_hair_lower':group='rear_hair'
        if '_slice_' in e['name']:group=e['name']+'_bend'
        membership[e['uuid']]=group
        nodes[group]['children'].append(e['uuid'])
    for n,parent,p in specs:
        if parent:nodes[parent]['children'].append(nodes[n])
    bb['outliner']=[nodes['root']]
    bb['name']='Marisa - Hakkei stretch exercises'
    waddle=read(root.parent/'marisa-rump/waddle-rig.json')
    wt=waddle['tracks'];wt['features_squash']=wt['upper_body']
    stretch={n:{} for n in ['body_stretch','features_lift']}
    for i in range(141):
        t=round(i*.02,4)
        if t<.3:s=0
        elif t<1.0:
            q=(t-.3)/.7;s=q*q*(3-2*q)
        elif t<1.65:s=1
        elif t<2.4:
            q=(t-1.65)/.75;s=1-q*q*(3-2*q)
        else:s=0
        stretch['body_stretch'].setdefault('scale',{})[str(t)]=[1,1,1]
        def lift(y):
            return y/8
        for name,(a,b) in sections.items():
            mid=(a+b)/2
            q=min(1,mid/2);narrow=1-q*q*(3-2*q)
            channels=stretch.setdefault(name,{})
            channels.setdefault('position',{})[str(t)]=[0,round(2.8*s*lift(mid),7),0]
            channels.setdefault('scale',{})[str(t)]=[round(1-.16*s*narrow,7),round(1+2.8*s*(lift(b)-lift(a))/(b-a),7),round(1-.14*s*narrow,7)]
        stretch['features_lift'].setdefault('position',{})[str(t)]=[0,round(2.8*s,7),0]
    clips={'stretch':dict(duration=2.8,loop=False,tracks=stretch),'waddle':dict(duration=1.2,loop=True,tracks=wt)}
    # Two independent exercise clips: timings are adaptations of source phase order.
    def smooth(q):
        q=max(0,min(1,q));return q*q*(3-2*q)
    def sample(keys,t):
        for (ta,a),(tb,b) in zip(keys,keys[1:]):
            if t<=tb:return a+(b-a)*smooth((t-ta)/(tb-ta))
        return keys[-1][1]
    def continuous(keys,t):
        period=keys[-1][0]
        def tangent(j):
            if j==0 or j==len(keys)-1:return (keys[1][1]-keys[-2][1])/(keys[1][0]+period-keys[-2][0])
            return (keys[j+1][1]-keys[j-1][1])/(keys[j+1][0]-keys[j-1][0])
        for j,((ta,a),(tb,b)) in enumerate(zip(keys,keys[1:])):
            if t<=tb:
                q=max(0,min(1,(t-ta)/(tb-ta)));dt=tb-ta
                return (2*q**3-3*q*q+1)*a+(q**3-2*q*q+q)*dt*tangent(j)+(-2*q**3+3*q*q)*b+(q**3-q*q)*dt*tangent(j+1)
        return keys[-1][1]
    def build_exercise(duration,heights,angles):
        tracks={}
        steps_time=round(duration*100)
        for frame in range(steps_time+1):
            t=round(frame*duration/steps_time,2);s=sample(heights,t);theta=math.radians(continuous(angles,t));sy=1+.35*s
            # Integrate a bending centerline; the bottom unit remains upright and planted.
            def tilt(y):return theta*smooth((y-1)/7)
            steps=256;step=8/steps;points=[(0.,0.)]
            for j in range(steps):
                angle=tilt((j+.5)*step);x,y=points[-1];points.append((x-sy*step*math.sin(angle),y+sy*step*math.cos(angle)))
            def center(y):
                u=max(0,min(steps,y/8*steps));j=min(steps-1,int(u));q=u-j
                return tuple(points[j][k]*(1-q)+points[j+1][k]*q for k in range(2))
            def put(name,ch,v):tracks.setdefault(name,{}).setdefault(ch,{})[str(t)]=[0 if abs(x)<.000001 else round(x,7) for x in v]
            for name,(a,b) in sections.items():
                mid=(a+b)/2;x,y=center(mid);angle=tilt(mid)
                q=min(1,mid/2);rounding=1-smooth(q)
                # Adjacent tilted cuboids overlap inside the skin so the bend remains closed.
                overlap=6.8*abs(tilt(b)-tilt(a))/(b-a)
                put(name,'position',[x,y-mid,0]);put(name,'rotation',[0,0,math.degrees(angle)])
                put(name,'scale',[1-.16*s*rounding,sy+overlap,1-.14*s*rounding])
            x,y=center(8);put('features_lift','position',[x,y-8,0]);put('features_lift','rotation',[0,0,math.degrees(theta)])
        return dict(duration=duration,loop=True,tracks=tracks)
    clips['exercise_vertical']=build_exercise(2.62,[(0,0),(19/60,2/7),(37/60,4.5/7),(55/60,1),(103/60,1),(121/60,4.5/7),(139/60,2/7),(2.62,0)],[(0,0),(2.62,0)])
    # Interpolate through intermediate angles without easing to a stop at each sprite phase.
    clips['exercise_sides']=build_exercise(1.18,[(0,1),(1.18,1)],[(0,0),(9/60,15),(18/60,30),(27/60,15),(36/60,0),(45/60,-15),(54/60,-30),(63/60,-15),(1.18,0)])
    # User preference: half-speed side exercise, retaining the continuous phase curve.
    side=clips['exercise_sides']
    side['duration']*=2
    side['tracks']={n:{ch:{str(round(float(t)*2,4)):v for t,v in keys.items()} for ch,keys in channels.items()} for n,channels in side['tracks'].items()}
    editor=lambda ch,v:([-v[0],-v[1],v[2]] if ch=='rotation' else ([-v[0],v[1],v[2]] if ch=='position' else v))
    bb['animations']=[];bedrock_animations={}
    for label,clip in clips.items():
        animators={};bedrock={}
        for n,channels in clip['tracks'].items():
            kfs=[];bedrock[n]={}
            for ch,keys in channels.items():
                bedrock[n][ch]={}
                for t,v in keys.items():
                    values=editor(ch,v);bedrock[n][ch][t]=values
                    kfs.append(dict(channel=ch,time=float(t),interpolation='linear',data_points=[dict(zip('xyz',(format(v,'.7f') for v in values)))],uuid=uid(label+n+ch+t)))
            animators[uid(n)]=dict(name=n,type='bone',keyframes=kfs)
        name='animation.marisa.'+label
        bb['animations'].append(dict(uuid=uid(label),name=name,loop='loop' if clip['loop'] else 'once',length=clip['duration'],snapping=100,animators=animators))
        bedrock_animations[name]=dict(loop=clip['loop'],animation_length=clip['duration'],bones=bedrock)
    bb['visible_box']=[3,4,1.5];save(root/'marisa.bbmodel',bb)
    geo=read(root/'marisa.geo.json');bones=[]
    for n,parent,p in specs:
        b=dict(name=n,pivot=p,cubes=[])
        if parent:b['parent']=parent
        for e in bb['elements']:
            if membership[e['uuid']]!=n:continue
            b['cubes'].append(dict(origin=e['from'],size=[v-u for u,v in zip(e['from'],e['to'])],uv={k:dict(uv=f['uv'][:2],uv_size=[f['uv'][2]-f['uv'][0],f['uv'][3]-f['uv'][1]]) for k,f in e['faces'].items()}))
        bones.append(b)
    geo['minecraft:geometry'][0]['bones']=bones
    geo['minecraft:geometry'][0]['description'].update(visible_bounds_height=4,visible_bounds_offset=[0,1.5,0])
    save(root/'marisa.geo.json',geo)
    save(root/'marisa.animation.json',dict(format_version='1.8.0',animations=bedrock_animations))
    rig=dict(duration=2.8,tracks=stretch,loop=False,clips=clips,bones=[dict(name=n,parent=parent,pivot=p) for n,parent,p in specs],groups=[membership[e['uuid']] for e in bb['elements']],stride_units=3.2,axes='right-handed model space; front=-Z; editor X/Y rotations and X positions negated')
    save(root/'animation-rig.json',rig)
    html=(root/'preview.html').read_text(encoding='utf-8');data=json.loads(re.search(r'const DATA=(.*);',html)[1]);data['rig']=rig
    html=re.sub(r'const DATA=.*;',lambda _: 'const DATA='+json.dumps(data,separators=(',',':'))+';',html,count=1)
    (root/'preview.html').write_text(html,encoding='utf-8')
    print(f'{len(bb["elements"])} cubes / {len(specs)} bones; source proportion stretch and waddle exported.')
