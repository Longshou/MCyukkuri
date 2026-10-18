"""Read only selected ordinary adult layers from the user-provided 1.12 package.

This creates a reference preview, not production art or an in-game screenshot.
The cached HSP is not fingerprint-bound to this package. Layer indices therefore
remain candidate assembly locations; this is not evidence of a state call.
"""
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont
ROOT = Path(__file__).resolve().parents[4]
PICT = ROOT/'油库里八景ver1.12/油库里八景ver1.12/pict'
OUT = ROOT/'.local/previews/rapem-alice/reference-normal.png'
FONT = Path('C:/Windows/Fonts/msyh.ttc')
# No atlas browsing or arbitrary state indices: only these ordinary adult slices.
LAYERS = [
    ('base/sei/3.png',0,0),
    ('arisu/sei_new/4.png',2,0),
    ('arisu/sei_new/10.png',2,0),
    ('arisu/sei_new/6.png',0,2),
    ('base/sei/8.png',0,2),
    ('base/sei/9.png',0,2),
    ('arisu/sei_new/7.png',0,2),
]
def tile(name,index):
    with Image.open(PICT/name) as im:
        if im.mode == 'RGB':
            # HSP gmode 7: RGB cell followed by its grayscale alpha cell.
            y=(index//2)*240
            part=im.crop((0,y,240,y+240)).convert('RGBA')
            part.putalpha(im.crop((240,y,480,y+240)).convert('L'))
            return part
        return im.crop((0,index*240,240,(index+1)*240)).convert('RGBA')
head=Image.new('RGBA',(240,240),(244,242,236,255))
for name,index,y in LAYERS:
    head.alpha_composite(tile(name,index),(0,y))
out=Image.new('RGBA',(1000,640),(244,242,236,255))
d=ImageDraw.Draw(out)
def font(size): return ImageFont.truetype(str(FONT),size)
d.text((25,18),'八景 ver1.12 · 成体 Alice 原资源核对',font=font(28),fill='#24272c')
d.text((25,60),'仅普通成体素材；这张分层拼合不是运行截图，也不是专属状态证据。',font=font(18),fill='#55585d')
out.alpha_composite(head.crop((70,115,185,240)).resize((460,500)),(18,105))
for name,at,label,box in [
    ('arisu/sei_new/6.png',(560,140),'6.png 第 0 格：蓝虹膜与白色高光',(90,165,165,200)),
    ('arisu/sei_new/7.png',(560,320),'7.png 第 0 格：普通细弧眉',(90,158,165,190)),
]:
    part=tile(name,0).crop(box)
    part=part.resize((360,round(part.height*360/part.width)))
    out.alpha_composite(part,at)
    d.text((540,at[1]-35),label,font=font(18),fill='#24272c')
d.text((525,510),'来源：pict/arisu/sei_new 与 base/sei',font=font(17),fill='#55585d')
d.text((525,540),'头发/发带第 2 格，其余第 0 格',font=font(17),fill='#55585d')
d.text((525,570),'缓存 HSP 未与本包绑定：仅候选分层定位',font=font(17),fill='#9b5433')
OUT.parent.mkdir(parents=True,exist_ok=True)
out.save(OUT)
print(OUT)
