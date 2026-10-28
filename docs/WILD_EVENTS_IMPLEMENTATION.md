# 野外事件与 41 张情景卡

野外事件系统把已有的日常生活、真实家谱、群落、食物、身份和帽船模块编排成连续情景：七项连续事件与 41 张情景卡，事件只绑定已加载的真实实体，不另造演员。本页区分原模块行为、事件层编排和各卡的结算边界，不按相同卡名重复计算功能。

代码：`event/`（`WildEventData`、`WildEvents`、`WildEventGoal`、`WildCards`、`RiverEncounter`、`Expedition`、`HomeClaim`、`HomeClaimData`、`HomeClaimGoal`、`HomeClaimCommands`）

## 来源与适配

依据：八景 U ModLab 参考工程的 41 张情景卡（`WildEvents.cs` 中的 `Card` 与 `Build` 条件）、该外部工程 `wiki/events/native-special-events.md` 中的原生事件核读记录。本仓库的来源整理见[八景与作品事件](../wiki/hakkei-reference/events/special-events.md)，社会关系与资源规则见[性格与社会关系](../wiki/hakkei-reference/world/personality-social.md)、[资源与生活生态](../wiki/hakkei-reference/world/resources-ecology.md)。本文七项按 MC 连续事件分组，不与来源页的故事题材逐项计数。参考工程只读，原生对白和图像不复制到运行资源。

事件只绑定已经加载的真实实体 UUID。姐妹必须有同一个非空父或母；单亲必须有真实亲子关系，另一亲代未知或明确死亡，而且当前没有存活配偶。收纳、卸载、跨维度和 `REMOVED` 均不冒充死亡。母父、配偶、出生和未知父方仍由 `FamilyData` / `FamilyLife` 维护。

MC 的距离、持续 tick、区域半径和个性阈值属于 MC 适配，不能称为八景原参数。`cognition/Cognition` 只读人格五轴中的智力，不另存认知分；探险队使用 `event/Expedition` 的队长、成员名单与实际路线，不以好奇值或普通同群关系替代。原生的季节传言、全家一键收养、跨世界寻主及完整跨群系探险均未加入。

## 七项连续事件

| 事件 | 真实入口与条件 | 状态与结果 |
| --- | --- | --- |
| N01 森林偶像与观众 | 森林群系或已登记森林群落；白天、足够精力、真实同伴；观察原 `DailyLife.songListener` 会话 | 原歌唱会话持续，绑定观众定期回应；实际结束才完成。会话令牌不存档，重载后中止旧歌唱事件 |
| N02 相扑 | 复用 `GroupInteractionGoal.challenge/canUse/tick`；真实成人、双向好感、可站立场地 | 原有准备、推挤、胜负、精力消耗与可配置伤害；不建立第二擂台 |
| N03 姐妹乞食 | 附近村落或任一姐妹的实际住所；两只未成年同胞、饥饿、附近真实玩家，无已有饲养者 | 接近→等待→吞咽后回应；每个成员各自实际吃到才有全体获食结果 |
| N04 单亲家庭求食 | 真实亲代与幼体；未知/死亡另一亲代、无存活配偶；幼体饥饿，附近玩家 | 不生成假孩子；每个绑定成员吞咽后登记，离开/拒绝没有正向结算 |
| N05 水边同伴 | 放下的帽子见 `headwear/PlacedHatEntity`；水上同伴事件随旧水生实现下架，WP4–6 重建 | — |
| N06 家庭求饲养与分离 | 成人发起、真实亲属、全员不饥饿且无商店/工厂所有者；玩家可达且态度允许 | 请求不直接变成收养；单体收养沿用 `HumanCommerce.Owner`；捕获成员引起亲属分离 |
| N07 明确弃养 | 本人拥有且 `Use=companion` 的真实个体；明确解除收养操作 | 先成功建立记录再移除所有者，保留身份/记忆/徽章；按原有信任表达失落或离去 |

## 41 卡逐项对照

下表区分原模块承担的行为与事件层提供的情景状态；试玩进度统一见[待办](TODO.md#待试玩清单)。

| 分组/卡 ID | 卡名 | 实现位置与真实触发 | 模块分工与结算边界 |
| --- | --- | --- | --- |
| A / forage | 闻嗅觅食 | `settlement/ForageGoal` 消耗真实短草；`YukkuriLife.seekFood` 取地面物或食盆 | 已有有限实物取食，不复制切场景库存 |
| A / share | 把一口分给同伴 | `YukkuriLife.tryShare` 转交口中那一份食物 | 已有单份转移、亲子或关照条件 |
| A / snatch | 抢走发现的食物 | `YukkuriLife.seekFood` 附近竞争者和实际物品扣减 | 成功取得并扣除一份后调用 `WildCards.competing`，保留真实对手 UUID |
| A / weak-escort | 停下来陪弱者 | `event/WildCards`：同伴低精力、帮助者有余力、亲近/关照条件 | 接近并等待，不无条件补精力 |
| A / little-gap | 大个子让出空隙 | `WildCards`：真实宽度至少 1.5 倍，距离近且有可达退让点 | 大个体移出空隙；完成要求实际拉开距离 |
| A / rivalry | 相看两厌 | `WildCards`：双向实际敌意与可识别伙伴 | 面对面反应与有限期的本次冲突证据；不凭空造成伤害 |
| A / crowd | 三只挤在一起 | `WildCards`：三只真实个体在小范围内 | 三方绑定和可达退让，不能用两只替代三只 |
| A / territory | 这块地方归谁 | `WildCards`：大胆、低关照、非亲属/非亲近对方 | 争执表现；不篡改住所/群落所有权 |
| A / plea | 饿着肚子的请求 | `WildCards`：一方饥饿、旁边同伴较饱 | 只做请求，实际食物仍由原取食/分食模块扣除 |
| A / lookout | 同伴轮流张望 | `WildCards`：对在场玩家已有恐惧，熟悉伙伴在场 | 绑定真实玩家 UUID，按轮次转向在场威胁；不造未知威胁 |
| B / parent-wait | 亲代回头等孩子 | `FamilyLife.WAIT_CHILD/FOLLOW` 依据真实母父和距离 | 已有自然等待/追随；不重复建立亲缘状态 |
| B / sibling-game | 同胞模仿游戏 | `WildCards`：真实同胞幼体、足够精力和净空 | 各两次真实跳跃，必须观察到向上运动才记完成 |
| B / mate-touch | 伴侣相互确认 | `FamilyLife.partner` + `WildCards`，双方配偶记录、非敌意且可识别 | 伴侣定向接近；日常蹭蹭仍由 `DailyLife` 执行 |
| B / teach | 跟着做一次 | `WildCards` + `Cognition.canTeach/commitTeaching`：双方可学习、教师智力更高、四格内双向视线、非紧急且有动作净空 | 教师实际跳起，学生随后实际模仿；完成时按学习概率抽取并核对真实课堂 UUID 和成员。此卡没有具名技能，不增加通用认知分；发起意愿由教师友善决定。共同规则见[认知与教学](BEHAVIOR.md#认知技能与教学) |
| B / imitate | 异种互学口癖 | `WildCards` + `YukkuriEntity.imitateSpeech`，好奇、无敌意、不同对白种族 | 对方实际说出目录中的原句后才复述；保留教师 UUID、对白 key、种族、时间与使用次数，不改自身种族 |
| B / ornament | 陪着寻找饰物 | `WildCards` + `AccessoryItem.matches`，失饰者与原主令牌匹配的实物 | 关照者陪同，原主接触真实物品后归还并扣一件 |
| B / deficient-wait | 等慢慢跟上的它 | `SubspeciesLife.congenital` + `WildCards`，明确不足形态、距离与关照条件 | 等待/靠近使用实体当前速度；不把普通低精力冒充不足形态 |
| B / young-call | 孩子叫住亲代 | `FamilyData.Person` + `WildCards`：真实亲子、幼体落后 | 幼体主动呼唤情景；普通追随仍复用家庭 Goal |
| B / reconcile | 争执后的试探 | `WildCards.competing` 的近期冲突与有向好感 | 实际应答结束后才按对方好感接受或保留距离；拒绝不抹掉旧怨；结束后沿用事件的 1200 tick 成员冷却 |
| B / group-turn | 同群成员商量先后 | `SettlementLife.groupId` + `WildCards` + 同一个真实食物实体 | 后一成员等待前一成员实际吞咽；只限制已绑定食物，不生成份数。原作从属阶级分支未移植 |
| C / fed-after | 玩家投食后的回望 | `YukkuriLife` 普通食物吞咽完成→`WildEvents.fed`→`WildCards.fed` | 实际后续，只在附近玩家和同伴存在时触发，不二次增加营养/信任 |
| C / voice-peace | 玩家劝说后的缓和 | `WildCards.talked`，仅成功温柔说话且有当前冲突证据 | 等实际说话响应结束再完成，不能由随机卡触发 |
| C / scold-after | 被喝斥后停止争抢 | 同上，成功冷言分支 | 保留原玩家独立记忆；不造食物或额外正向结果 |
| D / tank-corner | 小屋门口让位 | `WildCards` 检查同一真实住所的两名住户（各自床位可以不同）与门口距离 | 可达退让；位置必须对应当前实际住所 |
| D / tank-doll | 一起试探人偶 | `Facilities.ITEMS` 中真实灵梦/魔理沙玩偶，双方好奇差 | 轮流接触同一实物，不能产生第二个人偶 |
| D / grave-quiet | 墓地里放轻动作 | 附近实际 `GraveBlockEntity`→`WildCards` | 安静观察；不读取来访者为死者亲属 |
| D / street-performance | 街边短小表演 | 实际村落附近原 `DailyLife` 歌唱与绑定听众 | 观察已存在的歌曲会话；不另开随机歌曲入口，不重复结算 |
| D / street-scraps | 街边争一口碎食 | 村落/真实住所附近，绑定可吃的真实地面食物 | 依照现有饮食规则；普通角色不强吃仅房子油可吃的 `FOOD_SCRAP`。实际扣物与吞咽后结束 |
| D / alley-sniff | 巷口嫌弃与饥饿 | 实际住所/村落、相邻至少两面实墙、可达的真实便便或馅料物品 | 饥饿或卫生条件只触发观察，不把气味当一份食物 |
| D / alley-corner | 墙角能挤几只 | 三方实际拥挤 + 相邻至少两面实墙 + 居住地点 | 独立地点限制后才让位；不是复用全局拥挤条件换卡名 |
| D / forest-find | 林中找食物与误判 | 森林、饥饿、真实可达食物 + `Cognition.forestFindSucceeds` | 识别概率 `clamp(.35 + .006 × 智力, .35, .95)`；误判不扣物，识别后仍必须原取食/吞咽成功 |
| D / forest-family | 林间清点家人 | 森林标签/真实森林群落，至少三只实际亲属→`WildCards` | 逐个绑定与同场状态；不是三只陌生邻居 |
| D / park-family | 公园里商量跟随 | 干燥露天日间、真实家庭、附近玩家→`WildCards`/N06 | 公共草地是 MC 适配；请求与真正收养分别结算 |
| D / park-race | 公园动作比试 | 干燥露天净空、双方足够精力→`WildCards` | 两次实际跳跃与分先后观察，无奖品生成 |
| D / heat-rest | 热得停下来的同伴 | 高温群系、日间露天、低精力同伴→`WildCards` | 陪伴停步，不能冒称完整体温模拟 |
| E / concert-response | 观众接上一声 | N01 中实际 `DailyLife.singTo` 成功绑定的观众 | 听众回应；不中断底层歌曲，也不额外结算歌曲奖励 |
| E / expedition-count | 探险队互相报到 | `Expedition.form/discover`：森林、魔理沙成人队长、1–3 名真实成员、充足精力、可达植物目的地 | 明确建立队长和名单，出发点名→同行→到达点名→返程；不脚本制造敌人或跨群系冒险 |
| E / river-call | 岸边追赶者与漂流者 | N05 真船主、船、岸上关联成员；河流群系选择本卡 | 真实可达岸点移动，不生成重复漂流者 |
| E / rain-close | 雨里挨近一点 | 同时受雨、真实亲近同伴→`WildCards` | 靠近；雨淋与避险继续由生活逻辑处理 |
| E / lake-steady | 湖上别乱动 | 帽船真实乘员、水面与状态；非河流水域使用本卡 | 不停止船/危险，不以说话判定救援 |
| E / sisters-wait | 姐妹互相让一口 | N03 的同胞和等待投食阶段 | 单体吞咽后另一个仍等待；全体实际进食后才全体获食 |


## 状态、互斥与持久化

`event/WildEventData` 在世界 `SavedData` 中保存事件 UUID、参与者 UUID、维度/起点、阶段、加载 tick、实际已获食成员、结果、参与者冷却和区域冷却。新活动对完整名单一次性预约，认屋与野外事件共享入场互斥；任一成员已占用就整单拒绝，开唱、含食暂停和教学准备均在预约成功后执行。同维度起点的三维距离严格小于 32 格时不另开野外事件，远处玩家区域独立；相邻网格边界不绕过距离检查。

两份 `SavedData` 仍分别拥有认屋与野外事件事实。首次预约查询先读齐两份数据，再为该服务器建立派生索引；旧档重叠记录全部保留，所有持有者结束前禁止给该成员另开活动。结束只释放当前记录的预约。用途查询从全部持有者中筛选：行动控制排除 CONCERT，认屋先筛当前维度，住所封锁查实际存活的独占卡；不会让排在前面的其他旧记录遮住有效活动。

存档不保存一份可生成的家庭或食物快照。身份暂时不可用时暂停，不用空查找结果当死亡，也不调用生成器补演员。缺原维度、未知或未加载成员、成员所在位置不进行实体更新、全员无可观察者，以及河流事件缺绑定船，均记录失联时间。河流救援后的 RESPONDING 不再依赖船；认屋寻路预算等待属于正常可用状态。危险、固定、命令动作和紧迫生理仍按原规则优先处理。

失联监督使用主世界 `gameTime`，连续 24000 tick 或同一活动累计 72000 tick 后退休该记录；正常推进不累计失联时间，也没有统一总寿命限制。返回时先检查过去的失联是否到期，再清连续段；时间回退只重设连续起点，不扣除累计。`LastSupervised`、`BlockedSince`、`BlockedTicks`、`LastOriginTime` 随记录保存，缺完整字段的旧档从首次观察获得完整宽限，服务器停机不计墙钟。

超时结果为 `participant_unavailable_timeout` 或 `bound_target_unavailable_timeout`，野外事件记 INTERRUPTED、认屋记 ENDED。收尾释放本记录预约、原含食暂停及认屋成员完成进度，保留冷却和有界历史。成员找家游标与预算票据由住所组件及实体离场流程清理；原维度缺失时，冷却使用最后已知的原维度时间。

含食暂停通过弱实体引用和原 mouth 实例识别，换口与读档时清除；释放操作只作用于原来暂停的那一口。

认屋新区域冷却使用维度与 `floorDiv(x,64)`、`floorDiv(z,64)` 的结构化键。旧 `Regions` 的不可逆 long 键继续按旧公式查询至原截止，无法反推维度，因此不猜测或重写；新记录写 `RegionCooldowns`。野外事件维持原三维距离规则，永久发现记录不在此次迁移范围内。

N01 的实际歌唱仍由 `DailyLife` 共享会话掌管；`songSessionToken` 只作内存身份比较，重载后不能拿下一首歌结算旧事件。其他事件的占用由 `WildEvents.reserved` 提供，日常歌唱/蹭蹭/相扑使用同一互斥入口。徽章考核、教学、固定、指令动作和危险不会被事件抢占。孕体与尚未落地的新生儿不参与；普通家庭目标与事件互斥，紧迫需求仍保留优先。普通进食与避险可打断移动，不为演出冻结资源或健康结算。

取食卡的 `mayTakeFood` 仅限制已绑定的实物；成功 `beginMeal` 且扣除一份后才记取得，实际吞咽回调后才记获食。`interrupt` 释放已取得但未吞咽的场景，因此后来另一顿饭不会补成上一事件。探险队名单、队长顺序、目的地和阶段保存于同一世界事件记录；家谱与群落身份仍由原模块维护。

## 已知边界

- 事件使用当前实体造型和现有动作／表情，不带独立模型、美术源或运行导出。试玩缺口统一见[待办](TODO.md#待试玩清单)。

## 认屋（家园宣言）

依据 L16、L21（一家人认领人类农田，被围起来吃光后瓦解）与 E10 的传统套路。认屋是持续到定居或瓦解的家庭事件，与短场景卡分别记录。

- 触发：`WildEvents.discover` 在 `RiverEncounter` 之后调用 `HomeClaim.discover`。野生（无主人）成年个体、`WildEvents.available`，8 格内至少 1 个互相认得的亲属或伴侣（家庭至多 6 只），24 格内有玩家，10 格内（不在村庄）有人类农田（农田上的成熟或未成熟作物）或房屋（床、门、工作台、箱子、熔炉，旁边有顶且可站立处）。每次检查按配置 1/N 概率；每个个体 6000 tick、每个 64×64 区域按配置冷却；被驱赶的区域冷却为三倍。
- 流程：全家走到地点（GATHER）；第一只成年个体到达后 `angry`/`mine`，其余 `happy`，并向 24 格内玩家发无配音文字（`home_claim.mcyukkuri.declare`）；之后占住（HOLD）。成员离地点太远时 `HomeClaimGoal`（优先级 3）让其走回；饿了会吃认领范围内的真实作物（方块消失，对应物品经 `beginMeal` 入口，只吃 `acceptsFood` 为真者），短草仍由 `ForageGoal` 处理。占住期间 `HabitatGoal`、`SettlementGoal` 让位（`HomeClaim.holds`），`WildEvents.available` 对认屋成员返回 false。
- 安置：HOLD 满配置时长后，逐个调用成员的 `habitat().findHome()`，按 UUID 记录已结束的搜索。`DEFERRED` 保留当前成员的进度，下次继续；全部成员搜索结束后记录 `settled`。各成员的预算与游标由住所组件管理。
- 围起来：占住期间每 200 tick 从地点出发在可站立格（高度差 ≤1，农田可行走）上做 BFS，上限 512 格、半径 15；穷尽则进入 ENCLOSED 并记录包围盒（作物只在盒内取）。圈内没有作物和短草、且有成员 nutrition < 30 时进入 COLLAPSING：每 400 tick 争吵一次（`angry`/`mine`，互相降好感 6 [original]，幼体 `teary`/`hungry`），5 次后结局 `family_collapsed`，解除对全家的约束。玩家喂食照常；围栏被打开则回到 HOLD。饥饿致死沿用 `YukkuriLife` 已有的饿伤。
- 结局（`Claim.result`）：`driven_off`（攻击成员，经 `YukkuriEntity.hurt` 通知；或对成员用“拒绝事件”操作，经 `WildEvents.decline`；全家对该玩家信任降、恐惧升，`afraid`，沿远离玩家方向逃出 16 格以上）；`adopted`（`WildEvents.adopt` 成功后，其余仍满足收养条件的成员一并收养，门槛仍是信任 ≥10、恐惧 <25）；`settled`（完成上述安置流程）；`family_collapsed`；`never_arrived`（1200 tick 仍未到达）；`perished`。成员被收纳、未加载或跨维度时整体暂停，不算死亡。已结束记录保留最近 64 条。
- 配置（`home_claims` 段）：`enabled`、`checkOneIn`（默认 20）、`regionCooldownTicks`（24000）、`settleAfterTicks`（72000，约 3 个游戏日）、`enclosureCollapse`。
- 管理员命令：`/yukkuri homeclaim start` 以命令位置为中心，让最近的符合条件的野生家庭立即认屋（跳过概率和冷却，其余条件照常，附近需有人类房屋或农田）；`/yukkuri homeclaim status` 列出进行中的认屋。观察笔记在认屋期间多一页（`HomeClaim.page`）。
边界：瓦解不会自动解除伴侣关系；亲代拒绝分食目前只降低好感；认屋成员退出聚落、强盗团解散等未接入项统一见[待办](TODO.md)。

## 补充群体事件

魔理沙探险队的子油远行闲逛（项目设计）：`RoamingGoal` 复用真实地面寻路。子油魔理沙（不含赤油和亚成）完成早期行动能力、胆量≥65、智力≥40、营养≥50、精力≥60且不下雨时，每 1200 tick 最多进行一次 5% 抽签。成功后在普通闲逛半径 `20×胆量探索系数` 的两倍范围内选择实际可达点，目标须超出普通范围；找不到路径不算启动。抵达后返程，营养低于 35 或精力低于 40 提前返程；紧急需求、危险或其他实际 Goal 可中断，最多 1600 tick，不发放额外资源或成长。运行中的探险使用对应的亲子会合范围，结束后恢复普通亲子规则，真实危险仍交接。`behavior.roaming` 和 `[explore]` 记录已执行抽签、起点、目标、半径、返程与中断；读数不抽签或发起行为。此短途事件的瞬态计划不写存档，重载后由普通 AI 重新规划；现有成人组队事件的名单与存档规则保留。演出随真实过程在最后统一补，机制待试玩，见[玩法待办](TODO.md#玩法待办)。

四张卡写成 `WildEventData` 场景（`Kind.CARD` / `Kind.EXPEDITION`），代码在 `event/GroupCards.java`（前三张）与 `event/Expedition.java`。配置在 `group_events` 段。

- 共同筑巢后独占（`nest-monopoly`）：条件为同一住所、满足 `Bonds.careKin` 的成年亲属（同母者优先），发起者友善 ≤35，都在住所 6 格内且 `usable`；每次检查（约 5 秒）以 `1/nestMonopolyOneIn`（默认 8）触发。过程：独占者守入口（`angry`/`mine`），被挡在外的亲属站在入口外（`teary`/`wait_turn`），其 `getPersistentData()` 写 `ShelterBarredUntil`，`ShelterBlockEntity.usable` 经 `GroupCards.barred` 在场景存活且未到期时返回 false（场景因任何原因结束都自动解除）；其余亲属每 100 tick 对独占者降好感 2 并 `shy` 背身。600–900 tick [original] 后，若家人对独占者的好感合计为负：独占者 `teary`/`gentle` 认错，好感各回升 4，被挡者 `share`，结果 `apologized_and_shared`；否则 `kept_the_nest`；住所失效为 `nest_lost`（INTERRUPTED）。
- 流浪强盗团（`bandit-raid`）：`SettlementData.Kind.BANDITS` 群体由 `SettlementSpawner.Preset.BANDITS` 生成：3–4 只成年普通种，无住所（`HabitatLife.choose/findHome` 对该群体直接返回 false），不主动登记家庭；自然发现抽签接在森林、家庭区间之后（`banditChance`，默认 5%）；命令 `/yukkuri settlement spawn bandits`。卡：强盗团成员发现 16 格内有住户且存粮的住所（可达），以 `1/banditRaidOneIn`（默认 3）触发，2–3 只出动，首只走到入口，经 `ShelterBlockEntity.raid()` 真实取走 1 份食物，能吃则 `beginMeal`，否则丢在脚边；间隔 60 tick，最多 3 份。10 格内住户 `angry`/`mine`（护家者）或 `teary`/`afraid`，对强盗好感 -3。结果 `raided_store` / `store_empty`（空仓），超时 `raid_timed_out`。强盗不伤害个体。
- 多层巢塌方（`burrow-collapse`）：天然洞穴住户 >= 3，湿度 >= 90 连续超过 2400 tick（`ShelterBlockEntity.soaked`，不存档），之后每 100 tick 以 `1/burrowCollapseOneIn`（默认 200）触发，且 `EventHooks.canEntityGrief` 允许。住户（已加载者）受 1–2 点伤害（血量 > 3 才扣）、`cry`/`afraid` 并走向入口；产生泥土粒子与碎砾声；记一条立即完成的 CARD 记录（至多 6 名住户），结果 `collapsed_in_long_rain`；随后 `dismantle()` 拆除（物品掉落，不放置方块），住户经 `YukkuriLife.maintainHome` 释放旧住所并另找新家。
- 跨群系探险队（`expedition-biome`）：在 `Expedition.discover` 现有队长/队员条件通过后，以 `1/biomeExpeditionOneIn`（默认 3）改找 48–84 格外、已加载、可站立、群系与出发地不同的目的地；移动用分段导航（同 `SettlementLife.navigateToward`），队伍间距放宽到 14 格并不再要求视线，与出发地允许相距 144 格，超时 4600 tick。到达后点名、队长 `surprise`/`food_found`；返回后队长 `hello`，向 24 格内玩家发无配音文字 `event.mcyukkuri.biome_expedition`（群系名），结果 `crossed_biome_and_returned`；超时为 `biome_expedition_timed_out`。
强盗无法吞咽时会把夺取的食物落在入口；强盗不登记家庭，亦无专门的离开／解散行为。卡片触发依各自概率与场景条件。

## 可选的误判危险事件

TM05（L13）的误认危险情节尚未适配；若采用，只能用项目已有实体表现，不把拟议的捕食行为写成原作事实。边界见[待办](TODO.md)。
