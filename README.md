# 油库里世界 · MCyukkuri

[![Build](https://github.com/Longshou/MCyukkuri/actions/workflows/build.yml/badge.svg)](https://github.com/Longshou/MCyukkuri/actions/workflows/build.yml)

为 Minecraft Java 开发有种族、习性、家庭和巢穴的油库里世界。

**当前状态（2026-10-08）**：八景 U 移植的主要系统已交付。共有九种油库里实体和十种亚种／后天形态，覆盖生活需求与日常行为、茎生／胎生／卵生、伤口与遗体、性格与认知、对白配音、金银章、家油、饲养设施、野外住所与聚落、野外事件、商店与工厂、接触工具、冷冻收容和帽船。头饰与发型位置适配、鼓腮第七版已合入，造型待复查。

后续开发顺序见[完善计划](docs/DEVELOPMENT_PLAN.md)，重点是季节与越冬、失饰的社会后果、认屋、稀有种和资源瘦身。已交付范围见[移植实施记录](docs/PORT_REMAINDER.md)；人工试玩可使用[实验地图](docs/EXPERIMENTAL_MAP.md)。CI 只做构建，开发 Jar 在 [Actions](https://github.com/Longshou/MCyukkuri/actions) 下载。

## 环境

Minecraft **1.21.1** · NeoForge **21.1.250** · Java **21**

## 开始开发

先读 [项目地图](PROJECT_MAP.md) 和 [开发约定](AGENTS.md)，再进入目标目录。按任务需要使用本机制作、渲染、预览和验证工具，执行边界统一维护在开发约定中。

构建命令和人工验收项目见 [开发指南](docs/DEVELOPMENT.md)。[打开当前圆润模型的动作预览](art/source/rounded-runtime/previews/current/index.html)；该页面读取当前选用数据，不能代替 Minecraft 实机验收。橙、爱丽丝、帕秋莉的历史接入记录见 [种族实装记录](docs/SPECIES_IMPLEMENTATION.md)。

## 开发入口

- [项目地图与文件归类](PROJECT_MAP.md)
- [项目开发规则](AGENTS.md)
- [开发指南与运行验收](docs/DEVELOPMENT.md)
- [完善计划（后续开发顺序）](docs/DEVELOPMENT_PLAN.md)
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
