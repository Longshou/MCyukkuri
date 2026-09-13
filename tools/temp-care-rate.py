import json
from pathlib import Path
r=json.loads(Path('standards/yukkuri-library/interactions/marisa-braids/assembled/animation-rig.json').read_text(encoding='utf-8'));c=r['clips']['care_hat']
for t in [19.45,19.5,19.55,19.6,19.65]:
 print(t,{n:{ch:v[str(t)] for ch,v in c['tracks'][n].items()} for n in ['grip_right','braid_right_0','braid_right_tip']})
print('maxScale',max((v[1],a,n,t) for a,c in r['clips'].items() for n,ch in c['tracks'].items() if n.startswith('braid_') for t,v in ch['scale'].items()))
