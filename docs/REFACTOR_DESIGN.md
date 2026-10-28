# 身体能力重构：总设计

> 底座、出生链与水生魔理沙 DLC 的重构总设计（用户要求入库），取代原《水生魔理沙 DLC 设计》。
>
> - **范围**：底座的身体、动作、行为框架与编排；出生链；水生魔理沙 DLC。其他模块按[迁移规则](#105-其他模块)顺带受益，不主动大迁移。
> - **验收对象**：[待办](TODO.md#水生魔理沙模块重构)中 E8–E11 登记的出生、水生、入窝问题，以及各工作包列出的展台与场景。
> - **规则来源**：各专题（[分娩与接生](CONSUMABLES_AND_BIRTH.md#分娩动作接生与水上分娩)、[水生魔理沙](AQUATIC_MARISA_IMPLEMENTATION.md)、[头饰](HEADWEAR.md)、[子代成长](CHILDHOOD_LIFECYCLE.md)）。本文只改实现方式；涉及规则的取舍列在[第十五部分](#第十五部分用户裁决与待确认)。
> - **进度**：只登记在待办。每个工作包合入后，稳定契约写回[代码架构](ARCHITECTURE.md)和所属专题，并删去本文中已成为现行规则的重复内容。

---

## 第零部分　执行协议（接手代理必读）

1. **一次只领一个工作包（WP，见[第十部分](#第十部分迁移路线与工作包)）**。开工前在待办“进行中”登记工作树 `.worktrees/<wp>` 和主要文件。核心文件同一时刻只允许一个 WP 修改：`YukkuriEntity`、`HatBoatEntity`、`motor/BodyDriver`、`motor/Attach`。
2. **按 WP 表的五栏执行：新增、复用、改接、删除、禁止**。
   - **复用栏优先**：已有实现能改接就改接，不另写。
   - **删除栏必须在同一 WP 内删掉**，不能“保留不用”。双路径只允许 WP 明写的过渡期，并在指定的后续 WP 中删除。
3. **不改规则**。行为结果须符合专题规则；发现冲突就停下，在回复里列出，交用户裁决。
4. **每个 WP 的交付物**：
   - 接入[日志前缀](#93-日志)；
   - 在 `scripts/museum_scenarios.py` 增加对应展台或场景按钮；
   - `scripts/check_architecture.py` 的基线只减不增；
   - 更新 ARCHITECTURE 的契约行；
   - 待办条目改为“已接入待试玩”，写明观察点。
5. **命中[评审红线](#第十一部分治理与评审红线)任何一条即退回**。代码评审由主代理按红线逐条核对。
6. **问题分诊顺序**：先在展台复现 → 修动作或能力 → 再看方法 → 再看行为或编排 → 最后看演出。修复只能落在问题所属的那一层。

---

## 第一部分　诊断：上一轮“统一”为什么没生效

10-26 已经做过一轮“唯一入口”重构（`CrewTransfer`、`HatDeck`、`HullHelm`、`HullFactory`、`WaterIncidents`、`HatCustody`），10-27 首轮试玩仍多处阻塞。问题不在于没有封装，而在于**封装的单位错了**。

| 病根 | 证据（审计于 6d33c130） | 试玩后果 |
|---|---|---|
| **同一事实存多份** | “在船上”同时由四份数据表达：原版骑乘、`HatDeck`（102 席位、6 种租约）、`CrewState`、`HatStations`，靠每 10／20 tick 对账同步。“孩子在帽里”由搬运者（乘员计数）、孩子（vehicle）、席位租约三方各判 | E11“放下又放回”：托举途中孩子仍是帽的乘客（`CrewTransfer:1476`），滑脱后 `fail` 把曲线倒放回原座（`:1601-1610`）；`BraidCarry` 又把 SLIP 记为 TRANSIENT 无限重试 |
| **能力被做成“事务”** | `CrewTransfer` 1710 行：CLAIM→APPROACH→CONTACT→MOVE→SETTLE→RELEASE 六个阶段加租约。HOP 实际是按步行限速沿曲线走（`YukkuriLocomotion:66`），赶不上就暂停曲线时钟 | 1.44 格的跳用了 25 tick；出发席位在 APPROACH 阶段被别处删掉，即报 `no_departure_seat` |
| **同一动作多套实现** | 跳／弧线至少 8 套，转身 4 套（瞬转、12°/tick、5°/tick、40 tick 预算）。新生儿一次飞行混用两种重力：`FamilyFlight` 自积分且无阻力，`BirthLaunch` 按原版求解。帽檐几何两套：`HullHazards.rimPoint` 带倾斜，`CrewTransfer` 不带 | 一边判“咬到帽檐”、一边判“够不着”，反复 `rim_out_of_reach` |
| **失败没有结构** | 几何拒绝被改记成 TRANSIENT（`CrewTransfers:94-97`），而 TRANSIENT 不计次。分娩一次失败会同时被 2–3 个预算计数，全链至少 7 套预算。阶段切换没有滞回 | 救援 `attempts=0`、亲船永久 SAFETY HOLD；RECOVER⇄ASHORE 每秒互切 |
| **一个身体多个写者** | 被动时钟写租约和舵（舵命令调用 127 处，分布在 14 个文件）；`selfRescue.stop` 会释放船长正在用的登船租约 | `seat_claim_lost`；`boarding_done` 刷屏 |
| **底座认识 DLC** | 底座包内约 500 处引用水生概念；仅 `LiveDelivery` 就有 11 处 `boatHome()!=null` 分支；`YukkuriLocomotion` 的签名直接带 `HatBoatEntity` | 修陆地牵动水生，修水生又牵动陆地 |

- **胶水比例（关键词密度加抽读的估算）**：水生 1.8 万行中约 35–40% 是预约、续约、回退和重试代码。座位与租约模型约 3100 行，全部是胶水。
- **结论**：
  - 封装的单位应是**动作**：有可行性检查、进度、保证成立的后置条件；而不是事务（票据加租约加阶段）。
  - 每个事实只存一份。动作结束时（包括失败和被打断）一律按物理事实落定。
  - 失败只用有限词表；一个意图只有一个预算。
  - 独有的演出放进**编排**，不进入动作。

---

## 第二部分　设计宪法

| # | 原则 | 含义 |
|---|---|---|
| P1 | 能力决定动作 | 能做什么、做成什么样，只由身体能力和真实环境决定 |
| P2 | 一个动作只写一次 | 走、跳、抛、游、爬、推、叼、搬、划、转身各只有一个实现，全部行为复用 |
| P3 | 行为只说目标 | 行为声明“要什么结果”，不决定怎么动 |
| P4 | 限制必须附带手段 | 新增拒绝原因的同时，至少有一个方法能缩小这个差额 |
| P5 | 世界是连续的 | 不查座位表、冲突矩阵；空间与拥挤由几何现场判定；“地名”只作目标 |
| P6 | 物理是唯一真相 | 动作的每个结束路径都按实际位置落定，绝不倒放、回座、瞬移或回滚 |
| P7 | 帽船是会漂的小陆地 | 帽船是“可站立宿主”。乘员用原版骑乘随船移动，帽内局部位置自由（[4.2](#42-attach依附身体在哪的唯一事实)） |
| P8 | 真实失败有后果 | 手段用尽才失败，失败留下现场并产生后果 |
| P9 | 扩展只经接口 | 底座不引用 DLC；DLC 实现底座接口 |
| P10 | 先验动作，再验行为 | 动作先在展台单独验收，行为出问题先定位是哪个动作 |
| P11 | 真实驱动，调欲望不写死（用户裁决） | 不同行为、不同个体之间的先后由欲望竞争产生；想要某种演出就调欲望强度或优先级 |
| P12 | 一个身体一个驱动者 | 同一时刻只有一个动作改它的位置、速度、朝向和依附；被动时钟只读 |
| P13 | 演出可以独有，动作不能独有 | 编排决定“何时、什么顺序、谁、播什么”，不决定“身体怎么动、算不算成功”。单个仪式内部由设定事实决定的顺序（如产程）可以编排，不违反 P11 |

---

## 第三部分　分层架构

```
L3 行为   Drive（选哪个意图）→ Intent（要什么结果）→ Method（有序手段）     Choreography（仪式编排）
                         │ 只经 BodyDriver 请求动作；不写身体
L2 动作   Walk Face Hop Launch Gesture Hold Await Swim Climb Grab Carry Release Push Right Paddle PlaceProp TakeProp
                         │ 只读 L1；只经 Attach 改依附
L1 身体   BodyAbility（能力表）· Attach（依附唯一事实）· SurfaceHost（可站宿主）· ContactGeometry（接触几何）
          · Container/Occupancy（容器判定）· Custody（物品归属）
                         │
L0 引擎   原版 move／碰撞／重力、骑乘、YukkuriGroundNavigation（PathFinder）

横切：Presentation（读 L2 姿态、L1 依附、编排节拍、情绪）· Trace（[intent][method][action][body][choreo]）
```

| 包（相对 Java 根） | 内容 | 允许依赖 |
|---|---|---|
| `motor/` | L1 与 L2：`BodyAbility`、`Attach`、`BodyDriver`、`Outcome`、`actions/*` | `entity/`、`life/` 导航、原版 |
| `motor/surface/` | `SurfaceHost`、`HostFrame`、`ContactGeometry`、`Container`、`Occupancy` | `motor/` |
| `intent/` | `Intent`、`Method`、`Attempts`、`IntentRunner`、`Drive`、`Choreography`、`Ask` | `motor/` |
| 领域包（`family/`、`life/`、`settlement/`…） | 具体的意图、方法、编排、后果规则 | `intent/`、`motor/` |
| `subspecies/`（DLC） | `HatBoatEntity` 实现 `SurfaceHost`；物种能力修正；水上需求与意图 | 全部；**反向禁止** |

- **DLC 边界**：除 `subspecies/`、`client/aquatic/` 和注册入口外，任何包不得 import `subspecies.*`。
- **检验**：关闭 DLC 注册后，底座和陆地玩法照常编译、运行。

---

## 第四部分　L1 身体层契约

### 4.1 BodyAbility（能力表）

| 能力 | 用途 | 推导（系数在展台定稿） |
|---|---|---|
| `hopHeight` / `hopReach` | 一跳最大抬升高度与最远水平距离 | 体型 × 阶段 × 体力 × 伤病 × 负重 |
| `stepUp` | 不跳、直接走上去的台阶高度 | 体型比例 |
| `walkSpeed` | 行走速度 | 体型 × 阶段 × 体力 × 负重 |
| `turnRate` | 每 tick 最大转角 | 体型 × 体力 |
| `swimSpeed` | 水中前进速度；底座为很弱的挣扎前进 | 体型 × 体力，物种可修正 |
| `climbRise` | 扒住边沿后能爬上的最大高差 | 体型 × 抓握 |
| `grip` | 在斜面、攀爬和扶帽时不打滑的能力 | 体力 × 伤病 |
| `reach` | 嘴或辫梢能够到的距离 | 体型；魔理沙的辫子另加 |
| `pushForce` | 推物的力 | 体型 × 体力 |
| `canCarry(other)` | 能否叼起或托起 | 不设质量数值：被搬者体型小于搬运者即可；越大越慢、越易滑落 |

- **成体基准（用户裁决 Q1）**：最高跳 0.8 格，最远 1.0 格；子油、赤油按比例缩小。
- **单一来源**：所有动作和方法只读这张表。第一版系数从 `YukkuriLocomotion.limits` 的 S、F、L 公式迁入。
- **数据驱动**：系数放在数据包 `body_ability.json`，注册 reload 监听，`/reload` 即时生效；`[action]` 日志带参数文件的短指纹。
- **物种修正**：经 `AbilityModifier` 接口接入，只能乘系数或增加能力项。DLC 也走这个口子。

### 4.2 Attach（依附：身体在哪的唯一事实）

| 状态 | 含义 | 引擎表示 |
|---|---|---|
| `FREE` | 在地面或水中，交给原版物理 | 无 vehicle |
| `AIRBORNE` | 被抛出或起跳、尚未接触 | 无 vehicle，`Launch`／`Hop` 动作在跑 |
| `ON_HOST(host, local, yaw)` | 站在可站宿主上（帽船、接生帽） | 原版乘客；`host.positionRider` 按 `local` 放置 |
| `HELD(carrier, grip)` | 被辫子或嘴托着 | 原版乘客；沿用 `ParentCarry` 的骑乘承托 |

- **唯一写入口**：
  - `Attach.mount(body, host, local)`
  - `Attach.dismount(body, worldPos, velocity)`
  - `Attach.moveLocal(body, local)`
  - `Attach.settle(body)`：按实际支撑和位置推导状态

  只有 L2 动作可以调用（结构守卫检查）。`startRiding`、`stopRiding`、`setPos` 及速度写入同理。
- **存储**：`local` 存在宿主上（乘员 UUID → 局部坐标与朝向），随宿主存档，替代 `Deck`。按 AGENTS 约定，当前试玩场的数据结构调整不做旧存档迁移。
- **只读判定**：“在不在帽里” = `Attach.of(child)` 为 `ON_HOST(该帽)`，且 `Occupancy` 为 `INSIDE_SUPPORTED`。禁止再用乘员计数、租约或席位号判断。
- **不预约**：没有预约，没有租约。空间够不够由 `SurfaceHost.room` 现场判定；两个身体同时奔向同一空位时，后到者被几何拒绝，由方法换落点。
- **为什么保留原版骑乘、不做平台携带**：
  - 原版骑乘自带同帧同步、客户端插值、随载体存档和区块处理；
  - 全仓约 460 处调用依赖它；
  - `ParentCarry` 已用它承托孩子；
  - 原设计要求的“帽 ID 加局部坐标同步”就是骑乘加局部偏移，不需要重写实体物理。

### 4.3 SurfaceHost（可站宿主）

接口由 `HatBoatEntity` 实现，接生帽和帽船是同一个实体。

| 方法 | 契约 |
|---|---|
| `frame()` | 宿主位姿（含倾斜）：`toWorld`／`toLocal` 只能从这里取 |
| `support(local, radius)` | 帽面高度场：足迹下的可站高度，或“无支撑”。来源是 `HatVessel.travelSupport` 加模型导出的高度场 |
| `room(body, local)` | 现场几何判定：脚圆与其他 `ON_HOST` 身体、储粮是否重叠。不查表 |
| `rim()` | 帽檐接触点（已含倾斜），供 `ContactGeometry` 使用 |
| `floor()` | 帽腔内底多边形，供 `Occupancy` 使用 |
| `spots()` | 地名（船长位、育儿窝、如厕处、储粮侧），只作目标与朝向提示 |

- **拥挤**：帽内身体是脚圆，Walk 在局部坐标里互相分离。没有空间就沿坡滑向帽檐，越过帽檐即 `dismount` 并交给物理落水（用户裁决 Q6）。
- **倾斜**：坡度超过 `grip` 时 `Hold` 失败，身体滑动；滑出帽檐即落水。

### 4.4 ContactGeometry（接触几何）

所有“够得着、咬得住、托得起”的判定只问这里，包括：
- 嘴和辫梢的世界位置与可达范围（读 `BodyAbility.reach`）；
- 宿主帽檐点（含倾斜）；
- 身体包络。

合并 `HullHazards.rimPoint/bites`、`CrewTransfer` 的帽檐与 `holds` 判定、`BraidRescueGeometry`、`CatchHat` 的接触点。客户端 `ContactTargets` 读同一份同步数据。

### 4.5 Container 与 Occupancy（容器判定）

- **`Container`**：内底多边形、内底高度、边沿高度。实现者：
  - 宠物窝、野窝、屋内窝（经 `NestResting.Bed`）；
  - 洞穴、纸箱；
  - 接生帽和帽船（经 `SurfaceHost.floor`）。
- **`Occupancy.of(body, container)`**：比较身体足迹（半径来自体型）与内底多边形及支撑高度，返回四种结果之一：

  | 结果 | 含义 |
  |---|---|
  | `INSIDE_SUPPORTED` | 在里面且站稳 |
  | `ON_RIM` | 在边沿上 |
  | `PARTIAL` | 只进去一部分 |
  | `OUTSIDE` | 在外面 |

- **使用者**：入睡、保暖避险（`TemperatureLife`）、认窝、到窝、孩子在帽里、新生儿落点结算。取代现有约 9 处各写一套的判定。只有 `INSIDE_SUPPORTED` 算“到了”，站在边沿上会继续往里走（修 E11 第 2 条）。

### 4.6 Custody（物品归属）

- **帽子**：`HatCustody` 保留为唯一记录，形态有 `WORN`、`DEPLOYED`、`HELD_BY_PARENT`、`ADRIFT`、`LOST`。转换只由 `PlaceProp`、`TakeProp`、物理事件触发。
- **嘴和辫梢上的物品**（木棍、食物、遗落帽）：每个身体有一个 `Held` 槽位。`Paddle` 同时认叼着的木棍和帽内储物里的木棍（修“有木棍不划船”）。
- **归属变化**：发出需求信号。例如帽子不在认可形态时，主人持续产生 `ResolveOwnHat`。

---

## 第五部分　L2 动作层契约

### 5.1 统一接口

```java
public interface Action {
    String id();                                   // 进入 Trace 与 Presentation；ID 登记 Behaviors
    Feasibility check(YukkuriEntity body);         // 纯函数：可行，或 Blocked(原因, 差额)
    void start(YukkuriEntity body);
    Progress tick(YukkuriEntity body);             // Running(进度度量) 或一个 Outcome
    boolean interruptible();                       // 腾空等不可中断段返回 false
    void abort(YukkuriEntity body, Interrupt why); // 保持物理现状并 Attach.settle，绝不回退
    MotionPose pose();                             // 片段 ID 与相位，供演出
}
public sealed interface Outcome permits Done, Blocked, Incapable, Interrupted, Lost {}
public record Blocked(Reason reason, double shortfall, String detail) implements Outcome {}
```

- **`Reason` 是有限枚举**，初始集合：`TOO_HIGH`、`TOO_FAR`、`NO_PATH`、`NO_SUPPORT`、`NO_ROOM`、`NO_CONTACT`、`SLIPPED`、`DROPPED`、`TARGET_MOVED`、`TARGET_GONE`、`TOO_HEAVY`、`NO_TOOL`、`EXHAUSTED`、`NO_PROGRESS`。
- **分类固定**：
  - 由原因决定，调用方不得改记；
  - 几何、当下条件 → `Blocked`，带差额；
  - 体力、体型能力 → `Incapable`；
  - 目标消失 → `Lost`；
  - 更高意图抢占 → `Interrupted`。
- **后置条件**：每个结束路径都调用 `Attach.settle(body)`。落在宿主支撑面上就是 `ON_HOST`（实际局部坐标），否则是 `FREE`（实际位置）。绝不恢复原座位。
- **进度度量**：每个动作报告一个可度量的进度（剩余距离、高差、角度），供看门狗使用。

### 5.2 BodyDriver（每个身体唯一的驱动者）

- **接口**：`run(owner, action)` 返回是否接受；`current()`；`last()` 返回最近一次的 `Outcome` 和 tick。
- **一次一个动作**：同一身体同一时刻只运行一个动作。新请求只在可中断接缝处替换旧动作；被替换的动作执行 `abort`。
- **所有者**：持有原版 MOVE 旗标的那个运行中 Goal，包括在 Goal 内运行的意图或编排角色。不是所有者的请求被拒绝，并记 `[action] refused owner=`。
- **被动时钟只读**：被动 tick（产程时钟、水伤、饥饿等）不得调用 `run`，也不得写身体、租约、舵或他者状态，只能产生需求与事件。

### 5.3 动作目录

| 动作 | 可行性（读 L1） | 执行 | 复用 | 取代／删除 |
|---|---|---|---|---|
| **Walk(目标)** | 有路径；台阶 ≤ `stepUp` | 地面走 `YukkuriGroundNavigation`；宿主上在 `support` 高度场的局部网格里走 | `SurfaceApproach.walk/walkAround/lastResult`；`CrewTransfer.boardingApproach` 的帽内网格（720-755） | `CatchHat.seekMother`、`tickMidwife`、`tickFetch` 自行导航；`HatDeck` 步行边 |
| **Face(朝向)** | 无 | 每 tick 至多转 `turnRate` | `SurfaceApproach.face` | `FamilyLife.face` 瞬转、`recoveryBeats` 5°、`advanceLiveTick` 40 tick 预算 |
| **Hop(落点)** | 在能力内求**最低**可行弧线；差额给出 `TOO_HIGH` 或 `TOO_FAR` | 一次起跳冲量，之后交给原版重力；接触时 `Attach.settle`，落在宿主上就挂上 | `BirthLaunch` 的原版物理求解（.08/.98/.91） | `HatVessel.arc/solve`、`CrewTransfer` HOP 曲线、`YukkuriLocomotion.hop` 与采样推进 |
| **Launch(冲量)** | 冲量在调用方预算内；`preview` 做逐 tick 线段扫掠 | 被动弹道：原版物理加逐 tick 扫掠，首次接触时落定 | `BirthFlightPath` 的扫掠、`FamilyFlight.catchFalling` 的接触结算 | `FamilyFlight` 自积分器与 `pending*`、`launchPast`、`HullHazards` 抛出、`HatBoatEntity` 挤落冲量 |
| **Gesture(种类)** | 位移有界；要求能力允许（如 `canJump`） | 小跳、挤扁弹起、蹭脸前倾、窝沿滚动 | — | `LiveDelivery:609/664`、`FamilyLife:530-533` 手写速度 |
| **Hold / Await** | 坡度 ≤ `grip`；或等待条件成立 | 站稳；等待有上限、有信号 | — | 各处 SAFETY HOLD 与无限等待 |
| **Swim(目标)** | 在水中，有体力 | 单一速度模型限速推进 | `WaterDistress` 的速度式推进 | `AquaticSelfRescue`、`HullHazards`、`WaterIncidents` 的步进推进 |
| **Climb(帽檐)** | `ContactGeometry` 给出的帽檐在 `climbRise` 内且抓得住 | 分段上拉，时长不短于片段 | — | `CrewTransfer` CLIMB、自救咬檐攀爬 |
| **Grab / Carry / Release** | 目标在 `reach` 内且 `canCarry`；落点可支撑 | Grab 把目标挂为 `HELD`；Carry 时搬运者自己 Walk 或 Swim；Release 在实际位置 `dismount`，之后交给物理 | `ParentCarry` 的座位、嘴道与骑乘 | `CrewTransfer` LIFT、`BraidCarry` 会话、`CrewTransfers.releaseYoung/liftAcross`、`CatchHat.tickFetch` 循环 |
| **Push / Right** | 有接触；`pushForce` 足够 | 对实体施力；Right = 绕帽檐施加力矩的 Push | — | 自救扶正、亲油扶正（`AquaticFamilyLife:617-620`） |
| **Paddle(方向)** | 站在帽船上，并有木棍（叼着的或储物里的） | 把推力和转矩交给宿主；`HullHelm` 只做运动积分 | `HullHelm` 的运动积分部分 | `HullHelm` 租约与 127 处舵命令 |
| **PlaceProp / TakeProp** | 托管允许；落点放得下 | 播 `hat_off`／`hat_on` 片段；改变托管形态 | `HullFactory.setDown`（一次预检、统一回滚）、`HatCustody` | `CatchHat.beginHat/tickHat`、部署与收帽的各自实现 |

---

## 第六部分　L3 行为层

### 6.1 Intent 与 Method（有序手段，不做通用搜索规划器）

- **Intent**：一个对物理事实的成功判定，加上来源、紧迫度和截止期限。

  | 意图 | 成功条件 |
  |---|---|
  | `GetTo(place)` | 身体在目标处站稳；如果目标在容器里，要求 `INSIDE_SUPPORTED` |
  | `GetOnto(host)` | `Attach` 为 `ON_HOST(host)` 且站稳 |
  | `Fetch(obj, place)` | 物体实际在目标处或容器里 |
  | `Reach(target)` | 嘴或辫梢实际接触到目标 |
  | `Right(hull)` | 帽口朝上并浮着 |
  | `Rest(container)` | `INSIDE_SUPPORTED` 并进入休息 |
  | `ResolveOwnHat` | 帽子 `WORN`，或在认可的 `DEPLOYED` 形态 |

- **Method**：适用条件，加有序步骤。步骤可以是 Action、Await、子意图，或 `Ask(帮手, 意图)`。
  - 每个方法声明自己能缩小哪些 `Reason`（`reduces`）。
  - 方法顺序写在数据里，可调。
- **IntentRunner**：
  - 选第一个适用的方法执行；
  - 遇到 `Blocked` 时，优先换一个能缩小该原因的方法；
  - 看门狗：连续 N 次评估进度没有改善，记 `NO_PROGRESS`。
- **Attempts**：每个意图实例只有一个预算对象，含最多切换次数和截止期限。
  - 失败过的方法，在“世界变化信号”出现前不得再选。信号包括：目标移动超过 0.5 格、宿主移动超过 0.5 格、阻挡者离开、冷却到期。
  - 预算用尽时交给领域的后果规则：哭、放弃、呼救、溺水等。
- **示例：`GetOnto(帽船)` 的方法表**（顺序可调）：
  1. 在 `hopReach` 内就 Hop；
  2. Walk 到更近的岸边或帽檐再 Hop；
  3. 宿主正在靠近（有人划船或漂近）时 Await；
  4. Ask 亲油 Carry；
  5. 下水 Swim，再 Climb。
- **Ask**：帮手对每个（帮手, 事件）只决定一次，是否帮由关系和性格的概率决定（推广现有 `WaterIncidents` 的一次性友善选择）。答应后帮手执行自己的意图；拒绝、力竭和危险仍有后果。

### 6.2 Drive（选哪个意图）

- **领域内**：意图得分 = 欲望强度 × 权重（数据可调）。切换带滞回：得分须超过当前意图一定幅度，并持续若干 tick。`[intent]` 日志记录前三名的得分。
- **领域间**：暂时沿用原版 Goal 的优先级仲裁（现有 52 个 Goal）。
  - 每个领域只注册**一个** Goal，同一套逻辑不得注册在两个优先级上。
  - 旧稿中“顶层 DriveGoal”的方案推迟到至少三个领域迁移完成后再评估。
- **承诺**：正在执行的方法或编排带承诺强度。只有危险类欲望（恐惧、溺水、受伤）能抢占，且只在可中断接缝处抢占。
- **硬规则只留给设定事实**：生殖只在岸上、水生只与水生配对、水伤数值、产程顺序等。不同行为之间“谁先谁后”一律由欲望竞争产生（P11）。

### 6.3 Choreography（编排：独有演出的唯一归宿）

| 组成 | 契约 |
|---|---|
| **Roles** | 角色在开始时绑定一次，每个节拍前复核；角色失效时进入编排声明的分支 |
| **Stations** | 由几何算出的站位，只作为 Walk／Face 的目标 |
| **Beats** | 四种节拍：<br>`Perform(角色, 意图或动作)`<br>`Present(角色, 片段, 表情, 声音, tick)`<br>`Await(条件, 期限, 超时分支)`<br>`Branch(结果 → 节拍)` |
| **期限** | 每个 Await 一个期限。重试只经该角色意图的 Attempts，编排不另设预算 |
| **结果即内容** | 失败结果是剧情分支，例如没接住 → 摔伤，落水 → 落水事件，不当作错误重试 |
| **存档** | 只保存领域规则要求的部分（如胎次、已抽定的冲量、已出生数，仍在 `FamilyData`）；其余重载后按事实重新进入 |
| **禁止** | 写身体；自己判定“在不在、到没到”；自设重试；跨个体排定“谁先谁后” |

---

## 第七部分　出生链：一个编排，三种接收者

### 7.1 LiveBirth 编排

- **角色**：
  - 母油；
  - 接生者（可缺）：父亲是戴帽魔理沙时由父亲接；否则母油是魔理沙时自己接；螺壳魔理沙只在旁打气；
  - 新生儿；
  - 在场的兄姐。
- **接收者 `Receiver`**：放下的接生帽（`SurfaceHost`）、自己的帽船（`SurfaceHost`）、窝（`Container`）或地面。
  - 每只新生儿由 `BirthExit` 只读选择一次，发射前复核。
  - 底座代码中不再出现 `boatHome()!=null` 一类分支，差异全部由接收者类型表达。

| # | 节拍 | 实现 |
|---|---|---|
| 1 | 产地 | `placeForTerm` 仍是唯一判定；母油执行 `GetTo(产地)`：窝、自己的浮船、岸边或稳固地面 |
| 2 | 会合（陆地且有接生者） | 共享方法 `Rendezvous(母油, 接生者, 选点器)`：接生者用现有 `catchPose/stand` 几何提议站位，双方各自 `GetTo` + `Face`，只有一个期限。超时进入“无接生者”分支：照常分娩，新生儿可能摔伤 |
| 3 | 放接收者 | 接生者执行 `PlaceProp(帽 → 站位)`（`hat_off`／`hat_off_self`）；帽船分娩跳过此步 |
| 4 | 产程 | `Present`：产程时钟、片段锁相、`LiveBirthGeometry`、`BirthStaging` 的声音与粒子，原样保留 |
| 5 | 选口转身 | `BirthExit.choose`（只读）→ 母油执行 `Face(出口朝向)` |
| 6 | 出生 | `FamilyData.birth` 生成实体 → `Launch(新生儿, 冲量)`。冲量按 `BirthLaunch` 的分布在产程开始时抽定并存档（重载不重抽）。首次接触后 `Attach.settle`：<br>落在帽上 → `ON_HOST`；<br>落进窝 → `FREE` 且 `INSIDE_SUPPORTED`；<br>落在地面 → 摔伤规则；<br>落进水 → 落水事件 |
| 7 | 初醒与迎接 | `Present`（`first_wake`、问候）加兄姐 `Gesture(小跳)`，小跳受能力门槛约束 |
| 8 | 产后 | `Present`（蹭脸、短舔），转身和靠近用共享的 Face／Walk |
| 9 | 清空接收者（陆地接生帽） | 接生者执行 `Unload(帽)`，逐只处理：<br>会跳的孩子自己 `GetTo(地面)`；<br>不会跳的执行 `Fetch(孩子 → 地面落点)`，即 Grab、Carry、Release。<br>Release 按实际位置落定。够不到时，孩子先沿帽内路径 Walk 到靠接生者一侧的边缘（用户设计），再 Grab |
| 10 | 收回 | 帽的 `Occupancy` 为空后，接生者执行 `TakeProp(帽)`（`hat_on`）。帽主死亡时由存活亲本执行 Fetch，不继承帽子 |

- **帽船分娩**：接收者是自己的帽船，省去 2、3、9、10 四步。产后分孩子是意图 `Fetch(孩子 → 父船)`，方法有：Await 两船靠近、Ask 父油划近、Carry 越船。用力过猛的 12% 由冲量抽签产生越檐落水，这是剧情内容，不是错误。
- **茎生**：同一编排，换发射源（实子脱落）和站位规则（`CatchHat.stemClearance`）。

### 7.2 保留的独有演出

- 产程时钟与片段锁相、孕腹形变与出口锚点（`LiveBirthGeometry`）；
- 帽子翻过来当容器的姿态（`catchPose/stand`）与 `hat_off`／`hat_on` 片段；
- 冲量分布（`BirthLaunch`）；
- 阵痛、问候、产后、摔伤哭泣的节拍与声音粒子。

以上作为编排数据和演出保留在 `family/`。

### 7.3 删除

| 文件 | 删除内容 |
|---|---|
| `FamilyFlight` | 自积分器、`pending*`、`restoredFlight`、`cancelFlightAdmission`、`launchPast` |
| `CatchHat` | 会合协商（287-410）、站位桥接（412-466）、摘放帽逐 tick 复验（200-275）、`tickFetch` 循环、`seekMother` 自导航 |
| `LiveDelivery` | 接生窗口与出窝握手（219-263、390-431）、`boatHome()` 分支、`approachWaterPartner`（改为 Paddle 方法）、`partnerApproachClear` 自写扫掠 |
| `FamilyData` | 出生预约（`reserveBirthAdmission`、`waterCradle`、`prepareWaterBirthCargoSpace`）；四条发射分支合为一条 |
| `FamilyLife` | `face`；BROOD Goal 的 12 个分支改由编排执行器承担；`advanceStem` 重复的门控 |
| 各处预算 | `exitWait`、`stationWait`、`catchWindowBudget`、拒绝缓存、`siteStalled`、取帽 600+100、`releaseWait`、`cradleLinger`，改为 Attempts 与 Await 期限 |

---

## 第八部分　水生 DLC

### 8.1 DLC 只做四件事

| 类别 | 内容 | 接入点 |
|---|---|---|
| 能力修正 | 水生种的 `swimSpeed`、水中耐受、辫子托举（漂浮不等于会游，不能潜水） | `AbilityModifier` |
| 地形与物品 | 帽子四形态；帽船是 `SurfaceHost` 与 `Container` | `SurfaceHost` 实现、`HatCustody` 形态 |
| 需求与意图 | 想在水上生活、要上岸、夜泊、补给、初航、找回帽子 | 领域 Goal 内的 Drive 注册 |
| 规则与演出 | 水上家庭规则、落水后果、帽船片段 | 后果规则、演出片段 |

### 8.2 帽子：一个物品，四种形态

| 形态 | 物理身份 | 转换（都由动作完成） |
|---|---|---|
| 戴着 | 身体的一部分 | `PlaceProp`：辫子摘下，变为“托着”，或直接放到水面或地面 |
| 托着／叼着 | `Held` 物品 | 放到水面即展开；放到地上就是地上的物品 |
| 展开（帽船） | `SurfaceHost` 与 `Container` | `TakeProp`：扶起并戴上 |
| 翻覆 | 浮动物体，可推、可扶 | `Right` 扶正后回到展开 |

- **尺寸统一**（用户裁决 Q3）：只有一个尺寸 `HatScale.of(owner)`，四种形态只改姿态不改大小。建议从约 1.4 倍起试，改模型前先出三图同屏预览，由用户确认。幼体帽按自身体型同比缩放，初航不换帽。
- **找回帽子**：帽子不在认可形态时，主人持续产生 `ResolveOwnHat`。手段全部来自动作：游过去、推回来、扶正、托起戴上、Ask 亲油代取。

### 8.3 帽船：会漂的小陆地

| 方面 | 设计 |
|---|---|
| 站立与行走 | `ON_HOST` 加局部 Walk；帽内只是高低不同的地面 |
| 上下船 | 没有专门流程。岸上走到岸边 Hop，落在帽面即挂上；水中 Swim 到帽檐再 Climb；下船 Hop 或滑落 |
| 随船移动 | 原版骑乘；宿主先移动，乘员按局部坐标跟随，服务端与客户端同序 |
| 倾斜与滑落 | 坡度对帽内身体施力，`grip` 抵抗；抓不住就滑动，越过帽檐即落水 |
| 浮力 | 任何形态下都执行，不因被托或在扶正而清零速度；翻覆时帽内空气让空帽浮起；进水量决定下沉 |
| 被推 | `isPushable`，按质量比被推动，可被推上岸 |
| 与水中身体 | 帽外几个盒子与水中身体碰撞并分离，不再重叠 |
| 翻覆 | `HullHazards` 只保留外因判定（摇晃、撞击、超载加水流、浸水）；甩出乘员 = `dismount` + `Launch` |
| 容量 | 由几何与浮力自然决定：挤不进就进不去，太重就吃水深、更易翻 |
| 储粮 | 粮袋是帽腔里的真实物体，参与 `room`、重量与重心；放置位置按现有造型规则 |

### 8.4 水上生活：由意图驱动，取代船长阶段机

| 意图 | 产生条件 | 方法（技能组合） |
|---|---|---|
| **出航** `LiveAfloat` | 水生种常驻偏好；下雨、夜晚、缺粮时降低 | 备齐木棍和粮（Fetch）→ 到岸边（GetTo）→ `PlaceProp` 放帽到水面 → 带孩子 `GetOnto` 或 Carry → 自己 `GetOnto` |
| **上岸** `GoAshore` | 缺粮且水上找不到、要繁殖、要找木棍、天气恶劣 | Paddle 靠岸 → 孩子各自上岸或被 Carry → 自己上岸 → `TakeProp` 收帽 |
| **夜泊** | 夜里或雨雪 | 选背风岸凹 → Paddle 过去卡住加 Hold → 孩子 `GetTo(育儿窝)` → 俯身盖住（Present） |
| **补给** | 饥饿、储粮不足（冬季提高） | Paddle + Grab 捞漂浮食物，或上岸采集 |
| **照料** | 孩子饥饿、不安 | 帽内 Walk 到孩子身边，共用陆地照料逻辑 |
| **找回帽子** `ResolveOwnHat` | 托管不在认可形态 | 见 8.2 |

- **承诺段**：出航从“帽已放在水面”到“自己上船”、上岸从“靠岸”到“收帽”都是短的承诺段，只有危险能抢占，从而消除 `berth_lost`、RECOVER⇄ASHORE 这类来回切换。
- **船长不是一种模式**：谁在划船谁就是船长。“先载幼体、船长最后上船”不做硬规定（用户裁决 Q4），由护幼欲望驱动；无桨只能漂是物理事实。
- **原阶段机中的业务约束改为欲望输入或设定事实**：雨天不出航、生殖只在岸上、水生只与水生配对、岸上活动增加体力负担。

### 8.5 家庭、分娩与初航

| 内容 | 设计 |
|---|---|
| 帽船分娩 | LiveBirth 编排，接收者为自己的帽船（7.1） |
| 新生儿安置 | 新生儿就是落在帽腔里的身体，不分配座位；挤不下会滚到别处，甚至落水 |
| 送赤油到父船 | `Fetch(赤油 → 父船)`；够不到时 Await 靠近，或 Ask 父油划近 |
| 喂食与安抚 | 帽内 Walk 到孩子身边接触，与陆地共用照料逻辑 |
| 初航 | 学习需求：子油达到体型和能力门槛后，与亲船一同出航练习。由 `PlaceProp` → `GetOnto` → Paddle 一段 → 返回 → `TakeProp` 组成，完成才记为学会 |
| 子油搭亲船 | 子油只是 `ON_HOST` 在亲船上，自己的帽子仍戴在头上 |

### 8.6 落水、救援与翻覆

| 事件 | 设计 |
|---|---|
| 落水 | 身体实际入水时，`WaterIncidents` 开一个事件。保留事件记录与一次性友善选择，去掉其中的移动代码；本人立即得到 `GetOnto(帽船)` 或 `GetTo(岸)` 意图 |
| 自救 | 方法依次为：Swim → Climb；换帽檐点；帽子翻了先 `Right`；体力耗尽就漂向岸，仍可能溺亡。打滑次数计入同一个 Attempts |
| 他救 | 救助者执行 `Fetch(落水者 → 帽腔或岸)`，方法依次为：<br>① 帽内 Walk 到最近帽檐点再 Grab；<br>② Paddle 靠近后再 ①；<br>③ 下水 Push 向帽；<br>④ Ask 其他亲属。<br>几何失败不得改记为暂时失败 |
| 收尾 | 事件只在落水者真实 `ON_HOST` 受保护、上岸或死亡时关闭；托举途中不提前关闭 |
| 翻覆后的帽子 | 浮在水面，可推、可扶；由主人的 `ResolveOwnHat` 收回 |
| 水伤 | 沿用现有数值：普通个体每 20 tick 扣 1 点，水生种每 60 tick 扣 1 点 |

### 8.7 与其他系统的交界

| 交界 | 设计 |
|---|---|
| 捕食者 | 落地走 `GetTo(干地)`：没有干燥落点就继续飞向岸边 |
| 玩家交互 | 保留 `HatHitResolver`：点到身体交给身体，点到帽面交给帽子 |
| 天气与温度 | 作为欲望输入，不作为门槛 |
| 聚落与公仓 | 水边聚落把帽船当作可停泊的地形；公仓存取走 Fetch |

---

## 第九部分　横切关注点

### 9.1 演出

- **数据来源**：`Action.pose()` 与编排的 `Present` 节拍，经 `PresentationArbiter` 发布。动画是运动的结果，永远与实际动作一致。
- **表情优先级只有一处**：操作姿态 > 反应 > 伤病或哭泣 > 情绪 > 兜底。
- **帽上姿态**：按 `Attach` 的局部位置、帽面倾斜和地名选取，`CrewPoseTable` 改为按地名或支撑取姿态。
- **客户端只播放**：只读 `YukkuriView`／`HatBoatView`，不推导。领域迁移后，删除从行为 ID 推导动作的旧路径。

### 9.2 存档与同步

| 数据 | 处理 |
|---|---|
| `Attach` 局部坐标 | 存在宿主上，替代 `Deck`；乘员随原版乘客存档一起恢复 |
| 动作 | 瞬态，不存档。重载时按事实 `Attach.settle`，意图重新评估 |
| 编排 | 只保存规则要求的部分（胎次、已抽定冲量、已出生数），仍在 `FamilyData` |
| 同步 | 依附状态、当前动作 ID 与姿态相位进同步槽；客户端不读服务端组件 |

### 9.3 日志

| 前缀 | 内容 | 频率 |
|---|---|---|
| `[intent]` | 开始、完成、放弃；来源欲望、前三名得分、成功条件 | 每次 INFO |
| `[method]` | 选中的方法、换方法的原因（原因加差额）、看门狗触发、预算用尽时的原因清单 | 选择与切换 INFO |
| `[action]` | 开始与结束、能力值、目标、差额、实际峰高或速度、参数指纹 | 开始与结束 INFO；逐 tick 只在详尽开关下 |
| `[body]` | 依附变化（挂上、卸下、落水、滑落、被推） | 事件 INFO，按实体限频 |
| `[choreo]` | 编排开始、节拍切换、分支、结束 | 每次 INFO |
| `[headwear]` | 帽子形态转换、`ResolveOwnHat` 开始、结束、过期 | 事件 INFO |

通用规则（统一入口、实体身份、限频、不转储 NBT）沿用 [AGENTS](../AGENTS.md#日志留痕) 与[玩法日志](GAMEPLAY_LOGGING.md)。

### 9.4 读数、调试与性能

- **读数**：`/yukkuri status <目标> raw` 增加 `body` 页，显示能力值、依附、当前动作、最近结果（原因加差额）、当前意图与方法、预算余量。
- **性能**：
  - 意图评估每 10 tick 一次，按实体 ID 错开；
  - 可行性结果在同一 tick 内缓存；
  - 寻路计入 `SearchBudget`；
  - 高度场查询为常数时间。

---

## 第十部分　迁移路线与工作包

迁移采用“绞杀”方式：新层在旧代码旁建起，一次迁一条纵向链路，迁完的同一 WP 内删除旧路径。每个 WP 结束时，`check_architecture` 的未迁移清单必须变短。

### 10.1 依赖与并行

```
WP0 护栏 ─→ WP1 身体核心 ─→ WP2 容器·宿主·搬运 ─→ WP3 意图框架＋LiveBirth ─→ WP6 水上意图
                                     └────────→ WP4 帽船宿主化 ─→ WP5 水中动作 ─┘
离线线（可与 WP1 并行，不碰运行代码）：帽面高度场导出与预览、HatScale 三图预览
WP7 收口：全部完成后
```

### 10.2 工作包

**WP0　护栏**

| 栏 | 内容 |
|---|---|
| 新增 | `check_architecture` 规则：<br>`BODY_WRITE`：`motor/` 外写位置、速度、朝向、骑乘；<br>`BASE_IMPORTS_DLC`：非 DLC 包 import `subspecies.*`。<br>两条规则都记录当前基线清单。另加 `GameplayLog` 的 `[intent]`、`[method]`、`[action]`、`[choreo]` 前缀，以及 status 的 `body` 页骨架 |
| 删除 | 无 |
| 验收 | 守卫能列出两份基线清单；玩法无变化 |

**WP1　身体核心（陆地）**

| 栏 | 内容 |
|---|---|
| 新增 | `motor/`：`BodyAbility`（含 json 与 reload）、`Attach`（FREE／AIRBORNE／HELD，ON_HOST 留接口）、`BodyDriver`、`Outcome`／`Reason`、动作 Walk（地面）、Face、Hop（地面）、Launch、Gesture、Hold、Await |
| 复用 | `SurfaceApproach`、`YukkuriGroundNavigation`、`BirthLaunch` 求解、`BirthFlightPath` 扫掠、`YukkuriLocomotion.limits` 系数 |
| 改接 | 新生儿飞行 → Launch；迎接小跳、摔伤弹起、窝沿滚动 → Gesture／Launch；`FamilyLife.face` 等四套转身 → Face |
| 删除 | `FamilyFlight` 自积分器与 `pending*`；`FamilyLife.face`；`recoveryBeats` 自转身；`YukkuriLocomotion` 中对 `HatBoatEntity` 的依赖（移到 DLC 适配） |
| 禁止 | 新增任何预约或租约；改变冲量分布或片段时长 |
| 展台 | 新建“身体能力”厅：<br>A 台阶：赤油、子油、成体分别面对 0.1、0.25、0.5、1.0 格；<br>B 跨缝：0.3、0.6、1.0 格；<br>C 转身；<br>D 同一冲量连发 5 次，落点一致 |
| 场景 | `birth_land_missed`：摔伤路径不变；`birth_land_caught`：落帽观感不退步 |

**WP2　容器、宿主与搬运**

| 栏 | 内容 |
|---|---|
| 新增 | `Container`／`Occupancy`；`SurfaceHost` 接口与 `HatBoatEntity` 实现（先用于接生帽）；`Attach.ON_HOST` 局部坐标（`positionRider` 新路径）；`ContactGeometry`；Grab／Carry／Release；宿主上的 Walk |
| 复用 | `ParentCarry`（座位、嘴道、骑乘）、`HatVessel.travelSupport`、`CrewTransfer.boardingApproach` 网格、`NestResting.choose` |
| 改接 | `NestResting`／`TemperatureLife`／`LifeHome` 的“在窝里”→ Occupancy；`CatchHat` 收尾 → `Unload` 意图；所有“孩子在帽里”判定 → `Attach` + `Occupancy` |
| 删除 | 陆地的 `CrewTransfer` LIFT 路径与倒放回座（1601-1610）；陆地 `BraidCarry` 会话；`CatchHat.tickFetch` 循环；`CrewTransfers.releaseYoung`；约 9 处容器判定 |
| 过渡 | `positionRider` 双路径：有 `Attach` 局部坐标的走新路径；水上座位路径保留到 WP4 删除 |
| 展台 | 入窝（宠物窝、野窝、接生帽：站在边沿不算到）；叼与托（赤油、子油、木棍、食物）；放下后落在实际位置 |
| 场景 | `birth_land_caught` 收尾（E11-1）；宠物窝入睡（E11-2） |

**WP3　意图框架与 LiveBirth 编排**

| 栏 | 内容 |
|---|---|
| 新增 | `intent/`：`Intent`、`Method`、`Attempts`、`IntentRunner`、`Choreography` 执行器、`Ask`；共享方法 `Rendezvous`；动作 `PlaceProp`／`TakeProp`；LiveBirth 编排（陆地 caught／missed／natural、窝生、茎生） |
| 复用 | `placeForTerm`、`BirthExit`（只读）、`LiveBirthGeometry`、`catchPose/stand`、`labourBeats`、`BirthStaging`、`HullFactory.setDown`、`HatCustody` |
| 删除 | 7.3 中的陆地部分 |
| 冻结 | 自 WP1 开工起，分娩链只接受本设计内的提交；补丁式修复只限崩溃和存档损坏（待用户确认 C2） |
| 场景 | F1 全部（`birth_land_caught`、`birth_land_reunion`、`birth_land_missed`、`birth_nest_safe`、`birth_nest_stem`、`birth_stem_caught`）；`infant_feed`（E11-3：先补 Walk 看门狗读数再修） |

**WP4　帽船宿主化**

| 栏 | 内容 |
|---|---|
| 前置 | 用户确认 HatScale 预览（Q3） |
| 新增 | 浮力无条件执行；`isPushable` 与质量比推动；帽体与水中身体分离；`HatScale.of`；`room` 拥挤、滑落与挤落水；Hop 落在帽面即挂上、Hop 或滑落下船；地名 |
| 删除 | `HatDeck` 的席位、租约、冲突与对账；`DeckLayout` 座位表与冲突矩阵；`HatStations`；`HatBoatCrowding`；`CrewState` 的座位移动；`CrewTransfer` HOP；`positionRider` 座位路径 |
| 展台 | 帽内行走（平稳、倾斜、晃动）；推帽船（空帽、载重）；岸到帽船（0.3、0.8、1.5 格）；挤落水 |
| 场景 | `hat_swap`、`cargo`（P0-5）、`sail` 的登船段 |

**WP5　水中动作**

| 栏 | 内容 |
|---|---|
| 新增 | Swim（单一模型）、Climb（读 ContactGeometry 的倾斜帽檐）、Push、Right、Paddle（叼着的木棍或储物里的木棍） |
| 删除 | `AquaticSelfRescue` 的移动部分与阶段机（后果保留）；`WaterDistress` 中重复的推进；`HullHazards` 的释放脚本（改为 dismount + Launch）；`CrewTransfer`、`CrewTransfers`、`CrewPort`、`BraidCarry` 整体删除 |
| 展台 | 水中扒檐（浮着、翻覆、半沉）；扶正；划桨（叼棍、储棍、无棍） |
| 场景 | `self_rescue`、`shore_capsized`、`shake_capsize` |

**WP6　水上意图**

| 栏 | 内容 |
|---|---|
| 新增 | 单一 `AquaticGoal`；欲望与权重 json；意图：出航、上岸、夜泊、补给、照料、`ResolveOwnHat`、救援、初航、送赤油到父船；帽船分娩接入 LiveBirth |
| 删除 | `VoyageController` 与 `VoyagePhase`；`AfloatAgenda`／`AfloatActivities`；`HatFamilyAlarm` 的 SAFETY HOLD 分支；`FirstVoyage` 阶段机；`HullHelm` 租约；`WaterIncidents` 中的移动代码 |
| 场景 | A1–A8 全部池塘场景；待办 P0-1～P0-12 |

**WP7　收口**

| 栏 | 内容 |
|---|---|
| 内容 | 演出层收口；`BASE_IMPORTS_DLC` 清零；`BODY_WRITE` 例外清零；ARCHITECTURE 中相关行与[水生魔理沙](AQUATIC_MARISA_IMPLEMENTATION.md)按新实现改写；本文删去已成为现行规则的内容 |
| 验收 | 待办 E8–E11 全部复测 |

### 10.3 完成定义（每个 WP）

1. 交付表全部接入，删除栏确实删掉。
2. 守卫基线比上一个 WP 短。
3. 展台与场景按钮可用，待办写明观察点，状态为“已接入待试玩”。
4. 用户试玩认可才算完成。

### 10.4 规模目标

- `subspecies/` 净行数下降 40% 以上；
- 分娩链（`LiveDelivery`、`CatchHat`、`FamilyFlight`、`FamilyData` 中的出生部分）下降 30% 以上；
- 新增的 `motor/`、`intent/` 合计控制在 4000 行以内。

净增长意味着在叠新层而没有替换旧代码，应停下检查。

### 10.5 其他模块

迁徙运输（经 `ParentCarry`）、捕食者落地、屋内迁卵、聚落行走等，WP2 后自动受益于共享的 Carry、Walk 与 Occupancy。其余模块只在出现该层问题时迁到新能力，不主动大迁移。

---

## 第十一部分　治理与评审红线

**以下改动一律退回**：
1. 在动作之外写位置、速度、朝向或骑乘。
2. 新增预约、租约、席位、票据或 `pending` 桥接状态（Attempts 和 Await 期限除外）。
3. 失败后原样重试；改记失败分类；同一次失败计入两个预算。
4. 为某个场景加条件分支；底座中出现 `isAquatic`、`boatHome()!=null` 一类分支（应通过 `Receiver`、`SurfaceHost` 多态表达）。
5. 行为或编排中出现跳高、时长、曲线、冲量数值（应来自 `BodyAbility`，或编排数据中的冲量分布）。
6. 新的拒绝原因没有对应的方法（P4）。
7. 失败时倒放、回座、瞬移或直接改成功状态。
8. 被动时钟改动身体或他者状态。
9. 为同一事实新增第二份存储。

**其他治理**：
- **结构守卫**：`BODY_WRITE`、`BASE_IMPORTS_DLC` 两份清单只减不增。工具本身仍是只读建议，见[脚本说明](../scripts/README.md)。
- **待办写法**：每条问题注明归属层（能力、动作、方法、行为、编排、演出），修复只能落在该层。
- **冻结**：水生旧实现已冻结。补丁式修复只限崩溃和存档损坏。

---

## 第十二部分　风险与对策

| 风险 | 对策 |
|---|---|
| 骑乘局部位置抖动 | 固定顺序：宿主移动 → `positionRider` → 乘员动作。客户端沿用原版宿主插值 |
| 迁移期新旧并存冲突 | 只允许 WP 明写的过渡双路径，并在指定 WP 删除；核心文件一次只一个 WP 修改 |
| 去掉阶段机后行为难预测 | `[intent]` 前三名得分、`body` 读数页、承诺段 |
| 物理导致更多意外落水 | 能力系数经 json 热重载在展台调；先定手感，再接行为 |
| 迭代慢（不在本机编译，只靠试玩验收） | 展台小步验收；json 热重载；可选：L1 纯函数单测（待确认 C3） |
| 分娩手感回退 | 冲量分布、片段与节拍不变；用 WP1 展台 D 对照 |
| 工作量大 | 每个 WP 都删代码；WP2 起陆地问题（E11）先见效 |

---

## 第十三部分　旧到新对照与删除清单

| 现有部分 | 去向 | WP |
|---|---|---|
| `DeckLayout` / `hat_deck_layout.json` 的座位、冲突、步行边、入舷点 | 由 `SurfaceHost` 高度场与 `room` 取代；偏好点保留为地名；姿态表改为按地名取 | WP4 |
| `HatDeck`、`HatStations`、`HatBoatCrowding` | 删除；拥挤由 `room` 与滑落产生 | WP4 |
| `HatBoatEntity.positionRider` 的座位语义 | 改为按 `Attach` 局部坐标放置 | WP2→WP4 |
| `CrewTransfer` / `CrewTransfers` / `CrewPort` / `CrewState` 的转移票据 | 拆成 Hop、Climb、Grab、Carry、Release，可行性交给方法 | WP2、WP4、WP5 |
| `BraidCarry`、`BraidRescueGeometry` | 前者成为 Carry 与 Fetch 方法；后者并入 `ContactGeometry` | WP2、WP5 |
| `YukkuriLocomotion`、`CrewReach` | 前者的系数并入 `BodyAbility`、推进并入动作；后者并入 `BodyAbility.reach` | WP1 |
| `HatVessel.solve/arc` | 由 Hop 的最低弧线求解取代 | WP1、WP4 |
| `FamilyFlight`、`BirthFlightPath` | 并入 Launch 与它的 `preview` | WP1 |
| `CatchHat`、`LiveDelivery` 中的握手、预约与预算 | 改为 LiveBirth 编排、Rendezvous、PlaceProp、TakeProp、Unload | WP3 |
| `BirthExit`、`LiveBirthGeometry`、`BirthLaunch` | 保留，作为编排的只读选择器与数据 | — |
| `HullHelm`（租约、航线） | 降为运动积分与航线辅助；朝向与推力由 Paddle 施加 | WP5、WP6 |
| `VoyageController`、`VoyagePhase`、`AfloatAgenda`、`AfloatActivities` | 由水上意图与承诺段取代 | WP6 |
| `AquaticSelfRescue`、`WaterDistress`、`WaterIncidents` 的移动部分 | 由 Swim、Climb、Push、Right 取代；事件记录与一次性友善选择保留 | WP5、WP6 |
| `HullHazards` | 保留翻覆的外因判定；释放乘员、扶正、沉没交给物理与动作 | WP4、WP5 |
| 约 9 处窝、帽、屋判定 | 由 `Occupancy` 取代 | WP2 |
| 各动作内的找帽代码 | 由 `ResolveOwnHat` 取代 | WP6 |

---

## 第十四部分　与现行规则的关系

**保留不变的设定与数值**：
- 水伤频率；
- 漂浮不等于会游、不能潜水；
- 生殖只在岸上，水生只与水生配对；
- 一次性友善选择；
- 翻覆的真实外因；
- 储粮位置的造型规则；
- 雨天暂停出航；
- 岸上活动增加体力负担；
- 产程节拍与时长；
- 出生冲量分布与水上 12% 用力过猛；
- 摔伤规则；
- 接生者选择规则；
- 帽主死亡后的接下规则。

**改变实现、结果等价**：座位改为地名与现场几何；阶段机改为意图与承诺段；转移票据改为动作；出生预约改为接收者的现场判定。这些在对应 WP 合入时改写所属专题。

---

## 第十五部分　用户裁决与待确认

**已裁决（2026-10-28）**

| # | 问题 | 裁决 |
|---|---|---|
| Q1 | 能力数值 | 成体最高跳 0.8 格、最远 1.0 格；搬运不设质量数值，按体型大小判断 |
| Q2 | 水生种在水中能否主动前进 | 保留很弱的挣扎前进 |
| Q3 | 帽子尺寸 | 四种形态统一一个尺寸，先出预览确认 |
| Q4 | 先载幼体、船长最后上船 | 不做硬性规定，由欲望驱动 |
| Q5 | 帽船能否被推动 | 可以 |
| Q6 | 挤不下时孩子能否被挤落水 | 允许 |
| — | 总原则 | 一切行为按真实驱动，靠调整欲望强度或优先级得到演出效果，不硬编码（P11） |

**待确认（本设计相对旧稿的取舍）**

| # | 事项 | 建议 |
|---|---|---|
| C1 | “在帽上”用原版骑乘加自由局部坐标实现，不自写平台携带 | 采纳。结果与旧稿等价，风险和工作量小一个量级（4.2） |
| C2 | 分娩链自 WP1 开工起冻结补丁式修复 | 采纳。与水生冻结一致 |
| C3 | 是否允许 L1 纯函数（能力表、弧线求解、高度场、Occupancy）在远端 CI 跑不依赖 Minecraft 的单元测试 | 建议允许。这会改动 AGENTS 中“仓库不设自动化测试”的约定，须用户决定；不允许时按展台验收照常执行 |
| C4 | 领域之间暂用原版 Goal 优先级仲裁，“顶层 DriveGoal”推迟 | 采纳。领域内的欲望竞争照常满足 P11 |
| C5 | 编排可固定单个仪式内部由设定事实决定的顺序（P13） | 采纳。不同个体、不同行为之间的先后仍由欲望竞争产生 |
