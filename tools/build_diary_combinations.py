"""Independent component trials + three original combined MC model alternatives."""
from pathlib import Path
import shutil, subprocess, sys, json, importlib.util, base64
from PIL import Image, ImageDraw, ImageFont
ROOT=Path(__file__).resolve().parents[1]
BASE=ROOT/'variants/marisa-gentle'
OUT=ROOT/'variants/diary-combinations'
OUT.mkdir(exist_ok=True)
ENGINE=ROOT/'tools/face_layers.js'
presets={
 '01-lively':dict(title='01 · 活泼开心',detail='眼睛舒展、略向上看、圆底笑口、轻薄中分刘海',aperture=.88,gazeX=0,gazeY=-.30,mouth=.67,brow=.1,hair='taper'),
 '02-relaxed':dict(title='02 · 悠闲亲近',detail='眼皮略放松、小笑嘴、偏分刘海，接近保留版的温和感',aperture=.69,gazeX=0,gazeY=0,mouth=.18,brow=0,hair='sweep'),
 '03-curious':dict(title='03 · 好奇期待',detail='双眼同向轻瞟、轻提眉、中等笑口、轻薄中分刘海',aperture=.94,gazeX=-.32,gazeY=-.12,mouth=.42,brow=.35,hair='taper')
}

def face_png(p,where):
    raw=where.with_suffix('.rgba')
    subprocess.run(['node',str(ENGINE),json.dumps(p),str(raw)],check=True,capture_output=True)
    im=Image.frombytes('RGBA',(128,128),raw.read_bytes());im.save(where);raw.unlink()
    return im

source=(BASE/'generate.py').read_text(encoding='utf-8')
original_bangs="[(-6,1.6,6.6),(-4.4,1.7,7.5),(-2.7,1.8,7),(-.9,2,6.3),(1.1,1.7,7.2),(2.8,1.6,7.6),(4.4,1.6,6.8)]"
modules={};data=[]
for key,p in presets.items():
    folder=OUT/key;folder.mkdir(exist_ok=True)
    face_png(p,folder/'face-swatch.png')
    s=source.replace('y=48+(i//8)*16','y=96+(i//8)*16')
    a=s.index('# Broad, low face:');b=s.index("atlas.save(ROOT/'marisa.png')",a)
    s=s[:a]+"# Original layered pixel expression; see ../face_layers.js.\nface=Image.open(ROOT/'face-swatch.png').crop((0,0,96,64)).convert('RGB')\natlas.paste(face,(0,0)); uv['face']=[0,0,96,64]\n"+s[b:]
    s='\n'.join(line for line in s.split('\n') if "cube('lower_curve'" not in line)
    s=s.replace("groups=['head'", "# Use the approved base without the separate bottom tier.\nfor c in cubes:\n    c['lo'][1]-=1\n    c['hi'][1]-=1\n\ngroups=['head'")
    s=s.replace("[0,8,0]","[0,7,0]").replace('[0,9.3,0]','[0,8.8,0]')
    s=s.replace("for xx,yy in [(2,3),(9,8),(13,1)]", "for xx,yy in [(9,8)]")
    if p['hair']=='taper':
        s=s.replace(original_bangs,"[(-6,1.6,6.7),(-4.4,1.7,7.35),(-2.7,1.8,7.8),(-.9,2,6.3),(1.1,1.7,7.7),(2.8,1.6,7.3),(4.4,1.6,6.6)]")
    else:
        s=s.replace(original_bangs,"[(-6,1.6,6.5),(-4.4,1.7,7.0),(-2.7,1.8,7.6),(-.9,2,7.9),(1.1,1.7,7.25),(2.8,1.6,6.8),(4.4,1.6,6.5)]")
    s=s.replace("[x,bottom,-5.05]", "[x,bottom+.55,-5.05]")
    s=s.replace("[x+.35,bottom-.45,-5.02],[x+w-.2,bottom,-4.62]", "[x+.4,bottom-.5,-5.02],[x+w-.35,bottom+.55,-4.62]")
    s=s.replace('魔理沙 · Minecraft 方块模型初稿',p['title'])
    (folder/'generate.py').write_text(s,encoding='utf-8')
    for name in ['software-viewer.js','viewer-template.html']:
        content=(BASE/name).read_text(encoding='utf-8').replace('-9.3','-8.8').replace('54 个方块','53 个方块').replace('魔理沙 · 悠闲一点吧',p['title'])
        (folder/name).write_text(content,encoding='utf-8')
    subprocess.run([sys.executable,str(folder/'generate.py')],check=True,capture_output=True)
    spec=importlib.util.spec_from_file_location('combo_'+key,folder/'generate.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);modules[key]=m
    vertices=[]
    for c in m.cubes:
        for points,uvs,normal in m.faces(c):
            for j in [0,1,2,0,2,3]:vertices.extend([*points[j],uvs[j][0]/128,uvs[j][1]/128,*normal])
    data.append(dict(key=key,**p,vertices=vertices,texture='data:image/png;base64,'+base64.b64encode((folder/'marisa.png').read_bytes()).decode()))
    (folder/'settings.json').write_text(json.dumps(p,ensure_ascii=False,indent=2),encoding='utf-8')
    (folder/'README.md').write_text('# '+p['title']+'\n\n'+p['detail']+'。53方块、去底层基准。本目录为原创合并候选，不覆盖其他版本。\n\npreview.html 为静态表情旋转预览；../index.html 可调整眼皮、视线、嘴巴和眨眼。marisa.bbmodel / marisa.geo.json / marisa.png 与资源包为本预设的静态导出。交互试验尚未接入 Minecraft 动态实体。\n',encoding='utf-8')

# Separate one-factor tests. Only the named variable changes in each row.
studies=OUT/'studies';studies.mkdir(exist_ok=True)
seed=dict(aperture=.88,gazeX=0,gazeY=0,mouth=.42,brow=.1)
tests=[('眼皮开合',[('放松',dict(aperture=.55)),('自然',dict(aperture=.82)),('睁大',dict(aperture=1))]),
       ('双眼共同视线',[('向左',dict(gazeX=-.7)),('看前方',dict(gazeX=0)),('略向上',dict(gazeY=-.45))]),
       ('嘴巴开合',[('闭口笑',dict(mouth=0)),('小笑口',dict(mouth=.35)),('开心笑口',dict(mouth=.75))]),
       ('刘海轮廓',[('原直刘海',dict(hair='original')),('收尖中分',dict(hair='taper')),('错落偏分',dict(hair='sweep'))])]
font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',23);small=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',16)
board=Image.new('RGB',(1350,1840),'#eae7de');bd=ImageDraw.Draw(board)
for row,(heading,choices) in enumerate(tests):
    bd.text((24,row*460+12),heading,font=font,fill='#393540')
    for col,(label,change) in enumerate(choices):
        settings={**seed,**change};module=modules['02-relaxed' if change.get('hair')=='sweep' else '01-lively']
        oldatlas=module.atlas.copy();oldcubes=module.cubes
        # Reconstruct the original bang arrangement only for the hair control.
        if change.get('hair')=='original':
            import copy
            module.cubes=copy.deepcopy(oldcubes)
            positions=[(-6,1.6,6.6),(-4.4,1.7,7.5),(-2.7,1.8,7),(-.9,2,6.3),(1.1,1.7,7.2),(2.8,1.6,7.6),(4.4,1.6,6.8)]
            for c in module.cubes:
                if c['name'].startswith('bang_'):
                    i=int(c['name'].split('_')[-1]);x,w,b=positions[i]
                    if 'tip' in c['name']:c['lo']=[x+.35,b-.45-1,-5.02];c['hi']=[x+w-.2,b-1,-4.62]
                    else:c['lo']=[x,b-1,-5.05];c['hi']=[x+w,8.5,-4.58]
        face=face_png(settings,studies/f'{row}-{col}-face.png');module.atlas.paste(face.crop((0,0,96,64)).convert('RGB'),(0,0))
        im=module.render(0,0,390);im.save(studies/f'{row}-{col}.png');board.paste(im,(col*450+30,row*460+42));bd.text((col*450+25,row*460+428),label,font=small,fill='#635c53')
        module.atlas=oldatlas;module.cubes=oldcubes
board.save(OUT/'component-trials.png')
combined=Image.new('RGB',(1500,1000),'#eae7de');cd=ImageDraw.Draw(combined)
for i,(key,p) in enumerate(presets.items()):
    m=modules[key];combined.paste(m.render(0,0,490),(i*500,45));combined.paste(m.render(-30,12,490),(i*500,510))
    cd.text((i*500+25,12),p['title'],font=font,fill='#393540')
combined.save(OUT/'combined-options.png')
shutil.copy2(ENGINE,OUT/'face_layers.js')
raster=(BASE/'software-viewer.js').read_text(encoding='utf-8').split("if(typeof document!=='undefined')")[0].replace('-9.3','-8.8')
(OUT/'raster.js').write_text(raster,encoding='utf-8')
(OUT/'model-data.js').write_text('const COMBINATIONS='+json.dumps(data,ensure_ascii=False,separators=(',',':'))+';',encoding='utf-8')
print('Created 12 one-factor studies and 3 complete combined candidates.')
