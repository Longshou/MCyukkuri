# 捕食种枝巢与软帽

`build.py` 是本包制作入口。枝巢由四圈交错枝条与底部干草纤维构成，使用自制两色图集；两顶软帽直接选用标准捕食种当前导出的 `predator_cap_` 部件，离身形状与实体帽饰保持一致。

执行 `python art/props/predators/build.py` 同步本包 `assembled/`、运行道具网格、图集、物品和方块模型、巢穴掉落与合成配方。帽冠更改后还需运行 `scripts/export_badges.py --mounts-only` 更新离身徽章挂点。运行目录通过独立 `predators-index.json` 登记，避免覆盖其他道具包。

指纹见 [provenance.json](provenance.json)，玩法规则见[捕食种实现](../../../docs/PREDATOR_IMPLEMENTATION.md)。
