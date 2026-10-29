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

`RoamingGoal` 保存普通闲逛与子油探险的瞬态行程，仅真实运行时参与亲子会合半径与显示，不新增存档副本。`YukkuriGroundNavigation` 沿用原版 `PathFinder`／`WalkNodeEvaluator` 与节点跟随，使用独立的64格路径预算，不改攻击感知属性。`createPath` 只计算候选路径，不能改动已启用的目标、节点进度或卡住计时；只有 `moveTo` 启用路线，同路线续走保留进度与计时，实际移动请求失败则取消旧路线。动态障碍重算必须重新搜索并保留原到达精度，体型跨过节点占位尺寸时重算；停止会取消延迟重算；节点检查避雨偏好与赤油巢边范围，`SurfaceApproach` 的最后一步沿用相同约束；其接触站位与绕行接口通过 `createApproachPath` 将互动对象占位交给原版搜索器，一次计算完整路线；候选终点必须能沿真实支撑面走到精确站位，转弯不跨过同伴。有效路线及节点进度只由导航持有，重算后继续使用导航的新路线；`YukkuriLife` 为每个实体持有独立的接近请求与最后一步交接，不另存 `Path`，不使用全局实体缓存、不进存档。`lastResult` 只读取最近已执行请求的到达／行走／暂不能规划／受阻结果，业务不读取路径节点来重复判定卡住。`routeLength` 只读计算含最后一步的完整行程，供业务比较候选。调用方继续拥有接触判定、超时和结果；`walk`／`walkAround` 返回是否到位，不能将未到位当成路径失败。相扑等需保留外力的交接使用 `stopWalking` 清除行走输入，普通原地停止使用 `halt`。

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
| `WaterDistress` / `EscapeWater`（`life/`） | 落水阶段、浸水、呼救与离水喘息归 `WaterDistress`，它不写身体：水中身体的浮力由 `BodyDriver.tickTravel` 在交给原版积分前施加（`motor/geom/Buoyancy.bodyLift`），脱困是优先级 0 的 `EscapeWaterGoal` 经 `IntentRunner` 运行的 `EscapeWater` 意图，手段只调用 `Swim`／`Climb`／`Right`，够得着只问 `ContactGeometry.canGrip`；规则见[基础生活](CORE_GAMEPLAY.md)与[落水自救](AQUATIC_MARISA_IMPLEMENTATION.md#落水救助与靠岸) |
| `HatCustody`（`headwear/`，挂在 `LifeAccessory`） | 帽子在哪的唯一记录：`WORN`／`DEPLOYED`／`HELD_BY_PARENT`／`LOST`；读取是纯函数，转换只有命名的几种并各记一条日志；放下的帽子的托管由 `PlacedHatEntity` 以同一令牌匹配；重载从真实托管重建。戴着时还保管夹在帽里的木棍（存档 `HatCustody.Kit`），帽子放下时木棍随帽交给 `PlacedHatEntity`（存档 `Kit`），收回时回到托管，任何时刻只有一处 |
| `ParentCarry`（`FamilyLife.carry()`） | 陆上亲代承托的唯一所有者：能力与座位判定、接起、放下原因与保留时间；孩子是亲油实体的真实乘客（`YukkuriEntity.canAddPassenger`／`positionRider` 委托），不另存副本；客户端经 `YukkuriView.carriedYukkuri()`／`carryMode()` 与静态座位函数读取，不读服务端组件 |
| `Drive`／`DriveWeights`／`Desire`（`intent/`） | 一个领域的欲望竞争（技术规格 11.4）：领域 Goal 每 tick 调 `Drive.tick()`，它持有该领域唯一的 `IntentRunner`，每 10 tick（按实体 id 错开、意图结束后立即）按 `data/mcyukkuri/drive/<领域>.json` 的权重打分，承诺与持续 10 次评估的切换规则在纯函数 `DriveMath`；以 EXHAUSTED／LOST 结束的意图在其欲望所依据的稳定事实（`Desire.world`）或 200 tick 时段变化前不再参评；欲望的来由说明与日志参数只在实际写出时生成；不存档，读数登记弱引用不留住个体。读数 `aquatic.desire` 读 `Drive.status` |
| `aquatic/AquaticGoal`、`AquaticLife` | 水生的唯一领域 Goal（优先级 3）与共用读取：一个 `Drive` 运行出航 `SetOut`、上岸与找回帽子 `Ashore`、夜泊 `Moor`、水上 `Cruise`、孩子随行 `Follow`；手段由 `Plan` 按现场事实逐段给出（不存阶段），身体动作只调用共享动作（`Ferry` 汇集叼起、跳上跳下、放下），水面寻路在 `Waters`（`motor/geom/GridPath` 一格一单元）；自然生成钩子经 `DomainHooks.spawn`：尚未加入世界的个体在它自己的加入事件（最低优先级、未被取消）里放帽坐上，已加入的（聚落成员）立即坐上；`Waters` 的寻路、泊位、岸凹与木棍答案按同一问题缓存 20 tick（有界，不存实体） |
| `LiveBirth`（`family/`，基于 `intent/Choreography`、`Conductor`） | 近产的胎生／茎生是母油 `FamilyLife` 指挥的一场编排：被动时钟只推进胎次账本与节拍数据（孕程、产程、出生、落地问候时刻），不写身体、不调 `run`、不停导航。母油与接生者由优先级 2 的 `BirthGoal` 用各自的 `IntentRunner` 执行当前节拍给自己的意图（`Rendezvous`／`GoTo` 会合、经 `headwear/HatProp` 的 `PlaceProp`／`TakeProp` 放帽收帽、`Face` 转向出口、`Unload` 清空）并把结果回报给编排；角色只登记在编排里，`Conductor.roleOf` 按距离查找。接收者为放下的帽、窝或地面；存档只有 `Birth` 的节拍、角色与接收者，其余重载后按真实世界重选。`GrowthSize.birth(kind, child)` 读取受孕时保存的个体基础体型，`physicalBirth` 按既有形态倍率派生实际尺寸，茎上、出生显示与碰撞共用该尺寸，客户端只读同步视图。已抽定的出生冲量与“已发射”标志存在新生儿身上；发射、首次接触与落定、窝沿滚动和落地弹跳都在它自己的 `travel` 决策 tick 里经 `Launch`／`Gesture` 执行，首次接触用 `HatGeometry.envelope`（魔理沙的身体盒与帽檐盒）。规则见[分娩与接生](CONSUMABLES_AND_BIRTH.md#分娩动作接生与水上分娩) |
| `CareDecisionLedger`（`FamilyLife.careDecisions()`） | 亲代按孩子与需求保存照护决定；陆地喂养和首便共用接受／拒绝结果，执行路径只推进真实动作与物资交接，物理失败退让各自保存 |
| `HouseEggRelocation` / `HouseEggs` | 原窝保存整批迁卵行程、在途卵与亲代迁居交接；目标窝只预约接收空位，单枚卵的身份和孵化进度仍由 `FamilyData` 持有，搬运经过真实取放 |
| `Migration` / `SettlementTicker` | 聚落账本持有迁徙目标与阶段；族长发起，在场参与者接续推进，成员各自完成移动与新窝交接，群体到达统计只读结果 |
| `InteractionDispatch` / `CareAction` | 服务端照护入口与稳定动作 ID，菜单使用命名槽位常量 |
| `YukkuriStatus` | 指向领域读取器和受控写入方法；不复制权威数据，见[状态中枢](STATUS_HUB.md) |
| `Custody` / `CustodyData` | 饲主、角色、分配、释放与已确认 NPC 死亡；未加载或 UNKNOWN 不算死亡 |
| `FamilyData` / `YukkuriWorldData` / `SettlementData` | 家谱、对象关系／收纳、聚落账本，负责死亡、离场、过期和停止时清理。FamilyData 的 DismissedChildren 只解除记录者对孩子的照料认领；生物血缘和孩子的亲代字段保留，Bonds 的照护判断按观察者方向查询 |

`headwear/PlacedHatEntity`（实体 ID `placed_hat`）保持放下的帽子的实体交接与同步时机：乘员的局部位置与朝向存于 `Riders` 并同步到 `RIDERS`，存档走同一路径；帽体几何由 `headwear/HatGeometry` 提供，四种形态的尺寸统一由 `headwear/HatScale`（成长 × 种类系数，戴帽渲染、帽实体与物品同读）给出。帽子怎么动由行为插槽 `headwear/PlacedHatBehavior` 决定：每 tick 取第一个适用的已登记提供者，否则用陆地行为（原版重力与方块碰撞、不可推、帽面水平）。行为只移动帽子本身，经 `pose` 写同步的 roll／pitch（`frame()`、乘员定位与渲染共用，存档键 `Roll`／`Pitch`），并回答 `pushable`／`push`、空手或非储存物右击 `shake`、手持物右击 `store`、帽底占位 `obstacles`、载重 `load`、帽子离开该行为／被收回／被销毁时掉出所持物的 `release`、`steady`、`catches`（不是接生帽时帽主可乘坐）、`shelters`，以及经 `SurfaceHost` 转来的扶正 `right`、划桨 `thrust` 与帽内物品 `holds`；行为要画的帽内物品写进同步槽 `CONTENTS`，客户端经 `PlacedHatView.contents()` 与 `PlacedHatRenderer.layer` 登记的图层绘制；翻覆甩出乘员只经 `Motor.dismount`。`SurfaceHost.room` 计入 `obstacles`，`load()` 进入乘员能力的载重系数 L，`walkGrid` 给宿主上 `Walk` 的 `GridPath` 规划（按帽底与倾斜档缓存）；帽子每 tick 用 `motor/geom/Squeeze` 分开脚圆重叠的乘员，只写 `Riders`，挤出帽檐的经 `Motor.dismount`，短时间内不再接纳其接触落定。`SurfaceHost.shelters` 为真时 `YukkuriEntity` 的入水与溺水判定视该乘员为干燥。水生扩展的帽体在水里即生效：`aquatic/HullAfloat` 由 `CommonRegistration` 登记，客户端遮水 `client/aquatic/HullWaterMask` 由 `ClientRegistration` 接入，纯浮力与倾斜计算在 `motor/geom/Buoyancy`；储粮为 `aquatic/HullLarder`（存于帽子 `Motion` 的 `Portions`），绘制为 `client/aquatic/HullLarderRenderer`；其余底座包不 import `aquatic`。`Paddle` 动作已接 `thrust`；`HatProp` 的水面模式问行为插槽 `PlacedHatBehavior.carried`（提供者的 `carries`）决定能否把帽子放在水面上，`TakeProp` 嘴够到帽体即可收回，水上的帽子（`Prop.byBrim`）嘴或辫够到帽檐也可。没有动作、不在乘坐、自由下落且 3 格内有宿主的身体每 tick 用 `ContactResolver` 判定是否落进宿主（被放下的孩子落进帽腔）。水上日常意图见上表 `aquatic/AquaticGoal`。

`MindLife` 接收 `EmotionEvent`，业务效果不依赖对白播放；五属性由实体性格组件保存，`Cognition` 只查询规则。饲主查询正规化与成功入场后的世界清理分开，回滚保留未知字段。掉落拾取先复制要转交的一份，进食接纳后才缩减并写回剩余堆叠，不能转交已清空的引用。

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
