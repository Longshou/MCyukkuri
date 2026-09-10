本次[基础互动任务已冻结](art/library/scenes/basic-interactions/FROZEN.md)，待其他任务完成后调整再纳入。

# 当前：B 魔理沙修正油菊、底部与脸部衔接

[自由视角预览](variants/marisa-b-refined/index.html) · [模型](variants/marisa-b-refined/marisa.bbmodel) · [说明](variants/marisa-b-refined/README.md)。参考灵梦的前腹与透明五官处理。

# 当前动作展示：B 体型自由视角

[噗咕 / 伸长 / 逃跑](variants/marisa-b-actions/index.html)。播放与暂停时均可拖动旋转、缩放，切换动作保持镜头角度。

# 当前：B 躯干长度，稍增高与逃跑收稳

[预览](variants/marisa-b-taller/index.html) · [新版模型](variants/marisa-b-taller/b/marisa.bbmodel)。在用户选定 B 上增高 6%，取消油臀前后伸缩并收小扭动。

# 当前：躯干加长，油臀维持原尺寸

[四版逃跑观察](variants/marisa-body-length-trials/index.html) · [侧面对照](variants/marisa-body-length-trials/comparison.png)。修正此前只加深油臀的问题，延长主体并将原尺寸油臀后移。

# 新增：体型与油臀五版逃跑对照

[选择与播放](variants/marisa-rump-size-trials/index.html) · [并排动画](variants/marisa-rump-size-trials/flee-comparison.gif) · [动作归档目录](archives/ACTION-CATALOG.md)。原动作先归档，五版新比例独立保存，尚未选定。

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

- [项目分类导航](PROJECT_MAP.md)
- [动作、表情与场景资料库](art/library/README.md)
- [资产保存与恢复](docs/ASSET_WORKFLOW.md)
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
art/props/            馅料、食盆及油粮等独立道具
art/library/          分类动作/表情/场景源文件包、版本索引与校验清单
tools/project-library/ 资产整理、校验与恢复工具
wiki/                 设定、作品出处与核读记录
docs/                 开发指南和路线
.github/              自动构建、Issue 与 PR 模板
```

现有动作与美术试验已按用途保存到 `art/library/` 的无损源文件包中，原始工作路径保留。原作提取资料和逐帧渲染另存本地 `archives/`；浏览器配置、缓存与运行世界不纳入 Git。分类与恢复见 [项目导航](PROJECT_MAP.md)。后续通过 Issue → 分支 → PR 推进开发。

项目暂按 **All Rights Reserved** 管理。不是 Mojang 或 Microsoft 官方产品。
