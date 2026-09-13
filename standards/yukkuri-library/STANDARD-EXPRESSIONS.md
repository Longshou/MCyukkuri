# 标准表情

2026-09-12，用户确认将当前 89 项已核对表情保存为标准：76 项新增表情与 13 项原有表情。后续制作优先复用此集合中的五官、配方和已确认 A/B 差分。

[标准表情预览](standard-expressions.html) · [标准登记与文件指纹](provenance/standard-expressions.json) · [检查报告](reports/current/standard-expressions.json)

## 保存范围

- `expressions/index.json` 的 `standard_set` 指向唯一的标准表情集合。集合逐项列出编号、ID、正式配方与来源文件指纹；全部 89 项正式配方均为 `accepted`。
- 本次保存保留当前五官像素、几何偏移、鼓腮比例和动作要求。只补齐 36 项旧配方的确认状态，已有 53 项配方及此前批次批准指纹保持不变。
- N033 沿用已确认的棕灰版，A 为默认，B/C 保留为同编号备选，见[备选登记](expressions/variants/dull/index.json)。备选不重复计入 89 项。
- N072、N077、N078 继续暂缓；N080–N092 仅作历史对照，均未纳入标准集合。两个独立叠加效果也不属于本次 89 项表情确认范围。

## 制作源与导出

正式源位于 `expressions/presets/`，像素部件复用 `expressions/parts/`，几何与 UV 来自标准脸部组件及各角色绑定。登记保存源文件指纹和本次导出指纹；改变造型后须重新确认，不能沿用此次批准记录。

六个角色共用同一表情集合，配套导出为 534 张角色表情图集、89 张缩略图及六个独立预览。`standard-expressions.html` 只展示本次 89 项，完整历史图鉴仍保留全部 105 项。

本次保存属于标准素材库，未替换 Minecraft 运行快照。此前筛选过程与批准前配方保留在原批次记录及[登记前快照](archive/expression-standard-2026-09-12/README.md)中。
