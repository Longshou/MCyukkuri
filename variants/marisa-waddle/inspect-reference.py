from pathlib import Path
import UnityPy,json
r=Path('E:/yukkuri/MCyukkuri/variants/marisa-waddle'); out=[]
for p in Path('E:/yukkuri/YukkuriDiary-zhCN/original').glob('*.assets'):
 env=UnityPy.load(str(p))
 for o in env.objects:
  if o.type.name=='AnimationClip':
   t=o.read_typetree(); name=t.get('m_Name','')
   out.append({'file':p.name,'id':o.path_id,'name':name})
   if any(s in name.lower() for s in ['walk','move','run','suri']):
    (r/('reference-clip-'+str(o.path_id)+'-'+p.stem+'.json')).write_text(json.dumps(t,ensure_ascii=False,indent=2),encoding='utf-8')
(r/'reference-animation-index.json').write_text(json.dumps(out,ensure_ascii=False,indent=2),encoding='utf-8')
print(json.dumps(out,ensure_ascii=False))
