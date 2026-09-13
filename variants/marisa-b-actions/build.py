from pathlib import Path
import json,re
R=Path(__file__).resolve().parent
runtime=(R.parent/'marisa-b-taller/runtime.js').read_text(encoding='utf-8')
d=json.loads(re.search(r'const DATA=(.*);',runtime)[1]);d['models']={'b':d['models']['b']};d['variants']=[v for v in d['variants'] if v['id']=='b']
face=json.loads((R.parent/'marisa-puff-eye-a-clear/expressions.json').read_text(encoding='utf-8'))
d['textures']={p['id']:p['source'] for p in face['presets'] if p['id'] in ('neutral','surprise')}
runtime=re.sub(r'const DATA=.*;',lambda _: 'const DATA='+json.dumps(d,separators=(',',':'))+';',runtime,count=1)
(R/'index.html').write_text((R/'template.html').read_text(encoding='utf-8').replace('__RUNTIME__',runtime),encoding='utf-8')
print('Selected B: three motions and independent orbit camera')
