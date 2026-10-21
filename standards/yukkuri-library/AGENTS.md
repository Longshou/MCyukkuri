# 标准素材库规则

本目录保存可复用的美术制作源；项目执行、预览和交付规则统一遵循根目录 [AGENTS.md](../../AGENTS.md)。游戏当前选用的素材快照和来源映射在 `art/`。

## 目录职责

| 目录 | 内容 |
| --- | --- |
| `characters/`、`components/`、`rigs/`、`bindings/`、`textures/` | 角色登记、几何、骨架、UV 绑定与基础贴图 |
| `expressions/`、`motions/`、`behaviors/`、`presentation/` | 表情、动作、行为配置与展示时间表 |
| `assembled/<角色>/` | 与源同步的可编辑模型、几何、动画、贴图和预览页 |
| `subspecies/`、`ecology/`、`interactions/` | 亚种、生态场景与互动制作包 |
| `provenance/` | 来源、认可状态与文件指纹；认可状态以这里的登记为准 |
| `body-standard/` | 圆润身体配方、专属输入、合并组件和构建入口 |
| `drafts/` | 未认可的候选源及仍被导出器使用的道具制作源，不是默认标准或游戏资源 |
| `runtime/`、`tools/` | 素材预览支持代码和包内导出辅助工具 |

## 制作约束

- 油库里是没有脖子的圆球。整体俯仰与横滚合计约 12°，包含摆动峰值；以挤压拉伸、小幅平移、跳动、偏航和表情表达动作，偏航不受此角度限制。
- 几何、骨架、UV 与贴图都从制作源导出。编辑 `.bbmodel` 后须同步回组件及配方；共享组件只有在确实需要影响全部引用者时才改。
- 当前圆润身体由 `body-standard/recipe.json`、`profile.json` 与 `build.py` 构建。`author_standard_species.py` 是推广前的方块制作逻辑，不得覆盖当前登记。
- 魔理沙单辫与爱丽丝造型分别由 `body-standard/features/marisa-single-braid/author.py` 和 `body-standard/features/alice-hakkei/author.py` 制作，再用同目录 `sync_inputs.py` 同步专属输入；按对应 README 导出。
- 标准表情集合以 `expressions/index.json` 的 `standard_set` 与 `provenance/standard-expressions.json` 中的文件指纹为准；标准角色造型以 `provenance/standard-species.json` 为准。变更认可素材时更新对应源登记，不复制或猜测认可状态。
- 每轮绘制出图后，由专门的 subagent 直接看成图，独立审核正常审美、内容辨识和用户指令符合度；不通过则修改、重新出图并复审。代码检查和作者自评不能代替审图，审图不代替用户认可。
- 来源参考与采用边界记在相应 `provenance/`、专题 README 或 Wiki；项目设计不得写成原作事实。具体角色与互动规则见对应专题，不在本规则中另建裁决记录。
