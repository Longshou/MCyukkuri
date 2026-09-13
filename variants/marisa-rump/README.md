# 魔理沙 · 八景甩臀动作版

本版针对“上一版没有屁股甩甩的感觉”调整。保留 `../marisa-body/` 作为用户选定的静止建模基准，`../marisa-waddle/` 作为上一轮动作留档；本目录是最新动作候选。

## 看效果与使用文件

- [前后动态对比](before-after.gif)：同背面视角、同尺度，左为上一版，右为本版。
- [交互预览](preview.html)：默认背面便于看甩臀；可切正面、侧面，播放／暂停、拖动动作进度、切换前进演示。
- [正背面动作 GIF](waddle.gif) / [四阶段动作图](motion-sheet.png) / [静止三视图](preview-sheet.png)。
- [带动画 Blockbench 模型](marisa.bbmodel)：动画模式选择 `animation.marisa.waddle`。
- [骨骼几何](marisa.geo.json) / [配套动画](marisa.animation.json) / [贴图](marisa.png)。
- [中立坐标系动作定义](waddle-rig.json)，供后续实体动画接入。
- `resourcepack/` 与 `marisa-java-1.21.1-resourcepack.zip` 是本版静止形体的 Java 1.21–1.21.1 物品展示包，不能仅靠该包播放骨骼动画。

## 改了什么

上一版整团身体一起偏转，后半部被头发遮住。本版把后半部做成独立的形体与骨骼，让脸朝前而屁股甩出去再收回。

- 53 → 56 方块，12 个骨骼。原头部拆为前部与上后部，增加两个相互重叠的后臀方块；接合位于侧后发下方。正面底面仍是 Y=0，未加回前下巴层。
- 脸部表情、完整贴图、刘海、帽子和蝴蝶结的静止几何不变；修改脸部方块后方深度、两颊后缘，为独立臀部留出活动空间。
- 缩短后发下缘（保留上部轮廓），让裸露的后臀轮廓能被看到。
- 屁股独立横移 ±1.1 单位并偏转 ±16°，加上小幅侧倾与 6.5% 以内压缩。末端总左右位移约 4.94 单位；脸部左右位移为 0。
- 上半部只有 1.2% 以内的轻微竖向压缩；帽子只做 ±0.45° 跟随，不再作为主要摆动部件。
- 1.2 秒一循环，61 个采样关键帧，50 Hz 编辑时间格；原地骨骼动画与前进位移分开。前进演示走完 2.5 周期会回到起点以便重复观看。

这是八景动作分层与形变方向的 MC 方块化改编。既有八景普通移动有多种方式，专门甩臀与普通移动不是同一套动作；本版把“上部相对稳、下后部变形与偏摆”的特点用于用户要求的甩臀前进，没有宣称逐帧复制原游戏的常规移动。参考见 [reference/README.md](reference/README.md)。

## 实际验证

- `verify-motion.cjs`：241 个时刻检查连接与地面，后臀核心始终与前身体相交、后臀端部始终与核心相交；前身体底面恒为 Y=0，后臀最低点约 Y=0.04，没有穿地。所有顶点有限、法线单位化、循环首尾一致。还检查贴图及帽子／刘海／蝴蝶结几何保持不变、导出数量一致。记录 `motion-verification.json`。
- `verify-blockbench-all.cjs`：真实 Blockbench 网页版导入后，65 个动作时刻、全部 56 方块世界包围盒与预览逐一比对，最大误差约 0.00000077 单位；全部动画关键帧值与 Blockbench 官方 Bedrock 导出器匹配。记录 `blockbench-verification.json`。
- `browser-check.cjs`：真实 Chrome 检查左右姿势、播放／暂停、前进演示、静止造型、拖动、复位、缩放与手机尺寸，无页面脚本错误。记录 `browser-verification.json`。
- `finish-previews.py`：贴图内嵌／外部／资源包字节一致、ZIP CRC 与全部包内文件一致。记录 `asset-verification.json`。
- 已实际查看本轮正面／背面阶段图、前后对比、浏览器截图与八景分层参考图。

未做 MC 客户端验证，也未接入实体移动、AI、碰撞或动画控制器。动画和模型资产已具备；不是已经可玩、会走路的实体 Mod。

## 重生成

在项目根执行：

```powershell
python variants/marisa-rump/generate.py
node variants/marisa-rump/verify-motion.cjs
node variants/marisa-rump/browser-check.cjs
node variants/marisa-rump/verify-blockbench-all.cjs
node variants/marisa-rump/render-motion.cjs
python variants/marisa-rump/make-motion-preview.py
python variants/marisa-rump/finish-previews.py
```

最后一个脚本只读上一版的渲染中间帧以制作前后对比。Blockbench 验证需联网，普通预览完全离线。`reference/` 仅用于本地核读，未纳入游戏资源包。游戏、用户存档、原始基准、其他建模任务未被修改。
