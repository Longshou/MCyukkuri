"""Build original feeding assets; Unity sprites remain reference-only."""
from pathlib import Path
from PIL import Image, ImageDraw
import base64, copy, hashlib, json, math, random, uuid, zipfile
ROOT=Path(__file__).resolve().parent
BASE=ROOT.parent/'marisa-body'
def read(p): return json.loads(p.read_text(encoding='utf-8'))
def save(p,d): (ROOT/p).write_text(json.dumps(d,ensure_ascii=False,indent=2),encoding='utf-8')
def uid(n):return str(uuid.uuid5(uuid.NAMESPACE_URL,'yukkuri/feeding/'+n))
def smooth(q):q=max(0,min(1,q));return q*q*(3-2*q)
def env(t,keys):
    for (a,x),(b,y) in zip(keys,keys[1:]):
        if t<=b:return x+(y-x)*smooth((t-a)/(b-a))
    return keys[-1][1]
model=read(BASE/'marisa.bbmodel')
atlas=Image.new('RGB',(256,256),'#ffe7b9');atlas.paste(Image.open(BASE/'marisa.png'),(0,0))
d=ImageDraw.Draw(atlas)
face_uv={'rest':[0,0,48,32]}
for i,name in enumerate(['open','small','chew_a','chew_b','happy']):
    x=128+(i%2)*48;y=(i//2)*32
    face=Image.new('RGB',(48,32),'#ffe7b9');f=ImageDraw.Draw(face)
    for ex in (9,30):
        f.line([(ex,16),(ex+2,14),(ex+5,13),(ex+8,15)],fill='#48352e',width=2)
        f.line([(ex+1,8),(ex+4,7),(ex+7,8)],fill='#ba8e53')
    for ex in (5,36): f.ellipse((ex,21,ex+6,23),fill='#f0b4a4')
    if name in ('open','small','happy'):
        h={'open':8,'small':5,'happy':4}[name]
        f.ellipse((20,20,28,20+h),fill='#805044');f.ellipse((22,22,26,19+h),fill='#d97a72')
    else:
        points=[(20,22),(22,23),(25,23),(28,21)] if name=='chew_a' else [(20,23),(23,22),(26,23),(28,22)]
        f.line(points,fill='#805044',width=1)
        cx=32 if name=='chew_a' else 14
        f.line([(cx,22),(cx-1,24),(cx,26)],fill='#d3aa84')
    atlas.paste(face,(x,y));face_uv[name]=[x,y,x+48,y+32]
palette={}
for i,(n,col) in enumerate({'ceramic':'#74978e','rim':'#b1c8b7','inner':'#dbe0c3','foot':'#50766c','grain':'#ac703c','grain_light':'#d9a75b','grain_dark':'#885331','label':'#f4e8b7'}.items()):
    x=i*16;y=128;d.rectangle((x,y,x+15,y+15),fill=col);palette[n]=[x,y,x+16,y+16]
atlas.save(ROOT/'feeding.png')
texture='data:image/png;base64,'+base64.b64encode((ROOT/'feeding.png').read_bytes()).decode()
members={c:g['name'] for g in model['outliner'][0]['children'] for c in g['children']}
specs=[('root',None,[0,0,0]),('body','root',[0,0,-4.6]),('head','body',[0,0,0]),('cheek_left','body',[-6,3,-2]),('cheek_right','body',[6,3,-2]),('hair','body',[0,7,0]),('bangs','body',[0,7,0]),('hat','body',[0,9,0]),('bow','hat',[0,11,-5])]
elements=copy.deepcopy(model['elements']);groups=[]
for e in elements:
    group=members[e['uuid']]
    if e['name']=='soft_head':e['faces']['north']['uv']=[0,48,16,64]
    if e['name']=='left_cheek':group='cheek_left'
    if e['name']=='right_cheek':group='cheek_right'
    groups.append(group)
def cube(name,lo,hi,uv):
    return dict(name=name,uuid=uid(name),type='cube',**{'from':lo,'to':hi},origin=[0,0,0],rotation=[0,0,0],box_uv=False,rescale=False,autouv=0,color=0,faces={s:dict(uv=uv[:],texture=0) for s in ['north','south','east','west','up','down']})
for name,uv in face_uv.items():
    n='face_'+name;specs.append((n,'body',[0,4,-4.64]))
    e=cube(n,[-6,0,-4.64],[6,8,-4.62],[0,48,16,64]);e['faces']['north']['uv']=uv
    if name=='rest':e['from'][2]-=.025;e['to'][2]-=.025
    elements.append(e);groups.append(n)
def pose(t,clip):
    result={}
    def put(n,ch,v):result.setdefault(n,{})[ch]=v
    selected='rest';dip=0;cheek=0
    if clip=='bite':
        dip=env(t,[(0,0),(.4,1),(.64,1),(1.03,0),(2.7,0)])
        put('body','position',[0,0,-.65*dip]);put('body','rotation',[-18*dip,0,0]);put('body','scale',[1+.012*dip,1-.04*dip,1])
        if .25<=t<.65:selected='open'
        elif .65<=t<.92:selected='small'
        elif .92<=t<2.12:
            step=min(7,int((t-.92)/.15));selected=['open','small','chew_a','chew_b','chew_a','chew_b','chew_a','chew_b'][step]
            cheek=.5+.5*math.sin((t-.92)*math.pi/.15)
        elif 2.12<=t<2.5:selected='happy'
        put('cheek_left','scale',[1+.10*cheek,1,1]);put('cheek_right','scale',[1+.10*(1-cheek) if .92<=t<2.12 else 1,1,1])
        put('hat','rotation',[1.4*env(t,[(0,0),(.5,1),(.9,-.4),(1.3,0),(2.7,0)]),0,0])
    elif clip=='walk':
        q=t/.8*math.tau;lift=(1-math.cos(q*2))*.11
        put('body','position',[0,lift,0]);put('body','rotation',[0,0,math.sin(q)*1.5]);put('body','scale',[1+.012*math.cos(q*2),1-.014*math.cos(q*2),1]);put('hat','rotation',[0,0,-math.sin(q-.3)*1.0])
    elif clip=='satisfied':
        selected='happy' if t<1.1 else 'rest';q=env(t,[(0,0),(.4,1),(.8,0),(1.5,0)])
        put('body','scale',[1+.016*q,1-.015*q,1]);put('hat','rotation',[-1.3*q,0,0])
    for name in face_uv:put('face_'+name,'scale',[1,1,1] if name==selected else [.000001]*3)
    return result
clips={}
for name,duration,loop in [('idle',1,True),('walk',.8,True),('bite',2.7,False),('satisfied',1.5,False)]:
    tracks={}
    # 100 Hz keys retain the 150 ms source expression switches in all exports.
    for i in range(round(duration*100)+1):
        t=round(i/100,2)
        for n,channels in pose(t,name).items():
            for ch,v in channels.items():tracks.setdefault(n,{}).setdefault(ch,{})[str(t)]=[round(x,7) for x in v]
    # Preserve both sides of flat sections; only remove redundant constant keys.
    for channels in tracks.values():
        for ch,keys in list(channels.items()):
            points=list(keys.items());channels[ch]={k:v for i,(k,v) in enumerate(points) if i==0 or i==len(points)-1 or v!=points[i-1][1] or v!=points[i+1][1]}
    clips[name]=dict(duration=duration,loop=loop,tracks=tracks)
def export(name,els,gs,bones,clips=None):
    nodes={n:dict(name=n,uuid=uid(name+'/'+n),origin=p,children=[]) for n,parent,p in bones}
    for e,g in zip(els,gs):nodes[g]['children'].append(e['uuid'])
    for n,parent,p in bones:
        if parent:nodes[parent]['children'].append(nodes[n])
    bb=copy.deepcopy(model);bb.update(name=name,elements=els,outliner=[nodes[bones[0][0]]],resolution=dict(width=256,height=256),animations=[])
    bb['textures'][0].update(source=texture,name='feeding.png',path='feeding.png',width=256,height=256,uv_width=256,uv_height=256)
    animations={}
    for label,c in (clips or {}).items():
        animators={};tracks={}
        for n,channels in c['tracks'].items():
            kfs=[];tracks[n]={}
            for ch,keys in channels.items():
                tracks[n][ch]={}
                for t,v in keys.items():
                    val=[-v[0],-v[1],v[2]] if ch=='rotation' else ([-v[0],v[1],v[2]] if ch=='position' else v)
                    tracks[n][ch][t]=val
                    kfs.append(dict(channel=ch,time=float(t),interpolation='linear',data_points=[dict(zip('xyz',map(str,val)))],uuid=uid(name+label+n+ch+t)))
            animators[nodes[n]['uuid']]=dict(name=n,type='bone',keyframes=kfs)
        an='animation.'+name+'.'+label
        bb['animations'].append(dict(uuid=uid(an),name=an,loop='loop' if c['loop'] else 'once',length=c['duration'],snapping=100,animators=animators))
        animations[an]=dict(loop=c['loop'],animation_length=c['duration'],bones=tracks)
    save(name+'.bbmodel',bb)
    geo=[]
    for n,parent,p in bones:
        b=dict(name=n,pivot=p,cubes=[])
        if parent:b['parent']=parent
        for e,g in zip(els,gs):
            if g==n:b['cubes'].append(dict(origin=e['from'],size=[b-a for a,b in zip(e['from'],e['to'])],uv={s:dict(uv=f['uv'][:2],uv_size=[f['uv'][2]-f['uv'][0],f['uv'][3]-f['uv'][1]]) for s,f in e['faces'].items()}))
        geo.append(b)
    save(name+'.geo.json',{'format_version':'1.12.0','minecraft:geometry':[dict(description=dict(identifier='geometry.'+name,texture_width=256,texture_height=256,visible_bounds_width=4,visible_bounds_height=3,visible_bounds_offset=[0,1,0]),bones=geo)]})
    if clips:save(name+'.animation.json',dict(format_version='1.8.0',animations=animations))
    return dict(elements=els,groups=gs,rig=dict(bones=[dict(name=n,parent=pa,pivot=p) for n,pa,p in bones],groups=gs,clips=clips or {}))
marisa=export('marisa',elements,groups,specs,clips)
# Low, genuinely hollow trough: base + four walls, with a pale inside floor.
bowl=[];bg=[];bs=[('root',None,[0,0,0]),('bowl','root',[0,0,0])]
def add(n,lo,hi,mat,group='bowl'):bowl.append(cube(n,lo,hi,palette[mat]));bg.append(group)
add('foot',[-4.5,0,-3],[4.5,.3,3],'foot');add('base',[-5,.3,-3.5],[5,.65,3.5],'ceramic')
add('inside',[-4.5,.65,-3],[4.5,.72,3],'inner')
for n,lo,hi in [('front',[-5,.65,-3.5],[5,1.5,-2.9]),('back',[-5,.65,2.9],[5,1.5,3.5]),('left',[-5,.65,-2.9],[-4.4,1.5,2.9]),('right',[4.4,.65,-2.9],[5,1.5,2.9])]:add(n,lo,hi,'rim')
add('label',[-1.7,.55,-3.54],[1.7,1.23,-3.5],'label')
rng=random.Random(83)
for serving in range(8):
    n='food_'+str(serving);bs.append((n,'root',[0,.72,0]))
    for j in range(6):
        col=(serving*3+j)%8;row=(serving+j*3)%6
        x=-3.9+col*1.02+rng.uniform(-.08,.08);z=-2.35+row*.83+rng.uniform(-.07,.07);y=.73+(serving//4)*.45
        add(f'grain_{serving}_{j}',[x,y,z],[x+.76,y+.44,z+.64],['grain','grain_light','grain_dark'][(serving+j)%3],n)
foodbowl=export('food_bowl',bowl,bg,bs)
save('scene-data.json',dict(marisa=marisa,bowl=foodbowl,texture=texture))
data=dict(marisa=marisa,bowl=foodbowl,texture=texture)
html=(ROOT/'viewer.html.template').read_text(encoding='utf-8').replace('__THREE__',(ROOT.parent.parent/'preview/vendor/three.min.js').read_text(encoding='utf-8')).replace('__DATA__',json.dumps(data,separators=(',',':'))).replace('__MOTION__',(ROOT/'motion.js').read_text(encoding='utf-8')).replace('__FEEDING__',(ROOT/'feeding.js').read_text(encoding='utf-8')).replace('__VIEWER__',(ROOT/'viewer.js').read_text(encoding='utf-8'))
(ROOT/'preview.html').write_text(html,encoding='utf-8')
save('build-info.json',dict(base_sha256=hashlib.sha256((BASE/'marisa.bbmodel').read_bytes()).hexdigest(),base_cubes=len(model['elements']),marisa_cubes=len(elements),bowl_cubes=len(bowl),animations={n:dict(duration=c['duration'],loop=c['loop']) for n,c in clips.items()},source='eightSight.v8.3; source art excluded from package'))
with zipfile.ZipFile(ROOT/'marisa-feeding-assets.zip','w',zipfile.ZIP_DEFLATED) as z:
    for name in ['marisa.bbmodel','marisa.geo.json','marisa.animation.json','food_bowl.bbmodel','food_bowl.geo.json','feeding.png','feeding.js','motion.js','scene-data.json','README.md']:
        if (ROOT/name).exists():z.write(ROOT/name,name)
print('Built feeding model, hollow bowl, 8 portions, 4 clips and offline preview.')
