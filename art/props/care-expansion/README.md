# 小玩具

本包保存商店小玩具的游戏适配素材与小件照料用品的物品模型。制作源位于 `standards/yukkuri-library/drafts/care-expansion/build.py`，`recipe.json` 保存各件的风格；本包 `selected/`（四种玩具另在源目录 `assembled/`）保存玩具的可编辑网格、三角网格和贴图。

[清单](manifest.json)记录来源、输出指纹和本包语言键。静态模型无独立骨架或动作；游戏中的玩具运动由实际掉落物驱动。运行资源位于 `yukkuri_props/`、`textures/props/`、`models/item/`，配方位于 `data/mcyukkuri/recipe/`。

玩具有放置后的模型：玻璃弹珠、两种布偶与毛线球本来是圆的，按圆做（[toys.py](../../../standards/yukkuri-library/drafts/care-props/toys.py)，物品模型模板见[方块道具工具](../../../standards/yukkuri-library/drafts/block-props/kit.py)），造型以物品栏图标为准：毛线球 6.8 宽，缠线的行与三圈外层线，线头垂到地面盘在球的包围盒内；玻璃弹珠 4.4 宽的深蓝玻璃球，四个侧面各一道橙色扭纹；布偶是平底圆包子，按图标画脸，灵梦顶红蝴蝶结（高约 7.5），魔理沙戴宽檐女巫帽（高约 8.6）。清洗布、教导哨与同伴绳是手持小用品，不做放置模型：物品模型就是原版 `item/generated` 的图标（`textures/item/<id>.png`），手持、掉落与展示框都按原版方式显示。玩具在地面按真实尺寸、网格底面接地绘制；毛线球与玻璃弹珠按在地面走过的距离绕球心滚动，两种布偶保持直立。弹珠和毛线球会被推动并追近，其他物件以近距离探看和轻触为主；每次有效接触消耗体力，同一物品只能由一个玩耍过程占用。

真实掉落玩具按网格底面接地，不使用原版悬浮偏移；展示台中的物件采用固定展示。物品栏图标是 16×16 像素图，见[物品栏图标](../item-icons/README.md)。

原生核读入口：`E:/yukkuri/Eight景U-ModLab/wiki/game/native-operation-audit.md`、`wiki/game/environment-interactions.md`。只参考物件含义和行为关系，没有复制原生游戏像素。当前运行与视觉证据见[功能说明](../../../docs/FEATURES.md)。色阶取自物品栏图标（`item-icons/draw.py`）。
