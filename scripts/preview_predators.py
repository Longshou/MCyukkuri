"""Build interactive preview and deterministic sheets of the selected runtime models."""
from pathlib import Path
import json
import subprocess
from PIL import Image, ImageDraw, ImageFont

ROOT=Path(__file__).resolve().parents[1]
OUT=ROOT/'art/source/predator-runtime'
SHEETS=ROOT/'standards/yukkuri-library/subspecies/predator/previews/current'
TMP=ROOT/'.local/predators/render'
for p in (SHEETS,TMP):p.mkdir(parents=True,exist_ok=True)
catalog=json.loads((OUT/'preview-data.json').read_text(encoding='utf-8'))
(OUT/'preview-data.js').write_text('const MODELS='+json.dumps(catalog,separators=(',',':'))+';\n',encoding='utf-8')
js=(ROOT/'art/source/standard-runtime/preview.js').read_text(encoding='utf-8')
js=js.replace('textureSize:128','textureSize:model.texture_size||128')
js=js.replace("const labels={","const labels={predator_fly:'振翅飞行',predator_dive:'俯冲',predator_land:'降落收翅',predator_bite:'咬取',")
(OUT/'preview.js').write_text(js,encoding='utf-8')
html=(ROOT/'art/source/standard-runtime/preview.html').read_text(encoding='utf-8')
start=html.index('<select id="subject">');end=html.index('</select>',start)
html=html[:start]+'<select id="subject"><option value="remilia">蕾米莉亚</option><option value="flandre">弗兰</option>'+html[end:]
html=html.replace('MC 接入模型 · 动作预览','捕食种 · 模型与动作预览')
start=html.index('<p>');end=html.index('</p>',start)
html=html[:start]+'<p>蕾米莉亚与弗兰。拖动旋转、滚轮缩放，可暂停逐帧查看飞行、俯冲与收翅。'+html[end:]
start=html.index('<p class="note">');end=html.index('</p>',start)
html=html[:start]+'<p class="note">本页使用当前游戏导出的三角面、混合骨骼绑定与动作数据。造型待用户确认；离线预览不代替游戏行为验收。'+html[end:]
(OUT/'preview.html').write_text(html,encoding='utf-8')
core=js[js.index('const clamp='):js.index('function groundVertices')]
renderer=(ROOT/'standards/yukkuri-library/runtime/render.js').read_text(encoding='utf-8')
views=[('front','idle',0,0,.12),('oblique','idle',0,.7,.2),('side','idle',0,1.5708,.12),('back','idle',0,3.14159,.18),
       ('fly-up','predator_fly',.15,.45,.16),('fly-down','predator_fly',.45,.45,.16),('dive','predator_dive',.3,.65,.10),('bite','predator_bite',.3,0,.12)]
for ident,entry in catalog.items():
    for expr,url in entry['textures'].items():
        Image.open(ROOT/f'src/main/resources/assets/mcyukkuri/textures/entity/{ident}/{expr}.png').convert('RGBA').save(TMP/f'{ident}-{expr}.png')
        (TMP/f'{ident}-{expr}.rgba').write_bytes(Image.open(TMP/f'{ident}-{expr}.png').tobytes())
program="const fs=require('fs');\n"+renderer+'\n'+core+'\n'
program+="const catalog=JSON.parse(fs.readFileSync("+json.dumps(str(OUT/'preview-data.json'))+"));const views="+json.dumps(views)+";\n"
program+="const out="+json.dumps(str(TMP))+";for(const [id,e] of Object.entries(catalog)){Object.assign(e.model.clips,e.model.behavior_clips);for(const [name,action,t,az,el] of views){const texture=fs.readFileSync(out+'/'+id+'-'+(action==='predator_bite'?'biting':'neutral')+'.rgba');const pixels=renderModel(posed(e.model,action,t),texture,600,440,az,el,8.8,{centerY:5.8,textureSize:256});fs.writeFileSync(out+'/'+id+'-'+name+'.rgba',pixels);}}"
(TMP/'render.cjs').write_text(program,encoding='utf-8')
subprocess.run(['node',str(TMP/'render.cjs')],check=True)
font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',23)
for ident in catalog:
    sheet=Image.new('RGB',(1200,4*484),(236,232,221));draw=ImageDraw.Draw(sheet)
    for i,(name,*_) in enumerate(views):
        im=Image.frombytes('RGBA',(600,440),(TMP/f'{ident}-{name}.rgba').read_bytes())
        x=(i%2)*600;y=(i//2)*484
        sheet.paste(im,(x,y+38),im);draw.text((x+18,y+8),ident+' / '+name,fill=(49,47,43),font=font)
    sheet.save(SHEETS/f'{ident}-views.png')
print('Preview and two 8-view sheets exported.')
