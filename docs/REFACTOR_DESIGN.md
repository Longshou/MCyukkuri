# 身体能力重构：总设计

> 底座、出生链与水生魔理沙 DLC 的重构总设计（用户要求入库）。
>
> - **规则来源**：规则仍以各专题为准，本文只改实现方式：[分娩与接生](CONSUMABLES_AND_BIRTH.md#分娩动作接生与水上分娩)、[水生魔理沙](AQUATIC_MARISA_IMPLEMENTATION.md)、[头饰](HEADWEAR.md)、[子代成长](CHILDHOOD_LIFECYCLE.md)。
> - **进度**：只登记在[待办](TODO.md#水生魔理沙模块重构)。
> - **契约回写**：契约稳定后写回[代码架构](ARCHITECTURE.md)，并删去本文中已经成为现行规则的内容。
> - **冻结**：水生旧实现和出生链已冻结，只接受按本设计的提交；补丁式修复只限崩溃和存档损坏。

## 一、执行协议

1. **开工**：一次只领一个工作包（[第八节](#八工作包)），在待办“进行中”登记工作树和主要文件。核心文件同一时刻只允许一个工作包修改：`YukkuriEntity`、`HatBoatEntity`、`motor/`。
2. **删旧**：按工作包表执行。表中“删除”一栏必须在同一工作包内删掉；只有表里写明的过渡双路径可以暂留，并在指定的后续工作包中删除。
3. **不改规则**：发现与规则冲突就停下，交用户裁决。
4. **交付**：
   - 接入日志前缀；
   - 受影响场景可复测，现有场景观察不到时才补按钮；
   - 待办写明观察点，状态改为“已接入待试玩”；
   - 契约稳定后再更新 ARCHITECTURE。
5. **评审**：命中[红线](#九评审红线)任何一条即退回。
6. **分诊**：问题先定位到层（动作 → 方法 → 行为或编排 → 演出），修复只落在该层。

## 二、为什么上一轮“统一”没生效

10-26 已经做过一轮“唯一入口”重构，涉及 `CrewTransfer`、`HatDeck`、`HullHelm`、`HullFactory`、`WaterIncidents`、`HatCustody`。10-27 试玩仍多处阻塞。下表证据审计于 6d33c130。

| 病根 | 证据 | 后果 |
|---|---|---|
| 同一事实存多份 | “在船上”同时由原版骑乘、`HatDeck`（102 个席位、6 种租约）、`CrewState`、`HatStations` 表达，靠对账同步。“孩子在帽里”由搬运者、孩子、租约三方各自判断 | E11“放下又放回”：托举途中孩子仍是帽的乘客（`CrewTransfer:1476`），滑脱后曲线倒放回座（`:1601-1610`） |
| 能力做成了事务 | `CrewTransfer` 1710 行，六个阶段加租约；HOP 按步行限速沿曲线走（`YukkuriLocomotion:66`） | 1.44 格的跳用了 25 tick；出发席位被别处删掉，报 `no_departure_seat` |
| 同一动作多套实现 | 跳与弧线至少 8 套，转身 4 套；新生儿一次飞行混用两种重力；帽檐几何有带倾斜、不带倾斜两套 | 反复 `rim_out_of_reach` |
| 失败没有结构 | 几何拒绝被改记为暂时失败（`CrewTransfers:94-97`），而暂时失败不计次；分娩链至少 7 套预算；状态切换没有滞回 | 救援 `attempts=0`；RECOVER⇄ASHORE 每秒互切 |
| 一个身体多个写者 | 被动时钟写租约和舵（舵调用 127 处、分布在 14 个文件） | `seat_claim_lost`、`boarding_done` 刷屏 |
| 底座认识 DLC | 底座约 500 处引用水生概念；`LiveDelivery` 有 11 处 `boatHome()!=null` 分支 | 修陆地牵动水生，修水生又牵动陆地 |

结论：
- **封装单位**：封装的单位是**动作**（可行性、进度、保证成立的后置条件），不是事务。
- **单一事实**：每个事实只存一份。
- **按物理落定**：动作结束时一律按物理事实落定。
- **失败预算**：失败只用有限词表，一个意图只有一个预算。
- **独有演出**：独有的演出放进**编排**。

## 三、设计宪法

| # | 原则 |
|---|---|
| P1 | 能力决定动作：能做什么，只由身体能力和真实环境决定 |
| P2 | 一个动作只写一次：走、跳、抛、游、爬、推、叼、搬、划、转身全部复用同一实现 |
| P3 | 行为只说目标，不决定怎么动 |
| P4 | 新增拒绝原因的同时，必须有能缩小该差额的方法 |
| P5 | 不查座位表、冲突矩阵；空间由几何现场判定；“地名”只作目标 |
| P6 | 物理是唯一真相：每个结束路径按实际位置落定，绝不倒放、回座、瞬移或回滚 |
| P7 | 帽船是会漂的小陆地，即可站立宿主；乘员用原版骑乘随船，帽内局部位置自由 |
| P8 | 手段用尽才失败；失败留下现场并产生后果 |
| P9 | 底座不引用 DLC，DLC 实现底座接口 |
| P10 | 先验动作，再验行为 |
| P11 | 不同行为、不同个体之间的先后由欲望竞争产生，靠调欲望得到演出效果（用户裁决） |
| P12 | 一个身体一个驱动者：同一时刻只有一个动作在改它；被动时钟只读 |
| P13 | 演出可以独有，动作不能独有：编排决定何时、什么顺序、谁、播什么；不决定身体怎么动、算不算成功。单个仪式内部由设定事实决定的顺序（产程）可以编排 |

## 四、分层与包

```
L3 行为   Drive（选意图）→ Intent（要什么结果）→ Method（有序手段）       Choreography（仪式编排）
L2 动作   Walk Face Hop Launch Gesture Hold Await Swim Climb Grab Carry Release Push Right Paddle PlaceProp TakeProp
L1 身体   BodyAbility · Attach · SurfaceHost · ContactGeometry · Container/Occupancy · Custody
L0 引擎   原版 move／碰撞／重力、骑乘、YukkuriGroundNavigation
横切      Presentation（读动作姿态、依附、编排节拍、情绪）· Trace（[intent][method][action][body][choreo]）
```

| 包 | 内容 | 依赖 |
|---|---|---|
| `motor/` | L1、L2 | `entity/`、导航、原版 |
| `intent/` | `Intent`、`Method`、`Attempts`、`IntentRunner`、`Choreography`、`Ask` | `motor/` |
| 领域包 | 具体意图、方法、编排、后果规则 | `intent/`、`motor/` |
| `subspecies/` | `HatBoatEntity` 实现 `SurfaceHost`；物种修正；水上意图 | 全部；反向禁止 |

## 五、身体层（L1）

- **BodyAbility**：所有动作和方法只读这张表。
  - 能力项：`hopHeight`、`hopReach`、`stepUp`、`walkSpeed`、`turnRate`、`swimSpeed`、`climbRise`、`grip`、`reach`、`pushForce`、`canCarry(other)`。
  - 成体最高跳 0.8 格、最远 1.0 格（Q1）。
  - 搬运不设质量，被搬者体型小于搬运者即可搬。
  - 第一版系数从 `YukkuriLocomotion.limits` 迁入，放在 `body_ability.json`，`/reload` 生效。
  - 物种修正经 `AbilityModifier` 接入。
- **Attach（身体在哪的唯一事实）**：

  | 状态 | 含义 | 引擎表示 |
  |---|---|---|
  | `FREE` | 地面或水中 | 无载具 |
  | `AIRBORNE` | 起跳或被抛出，尚未接触 | 无载具，跳或抛的动作正在执行 |
  | `ON_HOST(host, local, yaw)` | 站在宿主上 | 原版乘客，宿主按 `local` 放置 |
  | `HELD(carrier)` | 被辫子或嘴托着 | 原版乘客，沿用 `ParentCarry` 的承托方式 |

  - **写入口**：只有 `mount`、`dismount`、`moveLocal`、`settle` 四个，且只允许 L2 动作调用。
  - **存储**：`local` 存在宿主上，随宿主存档，取代 `Deck`；不做旧存档迁移。
  - **判定**：“孩子在帽里”的唯一写法是 `Attach.of(child)` 为 `ON_HOST(该帽)`，且 `Occupancy` 为 `INSIDE_SUPPORTED`。
  - **不预约**：没有预约和租约。空间由 `SurfaceHost.room` 现场判定；两个身体同时奔向同一空位时，后到者被几何拒绝，由方法换落点。
- **SurfaceHost（宿主，`HatBoatEntity` 实现）**：

  | 方法 | 提供 |
  |---|---|
  | `frame()` | 宿主位姿，含倾斜 |
  | `support(local, r)` | 帽面高度场，来源 `HatVessel.travelSupport` |
  | `room(body, local)` | 脚圆与其他乘员、储粮是否重叠 |
  | `rim()` | 帽檐点，含倾斜 |
  | `floor()` | 帽腔内底 |
  | `spots()` | 地名，只作目标 |

  - 帽内挤不下就沿坡滑向帽檐，越檐落水（Q6）。
  - 坡度超过 `grip` 时滑动。
- **ContactGeometry**：所有“够得着、咬得住、托得起”只问这里。合并 `HullHazards.rimPoint/bites`、`CrewTransfer` 的帽檐判定、`BraidRescueGeometry` 和 `CatchHat` 的接触点。
- **Container / Occupancy**：
  - 返回四种结果之一：`INSIDE_SUPPORTED`、`ON_RIM`、`PARTIAL`、`OUTSIDE`。
  - 适用于窝（经 `NestResting.Bed`）、洞穴、纸箱、接生帽和帽船。
  - 入睡、保暖、到窝、孩子在帽里、新生儿落点都用它；取代约 9 处各写一套的判定。
- **Custody**：
  - `HatCustody` 仍是帽子归属的唯一记录。
  - 每个身体有 `Held` 槽位（木棍、食物）；`Paddle` 同时认叼着的木棍和储物里的木棍。
  - 帽子不在认可形态时，主人持续产生 `ResolveOwnHat`。

## 六、动作层（L2）

```java
public interface Action {
    String id();                                   // 登记 Behaviors；进入日志与演出
    Feasibility check(YukkuriEntity body);         // 纯函数：可行，或 Blocked(原因, 差额)
    void start(YukkuriEntity body);
    Progress tick(YukkuriEntity body);             // Running(进度度量) 或 Outcome
    boolean interruptible();                       // 腾空等不可中断段返回 false
    void abort(YukkuriEntity body, Interrupt why); // 保持物理现状并 Attach.settle，绝不回退
    MotionPose pose();                             // 片段与相位
}
public sealed interface Outcome permits Done, Blocked, Incapable, Interrupted, Lost {}
public record Blocked(Reason reason, double shortfall, String detail) implements Outcome {}
```

- **Reason（有限枚举）**：`TOO_HIGH`、`TOO_FAR`、`NO_PATH`、`NO_SUPPORT`、`NO_ROOM`、`NO_CONTACT`、`SLIPPED`、`DROPPED`、`TARGET_MOVED`、`TARGET_GONE`、`TOO_HEAVY`、`NO_TOOL`、`EXHAUSTED`、`NO_PROGRESS`。
- **分类由原因决定，调用方不得改记**：
  - 几何或当下条件 → `Blocked`；
  - 能力不足 → `Incapable`；
  - 目标消失 → `Lost`。
- **结束落定**：每个结束路径都调用 `Attach.settle`：落在宿主支撑面上就是 `ON_HOST`（按实际局部坐标），否则是 `FREE`（按实际位置）。
- **BodyDriver**：每个身体同一时刻只运行一个动作，只在可中断接缝处替换。所有者是持有原版 MOVE 旗标的运行中 Goal；被动 tick 不得调用。

| 动作 | 要点 | 复用 | 取代 |
|---|---|---|---|
| Walk | 地面走导航；宿主上在高度场的局部网格里走；带看门狗进度 | `SurfaceApproach`、`CrewTransfer.boardingApproach` 网格 | 各处自导航、`HatDeck` 步行边 |
| Face | 每 tick 至多转 `turnRate` | `SurfaceApproach.face` | 另外 3 套转身 |
| Hop | 能力内求最低弧线；一次冲量加原版重力；落在帽面即挂上 | `BirthLaunch` 的原版物理求解 | `HatVessel.arc/solve`、HOP 曲线、`YukkuriLocomotion.hop` |
| Launch | 被动弹道，逐 tick 扫掠，首次接触时落定 | `BirthFlightPath`、`catchFalling` 接触结算 | `FamilyFlight` 自积分器、`launchPast`、翻船抛出、挤落冲量 |
| Gesture | 位移有界的小跳、弹起、滚动，受能力门槛约束 | — | 手写速度 |
| Hold / Await | 站稳；或有上限地等待某个条件或信号 | — | SAFETY HOLD、无限等待 |
| Swim | 单一速度模型 | `WaterDistress` | 3 套步进推进 |
| Climb | 读 `ContactGeometry` 的倾斜帽檐 | — | CLIMB、自救攀爬 |
| Grab / Carry / Release | Grab 把目标挂为 HELD；搬运者自己走或游；Release 在实际位置卸下，交给物理 | `ParentCarry` | LIFT、`BraidCarry`、`releaseYoung`、`liftAcross`、`tickFetch` |
| Push / Right | 施力；Right 是绕帽檐加力矩的 Push | — | 两处扶正 |
| Paddle | 把推力和转矩交给宿主 | `HullHelm` 的运动积分 | 舵租约 |
| PlaceProp / TakeProp | 摘帽、放帽、收帽、戴帽，带片段 | `HullFactory.setDown`、`HatCustody` | `CatchHat.beginHat/tickHat` 等 |

## 七、行为层（L3）与编排

- **Intent**：用物理事实写成功条件，例如 `GetTo`、`GetOnto(host)`、`Fetch(obj, place)`、`Reach`、`Right`、`Rest`、`ResolveOwnHat`。
- **Method**：
  - 由适用条件和有序步骤组成；步骤可以是动作、Await、子意图，或 `Ask(帮手)`。
  - 每个方法声明自己能缩小哪些原因，方法顺序写在数据里。
  - 不做通用搜索规划器。
- **Attempts**：
  - 每个意图实例只有一个预算（切换次数加期限）。
  - 失败过的方法，要等“世界变化信号”出现才能再选：目标或宿主移动超过 0.5 格、阻挡者离开、冷却到期。
  - 预算用尽时交给后果规则。
- **Ask**：帮手对每个事件只决定一次，概率由关系和性格决定（推广 `WaterIncidents` 的一次性友善选择）。
- **Drive**：
  - 领域内按“欲望强度 × 权重”选意图，带滞回，`[intent]` 记录前三名得分。
  - 领域之间沿用原版 Goal 优先级，每个领域只注册一个 Goal（C4）。
  - 正在执行的方法或编排带承诺强度，只有危险类欲望能在接缝处抢占。
- **Choreography**：
  - 组成：角色（绑定一次，每个节拍前复核）、站位（只作目标）、节拍。节拍有四种：`Perform`、`Present`、`Await`（期限加超时分支）、`Branch`。
  - 失败结果就是剧情分支，例如没接住 → 摔伤，落水 → 落水事件。
  - 编排不写身体、不判定成败、不自设重试；只保存规则要求的状态（胎次、已抽定冲量）。

**示例：`GetOnto(帽船)` 的方法顺序**
1. 能跳到就 Hop；
2. 走近岸边或帽檐再 Hop；
3. 宿主正在靠近时 Await；
4. Ask 亲油 Carry；
5. 下水 Swim，再 Climb。

### 7.1 出生链：一个编排，三种接收者

**接收者**有三种：放下的接生帽、自己的帽船、窝；没有接收者时落在地面。每只新生儿由 `BirthExit` 只读选择接收者，发射前复核。

| # | 节拍 | 实现 |
|---|---|---|
| 1 | 产地 | `placeForTerm` 判定 → 母油 `GetTo` |
| 2 | 会合 | 共享方法 `Rendezvous`：站位用 `catchPose/stand`，只有一个期限；超时进入“无接生者”分支 |
| 3 | 放帽 | 接生者 `PlaceProp`（帽船分娩跳过） |
| 4 | 产程 | `Present`：产程时钟、片段锁相、`LiveBirthGeometry`、`BirthStaging`，原样保留 |
| 5 | 转身 | `BirthExit.choose` → 母油 `Face` |
| 6 | 出生 | `Launch`，冲量按 `BirthLaunch` 分布抽定并存档（重载不重抽）。首次接触后落定：帽上 → `ON_HOST`，窝 → `INSIDE_SUPPORTED`，地面 → 摔伤，水 → 落水事件 |
| 7 | 初醒、迎接、产后 | `Present` 加 `Gesture`；转身和靠近用共享动作 |
| 8 | 清空接生帽 | `Unload`：会跳的孩子自己跳下；不会跳的由接生者 `Fetch`（Grab、Carry、Release）。够不到时，孩子先在帽内走到靠接生者一侧的边缘 |
| 9 | 收帽 | 帽内为空后接生者 `TakeProp`；帽主死亡时，由存活亲本接下孩子 |

- **帽船分娩**：接收者是自己的帽船，省去 2、3、8、9 四步。分孩子是意图 `Fetch(孩子 → 父船)`。用力过猛的 12% 由冲量产生，属于剧情内容。
- **茎生**：同一编排，只换发射源和站位规则（`stemClearance`）。
- **删除**：
  - `CatchHat` 的会合协商、站位桥接、逐 tick 复验；
  - `LiveDelivery` 的握手和 `boatHome()` 分支；
  - `FamilyData` 的出生预约，四条发射分支合为一条；
  - BROOD Goal 的 12 个分支；
  - 7 套等待和重试预算，改用 Attempts 与 Await 期限。

### 7.2 水生 DLC

- **DLC 只提供四类内容**：能力修正、`SurfaceHost` 实现、水上意图、后果与片段。
- **帽子**：一个物品，四种形态：戴着、托着、展开、翻覆。转换全由动作完成。尺寸统一用 `HatScale.of(owner)`（Q3，先出预览确认）。
- **帽船**：
  - 上下船没有专门流程：岸上 Hop，水中 Swim 加 Climb；
  - 浮力在任何形态下都生效，可被推动（Q5）；
  - 与水中身体碰撞后分离；
  - `HullHazards` 只判翻覆的外因，甩出乘员就是 `dismount` 加 `Launch`。
- **水上生活**：出航、上岸、夜泊、补给、照料、`ResolveOwnHat` 都是意图，替代 `VoyageController` 阶段机和 `AfloatAgenda`。
  - 出航（从放帽到自己上船）、上岸（从靠岸到收帽）是短的承诺段，只有危险能抢占。
  - 谁在划船谁就是船长；“先载幼体、船长最后上船”不做硬规定（Q4）。
  - 雨天不出航、生殖只在岸上、水生只与水生配对、岸上活动增加体力负担，这四条作为欲望输入或设定事实保留。
- **落水与救援**：
  - `WaterIncidents` 只保留事件记录和一次性选择。
  - 自救方法依次为：Swim、Climb、换帽檐点、先 Right、漂向岸。
  - 他救执行 `Fetch(落水者 → 帽或岸)`，方法依次为：帽内走到帽檐再 Grab、Paddle 靠近、下水 Push、Ask 他人。
  - 只有落水者真实被帽保护、上岸或死亡，事件才关闭。
- **初航**：一项学习需求，由真实动作组成：PlaceProp、GetOnto、Paddle、返回、TakeProp。

### 7.3 演出、存档与日志

- **演出**：
  - 来源是 `Action.pose()` 和编排的 `Present`；表情优先级只有一处：操作姿态 > 反应 > 伤病或哭泣 > 情绪 > 兜底。
  - 帽上姿态按局部位置、帽面倾斜和地名选取。
  - 客户端只播放同步数据。
- **存档**：
  - 动作是瞬态的，重载后按事实 `settle`。
  - `Attach` 的局部坐标随宿主存档。
  - 编排只保存规则要求的部分，仍放在 `FamilyData`。
- **日志与读数**：
  - 前缀 `[intent]`、`[method]`、`[action]`、`[body]`、`[choreo]`，开始、切换、结束记 INFO，逐 tick 采样只在详尽开关下记。
  - 失败日志写原因加差额。
  - `status raw` 增加 `body` 页：能力值、依附、当前动作、最近结果、当前意图与方法、预算余量。

## 八、工作包

顺序按“尽早修好用户可见问题”排列。每个工作包的删除项在本包内完成。

| WP | 目标 | 新增与改接 | 删除 | 复测场景 |
|---|---|---|---|---|
| **1** | E11 切片：依附、搬运、容器 | `motor/`：`Outcome`/`Reason`、`Attach`、`BodyDriver`、`Container`/`Occupancy`、Grab/Carry/Release（复用 `ParentCarry` 骑乘）。陆地接生帽收尾改走 `Unload`。窝的入睡、保暖、到窝改用 `Occupancy`。补 JUnit 依赖，为 `Occupancy` 写纯函数测试 | 陆地 LIFT 路径与倒放回座、陆地 `BraidCarry` 会话、`CatchHat.tickFetch` 循环、陆地 `releaseYoung`、窝判定重复实现 | `birth_land_caught` 收尾（E11-1）；宠物窝入睡（E11-2） |
| **2** | 运动统一 | `BodyAbility`（json 加 reload）、Walk（带看门狗读数）、Face、Hop（地面）、Launch、Gesture；新生儿飞行、迎接小跳、摔伤弹起、窝沿滚动全部改接 | `FamilyFlight` 自积分器与 `pending*`、`FamilyLife.face` 等转身、`YukkuriLocomotion` 对帽船的依赖 | `birth_land_*`、`hop`、`infant_feed`（E11-3） |
| **3** | 意图框架与 LiveBirth | `intent/`、`Rendezvous`、PlaceProp/TakeProp；LiveBirth 编排（陆地、窝、茎生） | 7.1 删除项中的陆地部分 | F1 全部出生场景 |
| **4** | 帽船宿主化 | `SurfaceHost` 全量；`ON_HOST` 局部坐标；`room`、滑落、挤落水；Hop 登船；浮力；可推动；`HatScale`（预览经用户确认后） | `HatDeck` 席位与租约、`DeckLayout` 座位表、`HatStations`、`HatBoatCrowding`、`CrewState` 座位移动、HOP、`positionRider` 座位路径 | `hat_swap`、`cargo`、`sail` 登船段 |
| **5** | 水中动作 | Swim、Climb、Push、Right、Paddle；`ContactGeometry` | `AquaticSelfRescue` 的移动与阶段机、`HullHazards` 释放脚本；`CrewTransfer`、`CrewTransfers`、`CrewPort`、`BraidCarry` 整体删除 | `self_rescue`、`shore_capsized`、`shake_capsize` |
| **6** | 水上意图 | 单一 `AquaticGoal`、欲望权重 json、水上意图与救援；帽船分娩接入 LiveBirth | `VoyageController`、`VoyagePhase`、`AfloatAgenda`、`AfloatActivities`、SAFETY HOLD 分支、`FirstVoyage` 阶段机、`HullHelm` 租约、`WaterIncidents` 移动部分 | A1–A8 全部；待办 P0 观察点 |
| **7** | 收口 | `check_architecture` 增加 `BODY_WRITE`、`BASE_IMPORTS_DLC` 两条规则并清零；演出收口；改写 ARCHITECTURE 与水生专题 | 遗留过渡路径 | E8–E11 全量复测 |

- **WP1 过渡期**：
  - 接生帽上的孩子仍由旧座位放置；
  - 帽船继续走旧路径，WP4、WP5 删除；
  - 被 Grab 的孩子立刻离开座位、成为 HELD，掉落就按实际位置落定，不再回座。
- **规模信号**：每个工作包的净行数应下降，`motor/` 与 `intent/` 合计控制在 4000 行以内；出现净增长就停下检查。
- **其他模块**：迁徙运输、捕食者落地、迁卵在 WP1、WP2 后自动受益；其余只在出问题时迁移。

## 九、评审红线

1. 在动作之外写位置、速度、朝向或骑乘。
2. 新增预约、租约、席位、票据或 `pending` 桥接状态（Attempts 与 Await 期限除外）。
3. 失败后原样重试；改记失败分类；同一次失败计入两个预算。
4. 为某个场景加条件分支；底座中出现 `isAquatic`、`boatHome()!=null` 一类分支，应改用接收者或宿主的多态。
5. 行为或编排中出现跳高、时长、曲线、冲量数值（冲量分布数据除外）。
6. 新的拒绝原因没有对应的方法。
7. 失败时倒放、回座、瞬移，或直接改成功状态。
8. 被动时钟改动身体或他者状态。
9. 为同一事实新增第二份存储。

## 十、裁决（2026-10-28）

| # | 裁决 |
|---|---|
| Q1 | 成体最高跳 0.8 格、最远 1.0 格；搬运按体型大小判断，不设质量 |
| Q2 | 水生种在水中保留很弱的挣扎前进 |
| Q3 | 帽子四种形态统一尺寸，先出预览确认 |
| Q4 | “先载幼体、船长最后上船”不做硬规定 |
| Q5 | 帽船可被推动 |
| Q6 | 帽内挤不下时，孩子可以被挤落水 |
| P11 | 一切行为由真实欲望竞争驱动，靠调欲望得到演出效果 |
| C1 | “在帽上”用原版骑乘加自由局部坐标实现，不自写平台携带 |
| C2 | 出生链与水生旧实现一同冻结 |
| C3 | 允许身体层纯函数（能力表、弧线、高度场、Occupancy）在远端 CI 运行单元测试；单元测试不算试玩验收 |
| C4 | 领域之间暂用原版 Goal 优先级仲裁，顶层 DriveGoal 推迟 |
| C5 | 编排可以固定单个仪式内部由设定事实决定的顺序；个体之间的先后仍由欲望竞争产生 |
