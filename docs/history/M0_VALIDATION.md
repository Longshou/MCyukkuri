# M0 基础项目历史验收

本记录只适用于下述初始提交，不覆盖当前 M1 实体或后续素材改动。以下为历史事实，不是当前工作区的执行指引；本机限制见 [项目约定](../../AGENTS.md)。

日期：2026-09-11。验证对象：初始代码提交 `6a8e7c13982d38083ba03c23c0829eaff9d34994`。

| 检查 | 结果 |
| --- | --- |
| 本地 Windows / JDK 21 构建 | `gradlew build --no-daemon` 成功 |
| GitHub Actions / Ubuntu / Java 21 | 成功，已上传开发 Jar |
| GitHub Actions / Windows / Java 21 | 成功，已上传开发 Jar |
| Jar 内容 | 入口及注册类、展开后的模组声明、语言文件、物品模型、pack.mcmeta 均存在 |
| 资源边界 | 未将 art、wiki、variants、reference 开发资料打入 Jar |
| 客户端启动 | NeoForge 21.1.250、GeckoLib 4.8.4、Yukkuri World 0.1.0-dev 一同加载；初始化日志及资源重载正常 |
| 游戏画面 | 实际观察到客户端进入单人创造模式世界与创造物品栏 |

[对应 GitHub Actions 运行与产物](https://github.com/Longshou/MCyukkuri/actions/runs/34509481514)

本地 Jar：`mcyukkuri-0.1.0-dev.jar`，6648 字节。

SHA-256：`9551a0b1c57e615f6e9ec71d83b21e211ac69c8855398275e616d024b5256faa`。

本阶段没有独立单元测试用例，`test` 任务为 NO-SOURCE；不将编译成功表述为生态行为已测试。尚未逐项操作验证观察笔记，也尚未验证专用服务器、多人同步或实体行为。

本机为首次运行下载了开发依赖与游戏资源，后续运行可复用缓存。本地代理连接复用曾导致依赖下载错误，已通过仅作用于本机构建进程的参数处理；代理地址和 JDK 安装路径未纳入仓库配置。
