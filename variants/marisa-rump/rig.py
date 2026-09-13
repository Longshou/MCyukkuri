"""Original waddle rig; reads only this variant's freshly generated assets."""
import json, math, uuid, re
import numpy as np

def build(root):
    read=lambda p:json.loads(p.read_text(encoding='utf-8'))
    save=lambda p,v:p.write_text(json.dumps(v,ensure_ascii=False,indent=2),encoding='utf-8')
    uid=lambda name:str(uuid.uuid5(uuid.NAMESPACE_URL,'marisa-rump/'+name))
    bb=read(root/'marisa.bbmodel')
    old={g['name']:g for g in bb['outliner'][0]['children']}
    specs=[('root',None,[0,0,0]),('upper_body','root',[0,0,0]),
      ('head','upper_body',[0,7,0]),('hair','upper_body',[0,7,0]),('bangs','upper_body',[0,7,0]),
      ('rump_sway','root',[0,2.1,.6]),('rump_roll','rump_sway',[0,2.1,2.8]),
      ('rump','rump_roll',[0,2.1,2.8]),('rear_hair','upper_body',[0,5,3.8]),
      ('hat_follow','upper_body',[0,9,0]),('hat','hat_follow',[0,7,0]),('bow','hat_follow',[0,7,0])]
    nodes={n:dict(name=n,uuid=uid(n),origin=p,children=[]) for n,parent,p in specs}
    membership={child:n for n,g in old.items() for child in g['children']}
    for e in bb['elements']:
        group=membership[e['uuid']]
        if e['name'].startswith(('back_lock_','side_tip_')) or e['name']=='back_hair_lower':group='rear_hair'
        membership[e['uuid']]=group
        nodes[group]['children'].append(e['uuid'])
    for n,parent,p in specs:
        if parent:nodes[parent]['children'].append(nodes[n])
    bb['outliner']=[nodes['root']]
    bb['name']='Marisa - Hakkei rump sway'
    duration=1.2
    tracks={n:{} for n in ['upper_body','rump_sway','rump_roll','rear_hair','hat_follow']}
    frames=[]
    for i in range(61):
        t=duration*i/60;phase=2*math.pi*i/60
        swing=math.tanh(1.6*math.sin(phase))/math.tanh(1.6)
        roll=4.5*math.sin(phase-.28)
        sx=1+.025*abs(swing);sy=1-.065*swing*swing;sz=1+.04*swing*swing
        # Analytic minimum of the scaled rump_core around the roll pivot.
        r=math.radians(roll)
        rump_min=2.1+(.15-2.1)*sy*math.cos(r)-5.6*sx*abs(math.sin(r))
        pose={'upper_body':{'scale':[1,1-.012*swing*swing,1]},
          'rump_sway':{'rotation':[0,16*swing,0],'position':[1.1*swing,max(0,.04-rump_min),0]},
          'rump_roll':{'rotation':[0,0,roll],'scale':[sx,sy,sz]},
          'rear_hair':{'rotation':[0,3.5*math.sin(phase-.5),0],'position':[.28*math.sin(phase-.5),0,0]},
          'hat_follow':{'rotation':[0,0,.45*math.sin(phase-.35)]}}
        frames.append({'time':round(t,6),'bones':pose})
        for n,channels in pose.items():
            for ch,v in channels.items():tracks[n].setdefault(ch,{})[str(round(t,6))]=[round(x,7) for x in v]
    animators={}
    for n,channels in tracks.items():
        kfs=[]
        for ch,keys in channels.items():
            for t,v in keys.items():
                # Blockbench's editor rotation X/Y are opposite to model-space axes.
                values=[-v[0],-v[1],v[2]] if ch=='rotation' else ([-v[0],v[1],v[2]] if ch=='position' else v)
                kfs.append(dict(channel=ch,time=float(t),interpolation='linear',data_points=[dict(zip('xyz',map(str,values)))],uuid=uid(n+ch+t)))
        animators[uid(n)]=dict(name=n,type='bone',keyframes=kfs)
    bb['animations']=[dict(uuid=uid('walk'),name='animation.marisa.waddle',loop='loop',length=duration,snapping=50,animators=animators)]
    save(root/'marisa.bbmodel',bb)
    geo=read(root/'marisa.geo.json');bones=[]
    for n,parent,p in specs:
        b=dict(name=n,pivot=p)
        if parent:b['parent']=parent
        b['cubes']=[]
        for e in bb['elements']:
            if membership[e['uuid']]!=n:continue
            b['cubes'].append(dict(origin=e['from'],size=[v-u for u,v in zip(e['from'],e['to'])],uv={k:dict(uv=f['uv'][:2],uv_size=[f['uv'][2]-f['uv'][0],f['uv'][3]-f['uv'][1]]) for k,f in e['faces'].items()}))
        bones.append(b)
    geo['minecraft:geometry'][0]['bones']=bones
    save(root/'marisa.geo.json',geo)
    # Bedrock animation rotation uses the same X/Y sign convention as its editor.
    bedrock={}
    for n,channels in tracks.items():
        bedrock[n]={ch:{t:([-v[0],-v[1],v[2]] if ch=='rotation' else ([-v[0],v[1],v[2]] if ch=='position' else v)) for t,v in keys.items()} for ch,keys in channels.items()}
    save(root/'marisa.animation.json',{'format_version':'1.8.0','animations':{'animation.marisa.waddle':{'loop':True,'animation_length':duration,'bones':bedrock}}})
    rig=dict(duration=duration,bones=[dict(name=n,parent=parent,pivot=p) for n,parent,p in specs],
      tracks=tracks,groups=[membership[e['uuid']] for e in bb['elements']],stride_units=3.2,
      axes='right-handed model space, front=-Z; exported editor X/Y rotations are negated')
    save(root/'waddle-rig.json',rig)
    html=(root/'preview.html').read_text(encoding='utf-8')
    data=json.loads(re.search(r'const DATA=(.*);',html)[1]);data['rig']=rig
    html=re.sub(r'const DATA=.*;',lambda _: 'const DATA='+json.dumps(data,separators=(',',':'))+';',html,count=1)
    (root/'preview.html').write_text(html,encoding='utf-8')
    print('Rigged 56 cubes / 12 bones; separate rump sway and stable front exported.')
