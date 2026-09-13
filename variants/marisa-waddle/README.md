# 魔理沙 · 摇摆前进动作适配版

从用户选定的 `../marisa-body/` 基础版完整复制，在本目录完成动作骨骼调整。基础版模型与其他版本保持原样。

## 可用文件

- [动态预览](preview.html)：播放／暂停步态、拖动动作进度、原地／前进演示、静止造型、旋转视角。
- [动作 GIF](waddle.gif) / [四阶段动作图](motion-sheet.png)。
- [Blockbench 模型（内嵌动画）](marisa.bbmodel)。在动画模式选择 `animation.marisa.waddle`。
- [配套骨骼几何](marisa.geo.json) / [动画 JSON](marisa.animation.json) / [贴图](marisa.png)。
- [动作定义](waddle-rig.json)：中立模型坐标系的骨架、关键帧和推荐步幅，供后续 Java 实体渲染接入。
- `resourcepack/` 与 ZIP 保留静态物品展示功能；原版 Java 物品资源包不会播放本骨骼动画。

## 建模调整与动作

仍为 53 方块、636 三角形。静止几何、UV、表情和贴图与基础版逐项一致，变更集中在可编辑分组、枢轴与动作关键帧。保持完整馒头体，未加回下巴层。

新增地面补偿、身体左右扭动、身体侧倾、后发跟随、帽子跟随骨骼，共 11 个骨骼。后发下段和两侧发梢归到 rear_hair；帽子和蝴蝶结共同继承 hat_follow。

- `body_sway`：枢轴 Z=-3，靠近前下方；左右扭动 ±8°，让后半部摆动大于面部前端。
- `body_roll`：左右侧倾 ±3.5°，底面两侧交替支撑。
- `ground_contact`：随侧倾补偿高度，让支撑边保持 Y=0。
- `rear_hair`：±3° 延迟摆动；`hat_follow`：±1.8° 小幅延迟侧摆。
- 1.2 秒完成完整左右循环，65 个线性关键帧采样；首尾相接。
- 前进演示沿 -Z 方向运动，步幅每周期 3.2 模型单位，速度随半步略有起伏。为方便反复观看，走完 2.5 周期回到起点；该空间复位是演示循环，不是步态接缝。

完整身体仍是方块刚性骨骼摆动，没有软体蒙皮挤压。这一版解决摇摆前进所需的分组与枢轴；幅度与节奏是本次原创设计，不能称作已逐帧复刻《油库里日记》。参考记录见 [reference-notes.md](reference-notes.md)。

## 实际验证

- `verify.py`：静态模型数量、UV、图集与内嵌贴图、Java 展示导出、ZIP 与离线页面一致性通过。
- `verify-motion.cjs`：静止几何与贴图与基础版相同、骨骼关系与动画引用有效；完整周期 241 个采样底面误差小于 0.001 单位，所有顶点有限、法线单位化、循环首尾一致。见 `motion-verification.json`。
- `browser-check.cjs`：真实 Chrome 测试左右姿势、播放／暂停、前进演示、静止造型、拖动、复位、缩放及手机尺寸，未出现页面脚本错误。见 `browser-verification.json`。
- `check-blockbench.cjs`：真实 Blockbench 网页版成功导入 53 方块、11 分组及 1.2 秒循环动画；进入动画模式播放到 0.3 秒。`verify-blockbench-pose.cjs` 将 53 方块的世界包围盒与本地预览逐一比较，最大误差约 0.00000077 单位。见 `blockbench-verification.json`、`blockbench-import.png`。
- 已目视核读正面／背面动作截图、手机尺寸预览和 Blockbench 导入截图。

尚未在 Minecraft 客户端验证。要让 MC 生物实际摇摆前进，仍须接入实体动画播放器、移动速度与动作相位、碰撞及行为；当前交付不是已可游玩的实体 Mod。`marisa.animation.json` 与 geometry 成对导出，但没有完整 Bedrock 行为包或 Java 模组适配。

## 重生成

从项目根目录执行：

```powershell
python variants/marisa-waddle/generate.py
python variants/marisa-waddle/verify.py
node variants/marisa-waddle/verify-motion.cjs
node variants/marisa-waddle/browser-check.cjs
node variants/marisa-waddle/render-motion.cjs
python variants/marisa-waddle/make-motion-preview.py
node variants/marisa-waddle/check-blockbench.cjs
node variants/marisa-waddle/verify-blockbench-pose.cjs
```

Blockbench 检查需要访问其网页版；普通预览完全离线。只将模型、贴图与原创动画用作后续游戏素材；参考缓存不应纳入游戏资源包。
