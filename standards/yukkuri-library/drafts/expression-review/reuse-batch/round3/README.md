# 垂眼哭丧重绘

用户确认了 N019、N043、N044、N045、N046、N047；本批次其他十项均已按选定版本保存。当前只核对 N041/N042，其他表情共 37 项仍待核对。

[当前对照页](../../next-batch.html)左侧为上次的闭眼稿，右侧为本轮重绘。原图、场景与适配区别见[参考依据](REFERENCE.md)。

`before.json`、`before-manifest.json`、`before-overview.png`、`before-report.json` 是重绘前的依据；`approvals-applied.json` 记录本轮保存的六项。正式确认记录及文件指纹仍在 `expressions/component-review/next-batch-decisions.json`。

`eye-design.json` 保存调色板和像素矩阵，`parts/` 保存配套眼部 PNG。当前完整候选配方在上级 `presets/`，六个角色的配套图集在上级 `textures/`；新眼部没有覆盖已确认的共用组件。

在素材库目录重建当前草稿与对照页：

```sh
python tools/redraw_lament_expressions.py
python tools/build_reused_expression_review.py
```

六项批准已经通过 `tools/accept_reuse_round2.py` 保存，不要重复执行批准脚本。新眼部及眉泪位置应通过当前模型的正面、斜侧面核对；数据与浏览器检查不替代用户对新造型的确认。Minecraft 接入资源未修改。
