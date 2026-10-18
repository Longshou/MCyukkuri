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

## 试玩与检查

- 唯一试玩入口是根目录 `启动动作博物馆.cmd`：用 CI 产物启动由开放场地组成的试玩场，不在本机构建。需要新场景时加进 `scripts/museum_scenarios.py`，见[试玩场](EXPERIMENTAL_MAP.md)。
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
| 取 CI 运行包 | `<python> -I .local/setup/fast-runtime.py <分支名或 run-id>`：下载 2 MB 运行产物、从 git 取资源、校验哈希，约 1 分钟生成 `.local/setup/runtime-<sha前8位>` |
| 无界面自查 | `.local/harness/`（见其 README）：用运行包起专用服务器，RCON 发命令、`sprint` 推进时间、读实体 NBT、跑带断言的脚本。每个使用者用独有的 `--name`；实体只在 forceload 区块内 tick；用完 `stop --clean` |
| 试玩场运行包 | 推送后更新试玩场：改 `.local/experimental-map/museum-runtime.json` 的 `runtime`／`commit` 指向新运行包。运行包过旧会让数据包函数静默失效 |

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
