# 金章考试时序排查

2026-09-13。修正了验收夹具提前判断考试结果的问题，生产考试规则保持原样。徽章专项两项 GameTest、51 条检查通过；最终完整联合回归 42 项 GameTest、2141 条报告检查全部通过，退出码 0，无缺失报告，见 [专项结果](badge-summary.json) 与 [联合结果](integration-summary.json)。

## 已确认的问题与修正

旧夹具在每场考试开始后固定等待 500 tick（25 秒），随后要求已获得徽章。但生产规则允许前四阶段各 100 tick，最后导航阶段最多 240 tick，总计最多 640 tick（32 秒）。因此，有效考试可能尚未结束就被旧夹具误报失败。

新回归场景施加持续 520 tick 的临时减速效果，保留真实 AI、寻路、碰撞、费用和成绩判断。第 500 tick 时，考生仍处于导航阶段、结果为未完成、保留银章；减速自然结束后，第 541 tick 获得金章，未超出原有时限。四项边界检查见 [badge-timing-checks.json](badge-timing-checks.json)，实际时间线见 [badge-slow-exam-trace.json](badge-slow-exam-trace.json)。

夹具现在等待考试真正结束，再核对通过状态、导航成绩和证书。中断、成绩不合格和超时仍会失败，没有放宽合格标准。日常进食、如厕和休息检查保留至少 1000 tick 的观察期。

另外，实体加载后的首个 tick 可能尚未落地，地面寻路此时不可用。新夹具先等待考生实际开始更新并落地，再报名；释放后的金章考生也执行同样检查。每五个游戏刻采样阶段、成绩、位置、寻路、视线及生活状态，并周期保存，便于定位后续偶发问题。

## 原始失败的证据边界

最初联合测试的金章失败保留在[前轮报告](../../play-readiness/current/README.md)。它只有最终布尔断言，未记录失败时的阶段或中断原因。本轮加入诊断后的两次原流程联合运行均未再次出现该金章失败；另一次连续 12 轮原流程考试共 564 条检查通过。因此，可以确认并修复上述可稳定复现的提前误判，但不能据此断言最初那次偶发失败的唯一原因。

诊断过程的其他失败也未抹去：第一次带记录的联合运行有一项语音实体加载等待超时；早期连续试验有一次食盆记录断言失败。它们与金章结果分别记录，完整日志保留于 `.local/gold-exam/`。最终结果使用修正后独立冻结版本，未以单独复测替代最终联合回归。

## 改动与产物

本轮代码改动仅涉及 `BadgeGameTests.java`、`run_badge_checks.py` 和 `run_port_integration.py`。新增慢速导航测试后，联合运行器要求 42 项测试及 `badge-timing-checks` 报告；徽章运行器同时核对两份报告，缺失即失败。相关入口文档已同步。

实际构建输出及运行 classpath 位于 `.local/gold-exam/build/`，见 [build.log](build.log) 与 [isolation.json](isolation.json)。最终冻结目录为 `.local/gold-exam/runtime-20260913-123032/`，专项目录为 `.local/gold-exam/runtime-20260913-122928/`。检查完成后源码与冻结指纹一致。

生产源码、模型和运行资源未修改，开发 Jar 的 SHA256 仍为 `30102b8cc134988fa3d74bfe64a007f8e3e5e9a642c76688014ce3e2abe03304`，见 [release.json](release.json)。已有试玩包与实验地图无需切换版本。未提交或推送；本轮自有 GameTest 进程均已退出。

复现构建使用 JDK 21：

```powershell
$env:JAVA_HOME='E:/yukkuri/.devtools/mcyukkuri/java/jdk-21.0.12.1+1'
./gradlew.bat -I .local/gold-exam/checks.init.gradle --project-cache-dir .local/gold-exam/gradle-cache --no-configuration-cache build createP0GameTestLaunchScript integrationIsolation
python .local/gold-exam/run_probe.py
```

联合运行复用 `scripts/run_port_integration.py`，将 `WORK`、`BUILD` 分别指向 `.local/gold-exam/` 和其 `build/`，先 `freeze()` 再 `launch('gametest')`；实际日志为 `.local/gold-exam/final-integration.log`。本轮没有重跑客户端、多人或长期生存，旧资源检查入口的适配问题仍按前轮记录保留。
