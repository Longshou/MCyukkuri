from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
r=Path(__file__).resolve().parent;frames=[];font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',20)
for i,p in enumerate(sorted((r/'frames').glob('*.rgba'))):
 im=Image.frombytes('RGBA',(520,540),p.read_bytes());bg=Image.new('RGB',im.size,'#eae7de');bg.paste(im,mask=im.getchannel('A'));d=ImageDraw.Draw(bg);t=i*.04;label='吸气准备' if t<.9 else '两腮鼓起' if t<1.7 else '噗咕——憋住！' if t<3.9 else '泄气回弹' if t<5.1 else '恢复 / 眨眼';d.text((20,16),label,font=font,fill='#51463e');frames.append(bg)
frames[0].save(r/'puff.gif',save_all=True,append_images=frames[1:],duration=40,loop=0,optimize=False)
b=Image.new('RGB',(1560,540),'#eae7de')
for x,i in enumerate([10,65,112]):b.paste(frames[i],(x*520,0))
b.save(r/'puff-stages.png')
b=Image.new('RGB',(1560,450),'#eae7de');d=ImageDraw.Draw(b)
for x,i in enumerate([92,99,105,110,114,125]):
 xx=x%3*520;yy=x//3*225;b.paste(frames[i].crop((0,330,520,540)),(xx,yy+15));d.text((xx+15,yy),f'{i*.04:.2f} s',font=font,fill='#51463e')
b.save(r/'deflation-stages.png')
