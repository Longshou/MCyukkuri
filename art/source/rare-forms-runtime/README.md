# 稀有形态的游戏接入快照

由 `scripts/export_rare_forms.py` 从 [rare-forms 制作包](../../../standards/yukkuri-library/subspecies/rare-forms/README.md) 生成。每种形态一个目录，内含 `runtime.model.json` 和 `textures/`，与 `src/main/resources/assets/mcyukkuri/yukkuri/<形态>.json` 及其贴图逐字节一致。登记见 `art/runtime-sources.json` 的 `rare_forms`。

伤口表面、徽章挂点、贴合、动作修订、出生姿态、便所接触和束具道具都沿用基底种族的数据，只换成该形态自己的模型指纹。被修改过的部件（妖梦的蝴蝶结和收起的蕾丝、被炉、尾巴）会重新生成包络，并从贴合数据中剔除。造型待确认，游戏内未验收。
