from pathlib import Path
import json,UnityPy,sys
sys.stdout.reconfigure(encoding='utf-8')
r=Path('variants/marisa-stretch-source/reference');r.mkdir(parents=True,exist_ok=True)
m=json.loads(Path('variants/marisa-rump/reference/graphic-map.json').read_text(encoding='utf-8'));env=UnityPy.load('E:/yukkuri/资料/原作参考/eightSight.v8.3/ゆっくり八景U_Data/sharedassets0.assets');objs={o.path_id:o for o in env.objects};record=[]
for key in ['基本ボディグラベース','のび１ボディグラベース','のび２ボディグラベース','のび３ボディグラベース','基本表髪グラベース','基本裏髪グラベース','基本１もみあげグラベース','基本１おかざりグラベース','基本１おかざり裏グラベース','基本顔面グラベース']:
 for index,a in enumerate(m.get(key,[])[:2]):
  if not a['id']:continue
  o=objs[a['id']];d=o.read();im=d.image;file=key+str(index)+'.png';im.save(r/file)
  rec=dict(field=key,index=index,id=a['id'],file=file,size=im.size,pivot=[d.m_Pivot.x,d.m_Pivot.y],ppu=d.m_PixelsToUnits,rect=[d.m_Rect.x,d.m_Rect.y,d.m_Rect.width,d.m_Rect.height],offset=[d.m_RD.textureRectOffset.x,d.m_RD.textureRectOffset.y]);record.append(rec);print(rec)
(r/'source-index.json').write_text(json.dumps(record,ensure_ascii=False,indent=2),encoding='utf-8')
