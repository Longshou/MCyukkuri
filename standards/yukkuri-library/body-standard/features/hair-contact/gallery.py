"""Build the complete current character gallery without changing source models.

Uses the eight current rounded exports, selected runtime snapshots for the
unchanged species and sparse forms, and the retained DOS library model.
Outputs live at .local/previews/body-standard/all-models; work stays in .local/previews/_work/body-standard.
"""
from pathlib import Path
import base64
import copy
import hashlib
import io
import json
import math
import os
import subprocess
import sys
from PIL import Image, ImageDraw

HERE=Path(__file__).resolve().parent
BODY=HERE.parents[1]
LIB=BODY.parent
ROOT=LIB.parents[1]
OUT=ROOT/'.local/previews/body-standard/all-models'
TMP=ROOT/'.local/previews/_work/body-standard/gallery'
sys.path.insert(0,str(ROOT/'scripts'))
from build_preview_hub import font as make_font
OUT.mkdir(parents=True,exist_ok=True)
TMP.mkdir(parents=True,exist_ok=True)
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
sha=lambda p:hashlib.sha256(p.read_bytes()).hexdigest()
relative=lambda p:Path(os.path.relpath(p,OUT)).as_posix()
labels=read(BODY/'recipe.json')['labels']
catalog={}
records={}
actions=['idle','waddle','hop','turn','flee','shell_sway','shell_retract','fly','predator_fly']


def add(ident,model,texture,source,label,preview=None,group='保留现有造型'):
    model=copy.deepcopy(model)
    model.pop('expression_patches',None)
    model['clips'].update(model.pop('behavior_clips',{}))
    model['clips']={k:v for k,v in model['clips'].items() if k in actions}
    model.setdefault('clips',{}).setdefault('idle',dict(duration=1,loop=True,tracks=[]))
    # Gallery shows pose and gait in place; travel is not a camera movement.
    world={i for i,b in enumerate(model['bones']) if b['name']=='world_motion'}
    for clip in model['clips'].values():
        for track in clip['tracks']:
            if track['bone'] in world and track['channel']=='position':
                for key in track['keys']:key[1]=key[3]=0
    selected='waddle' if 'waddle' in model['clips'] else 'shell_sway' if 'shell_sway' in model['clips'] else 'idle'
    catalog[ident]=dict(model=model,texture=texture,label=label,group=group,action=selected,
                        source=relative(source),preview=relative(preview) if preview else relative(source))
    records[ident]=dict(source=str(source.relative_to(ROOT)),sha256=sha(source),group=group)
    im=Image.open(io.BytesIO(base64.b64decode(texture.split(',',1)[1]))).convert('RGBA')
    (TMP/(ident+'.rgba')).write_bytes(im.tobytes())


cfg=read(BODY/'recipe.json')
for ident,spec in cfg['sources'].items():
    folder=LIB/spec['export'];model=read(folder/'runtime.model.json')
    texture='data:image/png;base64,'+base64.b64encode((folder/'textures/neutral.png').read_bytes()).decode()
    add(ident,model,texture,folder/(ident+'.bbmodel'),labels[ident],folder/'index.html','本轮头发修复')

extra={'house_reimu':'家油灵梦','house_marisa':'家油魔理沙','remilia':'蕾米莉亚','flandre':'弗兰',
       'reimu_sparse':'不足灵梦','marisa_sparse':'不足魔理沙','reimu_face':'脸油灵梦','marisa_face':'脸油魔理沙',
       'reimu_bean':'豆油灵梦','marisa_bean':'豆油魔理沙'}
assets=ROOT/'src/main/resources/assets/mcyukkuri'
ledger=read(ROOT/'art/runtime-sources.json')['models']
legacy_contact=ROOT/'art/source/subspecies-runtime/motion-contact/manifest.json'
legacy_revision=read(legacy_contact) if legacy_contact.exists() else {}
for ident,label in extra.items():
    source=assets/'yukkuri'/(ident+'.json');model=read(source)
    texture='data:image/png;base64,'+base64.b64encode((assets/'textures/entity'/ident/'neutral.png').read_bytes()).decode()
    record=legacy_revision.get('models',{}).get(ident,{})
    corrected=(legacy_revision.get('published') and record.get('modified_parts') and record.get('output_sha256')==sha(source))
    group='豆油头发贴合修订' if ident.endswith('_bean') else '日常吸血鬼尖牙' if ident in ('remilia','flandre') else '后发移动适配' if corrected else '保留现有造型'
    if model.get('revision')=='rounded-marisa-deficient-v1':group='稀毛／歪脸魔理沙圆润体态修复'
    add(ident,model,texture,source,label,group=group)

# DOS remains a library-only model; convert its existing preview rig to the
# same sampler without exporting it into Minecraft or altering its baseline.
ident='marisa_dos';folder=LIB/'subspecies/reimu-dos/assembled'/ident
data=json.JSONDecoder().raw_decode((folder/'index.html').read_text(encoding='utf-8').split('const DATA=',1)[1])[0]
rig=data['model']['rig'];names={b['name']:i for i,b in enumerate(rig['bones'])}
bb=read(folder/(ident+'.bbmodel'));parts=[];weights=[]
for i,(e,group) in enumerate(zip(bb['elements'],rig['groups'])):
    count=rig.get('element_vertex_counts',[36]*len(rig['groups']))[i]
    parts.append(dict(name=e['name'],start=len(weights),count=count,anatomy=group.removeprefix('anatomy_') if group.startswith('anatomy_') else ''))
    weights.extend([[names[group],names[group],0] for _ in range(count)])
model=dict(id=ident,vertices=data['model']['vertices'],weights=weights,parts=parts,texture_size=data.get('textureSize',128),
           bones=[dict(name=b['name'],parent=names[b['parent']] if b['parent'] else -1,pivot=b['pivot']) for b in rig['bones']],
           clips={name:dict(duration=c['duration'],loop=c['loop'],tracks=[dict(bone=names[n],channel=ch,keys=[[float(t),*v]for t,v in sorted(keys.items(),key=lambda q:float(q[0]))]) for n,channels in c['tracks'].items() for ch,keys in channels.items()])for name,c in rig['clips'].items()})
assert len(weights)*8==len(model['vertices'])
v=model['vertices']
for i in range(0,len(v),24):
    a=[v[i+8+k]-v[i+k]for k in range(3)];b=[v[i+16+k]-v[i+k]for k in range(3)]
    n=[a[1]*b[2]-a[2]*b[1],a[2]*b[0]-a[0]*b[2],a[0]*b[1]-a[1]*b[0]]
    if sum(n[k]*v[i+5+k]for k in range(3))<0:v[i+8:i+24]=v[i+16:i+24]+v[i+8:i+16]
add(ident,model,data['textures'][data['preview'].get('default_expression','neutral')],folder/(ident+'.bbmodel'),'DOS 魔理沙',folder/'index.html')

# Include the game's Wasa-to-Deibu composition only after its owner has selected
# this hair revision. A stale pre-fix combination must not represent this round.
combo=assets/'yukkuri/reimu_wasa_deibu.json'
if combo.exists():
    model=read(combo)
    if (len(model['weights'])==len(catalog['reimu_wasa']['model']['weights']) and
        ledger.get('reimu_wasa_deibu',{}).get('selected_model_sha256')==records['reimu_wasa']['sha256']):
        texture='data:image/png;base64,'+base64.b64encode((assets/'textures/entity/reimu_wasa_deibu/neutral.png').read_bytes()).decode()
        add('reimu_wasa_deibu',model,texture,combo,'多毛灵胖',group='本轮头发修复 · 游戏组合')

jobs=[]
for ident,e in catalog.items():
    m=e['model'];v=m['vertices'];low=min(v[1::8]);high=max(v[1::8]);radius=max(math.hypot(v[i],v[i+2])for i in range(0,len(v),8))
    e['framing']=dict(center=(low+high)/2,span=max((high-low)*.72,radius*1.05))
    for view,az in [('front',0),('angle',.55),('side',math.pi/2),('back',math.pi)]:
        jobs.append(dict(id=ident,az=az,file=ident+'-'+view,action='idle',t=0))
    for i in range(12):jobs.append(dict(id=ident,az=2.8,file=f'{ident}-walk-{i}',action=e['action'],t=m['clips'][e['action']]['duration']*i/12))

(OUT/'models.js').write_text('const ALL_MODELS='+json.dumps(catalog,ensure_ascii=False,separators=(',',':'))+';',encoding='utf-8')
(TMP/'data.json').write_text(json.dumps(catalog,separators=(',',':')),encoding='utf-8')
code="const fs=require('fs');\n"+(LIB/'runtime/render.js').read_text(encoding='utf-8')+'\n'+(ROOT/'.local/previews/body-standard/pose.js').read_text(encoding='utf-8')+'\n'
code+='const models=JSON.parse(fs.readFileSync('+json.dumps(str(TMP/'data.json'))+'));const folder='+json.dumps(str(TMP))+';const jobs='+json.dumps(jobs)+';\n'
code+="for(const j of jobs){const e=models[j.id],p=renderModel(posed(e.model,j.action,j.t),fs.readFileSync(folder+'/'+j.id+'.rgba'),400,380,j.az,.13,e.framing.span,{centerY:e.framing.center,textureSize:e.model.texture_size||128});fs.writeFileSync(folder+'/'+j.file+'.rgba',p)}"
(TMP/'render.cjs').write_text(code,encoding='utf-8');subprocess.run(['node',str(TMP/'render.cjs')],check=True)
font=make_font(22)
title=make_font(30)


def tile(name):
    rgba=Image.frombytes('RGBA',(400,380),(TMP/(name+'.rgba')).read_bytes())
    bg=Image.new('RGB',rgba.size,(241,237,227));bg.paste(rgba,mask=rgba.getchannel('A'));return bg


overview=Image.new('RGB',(1600,math.ceil(len(catalog)/4)*350+70),(241,237,227));draw=ImageDraw.Draw(overview)
draw.text((20,15),f'全部油库里模型 · {len(catalog)} 种形态',font=title,fill='#303229')
for i,(ident,e) in enumerate(catalog.items()):
    thumb=tile(ident+'-angle');thumb.save(OUT/(ident+'.png'))
    x=(i%4)*400;y=70+(i//4)*350
    draw.text((x+18,y+5),e['label'],font=font,fill='#303229');overview.paste(thumb.resize((326,310)),(x+37,y+38))
    sheet=Image.new('RGB',(1600,430),(241,237,227));d=ImageDraw.Draw(sheet)
    for col,(view,label) in enumerate([('front','正面'),('angle','斜侧'),('side','侧面'),('back','背面')]):
        d.text((col*400+15,10),e['label']+' / '+label,font=font,fill='#303229');sheet.paste(tile(ident+'-'+view),(col*400,50))
    sheet.save(OUT/(ident+'-views.png'))
    frames=[tile(f'{ident}-walk-{i}')for i in range(12)]
    delay=max(20,round(e['model']['clips'][e['action']]['duration']*1000/12))
    frames[0].save(OUT/(ident+'-walk.gif'),save_all=True,append_images=frames[1:],duration=delay,loop=0)
overview.save(OUT/'overview.png')
records['gallery']=dict(models=len(catalog),views=4,animation_frames=12,scale_note='Each card is framed separately, not a size comparison')
(OUT/'manifest.json').write_text(json.dumps(records,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps({'models':len(catalog),'output':str(OUT)},ensure_ascii=False))
