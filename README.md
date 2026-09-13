# 油库里世界 · MCyukkuri

[![Build](https://github.com/Longshou/MCyukkuri/actions/workflows/build.yml/badge.svg)](https://github.com/Longshou/MCyukkuri/actions/workflows/build.yml)

为 Minecraft Java 开发有种族、习性、家庭和巢穴的油库里世界。

**当前阶段：本轮八景 U 移植功能与联合验收已交付。** 实体已扩至九种，并完成生活、家庭、身体资源、工具、普通胎生、冷冻交易、日常社会行为、认知教学及野外事件等内容；亚种、帽船、图鉴与配音分别登记来源。本轮共同版本通过 36 项 GameTest、2058 条报告检查，以及双客户端和磁盘恢复等 2631 条检查。完整范围、当前开发 Jar 和实机证据见[移植实施记录](docs/PORT_REMAINDER.md)与[联合验收](docs/validation/port-integration/README.md)；默认速度多代生存、长期平衡、可选兼容与新增造型确认仍待后续。

## 环境

Minecraft **1.21.1** · NeoForge **21.1.250** · Java **21**

## 开始开发

先读 [项目地图](PROJECT_MAP.md) 和 [开发约定](AGENTS.md)，再进入目标目录。按任务需要使用本机制作、渲染、预览和验证工具，执行边界统一维护在开发约定中。

构建命令和人工验收项目见 [开发指南](docs/DEVELOPMENT.md)。[打开七种模型的动作预览](art/source/standard-runtime/preview.html)；该页面使用本次运行资源数据，不能代替 Minecraft 实机验收。橙、爱丽丝、帕秋莉的接入及长发适配见 [种族实装记录](docs/SPECIES_IMPLEMENTATION.md)。

## 开发入口

- [项目地图与文件归类](PROJECT_MAP.md)
- [项目开发规则](AGENTS.md)
- [开发指南与运行验收](docs/DEVELOPMENT.md)
- [开发路线与里程碑](docs/ROADMAP.md)
- [八景 U 内容梳理与 MC 移植计划](docs/HAKKEI_U_PORT_PLAN.md)
- [本轮移植交付、范围与开发 Jar](docs/PORT_REMAINDER.md)
- [共同版本联合验收与实机证据](docs/validation/port-integration/README.md)
- [实体与动画开发记录](docs/ENTITY_IMPLEMENTATION.md)
- [核心玩法、配置和当前验证](docs/CORE_GAMEPLAY.md)
- [P1–P2 生活与互动基线](docs/P1_P2_IMPLEMENTATION.md)
- [P0 历史基线、实机证据与待验收项](docs/P0_VALIDATION.md)
- [协作约定](CONTRIBUTING.md)
- [GitHub 任务](https://github.com/Longshou/MCyukkuri/issues)
- [自动构建与开发 Jar](https://github.com/Longshou/MCyukkuri/actions)
- [油库里 Wiki](wiki/README.md)
- [标准美术素材](standards/README.md)
- [当前实体接入素材](art/README.md)
- [历史动作、表情与冻结方案](art/library/README.md)
- [旧版生命周期与亚种原型](standards/yukkuri-v1/README.md)
- [项目脚本](scripts/README.md)
- [来源与第三方说明](THIRD_PARTY_NOTICES.md)

## 仓库结构

```text
src/main/java/        模组入口、注册及后续生态代码
src/main/resources/   实际游戏资源
src/main/templates/   模组声明与版本信息模板
src/p0Test/           不打包的实体与生活游戏验收夹具
standards/            标准素材制作源、当前导出与独立派生包
art/source/           当前实体接入所用的选定素材快照
wiki/                 设定、作品出处与核读记录
docs/                 开发指南和路线
scripts/              跨目录的资源处理与验证脚本
.github/              自动构建、Issue 与 PR 模板
.local/               不提交的本机临时工作文件
```

素材包内的 `archive/` 保存必要的历史预览与证据，`drafts/` 保存待确认方案；正式素材和当前导出随项目维护。临时工作文件、凭据、构建缓存与运行世界不纳入 Git。完整职责与命名规则见项目地图。

项目暂按 **All Rights Reserved** 管理。不是 Mojang 或 Microsoft 官方产品。
