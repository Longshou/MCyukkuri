# 标准动作

| 动作 | 状态 | 参数与运行实现 | 预览 |
| --- | --- | --- | --- |
| [灵梦茎生 v2](reimu-stem-birth-v2.json) | 2026-09-11 用户确认保存的复合动作 | `behaviors/stem-birth.json` + `runtime/stem-birth.js` | [打开](../lifecycle/index.html) |
| [灵梦胎生 v1](reimu-viviparous-birth.json) | 2026-09-11 用户确认保存的复合动作 | `behaviors/viviparous-birth.json` + `runtime/viviparous-birth.js` | [打开](../viviparous/index.html) |

茎生包含母体、茎和多个子体，按可复用的程序动作保存；不是单个骨架的循环动作。当前确认版本为 `action.reimu.stem_birth.v2`，历史 [v1](reimu-stem-birth.json) 保留独立快照。本地预览与事件、保存恢复逻辑可复用，Minecraft 实体接入仍待实现。

茎生 v2 已保存有厚度、朝子油收尖且比主茎细的头顶连接茎。同时记录“母体大小影响子油数量；数量影响茎的长度和粗细；存在个体差异”。关系的具体数值实现待定，当前画面与运行逻辑按本次确认内容保存。历史 v1 ZIP 保留原样。

确认版本的源文件和独立离线预览以原始字节打包在 `snapshots/`，[快照索引](approved-snapshots.json)记录 ZIP 与逐文件 SHA-256。解压到新目录即可打开 `lifecycle/index.html`，或运行包内 `build_lifecycle.py` 重建。后续胎生编辑不会改写这份确认快照。

胎生复合动作 ID 为 `action.reimu.viviparous_birth.v1`，组合母体孕形和用力表情、闭合产道、子体接触挤压、依次出生、落地苏醒与产后恢复。由 `snapshot_viviparous.py` 保存完整依赖、离线预览与近景截图；解压后直接打开 `viviparous/index.html`，重建使用 `build_viviparous.py`（需要 Pillow）。快照包含所用的共享运行代码，后续调整茎生不会改变已保存的胎生版本。
