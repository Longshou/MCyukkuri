# 油库里世界 · MCyukkuri

[![Build](https://github.com/Longshou/MCyukkuri/actions/workflows/build.yml/badge.svg)](https://github.com/Longshou/MCyukkuri/actions/workflows/build.yml)

为 Minecraft Java 开发有种族、习性、家庭和巢穴的油库里世界，涵盖饲养、野外生态和人类商店与工厂。

**当前状态（2026-10-15）**：持续开发中，版本为 `0.1.0-dev`。已接入九种油库里实体、十九套运行造型及对应贴图；水生魔理沙作为生态变体复用普通魔理沙造型。

已接入的主要玩法：

- **生活与家庭**：饥饿、睡眠、卫生等需求，茎生／胎生／卵生，成长与亲缘、家油巢卵；胎数与母体大小联动，茎长按子油数量和体型适配。
- **身体与交流**：伤口、馅料与遗体，性格、信任与认知教学，对白配音和金银章考核。
- **野外生态**：天然住所、聚落、野外事件与捕食种，螺壳受惊缩壳、水生魔理沙帽船。
- **饲养与人类世界**：食盆、窝、厕所、游具、照料工具和冷冻收容，商店、三类工厂及人类 NPC；倒放帽子可用于接生和行船。

仓库资料整理和素材一致性检查修复已完成，部分造型仍待确认。构建与素材检查通过不代表全部形态和玩法已完成游戏内确认。已有功能范围见[移植实施记录](docs/PORT_REMAINDER.md)。

近期先补螺壳徽章挂点与各形态显示确认，再推进失饰的社会后果、温度与浸水体验、茎脱落物品和离巢独立。季节与越冬、认屋、稀有种、人类世界补完及运行资源瘦身仍在后续范围中，具体状态和顺序见[完善计划](docs/DEVELOPMENT_PLAN.md)。

## 环境

Minecraft **1.21.1** · NeoForge **21.1.250** · Java **21**

## 获取开发版

在 [GitHub Actions](https://github.com/Longshou/MCyukkuri/actions/workflows/build.yml) 选择 `main` 上构建成功的运行，从 Artifacts 下载对应的 `mcyukkuri-<提交号>`，解压后将 Jar 放入匹配版本的 NeoForge 游戏实例的 `mods/` 目录。模组使用项目内的网格与骨架渲染器，不需要 GeckoLib。

人工试玩可双击根目录的 `启动动作博物馆.cmd` 进入[油库里试玩场](docs/EXPERIMENTAL_MAP.md)，玩法与配置见[核心玩法记录](docs/CORE_GAMEPLAY.md)。CI 在 `main` 推送和 PR 时构建并上传开发 Jar，不自动执行游戏内试玩。

## 开始开发

先读 [项目地图](PROJECT_MAP.md) 和 [开发约定](AGENTS.md)，再按[完善计划](docs/DEVELOPMENT_PLAN.md)进入目标目录。本机只修改代码或建模，按需使用非编译的资源检查和离线预览工具；不执行 Gradle 构建或启动游戏时的自动编译。构建交给远端 CI（只编译，不跑测试），玩法效果由用户在油库里展馆（`启动动作博物馆.cmd`）亲自试玩。

远端构建说明和人工确认项目见[开发指南](docs/DEVELOPMENT.md)。用户可手动双击根目录的 `打开素材检查.cmd` 查看各形态的动作与表情；素材检查室读取游戏资源，不能代替 Minecraft 实机确认。预览、截图与检查结果只留在本机，不入库。

## 开发入口

- [项目地图与文件归类](PROJECT_MAP.md)
- [项目开发规则](AGENTS.md)
- [开发指南与运行验收](docs/DEVELOPMENT.md)
- [完善计划（后续开发顺序）](docs/DEVELOPMENT_PLAN.md)
- [开发路线与里程碑](docs/ROADMAP.md)
- [本轮移植交付、范围与开发 Jar](docs/PORT_REMAINDER.md)
- [实体与动画开发记录](docs/ENTITY_IMPLEMENTATION.md)
- [核心玩法、配置和当前验证](docs/CORE_GAMEPLAY.md)
- [P1–P2 生活与互动基线](docs/P1_P2_IMPLEMENTATION.md)
- [油库里试玩场（唯一试玩地图）](docs/EXPERIMENTAL_MAP.md)
- [协作约定](CONTRIBUTING.md)
- [GitHub 任务](https://github.com/Longshou/MCyukkuri/issues)
- [自动构建与开发 Jar](https://github.com/Longshou/MCyukkuri/actions)
- [油库里 Wiki](wiki/README.md)
- [标准美术素材](standards/README.md)
- [当前实体接入素材](art/README.md)
- [项目脚本](scripts/README.md)
- [来源与第三方说明](THIRD_PARTY_NOTICES.md)

## 仓库结构

```text
src/main/java/        模组入口、注册、实体行为与生态逻辑
src/main/resources/   实际游戏资源
src/main/templates/   模组声明与版本信息模板
standards/            标准素材制作源、当前导出与独立派生包
art/                  当前实体接入所用的素材快照、道具、建筑、音频与对白
preview/review/       本机素材检查室
wiki/                 设定、作品出处与核读记录
docs/                 开发指南和路线
scripts/              跨目录的资源处理与验证脚本
.github/              自动构建、Issue 与 PR 模板
.local/               不提交的本机临时工作文件
```

素材包内的 `drafts/` 保存待确认方案；正式素材和当前导出随项目维护。预览、截图、检查报告、临时工作文件、凭据、构建缓存与运行世界不纳入 Git。完整职责与命名规则见项目地图。

项目暂按 **All Rights Reserved** 管理。不是 Mojang 或 Microsoft 官方产品。
