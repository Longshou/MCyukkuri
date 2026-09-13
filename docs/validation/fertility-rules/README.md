# 生育资格修正与验收

2026-09-13。按用户设定，不足油除多毛外均不具备生育能力，DOS 也不具备。六种已接入的不足形态已落实限制，多毛继续满足正常条件后生育。DOS 尚无独立运行类型，本次登记其规则，不新增 DOS 实体，也不把灵胖标记视作 DOS。

## 实际改动

`SubspeciesLife.canReproduce()` 提供先天形态资格，`FamilyLife.sterile()` 汇合形态不育与手术绝育。自然配对双向检查、辅助繁育、促繁刺激及现有工厂配种都使用这项资格。形态数据和手术标记仍分别保存；不通过写入手术标记实现先天不育。正常亲本仍可生下不足形态子代。

旧存档中不足油母体仍挂接的未完成孕育，在家庭更新时取消，即使启用 NoAI 也会处理；直接出生接口也检查母体形态。已出生个体及其身份、家谱保留，未出生预留沿用现有 `LOST` 状态退出人口容量。已剪下的茎、离体卵不追溯取消。此次没有遍历未加载世界清理实体，母体加载后按上述规则处理。

联合夹具的豆体母体改为多毛母体。旧豆体分娩[补拍记录](../port-integration/current/bean-birth-supplement/README.md)及图集已注明不符合当前设定，原始图片、检查记录和旧 Jar 指纹保持不变；仅更新两份说明文件对应的证据哈希，95 份历史证据核对通过。

## 已执行的验证

JDK 21、Minecraft 1.21.1、NeoForge 21.1.250。**6 项 GameTest、134 条检查全部通过，进程退出码 0**。报告和完整日志见 [current](current/summary.json)。

| 报告 | 检查数 | 覆盖 |
| --- | ---: | --- |
| [生育资格](current/fertility-checks.json) | 33 | 六种不足形态双向亲本拒绝、辅助繁育拒绝且保留物品与营养、存读、多毛可受孕与真实出生、旧孕育停止且保留已出生子代、直接出生防绕过 |
| [辅助繁育与出生](current/consumable-birth-checks.json) | 42 | 投药提交、中断与退还、存读、出口碰撞和原身份出生 |
| [服务器自然出生](current/consumable-live-birth-checks.json) | 5 | 正式服务器 tick 推进孕育、双胎出生与落地 |
| [形态出生](current/consumable-morph-checks.json) | 14 | 固定子代形态、豆形出生碰撞与成长净空；母体为合格形态 |
| [消耗品规则](current/consumable-rule-checks.json) | 36 | 既有口服、刺激、喷雾和馅料效果 |
| [自然饮水](current/consumable-live-checks.json) | 4 | 既有实际寻路与饮水 |

首次新增夹具漏铺住所屋顶，导致四项正向资格检查失败；原有 101 条检查均通过。补齐真实屋顶并增加住所领取断言后，仅重新编译测试文件，再在新复制的同一生产副本上运行。前后生产类、资源和 Jar 字节一致，见[夹具修正记录](current/fixture-correction.json)。

固定运行目录为 `.local/fertility-rules/runtime-20260913-105628/`。Jar 为 `libs/mcyukkuri-0.1.0-dev.jar`，90,618,559 字节，SHA-256：

```text
497c4433d5347ea6e0f6a1d3e2b23047fe3d594c7c9abd5d40b625586de149d7
```

源、运行文件及本次五份改动源码与固定副本的一致性见[范围与指纹](current/scope.json)、[源清单](current/source-manifest.json)、[运行清单](current/runtime-manifest.json)。所有 source set 的实际类输出、资源输出与 runtime classpath 已核对，无根 `build/` 路径泄漏，见[隔离记录](current/isolation.json)。

本轮实际构建命令如下，初始化脚本同时保存在[验收附件](current/checks.init.gradle)：

```powershell
$env:JAVA_HOME='E:\yukkuri\.devtools\mcyukkuri\java\jdk-21.0.12.1+1'
$env:PATH="$env:JAVA_HOME\bin;$env:PATH"
./gradlew.bat --project-cache-dir .local/fertility-rules/gradle-cache --no-configuration-cache -I .local/fertility-rules/checks.init.gradle build createP0GameTestLaunchScript integrationIsolation -x test
python .local/fertility-rules/run_checks.py
python .local/fertility-rules/run_checks.py --retry
```

带 `--retry` 的命令重新编译修正后的夹具并复制固定输入；具体 `javac` 参数见夹具修正记录，游戏启动参数见[命令](current/command.json)。两个测试进程均已退出，本任务没有残留 Java 进程。三个既有测试运行器已纳入 `fertility-checks`，Python 语法检查通过；联合运行器的完整性期望同步为当前登记的 41 项。本轮未执行这 41 项联合回归、客户端、视觉或多人验收，其他任务在冻结后继续的资源修改也不属于本报告。

相关源码与文档差异检查通过，链接及中文事实、表述经过人工核对；未使用不可用的中文文档检查技能。
