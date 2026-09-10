# 开发指南

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

## Windows

安装 JDK 21 后，让 `JAVA_HOME` 指向 JDK 根目录，并将其 `bin` 加入 PATH；`java -version` 应显示 21。

```powershell
.\gradlew.bat build
.\gradlew.bat runClient
```

macOS / Linux 使用 `./gradlew build` 和 `./gradlew runClient`。IntelliJ IDEA 打开仓库根目录，选择 Gradle 项目，Project SDK 和 Gradle JVM 都设为 21。

产物为 `build/libs/mcyukkuri-0.1.0-dev.jar`。手动装入游戏时，还需安装版本匹配的 NeoForge 和 GeckoLib；Jar 不包含这两个依赖。开发运行任务自动提供依赖。

## 第一版验收

1. `build` 成功，产物含 `META-INF/neoforge.mods.toml` 和 `assets/mcyukkuri/`。
2. `runClient` 进入主菜单；日志包含 `Yukkuri World foundation initialized`。
3. 创建创造模式测试世界，物品栏可见“油库里世界”分类和观察笔记。
4. 也可运行 `/give @s mcyukkuri:field_notebook` 检查物品注册。

观察笔记只是注册与资源加载的验证入口，尚无阅读界面。GeckoLib 已接入依赖，尚无生物实体或动画控制器。

## 专用服务器

```powershell
.\gradlew.bat runServer
```

首次运行通常因 EULA 未接受而退出。开发者应自行阅读并接受 Minecraft EULA 后再启服。服务器运行目录及日志以 Gradle 输出为准，均不纳入 Git。

## 目录边界

- `src/main/java`：模组代码和注册。
- `src/main/resources`：实际打包到 Jar 的资源。
- `src/main/templates`：构建时展开版本号的模组声明。
- `art/source`：选定的可编辑美术源文件，不自动打进 Jar。
- `wiki`：设定与来源；同人作品之间的设定分歧保留说明。
- `docs`：架构、开发流程与里程碑。

所有历史模型与大体积试验仍保存在原本的本地目录；Git 忽略 `variants`、`preview`、`models`、`textures` 等历史目录。基础资产复制到 `art/source` 后单独管理，不自动覆盖历史文件。

## 自动构建

GitHub Actions 在 `main` 推送和 PR 时使用 Java 21，分别在 Ubuntu / Windows 构建。成功后在该次运行的 Artifacts 中下载开发 Jar。该流程验证编译与打包，不等价于完整游戏实机验收。

## 上游文档

- [NeoForge 1.21.1 开发文档](https://docs.neoforged.net/docs/1.21.1/gettingstarted/)
- [NeoForge ModDevGradle 模板](https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle)
- [GeckoLib 4 安装说明](https://github.com/bernie-g/geckolib/wiki/Installation-(Geckolib4))
