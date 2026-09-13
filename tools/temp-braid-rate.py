import json
from pathlib import Path
r=json.loads(Path('standards/yukkuri-library/interactions/marisa-braids/assembled/animation-rig.json').read_text(encoding='utf-8'))
for a,c in r['clips'].items():
 rows=[]
 for n,ch in c['tracks'].items():
  if not n.startswith('braid_'):continue
  k=sorted((float(t),v) for t,v in ch['rotation'].items())
  rows.extend((max(abs(u-v) for u,v in zip(x,y)),n,t) for (t,x),(_,y) in zip(k,k[1:]))
 print(a,max(rows))
