# 照料道具统一造型

食盆、油粮袋、窝、残渣与便便、携带用品等照料道具的共用制作源。放置后的食盆与填充、宠物软垫窝、2×2 编织大窝和厕所是原版方块风体素道具（`voxel.py`、`vanilla_props.py`）：形体由 1/16 格立方单元拼成，不做倒角；每个面贴自绘 16×16 材质，1 texel／单位、按世界坐标连续，低对比成簇像素、上亮下暗的色相偏移色阶，不复制原版文件；占地、高度和入口沿用 `CareBlock` 碰撞。其余道具仍是低多边形软造型：形体贴 1/16 格网（圆截面 1/32），颜色只取自 [共用色板](../../../../art/props/palette.json)（每种材质 4 阶，暗到亮），贴图是 4×4 texel 色块加可选贴花区（1–2 texel／单位，无抗锯齿位图字）；不描黑边，接触处和凹缝用本材质最暗一阶；物品 ≤600 三角，满盆 ≤2500。

野生油库里就地收集、搭建的东西是另一族（`wild_kit.py`）：叶床、个人小叶巢、叶束、2×2 野生大窝与储粮堆（含帽船粮堆）共用一张手绘 128×128 图集。造型照原版营火、堆肥桶、干草块的做法，用少量较大的圆润低多边形件，不用 1/16 立方阶梯：窝沿是一束束扎起的干草交叠成圈（草茎顺着窝沿走，束端露出切口），配落叶垫、整片大叶、方截面细枝、外露草茎与苔藓团；储粮堆是叶托上的干草堆，上放草团、浆果、橡子、白花与毛虫。贴图照 `supplies.py` 的画法：左上受光，各材质用自己的色相偏移色阶，以本材质最暗一阶作轮廓，无黑边、无抗锯齿，不复制原版文件；1 texel／单位，沿路径或按世界坐标连续，镂空精灵只用 0／255 透明度。占地、高度、入口和碰撞沿用原值。

来源与采用范围（Wiki 摘录与图片，均未作原文逐字核验）：干草编的碗状窝、窝内垫绿叶、窝旁叶片上放毛虫、白花和草团见 EH19 第 5 页（[图](../../../../wiki/art-references/ehentai/images/EH19/005.jpg)）；成束干草围成的深碗状窝见 M04 第 1 页（[图](../../../../wiki/art-references/images/M04/001-9b35bd1c.png)）；寝处叠起宽于睡圈的干草床见 [N103](../../../../wiki/novel-reading/cards/N103.md)，秋季收集叶枝见 [N38](../../../../wiki/novel-reading/cards/N38.md)，树根、岩石与苔藓铺层见[栖息与季节生活](../../../../wiki/hakkei-reference/lore/habitat-seasonal-life.md)；储粮的虫团与干草见 [N71](../../../../wiki/novel-reading/cards/N71.md)，坚果与干草耐放留作越冬见[资源与生态](../../../../wiki/hakkei-reference/world/resources-ecology.md)（L06，作者私设），花、毛虫与落果见 N93、N38 与 EH35。Wiki 没有画出堆放的存粮；草束的数量与尺寸、细枝与苔藓的搭配、叶托、储粮堆四档堆形与食物组合、帽船复用满堆以及全部配色和贴图都是项目设计。

| 文件 | 内容 |
| --- | --- |
| `voxel.py` | 体素工具：单元格（材质、渲染单位）只输出外露面，并在不跨越 16 texel 重复的范围内合并为矩形；面法线取坐标轴，侧面可按朝向中心区分内壁材质；层高默认整单位，可给定层边界（食盆填充 1/4 单位层、带盖厕 1.5 底面）；可指定斜边区域，使台阶侧面取 45° 法线；高单位单元不遮挡低单位面，使逐份填充在任一份数下都无破洞 |
| `vanilla_props.py` | 原版方块风放置道具与自绘材质：食盆（14×14 八角平面、三格切角，黏土足、薄荷带、奶油釉沿，盆底 y 1）及油粮／小麦／短草填充（1/4 单位层格上的材质堆：第 1 份为中心一小片，第 4 份约占半个盆底，第 8 份铺满并略高出盆沿成穹顶；油粮为带高光点的颗粒材质并每份落三粒半格散粒，小麦为交错短麦秆带穗尖的材质，短草为碎草材质；小麦每份平放两枚原版小麦物品贴图、短草每份插一丛原版短草贴图，只引用不复制，贴图单面绘制、法线朝上）；宠物软垫窝（20 宽八角，草莓羊毛围沿两格宽、3 高、直段整齐，斜边台阶面取斜向法线以平整受光，奶油绗缝垫面 y 2，北侧入口降到垫面）；2×2 编织大窝（31 格圆角方形、一格厚围沿 3 高，内部 29 格，正面 \|x\|<7 开口落到窝底、到 11 为 2 高肩部，另有一格宽、1 高的内肩；同文件的 `large_wild_nest` 调用 `wild.py`）；沙厕 12×10（薄荷青塑料框 3 高、沙面 y 2，脏态沙面带湿结块并有一个阶梯便堆与一小团）；便堆 `toilet_waste`（3×3、2×2、尖顶的豆沙褐阶梯堆，`CareRenderer` 在陶厕与带盖厕底面按存量逐个以半尺寸绘制）；陶厕与带盖厕（与食盆同款八角，前侧开口，陶厕底面 y 1，带盖厕底面 y 1.5 与压在后半盆沿上的两层薄荷盖（与薄荷带同色），下层外伸一格） |
| `kit.py` | 网格工具：旋转体、放样、杆、凸块、托盘壁；平滑法线；贴图与 `.bbmodel` 导出；材质瓦片：每个色板阶可画成 16×16 噪点瓦片（相邻一阶的稀疏像素），按世界坐标 1 texel／单位对齐，网格连续、与原版同密度，`NOISE_DEFAULT` 控制 |
| `designs.py` | 储粮堆 `forage_pile`（野窝存粮，`wild_kit` 图集）：单位 0 为三片大叶叠成的叶托；单位 1–4 各是一档干草堆（半径 3.0／3.8／4.4／4.9、高 1.7／2.9／4.1／5.3，第 3、4 档另靠一小团）及其上的食物，后一档盖住前一档：草团、浆果、橡子、立着的白花、毛虫，满档另有一捆扎草；帽船粮堆复用单位 0 与 4 |
| `wild_kit.py` | 野生族共用件：手绘图集（编草、旧草、落叶垫、干草堆、苔藓、树皮、草团、毛虫、浆果、坚果、草束切口与木切口等可平铺材质，大叶、细叶与白花等镂空精灵），沿路径扫掠的草卷 `roll`、两端收细的扎草束 `bundle`、低多边形草堆 `dome` 与小球、方截面细枝 `stick`／草茎 `straw`、贴面与直立十字精灵、平铺小块 |
| `bags.py` | 油粮袋一条产品线：廉价牛皮纸袋、普通薄荷站立袋、高级藏青褶袋（平直热封边）、超辣红袋；标签在前后两面共用一份。超辣红袋是运行物品；前三袋网格只作周边附属物供离线预览，物品外观见 `supplies.py` |
| `wild.py` | 野外油库里自建的巢（`wild_kit` 图集）：叶床（单位 1 铺满整格的落叶垫，2–17 为可拼接的边、角与延续段，每段一两束草、略伸过段端以便相邻睡位叠成一圈；后 5、侧 4、前 2.4 高）、个人小叶巢（散叶 → 带缺口的低草圈 → 八束草编成的小窝，后高前低）、叶束（扎成小捆的干草）与 2×2 野生大窝（窝底 0.5 落叶垫，13 束草沿 `CareBlock` 野生碰撞的围沿走、最高 3，正面 \|x\|<7 开口、两肩 1.5／2.3）；捕食种枝巢仍引用原版贴图与叶色着色（不复制原版文件），由 `art/props/predators/build.py` 导出 |
| `small.py` | 食物残渣、便便、漏馅（12 个单位逐块显示）、食糜、清洗布、毛线球、携带篮 |
| `large_nests.py`、`preview_large_nests.py` | 2×2 家庭窝（项目设计）导出：`vanilla_props.py` 的 `large_yukkuri_nest` 与 `large_wild_nest`（后者调用 `wild.py`），占地 32×32、原点在四格公共角、入口朝 +z，可容两名成体与四只新生随意散卧。写入 `art/props/large-nests/` 快照及运行网格、粒子方块模型、物品模型、方块状态、掉落表与中英文名，并以 `owner` 条目加入共享 `yukkuri_props/index.json`；`--check` 只读。预览输出到 `.local/previews/birth-large-nests/`（`MCYUKKURI_PREVIEWS` 可改根目录） |
| `cargo.py`、`preview_cargo.py` | 按用户要求直接复用 `designs.py:forage_pile` 的满堆（单位 0 叶托与单位 4 满档草堆及食物）与同一图集，帽腔中始终一整堆，仅按三档缩放；非紧急照料暂放侧檐时每侧至多一堆。各档与食物种类无关，服务端占位由缩放后的实际顶点测量生成。`build.py --hat-cargo` 只更新本素材和 `hat_cargo_layout.json`，随后运行周边素材导出。离线当前模型预览输出到 `.local/previews/hat-cargo/` |
| `goods.py` | 宠物照料四件物品（糖粒、布丁、牵引胴带、襁褓布）的 16×16 物品图标：前三件按色板阶绘制，襁褓布按 `supplies.py` 的原版物品画法；`build.py` 写入 `art/props/pet-goods/`，`scripts/export_pet_goods_icons.py` 导出到 `textures/item/`；身上外观由 `client/CareWearRenderer` 程序化绘制 |
| `wear.py` | 襁褓身上贴图：`swaddle_leaf_wear.png`（32×64：两片横卧整叶及其积便染色版，树叶襁褓的四片斜包叶与打开时的承托叶）与 `swaddle_wool_wear.png`（16×16 可平铺针织纹，布襁褓按顶点色着色）；直接运行写入 `art/props/pet-goods/`，`scripts/export_pet_goods_icons.py` 导出到 `textures/misc/`；`--check` 只读 |
| `preview_wear.py`、`preview_wear.cjs` | 本机离线身上预览（不入库、不进游戏）：`.cjs` 逐行移植 `CareWearRenderer` 的半径剖面与布杯、四片叠叶、打开时承托叶算法，用审阅页 `RuntimePose` 取运行动作姿态并软件光栅化；`.py` 准备贴图图集（顶点色着色的针织纹、积便染色叶片）、调用 node 并拼成 `swaddle/on-body.png`（`MCYUKKURI_PREVIEWS` 可改预览根目录） |
| `supplies.py` | 橙汁、面粉糊与廉价／普通／高级油粮五件物品的原版风格 16×16 图标：左上受光，每种材质用本文件内的色相偏移色阶，外轮廓取本材质最暗一阶，无黑边、无抗锯齿；直接运行写入 `art/props/supply-icons/`，`scripts/export_supply_icons.py` 导出贴图与 `item/generated` 物品模型 |
| `trays.py` | 三种垫料 |
| `render_preview.py`、`preview_nests*.py`、`preview_vanilla_care.py` | 本机离线预览（不入库、不进游戏）：软件光栅化网格，原版贴图引用从本机解出的原版资源（环境变量 `MCYUKKURI_VANILLA`）取用，成体／子体油库里以静止姿态对照尺度；`preview_vanilla_care.py` 另从本机原版客户端 jar（`MCYUKKURI_VANILLA_JAR`）读入炼药锅、堆肥桶、花盆方块模型并排对照，前后对比由 `MCYUKKURI_BEFORE` 指定旧资源树，输出到 `.local/previews/care-props/` |
| `preview_wild_nests.py` | 野生族当前运行网格的离线预览（不入库）：叶床单睡位与 L 形相连、2×2 野生大窝、个人小叶巢各阶段与叶束、储粮堆各档与特写、帽船三档与侧檐，配成体／子体对照；帽船部分沿用 `preview_cargo.py` 的占位与帽面校验。输出 `.local/previews/wild-nests/`（`MCYUKKURI_PREVIEWS` 可改根目录） |
| `build.py` | 写入 `art/props/runtime-peripheral/selected/` 的快照（含 `vanilla_props.py` 的食盆、填充、软垫窝与沙厕）；`--check` 只读 |

`build.py` 之后执行 `scripts/export_peripheral_assets.py`。陶厕、带盖厕（`vanilla_props.py`）、垫料、玩具色板由 `drafts/care-expansion/build.py` 导出；超辣油粮与食糜由 `drafts/consumables/build.py`；捕食种巢由 `art/props/predators/build.py`；叶床、个人小叶巢、叶束（`wild.py`）及纸箱、野外便所由 `ecology/wild-shelters/build.py` 导出，结界等其余件的输出指纹不变；2×2 野生大窝由 `large_nests.py` 导出。

被替换的原三档袋“标签位图”改动脚本 `art/props/runtime-peripheral/shop_goods_v2.py` 不再处理油粮袋。厨余盆 `food_bowl_garbage` 无任何代码引用，已删除。旧导出的 `nest_lining` 不由游戏代码使用，但仍被动作预览 `preview/hub/actions.json` 引用，保留不动。
