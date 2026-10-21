# 标准模型旧快照与家油适配

本目录保留原七模型接入快照及家油模型适配数据。当前游戏形态与来源以 [runtime-sources.json](../../runtime-sources.json) 为准；普通角色已改用[圆润运行包](../rounded-runtime/README.md)，本目录的旧快照不代表当前普通角色资源。

| 目录 | 来源与用途 |
| --- | --- |
| `reimu/`、`marisa/`、`chen/`、`alice/`、`patchouli/` | 标准库选定模型的接入快照 |
| `house_reimu/`、`house_marisa/` | 家油专用形态及标准骨架适配 |
| [face-motion/](face-motion/README.md) | 家油动态五官挂接 |
| [HOUSE_INTERACTIONS.md](HOUSE_INTERACTIONS.md) | 家油拾取、短辫搬运与低位蠕动的动作数据 |
| [WALKING.md](WALKING.md) | 运行步态配方 |

每个模型目录保存选定 `.bbmodel`、适配骨架与 `runtime.model.json`。标准源的原始编辑骨架与适配后数字骨架不同；两者的转换配方由 [`scripts/export_runtime_models.py`](../../../scripts/export_runtime_models.py) 维护。修改适配先改配方，再更新对应快照；改标准造型时从素材库制作源重新导出。家油交互由 [`runtime_house_interactions.py`](../../../scripts/runtime_house_interactions.py) 与 `scripts/export_house_carry_motion.py` 定向更新。
