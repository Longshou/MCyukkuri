# 代码架构说明

本文描述当前实现的职责、数据所有权和扩展方式。目录与素材流向见[项目地图](../PROJECT_MAP.md)，游戏规则见[文档索引](README.md)，开发约定见 [AGENTS.md](../AGENTS.md)，架构决策见[设计裁决](DESIGN_DECISIONS.md#2026-10-18-重构裁决)。

以下 Java 路径均相对 `src/main/java/io/github/longshou/mcyukkuri/`。

## 1. 启动、注册与事件

| 入口 | 职责 |
| --- | --- |
| `MCYukkuri` | 公共模组入口；安装聚落事件桥、接入资源注册、注册 SERVER 配置与公共事件入口 |
| `registry/` | 实体、方块、物品、菜单、创造栏、声音等注册；`ModSounds` 从打包的 `sounds.json` 键建立声音事件 |
| `human/HumanWorld` 等模块的 `register` | 保留各模块的 DeferredRegister 与资源接线，由公共入口调用 |
| `registry/CommonRegistration` | 显式登记公共 mod bus 与 NeoForge bus 监听器，集中声明顺序、优先级和取消事件策略 |
| `client/ClientRegistration` | 仅客户端加载的模组入口，登记渲染、界面、声音与客户端生命周期回调 |

公共和客户端事件分别在上述注册入口接线，业务回调留在领域类。新增监听器接入对应入口。

同一事件的回调顺序承载业务依赖，移动接线时保留 `CommonRegistration` 中的声明顺序、优先级和取消策略。实体入场时恢复考试布置；五属性由实体性格组件持有，不再维护独立认知镜像账本。`HumanCommerce` 在实体完整 `super.tick()` 后处理，随后实体 Post 事件依次处理帽上乘客和野外事件发现。该顺序不承诺与第三方监听维持未定义的交错。

模块资源只在模组构造期绑定一次，具体登记表见 `MCYukkuri`；重复绑定由 `DeferredRegister` 抛错。`Consumables` 保留旧物品 `mint_leaf` 到 `sleep_spray` 的别名，接线调整不改资源标识。

公共启动不引用客户端实现。`ReadoutNetworking` 的客户端接收器由客户端安装，公共网络层不直接依赖客户端界面。

## 2. 实体接缝与组件生命周期

`entity/YukkuriEntity` 承接 Minecraft 实体 API，将生命周期、同步、受击、交互和控制委托给专门的帮助类；领域状态由各组件持有。

| 部分 | 职责 |
| --- | --- |
| `YukkuriComponents` | 唯一组件登记表与实例容器，执行各阶段 tick、save、load、打断及可选 Goal 绑定 |
| `YukkuriGoals` | 既有 Goal 的构造、注册和显示身份绑定，保留真实实例与注册顺序 |
| `YukkuriSync`、`YukkuriSyncState` | 实体同步接缝与同步状态访问 |
| `YukkuriDamage` | 每次同步受击的 `BodyHit`／`HurtResult` 事务，显式区分攻击、咬伤、取食咬击、相扑、内部消耗与终结；致命回调先结算再做死亡交接 |
| `YukkuriInteractions` | 实体交互入口委托 |
| `YukkuriControl` | 推进惊慌和命令时钟、处理缩壳导航停止，产生本 tick 的物理接管快照 |
| `entity/component/` | 登记描述、类型键、初始化阶段、Goal 桥、同步槽和打断上下文 |

组件用 `ComponentKey<C>` 标识，通过 `ComponentSpec<C>` 登记工厂与动作。tick、save、load 和打断分别声明阶段与顺序。

既有组件可通过实体 getter 适配登记，新增组件由 `component(ComponentKey<C>)` 访问。一个组件可登记多项保存或加载动作，分别操作对应的 NBT 路径。

初始化分为 `EARLY` 和 `READY`：父类 `Mob` 构造期间会调用服务端 `registerGoals`，此时只能建立可用的早期实例和 Goal 桥；全部 READY 实例就绪后再绑定桥的实际 Goal。

`ComponentSpec.goal` 可同时登记按组件类型约束的 `PresentationGoals.ComponentSource`。READY 阶段先绑定实际 Goal，再把显示来源绑定到选择器内的同一个 `ComponentGoal` 桥；没有显示的 Goal 可省略该参数。接受照料的 `CARE_APPROACH` 使用这条路径，位于 priority 2 的 `HoldActionGoal` 与 `ComfortReceiverGoal` 之间。新 Goal 的同优先级插入序会影响仲裁。

在 `registerGoals` 中赋值的字段不能带初始化器，连 `= null` 也会在父构造返回后覆盖赋值。早期构造和方法引用不能读取尚未就绪的 `variant` 或其他组件。

同步槽由 `SyncSlot` 声明，schema 静态安装，独立于服务端组件实例。

### 保存与加载

存档根键 `YukkuriVersion` 当前为 1，缺失按旧版 0 处理。拆分保留原完整 NBT 路径、值类型、写入位置和旧档读取条件，不为委托另包一层 compound。

读取依赖继续由登记顺序表达：

- `SubspeciesLife` 先加载，并承载水生数据；
- `YukkuriLife` 承载越冬数据；
- `MindLife` 加载时兼读 `YukkuriPet`；
- 家庭、家油、身体旧键仍按原条件存在时读取；捕食种专属数据仍按例程资格存读。

四份调试视图由配置控制；`SukkiriExhaustion` 为瞬态并清理旧键；身体停止写派生的 `YukkuriBody.Wound` / `Burns`，保留旧档读取，并从真实伤口重建显示。

单一血量玩法额外迁移 `YukkuriBody.Filling` 到原版 Health，并写 `HealthVersion` 防止反复折算；遗体 `FoodRemaining` 兼读旧 `Filling`，中枢濒死保存一次 `PendingFood`。捕食待吞咽收益由进食组件保存，客户端只同步嘴里显示。完整契约见[血量与遗体资源](INJURIES_AND_REMAINS.md#资源结算遗体余额与旧档)。

### 打断

`Interrupt` 表达受惊、受击、命令、锁定、死亡与移除等原因，`InterruptContext` 携带上下文，`InterruptPhase` 声明通知、清理和死亡过程的先后关系。组件按原因和阶段响应，内部伤害、相扑、淋湿及死亡单次通知分别处理。

## 3. 服务端显示与客户端读取

实体完成组件 tick 后，先调用 `YukkuriControl.advance` 推进控制状态，产生本 tick 的 `Frame`；再由 `entity/presentation/PresentationArbiter` 读取快照并发布行为、动作和表情。

控制逻辑保留原接管顺序：辫救 → 帽船物理状态 → 登帽 → 自救 → 落水 → 帽船警报 → 惊慌。惊慌计数每个存活服务端 tick 在该阶段递减一次，快照保留递减前的值供惊吓／逃跑动作切换使用。没有前置接管时，再判断缩壳和发情；仅这些接管都未生效时处理命令动作到期。缩壳惊慌的导航停止也由控制逻辑执行。

显示层按接管快照发布对应表现；普通路径先处理病态、水中、进食、睡眠／排泄和家庭持续状态，再选实际运行的 Goal，最后使用家庭／新生儿兜底。玩法时钟由控制层推进。

`Presentation` 延迟发布选中的显示块，选中后立即执行一次；不能先计算全部候选而改变读取刚写入行为或动画起点的时序。

`PresentationGoals` 将显示来源绑定到已注册的同一个 Goal 实例，只筛选 `WrappedGoal.isRunning()`，按真实 priority 和同级注册序排序。不调用 `canUse`、`canContinueToUse` 或 `plan` 来探测显示。

威胁、天气、接受/主动安抚、聚落迁徙/普通返回分别绑定自己的 Goal。`ForageTrip` 的阶段和 `ForageGoal` 的计划目标可以在 Goal 停止后保留，显示资格以 Goal 的运行状态为准。

同类相食、亲代献身、宝物、外出觅食、采集、认屋、工厂工作和越冬均有对应显示来源。停止返回不接管；真实等待可显示行为 ID 而保持静止动作。命令和飞行等仍可能覆盖行为来源选出的动作。

行为 ID 使用 `entity/Behaviors`，动态 ID 通过其命名构造方法产生；字符串值保持同步与资源约定。动作枚举仍为 `YukkuriAction`。

### 同步视图

客户端展示读取 `entity/YukkuriView` 与 `subspecies/HatBoatView`。这些接口提供同步值、原生身份/几何信息和由同步值推出的显示结果，不暴露可变服务端组件。

例如亚种种类用 `subspeciesKind()`，成长用 `lifeStage()` / `growthScale()`，不调用 `subspecies()` / `family()` 取得服务端实例。`componentData(SyncSlot<T>)` 读取同步槽；`component(ComponentKey<C>)` 返回服务端组件，两者不同。

视图的部分 state tag 直接引用同步数据，调用者只读；帽船的 `seatsSnapshot` / `cargoSnapshot` / `motionSnapshot` 返回副本。

渲染器泛型和绑定实体的声音等框架接缝接收实体类型后，转为视图读取展示数据。

### 身体与外观

`BodyLife` 的真实伤口使用 `WoundKind`；霉斑、焦斑等纯展示覆盖使用 `SkinDecal`。`WoundSurface` 提供共享皮面锚点；逻辑命中位置用于伤型、头饰与身体判定。

伤口显示与逻辑共用实际受击位置；已撤回头发／帽子遮挡下的可见位置重算。旧档可选的 `Display*` 字段被忽略，模型变化只迁移实际伤口锚点。

客户端模型解析由 `YukkuriModelLoader` 承接，模型、适配、蒙皮与附加覆盖分工留在 `client/`。损坏头饰的帽衬按身体伤口绘制后的缓冲边界提交，避免持有已经切换的顶点消费者。

`YukkuriModel` 缓存静态默认姿态和各骨骼的局部变换，仍为每次绘制重新合成当前实体的世界矩阵。只有共面、相同绑定和共同纹理边界的相邻三角形才合成一次四边形提交；表情补丁、身体曲面、孕肚、鼓气、分娩及受损帽饰保留原三角形路径。

`WoundRenderer` 区分形变伤口与纯贴花：撕裂、凹伤替换身体表皮；污渍、伤疤、擦伤、灼伤和身体印记只更新贴花覆盖的三角形及法线邻域。美术资源、逻辑命中网格和存档锚点编号不变。孕肚面部贴合复用 `PregnancyFaces.Surface` 和候选三角形索引；发言气泡缓存断行与尺寸，资源重载或语言变化时失效。

## 4. 共享规则与真实数据所有者

| 接口 | 用途与限制 |
| --- | --- |
| `body/BodyCondition`、`BodyConstants` | 身体快照、按用途命名的条件和共用常量；繁殖、玩耍、安抚等资格有不同口径 |
| `social/Bonds`、`BondLevels` | 分别查询亲缘、照护、伴侣、群体、好感与信任 |
| `social/Temperament` | 共享性格规则，保留按用途命名的调用入口 |
| `entity/SpeciesProfile` | 不可变物种能力与展示系数 |
| `life/EcologyRoutine`、`EcologyRoutines` | 按家油、捕食种、水生的资格与顺序分派被动时钟 |
| `life/HomeProvider`、`LifeHome` | 按物种路由住所/休息规则，管理实际地址、搜索和保存；帽船位置由水生组件提供 |
| `subspecies/HatSeatState` | 帽船已登船座位、待登船预约、座内移动与帽主临时站位的唯一状态所有者；数组通过副本跨边界 |
| `interaction/InteractionDispatch` | 服务端照护入口与动作分派；`CareAction` 用稳定 ID，菜单按钮与槽位使用命名常量 |

身体、生活需求、家庭、住处、聚落、认知、归属和心理由各领域持有。`status/YukkuriStatus` 为外部命令/场景提供命名查询和受控写入，业务更新仍调用领域方法；详见[状态中枢](STATUS_HUB.md)。

帽船搬运、护理座、初航泊位与救援配对通过 `HatSeatState` 的预约、释放、移动和快照方法协作，不直接取得可变座位表。`HatBoatEntity` 保留实体交接与原同步时机；状态所有者生成 `SEATS` 的 `S`、`OwnerService`、`Carry`、`Crowding`、`Overcrowded` 展示结构。存档仍使用原 `ResidentSeats`、`CapsizeSeats`、`CarryPassengerSeats` 路径与类型。

`MindLife` 接收 `EmotionEvent` 命名情绪输入；对白负责选句和播放，业务效果不再依赖一句话是否播放。性格只保存五项主属性及其有界经历，当前试玩阶段不转换旧数值格式；现有阈值与含义见[情绪链](EMOTION_CHAIN.md)和[性格设计](BEHAVIOR_BALANCE_DESIGN.md#数据模型与保存)。

`human/Custody` 管理 `HumanCommerce` 的饲主身份、`Role`、分配与释放。`CustodyData` 保存已确认 NPC 死亡记录；主人暂未加载或身份 UNKNOWN 不表示死亡。查询正规化与成功入场后的世界清理分开，快照回滚保留未知字段。

`FamilyData`、`YukkuriWorldData`、`SettlementData` 是家谱、对象关系／收纳、聚落账本；智力由实体五属性持有，`Cognition` 只提供规则查询。死亡、离场、过期、服务器停止等清理由数据所有者执行。

掉落食物拾取先复制要转交的一份，进食接纳后再复制并缩减剩余量，写回 ItemEntity 或在空时移除；不能把已经变空的原堆叠引用交给进食状态。

## 5. 世界查询与暂态协调

`settlement/LoadedGroupMembers` 和 `LoadedShelters` 索引当前已加载的成员和住所；持久事实保存在领域账本中。

`world/SearchBudget` 统一计量探测、调查和寻路成本，每个服务器 tick 总额度为 12288。申请按 FIFO 排队，租约只在当前 tick 使用；超出总额的单次申请报错并移出队列。`settlement/SearchBudget` 提供兼容入口和领域成本上界。

搜索保存瞬态游标，分片申请预算。地形筛选可在一片内连续进行，调查、寻路和落位按成本上界分别安排。结果分为找到目标 `FOUND`、搜索结束未找到 `NOT_FOUND` 和等待续跑 `DEFERRED`；延期时保留进度，由调用方继续调度。

| 搜索 | 续跑与完成条件 |
| --- | --- |
| `HabitatHomes.findHome` | 游标记录维度、起点和家庭容量需求；换维度、离起点超过 12 格或需求变化时重建。使用候选前确认住所仍加载且是同一个方块实体 |
| `HabitatHomes.relocate` | 固定成人优先的家庭成员队列，发起者入住后继续安置其余成员。每片核对原住所身份与发起者归属；目标须为完好的洞穴，容量至少为原最低值与“当前家庭需求 +3”中的较大值，住户只能是本次已搬入成员 |
| `SiteLife.settle` | 按搜索开始时的距离固定住所槽位顺序，逐片检查和安置。维度、站点、规划或可用住所类别变化时重建 |
| `PredatorNestSearch` | 每片最多申请 4096 成本，先找已有巢穴，再按筑巢资格尝试新建；候选、地面路径和飞行路径均计费。筑巢与认领在同一片内完成，失败时回滚 |
| `MigrationSearch` | 每片最多申请 4096 成本，先找空置规划站点，再尝试最多 24 个方向列、收集前三个可居住地点。饥荒或资源耗竭时优先草地最多者，其他原因按候选顺序选择。选定后复核目标，中心、威胁、族长、站点或距离配置变化时重建 |

`HomeProvider` 向 `LifeHome` 传递三态结果；延期时保留找家流程。认屋事件按成员 UUID 记录已完成的搜索，各成员使用自己的租约与游标。迁居成员首次获得搜索额度后释放旧住所，后续继续安置，搜索结束才更新抵达地址。

票据绑定世界、拥有者实体和搜索代次，取消时按原维度与实体身份释放。搜索游标只在内存中保留，住所组件加载存档时清理旧进度和票据。迁居的调度与压力复核见[聚落](SETTLEMENTS.md#生活与存档)。

`coordination/LeaseTable`、`DeadlineMap`、`RegionKey` 提供运行时预约、冷却与区域键工具，由调用领域负责释放和重试。需要存档的猎食冷却、事件身份、考试日志和生产来源仍由各领域保存。

## 6. 调试、报告与静态检查

SERVER 配置 `[debug] entitySaveViews = false` 默认关闭四份保存观察视图：`YukkuriStatusView`、`YukkuriDebugGoals`、`YukkuriForageView`、`YukkuriHabitatView`。完整新建试玩场会打开它；不自动改变既有世界配置。

这些 tag 用于调试观察。`/yukkuri status <目标> raw` 可即时读取；`status trace` 单独控制状态采样/日志。显示调试记录本 tick 选中的来源。

试玩报告通过 `network/ReadoutPayload` 与 `ReadoutNetworking` 定向发送请求玩家，客户端分页查看。胎生等实体同步使用 `SynchedEntityData` / `CompoundTag`。

按需运行只读建议工具 `python scripts/check_architecture.py --check`，规则与用法见[脚本说明](../scripts/README.md)。

它检查客户端组件 getter、原始 `getPersistentData`、原始物种标志和内联全限定类型名。`scripts/architecture_exceptions.json` 按精确方法签名、完整方法 token hash、符号次数与入口理由登记许可；没有目录/类级豁免，也没有自动刷新基线。

修改许可时复核 receiver、键、读写方向和理由。工具使用有限词法分析，未知归属会报告覆盖缺口；继承、复杂泛型工厂、跨方法数据流、动态 NBT 子键及别名逃逸需人工核对。

## 7. 扩展入口

- 新组件：在 `YukkuriComponents` 登记类型、初始化阶段和生命周期动作；按需登记 Goal/同步槽，核对存读依赖。
- 新状态：在 `StatusKey` 指向领域读取器，需要外部写入时接领域方法。
- 新行为：登记 `Behaviors`，在实际 Goal/持续状态拥有者提供展示接口，再接仲裁与资源文案。
- 新事件：在公共或客户端注册入口接线，保留侧别、顺序、优先级和生命周期清理。
- 新存档：核对完整路径、类型、旧档与试玩场 DSL。
