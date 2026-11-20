# 大型游具游戏接入

选用[大型游具制作源](../../../standards/yukkuri-library/drafts/playgrounds/README.md)的四种平滑造型模型和澡盆的橙汁液面网格。`selected/<ID>/` 保存当前 `.bbmodel`、三角网格和贴图图集；`manifest.json` 登记制作输入、选用快照和运行文件指纹。

| ID | 用途与实际过程 |
| --- | --- |
| `ceramic_tunnel` | 从入口走入、穿过内部并从另一端离开 |
| `yukkuri_slide` | 沿台阶登上平台，再顺斜板滑下 |
| `yukkuri_trampoline` | 走上床面、连续起跳 1～5 次（随自制力）、落地后离开；床面是 unit 2 独立部件，渲染时整体下压，床下静止锥面随之露出凹陷 |
| `yukkuri_pool` | 橙汁澡盆（ID 沿用以兼容存档）。泡澡玩法未接入前仍按原水池：水桶补充三份有限水，核心格是原版水；沿盆边探看，真正入水才湿身和消耗水清洗 |
| `yukkuri_pool_juice` | 澡盆液面，不单独放置：unit 1–4 各为一档，画 unit 1..k 即 k 档满 |

运行网格位于 `assets/mcyukkuri/yukkuri_props/<ID>.json`，贴图位于 `textures/props/<ID>.png`，碰撞数据位于 `data/mcyukkuri/playgrounds/<ID>.json`。没有独立角色骨架或预烘焙动画，`PlaygroundRenderer` 绘制设施，`PlayLife` 驱动真实实体位移。物品栏图标是 16×16 像素图，见[物品栏图标](../item-icons/README.md)。

`data/mcyukkuri/playgrounds/yukkuri_pool.json` 的 `tub` 给泡澡玩法用，单位 1/16 格、从设施根格地面量起、朝向同碰撞：`levels` 是 1–4 档液面高度，`floor_top`、`bench_top`（前壁座阶，沿 `bench_edge_z` 以前）、`rim_top`、`stool_top`／`stool`（盆外踏凳的高度与 x0 z0 x1 z1）；`seats` 是成体与小体型的座位（x、y、z），`soak` 是各档液面高出座位的深度。

放置前检查平整支撑和占用空间：滑梯需要 3×5×2 格，其他设施需要 3×3×2 格，朝向随玩家改变。各部分共享一个根设施；拆任一部分移除整套，生存模式只返还一件。澡盆拆除同时清掉其内部水。

同一游具每次只供一只使用。饥饿、受伤、危险、被玩家操作、设施拆除等会中断；卸载不推进路线，重载后恢复自由状态并保留冷却。运动消耗精力，澡盆使用保留普通入水风险。配方、尺寸、速度、弹跳次数与冷却均为 MC 适配参数。
