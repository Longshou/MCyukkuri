"""Preview the delivered geometry and compare to Marisa without executing her builder."""
import importlib.util, json
from pathlib import Path
from PIL import Image, ImageDraw, ImageFont

r=Path(__file__).resolve().parent
spec=importlib.util.spec_from_file_location('reimu_builder',r/'generate.py')
g=importlib.util.module_from_spec(spec); spec.loader.exec_module(g)
saved_cubes,saved_atlas=g.cubes,g.atlas
font=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',24)
small=ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',19)
board=Image.new('RGB',(1200,1160),'#eae7de'); d=ImageDraw.Draw(board)
d.text((30,20),'魔理沙 → 灵梦 · 相同模型尺度、视角与地面高度',font=font,fill='#393540')
for col,kind in enumerate(('marisa','reimu')):
    root=r.parent/'marisa-body' if kind=='marisa' else r
    model=json.loads((root/f'{kind}.bbmodel').read_text(encoding='utf-8'))
    g.cubes=[dict(lo=e['from'],hi=e['to'],faces={k:v['uv'] for k,v in e['faces'].items()}) for e in model['elements']]
    g.atlas=Image.open(root/f'{kind}.png').convert('RGB')
    for row,(az,el) in enumerate(((0,0),(-32,14))):
        board.paste(g.render(az,el,530,27,8.8),(col*600+35,row*530+70))
    d.text((col*600+40,1120),'已选定魔理沙基础' if kind=='marisa' else '灵梦 · 八景 U 造型适配',font=small,fill='#635c59')
board.save(r/'comparison.png')
g.cubes,g.atlas=saved_cubes,saved_atlas
frames=[g.render(-32+i*360/48,12,420) for i in range(48)]
frames[0].save(r/'turntable.gif',save_all=True,append_images=frames[1:],duration=100,loop=0,disposal=2)
print('Saved equal-scale comparison and 360-degree turntable.')
