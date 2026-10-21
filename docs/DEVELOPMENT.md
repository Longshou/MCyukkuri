# 开发指南

环境版本、构建与运行方式、CI 产物和本机工具。规则以 [AGENTS.md](../AGENTS.md) 为准：本机只修改代码或建模，不执行任何编译；`build` 由远端 CI 完成（只编译，不跑测试）；玩法效果由用户在[油库里试玩场](EXPERIMENTAL_MAP.md)亲自试玩。

## 固定环境

| 项目 | 版本 |
| --- | --- |
| Minecraft Java | 1.21.1 |
| JDK | 21，64 位 |
| NeoForge | 21.1.250 |
| ModDevGradle | 2.0.146 |
| Gradle Wrapper | 9.2.1 |

依赖版本统一锁定，不混用 Forge / Fabric 文件；版本号集中在 `gradle.properties`。模组使用项目内的网格与骨架渲染器，不需要 GeckoLib。

## 远端构建（CI）

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

## 启动接线与服务器配置

启动、模块资源注册与事件顺序统一见[代码架构说明](REFACTOR_HANDOFF.md#1-启动注册与事件)。本节只说明运行配置的位置与用途。

三份配置均注册为 SERVER 配置，保存到对应世界的 `serverconfig/`，以服务器值为准：

| 文件 | 定义位置 | 分工 |
| --- | --- | --- |
| `mcyukkuri-server.toml` | `life/YukkuriConfig` | 生理、生态、聚落等规则 |
| `mcyukkuri-family.toml` | `family/FamilyConfig` | 家族与繁殖时序；成长阶段与寿命使用固定时钟 |
| `mcyukkuri-human-world.toml` | `human/HumanWorld` | 人类遭遇、商贸、徽章期限、工厂运输及滞销处理 |

分组名称属于路径，例如 `community.migrateThreshold`、`seasons.biomeSeasons`、`home_claims.enabled`、`badge_social.enabled`。`debug.entitySaveViews` 默认 `false`，只控制实体四份调试视图的写入；试玩场脚本的已有用法不变。

## 试玩与检查

- 唯一试玩入口是根目录 `启动动作博物馆.cmd`：用 CI 产物启动由开放场地组成的试玩场，不在本机构建。需要新场景时加进 `scripts/museum_scenarios.py`，见[试玩场](EXPERIMENTAL_MAP.md)。
- 更新已有试玩运行包：在实际游玩的机器关闭试玩场，将代码更新到目标 `main` 提交，再运行 `<python> scripts/update_museum_runtime.py --run <该提交成功的 Build run ID>`。脚本要求 CI 与当前提交一致，下载已编译代码，从同一提交恢复并逐个核对资源哈希，保留原运行包后切换 `museum-runtime.json`；仍用原来的 `.cmd` 启动。需要 `git`、已登录的 `gh` 和已有 NeoForge 运行配置，不编译、不自动启动游戏。
- 仓库没有 GameTest 或其他自动化测试。素材导出一致性可按需运行 `scripts/export_*.py --check`（只读，不编译），见[项目脚本](../scripts/README.md)，不是合并门槛。
- 根目录 `打开素材检查.cmd`／`关闭素材检查.cmd` 打开本机素材检查室，查看各形态的动作与表情；它读取游戏资源，不能代替游戏内确认。

### 试玩日志与事后排查

照常从 `启动动作博物馆.cmd` 启动即可自动记录，无需另开录制进程，也无需让助手实时盯日志。用户直接说“查刚才的日志”，附场景与大概时间即可，不需要手动导出或打包。

发现问题时，记下**场景、电脑上的大概时间、观察到的现象**，需要瞬时状态时，手工执行 `/yukkuri status` 或[场景诊断函数](EXPERIMENTAL_MAP.md#观察台的按钮分类)。通过 `yukkuri readout` 报告通道查询时，同一份文本快照会写入 `[readout]` 日志（含观察者、游戏 tick、位置与查询命令）；不是持续采样，也不会为了记录而推进玩法。报告超过大小限制会明确记截断。需要标记时，可直接在游戏聊天里发一句“迁徙：孩子停住了”，聊天记录也能作为时间定位。

**AI 查日志入口**（只读文件，不操作游戏界面）：

1. 先读 `.local/experimental-map/play-museum/process.json` 与同场 `.session.json`，定位启动时间、PID、世界与日志路径；进程是否仍存在要核对 PID **和** `startedTicks`，权限受限时看不到进程不能判定它已退出。
2. 当前游戏原生日志优先看 `.local/experimental-map/museum/logs/latest.log`，对照 `debug.log`。先看文件内最后的事件时间，再查最近场景标题、失败提示、事件与读数，保留异常堆栈续行。若启动方式不同，按启动记录的 `gameRoot` 或实际进程参数定位；旧配置中的 `game` 字段不一定是当前启动器使用的目录。
3. `.local/experimental-map/play-museum/<启动时间>.log`／`.err.log` 是按启动场次保留的标准输出／错误。必须比较它们与原生日志的末条时间：2026-10-21 实测 stdout 停在 00:38，而原生日志继续到 00:54，不能把 stdout 末尾当作最近试玩。旧场次另查原生日志目录中的日期归档（含 `.log.gz`）；核对启动时间与世界，不能把另一场次的 `latest.log` 混入旧场次。
4. 版本优先取启动器冻结的 `.session.json` 中 `runtimeConfig.commit/run`。旧场次没有快照时，可用仍在运行的进程中实际 `mod` 路径与该包 `mcyukkuri-runtime.json` 回执互证；不可仅用当前可变的配置或代码 HEAD 替代，无证据就标“版本待核实”。新版启动器的 `.session.json` 同时保存启动时间、时区、世界与游戏目录。
5. 按用户说的时间前后多取几分钟，需要留存时复制到 `.local/reports/<问题主题>/`；登记问题按[待办](TODO.md#实时日志发现的问题)的证据要求处理。原日志不修改、不清空，不用后台轮询代替一次有范围的读取。

时间以日志原文和记录机器时区为准。原生日志包含日期（本机形如 `[2110月2026 00:53:46.627]`），启动器 stdout 通常只有时分秒，跨午夜要结合场次日期判断。标准错误常无时间戳，不能假装已按时间精确筛选。

日志能核对已打点的事件、异常和手动读数，**不保证覆盖每一刻的全部实体状态或每次决策原因**。`/yukkuri status trace true` 可临时开启已有状态变更通知的日志，结束后用 `false` 关闭；它也不是完整状态连续录制。行为链读数不足时，仍需针对卡住环节补读数，不能把“没有日志”当作“没有发生”。

## 本机工具

这些工具在 `.local/`，不入库，只存在于开发机，换机器后可能没有。

| 工具 | 位置与用法 |
| --- | --- |
| Python | 启动器优先使用 `museum-runtime.json` 的 `python`，其次尝试 `py`、`python`。选用真实 Python 3，世界生成需要 `nbtlib`，建模脚本按需依赖 numpy、Pillow；换机器后重新核对解释器与依赖。 |
| Java 语法检查 | `<python> .local/smp/java_syntax.py <文件...>`：tree-sitter 只做语法解析，不是编译 |
| 原版与 NeoForge 源码 | `.local/mc-sources/`，用于核对 1.21.1 API 签名 |
| 语言文件合并 | `.local/smp/merge_lang.py`：合并冲突时按键三方合并 lang JSON |
| 取 CI 运行包 | `<python> scripts/update_museum_runtime.py --run <run-id>`：下载已编译产物，核对同一提交的资源，保留旧运行包后切换试玩配置 |
| 试玩场运行包 | 更新脚本读回 `.local/experimental-map/museum-runtime.json` 的 `runtime`／`commit` 后，仍由原 `.cmd` 启动。运行包过旧会让数据包函数静默失效 |

## 合并冲突与生成文件

- 生成文件（动作覆盖、语言、对白导出）冲突时不要手工拼接：先取上游版本，合并制作源，再重新导出。
- 动作：合并 `standards/yukkuri-library/motions/variants/lore/recipe.json`、`author.py`、`motion_corrections.json` 等制作源后，完整运行 `scripts/export_motion_corrections.py`，再在 `lore/` 目录运行 `author.py` 的相关 `--export-*`（`carry`、`birth`、`badge`、`comfort`、`distress`、`pet-and-distress`）。
- 语言 JSON 按键合并（可用 `.local/smp/merge_lang.py`）；对白源按行 ID 合并后运行 `scripts/export_dialogue.py`。

## 本机磁盘

`.local/setup/` 的旧 `runtime-*`、`ci-*` 可能占用较多磁盘。清理前核对 `museum-runtime.json`、运行进程与待回退包，只删除已不再引用的旧产物；本机临时脚本不作为跨机器的维护入口。

## 制作源与运行资源

目录职责见[项目地图](../PROJECT_MAP.md)。标准模型在 `standards/yukkuri-library/` 制作；游戏使用 `art/source/` 下选定的快照，运行数据在 `src/main/resources/assets/mcyukkuri/yukkuri/`，对应关系登记在 [art/runtime-sources.json](../art/runtime-sources.json)。

标准造型确认、素材导出和游戏接入是不同状态：采用新版标准模型时，单独检查资源标识、骨架、贴图与动画适配，不因标准库更新自动覆盖已接入资源。改模型改制作源再导出，在本机出一张当前预览贴在回复里；预览、截图与检查结果不入库。

## 上游文档

- [NeoForge 1.21.1 开发文档](https://docs.neoforged.net/docs/1.21.1/gettingstarted/)
- [NeoForge ModDevGradle 模板](https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle)

## 技术改动的采用条件

- **运行资源瘦身**：先测同一提交的完整 Jar 体积和 Java 加载／重载耗时，再决定是否改变 JSON 或归档压缩；不能用单文件 gzip 比例替代完整产物收益。保持模型数字字面量、几何指纹、资源包覆盖与外观契约；收益不足则保留现格式。Java 测量放在允许运行的远端或试玩环境，本机不编译。
- **实体同步改网络包**：已有 `ReadoutPayload` / `ReadoutNetworking` 服务于定向报告；实体展示仍沿用 `SynchedEntityData` / `CompoundTag`。只有实际性能问题或同步内容扩展需要时再迁移。
- **可选兼容候选**：Jade 状态显示、农夫乐事食物、季节模组替换 `Seasons.at`；已有观察笔记覆盖基本用途，不优先新增 Patchouli 图鉴。此处保留候选边界，正式排期只登记在待办。
