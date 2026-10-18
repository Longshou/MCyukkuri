"""Caption software-rendered frames and encode GIFs; never reads runtime UI."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
ROOT=Path(__file__).resolve().parents[4]
OUT=ROOT/'.local/previews/rapem-alice'
FONT=Path('C:/Windows/Fonts/msyh.ttc')
font=ImageFont.truetype(str(FONT),24)
small=ImageFont.truetype(str(FONT),17)
large=ImageFont.truetype(str(FONT),30)
BG=(243,247,242)
phase={
 'heat':[(0,'普通'),(1.2,'紧张 · 压低重心'),(3.5,'缓解 · 一次抬身与慢慢卸力'),(6.8,'疲惫恢复'),(9.2,'回到普通')],
 'rapem':[(0,'普通'),(.8,'警觉'),(1.5,'失控 · 前倾、低重心与凝视'),(5.4,'中断 · 收势'),(6.8,'疲惫恢复'),(9.2,'回到普通')]
}
titles={'heat':'素鸡立｜短时缓解表现','rapem':'爱丽丝｜失控状态形象'}
ids={'heat':'sukkiri','rapem':'alice-agitated'}
def label_at(branch,t):
 return [label for start,label in phase[branch] if t>=start][-1]
def source(branch,frame):
 return Image.open(OUT/'frames'/f'{branch}-{frame:03d}.png').convert('RGB')
def caption(branch,frame):
 im=Image.new('RGB',(600,544),BG);im.paste(source(branch,frame),(0,61));d=ImageDraw.Draw(im)
 d.text((22,12),titles[branch],font=font,fill=(35,53,43));d.text((22,43),'八景U成体外形参考 · 项目状态改编 · 非游戏录屏',font=small,fill=(80,103,88))
 d.text((22,501),label_at(branch,frame/10),font=small,fill=(35,53,43));d.text((505,501),f'{frame/10:04.1f} s',font=small,fill=(80,103,88))
 d.rectangle((22,531,578,534),fill=(200,213,201));d.rectangle((22,531,22+556*frame/100,534),fill=(79,121,92))
 return im
for branch in ['heat','rapem']:
 frames=[caption(branch,i) for i in range(101)]
 palette=Image.new('RGB',(600,544*3))
 for i,j in enumerate([0,35,76]):palette.paste(frames[j],(0,544*i))
 palette=palette.quantize(colors=192)
 encoded=[im.quantize(palette=palette,dither=Image.Dither.NONE) for im in frames]
 encoded[0].save(OUT/f'{ids[branch]}.gif',save_all=True,append_images=encoded[1:],duration=[100]*100+[1200],loop=0,optimize=True,disposal=2)
 # Three close views: ordinary, actual state silhouette, then recovery.
 close=Image.new('RGB',(1440,470),BG);d=ImageDraw.Draw(close)
 d.text((24,16),titles[branch]+' · 当前制作预览',font=large,fill=(35,53,43))
 main=35 if branch=='heat' else 34
 for index,(frame,title) in enumerate([(0,'普通'),(main,'状态姿态'),(76,'恢复')]):
  close.paste(source(branch,frame).resize((480,352),Image.Resampling.NEAREST),(index*480,75))
  d.text((index*480+24,64),title,font=font,fill=(35,53,43))
  d.text((index*480+24,423),f'{frame/10:.1f} 秒 · '+label_at(branch,frame/10),font=small,fill=(80,103,88))
 close.convert('RGBA').save(OUT/f'{ids[branch]}-closeup.png')
 # Fixed-camera six-frame evidence including the end state; no contact scene.
 times=[0,27,35,48,76,100] if branch=='heat' else [0,12,34,60,76,100]
 board=Image.new('RGB',(1200,814),BG);d=ImageDraw.Draw(board)
 d.text((20,12),titles[branch]+' · 六阶段动作分镜',font=large,fill=(35,53,43))
 for i,frame in enumerate(times):
  x=(i%3)*400;y=58+(i//3)*365
  board.paste(source(branch,frame).resize((400,294),Image.Resampling.NEAREST),(x,y+30))
  d.text((x+16,y),f'{frame/10:.1f} 秒 · '+label_at(branch,frame/10),font=small,fill=(35,53,43))
 d.text((20,788),'固定镜位与地面；10秒分段为演示编排。GIF在结束停顿后重播。',font=small,fill=(80,103,88))
 board.save(OUT/f'{ids[branch]}-storyboard.png')
 print('wrote',ids[branch],'PNG / GIF / storyboard')
