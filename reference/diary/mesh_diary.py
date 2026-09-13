from pathlib import Path
import UnityPy,json,zipfile,hashlib,re
src=Path('E:/yukkuri/YukkuriDiary-zhCN/original');out=Path('E:/yukkuri/MCyukkuri/reference/diary');env=UnityPy.load(str(src/'sharedassets0.assets'))
objects={o.path_id:o for o in env.objects}
for i in [202,224,257,258]:
 m=objects[i].read();s=m.export();print(i,m.m_Name,'export',len(s));(out/f'mesh-{i}.obj').write_text(s,encoding='utf-8')
for i in [292,302]:
 t=objects[i].read_typetree();print('shader',i,t.get('m_ParsedForm',{}).get('m_Name'), t.get('m_Name'))
rows=[]
for i in [305,306,307,308,309,314,316,322,325,326,328]:
 t=objects[i].read_typetree();print('clip',i,t['m_Name'],'keys',list(t.keys())[:14]);rows.append({'id':i,'tree':t})
(out/'animation-details.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2),encoding='utf-8')
for o in env.objects:
 if o.type.name=='Transform':
  d=o.read();name=d.m_GameObject.read().m_Name
  if name in ['Body','Hair04Marisa','LeftEye','RightEye','LeftMabutaUp','Jaw','Spine']:
   print(name,'pos',d.m_LocalPosition,'rot',d.m_LocalRotation,'scale',d.m_LocalScale)
with zipfile.ZipFile('E:/yukkuri/Yukkuri+Diary_2023.v2_APKPure.apk') as z:
 entries=[i.filename for i in z.infolist() if any(x in i.filename for x in ['libunity.so','libil2cpp.so'])];print('engine',entries)
print('unity',next(iter(env.files.values())).unity_version if hasattr(next(iter(env.files.values())),'unity_version') else '?')
