# 魔理沙单侧粗辫修订

2026-10-13，按用户要求修订魔理沙辫子：单辫，且比灵梦更粗壮，覆盖全部魔理沙。2026-10-14 用户保存单辫造型及适配动作，登记见 [approved.json](approved.json)；原圆润身体批准记录保留为基线。

- 辫子在魔理沙自身左侧，即 +X、`hand_right`。素材库预览和 Minecraft 的正面视图中 +X 显示在画面右侧，`hand_*` 也按该视图命名。
- 辫身由暗色内芯和 6 节交错编织块组成，截面从 2.36 × 2.30 收至 2.06 × 2.00，灵梦系束侧发为 1.00 × 1.30。白色发结和蝴蝶结以下是散开的发尾。
- 另一侧（−X、`hand_left`）是不编、无发带的短侧发 `side_hair_loose*`。
- 部件名 `braid_lock_*` 和 `side_hair_loose*` 都含 hair／braid 字样，秃发时按侧隐藏，与其他头发一致。旧 `side_lock_*` 不参与秃发隐藏。
- 只使用已有贴图格，不改贴图和表情图集。

已同步的形态：普通、螺壳、稀毛、歪脸、豆形、家油与 DOS 魔理沙。稀毛按 `../../../subspecies/deficient/rounded-marisa/recipe.json` 的 `sparse_side_hair` 只保留一束辫、发结、发尾和一缕短侧发。豆形复用同一组辫子组件，按豆形头部比例（×0.52、纵深×0.82）缩放，并逐件沿豆形侧轮廓外移贴合，参数见 `../../../subspecies/deficient/recipes.json` 的 `hair_fit.marisa_braid`；豆形灵梦仍为原有的双股辫。稀毛头顶无发垫，帽子作为独立饰物下压佩戴：帽檐底面落在眉顶以上 0.3（`sparse_hat_seat`），头顶进入帽身，游戏内不再叠加位置适配的帽子下移。家油夹取点改用两侧实际发端（`scripts/runtime_house_interactions.py`）。

各形态的模型指纹、改动部件、源文件与动作源指纹见 [approved.json](approved.json)（保存时版本）。之后再改造型时，`record.py` 只更新 [candidate.json](candidate.json)，不得沿用保存记录。

## 重建顺序

命令从项目根目录运行，`author.py`、`sync_inputs.py`、`export_house_runtime.py` 与 `record.py` 位于本目录。本节命令只生成静态美术和游戏数据。Pillow 与 numpy 在项目内的 `.local/venv` 中提供，不需要编译和启动游戏。

1. `python author.py`：写 `components/marisa/hand-left.json`、`hand-right.json` 和 `rigs/marisa.json` 的辫梢骨骼。粗细、位置参数在脚本顶部。
2. `python sync_inputs.py`：按方块时期的装配方式重建 `inputs/marisa`、`inputs/marisa_tsumuri` 的冻结输入及 `inputs/manifest.json`。角色登记 `extra_motions` 所列的专属动作（螺壳的静止、轻摆、缩入）同时从 `motions/variants/` 重新编入；动作源未变时输出与原文件逐字节相同。
3. `body-standard/build.py --character marisa`、`--character marisa_tsumuri`（`--character` 需先完整构建一次），再依次运行 `scripts/README.md` 所列圆润运行包导出。
4. 运行稀毛与歪脸 `scripts/export_marisa_deficient_rounded.py`（先用新圆润魔理沙替换包内冻结的 donor 三个文件）和豆形 `scripts/export_bean_assets.py`。
5. 家油依次运行 `ecology/house-yukkuri/build.py --characters-only`、`export_house_runtime.py marisa`（以 `inputs/<种族>` 为标准源调用正式导出函数，与原接入一致）、`scripts/export_house_carry_motion.py`、`scripts/export_house_face_motion.py --publish` 和 `node scripts/export_wound_surfaces.cjs --ids=house_marisa`。
6. DOS 运行 `subspecies/reimu-dos/build.py`。
7. 运行 `export_placement_fit.cjs`、`export_motion_corrections.py`、`export_hat_interactions.py`、`export_badges.py --mounts-only` 及束具 `drafts/operation-tools/build.py`。
8. `python check_runtime_clearance.py --out runtime-clearance.json`：复核游戏动作中的辫子穿插（需要 numpy）。
9. `python record.py`：更新 `candidate.json`。用户说“保存”时加 `--approve "<原话>"`，另写 `approved.json`。

本次在 macOS 上导出。PNG 编码、浮点末位和 LF／CRLF 指纹差异已按内容核对后还原，只保留魔理沙相关变化；游戏的模型指纹检查同时接受 LF 与 CRLF。

动作编排：七项辫子互动已改为单辫 v2（[互动包](../../../interactions/marisa-braids/README.md)）。家油魔理沙的夹取、搬运、放下只用辫子：两个夹取点都指向辫梢，物品跟随辫梢，短侧发留在头侧；家油灵梦仍用两束短发。

## 穿插治理

2026-10-14 按“可接受少量穿模，但尽量调整得更自然”处理，同日随单辫动作一起保存。

- 游戏动作：摇摆、撑住、压低和侧弯类动作由动作修正第8版的辫子跟随处理（[说明](../../../motions/variants/contact-safe/README.md#魔理沙单辫跟随)）。螺壳缩入时两侧头发改为后仰并略向外张开，不再内收穿过压扁的身体。
- 复核：`check_runtime_clearance.py` 按游戏的两骨骼权重摆出六种运行模型，叠加已发布的动作修正，以 10 Hz 采样每个动作，测量辫子与短侧发比静止时多进入身体皮肤的深度和低于地面的距离，结果写入 [runtime-clearance.json](runtime-clearance.json)。DOS 魔理沙不在游戏运行包中，未复核。
- 结果（单位为模型像素，1 格 = 16）：螺壳缩入从辫子 1.19、短侧发 1.02 降至 0；家油搬运 0.085、放下 0.054，其余动作均不超过 0.04，没有低于地面。静止时贴住皮肤的深度作为基线扣除。
- 七项互动：辫身避开帽檐、子油绕行后，最大残留为辫子扫过帽檐下沿时约 0.41，按可接受的轻擦保留；详见[互动包](../../../interactions/marisa-braids/README.md#穿插)。

## 未完成

- 本机未做实机查看；保存的是造型与动作设计，运行模型的游戏验收状态仍为待复查。动作仅做了离线数值检查和关键帧渲染，穿插数值不代替游戏内观察。
