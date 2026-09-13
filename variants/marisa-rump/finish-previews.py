from pathlib import Path
from PIL import Image,ImageDraw,ImageFont,ImageFilter
import json,zipfile,hashlib,base64
r=Path('variants/marisa-rump');old=r.parent/'marisa-waddle';font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',22);frames=[]
for i in range(32):
 b=Image.new('RGB',(880,510),'#eae7de');d=ImageDraw.Draw(b)
 d.text((26,16),'上一版 · 整团身体一起摇',font=font,fill='#393540');d.text((466,16),'本版 · 后半部独立甩动',font=font,fill='#393540')
 for col,src in enumerate([old,r]):
  shadow=Image.new('RGBA',(440,440));sd=ImageDraw.Draw(shadow);sd.ellipse((120,358,320,382),fill=(85,68,42,35));shadow=shadow.filter(ImageFilter.GaussianBlur(9));b.paste(shadow,(col*440,45),shadow)
  im=Image.frombytes('RGBA',(440,440),(src/f'motion-rear-{i}.rgba').read_bytes());b.paste(im,(col*440,45),im)
 d.text((26,476),'同视角、同尺度 · 帽子和脸稳住，屁股甩出去再收回',font=font,fill='#756959');frames.append(b)
frames[0].save(r/'before-after.gif',save_all=True,append_images=frames[1:],duration=[30 if i%4==0 else 40 for i in range(32)],loop=0,disposal=2)
frames[8].save(r/'comparison.png')
ref=r/'reference';board=Image.new('RGB',(1200,610),'#eae7de');d=ImageDraw.Draw(board)
for i in range(4):
 p=ref/f'正面ケツ振りボディグラ{i+1}ベース0.png';im=Image.open(p).convert('RGBA');im.thumbnail((280,230));board.paste(im,(i*300+(300-im.width)//2,260-im.height),im);d.text((i*300+20,275),f'正面甩臀 · 身体帧 {i+1}',font=font,fill='#393540')
for i,name in enumerate(['ケツ上げ３左２グラベース0.png','ケツ上げ３グラベース0.png','ケツ上げ３右２グラベース0.png']):
 im=Image.open(ref/name).convert('RGBA');im.thumbnail((270,230));board.paste(im,(i*400+(400-im.width)//2,560-im.height),im)
d.text((24,577),'八景 U 原始分层参考 · 各帧仅作轮廓核读，非本模型素材',font=font,fill='#393540');board.save(ref/'evidence-sheet.png')
bb=json.loads((r/'marisa.bbmodel').read_text());assert base64.b64decode(bb['textures'][0]['source'].split(',')[1])==(r/'marisa.png').read_bytes()
assert (r/'marisa.png').read_bytes()==(r/'resourcepack/assets/yukkuri/textures/entity/marisa.png').read_bytes()
with zipfile.ZipFile(r/'marisa-java-1.21.1-resourcepack.zip') as z:
 assert z.testzip() is None
 for p in (r/'resourcepack').rglob('*'):
  if p.is_file():assert z.read(p.relative_to(r/'resourcepack').as_posix())==p.read_bytes()
report={'status':'passed','checks':['embedded and external texture bytes match','resource pack texture matches','ZIP CRC and complete payload bytes match'],'sha256':{n:hashlib.sha256((r/n).read_bytes()).hexdigest() for n in ['marisa.bbmodel','marisa.geo.json','marisa.animation.json','marisa.png','marisa-java-1.21.1-resourcepack.zip']}}
(r/'asset-verification.json').write_text(json.dumps(report,indent=2),encoding='utf-8');print('PASS resource assets and ZIP consistency')
