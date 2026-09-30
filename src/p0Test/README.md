# 实体与生活游戏验收夹具

区块准备：GameTest 使用不按真实时间限速的 tick，远端夹具必须通过 `GameTestChunks.setForced` 同步准备半径 2 的 FULL 邻域，再等待实际使用的全部区块进入实体 tick 状态。此后才开始既有行为观察；不要用增加超时、任意睡眠或跳过断言掩盖加载竞态。饮水验收记录就绪 tick 和实际实体观察 tick，仍保留原有 800／300 tick 限制。

跨平台回归：模型来源指纹只容忍 LF／CRLF 换行差异，真实内容变化仍须失败。群落返回测试在计时前加载实体 tick 所需的相邻 FULL 区块，不缩短实际寻路距离；家庭测试按正常／豆形幼体分别核对碰撞宽度，并包含确定性的豆形出生用例，避免将合法的低概率亚种误报为失败。自然求偶检查等待真实受孕状态，仍受原有6000 tick总时限约束；捕食种巡飞先固定初始等待期间的起点，再交给真实AI移动，并核对飞行耗能。

`mcyukkuri.p0.suite=remains` 注册 `RemainsGameTests`、`WoundGameTests` 与 `CoreGameTests`，检查真实扣血后的轻伤／致命伤、死亡点遗体、身份与伤口保存、主动搬运、放下及铲子安葬。`RemainsPreview` 通过 `remains-setup/view/state/item` 提供本轮客户端近景。实现、固定产物与验收见[受伤与遗体](../../docs/INJURIES_AND_REMAINS.md)。

遗体专项已覆盖 19 种运行形态及偏离面部中心的实际命中；放下测试经过 `ServerPlayerGameMode.useItemOn`，分别检查生存模式、创造模式及空间受阻。客户端用 `remains-prepare-collect` 准备目标，再通过 `interact`、`block-interact` 发送实际交互包；`remains-client` 核对快照，`remains-render-check` 验证实际物品渲染器的四种显示场景，`remains-inventory` 打开物品栏。

`mcyukkuri.p0.suite=growth` 注册 `GrowthGameTests`、`FamilyGameTests` 与 `ConsumableGameTests`，检查独立成长时钟、30小时成年寿命、旧档与未出生子代迁移，以及原有家庭／胎生流程。年龄边界由受控数值推进，实际睡眠和出生由原有引擎 tick 场景覆盖；不等同于连续30小时游玩。运行入口见[成长验收](../../docs/validation/growth/current/README.md)。

`ConsumableGameTests` 由 `mcyukkuri.p0.suite=consumables` 选择，覆盖口服、自然饮水、辅助投药、固定胎次、保存收纳与普通 LIVE 出生；真实 tick 场景使用外层 `Player.interactOn`，核对一次扣除至幼体落地。`ConsumablePreview` 的 `consumable-*` 命令生成独立实机场景。隔离构建、报告与未验多人边界见 [消耗品与胎生](../../docs/CONSUMABLES_AND_BIRTH.md)。

`WaddleGameTests` 通过 `-Dmcyukkuri.p0.suite=waddle` 检查普通移动、慢走、静止和离地时的动作选择。`waddle-setup` 使用真实物理移动录制七种角色与家油搬运，`waddle-assets` 核对客户端步态、持物叠加及待机恢复；见[本轮走动记录](../../docs/validation/walking/current/README.md)。

`WoundGameTests` 通过 `-Dmcyukkuri.p0.suite=wounds` 单独注册，检查七种角色的伤口命中、保存、同步、动作挂点、有限出馅、逐处治疗、旧存档迁移和地板碰撞。`WoundPreview` 与客户端 `wound-assets` 采集实际渲染、咀嚼及同步状态。运行入口为 `scripts/run_wound_checks.py`，结果见[伤口适配记录](../../art/props/body-wounds/README.md)。

`OperationGameTests` 检查接触、工具扣除／归还、接地摩擦、饰物身份和剪切；`InstrumentGameTests` 检查五种专用器具、原胎次转移、糖的有限培养、母体死亡、枯萎及旧快照防重复出生。分别写 `operation-checks.json` 和 `instrument-checks.json`。`OperationPreview` 的 `operation-setup` 与 `operation-instruments` 布置静态挂接样本；`operation-menu` 打开真实菜单，配合客户端 `record` 的 `careAction` 参数采集连续操作。静态姿态不证明服务端行为。

本目录是独立开发模组 `mcyukkuri_p0`，仅 `p0GameTest`、`p0Preview`、`p0Server`、`p0ClientA`、`p0ClientB` 运行配置加载，普通客户端、服务器和开发 Jar 不包含它。只有设置 `mcyukkuri.p0=true` 才执行夹具。

测试世界、控制文件、日志和截图统一放项目 `.local/p0/`，不使用 `run/saves/` 或用户 Minecraft 存档。服务端仅监听 `127.0.0.1:25586`，离线身份只用于本机两个测试客户端。

夹具在 Minecraft 服务端实际 tick 上执行行为检查，并让两个真实客户端读取实体同步状态及截取渲染缓冲区。结果分别记录规则断言、联网观察和截图，不把受控调用等同于人工完整操作。

`runP0GameTest` 同时执行基础实体与 `LivingGameTests`，分别写 `server-checks.json`、`living-checks.json`。`LivingPreview` 提供隔离单人世界的生活场景、真实客户端交互和存档检查入口。当前证据见 [P1–P2 记录](../../docs/P1_P2_IMPLEMENTATION.md)。

现已增加家庭、家油伤病、对白和馅料检查。`FillingGameTests` 先等待测试区域进入实体加载状态，核对有限产出、来源认知、吞咽中止、治疗消耗、油粮、厕所保存及专用掉落恢复。`PeripheralPreview` 的 `core-props` 生成本轮周边实机检查场景；重复布置会清除该隔离台面旧物品，避免拆盆产生的掉落干扰新截图。结果与边界见[剩余移植记录](../../docs/PORT_REMAINDER.md)。

详细运行方式和此前基线见 [P0 验收记录](../../docs/P0_VALIDATION.md)。`p0Server` 专服启动前需由开发者自行接受 Minecraft EULA；脚本不代为接受。内置 GameTest 与单人集成服务器分别记录，不能替代专服验收。

`python scripts/run_human_checks.py` 只运行 `HumanGameTests` 与 `HumanExpansionGameTests`，分别输出 `human-world-checks.json` 和 `human-expansion-checks.json`。先执行 `gradlew.bat build createP0GameTestLaunchScript`；脚本每次冻结类文件与资源，在 `.local/human-world/gametest-clean/` 内新建临时世界，结束后保留日志与报告并清理临时运行目录。不能重复使用旧工厂测试世界代替该入口：仅移除实体和巢位不会清除持久化亲缘与人口记录，可能使正常繁育被人口上限拒绝。当前证据见[工厂扩展验收](../../docs/validation/human-world-expansion/README.md)。

`SettlementGameTests` 检查四种野外群体模板、亲缘、住所、成员与群落 NBT、族长失联／死亡、人数上限、区域防重、饰物识别及野草采食，并用实际服务端 tick 检查成员步行返回。结果写入 `settlement-checks.json`、`settlement-movement-checks.json`；实现边界见[群落记录](../../docs/SETTLEMENTS.md)。

`ShelterGameTests` 检查两类住所四个朝向的真实碰撞、入口寻路、床位占用、库存与 NBT、拆墙清理，以及雨淋损耗、干燥和实体屋顶防雨。环境断言受控推进住所 tick；另一项夹具使用真实服务端 AI 完成拾木棍修补、采草铺床、更换脏垫料和运粪出门，分别写入 `shelter-checks.json`、`shelter-resource-checks.json`。

指定系统属性 `mcyukkuri.p0.suite=settlements` 时只注册上述群落和住所夹具。`python scripts/run_settlement_checks.py` 使用该选项，在 `.local/settlements/gametest/` 下创建一次性世界并冻结本轮类文件、资源和 classpath；结束后保留 `acceptance.log` 与 `p0-control/` 报告，清理临时运行目录。启动前需要完成 Gradle 编译与 GameTest 启动参数生成。各行为夹具固定白天、晴天及生物破坏规则，避免旧世界状态干扰结果。

`HouseInteractionPreview` 的 `house-interaction-check` 在隔离集成服务器中核对两种家油的接触提交、中断与携带存读档；`house-interaction-setup` 布置可录制的真实目标与巢穴。受控逐步调用和自动目标录制分别记录，见[家油交互验收](../../docs/validation/house-interactions/current/README.md)。

流水线专项同样通过 `run_human_checks.py` 运行，可传入 `--work-directory .local/factory-pipeline/gametest`。`HumanExpansionGameTests` 区分最终工序夹具的库存／令牌断言与真实托盘 AI 全流程，后者通过家庭系统出生，再设置成熟年龄和营养以缩短等待；没有用测试直接调用各工序替代寻路。完整生命周期时长另按家庭机制执行。`HumanPreview` 增加 `human-tray` 与 `pipeline-floor`、`tray-front/side/back` 视角。
