# 捕食种本轮验收

2026-09-13。范围为蕾米莉亚、弗兰的模型、飞行捕食、枝巢、徽章控制及商店销售。[实现说明](../../PREDATOR_IMPLEMENTATION.md) · [模型和帽饰实机总览](overview.png) · [交互动作预览](../../../art/source/predator-runtime/preview.html)。造型待用户确认。

## 检查结果

| 检查 | 结果与范围 |
| --- | --- |
| 独立构建 | JDK 21，`build createP0GameTestLaunchScript createP0PreviewLaunchScript` 成功；使用独立构建目录和 Gradle 项目缓存，实际类输出已核对 |
| 捕食种真实引擎 | [27/27](predator-checks.json)：空中寻路、越障、实际咬击、猎物逃跑、体力消耗、拾取并吞咽真实份量、高台结巢、恢复重力、所有权及教育约束、活体翻页和原身份购买 |
| 商店真实引擎 | [37/37](human-world-checks.json)：六只模板存货绑定、购买和运输箱身份、重复交易、服务距离与营业规则 |
| 工厂回归 | [63/63](human-expansion-checks.json)，对应本任务冻结构建；不替代工厂专题的其他验收 |
| 资源和伤口 | 九种模型、来源指纹、表情和动作合同一致；[伤口共点绑定与数值检查](wound-check.json)通过 |
| 离线视觉 | 两种实际导出模型各八视图，检查正侧背面、两个振翅相位、俯冲、咬取；已修复后发缝隙、侧结埋入帽冠及翼根遮挡 |
| 实际客户端 | 两种模型四方向、帽饰与徽章、命令菜单、商店栏位和两页入口、飞行与收翼均已采集；没有用离线图片冒充实机 |

捕食种测试快照为 `.local/predators/predators/1789247223/`，商店测试快照为 `.local/predators/humans/1789246611/`，客户端快照为 `.local/predators/preview-runtime-1789247315/`。本任务客户端 48916 已通过 P0 停止指令退出，未结束其他任务进程。源模型、运行模型与证据指纹见 [validation.json](validation.json)，完整类和资源快照指纹见对应 `runtime-manifest.json` 文件。本记录只证明这些冻结副本，不证明之后其他任务仍在修改的共享检出目录整体通过。

## 可核对的实机画面

[蕾米莉亚](images/remilia-mount-oblique.png)、[弗兰](images/flandre-mount-oblique.png)使用完整当前模型和表情；[银章软帽](images/remilia-bonnet-badge-visible.png)、[金章软帽](images/flandre-bonnet-badge-visible.png)从实际有牌的一侧查看，完整圆牌贴在帽冠上。

[蕾米莉亚短途飞行](images/remilia-follow-flight.gif)与[弗兰起飞／收翼](images/flandre-follow-flight.gif)是原始客户端帧序列缩放后的动图。后者是一次短促的起飞，不能单凭该片证明长期巡航；连续飞行、体力与咬击以引擎测试为证。`capture-remilia-live-flight.json` 另记录了真实客户端的飞行、俯冲、咬取、降落状态，以及受伤后逃跑的猎物。

[商店新栏位](images/shop-predator-pens.png)和[活体菜单](images/shop-sale-page.png)显示当前模板和两页入口。截图在第一页；第二页两种捕食种的报价、实际购买、扣款和 UUID 保留由引擎测试直接断言。[捕食命令菜单](images/remilia-commands.png)中的银章示例教育尚未完成，因此控制按钮禁用，符合实际资格检查。

## 实际修复及尚未覆盖的项目

后续联合测试发现捕食种固定器具缺失，已补齐源与导出，并完成编译、九种器具覆盖检查及离线三视图。新资源指纹和证据单独记录在[器具适配补验](operations-current/README.md)；上表的原冻结快照不包含这项后续修复。

首轮追猎停在格中心、无法接近到咬击范围。补充可见目标的近距离精确追踪后，实际越障、咬击和逃跑测试通过。新增拾食断言曾把吞咽后的饱食度限定为大于 29，忽略 400 tick 内的一次自然饥饿下降；修正验收条件后，实际结果为份量已消耗、吞咽结束、饱食度由 25 增为 29。结巢验证使用有足够站位的三格宽高台，不把无落脚点的单根柱顶视为有效巢址。

未执行自然群落长期数量压力测试和捕食种专属双人联机轮次；自然生成规则已接入数据文件。标准造型还需用户确认，既有七种造型的确认记录没有被改写为本轮捕食种确认。
