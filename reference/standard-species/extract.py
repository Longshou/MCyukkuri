from pathlib import Path
import json,subprocess,sys
from PIL import Image,ImageDraw
R=Path(__file__).resolve().parent
m=json.loads((R.parent/'hakkei-u-expression-audit/graphic-map-verified.json').read_text(encoding='utf-8-sig'))
fields=['基本おかざり裏グラベース','基本ボディグラベース','基本髪グラベース','基本もみあげグラベース','基本おかざりグラベース','基本顔面グラベース']
rows=[dict(field=f,slot=i,**v) for f in fields for i,v in enumerate(m[f][:5]) if v['id']]
(R/'fields.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2),encoding='utf-8')
ids=sorted(set(x['id'] for x in rows))
subprocess.run([sys.executable,'C:/Users/shaoli/.codex/skills/hakkei-u-art-reference/scripts/extract_sprites.py','--assets','E:/yukkuri/eightSight.v8.3/ゆっくり八景U_Data/sharedassets0.assets','--ids',*map(str,ids),'--out',str(R/'hakkei')],check=True)
idx={x['id']:x for x in json.loads((R/'hakkei/sprite-index.json').read_text())}
sheet=Image.new('RGB',(1500,800),'#ebe7df');d=ImageDraw.Draw(sheet)
for slot in range(5):
 canvas=Image.new('RGBA',(800,800))
 for f in fields:
  s=slot if f not in ['基本ボディグラベース','基本顔面グラベース'] else 0
  sid=m[f][s]['id']
  if not sid:continue
  a=idx[sid];im=Image.open(R/'hakkei'/a['file']).convert('RGBA');x,y=a['texture_rect_offset'];canvas.alpha_composite(im,(round(x),round(800-y-im.height)))
 canvas.crop((160,380,640,780)).resize((300,250)).save(R/f'hakkei-slot-{slot}.png')
 sheet.paste(canvas.crop((160,380,640,780)).resize((300,250)),(slot*300,30),canvas.crop((160,380,640,780)).resize((300,250)))
 d.text((slot*300+10,10),f'Species slot {slot}',fill='black')
 for j,f in enumerate(['基本髪グラベース','基本もみあげグラベース','基本おかざりグラベース']):
  a=idx[m[f][slot]['id']];im=Image.open(R/'hakkei'/a['file']).convert('RGBA');im.thumbnail((290,140));sheet.paste(im,(slot*300+5,300+j*160),im);d.text((slot*300+5,285+j*160),str(a['id']),fill='black')
sheet.save(R/'hakkei-contact.png')
