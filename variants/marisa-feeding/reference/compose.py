from pathlib import Path
import json
from PIL import Image, ImageDraw, ImageFont
ROOT=Path(__file__).resolve().parent
records={r['id']:r for r in json.loads((ROOT/'sprite-index.json').read_text(encoding='utf-8'))}
font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',19)
sheet=Image.new('RGB',(1280,440),'#eee9df');draw=ImageDraw.Draw(sheet)
for i,face in enumerate([7193,4462,4474,5353]):
    canvas=Image.new('RGBA',(800,800))
    for sid in [8093,7910,4831,face,4872,6044]:
        r=records[sid];im=Image.open(ROOT/r['file']).convert('RGBA');x,y=r['texture_rect_offset']
        canvas.alpha_composite(im,(round(x),800-round(y)-im.height))
    canvas.save(ROOT/f'composite-{i+1}.png')
    crop=canvas.crop((200,250,650,700));crop.thumbnail((310,310))
    sheet.paste(crop,(i*320,65),crop)
    draw.text((i*320+20,18),f'咀嚼 {i+1} · Sprite {face}',font=font,fill='#403b32')
    draw.text((i*320+16,405),'原始图层重组 · 相同缩放',font=font,fill='#70665b')
sheet.save(ROOT/'source-chewing.png')
