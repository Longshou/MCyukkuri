from pathlib import Path
from PIL import Image,ImageDraw,ImageFont
import subprocess
r=Path(__file__).resolve().parent
font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',18)
sheet=Image.new('RGB',(1920,1050),'#eae7de');d=ImageDraw.Draw(sheet)
for col,(clip,t,az,label) in enumerate([('exercise_sides',.6,-.55,'左侧弯'),('exercise_sides',1.8,.55,'右侧弯'),('exercise_sides',.6,1.3,'侧面衔接'),('press',1.2,-.55,'笑眼恢复')]):
    for row,folder in enumerate(['before-face-integration','.']):
        raw=subprocess.check_output(['node',str(r/'render-refinement.cjs'),folder,clip,str(t),str(az),'.12'])
        im=Image.frombytes('RGBA',(480,480),raw)
        sheet.paste(im,(col*480,row*525+40),im)
        d.text((col*480+15,row*525+10),('修改前 · ' if row==0 else '修改后 · ')+label,font=font,fill='#393540')
sheet.save(r/'face-integration-comparison.png')
print('Saved face integration comparison')
