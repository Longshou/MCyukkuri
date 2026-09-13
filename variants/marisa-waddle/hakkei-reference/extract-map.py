from pathlib import Path
import UnityPy,json,sys
sys.stdout.reconfigure(encoding='utf-8')
r=Path('variants/marisa-waddle/hakkei-reference')
env=UnityPy.load('E:/yukkuri/eightSight.v8.3/ゆっくり八景U_Data/level0')
found=[]
for o in env.objects:
 if o.type.name=='MonoBehaviour':
  try:
   d=o.read_typetree()
   if any('正面ケツ振りボディ' in k for k in d):
    found.append({'file':o.assets_file.name,'id':o.path_id,'keys':[k for k in d if 'ケツ' in k or '基本左右' in k]})
    (r/'graphic-map.json').write_text(json.dumps(d,ensure_ascii=False,indent=2),encoding='utf-8')
    for key in [k for k in d if ('正面ケツ振りボディ' in k or k in ['正面ケツ振り髪グラベース','正面ケツ振りおかざりグラベース','正面ケツ振り顔面グラベース'] or 'ケツ１ボディ' in k or 'ケツ２ボディ' in k)]:
     print(key,str(d[key])[:260])
    break
  except Exception:pass
print('found',len(found))
