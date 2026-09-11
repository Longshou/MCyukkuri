# 油库里世界 · MCyukkuri

[![Build](https://github.com/Longshou/MCyukkuri/actions/workflows/build.yml/badge.svg)](https://github.com/Longshou/MCyukkuri/actions/workflows/build.yml)

为 Minecraft Java 开发有种族、习性、家庭和巢穴的油库里世界。

**当前阶段：M1 实体与动画开发，待运行验收。** 已加入灵梦、魔理沙的实体与生成蛋注册、GeckoLib 模型渲染及待机／移动动画代码和资源。当前改动尚未完成编译和实机验收；觅食、成长和繁殖尚未实现。

## 环境

Minecraft **1.21.1** · NeoForge **21.1.250** · Java **21** · GeckoLib **4.8.4**

## 开始开发

先读 [项目地图](PROJECT_MAP.md) 和 [开发约定](AGENTS.md)，再进入目标目录。本工作区只进行代码、美术素材与文档开发，以及允许的静态核对；不在本机编译、测试、运行游戏或启动预览服务器，也不使用浏览器验证项目。

构建与游戏验收在 CI 或指定验证环境完成，命令和人工验收项目见 [开发指南](docs/DEVELOPMENT.md)。M1 已加入灵梦、魔理沙生成蛋及两个实体 ID，当前仍待运行验收。

## 开发入口

- [项目地图与文件归类](PROJECT_MAP.md)
- [项目开发规则](AGENTS.md)
- [开发指南与指定环境验收](docs/DEVELOPMENT.md)
- [开发路线与里程碑](docs/ROADMAP.md)
- [实体与动画开发记录](docs/ENTITY_IMPLEMENTATION.md)
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
src/main/java/        模组入口、注册及后续生态代码
src/main/resources/   实际游戏资源
src/main/templates/   模组声明与版本信息模板
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
