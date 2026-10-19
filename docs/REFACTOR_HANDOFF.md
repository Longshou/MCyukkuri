# 代码架构说明

本文描述当前实现的职责、数据所有权和扩展边界，保留原文件路径供现有链接使用。目录与素材流向见[项目地图](../PROJECT_MAP.md)，游戏规则见[文档索引](README.md)，开发约定见 [AGENTS.md](../AGENTS.md)。

结构整理已完成实现，行为和展示仍待用户在[唯一试玩场](EXPERIMENTAL_MAP.md)确认。用户确认的 D1–D8 见[设计裁决](DESIGN_DECISIONS.md#2026-10-18-重构裁决)；旧诊断、分派表与迁移明细从 Git 历史查询。

以下 Java 路径均相对 `src/main/java/io/github/longshou/mcyukkuri/`。

## 1. 启动、注册与事件

| 入口 | 职责 |
| --- | --- |
| `MCYukkuri` | 公共模组入口；安装聚落事件桥、接入资源注册、注册 SERVER 配置与公共事件入口 |
| `registry/` | 实体、方块、物品、菜单、创造栏、声音等注册；`ModSounds` 从打包的 `sounds.json` 键建立声音事件 |
| `human/HumanWorld` 等模块的 `register` | 保留各模块的 DeferredRegister 与资源接线，由公共入口调用 |
| `registry/CommonRegistration` | 显式登记公共 mod bus 与 NeoForge bus 监听器，集中声明顺序、优先级和取消事件策略 |
| `client/ClientRegistration` | 仅客户端加载的模组入口，登记渲染、界面、声音与客户端生命周期回调 |

公共和客户端事件分别在上述注册入口接线，业务回调留在领域类。新增监听器应接入对应入口，不在领域类追加隐式自动订阅或另一份启动注册。

同一事件的回调顺序可能承载业务依赖，例如实体入场时先恢复考试布置再做认知镜像。移动接线时保留声明的顺序和参数，不把集中注册当作重排业务的许可。

公共启动不引用客户端实现。`ReadoutNetworking` 的客户端接收器由客户端安装，公共网络层不直接依赖客户端界面。

## 2. 实体接缝与组件生命周期

`entity/YukkuriEntity` 继续承接 Minecraft 实体 API，并把生命周期、同步、受击和交互委托给专门的帮助类。它不是所有领域状态的集中存储。

| 部分 | 职责 |
| --- | --- |
| `YukkuriComponents` | 唯一组件登记表与实例容器，执行各阶段 tick、save、load、打断及可选 Goal 绑定 |
| `YukkuriGoals` | 既有 Goal 的构造、注册和显示身份绑定，保留真实实例与注册顺序 |
| `YukkuriSync`、`YukkuriSyncState` | 实体同步接缝与同步状态访问 |
| `YukkuriDamage` | 每次同步受击的 `BodyHit`／`HurtResult` 事务，显式区分攻击、咬伤、取食咬击、相扑、内部消耗与终结；致命回调先结算再做死亡交接 |
| `YukkuriInteractions` | 实体交互入口委托 |
| `YukkuriControl` | 推进惊慌和命令时钟、处理缩壳导航停止，产生本 tick 的物理接管快照 |
| `entity/component/` | 登记描述、类型键、初始化阶段、Goal 桥、同步槽和打断上下文 |

组件用 `ComponentKey<C>` 标识，通过 `ComponentSpec<C>` 登记工厂与动作。tick 的阶段和顺序、save 顺序、load 顺序、打断原因/阶段/顺序各自明确；不能假设几张列表相同。

既有组件仍可通过实体 getter 适配登记；新增组件可由 `component(ComponentKey<C>)` 访问，不必为它再加实体字段和 getter。登记结构允许一个组件有多项保存或加载动作，并不要求每个组件恰好对应一个顶层 compound。

初始化分为 `EARLY` 和 `READY`：父类 `Mob` 构造期间会调用服务端 `registerGoals`，此时只能建立可用的早期实例和 Goal 桥；全部 READY 实例就绪后再绑定桥的实际 Goal。

`ComponentSpec.goal` 可同时登记按组件类型约束的 `PresentationGoals.ComponentSource`。READY 阶段先绑定实际 Goal，再把显示来源绑定到选择器内的同一个 `ComponentGoal` 桥；没有显示的 Goal 可省略该参数。接受照料的 `CARE_APPROACH` 已走这条路径，仍占原 priority 2、`HoldActionGoal` 与 `ComfortReceiverGoal` 之间的位置。登记新 Goal 时仍须核对同优先级插入序。

在 `registerGoals` 中赋值的字段不能带初始化器，连 `= null` 也会在父构造返回后覆盖赋值。早期构造和方法引用不能读取尚未就绪的 `variant` 或其他组件。

同步槽由 `SyncSlot` 声明，schema 静态安装，独立于服务端组件实例；客户端不会为了定义同步字段而构造整套服务端组件。

### 保存与加载

存档根键 `YukkuriVersion` 当前为 1，缺失按旧版 0 处理。拆分保留原完整 NBT 路径、值类型、写入位置和旧档读取条件，不为委托另包一层 compound。

读取依赖继续由登记顺序表达：

- `SubspeciesLife` 先加载，并承载水生数据；
- `YukkuriLife` 承载越冬数据；
- `MindLife` 加载时兼读 `YukkuriPet`；
- 家庭、家油、身体旧键仍按原条件存在时读取；捕食种专属数据仍按例程资格存读。

已经批准的存档变化包括：四份调试视图改为配置控制；`SukkiriExhaustion` 为瞬态并清理旧键；身体停止写派生的 `YukkuriBody.Wound` / `Burns`，保留原旧档读取和从真实伤口重建的语义，不凭旧数字新造伤口。

单一血量玩法额外迁移 `YukkuriBody.Filling` 到原版 Health，并写 `HealthVersion` 防止反复折算；遗体 `FoodRemaining` 兼读旧 `Filling`，中枢濒死保存一次 `PendingFood`。捕食待吞咽收益由进食组件保存，客户端只同步嘴里显示。完整契约见[血量与遗体资源](BODY_RESOURCE_PLAN.md)。

### 打断

`Interrupt` 表达原因，`InterruptContext` 携带上下文，`InterruptPhase` 保留通知、清理和死亡过程中的先后关系。按登记逐阶段执行，不把受惊、受击、命令、锁定、死亡与移除统一成“停掉全部组件”。

内部伤害、相扑、淋湿、死亡单次通知等仍有各自边界。修改这些语义属于玩法变化，需要独立判断。

## 3. 服务端显示与客户端读取

实体完成组件 tick 后，先调用 `YukkuriControl.advance`，再由 `entity/presentation/PresentationArbiter` 发布行为、动作和表情。控制逻辑只产生本 tick 的 `Frame`，不另存一份权威状态；显示接口没有倒计时、清除命令或停止导航的方法。

控制逻辑保留原接管顺序：辫救 → 帽船物理状态 → 登帽 → 自救 → 落水 → 帽船警报 → 惊慌。惊慌计数每个存活服务端 tick 在该阶段递减一次，快照保留递减前的值供惊吓／逃跑动作切换使用。没有前置接管时，再判断缩壳和发情；仅这些接管都未生效时处理命令动作到期。缩壳惊慌的导航停止也由控制逻辑执行。

显示层按接管快照发布对应表现；普通路径继续保留病态、水中、真实进食、睡眠／排泄和家庭持续状态，再选实际运行的 Goal，最后使用家庭／新生儿兜底。修改显示排序不会推进或停止上述玩法时钟。

`Presentation` 延迟发布选中的显示块，选中后立即执行一次；不能先计算全部候选而改变读取刚写入行为或动画起点的时序。

`PresentationGoals` 将显示来源绑定到已注册的同一个 Goal 实例，只筛选 `WrappedGoal.isRunning()`，按真实 priority 和同级注册序排序。不调用 `canUse`、`canContinueToUse` 或 `plan` 来探测显示。

威胁、天气、接受/主动安抚、聚落迁徙/普通返回分别绑定自己的 Goal；同类组件的活动字段不代表所有 Goal 都在运行。`ForageTrip` 停止后保留阶段、`ForageGoal` 持有计划目标也不能据此抢占显示。

同类相食、亲代献身、宝物、外出觅食、采集、认屋、工厂工作和越冬均有对应显示来源。停止返回不接管；真实等待可显示行为 ID 而保持静止动作。命令和飞行等仍可能覆盖行为来源选出的动作。

行为 ID 使用 `entity/Behaviors`，动态 ID 通过其命名构造方法产生；字符串值保持同步与资源约定。动作枚举仍为 `YukkuriAction`。

### 同步视图

客户端展示读取 `entity/YukkuriView` 与 `subspecies/HatBoatView`。这些接口提供同步值、原生身份/几何信息和由同步值推出的显示结果，不暴露可变服务端组件。

例如亚种种类用 `subspeciesKind()`，成长用 `lifeStage()` / `growthScale()`，不调用 `subspecies()` / `family()` 取得服务端实例。`componentData(SyncSlot<T>)` 读取同步槽；`component(ComponentKey<C>)` 返回服务端组件，两者不同。

视图的部分 state tag 是同步数据本身，不保证每项都深拷贝；帽船的 `seatsSnapshot` / `cargoSnapshot` / `motionSnapshot` 返回副本。调用者只按展示用途读取，不能据视图返回值另建业务权威。

渲染器泛型和绑定实体的声音等框架接缝仍可能接收实体类型，随后转视图；这不授权客户端调用组件 getter。

### 身体与外观

`BodyLife` 的真实伤口使用 `WoundKind`；霉斑、焦斑等纯展示覆盖使用 `SkinDecal`。`WoundSurface` 提供共享皮面锚点；逻辑命中位置用于伤型、头饰与身体判定。

`WoundVisibility` 另选显示位置：优先保留同一身体部位，只有确认整个部位被遮挡时才转向最近裸露边缘；狭窄裸露或覆盖判定不确定不能当作完全遮挡。显示锚点不反向改写逻辑命中。

客户端模型解析由 `YukkuriModelLoader` 承接，模型、适配、蒙皮与附加覆盖分工留在 `client/`。损坏头饰的帽衬按身体伤口绘制后的缓冲边界提交，避免持有已经切换的顶点消费者。

`YukkuriModel` 缓存静态默认姿态和各骨骼的局部变换，仍为每次绘制重新合成当前实体的世界矩阵。只有共面、相同绑定和共同纹理边界的相邻三角形才合成一次四边形提交；表情补丁、身体曲面、孕肚、鼓气、分娩及受损帽饰保留原三角形路径。

`WoundRenderer` 区分形变伤口与纯贴花：撕裂、凹伤替换身体表皮；污渍、伤疤、擦伤、灼伤和身体印记只更新贴花覆盖的三角形及法线邻域。美术资源、逻辑命中网格和存档锚点编号不变。孕肚面部贴合复用 `PregnancyFaces.Surface` 和候选三角形索引；发言气泡缓存断行与尺寸，资源重载或语言变化时失效。

## 4. 共享规则与真实数据所有者

| 接口 | 用途与限制 |
| --- | --- |
| `body/BodyCondition`、`BodyConstants` | 身体快照、按用途命名的条件和共用常量；繁殖、玩耍、安抚等资格有不同口径 |
| `social/Bonds`、`BondLevels` | 亲缘、照护、伴侣、群体、好感与信任查询；不把不同关系概念并成一个布尔值 |
| `social/Temperament` | 共享性格规则，保留按用途命名的调用入口 |
| `entity/SpeciesProfile` | 不可变物种能力与展示系数；不在画像中执行业务例程 |
| `life/EcologyRoutine`、`EcologyRoutines` | 家油、捕食种、水生的既有被动时钟分派，保留各例程原来的入口资格与顺序 |
| `life/HomeProvider`、`LifeHome` | 住所/休息政策路由与实际地址、搜索、保存；水生帽船不被偷偷改成另一份陆地 HomePos |
| `subspecies/HatSeatState` | 帽船已登船座位、待登船预约、座内移动与帽主临时站位的唯一状态所有者；数组通过副本跨边界 |
| `interaction/InteractionDispatch` | 服务端照护入口与动作分派；`CareAction` 用稳定 ID，菜单按钮与槽位使用命名常量 |

身体、生活需求、家庭、住处、聚落、认知、归属和心理继续由各领域持有。`status/YukkuriStatus` 为外部命令/场景提供命名查询和受控写入，不接管领域的正常业务写入；详见[状态中枢](STATUS_HUB.md)。

帽船搬运、护理座、初航泊位与救援配对通过 `HatSeatState` 的预约、释放、移动和快照方法协作，不直接取得可变座位表。`HatBoatEntity` 保留实体交接与原同步时机；状态所有者生成 `SEATS` 的 `S`、`OwnerService`、`Carry`、`Crowding`、`Overcrowded` 展示结构。存档仍使用原 `ResidentSeats`、`CapsizeSeats`、`CarryPassengerSeats` 路径与类型。

`MindLife` 接收 `EmotionEvent` 命名情绪输入；对白负责选句和播放，业务效果不再依赖一句话是否播放。性格保存读取兼容旧版本，现有阈值与含义见[情绪链](EMOTION_CHAIN.md)和[性格设计](PERSONALITY_DESIGN.md)。

`human/Custody` 管理 `HumanCommerce` 的饲主身份、`Role`、分配与释放。`CustodyData` 保存已确认 NPC 死亡记录；主人暂未加载或身份 UNKNOWN 不表示死亡。查询正规化与成功入场后的世界清理分开，快照回滚保留未知字段。

`FamilyData`、`YukkuriWorldData`、`SettlementData`、`CognitionData` 等仍是各自领域账本。死亡、离场、过期、服务器停止等清理按数据所有者执行，不能因另建中枢而复制一份状态。

掉落食物拾取先复制要转交的一份，进食接纳后再复制并缩减剩余量，写回 ItemEntity 或在空时移除；不能把已经变空的原堆叠引用交给进食状态。

## 5. 世界查询与暂态协调

`settlement/LoadedGroupMembers` 和 `LoadedShelters` 索引当前已加载的成员/住所。它们不代表未加载世界的全量事实，也不取代持久领域账本。

`world/SearchBudget` 是搜索预算的唯一账本，按探测、调查、寻路成本申请同 tick 租约。`settlement/SearchBudget` 是兼容入口和领域成本上界，不是第二个预算器。

每个服务器 tick 的总额度为 12288 逻辑成本。单次声明超过总额的申请会报错并移出等待队列，不再因当 tick 尚未使用额度而整笔放行。找家、换家和规划站点安置保存各自的瞬态进度，按可容纳的片段申请；纯地形筛选失败可在一片内继续扫描，昂贵的调查、寻路和落位按上界分开处理。

搜索结果区分 `FOUND`、`NOT_FOUND`、`DEFERRED`；预算延期不等于确实无家可找，不得在延期时擦掉地址或触发失败兜底。票据绑定实际世界、拥有者和代次，离场与服务端生命周期清理不能省略。

捕食种的 `PredatorNestSearch` 同样使用共享账本：先查已有巢穴，再按原资格尝试筑巢；候选探测、地面与飞行路径均计费。`HomeProvider` 透传三态结果，`LifeHome` 在延期时保留搜索而不运行无家可找的兜底。筑巢与认领在同一片内成功或回滚，不把半成品留到下一 tick；游标不入存档。

迁居选址由 `MigrationSearch` 保存瞬态游标：先查空置规划站点，再按原范围尝试最多 24 个方向列，从前三个可居住地点中选择草地最多的一处。每片最多申请 4096 逻辑成本，读地形和评分分片执行，完成前再验证目标。搜索不持有跨 tick 租约；中心、威胁、族长身份或距离配置改变时废弃旧游标。手动发起的搜索保留强制标记，后续由实际族长继续。

认屋事件按成员 UUID 记录本轮已结束的搜索，未完成成员仍使用自己的找家租约与游标，避免把整户搜索合成超额申请。迁居抵达在首次获准搜索后才释放旧住所，延期保留抵达进度；成员不会因更新了迁居地址而跳过尚未结束的安置。

`coordination/LeaseTable`、`DeadlineMap`、`RegionKey` 提供资源预约、纯冷却及区域键工具。具体预约含义、释放和重试仍属调用领域，不用通用表取代带存档语义的状态。

例如同类相食猎食冷却按已加载 tick 递减并存档；事件冲突携带对方身份；考试日志和生产来源是持久结果。这些都不能因为形式像期限或计数而统一迁入纯运行时表。

## 6. 调试、报告与静态检查

SERVER 配置 `[debug] entitySaveViews = false` 默认关闭四份保存观察视图：`YukkuriStatusView`、`YukkuriDebugGoals`、`YukkuriForageView`、`YukkuriHabitatView`。完整新建试玩场会打开它；不自动改变既有世界配置。

这些 tag 不是业务存档权威或客户端同步协议。`/yukkuri status <目标> raw` 可即时读取；`status trace` 控制状态采样/日志，和保存视图开关分开。显示调试记录本 tick 真正胜出的来源，不重新运行来源求值。

试玩报告通过 `network/ReadoutPayload` 与 `ReadoutNetworking` 定向发送请求玩家，客户端分页查看。胎生等实体同步仍使用 `SynchedEntityData` / `CompoundTag`，不意味着已完成全部同步迁移。

按需运行 `python scripts/check_architecture.py --check`。这是 Python 标准库只读建议工具，不接入 CI、pre-commit 或合并门槛；不替代远端编译或用户试玩。规则与用法见[脚本说明](../scripts/README.md)。

它检查客户端组件 getter、原始 `getPersistentData`、原始物种标志和内联全限定类型名。`scripts/architecture_exceptions.json` 按精确方法签名、完整方法 token hash、符号次数与入口理由登记许可；没有目录/类级豁免，也没有自动刷新基线。

修改许可须人工复核 receiver、键、读写方向和理由，不能只更新 hash 消除诊断。有限词法分析不能完整证明继承、复杂泛型工厂、跨方法数据流、动态 NBT 子键或别名逃逸；未知归属会报告覆盖缺口。Custody 入参和 Cognition 序列化子键仍需人工核对。

## 7. 扩展与后续边界

- 新组件：在 `YukkuriComponents` 登记类型、初始化阶段和生命周期动作；按需登记 Goal/同步槽，核对存读依赖。
- 新状态：在 `StatusKey` 指向真实领域读取器，需要外部受控写入时接领域方法，不复制一份字段。
- 新行为：登记 `Behaviors`，在实际 Goal/持续状态拥有者提供展示接口，再接仲裁与资源文案。
- 新事件：在公共或客户端注册入口接线，保留侧别、顺序、优先级和生命周期清理。
- 新存档：核对完整路径、类型、旧档与试玩场 DSL，不以静态字符串计数代替兼容审查。

受击事务与[血量与遗体资源](BODY_RESOURCE_PLAN.md)已接入：`YukkuriDamage` 用 `BodyHit` 明确受击类型，由 `BodyLife.onHurt` 一次结算伤口、生命损失和咬食收益；致命回调先结算再交接死亡。该改动包含旧档迁移，玩法表现仍待试玩。

水生魔理沙后续方案和 2026-10-19 第二轮试玩反馈仍见[待办](TODO.md)与[设计裁决](DESIGN_DECISIONS.md)，不把待办中要求删除或重做的机制提前视为已实施。

后续玩法状态仍以[完善计划](DEVELOPMENT_PLAN.md)为准。主线的连续表现、多人同步与观感等待用户在唯一试玩场确认；固定 `playground` 分支的观察不能作为当前 main 已通过试玩的证明。
