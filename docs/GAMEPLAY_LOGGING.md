# 玩法日志

服务端日志入口、前缀和调试开关。具体字段以代码为准，问题与运行版本只记[待办](TODO.md)。

## 公共入口

记录要求见 [日志留痕](../AGENTS.md#日志留痕)。服务端用 `status/GameplayLog` 在实际业务边界记录，客户端不重复记：

| 方法 | 用途与上下文 |
| --- | --- |
| `event(entity, category, message, values...)` | 实体事件；自动附品种／类型、实体数字 ID、UUID、游戏 tick、维度与位置 |
| `world(level, category, message, values...)` | 无存活实体的事务；自动附 tick、维度，正文补 token、brood、聚落或地块标识 |
| `limited` / `worldLimited` | 重复等待或拒绝；同主体、类别和稳定原因键按模拟 tick 限频，间隔由调用点指定 |

跨重载关联用 UUID，数字 ID 只在当次运行有效。目标、资源、概率与前后值复用已经执行的业务结果；不为日志重新寻路、碰撞检测、抽签、扣料或扫描世界。重要事件默认 `INFO`，不转储完整 NBT、背包或长路径。

限频缓存不写存档，每个实体／世界最多 64 个键，使用弱引用；新增键达到容量时清空该桶。下一次同键输出携带 `suppressed=N`，没有下一次调用就不会额外补发。键不能包含动态坐标、时间或剩余冷却；具体间隔以调用点为准，不在文档另列一份数值表。

## 系统查询入口

默认事件类别与关联入口：

| 前缀 | 覆盖边界 | 关联与规则入口 |
| --- | --- | --- |
| `[body]` / `[remains]` / `[comfort]` | 真实受击、伤口／疤痕／疾病变化、治疗与耗材、死亡／遗体（死因类别、水中阶段）、舔伤接触和结束 | 实体、伤口、治疗者 UUID；[受伤与遗体](INJURIES_AND_REMAINS.md) |
| `[predator]` / `[myon]` | 目标与追击阶段、因洞口结界拒绝目标（限频）、攻击是否接受、生命变化、战斗停止、拾取／失去武器 | 双方 UUID；[捕食种](PREDATOR_IMPLEMENTATION.md)、[妖梦](RARE_FORMS.md) |
| `[family]` / `[childhood]` / `[growth]` / `[independence]` | 配对受孕、出生、取粮与喂食交接、首餐首便、运动学习、成长、离家与新家；树叶襁褓的采叶、折叠、包裹、解开、辅助排便、清理、包回、积便／溢出、脱落和限频等待；亲代运输的开始抬起、接起、放下（附原因与实际位置）、重载续接和拒绝（限频） | brood、亲子 UUID、物资来源；[生命周期](CHILDHOOD_LIFECYCLE.md)、[成长](BEHAVIOR.md) |
| `[approach]` | 接触站位不可用、绕行拐点、路径失败与接近计算耗时异常（限频）；不代表接触或业务成功 | 双方 UUID、目标与拐点；[移动接口](ARCHITECTURE.md#显示与客户端) |
| `[care]` | 照护会合、拒绝、重新选位、真实接起／放下和停止 | 双方 UUID、会合种类；[照护](CHILDHOOD_LIFECYCLE.md) |
| `[offering]` / `[grief]` / `[cannibal]` | 献身与护子意愿、阻塞、阶段、中断、亲属临终反应、遗体食用交餐 | 亲子、捕食者、遗体 UUID；[献身与遗体](INJURIES_AND_REMAINS.md) |
| `[aquatic]` / `[water]` / `[voyage]` / `[boarding]` / `[cradle]` | 出航、建船、翻船、自救／救子、返岸、初航、登船与抱持、岸上工作、孤幼接手、帽船席位表的订座／入座／释放／拒绝（`deck claim／admit／release／refuse`：席位、体型级别、载重前后与原因；重复拒绝按体和原因限频，扫描与步行细节不逐次记）、乘员转移（`transfer done／failed／refused`：方式、锚点、席位、载重前后、接触类型、拒绝类别与原因；每个结局一条，进行中的阶段只在 `/yukkuri aqdiag` 下逐阶段记）、落水事件（`incident open／decide／attempt／close`：原因、救助者角色、概率与掷值、物理失败计数、自救打滑与被拒入舷点；每个事件每位救助者只一条 decide）、船体灾害（`hazard capsize／released／righted／squeeze_out／overload_roll／owner_died／destroyed`：真实原因、载重、乘员数）、演出回退（`clip fallback／expression fallback`：来源、模型、原 ID 与回退 ID；`presentation expired`：来源、原因、片段；按 ID 或来源限频） | 行动者、帽船、子代 UUID；[水生魔理沙](AQUATIC_MARISA_IMPLEMENTATION.md)；帽子托管转换记在 `[headwear] custody A -> B`，读数 `water.incidents`、`water.hull` |
| `[cargo]` / `[family-alarm]` | 库存真实出入、腐坏、散落、幼体装载与靠岸交接、召回事件起止 | 船 UUID、亲子 UUID、事件开始 tick；[帽船](AQUATIC_MARISA_IMPLEMENTATION.md) |
| `[settlement]` / `[forage]` | 聚落账本事件、迁徙／安置、嗯嗯坑选址与清运、嗯嗯奴隶回坑／进食／被嫌恶、采集／回家／上缴、真实路线获准和停止 | 聚落、家庭、行动者 UUID；[聚落](SETTLEMENTS.md) |
| `[habitat]` / `[home]` / `[house]` / `[home-claim]` | 认领／释放住所、挖穴／扩容、搬粮入库、洞口结界拆除、家油巢卵、住所宣称起止 | 住所位置、claim 与成员 UUID；[住所](WILD_SHELTERS.md)、[家油](HOUSE_ECOLOGY.md)、[野外事件](WILD_EVENTS_IMPLEMENTATION.md) |
| `[meal]` / `[sleep]` / `[needs]` | 实际吞咽／口服剂结果、餐程中断与退料、睡醒、饮水、排便与排泄物落块（落点、档位、溢出）、营养跨阈值 | 实体、喂食者 UUID、实际物品；[基础生活](CORE_GAMEPLAY.md) |
| `[interaction]` / `[transport]` / `[carrier]` / `[live-bag]` / `[freezer]` | 玩家操作结果、工具归还、喂食扣量、收容与释放、票据和放置拒绝 | 玩家、原实体 UUID、token、设施位置；[基础操作](CORE_GAMEPLAY.md)、[冷冻收容](CORE_GAMEPLAY.md#冷冻收容与观察手册) |
| `[npc-care]` / `[human-trade]` / `[factory]` | 供餐治疗扣量、工厂工序和服役替补、真实材料／产出与交易 | NPC／玩家、工位、批次和个体；[人类世界](HUMAN_WORLD.md) |
| `[shop]` / `[delivery]` / `[courier]` / `[exam]` | 扣款／交付／退款、配送／召回、考试受理／题目／结果／中断 | 商店、订单、token、被检实体 UUID；[宠物店](PET_SHOP.md) |
| `[custody]` / `[badge]` / `[lesson]` / `[treasure]` | 归属与用途变化、死主清理、徽章变化、课程与技能学习、宝物转移 | 主人、目标、徽章序列 UUID；[归属](HUMAN_WORLD.md#店铺登记与饲主归属)、[宠物培育](PET_SHOP.md) |
| `[contempt]` / `[sanction]` / `[headwear]` | 失饰识别与反应、制裁受理与实际动作、头饰损坏／修补／换主 | 双方 UUID、饰品种类／数量、实体头饰 token；[头饰](HEADWEAR.md) |
| `[winter]` / `[temperature]` / `[weather]` / `[threat]` / `[urgency]` | 冬居、冷热与天气避难、危险响应（含灵梦封洞口开始／完成／中止）、紧急移速及睡眠交接 | 实体、住所、掩体或危险来源；[季节生活](SEASONAL_LIFE.md) |
| `[forage]` 记录寻粮开始与发现；`[explore]` | 子油魔理沙探险的已执行抽签、开始、返程、结束或中断 | 个体数值、概率与抽取结果、起点／目标、范围和真实结果；不记录每 tick 位置 |
| `[move]` | 行走停滞且被另一只油库里挡路时的侧步、绕行重算与请挡路者让位，原版卡住检测终止路线，以及地面寻路耗时异常、体型刷新碰撞空位搜索的开始／结果与耗时（限频，不记录每 tick 位置） | 双方 UUID、动作、目标；[行为](BEHAVIOR.md) |
| `[daily]` / `[social]` / `[wild-event]` / `[judgement]` | 社交活动保留／开始／结算／结束、受欺负后的反击／退避及真实命中、野外事件、审判听审与中断 | 参与者、scene／聚落 UUID、案件；[行为](BEHAVIOR.md)、[日常生态](ECOLOGY_DAILY_LIFE.md)、[事件](WILD_EVENTS_IMPLEMENTATION.md)、[族规](SETTLEMENTS.md) |
| `[request]` / `[play]` / `[shell]` / `[agitation]` / `[pet-walk]` | 宠物请求、娱乐设施、缩壳、躁动、牵引与靠岸请求的阶段和结果 | 主人／目标 UUID、设施位置、真实状态；[宠物](PET_SHOP.md)、[亚种](SUBSPECIES_IMPLEMENTATION.md)、[发情](ADULT_AGITATION.md) |
| `[mind]` 等原有日志 | 情绪和非油症等已有关键变化 | 部分旧日志只有品种和数字 ID；[情绪链](EMOTION_CHAIN.md) |

## 记录边界

- 默认省略每 tick 的移动、朝向、接触几何、动画、台词、音效、代谢、微量回血、沾污和温度采样；候选扫描、寻路节点、分片等待、逐格挖掘、逐棵采草也不逐次输出。普通问候、连续娱乐与幼体练习只保留有实际状态变化的边界。
- 真实拾取、交易、建船、物资转移与状态结算通常逐次记录，没有会丢掉事务的全局条数上限；持续消耗用阈值或阶段汇总，自动拒绝按原因限频。转接组件不重复记录领域组件生命周期。
- 接近、预留、排队、获准路线和阶段进入都不代表业务完成。喂食与食腐用实际吞咽确认营养；登船、落岸、抱持用实际乘坐与落点确认；交易用实际扣款／归属／库存确认。取消献身不等于孩子已获救，执行者结束不等于制裁已完成。
- 收容、放置、清场和场景准备均按真实结果记录；初态准备成功不是玩法成功。`completed`、`satisfied` 等词只在所属业务范围内成立，不代替整条链路验收。
- 读档与场景直接写入初始状态不重放历史事务；首次读到存档乘员不算刚登船。限频后的沉默、较旧的最近判定读数、没有成功日志，都不能单独证明行为没有发生。
- 诊断先核对运行版本、时间、实体 UUID、实际 Goal 与现场状态。普通日志没有覆盖全部前置拒绝；读数不足就补实际阻塞处，不根据日志缺失猜原因。

## 调试与排查

| 入口 | 用途 |
| --- | --- |
| `/yukkuri aqdiag on` / `off` | 显式开启／关闭水生逐阶段细节（`GameplayLog.detail`，前缀 `[aqdiag]`）；试玩场启动器以 `-Dmcyukkuri.aqdiag=true` 默认开启，其他启动方式默认关闭 |
| `/yukkuri status trace true` / `false` | 显式开启／关闭细密状态变化日志，默认关闭 |
| `debug.entitySaveViews` | 完整实体保存调试视图，默认关闭 |
| `render.woundPerfLog`（客户端配置 `mcyukkuri-client.toml`） | 约每 5 秒输出 `[render]` 伤口渲染耗时汇总（实体数、完整／简化数量、三角形、构建与帧耗时），默认关闭 |
| `/yukkuri status`、`mcylab:read/` | 按需只读快照；不自动轮询或补造历史状态，调用方法见[试玩场](DEVELOPMENT.md#导航操作与读数) |

常规排查只需场景和大概时间，按[开发指南](DEVELOPMENT.md#试玩日志与事后排查)定位运行包与原生日志；沿实体和事务标识追到具体业务代码。细密调试可能快速增大日志，用完关闭。版本和失败证据只回填待办原条目，不在本页复制一份场景排障记录。
