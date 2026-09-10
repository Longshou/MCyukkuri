# 油库里世界 · MCyukkuri

[![Build](https://github.com/Longshou/MCyukkuri/actions/workflows/build.yml/badge.svg)](https://github.com/Longshou/MCyukkuri/actions/workflows/build.yml)

为 Minecraft Java 开发有种族、习性、家庭和巢穴的油库里世界。

**当前阶段：项目基础。** 已加入模组入口、观察笔记物品、创造模式分类、中英文资源与 GeckoLib 依赖。生物实体、动画控制器、觅食、成长和繁殖尚未实现。

## 环境

Minecraft **1.21.1** · NeoForge **21.1.250** · Java **21** · GeckoLib **4.8.4**

## 快速开始

使用 JDK 21，克隆仓库后在根目录运行：

```powershell
.\gradlew.bat build
.\gradlew.bat runClient
```

macOS / Linux 使用 `./gradlew build` 和 `./gradlew runClient`。首次构建会下载开发依赖。

产物：`build/libs/mcyukkuri-0.1.0-dev.jar`。手动安装还需要对应版本的 NeoForge 与 GeckoLib。

进入创造模式可检查“油库里世界”分类与观察笔记；观察笔记当前是注册验证物品，尚无阅读功能。

## 开发入口

- [开发指南与运行验证](docs/DEVELOPMENT.md)
- [开发路线与里程碑](docs/ROADMAP.md)
- [协作约定](CONTRIBUTING.md)
- [GitHub 任务](https://github.com/Longshou/MCyukkuri/issues)
- [自动构建与开发 Jar](https://github.com/Longshou/MCyukkuri/actions)
- [油库里 Wiki](wiki/README.md)
- [精选基础模型](art/README.md)
- [来源与第三方说明](THIRD_PARTY_NOTICES.md)

## 仓库结构

```text
src/main/java/        模组入口、注册及后续生态代码
src/main/resources/   实际游戏资源
src/main/templates/   模组声明与版本信息模板
art/source/           精选可编辑模型与贴图
wiki/                 设定、作品出处与核读记录
docs/                 开发指南和路线
.github/              自动构建、Issue 与 PR 模板
```

本地历史美术试验、浏览器配置、生成预览与运行世界不纳入 Git；原有文件保留在本地。后续通过 Issue → 分支 → PR 推进开发。

项目暂按 **All Rights Reserved** 管理。不是 Mojang 或 Microsoft 官方产品。
