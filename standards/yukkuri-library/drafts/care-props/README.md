# 照料道具统一造型（R4 候选，待用户确认）

食盆、油粮袋、窝、残渣与便便、携带用品等照料道具的共用制作源。统一规则：形体贴 1/16 格网（圆截面 1/32），低多边形软造型；颜色只取自 [共用色板](../../../../art/props/palette.json)（每种材质 4 阶，暗到亮），每个道具的贴图是 4×4 texel 色块加可选贴花区（1–2 texel／单位，无抗锯齿位图字）；不描黑边，接触处和凹缝用本材质最暗一阶；物品 ≤600 三角，满盆 ≤2500。

| 文件 | 内容 |
| --- | --- |
| `kit.py` | 网格工具：旋转体、放样、杆、凸块、托盘壁；平滑法线；贴图与 `.bbmodel` 导出 |
| `designs.py` | 食盆（奶油釉面陶瓷、薄荷带、深一级内釉）与盆内油粮／小麦／短草填充（油粮由 1–8 份逐份加高的颗粒团构成；麦与草仍引用原版贴图，不复制） |
| `bags.py` | 油粮袋一条产品线：廉价牛皮纸袋、普通薄荷站立袋、高级藏青褶袋（平直热封边）、超辣红袋；标签在前后两面共用一份 |
| `nests.py` | 宠物软垫窝（圆形卷边软垫）、捕食种编织枝巢 |
| `small.py` | 食物残渣、便便、漏馅（12 个单位逐块显示）、食糜、清洗布、毛线球、携带篮 |
| `goods.py` | 宠物照料四件物品（糖粒、布丁、牵引胴带、襁褓布）的 16×16 物品图标：按色板阶绘制，`build.py` 写入 `art/props/pet-goods/`，`scripts/export_pet_goods_icons.py` 导出到 `textures/item/`；身上外观由 `client/CareWearRenderer` 程序化绘制，颜色常量取同一色板 |
| `trays.py` | 陶厕、带盖厕与三种垫料 |
| `build.py` | 写入 `art/props/runtime-peripheral/selected/` 的快照；沙厕只做色板归并，脏态的小块换成同款豆沙色软旋堆；`--check` 只读 |

`build.py` 之后执行 `scripts/export_peripheral_assets.py`。陶厕、带盖厕、垫料、玩具色板由 `drafts/care-expansion/build.py` 导出；超辣油粮与食糜由 `drafts/consumables/build.py`；捕食种巢由 `art/props/predators/build.py`；纸箱与野外便所由 `ecology/wild-shelters/build.py`，叶床、结界等已确认件的输出指纹不变。

被替换的原三档袋“标签位图”改动脚本 `art/props/runtime-peripheral/shop_goods_v2.py` 不再处理油粮袋。厨余盆 `food_bowl_garbage` 无任何代码引用，已删除。
