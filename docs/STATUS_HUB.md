# 状态中枢与反应仲裁

代码在 `src/main/java/io/github/longshou/mcyukkuri/status/`。状态中枢为命令、场景与观察提供统一命名、查询和受控写入，真实数值与业务数据仍由各领域拥有。`StatusLife` 自身持有外观印记和威胁/天气反应；实体生命周期、显示与同步分层见[代码架构说明](REFACTOR_HANDOFF.md)。

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

`Identity` 由 `YukkuriStatus.identity` 按顺序推导：家油 `HOUSE` → 宠物 `PET` → 有主 `OWNED` → `HabitatLife.Residence.VAGRANT` 的流浪 `STRAY` → 有聚落记录的 `SETTLER` → 其余按住所记忆、生活住处、帽船家庭住处或依亲 `DEPENDENT` 判 `WILD` / `STRAY`。有聚落记录不保证已成年流浪者仍显示聚落身份。

住所种类查询包括 `cardboard`、`burrow`、`shelter`、`house_nest`、`cover`、`nest`、`hat_boat`、`digging`、`none`，由现有领域状态推导，不在状态中枢维护第二份地址。

## 2. 查询、受控写入与调试

`/yukkuri status <目标> raw` 即时产生快照。分组包括 `needs`、`mood`、`health`、`marks`、`reproduction`、`growth`、`identity`、`home`、`relations`、`behavior`；完整快照还包含伤口列表、印记、亲属和好感记录。

保存观察视图由 SERVER 配置控制：

```toml
[debug]
entitySaveViews = false
```

默认关闭。开启后实体保存时附带 `YukkuriStatusView`、`YukkuriDebugGoals`、`YukkuriForageView`、`YukkuriHabitatView`，可用 `/data get entity <e> YukkuriStatusView` 等读取。完整新建试玩场会写入开启配置，既有世界不会因此自动开启。这些 tag 是只读观察输出，不是业务权威、加载源或客户端同步协议。

`status trace true` 另行开启状态采样与日志：非 NoAI 个体每 20 tick 对可读键取观察值，前后分桶变化才发 `StatusChangedEvent`；首次采样只建基线。关闭后停止采样并清空比较缓存。好感变更的 `RelationChangedEvent` 仍由业务路径独立发出，trace 只控制其日志。

受控写入走真实逻辑：需求经 `YukkuriLife.setNeed`；伤口经 `BodyLife.addWound`（如 `health.wound=tear:2`，漏馅、感染、愈合照常）；感染只能升高；`mood.unease` 经 `MindLife.set`，`mood.stress` 经 `PetLife.addStress` 仍改变同一个不安值；脏污/伤痕经 `Marks`；好感经 `YukkuriWorldData.relate`。只读条目不允许写入。

## 3. 感知与反应规则

`Reactions` 拥有普通个体的威胁与天气感知。`ThreatGoal` 的实际 AI 优先级为 1，`WeatherGoal` 为 2，数字越小越优先；它们与其他 Goal 一起受真实目标选择器约束。显示胜者另外经过第 4 节的显示仲裁。

`prey()` 排除捕食种、家油、NoAI 和乘员；`free()` 进一步排除命令、接触操作、宠物忙碌、水中/着火、落水、生产、助产、辫救、等待落地和真实进食等接管状态。近距离惊慌警告使用单独的 `alarmable()` 条件，不能把“当前不由 ThreatGoal 控制”解释成一定听不见危险。

**感知每 5 tick 扫描：**

- 16 格范围内可见或近距离的捕食种；同主人与受教育保护等条件由 `Threat.menacing` 判定。
- 12 格内能认出的照护亲子、伴侣、同群、照护亲缘或达到亲近好感的同伴可收到警告，无视线也可进入警戒。
- 受击后由 `hurtBy` 把真实攻击者通知可感知的亲友。
- `Sky.at` 用原版降水与头顶遮挡区分晴、雨、雷暴和雪。

**威胁反应：** 9 − 胆量/20 格以内（幼体加 2），或捕食者正在追猎且 14 格内，进入既有惊慌并通知亲友。较远但仍能感知时，经 `threat_watch` 注视、`threat_retreat` 退向住处/附近主人或远离威胁、`threat_hide` 躲藏；离开感知一段时间后结束并冷却。阶段和目标推进由 `Reactions.Threat` / `ThreatGoal` 拥有。

**天气反应：** 雨和雷暴，或体感冷时的雪，触发寻找有顶、可达且可用的遮蔽，优先自己的住处。阶段为 `rain_run`、`rain_wait`、`rain_shake`；没有遮蔽时 `rain_huddle` 靠近同伴，并定期重新寻找。所有阶段均在体力低于 10、营养低于 20 或尿意达到 90 时让位给真实生活需求；仅 `WAIT` 阶段另在体力低于 35 时结束等待，让生活 Goal 安排睡眠。

搜索经 `settlement/SearchBudget` 兼容入口进入 `world/SearchBudget` 的共享账本；预算延后与没有找到遮蔽是不同结果，不能描述成一个不受其他搜索影响的独立计数器。

湿度和水伤仍由生活/身体领域负责；本层负责感知与避险行动。帽船乘员的水上警报由 `HatFamilyAlarm` 等水生领域处理，不能靠放宽一个 `prey()` 条件就声称完成水上生态。水生后续方案和第二轮反馈仍见[待办](TODO.md)，尚未作为本次重构实现。

## 4. 从规则到显示

行为由规则/Goal 产生，`entity/presentation/PresentationGoals` 把威胁与天气分别绑定到实际 `ThreatGoal`、`WeatherGoal`。只观察当前运行的同一 Goal，按选择器 priority 和同级注册序排列，不调用启动/继续资格或计划方法来探测显示。

`YukkuriControl` 先推进惊慌／命令时钟并确定辫救、帽船、落水、惊慌、缩壳等固定接管。`PresentationArbiter` 读取这一 tick 的接管快照；普通路径保留病态、水中、真实进食及家庭持续状态，然后才选实际运行的 Goal，最后有家庭兜底。显示层不递减惊慌、不清除命令，也不停止导航。`reactions().activity()` 因而不是行为链无条件第一项。

`EcologyPresentation` 为 `threat_*`、`rain_*` 提供动作和表情；命令等还可能覆盖动作。调试视图中的显示来源只记录本 tick 真正选中的结果，不重新执行候选。客户端通过 `YukkuriView` / `HatBoatView` 读取同步展示值，不取 `entity.status()` 或其他服务端组件。

## 5. 脏污、伤痕与橙汁粒子

- `Marks` 保存脏污 0–3 与最多 3 处伤痕，存档路径为 `YukkuriStatus.Marks`。
- 自由个体（非赤油）首次滚动外观时有 10% 概率带脏污（其中七成一级）、6% 概率带伤痕；卫生低于 80 的流浪个体生活约 6 分钟增加一级固着脏污（上限 2）。卫生低于 45/30/15 时至少显示 1/2/3 级脏污。
- 严重度 1 级伤口自然愈合后，裂口/焦痕/凹陷/其他伤型分别有 60%/50%/25%/12% 概率在同一皮面留下伤痕；橙汁清除伤口不留伤痕。
- 雨淋约 40 秒降低一级脏污；卫生一次回升至少 10 也降低一级。伤痕不会自然消失，命令可清。
- 服务端把可见印记（脏斑为 2×级数，另加伤痕）写入 `MARKS`；客户端 `WoundRenderer` 绘制 `dirt` / `scar`，不把它们计作 `BodyLife` 伤口或凹陷。脏斑由 UUID 决定位置，避开脸和底面。
- 制作源 `art/props/body-marks/build.py`，运行贴图 `textures/props/body_marks.png`，登记在 `art/runtime-sources.json` 的 `body_marks`。美术仍为 `pending_user_confirmation`，结构整理不改变确认状态。

`TreatmentItem` 橙汁恢复成功后调用 `entity.status().orangeRestored()`，由服务端向附近客户端发送持续 50 tick 的双螺旋橙色 `DUST`、顶部 `END_ROD` 与起始 `HAPPY_VILLAGER` 粒子；相关视觉效果仍待用户试玩确认。

## 6. 扩展边界

新增状态先确定领域所有者，再让 `StatusKey` 读取其结果；新增外部写操作调用该领域业务 API。感知输入可读取 `Sky.at`、`reactions().threat().source()`，扩展反应时由 `Reactions` 与实际 Goal 拥有阶段，统一接入显示仲裁。

状态事件采样当前依赖 trace，不能把订阅 `StatusChangedEvent` 当作默认持续运行的玩法时钟。新业务需要稳定时钟或通知时，应在真实领域生命周期中明确接入。

当前实现等待用户在唯一试玩场确认连续表现、多人同步与观感。旧运行包或固定 `playground` 分支的观察不作为当前 main 的试玩结论。
