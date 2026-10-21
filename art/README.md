# 当前游戏选用素材

`art/` 保存 Minecraft 当前选用的素材快照。制作源在[标准素材库](../standards/yukkuri-library/README.md)，选用快照与运行资源映射由 [`runtime-sources.json`](runtime-sources.json) 管理；标准库的修改不会自动替换游戏资源。

当前素材检查入口为项目根目录 `打开素材检查.cmd`，集中预览入口见[预览说明](../preview/README.md)。

| 目录 | 内容 |
| --- | --- |
| [source/](source/) | 角色模型与动作快照、位置适配、交互及生态展示 |
| [props/](props/README.md) | 道具、伤口、徽章与设施快照 |
| [structures/](structures/human-world/README.md) | 商店、工厂与人类世界布局 |
| [audio/](audio/README.md)、[dialogue/](dialogue/README.md) | 游戏配音、哼唱与原版对白来源 |

## 运行资源

选定的 `.bbmodel` 保留编辑骨架；九套圆润形态的适配骨架与顶点绑定由 `scripts/export_rounded_runtime.py` 从选定源生成，家油、捕食种与不足形态使用各自登记的导出器。旧七模型批量导出入口 `export_runtime_models.py` 禁止覆盖当前选用。运行模型 JSON 位于 `src/main/resources/assets/mcyukkuri/yukkuri/`，贴图位于 `textures/entity/<实体 ID>/`；快照、配方、运行文件与指纹见 [`runtime-sources.json`](runtime-sources.json)。

角色设定与美术来源见 [Wiki](../wiki/README.md)。几何与像素贴图为项目制作；原作程序、录音与美术不打入游戏资源，第三方授权未核实的内容不标为已获开源授权。
