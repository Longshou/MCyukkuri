from pathlib import Path
import UnityPy,json
src=Path('E:/yukkuri/YukkuriDiary-zhCN/original');out=Path('E:/yukkuri/MCyukkuri/reference/diary');env=UnityPy.load(str(src/'sharedassets0.assets'))
objs={o.path_id:o for o in env.objects}
def pname(p):
 try:return p.read().m_Name
 except:return str(p)
def transname(p):
 try:return p.read().m_GameObject.read().m_Name
 except:return '?'
rows=[]
for o in env.objects:
 if o.type.name=='SkinnedMeshRenderer':
  d=o.read();m=d.m_Mesh.read();row={'renderer':o.path_id,'object':pname(d.m_GameObject),'mesh_id':d.m_Mesh.path_id,'mesh':m.m_Name,'materials':[pname(p) for p in d.m_Materials],'bones':[transname(p) for p in d.m_Bones]};rows.append(row)
 if o.type.name=='AnimationClip':
  t=o.read_typetree();rows.append({'clip':o.path_id,'name':t.get('m_Name'),'paths':list(dict.fromkeys(v.get('path','') for k in ['m_RotationCurves','m_PositionCurves','m_ScaleCurves','m_FloatCurves'] for v in t.get(k,[])))})
 if o.type.name=='Material' and o.path_id in [35,59]:
  t=o.read_typetree();rows.append({'material':o.path_id,'tree':t})
(out/'rig-materials.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2),encoding='utf-8')
for row in rows:
 if row.get('mesh')=='Body' or row.get('mesh')=='Hair04Marisa' or 'material' in row:print(json.dumps(row,ensure_ascii=False))
print('clips',[(r['clip'],r['name']) for r in rows if 'clip'in r])
