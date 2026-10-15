# 八景 U 原版对白与游戏接入

[原版对白检索](native-u/index.html)按种族、成体／幼语、原始场景和行号查询；实际接入的候选声音可用合成脚本在本机生成的试听页收听（不入库）。

本轮只读整理本机《ゆっくり八景U》v9.8 的 10 份角色 `serifu.csv`／`sei_serifu.csv`，得到 34,109 条非空台词、3,399 个原始分组。包括灵梦、魔理沙、爱丽丝、帕秋莉和橙；后两者的原表较短。本索引不包含全部 NPC 或事件系统文本。

原版程序集及 CSV 指纹见 [来源清单](native-u/sources.json)。原版 `yu_base.cs` 的加载逻辑默认读 `serifu.csv`；成长标记为 1 且没有「シタタラン」标记时读 `sei_serifu.csv`。因此幼语是独立原表，不能由现代日文或中文自动改写代替。

检索页中的既有中文来自 ModLab `native-dialogue.zh-CN.tsv`，只在原行 SHA-256 相符时附加，共匹配 33,664 行。它是翻译参考，不是原版日文。另有 2,015 条 Mod 独立 TSV 记录放在单独的对照视图；未将其用于本轮游戏台词，也未声称穷尽代码内新增对白。

## 本轮选用

[runtime-lines.json](runtime-lines.json)是选用制作源，保存 288 条日文与校订中文，其中 204 条来自八景 U 原版，84 条为明确标注的项目原创。五种角色的成体、幼语共十组，分别覆盖当前 56 类事件；两种家油复用对应种族的声音，年龄分支沿用实体 `youngVoice()`。

灵梦、魔理沙、爱丽丝各选用 60 条原版台词。橙与帕秋莉各有 12 条原版、42 条原创；两者原表较短，没有用其他种族原句冒充它们的台词。原创补写源为 [species-original.json](species-original.json)，来源字段、字幕键和声音键均与原版分开，补写口吻与配音仍待听审。

- 日常包括问候、进食、满足、睡眠、醒来、如厕、玩耍、学习和饰物失而复得。
- 没有直接对应原场景的 Minecraft 事件采用含义适当的原版短反应，例如分享后的喜悦；不新写日文对白来冒充原版。
- 自称占位符 `my_name` 固定选用原版 `yukkuri/base.csv` 的首个对应名字：零基索引 41「まりちゃ」、48「まりさ」、55「れいみゅ」、62「れいむ」、69「ありちゅ」、76「ありす」。没有复刻原版随机名字的全部变体。
- `【飼い主】`采用原版加载器的默认替换「にんげんさん」，中文译为「人类先生」。它不表示 Minecraft 中已经建立所有权。
- 幼语保留「ゆっくち」「しあわちぇ」「なのじぇ」等原句写法。中文保留口吻并控制可读性，不照搬既有汉化中的所有错字。
- 英文语言环境暂时显示原版日文；中文和日文有各自的完整文本。

## 制作与导出

```powershell
python scripts/catalogue_hakkei_dialogue.py --original 'E:/yukkuri/テスト版ゆっくり八景U.v9.8' --mod-reference 'E:/yukkuri/Eight景U-ModLab'
python scripts/build_aquestalk_dialogue.py --player 'E:/yukkuri/工具/aquestalkplayer/AquesTalkPlayer.exe'
python scripts/export_dialogue.py
python scripts/export_dialogue.py --check
```

第一步只读来源游戏，更新完整检索数据。第二步读取选用台词与 [语音配方](../audio/aquestalk/recipe.json)，调用本机 AquesTalkPlayer。第三步只导出选中台词及声音，完整索引不进入模组。更改选用日文后必须重新合成；导出器会拒绝台词与录音清单不一致的文件。

运行目录为 `src/main/resources/assets/mcyukkuri/`：`dialogue/catalog.json`保存选句与时长，`lang/*.json`保存文本，`sounds/voice/native/`与`original/`分别保存 204／84 个单声道 OGG，`sounds.json`登记对应字幕。来源 WAV、配方和指纹保留在 `art/audio/aquestalk/`。导出器只更新本任务拥有的对白键，保留生态歌曲等其他条目；共享总表按任务交接窗口发布，可先用 `--stage .local/dialogue-species/staged-assets` 生成待核对资源。

## 对话行为

服务器选择台词并同步台词键、起止 tick 与递增序号。每个实体只保留一句当前台词；较高优先级可打断，重复或较低优先级不会重启当前句。结束后保留短间隔。文字时长按实际声音长度加 15 tick 计算，最短 45 tick。台词不写入存档，不向聊天栏广播。选句使用独立随机源，避免改变实体的行动随机序列。

客户端在 tick 中响应新序号，按实体位置播放一个声音，使用「友好生物」音量分类；打断、死亡、静音、离开世界或结束时停止。新进入跟踪范围时不重播已开始超过 5 tick 的旧句。渲染次数不会触发声音。

气泡独立于名字标签，采用有深度遮挡的底板、边框与尖角；文本按 150 像素换行，最多 4 行，最后 10 tick 淡出。镜头距离超过 16 格、实体隐身／死亡或隐藏 HUD 时不显示；高度按体型、成长比例、帽子与自定义名字留出空间。

管理员可用 `/yukkuri say @e[type=mcyukkuri:reimu,limit=1,sort=nearest] hello`演示已登记事件；该命令仍遵守播放间隔。`/yukkuri action ... puff`同时触发原版鼓腮叫声。实际进食可选用食事开始或「むーしゃ むーしゃ」，入睡及如厕结束已有触发。

当前声音是已接入的候选，待用户听审；AquesTalk1 的选择已获认可，不等于本轮音程、每句节奏与中文译法已获确认。首轮 112 条的历史验收见 [对话框实现](../../docs/DIALOGUE_IMPLEMENTATION.md)，本轮新增物种见 [扩展记录](../../docs/DIALOGUE_SPECIES_IMPLEMENTATION.md)。
