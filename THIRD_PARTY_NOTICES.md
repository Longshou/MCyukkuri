# 来源与依赖

本项目代码与原创资产暂按 All Rights Reserved 管理，未选择开源许可证。外部依赖遵循各自许可证。

- `art/dialogue/native-u/`为本机《ゆっくり八景U》v9.8 的角色台词索引，原文与来源指纹单独保留；既有汉化与 Mod 新增内容明确区分。204 条原版选句与 84 条项目原创台词使用 AquesTalk1 f1／f2 合成，运行 OGG 在 `assets/mcyukkuri/sounds/voice/native/`；见[制作与工具使用条件](art/audio/aquestalk/README.md)。AquesTalkPlayer 程序、字典和 DLL 不随项目或模组分发，不将原句或合成输出声明为第三方开源素材。

- 动作音效与日语语音的首轮 HTS Voice Mei（CC BY 3.0）方案已被否决，相关文件已从项目删除，不在游戏资源中。未使用八景 U 录音。《油库里日记》提取的原声只作本机参考，未加入游戏资源。

- Gradle Wrapper 脚本及 Jar 来源于 [NeoForge 1.21.1 ModDevGradle MDK](https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle/tree/30cafee9cd8d7f46427ec88fa8579d49c146df9a)。Gradle 遵循 Apache License 2.0，见 `gradle/wrapper/LICENSE`；Wrapper Jar 内保留其 `META-INF/LICENSE`。
- 开发结构参考 NeoForge 官方 MDK。NeoForge 由 Gradle 下载，不随本项目 Jar 打包。
- 旧 M1 使用 GeckoLib 4.8.4（MIT License）；当前四种实体采用项目内的渲染器，不再声明或下载 GeckoLib 依赖。
- 观察笔记引用 Minecraft 原版 `minecraft:item/book` 贴图，没有复制原版贴图文件。
- `art/source` 为本项目此前制作的方块模型与重绘贴图；角色与参考作品来源见 `art/README.md` 及 Wiki，不对原作权利作额外声明。
- `wiki` 为本地整理文本，保留出处与核读边界。`wiki/art-references/` 保存作品原图与出处；其中 `game/` 是建模时收集的八景 U Sprite、《油库里日记》APK 提取资源与网页快照。这些只作研究参考，不进入模组资源包，也不声明为第三方开源素材；不包含八景 U 程序或 APK 本体。
