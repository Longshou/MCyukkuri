# 八景 U 原版对白与游戏接入

[原版对白检索](native-u/index.html)按种族、成体／幼语、原始场景和行号查询；实际接入的候选声音可用合成脚本在本机生成的试听页收听（不入库）。

本轮只读整理本机《ゆっくり八景U》v9.8 的 10 份角色 `serifu.csv`／`sei_serifu.csv`，得到 34,109 条非空台词、3,399 个原始分组。包括灵梦、魔理沙、爱丽丝、帕秋莉和橙；后两者的原表较短。本索引不包含全部 NPC 或事件系统文本。

原版程序集及 CSV 指纹见 [来源清单](native-u/sources.json)。原版 `yu_base.cs` 的加载逻辑默认读 `serifu.csv`；成长标记为 1 且没有「シタタラン」标记时读 `sei_serifu.csv`。因此幼语是独立原表，不能由现代日文或中文自动改写代替。

检索页中的既有中文来自 ModLab `native-dialogue.zh-CN.tsv`，只在原行 SHA-256 相符时附加，共匹配 33,664 行。它是翻译参考，不是原版日文。另有 2,015 条 Mod 独立 TSV 记录放在单独的对照视图；未将其用于本轮游戏台词，也未声称穷尽代码内新增对白。

## 本轮选用

[runtime-lines.json](runtime-lines.json)是选用制作源，保存 502 条日文与校订中文，其中 204 条来自八景 U 原版，298 条为明确标注的项目原创。五种基础角色的成体、幼语共十组；两种家油复用对应种族的声音。另有蕾米莉亚、芙兰四组原创字幕，各覆盖 50 类日常事件，年龄分支均沿用实体 `youngVoice()`。

2026-10-16 家油生态增加12条明确标注的原创字幕：灵梦、魔理沙成体的听卵、惊扰、失巢与破壳，以及两种幼体的惊扰、失巢反应。制作源与导出已同步，尚未配音；既有288条录音不变。

原有选用中，灵梦、魔理沙、爱丽丝各有 60 条原版台词。橙与帕秋莉各有 12 条原版、42 条原创；两者原表较短，没有用其他种族原句冒充它们的台词。原创补写源为 [species-original.json](species-original.json)，来源字段、字幕键和声音键均与原版分开，补写口吻与配音仍待听审。

2026-10-16 为失饰社会后果新增五种角色各 10 条原创字幕（成体与幼体各五个事件）：冷落、驱赶、指派清扫、退让留群、自卑。每条都在上述原创制作源登记；幼体逐句按[成长语言规则](../../wiki/hakkei-reference/dialogue/growth-stages.md)及[种族口吻](../../wiki/hakkei-reference/dialogue/species-variants.md)显式改写，表达眼前的拒绝、清扫、陪伴或寻找饰物需求，保留“惹杰／なのじぇ”“瓦卡呜哟／瓦卡呐奈哟”等年龄口吻；这些是项目原创幼语，不冒充八景原句。新字幕尚无配音，既有 288 条录音不变。原文与游戏规则边界见[群落实现](../../docs/SETTLEMENTS.md)。

2026-10-16 为宠物店、徽章考试与家庭课程新增 392 条项目原创字幕：灵梦、魔理沙、爱丽丝、橙、帕秋莉、蕾米莉亚、芙兰七种角色，成体与幼语各 28 条，覆盖 21 个事件（考前紧张、答题、不确定、躲猫猫反串、等点心、抗议插队、报告野良、道歉、合格、不合格、炫耀徽章、展箱求买、被买走、被卖回、嫌弃旧家、野良役乞讨与挑衅、课后被夸与被骂、羡慕与钦佩高阶徽章），其中考前紧张、等点心、抗议插队、合格、不合格、展箱求买、被夸七类各两句。幼语逐句另写，家油复用对应种族的声音。全部仅有字幕（`sound` 为空），未运行配音合成；依据登记为 M02 考试场景、EH25 结果反应、L19 羡慕、R2 商店母题，不冒充八景原句。事件优先级见 `NativeDialogue.priority`：考试、商店与野良役事件为 2，课程与徽章羡慕／钦佩沿用默认 1。

2026-10-17 为相亲、繁殖许可、徽章佩戴、运输篮、弹珠汽水喷雾与野生请求新增 208 条项目原创字幕（未配音，`sound` 为空），七种角色成体与幼语各覆盖 12 个事件（`date_meet`、`date_fond`、`permit_glad`、`courtship_held`、`badge_pinned`、`badge_taken`、`spray_drowsy`、`basket_refuse`、`basket_enter`、`basket_exit`、`wild_plea_keep`、`pet_report_owner`，其中 `spray_drowsy`、`basket_refuse` 各两句），另有蕾米莉亚、芙兰专有的 `predator_restrain`、`predator_obey`、`predator_defy`。这些事件优先级均为 2。本批台词带 `english` 字段，英文语言环境显示英译；此前的条目仍保留日文。设计见[宠物店方案](../../docs/PET_SHOP.md)。

2026-10-17 为零食、日常请求、问心情、胴带牵引散步与襁褓新增 224 条项目原创字幕（未配音，`sound` 为空）：灵梦、魔理沙、爱丽丝、橙、帕秋莉、蕾米莉亚、芙兰七种角色成体与幼语各 16 个事件（`snack_eat`、`snack_greedy`、`snack_refuse`、`ask_food`、`ask_snack`、`ask_play`、`ask_walk`、`request_thanks`、`request_sulk`、`mood_good`、`mood_ok`、`mood_bad`、`harness_on`、`harness_tug`、`walk_enjoy`、`swaddle_on`），带英文字段；幼语逐句另写。事件优先级均为 2（`NativeDialogue.priority`）。设计见[宠物店方案 §12](../../docs/PET_SHOP.md)。

- 日常包括问候、进食、满足、睡眠、醒来、如厕、玩耍、学习和饰物失而复得。
- 2026-10-16 捕食种增加 152 条原创中日字幕，尚未配音。`うー／呜——`只参考版本特定游戏样例；漫画只参考个体日常语气。来源、幼语边界和本轮日语补全见[捕食种对白](../../docs/PREDATOR_DIALOGUE.md)。现有 288 条录音与 290 个声音登记不变。
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

第一步只读来源游戏，更新完整检索数据。第二步读取选用台词与 [语音配方](../audio/aquestalk/recipe.json)，调用本机 AquesTalkPlayer，属于另行安排的配音步骤。第三步导出选中台词和已有录音；未配音的原创条目只导出字幕，`sound` 为空。完整索引不进入模组。更改已经配音的日文后必须重新合成；导出器会拒绝台词与录音清单不一致的文件。本轮使用命令行生成水上情景配音，未运行界面工具或回归检查。

运行目录为 `src/main/resources/assets/mcyukkuri/`：`dialogue/catalog.json`保存选句与时长，`lang/*.json`保存文本，`sounds/voice/native/`与`original/`分别保存 204／134 个单声道 OGG，`sounds.json`登记对应字幕。来源 WAV、配方和指纹保留在 `art/audio/aquestalk/`。导出器只更新本任务拥有的对白键，保留生态歌曲等其他条目；共享总表按任务交接窗口发布，可先用 `--stage .local/dialogue-species/staged-assets` 生成待核对资源。

## 对话行为

2026-10-17 水上情景补写 58 条明确标注的原创台词：魔理沙成体／幼体各 17 条，分别描述惊吓、召回、回应、撤离、平复、求救、翻帽、抓檐、攀爬、滑脱、获救、辫救和空帽漂走；其他六种族成体／幼体补呼救与抓辫回应。取消 `NativeDialogue` 的旧事件别名，直接选择情景、物种与年龄匹配的条目。已用用户提供的 AquesTalkPlayer 生成 50 条，其中魔理沙 34 条齐全；蕾米莉亚与芙兰仍无既定声线，这 8 条保留字幕。召回与应答优先于一般惊吓句；亲油发声成功后才通知听距内孩子。连续预览使用同一批 OGG 和真实中文字幕，缺少录音时独立展示字幕。

服务器选择台词并同步台词键、起止 tick 与递增序号。每个实体只保留一句当前台词；较高优先级可打断，重复或较低优先级不会重启当前句。结束后保留短间隔。文字时长按实际声音长度加 15 tick 计算，最短 45 tick。台词不写入存档，不向聊天栏广播。选句使用独立随机源，避免改变实体的行动随机序列。

客户端在 tick 中响应新序号，按实体位置播放一个声音，使用「友好生物」音量分类；打断、死亡、静音、离开世界或结束时停止。新进入跟踪范围时不重播已开始超过 5 tick 的旧句。渲染次数不会触发声音。

气泡独立于名字标签，采用有深度遮挡的底板、边框与尖角；文本按 150 像素换行，最多 4 行，最后 10 tick 淡出。镜头距离超过 16 格、实体隐身／死亡或隐藏 HUD 时不显示；高度按体型、成长比例、帽子与自定义名字留出空间。

管理员可用 `/yukkuri say @e[type=mcyukkuri:reimu,limit=1,sort=nearest] hello`演示已登记事件；该命令仍遵守播放间隔。`/yukkuri action ... puff`同时触发原版鼓腮叫声。实际进食可选用食事开始或「むーしゃ むーしゃ」，入睡及如厕结束已有触发。

当前声音是已接入的候选，待用户听审；AquesTalk1 的选择已获认可，不等于本轮音程、每句节奏与中文译法已获确认。首轮 112 条的历史验收见 [对话框实现](../../docs/DIALOGUE_IMPLEMENTATION.md)，本轮新增物种见 [扩展记录](../../docs/DIALOGUE_SPECIES_IMPLEMENTATION.md)。
