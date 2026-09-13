from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import json
r=Path('reference/hakkei-u-expression-audit');rows=json.loads((r/'face-fields.json').read_text(encoding='utf-8'));unique={}
for row in rows:
 if not row['refs'] or not row['refs'][0]['id']:continue
 sid=row['refs'][0]['id'];unique.setdefault(sid,[]).append(row)
font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',12);records=[]
for idx,(sid,aliases) in enumerate(unique.items()):records.append(dict(index=idx,id=sid,fields=[a['field'] for a in aliases],section=aliases[0]['section']))
for page in range((len(records)+29)//30):
 chunk=records[page*30:(page+1)*30];sheet=Image.new('RGB',(1440,1080),'#e9dfca');draw=ImageDraw.Draw(sheet)
 for k,rec in enumerate(chunk):
  x=(k%6)*240;y=(k//6)*216;im=Image.open(r/f'sprites/sprite-{rec["id"]}.png').convert('RGBA');bbox=im.getbbox()
  if bbox:im=im.crop(bbox)
  im.thumbnail((225,160));sheet.paste(im,(x+(240-im.width)//2,y+8),im);draw.text((x+5,y+174),f'{rec["index"]} / {rec["id"]}',font=font,fill='#30291f');label=rec['fields'][0].replace('グラベース','');draw.text((x+5,y+192),label,font=font,fill='#30291f')
 sheet.save(r/f'contact-{page+1}.png')
(r/'unique-faces.json').write_text(json.dumps(records,ensure_ascii=False,indent=2),encoding='utf-8')
