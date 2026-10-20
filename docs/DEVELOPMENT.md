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

- `mcyukkuri-<完整sha>`：开发 Jar（约 105 MB），放进匹配版本 NeoForge 实例的 `mods/` 即可游玩。
- `mcyukkuri-runtime-<完整sha>`：约 2 MB 的已编译代码与资源哈希清单（`scripts/package_ci_runtime.py` 生成），本机据此拼出运行包。

PR 构建编译的是 PR **合并提交**，产物名用合并提交的 sha。编译失败用 `gh run view <id> --log-failed` 看错误。推送后不必前台等 CI，放到后台再看结果。

以下命令只供允许编译的环境（远端或其他机器）使用，本机不执行：

```powershell
.\gradlew.bat build       # 产物 build/libs/mcyukkuri-0.1.0-dev.jar
.\gradlew.bat runClient
.\gradlew.bat runServer   # 首次需自行阅读并接受 Minecraft EULA
```

## 启动接线与服务器配置

`MCYukkuri` 负责资源与配置入口，`registry/CommonRegistration` 逐条声明通用事件监听；独立的 CLIENT 模组入口 `client/ClientRegistration` 负责渲染、声音和客户端读数接线。共 85 条监听，均明确事件类型、NORMAL 优先级和不接收已取消事件。新增监听时在对应入口登记，业务类不再使用自动订阅注解。

同一事件的注册顺序写在入口中：保留原构造期前缀及聚落 ticker 的位置；入世界时恢复演出状态；五属性由实体的性格组件持有，不再另存认知账本。`HumanCommerce` 在实体完整 `super.tick()` 后处理，随后实体 Post 事件依次处理帽上乘客和野生事件发现；组件原有阶段不变。该顺序不承诺与第三方监听维持过去未定义的交错。

Facilities、Playgrounds、Consumables、FreezerModule 的 `register(modBus)` 分别绑定本模块的资源登记器，合计 8 个；与其他资源模块合计 23 个 DeferredRegister。资源标识不变，旧物品 `mint_leaf` 仍通过别名读取为 `sleep_spray`。登记入口只在模组构造时调用一次，重复绑定由 DeferredRegister 抛错，不静默跳过。

三份配置均注册为 SERVER 配置，保存到对应世界的 `serverconfig/`，以服务器值为准：

| 文件 | 定义位置 | 分工 |
| --- | --- | --- |
| `mcyukkuri-server.toml` | `life/YukkuriConfig` | 生理、生态、聚落等规则，71 个玩法键与 1 个调试键 |
| `mcyukkuri-family.toml` | `family/FamilyConfig` | 家族与繁殖时序，10 个键；成长阶段与寿命使用固定时钟 |
| `mcyukkuri-human-world.toml` | `human/HumanWorld` | 人类遭遇、商贸、徽章期限、工厂运输及滞销处理，9 个键 |

合计 91 个完整配置路径。分组名称属于路径，例如 `community.migrateThreshold`、`seasons.biomeSeasons`、`home_claims.enabled`、`badge_social.enabled`。`debug.entitySaveViews` 默认 `false`，只控制实体四份调试视图的写入；试玩场脚本的已有用法不变。

## 试玩与检查

- 唯一试玩入口是根目录 `启动动作博物馆.cmd`：用 CI 产物启动由开放场地组成的试玩场，不在本机构建。需要新场景时加进 `scripts/museum_scenarios.py`，见[试玩场](EXPERIMENTAL_MAP.md)。
- 更新已有试玩运行包：在实际游玩的机器关闭试玩场，将代码更新到目标 `main` 提交，再运行 `<python> scripts/update_museum_runtime.py --run <该提交成功的 Build run ID>`。脚本要求 CI 与当前提交一致，下载已编译代码，从同一提交恢复并逐个核对资源哈希，保留原运行包后切换 `museum-runtime.json`；仍用原来的 `.cmd` 启动。需要 `git`、已登录的 `gh` 和已有 NeoForge 运行配置，不编译、不自动启动游戏。
- 仓库没有 GameTest 或其他自动化测试。素材导出一致性可按需运行 `scripts/export_*.py --check`（只读，不编译），见[项目脚本](../scripts/README.md)，不是合并门槛。
- 根目录 `打开素材检查.cmd`／`关闭素材检查.cmd` 打开本机素材检查室，查看各形态的动作与表情；它读取游戏资源，不能代替游戏内确认。

## 本机工具

这些工具在 `.local/`，不入库，只存在于开发机，换机器后可能没有。

| 工具 | 位置与用法 |
| --- | --- |
| Python | 系统 `python` 是 Microsoft Store 占位。可用解释器（含 numpy、Pillow、nbtlib）：`C:\Users\Administrator\.cache\codex-runtimes\codex-primary-runtime\dependencies\python\python.exe`。node 在 PATH 上 |
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

`.local/setup/` 会快速堆积运行包（曾到 49 GB），定期按 `.local/setup/land.sh` 里的清理方式删除旧的 `runtime-*`、`ci-*`。

## 制作源与运行资源

目录职责见[项目地图](../PROJECT_MAP.md)。标准模型在 `standards/yukkuri-library/` 制作；游戏使用 `art/source/` 下选定的快照，运行数据在 `src/main/resources/assets/mcyukkuri/yukkuri/`，对应关系登记在 [art/runtime-sources.json](../art/runtime-sources.json)。

标准造型确认、素材导出和游戏接入是不同状态：采用新版标准模型时，单独检查资源标识、骨架、贴图与动画适配，不因标准库更新自动覆盖已接入资源。改模型改制作源再导出，在本机出一张当前预览贴在回复里；预览、截图与检查结果不入库。

## 上游文档

- [NeoForge 1.21.1 开发文档](https://docs.neoforged.net/docs/1.21.1/gettingstarted/)
- [NeoForge ModDevGradle 模板](https://github.com/NeoForgeMDKs/MDK-1.21.1-ModDevGradle)
