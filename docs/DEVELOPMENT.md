# 开发与试玩

本机不编译；远端 CI 构建，用户在唯一试玩场确认玩法。代码架构见 [ARCHITECTURE.md](ARCHITECTURE.md)，导出与检查脚本见 [scripts/](../scripts/README.md)。

## 环境与构建

版本以 `gradle.properties` 为准：Minecraft Java 1.21.1、64 位 JDK 21、NeoForge 21.1.250、ModDevGradle 2.0.146、Gradle Wrapper 9.2.1。不混用 Forge／Fabric 文件；渲染器为项目自有实现，无需 GeckoLib。

`.github/workflows/build.yml` 在 `main` 推送、PR 和手动触发时于 Ubuntu／Java 21 执行 `./gradlew build`，只检出构建所需目录。产物：

| 产物 | 用途 |
| --- | --- |
| `mcyukkuri-<完整sha>` | 开发 Jar，放入匹配 NeoForge 实例的 `mods/` |
| `mcyukkuri-runtime-<完整sha>` | 已编译代码及资源哈希清单，用于本机拼装运行包 |

PR 产物对应合并提交 SHA。失败用 `gh run view <id> --log-failed` 查看；推送后在后台核对。允许编译的远端环境可运行 `gradlew build`、`runClient`、`runServer`；服务器首次运行需自行阅读并接受 Minecraft EULA。

三份 SERVER 配置保存在世界 `serverconfig/`：`mcyukkuri-server.toml`（生活、生态与聚落）、`mcyukkuri-family.toml`（家庭与繁殖）、`mcyukkuri-human-world.toml`（人类、商贸与工厂）。分组是路径的一部分，例如 `seasons.biomeSeasons`、`badge_social.enabled`；具体键与默认值以专题和配置类为准。

上游资料：[NeoForge 1.21.1 文档](https://docs.neoforged.net/docs/1.21.1/gettingstarted/)、[ModDevGradle 模板](https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle)。

## CI 运行包更新

在实际游玩的机器关闭试玩场，将代码更新到目标 `main` 提交后执行：

```text
<python> scripts/update_museum_runtime.py --run <该提交成功的 Build run ID>
```

需要 `git`、已登录的 `gh` 和现有 `.local/experimental-map/museum-runtime.json`。脚本要求 CI 与当前提交一致，从同一提交恢复资源并核对哈希，保留原运行包后切换配置；不编译、不启动游戏。更新后仍用原 `.cmd` 启动。每次切换将背景音乐设为静音，保留其他音量设置；旧 options 随运行包回执备份，新建试玩场也默认音乐静音。启动器在生成世界前对照运行包提交与当前代码、资源、场景和构建配置；不一致时停止并提示更新 CI 包，避免新版场景搭配旧版模组。仅文档或未导出的美术源变化不阻止启动。

## 试玩场

根目录 `启动动作博物馆.cmd` 用已有 CI 运行包启动唯一试玩世界。场景在草地、洞穴、池塘、屋子、街道和群系中由普通 AI 运行；“动作片段”区只播放动作，不代表行为链通过。

| 参数 | 行为 |
| --- | --- |
| 无参数 | 世界缺失或生成器改动时生成全新世界，再启动 |
| `-Fresh` | 强制生成全新世界，丢弃旧存档 |
| `-Resume` | 保留已有世界，即使生成器已改 |
| `-Menu` | 进入主菜单而非直接进世界 |

`build_museum_full.py --install` 将全新世界写入 `.local/experimental-map/museum`，不原地修补；`map-manifest.json` 记录输入哈希。`museum-seed/level.dat` 只提供元数据，不复制旧区块、实体或玩家。运行配置指定 CI 产物、Java 与可选 Python；数据包入场检查必需命令和资源，模组过旧时提示缺失类别并停止布置。

## 导航、操作与读数

大厅按主题导航并提供导览书；每区观察台负责场景、清场、时间、天气、倍速和推进。进图为创造模式并开启观察标签；普通场景从北侧观察，个体自由转向。场景按钮先清场再生成，聊天中的回程链接、区外南侧按钮及 `/function mcylab:home` 可返回。

观察台提供跟随主角、正常速度、标签、旁观／创造等操作；无“读状态”按钮。手工读数：

- `/yukkuri status`：实体状态。
- `/function mcylab:read/<场景id>_<序号>`：场景读数，序号按 `museum_scenarios.py` 的 `reads` 从 0 起。
- `/function mcylab:read/<场地id>/lead`：主角读数。

`yukkuri readout` 仅在调用时记录只读报告，附观察者、tick、位置与命令；超限明确标记截断，不自动轮询或推进玩法。无需先读数也能查默认业务日志。旧命令方块须重建世界才更新，`-Resume` 保留旧布局，不在运行中修改存档。

场景、初态和读数只在 `museum_scenarios.py` 维护；地形在 `museum_fields.py`，实体起点在 `museum_dsl.py`，按钮在 `museum_sites.py`，动作在 `museum_actions.py`。大厅、观察台和环境控制为项目原创；规则见[系统专题](README.md#游戏系统)，故障与完成标准见[待办](TODO.md)。

## 清场与场景构造边界

清场取消本区延迟初始化、重试、返岸呼叫及动作片段，移除范围内本模组实体和掉落物，也清除带 `fld_<区>` 标记而已离开范围的已加载实体；同时复位关联的家族、认知、关系、住所、活动格、商店和工厂账本。随后恢复场地地形与被改群系，再清理替换方块产生的掉落。

清场无前置拒绝条件，未加载范围跳过；地形恢复需加载区块，应在观察台附近执行。`field/<区>/terrain` 不重建矩形外及清场边缘四格。群系聚落一次清一个，避免人口上限。

收纳篮／冰柜个体及令牌、已确认死亡的 NPC 主人、玩家记录（弃养、徽章特权、货架、每日交易）、倍速和场地外方块不复位。布局、`site vacant` 和概率结果可随机，固定 UUID 场景的亲缘与胎数种子一致。直接 `/function mcylab:scn/<id>` 只生成不清场；不带场地标记的 `yukkuri clear <从> <到>` 仅移除实体与聚落登记，账本保留 `REMOVED`。

行为链场景只提供真实起点、亲缘、环境和有限物资，让普通 AI 完成被测环节；不以直接放船、登船或写成功状态代替它。后半段场景可预置已有船和乘员，但须经过实际碰撞检查，失败不得遗留半套准备状态。参数不同的重复按钮合并，真实行为分支保留。

非被测的准备命令若依赖落地，须在召唤两秒后执行；同 tick 位置判断可能失败。家油屋模拟人类活动和睡一晚只在调用期间开启 `doMobSpawning`。倍速使用 `yukkuri speed rate <20–400>` / `sprint <ticks>`；原版 `/tick` 需等级 3，函数和命令方块仅等级 2。

## 试玩日志与事后排查

启动器和游戏自动记日志，用户只需提供场景、大概时间和现象；聊天中可留现象标记。按以下顺序只读排查：

1. 读 `.local/experimental-map/play-museum/process.json` 与同场 `.session.json`，确定启动时间、PID、世界和日志位置。进程核对 PID 与 `startedTicks`；权限不足不能判为已退出。
2. 原生日志优先看 `.local/experimental-map/museum/logs/latest.log` 和 `debug.log`；其他启动方式按 `gameRoot` 或实际进程参数定位。检查文件内末条事件时间，按场景和时间窗口读事件、读数及完整异常堆栈。
3. 启动器 `<启动时间>.log` / `.err.log` 可能早于原生日志停止，须比较末条时间；旧场次查原生日志日期归档（含 `.log.gz`），对齐世界与启动时间。
4. 版本取 `.session.json` 的 `runtimeConfig.commit/run`。无快照时，用实际进程 `mod` 路径和包内 `mcyukkuri-runtime.json` 互证；不得用当前配置或 HEAD 代替，无证据写“版本待核实”。
5. 保留原日志，需要副本放 `.local/reports/<主题>/`；问题及版本只登记在待办原条目。按用户时间前后数分钟读取，不持续轮询。

时间以原文及记录机器时区为准；跨午夜结合日期，无时间戳的 stderr 不能精确筛时。日志不覆盖每刻所有实体和拒绝原因，缺日志不证明行为未发生；读数不足应补阻塞处。`/yukkuri status trace true` 开启细密通知，结束用 `false` 关闭；其他开关与覆盖见[玩法日志](GAMEPLAY_LOGGING.md)。

## 本机工具

| 工具 | 用途 |
| --- | --- |
| Python | 优先配置里的 `python`，再试 `py`、`python`；世界生成需 Python 3.11+／nbtlib，美术按需使用 Pillow、numpy |
| `scripts/check_museum_pack.py` | 只读核对命令、函数引用、场景／按钮、地形与文字容量 |
| `scripts/render_museum_map.py` | 从生成器数据输出 `.local/previews/museum/map.png` |
| `scripts/build_museum_full.py --pack-only --output <目录>` | 生成数据包；输出须为 `.local/experimental-map/` 下不存在的新目录 |
| `/function mcylab:selfcheck` | 检查已加载按钮；`MCYMUSEUM_BUILD_COMPLETE` 仅表示建图结束 |
| `.local/smp/java_syntax.py` | 可用时用 tree-sitter 解析 Java 语法，不是编译 |
| `.local/mc-sources/` | 可用时查 Minecraft／NeoForge API |
| `.local/smp/merge_lang.py` | 可用时按键三方合并语言 JSON |

本机工具不入库，换机器须核对。资源 `--check` 仅检查导出一致性，不替代试玩；`打开素材检查.cmd` / `关闭素材检查.cmd` 查看动作表情，方法见[预览说明](../preview/README.md)。

## 生成文件与资源

生成文件冲突先合并制作源再重建：动作源包括 `motions/variants/lore/recipe.json`、`author.py`、`motion_corrections.json`，再执行对应导出器；语言按键合并，台词按行 ID 合并后运行 `export_dialogue.py`。只重建受影响输出。

标准制作源、选用快照和运行资源分工见[素材流向](README.md#素材从制作到游戏)；标准更新不自动替换游戏快照。清理 `.local/setup/` 的 `runtime-*`、`ci-*` 前，核对运行配置、进程与待回退包，只删不再引用的产物。
