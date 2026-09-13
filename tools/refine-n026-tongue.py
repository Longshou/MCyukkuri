from pathlib import Path
from PIL import Image
import json,hashlib
R=Path(__file__).resolve().parent.parent/'standards/yukkuri-library'
p=R/'expressions/presets/dead_tongue.json';data=json.loads(p.read_text(encoding='utf-8-sig'))
rows=['...xxxxxxxx...','..xrrrrrrrrx..','.xrrrrrrrrrrx.','xrrrrrrrrrrrrx','xrrttttttttrrx','.xrttttttttrx.','..xttttttttx..','...xttttttx...','...xttttttx...','....xttttx....','....xttttx....','....xttttx....','....xttttx....','.....xttx.....','.....xttx.....','......xx......']
colors={'.':(0,0,0,0),'x':(72,53,46,255),'r':(151,72,65,255),'t':(208,124,113,255)};im=Image.new('RGBA',(28,24))
for y,row in enumerate(rows):
 for x,c in enumerate(row):im.putpixel((x+7,y+5),colors[c])
h=hashlib.sha256(im.tobytes()).hexdigest();ref=f'expressions/parts/{h[:20]}.png';im.save(R/ref);data['parts']['face_mouth']=ref;p.write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
p=R/'expressions/parts/index.json';idx=json.loads(p.read_text(encoding='utf-8-sig'));idx[h[:20]]=dict(path=ref,size=[28,24],sha256=h);p.write_text(json.dumps(idx,ensure_ascii=False,indent=2),encoding='utf-8')
