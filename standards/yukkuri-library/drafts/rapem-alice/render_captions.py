"""Caption software-rendered adult frames; does not read runtime UI."""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
ROOT=Path(__file__).resolve().parents[4]
OUT=ROOT/'.local/previews/rapem-alice'
FONT=Path('C:/Windows/Fonts/msyh.ttc')
font=ImageFont.truetype(str(FONT),23)
small=ImageFont.truetype(str(FONT),16)
large=ImageFont.truetype(str(FONT),28)
BG=(243,247,242)
phase={
 'heat':[(0,'期内 · 中性观察'),(1.6,'期内 · 看一侧'),(3.2,'期内 · 回正'),(5.2,'期内 · 看另一侧'),(6.8,'期内 · 继续观察')],
 'relief':[(0,'第一拍 · 慢！'),(2.3,'第二拍 · 素——！'),(4.55,'第三拍 · 素鸡立～！'),(5.7,'结束 · 回到待机')],
 'rapem':[(0,'普通外形适配'),(.8,'警觉示意'),(1.5,'项目失控姿态'),(5.4,'中断示意'),(6.8,'恢复示意'),(9.2,'普通外形适配')]
}
titles={'heat':'素鸡立｜持续繁殖期 · 中性观察','relief':'素鸡立｜受孕后完成 · 三段节拍','rapem':'Alice｜项目状态示意 · 原作专属形态未完成'}
notes={'heat':'1.12普通成体外形适配；8秒循环，不演示期结束。','relief':'期内受孕或离屏结算成功后的一次性项目改编演出；三拍对应heat_relief_1/2/3原句。','rapem':'1.12蓝眼配色适配；眉口与姿态是项目改编。'}
ids={'heat':'sukkiri','relief':'relief','rapem':'alice-agitated'}
def label_at(branch,t):return [label for start,label in phase[branch] if t>=start][-1]
def source(branch,frame):return Image.open(OUT/'frames'/f'{branch}-{frame:03d}.png').convert('RGB')
def caption(branch,frame,last):
 im=Image.new('RGB',(600,544),BG);im.paste(source(branch,frame),(0,61));d=ImageDraw.Draw(im)
 d.text((18,9),titles[branch],font=font if branch!='rapem' else small,fill=(35,53,43))
 d.text((18,40),notes[branch],font=small,fill=(80,103,88))
 d.text((22,501),label_at(branch,frame/10),font=small,fill=(35,53,43));d.text((505,501),f'{frame/10:04.1f} s',font=small,fill=(80,103,88))
 d.rectangle((22,531,578,534),fill=(200,213,201));d.rectangle((22,531,22+556*frame/last,534),fill=(79,121,92))
 return im
for branch in ['heat','relief','rapem']:
 last=80 if branch=='heat' else 60 if branch=='relief' else 100
 frames=[caption(branch,i,last) for i in range(last+1)]
 palette=Image.new('RGB',(600,544*3))
 for i,j in enumerate([0,16,52] if branch=='heat' else [0,10,46] if branch=='relief' else [0,34,76]):palette.paste(frames[j],(0,544*i))
 palette=palette.quantize(colors=192)
 encoded=[im.quantize(palette=palette,dither=Image.Dither.NONE) for im in (frames[:-1] if branch=='heat' else frames)]
 duration=100 if branch=='heat' else [100]*last+[1200]
 encoded[0].save(OUT/f'{ids[branch]}.gif',save_all=True,append_images=encoded[1:],duration=duration,loop=0,optimize=True,disposal=2)
 close=Image.new('RGB',(1440,490),BG);d=ImageDraw.Draw(close)
 d.text((24,12),titles[branch],font=large,fill=(35,53,43));d.text((24,48),notes[branch],font=small,fill=(80,103,88))
 chosen=[(0,'期内：正视'),(16,'期内：看一侧'),(52,'期内：看另一侧')] if branch=='heat' else [(0,'第一拍：慢！'),(23,'第二拍：素——！'),(46,'第三拍：素鸡立～！')] if branch=='relief' else [(0,'普通配色适配'),(34,'项目姿态；非原作复刻'),(76,'恢复示意')]
 for index,(frame,title) in enumerate(chosen):
  close.paste(source(branch,frame).resize((480,352),Image.Resampling.NEAREST),(index*480,90))
  d.text((index*480+24,80),title,font=font,fill=(35,53,43))
  d.text((index*480+24,448),f'{frame/10:.1f} 秒 · '+label_at(branch,frame/10),font=small,fill=(80,103,88))
 close.convert('RGBA').save(OUT/f'{ids[branch]}-closeup.png')
 times=[0,16,32,52,68,80] if branch=='heat' else [0,10,23,35,46,58] if branch=='relief' else [0,12,34,60,76,100]
 board=Image.new('RGB',(1200,814),BG);d=ImageDraw.Draw(board)
 d.text((20,12),titles[branch]+' · 采样分镜',font=large,fill=(35,53,43))
 for i,frame in enumerate(times):
  x=(i%3)*400;y=58+(i//3)*365
  board.paste(source(branch,frame).resize((400,294),Image.Resampling.NEAREST),(x,y+30))
  d.text((x+16,y),f'{frame/10:.1f} 秒 · '+label_at(branch,frame/10),font=small,fill=(35,53,43))
 d.text((20,788),notes[branch]+' 固定镜位与地面；不是游戏录屏。',font=small,fill=(80,103,88))
 board.save(OUT/f'{ids[branch]}-storyboard.png')
 print('wrote',ids[branch],'PNG / GIF / storyboard')
