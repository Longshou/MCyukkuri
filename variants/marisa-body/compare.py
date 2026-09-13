from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
import generate as g
import json
r=Path(__file__).resolve().parent
baseline=r.parent/'marisa-gentle'
new_cubes=g.cubes; new_atlas=g.atlas
bb=json.loads((baseline/'marisa.bbmodel').read_text(encoding='utf-8'))
old_cubes=[dict(name=e['name'],lo=e['from'],hi=e['to'],faces={k:v['uv'] for k,v in e['faces'].items()}) for e in bb['elements']]
board=Image.new('RGB',(1200,1160),'#eae7de');d=ImageDraw.Draw(board)
font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',25)
for col,(cs,tex,title) in enumerate([(old_cubes,Image.open(baseline/'marisa.png'),'修改前 · 54 方块 · 底部叠层'),(new_cubes,new_atlas,'修改后 · 53 方块 · 单一底面')]):
    g.cubes=cs;g.atlas=tex
    d.text((col*600+50,24),title,font=font,fill='#393540')
    for row,(a,e) in enumerate([(0,0),(-32,14)]):
        board.paste(g.render(a,e,550),(col*600+25,65+row*545))
board.save(r/'comparison.png')
g.cubes=new_cubes;g.atlas=new_atlas
(r/'texture.rgba').write_bytes(new_atlas.convert('RGBA').tobytes())
