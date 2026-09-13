# 垂眼哭丧可爱化

用户认为上一轮过于写实，要求偏可爱。本轮将 N041/N042 的眼睛改为圆润大瞳孔、小高光和单层简洁轮廓，减少眼白与厚眼睑，用已确认的眉毛和泪花表达委屈。2026-09-12 用户“接受”当前展示版，两项均已确认保存。

N017 微张嘴、N012 眉毛和 N024/N025 泪花保留原像素；嘴与眉的位置、缩放保持上轮值，只沿新的下眼缘调整泪片高度。两帧使用相同眼型，分别展示蓄泪和滑落。

情绪依据沿用[上轮原图与场景核读](../round3/REFERENCE.md)，本轮画法是按用户偏好制作的卡通化设计，不作为八景 U 原图细节。已将两片眼部 PNG 复制入正式部件库，并同步正式配方、六角色导出与确认指纹；Minecraft 资源未接入本轮造型。

`before.json`、`before-manifest.json`、`before-overview.png` 与 `before-report.json` 保留上轮造型及对应记录，用于本轮前后比较。`eye-design.json` 和 `parts/` 保存本轮眼部像素及配色，主草案配方位于 `../presets/`，六角色配套图集位于 `../textures/`。

制作脚本已在确认后加保护，防止覆盖已保存版本。仅重建对照页：

```powershell
python standards/yukkuri-library/tools/build_reused_expression_review.py drafts/expression-review/reuse-batch
```

确认时的[正面总览](accepted-pending-batch.png)、[斜侧面总览](accepted-pending-batch-oblique.png)和 `accepted-report.json` 保留本版本证据。历史报告中的输出路径记录当时位置；对应截图现归档于本目录。当前 `next-batch.html` 已进入 N051–N062 下一批。
