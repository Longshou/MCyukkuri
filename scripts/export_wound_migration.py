"""Retain only old rest skin positions needed to migrate pre-rounded triangle wounds."""
from pathlib import Path
import gzip,json,hashlib
ROOT=Path(__file__).resolve().parents[1]
BASE=ROOT/'art/source/rounded-runtime/baseline/src/main/resources/assets/mcyukkuri/yukkuri_wounds'
OUT=ROOT/'src/main/resources/assets/mcyukkuri/yukkuri_wounds/legacy'
records={}
for path in sorted(BASE.glob('*.json.gz')):
    source=json.loads(gzip.decompress(path.read_bytes()));n=source['skinCount'];values=source['points'];points=[v for i,v in enumerate(values[:n*5])if i%5<3]
    data={'source_hash':source['runtimeSourceHash'],'points':points,'bounds':[min(points[k::3])for k in range(3)]+[max(points[k::3])for k in range(3)]}
    target=OUT/path.name;target.parent.mkdir(parents=True,exist_ok=True);target.write_bytes(gzip.compress(json.dumps(data,separators=(',',':')).encode(),mtime=0))
    records[path.name]={'source_hash':data['source_hash'],'sha256':hashlib.sha256(target.read_bytes()).hexdigest(),'vertices':n}
(ROOT/'art/source/rounded-runtime/wound-migration.json').write_text(json.dumps(records,indent=2)+'\n',encoding='utf-8')
print('Preserved',len(records),'legacy wound surfaces for one-time migration.')
