# 魔理沙：细分收腰伸长候选

[动画预览](preview.html) · [动图](stretch.gif) · [阶段对照](stretch-sheet.png) · [模型](marisa.bbmodel)

根据用户反馈重做伸长轮廓：底部保持宽度，中段收细，向脸下方逐渐放宽。下部 2 模型单位按 1/16 单位细分为 32 层，各层独立伸展与横向收缩。整模 178 方块、144 骨骼，保持 MC 方块表面，可近看分层细节。

最大伸展时身体增加 6 单位，中段正面宽度由 12 收至约 5.78；深度同步收缩。面部裁掉原贴图底部的空白皮肤区，让腰部与下脸正确衔接，眼嘴像素保持原比例。缩回时各段复位。此前被用户选定的静止基础 `marisa-body` 保留；上一版直筒伸长 `marisa-stretch` 留档。

动画为 `animation.marisa.stretch`（2.8 秒单次）与 `animation.marisa.waddle`（1.2 秒循环）。点击伸长重播，可切换甩臀；前进演示用于甩臀。骨骼、贴图与双动画均嵌入 `marisa.bbmodel`，另提供几何与动画 JSON。

## 验证

- 141 个伸长姿态：底面 Y=0，分层垂直衔接误差低于 0.000001 单位，结束回到静止。
- 甩臀的原有脸上五官、帽子和头发等未分层部件运动保持一致，误差为零；身体分层继承原有头部与臀部骨骼动作。
- 真实 Chrome：单次停止、切换动作、时间轴、移动演示与窄屏检查通过。
- 真实 Blockbench：导入 178 方块、144 骨骼与两套动画；各取 41 个姿态，坐标与预览最大差异约 0.0000361 单位。官方导出将极小位移舍入为零，关键帧最大差异 0.0000413，低于本轮 0.0001 容差。

详细结果见 `motion-verification.json`、`browser-verification.json`、`blockbench-verification.json`。

参考来源沿用[八景伸长原始帧与代码记录](../marisa-stretch/reference/README.md)。本次收腰比例为方块模型适配参数。原作资源仅作参考。

尚未接入 MC 实体或进行游戏内验证。附带 Java 1.21–1.21.1 资源包只展示静态模型；骨骼动作仍需实体渲染接入。

## 重建

在仓库根目录执行 `python variants/marisa-stretch-waist/generate.py`、`node variants/marisa-stretch-waist/render-stretch.cjs`、`python variants/marisa-stretch-waist/make-previews.py`。Python 需要 NumPy 和 Pillow；生成器读取 `marisa-rump/waddle-rig.json`。
