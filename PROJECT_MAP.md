# 项目目录与文件归类

魔理沙帽子的脱戴与小幼油帽托参数、当前预览和验收位于 [art/source/hat-interactions/](art/source/hat-interactions/README.md)，由现行角色几何推导；稀毛与歪脸的造型源仍归不足形态制作包。

当前圆润标准体型的制作配方、冻结输入与合并组件位于 `standards/yukkuri-library/body-standard/`，正式模型仍导出到各角色登记的路径。家油、DOS 与豆形保留各自原体型；稀毛、歪脸魔理沙及灵梦分别由 [rounded-marisa](standards/yukkuri-library/subspecies/deficient/rounded-marisa/README.md) 和 [rounded-reimu](standards/yukkuri-library/subspecies/deficient/rounded-reimu/README.md) 采用圆润身体并同步游戏资源。标准更新不自动替换其他游戏接入快照。

2026-09-13 已按用户要求另外完成[圆润游戏选用](art/source/rounded-runtime/README.md)：八只当前标准导出和多毛灵胖组合保存在 `art/source/rounded-runtime/`。该目录现在负责普通五种、壳种、多毛及两种灵胖的运行快照；旧 `standard-runtime/` 的普通五种和 `subspecies-runtime/` 的壳种／多毛记录作为历史基线，家油与六种不足油继续由各自原包维护。

| 内容 | 存放位置 | 职责 |
| --- | --- | --- |
| 模组代码 | `src/main/java/` | 入口、注册、实体及游戏逻辑；客户端实现在 `client/` |
| 游戏资源 | `src/main/resources/` | 实际打包到模组的几何、贴图、动画、语言和物品模型 |
| 模组声明 | `src/main/templates/` | 构建时替换版本号等信息的模板 |
| 游戏验收夹具 | `src/p0Test/` | 独立开发模组，执行真实引擎行为检查与客户端采集；不进入发行 Jar |
| 人工实验地图 | `scripts/build_experimental_map.py`、`docs/EXPERIMENTAL_MAP.md` | 制作16区试玩场；本机存档在 `run/saves/MCyukkuri-Lab/`，交付ZIP和检查在 `.local/experimental-map/release/`，不打入模组资源 |
| 标准素材制作 | `standards/yukkuri-library/` | 共用组件、角色登记、造型、表情、动作、派生资产及配套导出 |
| 当前实体接入素材 | `art/source/` | `standard-runtime/` 保存九种实体的选定快照与适配骨架；七种既有角色预览留在原包，两种捕食种预览见 `predator-runtime/`；旧 `reimu/`、`marisa/` 保留为历史基线 |
| 历史动作与表情 | `art/library/` | 按内容哈希保存的源文件包、清单与冻结方案；恢复说明见 `docs/ASSET_WORKFLOW.md` |
| 独立道具 | `art/props/` | 馅料、食盆等制作源及来源记录；`care-facilities/` 已接入生活设施，`wild-shelters/` 保存天然住所叶床／细枝结界／纸箱选用，其他包不自动接入 |
| 人类建筑 | `art/structures/human-world/` | 商店、工厂的原创布局、模板实体与生成规则；人类服装接入见 `art/source/human-runtime/` |
| 音频制作 | `art/audio/` | 动作音效与日语语音的台词、参数、WAV 母带、来源指纹和试听；游戏 OGG 位于 `src/main/resources/assets/mcyukkuri/sounds/` |
| 原版对白整理 | `art/dialogue/` | 八景 U 原版角色台词检索、Mod 对照、选用原句与译文；仅选中的台词进入游戏 |
| 旧版兼容素材 | `standards/yukkuri-v1/` | 生命周期、亚种等既有原型依赖；当前共用素材仍在 `yukkuri-library/` 制作 |
| 历史打包工具 | `tools/project-library/` | 保存与恢复旧素材包，保留原有相对路径依赖；遵守本机执行边界 |
| 设定与作品来源 | `wiki/` | 设定、出处与核读资料；不作为运行代码或素材导出目录 |
| 开发文档 | `docs/` | 环境说明、阶段计划、实现边界和验收说明 |
| 游戏验收证据 | `docs/validation/` 下的 `species/`、`predators/`、`badges/`、`factory-pipeline/`、`cold-storage/`、`house-motion/`、`house-interactions/`、`walking/`、`p0/`、`p1p2/`、`core/`、`dialogue/`、`port-remainder/`、`settlements/` 等专题目录 | 分阶段断言、文件指纹和精选实机截图；完整临时日志与帧序列放 `.local/` 的对应任务目录 |
| 项目脚本 | `scripts/` | 跨目录的资源处理与验证入口；每个脚本注明用途和执行边界 |
| CI 与协作模板 | `.github/` | 构建流程、Issue 和 PR 模板 |
| 本机工作文件 | `.local/` | 临时草稿、下载的参考资料、一次性探针；不提交，不作为唯一正式来源 |
| 独立工作树 | `.worktrees/` | 确需隔离时使用；不在项目同级另建目录，不纳入主项目版本管理 |

开发规则以 [AGENTS.md](AGENTS.md) 为准，人工协作说明见 [CONTRIBUTING.md](CONTRIBUTING.md)。

## 素材从制作到游戏接入

`standards/yukkuri-library/` 内的组件、角色登记与配方共同定义制作源，`assembled/<角色>/` 保存与源同步的可编辑模型、几何、动画、贴图和预览。新造型从这里制作，并在角色或资产包的登记中标明确认状态。

`art/source/standard-runtime/` 保存灵梦、魔理沙、橙、爱丽丝、帕秋莉与两种家油的选定快照、适配骨架和动作预览。运行模型位于 `src/main/resources/assets/mcyukkuri/yukkuri/`，每份 JSON 包含三角面、绑定和 19 个数字关键帧动作；两种家油另含夹取、搬运、放下和蠕动四段行为动作。表情贴图位于 `textures/entity/<实体 ID>/`。导出入口为 `scripts/export_runtime_models.py`，详细对应关系见 [美术来源登记](art/runtime-sources.json)。

该选用目录另已加入 `remilia/` 与 `flandre/` 两种捕食种快照，使当前实体范围增至九种。其制作源在 `standards/yukkuri-library/subspecies/predator/`，交互预览和选用说明在 [predator-runtime](art/source/predator-runtime/README.md)，行为和本轮验收见[捕食种实现](docs/PREDATOR_IMPLEMENTATION.md)。七种既有角色的预览与历史检查保持独立；九种资源校验通过不代表九种都已完成同一轮多人验收。

`art/source/reimu/`、`marisa/` 及旧运行目录 `geo/entity/`、`animations/entity/` 中的文件保留为此前 M1 基线，当前渲染器不再读取。它们与标准库中的同名角色属于不同制作版本，不能因名字相同而合并或覆盖。

橙、爱丽丝、帕秋莉已从标准素材库选定导出并接入游戏；帕秋莉的运行骨架另有长发接地适配，静态标准造型不变。接入与本轮验收见 [种族实装记录](docs/SPECIES_IMPLEMENTATION.md)。选用新版标准模型替换游戏资源时，需要单独完成骨架、资源标识、动画、尺寸和来源登记的适配，并在指定环境验收。

`standards/yukkuri-library.zip` 是历史导出包，不能覆盖当前制作目录。`archives/` 只保留历史备份说明；说明中列出的本地备份不随仓库提供。旧造型、过期预览和检查报告可从 Git 历史追溯，不能用作当前造型或运行验收的依据。

## 文件命名与放置

- 角色目录使用稳定的英文 ID，如 `patchouli`；文件名说明内容，如 `patchouli.geo.json`，不以“最终版”“新版2”表示版本。
- 同一素材包的制作脚本、运行预览支持代码和测试脚本保留在包内，避免移动后破坏相对引用。跨项目目录的脚本放 `scripts/`，并登记在其 README 中。
- 当前可用导出保留在各包的 `assembled/`；待确认方案放 `drafts/<主题>/`。被替代的预览、截图和检查输出在核对引用后清理；只有仍需追溯的记录进入包内 `archive/`，并说明适用版本和保留原因。
- 当前待人工查看的模型预览放素材包的 `previews/current/`，使用稳定文件名；批准记录仍引用正式组件与导出，不以截图作为源文件。
- 新检查结果放相关素材包的 `reports/current/`，写明适用源码版本或文件指纹、环境、时间与检查类型；不能用旧记录证明新改动通过。
- 不移动或删除已确认快照包中的文件来“去重”；快照的哈希和内部引用必须保持完整。仍有待确认设计或恢复用途的唯一文件继续保留，不能仅凭文件名或时间认定冗余。
- 目录中已有的制作模块和相对引用优先保留。整理不改模型形状、运行行为或未经本任务授权的素材确认状态。

## 文档维护

根 README 只提供项目状态与导航；目录职责集中维护在本页，开发规则维护在 `AGENTS.md`。版本与运行命令见 `docs/DEVELOPMENT.md`，功能状态见 `docs/ROADMAP.md`，具体实现与未验收事项放对应的专题文档。

本机上级 `E:/yukkuri/` 已按游戏、资料和工具分类，入口见上级 `从这里开始.md`。2026-09-13 清理范围、保留的源码和历史记录见[本机目录清理](docs/DIRECTORY_CLEANUP.md)。项目 `.local/` 中已结束测试的重复编译产物可以清理，但当前试玩固定运行目录、玩家存档、未提交源码、唯一素材与诊断记录须分别识别。

每次移动文件时同步有效入口的链接。历史记录中的原始路径可保留在归档内，但须明确其属于历史上下文，不能作为当前操作入口。
