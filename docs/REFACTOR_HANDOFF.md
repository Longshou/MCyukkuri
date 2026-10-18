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
interface PresentationSource { @Nullable Presentation present(YukkuriEntity e, Motion m); }   // 返回 null 表示不接管
record Presentation(String behavior, YukkuriAction action, OptionalLong startTick, String expression) {}
```

**E3 照搬现在的顺序，不改：**

1. 前置接管：辫救 → 帽船摇晃与拥挤 → 登帽 → 水生自救 → 落水 → 帽船警报 → 惊慌 → 缩壳 → 发情演出 → 命令动作到期。
2. 行为链：mind → reactions → predator → sanction → 在水中 → life → careApproach → childhood → aquaticLife → groupInteraction → headwear → playLife → woundComfort → dailyLife → WildEvents → family → house → habitat → independence → settlement.duties → settlement → social → family（兜底）。
3. 动作选择：命令 → 帽船 → 水中 → 行走 → 群体 → 静止。
4. 起始 tick 校正（求偶、分娩与助产），最后定表情。

调试视图新增一项“本 tick 胜出的来源”。

**E4（⚠，D5）：**
- 顺序与目标优先级对齐。现在群体（5）和玩耍（6）排在头饰（3）和安抚（4）前面；日常（5）排在家油和栖居（4）前面。
- Cannibalism、SelfOffering、TreasureLife、ForageTrip、ForageGoal、HomeClaimGoal、FactoryWorkGoal 补上 `activity()` 并接入显示链。
- 接上 `WinteringLife.activity()`（`season/WinteringLife.java:38`）。

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

**W1-M1 至 M6（Sonnet · high，6 个并行）**：W1-API 合入后开工。每个包按目录划分拥有文件，分 4 个提交：

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
- E4：只给 `social/Cannibalism`、`SelfOffering`、`TreasureLife`、`settlement/ForageTrip`、`ForageGoal`、`event/HomeClaimGoal`、`human/FactoryWorkGoal` 补展示接口并接线。

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
| S5 | 拆分 `HabitatLife`；把 `Season`、`Seasons` 从 `settlement/` 移到 `season/`，解开 settlement、event、season 之间的循环依赖 | Sonnet · high，Opus 复核 | M3 |

S1a、S2a、S3、S4、S5 可以同时跑；S1b、S2b 分别接在各自的 a 之后。

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

**R8b 世界层协调（⚠）：**
- 统一预约和冷却的原语（现有约 10 套实现、3 种区域键），并只保留一个“是否被占用”的判断。
- 成员未加载时暂停的场景和认屋加超时（`event/HomeClaim.java:151`、`WildEvents.java:187-194`）。现在它们会永久锁住成员。
- 族长、继任、迁徙审议这些群级逻辑改由一个服务端 ticker 驱动。

**R9 启动接线：**
- `MCYukkuri` 里的 22 个 `addListener` 和 16 个 `@EventBusSubscriber` 统一成一种写法。
- 空的 `init(){}` 触发改成显式注册。
- `HumanCommerce.tickAnimal` 移进实体的 tick。
- 三份配置的分工写进文档。

**并行关系：**
- R1、R3、R5、R7 可按实际文件锁并行；R8a 与 R1 都需要 `SettlementData`，R8a 等 R1 合入后再开工。R8a 的两个实施者先分清各自的完整文件清单。
- R2 排在 R1 之后，因为两者都改关系。
- R6 排在 R7 之后，因为两者都改 `interaction/` 和 `pet/`。
- R8b 排在 R8a 之后。
- R9 排在 E6 后，明确允许修改 `human/HumanCommerce` 和最终组件登记表以迁移 `tickAnimal`；与 R2 的实体调用点按文件锁串行。
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
- 第 1 波：只有 W1-API 加两处。
- 第 2 波：E 包的 agent 独占 `entity/`。第 3、4 波的包如果需要改实体，把需求写进 E 包 agent 的待办，不自己改。
- 第 2 波结束后，实体门面与四个委托仍按文件分别授权，门面保持单写者。R2 的 `showReaction` 和 R9 的 tick 接线依次获得对应文件锁。

**集中试玩：** 每一波结束后，用户按该波各 PR 提交信息里列的场景集中试玩一次。某个 PR 有问题就整个 PR 回退，再派原来的包去修。

**进度表**（做完后填状态和 PR 号）：

| 包 | 状态 | PR |
| --- | --- | --- |
| W0-1 | 已合入，CI 与独立复核通过；待用户试玩 | #49 |
| W0-2 | 已合入，CI 与独立复核通过；待用户试玩 | #53 |
| W0-3 | 已合入，CI 与独立复核通过；待用户试玩 | #51 |
| W0-4 | 已合入，CI 与独立复核通过；待用户试玩 | #50 |
| W1-API | 实施中：Astra high，持有实体接口接入锁 | |
| W1-M1…M6、W1-R、W1-D | 等待 W1-API 合入 | |
| E1…E6 | 未开始 | |
| S1a、S1b、S2a、S2b、S3、S4、S5 | 未开始 | |
| R1…R9 | 未开始 | |
| Z1、Z2、Z3 | 未开始 | |

## 6. 派发简报模板

调度方复制下面的简报，填上包 ID：

> 你负责 docs/REFACTOR_HANDOFF.md 里的工作包 **<包ID>**。先读 AGENTS.md、docs/DESIGN_DECISIONS.md，以及本页第 2 节和 <包ID> 那一节。只改该节列出的拥有文件；需要改别的文件时停下回报。在 `.worktrees/<包ID>` 里基于 origin/main 开分支 `refactor/<包ID>`，按节内步骤提交，推分支并开 PR。CI 通过后回报统筹；需要独立复核的包由统筹另派复核，统筹统一合入 main 并更新进度。回报：改了什么、存档键比对结果、需要试玩的场景、没做的事。

复核简报：

> 复核 PR #<号>（工作包 <包ID>）。按 docs/REFACTOR_HANDOFF.md 第 2 节和附录 A 逐处核对三件事：替换前后语义等价、存档键不变、只改了拥有文件。列出所有不等价的地方（文件:行、原表达式、新表达式）；没有问题就批准。

## 附录 A：迁移清单

由 W1-API 填写：每一行是“旧表达式（文件:行）→ 新方法或常量”，按目录分组，标出属于哪个 M 包。

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
