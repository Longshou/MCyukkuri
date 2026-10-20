# 文档索引

按系统找文档和代码。入口只给方向：规则细节在专题文档里，最终以代码为准。代码根目录 `src/main/java/io/github/longshou/mcyukkuri/`，以下包名都相对于它。

## 流程与总览

| 文档 | 内容 |
| --- | --- |
| [设计取向与用户裁决](DESIGN_DECISIONS.md) | 设定来源优先级、设计平衡、动作约束、设定冲突的采用结果。设计玩法前必读 |
| [待办](TODO.md) | 复测失败、按试玩场景分组的待试玩清单、修复顺序、演出缺口、最后统一补的配音台词、美术与用户待决 |
| [玩法日志](GAMEPLAY_LOGGING.md) | 新功能留痕规则、关键链路覆盖、治疗排查和高频日志省略范围 |
| [完善计划](DEVELOPMENT_PLAN.md) | 各步骤状态总表与未做项的设计 |
| [代码架构说明](REFACTOR_HANDOFF.md) | 注册、组件生命周期、控制与显示、同步视图、领域数据和分片搜索（6.2） |
| [开发指南](DEVELOPMENT.md) | 环境版本、CI 产物、本机工具（Python、语法检查、运行包、无界面自查） |
| [试玩场](EXPERIMENTAL_MAP.md) | 唯一试玩入口：场地、场景、观察台命令；新场景加在 `scripts/museum_scenarios.py` |
| [动作清单](MOTION_MUSEUM_ACTIONS.md) | `YukkuriAction` 全部动作及资源适配范围 |
| [周边美术清单](PROP_ART_INVENTORY.md) | 道具美术的确认状态与待替换的占位 |

## 游戏系统

| 主题 | 文档 | 主要代码 |
| --- | --- | --- |
| 基础操作、生活数值、服务端配置 | [基础生活与饲养操作](CORE_GAMEPLAY.md) | `life/`、`item/`、`interaction/`、`play/` |
| 状态查询、外部受控写入与反应仲裁 | [状态中枢](STATUS_HUB.md) | `status/`；真实数据仍由各领域拥有 |
| 五属性、成长遗传与行为连续性（已接入待试玩） | [行为数值与成长设计](BEHAVIOR_BALANCE_DESIGN.md) | `social/`、`cognition/`、`family/`、`subspecies/`；规则与建议初值 |
| 日常行为总表 | [日常生态](ECOLOGY_DAILY_LIFE.md) | `social/DailyLife`、`life/` |
| **家庭与生命周期** | | |
| 胎体到亚成、亲代照料、离巢 | [子代生命周期](CHILDHOOD_LIFECYCLE.md) | `family/` |
| 成长时长、寿命、体型 | [成长与寿命](GROWTH_BALANCE.md) | `family/GrowthTiming`、`GrowthSize` |
| 消耗品、胎生与分娩触发 | [消耗品与胎生](CONSUMABLES_AND_BIRTH.md) | `life/Consumables`、`family/` |
| 发情期、攻击素鸡立（雷普魔） | [成体发情期](ADULT_AGITATION.md) | `family/FamilyLife`、`life/AgitationLife` |
| **身体与心理** | | |
| 伤口、中枢馅、遗体与腐败、同类相食、献身、舔舐 | [受伤与遗体](INJURIES_AND_REMAINS.md) | `body/`、`social/`（`WoundComfort`、`Cannibalism`、`SelfOffering`）、`entity/RemainsEntity` |
| 血量、受击与遗体资源（已接入，待试玩） | [资源与演出对应方案](BODY_RESOURCE_PLAN.md) | 单一血量、伤口漏馅演出、捕食、遗体食用与工厂共用资源 |
| 不安、甜化、非油症、取名 | [情绪链](EMOTION_CHAIN.md) | `mind/` |
| 五项行为属性、对象关系与压力 | [行为属性与对象关系](PERSONALITY_DESIGN.md) | `social/YukkuriPersonality`、`life/YukkuriWorldData`、`mind/` |
| 智力、具体技能与教学 | [智力与教学](COGNITION_IMPLEMENTATION.md) | `cognition/`、`pet/PetLessons`、`social/` |
| 头饰第二生命、识别、轻蔑与制裁 | [头饰](HEADWEAR.md) | `headwear/` |
| **种族与形态** | | |
| 种族注册、元数据、创造栏 | [种族注册与接入](SPECIES.md) | `entity/YukkuriVariant`、`registry/` |
| 种族行为差异（颊囊等） | [物种行为差异](SPECIES_BEHAVIOUR.md) | `entity/SpeciesTraits`、`life/SpeciesLife` |
| 不足形态、灵胖、螺壳 | [亚种与先天形态](SUBSPECIES_IMPLEMENTATION.md) | `subspecies/` |
| 水生魔理沙与帽船 | [水生魔理沙](AQUATIC_MARISA_IMPLEMENTATION.md) | `subspecies/`（`Aquatic*`、`HatBoat*`、`Hat*`） |
| 妖梦、被炉魔理沙等稀有变体 | [妖梦与稀有变体](RARE_FORMS.md) | `rare/` |
| 蕾米莉亚、芙兰 | [捕食种](PREDATOR_IMPLEMENTATION.md) | `predator/` |
| 对白、气泡、配音、声音注册 | [对白与配音](DIALOGUE_IMPLEMENTATION.md) | `dialogue/`、`registry/ModSounds` |
| **野外与群体** | | |
| 洞穴、睡窝、纸箱、流浪油 | [野外住所](WILD_SHELTERS.md) | `settlement/`（`NaturalShelter`、`Burrowing`、`ShelterBlock`） |
| 聚落、族长、族规、共同狩猎、迁徙 | [野外家庭与森林聚落](SETTLEMENTS.md) | `settlement/` |
| 群系季节、体感温度、秋储、越冬 | [季节与气候生活](SEASONAL_LIFE.md) | `season/` |
| 野外事件、情景卡、认屋 | [野外事件](WILD_EVENTS_IMPLEMENTATION.md) | `event/` |
| 家油（室内寄居、巢与卵） | [家油生态](HOUSE_ECOLOGY.md) | `house/` |
| **人类与饲养** | | |
| 宠物店、徽章考核、宠物培育 | [宠物店与徽章](PET_SHOP.md) | `pet/`、`shop/`、`badge/` |
| 工厂、建筑、人类 NPC | [商店、工厂与人类](HUMAN_WORLD.md) | `human/` |
| 冷冻收容、观察手册 | [冷冻收容与观察手册](FREEZER_AND_NOTEBOOK.md) | `freezer/`、`notebook/` |

## 代码包

| 包 | 职责 |
| --- | --- |
| `MCYukkuri` | 公共模组入口、资源接线与配置注册 |
| `registry/` | 实体、方块、物品、菜单、创造栏、声音注册；`CommonRegistration` 显式接入公共事件 |
| `entity/` | 实体接缝与同步/受击/交互/控制委托，种族元数据、画像、行为/动作表、遗体与掉落实体 |
| `entity/component/`、`entity/presentation/` | 组件登记、生命周期、Goal 桥与同步槽；服务端显示仲裁与实际运行 Goal 的展示绑定 |
| `status/` | 状态登记、身份、印记、反应仲裁、`/yukkuri status` 命令 |
| `life/`、`interaction/`、`play/`、`item/` | 生活数值与设施；照料菜单与物理接触；游具；工具物品 |
| `family/` | 繁殖、孕育、出生、成长、亲缘、育幼、离巢 |
| `body/`、`mind/`、`cognition/`、`headwear/`、`social/` | 身体与伤病；情绪与取名；认知；头饰与识别；性格、日常、社交与遗体相关行为 |
| `subspecies/`、`rare/`、`predator/` | 亚种与水生帽船；稀有种；捕食种 |
| `settlement/`、`season/`、`event/`、`house/` | 住所与聚落；季节与越冬；野外事件；家油 |
| `pet/`、`shop/`、`badge/`、`human/`、`freezer/`、`notebook/` | 宠物培育；商店；徽章；工厂与 NPC；冷冻；观察手册 |
| `dialogue/` | 选句、称呼、口癖、说话时机 |
| `world/`、`coordination/` | 共享搜索预算与生命周期；资源租约、纯冷却、区域键等暂态协调工具 |
| `network/` | 试玩报告的定向载荷与公共网络接缝 |
| `command/` | `/yukkuri` 命令，含试玩场场景（`Scenario*`、`PlaygroundTools`） |
| `client/` | 客户端事件入口 `ClientRegistration`、渲染、声音与界面，读取同步视图；服务端代码不得引用 |

## 美术与资源

制作源、选用快照与运行资源的关系见[项目地图](../PROJECT_MAP.md#素材从制作到游戏)；入口分别是 [standards/](../standards/README.md)、[art/](../art/README.md)、[scripts/](../scripts/README.md)。设定原文见 [wiki/](../wiki/README.md)。
