from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import json
r=Path(__file__).resolve().parent
data=json.loads((r/'sprite-index.json').read_text(encoding='utf-8')); byid={x['id']:x for x in data}
mapping=json.loads((r.parents[1]/'marisa-rump/reference/graphic-map.json').read_text(encoding='utf-8'))
for row in data:
    row['bindings']=[{'field':k,'index':i,'file_id':p['file']} for k,v in mapping.items() if isinstance(v,list)
                     for i,p in enumerate(v) if isinstance(p,dict) and p.get('id')==row['id']]
(r/'source-index.json').write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
board=Image.new('RGB',(1200,580),'#eae7de');d=ImageDraw.Draw(board);f=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',20)
for i,(ids,label) in enumerate([([7910,6503,6447,5869,7799],'准备 / 侧向'),([6728,7149,6533,6022,5472,4463],'压低 / 中间'),([4414,5590,7470,6787,8302,7676],'抬臀 / 用力')]):
    c=Image.new('RGBA',(800,800))
    for sid in ids:
        row=byid[sid];im=Image.open(r/row['file']).convert('RGBA');x,y=row['texture_rect_offset'];c.alpha_composite(im,(round(x),800-round(y)-im.height))
    c.save(r/f'composite-{i}.png');crop=c.crop((180,300,620,700)).resize((396,360));board.paste(crop,(i*400,80),crop)
    d.text((i*400+22,25),label,font=f,fill='#393540');d.text((i*400+15,495),'原始图层重组 · 非游戏截图',font=f,fill='#635c59')
board.save(r/'source-taiso.png')
