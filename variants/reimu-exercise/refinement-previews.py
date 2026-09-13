from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import subprocess
r=Path(__file__).resolve().parent
font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',20)
def render(folder,clip,t,az=-.55,el=.12):
    raw=subprocess.check_output(['node',str(r/'render-refinement.cjs'),folder,clip,str(t),str(az),str(el)])
    im=Image.frombytes('RGBA',(480,480),raw)
    bg=Image.new('RGB',im.size,'#eae7de');bg.paste(im,(0,0),im);return bg
sheet=Image.new('RGB',(1440,1050),'#eae7de');d=ImageDraw.Draw(sheet)
for col,(clip,t,az,label) in enumerate([('taiso',6.2,-.55,'拉长底部'),('press',1.2,-.55,'弯月笑眼'),('press',1.2,2.65,'后臀')]):
    for row,folder in enumerate(['before-fixes','.']):
        sheet.paste(render(folder,clip,t,az),(col*480,row*525+40))
        d.text((col*480+15,row*525+10),('修改前 · ' if row==0 else '修改后 · ')+label,font=font,fill='#393540')
sheet.save(r/'refinement-comparison.png')
rear=Image.new('RGB',(1440,525),'#eae7de');d=ImageDraw.Draw(rear)
for col,(clip,t,az,label) in enumerate([('press',0,3.14159,'后臀 · 静止'),('press',1.2,2.65,'后臀 · 向一侧用力'),('press',3.6,3.7,'后臀 · 换侧用力')]):
    rear.paste(render('.',clip,t,az),(col*480,40));d.text((col*480+15,10),label,font=font,fill='#393540')
rear.save(r/'rump-sheet.png')
print('Saved comparison and rear views')
