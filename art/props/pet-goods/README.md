# 宠物照料四件物品图标

糖粒 `sweet_candy`、布丁 `custard_pudding`、牵引胴带 `pet_harness`、襁褓布 `swaddle_cloth` 的 16×16 物品图标快照。

- 制作源：`standards/yukkuri-library/drafts/care-props/goods.py`；运行 `build.py` 重写本目录 PNG。
- 导出：`scripts/export_pet_goods_icons.py` 把 PNG 复制到 `src/main/resources/assets/mcyukkuri/textures/item/` 并写 `provenance.json`；`--check` 只读核对。
- 色：只取 `art/props/palette.json` 的材质阶；左上受光，接触边用本材质最暗一阶，不描黑边。
- 物品模型仍是普通 `item/generated`；嘴里份量由同一图标经物品渲染绘制。身上外观由 `client/CareWearRenderer` 程序化绘制，不属于本目录。
