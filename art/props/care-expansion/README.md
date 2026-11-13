# 小玩具

本包保存商店小玩具的游戏适配素材。制作源位于 `standards/yukkuri-library/drafts/care-expansion/build.py`，`recipe.json` 保存参数；源目录的 `assembled/` 与本包 `selected/` 同步保存可编辑网格、三角网格和原创色板。

制作脚本复用相邻器具包的几何基本函数。[清单](manifest.json)记录来源、输出指纹和本包语言键。静态模型无独立骨架或动作；游戏中的玩具运动由实际掉落物驱动。运行资源位于 `yukkuri_props/`、`textures/props/`、`models/item/`，配方位于 `data/mcyukkuri/recipe/`。

玻璃弹珠、两种布偶与漂亮石子使用独立原创网格。弹珠和毛线球会被推动并追近，其他物件以近距离探看和轻触为主；每次有效接触消耗体力，同一物品只能由一个玩耍过程占用。

真实掉落玩具按网格底面接地，不使用原版悬浮偏移；展示台中的物件采用固定展示。物品栏图标是 16×16 像素图，见[物品栏图标](../item-icons/README.md)。

原生核读入口：`E:/yukkuri/Eight景U-ModLab/wiki/game/native-operation-audit.md`、`wiki/game/environment-interactions.md`。只参考物件含义和行为关系，没有复制原生游戏像素。当前运行与视觉证据见[功能说明](../../../docs/FEATURES.md)。玩具色板见[共用色板](../palette.json)。
