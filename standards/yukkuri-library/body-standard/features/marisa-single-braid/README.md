# 魔理沙单侧粗辫修订

2026-10-13，按用户要求修订魔理沙辫子：单辫，且比灵梦更粗壮，覆盖全部魔理沙。造型待确认，原圆润身体批准记录保留为基线。

- 辫子在魔理沙自身左侧，即 +X、`hand_right`。素材库预览和 Minecraft 的正面视图中 +X 显示在画面右侧，`hand_*` 也按该视图命名。
- 辫身由暗色内芯和 6 节交错编织块组成，截面从 2.36 × 2.30 收至 2.06 × 2.00，灵梦系束侧发为 1.00 × 1.30。白色发结和蝴蝶结以下是散开的发尾。
- 另一侧（−X、`hand_left`）是不编、无发带的短侧发 `side_hair_loose*`。
- 部件名 `braid_lock_*` 和 `side_hair_loose*` 都含 hair／braid 字样，秃发时按侧隐藏，与其他头发一致。旧 `side_lock_*` 不参与秃发隐藏。
- 只使用已有贴图格，不改贴图和表情图集。

已同步的形态：普通、螺壳、稀毛、歪脸、豆形、家油与 DOS 魔理沙。稀毛按 `../../../subspecies/deficient/rounded-marisa/recipe.json` 的 `sparse_side_hair` 只保留一束辫、发结、发尾和一缕短侧发。豆形复用同一组辫子组件，按豆形头部比例（×0.52、纵深×0.82）缩放，并逐件沿豆形侧轮廓外移贴合，参数见 `../../../subspecies/deficient/recipes.json` 的 `hair_fit.marisa_braid`；豆形灵梦仍为原有的双股辫。稀毛头顶无发垫，帽子作为独立饰物下压佩戴：帽檐底面落在眉顶以上 0.3（`sparse_hat_seat`），头顶进入帽身，游戏内不再叠加位置适配的帽子下移。家油夹取点改用两侧实际发端（`scripts/runtime_house_interactions.py`）。

各形态的模型指纹、改动部件和源文件指纹见 [candidate.json](candidate.json)。

## 重建顺序

命令从项目根目录运行，`author.py`、`sync_inputs.py`、`export_house_runtime.py` 与 `record.py` 位于本目录。本节命令只生成静态美术和游戏数据。Pillow 与 numpy 在项目内的 `.local/venv` 中提供，不需要编译和启动游戏。

1. `python author.py`：写 `components/marisa/hand-left.json`、`hand-right.json` 和 `rigs/marisa.json` 的辫梢骨骼。粗细、位置参数在脚本顶部。
2. `python sync_inputs.py`：按方块时期的装配方式重建 `inputs/marisa`、`inputs/marisa_tsumuri` 的冻结输入及 `inputs/manifest.json`。
3. `body-standard/build.py --character marisa`、`--character marisa_tsumuri`，再依次运行 `scripts/README.md` 所列圆润运行包导出。
4. 运行稀毛与歪脸 `scripts/export_marisa_deficient_rounded.py`（先用新圆润魔理沙替换包内冻结的 donor 三个文件）和豆形 `scripts/export_bean_assets.py`。
5. 家油依次运行 `ecology/house-yukkuri/build.py --characters-only`、`export_house_runtime.py marisa`（以 `inputs/<种族>` 为标准源调用正式导出函数，与原接入一致）、`scripts/export_house_carry_motion.py`、`scripts/export_house_face_motion.py --publish` 和 `node scripts/export_wound_surfaces.cjs --ids=house_marisa`。
6. DOS 运行 `subspecies/reimu-dos/build.py`。
7. 运行 `export_placement_fit.cjs`、`export_motion_corrections.py`、`export_hat_interactions.py`、`export_badges.py --mounts-only` 及束具 `drafts/operation-tools/build.py`。
8. `python record.py`：更新 `candidate.json`。

本次在 macOS 上导出。PNG 编码、浮点末位和 LF／CRLF 指纹差异已按内容核对后还原，只保留魔理沙相关变化；游戏的模型指纹检查同时接受 LF 与 CRLF。

## 未完成

- 七项辫子互动 v1 按双辫编排，`interactions/marisa-braids/build.py` 在新标准上会中止，需按单辫重新编排。
- 未执行 `./gradlew build`、GameTest 和实机查看；运行模型的验收状态已标为待复查。
