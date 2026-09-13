from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
R=Path(__file__).resolve().parent
font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',18)
small=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',14)
def read(name,size):
    im=Image.frombytes('RGBA',size,(R/'frames'/name).read_bytes())
    bg=Image.new('RGB',size,'#eae7de');bg.paste(im,mask=im.getchannel('A'));return bg
times=[0,1.4,2.8,3.6,4.25,6.4]
labels=['准备','压低身体、用力','逐渐排出','脱离','落地、放松','恢复']
sheet=Image.new('RGB',(6*440,3*490),'#eae7de');draw=ImageDraw.Draw(sheet)
for row,angle in enumerate([0,65,135]):
    for col,(t,label) in enumerate(zip(times,labels)):
        sheet.paste(read(f'stage-{t}-{angle}.rgba',(440,460)),(col*440,row*490))
        draw.text((col*440+16,row*490+462),f'{t:.2f}s · {label}',font=font,fill='#53493e')
sheet.save(R/'stages.png')
frames=[]
for i in range(160):
    frame=Image.new('RGB',(760,444),'#eae7de');draw=ImageDraw.Draw(frame)
    for j,angle in enumerate([30,110]):frame.paste(read(f'{angle}-{i:03}.rgba',(380,400)),(380*j,30))
    draw.text((18,10),'魔理沙 · 排嗯嗯',font=font,fill='#53493e')
    t=i*.04;label='准备' if t<.5 else '蓄力' if t<1.55 else '用力排出' if t<3.55 else '脱离、落地' if t<4.13 else '放松恢复'
    draw.text((580,12),f'{t:.2f}s · {label}',font=small,fill='#53493e')
    draw.text((100,420),'正面表情',font=small,fill='#71695e');draw.text((490,420),'侧后方动作',font=small,fill='#71695e')
    frames.append(frame)
frames[0].save(R/'poop.gif',save_all=True,append_images=frames[1:],duration=40,loop=0,disposal=2)
frames[108].save(R/'preview.png')
detail=Image.new('RGB',(1380,420),'#eae7de');dd=ImageDraw.Draw(detail)
for i,(name,label) in enumerate([('detail-mouth.rgba','张嘴用力'),('detail-mark.rgba','后臀 X 标记'),('filling-90.rgba','深紫褐色馅料团')]):
    detail.paste(read(name,(460,380)),(i*460,30));dd.text((i*460+20,8),label,font=font,fill='#53493e')
detail.save(R/'details.png')
filling=Image.new('RGB',(1380,420),'#eae7de');fd=ImageDraw.Draw(filling)
for i,angle in enumerate([25,90,150]):
    filling.paste(read(f'filling-{angle}.rgba',(460,380)),(460*i,30));fd.text((460*i+20,8),['馅料团 · 前端','馅料团 · 侧面','馅料团 · 后端'][i],font=font,fill='#53493e')
filling.save(R/'filling-preview.png')
print('Saved GIF, stages and preview.')
