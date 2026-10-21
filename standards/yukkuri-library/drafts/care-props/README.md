# 照料道具统一造型

食盆、油粮袋、窝、残渣与便便、携带用品等照料道具的共用制作源。统一规则：形体贴 1/16 格网（圆截面 1/32），低多边形软造型；颜色只取自 [共用色板](../../../../art/props/palette.json)（每种材质 4 阶，暗到亮），每个道具的贴图是 4×4 texel 色块加可选贴花区（1–2 texel／单位，无抗锯齿位图字）；不描黑边，接触处和凹缝用本材质最暗一阶；物品 ≤600 三角，满盆 ≤2500。

| 文件 | 内容 |
| --- | --- |
| `kit.py` | 网格工具：旋转体、放样、杆、凸块、托盘壁；平滑法线；贴图与 `.bbmodel` 导出；材质瓦片：每个色板阶可画成 16×16 噪点瓦片（相邻一阶的稀疏像素），按世界坐标 1 texel／单位对齐，网格连续、与原版同密度，`NOISE_DEFAULT` 控制 |
| `designs.py` | 食盆（奶油釉面陶瓷、薄荷带、深一级内釉）与盆内油粮／小麦／短草填充（油粮由 1–8 份逐份加高的颗粒团构成；小麦是原版小麦物品贴图的小堆、短草是原版短草贴图的十字草丛，均只引用不复制） |
| `bags.py` | 油粮袋一条产品线：廉价牛皮纸袋、普通薄荷站立袋、高级藏青褶袋（平直热封边）、超辣红袋；标签在前后两面共用一份 |
| `nests.py` | 人造垫：宠物软垫窝（圆形卷边软垫，内径 16.4、外径 24，按成体 12.8／最大 15.4 宽设计） |
| `wild.py` | 野外油库里自建的巢：叶床、个人小叶巢、叶束、捕食种枝巢；部件引用原版贴图（叶、草、苔藓、土、树皮、干草、羽毛）与原版叶色着色，不复制原版文件；按 1 texel／单位映射 |
| `small.py` | 食物残渣、便便、漏馅（12 个单位逐块显示）、食糜、清洗布、毛线球、携带篮 |
| `cargo.py`、`preview_cargo.py` | 按用户要求直接复用 `designs.py:forage_pile` 的现有储粮堆网格与原材质，帽腔中始终一整堆，仅按三档缩放；非紧急照料暂放侧檐时每侧至多一堆。各档与食物种类无关，服务端占位由缩放后的实际顶点测量生成。`build.py --hat-cargo` 只更新本素材和 `hat_cargo_layout.json`，随后运行周边素材导出。离线当前模型预览输出到 `.local/previews/hat-cargo/` |
| `goods.py` | 宠物照料四件物品（糖粒、布丁、牵引胴带、襁褓布）的 16×16 物品图标：按色板阶绘制，`build.py` 写入 `art/props/pet-goods/`，`scripts/export_pet_goods_icons.py` 导出到 `textures/item/`；身上外观由 `client/CareWearRenderer` 程序化绘制，颜色常量取同一色板 |
| `trays.py` | 陶厕、带盖厕与三种垫料 |
| `render_preview.py`、`preview_nests*.py` | 本机离线预览（不入库、不进游戏）：软件光栅化网格，原版贴图引用从本机解出的原版资源（环境变量 `MCYUKKURI_VANILLA`）取用，成体／子体灵梦以静止姿态摆在巢里对照尺度；输出到 `.local/previews/props-v2/` |
| `build.py` | 写入 `art/props/runtime-peripheral/selected/` 的快照；沙厕只做色板归并，脏态的小块换成同款豆沙色软旋堆；`--check` 只读 |

`build.py` 之后执行 `scripts/export_peripheral_assets.py`。陶厕、带盖厕、垫料、玩具色板由 `drafts/care-expansion/build.py` 导出；超辣油粮与食糜由 `drafts/consumables/build.py`；捕食种巢由 `art/props/predators/build.py`；纸箱与野外便所由 `ecology/wild-shelters/build.py`，叶床、结界等已确认件的输出指纹不变。

被替换的原三档袋“标签位图”改动脚本 `art/props/runtime-peripheral/shop_goods_v2.py` 不再处理油粮袋。厨余盆 `food_bowl_garbage` 无任何代码引用，已删除。
