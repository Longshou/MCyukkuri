# 第三轮：保存通过项与区分泪花 A/B

用户随后“通过”当前两组差分，N051–N062 全批已保存；以下保留第三轮制作经过。确认时的[正面](accepted-pending-batch.png)与[斜侧面](accepted-pending-batch-oblique.png)已归档；`pairs-approved.json` 登记通过范围，`accepted-exports.json` 核对源与导出。当前核对页已进入 N063–N074。

用户要求 N057 恢复老版嘴，认可其余改后造型，并指出睁眼哭喊和寒冷发颤的 A/B 同形。N053/N054/N055/N056/N057/N058/N059/N062 已保存为标准；N057 采用首轮偏侧咀嚼嘴，其余部件保留第二轮。确认指纹见[本批登记](../../../../expressions/component-review/batch-51-62-decisions.json)。八项已同步六角色导出，整体剩余 27 项待确认。

当前只核对 N051/N052、N060/N061 两组差分：

- A 保留第二轮细薄眼缘水光。
- B 沿用同一 N012 泪花像素，宽度为 A 的 1.15 倍、高度为 1.7 倍，按第 5 行高光基准重新贴合下眼缘，形成更饱满的泪花。眼神、眉毛和嘴型保持一致。
- 两组不再登记同形配方；只在实际模型前后与 A/B 对照中核对本轮区别。差分造型仍待用户确认。

用户的后续偏好已写入[素材库约定](../../../../AGENTS.md)：先复用已确认部件，泪水优先用泪花，A/B 在常用模型预览尺寸下必须有可见区别。

`before.json`、`before-manifest.json`、`before-overview.png`、`before-report.json` 保留第二轮同形稿及当时记录。`approvals-applied.json` 记录本轮保存范围；候选配方与图集继续位于上级 `presets/`、`textures/`。

项目根目录重建差分与对照：

```powershell
python standards/yukkuri-library/tools/build_reused_expression_review.py drafts/expression-review/reuse-batch-51-62
```

[当前模型页](../../next-batch.html)提供 A/B 切换；[正面并排总览](../../../../previews/current/pending-batch.png)与[斜侧面总览](../../../../previews/current/pending-batch-oblique.png)来自本轮实际模型。Minecraft 运行资源未改动。
