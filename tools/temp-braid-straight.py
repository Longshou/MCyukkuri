import json
from pathlib import Path
p=Path('standards/yukkuri-library/interactions/marisa-braids/recipes.json');r=json.loads(p.read_text(encoding='utf-8'))
r['actions']['remove_hat'].update(duration=9.2,release_time=8.2,phases=[[0,'准备摘帽'],[.7,'伸辫 · 扣住帽沿'],[1.7,'平稳抬离头顶'],[2.7,'缓缓前移'],[4.9,'托稳帽子'],[5.6,'平稳抬回'],[8.2,'戴正 · 收辫']])
r['actions']['care_hat'].update(duration=11.2,release_time=10.2,phases=[[0,'准备整理'],[.7,'双辫固定帽沿'],[1.7,'抬离头顶'],[2.5,'缓缓前移'],[4,'左辫托稳 · 右辫拂尘'],[6.1,'理顺蝴蝶结'],[7.7,'双辫托回'],[10.2,'戴正 · 收辫']])
p.write_text(json.dumps(r,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
