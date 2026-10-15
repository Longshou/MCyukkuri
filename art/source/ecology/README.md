# 日常生态表情接入

从 2026-09-12 已确认的标准表情中选用 `closed_smile`、`chewing`、`chewing_closed`、`satisfied`、`open_smile`。七种既有实体各增加 5 套图集，原有 11 套保留；新图集适用于当前 128×128 UV。

来源与选定模型指纹见 [expressions.json](expressions.json)。制作源仍是 `standards/yukkuri-library/expressions/presets/` 与对应角色的已确认图集，本目录保存接入快照。脚本检查批准时的导出指纹、确认状态和空几何偏移；需要嘴部额外缩放的 `smug`、`pursed` 没有直接套入。

家油制作脚本原本就复制对应普通角色图集，本轮沿用该 UV 关系。没有修改标准模型、运行三角面、骨架或行走曲线；新增擦脸摆动在 `YukkuriRenderer` 中，按真实同伴及当前模型嘴面位置补齐碰撞盒与面部之间的空隙，所有身体附属使用同一根变换。唱歌口部复用 `YukkuriModel` 的既有嘴骨。捕食种使用其独立素材，本轮不将普通种图集套到捕食种上。

```powershell
python scripts/export_ecology_expressions.py
python scripts/export_ecology_expressions.py --check
```

当前实机动作、表情和验证范围见[日常生态记录](../../../docs/ECOLOGY_DAILY_LIFE.md)。标准表情已确认，运行组合与原创动作适配仍须按实际游戏画面判断，不把标准批准当成新的交互演出已获用户确认。
