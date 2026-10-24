# 宠物照料物品图标与襁褓身上贴图

糖粒 `sweet_candy`、布丁 `custard_pudding`、牵引胴带 `pet_harness` 与襁褓布 `swaddle_cloth` 八款的 16×16 物品图标快照，以及布襁褓和树叶襁褓身上外观所用的贴图。

- 制作源：图标为 `standards/yukkuri-library/drafts/care-props/goods.py`，运行 `build.py` 重写本目录 PNG；身上贴图为同目录 `wear.py`，直接运行重写 `swaddle_leaf_wear.png`、`swaddle_trim.png` 与 `swaddle/` 下的布料，`--check` 只读。
- 导出：`scripts/export_pet_goods_icons.py` 把图标复制到 `src/main/resources/assets/mcyukkuri/textures/item/`、身上贴图复制到 `textures/misc/`（布料在 `textures/misc/swaddle/`），并写 `provenance.json`；`--check` 只读核对。物品模型 `models/item/swaddle_cloth.json` 用 `mcyukkuri:swaddle_style` 覆盖按款式切换图标。
- 色：糖粒、布丁、胴带只取 `art/props/palette.json` 的材质阶；襁褓布与身上贴图按 `supplies.py` 的原版物品画法使用本文件内的色相偏移色阶。左上受光，轮廓用本材质最暗一阶，不描黑边、无抗锯齿与半透明。
- 襁褓布图标：同一个折叠布包——上层右上角向下翻出里衬，下沿一道边，下面露出折起的第二层；各款换布料、里衬与下沿并加本款标记：`swaddle_cloth.png` 奶油针织配粉里衬与草莓心形，`swaddle_cloth_frill` 粉布白蕾丝边红蝴蝶结，`_letter` 红布白蕾丝边白色 M，`_tiger` 橙底深褐虎纹配绒白边，`_outing` 浅蓝布深灰胶底、斜挎带与小包，`_fleece` 燕麦厚绒配两颗棕色绒球，`_gingham` 蓝白格藏青边与蝴蝶结，`_floral` 小碎花配浅绿边与蝴蝶结。款式来源见[宠物照料](../../../docs/PET_SHOP.md#4-家庭课程与日常照料)。
- `swaddle_leaf_wear.png`（32×64，cutout）：一片宽卵形叶，0–31 行干净、32–63 行为同一片叶的积便染色版（叶面混入暗黄褐、保留轮廓）。u 从叶柄（x 0–4，第 15–16 行两像素粗）到 x 31 的叶尖，v 横跨叶面、主脉在第 16 行；叶缘每 4 像素一枚向外凸出 1 像素的齿并有几处缺口，侧脉斜向叶尖。渲染器把叶柄端放在身后油菊处，叶片向下兜过底部，最宽处落在正面下部，叶尖包到前下方排泄口、停在嘴下，叶缘向两侧上卷；积便时最低处起换用染色版。树叶襁褓不是物品，由亲油用采下的树叶在世界中制作。
- `swaddle/<布料>.png`（16×16 可平铺，渲染时每模型单位 1 像素，与放置道具相同）：`knit` 横向细针织、`cotton` 细斜纹、`fleece` 绒毛为近白，由渲染器顶点色乘出各款颜色；`tiger` 虎纹、`gingham` 蓝白格、`floral` 小碎花自带颜色。
- `swaddle_trim.png`（64×64，cutout）：不平铺的装饰，各占整块贴到一个面上——蕾丝荷叶边（一个扇贝）、蝴蝶结、绒球（近白，按款着色），胶底、背带、小包、虎额纹（自带颜色），以及 5×7 的白色大写字母 A–Z（首字母款）。矩形位置在 `wear.py` 的 `TRIM`，渲染器常量与之对应。
- 身上外壳的几何由 `client/CareWearRenderer` 按身体剖面程序化生成。离线预览见 `preview_wear.py`。
