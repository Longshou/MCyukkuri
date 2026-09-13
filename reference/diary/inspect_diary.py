from pathlib import Path
import UnityPy,json,collections
src=Path('E:/yukkuri/YukkuriDiary-zhCN/original');out=Path('E:/yukkuri/MCyukkuri/reference/diary');out.mkdir(exist_ok=True)
env=UnityPy.load(str(src/'sharedassets0.assets'))
rows=[];count=collections.Counter()
for obj in env.objects:
 count[obj.type.name]+=1
 if obj.type.name in ['Mesh','Material','Texture2D','GameObject','AnimationClip','AnimatorController']:
  try:
   data=obj.read();name=getattr(data,'m_Name','');row={'id':obj.path_id,'type':obj.type.name,'name':name}
   if obj.type.name=='Texture2D' and ('yukkuri' in name.lower() or name=='hada'):
    path=out/f'tex-{obj.path_id}-{name}.png';data.image.save(path);row['export']=path.name
   if obj.type.name=='Mesh':
    tree=obj.read_typetree();row['vertices']=tree.get('m_VertexData',{}).get('m_VertexCount');row['shapes']=tree.get('m_Shapes',{}).get('channels',[])
   rows.append(row)
  except Exception as e:rows.append({'id':obj.path_id,'error':str(e)[:140]})
(out/'asset-index.json').write_text(json.dumps(rows,ensure_ascii=False,indent=2),encoding='utf-8')
print(count)
print(json.dumps([r for r in rows if r.get('type') in ['Mesh','Material']],ensure_ascii=False)[:19000])
