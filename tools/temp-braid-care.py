import json
from pathlib import Path
p=Path('standards/yukkuri-library/interactions/marisa-braids/recipes.json');r=json.loads(p.read_text(encoding='utf-8'));r['actions']['care_hat'].update(duration=26.3,release_time=25.3,phases=[[0,'准备护理'],[.7,'双辫扣住帽沿'],[1.7,'抬离头顶'],[2.8,'向前取下'],[4.4,'缓缓转向自己'],[9.9,'放低到身前'],[11.8,'扶稳 · 擦拭帽带'],[13.5,'整理蝴蝶结'],[15.2,'重新托起'],[17.2,'转回佩戴方向'],[22.7,'移回头顶'],[25.3,'戴正 · 收辫']]);p.write_text(json.dumps(r,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
p=Path('standards/yukkuri-library/interactions/marisa-braids/viewer.html');s=p.read_text(encoding='utf-8').replace('左辫固定帽沿，右辫拂去尘土、整理蝴蝶结；双辫接回并戴正。','取下帽子，转向自己后放低；左辫扶稳，右辫擦拭帽带、整理蝴蝶结，再托起戴回。');p.write_text(s,encoding='utf-8')
