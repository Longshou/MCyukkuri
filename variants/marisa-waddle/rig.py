"""Original waddle rig; reads only this variant's freshly generated assets."""
import json, math, uuid, re
import numpy as np

def build(root):
    read=lambda p:json.loads(p.read_text(encoding='utf-8'))
    save=lambda p,v:p.write_text(json.dumps(v,ensure_ascii=False,indent=2),encoding='utf-8')
    uid=lambda name:str(uuid.uuid5(uuid.NAMESPACE_URL,'marisa-waddle/'+name))
    bb=read(root/'marisa.bbmodel')
    old={g['name']:g for g in bb['outliner'][0]['children']}
    specs=[('root',None,[0,0,0]),('ground_contact','root',[0,0,0]),
      ('body_sway','ground_contact',[0,0,-3]),('body_roll','body_sway',[0,0,0]),
      ('head','body_roll',[0,7,0]),('hair','body_roll',[0,7,0]),('bangs','body_roll',[0,7,0]),
      ('rear_hair','body_roll',[0,5,3.8]),('hat_follow','body_roll',[0,9,0]),
      ('hat','hat_follow',[0,7,0]),('bow','hat_follow',[0,7,0])]
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
    bb['name']='Marisa - Waddle rig'
    duration=1.2
    tracks={n:{} for n in ['ground_contact','body_sway','body_roll','rear_hair','hat_follow']}
    frames=[]
    for i in range(65):
        t=duration*i/64;phase=2*math.pi*i/64
        roll=3.5*math.sin(phase)
        pose={'ground_contact':{'position':[0,6*abs(math.sin(math.radians(roll))),0]},
          'body_sway':{'rotation':[0,8*math.sin(phase),0]},
          'body_roll':{'rotation':[0,0,roll]},
          'rear_hair':{'rotation':[0,3*math.sin(phase-.45),0]},
          'hat_follow':{'rotation':[0,0,-1.8*math.sin(phase-.35)]}}
        # All rotations are single-axis per bone; hierarchical order is unambiguous.
        frames.append({'time':round(t,6),'bones':pose})
        for n,channels in pose.items():
            for ch,v in channels.items():tracks[n].setdefault(ch,{})[str(round(t,6))]=[round(x,7) for x in v]
    animators={}
    for n,channels in tracks.items():
        kfs=[]
        for ch,keys in channels.items():
            for t,v in keys.items():
                # Blockbench's editor rotation X/Y are opposite to model-space axes.
                values=[-v[0],-v[1],v[2]] if ch=='rotation' else [-v[0],v[1],v[2]]
                kfs.append(dict(channel=ch,time=float(t),interpolation='linear',data_points=[dict(zip('xyz',map(str,values)))],uuid=uid(n+ch+t)))
        animators[uid(n)]=dict(name=n,type='bone',keyframes=kfs)
    bb['animations']=[dict(uuid=uid('walk'),name='animation.marisa.waddle',loop='loop',length=duration,snapping=40,animators=animators)]
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
        bedrock[n]={ch:{t:([-v[0],-v[1],v[2]] if ch=='rotation' else v) for t,v in keys.items()} for ch,keys in channels.items()}
    save(root/'marisa.animation.json',{'format_version':'1.8.0','animations':{'animation.marisa.waddle':{'loop':True,'animation_length':duration,'bones':bedrock}}})
    rig=dict(duration=duration,bones=[dict(name=n,parent=parent,pivot=p) for n,parent,p in specs],
      tracks=tracks,groups=[membership[e['uuid']] for e in bb['elements']],stride_units=3.2,
      axes='right-handed model space, front=-Z; exported editor X/Y rotations are negated')
    save(root/'waddle-rig.json',rig)
    html=(root/'preview.html').read_text(encoding='utf-8')
    data=json.loads(re.search(r'const DATA=(.*);',html)[1]);data['rig']=rig
    html=re.sub(r'const DATA=.*;',lambda _: 'const DATA='+json.dumps(data,separators=(',',':'))+';',html,count=1)
    (root/'preview.html').write_text(html,encoding='utf-8')
    print('Rigged 53 cubes / 11 bones; 1.2-second looping waddle exported.')
