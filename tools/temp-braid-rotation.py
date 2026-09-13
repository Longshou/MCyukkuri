import json
from pathlib import Path
r=json.loads(Path('standards/yukkuri-library/interactions/marisa-braids/assembled/animation-rig.json').read_text(encoding='utf-8'))
for action in ['remove_hat','care_hat']:
 rows=[]
 for n,chs in r['clips'][action]['tracks'].items():
  keys=sorted((float(t),v) for t,v in chs['rotation'].items())
  for (a,x),(b,y) in zip(keys,keys[1:]):
   d=max(abs(v-u) for u,v in zip(x,y))
   if d>60:rows.append((n,a,b,round(d,2)))
 print(action,rows[:20])
 print('hat max speed',max(max(abs(u-v) for u,v in zip(x,y))/(b-a) for (a,x),(b,y) in zip(sorted((float(t),v) for t,v in r['clips'][action]['tracks']['hat']['rotation'].items()),sorted((float(t),v) for t,v in r['clips'][action]['tracks']['hat']['rotation'].items())[1:])))
