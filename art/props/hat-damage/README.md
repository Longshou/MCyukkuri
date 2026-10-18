# 头饰损坏与碎片

头饰第二生命（[设计](../../../docs/HEADWEAR.md)）的外观制作源。现在的规则刻意按 MC 像素风格做得更粗：

- **损坏**保留完整头饰轮廓。前/后/上/下四个方向的盒面切成正方形像素格，只去掉少量格子；左右侧面的薄面保持完整，所以不会出现三角形锯齿或细长毛刺。轻损 5 处、撕裂 8 处，位置不对称、大小混合；帽顶只留 1–2 格（轻损）/ 2–3 格（撕裂）的小缺口，避免帕秋莉帽顶被一整片盖住。
- 每个方孔内部填一块从**局部布料颜色**算出的内衬：绿色用浅绿、红色用亮红、黑色用浅灰紫、淡粉用暗粉；孔边缘用同色系深色。这样孔像布料的创口，而不是灰黑色块。
- **碎片**三块完全同款：一个共用的小块破布模型，按来源头饰的主色着色。碎片 1/2/3 的外观不区分；不同头饰用不同颜色，视觉上不通用。物品归属/堆叠逻辑不在本制作源内，仍按现有 `AccessoryShardItem` 处理。
- 被摘下的饰物物品同样使用方形面片内衬（`client/PropModels`），与戴在头上的损坏一致。
- 拼回状态不挖洞，只沿两道窄缝加深，显示“拼起来但还看得见接缝”。

## 文件

- `recipe.json`：咬口位置/大小、粗格尺寸、缝线、碎片模型尺寸和颜色族。
- 导出：`python scripts/export_hat_damage.py`（`--check` 只读核对）。输出：
  - `yukkuri_presentation/hat_damage.json`
  - `yukkuri_props/accessory_shard.json`
  - `yukkuri_props/hat-damage-index.json`
  - `textures/props/accessory_shard.png`
  - `textures/props/hat_damage_inner.png`（内衬/边缘的白色底）
  - `models/item/accessory_shard.json`
- 预览：`python art/props/hat-damage/preview.py`，默认输出 `.local/previews/hat-damage/`（总览与近看）。
- 状态：已确认（`recipe.json` 的 `status: approved`，2026-10-18）。

## 约束

- 不替换完整头饰的几何，只跳过方形格；不改动帽船船体。
- 碎片共用同一个模型和贴图；颜色只来自 `SourceModel` 的色族，不按 `Piece` 改变外观。
- 运行时对应 `client/HatDamageMask`（咬口规则）与 `client/YukkuriModel`、`client/PropModels`（粗格渲染）。
