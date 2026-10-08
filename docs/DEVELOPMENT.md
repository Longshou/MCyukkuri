# 开发指南

构建与运行命令。流程从简：日常开发只要求 `build` 通过，玩法改动进游戏看一眼；其余检查按需使用。开发顺序见 [完善计划](DEVELOPMENT_PLAN.md)。

## 固定环境

| 项目 | 版本 |
| --- | --- |
| Minecraft Java | 1.21.1 |
| JDK | 21，64 位 |
| NeoForge | 21.1.250 |
| ModDevGradle | 2.0.146 |
| Gradle Wrapper | 9.2.1 |

依赖版本统一锁定；不要混用 Forge / Fabric 文件。无需手工下载 Gradle。首次构建会下载 Minecraft 开发依赖，时间取决于网络与机器。

## 构建与运行环境

安装 JDK 21 后，让 `JAVA_HOME` 指向 JDK 根目录，并将其 `bin` 加入 PATH；`java -version` 应显示 21。

```powershell
.\gradlew.bat build
.\gradlew.bat runClient
```

macOS / Linux 使用 `./gradlew build` 和 `./gradlew runClient`。IntelliJ IDEA 打开仓库根目录，选择 Gradle 项目，Project SDK 和 Gradle JVM 都设为 21。

产物为 `build/libs/mcyukkuri-0.1.0-dev.jar`。手动装入游戏时，需要版本匹配的 NeoForge。当前七种实体使用项目内的网格与骨架渲染器，不再要求 GeckoLib。

## 第一版验收

1. `build` 成功，产物含 `META-INF/neoforge.mods.toml` 和 `assets/mcyukkuri/`。
2. `runClient` 进入主菜单；日志包含 `Yukkuri World initialized with five standard species and two house variants.`。
3. 创建创造模式测试世界，物品栏可见“油库里世界”分类和观察笔记。
4. 也可运行 `/give @s mcyukkuri:field_notebook` 检查物品注册。

观察笔记已有八页个体状态和七页使用指南，包含成长、亲缘、身体和家油生活。当前玩法、配置和实机证据见 [核心玩法记录](CORE_GAMEPLAY.md)。测试入口继续使用 [P0 隔离夹具](P0_VALIDATION.md)，`runP0GameTest` 同时运行实体、生活、家庭和核心生态测试。

## 可选检查

以下工具保留，排查问题或大改后再用，不作为合并门槛：

- `./gradlew runP0GameTest`：无窗口测试服务器中运行实体、生活、家庭与核心生态 GameTest。
- `./gradlew verifyModelFingerprint`：模型指纹换行兼容检查。
- `python scripts/validate_entity_assets.py` 及 `scripts/export_*_assets.py --check`：素材导出与运行资源一致性检查，说明见 [项目脚本](../scripts/README.md)。

## 专用服务器

```powershell
.\gradlew.bat runServer
```

首次运行通常因 EULA 未接受而退出。开发者应自行阅读并接受 Minecraft EULA 后再启服。服务器运行目录及日志以 Gradle 输出为准，均不纳入 Git。

## 制作源与运行资源

目录职责统一见 [项目地图](../PROJECT_MAP.md)。标准模型在 `standards/yukkuri-library/` 制作；当前七种实体使用 `art/source/standard-runtime/` 中选定的快照和适配骨架，运行数据位于 `src/main/resources/assets/mcyukkuri/yukkuri/`。对应关系见 [美术来源登记](../art/runtime-sources.json)。

标准造型确认、素材导出和游戏接入是不同状态。采用新版标准模型时，单独检查资源标识、骨架、贴图与动画适配，再在指定环境验收；不因标准库更新自动覆盖 M1 接入资源。

模型每次修改后提供最新预览，交由用户观察。旧截图与检查结果归档，不能代替本次检查。历史基础项目验收见 [M0 记录](history/M0_VALIDATION.md)，不覆盖当前实体改动。

## 自动构建

GitHub Actions 在 `main` 推送和 PR 时用 Java 21（Ubuntu）执行 `./gradlew build`，并上传开发 Jar 到该次运行的 Artifacts。构建只检出 `src/` 与 Gradle 包装器，不检出大型美术目录。

## 上游文档

- [NeoForge 1.21.1 开发文档](https://docs.neoforged.net/docs/1.21.1/gettingstarted/)
- [NeoForge ModDevGradle 模板](https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle)
