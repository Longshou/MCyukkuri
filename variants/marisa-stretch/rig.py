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
      ('features_lift','root',[0,0,0]),('features_squash','features_lift',[0,0,0]),
      ('face','features_squash',[0,0,0]),('crown','features_squash',[0,8,0]),
      ('hair','features_squash',[0,7,0]),('bangs','features_squash',[0,7,0]),
      ('rear_hair','features_squash',[0,5,3.8]),('hat_follow','features_squash',[0,9,0]),
      ('hat','hat_follow',[0,7,0]),('bow','hat_follow',[0,7,0])]
    nodes={n:dict(name=n,uuid=uid(n),origin=p,children=[]) for n,parent,p in specs}
    membership={child:n for n,g in old.items() for child in g['children']}
    for e in bb['elements']:
        group=membership[e['uuid']]
        if e['name']=='crown':group='crown'
        if e['name'].startswith(('back_lock_','side_tip_')) or e['name']=='back_hair_lower':group='rear_hair'
        membership[e['uuid']]=group
        nodes[group]['children'].append(e['uuid'])
    for n,parent,p in specs:
        if parent:nodes[parent]['children'].append(nodes[n])
    bb['outliner']=[nodes['root']]
    bb['name']='Marisa - Hakkei stretch and rump sway'
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
        stretch['body_stretch'].setdefault('scale',{})[str(t)]=[1,round(1+.75*s,7),1]
        stretch['features_lift'].setdefault('position',{})[str(t)]=[0,round(6*s,7),0]
    clips={'stretch':dict(duration=2.8,loop=False,tracks=stretch),'waddle':dict(duration=1.2,loop=True,tracks=wt)}
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
                    kfs.append(dict(channel=ch,time=float(t),interpolation='linear',data_points=[dict(zip('xyz',map(str,values)))],uuid=uid(label+n+ch+t)))
            animators[uid(n)]=dict(name=n,type='bone',keyframes=kfs)
        name='animation.marisa.'+label
        bb['animations'].append(dict(uuid=uid(label),name=name,loop='loop' if clip['loop'] else 'once',length=clip['duration'],snapping=50,animators=animators))
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
    print('57 cubes / 17 bones; stretch and waddle clips exported.')
