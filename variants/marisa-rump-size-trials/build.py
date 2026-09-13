from pathlib import Path
import json,re,copy
R=Path(__file__).resolve().parent;B=R.parent/'marisa-puff-eye-a-clear'
data=json.loads(re.search(r'const DATA=(.*);',(B/'preview.html').read_text(encoding='utf-8'))[1])
variants=[dict(id='a',label='A · 当前比例',size=1,rump=[1,1,1]),dict(id='b',label='B · 小体型 / 紧凑臀',size=.85,rump=[.9,.95,.9]),dict(id='c',label='C · 小体型 / 饱满臀',size=.85,rump=[1.15,1.05,1.25]),dict(id='d',label='D · 原体型 / 饱满臀',size=1,rump=[1.15,1.05,1.25]),dict(id='e',label='E · 大体型 / 紧凑臀',size=1.15,rump=[.9,.95,.9])]
def point(p,v,rump=False):
    q=p[:]
    if rump:q=[q[0]*v['rump'][0],q[1]*v['rump'][1],.4+(q[2]-.4)*v['rump'][2]]
    return [round(x*v['size'],7) for x in q]
for v in variants:
    out=R/v['id'];out.mkdir(exist_ok=True);bb=copy.deepcopy(data['editableModel'])
    for e in bb['elements']:
        for key in ('from','to','origin'):e[key]=point(e[key],v,e['name'].startswith('rump_'))
    def walk(n):
        if isinstance(n,str):return
        n['origin']=point(n['origin'],v,n['name'].startswith('rump'))
        for c in n['children']:walk(c)
    for n in bb['outliner']:walk(n)
    for a in bb['animations']:
        for n,ch in a['animators'].items():
            for k in ch['keyframes']:
                if k['channel']=='position':
                    for p in k['data_points']:
                        for i,axis in enumerate('xyz'):p[axis]=format(float(p[axis])*v['size']*(v['rump'][i] if ch['name'].startswith('rump') else 1),'.7f')
    bb['name']=v['label'];(out/'marisa.bbmodel').write_text(json.dumps(bb,ensure_ascii=False,separators=(',',':')),encoding='utf-8')
    (out/'proportions.json').write_text(json.dumps(v,ensure_ascii=False,indent=2),encoding='utf-8')
minimal={k:data[k] for k in ('vertices','rig','cubes')};minimal['variants']=variants
minimal['textures']={p['id']:p['source'] for p in data['expressions']['presets'] if p['id'] in ('neutral','surprise')}
render=(B/'software-viewer.js').read_text(encoding='utf-8').split("if(typeof module")[0].replace('vertices[k+1]-11.6','vertices[k+1]-9.5')
runtime='const DATA='+json.dumps(minimal,separators=(',',':'))+';\n'+(B/'motion.js').read_text(encoding='utf-8')+'\n'+render+'\n'+(R/'proportions.js').read_text(encoding='utf-8')
(R/'runtime.js').write_text(runtime,encoding='utf-8');(R/'index.html').write_text((R/'template.html').read_text(encoding='utf-8').replace('__RUNTIME__',runtime),encoding='utf-8')
print('Built five proportional models and shared flee viewer')
