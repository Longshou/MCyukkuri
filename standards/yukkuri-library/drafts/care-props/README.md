# 照料道具统一造型

食盆、油粮袋、窝、残渣与便便、携带用品等照料道具的共用制作源。放置后的食盆与填充、宠物软垫窝、2×2 大窝和厕所是原版方块风体素道具（`voxel.py`、`vanilla_props.py`）：形体由 1/16 格立方单元拼成，不做倒角；每个面贴自绘 16×16 材质，1 texel／单位、按世界坐标连续，低对比成簇像素、上亮下暗的色相偏移色阶，不复制原版文件；占地、高度和入口沿用 `CareBlock` 碰撞。其余道具仍是低多边形软造型：形体贴 1/16 格网（圆截面 1/32），颜色只取自 [共用色板](../../../../art/props/palette.json)（每种材质 4 阶，暗到亮），贴图是 4×4 texel 色块加可选贴花区（1–2 texel／单位，无抗锯齿位图字）；不描黑边，接触处和凹缝用本材质最暗一阶；物品 ≤600 三角，满盆 ≤2500。

| 文件 | 内容 |
| --- | --- |
| `voxel.py` | 体素工具：单元格（材质、渲染单位）只输出外露面，并在不跨越 16 texel 重复的范围内合并为矩形；面法线取坐标轴，侧面可按朝向中心区分内壁材质；层高默认整单位，可给定层边界（食盆填充 1/4 单位层、带盖厕 1.5 底面）；可指定斜边区域，使台阶侧面取 45° 法线；高单位单元不遮挡低单位面，使逐份填充在任一份数下都无破洞 |
| `vanilla_props.py` | 原版方块风放置道具与自绘材质：食盆（14×14 八角平面、三格切角，黏土足、薄荷带、奶油釉沿，盆底 y 1）及油粮／小麦／短草填充（1/4 单位层格上的材质堆：第 1 份为中心一小片，第 4 份约占半个盆底，第 8 份铺满并略高出盆沿成穹顶；油粮为带高光点的颗粒材质并每份落三粒半格散粒，小麦为交错短麦秆带穗尖的材质，短草为碎草材质；小麦每份平放两枚原版小麦物品贴图、短草每份插一丛原版短草贴图，只引用不复制，贴图单面绘制、法线朝上）；宠物软垫窝（20 宽八角，草莓羊毛围沿两格宽、3 高、直段整齐，斜边台阶面取斜向法线以平整受光，奶油绗缝垫面 y 2，北侧入口降到垫面）；2×2 编织大窝与野生大窝（31 格圆角方形、一格厚围沿 3 高，内部 29 格，正面 \|x\|<7 开口落到窝底、到 11 为 2 高肩部；编织窝另有一格宽、1 高的内肩；野生窝为枯叶垫底加苔藓／干草斑块与少量空地，绿叶堆围沿与横放细枝）；沙厕 12×10（薄荷青塑料框 3 高、沙面 y 2，脏态沙面带湿结块并有一个阶梯便堆与一小团）；便堆 `toilet_waste`（3×3、2×2、尖顶的豆沙褐阶梯堆，`CareRenderer` 在陶厕与带盖厕底面按存量逐个以半尺寸绘制）；陶厕与带盖厕（与食盆同款八角，前侧开口，陶厕底面 y 1，带盖厕底面 y 1.5 与压在后半盆沿上的两层薄荷盖（与薄荷带同色），下层外伸一格） |
| `kit.py` | 网格工具：旋转体、放样、杆、凸块、托盘壁；平滑法线；贴图与 `.bbmodel` 导出；材质瓦片：每个色板阶可画成 16×16 噪点瓦片（相邻一阶的稀疏像素），按世界坐标 1 texel／单位对齐，网格连续、与原版同密度，`NOISE_DEFAULT` 控制 |
| `designs.py` | 储粮堆（野窝存粮，也供帽船粮堆复用） |
| `bags.py` | 油粮袋一条产品线：廉价牛皮纸袋、普通薄荷站立袋、高级藏青褶袋（平直热封边）、超辣红袋；标签在前后两面共用一份。超辣红袋是运行物品；前三袋网格只作周边附属物供离线预览，物品外观见 `supplies.py` |
| `wild.py` | 野外油库里自建的巢：叶床、个人小叶巢、叶束、捕食种枝巢（低多边形）；部件引用原版贴图（叶、草、苔藓、土、树皮、干草、羽毛）与原版叶色着色，不复制原版文件；按 1 texel／单位映射 |
| `small.py` | 食物残渣、便便、漏馅（12 个单位逐块显示）、食糜、清洗布、毛线球、携带篮 |
| `large_nests.py`、`preview_large_nests.py` | 2×2 家庭窝（项目设计）导出：`vanilla_props.py` 的 `large_yukkuri_nest` 与 `large_wild_nest`，占地 32×32、原点在四格公共角、入口朝 +z，可容两名成体与四只新生随意散卧。写入 `art/props/large-nests/` 快照及运行网格、粒子方块模型、物品模型、方块状态、掉落表与中英文名，并以 `owner` 条目加入共享 `yukkuri_props/index.json`；`--check` 只读。预览输出到 `.local/previews/birth-large-nests/`（`MCYUKKURI_PREVIEWS` 可改根目录） |
| `cargo.py`、`preview_cargo.py` | 按用户要求直接复用 `designs.py:forage_pile` 的现有储粮堆网格与原材质，帽腔中始终一整堆，仅按三档缩放；非紧急照料暂放侧檐时每侧至多一堆。各档与食物种类无关，服务端占位由缩放后的实际顶点测量生成。`build.py --hat-cargo` 只更新本素材和 `hat_cargo_layout.json`，随后运行周边素材导出。离线当前模型预览输出到 `.local/previews/hat-cargo/` |
| `goods.py` | 宠物照料四件物品（糖粒、布丁、牵引胴带、襁褓布）的 16×16 物品图标：前三件按色板阶绘制，襁褓布按 `supplies.py` 的原版物品画法；`build.py` 写入 `art/props/pet-goods/`，`scripts/export_pet_goods_icons.py` 导出到 `textures/item/`；身上外观由 `client/CareWearRenderer` 程序化绘制 |
| `wear.py` | 襁褓身上贴图：`swaddle_leaf_wear.png`（32×64：两片横卧整叶及其积便染色版，树叶襁褓的四片斜包叶与打开时的承托叶）与 `swaddle_wool_wear.png`（16×16 可平铺针织纹，布襁褓按顶点色着色）；直接运行写入 `art/props/pet-goods/`，`scripts/export_pet_goods_icons.py` 导出到 `textures/misc/`；`--check` 只读 |
| `preview_wear.py`、`preview_wear.cjs` | 本机离线身上预览（不入库、不进游戏）：`.cjs` 逐行移植 `CareWearRenderer` 的半径剖面与布杯、四片叠叶、打开时承托叶算法，用审阅页 `RuntimePose` 取运行动作姿态并软件光栅化；`.py` 准备贴图图集（顶点色着色的针织纹、积便染色叶片）、调用 node 并拼成 `swaddle/on-body.png`（`MCYUKKURI_PREVIEWS` 可改预览根目录） |
| `supplies.py` | 橙汁、面粉糊与廉价／普通／高级油粮五件物品的原版风格 16×16 图标：左上受光，每种材质用本文件内的色相偏移色阶，外轮廓取本材质最暗一阶，无黑边、无抗锯齿；直接运行写入 `art/props/supply-icons/`，`scripts/export_supply_icons.py` 导出贴图与 `item/generated` 物品模型 |
| `trays.py` | 三种垫料 |
| `render_preview.py`、`preview_nests*.py`、`preview_vanilla_care.py` | 本机离线预览（不入库、不进游戏）：软件光栅化网格，原版贴图引用从本机解出的原版资源（环境变量 `MCYUKKURI_VANILLA`）取用，成体／子体油库里以静止姿态对照尺度；`preview_vanilla_care.py` 另从本机原版客户端 jar（`MCYUKKURI_VANILLA_JAR`）读入炼药锅、堆肥桶、花盆方块模型并排对照，前后对比由 `MCYUKKURI_BEFORE` 指定旧资源树，输出到 `.local/previews/care-props/` |
| `build.py` | 写入 `art/props/runtime-peripheral/selected/` 的快照（含 `vanilla_props.py` 的食盆、填充、软垫窝与沙厕）；`--check` 只读 |

`build.py` 之后执行 `scripts/export_peripheral_assets.py`。陶厕、带盖厕（`vanilla_props.py`）、垫料、玩具色板由 `drafts/care-expansion/build.py` 导出；超辣油粮与食糜由 `drafts/consumables/build.py`；捕食种巢由 `art/props/predators/build.py`；纸箱与野外便所由 `ecology/wild-shelters/build.py`，叶床、结界等已确认件的输出指纹不变。

被替换的原三档袋“标签位图”改动脚本 `art/props/runtime-peripheral/shop_goods_v2.py` 不再处理油粮袋。厨余盆 `food_bowl_garbage` 无任何代码引用，已删除。旧导出的 `nest_lining` 不由游戏代码使用，但仍被动作预览 `preview/hub/actions.json` 引用，保留不动。
