# 小玩具

本包保存商店小玩具与小件照料用品的游戏适配素材。制作源位于 `standards/yukkuri-library/drafts/care-expansion/build.py`，`recipe.json` 保存各件的风格与尺寸；本包 `selected/`（四种玩具另在源目录 `assembled/`）保存可编辑网格、三角网格和贴图。

[清单](manifest.json)记录来源、输出指纹和本包语言键。静态模型无独立骨架或动作；游戏中的玩具运动由实际掉落物驱动。运行资源位于 `yukkuri_props/`、`textures/props/`、`models/item/`，配方位于 `data/mcyukkuri/recipe/`。

玻璃弹珠、两种布偶、漂亮石子与毛线球是方块风格（[toys.py](../../../standards/yukkuri-library/drafts/block-props/toys.py)，物品模型模板见[方块道具工具](../../../standards/yukkuri-library/drafts/block-props/kit.py)），造型以物品栏图标为准：毛线球是 7 格宽、三轴都削角的阶梯球，按图标画深粉斜向缠线，球下伸出一格粗的线头盘在地上，线头不超出球的包围盒；玻璃弹珠是 5 格宽的不透明阶梯球，深玻璃蓝，四个侧面都画橙色扭纹；漂亮石子 6×3×5，收脚的椭圆层上压一块偏心的顶，白色石英纹斜贯顶面；布偶是 6×5×5、各棱削一格的圆包子，正面按图标画 6×5 的脸，灵梦顶着红蝴蝶结（高 7），魔理沙戴宽檐白带女巫帽（高 8）。清洗布、教导哨与同伴绳是图标挤出的一像素厚薄片（教导哨立放，清洗布与同伴绳平放），贴图就是图标。玩具在地面按真实尺寸、网格底面接地绘制，不旋转；滚动由掉落物实体决定，与网格无关。弹珠和毛线球会被推动并追近，其他物件以近距离探看和轻触为主；每次有效接触消耗体力，同一物品只能由一个玩耍过程占用。

真实掉落玩具按网格底面接地，不使用原版悬浮偏移；展示台中的物件采用固定展示。物品栏图标是 16×16 像素图，见[物品栏图标](../item-icons/README.md)。

原生核读入口：`E:/yukkuri/Eight景U-ModLab/wiki/game/native-operation-audit.md`、`wiki/game/environment-interactions.md`。只参考物件含义和行为关系，没有复制原生游戏像素。当前运行与视觉证据见[功能说明](../../../docs/FEATURES.md)。色阶取自物品栏图标（`item-icons/draw.py`）。
