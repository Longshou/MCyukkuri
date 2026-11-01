# MCyukkuri 开发约定

本机只修改代码、文档或建模，正式编译交给远端 CI，玩法由用户在试玩场确认。提交前用 `bash scripts/javac_check.sh <工作树>` 做 javac 与纯函数测试自查（不经 Gradle，不算验收）。

## 入口

- [功能说明](docs/FEATURES.md)：当前玩法规则与关键数值的唯一归属处（精简，可能落后于代码，以代码为准）。
- [待办](docs/TODO.md)：未完成事项、实测问题、运行版本与完成标准。
- [Wiki](wiki/README.md)：设定来源；术语见[术语表](wiki/glossary.md)。
- 本文件：开发约定与常用命令。目录：`src/main/{java,resources,templates}`、`standards/yukkuri-library/`（制作源）、`art/`（选用快照与道具／建筑／音频源）、`scripts/`（导出、检查、试玩场生成，见[脚本说明](scripts/README.md)）、`preview/`（预览页）、`.local/`与`.worktrees/`（不提交）。

## 开发命令

- 版本以 `gradle.properties` 为准（Minecraft 1.21.1、JDK 21、NeoForge 21.1.250）。`.github/workflows/build.yml` 在 `main` 推送、PR 和手动触发时执行 `./gradlew build`，产物 `mcyukkuri-<sha>`（开发 Jar）与 `mcyukkuri-runtime-<sha>`（运行包）；失败用 `gh run view <id> --log-failed`。
- 自查：`bash scripts/javac_check.sh <工作树>`（javac 与纯函数测试）；`python scripts/smoke_museum.py [--worktree F:/yukkuri/.worktrees/<分支>] [场景ID ...]` 无界面冒烟（读主检出已装 CI 运行包，缺失即报错，不跑 Gradle，客户端或 Minecraft 运行时拒绝启动；产物在主检出 `.local/smoke/`，只查场景初态与链路读数）。资源导出脚本多支持 `--check`；`scripts/check_museum_pack.py` 只读核对试玩场数据包。
- 试玩场：根目录 `启动动作博物馆.cmd`（`-Fresh` 全新世界、`-Resume` 保留世界、`-Menu` 主菜单）。场景、读数和按钮源在 `scripts/museum_*.py`。清场与场景边界见[功能说明](docs/FEATURES.md#试玩场与动作博物馆)。
- 日志与排查：读 `.local/experimental-map/play-museum/process.json` 与同场 `.session.json` 得启动时间、PID、世界；原生日志 `.local/experimental-map/museum/logs/latest.log`、`debug.log`（旧场次查日期归档 `.log.gz`）。版本取 `.session.json` 的 `runtimeConfig.commit/run`，无证据写“版本待核实”；原日志保留，副本放 `.local/reports/<主题>/`。`/yukkuri status trace true` 开启细密采样。
- SERVER 配置在世界 `serverconfig/`：`mcyukkuri-server.toml`（生活、生态、聚落）、`mcyukkuri-family.toml`（家庭繁殖）、`mcyukkuri-human-world.toml`（人类、商贸、工厂）；分组是键路径的一部分，如 `seasons.biomeSeasons`。
- 场景准备命令依赖落地的须在召唤后 2 秒执行；`/tick` 需等级 3，函数和命令方块仅等级 2；直接 `/function mcylab:scn/<id>` 不清场；`smoke_museum.py --hat-form turned_land_hat|cone_basin` 切换池塘帽形；`build_museum_full.py --pack-only --output` 须为 `.local/experimental-map/` 或 `.local/smoke/` 下不存在的新目录；清理 `.local/setup/runtime-*`、`ci-*` 前核对配置与进程。

## 协作与范围

- 只改当前任务需要的内容，复用最近的实现；保留他人未提交改动，移动文件时更新引用。
- 代码、美术源、预览和文档都放本仓库。临时文件放 `.local/`，工作树放 `.worktrees/`；不提交外部游戏资源、凭据、Minecraft 本体或运行世界。
- 并行任务在 `.worktrees/<名字>` 基于 `origin/main` 开分支。开工前在待办“进行中”登记工作树与主要文件；已登记区域先协调，不同时改。不要替别人暂存、提交或丢弃改动。
- 推送前 rebase 最新 `origin/main`，完成后 `git push origin HEAD:main`。合入后移除自己的进行中条目、工作树和本地分支，不留远端分支；用完删除自己的临时脚本。
- 与用户直接交流用简体中文；代理之间的交接（任务说明、报告、子代理回复）用英文；仓库文档沿用中文，代码、英文标识和提交信息保持英文。提交信息具体说明改动，交付附提交号。
- 未经明确允许，禁止 computer use、桌面或浏览器自动化；启动、测试和视觉检查需求不构成授权，不通过其他工具绕过。页面效果由用户手工查看。
- 除自己创建且已合入的分支外，删除分支、改写历史等不可逆操作先确认。

## 规则与实现

- 规则直接维护在 [功能说明](docs/FEATURES.md) 的所属小节，冲突采用时间较近的明确结论；用户修正优先于参考设定。来源不明的原创规则注明“项目设计”。未实现要求保留简短边界并指向待办，不能因代码未做就删除，也不能写成已实现。
- 修复必须保留原定玩法，定位实际 Goal、阶段和阻塞处后修交接；读数不够先补读数。不能以停用能力、永久等待、强制留守、瞬移、凭空补资源或直接改成功状态掩盖失败。真实拒绝、能力不足和危险仍产生后果。
- 动作调用封装好的共享能力（走、跳、搬运、入窝判定等）：行为只声明目标，由共享能力执行；同类问题修在共享能力或其所属层，不为单个场景或任务另写衔接、特判和重试，缺能力先补能力再接行为。评审红线见下“架构红线”。
- 重构期间每个工作包净代码量下降（新建包除外），删除栏在本包完成；框架代码只随第一个真实调用方合入。
- 权衡设定还原、复杂度和 Minecraft 玩法；已有物品能承接的效果不另增物品。身体状态和重要身体结构须有可见表现与真实交互。工作来自用户需求、反馈和待办，不主动恢复已搁置的补缺计划。
- Java 根为 `src/main/java/io/github/longshou/mcyukkuri/`。客户端类只放 `client/`；服务端拥有生态状态和决策，成长、饥饿、繁殖不依赖客户端帧率。
- 组件、保存和事件契约：`YukkuriEntity` 委托 `YukkuriComponents` 登记的组件（`ComponentKey`／`ComponentSpec`，分 EARLY／READY 初始化；一个组件可登记多项存读）。拆分保持完整 NBT 路径、类型、写入位置和读取条件，`YukkuriVersion` 为 1；`YukkuriVariant` ordinal 只能末尾追加。公共／客户端回调分别在 `CommonRegistration`／`ClientRegistration`。新增组件沿用 `YukkuriComponents` / `ComponentSpec`；领域组件拥有权威数据，状态登记不存副本。
- `registerGoals` 在父构造期运行，其中赋值的字段不得带初始化器（含 `= null`）；早期构造不得读取未就绪的 variant 或组件。
- 显示只观察实际运行的 Goal，不启动或探测业务。客户端经 `YukkuriView` / `HatBoatView` 读同步展示数据，不调用服务端组件 getter。
- 拆分保留 NBT 路径、类型、写入时机和已有读取契约；当前试玩场的数据结构调整不另增旧存档迁移或补偿层。公共／客户端事件分别接入 `CommonRegistration`、`ClientRegistration`，保留顺序、优先级与取消策略，避免重复订阅。
- 行为 ID 使用 `Behaviors`，类型使用 import，避免内联全限定名。`scripts/check_architecture.py --check` 仅为按需只读建议，边界见[脚本说明](scripts/README.md)。

## 美术与资源

- 打包资源只在 `src/main/resources/`，模组声明模板在 `src/main/templates/`；参考图、预览和标准素材库不复制进运行资源。
- `standards/yukkuri-library/` 是制作源，`art/source/` 是游戏选用快照，`art/runtime-sources.json` 登记对应关系。改模型先改制作源再导出，换用素材同步登记；动作约束见[制作约定](standards/yukkuri-library/AGENTS.md)。
- 专用道具使用符合含义的美术，需替换的占位登记在[待办](docs/TODO.md#美术opus)。用户说“保存 xx”即认可当前版本，直接写入制作源登记，不重复确认。
- 预览统一输出 `.local/previews/<主题>/`，总入口 `.local/previews/index.html`；更新后重建总入口，同主题只留当前预览，不删制作输入、当前模型或原始参考图。改模型后出一张当前预览并贴在回复中。
- 生成脚本和模板留在原制作目录。预览、截图、报告、验收或确认记录不入库；截图、报告分别放 `.local/screenshots/`、`.local/reports/`。认可集合和文件指纹由制作源登记保存。
- 生成文件冲突先合并制作源（动作源 motions／variants／lore／recipe.json、author.py、motion_corrections.json；语言按键、台词按行 ID 合并）再重建受影响输出。目录与文件用稳定英文 ID 命名，不用“最终版／新版2”；当前导出放 `assembled/`，待确认方案放 `drafts/<主题>/`，被替代方案直接删除、不设 `archive/`；同包脚本留包内，跨目录脚本放 `scripts/` 并登记其 README；`standards/yukkuri-v1/` 仍被读取。

## 架构红线

命中任何一条退回：(1) `motor/` 之外写位置、速度、朝向或骑乘；(2) 新增预约、租约、席位、票据或 `pending` 桥接状态（`Attempts` 预算与等待动作期限除外）；(3) 失败后原样重试、改记失败分类、同一失败计入两个预算、Blocked 后立刻再 `run` 同一动作；(4) 为某场景加条件分支，或底座出现 `isAquatic`、`boatHome()!=null` 一类分支，`aquatic/`、`client/aquatic/`、`registry/`、`CommonRegistration` 以外不 import `aquatic.`；(5) 行为或编排里写跳高、时长、曲线、冲量数值（高度距离来自 `BodyAbility`，时长来自物理或 `ClipMarkers`）；(6) 新拒绝原因没有对应手段；(7) 失败时倒放、回座、瞬移或直接改成功状态；(8) 被动时钟移动身体、调用 `run` 或改其他实体状态；(9) 为同一事实新增第二份存储，或客户端自行推导乘员是否在帽里；(10) `motor/geom/` 引用 `net.minecraft.*`；(11) 保留旧路径“以防万一”。

## 文档按变更维护

- 除待办外，文档只保留当前规则、使用方法、必要接口和来源。直接更新原段落；不新增裁决页、计划页、修订流水、阶段总结、交接记录或重复状态表。历史由 Git 保存。
- [功能说明](docs/FEATURES.md)保持精简：只写当前玩家可见规则与关键数值、用户决定和 Wiki 出处，不写实现细节、过程或未实现要求（后者只在待办）；每条规则和数值只在一处维护，待办与其他文档用链接引用。文档可能描述旧代码，疑义以代码为准并顺手改正。去重前确认唯一内容已有归属，不因压缩删除未实现要求或来源边界。
- 待办是进度、实测现象、失败版本和完成标准的唯一登记处，只放未完成项，完成即删。实测问题保留各运行版本与现象的对应关系，附可查询的 commit 链接和已知 CI run；版本不明写“版本待核实”，不用登记时的 HEAD 代替。
- 待办单条只写状态、现象与运行版本、最短完成标准，一般不超过 5 行；排查过程、旧实现的修正说明和逐轮读数不入待办。整体下线或重写的实现，其失败条目在下线时收为一行反例。
- 普通修复只更新待办原条目的当前进展和最短完成标准，不追加多轮排查、修复提交或对话流水。共享运行证据在待办集中登记一次，各问题保留版本与现象的对应关系；规则链接所属专题，不再复述。只有规则、配置、交互或接口变化才改所属专题，纯内部整理无需另写记录。
- 制作说明保留产物、接口、使用方法和可追溯来源，不写当轮日期、授权对话、执行代理或工具访问过程。来源的实际采用范围与未核验边界必须保留，不能把既有摘录改写成已核验原文。
- Wiki 只随来源证据或采用内容变化更新。生成清单只改生成源并重建受影响输出，不手改生成页。
- 场景、按钮、初态和读数在 `scripts/museum_scenarios.py` 等源码维护，不抄一份场景表；通用操作见上“开发命令”。日志文案和字段以代码为准。
- 提交前逐段检查文档差异：是否放在唯一所属位置、是否重复规则或过程、是否保留失败版本与验收边界。清理文档不得提升待办状态，也不得把代码接入、CI 或助手复跑写成用户试玩通过。

## 试玩与完成标准

- 待办状态仅用：**未做**、**部分接入**、**已接入待试玩**、**复测失败**、**数值通过·演出待补**、**已完成**。只有用户试玩认可才算完成；代码、CI 或兜底逻辑不改变验收状态。
- 数值与状态变化符合预期而演出缺失，记“数值通过·演出待补”。配音、台词、表情和演出缺口最后统一补，此前不单独开工。复测失败写明现象，移除矛盾的“已接入”表述；同一待试玩行为链只修复测失败，不叠加新内容。
- 每项修复注明场景及应观察的状态／数值。“清空本场地”后重开须恢复实体、账本和地形初态，提供合适群系、真实有限物资和链路阶段读数；场景出错先修场景。
- 场景只提供被测行为所需的真实起点，不指定 Goal 或直接完成被测环节，不绕过碰撞、护幼或性格条件。后半段行为可用已有帽船、库存和乘员作为初态，但准备成功不算行为成功；参数不同的重复按钮合并，真实路径不同的边界保留。
- 唯一试玩验收入口是用户亲自运行 `启动动作博物馆.cmd`。本机不执行 Gradle 或启动时自动编译；远端 CI 编译并运行身体层纯函数单元测试（`src/test/java`，不依赖 Minecraft，不算试玩验收），不设 GameTest。资源 `--check` 按需使用，不作合并门槛。
- `scripts/smoke_museum.py` 无界面冒烟只作试玩前自查（场景初态与链路读数），不算验收，游戏运行时不跑。
- 必要非编译检查通过后直接提交推送。CI 后台核对；成功且试玩场未运行时，按[运行包更新](#ci-运行包更新)执行 `scripts/update_museum_runtime.py --run <run-id>`。交付和 PR 简述改动、实际检查、缺口与试玩入口；没做的检查直接说明。

## CI 运行包更新

在实际游玩的机器关闭试玩场，将代码更新到目标 `main` 提交后执行 `<python> scripts/update_museum_runtime.py --run <该提交成功的 Build run ID>`。需要 `git`、已登录的 `gh` 和 `.local/experimental-map/museum-runtime.json`，运行包位于 `.local/setup/runtime-*`。脚本核对 CI 与当前提交一致后恢复资源、校验哈希、切换配置并删除旧运行包，不编译、不启动游戏；切换前失败不删除原运行包。启动器在生成世界前对照运行包提交与当前代码、资源、场景和构建配置，不一致即停止并提示更新。

## 日志留痕

- 改功能同时检查日志：玩家操作、关键链路开始／交接／完成／中断，以及伤病、亲缘、归属、库存的真实变化须有服务端记录；拒绝给实际原因。日志只观察已执行结果，不额外判断、抽签或改变业务。
- 使用 `[body]`、`[family]` 等稳定前缀。通过统一入口记录品种／类型、实体 ID、UUID、tick、维度和位置；涉及目标、资源、概率时记录身份、前后值、失败条件或已算出的概率，不把尝试写成成功。客户端不重复记服务端业务。
- 关键事件默认 INFO；扫描无候选、持续寻路、每 tick 数值和几何采样不逐次 INFO。重复等待／拒绝按实体与原因限频，连续消耗记阈值、阶段或汇总；详尽采样需显式开关。缓存有界、不进存档，不转储完整 NBT、背包或长路径。
- 排查结合实际运行版本和现场读数；没有日志不等于行为未发生。实时日志问题统一登记到待办的对应模块。
- 调试开关默认关闭：`/yukkuri status trace true|false`（细密状态日志）、`debug.entitySaveViews`（完整实体保存视图）、客户端 `render.woundPerfLog`（`[render]` 伤口渲染耗时）、客户端参数 `-Dmcyukkuri.aqdiag=true`（`[hat-input]` 右键输入候选）；用完关闭。
- 公共入口 `status/GameplayLog`：`event(entity, category, message, values...)`（附品种、实体 ID、UUID、tick、维度、位置）、`world(level, ...)`（无存活实体的事务）、`limited`／`worldLimited`（按主体、类别、稳定原因键限频，缓存每桶最多 64 键、不入存档；下次输出带 `suppressed=N`，键不含动态坐标或时间）。跨重载关联用 UUID。
- 稳定前缀按领域划分，如 `[species]` `[body]` `[remains]` `[family]` `[childhood]` `[choreo]` `[approach]` `[care]` `[offering]` `[incident]` `[water]` `[settlement]` `[habitat]` `[meal]` `[needs]` `[interaction]` `[shop]` `[exam]` `[custody]` `[badge]` `[intent]` `[method]` `[action]` `[aquatic]` `[hat]` `[headwear]` `[winter]` `[move]` `[social]` `[wild-event]` `[mind]` `[predator]`；新增前缀沿用此命名。默认省略每 tick 的移动、朝向、几何、动画、台词、代谢和寻路节点；接近、预留、获准路线不代表业务完成，初态准备成功不是玩法成功；读档与场景直接写初态不重放历史事务。限频后的沉默或缺日志不能单独证明行为没有发生。
