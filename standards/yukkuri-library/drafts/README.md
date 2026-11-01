# 草稿素材

本目录保存尚未并入正式标准源的制作草稿，以及已选用道具、伤口与遗体的现行制作源（导出脚本和 `art/props/*`、`art/source/*` 清单直接读取这里的路径，不要改名）。正式表情源以 [`expressions/index.json`](../expressions/index.json) 及其配方为准；标准集合与指纹见[标准表情](../STANDARD-EXPRESSIONS.md)。

| 入口 | 用途 |
| --- | --- |
| [身体纹样](body-marks/README.md) | 伤口与身体标记制作源 |
| [照料扩展](care-expansion/README.md) | 照料设施与物品草稿 |
| [冷冻收容柜](cold-storage/README.md) | 冷冻收容设施草稿 |
| [照料道具](care-props/README.md) | 食盆、窝、厕所等照料道具共用制作源 |
| [操作器具](operation-tools/README.md) | 接触操作器具模型 |
| [大型游具](playgrounds/README.md) | 纸筒、滑梯、蹦床、水池 |
| `consumables/` | 消耗品模型制作源（`build.py`、`recipe.json`） |
| [成体流程适配](rapem-alice/README.md) | 成体外形适配与离线查看工具 |
| [遗体造型](remains/README.md) | 按死因的遗体形态与水中溶化 |
| `rounded-standard-body/` | 已推广到[圆润标准体型](../body-standard/README.md)；保留的批准时快照供 `body-standard/verify_sources.py` 比对 |

新增草稿按主题建目录；正式采用后更新对应标准源和来源登记。
