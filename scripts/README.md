# 项目脚本

`python scripts/check_architecture.py --check` 是 Python 3.10+ 标准库的只读架构建议工具；默认不带 `--check` 也只读。它只扫描本仓库 `src/main/java/**/*.java`，不扫描 `.worktrees`、`.local`、资源或文档，不调用 Git、Java、Gradle、游戏、网络，也不修改文件。不接入 CI、pre-commit 或合并门槛，不替代远端编译或用户试玩。

`python scripts/update_museum_runtime.py --run <run-id>` 为已有试玩配置安装同一提交的成功 CI 运行包，逐项核对资源哈希后切换清单并保留上一份运行包。不编译、不启动游戏；在实际游玩的机器执行，步骤见[开发指南](../docs/DEVELOPMENT.md#试玩与检查)。

`python scripts/museum_logs.py` 按时间导出试玩日志，默认最新场次末尾 15 分钟；`--list` 列场次，`--session <启动时间>` 选场次，`--start "YYYY-MM-DD HH:MM:SS" --end "YYYY-MM-DD HH:MM:SS"` 选本地时间范围，`--all` 导整场。Python 3.10+ 标准库；只读 `.local/experimental-map/play-museum`，只写 `.local/reports/museum-logs/` 的目录与 ZIP，不改原日志，不启动游戏、不编译、不访问网络。读取运行中的日志时按开始导出的文件大小截取；标准错误整场保留。`--root` 可让隔离工作树的脚本读取主检出的本机日志。Windows 双击根目录 `导出试玩日志.cmd`，经 `export_museum_logs.ps1` 查找现有 Python 并提示输入分钟数。运行版本只认启动时冻结的 `.session.json`，旧场次缺少快照则标未知。见[试玩日志](../docs/DEVELOPMENT.md#试玩日志与事后排查)。

检查四条约定：client 不读取实体服务端组件（35 个 YukkuriEntity getter，含 predator，另含 `component(key)` 与 HatBoatEntity.cargo）；原始 `getPersistentData` 入口必须有精确许可；`isHouse/isPredator` 只允许画像构建与已审核例外；新增内联全限定类型名必须处理。调用、方法引用、跨空白写法及 `e.<T>component(key)` 都进入检查。package/import、注释、普通字符串、字符和 text block 不按代码匹配；`this.G` 是字段访问，不是全限定类型名。同步视图、画像、同步记录和快照读取不因包含组件词根而禁止。

`architecture_exceptions.json` 记录固定审核对象、精确文件/方法签名、整个方法的归一化 token SHA-256、符号出现次数，以及每个入口按源码顺序列出的 receiver、键读写方向和独立理由。现有许可包含逐项审核的持久入口、画像的两个原始枚举读取和八个同名类冲突所需的全名委托；没有目录/类级豁免，也没有自动接纳当前树或更新基线命令。任一方法代码 token、字面量内容、计数或签名变化、许可失效都会报告复核；注释和字面量外排版变化不改变 hash。键与方向是人工审核记录，由完整方法 token 以及两个方法外键常量声明（GroupCards.BAR、Cannibalism.HUNT_COOLDOWN）的精确 token hash 共同锁定，不是假装推断出全部动态 NBT 路径；修改许可前必须重新核对理由，不能直接刷新 hash 消除诊断。

这是有限词法检查，不是 Java 编译器：receiver 类型只解析确定声明形状、完整 initializer 为单纯变量的 var 别名和 instanceof 绑定；this.field 与同名参数分开解析，复杂工厂返回/限定链报未知；不完整解析继承、泛型工厂、跨方法数据流、动态 compound 子键或别名逃逸。Custody.store 的入参尤其需人工复核。未知 receiver 或无法确定所属方法的候选、词法/括号错误、Java Unicode escape、包路径不一致、无源码和读取失败都显式报告覆盖缺口。词法按 Java 最长匹配保留多字符运算符，非法字符报覆盖错误；泛型方法的连续闭角括号另按类型参数处理。全名候选在扫描前统一收集全部源码 package/import（含 wildcard import）的包根，从 java/javax/net/org/com/io 以及这些包根开始，按类型段大写惯例定位；已知根的全小写长链报告待人工核对。未出现过的非常规外部包根、嵌套/泛型复杂类型不能视作完整覆盖证明。公开组件 getter 的已审核返回类型以及 ComponentKey 登记类型也做交叉核对；复杂限定返回类型仍需人工判断，不能把词法成功当作全部 Java 语义成立。

输出按路径/行/列稳定排序；退出码 0 表示已扫描范围没有发现，1 表示约定违规或许可漂移，2 表示覆盖/读取/配置错误（优先于 1）。`--json` 将同样诊断输出到 stdout；需要留存时由调用者重定向至 `.local/reports/`。`--root <目录>` 可指定另一仓库根，仍使用本工具旁已审核许可、仍只扫描其 src/main/java，不接受目标树自带的未审核例外。不会输出或修改报告文件，也没有修复模式。


所有新预览统一写到 `.local/previews/<主题>/`，总入口为 `.local/previews/index.html`。同主题只保留当前版本；截图和检查报告分别写到 `.local/screenshots/`、`.local/reports/`，不入库。

`python scripts/build_preview_hub.py --render` 生成集中入口、对应动作 GIF、当前模型缩略图与动作总览图；需要 Pillow、numpy 和 Node.js。`python scripts/serve_review.py --port 8766` 只启动本机 HTTP 服务，再手动打开它输出的地址。页面模板在 `preview/hub/`，模型查看器源码在 `preview/review/`。生成流程不启动浏览器、游戏或 Java 编译。

`asset_export_checks.py` 是各导出器 `--check` 共用的只读比较库（生活、家庭、家油照料与周边）。普通 JSON 仅容忍 LF／CRLF 差异，仍核对字段顺序、空格与结尾换行；PNG 检查按字节比较，生成器仅在格式、模式、尺寸和像素完全一致时复用现有编码。三个道具包的网格生成器仅复用符号零（`-0.0`／`0.0`）等值的既有表示；其 Blockbench 模型按 JSON 内容比较，仅容忍顶点坐标不超过 `1e-15` 的计算尾差和内嵌 PNG 的等价编码。草麦食盆先核对预览图集裁切像素，再复用所选原始调色板字节。生活口部数据从圆润选用网格重新推导并对照选用记录；遗体物品模型从 `art/props/house-care/yukkuri_remains.item.json` 复制当前渲染配置。

跨平台模型指纹通过 `runtime_model_fingerprint.py` 比较原始、LF 和 CRLF 三种字节表示；Java 客户端使用相同的 `ModelFingerprint` 规则。只容忍换行转换，不忽略模型数值、空格、字段顺序或结尾换行的变化。保留已有导出指纹，避免仅因跨平台检出而更换存档中的表面标识。

`verify_puff_motion.cjs` 检查第十版整身鼓腮：每种形态鼓腮为只驱动根骨骼的2.8秒循环且首尾回到静止、不再带脸颊形变与包络、鼓起幅度在预算内（豆形不拉高）、俯仰≤4°侧倾≤2°无转向、最低点不离地、表情时间线以 `cheek_puff` 收尾并让位于哭脸、完整体操段脸时间线一致。只读运行资源，离线运行。

整身鼓腮（第十版）、侧弯、跳跃与体操由 `export_motion_corrections.py` 从动作配方和19种当前骨架编译第2版覆盖资源，并生成各形态的 `cheek_puff.png`；`--check` 只核对，`--only id,... --trial 目录` 把试验结果写到指定目录而不碰运行资源。替换基础模型后重新执行。`verify_motion_corrections.cjs` 检查地面接触、挂接一致性、跳跃高度及表情时段（鼓腮按2.8秒），并离线生成前后对比与动图，不操作浏览器。见[当前动作适配](../art/source/motion-corrections/README.md)。

素材集中检查使用 `build_review_catalog.py` 从当前运行资源生成模型／声音索引，`start_review.ps1` 启动仅监听本机的只读服务；`-NoOpen` 只启动服务并输出地址，不打开浏览器。`stop_review.ps1` 按进程记录停止该服务。`verify_review.cjs` 通过命令行验证动作数据并生成模型缩略图，不操作浏览器或游戏。入口见[素材检查室](../preview/review/README.md)。

灵梦稀毛／歪脸的定向导出为 `export_reimu_deficient_rounded.py`，复用现有不足形态圆润生成逻辑；`preview_reimu_deficient.py` 生成当前四视图和动作检查，页面检查用 `node scripts/verify_marisa_deficient_browser.cjs reimu`。来源见[制作包](../standards/yukkuri-library/subspecies/deficient/rounded-reimu/README.md)。

人工试玩当前使用[油库里试玩场](../docs/EXPERIMENTAL_MAP.md)：`build_museum_full.py --install` 现场生成全新世界（数据包加种子 `level.dat` 的元数据）到 `.local/experimental-map/museum`，整体替换，不做原地修补；各开放场地的地形在 `museum_fields.py`，场景在 `museum_scenarios.py`，生成真实个体的词汇在 `museum_dsl.py`，按钮与观察台在 `museum_sites.py`，动作目录在 `museum_actions.py`；需要 Python 3.11+、nbtlib。`--catalog` 另外生成完整动作适配清单。根目录 `启动动作博物馆.cmd`（唯一的试玩启动入口，文件名沿用）调用 `launch_museum.ps1`：世界缺失或生成器改动时先生成，再用已有 CI 运行产物启动，不编译；运行模组比数据包旧时，进图会大字提示“运行模组过旧”。`check_museum_pack.py` 离线核对数据包里的每条命令与函数引用、场地布局、按钮与场景的对应，`render_museum_map.py` 画俯视布局图。其他输出目录必须是 `.local/experimental-map` 下不存在的新目录。

圆润体型、四轴性格与灵胖发育的新素材依次使用 `export_rounded_runtime.py`、`rounded_wound_surface.cjs`、`export_rounded_attachments.py`、`export_badges.py --mounts-only`、`export_family_assets.py` 和 `build_rounded_preview.py`；束具仍由标准库 `drafts/operation-tools/build.py` 生成。来源见[圆润运行包](../art/source/rounded-runtime/README.md)。

接触器具的制作与导出入口为 `standards/yukkuri-library/drafts/operation-tools/build.py`。它从参数配方及所选角色表面制作器具、固定带、铁箱和培养瓶，同步可编辑模型、接入快照、运行资源、配方与指纹；`--check` 只核对导出。当前版本为待确认的游戏适配版。

| 脚本 | 输入与用途 | 执行边界 |
| --- | --- | --- |
| `export_predator_assets.py` | 从捕食种配方导出的模型同步两种实体快照、256 图集、19+4 动作与来源登记 | 只导蕾米莉亚、弗兰，不替换现有七种 |
| `preview_predators.py` | 生成两种运行数据交互预览和八视图拼图 | 使用实际导出网格离线渲染，不启动游戏 |
| `settlement_site_preview.py` | 聚落场地规划器（`settlement/site/`）的 python 镜像：按档位出俯视预览，并对每个家巢跑 `NaturalShelter.survey` 与门口可达检查（`--check N`）；Java 规划器改动时同步修改 | 只读 Java 常量的镜像，不启动游戏，预览写 `.local/previews/settlement/` |
| `export_predator_language.py` | 增量合并捕食种专属语言、生成蛋模型和低权重森林生成规则 | 保留其他任务语言键 |
| `export_wound_surfaces.cjs` | 读取七种当前运行角色与选定 R3.1 源，导出外皮细分、五官挂接、骨架、动作及材质；记录来源指纹 | 写 `yukkuri_wounds/` 与伤口适配包，不修改标准角色源，不启动游戏 |
| `verify_wound_runtime.py` | 核对伤口源与输出指纹、有限数值及共点权重一致性 | 只读，不启动游戏 |
| `export_human_world.py` | 从制作源同步商店、三类工厂、生成规则、五套服装、原创机台与商品、生成蛋及中英语言；`--check` 核对指纹 | 默认写本模块资源与登记；检查只读，不启动游戏 |
| `catalogue_hakkei_dialogue.py` | 只读原版八景 U 角色 CSV 与 ModLab 翻译／新增 TSV，生成分离的对白检索与来源指纹 | 写 `art/dialogue/native-u/`，不写来源游戏和运行资源 |
| `build_aquestalk_dialogue.py` | 读取选句与配方，调用本机 AquesTalkPlayer，输出 288 条 WAV／OGG、发音符号及试听页；204 原版与 84 原创分别标注 | Windows 本机制作；`--work-directory` 隔离工具副本，匹配文本、参数和指纹时复用录音；不打包程序、字典或 DLL |
| `export_dialogue.py` | 核对原句／原创来源与录音文本，导出台词、语言资源、OGG、时长及声音索引，保留其他任务条目 | 默认写运行资源；`--stage` 先导出到指定待核对目录；`--check` 只读检查，不启动游戏 |
| `export_runtime_models.py` | 读取五个标准种族及两种家油导出；制作快照、适配骨架、运行 JSON、贴图和静态预览数据，并更新来源登记 | 只写静态美术数据；不启动游戏、页面或测试 |
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

Wiki 漫画参考使用 `wiki_reference_library.py`（Python 3.10+、Pillow）：`fetch` 读取 `wiki/art-references/sources.json`，从登记的公开作品正文保存原图与来源清单；缓存、失败记录放 `.local/reference/wiki-manga/`。`check` 离线只读核对解码、尺寸、哈希和文件登记；`catalog` 从已核读 manifest 生成逐作品 Markdown 目录；`sheets` 只在 `.local/` 生成联系表。它不操作浏览器、不登录站点、不绕过证书验证，不写游戏资源。详见[参考库维护](../wiki/art-references/README.md#维护命令)。

E-Hentai 补充库使用 `wiki_ehentai_reference.py`（Python 3.10+、Pillow）：`fetch` 读取 `wiki/art-references/ehentai/sources.json`，按登记上限与实际公开图页链接增量保存图片及 manifest；网页缓存和总览放 `.local/reference/ehentai/`。`check` 离线只读核对登记、解码、尺寸和 SHA-256；`catalog` 生成该子库作品目录；`sheets` 生成临时总览。只保存站点图页提供的版本，不承诺作者母版；不使用登录凭据、浏览器自动化、付费下载或访问限制绕过。选页须在图集首页已经观察到，新页默认未核读。详见[补充库维护](../wiki/art-references/ehentai/README.md#登记与维护)。

亚种与先天形态入口，详见[实现说明](../docs/SUBSPECIES_IMPLEMENTATION.md)：

| 脚本 | 用途与写入边界 |
| --- | --- |
| `export_subspecies.py` | 读取已选螺壳／多毛源与不足形态配方，生成八套模型、快照、口部数据、帽船和螺壳道具；`--stage` 只写源与快照，默认同步自身运行资源；`--textures-only` 只补表情导出，`--check-textures` 只读核对128图覆盖、解码、尺寸和来源指纹；需 Python/Pillow |
| `export_subspecies_wounds.cjs` | 读取八套实际运行几何与既有受伤表面算法，仅重建对应受伤表面和指纹；需 Node.js |
| `preview_subspecies.py` | 从本轮运行三角面和贴图生成离线交互页、六角度拼图与40幅生态表情图；需 Python/Pillow 和现有 Node.js，不启动游戏 |

日常生态入口：`export_ecology_expressions.py` 选择已确认表情，`build_ecology_audio.py` 制作原创短哼唱，二者支持只读 `--check`。说明见[日常生态记录](../docs/ECOLOGY_DAILY_LIFE.md)。

头饰与头发位置适配由 `export_placement_fit.cjs` 从[制作源](../standards/yukkuri-library/presentation/placement-fit/README.md)和当前运行模型导出 `yukkuri_presentation/placement_fit.json` 与来源清单；`--check` 只核对，`--only id,... --out 文件` 写试验结果。基础模型不改写。`placement_fit.py` 是 Python 导出器共用的拼接规则：`export_motion_corrections.py`（鼓腮）与 `export_hat_interactions.py`（脱帽、帽托和幼油尺寸）都读取拼接后的网格。`verify_placement_fit.cjs` 检查头饰透穿、头发进入身体、五官遮挡、蝴蝶结贴合与地面，并输出前后对比、脱帽、动作和未改形态预览；`--checks-only` 不渲染。只做离线计算与渲染，不操作界面或游戏。见[游戏适配记录](../art/source/placement-fit/README.md)。

游戏参考分类使用 `wiki_reference_topics.py build/check`：以 `wiki/reference-library/game-annotations.json` 的逐图实体、情景、情绪、制作目标、画格和用途为主，合并图片 manifest 与小说阅读卡。生成四个交叉索引、兼容旧主题的页面、精选清单与离线筛选器；页面模板为 `wiki_reference_gallery.html`。默认显示已写明用途的游戏图片，精选优先，可组合筛选实体／情景／情绪／目标与画风。检查实体范围、所有存图的用途或队列状态、实际文件哈希和生成一致性。`quality-selection.json` 只提供辅助质量标签。两个抓取器均跳过 `collect: false` 来源；E-Hentai 的 `fetch --source` 若显式请求停采来源会在联网前拒绝。见[游戏参考库](../wiki/reference-library/README.md)。
