# 灵梦 · 八景 U 方块模型

参考本机《ゆっくり八景U》`eightSight.v8.3` 原始美术，以用户已选定的 `../marisa-body/` 魔理沙基础模型为同系列起点。77 方块、924 三角形、8 个部件组，128×128 自绘像素贴图，底面 Y=0。

- [旋转预览](preview.html)：离线可用，支持正面、侧面、背面、拖动、缩放和自动旋转。
- [Blockbench 模型](reimu.bbmodel) / [geometry 导出](reimu.geo.json) / [贴图](reimu.png)。Blockbench 文件内嵌贴图。
- [三视图](preview-sheet.png) / [与魔理沙同尺度对比](comparison.png) / [360° 转台](turntable.gif)。
- [Java 1.21–1.21.1 独立物品展示包](reimu-java-1.21.1-resourcepack.zip)，配套目录为 `resourcepack/`。沿用既有展示包格式，纸物品 CMD 为 98003。展示包使用 `paper.json` override；与其他修改同一文件的包同时启用时需合并该文件。

四个身体方块的坐标、尺寸和 UV 与魔理沙逐项一致；眼睛、腮红、微笑、下巴贴图保留，仅把眉色改为适合深发的棕色。刘海改为中央长束，头发采用偏暖的近黑色和克制的亮色纹理。两侧发束有红色套饰及白色上下边，后方红结有阶梯外扩的结翼、白色褶边、中央结和两条垂带。

部件为 `head`、`hair`、`bangs`、`side_lock_left`、`side_lock_right`、`bow_left`、`bow_right`、`bow_knot`，均有独立枢轴，方便后续制作饰物动画。本次交付为静态模型，转台仅旋转观察相机；未移植魔理沙体操或表情工坊。

## 参考依据与适配

[参考记录](reference/README.md) / [原始图层核读图](reference/reference-sheet.png)。核对了深发的中央尖束、两侧红白绑饰和后置红结白褶边。原作普通脸更大眼、张口；这里按现有魔理沙的已选定神态做同系列适配。方块阶梯、三维厚度、背面布局与枢轴均为制作选择，不声称是原作三维模型。

参考提取图只放在本地 `reference/`，未嵌入模型、贴图或发行展示包。原游戏文件、存档、既有魔理沙及旧灵梦留档均未修改。

## 验证

`verify.py` 已通过身体基准一致性、面部像素保留、UUID 与分组完整性、三个导出几何/UV 一致性、坐标与贴图边界、内嵌/外部/包内贴图一致性、ZIP 内容与 CRC 检查。结果为 [verification.json](verification.json)。

`browser-check.cjs` 已在本机 Chrome 实测四个视角、拖动、复位、滚轮、自动旋转、390×844 手机尺寸，无页面脚本错误。结果为 [browser-verification.json](browser-verification.json)，截图为 `browser-*.png`。已查看三视图、同尺度比较、侧面/背面和手机预览，检查结体衔接、后发覆盖、面部可见性及贴地位置。

尚未在 Blockbench 应用中导入或进行 Minecraft 实机验证，也未接入生物实体。geometry 文件需要相应渲染接入，不能直接作为 Java 实体模型使用。

## 重生成

在项目根目录运行（Python 需 NumPy/Pillow，浏览器验证使用本地现有 Playwright 与 Chrome）：

```powershell
python variants/reimu-hakkei/generate.py
python variants/reimu-hakkei/make-previews.py
python variants/reimu-hakkei/verify.py
node variants/reimu-hakkei/browser-check.cjs
```

`generate.py` 是独立构建脚本，不执行其他角色的生成器；`make-previews.py` 仅读取已有魔理沙模型用于比较。
