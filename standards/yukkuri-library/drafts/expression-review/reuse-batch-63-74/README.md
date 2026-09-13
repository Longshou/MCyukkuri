# N063–N074：按已确认偏好复用

上一批 N051–N062 已全部保存。第二轮展示的 11 项已全部确认保存；N072 保留来源并暂停筛选。当前仍有 12 项未确认，其中 11 项已进入[下一轮](../reuse-final-batch/README.md)。制作遵循[素材库约定](../../../AGENTS.md)：先复用已确认组件，泪水优先使用下眼缘泪花，A/B 必须在实际模型上有可见差别。

| 编号 | 复用方案 |
| --- | --- |
| N063/N064 | N027 白眼、N041 忧虑眉、N055 鼓腮、N051 泪花；分别配 N014 小三角嘴和 N022 哭喊嘴 |
| N065 | 恢复本批原稿短舌嘴及尺寸，保留当前闭眼、潮红和紧眉 |
| N066/N067 | N021 下垂视线、N041 忧虑眉、N031 喘嘴；直接复用 N051/N052 细薄／饱满泪花差分 |
| N068 | O09 闭眼、N038 平紧嘴，保留松眉 |
| N069 | N027 白眼、N041 忧虑眉、N031 喘嘴、N051 泪花 |
| N070/N071 | 按用户要求完整恢复本批原版配方与几何尺寸，保留原版 A/B 差分 |
| N072 | 暂停筛选，保留来源及草稿；三维遮挡由实际物体、姿态与深度处理 |
| N073 | N002 上弯闭眼与松眉、O06 圆嘴，不带入口水，表达满足 |
| N074 | N027 白眼；N041 八字眉纵向放大至 1.25 倍；N039 下撇嘴放大至宽 1.35／高 1.25 倍；N052 饱满泪花重新贴合眼缘 |

`reuse-manifest.json` 登记当前供体、指纹、恢复原版的部件与暂停筛选项。N065 嘴部及 N070/N071 遵循本次明确选择；其他项目继续优先复用。N066/N067 沿用已确认泪花差分，N070/N071 使用原版差分。

`before.json` 为本轮原配方快照，`presets/` 保存本轮制作源，`textures/<角色>/` 为六角色配套图集；已确认的 11 项已同步正式配方与导出，N072 继续保留草稿。[原图核读](REFERENCE.md)说明各情绪与特殊遮挡场景。本轮仅核对静态表情，不声称已制作对应挤压、舔舐或虚弱动作。

以下命令仅供追溯；当前批次已确认，修订脚本会拒绝覆盖：

```powershell
python standards/yukkuri-library/tools/revise_expression_batch_63_74.py
python standards/yukkuri-library/tools/build_reused_expression_review.py
```

[本轮已确认正面](round2/accepted-pending-batch.png) · [本轮已确认斜侧面](round2/accepted-pending-batch-oblique.png) · [确认登记](../../../expressions/component-review/batch-63-74-decisions.json) · [批准前检查](round2/accepted-review-report.json)

第二轮修改前的配方、清单与总览保存在 [round2](round2/README.md)，用于追溯；原准备脚本已加防覆盖检查。
