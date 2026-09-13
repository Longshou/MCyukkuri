from pathlib import Path
from PIL import Image,ImageDraw,ImageFont,ImageFilter
r=Path('variants/marisa-waddle'); frames=[]
font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',21)
for i in range(32):
 board=Image.new('RGB',(880,510),'#eae7de');d=ImageDraw.Draw(board)
 d.text((28,14),'摇摆前进 · 正面',font=font,fill='#393540');d.text((468,14),'后半部左右摆动 · 背面',font=font,fill='#393540')
 for col,name in enumerate(['front','rear']):
  shadow=Image.new('RGBA',(440,440));sd=ImageDraw.Draw(shadow);sd.ellipse((120,358,320,382),fill=(85,68,42,35));shadow=shadow.filter(ImageFilter.GaussianBlur(9));board.paste(shadow,(col*440,45),shadow)
  im=Image.frombytes('RGBA',(440,440),(r/f'motion-{name}-{i}.rgba').read_bytes());board.paste(im,(col*440,45),im)
 d.text((28,476),'原地步态循环 · 1.2 秒 · 实际骨骼与贴图渲染',font=font,fill='#756959')
 frames.append(board)
frames[0].save(r/'waddle.gif',save_all=True,append_images=frames[1:],duration=[30 if i%4==0 else 40 for i in range(32)],loop=0,disposal=2)
board=Image.new('RGB',(1760,1020))
for i,f in enumerate([0,8,16,24]):board.paste(frames[f],((i%2)*880,(i//2)*510))
board.save(r/'motion-sheet.png');frames[8].save(r/'preview-motion.png');frames[8].save(r/'comparison.png')
