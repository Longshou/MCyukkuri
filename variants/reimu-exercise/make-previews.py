"""50 fps GIFs and pose sheet from the same geometry/rig evaluator as the viewer."""
from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import json, subprocess

r=Path(__file__).resolve().parent;rig=json.loads((r/'animation-rig.json').read_text(encoding='utf-8'))
font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',17)
small=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',13)
width,height=480,420;contact={}
poses={0:'准备',60:'转向 / 抬臀用力',180:'换侧用力',310:'伸高',430:'向左侧弯',490:'向右侧弯',670:'回正'}
for clip in ('taiso','exercise_vertical','exercise_sides'):
    p=subprocess.Popen(['node',str(r/'render-frames.cjs'),clip,str(width),str(height)],stdout=subprocess.PIPE)
    frames=[];count=round(rig['clips'][clip]['duration']*50)
    for i in range(count):
        raw=p.stdout.read(width*height*4)
        if len(raw)!=width*height*4:raise RuntimeError(f'Incomplete rendered frame {clip}/{i}: {len(raw)}')
        frame=Image.new('RGB',(width,height+60),'#eae7de');im=Image.frombytes('RGBA',(width,height),raw)
        frame.paste(im,(0,30),im);d=ImageDraw.Draw(frame)
        d.text((15,8),{'taiso':'灵梦 · 嗯嗯体操','exercise_vertical':'灵梦 · 上下伸缩','exercise_sides':'灵梦 · 左右侧弯（半速）'}[clip],font=font,fill='#393540')
        t=i*.02
        if clip=='taiso':
            label='转向 / 抬臀用力' if t<2.4 else '换侧 / 抬臀用力' if t<4.8 else '上下伸缩' if t<7.42 else '伸高准备' if t<8 else '左右侧弯' if t<12.72 else '回正 / 休息'
        else:label=f'{t:.2f} / {rig["clips"][clip]["duration"]:.2f} 秒'
        d.text((15,height+34),label,font=small,fill='#795454')
        if clip=='taiso' and i in poses:contact[i]=frame.copy()
        frames.append(frame.quantize(colors=128,method=Image.Quantize.MEDIANCUT,dither=Image.Dither.NONE))
    assert p.wait()==0
    frames[0].save(r/f'{clip}.gif',save_all=True,append_images=frames[1:],duration=20,loop=0,disposal=2,optimize=False)
    print(f'Saved {clip}: {count} frames, 50 fps',flush=True)
board=Image.new('RGB',(width*4,(height+95)*2),'#eae7de');d=ImageDraw.Draw(board)
for j,(i,frame) in enumerate(contact.items()):
    x=(j%4)*width;y=(j//4)*(height+95);board.paste(frame,(x,y));d.text((x+15,y+height+66),poses[i],font=font,fill='#393540')
d.text((width*3+18,height+155),'灵梦 · 嗯嗯体操',font=font,fill='#393540')
d.text((width*3+18,height+195),'同一视角、尺度与地面',font=small,fill='#635c59')
d.text((width*3+18,height+220),'五段动画 / 可编辑骨骼',font=small,fill='#635c59')
board.save(r/'pose-sheet.png')
