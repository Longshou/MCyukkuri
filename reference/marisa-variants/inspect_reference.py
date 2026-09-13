from pathlib import Path
import json,re
from PIL import Image,ImageDraw
root=Path(__file__).parent
records=json.loads((root/'hakkei/sprite-index.json').read_text(encoding='utf-8'))
sheet=Image.new('RGB',(1000,((len(records)+4)//5)*230),'#dedbd1'); draw=ImageDraw.Draw(sheet)
for i,r in enumerate(records):
 im=Image.open(root/'hakkei'/r['file']).convert('RGBA');im.thumbnail((190,195))
 x=(i%5)*200;y=(i//5)*230
 sheet.paste(im,(x+(200-im.width)//2,y+20),im);draw.text((x+10,y+3),str(r['id']),fill='black')
sheet.save(root/'hakkei-contact.png')
base=Path('E:/yukkuri/Eight景U-ModLab/analysis/decompiled')
hits=[]
for f in base.glob('*.cs'):
 for n,line in enumerate(f.read_text(encoding='utf-8-sig').splitlines(),1):
  if any(k in line for k in ['つむ','ツム','水生','こた','コタ','貝','種族 == 5','種類 == 5']):hits.append(f'{f.name}:{n}: {line.strip()}')
(root/'code-hits.txt').write_text('\n'.join(hits),encoding='utf-8')
print('Hits',len(hits))
byid={r['id']:r for r in records}
def layer(sid):
 r=byid[sid];im=Image.open(root/'hakkei'/r['file']).convert('RGBA')
 canvas=Image.new('RGBA',(800,800))
 x,y=r['texture_rect_offset']
 canvas.alpha_composite(im,(round(x),round(800-y-im.height)))
 return canvas
comparison=Image.new('RGBA',(900,500),'#dedbd1')
for i,accessory in enumerate((8105,6702)):
 canvas=Image.new('RGBA',(800,800))
 for sid in (accessory,7243,5802,6203,5680 if i==0 else 6585):canvas.alpha_composite(layer(sid))
 comparison.alpha_composite(canvas.crop((180,200,630,700)),(i*450,0))
comparison.convert('RGB').save(root/'hakkei-layer-reconstruction.png')
mapping=json.loads(Path('variants/marisa-rump/reference/graphic-map.json').read_text(encoding='utf-8'))
(root/'graphic-fields.txt').write_text('\n'.join(f'{k}: {v[:7]}' for k,v in mapping.items() if ('基本' in k or '目' in k) and '１' not in k and '２' not in k),encoding='utf-8')
