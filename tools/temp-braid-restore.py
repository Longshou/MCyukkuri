import json,zipfile,tempfile,hashlib,subprocess,sys
from pathlib import Path
root=Path('standards/yukkuri-library/interactions/marisa-braids');record=json.loads((root/'approved-snapshots.json').read_text(encoding='utf-8'))[-1]
out=Path(tempfile.mkdtemp(prefix='braids-v1-restore-',dir='tools/temp')).resolve()
with zipfile.ZipFile(root/record['archive']) as z:
 for name in z.namelist():
  dest=(out/name).resolve();assert dest.is_relative_to(out),name
 z.extractall(out)
subprocess.run([sys.executable,str(out/'interactions/marisa-braids/build.py')],check=True)
manifest=json.loads((root/record['manifest']).read_text(encoding='utf-8'))['files'];checked=[]
for name,e in manifest.items():
 if name.startswith('interactions/marisa-braids/assembled/') or name=='interactions/marisa-braids/index.html':
  assert hashlib.sha256((out/name).read_bytes()).hexdigest()==e['sha256'],name;checked.append(name)
report=dict(snapshot=record['archive'],zip_sha256=record['sha256'],restore_verified=True,rebuild_byte_identical=True,rebuilt_files=checked)
(root/'snapshots/156bec42878424fc95b3.restore-verification.json').write_text(json.dumps(report,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
p=Path('standards/yukkuri-library/README.md');s=p.read_text(encoding='utf-8');s='已保存：[普通魔理沙七项辫子互动 v1](interactions/marisa-braids/saved-action.json) · [独立快照索引](interactions/marisa-braids/approved-snapshots.json)。\n\n'+s;p.write_text(s,encoding='utf-8')
p=Path('art/library/README.md');s=p.read_text(encoding='utf-8');s=s.replace('# 美术与动作资料库\n','# 美术与动作资料库\n\n新增保存：[普通魔理沙七项辫子互动 v1](../../standards/yukkuri-library/interactions/marisa-braids/README.md)，包含绷直辫身、脸侧避让及转向自己后放低护理帽子的确认版本。[独立快照索引](../../standards/yukkuri-library/interactions/marisa-braids/approved-snapshots.json)；不计入下方历史 variants 统计。\n',1);p.write_text(s,encoding='utf-8')
print('Restored 30 files; rebuilt preview and all six assembled files are byte-identical to the saved version.')
