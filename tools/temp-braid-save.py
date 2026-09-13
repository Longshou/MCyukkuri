import json
from pathlib import Path
r=Path('standards/yukkuri-library/interactions/marisa-braids');recipes=json.loads((r/'recipes.json').read_text(encoding='utf-8'))
record=dict(schema_version=1,id='action.marisa.braid_interactions.v1',label='普通魔理沙 · 七项辫子互动 v1',status='user_approved_saved',approved_on='2026-09-11',character='marisa',revision='绷直辫身、减少脸侧穿模、帽子转向自己并放低护理',minecraft_integration=False,clips=[dict(id='animation.marisa.'+k,label=v['label'],duration=v['duration'],loop=False) for k,v in recipes['actions'].items()],snapshot_index='approved-snapshots.json')
(r/'saved-action.json').write_text(json.dumps(record,ensure_ascii=False,indent=2)+'\n',encoding='utf-8')
p=r/'README.md';s=p.read_text(encoding='utf-8');s=s.replace('# 普通魔理沙 · 辫子互动\n','# 普通魔理沙 · 辫子互动\n\n已按用户要求保存当前七项动作为 **v1**（2026-09-11）。[动作登记](saved-action.json) · [独立快照与 SHA-256 清单](approved-snapshots.json)。快照包含可编辑模型、动画、预览、验证结果及重建所需的普通魔理沙版本和共享运行代码；后续编辑不会改写这份快照。解压到新目录，打开 `interactions/marisa-braids/index.html`；在解压根目录运行 `python interactions/marisa-braids/build.py` 可重建（需要 Pillow）。\n',1);p.write_text(s,encoding='utf-8')
