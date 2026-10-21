# 项目地图

目录分工、素材流向和命名规则。开发规则见 [AGENTS.md](AGENTS.md)，各游戏系统的文档与代码入口见 [文档索引](docs/README.md)。

## 目录

| 位置 | 职责 |
| --- | --- |
| `src/main/java/io/github/longshou/mcyukkuri/` | 模组代码，按系统分包（包与文档的对应见[文档索引](docs/README.md#代码包)）；客户端实现只放 `client/` |
| `src/main/resources/` | 实际打包进模组的几何、贴图、动画、声音、语言、配方和数据包 |
| `src/main/templates/` | 构建时替换版本号的模组声明模板 |
| `standards/yukkuri-library/` | 标准美术制作源：共用组件、角色登记、造型、表情、动作、派生资产及导出；待确认方案在各包 `drafts/`。见 [standards/](standards/README.md) |
| `standards/yukkuri-v1/` | 仍被读取的成长规格、茎、出生行为与对应脚本 |
| `art/source/` | 游戏当前选用的素材快照（按包分 `standard-runtime/`、`rounded-runtime/`、`subspecies-runtime/`、`predator-runtime/`、`rare-forms-runtime/` 等），对应关系登记在 `art/runtime-sources.json`。见 [art/](art/README.md) |
| `art/props/` | 独立道具（食盆、器具、玩具、伤口、徽章、住所……）的制作源与来源登记 |
| `art/structures/human-world/` | 商店、工厂的原创布局、模板实体与生成规则 |
| `art/audio/`、`art/dialogue/` | AquesTalk 配音与生态音效制作源；八景 U 原版对白检索、选句与译文。游戏 OGG 在 `src/main/resources/assets/mcyukkuri/sounds/` |
| `wiki/` | 设定与作品出处、参考图库、小说阅读卡。设定依据，不是运行资源。见 [wiki/](wiki/README.md) |
| `docs/` | 开发文档：入口、待办、设计裁决和各系统专题。见 [文档索引](docs/README.md) |
| `scripts/` | 跨目录的导出、检查和试玩场生成脚本，每个脚本注明用途与写入边界。见 [scripts/](scripts/README.md) |
| `preview/hub/`、`preview/review/` | 预览总入口模板与素材检查室源码；由 `scripts/build_preview_hub.py` 生成到 `.local/previews/` |
| `tools/browser-check/` | 各 `verify*.cjs` 使用的 Playwright 依赖 |
| `.github/` | CI（只编译）与 Issue／PR 模板 |
| `.local/` | 本机工作区，不提交：预览、截图、报告、草稿、下载的参考、CI 运行包、试玩场存档、本机工具（见[开发指南](docs/DEVELOPMENT.md#本机工具)） |
| `.worktrees/` | 需要隔离时的 git 工作树，不纳入版本管理 |
| `油库里八景ver1.12/` | 用户提供的原作参考包，不提交 |
| 根目录 `.cmd` | `启动动作博物馆.cmd` 启动试玩场（唯一试玩入口）；`打开素材检查.cmd`／`关闭素材检查.cmd` 开关素材检查室 |

## 素材从制作到游戏

1. **制作**：在 `standards/yukkuri-library/` 改制作源（组件、配方、登记），导出到包内 `assembled/<角色>/`。确认状态写在角色或资产包的登记字段里。
2. **选用**：游戏采用某版本时，把快照放进 `art/source/<包>/`，同步更新 `art/runtime-sources.json`。标准库更新不会自动替换游戏资源。
3. **接入**：用 `scripts/export_*.py`（如 `export_runtime_models.py`、`export_rare_forms.py`）生成 `src/main/resources/` 下的运行资源；`--check` 只读核对一致性。
4. 运行模型位于 `src/main/resources/assets/mcyukkuri/yukkuri/`，表情贴图在 `textures/entity/<实体 ID>/`。头饰与头发的位置适配、动作修订在运行时拼接，不改写基础模型。

## 文件命名与放置

- 目录用稳定的英文 ID（如 `patchouli`），文件名说明内容（如 `patchouli.geo.json`），不用“最终版”“新版2”表示版本。
- 同一素材包的制作脚本和检查脚本留在包内，避免移动后破坏相对引用；跨目录的脚本放 `scripts/` 并登记在其 README。
- 当前导出放 `assembled/`，待确认方案放 `drafts/<主题>/`；被替代的方案直接删除，不设 `archive/`，需要时从 Git 历史找回。
- 预览统一生成到 `.local/previews/<主题>/`（总入口 `.local/previews/index.html`），同主题只保留当前版本；截图和报告放 `.local/screenshots/`、`.local/reports/`，都不入库。
- 导出脚本 `--check` 读取的 `manifest.json`、`provenance.json` 等来源登记是数据，随源文件维护。

## 文档维护

每次改动需要更新哪些文档，统一按 [AGENTS.md](AGENTS.md#文档按变更维护)。根 README 只做简介与导航，本页只管目录与素材流向；专题写规则，待办写未完成事项，Wiki 保存作品证据，不重复维护实现进度。
