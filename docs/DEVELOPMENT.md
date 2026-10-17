# 开发指南

构建与运行命令。本机只修改代码或建模，不执行编译；`build` 由远端 CI 完成（CI 只编译，不跑测试）。玩法效果由用户在[油库里试玩场](EXPERIMENTAL_MAP.md)中亲自试玩。开发顺序见 [完善计划](DEVELOPMENT_PLAN.md)。

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

以下编译和启动命令供远端或其他允许编译的环境使用，本机不执行。

安装 JDK 21 后，让 `JAVA_HOME` 指向 JDK 根目录，并将其 `bin` 加入 PATH；`java -version` 应显示 21。

```powershell
.\gradlew.bat build
.\gradlew.bat runClient
```

macOS / Linux 使用 `./gradlew build` 和 `./gradlew runClient`。IntelliJ IDEA 打开仓库根目录，选择 Gradle 项目，Project SDK 和 Gradle JVM 都设为 21。

产物为 `build/libs/mcyukkuri-0.1.0-dev.jar`。手动装入游戏时，需要版本匹配的 NeoForge。九种油库里实体及其亚种形态使用项目内的网格与骨架渲染器，不需要 GeckoLib。

## 第一版验收

1. `build` 成功，产物含 `META-INF/neoforge.mods.toml` 和 `assets/mcyukkuri/`。
2. `runClient` 进入主菜单；日志包含 `Yukkuri World initialized with nine Yukkuri entity types.`。
3. 创建创造模式测试世界，物品栏可见“油库里世界”分类和观察笔记。
4. 也可运行 `/give @s mcyukkuri:field_notebook` 检查物品注册。

观察笔记已有八页个体状态和七页使用指南，包含成长、亲缘、身体和家油生活。当前玩法与配置见 [核心玩法记录](CORE_GAMEPLAY.md)。

## 试玩与检查

唯一的试玩入口是根目录 `启动动作博物馆.cmd`：它用远端 CI 产出的运行包打开由十块开放场地组成的试玩场，不在本机构建。试玩场的生成、更新与场景说明见 [油库里试玩场](EXPERIMENTAL_MAP.md)。仓库里没有 GameTest 或其他自动化测试，CI 也不运行测试。

素材导出一致性可按需运行 `scripts/export_*.py --check`（只读，不编译），说明见 [项目脚本](../scripts/README.md)；它们不是合并门槛。

## 专用服务器

```powershell
.\gradlew.bat runServer
```

首次运行通常因 EULA 未接受而退出。开发者应自行阅读并接受 Minecraft EULA 后再启服。服务器运行目录及日志以 Gradle 输出为准，均不纳入 Git。

## 制作源与运行资源

目录职责统一见 [项目地图](../PROJECT_MAP.md)。标准模型在 `standards/yukkuri-library/` 制作；当前实体使用 `art/source/` 下选定的快照（普通五种等见 `rounded-runtime/`，其余见各自目录），运行数据位于 `src/main/resources/assets/mcyukkuri/yukkuri/`。对应关系见 [美术来源登记](../art/runtime-sources.json)。

标准造型确认、素材导出和游戏接入是不同状态。采用新版标准模型时，单独检查资源标识、骨架、贴图与动画适配，再在指定环境验收；不因标准库更新自动覆盖已接入资源。

模型每次修改后在本机出图，把最新预览贴在回复里交由用户观察。预览、截图与检查结果不入库，旧结果不能代替本次检查。

## 自动构建

GitHub Actions 在 `main` 推送和 PR 时用 Java 21（Ubuntu）执行 `./gradlew build`，并上传开发 Jar 到该次运行的 Artifacts。构建只检出 `src/` 与 Gradle 包装器，不检出大型美术目录。

## 上游文档

- [NeoForge 1.21.1 开发文档](https://docs.neoforged.net/docs/1.21.1/gettingstarted/)
- [NeoForge ModDevGradle 模板](https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle)
