# 魔理沙：八景伸长与甩臀动作

[交互预览](preview.html) · [伸长动图](stretch.gif) · [阶段对比](stretch-sheet.png) · [Blockbench 模型](marisa.bbmodel)

在上一版 `marisa-rump` 上新增“伸长—保持—缩回”，共 57 方块、17 骨骼。用户选定的静止基础 `marisa-body` 保留。

身体皮肤绕底面向上伸展至 1.75 倍；脸、头发和帽子整体上移 6 模型单位，五官保持原比例。包含帽子的总高度从 18.25 增至 24.25 单位。底面保持 Y=0。新增独立面部薄片，使眼嘴不随身体拉长。

## 播放与文件

预览默认播放一次伸长，结束后回到静止；点击“伸长”重播，也可拖动时间轴。切换“甩臀”播放上一版的循环动作，并可开启前进演示。

- `animation.marisa.stretch`：2.8 秒、单次。0–0.3 秒准备，0.3–1 秒伸长，1–1.65 秒保持，1.65–2.4 秒缩回，余下恢复静止。
- `animation.marisa.waddle`：1.2 秒循环，保留上一版的甩臀曲线。
- `marisa.bbmodel`：可编辑模型，嵌入贴图及两个动画。
- `marisa.geo.json`、`marisa.animation.json`、`marisa.png`：几何、动画和贴图导出。
- `animation-rig.json`：离线预览所用骨骼与动作曲线。

动作设计依据见[本地八景参考](reference/README.md)。1.75 倍幅度及 2.8 秒节奏是本次 MC 方块模型的设计参数，并非原作精确测量。

## 验证与适用范围

真实 Chrome 检查了动作切换、单次停止、恢复静止、甩臀前进演示和手机宽度显示，无页面错误。真实 Blockbench 网页版导入后，对两个动画各检查 41 个时刻、所有方块的世界坐标边界，与预览误差低于 0.000001 单位；官方动画导出关键帧与交付 JSON 一致。

另检查 281 个伸长姿态的贴地、脸部尺寸及身体衔接；61 个甩臀姿态与上一版的几何和法线完全一致。贴图字节保持不变，资源包 ZIP 内容和 CRC 检查通过。详细结果在 `*-verification.json`。

模型和动画已可在 Blockbench 使用，尚未接入 MC 实体或进行游戏实测。附带的 Java 1.21–1.21.1 物品展示资源包仅展示静态模型，不能播放这两个骨骼动画；动态效果仍需实体渲染与动作触发接入。

## 重建

在仓库根目录执行：

```powershell
python variants/marisa-stretch/generate.py
node variants/marisa-stretch/render-stretch.cjs
python variants/marisa-stretch/make-previews.py
```

Python 需要 NumPy 和 Pillow。生成器读取上一版 `marisa-rump/waddle-rig.json`，预览本身可离线使用。
