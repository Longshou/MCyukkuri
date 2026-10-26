# 项目脚本

`python scripts/check_architecture.py --check` 是 Python 3.10+ 标准库的只读架构建议工具；默认不带 `--check` 也只读。它只扫描本仓库 `src/main/java/**/*.java`，不扫描 `.worktrees`、`.local`、资源或文档，不调用 Git、Java、Gradle、游戏、网络，也不修改文件。不接入 CI、pre-commit 或合并门槛，不替代远端编译或用户试玩。

`python scripts/update_museum_runtime.py --run <run-id>` 为已有试玩配置安装同一提交的成功 CI 运行包，不编译、不启动游戏；更新与旧包清理规则见[开发指南](../docs/DEVELOPMENT.md#ci-运行包更新)，在实际游玩的机器执行。

试玩日志由游戏和启动器自动保存；用户直接告诉 AI 场景与大概时间，无需导出工具。AI 的查找入口、版本核实与来源选择见[试玩日志](../docs/DEVELOPMENT.md#试玩日志与事后排查)。

## 架构建议检查

检查客户端读取服务端组件、未许可的 `getPersistentData`、原始 `isHouse/isPredator` 和内联全限定类型名；覆盖调用、方法引用、跨空白与显式泛型调用，排除注释、字符串、字符、text block 和 package/import。同步视图与快照不因组件词根而禁止。

`architecture_exceptions.json` 按精确文件／方法签名、归一化 token SHA-256、出现次数和源码顺序记录许可；每个入口列 receiver、键读写方向与理由。方法代码、字面量、签名或次数变化须复核；注释和字面量外排版不改变 hash。两个方法外键常量 `GroupCards.BAR`、`Cannibalism.HUNT_COOLDOWN` 也按 token 锁定。没有目录／类级豁免或自动刷新基线命令，不能只换 hash 消除诊断。

这是有限词法检查：仅解析确定声明、简单 `var` 别名、`instanceof` 绑定及可确定的字段；复杂工厂、继承、跨方法数据流、动态 NBT 子键和别名逃逸仍需人工复核，尤其是 `Custody.store` 入参。未知 receiver、方法归属不明、Unicode escape、词法／括号错误、包路径不符及读取失败会报告覆盖缺口。全名检查从标准包根和源码 package/import 收集包根，按大写类型段识别；全小写长链报人工核对，非常规外部包根和复杂嵌套／泛型类型不保证完整覆盖。组件 getter 返回类型与 `ComponentKey` 登记交叉核对，但不证明全部 Java 语义成立。

诊断按路径／行／列排序；退出码 `0` 为扫描范围内无发现，`1` 为违规或许可漂移，`2` 为覆盖／读取／配置错误（优先）。`--json` 输出同样诊断；`--root <目录>` 只改扫描根，仍使用本工具旁许可。没有修复模式；报告需由调用者重定向至 `.local/reports/`。

## 预览与导出

所有新预览统一写到 `.local/previews/<主题>/`，总入口为 `.local/previews/index.html`。同主题只保留当前版本；截图和检查报告分别写到 `.local/screenshots/`、`.local/reports/`，不入库。

`python scripts/build_preview_hub.py` 生成纯预览入口、当前模型／物品缩略图、声音索引与连续场景 GIF／可拖动帧序列；`--render` 另行生成专项图（部分依赖现有原版资源 jar）。需要 Pillow、numpy 和 Node.js。`build_preview_props.py` 复用制作源的软件渲染器，只读当前打包网格和贴图，不导出游戏资源。`python scripts/serve_review.py --port 8766` 只启动本机 HTTP 服务，再手动打开它输出的地址。页面模板在 `preview/hub/`，模型查看器源码在 `preview/review/`；收录与专项图片更新边界见[预览说明](../preview/README.md#内容与边界)。生成流程不启动浏览器、游戏或 Java 编译。

总入口调用 `build_preview_library.py`，按 `preview/hub/categories.json` 生成游戏分类目录、物品缩略图及试玩入口；`preview_block_model.py` 适配原生方块元素供离线渲染；`preview_inventory.py` 从 Java 注册与运行引用过滤残留资源。它们只读资源与场景源码，输出在 `.local/previews/`，分类缺口报告在 `.local/reports/preview-library.json`；不会生成试玩世界或运行场景。

`asset_export_checks.py` 是各导出器 `--check` 共用的只读比较库（生活、家庭、家油照料与周边）。普通 JSON 仅容忍 LF／CRLF 差异，仍核对字段顺序、空格与结尾换行；PNG 检查按字节比较，生成器仅在格式、模式、尺寸和像素完全一致时复用现有编码。三个道具包的网格生成器仅复用符号零（`-0.0`／`0.0`）等值的既有表示；其 Blockbench 模型按 JSON 内容比较，仅容忍顶点坐标不超过 `1e-15` 的计算尾差和内嵌 PNG 的等价编码。草麦食盆先核对预览图集裁切像素，再复用所选原始调色板字节。生活口部数据从圆润选用网格重新推导并对照选用记录；遗体物品模型从 `art/props/house-care/yukkuri_remains.item.json` 复制当前渲染配置。

跨平台模型指纹通过 `runtime_model_fingerprint.py` 比较原始、LF 和 CRLF 三种字节表示；Java 客户端使用相同的 `ModelFingerprint` 规则。只容忍换行转换，不忽略模型数值、空格、字段顺序或结尾换行的变化。保留已有导出指纹，避免仅因跨平台检出而更换存档中的表面标识。

`verify_puff_motion.cjs` 检查整身鼓腮：每种形态鼓腮为只驱动根骨骼的2.8秒循环且首尾回到静止、不再带脸颊形变与包络、鼓起幅度在预算内（豆形不拉高）、俯仰≤4°侧倾≤2°无转向、最低点不离地、表情时间线以 `cheek_puff` 收尾并让位于哭脸、完整体操段脸时间线一致。只读运行资源，离线运行。

整身鼓腮、侧弯、跳跃与体操由 `export_motion_corrections.py` 从动作配方和19种当前骨架生成动作覆盖资源，并生成各形态的 `cheek_puff.png`；`--check` 只核对，`--only id,... --trial 目录` 把试验结果写到指定目录而不碰运行资源。替换基础模型后重新执行；`--update-only id,...` 只重建这些形态并保留其他形态的已选片段，可与 `--check` 合用。`verify_motion_corrections.cjs` 检查地面接触、挂接一致性、跳跃高度及表情时段（鼓腮按2.8秒），并离线生成前后对比与动图，不操作浏览器。见[当前动作适配](../art/source/motion-corrections/README.md)。

素材集中检查使用 `build_review_catalog.py` 从当前运行资源生成模型／声音索引，`start_review.ps1` 启动仅监听本机的只读服务；`-NoOpen` 只启动服务并输出地址，不打开浏览器。`stop_review.ps1` 按进程记录停止该服务。`node scripts/verify_review.cjs` 通过命令行验证动作数据并生成模型缩略图，不操作浏览器或游戏；Python 需有 Pillow，可用 `REVIEW_PYTHON` 指定解释器。检查数据写入 `.local/reports/model-review/`，联系表写入 `.local/previews/model-review/`，缩略图写入 `.local/previews/models/thumbs/`。入口见[素材检查室](../preview/README.md)。

灵梦稀毛／歪脸的定向导出为 `export_reimu_deficient_rounded.py`，复用现有不足形态圆润生成逻辑。当前运行模型统一用 `build_preview_hub.py --render` 离线预览。`preview_reimu_deficient.py`／`preview_marisa_deficient.py` 保留源、快照、运行资源指纹与动作采样检查，使用 Python、Pillow、numpy 与 Node.js，预览写入 `.local/previews/<reimu|marisa>-deficient-rounded/`，报告写入 `.local/reports/` 下同名主题目录；运行后重建预览总入口。通用预览不替代这些专项检查。来源见[制作包](../standards/yukkuri-library/subspecies/deficient/rounded-reimu/README.md)。

`verify_marisa_deficient_browser.cjs`、`verify_rounded_browser.cjs`、`verify_hat_interactions.cjs` 是历史浏览器自动化工具，不是当前 Agent 的检查入口；按根 AGENTS.md，不执行这些脚本。交互页面效果由用户手工查看。

人工试玩当前使用[油库里试玩场](../docs/DEVELOPMENT.md)：`build_museum_full.py --install` 现场生成全新世界（数据包加种子 `level.dat` 的元数据）到 `.local/experimental-map/museum`，整体替换，不做原地修补；各开放场地的地形在 `museum_fields.py`，场景在 `museum_scenarios.py`，生成真实个体的词汇在 `museum_dsl.py`，按钮与观察台在 `museum_sites.py`，动作目录在 `museum_actions.py`；需要 Python 3.11+、nbtlib。`--catalog` 另外生成完整动作适配清单。根目录 `启动动作博物馆.cmd`（唯一的试玩启动入口，文件名沿用）调用 `launch_museum.ps1`：世界缺失或生成器改动时先生成，再用已有 CI 运行产物启动，不编译；运行模组比数据包旧时，进图会大字提示“运行模组过旧”。`check_museum_pack.py` 离线核对数据包里的每条命令与函数引用、场地布局、按钮与场景的对应，`render_museum_map.py` 画俯视布局图。其他输出目录必须是 `.local/experimental-map` 下不存在的新目录。

圆润体型与灵胖发育的资源导出使用 `export_rounded_runtime.py`、`rounded_wound_surface.cjs`、`export_rounded_attachments.py`、`export_badges.py --mounts-only` 和 `export_family_assets.py`；按受影响的资源选择，不要求逐个执行。束具仍由标准库 `drafts/operation-tools/build.py` 生成。`build_rounded_preview.py` 将专项预览模板写入 `.local/previews/rounded-runtime/`，只服务专项预览依赖，不作为当前通用预览入口。来源见[圆润运行包](../art/source/rounded-runtime/README.md)。

接触器具的制作与导出入口为 `standards/yukkuri-library/drafts/operation-tools/build.py`。它从参数配方及所选角色表面制作器具、固定带、铁箱和培养瓶，同步可编辑模型、接入快照、运行资源、配方与指纹；`--check` 只核对导出。确认状态见制作源登记。

| 脚本 | 输入与用途 | 执行边界 |
| --- | --- | --- |
| `export_predator_assets.py` | 从捕食种配方导出的模型同步两种实体快照、256 图集、19+4 动作与来源登记 | 只导蕾米莉亚、弗兰，不替换现有七种 |
| `preview_predators.py` | 生成两种运行数据交互预览和八视图拼图 | 使用实际导出网格离线渲染，不启动游戏 |
| `settlement_site_preview.py` | 用本机 JDK 编译模组源码与 `settlement_site_preview/SiteDump.java`，由真实的 `SitePlanner`／`SiteBuilder` 在内存里的合成坡地／平地上建出各适居群系的小群与村，镜像 `NaturalShelter.survey` 逐洞勘测并检查空地到洞口、嗯嗯坑的可达（`--seeds N`），出等轴预览 | 不用 Gradle、不启动游戏；需要主检出的 `.local/tools/java`、`build/moddev/artifacts` 合并 jar 与 `.local/gradle-home`；预览写 `.local/previews/wild-settlements/`，`build_preview_hub.py` 只收录输入指纹一致的现有输出，不调用此编译脚本 |
| `export_predator_language.py` | 增量合并捕食种专属语言、生成蛋模型和低权重森林生成规则 | 保留其他任务语言键 |
| `export_wound_surfaces.cjs` | 读取七种当前运行角色与选定 R3.1 源，导出外皮细分、五官挂接、骨架、动作及材质；记录来源指纹 | 写 `yukkuri_wounds/` 与伤口适配包，不修改标准角色源，不启动游戏 |
| `verify_wound_runtime.py` | 核对伤口源与输出指纹、有限数值及共点权重一致性 | 只读，不启动游戏 |
| `export_human_world.py` | 从制作源同步商店、三类工厂、生成规则、五套服装、原创机台与商品、生成蛋及中英语言；`--check` 核对指纹 | 默认写本模块资源与登记；检查只读，不启动游戏 |
| `catalogue_hakkei_dialogue.py` | 只读原版八景 U 角色 CSV 与 ModLab 翻译／新增 TSV，生成分离的对白检索与来源指纹 | 写 `art/dialogue/native-u/`，不写来源游戏和运行资源 |
| `build_aquestalk_dialogue.py` | 读取选句与配方，调用本机 AquesTalkPlayer，输出 288 条 WAV／OGG、发音符号及试听页；204 原版与 84 原创分别标注 | Windows 本机制作；`--work-directory` 隔离工具副本，匹配文本、参数和指纹时复用录音；不打包程序、字典或 DLL |
| `export_dialogue.py` | 核对原句／原创来源与录音文本，导出台词、语言资源、OGG、时长及声音索引，保留其他任务条目 | 默认写运行资源；`--stage` 先导出到指定待核对目录；`--check` 只读检查，不启动游戏 |
| `export_runtime_models.py` | 旧七模型批量接入配方；当前圆润选用存在时拒绝执行，避免覆盖模型 | 当前圆润形态用 `export_rounded_runtime.py`；家油定向导出仍复用其函数，见对应制作包 |
| `runtime_house_interactions.py` | 供模型导出器调用；制作家油俯身夹取、短辫持物、放下与蠕动曲线，登记辫梢挂点 | 无独立启动入口，不改标准造型 |
| `runtime_waddle.py` | 供模型导出器调用；将家油走动适配至实际连续身体截面，增强五种普通角色的油菊摆动 | 不写文件，不改标准造型，保留 1.2 秒周期 |
| `runtime_animation.py` | 供模型导出器调用；按游戏矩阵采样并生成帕秋莉长发接地骨骼与混合绑定 | 无独立启动入口，不改标准造型 |
| `export_badges.py` | 从徽章挂点配方及当前饰物几何导出挂接数据、手册配方和语言；`--check` 核对一致性 | 默认写游戏资源；检查模式只读 |
| `export_living_assets.py` | 从生活设施配方及圆润选用网格导出当前归属的方块状态、材质与嘴部表现数据，更新来源指纹；`--check` 检查现有导出 | 周边模型交给周边导出器；默认写资源与来源登记，检查模式只读，不启动游戏 |
| `export_family_assets.py` | 从生命周期和家庭配方导出共用茎形、挂接与成长参数；`--check` 核对指纹 | 只写游戏适配资源，不覆盖角色标准 |
| `export_house_assets.py` | 从家油照料配方与遗体物品渲染配置选用源导出当前归属的方块状态、物品、合成和来源；`--check` 核对导出 | 默认写资源，检查模式只读 |
| `export_peripheral_assets.py` | 从周边固定快照导出静态网格、贴图、物品／方块调度和合成，记录输入输出指纹；`--check` 核对导出 | 只读取所选快照，不改变标准确认；草麦引用原版贴图资源位置 |
| `export_pet_goods_icons.py` | 把 `art/props/pet-goods/` 的四张 16×16 宠物照料图标（糖粒、布丁、牵引胴带、襁褓布）导出到 `textures/item/`，写 `art/props/pet-goods/provenance.json`；`--check` 核对 | 只读快照；默认写资源与来源登记，检查模式只读 |

标准素材包的制作和局部导出脚本保留在 `standards/yukkuri-library/` 内，见其 [目录约定](../standards/yukkuri-library/AGENTS.md)。不要把有相对路径依赖的包内脚本移到这里。

新增脚本须说明输入、输出、是否写文件及适用环境。临时脚本放项目内 `.local/`；可复用后再整理为正式入口。所有脚本遵守根目录 [AGENTS.md](../AGENTS.md) 的本机执行限制。

Wiki 漫画参考使用 `wiki_reference_library.py`（Python 3.10+、Pillow）：`fetch` 读取 `wiki/art-references/sources.json`，从登记的公开作品正文保存原图与来源清单；缓存、失败记录放 `.local/reference/wiki-manga/`。`check` 离线只读核对解码、尺寸、哈希和文件登记；`catalog` 从已核读 manifest 生成逐作品 Markdown 目录；`sheets` 只在 `.local/` 生成联系表。它不操作浏览器、不登录站点、不绕过证书验证，不写游戏资源。详见[参考库维护](../wiki/art-references/README.md#出处索引)。

E-Hentai 补充库使用 `wiki_ehentai_reference.py`（Python 3.10+、Pillow）：`fetch` 读取 `wiki/art-references/ehentai/sources.json`，按登记上限与实际公开图页链接增量保存图片及 manifest；网页缓存和总览放 `.local/reference/ehentai/`。`check` 离线只读核对登记、解码、尺寸和 SHA-256；`catalog` 生成该子库作品目录；`sheets` 生成临时总览。只保存站点图页提供的版本，不承诺作者母版；不使用登录凭据、浏览器自动化、付费下载或访问限制绕过。选页须在图集首页已经观察到，新页默认未核读。详见[补充库维护](../wiki/art-references/ehentai/README.md#来源登记)。

亚种与先天形态入口，详见[实现说明](../docs/SUBSPECIES_IMPLEMENTATION.md)：

| 脚本 | 用途与写入边界 |
| --- | --- |
| `export_subspecies.py` | 读取已选螺壳／多毛源与不足形态配方，生成八套模型、快照、口部数据、帽船和螺壳道具；`--stage` 只写源与快照，默认同步自身运行资源；`--textures-only` 只补表情导出，`--check-textures` 只读核对128图覆盖、解码、尺寸和来源指纹；需 Python/Pillow |
| `export_subspecies_wounds.cjs` | 读取八套实际运行几何与既有受伤表面算法，仅重建对应受伤表面和指纹；需 Node.js |
| `preview_subspecies.py` | 从当前运行三角面和贴图生成离线交互页、六角度拼图与40幅生态表情图；需 Python/Pillow 和现有 Node.js，不启动游戏 |

日常生态入口：`export_ecology_expressions.py` 选择已确认表情，`build_ecology_audio.py` 制作原创短哼唱，二者支持只读 `--check`。说明见[日常生态](../docs/ECOLOGY_DAILY_LIFE.md)。

头饰与头发位置适配由 `export_placement_fit.cjs` 从[制作源](../standards/yukkuri-library/presentation/placement-fit/README.md)和当前运行模型导出 `yukkuri_presentation/placement_fit.json` 与来源清单；`--check` 只核对，`--only id,... --out 文件` 写试验结果；`--update-only id,...` 只更新这些模型并保留其他适配。基础模型不改写。`placement_fit.py` 是 Python 导出器共用的拼接规则：`export_motion_corrections.py`（鼓腮）与 `export_hat_interactions.py`（脱帽、帽托和幼油尺寸）都读取拼接后的网格。`verify_placement_fit.cjs` 检查头饰透穿、头发进入身体、五官遮挡、蝴蝶结贴合与地面，并输出前后对比、脱帽、动作和未改形态预览；`--checks-only` 不渲染。只做离线计算与渲染，不操作界面或游戏。见[游戏适配](../art/source/placement-fit/README.md)。

游戏参考分类使用 `wiki_reference_topics.py build/check`：以 `wiki/reference-library/game-annotations.json` 的逐图实体、情景、情绪、制作目标、画格和用途为主，合并图片 manifest 与小说阅读卡。生成四个交叉索引、兼容旧主题的页面、精选清单与离线筛选器；页面模板为 `wiki_reference_gallery.html`。默认显示已写明用途的游戏图片，精选优先，可组合筛选实体／情景／情绪／目标与画风。检查实体范围、所有存图的用途或队列状态、实际文件哈希和生成一致性。`quality-selection.json` 只提供辅助质量标签。两个抓取器均跳过 `collect: false` 来源；E-Hentai 的 `fetch --source` 若显式请求停采来源会在联网前拒绝。见[游戏参考库](../wiki/reference-library/README.md)。
