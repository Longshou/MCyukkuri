# 协作约定

规则以 [AGENTS.md](AGENTS.md) 为准，目录分工见 [项目地图](PROJECT_MAP.md)，构建命令见 [开发指南](docs/DEVELOPMENT.md)，开发顺序见 [完善计划](docs/DEVELOPMENT_PLAN.md)。

1. 一次改动围绕一个目标。功能可直接在分支上开发，必要时开 Issue 记录想法；生态设定尽量附 Wiki 来源，原创规则注明“原创”。
2. `main` 是集成分支。CI 只做 `./gradlew build` 并上传开发 Jar（不跑测试），构建通过即可合并；玩法由用户在油库里试玩场亲自试玩。
3. PR 写清楚改了什么、玩家能看到什么变化、实际做了哪些验证（没做就写没做）。
4. 版本号集中在 `gradle.properties`。
5. 公共逻辑放 `io.github.longshou.mcyukkuri` 下，客户端渲染放 `client` 包并用客户端事件注册，避免专用服务器加载客户端类。服务器负责生态状态与决策，客户端只负责展示。
6. 仓库暂按 All Rights Reserved 管理。不要提交外部游戏资源、账户凭据、Minecraft 本体或运行世界。
