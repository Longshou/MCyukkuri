"""Build a current runtime contact/toilet pose sheet without launching any UI."""
from pathlib import Path
import json,subprocess
from PIL import Image,ImageDraw,ImageFont
HERE=Path(__file__).resolve().parent;ROOT=HERE.parents[3];OUT=ROOT/'.local/previews/toilet-contact';OUT.mkdir(parents=True,exist_ok=True)
resource=json.loads((ROOT/'src/main/resources/assets/mcyukkuri/yukkuri_presentation/toilet_contact.json').read_text(encoding='utf8'))
labels=[('ground_toilet','地面排便 · 两次收缩用力','reimu',[0,.65,1.3]),('toilet','设施排便 · 保持接地','marisa',[0,.65,1.3]),('boat_toilet','帽沿排便 · 服务器负责真实座位','marisa',[0,.65,1.3]),('toilet_accident','失禁 · 惊慌后用力','reimu',[0,.65,1.3]),('first_toilet_wait','幼体等待 · 尚未受刺激','reimu',[0,.5,1]),('first_toilet_stimulated','幼体受刺激 · 惊讶、收缩、含泪','reimu',[0,.15,.35]),('first_toilet_assist','亲油后侧照护 · 轻点头与口部节奏','marisa',[0,.15,.35])]
tasks=[]
for action,label,model,times in labels:
 for i,t in enumerate(times):
  clip=resource['models'][model]['clips'][action];face=next((face for a,b,face in clip['expression_spans'] if a<=t<b),'neutral')
  texture=f'{model}-{face}.texture'
  (OUT/texture).write_bytes(Image.open(ROOT/f'src/main/resources/assets/mcyukkuri/textures/entity/{model}/{face}.png').convert('RGBA').tobytes())
  tasks.append(dict(id=f'{action}-{i}',action=action,model=model,time=t,face=face,texture=texture))
(OUT/'tasks.json').write_text(json.dumps(tasks),encoding='utf8')
a=Image.open(ROOT/'src/main/resources/assets/mcyukkuri/textures/entity/marisa/chewing.png').convert('RGBA');b=Image.open(ROOT/'src/main/resources/assets/mcyukkuri/textures/entity/reimu/puff.png').convert('RGBA');size=max(a.width,b.width);atlas=Image.new('RGBA',(size*2,size*2),(226,126,137,255));atlas.paste(a.resize((size,size)),(0,0));atlas.paste(b.resize((size,size)),(size,0));(OUT/'contact-atlas.texture').write_bytes(atlas.tobytes())
subprocess.run(['D:/NodeJs/node.exe',str(HERE/'render.cjs')],check=True,cwd=ROOT)
font=lambda n:ImageFont.truetype('C:/Windows/Fonts/msyh.ttc',n)
sheet=Image.new('RGB',(1340,2780),'#f3f0e8');draw=ImageDraw.Draw(sheet)
draw.text((30,22),'排便与后侧照护 · 当前动作资源',font=font(32),fill='#253c35')
draw.text((30,72),'离线骨骼/表情采样；不代表 Minecraft 接触、排泄物或帽沿效果验收',font=font(19),fill='#54665e')
for row,(action,label,model,times) in enumerate(labels):
 y=125+row*375;draw.text((30,y),label,font=font(24),fill='#253c35')
 for col,t in enumerate(times):
  task=tasks[row*3+col];image=Image.frombytes('RGBA',(420,310),(OUT/(task['id']+'.rgba')).read_bytes());sheet.paste(image,(25+col*440,y+34),image)
  draw.text((40+col*440,y+340),f'{t:.2f}s · {task["face"]}',font=font(17),fill='#54665e')
sheet.save(OUT/'toilet-contact.png')
contact=Image.new('RGB',(1340,760),'#f3f0e8');cd=ImageDraw.Draw(contact);cd.text((30,20),'成年亲油与 0.2 尺寸幼体 · 后侧照护离线采样',font=font(30),fill='#253c35');cd.text((30,64),'按当前嘴点/俯仰预算/0.53格站距绘制；不模拟实际碰撞、AI或服务端接触判定',font=font(19),fill='#54665e');ci=Image.frombytes('RGBA',(1340,650),(OUT/'contact-scene.rgba').read_bytes());contact.paste(ci,(0,100),ci);contact.save(OUT/'contact-scene.png')
(OUT/'index.html').write_text('<!doctype html><meta charset="utf-8"><title>排便与后侧照护</title><style>body{background:#f3f0e8;max-width:1340px;margin:20px auto;font-family:sans-serif}img{width:100%}</style><h1>排便与后侧照护</h1><p>当前运行模型与新片段的离线采样；真实接触、舌头定位、碰撞及排泄物生成需游戏实测。</p><img src="contact-scene.png"><img src="toilet-contact.png">',encoding='utf8')
# Local hub only: include every currently available topic without modifying shared source templates.
links=''.join(f'<li><a href="{p.parent.name}/index.html">{p.parent.name}</a></li>' for p in sorted(OUT.parent.glob('*/index.html')))
(OUT.parent/'index.html').write_text('<!doctype html><meta charset="utf-8"><title>当前预览</title><h1>当前预览</h1><ul>'+links+'</ul>',encoding='utf8')
# Remove only our disposable intermediate render products, all below this topic's resolved path.
for item in OUT.iterdir():
 if item.suffix in ('.rgba','.texture') or item.name=='tasks.json':item.unlink()
print(OUT/'toilet-contact.png')
