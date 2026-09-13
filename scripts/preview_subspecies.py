#!/usr/bin/env python3
"""Render the selected runtime triangles and build an offline interactive preview."""
from pathlib import Path
import base64, json, subprocess
from PIL import Image, ImageDraw, ImageFont
from export_ecology_expressions import IDS as ECOLOGY_EXPRESSIONS

ROOT=Path(__file__).resolve().parents[1]; OUT=ROOT/'art/source/subspecies-runtime'; TMP=ROOT/'.local/subspecies-port/render'
TMP.mkdir(parents=True,exist_ok=True); SHEETS=OUT/'previews/current'; SHEETS.mkdir(parents=True,exist_ok=True)
manifest=json.loads((OUT/'manifest.json').read_text(encoding='utf-8')); catalog={}
for ident,record in manifest['models'].items():
    model=json.loads((OUT/ident/'runtime.model.json').read_text(encoding='utf-8'))
    textures={expr:'data:image/png;base64,'+base64.b64encode((OUT/ident/'textures'/(expr+'.png')).read_bytes()).decode() for expr in record['textures']}
    model['texture_size']=Image.open(OUT/ident/'textures/neutral.png').width
    catalog[ident]={'model':model,'textures':textures}
    (TMP/(ident+'.rgba')).write_bytes(Image.open(OUT/ident/'textures/neutral.png').convert('RGBA').tobytes())
    for expression in ECOLOGY_EXPRESSIONS:
        (TMP/(ident+'-'+expression+'.rgba')).write_bytes(Image.open(OUT/ident/'textures'/(expression+'.png')).convert('RGBA').tobytes())
(OUT/'preview-data.js').write_text('const MODELS='+json.dumps(catalog,separators=(',',':'))+';',encoding='utf-8')
(TMP/'catalog.json').write_text(json.dumps(catalog,separators=(',',':')),encoding='utf-8')
js=(ROOT/'art/source/standard-runtime/preview.js').read_text(encoding='utf-8').replace('textureSize:128','textureSize:model.texture_size||128')
js=js.replace("const labels={","const labels={still:'静止',shell_sway:'壳体轻摆',shell_retract:'缩壳探出',fur_sway:'鬓毛轻摆',")
(OUT/'preview.js').write_text(js,encoding='utf-8')
html=(ROOT/'art/source/standard-runtime/preview.html').read_text(encoding='utf-8')
labels={'marisa_tsumuri':'螺壳魔理沙','reimu_wasa':'多毛灵梦','reimu_sparse':'灵梦 · 稀疏毛发','reimu_face':'灵梦 · 面部不足','reimu_bean':'灵梦 · 豆形细长','marisa_sparse':'魔理沙 · 稀疏毛发','marisa_face':'魔理沙 · 面部不足','marisa_bean':'魔理沙 · 豆形细长'}
start=html.index('<select id="subject">');end=html.index('</select>',start)
html=html[:start]+'<select id="subject">'+''.join(f'<option value="{k}">{labels[k]}</option>' for k in catalog)+html[end:]
start=html.index('<select id="expression">');end=html.index('</select>',start)
html=html[:start]+'<select id="expression">'+''.join(f'<option value="{k}">{k}</option>' for k in next(iter(catalog.values()))['textures'])+html[end:]
html=html.replace('MC 接入模型 · 动作预览','亚种与先天形态 · 当前运行模型')
start=html.index('<p>');end=html.index('</p>',start)
html=html[:start]+'<p>螺壳复用已有确认造型。其余为待确认适配。拖动旋转、滚轮缩放，可暂停检查动作。'+html[end:]
start=html.index('<p class="note">');end=html.index('</p>',start)
html=html[:start]+'<p class="note">使用本轮运行三角面、骨架和贴图。生长、乘船和受伤以真实客户端验收为准。'+html[end:]
(OUT/'preview.html').write_text(html,encoding='utf-8')
core=js[js.index('const clamp='):js.index('function groundVertices')]
renderer=(ROOT/'standards/yukkuri-library/runtime/render.js').read_text(encoding='utf-8')
views=[('front','idle',0,0,.08),('oblique','idle',0,.7,.16),('side','idle',0,1.5708,.10),('back','idle',0,3.14159,.14),('walk','waddle',.2,.6,.14),('bend','side_bend',.7,.65,.12)]
program="const fs=require('fs');\n"+renderer+'\n'+core+'\n'
expression_views=[(expr,'idle',0,0,.08) for expr in ECOLOGY_EXPRESSIONS]
program+='const catalog=JSON.parse(fs.readFileSync('+json.dumps(str(TMP/'catalog.json'))+'));const views='+json.dumps(views+expression_views)+';\n'
program+='const out='+json.dumps(str(TMP))+";for(const [id,e]of Object.entries(catalog)){let lo=Infinity,hi=-Infinity,r=0;for(let i=0;i<e.model.vertices.length;i+=8){const v=e.model.vertices;lo=Math.min(lo,v[i+1]);hi=Math.max(hi,v[i+1]);r=Math.max(r,Math.hypot(v[i],v[i+2]));}for(const [name,action,t,az,el]of views){const texture=fs.readFileSync(out+'/'+id+(e.textures[name]?'-'+name:'')+'.rgba');const pixels=renderModel(posed(e.model,action,t),texture,520,440,az,el,Math.max((hi-lo)*.73,r*.94),{centerY:(hi+lo)/2,textureSize:e.model.texture_size});fs.writeFileSync(out+'/'+id+'-'+name+'-render.rgba',pixels);}}"
(TMP/'render.cjs').write_text(program,encoding='utf-8');subprocess.run(['node',str(TMP/'render.cjs')],check=True)
font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',20)
for ident in catalog:
    sheet=Image.new('RGB',(1040,1440),(236,232,221)); draw=ImageDraw.Draw(sheet)
    for i,(name,*_) in enumerate(views):
        im=Image.frombytes('RGBA',(520,440),(TMP/f'{ident}-{name}-render.rgba').read_bytes());x=(i%2)*520;y=(i//2)*480
        sheet.paste(im,(x,y+38),im);draw.text((x+14,y+7),labels[ident]+' / '+name,fill=(49,47,43),font=font)
    sheet.save(SHEETS/(ident+'-views.png'))
sheet=Image.new('RGB',(1300,2000),(236,232,221));draw=ImageDraw.Draw(sheet)
for row,ident in enumerate(catalog):
    for col,expression in enumerate(ECOLOGY_EXPRESSIONS):
        im=Image.frombytes('RGBA',(520,440),(TMP/f'{ident}-{expression}-render.rgba').read_bytes()).resize((260,220))
        x=col*260;y=row*250
        sheet.paste(im,(x,y+30),im);draw.text((x+6,y+3),labels[ident]+' / '+expression,fill=(49,47,43),font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',12))
sheet.save(SHEETS/'ecology-expressions.png')
print('Eight current geometry sheets, 40 expression views and interactive preview written.')
