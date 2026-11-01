# 水生魔理沙的帽子

水生魔理沙帽的制作源（外观尖锥圆台阶，帽腔圆冠盆）：戴在头上；放下时帽檐朝上，像睡莲叶一样浮在水上当帽船（用户决定 2026-11-01：放下的帽子就是戴着的帽子翻过来，同样大小、圆形 1.0 倍，压低浮着，帽盆在水下但里面是干的，帽檐平铺在水面、没有翘边）。陆生魔理沙的帽子与接生帽不受影响，两种魔理沙不共用帽子模型、物品外观或几何。`export.py` 导出运行资源；游戏接入见[待办](../../../../docs/TODO.md)。

| 文件 | 用途 |
| --- | --- |
| `recipe.json` | 尺寸（1.4 倍）、帽檐、外侧圆台阶、内侧圆盆、放下时的固定水线与帽檐外缘（`set_down`）、储粮分档（`stores`） |
| `build.py` | 生成戴着与放下的网格、剖面，检查圆盆是否在外壳之内，写 `geometry.json` |
| `export.py` | 导出运行网格、储粮贴图与帽船边界，写 `art/source/aquatic-basin-hat/` 快照与指纹 |
| `geometry.json` | 帽檐、帽口、平底、深度、龙骨、水线与圆盆剖面 |
| `food_layer.png` | 复用既有 16×16 储粮贴图，按覆盖面积显示份数 |
| `scene.py` | 摆姿的身体、身体底面贴帽面的支撑、帽船网格、辫兜够距；姿态缓存在 `<MCY_LOCAL 或 .local>/cache/aquatic-marisa-hat/` |
| `layout.py` | 船长座位、帽盆容量、帽檐边子油、救援够距与贴帽动作检查 |
| `preview.py` | 当前预览 `<MCY_LOCAL 或 .local>/previews/aquatic-hat-seats/current.png`；`--clips` 出帽船动作关键帧 `previews/aquatic-clips/current.png` |

```sh
python standards/yukkuri-library/interactions/aquatic-marisa-hat/build.py [--check] [--set key.path=值 ...]
python standards/yukkuri-library/interactions/aquatic-marisa-hat/export.py [--check] [--set key.path=值 ...]
MCY_LOCAL=<主检出>/.local python standards/yukkuri-library/interactions/aquatic-marisa-hat/layout.py [--no-gates] [--set ...]
MCY_LOCAL=<主检出>/.local python standards/yukkuri-library/interactions/aquatic-marisa-hat/preview.py
```

依赖 NumPy 和 Pillow。`--set`（如 `--set set_down.waterline=0.3`）只在内存中改配方：`build.py`、`export.py` 打印结果、不写文件，`layout.py` 按改后的帽子重算；脚本里用 `build.override({...})`。身体取当前运行的魔理沙模型（按 placement_fit 拟合），姿态取导出动作（白天 `boat_drift`、划桨 `boat_paddle`）。帽子用魔理沙图集里陆生帽的同一批格子。脚本只读运行资源，不启动游戏。

## 造型

外侧和内侧是两个各自设计的形状，中间的布料厚度由两者之间的空间决定（项目设计）。

**戴着**

- 帽冠是四层圆台阶，沿用陆生帽逐层收窄的样子。
- 顶上直接用陆生帽自己的弯尖（`hat_tip_bend`、`hat_tip`、`hat_tip_end`），放在顶层台阶上的位置与陆生帽放在 `hat_taper_3` 上相同。
- 底层台阶有白带，蝴蝶结是陆生帽的那只，移到白带正前方。
- 帽檐是圆形，直径与陆生帽左右宽相同；外缘戴着时向下垂。

**放下（睡莲叶）**

- 与戴着同样大小、圆形；底层台阶保持直立围住帽盆，下面三层压成水下的台阶龙骨，弯尖压进龙骨，不单独画。
- 固定演出水线：帽檐面在水面上 `set_down.waterline`（0.36 帽子单位，0.5 模型单位）；帽子不按排水量浮沉。
- 帽檐从帽口到 `droop_from` 保持水平，外侧落到水面，外缘顶面在水线下 `edge_dip`，像睡莲叶贴着水；没有翘边。
- 帽盆在水下、里面是干的。

**帽腔**

- 帽口先沿帽檐厚度直下，再以四分之一椭圆弧弯进平底；中央压扁的帽尖留下浅窝。
- 圆盆必须在直立的底层台阶以内，至少留 `min_wall` 的布料，帽口不到帽檐开始下落处，水线在帽檐布料厚度以内；`build.py` 检查。

尺寸（成体模型单位，16 = 1 格；以 `geometry.json` 为准）：

| 部位 | 数值 |
| --- | --- |
| 帽檐半径（戴着、放下相同） | 14.18；11.48 以内水平 |
| 底层台阶半径 | 10.64 |
| 帽口半径 | 10.15 |
| 平底半径 | 8.26 |
| 平底深度 | 3.99（从帽檐面往下量；中央浅窝 4.2） |
| 龙骨底深度 | 6.55 |
| 水线 | 帽檐面下 0.5；帽檐外缘顶面 -0.57 |

## 座位、容量与够距（layout.py，项目设计）

载重规则见[水生魔理沙](../../../../docs/FEATURES.md#水生魔理沙)。身体按“身体底面贴帽面”支撑，两只身体只在身体本身（不含帽子、头发、辫子）的柱体重叠时算相撞。

- 船长座位：从中心往船尾挪，直到再挪就会离开平底，或头发（不含辫子）在船长左右转 15° 时碰到帽口；成体停在中心后 0.75，身底 -4.02，后发贴着船尾帽口。
- 帽盆：两只 0.15 或 0.20 赤油并排坐在她身前的平底上，身体顶部在帽口下 2.1 以上（0.20 赤油的帽尖高出帽口约 1.1）；运行容量见[功能说明](../../../../docs/FEATURES.md#水生魔理沙)。`layout.py` 的 `BASIN_YOUNG` 还限制静止时刘海与辫根绒团不碰帽口；仅测身体柱的极限不能作入座上限。
- 帽檐边：子油中心在帽檐外缘内 0.96（0.30 子油半身宽的一半），辫子一侧朝外；0.30、0.45 子油坐直时辫子差 0.24–0.28 碰不到水，向水面侧倾 10° 时辫尖可入水 0.16、0.32；嘴够不到水。
- 救援：船长在座位上原地转身，辫根到帽檐外 0.5 处水面最近 8.65（辫长 9.70 的 89%），越过横向帽檐 95%。

## 储粮贴图

`food_layer.png` 复用既有的 16×16 储粮贴图，不重新绘制。覆盖规则（项目设计）由 `recipe.json` 的 `stores` 导出到帽船边界：总份数为 0 时不绘制，1–2、3–5、6–8 份分别覆盖半径为平底半径 R 的 0.45、0.70、1.0 倍的圆面。每档半径比例为 f，圆心在 `(x=0, z=-(1-f)·R)`，与船首侧壁相切，小档在船长身前。贴图固定为每格方块 16 像素，UV 随帽船坐标平铺（+X 向右、+Z 向下），小圆只裁切同一贴图，不拉伸。

## 运行资源

| ID / 文件 | 内容 |
| --- | --- |
| `yukkuri_props/aquatic_basin_hat.json` | 戴着的帽子，也是物品外观：魔理沙模型坐标、帽子单位，与 `accessory_marisa` 同一坐标，按同一 `HatScale` 放大；部件 `crown`、`tip`、`bow` |
| `yukkuri_props/aquatic_basin_hat_turned.json` | 放下的帽船：坐标同 `marisa_hat_turned`（原点在帽檐面中心，+Y 出帽腔，+X 为戴着时的前方）；龙骨已压好，绘制时不再压扁；部件 `hull`、`bow` |
| `textures/props/aquatic_basin_hat_stores.png` | 储粮贴图（即 `food_layer.png`） |
| `yukkuri_presentation/hull_bounds/aquatic_basin_hat_turned.json` | 帽船边界，见下节 |

两个网格登记在 `yukkuri_props/subspecies-index.json`。水生帽的支撑、接触与储粮贴图读取帽船边界；陆地接生帽仍读取自己的 `yukkuri_presentation/hull_profiles/marisa_hat_turned.json`。

## 帽船边界（hull_bounds）

游戏放置与绘制帽船只读这些简单尺寸，不做排水、浮心或横倾计算。单位：格（帽子单位 × 倍率 ÷ 16）；帽船坐标，原点在帽檐面中心，+Y 出帽腔，+X 为戴着时的前方，船首朝 -Z；圆形，半径从中心量。

| 字段 | 含义 |
| --- | --- |
| `hull`、`scale` | 帽船网格 ID、绘制倍率 |
| `waterline_y` | 固定演出水线（帽船停在这里，帽盆是干的） |
| `mouth.radius` | 帽口半径 |
| `floor.radius`、`floor.y` | 平底半径与高度（中央另有浅窝） |
| `brim.inner_radius`、`level_radius`、`outer_radius`、`y`、`edge_y` | 帽檐从帽口（inner）到 level 保持在 `y`，再落到外缘（outer），外缘顶面高 `edge_y` |
| `keel_y` | 帽船最低点 |
| `captain_seat` | 成体船长（`growth` 1）坐在船尾、面朝船首时身底的位置 `x`、`y`、`z` |
| `brim_ring.radius`、`brim_ring.underside_y` | 帽檐边子油的中心半径（辫子一侧朝外），以及各体型 `[生长, 身底高度]` |
| `stores.texture`、`texture_px`、`tiers`、`radius_fractions`、`pixels_per_block`、`note` | 复用贴图与边长、各档份数 `[起, 止]`、半径比例、像素密度及船首相切的覆盖规则（见上节） |

## 采用时

造型、尺寸、边界与贴图都是项目设计，尚未接入游戏；试玩前都不算行为验收。
