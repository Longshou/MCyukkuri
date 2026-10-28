# 身体能力重构：技术规格

> 本文是[整体设计](REFACTOR_DESIGN.md)的实现规格。凡是接手本重构的代理：
> - 先读第 0–3 节；
> - 再读所领工作包在[第 14 节](#14工作包实施细则)中的条目，以及该条目引用的章节；
> - 有歧义时先读[第 15 节](#15常见误解)，仍无法确定就停下来问，不要猜。
>
> 用语：**必须**、**禁止**是硬性要求；**建议**允许按现场取舍，但须在提交说明里写明理由。
> 行号以审计时的 6d33c130 为准，实施前用 grep 重新定位。

---

## 0　术语

| 术语 | 含义 |
|---|---|
| 身体 | 一只 `YukkuriEntity` |
| 宿主 | 可以站在上面的实体，目前只有 `HatBoatEntity`（接生帽与帽船），实现 `SurfaceHost` |
| 局部坐标 | 宿主坐标系下的坐标，单位是“帽单位”。1 帽单位 = `HatBoatEntity.unit()` 格 = `hullScale()/16` 格 |
| 依附 | 身体此刻的位置归属，取值 `Free`、`Airborne`、`OnHost`、`Held` 之一（第 5 节） |
| 动作 | L2 层的一次身体执行，实现 `Action` |
| 意图 | L3 层要达成的结果，实现 `Intent` |
| 方法 | 达成意图的一种手段，实现 `Method` |
| 编排 | 仪式脚本，实现 `Choreography` |
| 被动时钟 | 组件的 `ComponentSpec.tick` 回调；在原版 AI 和 `travel` 之后运行 |

---

## 1　运行环境与硬约束

| 项 | 取值 |
|---|---|
| 游戏与加载器 | Minecraft 1.21.1；NeoForge 21.1.250；ModDevGradle 2.0.146；Java 21 |
| 权威 | 服务端拥有全部业务状态与决策。客户端只经 `YukkuriView` / `HatBoatView` 读同步数据，禁止推导业务 |
| 构建 | 本机不编译。远端 CI 执行 `./gradlew build`，其中包含 `test` 任务，会运行 `src/test/java` 下的单元测试 |
| 验收 | 只有用户在试玩场认可才算完成。单元测试与 CI 不改变待办状态 |
| 存档 | 当前试玩场的数据结构调整不做旧存档迁移；但已有 NBT 的路径、类型和写入时机，只能在本文明确列出的地方改变 |
| 组件 | 新组件沿用 `YukkuriComponents` / `ComponentSpec`。`registerGoals` 内赋值的字段不得带初始化器（含 `= null`） |

---

## 2　Tick 时序（服务端）

```
ServerLevel 实体循环（非乘客实体）
 ├─ 宿主 HatBoatEntity.tick()                     ① 宿主先动：浮力、漂移、HullHelm 积分、翻覆判定
 │    └─ 原版对每个乘客调用 passenger.rideTick()   ② 乘客的 tick 走下方流程，结束后原版调用 host.positionRider(passenger)
 └─ YukkuriEntity.tick() → aiStep()
      a. super.aiStep()
           serverAiStep: goalSelector.tick()       ③ Goal / 意图 / 编排角色在这里决策，只调用 BodyDriver.run(...)
                         navigation / moveControl / jumpControl
           travel(input):
             ★ BodyDriver.tickTravel(input)        ④ 当前动作执行；返回 CONSUMED 时跳过原版 travel
             （遗留钩子：crew().holdsBody()、family().travelFlight()，分别在 WP5、WP2 删除）
             原版 travel                            ⑤ PASS 时由原版积分：move → 重力 0.08 → 垂直 ×0.98、空中水平 ×0.91
      b. components.tick(BEFORE / AFTER_ALIVE_CHECK) ⑥ 被动时钟：只改本组件自己拥有的数据
      c. presentation.tick(YukkuriControl.advance())  ⑦ 读取动作姿态、依附与编排节拍并发布
```

- **乘客走同一条流程**：乘客在 `rideTick` 中同样走完 a–c，随后位置被宿主的 `positionRider` 覆盖。因此 `OnHost` 身体的位移只能写进局部坐标（第 6 节），不能写世界位置。
- **实现时核对**：先确认 1.21.1 中乘客是否进入 `LivingEntity.travel`。若不进入，就在 `aiStep` 的 a 与 b 之间调用同一个 `BodyDriver.tickTravel`，语义不变。核对结论写进提交说明。
- **决策与执行**：决策（③）先于执行（④），同一 tick 内生效，没有一帧延迟。

---

## 3　包结构与依赖

```
motor/                        L1 + L2（可依赖 entity/、life/ 导航、原版）
  Motor.java                  唯一写身体的门面（第 4 节）
  BodyDriver.java             组件 MOTOR（第 9 节）
  Attach.java  AttachState.java
  BodyAbility.java  AbilityTable.java  AbilityModifier.java
  Outcome.java  Reason.java  Feasibility.java  Progress.java  MotionPose.java
  ClipMarkers.java            导出片段标记（泛化自 subspecies/crew/CrewClips）
  action/                     Walk Face Hop Launch Gesture Hold Await Grab Release PlaceProp TakeProp
                              Swim Climb Push Right Paddle（WP5）
  surface/                    SurfaceHost HostFrame Riders Container Occupancy BedContainer HostContainer ContactGeometry
  geom/                       纯 Java，禁止 import net.minecraft.*：Vec  ArcSolver  Ballistic  Disc  FloorShape
                              OccupancyMath  HeightGrid  GridPath
intent/                       可依赖 motor/
  Intent  Method  Step  Attempts  WorldSignature  IntentRunner  MotorOwner
  Ask  AskLedger  Drive  Desire  Commitment          （WP3）
  Choreography  Beat  Role  Conductor                （WP3）
领域包（family/ life/ settlement/ …）       具体意图、方法、编排、后果规则
subspecies/                   HatBoatEntity implements SurfaceHost；物种修正；水上意图（WP4–6）
```

- **底座不引用 DLC**：除 `subspecies/`、`client/aquatic/` 与注册入口外，任何包禁止 import `subspecies.*`。WP1–WP6 期间，已有引用只允许减少。
- **`geom/` 保持纯 Java**：只用 `java.*` 与本包的 record，保证单元测试不需要 Minecraft 引导。

---

## 4　Motor：唯一写身体的门面

```java
public final class Motor {
    /** 设置速度并标记 hasImpulse。只用于起跳、抛射、推、游的推进。 */
    public static void velocity(YukkuriEntity body, Vec3 v, String cause);
    /** 带碰撞的位移（MoverType.SELF），返回实际位移。 */
    public static Vec3 move(YukkuriEntity body, Vec3 delta, String cause);
    /** 同步 yRot / yBodyRot / yHeadRot；调用方已按 turnRate 限速。 */
    public static void face(YukkuriEntity body, float yaw);
    /** startRiding(host, true)；成功后记 [body] mount。 */
    public static boolean mount(YukkuriEntity body, Entity host, String cause);
    /** 在实际脚点卸下：stopRiding 后位置保持 at，速度设为 v；记 [body] dismount。 */
    public static void dismount(YukkuriEntity body, Vec3 at, Vec3 v, String cause);
    /** 仅供 settle 推出碰撞时使用，|delta| 不得超过 0.25 格，超过即拒绝并记日志。 */
    public static boolean nudge(YukkuriEntity body, Vec3 delta, String cause);
}
```

- **写入范围（必须）**：对 `YukkuriEntity` 的 `setPos`、`moveTo`、`teleportTo`、`setDeltaMovement`、`addDeltaMovement`、`move(...)`、`startRiding`、`stopRiding` 和朝向写入，只能出现在 `motor/` 内。
  - 例外一：`YukkuriEntity` 对原版方法的覆写，只能转调 `BodyDriver`。
  - 例外二：迁移期的基线清单（第 13 节）只减不增。
- **卸下位置**：`Motor.dismount` 置位线程局部标志 `Motor.DISMOUNTING`。`YukkuriEntity` 与 `HatBoatEntity` 的 `getDismountLocationForPassenger` 在标志置位时返回 `passenger.position()`，以阻止原版把乘客搬到载具中心。
- **禁止**：`moveTo` 到“落点”、把孩子放回座位、为了接住而修正速度。

---

## 5　Attach：依附（身体在哪的唯一事实）

### 5.1 状态与推导

```java
public sealed interface AttachState permits Free, Airborne, OnHost, Held {}
public record Free(boolean inWater) implements AttachState {}
public record Airborne(String action) implements AttachState {}
public record OnHost(SurfaceHost host, Vec3 local, float localYaw) implements AttachState {}
public record Held(YukkuriEntity carrier) implements AttachState {}

public final class Attach {
    public static AttachState of(YukkuriEntity body);   // 纯读取
    public static AttachState settle(YukkuriEntity body, String cause); // 按事实推导；必要时 nudge 推出碰撞
}
```

`of` 的推导顺序：

| 条件 | 结果 |
|---|---|
| `getVehicle() instanceof SurfaceHost h` | `OnHost(h, h.riders().local(body), h.riders().yaw(body))` |
| `getVehicle() instanceof YukkuriEntity c` | `Held(c)` |
| 当前动作是 Hop 或 Launch，且尚未接触 | `Airborne(action.id())` |
| 以上都不是 | `Free(isInWater())` |

- **唯一存储**：原版载具关系，加宿主侧的 `Riders`（UUID → 局部坐标、朝向）。禁止在其他地方保存“在帽里”“在搬运中”之类的标记。
- **组合判定**：“在帽里”=`of(child)` 为 `OnHost(该帽)`，且 `Occupancy.of(child, 帽.container())` 为 `INSIDE_SUPPORTED`。

### 5.2 状态转换（只由动作调用）

| 转换 | 调用者 | 实现 |
|---|---|---|
| Free → Airborne | Hop / Launch 开始 | `Motor.velocity` |
| Airborne → OnHost | Hop / Launch 接触帽面 | `Motor.move` 到接触点 → `Motor.mount(host)` → `riders.put(local)` → 速度清零 |
| Airborne → Free | 原版 `onGround` 或入水 | `Attach.settle` |
| Free / OnHost → Held | Grab | `Motor.mount(carrier)`；原版先移除旧载具，由宿主的 `removePassenger` 删除其 `Riders` 条目 |
| Held → Free | Release，或搬运被打断 | `Motor.dismount(child, child.position(), Vec3.ZERO)`，之后交给原版物理 |
| OnHost → Free | 跳下、滑落、被甩出 | `Motor.dismount(实际脚点, 速度)` |
| OnHost 内移动 | 宿主上的 Walk、滑动 | `riders.put(local')`，单 tick 位移不超过 `walkSpeed` |

### 5.3 不变量

每次转换后检查；违反时记 `[body] invariant` 的 WARN，禁止“修正”。

| 编号 | 不变量 |
|---|---|
| I1 | 每个身体至多一个载具（原版保证） |
| I2 | `host.riders()` 的键集合等于宿主的乘客 UUID 集合。`addPassenger` 遇到缺键时写入 `toLocal(position)`；`removePassenger` 删除对应键 |
| I3 | `Held` 的数量与形态受 `ParentCarry.canSeat` 约束 |
| I4 | 卸下前后位置差不超过 0.25 格；超过说明有代码在瞬移 |

### 5.4 边界情况

| 情况 | 处理 |
|---|---|
| 宿主被移除或区块卸载 | 原版弹出乘客 → `removePassenger` → 乘客成为 `Free`，留在实际位置 |
| 搬运者死亡、受击、受惊、被命令、被锁定 | 打断桥接到当前 Carry → Release，在实际位置落定 |
| 区块保存 | 原版把乘客写进载具 NBT，`Riders` 写进宿主 NBT，读档时由 `addPassenger` 重建 |
| 换维度 | 原版先卸下乘客；不做额外补偿 |

---

## 6　SurfaceHost、HostFrame、Riders

```java
public interface SurfaceHost {
    Entity entity();
    HostFrame frame();                              // 当前 tick 位姿
    OptionalDouble support(Vec3 local, double r);   // 帽面高度场，局部单位；无支撑返回 empty
    boolean room(YukkuriEntity body, Vec3 local);   // 与其他乘员、储粮的脚圆不重叠
    List<Vec3> rim();                               // 帽檐采样点，世界坐标，含倾斜，24 个
    Container container();                          // 帽腔内底
    Map<String, Vec3> spots();                      // 地名，只作目标
    Riders riders();                                // UUID → 局部坐标、朝向；存档与同步
    boolean steady();                               // 剧烈晃动或翻覆中为 false，此时不接受起跳落点规划
    boolean unloadRequested();                      // 帽主当前运行的意图是 Unload 或收帽：由意图推导，不另存标记
}
public record HostFrame(Vec3 origin, double unit, float yawRad, float rollDeg, float pitchDeg) {
    public Vec3 toWorld(Vec3 local);  // 矩阵顺序 yaw · roll(Z) · pitch(X)，与 HatBoatEntity.motionRoll / motionPitch 一致
    public Vec3 toLocal(Vec3 world);
}
```

| 方法 | 实现要点（`HatBoatEntity`） |
|---|---|
| `frame()` | `origin=position()`、`unit=unit()`、`yawRad=turn()`、`roll=motionRoll(1)`、`pitch=motionPitch(1)` |
| `support` | `HatVessel.travelSupport(mode(), local.x, local.z, r/unit)`；结果为 NaN 时返回 empty |
| `room` | 脚圆半径 = `0.42·bbWidth/unit`；与其他乘员（及 WP4 起的储粮圆）的圆心距小于 `r1+r2-0.02` 即为重叠 |
| `rim` | 对 24 个方向 θ，取 `HatVessel.extent(mode,0,0,cosθ,sinθ,0)` 处的帽檐顶点，经 `frame().toWorld` 变换 |
| `riders` | 存在宿主上：NBT 键 `Riders`，同步槽 `RIDERS`（第 12 节） |

- **positionRider 新路径**：`at = frame().toWorld(riders.local(p))`，`move.accept(p, at)`。宿主先移动、乘员后定位由原版保证（第 2 节①②），所以不另写平台携带。
- **过渡（WP1–WP3）**：`HatBoatEntity.usesDeck()` 在 `mode()==NEST`（接生帽）时为 false，NEST 的乘员全部走新路径，不进 `HatDeck`。水上模式仍走 `HatDeck`，到 WP4 删除 `usesDeck()` 与全部座位路径。
- **帽内寻路（WP4）**：
  - 在局部平面上建 `HeightGrid`，格距 0.25 格（换算成帽单位），每格高度取 `support`。
  - 相邻格高差不超过 `stepUp/unit` 才连边。
  - `GridPath` 用 A*，代价为距离，加上贴近帽檐的惩罚。

---

## 7　Container 与 Occupancy

```java
public interface Container {
    FloorShape floor();      // 内底形状，在容器平面坐标中（geom/FloorShape：圆、圆角矩形或多边形）
    double floorY();         // 世界高度
    double rimY();
    Vec3 toPlane(Vec3 world);// 世界 → 容器平面坐标（宿主容器经 HostFrame）
}
public enum Occupancy { INSIDE_SUPPORTED, ON_RIM, PARTIAL, OUTSIDE;
    public static Occupancy of(YukkuriEntity body, Container c); // 委托 geom/OccupancyMath.classify
}
```

**判定算法**（`OccupancyMath.classify`，纯函数）：

- **输入**：脚点平面坐标 `(x,z)`、脚高 `y`、足迹半径 `r = 0.42·bbWidth`、`FloorShape`、`floorY`、`rimY`、`supported`（脚下是否有碰撞支撑）。
- **探针**：圆心，加上半径为 `r` 的圆周上 8 个点，共 9 个。

| 结果 | 条件 |
|---|---|
| `INSIDE_SUPPORTED` | 9 个探针全部在内底内，且 `|y-floorY| ≤ 0.15`，且 `supported` |
| `ON_RIM` | 圆心在内底内，或至少 3 个探针在内底内，且 `y ≥ rimY-0.05`；或者 `y ≥ rimY-0.05`，且任一探针落在内底外扩 0.25 格的环带里 |
| `PARTIAL` | 圆心在内底内，或至少 3 个探针在内底内，但 `y < rimY-0.05`，且不满足 `INSIDE_SUPPORTED` |
| `OUTSIDE` | 其余情况 |

**容器实现**：

| 容器 | 内底来源 |
|---|---|
| `BedContainer`（宠物窝、叶窝、聚落窝） | `NestResting.Bed`：半边长 `half()`、圆角 `corner()`，中心为方块中心；`floorY` 取中心处的碰撞顶面（`SurfaceApproach.surface`）；`rimY = floorY + 窝沿高`（从方块碰撞形状取） |
| `HostContainer`（接生帽、帽船） | 帽腔内底高度上的 `HatVessel.extent` 半径圆；经 `HostFrame` 换算 |
| 洞穴、纸箱 | 方块格并集（WP 按需） |

**规则（必须）**：“到窝”“可入睡”“在帽里”“新生儿落进窝里”只认 `INSIDE_SUPPORTED`。`ON_RIM` 和 `PARTIAL` 由方法继续 Walk 到 `NestResting.choose` 给出的休息点。

---

## 8　BodyAbility 与物理模型

### 8.1 能力公式

沿用 `YukkuriLocomotion.limits` 的 S、F、L，系数按 Q1 改定。

```
S = sqrt(clamp(growthScale·scale, .06, 1.5))                   体型
E = clamp(energy/80, 0, 1)
H = clamp(health/maxHealth, .1, 1)
I = 1 / (1 + .18·painSeverity + (footBurned ? .3 : 0))
F = (.35 + .65E) · sqrt(H) · I                                 体况
L = 宿主载重系数（WP4 前沿用 1/(1+load/12)；WP4 起由宿主提供）
canJump = (adult || childhood.canJump) && energy ≥ 8 && !dying && !sleeping

hopHeight = canJump ? 0.80·S·F : 0          （Q1：成体满体况 0.8 格）
hopReach  = canJump ? 1.00·S·F : 0          （Q1：成体满体况 1.0 格）
stepUp    = 0.6 · S                         （与原版 maxUpStep 对齐的起点值，展台定稿）
walkSpeed = 0.10·S·F·L                      格/tick
turnRate  = 12° · (0.6 + 0.4F)              每 tick
swimSpeed = 0.055·S·F·L                     底座值；水生种由 AbilityModifier 修正（Q2）
climbRise = 0.75·S·(.65 + .35F)
grip      = F                               0..1，与倾角阈值比较（WP4）
reach     = 嘴：沿用 ParentCarry.inReach 的现行距离；魔理沙辫梢：沿用 BraidRescueGeometry 的现行范围（WP5 合并）
canCarry(o) = ParentCarry.capacityProblem(self, o, …) == null   （沿用现行判定，结果不变）

全部系数来自 data/mcyukkuri/body/ability.json，修正器经 AbilityModifier 按物种乘入。
```

### 8.2 数据与热重载

- **文件**：`src/main/resources/data/mcyukkuri/body/ability.json`，格式为 `{ "coefficients": { "hop_height": 0.80, ... }, "modifiers": { "aquatic_marisa": { "swim_speed": 2.0 } } }`。
- **加载**：
  - 在 `CommonRegistration` 的 `AddReloadListenerEvent` 注册 `AbilityTable`（`SimpleJsonResourceReloadListener`）。
  - 加载时计算 8 位十六进制指纹，供 `[action]` 日志使用。
  - JSON 缺失或解析失败时使用代码内默认值，并记 WARN。

### 8.3 原版运动学（Hop、Launch 唯一使用的模型）

- **离散模型**：与 `LivingEntity.travel` 一致，也与 `BirthLaunch` 已采用的模型一致。

  ```
  每 tick：pos += v；v.y = (v.y − 0.08)·0.98；空中 v.xz ·= 0.91
  ```

- **实现方式**：Hop 与 Launch 起跳后返回 `PASS`，由原版 travel 积分；动作只在每 tick 开头做接触检测（8.5）。禁止自写积分器，或用与此不同的重力、阻力。
- **`geom/Ballistic`**：
  - `airTicks(vy, dy)`：从起跳到下落穿过相对高度 `dy` 的 tick 数，带小数插值；
  - `speedFor(dx, n) = dx·(1−0.91)/(1−0.91^n)`；
  - `samples(v0, n)`：逐 tick 的位置序列。

### 8.4 Hop 弧线求解（`geom/ArcSolver.lowest`）

```
输入：from、to（脚点）、hopHeight、hopReach、clearance(points) 回调（扫掠检查）
dx = |to−from|水平，dy = to.y − from.y
dx > hopReach   → Blocked(TOO_FAR, dx − hopReach)
dy > hopHeight  → Blocked(TOO_HIGH, dy − hopHeight)
for apex c from max(dy,0) + 0.08·S to hopHeight，步长 0.05：
    vy = 二分求解：使离散序列的最高点 = c
    n  = Ballistic.airTicks(vy, dy)
    vh = Ballistic.speedFor(dx, n)
    pts = Ballistic.samples((vh·dir, vy), n)
    if clearance(pts)：return Plan(vy, vh, dir, n, c)
return Blocked(TOO_HIGH, 最小可行 c − hopHeight)      // 越不过障碍也按“需要更高”报告差额
```

- **扫掠**：`clearance` 逐 tick 取弦线段，用身体 AABB 扫掠方块碰撞与宿主帽面（帽体用 `support` 加 `rim`）。不能只检查理想曲线，原因见 `BirthFlightPath` 的注释。
- **起跳时序**：
  - 动作开始后先做蓄力：读 `ClipMarkers.of("hop").ticks("takeoff", 4)`，期间身体静止并播放蓄力姿态。
  - 到标记时调用 `Motor.velocity((vh·dir, vy))`。
  - 落地由接触事件触发 `land` 片段。

### 8.5 接触与落定（Hop、Launch 共用 `ContactResolver`）

每个空中 tick 在原版 move **之前**执行：

1. **找候选宿主**：`seg = [feet, feet+v]`。在 `seg` 扩张 3 格的范围内，找 `steady()` 的宿主。
2. **算交点**：对每个宿主，在局部坐标下求 `seg` 与 `support` 曲面的**向下**交点。若交点的足迹满足 `Occupancy` 为 `INSIDE_SUPPORTED`，且 `room` 为真，就是有效接触。
3. **最近有效接触**：`Motor.move` 到接触点；`Motor.mount(host)`；`riders.put(local)`；速度清零；`Done`。
4. **只碰到帽檐**（`seg` 穿过帽檐壁，帽面高度陡升处）：水平速度乘以 −0.3，竖直速度保留，记 `[body] rim_bounce`；本 tick 交给原版。接生帽 `canBeCollidedWith=false`，所以帽檐反弹只能在这里做。
5. **其余情况**：交给原版。`onGround` 或入水后，由 `Attach.settle` 产生 `Free`，原因分别为 `landed` 或 `water`。

- **溺水与摔伤**：都由各自规则根据实际结果触发（落地摔伤、`WaterIncidents`）。`ContactResolver` 不判后果。

---

## 9　动作框架

### 9.1 接口

```java
public interface Action {
    String id();                                    // 小写下划线；进入日志、同步与 Behaviors
    Feasibility check(YukkuriEntity body);          // 纯函数，无副作用
    void start(YukkuriEntity body);
    Progress tick(YukkuriEntity body);              // 在 BodyDriver.tickTravel 中调用
    boolean interruptible();
    void abort(YukkuriEntity body, String why);     // 保持物理现状并 Attach.settle，禁止回退
    MotionPose pose();                              // 片段 id、起始 tick、是否循环
    default int progressWindow() { return 40; }     // 看门狗窗口（tick）
    default double progressEpsilon() { return 0.05; }
}
public record Feasibility(boolean ok, Reason reason, double shortfall, String detail) {}
public sealed interface Progress permits Running, Outcome {}
public record Running(double metric, TravelMode travel) implements Progress {} // metric 越小越接近完成
public enum TravelMode { CONSUMED, PASS }        // CONSUMED：本 tick 已自行移动；PASS：交给原版 travel
public sealed interface Outcome extends Progress permits Done, Blocked, Incapable, Interrupted, Lost {}
public record Blocked(Reason reason, double shortfall, String detail) implements Outcome {}
```

### 9.2 Reason 与分类

分类由 `Reason` 固定决定，调用方禁止改记。

| Reason | 结果类 | 差额单位 | 典型方法应对 |
|---|---|---|---|
| `TOO_HIGH` | Blocked | 格 | 换落点、找更低的边沿、请人搬 |
| `TOO_FAR` | Blocked | 格 | 走近、等宿主靠近 |
| `NO_PATH` | Blocked | 到最近可达点的距离 | 绕行、换目标、请人 |
| `NO_SUPPORT` | Blocked | — | 换落点 |
| `NO_ROOM` | Blocked | — | 换位置、等空位、挤（WP4 规则） |
| `NO_CONTACT` | Blocked | 差多少够到 | 挪位、转身、靠近 |
| `SLIPPED` | Blocked | — | 换抓点；同一意图的预算计数 |
| `DROPPED` | Blocked | — | 重新 Grab |
| `TARGET_MOVED` | Blocked | 移动距离 | 重新接近 |
| `NO_PROGRESS` | Blocked | 窗口内的进度变化 | 换方法 |
| `TOO_HEAVY` | Incapable | — | 请别人 |
| `NO_TOOL` | Incapable | — | 先取工具（木棍） |
| `EXHAUSTED` | Incapable | — | 休息，或交给后果规则 |
| `TARGET_GONE` | Lost | — | 结束意图 |

### 9.3 BodyDriver（组件 `MOTOR`）

```java
public final class BodyDriver {
    public boolean run(MotorOwner owner, Action a);   // 见下方接受规则
    public void cancel(MotorOwner owner, String why); // 当前动作 abort
    public Action current();  public Outcome last();  public long lastTick();  public MotorOwner owner();
    TravelMode tickTravel(Vec3 input);                // 第 2 节 ④
}
public interface MotorOwner { String ownerId(); boolean active(); }  // Goal 包装在 start / stop 时切换 active
```

- **接受规则**：
  - 没有当前动作，接受；
  - 同一所有者，在当前动作可中断时 `abort("replaced")` 后接受，否则拒绝；
  - 不同所有者，仅当当前动作可中断、且旧所有者 `active()==false` 时接受；
  - 拒绝时记 `[action] refused`（按实体与原因限频）。
- **每 tick**：
  1. 所有者 `active()==false` 且动作可中断：`abort("owner_inactive")`。
  2. 调用 `tick`。
  3. 看门狗：`metric` 在 `progressWindow` 内下降不足 `progressEpsilon`，就以 `Blocked(NO_PROGRESS)` 结束。
  4. 得到 Outcome 时：`Attach.settle`，保存 `last`，记 `[action] end`。
- **打断桥接**：`ComponentSpec` 中 MOTOR 的打断 FRIGHT、HURT、COMMAND、PET_LOCK、BURNING、DEATH。可中断动作立即 abort；不可中断的空中段继续由物理完成，落地后以 `Interrupted` 结束。
- **存档**：不保存。读档后 `current=null`，依附由事实推导，所有者 Goal 重新决策。
- **被动时钟禁止调用 `run`**：`YukkuriEntity.aiStep` 在调用 `super.aiStep()` 期间置位 `BodyDriver.DECIDING`（Goal 决策与 travel 都在其中），`run` 在标志未置位时一律拒绝并记 WARN。被动时钟、交互回调和命令只能改需求或数据，由 Goal 在下一次决策时发起动作。

### 9.4 动作规格

| 动作 | 参数 | check | 执行 | 结束 |
|---|---|---|---|---|
| **Walk** | 目标（世界坐标，或宿主局部坐标）；速度系数 | 地面：`SurfaceApproach.canApproach`。宿主上：`GridPath` 有路 | 地面：`SurfaceApproach.walk/walkAround`，返回 PASS。宿主上：沿路径每 tick 移动 ≤ `walkSpeed/unit` 并 `riders.put`，返回 CONSUMED | 到达（`SurfaceApproach.arrived`，或局部距离 ≤ 0.05）→ Done；`lastResult` 受阻 → `NO_PATH`；看门狗 → `NO_PROGRESS` |
| **Face** | 目标朝向 | 无 | 每 tick 转动 ≤ `turnRate`，经 `Motor.face`；CONSUMED（不移动） | 角差 ≤ 3° → Done |
| **Hop** | 落点 | `ArcSolver.lowest` | 蓄力 → 冲量 → PASS → `ContactResolver` | 接触 → Done；空中超过 `n+10` tick → 按实际位置 settle 后 Done |
| **Launch** | 冲量（调用方预算内） | 冲量有限；`preview` 扫掠仅作日志 | 立即 `Motor.velocity`；PASS；`ContactResolver` | 同 Hop；不可中断 |
| **Gesture** | `HOP_SMALL` / `SQUASH` / `ROLL_IN` / `LEAN` | `HOP_SMALL` 需要 `canJump`；`ROLL_IN` 位移 ≤ 0.3 格且朝向容器中心 | `HOP_SMALL`：竖直冲量满足顶点 ≤ min(0.25·S, hopHeight)。`ROLL_IN`：`Motor.move` 加碰撞。其余只改姿态 | 片段时长到 → Done |
| **Hold** | 时长或条件 | 无 | 站稳；WP4 起检查坡度是否超过 `grip` | 条件满足 → Done；坡度超限 → `SLIPPED` |
| **Await** | 条件、最长 tick、等待姿态 | 最长 tick 必须 > 0 | 不移动 | 条件满足 → Done；超时 → `Blocked(NO_PROGRESS)` |
| **Grab** | 目标身体 | `canCarry`；目标不是 Airborne；目标未被他人 Held；嘴在 `reach` 内（`ContactGeometry.mouthGap ≤ reach`） | `LIFT_TICKS`（沿用 `ParentCarry`）的抬起姿态 → `Motor.mount(target, carrier)` | 成功 → Done；抬起期间目标移动 > 0.3 → `TARGET_MOVED`；挂载被拒 → `NO_CONTACT` |
| **Carry** | — | — | 不单独实现：持有 Held 时的 Walk / Swim 速度乘 `(1 − 0.5·被搬宽/搬运者宽)` | — |
| **Release** | 被搬身体 | 当前座位处的 AABB 无方块碰撞；下方 1.5 格内有支撑或水 | `Motor.dismount(child, child.position(), ZERO)` | 立即 Done。check 失败 → `NO_ROOM`，由方法转身或挪步；禁止 `moveTo` 落点 |
| **PlaceProp** | 帽 → 站位（世界坐标与朝向） | `HullFootprint` 放得下；托管为 `WORN` | 播 `hat_off`／`hat_off_self`，到 `release` 标记时调用 `HullFactory.setDown`；`HatCustody` 改为 `DEPLOYED` | 成功 → Done；放不下 → `NO_ROOM` |
| **TakeProp** | 自己的帽 | 帽的 `riders` 为空；嘴或辫在 `reach` 内；托管为 `DEPLOYED` | 播 `hat_on`／`hat_on_self`，到 `release` 标记时收回实体并改为 `WORN` | 成功 → Done；仍有乘员 → `NO_ROOM` |

水中动作（WP5）：

| 动作 | 执行要点 |
|---|---|
| **Swim** | 在水中每 tick `Motor.velocity`：水平分量朝目标、大小为 `swimSpeed`，竖直分量沿用 `WaterDistress` 的上浮模型；体力不足时 → `EXHAUSTED` |
| **Climb** | 需要 `ContactGeometry.canGrip(body, rimPoint)`，且高差 ≤ `climbRise`。分两段：先抓住（读 `grip` 标记）；再上拉，在 `pull` 标记时长内用 `Motor.move` 扫掠到帽檐内侧，到达后 `Motor.mount(host)` 并写入局部坐标。宿主倾角超过 `gripAngle(grip)`，或宿主速度大于 0.1 → `SLIPPED` |
| **Push** | 对目标调用 `push(dir·pushForce/质量比)`，质量取包围盒体积；帽船走 `HatBoatEntity.push` |
| **Right** | 需要与翻覆帽的帽檐接触；每 tick 扶正进度增加 `pushForce·k`，满 1 时帽改为 AFLOAT；期间执行者 Hold |
| **Paddle** | 需要 `OnHost(hull)`，且持有木棍（`Held` 槽位或储粮中的木棍）；调用 `HullHelm.thrust(vec, yawTorque)`，`HullHelm` 只做积分 |

---

## 10　ContactGeometry

```java
public final class ContactGeometry {
    public static Vec3 mouth(YukkuriEntity b);                 // 嘴尖世界坐标（ParentCarry 嘴道同源）
    public static Vec3 braidTip(YukkuriEntity b);              // 辫梢（BraidRescueGeometry 同源，WP5 并入）
    public static double mouthGap(YukkuriEntity b, Entity t);  // 嘴尖到目标包络的距离
    public static Optional<Vec3> nearestRim(YukkuriEntity b, SurfaceHost h); // 来自 h.rim()，已含倾斜
    public static boolean canGrip(YukkuriEntity b, Vec3 rimPoint);           // 距离 ≤ reach，且嘴或辫梢与帽檐点之间无方块遮挡
}
```

- **唯一来源**：所有够得着、咬得住、托得起的判定只调用这里。
- **WP5 合并对象**：`HullHazards.rimPoint/bites`、`CrewTransfer` 的 `holds` 与帽檐判定、`BraidRescueGeometry`、`CatchHat` 接触点。
- **客户端**：`ContactTargets` 读同步来的同一组点。

---

## 11　意图、方法与编排

### 11.1 接口

```java
public interface Intent {
    String id();                         // 如 "fetch", "get_onto", "unload"
    boolean satisfied();                 // 只读物理事实（Attach、Occupancy、位置）
    long deadline();                     // 绝对 tick；必须有限
    List<Method> methods();              // 有序；顺序可来自数据
    default Commitment commitment() { return Commitment.NONE; }
}
public interface Method {
    String id();
    Feasibility applicable();            // 只读
    Set<Reason> reduces();               // 能缩小的原因
    Step next();                         // 返回下一步；null 表示本方法已完成但意图未满足 → 视为 NO_PROGRESS
}
public sealed interface Step permits Do, Wait, Sub, AskFor {}
public record Do(Action action) implements Step {}
public record Wait(BooleanSupplier until, int maxTicks, String why) implements Step {}  // 内部转为 Await 动作
public record Sub(Intent intent) implements Step {}
public record AskFor(Ask ask) implements Step {}
```

### 11.2 IntentRunner 算法

```
tick():
  if intent.satisfied(): end(DONE)
  if now > intent.deadline() or attempts.exhausted(): end(EXHAUSTED, attempts.reasons())
  if step running:
     o = driver.last()（仅取本 runner 发起的动作）或子意图结果
     DONE        → step = method.next()
     Blocked r,s → attempts.fail(method, r, s, WorldSignature.of(...)); switchMethod(prefer reduces(r))
     Incapable   → attempts.incapable(method); switchMethod()
     Lost        → end(LOST)
     Interrupted → end(INTERRUPTED)              // 不自动恢复；需求仍在会重新产生意图
  else:
     m = 第一个 applicable 且 !attempts.excluded(m, signature) 的方法；没有则 end(EXHAUSTED)
     step = m.next(); driver.run(owner, step.action)
```

- **Attempts**：`maxSwitches` 默认为 6；`excluded(m, sig)` 表示该方法曾在相同签名下失败，或被标记为能力不足。
- **WorldSignature**：目标位置量化到 0.5 格，宿主位置量化到 0.5 格，加阻挡实体 id 集合，加时间桶（200 tick）。签名变化后，被排除的方法可以重选。
- **结束后**：交给领域后果规则，`[intent] end` 记录全部原因与差额。

### 11.3 Ask（WP3）

- **记录**：`Ask(requester, helper, intentFactory, incidentId)`。`AskLedger` 按 `(incidentId, helper)` 只抽一次，保存 chance、roll、accepted。不存档；事件对象自身需要存档的部分保留在 `WaterIncidents` 等原有位置。
- **执行**：帮手的领域 Goal 发现自己接受了请求，就以 `Commitment.SOFT` 运行对应意图。请求方用 `Wait` 等待结果，等待有上限。

### 11.4 Drive（WP3 起）

```
score(i) = clamp(desire(i), 0, 100) · weight(i) − costEstimate(i)
切换条件：score(new) ≥ score(cur)·(1+0.15) + 5，且连续 10 tick 成立
Commitment：NONE 正常切换；SOFT 需要 2 倍余量；HARD 只允许 DANGER 类欲望抢占（恐惧、溺水、受伤）
评估：每 10 tick 一次，按 entityId % 10 错开；入水、受击、受惊时立即评估
权重：data/mcyukkuri/drive/<domain>.json（热重载，同 8.2）
```

每个领域只注册一个 Goal（C4）。`[intent]` 选择日志记录前三名的得分。

### 11.5 Choreography（WP3）

```java
public abstract class Choreography {
    String id();  Map<String, UUID> roles();  String beat();
    void conduct();                    // 由“指挥者”组件的被动时钟推进时间型节拍（只改编排自身数据）
    Intent roleIntent(UUID role);      // 当前 Perform 节拍给该角色的意图；角色的领域 Goal 用 IntentRunner 执行
    void report(UUID role, Outcome o); // 角色结束时回报；由 Branch 决定下一节拍
    CompoundTag save();  void load(CompoundTag t);
}
```

| 节拍 | 作用 |
|---|---|
| `Perform(role, intent)` | 让某个角色执行一个意图 |
| `Present(role, clip, expression, sound, ticks)` | 播演出 |
| `Await(condition, ticks, onTimeout)` | 等条件成立，带期限与超时分支 |
| `Branch(outcome → beat)` | 按结果选下一个节拍 |

- **指挥者**：拥有编排实例的组件。LiveBirth 的指挥者是母油的 `FamilyLife`。
- **角色重绑**：每个节拍开始前复核角色存活、同维度、距离 ≤ 48 格。角色失效时进入编排声明的分支。
- **存档**：只保存节拍 id、角色 UUID 和领域规则要求的数据（第 12 节）。读档后，Perform 节拍从头运行该角色的意图。

---

## 12　存档与同步

| 数据 | 位置 | 格式 | 起始 WP |
|---|---|---|---|
| 宿主乘员局部坐标 | `HatBoatEntity` NBT `Riders` | ListTag `{UUID:int[4], X:double, Y:double, Z:double, Yaw:float}`（帽单位） | WP1（NEST），WP4（全部）；同时删除 `Deck` |
| 动作、意图、Attempts | 不存档 | — | — |
| LiveBirth 编排 | `YukkuriFamily` 下的 `Birth` | `{Beat:string, Roles:{Mother,Catcher:int[4]}, Receiver:{Kind:string, Host:int[4]}, Born:int}`；已抽定冲量沿用 `BirthLaunch.save` | WP3 |
| 能力、欲望参数 | 数据包 | 第 8.2、11.4 节 | WP2、WP3 |

同步：

| 槽 | 所属 | 类型 | 用途 |
|---|---|---|---|
| `RIDERS` | `HatBoatEntity` entityData | CompoundTag（同 NBT 格式，键为乘员 entityId） | 客户端 `positionRider` 读取局部坐标；只在变化时写，每 tick 至多一次 |
| `MOTOR_ACTION` | MOTOR 组件 SyncSlot | String | 当前动作 id |
| `MOTOR_START` | MOTOR 组件 SyncSlot | Long | 动作起始 tick（片段相位） |
| `ATTACH_KIND` | MOTOR 组件 SyncSlot | Byte | 0 Free、1 Airborne、2 OnHost、3 Held |

客户端经 `YukkuriView.motorAction()` / `motorStart()` / `attachKind()` 读取，禁止读服务端组件。

---

## 13　日志、读数与守卫

### 13.1 日志模板

日志用 `GameplayLog.event(entity, category, …)`，category 取前缀名，不带方括号。

```
[action] start id=hop from=x,y,z to=x,y,z apex=0.42 vy=0.36 vh=0.11 ticks=9 ability=h0.80/r1.00/s0.60 owner=<goal> fp=<8hex>
[action] end id=hop outcome=DONE at=x,y,z peak=0.41 airTicks=9
[action] end id=walk outcome=BLOCKED reason=NO_PROGRESS shortfall=0.03 window=40 navDone=false nodesLeft=5 owner=<goal>
[action] refused id=grab owner=<goal> current=hop reason=not_interruptible          （限频 200）
[method] pick intent=fetch method=grab_then_walk attempts=0/6
[method] switch intent=fetch from=hop_from_here reason=TOO_FAR shortfall=0.40 to=walk_closer_then_hop
[intent] start id=unload host=<uuid> source=birth_catch deadline=+1200 top=[unload:72, comfort:40, rest:12]
[intent] end id=unload result=EXHAUSTED reasons=[NO_ROOM:0, NO_CONTACT:0.3]
[body] mount host=<uuid> local=x,y,z cause=launch_contact
[body] dismount at=x,y,z v=x,y,z cause=release|slip|thrown|host_removed
[body] invariant id=I2 detail=…                                                       （WARN）
[choreo] beat id=live_birth beat=rendezvous role=catcher outcome=DONE next=place_hat
```

- **频率**：开始、结束、切换、挂载与卸下都记 INFO。逐 tick 采样只在 `GameplayLog.detailEnabled()` 时记。
- **E11-3 用的采样**：Walk 每 20 tick 记一次位置、导航状态、剩余节点和当前所有者，键为 `walk.sample`。

### 13.2 读数

`StatusKey` 新增分组 `body`：

| 键 | 内容 |
|---|---|
| `body.ability` | 各项能力值与参数指纹 |
| `body.attach` | 依附状态、宿主、局部坐标 |
| `body.action` | 当前动作 id 与所有者 |
| `body.last` | 最近一次的 Outcome、原因与差额 |
| `body.intent` | 当前意图 |
| `body.method` | 当前方法 |
| `body.attempts` | 已用和剩余预算 |

### 13.3 架构守卫（WP7 写进 `check_architecture.py`，此前由评审人工执行）

| 规则 | 内容 |
|---|---|
| `BODY_WRITE` | `motor/` 以外，对 `YukkuriEntity` 类型的接收者调用第 4 节所列写方法 |
| `BASE_IMPORTS_DLC` | `subspecies/`、`client/aquatic/`、`registry/`、`CommonRegistration` 以外的包 import `subspecies.` |
| `GEOM_PURE` | `motor/geom/` import `net.minecraft.` |

---

## 14　工作包实施细则

> 通用规则：
> - **登记与独占**：开工在待办“进行中”登记工作树 `.worktrees/wpN`；核心文件（`YukkuriEntity`、`HatBoatEntity`、`motor/`）同时只允许一个工作包修改。
> - **删除**：删除栏必须在本包内完成。
> - **提交说明**：写清新增、删除、过渡、实际做过的检查、受影响场景与观察点。
> - **待办**：改为“已接入待试玩”。
> - **规模**：净行数应下降（WP1 因为新建框架，允许持平）。

### WP1　依附、容器、跳、陆地叼搬放（E11-1、E11-2）

**步骤**
1. **盘点**：用 grep 列出以下调用点，写进提交说明，作为改接清单。
   - “在帽里”判定：`getPassengers()`、`hasPassenger(`、`ownYoungAboard`、`deck().moving`、`placed.aboard`、`HatBoatEntity.protects`、`catches()`；
   - “在窝里”判定：`NestResting`、`homeStand`、`TemperatureLife` 的离格心判定、入睡起始条件。
2. **新建 `motor/` 基础**：`Motor`、`Attach`、`AttachState`、`Outcome`、`Reason`、`Feasibility`、`Progress`、`MotionPose`、`BodyDriver`。
   - 在 `YukkuriComponents` 登记组件 `MOTOR`（READY）：带打断桥接与同步槽。
   - 在 `YukkuriEntity.travel` 开头接入 `tickTravel`。
3. **新建 `intent/` 核心**：`Intent`、`Method`、`Step`、`Attempts`、`WorldSignature`、`IntentRunner`、`MotorOwner`。
4. **容器判定**：`geom/FloorShape`、`geom/OccupancyMath`，以及 `surface/Container`、`Occupancy`、`BedContainer`、`HostContainer`。单元测试见 14.8。
5. **宿主**：`surface/SurfaceHost`、`HostFrame`、`Riders`；`HatBoatEntity implements SurfaceHost`。
   - 加入 `usesDeck()`：NEST 为 false。
   - NEST 走 `Riders` 存档与 `RIDERS` 同步，`positionRider` 走新路径。
   - `addPassenger`／`removePassenger` 维护 `Riders`。
   - NEST 的 `boardYoung`／`reserve`／`makeRoom` 改为 `room()` 加 `Riders`，不调用 `deck`。
6. **动作**：Grab、Release、Face（最小版，限速转身）、Hop（地面与宿主之间）。
   - Hop 需要 `geom/Ballistic`、`geom/ArcSolver`、`ContactResolver`、`ClipMarkers`（泛化 `CrewClips`），以及最小版 `BodyAbility`：只有代码默认值，采用 Q1 系数，JSON 留到 WP2。
   - 把 `ParentCarry.tickPickup` 的抬起部分改为 Grab，把 `putDown` 改为 Release。
   - 删除 `putDown` 中的 `moveTo(HatCarry.landing)`；碰撞时 Release 返回 `NO_ROOM`。
   - 打断时 `interrupted` 改为 Release 并在实际位置落定。
7. **改接收尾**：接生帽收尾改成意图 `Unload(hat)`，由接生者现有的 FETCH_HAT 任务所在 Goal 运行。逐只处理：
   - 会跳的孩子：由孩子自己的领域 Goal 发起 `GetTo(ground)`，执行 Hop。触发条件是读取宿主的 `unloadRequested()`，它由帽主当前运行的意图推导而来，不另存标记；
   - 不会跳的孩子：执行 `Fetch(child → ground)`，方法顺序为：
     1. 走到 `SurfaceApproach.beside` 站位 → Grab → 走到最近的可支撑地面 → Release；
     2. 让孩子沿帽内路径走到靠接生者一侧的边缘（沿用用户设计），再执行 1；
     3. 在 `Wait`（期限 200）之后重试 1。
   - 所有孩子离帽后，沿用现有收帽逻辑（WP3 换成 TakeProp）。
8. **入窝**：入睡、保暖避险、到窝都改为要求 `INSIDE_SUPPORTED`；否则先 Walk（WP1 用 `SurfaceApproach.walk`）到 `NestResting.choose` 给出的休息点。
9. **日志与读数**：接入 `[action]`、`[intent]`、`[body]`，以及 `body.attach`／`action`／`last`。
10. **测试依赖**：`build.gradle` 加 JUnit（14.8）。

**删除**
- `CatchHat.tickFetch` 循环与按乘员计数推进的逻辑；
- NEST 宿主上的 `CrewTransfers.releaseYoung` 调用；`CrewTransfer` LIFT 与倒放回座在 NEST 宿主上不再可达，加 `usesDeck()` 守卫，代码随 WP5 整体删除；
- 陆地 `BraidCarry` 会话的调用；
- `ParentCarry.putDown` 的 `HatCarry.landing` 瞬移；
- 第 1 步盘点出的重复“在窝里”判定。

**过渡**：`usesDeck()`，WP4 删除。

**禁止**：
- 为 NEST 另写座位或预约；
- Release 失败时把孩子放回帽里；
- 在 `Unload` 里判断场景名。

**复测**：

| 场景 | 观察点 |
|---|---|
| `birth_land_caught` 收尾 | 每只赤油各有一条 `[body] dismount cause=release`；之后不再出现 `mount host=<接生帽>`；全部离帽后才收帽 |
| 宠物窝入睡 | 入睡日志带 `occupancy=INSIDE_SUPPORTED`；站在窝沿上的个体先走进去再睡 |

### WP2　能力表数据化与运动统一（E11-3）

1. **能力表**：`BodyAbility` 数据化，加 `AbilityTable`（JSON 加热重载）和 `AbilityModifier`；`YukkuriLocomotion.limits` 改为转调 `BodyAbility`，并移除其参数和函数体中的 `HatBoatEntity`（载重系数由调用方传入）。
2. **动作**：Walk（地面，带看门狗与 `walk.sample`）、Face（完整版）、Launch、Gesture；Hop 改读数据化的能力表。
3. **改接新生儿飞行**：`FamilyData.birth` 按 `BirthLaunch` 的计划执行 Launch。
   - 删除 `FamilyFlight` 的 `beginFlight`、`travelFlight`、`catchFalling`、`launchPast`、`pending*`、`restoredFlight`、`cancelFlightAdmission`、`rebuildBirthAdmission`；
   - `BirthFlightPath` 的扫掠并入 `ContactResolver` 与 `Launch.preview`。
4. **改接小动作与转身**：
   - `LiveDelivery` 的 `bump` 与迎接小跳、`FamilyLife` 的窝沿滚动 → Gesture；
   - `FamilyLife.face`、`recoveryBeats` 的 5°/tick 转身、`advanceLiveTick` 的转身预算 → Face。
5. **E11-3**：先用 `walk.sample` 读数定位争抢移动的 Goal 或寻路原因，再在 Walk 或所属层修复。

**复测**：`birth_land_caught`／`birth_land_missed`（落帽率、摔伤表现与旧版分布一致）；`hop`；`infant_feed`（途中无 `NO_PROGRESS`）。

### WP3　意图框架完整版与 LiveBirth 编排

1. **框架**：`intent/` 补齐 Ask、AskLedger、Drive、Desire、Commitment、Choreography、Beat、Role、Conductor；新增动作 PlaceProp、TakeProp；新增共享方法 `Rendezvous(a, b, siteSelector)`，即双方 `GetTo`+`Face`，只有一个期限。
2. **LiveBirth 编排**：节拍见整体设计第 7 节；接收者为 `Receiver` 接口，实现有 `PlacedHat`、`OwnHull`（WP6 接入）、`Nest`、`Ground`。
   - 母油与接生者都由 `BirthGoal` 承担角色（priority 2；`canUse` 条件为“在某个进行中的 LiveBirth 中有角色”）。
   - 产程时钟由母油的 `FamilyLife` 指挥者推进。
3. **删除**：
   - `CatchHat` 的会合协商、站位桥接、逐 tick 复验与 `seekMother`；
   - `LiveDelivery` 的接生窗口与出窝握手；
   - `FamilyData` 的出生预约（`reserveBirthAdmission`、`waterCradle`、`prepareWaterBirthCargoSpace`），四条发射分支合为一条；
   - BROOD Goal 的分支；
   - `exitWait`、`stationWait`、`catchWindowBudget`、`rejectSite`、`siteStalled`、取帽 600+100、`releaseWait`、`cradleLinger`；
   - 陆地路径上的 `boatHome()` 分支（水上分支保留到 WP6）。

**复测**：F1 全部出生场景（`birth_land_caught`、`birth_land_reunion`、`birth_land_missed`、`birth_nest_safe`、`birth_nest_stem`、`birth_stem_caught`）；观察 `[choreo]` 节拍链与分支。

### WP4　帽船宿主化（前置：用户确认 HatScale 预览）

1. 所有模式走 `Riders`；删除 `usesDeck()` 与 `positionRider` 的座位路径。
2. 宿主上的 Walk 改用 `HeightGrid` 加 `GridPath`（14.8 有测试）；`room` 计入储粮圆；挤不下时沿坡度滑向帽檐，越过帽檐就 `dismount` 加 Launch（Q6）。
3. **物理**：
   - 删除 `tickServer` 中 `selfRescueHeld` 与 `selfRighting` 分支的速度清零和提前 return，浮力无条件执行；
   - `isPushable()=true`，被推时按质量比位移（Q5）；
   - 水中身体与帽外盒子之间做分离。
4. **尺寸**：`HatScale.of(owner)` 统一四种形态的尺寸（Q3）；`HatVessel.hatScale` 与 `SelfRescueHatGeometry` 都改读它。
5. **姿态**：`CrewPoseTable` 改为按地名或支撑高度取姿态。
6. **删除**：`HatDeck` 的席位、租约、冲突与对账；`DeckLayout` 座位表与冲突矩阵；`HatStations`；`HatBoatCrowding`；`CrewState` 的座位移动；`CrewTransfer` HOP；`Deck` NBT。

**复测**：`hat_swap`、`cargo`、`sail` 登船段，以及推船和挤落水。

### WP5　水中动作

1. 新增 Swim、Climb、Push、Right、Paddle；`ContactGeometry` 合并四处帽檐几何。
2. **删除**：
   - `AquaticSelfRescue` 的移动与阶段机（后果规则保留）；
   - `WaterDistress` 的重复推进；
   - `HullHazards` 的释放脚本（改为 `dismount` 加 Launch）；
   - `CrewTransfer`、`CrewTransfers`、`CrewPort`、`CrewTransferGoal`、`CrewState`、`BraidCarry` 整体删除；
   - `YukkuriEntity.travel` 中的 `crew().holdsBody()` 钩子。

**复测**：`self_rescue`、`shore_capsized`、`shake_capsize`。

### WP6　水上意图

1. **意图**：单一 `AquaticGoal`（Drive 在其内部运作），加 `drive/aquatic.json`。意图包括出航、上岸、夜泊、补给、照料、`ResolveOwnHat`、救援、初航、送赤油到父船。出航与上岸为 `Commitment.HARD`。
2. **帽船分娩**：接入 LiveBirth 的 `OwnHull` 接收者；删除水上的 `boatHome()` 分支。
3. **删除**：`VoyageController`、`VoyagePhase`、`AfloatAgenda`、`AfloatActivities`、`HatFamilyAlarm` 的 SAFETY HOLD 分支、`FirstVoyage` 阶段机、`HullHelm` 租约、`WaterIncidents` 的移动部分。

**复测**：A1–A8 全部场景，以及待办中的 P0 观察点。

### WP7　收口

- 写入 13.3 的三条守卫规则，并清零基线；
- 删除 `PresentationArbiter` 中被动作层取代的接管来源；
- 改写 ARCHITECTURE 相关行与[水生魔理沙](AQUATIC_MARISA_IMPLEMENTATION.md)；
- 删去整体设计中已成为现行规则的内容。

**复测**：E8–E11 全量复测。

### 14.8 单元测试

**依赖**：`build.gradle` 增加以下配置。

```groovy
dependencies {
    testImplementation 'org.junit.jupiter:junit-jupiter:5.10.3'
    testRuntimeOnly 'org.junit.platform:junit-platform-launcher'
}
tasks.named('test') { useJUnitPlatform() }
```

测试放在 `src/test/java/io/github/longshou/mcyukkuri/motor/geom/`，只测 `geom/`（纯 Java）。

| 测试 | 必须覆盖 |
|---|---|
| `OccupancyMathTest`（WP1） | 正中、紧贴内壁、骑在窝沿、一半在外、悬空（无支撑）、高于窝沿；以及圆角矩形四角的边界 |
| `BallisticTest`（WP1） | 与 `BirthLaunch.airTicks`／`speedFor` 结果一致（同输入同输出）；离散积分与解析近似的误差 < 0.02 格 |
| `ArcSolverTest`（WP1） | 平地 0.5 格返回最低弧；`dy=0.9` 时 `TOO_HIGH` 差额为 0.1；`dx=1.3` 时 `TOO_FAR` 差额为 0.3；有障碍时顶点升高；障碍超过 `hopHeight` 时报 `TOO_HIGH` |
| `AbilityFormulaTest`（WP2） | 成体满体况时 `hopHeight=0.80`、`hopReach=1.00`；`energy<8` 时跳跃为 0 |
| `GridPathTest`（WP4） | 高差超过 `stepUp` 的格不连边；绕过不可走格；起点与终点不连通时返回空 |

---

## 15　常见误解

| 误解 | 正确做法 |
|---|---|
| Carry 要新写一套托举系统 | 不用。Held 就是原版骑乘（`ParentCarry` 已有）；Carry 是“持有 Held 时的 Walk” |
| 失败后把孩子放回原位，免得掉水 | 禁止。按实际位置落定，掉水由落水规则处理 |
| Blocked 之后立刻再 `run` 同一个动作 | 禁止。只有 `WorldSignature` 变化后才能重选同一方法 |
| Await 可以等到条件成立为止 | 禁止无限等待。每个 Await 都有最长 tick 与超时分支 |
| 产程推进由被动时钟负责，算不算写状态 | 被动时钟可以改本组件的数据（产程进度、编排节拍），但不得移动身体、不得调用 `run`、不得改其他实体 |
| Attach 要单独存档 | 不存。它由原版载具关系加宿主 `Riders` 推导 |
| 为了接住新生儿，可以修正冲量或速度 | 禁止。冲量只抽一次，接触决定结果 |
| 客户端自己判断乘员在不在帽里 | 禁止。客户端只读 `RIDERS`、`ATTACH_KIND` 与动作同步 |
| 方法里写 `if (scenario == ...)` 或 `if (aquatic)` | 禁止。差异通过接收者、宿主、能力修正或数据表达 |
| 把新动作的高度和时长写成常量 | 禁止。高度与距离来自 `BodyAbility`，时长来自物理或 `ClipMarkers` |
| `geom/` 里用 `Vec3` 更方便 | 禁止。`geom/` 只用自带 record，保证单元测试无需 Minecraft |
| WP 做到一半，先留着旧路径以防万一 | 禁止。只允许本文明写的过渡（`usesDeck()`、`travel` 遗留钩子），并在指定 WP 删除 |
