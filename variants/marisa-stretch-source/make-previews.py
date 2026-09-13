from pathlib import Path
from PIL import Image,ImageDraw,ImageFont,ImageFilter
r=Path(__file__).resolve().parent;font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',22);frames=[]
for i in range(56):
 b=Image.new('RGB',(840,615),'#eae7de');d=ImageDraw.Draw(b);d.text((24,13),'伸长 · 正面',font=font,fill='#393540');d.text((444,13),'身体变高 · 五官保持比例',font=font,fill='#393540')
 for col,name in enumerate(['front','quarter']):
  sh=Image.new('RGBA',(420,540));sd=ImageDraw.Draw(sh);sd.ellipse((110,450,310,480),fill=(85,68,42,35));sh=sh.filter(ImageFilter.GaussianBlur(10));b.paste(sh,(col*420,40),sh)
  im=Image.frombytes('RGBA',(420,540),(r/f'stretch-{name}-{i}.rgba').read_bytes());b.paste(im,(col*420,40),im)
 t=i*.05;stage='准备' if t<.3 else '伸长' if t<1 else '保持' if t<1.65 else '缩回' if t<2.4 else '恢复'
 d.text((24,578),f'{stage} · 原作约 1.35 倍高度 · 连续馒头体型',font=font,fill='#756959');frames.append(b)
frames[0].save(r/'stretch.gif',save_all=True,append_images=frames[1:],duration=50,loop=0,disposal=2)
frames[26].save(r/'stretch-maximum.png')
b=Image.new('RGB',(1260,610),'#eae7de');d=ImageDraw.Draw(b)
for col,(i,label) in enumerate([(0,'正常'),(13,'伸长途中'),(26,'伸长保持')]):
 im=Image.open(r/'stretch-maximum.png') if False else frames[i]
 b.paste(im.crop((420,40,840,580)),(col*420,55));d.text((col*420+30,18),label,font=font,fill='#393540')
b.save(r/'stretch-sheet.png')
