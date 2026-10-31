# 代码架构

Java 路径相对 `src/main/java/io/github/longshou/mcyukkuri/`。玩法规则见[专题索引](README.md)，开发约束见 [AGENTS.md](../AGENTS.md)。

## 启动、注册与事件

| 入口 | 职责 |
| --- | --- |
| `MCYukkuri` | 安装聚落事件桥，接入模块资源、SERVER 配置与公共事件 |
| `registry/` | 注册实体、方块、物品、菜单、创造栏和声音；`ModSounds` 读取打包的 `sounds.json` |
| 各模块 `register` | 在构造期绑定一次 `DeferredRegister`；重复绑定会报错 |
| `CommonRegistration` | 公共 mod bus／NeoForge bus 接线，声明回调顺序、优先级与取消策略；登记附加模块（水生）的帽子行为、领域 Goal 与自然生成钩子，以及能力表与欲望数据的重载监听 |
| `ClientRegistration` | 客户端专用的渲染、界面、声音与生命周期接线 |

回调留在领域类；移动接线须保留上述顺序与策略。实体入场恢复考试布置；`HumanCommerce` 在完整 `super.tick()` 后执行，Post 事件再依次处理帽上乘客和野外事件发现。该顺序不保证第三方监听器的交错。

公共启动不引用客户端类；`ReadoutNetworking` 的客户端接收器由客户端安装。`Consumables` 的 `mint_leaf` → `sleep_spray` 别名属于资源读取契约。

## 实体与组件

`YukkuriEntity` 承接 Minecraft API，将生命周期、同步、受击、交互和控制委托给下列组件接缝。

| 部分 | 职责 |
| --- | --- |
| `YukkuriComponents` | 唯一组件登记与实例容器，执行 tick、存读、打断和可选 Goal 绑定 |
| `YukkuriGoals` | 构造、注册实际 Goal，绑定其显示身份；附加模块的领域 Goal 经 `entity/DomainHooks` 加入（底座不点名） |
| `YukkuriSync` / `YukkuriSyncState` | 实体同步与同步状态访问 |
| `YukkuriDamage` | `BodyHit` / `HurtResult` 受击事务，区分攻击、咬击、相扑、内部消耗和终结；先结算再死亡交接 |
| `YukkuriInteractions` | 服务端交互分派 |
| `YukkuriControl` | 推进惊慌／命令时钟、停止缩壳导航，生成本 tick 的物理接管快照 |
| `entity/component/` | 类型键、阶段、Goal 桥、同步槽与打断上下文 |

组件以 `ComponentKey<C>` 标识，`ComponentSpec<C>` 登记工厂及各阶段、顺序。已有 getter 可作为适配入口，新增组件用 `component(key)`；一个组件可登记多项存读动作。

初始化分 `EARLY`、`READY`。父构造调用 `registerGoals` 时只建立早期实例和 Goal 桥；READY 实例齐备后绑定真实 Goal，再将显示来源绑定到选择器内的同一桥。`ComponentSpec.goal` 可提供 `PresentationGoals.ComponentSource`，无显示者可省略。同优先级按注册序仲裁；`CARE_APPROACH` 在 priority 2 的 `HoldActionGoal` 与 `ComfortReceiverGoal` 之间。

`registerGoals` 内赋值字段不得有初始化器（含 `= null`），早期构造与方法引用不读取未就绪的 variant 或组件。`SyncSlot` 的 schema 静态安装，不依赖服务端实例。

## 存读与打断

- `YukkuriVersion` 为 1，缺失按 0 读取。拆分保持完整 NBT 路径、类型、写入位置和读取条件，不另包 compound。
- 加载顺序由登记表表达：`SubspeciesLife` 先加载并承载水生数据，`YukkuriLife` 承载越冬，`MindLife` 兼读 `YukkuriPet`；家庭、家油和身体旧键按存在条件读取，捕食种数据按资格存读。
- 四份调试视图受配置控制；`SukkiriExhaustion` 为瞬态，加载时清理旧键。伤口是真实数据，`Wounds` 内追加 `Shape` 保存已发生的伤因形态，疤痕和遗体沿用同一身份与锚点；`Wound` / `Burns` 只兼读，显示从伤口重建。旧 `Display*` 字段忽略，模型变更只迁移真实伤口锚点。
- 血量以原版 Health 为准；`HealthVersion` 防止 `YukkuriBody.Filling` 重复折算。遗体 `FoodRemaining` 兼读 `Filling`，中枢濒死保存一次 `PendingFood`；待吞咽收益由进食组件保存，客户端只同步嘴中显示。见[身体资源](INJURIES_AND_REMAINS.md)。
- `Interrupt` 区分受惊、受击、命令、锁定、死亡和移除；`InterruptContext` 携带上下文，`InterruptPhase` 保证通知、清理与死亡顺序。内部伤害、相扑、淋湿和死亡单次通知分别处理。

## 显示与客户端

组件 tick → `YukkuriControl.advance` 生成 `Frame` → `PresentationArbiter` 读取并发布行为、动作、表情。物理接管顺序为落水、惊慌、缩壳、发情；惊慌每个存活服务端 tick 递减一次，快照保留递减前值。没有前置接管再处理命令到期。

普通显示依次处理病态、水中、进食、睡眠／排泄、家庭持续状态、实际运行 Goal，最后是家庭／新生儿兜底。`Presentation` 选中后立即执行一次，不能预算全部候选而改变时序。命令、飞行等仍可覆盖动作。

`RoamingGoal` 保存普通闲逛与子油探险的瞬态行程，仅真实运行时参与亲子会合半径与显示，不新增存档副本。`YukkuriGroundNavigation` 沿用原版 `PathFinder`／`WalkNodeEvaluator` 与节点跟随，使用独立的64格路径预算，不改攻击感知属性。`createPath` 只计算候选路径，不能改动已启用的目标、节点进度或卡住计时；只有 `moveTo` 启用路线，同路线续走保留进度与计时，实际移动请求失败则取消旧路线。动态障碍重算必须重新搜索并保留原到达精度，体型跨过节点占位尺寸时重算；停止会取消延迟重算；节点检查避雨偏好与赤油巢边范围，`SurfaceApproach` 的最后一步沿用相同约束；其接触站位与绕行接口通过 `createApproachPath` 将互动对象占位交给原版搜索器，一次计算完整路线；候选终点必须能沿真实支撑面走到精确站位，转弯不跨过同伴。放下的帽子（帽檐矩形）与站着的油库里（碰撞箱）是足迹（`motor/geom/Detour`），目的地所属的接触对象（目的地接触范围内的身体）和正在行走的身体除外：节点处脚圆压到帽檐或近来挡过路的足迹即不可走，压到站着的身体按危险代价，留空不超过起点与目的地离它的距离（起点贴着、站位挨着都不封死，起点或目的地在其内则不计）；节点之间与最后一步都绕开直线上第一个足迹（较短一侧、留出脚圆半径，另一侧明显更短才换边，40 tick 不绕即忘），拐角都看不见或被相邻足迹占住时改走规划路线；目的地中心在帽檐下时 `Walk` 与 `SurfaceApproach` 都改到紧贴帽檐外的最近可达点，仍在该帽接触范围内，帽子周围变化时重找。行走停滞且前方是足迹时：迎面行走的两只只由编号小的让路，另一只保持路线；其余按把它当实心足迹重算、让一次（自己原地停一窗或请空闲挡路者让位）、等待的顺序，标记过期后再重算；`replans` 增加时 `Walk` 交 `Running.replanned`。真把唯一通道堵住的帽子仍是障碍，结果为 `NO_PATH`（`why=no_complete_surface_route solid=N`）。有效路线及节点进度只由导航持有，重算后继续使用导航的新路线；`YukkuriLife` 为每个实体持有独立的接近请求与最后一步交接，不另存 `Path`，不使用全局实体缓存、不进存档。`lastResult` 只读取最近已执行请求的到达／行走／暂不能规划／受阻结果，业务不读取路径节点来重复判定卡住。`routeLength` 只读计算含最后一步的完整行程，供业务比较候选。调用方继续拥有接触判定、超时和结果；`walk`／`walkAround` 返回是否到位，不能将未到位当成路径失败。相扑等需保留外力的交接使用 `stopWalking` 清除行走输入，普通原地停止使用 `halt`。

`PresentationGoals` 只筛同一已注册实例的 `WrappedGoal.isRunning()`，按真实 priority 与注册序排序；不调用 `canUse`、`canContinueToUse` 或 `plan`。计划或阶段残留不构成显示资格。真实等待可显示行为 ID 而不移动；ID 使用 `Behaviors`，动作使用 `YukkuriAction`。

客户端经 `YukkuriView`／`PlacedHatView` 读取同步值和只读派生结果，不读取服务端组件。`componentData(SyncSlot)` 是同步槽，`component(ComponentKey)` 是服务端实例；视图 tag 只读，乘员快照返回副本。

- `WoundKind` 表示治疗与漏馅类别，`WoundShape` 表示真实伤因及热损范围，`SkinDecal` 表示展示覆盖；`WoundSurface` 提供共享皮面锚点及按局部包围盒裁剪的只读三角查询，帽面接触复用现有空间索引。伤口使用实际受击位置，接受帽发遮挡，不另算可见位置。
- 人工体表展示的 `YukkuriBody.FaceMarks` 由 `BodyLife` 保存，`BodySigns.FACE_MARKS` 经既有整数同步字段传给客户端；只约束预置脏污、疤痕和油霉位置，不移动真实受击伤口或其愈合疤痕。
- `YukkuriModelLoader` 解析模型；渲染缓存默认姿态与骨骼局部变换，每次绘制仍重算实体世界矩阵。只有共面、同绑定及同纹理边界的相邻三角形可合为四边形，曲面、表情、孕肚和受损帽饰保留三角形路径。
- `HairWoundRenderer` 从所选制作源按真实锚点生成皮面／发面裁切、断发边与热损覆层，凹伤和全身干瘦共用 `WoundSurface`／`WoundProfiles` 形变；原始命中网格与存档锚点号保持稳定。`BodyCavity` 提供深伤开口与唯一中央核心，客户端 `CavityRenderer` 共用开口；服务端中枢命中只看放宽后的开口范围，不再做射线或头发／饰物遮挡判定。遗体 `CoreTaken` 控制核心是否存在。帽衬在身体伤口后的缓冲边界提交，避免使用失效的顶点消费者。
- 孕肚贴脸复用 `PregnancyFaces.Surface` 和候选索引；`PregnancyAttachments` 向显示和命中提供孕期附件位移。气泡缓存断行与尺寸，资源或语言变化时失效。

## 数据所有者与共享接口

| 所有者／接口 | 契约 |
| --- | --- |
| `BodyCondition` / `BodyConstants` | 身体快照与按用途命名的条件；繁殖、玩耍、安抚不强求同一阈值 |
| `Bonds` / `BondLevels` / `Temperament` | 亲缘、照护、伴侣、群体、好感、信任与性格规则 |
| `SpeciesProfile` / `EcologyRoutines` | 不可变物种能力（含水生种的物种事实），以及按家油／捕食种分派的被动时钟 |
| `HomeProvider` / `LifeHome` | 住所、休息、地址、搜索与保存 |
| `WaterDistress` / `EscapeWater`（`life/`） | 落水阶段、浸水、呼救与离水喘息归 `WaterDistress`，它不写身体：水中身体的浮力由 `BodyDriver.tickTravel` 在交给原版积分前施加（`motor/geom/Buoyancy.bodyLift`），脱困是优先级 0 的 `EscapeWaterGoal` 经 `IntentRunner` 运行的 `EscapeWater` 意图，手段只调用 `Swim`／`Climb`／`Right`，够得着只问 `ContactGeometry.canGrip`；这个 Goal 也是落水事件的所有者：落水冲击过去、开始自救时在 `AskLedger` 开事件，出水后关闭；规则见[基础生活](CORE_GAMEPLAY.md)与[落水自救](AQUATIC_MARISA_IMPLEMENTATION.md#落水救助与靠岸) |
| `HatCustody`（`headwear/`，挂在 `LifeAccessory`） | 帽子在哪的唯一记录：`WORN`／`DEPLOYED`／`HELD_BY_PARENT`／`LOST`；读取是纯函数，转换只有命名的几种并各记一条日志；放下的帽子的托管由 `PlacedHatEntity` 以同一令牌匹配；重载从真实托管重建。戴着时还保管夹在帽里的木棍（存档 `HatCustody.Kit`），帽子放下时木棍随帽交给 `PlacedHatEntity`（存档 `Kit`），收回时回到托管，任何时刻只有一处 |
| `ParentCarry`（`FamilyLife.carry()`） | 陆上亲代承托的唯一所有者：承托方式（嘴、帽檐、尾巴、辫梢）的容量判定、何时抬与放下的原因和保留时间；接起是共享 `Grab`（按 `ParentCarry.holdsFor` 选承托方式，够及只问 `ContactGeometry.inMouthReach`／`inBraidReach`），带回是共享 `Walk`，放下由优先级 2 的放下 Goal（家庭组件登记）运行共享 `Release`（帽檐、尾巴先放到地面，辫梢在身旁地面展开；受阻时挪一个身位重试，最多 4 次）；被动时钟只观察，落水、着火、惊慌、手术、死亡与受击等经 MOTOR 打断桥（`Release.interrupted`）在实际位置松开，每次松开都由 `ParentCarry.released` 清掉记录。孩子是亲油实体的真实乘客（`YukkuriEntity.canAddPassenger`／`positionRider` 委托 `ParentCarry.seat`，位置由该承托方式的亲油几何算出）；唯一记录是同步并随家庭存档的 `ParentCarry.HOLDS`（每只孩子的承托方式、帽檐位置与放下进度）；客户端经 `YukkuriView.passengerViews()`／`carryHolds()`／`motorAction()` 与静态 `rawSeat`／`mouthLane`／`brimSeat` 读取，不读服务端组件 |
| `Drive`／`DriveWeights`／`Desire`（`intent/`） | 一个领域的欲望竞争：领域 Goal 通过 `Drive.wants()`／`tick()` 按距上次评估经过的游戏时间检查到期，逾期后保持到下一次询问；它持有该领域唯一的 `IntentRunner`，每 10 tick（按实体 id 错开、意图结束后立即）按 `data/mcyukkuri/drive/<领域>.json` 的权重打分，承诺与持续 10 次评估的切换规则在纯函数 `DriveMath`；以 EXHAUSTED／LOST 结束的意图在其欲望所依据的稳定事实（`Desire.world`）或 200 tick 时段变化前不再参评；欲望的来由说明与日志参数只在实际写出时生成；不存档，读数登记弱引用不留住个体。读数 `aquatic.desire` 读 `Drive.status`；别的个体经 `Drive.running(body, domain)` 只读对方正在运行的意图 |
| `Ask`／`AskLedger`（`intent/`） | 求助的唯一账本：事件由其所有者开启与关闭（落水者的 `EscapeWaterGoal`、请父船划近的母油），每个（事件，帮手）只抽一次并保存概率、抽值与结果，帮手的领域 Goal 读到接受就运行自己的意图，结束后标记不再接手；只淘汰已关闭的事件，所有者死亡或离开世界时在下次读取时关闭；不存档，服务器停止时清空 |
| `aquatic/AquaticGoal`、`AquaticLife` | 水生的唯一领域 Goal（优先级 3）与共用读取：一个 `Drive` 运行出航 `SetOut`、上岸与找回帽子 `Ashore`、夜泊 `Moor`、水上 `Cruise`、孩子随行 `Follow`、帽内进食与捞食 `Provision`、帽内喂食与安抚 `Care`、救起落水的孩子 `Rescue`、送孩子到父船与划近 `Share`（WP6b）；帽内储粮经共享 `Pick` 的取物源（`HullLarder.portion`）被嘴取走，喂食的口中食物是家庭喂食同一份（`FamilyLife.holdCareFood`／`chewCareFood`／`handCareFood`）；手段由 `Plan` 按现场事实逐段给出（不存阶段），身体动作只调用共享动作（`Ferry` 汇集叼起、跳上跳下、放下），水面寻路在 `Waters`（`motor/geom/GridPath` 一格一单元）；自然生成钩子经 `DomainHooks.spawn`：尚未加入世界的个体在它自己的加入事件（最低优先级、未被取消）里放帽坐上，已加入的（聚落成员）立即坐上；`Waters` 的寻路、泊位、岸凹与木棍答案按同一问题缓存 20 tick（有界，不存实体） |
| `LiveBirth`（`family/`，基于 `intent/Choreography`、`Conductor`） | 近产的胎生／茎生是母油 `FamilyLife` 指挥的一场编排：被动时钟只推进胎次账本与节拍数据（孕程、产程、出生、落地问候时刻），不写身体、不调 `run`、不停导航。母油与接生者由优先级 2 的 `BirthGoal` 用各自的 `IntentRunner` 执行当前节拍给自己的意图（`Rendezvous`／`GoTo` 会合、经 `headwear/HatProp` 的 `PlaceProp`／`TakeProp` 放帽收帽、`Walk`／`Face` 到达合格出口、`Unload` 清空；接生者从第一只出生到收帽在帽檐外守望，这是只照看不请离帽的宿主意图 `Attend`（`HostIntent.unloads()` 为假），帽子据此让爬行幼体爬到他一侧（`SurfaceHost.attended`），离帽请求 `unloadRequested` 只来自清空与收帽）并把结果回报给编排；角色只登记在编排里，`Conductor.roleOf` 按距离查找。接收者为放下的帽、窝、地面或她乘着的自己的帽船（`OwnHull`：出口对准帽腔空处 `PlacedHatEntity.hollowPlaces`，没有会合、放帽、清空与收帽）；`Birth` 保存节拍、角色、接收者及当前孩子合格出口的宿主局部站位、朝向、目标和飞行时长；重载后复核，搜索结果不另存。`GrowthSize.birth(kind, child)` 读取受孕时保存的个体基础体型，`physicalBirth` 按既有形态倍率派生实际尺寸，茎上、出生显示与碰撞共用该尺寸，客户端只读同步视图。已抽定的出生冲量与“已发射”标志存在新生儿身上；发射、首次接触与落定、窝沿滚动和落地弹跳都在它自己的 `travel` 决策 tick 里经 `Launch`／`Gesture` 执行，出口预检与首次接触共用 `Ballistic` 的离散弹道、`ContactResolver` 的 `FlightContact` 曲面交点和 `HatGeometry.envelope`（魔理沙的身体盒与帽檐盒），出生时沿保留目标只抽一次冲量。规则见[分娩与接生](CONSUMABLES_AND_BIRTH.md#分娩动作接生与水上分娩) |
| `CareDecisionLedger`（`FamilyLife.careDecisions()`） | 亲代按孩子与需求保存照护决定；陆地喂养和首便共用接受／拒绝结果，执行路径只推进真实动作与物资交接，物理失败退让各自保存 |
| `HouseEggRelocation` / `HouseEggs` | 原窝保存整批迁卵行程、在途卵与亲代迁居交接；目标窝只预约接收空位，单枚卵的身份和孵化进度仍由 `FamilyData` 持有，搬运经过真实取放 |
| `Migration` / `SettlementTicker` | 聚落账本持有迁徙目标与阶段；族长发起，在场参与者接续推进，成员各自完成移动与新窝交接，群体到达统计只读结果 |
| `InteractionDispatch` / `CareAction` | 服务端照护入口与稳定动作 ID，菜单使用命名槽位常量 |
| `YukkuriStatus` | 指向领域读取器和受控写入方法；不复制权威数据，见[状态中枢](STATUS_HUB.md) |
| `Custody` / `CustodyData` | 饲主、角色、分配、释放与已确认 NPC 死亡；未加载或 UNKNOWN 不算死亡 |
| `FamilyData` / `YukkuriWorldData` / `SettlementData` | 家谱、对象关系／收纳、聚落账本，负责死亡、离场、过期和停止时清理。FamilyData 的 DismissedChildren 只解除记录者对孩子的照料认领；生物血缘和孩子的亲代字段保留，Bonds 的照护判断按观察者方向查询 |

`headwear/PlacedHatEntity`（实体 ID `placed_hat`）保持放下的帽子的实体交接与同步时机：乘员的局部位置与朝向存于 `Riders` 并同步到 `RIDERS`，存档走同一路径；帽体几何由 `headwear/HullProfile` 从两份 `hull_profiles/` 按 ID 惰性读取并校验字段，随 jar 固定、无数据包重载；放置时由 `HatGeometry.wornForm` 读取 `hat_vessel.json` 的 `forms[assetId]` 选择形态，`HullForm` 保存并经 `PlacedHatView.formId/profile` 同步读取，无键取现行形态，四种形态的尺寸统一由 `headwear/HatScale`（成长 × 种类系数，戴帽渲染、帽实体与物品同读）给出。帽子怎么动由行为插槽 `headwear/PlacedHatBehavior` 决定：每 tick 取第一个适用的已登记提供者，否则用陆地行为（原版重力与方块碰撞、不可推、帽面水平）。行为只移动帽子本身，经 `pose` 写同步的 roll／pitch（`frame()`、乘员定位与渲染共用，存档键 `Roll`／`Pitch`），并回答 `pushable`／`push`、空手或非储存物右击 `shake`、手持物右击 `store`、帽底占位 `obstacles`、载重 `load`、帽子离开该行为／被收回／被销毁时掉出所持物的 `release`、`steady`、`catches`（不是接生帽时帽主可乘坐）、`shelters`，以及经 `SurfaceHost` 转来的扶正 `right`、划桨 `thrust` 与帽内物品 `holds`；行为要画的帽内物品写进同步槽 `CONTENTS`，客户端经 `PlacedHatView.contents()` 与 `PlacedHatRenderer.layer` 登记的图层绘制；翻覆甩出乘员只经 `Motor.dismount`。`SurfaceHost.room` 计入 `obstacles`，支撑、身体占位与下落扫掠遵守[宿主契约](#surfacehostcontainer-与-occupancy)；`SurfaceHost.bears` 是唯一接纳谓词（帽主的幼体、在船上的帽主，刚被挤出的除外），接触落定只认它；陆地行为的接触段 `clearUnder` 把脚圆与帽檐重叠的身体经 `Motor.move` 慢慢推出（守在产位的母油只看中心）；帽边站位一律取帽檐真实范围加脚圆再加大于走位到达容差的余量（`HatGeometry.stand`／`outside`，与推出共用 `HullProfile.clearance`），不取帽实体碰撞箱；`load()` 进入乘员能力的载重系数 L，`walkGrid` 给宿主上 `Walk` 的 `GridPath` 规划（按帽底与倾斜档缓存）；帽子每 tick 用 `motor/geom/Squeeze` 分开身体列重叠的乘员，只写 `Riders`，挤出帽檐的经 `Motor.dismount`，短时间内不再接纳其接触落定。`SurfaceHost.shelters` 为真时 `YukkuriEntity` 的入水与溺水判定视该乘员为干燥。水生扩展的帽体在水里即生效：`aquatic/HullAfloat` 由 `CommonRegistration` 登记，客户端遮水 `client/aquatic/HullWaterMask` 由 `ClientRegistration` 接入，纯浮力与倾斜计算在 `motor/geom/Buoyancy`；储粮为 `aquatic/HullLarder`（存于帽子 `Motion` 的 `Portions`），绘制为 `client/aquatic/HullLarderRenderer`；其余底座包不 import `aquatic`。`Paddle` 动作已接 `thrust`；`HatProp` 的水面模式问行为插槽 `PlacedHatBehavior.carried`（提供者的 `carries`）决定能否把帽子放在水面上，`TakeProp` 嘴够到帽体即可收回，水上的帽子（`Prop.byBrim`）嘴或辫够到帽檐也可。没有动作、不在乘坐、自由下落且 3 格内有宿主的身体每 tick 用 `ContactResolver` 判定是否落进宿主（被放下的孩子落进帽腔）。水上日常意图见上表 `aquatic/AquaticGoal`。

`MindLife` 接收 `EmotionEvent`，业务效果不依赖对白播放；五属性由实体性格组件保存，`Cognition` 只查询规则。饲主查询正规化与成功入场后的世界清理分开，回滚保留未知字段。掉落拾取先复制要转交的一份，进食接纳后才缩减并写回剩余堆叠，不能转交已清空的引用。

## 身体、动作与意图层

分层只向下依赖：行为层（`Drive` → `Intent` → `Method`，以及仪式 `Choreography`）只经 `BodyDriver` 请求动作；动作层只读身体层，只经 `Motor`／`Attach` 改身体；身体层是能力、依附、宿主与容器；之下是原版移动、碰撞、骑乘和 `YukkuriGroundNavigation`。横切的显示读动作姿态、依附与编排节拍，日志前缀 `[intent][method][action][body][choreo]`（见[玩法日志](GAMEPLAY_LOGGING.md)）。包布局：

| 包 | 内容 |
| --- | --- |
| `motor/` | `Motor`、`BodyDriver`、`Attach`／`AttachState`、`BodyAbility`／`AbilityTable`／`AbilityModifier`、`Action`／`Outcome`／`Reason`／`Feasibility`、`ContactResolver`／`ContactGeometry`、`ClipMarkers` |
| `motor/action/` | 共享动作：`Walk` `Face` `Hop` `Launch` `Gesture` `Await` `Grab` `Release` `Pick` `PlaceProp` `TakeProp`，水中 `Swim` `Climb` `Push` `Right` `Paddle`，岸边 `Beckon` |
| `motor/surface/` | `SurfaceHost` `HostFrame` `Riders` `Container` `Occupancy` `BedContainer` `HostContainer` |
| `motor/geom/` | 纯 Java 几何与数学（弧线、弹道、足迹、高度场、网格寻路、绕行、浮力、挤开），单元测试在 `src/test/java` |
| `intent/` | `Intent` `Method` `Step` `IntentRunner` `Attempts` `WorldSignature`；`Choreography` `Beat` `Conductor` `Rendezvous` `GoTo`；`Drive` `Desire` `Commitment` `Ask` `AskLedger` |
| `headwear/` | 放下的帽子 `PlacedHatEntity`（`SurfaceHost`）、`HatGeometry`、`HatScale`、`HatCustody`、`HatProp` |
| `aquatic/`、`client/aquatic/` | 水生魔理沙扩展：帽船行为、储粮、水上意图与渲染；`subspecies/` 只留通用亚种 |

**设计原则**（评审时逐条对照）：能力决定动作，高度、距离、速度、时长只来自能力表、物理或片段标记；一个动作只实现一次；行为只说目标，不含坐标插值、冲量与曲线；任何限制都配至少一个能缩小它的手段；世界连续，空间与拥挤由几何现场判定，无座位表、冲突矩阵；物理是唯一真相，动作的每个结束路径都按实际位置落定；一个身体同一时刻只有一个驱动者，被动时钟只读；演出可以独有，动作不能独有（编排决定何时、什么顺序、谁、播什么，不决定身体怎么动、算不算成功）；底座不认识 DLC，DLC 只经 `AbilityModifier`、`SurfaceHost` 实现、领域 Goal、后果规则和帽子行为插槽接入，关掉 DLC 注册后陆地玩法照常；行为由真实欲望竞争驱动，演出靠调欲望权重而非写死（只有设定事实是硬规则：生殖只在岸上、水生只与水生配对、水伤数值、产程顺序）；领域之间沿用原版 Goal 优先级仲裁，不设顶层 Drive；真实失败有后果，手段用尽才失败；试玩场不用场景开关强制出生结果，有无接生帽、窝由真实初态决定。

### Motor 与依附

- `Motor` 是写身体的唯一门面：`velocity`、`move`（带碰撞）、`face`、`mount`、`dismount`（在实际脚点卸下，线程局部标志 `DISMOUNTING` 令 `getDismountLocationForPassenger` 返回乘客当前位置）、`nudge`（仅供落定推出碰撞，单次不超过 0.25 格）、`relocate`（系统有意安放身体：送货落位、手术台、取出关押、脚本仪式、入世界前生成；记 `[body] relocate`，绝不用来结束失败的行动）。`motor/` 之外对 `YukkuriEntity` 写位置、速度、朝向或骑乘由 `BODY_WRITE` 守卫拦截，`YukkuriEntity` 对原版方法的覆写只转调 `BodyDriver`，基线已清零、无许可条目。
- `Attach.of(body)` 是纯读取，依附为 `Free(inWater)`、`Airborne(action)`、`OnHost(host, local, yaw)`、`Held(carrier)`，由原版载具关系与宿主 `Riders` 推导，不单独存档、不另存“在帽里”“在搬运”标记；`Attach.settle` 按事实推导并在必要时推出碰撞。转换只由动作发起：Hop／Launch 接触帽面为 Airborne → OnHost，Grab 为 → Held，Release／跳下／滑落／甩出经 `dismount` → Free。违反“载具唯一、`Riders` 键集等于宿主乘客集、卸下前后位移不超过 0.25 格”时记 `[body] invariant` WARN，不替代码“修正”。
- 搬运者死亡、受击、受惊、命令、锁定经 MOTOR 打断桥落到当前动作的 `Release`，在实际位置落定；宿主移除、区块卸载、换维度由原版弹出乘客，身体成为 `Free` 并留在实际位置。

### SurfaceHost、Container 与 Occupancy

- `SurfaceHost`（`PlacedHatEntity` 实现）提供 `frame()`、`support`（局部高度场）、`room`（身体列占位，见下文）、`bears`（唯一接纳谓词）、`rim`、`container`、`spots`（地名，只作目标）、`riders`、`steady`、`unloadRequested` 与 `attended`（由附近身体运行的宿主意图推导，不另存）。`HostFrame` 把局部（帽单位）与世界互转，矩阵顺序与帽的 roll／pitch 一致。`Riders`（UUID → 局部坐标、朝向）是乘员位置的唯一存储，存档与 `RIDERS` 同步槽同形；`positionRider` 只做 `frame().toWorld(local)`，`OnHost` 身体的位移只写局部坐标，不写世界位置。帽内寻路为局部 `HeightGrid` 上的 `GridPath`（相邻格高差不超过 `stepUp` 才连边）。
- 帽体的 `support(local, body)`、`room`、出生落点、寻路与挤动共用 `motor/geom/BodyFootprint`：从各自 `assetId` 网格的 `*_body_surface` 身体面取竖直列（不含帽、发、辫），按实际身宽／身高缩放并转到帽坐标，偏航按档缓存；支撑取每列帽面减身体底面的最大值，整片身体投影须在帽面内。碰撞只在同一平面位置的两段身体列相交时成立；下落检查覆盖整段竖直扫掠，储粮仍按实体小件占位。此为服务端机械轮廓，不随客户端动作帧变化；脚圆用于占用分类、粗筛、网格间距与挤动步幅上界。剖面按导出倍率转换为帽单位，实体原点在龙骨；支撑三角面与轮廓边在加载时建表，檐点仅随帽姿态重算；停靠与放帽位置从同一剖面求得，戴帽动作的握点保留制作数据。
- `Container`（`BedContainer` 窝、`HostContainer` 帽腔）给出内底形状、`floorY`、`rimY`；所有 `Occupancy.of/at` 均委托纯函数 `OccupancyMath.classify`（脚圆心加圆周 8 点共 9 个探针）；帽体沿用以檐轴向最短半径为界的判定圆，保留既有地板、檐顶与檐环容差，支撑与真实碰撞仍由身体列判定；结果 `INSIDE_SUPPORTED`／`ON_RIM`／`PARTIAL`／`OUTSIDE`。“到窝”“可入睡”“在帽里”“新生儿落进窝里”只认 `INSIDE_SUPPORTED`，其余由方法继续 Walk 到休息点。足迹、容差等数值只在代码维护。

### 能力与动作

- `BodyAbility` 由体型、体力、健康、疼痛与脚伤、宿主载重系数算出跳高／跳远、台阶、走速、转身、游速、攀升、抓握、够及与搬运；全部系数来自 `data/mcyukkuri/body/ability.json`，`AbilityTable` 随 `/reload` 热加载并给出日志指纹，缺失或解析失败用代码默认值并记 WARN。`AbilityModifier` 按物种在 `modifiers` 里乘入系数；非系数的键是能力开关（`AbilityModifier.FLAGS`，如 `hat_boat`：能把戴着的帽子放到水面当船）。物种差异只经修正器，不在动作里分支。
- `Action`：`check`（纯函数）、`start`、`tick`（返回 `Running` 或 `Outcome`）、`interruptible`、`abort`（保持物理现状并 `Attach.settle`，禁止回退）、`pose`。`Running.travel` 为 `CONSUMED`（本 tick 已自行移动）或 `PASS`（交原版 travel 积分）。`Outcome` 为 `Done`／`Blocked`／`Incapable`／`Interrupted`／`Lost`，类别由 `Reason` 固定决定（`TOO_HIGH` `TOO_FAR` `NO_PATH` `NO_SUPPORT` `NO_ROOM` `NO_CONTACT` `SLIPPED` `DROPPED` `TARGET_MOVED` `NO_PROGRESS` 为 Blocked；`TOO_HEAVY` `NO_TOOL` `EXHAUSTED` 为 Incapable；`TARGET_GONE` 为 Lost），调用方不得改记；每个原因带差额，供方法选择能缩小它的手段。
- `BodyDriver`（组件 `MOTOR`）在 `travel` 内每 tick 执行当前动作，决策（Goal）先于执行，同 tick 生效。`run` 接受规则：无当前动作则接受；同一所有者在当前动作可中断时 abort 后接受；不同所有者仅当当前动作可中断且旧所有者 `active()` 为 false；否则拒绝并记限频的 `[action] refused`。所有者失活则 abort；`metric` 在 `progressWindow` 内下降不足 `progressEpsilon` 以 `Blocked(NO_PROGRESS)` 结束（`Running.replanned` 只重启计时窗口、保留最好值，`Walk` 每次至多 3 次）；得到 Outcome 后 `settle`、保存 `last`、记 `[action] end`。打断桥登记 FRIGHT、HURT、COMMAND、PET_LOCK、BURNING、DEATH：可中断动作立即 abort，不可中断的空中段由物理完成后以 `Interrupted` 结束；溺水／浸水伤害不打断任何动作（游、爬、扶正、推、划正是对它的应对），真实攻击照常打断。动作与依附不存档，读档后由事实与 Goal 重新决策。
- 被动时钟、交互回调与命令不得调用 `run`：`aiStep` 在 `super.aiStep()` 期间置位 `BodyDriver.DECIDING`，其外的 `run` 一律拒绝并记 WARN；它们只改自己组件的数据，身体由拥有该行为的 Goal 在下一次决策发起动作。
- `Hop`／`Launch` 只用原版运动学（每 tick 位置加速度，竖直 `(v−0.08)·0.98`，空中水平 ×0.91），起跳后返回 `PASS`，禁止自写积分器；弧线由 `geom/ArcSolver.lowest` 求最低可行弧，用身体盒扫掠方块与帽面，越不过障碍按“需要更高”报告差额；起跳蓄力与冲量时刻读 `ClipMarkers`。`Launch` 的冲量由调用方在预算内抽定，动作不修正冲量或速度；需观察到离地后真实接触才成功，未离地或飞行未接触超出预算均为失败。
- `ContactResolver` 落定规则（Hop、Launch 共用，每个空中 tick 在原版 move 之前）：在 `steady()` 的宿主中求位移段与 `support` 曲面的向下交点；身体中心在帽上且宿主 `bears` 时一律接触，落在交点（该处 `room` 不通过则至多偏一次到最近空处，近处没有仍落交点由挤开分开），`move`、`mount`、`riders.put`、速度清零、`Done`、记 `[body] contact`；中心不在帽上、只有侧面碰到帽檐外缘或宿主不承载时是擦边：朝帽心的水平速度乘 −0.3、竖直保留并记 `rim_bounce`；其余交给原版，`onGround` 或入水后由 `settle` 成为 `Free`。它不判后果，摔伤与溺水由各自规则按实际结果触发。没有动作、不在乘坐、自由下落且 3 格内有宿主的身体每 tick 用它判定是否落进宿主。
- `ContactGeometry` 是够得着、咬得住、托得起的唯一来源（嘴尖、辫梢、`mouthGap`、`nearestRim`、`canGrip`）；`Walk` 在地面经 `SurfaceApproach`、在宿主上经 `GridPath` 并写 `Riders`（爬行者用爬行步态与步幅时钟，并转向去处）；`Grab` 的够及方式（嘴、辫梢；选择顺序见[接生收尾](CONSUMABLES_AND_BIRTH.md#分娩动作接生与水上分娩)）决定 `Held` 的位置，抓前只按转身速度转到够得着（辫子经 `ParentCarry.braidTurn`），辫梢在 lift_start 挂上并持续到 place：孩子沿记录在承托记录里的同一路线（`ParentCarry.braided`，服务端座位与客户端共读）抬过宿主、摆到按[运输规则](CHILDHOOD_LIFECYCLE.md#子代移动与亲代运输)选定的空地再放低，被持身体的脚不低于这次搬运所起的宿主（或接起时身旁的宿主，仅在 0.3 格内）在其下的表面（`ParentCarry.pinned`，宿主随持有记录缓存，服务端座位与客户端抬起路径共用），越过帽檐而不穿过，搬运不单列动作，持有 `Held` 时 Walk／Swim 速度按双方体宽比下降；口含的抬起也由 `ParentCarry` 同步路线驱动，`Grab` 等待移动到嘴前才结束；`Release` 用共享 `Face` 将口含孩子转到可放下处，等待实际放低到位后卸下，不通过时报 `NO_ROOM` 由方法挪步，禁止 `moveTo` 落点；`PlaceProp`／`TakeProp` 放帽收帽，`release` 标记处才生成或移除实体；`Await` 必须有限期。
- 同步槽 `MOTOR_ACTION`、`MOTOR_START`、`ATTACH_KIND` 与宿主 `RIDERS`；客户端只经 `YukkuriView.motorAction()`／`motorStart()`／`attachKind()` 与 `PlacedHatView` 读取。读数分组 `body`（`body.ability` `attach` `action` `last` `intent` `method` `attempts`）。

### 意图、方法与编排

- `Intent`：`satisfied()` 只读物理事实，`deadline()` 必须有限，`methods()` 为有序手段（顺序可来自数据），`commitment()`。`Method`：`applicable()` 只读、`reduces()` 声明能缩小的原因、`next()` 给出 `Step`（`Do` 动作、`Wait` 有限等待、`Sub` 子意图、`AskFor` 求助），返回 null 但意图未满足按 `NO_PROGRESS` 处理。
- `IntentRunner` 每 tick 与存活且未移除个体的 Goal 停止交接时先读真实满足条件，满足则 `DONE`；死亡、移除、父意图结束后的子意图清理不读满足条件，停止仍为 `INTERRUPTED`；未满足的停止与已结束的失败不改记。每 tick 超期或 `Attempts` 用尽则 `EXHAUSTED` 并带全部原因与差额；当前步 `Blocked` 记入 `Attempts` 后优先换能缩小该原因的方法，`Incapable` 排除该方法，`Lost`／`Interrupted` 结束（不自动恢复，需求仍在会重新产生意图）。`Attempts` 与 `WorldSignature`（目标与宿主位置量化、阻挡实体集合、时间桶）共同保证现场未变化前不原样重试同一方法，签名变化后被排除的方法才可重选；一个意图只用一个预算，失败不改记、不双计。结束后交给领域后果规则（哭、呼救、放弃、落水）。
- `Drive`／`Desire`／`Commitment`：每个领域只注册一个 Goal，欲望强度 × 权重减代价打分，切换须超过当前意图一定幅度并持续一段时间（滞回），`Commitment` 为 `NONE`／`SOFT`／`HARD`，`HARD` 只让位于危险类欲望；权重在 `data/mcyukkuri/drive/<领域>.json`。持有者与评估节奏见上表 `Drive` 行，`Ask`／`AskLedger` 见其行。
- `Choreography`／`Beat`／`Conductor`：节拍为 `Perform`（角色执行一个意图，带最长 tick 与超时分支）、`Present`（首个角色播片段、表情、声音，其余角色执行各自意图）、`Await`（带期限与超时分支）、`Branch`（按结果选下一节拍）。指挥者是拥有编排实例的组件，其被动时钟只推进时间型节拍并只改编排数据；每 tick 复核当前节拍的角色存活、同维度、距离 48 格内，失效进入声明的分支；`report` 只接受当前 Perform 所发意图实例的结果，旧意图迟到的回报丢弃；任何节拍都不能无限等待。存档只含节拍 id、角色 UUID 与领域要求的数据，读档后 Perform 节拍从头运行。LiveBirth 见上表。
- 日志模板以代码为准，开始、结束、切换、挂载与卸下记 INFO，逐 tick 采样只在 `GameplayLog.detailEnabled()` 时记。

### 评审红线

命中任何一条退回：

1. `motor/` 之外写位置、速度、朝向或骑乘。
2. 新增预约、租约、席位、票据或 `pending` 桥接状态（尝试预算 `Attempts` 与等待动作的期限除外）。
3. 失败后原样重试；改记失败分类；同一次失败计入两个预算；Blocked 后立刻再 `run` 同一动作。
4. 为某个场景加条件分支；底座出现 `isAquatic`、`boatHome()!=null` 一类分支（经接收者、宿主、能力修正或数据表达）；`BASE_IMPORTS_DLC` 守卫：`aquatic/`、`client/aquatic/`、`registry/`、`CommonRegistration` 以外不 import `aquatic.`。
5. 行为或编排里出现跳高、时长、曲线、冲量数值（冲量分布数据除外）；新动作的高度与距离来自 `BodyAbility`，时长来自物理或 `ClipMarkers`。
6. 新的拒绝原因没有对应的手段。
7. 失败时倒放、回座、瞬移，或直接改成功状态；为接住新生儿修正冲量或速度。
8. 被动时钟移动身体、调用 `run` 或改其他实体的状态（可改本组件自己的数据，如产程进度与编排节拍）。
9. 为同一事实新增第二份存储（如单独存档 `Attach`）；客户端自行推导乘员是否在帽里。
10. `motor/geom/` 引用 `net.minecraft.*`（`GEOM_PURE` 守卫）。
11. 保留旧路径“以防万一”。

## 世界查询与协调

`LoadedGroupMembers` / `LoadedShelters` 索引已加载对象；持久事实仍在领域账本。`world/SearchBudget` 每服务器 tick 总额度 12288，FIFO 申请，租约只限本 tick；单次超额报错出队，领域兼容入口在 `settlement/SearchBudget`。

搜索保留瞬态游标，分片计量探测、调查、寻路和落位。结果为 `FOUND`、`NOT_FOUND`、`DEFERRED`；延期保留进度，不当作找不到。维度、需求、规划或依赖对象变化时重建游标，使用候选前复核其身份与可达性。

- `HabitatHomes.findHome/relocate`：按家庭容量找住所，迁居固定成人优先队列，发起者入住后继续安置家人；候选为完好洞穴，容量至少为原最低值与当前需求加 3 的较大值，只允许本次成员入住。
- `SiteLife.settle`：按起始距离固定槽位次序，逐片调查和安置。
- `PredatorNestSearch`：每片最多 4096，先已有巢再新建，候选和地面／飞行路径均计费；筑巢认领同片完成，失败回滚。
- `MigrationSearch`：每片最多 4096，先空规划站点，再最多 24 个方向列取前三个可居住地点；饥荒／耗竭优先草地多者，其余按候选序，选定后复核。

票据绑定世界、实体和搜索代次，取消按原维度和身份释放；加载时清理游标及旧票据。迁居成员首次获额度才释放旧住所，搜索结束才更新抵达地址。认屋事件按成员 UUID 保存各自游标与租约。见[聚落](SETTLEMENTS.md)。

`coordination/LeaseTable`、`DeadlineMap`、`RegionKey` 提供暂态预约、冷却和区域键，由领域释放、重试；需存档的猎食冷却、事件、考试和生产来源仍归各领域。

## 调试与扩展

`debug.entitySaveViews` 默认关闭 `YukkuriStatusView`、`YukkuriDebugGoals`、`YukkuriForageView`、`YukkuriHabitatView`；新建试玩场开启。`/yukkuri status <目标> raw` 即时查询，`status trace` 独立控制日志。`ReadoutPayload` / `ReadoutNetworking` 只向请求玩家发分页报告；实体同步使用 `SynchedEntityData` / `CompoundTag`。

新增组件在 `YukkuriComponents` 登记阶段与存读依赖；新增状态让 `StatusKey` 指向领域方法；新增行为登记 `Behaviors` 并接实际 Goal／持续状态、仲裁与资源；新增事件接对应端注册入口并保留顺序和清理。静态建议工具的许可、词法边界与命令见[脚本说明](../scripts/README.md)。

资源格式变化须保留模型数字、几何指纹、资源包覆盖与外观契约；收益以同提交完整 Jar 体积和 Java 加载／重载耗时衡量，不能用单文件压缩比例替代。Java 测量在远端或试玩环境执行。实体同步只有实际性能或内容扩展需要才迁移；可选兼容为 Jade 状态显示、农夫乐事食物和替换 `Seasons.at` 的季节模组，观察笔记已覆盖基本图鉴用途。
