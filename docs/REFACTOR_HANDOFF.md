# 代码结构整理：架构诊断与交接安排

2026-10-18 依据对 `b3616c5f`（origin/main）的 9 路只读审计写成。本页是调度用的交接单：每个工作包可以单独派给一个 agent，做完在第 5 节进度表里登记。全部完成后，本页压缩为一份代码架构说明，约定写进 `AGENTS.md`。

代码路径相对于 `src/main/java/io/github/longshou/mcyukkuri/`。行号以 `b3616c5f` 为准，有偏移时按符号搜索。

## 0. 已确认的用户裁决

2026-10-18 用户确认 D1–D8 全部采用下表推荐，已登记到 [设计裁决](DESIGN_DECISIONS.md#2026-10-18-重构裁决)。执行模型映射：Opus → `gpt-6-astra`，Sonnet → `gpt-6.1-sol`；推理强度沿用包内安排。未标强度的任务由统筹评估。

| 编号 | 问题 | 推荐 | 影响的包 |
| --- | --- | --- | --- |
| D1 | 伴侣死后能否再配对。`FamilyLife.partner` 只在 `family/FamilyLife.java:972`、`:1643` 赋值，从不清空；`canReproduceWith`（`:1007`）和求偶筛选（`:1420-1421`）拒绝已登记伴侣以外的所有对象，所以丧偶个体终身不能再繁殖 | 能再配对；原伴侣 UUID 保留，供悲伤与遗体识别使用 | W0-2 |
| D2 | 烧伤算不算“带伤”。`BodyLife.wound()` 不含焦痕（`body/BodyLife.java:73`），繁殖（`FamilyLife:943`、`:981`）、分娩（`:1064`）和玩耍（`play/PlayLife.java:27`）都只查 `wound()==0`，所以重度烧伤仍可繁殖、玩耍 | 算：烧伤与皮伤同口径，挡繁殖和玩耍 | W1-D |
| D3 | 重构包走 PR：推分支开 PR，CI（已对 PR 触发）通过、需要复核的包复核通过后再合入 main。现行 `AGENTS.md` 是直接推 main | 重构期间走 PR，玩法改动照旧 | 全部 |
| D4 | 每次自动存档都给每只个体全量计算四份调试视图（`YukkuriStatusView`、`YukkuriDebugGoals`、`YukkuriForageView`、`YukkuriHabitatView`，`entity/YukkuriEntity.java:1135-1148`），Java 代码不读它们，只有试玩场脚本和文档用 `/data get` 读 | 改成配置开关，默认关闭，试玩场打开 | E1 |
| D5 | 显示链的顺序与目标优先级不一致，有 7 个目标运行时不进显示链（见 3.3） | 按优先级重排并补齐，试玩确认 | E4 |
| D6 | 同一概念阈值不一致：“严厉”是攻击性 ≥55 或 ≥65；“信任的玩家”是恐惧 <25、<30 或 <40；好感档有 0/4/8/10/12/15/−10/−20；不安有 30/50/60 | W1 完成后出对照表；授权按多数口径统一，个别保留差异的写明理由 | W1-D、R2 |
| D7 | NPC 主人死亡后，名下个体永远挂在死去的 UUID 上：仍受保护、不能捕获、永不转野生（`human/HumanNpc.java` 的 `die` 只关店） | 转为无主：宠物转野生，库存与工厂个体变为可捕获 | R6 |
| D8 | 聚落把不认识的 UUID 当成成员（`settlement/SettlementData.java:87`）；死者的好感边和成员记录永不清理 | 不认识的返回 FREE；确认死亡后清理 | R1 |

## 1. 诊断摘要

规模：388 个 Java 文件，约 6 万行，9 月 11 日到 10 月 18 日共 726 次提交。`entity/YukkuriEntity.java` 1387 行，60 天里改了 78 次，230 个文件依赖它。

根因有四条。

1. **功能都靠中心实体手工接线。**
   - 约 35 个 `*Life` 组件挂在实体上，加一个功能要改实体 8 处：字段、初始化、访问器、tick、行为链、`die`、`save`、`load`。
   - 初始化分散在字段、构造器、`registerGoals` 三处，因为 `registerGoals` 在 `Mob` 构造器里运行，早于子类字段初始化和 `variant` 赋值。
2. **没有共享的规则层。**
   - “带伤吗”约 15 处，有 4 种口径。
   - “是不是亲属”至少 8 种写法。
   - 性格阈值约 90 处字面量。
   - 物种分支约 300 处。
   - “现在空闲吗”由每个功能自己列清单。
3. **三套仲裁并存。**
   - Goal 优先级决定移动。
   - `aiStep` 的 9 段提前返回加 23 层三元表达式决定显示。
   - 各功能自列“别人在忙我就不做”的清单，而且互相对不上。
   - 打断靠 5 份互不一致的停止列表。
   - 行为 ID 是约 250 个裸字符串，拼错只能在试玩时发现。
4. **生命周期有缺口。**
   - 关系分散在约 9 处。
   - 个体死亡或被移除时清理不全。
   - 客户端读服务端组件，拿到的是默认值。
   - 静态 UUID 表在单人切换世界时不清空。

**保留的好设计（重构不得破坏）：**

- 服务端拥有阶段时钟并同步起始 tick，客户端不依赖帧率。通用代码不引用 `client/`。
- 每个功能一个组件、一个存档键；Goal 只是薄适配器。
- 伤口：
  - 挂在皮面三角形加重心坐标上；
  - `InjuryModel` 把分析和裁决分开；
  - 馅料要等世界接受掉落物后才扣；
  - `WoundMigration` 处理模型网格变化。
- 情绪与状态：
  - `MindLife` 是不安值的唯一来源；
  - `relate()` 是好感唯一的写入口；
  - `StatusKey` 只读组件，写入走真实逻辑。
- 物种：
  - `SpeciesTraits` 是纯数值登记；
  - `SubspeciesLife.kind()` 在客户端改读同步标志，是客户端安全读法的范例。
- 宠物与商店：
  - 捕获令牌带回滚；
  - `PetLife` 有锁和看门狗；
  - 考试项可插拔；
  - `LeashGuard` 只在服务端运行；
  - 菜单的对象列表由服务端固定，按钮包不带实体 id。
- 世界层：
  - `HouseholdEcology` 有扫描预算，并在维度间轮转；
  - “未加载不等于死亡”，死亡只由家族账本确认；
  - 聚落场地只存规划输入，布局按确定方式重新推导。
- 已有的委托拆分写法：`HatBoatCargo`、`HatBoatCrowding`、`AquaticLanding`。

## 2. 所有工作包共同遵守

1. **行为不变**，除非包名标了 ⚠。⚠ 包只改该节写明的行为。
2. **存档键不改名，保留读取兼容。**
   - 试玩场 DSL 直接写原始 NBT（`scripts/museum_scenarios.py`、`scripts/museum_dsl.py`）。键一改名，场景就静默失效，`check_museum_pack.py` 也发现不了。
   - 拆分时把 `put` 段原样搬进委托，写回父 tag 的同一位置，不新建子 compound。可以新增键，但必须保留读旧键的兼容。
   - 提交前比对键：

     ```
     git show origin/main:<原文件> | grep -o 'put[A-Za-z]*("[A-Za-z]*"' | sort > .local/keys-before
     cat <原文件> <新委托文件…> | grep -o 'put[A-Za-z]*("[A-Za-z]*"' | sort > .local/keys-after
     diff .local/keys-before .local/keys-after   # 只允许出现新增的键
     ```

   比对使用该 PR 的固定基线，不能随 `origin/main` 移动；还须人工核对完整 NBT 路径、类型和读旧档行为。仅有以下明确例外：E1 四份调试视图按配置写入、`SukkiriExhaustion` 转瞬态；R3 停写 `YukkuriBody.Wound`、`YukkuriBody.Burns`，保留原来的旧档读取和从 `Wounds` 重建语义，不新增仅凭旧数字造伤口的迁移。

3. **不本机编译。** CI 是唯一的编译检查，所以 PR 要小：一个 PR 只做一个包里的一步。热点文件近三周改了 38–56 次，合得越快冲突越少。纯搬移用 `git diff --color-moved=zebra` 自查。
4. **工作树与流程。** 在 `.worktrees/<包ID>` 里基于 `origin/main` 开分支 `refactor/<包ID>`，推分支并开 PR（D3）。CI 通过、需要复核的包复核也通过后，由统筹协调合入。未发布的提交可以 rebase 到最新的 `origin/main`；已发布分支需要更新基线时合并最新 `origin/main` 并重新检查 CI，避免改写远端历史。实施者不并发合入，不修改共享进度表；进度由统筹更新，W1-API 仍拥有附录 A。
5. **文件所有权。** 每个包只改它列出的拥有文件；需要改别人的文件时停下回报，不顺手改。`entity/YukkuriEntity.java` 同一时间只有一个拥有者，见第 5 节“实体锁”。
6. **初始化顺序陷阱。** 在 `registerGoals` 里赋值的字段不能带初始化器，连 `= null` 也不行，否则构造器返回后会被覆盖。组件的构造器里不能读 `variant`。
7. **新代码的写法：**
   - 用 import，不写内联全限定名；
   - 阈值用第 3 节的命名常量，不新写字面量；
   - 客户端只读同步数据（3.6）。
8. **提交信息**写明 `行为不变` 或 `行为变更：…`，以及 `试玩：<场景 id>`（场景 id 见 `scripts/museum_scenarios.py` 的 `TOPICS`；聚落类场景以 `settle_` 开头）。用户按波次集中试玩，出问题就整个 PR 回退。
9. **卡住就换人。** 同一问题三轮 CI 失败，或超过约一小时没有进展，就停下回报。调度方另派一个 Opus 诊断、一个 Opus 复核，不让原 agent 继续磨。

## 3. 目标架构

### 3.1 组件登记（E1）

实体只保留一张有序的登记表，不再手写 tick、save、load 列表。旧组件不改文件，用适配器登记，这样第 2 波只动 `entity/` 目录。

```java
// entity/component/YukkuriComponent.java
public interface YukkuriComponent {
    /** 存档键，与现在逐字相同（如 "YukkuriFamily"）；不存档返回 null。 */
    @Nullable String saveKey();
    default void tick() {}
    default boolean active() { return false; }
    default String activity() { return Behaviors.IDLE; }
    default void interrupt(Interrupt reason) {}
    default @Nullable CompoundTag save() { return null; }
    default void load(CompoundTag tag) {}
}
// entity/component/YukkuriComponents.java：tickOrder()、saveOrder()、loadOrder() 三张表，
// 顺序逐项照搬 aiStep 里的那一行 tick 调用（:674 起）和 addAdditionalSaveData / readAdditionalSaveData（:1116-1197）。
// 旧组件用适配器登记：Legacy.of("YukkuriFamily", family::tick, family::save, family::load, family::active, family::activity, …)
```

- **隐藏的读档耦合照旧，并在表里注明：**
  - `life.load` 会顺带读越冬数据（`life/YukkuriLife.java:1006`）。
  - `subspecies.load` 会顺带读水生数据（`subspecies/SubspeciesLife.java:167`），而且必须最先读，因为其他组件要读 `kind`。
  - `mind.load` 同时读 `YukkuriPet`。
  - 捕食种的数据只对捕食种存、读。
  - `YukkuriFamily`、`YukkuriHouse`、`YukkuriBody` 读之前先判断键是否存在。
- 实体存档新增 `YukkuriVersion` 键，读不到视为 0。
- **完成标准：** 新增组件只需要改组件类和登记表。

### 3.2 打断协议（E2）

```java
// entity/component/Interrupt.java
public enum Interrupt { FRIGHT, HURT, BURNING, COMMAND, PET_LOCK, DEATH, REMOVED }
// entity.interrupt(reason)：按一张“原因 → 要停的组件”表执行。
```

表的初值必须逐项等于现在这几份列表；差异照抄，不在 E2 里顺便统一。

| 现在的位置 | 停止的组件 |
| --- | --- |
| `frightenFrom`（`YukkuriEntity:357-364`） | agitation、`operations.end()`、groupInteraction、dailyLife、woundComfort、playLife、social、`life.interrupt()`；另设惊慌 100 tick，调用 `life.involuntaryRelease("fright", false)`；妙姆先判 `myon.confront` |
| `playAction`（`:612-614`） | `shellRetraction.reset()`、agitation、`operations.end()`、groupInteraction、dailyLife、woundComfort、playLife、`life.interrupt()`、social |
| `hurt`（`:1003-1014`） | agitation、groupInteraction、dailyLife、woundComfort、playLife、`life.interrupt()`、social；operations 只调 `damaged(source)`，不结束；内部伤害和淋湿（`:1001`）直接返回，不打断 |
| 着火（`aiStep`） | `life.interrupt()`、social，并清掉命令动作 |
| `die`（`:1026-1053`） | 按代码照抄，含 `deathRecorded` 只执行一次的那一段；现在 Sanction、Cannibalism、CareApproach、predator 没有被停 |
| `PetLife.lock`（`pet/PetLife.java:292-297`） | 按代码照抄 |

### 3.3 显示仲裁（E3、E4）

`aiStep` 里决定“显示什么”的代码（`:711-845`）原样搬到 `entity/presentation/PresentationArbiter`，改写成一张有序的来源表：

```java
interface PresentationSource { @Nullable Presentation present(PresentationAccess state, Motion motion); }   // 返回 null 表示不接管
// E3 实际使用延迟执行的显示块，选中后在原位置立即执行一次。
record Presentation(Runnable display) {}
```

**E3 照搬现在的顺序，不改：**

1. 前置接管：辫救 → 帽船摇晃与拥挤 → 登帽 → 水生自救 → 落水 → 帽船警报 → 惊慌 → 缩壳 → 发情演出 → 命令动作到期。
2. 行为链：mind → reactions → predator → sanction → 在水中 → life → careApproach → childhood → aquaticLife → groupInteraction → headwear → playLife → woundComfort → dailyLife → WildEvents → family → house → habitat → independence → settlement.duties → settlement → social → family（兜底）。
3. 动作选择：命令 → 帽船 → 水中 → 行走 → 群体 → 静止。
4. 起始 tick 校正（求偶、分娩与助产），最后定表情。

调试视图新增一项“本 tick 胜出的来源”。

E3 保留逐项写入与查询的先后关系：表情可能读取刚写入的行为与起始 tick，惊慌与缩壳的写法也不相同，所以不能预先计算所有候选的字段。来源返回延迟显示块，仲裁选中后立即执行；调试信息记录行为胜者，命令或物理层仍可覆盖动作。

**E4（⚠，D5）：**
- 顺序与目标优先级对齐。现在群体（5）和玩耍（6）排在头饰（3）和安抚（4）前面；日常（5）排在家油和栖居（4）前面。
- Cannibalism、SelfOffering、TreasureLife、ForageTrip、ForageGoal、HomeClaimGoal、FactoryWorkGoal 补上 `activity()` 并接入显示链。
- 接上 `WinteringLife.activity()`（`season/WinteringLife.java:38`）。
- 普通 Goal 来源按实际 priority 和同级注册序选择，只读当前运行的同一 Goal 实例，不重排 AI 注册，也不调用 `canUse/canContinue` 来查询显示。Reactions 的威胁与天气、安抚的接受与主动、聚落的迁徙与普通返回分别绑定各自 Goal。
- 保留进食、病态、出生与接触等非 Goal 持续状态，以及 E3 前置物理层的时序。`ForageTrip.stop` 保留阶段，`ForageGoal` 可持有未启动目标，这些字段不能单独证明当前拥有显示。
- 七来源区分真实阶段、等待与停止；停止返回 null，真实等待可以有行为 ID 而动作保持静止。不得为显示新增采收、献身或加工阶段，不改变 AI、物资、伤害与存档。群体专用动作由胜出的群体来源拥有；捕食的真实飞行姿态仍保留。

### 3.4 行为 ID 常量（W1-API）

`entity/Behaviors.java` 收录全部行为 ID 的 `String` 常量，值与现在逐字相同，所以同步、存档、客户端选片段都不变。

- **动态拼接的 ID 给构造方法：**
  - `hatBoard(phase)`，对应 `"hat_board_"+phase`；
  - `event(card)`，对应 `"event_"+card`；
  - `aquatic(id)`，对应在后面加 `"_aq"`。
- **来源：**
  - 36 个 `activity()` 方法；
  - `social/EcologyPresentation` 的分支标签；
  - `client/YukkuriModel.java:740-753` 和 `client/YukkuriRenderer` 里的比较；
  - `setBehavior` 的字面量。
- **目的：** 拼错变成编译错误。CI 只做编译，这是唯一能自动抓到拼写错误的地方。

### 3.5 共享查询层（W1-API）

三个新入口的初值都逐个复刻现有口径。同一用途口径不同的，先各留一个命名方法，标 `// 待统一（D6）`，迁移时不合并。

```java
// body/BodyCondition.java   —— BodyLife.condition() 返回的快照，带按用途命名的判断方法
public record BodyCondition(int wound, int burns, int infection, int mold, int filling,
                            boolean weak, boolean dying, boolean sick, boolean footBurned) { … }
// body/BodyConstants.java   —— MAX_FILLING = 12、WOUND_CAP = 8、HOUSE_WOUND_SIZE = .35f、HOUSE_SOURCE_SCALE = .32f、足底高度带

// social/Bonds.java
public enum Kin { BIO, CARE, MATE, HOUSEHOLD, GROUP }
static boolean kin(YukkuriEntity a, YukkuriEntity b, Set<Kin> kinds);
static boolean trustedPlayer(YukkuriEntity y, UUID player);
// social/BondLevels.java    —— 好感、信任、恐惧、不安各档的命名常量（不安引用 MindLife 已有常量，如 SWEETENED）
// social/Temperament.java   —— harsh、lenient、protects 等性格规则
```

**已知需要复刻的口径**（W1-API 补全后写进附录 A）：

- **带伤：**
  - 皮伤＋感染：`FamilyLife:943`；
  - 再加霉斑：`human/FactoryPipeline.java:32`；
  - 再加烧伤和霉斑：`season/AutumnStock.java:42`；
  - 再加烧伤：`social/WoundComfort.java:124`。
- **亲属：**
  - `social/GriefLife.java:62` 与 `headwear/Sanction.java:108` 相同；
  - 其余各不相同：`social/Cannibalism.java:87`、`status/Reactions.java:80`、`social/WoundComfort.java:149`、`mind/MindLife.java:265`、`settlement/CommunityRules.java:231-233`、`event/WildCards.java:46-53`、`social/DailyLife.java:85`。
- **信任的玩家：**
  - 信任 ≥10 且恐惧 <25：`DailyLife:95`、`event/WildEvents.java:270`、`item/CarrierItem.java:60`；
  - 恐惧 <30：`social/YukkuriSocial.java:64`；
  - 恐惧 <40：`interaction/OperationLife.java:78`。
- **严厉：**
  - 攻击性 ≥55：`headwear/Contempt.java:48`；
  - 攻击性 ≥65：`settlement/SettlementSociety.java:31`、`CommunityRules:162`。
- **不安：** `life/SpeciesLife.java:128`、`pet/PetCare.java:225-234`、`shop/ShopPricing.java:24`、`shop/ShopMenu.java:201`、`pet/social/BadgeArcPolicy.java:73` 写死了 60、50、30。

### 3.6 客户端视图（W1-API 定义，W1-M6 采用）

- `entity/YukkuriView` 是 `YukkuriEntity` 实现的接口，只暴露同步数据和由同步数据推出的值，例如 `adult()` 定义为 `lifeStage()==ADULT`，以及 `assetId()`。
- `client/` 下的渲染器第一行就转成 `YukkuriView`，往下只传视图，这样 `v.family()` 根本编译不过。帽船同样做一个 `HatBoatView`。
- **安全读法：**
  - `visible*()`；
  - `lifeStage()`、`isBaby()`、`growthScale()`；
  - `brood*()`、`live*()`；
  - `*State()`、`*Phase()`、`contactPose()`、`subspeciesFlags()`；
  - `subspecies().kind()`、`assetId()`；
  - 帽船的 `seatOf`、拥挤状态、`rocking`、`capsizing`、`paddling`。

### 3.7 物种画像（W1-API 定义，R5 深化）

```java
public enum Ecology { WILD, HOUSE, PREDATOR, AQUATIC }
public enum BreedingPool { LAND, AQUATIC, HOUSE, PREDATOR }
public record SpeciesProfile(Ecology ecology, BreedingPool pool, FamilyData.BirthKind birth,
    boolean wildSociety, boolean landSettler, boolean dangerToYukkuri, boolean boundBySocialRules,
    boolean hatVessel, boolean jumps, boolean diurnal, int metabolismDivisor,
    float modelScale, float portionScale, SpeciesTraits traits) {
    static SpeciesProfile of(YukkuriVariant v, SubspeciesLife.Kind k);   // EnumMap 缓存，纯值
}
// YukkuriEntity.profile()：客户端也能用，因为 kind() 读的是同步标志
```

- 约 300 处物种分支的分类：
  - 约 165 处是能力开关、约 40 处是表现常量，W1 迁到画像上；
  - 约 70 处是整套例程切换，留给 R5；
  - 约 20 处是真正的个例，保留。
- **不对称的地方逐个保持原义：**
  - `settlement/HabitatLife.java:199,231`、`Sanction:115`、`social/TreasureLife.java:63`、`settlement/ForageGoal.java:34` 只排除家油；
  - 帽船相关判断用的是 `variant==MARISA`，所以家油魔理沙不算。

### 3.8 归属（R6）

`human/Custody` 统一管理饲主关系：

- `owner()`；
- `ownerKind()`：区分玩家和 NPC。这个值要存下来，不能靠 `level.getEntity` 猜，因为那个区块一卸载答案就变了；
- `Role` 枚举：取代 37 处 `getString("Use")`；
- `assign()`、`release(reason)`；
- `wild()`：取代 6 处复制粘贴的“是否野生”判断。

NBT 格式不变，试玩场写入的 `NeoForgeData.HumanCommerce` 继续可读。“Owner”这个键在饰品、徽章、冷冻柜、觅食堆里另有 4 种含义，只迁移表示饲主的那一种。

## 4. 工作包

**模型分工：** Sonnet 做机械替换和小修；Opus 做跨系统设计、存档兼容和语义判断。“复核”指另派一个 Opus · high 读 PR 的 diff，核对替换前后语义等价。

### 第 0 波：立即修复（4 个 Sonnet 并行，不碰 YukkuriEntity）

| 包 | 内容 | 拥有文件 | 模型 | 试玩 |
| --- | --- | --- | --- | --- |
| W0-1 ⚠ | 客户端误读修复，见下方说明 | 下方说明列出的文件 | Sonnet · medium | `sail`、`shore_baby`、`birth_boat_caught`、`rescue_baby` |
| W0-2 ⚠ | D1 丧偶再配对，见下方说明 | `family/FamilyLife.java`、`scripts/museum_scenarios.py`、`docs/EXPERIMENTAL_MAP.md` | Sonnet · high | `adult_pairing`、`widow_repair` |
| W0-3 ⚠ | 瞬态静态状态，见下方说明 | `pet/SceneReset`、`pet/exam/*`、`pet/PetLife`（只动考试字段）、`pet/stage/Staging`、`shop/ShopPens`、`shop/ShopPerformance`、`shop/ShopMenu` 与 `shop/ShopCourier`（仅预约表调用迁移）、`item/CarrierItem`、`headwear/Sanction`、`social/Cannibalism` | Sonnet · medium | `exam`、`badge`、`transport`、`alice_hunt`；手动：考试中途退出再进 |
| W0-4 | 世界层小修，见下方说明 | `settlement/` 下的 `Levy`、`SiteUpkeep`、`Rations`、`ForageClaims`、`Migration`、`ShelterBlockEntity`、`Grazing`，以及 `cognition/Cognition`、`status/StatusLife` | Sonnet · medium | `settle_*`、`den_family`、`forage`、`graze` |

**W0-1 客户端误读修复：**
- 以下三处的 `family().adult()` 改为 `!isBaby()`：
  - `client/AquaticMotionPresentation.java:30`：可见 bug，帽船里的幼体播成体片段；
  - `client/YukkuriRenderer.java:49`：可见 bug，帽船里的幼体显示惊讶脸，而不是含泪；
  - `client/AgitationPresentation.java:123`。
- `family/LiveBirthGeometry.java:175` 的 `exitFront` 改读已同步的胎儿变种和形态。
- `item/AccessoryShardItem.java:30` 在客户端按物品的 Owner 判断。
- `family/DetachedStemBlock.java:31` 只查已同步的字段。

**W0-2 丧偶再配对（D1）：**
- 新增 `livingPartner()`：伴侣在家族账本里是 DEAD 时返回 null。REMOVED 可能只是区块卸载，不算死亡。
- 只替换求偶和 `canReproduceWith` 里的伴侣判断（`:1007`、`:1420-1421`）；悲伤和遗体识别继续读原来的 `partner`。
- 在试玩场新增场景 `widow_repair`。

**W0-3 瞬态静态状态：**
- `SceneReset` 补齐还没清空的表：`ExamSession.ACTIVE`、`Staging.CARDS`、`ShopPens.NO_PEN`、`ExamHall.CACHE`、`CarrierItem.SCENES`。
- `ExamSession` 的 `wildTag` 存到个体上，重启后能还原。否则野生个体考试中途重启，会被永久挂成玩家的伴侣。
- `ShopPerformance.RESERVED` 改为按维度加坐标作键，并封装成方法，不再从 3 个类直接写。
- `Sanction.RESPITE` 和 `Cannibalism.huntCooldown` 改为持久化。

**W0-4 世界层小修：**
- 补上漏掉的 `setDirty`：`Levy.kept`（`:80-83`）、`SiteUpkeep` 的繁荣度（`:50`）、`Rations.fetchStart`（`:324`）。
- `ForageClaims.CLAIMS` 在服务器停止时清空。
- `Migration.sight` 里两次相同的扫描合并成一次（`:134,144`）。
- `Cognition.get` 的读路径不再每次序列化 NBT（`cognition/Cognition.java:38-46`）。
- `ShelterBlockEntity`：
  - 只在内容变化时发方块更新（`:446,473`）；
  - 更新包里写入 `Pile`，并补上 `onDataPacket`（`:524-528`）。
- `Grazing.Ledger` 改用优先队列。
- `StatusLife` 每秒对全部登记键的变化事件采样，现在没有任何监听者，改为只在 `status trace` 打开时运行。

### 第 1 波：共享查询层（1 + 6 + 1 + 1 个 agent）

**W1-API（Opus · high，1 个，单个 PR）**：只新增代码，不迁移调用点。

- 新建 3.4–3.7 里的类：`entity/Behaviors`、`body/BodyCondition`、`body/BodyConstants`、`social/Bonds`、`social/BondLevels`、`social/Temperament`、`entity/SpeciesProfile`（含 `Ecology`、`BreedingPool`）、`entity/YukkuriView`、`subspecies/HatBoatView`。
- `YukkuriEntity` 加 `implements YukkuriView`、`profile()` 及确有必要的同步只读适配；`HatBoatEntity` 仅增加 `HatBoatView` 实现及同步只读适配；`BodyLife` 只加 `condition()`。接口尽量用已有方法或默认方法，不迁移调用点。
- `BodyCondition` 必须保留现有霉变程度等分级数据，不得因示例的 boolean 字段丢失原判断所需的信息。
- 在本页附录 A 写迁移清单：每个旧表达式对应哪个新方法或常量，写到文件:行。迁移者照清单替换，不自己起名。候选项逐项标注“迁移／留 R5／保留个例”，包括由 E1 接手的 `entity/` 调用点。
- 拥有文件：上面的新文件、`YukkuriEntity` 与 `HatBoatEntity`（仅上述接口接入）、`BodyLife`（仅一处）、本页附录 A。

**W1-M1 至 M6（Sonnet · high，6 个并行）**：W1-API 合入后开工。每个包按目录划分拥有文件，按以下 4 类分别提交；没有适用迁移的类别记录无改动，不为凑数量扩大范围或制造空提交：

1. 行为 ID 改用常量；
2. 带伤判断改用 `BodyCondition`；
3. 关系、信任、性格阈值改用 `Bonds`、`BondLevels`、`Temperament`；
4. 能力开关和表现常量改用 `SpeciesProfile`。整套例程的切换不动，留给 R5。

逐处做等价替换；附录 A 里没有的表达式不碰，回报给调度方。

| 包 | 拥有的目录 |
| --- | --- |
| W1-M1 | `family/` |
| W1-M2 | `social/`、`mind/`、`headwear/`、`status/`、`cognition/`、`dialogue/` |
| W1-M3 | `settlement/`、`event/`、`season/`、`house/` |
| W1-M4 | `pet/`、`shop/`、`human/`、`interaction/`、`item/`、`play/`、`badge/`、`freezer/`、`notebook/`、`predator/`、`rare/` |
| W1-M5 | `subspecies/`、`life/`、`body/`、`registry/`、`command/` |
| W1-M6 | `client/`。渲染器和客户端辅助改用 `YukkuriView`。`family/LiveBirthGeometry`、`family/FamilyFeedingContact`、`family/HatCarry`、`subspecies/SelfRescueHatGeometry` 四个完整文件归 M6：只做附录 A 的等价迁移与客户端入口参数视图化，M1、M5 不改这四个文件 |

`entity/` 下的调用点不在第 1 波处理，由第 2 波负责。

**W1-R（Opus · high，1 个）**：逐个复核 M1–M6 的 PR，复核通过才合入。重点看：
- 替换前后的表达式是否等价；
- 物种分支里不对称的地方（3.7）；
- 各种亲属口径。

**W1-D（Sonnet · medium，1 个，⚠）**：D2、D6 已通过；M1–M6 与 W1-R 全部完成后，只修改 `BodyCondition`、`BondLevels`、`Temperament` 里的定义。先交付 D6 口径对照与保留差异的理由；仍被调用的方法保留为兼容委托，不能删除后留下编译断点。允许更新本页附录 A 的定义说明与对照，调用点不在本包扩大修改。

### 第 2 波：实体核心（1 个 Opus 串行，持有实体锁）

第 1 波全部合入后开工。第 2 波独占 `entity/`（含新建的子包），并按下列最小例外借用跨目录文件；与第 3 波按文件锁并行。

- E1：`life/YukkuriConfig.java` 只增调试开关，`scripts/build_museum_full.py` 只在完整生成世界时写对应 SERVER 配置，`life/AgitationLife.java` 只迁移 exhaustion 写入。
- E2：`command/PlaygroundTools.java` 只删被统一移除逻辑接管的取消胎代码；`pet/PetLife.java` 仅 `lock` 的等价打断接线。
- E3：`social/EcologyPresentation.java` 仅合并原文指定的相同船动作代码。
- E4：只给 `social/Cannibalism`、`SelfOffering`、`TreasureLife`、`settlement/ForageTrip`、`ForageGoal`、`event/HomeClaimGoal`、`human/FactoryWorkGoal` 补展示接口并接线；`assets/mcyukkuri/lang/{zh_cn,en_us,ja_jp}.json` 仅补本步新行为与首次接入的越冬行为文案，供观察笔记显示。

| 步 | 内容 | 模型 | 试玩 |
| --- | --- | --- | --- |
| E1 | 组件登记、存档版本、调试视图开关、`SukkiriExhaustion` 改瞬态，见下方说明 | Opus · high | `house_pair`、`forage`、`den_family`、存档重载 |
| E2 | 打断协议与 `remove()` 覆写（⚠），见下方说明 | Opus · high | `hurt`、`hurt_kin`、`pred_hunt`、`alice_interrupt`、清场 |
| E3 | 3.3 的显示仲裁搬迁，顺序不变；见下方说明 | Opus · xhigh | `sail`、`braid`、`self_rescue`、`agitation_relief`、`rain_shelter` |
| E4 ⚠ | 显示链重排与补齐（D5），见下方说明 | Opus · xhigh | 全部 `D*`、`W*`、`B1` 场景 |
| E5 | 同步整理，见下方说明 | Opus · high | `birth_land_caught`、`hurt`、`feed`、`meal` |
| E6 | 拆分实体本身，见下方说明 | Opus · high（纯搬移部分可交给 Sonnet） | 全部回归 |

**E1：**
- 按 3.1 建组件登记表，旧组件用适配器，不改组件文件。
- 实体存档加版本号。
- 按 D4 在现有 `YukkuriConfig` 加默认关闭的调试开关，在 `build_museum_full.py` 完整生成世界时写入 `world/serverconfig/mcyukkuri-server.toml` 并开启。启动脚本只调用生成器，不直接写配置；沿用 `-Resume` 不重建的语义，旧场景需正常重建才应用配置。
- `SukkiriExhaustion` 从 persistent data 改成瞬态字段，个体活下来就清掉。允许为此改 `life/AgitationLife.java:479` 这一行。
- `entity/` 里的调用点按附录 A 迁移。

**E2：**
- 按 3.2 实现打断协议，表的初值照抄现有列表。
- ⚠ 新增 `remove()` 覆写：个体被丢弃（不是被捕获）时，停掉 Sanction、Cannibalism、CareApproach、predator，并释放还没出生的胎。
- 之后可以删掉 `command/PlaygroundTools.java:46-51` 里手工取消胎的代码，这一处例外允许修改。

**E3：**
- 按 3.3 把显示仲裁原样搬出去，顺序不变。
- 把 `boatAction` 和 `EcologyPresentation.boat` 合并，只合并逐字相同的部分。
- 调试视图记录每 tick 胜出的来源。

**E4（⚠，D5）：**
- 显示顺序与目标优先级对齐，补齐 7 个不显示的目标和越冬的 `activity()`。
- 这需要给 `social/`、`settlement/` 等目录下的组件补 `activity()`。此时第 1 波已经结束，允许 E4 修改这些文件。

**E5：**
- `{Phase, Start, *Id}` 这类 CompoundTag 同步数据改成带类型的编解码。
- 缓存 `visibleWounds()`（`:434`）。
- `syncFamily` 和 `syncBody` 不再每 tick 重建 tag（`:436`、`:462`），内容没变就不 set。
- `mobInteract` 在客户端只返回乐观的 `sidedSuccess`，不读组件（`:1072-1086`）。

**E6：**
- 把实体拆成 `YukkuriSync`（同步字段与 `sync*` 方法）、`YukkuriGoals`（目标登记）、`YukkuriDamage`（`hurt` 和 `die`）、`YukkuriInteractions`（`mobInteract` 和牵绳）。
- 实体本身保留公开方法作门面。
- 本文件里的内联全限定名改成 import。

一个 agent 连续做完 E1–E6，每步一个 PR。上下文耗尽时，由新的 Opus 从第 5 节进度表接手。

### 第 3 波：拆分巨型类（与第 2 波并行，对应的第 1 波包合入后开工）

具体怎么拆见附录 B。委托类照 `HatBoatCargo` 的写法：`final class X { private final Owner o; }`；主类留包内可见的钩子，外部调用的公开方法保留为委托。

| 包 | 内容 | 模型 | 前置 |
| --- | --- | --- | --- |
| S1a | `HatBoatEntity` 的 H1–H5：船体晃动、下水与收帽、离岸泊位、倾覆、救援配对 | Sonnet · high | M5 |
| S1b | `HatBoatEntity` 的 H6–H7：搬运行程与服务座位。这是最难的接缝：两者互相调用、存档互读。顺带修拖曳状态不同步：`towed()` 读的是只存在于服务端的 `pullTicks`（`:2183`），改为在 MOTION 里同步一个 TowUntil | Opus · high | S1a |
| S2a | `FamilyLife` 的 F1–F3：喂食、新生儿飞行、求偶。先给其他实例直接读的私有字段（`task`、`target`、`partner`、`wait`、`arranged`）加包内访问器 | Sonnet · high | M1 |
| S2b | `FamilyLife` 的 F4–F5：接帽、胎生分娩。`advanceLive` 会同时读两边的状态 | Opus · high | S2a |
| S3 | `YukkuriLife` 的 L1–L5：饰品、帽中如厕、药剂、住所、进食。规划器（`:506-772`）不动 | Sonnet · high（L5 的咀嚼段要小心） | M5 |
| S4 | `YukkuriModel` 的 M1–M4：加载、徽章绘制、辫子姿势、帽子损伤绘制 | Sonnet · high | M6 |
| S5 | 分两 PR：S5a 分离季节纯查询与聚落策略；S5b 拆分 `HabitatLife`。三目录无环目标由 S5 与 R8b 共同完成，见下文 | Sonnet · high，Opus 复核 | M1、M2、M3、M4 |

S1a、S3、S4、S5a 可以同时跑；S5a 合入后释放 `FamilyLife`，S2a 与 S5b 再并行。S1b、S2b 分别接在各自的 a 之后。

S1b 拥有 `HatBoatEntity`、新增 `HatBoatCarry`、`HatServiceSeats` 与 `HatBoatView`。`HatDepartureBerth`、`HatRescuePair` 仅将搬移字段访问改为等价窄钩子；`HatBoatLaunch`、`HatBoatCapsize` 仅将原 `pullTicks=0` 改成同步清理的 `clearTowTicks()`，不扩大清理集合。服务器计数和暂停时机保持；TowUntil 只修复客户端拖曳展示，所有 MOTION 整包写入都保留或清理该字段。

**S5 的拆分边界：**
- S5a 将 `Season` 原样移到 `season/`；`season/Seasons` 只保留环境标签、`enabled/at/on/snowing/noon`。新增 `settlement/SettlementSeasons` 承接 `of/is/starving/confined/forageBlocked/mayCourt/assembling`，保留群中心、住所、个体位置的选择次序及原客户端门槛。
- S5a 拥有两个移动源及其目标、新 helper，以及完整文件 `family/FamilyLife`、`rare/RareForms`、`social/DailyLife`、`social/WoundComfort`、`season/SeasonClimate`、`SeasonCalendar`、`AutumnStock`、`WinteringLife`、`settlement/ForageTrip`、`CommonHunt`、`ForageGoal`、`Levy`、`Migration`、`SeasonCommands`、`ShelterBlockEntity`、`SiteUpkeep`、`Rations`、`SettlementLife`、`SettlementObservation`、`HabitatLife`、`CommunityRules`；除拆出的查询方法外仅改调用归属和导入。同步 `docs/SEASONAL_LIFE.md`、`docs/DEVELOPMENT_PLAN.md` 的相关路径。
- S5b 只拥有 `HabitatLife` 和新增 `HabitatHomes`、`HabitatSeasonal`。住房搜索、挖洞、扩建、迁居、游荡及各自冷却放 Homes；季节停留、午睡、共享、争吵及其字段放 Seasonal。任务仲裁、唯一携带槽、物品接触、私巢工程、存读和公开门面留 owner，原调用位置逐段委托，不新增时钟或重排优先级。
- 构造和 load 的随机调用、双方争吵冷却、找阴凉失败仍消耗当天机会、共享接触时才取粮、stop/load 原本不同的清理集合都保持。四个存档字段 Home/NestHome/Carrying/NestMaterial 仍写原父 tag。
- S5a、S5b 分别接受独立 Astra 复核。完成 S5 不能宣称三目录已无环：`AutumnStock`、`WinteringLife` 与聚落对事件的直接调用在 R8b 收口。

### 第 4 波：语义与生命周期（多数标 ⚠，以 Opus 为主）

前置条件：第 2 波 E6，以及要改的文件所对应的第 3 波拆分，都已合入。R2、R6、R9 的跨目录调用点须在派发前展开成文件清单，不能将“各调用点”理解成任意文件权限。

| 包 | 内容 | 拥有文件 | 模型 |
| --- | --- | --- | --- |
| R1 ⚠ | 关系生命周期（D8），见下方说明 | `family/FamilyData`、`FamilyLife`（拆分后的伴侣部分）、`life/YukkuriWorldData`、`settlement/SettlementData`、`Commons`、`cognition/CognitionData`、新建的清扫类 | Opus · high |
| R2 ⚠ | 情绪与对白解耦，见下方说明 | `mind/`、`social/YukkuriPersonality`、`entity/` 里 `showReaction` 一处、约 25 个调用点 | Opus · high |
| R3 | 伤口类型，见下方说明 | `body/`、`status/Marks`、`client/WoundRenderer` | Opus · medium |
| R4 | 受击管线（可选，最后做），见下方说明 | `body/`、`entity/YukkuriDamage`、伤害类型 JSON | Opus · xhigh |
| R5 ⚠ | 物种分派深化，见下方说明 | `subspecies/`、`house/HouseLife`、`predator/`、`life/`（住所部分） | Opus · high |
| R6 ⚠ | 归属（3.8，D7），见下方说明 | `human/`、`pet/PetLife`、各调用点 | Opus · high |
| R7 | 交互分派，见下方说明 | `interaction/`、`item/`、各菜单与 `client/` 的屏幕类 | Sonnet · high，Opus 复核 |
| R8a | 世界层性能，见下方说明 | `settlement/`（含 `site/`） | Sonnet · high（2 个：一个做“名册＋预算”，一个做“索引＋仓储”） |
| R8b ⚠ | 世界层协调，见下方说明 | `settlement/`、`event/` | Opus · high |
| R9 | 启动接线，见下方说明 | `MCYukkuri`、`registry/`、`human/HumanWorld` | Sonnet · medium |

**R1 关系生命周期（⚠，D8）：**
- 伴侣写进 `FamilyData.Person`，配对时两边同时写，并新增丧偶状态。
  - 兼容读取旧的实体 `Partner` 键；
  - 遗体自带的 `Partner` 副本保持不变。
- 确认死亡后清理：
  - 好感边；
  - 聚落成员记录；
  - 全员死亡的 FAMILY 群。
- `Commons.standing` 加老化，`CognitionData` 做安全清理。
- `FamilyData.people` 加按父母的索引，并做清理。
- `Group.role` 遇到不认识的 UUID 返回 FREE。
- 好感边按来源建索引。

**R2 情绪与对白解耦（⚠）：**
- 情绪输入改成命名事件，不再按对白事件名触发（`MindLife.felt`，`:122-135`）。现在一个事件加多少不安，取决于有没有写那句台词。
- 去掉 4 个特质别名，人格存档升到 v3，并兼容读取 v1、v2。
- W1-D 没统一的阈值按 D6 收口。

**R3 伤口类型：**
- 新增 `WoundKind` 枚举，存档仍写字符串。
- 把只用于绘制的霉斑、焦斑、馅糊、中枢拆成 `SkinDecal`。
- 4 个皮面选点器和 6 处内联的重心坐标计算统一用 `WoundSurface.anchor`。
- 不再存派生的 `wound`、`burns` 值。

**R4 受击管线（可选，最后做）：**
- 用自定义伤害类型，或单次受击的上下文对象，替换 `boutDamage`、`internalDamage`、`finishing`、`strike` 和 `Sanction.biting` 这些隐藏标志。
- 把 `guardCore`、原版 `hurt`、`injured` 三步合成 `BodyLife.onHurt`，返回结果。
- 原版受击冷却和 `repeatHitIgnored` 很脆，改动时要保住它们现在的表现。

**R5 物种分派深化（⚠）：**
- 用 `HomeProvider` 抽出 `YukkuriLife:545-862` 里的住所路由。
- 家油、捕食种、水生三套例程实现 `EcologyRoutine`，去掉例程里自己做的物种判断。
- 水生改用 `BoatTask` 和 `yieldAllBut(task)`，取代 18 处 `care.cancel()` 和 39 处 `daily.stop/yield`。尝试顺序本身就是行为，照抄。
- 喂食常量统一到 `FamilyFeedingContact.REQUIRED_CONTACT_TICKS`。

**R6 归属（⚠，3.8，D7）：**
- 实现 `Custody` API 和 `Role` 枚举，迁移约 19 处原始读取。
- 手写的释放（`WildEvents:288`、`shop/ShopDeliveries.java:147`、`ExamSession:313`）改走 `release`。
- NPC 死亡后按 D7 处理名下个体。这些个体可能还没加载，所以要存一份已死主人的记录。

**R7 交互分派：**
- 在 `interaction/` 下建服务端统一入口，把 `CareMenu.open` 的三个入口合成一个。
- 菜单的按钮和数据槽改成常量。
- `CareAction` 改用稳定 id，不再按枚举序号传。

**R8a 世界层性能：**
- 按群维护已加载成员的名册，取代半径 16–72 的区域扫描。
- 按开销计费的搜索预算移到中立位置，套到这些搜索上：`HabitatLife.findHome`、`biggerHome`、`Migration.pickSite`、`SettlementLife.navigateToward`、`SiteLife.settle`。
- 用已加载住所的索引取代方块实体扫描。
- `sites()`、`groups()` 不再每次调用都复制整张表。
- 合并重复的仓储代码。
- ⚠ `Site.emptySince` 改为持久化。
- `house/HouseholdEcology` 不动。
- 预算结果区分找到、未找到与暂缓；暂缓不能触发失败后备或宣告搜索完成。为闭合现有调用，名册／预算实施者额外锁定 `life/LifeHome.maintainHome`、`life/YukkuriLife` 的住所重试接线、`event/HomeClaim` 的 HOLD 收尾；先等 R5a 释放两个 life 文件。仅暂缓时把住所重试改为20 tick，其他仍沿原200 tick与后备流程。
- 认屋收尾在第一位成员搜索前为整个存活且已加载的成员批次预留预算，按原成员顺序显式共享同一计费上下文，全部搜索完成后才沿原规则收尾。预算不足时无人执行搜索、不结束活动；不执行半批后跨 tick 暂停，也不在预算层保存事件对象或搜索闭包。
- 名册替换保留影响选择或分配的原并列顺序；必要时回退一次旧查询。预算使用按服务器实例隔离的 FIFO，超大原子搜索轮到时可独占一次准入；这提供排队公平，不宣称已有单 tick 耗时或吞吐的性能实测。

**R8b 世界层协调（⚠）：**
- 统一预约和冷却的原语（现有约 10 套实现、3 种区域键），并只保留一个“是否被占用”的判断。
- 成员未加载时暂停的场景和认屋加超时（`event/HomeClaim.java:151`、`WildEvents.java:187-194`）。现在它们会永久锁住成员。
- 族长、继任、迁徙审议这些群级逻辑改由一个服务端 ticker 驱动。
- 完成 S5 剩余的三目录无环目标：`AutumnStock`、`WinteringLife` 运行策略移至 `settlement/`；纯环境查询留 `season/`。保持越冬 Goal 优先级及同级顺序、构造时机、原存档路径。
- 由 `settlement/SettlementEvents` 声明窄同步端口，`event/SettlementEventBridge` 实现 `reserved/homeClaimHeld/unowned/shelterBarred/tryCollapse`，顶层 `MCYukkuri` 在世界运行前安装一次。接口与 holder 不反向引用 event，不用静默默认值、缓存、排队或事件总线。collapse 仍在住所原位置同步调用，保留随机 gate、成功即 return 和失败后继续。
- 无环收口额外锁定两移动类、`settlement/ForageTrip`、`HabitatLife`（及拆分后持调用的 helper）、`LeaderSuccession`、`SettlementDuties`、`Migration`、`ShelterBlockEntity`、两端口文件、`item/NotebookItem`、`MCYukkuri` 和 `docs/SEASONAL_LIFE.md`。Wintering 的字段、fallback 初始化、getter 和正式创建须在 E6 后映射到精确实体文件再派发，不授权整个 entity 目录。
- 机械依赖反转与预约/ticker 行为变化分提交审查。最终核对直接导入、全限定名、方法引用及推导类型/链式调用，确保 season 不依赖 settlement/event、settlement 不依赖 event，形成 `event → settlement → season`；不能只统计 import 后标完成。此目标针对三个领域包，不声称整个项目的所有 Java 类型无环。

**R9 启动接线：**
- `MCYukkuri` 里的 22 个 `addListener` 和 16 个 `@EventBusSubscriber` 统一成一种写法。
- 实际预检为 26 条显式监听、23 个自动订阅类；最终按实施基线重新展开完整文件白名单。采用逐条显式命名注册，保持原构造期监听的已知前缀与各 listener 的优先级、取消过滤；客户端由单独的 CLIENT 模组入口注册，通用入口不引用客户端类。
- 原自动扫描没有稳定规定的顺序现明确为：原入世界监听前缀之后，`Staging.joining` 先于 `Cognition.joining`；`HumanCommerce` 完整实体 tick 末尾处理之后，`HatCarry.tick` 先于 `WildEvents.discover`。先拒绝无效演出实体再认知入账，先处理帽上乘客安全释放再发现事件。这是固定原先未定义的顺序，须与机械迁移分提交审查；不声称保持与所有第三方监听的历史交错。
- 空的 `init(){}` 触发改成显式注册。
- `HumanCommerce.tickAnimal` 移进实体的 tick。
- 三份配置的分工写进文档。

**并行关系：**
- R1、R3、R5、R7 可按实际文件锁并行；R3 还需 HF1 释放客户端模型。R8a 与 R1 都需要 `SettlementData`，且搜索暂缓接线需要 R5a 的两个 life 文件，因此等 R1、R5a 合入后再开工。R8a 的两个实施者先分清各自的完整文件清单。
- R2 排在 R1 之后，因为两者都改关系。
- R6 排在 R7 之后，因为两者都改 `interaction/` 和 `pet/`。
- R8b 排在 R8a 之后。
- R9 排在 E6 后，明确允许修改 `human/HumanCommerce` 和最终组件登记表以迁移 `tickAnimal`；与 R2 的实体调用点、R8b 的 MCYukkuri/越冬接线按文件锁串行，最终统一注册吸收这些接线。
- R4 最后做。

### 第 5 波：收尾（在安静窗口做，即没有其他重构 PR 未合入时）

| 包 | 内容 | 模型 |
| --- | --- | --- |
| Z1 | 机械整理：内联全限定名改成 import（现有 1840 处），拆分超过 200 字符的行（390 行）。按包组分给 3 个 agent | Sonnet · low（3 个） |
| Z2 | 写 `scripts/check_architecture.py --check`，规则见下方说明 | Sonnet · medium |
| Z3 | 文档收尾，见下方说明 | Sonnet · medium |

**Z2 检查规则：**
- `client/` 不得调用服务端组件的 getter；
- `getPersistentData` 只允许出现在白名单里；
- `isHouse()`、`isPredator()` 只允许出现在 `SpeciesProfile` 和白名单里；
- 不得新增内联全限定名。

登记到 `scripts/README`，不作为合并门槛。

**Z3 文档收尾：**
- 把第 2、3 节的约定压缩后写进 `AGENTS.md` 的“代码与资源”一节；
- `docs/STATUS_HUB.md` 和 `docs/DEVELOPMENT_PLAN.md` 6.2 改为已完成；
- 本页压缩为代码架构说明。

## 5. 调度安排

### 用户追加：HF1 损坏头饰渲染崩溃

2026-10-19 用户报告 main 的损坏头饰渲染崩溃，并提供固定试玩分支 `playground` 上的 `286b744a` 修复作为参考。该问题不在原重构包内，补为独立优先修复包；不将试玩分支的历史代码整体合回 main。

- 根因接缝：身体/帽布的 `VertexConsumer` 已取出后，提前请求内衬纹理的 buffer，可能结束原 buffer，后续继续写入会触发 `Not building!`。掉落损坏头饰的 `PropModels.renderHat` 有相同的交错写法。
- 采用参考修复的 `HatCapQueue` 思路：记录内衬与破边的顶点、姿态、法线、颜色、光照和 overlay，身体及伤口写完后再请求内衬 buffer 并绘制。异常路径清空队列，避免复用模型时残留上次数据；保持原几何、材质、损坏等级与伤口绘制语义。
- main 已完成 S4 拆分，完整文件锁为 `client/YukkuriModel.java`、`client/HatDamageDraw.java`、`client/PropModels.java` 和新增 `client/HatCapQueue.java`。仅适配此修复，不回填旧模型类或改服务器组件。由 Astra high 实施、另一位 Astra high 独立复核，单独 PR，远端 CI 通过后统筹合入。
- HF1 可与 E4–E6 并行；R3 和最终客户端整理必须等其释放 `YukkuriModel` 文件锁。用户自行操作的固定试玩分支继续独立，不视为 main 的游戏内验收；修复后 main 仍需检查活体损帽、掉落损帽与伤口共存的实际展示。

HF1 已由 #81 合入；R3 后续改变伤口／贴花渲染时须保留身体与伤口完整绘制之后才刷新帽内衬队列的顺序，不能在切换内衬纹理后继续写旧身体 buffer。

### 用户追加：HF2–HF4 试玩问题

2026-10-19 用户追加以下三项，纳入本次交付。修复在 main 的隔离工作树完成；固定试玩分支仍由另一位 AI 维护。

- **HF2 拾取馅料渲染崩溃**：原版拾取动画使用普通 `ItemEntity` 副本，`MaterialDropRenderer` 的 `MaterialDrop` 类型约束会在桥接调用中产生强转异常。渲染只使用 `getItem()` 与 `bobOffs`，将泛型和对应方法参数放宽为 `ItemEntity`，并核对注册兼容。初始文件锁仅 `client/MaterialDropRenderer.java`；GPT-6.1 Sol medium 实施，单独 PR。
- **HF3 伤口可见性**：伤口优先留在原受击部位的裸露皮肤；用户已批准仅在整个部位被头发或帽子覆盖时，改用最近的裸露边缘。Astra high 在 R3 后实施：保留原逻辑命中锚点，另存服务器权威的显示锚点；伤口绘制与掉馅出口使用显示点，原部位判定、容量合并和随机顺序不变。局部搜索失败或裸露细缝不足以容纳伤口，不能视为整个部位被遮挡。共享模型适配计算，保留旧档读取与 HF1 绘制顺序。已按现有导出器重导两种家油的伤口资源并更新来源登记，修复旧指纹不匹配；几何、绑定和三角顺序不变。损帽须共享真实缺口／内盖几何，完整未损帽壳不能作为全覆盖证明；未建模的摘帽／还帽姿态不得新签跨部位位置，结束后重查。候选按实际显示点距离选择并精化裸露边缘，不以三角中心排序代替最近性。
- **HF4 试玩场读数展示**：GPT-6.1 Sol medium 将读数转为只发给执行玩家的分页虚拟书报告，按实际字体宽高换页，不修改手持物品。保留完整 raw 快照路径与需要观察的信息；原短控制反馈不变。范围限两个命令类、试玩场生成脚本与说明、新增报告网络载荷及客户端展示桥接；新增监听统一登记给 R9 收口。

三项均沿用远端 CI 与复核流程；不本机编译、启动游戏或操作 UI，实际拾取、遮挡与按钮展示待用户试玩。

### 并行设计交接：血量与遗体资源

[血量与遗体资源方案](BODY_RESOURCE_PLAN.md) 已进入独立架构评审。当前重构保持既定行为，HF3 继续完成显示位置修复，R6 继续完成归属生命周期；不在这些包里提前替换馅料、核心、捕食或工厂规则。新方案实施前须明确同步死亡中的实际损血结算、濒死快照优先级、遗体交接失败重试、吞咽收益单次提交、旧档及旧物品迁移、各条治疗路径的成本。推荐数值和新终结流程仍按待裁定处理，不计入已批准的重构完成项。

后续玩法接入以 HF3、R6、R2 的最终实现为基线，并遵守 R9 的唯一事件入口。可选 R4 在最后评估时一并考虑该方案，避免先做等价受击改造、随即又替换同一事务；其余重构排期继续推进。

### 原计划波次

| 波次 | agent 数 | 同时运行 | 模型 | 开工条件 |
| --- | --- | --- | --- | --- |
| 0 | 4 | 4 | Sonnet ×4 | D1 已定 |
| 1 | 9（API 1、迁移 6、复核 1、裁决落地 1） | 先 1 个，再 7 个 | Opus ×2，Sonnet ×7 | 第 0 波已合入 |
| 2 | 1（可接力） | 1 | Opus | 第 1 波已合入 |
| 3 | 7 | 5 | Sonnet ×5，Opus ×2 | 对应的 M 包已合入；与第 2 波并行 |
| 4 | 11 | 6 | Opus ×7，Sonnet ×4 | 见第 4 波前置；R 包多数需要先裁决 |
| 5 | 5 | 3 | Sonnet ×5 | 前面全部合入 |

合计约 37 个 agent 次，其中 Opus 约 12 个、Sonnet 约 25 个。最多同时运行约 7 个，出现在第 1 波迁移阶段，或第 2、3 波并行时。S5、R7 等标了“Opus 复核”的包，复核沿用 W1-R 的复核简报，另起一个 Opus 去做，不计在上表内。

**实体锁：**
- 第 0 波：没有人碰 `YukkuriEntity`。
- 第 1 波：W1-API 独占 YukkuriEntity 与 HatBoatEntity 的视图／画像接入，范围限已列明只读适配；M 包不改实体。
- 第 2 波：E 包的 agent 独占 `entity/`。第 3、4 波的包如果需要改实体，把需求写进 E 包 agent 的待办，不自己改。
- 第 2 波结束后，实体门面与四个委托仍按文件分别授权，门面保持单写者。R2 的 `showReaction` 和 R9 的 tick 接线依次获得对应文件锁。

**集中试玩：** 每一波结束后，用户按该波各 PR 提交信息里列的场景集中试玩一次。某个 PR 有问题就整个 PR 回退，再派原来的包去修。

**进度表**（做完后填状态和 PR 号）：

| 包 | 状态 | PR |
| --- | --- | --- |
| HF1 | 已合入，CI 与独立 Astra 复核通过；活体和掉落损帽均延后绘制内衬，待用户试玩 | #81 |
| W0-1 | 已合入，CI 与独立复核通过；待用户试玩 | #49 |
| W0-2 | 已合入，CI 与独立复核通过；待用户试玩 | #53 |
| W0-3 | 已合入，CI 与独立复核通过；待用户试玩 | #51 |
| W0-4 | 已合入，CI 与独立复核通过；待用户试玩 | #50 |
| W1-API | 已合入，CI 与独立复核通过；实体接口接入锁已释放 | #55 |
| W1-M1 | 已合入，CI 与独立复核通过；待用户试玩 | #57 |
| W1-M2 | 已合入，CI 与独立复核通过；待用户试玩 | #60 |
| W1-M3 | 已合入，CI 与独立复核通过；待用户试玩 | #59 |
| W1-M4 | 已合入，CI 与独立复核通过；待用户试玩 | #58 |
| W1-M5 | 已合入，CI 与独立复核通过；待用户试玩 | #62 |
| W1-M6 | 已合入，CI 与独立复核通过；第 2、3 类无适用项，待用户试玩 | #61 |
| W1-R | 已完成六个迁移 PR 的精确 head 独立复核 | #57–#62 |
| W1-D | 已合入，CI 与独立复核通过；D2 与 D6 已落实，待用户试玩 | #63 |
| E1 | 已合入，CI 与独立 Astra 复核通过；实体锁继续由 E1–E6 串行会话持有 | #69 |
| E2 | 已合入，CI 与独立 Astra 复核通过；保持原打断顺序，新增非捕获丢弃清理 | #74 |
| E3 | 已合入，CI 与独立 Astra 复核通过；显示仲裁保持原执行顺序 | #77 |
| E4 | 已合入，CI 与独立 Astra xhigh 复核通过；D5 显示排序与遗漏来源已接入，待用户试玩 | #79 |
| E5 | 已合入，CI 与独立 Astra high 复核通过；同步类型与缓存、客户端交互候选分流完成，待用户试玩 | #83 |
| E6 | 已合入，CI 与独立 Astra high 复核通过；四助手拆分完成，后续按完整文件分配锁 | #85 |
| S1a | 已合入，CI 与独立 Astra 复核通过 | #68 |
| S1b | 已合入，CI 与独立 Astra 复核通过 | #72 |
| S2a | 已合入，CI 与独立 Astra 复核通过 | #71 |
| S2b | 已合入，CI 与独立 Astra 复核通过 | #75 |
| S3 | 已合入，CI 与独立 Astra 复核通过 | #64 |
| S4 | 已合入，CI 与统筹复核通过 | #65 |
| S5a | 已合入，CI 与独立 Astra 复核通过 | #67 |
| S5b | 已合入，CI 与独立 Astra 复核通过；三目录无环目标继续由 R8b 收口 | #70 |
| R1a | 已合入，CI 与独立 Astra 复核通过；父母／子女与好感来源索引 | #88 |
| R1b | 已合入，CI 与独立 Astra 复核通过；伴侣账本权威、旧档迁移与事务回滚 | #93 |
| R1c | 已合入，CI 与独立 Astra 复核通过；清扫按有限工作量推进，保留有效关系与在途社会引用 | #100 |
| R3 | 已合入，CI 与独立 Astra 复核通过；保留 HF1 绘制顺序，文件锁已交 HF3 | #90 |
| R5a | 已合入，CI 与独立 Astra 复核通过；住所路由抽离 | #89 |
| R5b | 已合入，CI 与独立 Astra 复核通过；例程契约与出生方式接线，原顺序和概率保留 | #95 |
| R5c | 已合入，CI 与统筹独立源码复核通过；船上让位意图与喂食接触常量收口，保持原回收顺序 | #99 |
| R7 | 已合入，CI 与独立 Astra 复核通过；统一交互入口与稳定菜单协议，复核修正了跨含义常量 | #91 |
| R6a | 已合入，CI 与统筹独立源码复核通过；Custody 查询与原存储桥，保留旧接口 | #96 |
| R6b | 已合入，CI 与独立 Astra 复核通过；三十四文件迁移完成，保留 R1c 引用维护接缝 | #102 |
| R6c | 已正式实施；持久死亡主人台账、捕获快照与释放收口，随后 R9 统一接线 | |
| R2 | 最终位置已映射；先做人格／六槽遗传适配，再一次切换全部情绪来源，按 R1、R6、HF3、R8a 文件锁串行或并行 | |
| R8b、R9 | 已完成只读范围预检；等待各前置与实际文件锁释放 | |
| R8a B | 已合入，CI 与独立 Astra 复核通过；已加载住所索引、仓储操作及共享接口完成 | #103 |
| R8a A | 已正式实施；按结构快照、名册、预算三态接线分步交付，消费 B 的既定接口 | |
| R4 | 可选项，按安排最后评估 | |
| Z1、Z3 | 未开始 | |
| Z2 | 已完成只读范围预检；检查器与例外清单等全部 R、Z1 完成后实施 | |
| HF2 | 已合入，CI 与统筹独立源码复核通过；兼容拾取动画的普通 ItemEntity 副本 | #86 |
| HF3 | 双锚点、愈合重查及家油资源修复已编译通过；真实损帽几何、部位裁切和实际显示点距离增量待复核，继续补齐透明纹理遮挡计算，不标完成 | #97 |
| HF4 | 已合入，CI 与独立 Astra 复核通过；完整命令队列完成后定向发送分页报告，待用户试玩 | #92 |

上述已合入包仍待用户在既有试玩场验证；远端 CI 只证明编译通过，不代表已完成游戏内验收。

## 6. 派发简报模板

调度方复制下面的简报，填上包 ID：

> 你负责 docs/REFACTOR_HANDOFF.md 里的工作包 **<包ID>**。先读 AGENTS.md、docs/DESIGN_DECISIONS.md，以及本页第 2 节和 <包ID> 那一节。只改该节列出的拥有文件；需要改别的文件时停下回报。在 `.worktrees/<包ID>` 里基于 origin/main 开分支 `refactor/<包ID>`，按节内步骤提交，推分支并开 PR。CI 通过后回报统筹；需要独立复核的包由统筹另派复核，统筹统一合入 main 并更新进度。回报：改了什么、存档键比对结果、需要试玩的场景、没做的事。

复核简报：

> 复核 PR #<号>（工作包 <包ID>）。按 docs/REFACTOR_HANDOFF.md 第 2 节和附录 A 逐处核对三件事：替换前后语义等价、存档键不变、只改了拥有文件。列出所有不等价的地方（文件:行、原表达式、新表达式）；没有问题就批准。

## 附录 A：迁移清单

### A.1 固定基线、所有权与执行规则

以下位置固定于 `c168498048058670b320ba062f61d752128640bc`；API 自身新增行使后续行号偏移时，以表中的旧表达式定位。每个文件的完整所有权见 A.3。M1–M6 不改 `entity/`，其中的调用点全部交 E1。

每行“迁移”只允许所列的等价替换；同一行有多个子表达式时先替换最长复合表达式，再处理剩余短表达式。同一个旧字符串在别处出现不构成授权。行为值、NBT 路径、大小写、枚举序号及随机调用顺序均不变。新文件本身不再二次迁移；新增 import 可随拥有文件调整。

所有“留 R5”项在 W1 保留；“保留个例”说明该候选为何不套共享查询。单项身体 getter、存档写入和诊断显示保留，复合带伤判断使用按用途命名的方法。W1-D 只改已登记定义，不回头扩张调用点。

### A.2 契约细节与接缝

- `BodyCondition` 的 `wound/burns/infection/mold/filling` 均为原 int 分级值，boolean 另存 `weak/dying/sick/footBurned`。W1-D 按 D2 将 `readyForReproduction/readyForDelivery` 改为皮伤、烧伤、感染均为0，`blocksBreedingApproach` 相应遇任一伤情即阻止；`readyForPlay` 要求皮伤、烧伤均为0。其余用途不扩大：工厂额外查霉变、秋储额外查烧伤与霉变；`readyForGroupPlay` 同时服务相扑、欺凌与保护，不在本包给公共条件增加烧伤门槛。
- `Bonds` 是服务端查询。`BIO` 是 FamilyData.related（包括 incidentSource），`CARE` 是 socialKin（包括照护父母和兄弟姐妹），`MATE` 接受任一方向的历史 partner，`HOUSEHOLD` 限 FAMILY 群，`GROUP` 只比非空群 UUID。直接父母、带年龄的照护、双向伴侣和遗体 partner 均有独立方法；识别、好感、同世界等外围条件保留。`exileRelatives` 保留顺序和重复项。
- D6 的现行定义及保留差异见下表；A.4 仍是 W1 迁移时的历史表达式清单，不据本轮阈值变更重生成。未出现比较的权重、奖励、处罚和随机公式不变。
- `SpeciesProfile.of(variant, kind)` 缓存纯值。`aquatic` 独立取 kind==AQUATIC_MARISA，保留旧 kind-only 判断；ecology 分类按 HOUSE/PREDATOR/AQUATIC/WILD 优先。`birth` 仅家油 EGG、捕食种 STEM，其余为 null，表示继续原 LIVE/STEM 选择，不能直接传 null 到 conceive。`pool` 不替代受双方状态影响的兼容性检查。`hatVessel` 只认原版 MARISA，不包括 HOUSE_MARISA；具体变种与形态个例保留。
- `metabolismDivisor` 保留旧家油值4（其他1），旧计算实际乘在饥饿间隔上，迁移用 `hungerIntervalMultiplier()` 并保留运算顺序。`modelScale` 是掉落来源比例 .32，不是成长倍率；`portionScale/woundSize` 是 .35。
- `YukkuriView` 不提供 entity()/family()/life()/body()/subspecies()。`assetId()` 由 variant+同步 flags 推导，adult() 由同步 lifeStage 推导；原版空间/存活/世界接口与公开旋转、受击、死亡和年龄字段用只读方法。`getVehicle() instanceof YukkuriEntity/HatBoatEntity` 分别换 `vehicleYukkuri()/vehicleHat()` 非空；同载具判定换非负 vehicleId 相等；hasPassenger(child) 换 hasPassengerId(child.getUUID())；level().getEntity(id) 的油库里/帽船查询换 yukkuriView(id)/hatBoatView(id)。视图之间 distanceToSqr(other) 换 distanceSquaredTo(other)，避免与 Entity.distanceToSqr(Entity) 重载歧义；Vec3 同签名保持。hasSightTo(other) 在实体适配内部调用原 hasLineOfSight，clearColliderPath(from,to) 在内部构造带 this 上下文的 ClipContext(COLLIDER,NONE)，仅返回原布尔结果；不得向视图增加原始实体返回值。
- 渲染器覆盖方法、EntityRenderer 泛型、注册事件、EntityBoundSoundInstance 构造和 super 调用保留框架要求的原生实体参数，进入自有显示逻辑后立刻转视图；音频 owner 字段改视图，原生实体只在父类绑定入口使用。HatBoatMotion.rider 将 rider 参数改 YukkuriView，仅用 getPosition(partial)。
- M6 独占 `family/LiveBirthGeometry`、`FamilyFeedingContact`、`HatCarry`、`subspecies/SelfRescueHatGeometry` 四个完整文件。仅将客户端确实调用的 modelOutlet/outlet、mouthDistance/minimumMouthDistance/horizontalForward/rearPoint、hasHat/carryAction/hatPose/riderRotation/seatWorld、bodyUnit/hatUnit/pose/wornOrigin/settledOrigin 改视图参数。exitFront 含服务端 brood 分支，原方法保留，新增 visibleExitFront(YukkuriView,int) 承接原客户端分支；riding 原 Entity 入口保留，新增 visibleRiding(YukkuriView,YukkuriView)，避免交叉重载歧义。mouthsMeet/rearContact 原服务器签名保留，另新增 visibleMouthsMeet/visibleRearContact：身份、存活、同世界、双向视线、身体分离/高度/朝向和豆型排除均照原客户端分支；碰撞射线仍为 caregiver 上下文的 COLLIDER/NONE。clear/plan/valid/pickUp/putDown/faceEachOther 等服务端行动入口保留实体类型；FamilyGeometry.toWorld 的服务器链不需要扩大文件权限。
- `HatBoatView` 从 SEATS/CARGO/MOTION 的复制快照提供运输、座位、拥挤、货物分档和摇晃显示。CARGO 按原 ItemStack 读取与8份截断重建数量，保留旧 Version<3 的侧放语义；不返回 cargo/crowding 组件。漂流、摇晃、平衡的 visible 方法仅用同步字段，重现当前客户端 pullTicks=0 的结果；拖曳同步仍留 S1b 加 TowUntil，现有服务端 drifting() 不改。
- 动态 ID：`"hat_board_"+phase.toLowerCase(Locale.ROOT)` → `Behaviors.hatBoard(phase.toLowerCase(Locale.ROOT))`；`"event_"+card` → `Behaviors.event(card)`（原 replace/大小写处理仍留在调用方）；`"event_badge_"+card` → eventBadge(card)；`id+"_aq"` → aquatic(id)，带是否水生条件时用 aquatic(id, boolean)。不新增规范化、不去重已有后缀。固定值全部使用同名大写 String 常量，保持 switch 常量表达式。

#### W1-D：D6 口径对照

计数按相同语义的独立用途，双向比较不算两个用途；定义数和使用情境数分开。以下差异理由是本次核对后采用的解释，不宣称原实现已有明确设计意图。

| 概念 | 对照与本轮处理 | 理由 |
| --- | --- | --- |
| 复合“严厉” | 原攻击性55/65各1个定义；55用于失饰鄙视、怪物驱赶、制裁执行3个情境，65用于聚落排斥1个情境。本轮统一55，旧常量名和两个方法保留兼容委托。 | 都是失饰排斥/制裁的同一倾向，按实际用途多数收口。自私>=65且自控<65的另一分支保持。攻击性55–64且未满足另一分支者现在也可触发聚落排斥；量刑加重等独立用途的 `AGGRESSION_AT_65` 保持65。 |
| 普通信任 | `trustedPlayer` 的 trust>=10且fear<25保持；亲昵/邀请、自愿携带、收养资格3个用途。 | 普通信任的多数口径为25；外层主人豁免、识别等条件保持。 |
| 教学与食物诱导 | 教学开始的 trust>=10且fear<30、进行中的fear>=30中止保持；食物诱导的fear<30也保持。 | 教学可容忍轻度紧张，较亲昵有不同接触要求；食物诱导只测恐惧，没有信任前提。两者共用 `SOCIAL_FEAR_LIMIT`，不能为统一教学连带改掉诱导门槛。 |
| 抱举与其他恐惧条件 | `acceptsLift` 保持fear<40，无trust条件；揉蹭、事件开场、人类称呼的25边界及额外条件保持。 | 接受抱举、主动亲昵、派生接近分数、称呼分类并非同一许可，不能统一成“信任玩家”。 |
| 好感正向档 | 0=无负面；4=双方相扑邀请；8=水难熟悉者警报；10=亲近；12=自主繁殖双方意愿；15=冒险护友，全部保持。 | 10用于亲近判断已是同口径；认识、应急通知、繁殖意愿和承担战斗风险有不同要求，不能抹平递进档位。 |
| 好感负向档 | -10=不喜欢/和解接受；-20=强敌意欺凌；-40=和解可发起的底线，全部保持。 | 普通负面、强敌意与和解边界不同；比较方向和单向/双向条件照旧。 |
| 不安分级 | 30=良好心情，50=照护警示/甜化累积或嫉妒加权，60=高压力症状，全部保持。 | 属于不同阶段；`<=`/`>=`/`>`边界、整数stress的Math.round读取与浮点unease读取、处罚和累积公式保持，不能据比较次数最多统一为60。 |

### A.3 完整文件所有权

| 文件（整个文件） | 所有者 |
| --- | --- |
| `MCYukkuri.java` | 不迁移 |
| `badge/BadgeItem.java` | M4 |
| `badge/BadgeMenu.java` | M4 |
| `body/BodyCommands.java` | M5 |
| `body/BodyLife.java` | M5 |
| `body/BodyPart.java` | M5 |
| `body/BodySigns.java` | M5 |
| `body/BodyWound.java` | M5 |
| `body/DetachedItems.java` | M5 |
| `body/FillingResource.java` | M5 |
| `body/GraveBlock.java` | M5 |
| `body/GraveBlockEntity.java` | M5 |
| `body/InjuryModel.java` | M5 |
| `body/ModelFingerprint.java` | M5 |
| `body/MouthPlacement.java` | M5 |
| `body/RemainsRot.java` | M5 |
| `body/WoundMigration.java` | M5 |
| `body/WoundPlacement.java` | M5 |
| `body/WoundSurface.java` | M5 |
| `client/AgitationPresentation.java` | M6 |
| `client/AquaticMotionPresentation.java` | M6 |
| `client/BadgeScreen.java` | M6 |
| `client/BraidRescuePresentation.java` | M6 |
| `client/CabinDistressPresentation.java` | M6 |
| `client/CareRenderer.java` | M6 |
| `client/CareScreen.java` | M6 |
| `client/CareWearRenderer.java` | M6 |
| `client/ClientEvents.java` | M6 |
| `client/CradlePickupPresentation.java` | M6 |
| `client/DetachedStemRenderer.java` | M6 |
| `client/DialogueAudio.java` | M6 |
| `client/EcologyAudio.java` | M6 |
| `client/ExamScreen.java` | M6 |
| `client/FactoryTrayRenderer.java` | M6 |
| `client/FamilyRenderer.java` | M6 |
| `client/ForagePileRenderer.java` | M6 |
| `client/GraveRenderer.java` | M6 |
| `client/GuiKit.java` | M6 |
| `client/HatBoatMotion.java` | M6 |
| `client/HatBoatRenderer.java` | M6 |
| `client/HatCargoRenderer.java` | M6 |
| `client/HatDamageFaces.java` | M6 |
| `client/HatDamageMask.java` | M6 |
| `client/HatWaterMaskPass.java` | M6 |
| `client/HeldItemPresentation.java` | M6 |
| `client/HouseNestRenderer.java` | M6 |
| `client/HouseScrapRenderer.java` | M6 |
| `client/HumanRenderer.java` | M6 |
| `client/HumanScreen.java` | M6 |
| `client/KeeperScreen.java` | M6 |
| `client/LiveBirthRenderer.java` | M6 |
| `client/LivingPresentation.java` | M6 |
| `client/MaterialDropRenderer.java` | M6 |
| `client/OperationAttachments.java` | M6 |
| `client/PlaygroundRenderer.java` | M6 |
| `client/PregnancyFaces.java` | M6 |
| `client/PropItemRenderer.java` | M6 |
| `client/PropModels.java` | M6 |
| `client/RapemOverlayRenderer.java` | M6 |
| `client/RemainsRenderer.java` | M6 |
| `client/SelfRescueHatPresentation.java` | M6 |
| `client/ShelterRenderer.java` | M6 |
| `client/ShopScreen.java` | M6 |
| `client/SkinningCache.java` | M6 |
| `client/SpeechBubble.java` | M6 |
| `client/SupplyPileRenderer.java` | M6 |
| `client/ToiletPresentation.java` | M6 |
| `client/WoundRenderer.java` | M6 |
| `client/YukkuriModel.java` | M6 |
| `client/YukkuriRenderer.java` | M6 |
| `cognition/Cognition.java` | M2 |
| `cognition/CognitionData.java` | M2 |
| `cognition/CognitionObservation.java` | M2 |
| `command/BadgeSocialCommands.java` | M5 |
| `command/HouseholdCommands.java` | M5 |
| `command/HumanCommands.java` | M5 |
| `command/MuseumCommands.java` | M5 |
| `command/PlaygroundTools.java` | M5 |
| `command/ScenarioBoats.java` | M5 |
| `command/ScenarioCommands.java` | M5 |
| `command/ScenarioSetup.java` | M5 |
| `command/YukkuriCommands.java` | M5 |
| `dialogue/NativeDialogue.java` | M2 |
| `dialogue/SpeechAddress.java` | M2 |
| `dialogue/SpeechCount.java` | M2 |
| `dialogue/SpeechQuirk.java` | M2 |
| `dialogue/SpeechTiming.java` | M2 |
| `entity/MaterialDrop.java` | E1 |
| `entity/RemainsEntity.java` | E1 |
| `entity/SpeciesTraits.java` | E1 |
| `entity/YukkuriAction.java` | E1 |
| `entity/YukkuriEntity.java` | E1 |
| `entity/YukkuriVariant.java` | E1 |
| `event/Expedition.java` | M3 |
| `event/GroupCards.java` | M3 |
| `event/HomeClaim.java` | M3 |
| `event/HomeClaimCommands.java` | M3 |
| `event/HomeClaimData.java` | M3 |
| `event/HomeClaimGoal.java` | M3 |
| `event/RiverEncounter.java` | M3 |
| `event/WildCards.java` | M3 |
| `event/WildEventData.java` | M3 |
| `event/WildEventGoal.java` | M3 |
| `event/WildEvents.java` | M3 |
| `family/BirthStaging.java` | M1 |
| `family/BraidRescue.java` | M1 |
| `family/BraidRescueGeometry.java` | M1 |
| `family/CareApproach.java` | M1 |
| `family/ChildhoodLife.java` | M1 |
| `family/CradlePickup.java` | M1 |
| `family/DetachedStemBlock.java` | M1 |
| `family/DetachedStemBlockEntity.java` | M1 |
| `family/DetachedStemGeometry.java` | M1 |
| `family/FamilyConfig.java` | M1 |
| `family/FamilyData.java` | M1 |
| `family/FamilyFeedingContact.java` | M6 |
| `family/FamilyGeometry.java` | M1 |
| `family/FamilyLife.java` | M1 |
| `family/GrowthSize.java` | M1 |
| `family/GrowthTiming.java` | M1 |
| `family/HatBoarding.java` | M1 |
| `family/HatCarry.java` | M6 |
| `family/IndependenceLife.java` | M1 |
| `family/LiveBirthGeometry.java` | M6 |
| `family/ShedStemDrops.java` | M1 |
| `freezer/FreezerBlock.java` | M4 |
| `freezer/FreezerModule.java` | M4 |
| `freezer/FreezerStorage.java` | M4 |
| `headwear/Contempt.java` | M2 |
| `headwear/Headwear.java` | M2 |
| `headwear/Recognition.java` | M2 |
| `headwear/Sanction.java` | M2 |
| `house/HouseCrawlControl.java` | M3 |
| `house/HouseCrawlMotion.java` | M3 |
| `house/HouseEggGeometry.java` | M3 |
| `house/HouseEggs.java` | M3 |
| `house/HouseLife.java` | M3 |
| `house/HouseNavigation.java` | M3 |
| `house/HouseNestBlock.java` | M3 |
| `house/HouseNestBlockEntity.java` | M3 |
| `house/HouseholdEcology.java` | M3 |
| `house/HouseholdEcologyData.java` | M3 |
| `house/HouseholdProtection.java` | M3 |
| `house/SupplyPileBlock.java` | M3 |
| `house/SupplyPileBlockEntity.java` | M3 |
| `house/SupplyPileView.java` | M3 |
| `house/SupplyPiles.java` | M3 |
| `human/ExamHideBoxBlock.java` | M4 |
| `human/ExamMarkBlock.java` | M4 |
| `human/ExamPodiumBlock.java` | M4 |
| `human/FactoryBreeding.java` | M4 |
| `human/FactoryPipeline.java` | M4 |
| `human/FactoryProduction.java` | M4 |
| `human/FactoryStationBlock.java` | M4 |
| `human/FactoryTray.java` | M4 |
| `human/FactoryWorkGoal.java` | M4 |
| `human/HumanBuildingStructure.java` | M4 |
| `human/HumanCommerce.java` | M4 |
| `human/HumanFolk.java` | M4 |
| `human/HumanMenu.java` | M4 |
| `human/HumanNpc.java` | M4 |
| `human/HumanScenes.java` | M4 |
| `human/HumanWalk.java` | M4 |
| `human/HumanWorld.java` | M4 |
| `human/LevelledPoolElement.java` | M4 |
| `human/LiveBagItem.java` | M4 |
| `interaction/CareAction.java` | M4 |
| `interaction/CareMenu.java` | M4 |
| `interaction/ItemContact.java` | M4 |
| `interaction/OperationLife.java` | M4 |
| `item/AccessoryItem.java` | M4 |
| `item/AccessoryShardItem.java` | M4 |
| `item/CarrierItem.java` | M4 |
| `item/CompanionCordItem.java` | M4 |
| `item/FillingItem.java` | M4 |
| `item/HouseEggItem.java` | M4 |
| `item/HouseholdSprayItem.java` | M4 |
| `item/NotebookItem.java` | M4 |
| `item/OperationToolItem.java` | M4 |
| `item/PetHarnessItem.java` | M4 |
| `item/RemainsItem.java` | M4 |
| `item/ShelterItem.java` | M4 |
| `item/SupplyItem.java` | M4 |
| `item/SwaddleItem.java` | M4 |
| `item/TeachingWhistleItem.java` | M4 |
| `item/TreatmentItem.java` | M4 |
| `item/WashClothItem.java` | M4 |
| `life/AgitationLife.java` | M5 |
| `life/CareBlock.java` | M5 |
| `life/CareBlockEntity.java` | M5 |
| `life/Consumables.java` | M5 |
| `life/Facilities.java` | M5 |
| `life/InfantFood.java` | M5 |
| `life/SpeciesLife.java` | M5 |
| `life/SurfaceApproach.java` | M5 |
| `life/SurfaceLife.java` | M5 |
| `life/Sweets.java` | M5 |
| `life/TemperatureLife.java` | M5 |
| `life/ToiletContact.java` | M5 |
| `life/Toxins.java` | M5 |
| `life/WaterDistress.java` | M5 |
| `life/YukkuriConfig.java` | M5 |
| `life/YukkuriLife.java` | M5 |
| `life/YukkuriWorldData.java` | M5 |
| `mind/GivenNames.java` | M2 |
| `mind/MindLife.java` | M2 |
| `mind/Naming.java` | M2 |
| `notebook/NotebookGuide.java` | M4 |
| `notebook/SurvivalProgress.java` | M4 |
| `pet/AbandonLog.java` | M4 |
| `pet/Ability.java` | M4 |
| `pet/BadgeGrade.java` | M4 |
| `pet/ExamRecord.java` | M4 |
| `pet/LeashGuard.java` | M4 |
| `pet/PetCare.java` | M4 |
| `pet/PetLessons.java` | M4 |
| `pet/PetLife.java` | M4 |
| `pet/PetPrivileges.java` | M4 |
| `pet/PetRequests.java` | M4 |
| `pet/PetScenes.java` | M4 |
| `pet/PetWalk.java` | M4 |
| `pet/SceneReset.java` | M4 |
| `pet/WildEncounter.java` | M4 |
| `pet/exam/ExamContext.java` | M4 |
| `pet/exam/ExamHall.java` | M4 |
| `pet/exam/ExamItem.java` | M4 |
| `pet/exam/ExamMenu.java` | M4 |
| `pet/exam/ExamPlan.java` | M4 |
| `pet/exam/ExamSession.java` | M4 |
| `pet/exam/SimpleCheckItem.java` | M4 |
| `pet/exam/items/OralReadItem.java` | M4 |
| `pet/exam/items/OralSumItem.java` | M4 |
| `pet/exam/items/PoiseTroubleItem.java` | M4 |
| `pet/exam/items/PoiseWildItem.java` | M4 |
| `pet/exam/items/Scene.java` | M4 |
| `pet/exam/items/SocialFeedItem.java` | M4 |
| `pet/exam/items/SocialFightItem.java` | M4 |
| `pet/exam/items/SocialSleepItem.java` | M4 |
| `pet/exam/items/SocialToiletItem.java` | M4 |
| `pet/exam/items/WitsHideItem.java` | M4 |
| `pet/exam/items/WitsInterviewItem.java` | M4 |
| `pet/exam/items/WitsPromiseItem.java` | M4 |
| `pet/social/BadgeArcPolicy.java` | M4 |
| `pet/social/BadgeArcs.java` | M4 |
| `pet/social/BadgeBudget.java` | M4 |
| `pet/social/BadgeLedger.java` | M4 |
| `pet/social/BadgePraise.java` | M4 |
| `pet/social/BadgeSocial.java` | M4 |
| `pet/stage/Staging.java` | M4 |
| `play/PlayBlock.java` | M4 |
| `play/PlayBlockEntity.java` | M4 |
| `play/PlayGeometry.java` | M4 |
| `play/PlayItem.java` | M4 |
| `play/PlayLife.java` | M4 |
| `play/Playgrounds.java` | M4 |
| `predator/PredatorLife.java` | M4 |
| `rare/MyonLife.java` | M4 |
| `rare/RareForms.java` | M4 |
| `registry/ModBlocks.java` | M5 |
| `registry/ModCreativeTabs.java` | M5 |
| `registry/ModEntities.java` | M5 |
| `registry/ModItems.java` | M5 |
| `registry/ModMenus.java` | M5 |
| `registry/ModSounds.java` | M5 |
| `season/AutumnStock.java` | M3 |
| `season/SeasonCalendar.java` | M3 |
| `season/SeasonClimate.java` | M3 |
| `season/WinteringLife.java` | M3 |
| `settlement/Burrowing.java` | M3 |
| `settlement/CommonHunt.java` | M3 |
| `settlement/CommonStore.java` | M3 |
| `settlement/Commons.java` | M3 |
| `settlement/CommonsCommands.java` | M3 |
| `settlement/CommunityRules.java` | M3 |
| `settlement/ForageClaims.java` | M3 |
| `settlement/ForageGoal.java` | M3 |
| `settlement/ForagePileBlock.java` | M3 |
| `settlement/ForagePileBlockEntity.java` | M3 |
| `settlement/ForageTrip.java` | M3 |
| `settlement/Grazing.java` | M3 |
| `settlement/HabitatLife.java` | M3 |
| `settlement/Homes.java` | M3 |
| `settlement/LeaderSuccession.java` | M3 |
| `settlement/Levy.java` | M3 |
| `settlement/Migration.java` | M3 |
| `settlement/NaturalShelter.java` | M3 |
| `settlement/PileContact.java` | M3 |
| `settlement/PlantBedding.java` | M3 |
| `settlement/Rations.java` | M3 |
| `settlement/Rumours.java` | M3 |
| `settlement/Sanction.java` | M3 |
| `settlement/SearchBudget.java` | M3 |
| `settlement/Season.java` | M3 |
| `settlement/SeasonCommands.java` | M3 |
| `settlement/Seasons.java` | M3 |
| `settlement/SettlementCommands.java` | M3 |
| `settlement/SettlementComposition.java` | M3 |
| `settlement/SettlementData.java` | M3 |
| `settlement/SettlementDuties.java` | M3 |
| `settlement/SettlementLife.java` | M3 |
| `settlement/SettlementObservation.java` | M3 |
| `settlement/SettlementSociety.java` | M3 |
| `settlement/SettlementSpawner.java` | M3 |
| `settlement/ShelterBlock.java` | M3 |
| `settlement/ShelterBlockEntity.java` | M3 |
| `settlement/ShelterGeometry.java` | M3 |
| `settlement/ShelterPlacement.java` | M3 |
| `settlement/SiteClaimer.java` | M3 |
| `settlement/SiteLife.java` | M3 |
| `settlement/SiteUpkeep.java` | M3 |
| `settlement/site/SiteBuilder.java` | M3 |
| `settlement/site/SiteGeom.java` | M3 |
| `settlement/site/SitePlan.java` | M3 |
| `settlement/site/SitePlanner.java` | M3 |
| `settlement/site/SiteProfile.java` | M3 |
| `settlement/site/SiteRng.java` | M3 |
| `settlement/site/SiteSlot.java` | M3 |
| `settlement/site/SiteTier.java` | M3 |
| `settlement/site/SiteWorld.java` | M3 |
| `settlement/site/WildSettlementPiece.java` | M3 |
| `settlement/site/WildSettlementStructure.java` | M3 |
| `shop/CommodityLife.java` | M4 |
| `shop/KeeperMenu.java` | M4 |
| `shop/ShopCatalog.java` | M4 |
| `shop/ShopCourier.java` | M4 |
| `shop/ShopDate.java` | M4 |
| `shop/ShopDeliveries.java` | M4 |
| `shop/ShopKeeping.java` | M4 |
| `shop/ShopMenu.java` | M4 |
| `shop/ShopOrders.java` | M4 |
| `shop/ShopPens.java` | M4 |
| `shop/ShopPerformance.java` | M4 |
| `shop/ShopPricing.java` | M4 |
| `shop/ShopRegistry.java` | M4 |
| `shop/ShopStock.java` | M4 |
| `shop/ShopTrade.java` | M4 |
| `social/Cannibalism.java` | M2 |
| `social/DailyLife.java` | M2 |
| `social/EcologyPresentation.java` | M2 |
| `social/EnvironmentExperience.java` | M2 |
| `social/GriefLife.java` | M2 |
| `social/GroupInteractionGoal.java` | M2 |
| `social/SelfOffering.java` | M2 |
| `social/TreasureLife.java` | M2 |
| `social/WoundComfort.java` | M2 |
| `social/YukkuriPersonality.java` | M2 |
| `social/YukkuriSocial.java` | M2 |
| `status/Identity.java` | M2 |
| `status/Marks.java` | M2 |
| `status/Reactions.java` | M2 |
| `status/RelationChangedEvent.java` | M2 |
| `status/Sky.java` | M2 |
| `status/StatusChangedEvent.java` | M2 |
| `status/StatusCommands.java` | M2 |
| `status/StatusEvents.java` | M2 |
| `status/StatusKey.java` | M2 |
| `status/StatusLife.java` | M2 |
| `status/YukkuriStatus.java` | M2 |
| `subspecies/AquaDiag.java` | M5 |
| `subspecies/AquaticDailyLife.java` | M5 |
| `subspecies/AquaticFamilyLife.java` | M5 |
| `subspecies/AquaticLanding.java` | M5 |
| `subspecies/AquaticLife.java` | M5 |
| `subspecies/AquaticNavigation.java` | M5 |
| `subspecies/AquaticSelfRescue.java` | M5 |
| `subspecies/AquaticShore.java` | M5 |
| `subspecies/FirstVoyage.java` | M5 |
| `subspecies/HatBoatCargo.java` | M5 |
| `subspecies/HatBoatControls.java` | M5 |
| `subspecies/HatBoatCrowding.java` | M5 |
| `subspecies/HatBoatEntity.java` | M5 |
| `subspecies/HatBoatRoute.java` | M5 |
| `subspecies/HatCareAnchor.java` | M5 |
| `subspecies/HatCareGeometry.java` | M5 |
| `subspecies/HatCargoLayout.java` | M5 |
| `subspecies/HatDock.java` | M5 |
| `subspecies/HatFamilyAlarm.java` | M5 |
| `subspecies/HatFamilyCare.java` | M5 |
| `subspecies/HatFamilyPaddle.java` | M5 |
| `subspecies/HatInteriorLayout.java` | M5 |
| `subspecies/HatRoomProbe.java` | M5 |
| `subspecies/HatShoreForage.java` | M5 |
| `subspecies/HatSurfaceRoute.java` | M5 |
| `subspecies/HatVessel.java` | M5 |
| `subspecies/SelfRescueHatGeometry.java` | M6 |
| `subspecies/ShellRetraction.java` | M5 |
| `subspecies/SubspeciesAssets.java` | M5 |
| `subspecies/SubspeciesLife.java` | M5 |

API 新文件归 W1-API；`YukkuriEntity`、`HatBoatEntity` 在本 PR 只接入接口和必要只读适配，`BodyLife` 只新增 condition()；后续 M 包按上表取得完整文件，entity 仍只交 E1。

### A.4 逐处迁移与保留项

#### M1

| 位置 | 类别／决定 | 旧表达式 → 新入口 | 边界 |
| --- | --- | --- | --- |
| `family/BraidRescueGeometry.java:149` | 物种个例／保留个例 | `if(actor.getVariant()!=io.github.longshou.mcyukkuri.entity.YukkuriVariant.MARISA&#124;&#124;!DATA.has("body_min"))return envelope(actor,feet);` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `family/BraidRescueGeometry.java:212` | 物种个例／保留个例 | `if(actor.getVariant()!=io.github.longshou.mcyukkuri.entity.YukkuriVariant.MARISA&#124;&#124;!DATA.has("body_min"))return envelopeClearance(boxes,hat,feet,dip);` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `family/BraidRescueGeometry.java:240` | 物种个例／保留个例 | `if(actor.getVariant()==io.github.longshou.mcyukkuri.entity.YukkuriVariant.MARISA&&actor.life().hasAccessory()){` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `family/CareApproach.java:40` | 关系保留／保留个例 | `&& owner.family().caresFor(child) && !owner.life().hasUrgentNeed()` → `原样保留` | 既有单向、带成年/幼年门槛的命名查询，不改成 currentCarePair 双向或无年龄 CARE。 |
| `family/CareApproach.java:49` | 行为／迁移 | `"care_wait"` → `Behaviors.CARE_WAIT` |  |
| `family/CareApproach.java:49` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `family/CareApproach.java:52` | 行为／迁移 | `"first_toilet_wait"` → `Behaviors.FIRST_TOILET_WAIT` |  |
| `family/CareApproach.java:53` | 行为／迁移 | `"first_toilet_stimulated"` → `Behaviors.FIRST_TOILET_STIMULATED` |  |
| `family/CareApproach.java:53` | 行为／迁移 | `"ground_toilet"` → `Behaviors.GROUND_TOILET` |  |
| `family/ChildhoodLife.java:55` | 物种／迁移 | `entity.getVariant().isPredator()` → `entity.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `family/ChildhoodLife.java:55` | 物种／迁移 | `entity.getVariant().isHouse()` → `entity.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `family/ChildhoodLife.java:254` | 行为／迁移 | `"first_toilet_assist"` → `Behaviors.FIRST_TOILET_ASSIST` |  |
| `family/ChildhoodLife.java:254` | 行为／迁移 | `"first_toilet_seek"` → `Behaviors.FIRST_TOILET_SEEK` |  |
| `family/ChildhoodLife.java:254` | 行为／迁移 | `"care_finish"` → `Behaviors.CARE_FINISH` |  |
| `family/ChildhoodLife.java:256` | 行为／迁移 | `"learn_jump"` → `Behaviors.LEARN_JUMP` |  |
| `family/ChildhoodLife.java:257` | 行为／迁移 | `"learn_watch"` → `Behaviors.LEARN_WATCH` |  |
| `family/ChildhoodLife.java:258` | 行为／迁移 | `"newborn_crawl"` → `Behaviors.NEWBORN_CRAWL` |  |
| `family/ChildhoodLife.java:259` | 行为／迁移 | `"learn_jump"` → `Behaviors.LEARN_JUMP` |  |
| `family/ChildhoodLife.java:260` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `family/ChildhoodLife.java:268` | 行为／迁移 | `"first_toilet_wait"` → `Behaviors.FIRST_TOILET_WAIT` |  |
| `family/ChildhoodLife.java:270` | 行为／迁移 | `"first_toilet_stimulated"` → `Behaviors.FIRST_TOILET_STIMULATED` |  |
| `family/ChildhoodLife.java:270` | 行为／迁移 | `"first_toilet_wait"` → `Behaviors.FIRST_TOILET_WAIT` |  |
| `family/ChildhoodLife.java:270` | 行为／迁移 | `"ground_toilet"` → `Behaviors.GROUND_TOILET` |  |
| `family/ChildhoodLife.java:277` | 关系保留／保留个例 | `other -> entity.family().caresFor(other) && !other.isPassenger() && childAvailable(other)` → `原样保留` | 既有单向、带成年/幼年门槛的命名查询，不改成 currentCarePair 双向或无年龄 CARE。 |
| `family/ChildhoodLife.java:287` | 关系保留／保留个例 | `other -> entity.family().caresFor(other) && other.childhood().readyToPractiseJump()` → `原样保留` | 既有单向、带成年/幼年门槛的命名查询，不改成 currentCarePair 双向或无年龄 CARE。 |
| `family/ChildhoodLife.java:324` | 关系保留／保留个例 | `&& entity.family().caresFor(target) && !target.isPassenger() && childAvailable(target) && target.childhood().needsAssistedFirstToilet();` → `原样保留` | 既有单向、带成年/幼年门槛的命名查询，不改成 currentCarePair 双向或无年龄 CARE。 |
| `family/ChildhoodLife.java:408` | 关系保留／保留个例 | `&#124;&#124; !parent.family().caresFor(entity) &#124;&#124; !(hatAssistance &#124;&#124; parent.childhood().task == Task.FIRST_TOILET` → `原样保留` | 既有单向、带成年/幼年门槛的命名查询，不改成 currentCarePair 双向或无年龄 CARE。 |
| `family/ChildhoodLife.java:449` | 物种个例／保留个例 | `private boolean shellForm() { return entity.subspecies().kind() == SubspeciesLife.Kind.SHELL_MARISA; }` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `family/ChildhoodLife.java:474` | 关系保留／保留个例 | `var mother = person(entity.family().mother());` → `原样保留` | 身份读取、持久化、展示、收件人遍历或特指母方的条件，不替换为泛化亲属查询。 |
| `family/ChildhoodLife.java:476` | 关系保留／保留个例 | `var father = person(entity.family().father());` → `原样保留` | 身份读取、持久化、展示、收件人遍历或特指母方的条件，不替换为泛化亲属查询。 |
| `family/CradlePickup.java:54` | 物种个例／保留个例 | `&#124;&#124;!parent.isAlive()&#124;&#124;parent.isPassenger()&#124;&#124;interrupted(parent)&#124;&#124;interrupted(young)&#124;&#124;home.unsteadyMotion()&#124;&#124;parent.getVariant()!=YukkuriVariant.MARISA` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `family/CradlePickup.java:210` | 关系／迁移 | `parent.getUUID().equals(young.family().mother())&#124;&#124;parent.getUUID().equals(young.family().father())` → `Bonds.parentOf(parent, young)` | 只认直接父母，无年龄门槛；不扩大为兄弟姐妹、照护亲属或群体。 |
| `family/FamilyData.java:104` | 关系保留／保留个例 | `if (record.mother == null && entity.family().mother() != null) {` → `原样保留` | 身份读取、持久化、展示、收件人遍历或特指母方的条件，不替换为泛化亲属查询。 |
| `family/FamilyData.java:105` | 关系保留／保留个例 | `record.mother=entity.family().mother(); record.father=entity.family().father(); record.brood=entity.family().birthId();` → `原样保留` | 身份读取、持久化、展示、收件人遍历或特指母方的条件，不替换为泛化亲属查询。 |
| `family/FamilyData.java:171` | 物种／迁移 | `mother.getVariant().isHouse()` → `mother.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `family/FamilyData.java:171` | 物种／迁移 | `father.getVariant().isHouse()` → `father.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `family/FamilyData.java:172` | 物种／迁移 | `mother.getVariant().isPredator()` → `mother.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `family/FamilyData.java:172` | 物种／迁移 | `father.getVariant().isPredator()` → `father.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `family/FamilyData.java:173` | 物种／迁移 | `io.github.longshou.mcyukkuri.subspecies.HatVessel.aquatic(mother)` → `mother.profile().aquatic()` |  |
| `family/FamilyData.java:174` | 物种／迁移 | `io.github.longshou.mcyukkuri.subspecies.HatVessel.aquatic(father)` → `father.profile().aquatic()` |  |
| `family/FamilyData.java:184` | 物种／迁移 | `mother.getVariant().isHouse()` → `mother.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `family/FamilyData.java:185` | 物种／迁移 | `mother.getVariant().isPredator()` → `mother.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `family/FamilyData.java:247` | 物种个例／保留个例 | `if(source.getVariant()!=YukkuriVariant.ALICE&#124;&#124;!source.isAlive()&#124;&#124;!source.family().adult()` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `family/FamilyData.java:248` | 身体候选／保留个例 | `&#124;&#124;source.body().dying()&#124;&#124;!source.body().coreIntact())return "来源必须是未濒死、核心完好的存活成年爱丽丝";` → `保留原标量读取` | 数值展示、持久化、单项判断或公式；下方指定的合并判断除外。 |
| `family/FamilyLife.java:142` | 物种／迁移 | `entity.getVariant().isPredator()` → `entity.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `family/FamilyLife.java:142` | 物种／迁移 | `entity.getVariant().isHouse()` → `entity.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `family/FamilyLife.java:167` | 行为／迁移 | `"pairing_accept"` → `Behaviors.PAIRING_ACCEPT` |  |
| `family/FamilyLife.java:167` | 行为／迁移 | `"courtship"` → `Behaviors.COURTSHIP` |  |
| `family/FamilyLife.java:167` | 行为／迁移 | `"pairing"` → `Behaviors.PAIRING` |  |
| `family/FamilyLife.java:193` | 关系／迁移 | `entity.getUUID().equals(child.family().mother)&#124;&#124;entity.getUUID().equals(child.family().father)` → `Bonds.parentOf(entity, child)` | 仅替换直接亲本子式；caresFor 的成年/幼年条件、youngAround 半径、closestParent 排序、bereave 的 age<1200 与 else-if 顺序保持。禁止改为 currentCarePair 造成递归。 |
| `family/FamilyLife.java:215` | 物种／迁移 | `HatVessel.aquatic(entity)` → `entity.profile().aquatic()` |  |
| `family/FamilyLife.java:215` | 行为／迁移 | `"_aq"` → `Behaviors.AQUATIC_SUFFIX` |  |
| `family/FamilyLife.java:215` | 行为构造／迁移 | `String big=HatVessel.aquatic(entity)?"_aq":"";` → `boolean aquatic=entity.profile().aquatic();` | 原 task 名小写或条件后缀逐值保持；同步/存档字符串不变。此完整表达式优先于同行内部常量映射。 |
| `family/FamilyLife.java:216` | 行为／迁移 | `"hat_off_self"` → `Behaviors.HAT_OFF_SELF` |  |
| `family/FamilyLife.java:216` | 行为／迁移 | `"hat_on_self"` → `Behaviors.HAT_ON_SELF` |  |
| `family/FamilyLife.java:216` | 行为／迁移 | `"hat_off"` → `Behaviors.HAT_OFF` |  |
| `family/FamilyLife.java:216` | 行为／迁移 | `"hat_on"` → `Behaviors.HAT_ON` |  |
| `family/FamilyLife.java:216` | 行为构造／迁移 | `switch(hatMove){case OFF->"hat_off";case ON->"hat_on";case OFF_SELF->"hat_off_self";default->"hat_on_self";}+big` → `Behaviors.aquatic(switch(hatMove){case OFF->Behaviors.HAT_OFF;case ON->Behaviors.HAT_ON;case OFF_SELF->Behaviors.HAT_OFF_SELF;default->Behaviors.HAT_ON_SELF;}, aquatic)` | 原 task 名小写或条件后缀逐值保持；同步/存档字符串不变。此完整表达式优先于同行内部常量映射。 |
| `family/FamilyLife.java:217` | 行为／迁移 | `"nuzzle"` → `Behaviors.NUZZLE` |  |
| `family/FamilyLife.java:218` | 行为／迁移 | `"labor"` → `Behaviors.LABOR` |  |
| `family/FamilyLife.java:219` | 行为／迁移 | `"postpartum"` → `Behaviors.POSTPARTUM` |  |
| `family/FamilyLife.java:221` | 行为／迁移 | `"follow_parent"` → `Behaviors.FOLLOW_PARENT` |  |
| `family/FamilyLife.java:221` | 行为／迁移 | `"tend_brood"` → `Behaviors.TEND_BROOD` |  |
| `family/FamilyLife.java:222` | 行为／迁移 | `"search_family"` → `Behaviors.SEARCH_FAMILY` |  |
| `family/FamilyLife.java:222` | 行为／迁移 | `"wait_child"` → `Behaviors.WAIT_CHILD` |  |
| `family/FamilyLife.java:223` | 行为／迁移 | `"hat_hold"` → `Behaviors.HAT_HOLD` |  |
| `family/FamilyLife.java:223` | 行为／迁移 | `"midwife"` → `Behaviors.MIDWIFE` |  |
| `family/FamilyLife.java:223` | 行为构造／迁移 | `"hat_hold"+big` → `Behaviors.aquatic(Behaviors.HAT_HOLD, aquatic)` | 原 task 名小写或条件后缀逐值保持；同步/存档字符串不变。此完整表达式优先于同行内部常量映射。 |
| `family/FamilyLife.java:224` | 行为／迁移 | `"care_finish"` → `Behaviors.CARE_FINISH` |  |
| `family/FamilyLife.java:224` | 行为／迁移 | `"family_chew"` → `Behaviors.FAMILY_CHEW` |  |
| `family/FamilyLife.java:224` | 行为／迁移 | `"feed_child"` → `Behaviors.FEED_CHILD` |  |
| `family/FamilyLife.java:225` | 行为／迁移 | `"newborn_fall"` → `Behaviors.NEWBORN_FALL` |  |
| `family/FamilyLife.java:225` | 行为／迁移 | `"postpartum"` → `Behaviors.POSTPARTUM` |  |
| `family/FamilyLife.java:225` | 行为／迁移 | `"stay_home"` → `Behaviors.STAY_HOME` |  |
| `family/FamilyLife.java:225` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `family/FamilyLife.java:264` | 物种个例／保留个例 | `return y.getVariant()==io.github.longshou.mcyukkuri.entity.YukkuriVariant.MARISA&&y.life().hasAccessory()&&!y.isPassenger()` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `family/FamilyLife.java:265` | 物种个例／保留个例 | `&&y.subspecies().kind()!=io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Kind.SHELL_MARISA` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `family/FamilyLife.java:266` | 物种个例／保留个例 | `&&y.subspecies().kind()!=io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Kind.KOTATSU_MARISA; // a kotatsu is no hat to catch in` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `family/FamilyLife.java:280` | 物种／迁移 | `HatVessel.aquatic(catcher)` → `catcher.profile().aquatic()` |  |
| `family/FamilyLife.java:315` | 物种／迁移 | `HatVessel.aquatic(catcher)` → `catcher.profile().aquatic()` |  |
| `family/FamilyLife.java:363` | 关系／迁移 | `entity.getUUID().equals(y.family().mother)&#124;&#124;entity.getUUID().equals(y.family().father)` → `Bonds.parentOf(entity, y)` | 仅替换直接亲本子式；caresFor 的成年/幼年条件、youngAround 半径、closestParent 排序、bereave 的 age<1200 与 else-if 顺序保持。禁止改为 currentCarePair 造成递归。 |
| `family/FamilyLife.java:478` | 关系保留／保留个例 | `var mother=entity.family().mother()!=null&&level().getEntity(entity.family().mother()) instanceof YukkuriEntity m?m:null;` → `原样保留` | 身份读取、持久化、展示、收件人遍历或特指母方的条件，不替换为泛化亲属查询。 |
| `family/FamilyLife.java:726` | 物种个例／保留个例 | `if(entity.subspecies().kind()!=io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Kind.AQUATIC_MARISA)return false;` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `family/FamilyLife.java:862` | 物种／迁移 | `entity.getVariant().isHouse()` → `entity.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `family/FamilyLife.java:910` | 身体候选／保留个例 | `&& !entity.body().dying() && entity.body().coreIntact()` → `保留原标量读取` | 数值展示、持久化、单项判断或公式；下方指定的合并判断除外。 |
| `family/FamilyLife.java:911` | 物种／迁移 | `entity.getVariant().isPredator()` → `entity.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `family/FamilyLife.java:911` | 物种／迁移 | `entity.getVariant().isHouse()` → `entity.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `family/FamilyLife.java:941` | 物种／迁移 | `entity.getVariant().isPredator()` → `entity.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `family/FamilyLife.java:941` | 物种／迁移 | `entity.getVariant().isHouse()` → `entity.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `family/FamilyLife.java:949` | 身体／迁移 | `entity.body().wound()==0&&entity.body().infection()==0` → `entity.body().condition().readyForReproduction()` |  |
| `family/FamilyLife.java:962` | 物种／迁移 | `entity.getVariant().isHouse()` → `entity.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `family/FamilyLife.java:971` | 物种／迁移 | `entity.getVariant().isHouse()` → `entity.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `family/FamilyLife.java:971` | 物种／迁移 | `other.getVariant().isHouse()` → `other.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `family/FamilyLife.java:972` | 物种／迁移 | `HatVessel.aquatic(entity)` → `entity.profile().aquatic()` |  |
| `family/FamilyLife.java:972` | 物种／迁移 | `HatVessel.aquatic(other)` → `other.profile().aquatic()` |  |
| `family/FamilyLife.java:973` | 物种／迁移 | `entity.getVariant().isPredator()` → `entity.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `family/FamilyLife.java:973` | 物种／迁移 | `other.getVariant().isPredator()` → `other.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `family/FamilyLife.java:976` | 关系／迁移 | `data.related(entity.getUUID(),other.getUUID())` → `Bonds.biological(entity, other)` | 只替换既有账本查询；识别、配对兼容、存活与好感条件仍留调用方。 |
| `family/FamilyLife.java:984` | 身体候选／保留个例 | `return adult()&&!sterile()&&!elder()&&recovery==0&&entity.isAlive()&&!entity.body().dying()&&entity.body().coreIntact()&&!entity.isPanicking()&&!entity.isCommandPlaying()&&!entity.operations().active()&&!entity.predator().active()&&!entity.agitation().blocksCourtship()` → `保留原标量读取` | 数值展示、持久化、单项判断或公式；下方指定的合并判断除外。 |
| `family/FamilyLife.java:987` | 身体／迁移 | `entity.body().wound()==0&&entity.body().infection()==0` → `entity.body().condition().readyForReproduction()` |  |
| `family/FamilyLife.java:999` | 物种／迁移 | `entity.getVariant().isPredator()` → `entity.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `family/FamilyLife.java:999` | 物种／迁移 | `other.getVariant().isPredator()` → `other.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `family/FamilyLife.java:999` | 物种／迁移 | `entity.getVariant().isHouse()` → `entity.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `family/FamilyLife.java:999` | 物种／迁移 | `other.getVariant().isHouse()` → `other.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `family/FamilyLife.java:999` | 物种／迁移 | `HatVessel.aquatic(entity)` → `entity.profile().aquatic()` |  |
| `family/FamilyLife.java:999` | 物种／迁移 | `HatVessel.aquatic(other)` → `other.profile().aquatic()` |  |
| `family/FamilyLife.java:1001` | 关系／迁移 | `FamilyData.get(level()).related(entity.getUUID(),other.getUUID())` → `Bonds.biological(entity, other)` | 只替换既有账本查询；识别、配对兼容、存活与好感条件仍留调用方。 |
| `family/FamilyLife.java:1012` | 物种／迁移 | `entity.getVariant().isPredator()` → `entity.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `family/FamilyLife.java:1012` | 物种／迁移 | `other.getVariant().isPredator()` → `other.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `family/FamilyLife.java:1012` | 物种／迁移 | `entity.getVariant().isHouse()` → `entity.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `family/FamilyLife.java:1012` | 物种／迁移 | `other.getVariant().isHouse()` → `other.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `family/FamilyLife.java:1019` | 关系／迁移 | `data.related(entity.getUUID(),other.getUUID())` → `Bonds.biological(entity, other)` | 只替换既有账本查询；识别、配对兼容、存活与好感条件仍留调用方。 |
| `family/FamilyLife.java:1019` | 阈值／迁移 | `affinity(entity.getUUID(),other.getUUID())<12` → `affinity(entity.getUUID(),other.getUUID())<BondLevels.AFFINITY_COOPERATIVE` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `family/FamilyLife.java:1019` | 阈值／迁移 | `affinity(other.getUUID(),entity.getUUID())<12` → `affinity(other.getUUID(),entity.getUUID())<BondLevels.AFFINITY_COOPERATIVE` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `family/FamilyLife.java:1029` | 身体／迁移 | `entity.body().wound()>0&#124;&#124;entity.body().infection()>0` → `entity.body().condition().blocksBreedingApproach()` | 先替换最长复合表达式；保留其余健康、饥饿、识别或行动条件。 |
| `family/FamilyLife.java:1063` | 物种／留 R5 | `if(entity.getVariant().isHouse())return FamilyData.BirthKind.EGG;` → `原样保留` | 整套例程、住所路由、出生方式选择或按物种存读分派；E1 生命周期搬迁时同样不合并这些分支。 |
| `family/FamilyLife.java:1064` | 物种／留 R5 | `if(entity.getVariant().isPredator())return FamilyData.BirthKind.STEM;` → `原样保留` | 整套例程、住所路由、出生方式选择或按物种存读分派；E1 生命周期搬迁时同样不合并这些分支。 |
| `family/FamilyLife.java:1071` | 身体／迁移 | `entity.body().wound()==0&&entity.body().infection()==0` → `entity.body().condition().readyForDelivery()` |  |
| `family/FamilyLife.java:1335` | 关系保留／保留个例 | `&&(other.getUUID().equals(partner)&#124;&#124;entity.getUUID().equals(other.family().mother())));` → `原样保留` | 身份读取、持久化、展示、收件人遍历或特指母方的条件，不替换为泛化亲属查询。 |
| `family/FamilyLife.java:1358` | 关系保留／保留个例 | `&&entity.getUUID().equals(other.family().mother())&&!other.family().adult()).stream().min(Comparator.comparingDouble(entity::distanceToSqr)).orElse(null);` → `原样保留` | 身份读取、持久化、展示、收件人遍历或特指母方的条件，不替换为泛化亲属查询。 |
| `family/FamilyLife.java:1417` | 关系／迁移 | `entity.getUUID().equals(other.family().mother)&#124;&#124;entity.getUUID().equals(other.family().father)` → `Bonds.parentOf(entity, other)` | 仅替换直接亲本子式；caresFor 的成年/幼年条件、youngAround 半径、closestParent 排序、bereave 的 age<1200 与 else-if 顺序保持。禁止改为 currentCarePair 造成递归。 |
| `family/FamilyLife.java:1420` | 物种／迁移 | `entity.getVariant().isHouse()` → `entity.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `family/FamilyLife.java:1437` | 物种个例／保留个例 | `if(entity.getVariant()!=io.github.longshou.mcyukkuri.entity.YukkuriVariant.MARISA&#124;&#124;partner==null&#124;&#124;entity.isPassenger()` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `family/FamilyLife.java:1460` | 关系／迁移 | `other.getUUID().equals(mother)&#124;&#124;other.getUUID().equals(father)` → `Bonds.parentOf(other, entity)` | 仅替换直接亲本子式；caresFor 的成年/幼年条件、youngAround 半径、closestParent 排序、bereave 的 age<1200 与 else-if 顺序保持。禁止改为 currentCarePair 造成递归。 |
| `family/FamilyLife.java:1478` | 物种／迁移 | `entity.getVariant().isPredator()` → `entity.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `family/FamilyLife.java:1478` | 物种／迁移 | `entity.getVariant().isHouse()` → `entity.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `family/FamilyLife.java:1479` | 物种／迁移 | `HatVessel.aquatic(entity)` → `entity.profile().aquatic()` |  |
| `family/FamilyLife.java:1645` | 物种个例／保留个例 | `if(entity.getVariant()==io.github.longshou.mcyukkuri.entity.YukkuriVariant.MARISA&&target.getVariant()==io.github.longshou.mcyukkuri.entity.YukkuriVariant.REIMU){mother=target;father=entity;}` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `family/FamilyLife.java:1713` | 关系／迁移 | `other.getUUID().equals(mother)&#124;&#124;other.getUUID().equals(father)` → `Bonds.parentOf(other, entity)` | 仅替换直接亲本子式；caresFor 的成年/幼年条件、youngAround 半径、closestParent 排序、bereave 的 age<1200 与 else-if 顺序保持。禁止改为 currentCarePair 造成递归。 |
| `family/FamilyLife.java:1716` | 关系／迁移 | `data.socialKin(entity.getUUID(),other.getUUID())` → `Bonds.careKin(entity, other)` | 只替换既有账本查询；识别、配对兼容、存活与好感条件仍留调用方。 |
| `family/HatBoarding.java:458` | 物种个例／保留个例 | `if(actualGrip==null&#124;&#124;actor.getVariant()!=io.github.longshou.mcyukkuri.entity.YukkuriVariant.MARISA){lastClimbPlan="no reachable grip/model";return false;}` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `family/IndependenceLife.java:60` | 行为／迁移 | `"independence_farewell"` → `Behaviors.INDEPENDENCE_FAREWELL` |  |
| `family/IndependenceLife.java:60` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `family/IndependenceLife.java:70` | 物种／迁移 | `entity.getVariant().isPredator()` → `entity.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `family/IndependenceLife.java:70` | 物种／迁移 | `entity.getVariant().isHouse()` → `entity.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `family/IndependenceLife.java:71` | 物种／迁移 | `io.github.longshou.mcyukkuri.subspecies.HatVessel.aquatic(entity)` → `entity.profile().aquatic()` |  |
| `family/IndependenceLife.java:91` | 关系保留／保留个例 | `if (entity.family().partner() != null) return "partnered";` → `原样保留` | 伴侣槽位空缺/已占用或允许同伴侣的条件，不等同于双方已结伴。 |
| `family/IndependenceLife.java:97` | 关系保留／保留个例 | `if (!(home.owns(entity.family().mother()) &#124;&#124; home.owns(entity.family().father()))) return "parents_elsewhere";` → `原样保留` | 身份读取、持久化、展示、收件人遍历或特指母方的条件，不替换为泛化亲属查询。 |
| `family/IndependenceLife.java:107` | 关系保留／保留个例 | `if (independent &#124;&#124; !wild() &#124;&#124; !entity.childhood().canLiveIndependently() &#124;&#124; entity.family().elder() &#124;&#124; entity.family().partner() != null` → `原样保留` | 伴侣槽位空缺/已占用或允许同伴侣的条件，不等同于双方已结伴。 |
| `family/IndependenceLife.java:114` | 关系保留／保留个例 | `return home != null && home.usable(entity) && (home.owns(entity.family().mother()) &#124;&#124; home.owns(entity.family().father()));` → `原样保留` | 身份读取、持久化、展示、收件人遍历或特指母方的条件，不替换为泛化亲属查询。 |
| `family/IndependenceLife.java:148` | 阈值／迁移 | `attachment() >= 65` → `attachment() >= Temperament.ATTACHMENT_AT_65` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `family/IndependenceLife.java:197` | 关系／迁移 | `other.getUUID().equals(entity.family().mother()) &#124;&#124; other.getUUID().equals(entity.family().father())` → `Bonds.parentOf(other, entity)` | 只认直接父母，无年龄门槛；不扩大为兄弟姐妹、照护亲属或群体。 |
| `family/IndependenceLife.java:211` | 关系保留／保留个例 | `for (var parent : new UUID[]{entity.family().mother(), entity.family().father()}) {` → `原样保留` | 身份读取、持久化、展示、收件人遍历或特指母方的条件，不替换为泛化亲属查询。 |

#### M2

| 位置 | 类别／决定 | 旧表达式 → 新入口 | 边界 |
| --- | --- | --- | --- |
| `cognition/Cognition.java:111` | 阈值／迁移 | `trait(Trait.PATIENCE) >= 55` → `trait(Trait.PATIENCE) >= Temperament.PATIENCE_AT_55` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `headwear/Contempt.java:40` | 物种／迁移 | `e.getVariant().isPredator()` → `e.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `headwear/Contempt.java:41` | 身体候选／保留个例 | `&& !e.body().dying();` → `保留原标量读取` | 数值展示、持久化、单项判断或公式；下方指定的合并判断除外。 |
| `headwear/Contempt.java:50` | 关系/性格／迁移 | `p.aggression() >= 55 &#124;&#124; p.selfishness() >= 65 && p.selfControl() < 65` → `Temperament.harshForContempt(p)` | 阈值先保持55。 |
| `headwear/Contempt.java:57` | 物种／迁移 | `y.getVariant().isPredator()` → `y.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `headwear/Headwear.java:137` | 物种／迁移 | `y.getVariant().isPredator()` → `y.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `headwear/Headwear.java:176` | 表现数值／迁移 | `entity.getVariant().isHouse() ? .32f : 1` → `entity.profile().modelScale()` |  |
| `headwear/Headwear.java:275` | 行为／迁移 | `"shard_lick"` → `Behaviors.SHARD_LICK` |  |
| `headwear/Headwear.java:275` | 行为／迁移 | `"shard_seek"` → `Behaviors.SHARD_SEEK` |  |
| `headwear/Headwear.java:275` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `headwear/Recognition.java:54` | 物种／迁移 | `target.getVariant().isPredator()` → `target.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `headwear/Recognition.java:65` | 身体标量／保留个例 | `return body.dying() &#124;&#124; body.filling() <= BodySigns.CORE_SHOWS &#124;&#124; BodySigns.moldTier(body.mold()) >= 2` → `保留原标量读取` | 同步编码、识别阈值或单项状态读取，未合并成健康门槛；同行另列的物种子表达式可迁移。 |
| `headwear/Sanction.java:125` | 物种／迁移 | `t.getVariant().isPredator()` → `t.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `headwear/Sanction.java:125` | 物种／迁移 | `t.getVariant().isHouse()` → `t.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `headwear/Sanction.java:133` | 关系/性格／迁移 | `FamilyData.get(level).socialKin(e.getUUID(), target.getUUID())                 &#124;&#124; target.getUUID().equals(e.family().partner()) &#124;&#124; e.getUUID().equals(target.family().partner())` → `Bonds.careOrMate(e, target)` | 保留外层 instanceof ServerLevel guard。 |
| `headwear/Sanction.java:138` | 物种／迁移 | `e.getVariant().isPredator()` → `e.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `headwear/Sanction.java:139` | 物种／迁移 | `e.getVariant().isHouse()` → `e.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `headwear/Sanction.java:139` | 身体候选／保留个例 | `&#124;&#124; e.getVariant().isHouse() &#124;&#124; e.isPanicking() &#124;&#124; e.life().isSleeping() &#124;&#124; e.body().dying() &#124;&#124; e.body().weak()` → `保留原标量读取` | 数值展示、持久化、单项判断或公式；下方指定的合并判断除外。 同行已明确列为迁移的其他子表达式仍按其记录执行；本条只保留此类别语义。 |
| `headwear/Sanction.java:157` | 行为／迁移 | `"sanction_approach"` → `Behaviors.SANCTION_APPROACH` |  |
| `headwear/Sanction.java:157` | 行为／迁移 | `"sanction_declare"` → `Behaviors.SANCTION_DECLARE` |  |
| `headwear/Sanction.java:157` | 行为／迁移 | `"sanction_bully"` → `Behaviors.SANCTION_BULLY` |  |
| `headwear/Sanction.java:157` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `headwear/Sanction.java:172` | 身体候选／保留个例 | `&& !entity.isPanicking() && !entity.body().dying() && entity.getHealth() >= entity.getMaxHealth() * .3f` → `保留原标量读取` | 数值展示、持久化、单项判断或公式；下方指定的合并判断除外。 |
| `headwear/Sanction.java:242` | 身体候选／保留个例 | `return target.getHealth() - BITE > target.getMaxHealth() * HEALTH_FLOOR && !target.body().weak() && !target.body().dying()` → `保留原标量读取` | 数值展示、持久化、单项判断或公式；下方指定的合并判断除外。 |
| `mind/MindLife.java:174` | 行为／迁移 | `"non_yukkuri_wither"` → `Behaviors.NON_YUKKURI_WITHER` |  |
| `mind/MindLife.java:174` | 行为／迁移 | `"non_yukkuri"` → `Behaviors.NON_YUKKURI` |  |
| `mind/MindLife.java:206` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `mind/MindLife.java:217` | 身体／迁移 | `Math.max(entity.body().wound(), entity.body().burns())` → `entity.body().condition().painSeverity()` | 先替换最长复合表达式；保留其余健康、饥饿、识别或行动条件。 |
| `mind/MindLife.java:219` | 身体候选／保留个例 | `if (entity.body().filling() <= 6) put("filling_loss", 1.5f);` → `保留原标量读取` | 数值展示、持久化、单项判断或公式；下方指定的合并判断除外。 |
| `mind/MindLife.java:220` | 身体／迁移 | `entity.body().infection() > 0 &#124;&#124; entity.body().mold() > 0` → `entity.body().condition().hasSicknessPressure()` | 先替换最长复合表达式；保留其余健康、饥饿、识别或行动条件。 |
| `mind/MindLife.java:242` | 阈值／迁移 | `unease >= 50` → `unease >= BondLevels.UNEASE_CARE_LIMIT` | 局部变量仍读原来源；比较方向、量化与奖励/惩罚公式不变。 |
| `mind/MindLife.java:264` | 关系/性格／迁移 | `y.getUUID().equals(entity.family().partner())` → `Bonds.recordedMate(entity, y)` | 只查观察者的一侧伴侣；外层 ! 保留。 |
| `mind/MindLife.java:265` | 关系/性格／迁移 | `family.related(entity.getUUID(), y.getUUID())` → `Bonds.biological(entity, y)` | 生物学亲缘含 incidentSource，不得换 careKin。 |
| `mind/Naming.java:92` | 身体候选／保留个例 | `for (int i = 0; i < portions && y.body().filling() > 3; i++) if (y.body().releaseFilling("vomited")) vomited++;` → `保留原标量读取` | 数值展示、持久化、单项判断或公式；下方指定的合并判断除外。 |
| `mind/Naming.java:102` | 身体候选／保留个例 | `+ " -> " + Math.round(mind.unease()) + ", vomited " + vomited + ", filling " + y.body().filling() + ", line " + line + ")";` → `保留原标量读取` | 数值展示、持久化、单项判断或公式；下方指定的合并判断除外。 |
| `social/Cannibalism.java:63` | 物种／迁移 | `entity.getVariant().isHouse()` → `entity.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `social/Cannibalism.java:64` | 物种／迁移 | `entity.getVariant().isPredator()` → `entity.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `social/Cannibalism.java:64` | 身体候选／保留个例 | `&& !entity.getVariant().isPredator() && !entity.life().isSleeping() && !entity.body().dying() && !entity.operations().active()` → `保留原标量读取` | 数值展示、持久化、单项判断或公式；下方指定的合并判断除外。 同行已明确列为迁移的其他子表达式仍按其记录执行；本条只保留此类别语义。 |
| `social/Cannibalism.java:94` | 关系/性格／迁移 | `data.socialKin(entity.getUUID(), dead) &#124;&#124; dead.equals(entity.family().partner()) &#124;&#124; entity.getUUID().equals(deadPartner)                 &#124;&#124; data.related(entity.getUUID(), dead)` → `Bonds.corpseKin(entity, dead, deadPartner)` | 遗体 partner 副本保留；不得改为 livingPartner()。 |
| `social/Cannibalism.java:141` | 物种／迁移 | `y.getVariant().isPredator()` → `y.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `social/Cannibalism.java:141` | 物种／迁移 | `y.getVariant().isHouse()` → `y.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `social/Cannibalism.java:142` | 身体候选／保留个例 | `&& io.github.longshou.mcyukkuri.headwear.Headwear.fairGame(entity, y) && !y.isPassenger() && !y.body().dying()` → `保留原标量读取` | 数值展示、持久化、单项判断或公式；下方指定的合并判断除外。 |
| `social/DailyLife.java:51` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `social/DailyLife.java:53` | 行为／迁移 | `"seek_affection"` → `Behaviors.SEEK_AFFECTION` |  |
| `social/DailyLife.java:53` | 行为／迁移 | `"nuzzle"` → `Behaviors.NUZZLE` |  |
| `social/DailyLife.java:54` | 行为／迁移 | `"listen_song"` → `Behaviors.LISTEN_SONG` |  |
| `social/DailyLife.java:54` | 行为／迁移 | `"sing"` → `Behaviors.SING` |  |
| `social/DailyLife.java:55` | 行为／迁移 | `"exercise_watch"` → `Behaviors.EXERCISE_WATCH` |  |
| `social/DailyLife.java:55` | 行为／迁移 | `"unun_exercise"` → `Behaviors.UNUN_EXERCISE` |  |
| `social/DailyLife.java:68` | 阈值／迁移 | `trait(Trait.SOCIABILITY) >= 65` → `trait(Trait.SOCIABILITY) >= Temperament.SOCIABILITY_AT_65` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `social/DailyLife.java:78` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `social/DailyLife.java:80` | 身体／迁移 | `e.body().wound() == 0 && e.body().infection() == 0` → `e.body().condition().readyForAffection()` |  |
| `social/DailyLife.java:89` | 关系/性格／迁移 | `a >= 0 && b >= 0 && (a >= 10 && b >= 10                 &#124;&#124; FamilyData.get(level()).socialKin(entity.getUUID(), peer.getUUID()))` → `Bonds.closeAffectionPeer(entity, peer)` | 保留两边识别前置；可删除仅服务此判断的 relations/a/b 局部变量。 |
| `social/DailyLife.java:95` | 关系/性格／迁移 | `personality.trust(player.getUUID()) >= 10 && personality.fear(player.getUUID()) < 25` → `Bonds.trustedPlayer(entity, player.getUUID())` | 原 player 存活/世界/旁观条件保持。 |
| `social/EcologyPresentation.java:11` | 行为／迁移 | `"unun_exercise"` → `Behaviors.UNUN_EXERCISE` |  |
| `social/EcologyPresentation.java:12` | 行为／迁移 | `"first_toilet_stimulated"` → `Behaviors.FIRST_TOILET_STIMULATED` |  |
| `social/EcologyPresentation.java:12` | 行为／迁移 | `"toilet_accident"` → `Behaviors.TOILET_ACCIDENT` |  |
| `social/EcologyPresentation.java:12` | 行为／迁移 | `"ground_toilet"` → `Behaviors.GROUND_TOILET` |  |
| `social/EcologyPresentation.java:12` | 行为／迁移 | `"boat_toilet"` → `Behaviors.BOAT_TOILET` |  |
| `social/EcologyPresentation.java:12` | 行为／迁移 | `"toilet"` → `Behaviors.TOILET` |  |
| `social/EcologyPresentation.java:13` | 行为／迁移 | `"family_chew"` → `Behaviors.FAMILY_CHEW` |  |
| `social/EcologyPresentation.java:13` | 行为／迁移 | `"take_food"` → `Behaviors.TAKE_FOOD` |  |
| `social/EcologyPresentation.java:13` | 行为／迁移 | `"swallow"` → `Behaviors.SWALLOW` |  |
| `social/EcologyPresentation.java:13` | 行为／迁移 | `"chew"` → `Behaviors.CHEW` |  |
| `social/EcologyPresentation.java:14` | 行为／迁移 | `"receive_mash"` → `Behaviors.RECEIVE_MASH` |  |
| `social/EcologyPresentation.java:15` | 行为／迁移 | `"meal_finish"` → `Behaviors.MEAL_FINISH` |  |
| `social/EcologyPresentation.java:16` | 行为／迁移 | `"drink"` → `Behaviors.DRINK` |  |
| `social/EcologyPresentation.java:17` | 行为／迁移 | `"sleep_enter"` → `Behaviors.SLEEP_ENTER` |  |
| `social/EcologyPresentation.java:17` | 行为／迁移 | `"shade_rest"` → `Behaviors.SHADE_REST` |  |
| `social/EcologyPresentation.java:17` | 行为／迁移 | `"sleep"` → `Behaviors.SLEEP` |  |
| `social/EcologyPresentation.java:18` | 行为／迁移 | `"house_sleep"` → `Behaviors.HOUSE_SLEEP` |  |
| `social/EcologyPresentation.java:18` | 行为／迁移 | `"house_hide"` → `Behaviors.HOUSE_HIDE` |  |
| `social/EcologyPresentation.java:19` | 行为／迁移 | `"house_search_home"` → `Behaviors.HOUSE_SEARCH_HOME` |  |
| `social/EcologyPresentation.java:19` | 行为／迁移 | `"house_sniff"` → `Behaviors.HOUSE_SNIFF` |  |
| `social/EcologyPresentation.java:19` | 行为／迁移 | `"house_peek"` → `Behaviors.HOUSE_PEEK` |  |
| `social/EcologyPresentation.java:20` | 行为／迁移 | `"house_shape"` → `Behaviors.HOUSE_SHAPE` |  |
| `social/EcologyPresentation.java:21` | 行为／迁移 | `"house_listen"` → `Behaviors.HOUSE_LISTEN` |  |
| `social/EcologyPresentation.java:21` | 行为／迁移 | `"house_tend"` → `Behaviors.HOUSE_TEND` |  |
| `social/EcologyPresentation.java:22` | 行为／迁移 | `"house_startled"` → `Behaviors.HOUSE_STARTLED` |  |
| `social/EcologyPresentation.java:23` | 行为／迁移 | `"seek_warmth"` → `Behaviors.SEEK_WARMTH` |  |
| `social/EcologyPresentation.java:23` | 行为／迁移 | `"warm_up"` → `Behaviors.WARM_UP` |  |
| `social/EcologyPresentation.java:25` | 行为／迁移 | `"threat_watch"` → `Behaviors.THREAT_WATCH` |  |
| `social/EcologyPresentation.java:26` | 行为／迁移 | `"threat_hide"` → `Behaviors.THREAT_HIDE` |  |
| `social/EcologyPresentation.java:26` | 行为／迁移 | `"rain_huddle"` → `Behaviors.RAIN_HUDDLE` |  |
| `social/EcologyPresentation.java:26` | 行为／迁移 | `"rain_wait"` → `Behaviors.RAIN_WAIT` |  |
| `social/EcologyPresentation.java:27` | 行为／迁移 | `"rain_shake"` → `Behaviors.RAIN_SHAKE` |  |
| `social/EcologyPresentation.java:28` | 行为／迁移 | `"seek_shade"` → `Behaviors.SEEK_SHADE` |  |
| `social/EcologyPresentation.java:29` | 行为／迁移 | `"independence_build"` → `Behaviors.INDEPENDENCE_BUILD` |  |
| `social/EcologyPresentation.java:29` | 行为／迁移 | `"nest_harvest"` → `Behaviors.NEST_HARVEST` |  |
| `social/EcologyPresentation.java:29` | 行为／迁移 | `"nest_spread"` → `Behaviors.NEST_SPREAD` |  |
| `social/EcologyPresentation.java:29` | 行为／迁移 | `"nest_build"` → `Behaviors.NEST_BUILD` |  |
| `social/EcologyPresentation.java:29` | 行为／迁移 | `"nest_place"` → `Behaviors.NEST_PLACE` |  |
| `social/EcologyPresentation.java:29` | 行为／迁移 | `"nest_press"` → `Behaviors.NEST_PRESS` |  |
| `social/EcologyPresentation.java:32` | 行为／迁移 | `"first_toilet_assist"` → `Behaviors.FIRST_TOILET_ASSIST` |  |
| `social/EcologyPresentation.java:32` | 行为／迁移 | `"feed_child"` → `Behaviors.FEED_CHILD` |  |
| `social/EcologyPresentation.java:33` | 行为／迁移 | `"first_toilet_seek"` → `Behaviors.FIRST_TOILET_SEEK` |  |
| `social/EcologyPresentation.java:34` | 行为／迁移 | `"newborn_crawl"` → `Behaviors.NEWBORN_CRAWL` |  |
| `social/EcologyPresentation.java:34` | 行为／迁移 | `"hat_walk"` → `Behaviors.HAT_WALK` |  |
| `social/EcologyPresentation.java:35` | 行为／迁移 | `"learn_jump"` → `Behaviors.LEARN_JUMP` |  |
| `social/EcologyPresentation.java:36` | 行为／迁移 | `"first_toilet_wait"` → `Behaviors.FIRST_TOILET_WAIT` |  |
| `social/EcologyPresentation.java:36` | 行为／迁移 | `"newborn_wait"` → `Behaviors.NEWBORN_WAIT` |  |
| `social/EcologyPresentation.java:36` | 行为／迁移 | `"care_finish"` → `Behaviors.CARE_FINISH` |  |
| `social/EcologyPresentation.java:36` | 行为／迁移 | `"learn_watch"` → `Behaviors.LEARN_WATCH` |  |
| `social/EcologyPresentation.java:37` | 行为／迁移 | `"boat_carry_young"` → `Behaviors.BOAT_CARRY_YOUNG` |  |
| `social/EcologyPresentation.java:37` | 行为／迁移 | `"boat_be_carried"` → `Behaviors.BOAT_BE_CARRIED` |  |
| `social/EcologyPresentation.java:38` | 行为／迁移 | `"nest_gather"` → `Behaviors.NEST_GATHER` |  |
| `social/EcologyPresentation.java:39` | 行为／迁移 | `"independence_farewell"` → `Behaviors.INDEPENDENCE_FAREWELL` |  |
| `social/EcologyPresentation.java:39` | 行为／迁移 | `"independence_visit"` → `Behaviors.INDEPENDENCE_VISIT` |  |
| `social/EcologyPresentation.java:40` | 行为／迁移 | `"independence_gather"` → `Behaviors.INDEPENDENCE_GATHER` |  |
| `social/EcologyPresentation.java:40` | 行为／迁移 | `"independence_return"` → `Behaviors.INDEPENDENCE_RETURN` |  |
| `social/EcologyPresentation.java:41` | 行为／迁移 | `"wake"` → `Behaviors.WAKE` |  |
| `social/EcologyPresentation.java:42` | 行为／迁移 | `"nuzzle"` → `Behaviors.NUZZLE` |  |
| `social/EcologyPresentation.java:43` | 行为／迁移 | `"courtship"` → `Behaviors.COURTSHIP` |  |
| `social/EcologyPresentation.java:44` | 行为／迁移 | `"pairing_accept"` → `Behaviors.PAIRING_ACCEPT` |  |
| `social/EcologyPresentation.java:45` | 行为／迁移 | `"pairing"` → `Behaviors.PAIRING` |  |
| `social/EcologyPresentation.java:46` | 行为／迁移 | `"comfort_lick"` → `Behaviors.COMFORT_LICK` |  |
| `social/EcologyPresentation.java:46` | 行为／迁移 | `"shard_lick"` → `Behaviors.SHARD_LICK` |  |
| `social/EcologyPresentation.java:48` | 行为／迁移 | `"sanction_approach"` → `Behaviors.SANCTION_APPROACH` |  |
| `social/EcologyPresentation.java:48` | 行为／迁移 | `"shard_seek"` → `Behaviors.SHARD_SEEK` |  |
| `social/EcologyPresentation.java:49` | 行为／迁移 | `"sanction_declare"` → `Behaviors.SANCTION_DECLARE` |  |
| `social/EcologyPresentation.java:50` | 行为／迁移 | `"sanction_bully"` → `Behaviors.SANCTION_BULLY` |  |
| `social/EcologyPresentation.java:51` | 行为／迁移 | `"comfort_receive"` → `Behaviors.COMFORT_RECEIVE` |  |
| `social/EcologyPresentation.java:52` | 行为／迁移 | `"sing"` → `Behaviors.SING` |  |
| `social/EcologyPresentation.java:53` | 行为／迁移 | `"exercise_watch"` → `Behaviors.EXERCISE_WATCH` |  |
| `social/EcologyPresentation.java:53` | 行为／迁移 | `"listen_song"` → `Behaviors.LISTEN_SONG` |  |
| `social/EcologyPresentation.java:54` | 行为／迁移 | `"search_family"` → `Behaviors.SEARCH_FAMILY` |  |
| `social/EcologyPresentation.java:54` | 行为／迁移 | `"wait_child"` → `Behaviors.WAIT_CHILD` |  |
| `social/EcologyPresentation.java:54` | 行为／迁移 | `"stay_home"` → `Behaviors.STAY_HOME` |  |
| `social/EcologyPresentation.java:55` | 行为／迁移 | `"tend_brood"` → `Behaviors.TEND_BROOD` |  |
| `social/EcologyPresentation.java:56` | 行为／迁移 | `"labor"` → `Behaviors.LABOR` |  |
| `social/EcologyPresentation.java:57` | 行为／迁移 | `"postpartum"` → `Behaviors.POSTPARTUM` |  |
| `social/EcologyPresentation.java:58` | 行为／迁移 | `"midwife"` → `Behaviors.MIDWIFE` |  |
| `social/EcologyPresentation.java:59` | 行为／迁移 | `"voyage_launch"` → `Behaviors.VOYAGE_LAUNCH` |  |
| `social/EcologyPresentation.java:59` | 行为／迁移 | `"boat_launch"` → `Behaviors.BOAT_LAUNCH` |  |
| `social/EcologyPresentation.java:60` | 行为／迁移 | `"voyage_wait_parent"` → `Behaviors.VOYAGE_WAIT_PARENT` |  |
| `social/EcologyPresentation.java:60` | 行为／迁移 | `"voyage_prepare"` → `Behaviors.VOYAGE_PREPARE` |  |
| `social/EcologyPresentation.java:60` | 行为／迁移 | `"voyage_watch"` → `Behaviors.VOYAGE_WATCH` |  |
| `social/EcologyPresentation.java:61` | 行为／迁移 | `"voyage_praise"` → `Behaviors.VOYAGE_PRAISE` |  |
| `social/EcologyPresentation.java:62` | 行为／迁移 | `"voyage_proud"` → `Behaviors.VOYAGE_PROUD` |  |
| `social/EcologyPresentation.java:63` | 行为／迁移 | `"aquatic_paddle_practice"` → `Behaviors.AQUATIC_PADDLE_PRACTICE` |  |
| `social/EcologyPresentation.java:63` | 行为／迁移 | `"aquatic_imitate_paddle"` → `Behaviors.AQUATIC_IMITATE_PADDLE` |  |
| `social/EcologyPresentation.java:63` | 行为／迁移 | `"aquatic_watch_parent"` → `Behaviors.AQUATIC_WATCH_PARENT` |  |
| `social/EcologyPresentation.java:64` | 行为／迁移 | `"boat_return_family"` → `Behaviors.BOAT_RETURN_FAMILY` | 多行 switch 延续的行为标签，保持匹配值。 |
| `social/EcologyPresentation.java:64` | 行为／迁移 | `"boat_follow_family"` → `Behaviors.BOAT_FOLLOW_FAMILY` | 多行 switch 延续的行为标签，保持匹配值。 |
| `social/EcologyPresentation.java:65` | 行为／迁移 | `"aquatic_answer_family"` → `Behaviors.AQUATIC_ANSWER_FAMILY` |  |
| `social/EcologyPresentation.java:66` | 行为／迁移 | `"boat_guard_young"` → `Behaviors.BOAT_GUARD_YOUNG` |  |
| `social/EcologyPresentation.java:67` | 行为／迁移 | `"boat_rocked"` → `Behaviors.BOAT_ROCKED` |  |
| `social/EcologyPresentation.java:67` | 行为／迁移 | `"hat_stumble"` → `Behaviors.HAT_STUMBLE` |  |
| `social/EcologyPresentation.java:68` | 行为／迁移 | `"boat_capsizing"` → `Behaviors.BOAT_CAPSIZING` |  |
| `social/EcologyPresentation.java:69` | 行为／迁移 | `"hat_scramble"` → `Behaviors.HAT_SCRAMBLE` |  |
| `social/EcologyPresentation.java:69` | 行为／迁移 | `"hat_slipping"` → `Behaviors.HAT_SLIPPING` |  |
| `social/EcologyPresentation.java:69` | 行为／迁移 | `"hat_jostle"` → `Behaviors.HAT_JOSTLE` |  |
| `social/EcologyPresentation.java:70` | 行为／迁移 | `"boat_rescue_young"` → `Behaviors.BOAT_RESCUE_YOUNG` |  |
| `social/EcologyPresentation.java:70` | 行为／迁移 | `"boat_crowd_guard"` → `Behaviors.BOAT_CROWD_GUARD` |  |
| `social/EcologyPresentation.java:70` | 行为／迁移 | `"boat_reassure"` → `Behaviors.BOAT_REASSURE` |  |
| `social/EcologyPresentation.java:71` | 行为／迁移 | `"boat_rescue_approach"` → `Behaviors.BOAT_RESCUE_APPROACH` |  |
| `social/EcologyPresentation.java:72` | 行为／迁移 | `"boat_alarm_call"` → `Behaviors.BOAT_ALARM_CALL` |  |
| `social/EcologyPresentation.java:73` | 行为／迁移 | `"boat_alarm_wait"` → `Behaviors.BOAT_ALARM_WAIT` |  |
| `social/EcologyPresentation.java:74` | 行为／迁移 | `"boat_return_called"` → `Behaviors.BOAT_RETURN_CALLED` |  |
| `social/EcologyPresentation.java:74` | 行为／迁移 | `"boat_flee"` → `Behaviors.BOAT_FLEE` |  |
| `social/EcologyPresentation.java:75` | 行为／迁移 | `"boat_calm"` → `Behaviors.BOAT_CALM` |  |
| `social/EcologyPresentation.java:76` | 行为／迁移 | `"boat_answer_call"` → `Behaviors.BOAT_ANSWER_CALL` |  |
| `social/EcologyPresentation.java:77` | 行为／迁移 | `"braid_rescue_offer"` → `Behaviors.BRAID_RESCUE_OFFER` |  |
| `social/EcologyPresentation.java:78` | 行为／迁移 | `"braid_rescue_push"` → `Behaviors.BRAID_RESCUE_PUSH` |  |
| `social/EcologyPresentation.java:79` | 行为／迁移 | `"braid_rescue_reach"` → `Behaviors.BRAID_RESCUE_REACH` |  |
| `social/EcologyPresentation.java:80` | 行为／迁移 | `"braid_rescue_hold"` → `Behaviors.BRAID_RESCUE_HOLD` |  |
| `social/EcologyPresentation.java:81` | 行为／迁移 | `"braid_rescue_settle"` → `Behaviors.BRAID_RESCUE_SETTLE` |  |
| `social/EcologyPresentation.java:82` | 行为／迁移 | `"braid_be_rescued"` → `Behaviors.BRAID_BE_RESCUED` |  |
| `social/EcologyPresentation.java:83` | 行为／迁移 | `"water_impact"` → `Behaviors.WATER_IMPACT` |  |
| `social/EcologyPresentation.java:84` | 行为／迁移 | `"water_struggle"` → `Behaviors.WATER_STRUGGLE` |  |
| `social/EcologyPresentation.java:84` | 行为／迁移 | `"escape_water"` → `Behaviors.ESCAPE_WATER` |  |
| `social/EcologyPresentation.java:84` | 行为／迁移 | `"struggle"` → `Behaviors.STRUGGLE` |  |
| `social/EcologyPresentation.java:85` | 行为／迁移 | `"water_exhausted"` → `Behaviors.WATER_EXHAUSTED` |  |
| `social/EcologyPresentation.java:86` | 行为／迁移 | `"water_recover"` → `Behaviors.WATER_RECOVER` |  |
| `social/EcologyPresentation.java:87` | 行为／迁移 | `"water_hat_remove"` → `Behaviors.WATER_HAT_REMOVE` |  |
| `social/EcologyPresentation.java:88` | 行为／迁移 | `"water_hat_right"` → `Behaviors.WATER_HAT_RIGHT` |  |
| `social/EcologyPresentation.java:89` | 行为／迁移 | `"water_hat_reach"` → `Behaviors.WATER_HAT_REACH` |  |
| `social/EcologyPresentation.java:90` | 行为／迁移 | `"water_hat_grip"` → `Behaviors.WATER_HAT_GRIP` |  |
| `social/EcologyPresentation.java:90` | 行为／迁移 | `"hat_board_grip"` → `Behaviors.HAT_BOARD_GRIP` |  |
| `social/EcologyPresentation.java:91` | 行为／迁移 | `"water_hat_climb"` → `Behaviors.WATER_HAT_CLIMB` |  |
| `social/EcologyPresentation.java:92` | 行为／迁移 | `"hat_board_lift"` → `Behaviors.HAT_BOARD_LIFT` |  |
| `social/EcologyPresentation.java:93` | 行为／迁移 | `"hat_board_settle"` → `Behaviors.HAT_BOARD_SETTLE` |  |
| `social/EcologyPresentation.java:94` | 行为／迁移 | `"water_hat_slip"` → `Behaviors.WATER_HAT_SLIP` |  |
| `social/EcologyPresentation.java:95` | 行为／迁移 | `"water_hat_safe"` → `Behaviors.WATER_HAT_SAFE` |  |
| `social/EcologyPresentation.java:96` | 行为／迁移 | `"hat_board_align"` → `Behaviors.HAT_BOARD_ALIGN` |  |
| `social/EcologyPresentation.java:97` | 行为／迁移 | `"hat_board_hop"` → `Behaviors.HAT_BOARD_HOP` |  |
| `social/EcologyPresentation.java:98` | 行为／迁移 | `"boat_give_paddle"` → `Behaviors.BOAT_GIVE_PADDLE` |  |
| `social/EcologyPresentation.java:99` | 行为／迁移 | `"boat_receive_paddle"` → `Behaviors.BOAT_RECEIVE_PADDLE` |  |
| `social/EcologyPresentation.java:100` | 行为／迁移 | `"boat_find_paddle"` → `Behaviors.BOAT_FIND_PADDLE` |  |
| `social/EcologyPresentation.java:100` | 行为／迁移 | `"boat_wait_young"` → `Behaviors.BOAT_WAIT_YOUNG` |  |
| `social/EcologyPresentation.java:100` | 行为／迁移 | `"boat_seek_bank"` → `Behaviors.BOAT_SEEK_BANK` |  |
| `social/EcologyPresentation.java:100` | 行为／迁移 | `"boat_provision"` → `Behaviors.BOAT_PROVISION` |  |
| `social/EcologyPresentation.java:101` | 行为／迁移 | `"aquatic_hat_care"` → `Behaviors.AQUATIC_HAT_CARE` |  |
| `social/EcologyPresentation.java:102` | 行为／迁移 | `"hat_off_self"` → `Behaviors.HAT_OFF_SELF` |  |
| `social/EcologyPresentation.java:102` | 行为／迁移 | `"hat_on_self"` → `Behaviors.HAT_ON_SELF` |  |
| `social/EcologyPresentation.java:102` | 行为／迁移 | `"hat_hold"` → `Behaviors.HAT_HOLD` |  |
| `social/EcologyPresentation.java:102` | 行为／迁移 | `"hat_off"` → `Behaviors.HAT_OFF` |  |
| `social/EcologyPresentation.java:102` | 行为／迁移 | `"hat_on"` → `Behaviors.HAT_ON` |  |
| `social/EcologyPresentation.java:103` | 行为／迁移 | `"hat_off_self_aq"` → `Behaviors.HAT_OFF_SELF_AQ` | 多行 switch 延续的行为标签，保持匹配值。 |
| `social/EcologyPresentation.java:103` | 行为／迁移 | `"hat_on_self_aq"` → `Behaviors.HAT_ON_SELF_AQ` | 多行 switch 延续的行为标签，保持匹配值。 |
| `social/EcologyPresentation.java:103` | 行为／迁移 | `"hat_hold_aq"` → `Behaviors.HAT_HOLD_AQ` | 多行 switch 延续的行为标签，保持匹配值。 |
| `social/EcologyPresentation.java:103` | 行为／迁移 | `"hat_off_aq"` → `Behaviors.HAT_OFF_AQ` | 多行 switch 延续的行为标签，保持匹配值。 |
| `social/EcologyPresentation.java:103` | 行为／迁移 | `"hat_on_aq"` → `Behaviors.HAT_ON_AQ` | 多行 switch 延续的行为标签，保持匹配值。 |
| `social/EcologyPresentation.java:110` | 行为／迁移 | `"bully_peer"` → `Behaviors.BULLY_PEER` |  |
| `social/EcologyPresentation.java:110` | 行为／迁移 | `"sumo_push"` → `Behaviors.SUMO_PUSH` |  |
| `social/EcologyPresentation.java:111` | 行为／迁移 | `"sumo_prepare"` → `Behaviors.SUMO_PREPARE` |  |
| `social/EcologyPresentation.java:118` | 行为／迁移 | `"boat_alarm_call"` → `Behaviors.BOAT_ALARM_CALL` |  |
| `social/EcologyPresentation.java:118` | 行为／迁移 | `"boat_alarm_wait"` → `Behaviors.BOAT_ALARM_WAIT` |  |
| `social/EcologyPresentation.java:118` | 行为／迁移 | `"braid_rescue_"` → `Behaviors.BRAID_RESCUE_PREFIX` |  |
| `social/EcologyPresentation.java:119` | 行为／迁移 | `"boat_answer_call"` → `Behaviors.BOAT_ANSWER_CALL` |  |
| `social/EcologyPresentation.java:119` | 行为／迁移 | `"boat_calm"` → `Behaviors.BOAT_CALM` |  |
| `social/EcologyPresentation.java:120` | 行为／迁移 | `"boat_return_called"` → `Behaviors.BOAT_RETURN_CALLED` |  |
| `social/EcologyPresentation.java:120` | 行为／迁移 | `"boat_flee"` → `Behaviors.BOAT_FLEE` |  |
| `social/EcologyPresentation.java:122` | 行为／迁移 | `"boat_rescue_approach"` → `Behaviors.BOAT_RESCUE_APPROACH` |  |
| `social/EcologyPresentation.java:122` | 行为／迁移 | `"boat_rescue_young"` → `Behaviors.BOAT_RESCUE_YOUNG` |  |
| `social/EcologyPresentation.java:122` | 行为／迁移 | `"boat_crowd_guard"` → `Behaviors.BOAT_CROWD_GUARD` |  |
| `social/EcologyPresentation.java:122` | 行为／迁移 | `"boat_reassure"` → `Behaviors.BOAT_REASSURE` |  |
| `social/EcologyPresentation.java:130` | 行为／迁移 | `"boat_toilet"` → `Behaviors.BOAT_TOILET` |  |
| `social/EcologyPresentation.java:131` | 行为／迁移 | `"family_chew"` → `Behaviors.FAMILY_CHEW` |  |
| `social/EcologyPresentation.java:132` | 行为／迁移 | `"voyage_balance"` → `Behaviors.VOYAGE_BALANCE` |  |
| `social/EcologyPresentation.java:133` | 行为／迁移 | `"voyage_praise"` → `Behaviors.VOYAGE_PRAISE` |  |
| `social/EcologyPresentation.java:134` | 行为／迁移 | `"boat_feed_young"` → `Behaviors.BOAT_FEED_YOUNG` |  |
| `social/EcologyPresentation.java:134` | 行为／迁移 | `"feed_child"` → `Behaviors.FEED_CHILD` |  |
| `social/EcologyPresentation.java:135` | 行为／迁移 | `"boat_nuzzle"` → `Behaviors.BOAT_NUZZLE` |  |
| `social/EcologyPresentation.java:136` | 行为／迁移 | `"boat_lullaby"` → `Behaviors.BOAT_LULLABY` |  |
| `social/EcologyPresentation.java:138` | 行为／迁移 | `"aquatic_watch_parent"` → `Behaviors.AQUATIC_WATCH_PARENT` |  |
| `social/EcologyPresentation.java:139` | 行为／迁移 | `"aquatic_answer_family"` → `Behaviors.AQUATIC_ANSWER_FAMILY` |  |
| `social/EcologyPresentation.java:140` | 行为／迁移 | `"boat_give_paddle"` → `Behaviors.BOAT_GIVE_PADDLE` |  |
| `social/EcologyPresentation.java:141` | 行为／迁移 | `"aquatic_paddle_practice"` → `Behaviors.AQUATIC_PADDLE_PRACTICE` |  |
| `social/EcologyPresentation.java:141` | 行为／迁移 | `"aquatic_imitate_paddle"` → `Behaviors.AQUATIC_IMITATE_PADDLE` |  |
| `social/EcologyPresentation.java:142` | 行为／迁移 | `"boat_follow_family"` → `Behaviors.BOAT_FOLLOW_FAMILY` |  |
| `social/EcologyPresentation.java:142` | 行为／迁移 | `"boat_return_family"` → `Behaviors.BOAT_RETURN_FAMILY` |  |
| `social/EcologyPresentation.java:144` | 行为／迁移 | `"boat_guard_young"` → `Behaviors.BOAT_GUARD_YOUNG` |  |
| `social/EcologyPresentation.java:146` | 行为／迁移 | `"boat_drink"` → `Behaviors.BOAT_DRINK` |  |
| `social/EcologyPresentation.java:147` | 行为／迁移 | `"boat_gather"` → `Behaviors.BOAT_GATHER` |  |
| `social/EcologyPresentation.java:148` | 行为／迁移 | `"boat_retrieve_food"` → `Behaviors.BOAT_RETRIEVE_FOOD` |  |
| `social/EcologyPresentation.java:148` | 行为／迁移 | `"boat_stow_food"` → `Behaviors.BOAT_STOW_FOOD` |  |
| `social/EcologyPresentation.java:149` | 行为／迁移 | `"boat_wait_young"` → `Behaviors.BOAT_WAIT_YOUNG` |  |
| `social/EcologyPresentation.java:149` | 行为／迁移 | `"boat_call"` → `Behaviors.BOAT_CALL` |  |
| `social/EcologyPresentation.java:153` | 行为／迁移 | `"event_river"` → `Behaviors.EVENT_RIVER` |  |
| `social/EcologyPresentation.java:187` | 身体／迁移 | `e.body().wound() > 0 &#124;&#124; e.body().burns() > 0` → `e.body().condition().hasSkinOrBurnDamage()` | 先替换最长复合表达式；保留其余健康、饥饿、识别或行动条件。 |
| `social/EcologyPresentation.java:189` | 身体／迁移 | `e.body().wound() > 0 &#124;&#124; e.body().burns() > 0 &#124;&#124; e.body().infection() > 0` → `e.body().condition().needsPainExpression()` | 先替换最长复合表达式；保留其余健康、饥饿、识别或行动条件。 |
| `social/EcologyPresentation.java:192` | 行为／迁移 | `"comfort_lick"` → `Behaviors.COMFORT_LICK` |  |
| `social/EcologyPresentation.java:193` | 行为／迁移 | `"boat_receive_care"` → `Behaviors.BOAT_RECEIVE_CARE` |  |
| `social/EcologyPresentation.java:193` | 行为／迁移 | `"exercise_watch"` → `Behaviors.EXERCISE_WATCH` |  |
| `social/EcologyPresentation.java:193` | 行为／迁移 | `"listen_song"` → `Behaviors.LISTEN_SONG` |  |
| `social/EcologyPresentation.java:193` | 行为／迁移 | `"boat_nuzzle"` → `Behaviors.BOAT_NUZZLE` |  |
| `social/EcologyPresentation.java:193` | 行为／迁移 | `"boat_listen"` → `Behaviors.BOAT_LISTEN` |  |
| `social/EcologyPresentation.java:193` | 行为／迁移 | `"nuzzle"` → `Behaviors.NUZZLE` |  |
| `social/EcologyPresentation.java:194` | 行为／迁移 | `"pairing_accept"` → `Behaviors.PAIRING_ACCEPT` |  |
| `social/EcologyPresentation.java:195` | 行为／迁移 | `"pairing"` → `Behaviors.PAIRING` |  |
| `social/EcologyPresentation.java:196` | 行为／迁移 | `"meal_finish"` → `Behaviors.MEAL_FINISH` |  |
| `social/EcologyPresentation.java:197` | 行为／迁移 | `"independence_farewell"` → `Behaviors.INDEPENDENCE_FAREWELL` |  |
| `social/EcologyPresentation.java:197` | 行为／迁移 | `"independence_visit"` → `Behaviors.INDEPENDENCE_VISIT` |  |
| `social/EcologyPresentation.java:198` | 行为／迁移 | `"independence_build"` → `Behaviors.INDEPENDENCE_BUILD` |  |
| `social/EcologyPresentation.java:198` | 行为／迁移 | `"nest_harvest"` → `Behaviors.NEST_HARVEST` |  |
| `social/EcologyPresentation.java:198` | 行为／迁移 | `"nest_spread"` → `Behaviors.NEST_SPREAD` |  |
| `social/EcologyPresentation.java:198` | 行为／迁移 | `"nest_build"` → `Behaviors.NEST_BUILD` |  |
| `social/EcologyPresentation.java:198` | 行为／迁移 | `"nest_place"` → `Behaviors.NEST_PLACE` |  |
| `social/EcologyPresentation.java:198` | 行为／迁移 | `"nest_press"` → `Behaviors.NEST_PRESS` |  |
| `social/EcologyPresentation.java:199` | 行为／迁移 | `"feed_child"` → `Behaviors.FEED_CHILD` |  |
| `social/EcologyPresentation.java:200` | 行为／迁移 | `"first_toilet_seek"` → `Behaviors.FIRST_TOILET_SEEK` |  |
| `social/EcologyPresentation.java:200` | 行为／迁移 | `"first_toilet_wait"` → `Behaviors.FIRST_TOILET_WAIT` |  |
| `social/EcologyPresentation.java:201` | 行为／迁移 | `"first_toilet_stimulated"` → `Behaviors.FIRST_TOILET_STIMULATED` |  |
| `social/EcologyPresentation.java:202` | 行为／迁移 | `"first_toilet_assist"` → `Behaviors.FIRST_TOILET_ASSIST` |  |
| `social/EcologyPresentation.java:203` | 行为／迁移 | `"newborn_crawl"` → `Behaviors.NEWBORN_CRAWL` |  |
| `social/EcologyPresentation.java:203` | 行为／迁移 | `"learn_jump"` → `Behaviors.LEARN_JUMP` |  |
| `social/EcologyPresentation.java:204` | 行为／迁移 | `"receive_mash"` → `Behaviors.RECEIVE_MASH` |  |
| `social/EcologyPresentation.java:205` | 行为／迁移 | `"sing"` → `Behaviors.SING` |  |
| `social/EcologyPresentation.java:206` | 行为／迁移 | `"unun_exercise"` → `Behaviors.UNUN_EXERCISE` |  |
| `social/EcologyPresentation.java:207` | 行为／迁移 | `"sleep_enter"` → `Behaviors.SLEEP_ENTER` |  |
| `social/EcologyPresentation.java:208` | 行为／迁移 | `"house_sleep"` → `Behaviors.HOUSE_SLEEP` |  |
| `social/EcologyPresentation.java:208` | 表情／保留个例 | RHS `"sleep"` → `原样保留` | `case "house_sleep" -> "sleep";` 的右侧是表情 ID；仅左侧行为迁移为 `Behaviors.HOUSE_SLEEP`。 |
| `social/EcologyPresentation.java:209` | 行为／迁移 | `"house_hide"` → `Behaviors.HOUSE_HIDE` |  |
| `social/EcologyPresentation.java:210` | 行为／迁移 | `"house_peek"` → `Behaviors.HOUSE_PEEK` |  |
| `social/EcologyPresentation.java:211` | 行为／迁移 | `"house_search_home"` → `Behaviors.HOUSE_SEARCH_HOME` |  |
| `social/EcologyPresentation.java:212` | 行为／迁移 | `"house_startled"` → `Behaviors.HOUSE_STARTLED` |  |
| `social/EcologyPresentation.java:213` | 行为／迁移 | `"house_tend"` → `Behaviors.HOUSE_TEND` |  |
| `social/EcologyPresentation.java:214` | 行为／迁移 | `"house_listen"` → `Behaviors.HOUSE_LISTEN` |  |
| `social/EcologyPresentation.java:215` | 行为／迁移 | `"house_pickup"` → `Behaviors.HOUSE_PICKUP` |  |
| `social/EcologyPresentation.java:215` | 行为／迁移 | `"house_carry"` → `Behaviors.HOUSE_CARRY` |  |
| `social/EcologyPresentation.java:215` | 行为／迁移 | `"house_shape"` → `Behaviors.HOUSE_SHAPE` |  |
| `social/EcologyPresentation.java:216` | 行为／迁移 | `"house_sniff"` → `Behaviors.HOUSE_SNIFF` |  |
| `social/EcologyPresentation.java:217` | 行为／迁移 | `"house_deposit"` → `Behaviors.HOUSE_DEPOSIT` |  |
| `social/EcologyPresentation.java:218` | 行为／迁移 | `"seek_warmth"` → `Behaviors.SEEK_WARMTH` |  |
| `social/EcologyPresentation.java:218` | 行为／迁移 | `"warm_up"` → `Behaviors.WARM_UP` |  |
| `social/EcologyPresentation.java:219` | 行为／迁移 | `"threat_retreat"` → `Behaviors.THREAT_RETREAT` |  |
| `social/EcologyPresentation.java:219` | 行为／迁移 | `"threat_watch"` → `Behaviors.THREAT_WATCH` |  |
| `social/EcologyPresentation.java:220` | 行为／迁移 | `"threat_hide"` → `Behaviors.THREAT_HIDE` |  |
| `social/EcologyPresentation.java:220` | 行为／迁移 | `"rain_huddle"` → `Behaviors.RAIN_HUDDLE` |  |
| `social/EcologyPresentation.java:220` | 行为／迁移 | `"rain_wait"` → `Behaviors.RAIN_WAIT` |  |
| `social/EcologyPresentation.java:220` | 行为／迁移 | `"rain_run"` → `Behaviors.RAIN_RUN` |  |
| `social/EcologyPresentation.java:221` | 行为／迁移 | `"rain_shake"` → `Behaviors.RAIN_SHAKE` |  |
| `social/EcologyPresentation.java:222` | 行为／迁移 | `"seek_shade"` → `Behaviors.SEEK_SHADE` |  |
| `social/EcologyPresentation.java:222` | 行为／迁移 | `"shade_rest"` → `Behaviors.SHADE_REST` |  |
| `social/EcologyPresentation.java:223` | 行为／迁移 | `"wake"` → `Behaviors.WAKE` |  |
| `social/EcologyPresentation.java:224` | 行为／迁移 | `"ground_toilet"` → `Behaviors.GROUND_TOILET` |  |
| `social/EcologyPresentation.java:224` | 行为／迁移 | `"boat_toilet"` → `Behaviors.BOAT_TOILET` |  |
| `social/EcologyPresentation.java:224` | 行为／迁移 | `"toilet"` → `Behaviors.TOILET` |  |
| `social/EcologyPresentation.java:225` | 行为／迁移 | `"toilet_accident"` → `Behaviors.TOILET_ACCIDENT` |  |
| `social/EcologyPresentation.java:226` | 行为／迁移 | `"seek_toilet"` → `Behaviors.SEEK_TOILET` |  |
| `social/EcologyPresentation.java:227` | 行为／迁移 | `"take_food"` → `Behaviors.TAKE_FOOD` |  |
| `social/EcologyPresentation.java:227` | 行为／迁移 | `"seek_food"` → `Behaviors.SEEK_FOOD` |  |
| `social/EcologyPresentation.java:228` | 行为／迁移 | `"boat_paddle"` → `Behaviors.BOAT_PADDLE` |  |
| `social/EcologyPresentation.java:228` | 行为／迁移 | `"boat_drift"` → `Behaviors.BOAT_DRIFT` |  |
| `social/EcologyPresentation.java:229` | 行为／迁移 | `"aquatic_watch_parent"` → `Behaviors.AQUATIC_WATCH_PARENT` |  |
| `social/EcologyPresentation.java:230` | 行为／迁移 | `"aquatic_answer_family"` → `Behaviors.AQUATIC_ANSWER_FAMILY` |  |
| `social/EcologyPresentation.java:231` | 行为／迁移 | `"aquatic_paddle_practice"` → `Behaviors.AQUATIC_PADDLE_PRACTICE` |  |
| `social/EcologyPresentation.java:231` | 行为／迁移 | `"boat_follow_family"` → `Behaviors.BOAT_FOLLOW_FAMILY` |  |
| `social/EcologyPresentation.java:232` | 行为／迁移 | `"aquatic_imitate_paddle"` → `Behaviors.AQUATIC_IMITATE_PADDLE` |  |
| `social/EcologyPresentation.java:232` | 行为／迁移 | `"boat_guard_young"` → `Behaviors.BOAT_GUARD_YOUNG` |  |
| `social/EcologyPresentation.java:233` | 行为／迁移 | `"boat_receive_paddle"` → `Behaviors.BOAT_RECEIVE_PADDLE` |  |
| `social/EcologyPresentation.java:233` | 行为／迁移 | `"boat_give_paddle"` → `Behaviors.BOAT_GIVE_PADDLE` |  |
| `social/EcologyPresentation.java:234` | 行为／迁移 | `"boat_return_family"` → `Behaviors.BOAT_RETURN_FAMILY` |  |
| `social/EcologyPresentation.java:235` | 行为／迁移 | `"voyage_praise"` → `Behaviors.VOYAGE_PRAISE` |  |
| `social/EcologyPresentation.java:235` | 行为／迁移 | `"voyage_proud"` → `Behaviors.VOYAGE_PROUD` |  |
| `social/EcologyPresentation.java:235` | 行为／迁移 | `"voyage_out"` → `Behaviors.VOYAGE_OUT` |  |
| `social/EcologyPresentation.java:235` | 行为／迁移 | `"hat_brim"` → `Behaviors.HAT_BRIM` |  |
| `social/EcologyPresentation.java:236` | 行为／迁移 | `"voyage_prepare"` → `Behaviors.VOYAGE_PREPARE` |  |
| `social/EcologyPresentation.java:236` | 行为／迁移 | `"voyage_return"` → `Behaviors.VOYAGE_RETURN` |  |
| `social/EcologyPresentation.java:236` | 行为／迁移 | `"voyage_watch"` → `Behaviors.VOYAGE_WATCH` |  |
| `social/EcologyPresentation.java:237` | 行为／迁移 | `"voyage_balance"` → `Behaviors.VOYAGE_BALANCE` |  |
| `social/EcologyPresentation.java:238` | 行为／迁移 | `"aquatic_hat_care"` → `Behaviors.AQUATIC_HAT_CARE` |  |
| `social/EcologyPresentation.java:238` | 行为／迁移 | `"boat_feed_young"` → `Behaviors.BOAT_FEED_YOUNG` |  |
| `social/EcologyPresentation.java:239` | 行为／迁移 | `"boat_provision"` → `Behaviors.BOAT_PROVISION` |  |
| `social/EcologyPresentation.java:239` | 行为／迁移 | `"boat_beg"` → `Behaviors.BOAT_BEG` |  |
| `social/EcologyPresentation.java:240` | 行为／迁移 | `"boat_lullaby"` → `Behaviors.BOAT_LULLABY` |  |
| `social/EcologyPresentation.java:241` | 行为／迁移 | `"boat_gather"` → `Behaviors.BOAT_GATHER` |  |
| `social/EcologyPresentation.java:241` | 行为／迁移 | `"boat_eat"` → `Behaviors.BOAT_EAT` |  |
| `social/EcologyPresentation.java:242` | 行为／迁移 | `"boat_drink"` → `Behaviors.BOAT_DRINK` |  |
| `social/EcologyPresentation.java:243` | 行为／迁移 | `"boat_sleep"` → `Behaviors.BOAT_SLEEP` |  |
| `social/EcologyPresentation.java:244` | 行为／迁移 | `"boat_wait_young"` → `Behaviors.BOAT_WAIT_YOUNG` |  |
| `social/EcologyPresentation.java:244` | 行为／迁移 | `"boat_call"` → `Behaviors.BOAT_CALL` |  |
| `social/EcologyPresentation.java:245` | 行为／迁移 | `"boat_steady"` → `Behaviors.BOAT_STEADY` |  |
| `social/EcologyPresentation.java:246` | 行为／迁移 | `"boat_crowd_guard"` → `Behaviors.BOAT_CROWD_GUARD` |  |
| `social/EcologyPresentation.java:246` | 行为／迁移 | `"hat_scramble"` → `Behaviors.HAT_SCRAMBLE` |  |
| `social/EcologyPresentation.java:246` | 行为／迁移 | `"hat_slipping"` → `Behaviors.HAT_SLIPPING` |  |
| `social/EcologyPresentation.java:246` | 行为／迁移 | `"boat_rocked"` → `Behaviors.BOAT_ROCKED` |  |
| `social/EcologyPresentation.java:246` | 行为／迁移 | `"hat_stumble"` → `Behaviors.HAT_STUMBLE` |  |
| `social/EcologyPresentation.java:246` | 行为／迁移 | `"hat_jostle"` → `Behaviors.HAT_JOSTLE` |  |
| `social/EcologyPresentation.java:247` | 行为／迁移 | `"boat_rescue_young"` → `Behaviors.BOAT_RESCUE_YOUNG` |  |
| `social/EcologyPresentation.java:247` | 行为／迁移 | `"boat_reassure"` → `Behaviors.BOAT_REASSURE` |  |
| `social/EcologyPresentation.java:248` | 行为／迁移 | `"boat_rescue_approach"` → `Behaviors.BOAT_RESCUE_APPROACH` |  |
| `social/EcologyPresentation.java:248` | 行为／迁移 | `"water_hat_remove"` → `Behaviors.WATER_HAT_REMOVE` |  |
| `social/EcologyPresentation.java:248` | 行为／迁移 | `"water_hat_right"` → `Behaviors.WATER_HAT_RIGHT` |  |
| `social/EcologyPresentation.java:248` | 行为／迁移 | `"water_hat_reach"` → `Behaviors.WATER_HAT_REACH` |  |
| `social/EcologyPresentation.java:248` | 行为／迁移 | `"water_hat_climb"` → `Behaviors.WATER_HAT_CLIMB` |  |
| `social/EcologyPresentation.java:248` | 行为／迁移 | `"water_hat_grip"` → `Behaviors.WATER_HAT_GRIP` |  |
| `social/EcologyPresentation.java:248` | 行为／迁移 | `"water_hat_slip"` → `Behaviors.WATER_HAT_SLIP` |  |
| `social/EcologyPresentation.java:249` | 行为／迁移 | `"hat_board_settle"` → `Behaviors.HAT_BOARD_SETTLE` | 多行 switch 延续的行为标签，保持匹配值。 |
| `social/EcologyPresentation.java:249` | 行为／迁移 | `"hat_board_align"` → `Behaviors.HAT_BOARD_ALIGN` | 多行 switch 延续的行为标签，保持匹配值。 |
| `social/EcologyPresentation.java:249` | 行为／迁移 | `"hat_board_grip"` → `Behaviors.HAT_BOARD_GRIP` | 多行 switch 延续的行为标签，保持匹配值。 |
| `social/EcologyPresentation.java:249` | 行为／迁移 | `"hat_board_lift"` → `Behaviors.HAT_BOARD_LIFT` | 多行 switch 延续的行为标签，保持匹配值。 |
| `social/EcologyPresentation.java:249` | 行为／迁移 | `"hat_board_hop"` → `Behaviors.HAT_BOARD_HOP` | 多行 switch 延续的行为标签，保持匹配值。 |
| `social/EcologyPresentation.java:250` | 行为／迁移 | `"water_hat_safe"` → `Behaviors.WATER_HAT_SAFE` |  |
| `social/EcologyPresentation.java:251` | 行为／迁移 | `"boat_answer_call"` → `Behaviors.BOAT_ANSWER_CALL` |  |
| `social/EcologyPresentation.java:251` | 行为／迁移 | `"boat_alarm_call"` → `Behaviors.BOAT_ALARM_CALL` |  |
| `social/EcologyPresentation.java:252` | 行为／迁移 | `"boat_return_called"` → `Behaviors.BOAT_RETURN_CALLED` |  |
| `social/EcologyPresentation.java:252` | 行为／迁移 | `"braid_rescue_reach"` → `Behaviors.BRAID_RESCUE_REACH` |  |
| `social/EcologyPresentation.java:252` | 行为／迁移 | `"braid_rescue_offer"` → `Behaviors.BRAID_RESCUE_OFFER` |  |
| `social/EcologyPresentation.java:252` | 行为／迁移 | `"braid_rescue_hold"` → `Behaviors.BRAID_RESCUE_HOLD` |  |
| `social/EcologyPresentation.java:252` | 行为／迁移 | `"braid_be_rescued"` → `Behaviors.BRAID_BE_RESCUED` |  |
| `social/EcologyPresentation.java:252` | 行为／迁移 | `"boat_alarm_wait"` → `Behaviors.BOAT_ALARM_WAIT` |  |
| `social/EcologyPresentation.java:252` | 行为／迁移 | `"boat_flee"` → `Behaviors.BOAT_FLEE` |  |
| `social/EcologyPresentation.java:253` | 行为／迁移 | `"braid_rescue_push"` → `Behaviors.BRAID_RESCUE_PUSH` |  |
| `social/EcologyPresentation.java:254` | 行为／迁移 | `"boat_calm"` → `Behaviors.BOAT_CALM` |  |
| `social/EcologyPresentation.java:255` | 行为／迁移 | `"braid_rescue_settle"` → `Behaviors.BRAID_RESCUE_SETTLE` |  |
| `social/EcologyPresentation.java:256` | 行为／迁移 | `"water_impact"` → `Behaviors.WATER_IMPACT` |  |
| `social/EcologyPresentation.java:257` | 行为／迁移 | `"water_exhausted"` → `Behaviors.WATER_EXHAUSTED` |  |
| `social/EcologyPresentation.java:257` | 行为／迁移 | `"water_struggle"` → `Behaviors.WATER_STRUGGLE` |  |
| `social/EcologyPresentation.java:257` | 行为／迁移 | `"water_recover"` → `Behaviors.WATER_RECOVER` |  |
| `social/EcologyPresentation.java:258` | 行为／迁移 | `"family_chew"` → `Behaviors.FAMILY_CHEW` |  |
| `social/EcologyPresentation.java:258` | 行为／迁移 | `"chew"` → `Behaviors.CHEW` |  |
| `social/EcologyPresentation.java:259` | 行为／迁移 | `"swallow"` → `Behaviors.SWALLOW` |  |
| `social/EcologyPresentation.java:259` | 行为／迁移 | `"drink"` → `Behaviors.DRINK` |  |
| `social/EcologyPresentation.java:260` | 行为／迁移 | `"sanction_approach"` → `Behaviors.SANCTION_APPROACH` |  |
| `social/EcologyPresentation.java:260` | 行为／迁移 | `"sanction_declare"` → `Behaviors.SANCTION_DECLARE` |  |
| `social/EcologyPresentation.java:260` | 行为／迁移 | `"sanction_bully"` → `Behaviors.SANCTION_BULLY` |  |
| `social/EcologyPresentation.java:260` | 行为／迁移 | `"protect_peer"` → `Behaviors.PROTECT_PEER` |  |
| `social/EcologyPresentation.java:260` | 行为／迁移 | `"bully_peer"` → `Behaviors.BULLY_PEER` |  |
| `social/EcologyPresentation.java:260` | 行为／迁移 | `"sumo_push"` → `Behaviors.SUMO_PUSH` |  |
| `social/EcologyPresentation.java:261` | 行为／迁移 | `"shard_seek"` → `Behaviors.SHARD_SEEK` |  |
| `social/EcologyPresentation.java:261` | 行为／迁移 | `"shard_lick"` → `Behaviors.SHARD_LICK` |  |
| `social/EcologyPresentation.java:267` | 物种／迁移 | `e.getVariant().isPredator()` → `e.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `social/GriefLife.java:34` | 身体候选／保留个例 | `other -> other != dying && other.isAlive() && !other.body().dying() && !other.life().isSleeping() && kinOf(level, dying, other) && other.hasLineOfSight(dying))` → `保留原标量读取` | 数值展示、持久化、单项判断或公式；下方指定的合并判断除外。 |
| `social/GriefLife.java:63` | 关系/性格／迁移 | `FamilyData.get(level).socialKin(a.getUUID(), b.getUUID()) &#124;&#124; b.getUUID().equals(a.family().partner()) &#124;&#124; a.getUUID().equals(b.family().partner())` → `Bonds.careOrMate(a, b)` | 保留原服务端上下文。 |
| `social/GriefLife.java:72` | 身体候选／保留个例 | `if (!YukkuriConfig.SACRIFICE.get() &#124;&#124; !entity.isAlive() &#124;&#124; !entity.family().adult() &#124;&#124; entity.body().dying() &#124;&#124; entity.life().isSleeping()` → `保留原标量读取` | 数值展示、持久化、单项判断或公式；下方指定的合并判断除外。 |
| `social/GriefLife.java:73` | 物种／迁移 | `entity.getVariant().isPredator()` → `entity.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `social/GriefLife.java:73` | 物种／迁移 | `entity.getVariant().isHouse()` → `entity.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `social/GriefLife.java:74` | 物种／迁移 | `p.getVariant().isPredator()` → `p.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `social/GriefLife.java:87` | 关系/性格／迁移 | `entity.getUUID().equals(other.family().mother()) &#124;&#124; entity.getUUID().equals(other.family().father())` → `Bonds.parentOf(entity, other)` | 直接父母，不增加年龄、兄弟姐妹或照护账本。 |
| `social/GroupInteractionGoal.java:32` | 行为／迁移 | `"protect_peer"` → `Behaviors.PROTECT_PEER` |  |
| `social/GroupInteractionGoal.java:32` | 行为／迁移 | `"sumo_prepare"` → `Behaviors.SUMO_PREPARE` |  |
| `social/GroupInteractionGoal.java:32` | 行为／迁移 | `"bully_peer"` → `Behaviors.BULLY_PEER` |  |
| `social/GroupInteractionGoal.java:32` | 行为／迁移 | `"sumo_push"` → `Behaviors.SUMO_PUSH` |  |
| `social/GroupInteractionGoal.java:32` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `social/GroupInteractionGoal.java:41` | 身体／迁移 | `e.body().wound()==0&&e.body().infection()==0` → `e.body().condition().readyForGroupPlay()` |  |
| `social/GroupInteractionGoal.java:54` | 阈值／迁移 | `affinity(entity.getUUID(),peer.getUUID())<4` → `affinity(entity.getUUID(),peer.getUUID())<BondLevels.AFFINITY_ACQUAINTED` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `social/GroupInteractionGoal.java:54` | 阈值／迁移 | `affinity(peer.getUUID(),entity.getUUID())<4` → `affinity(peer.getUUID(),entity.getUUID())<BondLevels.AFFINITY_ACQUAINTED` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `social/GroupInteractionGoal.java:76` | 阈值／迁移 | `affinity(entity.getUUID(),peer.getUUID())<=-20` → `affinity(entity.getUUID(),peer.getUUID())<=BondLevels.AFFINITY_HOSTILE` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `social/GroupInteractionGoal.java:76` | 阈值／迁移 | `affinity(entity.getUUID(),peer.getUUID())<=0` → `affinity(entity.getUUID(),peer.getUUID())<=BondLevels.AFFINITY_NEUTRAL` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `social/GroupInteractionGoal.java:137` | 关系／迁移 | `FamilyData.get(level).socialKin(witness.getUUID(),victim.getUUID())` → `Bonds.careKin(witness, victim)` | 只替换既有账本查询；识别、配对兼容、存活与好感条件仍留调用方。 |
| `social/GroupInteractionGoal.java:137` | 阈值／迁移 | `affinity(witness.getUUID(),victim.getUUID())>=15` → `affinity(witness.getUUID(),victim.getUUID())>=BondLevels.AFFINITY_ATTACHED` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `social/SelfOffering.java:58` | 物种／迁移 | `entity.getVariant().isPredator()` → `entity.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `social/SelfOffering.java:58` | 物种／迁移 | `entity.getVariant().isHouse()` → `entity.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `social/SelfOffering.java:58` | 身体候选／保留个例 | `&& !entity.getVariant().isPredator() && !entity.getVariant().isHouse() && !entity.life().isSleeping() && !entity.body().dying()` → `保留原标量读取` | 数值展示、持久化、单项判断或公式；下方指定的合并判断除外。 同行已明确列为迁移的其他子表达式仍按其记录执行；本条只保留此类别语义。 |
| `social/SelfOffering.java:62` | 关系/性格／迁移 | `entity.getUUID().equals(other.family().mother()) &#124;&#124; entity.getUUID().equals(other.family().father())` → `Bonds.parentOf(entity, other)` | 直接父母，不增加年龄条件。 |
| `social/SelfOffering.java:71` | 物种／迁移 | `y.getVariant().isPredator()` → `y.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `social/SelfOffering.java:71` | 身体候选／保留个例 | `&& !y.body().dying() && !y.getVariant().isPredator() && !y.life().isSleeping() && y.hasLineOfSight(entity)` → `保留原标量读取` | 数值展示、持久化、单项判断或公式；下方指定的合并判断除外。 同行已明确列为迁移的其他子表达式仍按其记录执行；本条只保留此类别语义。 |
| `social/SelfOffering.java:72` | 关系保留／保留个例 | `&& (ignoreHunger &#124;&#124; y.life().nutrition() < 10 && !foodNear(y)) && (mine(y) &#124;&#124; y.getUUID().equals(entity.family().partner())));` → `原样保留` | 身份读取、持久化、展示、收件人遍历或特指母方的条件，不替换为泛化亲属查询。 |
| `social/SelfOffering.java:82` | 身体候选／保留个例 | `p += entity.body().wound() / 4f + entity.mind().unease() / 10f;` → `保留原标量读取` | 数值展示、持久化、单项判断或公式；下方指定的合并判断除外。 |
| `social/SelfOffering.java:109` | 身体候选／保留个例 | `return child != null && child.isAlive() && !child.body().dying();` → `保留原标量读取` | 数值展示、持久化、单项判断或公式；下方指定的合并判断除外。 |
| `social/SelfOffering.java:157` | 物种／迁移 | `entity.getVariant().isPredator()` → `entity.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `social/SelfOffering.java:157` | 物种／迁移 | `entity.getVariant().isHouse()` → `entity.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `social/SelfOffering.java:158` | 身体候选／保留个例 | `var near = level().getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(10, 3, 10), y -> y != entity && y.isAlive() && !y.body().dying()` → `保留原标量读取` | 数值展示、持久化、单项判断或公式；下方指定的合并判断除外。 |
| `social/SelfOffering.java:159` | 物种／迁移 | `y.getVariant().isPredator()` → `y.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `social/SelfOffering.java:168` | 身体候选／保留个例 | `if (entity.isAlive() && !entity.body().dying()) { recipient = null; child = null; }` → `保留原标量读取` | 数值展示、持久化、单项判断或公式；下方指定的合并判断除外。 |
| `social/SelfOffering.java:173` | 身体候选／保留个例 | `if (recipient != null && entity.body().dying()) tag.putUUID("Recipient", recipient);` → `保留原标量读取` | 数值展示、持久化、单项判断或公式；下方指定的合并判断除外。 |
| `social/TreasureLife.java:63` | 物种／迁移 | `entity.getVariant().isHouse()` → `entity.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `social/TreasureLife.java:66` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `social/WoundComfort.java:69` | 行为／迁移 | `"comfort_receive"` → `Behaviors.COMFORT_RECEIVE` |  |
| `social/WoundComfort.java:69` | 行为／迁移 | `"comfort_lick"` → `Behaviors.COMFORT_LICK` |  |
| `social/WoundComfort.java:69` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `social/WoundComfort.java:99` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `social/WoundComfort.java:124` | 身体／迁移 | `licker.body().wound() != 0 &#124;&#124; licker.body().burns() != 0 &#124;&#124; licker.body().infection() != 0` → `!licker.body().condition().readyToLick()` | 先替换最长复合表达式；保留其余健康、饥饿、识别或行动条件。 |
| `social/WoundComfort.java:137` | 身体／迁移 | `victim.body().wound() <= 0 && victim.body().burns() <= 0` → `!victim.body().condition().hasSkinOrBurnDamage()` | 先替换最长复合表达式；保留其余健康、饥饿、识别或行动条件。 |
| `social/WoundComfort.java:154` | 关系保留／保留个例 | `boolean mates = other.equals(mine.partner()) && me.equals(theirs.partner());` → `原样保留` | 身份读取、持久化、展示、收件人遍历或特指母方的条件，不替换为泛化亲属查询。 |
| `social/WoundComfort.java:155` | 关系保留／保留个例 | `boolean parent = me.equals(theirs.mother()) &#124;&#124; me.equals(theirs.father());` → `原样保留` | 母/父身份、共享亲本或 UUID 所有者检查；保留原方向，不扩大为照护兄弟姐妹或双向照护。 |
| `social/WoundComfort.java:156` | 关系保留／保留个例 | `boolean child = other.equals(mine.mother()) &#124;&#124; other.equals(mine.father());` → `原样保留` | 母/父身份、共享亲本或 UUID 所有者检查；保留原方向，不扩大为照护兄弟姐妹或双向照护。 |
| `social/WoundComfort.java:157` | 关系/性格／迁移 | `mates &#124;&#124; parent &#124;&#124; child` → `Bonds.comfortFamily(entity, victim)` | 删除仅服务这个判断的 mates/parent/child 局部变量；识别和好感检查顺序保留。 |
| `social/WoundComfort.java:158` | 阈值／迁移 | `affinity(me, other) < 0` → `affinity(me, other) < BondLevels.AFFINITY_NEUTRAL` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `social/WoundComfort.java:185` | 身体／迁移 | `other.body().wound() > 0 &#124;&#124; other.body().burns() > 0` → `other.body().condition().hasSkinOrBurnDamage()` | 先替换最长复合表达式；保留其余健康、饥饿、识别或行动条件。 |
| `social/WoundComfort.java:231` | 身体／迁移 | `victim.body().wound() <= 0 && victim.body().burns() <= 0` → `!victim.body().condition().hasSkinOrBurnDamage()` | 先替换最长复合表达式；保留其余健康、饥饿、识别或行动条件。 |
| `social/YukkuriPersonality.java:62` | 关系/性格／迁移 | `selfishness()<55 && aggression()+selfControl()/3>=50` → `Temperament.protects(this)` | 保留整除，不替换成浮点除法。 |
| `social/YukkuriSocial.java:28` | 行为构造／迁移 | `task.name().toLowerCase(java.util.Locale.ROOT)` → `switch(task) { case NONE -> Behaviors.NONE; case PLAYER -> Behaviors.PLAYER; case PEER -> Behaviors.PEER; case TOY -> Behaviors.TOY; case TEACH -> Behaviors.TEACH; case WAIT -> Behaviors.WAIT; case ACCESSORY -> Behaviors.ACCESSORY; }` | 原 task 名小写或条件后缀逐值保持；同步/存档字符串不变。此完整表达式优先于同行内部常量映射。 |
| `social/YukkuriSocial.java:64` | 关系/性格／迁移 | `personality().trust(player.getUUID()) < 10 &#124;&#124; personality().fear(player.getUUID()) >= 30` → `!Bonds.trustedForLesson(entity, player.getUUID())` | 原拒绝分支保持。 |
| `social/YukkuriSocial.java:165` | 阈值／迁移 | `fear(target.getUUID()) >= 30` → `fear(target.getUUID()) >= BondLevels.SOCIAL_FEAR_LIMIT` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `social/YukkuriSocial.java:166` | 阈值／迁移 | `selfControl()<30` → `selfControl()<Temperament.SELF_CONTROL_AT_30` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `social/YukkuriSocial.java:178` | 阈值／迁移 | `fear(target.getUUID()) > 15` → `fear(target.getUUID()) > BondLevels.FEAR_AT_15` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `social/YukkuriSocial.java:192` | 阈值／迁移 | `trust(target.getUUID()) >= 20` → `trust(target.getUUID()) >= BondLevels.TRUST_AT_20` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `social/YukkuriSocial.java:198` | 阈值／迁移 | `affinity(entity.getUUID(),peer.getUUID()) < 0` → `affinity(entity.getUUID(),peer.getUUID()) < BondLevels.AFFINITY_NEUTRAL` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `social/YukkuriSocial.java:202` | 阈值／迁移 | `affinity(entity.getUUID(), peer.getUUID()) >= 10` → `affinity(entity.getUUID(), peer.getUUID()) >= BondLevels.AFFINITY_CLOSE` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `social/YukkuriSocial.java:205` | 阈值／迁移 | `affinity(peer.getUUID(), entity.getUUID()) >= 0` → `affinity(peer.getUUID(), entity.getUUID()) >= BondLevels.AFFINITY_NEUTRAL` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `status/Reactions.java:66` | 物种／迁移 | `y.getVariant().isPredator()` → `y.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `status/Reactions.java:66` | 物种／迁移 | `y.getVariant().isHouse()` → `y.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `status/Reactions.java:82` | 关系/性格／迁移 | `a.family().caresFor(b) &#124;&#124; b.family().caresFor(a)` → `Bonds.currentCarePair(a, b)` | 保留成年/幼年条件及原识别前置。 |
| `status/Reactions.java:83` | 关系/性格／迁移 | `b.getUUID().equals(a.family().partner()) &#124;&#124; a.getUUID().equals(b.family().partner())` → `Bonds.eitherMate(a, b)` | 单向登记即可，不改成双向。 |
| `status/Reactions.java:85` | 关系/性格／迁移 | `group != null && group.equals(b.settlement().groupId())` → `Bonds.sameGroupId(a, b)` | 与 SettlementSociety.sameGroup 的完整世界/实体条件不同。 |
| `status/Reactions.java:87` | 关系/性格／迁移 | `FamilyData.get(level).socialKin(a.getUUID(), b.getUUID())` → `Bonds.careKin(a, b)` | 剩余好感 >=10 分支保留。 |
| `status/Reactions.java:88` | 阈值／迁移 | `affinity(a.getUUID(), b.getUUID()) >= 10` → `affinity(a.getUUID(), b.getUUID()) >= BondLevels.AFFINITY_CLOSE` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `status/Reactions.java:119` | 行为／迁移 | `"threat_retreat"` → `Behaviors.THREAT_RETREAT` |  |
| `status/Reactions.java:119` | 行为／迁移 | `"threat_watch"` → `Behaviors.THREAT_WATCH` |  |
| `status/Reactions.java:119` | 行为／迁移 | `"threat_hide"` → `Behaviors.THREAT_HIDE` |  |
| `status/Reactions.java:119` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `status/Reactions.java:126` | 物种／迁移 | `p.getVariant().isPredator()` → `p.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `status/Reactions.java:269` | 行为／迁移 | `"rain_huddle"` → `Behaviors.RAIN_HUDDLE` |  |
| `status/Reactions.java:269` | 行为／迁移 | `"rain_shake"` → `Behaviors.RAIN_SHAKE` |  |
| `status/Reactions.java:269` | 行为／迁移 | `"rain_wait"` → `Behaviors.RAIN_WAIT` |  |
| `status/Reactions.java:269` | 行为／迁移 | `"rain_run"` → `Behaviors.RAIN_RUN` |  |
| `status/Reactions.java:269` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `status/StatusKey.java:80` | 身体候选／保留个例 | `WOUND("health", "wound", 0, y -> y.body().wound(), (y, raw) -> wound(y, raw, false)),` → `保留原标量读取` | 数值展示、持久化、单项判断或公式；下方指定的合并判断除外。 |
| `status/StatusKey.java:81` | 身体候选／保留个例 | `BURNS("health", "burns", 0, y -> y.body().burns(), (y, raw) -> wound(y, raw, true)),` → `保留原标量读取` | 数值展示、持久化、单项判断或公式；下方指定的合并判断除外。 |
| `status/StatusKey.java:82` | 身体候选／保留个例 | `INFECTION("health", "infection", 0, y -> y.body().infection(), (y, raw) -> {` → `保留原标量读取` | 数值展示、持久化、单项判断或公式；下方指定的合并判断除外。 |
| `status/StatusKey.java:85` | 身体候选／保留个例 | `for (int i = y.body().infection(); i < Math.min(8, Math.round(v)); i++) y.body().contaminated();` → `保留原标量读取` | 数值展示、持久化、单项判断或公式；下方指定的合并判断除外。 |
| `status/StatusKey.java:86` | 身体候选／保留个例 | `return Math.round(v) < y.body().infection() ? "infection only rises by hand; treat it with medicine" : null;` → `保留原标量读取` | 数值展示、持久化、单项判断或公式；下方指定的合并判断除外。 |
| `status/StatusKey.java:103` | 身体候选／保留个例 | `FILLING("health", "filling", 0, y -> y.body().filling(), (y, raw) -> {` → `保留原标量读取` | 数值展示、持久化、单项判断或公式；下方指定的合并判断除外。 |
| `status/StatusKey.java:112` | 身体候选／保留个例 | `MOLD("health", "mold", 0, y -> y.body().mold(), (y, raw) -> {` → `保留原标量读取` | 数值展示、持久化、单项判断或公式；下方指定的合并判断除外。 |
| `status/StatusKey.java:152` | 身体候选／保留个例 | `WEAK("health", "weak", 0, y -> y.body().weak(), null),` → `保留原标量读取` | 数值展示、持久化、单项判断或公式；下方指定的合并判断除外。 |
| `status/StatusKey.java:161` | 身体候选／保留个例 | `FOOT_BURNED("health", "foot_burned", 0, y -> y.body().footBurned(), (y, raw) -> {` → `保留原标量读取` | 数值展示、持久化、单项判断或公式；下方指定的合并判断除外。 |
| `status/StatusKey.java:244` | 物种／迁移 | `y.getVariant().isPredator()` → `y.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `status/StatusKey.java:263` | 关系保留／保留个例 | `PARTNER("relations", "partner", 0, y -> uuid(y.family().partner()), null),` → `原样保留` | 身份读取、持久化、展示、收件人遍历或特指母方的条件，不替换为泛化亲属查询。 |
| `status/StatusKey.java:264` | 关系保留／保留个例 | `MOTHER("relations", "mother", 0, y -> uuid(y.family().mother()), null),` → `原样保留` | 身份读取、持久化、展示、收件人遍历或特指母方的条件，不替换为泛化亲属查询。 |
| `status/StatusKey.java:265` | 关系保留／保留个例 | `FATHER("relations", "father", 0, y -> uuid(y.family().father()), null),` → `原样保留` | 身份读取、持久化、展示、收件人遍历或特指母方的条件，不替换为泛化亲属查询。 |
| `status/YukkuriStatus.java:29` | 物种／迁移 | `y.getVariant().isHouse()` → `y.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `status/YukkuriStatus.java:61` | 物种／迁移 | `y.getVariant().isHouse()` → `y.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `status/YukkuriStatus.java:159` | 物种个例／保留个例 | `case ALICE_WATCH -> "攻击魔搜寻";` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `status/YukkuriStatus.java:160` | 物种个例／保留个例 | `case ALICE_APPROACH -> "攻击魔追击";` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `status/YukkuriStatus.java:161` | 物种个例／保留个例 | `case ALICE_ACT -> "素鸡立中";` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |

#### M3

| 位置 | 类别／决定 | 旧表达式 → 新入口 | 边界 |
| --- | --- | --- | --- |
| `event/Expedition.java:81` | 物种个例／保留个例 | `&&e.getVariant().dialogueSpecies().equals("marisa")&&e.life().personality().trait(Trait.BOLDNESS)>=60` → `原样保留` | 具体口音/角色种类，保留 dialogueSpecies，不套生态能力。 同行已明确列为迁移的其他子表达式仍按其记录执行；本条只保留此类别语义。 |
| `event/Expedition.java:81` | 阈值／迁移 | `trait(Trait.BOLDNESS)>=60` → `trait(Trait.BOLDNESS)>=Temperament.BOLDNESS_AT_60` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `event/Expedition.java:86` | 物种个例／保留个例 | `&&e.life().nutrition()>=55&&e.life().energy()>=65&&e.getVariant().dialogueSpecies().equals("marisa")` → `原样保留` | 具体口音/角色种类，保留 dialogueSpecies，不套生态能力。 |
| `event/GroupCards.java:59` | 阈值／迁移 | `selfishness() < 65` → `selfishness() < Temperament.SELFISHNESS_AT_65` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `event/GroupCards.java:65` | 关系／迁移 | `family.socialKin(e.getUUID(), p.getUUID())` → `Bonds.careKin(e, p)` | 只替换既有账本查询；识别、配对兼容、存活与好感条件仍留调用方。 |
| `event/HomeClaim.java:57` | 物种／迁移 | `e.getVariant().isPredator()` → `e.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `event/HomeClaim.java:57` | 物种／迁移 | `e.getVariant().isHouse()` → `e.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `event/HomeClaim.java:62` | 关系／迁移 | `kin.socialKin(e.getUUID(),p.getUUID())` → `Bonds.careKin(e, p)` | 只替换既有账本查询；识别、配对兼容、存活与好感条件仍留调用方。 |
| `event/RiverEncounter.java:31` | 关系／迁移 | `FamilyData.get(level).socialKin(shore.getUUID(),rider.getUUID())` → `Bonds.careKin(shore, rider)` | 只替换既有账本查询；识别、配对兼容、存活与好感条件仍留调用方。 |
| `event/RiverEncounter.java:32` | 关系／迁移 | `shore.settlement().groupId()!=null&&shore.settlement().groupId().equals(rider.settlement().groupId())` → `Bonds.sameGroupId(shore, rider)` | 仅比非空群 UUID，不加 FAMILY 类型、同世界、存活或关系门槛。 |
| `event/RiverEncounter.java:33` | 阈值／迁移 | `affinity(shore.getUUID(),rider.getUUID())<0` → `affinity(shore.getUUID(),rider.getUUID())<BondLevels.AFFINITY_NEUTRAL` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `event/WildCards.java:38` | 关系／迁移 | `FamilyData.get((ServerLevel)first.level()).socialKin(first.getUUID(),p.getUUID())` → `Bonds.careKin(first, p)` | 只替换既有账本查询；识别、配对兼容、存活与好感条件仍留调用方。 |
| `event/WildCards.java:47` | 阈值／迁移 | `trait(Trait.CARE)>=60` → `trait(Trait.CARE)>=Temperament.CARE_AT_60` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `event/WildCards.java:48` | 关系/性格／迁移 | `FamilyData.get((ServerLevel)a.level()).socialKin(a.getUUID(),b.getUUID())` → `Bonds.careKin(a, b)` | 两处都替换；friendly/性格/好感外围保持。 |
| `event/WildCards.java:49` | 阈值／迁移 | `affinity(a.getUUID(),b.getUUID())>=10` → `affinity(a.getUUID(),b.getUUID())>=BondLevels.AFFINITY_CLOSE` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `event/WildCards.java:52` | 关系/性格／迁移 | `FamilyData.get((ServerLevel)a.level()).socialKin(a.getUUID(),b.getUUID())` → `Bonds.careKin(a, b)` | 两处都替换；friendly/性格/好感外围保持。 |
| `event/WildCards.java:53` | 阈值／迁移 | `affinity(a.getUUID(),b.getUUID())>=10` → `affinity(a.getUUID(),b.getUUID())>=BondLevels.AFFINITY_CLOSE` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `event/WildCards.java:101` | 阈值／迁移 | `affinity(second.getUUID(),first.getUUID())<0` → `affinity(second.getUUID(),first.getUUID())<BondLevels.AFFINITY_NEUTRAL` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `event/WildCards.java:101` | 阈值／迁移 | `affinity<=-10` → `affinity<=BondLevels.AFFINITY_DISLIKED` | 局部变量仍读原来源；比较方向、量化与奖励/惩罚公式不变。 |
| `event/WildCards.java:103` | 派生阈值／保留个例 | `case "territory" -> first.life().personality().escalationScore()>=65 && first.life().personality().selfishness()>=65` → `原样保留` | escalationScore>=65 或双方 CURIOSITY 差值>=20 保留，不等同单项严厉/好奇绝对阈值。 同行已明确列为迁移的其他子表达式仍按其记录执行；本条只保留此类别语义。 |
| `event/WildCards.java:103` | 阈值／迁移 | `selfishness()>=65` → `selfishness()>=Temperament.SELFISHNESS_AT_65` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `event/WildCards.java:104` | 关系／迁移 | `family.socialKin(first.getUUID(),second.getUUID())` → `Bonds.careKin(first, second)` | 只替换既有账本查询；识别、配对兼容、存活与好感条件仍留调用方。 |
| `event/WildCards.java:104` | 阈值／迁移 | `affinity<=0` → `affinity<=BondLevels.AFFINITY_NEUTRAL` | 局部变量仍读原来源；比较方向、量化与奖励/惩罚公式不变。 |
| `event/WildCards.java:107` | 阈值／迁移 | `fear(level.getNearestPlayer(first,6).getUUID())>=15` → `fear(level.getNearestPlayer(first,6).getUUID())>=BondLevels.FEAR_AT_15` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `event/WildCards.java:110` | 关系保留／保留个例 | `case "mate-touch" -> second.getUUID().equals(first.family().partner()) && first.getUUID().equals(second.family().partner())` → `原样保留` | 身份读取、持久化、展示、收件人遍历或特指母方的条件，不替换为泛化亲属查询。 |
| `event/WildCards.java:112` | 物种个例／保留个例 | `case "imitate" -> !first.getVariant().dialogueSpecies().equals(second.getVariant().dialogueSpecies())` → `原样保留` | 具体口音/角色种类，保留 dialogueSpecies，不套生态能力。 |
| `event/WildCards.java:113` | 阈值／迁移 | `trait(Trait.CURIOSITY)>=60` → `trait(Trait.CURIOSITY)>=Temperament.CURIOSITY_AT_60` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `event/WildCards.java:120` | 阈值／迁移 | `trait(Trait.CARE)>=60` → `trait(Trait.CARE)>=Temperament.CARE_AT_60` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `event/WildCards.java:120` | 阈值／迁移 | `affinity>-40` → `affinity>BondLevels.AFFINITY_RECONCILABLE` | 局部变量仍读原来源；比较方向、量化与奖励/惩罚公式不变。 |
| `event/WildCards.java:121` | 关系／迁移 | `first.settlement().groupId()!=null && first.settlement().groupId().equals(second.settlement().groupId())` → `Bonds.sameGroupId(first, second)` | 仅比非空群 UUID，不加 FAMILY 类型、同世界、存活或关系门槛。 |
| `event/WildCards.java:127` | 派生阈值／保留个例 | `case "tank-doll" -> Math.abs(first.life().personality().trait(Trait.CURIOSITY)-second.life().personality().trait(Trait.CURIOSITY))>=20` → `原样保留` | escalationScore>=65 或双方 CURIOSITY 差值>=20 保留，不等同单项严厉/好奇绝对阈值。 |
| `event/WildCards.java:141` | 关系／迁移 | `family.socialKin(first.getUUID(),p.getUUID())` → `Bonds.careKin(first, p)` | 只替换既有账本查询；识别、配对兼容、存活与好感条件仍留调用方。 |
| `event/WildCards.java:142` | 关系／迁移 | `family.socialKin(first.getUUID(),second.getUUID())` → `Bonds.careKin(first, second)` | 只替换既有账本查询；识别、配对兼容、存活与好感条件仍留调用方。 |
| `event/WildCards.java:230` | 阈值／迁移 | `affinity(second.getUUID(),first.getUUID())>=-10` → `affinity(second.getUUID(),first.getUUID())>=BondLevels.AFFINITY_DISLIKED` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `event/WildEvents.java:38` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `event/WildEvents.java:40` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `event/WildEvents.java:41` | 行为／迁移 | `"event_"+s.card.replace('-','_')` → `Behaviors.event(s.card.replace('-','_'))` | 只替换行为 ID 或其构造；保留规范化位置、大小写、短路和原前后缀。 |
| `event/WildEvents.java:41` | 行为／迁移 | `"event_"` → `Behaviors.EVENT_PREFIX` |  |
| `event/WildEvents.java:42` | 行为／迁移 | `"event_badge_"+s.card` → `Behaviors.eventBadge(s.card)` | 只替换行为 ID 或其构造；保留规范化位置、大小写、短路和原前后缀。 |
| `event/WildEvents.java:42` | 行为／迁移 | `"event_badge_"` → `Behaviors.EVENT_BADGE_PREFIX` |  |
| `event/WildEvents.java:43` | 行为／迁移 | `"event_"+s.kind.name().toLowerCase(java.util.Locale.ROOT)` → `Behaviors.event(s.kind.name().toLowerCase(java.util.Locale.ROOT))` | 只替换行为 ID 或其构造；保留规范化位置、大小写、短路和原前后缀。 |
| `event/WildEvents.java:43` | 行为／迁移 | `"event_separated"` → `Behaviors.EVENT_SEPARATED` |  |
| `event/WildEvents.java:43` | 行为／迁移 | `"event_"` → `Behaviors.EVENT_PREFIX` |  |
| `event/WildEvents.java:50` | 身体／迁移 | `e.body().wound()==0 && e.body().infection()==0` → `e.body().condition().readyForWildEvent()` |  |
| `event/WildEvents.java:53` | 物种／迁移 | `e.getVariant().isPredator()` → `e.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `event/WildEvents.java:53` | 物种／迁移 | `e.getVariant().isHouse()` → `e.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `event/WildEvents.java:63` | 阈值／迁移 | `affinity(a.getUUID(),b.getUUID())>=0` → `affinity(a.getUUID(),b.getUUID())>=BondLevels.AFFINITY_NEUTRAL` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `event/WildEvents.java:63` | 阈值／迁移 | `affinity(b.getUUID(),a.getUUID())>=0` → `affinity(b.getUUID(),a.getUUID())>=BondLevels.AFFINITY_NEUTRAL` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `event/WildEvents.java:65` | 关系保留／保留个例 | `public static boolean siblings(YukkuriEntity a, YukkuriEntity b) {` → `原样保留` | FamilyData.Person 账本与成年/幼年/DEAD 条件，保留 siblings/childOf/singleParent 原优先级；不能以实体 family 槽位替代，也不能把失联当死亡。 |
| `event/WildEvents.java:66` | 关系保留／保留个例 | `if(a==b&#124;&#124;a.family().adult()&#124;&#124;b.family().adult())return false;` → `原样保留` | FamilyData.Person 账本与成年/幼年/DEAD 条件，保留 siblings/childOf/singleParent 原优先级；不能以实体 family 槽位替代，也不能把失联当死亡。 |
| `event/WildEvents.java:67` | 关系保留／保留个例 | `var data=FamilyData.get((ServerLevel)a.level());var first=data.person(a.getUUID());var second=data.person(b.getUUID());` → `原样保留` | FamilyData.Person 账本与成年/幼年/DEAD 条件，保留 siblings/childOf/singleParent 原优先级；不能以实体 family 槽位替代，也不能把失联当死亡。 |
| `event/WildEvents.java:68` | 关系保留／保留个例 | `return first!=null && second!=null && (first.mother!=null && first.mother.equals(second.mother)` → `原样保留` | FamilyData.Person 账本与成年/幼年/DEAD 条件，保留 siblings/childOf/singleParent 原优先级；不能以实体 family 槽位替代，也不能把失联当死亡。 |
| `event/WildEvents.java:69` | 关系保留／保留个例 | `&#124;&#124; first.father!=null && first.father.equals(second.father));` → `原样保留` | FamilyData.Person 账本与成年/幼年/DEAD 条件，保留 siblings/childOf/singleParent 原优先级；不能以实体 family 槽位替代，也不能把失联当死亡。 |
| `event/WildEvents.java:71` | 关系保留／保留个例 | `public static boolean childOf(YukkuriEntity parent, YukkuriEntity child) {` → `原样保留` | FamilyData.Person 账本与成年/幼年/DEAD 条件，保留 siblings/childOf/singleParent 原优先级；不能以实体 family 槽位替代，也不能把失联当死亡。 |
| `event/WildEvents.java:72` | 关系保留／保留个例 | `var person=FamilyData.get((ServerLevel)parent.level()).person(child.getUUID());` → `原样保留` | FamilyData.Person 账本与成年/幼年/DEAD 条件，保留 siblings/childOf/singleParent 原优先级；不能以实体 family 槽位替代，也不能把失联当死亡。 |
| `event/WildEvents.java:73` | 关系保留／保留个例 | `return parent.family().adult() && !child.family().adult() && person!=null` → `原样保留` | FamilyData.Person 账本与成年/幼年/DEAD 条件，保留 siblings/childOf/singleParent 原优先级；不能以实体 family 槽位替代，也不能把失联当死亡。 |
| `event/WildEvents.java:74` | 关系保留／保留个例 | `&& (parent.getUUID().equals(person.mother)&#124;&#124;parent.getUUID().equals(person.father));` → `原样保留` | FamilyData.Person 账本与成年/幼年/DEAD 条件，保留 siblings/childOf/singleParent 原优先级；不能以实体 family 槽位替代，也不能把失联当死亡。 |
| `event/WildEvents.java:76` | 关系保留／保留个例 | `public static boolean singleParent(YukkuriEntity parent, YukkuriEntity child) {` → `原样保留` | FamilyData.Person 账本与成年/幼年/DEAD 条件，保留 siblings/childOf/singleParent 原优先级；不能以实体 family 槽位替代，也不能把失联当死亡。 |
| `event/WildEvents.java:77` | 关系保留／保留个例 | `if(!childOf(parent,child))return false;` → `原样保留` | FamilyData.Person 账本与成年/幼年/DEAD 条件，保留 siblings/childOf/singleParent 原优先级；不能以实体 family 槽位替代，也不能把失联当死亡。 |
| `event/WildEvents.java:78` | 关系保留／保留个例 | `var data=FamilyData.get((ServerLevel)parent.level());var childRecord=data.person(child.getUUID());` → `原样保留` | FamilyData.Person 账本与成年/幼年/DEAD 条件，保留 siblings/childOf/singleParent 原优先级；不能以实体 family 槽位替代，也不能把失联当死亡。 |
| `event/WildEvents.java:79` | 关系保留／保留个例 | `UUID other=parent.getUUID().equals(childRecord.mother)?childRecord.father:childRecord.mother;` → `原样保留` | FamilyData.Person 账本与成年/幼年/DEAD 条件，保留 siblings/childOf/singleParent 原优先级；不能以实体 family 槽位替代，也不能把失联当死亡。 |
| `event/WildEvents.java:81` | 关系保留／保留个例 | `if(other!=null && (data.person(other)==null &#124;&#124; data.person(other).status!=FamilyData.Status.DEAD))return false;` → `原样保留` | FamilyData.Person 账本与成年/幼年/DEAD 条件，保留 siblings/childOf/singleParent 原优先级；不能以实体 family 槽位替代，也不能把失联当死亡。 |
| `event/WildEvents.java:82` | 关系保留／保留个例 | `UUID mate=parent.family().partner();` → `原样保留` | 身份读取、持久化、展示、收件人遍历或特指母方的条件，不替换为泛化亲属查询。 |
| `event/WildEvents.java:118` | 关系／迁移 | `FamilyData.get(level).socialKin(first.getUUID(),e.getUUID())` → `Bonds.careKin(first, e)` | 只替换既有账本查询；识别、配对兼容、存活与好感条件仍留调用方。 |
| `event/WildEvents.java:121` | 派生阈值／保留个例 | `&& first.life().personality().approachScore(player.getUUID())>=45 && first.life().personality().fear(player.getUUID())<25` → `原样保留` | approachScore>=45 为派生分数门槛，保留；同行 fear 阈值按已列迁移，不套 aggression 常量。 同行已明确列为迁移的其他子表达式仍按其记录执行；本条只保留此类别语义。 |
| `event/WildEvents.java:121` | 阈值／迁移 | `fear(player.getUUID())<25` → `fear(player.getUUID())<BondLevels.AFFECTION_FEAR_LIMIT` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `event/WildEvents.java:155` | 关系／迁移 | `FamilyData.get(level).socialKin(e.getUUID(),p.getUUID())` → `Bonds.careKin(e, p)` | 只替换既有账本查询；识别、配对兼容、存活与好感条件仍留调用方。 |
| `event/WildEvents.java:227` | 阈值／迁移 | `trust(s.player)>=10` → `trust(s.player)>=BondLevels.TRUST_FAMILIAR` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `event/WildEvents.java:269` | 物种／迁移 | `e.getVariant().isPredator()` → `e.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `event/WildEvents.java:269` | 物种／迁移 | `e.getVariant().isHouse()` → `e.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `event/WildEvents.java:270` | 关系/性格／迁移 | `e.life().personality().trust(player.getUUID())>=10&&e.life().personality().fear(player.getUUID())<25` → `Bonds.trustedPlayer(e, player.getUUID())` | 原 unowned 等外层判断保持。 |
| `house/HouseCrawlControl.java:23` | 身体候选／保留个例 | `&& (!entity.childhood().canJump() &#124;&#124; entity.body().footBurned() &#124;&#124; entity.house().wantsCrawl() &#124;&#124; entity.life().wantsCrawl());` → `保留原标量读取` | 数值展示、持久化、单项判断或公式；下方指定的合并判断除外。 |
| `house/HouseLife.java:79` | 物种／留 R5 | `if (!entity.getVariant().isHouse() &#124;&#124; !value.usable(entity) &#124;&#124; !entity.life().reachable(value.entrance(), false)` → `原样保留` | 整套例程、住所路由、出生方式选择或按物种存读分派；E1 生命周期搬迁时同样不合并这些分支。 |
| `house/HouseLife.java:138` | 行为／迁移 | `"house_deposit"` → `Behaviors.HOUSE_DEPOSIT` |  |
| `house/HouseLife.java:138` | 行为／迁移 | `"house_pickup"` → `Behaviors.HOUSE_PICKUP` |  |
| `house/HouseLife.java:140` | 行为／迁移 | `"house_carry"` → `Behaviors.HOUSE_CARRY` |  |
| `house/HouseLife.java:140` | 行为／迁移 | `"house_tend"` → `Behaviors.HOUSE_TEND` |  |
| `house/HouseLife.java:140` | 行为／迁移 | `"house_hide"` → `Behaviors.HOUSE_HIDE` |  |
| `house/HouseLife.java:141` | 行为／迁移 | `"house_enter"` → `Behaviors.HOUSE_ENTER` |  |
| `house/HouseLife.java:142` | 行为／迁移 | `"house_sleep"` → `Behaviors.HOUSE_SLEEP` |  |
| `house/HouseLife.java:142` | 行为／迁移 | `"house_hide"` → `Behaviors.HOUSE_HIDE` |  |
| `house/HouseLife.java:143` | 行为／迁移 | `"house_exit"` → `Behaviors.HOUSE_EXIT` |  |
| `house/HouseLife.java:144` | 行为／迁移 | `"house_peek"` → `Behaviors.HOUSE_PEEK` |  |
| `house/HouseLife.java:145` | 行为／迁移 | `"house_tend"` → `Behaviors.HOUSE_TEND` |  |
| `house/HouseLife.java:146` | 行为／迁移 | `"house_listen"` → `Behaviors.HOUSE_LISTEN` |  |
| `house/HouseLife.java:147` | 行为／迁移 | `"house_startled"` → `Behaviors.HOUSE_STARTLED` |  |
| `house/HouseLife.java:148` | 行为／迁移 | `"house_search_home"` → `Behaviors.HOUSE_SEARCH_HOME` |  |
| `house/HouseLife.java:149` | 行为／迁移 | `"house_scavenge"` → `Behaviors.HOUSE_SCAVENGE` |  |
| `house/HouseLife.java:150` | 行为／迁移 | `"house_sniff"` → `Behaviors.HOUSE_SNIFF` |  |
| `house/HouseLife.java:151` | 行为／迁移 | `"house_hide"` → `Behaviors.HOUSE_HIDE` |  |
| `house/HouseLife.java:152` | 行为／迁移 | `"house_shape"` → `Behaviors.HOUSE_SHAPE` |  |
| `house/HouseLife.java:153` | 行为／迁移 | `"house_scavenge"` → `Behaviors.HOUSE_SCAVENGE` |  |
| `house/HouseLife.java:153` | 行为／迁移 | `"house_carry"` → `Behaviors.HOUSE_CARRY` |  |
| `house/HouseLife.java:182` | 物种／留 R5 | `if (entity.level().isClientSide &#124;&#124; !entity.getVariant().isHouse() &#124;&#124; entity.isNoAi()) return;` → `原样保留` | 整套例程、住所路由、出生方式选择或按物种存读分派；E1 生命周期搬迁时同样不合并这些分支。 |
| `house/HouseLife.java:263` | 物种／留 R5 | `return entity.getVariant().isHouse() && entity.isAlive() && !entity.isNoAi() && !entity.isPassenger()` → `原样保留` | 整套例程、住所路由、出生方式选择或按物种存读分派；E1 生命周期搬迁时同样不合并这些分支。 |
| `house/HouseLife.java:290` | 物种／留 R5 | `e -> e != entity && e.isAlive() && e.getVariant().isHouse() && e.family().adult() && value.getBlockPos().equals(e.house().nestPosition())` → `原样保留` | 整套例程、住所路由、出生方式选择或按物种存读分派；E1 生命周期搬迁时同样不合并这些分支。 |
| `house/HouseLife.java:748` | 物种／留 R5 | `if (entity.level().isClientSide &#124;&#124; !entity.getVariant().isHouse()) return;` → `原样保留` | 整套例程、住所路由、出生方式选择或按物种存读分派；E1 生命周期搬迁时同样不合并这些分支。 |
| `house/HouseLife.java:825` | 物种／留 R5 | `other -> other != entity && other.isAlive() && other.getVariant().isHouse() && from.getBlockPos().equals(other.house().nestPosition())))` → `原样保留` | 整套例程、住所路由、出生方式选择或按物种存读分派；E1 生命周期搬迁时同样不合并这些分支。 |
| `house/HouseNestBlockEntity.java:97` | 物种／迁移 | `entity.getVariant().isHouse()` → `entity.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `house/HouseNestBlockEntity.java:183` | 物种／迁移 | `e.getVariant().isHouse()` → `e.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `house/HouseNestBlockEntity.java:250` | 关系保留／保留个例 | `if((a.family().partner()==null&#124;&#124;a.family().partner().equals(b.getUUID()))&&(b.family().partner()==null&#124;&#124;b.family().partner().equals(a.getUUID()))){` → `原样保留` | 伴侣槽位空缺/已占用或允许同伴侣的条件，不等同于双方已结伴。 |
| `house/HouseNestBlockEntity.java:266` | 身体／迁移 | `entity.body().wound()==0&&entity.body().infection()==0` → `entity.body().condition().readyForHouseCare()` |  |
| `house/HouseNestBlockEntity.java:272` | 物种／迁移 | `e.getVariant().isHouse()` → `e.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `house/HouseholdEcology.java:361` | 物种／迁移 | `e.getVariant().isHouse()` → `e.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `house/HouseholdEcology.java:565` | 物种／迁移 | `e.getVariant().isHouse()` → `e.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `season/AutumnStock.java:42` | 身体／迁移 | `entity.body().wound() == 0 && entity.body().burns() == 0 && entity.body().infection() == 0 && entity.body().mold() == 0` → `entity.body().condition().readyForAutumnStock()` |  |
| `season/WinteringLife.java:38` | 行为／迁移 | `"winter_return"` → `Behaviors.WINTER_RETURN` |  |
| `season/WinteringLife.java:38` | 行为／迁移 | `"winter_stay"` → `Behaviors.WINTER_STAY` |  |
| `season/WinteringLife.java:40` | 物种／迁移 | `entity.getVariant().isPredator()` → `entity.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `season/WinteringLife.java:40` | 物种／迁移 | `entity.getVariant().isHouse()` → `entity.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `season/WinteringLife.java:49` | 物种／迁移 | `entity.getVariant().isPredator()` → `entity.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `season/WinteringLife.java:49` | 物种／迁移 | `entity.getVariant().isHouse()` → `entity.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `settlement/CommonHunt.java:118` | 阈值／迁移 | `selfishness() >= 60` → `selfishness() >= Temperament.SELFISHNESS_AT_60` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `settlement/CommonHunt.java:118` | 阈值／迁移 | `selfControl() < 50` → `selfControl() < Temperament.SELF_CONTROL_AT_50` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `settlement/CommonHunt.java:118` | 阈值／迁移 | `attachment() < 40` → `attachment() < Temperament.ATTACHMENT_AT_40` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `settlement/CommunityRules.java:162` | 阈值／迁移 | `aggression() >= 65` → `aggression() >= Temperament.AGGRESSION_AT_65` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `settlement/CommunityRules.java:163` | 关系/性格／迁移 | `p.selfControl() >= 65 && p.aggression() < 40` → `Temperament.lenient(p)` | 保留 else-if 和 offence.base<=2。 |
| `settlement/CommunityRules.java:231` | 关系／迁移 | `var kin = new ArrayList<UUID>(data.children(offender.getUUID()));         if (person != null) { if (person.mother != null) kin.add(person.mother); if (person.father != null) kin.add(person.father); }         if (offender.family().partner() != null) kin.add(offender.family().partner());` → `var kin = Bonds.exileRelatives(offender);` | 保留接收列表顺序与重复项，后续同群/存活筛选不变；删除不再使用的 data/person 局部变量。 |
| `settlement/CommunityRules.java:263` | 物种／迁移 | `other.getVariant().isPredator()` → `other.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `settlement/CommunityRules.java:263` | 物种／迁移 | `other.getVariant().isHouse()` → `other.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `settlement/CommunityRules.java:285` | 阈值／迁移 | `selfishness() < 60` → `selfishness() < Temperament.SELFISHNESS_AT_60` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `settlement/CommunityRules.java:285` | 阈值／迁移 | `aggression() < 50` → `aggression() < Temperament.AGGRESSION_AT_50` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `settlement/CommunityRules.java:320` | 物种／迁移 | `attacker.getVariant().isPredator()` → `attacker.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `settlement/CommunityRules.java:356` | 关系保留／保留个例 | `var id = entity.family().partner();` → `原样保留` | 身份读取、持久化、展示、收件人遍历或特指母方的条件，不替换为泛化亲属查询。 |
| `settlement/CommunityRules.java:382` | 阈值／迁移 | `selfControl() >= 45` → `selfControl() >= Temperament.SELF_CONTROL_AT_45` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `settlement/CommunityRules.java:382` | 阈值／迁移 | `attachment() >= 65` → `attachment() >= Temperament.ATTACHMENT_AT_65` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `settlement/ForageGoal.java:34` | 物种／迁移 | `entity.getVariant().isHouse()` → `entity.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `settlement/ForageTrip.java:120` | 物种／迁移 | `entity.getVariant().isPredator()` → `entity.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `settlement/ForageTrip.java:120` | 物种／迁移 | `entity.getVariant().isHouse()` → `entity.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `settlement/ForageTrip.java:120` | 物种／迁移 | `HatVessel.aquatic(entity)` → `entity.profile().aquatic()` |  |
| `settlement/HabitatLife.java:99` | 行为／迁移 | `"shelter_gather"` → `Behaviors.SHELTER_GATHER` |  |
| `settlement/HabitatLife.java:99` | 行为／迁移 | `"shelter_clean"` → `Behaviors.SHELTER_CLEAN` |  |
| `settlement/HabitatLife.java:99` | 行为／迁移 | `"shelter_carry"` → `Behaviors.SHELTER_CARRY` |  |
| `settlement/HabitatLife.java:99` | 行为／迁移 | `"shelter_stock"` → `Behaviors.SHELTER_STOCK` |  |
| `settlement/HabitatLife.java:99` | 行为／迁移 | `"nest_harvest"` → `Behaviors.NEST_HARVEST` |  |
| `settlement/HabitatLife.java:99` | 行为／迁移 | `"nest_gather"` → `Behaviors.NEST_GATHER` |  |
| `settlement/HabitatLife.java:99` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `settlement/HabitatLife.java:101` | 行为／迁移 | `"nest_carry"` → `Behaviors.NEST_CARRY` |  |
| `settlement/HabitatLife.java:102` | 行为／迁移 | `"nest_spread"` → `Behaviors.NEST_SPREAD` |  |
| `settlement/HabitatLife.java:102` | 行为／迁移 | `"nest_place"` → `Behaviors.NEST_PLACE` |  |
| `settlement/HabitatLife.java:102` | 行为／迁移 | `"nest_press"` → `Behaviors.NEST_PRESS` |  |
| `settlement/HabitatLife.java:108` | 物种／迁移 | `io.github.longshou.mcyukkuri.subspecies.HatVessel.aquatic(entity)` → `entity.profile().aquatic()` |  |
| `settlement/HabitatLife.java:108` | 物种／迁移 | `entity.getVariant().isPredator()` → `entity.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `settlement/HabitatLife.java:108` | 物种／迁移 | `entity.getVariant().isHouse()` → `entity.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `settlement/HabitatLife.java:124` | 关系保留／保留个例 | `if(entity.family().partner()!=null)count++;` → `原样保留` | 伴侣槽位空缺/已占用或允许同伴侣的条件，不等同于双方已结伴。 |
| `settlement/HabitatLife.java:138` | 物种／迁移 | `entity.getVariant().isPredator()` → `entity.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `settlement/HabitatLife.java:138` | 物种／迁移 | `entity.getVariant().isHouse()` → `entity.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `settlement/HabitatLife.java:162` | 物种／迁移 | `entity.getVariant().isPredator()` → `entity.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `settlement/HabitatLife.java:162` | 物种／迁移 | `entity.getVariant().isHouse()` → `entity.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `settlement/HabitatLife.java:199` | 物种／迁移 | `entity.getVariant().isHouse()` → `entity.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `settlement/HabitatLife.java:231` | 物种／迁移 | `entity.getVariant().isHouse()` → `entity.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `settlement/HabitatLife.java:414` | 物种／迁移 | `entity.getVariant().isPredator()` → `entity.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `settlement/LeaderSuccession.java:63` | 物种／迁移 | `y.getVariant().isPredator()` → `y.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `settlement/LeaderSuccession.java:63` | 物种／迁移 | `y.getVariant().isHouse()` → `y.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `settlement/LeaderSuccession.java:64` | 物种／迁移 | `io.github.longshou.mcyukkuri.subspecies.HatVessel.aquatic(y)` → `y.profile().aquatic()` |  |
| `settlement/LeaderSuccession.java:71` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `settlement/Levy.java:46` | 阈值／迁移 | `selfishness() < 35` → `selfishness() < Temperament.SELFISHNESS_AT_35` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `settlement/Levy.java:46` | 阈值／迁移 | `attachment() >= 60` → `attachment() >= Temperament.ATTACHMENT_AT_60` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `settlement/Levy.java:55` | 阈值／迁移 | `selfishness() >= 65` → `selfishness() >= Temperament.SELFISHNESS_AT_65` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `settlement/Levy.java:55` | 阈值／迁移 | `selfControl() < 50` → `selfControl() < Temperament.SELF_CONTROL_AT_50` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `settlement/Migration.java:75` | 物种／迁移 | `attacker.getVariant().isPredator()` → `attacker.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `settlement/Migration.java:75` | 物种／迁移 | `victim.getVariant().isPredator()` → `victim.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `settlement/Migration.java:90` | 物种／迁移 | `killer.getVariant().isPredator()` → `killer.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `settlement/Migration.java:110` | 物种／迁移 | `caller.getVariant().isPredator()` → `caller.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `settlement/Migration.java:110` | 物种／迁移 | `caller.getVariant().isHouse()` → `caller.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `settlement/Migration.java:136` | 物种／迁移 | `p.getVariant().isPredator()` → `p.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `settlement/Migration.java:139` | 物种／迁移 | `predator.getVariant().isPredator()` → `predator.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `settlement/Migration.java:207` | 物种个例／保留个例 | `if (leader != null && "patchouli".equals(leader.getVariant().dialogueSpecies())) predators *= 1.5f;` → `原样保留` | 具体口音/角色种类，保留 dialogueSpecies，不套生态能力。 |
| `settlement/Migration.java:278` | 阈值／迁移 | `trait(Trait.BOLDNESS) >= 60` → `trait(Trait.BOLDNESS) >= Temperament.BOLDNESS_AT_60` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `settlement/Migration.java:278` | 阈值／迁移 | `attachment() >= 65` → `attachment() >= Temperament.ATTACHMENT_AT_65` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `settlement/Migration.java:352` | 物种／迁移 | `e.getVariant().isPredator()` → `e.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `settlement/Migration.java:352` | 物种／迁移 | `e.getVariant().isHouse()` → `e.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `settlement/Rations.java:83` | 身体／迁移 | `m.body().wound() > 0 &#124;&#124; m.body().infection() > 0` → `m.body().condition().needsExtraRation()` | 先替换最长复合表达式；保留其余健康、饥饿、识别或行动条件。 |
| `settlement/Rations.java:377` | 阈值／迁移 | `selfControl() < 70` → `selfControl() < Temperament.SELF_CONTROL_AT_70` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `settlement/Rations.java:378` | 阈值／迁移 | `selfControl() < 60` → `selfControl() < Temperament.SELF_CONTROL_AT_60` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `settlement/Rations.java:379` | 阈值／迁移 | `selfishness() >= 60` → `selfishness() >= Temperament.SELFISHNESS_AT_60` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `settlement/Rations.java:379` | 阈值／迁移 | `selfControl() < 50` → `selfControl() < Temperament.SELF_CONTROL_AT_50` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `settlement/Seasons.java:54` | 物种／迁移 | `entity.getVariant().isPredator()` → `entity.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `settlement/Seasons.java:54` | 物种／迁移 | `entity.getVariant().isHouse()` → `entity.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `settlement/SettlementDuties.java:31` | 行为／迁移 | `"settlement_withdraw"` → `Behaviors.SETTLEMENT_WITHDRAW` |  |
| `settlement/SettlementDuties.java:31` | 行为／迁移 | `"settlement_clean"` → `Behaviors.SETTLEMENT_CLEAN` |  |
| `settlement/SettlementLife.java:89` | 行为／迁移 | `"settlement_return"` → `Behaviors.SETTLEMENT_RETURN` |  |
| `settlement/SettlementSociety.java:33` | 关系/性格／迁移 | `p.aggression() >= 65 &#124;&#124; p.selfishness() >= 65 && p.selfControl() < 65` → `Temperament.harshForSettlement(p)` | 阈值先保持65。 |
| `settlement/SettlementSociety.java:77` | 阈值／迁移 | `selfControl() >= 50` → `selfControl() >= Temperament.SELF_CONTROL_AT_50` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `settlement/SettlementSociety.java:77` | 阈值／迁移 | `aggression() <= 40` → `aggression() <= Temperament.AGGRESSION_AT_40` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `settlement/SettlementSociety.java:77` | 阈值／迁移 | `attachment() >= 55` → `attachment() >= Temperament.ATTACHMENT_AT_55` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `settlement/SettlementSpawner.java:28` | 物种／迁移 | `seed.getVariant().isHouse()` → `seed.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `settlement/SettlementSpawner.java:77` | 物种个例／保留个例 | `boolean seedLeader = seed != null && seed.getVariant() == YukkuriVariant.PATCHOULI && composition.leader();` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `settlement/SettlementSpawner.java:96` | 物种／迁移 | `io.github.longshou.mcyukkuri.subspecies.HatVessel.aquatic(seed)` → `seed.profile().aquatic()` |  |
| `settlement/SettlementSpawner.java:100` | 物种个例／保留个例 | `YukkuriVariant variant = aquaticFamily ? YukkuriVariant.MARISA : i < household.parents() ? couple ? (i == 0 ? YukkuriVariant.REIMU : family.getFirst().getVariant() == YukkuriVariant.MARISA ? YukkuriVariant.REIMU : YukkuriVariant.MARISA) : COMMON[level.random.nextInt(COMMON.length)]` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `settlement/SettlementSpawner.java:107` | 物种个例／保留个例 | `if (aquaticFamily && member.subspecies().kind() == io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Kind.AQUATIC_MARISA)` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `settlement/SettlementSpawner.java:155` | 物种个例／保留个例 | `if (second != null && first.getVariant() == YukkuriVariant.MARISA && second.getVariant() == YukkuriVariant.REIMU) { var swap = first; first = second; second = swap; }` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `settlement/SettlementSpawner.java:161` | 物种／迁移 | `io.github.longshou.mcyukkuri.subspecies.HatVessel.aquatic(first)` → `first.profile().aquatic()` |  |
| `settlement/ShelterBlockEntity.java:177` | 关系保留／保留个例 | `for(var parent:new UUID[]{entity.family().mother(),entity.family().father()}) {` → `原样保留` | 身份读取、持久化、展示、收件人遍历或特指母方的条件，不替换为泛化亲属查询。 |
| `settlement/ShelterBlockEntity.java:186` | 物种／迁移 | `entity.getVariant().isHouse()` → `entity.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `settlement/ShelterBlockEntity.java:196` | 关系保留／保留个例 | `if(!residents.isEmpty() && residents.keySet().stream().noneMatch(id->id.equals(entity.family().partner())` → `原样保留` | 身份读取、持久化、展示、收件人遍历或特指母方的条件，不替换为泛化亲属查询。 |
| `settlement/ShelterBlockEntity.java:197` | 关系保留／保留个例 | `&#124;&#124;FamilyData.get((ServerLevel)level).socialKin(id,entity.getUUID())&&!(((ServerLevel)level).getEntity(id) instanceof YukkuriEntity res&&!io.github.longshou.mcyukkuri.headwear.Recognition.recognizes(res,entity))))return false;` → `原样保留` | UUID 账本查询或策略快照布尔值；未同时持有双方实体，不为迁移额外解析实体或改变离线亲属语义。 |
| `settlement/ShelterBlockEntity.java:204` | 关系保留／保留个例 | `for(var kin:new UUID[]{entity.family().mother(),entity.family().father(),entity.family().partner()}) {` → `原样保留` | 身份读取、持久化、展示、收件人遍历或特指母方的条件，不替换为泛化亲属查询。 |
| `settlement/ShelterBlockEntity.java:218` | 关系／迁移 | `entity.getUUID().equals(kid.family().mother())&#124;&#124;entity.getUUID().equals(kid.family().father())` → `Bonds.parentOf(entity, kid)` | 只认直接父母，无年龄门槛；不扩大为兄弟姐妹、照护亲属或群体。 |

#### M4

| 位置 | 类别／决定 | 旧表达式 → 新入口 | 边界 |
| --- | --- | --- | --- |
| `badge/BadgeMenu.java:71` | 物种／迁移 | `y.getVariant().isPredator()` → `y.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `human/FactoryBreeding.java:76` | 物种／迁移 | `a.getVariant().isHouse()` → `a.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `human/FactoryBreeding.java:76` | 物种／迁移 | `b.getVariant().isHouse()` → `b.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `human/FactoryBreeding.java:77` | 关系／迁移 | `family.related(a.getUUID(),b.getUUID())` → `Bonds.biological(a, b)` | 只替换既有账本查询；识别、配对兼容、存活与好感条件仍留调用方。 |
| `human/FactoryBreeding.java:78` | 关系保留／保留个例 | `&#124;&#124; a.family().partner()!=null&&!a.family().partner().equals(b.getUUID())` → `原样保留` | 伴侣槽位空缺/已占用或允许同伴侣的条件，不等同于双方已结伴。 |
| `human/FactoryBreeding.java:79` | 关系保留／保留个例 | `&#124;&#124; b.family().partner()!=null&&!b.family().partner().equals(a.getUUID()))continue;` → `原样保留` | 伴侣槽位空缺/已占用或允许同伴侣的条件，不等同于双方已结伴。 |
| `human/FactoryPipeline.java:32` | 身体／迁移 | `y.body().infection()==0 && y.body().mold()==0 && y.body().wound()==0` → `y.body().condition().readyForFactory()` |  |
| `human/FactoryPipeline.java:241` | 身体候选／保留个例 | `tag.putInt("FillingPortions",y.body().filling()); tag.putInt("Burns",y.body().burns());` → `保留原标量读取` | 数值展示、持久化、单项判断或公式；下方指定的合并判断除外。 |
| `human/HumanCommerce.java:89` | 关系保留／保留个例 | `if (!tag.hasUUID("Owner") && animal.family().mother()!=null && level.getEntity(animal.family().mother()) instanceof YukkuriEntity mother) {` → `原样保留` | 身份读取、持久化、展示、收件人遍历或特指母方的条件，不替换为泛化亲属查询。 |
| `human/HumanScenes.java:147` | 物种／迁移 | `v.isPredator()` → `SpeciesProfile.of(v).dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `human/HumanScenes.java:147` | 物种／迁移 | `v.isHouse()` → `SpeciesProfile.of(v).houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `interaction/OperationLife.java:64` | 身体候选／保留个例 | `Math.round(entity.getHealth()),Math.round(entity.life().nutrition()),entity.body().wound(),entity.body().filling()));yield true;` → `保留原标量读取` | 数值展示、持久化、单项判断或公式；下方指定的合并判断除外。 |
| `interaction/OperationLife.java:78` | 关系/性格／迁移 | `entity.life().personality().fear(player.getUUID())<40` → `Bonds.acceptsLift(entity, player.getUUID())` | 没有 trust>=10 门槛，不能套通用 trustedPlayer。 |
| `interaction/OperationLife.java:82` | 阈值／迁移 | `fear(player.getUUID())>=25` → `fear(player.getUUID())>=BondLevels.AFFECTION_FEAR_LIMIT` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `interaction/OperationLife.java:96` | 阈值／迁移 | `trust(player.getUUID())<10` → `trust(player.getUUID())<BondLevels.TRUST_FAMILIAR` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `interaction/OperationLife.java:238` | 表现数值／迁移 | `entity.getVariant().isHouse()?.32f:1` → `entity.profile().modelScale()` |  |
| `interaction/OperationLife.java:254` | 表现数值／迁移 | `entity.getVariant().isHouse()?.32f:1` → `entity.profile().modelScale()` |  |
| `item/CarrierItem.java:61` | 关系/性格／迁移 | `entity.life().personality().trust(id) >= 10 && entity.life().personality().fear(id) < 25` → `Bonds.trustedPlayer(entity, id)` | 主人豁免和 !Owner 分支保持。 |
| `item/NotebookItem.java:111` | 关系保留／保留个例 | `Component.translatable("notebook.mcyukkuri.family",identity(entity.family().mother()),identity(entity.family().father()),` → `原样保留` | 身份读取、持久化、展示、收件人遍历或特指母方的条件，不替换为泛化亲属查询。 |
| `item/NotebookItem.java:112` | 关系保留／保留个例 | `identity(entity.family().partner()),entity.family().brood()==null?0:entity.family().brood().unborn(),entity.family().recovery()/20),` → `原样保留` | 伴侣槽位空缺/已占用或允许同伴侣的条件，不等同于双方已结伴。 |
| `item/NotebookItem.java:115` | 身体候选／保留个例 | `Component.translatable("notebook.mcyukkuri.body",entity.body().wound(),entity.body().infection(),entity.body().filling(),entity.body().mold()),` → `保留原标量读取` | 数值展示、持久化、单项判断或公式；下方指定的合并判断除外。 |
| `item/NotebookItem.java:120` | 物种／迁移 | `io.github.longshou.mcyukkuri.subspecies.HatVessel.aquatic(entity)` → `entity.profile().aquatic()` |  |
| `notebook/NotebookGuide.java:28` | 物种／迁移 | `variant.isPredator()` → `SpeciesProfile.of(variant).dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `notebook/NotebookGuide.java:28` | 物种／迁移 | `variant.isHouse()` → `SpeciesProfile.of(variant).houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `pet/PetCare.java:225` | 阈值／迁移 | `stress() >= 60` → `stress() >= BondLevels.UNEASE_SWEETENED` | 保留 stress() / stressView() 的 Math.round 整数读数；不换为 float unease()；比较方向和其余算术不变。 |
| `pet/PetCare.java:226` | 阈值／迁移 | `stress() <= 30` → `stress() <= BondLevels.UNEASE_LOW_LIMIT` | 保留 stress() / stressView() 的 Math.round 整数读数；不换为 float unease()；比较方向和其余算术不变。 |
| `pet/PetCare.java:234` | 阈值／迁移 | `stress() >= 60` → `stress() >= BondLevels.UNEASE_SWEETENED` | 保留 stress() / stressView() 的 Math.round 整数读数；不换为 float unease()；比较方向和其余算术不变。 |
| `pet/PetLife.java:267` | 阈值／迁移 | `attachment() >= 60` → `attachment() >= Temperament.ATTACHMENT_AT_60` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `pet/PetLife.java:267` | 阈值／迁移 | `selfishness() < 60` → `selfishness() < Temperament.SELFISHNESS_AT_60` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `pet/PetLife.java:284` | 阈值／迁移 | `selfishness() >= 60` → `selfishness() >= Temperament.SELFISHNESS_AT_60` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `pet/PetLife.java:296` | 物种／迁移 | `threat.getVariant().isPredator()` → `threat.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `pet/PetLife.java:382` | 阈值／迁移 | `trait(Trait.BOLDNESS) >= 55` → `trait(Trait.BOLDNESS) >= Temperament.BOLDNESS_AT_55` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `pet/PetLife.java:384` | 关系保留／保留个例 | `boolean kind = personality.trait(Trait.CARE) >= 55 &#124;&#124; animal.family().caresFor(peer) &#124;&#124; world.affinity(animal.getUUID(), peer.getUUID()) >= 10;` → `原样保留` | 既有单向、带成年/幼年门槛的命名查询，不改成 currentCarePair 双向或无年龄 CARE。 同行已明确列为迁移的其他子表达式仍按其记录执行；本条只保留此类别语义。 |
| `pet/PetLife.java:384` | 阈值／迁移 | `affinity(animal.getUUID(), peer.getUUID()) >= 10` → `affinity(animal.getUUID(), peer.getUUID()) >= BondLevels.AFFINITY_CLOSE` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `pet/PetLife.java:384` | 阈值／迁移 | `trait(Trait.CARE) >= 55` → `trait(Trait.CARE) >= Temperament.CARE_AT_55` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `pet/PetRequests.java:85` | 阈值／迁移 | `selfishness() >= 55` → `selfishness() >= Temperament.SELFISHNESS_AT_55` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `pet/PetScenes.java:52` | 阈值／迁移 | `trait(Trait.BOLDNESS) >= 55` → `trait(Trait.BOLDNESS) >= Temperament.BOLDNESS_AT_55` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `pet/PetScenes.java:87` | 阈值／迁移 | `selfControl() >= 50` → `selfControl() >= Temperament.SELF_CONTROL_AT_50` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `pet/WildEncounter.java:48` | 物种／迁移 | `wild.getVariant().isPredator()` → `wild.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `pet/WildEncounter.java:48` | 物种／迁移 | `wild.getVariant().isHouse()` → `wild.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `pet/WildEncounter.java:60` | 物种／迁移 | `v.getVariant().isPredator()` → `v.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `pet/WildEncounter.java:76` | 阈值／迁移 | `trait(Trait.BOLDNESS) >= 55` → `trait(Trait.BOLDNESS) >= Temperament.BOLDNESS_AT_55` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `pet/WildEncounter.java:77` | 阈值／迁移 | `trait(Trait.CARE) >= 55` → `trait(Trait.CARE) >= Temperament.CARE_AT_55` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `pet/exam/ExamSession.java:319` | 阈值／迁移 | `trait(Trait.BOLDNESS) >= 55` → `trait(Trait.BOLDNESS) >= Temperament.BOLDNESS_AT_55` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `pet/exam/ExamSession.java:359` | 阈值／迁移 | `selfControl() <= 40` → `selfControl() <= Temperament.SELF_CONTROL_AT_40` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `pet/exam/ExamSession.java:359` | 阈值／迁移 | `stress() >= 60` → `stress() >= BondLevels.UNEASE_SWEETENED` | 保留 stress() / stressView() 的 Math.round 整数读数；不换为 float unease()；比较方向和其余算术不变。 |
| `pet/exam/ExamSession.java:443` | 阈值／迁移 | `trait(Trait.BOLDNESS) >= 55` → `trait(Trait.BOLDNESS) >= Temperament.BOLDNESS_AT_55` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `pet/exam/ExamSession.java:444` | 阈值／迁移 | `trait(Trait.CARE) >= 55` → `trait(Trait.CARE) >= Temperament.CARE_AT_55` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `pet/social/BadgeArcPolicy.java:44` | 阈值／迁移 | `selfishness() >= 65` → `selfishness() >= Temperament.SELFISHNESS_AT_65` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `pet/social/BadgeArcPolicy.java:45` | 阈值／迁移 | `selfishness() < 50` → `selfishness() < Temperament.SELFISHNESS_AT_50` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `pet/social/BadgeArcPolicy.java:51` | 阈值／迁移 | `affinity() < 0` → `affinity() < BondLevels.AFFINITY_NEUTRAL` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `pet/social/BadgeArcPolicy.java:54` | 阈值／迁移 | `selfishness() >= 65` → `selfishness() >= Temperament.SELFISHNESS_AT_65` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `pet/social/BadgeArcPolicy.java:54` | 阈值／迁移 | `selfControl() < 40` → `selfControl() < Temperament.SELF_CONTROL_AT_40` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `pet/social/BadgeArcPolicy.java:55` | 关系保留／保留个例 | `&& r.seenCount() >= 2 && (r.flags() & (BadgeLedger.ENVIED &#124; BadgeLedger.MOCKED)) != 0 && !r.related())` → `原样保留` | UUID 账本查询或策略快照布尔值；未同时持有双方实体，不为迁移额外解析实体或改变离线亲属语义。 |
| `pet/social/BadgeArcPolicy.java:57` | 关系保留／保留个例 | `if (c.homeClaimEnabled() && !o.pet() && s.pet() && s.homeNear() && o.selfishness() >= 55 && o.aggression() >= 45 && o.nutrition() < 60 && !r.related())` → `原样保留` | UUID 账本查询或策略快照布尔值；未同时持有双方实体，不为迁移额外解析实体或改变离线亲属语义。 同行已明确列为迁移的其他子表达式仍按其记录执行；本条只保留此类别语义。 |
| `pet/social/BadgeArcPolicy.java:57` | 阈值／迁移 | `selfishness() >= 55` → `selfishness() >= Temperament.SELFISHNESS_AT_55` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `pet/social/BadgeArcPolicy.java:57` | 阈值／迁移 | `aggression() >= 45` → `aggression() >= Temperament.AGGRESSION_AT_45` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `pet/social/BadgeArcPolicy.java:59` | 阈值／迁移 | `boldness() < 70` → `boldness() < Temperament.BOLDNESS_AT_70` | 既有快照数值比较，只替换阈值。 |
| `pet/social/BadgeArcPolicy.java:63` | 阈值／迁移 | `attachment() >= 55` → `attachment() >= Temperament.ATTACHMENT_AT_55` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `pet/social/BadgeArcPolicy.java:66` | 关系保留／保留个例 | `if (!o.pet() && grudge && o.aggression() >= 55 && o.selfishness() >= 50 && o.selfControl() < 60 && s.grade() > 0 && !r.related()` → `原样保留` | UUID 账本查询或策略快照布尔值；未同时持有双方实体，不为迁移额外解析实体或改变离线亲属语义。 同行已明确列为迁移的其他子表达式仍按其记录执行；本条只保留此类别语义。 |
| `pet/social/BadgeArcPolicy.java:66` | 阈值／迁移 | `selfishness() >= 50` → `selfishness() >= Temperament.SELFISHNESS_AT_50` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `pet/social/BadgeArcPolicy.java:66` | 阈值／迁移 | `aggression() >= 55` → `aggression() >= Temperament.AGGRESSION_AT_55` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `pet/social/BadgeArcPolicy.java:66` | 阈值／迁移 | `selfControl() < 60` → `selfControl() < Temperament.SELF_CONTROL_AT_60` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `pet/social/BadgeArcPolicy.java:73` | 阈值／迁移 | `stress() >= 50` → `stress() >= BondLevels.UNEASE_CARE_LIMIT` | 保留 stress() / stressView() 的 Math.round 整数读数；不换为 float unease()；比较方向和其余算术不变。 |
| `pet/social/BadgeArcPolicy.java:77` | 阈值／迁移 | `selfishness() < 45` → `selfishness() < Temperament.SELFISHNESS_AT_45` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `pet/social/BadgeArcPolicy.java:78` | 阈值／迁移 | `selfishness() >= 45` → `selfishness() >= Temperament.SELFISHNESS_AT_45` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `pet/social/BadgeArcPolicy.java:78` | 阈值／迁移 | `boldness() >= 55` → `boldness() >= Temperament.BOLDNESS_AT_55` | 既有快照数值比较，只替换阈值。 |
| `pet/social/BadgeArcs.java:121` | 物种个例／保留个例 | `scene.result = variant == null ? "" : variant;` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `pet/social/BadgeArcs.java:151` | 阈值／迁移 | `selfishness() < 45` → `selfishness() < Temperament.SELFISHNESS_AT_45` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `pet/social/BadgeArcs.java:152` | 阈值／迁移 | `trait(Trait.BOLDNESS) >= 55` → `trait(Trait.BOLDNESS) >= Temperament.BOLDNESS_AT_55` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `pet/social/BadgeArcs.java:152` | 阈值／迁移 | `selfishness() >= 50` → `selfishness() >= Temperament.SELFISHNESS_AT_50` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `pet/social/BadgeArcs.java:229` | 阈值／迁移 | `selfishness() >= 65` → `selfishness() >= Temperament.SELFISHNESS_AT_65` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `pet/social/BadgeArcs.java:229` | 阈值／迁移 | `aggression() >= 55` → `aggression() >= Temperament.AGGRESSION_AT_55` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `pet/social/BadgeArcs.java:239` | 阈值／迁移 | `trait(Trait.BOLDNESS) < 40` → `trait(Trait.BOLDNESS) < Temperament.BOLDNESS_AT_40` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `pet/social/BadgeArcs.java:267` | 阈值／迁移 | `selfishness() < 55` → `selfishness() < Temperament.SELFISHNESS_AT_55` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `pet/social/BadgeArcs.java:267` | 阈值／迁移 | `aggression() < 55` → `aggression() < Temperament.AGGRESSION_AT_55` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `pet/social/BadgeArcs.java:268` | 阈值／迁移 | `trait(Trait.BOLDNESS) >= 55` → `trait(Trait.BOLDNESS) >= Temperament.BOLDNESS_AT_55` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `pet/social/BadgeArcs.java:268` | 阈值／迁移 | `aggression() >= 55` → `aggression() >= Temperament.AGGRESSION_AT_55` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `pet/social/BadgeArcs.java:300` | 关系保留／保留个例 | `int score = sub.life().personality().shareScore(sub.life().nutrition(), o.life().nutrition(), sub.family().caresFor(o), world.affinity(sub.getUUID(), o.getUUID()));` → `原样保留` | 既有单向、带成年/幼年门槛的命名查询，不改成 currentCarePair 双向或无年龄 CARE。 |
| `pet/social/BadgeArcs.java:341` | 阈值／迁移 | `trait(Trait.BOLDNESS) >= 55` → `trait(Trait.BOLDNESS) >= Temperament.BOLDNESS_AT_55` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `pet/social/BadgeArcs.java:341` | 阈值／迁移 | `aggression() >= 45` → `aggression() >= Temperament.AGGRESSION_AT_45` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `pet/social/BadgeArcs.java:342` | 阈值／迁移 | `selfControl() >= 50` → `selfControl() >= Temperament.SELF_CONTROL_AT_50` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `pet/social/BadgeArcs.java:352` | 阈值／迁移 | `trait(Trait.BOLDNESS) < 65` → `trait(Trait.BOLDNESS) < Temperament.BOLDNESS_AT_65` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `pet/social/BadgeArcs.java:394` | 阈值／迁移 | `selfishness() >= 60` → `selfishness() >= Temperament.SELFISHNESS_AT_60` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `pet/social/BadgeArcs.java:522` | 表现数值／迁移 | `victim.getVariant().isHouse() ? .32f : 1` → `victim.profile().modelScale()` |  |
| `pet/social/BadgeArcs.java:695` | 阈值／迁移 | `selfishness() >= 60` → `selfishness() >= Temperament.SELFISHNESS_AT_60` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `pet/social/BadgeArcs.java:703` | 阈值／迁移 | `selfishness() >= 60` → `selfishness() >= Temperament.SELFISHNESS_AT_60` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `pet/social/BadgeSocial.java:80` | 物种／迁移 | `o.getVariant().isPredator()` → `o.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `pet/social/BadgeSocial.java:80` | 物种／迁移 | `o.getVariant().isHouse()` → `o.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `pet/social/BadgeSocial.java:90` | 物种／迁移 | `peer.getVariant().isHouse()` → `peer.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `pet/social/BadgeSocial.java:110` | 物种／迁移 | `subject.getVariant().isPredator()` → `subject.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `pet/social/BadgeSocial.java:110` | 物种／迁移 | `subject.getVariant().isHouse()` → `subject.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `pet/social/BadgeSocial.java:115` | 物种／迁移 | `peer.getVariant().isPredator()` → `peer.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `pet/social/BadgeSocial.java:115` | 物种／迁移 | `peer.getVariant().isHouse()` → `peer.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `pet/social/BadgeSocial.java:137` | 关系／迁移 | `FamilyData.get(level).socialKin(o.getUUID(), s.getUUID())` → `Bonds.careKin(o, s)` | 只替换既有账本查询；识别、配对兼容、存活与好感条件仍留调用方。 |
| `play/PlayLife.java:23` | 行为／迁移 | `"slide_climb"` → `Behaviors.SLIDE_CLIMB` |  |
| `play/PlayLife.java:23` | 行为／迁移 | `"slide_down"` → `Behaviors.SLIDE_DOWN` |  |
| `play/PlayLife.java:23` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `play/PlayLife.java:24` | 行为／迁移 | `"trampoline"` → `Behaviors.TRAMPOLINE` |  |
| `play/PlayLife.java:24` | 行为／迁移 | `"pool_edge"` → `Behaviors.POOL_EDGE` |  |
| `play/PlayLife.java:24` | 行为／迁移 | `"tunnel"` → `Behaviors.TUNNEL` |  |
| `play/PlayLife.java:27` | 身体／迁移 | `entity.body().wound()==0` → `entity.body().condition().readyForPlay()` |  |
| `predator/PredatorLife.java:53` | 行为／迁移 | `"predator_takeoff"` → `Behaviors.PREDATOR_TAKEOFF` |  |
| `predator/PredatorLife.java:54` | 行为／迁移 | `"predator_chase"` → `Behaviors.PREDATOR_CHASE` |  |
| `predator/PredatorLife.java:55` | 行为／迁移 | `"predator_dive"` → `Behaviors.PREDATOR_DIVE` |  |
| `predator/PredatorLife.java:56` | 行为／迁移 | `"predator_feed"` → `Behaviors.PREDATOR_FEED` |  |
| `predator/PredatorLife.java:57` | 行为／迁移 | `"predator_return"` → `Behaviors.PREDATOR_RETURN` |  |
| `predator/PredatorLife.java:58` | 行为／迁移 | `"predator_fly"` → `Behaviors.PREDATOR_FLY` |  |
| `predator/PredatorLife.java:59` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `predator/PredatorLife.java:69` | 物种／留 R5 | `return animal.getVariant().isPredator() && animal.pet().effectiveGrade().id() >= io.github.longshou.mcyukkuri.pet.BadgeGrade.SILVER.id()` → `原样保留` | 整套例程、住所路由、出生方式选择或按物种存读分派；E1 生命周期搬迁时同样不合并这些分支。 |
| `predator/PredatorLife.java:85` | 物种／留 R5 | `if (reactWait > 0 &#124;&#124; !animal.getVariant().isPredator() &#124;&#124; active() &#124;&#124; !animal.pet().near(player) &#124;&#124; !HumanCommerce.ownedBy(animal, player.getUUID()) &#124;&#124; animal.pet().busy()` → `原样保留` | 整套例程、住所路由、出生方式选择或按物种存读分派；E1 生命周期搬迁时同样不合并这些分支。 |
| `predator/PredatorLife.java:113` | 物种／留 R5 | `return animal.getVariant().isPredator() && animal.isAlive() && !animal.isNoAi() && !animal.isPassenger()` → `原样保留` | 整套例程、住所路由、出生方式选择或按物种存读分派；E1 生命周期搬迁时同样不合并这些分支。 |
| `predator/PredatorLife.java:136` | 物种／留 R5 | `if (target == animal &#124;&#124; !target.isAlive() &#124;&#124; target.isInvulnerable() &#124;&#124; target.getVariant().isPredator()` → `原样保留` | 整套例程、住所路由、出生方式选择或按物种存读分派；E1 生命周期搬迁时同样不合并这些分支。 |
| `predator/PredatorLife.java:137` | 物种／留 R5 | `&#124;&#124; target.getVariant().isHouse() &#124;&#124; target.isPassenger() &#124;&#124; target.operations().active()` → `原样保留` | 整套例程、住所路由、出生方式选择或按物种存读分派；E1 生命周期搬迁时同样不合并这些分支。 |
| `predator/PredatorLife.java:141` | 关系保留／保留个例 | `if (animal.family().caresFor(target) &#124;&#124; target.getUUID().equals(animal.family().partner())) return false;` → `原样保留` | 既有单向、带成年/幼年门槛的命名查询，不改成 currentCarePair 双向或无年龄 CARE。 |
| `predator/PredatorLife.java:148` | 物种／留 R5 | `if (animal.level().isClientSide &#124;&#124; !animal.getVariant().isPredator()) return;` → `原样保留` | 整套例程、住所路由、出生方式选择或按物种存读分派；E1 生命周期搬迁时同样不合并这些分支。 |
| `predator/PredatorLife.java:172` | 物种／留 R5 | `if (!animal.getVariant().isPredator() &#124;&#124; animal.life().homeStand() != null) return false;` → `原样保留` | 整套例程、住所路由、出生方式选择或按物种存读分派；E1 生命周期搬迁时同样不合并这些分支。 |
| `predator/PredatorLife.java:319` | 物种／留 R5 | `y -> !y.getVariant().isPredator() && y.isAlive() && !y.isNoAi() && y.hasLineOfSight(animal)))` → `原样保留` | 整套例程、住所路由、出生方式选择或按物种存读分派；E1 生命周期搬迁时同样不合并这些分支。 |
| `rare/MyonLife.java:54` | 物种个例／保留个例 | `if (variant == YukkuriVariant.MYON) builder.add(Attributes.MAX_HEALTH, 14.0).add(Attributes.MOVEMENT_SPEED, .20);` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `rare/MyonLife.java:57` | 物种个例／保留个例 | `public static double cognitionScale(YukkuriEntity entity) { return entity.getVariant() == YukkuriVariant.MYON ? COGNITION_SCALE : 1; }` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `rare/MyonLife.java:58` | 物种个例／保留个例 | `public static double forageScale(YukkuriEntity entity) { return entity.getVariant() == YukkuriVariant.MYON ? FORAGE_SCALE : 1; }` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `rare/MyonLife.java:60` | 物种个例／保留个例 | `private boolean myon() { return entity.getVariant() == YukkuriVariant.MYON; }` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `rare/MyonLife.java:146` | 身体候选／保留个例 | `&& entity.family().stage() != io.github.longshou.mcyukkuri.family.FamilyLife.Stage.BABY && !entity.body().dying();` → `保留原标量读取` | 数值展示、持久化、单项判断或公式；下方指定的合并判断除外。 |
| `shop/ShopDate.java:48` | 阈值／迁移 | `affinity(petId, studId) >= 10` → `affinity(petId, studId) >= BondLevels.AFFINITY_CLOSE` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `shop/ShopDate.java:48` | 阈值／迁移 | `affinity(studId, petId) >= 10` → `affinity(studId, petId) >= BondLevels.AFFINITY_CLOSE` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `shop/ShopMenu.java:94` | 阈值／迁移 | `trait(Trait.BOLDNESS) >= 40` → `trait(Trait.BOLDNESS) >= Temperament.BOLDNESS_AT_40` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `shop/ShopMenu.java:124` | 阈值／迁移 | `selfishness() >= 65` → `selfishness() >= Temperament.SELFISHNESS_AT_65` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `shop/ShopMenu.java:124` | 阈值／迁移 | `selfControl() <= 35` → `selfControl() <= Temperament.SELF_CONTROL_AT_35` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `shop/ShopMenu.java:124` | 阈值／迁移 | `selfControl() >= 65` → `selfControl() >= Temperament.SELF_CONTROL_AT_65` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `shop/ShopMenu.java:124` | 阈值／迁移 | `attachment() >= 65` → `attachment() >= Temperament.ATTACHMENT_AT_65` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `shop/ShopMenu.java:125` | 阈值／迁移 | `trait(Trait.CURIOSITY) >= 65` → `trait(Trait.CURIOSITY) >= Temperament.CURIOSITY_AT_65` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `shop/ShopMenu.java:125` | 阈值／迁移 | `trait(Trait.BOLDNESS) <= 35` → `trait(Trait.BOLDNESS) <= Temperament.BOLDNESS_AT_35` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `shop/ShopMenu.java:201` | 阈值／迁移 | `stress() > 50` → `stress() > BondLevels.UNEASE_CARE_LIMIT` | 保留 stress() / stressView() 的 Math.round 整数读数；不换为 float unease()；比较方向和其余算术不变。 |
| `shop/ShopMenu.java:251` | 物种／迁移 | `v.isPredator()` → `SpeciesProfile.of(v).dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `shop/ShopMenu.java:289` | 阈值／迁移 | `trust(p.getUUID()) >= 20` → `trust(p.getUUID()) >= BondLevels.TRUST_AT_20` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `shop/ShopMenu.java:289` | 阈值／迁移 | `attachment() >= 60` → `attachment() >= Temperament.ATTACHMENT_AT_60` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `shop/ShopMenu.java:290` | 阈值／迁移 | `selfishness() >= 60` → `selfishness() >= Temperament.SELFISHNESS_AT_60` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `shop/ShopPerformance.java:50` | 阈值／迁移 | `trait(Trait.BOLDNESS) < 40` → `trait(Trait.BOLDNESS) < Temperament.BOLDNESS_AT_40` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `shop/ShopPricing.java:12` | 物种／迁移 | `v.isPredator()` → `SpeciesProfile.of(v).dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `shop/ShopPricing.java:12` | 物种／迁移 | `v.isHouse()` → `SpeciesProfile.of(v).houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `shop/ShopPricing.java:12` | 物种个例／保留个例 | `int base = v.isPredator() ? 30 : v.isHouse() ? 3 : switch (v) { case ALICE, CHEN, MYON -> 8; case PATCHOULI -> 10; default -> 6; };` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 同行已明确列为迁移的其他子表达式仍按其记录执行；本条只保留此类别语义。 |
| `shop/ShopPricing.java:24` | 阈值／迁移 | `stress() > 60` → `stress() > BondLevels.UNEASE_SWEETENED` | 保留 stress() / stressView() 的 Math.round 整数读数；不换为 float unease()；比较方向和其余算术不变。 |

#### M5

| 位置 | 类别／决定 | 旧表达式 → 新入口 | 边界 |
| --- | --- | --- | --- |
| `body/BodyLife.java:17` | 身体常量／迁移 | `filling=12` → `filling=BodyConstants.MAX_FILLING` |  |
| `body/BodyLife.java:79` | 表现数值／迁移 | `entity.getVariant().isHouse()?.32f:1` → `entity.profile().modelScale()` |  |
| `body/BodyLife.java:95` | 身体常量／迁移 | `filling=12` → `filling=BodyConstants.MAX_FILLING` |  |
| `body/BodyLife.java:159` | 身体常量／迁移 | `Math.clamp(value,0,12)` → `Math.clamp(value,0,BodyConstants.MAX_FILLING)` |  |
| `body/BodyLife.java:273` | 表现数值／迁移 | `entity.getVariant().isHouse()?.35f:1` → `entity.profile().portionScale()` |  |
| `body/BodyLife.java:291` | 表现数值／迁移 | `entity.getVariant().isHouse()?.35f:1` → `entity.profile().portionScale()` |  |
| `body/BodyLife.java:297` | 表现数值／迁移 | `entity.getVariant().isHouse()?.35f:1` → `entity.profile().portionScale()` |  |
| `body/BodyLife.java:304` | 身体常量／迁移 | `(b[4]-b[1])*.22f` → `(b[4]-b[1])*BodyConstants.SOLE_HEIGHT_BAND` |  |
| `body/BodyLife.java:411` | 身体常量／迁移 | `Math.clamp(tag.getInt("Filling"),0,12):12` → `Math.clamp(tag.getInt("Filling"),0,BodyConstants.MAX_FILLING):BodyConstants.MAX_FILLING` |  |
| `body/BodyLife.java:411` | 身体常量／迁移 | `Math.clamp(tag.getInt("Wound"),0,8)` → `Math.clamp(tag.getInt("Wound"),0,BodyConstants.WOUND_CAP)` |  |
| `body/BodyPart.java:15` | 身体常量／迁移 | `.22f` → `BodyConstants.SOLE_HEIGHT_BAND` |  |
| `body/DetachedItems.java:14` | 表现数值／迁移 | `source.getVariant().isHouse()?.32f:1` → `source.profile().modelScale()` |  |
| `body/FillingResource.java:27` | 表现数值／迁移 | `source.getVariant().isHouse()?.32f:1` → `source.profile().modelScale()` |  |
| `body/FillingResource.java:104` | 物种／迁移 | `eater.getVariant().isPredator()` → `eater.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `body/InjuryModel.java:70` | 物种／迁移 | `y.getVariant().isPredator()` → `y.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `body/WoundPlacement.java:33` | 身体候选／保留个例 | `int filling=e.body()!=null&&!e.level().isClientSide?e.body().filling():e.visibleFilling();` → `保留原标量读取` | 数值展示、持久化、单项判断或公式；下方指定的合并判断除外。 |
| `body/WoundPlacement.java:35` | 行为／迁移 | `"sleep_enter"` → `Behaviors.SLEEP_ENTER` |  |
| `body/WoundPlacement.java:35` | 行为／迁移 | `"sleep"` → `Behaviors.SLEEP` |  |
| `body/WoundPlacement.java:35` | 行为／迁移 | `"wake"` → `Behaviors.WAKE` |  |
| `body/WoundPlacement.java:38` | 动画个例／保留个例 | `public static Matrix4f[] pose(YukkuriEntity e){boolean operated=!e.contactPose().equals("NONE")&&!e.contactPose().equals("SLIDE");return WoundSurface.get(e.subspecies().assetId()).pose(operated?"idle":e.getAnimationId(),operated?0:Math.max(0,(e.level().getGameTime()-e.getAnimationStartTick())/20f));}` → `原样保留` | 姿态采样/默认 clip 资源键，使用 getAnimationId 的命名空间，不是行为 ID。 |
| `body/WoundSurface.java:128` | 动画个例／保留个例 | `public Matrix4f[] pose(String action,float elapsed){float[][] values=new float[bones.size()][9];for(var v:values)v[6]=v[7]=v[8]=1;sample(clips.getAsJsonObject("idle"),0,values);` → `原样保留` | 姿态采样/默认 clip 资源键，使用 getAnimationId 的命名空间，不是行为 ID。 |
| `body/WoundSurface.java:129` | 动画个例／保留个例 | `sample(clips.has(action)?clips.getAsJsonObject(action):clips.getAsJsonObject("idle"),elapsed,values);var world=new Matrix4f[bones.size()];` → `原样保留` | 姿态采样/默认 clip 资源键，使用 getAnimationId 的命名空间，不是行为 ID。 |
| `body/WoundSurface.java:137` | 动画个例／保留个例 | `var root=parent<0?new Matrix4f():new Matrix4f(pose("idle",0)[parent]);` → `原样保留` | 姿态采样/默认 clip 资源键，使用 getAnimationId 的命名空间，不是行为 ID。 |
| `command/HumanCommands.java:71` | 物种／迁移 | `v.isPredator()` → `SpeciesProfile.of(v).dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `command/HumanCommands.java:71` | 物种／迁移 | `v.isHouse()` → `SpeciesProfile.of(v).houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `command/HumanCommands.java:84` | 物种／迁移 | `v.isHouse()` → `SpeciesProfile.of(v).houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `command/ScenarioBoats.java:39` | 物种／迁移 | `HatVessel.aquatic(parent)` → `parent.profile().aquatic()` |  |
| `command/ScenarioBoats.java:67` | 物种／迁移 | `HatVessel.aquatic(actor)` → `actor.profile().aquatic()` |  |
| `command/ScenarioBoats.java:87` | 关系保留／保留个例 | `boolean fresh = child.family().mother() == null && child.family().father() == null && child.family().birthId() == null;` → `原样保留` | 身份读取、持久化、展示、收件人遍历或特指母方的条件，不替换为泛化亲属查询。 |
| `command/ScenarioSetup.java:28` | 关系保留／保留个例 | `if (parent.family().caresFor(child)) {` → `原样保留` | 既有单向、带成年/幼年门槛的命名查询，不改成 currentCarePair 双向或无年龄 CARE。 |
| `command/ScenarioSetup.java:35` | 关系保留／保留个例 | `if (child.family().mother() != null &#124;&#124; child.family().father() != null &#124;&#124; child.family().birthId() != null)` → `原样保留` | 身份读取、持久化、展示、收件人遍历或特指母方的条件，不替换为泛化亲属查询。 |
| `command/ScenarioSetup.java:122` | 关系保留／保留个例 | `&#124;&#124; !parent.getUUID().equals(teen.family().mother()) && !parent.getUUID().equals(teen.family().father()))` → `原样保留` | 母/父身份、共享亲本或 UUID 所有者检查；保留原方向，不扩大为照护兄弟姐妹或双向照护。 |
| `life/AgitationLife.java:73` | 行为／迁移 | `"breeding_period"` → `Behaviors.BREEDING_PERIOD` |  |
| `life/AgitationLife.java:74` | 行为／迁移 | `"heat_relief"` → `Behaviors.HEAT_RELIEF` |  |
| `life/AgitationLife.java:75` | 行为／迁移 | `"heat_unmet"` → `Behaviors.HEAT_UNMET` |  |
| `life/AgitationLife.java:76` | 行为／迁移 | `"agitation_recover"` → `Behaviors.AGITATION_RECOVER` |  |
| `life/AgitationLife.java:77` | 物种个例／保留个例 | `case ALICE_WATCH -> "alice_watch";` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 同行已明确列为迁移的其他子表达式仍按其记录执行；本条只保留此类别语义。 |
| `life/AgitationLife.java:77` | 行为／迁移 | `"alice_watch"` → `Behaviors.ALICE_WATCH` |  |
| `life/AgitationLife.java:78` | 物种个例／保留个例 | `case ALICE_APPROACH -> "alice_approach";` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 同行已明确列为迁移的其他子表达式仍按其记录执行；本条只保留此类别语义。 |
| `life/AgitationLife.java:78` | 行为／迁移 | `"alice_approach"` → `Behaviors.ALICE_APPROACH` |  |
| `life/AgitationLife.java:79` | 物种个例／保留个例 | `case ALICE_ACT -> "alice_act";` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 同行已明确列为迁移的其他子表达式仍按其记录执行；本条只保留此类别语义。 |
| `life/AgitationLife.java:79` | 行为／迁移 | `"alice_act"` → `Behaviors.ALICE_ACT` |  |
| `life/AgitationLife.java:80` | 行为／迁移 | `"rapem_held"` → `Behaviors.RAPEM_HELD` |  |
| `life/AgitationLife.java:81` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `life/AgitationLife.java:92` | 物种个例／保留个例 | `case ALICE_WATCH, ALICE_APPROACH -> "open_drool";` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `life/AgitationLife.java:93` | 物种个例／保留个例 | `case ALICE_ACT -> "flushed_tongue";` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `life/AgitationLife.java:110` | 物种个例／保留个例 | `case ALICE_WATCH, ALICE_ACT -> YukkuriAction.ANATOMY_PENI_EXTEND;` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `life/AgitationLife.java:111` | 物种个例／保留个例 | `case ALICE_APPROACH -> YukkuriAction.LOOK_AROUND;` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `life/AgitationLife.java:195` | 物种／迁移 | `entity.getVariant().isPredator()` → `entity.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `life/AgitationLife.java:196` | 物种个例／保留个例 | `&& (!alicePhase() && phase != Phase.ALICE_ACT &#124;&#124; entity.getVariant() == YukkuriVariant.ALICE);` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `life/AgitationLife.java:209` | 身体候选／保留个例 | `&& !entity.body().dying() && entity.body().coreIntact()` → `保留原标量读取` | 数值展示、持久化、单项判断或公式；下方指定的合并判断除外。 |
| `life/AgitationLife.java:214` | 身体／迁移 | `entity.body().wound() == 0 && entity.body().infection() == 0` → `entity.body().condition().readyForAgitation()` |  |
| `life/AgitationLife.java:217` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `life/AgitationLife.java:241` | 物种个例／保留个例 | `if (entity.getVariant() == YukkuriVariant.ALICE && entity.getRandom().nextInt(80) == 0) {` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `life/AgitationLife.java:247` | 关系保留／保留个例 | `&& (FamilyConfig.NATURAL_PAIRING.get() &#124;&#124; entity.family().partner() != null)` → `原样保留` | 伴侣槽位空缺/已占用或允许同伴侣的条件，不等同于双方已结伴。 |
| `life/AgitationLife.java:306` | 物种个例／保留个例 | `case ALICE_WATCH -> {` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `life/AgitationLife.java:315` | 物种个例／保留个例 | `case ALICE_APPROACH -> approach();` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `life/AgitationLife.java:316` | 物种个例／保留个例 | `case ALICE_ACT -> act();` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `life/AgitationLife.java:323` | 身体候选／保留个例 | `&& !other.body().dying() && other.body().coreIntact()` → `保留原标量读取` | 数值展示、持久化、单项判断或公式；下方指定的合并判断除外。 |
| `life/AgitationLife.java:324` | 物种／迁移 | `other.getVariant().isPredator()` → `other.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `life/SpeciesLife.java:104` | 身体候选／保留个例 | `&#124;&#124; entity.getRandom().nextFloat() >= chance * traits().vomit() &#124;&#124; entity.body().filling() <= 3) return false;` → `保留原标量读取` | 数值展示、持久化、单项判断或公式；下方指定的合并判断除外。 |
| `life/SpeciesLife.java:128` | 阈值／迁移 | `unease() >= 60` → `unease() >= BondLevels.UNEASE_SWEETENED` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `life/SpeciesLife.java:166` | 阈值／迁移 | `fear(player.getUUID()) >= 25` → `fear(player.getUUID()) >= BondLevels.AFFECTION_FEAR_LIMIT` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `life/SpeciesLife.java:166` | 阈值／迁移 | `trust(player.getUUID()) < -5` → `trust(player.getUUID()) < BondLevels.TRUST_AT_MINUS_5` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `life/SpeciesLife.java:167` | 阈值／迁移 | `trust(player.getUUID()) >= 10` → `trust(player.getUUID()) >= BondLevels.TRUST_FAMILIAR` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `life/SpeciesLife.java:187` | 关系保留／保留个例 | `if (id.equals(family.mother())) kin = SpeechAddress.Kin.MOTHER;` → `原样保留` | 称谓的母亲/父亲/伴侣/孩子/兄弟姐妹优先级须保留；不是无方向亲属布尔查询。 |
| `life/SpeciesLife.java:188` | 关系保留／保留个例 | `else if (id.equals(family.father())) kin = SpeechAddress.Kin.FATHER;` → `原样保留` | 称谓的母亲/父亲/伴侣/孩子/兄弟姐妹优先级须保留；不是无方向亲属布尔查询。 |
| `life/SpeciesLife.java:189` | 关系保留／保留个例 | `else if (id.equals(family.partner()) &#124;&#124; entity.getUUID().equals(other.family().partner())) kin = SpeechAddress.Kin.PARTNER;` → `原样保留` | 称谓的母亲/父亲/伴侣/孩子/兄弟姐妹优先级须保留；不是无方向亲属布尔查询。 |
| `life/SpeciesLife.java:190` | 关系／迁移 | `entity.getUUID().equals(other.family().mother()) &#124;&#124; entity.getUUID().equals(other.family().father())` → `Bonds.parentOf(entity, other)` | 只认直接父母，无年龄门槛；不扩大为兄弟姐妹、照护亲属或群体。 |
| `life/SpeciesLife.java:192` | 关系保留／保留个例 | `else if (family.mother() != null && family.mother().equals(other.family().mother())` → `原样保留` | 称谓的母亲/父亲/伴侣/孩子/兄弟姐妹优先级须保留；不是无方向亲属布尔查询。 |
| `life/SpeciesLife.java:193` | 关系保留／保留个例 | `&#124;&#124; family.father() != null && family.father().equals(other.family().father()))` → `原样保留` | 称谓的母亲/父亲/伴侣/孩子/兄弟姐妹优先级须保留；不是无方向亲属布尔查询。 |
| `life/SpeciesLife.java:212` | 关系保留／保留个例 | `int children = level.getEntitiesOfClass(YukkuriEntity.class, entity.getBoundingBox().inflate(10), c -> entity.family().caresFor(c)).size();` → `原样保留` | 既有单向、带成年/幼年门槛的命名查询，不改成 currentCarePair 双向或无年龄 CARE。 |
| `life/TemperatureLife.java:49` | 行为／迁移 | `"seek_warmth"` → `Behaviors.SEEK_WARMTH` |  |
| `life/TemperatureLife.java:49` | 行为／迁移 | `"shade_rest"` → `Behaviors.SHADE_REST` |  |
| `life/TemperatureLife.java:49` | 行为／迁移 | `"seek_shade"` → `Behaviors.SEEK_SHADE` |  |
| `life/TemperatureLife.java:49` | 行为／迁移 | `"warm_up"` → `Behaviors.WARM_UP` |  |
| `life/WaterDistress.java:47` | 行为／迁移 | `"water_struggle"` → `Behaviors.WATER_STRUGGLE` |  |
| `life/WaterDistress.java:47` | 行为／迁移 | `"water_impact"` → `Behaviors.WATER_IMPACT` |  |
| `life/WaterDistress.java:47` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `life/WaterDistress.java:48` | 行为／迁移 | `"water_exhausted"` → `Behaviors.WATER_EXHAUSTED` |  |
| `life/WaterDistress.java:48` | 行为／迁移 | `"water_recover"` → `Behaviors.WATER_RECOVER` |  |
| `life/WaterDistress.java:177` | 物种／迁移 | `y.getVariant().isPredator()` → `y.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `life/WaterDistress.java:182` | 关系保留／保留个例 | `boolean sibling=entity.family().mother()!=null&&entity.family().mother().equals(other.family().mother())` → `原样保留` | 身份读取、持久化、展示、收件人遍历或特指母方的条件，不替换为泛化亲属查询。 |
| `life/WaterDistress.java:183` | 关系保留／保留个例 | `&#124;&#124;entity.family().father()!=null&&entity.family().father().equals(other.family().father());` → `原样保留` | 身份读取、持久化、展示、收件人遍历或特指母方的条件，不替换为泛化亲属查询。 |
| `life/WaterDistress.java:184` | 关系保留／保留个例 | `if(entity.family().caresFor(other)&#124;&#124;other.family().caresFor(entity)&#124;&#124;sibling` → `原样保留` | 既有单向、带成年/幼年门槛的命名查询，不改成 currentCarePair 双向或无年龄 CARE。 |
| `life/WaterDistress.java:185` | 阈值／迁移 | `affinity(other.getUUID(),entity.getUUID())>=8` → `affinity(other.getUUID(),entity.getUUID())>=BondLevels.AFFINITY_FAMILIAR` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `life/YukkuriLife.java:226` | 物种／迁移 | `entity.getVariant().isHouse()` → `entity.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `life/YukkuriLife.java:270` | 行为／迁移 | `"receive_mash"` → `Behaviors.RECEIVE_MASH` |  |
| `life/YukkuriLife.java:270` | 行为／迁移 | `"meal_finish"` → `Behaviors.MEAL_FINISH` |  |
| `life/YukkuriLife.java:271` | 行为／迁移 | `"meal_finish"` → `Behaviors.MEAL_FINISH` |  |
| `life/YukkuriLife.java:271` | 行为／迁移 | `"take_food"` → `Behaviors.TAKE_FOOD` |  |
| `life/YukkuriLife.java:271` | 行为／迁移 | `"swallow"` → `Behaviors.SWALLOW` |  |
| `life/YukkuriLife.java:271` | 行为／迁移 | `"chew"` → `Behaviors.CHEW` |  |
| `life/YukkuriLife.java:273` | 行为／迁移 | `"sleep_enter"` → `Behaviors.SLEEP_ENTER` |  |
| `life/YukkuriLife.java:273` | 行为／迁移 | `"sleep"` → `Behaviors.SLEEP` |  |
| `life/YukkuriLife.java:273` | 行为／迁移 | `"wake"` → `Behaviors.WAKE` |  |
| `life/YukkuriLife.java:274` | 行为／迁移 | `"shelter_stock"` → `Behaviors.SHELTER_STOCK` |  |
| `life/YukkuriLife.java:274` | 行为／迁移 | `"return_home"` → `Behaviors.RETURN_HOME` |  |
| `life/YukkuriLife.java:274` | 行为／迁移 | `"seek_toilet"` → `Behaviors.SEEK_TOILET` |  |
| `life/YukkuriLife.java:274` | 行为／迁移 | `"seek_food"` → `Behaviors.SEEK_FOOD` |  |
| `life/YukkuriLife.java:274` | 行为／迁移 | `"toilet"` → `Behaviors.TOILET` |  |
| `life/YukkuriLife.java:275` | 行为／迁移 | `"toilet_accident"` → `Behaviors.TOILET_ACCIDENT` |  |
| `life/YukkuriLife.java:275` | 行为／迁移 | `"ground_toilet"` → `Behaviors.GROUND_TOILET` |  |
| `life/YukkuriLife.java:275` | 行为／迁移 | `"seek_toilet"` → `Behaviors.SEEK_TOILET` |  |
| `life/YukkuriLife.java:276` | 行为／迁移 | `"seek_water"` → `Behaviors.SEEK_WATER` |  |
| `life/YukkuriLife.java:276` | 行为／迁移 | `"drink"` → `Behaviors.DRINK` |  |
| `life/YukkuriLife.java:276` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `life/YukkuriLife.java:288` | 物种／迁移 | `entity.getVariant().isHouse()` → `entity.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `life/YukkuriLife.java:308` | 物种／迁移 | `entity.getVariant().isHouse()` → `entity.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `life/YukkuriLife.java:308` | 物种数值／迁移 | `entity.getVariant().isHouse() ? 4 : 1` → `entity.profile().hungerIntervalMultiplier()` | 只替换括号内 ?4:1；饥饿间隔的乘法顺序与各配置系数保持。 |
| `life/YukkuriLife.java:368` | 身体候选／保留个例 | `entity.gameEvent(GameEvent.EAT); if(!entity.body().sick()&#124;&#124;!(spicy&#124;&#124;toxic>0))entity.showReaction(spicy&#124;&#124;toxic>0?"surprise":"happy", spicy&#124;&#124;toxic>0?"hurt":treat!=null?treat:"fed", 100);` → `保留原标量读取` | 数值展示、持久化、单项判断或公式；下方指定的合并判断除外。 |
| `life/YukkuriLife.java:391` | 关系保留／保留个例 | `if (caregiver == null &#124;&#124; caregiver.level() != entity.level() &#124;&#124; !caregiver.family().caresFor(entity)` → `原样保留` | 既有单向、带成年/幼年门槛的命名查询，不改成 currentCarePair 双向或无年龄 CARE。 |
| `life/YukkuriLife.java:399` | 关系保留／保留个例 | `if (caregiver == null &#124;&#124; caregiver.level() != entity.level() &#124;&#124; !caregiver.family().caresFor(entity)` → `原样保留` | 既有单向、带成年/幼年门槛的命名查询，不改成 currentCarePair 双向或无年龄 CARE。 |
| `life/YukkuriLife.java:443` | 关系保留／保留个例 | `.sorted(Comparator.comparingInt(other->entity.family().caresFor(other)?0:1)).findFirst().orElse(null);` → `原样保留` | 既有单向、带成年/幼年门槛的命名查询，不改成 currentCarePair 双向或无年龄 CARE。 |
| `life/YukkuriLife.java:445` | 关系保留／保留个例 | `int score=personality.shareScore(nutrition,peer.life().nutrition(),entity.family().caresFor(peer),YukkuriWorldData.get(level()).affinity(entity.getUUID(),peer.getUUID()));` → `原样保留` | 既有单向、带成年/幼年门槛的命名查询，不改成 currentCarePair 双向或无年龄 CARE。 |
| `life/YukkuriLife.java:545` | 物种／留 R5 | `if (entity.getVariant().isHouse()) {` → `原样保留` | 整套例程、住所路由、出生方式选择或按物种存读分派；E1 生命周期搬迁时同样不合并这些分支。 |
| `life/YukkuriLife.java:569` | 物种／留 R5 | `&& (!entity.getVariant().isHouse() &#124;&#124; entity.house().homeStand() == null &#124;&#124; energy <= 10)) {` → `原样保留` | 整套例程、住所路由、出生方式选择或按物种存读分派；E1 生命周期搬迁时同样不合并这些分支。 |
| `life/YukkuriLife.java:576` | 物种／留 R5 | `if (targetBlock == null && entity.habitat().vagrant() && !entity.getVariant().isHouse()) targetBlock = nearestDryCover();` → `原样保留` | 整套例程、住所路由、出生方式选择或按物种存读分派；E1 生命周期搬迁时同样不合并这些分支。 |
| `life/YukkuriLife.java:699` | 物种／留 R5 | `&#124;&#124; entity.getVariant().isHouse() && !restTime() && energy >= 70)) sleepTicks = Math.max(sleepTicks, 620);` → `原样保留` | 整套例程、住所路由、出生方式选择或按物种存读分派；E1 生命周期搬迁时同样不合并这些分支。 |
| `life/YukkuriLife.java:819` | 物种／留 R5 | `if (level().getBlockState(selected.pos()).is(io.github.longshou.mcyukkuri.registry.ModBlocks.PREDATOR_NEST.get()) && !entity.getVariant().isPredator()) return false;` → `原样保留` | 整套例程、住所路由、出生方式选择或按物种存读分派；E1 生命周期搬迁时同样不合并这些分支。 |
| `life/YukkuriLife.java:821` | 物种／留 R5 | `if (stand == null &#124;&#124; !(reachable(stand, false) &#124;&#124; entity.getVariant().isPredator() && entity.predator().canFlyTo(stand))` → `原样保留` | 整套例程、住所路由、出生方式选择或按物种存读分派；E1 生命周期搬迁时同样不合并这些分支。 |
| `life/YukkuriLife.java:843` | 物种／留 R5 | `if(entity.getVariant().isHouse()&&entity.house().homeStand()!=null)return entity.house().homeStand();` → `原样保留` | 整套例程、住所路由、出生方式选择或按物种存读分派；E1 生命周期搬迁时同样不合并这些分支。 |
| `life/YukkuriLife.java:851` | 物种／留 R5 | `if (entity.getVariant().isHouse() && entity.house().homeStand() != null) return;` → `原样保留` | 整套例程、住所路由、出生方式选择或按物种存读分派；E1 生命周期搬迁时同样不合并这些分支。 |
| `life/YukkuriLife.java:862` | 物种／留 R5 | `if (entity.getVariant().isPredator()) { if (entity.predator().findNest(false)) return; }` → `原样保留` | 整套例程、住所路由、出生方式选择或按物种存读分派；E1 生命周期搬迁时同样不合并这些分支。 |
| `subspecies/AquaticDailyLife.java:24` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `subspecies/AquaticDailyLife.java:53` | 关系保留／保留个例 | `var mother=person(child.family().mother());var father=person(child.family().father());` → `原样保留` | 身份读取、持久化、展示、收件人遍历或特指母方的条件，不替换为泛化亲属查询。 |
| `subspecies/AquaticDailyLife.java:78` | 物种／迁移 | `HatVessel.aquatic(child)` → `child.profile().aquatic()` |  |
| `subspecies/AquaticDailyLife.java:89` | 行为／迁移 | `"boat_beg"` → `Behaviors.BOAT_BEG` |  |
| `subspecies/AquaticDailyLife.java:94` | 行为／迁移 | `"boat_sleep"` → `Behaviors.BOAT_SLEEP` |  |
| `subspecies/AquaticDailyLife.java:104` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `subspecies/AquaticDailyLife.java:107` | 行为／迁移 | `"boat_drift"` → `Behaviors.BOAT_DRIFT` |  |
| `subspecies/AquaticDailyLife.java:124` | 行为／迁移 | `"boat_drift"` → `Behaviors.BOAT_DRIFT` |  |
| `subspecies/AquaticDailyLife.java:136` | 关系保留／保留个例 | `return child.family().mother()!=null&&child.family().mother().equals(peer.family().mother())` → `原样保留` | 身份读取、持久化、展示、收件人遍历或特指母方的条件，不替换为泛化亲属查询。 |
| `subspecies/AquaticDailyLife.java:137` | 关系保留／保留个例 | `&#124;&#124;child.family().father()!=null&&child.family().father().equals(peer.family().father());` → `原样保留` | 身份读取、持久化、展示、收件人遍历或特指母方的条件，不替换为泛化亲属查询。 |
| `subspecies/AquaticDailyLife.java:158` | 行为／迁移 | `"aquatic_watch_parent"` → `Behaviors.AQUATIC_WATCH_PARENT` |  |
| `subspecies/AquaticDailyLife.java:178` | 行为／迁移 | `"aquatic_answer_family"` → `Behaviors.AQUATIC_ANSWER_FAMILY` |  |
| `subspecies/AquaticDailyLife.java:220` | 行为／迁移 | `"aquatic_paddle_practice"` → `Behaviors.AQUATIC_PADDLE_PRACTICE` |  |
| `subspecies/AquaticDailyLife.java:220` | 行为／迁移 | `"aquatic_imitate_paddle"` → `Behaviors.AQUATIC_IMITATE_PADDLE` |  |
| `subspecies/AquaticDailyLife.java:258` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `subspecies/AquaticFamilyLife.java:34` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `subspecies/AquaticFamilyLife.java:35` | 行为／迁移 | `rescuePhase="idle"` → `rescuePhase=Behaviors.IDLE` | 报警救援行为缺省值保持；不改 phase 时间与优先级。 |
| `subspecies/AquaticFamilyLife.java:59` | 行为／迁移 | `activity.startsWith("boat_rescue_")` → `activity.startsWith(Behaviors.BOAT_RESCUE_PREFIX)` | 行为选择前缀；不影响同名素材前缀。 |
| `subspecies/AquaticFamilyLife.java:59` | 行为／迁移 | `"boat_rescue_"` → `Behaviors.BOAT_RESCUE_PREFIX` |  |
| `subspecies/AquaticFamilyLife.java:60` | 行为／迁移 | `activity.startsWith("boat_rescue_")` → `activity.startsWith(Behaviors.BOAT_RESCUE_PREFIX)` | 行为选择前缀；不影响同名素材前缀。 |
| `subspecies/AquaticFamilyLife.java:60` | 行为／迁移 | `"boat_rescue_"` → `Behaviors.BOAT_RESCUE_PREFIX` |  |
| `subspecies/AquaticFamilyLife.java:64` | 行为／迁移 | `activity.startsWith("boat_rescue_")` → `activity.startsWith(Behaviors.BOAT_RESCUE_PREFIX)` | 行为选择前缀；不影响同名素材前缀。 |
| `subspecies/AquaticFamilyLife.java:64` | 行为／迁移 | `"boat_rescue_"` → `Behaviors.BOAT_RESCUE_PREFIX` |  |
| `subspecies/AquaticFamilyLife.java:69` | 行为／迁移 | `"boat_rescue_approach"` → `Behaviors.BOAT_RESCUE_APPROACH` |  |
| `subspecies/AquaticFamilyLife.java:86` | 物种／迁移 | `HatVessel.aquatic(entity)` → `entity.profile().aquatic()` |  |
| `subspecies/AquaticFamilyLife.java:88` | 关系保留／保留个例 | `&&(entity.family().mother()!=null&#124;&#124;entity.family().father()!=null);` → `原样保留` | 身份读取、持久化、展示、收件人遍历或特指母方的条件，不替换为泛化亲属查询。 |
| `subspecies/AquaticFamilyLife.java:91` | 物种／迁移 | `HatVessel.aquatic(entity)` → `entity.profile().aquatic()` |  |
| `subspecies/AquaticFamilyLife.java:93` | 关系保留／保留个例 | `&&(entity.family().mother()!=null&#124;&#124;entity.family().father()!=null);` → `原样保留` | 身份读取、持久化、展示、收件人遍历或特指母方的条件，不替换为泛化亲属查询。 |
| `subspecies/AquaticFamilyLife.java:112` | 关系保留／保留个例 | `if(fresh)entity.showReaction("teary",entity.family().caresFor(young)?"caregiver":"afraid",45);` → `原样保留` | 既有单向、带成年/幼年门槛的命名查询，不改成 currentCarePair 双向或无年龄 CARE。 |
| `subspecies/AquaticFamilyLife.java:123` | 行为／迁移 | `"boat_crowd_guard"` → `Behaviors.BOAT_CROWD_GUARD` |  |
| `subspecies/AquaticFamilyLife.java:123` | 行为／迁移 | `"boat_reassure"` → `Behaviors.BOAT_REASSURE` |  |
| `subspecies/AquaticFamilyLife.java:134` | 关系／迁移 | `parent.getUUID().equals(entity.family().mother())&#124;&#124;parent.getUUID().equals(entity.family().father())` → `Bonds.parentOf(parent, entity)` | 只认直接父母，无年龄门槛；不扩大为兄弟姐妹、照护亲属或群体。 |
| `subspecies/AquaticFamilyLife.java:144` | 关系保留／保留个例 | `for(var id:new UUID[]{entity.family().mother(),entity.family().father()}){` → `原样保留` | 身份读取、持久化、展示、收件人遍历或特指母方的条件，不替换为泛化亲属查询。 |
| `subspecies/AquaticFamilyLife.java:145` | 物种／迁移 | `HatVessel.aquatic(candidate)` → `candidate.profile().aquatic()` |  |
| `subspecies/AquaticFamilyLife.java:183` | 物种／迁移 | `HatVessel.aquatic(entity)` → `entity.profile().aquatic()` |  |
| `subspecies/AquaticFamilyLife.java:228` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `subspecies/AquaticFamilyLife.java:260` | 行为／迁移 | `"boat_return_family"` → `Behaviors.BOAT_RETURN_FAMILY` |  |
| `subspecies/AquaticFamilyLife.java:267` | 行为／迁移 | `"voyage_prepare"` → `Behaviors.VOYAGE_PREPARE` |  |
| `subspecies/AquaticFamilyLife.java:275` | 行为／迁移 | `"boat_wait_young"` → `Behaviors.BOAT_WAIT_YOUNG` |  |
| `subspecies/AquaticFamilyLife.java:291` | 行为／迁移 | `"boat_wait_young"` → `Behaviors.BOAT_WAIT_YOUNG` |  |
| `subspecies/AquaticFamilyLife.java:307` | 行为／迁移 | `"boat_return_family"` → `Behaviors.BOAT_RETURN_FAMILY` |  |
| `subspecies/AquaticFamilyLife.java:314` | 行为／迁移 | `"boat_return_family"` → `Behaviors.BOAT_RETURN_FAMILY` |  |
| `subspecies/AquaticFamilyLife.java:324` | 行为／迁移 | `"boat_follow_family"` → `Behaviors.BOAT_FOLLOW_FAMILY` |  |
| `subspecies/AquaticFamilyLife.java:328` | 行为／迁移 | `"boat_follow_family"` → `Behaviors.BOAT_FOLLOW_FAMILY` |  |
| `subspecies/AquaticFamilyLife.java:331` | 行为／迁移 | `"boat_wait_young"` → `Behaviors.BOAT_WAIT_YOUNG` |  |
| `subspecies/AquaticFamilyLife.java:334` | 行为／迁移 | `"boat_sleep"` → `Behaviors.BOAT_SLEEP` |  |
| `subspecies/AquaticFamilyLife.java:341` | 行为／迁移 | `"boat_follow_family"` → `Behaviors.BOAT_FOLLOW_FAMILY` |  |
| `subspecies/AquaticFamilyLife.java:378` | 行为／迁移 | `"boat_rescue_young"` → `Behaviors.BOAT_RESCUE_YOUNG` |  |
| `subspecies/AquaticFamilyLife.java:386` | 行为／迁移 | `"boat_return_family"` → `Behaviors.BOAT_RETURN_FAMILY` |  |
| `subspecies/AquaticFamilyLife.java:389` | 行为／迁移 | `"boat_guard_young"` → `Behaviors.BOAT_GUARD_YOUNG` |  |
| `subspecies/AquaticFamilyLife.java:390` | 行为／迁移 | `"boat_rescue_approach"` → `Behaviors.BOAT_RESCUE_APPROACH` |  |
| `subspecies/AquaticFamilyLife.java:390` | 行为／迁移 | `"boat_rescue_young"` → `Behaviors.BOAT_RESCUE_YOUNG` |  |
| `subspecies/AquaticFamilyLife.java:395` | 行为／迁移 | `"boat_guard_young"` → `Behaviors.BOAT_GUARD_YOUNG` |  |
| `subspecies/AquaticFamilyLife.java:396` | 行为／迁移 | `"boat_guard_young"` → `Behaviors.BOAT_GUARD_YOUNG` |  |
| `subspecies/AquaticFamilyLife.java:398` | 行为／迁移 | `"boat_return_family"` → `Behaviors.BOAT_RETURN_FAMILY` |  |
| `subspecies/AquaticFamilyLife.java:400` | 行为／迁移 | `"boat_wait_young"` → `Behaviors.BOAT_WAIT_YOUNG` |  |
| `subspecies/AquaticFamilyLife.java:401` | 行为／迁移 | `"boat_wait_young"` → `Behaviors.BOAT_WAIT_YOUNG` |  |
| `subspecies/AquaticFamilyLife.java:413` | 行为／迁移 | `"boat_wait_young"` → `Behaviors.BOAT_WAIT_YOUNG` |  |
| `subspecies/AquaticFamilyLife.java:415` | 行为／迁移 | `"boat_sleep"` → `Behaviors.BOAT_SLEEP` |  |
| `subspecies/AquaticFamilyLife.java:433` | 行为／迁移 | `"boat_return_family"` → `Behaviors.BOAT_RETURN_FAMILY` |  |
| `subspecies/AquaticFamilyLife.java:445` | 关系保留／保留个例 | `&#124;&#124;!home.protects(entity)&#124;&#124;!entity.family().caresFor(child)&#124;&#124;child.family().flying()` → `原样保留` | 既有单向、带成年/幼年门槛的命名查询，不改成 currentCarePair 双向或无年龄 CARE。 |
| `subspecies/AquaticFamilyLife.java:459` | 行为／迁移 | `"boat_wait_young"` → `Behaviors.BOAT_WAIT_YOUNG` |  |
| `subspecies/AquaticFamilyLife.java:463` | 行为／迁移 | `"boat_return_family"` → `Behaviors.BOAT_RETURN_FAMILY` |  |
| `subspecies/AquaticFamilyLife.java:466` | 行为／迁移 | `"boat_guard_young"` → `Behaviors.BOAT_GUARD_YOUNG` |  |
| `subspecies/AquaticFamilyLife.java:467` | 行为／迁移 | `"boat_return_family"` → `Behaviors.BOAT_RETURN_FAMILY` |  |
| `subspecies/AquaticFamilyLife.java:494` | 关系保留／保留个例 | `y->entity.family().caresFor(y)))result.put(child.getUUID(),child);` → `原样保留` | 既有单向、带成年/幼年门槛的命名查询，不改成 currentCarePair 双向或无年龄 CARE。 |
| `subspecies/AquaticFamilyLife.java:504` | 关系保留／保留个例 | `&&(entity.family().mother()!=null&#124;&#124;entity.family().father()!=null);` → `原样保留` | 身份读取、持久化、展示、收件人遍历或特指母方的条件，不替换为泛化亲属查询。 |
| `subspecies/AquaticFamilyLife.java:509` | 关系保留／保留个例 | `var mate=person(entity.getUUID().equals(child.family().mother())?child.family().father():child.family().mother());` → `原样保留` | 母/父身份、共享亲本或 UUID 所有者检查；保留原方向，不扩大为照护兄弟姐妹或双向照护。 |
| `subspecies/AquaticFamilyLife.java:560` | 物种／迁移 | `other.getVariant().isPredator()` → `other.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `subspecies/AquaticFamilyLife.java:568` | 行为／迁移 | `"boat_wait_young"` → `Behaviors.BOAT_WAIT_YOUNG` |  |
| `subspecies/AquaticFamilyLife.java:570` | 行为／迁移 | `"boat_wait_young"` → `Behaviors.BOAT_WAIT_YOUNG` |  |
| `subspecies/AquaticFamilyLife.java:587` | 行为／迁移 | `"boat_wait_young"` → `Behaviors.BOAT_WAIT_YOUNG` |  |
| `subspecies/AquaticFamilyLife.java:598` | 行为／迁移 | `"boat_follow_family"` → `Behaviors.BOAT_FOLLOW_FAMILY` |  |
| `subspecies/AquaticFamilyLife.java:612` | 行为／迁移 | `"boat_follow_family"` → `Behaviors.BOAT_FOLLOW_FAMILY` |  |
| `subspecies/AquaticFamilyLife.java:619` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `subspecies/AquaticFamilyLife.java:623` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `subspecies/AquaticLife.java:41` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `subspecies/AquaticLife.java:52` | 行为／迁移 | `activity.equals("idle")` → `activity.equals(Behaviors.IDLE)` | 只替换行为 ID 或其构造；保留规范化位置、大小写、短路和原前后缀。 |
| `subspecies/AquaticLife.java:63` | 行为／迁移 | `"boat_lullaby".equals(activity)` → `Behaviors.BOAT_LULLABY.equals(activity)` | 只替换行为 ID 或其构造；保留规范化位置、大小写、短路和原前后缀。 |
| `subspecies/AquaticLife.java:63` | 行为／迁移 | `"boat_sleep".equals(activity)` → `Behaviors.BOAT_SLEEP.equals(activity)` | 只替换行为 ID 或其构造；保留规范化位置、大小写、短路和原前后缀。 |
| `subspecies/AquaticLife.java:69` | 物种／迁移 | `HatVessel.aquatic(entity)` → `entity.profile().aquatic()` |  |
| `subspecies/AquaticLife.java:78` | 物种／迁移 | `HatVessel.aquatic(entity)` → `entity.profile().aquatic()` |  |
| `subspecies/AquaticLife.java:82` | 物种／迁移 | `HatVessel.aquatic(entity)` → `entity.profile().aquatic()` |  |
| `subspecies/AquaticLife.java:117` | 物种／迁移 | `HatVessel.aquatic(entity)` → `entity.profile().aquatic()` |  |
| `subspecies/AquaticLife.java:138` | 关系／迁移 | `entity.getUUID().equals(y.family().mother()) &#124;&#124; entity.getUUID().equals(y.family().father())` → `Bonds.parentOf(entity, y)` | 只认直接父母，无年龄门槛；不扩大为兄弟姐妹、照护亲属或群体。 |
| `subspecies/AquaticLife.java:141` | 物种／迁移 | `HatVessel.aquatic(entity)` → `entity.profile().aquatic()` |  |
| `subspecies/AquaticLife.java:170` | 关系保留／保留个例 | `for(var id:new java.util.UUID[]{entity.family().mother(),entity.family().father()}){` → `原样保留` | 身份读取、持久化、展示、收件人遍历或特指母方的条件，不替换为泛化亲属查询。 |
| `subspecies/AquaticLife.java:189` | 物种／迁移 | `HatVessel.aquatic(entity)` → `entity.profile().aquatic()` |  |
| `subspecies/AquaticLife.java:236` | 行为／迁移 | `"boat_land"` → `Behaviors.BOAT_LAND` |  |
| `subspecies/AquaticLife.java:240` | 行为／迁移 | `"boat_launch"` → `Behaviors.BOAT_LAUNCH` |  |
| `subspecies/AquaticLife.java:256` | 行为／迁移 | `"aquatic_hat_care"` → `Behaviors.AQUATIC_HAT_CARE` |  |
| `subspecies/AquaticLife.java:261` | 行为／迁移 | `"boat_find_paddle"` → `Behaviors.BOAT_FIND_PADDLE` |  |
| `subspecies/AquaticLife.java:263` | 行为／迁移 | `"boat_provision"` → `Behaviors.BOAT_PROVISION` |  |
| `subspecies/AquaticLife.java:265` | 行为／迁移 | `"boat_seek_bank"` → `Behaviors.BOAT_SEEK_BANK` |  |
| `subspecies/AquaticLife.java:274` | 行为／迁移 | `"voyage_wait_parent"` → `Behaviors.VOYAGE_WAIT_PARENT` |  |
| `subspecies/AquaticLife.java:282` | 行为／迁移 | `"boat_wait_young"` → `Behaviors.BOAT_WAIT_YOUNG` |  |
| `subspecies/AquaticLife.java:291` | 行为／迁移 | `"boat_launch"` → `Behaviors.BOAT_LAUNCH` |  |
| `subspecies/AquaticLife.java:295` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `subspecies/AquaticLife.java:303` | 关系／迁移 | `entity.getUUID().equals(y.family().mother()) &#124;&#124; entity.getUUID().equals(y.family().father())` → `Bonds.parentOf(entity, y)` | 只认直接父母，无年龄门槛；不扩大为兄弟姐妹、照护亲属或群体。 |
| `subspecies/AquaticLife.java:397` | 行为／迁移 | `"boat_drift"` → `Behaviors.BOAT_DRIFT` |  |
| `subspecies/AquaticLife.java:401` | 行为／迁移 | `"boat_capsizing"` → `Behaviors.BOAT_CAPSIZING` |  |
| `subspecies/AquaticLife.java:401` | 行为／迁移 | `"boat_steady"` → `Behaviors.BOAT_STEADY` |  |
| `subspecies/AquaticLife.java:401` | 行为／迁移 | `"boat_rocked"` → `Behaviors.BOAT_ROCKED` |  |
| `subspecies/AquaticLife.java:423` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` | 幼体 response 行为仲裁，保持优先级与短路。 |
| `subspecies/AquaticLife.java:424` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` | 幼体 response 行为仲裁，保持优先级与短路。 |
| `subspecies/AquaticLife.java:425` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` | 幼体 response 行为仲裁，保持优先级与短路。 |
| `subspecies/AquaticLife.java:430` | 行为／迁移 | `"first_toilet_stimulated"` → `Behaviors.FIRST_TOILET_STIMULATED` | 幼体 response 行为仲裁，保持优先级与短路。 |
| `subspecies/AquaticLife.java:430` | 行为／迁移 | `"first_toilet_wait"` → `Behaviors.FIRST_TOILET_WAIT` | 幼体 response 行为仲裁，保持优先级与短路。 |
| `subspecies/AquaticLife.java:431` | 行为／迁移 | `"boat_toilet"` → `Behaviors.BOAT_TOILET` | 幼体 response 行为仲裁，保持优先级与短路。 |
| `subspecies/AquaticLife.java:431` | 行为／迁移 | `"hat_walk"` → `Behaviors.HAT_WALK` | 幼体 response 行为仲裁，保持优先级与短路。 |
| `subspecies/AquaticLife.java:431` | 行为／迁移 | `"boat_eat"` → `Behaviors.BOAT_EAT` | 幼体 response 行为仲裁，保持优先级与短路。 |
| `subspecies/AquaticLife.java:442` | 行为／迁移 | `"boat_toilet"` → `Behaviors.BOAT_TOILET` |  |
| `subspecies/AquaticLife.java:442` | 行为／迁移 | `"hat_walk"` → `Behaviors.HAT_WALK` |  |
| `subspecies/AquaticLife.java:451` | 行为／迁移 | `"boat_sleep"` → `Behaviors.BOAT_SLEEP` |  |
| `subspecies/AquaticLife.java:451` | 行为／迁移 | `"hat_cradle"` → `Behaviors.HAT_CRADLE` |  |
| `subspecies/AquaticLife.java:451` | 行为／迁移 | `"hat_walk"` → `Behaviors.HAT_WALK` |  |
| `subspecies/AquaticLife.java:451` | 行为／迁移 | `"hat_brim"` → `Behaviors.HAT_BRIM` |  |
| `subspecies/AquaticLife.java:470` | 行为／迁移 | `"boat_rescue_young"` → `Behaviors.BOAT_RESCUE_YOUNG` |  |
| `subspecies/AquaticLife.java:474` | 行为／迁移 | `"boat_eat"` → `Behaviors.BOAT_EAT` |  |
| `subspecies/AquaticLife.java:477` | 行为／迁移 | `"boat_drink"` → `Behaviors.BOAT_DRINK` |  |
| `subspecies/AquaticLife.java:484` | 行为／迁移 | `"boat_eat"` → `Behaviors.BOAT_EAT` |  |
| `subspecies/AquaticLife.java:489` | 物种／迁移 | `HatVessel.aquatic(entity)` → `entity.profile().aquatic()` |  |
| `subspecies/AquaticLife.java:496` | 行为／迁移 | `activity.equals("boat_wait_young")` → `activity.equals(Behaviors.BOAT_WAIT_YOUNG)` | 只替换行为 ID 或其构造；保留规范化位置、大小写、短路和原前后缀。 |
| `subspecies/AquaticLife.java:496` | 行为／迁移 | `activity.equals("boat_sleep")` → `activity.equals(Behaviors.BOAT_SLEEP)` | 只替换行为 ID 或其构造；保留规范化位置、大小写、短路和原前后缀。 |
| `subspecies/AquaticLife.java:509` | 行为／迁移 | `"boat_crowd_guard"` → `Behaviors.BOAT_CROWD_GUARD` |  |
| `subspecies/AquaticLife.java:517` | 行为／迁移 | `"boat_steady"` → `Behaviors.BOAT_STEADY` |  |
| `subspecies/AquaticLife.java:526` | 行为／迁移 | `"boat_wait_young"` → `Behaviors.BOAT_WAIT_YOUNG` |  |
| `subspecies/AquaticLife.java:530` | 行为／迁移 | `"boat_toilet"` → `Behaviors.BOAT_TOILET` |  |
| `subspecies/AquaticLife.java:532` | 行为／迁移 | `"boat_eat"` → `Behaviors.BOAT_EAT` |  |
| `subspecies/AquaticLife.java:541` | 行为／迁移 | `"boat_steady"` → `Behaviors.BOAT_STEADY` |  |
| `subspecies/AquaticLife.java:543` | 行为／迁移 | `"boat_toilet"` → `Behaviors.BOAT_TOILET` |  |
| `subspecies/AquaticLife.java:543` | 行为／迁移 | `"hat_walk"` → `Behaviors.HAT_WALK` |  |
| `subspecies/AquaticLife.java:546` | 行为／迁移 | `"boat_paddle"` → `Behaviors.BOAT_PADDLE` |  |
| `subspecies/AquaticLife.java:548` | 物种／迁移 | `HatVessel.aquatic(entity)` → `entity.profile().aquatic()` |  |
| `subspecies/AquaticLife.java:552` | 行为／迁移 | `"boat_steady"` → `Behaviors.BOAT_STEADY` |  |
| `subspecies/AquaticLife.java:552` | 行为／迁移 | `"boat_call"` → `Behaviors.BOAT_CALL` |  |
| `subspecies/AquaticLife.java:556` | 行为／迁移 | `"boat_sleep"` → `Behaviors.BOAT_SLEEP` |  |
| `subspecies/AquaticLife.java:565` | 行为／迁移 | `"boat_drift"` → `Behaviors.BOAT_DRIFT` |  |
| `subspecies/AquaticLife.java:568` | 行为／迁移 | `"boat_eat"` → `Behaviors.BOAT_EAT` |  |
| `subspecies/AquaticLife.java:574` | 行为／迁移 | `"boat_gather"` → `Behaviors.BOAT_GATHER` |  |
| `subspecies/AquaticLife.java:584` | 行为／迁移 | `"boat_seek_rest"` → `Behaviors.BOAT_SEEK_REST` |  |
| `subspecies/AquaticLife.java:588` | 行为／迁移 | `"boat_steady"` → `Behaviors.BOAT_STEADY` |  |
| `subspecies/AquaticLife.java:592` | 行为／迁移 | `"boat_sleep"` → `Behaviors.BOAT_SLEEP` |  |
| `subspecies/AquaticLife.java:602` | 行为／迁移 | `"boat_drift"` → `Behaviors.BOAT_DRIFT` |  |
| `subspecies/AquaticLife.java:606` | 行为／迁移 | `"boat_sleep"` → `Behaviors.BOAT_SLEEP` |  |
| `subspecies/AquaticLife.java:610` | 行为／迁移 | `"boat_paddle"` → `Behaviors.BOAT_PADDLE` |  |
| `subspecies/AquaticLife.java:619` | 行为／迁移 | `"boat_paddle"` → `Behaviors.BOAT_PADDLE` |  |
| `subspecies/AquaticLife.java:619` | 行为／迁移 | `"boat_drift"` → `Behaviors.BOAT_DRIFT` |  |
| `subspecies/AquaticLife.java:653` | 行为／迁移 | `"boat_land"` → `Behaviors.BOAT_LAND` |  |
| `subspecies/AquaticLife.java:668` | 行为／迁移 | `"boat_call"` → `Behaviors.BOAT_CALL` |  |
| `subspecies/AquaticLife.java:711` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `subspecies/AquaticSelfRescue.java:42` | 行为／迁移 | `"water_hat_remove"` → `Behaviors.WATER_HAT_REMOVE` |  |
| `subspecies/AquaticSelfRescue.java:42` | 行为／迁移 | `"water_hat_right"` → `Behaviors.WATER_HAT_RIGHT` |  |
| `subspecies/AquaticSelfRescue.java:42` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `subspecies/AquaticSelfRescue.java:43` | 行为／迁移 | `"water_hat_reach"` → `Behaviors.WATER_HAT_REACH` |  |
| `subspecies/AquaticSelfRescue.java:43` | 行为／迁移 | `"water_hat_climb"` → `Behaviors.WATER_HAT_CLIMB` |  |
| `subspecies/AquaticSelfRescue.java:43` | 行为／迁移 | `"water_hat_grip"` → `Behaviors.WATER_HAT_GRIP` |  |
| `subspecies/AquaticSelfRescue.java:44` | 行为／迁移 | `"water_hat_slip"` → `Behaviors.WATER_HAT_SLIP` |  |
| `subspecies/AquaticSelfRescue.java:44` | 行为／迁移 | `"water_hat_safe"` → `Behaviors.WATER_HAT_SAFE` |  |
| `subspecies/AquaticSelfRescue.java:47` | 物种／迁移 | `HatVessel.aquatic(actor)` → `actor.profile().aquatic()` |  |
| `subspecies/AquaticShore.java:26` | 物种／迁移 | `HatVessel.aquatic(entity)` → `entity.profile().aquatic()` |  |
| `subspecies/AquaticShore.java:43` | 物种／迁移 | `HatVessel.aquatic(entity)` → `entity.profile().aquatic()` |  |
| `subspecies/FirstVoyage.java:40` | 物种／迁移 | `HatVessel.aquatic(child)` → `child.profile().aquatic()` |  |
| `subspecies/FirstVoyage.java:41` | 关系保留／保留个例 | `public boolean needsLesson(){return !child.family().adult()&&!completed&&!independent&&(child.family().mother()!=null&#124;&#124;child.family().father()!=null);}` → `原样保留` | 身份读取、持久化、展示、收件人遍历或特指母方的条件，不替换为泛化亲属查询。 |
| `subspecies/FirstVoyage.java:70` | 行为／迁移 | `"voyage_praise"` → `Behaviors.VOYAGE_PRAISE` |  |
| `subspecies/FirstVoyage.java:70` | 行为／迁移 | `"voyage_watch"` → `Behaviors.VOYAGE_WATCH` |  |
| `subspecies/FirstVoyage.java:72` | 行为／迁移 | `"voyage_prepare"` → `Behaviors.VOYAGE_PREPARE` |  |
| `subspecies/FirstVoyage.java:72` | 行为／迁移 | `"voyage_launch"` → `Behaviors.VOYAGE_LAUNCH` |  |
| `subspecies/FirstVoyage.java:72` | 行为／迁移 | `"voyage_board"` → `Behaviors.VOYAGE_BOARD` |  |
| `subspecies/FirstVoyage.java:73` | 行为／迁移 | `"voyage_balance"` → `Behaviors.VOYAGE_BALANCE` |  |
| `subspecies/FirstVoyage.java:73` | 行为／迁移 | `"voyage_return"` → `Behaviors.VOYAGE_RETURN` |  |
| `subspecies/FirstVoyage.java:73` | 行为／迁移 | `"voyage_out"` → `Behaviors.VOYAGE_OUT` |  |
| `subspecies/FirstVoyage.java:74` | 行为／迁移 | `"voyage_proud"` → `Behaviors.VOYAGE_PROUD` |  |
| `subspecies/FirstVoyage.java:74` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `subspecies/FirstVoyage.java:113` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `subspecies/FirstVoyage.java:115` | 关系保留／保留个例 | `&#124;&#124;!(boat.ownerYukkuri().equals(child.family().father())&#124;&#124;boat.ownerYukkuri().equals(child.family().mother()))&#124;&#124;boat.hasIncoming())return false;` → `原样保留` | 母/父身份、共享亲本或 UUID 所有者检查；保留原方向，不扩大为照护兄弟姐妹或双向照护。 |
| `subspecies/FirstVoyage.java:119` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `subspecies/FirstVoyage.java:133` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `subspecies/FirstVoyage.java:136` | 关系保留／保留个例 | `for(var id:new UUID[]{child.family().mother(),child.family().father()}){` → `原样保留` | 身份读取、持久化、展示、收件人遍历或特指母方的条件，不替换为泛化亲属查询。 |
| `subspecies/FirstVoyage.java:140` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `subspecies/HatBoatCargo.java:35` | 行为／迁移 | `"boat_retrieve_food"` → `Behaviors.BOAT_RETRIEVE_FOOD` |  |
| `subspecies/HatBoatCargo.java:35` | 行为／迁移 | `"boat_stow_food"` → `Behaviors.BOAT_STOW_FOOD` |  |
| `subspecies/HatBoatCargo.java:145` | 关系保留／保留个例 | `&#124;&#124; !boat.protects(parent) &#124;&#124; !boat.protects(child) &#124;&#124; !parent.family().caresFor(child)` → `原样保留` | 既有单向、带成年/幼年门槛的命名查询，不改成 currentCarePair 双向或无年龄 CARE。 |
| `subspecies/HatBoatControls.java:17` | 物种／迁移 | `HatVessel.aquatic(y)` → `y.profile().aquatic()` |  |
| `subspecies/HatBoatControls.java:24` | 物种／迁移 | `HatVessel.aquatic(y)` → `y.profile().aquatic()` |  |
| `subspecies/HatBoatControls.java:33` | 物种／迁移 | `HatVessel.aquatic(y)` → `y.profile().aquatic()` |  |
| `subspecies/HatBoatControls.java:41` | 物种／迁移 | `HatVessel.aquatic(y)` → `y.profile().aquatic()` |  |
| `subspecies/HatBoatCrowding.java:50` | 行为／迁移 | `"boat_crowd_guard"` → `Behaviors.BOAT_CROWD_GUARD` |  |
| `subspecies/HatBoatCrowding.java:50` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `subspecies/HatBoatCrowding.java:51` | 行为／迁移 | `"hat_slipping"` → `Behaviors.HAT_SLIPPING` |  |
| `subspecies/HatBoatCrowding.java:52` | 行为／迁移 | `"hat_jostle"` → `Behaviors.HAT_JOSTLE` |  |
| `subspecies/HatBoatCrowding.java:53` | 行为／迁移 | `"hat_scramble"` → `Behaviors.HAT_SCRAMBLE` |  |
| `subspecies/HatBoatCrowding.java:54` | 行为／迁移 | `"hat_stumble"` → `Behaviors.HAT_STUMBLE` |  |
| `subspecies/HatBoatCrowding.java:54` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `subspecies/HatBoatCrowding.java:84` | 物种／迁移 | `other.getVariant().isPredator()` → `other.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `subspecies/HatBoatEntity.java:530` | 关系保留／保留个例 | `return ownerYukkuri() != null && (ownerYukkuri().equals(y.family().mother()) &#124;&#124; ownerYukkuri().equals(y.family().father())) && !y.family().adult();` → `原样保留` | 母/父身份、共享亲本或 UUID 所有者检查；保留原方向，不扩大为照护兄弟姐妹或双向照护。 |
| `subspecies/HatBoatEntity.java:1877` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `subspecies/HatBoatEntity.java:1878` | 行为／迁移 | `"boat_carry_young"` → `Behaviors.BOAT_CARRY_YOUNG` |  |
| `subspecies/HatBoatEntity.java:1879` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `subspecies/HatBoatEntity.java:1880` | 行为／迁移 | `"boat_prepare_carry"` → `Behaviors.BOAT_PREPARE_CARRY` |  |
| `subspecies/HatBoatEntity.java:1880` | 行为／迁移 | `"boat_release_carry"` → `Behaviors.BOAT_RELEASE_CARRY` |  |
| `subspecies/HatBoatEntity.java:1880` | 行为／迁移 | `"boat_be_carried"` → `Behaviors.BOAT_BE_CARRIED` |  |
| `subspecies/HatBoatEntity.java:1880` | 行为／迁移 | `"hat_walk"` → `Behaviors.HAT_WALK` |  |
| `subspecies/HatBoatEntity.java:2273` | 关系保留／保留个例 | `&#124;&#124; !(home.ownerYukkuri().equals(child.family().father()) &#124;&#124; home.ownerYukkuri().equals(child.family().mother()))` → `原样保留` | 母/父身份、共享亲本或 UUID 所有者检查；保留原方向，不扩大为照护兄弟姐妹或双向照护。 |
| `subspecies/HatBoatEntity.java:2279` | 物种个例／保留个例 | `if (!(owner.level() instanceof ServerLevel level) &#124;&#124; owner.getVariant() != YukkuriVariant.MARISA` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `subspecies/HatBoatEntity.java:2280` | 物种个例／保留个例 | `&#124;&#124; owner.subspecies().kind() != SubspeciesLife.Kind.AQUATIC_MARISA &#124;&#124; !owner.aquaticLife().firstVoyage().mayLaunch()` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `subspecies/HatBoatEntity.java:2373` | 物种个例／保留个例 | `if (!(owner.level() instanceof ServerLevel level) &#124;&#124; owner.getVariant() != YukkuriVariant.MARISA &#124;&#124; !owner.isAlive()` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `subspecies/HatBoatEntity.java:2643` | 物种／迁移 | `HatVessel.aquatic(actor)` → `actor.profile().aquatic()` |  |
| `subspecies/HatBoatEntity.java:2671` | 物种／迁移 | `HatVessel.aquatic(actor)` → `actor.profile().aquatic()` |  |
| `subspecies/HatCareAnchor.java:59` | 动画个例／保留个例 | `Vec3 parentMouth=posed(parent,"boat_carry_young"), childMouth=posed(child,"boat_be_carried");` → `原样保留` | shape/posed/action 参数是姿态 clip ID，不因与行为字符串同名而迁移。 |
| `subspecies/HatCareGeometry.java:145` | 动画个例／保留个例 | `Shape p=shape(parent,"boat_carry_young",0,unit,yaw),c=shape(child,"boat_be_carried",0,unit,yaw+Math.PI);` → `原样保留` | shape/posed/action 参数是姿态 clip ID，不因与行为字符串同名而迁移。 |
| `subspecies/HatCareGeometry.java:197` | 动画个例／保留个例 | `if(s==null){s=shape(child,release?"boat_release_carry":"boat_prepare_carry",index*.05,unit,yaw);transition[row][index]=s;}` → `原样保留` | shape/posed/action 参数是姿态 clip ID，不因与行为字符串同名而迁移。 |
| `subspecies/HatCareGeometry.java:214` | 动画个例／保留个例 | `var source=action.equals("boat_carry_young")&&original.parent!=null?original.parent:original;` → `原样保留` | shape/posed/action 参数是姿态 clip ID，不因与行为字符串同名而迁移。 |
| `subspecies/HatFamilyAlarm.java:27` | 行为／迁移 | `phase="idle"` → `phase=Behaviors.IDLE` | 原存档键/值和缺省行为保持，仅命名字面量。 |
| `subspecies/HatFamilyAlarm.java:68` | 行为／迁移 | `set("boat_alarm_call")` → `set(Behaviors.BOAT_ALARM_CALL)` | 报警 phase 的行为值；同一行 stageReaction/speak/showReaction 对白键保持。 |
| `subspecies/HatFamilyAlarm.java:90` | 行为／迁移 | `set("idle")` → `set(Behaviors.IDLE)` | 报警 phase 的行为值；同一行 stageReaction/speak/showReaction 对白键保持。 |
| `subspecies/HatFamilyAlarm.java:101` | 行为／迁移 | `set("boat_return_called")` → `set(Behaviors.BOAT_RETURN_CALLED)` | 报警 phase 的行为值；同一行 stageReaction/speak/showReaction 对白键保持。 |
| `subspecies/HatFamilyAlarm.java:103` | 行为／迁移 | `phase.equals("boat_return_called")` → `phase.equals(Behaviors.BOAT_RETURN_CALLED)` | 报警 phase 的行为值；同一行 stageReaction/speak/showReaction 对白键保持。 |
| `subspecies/HatFamilyAlarm.java:123` | 行为／迁移 | `set("boat_answer_call")` → `set(Behaviors.BOAT_ANSWER_CALL)` | 报警 phase 的行为值；同一行 stageReaction/speak/showReaction 对白键保持。 |
| `subspecies/HatFamilyAlarm.java:128` | 行为／迁移 | `set("idle")` → `set(Behaviors.IDLE)` | 报警 phase 的行为值；同一行 stageReaction/speak/showReaction 对白键保持。 |
| `subspecies/HatFamilyAlarm.java:139` | 行为／迁移 | `parentAlarm.activity().equals("boat_calm")` → `parentAlarm.activity().equals(Behaviors.BOAT_CALM)` | 报警 phase 的行为值；同一行 stageReaction/speak/showReaction 对白键保持。 |
| `subspecies/HatFamilyAlarm.java:140` | 行为／迁移 | `phase.equals("boat_calm")` → `phase.equals(Behaviors.BOAT_CALM)` | 报警 phase 的行为值；同一行 stageReaction/speak/showReaction 对白键保持。 |
| `subspecies/HatFamilyAlarm.java:140` | 行为／迁移 | `set("boat_calm")` → `set(Behaviors.BOAT_CALM)` | 报警 phase 的行为值；同一行 stageReaction/speak/showReaction 对白键保持。 |
| `subspecies/HatFamilyAlarm.java:142` | 行为／迁移 | `set("boat_reassure")` → `set(Behaviors.BOAT_REASSURE)` | 报警 phase 的行为值；同一行 stageReaction/speak/showReaction 对白键保持。 |
| `subspecies/HatFamilyAlarm.java:143` | 行为／迁移 | `parentAlarm.activity().equals("boat_flee")` → `parentAlarm.activity().equals(Behaviors.BOAT_FLEE)` | 报警 phase 的行为值；同一行 stageReaction/speak/showReaction 对白键保持。 |
| `subspecies/HatFamilyAlarm.java:199` | 行为／迁移 | `set("boat_rescue_young")` → `set(Behaviors.BOAT_RESCUE_YOUNG)` | 报警 phase 的行为值；同一行 stageReaction/speak/showReaction 对白键保持。 |
| `subspecies/HatFamilyAlarm.java:199` | 行为／迁移 | `set("boat_alarm_wait")` → `set(Behaviors.BOAT_ALARM_WAIT)` | 报警 phase 的行为值；同一行 stageReaction/speak/showReaction 对白键保持。 |
| `subspecies/HatFamilyAlarm.java:219` | 行为／迁移 | `set("boat_alarm_wait")` → `set(Behaviors.BOAT_ALARM_WAIT)` | 报警 phase 的行为值；同一行 stageReaction/speak/showReaction 对白键保持。 |
| `subspecies/HatFamilyAlarm.java:221` | 行为／迁移 | `phase.equals("boat_calm")` → `phase.equals(Behaviors.BOAT_CALM)` | 报警 phase 的行为值；同一行 stageReaction/speak/showReaction 对白键保持。 |
| `subspecies/HatFamilyAlarm.java:221` | 行为／迁移 | `set("boat_calm")` → `set(Behaviors.BOAT_CALM)` | 报警 phase 的行为值；同一行 stageReaction/speak/showReaction 对白键保持。 |
| `subspecies/HatFamilyAlarm.java:226` | 行为／迁移 | `set("boat_alarm_wait")` → `set(Behaviors.BOAT_ALARM_WAIT)` | 报警 phase 的行为值；同一行 stageReaction/speak/showReaction 对白键保持。 |
| `subspecies/HatFamilyAlarm.java:236` | 行为／迁移 | `phase.equals("boat_flee")` → `phase.equals(Behaviors.BOAT_FLEE)` | 报警 phase 的行为值；同一行 stageReaction/speak/showReaction 对白键保持。 |
| `subspecies/HatFamilyAlarm.java:236` | 行为／迁移 | `set("boat_flee")` → `set(Behaviors.BOAT_FLEE)` | 报警 phase 的行为值；同一行 stageReaction/speak/showReaction 对白键保持。 |
| `subspecies/HatFamilyAlarm.java:239` | 行为／迁移 | `set("boat_alarm_wait")` → `set(Behaviors.BOAT_ALARM_WAIT)` | 报警 phase 的行为值；同一行 stageReaction/speak/showReaction 对白键保持。 |
| `subspecies/HatFamilyAlarm.java:296` | 行为／迁移 | `set("boat_rescue_young")` → `set(Behaviors.BOAT_RESCUE_YOUNG)` | 报警 phase 的行为值；同一行 stageReaction/speak/showReaction 对白键保持。 |
| `subspecies/HatFamilyAlarm.java:297` | 行为／迁移 | `phase.equals("boat_rescue_approach")` → `phase.equals(Behaviors.BOAT_RESCUE_APPROACH)` | 报警 phase 的行为值；同一行 stageReaction/speak/showReaction 对白键保持。 |
| `subspecies/HatFamilyAlarm.java:298` | 行为／迁移 | `set("boat_rescue_approach")` → `set(Behaviors.BOAT_RESCUE_APPROACH)` | 报警 phase 的行为值；同一行 stageReaction/speak/showReaction 对白键保持。 |
| `subspecies/HatFamilyAlarm.java:309` | 行为／迁移 | `t.getString("Phase"):"idle"` → `t.getString("Phase"):Behaviors.IDLE` | 原存档键/值和缺省行为保持，仅命名字面量。 |
| `subspecies/HatFamilyCare.java:47` | 行为／迁移 | `"boat_carry_young"` → `Behaviors.BOAT_CARRY_YOUNG` |  |
| `subspecies/HatFamilyCare.java:48` | 行为／迁移 | `"hat_walk"` → `Behaviors.HAT_WALK` |  |
| `subspecies/HatFamilyCare.java:49` | 行为／迁移 | `"care_finish"` → `Behaviors.CARE_FINISH` |  |
| `subspecies/HatFamilyCare.java:51` | 行为／迁移 | `"boat_feed_young"` → `Behaviors.BOAT_FEED_YOUNG` |  |
| `subspecies/HatFamilyCare.java:51` | 行为／迁移 | `"family_chew"` → `Behaviors.FAMILY_CHEW` |  |
| `subspecies/HatFamilyCare.java:52` | 行为／迁移 | `"first_toilet_assist"` → `Behaviors.FIRST_TOILET_ASSIST` |  |
| `subspecies/HatFamilyCare.java:52` | 行为／迁移 | `"first_toilet_seek"` → `Behaviors.FIRST_TOILET_SEEK` |  |
| `subspecies/HatFamilyCare.java:52` | 行为／迁移 | `"care_finish"` → `Behaviors.CARE_FINISH` |  |
| `subspecies/HatFamilyCare.java:53` | 行为／迁移 | `"boat_nuzzle"` → `Behaviors.BOAT_NUZZLE` |  |
| `subspecies/HatFamilyCare.java:54` | 行为／迁移 | `"boat_lullaby"` → `Behaviors.BOAT_LULLABY` |  |
| `subspecies/HatFamilyCare.java:54` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `subspecies/HatFamilyCare.java:57` | 行为／迁移 | `"boat_be_carried"` → `Behaviors.BOAT_BE_CARRIED` | response 返回值流入 AquaticLife.activity 与实体显示行为。 |
| `subspecies/HatFamilyCare.java:58` | 行为／迁移 | `"hat_walk"` → `Behaviors.HAT_WALK` | response 返回值流入 AquaticLife.activity 与实体显示行为。 |
| `subspecies/HatFamilyCare.java:59` | 行为／迁移 | `"boat_listen"` → `Behaviors.BOAT_LISTEN` | response 返回值流入 AquaticLife.activity 与实体显示行为。 |
| `subspecies/HatFamilyCare.java:60` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` | response 返回值流入 AquaticLife.activity 与实体显示行为。 |
| `subspecies/HatFamilyCare.java:61` | 行为／迁移 | `"boat_receive_care"` → `Behaviors.BOAT_RECEIVE_CARE` | response 返回值流入 AquaticLife.activity 与实体显示行为。 |
| `subspecies/HatFamilyCare.java:61` | 行为／迁移 | `"boat_eat"` → `Behaviors.BOAT_EAT` | response 返回值流入 AquaticLife.activity 与实体显示行为。 |
| `subspecies/HatFamilyCare.java:63` | 行为／迁移 | `"boat_beg"` → `Behaviors.BOAT_BEG` | response 返回值流入 AquaticLife.activity 与实体显示行为。 |
| `subspecies/HatFamilyCare.java:64` | 行为／迁移 | `"first_toilet_stimulated"` → `Behaviors.FIRST_TOILET_STIMULATED` | response 返回值流入 AquaticLife.activity 与实体显示行为。 |
| `subspecies/HatFamilyCare.java:64` | 行为／迁移 | `"first_toilet_wait"` → `Behaviors.FIRST_TOILET_WAIT` | response 返回值流入 AquaticLife.activity 与实体显示行为。 |
| `subspecies/HatFamilyCare.java:64` | 行为／迁移 | `"boat_toilet"` → `Behaviors.BOAT_TOILET` | response 返回值流入 AquaticLife.activity 与实体显示行为。 |
| `subspecies/HatFamilyCare.java:65` | 行为／迁移 | `"boat_receive_care"` → `Behaviors.BOAT_RECEIVE_CARE` | response 返回值流入 AquaticLife.activity 与实体显示行为。 |
| `subspecies/HatFamilyCare.java:66` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` | response 返回值流入 AquaticLife.activity 与实体显示行为。 |
| `subspecies/HatFamilyPaddle.java:47` | 行为／迁移 | `"boat_receive_paddle"` → `Behaviors.BOAT_RECEIVE_PADDLE` | response 返回值流入 AquaticLife.activity 与实体显示行为。 |
| `subspecies/HatFamilyPaddle.java:47` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` | response 返回值流入 AquaticLife.activity 与实体显示行为。 |
| `subspecies/HatFamilyPaddle.java:49` | 行为／迁移 | `"boat_give_paddle"` → `Behaviors.BOAT_GIVE_PADDLE` |  |
| `subspecies/HatFamilyPaddle.java:49` | 行为／迁移 | `"boat_gather"` → `Behaviors.BOAT_GATHER` |  |
| `subspecies/HatFamilyPaddle.java:49` | 行为／迁移 | `"boat_paddle"` → `Behaviors.BOAT_PADDLE` |  |
| `subspecies/HatFamilyPaddle.java:73` | 物种／迁移 | `HatVessel.aquatic(owner)` → `owner.profile().aquatic()` |  |
| `subspecies/HatFamilyPaddle.java:84` | 物种／迁移 | `other.getVariant().isPredator()` → `other.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `subspecies/HatShoreForage.java:34` | 行为／迁移 | `"boat_gather"` → `Behaviors.BOAT_GATHER` |  |
| `subspecies/HatShoreForage.java:34` | 行为／迁移 | `"boat_paddle"` → `Behaviors.BOAT_PADDLE` |  |
| `subspecies/HatShoreForage.java:43` | 物种／迁移 | `HatVessel.aquatic(owner)` → `owner.profile().aquatic()` |  |
| `subspecies/HatVessel.java:70` | 物种个例／保留个例 | `public static boolean aquatic(YukkuriEntity y){return y.subspecies().kind()==SubspeciesLife.Kind.AQUATIC_MARISA;}` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `subspecies/HatVessel.java:264` | 物种个例／保留个例 | `boolean hat=child.getVariant()==io.github.longshou.mcyukkuri.entity.YukkuriVariant.MARISA&&child.life().hasAccessory();` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `subspecies/ShellRetraction.java:19` | 物种个例／保留个例 | `return entity.subspecies().kind() == SubspeciesLife.Kind.SHELL_MARISA && entity.life().hasAccessory();` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `subspecies/ShellRetraction.java:35` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `subspecies/SubspeciesLife.java:23` | 物种个例／保留个例 | `public boolean fits(YukkuriVariant species) { return variant == null &#124;&#124; variant == species; }` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `subspecies/SubspeciesLife.java:65` | 物种个例／保留个例 | `if((flags&DEIBU_FLAG)!=0&&variant==YukkuriVariant.REIMU&&((flags>>4)&15)==0)` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `subspecies/SubspeciesLife.java:67` | 物种个例／保留个例 | `if ((flags&15)==Kind.SHELL_MARISA.ordinal() && variant==YukkuriVariant.MARISA) return "marisa_tsumuri";` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `subspecies/SubspeciesLife.java:68` | 物种个例／保留个例 | `if ((flags&15)==Kind.WASA_REIMU.ordinal() && variant==YukkuriVariant.REIMU) return "reimu_wasa";` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `subspecies/SubspeciesLife.java:69` | 物种个例／保留个例 | `if ((flags&15)==Kind.KOTATSU_MARISA.ordinal() && variant==YukkuriVariant.MARISA) return "marisa_kotatsu";` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `subspecies/SubspeciesLife.java:70` | 物种个例／保留个例 | `if ((flags&15)==Kind.LONGTAIL_CHEN.ordinal() && variant==YukkuriVariant.CHEN) return "chen_longtail";` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `subspecies/SubspeciesLife.java:71` | 物种个例／保留个例 | `if ((flags&15)==Kind.ROUNDTAIL_CHEN.ordinal() && variant==YukkuriVariant.CHEN) return "chen_roundtail";` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `subspecies/SubspeciesLife.java:72` | 物种个例／保留个例 | `if ((flags&15)==Kind.REDEYE_MYON.ordinal() && variant==YukkuriVariant.MYON) return "myon_redeye";` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `subspecies/SubspeciesLife.java:74` | 物种个例／保留个例 | `if (variant == YukkuriVariant.REIMU &#124;&#124; variant == YukkuriVariant.MARISA) return variant.assetId() + suffix;` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `subspecies/SubspeciesLife.java:80` | 物种个例／保留个例 | `if (shape != Congenital.NORMAL && entity.getVariant() != YukkuriVariant.REIMU && entity.getVariant() != YukkuriVariant.MARISA) return false;` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `subspecies/SubspeciesLife.java:89` | 物种个例／保留个例 | `if (kind == Kind.NORMAL && congenital == Congenital.NORMAL && entity.getVariant() == YukkuriVariant.MARISA && nearOpenWater()` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `subspecies/SubspeciesLife.java:108` | 物种个例／保留个例 | `var candidate=variant==YukkuriVariant.MARISA?Kind.SHELL_MARISA:variant==YukkuriVariant.REIMU?Kind.WASA_REIMU:Kind.NORMAL;` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `subspecies/SubspeciesLife.java:112` | 物种个例／保留个例 | `if(selected==Kind.NORMAL&&variant==YukkuriVariant.MARISA&&(parentA==Kind.AQUATIC_MARISA&#124;&#124;parentB==Kind.AQUATIC_MARISA)&&random.nextInt(2)==0)selected=Kind.AQUATIC_MARISA;` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `subspecies/SubspeciesLife.java:113` | 物种个例／保留个例 | `if(selected==Kind.NORMAL&&(variant==YukkuriVariant.REIMU&#124;&#124;variant==YukkuriVariant.MARISA)&&random.nextInt(200)==0)shape=1+random.nextInt(3);` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `subspecies/SubspeciesLife.java:120` | 物种个例／保留个例 | `case MARISA -> { if(random.nextInt(parentA==Kind.KOTATSU_MARISA&#124;&#124;parentB==Kind.KOTATSU_MARISA?3:120)==0)return Kind.KOTATSU_MARISA; }` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `subspecies/SubspeciesLife.java:121` | 物种个例／保留个例 | `case CHEN -> {` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `subspecies/SubspeciesLife.java:125` | 物种个例／保留个例 | `case MYON -> { if(random.nextInt(parentA==Kind.REDEYE_MYON&#124;&#124;parentB==Kind.REDEYE_MYON?4:40)==0)return Kind.REDEYE_MYON; }` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `subspecies/SubspeciesLife.java:163` | 物种个例／保留个例 | `if (kind != Kind.NORMAL &#124;&#124; entity.getVariant() != YukkuriVariant.REIMU && entity.getVariant() != YukkuriVariant.MARISA) congenital = Congenital.NORMAL;` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `subspecies/SubspeciesLife.java:168` | 物种个例／保留个例 | `deibu=tag.getBoolean("Deibu")&&entity.getVariant()==YukkuriVariant.REIMU&&congenital==Congenital.NORMAL;` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `subspecies/SubspeciesLife.java:175` | 物种个例／保留个例 | `boolean qualified=entity.getVariant()==YukkuriVariant.REIMU&&congenital==Congenital.NORMAL&&entity.family().adult()` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |

#### M6

| 位置 | 类别／决定 | 旧表达式 → 新入口 | 边界 |
| --- | --- | --- | --- |
| `client/AgitationPresentation.java:32` | 视图入口／迁移 | `private static final Map<YukkuriEntity, DisplayedPose> displayed = new WeakHashMap<>();` → `private static final Map<YukkuriView, DisplayedPose> displayed = new WeakHashMap<>();` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/AgitationPresentation.java:37` | 视图入口／迁移 | `final WeakReference<YukkuriEntity> owner;` → `final WeakReference<YukkuriView> owner;` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/AgitationPresentation.java:44` | 视图入口／迁移 | `DisplayedPose(YukkuriEntity entity, String phase, long start, double now, float[] from) {` → `DisplayedPose(YukkuriView entity, String phase, long start, double now, float[] from) {` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/AgitationPresentation.java:116` | 视图入口／迁移 | `static boolean selected(YukkuriEntity entity) {` → `static boolean selected(YukkuriView entity) {` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/AgitationPresentation.java:122` | 视图入口／迁移 | `private static boolean matchesPresentation(YukkuriEntity entity) {` → `private static boolean matchesPresentation(YukkuriView entity) {` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/AgitationPresentation.java:130` | 行为／迁移 | `"breeding_period"` → `Behaviors.BREEDING_PERIOD` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/AgitationPresentation.java:131` | 行为／迁移 | `"heat_relief"` → `Behaviors.HEAT_RELIEF` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/AgitationPresentation.java:132` | 行为／迁移 | `"heat_unmet"` → `Behaviors.HEAT_UNMET` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/AgitationPresentation.java:133` | 行为／迁移 | `"agitation_recover"` → `Behaviors.AGITATION_RECOVER` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/AgitationPresentation.java:134` | 行为／迁移 | `"alice_watch"` → `Behaviors.ALICE_WATCH` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/AgitationPresentation.java:135` | 行为／迁移 | `"alice_approach"` → `Behaviors.ALICE_APPROACH` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/AgitationPresentation.java:136` | 行为／迁移 | `"alice_act"` → `Behaviors.ALICE_ACT` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/AgitationPresentation.java:137` | 行为／迁移 | `"rapem_held"` → `Behaviors.RAPEM_HELD` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/AgitationPresentation.java:144` | 视图入口／迁移 | `static String expression(YukkuriEntity entity, String otherwise) {` → `static String expression(YukkuriView entity, String otherwise) {` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/AgitationPresentation.java:150` | 视图读取／迁移 | `entity.subspecies().assetId()` → `entity.assetId()` |  |
| `client/AgitationPresentation.java:162` | 视图读取／迁移 | `entity.subspecies().assetId()` → `entity.assetId()` |  |
| `client/AgitationPresentation.java:168` | 行为／迁移 | `behavior.equals("tend_brood")` → `behavior.equals(Behaviors.TEND_BROOD)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/AgitationPresentation.java:168` | 行为／迁移 | `behavior.equals("idle")` → `behavior.equals(Behaviors.IDLE)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/AgitationPresentation.java:169` | 同名显示字符串／保留个例 | `"idle"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/AgitationPresentation.java:175` | 视图入口／迁移 | `static void pose(YukkuriEntity entity, float partialTick, PoseStack pose) {` → `static void pose(YukkuriView entity, float partialTick, PoseStack pose) {` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/AgitationPresentation.java:229` | 视图读取／迁移 | `entity.subspecies().assetId()` → `entity.assetId()` |  |
| `client/AgitationPresentation.java:239` | 视图入口／迁移 | `private static void prune(YukkuriEntity entity, long tick) {` → `private static void prune(YukkuriView entity, long tick) {` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/AquaticMotionPresentation.java:10` | 视图入口／迁移 | `static CabinDistressPresentation.Motion motion(YukkuriEntity entity,float partial){` → `static CabinDistressPresentation.Motion motion(YukkuriView entity,float partial){` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/AquaticMotionPresentation.java:12` | 视图入口／迁移 | `if(!(entity.getVehicle() instanceof HatBoatEntity boat))return distress;` → `改用 vehicleYukkuri()/vehicleHat() 或 yukkuriView(id)，显式 null guard 后沿用原局部变量` | 查找与乘骑的具体对应见 A.2；保持原 && / || 短路和局部变量作用域。 |
| `client/AquaticMotionPresentation.java:13` | 帽船视图／迁移 | `boat.transportingInHat(entity)` → `boat.visibleTransporting(entity.getUUID())` |  |
| `client/AquaticMotionPresentation.java:14` | 帽船视图／迁移 | `boat.carryActivity(entity)` → `boat.visibleCarryActivity(entity.getUUID())` |  |
| `client/AquaticMotionPresentation.java:14` | 帽船视图／迁移 | `boat.carrySeconds(partial)` → `boat.visibleCarrySeconds(partial)` |  |
| `client/AquaticMotionPresentation.java:17` | 行为／迁移 | `case "boat_alarm_wait","boat_alarm_call","boat_answer_call","boat_calm"` → `case Behaviors.BOAT_ALARM_WAIT,Behaviors.BOAT_ALARM_CALL,Behaviors.BOAT_ANSWER_CALL,Behaviors.BOAT_CALM` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/AquaticMotionPresentation.java:20` | 同名显示字符串／保留个例 | `"boat_paddle"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/AquaticMotionPresentation.java:22` | 帽船视图／迁移 | `boat.cargo().paddle()` → `boat.visiblePaddle()` |  |
| `client/AquaticMotionPresentation.java:25` | 同名显示字符串／保留个例 | `"boat_paddle"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/AquaticMotionPresentation.java:28` | 同名显示字符串／保留个例 | `"boat_paddle"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 同行已明确列为迁移的其他子表达式仍按其记录执行；本条只保留此类别语义。 |
| `client/AquaticMotionPresentation.java:28` | 帽船视图／迁移 | `boat.drifting()` → `boat.visibleDrifting()` |  |
| `client/AquaticMotionPresentation.java:30` | 同名显示字符串／保留个例 | `"boat_steady"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/AquaticMotionPresentation.java:30` | 同名显示字符串／保留个例 | `"hat_cradle"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/AquaticMotionPresentation.java:36` | 视图入口／迁移 | `static void recoverPaddle(PoseStack pose,HatBoatEntity boat,float partial){` → `static void recoverPaddle(PoseStack pose,HatBoatView boat,float partial){` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/BraidRescuePresentation.java:14` | 视图入口／迁移 | `static boolean active(YukkuriEntity entity){` → `static boolean active(YukkuriView entity){` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/BraidRescuePresentation.java:20` | 视图入口／迁移 | `static boolean parent(YukkuriEntity entity){return active(entity)&&entity.braidRescueState().getInt("ParentId")==entity.getId();}` → `static boolean parent(YukkuriView entity){return active(entity)&&entity.braidRescueState().getInt("ParentId")==entity.getId();}` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/BraidRescuePresentation.java:21` | 视图入口／迁移 | `static boolean child(YukkuriEntity entity){return active(entity)&&entity.braidRescueState().getInt("ChildId")==entity.getId();}` → `static boolean child(YukkuriView entity){return active(entity)&&entity.braidRescueState().getInt("ChildId")==entity.getId();}` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/BraidRescuePresentation.java:22` | 视图入口／迁移 | `static CabinDistressPresentation.Motion motion(YukkuriEntity entity,float partial){` → `static CabinDistressPresentation.Motion motion(YukkuriView entity,float partial){` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/BraidRescuePresentation.java:48` | 同名显示字符串／保留个例 | `"boat_calm"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/BraidRescuePresentation.java:49` | 同名显示字符串／保留个例 | `"water_struggle"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/BraidRescuePresentation.java:51` | 同名显示字符串／保留个例 | `"water_struggle"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/BraidRescuePresentation.java:53` | 同名显示字符串／保留个例 | `"water_struggle"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/BraidRescuePresentation.java:62` | 同名显示字符串／保留个例 | `"boat_calm"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/BraidRescuePresentation.java:63` | 同名显示字符串／保留个例 | `"water_recover"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/BraidRescuePresentation.java:64` | 同名显示字符串／保留个例 | `"boat_calm"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/BraidRescuePresentation.java:65` | 同名显示字符串／保留个例 | `"water_recover"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/BraidRescuePresentation.java:70` | 视图入口／迁移 | `static void configure(YukkuriEntity actor,YukkuriModel model,float partial,float renderedYaw){` → `static void configure(YukkuriView actor,YukkuriModel model,float partial,float renderedYaw){` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/CabinDistressPresentation.java:11` | 视图入口／迁移 | `static boolean alarmSelected(YukkuriEntity entity){` → `static boolean alarmSelected(YukkuriView entity){` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/CabinDistressPresentation.java:13` | 行为／迁移 | `alarm.equals("idle")` → `alarm.equals(Behaviors.IDLE)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/CabinDistressPresentation.java:15` | 视图入口／迁移 | `static Motion motion(YukkuriEntity entity,float partial){` → `static Motion motion(YukkuriView entity,float partial){` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/CabinDistressPresentation.java:18` | 视图入口／迁移 | `if(entity.getVehicle() instanceof HatBoatEntity boat){` → `改用 vehicleYukkuri()/vehicleHat() 或 yukkuriView(id)，显式 null guard 后沿用原局部变量` | 查找与乘骑的具体对应见 A.2；保持原 && / || 短路和局部变量作用域。 |
| `client/CabinDistressPresentation.java:19` | 同名显示字符串／保留个例 | `"boat_steady"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/CabinDistressPresentation.java:19` | 同名显示字符串／保留个例 | `"hat_stumble"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/CabinDistressPresentation.java:21` | 帽船视图／迁移 | `boat.crowdingState(entity)` → `boat.visibleCrowdingState(entity)` |  |
| `client/CabinDistressPresentation.java:23` | 行为／迁移 | `case "hat_stumble","hat_slipping"` → `case Behaviors.HAT_STUMBLE,Behaviors.HAT_SLIPPING` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/CabinDistressPresentation.java:24` | 行为／迁移 | `case "hat_jostle","hat_scramble"` → `case Behaviors.HAT_JOSTLE,Behaviors.HAT_SCRAMBLE` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/CabinDistressPresentation.java:25` | 同名显示字符串／保留个例 | `"boat_reassure"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 同行已明确列为迁移的其他子表达式仍按其记录执行；本条只保留此类别语义。 |
| `client/CabinDistressPresentation.java:25` | 行为／迁移 | `case "boat_crowd_guard"` → `case Behaviors.BOAT_CROWD_GUARD` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/CabinDistressPresentation.java:28` | 帽船视图／迁移 | `boat.crowdingPhaseStart(entity)` → `boat.visibleCrowdingPhaseStart(entity.getUUID())` |  |
| `client/CabinDistressPresentation.java:29` | 同名显示字符串／保留个例 | `"boat_reassure"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/CabinDistressPresentation.java:29` | 同名显示字符串／保留个例 | `"hat_jostle"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/CabinDistressPresentation.java:31` | 行为／迁移 | `entity.aquaticAlarmActivity().equals("boat_alarm_call")` → `entity.aquaticAlarmActivity().equals(Behaviors.BOAT_ALARM_CALL)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/CabinDistressPresentation.java:32` | 同名显示字符串／保留个例 | `"boat_alarm_call"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/CabinDistressPresentation.java:33` | 同名显示字符串／保留个例 | `"boat_steady"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/CabinDistressPresentation.java:40` | 同名显示字符串／保留个例 | `"hat_stumble"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/CabinDistressPresentation.java:42` | 同名显示字符串／保留个例 | `"water_struggle"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/CabinDistressPresentation.java:44` | 同名显示字符串／保留个例 | `"water_recover"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/CabinDistressPresentation.java:51` | 行为／迁移 | `case "boat_alarm_call"` → `case Behaviors.BOAT_ALARM_CALL` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/CabinDistressPresentation.java:52` | 行为／迁移 | `case "boat_alarm_wait"` → `case Behaviors.BOAT_ALARM_WAIT` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/CabinDistressPresentation.java:53` | 行为／迁移 | `case "boat_answer_call"` → `case Behaviors.BOAT_ANSWER_CALL` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/CabinDistressPresentation.java:54` | 行为／迁移 | `case "boat_calm"` → `case Behaviors.BOAT_CALM` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/CabinDistressPresentation.java:55` | 同名显示字符串／保留个例 | `"boat_alarm_wait"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 同行已明确列为迁移的其他子表达式仍按其记录执行；本条只保留此类别语义。 |
| `client/CabinDistressPresentation.java:55` | 同名显示字符串／保留个例 | `"boat_paddle"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 同行已明确列为迁移的其他子表达式仍按其记录执行；本条只保留此类别语义。 |
| `client/CabinDistressPresentation.java:55` | 行为／迁移 | `case "boat_flee","boat_return_called"` → `case Behaviors.BOAT_FLEE,Behaviors.BOAT_RETURN_CALLED` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/CabinDistressPresentation.java:59` | 视图入口／迁移 | `private static Motion selfRescue(YukkuriEntity entity,float partial){` → `private static Motion selfRescue(YukkuriView entity,float partial){` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/CabinDistressPresentation.java:61` | 行为／迁移 | `entity.getBehaviorId().startsWith("water_hat_")` → `entity.getBehaviorId().startsWith(Behaviors.WATER_HAT_PREFIX)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/CabinDistressPresentation.java:76` | 同名显示字符串／保留个例 | `"water_recover"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/CabinDistressPresentation.java:80` | 视图入口／迁移 | `private static Motion boarding(YukkuriEntity entity,float partial){` → `private static Motion boarding(YukkuriView entity,float partial){` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/CabinDistressPresentation.java:82` | 行为／迁移 | `behavior.startsWith("hat_board_")` → `behavior.startsWith(Behaviors.HAT_BOARD_PREFIX)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/CabinDistressPresentation.java:82` | 行为／迁移 | `behavior.equals("boat_board")` → `behavior.equals(Behaviors.BOAT_BOARD)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/CabinDistressPresentation.java:83` | 行为／迁移 | `behavior.startsWith("water_hat_")` → `behavior.startsWith(Behaviors.WATER_HAT_PREFIX)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/CabinDistressPresentation.java:89` | 同名显示字符串／保留个例 | `"water_hat_grip"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/CabinDistressPresentation.java:98` | 视图入口／迁移 | `static void configureBoardingContact(YukkuriEntity entity,YukkuriModel model,float partial,float renderedYaw){` → `static void configureBoardingContact(YukkuriView entity,YukkuriModel model,float partial,float renderedYaw){` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/CabinDistressPresentation.java:116` | 视图入口／迁移 | `private static org.joml.Vector3f boardingPoint(YukkuriEntity entity,net.minecraft.nbt.CompoundTag state,String key,float partial,float yaw){` → `private static org.joml.Vector3f boardingPoint(YukkuriView entity,net.minecraft.nbt.CompoundTag state,String key,float partial,float yaw){` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/CabinDistressPresentation.java:128` | 视图入口／迁移 | `static void balance(PoseStack pose,YukkuriEntity entity,HatBoatEntity boat,float partial,float renderedBodyYaw){` → `static void balance(PoseStack pose,YukkuriView entity,HatBoatView boat,float partial,float renderedBodyYaw){` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/CabinDistressPresentation.java:131` | 帽船视图／迁移 | `boat.riderBalanceRoll(entity,partial)` → `boat.visibleBalanceRoll(entity,partial)` |  |
| `client/CabinDistressPresentation.java:132` | 帽船视图／迁移 | `boat.riderBalancePitch(entity,partial)` → `boat.visibleBalancePitch(entity,partial)` |  |
| `client/CradlePickupPresentation.java:10` | 视图入口／迁移 | `static boolean active(YukkuriEntity actor){` → `static boolean active(YukkuriView actor){` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/CradlePickupPresentation.java:14` | 视图入口／迁移 | `static CabinDistressPresentation.Motion motion(YukkuriEntity actor){` → `static CabinDistressPresentation.Motion motion(YukkuriView actor){` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/CradlePickupPresentation.java:16` | 同名显示字符串／保留个例 | `"idle"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/CradlePickupPresentation.java:18` | 视图入口／迁移 | `static void configure(YukkuriEntity actor,YukkuriModel model,float partial,float renderedYaw){` → `static void configure(YukkuriView actor,YukkuriModel model,float partial,float renderedYaw){` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/CradlePickupPresentation.java:27` | 视图入口／迁移 | `private static Vector3f point(YukkuriEntity actor,String key,float partial,float renderedYaw){` → `private static Vector3f point(YukkuriView actor,String key,float partial,float renderedYaw){` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/DialogueAudio.java:32` | 视图边界／保留个例 | `if (!(entity instanceof YukkuriEntity y)) continue;` → `原生参数/筛选保留；自有显示主体接收 YukkuriView 或 HatBoatView` | 渲染 override 和绑定音频构造需 Entity；允许本地 view 别名/私有显示方法，禁止传原实体到辅助链。 |
| `client/DialogueAudio.java:52` | 视图入口／迁移 | `private final YukkuriEntity owner;` → `private final YukkuriView owner;` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/DialogueAudio.java:54` | 视图边界／保留个例 | `Voice(YukkuriEntity owner, String sound) {` → `原生参数/筛选保留；自有显示主体接收 YukkuriView 或 HatBoatView` | 渲染 override 和绑定音频构造需 Entity；允许本地 view 别名/私有显示方法，禁止传原实体到辅助链。 |
| `client/EcologyAudio.java:30` | 视图边界／保留个例 | `for(var visible:mc.level.entitiesForRendering())if(visible instanceof YukkuriEntity e){` → `原生参数/筛选保留；自有显示主体接收 YukkuriView 或 HatBoatView` | 渲染 override 和绑定音频构造需 Entity；允许本地 view 别名/私有显示方法，禁止传原实体到辅助链。 |
| `client/EcologyAudio.java:32` | 行为／迁移 | `e.getBehaviorId().equals("sing")` → `e.getBehaviorId().equals(Behaviors.SING)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/EcologyAudio.java:47` | 视图入口／迁移 | `private final YukkuriEntity owner;` → `private final YukkuriView owner;` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/EcologyAudio.java:49` | 视图边界／保留个例 | `Song(YukkuriEntity e){` → `原生参数/筛选保留；自有显示主体接收 YukkuriView 或 HatBoatView` | 渲染 override 和绑定音频构造需 Entity；允许本地 view 别名/私有显示方法，禁止传原实体到辅助链。 |
| `client/EcologyAudio.java:55` | 行为／迁移 | `owner.getBehaviorId().equals("sing")` → `owner.getBehaviorId().equals(Behaviors.SING)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/FamilyRenderer.java:17` | 视图入口／迁移 | `static void render(YukkuriEntity mother,PoseStack pose,MultiBufferSource buffers,int light) {` → `static void render(YukkuriView mother,PoseStack pose,MultiBufferSource buffers,int light) {` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/FamilyRenderer.java:18` | 物种／迁移 | `mother.getVariant().isHouse()` → `mother.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `client/FamilyRenderer.java:48` | 同名显示字符串／保留个例 | `"idle"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/HatBoatMotion.java:11` | 视图入口／迁移 | `static float yaw(HatBoatEntity boat,float partial){return Mth.rotLerp(partial,boat.yRotO,boat.getYRot());}` → `static float yaw(HatBoatView boat,float partial){return Mth.rotLerp(partial,boat.yRotO,boat.getYRot());}` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/HatBoatMotion.java:11` | 视图读取／迁移 | `boat.yRotO` → `boat.previousYaw()` |  |
| `client/HatBoatMotion.java:13` | 视图入口／迁移 | `static void local(PoseStack pose,HatBoatEntity boat,float partial){` → `static void local(PoseStack pose,HatBoatView boat,float partial){` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/HatBoatMotion.java:14` | 帽船视图／迁移 | `boat.motionRoll(partial)` → `boat.visibleMotionRoll(partial)` |  |
| `client/HatBoatMotion.java:15` | 帽船视图／迁移 | `boat.motionPitch(partial)` → `boat.visibleMotionPitch(partial)` |  |
| `client/HatBoatMotion.java:18` | 视图入口／迁移 | `static void rider(PoseStack pose,Entity rider,HatBoatEntity boat,float partial){` → `static void rider(PoseStack pose,YukkuriView rider,HatBoatView boat,float partial){` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/HatBoatRenderer.java:22` | 视图边界／保留个例 | `public final class HatBoatRenderer extends EntityRenderer<HatBoatEntity> {` → `原生框架入口保留实体类型；内部取 YukkuriView / HatBoatView` | 框架注册、事件筛选或父类调用边界，不把实体传给显示辅助方法。 |
| `client/HatBoatRenderer.java:31` | 视图边界／保留个例 | `@Override public ResourceLocation getTextureLocation(HatBoatEntity boat) { return MCYukkuri.id("textures/entity/marisa/neutral.png"); }` → `原生参数/筛选保留；自有显示主体接收 YukkuriView 或 HatBoatView` | 渲染 override 和绑定音频构造需 Entity；允许本地 view 别名/私有显示方法，禁止传原实体到辅助链。 |
| `client/HatBoatRenderer.java:37` | 视图入口／迁移 | `private static void applyDip(PoseStack pose,HatBoatEntity boat,float partial){` → `private static void applyDip(PoseStack pose,HatBoatView boat,float partial){` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/HatBoatRenderer.java:45` | 视图边界／保留个例 | `@Override public void render(HatBoatEntity boat,float yaw,float partial,PoseStack pose,MultiBufferSource buffers,int light) {` → `原生参数/筛选保留；自有显示主体接收 YukkuriView 或 HatBoatView` | 渲染 override 和绑定音频构造需 Entity；允许本地 view 别名/私有显示方法，禁止传原实体到辅助链。 |
| `client/HatBoatRenderer.java:46` | 视图边界／保留个例 | `if(boat.state()==HatBoatEntity.State.RESCUED)return;  // back on its owner's head (the braid clip carries it there)` → `原样保留` | 枚举/静态常量所属类型不变，不是实例组件访问。 |
| `client/HatCargoRenderer.java:15` | 视图入口／迁移 | `static void render(HatBoatEntity boat,float partial,PoseStack pose,MultiBufferSource buffers,int light) {` → `static void render(HatBoatView boat,float partial,PoseStack pose,MultiBufferSource buffers,int light) {` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/HatCargoRenderer.java:16` | 帽船视图／迁移 | `boat.cargo().asideTier()` → `boat.visibleAsideTier()` |  |
| `client/HatCargoRenderer.java:16` | 帽船视图／迁移 | `boat.cargo().tier()` → `boat.visibleCargoTier()` |  |
| `client/HatWaterMaskPass.java:27` | 视图入口／迁移 | `static void enqueue(HatBoatEntity boat,PoseStack pose) {` → `static void enqueue(HatBoatView boat,PoseStack pose) {` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/HeldItemPresentation.java:8` | 视图入口／迁移 | `static boolean closedGrip(YukkuriEntity entity) {` → `static boolean closedGrip(YukkuriView entity) {` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/HeldItemPresentation.java:10` | 物种／迁移 | `entity.getVariant().isHouse()` → `entity.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `client/HeldItemPresentation.java:11` | 行为／迁移 | `behavior.equals("family_chew")` → `behavior.equals(Behaviors.FAMILY_CHEW)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/HeldItemPresentation.java:12` | 行为／迁移 | `behavior.equals("boat_feed_young")` → `behavior.equals(Behaviors.BOAT_FEED_YOUNG)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/HeldItemPresentation.java:16` | 视图入口／迁移 | `static boolean kanroken(YukkuriEntity entity,String behavior) {` → `static boolean kanroken(YukkuriView entity,String behavior) {` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/HeldItemPresentation.java:18` | 行为／迁移 | `behavior.equals("family_chew")` → `behavior.equals(Behaviors.FAMILY_CHEW)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/HeldItemPresentation.java:18` | 行为／迁移 | `behavior.equals("feed_child")` → `behavior.equals(Behaviors.FEED_CHILD)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/HeldItemPresentation.java:18` | 行为／迁移 | `behavior.startsWith("sleep")` → `behavior.startsWith(Behaviors.SLEEP)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/HeldItemPresentation.java:18` | 行为／迁移 | `behavior.equals("wake")` → `behavior.equals(Behaviors.WAKE)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/HeldItemPresentation.java:19` | 行为／迁移 | `behavior.equals("boat_feed_young")` → `behavior.equals(Behaviors.BOAT_FEED_YOUNG)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/LiveBirthRenderer.java:24` | 视图入口／迁移 | `static float contraction(YukkuriEntity mother,float partial){` → `static float contraction(YukkuriView mother,float partial){` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/LiveBirthRenderer.java:29` | 视图入口／迁移 | `static Delivery prepare(YukkuriEntity mother,YukkuriModel model,float partial){` → `static Delivery prepare(YukkuriView mother,YukkuriModel model,float partial){` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/LiveBirthRenderer.java:30` | 同名显示字符串／保留个例 | `"postpartum"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/LiveBirthRenderer.java:37` | 视图入口／迁移 | `static void renderDose(YukkuriEntity entity,PoseStack pose,MultiBufferSource buffers,int light){` → `static void renderDose(YukkuriView entity,PoseStack pose,MultiBufferSource buffers,int light){` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/LiveBirthRenderer.java:43` | 视图入口／迁移 | `private final YukkuriEntity mother;` → `private final YukkuriView mother;` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/LiveBirthRenderer.java:58` | 视图入口／迁移 | `Delivery(YukkuriEntity mother,YukkuriModel model,float contraction){` → `Delivery(YukkuriView mother,YukkuriModel model,float contraction){` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/LiveBirthRenderer.java:79` | 视图读取／迁移 | `mother.subspecies().assetId()` → `mother.assetId()` |  |
| `client/LiveBirthRenderer.java:87` | 视图调用／迁移 | `LiveBirthGeometry.exitFront(mother,next)` → `LiveBirthGeometry.visibleExitFront(mother,next)` | 对应独立名入口，不与原 Entity 参数形成交叉重载。 |
| `client/LiveBirthRenderer.java:164` | 视图读取／迁移 | `mother.subspecies().assetId()` → `mother.assetId()` |  |
| `client/OperationAttachments.java:11` | 视图入口／迁移 | `public static void render(YukkuriEntity entity,YukkuriModel model,PoseStack pose,MultiBufferSource buffers,int light,float angle,float lift,float partial){` → `public static void render(YukkuriView entity,YukkuriModel model,PoseStack pose,MultiBufferSource buffers,int light,float angle,float lift,float partial){` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/OperationAttachments.java:15` | 视图读取／迁移 | `entity.yBodyRotO` → `entity.previousBodyYaw()` |  |
| `client/OperationAttachments.java:15` | 视图读取／迁移 | `entity.yBodyRot` → `entity.bodyYaw()` |  |
| `client/OperationAttachments.java:19` | 表现数值／迁移 | `entity.getVariant().isHouse()?.25f:.65f` → `entity.profile().operationTipScale()` |  |
| `client/OperationAttachments.java:25` | 视图读取／迁移 | `entity.subspecies().assetId()` → `entity.assetId()` |  |
| `client/PropItemRenderer.java:24` | 素材个例／保留个例 | `float scale=context==ItemDisplayContext.GUI?(RemainsRenderer.asset(data).startsWith("house_")?2.5f:.8f):RemainsRenderer.renderScale(data);` → `保留素材 ID 分支` | 操作模型资源/遗体存档而非存活实体画像；不通过字符串反推实体物种。 |
| `client/RapemOverlayRenderer.java:19` | 视图入口／迁移 | `public static void render(YukkuriEntity entity, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {` → `public static void render(YukkuriView entity, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/RapemOverlayRenderer.java:28` | 表现数值／迁移 | `entity.getVariant().isHouse() ? .35 : .87` → `entity.profile().bodyOverlayTop()` |  |
| `client/RemainsRenderer.java:16` | 素材个例／保留个例 | `static float renderScale(CompoundTag data){return RemainsEntity.dimension(data,"RenderScale",asset(data).startsWith("house_")?RemainsEntity.dimension(data,"SourceScale",.32f)/.32f:RemainsEntity.dimension(data,"SourceScale",1));}` → `保留素材 ID 分支` | 操作模型资源/遗体存档而非存活实体画像；不通过字符串反推实体物种。 |
| `client/SelfRescueHatPresentation.java:11` | 视图入口／迁移 | `static void configure(YukkuriEntity entity,YukkuriModel model,float partial,float bodyYaw){` → `static void configure(YukkuriView entity,YukkuriModel model,float partial,float bodyYaw){` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/SelfRescueHatPresentation.java:14` | 行为／迁移 | `entity.getBehaviorId().startsWith("water_hat_")` → `entity.getBehaviorId().startsWith(Behaviors.WATER_HAT_PREFIX)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/SpeechBubble.java:15` | 视图入口／迁移 | `public static void render(YukkuriEntity entity, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {` → `public static void render(YukkuriView entity, float partialTick, PoseStack pose, MultiBufferSource buffers, int light) {` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/SpeechBubble.java:29` | 物种／迁移 | `entity.getVariant().isHouse()` → `entity.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `client/ToiletPresentation.java:65` | 行为／迁移 | `case "toilet", "ground_toilet", "boat_toilet", "toilet_accident", "first_toilet_assist",` → `case Behaviors.TOILET, Behaviors.GROUND_TOILET, Behaviors.BOAT_TOILET, Behaviors.TOILET_ACCIDENT, Behaviors.FIRST_TOILET_ASSIST,` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/ToiletPresentation.java:66` | 行为／迁移 | `"first_toilet_wait", "first_toilet_stimulated"` → `Behaviors.FIRST_TOILET_WAIT, Behaviors.FIRST_TOILET_STIMULATED` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/ToiletPresentation.java:71` | 视图入口／迁移 | `static void renderTongue(YukkuriEntity parent, String behavior, float ticks, float partialTick,` → `static void renderTongue(YukkuriView parent, String behavior, float ticks, float partialTick,` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/ToiletPresentation.java:73` | 行为／迁移 | `behavior.equals("comfort_lick")` → `behavior.equals(Behaviors.COMFORT_LICK)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/ToiletPresentation.java:74` | 行为／迁移 | `behavior.equals("first_toilet_assist")` → `behavior.equals(Behaviors.FIRST_TOILET_ASSIST)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/ToiletPresentation.java:75` | 视图入口／迁移 | `&#124;&#124; !(parent.level().getEntity(parent.carePartnerId()) instanceof YukkuriEntity child)` → `改用 vehicleYukkuri()/vehicleHat() 或 yukkuriView(id)，显式 null guard 后沿用原局部变量` | 查找与乘骑的具体对应见 A.2；保持原 && / || 短路和局部变量作用域。 |
| `client/ToiletPresentation.java:76` | 视图调用／迁移 | `FamilyFeedingContact.rearContact(parent, child)` → `FamilyFeedingContact.visibleRearContact(parent, child)` | 对应独立名入口，不与原 Entity 参数形成交叉重载。 |
| `client/ToiletPresentation.java:76` | 视图调用／迁移 | `FamilyFeedingContact.mouthsMeet(parent, child)` → `FamilyFeedingContact.visibleMouthsMeet(parent, child)` | 对应独立名入口，不与原 Entity 参数形成交叉重载。 |
| `client/ToiletPresentation.java:77` | 视图读取／迁移 | `parent.subspecies().assetId()` → `parent.assetId()` |  |
| `client/ToiletPresentation.java:108` | 视图入口／迁移 | `static void renderStool(YukkuriEntity actor, String behavior, float ticks, YukkuriModel model, PoseStack pose, MultiBufferSource buffers, int light) {` → `static void renderStool(YukkuriView actor, String behavior, float ticks, YukkuriModel model, PoseStack pose, MultiBufferSource buffers, int light) {` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/ToiletPresentation.java:109` | 行为／迁移 | `behavior.equals("toilet_accident")` → `behavior.equals(Behaviors.TOILET_ACCIDENT)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/ToiletPresentation.java:109` | 行为／迁移 | `behavior.equals("ground_toilet")` → `behavior.equals(Behaviors.GROUND_TOILET)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/ToiletPresentation.java:109` | 行为／迁移 | `behavior.equals("boat_toilet")` → `behavior.equals(Behaviors.BOAT_TOILET)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/ToiletPresentation.java:109` | 行为／迁移 | `behavior.equals("toilet")` → `behavior.equals(Behaviors.TOILET)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/ToiletPresentation.java:112` | 视图读取／迁移 | `actor.subspecies().assetId()` → `actor.assetId()` |  |
| `client/WoundRenderer.java:107` | 视图入口／迁移 | `static boolean active(YukkuriEntity e){return !e.visibleWounds().isEmpty()&#124;&#124;!e.visibleMarks().isEmpty()&#124;&#124;(e.visibleSigns()&(BodySigns.MOLD_MASK&#124;BodySigns.FOOT_BURNED))!=0;}` → `static boolean active(YukkuriView e){return !e.visibleWounds().isEmpty()&#124;&#124;!e.visibleMarks().isEmpty()&#124;&#124;(e.visibleSigns()&(BodySigns.MOLD_MASK&#124;BodySigns.FOOT_BURNED))!=0;}` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/WoundRenderer.java:116` | 视图入口／迁移 | `static void render(YukkuriEntity e,String expression,Matrix4f[] world,PoseStack stack,VertexConsumer body,MultiBufferSource buffers,int light,int overlay){` → `static void render(YukkuriView e,String expression,Matrix4f[] world,PoseStack stack,VertexConsumer body,MultiBufferSource buffers,int light,int overlay){` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/WoundRenderer.java:117` | 视图读取／迁移 | `e.subspecies().assetId()` → `e.assetId()` |  |
| `client/WoundRenderer.java:119` | 物种／迁移 | `e.getVariant().isHouse()` → `e.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `client/WoundRenderer.java:119` | 视图读取／迁移 | `e.subspecies().assetId()` → `e.assetId()` |  |
| `client/YukkuriModel.java:335` | 素材个例／保留个例 | `float factor=(id.startsWith("house_")?.22f:.65f)*scale;target.scale(factor,factor,factor);` → `保留素材 ID 分支` | 操作模型资源/遗体存档而非存活实体画像；不通过字符串反推实体物种。 |
| `client/YukkuriModel.java:340` | 素材个例／保留个例 | `float x=id.startsWith("house_")?1.2f:3.6f,y=id.startsWith("house_")?1.4f:2.2f,z=id.startsWith("house_")?-1.65f:-4.8f;` → `保留素材 ID 分支` | 操作模型资源/遗体存档而非存活实体画像；不通过字符串反推实体物种。 |
| `client/YukkuriModel.java:683` | 同名显示字符串／保留个例 | `"idle"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/YukkuriModel.java:684` | 素材个例／保留个例 | `houseNormals=id.startsWith("house_")?new HouseNormals(restVertices,parts):null;` → `保留素材 ID 分支` | 操作模型资源/遗体存档而非存活实体画像；不通过字符串反推实体物种。 |
| `client/YukkuriModel.java:724` | 行为／迁移 | `true, "idle", 0` → `true, Behaviors.IDLE, 0` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriModel.java:737` | 同名显示字符串／保留个例 | `"idle"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/YukkuriModel.java:739` | 同名显示字符串／保留个例 | `"idle"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/YukkuriModel.java:740` | 行为／迁移 | `behavior.equals("house_deposit")` → `behavior.equals(Behaviors.HOUSE_DEPOSIT)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriModel.java:740` | 行为／迁移 | `behavior.equals("house_pickup")` → `behavior.equals(Behaviors.HOUSE_PICKUP)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriModel.java:740` | 行为／迁移 | `behavior.equals("house_carry")` → `behavior.equals(Behaviors.HOUSE_CARRY)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriModel.java:741` | 行为／迁移 | `behavior.equals("house_crawl")` → `behavior.equals(Behaviors.HOUSE_CRAWL)` | 只替换行为 ID 或其构造；保留规范化位置、大小写、短路和原前后缀。 |
| `client/YukkuriModel.java:745` | 行为／迁移 | `behavior.startsWith("house_")` → `behavior.startsWith(Behaviors.HOUSE_PREFIX)` | 行为选择前缀；不影响同名素材前缀。 |
| `client/YukkuriModel.java:746` | 同名显示字符串／保留个例 | `"sleep"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 同行已明确列为迁移的其他子表达式仍按其记录执行；本条只保留此类别语义。 |
| `client/YukkuriModel.java:746` | 同名显示字符串／保留个例 | `"idle"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 同行已明确列为迁移的其他子表达式仍按其记录执行；本条只保留此类别语义。 |
| `client/YukkuriModel.java:746` | 行为／迁移 | `behavior.equals("house_sleep")` → `behavior.equals(Behaviors.HOUSE_SLEEP)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriModel.java:746` | 行为／迁移 | `behavior.equals("house_hide")` → `behavior.equals(Behaviors.HOUSE_HIDE)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriModel.java:749` | 行为／迁移 | `behavior.equals("first_toilet_assist")` → `behavior.equals(Behaviors.FIRST_TOILET_ASSIST)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriModel.java:749` | 行为／迁移 | `behavior.equals("feed_child")` → `behavior.equals(Behaviors.FEED_CHILD)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriModel.java:750` | 行为／迁移 | `behavior.equals("newborn_crawl")` → `behavior.equals(Behaviors.NEWBORN_CRAWL)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriModel.java:750` | 行为／迁移 | `behavior.equals("learn_jump")` → `behavior.equals(Behaviors.LEARN_JUMP)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriModel.java:752` | 行为／迁移 | `behavior.equals("boat_prepare_carry")` → `behavior.equals(Behaviors.BOAT_PREPARE_CARRY)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriModel.java:752` | 行为／迁移 | `behavior.equals("boat_release_carry")` → `behavior.equals(Behaviors.BOAT_RELEASE_CARRY)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriModel.java:752` | 行为／迁移 | `behavior.equals("boat_carry_young")` → `behavior.equals(Behaviors.BOAT_CARRY_YOUNG)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriModel.java:752` | 行为／迁移 | `behavior.equals("boat_be_carried")` → `behavior.equals(Behaviors.BOAT_BE_CARRIED)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriModel.java:752` | 行为／迁移 | `behavior.equals("learn_watch")` → `behavior.equals(Behaviors.LEARN_WATCH)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriModel.java:752` | 行为／迁移 | `behavior.equals("care_finish")` → `behavior.equals(Behaviors.CARE_FINISH)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriModel.java:752` | 行为／迁移 | `behavior.equals("hat_walk")` → `behavior.equals(Behaviors.HAT_WALK)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriModel.java:753` | 行为／迁移 | `behavior.equals("nest_harvest")` → `behavior.equals(Behaviors.NEST_HARVEST)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriModel.java:753` | 行为／迁移 | `behavior.equals("nest_spread")` → `behavior.equals(Behaviors.NEST_SPREAD)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriModel.java:753` | 行为／迁移 | `behavior.equals("nest_place")` → `behavior.equals(Behaviors.NEST_PLACE)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriModel.java:753` | 行为／迁移 | `behavior.equals("nest_press")` → `behavior.equals(Behaviors.NEST_PRESS)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriModel.java:754` | 同名显示字符串／保留个例 | `"house_carry"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/YukkuriModel.java:754` | 同名显示字符串／保留个例 | `"idle"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/YukkuriModel.java:755` | 同名显示字符串／保留个例 | `"house_deposit"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/YukkuriModel.java:755` | 同名显示字符串／保留个例 | `"house_pickup"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/YukkuriModel.java:755` | 同名显示字符串／保留个例 | `"house_carry"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/YukkuriModel.java:761` | 同名显示字符串／保留个例 | `"idle"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/YukkuriModel.java:764` | 同名显示字符串／保留个例 | `"idle"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/YukkuriModel.java:778` | 同名显示字符串／保留个例 | `"house_carry"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/YukkuriModel.java:782` | 同名显示字符串／保留个例 | `"house_deposit"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/YukkuriModel.java:782` | 同名显示字符串／保留个例 | `"house_pickup"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/YukkuriModel.java:782` | 同名显示字符串／保留个例 | `"house_carry"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/YukkuriModel.java:784` | 同名显示字符串／保留个例 | `"house_carry"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/YukkuriModel.java:786` | 行为／迁移 | `behavior.equals("family_chew")` → `behavior.equals(Behaviors.FAMILY_CHEW)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriModel.java:786` | 行为／迁移 | `behavior.equals("chew")` → `behavior.equals(Behaviors.CHEW)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriModel.java:786` | 行为／迁移 | `behavior.equals("sing")` → `behavior.equals(Behaviors.SING)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriModel.java:802` | 行为／迁移 | `behavior.equals("first_toilet_assist")` → `behavior.equals(Behaviors.FIRST_TOILET_ASSIST)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriModel.java:802` | 行为／迁移 | `behavior.equals("boat_feed_young")` → `behavior.equals(Behaviors.BOAT_FEED_YOUNG)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriModel.java:802` | 行为／迁移 | `behavior.equals("feed_child")` → `behavior.equals(Behaviors.FEED_CHILD)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriModel.java:817` | 行为／迁移 | `behavior.equals("first_toilet_assist")` → `behavior.equals(Behaviors.FIRST_TOILET_ASSIST)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriModel.java:900` | 素材个例／保留个例 | `if(id.startsWith("house_")&&part.name.startsWith("face_")&&vertices[first*8+7]>=-.15f)continue;` → `保留素材 ID 分支` | 操作模型资源/遗体存档而非存活实体画像；不通过字符串反推实体物种。 |
| `client/YukkuriModel.java:1109` | 视图入口／迁移 | `boolean accessory,String behavior,float behaviorTicks,int hairLoss,io.github.longshou.mcyukkuri.entity.YukkuriEntity entity,` → `boolean accessory,String behavior,float behaviorTicks,int hairLoss,io.github.longshou.mcyukkuri.entity.YukkuriView entity,` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/YukkuriModel.java:1147` | 同名显示字符串／保留个例 | `"sleep"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/YukkuriModel.java:1151` | 行为／迁移 | `accessory,"idle",0` → `accessory,Behaviors.IDLE,0` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriModel.java:1152` | 同名显示字符串／保留个例 | `"sleep"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/YukkuriModel.java:1152` | 素材个例／保留个例 | `if(drawingWoundedBody)WoundRenderer.render(key,id,"sleep",wounds,0,body.getInt("Bandage")>0,data.getInt("Filling"),id.startsWith("house_"),world,stack,buffer,buffers,light,overlay);` → `保留素材 ID 分支` | 操作模型资源/遗体存档而非存活实体画像；不通过字符串反推实体物种。 |
| `client/YukkuriRenderer.java:18` | 视图边界／保留个例 | `public final class YukkuriRenderer extends EntityRenderer<YukkuriEntity> {` → `原生框架入口保留实体类型；内部取 YukkuriView / HatBoatView` | 框架注册、事件筛选或父类调用边界，不把实体传给显示辅助方法。 |
| `client/YukkuriRenderer.java:25` | 视图边界／保留个例 | `public boolean shouldRender(YukkuriEntity entity, Frustum frustum, double x, double y, double z) {` → `原生参数/筛选保留；自有显示主体接收 YukkuriView 或 HatBoatView` | 渲染 override 和绑定音频构造需 Entity；允许本地 view 别名/私有显示方法，禁止传原实体到辅助链。 |
| `client/YukkuriRenderer.java:28` | 物种／迁移 | `entity.getVariant().isHouse()` → `entity.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `client/YukkuriRenderer.java:33` | 视图边界／保留个例 | `public ResourceLocation getTextureLocation(YukkuriEntity entity) {` → `原生参数/筛选保留；自有显示主体接收 YukkuriView 或 HatBoatView` | 渲染 override 和绑定音频构造需 Entity；允许本地 view 别名/私有显示方法，禁止传原实体到辅助链。 |
| `client/YukkuriRenderer.java:37` | 视图入口／迁移 | `private ResourceLocation textureAt(YukkuriEntity entity, float partialTick) {` → `private ResourceLocation textureAt(YukkuriView entity, float partialTick) {` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/YukkuriRenderer.java:40` | 视图读取／迁移 | `entity.subspecies().assetId()` → `entity.assetId()` |  |
| `client/YukkuriRenderer.java:43` | 视图读取／迁移 | `entity.subspecies().assetId()` → `entity.assetId()` |  |
| `client/YukkuriRenderer.java:45` | 行为／迁移 | `entity.getBehaviorId().equals("boat_rescue_approach")` → `entity.getBehaviorId().equals(Behaviors.BOAT_RESCUE_APPROACH)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriRenderer.java:46` | 行为／迁移 | `entity.aquaticAlarmActivity().equals("boat_return_called")` → `entity.aquaticAlarmActivity().equals(Behaviors.BOAT_RETURN_CALLED)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriRenderer.java:46` | 行为／迁移 | `entity.aquaticAlarmActivity().equals("boat_flee")` → `entity.aquaticAlarmActivity().equals(Behaviors.BOAT_FLEE)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriRenderer.java:48` | 视图入口／迁移 | `if(distress==null&&entity.getVehicle() instanceof io.github.longshou.mcyukkuri.subspecies.HatBoatEntity boat` → `改用 vehicleYukkuri()/vehicleHat() 或 yukkuriView(id)，显式 null guard 后沿用原局部变量` | 查找与乘骑的具体对应见 A.2；保持原 && / || 短路和局部变量作用域。 |
| `client/YukkuriRenderer.java:51` | 视图读取／迁移 | `entity.subspecies().assetId()` → `entity.assetId()` |  |
| `client/YukkuriRenderer.java:55` | 视图边界／保留个例 | `public void render(YukkuriEntity entity, float yaw, float partialTick, PoseStack pose,` → `原生参数/筛选保留；自有显示主体接收 YukkuriView 或 HatBoatView` | 渲染 override 和绑定音频构造需 Entity；允许本地 view 别名/私有显示方法，禁止传原实体到辅助链。 |
| `client/YukkuriRenderer.java:58` | 表现数值／迁移 | `entity.getVariant().isHouse() ? .12f : .32f` → `entity.profile().shadowRadius()` |  |
| `client/YukkuriRenderer.java:61` | 视图入口／迁移 | `var hatParent=entity.getVehicle() instanceof YukkuriEntity parent&&io.github.longshou.mcyukkuri.family.HatCarry.riding(parent,entity)?parent:null;` → `改用 vehicleYukkuri()/vehicleHat() 或 yukkuriView(id)，显式 null guard 后沿用原局部变量` | 查找与乘骑的具体对应见 A.2；保持原 && / || 短路和局部变量作用域。 |
| `client/YukkuriRenderer.java:61` | 视图调用／迁移 | `io.github.longshou.mcyukkuri.family.HatCarry.riding(parent,entity)` → `HatCarry.visibleRiding(parent,entity)` | 对应独立名入口，不与原 Entity 参数形成交叉重载。 |
| `client/YukkuriRenderer.java:63` | 视图读取／迁移 | `entity.yBodyRotO` → `entity.previousBodyYaw()` |  |
| `client/YukkuriRenderer.java:63` | 视图读取／迁移 | `entity.yBodyRot` → `entity.bodyYaw()` |  |
| `client/YukkuriRenderer.java:70` | 视图入口／迁移 | `var rockingHat=entity.getVehicle() instanceof io.github.longshou.mcyukkuri.subspecies.HatBoatEntity boat?boat` → `改用 vehicleYukkuri()/vehicleHat() 或 yukkuriView(id)，显式 null guard 后沿用原局部变量` | 查找与乘骑的具体对应见 A.2；保持原 && / || 短路和局部变量作用域。 |
| `client/YukkuriRenderer.java:71` | 视图入口／迁移 | `:hatParent!=null&&hatParent.getVehicle() instanceof io.github.longshou.mcyukkuri.subspecies.HatBoatEntity carriedBoat?carriedBoat:null;` → `改用 vehicleYukkuri()/vehicleHat() 或 yukkuriView(id)，显式 null guard 后沿用原局部变量` | 查找与乘骑的具体对应见 A.2；保持原 && / || 短路和局部变量作用域。 |
| `client/YukkuriRenderer.java:80` | 行为／迁移 | `state.equals("boat_crowd_guard")` → `state.equals(Behaviors.BOAT_CROWD_GUARD)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriRenderer.java:80` | 行为／迁移 | `state.equals("boat_reassure")` → `state.equals(Behaviors.BOAT_REASSURE)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriRenderer.java:82` | 视图读取／迁移 | `entity.yHeadRotO` → `entity.previousHeadYaw()` |  |
| `client/YukkuriRenderer.java:89` | 视图读取／迁移 | `entity.deathTime` → `entity.deathTicks()` |  |
| `client/YukkuriRenderer.java:94` | 视图读取／迁移 | `entity.subspecies().assetId()` → `entity.assetId()` |  |
| `client/YukkuriRenderer.java:96` | 视图读取／迁移 | `entity.subspecies().assetId()` → `entity.assetId()` |  |
| `client/YukkuriRenderer.java:98` | 视图读取／迁移 | `entity.subspecies().assetId()` → `entity.assetId()` |  |
| `client/YukkuriRenderer.java:101` | 视图读取／迁移 | `entity.subspecies().assetId()` → `entity.assetId()` |  |
| `client/YukkuriRenderer.java:111` | 视图读取／迁移 | `entity.tickCount` → `entity.ageTicks()` |  |
| `client/YukkuriRenderer.java:113` | 视图读取／迁移 | `entity.deathTime` → `entity.deathTicks()` |  |
| `client/YukkuriRenderer.java:113` | 视图读取／迁移 | `entity.hurtTime` → `entity.hurtTicks()` |  |
| `client/YukkuriRenderer.java:115` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriRenderer.java:116` | 行为／迁移 | `behavior.equals("slide_down")` → `behavior.equals(Behaviors.SLIDE_DOWN)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriRenderer.java:128` | 行为／迁移 | `behavior = "house_crawl"` → `behavior = Behaviors.HOUSE_CRAWL` | 只替换行为 ID 或其构造；保留规范化位置、大小写、短路和原前后缀。 |
| `client/YukkuriRenderer.java:132` | 同名显示字符串／保留个例 | `"sleep"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/YukkuriRenderer.java:133` | 行为／迁移 | `behavior.equals("sleep_enter")` → `behavior.equals(Behaviors.SLEEP_ENTER)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriRenderer.java:134` | 行为／迁移 | `behavior.equals("sleep")` → `behavior.equals(Behaviors.SLEEP)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriRenderer.java:135` | 行为／迁移 | `behavior.equals("wake")` → `behavior.equals(Behaviors.WAKE)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriRenderer.java:145` | 同名显示字符串／保留个例 | `"idle"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/YukkuriRenderer.java:148` | 同名显示字符串／保留个例 | `"boat_steady"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/YukkuriRenderer.java:148` | 同名显示字符串／保留个例 | `"hat_cradle"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/YukkuriRenderer.java:150` | 同名显示字符串／保留个例 | `"idle"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 同行已明确列为迁移的其他子表达式仍按其记录执行；本条只保留此类别语义。 |
| `client/YukkuriRenderer.java:150` | 行为／迁移 | `behavior.equals("slide_down")` → `behavior.equals(Behaviors.SLIDE_DOWN)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriRenderer.java:151` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriRenderer.java:157` | 同名显示字符串／保留个例 | `"boat_paddle"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 同行已明确列为迁移的其他子表达式仍按其记录执行；本条只保留此类别语义。 |
| `client/YukkuriRenderer.java:157` | 视图入口／迁移 | `if ((action.equals("boat_paddle")&#124;&#124;action.equals("hat_brim_offer")&#124;&#124;action.equals("boat_nudge_young")) && entity.getVehicle() instanceof io.github.longshou.mcyukkuri.subspecies.HatBoatEntity boat` → `改用 vehicleYukkuri()/vehicleHat() 或 yukkuriView(id)，显式 null guard 后沿用原局部变量` | 查找与乘骑的具体对应见 A.2；保持原 && / || 短路和局部变量作用域。 |
| `client/YukkuriRenderer.java:158` | 帽船视图／迁移 | `boat.cargo().paddle()` → `boat.visiblePaddle()` |  |
| `client/YukkuriRenderer.java:162` | 同名显示字符串／保留个例 | `"boat_paddle"` → `原样保留` | 动画 clip、表情或模型资源键，不是行为 ID。 |
| `client/YukkuriRenderer.java:172` | 视图读取／迁移 | `entity.subspecies().assetId()` → `entity.assetId()` |  |
| `client/YukkuriRenderer.java:178` | 行为／迁移 | `behavior.equals("boat_feed_young")` → `behavior.equals(Behaviors.BOAT_FEED_YOUNG)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriRenderer.java:178` | 行为／迁移 | `behavior.equals("family_chew")` → `behavior.equals(Behaviors.FAMILY_CHEW)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriRenderer.java:183` | 行为／迁移 | `behavior.equals("boat_give_paddle")` → `behavior.equals(Behaviors.BOAT_GIVE_PADDLE)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriRenderer.java:184` | 行为／迁移 | `behavior.equals("family_chew")` → `behavior.equals(Behaviors.FAMILY_CHEW)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriRenderer.java:189` | 表现数值／迁移 | `entity.getVariant().isHouse()?.35f:1` → `entity.profile().portionScale()` |  |
| `client/YukkuriRenderer.java:201` | 行为／迁移 | `behavior.startsWith("independence_")` → `behavior.startsWith(Behaviors.INDEPENDENCE_PREFIX)` | 行为选择前缀；不影响同名素材前缀。 |
| `client/YukkuriRenderer.java:201` | 行为／迁移 | `behavior.equals("shelter_carry")` → `behavior.equals(Behaviors.SHELTER_CARRY)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriRenderer.java:201` | 行为／迁移 | `behavior.startsWith("nest_")` → `behavior.startsWith(Behaviors.NEST_PREFIX)` | 行为选择前缀；不影响同名素材前缀。 |
| `client/YukkuriRenderer.java:210` | 表现数值／迁移 | `entity.getVariant().isHouse()?.35f:1` → `entity.profile().portionScale()` |  |
| `client/YukkuriRenderer.java:251` | 视图入口／迁移 | `private static void configureCareContact(YukkuriEntity parent,String behavior,float partialTick,YukkuriModel model) {` → `private static void configureCareContact(YukkuriView parent,String behavior,float partialTick,YukkuriModel model) {` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/YukkuriRenderer.java:252` | 行为／迁移 | `behavior.equals("first_toilet_assist")` → `behavior.equals(Behaviors.FIRST_TOILET_ASSIST)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriRenderer.java:253` | 行为／迁移 | `behavior.equals("boat_feed_young")` → `behavior.equals(Behaviors.BOAT_FEED_YOUNG)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriRenderer.java:253` | 行为／迁移 | `behavior.equals("feed_child")` → `behavior.equals(Behaviors.FEED_CHILD)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriRenderer.java:254` | 视图入口／迁移 | `&#124;&#124; !(parent.level().getEntity(parent.carePartnerId()) instanceof YukkuriEntity child)) {` → `改用 vehicleYukkuri()/vehicleHat() 或 yukkuriView(id)，显式 null guard 后沿用原局部变量` | 查找与乘骑的具体对应见 A.2；保持原 && / || 短路和局部变量作用域。 |
| `client/YukkuriRenderer.java:258` | 视图读取／迁移 | `parent.subspecies().assetId()` → `parent.assetId()` |  |
| `client/YukkuriRenderer.java:259` | 视图读取／迁移 | `child.subspecies().assetId()` → `child.assetId()` |  |
| `client/YukkuriRenderer.java:264` | 视图读取／迁移 | `parent.yBodyRotO` → `parent.previousBodyYaw()` |  |
| `client/YukkuriRenderer.java:264` | 视图读取／迁移 | `parent.yBodyRot` → `parent.bodyYaw()` |  |
| `client/YukkuriRenderer.java:265` | 视图读取／迁移 | `child.yBodyRotO` → `child.previousBodyYaw()` |  |
| `client/YukkuriRenderer.java:265` | 视图读取／迁移 | `child.yBodyRot` → `child.bodyYaw()` |  |
| `client/YukkuriRenderer.java:277` | 视图入口／迁移 | `private static void dailyMotion(YukkuriEntity entity,float partialTick,PoseStack pose) {` → `private static void dailyMotion(YukkuriView entity,float partialTick,PoseStack pose) {` | 自有显示参数、字段或泛型改视图；同一行已有字段读取另按对应行处理。 |
| `client/YukkuriRenderer.java:283` | 行为／迁移 | `behavior.equals("boat_feed_young")` → `behavior.equals(Behaviors.BOAT_FEED_YOUNG)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriRenderer.java:283` | 行为／迁移 | `behavior.equals("boat_nuzzle")` → `behavior.equals(Behaviors.BOAT_NUZZLE)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriRenderer.java:284` | 视图入口／迁移 | `&& entity.level().getEntity(entity.aquaticChildId()) instanceof YukkuriEntity young` → `改用 vehicleYukkuri()/vehicleHat() 或 yukkuriView(id)，显式 null guard 后沿用原局部变量` | 查找与乘骑的具体对应见 A.2；保持原 && / || 短路和局部变量作用域。 |
| `client/YukkuriRenderer.java:290` | 视图读取／迁移 | `entity.yBodyRotO` → `entity.previousBodyYaw()` |  |
| `client/YukkuriRenderer.java:290` | 视图读取／迁移 | `entity.yBodyRot` → `entity.bodyYaw()` |  |
| `client/YukkuriRenderer.java:293` | 视图读取／迁移 | `entity.subspecies().assetId()` → `entity.assetId()` |  |
| `client/YukkuriRenderer.java:294` | 行为／迁移 | `behavior.equals("nuzzle")` → `behavior.equals(Behaviors.NUZZLE)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriRenderer.java:296` | 行为／迁移 | `behavior.equals("nuzzle")` → `behavior.equals(Behaviors.NUZZLE)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriRenderer.java:296` | 视图入口／迁移 | `if(behavior.equals("nuzzle")&&entity.level().getEntity(entity.nuzzlePartnerId()) instanceof YukkuriEntity peer` → `改用 vehicleYukkuri()/vehicleHat() 或 yukkuriView(id)，显式 null guard 后沿用原局部变量` | 查找与乘骑的具体对应见 A.2；保持原 && / || 短路和局部变量作用域。 |
| `client/YukkuriRenderer.java:297` | 行为／迁移 | `peer.getBehaviorId().equals("nuzzle")` → `peer.getBehaviorId().equals(Behaviors.NUZZLE)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriRenderer.java:300` | 视图读取／迁移 | `entity.subspecies().assetId()` → `entity.assetId()` |  |
| `client/YukkuriRenderer.java:301` | 视图读取／迁移 | `peer.subspecies().assetId()` → `peer.assetId()` |  |
| `client/YukkuriRenderer.java:307` | 行为／迁移 | `behavior.equals("exercise_watch")` → `behavior.equals(Behaviors.EXERCISE_WATCH)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `client/YukkuriRenderer.java:307` | 行为／迁移 | `behavior.equals("sing")` → `behavior.equals(Behaviors.SING)` | 仅替换行为操作数；同一行的动画 clip、表情与对白键保持原样。 |
| `family/FamilyFeedingContact.java:26` | 视图入口／迁移 | `public static double mouthDistance(YukkuriEntity caregiver, YukkuriEntity child) {` → `public static double mouthDistance(YukkuriView caregiver, YukkuriView child) {` | 共享纯几何读取；原实体实现视图，服务端调用自动适用，无需改其调用文件。 |
| `family/FamilyFeedingContact.java:31` | 视图入口／迁移 | `public static double minimumMouthDistance(YukkuriEntity caregiver, YukkuriEntity child) {` → `public static double minimumMouthDistance(YukkuriView caregiver, YukkuriView child) {` | 共享纯几何读取；原实体实现视图，服务端调用自动适用，无需改其调用文件。 |
| `family/FamilyFeedingContact.java:36` | 服务端几何边界／保留个例 | `public static double standingDistance(YukkuriEntity caregiver, YukkuriEntity child) {` → `原样保留` | 服务端行动、碰撞验证或未被客户端调用的入口；本行不改视图签名/字段读取。 |
| `family/FamilyFeedingContact.java:41` | 服务端几何边界／保留个例 | `public static Vec3 approachSpot(YukkuriEntity caregiver, YukkuriEntity child) {` → `原样保留` | 服务端行动、碰撞验证或未被客户端调用的入口；本行不改视图签名/字段读取。 |
| `family/FamilyFeedingContact.java:51` | 服务端几何边界／保留个例 | `public static void faceEachOther(YukkuriEntity caregiver, YukkuriEntity child) {` → `原样保留` | 服务端行动、碰撞验证或未被客户端调用的入口；本行不改视图签名/字段读取。 |
| `family/FamilyFeedingContact.java:62` | 视图边界／迁移 | `public static boolean mouthsMeet(YukkuriEntity caregiver, YukkuriEntity child) {` → `新增 visibleMouthsMeet(YukkuriView caregiver, YukkuriView child)，逐项保留原客户端查询` | 原 Entity 服务端入口保留。存活、同世界、身份、双向 hasSightTo、距离/高度/朝向门槛全部保持；后部接触另保留 congenitalForm()==BEAN_BODY 排除与 caregiver.clearColliderPath(mouth,rear)。minimumMouthDistance/horizontalForward 纯读取改视图。 |
| `family/FamilyFeedingContact.java:77` | 视图入口／迁移 | `public static Vec3 rearPoint(YukkuriEntity child) {` → `public static Vec3 rearPoint(YukkuriView child) {` | 共享纯几何读取；原实体实现视图，服务端调用自动适用，无需改其调用文件。 |
| `family/FamilyFeedingContact.java:83` | 视图边界／迁移 | `public static boolean rearContact(YukkuriEntity caregiver, YukkuriEntity child) {` → `新增 visibleRearContact(YukkuriView caregiver, YukkuriView child)，逐项保留原客户端查询` | 原 Entity 服务端入口保留。存活、同世界、身份、双向 hasSightTo、距离/高度/朝向门槛全部保持；后部接触另保留 congenitalForm()==BEAN_BODY 排除与 caregiver.clearColliderPath(mouth,rear)。minimumMouthDistance/horizontalForward 纯读取改视图。 |
| `family/FamilyFeedingContact.java:85` | 服务端几何边界／保留个例 | `caregiver.subspecies().congenital()` → `原样保留` | 服务端行动、碰撞验证或未被客户端调用的入口；本行不改视图签名/字段读取。 |
| `family/FamilyFeedingContact.java:104` | 视图入口／迁移 | `private static Vec3 horizontalForward(YukkuriEntity entity) {` → `private static Vec3 horizontalForward(YukkuriView entity) {` | 共享纯几何读取；原实体实现视图，服务端调用自动适用，无需改其调用文件。 |
| `family/FamilyFeedingContact.java:108` | 服务端几何边界／保留个例 | `private static float yawTo(YukkuriEntity from, YukkuriEntity to) {` → `原样保留` | 服务端行动、碰撞验证或未被客户端调用的入口；本行不改视图签名/字段读取。 |
| `family/FamilyFeedingContact.java:113` | 服务端几何边界／保留个例 | `private static void face(YukkuriEntity entity, float yaw) {` → `原样保留` | 服务端行动、碰撞验证或未被客户端调用的入口；本行不改视图签名/字段读取。 |
| `family/HatCarry.java:31` | 视图入口／迁移 | `public static boolean hasHat(YukkuriEntity carrier){return shape(carrier.subspecies().assetId())!=null;}` → `public static boolean hasHat(YukkuriView carrier){return shape(carrier.subspecies().assetId())!=null;}` | 共享纯几何读取；原实体实现视图，服务端调用自动适用，无需改其调用文件。 |
| `family/HatCarry.java:31` | 视图读取／迁移 | `carrier.subspecies().assetId()` → `carrier.assetId()` | 共享纯几何读取；原实体实现视图，服务端调用自动适用，无需改其调用文件。 |
| `family/HatCarry.java:32` | 视图边界／迁移 | `public static boolean riding(YukkuriEntity carrier,Entity child){return hasHat(carrier)&&child instanceof YukkuriEntity&&carrier.hasPassenger(child);}` → `新增 visibleRiding(YukkuriView carrier, YukkuriView child): hasHat(carrier) && carrier.hasPassengerId(child.getUUID())` | 原 riding(YukkuriEntity,Entity) 保留。客户端已验证子实体为视图后调用独立名，不新增交叉重载。 |
| `family/HatCarry.java:50` | 服务端几何边界／保留个例 | `public static boolean canSeat(YukkuriEntity carrier,Entity passenger){` → `原样保留` | 服务端行动、碰撞验证或未被客户端调用的入口；本行不改视图签名/字段读取。 |
| `family/HatCarry.java:51` | 服务端几何边界／保留个例 | `if(!(passenger instanceof YukkuriEntity child)&#124;&#124;carrier==child&#124;&#124;!carrier.isAlive()&#124;&#124;!child.isAlive()` → `原样保留` | 服务端行动、碰撞验证或未被客户端调用的入口；本行不改视图签名/字段读取。 |
| `family/HatCarry.java:57` | 服务端几何边界／保留个例 | `carrier.subspecies().assetId()` → `原样保留` | 服务端行动、碰撞验证或未被客户端调用的入口；本行不改视图签名/字段读取。 |
| `family/HatCarry.java:57` | 服务端几何边界／保留个例 | `child.subspecies().assetId()` → `原样保留` | 服务端行动、碰撞验证或未被客户端调用的入口；本行不改视图签名/字段读取。 |
| `family/HatCarry.java:59` | 服务端几何边界／保留个例 | `public static boolean pickUp(YukkuriEntity carrier,Player player){` → `原样保留` | 服务端行动、碰撞验证或未被客户端调用的入口；本行不改视图签名/字段读取。 |
| `family/HatCarry.java:62` | 服务端几何边界／保留个例 | `var child=carrier.level().getEntitiesOfClass(YukkuriEntity.class,carrier.getBoundingBox().inflate(1.5),` → `原样保留` | 服务端行动、碰撞验证或未被客户端调用的入口；本行不改视图签名/字段读取。 |
| `family/HatCarry.java:72` | 视图入口／迁移 | `public static String carryAction(YukkuriEntity carrier){return carrier.getAnimationId().equals("waddle")?"waddle":"idle";}` → `public static String carryAction(YukkuriView carrier){return carrier.getAnimationId().equals("waddle")?"waddle":"idle";}` | 共享纯几何读取；原实体实现视图，服务端调用自动适用，无需改其调用文件。 |
| `family/HatCarry.java:73` | 视图入口／迁移 | `public static org.joml.Matrix4f hatPose(YukkuriEntity carrier,float partial){` → `public static org.joml.Matrix4f hatPose(YukkuriView carrier,float partial){` | 共享纯几何读取；原实体实现视图，服务端调用自动适用，无需改其调用文件。 |
| `family/HatCarry.java:75` | 视图读取／迁移 | `carrier.subspecies().assetId()` → `carrier.assetId()` | 共享纯几何读取；原实体实现视图，服务端调用自动适用，无需改其调用文件。 |
| `family/HatCarry.java:77` | 视图入口／迁移 | `public static org.joml.Matrix4f riderRotation(YukkuriEntity carrier,float partial){` → `public static org.joml.Matrix4f riderRotation(YukkuriView carrier,float partial){` | 共享纯几何读取；原实体实现视图，服务端调用自动适用，无需改其调用文件。 |
| `family/HatCarry.java:78` | 视图读取／迁移 | `carrier.yBodyRotO` → `carrier.previousBodyYaw()` | 共享纯几何读取；原实体实现视图，服务端调用自动适用，无需改其调用文件。 |
| `family/HatCarry.java:78` | 视图读取／迁移 | `carrier.yBodyRot` → `carrier.bodyYaw()` | 共享纯几何读取；原实体实现视图，服务端调用自动适用，无需改其调用文件。 |
| `family/HatCarry.java:81` | 视图入口／迁移 | `public static Vec3 seatWorld(YukkuriEntity carrier,float partial){` → `public static Vec3 seatWorld(YukkuriView carrier,float partial){` | 共享纯几何读取；原实体实现视图，服务端调用自动适用，无需改其调用文件。 |
| `family/HatCarry.java:82` | 视图读取／迁移 | `carrier.subspecies().assetId()` → `carrier.assetId()` | 共享纯几何读取；原实体实现视图，服务端调用自动适用，无需改其调用文件。 |
| `family/HatCarry.java:85` | 视图读取／迁移 | `carrier.yBodyRotO` → `carrier.previousBodyYaw()` | 共享纯几何读取；原实体实现视图，服务端调用自动适用，无需改其调用文件。 |
| `family/HatCarry.java:85` | 视图读取／迁移 | `carrier.yBodyRot` → `carrier.bodyYaw()` | 共享纯几何读取；原实体实现视图，服务端调用自动适用，无需改其调用文件。 |
| `family/HatCarry.java:90` | 服务端几何边界／保留个例 | `private static boolean seatClear(YukkuriEntity carrier,YukkuriEntity child){` → `原样保留` | 服务端行动、碰撞验证或未被客户端调用的入口；本行不改视图签名/字段读取。 |
| `family/HatCarry.java:91` | 服务端几何边界／保留个例 | `child.subspecies().assetId()` → `原样保留` | 服务端行动、碰撞验证或未被客户端调用的入口；本行不改视图签名/字段读取。 |
| `family/HatCarry.java:105` | 服务端几何边界／保留个例 | `private static Vec3 landing(YukkuriEntity carrier,YukkuriEntity child){` → `原样保留` | 服务端行动、碰撞验证或未被客户端调用的入口；本行不改视图签名/字段读取。 |
| `family/HatCarry.java:116` | 服务端几何边界／保留个例 | `public static boolean putDown(YukkuriEntity carrier){` → `原样保留` | 服务端行动、碰撞验证或未被客户端调用的入口；本行不改视图签名/字段读取。 |
| `family/HatCarry.java:118` | 服务端几何边界／保留个例 | `var child=carrier.getFirstPassenger() instanceof YukkuriEntity y?y:null;if(child==null)return false;` → `原样保留` | 服务端行动、碰撞验证或未被客户端调用的入口；本行不改视图签名/字段读取。 |
| `family/HatCarry.java:123` | 服务端几何边界／保留个例 | `if(!(event.getEntity() instanceof YukkuriEntity carrier)&#124;&#124;carrier.level().isClientSide&#124;&#124;!hasHat(carrier)&#124;&#124;!carrier.isVehicle())return;` → `原样保留` | 服务端行动、碰撞验证或未被客户端调用的入口；本行不改视图签名/字段读取。 |
| `family/HatCarry.java:124` | 服务端几何边界／保留个例 | `var child=carrier.getFirstPassenger() instanceof YukkuriEntity y?y:null;if(child==null)return;` → `原样保留` | 服务端行动、碰撞验证或未被客户端调用的入口；本行不改视图签名/字段读取。 |
| `family/HatCarry.java:127` | 服务端几何边界／保留个例 | `carrier.subspecies().assetId()` → `原样保留` | 服务端行动、碰撞验证或未被客户端调用的入口；本行不改视图签名/字段读取。 |
| `family/HatCarry.java:127` | 服务端几何边界／保留个例 | `child.subspecies().assetId()` → `原样保留` | 服务端行动、碰撞验证或未被客户端调用的入口；本行不改视图签名/字段读取。 |
| `family/LiveBirthGeometry.java:36` | 服务端几何边界／保留个例 | `private static Vec3 releasePose(YukkuriEntity mother,Vec3 local){` → `原样保留` | 服务端行动、碰撞验证或未被客户端调用的入口；本行不改视图签名/字段读取。 |
| `family/LiveBirthGeometry.java:37` | 服务端几何边界／保留个例 | `mother.subspecies().assetId()` → `原样保留` | 服务端行动、碰撞验证或未被客户端调用的入口；本行不改视图签名/字段读取。 |
| `family/LiveBirthGeometry.java:156` | 服务端几何边界／保留个例 | `public static EntityDimensions bodyDimensions(YukkuriEntity entity,float growth,float belly){` → `原样保留` | 服务端行动、碰撞验证或未被客户端调用的入口；本行不改视图签名/字段读取。 |
| `family/LiveBirthGeometry.java:163` | 视图入口／迁移 | `public static Vector3f modelOutlet(YukkuriEntity mother,float belly,float contraction){` → `public static Vector3f modelOutlet(YukkuriView mother,float belly,float contraction){` | 共享纯几何读取；原实体实现视图，服务端调用自动适用，无需改其调用文件。 |
| `family/LiveBirthGeometry.java:164` | 视图读取／迁移 | `mother.subspecies().assetId()` → `mother.assetId()` | 共享纯几何读取；原实体实现视图，服务端调用自动适用，无需改其调用文件。 |
| `family/LiveBirthGeometry.java:172` | 视图入口／迁移 | `public static Vec3 outlet(YukkuriEntity mother){` → `public static Vec3 outlet(YukkuriView mother){` | 共享纯几何读取；原实体实现视图，服务端调用自动适用，无需改其调用文件。 |
| `family/LiveBirthGeometry.java:179` | 视图边界／迁移 | `public static double exitFront(YukkuriEntity mother,int index){` → `新增 visibleExitFront(YukkuriView mother, int index)，仅复制原客户端同步子分支` | 原 exitFront(YukkuriEntity,int) 保留：服务端仍读 brood；共同 modelOutlet/outlet 改视图。客户端调用改独立名，禁止用同步预览替代服务端账本。 |
| `family/LiveBirthGeometry.java:180` | 视图边界／迁移 | `if(mother.getVehicle() instanceof io.github.longshou.mcyukkuri.subspecies.HatBoatEntity){` → `新增 visibleExitFront(YukkuriView mother, int index)，仅复制原客户端同步子分支` | 原 exitFront(YukkuriEntity,int) 保留：服务端仍读 brood；共同 modelOutlet/outlet 改视图。客户端调用改独立名，禁止用同步预览替代服务端账本。 |
| `family/LiveBirthGeometry.java:196` | 服务端几何边界／保留个例 | `public static double back(YukkuriEntity y){` → `原样保留` | 服务端行动、碰撞验证或未被客户端调用的入口；本行不改视图签名/字段读取。 |
| `family/LiveBirthGeometry.java:197` | 服务端几何边界／保留个例 | `y.subspecies().assetId()` → `原样保留` | 服务端行动、碰撞验证或未被客户端调用的入口；本行不改视图签名/字段读取。 |
| `family/LiveBirthGeometry.java:201` | 服务端几何边界／保留个例 | `public static Vec3 localExit(YukkuriEntity mother,int index){` → `原样保留` | 服务端行动、碰撞验证或未被客户端调用的入口；本行不改视图签名/字段读取。 |
| `family/LiveBirthGeometry.java:209` | 服务端几何边界／保留个例 | `public static Vec3 position(YukkuriEntity mother,int index){return FamilyGeometry.toWorld(mother,releasePose(mother,localExit(mother,index)));}` → `原样保留` | 服务端行动、碰撞验证或未被客户端调用的入口；本行不改视图签名/字段读取。 |
| `family/LiveBirthGeometry.java:210` | 服务端几何边界／保留个例 | `public static boolean clear(YukkuriEntity mother,FamilyData.Child child,int index){` → `原样保留` | 服务端行动、碰撞验证或未被客户端调用的入口；本行不改视图签名/字段读取。 |
| `family/LiveBirthGeometry.java:216` | 服务端几何边界／保留个例 | `if(boat!=null)return boat.isAfloat()&&boat.youngAboard()<io.github.longshou.mcyukkuri.subspecies.HatBoatEntity.MAX_YOUNG;` → `原样保留` | 服务端行动、碰撞验证或未被客户端调用的入口；本行不改视图签名/字段读取。 |
| `family/LiveBirthGeometry.java:222` | 服务端几何边界／保留个例 | `var floated=level.getEntitiesOfClass(io.github.longshou.mcyukkuri.subspecies.HatBoatEntity.class,box.inflate(.9),hat->hat.isMoored()&&brood!=null` → `原样保留` | 服务端行动、碰撞验证或未被客户端调用的入口；本行不改视图签名/字段读取。 |
| `family/LiveBirthGeometry.java:229` | 服务端几何边界／保留个例 | `if(!level.getEntitiesOfClass(YukkuriEntity.class,box.inflate(.015),other->other!=mother&&other.isAlive()` → `原样保留` | 服务端行动、碰撞验证或未被客户端调用的入口；本行不改视图签名/字段读取。 |
| `family/LiveBirthGeometry.java:230` | 服务端几何边界／保留个例 | `&&!(other.getVehicle() instanceof io.github.longshou.mcyukkuri.subspecies.HatBoatEntity)).isEmpty())return false;` → `原样保留` | 服务端行动、碰撞验证或未被客户端调用的入口；本行不改视图签名/字段读取。 |
| `subspecies/SelfRescueHatGeometry.java:53` | 视图入口／迁移 | `public static double bodyUnit(YukkuriEntity actor){return actor.growthScale()*actor.getScale()/16;}` → `public static double bodyUnit(YukkuriView actor){return actor.growthScale()*actor.getScale()/16;}` | 共享纯几何读取；原实体实现视图，服务端调用自动适用，无需改其调用文件。 |
| `subspecies/SelfRescueHatGeometry.java:54` | 视图入口／迁移 | `public static double hatUnit(YukkuriEntity actor){return bodyUnit(actor)*HatVessel.hatMultiplier(actor.subspeciesFlags());}` → `public static double hatUnit(YukkuriView actor){return bodyUnit(actor)*HatVessel.hatMultiplier(actor.subspeciesFlags());}` | 共享纯几何读取；原实体实现视图，服务端调用自动适用，无需改其调用文件。 |
| `subspecies/SelfRescueHatGeometry.java:57` | 视图入口／迁移 | `private static Vec3 wornOrigin(YukkuriEntity actor,Plan plan){return plan.feet().add(transform(facing(plan.bodyYaw()),anchor().scale(bodyUnit(actor))));}` → `private static Vec3 wornOrigin(YukkuriView actor,Plan plan){return plan.feet().add(transform(facing(plan.bodyYaw()),anchor().scale(bodyUnit(actor))));}` | 共享纯几何读取；原实体实现视图，服务端调用自动适用，无需改其调用文件。 |
| `subspecies/SelfRescueHatGeometry.java:58` | 视图入口／迁移 | `private static Vec3 settledOrigin(YukkuriEntity actor,Plan plan){return plan.finalCentre().add(0,HatVessel.FREEBOARD*hatUnit(actor),0);}` → `private static Vec3 settledOrigin(YukkuriView actor,Plan plan){return plan.finalCentre().add(0,HatVessel.FREEBOARD*hatUnit(actor),0);}` | 共享纯几何读取；原实体实现视图，服务端调用自动适用，无需改其调用文件。 |
| `subspecies/SelfRescueHatGeometry.java:60` | 服务端几何边界／保留个例 | `public static Plan plan(YukkuriEntity actor,double waterY,Predicate<Plan> usable){` → `原样保留` | 服务端行动、碰撞验证或未被客户端调用的入口；本行不改视图签名/字段读取。 |
| `subspecies/SelfRescueHatGeometry.java:64` | 服务端几何边界／保留个例 | `actor.yBodyRot` → `原样保留` | 服务端行动、碰撞验证或未被客户端调用的入口；本行不改视图签名/字段读取。 |
| `subspecies/SelfRescueHatGeometry.java:75` | 视图入口／迁移 | `public static Pose pose(YukkuriEntity actor,Plan plan,double elapsed){` → `public static Pose pose(YukkuriView actor,Plan plan,double elapsed){` | 共享纯几何读取；原实体实现视图，服务端调用自动适用，无需改其调用文件。 |
| `subspecies/SelfRescueHatGeometry.java:90` | 服务端几何边界／保留个例 | `private static boolean validPath(YukkuriEntity actor,Plan plan){` → `原样保留` | 服务端行动、碰撞验证或未被客户端调用的入口；本行不改视图签名/字段读取。 |
| `subspecies/SelfRescueHatGeometry.java:95` | 服务端几何边界／保留个例 | `public static boolean valid(YukkuriEntity actor,Plan plan,Pose pose,Pose previous){` → `原样保留` | 服务端行动、碰撞验证或未被客户端调用的入口；本行不改视图签名/字段读取。 |
| `subspecies/SelfRescueHatGeometry.java:96` | 服务端几何边界／保留个例 | `actor.yBodyRot` → `原样保留` | 服务端行动、碰撞验证或未被客户端调用的入口；本行不改视图签名/字段读取。 |
| `subspecies/SelfRescueHatGeometry.java:98` | 服务端几何边界／保留个例 | `private static boolean clear(YukkuriEntity actor,Plan plan,Pose pose,Pose previous){` → `原样保留` | 服务端行动、碰撞验证或未被客户端调用的入口；本行不改视图签名/字段读取。 |
| `subspecies/SelfRescueHatGeometry.java:100` | 服务端几何边界／保留个例 | `actor.subspecies().assetId()` → `原样保留` | 服务端行动、碰撞验证或未被客户端调用的入口；本行不改视图签名/字段读取。 |

#### E1

| 位置 | 类别／决定 | 旧表达式 → 新入口 | 边界 |
| --- | --- | --- | --- |
| `entity/RemainsEntity.java:67` | 身体候选／保留个例 | `tag.putInt("Filling",source.body().filling());tag.putFloat("RenderScale",source.growthScale()*source.getScale());` → `保留原标量读取` | 数值展示、持久化、单项判断或公式；下方指定的合并判断除外。 |
| `entity/RemainsEntity.java:68` | 表现数值／迁移 | `source.getVariant().isHouse()?.32f:1` → `source.profile().modelScale()` |  |
| `entity/RemainsEntity.java:73` | 物种／迁移 | `source.getVariant().isHouse()` → `source.profile().houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `entity/RemainsEntity.java:74` | 关系保留／保留个例 | `if(source.family().partner()!=null)tag.putUUID("Partner",source.family().partner());` → `原样保留` | 伴侣槽位空缺/已占用或允许同伴侣的条件，不等同于双方已结伴。 |
| `entity/SpeciesTraits.java:36` | 物种个例／保留个例 | `case REIMU -> REIMU;` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `entity/SpeciesTraits.java:37` | 物种个例／保留个例 | `case MARISA -> MARISA;` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `entity/SpeciesTraits.java:38` | 物种个例／保留个例 | `case ALICE -> ALICE;` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `entity/SpeciesTraits.java:39` | 物种个例／保留个例 | `case PATCHOULI -> PATCHOULI;` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `entity/SpeciesTraits.java:40` | 物种个例／保留个例 | `case CHEN -> CHEN;` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `entity/SpeciesTraits.java:41` | 物种个例／保留个例 | `case MYON -> MYON;` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `entity/YukkuriEntity.java:233` | 行为／迁移 | `activity.equals("idle")` → `activity.equals(Behaviors.IDLE)` | 只替换行为 ID 或其构造；保留规范化位置、大小写、短路和原前后缀。 |
| `entity/YukkuriEntity.java:273` | 物种／迁移 | `variant.isPredator()` → `SpeciesProfile.of(variant).dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `entity/YukkuriEntity.java:273` | 物种个例／保留个例 | `if (level().isClientSide &#124;&#124; variant == null &#124;&#124; !variant.isPredator() &#124;&#124; predatorFlying() == flying) return;` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 同行已明确列为迁移的其他子表达式仍按其记录执行；本条只保留此类别语义。 |
| `entity/YukkuriEntity.java:293` | 物种／迁移 | `variant.isHouse()` → `SpeciesProfile.of(variant).houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `entity/YukkuriEntity.java:293` | 身体标量／保留个例 | `return (variant.isHouse() &#124;&#124; childhood != null && !childhood.canJump() &#124;&#124; body != null && body.footBurned()) && isAlive() && !isNoAi() && !isPassenger() && !isCommandPlaying()` → `保留原标量读取` | 同步编码、识别阈值或单项状态读取，未合并成健康门槛；同行另列的物种子表达式可迁移。 同行已明确列为迁移的其他子表达式仍按其记录执行；本条只保留此类别语义。 |
| `entity/YukkuriEntity.java:305` | 身体标量／保留个例 | `if (body != null && body.footBurned() && !isInWaterOrBubble()) return; // a baked sole never springs again` → `保留原标量读取` | 同步编码、识别阈值或单项状态读取，未合并成健康门槛；同行另列的物种子表达式可迁移。 |
| `entity/YukkuriEntity.java:337` | 身体标量／保留个例 | `public boolean isPanicking() { return panicTicks > 0 && (body == null &#124;&#124; !body.dying()); } // a dying body no longer bolts` → `保留原标量读取` | 同步编码、识别阈值或单项状态读取，未合并成健康门槛；同行另列的物种子表达式可迁移。 |
| `entity/YukkuriEntity.java:403` | 物种／留 R5 | `if (variant.isPredator()) predator.naturalSpawn(); else settlement.naturalSpawn();` → `原样保留` | 整套例程、住所路由、出生方式选择或按物种存读分派；E1 生命周期搬迁时同样不合并这些分支。 |
| `entity/YukkuriEntity.java:436` | 身体标量／保留个例 | `public void syncBody(){if(body==null&#124;&#124;level().isClientSide)return;entityData.set(WOUND,Math.max(body.wound(),body.burns()));entityData.set(WOUNDS,io.github.longshou.mcyukkuri.body.BodyWound.write(body.wounds()));entityData.set(FILLING,body.filling());entityData.set(BANDAGED,body.bandage()>0);entityData.set(HAIR_LOSS,body.hairLoss());entityData.set(BODY_SIGNS,body.signs());}` → `保留原标量读取` | 同步编码、识别阈值或单项状态读取，未合并成健康门槛；同行另列的物种子表达式可迁移。 |
| `entity/YukkuriEntity.java:485` | 物种个例／保留个例 | `if(family==null&#124;&#124;subspecies==null&#124;&#124;variant==null)return super.getDefaultDimensions(pose);` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `entity/YukkuriEntity.java:564` | 阈值／迁移 | `fear(player.getUUID()) < 30` → `fear(player.getUUID()) < BondLevels.SOCIAL_FEAR_LIMIT` | 仅替换比较右侧数值；不改比较方向、短路、权重或加减量。 |
| `entity/YukkuriEntity.java:598` | 物种／迁移 | `variant.isHouse()` → `SpeciesProfile.of(variant).houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `entity/YukkuriEntity.java:599` | 物种／迁移 | `variant.isHouse()` → `SpeciesProfile.of(variant).houseBody()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `entity/YukkuriEntity.java:676` | 物种／留 R5 | `entityData.set(CARRYING,(!familyPaddle.isEmpty()?familyPaddle:!family.carrying().isEmpty()?family.carrying():!independence.carrying().isEmpty()?independence.carrying():!settlement.duties().carrying().isEmpty()?settlement.duties().carrying():variant.isHouse()?house.carrying():habitat.carrying()).copy());` → `原样保留` | 整套例程、住所路由、出生方式选择或按物种存读分派；E1 生命周期搬迁时同样不合并这些分支。 |
| `entity/YukkuriEntity.java:682` | 行为／迁移 | `"first_toilet_assist"` → `Behaviors.FIRST_TOILET_ASSIST` |  |
| `entity/YukkuriEntity.java:715` | 行为／迁移 | `"caregiver"` → `Behaviors.CAREGIVER` |  |
| `entity/YukkuriEntity.java:715` | 行为／迁移 | `"newborn"` → `Behaviors.NEWBORN` |  |
| `entity/YukkuriEntity.java:720` | 行为／迁移 | `"braid_rescue_reach"` → `Behaviors.BRAID_RESCUE_REACH` |  |
| `entity/YukkuriEntity.java:721` | 行为／迁移 | `"braid_rescue_hold"` → `Behaviors.BRAID_RESCUE_HOLD` |  |
| `entity/YukkuriEntity.java:722` | 行为／迁移 | `"braid_rescue_offer"` → `Behaviors.BRAID_RESCUE_OFFER` |  |
| `entity/YukkuriEntity.java:723` | 行为／迁移 | `"braid_rescue_push"` → `Behaviors.BRAID_RESCUE_PUSH` |  |
| `entity/YukkuriEntity.java:724` | 行为／迁移 | `"braid_rescue_settle"` → `Behaviors.BRAID_RESCUE_SETTLE` |  |
| `entity/YukkuriEntity.java:725` | 行为／迁移 | `"braid_be_rescued"` → `Behaviors.BRAID_BE_RESCUED` |  |
| `entity/YukkuriEntity.java:738` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `entity/YukkuriEntity.java:743` | 行为／迁移 | `"boat_capsizing"` → `Behaviors.BOAT_CAPSIZING` |  |
| `entity/YukkuriEntity.java:743` | 行为／迁移 | `"boat_steady"` → `Behaviors.BOAT_STEADY` |  |
| `entity/YukkuriEntity.java:743` | 行为／迁移 | `"boat_rocked"` → `Behaviors.BOAT_ROCKED` |  |
| `entity/YukkuriEntity.java:743` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `entity/YukkuriEntity.java:745` | 行为／迁移 | `"hat_stumble"` → `Behaviors.HAT_STUMBLE` |  |
| `entity/YukkuriEntity.java:745` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `entity/YukkuriEntity.java:748` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `entity/YukkuriEntity.java:755` | 行为／迁移 | `"hat_board_"+phase.toLowerCase(java.util.Locale.ROOT)` → `Behaviors.hatBoard(phase.toLowerCase(java.util.Locale.ROOT))` | 只替换行为 ID 或其构造；保留规范化位置、大小写、短路和原前后缀。 |
| `entity/YukkuriEntity.java:755` | 行为／迁移 | `"hat_board_"` → `Behaviors.HAT_BOARD_PREFIX` |  |
| `entity/YukkuriEntity.java:764` | 行为／迁移 | `"water_hat_safe"` → `Behaviors.WATER_HAT_SAFE` |  |
| `entity/YukkuriEntity.java:768` | 行为／迁移 | `"hat_slipping"` → `Behaviors.HAT_SLIPPING` |  |
| `entity/YukkuriEntity.java:775` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `entity/YukkuriEntity.java:791` | 行为／迁移 | `"shell_retract"` → `Behaviors.SHELL_RETRACT` |  |
| `entity/YukkuriEntity.java:791` | 行为／迁移 | `"escape_water"` → `Behaviors.ESCAPE_WATER` |  |
| `entity/YukkuriEntity.java:791` | 行为／迁移 | `"boat_steady"` → `Behaviors.BOAT_STEADY` |  |
| `entity/YukkuriEntity.java:791` | 行为／迁移 | `"flee"` → `Behaviors.FLEE` |  |
| `entity/YukkuriEntity.java:796` | 行为／迁移 | `"shell_retract"` → `Behaviors.SHELL_RETRACT` |  |
| `entity/YukkuriEntity.java:809` | 行为／迁移 | `"escape_water"` → `Behaviors.ESCAPE_WATER` |  |
| `entity/YukkuriEntity.java:809` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `entity/YukkuriEntity.java:810` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `entity/YukkuriEntity.java:811` | 行为／迁移 | `"boat_toilet"` → `Behaviors.BOAT_TOILET` |  |
| `entity/YukkuriEntity.java:811` | 行为／迁移 | `"seek_toilet"` → `Behaviors.SEEK_TOILET` |  |
| `entity/YukkuriEntity.java:824` | 行为／迁移 | `"pairing_accept"` → `Behaviors.PAIRING_ACCEPT` |  |
| `entity/YukkuriEntity.java:825` | 行为／迁移 | `"pairing"` → `Behaviors.PAIRING` |  |
| `entity/YukkuriEntity.java:844` | 身体标量／保留个例 | `reactionTicks > 0 ? reactionExpression : body.dying() ? "blank_cry" : mind.face(body.weak() &#124;&#124; settlement.society().dejected() ? "teary" : life.hasAccessory() ? "neutral" : "surprise")));` → `保留原标量读取` | 同步编码、识别阈值或单项状态读取，未合并成健康门槛；同行另列的物种子表达式可迁移。 |
| `entity/YukkuriEntity.java:849` | 物种／迁移 | `io.github.longshou.mcyukkuri.subspecies.HatVessel.aquatic(this)` → `this.profile().aquatic()` |  |
| `entity/YukkuriEntity.java:851` | 行为／迁移 | `"tend_brood"` → `Behaviors.TEND_BROOD` |  |
| `entity/YukkuriEntity.java:851` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `entity/YukkuriEntity.java:855` | 行为／迁移 | `"tend_brood"` → `Behaviors.TEND_BROOD` |  |
| `entity/YukkuriEntity.java:855` | 行为／迁移 | `"idle"` → `Behaviors.IDLE` |  |
| `entity/YukkuriEntity.java:858` | 行为／迁移 | `behavior.startsWith("house_")` → `behavior.startsWith(Behaviors.HOUSE_PREFIX)` | 行为选择前缀；不影响同名素材前缀。 |
| `entity/YukkuriEntity.java:862` | 物种个例／保留个例 | `&& subspecies.kind() == io.github.longshou.mcyukkuri.subspecies.SubspeciesLife.Kind.AQUATIC_MARISA) action = YukkuriAction.SHAKE;` → `原样保留` | 具体变种/形态、模型选择、生成/遗传或专属动作；不把它扩大为另一种生态能力。 |
| `entity/YukkuriEntity.java:863` | 身体标量／保留个例 | `return action == YukkuriAction.IDLE && (!life.hasAccessory() &#124;&#124; body.weak() &#124;&#124; body.dying()) ? YukkuriAction.DROOP : action;` → `保留原标量读取` | 同步编码、识别阈值或单项状态读取，未合并成健康门槛；同行另列的物种子表达式可迁移。 |
| `entity/YukkuriEntity.java:868` | 行为／迁移 | `behavior.startsWith("boat_alarm_")` → `behavior.startsWith(Behaviors.BOAT_ALARM_PREFIX)` | 行为选择前缀；不影响同名素材前缀。 |
| `entity/YukkuriEntity.java:868` | 行为／迁移 | `"boat_answer_call"` → `Behaviors.BOAT_ANSWER_CALL` |  |
| `entity/YukkuriEntity.java:868` | 行为／迁移 | `"boat_calm"` → `Behaviors.BOAT_CALM` |  |
| `entity/YukkuriEntity.java:870` | 行为／迁移 | `"boat_return_called"` → `Behaviors.BOAT_RETURN_CALLED` |  |
| `entity/YukkuriEntity.java:870` | 行为／迁移 | `"boat_flee"` → `Behaviors.BOAT_FLEE` |  |
| `entity/YukkuriEntity.java:872` | 行为／迁移 | `"boat_rescue_approach"` → `Behaviors.BOAT_RESCUE_APPROACH` |  |
| `entity/YukkuriEntity.java:872` | 行为／迁移 | `"boat_rescue_young"` → `Behaviors.BOAT_RESCUE_YOUNG` |  |
| `entity/YukkuriEntity.java:872` | 行为／迁移 | `"boat_crowd_guard"` → `Behaviors.BOAT_CROWD_GUARD` |  |
| `entity/YukkuriEntity.java:872` | 行为／迁移 | `"boat_reassure"` → `Behaviors.BOAT_REASSURE` |  |
| `entity/YukkuriEntity.java:876` | 行为／迁移 | `"boat_be_carried"` → `Behaviors.BOAT_BE_CARRIED` |  |
| `entity/YukkuriEntity.java:876` | 行为／迁移 | `"hat_walk"` → `Behaviors.HAT_WALK` |  |
| `entity/YukkuriEntity.java:880` | 行为／迁移 | `"newborn_crawl"` → `Behaviors.NEWBORN_CRAWL` |  |
| `entity/YukkuriEntity.java:880` | 行为／迁移 | `"boat_toilet"` → `Behaviors.BOAT_TOILET` |  |
| `entity/YukkuriEntity.java:880` | 行为／迁移 | `"learn_jump"` → `Behaviors.LEARN_JUMP` |  |
| `entity/YukkuriEntity.java:881` | 行为／迁移 | `"first_toilet_stimulated"` → `Behaviors.FIRST_TOILET_STIMULATED` |  |
| `entity/YukkuriEntity.java:881` | 行为／迁移 | `"first_toilet_wait"` → `Behaviors.FIRST_TOILET_WAIT` |  |
| `entity/YukkuriEntity.java:882` | 行为／迁移 | `"boat_listen"` → `Behaviors.BOAT_LISTEN` |  |
| `entity/YukkuriEntity.java:883` | 行为／迁移 | `"boat_receive_care"` → `Behaviors.BOAT_RECEIVE_CARE` |  |
| `entity/YukkuriEntity.java:884` | 行为／迁移 | `"aquatic_watch_parent"` → `Behaviors.AQUATIC_WATCH_PARENT` |  |
| `entity/YukkuriEntity.java:885` | 行为／迁移 | `"aquatic_answer_family"` → `Behaviors.AQUATIC_ANSWER_FAMILY` |  |
| `entity/YukkuriEntity.java:886` | 行为／迁移 | `"boat_receive_paddle"` → `Behaviors.BOAT_RECEIVE_PADDLE` |  |
| `entity/YukkuriEntity.java:887` | 行为／迁移 | `"aquatic_paddle_practice"` → `Behaviors.AQUATIC_PADDLE_PRACTICE` |  |
| `entity/YukkuriEntity.java:887` | 行为／迁移 | `"aquatic_imitate_paddle"` → `Behaviors.AQUATIC_IMITATE_PADDLE` |  |
| `entity/YukkuriEntity.java:888` | 行为／迁移 | `"boat_follow_family"` → `Behaviors.BOAT_FOLLOW_FAMILY` |  |
| `entity/YukkuriEntity.java:888` | 行为／迁移 | `"boat_return_family"` → `Behaviors.BOAT_RETURN_FAMILY` |  |
| `entity/YukkuriEntity.java:889` | 行为／迁移 | `"boat_guard_young"` → `Behaviors.BOAT_GUARD_YOUNG` |  |
| `entity/YukkuriEntity.java:890` | 行为／迁移 | `"voyage_launch"` → `Behaviors.VOYAGE_LAUNCH` |  |
| `entity/YukkuriEntity.java:891` | 行为／迁移 | `"voyage_proud"` → `Behaviors.VOYAGE_PROUD` |  |
| `entity/YukkuriEntity.java:892` | 物种／迁移 | `io.github.longshou.mcyukkuri.subspecies.HatVessel.aquatic(this)` → `this.profile().aquatic()` |  |
| `entity/YukkuriEntity.java:1135` | 物种／留 R5 | `if (variant.isHouse()) {` → `原样保留` | 整套例程、住所路由、出生方式选择或按物种存读分派；E1 生命周期搬迁时同样不合并这些分支。 |
| `entity/YukkuriEntity.java:1153` | 物种／留 R5 | `if (variant.isPredator()) tag.put("YukkuriPredator", predator.save());` → `原样保留` | 整套例程、住所路由、出生方式选择或按物种存读分派；E1 生命周期搬迁时同样不合并这些分支。 |
| `entity/YukkuriEntity.java:1193` | 物种／留 R5 | `if (variant.isPredator()) { predator.load(tag.getCompound("YukkuriPredator")); setNoGravity(false); }` → `原样保留` | 整套例程、住所路由、出生方式选择或按物种存读分派；E1 生命周期搬迁时同样不合并这些分支。 |
| `entity/YukkuriEntity.java:1249` | 物种／迁移 | `threat.getVariant().isPredator()` → `threat.profile().dangerToYukkuri()` | 仅替换该布尔子表达式，保留 !、&&、||、相等比较及其余条件。 |
| `entity/YukkuriVariant.java:43` | 物种个例／保留个例 | `public boolean isHouse() { return house; }` → `原样保留` | 登记表或局部语义不属于可直接替换的实体能力开关。 |
| `entity/YukkuriVariant.java:44` | 物种个例／保留个例 | `public boolean isPredator() { return predator; }` → `原样保留` | 登记表或局部语义不属于可直接替换的实体能力开关。 |


## 附录 B：巨型类拆分表

### B.1 `subspecies/HatBoatEntity.java`（3193 行，拆完约剩 1500 行）

| 新类 | 移出的行 | 拥有的字段 |
| --- | --- | --- |
| H1 `HatHullMotion` | 281-471 | dipRenewed、agitation、delayedDrops |
| H2 `HatBoatLaunch`（静态） | 2236-2416 | 无；需要 accessoryToken、OWNER、SCALE、role、crew 的设置钩子 |
| H3 `HatDepartureBerth` | 736-894 | departureYield*、departureDebug、voyageYoung、voyageBerth |
| H4 `HatBoatCapsize` | 2417-2469、2600-2781 | capsizeReleased |
| H5 `HatRescuePair` | 1449-1568 | rescueOwnerHeld |
| H6 `HatBoatCarry` | 100-114、1607-1960 | CarryTrip、carryTrip、recoverCarry、搬运调试字符串 |
| H7 `HatServiceSeats` | 937-1218、1267-1339、1589-1606 | serviceSeats、returningSeats、returnRetries、ownerToiletSeat、carePlanDebug |

- **座位簿留在实体上作为中枢，改为包内可见。** 包括：
  - `seats`、`incoming`、`seatMoves`、`ownerServiceSeat`、`ownerSeatMove`、`cramped`、`leaving`；
  - `syncSeats()`、`seatOf()`、`taken`、`layoutTaken`、`frontEdgePlace`、`rescueBody`；
  - `atSeat`、`moveYoungSeat`、`moveOwnerSeat`、`walkingRoute`、`radiusOf`、`owner()`、`point()`。
- `startCapsize`（2724-2732）会重置座位，这几行原样移进实体的钩子 `freezeSeatsForCapsize()`。
- 委托类不能直接访问 `entityData`（它在 Entity 里是 `protected`），照 `crowdingData()` 的做法加包内包装方法。
- 私有的嵌套 record（SeatMove、ServiceSeat、CarryTrip、Part）改成包内可见，或移到所属的委托类里。
- `addAdditionalSaveData`、`readAdditionalSaveData`（3065-3112）留在实体里。客户端解析的同步 SEATS tag（895-914）必须逐字不变。
- **最险的接缝是 H6 和 H7：**
  - `saveCarry`（1933-1939）读服务座位的预约；
  - `tickCarry`（1865-1871）和 `returnCareSeats`（1146）互相调用；
  - 读档时从 CarryTrip 重建 `returningSeats`（3176-3180）；
  - 3048 处的 `interiorBusy` 读以上全部状态。

### B.2 `family/FamilyLife.java`（1785 行，拆完约剩 690 行）

| 新类 | 移出的行 | 拥有的字段 | 存档键 |
| --- | --- | --- | --- |
| F1 `FamilyFeeding` | 1470-1586 | carrying、foodSource、foodStore、storeContact、care*Ticks | CareFood、CareFoodTicks、CareChewTicks、CareOfferTicks |
| F2 `FamilyFlight` | 438-717 | Flight、flight、flightTick、newbornLaunch、pendingNewbornLaunch、sweepWhy | HatFlight |
| F3 `Courtship` | 149-171、964-1019、1611-1656 里的 COURT 分支 | court*、arranged、unauthorizedWith | 无 |
| F4 `CatchHat` | 257-400、1200-1253 | hatMove、hat*、cradleLinger、fetching、hatApproach、releaseWait、dutyTicks、dutyGaveUp | 无 |
| F5 `LiveDelivery` | 222-249、1061-1199、1254-1374 | delivery*、catcherWait、nestless、bump*、comfort*、welcomeTicks、hold、liveRecovery | LiveRecovery、DeliveryYaw、CatcherWait、DeliveryOutcome |

- 目标状态机（1375-1427、1598-1695）用的 `task`、`target`、`taskTicks`、`wait` 留在 FamilyLife 里；`stop()` 里调用 `feeding.reset()`。
- `active()`（198）和 `birthDuty()`（1092）要读所有委托。
- `flying()`、`laboring()`、`carrying()` 保留为委托方法。

### B.3 `life/YukkuriLife.java`（1027 行，拆完约剩 520 行）

| 新类 | 移出的行 | 拥有的字段 | 存档键 |
| --- | --- | --- | --- |
| L1 `LifeAccessory` | 218-220、469-504 | accessory、accessoryToken | Accessory、AccessoryToken |
| L2 `HatToilet` | 98-137 | hatToilet* | 无（瞬态） |
| L3 `LifeDoses` | 149-211 | spicy、spray、drugSleep、leak、flour*、addicted、returnDoseBottle | SpicyTicks 至 ReturnDoseBottle |
| L4 `LifeHome` | 774-943 | home、naturalHome | HomeDimension、HomePos、NaturalHome |
| L5 `LifeMeal` | 225-238、377-468，以及咀嚼段 328-373（改成 `tickChew()`） | mouth、meal*、feeder、sharedMeal、preparedMeal、bowlMeal | Mouth 至 Feeder |

- 生理字段（nutrition、bladder、dryness、task、planWait）留在原类，提供包内可见的修改方法；咀嚼段会写其中 6 个。
- 规划器（506-772）不动。
- 外部调用多的方法保留为委托：`hasAccessory`、`dryStand`、`homeStand`、`mealTicks`、`acceptsFood`、`beginMeal`。

### B.4 `client/YukkuriModel.java`（1268 行，无存档，拆完约剩 900 行）

| 新类 | 移出的行 | 说明 |
| --- | --- | --- |
| M1 `YukkuriModelLoader` | 412-563 | 静态的 `models`、`detachedBadges` 留在原类，通过包内可见的 `install()` 写入；`RELOAD` 改为指向 `YukkuriModelLoader::reload` |
| M2 `BadgeDraw` | 368-411 | |
| M3 `BraidPose` | 1021-1067，加上 158-163 的设置方法 | 拥有 rescue、braidCarry 字段 |
| M4 `HatDamageDraw` | 69-92、938-1019 | 拥有帽子遮罩、面片和单元格的临时缓冲。它和 `renderPosed` 共用 `triangle` 临时缓冲；面片缓存在帽子损伤或遮罩代数变化时失效。这是 B.4 里最险的一步 |
