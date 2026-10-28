# 文档与目录索引

专题直接保存当前规则和必要代码入口；未实现边界指向待办。代码根目录 `src/main/java/io/github/longshou/mcyukkuri/`，下表包名相对该目录。维护方式见 [AGENTS.md](../AGENTS.md#文档按变更维护)。

[待办](TODO.md)保存未完成事项与实测问题；[开发指南](DEVELOPMENT.md)说明环境和试玩操作；[代码架构](ARCHITECTURE.md)说明职责与接口；[玩法日志](GAMEPLAY_LOGGING.md)说明排查入口。制作素材见 [art/](../art/README.md)、[标准库](../standards/yukkuri-library/README.md)，设定出处见 [Wiki](../wiki/README.md)。

## 游戏系统

| 主题 | 文档 | 主要代码 |
| --- | --- | --- |
| 基础操作、生活数值、冷冻收容、观察手册与配置 | [基础生活与饲养操作](CORE_GAMEPLAY.md) | `life/`、`item/`、`interaction/`、`play/`、`freezer/`、`notebook/` |
| 状态查询、外部受控写入与反应仲裁 | [状态中枢](STATUS_HUB.md) | `status/`；真实数据仍由各领域拥有 |
| 五属性、关系、教学、成长体型与行为连续性 | [行为与成长](BEHAVIOR.md) | `social/`、`cognition/`、`family/`、`subspecies/` |
| 日常行为总表 | [日常生态](ECOLOGY_DAILY_LIFE.md) | `social/DailyLife`、`life/` |
| 胎体到亚成、亲代照料、离巢 | [子代生命周期](CHILDHOOD_LIFECYCLE.md) | `family/` |
| 消耗品、胎生与分娩触发 | [消耗品与胎生](CONSUMABLES_AND_BIRTH.md) | `life/Consumables`、`family/` |
| 发情期、攻击素鸡立（雷普魔） | [成体发情期](ADULT_AGITATION.md) | `family/FamilyLife`、`life/AgitationLife` |
| 伤口、中枢馅、遗体与腐败、同类相食、献身、舔舐 | [受伤与遗体](INJURIES_AND_REMAINS.md) | `body/`、`social/`（`WoundComfort`、`Cannibalism`、`SelfOffering`）、`entity/RemainsEntity` |
| 不安、甜化、非油症、取名 | [情绪链](EMOTION_CHAIN.md) | `mind/` |
| 头饰第二生命、识别、轻蔑与制裁 | [头饰](HEADWEAR.md) | `headwear/` |
| 种族注册、元数据、创造栏与行为差异 | [种族与行为](SPECIES.md) | `entity/YukkuriVariant`、`entity/SpeciesTraits`、`life/SpeciesLife`、`registry/` |
| 不足形态、灵胖、螺壳 | [亚种与先天形态](SUBSPECIES_IMPLEMENTATION.md) | `subspecies/` |
| 水生魔理沙（未实现，待 WP4–6 重建） | [水生魔理沙](AQUATIC_MARISA_IMPLEMENTATION.md) | `subspecies/SubspeciesLife`（物种数据）；放下的帽子见 `headwear/PlacedHatEntity` |
| 身体能力重构：底座、出生链与水生 DLC 的整体设计与技术规格 | [整体设计](REFACTOR_DESIGN.md)、[技术规格](REFACTOR_TECH_SPEC.md) | 底座（`motor/`、`intent/`）、`family/` 出生链与 `subspecies/` |
| 妖梦、被炉魔理沙等稀有变体 | [妖梦与稀有变体](RARE_FORMS.md) | `rare/` |
| 蕾米莉亚、芙兰 | [捕食种](PREDATOR_IMPLEMENTATION.md) | `predator/` |
| 对白、气泡、配音、声音注册 | [对白与配音](DIALOGUE_IMPLEMENTATION.md) | `dialogue/`、`registry/ModSounds` |
| 洞穴、睡窝、纸箱、流浪油 | [野外住所](WILD_SHELTERS.md) | `settlement/`（`NaturalShelter`、`Burrowing`、`ShelterBlock`） |
| 聚落、族长、族规、共同狩猎、迁徙 | [野外家庭与森林聚落](SETTLEMENTS.md) | `settlement/` |
| 群系季节、体感温度、储粮、寒地生活 | [季节与气候生活](SEASONAL_LIFE.md) | `season/` |
| 野外事件、情景卡、认屋 | [野外事件](WILD_EVENTS_IMPLEMENTATION.md) | `event/` |
| 家油（室内寄居、巢与卵） | [家油生态](HOUSE_ECOLOGY.md) | `house/` |
| 宠物店、徽章考核、宠物培育 | [宠物店与徽章](PET_SHOP.md) | `pet/`、`shop/`、`badge/` |
| 工厂、建筑、人类 NPC | [商店、工厂与人类](HUMAN_WORLD.md) | `human/` |

## 目录

| 位置 | 内容 |
| --- | --- |
| `src/main/java/`、`src/main/resources/`、`src/main/templates/` | Java、打包资源、模组声明模板 |
| `standards/yukkuri-library/` | 标准制作源、组件、配方与导出；候选在各包 `drafts/` |
| `standards/yukkuri-v1/` | 仍被读取的成长、茎和出生规格 |
| `art/source/` | 游戏选用快照；对应关系在 `art/runtime-sources.json` |
| `art/props/`、`art/structures/` | 道具与建筑制作源 |
| `art/audio/`、`art/dialogue/` | 配音、音效与对白制作源 |
| `wiki/`、`docs/` | 作品知识与游戏专题 |
| `scripts/` | 跨目录导出、检查与试玩场生成，见[脚本说明](../scripts/README.md) |
| `preview/` | 预览页面与分镜源码，见[预览说明](../preview/README.md) |
| `.github/` | CI 与协作模板 |
| `.local/`、`.worktrees/` | 不提交的临时产物、运行包、试玩世界与工作树 |
| `油库里八景ver1.12/` | 本机原作参考包，不提交 |

根目录 `启动动作博物馆.cmd` 启动试玩场，`打开素材检查.cmd`／`关闭素材检查.cmd` 开关素材检查室。[动作清单](MOTION_MUSEUM_ACTIONS.md)由动作与资源登记生成。

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
