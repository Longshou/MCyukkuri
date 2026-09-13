from pathlib import Path
import UnityPy,json,zlib,zipfile,hashlib
src=Path('E:/yukkuri/YukkuriDiary-zhCN/original');out=Path('E:/yukkuri/MCyukkuri/reference/diary');env=UnityPy.load(str(src/'sharedassets0.assets'))
paths={}
for o in env.objects:
 if o.type.name not in ['Transform','RectTransform']:continue
 d=o.read();parts=[]
 for _ in range(30):
  parts.insert(0,d.m_GameObject.read().m_Name)
  if d.m_Father.path_id==0:break
  d=d.m_Father.read()
 for i in range(len(parts)):
  p='/'.join(parts[i:]);paths[zlib.crc32(p.encode())&0xffffffff]=p
rows=json.loads((out/'animation-details.json').read_text(encoding='utf-8'));summary=[]
for r in rows:
 t=r['tree'];mapped=[]
 for b in t.get('m_ClipBindingConstant',{}).get('genericBindings',[]):
  mapped.append({'path_hash':b['path'],'path':paths.get(b['path']),'attribute':b['attribute']})
 summary.append({'clip':t['m_Name'],'bindings':mapped})
 print(t['m_Name'],list(dict.fromkeys(b['path'] for b in mapped)))
(out/'animation-bindings.json').write_text(json.dumps(summary,ensure_ascii=False,indent=2),encoding='utf-8')
with zipfile.ZipFile('E:/yukkuri/资料/安装包/Yukkuri+Diary_2023.v2_APKPure.apk') as z:
 prefix='assets/bin/Data/sharedassets0.assets';names=[n for n in z.namelist() if n==prefix or n.startswith(prefix+'.split')];names.sort(key=lambda n:int(n.split('.split')[-1]) if '.split' in n else -1)
 data=b''.join(z.read(n) for n in names);same=data==(src/'sharedassets0.assets').read_bytes();print('APK vs analysis assets identical',same)
 (out/'source-evidence.json').write_text(json.dumps({'apk':'E:/yukkuri/资料/安装包/Yukkuri+Diary_2023.v2_APKPure.apk','apk_sha256':hashlib.sha256(Path('E:/yukkuri/资料/安装包/Yukkuri+Diary_2023.v2_APKPure.apk').read_bytes()).hexdigest(),'entries':names,'asset_sha256':hashlib.sha256(data).hexdigest(),'matches_existing_original_analysis':same,'unity_version':'2021.3.26f1','package':'jp.gr.java_conf.sakatasnow.YukkuriDiary'},indent=2),encoding='utf-8')
