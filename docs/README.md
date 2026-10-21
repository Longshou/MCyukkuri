# 文档与目录索引

按系统找文档和代码。入口只给方向：规则细节在专题文档里，待办集中记录状态与完成标准，最终实现以代码为准。每次改动的最小维护范围见 [开发约定](../AGENTS.md#文档按变更维护)，不要求逐篇更新。代码根目录 `src/main/java/io/github/longshou/mcyukkuri/`，以下包名都相对于它。

## 流程与总览

| 文档 | 内容 |
| --- | --- |
| [设计取向与用户裁决](DESIGN_DECISIONS.md) | 设定来源优先级、设计平衡、动作约束、设定冲突的采用结果。设计玩法前必读 |
| [待办](TODO.md) | 复测失败、按试玩场景分组的待试玩清单、修复顺序、演出缺口、低优先级配音台词与美术 |
| [玩法日志](GAMEPLAY_LOGGING.md) | 公共日志入口、系统前缀与覆盖、高频省略及调试开关 |
| [代码架构说明](REFACTOR_HANDOFF.md) | 注册、组件生命周期、控制与显示、同步视图、领域数据和分片搜索 |
| [开发指南](DEVELOPMENT.md) | 环境版本、CI 产物、本机工具、试玩场启动与现场诊断 |
| [动作清单](MOTION_MUSEUM_ACTIONS.md) | `YukkuriAction` 全部动作及资源适配范围 |
| [周边美术清单](PROP_ART_INVENTORY.md) | 道具美术的确认状态与待替换的占位 |

## 游戏系统

| 主题 | 文档 | 主要代码 |
| --- | --- | --- |
| 基础操作、生活数值、冷冻收容、观察手册与配置 | [基础生活与饲养操作](CORE_GAMEPLAY.md) | `life/`、`item/`、`interaction/`、`play/`、`freezer/`、`notebook/` |
| 状态查询、外部受控写入与反应仲裁 | [状态中枢](STATUS_HUB.md) | `status/`；真实数据仍由各领域拥有 |
| 五属性、关系、教学、成长体型与行为连续性 | [行为数值与成长设计](BEHAVIOR_BALANCE_DESIGN.md) | `social/`、`cognition/`、`family/`、`subspecies/`；现行规则、存档、读数及尚未落实的设计 |
| 日常行为总表 | [日常生态](ECOLOGY_DAILY_LIFE.md) | `social/DailyLife`、`life/` |
| **家庭与生命周期** | | |
| 胎体到亚成、亲代照料、离巢 | [子代生命周期](CHILDHOOD_LIFECYCLE.md) | `family/` |
| 消耗品、胎生与分娩触发 | [消耗品与胎生](CONSUMABLES_AND_BIRTH.md) | `life/Consumables`、`family/` |
| 发情期、攻击素鸡立（雷普魔） | [成体发情期](ADULT_AGITATION.md) | `family/FamilyLife`、`life/AgitationLife` |
| **身体与心理** | | |
| 伤口、中枢馅、遗体与腐败、同类相食、献身、舔舐 | [受伤与遗体](INJURIES_AND_REMAINS.md) | `body/`、`social/`（`WoundComfort`、`Cannibalism`、`SelfOffering`）、`entity/RemainsEntity` |
| 不安、甜化、非油症、取名 | [情绪链](EMOTION_CHAIN.md) | `mind/` |
| 头饰第二生命、识别、轻蔑与制裁 | [头饰](HEADWEAR.md) | `headwear/` |
| **种族与形态** | | |
| 种族注册、元数据、创造栏与行为差异 | [种族与行为](SPECIES.md) | `entity/YukkuriVariant`、`entity/SpeciesTraits`、`life/SpeciesLife`、`registry/` |
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

## 目录

| 位置 | 职责 |
| --- | --- |
| `src/main/java/io/github/longshou/mcyukkuri/` | 模组代码，按系统分包（包与文档的对应见[游戏系统](#游戏系统)）；客户端实现只放 `client/` |
| `src/main/resources/` | 实际打包进模组的几何、贴图、动画、声音、语言、配方和数据包 |
| `src/main/templates/` | 构建时替换版本号的模组声明模板 |
| `standards/yukkuri-library/` | 标准美术制作源：共用组件、角色登记、造型、表情、动作、派生资产及导出；待确认方案在各包 `drafts/`。见[标准素材库](../standards/yukkuri-library/README.md) |
| `standards/yukkuri-v1/` | 仍被读取的成长规格、茎、出生行为与对应脚本 |
| `art/source/` | 游戏当前选用的素材快照（按包分 `standard-runtime/`、`rounded-runtime/`、`subspecies-runtime/`、`predator-runtime/`、`rare-forms-runtime/` 等），对应关系登记在 `art/runtime-sources.json`。见 [art/](../art/README.md) |
| `art/props/` | 独立道具（食盆、器具、玩具、伤口、徽章、住所……）的制作源与来源登记 |
| `art/structures/human-world/` | 商店、工厂的原创布局、模板实体与生成规则 |
| `art/audio/`、`art/dialogue/` | AquesTalk 配音与生态音效制作源；八景 U 原版对白检索、选句与译文。游戏 OGG 在 `src/main/resources/assets/mcyukkuri/sounds/` |
| `wiki/` | 设定与作品出处、参考图库、小说阅读卡。设定依据，不是运行资源。见 [wiki/](../wiki/README.md) |
| `docs/` | 开发文档：入口、待办、设计裁决和各系统专题 |
| `scripts/` | 跨目录的导出、检查和试玩场生成脚本，每个脚本注明用途与写入边界。见 [scripts/](../scripts/README.md) |
| `preview/hub/`、`preview/review/` | [预览总入口模板与素材检查室](../preview/README.md)；由 `scripts/build_preview_hub.py` 生成到 `.local/previews/` |
| `tools/browser-check/` | 历史浏览器检查工具的依赖；不是当前 Agent 验证入口，见[脚本边界](../scripts/README.md) |
| `.github/` | CI（只编译）与 Issue／PR 模板 |
| `.local/` | 本机工作区，不提交：预览、截图、报告、草稿、下载的参考、CI 运行包、试玩场存档、本机工具（见[开发指南](DEVELOPMENT.md#本机工具)） |
| `.worktrees/` | 需要隔离时的 git 工作树，不纳入版本管理 |
| `油库里八景ver1.12/` | 用户提供的原作参考包，不提交 |
| 根目录 `.cmd` | `启动动作博物馆.cmd` 启动试玩场（唯一试玩入口）；`打开素材检查.cmd`／`关闭素材检查.cmd` 开关素材检查室 |

## 素材从制作到游戏

1. **制作**：在 `standards/yukkuri-library/` 改制作源（组件、配方、登记），导出到包内 `assembled/<角色>/`。确认状态写在角色或资产包的登记字段里。
2. **选用**：游戏采用某版本时，把快照放进 `art/source/<包>/`，同步更新 `art/runtime-sources.json`。标准库更新不会自动替换游戏资源。
3. **接入**：用 `scripts/export_*.py`（如 `export_rounded_runtime.py`、`export_rare_forms.py`）生成 `src/main/resources/` 下的运行资源；支持 `--check` 的脚本可只读核对一致性，参数以对应脚本为准。
4. 运行模型位于 `src/main/resources/assets/mcyukkuri/yukkuri/`，表情贴图在 `textures/entity/<实体 ID>/`。头饰与头发的位置适配、动作修订在运行时拼接，不改写基础模型。

## 文件命名与放置

- 目录用稳定的英文 ID（如 `patchouli`），文件名说明内容（如 `patchouli.geo.json`），不用“最终版”“新版2”表示版本。
- 同一素材包的制作脚本和检查脚本留在包内，避免移动后破坏相对引用；跨目录的脚本放 `scripts/` 并登记在其 README。
- 当前导出放 `assembled/`，待确认方案放 `drafts/<主题>/`；被替代的方案直接删除，不设 `archive/`，需要时从 Git 历史找回。
- 预览统一生成到 `.local/previews/<主题>/`（总入口 `.local/previews/index.html`），同主题只保留当前版本；截图和报告放 `.local/screenshots/`、`.local/reports/`，都不入库。
- 导出脚本 `--check` 读取的 `manifest.json`、`provenance.json` 等来源登记是数据，随源文件维护。
