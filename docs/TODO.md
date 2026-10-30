# 待办

只放未完成项，完成即删。“陆”“水”是原条目编号，只增不重排；括号内为用户原编号。状态只用[约定](../AGENTS.md#试玩与完成标准)六种。美术制作统一交 Opus；规则在所属专题，这里登记现象、版本与完成标准。已接入项集中在[待试玩清单](#待试玩清单)。

## 进行中

开工前登记，合入后移除；已登记区域先协调，不同时改。

| 条目 | 工作树 | 主要改动文件 |
| --- | --- | --- |
| 陆地接生收尾：父油全程守候、站位在帽外、按够及选嘴或辫子、赤油产后爬向父油、叼起越过帽檐、不原样重试、台词修正 | `.worktrees/fix-birth-cleanup` | `family/LiveBirth`、`Fetch`、`Unload`、`motor/action/Grab`／`TakeProp`、`headwear/`、宿主上的 `Walk`、对白选择 |
| 重构 WP9：寻路认识实体障碍并会绕行 | `.worktrees/wp9-pathing` | `entity/YukkuriGroundNavigation`、`life/SurfaceApproach`、地面 `Walk`、`motor/geom/` 绕行纯函数 |

## 玩法待办

### 复测失败/待修复

- **帽子与帽上乘员交互**：已接入待试玩。此前主手空手点击无反馈（版本待核实）；E8 陆地接生射线命中帽实体返回 `cradle_status`，赤油被遮住。完成：`birth_land_caught`、`birth_stem_caught` 可直接照护帽中赤油，点空白帽檐仍有状态反馈；`cargo`、`shake_capsize` 保留储粮与推晃；`[hat-input]`、`hat use received/result` 显示物资只扣一次。
- **陆 33 迁徙、安家与途中断粮**（46）：复测失败。2026-10-20 多轮 `colony_migrate` 幼油饿停、住所仍指旧窝、重复献身与批量死亡。版本、现象与完成标准见[日志问题 1](#日志问题-1群体迁徙断粮与连锁死亡陆-33)；运输演出并入演出包。
- **水生魔理沙模块**：复测失败。1325c6bd 首轮试玩下水、找回帽子、扶正、跳出帽船与亲油施救均阻塞；见[水生魔理沙模块（重构）](#水生魔理沙模块重构)。
- **接生收尾、入窝与赤油取食（E11）**：1、2 已接入待试玩（WP1，[12774ca1](https://github.com/Longshou/MCyukkuri/commit/12774ca1)）；3 已接入待试玩（WP2，[9822228e](https://github.com/Longshou/MCyukkuri/commit/9822228e)）。契约见[代码架构](ARCHITECTURE.md#身体动作与意图层)。
  1. 父油把赤油从接生帽放到地上后又放回帽里：`01-land-catch` 03:25:20.158 `transfer failed … why=land_lift reason=contact_lost class=SLIP`。
  2. 宠物窝入睡判定太宽：身体边缘蹭到窝沿即入睡，像浮在窝上。
  3. 赤油极饿取食一卡一卡：`04-infant-feed` 03:28:13–03:28:46 每 100 tick 一次 `ground path stopped reason=no_progress`，每次约 0.1 格；`food found` 在 elapsed=601、1201 重复。
  完成：`birth_land_caught` 每只赤油各有一条 `[body] dismount cause=release`（或 `[action] start id=hop … owner=leave_host`），之后不再 `mount host=<接生帽>`，全部离帽后才收帽；宠物窝入睡 `[sleep] begin … occupancy=INSIDE_SUPPORTED`，窝沿个体先走进去再睡；`infant_feed` 极饿赤油连续走到食物、无 `no_progress`、`food found` 不重复。

### 家庭、繁殖与照护

- **新生儿一律赤油、体型各异**：已接入待试玩。E8 茎生 `STEM JUVENILE scale=0.2` 大于胎生 `LIVE BABY scale=0.14`；E9 未复测出生链。完成：`birth_stem_caught`、`birth_land_caught` 每只新生儿 `stage=BABY`，同胎基础 `scale` 各异且在赤体区间；特殊形态成熟实子落地不变大，重载不重抽（[体型规范](BEHAVIOR.md#体型规范)）。
- **陆 27 繁衍剩余缺口核对**（43）：未做。对照 Wiki 列清求偶到育儿真正未实现的环节；迁徙照护、宠物许可、树叶襁褓按已有条目处理。

### 宠物、商店与人类关系

- **陆 9 后半：自家宠物配对与繁殖许可**：未做。规则见[许可／发情期例外](PET_SHOP.md#5-繁殖与家谱展示)；`PetLife.courtshipGate` 未限定发情期、未纳入智力。完成：正常、获许可、发情期联合判定通过／未通过及概率未受孕，核对配对、受孕和违规档案；店方相亲保留原待试玩条目。
- **陆 34 普通商店重做**（10）：部分接入。已修缺门、分大小店、全玻璃展示柜、室外放风场。余：店员即店长，稳定照料在售个体并回收逃出者；主卖赤油、子油；按[库存规则](PET_SHOP.md#63-相亲库存与工厂出货)定时补货，撤除走失与补货委托；页签和“售罄”图标；可选自荐求购演出。完成：长时试玩库存、照料、交易稳定。
- **陆 39 宠物油生活**（15）：部分接入。参考 Wiki 按系统闭环梳理剩余缺口；配对许可、商店、树叶襁褓不在此重复。
- **陆 41–43 人类关系与交互**（19、25、26、30）：未做。态度由油库里数值决定；对人默认关系偏低，当前过于主动打招呼，被打招呼应回礼；非语言功能改用 MC 原生交互，只有语言功能进界面。
- **陆 44 家油躲人**（25）：未做。在隐蔽角落筑巢，只有高利己个体挑衅人类；天真个体向人讨东西，被其他油劝走或拉走。

### 场景、信息与读数

- **小墓碑**：未做。安葬后显示小墓碑，右键像告示牌看基本信息与死因，重载对应同一死者（[遗体后续](INJURIES_AND_REMAINS.md#同伴照护与遗体)）。
- **陆 12 群系融入／陆 13 后半真实山地**：未做。沙漠、恶地聚落的土堆和木头融入群系；保留真实山地环境与自然演出。

#### 试玩场读数缺口

原登记清单，先对照场景源码核实再补：`sleep`、`meet`、`meal`、`toilet`、`hurt`、`hop`、`hurt_kin`、`thunder`、`graze`、`wild`、`exam`、`date`、`feed_wild`、`colony_ration`、`marisa_sumo`、`factory` 三个；`last_words` 原只读孩子，缺走向死亡的亲油。读数随行为链补齐。

### 演出缺口

未做演出最后与配音一起补；已有表现仍单列待试玩；不能用表现代替实际成功。树叶襁褓演出归本包，状态在[出生与成长](#出生与成长)。

- **出生落点演出**：未做。随机弹道已接入，缺飞出窝外惊叫、落地扬尘弹跳、碰帽檐弹进帽声音、母油目送；`LiveDelivery` 欢迎／漏接字幕按 `outcome`（窝垫、窝沿滚入、帽中、飞出窝外、落帽外）区分。
- **生殖行为演出**：已接入待试玩。擦脸前段、父方绕后、佩妮佩妮／马姆马姆、共享接触节拍与粒子；爱丽丝追击保持佩妮佩妮，受方压制显示马姆马姆。完成：`adult_pairing` 正侧背面看清回应、擦脸、绕后、接触，`home.family_state` 的 `court=` 中 `align` 不计有效接触、`contact` 后父母角色与账本一致；`alice_hunt` 追击器官不消失；`alice_interrupt` 喷雾、受击或遮挡后只收势。
- **食尸、伴侣与请吃了我演出**：部分接入。丰富 `carrion` 食尸、伴侣反应与 `offering` 献身请求演出，只跟随实际数值。完成：看清食尸吞咽、伴侣的选择及献身请求、回应、交付；与陆 24 分体统一收尾，不标记数值通过。
- **陆 14 雨天与雷暴演出**：未做。补普通雨不安、雷暴更强不安、寻找掩体、干燥区恢复休闲／觅食、很饿冒雨觅食的表现，随真实状态。机制见待试玩陆 14。
- **陆 5 暖桌魔理沙**（2，`kotatsu_winter`、`kotatsu_summer`）：部分接入。被炉帘布造型已补待视觉确认；冬天应不觉冷、不进洞冬居，夏天白天回棚下睡，现看不出区别；读数冬夏不同只补演出，相同按数值问题修。
- **陆 17 妖梦咬刀**：已接入待试玩。[ac4ba87b](https://github.com/Longshou/MCyukkuri/commit/ac4ba87b)（CI `40196337976`）`myon_brave` 拔刀交战时观楼剑在身体下方不在嘴里（此后空口持剑也逐帧刷新挂点，待复测）。完成：`myon_stick` 木棍正侧面横贯嘴部，走动跳动转身不脱离；`myon_brave` 交战、追击、受击、退战全程不在身体下方或原点，不与他只串位。
- **陆 22 茎生尊重重力**（7）：复测失败。[dd75d650](https://github.com/Longshou/MCyukkuri/commit/dd75d650)（CI 40200352271，2026-10-25 22:24–22:38）`birth_stem_caught`：用户问茎为何左右歪、实子是否过大（核对 0.10→0.20 未超标，见[体型规范](BEHAVIOR.md#体型规范)）；子油头顶小茎仍左右斜（版本待核实），现改整根竖直悬下。完成：`aftermath_growth`、`birth_stem_caught` 母体静止小茎竖直，走动跳跃轻摆、停稳恢复；各视角无固定左右斜伸，子代不穿主茎、母体或同胞，出生从悬挂处脱落；剪下／装瓶的茎同样竖垂（[挂法](../art/props/family-life/README.md)）。
- **陆 24 献身分体**（37）：部分接入。现 GIVE 只停步、注视、低垂后进中枢濒死，遗体完整压低。要从中间劈成两半，横切面按构造分层，中枢馅／线也分两半，对应同一遗体资源；入口并入 `offering`。完成：分体可见、资源不重复（[受伤与遗体](INJURIES_AND_REMAINS.md)）。
- **亲代运输演出**：已接入待试玩（[规则](SETTLEMENTS.md#运输补给与掉队)）。口含（灵梦多只沿嘴横排）、魔理沙帽檐就座、橙两尾平托；孩子随转身步伐轻摆，接起与放下时亲油俯身。完成：`colony_migrate_babies` 亲油俯身再抬起；灵梦嘴前多只互不穿插；魔理沙帽檐孩子不浮不陷，满后再来的叼嘴上；橙两尾各卷一只；放下平稳落地；1.0 与 1.2 体型各看。未做：被抬孩子的表情与对白；口含放下落点由服务端定，与嘴前位置远则仍滑移。

### 最后统一补：配音、台词与表情

**未做项最后统一完成。** 所有配音（赤油、子油、捕食种、妖梦、声线参数、听审与声音适配）排到收尾，不单独开工，也不因缺语音判玩法失败；台词、表情与演出一并收尾；陆 48 体型调音、家油听感、陆 41 人类交互台词并入。完成：在原试玩场核对关键动作的台词时机、角色与年龄声线、体型调音、表情及三语显示，用户听审确认（[对白与配音](DIALOGUE_IMPLEMENTATION.md)）。

### 美术（Opus）

用户说“保存 xx”即登记为标准；制作源、快照和 `art/runtime-sources.json` 同步（[入口](../art/props/README.md)，[照料道具制作源](../standards/yukkuri-library/drafts/care-props/README.md)）。本节只留独立制作缺口。以下均已接入待试玩，外观待用户确认，确认后“保存 xx”登记认可。

- **新生儿第一句问候**：未做。设定中赤油出生后第一句是赤油口吻的「ゆっくちしていっちぇね！」（经典问候），现播「ゆっ、ゆっ！」；亲属回应「ゆっくり していってね！」已接入。另缺父油自己戴回帽子时的台词。完成：`birth_land_caught` 等出生场景每只新生儿初醒说出赤油口吻的问候，亲属随后回应。
- **佩妮佩妮圆润外形**：五段方柱改 8 环 10 边圆润轴体。看 `adult-pairing`、`alice-rapem` 侧面接触，`shared_body` “伸出佩妮佩妮”近景；各形态不恢复石笋状分段，根部不穿体、尖端不过长。
- **野生巢与储粮堆重做**：叶床、个人小叶巢、叶束、2×2 野生大窝、陆上储粮堆统一手绘原版风格。看 `den_family`、`forage` 叶床与储粮堆随存量四档变化；`birth_nest_safe` 野生大窝（成体与赤油在窝内、正面开口）；后期子体自建小叶巢三阶段（`den_family` 倍速）；成体站在叶床与小叶巢中围沿不穿身体。
- **宠物照料用品造型与款式**：食盆（空与油粮／小麦／短草 1–8 份）、软垫窝、2×2 编织大窝、沙厕（干净／脏）、陶厕、带盖厕与便堆；放置后手持染料右键换款式（[容纳](CHILDHOOD_LIFECYCLE.md#大巢小巢与洞穴是不同的东西)）。用户反馈成体卡软垫窝窝沿悬空（版本待核实）；[dd75d650](https://github.com/Longshou/MCyukkuri/commit/dd75d650)（CI 40200352271）软垫窝对足月孕体太小被拒收（已放大窝与厕）。已知缺口：魔理沙辫尖和鬓发垂到软卷高度时插进约 1（1.2 倍约 2.6）。看 `sleep`（成体坐进垫面，身体底贴垫面）、`family_nests`、`toilet`、`date`（母油窝两侧隔两格备用软垫窝，生产容量不因不紧挨拒绝）；食盆、陶厕、带盖厕在创造模式放置，食盆放 1、4、8 份，染料右键读 `[interaction] care style changed`；重载保持款式。
- **面粉糊、橙汁与饲料颗粒重做**：五件改 16×16 物品图标（[图标](../art/props/supply-icons/README.md)），“饲料颗粒”按三档油粮。看 `treatment_care`、`treatment_severe`、`treatment_scars`、`marks` 的发放物品与 NPC 持药，商店陈列，手持与展示框。
- **嗯嗯方块分档外观**：`yukkuri_poop` `tier` 1–4 对应 1–2／3–4／5–6／7–8 份（[规则](ECOLOGY_DAILY_LIFE.md#排泄修正)）；制作源 `drafts/care-props/poop_block.py`，快照 `art/source/poop-block/`；离线预览远处 1、2 档较小。看 `settle_pit` 随份数换档、`settle_pit_slave` 取食后降档，远处、坑底与平地各看；四档远处一眼可分、读作排泄物。
- **洞口结界屏障外观**：`mcyukkuri:den_barrier`（[规则](WILD_SHELTERS.md#灵梦封洞口结界)）细枝插靠成堆、格心落叶、有缝；制作源 `ecology/wild-shelters/den_barrier.py`，快照 `art/source/den-barrier/`。看 `den_barrier` 左洞灵梦封口后洞外近处与约 8 格外、洞内背面；不穿洞壁洞顶，缝里能看到灵梦。
- **水生表情与帽冠外观**（`sad`／`afraid` 表情、破损帽外观；观察并入水生模块 P0-11）：表情由 `expressions/derived/faces.py` 从已认可贴图派生，是项目设计候选，未进标准表情集合。看 `afloat_family` 孩子探头与夜里船长盖窝、幼体小帽；`sail` 翻船后漂浮帽、破损帽放成船；自救成功与警报安抚的沮丧脸、受惊的害怕脸。完成：表情写入标准登记，破损外观登记认可。
- **水中游泳动作**：未做。水中移动已接限速与碰撞扫掠，动作仍暂用 `WATER_STRUGGLE`；落水自救、返岸、救援拖行需专用游动片段。先在 `interactions/braid-water-rescue`、`aquatic-tools` 核对已有动作，有则接入，无则按[制作约定](../standards/yukkuri-library/AGENTS.md)新做。完成：`self_rescue`、`water_dock` 水里个体有可见划行动作，速度与节奏一致，用户确认。

## 低优先级

- **陆 35 鬼威惨商店**（10）：未做。售卖虐油用具、劣质虐用个体和特殊处理过的个体，后续再做。
- **陆 36 工厂重做**（6）：未做。重点是加工厂里的遭遇演出，不重机械，后续再做。

## 暂缓/不重要

保持暂缓，用户未重新提出时不开工。

- 用户不提不开工（未做）：[DOS](RARE_FORMS.md#形态边界)、[捕食种误判危险](WILD_EVENTS_IMPLEMENTATION.md#可选的误判危险事件)、[村边纠纷](HUMAN_WORLD.md)、[饲主威胁线](PET_SHOP.md)、[网络包同步和可选兼容](ARCHITECTURE.md#调试与扩展)。部分接入、现状待核对：[工厂流水线与 NPC 态度关系](HUMAN_WORLD.md)。
- 未做：[认屋成员脱离聚落、解除伴侣与强盗团离开消散](WILD_EVENTS_IMPLEMENTATION.md)；[运行资源瘦身](ARCHITECTURE.md#调试与扩展)（先测完整 Jar 大小与加载耗时）。

## 运行证据索引（2026-10-21–28）

编号只定位运行版本与反馈，日志用于诊断，不代替用户试玩验收。会话与日志在 `.local/experimental-map/`，E8–E10 副本在 `.local/reports/birth-aquatic-fix/`。

| 编号 | 运行版本 | 已确认现象／边界 |
| --- | --- | --- |
| **E1** | 10-21 22:41 至 10-22 00:04，[f4872105](https://github.com/Longshou/MCyukkuri/commit/f4872105)，[CI 39595554878](https://github.com/Longshou/MCyukkuri/actions/runs/39595554878) | 头饰、捕食、遗体／治疗及出生与成长反馈的共同运行包。 |
| **E2** | 10-22 00:10–00:39，[372fa23d](https://github.com/Longshou/MCyukkuri/commit/372fa23d)，[CI 39630804471](https://github.com/Longshou/MCyukkuri/actions/runs/39630804471) | 中枢按钮卡死、岩浆块过快致死、护尸走停、治疗持物不可见。 |
| **E3** | 00:45–01:09，[cc0e78ff](https://github.com/Longshou/MCyukkuri/commit/cc0e78ff)，[CI 39634939963](https://github.com/Longshou/MCyukkuri/actions/runs/39634939963) | 天气、捕食、睡眠与闲逛场景反馈。 |
| **E4** | 01:25:54 起 [d1cf4f1f](https://github.com/Longshou/MCyukkuri/commit/d1cf4f1f)，[CI 39641090628](https://github.com/Longshou/MCyukkuri/actions/runs/39641090628)（此前仍为 E3） | 桌面复测未再卡死且有取核／治疗日志（仅诊断）；01:29–01:38 护尸、移速反馈归属 d1cf。 |
| **E5** | 01:44–01:58，[193a87ae](https://github.com/Longshou/MCyukkuri/commit/193a87ae)，[CI 39644324457](https://github.com/Longshou/MCyukkuri/actions/runs/39644324457) | 天气、捕食、困倦、认窝与通用身体复测。 |
| **E6** | 10-25 16:08–16:09，[303d10d0](https://github.com/Longshou/MCyukkuri/commit/303d10d0)，[CI 40173988194](https://github.com/Longshou/MCyukkuri/actions/runs/40173988194) | `core_exposed` 多只伤员同屏明显卡顿，服务端无落后记录；发面伤口处头发穿透／缺口。 |
| **E7** | 16:45–16:51，[b1b7d88f](https://github.com/Longshou/MCyukkuri/commit/b1b7d88f)，[CI 40177589999](https://github.com/Longshou/MCyukkuri/actions/runs/40177589999) | `core_exposed` 全程严重卡顿；全身烧伤斑驳、侧边部件错位、泪痕浮出；前刘海爆裂伤洞口褐色污渍、断发参差；头顶深伤交界露肤色细缝。 |
| **E8** | 10-27 21:50–22:37，[ce9c0bfc](https://github.com/Longshou/MCyukkuri/commit/ce9c0bfc)，[CI 40465587203](https://github.com/Longshou/MCyukkuri/actions/runs/40465587203) | 出生与水上链路反馈。 |
| **E9** | 10-28 00:15–00:20，[7e99a8bd](https://github.com/Longshou/MCyukkuri/commit/7e99a8bd)，[CI 40536690947](https://github.com/Longshou/MCyukkuri/actions/runs/40536690947) | 自救、扶正后登船、换船与返岸仍失败；满粮救援有真实拒绝抽签，不算事件未触发；未复测出生链。 |
| **E10** | 01:52–02:02，[98038210](https://github.com/Longshou/MCyukkuri/commit/98038210)，[CI 40550269285](https://github.com/Longshou/MCyukkuri/actions/runs/40550269285) | 出生、水上与动作细节复测；下文时间为录像“屏幕录制 2026-10-28 020258.mp4”偏移，抽帧在 `.local/screenshots/birth-aquatic-20261028-020258/`。 |
| **E11** | 03:22–03:37，[2715d9c1](https://github.com/Longshou/MCyukkuri/commit/2715d9c1)，CI 待核实 | 陆地接生收尾、宠物窝入睡、赤油取食与水上自救、帽子归属回归。 |

## 实时日志发现的问题

试玩时由日志与现场读数发现的未完成问题登记在这里；新证据更新原条目。每项保留场景、运行版本、现象、状态与完成标准；版本不明写“版本待核实”；日志无记录不等于未发生。

### 日志问题 1：群体迁徙断粮与连锁死亡（陆 33）

**状态：复测失败（断粮、亲油照护各行）；亲代运输（[331427af](https://github.com/Longshou/MCyukkuri/commit/331427af)，晚于两个失败版本）与先锋、新址自建与途中卡住两行已接入待试玩。** 场景 `colony_migrate`。

- [c235e189](https://github.com/Longshou/MCyukkuri/commit/c235e189)（[CI 39427119109](https://github.com/Longshou/MCyukkuri/actions/runs/39427119109)，2026-10-20，后期 5 倍速）：多轮幼油断粮与亲油献身；23:39 一轮 4 只成体献身死亡、3 只幼油饿死，双亲几乎同时献给同一孩子；23:53:14 重开一轮至 23:56:26 有 6 只幼油饿死、2 只成体献身死亡，族长帕琪也饿死；23:54:48 饿死的 5 只幼油距新址仍约 36–48 格；23:47 快照两只幼油已到新址附近，营养 22、`idle`、住所仍指旧窝，双亲已认领新窝、营养 82，23:49:42 饿死；受赠孩子后变非油症，23:56:30 触发 `non_yukkuri` 制裁流程。
- [ac4ba87b](https://github.com/Longshou/MCyukkuri/commit/ac4ba87b)（[CI 40196337976](https://github.com/Longshou/MCyukkuri/actions/runs/40196337976)，2026-10-25 22:02–22:10；日志副本 `.local/experimental-map/play-museum/20261025-211412-264.log`）：两轮（22:02、22:05）均 `migrate_failed reason deadline phase MARCH elapsed 7211／7213`；【迁徙·赤油跟亲】22:09 只到议事阶段无读数。族长帕琪（体弱）走最前，部分成员 `march route accepted` 目标是族长位置（`-733,64,478`，约 13 格），族长体力 24→8 被 `urgent_need` 拒绝停下，随队者在她身边等待。到达后无成员在新址认屋：`settled` 到 -720 为 0、无新址 `home chosen`；成员在 -721～-713 抵达、`home released` 后部分重复 `settle waiting reason=no_home`，闲逛、觅食或 `urgent_need`（体力<25）离开广场后又被拉回直到超时。超时后 `regroup route accepted` 折返约 64 格，途中数百次 `settlement goal refused ... reason=urgent_need`（营养 70–90、体力 24→9）；约 1200–2000 tick 后少数回旧窝，其余停在 x=-766～-739。

| 问题 | 完成标准 |
| --- | --- |
| 饥饿、迁徙安置与取食互相阻塞（族长饿死的具体阻塞缺读数，不并入同因） | 同场景达到行军饥饿阈值后，迁徙让位于可执行的觅食、补给或安置；幼油继续抵达、住所切新窝、取食后营养回升，不因互卡持续 `idle` 至死。族长若仍饿停，补当时 Goal、阶段与粮源读数再修。 |
| 亲油照护与献身衔接失衡（友善决策失败与物理阻塞应区分） | 有意愿且条件允许的亲油接上寻回、真实粮食交接与喂养，来源和消耗可核对；可用食物或已生效救助不被误判为绝境，双亲不对同一孩子重复献身；保留真实缺粮和性格决策。 |
| 赤油行军资格与亲代运输（已接入待试玩；[规则](CHILDHOOD_LIFECYCLE.md#子代移动与亲代运输)）。未收紧：极饿赤油越界搜索；无亲油能抬时退回爬行跟随；无往返接力 | `colony_migrate_babies`：行军开始后亲油走近孩子、孩子坐上承托位随行（魔理沙帽檐排开多只、橙两尾各一只、灵梦口含多只，座位不重叠，读 `home.family_state` 的 `carry=`、`count=`；日志 `[family] carry lift started／pickup／put down reason=…`）；孩子不再自己爬跟随；营养低于 30 时放下喂食再抬起；新址认屋后放下、住所切新窝；口含的母亲不备口粮。`colony_migrate` 同看；重载按乘客续接不复制不瞬移；配合 `infant_starvation` 核对极饿不越界。 |
| 先锋、新址自建与途中卡住（ac4ba87b；已接入待试玩） | `colony_migrate`：“迁徙与压力”读 `vanguard`、`pivot_arrived`；“全体成员安置”读 `identity.settlement_detail` 的 `result`／`vanguard`、`home.pos` 变到 -720 附近；日志 `migration vanguard`、`migration pivot arrived`、`settle started`、`settled`、`site settle found no home`（空位／被占／放置失败／拒绝认领计数）、`migrate_failed` 末尾 members／near／settled／unsettled_near 与先锋、族长距离。通过：先锋先到；多数成员新址 `settled` 并有新 `home.pos`；`migrate_done` 后中心改为目标；族长卸载时仍可由在场参与者推进；幼体新窝与亲代一致；失败原因可由计数读出，折返成员不长时间卡在半路。不以停用能力或强制到位掩盖。 |
| 群落对途中危机的响应缺口：未做。死亡计入迁徙压力，但未接停队、送粮或救援；普通日志无记录不写成“完全没反应” | 规则见[途中危机](SETTLEMENTS.md#运输补给与掉队)（停队／赶路意愿竞争、口粮交接与就地出猎、亲属自行寻回、途中死亡计入结果而不计新址压力），复用既有盘点、共同狩猎、寻回和族规动作。`colony_migrate` 读数看到停队／续行决定与意愿分值、口粮交接与拒绝、途中狩猎结果，区分集体迁徙结束与个人未安置，显示存活掉队者、粮食与死亡压力的处理，不以中心已变或仅触发制裁算通过。 |

**复测读数**：“清空本场地”后重开，于出发、幼油营养接近／低于 30、抵达及死亡前后读“读全体成员安置”“读迁徙与压力”“读新聚落状态”；对照 `needs.nutrition`、血量、Goal、`identity.settlement_detail`、`home.pos`、`home.family_state`、`home.habitat_state`、`home.offering_state`、各户／公仓粮数、`migrate_done`／`migrate_failed`。亲属死亡反应另核对距离、识别、视线与情绪；无哭喊日志不单独判失败。专题：[聚落迁徙与分粮](SETTLEMENTS.md)、[子代生命周期](CHILDHOOD_LIFECYCLE.md)、[受伤、遗体与献身](INJURIES_AND_REMAINS.md)、[情绪链](EMOTION_CHAIN.md)。

## 待试玩清单

以下为**已接入待试玩**，仍须用户用对应运行包确认；历史失败不表示新修正已验收。按场景（`scripts/museum_scenarios.py` 的 id）分组；演出问题记到[演出缺口](#演出缺口)；操作见[试玩场](DEVELOPMENT.md)。

### 生存、捕食与伤病修正

- **赤油受伤原地哭泣**：已接入待试玩。此前受伤赤油会跑动逃跑。完成：`birth_land_missed` 摔落赤油不走动、哭脸垂身（`[childhood] hurt cry start/end`），亲油赶来舔舐；攻击赤油无 `flee`，子油受击仍逃跑。
- **妖梦体力系数未生效**：已接入待试玩。源码核查 [c3033e91](https://github.com/Longshou/MCyukkuri/commit/c3033e91)（CI `39580340893`），无试玩复现。完成：`species_fatigue` 妖梦、灵梦、帕秋莉同初态，`species.fatigue.steps=20` 时代谢 `cost` 分别 18／20／25，重载不带入上次统计。
- **陆 38 捕食种腾空台词**：已接入待试玩。原生会飞的捕食种说“好像在天上飞”（版本待核实）。完成：`airborne` 普通高抛只触发一次，小跳和蕾米莉亚／弗兰均不触发。
- **固定群系下的日常储粮**：已接入待试玩。已移除秋储规划、秋季加征和过冬预留。完成：`forage`、`settle_taiga`、`colony_hunt`、`colony_ration` 读库存／公仓目标与上缴比例，秋季群系不为未来冬天囤粮或扣留公粮。
- **陆 14 避雨搜索与雨天活动**（4）：已接入待试玩。E1 找掩体偏低且避雨不应锁死活动；E3 雨伤、湿润和水生避雨缺口；E5 严重饥饿者雨中 `idle`。完成：`rain_shelter`、`rain_open`、`rain_life` 读搜索目标、遮蔽、Goal、不安、营养与湿度；无掩体仍合理搜索，干燥区恢复活动，极饿真实冒雨觅食。
- **陆 16 挡捕食种与亲油反应**（39）：已接入待试玩。2026-10-20（版本待核实）`sacrifice` 只追亲油；E1 23:17:57、23:18:22 子油→亲油转移不证明完成挡护；E3 看不清挡护；E5 01:48:18、01:48:42 噗咕后子油受咬、捕食种落地清空猎物、亲油下一 tick 中断。[ac4ba87b](https://github.com/Longshou/MCyukkuri/commit/ac4ba87b)（CI `40196337976`）21:36、21:37：亲油被挑衅后芙兰约 25 tick 咬一次（2.0），5 次杀死亲油，清场后立刻再选子油；现改捕食／战斗两态、伤害 1.5。完成：`sacrifice` 确认真实护子或退避（`behavior.protection`、血量），低意愿仍可抽中；转怒后命中间隔≥40 tick（`mode predation -> combat`）；亲油死亡后 `eat pause started; reason=combat_kill`，芙兰不起飞追猎，饿时 `remains feeding via=eat_pause` 至吃饱或遗体取尽才再选目标。
- **陆 17 妖梦战力**（40）：已接入待试玩。2026-10-20（版本待核实）妖梦击杀捕食种。ac4ba87b（CI `40196337976`）21:39、21:40：芙兰贴地连咬妖梦，约 25 tick 一口、每口 2.0，14 点血打到 4.0 退战后被补死；现每次命中 1.5、间隔≥40 tick。完成：`myon_brave` 双方都造成实际伤害，健康同阶段妖梦略弱于捕食种，按胆量退避；`combat strike` 的 `since_last` ≥40，战斗中芙兰 `nutrition` 不因命中增加。
- **饿极食尸与伴侣决策**：已接入待试玩。E2 护尸走停及 `guard_path_blocked`；E4 01:35:41 只打一次，01:35:56 食尸者返回后不再护尸；同轮 87.5 分接受、20.25 分拒食。完成：`carrion` 对照大胆攻击、胆小抗议与自愿食尸（`guardStyle`、`decision`、`score`、伤害、遗体余额），返回后仍响应，保留拒绝与能力不足后果。
- **饿极攻击失饰者**：已接入待试玩。`cannibal_hunt` 攻击意愿按饥饿、友善、自制、胆量、恐惧和能力差判分。完成：两组对照读决策、追咬目标、伤害及吞咽营养，保留真实拒绝与危险后果。
- **陆 24 请吃了我触发条件**（37）：已接入待试玩。同一受赠者连续六次检查营养≤2、生命≤50%，献身者营养≤15、友善≥70、胆量≥40、亲近非负；概率上限 65%，拒绝冷却 1200 tick，补给与照护可中止。完成：`offering` 对照绝境、低意愿和普通饥饿，观察请求、交付、中断；一般饥饿不轻易献身，不写 Goal 或成功状态。
- **陆 49 橙汁治伤、面粉糊去疤与 NPC 治疗**：已接入待试玩。E1 23:00:38、23:00:48 清伤后疤数 0，23:00:39 原有疤 1，23:02:46 橙汁 8→7、草药 7→6；E2 看不出持药，00:37:50 橙汁 8→7、00:38:35 面粉糊 7→6。完成：`treatment_severe`、`treatment_scars`、`marks` 普通伤概率留疤、大伤必留，橙汁保留旧疤且不治油霉，面粉糊只去疤；`treatment_care` 按需先橙汁后面粉糊、逐剂持物、扣余量，无效不扣料；绷带移除及旧档背包、箱内迁移仍待试玩。
- **陆 50 中枢作为通用身体结构**：已接入待试玩。E2 `core_filling`、`core_exposed` 生成后卡死无栈（已加 `[body-render]` 耗时读数）。完成：外露中枢命中立即死亡并留遗体，旁侧不致死，能取得中枢；两按钮反复生成、转视角、治疗闭口、取核不卡死。
- **低营养虚弱与饥饿掉血**：已接入待试玩。E4 01:36 看不出减速。完成：`nutrition_age` 对照移速、营养、血量和 `needs.fatigue` 计时；补食后计时归零、效果到期，饥饿仍可致死。
- **定向噗咕的数值回应**：已接入待试玩。完成：`puff_response` 四种初态待自然制裁宣告，核对 `[social] puff` 输入、选择、Goal 与移动／伤害；`carrion` 饥饿目标对护尸噗咕的反应。
- **新增演示到场传送**：已接入待试玩。E5 仍未传送。完成：`fatigue`、`fatigue_predator`、`nutrition_age` 各观察台启动均传送到样例附近，清场重开一致。
- **困倦打盹、胆量睡时与危险惊醒**：已接入待试玩。ac4ba87b（CI `40196337976`）两次打盹间隔不足 0.5 秒（现加 300 tick 最小间隔）；E5 01:52:17 入睡、01:52:20 已受咬且未找到困倦演示。口渴 `dryness` 加缓慢基础上升（成体每分钟 +1，40 起饮水）。完成：`fatigue` 两次打盹至少隔 15 秒；`yukkuri status … raw` 的 dryness 随时间上升，水边个体约 40 分钟后自行喝水不扣血；`fatigue_predator`、`sleep` 对照停步、睡眠表情、困值、危险惊醒和捕食目标，不指定 Goal。
- **成长营养需求与献身供养总量**：已接入待试玩。E2 要求幼体需求低于成体，默认消耗下献身余额可供两只子油约五天。完成：`nutrition_age` 对照需求与营养扣值；`offering` 共享真实余额与吞咽，受伤、腐败和成长仍减少可供时间。
- **空手击打无伤口反馈**：已接入待试玩。E7 `core_exposed` 空手击打只首拳留一级凹伤，重复击打 `wound unchanged`。完成：对“满血爆裂”个体背面连打同一处，第 3、5 拳分别升二、三级，`[body]` 点数前后值对应；每拳命中点有馅屑（火烧冒烟）。
- **旁观受伤语音串用**：已接入待试玩。E2 旁观者播放自己的受伤语音。完成：`hurt` 轻击一只，只有受伤者痛呼。
- **漏馅止漏演示覆盖**：已接入待试玩。E2 缺大伤口与露核对照。完成：`wound_leak` 读漏血、`core_site`、`wound_anchors`；橙汁闭口止漏并原位留疤，面粉糊仅去疤，场景不卡死。
- **无粮时主动搜索（E3 反馈6）**：已接入待试玩。ac4ba87b（CI `40196337976`）21:35 搜索与 `seek_food` 是两段状态、表情不对，被攻击或雨中觅食仍慢悠悠。完成：`forage_empty` 空手饥饿灵梦流口水并游荡；手持苹果走近立即走向玩家（`search met player holding food`），丢下后 `search saw loose food` 再吃；雨天干燥区活动与攻击后 `[urgency] urgency speed on`、`move_speed` 约 ×1.3，结束 off；无粮不假装取食。
- **捕食种／水生种通用身体与地面动作（E5 反馈3、4）**：已接入待试玩。E5 捕食种缺油菊、伸缩与步态仍旧。完成：`shared_body` 四种对照核对油菊、互斥伸缩、通用步态及落地后真实行走。
- **岩浆块烫伤脚底**：已接入待试玩。E2 伤害过快；E4 改为原版受伤频率、受伤后降低跳高并逃向真实安全地面。完成：`magma_sole` 健康个体从四格岩浆块中心活着离开，石头对照不烫伤，离开后不再新增接触伤。
- **野生灵梦入巢结界**：已接入待试玩（项目设计，用户要求；[规则](WILD_SHELTERS.md#灵梦封洞口结界)）。智力与自制都≥70 的野生灵梦被捕食种威胁、跑回天然洞后约 2 秒封洞口（耗结界枝 1 根），捕食种不再选其为猎物，约 10 秒无危险后拆下。完成：`den_barrier` 左洞（90·90）依次 `den seal started`、`den seal completed`（twigs 2 → 1 或 0）、屏障出现、`target rejected reason=barrier`，捕食者离开后 `den barrier removed reason=danger_passed twig_returned=true`；右洞（40·30）只逃入窝，`den seal skipped reason=traits_low`；被攻击或洞口有他者时中止且不扣枝。

### 出生与成长

**原失败来源**：E1，23:38 至次日 00:04；另标 E3／E5 的以对应版本为准。陆 25、反馈 4、5 对应的旧出生链由 WP3b 重写（[代码架构](ARCHITECTURE.md#身体动作与意图层)），下列现象与完成标准作为重写后的验收。

- **陆 23 走散寻回、分离不安与母油放弃（反馈 1）**：已接入待试玩。演示距离 30 格、搜索 48 格；母油按孩子数和性格的搜寻计时、单向放弃；重载保留计时与解除方向。完成：`family_search` 真实重逢，`family_search_blocked` 受阻计时与双向认亲差异；`home.family_state` 读搜索、时限、dismissed 与当前窝。
- **成长标签与数值展示（反馈 2）**：已接入待试玩。用户反馈标签老旧；现读实际行为，区分赤油、子油、亚成体、成体、老年，补生命、不安、距换阶段秒数。完成：`growth`、`balance_growth`、亲子与接生场景标签随阶段更新，数值与 `/yukkuri status` 一致。
- **陆 25 陆上接生后的幼体安置与收帽（反馈 3）**：复测失败。[470bc06e](https://github.com/Longshou/MCyukkuri/commit/470bc06e)（CI 41015701491，10-30 20:50–20:51 录屏）`birth_land_caught`：产后父油先离开、几秒后才回来清帽；两次接取站位落在帽内被推开（导航结束仍差约 0.8 格判无进展），孩子在帽里哭约 20 秒，父油中途进了宠物窝；全程只用嘴叼；赤油出生后不动，清帽时快速平移到帽檐，被叼下时穿过帽檐滑下；取帽站位落在帽内，无进展后同一状态连续重试 5 次放弃，帽子留在地上，字幕刷屏；父油与帽檐、产程中与母油脸贴脸穿模；父油会直线撞帽不会绕；戴帽时说出“把帽子还回来”；出生后缺打招呼等环节。用户决定：帽子形状与赤油比例保持不变。更早 [a9b60f00](https://github.com/Longshou/MCyukkuri/commit/a9b60f00)（CI 40928640596，10-30 02:32–02:46）：父油戴回帽子时辫子空挥、帽子瞬移到头上（已改待试玩：摘戴帽时帽子随辫梢移动，嘴叼与辫子抓并存，帽檐、尾托、多只口含按种族保留，WP8 [23e764b5](https://github.com/Longshou/MCyukkuri/commit/23e764b5)）；新生儿碰帽檐弹开后穿过帽子落在帽下地面、判摔伤，帽中与地面难以分辨（已改待试玩：中心在帽上的下落一律坐上帽子，落在帽檐先坐檐再滚进帽腔或从外侧滚落，地上帽子把帽下的身体慢慢挪出；看 `birth_stem_caught`、`birth_land_caught` 的 `[body] contact`、`newborn on hat rim rolls inward`，`outcome=GROUND` 的落点都在帽外）。更早 [405bf290](https://github.com/Longshou/MCyukkuri/commit/405bf290)（CI 40908737713，10-30 01:09–01:34） `birth_land_caught`：逐只取幼已能进行，但最后一只叼在嘴里时收尾已判完成、未放下，父油戴回帽子后赤油出现在头上（已修，收尾须嘴里也放下才算完成）；赤油上帽或被叼后显示变得很小；只有嘴叼、没有魔理沙用辫子取幼；接生时父油、赤油穿模。[778bf6e8](https://github.com/Longshou/MCyukkuri/commit/778bf6e8)（CI 40783569330，10-29 10:05–10:06）`birth_land_caught` 复测失败：出生与落帽正常，但父油取帽中赤油每次立即 `fetch … NO_ROOM`（抓取被 `child_boarded` 拒绝），赤油留在帽里；现允许直接从帽上叼起。旧实现反例：用户见赤油留帽中无收尾、父油舍帽离开；[9b048506](https://github.com/Longshou/MCyukkuri/commit/9b048506) 父油挤母油未放帽；[6024c7f5](https://github.com/Longshou/MCyukkuri/commit/6024c7f5) 两轮未放帽；[cadc7e6c](https://github.com/Longshou/MCyukkuri/commit/cadc7e6c) 父油在窝柱间反复掉头；[a0300e75](https://github.com/Longshou/MCyukkuri/commit/a0300e75) 侧发被当辫子；[1325c6bd](https://github.com/Longshou/MCyukkuri/commit/1325c6bd) 另一只持续 `no_reach` 至收尾超时；E8 茎生固定站位 `known_impossible`；E10 取幼时身体朝外、`stalled_approach`。
  完成：`birth_land_caught`、`birth_land_reunion`、`birth_stem_caught` 的 `[choreo] beat id=live_birth|stem_birth` 依次走到 `placed`、产程（茎生 `drop`）、`linger`、`unload`、`take_hat`、结束；`catch site selected`、`catch hat placed`、每只赤油一条 `[body] dismount cause=release`（会跳的自己跳出），之后不再回帽；`catch cleanup completed` 后戴回同一顶帽（`帽子已戴回头上`）；收尾受阻时有 `catch cleanup gave up reason=` 并由帽主随后收帽，不永久停在帽边；`birth_land_reunion` 父油从约 10 格外走来，会合失败有 `next=regroup` 与第二次选点。
- **普通窝容量、大窝与围产期同居（反馈 4）**：已接入待试玩（产地节拍由 WP3b 重写，9a3830ba；以下为旧版现象）（[规则](CHILDHOOD_LIFECYCLE.md#大巢小巢与洞穴是不同的东西)）。
  - ac4ba87b（CI 40196337976）`birth_nest_safe` 临产母油停在窝外约 2 格长期 `no_place_for_term`，推入后才生产，推入窝时高跳。
  - [dd75d650](https://github.com/Longshou/MCyukkuri/commit/dd75d650)（CI 40200352271，10-25 22:24–22:38）`birth_nest_safe` 母油走到野生大窝窝沿上悬着不再行动（`no_place_for_term` 约 36 秒），手推才生产，首只赤油落窝沿顶仍记 CUSHION 且不动；伴侣反复 `nest claim refused reason=no_supported_body_space bodyWidth=0.80`；陆地接生足月母油（`bodyWidth=1.169`）被软垫窝拒收；`pet_birth` 22:38:41 受孕后卡死。用户改判：伴侣拒收不再作真实后果，宁可少量穿模也要放得下、判定稳。
  - a0300e75（CI 40376399575，10-27 02:30:15）`pet_birth` 受孕后卡死（均无线程栈）。
  - E8 `pet_birth` 母油出窝是场景放了魔理沙接生者（`leave nest reason=catcher`）；场景已改灵梦伴侣，待试玩。

  完成：`pet_birth` 反复清场重开，初态落地、临产后游戏响应、母油留在大窝内生产；`birth_nest_safe` 母油不跳地走进窝、不停窝沿，无长时间 `no_place_for_term`，到位后才 `delivery labor started`，落窝沿的赤油 `newborn on nest rim rolls inward` 后 `outcome=CUSHION`，伴侣 `assigned kind=care` 认领同窝；`birth_land_missed`、`birth_land_reunion` 不再 `nest no longer fits stage/body`，`near-term leave nest reason=…` 后在窝外生产。1.2 倍足月孕母配 1.05 倍以上伴侣仍认窝失败是保留的空间上限。两种新窝模型待用户确认。
- **窝内外寻路与接触交接（反馈 5）**：复测失败。用户反复看到途经窝时被卡住；陆地接生部分已由 WP3b 重写（见陆 25），其余场景未改。完成：`feed`、`first_toilet`、`hurt`／`hurt_kin`、`meet`、`marisa_sumo`、`birth_land_caught`／`birth_land_missed`、`family_search`、`indep_near` 覆盖斜向接近、双方窝内／外、远侧站位、途中亲油移动；读 Goal、路径、接触距离与阻塞原因，途中探路不换当前目标，窝柱夹缝走完整绕路或换可达接生点，真实进出并完成交接；不用瞬移或强制留守。
- **陆地出生随机弹道与落地结果**：复测失败。[a9b60f00](https://github.com/Longshou/MCyukkuri/commit/a9b60f00)（CI 40928640596，10-30 02:32–02:46） 茎生仍难接住（该版尚未含茎缩短）。已改待试玩（[f94dfb2e](https://github.com/Longshou/MCyukkuri/commit/f94dfb2e) 前后两提交）：茎缩短到实子间距约一个实子、脱落横向力减小、帽口覆盖每只实子的垂点、接生站位外移，新生儿娩出时显示尺寸与本体一致；看 `birth_stem_caught` 多数落帽。更早 [405bf290](https://github.com/Longshou/MCyukkuri/commit/405bf290)（CI 40908737713，10-30 01:09–01:34） `birth_stem_caught` 两轮共 7 只实子仅 2 只落帽：茎过长、实子间距过大。（[规则](CONSUMABLES_AND_BIRTH.md#陆地出生的随机弹道)；水上帽船出生与无母油分离茎块暂不变）。
  - ac4ba87b（CI 40196337976）胎生出射固定，落窝外不受伤，帽接与茎生按预抽 12% 定漏接。
  - dd75d650（CI 40200352271）`birth_stem_caught` 两轮 7 只实子全 `outcome=GROUND` 并 `birth impact injury`，接生帽 `passengers=0`，实子穿帽掉出（多数落点在帽范围内）。
  - E10 05:09 大窝内胎生仍 `outcome=GROUND`、赤油摔伤 2 点；05:50 大窝茎生两只均 `CUSHION` 无摔伤，两种路径分别验收。
  - 窝沿上的赤油（反馈 4）已接入待试玩：`birth_nest_safe` 中 `newborn settled on soft nest rim` 后 `carry pickup ... purpose=NEST_RIM`、`carry put down reason=nest_rim_to_cushion`，放下后在垫面（无空位 `nest_full`）。

  完成：`birth_nest_safe`、`birth_nest_stem`、`birth_stem_caught`、`birth_land_caught`、`birth_land_missed` 各约 20 只，母油不跳地走进窝；大窝与帽下多数 CUSHION／HAT、约 5–15% GROUND 并有 `birth impact injury`（至少留 1 点生命）；帽下茎生实子落进帽里坐好；读 `birth landed outcome=`、`falling newborn not caught`、`childhood.birth_landing`。
- **饥饿赤油请求驱动亲油找粮并喂食（反馈 6）**：已接入待试玩。23:55:25 母油 `feed refused reason=no_food_source`（亲油营养 75／孩子 54），旧入口找不到掉落物／库存即退出；现先按友善概率接受／拒绝，再搜索 24 格掉落物、存粮及可达植物，30 秒未取得记原因稍后重试。完成：`feed` 近／远食物、无粮、低数值拒绝和窝内外交接对照；接受后真实找粮、消耗并喂入，失败有原因；双方有表情与回应，拒绝转身。
- **赤油取食限制与产后茎优先（反馈 7）**：已接入待试玩。赤油通常仅亲油喂糜，母油优先拾取可达真实空茎并咀嚼。完成：`feed` 与茎生观察唯一茎份额、嚼糜和真实交接。极饿越界搜索随陆 33 离巢限制修正，不在此算已修复。
- **树叶襁褓**：已接入待试玩（[规则](CHILDHOOD_LIFECYCLE.md#树叶襁褓与幼体排便照护)）。亲油采叶、整理、包裹，包着时子油不能自行如厕，须亲油解开、辅助排便、舔净、包回；无人照护则积便、溢出并扣卫生、加不安。一片叶、只给赤油与子油。用户不满意 [af7ba21b](https://github.com/Longshou/MCyukkuri/commit/af7ba21b)（四片斜叶），单叶 [e2df30e7](https://github.com/Longshou/MCyukkuri/commit/e2df30e7)、加宽 [b3932437](https://github.com/Longshou/MCyukkuri/commit/b3932437)；用户反馈与头发辫子穿模，现叶片只贴皮肤。完成：`leaf_swaddle` 叶丛少一块、赤油 `wrap=none` 变 `closed`、亚成始终 `none`；便意子油解开排出一份后 `needs.bladder` 约减 40、`needs.hygiene` 加 25 再包回；积便解开时两份便便落地、脏叶作废；`leaf_swaddle_neglect` `soil` 0→1、满 3 份溢地，卫生与 `mood.unease` 每 30 秒恶化；重载保留襁褓与积便；叶缘接后发下沿不露肤色，发辫饰物在叶外不穿模。演出缺口：采叶、整理、包裹／解开沿用既有姿态，无专用动作与叶片过渡，开合瞬间切换；解开后子油走开时原处的叶不显示；配音台词未做。
- **布襁褓八款**：已接入待试玩（[来源](PET_SHOP.md#4-家庭课程与日常照料)）。`swaddle_cloth` 款式存于物品数据：奶油针织、荷叶边蝴蝶结、首字母、虎纹、外出款、防寒厚绒、格纹、碎花。[e2df30e7](https://github.com/Longshou/MCyukkuri/commit/e2df30e7) 接入；美术审核判像短裙（[b3932437](https://github.com/Longshou/MCyukkuri/commit/b3932437) 改包被）、圆管卷口像泳圈（[1de48484](https://github.com/Longshou/MCyukkuri/commit/1de48484) 改翻折边）；用户反馈布吞掉头发辫子，现为贴皮肤薄包被。虎纹款带耳头罩未做。完成：`swaddle_styles` 各款右键包上三只自家幼体，外观与物品图标同款（首字母款背后为本物种首字母，灵梦 R、魔理沙 M、爱丽丝 A），发辫尾巴在布外不穿模；解开取回同款（`[interaction] swaddle on/off` 带款式）；工作台“襁褓布＋染料”八配方对应八款；商店购买随机一款；重载、落水、着火、换主、死亡归还保留款式。
- **宠物窝去边与体表演示（E3 反馈10、11）**：已接入待试玩。E5 01:55:56、01:56:20、01:56:31 三次认窝失败，选中框显示细碎碰撞格；现简化选中框并扩大实际入口（[玩家操作](CORE_GAMEPLAY.md#玩家操作)）。完成：`marisa_explore` 赤油进出，`sleep` 与宠物认窝看成体认领、寻路、睡眠；`marks`、`body_mold`、治疗演示表面变化与实际治疗同步。

### 头饰、水生与远行

- **摘帽后的持续情绪与表情**：已接入待试玩。E8 `birth_land_reunion` 取帽后哭脸一闪恢复（日志确认 `WORN→LOST`，不能证明帧序列）。完成：摘帽中、结束及还帽后的脸与 `mood.unease`／托管状态不一闪恢复；有效托管放帽不被误判失饰。
- **陆 11 头饰认亲**（34）：已接入待试玩。E1 22:45 智力 40 亲油 `known=false`、亲近 30→27→24，驱赶不明显。完成：`hatless_kin` 智力 40／80 亲油对照，低智力自然鄙视并按概率升级为真实驱赶（`accessory.sanction`）；高智力仍认亲。
- **陆 20 怪物判定与欺负反应**（35）：已接入待试玩。E1 22:46–22:49 按前后两处二级伤判怪物，低血量样例制裁开始即退出；普通灵梦受咬后只进捕食种反击拒绝入口。现改为失饰且面部活动伤口≥3 处，或任意部位三级伤；受欺负及欺负者被反击均按自身状态反击或逃跑，低血量必逃。完成：`hatless_monster` 两种初态均出怪物读数；`hatless_boundary` 两处普通伤和低血量不算怪物；`hatless_sanction` 自然发起欺负，选择、伤害、逃跑位置可对应。
- **远行活动范围**：已接入待试玩（[规则](BEHAVIOR.md#属性与关系)）。完成：`rain_shelter`、`pred_range`、`forage`、`dig`、`marisa_explore` 与找岸／帽船觅食核对目标距离、Goal、位置、物资消耗和返回；攻击、接触、感知距离不扩大；只改数字不算通过。
- **魔理沙探险队（子油远行闲逛）**：已接入待试玩（[规则](WILD_EVENTS_IMPLEMENTATION.md#补充群体事件)）。完成：`marisa_explore` 对照合格子油、低胆量魔理沙、灵梦与有巢赤油，读 `behavior.roaming`、`[explore]` 抽签、范围、返程／中断；赤油与不合格者不触发。台词、表情与演出最后补，数值尚未通过。
- **水 16 同种饰品通用、堆叠与受损交互**（所有种族）：已接入待试玩（[规则](HEADWEAR.md#饰品通用)）。E1 碎片收齐后不能摘取、魔理沙不拾取兼容碎片且不能堆叠；E2 00:11:55–00:12:04 无碎裂记录的缺帽个体被仅限 `SHATTERED` 的入口排除，后排碎饰者收齐 3/3。完成：`hat_swap` 缺帽成体及子油走向兼容碎片或接受手递，逐片增加并凑齐才长合；异种拒绝、帽船托管不接第二顶；`hat_shatter` 收齐后摘取／归还继续长合，实物与进度不重复不丢失。

### 水生魔理沙模块（重构）

**状态：复测失败。** 帽船、船长阶段机、席位、乘员转移、落水救助与试玩场已按[水生魔理沙](AQUATIC_MARISA_IMPLEMENTATION.md)重做，首轮运行包试玩主链多处阻塞。该实现已冻结，将由 WP3a 下线、WP4–6 重建（用户裁决 2026-10-28），不再在旧代码上补丁；下列现象作为重建反例与三级复测对照，代码、CI 与助手检查不算验收。旧水 11、水 14（辫子抽动）演出缺口按 P0-6／P0-8／P0-11 复看；无桨手划暂用不握木棍的辫子划桨片段，专用片段待补。水 16 异种饰品见上节。

**重构方向（用户裁决 2026-10-28）**：已接入待试玩。水生魔理沙按“底座加 DLC”整体重构，不再补丁式修复；旧实现已下线，在新底座上重建。
- 原则：动作由身体能力和物理决定；每个能力只实现一次、全模块复用；行为只声明目标，做不到就换手段；任何限制都要配满足它的办法；帽船当作会漂、会倾斜、会翻的小陆地，取消座位表与乘客钉位；一切行为由多种欲望的真实竞争驱动，想要的演出通过调整欲望强度和优先级得到，不硬编码顺序和门槛。
- 裁决：
  - 成体最高跳 0.8 格、最远 1.0 格，搬运不设质量数值，按体型大小判断；
  - 水生种在水中保留很弱的挣扎前进；
  - 帽子四种形态统一一个尺寸；
  - “先载幼体、船长最后上船”不做硬规定；
  - 帽船可被推动；
  - 帽内挤不下时，孩子可以被挤落水。
- 契约与设计原则见[代码架构](ARCHITECTURE.md#身体动作与意图层)；各工作包的复测现象只登记在本条。
- 进度：WP1–WP8 全部已接入待试玩，运行 [8ceba8a8](https://github.com/Longshou/MCyukkuri/commit/8ceba8a8) 起（身体层：依附、能力表、走／转身／跳／抛／小动作、抓取与放下、游／爬／推／扶正／划；意图、欲望竞争、请求与编排；陆地胎生与茎生编排；放下的帽子在陆地当接生帽、在水上当船；搬运统一为嘴叼、辫子、帽檐、尾托与多只口含；`motor/` 之外不再直接写身体）。帽子统一尺寸按 1.4 倍代定（R6），试玩时可改。
- 重建后的复测记录（均已改，待复测）：[a9b60f00](https://github.com/Longshou/MCyukkuri/commit/a9b60f00)（CI 40928640596，10-30 02:32–02:46）`cargo` 空帽也提示帽底无空间；落水游得太快，扶正被泡水伤反复打断、一卡一卡未成功，扶正时播挣扎动作；魔理沙无辫一侧鬓发被当成手摆动；子油不会把戴着的帽子放成船、直接游回岸。[405bf290](https://github.com/Longshou/MCyukkuri/commit/405bf290)（CI 40908737713，10-30 01:09–01:34）`hat_float` 父油与子油穿模；`hat_crowd` 挤动因穿模看不出效果；`cargo` 储粮只是平面贴图；落水后不先扶正自己的帽子、直接游岸，上岸常失败卡在岸边（旧落水逻辑）。
- 完成（用户全量复测）：陆地出生 `birth_land_caught`／`birth_land_reunion`／`birth_land_missed`／`birth_stem_caught`／`birth_nest_safe`／`birth_nest_stem`／`pet_birth`；赤油 `infant_starvation`、`colony_migrate_babies`；帽子 `hat_float`／`hat_push`／`shake_capsize`／`cargo`／`hat_crowd`／`hat_swap`；落水 `self_rescue`／`self_rescue_far`／`shore_capsized`／`fall_in_pond`／`worn_hat_boat`；水上生活 `water_colony`／`sail`／`afloat_family`／`shore_family`／`water_night`；家庭 `rescue_child`／`rescue_infant_full`／`birth_boat_caught`／`birth_boat_missed`／`family_skiffs`。各场景的看点在场景说明里，下列 P0 观察点为水上部分的验收标准。

**旧实现反例（重建对照）**：场景 — 现象 — 版本。池塘场景日志默认开逐阶段细节（[玩法日志](GAMEPLAY_LOGGING.md)）；旧实现不再单独复测，重建后按本列表与 P0 复看。状态在行末。

- `sail`、`cargo_load` — 提示“帽船没能生成”（拒绝原因被合并）、魔理沙落水溺亡、`afraid.png` 缺文件 — [d5d88bb0](https://github.com/Longshou/MCyukkuri/commit/d5d88bb0)（[CI 39455579956](https://github.com/Longshou/MCyukkuri/actions/runs/39455579956)，2026-10-21 00:48–00:54）。
- `cargo_load`、`shore`、`sail` — 收帽后赤油瞬移上岸；建船被 `hull_collision` 拒绝后亲缘与幼体头饰修改未回滚、岸上赤油持续 `infant_ashore` 阻止出航；`sail` 仅证明单体出航接通 — [56b5e4f1](https://github.com/Longshou/MCyukkuri/commit/56b5e4f1)（[CI 39465772518](https://github.com/Longshou/MCyukkuri/actions/runs/39465772518)，01:46–01:52，复测失败）。
- `self_rescue`、`capsize_shore` 岸上翻帽 — 个体一直泡水、头与帽檐重合反复挣扎，`self_giveup … slips=3` 后回 IDLE 无后续办法，偶尔帽子留头上浮不上来 — [2715d9c1](https://github.com/Longshou/MCyukkuri/commit/2715d9c1)（E11，复测失败）。
- 落水自救演示 — 自救后帽子瞬间变大；帽船推不动、水中个体与帽体重叠、翻覆帽不浮上来；带木棍不划船；初航后子油不捡回自己的帽子 — E11。
- `birth_boat_caught`×2、`birth_boat_missed` — 岸边下水必失败：放帽当刻 `voyage cancelled reason=berth_lost`，转收帽又 `stow_no_berth`，帽子漂走人留岸，母油产程停在 5600/6000 `waiting_for_own_hat`，无一胎出生；漏接场父油放帽被漂走母帽占位 `hull_does_not_fit` — [1325c6bd](https://github.com/Longshou/MCyukkuri/commit/1325c6bd)（[CI 40380823130](https://github.com/Longshou/MCyukkuri/actions/runs/40380823130)，2026-10-27 03:21–03:31，`.local/reports/aquatic-1325c6bd/`；未开逐阶段细节）。
- `water_dock`、`shore_capsized`、`birth_boat_*` — 上岸后找不回帽：`ASHORE→RECOVER own_hull_in_reach` 与 `RECOVER→ASHORE recover_hull_gone` 每秒互切数十秒，帽子最终 `sunk` — 1325c6bd；E8 `rescue_child` 外部救援中断自救脱帽后留倾覆原帽；E10 03:06–03:39 子油已跳回亲帽，原小帽仍因 `hat_recovery_timeout` 与亲油 `no_reachable_contact` 留水面（复测失败）。
- `water_dock`、`self_rescue`、`shore_capsized`、`water_neighbours`、`shake_capsize` — 扶帽受阻：1.75 帽扶到一半 `righting_pose_refused`、三次打滑转岸（0.4、0.72 帽曾能扶正爬回）；`shore_capsized` 扶正后持续 `rim_out_of_reach` 至溺亡 — 1325c6bd；E9 满体力小帽自救仍 `righting_swept_collision`；E10 00:05–00:11 满体力子油摘帽被 `swept_hat_vertex_crosses_skin` 拒绝，两次 `righting_swept_collision`、三次滑落放弃（复测失败）。
- 翻船后落在自己帽下几乎扶不正 — 释放仅偏移 0.035、船长留在倒扣帽檐下，帽内“外侧”算反、从帽底咬檐，4 次咬上 1 tick 后 `grip_moved`（用户反馈）；E8 `shake_capsize`、`self_rescue` 倾覆穿模与水中平移（已接入待试玩）。
- `land_hop`、`return_to_parent`、`land_lift`、`water_life`、`water_neighbours`、`family_skiffs` — 换船、跳出与托幼下岸：`land_hop` 34 次、`return_to_parent` 19 次 `no_departure_seat`，`land_lift` 38 次 `no_reach`／`known_impossible`，赤油被留船 `no_way_found`，`family_skiffs` 召集超时且亲帽先扔粮腾位 — 1325c6bd；E8 `water_dock`、`shore_family` 跳弧过高过快，`self_rescue` 攀爬和 `rescue_infant_full` 满载托举偏快，`shore_family` 同座位 `carry_within` 循环；E9 `family_skiffs` 换船途中提前成为亲船乘员、亲船驶离后 `revert_blocked_or_body_limit` 落水，`water_life` 靠岸后走到与预检不同的出发座位反复 `hop_exceeds_body`；E10 01:47–02:14 赤油 `no_reach`、七次换落点后 `no_way_found` 留船，03:49／04:04 岸上登船 HOP 直接跨约 1.44／1.39 格到船长位（MOVE 25／24 tick，像放慢的长跳）（复测失败）。
- `family_skiffs` — 落水孩子在自帽与亲船间摇摆：自救与亲船指引争抢移动、每 20 tick 重选，0.4 帽子油 `#55102` 03:29:05 落水、03:29:26 溺亡，离亲船不到 2 格 — 1325c6bd（用户反馈）。
- 全场亲油施救 — `incident close attempts=0`、亲船长期 `SAFETY HOLD`，会跳的孩子反复 `rim_out_of_reach` 未转辫托 — 1325c6bd；E9 `family_skiffs`、`water_neighbours`、`rescue_child` 有实际 CLIMB 完成，`rescue_infant_full` 按概率 0.8600001、抽值 0.937279 拒绝救援（真实拒绝，不能判事件未触发）；E10 04:20 第一只新生儿落水后父亲停在 `no_contact:hull_boat`、母亲 `stand_unreachable`，直到清场未救起（复测失败）。
- 救助角色误判 — 兄弟姐妹按亲属计，赤油判 `CAPTAIN_SELF`、兄姐船判 `SAME_BOAT_PARENT`，名额挤出后重抽；E8 `rescue_child` APPROACH 船舵与辫子托举抢朝向反复摆头（已接入待试玩）。
- `shake_capsize` 只跑约 27 秒；`self_rescue` 3 点体力船主漂上岸入睡、翻船再落水溺亡 — 未单独判断。
- `birth_boat_caught` — 船上分娩出口：E8 三只新生儿全部 `outcome=WATER`，本帽预约随即取消、父帽 `outside_footprint`；E10 04:20 第一只出口预检 `safe` 且席位已预留，实际推出被帽体净空拒绝，继而 `seat_beyond_settle_reach`、`outcome=WATER`，04:27 第二只实际 `HAT`；E9 未复测出生链（复测失败）。
- `family_skiffs` — 亲子帽船动作反复中断：E8 idle/passenger 反复 `lapsed` 重启，子船被 `hull_boat` 挡住时周期重发航路（已接入待试玩）。
- `water_threat` — 水上捕食后的落地：E8 捕食种 `no_dry_stand` 后关闭飞行而落水受伤；E10 02:24 俯冲入水，返岸被 `not_ready` 取消，02:39 上岸血量 10→7，再次俯冲入水降至 6；落水期间被 `water_struggle` 覆盖，鬓发曾由 `hand` 骨带动而翼骨不动，侧发现只随身体、翼仍无动作（复测失败）。
- `afloat_family`、`first_voyage` — 亲子出航与初航交接：E10 00:45 亲油放船后赤油 BOARD `no_reach`，约两秒后 `young_unloadable` 取消并戴帽；01:09 初航子油挪到帽檐即 `voyage_berth_lost`（复测失败）。
- `water_neighbours`、`first_voyage` — 池塘场景初态：E8 邻船先缺粮上岸、母油被低温打断；孩子尚在岸上反复 `first_voyage_ineligible`；两场受前一冬季群系影响；已改温和场景、清场恢复群系、删水上烧帽演示（[头饰](HEADWEAR.md)）（已接入待试玩）。

通用要求：区分选择拒绝、物理受阻、接触与真实完成，真实受阻／力竭仍失败，不瞬移、不穿岸、不直接改成功状态。

**行为场景观察点（原 P0；重建合入后复测，场景 → 应观察）**

- **P0-1 自然生成即在船上**：`water_colony` → 成员第 0 tick 已坐船漂，无延迟 `moveTo`／`launchAfloat`；无水面则留岸并记 `spawn refused reason=`。
- **P0-2 单体完整航程**：`sail` → 找棍、备粮、走到岸位、放帽（`hat_off_self_aq` 终帧与船体重合）、登船、划、捞漂浮物、返岸、下船、收帽；无原地转圈或“上岸—再上船”循环，每阶段一条日志。
- **P0-3 带赤油出航**：`afloat_family` → 系泊帽稳在岸边，子油自己跳入，赤油被辫兜放进帽腔；登船先后由欲望竞争产生；赤油睡着时出航等待或取消并记原因，无人独自留岸。
- **P0-4 带幼体靠岸下船**：`shore_family` → 缺粮或天黑引发返岸；子油自己跳上岸，赤油被逐只辫兜放到干地，单只失败只换落点；船长最后上岸收帽。
- **P0-5 帽冠粮袋与玩家交互**：`cargo` → 食物右击船体逐份入粮（潜行最多 8 份），粮只在帽腔里、帽檐不出现；放不下提示加 `cheek_puff`、日志给原因、不晃船；吃水随载重下降，读档一致；空手和非食物点船才晃；`hat_swap`、`shore_family` 核对岸上摘取与水上拒绝。
- **P0-6 落水救助一次决策**：`rescue_child` → 子油被嘴咬后颈拉上船；`rescue_infant_full` → 先推一份粮下水再辫兜救赤油，被兜孩子随船晃不抽动；每事件每救助者一条 `incident decide`。
- **P0-7 自救有上限**：`self_rescue`（含远帽起点）→ 咬檐勾辫爬回；3 次打滑后朝岸弱漂；接近超过 600 tick 转岸；浸水伤害按 60 tick 真实累积；孩子不在两帽间反复改向，两亲不同时指挥。
- **P0-8 真实翻船与扶正**：`shake_capsize` → 连续空手摇船至翻，乘员被甩出，粮与桨漂散；高友善者先推赤油向帽檐，低友善者先抢帽；嘴咬檐翻正，散落物可捞回；乘员先游到外侧再扶，浮起读数对应，不凭观感调浮力。
- **P0-9 船上分娩与新生护理**：`birth_boat_caught` → 临产挪座、每推船晃、新生儿落帽腔初醒、嚼糜喂、首便在后帽檐朝水（`birth exit selected`、`birth berth reserved`、`outcome=HAT`，仍可真实漏接）；`birth_boat_missed` → 落水建事件，母亲伸辫或父船来救，没救起则溺亡并哀悼。
- **P0-10 夜宿（无绳夜泊）**：`water_night` → 黄昏船头楔进岸凹，孩子进帽腔，船长俯身盖住并哼歌；漂离超过 3 格会醒来复泊；天亮 `boat_wake`。
- **P0-11 帽船即家的基础演出**：`hat_swap`（帽型一致、水生帽更大）与任一池塘 → 吃水线湿色带、闲置起伏、乘员按站位与帽面倾斜取姿态、桨在左侧帽檐或辫梢不消失不穿模，表情无缺图（`sad`／`afraid`）；托管实船 `shore_capsized` 暂放不失帽，水上不累计火焰烧伤，漂远、沉没各结算一次，帽与库存不复制不丢失。
- **P0-12 不卡死**：任一水上场景跑满 10 分钟 → `/yukkuri aqdiag on` 下无持续同一阶段超时的个体；超时有 `ended reason=timeout/stalled` 与真实结果；辫子不会一直伸着。
- 另：帽子可被推开、扶正，个体不与帽体重叠；帽子大小戴着与展开一致；带木棍个体会划船；任何动作结束后主人找回或明确放弃自己的帽子（`reclaim done` 或带原因的 `expired`）；陆上摘帽放帽不闪退。

**未做（按新架构）**：旧代码中的初航、随亲出航、雨天等按新架构迁移，不计为完成。P1（15 项：初航与小船随亲、水上离家、赤油帽檐守护、威胁、雨天、帽内事故、晒干、非亲救助、推遗体与哀悼、孤儿船、孕期腾舱、无棍手划、船体损伤与修帽、桨滑落、并舷社交）与 P2（11 项：啃睡莲、夜间筏群、领地碰船、并舷递粮、结冰搁浅、桨拍反击、高湿软化、撕裂帽漏水、老年航程、宠物水生、尺度统一）清单与完成标准以 `.local/reports/aquatic-redesign/D-ecology.md` 第 2 节为准，原水 21–32 并入其中；船长遗体留在船长位直到靠岸或被推入水（A10）尚未实现。

### 其他已接入玩法

- **油库里互挡绕行与互相推挤**：已接入待试玩。[ac4ba87b](https://github.com/Longshou/MCyukkuri/commit/ac4ba87b)（CI 40196337976）一只挡住另一只路线时反复碰撞、既不绕行也不重算，互相推不动，亲子被夹在窝里两亲之间出不来；对爬行赤油推力无效（[规则](BEHAVIOR.md)）。完成：`den_family`、`birth_nest_safe`（赤油能从两亲之间出窝，亲代睡着也被缓慢挤开）、聚落各场景与 `colony_migrate`（窄处互让、不长期对撞）读 `[move] blocked by yukkuri` 的 action（sidestep／replanned／blocker_yields）与位置变化；护子、抱持、乘船与出生弹道不受影响。窝内卡住的其余原因仍需现场读数。
- **行为数值、成长与遗传**：五属性、对象关系与恐惧、技能学习和固定成长时钟已接入。完成：“五属性读数对照”概率与任务预算符合公式；“固定成长边界”按实际 tick 换阶段；“特殊形态边界”不越界；“灵胖成年锁定”在成年边界锁定且不中断短动作；亲子分享、照护和翻船救援按友善作一次决策，普通失败冷却后才重选。见[正式设计](BEHAVIOR.md)。
- **数值改造适配**：修正初始商店资格／技能漏载、新生儿关系用临时 UUID、中立对象无法首次问候。完成：`shop_build` 受训库存有原定徽章与六项技能，购买放出及重载保持；`birth_land_caught` 新生儿读取亲子关系，收纳放出及重载继续累计；`balance_first_contact` 从亲近 0 开始普通问候并建立同伴关系，高恐惧仍按 `balance_relation` 退避。
- **通用**：陆 1（23）场景北侧观察、个体初始朝北；陆 3（11）清空本场地后实体、账本和地形回到同一初始状态，无模组物品与拆窝掉落；陆 6、7 季节展示与帕琪误报已删，无残留即删；[架构整理](ARCHITECTURE.md)后的行为、显示顺序、保存契约与多人同步；同屏 1–15 只（污渍、孕肚、发言）的帧时间；单一血量下多日的死亡与繁殖节奏。
- **野外住所**：`den_family` 家庭洞、`widow_family` 丧偶家庭：陆 52 清醒饥饿翻倍，丧偶后母油接替外出存粮，未独立子油留洞内、不再进出循环。`indep_near`、`indep_far` 独立：告别后释放旧床位，成为寻窝流浪油，亲缘与好感保留，尚不具备繁殖资格。
- **流浪与觅食**：`dig` 流浪油挖穴（陆 29（47）内部 2 格高）；`forage` 外出觅食（陆 30（48）近处不足才远行：父基础／上限 48／192、母 20／128 格，再乘胆量系数）；`graze` 吃草（陆 31（49）优先非草、咽草嫌苦）；`cheek_forage` 灵梦颊囊：多捡、逐份放入。
- **群系聚落**
  - `settle_snow`、`settle_desert`：体感、保温窝和避暑效果符合[季节与温度](SEASONAL_LIFE.md)，笔记与命令季节一致，跳时间不换季。
  - **族长空缺后的交接**：已接入待试玩（[族规与继任](SETTLEMENTS.md#职位与族长)）。完成：`colony_hunt`、`colony_ration`、`colony_migrate` 手动杀死原族长后，公仓实物仍可取、救急盘点不重复份额、召集中断不算空猎、已出发猎手返程交粮、迁徙继续、候选会合在吃饭／照护中暂停后累计完成；无合格成体时保持空缺、真实缺粮仍有后果。水上接力须在实际水上聚落迁徙中杀死引导船主，确认接力者驾驶自有帽船、未搬走死者船货且抵达后才换公仓；陆地场景不作这项通过证据。
  - `colony_judge` 族规·审判：陆 32（45）逐案走近、相向听审再裁决。
  - **野外嗯嗯坑与山脚穴居聚落**：已接入待试玩（[聚落场地](SETTLEMENTS.md#聚落场地山脚穴居群)、[群系适居](SETTLEMENTS.md#群系适居裁定项目设计)）。首版 [98b93c71](https://github.com/Longshou/MCyukkuri/commit/98b93c71) 美术复审不通过（缓坡家洞像土屋、土柱），已改后复审通过；收尾项：补土约每两格降一格、门前只一格砾石、倒木两三格长、坑约 4×3／6×3。完成：`settle_forest`（村）、`settle_taiga`、`settle_cherry`、`settle_hills`、`settle_savanna` 家洞挖进北侧坡、洞口朝南，补土处顺坡无露土竖墙，坑是低一格凹地，只有可饮水群系有塘；`settle_plains` 洞岸补起；`settle_pit` 去不了时 `toilet pit refused reason=…` 并改用离家 4 格外地面；新开世界聚落只在适居群系坡脚；用户认可外观与如厕行为。
  - **嗯嗯落块、坑边排泄与嗯嗯奴隶坑中生活**：已接入待试玩（[落块](ECOLOGY_DAILY_LIFE.md#排泄修正)、[奴隶](SETTLEMENTS.md#嗯嗯奴隶的坑中生活项目设计)）。ac4ba87b（CI `40196337976`）`settle_pit`（21:58）：成员走进坑底排泄、排泄物是掉落物、奴隶只分四分之一口粮；用户据此要求落成有容量分档的方块、奴隶留坑吃排泄物并受羞辱、成员只站坑沿排泄。完成：`settle_pit` 便意升到 90 后成员站坑沿背对坑口排泄，坑格出“嗯嗯”方块、份数档位增加，满 8 份溢到相邻格（`toilet pit edge reached` → `poop placed … overflow`）；`settle_pit_slave` 一只成体成奴隶：留坑，营养≤50 时到份数最多的格吃一份（`pit slave eats droppings`，份数减一、营养 +12、卫生下降），坑空则 `pit slave hungry reason=no_droppings` 且营养继续下降；他人排泄后 `pit mockery`。已知边界：玩家放下的废物仍落“地面零碎”、奴隶不吃；`alley-sniff` 仍只找掉落的废物；住所内部排泄仍是掉落物。
- **日常草地**：`meet` 已建立关系的两只个体普通问候与蹭蹭。
- **出生与成长**
  - `birth_land_caught`、`birth_land_reunion`、`birth_land_missed` 陆地接生：陆 25（41）复测失败，按“出生与成长”反馈 3 复测，不能以真实入帽算整链通过；临产出窝与大窝生产见反馈 4。
  - `first_toilet` 首餐后首便：陆 26（42）首餐吞下后才首便。亲代照护决定已接入待试玩：`feed`、`first_toilet` 与帽中照护核对 `home.family_state` 的 `care`；同一孩子同一需求中断重试、重载后沿用决定，喂食与首便独立。`hurt_kin` 亲子受伤舔舐：完整舔舐让最老一处轻伤降一级并回 2 点生命，深伤只安抚（`life.lickHeals`）。
  - `growth` 一路长大：陆 13（50）335999 tick 亚成在下一模拟 tick 成年，不因体型受阻延后年龄资格；陆 51 移速新生约成体 45%、赤体末 55%、子体渐到 100%。
  - `family_distance`、`family_search`：陆 23（36）按反馈 1；`feed` 按反馈 2、5、6；赤油食物限制与产后茎优先见反馈 7，离巢限制见陆 33。`aftermath_growth` 事后茎生：自然生产结束后空茎消失，落成一次真实茎物品；检查连续生产、食用、配方和保存，母油优先嚼茎喂赤油随反馈 7 核对。
- **天气与捕食种**：`rain_shelter`、`rain_open`（陆 14（4））按上方完成标准复测。`pred_watch`、`pred_hunt`、`pred_range`（陆 15（17、20、38））发现与巡飞更远，续追不中断，看见即退避，接触后取食；慢速赤／子油优先与陆 16 一并试玩。`marks` 脏污与伤痕：陆 21（9）与陆 49 留疤合并复测；普通伤概率原位留疤、大伤必留，橙汁治伤也须结算，面粉糊去疤。
- **伤病与遗体**
  - `airborne` 腾空与小跳：陆 38（14）明显腾空才感叹。`house_health` 家油血量：陆 45（24）生命上限 1／2／4。
  - `wound_leak`、`core_exposed`、`core_filling`、`carrion`、`cannibal_hunt`：血量与遗体资源按[规则](INJURIES_AND_REMAINS.md#身体状态)；治疗止漏另看陆 49；`body_mold` 核对四档油霉及治疗后原位回退，`core_exposed` 核对大创腔、普通馅／唯一中央中枢、低血小裂口及完整皮面对照。
  - `treatment_severe`、`treatment_scars`、`treatment_care`（陆 49）按[治疗流程](INJURIES_AND_REMAINS.md#伤口与治疗)核对伤口、烤足、生命、疤痕、感染与医疗余量；`marks` 核对自然闭合留疤后去疤。
  - `remains_rot` 遗体腐败与死臭（[规则](INJURIES_AND_REMAINS.md)）：手持或冷冻不老化、重载保持阶段。
  - `offering` 请吃了我（含临终献体分支）：陆 24（37）按性格、好感、自身与孩子饥饿触发（拒绝后 1200 tick 冷却），投喂可中断，GIVE 后留受赠遗体。
- **头饰与认知**：`hatless_scorn` 失饰者被鄙视（陆 18（8））观察与问候共用冷却，接生放帽者不算失饰（随帽船托管）；`hatless_sanction`（陆 20）自然触发欺负，双方按实际状态反击／逃跑，低血量必逃且停止追推。
- **家油人类屋**：`house_empty`、`house_pair` 家油入住：陆 46（27）夜出昼归、饥饿下降；陆 2 羊圈只有四壁。`house_eggs` 整批迁卵：陆 47（29）已接入待试玩（[家油生态](HOUSE_ECOLOGY.md)）。完成：逐枚搬运后返回旧窝，旧窝余卵和在途卵清零后才交接亲代住所；读两窝 `EggSlots`、`EggRelocation` 与亲代 `relocation` 核对数量、身份、孵化进度；重载、目标变脏或被毁后，返卵或落地物品与账本一致。
- **街道、商店、工厂**：`badge`（陆 8（13））令牌绑定真实个体；`date`（陆 9（16））店方相亲；`pred_pets`、`transport`（陆 2 四壁内可活动；陆 15 受训蕾米莉亚饿了也忍住）；`harness_walk`、`walker`（陆 37（12））套胸带回应限时、归还移动控制；`feed_wild`（陆 40（18））只喂营养≤35 的野生个体，间隔 2400 tick；`oniisan`、`oniisan_prey`、`attack_witness`（陆 10（22））目击者退避、亲友收到警报；`exam_fail` 处分；`shop_stale`（陆 34）滞销减粮已关；`shop_build`；`factory_wear` 多胎恶化曲线。
- **物种与稀有形态**：`marisa_sumo`（陆 4（1））相扑出现；`myon_stick` 妖梦与楼观剑（[妖梦](RARE_FORMS.md)口癖与觅草，战力见陆 17）；`rare_forms` 一览与 `/yukkuri action`：逐一查看全部 19 种形态受伤有伤口、佩章有圆牌，日志无 `Wound/Badge attachment disabled`；另核对木棍嘴部朝向与被炉动作穿插；跨季节行为结合陆 5，红眼兆头迁址需在真实聚落旁观察，一览场景本身不证明迁址。素材匹配不代替视觉确认。
- **群体事件**：已接入待试玩。补入试玩场的重点：多人晚加入、双玩家并发、长期自然推进，`street-performance` 的村落地点、`alley-corner` 的实际退让、`lookout` 的朝向。完成：真实参与者按条件进入，移动与结算符合[事件规则](WILD_EVENTS_IMPLEMENTATION.md)，中断和重载不重复扣物或结算。
- **无专属场景**：[胎数随体型变化](BEHAVIOR.md)、[认屋与群体事件](WILD_EVENTS_IMPLEMENTATION.md)、[螺壳雨天缩壳](SUBSPECIES_IMPLEMENTATION.md)、[工厂到店物流](PET_SHOP.md)、[声音注册](DIALOGUE_IMPLEMENTATION.md)；命名牌取名、子代本名不重名、宝物被取走或被抢。

### 美术与视觉确认

均为已接入待试玩，外观由用户确认。

- **通用照料道具外观**：放置后的食盆、软垫窝、编织大窝与厕所见[美术](#美术opus)。油粮袋、清洗布、毛线球与运输篮仍为原低多边形造型，只按明确替换项继续。完成：携带与地面道具远近观感。
- **宠物与野生大窝**：验收容量、入口与碰撞（外观见美术）：占真实 2×2 格（点击格为西北角，四格须空，拆任一格整窝拆下、只掉一件），可并排两只至多约 1.1 倍成体与四只赤油。[dd75d650](https://github.com/Longshou/MCyukkuri/commit/dd75d650)（CI 40200352271）伴侣在足月孕母旁认窝被拒（见反馈 4）；现按容差挨挤同居。完成：`family_nests`、`birth_nest_safe` 双亲与赤油站位、入口进出；创造模式墙边试放，空间不足提示且不放置。
- **火烧后的烧焦外观**：E7 `core_exposed` 低血烧伤全身斑驳、侧边部件错位、泪痕浮出；用户接受降级，全身烧伤改按伤级整体着色，家油只用三级热损外观。完成：`core_exposed` 低血烧伤三级着色均匀，泪痕与侧发贴合，`[render]` 读数不明显升高；`thermal_body`、`wound_sources`、`magma_sole` 真实火烧验证，足烧只足底、全烧保持高度／深度与头饰尺寸，不误出露核洞。
- **身体印记与重伤创腔**：发面／皮面裁切、硬边断发边、直壁平底创腔、普通馅和唯一中央中枢；露核命中放宽为开口外扩 1.5 倍（[规则](INJURIES_AND_REMAINS.md#中枢与临终)）。完成：`body_mold`、`marks`、`treatment_severe` 原位变化；`core_exposed` 各类伤瞄准开口及外缘即可命中；`core_filling` 取核后空腔，`rare_forms` 品种内馅配色及捕食种核心接缝无原发色弧边；治疗、遗体搬放及清场重开不换位、不重复中枢。
- **遗体美术**：按[死因类别与水中阶段](INJURIES_AND_REMAINS.md#同伴照护与遗体)换死亡脸、姿态、表层与地面馅渍；寿终缎带下垂需骨骼姿态，目前缺；制作源 `standards/yukkuri-library/drafts/remains/` 未经用户认可，献身切面归分体项。完成：`remains_forms` 用“读遗体”核对七种死因与溺死的 `category`／`form`；水池左具约 30–60 秒后 `water=softened`，右具约 30–60 秒后 `water=dissolving`、`food` 6→0、`core=false` 且沉底，岸上对照不变。
- **多只伤员同屏卡顿**：E6、E7 `core_exposed` 多只伤员同屏严重卡顿；距离分级后缓解，[58d99ee0](https://github.com/Longshou/MCyukkuri/commit/58d99ee0)（CI 40182081691）攻击瞬间仍卡；[18fd3981](https://github.com/Longshou/MCyukkuri/commit/18fd3981)（CI 40183945794）一律平贴后用户认为覆盖原有样式，已撤回。现 6 格内最近 2 只完整、其余平贴，客户端 `render.woundPerfLog` 记分项耗时。完成：`core_exposed`、`rare_forms` 全部伤员同屏绕行和连续攻击无明显卡顿。
- **脸部表情贴片分块与遮眼**：[e54d0861](https://github.com/Longshou/MCyukkuri/commit/e54d0861)（CI 40185538416）未受伤个体走路时脸上几块明暗不同的贴片闪动、偶尔遮眼；[998f52c2](https://github.com/Longshou/MCyukkuri/commit/998f52c2)（CI 40186711876）仍见头部皮肤竖向明暗带、眼睛边缘略被遮（截图未入库）。完成：`core_exposed` 及任一普通场景近看未受伤与受伤个体走动、转身，脸上无分块明暗，眼睛不被遮挡。
- **遗体表面颗粒闪烁**：[17f1e4f8](https://github.com/Longshou/MCyukkuri/commit/17f1e4f8)（CI 40195412431）、ac4ba87b（CI 40196337976）走动或转视角时自然死等遗体皮肤满面颗粒闪烁，暴力死、溶解不明显（截图未入库）；遗体图集已统一着色。完成：自然死、饿死、烧死、病死遗体旁走动、转视角，皮肤颜色均匀无颗粒，呈各死法设计偏色。
- **伤口贴图、创腔与发面适配**：E6、E7 发面伤口穿透／缺口、爆裂伤洞口污渍与断发参差、头顶凹伤交界细缝；[d0412a01](https://github.com/Longshou/MCyukkuri/commit/d0412a01) 满血爆裂前角伤位挖空侧发；58d99ee0 低血露核仍挖空侧发；[373ed249](https://github.com/Longshou/MCyukkuri/commit/373ed249)（CI 40187473739）低血露核灵梦右颊近边角褐色环带、粉色内壁并透见草地；[5c99a34b](https://github.com/Longshou/MCyukkuri/commit/5c99a34b)（CI 40194538150）伤口边缘斜向明暗条带；头顶细缝待复测（[发面素材](../standards/yukkuri-library/drafts/body-marks/hair-wounds/README.md)）。完成：`core_exposed` 斜看侧发后发及近边角伤口，开口外头发完整、不见皮下或透出地面；`wound_materials`、`wound_parts` 各级伤皮面／发面连续；摘饰、治疗、重载保留原伤与疤痕锚点。
- **等视觉确认的素材**：爱丽丝八景蓝瞳与蓝眼表情（`body-standard/features/alice-hakkei/`）；雷普魔 1.12 脸、行为序列与 peni 显示（`drafts/rapem-alice/` 未认可）；成体状态脸（八景 U 重绘，`art/source/adult-states/`）；稀有形态贴图（妖梦与红眼、橙尾、被炉魔理沙，登记缺认可字段）；中枢馅、去霉药、酸奶、兴奋药、幼语药、弹珠汽水喷雾。

### 演出与表现复测

- **落水与挣扎时的侧发**：已接入待试玩。[a9b60f00](https://github.com/Longshou/MCyukkuri/commit/a9b60f00)（用户反馈）魔理沙 `water_struggle` 的侧发随 `hand` 骨整片扇动像多了一只手，捕食种侧发同样装作翅膀。现只驱动承载辫子的 `hand` 骨，散侧发／前发／马尾只随身体（[动作规则](../standards/yukkuri-library/motions/variants/lore/README.md)）。完成：`water_threat`、`water_struggle` 近看魔理沙两侧侧发都不扇水、辫子仍有小幅扑水；灵梦、爱丽丝侧发静止。
- **走散寻回与放弃演出**：已接入待试玩。分离／搜寻用不安表情和呼唤，真实重逢回应与亲昵粒子，搜索超时放弃和单向断亲有表情、字幕与服务端日志。完成：与 `family_search`／`family_search_blocked` 阶段和关系读数一致，不以演出代替寻回。
- **亲油接受／拒绝赤油喂食请求**：已接入待试玩。接受／拒绝均按已执行决策回应，拒绝转身与孩子哭泣。完成：`feed` 双方回应和实际决定一致；没有物资不演成喂食成功。
- **陆 16 亲油挡捕食种**：部分接入。噗咕挑衅动作、转怒与亲子分支字幕已接，行为仍待复测；配音留最后统一补。

### 次要项与长期观察

- **陆 28 痛苦记忆随排便淡化**（21）：已接入待试玩。真实完成排便对已记住对象的恐惧减 2，不改亲近或当前伤口。完成：“亲近与恐惧独立”看排便前后 `relations.objects`。
- **默认速度多代平衡**：沿用主要试玩之后再观察的安排，见[行为与成长](BEHAVIOR.md)；不据此扩展数值改造。
