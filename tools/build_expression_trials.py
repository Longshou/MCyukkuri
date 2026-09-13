"""Four controlled expression/hair alternatives, preserving accepted variants."""
from pathlib import Path
import shutil, subprocess, sys, json
from PIL import Image, ImageDraw, ImageFont
ROOT=Path(__file__).resolve().parents[1]
BASE=ROOT/'variants/marisa-hakkei'
OUT=ROOT/'variants/expression-trials'
OUT.mkdir(exist_ok=True)
original=(BASE/'generate.py').read_text(encoding='utf-8')
soft='''# B/C/D: one small upper highlight, open outer sclera, no glossy lower dots.
face=Image.new('RGB',(96,64),'#ffe7b9');f=ImageDraw.Draw(face)
f.rectangle((0,54,95,63),fill='#f5d8a8');f.rectangle((4,54,91,57),fill='#ffe2b2')
for cx,side in [(26,-1),(70,1)]:
    def pts(coords):return [(cx+side*x,y) for x,y in coords]
    # Outer lower corner only; never draw a complete black/white ring.
    f.polygon(pts([(6,24),(10,28),(11,34),(8,39),(4,40),(6,36)]),fill='#fff1cf')
    f.line(pts([(9,28),(10,34),(8,38)]),fill='#806747',width=1)
    f.ellipse((cx-8,23,cx+8,40),fill='#34291f')
    f.polygon(pts([(-5,36),(-3,39),(3,39),(6,36),(4,40),(-3,40)]),fill='#705238')
    # 18 pixels of ivory instead of a broad white bar and lower glints.
    f.polygon(pts([(-3,25),(0,24),(3,25),(3,27),(0,28),(-3,27)]),fill='#fff8e6')
    f.line(pts([(-9,27),(-6,23),(-1,21),(4,22),(8,25)]),fill='#493525',width=2)
    f.line(pts([(-6,18),(-1,17),(5,18)]),fill='#a37e49',width=1)
for x in (10,78):
    f.ellipse((x,42,x+8,45),fill='#f0b99d')
# Soft U-shaped smile with a wide top; reduced red area.
f.polygon([(42,42),(53,42),(53,45),(51,48),(45,48),(42,45)],fill='#9f4e3b')
f.polygon([(44,43),(51,43),(51,45),(49,47),(46,47),(44,45)],fill='#d75b48')
atlas.paste(face,(0,0)); uv['face']=[0,0,96,64]
'''
variants=[('a','A · 只减高光','原眼形、原刘海；缩小主高光，去掉下方亮点。'),
          ('b','B · 放松眼神','单块小高光、局部眼白、柔和笑嘴；原刘海。'),
          ('c','C · 调整刘海','与 B 完全相同的表情；发束收尖、错落，减少发丝条纹。'),
          ('d','D · 收嘴微笑','沿用 C 的眼睛与刘海；仅将张嘴换成小小的闭口笑。')]
for key,title,description in variants:
    target=OUT/key;target.mkdir(exist_ok=True)
    for filename in ['software-viewer.js','viewer-template.html','verify.py','verify-viewer.cjs']:
        shutil.copy2(BASE/filename,target/filename)
    s=original
    if key=='a':
        s=s.replace("f.polygon(pts([(-7,25),(-4,23),(0,23),(6,26),(6,29),(2,30),(-4,28),(-7,27)]),fill='#fffef7')", "f.polygon(pts([(-3,25),(0,24),(3,25),(3,27),(0,28),(-3,27)]),fill='#fff8e6')")
        s=s.replace("    f.ellipse((cx-side*4-1,35,cx-side*4+1,37),fill='#fff8e4')",'    # Removed lower glint.')
    else:
        start=s.index('# Higher-density pixel expression:');end=s.index("atlas.save(ROOT/'marisa.png')",start)
        s=s[:start]+soft+s[end:]
    if key in ('c','d'):
        s=s.replace("for xx,yy in [(2,3),(9,8),(13,1)]", "for xx,yy in [(9,8)]")
        s=s.replace("[(-6,1.6,6.6),(-4.4,1.7,7.5),(-2.7,1.8,7),(-.9,2,6.3),(1.1,1.7,7.2),(2.8,1.6,7.6),(4.4,1.6,6.8)]", "[(-6,1.6,6.7),(-4.4,1.7,7.35),(-2.7,1.8,7.8),(-.9,2,6.3),(1.1,1.7,7.7),(2.8,1.6,7.3),(4.4,1.6,6.6)]")
        s=s.replace("[x,bottom,-5.05]", "[x,bottom+.65,-5.05]")
        s=s.replace("[x+.35,bottom-.45,-5.02],[x+w-.2,bottom,-4.62]", "[x+.45,bottom-.55,-5.02],[x+w-.4,bottom+.65,-4.62]")
    if key=='d':
        a=s.index('# Soft U-shaped smile');b=s.index('atlas.paste(face',a)
        s=s[:a]+"f.line([(42,43),(44,45),(46,45),(48,43),(50,45),(52,45),(54,43)],fill='#82503a',width=1)\n"+s[b:]
    s=s.replace('魔理沙 · 八景神态版',title)
    (target/'generate.py').write_text(s,encoding='utf-8')
    p=target/'viewer-template.html';p.write_text(p.read_text(encoding='utf-8').replace('魔理沙 · 八景神态版',title),encoding='utf-8')
    subprocess.run([sys.executable,str(target/'generate.py')],check=True,capture_output=True)
    (target/'README.md').write_text('# '+title+'\n\n'+description+'\n\n与其余候选分开生成，未覆盖保留版。preview.html 可旋转；marisa.bbmodel 可编辑。底部体型调整仍由独立任务负责，本组使用同一基准体型比较。\n',encoding='utf-8')

# Consistent, enlarged front views avoid judging differences in camera angle.
font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',24)
small=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',16)
board=Image.new('RGB',(1440,1220),'#eae7de');d=ImageDraw.Draw(board)
for i,(key,title,desc) in enumerate(variants):
    x=(i%2)*720;y=(i//2)*610
    sheet=Image.open(OUT/key/'preview-sheet.png')
    front=sheet.crop((5,90,495,580)).resize((550,550))
    board.paste(front,(x+85,y+45));d.text((x+28,y+15),title,font=font,fill='#393540')
    d.text((x+28,y+578),desc,font=small,fill='#675e53')
board.save(OUT/'comparison.png')
cards=''.join(f'<a href="{k}/preview.html"><img src="{k}/preview.png"><h2>{t}</h2><p>{desc}</p><span>打开旋转预览 →</span></a>' for k,t,desc in variants)
(OUT/'index.html').write_text('''<!doctype html><meta charset="utf-8"><title>魔理沙神态试验 A–D</title><style>body{background:#eae7de;color:#393540;font-family:"Microsoft YaHei",sans-serif;margin:30px auto;max-width:1200px;padding:0 20px}h1{font-size:26px}main{display:grid;grid-template-columns:1fr 1fr;gap:20px}a{color:inherit;text-decoration:none;background:#f5f1e9;border-radius:12px;padding:16px}img{width:100%;display:block}h2{font-size:21px}p{line-height:1.7}span{color:#846848}@media(max-width:650px){main{grid-template-columns:1fr}}</style><h1>魔理沙 · 高光与刘海对比</h1><p>A 对照高光；B 调整眼神；B/C 对照刘海；C/D 对照嘴形。点击每张图可旋转查看。</p><main>'''+cards+'</main>',encoding='utf-8')
print('Generated A/B/C/D and comparison gallery.')
