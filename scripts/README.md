# 项目脚本

所有新预览统一写到 `.local/previews/<主题>/`，总入口为 `.local/previews/index.html`。同主题只保留当前版本；截图和检查报告分别写到 `.local/screenshots/`、`.local/reports/`，不入库。

`python scripts/build_preview_hub.py --render` 生成集中入口、对应动作 GIF、当前模型缩略图与动作总览图；需要 Pillow、numpy 和 Node.js。`python scripts/serve_review.py --port 8766` 只启动本机 HTTP 服务，再手动打开它输出的地址。页面模板在 `preview/hub/`，模型查看器源码在 `preview/review/`。生成流程不启动浏览器、游戏或 Java 编译。

`python scripts/test_runtime_atlas.py` 检查原生方块／物品模型引用的模组贴图是否存在，并核对 `textures/props/` 已通过 `assets/minecraft/atlases/blocks.json` 加入方块图集；真实客户端重载后仍须检查缺图警告与实际外观。

`python scripts/test_asset_export_checks.py` 检查生活、家庭、家油照料与周边四个导出器的只读检查模式，注入源／运行文件漂移并确认检查失败。普通 JSON 仅容忍 LF／CRLF 差异，仍核对字段顺序、空格与结尾换行；PNG 检查仍按字节比较，生成器仅在格式、模式、尺寸和像素完全一致时复用现有编码。三个道具包的网格生成器仅复用符号零（`-0.0`／`0.0`）等值的既有表示；其 Blockbench 模型按 JSON 内容比较，仅容忍顶点坐标不超过 `1e-15` 的计算尾差和内嵌 PNG 的等价编码。草麦食盆先核对预览图集裁切像素，再复用所选原始调色板字节。生活口部数据从圆润选用网格重新推导并对照选用记录；遗体物品模型从 `art/props/house-care/yukkuri_remains.item.json` 复制当前渲染配置，不恢复旧的 bundle 占位图标。

跨平台模型指纹通过 `runtime_model_fingerprint.py` 比较原始、LF 和 CRLF 三种字节表示；Java 客户端及 GameTest 使用相同的 `ModelFingerprint` 规则。只容忍换行转换，不忽略模型数值、空格、字段顺序或结尾换行的变化。保留已有导出指纹，避免仅因跨平台检出而更换存档中的表面标识。`python scripts/test_runtime_model_fingerprint.py` 核对换行兼容、内容漂移拒绝及19种已打包模型、23组脱下帽饰的徽章挂接资源；Gradle `check` 同时运行 `verifyModelFingerprint`。

`verify_puff_revision.cjs` 检查第七版鼓腮形变：细分皮肤闭合且静止时与原身体重合，五官与形变以外的部件全程不动，根骨骼不动，侧发外摆为刚体，包络回到零，打断后常态与基础模型一致。当前预览输出到 `art/source/motion-corrections/previews/current/`，检查输出到该包的 `reports/current/checks.json`；`--checks-only` 不渲染。只做离线渲染，不操作界面。

鼓腮、侧弯、跳跃与体操由 `export_motion_corrections.py` 从动作配方和19种当前骨架编译第2版覆盖资源，并生成各形态的 `cheek_puff.png`；`--check` 只核对，`--only id,... --trial 目录` 把试验结果写到指定目录而不碰运行资源。替换基础模型后重新执行。`verify_motion_corrections.cjs` 检查地面接触、挂接一致性、跳跃高度及表情时段，并离线生成前后对比与动图，不操作浏览器。见[当前动作适配](../art/source/motion-corrections/README.md)。

素材集中检查使用 `build_review_catalog.py` 从当前运行资源生成模型／声音索引，`start_review.ps1` 启动仅监听本机的只读服务；`-NoOpen` 只启动服务并输出地址，不打开浏览器。`stop_review.ps1` 按进程记录停止该服务。`verify_review.cjs` 通过命令行验证动作数据并生成模型缩略图，不操作浏览器或游戏。入口见[素材检查室](../preview/review/README.md)。

灵梦稀毛／歪脸的定向导出为 `export_reimu_deficient_rounded.py`，复用现有不足形态圆润生成逻辑；`preview_reimu_deficient.py` 生成当前四视图和动作检查，页面检查用 `node scripts/verify_marisa_deficient_browser.cjs reimu`。来源见[制作包](../standards/yukkuri-library/subspecies/deficient/rounded-reimu/README.md)。

人工试玩使用[实验地图](../docs/EXPERIMENTAL_MAP.md)：`build_experimental_map.py` 在 `.local/experimental-map/` 生成全新世界的数据包和元数据，再由游戏完成16区场地落地；`launch_experimental_map.ps1` 读取固定运行清单，直接进入 `run/saves/MCyukkuri-Lab/`。根目录 `启动实验地图.cmd` 是双击入口，不重新构建，不覆盖既有存档。

生育资格修正复用消耗品测试中的 `fertilityRules`，消耗品、成长与联合运行器均收集 `fertility-checks`。联合运行器当前要求 43 项 GameTest 及完整报告，已加入遗体与铲子安葬检查。徽章专项运行器同时核对原有报告与 `badge-timing-checks`，报告缺失即失败。

圆润体型、四轴性格与灵胖发育使用 `personality_deibu_checks.init.gradle`、`run_personality_deibu_checks.py --suite integration` 隔离构建并冻结全机制GameTest；`run_rounded_preview.py` 隐藏启动本轮独立客户端。新素材依次使用 `export_rounded_runtime.py`、`rounded_wound_surface.cjs`、`export_rounded_attachments.py`、`export_badges.py --mounts-only`、`export_family_assets.py` 和 `build_rounded_preview.py`；束具仍由标准库 `drafts/operation-tools/build.py` 生成。来源与后续检查见[圆润运行包](../art/source/rounded-runtime/README.md)。

成长与寿命使用 `growth_checks.init.gradle` 隔离类、资源、运行参数和 Gradle 项目缓存；`run_growth_checks.py` 冻结成功构建后的产物，在 `.local/deibu-life-design/` 运行成长迁移、家庭和胎生七项 GameTest。

联合验收使用 `port_integration.init.gradle` 隔离每个 source set 的真实输出与运行 classpath；`run_port_integration.py` 冻结、校验并隐藏启动本任务产物，`accept_port_integration.py` 驱动真实专服双客户端流程。

消耗品与普通胎生使用 `consumable_checks.init.gradle` 显式隔离所有 source set 的编译输出，配合独立 Gradle project cache；`run_consumable_checks.py` 冻结产物、在 `.local/consumables-birth/` 新建世界并核对五份报告。构建与运行命令见 [专题实现](../docs/CONSUMABLES_AND_BIRTH.md)，不启动客户端或代为接受 EULA。

接触器具的制作与导出入口为 `standards/yukkuri-library/drafts/operation-tools/build.py`。它从参数配方及所选角色表面制作器具、固定带、铁箱和培养瓶，同步可编辑模型、接入快照、运行资源、配方与指纹；`--check` 只核对导出。当前版本为待确认的游戏适配版。

| 脚本 | 输入与用途 | 执行边界 |
| --- | --- | --- |
| `export_predator_assets.py` | 从捕食种配方导出的模型同步两种实体快照、256 图集、19+4 动作与来源登记 | 只导蕾米莉亚、弗兰，不替换现有七种 |
| `preview_predators.py` | 生成两种运行数据交互预览和八视图拼图 | 使用实际导出网格离线渲染，不启动游戏 |
| `export_predator_language.py` | 增量合并捕食种专属语言、生成蛋模型和低权重森林生成规则 | 保留其他任务语言键 |
| `run_predator_checks.py` | 从独立构建冻结运行资源，执行捕食或商店引擎检查 | 输出位于 `.local/predators/`，要求最近完整构建成功 |
| `predator_preview.py` | 准备独立客户端、发出预览／采集／停止请求 | 使用本任务冻结快照和一次性世界，不控制其他任务进程 |
| `export_wound_surfaces.cjs` | 读取七种当前运行角色与选定 R3.1 源，导出外皮细分、五官挂接、骨架、动作及材质；记录来源指纹 | 写 `yukkuri_wounds/` 与伤口适配包，不修改标准角色源，不启动游戏 |
| `verify_wound_runtime.py` | 核对伤口源与输出指纹、有限数值及共点权重一致性 | 只读，不启动游戏 |
| `run_wound_checks.py` | 使用既有 JDK 21、Gradle 参数和编译夹具启动伤口 GameTest 或隔离客户端 | 冻结每轮运行产物；隐藏启动，世界与日志写 `.local/wound-integration/`，完成后停止本轮进程 |
| `capture_wound_preview.py` | 在已就绪的伤口客户端录制七种角色、多方向和动作，核对服务端与客户端伤情 | 仅向该客户端顺序发送预览请求，不并发控制；PNG 与状态写本轮临时目录 |
| `export_human_world.py` | 从制作源同步商店、三类工厂及旧模板、生成规则、五套服装、原创机台与商品、生成蛋及中英语言；`--check` 核对指纹 | 默认写本模块资源与登记；检查只读，不启动游戏 |
| `run_human_checks.py` | 使用 Gradle 已编译产物，运行人类交易与工厂生产两组 GameTest | 每次冻结运行文件并新建隔离世界，避免旧亲缘和人口记录影响繁育；隐藏启动，报告默认写 `.local/human-world/gametest-clean/`，可用 `--work-directory .local/<任务>/gametest` 隔离，不接受 EULA |
| `catalogue_hakkei_dialogue.py` | 只读原版八景 U 角色 CSV 与 ModLab 翻译／新增 TSV，生成分离的对白检索与来源指纹 | 写 `art/dialogue/native-u/`，不写来源游戏和运行资源 |
| `build_aquestalk_dialogue.py` | 读取选句与配方，调用本机 AquesTalkPlayer，输出 288 条 WAV／OGG、发音符号及试听页；204 原版与 84 原创分别标注 | Windows 本机制作；`--work-directory` 隔离工具副本，匹配文本、参数和指纹时复用录音；不打包程序、字典或 DLL |
| `export_dialogue.py` | 核对原句／原创来源与录音文本，导出台词、语言资源、OGG、时长及声音索引，保留其他任务条目 | 默认写运行资源；`--stage` 先导出到指定待核对目录；`--check` 只读检查，不启动游戏 |
| `export_runtime_models.py` | 读取五个标准种族及两种家油导出；制作快照、适配骨架、运行 JSON、贴图和静态预览数据，并更新来源登记 | 只写静态美术数据；不启动游戏、页面或测试 |
| `runtime_house_interactions.py` | 供模型导出器调用；制作家油俯身夹取、短辫持物、放下与蠕动曲线，登记辫梢挂点 | 无独立启动入口，不改标准造型 |
| `runtime_waddle.py` | 供模型导出器调用；将家油走动适配至实际连续身体截面，增强五种普通角色的油菊摆动 | 不写文件，不改标准造型，保留 1.2 秒周期 |
| `runtime_animation.py` | 供模型导出器调用；按游戏矩阵采样并生成帕秋莉长发接地骨骼与混合绑定 | 无独立启动入口，不改标准造型 |
| `export_badges.py` | 从徽章挂点配方及当前饰物几何导出挂接数据、手册配方和语言；`--check` 核对一致性 | 默认写游戏资源；检查模式只读 |
| `run_badge_checks.py` | 在冻结构建和独立临时世界中验证金银章训练、考试、佩戴、保存与社会反应 | JDK 21；报告写 `.local/badges/gametest-clean/`；缺少实际报告时判失败 |
| `badge_checks.init.gradle` | 固定徽章任务所有 source set 的实际编译输出到 `.local/badges/build` | 配合独立 `--project-cache-dir` 与 `--no-configuration-cache`；详见 `docs/BADGES.md` |
| `validate_entity_assets.py` | 读取九种运行模型，检查来源指纹、骨架、共享顶点绑定、19 个动作时长、贴图及生成蛋资源 | 只读检查，可按任务需要在本机或 CI 运行 |
| `export_living_assets.py` | 从生活设施配方及圆润选用网格导出当前归属的方块状态、材质与嘴部表现数据，更新来源指纹；`--check` 检查现有导出 | 周边模型交给周边导出器；默认写资源与来源登记，检查模式只读，不启动游戏 |
| `export_family_assets.py` | 从生命周期和家庭配方导出共用茎形、挂接与成长参数；`--check` 核对指纹 | 只写游戏适配资源，不覆盖角色标准 |
| `export_house_assets.py` | 从家油照料配方与遗体物品渲染配置选用源导出当前归属的方块状态、物品、合成和来源；`--check` 核对导出 | 默认写资源，检查模式只读 |
| `export_peripheral_assets.py` | 从周边固定快照导出静态网格、贴图、物品／方块调度和合成，记录输入输出指纹；`--check` 核对导出 | 只读取所选快照，不改变标准确认；草麦引用原版贴图资源位置 |
| `run_p0.py` | 准备隔离配置、启动已有 JDK 21 与 Gradle 生成的运行入口、发送 JSON 控制请求 | 世界、日志与结果只写 `.local/p0/`；隐藏启动窗口；不代为接受 EULA；进程停止使用对应目标的 `stop` 请求 |
| `run_settlement_checks.py` | 复用 Gradle 生成的 GameTest 启动参数，只运行群落、住所、返家与物资维护检查 | 需 JDK 21 与已编译夹具；世界和报告写 `.local/settlements/gametest/`，隐藏无窗口运行，不接受 EULA |
| `capture_p0.py` | 在已加载的 P0 单人预览中逐一录制 19 个动作和家油重点动作 | 写实际 Minecraft 帧缓冲 PNG 与状态时间线；约两分钟，期间不要向同一客户端发送其他请求 |

标准素材包的制作和局部导出脚本保留在 `standards/yukkuri-library/` 内，见其 [目录约定](../standards/yukkuri-library/AGENTS.md)。不要把有相对路径依赖的包内脚本移到这里。

P0 的准备命令、控制协议及验收边界见 [P0 测试夹具](../docs/P0_VALIDATION.md)。同一目标一次只能有一个实例，所有控制请求按顺序发送。

新增脚本须说明输入、输出、是否写文件及适用环境。临时脚本放项目内 `.local/`；可复用后再整理为正式入口。所有脚本遵守根目录 [AGENTS.md](../AGENTS.md) 的本机执行限制。

Wiki 漫画参考使用 `wiki_reference_library.py`（Python 3.10+、Pillow）：`fetch` 读取 `wiki/art-references/sources.json`，从登记的公开作品正文保存原图与来源清单；缓存、失败记录放 `.local/reference/wiki-manga/`。`check` 离线只读核对解码、尺寸、哈希和文件登记；`catalog` 从已核读 manifest 生成逐作品 Markdown 目录；`sheets` 只在 `.local/` 生成联系表。它不操作浏览器、不登录站点、不绕过证书验证，不写游戏资源。详见[参考库维护](../wiki/art-references/README.md#维护命令)。

E-Hentai 补充库使用 `wiki_ehentai_reference.py`（Python 3.10+、Pillow）：`fetch` 读取 `wiki/art-references/ehentai/sources.json`，按登记上限与实际公开图页链接增量保存图片及 manifest；网页缓存和总览放 `.local/reference/ehentai/`。`check` 离线只读核对登记、解码、尺寸和 SHA-256；`catalog` 生成该子库作品目录；`sheets` 生成临时总览。只保存站点图页提供的版本，不承诺作者母版；不使用登录凭据、浏览器自动化、付费下载或访问限制绕过。选页须在图集首页已经观察到，新页默认未核读。详见[补充库维护](../wiki/art-references/ehentai/README.md#登记与维护)。

亚种与先天形态入口，详见[实现说明](../docs/SUBSPECIES_IMPLEMENTATION.md)：

| 脚本 | 用途与写入边界 |
| --- | --- |
| `export_subspecies.py` | 读取已选螺壳／多毛源与不足形态配方，生成八套模型、快照、口部数据、帽船和螺壳道具；`--stage` 只写源与快照，默认同步自身运行资源；`--textures-only` 只补表情导出，`--check-textures` 只读核对128图覆盖、解码、尺寸和来源指纹；需 Python/Pillow |
| `export_subspecies_wounds.cjs` | 读取八套实际运行几何与既有受伤表面算法，仅重建对应受伤表面和指纹；需 Node.js |
| `preview_subspecies.py` | 从本轮运行三角面和贴图生成离线交互页、六角度拼图与40幅生态表情图；需 Python/Pillow 和现有 Node.js，不启动游戏 |
| `subspecies_checks.init.gradle` | 隔离各 source set 的编译／资源输出并选中亚种 GameTest；须搭配独立项目缓存与禁用配置缓存 |

日常生态入口：`export_ecology_expressions.py` 选择已确认表情，`build_ecology_audio.py` 制作原创短哼唱，二者支持只读 `--check`；构建先使用 `-I scripts/ecology-build.init.gradle --project-cache-dir .local/ecology/gradle-cache --no-configuration-cache` 隔离实际类、资源和 classpath，随后 `run_ecology_checks.py gametest/preview` 冻结本任务成功产物到 `.local/ecology/` 并运行隔离测试或隐藏客户端。`--suite living` 单独运行旧生活回归；`preview --world-from <目录>` 可选择已经通过生态报告的隔离世界。说明与验收见[日常生态记录](../docs/ECOLOGY_DAILY_LIFE.md)。

认知检查使用 `cognition_checks.init.gradle` 隔离实际类、资源与 classpath，搭配 `--project-cache-dir .local/cognition/gradle-cache --no-configuration-cache` 完成构建。
`run_cognition_checks.py gametest` 将本轮产物、启动参数与逐文件指纹冻结到 `.local/cognition/` 并运行认知专项；`preview` 可运行隔离图鉴客户端，须先协调采集时段。输入为成功构建输出及 JDK 21，写入只在本任务临时目录，不使用玩家存档。见[认知能力实现](../docs/COGNITION_IMPLEMENTATION.md)。

`verify_puff_face_contact.cjs` 对运行资源做精确接触检查：在8个鼓腮时刻以三角形逐对相交测试皮肤与头发／饰物、外摆侧发与其他部件、皮肤自交，并检查原本扎根于皮肤的部件是否被埋、不透明五官像素是否被皮肤遮挡、腮红间距与地面；以静止姿态已有的接触为基线。输出 `art/source/motion-corrections/reports/current/face-contact.json`，本机检查全部19种形态约需20分钟。命令行离线检查，不启动或操作浏览器。

`verify_puff_java.cjs` 离线调用生产 `YukkuriModel` 的 `applyFit`、`applyRevision`、构造函数和 `render()`，以记录型 `VertexConsumer` 采集三角形，与 `pose.js` 逐三角比较，并比较鼓腮包络。需要 Java 21 和运行 classpath：以 `puff_checks.init.gradle` 隔离构建时执行 `writePuffClasspath`，写到 `.local/puff-v7/classpath.txt`（可用 `PUFF_CLASSPATH` 指定）；不启动 Minecraft。该初始化脚本把编译与资源输出都放到 `.local/puff-v7/build/`。输出该包的 `reports/current/java-parity.json`，不是实机渲染验收。另可用 `placement_fit_checks.init.gradle` 隔离构建并执行 `writePlacementFitClasspath`，输出与 classpath 位于 `.local/placement-fit/`，再以 `PUFF_CLASSPATH=.local/placement-fit/classpath.txt` 运行。

头饰与头发位置适配由 `export_placement_fit.cjs` 从[制作源](../standards/yukkuri-library/presentation/placement-fit/README.md)和当前运行模型导出 `yukkuri_presentation/placement_fit.json` 与来源清单；`--check` 只核对，`--only id,... --out 文件` 写试验结果。基础模型不改写。`placement_fit.py` 是 Python 导出器共用的拼接规则：`export_motion_corrections.py`（鼓腮）与 `export_hat_interactions.py`（脱帽、帽托和幼油尺寸）都读取拼接后的网格。`verify_placement_fit.cjs` 检查头饰透穿、头发进入身体、五官遮挡、蝴蝶结贴合与地面，并输出前后对比、脱帽、动作和未改形态预览；`--checks-only` 不渲染。只做离线计算与渲染，不操作界面或游戏。见[游戏适配记录](../art/source/placement-fit/README.md)。

游戏参考分类使用 `wiki_reference_topics.py build/check`：以 `wiki/reference-library/game-annotations.json` 的逐图实体、情景、情绪、制作目标、画格和用途为主，合并图片 manifest 与小说阅读卡。生成四个交叉索引、兼容旧主题的页面、精选清单与离线筛选器；页面模板为 `wiki_reference_gallery.html`。默认显示已写明用途的游戏图片，精选优先，可组合筛选实体／情景／情绪／目标与画风。检查实体范围、所有存图的用途或队列状态、实际文件哈希和生成一致性。`quality-selection.json` 只提供辅助质量标签。两个抓取器均跳过 `collect: false` 来源；E-Hentai 的 `fetch --source` 若显式请求停采来源会在联网前拒绝。见[游戏参考库](../wiki/reference-library/README.md)。
