# 宠物照料四件物品图标与襁褓身上贴图

糖粒 `sweet_candy`、布丁 `custard_pudding`、牵引胴带 `pet_harness`、襁褓布 `swaddle_cloth` 的 16×16 物品图标快照，以及布襁褓和树叶襁褓身上外观所用的两张贴图。

- 制作源：图标为 `standards/yukkuri-library/drafts/care-props/goods.py`，运行 `build.py` 重写本目录 PNG；身上贴图为同目录 `wear.py`，直接运行重写 `swaddle_leaf_wear.png`、`swaddle_wool_wear.png`，`--check` 只读。
- 导出：`scripts/export_pet_goods_icons.py` 把图标复制到 `src/main/resources/assets/mcyukkuri/textures/item/`、身上贴图复制到 `textures/misc/`，并写 `provenance.json`；`--check` 只读核对。
- 色：糖粒、布丁、胴带只取 `art/props/palette.json` 的材质阶；襁褓布与身上贴图按 `supplies.py` 的原版物品画法使用本文件内的色相偏移色阶。左上受光，轮廓用本材质最暗一阶，不描黑边、无抗锯齿与半透明。
- 襁褓布图标：奶油色针织小包被，右上角向下折出粉色衬里，下沿粉色包边，下面露出折叠的第二层，左上一枚草莓色小心形。
- `swaddle_leaf_wear.png`（32×64，cutout）：0–15 行为叶 A、16–31 行为偏黄偏亮的叶 B，32–63 行是两片叶的积便染色版（叶面混入暗黄褐、保留轮廓），积便时最低几格换用；每格一片横卧整叶，叶柄在 x 0–2 的主脉行（格内第 8 行），叶尖在 x 31，格内第 0 行一侧是包层上缘、第 15 行靠地；叶缘每 3 像素一齿并有缺口，主脉最亮、侧脉斜向叶尖。树叶襁褓不是物品，由亲油用采下的树叶在世界中制作。
- `swaddle_wool_wear.png`（16×16，可平铺）：近白低对比针织 V 字纹（接近原版白色羊毛），由渲染器的顶点色乘出奶油布、叠襟暗部与粉色包边。
- 物品模型仍是普通 `item/generated`；嘴里份量由同一图标经物品渲染绘制。身上外壳的几何由 `client/CareWearRenderer` 程序化生成。
