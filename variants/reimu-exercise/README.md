# 灵梦 · 嗯嗯体操

[播放整套体操](preview.html) · [完整动图](taiso.gif) · [动作阶段图](pose-sheet.png) · [含动画的 Blockbench 模型](reimu.bbmodel)

在 `../reimu-hakkei/` 的灵梦基础模型上增加骨骼动画。当前保留独立后臀、露出的油臀标记与减轻台阶的前下腹；笑眼按用户要求恢复为原来的小闭眼样式。侧弯的脸部去掉整块肤色薄片，改为透明底的五官与腮红，各自跟随对应高度的身体。当前为 254 方块、155 骨骼、5 段动画。

[本轮脸部衔接对比](face-integration-comparison.png) · [后臀三姿态](rump-sheet.png)。对比采用同尺度、同角度、同动作时刻。本轮修改前保存在 `before-face-integration/`；更早的体操初版保存在 `before-fixes/`。`refinement-comparison.png` 是此前扩大笑眼那一轮的留档。

| 动画 | 时长 | 行为 |
|---|---:|---|
| `animation.reimu.taiso` | 14 秒，循环 | 转向抬臀、换侧用力、上下伸缩、伸高后左右侧弯、回正休息 |
| `animation.reimu.press` | 4.8 秒，循环 | 两侧转向、压低抬臀、用力表情与轻微摆动、回正 |
| `animation.reimu.exercise_vertical` | 2.62 秒，循环 | 沿用魔理沙已完成的上下伸缩曲线 |
| `animation.reimu.exercise_sides` | 2.36 秒，循环 | 沿用用户选定的魔理沙半速左右侧弯曲线 |
| `animation.reimu.stretch` | 2.8 秒，单次 | 沿用魔理沙单次伸长与恢复曲线 |

整套默认自动播放，也可单独切换各段、暂停拖动进度、查看静止造型、旋转与缩放。完整体操的 14 秒顺序是本次演示编排，不是原作歌曲逐帧对时。五段均写入 `.bbmodel` 与动画 JSON，表情切换也是骨骼轨道，不依赖网页临时切换图片。

## 复用与适配

只读复用了 `../marisa-exercise/` 的前身／后臀分体几何、伸缩、侧弯、单次伸长曲线，以及甩臀曲线。输入快照保存在 `reuse/`，来源与 SHA-256 见 `build-info.json`。臀部独立摆动按源幅度的 65% 混入用力阶段。拉长时前身的深度缩放保持为 1，宽度收窄幅度减半，避免下腹露出重复的水平小台面；其余伸高高度、弯曲和时长沿用源曲线。

身体与后臀使用对应部位的分段骨骼，侧发复用同高度的前身骨骼，大红结跟随上部，两侧红白套饰以较低枢轴跟随。脸部拆成左右眼、左右腮红和嘴巴五个透明底色块，仅保留五官色素，各自复用对应高度的位移和旋转，不施加分段拉伸，保留眼嘴比例。色块距实际身体前表面仅 0.003 单位，用于避免共面闪烁，没有整块肤色面板。预览和导出贴图均使用 alpha 透明。默认脸、恢复的小笑眼和配色像素保留，后臀标记继续随臀部变形。

转向抬臀根据八景 U 的普通侧向、体操 1、体操 2 图层和 `うんうんたいそう顔()` 的动作阶段制作；转向角度、前部支点、三维厚度、平滑衔接和饰物跟随幅度属于本次适配。[原始参考核读记录](reference/README.md)。

## 文件与验证

- [几何 JSON](reimu.geo.json) / [动画 JSON](reimu.animation.json) / [贴图](reimu.png)。Blockbench 文件含贴图及全部动画。
- [上下伸缩动图](exercise_vertical.gif) / [左右侧弯动图](exercise_sides.gif)，与完整动图均按 50 帧/秒输出。
- `verify.py` 检查五官色块能逐像素重组为原表情、肤色底板完全透明、身体几何与输入快照一致、饰物与配色、纹理嵌入、骨骼引用及导出一致性，结果为 `verification.json`。
- `verify-motion.cjs` 逐段按 50 帧/秒检查姿态有限值、地面、循环闭合、相邻皮肤层间隙、后臀包围体衔接，并核对复用曲线及五官贴合。所有可见五官距身体前表面最大约 0.00306 单位；结果为 `motion-verification.json`。
- `browser-check.cjs` 实测五段动作、时间轴、循环持续、按真实时间播放、静止、拖动缩放及手机布局。结果为 `browser-verification.json`。
- `verify-blockbench.cjs` 在实际 Blockbench 网页编辑器中导入交付文件，对每段 31 个姿态逐方块比较世界坐标包围盒，并核对官方 Bedrock 动画编译结果。结果为 `blockbench-verification.json`。

尚未接入 Minecraft 实体或进行 MC 实机验证。原静态物品展示包仍在基础模型目录；物品展示包不会播放这些骨骼动画。

## 重建

从项目根目录运行（现有 Python NumPy/Pillow、Node、Playwright/Chrome 环境）：

```powershell
python variants/reimu-exercise/generate.py
python variants/reimu-exercise/verify.py
node variants/reimu-exercise/verify-motion.cjs
node variants/reimu-exercise/browser-check.cjs
node variants/reimu-exercise/verify-blockbench.cjs
python variants/reimu-exercise/make-previews.py
python variants/reimu-exercise/face-integration-previews.py
```

动图由实际预览使用的同一动画求值器与光栅渲染器生成，以内存流传给 Pillow，不产生逐帧原始图片文件。原作 Sprite 仅位于参考目录，未打包进交付模型。
