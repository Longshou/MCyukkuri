# 商店、工厂与人类 NPC

本页管人类世界：三种独立工厂、街边建筑与人类 NPC（爱派居民、鬼威惨、店员），及其生成、归属和放置接口。宠物店的考试、养成、商品目录、报价和徽章规则见 [PET_SHOP.md](PET_SHOP.md)，这里不重复。

代码入口是 `human/`（`HumanWorld`、`HumanNpc`、`HumanScenes`、`HumanCommerce`、`Factory*`、`LevelledPoolElement`）和 `shop/`（`ShopRegistry`、`ShopStock`）。作品核读与游戏化取舍见 [Wiki](../wiki/shops-factory-npcs.md)。建筑、设备和服装是项目原创转译，造型仍待用户确认。

## 生成与体验

| 对象 | 当前内容 |
| --- | --- |
| 宠物店 | 41×16×33 的单层坡屋顶街边店；四个展舱、四个后场栏、繁育室和考场；店主、考官、饲养员各一名，另有常客爱派大姐姐和店后树篱中的走失魔理沙。宠物店规则见 [PET_SHOP.md](PET_SHOP.md#6-商店)。 |
| 劳力工场 | 41×15×35；油库里以实际劳作把甘蔗制成纸，消耗自身营养。 |
| 馒头工厂 | 41×15×35；实际馅料处理、蒸制、验收、成品库及亲代／子油育成间。 |
| 活体袋装厂 | 同样大小；检视、清洁、封装与独立育成间。成品保留活体身份，不把本体变成袋形。 |
| 鬼威惨 | 工厂各有一名工头，也可单独放置；只会伤害未受保护的野生油库里，不主动攻击玩家。附近爱派可阻止本次行为。 |
| 爱派居民 | 不做宠物店以外的商品交易；可带自己的宠物散步、在条件满足时喂野生个体，并提供对话、油粮捐赠和走失委托归还。 |

职业 `RESIDENT`、`SHOPKEEPER`、`FACTORY`、`EXAMINER`、`KEEPER` 与态度独立保存。店员和工厂职员默认中立，不因岗位被视作鬼威惨；居民日间活动，夜间回住处休息。

结构使用 jigsaw 与 random spread：在平原、向日葵平原、草甸和热带草原的新区块候选生成，先做五点地基采样，再由 `LevelledPoolElement` 整平。商店候选区间为 16／6 区块，工厂为 32／12；既有区块和被清空的模板不会自动补建。低权重游荡人类只在白天明亮地表出现，96 格已有三名人类时停止；结构岗位不受游荡开关影响。

```mcfunction
/locate structure mcyukkuri:yukkuri_shop
/locate structure mcyukkuri:yukkuri_labor_factory
/locate structure mcyukkuri:yukkuri_manju_factory
/locate structure mcyukkuri:yukkuri_bag_factory
/place structure mcyukkuri:yukkuri_shop
/summon mcyukkuri:oniisan
/summon mcyukkuri:caretaker_brother
/summon mcyukkuri:caretaker_sister
```

## 店铺登记、牵引散步与放置接口

### 饲主归属与确认死亡

`human/Custody` 统一查询和转换归属。实体的 `NeoForgeData.HumanCommerce` 仍保存 `Owner`、旧用途 `Use` 和可选 `OwnerKind`；未知用途不改写，`PendingRole` 仍可等待岗位绑定。离线、跨维度或未加载不是死亡证据。

主世界 `mcyukkuri_custody` 记录已知主人类型和确认死亡的 NPC。只有 NPC 的 `die` 路径会登记死亡；普通移除、卸载和玩家死亡都不会。确认死亡后处理全维度已加载的名下个体及中央捕获快照：清除归属、用途、阶段和工厂／商店临时标记，保留血缘、伤病、技能、徽章和历史统计；不写弃养记录、不作废章、不触发弃养等待或重逢。已售给玩家的个体不再匹配旧店主。

运输篮、活体包装和冰柜只持令牌，权威快照在 `YukkuriWorldData.Captives`。冻结或释放先做幂等的死主清理；释放失败不消耗令牌或装备。升级前已死亡又没有身份或死亡台账的旧 UUID 保持 `UNKNOWN`。试玩场景是 `custody_death`，见[试玩场](EXPERIMENTAL_MAP.md#npc-主人死亡custody_death)。

店主 UUID 是店铺身份。`ShopRegistry` 记岗位坐标和维度，`ShopStock` 按店主记录货架与每日交易；在售条件仍是实体的 `Owner=店主`、`Use=stock`。上架只能来自模板／命令的待绑定岗位，或玩家卖回；出售保留同一实体的 UUID、家庭、性格、身体和徽章，并用运输篮交付。

爱派居民可带自己的宠物在白天、不下雨时，在住处 10 格内散步；宠物出现紧急需求或落后会停下，散步沿用 `PetWalk` 的距离累计。居民在宠物身边时不累计冷落。建筑放置和命令共用 `HumanScenes`：会清场、整平、按岗位绑定人员和模板个体。

```mcfunction
/yukkuri human build shop|labor|manju|bag <入口坐标> [north|east|south|west] [clear]
/yukkuri human walker <坐标> [brother|sister] [variant]
/yukkuri human oniisan <坐标> [prey]
/yukkuri human shop list
/yukkuri human shop stock <店主> <variant> <徽章等级0-3> <坐标>
/yukkuri human shop buy <店主> <个体> <买家UUID>
/yukkuri human shop carrier <物品实体>
```

## 交易、照顾与工厂

宠物店的商品、报价、收购、考试、相亲和补货规则见 [PET_SHOP.md](PET_SHOP.md#6-商店)。店主、考官和饲养员分别负责商店、考试及相亲／运送；其余居民、工厂职员仍用 `HumanMenu`。

照顾口粮与药量独立保存。玩家可捐赠普通油粮；店主和饲养员每天补充照顾口粮供库存使用。普通爱派居民只在野生个体营养较低时喂食，并对同一个体保留跨居民的冷却。服务器仍核对距离、视线、日间、付款、背包容量和现场实体／运输令牌，不以客户端报价为准。

工厂只接收玩家主手运输篮中真实、归玩家所有的个体：劳力厂编为劳力，另外两厂进入相应生产线。劳力线消耗甘蔗和劳力营养产纸；馒头线消耗小麦及真实遗体食用量；袋装线消耗纸和真实活体，拆包后仍为同一实体。产线原料、成品、输入数量和中断都按实际库存与状态处理，不能靠重载补造。

`factoryShopShipping` 默认开启：工厂每两游戏日可向 256 格内店铺出货至多两只符合独立生活条件的育成栏个体，2400 刻后可办理到货，实际入店还受空栏与运送条件限制。到货保留原徽章数据；只有有效徽章为无章的工厂货，才在无章报价上再乘 0.8。`commodityHarshness=0` 会同时关闭商品化背景和滞销回收；开启时 `staleRecallDays` 决定回收阈值，`staleRationDays` 仅为旧配置兼容。工人收到滞销个体后仍进入真实输入流程。

## 未完成与边界

本页现有功能均为**已接入待试玩**。自然地形的陡坡、建筑重叠、多人交易、长期经济平衡及建筑／服装造型都需要在试玩场确认。

- **工厂流水线，部分接入、现状待核对：**亲代工位轮换与替换、按种类／体型／健康状态分选、整茎采收、按批次育成栏、多只装袋时逐个保存身份。当前是单体周转托盘；没有皮带网、整茎采收或多体袋装。研究去向、按成长阶段区分的多年龄段出货仍未做。
- **村边纠纷，未做：**野生个体偷吃农田时，村民应优先低伤害驱离、诱捕或请求玩家处理；可损坏地毯、草叶等轻设施，不破坏房屋或以爆炸收场。
- **NPC 态度与关系，部分接入、现状待核对：**计划让 NPC 记住玩家对油库里的做法，并改变报价、对话和委托；床上睡眠与走失委托超时重新分配也未实现。不能把现有居民／鬼威惨身份或单次喂食当作该关系分支已完成。
- **饲主与考试边界：**弃养、疏忽和重逢见 [PET_SHOP.md](PET_SHOP.md#55-疏忽与弃养)；“送去加工所”威胁对白与跨地图追踪仍未做。最高级考试的生存风险未采用。

## 资产与验证

结构制作源见[建筑包](../art/structures/human-world/README.md)，人类几何、骨架、原创贴图和接入路径见[运行来源](../art/source/human-runtime/README.md)。`python scripts/export_human_world.py --check` 可核对制作源、导出物和语言条目；它不是用户试玩验收。
