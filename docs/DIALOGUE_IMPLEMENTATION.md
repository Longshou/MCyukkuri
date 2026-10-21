# 对白、气泡与配音

服务器按事件、种族与说话档位从打包的对白目录选句，限流后同步给客户端显示头顶气泡并播放配音；对白文本三语齐全，部分句子带 AquesTalk1 合成配音。

代码：`dialogue/`（`NativeDialogue`、`SpeechTiming`、`SpeechAddress`、`SpeechCount`、`SpeechQuirk`）、`registry/ModSounds`、`client/DialogueAudio`、`client/EcologyAudio`、`entity/YukkuriEntity`（`speechTier()`、`speakCount`）、`family/FamilyLife`（`youngVoice()`）

## 1. 数据来源与导出

制作源与复现命令见 [对白入口](../art/dialogue/README.md)；原版对白检索页、来源清单与 CSV 指纹也在该目录。

| 制作源 | 内容 |
| --- | --- |
| `art/dialogue/runtime-lines.json` | 选用记录：日文、校订中文、原版来源（文件、行号、行 SHA-256）或原创标记；每种成体／幼语独立制作 |
| `art/dialogue/species-original.json` | 项目原创句（中日文、事件、依据、`audio_status`）；明确标 `project-original`，不填虚构的原版行号 |
| `art/dialogue/speech-tiers.json` | 赤油／子油档位、称呼、数数、自述与妖梦口癖台词，由 `scripts/build_speech_tiers.py` 生成；妖梦句另见 `scripts/myon_speech_lines.py` |
| `art/audio/aquestalk/` | 配音合成配方、参数与逐文件指纹（见该目录 README）；WAV 母带与合成工具不打入运行包 |

- 导出：`python scripts/export_dialogue.py`（`--check` 只读核对，不启动游戏、不合成）。输出运行总表 `src/main/resources/assets/mcyukkuri/dialogue/catalog.json`、`lang/{zh_cn,ja_jp,en_us}.json` 的对白键和声音文件登记；导出时保留其他任务的语言键与生态歌曲声音键。
- 原版来源是《ゆっくり八景U》v9.8 的角色 `serifu.csv`／`sei_serifu.csv`；幼语是独立原表，不能由现代日文或中文自动改写代替。原版索引之外的 Mod 对照不用作选句。
- 原版句使用 `dialogue.mcyukkuri.native.*`（文本键）与 `voice.native.*`（声音键）；项目原创使用 `dialogue.mcyukkuri.original.*` 与 `voice.original.*`。目录条目形如 `marisa.adult.l0013`：种族 + 档位 + 原表行号（原创为 `o_<事件>`）。声音文件在 `assets/mcyukkuri/sounds/voice/native/` 与 `original/`，运行格式为 32 kHz 单声道 OGG。
- 目录行字段：`id`、`key`、`origin`、`species`、`age`（档位）、`events`、`ticks`（气泡生命周期，取配音时长）、`sound`、可选 `quirk`。英文环境下，有英文译文的句子显示英文，其余暂沿用日文（导出政策）。
- 原版 `nuzzle` 事件在灵梦、魔理沙、爱丽丝上使用原 CSV 一基行号 3291，`want_nuzzle` 使用 3302；`nuzzle_done`、`leisure_done` 复用满足反应，`exercise` 复用学习开始反应。这些是 Minecraft 事件与选句的映射，不代表八景原版使用同一事件系统。
- 自称占位符 `my_name` 固定取原版 `yukkuri/base.csv` 的首个对应名字；`【飼い主】` 采用原版加载器默认替换「にんげんさん」（中文「人类先生」），不表示已建立所有权。

## 2. 选句与气泡

- `NativeDialogue.select(species, tier, quirk, event, random)` 按 `种族/档位/事件[/口癖]` 索引取句；`NativeDialogue.priority(event)` 给出事件优先级（紧急求救／受伤最高，日常闲聊为 0，大量商店、考试、徽章社交事件为 2）。选句不退回其他种族的声音。
- 档位由 `YukkuriEntity.speechTier()` 按生命阶段给出：赤油（`Stage.BABY`）`baby`、子油 `juvenile`、成体 `adult`；因药物或幼声标记保持幼声的成体为 `young`。是否幼声由 `FamilyLife.youngVoice()` 决定（非成体、`juvenileVoice` 或 `babyTalk` 为真）。缺档回退：baby→young、juvenile→young、young→juvenile；成体不借用幼体台词。`young` 档（八景原句与既有原创）是兜底。幼语不等于生理幼体，`young` 表保留家庭与生育反应，不能据此推断子体可以繁殖。
- 家油（`house_reimu`、`house_marisa`）通过 `dialogueSpecies` 复用灵梦／魔理沙的年龄分支。
- 限流 `SpeechTiming`（服务器端瞬态状态，不入存档）：优先级 0 的闲聊结束后静默 200 tick（10 秒）、优先级 1 的日常互动静默 120 tick（6 秒）、更高优先级 30 tick；新的发言只能被不低于当前优先级的打断。优先级低于 2 的发言还会检查 16 格内其他油库里，附近有人正在说话或刚结束不足 80 tick（4 秒）时一并跳过语音与气泡、不排队补播，被拦截者错开 40–80 tick 后再试。优先级 2 以上的进食完成、治疗、考试、受伤、求救等不受附近闲聊限制；学舌情景允许在老师说话时模仿。生态歌声是独立声音通道，不走限流。
- 客户端按序号播放实体声音；头顶面板带边框、尖角、换行与淡出，气泡时长随配音时长。

### 腾空感叹

`StatusLife` 观察实际位移：离地至少 4 tick、存在上升过程，且比起点升高至少 1.75 格时尝试 `airborne` 台词。普通跳跃和蹦床的单次小跳达不到该高度；同次腾空成功说出后不重复，两次成功发言至少间隔 600 tick（30 秒）。该观察不改变速度，也不启动行为。感叹使用优先级 2，能打断日常闲聊，仍让位于受伤、惊恐和求救；乘船、水中、受伤瞬间、火烧、威胁、救援与出生照护期间不触发。

措辞依据 [L36 飞起感叹](../wiki/hakkei-reference/dialogue/common-phrases.md)，27 条物种和年龄适配句按项目原创字幕登记，尚未配音。试玩入口为“腾空与小跳”。

## 3. 配音与声音注册

- `registry/ModSounds` 在模组加载时读取 Jar 自带的 `assets/mcyukkuri/sounds.json`，按事件键排序后经 `DeferredRegister<SoundEvent>` 注册，并在 `MCYukkuri` 中接入 mod event bus。通用端只读打包资源，不引用客户端类；新增配音只要随模组更新 `sounds.json` 就进入同一注册流程，没有另一份编号表。`ModSounds.event(path)` 取已注册声音，`paths()` 列出全部事件键。
- `DialogueAudio` 与 `EcologyAudio`（客户端）使用注册后的声音单例。`VoiceScale` 根据成长缩放、实体缩放和物种模型比例计算声音：身体越小，音量越低、音高越高；标准成体为基准，家油的 0.32 模型比例也参与计算。音量系数为体型平方根，限制在 0.25–1.2；音高为体型的 −0.18 次方，限制在 0.85–1.65（项目初值）。降低音高时延长客户端对白播放期限，新发言仍立即替换旧发言。
- 事件 ID 与 OGG 路径保持原样，资源包可用同名声音定义或文件替换内容；要用自己的声音列表完全替代某事件，按 Minecraft 声音定义设置 `"replace": true`。注册身份在启动时固定，资源重载只更新声音定义与文件。参考 [NeoForge 1.21.1 声音文档](https://docs.neoforged.net/docs/1.21.1/resources/client/sounds/)。
- 检查声音：`/playsound mcyukkuri:ecology.song.reimu neutral @s ~ ~ ~ 1 1`（生态歌声）、`/playsound mcyukkuri:voice.native.marisa.adult.l0013 neutral @s ~ ~ ~ 1 1`（对白）。
- 配音引擎为本机 AquesTalk1，试听页可用合成脚本在本机生成（不入库）。声音参数是本项目候选值，不是《油库里日记》作者的合成设置。
- 捕食种（蕾米莉亚、芙兰）与妖梦、赤油／子油档位、众多原创事件没有配音：对应目录行 `sound` 为空，只显示字幕，不借用其他角色音轨。

## 4. 各种族说明

| 种族 | 原表 | 内容 |
| --- | --- | --- |
| 灵梦 | 八景 U 原表 | 原版句为主，成体与幼语分别制作；含擦脸、`nuzzle` 等原句 |
| 魔理沙 | 八景 U 原表 | 同上；另有水边、帽船、辫子救援等原创句与配音 |
| 爱丽丝 | `arisu` | 原版句，成体与幼语分别制作 |
| 橙 | `tilen` | 原表较短：问候、待机、受伤等用原句，进食、睡眠、学习和部分日常需求由项目另写（原创） |
| 帕秋莉 | `patyu` | 同橙；原创句另含 `heat_*`、`aftermath_realize`、`rapem_*` 等 |
| 蕾米莉亚、芙兰 | 无（全原创字幕） | 见下 |
| 妖梦 | 无（全原创字幕） | 见 §5 口癖，说明在 [RARE_FORMS.md](RARE_FORMS.md) |

**捕食种**：蕾米莉亚与芙兰各有成体、幼语两组原创短句，覆盖问候、待机、食物、清洁、睡眠、受惊、受伤、饰物、学习、亲近与家庭等日常事件，另有商店、徽章、请求等通用事件，以及捕食种专有的 `predator_restrain`、`predator_obey`、`predator_defy`。不为捕食种添加家油搬运、孵卵或普通群落处分事件。

- 写作依据：E03《ゆっくり観察日記》社区对白表（[atwiki 页](https://w.atwiki.jp/ykansatu/pages/12.html)）只参考 `うー` 短鸣、日常招呼和玩耍邀请，该页有版本差异，其状态、等级、亲属称呼不是本项目通用设定；EH02-004 漫画页（[EH02](../wiki/art-references/ehentai/works/EH02.md)）只取评价眼前食物、表达个人反应的生活语气，不据此规定全族食谱或道德。整句均为本项目另写，未标作八景 U 原生 CSV。
- 写作规则沿用[种族口吻](../wiki/hakkei-reference/dialogue/species-variants.md)与[成长语言](../wiki/hakkei-reference/dialogue/growth-stages.md)：蕾米偏主动表达，芙兰偏好奇提问，不强制每只同一性格；两者都用中文拟声“呜——”，不引入吸血、不死、魔法或每句威胁捕食的口吻。幼语逐句另写，自称「れみりゃ／ふりゃん」「蕾米呀／芙呀」也是原创选择，不声称是唯一标准读音。
- 捕食种的捕食、生殖与群落规则见 [PREDATOR_IMPLEMENTATION.md](PREDATOR_IMPLEMENTATION.md)。

## 5. 特殊说话规则

由 `speech-tiers.json` 生成的台词均为项目原创、仅字幕（`sound` 为空）、三语齐全；依据 komicolle「饅饅來大講堂」11518764（婴语、称呼、名词加“先生”）、11518780（笨、边做边说、数到 4 即“好多好多”）、11518905（妖梦两种互斥口癖、帕秋莉可数过 4）。

- **赤油**：只有“油、油”拟声（帕秋莉“姆、啾”，橙“油、喵”，蕾米莉亚／芙兰“呜——”）加“饭饭、睡睡、痛痛”等极简词，覆盖日常事件，并含父母、人类的称呼。
- **子油**：半成熟说法，咬字清楚些，名词加“先生”（食物先生、头饰先生），边做边说（自称第三人称）。成体用同一套称呼与数数，种族语尾沿用既有口吻（魔理沙“贼/惹杰”等）。
- **称呼**（`SpeechAddress`）：人类按关系和好恶分 `addr_human_kind/bro/sis/owner`（人类先生、大哥哥、大姐姐、饲主先生）与 `addr_human_hostile_old/man`（臭老太婆、粪老头，仅对年长者）；家人 `addr_mother/father/partner/child/sibling_*`、`addr_leader`；捕食种 `addr_predator_remilia/flandre`；`thing_san`（“宝物先生”）。**家人称呼讲座没有列出，属项目设计**；“小贝比酱”沿用 wiki 旧译。触发点见 [种族行为差异](SPECIES.md#种族行为差异)。
- **数数**（`SpeechCount`）：`count_food / count_stock / count_children / count_peers` 加数量。普通个体 1、2、3 之后一律“好多好多”；赤油只数到 2；帕秋莉数到 9（讲座“有些可以数到 4 以上”，此处当作种族特征）。目录里每个档位预渲染对应桶（`count_food_1/2/3/many`，帕秋莉到 `_9`），调用 `YukkuriEntity.speakCount(base, quantity)` 或 `SpeechCount.event(...)`；数量为 0 不说话。
- **自述**：`narrate_steal / narrate_flee / narrate_hide`，偷东西（欺负夺宝、突袭储粮）、开始逃跑（受惊、撤退）、躲藏（威胁躲避阶段）各约 30% 说出，冷却 400 tick。
- **妖梦口癖**（`SpeechQuirk`）：两种互斥口癖 `miyo`（咪呦）与 `kinbao`（金寶，日文「ちーんぽ」）。目录行带 `quirk` 字段，带口癖的说话者只选自己那一种；口癖在个体生成时抽定并存档（`YukkuriMyon.SpeechQuirk`），父母是妖梦时 3/4 继承。台词覆盖全部日常事件、觀樓劍、炫耀采集及被炉魔理沙的冷热反应。
- **调试**：`/yukkuri say <e> <event>` 回显 `tier`、`species` 与台词键；`/yukkuri say <e> count_food 5` 按数数规则取档。

## 6. 约定

- 译法与声音仍待用户听审；原创口吻与幼语不冒充八景原句。
- 新增事件时在 `runtime-lines.json`／`species-original.json`／`speech-tiers.json` 登记，经 `export_dialogue.py` 导出，并在 `NativeDialogue.priority` 给出优先级（默认 1）。

## 7. 待补台词与配音（最后统一补）

按[2026-10-20 裁决](DESIGN_DECISIONS.md#2026-10-20-验收口径交互工具与绷带)，配音、台词与表情适配放到最后统一补；排期见[待办](TODO.md#最后统一补配音台词与表情)。下面是 2026-10-19 盘点的缺口明细。

**目标**：玩家常见的动作和长演出，每个关键时间点（开始、高潮、结束、旁观者反应）都有台词；主种族的台词有语音。

**现状**：catalog 有 277 个事件、4,556 条台词，有录音的 447 条（约 10%），全是五种主种族的成体与 young 档，而且基本都是八景原句。蕾米莉亚、芙兰、妖梦、赤油、子油的台词全部只有字幕。`YukkuriAction` 约 114 个动作是动画片段，本身不挂台词；台词挂在行为状态机上（`showReaction`、`showReactionDelayed`、`speak`），直接对齐动作时间点的只有二三十个。

**排期与声线**：全部配音相关内容按[最新裁决](DESIGN_DECISIONS.md#2026-10-21-小墓碑与配音排期)列为低优先级，之后统一完成；赤油、子油、捕食种、妖梦也在该收尾范围内。声线参数和听审到制作时一并处理，当前不再作为阻塞其他工作的用户待决项。现有参数在 `art/audio/aquestalk/recipe.json`，幼语目前仅提高音程；缺少本种录音时不借用其他角色音轨。

**高档：演出的时间点对齐（先补台词与字幕）**

| 演出 | 现在 | 要补 |
| --- | --- | --- |
| 分娩（`LABOR`、`MIDWIFE`、`BOAT_LABOR`、每胎循环） | 每胎出生说 `birth`，助产者说 `caregiver` | 阵痛、用力、陪产者鼓励、胎与胎之间、产后 |
| 离巢告别（`IndependenceLife`） | 孩子 `hello`，亲油 `gentle` | 专用的告别与目送台词，离去时、离去后 |
| 「请吃了我」（`SelfOffering`） | ask/insist/refuse/spared 已对齐 | 走近阶段和死亡瞬间；全部无录音 |
| 垂死与哀悼（`GriefLife`、`Cannibalism`） | `dying_kin`、`kin_dying`、`eat_kin`、`stench`、`sacrifice` | 全部无录音；`dying` 也无录音 |
| 落水与自救（`AquaticSelfRescue`、`WATER_*`） | 魔理沙有录音 | 其他种族的录音；挣扎、喘气、恢复各时间点的台词 |
| 辫救（`BraidRescue`）、帽船警报、初航 | grip 有录音；警报与初航只有魔理沙有 | offer/safe 的录音；HOLD/SETTLE、BOARD/DRIFT 阶段的台词 |
| 发情与素鸡立（`AgitationLife`） | 对齐最好 | `rapem_hunt/alarm/moan/buffet/review_*` 无录音；`rapem_act` 只有爱丽丝有录音 |
| 进食与饮水 | 基本对齐 | 吃到高潮时的「しあわせー」、饮水；每档只有一条，要多备几句 |

**给已有字幕配音（主种族成体与 young）**：185 个事件没有任何录音（其中约 40 个是数数事件），优先补：考试 `exam_*`、徽章 `badge_*`、商店与相亲 `shop_*`/`date_*`、狩猎 `hunt_*`、篮子 `basket_*`、聚落与头饰事件。

**中档**：舔舐动作的专用事件（现在借用 `nuzzle`/`gentle`）、越冬与秋储（现在没有台词）、帽子脱戴、被抱起（`house_pickup` 只有魔理沙有）、发现食物。

**低档（不计划逐一补）**：伸展、转身、跳、摇、四处看、体操等装饰动作，以及受惊、发抖、逃跑、乞求、闹别扭、得意等。后一组台词已有，只需检查与动作是否对齐。

**做法与约束**：

1. 台词登记到 `art/dialogue/runtime-lines.json`、`species-original.json` 或 `speech-tiers.json`（`scripts/build_speech_tiers.py` 生成），三语齐全，原创句标 `project-original`，不冒充原版行号。
2. 新事件在 `NativeDialogue.priority` 给优先级；在动作或阶段代码里用 `showReaction`、`showReactionDelayed`、`speak` 按 phaseTicks 对齐。
3. 配音：把条目 ID 写进清单，运行 `scripts/build_aquestalk_dialogue.py --ids-json`（需要本机 AquesTalkPlayer），再运行 `scripts/export_dialogue.py` 并 `--check`。
4. 在试玩场加场景（`scripts/museum_scenarios.py`）让用户听审。声音参数是项目候选值，以用户听审为准。
5. 选句不跨种族借用，成体不借用幼体台词。
