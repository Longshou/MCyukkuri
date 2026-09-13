# 帽子交互与两种体态修复验收

2026-09-13，本轮独立固定产物完成 1 个 GameTest、33 条机制断言，以及五种魔理沙实际客户端脱帽、戴回检查。普通、稀毛、歪脸和豆形分别完成有效幼油组合的移动、转向帽托与放下操作。家油只验证独立脱戴；其正常初生家幼油尺寸不满足帽檐支撑条件。

原始机制结果为 [game-checks.json](game-checks.json)，构建及 GameTest 日志为 [build.log](build.log)。实机结果为 [截图总览](minecraft-overview.png)、[14 组完整客户端状态记录](client-captures.json)、[稀毛脱帽实录](sparse-remove.gif)、[稀毛转向帽托实录](sparse-carry-side.gif)、[歪脸帽托实录](face-carry-side.gif)与[豆形帽托实录](bean-carry-side.gif)。这些图片来自实际 Minecraft 帧缓冲；离线交互预览单独位于 `previews/current/`。

机制检查覆盖真实乘客关系、跟随位置、转向、同实体存档序列化／反序列化、独立帽令牌、亚种模型标识、戴回消耗、五种帽根、尺寸拒绝、墙体拒载、放下、剪刀快捷入口和无安全落地点时的强制解绑定。无落地用例使用无重力测试实体，只证明不残留乘坐关系，不宣称已测试恢复重力。没有执行多人同步、服务重启和整个共享工作副本的全套回归。

独立造型审查先打回浮动顶板、稀毛根部和帽高，修订后通过；交互审查先打回姿态不一致、世界碰撞、无落点解绑定和剪刀旁路，修订后也通过。预览中曾误示豆形托普通幼油，最终改为实际可通过尺寸检查的同型幼油。完整过程见[造型审查](../../../../../standards/yukkuri-library/subspecies/deficient/rounded-marisa/reports/current/independent-review.md)与[交互审查](../../../../../standards/yukkuri-library/subspecies/deficient/rounded-marisa/reports/current/independent-code-review.md)。造型保持待用户确认。

实际执行的最终生产构建与机制命令如下。`JAVA_HOME` 仅设置在本轮进程中，指向本机已有 JDK 21。

```powershell
$env:JAVA_HOME='E:/yukkuri/.devtools/mcyukkuri/java/jdk-21.0.12.1+1'
.\gradlew.bat -I scripts/hat_checks.init.gradle --project-cache-dir .local/hat-interactions/gradle-cache --no-configuration-cache runP0GameTest hatIsolation jar createP0PreviewLaunchScript
```

`hatIsolation` 对所有 source set 的实际编译目录、资源目录及运行 classpath 检查根 `build/` 泄漏，结果见 [isolation.json](isolation.json)。成功构建立即复制为 `.local/hat-interactions/runtime-20260913-214608/`。第一次客户端检查后，仅修改 P0 预览相机与豆形测试幼油选择，再执行 `compileP0TestJava hatIsolation` 并冻结为 `.local/hat-interactions/runtime-20260913-215007/`；两份生产类、资源与 Jar 为同一版。

当前固定 Jar 为 `.local/hat-interactions/runtime-20260913-215007/libs/mcyukkuri-0.1.0-dev.jar`，SHA-256：

`09e33cf12d078b3aff761936c9b4366f8c611990f6e7083e5b3776367990fdad`

逐文件固定指纹和当前模型对应关系见 [verification.json](verification.json)。稀毛运行模型指纹为 `6f8ae3b2f47b04867d4946ab5aa0f8ad43d3bf092e15f500c3024a27ba874398`，歪脸为 `3326786808c32d1c5282090fb0e13de4f3005d34ef052131c564e83ba465d2a4`。两种模型在制作源、接入快照、运行资源、独立审查和本轮固定游戏包之间一致。

客户端通过本任务的 `.local/hat-interactions/launch_preview.py` 隐藏启动，使用从本轮 GameTest 世界复制的独立 `preview/saves/p0-preview/`。P0 `hat-setup`、`hat-remove`、`hat-return`、`hat-carry`、`hat-move`、`hat-put-down` 分别建立受控场景和调用实际操作；移动场景主动平移并转动父实体，验收渲染和乘客随动，不据此宣称自主寻路已测试。两次客户端 PID 3628、30584 均通过 P0 `stop` 正常退出，随后核实进程不存在。

模型与网页检查另包括：两种源／导出一致、256 像素图集、950 个有限动作时点、28 项运行资源检查、36 个表情下的 720 个浏览器姿态状态、20 形态总览引用，以及帽子交互页的 72 个状态。报告分别位于[两种体态包](../../../../../standards/yukkuri-library/subspecies/deficient/rounded-marisa/reports/current/checks.json)、[体态页面](../../../../../standards/yukkuri-library/subspecies/deficient/rounded-marisa/reports/current/browser.json)、[运行资源](resources.json)与[帽子页面](browser.json)。

变更范围包括两种不足魔理沙的制作配方、源与导出、发束接触及配套伤口／帽饰挂点；五种帽根的交互参数；乘客、取戴动作、剪刀入口、精确饰物模型标识、三语菜单；定向预览、测试和来源登记。保留其他任务已有的受伤、遗体接线及未提交改动，没有提交、推送或合并。

实验地图已接收为 `.local/experimental-map/runtime-hats-20260913-215007/`，正式运行清单已切换到该目录。256 个生产类、1483 个资源及 Jar 与本轮固定指纹一致，114 项启动路径有效；原存档的 146 个文件及地图 ZIP 指纹不变。地图任务未另启客户端，本记录的实机验收针对上文独立固定包。接收证据见[地图接收记录](../../../../../docs/validation/experimental-map/current/hat-runtime-update.json)。用户下次使用既有双击入口即可加载当前试用版。

独立复审后，帽子参数导出器仅补充来源登记中的正斜线路径、自身与运行文件指纹；生成的 `hat_interactions.json` 与已验固定包内容保持一致。当前导出器指纹以 `art/source/hat-interactions/recipe.json` 为准，审查报告中的旧脚本指纹作为当时证据保留。
