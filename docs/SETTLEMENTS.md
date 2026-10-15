# 野外家庭与森林聚落

本功能在既有实体、生活、亲缘与社交系统上增加群体登记、自然发现和居住范围行为。出处及采用边界见[漫画与小说核读](../wiki/yukkuri-communities.md)。野外住所与生活环境已补充，见[天然住所与纸箱](WILD_SHELTERS.md)；角色标准模型保持原有确认状态。

## 可遇见的群体

| 类型 | 初始组成 | 行为 |
| --- | --- | --- |
| 流浪油／独居个体 | 1 只普通种 | 沿用个体性格、社交、认屋与避险，可采食真实野草 |
| 小型家庭 | 1～2 位亲代、0～5 只幼体，共 2～7 只 | 登记实际配偶及单亲／双亲，幼体沿用跟随、亲代等待及分食；远离住所后尝试返回 |
| 无族长森林聚落 | 6～12 户，每户独立抽取人数，共 12～84 只 | 共享聚落身份与居住中心，各自保留亲缘和住处 |
| 有族长森林聚落 | 6～12 户，加 1 位帕秋莉族长，共 13～85 只 | 增加傍晚向可见且能认出的族长集合；族长已知死亡后保留无族长聚落 |
| 流浪强盗团（罕见） | 3～4 只成年普通种，无住所，不登记家庭；自然抽签区间接在家庭之后（`banditChance`，默认 5%）；只是个别群体，并非流浪油都是强盗，行为见 `WILD_EVENTS_IMPLEMENTATION.md` “补充群体事件” | `Kind.BANDITS`，会劫掠附近住所的存粮，不伤个体 |

自然生成种子保留自身种族；补充的普通成员从灵梦、魔理沙、橙、爱丽丝中选取。帕秋莉作为自然种子进入无族长群体时，可以只是普通成员。全体成员使用当前已接入实体资源；本功能没有更新任何造型确认状态。

## 自然发现与住所

正常自然生成和区块生成的实体，在首次服务端生活 tick 尝试发现群体；刷怪蛋、指令召唤、运输放出及旧实体读档不凭空补出同行成员。出生的后代继承可用亲代的群体登记。

每个维度以 64×64 水平区域记录一次自然群体抽签。成功或失败均保存，不因移走成员、死亡或重新加载而反复补员。新生群体寻找附近已加载、可达、干燥的现成洞穴或土坑，按户登记实际睡位并铺设住所核心；不挖洞、不堆土、不造屋顶，不强制加载远方区块。地形或人数预算不足时，种子继续独居。

默认森林名单为森林、繁花森林、桦木森林，可通过 `data/mcyukkuri/tags/worldgen/biome/forest_settlements.json` 扩展。全体自然种子的既有适生群系名单仍由 `yukkuri_habitat.json` 决定；只加入森林标签不会自动增加某群系的自然生成表。

默认抽签区间：森林聚落 25%，小型家庭 30%，其余独居；森林聚落内族长概率 50%。非森林地区的森林区间也落为独居，因此非森林为家庭 30%、独居 70%。这些是**区域首次抽签概率**，不是实际遭遇率；完整住所场地、地形与人数检查会进一步减少成群结果。自然生成表每次生成 1 只种子，同行成员由群体生成器补齐。

初始家庭人数独立抽取：25% 为单亲带 1～5 只幼体；其余为双亲，其中 20% 暂无幼体，另外 80% 带 1～5 只幼体。成年伴侣本身也构成一户。人数随后可因繁殖、成长、死亡而变化。这是可调的游戏遭遇参数，不是漫画或小说的统一人口定额。

森林聚落独立使用 `communitySpawnCap`（默认 96）与 `communityPopulationCap`（默认 128）的较小值作为初始预算，不受普通生成的 8 只上限限制。小家庭与流浪油继续使用 `localSpawnCap` 和家庭 `localPopulationCap` 的较小值。统计包括附近登记但未加载的个体及未出生预留；预算或地形不足时整组失败，不截断家庭或漏掉族长。

生成器在中心半径 24 格内预留每户现成洞穴的可用睡位，以短路径连接入口；每位住户分配真实睡位，同户不重叠。最多尝试 128 条户间路径，避免无限搜索。需要足够且彼此可达的自然凹处；森林标签并不保证适合生成。聚落亲代在中心 32 格内繁殖，使用聚落中心人口统计及 128 只上限预留幼体；离开本地或跨维度后使用普通家庭预算。

## 生活与存档

- 小家庭距居住中心超过 7 格、森林成员超过 14 格时，尝试沿可达路径回到自身住处；超过 48 格不执行远程拉回。运输、维度变化不会触发传送。
- 11000–12500 日内 tick 为集合时段。有族长成员会向看见且认得的族长靠近。求食、睡眠、避险、动作指令与亲代照护先于集合；失饰阻碍当面识别，但不取消族长登记。
- 短草采食消耗一株真实方块，将一个短草物品转入口中，再沿用进食与分食流程，不瞬间增加营养，不自动再生食物。遵守 `mobGriefing`。不会因此收割玩家的小麦或其他作物。
- 住所判断使用上方实际阻挡高度，避免拆屋顶后因天光传播滞后误判仍有住所。失去屋顶后沿用既有重新认屋逻辑；群落只有在“传闻与迁徙”一节的条件下才会整体迁址，平时不会。
- `YukkuriSettlement` 保存个体群体 UUID、原维度和生成待办；每维度的 `mcyukkuri_settlements` 保存群体类型、中心、族长及已抽签区域。区块卸载或运输收纳不视为死亡；族长死亡／实际移除依据既有家系记录处理。

观察笔记新增群体页及使用指南，可查看类型、群体短 ID、中心和族长。群体信息由服务端生成，附近陌生个体不会因同种或相邻而自动合并。

## 配置与手动检查

服务器 `mcyukkuri-server.toml` 的 `[discovery]` 保留 `naturalSpawns`、`localSpawnCap`，新增：

| 配置 | 默认值 | 含义 |
| --- | --- | --- |
| `communities` | `true` | 允许自然群体发现；关闭不删除已有群体 |
| `forestCommunityChance` | `25` | 森林群体抽签区间大小，0–50 |
| `smallFamilyChance` | `30` | 小家庭抽签区间大小，0–50 |
| `patchouliLeaderChance` | `50` | 森林群体拥有帕秋莉族长的百分比，0–100 |
| `forestMinHouseholds` / `forestMaxHouseholds` | `6` / `12` | 森林初始户数区间，各为 3–24；填反时取排序后的范围 |
| `householdMaxChildren` | `5` | 每户初始幼体数量上限，1–8 |
| `communitySpawnCap` | `96` | 森林生成时附近人口预算，1–256 |
| `communityPopulationCap` | `128` | 森林繁殖时总人口预算，含未出生预留，2–512 |

管理员命令使用现有 `/yukkuri` 权限（等级 2）：

```mcfunction
/yukkuri settlement spawn wanderer ~ ~ ~
/yukkuri settlement spawn family ~ ~ ~
/yukkuri settlement spawn forest ~ ~ ~
/yukkuri settlement spawn forest_led ~ ~ ~
```

位置可省略，默认命令发起位置。手动生成仍检查现成洞穴空间、可达性和种群上限，可在测试场地复核四种模板；不要求场地位于森林群系。对实体使用观察笔记，核对群体及亲缘页。将成员引出居住范围，观察步行返回；在傍晚核对族长识别，拆除屋顶后观察重新认屋。

## 群系季节与季节生活

没有按游戏天数轮转的四季：**地区就是季节**。雪林是冬，平原是春，森林是秋，沙漠和热带是夏。聚落的季节由聚落中心所在群系决定（无聚落则取住所，再取当前位置），不因成员走动而变。解析入口只有 `Seasons.at(level, pos)` / `Seasons.of(entity)`，将来接季节模组时替换这里即可。

群系由数据标签决定（`data/mcyukkuri/tags/worldgen/biome/season_{spring,summer,autumn,winter}.json`，整合包可追加），匹配顺序为冬、秋、夏、春；未被任何标签收录的群系按基础温度回退：`< 0.15` 冬，`>= 1.0` 夏，`< 0.5` 秋，其余春。`biomeSeasons = false` 时所有季节判断恒为否，行为与此前完全一致。

| 季节群系 | 生活表现 |
| --- | --- |
| 秋 | 住所储粮目标提高到每名住户 `autumnStockPerResident`（默认 6，上限 40），食物容量上限放宽到 48；采集与拾取等待间隔减半；储粮腐败放慢到 1.5 倍间隔 |
| 冬 | 夜间与降雪时（`level.isRaining()`），有可用住所的成员闭巢：只回巢待着，不外出社交、玩耍、采食、集合；晴天白天只在住所库存低于「住户数×2」时短途采集；聚落回家半径减半；腐败放慢；自然求偶需住所库存 ≥ `(住户数+2) × winterCourtStockPerHead`（默认 2），不动已有怀孕 |
| 冬（粮尽） | 住所库存为 0 且同住所两只成员都饥饿（营养 < 30）时「争粮」：互相 angry/teary 反应，双向好感降低 `winterQuarrelPenalty`（默认 6），冷却 2400 tick；饥饿成年个体（幼体营养 < 15）不再闭巢，可冒雪出门觅食，承担寒冷耗体力与湿冷冻伤 |
| 冬/春 出巢分粮 | 冬：一场降雪结束后的白天；春：每天清晨（日内 0–1500 tick）。聚落打开持续 `assemblyTicks`（默认 1200）的集合期，成员走到聚落中心附近打招呼；期间库存 > 住户数×`shareSurplusPerResident`（默认 4）的住户，从自家库存取 1 份真实食物，走到同聚落库存最少且 < 住户数×`shareNeedPerResident`（默认 2）的另一住所投入；每名成员 `shareCooldown`（默认 1200 tick）一次 |
| 夏 | 日内约 5000–8000 tick，露天且不在雨中的成员每天一次去最近的遮阴处（6 格内，非他人住所），否则 24 格内的自家床位停下休息，`tired` 反应；储粮腐败加快到一半间隔 |

实现位置：`settlement/Seasons.java`（判定入口，含 `confined`、`forageBlocked`、`mayCourt`）、`HabitatLife`（STAY、SIESTA、SHARE 任务，争粮，储粮目标）、`SettlementLife`（集合状态机，瞬态字段在 `SettlementData.Group`，不存档）、`ShelterBlockEntity`（食物上限与腐败节奏，存档格式不变）、`ForageGoal`、`DailyLife.available`、`FamilyLife` 求偶条件。笔记新增「群系与季节」章节，群体页显示当前季节。

管理员命令：`/yukkuri season [pos]` 报告该位置的季节与群系。

手动检查：在雪林放一个森林聚落（`/yukkuri settlement spawn forest`），夜里和下雪时成员待在巢里吃储粮；清空储粮后饥饿的同住所成员争吵，并冒雪外出觅食；雪停后的白天在中心集合，富余的家把粮送给缺粮的家。森林（秋）里囤粮更积极；平原（春）清晨集合分粮；热带草原（夏）正午躲阴凉。笔记群体页显示季节，`/yukkuri season` 报告季节。

未做：饥荒献身（计划 2.4）未实现，粮尽目前只有争吵与外出觅食，之后在 `HabitatLife.quarrel` 处衔接；夏季午歇只对有住所或附近有遮阴的个体生效；不做体感温度（计划 1.3）。

## 职位与族长

设定依据：族长只在部分作品中是组织者；体型大或是 DOS 都不自动成为首领；首领会分派觅食与侦察（派橙传达，派妖梦打探）。

- 职位：`SettlementData.Role`（FREE、MEMBER、LEADER、OUTCAST、SLAVE）。`Group.roles` 只存 OUTCAST/SLAVE，LEADER 由 `leader` 字段决定，其余默认 MEMBER；`Group.led` 表示群体应当有族长（出生带族长为真，旧存档以有无族长推断），`vacantSince` 记录空缺起始时刻。查询入口 `SettlementLife.role()`、`Group.role(uuid)`，写入 `SettlementData.setRole/setLeader`。失饰降级（计划 1.1）尚未接入，OUTCAST/SLAVE 成员不参与继任也不接受分派。
- 族长更替：族长死亡、移除或 `LOST` 后清空并记空缺时刻；`led` 的群体在服丧期（`community.mourningTicks`，默认 2400）内成员各哭一次，期满由任一已加载成员触发一次选举（`Succession.tick`）。候选为中心 48 格内已加载的存活成年非捕食、非 OUTCAST/SLAVE 个体，评分为守序 + 认知 × 0.3 + 年龄（封顶 10 日 × 2）+ 帕秋莉小加成，不看体型。无人合格则保持空缺，约 400 tick 后重试。新族长高兴，附近成员惊讶。`led == false` 的森林聚落不选举。
- 分派：`SettlementOrders`（每个实体一个，不存档），由 `OrderGoal`（优先级 5）执行。族长在白天、安全、距中心 16 格内，每 `orderMinInterval`–`orderMaxInterval` tick（默认 1200–2400）从 12 格内可见、互相认出、空闲的成年成员里挑一只下令。
  - 觅食：中心 16 格内取一份短草（遵守 mobGriefing）或地上的食物物品，带回存进自家住所；住所不收或不存在则掉落。
  - 侦察：优先好奇心 + 大胆最高的成员（妖梦接入后改为优先妖梦，计划 4.2），走到中心外 16–24 格的可站立点张望约 60 tick，再回族长身边汇报；途中 16 格内看见捕食种则惊慌汇报并调用 `SettlementData.reportThreat`（`Group.threat`、`threatPos`、`threatTime`，存档，供迁徙使用，见下节）。
  - 传达：优先橙，依次走到最多 3 只 32 格内的其他成员身边，说话后对方被召集约 600 tick，在族长身边集合（`SettlementLife.canUse` 里的召集分支）。
  - 命令超时 `community.orderTimeout`（默认 1200）；惊慌、紧急需求、睡觉、事件占用、认屋都会取消；同一成员完成或取消后有 3600 tick 冷却。
- 观察与命令：笔记群体页显示职位、当前命令与“空缺（服丧中／待选）”；`/yukkuri settlement info` 报告命令位置最近的群体（类型、中心、族长、是否设族长、空缺时长、已加载成员职位统计、威胁次数）。
- 手动检查：`/yukkuri settlement spawn forest_led` 后杀掉族长，约 2 分钟（服丧）后应出现新族长；白天族长会隔一阵向成员下令，被派者走开办事再回来。可把 `community.mourningTicks` 调小加快验证。
- 未做：妖梦优先侦察、嗯嗯奴隶清厕所、失饰降级（计划 1.1）；分派的携带物不会在实体卸载后恢复（命令不存档）。

## 传闻与迁徙（计划 3.4）

设定依据：TM03／TM04，群落把红眼访客当作捕食者的征兆，听信帕秋莉的判断弃巢。原作没交代逃亡者的结局，所以这里不额外制造死亡，风险只来自既有机制。

- 威胁积累（`SettlementData.Group.threat`，上限 20，存档）：成员（`SettlementLife.tick` 每 100 tick 经 `Migration.tick`）12 格内有视线的捕食种记 1 点，同一只对同一群体 2400 tick 内只计一次；侦察汇报记 1 点；成员被捕食种打伤记 2 点（200 tick 内只计一次），被杀记 3 点（`YukkuriEntity.hurt/die` 经 `SettlementLife.attackedBy`）。`Migration.omen(...)` 预留给红眼妖梦（计划 4.2），记 2 点，目前没有调用方。威胁在 `community.threatDecayTicks`（默认 24000）无新报告后每期 −1。BANDITS 群体不累积也不迁徙。
- 决定：只有有族长（已加载、成年、清醒、未惊慌且仍属该群体）的群体会迁徙；无族长或族长不在场时成员只会害怕（`afraid`），不整体搬家。威胁 ≥ `community.migrateThreshold`（默认 3，0 为关闭）后，族长每约 5 秒（失败后至少隔 600 tick）按性格掷一次：基础 50%，帕秋莉 +35%，大胆每高于 50 一点 −0.5% [original]。通过后族长 `surprise/afraid`，24 格内玩家收到无配音文字（`message.mcyukkuri.settlement.migrate`），32 格内成员 `surprise/heard`。
- 选址（`Migration.pickSite`）：从中心朝远离最近威胁位置的方向（±60°）尝试 24 次，距中心 `migrateMinDistance`–`migrateMaxDistance`（默认 32–48），要求区块已加载、可站立、距威胁点 ≥ 24 格、8 格内至少 3 个 `NaturalShelter.enclosed` 格子。找不到就放弃，威胁不清零，稍后再试。
- 过程（`Group.migrateTo` / `migrateSince`，存档）：`SettlementLife.migrate` 让参与者（成年、非 OUTCAST/SLAVE、不在认屋、无主人）朝目标走，超过 48 格的限制放开，仍只在已加载区块里分段导航、不传送；族长先行，成员不会走到族长前面太多。幼体沿用跟随亲代。到目标 8 格内的成员释放旧住所并 `findHome` 找/建新住所，找不到就露宿，之后按日常逻辑继续找。
- 完成：族长到达目标且已加载参与者至少一半到达，或超过 `community.migrateTimeout`（默认 6000，任一成员都能触发超时结算）。完成后 `Group.center` 改为目标、威胁清零、集合状态清除、`happy/hello`。掉队者仍属该群体：`SettlementLife.origin` 记录成员最后安顿的中心，与当前中心不同的成年参与者会在 96 格内分段走向新中心，到达后同样释放旧住所、找新住所。
- 迁徙期间暂停：族长分派（`SettlementOrders.tickLeader` 与成员的在途命令）、出巢集合（季节集合不再开启，成员走迁徙分支）、分粮（`HabitatLife.shareStart`）；尚未到达新址的参与者也不再在旧巢做进食、采集、闭巢等住所杂务（`Migration.underway`）。
- 后果：储粮留在旧巢；新址的季节取决于新中心所在群系，从森林搬进雪林就要在冬季冷却、闭巢缺粮下重新备粮，风险由既有的季节生活机制体现。
- 观察与命令：笔记群体页显示威胁程度（平静／不安／恐慌）与迁徙目标和已用时间；`/yukkuri settlement threat <1-20>` 给最近群体加威胁；`/yukkuri settlement migrate` 让最近群体已加载的族长立即尝试迁址（跳过阈值与掷骰，选址检查仍然有效）。
- 手动检查：`/yukkuri settlement spawn forest_led`，在聚落旁生成雷米莉亚（或用 `threat 3`），族长应喊话并带队走向远离威胁的新址；到达后用 `info` 看中心已变。可调小 `migrateTimeout` 验证超时结算。
- 未做：红眼妖梦触发的 `omen`（计划 4.2）；迁徙途中的储粮搬运；离新中心超过 96 格的掉队者回归。

## 验证与边界

专用夹具：`SettlementGameTests`，全套执行入口为 `./gradlew build runP0GameTest`。检查报告位于 `.local/p0/gametest/p0-control/settlement-checks.json` 与 `settlement-movement-checks.json`。

也可先运行 `./gradlew build createP0GameTestLaunchScript`，再运行 `python scripts/run_settlement_checks.py`。该入口通过 `mcyukkuri.p0.suite=settlements` 只注册群落检查，使用独立的 `.local/settlements/gametest/` 世界和报告，避免与其他任务共用测试存档。仍需将 `JAVA_HOME` 指向 JDK 21。

住所修订后的检查另见[野外住所](WILD_SHELTERS.md)，不沿用人口阶段结果作为住所证据。

本次不实现成套村庄建筑、公共粮仓、族长选举、领地战争和跨群系的季节迁居（季节随群系固定，不随时间轮转）。自然地形上的长时生存、野草再生平衡与多人游玩体验仍需持续验收；受控引擎检查不等于这些项目已经通过。
