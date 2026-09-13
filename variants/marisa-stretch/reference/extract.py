from pathlib import Path
import UnityPy,json,sys
sys.stdout.reconfigure(encoding='utf-8')
r=Path('variants/marisa-stretch/reference');mapping=json.loads(Path('variants/marisa-rump/reference/graphic-map.json').read_text(encoding='utf-8'))
env=UnityPy.load('E:/yukkuri/eightSight.v8.3/ゆっくり八景U_Data/sharedassets0.assets');objs={o.path_id:o for o in env.objects};record=[]
for key,refs in mapping.items():
 if key in ['のび１ボディグラベース','のび２ボディグラベース','のび３ボディグラベース','のび３右１ボディグラベース','のび３右２ボディグラベース','のび３左１ボディグラベース','のび３左２ボディグラベース']:
  a=refs[0];o=objs[a['id']];d=o.read();d.image.save(r/(key+'.png'));record.append(dict(field=key,id=a['id'],name=d.m_Name,size=d.image.size));print(record[-1])
(r/'source-index.json').write_text(json.dumps(record,ensure_ascii=False,indent=2),encoding='utf-8')
