# 标准素材库约定

本目录是标准美术制作入口。执行与交付规则统一遵循根目录 [AGENTS.md](../../AGENTS.md)。

## 目录职责

| 目录 | 内容与维护规则 |
| --- | --- |
| `characters/` | 角色 ID、组件列表、骨架、贴图和行为入口；新增角色先在这里登记 |
| `components/`、`rigs/`、`bindings/`、`textures/` | 几何、骨架、UV 绑定与基础贴图；共用组件只在明确需要影响所有引用者时修改 |
| `expressions/`、`motions/`、`behaviors/`、`presentation/` | 表情与动作源、行为配置和展示时间表；优先引用现有部件与配方 |
| `assembled/<角色>/` | 与源同步的可编辑模型、几何、动画、贴图和独立预览；不单独作为另一套制作源 |
| `subspecies/`、`ecology/`、`interactions/` | 亚种、生态场景和互动包；保留包内构建依赖和已确认快照 |
| `provenance/` | 来源与确认记录；以既有角色或资产包的登记为准，不另建冲突的批准状态 |
| `body-standard/` | 已确认圆润身体配方、冻结专属输入、合并组件和重建入口；适用与排除范围见包内 README |
| `drafts/` | 待确认设计和试用预览；不得作为默认标准或游戏运行资源 |
| `previews/current/` | 当前供用户查看的简化预览；正式完整预览仍在 `assembled/` |
| `reports/current/` | 当前检查结果；说明适用版本、环境和检查范围 |
| `archive/` | 仍有追溯用途的历史记录，注明保留原因；不证明当前版本通过 |
| `runtime/` | 素材预览使用的支持代码；不属于 Minecraft 游戏运行资源 |
| `tools/`、`tests/` | 包内辅助导出与测试脚本；制作主入口保持在本目录以保留相对依赖 |

## 制作与同步

- 先读取目标角色登记和对应来源说明，明确目标组件与参考组件。调整帕秋莉专属造型时，不覆盖其他角色或共用身体。
- 当前圆润标准体型的制作入口为 `body-standard/recipe.json`、`profile.json` 与 `build.py`，角色登记指向生成的组件和合并骨架。`author_standard_species.py` 保留推广前的方块制作逻辑，不得覆盖当前登记。专属造型修改应建立输入修订，并同步组件与导出。
- 魔理沙侧发由 `body-standard/features/marisa-single-braid/author.py` 生成方块组件，再用同目录 `sync_inputs.py` 重建冻结输入；七种魔理沙的后续导出顺序见该目录 README。
- 仅修改帕秋莉头发时，只修订对应专属输入，再通过 `body-standard/build.py` 同步当前合并模型。旧 `tools/sync_patchouli_geometry.py` 只支持推广前结构，不适用于当前分段权重。
- 按改动范围选择局部或完整素材构建，保留 `assembled/` 与派生包之间的读取关系。
- `.bbmodel` 可用于人工编辑；编辑结果须同步回组件及制作源，不能只保留导出文件中的变化。
- 表情制作先复用已确认的五官与部件，再交付组合核对。泪水默认优先使用下眼缘泪花；有明确场景或用户要求时再采用积泪、长泪痕或滴落。
- A/B 表情帧必须在当前模型的常用预览尺寸下有可见区别，不能只改编号、配方说明或登记为同形。优先通过泪花的饱满程度、高光或位置形成差分，并实际并排或切换检查；该约定同样适用于后续批次。

## 确认与预览

- 标准表情集合以 `provenance/standard-expressions.json` 和 `expressions/index.json` 的 `standard_set` 为准；角色标准记录在 `provenance/standard-species.json`。
- 用户说“保存 xx”即确认为标准，同步登记与导出；未确认的新造型标为待确认即可。
- 改完给一张当前预览（正面／侧面按需），重点看轮廓、穿插和饰品挂接。旧预览可直接替换，不必逐份归档。
- 外部游戏提取物与作品参考不进入正式贴图或导出包；来源说明放 `provenance/` 或 Wiki。
