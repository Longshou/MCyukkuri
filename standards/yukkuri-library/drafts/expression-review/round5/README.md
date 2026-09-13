# 表情第五轮

本目录保留第五轮来源；用户在第六轮反馈中已确认 N020 完整展示版，并认可 N033 的样式，要求改回棕灰。当前入口见[第六轮说明](../round6/README.md)。下文为第五轮工作记录。

[当前比较页](../batch-16-40.html)只保留 N020 与 N033。N024/N025 已按用户选择保存第四轮蓄泪与滑落差分，其完整配方及部件指纹在 `expressions/component-review/n016-n040-decisions.json` 的 `confirmed_versions` 中登记。

N020 采用第四轮 B 的嘴、眉和鼓脸构图，闭眼线直接引用用户此前选定的 `E3-PUFF`，与 O10、N014/N015 的部件像素完全相同。左右眼线并非严格镜像，因此分别按下缘适配泪花；跳过上方孤立的眼线端点，避免生成悬空亮点。所选 B 构图与闭眼组件已登记，适配后的整体效果仍待用户查看。

N033 三款虹膜中段由约 7 像素加宽到 9 像素，保持两侧可见眼白；B 版暗瞳孔中段由 3 像素加宽到 5 像素。A/B/C 仍是待选候选，切换时左栏同步显示同款加宽前，避免跨款比较。

## 制作与复现

`candidates.json` 保存本轮展示配方；`parts/` 保存 N033 候选像素。N020 已按选定方向写回共用制作源并同步导出，但新的泪花适配不冒充用户已经确认的完整版本。

在素材库目录运行：

```sh
python tools/review_expression_round5.py
python tools/sync_expression_exports.py puff_teary tongue_cry tongue_cry_b
python tools/build_expression_round5_review.py
python validate.py
```

制作脚本应用本轮选择，后续轮次不要用它覆盖新选择。同步覆盖标准库六个角色的指定表情图集与内嵌预览，不重建身体、骨架和动作，不更新 Minecraft 快照。

基线是 `expressions/component-review/before-n016-n040-round5.json`；N020 左栏取第四轮 B 候选，N033 左栏分别取第四轮 A/B/C。原先闭眼线的确认依据为 `expressions/component-review/selection-applied.json` 中 N014/N015 的 `E3-PUFF` 选择，以及 `accepted-components.json` 对应像素。

N020 的第五轮右栏版本已确认，见[适配对比](../../../previews/current/n020-variants.png)。N033 预览已被第六轮替代；检查见[历史第五轮报告](../../../archive/expression-review-round5-report.json)。
