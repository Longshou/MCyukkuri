# 开发指南

环境版本、远端构建、运行包、唯一试玩场和本机维护工具。规则以 [AGENTS.md](../AGENTS.md) 为准：本机只修改代码或建模，不执行任何编译；`build` 由远端 CI 完成（只编译，不跑测试）；玩法效果由用户在试玩场亲自确认。

## 构建与运行

### 固定环境

| 项目 | 版本 |
| --- | --- |
| Minecraft Java | 1.21.1 |
| JDK | 21，64 位 |
| NeoForge | 21.1.250 |
| ModDevGradle | 2.0.146 |
| Gradle Wrapper | 9.2.1 |

依赖版本统一锁定，不混用 Forge / Fabric 文件；版本号集中在 `gradle.properties`。模组使用项目内的网格与骨架渲染器，不需要 GeckoLib。

### 远端构建（CI）

`.github/workflows/build.yml` 在 `main` 推送、PR 和手动触发时用 Java 21（Ubuntu）执行 `./gradlew build`。只稀疏检出 `src/`、`gradle/`、`scripts/`，不检出大型美术目录。每次运行上传两个产物：

- `mcyukkuri-<完整sha>`：开发 Jar，放进匹配版本 NeoForge 实例的 `mods/` 即可游玩。
- `mcyukkuri-runtime-<完整sha>`：已编译代码与资源哈希清单（`scripts/package_ci_runtime.py` 生成），本机据此拼出运行包。

PR 构建编译的是 PR **合并提交**，产物名用合并提交的 sha。编译失败用 `gh run view <id> --log-failed` 看错误。推送后不必前台等 CI，放到后台再看结果。

以下命令只供允许编译的环境（远端或其他机器）使用，本机不执行：

```powershell
.\gradlew.bat build       # 产物 build/libs/mcyukkuri-0.1.0-dev.jar
.\gradlew.bat runClient
.\gradlew.bat runServer   # 首次需自行阅读并接受 Minecraft EULA
```

### 启动接线与服务器配置

启动、模块资源注册与事件顺序统一见[代码架构说明](REFACTOR_HANDOFF.md#1-启动注册与事件)。本节只说明运行配置的位置与用途。

三份配置均注册为 SERVER 配置，保存到对应世界的 `serverconfig/`，以服务器值为准：

| 文件 | 定义位置 | 分工 |
| --- | --- | --- |
| `mcyukkuri-server.toml` | `life/YukkuriConfig` | 生理、生态、聚落等规则 |
| `mcyukkuri-family.toml` | `family/FamilyConfig` | 家族与繁殖时序；成长阶段与寿命使用固定时钟 |
| `mcyukkuri-human-world.toml` | `human/HumanWorld` | 人类遭遇、商贸、徽章期限、工厂运输及滞销处理 |

分组名称属于路径，例如 `community.migrateThreshold`、`seasons.biomeSeasons`、`home_claims.enabled`、`badge_social.enabled`。`debug.entitySaveViews` 默认 `false`，只控制实体四份调试视图的写入；试玩场脚本的已有用法不变。

### CI 运行包更新

实际游玩的机器先关闭试玩场、把代码更新到目标 `main` 提交，再运行：

```text
<python> scripts/update_museum_runtime.py --run <该提交成功的 Build run ID>
```

脚本要求 CI 与当前提交一致，下载已编译代码，从同一提交恢复并逐个核对资源哈希，保留原运行包后切换 `museum-runtime.json`。它需要 `git`、已登录的 `gh` 和已有 NeoForge 运行配置，不编译、不自动启动游戏；完成后仍由原 `.cmd` 启动。

## 试玩场

唯一试玩地图和唯一测试入口是根目录 `启动动作博物馆.cmd`。它用已有 CI 运行产物启动游戏，不在本机构建；场景在真实草地、土丘、池塘、屋子、街道和群系中按普通 AI 自由运行，只有“动作片段”区的九位演员只播放动作。大厅、观察台、按钮、清场、时间、天气和倍速是项目原创试玩设施，行为依据仍以系统专题、[设计取向与用户裁决](DESIGN_DECISIONS.md)和 `wiki/` 为准。

```text
启动动作博物馆.cmd            世界缺失或生成器改动后生成全新世界，再启动游戏
启动动作博物馆.cmd -Fresh     无论如何都生成全新世界（旧存档丢弃）
启动动作博物馆.cmd -Resume    保留已有世界，即使生成器已改
启动动作博物馆.cmd -Menu      进入 Minecraft 主菜单而不是直接进世界
```

世界由 `scripts/build_museum_full.py --install` 生成到 `.local/experimental-map/museum`，整体替换而不在旧存档上修补；`map-manifest.json` 记录生成器输入哈希。运行配置 `.local/experimental-map/museum-runtime.json` 指定 CI 运行产物、Java 和可选的 Python。进入地图时，数据包会检查必需命令、方块、物品和实体；运行模组过旧会提示缺失类别并停止布置地图。

### 导航、操作与读数

- 大厅只按主题导航并提供导览书；生成、清场、环境、倍速和推进只在各分区观察台操作。进图后为创造模式，出生在大厅，观察标签默认开启。
- 每个观察台可回大厅；每区南侧区外也有回观察台／回大厅按钮。生成场景会把玩家带到附近，聊天栏的 `[回观察台]`、`[回大厅]` 和 `/function mcylab:home` 都可返回。普通场景从北侧观察，个体随后由普通 AI 自由转向。
- 观察台提供回大厅、跟随主角、正常速度、观察标签、旁观／创造、无条件清场、倍速、推进、时间、天气和场景操作。场景按钮一律先清场再生成；家油屋的模拟人类活动、睡一晚和动作片段操作紧随场景按钮。
- 试玩不放“读状态”或场景自检实体按钮。需要瞬时状态时，手工执行 `/yukkuri status`、`/function mcylab:read/<场景id>_<序号>`，或主角入口 `/function mcylab:read/<场地id>/lead`；序号从 `museum_scenarios.py` 的 `reads` 中 0 起。`yukkuri readout` 只在实际调用时写入完整只读报告，不自动轮询或补造历史快照。
- 旧世界的命令方块不会随源码改写：用正常启动入口重建世界才会得到新布局，`-Resume` 保留旧布局；不在运行中修改世界。

读数报告同时记观察者、游戏 tick、位置和查询命令；超过大小限制会明确记为截断。服务端业务日志默认保留，提供场景和大概时间即可排查，不必先点读数按钮。

场景 ID、按钮标题、初态、读数、分区地形和完整场景清单以 `scripts/museum_scenarios.py`、`museum_fields.py`、`museum_sites.py` 和游戏内导览为准。新增场景在这些权威源码中维护，不另写尺寸表、完整命令表或第二份试玩状态。当前复测目标、故障证据和完成标准只在[待办](TODO.md#待试玩清单)登记；规则和专用操作见[系统专题](README.md#游戏系统)。

世界元数据 `level.dat` 取自 `.local/experimental-map/museum-seed/level.dat`；生成器只复制元数据，不复制旧世界的区块、实体或玩家。

### 清场与场景构造边界

“清空本场地”后再按场景按钮，实体、世界账本和地形回到同一初始状态。清场会取消本区尚未执行的延迟初始化、重试链、返岸呼叫和动作片段；移除方框内本模组实体与掉落物，并清除带本区 `fld_<区>` 标记、已离开方框的已加载实体和关联的家族、认知、好感、住所、活动格、商店、工厂等账本记录。随后恢复本区地形和被场景改动的群系，再清一次替换方块产生的掉落。

清场没有“真实过程进行中”或其他前置拒绝条件。未加载方框会被跳过而非让整条清场失败；地形恢复需要区块加载，因此应在观察台或场地附近执行。每个 `field/<区>/terrain` 负责本区场景地形和群系的复位，场地矩形外及清场方框边缘四格不重建。群系聚落一次清一个聚落，避免碰到人口上限。

以下状态故意不复位：收纳篮或冰柜中的个体及其托管令牌、已确认死亡的 NPC 主人、玩家自己的记录（弃养、徽章特权、店主货架和每日交易）、倍速和场地矩形外方块。聚落布局、`site vacant` 和概率行为本身每次可随机；固定 UUID 场景的亲缘和胎数种子保持一致。`/function mcylab:scn/<id>` 只生成不清场，适合任意平地试用，不能保证初始状态。

不带场地标记的 `yukkuri clear <从> <到>` 只移除实体和聚落登记，账本仍记录 `REMOVED`；丧偶再配对的 `REMOVED` 对照使用这一行为。

场景函数先召唤个体和道具，再以游戏命令写入不能直接写进 NBT 的真实伤病、亲缘、伴侣、住所、受孕、帽船等状态。认窝、受孕、放帽船等依赖个体已经落地的命令必须在生成两秒后执行；同一刻召唤的个体尚未站稳，抵达和位置判断会失败。家油屋的模拟人类活动和睡一晚仅在那次调用中打开 `doMobSpawning`，结束立即关回去。原版 `/tick` 需要等级 3，而函数和命令方块只有等级 2，倍速使用 `yukkuri speed rate <20–400>` 与 `yukkuri speed sprint <ticks>`。

## 日志排查

### 试玩日志与事后排查

照常从 `启动动作博物馆.cmd` 启动即可自动记录，无需另开录制进程，也无需让助手实时盯日志。发现问题时，记下**场景、电脑上的大概时间、观察到的现象**；需要瞬时状态时用本页的手工读数。可在游戏聊天留一句现象描述，作为时间定位。

**AI 查日志入口**（只读文件，不操作游戏界面）：

1. 先读 `.local/experimental-map/play-museum/process.json` 与同场 `.session.json`，定位启动时间、PID、世界和日志路径；进程是否仍存在要核对 PID **和** `startedTicks`，权限受限时看不到进程不能判定它已退出。
2. 当前游戏原生日志优先看 `.local/experimental-map/museum/logs/latest.log`，对照 `debug.log`。先看文件内最后的事件时间，再查最近场景标题、失败提示、事件与读数，保留异常堆栈续行。不同启动方式按启动记录的 `gameRoot` 或实际进程参数定位，不能假定旧配置的 `game` 字段仍在用。
3. `.local/experimental-map/play-museum/<启动时间>.log`／`.err.log` 是按启动场次保留的标准输出／错误，必须与原生日志末条时间比较。2026-10-21 实测 stdout 停在 00:38、原生日志继续到 00:54，因此不能把 stdout 末尾当作最近试玩。旧场次另查原生日志目录的日期归档（含 `.log.gz`），并核对启动时间与世界。
4. 版本优先取启动器冻结的 `.session.json` 中 `runtimeConfig.commit/run`。旧场次没有快照时，可用仍在运行的进程实际 `mod` 路径与该包 `mcyukkuri-runtime.json` 回执互证；不可用当前可变配置或代码 HEAD 代替，无证据就标“版本待核实”。
5. 按用户所说时间前后多取几分钟；需要留存时复制到 `.local/reports/<问题主题>/`，并按[待办的实时日志证据要求](TODO.md#实时日志发现的问题)登记。原日志不修改、不清空，不用后台轮询代替一次有范围的读取。

时间以日志原文和记录机器时区为准。原生日志包含日期（本机形如 `[2110月2026 00:53:46.627]`），启动器 stdout 通常只有时分秒；跨午夜须结合场次日期，标准错误无时间戳不能假装已精确筛选。

日志能核对已打点事件、异常和手工读数，**不保证覆盖每一刻全部实体状态或每次决策原因**。`/yukkuri status trace true` 可临时开启已有状态变更通知，结束用 `false` 关闭；它也不是完整连续录制。行为链读数不足时，应针对卡住环节补读数，不能把“没有日志”当作“没有发生”。

## 维护工具

### 静态检查与制作工具

以下工具只检查或生成数据包与布局，不能证明玩法通过：

- `python scripts/check_museum_pack.py`：核对命令树、函数引用、场景与按钮覆盖、观察台布局、地形与洞穴条件、文字容量及已删除的物品／命令和 NoAI／无敌限制。
- `python scripts/render_museum_map.py`：从生成器数据画俯视布局图，默认写到 `.local/previews/museum/map.png`。
- `python scripts/build_museum_full.py --pack-only --output .local/experimental-map/<新目录>`：只生成数据包，不编译、不启动游戏。
- `/function mcylab:selfcheck`：核对已加载区块中的按钮数。`MCYMUSEUM_BUILD_COMPLETE` 仅表示建图结束；动态接触、连续导航、重启恢复、多人干扰和帧耗时仍需在试玩场确认。

仓库没有 GameTest 或其他自动化测试；`scripts/export_*.py --check` 仅按需检查素材导出一致性，不是合并门槛。根目录 `打开素材检查.cmd`／`关闭素材检查.cmd` 用于查看动作和表情，不能替代游戏内确认。

### 试玩场制作源码

`build_museum_full.py` 装配函数、数据包和世界；`museum_fields.py` 定义分区与地形，`museum_scenarios.py` 定义场景，`museum_dsl.py` 写入真实个体起始状态，`museum_sites.py` 生成按钮和观察台，`museum_actions.py` 管理动作目录。动作覆盖见[动作目录](MOTION_MUSEUM_ACTIONS.md)。

### 本机工具

这些工具在 `.local/`，不入库，只存在于开发机，换机器后可能没有。

| 工具 | 位置与用法 |
| --- | --- |
| Python | 启动器优先使用 `museum-runtime.json` 的 `python`，其次尝试 `py`、`python`。选用真实 Python 3；世界生成需要 `nbtlib`，建模脚本按需依赖 numpy、Pillow。 |
| Java 语法检查 | `<python> .local/smp/java_syntax.py <文件...>`：tree-sitter 只做语法解析，不是编译。 |
| 原版与 NeoForge 源码 | `.local/mc-sources/`，用于核对 1.21.1 API 签名。 |
| 语言文件合并 | `.local/smp/merge_lang.py`：合并冲突时按键三方合并 lang JSON。 |

### 合并冲突与生成文件

- 生成文件（动作覆盖、语言、对白导出）冲突时不要手工拼接：先取上游版本，合并制作源，再重新导出。
- 动作：合并 `standards/yukkuri-library/motions/variants/lore/recipe.json`、`author.py`、`motion_corrections.json` 等制作源后，完整运行 `scripts/export_motion_corrections.py`，再在 `lore/` 目录运行 `author.py` 的相关 `--export-*`。
- 语言 JSON 按键合并（可用 `.local/smp/merge_lang.py`）；对白源按行 ID 合并后运行 `scripts/export_dialogue.py`。

### 本机磁盘

`.local/setup/` 的旧 `runtime-*`、`ci-*` 可能占用较多磁盘。清理前核对 `museum-runtime.json`、运行进程和待回退包，只删除不再引用的旧产物；本机临时脚本不作为跨机器维护入口。

### 制作源与运行资源

目录职责见[文档索引](README.md#目录)。标准模型在 `standards/yukkuri-library/` 制作；游戏使用 `art/source/` 下选定的快照，运行数据在 `src/main/resources/assets/mcyukkuri/yukkuri/`，对应关系登记在 [art/runtime-sources.json](../art/runtime-sources.json)。

标准造型确认、素材导出和游戏接入是不同状态：采用新版标准模型时，单独检查资源标识、骨架、贴图与动画适配，不因标准库更新自动覆盖已接入资源。改模型改制作源再导出，在本机出一张当前预览贴在回复里；预览、截图与检查结果不入库。

### 上游文档

- [NeoForge 1.21.1 开发文档](https://docs.neoforged.net/docs/1.21.1/gettingstarted/)
- [NeoForge ModDevGradle 模板](https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle)

### 技术改动的采用条件

- **运行资源瘦身**：先测同一提交的完整 Jar 体积和 Java 加载／重载耗时，再决定是否改变 JSON 或归档压缩；不能用单文件 gzip 比例替代完整产物收益。保持模型数字字面量、几何指纹、资源包覆盖与外观契约；收益不足则保留现格式。Java 测量放在允许运行的远端或试玩环境，本机不编译。
- **实体同步改网络包**：已有 `ReadoutPayload` / `ReadoutNetworking` 服务于定向报告；实体展示仍沿用 `SynchedEntityData` / `CompoundTag`。只有实际性能问题或同步内容扩展需要时再迁移。
- **可选兼容候选**：Jade 状态显示、农夫乐事食物、季节模组替换 `Seasons.at`；已有观察笔记覆盖基本用途，不优先新增 Patchouli 图鉴。正式排期只登记在待办。
