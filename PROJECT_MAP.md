# 项目目录与文件归类

成体持续发情期、爱丽丝失控及正常繁殖衔接见 [成体状态说明](docs/ADULT_AGITATION.md)；繁殖期由 `family/FamilyLife.sexDrive` 计时，`life/AgitationLife` 提供状态展示与失控行为；制作候选在 `standards/yukkuri-library/drafts/rapem-alice/`，本机预览在 `.local/previews/rapem-alice/`。

魔理沙帽子的脱戴与小幼油帽托参数位于 [art/source/hat-interactions/](art/source/hat-interactions/README.md)，由现行角色几何推导；稀毛与歪脸的造型源仍归不足形态制作包。

当前游戏形态的帽子、头饰与头发位置适配：制作源在 `standards/yukkuri-library/presentation/placement-fit/`，游戏接入说明在 [art/source/placement-fit/](art/source/placement-fit/README.md)；运行时在动作修订之前拼接，不改写基础模型。

当前圆润标准体型的制作配方、输入与合并组件位于 `standards/yukkuri-library/body-standard/`，正式模型仍导出到各角色登记的路径。家油、DOS 与豆形保留各自原体型；稀毛、歪脸魔理沙及灵梦分别由 [rounded-marisa](standards/yukkuri-library/subspecies/deficient/rounded-marisa/README.md) 和 [rounded-reimu](standards/yukkuri-library/subspecies/deficient/rounded-reimu/README.md) 采用圆润身体并同步游戏资源。标准更新不自动替换其他游戏接入快照。

2026-09-13 已按用户要求另外完成[圆润游戏选用](art/source/rounded-runtime/README.md)：八只当前标准导出和多毛灵胖组合保存在 `art/source/rounded-runtime/`。该目录现在负责普通五种、壳种、多毛及两种灵胖的运行快照；家油与六种不足油继续由各自原包维护。

| 内容 | 存放位置 | 职责 |
| --- | --- | --- |
| 模组代码 | `src/main/java/` | 入口、注册、实体及游戏逻辑；客户端实现在 `client/`；宠物档案、考试与课程在 `pet/`，宠物日常照料（甜食、请求、牵引散步、襁褓、徽章特权）在 `pet/PetCare`、`PetRequests`、`PetWalk`、`PetPrivileges` 与 `life/Sweets`，商店界面、货架库存与补货在 `shop/`，手帖在 `badge/`，设计见 [docs/PET_SHOP.md](docs/PET_SHOP.md) |
| 游戏资源 | `src/main/resources/` | 实际打包到模组的几何、贴图、动画、语言和物品模型 |
| 模组声明 | `src/main/templates/` | 构建时替换版本号等信息的模板 |
| 油库里试玩场（唯一试玩地图） | `scripts/build_museum_full.py`（数据：`museum_fields.py`、`museum_scenarios.py`、`museum_dsl.py`、`museum_sites.py`、`museum_actions.py`；服务端辅助 `command/Scenario*.java`、`command/PlaygroundTools.java`）、`docs/EXPERIMENTAL_MAP.md`、`docs/MOTION_MUSEUM_ACTIONS.md` | 十块开放的真实场地（草地、人类社会、池塘、人类房屋、山坡洞穴、林地、雪原、聚落草地、季节、动作片段）加大厅站点墙；每个场景生成真实个体（普通 AI，只设起始状态），观察台上有清场（永不拒绝）、时间天气倍速和观察标签；启动器现场生成全新世界，不再补洞。完整动作清单附适配范围与试玩位置。唯一启动入口仍是根目录 `启动动作博物馆.cmd`（调用 `scripts/launch_museum.ps1`），本机存档和运行配置在 `.local/experimental-map/`，不打入模组资源 |
| 标准素材制作 | `standards/yukkuri-library/` | 共用组件、角色登记、造型、表情、动作、派生资产及配套导出 |
| 当前实体接入素材 | `art/source/` | `standard-runtime/` 保存九种实体的选定快照与适配骨架；`rounded-runtime/`、`subspecies-runtime/`、`predator-runtime/` 等按包保存其余形态的选用快照 |
| 独立道具 | `art/props/` | 食盆、器具、玩具等制作源及来源登记；`care-facilities/` 已接入生活设施，`wild-shelters/` 保存天然住所叶床／细枝结界／纸箱选用，其他包不自动接入 |
| 人类建筑 | `art/structures/human-world/` | 商店、工厂的原创布局、模板实体与生成规则；人类服装接入见 `art/source/human-runtime/` |
| 音频制作 | `art/audio/` | AquesTalk 配音与生态音效的制作源和来源登记；游戏 OGG 位于 `src/main/resources/assets/mcyukkuri/sounds/` |
| 原版对白整理 | `art/dialogue/` | 八景 U 原版角色台词检索、Mod 对照、选用原句与译文；仅选中的台词进入游戏 |
| 成长与出生规格 | `standards/yukkuri-v1/` | 只保留仍被读取的成长规格、茎、出生行为与对应运行脚本；其余旧原型已删除 |
| 集中预览 | `.local/previews/index.html` | 本次更新、分镜、全部模型动作和声音的统一入口；同主题只保留当前版本，不提交产物 |
| 预览工具源码 | `preview/hub/`、`preview/review/`、根目录 `打开素材检查.cmd`／`关闭素材检查.cmd` | 页面模板和当前模型查看工具，读取游戏资源；由 `scripts/build_preview_hub.py` 生成集中入口 |
| 浏览器检查依赖 | `tools/browser-check/` | 各 `verify*.cjs` 使用的 Playwright 安装位置 |
| 设定与作品来源 | `wiki/` | 设定、出处与核读资料；`reference-library/` 按实体、情景、情绪和制作目标索引游戏图文参考，`art-references/` 保存图像原文件与出处（`art-references/game/` 为建模时收集的游戏与网页素材），`novel-reading/` 保存阅读卡；不作为运行资源或素材导出目录 |
| 开发文档 | `docs/` | 环境说明、阶段计划和各系统的实现说明 |
| 项目脚本 | `scripts/` | 跨目录的资源处理与验证入口；每个脚本注明用途和执行边界 |
| CI 与协作模板 | `.github/` | 构建流程、Issue 和 PR 模板 |
| 本机工作文件 | `.local/` | 预览、截图、检查报告、临时草稿、下载的参考资料；不提交，不作为唯一正式来源 |
| 独立工作树 | `.worktrees/` | 确需隔离时使用；不在项目同级另建目录，不纳入主项目版本管理 |

开发规则以 [AGENTS.md](AGENTS.md) 为准，人工协作说明见 [CONTRIBUTING.md](CONTRIBUTING.md)。

胎体出生、喂糜、首便、运动学习、家庭共巢、成长分床与亚成独立规则见[子代生命周期](docs/CHILDHOOD_LIFECYCLE.md)。

家油昼夜作息、棉絮筑巢、照卵、迁卵和破壳演出见[家油生态](docs/HOUSE_ECOLOGY.md)；静态制作源在 `standards/yukkuri-library/ecology/house-nest/`，选用快照与动作源分别在 `art/source/house-ecology/` 和 `art/source/house-ecology-presentation/`。

## 素材从制作到游戏接入

`standards/yukkuri-library/` 内的组件、角色登记与配方共同定义制作源，`assembled/<角色>/` 保存与源同步的可编辑模型、几何、动画和贴图。新造型从这里制作，并在角色或资产包的登记中标明确认状态。

`art/source/standard-runtime/` 保存灵梦、魔理沙、橙、爱丽丝、帕秋莉与两种家油的选定快照、适配骨架和交互查看页。运行模型位于 `src/main/resources/assets/mcyukkuri/yukkuri/`，每份 JSON 包含三角面、绑定和 19 个数字关键帧动作；两种家油另含夹取、搬运、放下和蠕动四段行为动作。表情贴图位于 `textures/entity/<实体 ID>/`。导出入口为 `scripts/export_runtime_models.py`，详细对应关系见 [美术来源登记](art/runtime-sources.json)。

该选用目录另已加入 `remilia/` 与 `flandre/` 两种捕食种快照，使当前实体范围增至九种。其制作源在 `standards/yukkuri-library/subspecies/predator/`，选用说明在 [predator-runtime](art/source/predator-runtime/README.md)，行为见[捕食种实现](docs/PREDATOR_IMPLEMENTATION.md)。九种资源校验通过不代表九种都已完成同一轮多人验收。

橙、爱丽丝、帕秋莉已从标准素材库选定导出并接入游戏；帕秋莉的运行骨架另有长发接地适配，静态标准造型不变。接入见 [种族实装记录](docs/SPECIES_IMPLEMENTATION.md)。选用新版标准模型替换游戏资源时，需要单独完成骨架、资源标识、动画、尺寸和来源登记的适配，并在指定环境验收。

2026-10-15 整理时删除了旧素材包（`variants/`、`art/library/`、`archives/`、`standards/yukkuri-library.zip` 等）、独立预览产物与截图、验收证据与报告。需要时可从 Git 历史找回，但不能用作当前造型或运行验收的依据。仍被制作脚本读取的 HTML 数据、动作包，以及素材检查工具的必要页面继续保留。

## 文件命名与放置

- 角色目录使用稳定的英文 ID，如 `patchouli`；文件名说明内容，如 `patchouli.geo.json`，不以“最终版”“新版2”表示版本。
- 同一素材包的制作脚本、运行预览支持代码和测试脚本保留在包内，避免移动后破坏相对引用。跨项目目录的脚本放 `scripts/`，并登记在其 README 中。
- 当前可用导出保留在各包的 `assembled/`；待确认方案放 `drafts/<主题>/`。被替代的方案直接删除，不另设 `archive/`。
- 预览统一生成到 `.local/previews/<主题>/`，总入口为 `.local/previews/index.html`。每个主题保留当前版本，生成脚本和必要制作输入留在原位；截图和检查报告分别放 `.local/screenshots/`、`.local/reports/`，均不入库。
- 认可状态写在制作源的登记字段里，不另存批准记录、冻结快照或截图。
- 导出脚本 `--check` 读取的 `manifest.json`、`provenance.json` 等来源登记是数据，随源文件维护。
- 目录中已有的制作模块和相对引用优先保留。整理不改模型形状、运行行为或未经本任务授权的素材确认状态。

## 文档维护

根 README 只提供项目状态与导航；目录职责集中维护在本页，开发规则维护在 `AGENTS.md`。版本与运行命令见 `docs/DEVELOPMENT.md`，已交付功能见 `docs/ROADMAP.md`，后续开发顺序见 `docs/DEVELOPMENT_PLAN.md`，具体实现与未验收事项放对应的专题文档。

每次移动文件时同步有效入口的链接。
