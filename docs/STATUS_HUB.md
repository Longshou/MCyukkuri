# 状态中枢与反应仲裁

代码在 `src/main/java/io/github/longshou/mcyukkuri/status/`。状态中枢为命令、场景与观察提供统一命名、查询和受控写入，真实数值与业务数据仍由各领域拥有。`StatusLife` 自身持有外观印记和威胁/天气反应；实体生命周期、显示与同步分层见[代码架构说明](ARCHITECTURE.md)。

## 1. 状态登记与领域所有者

| 部分 | 作用 |
| --- | --- |
| `StatusKey` | 标量登记，使用 `组.名`（如 `needs.nutrition`、`health.wound`）。读取器调用真正拥有数据的组件，可选写入器调用其业务方法，并定义观察变化的分桶。 |
| `YukkuriStatus` | 查询/受控写入门面：`identity`、`get`、`set`、`relate`、`snapshot`、`describe`、`label`。 |
| `StatusLife`（`entity.status()`） | 持有 `Marks`、`Reactions`，更新外观同步；服务端存活且非 NoAI 时每 5 tick 扫描感知，每 20 tick 更新印记，并按 trace 开关采样状态。 |
| `StatusChangedEvent`、`RelationChangedEvent` | 服务端 NeoForge 总线事件。状态事件在开启 trace 后由采样变化产生；关系事件由真实好感写入路径触发，不依赖 trace 开关。 |
| `StatusCommands` | `/yukkuri status <目标>`、`... raw`、`status set <目标> <键> <值>`、`status relate <从> <到> <分>`、`status trace <true|false>`。 |

登记不另存一份领域数据。各领域正常业务继续直接走自己的 API，命令和场景的外部修改才通过登记的受控写入器。

| 数据 | 权威所有者/查询来源 |
| --- | --- |
| 营养、体力、卫生、湿度等需求 | `life/YukkuriLife` 及其生活委托 |
| 伤口、感染、馅料等身体状态 | `body/BodyLife` 及身体模型；显示贴花不等于真实伤口 |
| 成长、孕育、亲代照料 | `family/FamilyLife` 及成长、求偶、分娩、育幼等委托；亲缘账本在 `FamilyData` |
| 陆地住处、家油住处、帽船家庭归属 | `HabitatLife`、`LifeHome`（经 `YukkuriLife`）、`HouseLife`、家庭与水生领域 |
| 聚落身份与好感 | `SettlementLife` / `SettlementData`、`YukkuriWorldData` |
| 饲主与人类用途 | `human/Custody` 的 `HumanCommerce` 数据及 `CustodyData`；宠物资格/徽章由 `PetLife` 等持有 |
| 不安、甜化、非油症与取名 | `mind/MindLife`；`PetLife.stress` 为不安视图，规则见[情绪链](EMOTION_CHAIN.md) |
| 脏污、伤痕、威胁/天气反应 | `StatusLife` 的 `Marks`、`Reactions` |

`Identity` 由 `YukkuriStatus.identity` 按顺序推导：家油 `HOUSE` → 宠物 `PET` → 有主 `OWNED` → `HabitatLife.Residence.VAGRANT` 的流浪 `STRAY` → 有聚落记录的 `SETTLER` → 其余按住所记忆、生活住处、帽船家庭住处或依亲 `DEPENDENT` 判 `WILD` / `STRAY`。聚落成员表中查不到某 UUID 时，该个体的聚落角色为 `FREE`；未加载或移除不等于死亡，不能据此清理成员记录。

住所种类查询包括 `cardboard`、`burrow`、`shelter`、`house_nest`、`cover`、`nest`、`hat_boat`、`digging`、`none`，由现有领域状态推导，不在状态中枢维护第二份地址。

## 2. 查询、受控写入与调试

`/yukkuri status <目标> raw` 即时产生快照。分组包括 `needs`、`mood`、`health`、`marks`、`reproduction`、`growth`、`personality`、`identity`、`home`、`relations`、`behavior`；完整快照还包含伤口列表、印记、亲属和 `relations.objects` 定向对象关系（亲近、恐惧、最近互动时间）。

保存观察视图由 SERVER 配置控制：

```toml
[debug]
entitySaveViews = false
```

默认关闭。开启后实体保存时附带 `YukkuriStatusView`、`YukkuriDebugGoals`、`YukkuriForageView`、`YukkuriHabitatView`，可用 `/data get entity <e> YukkuriStatusView` 等读取。完整新建试玩场会写入开启配置，既有世界不会因此自动开启。这些 tag 是只读观察输出，不是业务权威、加载源或客户端同步协议。

`status trace true` 另行开启状态采样与日志：非 NoAI 个体每 20 tick 对可读键取观察值，前后分桶变化才发 `StatusChangedEvent`；首次采样只建基线。关闭后停止采样并清空比较缓存。好感变更的 `RelationChangedEvent` 仍由业务路径独立发出，trace 只控制其日志。

受控写入走真实逻辑：需求经 `YukkuriLife.setNeed`；伤口经 `BodyLife.addWound`（如 `health.wound=tear:2`，漏馅、感染、愈合照常）；感染只能升高；`mood.unease` 经 `MindLife.set`，`mood.stress` 经 `PetLife.addStress` 仍改变同一个不安值；脏污/伤痕经 `Marks`；亲近经 `YukkuriWorldData.relate`，`status fear <来源> <对象> <0–100>` 写独立恐惧；对象可为玩家、NPC或油库里。`personality` 五轴经性格组件写入并服从形态边界，`growth.age_ticks` 经固定成长时钟初始化真实年龄。只读条目不允许写入。

灵胖读数：`growth.deibu_exposure` 是成年前连续达标的 tick 数；`growth.deibu_pending` 表示成年结果已锁定、体型尚未应用；`growth.deibu_space_state` 的 `pending` 表示等待安全接缝或有限重试，`limited` 表示三次扩体尝试失败、等待位置或空间变化，`applied` 表示体型已应用。

## 3. 感知与反应规则

`Reactions` 拥有普通个体的威胁与天气感知。`ThreatGoal` 的实际 AI 优先级为 1，`WeatherGoal` 为 2，数字越小越优先；它们与其他 Goal 一起受真实目标选择器约束。显示胜者另外经过第 4 节的显示仲裁。

`prey()` 排除捕食种、家油、NoAI 和乘员；`free()` 进一步排除命令、接触操作、宠物忙碌、水中/着火、落水、生产、助产、辫救、等待落地和真实进食等接管状态。近距离惊慌警告使用单独的 `alarmable()` 条件，不能把“当前不由 ThreatGoal 控制”解释成一定听不见危险。

**普通个体的定向噗咕回应（项目设计）**：护尸抗议、制裁宣告和同伴护卫在真实发生且六格内可见时通知目标，单个接收者至少间隔 100 tick 再判断；环境语音和展示动作不会自行触发业务。取当前体宽比减一并限到 ±1，信心为 `胆量−50+35×体型差−0.4×对象恐惧`；怒意为 `信心+0.6×(50−友善)+0.15×任务重要程度`。危险／生产／护幼优先级 100、极饿或精力≤10 为 90、亲子照料／进食／正在自卫为 75、其他急需／劳动／远行觅食为 60、普通社交为 30、闲置为 10。危险优先时无视；生命≤40% 或 `信心+0.25×重要程度 < −12` 则逃跑；有战斗能力且怒意≥45 则激怒攻击；否则重要程度≥70 或友善≥70且信心非负时无视，其余噗咕回应 40 tick。攻击沿用真实接近、接触、消耗与受伤退避；回噗咕可通知原发起者，但不递归立即回击。读 `behavior.group_session` 与服务端 `[social] puff` 数值，不以单次动画代替判定。

**感知每 5 tick 扫描：**

- 清醒时感知 16 格范围内可见或近距离的捕食种；同主人与受教育保护等条件由 `Threat.menacing` 判定。睡眠时不扫描接近者，不接收接近警报；真实受击、淋雨等危险仍立即唤醒，困值高于 50 时回到 50。
- 12 格内能认出的照护亲子、伴侣、同群、照护亲缘或达到亲近好感的同伴可收到警告，无视线也可进入警戒。
- 受击后由 `hurtBy` 把真实攻击者通知可感知的亲友。
- `Sky.at` 用原版降水与头顶遮挡区分晴、雨、雷暴和雪。

**威胁反应：** 9 − 胆量/20 格，并按该对象恐惧增加退避距离，或捕食者正在追猎且 14 格内，进入既有惊慌并通知亲友。看得见威胁时直接 `threat_retreat`，退向安全的住处／附近主人或远离威胁；仅听见而看不见来源时先短暂 `threat_watch`，随后退避、`threat_hide` 躲藏。玩家或人形 NPC 实际攻击时，受害者八格内同时看得见受害者与攻击者的普通个体也会报警，无需亲缘；致命一击同样通知。陌生个体不会隔墙感知，亲友仍保留原有呼救传播，同一威胁的重复呼叫沿用冷却。阶段和目标推进由 `Reactions.Threat` / `ThreatGoal` 拥有。

**天气反应：** 雨和雷暴，或体感冷时的雪，触发寻找有顶、可达且可用的遮蔽，优先自己的住处。普通掩体搜索半径 32 格；无目标时进入 `weather:search`，每 80 tick 重新搜索，沿真实可走地面寻找。每轮最多验证 8 条候选路径，轮换候选以免最近的不可达屋顶持续挡住远处目标。赤油日常搜索遵守巢边边界。到达实际干燥处后释放避雨 Goal，由普通生活 Goal 安排后续活动；原 `WAIT` 只用于到达交接，不持续占据移动。目标失效或仍在淋雨则继续找掩体，雨停仍可短暂抖水。体力低于 10、营养低于 20 或尿意达到 90 时，让位给真实生活需求。

雨天活动规则（项目设计）：在干燥处的普通个体平时不走入淋雨位置，地面寻路可绕开露天缺口，近身最后一步同样检查；营养低于 20 时可冒雨找食物，真实危险、生产与救援保持原有优先级。`MindLife` 按当地降水类型加入雨天每分钟 +2.5、雷暴 +5 的不安原因，仍与安心、居家、睡眠等原有增减合算；不安不因站在屋顶下完全失去天气影响。`behavior.weather` 只读实际阶段、搜索半径与避雨偏好，`behavior.roaming` 观察闲逛和子油探险。相关演出最后统一补，见[待办陆 14](TODO.md#复测失败待修复)。

搜索经 `settlement/SearchBudget` 兼容入口进入 `world/SearchBudget` 的共享账本，分别返回找到遮蔽、搜索无结果和等待预算三种状态。

`marks.face=true` 指定人工展示的脏污、后续预置疤痕及油霉位于脸部；保存于身体 `FaceMarks`，同步展示位标志，既有伤口和自然愈合疤痕不搬移。

湿度和水伤由生活/身体领域负责，本层负责感知与避险行动。帽船乘员的水上警报由 `HatFamilyAlarm` 等水生组件处理。

## 4. 从规则到显示

行为由规则/Goal 产生，`entity/presentation/PresentationGoals` 把威胁与天气分别绑定到 `ThreatGoal`、`WeatherGoal`，从当前运行的 Goal 中按选择器 priority 和同级注册序选出显示来源。

`YukkuriControl` 负责惊慌／命令时钟、缩壳导航控制和固定接管判定。`PresentationArbiter` 读取本 tick 的接管快照；普通路径依次处理病态、水中、进食和家庭持续状态，再选择运行中的 Goal，最后使用家庭兜底表现。

`EcologyPresentation` 为 `threat_*`、`rain_*` 提供动作和表情，命令等可覆盖动作。表情由服务端 `PresentationArbiter` 按操作姿态、实际反应、伤病或哭泣、情绪、兜底顺序选择；帽子归属不改变兜底表情，失饰压力由情绪层持续提供。调试视图记录本 tick 选中的来源，客户端通过 `YukkuriView` / `HatBoatView` 读取同步展示值，不再另行叠加操作、躁动或繁殖表情。

## 5. 脏污、伤痕与橙汁粒子

- `Marks` 保存脏污 0–3 与最多 3 处伤痕，存档路径为 `YukkuriStatus.Marks`。
- 自由个体（非赤油）首次滚动外观时有 10% 概率带脏污（其中七成一级）、6% 概率带伤痕；卫生低于 80 的流浪个体生活约 6 分钟增加一级固着脏污（上限 2）。卫生低于 45/30/15 时至少显示 1/2/3 级脏污。
- 1–2 级伤口经自然愈合或舔舐闭合最后一级时，在同一皮面计算留疤。一级裂口／灼伤／凹伤／擦伤的基础概率为 60%／50%／25%／12%；原伤最高严重度每高一级增加 20 个百分点，上限 100%，留下的疤级等于最高严重度。`BodyWound.PeakSeverity` 随伤口保存。橙汁可直接清除所有等级伤口，不新留疤，也不清已有疤痕。
- 雨淋约 40 秒降低一级脏污；卫生一次回升至少 10 也降低一级。疤痕不自然消失，舔舐不能去疤；面粉糊清除现存疤痕，包括旧伤口列表中的 `scar`，但不降活动伤级、不回血、不去脏污。没有疤痕时材料不消耗；诊断命令仍可清疤。
- 服务端把可见印记（脏斑为 2×级数，另加伤痕）写入 `MARKS`；客户端 `WoundRenderer` 绘制 `dirt` / `scar`，不把它们计作 `BodyLife` 伤口或凹陷。脏斑由 UUID 决定位置，避开脸和底面。
- 制作源 `art/props/body-marks/build.py`，运行贴图 `textures/props/body_marks.png`，登记在 `art/runtime-sources.json` 的 `body_marks`。美术仍为 `pending_user_confirmation`，结构整理不改变确认状态。

`TreatmentItem` 橙汁恢复成功后调用 `entity.status().orangeRestored()`，由服务端向附近客户端发送持续 50 tick 的双螺旋橙色 `DUST`、顶部 `END_ROD` 与起始 `HAPPY_VILLAGER` 粒子。

## 6. 扩展边界

新增状态先确定领域所有者，再让 `StatusKey` 读取其结果；新增外部写操作调用该领域业务 API。感知输入可读取 `Sky.at`、`reactions().threat().source()`，扩展反应时由 `Reactions` 与实际 Goal 拥有阶段，统一接入显示仲裁。

状态事件采样当前依赖 trace，不能把订阅 `StatusChangedEvent` 当作默认持续运行的玩法时钟。新业务需要稳定时钟或通知时，应在真实领域生命周期中明确接入。

连续表现、多人同步与观感以用户在当前试玩包中的实际观察为准。
