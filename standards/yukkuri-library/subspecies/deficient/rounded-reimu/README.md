# 稀毛与歪脸灵梦圆润体态

2026-09-14 按用户要求补齐灵梦两种不足形态。此前修复只覆盖魔理沙；本次 `reimu_sparse` 与 `reimu_face` 使用冻结的当前圆润灵梦身体、256 像素图集和 19 段动作。身体与连续权重保持供体数据，复用魔理沙不足形态的制作逻辑，保留灵梦的蝴蝶结和红白侧发套。

当前造型待用户确认，未更改既有标准批准指纹。

- 稀毛：仅保留三束刘海、细侧发和短后束，五官保持正常比例。移除原悬空顶板，发束随身体曲面弯曲；蝴蝶结下移并向身体靠拢，尾带贴合后部表面。
- 歪脸：保留完整头发，左眼按既有配方缩小并偏移，嘴部偏移；五官重新贴合身体表面。头发与头饰保留圆润灵梦供体的结构。

制作源为 [recipe.json](recipe.json)、`inputs/` 冻结供体和上级 `recipes.json` 的特征参数。可编辑导出在 `../assembled/reimu_sparse/`、`../assembled/reimu_face/`；选用快照在 `art/source/subspecies-runtime/`，运行资源在 `src/main/resources/assets/mcyukkuri/`。对应模型、骨架、贴图、动作、伤口与指纹见 [manifest.json](manifest.json)。

定向重建：`python scripts/export_reimu_deficient_rounded.py`；多角度与动作检查：`python scripts/preview_reimu_deficient.py`；实际页面检查：`node scripts/verify_marisa_deficient_browser.cjs reimu`。命令从项目根目录执行。主生成逻辑复用 `scripts/export_marisa_deficient_rounded.py`，稀毛曲面发束复用相邻 `rounded-marisa/hair.py`；输入和导出分别登记，执行灵梦入口不会重建魔理沙。

重建同时同步两种源模型、骨架、运行模型、18 张表情图集、嘴部与身体参数、离身蝴蝶结、徽章挂点、伤口表面和旧伤口迁移输入。旧表情导出器跳过这两套 256 像素图集。
