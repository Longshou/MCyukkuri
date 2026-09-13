"""Export only the original cold-storage cabinet, recipe, loot and current geometry preview."""
from pathlib import Path
import base64
import hashlib
import io
import json
import math
import sys
import uuid
import numpy as np
from PIL import Image, ImageDraw, ImageFont

HERE = Path(__file__).resolve().parent
ROOT = HERE.parents[3]
ASSETS = ROOT / 'src/main/resources/assets/mcyukkuri'
DATA = ROOT / 'src/main/resources/data/mcyukkuri'
SELECTED = ROOT / 'art/props/cold-storage/selected'
recipe = json.loads((HERE / 'recipe.json').read_text(encoding='utf-8'))
palette = recipe['palette']
texture = Image.new('RGB', (16, 16))
paint = ImageDraw.Draw(texture)
for i, color in enumerate(palette):
    x, y = (i % 4) * 4, (i // 4) * 4
    paint.rectangle((x, y, x+3, y+3), fill=color)
buffer = io.BytesIO(); texture.save(buffer, format='PNG'); png = buffer.getvalue()
elements = []
bb_elements = []
for source in recipe['elements']:
    i = source['color']; x, y = (i % 4) * 4, (i // 4) * 4
    uv = [x+1, y+1, x+2, y+2]
    faces = {face: {'uv': uv, 'texture': '#cabinet'} for face in ('north', 'south', 'east', 'west', 'up', 'down')}
    elements.append({'name':source['name'], 'from':source['from'], 'to':source['to'], 'faces':faces})
    bb_elements.append({'name':source['name'], 'type':'cube', 'uuid':str(uuid.uuid5(uuid.NAMESPACE_URL, 'mcyukkuri:cold_storage/'+source['name'])),
                        'from':source['from'], 'to':source['to'], 'origin':[8,8,8],
                        'faces':{face:{'uv':uv,'texture':0} for face in faces}})
model = {'parent':'minecraft:block/block', 'textures':{'cabinet':'mcyukkuri:block/cold_storage','particle':'mcyukkuri:block/cold_storage'}, 'elements':elements,
         'display':{'gui':{'rotation':[30,225,0],'translation':[0,0,0],'scale':[.62,.62,.62]},
                    'ground':{'translation':[0,3,0],'scale':[.25,.25,.25]},
                    'fixed':{'scale':[.5,.5,.5]}}}
bb = {'meta':{'format_version':'4.10','model_format':'java_block','box_uv':False},'name':'cold_storage',
      'resolution':{'width':16,'height':16},'elements':bb_elements,'outliner':[e['uuid'] for e in bb_elements],
      'textures':[{'id':'0','name':'cold_storage.png','uuid':str(uuid.uuid5(uuid.NAMESPACE_URL,'mcyukkuri:cold_storage/texture')),'source':'data:image/png;base64,'+base64.b64encode(png).decode()}]}
outputs = {}
def document(path, value): outputs[path] = (json.dumps(value, indent=2, ensure_ascii=False)+'\n').encode('utf-8')
for path in (HERE/'assembled/cold_storage.json', SELECTED/'cold_storage.json', ASSETS/'models/block/cold_storage.json'): document(path,model)
for path in (HERE/'assembled/cold_storage.bbmodel',SELECTED/'cold_storage.bbmodel'): document(path,bb)
for path in (HERE/'assembled/cold_storage.png',SELECTED/'cold_storage.png',ASSETS/'textures/block/cold_storage.png'): outputs[path]=png
document(ASSETS/'models/item/cold_storage.json',{'parent':'mcyukkuri:block/cold_storage'})
document(ASSETS/'blockstates/cold_storage.json',{'variants':{f'facing={face}':{'model':'mcyukkuri:block/cold_storage','y':rotation} for face,rotation in [('north',0),('east',90),('south',180),('west',270)]}})
document(DATA/'recipe/cold_storage.json',{'type':'minecraft:crafting_shaped','category':'misc','pattern':['IPI','SCS','IRI'],
    'key':{'I':{'item':'minecraft:iron_ingot'},'P':{'item':'minecraft:ender_pearl'},'S':{'item':'minecraft:snow_block'},'C':{'item':'minecraft:chest'},'R':{'item':'minecraft:redstone'}},
    'result':{'id':'mcyukkuri:cold_storage','count':1}})
document(DATA/'loot_table/blocks/cold_storage.json',{'type':'minecraft:block','pools':[{'rolls':1,'entries':[{'type':'minecraft:item','name':'mcyukkuri:cold_storage'}],
    'conditions':[{'condition':'minecraft:survives_explosion'}]}]})

# Orthographic views of the exported cuboids, with depth-sorted opaque faces.
sheet=Image.new('RGB',(1200,1000),'#eaf0ed'); draw=ImageDraw.Draw(sheet)
try: font=ImageFont.truetype('C:/Windows/Fonts/segoeui.ttf',24)
except OSError: font=ImageFont.load_default()
for view,(azimuth,title) in enumerate([(0,'FRONT'),(40,'FRONT / SIDE'),(90,'SIDE'),(145,'BACK / SIDE')]):
    cx=300+(view%2)*600; cy=275+(view//2)*490
    angle=math.radians(azimuth); elevation=math.radians(18 if view in (1,3) else 0)
    ca,sa=math.cos(angle),math.sin(angle); ce,se=math.cos(elevation),math.sin(elevation)
    def project(point):
        x,y,z=point[0]-8,point[1]-8,point[2]-8
        xx=x*ca+z*sa; zz=-x*sa+z*ca
        return (cx-xx*20,cy-(y*ce+zz*se)*20,zz*ce-y*se)
    polygons=[]
    face_defs=[([0,1,3,2],.94,(0,0,-1)),([4,6,7,5],.65,(0,0,1)),([0,4,5,1],.72,(0,-1,0)),([2,3,7,6],1.0,(0,1,0)),([0,2,6,4],.82,(-1,0,0)),([1,5,7,3],.86,(1,0,0))]
    for source in recipe['elements']:
        a,b=source['from'],source['to']
        points=[(x,y,z) for z in (a[2],b[2]) for y in (a[1],b[1]) for x in (a[0],b[0])]
        base=tuple(bytes.fromhex(palette[source['color']][1:]))
        for indexes,shade,normal in face_defs:
            if (-normal[0]*sa+normal[2]*ca)*ce-normal[1]*se >= -0.0001: continue
            projected=[project(points[k]) for k in indexes]
            polygons.append((projected,tuple(int(c*shade) for c in base)))
    draw.ellipse((cx-195,cy+163,cx+195,cy+184),fill='#ccd6d1')
    canvas=np.array(sheet); depth_buffer=np.full((1000,1200),np.inf)
    for points,color in polygons:
        for corners in ((0,1,2),(0,2,3)):
            a,b,c=(points[i] for i in corners)
            x0=max(0,int(min(a[0],b[0],c[0]))); x1=min(1199,int(max(a[0],b[0],c[0]))+1)
            y0=max(0,int(min(a[1],b[1],c[1]))); y1=min(999,int(max(a[1],b[1],c[1]))+1)
            yy,xx=np.mgrid[y0:y1+1,x0:x1+1];xx=xx+.5;yy=yy+.5
            divisor=(b[1]-c[1])*(a[0]-c[0])+(c[0]-b[0])*(a[1]-c[1])
            if abs(divisor)<1e-8:continue
            wa=((b[1]-c[1])*(xx-c[0])+(c[0]-b[0])*(yy-c[1]))/divisor
            wb=((c[1]-a[1])*(xx-c[0])+(a[0]-c[0])*(yy-c[1]))/divisor;wc=1-wa-wb
            depth=wa*a[2]+wb*b[2]+wc*c[2]
            area=depth_buffer[y0:y1+1,x0:x1+1];mask=(wa>=0)&(wb>=0)&(wc>=0)&(depth<area)
            area[mask]=depth[mask];canvas[y0:y1+1,x0:x1+1][mask]=color
    sheet=Image.fromarray(canvas);draw=ImageDraw.Draw(sheet)
    draw.text((cx-125,cy+200),title,fill='#38566b',font=font)
draw.text((28,18),'COLD STORAGE  /  ORIGINAL DRAFT  /  16 px = 1 block',fill='#38566b',font=font)
out=io.BytesIO();sheet.save(out,format='PNG')
outputs[ROOT/'art/props/cold-storage/previews/current/model-sheet.png']=out.getvalue()
outputs[HERE/'previews/current/model-sheet.png']=out.getvalue()
manifest={'status':'pending_user_confirmation','source':str((HERE/'recipe.json').relative_to(ROOT)).replace('\\','/'),
          'source_sha256':hashlib.sha256((HERE/'recipe.json').read_bytes()).hexdigest(),
          'geometry':'26 static opaque cuboids; no bone or animation; vanilla Java block model',
          'outputs':{str(path.relative_to(ROOT)).replace('\\','/'):hashlib.sha256(content).hexdigest() for path,content in outputs.items()}}
document(ROOT/'art/props/cold-storage/manifest.json',manifest)
check='--check' in sys.argv
failed=[]
for path,content in outputs.items():
    if check:
        if not path.is_file() or path.read_bytes()!=content:failed.append(str(path.relative_to(ROOT)))
    else:path.parent.mkdir(parents=True,exist_ok=True);path.write_bytes(content)
if failed:raise SystemExit('Stale exports: '+', '.join(failed))
print(('Verified' if check else 'Exported'),len(outputs),'cold-storage files')
