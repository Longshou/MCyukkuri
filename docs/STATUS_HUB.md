# 状态中枢与反应仲裁

代码在 `src/main/java/io/github/longshou/mcyukkuri/status/`。设计目标：油库里的数值、伤口、关系、身份、住所、当前行为只有**一份登记和一个读写入口**，感知与反应只有**一个仲裁框架**；各领域包（`settlement/`、`house/`、`family/`、`subspecies/`、`pet/`）继续拥有自己的逻辑和数据，通过这套接口被读取、被接入。

## 1. 状态登记

| 部分 | 作用 |
| --- | --- |
| `StatusKey` | 标量条目登记，`组.名`（如 `needs.nutrition`、`health.wound`、`identity.kind`）。每条有一个读取器（调用真正拥有该值的组件）、可选的写入器、变化事件的分桶。新增状态只加一行。 |
| `YukkuriStatus` | 门面：`identity(y)`、`get`、`set`、`relate`、`snapshot`（NBT）、`describe`（文本）、`label`（试玩场头顶一行）。 |
| `Identity` | 身份，由 `YukkuriStatus.identity` 在一处推导：`HOUSE` 家油、`PET` 宠物、`OWNED` 有主非伴侣、`SETTLER` 聚落成员、`STRAY` 无任何住所、`WILD` 有住所的野生。 |
| `StatusLife` | 每只个体的挂点（`entity.status()`）：持有 `Marks`、`Reactions`，每 20 tick 对登记采样并发变化事件，保存脏污／伤痕。 |
| `StatusChangedEvent`、`RelationChangedEvent` | NeoForge 总线事件（服务端）。数值型条目按分桶变化才触发（需求 10 一档）；好感变化由 `YukkuriWorldData.relate` 触发。 |
| `StatusCommands` | `/yukkuri status <目标>`（分组文本）、`... raw`（SNBT）、`status set <目标> <键> <值>`、`status relate <从> <到> <分>`、`status trace <true|false>`（把变化写进服务端日志）。 |

**不复制数据。** 登记只读取各组件，所以不会与组件不一致；需要“迁入”的字段先不迁（见第 6 节）。

**一次读全：** `/data get entity <e> YukkuriStatusView`（实体存档里附带当前快照，只读）或 `/yukkuri status <e> raw`。快照分组：`needs`（营养、体力、卫生、尿意、湿度、干渴、体温状态）、`mood`（压力、亲昵需求、无聊、惊慌）、`health`（生命、伤口等级、灼伤、感染、包扎、馅量、脱发、霉斑、伤口列表）、`marks`（脏污、伤痕）、`reproduction`（怀孕、胎类型、进度、未出生数、绝育）、`growth`（阶段、年龄、成长百分比、寿命）、`identity`、`home`（种类、位置）、`relations`（伴侣、父母、子女、好感列表、对玩家的信任与恐惧）、`behavior`（行为、动画、表情、当前反应、天空）。

**受控写入**走各组件的真实逻辑：需求写入 `YukkuriLife.setNeed`；伤口是 `BodyLife.addWound` 的真实皮面伤口（`health.wound=tear:2`，漏馅、愈合、感染照常发生）；感染只能升高；压力走 `PetLife.addStress`；脏污／伤痕走 `Marks`；好感走 `YukkuriWorldData.relate`（会触发事件）。登记里标明只读的条目不能写。

## 2. 为什么普通油库里“看不见”捕食种和天气（根因）

- 捕食种：普通个体**没有任何感知**。唯一的反应在 `PredatorLife.alertPrey`，且只在捕食者自己**开始追猎**（饥饿、视线内、没有教育约束）时才对 8 格内个体调用 `frightenFrom`。捕食者在 10 格外游荡、不饿或者在巡逻，个体什么都不会做。
- 天气：下雨只把 `wetness` 加 2/秒；唯一的“躲雨”分支在 `YukkuriLife.plan()`，要求 8 格内恰好有一个有顶、可站、可达的位置，没有就静默无事；没有任何表情、台词、动作。雷暴和雪没有任何独立反应。

## 3. 反应仲裁（`Reactions`）

目标优先级沿用 Minecraft 的“数字越小越优先、同级不抢占”，约定为：

| 优先级 | 层 | 内容 |
| --- | --- | --- |
| 0 | 生存 | 脱水、操作接触、家油出巢 |
| 1 | 紧急 | 受惊逃跑 `FleeGoal`、缩壳、**`ThreatGoal`** |
| 2 | 需求 | **`WeatherGoal`**（躲雨雪） |
| 3–4 | 生活需求 | 进食、睡眠、排泄、温度、家务 |
| 5–6 | 社交、家庭、聚落 | |
| 7+ | 闲逛 | |

**感知（每 5 tick，`StatusLife.tick`）：**
- 视线内或 6 格内的捕食种（16 格范围）。同主人的捕食种不算；受教育且遵守“保护宠物”的捕食种不吓受保护的宠物。
- 同伴警告：亲子、伴侣、同聚落、好感≥10 或同血缘者在 12 格内会“听见”警告，没有视线也进入警戒。
- 受到伤害：旁边的亲属听见攻击者。
- 脚下天空（`Sky`）：晴、雨、雷暴、雪（用原版降水判定和头顶遮挡）。

**威胁反应：** 9 − 胆量/20 格以内（幼体 +2），或捕食者正在追猎且 14 格内 → 立刻惊恐（说 `afraid`，`frightenFrom` 触发既有的惊慌与逃往住所／主人）并通知亲属。更远但看得见的 → `ThreatGoal`：停下手中事、`threat_watch` 注视 25 tick，然后 `threat_retreat` 退向自己的住所（宠物退向 16 格内的主人，没有住所就远离）；到了住所或拉开 20 格 → `threat_hide` 藏着发抖，直到看不见威胁 80 tick（最长 60 秒），随后冷却。生产性动作（进食、睡觉、社交）都被它抢占，但生育中、接触操作中、被命令中、在水里的个体不会被它控制。

**天气反应：** 淋雨（雨、雷暴；雪只在体感冷时）→ 说 `cold`（雷暴为惊讶），然后 `rain_run` 跑向 16 格内最近的有顶、可达、不属于别人的位置（优先自己的住所）；到位 `rain_wait` 颤抖等到雨停，之后 `rain_shake` 抖水。没有遮蔽 → `rain_huddle` 靠近最近的同伴缩着，每 5 秒重新找遮蔽。体力低于 35 就让位给生活目标去睡觉，营养<20、尿意≥90 同样让位（雨中不挨饿）。搜索走 `SearchBudget`，每 tick 全服最多三次。
湿度仍由 `YukkuriLife` 负责（淋雨 +2/秒，达阈值掉血，即“怕水溶化”）；本层只负责“知道、说出来、去躲”。

**行为表现：** `entity.status().reactions().activity()` 排在 `YukkuriEntity` 行为链最前，`EcologyPresentation` 为 `threat_*`、`rain_*` 配动作（看、颤抖、抖水）和表情（惊讶、含泪）。

## 4. 脏污与伤痕（外观标记）

- 状态：`Marks`（脏污 0–3、最多 3 处伤痕），保存在 `YukkuriStatus.Marks`。
- 来源：自由个体（非赤油）第一次被模拟时低概率获得：脏污 10%（其中七成一级）、伤痕 6%；无住所的流浪个体生活约 6 分钟脏污 +1（上限 2）；卫生值低于 45/30/15 时显示 1/2/3 级脏污；深伤（严重度 1 级）自然愈合完成时按种类 60%（裂口）/50%（焦痕）/25%（凹陷）/12%（擦伤）在同一皮面位置留下伤痕；橙汁清除全部伤口时不留伤痕。
- 去除：雨淋约 40 秒 −1 级；卫生一次回升≥10（清洗、水池）−1 级。伤痕不会消失（命令可清）。
- 同步：服务端把可见列表（脏斑 2×级数、伤痕）写入同步数据 `MARKS`，客户端 `WoundRenderer` 在已有皮面伤口系统上绘制（种类 `dirt`、`scar`，不参与凹陷变形，也不进入 `BodyLife` 的伤口计数）。位置避开脸和底面，脏斑位置由个体 UUID 决定，不会跳动。
- 美术：色板条 `art/props/body-marks/build.py` → `textures/props/body_marks.png`，登记在 `art/runtime-sources.json` 的 `body_marks`；`preview.py` 在真实皮面网格上生成布局预览。用户尚未确认标准（`pending_user_confirmation`）。

## 5. 橙汁粒子

`TreatmentItem` 橙汁成功恢复后调用 `entity.status().orangeRestored()`：服务端在个体周围发送 50 tick 的双螺旋橙色 `DUST` 粒子上升、顶部 `END_ROD` 闪光，起始有一团 `HAPPY_VILLAGER`；所有附近客户端可见。

## 6. 还没迁入中枢的字段与后续清单

登记现在是读取层，字段仍在原组件中（营养、体力在 `YukkuriLife`；伤口、感染在 `BodyLife`；成长、怀孕在 `FamilyLife`；住所在 `HabitatLife`/`YukkuriLife.home`/`HouseLife`；好感在 `YukkuriWorldData`）。不迁的原因是这些文件由其他代理同时改动。后续由各领域代理做：

- **wild（`settlement/`）**：提供“流浪”的权威标记后，改 `YukkuriStatus.identity` 里 `STRAY/WILD` 的一行推导；住所种类 `homeKind` 目前只区分 shelter、house_nest、nest、cover、hat_boat。若实现“被雨淋透的窝”，可订阅 `StatusChangedEvent`（键 `needs.wetness`）。
- **family**：`reproduction.*`、`growth.*` 读的是 `FamilyLife` 现有访问器；怀孕/茎生可见性改动后，保持这些访问器语义不变即可。伤口舔舐（`WoundComfort`）可读 `health.wound`。
- **aquatic**：帽船上（乘员）个体不参加威胁/天气反应（`isPassenger()` 排除），水上威胁继续由 `HatFamilyAlarm` 处理；如需让水上个体也进入统一仲裁，在 `Reactions.prey()` 放宽并提供各自的目标。
- **house**：家油被整体排除（`prey()` 里 `isHouse()`）；如需要家油对捕食种/天气反应，在 `Reactions` 中加分支或实现自己的 `Goal` 并读取 `Sky`。
- **human/pet**：`PetLife` 压力写入走 `addStress`；宠物遇捕食种退向主人已在 `Reactions.Threat.retreat` 实现。

接入方式：订阅事件（`StatusChangedEvent`、`RelationChangedEvent`），读取 `YukkuriStatus`，需要感知时用 `Sky.at`、`entity.status().reactions().threat().source()`，要加新的反应就在 `Reactions` 里加一个阶段或一个同级 `Goal`，不要在各包里再写一套对捕食种和天气的判断。

## 7. 无界面验证（runtime 为 PR 合并提交 ba225501）

- 捕食种感知：普通雷米（灵梦）与 NoAI 蕾米莉亚相距 10~14 格 → `threat_watch`（25 tick）→ `threat_retreat`（退到 15 格外）→ `threat_hide` → 看不见 80 tick 后恢复空闲；追猎中的蕾米莉亚（营养 10）使 14 格外的雷米 `threat:watch` 后在被追时转入既有惊慌，魔理沙被咬后出现真实伤口与漏馅。
- 攻击警告：`damage ... by zombie` 后 25 好感的亲友 20 格外进入 `threat_retreat`，无关的爱丽丝不动。
- 下雨：`weather rain` 后，6 格内有屋顶的灵梦与魔理沙跑进去并 `weather:wait`，湿度保持 0；无遮蔽处进入 `weather:huddle`（含泪、颤抖），雷暴时 `sky=storm`。
- 脏污/伤痕：41 只灵梦出生时 2 只脏污、3 只伤痕（约 5%/7%）；`health.treat orange` 通过真实的橙汁恢复，日志出现 `orange juice glow started`。
- 读取：`/yukkuri status <实体>`、`... raw`、`/data get entity <e> YukkuriStatusView`。
- 没有验证：客户端实际渲染（脏污、伤痕、橙汁粒子）没有在游戏里看过，只有布局预览（`art/props/body-marks/preview.py`）与服务端数据；粒子的视觉效果需要用户试玩确认。
