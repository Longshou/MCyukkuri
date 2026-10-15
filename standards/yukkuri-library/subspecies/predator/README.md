# 捕食种 · 蕾米莉亚与弗兰

本轮游戏选用模型为 `assembled/remilia/` 与 `assembled/flandre/`，制作入口是 [species.json](species.json)、[author_species.py](author_species.py) 与 [species_geometry.py](species_geometry.py)。模型、骨架、贴图和动作同源导出；两种造型均待用户确认。

[当前交互预览](../../../../art/source/predator-runtime/preview.html) · [本轮参考核读](REFERENCES-RUNTIME.md) · [游戏实现](../../../../docs/PREDATOR_IMPLEMENTATION.md)

本轮使用连续身体网格、蓝发／金发、软帽、后置／侧置蝴蝶结；蕾米莉亚使用完整蝙蝠翼膜，弗兰每翼挂四枚彩色晶体。每侧整翼围绕一个翅根摆动，晶体另有悬挂关节。图集为 256×256，含 11 个基础表情与咬取表情；保留 19 个通用动作并增加飞行、俯冲、降落、咬取四段动作。运行导出为 `scripts/export_predator_assets.py`，不重导现有七种角色。

2026-09-13 尖牙返工：此前低分辨率嘴区中的牙齿形状不清楚，现为嘴部使用 112×96 像素独立绘图区，在暗色口腔内绘制成对白色三角尖牙、细轮廓与浅阴影。普通表情改为小开口笑，咬取表情也使用同一套牙齿绘制。参数见 `species.json` 的 `fangs`；嘴部正面 UV 移至 `[128,0,240,96]`，三维位置、法线、骨架、权重与动作保持不变。已同步标准模型内嵌贴图、配套图集、游戏选用快照及 12 张运行表情。当前两种共 1,150 个动作采样及 24 次浏览器表情切换通过；该指纹版本的普通、开心、咬取表情已通过正面与斜前方实机复验。此修订仍待用户确认。

## 旧稿保留说明

下面的 `predator_remilia`、`recipes.json`、旧转台及验证报告属于此前原型，不是本轮游戏选用的模型；原型的“尚未接入”描述仅适用于该旧稿。当前选用与指纹以 [species-manifest.json](species-manifest.json) 和项目运行来源登记为准。

[三维预览](assembled/predator_remilia/index.html) · [参考核读](REFERENCES.md)

在标准素材库的魔理沙基座上，按用户提供的三张参考图重做的捕食种（蕾米莉亚系）：一对**小型下垂蝙蝠翼**贴在体侧，粉红软帽配帽后红色荷叶边蝴蝶结，淡蓝紫发。**状态为待确认设计稿**，尚未接入 Minecraft 实体、物品或行为逻辑。

本次是按参考图的返工版。上一版在没有参考图的情况下凭「飞行、翅膀、尖牙」推测制作，用户判定「根本不像」；返工依据与新旧差异见 [返工计划](RENOVATION-PLAN.md)，参考图逐项核读见 [REFERENCES.md](REFERENCES.md)。

**尺寸修正记录**：返工中途我一度把翼做成 34 模型单位翼展、且错误上扬，用户指出「翅膀哪有这么大」。重新实测参考图 R2：体宽约 150px、单翼自肩至尖端约 100px、翼高约 55px，即**翼长约体宽的 0.67 倍**。现在的翼展到达比为 **0.49**（`verify.cjs` 实测），比参考略保守，断言把它锁在 0.45～0.95 之间，防止再次失控。

## 怎么转着看

`render-turntable.cjs` 在本机生成单文件转台页（`previews/current/turntable.html`，不入库）：在图上按住拖动就能任意转视角（左右转方位、上下调俯仰），另有方位滑杆、正面／右侧／背面／左侧按钮、双击复位和自转开关，并可切换「静止／振翅中／张口露牙」三个状态。文件自包含，用浏览器打开即可，不需要联网或本地服务。

它预先渲染了 24 个方位 × 4 个俯仰 × 3 个状态共 288 帧，用素材库自身的 `runtime/render.js` 对导出后的几何、骨架与图集渲染。**这是绕开本机浏览器限制的做法**：Node 的 `spawn` 与 Chrome 的调试端口握手都被沙箱拒绝，起不了无头浏览器，所以没有直接驱动交互式 `index.html`。转台页看到的是真实导出几何，但交互逻辑是页面自己写的，不等于 `index.html` 那个查看器的行为。

不想开浏览器时，`verify-turntable.cjs` 另在本机写出离线拼图：方位一圈（3 个俯仰 × 6 个方位）、俯仰变化、静止与振翅对照。

## 交付内容

| 内容 | 文件 |
| --- | --- |
| 制作参数（唯一造型来源） | [recipes.json](recipes.json) |
| 制作脚本 | [build.py](build.py) |
| 结构检查 | [verify.cjs](verify.cjs) |
| 转台页生成与检查 | [render-turntable.cjs](render-turntable.cjs)、[verify-turntable.cjs](verify-turntable.cjs) |
| 离线多视角渲染 | [render-views.cjs](render-views.cjs) |
| 可编辑模型 | [predator_remilia.bbmodel](assembled/predator_remilia/predator_remilia.bbmodel) |
| 几何导出 | [predator_remilia.geo.json](assembled/predator_remilia/predator_remilia.geo.json) |
| 动作导出 | [predator_remilia.animation.json](assembled/predator_remilia/predator_remilia.animation.json) |
| 骨架与动作源 | [animation-rig.json](assembled/predator_remilia/animation-rig.json) |
| 贴图（256×256 图集，106 张表情） | `assembled/predator_remilia/textures/` |
| 尺寸标定 | [physical-scale.json](physical-scale.json) |

## 本稿做了什么

- **翼**：**小型下垂三角翼**，翼长约体宽的 0.6 倍。轮廓由 `outline` 显式给出（翼根→前缘→指骨尖→后缘凹口→回翼根，x 单调递增），翼膜是对该多边形的扫描线填充，因此凹口是真实的后缘波浪而非贴图暗示：三点外突、两点缩回。另在每根指骨位置叠一根抬高的指骨条（各自挂在 `wing_a`…`wing_c` 骨上），指骨是**真实几何**。整片只做 yaw 后掠、不做面板旋转，所以不会有斜置长方体铺不满的裂缝。要调翼形直接改 `outline` 那串点即可。
- **翼面贴图**：因长条是竖向而面板取样区是宽扁矩形，翼面按**斜向**绘制大块膜面与指骨列，避免竖色带被斜切后读成格纹；前缘另有一条亮带。
- **翼有两态**：默认按参考图 R2 做成上扬的展开警戒姿态；另加 `wing_fold` 动作，把每侧翼链绕 Z 轴下收 52°，得到 R3 那种贴身的休息姿态（模型不动，纯动作）。转台页的「收翼」按钮可以直接对比两态。
- **软帽**：基座女巫帽的 12 个方块全部移除，换成坐得高、压得扁的软帽（横宽 15、高约 1.2），否则红帽带会盖住额头读成一条横贯脸的红带。
- **帽形取椭圆且分三层收顶**：参考图和八景 U 官方的软帽都是**圆盘**，而方块风基座搭出来的是方盒。为此帽身、帽带、帽檐都改用椭圆分段——两侧主板取椭圆在 `x=±rx*corner` 处的矩形，四角补上该处椭圆边缘的薄块，俯视轮廓因此接近椭圆（主板宽 9.30、角块宽 7.82，不再是等宽方盒）。帽顶再叠三层逐级收窄的环（收窄 20%／40%／62%），把直筒壁过渡成圆顶。`verify.cjs` 有两条检查专门断言「角块明显窄于主板」与「圆顶逐级收窄」，防止以后改回方盒或平顶。
- **帽檐荷叶边**：帽檐外缘按 8 个 tab 沿椭圆法线交替向外凸出，凸起高度向外缘递减，形成参考图 R1 的波浪轮廓。
- **蝴蝶结**：基座蝴蝶结压扁下移到帽身上；原先挂在帽顶上方，正面看像两条悬空红条。
- **配色**：只替换发型专属 UV 区块（`0,48..48,64`）内的金色像素共 512 个，取色依据参考图 R1/R2/R3 的淡蓝紫发。
- **五官放大（本亚种专用）**：八景 U 官方正视里眼睛很大、嘴在脸下方且不与眼重叠；方块风基座把五官都塞在约 0.6 模型单位高的一条里，读起来既小又挤。本亚种把十件共用五官重挂到更大的 UV 区块（60..114 区）并放大几何——眼睛宽 4.8 模型单位（原约 3.75）、嘴下移并与眼分开、腮红加大。**只改本亚种的图集与模型**：共用组件未动，105 张表情仍按新 UV 逐张重贴，因此表情全部保留。
  - 期间踩到一个坑：躯干 `soft_head_slice` 的前表面在 z=-4.83，而脸部元件自己的 z 是 -4.60。把五官放在 -4.75 时整块被躯干挡住、渲染里完全看不见（单件隔离渲染却正常）。正确位置是 -4.88..-4.78，即真正在躯干表面之前。
- **图集扩容**：基础 128 图集原样放在 256×256 图集的左上角，新部件只用右侧边距。导出与运行都按声明的 `texture_size` 换算，游戏侧读取的是烘焙后的 0..1 UV。

## 与基座的关系

| 项目 | 数值 |
| --- | --- |
| 基座 | `assembled/marisa`（项目自绘标准素材） |
| 方块数 | 266（基座 218 − 移除女巫帽 12 + 软帽 32 + 翼 28） |
| 骨骼数 | 208（基座 202 + 每侧 3 根翼骨） |
| 图集 | 256×256（基座 128×128） |
| 表情 | 105 张（沿用基座，未新增） |
| 保留动作 | `idle`、`waddle`、`turn`、`puff`、`startle`、`hop`、`brace` 与解剖动作 |
| 新增动作 | `wing_sway`（振翅，1.2 秒循环，每侧各翼骨带相位差）、`wing_fold`（收翼，1 秒） |

**本稿不做獠牙**：参考图 R1／R3 都看不到尖牙，R2 张口时口内可见但无法判定有突出的尖牙，因此不把獠牙当作该种特征。若后续确认有，再按图补。

未适配：`wing_sway` 之外的体操与繁殖动作没有按新体型重新标定，本轮不声明这些动作可用。

## 实际尺寸

16 模型单位 = 1 米。普通成体主体宽约 0.24 米；本稿横向放大 1.06 倍，主体宽约 0.254 米，仍是篮球级体型。翼展不计入主体尺寸。数据见 [physical-scale.json](physical-scale.json)。

## 重建与检查

```powershell
python standards/yukkuri-library/build.py            # 基座变更后先重建标准库
python standards/yukkuri-library/subspecies/predator/build.py
node standards/yukkuri-library/subspecies/predator/verify.cjs
node standards/yukkuri-library/subspecies/predator/render-views.cjs
node standards/yukkuri-library/subspecies/predator/render-views.cjs marisa idle 0 --asset ../../assembled/marisa
node standards/yukkuri-library/subspecies/predator/render-turntable.cjs
node standards/yukkuri-library/subspecies/predator/verify-turntable.cjs
```

倒数第三条用同一相机与画幅渲染未改动的基座魔理沙，本机输出（`previews/current/`，不入库）中的 `marisa-*` 与 `predator_remilia-*` 可以直接对照体型、发色、头饰与翼的差异。传 `--asset` 时不会输出翼或张口相关的额外视图。最后两条生成并检查转台页；`verify-turntable.cjs` 会重渲哨兵角度，断言转动确实改变画面（而不只是页面看起来能转），并顺手写出三张离线拼图。

本机浏览器不可用（Node 的 `spawn` 与 Chrome 的调试端口握手都被沙箱拒绝），因此 `render-views.cjs` 直接用素材库的 `runtime/motion.js` 与 `runtime/render.js` 对**导出后的** `geo.json`、`animation-rig.json` 与表情贴图做离线渲染，不经过浏览器。这条路径能核对几何、挂接与骨架，但不能替代用户在浏览器里看 [index.html](assembled/predator_remilia/index.html) 的交互结果。

## 检查范围

```powershell
node standards/yukkuri-library/subspecies/predator/verify.cjs
```

2026-09-12（UTC）在 Python 3.13.1 + Pillow 12.3.0、Node v24.21.0 环境下执行。25 项结构检查全部通过。
**已覆盖**：三份导出（bbmodel／geo／animation）的元素与骨骼计数一致、uuid 唯一、几何尺寸与 bbmodel 逐块对应；女巫帽 12 个方块确实移除；图集实际尺寸与声明的 `texture_size` 一致；翼根落在侧腹范围内、翼体在躯干前方；獠牙数量与配方一致（本稿为 0）；12 个动作各取 5 个时间点求解均为有限值；运行时的「每元素 36 顶点」假设成立、重建顶点缓冲区长度与方块数相符、动作轨道没有指向不存在的骨骼；`wing_sway` 确实让翼尖产生可测位移（0.3 秒与 0.9 秒两个相位之间 Y 向最大位移大于 0.15 模型单位）。

**未覆盖**：造型好不好看、翼形是否像参考图、脸的比例是否合适——这些只能由用户判断；未在浏览器中打开 `assembled/predator_remilia/index.html` 验证那个查看器的交互、控制器与缓存表现（本机浏览器被沙箱拒绝），只能靠转台页代替；未接入 Minecraft 实体，未做实机渲染；未在 Blockbench 中人工打开 `.bbmodel` 复核。

转台页另有 11 项检查：方位转动、俯仰变化、振翅状态都确实改变渲染像素；页面内嵌图集＋静帧数量与状态数相符（状态数从模型推导，不写死）；带指针拖动处理器；**内嵌 PNG 的 IHDR 完整**（早先版本漏写位深与颜色类型，Node 能解码但 Chrome 会拒绝整张图，页面会全白）；**页面自身脚本能解析**（语法错误同样只会得到一片空白），且拖动能请求到的帧坐标全都落在内嵌图集范围内。

## 本轮之后仍待处理

1. **帽子的方块感**：帽子俯视已收成椭圆、上沿也加了一层收窄的圆顶（`cap_dome`），但仍是分段直壁，官方是更连续的圆顶。再做需要更多层切角。
2. **收翼动作是纯旋转**：`wing_fold` 把整片翼绕翼根下收，翼尖会扫过体侧；若要像 R3 那样严丝合缝贴身，需要给收拢态单独做一版几何。

## 确认状态

造型、配色与翼形均为待确认设计稿，`provenance/` 未登记本稿。**本轮没有查到任何原作或社区配图**，参考缺口见 [REFERENCES.md](REFERENCES.md) 第二节。用户确认前不改动批准指纹。

后续接入复验已完成：接入任务检查了两豆油与两捕食种的实际 Minecraft 四方向及尖牙表情；用户造型确认仍为待确认。
