# 家油生态演出

`recipe.json` 是探头、照卵、侧耳、惊缩与失巢回望的制作源；`build.mjs` 导出到 `yukkuri_presentation/house_ecology.json`，由现有数字骨骼片段加载器接入。动作只作用于家油根骨，自动计算每帧身体最低点以保持接地；不改家油造型、握持点或运动规则。服务器行为 ID 与起始 tick 决定播放。

```powershell
node art/source/house-ecology-presentation/build.mjs
node art/source/house-ecology-presentation/build.mjs --check
```

夜间活动、棉絮窝和卵区取自 [家油来源](../../../wiki/hakkei-reference/lore/house-nest-sources.md)。动作时长、侧耳照卵和破壳节奏属于项目原创适配，不声称是漫画逐格复原。`house_sleep`/`house_hide` 复用已选用的家油呼吸睡姿；捡取、搬运、放下及进出巢蠕动继续使用家油现有的接触动作。

预览由家油生态道具制作脚本统一生成到 `.local/previews/house-ecology/`。
