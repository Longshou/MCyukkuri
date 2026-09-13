# 剩余表情核对

N063–N074 第二轮展示的 11 项已全部保存，确认指纹见[批次登记](../../../expressions/component-review/batch-63-74-decisions.json)。用户已通过本轮除 N077/N078 外的九项，见[确认登记](../../../expressions/component-review/final-batch-decisions.json)。当前队列已清空，N072/N077/N078 均暂缓；下面的表格保留本轮组合来源。

| 编号 | 本轮组合 |
| --- | --- |
| N075/N076 | N056 挤眼、N029 紧眉、N022 哭喊嘴；N076 增加 N052 饱满泪花 |
| N077/N078 | 保留原稿的专用破碎、散落像素；仅核对图案及用途 |
| N079 | N033 已确认棕灰失神眼、眉与笑嘴；N055 鼓腮，保留 1.4 倍腮部高度 |
| N003 | 沿用 O04 已有 A 的眼眉嘴，B 改用 N052 饱满泪花；左侧为已有 A |
| N004 | 沿用 O05 已有 A 的眼眉嘴，B 改用 N052 饱满泪花；左侧为已有 A |
| N006/N007 | N046 大瞳孔／N027 白眼、N051 忧虑眉、N053 小抿嘴 |
| N008/N009 | N046 大瞳孔、N002 松眉、N001 笑嘴；N051/N052 细薄／饱满泪花差分 |

`before.json` 为本批正式原配方快照，`presets/` 保存本轮制作源，其中九项已确认，N077/N078 仍待确认，`textures/<角色>/` 为六角色共 66 张图集。`reuse-manifest.json` 记录 96 个供体槽位及指纹，包括 N003/N004 保留已有 A 的来源；14 个未匹配可见槽位均为 N077/N078 专用碎裂像素，继续保留待核对。已有 A 的正式配方、已确认组件均未重画。

N077/N078 来源于配合身体、头发一同破碎的二维阶段图层。当前三维预览只展示脸部图案；三维碎块、身体破坏及对应动作尚未制作或验收。N072 的二维仅眉毛图层继续保留来源，三维遮挡由物体与姿态处理。

当前重建核对收尾页；原准备脚本会拒绝覆盖已确认项：

```powershell
python standards/yukkuri-library/tools/build_reused_expression_review.py
```

[当前对照页](../next-batch.html) · [正面总览](../../../previews/current/pending-batch.png) · [斜侧面总览](../../../previews/current/pending-batch-oblique.png) · [参考核读](REFERENCE.md) · [检查记录](../../../reports/current/expression-component-reuse.json)

九项批准前配方及当时完整十一项总览保存在 [acceptance](acceptance/README.md)，正式配方与六角色导出已同步。
