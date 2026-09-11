当前统一复用库已迁至 [yukkuri-library](../yukkuri-library/README.md)。本目录保留亚种／生命周期等现有模块依赖的兼容数据；新表情、基础动作与复合配方统一在新库登记。

以下内容描述旧版原型及其历史检查结果，不证明当前 Minecraft 接入通过验收。构建和验证命令仅供指定环境使用；本机执行边界以项目 [AGENTS.md](../../AGENTS.md) 为准。预览由用户打开，Agent 不执行浏览器验证。

# 油库里可复用标准 v1

已确认：[灵梦茎生 v2 与胎生 v1 复合动作](actions/README.md)，含独立源文件快照与哈希清单，并登记到[统一素材库](../yukkuri-library/index.html#composite)。[灵梦胎生预览](viviparous/index.html) · [漫画参考与生产位置](references/viviparous-comics.md)。胎生按用户确认采用正面嘴巴下方出口，生殖行为的后侧位置另行保留。运行 `python build_viviparous.py` 重建胎生预览。

新增：[螺壳、水生、被炉魔理沙](subspecies/index.html) · [模型与参考说明](subspecies/README.md)。三种形态继承普通魔理沙组件；螺壳为倾斜半包结构，附缩入与探出预览。

标准魔理沙同时具有麻姆麻姆和佩尼佩尼，两者常态隐藏，同一时刻只显现一种。麻姆麻姆在后侧油菊下方，佩尼佩尼在正面嘴巴下方，沿用八景 U 的细长收尖轮廓。用「显现部位」选择，配合「查看脸下」或「查看油菊下方」检查；按住「按住接触」渐显，松开恢复。切换时先完整收回当前部件，再显现另一种。开启「接触模式」也可直接按住角色测试；关闭后可拖动旋转。键盘支持空格或回车。

接触反应独立于动作播放，暂停时仍可检查，使用标准成年魔理沙的身体骨架。显现时间 0.24 秒、恢复时间 0.65 秒，参数位于 `behaviors/contact-reveal.json`；造型位于 `components/marisa/contact-anatomy.json`。麻姆麻姆跟随后侧皮肤切片，佩尼佩尼跟随 `face_mouth_skin_anchor`；两者支持伸长、体操和逃跑。原作证据与本次适配见 [位置参考](references/contact-position.md)。

可编辑模型与几何中，部件绑定位置在身体内部，直接打开也是隐藏状态。导出 `animation.marisa.contact_peni` 和 `animation.marisa.contact_mamu` 两条非循环姿态曲线（0–1 秒），每条都显式让另一种保持隐藏；替代旧的同时显现曲线。实体控制器接入时应维持互斥状态，切换先归零，不能同时混合两条显现动作。网页已经实现该状态机，目前尚未连接 Minecraft 的碰撞、实体或联机逻辑。检查结果见 `contact-verification.json`。

以本次魔理沙确认的 B 加高体型、空心圆受惊眼和独立辫子为基准。灵梦沿用同一份身体与油臀，保留自己的黑发、红白饰物与动作。灵梦是据此完成的版本，等待后续观感反馈。

## 预览与成品

新增：[成长体型对照与灵梦茎生](lifecycle/index.html) · [范围、过程与复用说明](LIFECYCLE.md)。赤体 0.12–0.30 倍、子体 0.30–0.80 倍、成体 0.80–1.20 倍，以标准成体为 1；比例为项目适配。茎生采用八景 U 的单根弯茎与细线悬挂，每胎 1–5 只，未出生时闭眼沉睡，依次脱落后苏醒。可自由旋转、调整体型、指定或随机数量及保存预览。运行 `python build_lifecycle.py` 重建；仍属模型与行为预览。

打开 `index.html` 选择魔理沙或灵梦。播放、暂停时均可拖动旋转、滚轮缩放；“抬左辫 / 抬右辫”可检查独立活动。

`assembled/marisa/` 与 `assembled/reimu/` 各含可打开的 `.bbmodel`、Bedrock 几何和动画 JSON、贴图、运行时骨架及独立 HTML 预览。成品只是组装结果，下列源数据可分别复用。

## 可分别复用的数据

| 目录 | 内容 |
| --- | --- |
| `profiles/body.json` | 体型参数、单位和坐标轴 |
| `components/common/body.json` | 两角色共用的身体几何 |
| `components/common/rump.json` | 共用油臀几何，含油菊的 UV |
| `components/角色/` | 左辫、右辫、头发、饰物、脸部、噗咕组件 |
| `skeletons/角色.json` | 骨架、父子关系、挂接点 |
| `parts/角色/` | 单个几何零件及索引 |
| `expressions/角色/` | 各表情的独立透明五官 PNG、UV 与几何索引 |
| `textures/角色/` | 与该角色 UV 配套的完整贴图 |
| `animations/角色/` | 每个动作独立一份 JSON |
| `behaviors/` | 受惊逃跑阶段参数、噗咕对抗参数和原型逻辑 |
| `characters/` | 角色装配清单，引用组件、骨架、动作、贴图 |
| `runtime/` | 预览动画、渲染和逃跑实现 |

魔理沙包含 13 套表情贴图；灵梦包含普通与空心圆受惊贴图，用力表情保留在自己的图集与动作中。噗咕需要同时使用鼓脸组件和动作，不能只换一张贴图。两个角色五官 UV 不同，跨角色复用表情时按索引重新排布。

## 搬到其他项目

复制整个 `yukkuri-v1` 文件夹，在其中运行：

```sh
python assemble.py
python validate.py
```

需要 Python 与 Pillow（`python -m pip install Pillow`），不读取旧的 variants 目录。也可仅取所需组件，再一并带走它引用的骨架父节点、贴图和动作轨道。

组件 `from/to` 为模型绑定姿态坐标；16 单位为一格，前方 -Z，上方 +Y。组件的 `bone` 对应骨架名称，UV 使用该角色 128×128 图集。动画源文件是预览模型空间，组装器负责导出坐标转换；迁移时不要重复转换。

左右辫子分别挂在 `hand_left` 和 `hand_right`，是可编辑、可独立旋转的手部组件，尖端和饰带可以是子组件；不会并入身体蒙皮切片。动作中允许少量穿模。

## 本版保留的约束

- 身体纵深增加 2.275，整体高度增加 6%；油臀随身体后移，未通过加大油臀替代身体加长。
- 奔跑油臀深度缩放固定为 1，左右摆动最多 6°。
- 受惊眼使用空心圆，无瞳孔。
- 脸部透明五官跟随相应高度的身体切片，伸长时前部深度固定，减少薄片与底部堆叠。

动作和行为目前是模型及预览数据；接入 Minecraft 实体仍需项目自己的实体定义、控制器和 AI。`verification.json` 为浏览器检查，`structural-verification.json` 为组件检查。`source-provenance.json` 记录源版本。

## 本次修正

前方辫子与后侧发帘拆成不同几何，后侧发帘挂在 hair，不随 hand_left/right 抬起。预览默认同一静止姿态；compare.html 可同步比较身高，也可只显示身体。五组共用动作的采样身体高度差小于 0.00001 单位（取整误差），静止高度均为 8.48。帽子与蝴蝶结不计入身体高度。

## 共用动作与表情（本次解耦）

`animations/common/press.json` 和 `taiso.json` 是两个角色共同引用的体操主体；`bindings/角色/动作.json` 保存骨架映射和该角色五官、饰物的附加轨道。组装时解析绑定，生成各自的动画，模型组件仍各自独立。

`expressions/common/surprise.json` 与同名目录中的三个透明 PNG 是共用受惊表情，直接取自本次魔理沙确认的空心眼和张嘴。`bindings/角色/surprise.json` 只指定五官目标及 UV 位置；组装器生成各自图集和表情几何覆盖，不再单独画灵梦的嘴。导出的 `角色.surprise.png` 配合 `角色.surprise.geometry-overrides.json` 使用。

预览可独立选择表情，全部八项动作均可双角色同步观看。基础 bbmodel 为普通表情；切换表情时须应用相应图集与几何覆盖。
