from pathlib import Path
import json,subprocess,sys,shutil,importlib.util
from PIL import Image,ImageDraw,ImageFont
ROOT=Path(__file__).resolve().parents[1]
base=ROOT/'variants/diary-combinations/01-lively'
out=ROOT/'variants/diary-combinations/04-round-eyes'
out.mkdir(exist_ok=True)
p=json.loads((base/'settings.json').read_text(encoding='utf-8'));p.update(roundEyes=True,title='04 · 圆眼开心',detail='与 01 相同嘴形、刘海和视线，仅把眼睛轮廓改圆')
(out/'settings.json').write_text(json.dumps(p,ensure_ascii=False,indent=2),encoding='utf-8')
raw=out/'face.rgba'
subprocess.run(['node',str(ROOT/'tools/face_layers.js'),json.dumps(p),str(raw)],check=True)
Image.frombytes('RGBA',(128,128),raw.read_bytes()).save(out/'face-swatch.png');raw.unlink()
for name in ['generate.py','software-viewer.js','viewer-template.html']:
 (out/name).write_text((base/name).read_text(encoding='utf-8').replace('01 · 活泼开心','04 · 圆眼开心'),encoding='utf-8')
subprocess.run([sys.executable,str(out/'generate.py')],check=True)
mods=[]
for i,folder in enumerate([base,out]):
 spec=importlib.util.spec_from_file_location('round_compare_'+str(i),folder/'generate.py');m=importlib.util.module_from_spec(spec);spec.loader.exec_module(m);mods.append(m)
board=Image.new('RGB',(1200,1160),'#eae7de');d=ImageDraw.Draw(board);font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',26)
for i,m in enumerate(mods):
 board.paste(m.render(0,0,580),(i*600+10,50));board.paste(m.render(-30,12,520),(i*600+40,630))
 d.text((i*600+30,14),['01 · 原眼形','04 · 更圆眼形'][i],font=font,fill='#393540')
board.save(out/'comparison.png')
a=Image.open(base/'marisa.png').convert('RGB');b=Image.open(out/'marisa.png').convert('RGB')
changed=[(x,y) for y in range(128) for x in range(128) if a.getpixel((x,y))!=b.getpixel((x,y))]
assert changed and all(13<=x<=83 and 19<=y<=43 for x,y in changed)
assert mods[0].cubes==mods[1].cubes
(out/'README.md').write_text('# 圆眼开心对比\n\n[对比图](comparison.png) · [旋转预览](preview.html) · [模型](marisa.bbmodel)\n\n以 01 活泼开心为对照，仅调整双眼轮廓与瞳孔比例：弧形上缘、加高眼形、圆润下缘，仍为单块小高光。嘴形、眉毛、视线参数、刘海及 53 方块几何保持一致。已检查贴图差异仅在眼部区域、几何完全一致。静态导出，未作 MC 实机验证。\n\n重建：python tools/build_round_eye_comparison.py。\n',encoding='utf-8')
print('PASS geometry identical; only eye pixels changed:',len(changed))
