# 捕食种枝巢与软帽

`build.py` 是本包制作入口（`--check` 只读核对）。枝巢是方块风格的阶梯枝碗（[方块道具](../../../standards/yukkuri-library/drafts/block-props/nests.py)）：交错枝条、伸出的直枝、暗色窝心铺干草、苔藓与白羽，整体沉入台面 0.6，垫面正好在站立高度 2.4，自带图集、不引用原版贴图；两顶软帽直接选用标准捕食种当前导出的 `predator_cap_` 部件，离身形状与实体帽饰保持一致。

执行 `python art/props/predators/build.py` 同步本包 `assembled/`、运行道具网格、图集、物品和方块模型、巢穴掉落与合成配方。帽冠更改后还需运行 `scripts/export_badges.py --mounts-only` 更新离身徽章挂点。运行目录通过独立 `predators-index.json` 登记，避免覆盖其他道具包。

指纹见 [provenance.json](provenance.json)，玩法规则见[捕食种实现](../../../docs/FEATURES.md#捕食种)。
