# 日常生态表情接入

本包从[标准表情](../../../standards/yukkuri-library/expressions/index.json)选用 `closed_smile`、`chewing`、`chewing_closed`、`satisfied` 与 `open_smile`。快照与逐形态来源指纹见 [expressions.json](expressions.json)。需要额外嘴部缩放的 `smug`、`pursed` 不直接套用。

家油沿用对应普通角色的图集 UV，不修改标准模型、运行网格、骨架或步态。擦脸摆动由 `YukkuriRenderer` 根据实际同伴和嘴部位置处理；身体附属件使用同一根变换。唱歌口部复用 `YukkuriModel` 现有嘴骨，捕食种使用其专属素材。

从仓库根目录运行 `python scripts/export_ecology_expressions.py` 更新接入快照；游戏玩法见[日常生态](../../../docs/ECOLOGY_DAILY_LIFE.md)。本包只维护表情选用与擦脸动作的接入数据，生态行为由游戏玩法模块驱动。
