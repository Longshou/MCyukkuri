# 待办

只放未完成项，完成即删。“陆”“水”是原条目编号，只增不重排；括号内为用户原编号。状态只用[约定](../AGENTS.md#试玩与完成标准)六种。美术制作统一交 Opus；规则在所属专题，这里登记现象、版本与完成标准。已接入项集中在[待试玩清单](#待试玩清单)。

## 进行中

开工前登记，合入后移除；已登记区域先协调，不同时改。

| 条目 | 工作树 | 主要改动文件 |
| --- | --- | --- |
| 搬运放下：接生、带回窝、托幼三链放不下孩子（暂停至基建完成：第三轮 `carry_home` END 19/20，`birth_land_caught` 先放下再收帽 9/20、主线 16/20，待二分；报告 `.local/refactor/reports/wp-cs-carry-putdown.md`） | `.worktrees/wp-cs-carry-putdown` | family/ParentCarry.java、motor/action/Release.java、life/SurfaceApproach.java |
| 服务端检查第二轮：新基线复核 LOOP／PROGRESS／JUMP／LEDGER／END，过往失败目录逐行能自动报出 | `.worktrees/wp-dc-check-baseline2` | scripts/smoke_checks.py、scripts/timeline.py、scripts/fixtures/timeline/、smoke_museum.py GATING_CHECKS |
| 模组随机数全部跟随运行种子（实体随机、新个体 UUID、世界随机源） | `.worktrees/wp-dd-mod-seed` | 模组各处随机调用（client/ 除外）、status/TraceRecorder.java、scripts/README.md 种子段 |

## 玩法待办

### 复测失败/待修复
- **`rain_open`**：复测失败。雨中个体因湿透死亡；冒烟发现，版本 2067c2fcd 本地组装。完成：空地淋雨找掩体后不再湿死。
- **`hat_current`**：复测失败。缓流漂帽个体受溺水伤；冒烟发现，版本 2067c2fcd 本地组装。完成：顺流搁浅全程无溺水伤。
- **`hat_crowd`**：复测失败。满帽登船、湿木棍取用或跳岸拒绝反复，另见过早翻帽；冒烟发现，版本 2067c2fcd 本地组装。完成：超载前后各有可执行退路，无同因拒绝循环或警戒期翻帽。
- **`rescue_no_seat`**：复测失败。4 次中 1 次赤油溺水（主线 2/2 通过，次数太少不作结论）；冒烟发现，版本 [8c46bdd17](https://github.com/Longshou/MCyukkuri/commit/8c46bdd17) 本地组装。满船托幼的整链也走不完（版本 [414547b70](https://github.com/Longshou/MCyukkuri/commit/414547b70)，S2 目录冒烟存档 `.local/smoke/instance-5a0da4a1c104`）：t=175983 `carry put down refused … reason=NO_ROOM detail=no_supported_body_space`、t=175984 `rescue end result=EXHAUSTED … aboard=false last=BLOCKED reason=NO_ROOM`，落水的新生赤油没有被放下到岸上。完成：落水者被真正放到干地，帽上自家孩子全部下船，最后收回空帽。
- **`kid_boat_play`**：复测失败。赤油玩帽场景反复跳岸拒绝；冒烟发现，版本 2067c2fcd 本地组装。完成：打雷躲避与玩帽切换无同因跳岸拒绝循环。
- **`birth_boat_missed`**：复测失败。临产回岸场景接触站位不可达反复；冒烟发现，版本 2067c2fcd 本地组装。完成：放下孩子与生产交接无同因站位拒绝循环。
- **`settle_pit_slave`**：复测失败。坑奴接触站位不可达反复，另见个体死亡；冒烟发现，版本 2067c2fcd 本地组装。身份也不稳（版本 [414547b70](https://github.com/Longshou/MCyukkuri/commit/414547b70)，同一存档）：t=24154 `[mind] stage uneasy -> sweetened (unease 63, sweetness 3)`，与“长期停在不安、不会甜化”相违。完成：取食接触有退路，无同因站位拒绝循环或意外死亡；奴隶长期停在“不安”一档，不出现 `sweetened`／非油症。
- **`birth_land_caught`**：复测失败。父油把接住的赤油放下的整链走不完（版本 [414547b70](https://github.com/Longshou/MCyukkuri/commit/414547b70)，同一存档）：t=104452 `carry put down refused … reason=NO_PATH shortfall=0.38 … why=last_step_blocked`，t=104454 `carry put down reason=fetch_ended hold=braid cause=fetch_ended … rejected=braid_lift`，第二只重复于 t=104650／104653；戴回帽子不能代替放下。完成：每只被接住的赤油各有一次成功的 `carry put down … cause=release` 落到可支撑地面，之后才戴回空帽。
- **`carry_home`**：复测失败。孩子的带回链多只不完整（版本 [414547b70](https://github.com/Longshou/MCyukkuri/commit/414547b70)，同一存档）：`15db71e1` 全程没有成功接起（只有 `carry pickup stand blocked … why=no_complete_surface_route`），`30276b56` 于 t=140356 `carry nest release blocked … reason=NO_ROOM … no_supported_body_space`、t=140458 `carry put down reason=arrived_home hold=brim cause=nest_blocked … nest=OUTSIDE`，另三只同样只有站位拒绝。完成：每只初始赤油各有自己的接起 → 到窝 → 支撑面真实放下的完整链，兄弟的成功不能代替。
- **陆地家庭在场（审计 P1，[规则](FEATURES.md#子代生命周期与离家)）**：已接入待试玩（本机冒烟非验收；`den_family` 丧偶觅食复测失败，见本条末）。看点：近处看得见的孩子只看着、走失才寻，窝内孩子不算走失；受饿幼体留在亲油身边；一只赤油只由一个亲油去抬，无路站位即放弃（`carry pickup stand blocked`）；孕母在窝里安住；进不了床的孩子原地歇（`child rests short of its bed`）；走失幼体在最后所知处附近找；该问候时先问候再蹭蹭；求偶超时记 `courtship stopped reason=time_cap`。未做：母油在孩子看不见处死亡时，孤儿不知其死、不去找；本机偶见两只发情成体始终不开始求偶（`HEAT_UNMET`，样本小未定）。完成：`den_family`、`hop` 无近处 `family search started`；`settle_snow` 窝内不反复 `child separation lost`；`offering` 献身仪式成形；`indep_near` 告别 `contact=100`；`family_search` 隔玻璃的母油走到玻璃前等待至放弃；`last_words` 亲缘目睹死亡播 bereaved（`witnessed kin death`）。复测失败：`den_family` 父母可能选中不同洞穴，父油死亡后母油未开始家庭觅食（本地组装 16/20、主线 f3dcbb032 17/20，冒烟非试玩）。完成：共同入住，父油死亡后母油实际外出觅食、存粮、照料孩子；保留原有用户验收要求。
- **聚落、无头饰与人类的社会生活（审计 P5，[规则](FEATURES.md#聚落族规与迁徙)）**：已接入待试玩（本机冒烟非验收，运行版本待核实）。看点：
  - 坑与审判：`settle_pit_famine` 营养<30 时向坑沿成员 `pit slave begs`；`settle_pit_drive` 同一观察者两次拒斥至少隔 1200 tick、被拒者躲开；`colony_judge` 受审者 `hearing stance`（鞠躬／求饶／站稳），被逐者 2400 tick 内不报 `returned_exile`，视线内亲属低落；`colony_hunt` 拒绝出猎按依恋／友善／自制判定（`hunt call refused`）。
  - 无头饰：`hatless_scorn`、`hatless_kin` 被鄙视者按性格 `puff response=`（逃开／鼓腹／忽略）并对鄙视者好感同降，鄙视者不按时钟走开；`hat_shatter` 三块碎片落点各异、逐块 `piece gathered`。
  - 人类与宠物：`treatment_care` 照料上限从接触起算、第一剂不被收回；`feed_wild` 被喂后鞠躬；`myon_stick` 无木棍的妖梦走到树边 `twig pull` 后才 `kanroken acquired`；`den_move`、`den_family` 配偶先看伴侣的家、搬家只起一次。
  - 未做／待定：主人死亡时名下宠物悲伤（`custody_death`，不安量按亲缘死亡×依恋暂定，需用户确认）；`exam` 饿考生由考官喂食（需玩家）；被逐者主动离开领地（现只加宽限期）；搬家须全员装得下才提交（本机见子油先占远处大洞、双亲不搬）；NPC 作为觅食目的地。
- **陆地休闲、同伴与亡者（审计 P2，[规则](FEATURES.md#日常生态)）**：已接入待试玩（本机冒烟非验收）。休闲统一门、孤独者走向朋友蹭蹭、同伴闲逛靠近、目睹受伤／濒死亲缘去安抚、悲伤时不躲臭味、躲臭味走出半径。完成：`meet` 孤独者走近信任朋友蹭蹭（`session reserved kind=NUZZLE`）；`last_words` 亲缘在濒死期内走到身边（`[comfort] cause=dying`）；`hurt` 目睹后安抚（`cause=witnessed_hurt`）。本机冒烟 `meet` 首次问候约 46% 通过：陌生个体走向刚开始唱歌／运动的对方，停在约 2 格外，200 tick 后 `greeted=false` 放弃（版本待核实，冒烟日志 37 份）。`hurt` 三次截图均未录到舔伤（冒烟 2/2 有）：正面 `dent:1` 约 400 tick 自愈，爱丽丝先 `victim_panic` 再 `licker_habitat`，灵梦到时已 `no_wound`（截图 `.local/captures/20261108-185507-1e5496`，本地组装）。
- **首次加载卡顿余项**：部分接入（本机冒烟非验收）。剩余：陆地接生选接住落点（`BirthLaunch.catches`）单 tick 可达 0.2–1.2 s；场景布置帧约 0.5 s 不属代码；分娩期间见水生模块“分娩时卡顿突刺”。完成：各行为首次出现时无可见卡顿。
- **帽檐上不会玩的幼体进不了舱（审计 M4，`rescue_child`）**：部分接入（本机冒烟非验收）。被救起放到帽檐的幼体干爽后可被安抚，但舱内有位仍留在帽檐：入座不对帽檐幼体出手，辫子放下仍放回帽檐，亲油照护接近报 `no_reachable_meeting`。完成：舱内有空位时被救幼体进舱（自己爬入或辫子放入），亲油随后安抚。
- **`birth_boat_missed` 新生儿首餐／首便偶发不完成**：部分接入（池塘与河湾“临产划回岸边，放下孩子再生产”，本机非验收）。本机偶发：新生儿在岸边反复落水再爬出（`damage cause=drown`），或窗口内没有 `first meal swallowed`／`first toilet completed`；约半数母油上岸时孩子还在帽上，`come_ashore` 等 300 tick 后 `NO_PROGRESS`、帽子没取回，新生儿无帽落地受伤。完成：该场连续 20 次无此失败，新生儿在岸上完成首餐与首便。
- **连续游泳与划船（反馈 8、13、15、16）**：复测失败，其后已改待试玩。[bbd16394b](https://github.com/Longshou/MCyukkuri/commit/bbd16394b)：轻推与猛撞断续，游回帽子挣扎一下前进一下，持棍划水反复停顿，亲油船过慢看不到演出，同场服务端落后 265 tick。[5a5711245](https://github.com/Longshou/MCyukkuri/commit/5a5711245) 反馈 15：`self_rescue`、`shore_capsized` 游泳开头一段抽搐样动作，后半段消失。[81858504b](https://github.com/Longshou/MCyukkuri/commit/81858504b) 反馈 1：划船与静止举棍动作之间切换突兀，划着划着突然闪过一瞬举棍动作再继续划，须按首尾帧对齐衔接。完成：池塘与河湾 `hat_push`、`self_rescue`、`shore_capsized`、`shore_capsized_far`、`sail`、`kid_boat_play` 逐 tick 位移无片段节拍造成的停走，游泳与划桨动作随实际速度连续循环，游泳起手无抽搐、划桨与举棍切换无闪帧（看 `paddle turning … pose=stroke|drift`），亲油在场景内可见划行，无周期性服务端长停顿；真实碰撞、耗竭和漂流保留。
- **2026-11-02 试玩反馈**（[4064a9234](https://github.com/Longshou/MCyukkuri/commit/4064a9234)，复测 [bbd16394b](https://github.com/Longshou/MCyukkuri/commit/bbd16394b)）：
  - 试玩场布置（反馈 1、3、6、9）：复测失败，其后已改待试玩。bbd16394b：重置强开标签、嗯嗯坑玩家埋土、茎生与水流演示实体卡墙。完成：标签保留手动开关；全部场景实体与玩家起点无穿模、有真实支撑、在所属场地内且互不挤占，观察点看得到被测行为。
  - 帽船搁浅与帽子取回（反馈 7、12；11-04 反馈 14）：复测失败，其后已改待试玩。bbd16394b：浅水帽船不落底、乘员留船，远帽先等 300 tick；[5a5711245](https://github.com/Longshou/MCyukkuri/commit/5a5711245)：`hat_grounded_capsized` 开场即 `hat refused`，帽子没放成、没有演出。完成：`hat_current` 双乘员离帽、粮棍漂散、帽主接触物品即戴好并回到草岸；`hat_grounded_capsized`“落水掀翻帽子与帽子搁浅”倒扣空帽与载人载粮翻覆的帽子都搁浅成物品，帽主游过去碰到物品戴回；`hat_swap` 幼体够不到时继续日常，无 `no_way`／呼叫循环；`shore_capsized_far` 远帽直接游去扶正；`afloat_family`、`sail` 放帽点不落底（[规则](FEATURES.md#水生魔理沙)）。
  - 岸上不处理水中帽子与亲子出航反复放帽（`hat_swap`、`self_rescue`、`shore_capsized`、`shore_capsized_far`、`afloat_family`）：已接入待试玩。完成：岸上对漂着或倒扣的自家帽先招帽靠岸再扶正收回（`beckon` → `right`／`take_prop` → `reclaim done`），招不到时走进水里游去扶正再爬回（`shore_capsized_far`），无原地哭脸或岸边来回循环；亲子出航一次放帽、一次出航，待接赤油逐只辫子接上落座（`set_out complete aboard=…`），无收帽或重复出航。
  - 重做后丢失的水上表情与反应：已接入待试玩。完成：`shake_capsize` 摇晃时帽内子油害怕发抖、成体惊起，被甩进水的孩子入水惊慌、出水后松一口气；`rescue_child` 接受救助的亲油先招呼落水孩子，`self_rescue` 自救者动身时出声；初航成功返岸雀跃。只核演出与台词时机；家族呼救问答、帽子漂走／够不到、摇篮哼唱台词归[配音与台词](#最后统一补配音台词与表情)。
  - 拨水与持棍划水贴水（反馈 12、15；11-05 反馈 8）：复测失败，其后已改待试玩。bbd16394b：岸边拨水辫子浮在空中没下到水里；持棍划水木棍偏平、浮在水面上方，动作像间隔重复。[81858504b](https://github.com/Longshou/MCyukkuri/commit/81858504b) 反馈 8：无桨划水辫子仍悬在空中没沾到水。完成：`hat_swap`“陆地帽与水生帽并排比大小”岸上招帽辫梢真入水向岸拨；`sail`“捡棍放帽出航，饿了返岸”木棍拉水全程在水下、无停顿；`afloat_stick`“划去捞水面上的木棍”取棍前无桨划水辫梢下到水面；辫子与木棍不穿帽檐、身体与岸。
  - 茎生消耗个体的美术表现：已接入待试玩（[三级外观](FEATURES.md#成体发情与攻击素鸡立)）。日常草地“事后茎生：吃饱的茎长，饿的茎垂”（左栏生长、右栏营养消耗）与“事后茎生耗竭：濒死耗馅后死去”。完成：生长栏含泪略瘦，营养消耗栏垂眼灰暗更瘦，耗竭场干纹灰暗最瘦；营养消耗栏降到 30 以下转第三级，茎生完成或取消后母体仍垂眼，喂到营养与体力都≥50 才逐级消退；每次换级一条 `[family] stem drain stage A->B`。
- **帽子与帽上乘员交互**：已接入待试玩（[规则](FEATURES.md#水生魔理沙)；空帽见水生模块“无乘员的水上帽子像原版船”）。[5a5711245](https://github.com/Longshou/MCyukkuri/commit/5a5711245) 反馈 4：`kid_boat_play` 对帽中赤油右键只晃船，打不了招呼。完成：`kid_boat_play` 右键帽中赤油得到问候与 `hat use result … action=body`、左键打中赤油本人，点帽檐才晃船；`birth_land_caught`、`birth_stem_caught` 可直接照护帽中赤油；`cargo`、`shake_capsize` 保留储粮与推晃；`hat use received/result` 显示物资只扣一次。
- **陆 33 迁徙、安家与途中断粮**（46）：复测失败，见[日志问题 1](#日志问题-1群体迁徙断粮与连锁死亡陆-33)；运输演出见“亲代运输演出”。
- **水生魔理沙模块**：复测失败，见[水生魔理沙模块（重构）](#水生魔理沙模块重构)。
- **接生收尾、入窝与赤油取食（E11）**：2 复测失败（[bbd16394b](https://github.com/Longshou/MCyukkuri/commit/bbd16394b)），其后已改待试玩；1、3 已接入待试玩。
  1. E11：父油把赤油从接生帽放到地上后又放回帽里（`transfer failed … reason=contact_lost`）。
  2. bbd16394b：宠物窝入窝、离窝都从任意一侧翻过窝沿，不走入口。
  3. E11：赤油极饿取食一卡一卡（每 100 tick 一次 `ground path stopped reason=no_progress`，`food found` 重复）。
  完成：`birth_land_caught` 每只赤油各有一条 `[body] dismount cause=release`（或 `hop … owner=leave_host`），之后不再回接生帽，全部离帽后才收帽；`sleep`、`carry_home` 身体步行从低沿入口进出（`[home] bed crossing … opening=true source=walk`），入睡 `occupancy=INSIDE_SUPPORTED`，无翻侧沿或长期卡住，亲代从沿上方放幼、身体挤碰不算步行；`infant_starvation` 极饿赤油连续走到食物、无 `no_progress`、`food found` 不重复。
- **服务端检查校准中查到的真实问题**：未做（冒烟自查，非验收；分支 243503a7e 与主线 0c830e89b 本地组装，CI 运行号未核实；原始日志 `.local/smoke/instance-66f8d417c231`、`instance-23f142ac8cf9`）。
  - `forage`／`den_family` 靠近同伴反复停走：forage-2 `c5d8ce7c` 在 2.27–2.73 格外停走 6 次，t13046 `social contact ended … greeted=false … elapsed=200/200`。完成：接近与接触连续可读，保留真实距离拒绝。
  - 口部库存守恒（主线）：birth_nest_safe-536 亲油 `4715eefd` t3428777 喂食因幼体睡觉中断仍叼 1 份草，觅食接管后 t3428816 口中两份，t3428859 才吞一份。完成：进食、准备、拾取与中断归还共用同一口部占用，物品可见且守恒。
  - `birth_land_caught` 摘帽接生时身体朝向逐 tick 来回：birth_land_caught-7 `0f606699` t102325–102329 身体 33.27°／21.55° 交替、头保持 21.55°。完成：保留完整接放幼链，无反复身体摆动。
  - `birth_nest_safe` 喂食接近卡住：birth_nest_safe-9 幼体 `7a26ca55` 等 600 tick，照护者 `2d450150` t116101 `reason=stalled retries=0 stalled=51 bodyContacts=0`，t116430 才交付。完成：真实抵达并交付，失败按阻塞退出或改变条件。
  - `hat_crowd` 初态位置跳变：hat_crowd-21 幼体 `9e710557` t152265–152266 帽内局部位置未变、世界位置从帽心跳到帽沿 0.82 格。完成：初态／重载后位置权威与显示一致。
  - `shake_capsize` 搬起时幼体突抬：shake_capsize-173 `5402a6f7` t1124659–1124660 承载者几乎不动、幼体抬高 0.30 格（尚无画面确认）。完成：搬起放下接触连续。
  - `hat_crowd` 救起又放回水里（主线）：hat_crowd-548 亲油 `72d7d4e6` t3465486 `rescue end result=EXHAUSTED … reason=DROPPED`，幼体 `7670a98f` t3465525、3465585 溺水伤 3→2→1。完成：救幼放到真实安全承托，保留容量不足与路径阻塞的后果。
  - 固定观察点看不到主体：野外住所、觅食、坑地、迁徙被地形遮挡，`carry_home` 朝墙，部分水上场景主体不入镜（VANTAGE 报告）。完成：按钮初始视角看清个体与交接。

### 家庭、繁殖与照护

- **新生儿一律赤油、体型各异**：已接入待试玩。E8 茎生 `STEM JUVENILE scale=0.2` 大于胎生 `LIVE BABY scale=0.14`。完成：`birth_stem_caught`、`birth_land_caught` 每只新生儿 `stage=BABY`，同胎基础 `scale` 各异且在赤体区间；特殊形态成熟实子落地不变大，重载不重抽（[体型规范](FEATURES.md#行为性格与成长)）。
- **陆 27 繁衍剩余缺口核对**（43）：未做。对照 Wiki 列清求偶到育儿真正未实现的环节；迁徙照护、宠物许可、树叶襁褓按已有条目处理。
- **分娩阵痛与大哭触发情绪失禁**：未做。现只有受惊与压力≥80 会失禁（规则见[伤口、遗体与身体状态](FEATURES.md#伤口遗体与身体状态)）。
- **被运输的孩子仍可交互，亲油受击可能摔落**（[5a5711245](https://github.com/Longshou/MCyukkuri/commit/5a5711245) 反馈 1）：已接入待试玩（[354eeb36b](https://github.com/Longshou/MCyukkuri/commit/354eeb36b)，[规则](FEATURES.md#聚落族规与迁徙)）。用户：被运输的赤油／子油也应是可互动状态，攻击和右键菜单都生效，只是不能自由移动；亲油被攻击时有概率让所运的孩子摔落，概率按亲油数值计算；灵梦口含的看不见，可不做点选。完成：`carry_home`、`colony_migrate_babies` 搬运途中右键开菜单与攻击孩子都有效，孩子不被放下也不自己走；攻击亲油时 `[family] carry hit drop` 的 chance／roll／dropped 与其自制、伤害相符，命中的一只落到脚下的地面、水面或帽船；自制高低不同的亲油对照；灵梦放出的孩子与口含前是同一个体。
- **亲油护崽本能：对攻击者噗咕**（[5a5711245](https://github.com/Longshou/MCyukkuri/commit/5a5711245) 反馈 2）：已接入待试玩（[规则](FEATURES.md#子代生命周期与离家)）。用户：油库里应有护崽本能，会对攻击者噗咕，概率由亲油数值决定，摔落可接在这个动作上。看：出生与成长“亲油把走远的赤油带回窝”、池塘与河湾“赤油在帽檐玩耍，打雷躲避”里打身边的孩子（含帽里那只），亲油转身鼓脸噗咕，`[social] protection puff` 的 chance／roll 与友善高低相符；惊走、走开或被拖进对打都不算。完成：数值高低不同的亲油对照；同一击的摔落与噗咕各掷各的；坐帽的魔理沙用 `boat_puff`，灵梦放出孩子时先放完再噗咕。

### 宠物、商店与人类关系

- **陆 9 后半：自家宠物配对与繁殖许可**：未做。规则见[许可／发情期例外](FEATURES.md#宠物店徽章与宠物培育)；`PetLife.courtshipGate` 未限定发情期、未纳入智力。完成：正常、获许可、发情期联合判定通过／未通过及概率未受孕，核对配对、受孕和违规档案；店方相亲保留原待试玩条目。
- **陆 34 普通商店重做**（10）：部分接入。已修缺门、分大小店、全玻璃展示柜、室外放风场。余：店员即店长，稳定照料在售个体并回收逃出者；主卖赤油、子油；按[库存规则](FEATURES.md#宠物店徽章与宠物培育)定时补货，撤除走失与补货委托；页签和“售罄”图标；可选自荐求购演出。完成：长时试玩库存、照料、交易稳定。
- **陆 39 宠物油生活**（15）：部分接入。参考 Wiki 按系统闭环梳理剩余缺口；配对许可、商店、树叶襁褓不在此重复。
- **陆 41–43 人类关系与交互**（19、25、26、30）：未做。态度由油库里数值决定；对人默认关系偏低，当前过于主动打招呼，被打招呼应回礼；非语言功能改用 MC 原生交互，只有语言功能进界面。
- **陆 44 家油躲人**（25）：部分接入。隐蔽墙角筑巢、人近回窝已属[家油生态](FEATURES.md#家油生态)（试玩见“家油人类屋”）；未做：只有高利己个体挑衅人类，天真个体向人讨东西、被其他油劝走或拉走。

### 场景、信息与读数

- **陆 12 群系融入／陆 13 后半真实山地**：未做。沙漠、恶地聚落的土堆和木头融入群系；保留真实山地环境与自然演出。
- **试玩场演示场景梳理与取名**：已接入待试玩（本机冒烟与 `--layout` 自查非验收）。用户：[5a5711245](https://github.com/Longshou/MCyukkuri/commit/5a5711245) 反馈 14，场景取名都要改得更匹配实际演出，很多取名完全看不懂；[81858504b](https://github.com/Longshou/MCyukkuri/commit/81858504b) 反馈 13，水生演示很多其实是一套几乎差不多的演出，看了没有意义，名字答非所问，每轮修改要矫正，不留早就不用的场景，日志也可以看看；[f90b78a3c](https://github.com/Longshou/MCyukkuri/commit/f90b78a3c) 后：“族群迁徙的场景呢 怎么没了？记一个todo 梳理游戏的演示场景 跟之前一样合并无用展示 并且确保生成点 初始化等正确”。未合并：`afloat_family` 与 `afloat_family_gather`（两场首次 `set_out` 都可能终止）。完成：“族群迁徙与族规”场地的迁徙两场在大厅找得到并能演出；各场开场即见主题，按钮名与实际演出对应、用户读得懂；不留不用的场景；用户试玩认可。

#### 试玩场读数缺口

按场景源码，`sleep`、`toilet`、`hop`、`graze`、`wild`、`date`、`feed_wild`、`marisa_sumo`、`factory_labor`、`factory_bag` 没有读数按钮，`factory_manju` 只读种油；`last_words` 只读孩子，缺走向死亡的亲油。读数随行为链补齐。

### 演出缺口

未做演出最后与配音一起补；已有表现仍单列待试玩；不能用表现代替实际成功。树叶襁褓演出归本包，状态在[出生与成长](#出生与成长)。

- **亲油呼唤走散的孩子（call_child）**：已接入待试玩。出生与成长「走散亲子：走得到就重逢，隔玻璃就放弃」。看：北边亲油被石砖墙遮住孩子时停下、朝孩子最后所在处伸长身体张嘴呼唤三下，再张望着听，孩子转向她回应、跳一下再走过去；南边友善 0 的亲油只喊一轮，玻璃里的孩子在 24 格内也回应但走不出来；无回应时低落再找，守在窝里的赤油不出窝回应。日志 `[family] child call started/unanswered/ended`、`child heard parent call`。完成：用户试玩认可演出。
- **迎接归家**：已接入待试玩。流浪与觅食「父母去远处草场割草带回洞」。看：亲油离家至少 600 tick 回洞后，洞里看得见它的赤油走过去打招呼、蹭蹭；归来者正忙时等它空下来。日志 `welcome home started`。缺口：赤油太小且在封闭土丘洞里，从观察台难看清；待补日常草地的开放侧小窝棚场景（子油或伴侣迎接），本包不新增场景。完成：用户能看清并认可。
- **睡醒伸懒腰**：已接入待试玩。日常草地「困了走回有顶的窝睡觉」。看：睡足醒来在窝里打哈欠、伸长身体再起身（饿醒直接起），日志 `[sleep] end ... stretched=true`；从观察台看是背面，需绕到窝口看脸。完成：用户试玩认可。
- **吃饱满足瘫软（meal_content）**：已接入待试玩。日常草地「吃饱了原地瘫软一会儿」。看：自制70 成体和子油吃下苹果后原地压扁眯眼一会儿再起来，自制95 的只做普通饭后表情。日志 `[meal] full ... content=true|false reason=`。完成：用户试玩认可。

- **现有演出夸张化调优**：部分接入。四个样例（屋檐发抖、雷声缩身、呼唤孩子、吃饱满足）用户 2026-11-08 认可并已合入（[010a608c5](https://github.com/Longshou/MCyukkuri/commit/010a608c5)）；小瑕疵用户原话：「雷暴锁身用了两个几乎一样的表情。 表情集里可能也要处理 如果要表现流眼泪动态，需要调整更好，这两版差距不大。 呼唤的问题则是泪留痕和泪盈眼眶切换太快了，观感不太好」。其余演出是否按同样方式重做待用户决定；规则见[制作约定](../standards/yukkuri-library/AGENTS.md)。
- **噗咕两版**：已接入待试玩。原版加怒筋，亲油挡捕食种时用含泪硬撑（[规则](FEATURES.md#对白与配音)）。身体鼓腮试过两版，用户决定不做，保留原身体。完成：用户试玩认可两版的使用场合。
- **甜化与非油症专属表现**：未做。现复用 hop／struggle／tremble／droop，遗体快照未记录死者甜度与非油症状态。
- **出生落点演出**：未做。随机弹道已接入，缺飞出窝外惊叫、落地扬尘弹跳、碰帽檐弹进帽声音、母油目送；`LiveDelivery` 欢迎／漏接字幕按 `outcome`（窝垫、窝沿滚入、帽中、飞出窝外、落帽外）区分。
- **生殖行为演出**：已接入待试玩。擦脸前段、父方绕后、佩妮佩妮／马姆马姆、共享接触节拍与粒子；爱丽丝追击保持佩妮佩妮，受方压制显示马姆马姆。完成：`adult_pairing` 正侧背面看清回应、擦脸、绕后、接触，`home.family_state` 的 `court=` 中 `align` 不计有效接触、`contact` 后父母角色与账本一致；`alice_hunt` 追击器官不消失，喷雾、受击或遮挡后只收势。
- **食尸、伴侣与请吃了我演出**：部分接入。丰富 `carrion` 食尸、伴侣反应与 `offering` 献身请求演出，只跟随实际数值。完成：看清食尸吞咽、伴侣的选择及献身请求、回应、交付；与陆 24 分体统一收尾，不标记数值通过。
- **陆 14 雨天与雷暴演出**：已接入待试玩（本机冒烟与截图非验收）。看：天气与捕食种“屋檐下怕雨的贴着伴侣发抖”——胆小魔理沙走到伴侣灵梦身侧贴紧（帽子贴合头部，按帽檐与同伴头发／蝴蝶结轮廓留出真实间距），两只同步发抖，按“附近落雷”一起受惊后再贴回（`[weather] huddle pressed`、`thunder clap heard`），后排独处灵梦只发抖（`sheltered alone pose=tremble`）；“雷暴：胆小的缩成一团，淋雨的跑进石顶”——胆小灵梦压扁缩身偷看（`pose=cower`），胆大魔理沙只等雨停，空地上的跑进石顶。用户录像反馈原话：「这个天气包躲雨的场景魔理沙帽子怎么是歪的，都和头发穿模了」。缺口：帽子保持戴正，贴靠全程帽檐与头发／蝴蝶结不穿模；接收方单独被推开时未重新核验接触（代码审查发现，待复现）。完成：用户看到贴紧不穿模、同步发抖与一起受惊；缩身脸可读。
- **陆 5 暖桌魔理沙**（2，`kotatsu_winter`、`kotatsu_summer`）：部分接入。被炉帘布造型待视觉确认；冬天应不觉冷、不进洞冬居，夏天白天回棚下睡，现看不出区别（读数冬夏不同只补演出，相同按数值问题修）。审计 P3 已接入待试玩（本机冒烟非验收）：冷的普通魔理沙选她身边落脚（`refuge route selected … target_heat=`）；缺口：两人各自闲逛走散后，冷的那只仍去群系边缘取暖（`refuge ended gate=lost_refuge`），要靠同伴闲逛贴靠。
- **出水甩水**：已接入待试玩。看：池塘与河湾“落水灵梦爬上岸甩水”，上岸后停下甩一次（`shaking water off … after=water`、`shook the water off wetness=A -> A/2`），不反复甩。完成：用户认可。
- **晒太阳**：已接入待试玩。看：物种与稀有形态“寒冷晴天怕冷的魔理沙晒太阳”，觉冷的魔理沙在树荫外压扁闭眼晒约 10 秒再圆回（`refuge route selected … sun=true`、`refuge stopped … sun=true`）。完成：用户认可；树荫、阴雨、夜里不晒。
- **陆 5 暖桌魔理沙钻被炉（kotatsu_tuck_in）**：未做。冷的同伴挪到暖桌魔理沙被褥边拱进去只露脸，关系好主人 `shiawase`，关系差被挤出（分镜 `.local/generated-images/kotatsu_tuck_in/`）。前置：现被炉只盖住魔理沙自己，需能掀起盖住第二只的被褥侧摆（替换在用美术，先给用户同视角新旧对比）。完成：用户认可钻入与挤出两种结局。
- **陆 17 妖梦咬刀**：已接入待试玩。[ac4ba87b](https://github.com/Longshou/MCyukkuri/commit/ac4ba87b) `myon_brave` 拔刀交战时观楼剑在身体下方不在嘴里。完成：`myon_stick` 木棍正侧面横贯嘴部，走动跳动转身不脱离；`myon_brave` 交战、追击、受击、退战全程不在身体下方或原点，不与他只串位。
- **陆 22 茎生尊重重力**（7）：复测失败。[dd75d650](https://github.com/Longshou/MCyukkuri/commit/dd75d650) `birth_stem_caught`：用户问茎为何左右歪、实子是否过大（核对 0.10→0.20 未超标，见[体型规范](FEATURES.md#行为性格与成长)）；子油头顶小茎仍左右斜（版本待核实）。其后已改待试玩。完成：`aftermath_growth`、`birth_stem_caught` 母体静止小茎竖直，走动跳跃轻摆、停稳恢复；各视角无固定左右斜伸，子代不穿主茎、母体或同胞，出生从悬挂处脱落；剪下／装瓶的茎同样竖垂（[挂法](../art/props/family-life/README.md)）。
- **陆 24 献身分体**（37）：部分接入。现 GIVE 只停步、注视、低垂后进中枢濒死，遗体完整压低。要从中间劈成两半，横切面按构造分层，中枢馅／线也分两半，对应同一遗体资源；入口并入 `offering`。完成：分体可见、资源不重复（[受伤与遗体](FEATURES.md#伤口遗体与身体状态)）。
- **亲代运输演出**（反馈 2、5；[规则](FEATURES.md#聚落族规与迁徙)）：复测失败（[f90b78a3c](https://github.com/Longshou/MCyukkuri/commit/f90b78a3c)），其后已改待试玩。
  - 试玩记录：[81858504b](https://github.com/Longshou/MCyukkuri/commit/81858504b)（一次运多只等其余部分没问题）用户：魔理沙辫子穿模、动作不自然，可以自己转身到有辫子的一侧；帽子一侧满时允许用不穿模的辫子动作接触帽子、子油自己移到帽上（可接受的取舍）；辫子像机械一样先往一个方向移动、停顿、再换方向，僵硬，没有生物的流畅感；灵梦放下时孩子不是从嘴巴处出来。f90b78a3c 用户：“亲油运输的魔理沙 不管是放上去还是放下来，这个辫子都与身体和帽子穿模 而且辫子动作很诡异啊”。辫子较粗的方块段看着偏僵（模型形状，待用户判断）。
  - 已知缺口：嘴叼子油在子油体型约 0.35 以上时本身就穿身（姿态检查器测得）；帽船上辫子抬子油跳动时帽檐与亲油头发重叠。`carry_home` 魔理沙放第二、三只入窝常因落点与先放的相距 0.14 格被挡（`no_supported_body_space`，改放软垫）；窝沿／高处放下拒绝后重新规划、迁徙抓空／到达超时及二次取回收尾、嘴部放出与双尾同时包裹、观察台遮挡辫子接放；被抬孩子的表情与对白留统一补。
  - 完成：出生与成长“亲油把走远的赤油带回窝”（`carry_home`，一趟按体型带多只）与族群迁徙与族规“族群迁徙：亲油带着赤油走”（`colony_migrate_babies`）：魔理沙转向辫侧后一条连续顺滑的辫子路径抬过帽檐并平稳放下，不穿身不穿帽，帽满后叼嘴；灵梦张嘴含入、鼓腮、到窝从嘴部吐出（放出有可见动画），多只沿嘴横排互不穿插、腮更鼓；橙两尾各卷一只；真实体型无穿插、不浮不陷，窝内空位或窝外支撑点真实放下、不强放、不永久含住，行走／跳跃无异常掉落；每个亲油 `carry lift started` 与实际接起（`carry pickup hold=`）条数相符，无原地重复抬手、无 `seat_blocked`；`carry_home` 跳转点露天，三家从真实大窝认窝到接起、抵达、窝内放下，无 `assignment refused` 或 `target=null` 停搜；1.0 与 1.2 体型各看。

### 最后统一补：配音、台词与表情

**未做项最后统一完成。** 所有配音（赤油、子油、捕食种、妖梦、声线参数、听审与声音适配）排到收尾，不单独开工，也不因缺语音判玩法失败；台词随后统一收尾（表情与夸张演出用组装件补，见[演出缺口](#演出缺口)“现有演出夸张化调优”）；陆 48 体型调音、家油听感、陆 41 人类交互台词、父油自己戴回帽子时的台词并入。完成：在原试玩场核对关键动作的台词时机、角色与年龄声线、体型调音、表情及三语显示，用户听审确认（[对白与配音](FEATURES.md#对白与配音)）。

### 美术（Opus）

用户说“保存 xx”即登记为标准；制作源、快照和 `art/runtime-sources.json` 同步（[入口](../art/props/README.md)，[照料道具制作源](../standards/yukkuri-library/drafts/care-props/README.md)）。本节只留独立制作缺口；未标状态的均已接入待试玩，外观待用户确认，确认后“保存 xx”登记认可。

- **野生巢与储粮堆重做**：叶床、个人小叶巢、叶束、2×2 野生大窝、陆上储粮堆统一手绘原版风格。看 `den_family`、`forage` 叶床与储粮堆随存量四档变化；`birth_nest_safe` 野生大窝（成体与赤油在窝内、正面开口）；后期子体自建小叶巢三阶段（`den_family` 倍速）；成体站在叶床与小叶巢中围沿不穿身体。
- **宠物照料用品造型与款式**：食盆（空与油粮／小麦／短草 1–8 份）、软垫窝、2×2 编织大窝、沙厕（干净／脏）、陶厕、带盖厕与便堆；放置后手持染料右键换款式（[容纳](FEATURES.md#子代生命周期与离家)）。用户反馈成体卡软垫窝窝沿悬空（版本待核实）。已知缺口：魔理沙辫尖和鬓发垂到软卷高度时插进约 1（1.2 倍约 2.6）。看 `sleep`（成体坐进垫面，身体底贴垫面）、`family_nests`、`toilet`、`date`（母油窝两侧隔两格备用软垫窝，生产容量不因不紧挨拒绝）；食盆、陶厕、带盖厕在创造模式放置，食盆放 1、4、8 份，染料右键读 `[interaction] care style changed`；重载保持款式。
- **面粉糊、橙汁与饲料颗粒重做**：五件改 16×16 物品图标（[图标](../art/props/supply-icons/README.md)），“饲料颗粒”按三档油粮。看 `treatment_care`、`treatment_severe`、`treatment_scars` 的发放物品与 NPC 持药，商店陈列，手持与展示框。
- **嗯嗯方块分档外观**：`yukkuri_poop` `tier` 1–4 对应 1–2／3–4／5–6／7–8 份（[规则](FEATURES.md#日常生态)）；制作源 `drafts/care-props/poop_block.py`，快照 `art/source/poop-block/`；离线预览远处 1、2 档较小。看 `settle_pit` 随份数换档、`settle_pit_slave` 取食后降档，远处、坑底与平地各看；四档远处一眼可分、读作排泄物。
- **洞口结界屏障外观**：`mcyukkuri:den_barrier`（[规则](FEATURES.md#野外住所)）细枝插靠成堆、格心落叶、有缝；制作源 `ecology/wild-shelters/den_barrier.py`，快照 `art/source/den-barrier/`。看 `den_barrier` 左洞灵梦封口后洞外近处与约 8 格外、洞内背面；不穿洞壁洞顶，缝里能看到灵梦。
- **水生表情与帽冠外观**（`sad`／`afraid` 表情、破损帽外观；观察并入水生模块 P0-11）：表情由 `expressions/derived/faces.py` 从已认可贴图派生，是项目设计候选，未进标准表情集合。看 `afloat_family` 孩子探头与幼体小帽；`sail` 翻船后漂浮帽、破损帽放成船；自救成功与警报安抚的沮丧脸、受惊的害怕脸。完成：表情写入标准登记，破损外观登记认可。
- **水生种伤口与头饰损坏的配套美术**（[5a5711245](https://github.com/Longshou/MCyukkuri/commit/5a5711245) 反馈 17）：未做。用户认为与水生种伤口、头饰损坏规则相匹配的美术也缺；先对照上条破损帽外观与 `hat_damage`、`hat_shatter` 现有素材核对缺口，再按[制作约定](../standards/yukkuri-library/AGENTS.md)补。完成：水生圆盆帽的损坏阶段与碎片、水生种伤口，在 `hat_damage`、`hat_shatter` 与伤口场景远近看，与陆生种同套风格，用户确认。
- **水中游泳动作**：水里划行播专用 `water_swim`（81858504b 起各运行包已含），真实挣扎才播挣扎片段；划行连续性见“连续游泳与划船”。完成：`self_rescue`、`shore_capsized_far` 水里个体有可见划行动作，速度与节奏一致，用户确认。

## 流程与工具

提效类改动；状态与玩法条目相同，完成即删。用户 2026-11-07 决定：先完成本节基础建设，再继续游戏内容开发；高频公共需求封装成一条命令，代理不再每次重新摸索。设计审过后 3、4 并行，6 随后。

3. **服务端记录与自动检查**：部分接入。记录、十项检查、场景结束条件、每轮独立种子与重跑命令、JFR 汇总已接入；主线 31 场景×20 次校准（[f63a79b10](https://github.com/Longshou/MCyukkuri/commit/f63a79b10)）后 ORPHAN、SPAWN 已是冒烟失败门槛（[62b40f799](https://github.com/Longshou/MCyukkuri/commit/62b40f799)），FATE 由命运检查判失败，LAG（测机器负载）与 VANTAGE（场景取景）只报告；8 个已知失败仍报出。未做：LOOP、PROGRESS、JUMP、LEDGER 在旧日志里仍有误报（1／10／12／22 处），须用带新等待／碰撞／库存事务记录的新基线复核后再升门槛；END 未取得资格；种子只覆盖场景个体加入世界后的随机数（`level.random`、新生儿 UUID、台词随机等未覆盖）。要求：每次冒烟自动查卡住与原地断续、瞬移、物品凭空消失或出现、坠落溺水、遗弃幼体、服务端卡顿（附 tick 耗时与 JFR 汇总，取代各包手写的性能脚本）、观察点看不到被测个体；模组随机数可指定种子；失败附个体时间线、种子与重跑命令。检查先只报告，主线基线零误报后升为合入门槛。完成：设计页的过往失败实例在冒烟中能自动报出。
4. **截图姿态读数与审图**：部分接入。逐帧姿态读数、跟拍按画出的身体朝向、`look_at` 视角、按标记与事件挑约十帧、每个视角单独标准、按时间对齐的节拍、AI 只判可读性、一条命令出审图已接入（[8e15f2dd8](https://github.com/Longshou/MCyukkuri/commit/8e15f2dd8)）；朝向与骨骼角速度扣除片段自带转向和整体转身、逐帧缺证据单列、截图客户端崩溃已修（[7ac2fe4f0](https://github.com/Longshou/MCyukkuri/commit/7ac2fe4f0)，family_search 子视角疑似 136+5 失败降到 31+0）。无姿态或跟拍主体缺失的机位也出完整审图并单列缺证据、`hurt` 正面伤口机位、妖梦木棍与交接途中持物按实际混合路径检查、机位不再继承整场景观察标准（[2b3ecd91b](https://github.com/Longshou/MCyukkuri/commit/2b3ecd91b)）。未做：判定带只报告、告警未逐帧人工标注成标定集，`gap` 未校准；特殊伤口／破帽／产出替换表面、划水桨深与桨尖仍报缺证据。完成：任一场景一条命令出审图结果，误报明显少于当前（48 帧中 2–32 帧）。
6. **资源导出与动作流水线**：部分接入。统一导出、素材目录归类与分片登记删除已接入（[51e665bbd](https://github.com/Longshou/MCyukkuri/commit/51e665bbd)，Build [42446003796](https://github.com/Longshou/MCyukkuri/actions/runs/42446003796)）；全量 `--check` 63 PASS／27 NO CHECK、目录 0 未归类、分片删除后重建仍删除。完成：下一个动作包全程不写临时脚本。

## 待用户裁决

设定来源之间、或来源与项目设计之间需要用户决定的冲突；游戏已采用的以游戏实现为准，不列在这里。每条写清各方说法与出处、对游戏的影响，用户决定后移入所属文档并删去。

- **环境捕食者与其它吃同类的种类**：站点记鸟、鼠、猫、狗会伤害油库里，Letty、Yoshika、Rumia 等型也捕食同类（[来源表](../wiki/hakkei-reference/sources-fandom.md) F04、F36）；项目只实现蕾米莉亚与芙兰两个捕食种。选项：维持两种／加入不具名的环境捕食者（猫、鸟）／扩大捕食种名单。影响：威胁扫描与逃跑、护幼链路、试玩场景数量。
- **幼体的“答应慢慢来才活得下去”**：站点写幼体未获亲代或饲主回应会拒食、停止活动，约二十四小时仍未听到便可能死亡（F35）；项目已有出生问候，但只按真实饥饿、伤势与照料结算，没有回应存活判定。选项：不加／只作不安压力（不致死）／按站点写成存活门槛。影响：新生儿首餐链、亲代回应演出、幼体死亡率。
- **临时替代帽**：站点写城市流浪个体用纸箱、瓶盖、冰淇淋筒或假饰物顶替丢失的帽子，同类看得出是假货、鄙视但不驱离（F04、F22）；项目只认原饰物与同种碎片，缺帽者无法被认出。选项：不做／只作外观不参与识别／参与识别但降低好感。影响：识别链、物品来源、“失饰”的缓和手段。
- **完全水生的种类**：站点列 Nitori、Wakasagihime 等不怕水的型，并称部分型能长期在水下生活（F18），另写把丝带当筏的“水生灵梦”（F151）；项目只有帽船水生魔理沙，其余种类落水即受水伤。选项：维持现状／只做外观亚种／做真实水生亚种（需独立的溺水与救援规则）。影响：溺水与救援、帽船亚种的独占性。
- **商品化宠物品系（迷你油、即时油、哭泣油）**：站点写宠物市场把主线种育成改良品系：迷你油成体只有几厘米、永远带幼体口音，即时油冻结成包、由饲主解冻，哭泣油专卖给施虐者（[来源表](../wiki/hakkei-reference/sources-fandom.md) F40、F44）；项目宠物就是主线种族个体，按阶段、体型、徽章与技能区分，没有这些品系。选项：不做（店铺只卖普通个体）／只做“迷你”体型的普通个体（沿用同一套属性与技能，不新增登记）／做独立品系（新种族登记与专属饲养规则）。影响：种族登记与存档契约、活体库存与定价、体型与成长规则。

## 低优先级

- **陆 35 鬼威惨商店**（10）：未做。售卖虐油用具、劣质虐用个体和特殊处理过的个体，后续再做。
- **陆 36 工厂重做**（6）：未做。重点是加工厂里的遭遇演出，不重机械，后续再做。
- **宠物店与玩法补缺**：未做。地面甜食偷吃、店方向野外收购、金章自由活动、手册交流页、玩具分档、散步路线、货架实体；考试／课程／相亲／购买演出不跨重启恢复；胎次页与未知父体显示。
- **工厂与商店补缺**：未做。工厂轮换工位、分选、整茎采收、批次育成、多体袋装未接入；商店后场库存定时补货的间隔与库存目标未定（见[人类世界](FEATURES.md#人类世界)）。

## 暂缓/不重要

保持暂缓，用户未重新提出时不开工。

- 用户不提不开工（未做）：[DOS](FEATURES.md#妖梦与稀有变体)、[捕食种误判危险](FEATURES.md#野外事件)、[村边纠纷](FEATURES.md#人类世界)、[饲主威胁线](FEATURES.md#宠物店徽章与宠物培育)、网络包同步和可选兼容。部分接入、现状待核对：[工厂流水线与 NPC 态度关系](FEATURES.md#人类世界)。
- 未做：[认屋成员脱离聚落、解除伴侣与强盗团离开消散](FEATURES.md#野外事件)；运行资源瘦身（先测完整 Jar 大小与加载耗时）。
- 未做：饥荒断粮献身（目前粮尽只有争吵与外出觅食）；迁徙行军觅食阈值未定；亲代拒绝分食目前只降低好感；红灵梦、早苗种、穴ゆ、牧ゆ、兄貴種、身體種、嗚箱讲堂名称无规则未做；Patchouli／Jade 等可选兼容未接入（见[季节](FEATURES.md#季节体感温度与储粮)、[聚落](FEATURES.md#聚落族规与迁徙)、[基础生活](FEATURES.md#基础生活与饲养操作)）。

## 运行证据索引

用户试玩的运行版本在此登记一次，条目只写版本链接与反馈编号；日志用于诊断，不代替用户试玩验收。会话与原日志在 `.local/experimental-map/`，副本见各行。

| 编号 | 运行版本 | 已确认现象／边界 |
| --- | --- | --- |
| **E1** | 10-21 22:41 至 10-22 00:04，[f4872105](https://github.com/Longshou/MCyukkuri/commit/f4872105)，[CI 39595554878](https://github.com/Longshou/MCyukkuri/actions/runs/39595554878) | 头饰、捕食、遗体／治疗及出生与成长反馈的共同运行包。 |
| **E2** | 10-22 00:10–00:39，[372fa23d](https://github.com/Longshou/MCyukkuri/commit/372fa23d)，[CI 39630804471](https://github.com/Longshou/MCyukkuri/actions/runs/39630804471) | 中枢按钮卡死、岩浆块过快致死、护尸走停、治疗持物不可见。 |
| **E3** | 00:45–01:09，[cc0e78ff](https://github.com/Longshou/MCyukkuri/commit/cc0e78ff)，[CI 39634939963](https://github.com/Longshou/MCyukkuri/actions/runs/39634939963) | 天气、捕食、睡眠与闲逛场景反馈。 |
| **E4** | 01:25:54 起 [d1cf4f1f](https://github.com/Longshou/MCyukkuri/commit/d1cf4f1f)，[CI 39641090628](https://github.com/Longshou/MCyukkuri/actions/runs/39641090628)（此前仍为 E3） | 桌面复测未再卡死（仅诊断）；01:29–01:38 护尸、移速反馈归属 d1cf。 |
| **E5** | 01:44–01:58，[193a87ae](https://github.com/Longshou/MCyukkuri/commit/193a87ae)，[CI 39644324457](https://github.com/Longshou/MCyukkuri/actions/runs/39644324457) | 天气、捕食、困倦、认窝与通用身体复测。 |
| **E7** | 10-25 16:45–16:51，[b1b7d88f](https://github.com/Longshou/MCyukkuri/commit/b1b7d88f)，[CI 40177589999](https://github.com/Longshou/MCyukkuri/actions/runs/40177589999) | `core_exposed` 全程严重卡顿；烧伤斑驳、侧边部件错位、泪痕浮出；爆裂伤洞口污渍、断发参差、头顶深伤交界细缝。 |
| — | 10-25 21:35–22:10，[ac4ba87b](https://github.com/Longshou/MCyukkuri/commit/ac4ba87b)，[CI 40196337976](https://github.com/Longshou/MCyukkuri/actions/runs/40196337976) | 捕食、妖梦、迁徙、嗯嗯坑反馈；日志副本 `.local/experimental-map/play-museum/20261025-211412-264.log`。 |
| — | 22:24–22:38，[dd75d650](https://github.com/Longshou/MCyukkuri/commit/dd75d650)，[CI 40200352271](https://github.com/Longshou/MCyukkuri/actions/runs/40200352271) | 茎生、窝与接生反馈。 |
| **E8** | 10-27 21:50–22:37，[ce9c0bfc](https://github.com/Longshou/MCyukkuri/commit/ce9c0bfc)，[CI 40465587203](https://github.com/Longshou/MCyukkuri/actions/runs/40465587203) | 出生与水上链路反馈；E8、E10 副本在 `.local/reports/birth-aquatic-fix/`。 |
| **E10** | 10-28 01:52–02:02，[98038210](https://github.com/Longshou/MCyukkuri/commit/98038210)，[CI 40550269285](https://github.com/Longshou/MCyukkuri/actions/runs/40550269285) | 出生、水上与动作细节复测；条目中的时间为录像“屏幕录制 2026-10-28 020258.mp4”偏移，抽帧在 `.local/screenshots/birth-aquatic-20261028-020258/`。 |
| **E11** | 03:22–03:37，[2715d9c1](https://github.com/Longshou/MCyukkuri/commit/2715d9c1)，CI 待核实 | 陆地接生收尾、宠物窝入睡、赤油取食与水上自救、帽子归属回归。 |
| — | 10-30 02:32–02:46，[a9b60f00](https://github.com/Longshou/MCyukkuri/commit/a9b60f00)，[CI 40928640596](https://github.com/Longshou/MCyukkuri/actions/runs/40928640596) | 陆地接生、茎生与落水反馈。 |
| — | 22:35–23:02，[b333bfc0](https://github.com/Longshou/MCyukkuri/commit/b333bfc0)，[CI 41070657813](https://github.com/Longshou/MCyukkuri/actions/runs/41070657813) | 水生重建后首轮复测与陆地接生反馈。 |
| **E12** | 10-31 14:07–14:31，[55449fd63](https://github.com/Longshou/MCyukkuri/commit/55449fd6381619dec7d211af4748778c5ac76e54)，[CI 41131967155](https://github.com/Longshou/MCyukkuri/actions/runs/41131967155) | 陆地接生与水生复测；用户反馈水生魔理沙几乎全部演出有问题；暂停时间不计观察时长。 |
| — | 11-02 01:44 起，[4064a9234](https://github.com/Longshou/MCyukkuri/commit/4064a9234)，[CI 41332517724](https://github.com/Longshou/MCyukkuri/actions/runs/41332517724) | 2026-11-02 试玩反馈 1–18。 |
| — | 11-02 20:40–21:22，[bbd16394b](https://github.com/Longshou/MCyukkuri/commit/bbd16394b)，[CI 41438818895](https://github.com/Longshou/MCyukkuri/actions/runs/41438818895) | 11-02 反馈复测；20:57:24 服务端落后 265 tick。 |
| — | 11-04 00:16–01:18，[5a5711245](https://github.com/Longshou/MCyukkuri/commit/5a5711245)，[CI 41615566507](https://github.com/Longshou/MCyukkuri/actions/runs/41615566507) | 11-04 反馈 1–17；会话 20261104-001607-669、20261104-002901-312，副本 `.local/reports/playtest-1104/`。 |
| — | 11-05 01:14–01:41，[81858504b](https://github.com/Longshou/MCyukkuri/commit/81858504b)，[CI 41863598091](https://github.com/Longshou/MCyukkuri/actions/runs/41863598091) | 11-05 反馈 1–13；会话 20261105-011400-077，副本 `.local/reports/playtest-1105/latest-20261105-011400.log`。 |
| — | 11-06 00:08 起，[6ace787dc](https://github.com/Longshou/MCyukkuri/commit/6ace787dc)，[CI 42042884686](https://github.com/Longshou/MCyukkuri/actions/runs/42042884686) | 11-06 反馈 1–6 与分娩卡顿；副本 `.local/reports/playtest-1106/`。 |
| — | 11-06 19:57 起，[f90b78a3c](https://github.com/Longshou/MCyukkuri/commit/f90b78a3c)，[CI 42185057018](https://github.com/Longshou/MCyukkuri/actions/runs/42185057018) | 亲代运输复测、演示场景梳理请求；副本 `.local/reports/playtest-1106b/`。 |

## 实时日志发现的问题

试玩时由日志与现场读数发现的未完成问题登记在这里；新证据更新原条目。每项保留场景、运行版本、现象、状态与完成标准；版本不明写“版本待核实”；日志无记录不等于未发生。

### 日志问题 1：群体迁徙断粮与连锁死亡（陆 33）

场景在“族群迁徙与族规”场地：`colony_migrate`“族群迁徙：全族搬去东边空村”、`colony_migrate_babies`“族群迁徙：亲油带着赤油走”。

- [c235e189](https://github.com/Longshou/MCyukkuri/commit/c235e189)（[CI 39427119109](https://github.com/Longshou/MCyukkuri/actions/runs/39427119109)，2026-10-20，后期 5 倍速）：多轮幼油断粮饿死与亲油献身，双亲几乎同时献给同一孩子，族长帕琪也饿死；饿死的幼油距新址仍约 36–48 格；已到新址附近的幼油住所仍指旧窝、`idle` 至饿死，双亲已认领新窝、营养 82；受赠孩子后变非油症触发 `non_yukkuri` 制裁。
- [ac4ba87b](https://github.com/Longshou/MCyukkuri/commit/ac4ba87b)：两轮均 `migrate_failed reason deadline phase MARCH`。体弱的族长帕琪走最前，体力耗尽仍被 `urgent_need` 拒绝停下，随队者在她身边等待；到达后无成员在新址认屋（`settle waiting reason=no_home`），离开广场后又被拉回直到超时；超时后折返途中数百次 `settlement goal refused … reason=urgent_need`，多数停在半路。

问题与完成标准：

- **饥饿、迁徙安置与取食互相阻塞**：复测失败（族长饿死的具体阻塞缺读数，不并入同因）。完成：达到行军饥饿阈值后，迁徙让位于可执行的觅食、补给或安置；幼油继续抵达、住所切新窝、取食后营养回升，不因互卡持续 `idle` 至死；族长若仍饿停，补当时 Goal、阶段与粮源读数再修。
- **亲油照护与献身衔接失衡**：复测失败（友善决策失败与物理阻塞应区分）。完成：有意愿且条件允许的亲油接上寻回、真实粮食交接与喂养，来源和消耗可核对；可用食物或已生效救助不被误判为绝境，双亲不对同一孩子重复献身；保留真实缺粮和性格决策。
- **赤油行军资格与亲代运输**：已接入待试玩（[331427af](https://github.com/Longshou/MCyukkuri/commit/331427af)，晚于两个失败版本；[规则](FEATURES.md#子代生命周期与离家)；承托演出见“亲代运输演出”）。未收紧：极饿赤油越界搜索；无亲油能抬时退回爬行跟随；无往返接力。完成：`colony_migrate_babies` 行军开始后亲油走近孩子、孩子坐上承托位随行（读 `home.family_state` 的 `carry=`、`count=`；`[family] carry lift started／pickup／put down reason=…`），孩子不再自己爬跟随；营养低于 30 时放下喂食再抬起；新址认屋后放下、住所切新窝；口含的母亲不备口粮；`colony_migrate` 同看；重载按乘客续接不复制不瞬移；配合 `infant_starvation` 核对极饿不越界。
- **先锋、新址自建与途中卡住**：已接入待试玩（ac4ba87b 之后）。完成：`colony_migrate` 读“读迁徙与压力（含先锋）”“读全体成员安置”（`identity.settlement_detail` 的 `result`／`vanguard`，`home.pos` 变到 -720 附近），日志 `migration vanguard`、`migration pivot arrived`、`settle started`、`settled`、`site settle found no home`（空位／被占／放置失败／拒绝认领计数）、`migrate_failed` 末尾计数与先锋、族长距离。通过：先锋先到；多数成员新址 `settled` 并有新 `home.pos`；`migrate_done` 后中心改为目标；族长卸载时仍可由在场参与者推进；幼体新窝与亲代一致；失败原因可由计数读出，折返成员不长时间卡在半路；不以停用能力或强制到位掩盖。
- **边界**：未做。规划站点粮仓洞存粮不随迁；无软土坡面的平地新址不能自建住所（选址跳过）。用户提出后再定完成标准，补齐前不写成迁徙完整。
- **途中危机**（项目设计）：未做。死亡已计入迁徙压力，但未接停队、送粮或救援；普通日志无记录不写成“完全没反应”。设计：族长或代做盘点者在行军中比较停队意愿（随低于行军觅食阈值者口数加权比例与途中饿死数上升）与赶路意愿（随捕食种压力、威胁计数、剩余距离、天色上升；宽厚偏停队，大胆自私偏赶路）。停队就地进行：先请携粮者经嘴贴接触按傍晚盘点的缺粮户与优先份交粮（拒绝为真实拒绝，自私族长照 `unfair_ration` 多留），不足则就地共同狩猎（不受时段与次数限制，草直接交缺粮户、不进公仓不收上缴，途中空猎不计领地枯竭）；补给完、狩猎折返或超过一次狩猎期限即续行。族长不派人回头，掉队者按行军来源自行走，亲属按既有走失寻回规则决定是否回头。途中死亡计入本次迁徙结果、完成时与旧址死亡一并清零，安顿期结束后才按原权重计入压力。复用既有盘点、共同狩猎、寻回和族规动作；日志 `[settlement]` 记停队／续行决定与意愿分值、口粮交接、途中狩猎与死亡。完成：`colony_migrate` 读数看到停队／续行决定与意愿分值、口粮交接与拒绝、途中狩猎结果，区分集体迁徙结束与个人未安置，显示存活掉队者、粮食与死亡压力的处理；不以中心已变或仅触发制裁算通过。

**复测读数**：“清空本场地”后重开，于出发、幼油营养接近／低于 30、抵达及死亡前后读“读全体成员安置”“读迁徙与压力（含先锋）”“读新聚落状态”；对照 `needs.nutrition`、血量、Goal、`identity.settlement_detail`、`home.pos`、`home.family_state`、`home.habitat_state`、`home.offering_state`、各户／公仓粮数、`migrate_done`／`migrate_failed`。亲属死亡反应另核对距离、识别、视线与情绪；无哭喊日志不单独判失败。专题：[聚落迁徙与分粮](FEATURES.md#聚落族规与迁徙)、[子代生命周期](FEATURES.md#子代生命周期与离家)、[受伤、遗体与献身](FEATURES.md#伤口遗体与身体状态)、[情绪链](FEATURES.md#情绪链)。

## 待试玩清单

以下未标状态的条目均为**已接入待试玩**，仍须用户用对应运行包确认；历史失败不表示新修正已验收。按场景（`scripts/museum_scenarios.py` 的 id）分组；演出问题记到[演出缺口](#演出缺口)；操作见[试玩场](FEATURES.md#试玩场与动作博物馆)。

### 生存、捕食与伤病修正

- **陆地审查 P6 场景起点**：已接入待试玩（本机冒烟非验收）。初态改为真实起点，重开即见主题，无需玩家先修：`hurt` 两只橙身上是真实磕伤（`[comfort] approach started`、`mouth contact started`）；`alice_chores` 是一对共用一洞的伴侣（`[habitat] … home chosen` 同窝）；`sacrifice` 子油与亲油亲近（`[grief] protection diverted … response=protect_child_worry`）；`colony_hunt` 场边铺草后族长召集出发（`[settlement] event=hunt_call … detail=party n dest`）；`colony_ration` 等约 90 秒黄昏分粮（`event=ration … relief n/m`、`event=ration_fetched`）；`house_pair`／`house_eggs`／`widow_repair` 为真伴侣、`shared_body` 为同一主人；`den_move`、`indep_near` 只留一所空窝，`den_barrier` 两只捕食种各在自己的灵梦进洞后出现；`wild` 只有一个野苹果；`hatless_monster` 改用不漏馅的凹伤（主角撑得过观察时间）。完成：各场景按上列日志与读数开场即见主题。
- **赤油受伤原地哭泣**：已接入待试玩。此前受伤赤油会跑动逃跑。完成：`birth_land_missed` 摔落赤油不走动、哭脸垂身（`[childhood] hurt cry start/end`），亲油赶来舔舐；攻击赤油无 `flee`，子油受击仍逃跑。
- **妖梦体力系数未生效**：已接入待试玩（源码修正 [c3033e91](https://github.com/Longshou/MCyukkuri/commit/c3033e91)，无试玩复现）。完成：`species_fatigue` 妖梦、灵梦、帕秋莉同初态，`species.fatigue.steps=20` 时代谢 `cost` 分别 18／20／25，重载不带入上次统计。
- **陆 38 捕食种腾空台词**：已接入待试玩。原生会飞的捕食种说“好像在天上飞”（版本待核实）。完成：`airborne` 普通高抛只触发一次，小跳和蕾米莉亚／弗兰均不触发。
- **固定群系下的日常储粮**：已接入待试玩。已移除秋储规划、秋季加征和过冬预留。完成：`forage`、`settle_taiga`、`colony_hunt`、`colony_ration` 读库存／公仓目标与上缴比例，秋季群系不为未来冬天囤粮或扣留公粮。
- **陆 14 避雨搜索与雨天活动**（4）：已接入待试玩。E1 找掩体偏低且避雨不应锁死活动；E3 雨伤、湿润和水生避雨缺口；E5 严重饥饿者雨中 `idle`。看点：`rain_life` 饿油吃完一枚苹果接着出去吃下一枚，营养 30 才回去躲雨，进屋 `shaking the rain off on arrival`。未解决：`rain_open` 无掩体时仍按随机迈步搜索，本机常有个体找不到屋顶而死于雨伤。完成：`rain_shelter`、`rain_open`、`rain_life` 读搜索目标、遮蔽、Goal、不安、营养与湿度；无掩体仍合理搜索，干燥区恢复活动，极饿真实冒雨觅食。
- **陆 16 挡捕食种与亲油反应**（39）：已接入待试玩。2026-10-20（版本待核实）`sacrifice` 只追亲油；E1 子油→亲油转移不证明完成挡护；E3 看不清挡护；E5 噗咕后子油受咬、捕食种落地清空猎物、亲油下一 tick 中断。[ac4ba87b](https://github.com/Longshou/MCyukkuri/commit/ac4ba87b) 亲油被挑衅后芙兰约 25 tick 咬一次（2.0），5 次杀死亲油，清场后立刻再选子油。完成：`sacrifice` 确认真实护子或退避（`behavior.protection`、血量），低意愿仍可抽中；转怒后命中间隔≥40 tick（`mode predation -> combat`）；亲油死亡后 `eat pause started; reason=combat_kill`，芙兰不起飞追猎，饿时 `remains feeding via=eat_pause` 至吃饱或遗体取尽才再选目标。
- **陆 17 妖梦战力**（40）：已接入待试玩。[ac4ba87b](https://github.com/Longshou/MCyukkuri/commit/ac4ba87b) 芙兰贴地连咬妖梦，约 25 tick 一口、每口 2.0，14 点血打到 4.0 退战后被补死。完成：`myon_brave` 双方都造成实际伤害，健康同阶段妖梦略弱于捕食种，按胆量退避；`combat strike` 的 `since_last` ≥40，战斗中芙兰 `nutrition` 不因命中增加；退战或体力耗尽后不在妖梦脚边落地（`withdrawing by air`，落点距对手 ≥16 格），先挑只会跑的猎物（持棍且敢战者评分更差）。
- **饿极食尸与伴侣决策**：已接入待试玩。E2 护尸走停及 `guard_path_blocked`；E4 护尸者只打一次，食尸者返回后不再护尸。完成：`carrion` 对照大胆攻击、胆小抗议与自愿食尸（`guardStyle`、`decision`、`score`、伤害、遗体余额），返回后仍响应，保留拒绝与能力不足后果。
- **饿极攻击失饰者**：已接入待试玩。`cannibal_hunt` 攻击意愿按饥饿、友善、自制、胆量、恐惧和能力差判分。完成：两组对照读决策、追咬目标、伤害及吞咽营养，保留真实拒绝与危险后果。
- **陆 24 请吃了我触发条件**（37）：已接入待试玩（[触发阈值](FEATURES.md#伤口遗体与身体状态)）。完成：`offering` 对照绝境、低意愿和普通饥饿，观察请求、交付、中断；一般饥饿不轻易献身，补给与照护可中止，不写 Goal 或成功状态。
- **陆 49 橙汁治伤、面粉糊去疤与 NPC 治疗**：已接入待试玩。E2 看不出持药。完成：`treatment_severe`、`treatment_scars` 普通伤概率留疤、大伤必留，橙汁保留旧疤且不治油霉，面粉糊只去疤；`treatment_care` 按需先橙汁后面粉糊、逐剂持物、扣余量，无效不扣料；绷带移除及旧档背包、箱内迁移仍待试玩。
- **陆 50 中枢作为通用身体结构**：已接入待试玩。E2 `core_filling`、`core_exposed` 生成后卡死无栈（E4 未再卡死，仅诊断；`[body-render]` 记耗时）。完成：外露中枢命中立即死亡并留遗体，旁侧不致死，能取得中枢；两按钮反复生成、转视角、治疗闭口、取核不卡死。
- **低营养虚弱与饥饿掉血**：已接入待试玩。E4 看不出减速。完成：`nutrition_age` 对照移速、营养、血量和 `needs.fatigue` 计时；补食后计时归零、效果到期，饥饿仍可致死。
- **定向噗咕的数值回应**：已接入待试玩。完成：`puff_response` 四种初态待自然制裁宣告，核对 `[social] puff` 输入、选择、Goal 与移动／伤害；`carrion` 饥饿目标对护尸噗咕的反应。
- **新增演示到场传送**：已接入待试玩。E5 仍未传送。完成：`fatigue`、`fatigue_predator`、`nutrition_age` 各观察台启动均传送到样例附近，清场重开一致。
- **困倦打盹、胆量睡时与危险惊醒**：已接入待试玩。ac4ba87b 两次打盹间隔不足 0.5 秒。口渴见[基础生活](FEATURES.md#基础生活与饲养操作)。完成：`fatigue` 两次打盹至少隔 15 秒；`yukkuri status … raw` 的 dryness 随时间上升，水边个体约 40 分钟后自行喝水不扣血；`fatigue_predator`、`sleep` 对照停步、睡眠表情、困值、危险惊醒和捕食目标，不指定 Goal。
- **成长营养需求与献身供养总量**：已接入待试玩。E2 要求幼体需求低于成体，默认消耗下献身余额可供两只子油约五天。完成：`nutrition_age` 对照需求与营养扣值；`offering` 共享真实余额与吞咽，受伤、腐败和成长仍减少可供时间。
- **空手击打无伤口反馈**：已接入待试玩。E7 `core_exposed` 空手击打只首拳留一级凹伤，重复击打 `wound unchanged`。完成：对“满血爆裂”个体背面连打同一处，第 3、5 拳分别升二、三级，`[body]` 点数前后值对应；每拳命中点有馅屑（火烧冒烟）。
- **旁观受伤语音串用**：已接入待试玩。E2 旁观者播放自己的受伤语音。完成：`hurt` 轻击一只，只有受伤者痛呼。
- **漏馅止漏演示覆盖**：已接入待试玩。E2 缺大伤口与露核对照。完成：`wound_leak` 读漏血、`core_site`、`wound_anchors`；橙汁闭口止漏并原位留疤，面粉糊仅去疤，场景不卡死。
- **无粮时主动搜索（E3 反馈 6）**：已接入待试玩。ac4ba87b 搜索与 `seek_food` 是两段状态、表情不对，被攻击或雨中觅食仍慢悠悠。完成：`forage_empty` 空手饥饿灵梦流口水并游荡；手持苹果走近立即走向玩家（`search met player holding food`），丢下后 `search saw loose food` 再吃；雨天干燥区活动与攻击后 `[urgency] urgency speed on`、`move_speed` 约 ×1.3，结束 off；无粮不假装取食。
- **捕食种／水生种通用身体与地面动作（E5 反馈 3、4）**：已接入待试玩。E5 捕食种缺油菊、伸缩与步态仍旧。完成：`shared_body` 四种对照核对油菊、互斥伸缩、通用步态及落地后真实行走。
- **岩浆块烫伤脚底**：已接入待试玩。E2 伤害过快。完成：`magma_sole` 健康个体从四格岩浆块中心活着离开，石头对照不烫伤，离开后不再新增接触伤。
- **野生灵梦入巢结界**：已接入待试玩（项目设计，用户要求；[规则](FEATURES.md#野外住所)）。完成：`den_barrier` 左洞（90·90）依次 `den seal started`、`den seal completed`（twigs 2 → 1 或 0）、屏障出现、`target rejected reason=barrier`，捕食者离开后 `den barrier removed reason=danger_passed twig_returned=true`；右洞（40·30）只逃入窝，`den seal skipped reason=traits_low`；被攻击或洞口有他者时中止且不扣枝。

### 出生与成长

未注版本的反馈来自 E1（23:38 至次日 00:04）。

- **陆 23 走散寻回、分离不安与母油放弃（反馈 1）**：已接入待试玩。演示距离 30 格、搜索 48 格；母油按孩子数和性格的搜寻计时、单向放弃；重载保留计时与解除方向。完成：`family_search` 北边一对真实重逢，南边隔玻璃一对受阻计时与双向认亲差异；`home.family_state` 读搜索、时限、dismissed 与当前窝。
- **成长标签与数值展示（反馈 2）**：已接入待试玩。用户反馈标签老旧；现读实际行为，区分赤油、子油、亚成体、成体、老年，补生命、不安、距换阶段秒数。完成：`growth`、亲子与接生场景标签随阶段更新，数值与 `/yukkuri status` 一致。
- **陆 25 陆上接生后的幼体安置与收帽（反馈 3）**：复测失败，其后已改待试玩。用户决定：帽子形状与赤油比例保持不变。
  - 试玩记录：[b333bfc0](https://github.com/Longshou/MCyukkuri/commit/b333bfc0) 产程中母油轻易改变朝向、孩子却仍落进帽里；辫子抓取不自然，抓完父油快速转一圈；有赤油没进帽时父油表情在震惊与期待间快速切换；没有按设定打招呼（「ゆっくりしていってね」）。E12 `birth_land_caught`、`birth_stem_caught` 辫子把赤油放到右后方而非面前、嘴叼不可见。
  - 已知问题（本机）：父油放帽点与母油 `birth_site` 站位相差约 1 格时整胎落地，两者须一致；[b3e32e4a2](https://github.com/Longshou/MCyukkuri/commit/b3e32e4a2) 曾连续出现 `born=0` 或无接生帽 `outcome=GROUND`，原因未查。
  - 完成：`birth_land_caught`、`birth_stem_caught` 的 `[choreo] beat id=live_birth|stem_birth` 依次走到 `placed`、产程（茎生 `drop`）、`linger`、`unload`、`take_hat`、结束；`catch site selected`、`catch hat placed`，每只赤油一条 `[body] dismount cause=release`（会跳的自己跳出），嘴叼可见、辫子在父油面前放下，之后不再回帽；`catch cleanup completed` 后戴回同一顶帽（“帽子已戴回头上”）；收尾受阻时有 `catch cleanup gave up reason=` 并由帽主随后收帽，不永久停在帽边；`birth_land_caught` 父油从远处走来，会合失败有 `next=regroup` 与第二次选点；两场各 10 次全为 `outcome=HAT`；`birth_land_missed` 两只真实落地受伤，与接住场分开核对。
- **普通窝容量、大窝与围产期同居（反馈 4）**：已接入待试玩（[规则](FEATURES.md#子代生命周期与离家)）。[dd75d650](https://github.com/Longshou/MCyukkuri/commit/dd75d650) `birth_nest_safe` 母油停在野生大窝窝沿上约 36 秒，手推才生产，首只赤油落窝沿顶仍记 CUSHION 且不动；伴侣反复 `nest claim refused reason=no_supported_body_space`；陆地接生足月母油（宽 1.169）被软垫窝拒收。[a0300e75](https://github.com/Longshou/MCyukkuri/commit/a0300e75)（CI 40376399575）`birth_nest_safe`（原 pet_birth 场景）受孕后卡死，无线程栈。用户改判：伴侣拒收不再作真实后果，宁可少量穿模也要放得下、判定稳。完成：`birth_nest_safe` 反复清场重开，母油不跳地走进大窝、不停窝沿，无长时间 `no_place_for_term`，到位后才 `delivery labor started`（窝口太窄另记 `[home] nest doorway too narrow`）；落窝沿的赤油 `newborn on nest rim rolls inward` 后 `outcome=CUSHION`；伴侣 `assigned kind=care` 认领同窝；`birth_land_missed`、`birth_land_caught` 不再 `nest no longer fits stage/body`，`near-term leave nest reason=…` 后在窝外生产。1.2 倍足月孕母配 1.05 倍以上伴侣仍认窝失败是保留的空间上限。两种新窝模型待用户确认。
- **窝内外寻路与接触交接（反馈 5）**：复测失败。用户反复看到途经窝时被卡住；陆地接生部分已随陆 25 重写，其余场景未改。完成：`feed`、`first_toilet`、`hurt`、`meet`、`marisa_sumo`、`birth_land_caught`／`birth_land_missed`、`family_search`、`indep_near` 覆盖斜向接近、双方窝内／外、远侧站位、途中亲油移动；读 Goal、路径、接触距离与阻塞原因，途中探路不换当前目标，窝柱夹缝走完整绕路或换可达接生点，真实进出并完成交接；不用瞬移或强制留守。
- **陆地出生随机弹道与落地结果**：复测失败，其后已改待试玩（[规则](FEATURES.md#消耗品与胎生)；水上帽船出生与无母油分离茎块暂不变）。E10 大窝内胎生仍 `outcome=GROUND`、赤油摔伤 2 点（大窝茎生两只均 `CUSHION`，两种路径分别验收）；[a9b60f00](https://github.com/Longshou/MCyukkuri/commit/a9b60f00) `birth_stem_caught` 茎生仍难接住。完成：`birth_nest_safe`、`birth_nest_stem`、`birth_stem_caught`、`birth_land_caught`、`birth_land_missed` 各约 20 只，母油不跳地走进窝；大窝与帽下多数 CUSHION／HAT、约 5–15% GROUND 并有 `birth impact injury`（至少留 1 点生命）；帽下茎生实子落进帽里坐好；窝沿上的赤油由亲代放回垫面（`carry put down reason=nest_rim_to_cushion`）；读 `birth landed … outcome=` 与 `childhood.birth_landing`。
- **饥饿赤油请求驱动亲油找粮并喂食（反馈 6）**：已接入待试玩。E1 母油 `feed refused reason=no_food_source`（亲油营养 75、孩子 54）。完成：`feed` 近／远食物、无粮、低数值拒绝和窝内外交接对照；接受后真实找粮、消耗并喂入，失败有原因；双方有表情与回应，拒绝转身。
- **赤油取食限制与产后茎优先（反馈 7）**：已接入待试玩。赤油通常仅亲油喂糜，母油优先拾取可达真实空茎并咀嚼。完成：`feed` 与茎生观察唯一茎份额、嚼糜和真实交接。极饿越界搜索随陆 33 离巢限制修正，不在此算已修复。
- **树叶襁褓**：已接入待试玩（[规则](FEATURES.md#子代生命周期与离家)）。用户反馈叶片与头发辫子穿模（版本待核实）。完成：`leaf_swaddle` 叶丛少一块、赤油 `wrap=none` 变 `closed`、亚成始终 `none`；便意子油解开排出一份后 `needs.bladder` 约减 40、`needs.hygiene` 加 25 再包回；积便解开时两份便便落地、脏叶作废；`leaf_swaddle_neglect` `soil` 0→1、满 3 份溢地，卫生与 `mood.unease` 每 30 秒恶化；重载保留襁褓与积便；叶缘接后发下沿不露肤色，发辫饰物在叶外不穿模。演出缺口：采叶、整理、包裹／解开沿用既有姿态，无专用动作与叶片过渡，开合瞬间切换；解开后子油走开时原处的叶不显示；配音台词未做。
- **布襁褓八款**：已接入待试玩（[来源](FEATURES.md#宠物店徽章与宠物培育)）。用户反馈布吞掉头发辫子（版本待核实）。虎纹款带耳头罩未做（会罩住头发与帽饰，须按物种另做）。完成：街道“八款布襁褓穿上并排，可亲手包”（`swaddle_worn`）各款右键包上自家幼体，外观与物品图标同款（首字母款背后为本物种首字母，灵梦 R、魔理沙 M、爱丽丝 A），发辫尾巴在布外不穿模；解开取回同款（`[interaction] swaddle on/off` 带款式）；工作台“襁褓布＋染料”八配方对应八款；商店购买随机一款；重载、落水、着火、换主、死亡归还保留款式。
- **宠物窝去边与体表演示（E3 反馈 10、11）**：已接入待试玩。E5 三次认窝失败，选中框显示细碎碰撞格（[玩家操作](FEATURES.md#基础生活与饲养操作)）。完成：`marisa_explore` 赤油进出，`sleep` 与宠物认窝看成体认领、寻路、睡眠；`treatment_scars`、`body_mold`、治疗演示表面变化与实际治疗同步。

### 头饰、水生与远行

- **初航与登船载重准入**：已接入待试玩（本机自查非验收）。完成：能自己上帽檐的子油站帽檐，头顶只给还不能自己上帽檐的幼体；子油自行登船按载重准入，会压过容量的不上船（`board refused … reason=hull_full load=… capacity=…`）、留岸等亲油回岸；`first_voyage`、`first_voyage_brim`、`birth_boat_missed` 无超载翻船或溺亡；`hat_crowd` 最大的孩子坐头顶、`kid_boat_play` 头顶赤油探头。
- **摘帽后的持续情绪与表情**：已接入待试玩。E8 父油取帽后哭脸一闪恢复（日志确认 `WORN→LOST`，不能证明帧序列）。完成：`birth_land_caught` 摘帽中、结束及还帽后的脸与 `mood.unease`／托管状态不一闪恢复；有效托管放帽不被误判失饰。
- **陆 11 头饰认亲**（34）：已接入待试玩。E1 智力 40 亲油 `known=false`、亲近 30→27→24，驱赶不明显。完成：`hatless_kin` 智力 40／80 亲油对照，低智力自然鄙视并按概率升级为真实驱赶（`accessory.sanction`）；高智力仍认亲。
- **陆 20 怪物判定与欺负反应**（35）：已接入待试玩（[怪物与制裁](FEATURES.md#头饰)）。E1 低血量样例制裁开始即退出；普通灵梦受咬后只进捕食种反击拒绝入口。完成：`hatless_monster` 两种初态均出怪物读数，右边两处普通伤和低血量不算怪物；`hatless_scorn` 凶悍者自然发起欺负，受欺负及被反击的欺负者按自身状态反击或逃跑、低血量必逃，选择、伤害、逃跑位置可对应。
- **远行活动范围**：已接入待试玩（[规则](FEATURES.md#行为性格与成长)）。完成：`rain_shelter`、`pred_range`、`forage`、`dig`、`marisa_explore` 与找岸／帽船觅食核对目标距离、Goal、位置、物资消耗和返回；攻击、接触、感知距离不扩大；只改数字不算通过。
- **魔理沙探险队（子油远行闲逛）**：已接入待试玩（[规则](FEATURES.md#野外事件)）。完成：`marisa_explore` 对照合格子油、低胆量魔理沙、灵梦与有巢赤油，读 `behavior.roaming`、`[explore]` 抽签、范围、返程／中断；赤油与不合格者不触发。台词、表情与演出最后补，数值尚未通过。
- **水 16 同种饰品通用、堆叠与受损交互**（所有种族；水生种受伤与头饰损坏见水生模块“水生种受击无伤口”）：已接入待试玩（[规则](FEATURES.md#头饰)）。E1 碎片收齐后不能摘取、魔理沙不拾取兼容碎片且不能堆叠；E2 无碎裂记录的缺帽个体被仅限 `SHATTERED` 的入口排除。完成：`hat_swap` 缺帽成体及子油走向兼容碎片或接受手递，逐片增加并凑齐才长合；异种拒绝、帽船托管不接第二顶；`hat_shatter` 收齐后摘取／归还继续长合，实物与进度不重复不丢失。

### 水生魔理沙模块（重构）

**状态：复测失败。** 规则在[水生魔理沙](FEATURES.md#水生魔理沙)，契约与架构红线见 [AGENTS.md](../AGENTS.md)；本节按场景登记试玩现象与完成标准，场景都在“池塘与河湾”。

- **重构与帽船重做裁决**：
  - 用户 2026-10-28：按“底座加 DLC”整体重构，旧实现下线、在新底座上重建（WP1–WP8 已接入待试玩，运行 [8ceba8a8](https://github.com/Longshou/MCyukkuri/commit/8ceba8a8) 起）。身体与操帽规则见[水生魔理沙](FEATURES.md#水生魔理沙)。帽形：陆地魔理沙与水生魔理沙是两个物种，帽子不混用；帽子统一尺寸按 1.4 倍代定，试玩时可改（后续 2026-11-01 裁决只保留圆盆帽形，见该节）。
  - 用户 2026-11-01：“以演出为核心”“储粮就用贴图”“赤油能在亲油船之间转移的能力……不需要”。帽船重做：**部分接入**，工作包 1 下线舍弃行为 → 2 计数储粮与贴图 → 3 规则驱动船体 → 4 睡莲叶帽体与按体型排座、退役旧帽形 → 5 接受后的救援完整演出 → 6 岸上分娩、阵痛交接与水生少胎 → 7 子油船上玩耍 → 8 清理残留。夜泊附加流程暂缓，保持未做。
  - 辫子遗留：越檐托举时辫根头发略露帽檐，划桨末段发尾转折偏快。
  - 完成（用户全量复测）：陆地出生 `birth_land_caught`／`birth_land_missed`／`birth_stem_caught`／`birth_nest_safe`／`birth_nest_stem`；赤油 `infant_starvation`、`colony_migrate_babies`；帽子 `kid_boat_play`／`hat_push`／`shake_capsize`／`hat_crowd`／`hat_swap`；落水 `self_rescue`／`shore_capsized`／`shore_capsized_far`／`worn_hat_boat`；水上生活 `water_colony`／`sail`／`afloat_family`／`shore_family`；家庭 `rescue_child`／`birth_boat_missed`。水上部分的验收标准见下列条目与 P0。
- **11-06 试玩反馈**（[6ace787dc](https://github.com/Longshou/MCyukkuri/commit/6ace787dc)）：已接入待试玩（本机非验收）。用户原话：
  1. “临产划回岸边，放下孩子再生产中，靠近岸边想上岸时卡住，可能是被子油不动卡住？”
  2. “满载亲帽救起新生儿上岸演出中 首先帽前的子油和1.一样不动 还是被亲油叼上来的。应该自己也能上岸才对。其次动作虽然完成了，但是又是辫子往后弯曲，放在身后”
  3. “亲油用辫子救起落水子油和赤油中，首先是木棍又是直接消失，救完孩子才凭空出现。 其次是头上的赤油有时候会变成类似45度角又变回来”——木棍见“木棍浮水、取回与持物可见”；头顶见“帽内空间与头顶承托”。
  4. “头上的孩子的底部和亲油的头部距离过大，看起来像悬浮。如果亲油晃动严重，其实孩子直接被晃下来就行了。”——见“帽内空间与头顶承托”。
  5. 原则，已写入 AGENTS“生物而非脚本”。
  6. “你没有做好辫子状态管理，我不能接受的是物品等突然消失”——见“木棍浮水、取回与持物可见”。
  完成：“临产划回岸边，放下孩子再生产”帽檐子油自己上岸不卡；“满载亲帽救起新生儿送上岸”子油自己跳上岸，岸上放下辫中与头顶孩子时孩子落在辫侧前方、辫子向前探；用户试玩确认。
- **分娩时卡顿突刺**：已接入待试玩（本机非验收）。[6ace787dc](https://github.com/Longshou/MCyukkuri/commit/6ace787dc) 读档续产“独自在自己帽里生下两只赤油”：赤油落帽后服务端停顿 2.3 s（`Can't keep up! 2282ms`），用户见 CPU 连续突刺。仍有新生赤油坐进被占座位时 take_seat 反复开始又 `NO_ROOM` 结束（每场约 60 次，单次已便宜）。首次加载卡顿见复测失败节。完成：“独自在自己帽里生下两只赤油”“临产划回岸边，放下孩子再生产”与出生与成长“父油摘帽接住新生儿再戴回”分娩与护幼期间无明显卡顿、无周期突刺。
- **木棍浮水、取回与持物可见**（11-04 反馈 6；11-05 反馈 9；11-06 反馈 3、6）：复测失败，其后已改待试玩（[持物显示](FEATURES.md#基础生活与饲养操作)）。[81858504b](https://github.com/Longshou/MCyukkuri/commit/81858504b) 反馈 9：翻船后回到船上的油库里没有想去拿回桨，对水中木棍没有欲望；[6ace787dc](https://github.com/Longshou/MCyukkuri/commit/6ace787dc)：救援时木棍直接消失，救完孩子才凭空出现。用户：木棍要能浮在水面上；水生油库里没有桨或失去桨（同一状态）时，应有驱动去取看到的水中漂浮木棍；岸上取得木棍的表现应是辫子攥着、不是消失，类似直接持有物品；油库里持物应是通用的能力与表现。完成：落水木棍浮在水面；缺棍或失棍的水生个体（含翻船后爬回帽的）主动取回水中浮棍（“划去捞水面上的木棍”看 `fetch_stick` → `stick taken`）；“捡棍放帽出航，饿了返岸”岸上拾棍后辫子攥着棍可见，持有真实木棍时随辫梢划水，失去木棍后才不显示，空辫招帽不出现木棍；“亲油用辫子救起落水的子油和赤油”“满载亲帽救起新生儿送上岸”“捡棍放帽出航，饿了返岸”全程木棍可见，抓放孩子前后有交接动作、不凭空出现（`[headwear] kit place`、`[body] held moved`、状态 `body.braid`）；持物显示不限水生种。
- **辫子占用时的取戴帽动作**（[81858504b](https://github.com/Longshou/MCyukkuri/commit/81858504b) 反馈 2）：部分接入。辫子持木棍时发生戴帽、放帽，辫子没有动画、帽子直接穿模；“划去捞水面上的木棍”里拿下帽子的下一瞬间帽子就直接出现在水上。用户：这里有辫子占用，以后都要考虑好辫子当前状态；放帽收帽此前相对较好，改着改着又出问题，要看真实暴露的问题而非回溯。完成：`afloat_stick` 及其他取戴帽、放帽场景，辫子持物时先腾出或按占用状态做动画，帽子随辫梢移动、不穿模、不凭空出现在水上；“捡棍放帽出航，饿了返岸”“饿了带孩子划回岸边”取戴帽全程看得见木棍，不从脸前横过。
- **`sail`／`shore_family` 划桨演出**：已接入待试玩（[091899217](https://github.com/Longshou/MCyukkuri/commit/091899217)）。原现象（录像 22:52，版本待核实）：圆帽 `cone_basin` 辫子穿船体、上下两节铰链、无力感。完成：看清转向时不划水、出桨越过帽檐，核对真实航行；不以离线船体检查通过代替演出认可。
- **成油站在帽檐、放幼穿过自己的身体**（[5a5711245](https://github.com/Longshou/MCyukkuri/commit/5a5711245) 反馈 6、7，未点名场景）：复测失败（演出待试玩）。救援时亲油站在帽边缘，辫子拾起赤油后穿过自己的身体向后送进帽内；成油落水后上船站在帽檐、不进帽内。完成：`rescue_child`、`shake_capsize`、`self_rescue` 船长回到帽内座位，辫托绕身到实际支撑点、不穿身；冒烟非试玩验收。
- **`shore_capsized`“岸边用辫子拨回漂远的帽子”**（11-04 反馈 15；11-05 反馈 12）：复测失败，其后已改待试玩。[5a5711245](https://github.com/Longshou/MCyukkuri/commit/5a5711245) 用户：原“扶正帽子”与 `self_rescue`“落水后游回自己的帽子”一模一样；应改名，拨水让水流流动、帽子漂近；三格内拨水，三格外游过去；最好接上原版水的流动规则。[81858504b](https://github.com/Longshou/MCyukkuri/commit/81858504b) 反馈 12：“岸边用辫子拨回漂远的帽子”毫无反应。8 格外的远帽现为 `shore_capsized_far`“远处的帽子游过去扶正”。完成：`shore_capsized` 帽子被一下下拨近（`[action] start id=beckon … wet=false` → `end … at_bank` → `right` → `reclaim done how=worn`），全程不下水；`shore_capsized_far` 走进水里游去扶正再爬回；两场看得出区别（[规则](FEATURES.md#水生魔理沙)）。
- **`shake_capsize` 赤油被甩进水里后溺死**：已接入待试玩（本机冒烟非验收）。完成：“右击晃翻帽子，船主游回扶正”连续 5 次两只赤油均上岸或被救起，真实受阻仍保留原因；船主爬回后一并核对“成油站在帽檐”。
- **`worn_hat_boat`“戴着的帽子摘下当船自救”**：已接入待试玩。完成：子油真实落水后放帽自救，亲油含泪围观不救。
- **无乘员的水上帽子像原版船**（[5a5711245](https://github.com/Longshou/MCyukkuri/commit/5a5711245) 反馈 16，项目设计）：已接入待试玩（[fa8800c5c](https://github.com/Longshou/MCyukkuri/commit/fa8800c5c)，[规则](FEATURES.md#水生魔理沙)）。用户：玩家无法与水生种的帽子互动；水上帽子只要没有油库里在上面，右键晃动几下直接变成物品，类似原版船。实现为左键打三下（见规则）。完成：`shake_capsize` 有乘员侧仍只摇不散；`cargo`“帽里存粮取食，空帽打下”用 `scenario hit` 打三下即变物品（`[hat] hit … count=` → `pickup cause=hit_empty`），八份粮全掉出。
- **水生种受击无伤口、伤口与头饰损坏未接上陆生种通用规则**（[5a5711245](https://github.com/Longshou/MCyukkuri/commit/5a5711245) 反馈 12、17）：已接入待试玩（[5a774ca6c](https://github.com/Longshou/MCyukkuri/commit/5a774ca6c)、[fa8800c5c](https://github.com/Longshou/MCyukkuri/commit/fa8800c5c)）。用户：水生种在帽船上无敌吗？打击不奏效，尝试造成伤害也不显示伤口；伤口、头饰碎裂等规则都没有接上陆生种通用规则：陆上与陆生种一致，只是在水上可受伤、头饰不会损坏。完成：陆上水生种受击、伤口显示、头饰损坏与碎裂同陆生种；水上（含帽船上）受击会受伤并显示伤口、头饰不损坏；对照 `hat_damage`、`hat_shatter` 与陆生种伤口场景，读 `[body]` 伤口点数与 `[headwear]` 日志，魔理沙各形态无 `Wound attachment disabled`。配套美术见[美术](#美术opus)。
- **帽内空间与头顶承托**（11-04 反馈 4、13，项目设计；11-06 反馈 3 后半、4）：反馈 4 与 11-06 各项已接入待试玩，反馈 13 见 P0-3 `hat_crowd`。[5a5711245](https://github.com/Longshou/MCyukkuri/commit/5a5711245) 反馈 4：“临产划回岸边，放下孩子再生产”两幼一大一小，大的在帽檐、小的在头顶，子油在辫子端穿模。用户（2026-11-06）：“亲油落水头上的赤油应该也落水”（[规则](FEATURES.md#水生魔理沙)）。完成：“临产划回岸边，放下孩子再生产”大的幼体坐头顶、帽檐幼体不穿辫子；“满载亲帽救起新生儿送上岸”救起时三处确实无位；头顶可点、可转身、不能自由移动或做水上动作；“饿了带孩子划回岸边”“临产划回岸边，放下孩子再生产”带头顶孩子跳上岸、“赤油在帽檐玩耍，打雷躲避”头顶赤油随船轻摆，不悬浮、不转 45°；亲油因翻船、甩出或跳失落水，或帽船猛烈摇摆时，头顶赤油随之落水再走正常的自救／救援，小幅摇晃不松手。
- **水上个体的生物取舍**（11-06 审计 P1）：已接入待试玩（本机冒烟非验收，[规则](FEATURES.md#水生魔理沙)）。看：“落水掀翻帽子与帽子搁浅”亲油自己的帽在翻／晃或自己在水里时先顾自己，站稳后才决定救不救（`rescue decision deferred … reason=unsteady_footing`）；“右击晃翻帽子，船主游回扶正”猛晃时头顶赤油也被甩下（`play fall_in … from=head`）；“孩子长大超载翻船，亲油取舍”北侧普通亲油回岸卸下（`load heavy … reason=unload_ashore`，再出航载不下的 `left_ashore … hull_full`）；“子油跳上岸，自己放帽划一圈”亲油累到体力约 15 回岸小睡、睡足再出航；“帽船上朝帽外排便”帽里赤油跳不出去不反复重试；“捡棍放帽出航，饿了返岸”“饿了带孩子划回岸边”只在岸上有她能吃的食物时返岸（`land_food=true`）；“临产划回岸边，放下孩子再生产”新生儿在岸上时父油回岸陪着。未出现：岸上直接用辫子捞落水孩子（`rescue_child` 赤油沉到池底，离岸站位低约 1 格、辫长约 0.6 格，`rescue bank reach none`）。未做：伴侣岸产时父油随她上岸接生（须先修岸边接生会合）。完成：上述场景读数依次出现，零非溺水死亡，用户试玩认可。
- **落水危险下的生物反应**（11-06 审计 P4）：已接入待试玩。被断绝的子油不再守候在逐出者的帽檐而是游向岸边（`hat_crowd`）；亲人（含兄弟姐妹）在视野内落水时幼体不练习爬跳（`rescue_child`）；累（能量低于 30）或伤（生命不超过一半）的游泳者不游向自己的帽子而先去最近的岸（`self_rescue`、`shore_capsized_far` 初态满状态，仅在耗力后看 `[water]` 路径选择）。
- **水生种排便动作**（[81858504b](https://github.com/Longshou/MCyukkuri/commit/81858504b) 反馈 7）：已接入待试玩（[324ea4ec3](https://github.com/Longshou/MCyukkuri/commit/324ea4ec3)，[规则](FEATURES.md#水生魔理沙)）。日志 `boat_toilet dropped … release_distance=… push=… land_distance=…`，落水后一 tick `boat_toilet stool … in_water=true`。已知缺口：落水粪便按原版物品在水中下沉、滑行；赤油跳上帽檐落地后原地转身约 16 tick；赤油跳出途中亲油正好开始新一趟划行时，粪便仍可能落在移动的帽旁。完成：“帽船上朝帽外排便”亲油与帽檐赤油都明显撅起屁股，粪便从屁股出来越过帽沿落进帽外水中，不穿帽、不跳位。

**行为场景观察点（P0，场景 → 应观察）**

- **P0-1 自然生成即在船上**：已接入待试玩。`water_colony`：近水成员出生即在船长座，离水成员留岸并记 `spawn refused reason=no_water`。
- **P0-2 单体完整航程**：部分接入。完成：`sail` 找棍、放帽、可见跳入船尾座、划行、饿后返岸收帽；出航前备粮与捞物仍未做。
- **P0-3 亲子出航与容量（WP4）**：复测失败。完成：`afloat_family` 亲油按[登船规则](FEATURES.md#aquatic-boarding)经过帽檐接幼、就位后托放，全家真实上船、欲望重排；`hat_crowd` 混合体型入座、无位留岸带原因、不互挤，按[载重与断亲规则](FEATURES.md#水生魔理沙)观察超载取舍，保存重载坐标不变；`first_voyage` 子油两跳离亲帽上岸，再放自帽、划出时绕开亲船与岸边、绕行后回岸收帽戴好（`first_voyage success`），之后在自己的帽船上生活。睡着时等待／取消仍未做，不以初态已坐帽作为搬运通过。
  - `afloat_family`“亲油用辫子抱赤油上帽船”与 `afloat_family_gather`“内陆赤油爬不来，亲油抱去上船”：复测失败，其后已改待试玩。[81858504b](https://github.com/Longshou/MCyukkuri/commit/81858504b) 反馈 11：亲油下去在帽中旋转再上岸就没了，无意义；站帽檐、放幼穿身见“成油站在帽檐”。完成：全家真实登船，无启动超时，船长就位后绕身托放、不穿身，逐帧及时接触、温和托举与放下；孩子不在旁边时不滞留、不循环，孩子离开后能继续接取；接幼动作有实际接上船的结果，不以帽中旋转后上岸收场；无受困／溺水／饥饿死亡。
  - `afloat_family_ashore`“回岸寻回远处赤油再出航”（11-04 反馈 3）：已接入待试玩。[5a5711245](https://github.com/Longshou/MCyukkuri/commit/5a5711245) 寻回后顶着孩子前行、无问候、闲逛折返。孩子独处时可能先自学会跳、自己跳上帽船，这条真实路径也算（重逢、`set_out`、放帽、`set_out complete aboard=1/1` 仍须有）。完成：“回岸寻回远处赤油再出航”“内陆赤油爬不来，亲油抱去上船”停步问候后真实接幼、带到泊位并装载出航，无反复寻回或把爬不来的孩子丢在岸上；真实接近失败或承托不足仍有后果（[规则](FEATURES.md#aquatic-boarding)）。
  - 子油离亲帽两跳上岸（11-04 反馈 10）：复测失败（演出待试玩）。[5a5711245](https://github.com/Longshou/MCyukkuri/commit/5a5711245) `first_voyage`“子油跳上岸，自己放帽划一圈”子油从亲油帽上岸连跳四次，很快；用户要求一步跳上帽檐（通用上帽檐动作）、一步跳上岸，朝向与运动方向一致，节奏放慢。完成：按[上下船规则](FEATURES.md#aquatic-boarding)两跳上岸，停稳期间保有真实净空。
  - `first_voyage_brim`“子油在帽檐脱帽放水划一圈”（11-04 反馈 10）：已接入待试玩。亲船静止在一跳够不到岸的静水里，子油在亲帽帽檐面向水面把自己的帽放到船旁，再一步跳进去，绕行、返岸、收帽。完成：日志依次 `first_voyage route=brim`、`place_prop`、`first_voyage hat set on water`、`hop`（`mount … cause=hop_contact`）、`first_voyage success … route=brim`，两帽并排不重叠、跳向与朝向一致，无落水死亡。
  - `hat_crowd`“孩子长大超载翻船，亲油取舍”（11-04 反馈 13）：复测失败，其后已改待试玩（项目设计）。[5a5711245](https://github.com/Longshou/MCyukkuri/commit/5a5711245) 反馈 13：用户判断满座与超重翻船的逻辑不对、无法闭环。本机仍偶见 `cruise` EXHAUSTED（NO_SUPPORT）未定位。完成：挤落与真实倾覆分别可观察，离水后意图 `DONE`，真实打滑仍失败；按[载重与断亲规则](FEATURES.md#水生魔理沙)核对真实成长、帽内／头顶／帽檐载重及概率读数，实际超载翻覆后普通亲油单向断亲且不再救该幼体、例外亲油不断亲；存亡由真实水伤与救助决定，保存重载保持乘员身份与坐标。
- **P0-4 带幼体靠岸下船**（`shore_family`“饿了带孩子划回岸边”，11-04 反馈 5）：已接入待试玩。[5a5711245](https://github.com/Longshou/MCyukkuri/commit/5a5711245) 收帽中转身、帽穿地。开放：亲油上岸后走向岸边站位时仍会穿过刚放下孩子的那一点（重叠约 0.2 格），须给孩子留位、真的绕过去。完成：孩子真实离帽站到干地（跳下或由亲油放下，头顶幼体落地后辫子放下）、船长最后收帽，收帽与理帽期间不转身、不穿地。
- **P0-5 帽底计数储粮与玩家交互（WP2）**：已接入待试玩。完成：`cargo` 对照 stores／tier／eats／refusals、手中实际扣数、原地取食和重载余量，满帽拒收不晃，翻覆按种类守恒；`birth_boat_missed` 补粮后原地取粮嚼糜、靠近孩子喂食；`hat_swap`、`shore_family` 保留摘取／拒绝边界。
- **P0-6 落水救助一次决策**（`rescue_child`“亲油用辫子救起落水的子油和赤油”、`rescue_no_seat`“满载亲帽救起新生儿送上岸”）：已接入待试玩（本机冒烟非验收，[规则](FEATURES.md#水生魔理沙)）。
  - 试玩记录：[5a5711245](https://github.com/Longshou/MCyukkuri/commit/5a5711245) 反馈 7：`rescue_no_seat` 落水点离岸太近，一只赤油自己爬上岸，亲油载着帽内赤油划走不管（场景须先改到赤油无法自行爬上岸）；反馈 6：赤油自行上岸后亲油不靠岸、不进入寻子逻辑；[81858504b](https://github.com/Longshou/MCyukkuri/commit/81858504b) 反馈 10：满船托幼送岸后船上还留两只；11-06 反馈 1–3 见上。
  - 未做：帽子漂远、亲油在岸上够不着自己的帽而孩子在水里时，尚无下水游回帽子的手段。
  - 完成：`rescue_child` 子油与赤油两次落水各自同一孩子同一事件实际 `carry grip` → `seat landed` → `rescue end result=DONE outcome=rescued aboard=true`，提过帽檐后随船晃、不抽动；`rescue_no_seat` 实际抓取、跳岸、放下并存活，救起后帽上自家幼油全部到岸上、不留船，亲油收回空帽；自行上岸只记 `self_climbed`，亲油随后靠近岸边接上家庭搜寻（`family_search`），不划走不管；帽子漂远时亲油下水游回帽子再救；救援放幼先在舱内选位，无舱位时（“赤油在帽檐玩耍，打雷躲避”）辫子托上空头顶，帽檐幼体进舱见复测失败节 M4；救助或出航结束时（含 `afloat_family`）仍持抱的孩子由共享放下真正落到承托面或干地，之后才继续航行；每事件每救助者一条决策，出生问候后可接续，真实阻挡／干预保留原因。
- **P0-7 自救与扶正交接（WP3）**：已接入待试玩（本机冒烟非验收）。[5a5711245](https://github.com/Longshou/MCyukkuri/commit/5a5711245) 反馈 15：`self_rescue` 与原“扶正帽子”表现一模一样、开头都有类似抽搐的动作；反馈 6 成油爬回后站在帽檐（见“成油站在帽檐”）。完成：`self_rescue`（远帽长泳）、`shore_capsized_far` 游到帽檐、单次扶正后爬回帽内船长位（`climb DONE`），落水状态结束；真实中断保留姿态。
- **P0-8 规则翻船与扶正（WP3）**：已接入待试玩。[4064a9234](https://github.com/Longshou/MCyukkuri/commit/4064a9234) 反馈 12：缓流不漂、似乎不响应水力；反馈 6：碰撞后动作一卡一卡（是否另含陆上身体碰撞待用户复看）。完成：`hat_current` 顺流至少 3 格、乘员随帽，靠岸停稳不抖，帽檐推移不把乘员压进岸壁（`inWall`）、乘员安全离帽；`hat_push`“木船轻碰只推开，猛撞才翻”轻推后连续滑动、猛撞翻覆；`shake_capsize`、`hat_grounded_capsized`（含静水湖的落水冲击）保留摇晃／水力／碰撞触发，`hat_crowd` 增加载重警戒与 `too_heavy` 超载触发，均先摇后翻、乘员落水及物品守恒。`status trace` 的 `[hat] travel` 读逐 tick 位置、位移、速度和乘员数。
- **P0-9 水生岸产与新生护理**（`birth_boat_missed`“临产划回岸边，放下孩子再生产”）：复测失败（[分娩规则](FEATURES.md#消耗品与胎生)）。
  - 试玩记录：[bbd16394b](https://github.com/Longshou/MCyukkuri/commit/bbd16394b) 反馈 10、18：水生亲本生出方帽陆生子、突然多出幼体；[5a5711245](https://github.com/Longshou/MCyukkuri/commit/5a5711245) 反馈 9：怀孕亲油上岸后帽内两只赤油一动不动且彼此穿模，用户没看懂这场景要看什么；反馈 8 用户新增设定：临产前才上岸、一两只可直接生进自己帽内（见下条）；[81858504b](https://github.com/Longshou/MCyukkuri/commit/81858504b) 反馈 5：怀孕母油想上岸时都会在岸边呆滞一会；反馈 6：孩子和自己上岸都是瞬移，缺少基础动作。
  - 未解决：真临产满体型母油（宽 1.169）帽檐无出口（`exit_stops=0`）时未离帽；头顶幼体随亲体落岸后仍被持抱、未经普通放下（`NO_ROOM no_supported_release_spot`，或 `come_ashore` 在放下前被打断）；陆生接生帽卸幼途中被打断、新生儿入水降至生命 7；圆帽接生的地形穿透未覆盖；首餐首便偶发不完成见复测失败节同名条目。
  - 完成：带幼（含空船）先有水上生活、临产前才上岸，到岸后不呆滞；母子离帽上岸都走起跳、落地等基础动作，无瞬移，按[上下船规则](FEATURES.md#aquatic-boarding)真实离帽分娩，不扩大跳距、不穿岸，不能把距离够近写成靠岸成功；原有幼体先落岸，头顶幼体随亲体安全落岸后经普通放幼动作放下，留在帽内的赤油按各自状态有可见行为、身体互不穿模；初始乘员加新生儿总数无误，所有新生儿水生圆帽、出生净空、不溺亡，护理后不自行入水；产后水生亲油喂出首餐（不走回岸上苹果那头）、首便完成，再普通出航装载；冒烟不算试玩验收。
  - `birth_hat_own`“独自在自己帽里生下两只赤油”（11-04 反馈 8）：已接入待试玩。用户（2026-11-05）：“水上分娩部分生到水里能接受 类似陆地上的没接住子油。水上分娩时父油如果存在，则会在一旁守候。如果赤油落水会进入正常救援流程”。完成：幼体从出口落入帽内、触布后附着，不悬空、不与母体重叠，问候与首餐首便不靠亲油转身挤人且真实完成；落点偏了则真实落水并经正常救援救起（不丢失未出生部分）；父油在产程内已划到旁侧守候（`guard start/arrive/end`，两帽不相叠、不挡出口）；不适配胎次仍走岸产。
- **P0-10 赤油亲帽互动（WP7）**（`kid_boat_play`“赤油在帽檐玩耍，打雷躲避”，11-04 反馈 4、13）：已接入待试玩。规则见[赤油船上互动](FEATURES.md#水生魔理沙)。11-04 反馈 4：赤油在身前、空间狭窄转不了身，“赤油让位及随帽转向”不作通过依据。完成：用户亲眼看到帽檐拍水／学划水（辫子朝外）、头顶只做干演出、受惊回舱内、由亲油辫子托上空头顶，或两处均无位时在帽檐缩身；右击赤油得到问候而非晃船（见“帽子与帽上乘员交互”）。
- **P0-11 帽船即家的基础演出（WP3）**：已接入待试玩。`kid_boat_play` 静水里帽子不翻、轻摆跟船，`hat_swap` 戴帽／放帽同大，`first_voyage` 子油自帽沿用戴着时尺寸，`[hat] placed` 的 `scale/hullBox` 与体型一致，座位、储粮圈、浮态和帽檐点随同一尺寸；`shore_capsized_far` 倒扣稳定；排座复看 P0-3，陆地帽落水下沉且陆地接生不受影响。
- **P0-12 不卡死**：任一水上场景跑满 10 分钟，`/yukkuri aqdiag on` 下无持续同一阶段超时的个体；超时有 `ended reason=timeout/stalled` 与真实结果；辫子不会一直伸着。
- 另：帽子可被推开、扶正，个体不与帽体重叠；帽子大小戴着与展开一致；带木棍个体会划船；任何动作结束后主人找回或明确放弃自己的帽子（`reclaim done` 或带原因的 `expired`）；陆上摘帽放帽不闪退。

**旧实现反例**：重建前的帽船生成与收帽、吃水／重心翻覆与重量扶正、旧换船与托幼下岸、送幼与夜泊（`family_skiffs`、`share_young`、`water_night`）、船内生产与旧出口弹道、水上捕食落地场景均已下线，失败记录见 Git 历史；旧圆帽 A/B 对照（[5d930b5c4](https://github.com/Longshou/MCyukkuri/commit/5d930b5c4) 开场全翻）后只保留圆盆帽，椭圆帽船被用户否决（[2ac6d5f58](https://github.com/Longshou/MCyukkuri/commit/2ac6d5f58)，CI 41289272356：船形夸张、戴帽与放船差异过大、仍显示粮堆）。

**未做（按新架构）**：旧代码中的初航、随亲出航、雨天等按新架构迁移，不计为完成。P1（15 项：初航与小船随亲、水上离家、赤油帽檐守护、威胁、雨天、帽内事故、晒干、非亲救助、推遗体与哀悼、孤儿船、孕期腾舱、无棍手划、船体损伤与修帽、桨滑落、并舷社交）与 P2（11 项：啃睡莲、夜间筏群、领地碰船、并舷递粮、结冰搁浅、桨拍反击、高湿软化、撕裂帽漏水、老年航程、宠物水生、尺度统一）原水 21–32 并入其中；各项逐项立场景（岸边即用的真实起点）与阶段读数后才开工，完成标准为用户试玩认可，动作遵守辫子／嘴／身体的能力边界，失败有真实后果，不永久等待；船长遗体留在船长位直到靠岸或被推入水（A10）尚未实现。另未做：出航前备粮；玩家交付木棍与从树叶／枯灌木取棍（亲油取棍递给孩子）；夜泊的芦苇／睡莲与摇篮曲、天亮后推离岸边；船长湿度与避雨细则；帽内亲昵；母船无位时新生儿落到父船。

### 其他已接入玩法

- **陆地食物与喂养（审计 P4，[规则](FEATURES.md#野外住所)）**：已接入待试玩（本机冒烟非验收）。看点：`settle_pit_slave` 长跑饮水后不在池岸反复 `ground path stopped`、无成员饿死，奴隶到休息时间在坑里睡（`begin source=life`）；`forage` 由近及远选草场（`end ok=true reason=full`，无 `no_meadow`），回程失败不丢草，有饥饿幼体时先入粮堆再铺床；`birth_land_caught` 两亲代喂不同孩子（无 `receiver_already_waiting`），饥饿新生儿求食（`[childhood] hungry baby begs`）；`chen_speed` 先到者先吃；`settle_snow` 营养≥30 的成年不为冷处的草离开暖处。未做：幼体饥饿时保持跟随亲代（紧急需求下仍丢弃跟随，`offering` 中幼体仍饿死）；亲代喂幼的就近草被吃光后仍只搜 8 格内（`food_search_exhausted`）；`aftermath_growth` 三个窝相距 2 格互相挡路、母亲吃不到脚边苹果（场景问题）。
- **油库里互挡绕行与互相推挤**：已接入待试玩（[规则](FEATURES.md#行为性格与成长)）。[ac4ba87b](https://github.com/Longshou/MCyukkuri/commit/ac4ba87b) 一只挡住另一只路线时反复碰撞、既不绕行也不重算，亲子被夹在窝里两亲之间出不来，对爬行赤油推力无效。完成：`den_family`、`birth_nest_safe`（赤油能从两亲之间出窝，亲代睡着也被缓慢挤开）、聚落各场景与 `colony_migrate`（窄处互让、不长期对撞）读 `[move] blocked by yukkuri` 的 action（sidestep／replanned／blocker_yields）与位置变化；护子、抱持、乘船与出生弹道不受影响。窝内卡住的其余原因仍需现场读数。
- **行为数值、成长与遗传**：已接入待试玩（[正式设计](FEATURES.md#行为性格与成长)）。完成：日常草地 `balance_traits`“五属性20／50／80／90对照”概率与任务预算符合公式；`growth` 按实际 tick 换阶段；`balance_forms` 形态不越界；`balance_deibu` 在成年边界锁定灵胖且不中断短动作；亲子分享、照护和翻船救援按友善作一次决策，普通失败冷却后才重选。
- **数值改造适配**：已接入待试玩。完成：`shop_build` 受训库存有原定徽章与六项技能，购买放出及重载保持；`birth_land_caught` 新生儿读取亲子关系，收纳放出及重载继续累计；`meet` 南边陌生一对从亲近 0 开始普通问候并建立同伴关系，高恐惧仍按 `balance_relation` 退避。
- **通用**：陆 1（23）场景北侧观察、个体初始朝北；陆 3（11）清空本场地后实体、账本和地形回到同一初始状态，无模组物品与拆窝掉落；[架构整理](../AGENTS.md)后的行为、显示顺序、保存契约与多人同步；同屏 1–15 只（污渍、孕肚、发言）的帧时间；单一血量下多日的死亡与繁殖节奏。
- **野外住所**：`den_family` 家庭洞（杀死父油即丧偶家庭）：陆 52 清醒饥饿翻倍，丧偶后母油接替外出存粮，未独立子油留洞内、不再进出循环。`indep_near` 独立（亲油在与不在各一只）：告别后释放旧床位，成为寻窝流浪油，亲缘与好感保留，尚不具备繁殖资格。
- **流浪与觅食**：`dig` 流浪油挖穴（陆 29（47）内部 2 格高）；`forage` 外出觅食（陆 30（48）近处不足才远行：距离见[觅食](FEATURES.md#野外住所)）与灵梦颊囊（多捡、逐份放入）；`graze` 吃草（陆 31（49）优先非草、咽草嫌苦）。
  - 复测失败（本机冒烟，主线 [aa574abd4](https://github.com/Longshou/MCyukkuri/commit/aa574abd4) 同样）：`forage` 母油割满 6 份草后 `end ok=false phase=BACK reason=stuck_home`，草没有进洞。完成：父母带草进洞存入，日志有 `deposit home=`。
- **群系聚落**
  - `settle_snow`、`settle_desert`：体感、保温窝和避暑效果符合[季节与温度](FEATURES.md#季节体感温度与储粮)，笔记与命令季节一致，跳时间不换季。
  - **族长空缺后的交接**：已接入待试玩（[族规与继任](FEATURES.md#聚落族规与迁徙)）。完成：`colony_hunt`、`colony_ration`、`colony_migrate` 手动杀死原族长后，公仓实物仍可取、救急盘点不重复份额、召集中断不算空猎、已出发猎手返程交粮、迁徙继续、候选会合在吃饭／照护中暂停后累计完成；无合格成体时保持空缺、真实缺粮仍有后果。水上接力须在实际水上聚落迁徙中杀死引导船主，确认接力者驾驶自有帽船、未搬走死者船货且抵达后才换公仓；陆地场景不作这项通过证据。
  - `colony_judge` 族规·审判：陆 32（45）逐案走近、相向听审再裁决。
  - **野外嗯嗯坑与山脚穴居聚落**：已接入待试玩（[聚落场地](FEATURES.md#聚落族规与迁徙)）。收尾项：补土约每两格降一格、门前只一格砾石、倒木两三格长、坑约 4×3／6×3。完成：`settle_forest`（村）、`settle_taiga`、`settle_cherry`、`settle_hills`、`settle_savanna` 家洞挖进北侧坡、洞口朝南，补土处顺坡无露土竖墙，坑是低一格凹地，只有可饮水群系有塘；`settle_plains` 洞岸补起；`settle_pit` 去不了时 `toilet pit refused reason=…` 并改用离家 4 格外地面；新开世界聚落只在适居群系坡脚；用户认可外观与如厕行为。
  - **嗯嗯落块、坑边排泄与嗯嗯奴隶坑中生活**：复测失败（[bbd16394b](https://github.com/Longshou/MCyukkuri/commit/bbd16394b)），其后已改待试玩（[落块](FEATURES.md#日常生态)、[奴隶](FEATURES.md#聚落族规与迁徙)）。用户要求（[ac4ba87b](https://github.com/Longshou/MCyukkuri/commit/ac4ba87b) 后）：排泄物落成有容量分档的方块，奴隶留坑吃排泄物并受羞辱，成员只站坑沿排泄。bbd16394b 反馈 4：奴隶在坑里长期脏、被嫌恶且头饰不归还，约四个游戏日甜化、疯狂后死亡，身份无法长期存在，产生只靠指令、每群可多只；同场两场长跑聚落成员饿死（3–5 天窗口内 3–5 只，含困在土丘顶的成员，另 1 只溺水）。边界：不提供玩家强迫食糞；玩家放下的废物仍落“地面零碎”、奴隶不吃；`alley-sniff` 仍只找掉落的废物；住所内部排泄仍是掉落物。完成：`settle_pit` 便意升到 90 后各成员从洞内／坡上走到坑沿背对坑口排泄（`toilet pit trip start` → `toilet pit edge reached` → `relieved source=pit`），无 `no_path` 或地面排泄，坑格出“嗯嗯”方块、份数档位增加，满 8 份溢到相邻格（`poop placed … overflow`）；`settle_pit_slave`（72000 tick，奴隶是有幼体的亲代）营养≤50 时到份数最多的格吃一份（`pit slave eats droppings`），坑空则 `pit slave hungry reason=no_droppings`，他人排泄后 `pit mockery`，长期取食且无奴隶饿死或甜化；`settle_pit_famine`（6000）空坑真实饿伤；`settle_pit_drive`（20000）失饰拒斥后入坑；三场景均无聚落成员因饥饿或困在丘顶、池岸而死亡，长跑无成员溺亡（落水后 `climb DONE on_bank` 之后有 `escape done support=stand=` 或 `aboard=`，被推或摔进水仍要挣扎），用户试玩确认长期生存与产生闭环。
- **日常草地**：`meet` 已建立关系的两只个体普通问候与蹭蹭。
- **出生与成长**
  - `birth_land_caught`、`birth_land_missed` 陆地接生：陆 25（41）复测失败，按“出生与成长”反馈 3 复测，不能以真实入帽算整链通过；临产出窝与大窝生产见反馈 4。
  - `first_toilet` 首餐后首便：陆 26（42）首餐吞下后才首便。亲代照护决定已接入待试玩：`feed`、`first_toilet` 与帽中照护核对 `home.family_state` 的 `care`；同一孩子同一需求中断重试、重载后沿用决定，喂食与首便独立。`hurt` 南边亲子受伤舔舐：完整舔舐让最老一处轻伤降一级并回 2 点生命，深伤只安抚（`life.lickHeals`）。
  - `growth` 一路长大：陆 13（50）335999 tick 亚成在下一模拟 tick 成年，不因体型受阻延后年龄资格；陆 51 移速新生约成体 45%、赤体末 55%、子体渐到 100%。
  - `family_distance`、`family_search`：陆 23（36）按反馈 1；`feed` 按反馈 2、5、6；赤油食物限制与产后茎优先见反馈 7，离巢限制见陆 33。`aftermath_growth` 事后茎生：自然生产结束后空茎消失，落成一次真实茎物品；检查连续生产、食用、配方和保存，母油优先嚼茎喂赤油随反馈 7 核对。
- **天气与捕食种**：`rain_shelter`、`rain_open`（陆 14（4））按上方完成标准复测。`pred_watch`、`pred_range`（陆 15（17、20、38））发现与巡飞更远，续追不中断，看见即退避，接触后取食；慢速赤／子油优先与陆 16 一并试玩。审计 P3 已接入待试玩（本机冒烟非验收）：被咬过的个体记住攻击者并持续逃到掩体或干地（`[threat] flight cover=…|flight away=…`），`pred_range` 被咬后一路跑远；`sacrifice` 子油躲到亲油身后（`fleeing to parent`）；`myon_brave` 芙兰低血后飞去 16 格外落地（`withdrawing by air`）；`pred_pets` 饿了仍 `task=PATROL`；`alice_hunt` 追到接触，追击不被她自己的差事打断（`pursuit ended by gate=`），旁观成体受惊。未解决：饿着的捕食种仍会追上再咬第二口（间隔约 300 tick）；妖梦追上退战的芙兰仍可能补死。`treatment_scars` 南排脏污与伤痕：陆 21（9）与陆 49 留疤合并复测；普通伤概率原位留疤、大伤必留，橙汁治伤也须结算，面粉糊去疤。
- **伤病与遗体**
  - `airborne` 腾空与小跳：陆 38（14）明显腾空才感叹。`house_health` 家油血量：陆 45（24）生命上限 1／2／4。
  - `wound_leak`、`core_exposed`、`core_filling`、`carrion`、`cannibal_hunt`：血量与遗体资源按[规则](FEATURES.md#伤口遗体与身体状态)；治疗止漏另看陆 49；`body_mold` 核对四档油霉及治疗后原位回退，`core_exposed` 核对大创腔、普通馅／唯一中央中枢、低血小裂口及完整皮面对照。
  - `treatment_severe`、`treatment_scars`、`treatment_care`（陆 49）按[治疗流程](FEATURES.md#伤口遗体与身体状态)核对伤口、烤足、生命、疤痕、感染与医疗余量；`treatment_scars` 南排核对自然闭合留疤后去疤。
  - `remains_rot` 遗体腐败与死臭（[规则](FEATURES.md#伤口遗体与身体状态)）：手持或冷冻不老化、重载保持阶段。
  - `offering` 请吃了我（含临终献体分支）：陆 24（37）按性格、好感、自身与孩子饥饿触发（拒绝后 1200 tick 冷却），投喂可中断，GIVE 后留受赠遗体。
- **头饰与认知**：`hatless_scorn` 失饰者被鄙视（陆 18（8））观察与问候共用冷却，接生放帽者不算失饰（随帽船托管）；同场凶悍者（陆 20）自然触发欺负，双方按实际状态反击／逃跑，低血量必逃且停止追推。
- **家油人类屋**：`house_empty`、`house_pair` 家油入住：陆 46（27）夜出昼归、饥饿下降；陆 2 羊圈只有四壁。`house_eggs` 整批迁卵：陆 47（29）已接入待试玩（[家油生态](FEATURES.md#家油生态)）。完成：逐枚搬运后返回旧窝，旧窝余卵和在途卵清零后才交接亲代住所；读两窝 `EggSlots`、`EggRelocation` 与亲代 `relocation` 核对数量、身份、孵化进度；重载、目标变脏或被毁后，返卵或落地物品与账本一致。
- **街道、商店、工厂**：`badge`（陆 8（13））令牌绑定真实个体；`date`（陆 9（16））店方相亲；`pred_pets`、`transport`（陆 2 四壁内可活动；陆 15 受训蕾米莉亚饿了也忍住）；`harness_walk`、`feed_wild` 牵宠居民（陆 37（12））套胸带回应限时、归还移动控制；`feed_wild`（陆 40（18））规则见[人类世界](FEATURES.md#人类世界)；`oniisan_prey`、`attack_witness`（陆 10（22））目击者退避、亲友收到警报；`exam` 饿着考的处分；`shop_build`（陆 34）滞销减粮已关；`factory_manju` 多胎恶化曲线。
- **物种与稀有形态**：`marisa_sumo`（陆 4（1））相扑出现；`myon_stick` 妖梦与楼观剑（[妖梦](FEATURES.md#妖梦与稀有变体)口癖与觅草，战力见陆 17）；`rare_forms` 一览与 `/yukkuri action`：逐一查看全部 19 种形态受伤有伤口、佩章有圆牌，日志无 `Wound/Badge attachment disabled`；另核对木棍嘴部朝向与被炉动作穿插；跨季节行为结合陆 5，红眼兆头迁址需在真实聚落旁观察，一览场景本身不证明迁址。素材匹配不代替视觉确认。
- **群体事件**：已接入待试玩。补入试玩场的重点：多人晚加入、双玩家并发、长期自然推进，`street-performance` 的村落地点、`alley-corner` 的实际退让、`lookout` 的朝向。完成：真实参与者按条件进入，移动与结算符合[事件规则](FEATURES.md#野外事件)，中断和重载不重复扣物或结算。
- **无专属场景**：[胎数随体型变化](FEATURES.md#行为性格与成长)、[认屋与群体事件](FEATURES.md#野外事件)、[螺壳雨天缩壳](FEATURES.md#种族与亚种)、[工厂到店物流](FEATURES.md#宠物店徽章与宠物培育)、[声音注册](FEATURES.md#对白与配音)；命名牌取名、子代本名不重名、宝物被取走或被抢。

### 美术与视觉确认

均为已接入待试玩，外观由用户确认。

- **通用照料道具外观**：放置后的食盆、软垫窝、编织大窝与厕所见[美术](#美术opus)。油粮袋、清洗布、毛线球与运输篮仍为原低多边形造型，只按明确替换项继续。完成：携带与地面道具远近观感。
- **宠物与野生大窝**：验收容量、入口与碰撞（外观见美术）：占真实 2×2 格（点击格为西北角，四格须空，拆任一格整窝拆下、只掉一件），可并排两只至多约 1.1 倍成体与四只赤油；伴侣认窝见“普通窝容量”。完成：`family_nests`、`birth_nest_safe` 双亲与赤油站位、入口进出；创造模式墙边试放，空间不足提示且不放置。
- **火烧后的烧焦外观**：E7 `core_exposed` 低血烧伤全身斑驳、侧边部件错位、泪痕浮出；用户接受降级，全身烧伤改按伤级整体着色，家油只用三级热损外观。完成：`core_exposed` 低血烧伤三级着色均匀，泪痕与侧发贴合，`[render]` 读数不明显升高；`thermal_body`、`wound_sources`、`magma_sole` 真实火烧验证，足烧只足底、全烧保持高度／深度与头饰尺寸，不误出露核洞。
- **身体印记与重伤创腔**：发面／皮面裁切、硬边断发边、直壁平底创腔、普通馅和唯一中央中枢；露核命中放宽为开口外扩 1.5 倍（[规则](FEATURES.md#伤口遗体与身体状态)）。完成：`body_mold`、`treatment_scars`、`treatment_severe` 原位变化；`core_exposed` 各类伤瞄准开口及外缘即可命中；`core_filling` 取核后空腔，`rare_forms` 品种内馅配色及捕食种核心接缝无原发色弧边；治疗、遗体搬放及清场重开不换位、不重复中枢。
- **遗体美术**：按[死因类别与水中阶段](FEATURES.md#伤口遗体与身体状态)换死亡脸、姿态、表层与地面馅渍；寿终缎带下垂需骨骼姿态，目前缺；制作源 `standards/yukkuri-library/drafts/remains/` 未经用户认可，献身切面归分体项。完成：`remains_forms` 用“读遗体”核对七种死因与溺死的 `category`／`form`；水池左具约 30–60 秒后 `water=softened`，右具约 30–60 秒后 `water=dissolving`、`food` 6→0、`core=false` 且沉底，岸上对照不变。
- **小墓碑**：已接入待试玩（分支 wp-bc-grave-marker，[65ddd902b](https://github.com/Longshou/MCyukkuri/commit/65ddd902b)）。铲子安葬后冢头立小石碑、正面朝向安葬者，赤体等小个子碑缩小；右键读名字／无名、种类与年龄段、死因、安葬日。试玩：“伤病与遗体”场地按钮“八种死因遗体，溶化与立碑”，开场观察点前方约 5 格偏右已埋好一只饿死赤油，另给铁锹可安葬其余遗体。完成：用户看到墓碑并认可外观，右键读到正确信息，退出重进后仍是同一死者。
- **多只伤员同屏卡顿**：[58d99ee0](https://github.com/Longshou/MCyukkuri/commit/58d99ee0)（CI 40182081691）`core_exposed` 多只伤员同屏，攻击瞬间仍卡；用户认为一律平贴覆盖原有样式（[18fd3981](https://github.com/Longshou/MCyukkuri/commit/18fd3981)，已撤回）。完成：`core_exposed`、`rare_forms` 全部伤员同屏绕行和连续攻击无明显卡顿，不靠一律平贴。
- **脸部表情贴片分块与遮眼**：[998f52c2](https://github.com/Longshou/MCyukkuri/commit/998f52c2)（CI 40186711876）头部皮肤竖向明暗带、眼睛边缘略被遮（截图未入库）。完成：`core_exposed` 及任一普通场景近看未受伤与受伤个体走动、转身，脸上无分块明暗，眼睛不被遮挡。
- **遗体表面颗粒闪烁**：[ac4ba87b](https://github.com/Longshou/MCyukkuri/commit/ac4ba87b) 走动或转视角时自然死等遗体皮肤满面颗粒闪烁，暴力死、溶解不明显（截图未入库）。完成：自然死、饿死、烧死、病死遗体旁走动、转视角，皮肤颜色均匀无颗粒，呈各死法设计偏色。
- **伤口贴图、创腔与发面适配**：[373ed249](https://github.com/Longshou/MCyukkuri/commit/373ed249)（CI 40187473739）低血露核灵梦右颊近边角褐色环带、粉色内壁并透见草地；[5c99a34b](https://github.com/Longshou/MCyukkuri/commit/5c99a34b)（CI 40194538150）伤口边缘斜向明暗条带；E7 头顶凹伤交界细缝待复测（[发面素材](../standards/yukkuri-library/drafts/body-marks/hair-wounds/README.md)）。完成：`core_exposed` 斜看侧发后发及近边角伤口，开口外头发完整、不见皮下或透出地面；`wound_materials` 各级伤皮面／发面连续；摘饰、治疗、重载保留原伤与疤痕锚点。
- **等视觉确认的素材**：爱丽丝八景蓝瞳与蓝眼表情（`body-standard/features/alice-hakkei/`）；雷普魔 1.12 脸、行为序列与 peni 显示（`drafts/rapem-alice/` 未认可）；成体状态脸（八景 U 重绘，`art/source/adult-states/`）；稀有形态贴图（妖梦与红眼、橙尾、被炉魔理沙，登记缺认可字段）；中枢馅、去霉药、酸奶、兴奋药、幼语药、弹珠汽水喷雾。

### 演出与表现复测

- **落水与挣扎时的侧发**：已接入待试玩。[a9b60f00](https://github.com/Longshou/MCyukkuri/commit/a9b60f00) 用户反馈魔理沙 `water_struggle` 的侧发随 `hand` 骨整片扇动像多了一只手，捕食种侧发同样装作翅膀（[动作规则](../standards/yukkuri-library/motions/variants/lore/README.md)）。完成：落水挣扎时（`shake_capsize` 被甩进水的孩子、`rescue_child` 落水的孩子）近看魔理沙两侧侧发都不扇水、辫子仍有小幅扑水；其他种落水时侧发静止；捕食种落水场景已删，遇到时同样核对。
- **走散寻回与放弃演出**：已接入待试玩。分离／搜寻用不安表情和呼唤，真实重逢回应与亲昵粒子，搜索超时放弃和单向断亲有表情、字幕与服务端日志。完成：与 `family_search` 两对的阶段和关系读数一致，不以演出代替寻回。
- **亲油接受／拒绝赤油喂食请求**：已接入待试玩。接受／拒绝均按已执行决策回应，拒绝转身与孩子哭泣。完成：`feed` 双方回应和实际决定一致；没有物资不演成喂食成功。
- **陆 16 亲油挡捕食种**：部分接入。噗咕挑衅动作、转怒与亲子分支字幕已接，行为仍待复测；配音留最后统一补。

### 次要项与长期观察

- **陆 28 痛苦记忆随排便淡化**（21）：已接入待试玩。真实完成排便对已记住对象的恐惧减 2，不改亲近或当前伤口。完成：日常草地 `balance_relation`“亲近玩家但恐惧0／40／80”看排便前后 `relations.objects`。
- **默认速度多代平衡**：沿用主要试玩之后再观察的安排，见[行为与成长](FEATURES.md#行为性格与成长)；不据此扩展数值改造。
