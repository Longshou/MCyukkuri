from pathlib import Path
import json,re
R=Path(__file__).resolve().parent
B=R.parent/'marisa-puff-eye-a-clear'
d=json.loads(re.search(r'const DATA=(.*);',(B/'preview.html').read_text(encoding='utf-8'))[1])
data={k:d[k] for k in ('vertices','rig','cubes')}
data['textures']={p['id']:p['source'] for p in d['expressions']['presets'] if p['id'] in ('neutral','surprise','smug')}
motion=(B/'motion.js').read_text(encoding='utf-8')
render=(B/'software-viewer.js').read_text(encoding='utf-8').split("if(typeof module")[0]
render=render.replace('function renderModel(vertices, texture,','function renderEncounter(vertices, textures,')
render=render.replace('      const t=(v*128+u)*4,p=index*4;','      const texture=textures[i<DATA.vertices.length?0:1];\n      const t=(v*128+u)*4,p=index*4;')
render=render.replace('vertices[k+1]-11.6','vertices[k+1]-8.3')
(R/'runtime.js').write_text('const DATA='+json.dumps(data,separators=(',',':'))+';\n'+motion+'\n'+render+'\n'+(R/'scene.js').read_text(encoding='utf-8'),encoding='utf-8')
template=(R/'template.html').read_text(encoding='utf-8')
(R/'index.html').write_text(template.replace('__RUNTIME__',(R/'runtime.js').read_text(encoding='utf-8')),encoding='utf-8')
print('Built self-contained two-Marisa encounter')
