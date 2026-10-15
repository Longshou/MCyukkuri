import UnityPy,json
from pathlib import Path
r=Path('reference/hakkei-u-expression-audit');e=UnityPy.load('E:/yukkuri/资料/原作参考/eightSight.v8.3/ゆっくり八景U_Data/level0')
for o in e.objects:
 if o.path_id not in (76216,76030):continue
 data=o.get_raw_data();print(o.path_id,o.type.name,len(data),data[:60].hex())
 if o.type.name=='MonoBehaviour':
  try:
   d=o.read();print(d.m_Name,d.m_Script.read().m_ClassName)
  except Exception as ex:print(type(ex).__name__,str(ex)[:150])
 (r/f'raw-{o.path_id}.bin').write_bytes(data)
