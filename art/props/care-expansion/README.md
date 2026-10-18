# 垫料、升级厕所与小玩具

本包是八景 U 剩余移植的待确认 MC 适配版，不改变已确认造型。制作源位于 `standards/yukkuri-library/drafts/care-expansion/build.py`，`recipe.json` 保存参数；源目录的 `assembled/` 与本包 `selected/` 同步保存可编辑 Blockbench 网格、三角网格和原创色板。

制作脚本复用相邻器具包的几何基本函数，执行 `python standards/yukkuri-library/drafts/care-expansion/build.py --check` 核对源与导出。[清单](manifest.json)记录来源、输出指纹和本包语言键。静态模型无独立骨架或动作；游戏中的玩具运动由实际掉落物驱动。运行资源位于 `yukkuri_props/`、`textures/props/`、`models/`、`blockstates/`，配方与掉落规则位于 `data/mcyukkuri/`。

报纸、泥土与砂纸加上不铺垫材，构成原生四档入口；粗糙度与缓冲关系参考本机原生操作审计和庭院环境规则，具体 MC 数值属于适配。砂纸只按真实接地位移积累擦伤，静止、离地、传送、卸载不补算行程。滚动与滑动沿用接触工具模块，避免重复结算。

基础沙厕容量为 8 份，陶瓷油厕为 16 份，带盖油厕为 32 份。每份排泄物仍保存来源；清理与拆除退回同一批物品，显示最多八份。覆盖部分不表示已有自动冲水。

玻璃弹珠、橡胶球、两种布偶、漂亮石子与透明骰子使用独立原创网格。球类会被推动并追近，其他物件以近距离探看和轻触为主；每次有效接触消耗体力，同一物品只能由一个玩耍过程占用。

真实掉落玩具按网格底面接地，不使用原版悬浮偏移；展示台中的物件采用固定展示。透明骰子对面点数为 1/6、2/5、3/4。造型未获标准确认。

原生核读入口：`E:/yukkuri/Eight景U-ModLab/wiki/game/native-operation-audit.md`、`wiki/game/environment-interactions.md`。只参考物件含义和行为关系，没有复制原生游戏像素。当前运行与视觉证据见[文档索引](../../../docs/README.md)。

2026-10-17：陶瓷／带盖油厕和三种垫料改由[照料道具统一造型](../../../standards/yukkuri-library/drafts/care-props/README.md)的 `trays.py` 制作；玩具网格不变，色板并入[共用色板](../palette.json)。
