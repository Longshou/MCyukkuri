# 开发指南

本工作区只用于代码、美术素材和文档开发，以及允许的静态核对。下文构建、客户端和服务器命令仅供 CI 或另行指定的验证环境使用，不在本机执行；Agent 不使用浏览器进行项目验证。完整边界见 [项目约定](../AGENTS.md)。

## 固定环境

| 项目 | 版本 |
| --- | --- |
| Minecraft Java | 1.21.1 |
| JDK | 21，64 位 |
| NeoForge | 21.1.250 |
| GeckoLib | 4.8.4，NeoForge 1.21.1 构建 |
| ModDevGradle | 2.0.146 |
| Gradle Wrapper | 9.2.1 |

依赖版本统一锁定；不要混用 Forge / Fabric 文件。无需手工下载 Gradle。首次构建会下载 Minecraft 开发依赖，时间取决于网络与机器。

## 指定验证环境

安装 JDK 21 后，让 `JAVA_HOME` 指向 JDK 根目录，并将其 `bin` 加入 PATH；`java -version` 应显示 21。

```powershell
.\gradlew.bat build
.\gradlew.bat runClient
```

macOS / Linux 使用 `./gradlew build` 和 `./gradlew runClient`。IntelliJ IDEA 打开仓库根目录，选择 Gradle 项目，Project SDK 和 Gradle JVM 都设为 21。

产物为 `build/libs/mcyukkuri-0.1.0-dev.jar`。手动装入游戏时，还需安装版本匹配的 NeoForge 和 GeckoLib；Jar 不包含这两个依赖。开发运行任务自动提供依赖。

## 第一版验收

1. `build` 成功，产物含 `META-INF/neoforge.mods.toml` 和 `assets/mcyukkuri/`。
2. `runClient` 进入主菜单；日志包含 `Yukkuri World initialized with Reimu and Marisa entities.`。
3. 创建创造模式测试世界，物品栏可见“油库里世界”分类和观察笔记。
4. 也可运行 `/give @s mcyukkuri:field_notebook` 检查物品注册。

观察笔记只是注册与资源加载的验证入口，尚无阅读界面。M1 已加入灵梦、魔理沙的实体、生成蛋、GeckoLib 渲染和动画代码；当前交付仅覆盖代码与美术资源开发，编译、测试和游戏运行需在另行安排的验证环境中进行。具体项目见[实体与动画开发记录](ENTITY_IMPLEMENTATION.md)。

## 专用服务器

```powershell
.\gradlew.bat runServer
```

首次运行通常因 EULA 未接受而退出。开发者应自行阅读并接受 Minecraft EULA 后再启服。服务器运行目录及日志以 Gradle 输出为准，均不纳入 Git。

## 制作源与运行资源

目录职责统一见 [项目地图](../PROJECT_MAP.md)。标准模型在 `standards/yukkuri-library/` 制作；当前 M1 实体使用 `art/source/` 中选定的灵梦、魔理沙快照，运行副本位于 `src/main/resources/`。对应关系见 [美术来源登记](../art/runtime-sources.json)。

标准造型确认、素材导出和游戏接入是不同状态。采用新版标准模型时，单独检查资源标识、骨架、贴图与动画适配，再在指定环境验收；不因标准库更新自动覆盖 M1 接入资源。

模型每次修改后提供最新预览，交由用户观察。旧截图与检查结果归档，不能代替本次检查。历史基础项目验收见 [M0 记录](history/M0_VALIDATION.md)，不覆盖当前实体改动。

## 自动构建

GitHub Actions 在 `main` 推送和 PR 时使用 Java 21，分别在 Ubuntu / Windows 构建。成功后在该次运行的 Artifacts 中下载开发 Jar。该流程验证编译与打包，不等价于完整游戏实机验收。

## 上游文档

- [NeoForge 1.21.1 开发文档](https://docs.neoforged.net/docs/1.21.1/gettingstarted/)
- [NeoForge ModDevGradle 模板](https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle)
- [GeckoLib 4 安装说明](https://github.com/bernie-g/geckolib/wiki/Installation-(Geckolib4))
