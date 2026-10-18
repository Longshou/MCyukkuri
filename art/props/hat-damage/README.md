# 头饰损坏与碎片

头饰第二生命（[设计](../../../docs/HEADWEAR.md)）的外观制作源。`recipe.json` 在每件饰物自己的包围盒里放置缺口、毛边和裂缝区域，以及三块碎片的范围；游戏用 `client/HatDamageMask` 把同一规则套到各物种戴着的饰物和饰物物品上，不新增网格和贴图。

- 导出：`python scripts/export_hat_damage.py`（`--check` 只读核对）。
- 预览：`python art/props/hat-damage/preview.py <输出目录>`，默认 `.local/previews/hat-damage/`。
- 状态：候选，待用户确认（`recipe.json` 的 `status`）。
