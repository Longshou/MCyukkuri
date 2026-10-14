# 素材检查室

2026-10-14 追加：动作修正第 9 版新增 15 段[世界观动作](../../standards/yukkuri-library/motions/variants/lore/README.md)，在模型页的动作列表中可选（进食、しあわせー、饮水、睡眠、醒来、擦擦脸、唱歌、跟着摇摆、推挤、问候、发抖、低落、张望、甩水、帽船平衡），待用户复查。

2026-10-07 追加：模型页已拼接[头饰与头发位置适配](../../art/source/placement-fit/README.md)（待用户复查），与游戏读取同一 `yukkuri_presentation/placement_fit.json`；鼓腮第七版已按适配后的网格重新导出。检查意见的模型指纹包含位置适配资源，此前的意见不会被当作新版结论。

2026-10-07，第七版鼓腮修订，状态为待用户复查。当前入口：[常态与满腮五视图](../../art/source/motion-corrections/previews/current/review.png)、[19种形态特写](../../art/source/motion-corrections/previews/current/close-ups.png)、[19种形态斜侧面](../../art/source/motion-corrections/previews/current/all-models.png)、[连续动作](../../art/source/motion-corrections/previews/current/face-stability.gif)。

依据八景U的“ぷくー”画法，两颊各鼓成一个圆球，中间留出嘴部缺口；眼睛保持睁开，嘴换成标准鼓腮的“ハ”形噘嘴，腮红贴在鼓起的皮肤上。眼睛、眉毛、嘴和身体底部不动。灵梦与歪脸灵梦的侧发同步外摆，其余形态的头发与饰物固定，脸颊鼓到它们前方即停住。在模型页选择“鼓腮”动作，表情保持“随动作自动切换”即可查看；表情列表中的“鼓腮噗咕（睁眼噘嘴）”即本版表情。

[形变检查](../../art/source/motion-corrections/reports/current/checks.json)、[精确接触检查](../../art/source/motion-corrections/reports/current/face-contact.json)和[Java渲染一致性](../../art/source/motion-corrections/reports/current/java-parity.json)的范围、构建指纹与未覆盖项统一见[本轮验收](../../art/source/motion-corrections/reports/current/README.md)。这些离线结果不代表Minecraft实机或用户标准造型确认。

## 先前修订记录

以下预览与检查属于各自历史版本，不作为第七版的验收依据。

第六版历史入口：[归档说明](puff-v6/README.md)。第六版把下半脸横向摊开并让侧发随皮肤外推，侧发呈翅膀状，表情为猫嘴，已由第七版替代。

第五版历史入口：[常态／满腮对比](puff-v5/review.png)、[连续动作](puff-v5/face-stability.gif)、[19种形态](puff-v5/all-models.png)、[形变检查](puff-v5/checks.json)及[贴图遮挡检查](puff-v5/face-contact.json)。以下旧版记录不代表新版效果。

第五版采用威吓鼓腮，按用户要求突出“腮帮越大，威胁越强”的漫画表现。两侧脸颊明显膨大，经历充气、满腮保持和放气；眼睛、眉毛、嘴形保持稳定，身体根部及底部不整体缩放。新版将横向撑开和向前鼓起分开计算，保护眼部深度，并验证过渡阶段的体积不翻折。它是威吓动作的视觉修订，尚未新增群体威胁值或等级判定。

第四版历史：修复第三版眼睛随脸颊拉扯的问题，保持眼睛、眉毛、嘴形和饰物稳定，保护眼部下方皮肤，并保持腮红图案完整。当前入口为[多角度图](puff-v4/review.png)、[连续正面动作](puff-v4/face-stability.gif)；刷新页面后选择表情“随动作自动切换”。状态仍为待用户复查，下方旧版说明保留为缺陷修复历史。

鼓腮第二版修复了表情补丁误写身体顶点的问题，并改为两侧脸颊局部鼓起。根部不再整体缩放。刷新页面后使用默认“随动作自动切换”，查看[多角度对比](puff-v2/review.png)和[动图](puff-v2/puff.gif)。全形态及闭合表面回归检查由 `scripts/verify_puff_revision.cjs` 生成，当前状态仍为待用户复查。

2026-10-07 动作修订：检查室与 Minecraft 共用 `yukkuri_presentation/motion_corrections.json` 的八段修订动作。表情默认“随动作自动切换”，鼓腮及体操鼓腮段自动切换表情，仍可手动覆盖。[前后图](motion-fix/comparison.png)和[制作／检查说明](../../art/source/motion-corrections/README.md)供本轮复查，尚未代表用户确认。单动作GIF按原周期采样，`motions.gif` 仅为归一化相位对照，各列不是相同播放速度。

在项目根目录双击 `打开素材检查.cmd`，或运行 `scripts/start_review.ps1 -NoOpen` 后手动打开输出的地址。服务只监听 `127.0.0.1`；`关闭素材检查.cmd` 只关闭本入口记录的服务进程。不要直接以 `file://` 打开页面。

模型页按需读取 `src/main/resources/assets/mcyukkuri/yukkuri/` 的19种当前游戏形态及对应表情，支持旋转、线框、动作选择、慢放、时间轴和1/20秒步进。`pose.js` 延续现有圆润预览的数字骨架、混合权重与五官挂接算法。各角色按画面适配取景；灵胖仍应用1.5倍形态比例。渲染使用仓库已有 Three.js，无外部 CDN。

声音页读取当前游戏对白目录及声音注册，收录288条对白和2段生态哼唱，支持角色、年龄、场景和文字筛选、单条或连续试听。家油复用对应种族声音；两种捕食种没有这批独立配音。声音不会随模型动作自动触发。

检查意见保存在当前浏览器的 localStorage，并按源文件指纹区分；资源变更后旧意见仍保留在导出中，不会被视为新版的检查结论。点“导出检查意见”下载JSON，可发回聊天。记录不修改游戏资源，也不自动确认标准造型。

`scripts/build_review_catalog.py` 重新生成索引；`scripts/verify_review.cjs` 在命令行校验动作数学和素材路径，并离线生成缩略图。离线渲染与数据检查不代替浏览器实际交互或 Minecraft 实机验收。专题页链接指向原有预览及历史采集，各自版本范围保持原样。
