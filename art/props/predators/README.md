# 捕食种枝巢与软帽

`build.py` 是本包制作入口（`--check` 只读核对）。枝巢是方块风格的阶梯枝碗（[方块道具](../../../standards/yukkuri-library/drafts/block-props/nests.py)）：交错枝条、伸出的直枝、暗色窝心铺干草、苔藓与白羽，整体沉入台面 0.6，垫面正好在站立高度 2.4，自带图集、不引用原版贴图；两顶软帽直接选用标准捕食种当前导出的 `predator_cap_` 部件，离身形状与实体帽饰保持一致。

巢架 `predator_nest_frame` 是同一枝碗的未完工阶段（同一制作源），不沉入台面、没有方块实体，`twigs=1..7` 各一个原生方块模型（`models/block/predator_nest_frame_<n>.json`，每个合并面一个零厚度元素，共用 `textures/block/predator_nest_frame.png`）：1–3 根是搭在树枝上的三根交叉细枝（顶高 2），4 根起用完工枝碗的半径与画法垒出空心的下层圈（4 第一圈、5/6 第二圈两半、7 第三圈一半，顶高 3），第 8 根换成完工枝巢；后一阶段包含前一阶段的全部格子。掉落等于 `twigs` 根木棍，没有物品形态。

执行 `python art/props/predators/build.py` 同步本包 `assembled/`、运行道具网格、图集、物品和方块模型、巢穴与巢架掉落、合成配方；`preview.py` 离线渲染巢架各阶段与完工枝巢对比（输出到 `.local/tmp/tree-predators-art/`）。帽冠更改后还需运行 `scripts/export_badges.py --mounts-only` 更新离身徽章挂点。运行目录通过独立 `predators-index.json` 登记，避免覆盖其他道具包。

指纹见 [provenance.json](provenance.json)，玩法规则见[捕食种实现](../../../docs/FEATURES.md#捕食种)。
