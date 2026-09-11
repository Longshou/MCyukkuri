# 本地美术历史备份

以下为历史备份的位置说明，所列 ZIP、清单和目录不随仓库提供，也不表示当前磁盘上仍有对应文件。正式源文件包见 [分类资料库](../art/library/README.md)，新增临时备份放项目内 `.local/`。

- `motion-data-20260911-013143.zip` 与 `latest-motion-snapshot.json`：此前已存在的备份，其中弃用条目待清理，不能直接全量恢复。
- `art-preservation/objects/<项目>/`：按内容哈希保存的完整项目包与逐文件 SHA-256 清单。
- `art-preservation/snapshots/`：每次整理的全量目录清单。
- `latest-preservation.json`：最新完整快照的路径、校验值和汇总。

这里保留原作参考图、逐帧渲染和历史交付包，因此除本说明外均为本地文件。可移植且纳入版本管理范围的自绘模型、保留方案的动作和行为脚本另存于 [分类资料库](../art/library/README.md)。

本地备份不等同于异地备份。历史清理范围见 [清理清单](../art/library/retirement-plan.json)，清单不证明当前磁盘状态。保存与恢复命令见 [资产流程](../docs/ASSET_WORKFLOW.md)。
