# 大型游具游戏接入

选用[大型游具制作源](../../../standards/yukkuri-library/drafts/playgrounds/README.md)的五种待确认方案。`selected/<ID>/` 保存本次 `.bbmodel`、三角网格和色板贴图；`manifest.json` 登记制作输入、选用快照和运行文件指纹。

| ID | 用途与实际过程 |
| --- | --- |
| `paper_tunnel`、`ceramic_tunnel` | 从入口走入、穿过内部并从另一端离开 |
| `yukkuri_slide` | 沿台阶登上平台，再顺斜板滑下 |
| `yukkuri_trampoline` | 走上垫面、连续起跳三次、落地后离开 |
| `yukkuri_pool` | 水桶补充三份有限水；沿侧踏台探看，真正入水才湿身和消耗水清洗 |

运行网格位于 `assets/mcyukkuri/yukkuri_props/<ID>.json`，贴图位于 `textures/props/<ID>.png`，碰撞数据位于 `data/mcyukkuri/playgrounds/<ID>.json`。没有独立角色骨架或预烘焙动画，`PlaygroundRenderer` 绘制设施，`PlayLife` 驱动真实实体位移。

放置前检查平整支撑和占用空间：滑梯需要 3×5×2 格，其他设施需要 3×3×2 格，朝向随玩家改变。各部分共享一个根设施；拆任一部分移除整套，生存模式只返还一件。水池拆除同时清掉其内部水。

同一游具每次只供一只使用。饥饿、受伤、危险、被玩家操作、设施拆除等会中断；卸载不推进路线，重载后恢复自由状态并保留冷却。运动消耗精力，水池使用保留普通入水风险。配方、尺寸、速度、三次弹跳与冷却均为 MC 适配参数。

本批引擎检查和当前画面见[验证清单](../../../docs/validation/port-remainder/playground-manifest.json)与[实机预览](previews/current/index.html)。用户标准造型确认、专服双客户端及多代生存长测尚未由本批检查覆盖。
