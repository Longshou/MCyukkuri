"""Register only this scene, without rebuilding any other collection."""
from pathlib import Path
from datetime import datetime, timezone
import importlib.util, json

HERE=Path(__file__).resolve().parent
ROOT=HERE.parents[1]
spec=importlib.util.spec_from_file_location('library',ROOT/'tools/project-library/manage.py')
lib=importlib.util.module_from_spec(spec);spec.loader.exec_module(lib)
if lib.read(lib.LIB/'collections.json')['collections'].get('basic-interactions',{}).get('status') == 'frozen':
    raise SystemExit('This task is frozen by the user. Wait for explicit adjustment and adoption instructions.')
files=[p for p in lib.list_files(HERE) if lib.portable(p)]
package=lib.make_pack(ROOT/'art/library/scenes/basic-interactions',lib.inventory(files))
lib.verify_pack(package,workspace=True)
config=lib.read(lib.LIB/'collections.json')
meta={'category':'scenes','title':'魔理沙与灵梦 · 六项基础互动','status':'working'}
config['collections']['basic-interactions']=meta
lib.write(lib.LIB/'collections.json',config)
catalog=lib.read(lib.LIB/'catalog.json')
entry=dict(id='basic-interactions',**meta,workspace='variants/basic-interactions',preview='variants/basic-interactions/index.html',dependencies=[],package=package,clips=lib.clips(files),editable_clips=lib.editable_clips(files),scripts=[lib.rel(p) for p in files if p.suffix in {'.py','.js','.cjs'}],procedural_actions=['rub','nuzzle','lick','accessory','talk','hold'])
catalog['collections']=[c for c in catalog['collections'] if c['id']!='basic-interactions']+[entry]
collections=catalog['collections'];allclips=[a for c in collections for a in c['clips']]
catalog['summary'].update(collections=len(collections),source_files=sum(c['package']['files'] for c in collections),exported_clips=len(allclips),editable_model_clips=sum(len(c['editable_clips']) for c in collections),distinct_clip_names=len({a['name'] for a in allclips}),distinct_clip_contents=len({a['sha256'] for a in allclips}),source_pack_bytes=sum(c['package']['bytes'] for c in collections))
stamp=datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')
catalog['metadata_updated_utc']=stamp
lib.write(lib.LIB/'catalog.json',catalog)
lib.write(lib.LIB/'snapshots'/f'{stamp}-basic-interactions.json',catalog)
readme=lib.LIB/'README.md';text=readme.read_text(encoding='utf-8')
row=f"| 魔理沙与灵梦 · 六项基础互动 | 预览已验证，待实体接入 | 6 项场景脚本（未烘焙） | [完整源文件]({package['archive'].removeprefix('art/library/')}) | [打开预览](../../variants/basic-interactions/index.html) |"
lines=[line for line in text.splitlines() if not line.startswith('| 魔理沙与灵梦 · 六项基础互动 |')]
for i,line in enumerate(lines):
    if line.startswith('| 魔理沙 · 进食、食盆与油粮 |'):
        lines.insert(i,row);break
for i,line in enumerate(lines):
    if line.startswith('保存 ') and '个源文件' in line:
        s=catalog['summary'];lines[i]=f"保存 {s['source_files']} 个源文件、{s['exported_clips']} 条动画 JSON 导出记录和 {s['editable_model_clips']} 条模型内嵌动作记录（包含重复与历史版本，两种记录不能相加当作独立动作数），另有互动场景状态机与表情脚本。"
readme.write_text('\n'.join(lines)+'\n',encoding='utf-8')
folder=ROOT/package['archive'];folder=folder.parent
(folder/'README.md').write_text(f'''# 魔理沙与灵梦 · 六项基础互动

[打开预览](../../../../variants/basic-interactions/index.html) · [完整清单与原作依据](../../../../variants/basic-interactions/README.md)

已实现擦擦脸、互相蹭蹭、互相舔舔、舔舔饰品、温柔说话、轻轻抱起／放下。支持两种角色、自由视角、暂停和进度拖动；尚未接入 Minecraft。

[当前源文件包]({Path(package['archive']).name}) · [逐文件校验]({Path(package['manifest']).name})

ZIP 内为项目相对路径。解压后直接打开 `variants/basic-interactions/index.html`；该单文件预览无需网络。六项互动使用场景行为脚本，复制的 Blockbench 模型仅保留其原有动画，新互动尚未烘焙到模型轨道。

包含生成源码、便携预览、当前选定的两种模型、运行库及验证报告。原作 Sprite 不进入源文件包。
''',encoding='utf-8')
full=lib.make_pack(ROOT/'archives/art-preservation/objects/basic-interactions',lib.inventory(lib.list_files(HERE)))
print(json.dumps({'package':package,'local_reference_backup':full},ensure_ascii=False,indent=2))
