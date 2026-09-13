"""Local reference only; preserve the shared Sprite canvas, never package sprites."""
from pathlib import Path
import json
from PIL import Image, ImageDraw, ImageFont

ROOT = Path(__file__).resolve().parent
records = json.loads((ROOT/'sprite-index.json').read_text(encoding='utf-8'))
lookup = {x['id']: x for x in records}
mapping = json.loads((ROOT.parents[1]/'marisa-rump/reference/graphic-map.json').read_text(encoding='utf-8'))
for row in records:
    row['bindings'] = [{'field': k, 'index': i, 'file_id': p['file']} for k, v in mapping.items()
                       if isinstance(v, list) for i, p in enumerate(v)
                       if isinstance(p, dict) and p.get('id') == row['id']]
(ROOT/'source-index.json').write_text(json.dumps(records, ensure_ascii=False, indent=2), encoding='utf-8')
canvas = Image.new('RGBA', (800,800))
# Neutral standing pose: same pixel coordinates for all layers. Parent transforms
# and Unity sorting have not been reproduced; this is a proportion study.
order = [7017,7243,6280,7629,5051,4598]
for sid in order:
    row=lookup[sid]; im=Image.open(ROOT/row['file']).convert('RGBA')
    x,y=row['texture_rect_offset']
    canvas.alpha_composite(im,(round(x),800-round(y)-im.height))
canvas.save(ROOT/'composite-full.png')
font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',20)
board=Image.new('RGB',(1200,560),'#eae7de'); d=ImageDraw.Draw(board)
crop=canvas.crop((220,320,580,690)); crop=crop.resize((432,444))
board.paste(crop,(20,65),crop)
d.text((22,18),'八景 U · 灵梦原始图层重组（非游戏截图）',font=font,fill='#393540')
for i,sid in enumerate([7629,7017,5051,4598]):
    im=Image.open(ROOT/lookup[sid]['file']).convert('RGBA'); im.thumbnail((300,195))
    x=490+(i%2)*350; y=65+(i//2)*245
    board.paste(im,(x,y),im); d.text((x,y+198),f'Sprite {sid}',font=font,fill='#393540')
board.save(ROOT/'reference-sheet.png')
print('Composed reference with original offsets; recorded field/index mappings.')
