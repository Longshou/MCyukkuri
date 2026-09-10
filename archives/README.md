# 本地美术历史备份

- `motion-data-20260911-013143.zip` 与 `latest-motion-snapshot.json`：此前已存在的备份，其中弃用条目待清理，不能直接全量恢复。
- `art-preservation/objects/<项目>/`：按内容哈希保存的完整项目包与逐文件 SHA-256 清单。
- `art-preservation/snapshots/`：每次整理的全量目录清单。
- `latest-preservation.json`：最新完整快照的路径、校验值和汇总。

这里保留原作参考图、逐帧渲染和历史交付包，因此除本说明外均为本地文件。可移植且纳入版本管理范围的自绘模型、保留方案的动作和行为脚本另存于 [分类资料库](../art/library/README.md)。

这是同一磁盘上的校验备份，不等同于异地备份或已经上传。弃用目录及旧备份的实际删除被执行策略拦截；新快照已排除它们。残留范围见 [清理清单](../art/library/retirement-plan.json)。保存与恢复命令见 [资产流程](../docs/ASSET_WORKFLOW.md)。
