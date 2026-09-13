"""Render the current draft's real runtime geometry with the existing rasterizer."""
from pathlib import Path
import json
import subprocess
from PIL import Image, ImageDraw, ImageFont

HERE=Path(__file__).resolve().parent
LIB=HERE.parents[1]
ROOT=LIB.parents[1]
OUT=HERE/'previews/current'
TMP=ROOT/'.local/rounded-standard-body/render'
TMP.mkdir(parents=True,exist_ok=True)
MODELS=json.loads((OUT/'preview-data.json').read_text(encoding='utf-8'))
VIEWS=[('正面',0,.12),('斜侧',.65,.18),('侧面',1.570796,.12),('背面',3.141593,.16)]
FONT=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',22)
TITLE=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',30)
for id,entry in MODELS.items():
    import base64,io
    for expr,url in entry['textures'].items():
        im=Image.open(io.BytesIO(base64.b64decode(url.split(',',1)[1]))).convert('RGBA')
        (TMP/f'{id}-{expr}.rgba').write_bytes(im.tobytes())

def render_jobs(jobs,w,h):
    program="const fs=require('fs');\n"+(LIB/'runtime/render.js').read_text(encoding='utf-8')+'\n'+(OUT/'pose.js').read_text(encoding='utf-8')+'\n'
    for file in [LIB.parent/'yukkuri-v1/runtime/growth.js',LIB.parent/'yukkuri-v1/runtime/viviparous-birth.js',OUT/'birth.js']:
        program+=file.read_text(encoding='utf-8')+'\n'
    program+='const models=JSON.parse(fs.readFileSync('+json.dumps(str(OUT/'preview-data.json'))+'));\n'
    program+='const jobs='+json.dumps(jobs)+';const folder='+json.dumps(str(TMP))+';\n'
    program+=f"for(const j of jobs){{const e=models[j.id],tex=fs.readFileSync(folder+'/'+j.id+'-'+(j.expr||'neutral')+'.rgba');let overrides=null;if(j.part){{const q=j.amount??1;overrides=Object.fromEntries(e.model.anatomy.parts.map(p=>[p.bone,{{position:p.offset.map(v=>v*(p.id===j.part?q:0)),...(p.rotation?{{rotation:p.rotation.map(v=>v*(p.id===j.part?q:0))}}:{{}})}}]));}}const p=j.birth?RoundedBirth.render(e.model,Object.fromEntries(['neutral','angry','sleep'].map(k=>[k,fs.readFileSync(folder+'/'+j.id+'-'+k+'.rgba')])),j.t,j.count||3,{w},{h},j.az,j.el,j.span||16,{{centerY:j.center||10}}).pixels:renderModel(posed(e.model,j.action||'idle',j.t||0,overrides),tex,{w},{h},j.az,j.el,j.span||12.6,{{centerY:j.center||10,textureSize:e.model.texture_size||128}});fs.writeFileSync(folder+'/'+j.file+'.rgba',p);}}"
    (TMP/'render.cjs').write_text(program,encoding='utf-8')
    subprocess.run(['node',str(TMP/'render.cjs')],check=True)

def tile(file,w,h):
    im=Image.frombytes('RGBA',(w,h),(TMP/(file+'.rgba')).read_bytes())
    back=Image.new('RGB',(w,h),(241,237,227));back.paste(im,mask=im.getchannel('A'))
    return back

jobs=[dict(id=id,file=f'{id}-{v}',az=az,el=el) for id in MODELS for v,az,el in VIEWS]
render_jobs(jobs,500,480)
sheet=Image.new('RGB',(2000,4*535+80),(241,237,227));d=ImageDraw.Draw(sheet)
d.text((28,18),'捕食种身体移植 · 同比例、同光照对比（待确认）',font=TITLE,fill='#292a25')
for row,(id,label) in enumerate([('reimu_baseline','灵梦 · 原标准'),('reimu','灵梦 · 圆润身体'),('marisa_baseline','魔理沙 · 原标准'),('marisa','魔理沙 · 圆润身体')]):
    for col,(v,*_) in enumerate(VIEWS):
        x,y=col*500,80+row*535
        d.text((x+18,y+8),label+' / '+v,font=FONT,fill='#373a32')
        sheet.paste(tile(f'{id}-{v}',500,480),(x,y+46))
sheet.save(OUT/'comparison.png')
hero=Image.new('RGB',(1200,650),(241,237,227));d=ImageDraw.Draw(hero)
d.text((24,20),'灵梦 & 魔理沙 · 圆润身体试用',font=TITLE,fill='#292a25')
render_jobs([dict(id=id,file=id+'-hero',az=.5,el=.16,span=12.3,center=10) for id in ['reimu','marisa']],600,570)
for i,id in enumerate(['reimu','marisa']):hero.paste(tile(id+'-hero',600,570),(i*600,72))
hero.save(OUT/'overview.png')
print('Static previews rendered:',OUT/'overview.png')

ACTIONS=[('waddle','走路',.3),('hop','跳跃',.08),('stretch','伸展',1.4),('side_bend','侧弯',.59)]
jobs=[dict(id=id,action=action,t=t,file=f'{id}-{action}',az=.55,el=.12,span=16,center=12) for id in ['reimu','marisa'] for action,_,t in ACTIONS]
render_jobs(jobs,480,460)
motion=Image.new('RGB',(1920,1080),(241,237,227));d=ImageDraw.Draw(motion)
d.text((24,18),'圆润身体 · 动作实际姿态',font=TITLE,fill='#292a25')
for row,id in enumerate(['reimu','marisa']):
    for col,(action,label,_) in enumerate(ACTIONS):
        x,y=480*col,75+row*500
        d.text((x+20,y+5),('灵梦' if id=='reimu' else '魔理沙')+' / '+label,font=FONT,fill='#373a32')
        motion.paste(tile(f'{id}-{action}',480,460),(x,y+35))
motion.save(OUT/'actions.png')

if '--animations' in __import__('sys').argv:
    frames=40
    jobs=[]
    for action,_,_ in ACTIONS:
        duration=MODELS['reimu']['model']['clips'][action]['duration']
        for i in range(frames):
            for id in ['reimu','marisa']:
                jobs.append(dict(id=id,action=action,t=duration*i/frames,file=f'{id}-{action}-{i}',az=.55,el=.12,
                                 span=16 if action in ['stretch','side_bend'] else 12.6,center=12 if action in ['stretch','side_bend'] else 10))
    render_jobs(jobs,480,440)
    for action,label,_ in ACTIONS:
        sequence=[]
        for i in range(frames):
            frame=Image.new('RGB',(960,492),(241,237,227));d=ImageDraw.Draw(frame)
            d.text((18,12),'灵梦 / '+label,font=FONT,fill='#292a25');d.text((498,12),'魔理沙 / '+label,font=FONT,fill='#292a25')
            for j,id in enumerate(['reimu','marisa']):frame.paste(tile(f'{id}-{action}-{i}',480,440),(j*480,52))
            sequence.append(frame)
        duration=MODELS['reimu']['model']['clips'][action]['duration']
        # Hop is very short; repeat its real timing rather than slowing it down.
        # GIF has 10 ms resolution. Use fewer hop frames to retain its 0.262 s cycle.
        if action=='hop':sequence=[sequence[round(i*frames/13)%frames] for i in range(13)]
        ms=max(10,round(duration*1000/len(sequence)/10)*10)
        sequence[0].save(OUT/(action+'.gif'),save_all=True,append_images=sequence[1:],duration=ms,loop=0,optimize=False,disposal=2)
        contact=Image.new('RGB',(1920,2*480),(241,237,227));dc=ImageDraw.Draw(contact)
        for row,id in enumerate(['reimu','marisa']):
            for col,i in enumerate([0,10,20,30]):
                dc.text((col*480+10,row*480+5),id+' / '+label+f' / {i/40:.0%}',font=FONT,fill='#292a25')
                contact.paste(tile(f'{id}-{action}-{i}',480,440),(col*480,row*480+36))
        reports=HERE/'reports/current';reports.mkdir(parents=True,exist_ok=True)
        contact.save(reports/(action+'-phases.png'))
    print('Four animated previews rendered.')

if '--review' in __import__('sys').argv:
    review_views=[('waddle',.3,1.570796),('waddle',.9,3.141593),('stretch',1.4,1.570796),('stretch',1.4,3.141593),('side_bend',.59,1.570796),('side_bend',1.77,3.141593)]
    jobs=[dict(id=id,action=action,t=t,az=az,el=.1,span=16,center=12,file=f'{id}-review-{i}') for id in ['reimu','marisa'] for i,(action,t,az) in enumerate(review_views)]
    render_jobs(jobs,450,500)
    review=Image.new('RGB',(2700,2*545),(241,237,227));d=ImageDraw.Draw(review)
    for row,id in enumerate(['reimu','marisa']):
        for col,(action,t,az) in enumerate(review_views):
            d.text((col*450+10,row*545+5),f'{id} / {action} / {t}',font=FONT,fill='#292a25')
            review.paste(tile(f'{id}-review-{col}',450,500),(col*450,row*545+35))
    (HERE/'reports/current').mkdir(parents=True,exist_ok=True)
    review.save(HERE/'reports/current/side-back-motion.png')

if '--anatomy' in __import__('sys').argv:
    anatomy_views=[('front-rest','脸下 · 常态',0,None),('peni','佩尼佩尼 · 下移后',.55,'peni'),('rear-rest','油菊 · 常态',3.141593,None),('mamu','麻姆麻姆 · 同一前下方',0,'mamu'),('peni-side','佩尼佩尼 · 侧面',1.570796,'peni')]
    jobs=[dict(id=id,part=part,file=f'{id}-anatomy-{name}',az=az,el=.035,span=8.0,center=4.3) for id in ['reimu','marisa'] for name,_,az,part in anatomy_views]
    render_jobs(jobs,480,450)
    anatomy_sheet=Image.new('RGB',(2400,1070),(241,237,227));d=ImageDraw.Draw(anatomy_sheet)
    d.text((20,15),'圆润身体 · 油菊与伸缩部件近景（原有标准部件适配）',font=TITLE,fill='#292a25')
    for row,id in enumerate(['reimu','marisa']):
        for col,(name,label,*_) in enumerate(anatomy_views):
            x,y=col*480,75+row*490
            d.text((x+12,y+4),('灵梦' if id=='reimu' else '魔理沙')+' / '+label,font=FONT,fill='#292a25')
            anatomy_sheet.paste(tile(f'{id}-anatomy-{name}',480,450),(x,y+35))
    anatomy_sheet.save(OUT/'anatomy.png')
    print('Anatomy closeups rendered.')

if '--birth' in __import__('sys').argv:
    phases=[(0,'常态'),(9,'孕体'),(14.9,'首胎产出'),(17.3,'次胎产出'),(20.4,'末胎离体'),(26,'产后恢复')]
    jobs=[dict(id=id,birth=True,t=t,file=f'{id}-birth-{i}',az=.45,el=.14,span=16,center=10) for id in ['reimu','marisa'] for i,(t,_) in enumerate(phases)]
    render_jobs(jobs,480,480)
    sheet=Image.new('RGB',(2880,1110),(241,237,227));draw=ImageDraw.Draw(sheet)
    draw.text((20,15),'圆润身体 · 胎生适配（共用前下方出口，三胎演示）',font=TITLE,fill='#292a25')
    for row,id in enumerate(['reimu','marisa']):
        for col,(t,label) in enumerate(phases):
            x,y=col*480,70+row*520
            draw.text((x+10,y),('灵梦' if id=='reimu' else '魔理沙')+' / '+label,font=FONT,fill='#292a25')
            sheet.paste(tile(f'{id}-birth-{col}',480,480),(x,y+35))
    sheet.save(OUT/'birth.png')
    review=[(13,0),(14.3,0),(14.9,1.570796),(15.8,1.570796),(17.3,0),(26,3.141593)]
    jobs=[dict(id=id,birth=True,t=t,file=f'{id}-birth-review-{i}',az=az,el=.035,span=15,center=8) for id in ['reimu','marisa'] for i,(t,az) in enumerate(review)]
    render_jobs(jobs,520,520)
    contact=Image.new('RGB',(3120,1120),(241,237,227));draw=ImageDraw.Draw(contact)
    for row,id in enumerate(['reimu','marisa']):
        for col,(t,az) in enumerate(review):
            x,y=col*520,row*560
            draw.text((x+10,y+4),f'{id} / {t}s / {az:.2f}',font=FONT,fill='#292a25')
            contact.paste(tile(f'{id}-birth-review-{col}',520,520),(x,y+35))
    contact.save(HERE/'reports/current/birth-views.png')
    if '--birth-animation' in __import__('sys').argv:
        frames=81
        jobs=[dict(id=id,birth=True,t=i*.4,file=f'{id}-birth-frame-{i}',az=.5,el=.16,span=16,center=10) for i in range(frames) for id in ['reimu','marisa']]
        render_jobs(jobs,460,460);sequence=[]
        for i in range(frames):
            frame=Image.new('RGB',(920,500),(241,237,227));draw=ImageDraw.Draw(frame)
            draw.text((16,5),f'灵梦 / 魔理沙 · 胎生    {i*.4:.1f} / 32 秒',font=FONT,fill='#292a25')
            for j,id in enumerate(['reimu','marisa']):frame.paste(tile(f'{id}-birth-frame-{i}',460,460),(j*460,40))
            sequence.append(frame)
        sequence[0].save(OUT/'birth.gif',save_all=True,append_images=sequence[1:],duration=400,loop=0,optimize=False,disposal=2)
    print('Birth stages rendered.')
