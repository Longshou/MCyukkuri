# 成长时钟与旧档迁移验收

2026-09-13。当前生产源码与测试时复制的固定快照一致，文件指纹见[manifest.json](manifest.json)。本轮修改限于成长配置、家庭个体与未出生子代时钟、工厂育幼年龄依赖和相关夹具；灵胖形成与新性格系统未作为游戏逻辑接入。

Windows、本机 JDK 21、Minecraft 1.21.1、NeoForge 21.1.250。命令从项目根目录执行，先将 `JAVA_HOME` 指向已有 JDK 21：

```powershell
./gradlew.bat -I scripts/growth_checks.init.gradle --project-cache-dir .local/deibu-life-design/gradle-cache --no-configuration-cache build createP0GameTestLaunchScript verifyGrowthOutputs
python scripts/run_growth_checks.py
```

构建成功。实际确认 `main`、`p0Test`、`test` 的类输出和资源输出均在 `.local/deibu-life-design/build/`，并核对编译任务与 source set 输出一致。运行前立即复制类、资源和启动参数，替换运行 classpath 与模组目录到固定快照；来源和运行文件指纹随快照保存。测试进程隐藏运行，退出码0，未启动客户端或普通专服。

| 范围 | 检查数 | 结果 |
| --- | ---: | --- |
| 新成长／寿命边界与旧档迁移 | 28 | 通过，[growth-checks.json](growth-checks.json) |
| 既有家庭、实际睡醒成长、出生、收纳与亲缘 | 141 | 通过，[family-checks.json](family-checks.json) |
| 消耗品规则 | 36 | 通过，[consumable-rule-checks.json](consumable-rule-checks.json) |
| 实际消耗品行为 | 4 | 通过，[consumable-live-checks.json](consumable-live-checks.json) |
| 辅助胎生规则 | 42 | 通过，[consumable-birth-checks.json](consumable-birth-checks.json) |
| 实际引擎 tick 胎生 | 5 | 通过，[consumable-live-birth-checks.json](consumable-live-birth-checks.json) |
| 固定先天形态与出生碰撞 | 14 | 通过，[consumable-morph-checks.json](consumable-morph-checks.json) |

合计7项 GameTest、270条检查全部通过，原始汇总见[summary.json](summary.json)。新版覆盖40分钟成熟、30小时成年时段、营养不足拒绝成熟、长足体型时钟、NoAI不计龄、新老年／寿终边界、版本1幼体与成体迁移、重复加载幂等、未出生子代迁移与实际 newborn 参数接收。

首轮269／270通过；唯一失败是旧夹具把固定年龄70,000当作老年。在新寿命下该年龄仍年轻，故改为使用当前个体寿命终点前一 tick，并以相同固定产物重新跑完整专题；第二轮270／270。首轮日志保留在本任务临时目录，未改写成通过结果。

未连续挂机30小时；寿终边界通过设置测试年龄后调用真实家庭 tick 检查。没有执行新版多人、客户端展示或用户世界升级，不把旧版这些验收自动算作本轮通过。寿命延长后的群落人口、繁殖节奏与长期生存仍待游玩平衡。

灵胖美术的独立检查见[当前素材候选](../../../../standards/yukkuri-library/body-standard/features/deibu-bow/candidate.json)，不属于本轮 Minecraft Jar 的资源变更。
