# 魔理沙 · 排嗯嗯动画

[交互预览](preview.html) · [动画 GIF](poop.gif) · [细节图](details.png) · [魔理沙模型](marisa.bbmodel) · [独立馅料模型](filling.bbmodel)

基于 `marisa-expressions` 的魔理沙体型、独立后臀与分层表情，新增一段 6.4 秒动画 `animation.marisa.poop`。本目录是独立动作版本，先前鼓腮、体操等版本仍在各自目录。

| 时间 | 动作 |
| --- | --- |
| 0–0.5 秒 | 正常坐姿、准备 |
| 0.5–1.55 秒 | 身体压低，向前倾约 7°，后臀抬起；挤眼、圆嘴张开 |
| 1.55–3.55 秒 | 三次用力，嘴巴随力度张合，后臀轻颤，馅料从 X 标记中心逐渐挤出 |
| 3.55–4.13 秒 | 排出物脱离角色，独立下落 |
| 4.13–5.4 秒 | 落地轻微压缩，魔理沙放松、眨眼 |
| 5.4–6.4 秒 | 恢复原姿态，排出物留在地面 |

预览默认播放一次，结束保留落地结果。可重新播放、切换循环、拖动进度和旋转视角。正面便于看表情，侧面／侧后方便于检查排出与落地。循环开启时，下一轮会重置排出物。

导出包含 238 个方块、161 个骨骼，表情切换、身体动作和排出物都使用骨骼关键帧。打开 `marisa.bbmodel` 后进入动画模式，选择 `animation.marisa.poop`。`marisa.geo.json`、`marisa.animation.json`、`marisa.png` 是配套几何、动画和贴图；`animation-rig.json` 保存中立模型坐标的动作定义。

本轮调整：嘴巴参考八景用力脸，改为有深红口腔和粉红下缘的圆张嘴；后臀增加简单 X 标记，绑定后臀骨骼。馅料改为深紫褐色的连续水滴状团块，收细连接端、鼓起末端，带少量像素颗粒。45 个相交方块组成完整立体形体，落地时轻微压扁。预览的“馅料特写”可单独旋转查看，点击播放／重新播放即可回到角色。

`filling.bbmodel`、`filling.geo.json`、`filling.png` 是另存的独立馅料资产：底面 Y=0，1 个骨骼、45 个方块，不依赖角色模型。见 [馅料三视角](filling-preview.png)。

排出物在静止编辑姿态收在体内，动画起始进一步缩小；它挂在独立场景骨骼下，角色恢复时不会把已经落地的物体拉回去。接入实体时可在 3.55 秒将这段展示骨骼替换为独立物体生成事件。本次未实现 Minecraft 实体注册、行为逻辑、物理碰撞或游戏运行验证。

验证结果：浏览器播放／暂停、重播、拖动进度、单次结束、循环、馅料特写、视角拖动和手机布局通过；641 个采样检查模型有限值、单位法线、地面及角色恢复，角色最低点 Y=0，排出物落地最低点 Y=0.0264。另核对用力阶段馅料连接端与 X 中心衔接，误差小于 0.06 模型单位。真实 Blockbench 网页版核对 41 个姿态、全部方块与官方 Bedrock 动画导出，最大包围盒误差约 0.000065 模型单位。

参考依据与本次适配选择见 [reference/README.md](reference/README.md)。模型与贴图沿用项目自绘资产，没有把提取的原作图层嵌入交付模型。

在项目根目录复现：

```powershell
python variants/marisa-poop/generate.py
node variants/marisa-poop/render-check.cjs
python variants/marisa-poop/finish-previews.py
node variants/marisa-poop/browser-check.cjs
node variants/marisa-poop/verify-blockbench.cjs
python variants/marisa-poop/verify-assets.py
```

生成依赖同项目的 `marisa-expressions` 模型／骨骼与 `marisa-puff` 的共享运动和渲染函数。生成后的 HTML 已内嵌模型、贴图和脚本，可离线打开。
