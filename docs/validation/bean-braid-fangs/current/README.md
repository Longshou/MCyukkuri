# 豆油辫子、帽檐与捕食种尖牙实机复验

2026-09-13。本页对应另一模型任务释放的四套修订：豆油封闭双股辫与环绕发带、魔理沙帽子降低0.62模型单位，以及两捕食种高分辨率嘴部UV与白色三角尖牙。此前豆油动态五官跟随修正保留。造型仍待用户确认。

## 结果与固定产物

本轮构建成功；主程序类没有改动。资源检查28条记录通过，涵盖九套圆润选用来源及全部19运行形态的数值、动作、伤口和徽章指纹。四套发布模型的指纹逐项匹配交接值；Jar内全部主程序类、资源均与实际客户端快照一致，见[产物指纹](release.json)和[资源检查](assets.json)。

开发Jar：`.local/bean-braid-fangs/release/mcyukkuri-0.1.0-dev.jar`。SHA256：`fcbc0fb5fa7d8a9d52f3423304baa9fd3fb31894cfb311f39f3467ac90f93e61`。

实际客户端24组场景共采集230帧，选择并人工核对56个样本，见[实机汇总](index.html)、[逐项记录](visual.json)。

- 两豆油正面、侧面与斜后方的完整waddle循环，以及正面哭泣表情：辫子外侧持续可见，帽檐贴合发顶；未再观察到吞眼或半嘴。保留动作本身的眨眼和表情闭眼。
- 蕾米莉亚与弗兰各自的neutral、happy、biting表情，检查正面与斜前方：白色尖牙清晰，未见旧嘴部UV错采。
- 两豆油脱饰状态和固定架：主体、头发和新器具正常显示。夹具设置这些状态，不能据此宣称重新执行了完整脱落物拾取或操作交互验收。

图集对头部与器具区域裁切放大；原始帧位于 `frames/`。动作时间采用20Hz游戏tick和实际渲染插值，所选相位以记录为准。完整临时连续帧与控制日志保留在 `.local/personality-deibu/preview-dhsi3py5/`。客户端已正常退出。

## 配套同步

已重建六种不足油及家油／捕食种的伤口表面、对应操作器具和徽章挂点。`export_rounded_attachments.py` 现在另外派生本轮四只的真实帽饰，随同既有九套写入资源索引；豆油帽饰不再回退到普通种的旧网格。来源指纹记录在 `art/source/rounded-runtime/attachments.json`。

制作源与四套主模型由模型任务负责，本任务没有再改它们的几何和贴图。标准库[全形态展示](../../../../standards/yukkuri-library/body-standard/previews/current/all-models/index.html)与本页实际Minecraft截图职责不同。

复现顺序：`export_subspecies_wounds.cjs`、`export_wound_surfaces.cjs`、`export_rounded_attachments.py`、`export_badges.py --mounts-only`、标准库 `drafts/operation-tools/build.py`，然后隔离构建。主程序和预览类资源均位于 `.local/personality-deibu/build/`，使用 `personality_deibu_checks.init.gradle` 及独立项目缓存。静态检查命令为 `python scripts/verify_rounded_runtime.py --output docs/validation/bean-braid-fangs/current`；实际采集入口为 `scripts/capture_braid_fangs.py`。

本轮按改动范围做资源与实机复验，没有重跑全套无关GameTest、多人或长时生存；[上轮完整回归](../../personality-deibu/current/README.md)对应更早的固定资源版本，不能替代本轮新资源的验证。未提交、推送或发布。
