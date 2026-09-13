"""Render the current draft's real runtime geometry with the existing rasterizer."""
from pathlib import Path
import json
import subprocess
from PIL import Image, ImageDraw, ImageFont

HERE=Path(__file__).resolve().parent
LIB=HERE.parent
ROOT=LIB.parents[1]
OUT=HERE/'previews/current'
TMP=ROOT/'.local/rounded-standard-body/standard-render'
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


labels=json.loads((HERE/'recipe.json').read_text(encoding='utf-8'))['labels']
jobs=[dict(id=id,file=f'{id}-{v}',az=az,el=el,span=17,center=10) for id in MODELS for v,az,el in VIEWS]
render_jobs(jobs,500,480)
overview=Image.new('RGB',(1600,2*440+65),(241,237,227));d=ImageDraw.Draw(overview)
d.text((24,16),'圆润模型 · 本轮头发修复（待确认）',font=TITLE,fill='#292a25')
for row,id in enumerate(MODELS):
    sheet=Image.new('RGB',(2000,540),(241,237,227));ds=ImageDraw.Draw(sheet)
    for col,(v,*_) in enumerate(VIEWS):
        ds.text((col*500+18,12),labels[id]+' / '+v,font=FONT,fill='#373a32')
        sheet.paste(tile(f'{id}-{v}',500,480),(col*500,55))
    sheet.save(OUT/(id+'-views.png'))
    thumb=tile(f'{id}-斜侧',500,480);thumb.save(OUT/(id+'.png'))
    x,y=(row%4)*400,65+(row//4)*440
    d.text((x+20,y+6),labels[id],font=FONT,fill='#292a25')
    overview.paste(thumb.resize((400,384)),(x,y+45))
overview.save(OUT/'overview.png')
print('Rendered eight current models in four views.')

render_jobs([dict(id=id,file=id+'-size-compare',az=.45,el=.12,span=17,center=10) for id in ['reimu','reimu_deibu']],600,580)
comparison=Image.new('RGB',(1200,650),(241,237,227));d=ImageDraw.Draw(comparison)
for col,(id,label) in enumerate([('reimu','普通灵梦 · 1 倍'),('reimu_deibu','灵胖 · 1.5 倍')]):
    d.text((col*600+22,20),label,font=TITLE,fill='#292a25')
    comparison.paste(tile(id+'-size-compare',600,580),(col*600,65))
comparison.save(OUT/'deibu-size-comparison.png')

if '--actions' in __import__('sys').argv:
    jobs=[];selected={}
    for id,e in MODELS.items():
        clips=e['model']['clips']
        actions=['shell_sway','shell_retract','anatomy_peni_extend','anatomy_mamu_extend'] if id=='marisa_tsumuri' else ['waddle','stretch','side_bend','fur_sway' if id=='reimu_wasa' else 'turn']
        actions=[a for a in actions if a in clips]
        selected[id]=actions
        for a in actions:jobs.append(dict(id=id,file=id+'-action-'+a,action=a,t=(.59 if a=='side_bend' else 1.4 if a=='stretch' else clips[a]['duration']*.5),az=.55,el=.14,span=18 if a in ['stretch','side_bend'] else 13.5,center=12 if a in ['stretch','side_bend'] else 10))
    render_jobs(jobs,480,460)
    for id,actions in selected.items():
        sheet=Image.new('RGB',(480*len(actions),515),(241,237,227));d=ImageDraw.Draw(sheet)
        for i,a in enumerate(actions):
            d.text((i*480+15,12),labels[id]+' / '+a,font=FONT,fill='#292a25')
            sheet.paste(tile(id+'-action-'+a,480,460),(i*480,50))
        sheet.save(HERE/'reports/current'/(id+'-actions.png'))
    print('Rendered current per-character action poses.')
