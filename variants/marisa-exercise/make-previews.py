from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import json
r=Path(__file__).resolve().parent;f=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',22);meta=json.loads((r/'preview-timing.json').read_text());allframes={}
for clip,label in [('exercise_vertical','上下体操'),('exercise_sides','左右体操')]:
 frames=[]
 for i in range(meta[clip]['count']):
  b=Image.new('RGB',(520,635),'#eae7de');d=ImageDraw.Draw(b);d.text((24,16),label+' · 原作速率适配',font=f,fill='#393540');im=Image.frombytes('RGBA',(520,580),(r/f'{clip}-{i}.rgba').read_bytes());b.paste(im,(0,45),im);frames.append(b)
 frames[0].save(r/(clip+'.gif'),save_all=True,append_images=frames[1:],duration=20,loop=0,disposal=2);allframes[clip]=frames
frames=[];v=allframes['exercise_vertical'];s=allframes['exercise_sides']
for i in range(len(v)):
 b=Image.new('RGB',(840,513),'#eae7de');b.paste(v[i].resize((420,513),Image.Resampling.LANCZOS),(0,0));j=i%len(s) if i<len(s)*2 else 0;b.paste(s[j].resize((420,513),Image.Resampling.LANCZOS),(420,0));frames.append(b)
frames[0].save(r/'exercise-comparison.gif',save_all=True,append_images=frames[1:],duration=20,loop=0,disposal=2)
