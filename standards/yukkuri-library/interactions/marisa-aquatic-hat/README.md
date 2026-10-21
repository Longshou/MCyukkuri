# 水生魔理沙的帽子：更深更圆的帽船

水生魔理沙专用帽，状态为**待用户确认**。依据 [2026-10-19 设计裁决](../../../../docs/DESIGN_DECISIONS.md#2026-10-19-水生魔理沙与饰品)：帽腔更深更圆、像船身，与普通魔理沙的帽子是两种物品。普通帽与陆地接生的帽子仍由 [marisa-hat-vessel](../marisa-hat-vessel/README.md) 制作，本包只读取它，不改写。参考 EH22-001、EH22-007：圆帽檐浮在水面，深色圆碗挂在水下，底部有一个小尖（见 [EH22](../../../../wiki/art-references/ehentai/works/EH22.md)）。

## 一份几何，四种用途

`build.py` 按 `recipe.json` 的轮廓在“翻过来”的帽船坐标里做出一套三角面，原样写出两份：

| 资源 | 坐标 | 用途 |
| --- | --- | --- |
| `yukkuri_props/accessory_marisa_aquatic.json` | 戴着的朝向，`marisa.json` 模型单位，与 `accessory_marisa` 和模型自带的 `brim_`/`hat_`/`bow_` 部件同一坐标 | 戴在头上、摘下的物品（`SourceModel` = `marisa_aquatic`）、帽子破损和拼合 |
| `yukkuri_props/marisa_aquatic_hat_turned.json` | 帽船坐标，与 `marisa_hat_turned` 相同的翻转 | 帽船、靠岸接生的浮帽、自救时甩下的帽子 |

两份顶点满足 `帽船 = (-(w.z-a.z), -(w.y-a.y), -(w.x-a.x))`，`a` 为 `anchor_worn`（与普通帽相同），UV 和部件一致，由 `check.py` 逐点核对。碎片沿用所有饰品共用的 `accessory_shard`，不另做模型。

## 造型

- **帽檐**：圆角超椭圆，半径 7.9 × 10.6（普通帽十字形帽檐的外接为 7.21 × 10.13），厚 0.8。比普通帽略大，水生帽辫子抓帽檐的两个点仍落在帽檐上。
- **帽口**：半径 4.8 × 6.6 的圆角口，面积与普通帽的 8.74 × 12.02 矩形相当。
- **帽腔**：前部浅台阶（深 1.1，直边在 z = −5.5）、船主坐的船尾台阶（深 2.2484，与普通帽相同，水上船主座高 `owner_y` 不变），往下六层圆形台阶收到深 9.9 的底（普通帽 7.03）。台阶面水平、立面垂直，服务端读的分段就是画出来的台阶。
- **帽冠外壳**：帽檐下直落到帽带，再以椭圆收拢成圆船底，各处壁厚不小于 0.55。底部伸出一段弯尖，戴着时弯向 +x，与普通帽的尖同向；浮在水上时它顺着船底横躺，不会往深处挂。
- **帽带、蝴蝶结**：帽带在普通帽同样的高度。蝴蝶结沿用普通帽自己的方块、三角面和 UV，按每个顶点的 z，把它沿 x 推到这条圆帽带实际画出的折面前方，推出的距离等于该处新帽带比普通帽平直帽带多出的部分（沿 z 的剪切）。这样在普通帽上互相贴住的方块仍然贴住，翼片仍嵌入帽带 0.043，飘带仍离帽带至少 0.028（以帽带上每个折点计）。
  - 2026-10-21 修正：先前逐块旋转摆放，使外翼和内翼之间裂开 0.049 的缝（右侧 0.003），翼片又比普通帽多浮出帽带 0.05。从侧前方看，这些缝里会露出黑色帽身，形成“锯齿黑洞”。纹理不是原因：采样点内部全不透明。
- **贴图**：帽布、深色衬里和帽带取魔理沙图集三个格子的右下四分之一（整格左上有一道高光，铺在每个小面上会变成点阵）；蝴蝶结沿用普通帽原来的整格 UV。所有部件都指向 `textures/entity/marisa/neutral.png`。
- **面的朝向**：每个三角形都按所记法线逆时针缠绕。帽子破损的渲染和徽章导出都按缠绕方向判断外侧。

## 帽船数据

`yukkuri_presentation/marisa_aquatic_hat_vessel.json` 沿用 `hat_vessel.json` 的字段，同时新增精确的多边形：

- `cavity_sections[].outline`：每段帽腔是凸多边形（从 +x 往 +z 逆时针），这才是权威数据；`rect` 只是外接框，用作搜索范围，不能直接当矩形段使用。
- `cavity_sections[].strips`：位于该段多边形内部、以 0.25 误差覆盖它的矩形条；条带以外都按实心壁处理，供只能用方盒做墙的代码（如 `HatCareGeometry`）偏保守地使用。
- `brim_outline`：精确帽檐。`brim_rects` 是 41 条位于圆帽檐内部的矩形，以 0.5 的误差覆盖帽檐，只读矩形的代码可以安全使用。
- `bowl.mask_outline`：水面遮罩的真实帽口；`bowl.mask` 是四点外接框。
- 压扁 0.75、干舷 1.2、水生倍率 1.5、`anchor_worn` 都与普通帽相同。`keel` 为 14.4485（含帽尖），`squash.nest` 只为补齐字段。
- `placements.aquatic` 用 marisa-hat-vessel 的同一套站位搜索在这顶帽子上算出：帽子离岸多出 1.03 个模型单位；帮手抓帽檐的点从 (9.5, −3.4) 移到 (9.5, −3.9)，自己放帽时抓的点不变。
- `worn`：替换哪些部件、跟随哪根骨（`hat`，`bow_` 部件跟 `bow`）、运行时位置适配的偏移（魔理沙为 (0, −1.5, 0)）。`badge_mount` 是徽章在帽带上的位置和法线。

## 与其他包的同步

以下数据都从本包导出的帽子取得，不另抄坐标：

| 内容 | 制作源与导出 | 运行资源 |
| --- | --- | --- |
| 徽章挂点 | `art/props/badges/mounts.json` 的 `detached.accessory_marisa_aquatic`，取自本包的 `badge_mount`（`check.py` 核对两者一致）；用 `python scripts/export_badges.py --mounts-only --detached accessory_marisa_aquatic` 只导出这一项 | `yukkuri/badge_mounts.json` |
| 救援帽高 | `interactions/braid-water-rescue/author.py` 分别读取两顶帽子：`hat_heights_model_units`（land 10.9021、aquatic 14.4485），`hat_brim_half_model_units` 同理；旧键 `hat_height_model_units` 仍为普通帽的值 | `yukkuri_presentation/braid_rescue.json` |
| 放帽、戴帽、扶帽和整理帽子动作 | `motions/variants/lore/hat.py` 的 aquatic 变体读本包的数据，把帽子按实际画出的位置落座（位置适配后的帽檐，绕 `anchor_worn` 放大）；`lore/author.py --export-aquatic-hat` 只导出 `AQUATIC_HAT_CLIPS` 六个片段，并刷新稀有形态的复制件 | `yukkuri_presentation/motion_corrections.json`、`art/source/motion-corrections/manifest.json` |
| 物品名 | 本包 `build.py` 的 `LABELS`：`item.mcyukkuri.marisa_aquatic_hat`，中英日三语 | `lang/zh_cn.json`、`en_us.json`、`ja_jp.json` |

`check_motion.py` 按游戏的骨骼合成计算前向运动学：摘帽片段的末帧和戴帽片段的首帧，水生帽与帽子实体的偏差为 0.0005 模型单位；扶帽片段中辫梢与所扶帽檐点重合。普通帽片段按原样保留，同一检查测得的交接偏差为 0.44，只作报告。

帽内储粮复用 `drafts/care-props/cargo.py` 的原粮堆：帽内三档堆放在新的深底中央，整体缩小到每个顶点都不穿过台阶（满载半径从 3.38 缩到 2.64）；侧檐暂存挪到更宽的帽檐上，避开更圆的帽口。输出为 `yukkuri_props/marisa_aquatic_hat_provisions.json`（共用 `textures/props/hat_provisions.png`，构建时核对贴图一致）和 `yukkuri_presentation/marisa_aquatic_hat_cargo_layout.json`。物品模型为 `models/item/marisa_aquatic_hat.json`。三个道具登记在 `subspecies-index.json`；这会改变 marisa-hat-vessel 清单中索引的哈希，已重跑该包刷新，它的道具与数据逐字节不变。

## 检查

`check.py` 只读检查，不编译：

1. 戴着和翻过来的两份是同一组三角面。
2. 在帽口内两种模式各打约 1100 条竖直射线，命中的台阶高度与多边形分段一致。
3. 分段为凸多边形、逐层嵌套、逐层加深；帽冠最薄处 0.55。
4. 两个辫子抓点在帽檐上、在帽冠外；徽章在帽带上。
5. 储粮每个顶点都在它所在高度的帽腔内；侧檐粮堆完整落在帽檐上、帽口外。
6. 按 `HatVessel.place` 的规则（中心在真实台阶上、整个碰撞方块不碰到该高度的立面、互不重叠）排幼体，并扣掉船主整个圆形身体（比游戏更严）。赤油 0.14、子油 0.20 和 0.25 分别能坐 26、7、4 只，不少于 12 份规则允许的 6、3、3 只。不扣船主时的数量与普通帽船一并打印：大子油的座位比普通帽船少，因为深处更窄。
7. 普通帽蝴蝶结上 12 对互相贴住或重叠的方块，在新帽上仍然贴住或重叠（分离轴检验）；蝴蝶结没有任何面与帽带的面共面重叠。
8. 两份道具的每个部件都指向存在的贴图，UV 内部只采到不透明像素；每个三角形都按所记法线缠绕；徽章制作源与本包一致，导出的挂点落在帽带正面（离设定点 0.05 以内）。

`check_motion.py` 只读核对动作交接，见上节。

## 重建

需要 numpy 和 Pillow，不启动游戏或浏览器：

1. `python build.py`（`--check` 只读核对；同时写入两个物品名）
2. `python scripts/export_badges.py --mounts-only --detached accessory_marisa_aquatic`（帽子几何或 `badge_mount` 变化时）
3. `python -B standards/yukkuri-library/interactions/braid-water-rescue/author.py`
4. `python -B standards/yukkuri-library/motions/variants/lore/author.py --export-aquatic-hat`（加 `--trial <目录>` 只写到该目录）
5. `python check.py`、`python check_motion.py`
6. `python preview.py`：生成 `.local/previews/aquatic-hat/aquatic-hat.png`，内容为戴着的正面与侧面、蝴蝶结近看、水上帽船、纵横剖面和俯视分段，均与普通帽对比，不入库。

普通魔理沙模型（`yukkuri/marisa.json`）、marisa-hat-vessel 的站位规则或 care-props 的粮堆改动后，都要按以上顺序重跑。
