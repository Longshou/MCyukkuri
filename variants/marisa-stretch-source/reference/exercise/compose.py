from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import json
r=Path('variants/marisa-stretch-source/reference');data=json.loads((r/'source-index.json').read_text(encoding='utf-8'));lookup={x['field']:x for x in data if x['index']==0};side={x['id']:x for x in json.loads((r/'exercise/sprite-index.json').read_text(encoding='utf-8'))}
font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',20);sheet=Image.new('RGB',(1500,440),'#eae7de');draw=ImageDraw.Draw(sheet)
for i,(sid,dx,dy,angle,label) in enumerate([(6482,-105,50,30,'左弯 30°'),(6291,-54,60,15,'左弯 15°'),(7138,0,60,0,'伸高直立'),(5468,54,60,-15,'右弯 15°'),(5114,105,50,-30,'右弯 30°')]):
 canvas=Image.new('RGBA',(800,800))
 for field in ['基本１おかざり裏グラベース','基本裏髪グラベース','BODY','基本顔面グラベース','基本表髪グラベース','基本１もみあげグラベース','基本１おかざりグラベース']:
  if field=='BODY':
   d=lookup['のび３ボディグラベース'] if sid==7138 else side[sid];im=Image.open((r if sid==7138 else r/'exercise')/d['file']).convert('RGBA');x,y=d.get('offset',d.get('texture_rect_offset'));canvas.alpha_composite(im,(round(x),800-round(y)-im.height));continue
  d=lookup[field];im=Image.open(r/d['file']).convert('RGBA');x,y=d['offset'];layer=Image.new('RGBA',(800,800));layer.alpha_composite(im,(round(x),800-round(y)-im.height));layer=layer.rotate(angle,resample=Image.Resampling.BICUBIC,center=(400,560));canvas.alpha_composite(layer,(dx,-dy))
 canvas.save(r/'exercise'/f'composite-{i}.png');im=canvas.crop((150,230,650,680));im.thumbnail((295,340));sheet.paste(im,(i*300+2,50),im);draw.text((i*300+30,15),label,font=font,fill='#393540');draw.text((i*300+15,400),'原始图层按代码重组',font=font,fill='#71695e')
sheet.save(r/'exercise/source-exercise.png')
