# 当前保存、打包与可运行性检查

后续更新：[金章排查](../../gold-exam/current/README.md)已修正可复现的验收时序问题，最新完整联合回归 42 项通过，游戏包指纹未变。下文保留首次检查的原始结果及范围。

2026-09-13。当前磁盘源码可以构建，实际客户端可以进入一次性测试世界。已有改动已保存在本地，仍有大量未提交文件；保存到磁盘不代表已提交或上传。当前版本可以用于开发试玩，但本轮联合回归有一项失败，不能登记为全部验收通过。

## 当前可用包

本轮从当前检出目录隔离构建并冻结，开发 Jar 位于 `.local/play-readiness/release/mcyukkuri-0.1.0-dev.jar`，大小 90,596,950 字节。SHA256：`30102b8cc134988fa3d74bfe64a007f8e3e5e9a642c76688014ce3e2abe03304`。使用 Minecraft 1.21.1、NeoForge 21.1.250、Java 21；不需要 GeckoLib。

[打包核对](package.json)确认 Jar 的 1727 个类／资源条目与冻结输出一致，1473 个当前运行资源逐字节匹配，`src/` 在冻结后未变化，测试模组未打入发行包。圆润模型、人格、灵胖、生育规则及后续家油资源均在本轮当前源码构建范围内。

根目录 `build/libs/mcyukkuri-0.1.0-dev.jar` 是凌晨旧包，与本轮包相比有 677 个新增或内容不同的条目。此前 `.local/bean-braid-fangs/release/` 的包也有 15 个条目与当前不同，详见打包核对。此次不覆盖这些历史产物，也没有提交、推送或安装到用户的其他游戏实例。

标准库中的草稿、候选、DOS 等未接入范围，以及待确认的造型仍按各登记保留；不能把全部美术文件都称为游戏实装。既有剩余范围见[移植实施记录](../../../PORT_REMAINDER.md)。

## 实际结果

- 隔离 `build` 成功。实际主程序、测试类、资源与运行 classpath 的输出位置检查通过，见 [build.log](build.log) 和 [isolation.json](isolation.json)。
- 当前圆润来源及全部 19 种运行形态的网格、动作、伤口和徽章指纹通过，资源检查共 28 条记录，见 [assets.json](assets.json)。对白 288 条、周边导出 107 项、徽章挂点、人类世界 112 个文件、家庭 2 个文件、家油设施 11 个文件的既有检查通过。
- 完整联合 GameTest 完成 41 项，40 项通过、1 项失败；报告共 2177 条检查，其中 2176 条通过，没有缺失报告，见 [gametest-summary.json](gametest-summary.json)。唯一失败为金章考试完成断言，原始记录见 [badge-joint-failure.json](badge-joint-failure.json)。
- 使用同一冻结主程序和资源，在另一全新世界仅运行徽章专项，47 条全部通过、退出码 0，见 [badge-recheck.json](badge-recheck.json)。此复测不能抹去联合失败；当前证据尚不能确定其来自生产逻辑、随机状态或测试时序。夹具在考试后等待 500 tick，而生产规则前四阶段共 400 tick、最后导航阶段允许最多 240 tick，这是后续定位线索，尚未确认因果。
- 实际客户端进入复制的测试世界，并通过夹具设置普通灵梦、灵胖、多毛灵胖、两豆油、两捕食种与家油灵梦。当前画面与机器记录见本目录 `frames/` 和 `client-checks.json`。这是当前冻结版本的单客户端显示检查，状态由夹具设置，不代表重新执行所有玩家交互。

## 尚未同步的检查入口

以下失败均保留，没有为获得通过而回写旧资源或放宽生产规则：

1. `python scripts/validate_entity_assets.py` 在“manifest must register all nine variants”处失败。脚本仍要求旧的九项登记、固定 `standard-runtime` 快照及旧贴图尺寸，当前登记已经扩展并使用圆润选用来源。
2. `python scripts/export_living_assets.py --check` 报告 `yukkuri_presentation/living.json` 与旧设施配方不同。当前嘴部挂点由 `export_rounded_runtime.py` 的 `presentation()` 按新模型计算；旧导出器仍比较旧配方。现有 CI 调用了上述两项，因此这次检查不能宣称 CI 就绪。
3. 客户端夹具 `assets` 报 `Invalid atlas: REIMU`。`P0Client.java` 仍要求非捕食种图集为 128×128，当前圆润图集为 256×256。此失败属于夹具尺寸约定过期；当前游戏显示另用实机帧检查，完整客户端资源断言未通过。

本轮未修改生产源码、模型或上述旧检查脚本。未重新执行多人、默认速度多代生存、长期平衡、真实用户旧世界升级或第三方模组兼容。

## 本机启动

当前 PATH 中的默认 Java 是 17；项目已有 JDK 21 可直接使用。在项目根目录运行：

```powershell
$env:JAVA_HOME='E:/yukkuri/.devtools/mcyukkuri/java/jdk-21.0.12.1+1'
./gradlew.bat -I .local/play-readiness/checks.init.gradle --project-cache-dir .local/play-readiness/gradle-cache --no-configuration-cache runClient
```

该命令使用普通客户端配置并重新检查当前编译输出，不加载 P0 测试模组；普通客户端存档由项目现有 `run/` 管理。本轮实际运行验收使用复制的一次性 P0 世界，未启动或操作用户存档。使用独立游戏启动器时，将本轮 Jar 放入上述匹配版本实例的 `mods` 目录，并选择 Java 21。

本轮构建命令为相同前缀加 `build createP0GameTestLaunchScript createP0PreviewLaunchScript createClientLaunchScript integrationIsolation`。冻结、联合回归、徽章单测及客户端控制脚本与完整日志保存在 `.local/play-readiness/`。本轮客户端和 GameTest 临时进程在检查后退出；此前已有的旧 P0 服务器不属于本任务，未操作。
