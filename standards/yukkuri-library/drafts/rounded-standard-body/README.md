# 灵梦与魔理沙 · 捕食种身体移植试稿

2026-09-13：本页展示版本已由用户确认，并推广到[正式圆润标准体型](../../body-standard/README.md)。本目录冻结保留批准证据；下文“试稿／待确认”描述属于确认前上下文。请勿用此旧构建器读取推广后的模型重复变换。当前预览进入[正式包](../../body-standard/previews/current/index.html)。

2026-09-13。按用户要求，将当前蕾米莉亚／弗兰共用的连续身体移植给灵梦与魔理沙，制作多角度与动作预览。造型待确认，未替换标准库已确认角色或 Minecraft 运行资源。

[交互预览](previews/current/index.html) · [静态总览](previews/current/overview.png) · [新旧四视图](previews/current/comparison.png) · [动作姿态](previews/current/actions.png)

页面可选择两种新身体，或同一角色的新旧同步对照；支持旋转、缩放、暂停、时间拖动、表情切换和仅显示身体。可直接打开本地 HTML，不需要服务或网络。

## 造型与动作

身体直接采用 `subspecies/predator/species.json` 的 `body_profile` 和 `species_geometry.py` 的身体生成函数，保留其下巴收圆、侧腹与后部连续轮廓。主体尺寸为宽 12.8、高 9.6、深 10.73 模型单位；16 单位为一格。肤色沿用两种标准模型的贴图色，不改眼睛像素或发色。

原有分层身体、后部和鼓腮薄片从本试稿移除；原有方块式发型与饰物保留，按挂接比例适配。五官划分细网格，贴合新身体前表面，并与身体共用高度方向双骨权重，防止侧弯时五官脱离皮肤。伸展时五官会随曲面一起伸长，实际效果已展示在动作图中。

| 动作 | 本稿处理与预览 |
| --- | --- |
| 静止 | 两种身体及新旧对照 |
| 走路 | 复用当前步态节奏，将旧独立后部摆动改为连续身体的高度分层摆动；[动图](previews/current/waddle.gif) |
| 跳跃 | 复用原有短周期起落；[动图](previews/current/hop.gif) |
| 伸展 | 按新比例复用身体伸展与饰物随动；[动图](previews/current/stretch.gif) |
| 侧弯 | 保留分段弯曲，连续身体与贴面五官同步；[动图](previews/current/side_bend.gif) |
| 受惊 | 复用原有受惊起落 |

基础动作包含上述六段，另有四段独立伸缩动作与下面的胎生流程。进食、擦脸、携物、鼓腮、茎生、器具与伤口等未纳入本稿验收，也未因本稿改动游戏接入。跳跃 GIF 以 13 帧、每帧 20 毫秒近似原 0.262 秒周期，其余 GIF 受 10 毫秒时间精度限制；交互页直接按原动作时长运行。

## 油菊与伸缩部件

首轮身体替换遗漏了旧后部贴图中的油菊，同时没有适配伸缩部件。现已在这份试稿中补齐：[部件近景](previews/current/anatomy.png)。

- 油菊复用标准图集右下角的原图案，只提取色素像素，贴面网格沿新身体后部曲面铺设；与身体使用同一高度分层绑定，常态显示。
- 佩尼佩尼保留标准渐细形状及 25 度抬起角，按用户本轮要求整体下移 0.30 模型单位，位于脸部下方。
- 麻姆麻姆移到同一前下方位置。挂点由佩尼佩尼伸出后的轴线与皮肤交点推导，避免误用体内支点作为外部位置。底座按前下腹曲率分段，常态内收 0.55 单位，朝前伸出 0.60 单位，最外表面在伸出终点高于皮肤 0.05 单位。
- 两个部位使用共用 `AnatomyController`，默认均收回。伸出后保持，可与走路等身体动作叠加；身体动作暂停时仍能伸缩。切换部位先完整收回，再伸出新部位；切换查看对象、复位及重新打开页面会恢复收回状态。

四段 `anatomy_mamu_extend/retract`、`anatomy_peni_extend/retract` 已同步导出。各部件通过跟随身体分层的独立驱动骨混合绑定，不再沿用旧后部的固定挂点；按钮控制与导出动作读取同一行程配置。制作配置见 `recipe.json`，派生控制配置见 [anatomy.json](anatomy.json)。

## 胎生适配

[胎生阶段图](previews/current/birth.png) · [完整动图](previews/current/birth.gif)。交互页点击“胎生全过程”，可选 1—5 胎，暂停或拖动时间；流程结束后再次播放会从头开始。32 秒是制作演示时长。

复用既有胎生原型的孕体变化、阵痛、依次出生和恢复时序，按本轮圆润身体调整下腹变形范围、幼体身高及出口。麻姆麻姆和胎生共用前下方皮肤挂点；胎生期间清除独立伸出状态并锁定选择，只由产程控制出口，佩尼佩尼不会同时出现。退出胎生、复位及重开页面恢复常态。

出口从本轮母体三角面中实际裁出，边缘沿当前曲面生成。幼体采用同角色本轮圆润模型，尺寸为成体的 0.18，经过出口时局部收缩；仅绘制位于皮肤外侧的部分，离体后恢复形状并落地。落地间距按角色完整外形宽度计算，包含魔理沙帽檐。孕体五官和饰物随身体一起变形，幼体出体前使用闭眼表情。

孕体五官贴面按身体实际截面高度重新分段，再投到变形后的皮肤前方，保留 0.035 单位间隙。上一版直接对不同疏密的身体与五官网格施加同一非线性变形，足月时两角色各有 36 个五官顶点沉入皮肤，导致眼睛出现横向断带；此前的有限数值和流程检查未发现这处遮挡。当前已修正，实际浏览器同一孕体阶段见 [修正前](reports/current/birth-face-before.png) 与 [修正后](reports/current/birth-face-after.png)。常态及产后恢复继续保留原有显示。

配置随模型导出到 [灵梦 birth.json](assembled/reimu/birth.json) 和 [魔理沙 birth.json](assembled/marisa/birth.json)，并写入各自 `runtime.model.json`。曲面裁切、幼体与母体同场绘制由 [birth.js](previews/current/birth.js) 执行；这部分是程序化流程，未作为单个骨骼动作写入 `.animation.json` 或 `.bbmodel`。既有原型只作为时序与算法来源，本稿的挂点变化和魔理沙适配仍待用户确认。

## 制作源与导出

`recipe.json` 与 `build.py` 是本稿制作入口，读取已登记的标准模型、当前动作骨架和捕食种身体函数。`components/`、`rigs/` 保存本轮生成的可编辑组件与绑定源快照，`assembled/<角色>/` 同步导出模型、几何、动画、全部表情图集和预览使用的运行格式 JSON。手工调整快照后须同步制作入口再导出。

- [灵梦可编辑模型](assembled/reimu/reimu.bbmodel)
- [魔理沙可编辑模型](assembled/marisa/marisa.bbmodel)
- [输入与输出指纹](manifest.json)

`.bbmodel` 提供静态几何和骨骼轨道，双骨混合权重保存在配套 `animation-rig.json` 与 `runtime.model.json`。本页按该权重实际变形；普通 Blockbench 单组绑定不能完整复现这个混合蒙皮，未声称在 Blockbench 中完成动画验收。

```powershell
python standards/yukkuri-library/drafts/rounded-standard-body/build.py
python standards/yukkuri-library/drafts/rounded-standard-body/render_preview.py --animations --review --anatomy --birth --birth-animation
node standards/yukkuri-library/drafts/rounded-standard-body/verify.cjs
node standards/yukkuri-library/drafts/rounded-standard-body/verify_anatomy.cjs
node standards/yukkuri-library/drafts/rounded-standard-body/verify_birth.cjs
node standards/yukkuri-library/drafts/rounded-standard-body/verify_browser.cjs
```

制作复用本机 Python、Pillow、Node，以及 `tools/browser-check` 中的 Playwright 和本机 Chrome。原始帧与临时探针位于 `.local/rounded-standard-body/`；没有启动游戏或长驻预览服务。

## 本轮检查

- [结构报告](reports/current/structure.json)：两角色的顶点、部件与骨骼权重一致；旧身体已移除；十段动作各采样 25 个时点，合计 500 个姿态，数值有限，动态动作均有实际变化。
- [部件检查](reports/current/anatomy.json)：172 项检查通过。其中 144 组真实渲染在六段基础动作、三个相位与四个角度下，强制绘制收回部件与移除部件的像素逐点一致，证明它们实际藏在身体内；另覆盖伸出可见、离地、侧弯叠加、油菊显示及控制器互斥与回收。
- [胎生检查](reports/current/birth.json)：526 项检查通过。覆盖两角色的共用皮肤挂点、1／3／5 胎的 19 个采样时点、数值有效性、出生顺序、幼体身份与离地、落地间距、接触采样、产后恢复和可重复拖动。新增孕体贴面间隙采样与 36 组正面／斜侧面像素对比，五官单独绘制与加入身体后的可见像素一致。初始胎生场景与普通静态模型逐像素一致。
- [浏览器报告](reports/current/browser.json)：44 项检查通过。实际打开交付页面，覆盖基础动作、表情、视角与部件控制，以及胎生时互斥锁定、孕体变化、时间拖动、胎数切换、结束重播与退出复位；报告记录页面及数据指纹。
- 本任务自行查看当前正、斜、侧、背视图，四段动作的四个相位，以及走路、伸展和侧弯的侧背视图。检查图位于 `reports/current/`，当前所看姿态未见身体裂缝、丢失五官或饰物脱离。保留原发型和饰物的方块轮廓，整体圆润程度仍由用户查看决定。
- 胎生另查看当前阶段图及 [正侧背检查图](reports/current/birth-views.png)，核对出口、幼体出体与落地；幼体采用本轮模型缩放，未重新制作年龄专属造型。
- Minecraft 实机、未列出的动作、幼体成长与其他亚种未验收；本稿造型仍待用户确认。
