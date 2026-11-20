# 捕食种枝巢与软帽

`build.py` 是本包制作入口（`--check` 只读核对，也报出本导出器已不再写出的旧文件）。枝巢由四圈交错的橡木与云杉枝条、伸出的直枝、干草苔藓灰化土垫底与落叶白羽构成（[`wild.py`](../../../standards/yukkuri-library/drafts/care-props/wild.py) `predator_nest`），引用原版贴图与叶色着色（不复制原版文件），垫面在站立高度 2.4；两顶软帽直接选用标准捕食种当前导出的 `predator_cap_` 部件，离身形状与实体帽饰保持一致。

巢架 `predator_nest_frame` 是同一枝巢的未完工阶段（同一制作源 `predator_nest_frames`），没有方块实体渲染，`twigs=1..7` 各一个原生方块模型（`models/block/predator_nest_frame_<n>.json`），枝条元素采样原版橡木／云杉原木侧面：1–3 根是搭在树枝上的三根交叉细枝，4 根起按完工枝巢自己的位置、长短与木种垒出空心的下层圈（4 第一圈、5/6 第二圈两半、7 第三圈一半），原生元素只能绕竖轴按 22.5° 转，枝条取最近的角度、不带俯仰；第 8 根换成完工枝巢；后一阶段包含前一阶段的全部枝条。掉落等于 `twigs` 根木棍，没有物品形态。

执行 `python art/props/predators/build.py` 同步本包 `assembled/`、运行道具网格、物品和方块模型、巢穴与巢架掉落、合成配方；`preview.py` 离线渲染巢架各阶段与完工枝巢对比（输出到 `.local/tmp/tree-predators-art/`）。帽冠更改后还需运行 `scripts/export_badges.py --mounts-only` 更新离身徽章挂点。运行目录通过独立 `predators-index.json` 登记，避免覆盖其他道具包。

指纹见 [provenance.json](provenance.json)，玩法规则见[捕食种实现](../../../docs/FEATURES.md#捕食种)。
