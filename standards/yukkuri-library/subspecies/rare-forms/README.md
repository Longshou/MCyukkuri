# 稀有种与变体派生包（rare-forms）

妖梦、红眼妖梦、长尾橙、圆尾橙、被炉魔理沙五种形态，以及妖梦叼的木棍道具「觀樓劍」。依据和游戏规则见 [docs/FEATURES.md](../../../../docs/FEATURES.md#妖梦与稀有变体)。

- `recipe.json`：制作源。包括配色映射（妖梦的头发和发带）、蕾丝收起、蝴蝶结方块、红眼虹膜、尾巴变换、被炉方块和调色、徽章位置，以及觀樓劍的尺寸与配色。
- `build.py`：以基底种族的选定运行模型（`art/source/rounded-runtime/<基底>/runtime.model.json`）和游戏贴图为输入，生成 `assembled/<形态>/` 下的 `runtime.model.json`、全部表情贴图和 `derived.json`（记录做了哪些操作及指纹）。派生时保持基底的顶点数和部件顺序。
- 修改造型：改 `recipe.json`，然后依次运行
  ```
  <py> scripts/export.py --family source:standards/yukkuri-library/subspecies/rare-forms/build.py --family rare-forms
  ```
  加 `--check` 只做核对。基底选定的运行模型或贴图更新后，还要重跑按运行模型逐一出数据的 `scripts/export_placement_fit.cjs`、`scripts/export_hat_interactions.py`、`scripts/export_motion_corrections.py` 和 `scripts/export_badges.py`；五种形态的适配、帽交互、鼓腮和徽章挂点由这些导出器按各自模型生成。通用表情库通过 `expressions/runtime.json` 的 `texture_source` 使用本包的派生图集，保留红眼与配色；`sad`／`afraid` 只由 `expressions/derived/faces.py` 生成，`cheek_puff` 只由动作导出器生成。器具适配只由 `drafts/operation-tools/build.py` 生成，不从基底器具复制。
- 本机预览：`.local/previews/youmu/`（不入库）。
- 出生姿态由 lore `birth` 家族按每种模型编译，稀有形态导出器不复制基底的出生姿态。
