# 照料补给物品图标

橙汁 `orange_juice`、小麦粉糊 `flour_paste`、廉价／普通／高级油粮 `oil_grain_budget`／`oil_grain_regular`／`oil_grain_premium` 的 16×16 物品图标快照。

- 制作源：`standards/yukkuri-library/drafts/care-props/supplies.py`；直接运行重写本目录 PNG，`--check` 只读核对。
- 导出：`scripts/export_supply_icons.py` 写入 `src/main/resources/assets/mcyukkuri/textures/item/<id>.png` 与 `models/item/<id>.json`（`item/generated`），并更新 `provenance.json`；`--check` 只读核对。
- 画法贴近原版物品：左上受光，每种材质一条色相偏移色阶，外轮廓取本材质最暗一阶，无黑边、无半透明与抗锯齿；同一图标用于物品栏、手持挤出、掉落物、展示框、商店陈列和 NPC 手持。
- 橙汁：带木塞的玻璃瓶装满橙汁，右下靠一片橙子横切片（果瓣纹不碰果皮），瓶身轮廓在切片处续接以免 1× 下粘连；喝完返还玻璃瓶。
- 小麦粉糊：薄木片上一团带尖、有一道黏稠褶的湿面粉糊，白色湿亮高光，糊里有麦麸点，下沿两处短垂滴；使用后不返还容器（项目设计：木片随糊用掉）。不用叶片承托，避免与树叶襁褓混淆。
- 油粮：同一款敞口纸袋，袋口堆出颗粒；廉价为素色牛皮纸与灰米色颗粒，普通为浅牛皮纸印薄荷带与金黄颗粒，高级为梅紫亮面袋贴金标与带高光的琥珀颗粒。食盆内颗粒仍由 `food_bowl_regular` 网格显示。
