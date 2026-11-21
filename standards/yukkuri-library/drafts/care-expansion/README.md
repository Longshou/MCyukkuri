# 小玩具与小件照料用品

`build.py` 导出商店小玩具的道具物品和小件照料用品的物品模型，造型以各自的 16×16 物品栏图标为准：

- 圆形玩具（设计在[照料道具](../care-props/toys.py)，圆形工具见 `care-props/shapes.py`）：毛线球、玻璃弹珠、灵梦布偶、魔理沙布偶，放在地上、被推、滚动时都用这一模型。
- 原版物品模型（`item/generated`，贴图是 `textures/item/<id>.png` 图标）：教导哨、清洗布、同伴绳，不做放置模型。

导出同时更新本目录 `recipe.json`（各件的风格、四种玩具的合成材料）、四种玩具的 `assembled/`、`art/props/care-expansion/selected/` 和对应游戏资源，不单独修改生成网格。`--check` 只读检查导出一致性。几何使用每方块 16 单位，模型无独立骨架。运行行为与来源说明见[接入包](../../../../art/props/care-expansion/README.md)。
