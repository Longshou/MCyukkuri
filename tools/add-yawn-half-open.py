"""Keep accepted yawn phases and insert a shared half-open recovery frame."""
from pathlib import Path
from PIL import Image
import json,copy
R=Path(__file__).resolve().parent.parent/'standards/yukkuri-library';O=R/'expressions/component-review'
read=lambda p:json.loads(p.read_text(encoding='utf-8-sig'))
path=O/'yawn-animation-source.json';data=read(path)
backup=O/'yawn-before-half-open.json'
if not backup.exists():backup.write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
original=read(backup);data=copy.deepcopy(original)
half=read(R/'expressions/presets/puff_open.json')
recovery=copy.deepcopy(data['frames'][-1]);recovery['time']=5.15
transition=copy.deepcopy(data['frames'][-1]);transition.update(time=4.6,label='半睁眼过渡')
for side in ['left','right']:
 slot='face_eye_'+side;im=Image.open(R/half['parts'][slot]).convert('RGBA');transition['parts'][slot]=dict(size=list(im.size),pixels=list(im.tobytes()))
data['frames']=data['frames'][:-1]+[transition,recovery]
data['review_status']={'middle_phases':'accepted','change':'Half-open eyes at 4.6 s, fully open at 5.15 s; original phases through 4.1 s preserved byte-for-byte.'}
assert data['frames'][:-2]==original['frames'][:-1]
path.write_text(json.dumps(data,ensure_ascii=False,indent=2),encoding='utf-8')
p=R/'yawn-animation.html';s=p.read_text(encoding='utf-8');a=s.index('const DATA=')+len('const DATA=');b=s.index(';\nconst get=',a);embedded=json.loads(s[a:b]);embedded.update(data)
s=s[:a]+json.dumps(embedded,ensure_ascii=False,separators=(',',':'))+s[b:]
s=s.replace('收口 → 恢复','收口 → 半睁眼 → 恢复')
p.write_text(s,encoding='utf-8')
print('Accepted middle unchanged; half-open 4.6–5.15 s; same 5.8 s loop')
