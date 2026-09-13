# 家油面部浮动褶皱修订

2026-09-13。用户指出家油走路时面部出现浮动褶皱。同姿态对照确认：身体分段扭转后，客户端为每个三角面独立计算法线，原本连续的前壁因此出现移动的横条与斜纹。上一轮眼口无遮挡的结果不能证明面部光照自然。

本轮只改绘制。家油 `house_body_surface` 中静态位置和原始法线相同的顶点共享光照法线：位置按 0.00001 模型单位、法线分量按 0.001 精度归组，把蒙皮后各相邻三角面的叉积相加，再归一化。叉积保留面积权重；不同原始法线仍分组，保留身体的硬转角。五官、头发与饰物沿用原绘制，身体顶点、UV、绑定、步态和搬运曲线不改。两份模型仍为 `62d853e5…` 与 `5acd0279…`。

游戏实现位于 [YukkuriModel.java](../../../../src/main/java/io/github/longshou/mcyukkuri/client/YukkuriModel.java)。伤口替换表面本来使用同类分组，保留凹陷自身的立体明暗。独立预览的 [render.js](../../../../standards/yukkuri-library/runtime/render.js) 共享同一分组规则，并插值各顶点亮度；三角面剔除仍按几何朝向判断。两个现用动作采样器都已同步，模型及标准造型的确认记录不变。

[正面对照](previews/current/surface-front.png) · [斜侧对照](previews/current/surface-angle.png) · [最新离线行走动图](previews/current/surface-waddle.gif) · [全部 20 种形态](../../../../standards/yukkuri-library/body-standard/previews/current/all-models/index.html?character=house_reimu)

可重跑 `python art/source/standard-runtime/face-motion/surface-preview.py`，它使用当前模型与中性表情生成上述预览，并写入[表面检查报告](reports/current/surface-lighting.json)。两种家油的待机、行走、持物行走、蠕动、侧弯与伸展各取 25 个相位，共 300 个姿态；位置和 UV 零变化，共组顶点的法线跳变归零，单位法线检查通过。其他 18 种形态的采样结果逐数相同。

两个真实浏览器入口的六个定向画面已加载并目视复核，面部横条和斜纹消失，见[浏览器记录](reports/current/surface-browser.json)。当前 NoCull 规则下另复查 72 个普通／持物行走眼口样本，无意外遮挡，见[本轮快速复核](reports/current/quick-check.json)。

新固定构建的实机定向复核通过：两种家油共 14 组、321 个实际采集帧，逐组核看两张接触表的 56 个相位样本。普通行走、慢走、携物及三种表情的采集范围见[实机报告](../../../../docs/validation/house-surface/current/index.html)；浮动横条与斜纹消失，原轮廓、五官和夹点正常。已独立核对 56 张原帧指纹、JAR 及其中的两份模型指纹。固定 JAR 为 `30102b8c…`，相对上一轮只有 `YukkuriModel` 及其内部类变化。此结论限于该固定产物的定向范围。

[查看实机修前后动图](../../../../docs/validation/house-surface/current/comparison.gif)
