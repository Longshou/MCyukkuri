# 油库里美术素材库

2026-09-13：[圆润标准体型](body-standard/README.md)已保存，覆盖普通五种、螺壳种、多毛种、灵胖及本已符合的两种捕食种；家油、DOS、不足油保留原型。[打开本轮预览](body-standard/previews/current/index.html)。

人类 NPC 首批制作登记见 [humans.json](characters/humans.json)，包含鬼威惨、爱派大哥哥、大姐姐及两套职业服装；[配套导出](assembled/humans/README.md)已接入，造型待确认。

本目录是标准美术制作与复用入口，当前实体接入使用的选定快照另见 [art](../../art/README.md)。目录职责与修改规则见 [AGENTS.md](AGENTS.md)。

| 入口 | 内容 |
| --- | --- |
| [全身伤口标准](ecology/body-wounds/README.md) | 已确认的 R3.1 游戏适配：四类伤情、多处挂接、局部变形和漏馅；七种角色的选定源、导出和实机预览已冻结 |
| [素材登记](index.html) | 角色、共用组件、动作和表情 |
| [89 项标准表情](standard-expressions.html) · [标准说明](STANDARD-EXPRESSIONS.md) | 当前已确认造型与指纹；72、77、78 暂缓 |
| [标准帕秋莉模型](assembled/patchouli/patchouli.bbmodel) · [独立预览](assembled/patchouli/index.html) | 已确认的五撮宽厚刘海、完整包脸侧发、贴地小束尾与浅粉新月软帽 |
| [标准种族对照](species.html) · [造型与来源](STANDARD-SPECIES.md) | 橙、爱丽丝、帕秋莉的专属造型与共用边界 |
| [螺壳魔理沙](TSUMURI.md) | 已确认的居中壳形与互斥伸缩控制 |
| [普通魔理沙七项互动](interactions/marisa-braids/README.md) | 拾石、挥剑、拔草、摘帽、护理帽子、挥辫道别、托子油；[批准记录](interactions/marisa-braids/saved-action.json)与[快照索引](interactions/marisa-braids/approved-snapshots.json) |
| [家油场景](ecology/house-yukkuri/README.md) | 两种微小成体、棉絮巢、卵、破壳幼体与生活片段；未接入 Minecraft |
| [多毛灵梦、灵胖、DOS](subspecies/reimu-dos/README.md) | 派生造型与[来源说明](subspecies/reimu-dos/REFERENCES.md) |
| [当前预览](previews/README.md) · [待确认草稿](drafts/README.md) · [历史归档](archive/README.md) | 区分当前展示、候选方案与旧记录 |

帕秋莉的标准入口为 `characters/patchouli.json`，确认记录与指纹位于 `provenance/standard-species.json`。其他角色及资产包按各自登记判断状态；目录名不代表全部素材已经确认。`compare.html` 专用于普通魔理沙、灵梦双角色比较。

## 当前登记

- 9 个基础动作、6 个骨架复合动作配方、2 个已确认的多角色复合动作包。
- 105 个表情配方：89 项已确认并保存为[标准表情](STANDARD-EXPRESSIONS.md)，13 项仅作历史对照，3 项暂缓；另有 2 个独立叠加效果。本次标准确认以[集合与指纹](provenance/standard-expressions.json)为准。
- 八种角色通过 `body-standard/recipe.json` 生成同一身体截面与贴合五官，保留各自专属部件；旧共用方块组件保留供历史输入及排除种类使用。
- 辫子、后发与饰物分别建模；左右辫子可独立活动。
- 两角色共用麻姆麻姆、佩尼佩尼及伸缩控制；默认缩回，通过按钮伸出或缩回，切换时保持互斥。见 [标准伸缩部件](ANATOMY.md)。

魔理沙原先已有 happy 素材，但未完整接入共用登记与选择器。本库已统一提供睁眼笑、闭眼大笑、闭口微笑、含泪笑等表情。

## 动作复用

| 复合动作 | 引用与组成 |
| --- | --- |
| 伸长演示 stretch | 伸长形态 stretch_shape ＋ 演示节拍 |
| 上下体操 exercise_vertical | 同一伸长形态 ＋ 八景 U 上下体操节拍 |
| 左右体操 exercise_sides | 最大伸长形态 ＋ 左右弯曲增量 side_bend |
| 左右用力体操 press | 用力下压 brace ＋ 转身 turn（左右各一次、32°）＋ 走动 waddle 的油臀摆动 |
| 整套体操 taiso | press 4.80 秒 → exercise_vertical 2.62 秒 → 伸长过渡 0.58 秒 → exercise_sides ×2 共 4.72 秒 → 恢复 0.68 秒 → 静止 0.60 秒 |
| 受惊逃跑 flee | 受惊弹起 startle 0.60 秒 → 转身 turn 0.40 秒 → waddle ×1.8 倍速 ＋ hop 跑动跳跃 |

用力体操与单独摆臀有区别：额外转身、身体俯身 12°和下压。它复用摆臀周期，不再单独存一份摆臀轨道。“整套体操”原来重复烘焙了已有动作，现改为引用配方。

`motions/base/` 是基础数据；`motions/composite/` 只含引用、时间、权重与组合规则，禁止加入整套烘焙轨道。`motion_library.py` 解析依赖并检查循环；修改基础动作后，引用它的复合动作会重新生成。

已确认的灵梦茎生 v2、胎生 v1 复合动作登记于 [motions/procedural/index.json](motions/procedural/index.json)，同时显示在本库复合动作区域。它们协调母体和多个子体，使用独立程序状态；不塞进单骨架关键帧编译器。动作包自包含源文件、依赖与离线预览；茎生解压后打开 `lifecycle/index.html`，重建用 `build_lifecycle.py`；胎生打开 `viviparous/index.html`，重建用 `build_viviparous.py`。运行 `python build_procedural_catalog.py` 验证包内来源哈希并更新登记卡片；不读取旧标准目录。

茎生 v2 保存了有厚度且比主茎细的头顶连接茎，同时记录：母体大小影响子油数量，数量影响茎长度和粗细，并存在个体差异。这些关系的具体函数与分布尚未实现，不能把当前演示抽数与容量布局当作完整生理规则。

左右弯曲是最大伸长姿态上的增量，前置形态在 requires 中注明。动作预览会显示这个前置形态。

## 表情与动作解耦

`expressions/presets/` 的每个配方引用眼睛、眉、嘴、腮红、眼泪和附加图层；相同像素按内容散列存放在 `expressions/parts/`，不按角色重复画。`expressions/effects/` 保存湿润高光和横向眼泪，可叠加到任何表情。

角色的 `bindings/` 只记录 UV 装配位置。角色通过共用五官组件复用表情配方；鼓脸表情另外声明所需鼓脸组件和局部五官偏移。已保留之前确认的普通脸、空心圆受惊脸及鼓脸眼型。

A/B 泪帧共用眼睛与嘴，只换眼泪部件。方向图、动作姿态图复用对应表情，由相机、身体动作表现方向。相同视觉配方保留别名，不再建立第二份素材。

`presentation/expressions.json` 只是“随动作建议”的独立时间表。手动选择表情后即可覆盖建议，动作数据不携带五官图片。

原有与新增均登记在选择器及图鉴，O 编号为原有，N 编号为新增；完整编号见 [表情清单](expressions/review-list.md)。13 个原有表情已恢复，包括笑脸、愤怒、哭泣、睡觉、困倦等；鼓脸保留已确认的专用眼嘴与偏移。89 项已保存为标准，3 项暂缓，13 项仅作历史对照；来源映射本身不代表确认。后续重绘以已有表情为画风和五官位置基准，不得用旧批量脚本覆盖已确认部件。

2026-09-12 已选定 N032、N039、N040 新版、N037 原版、N026 第三轮加强泪花版、N024/N025 第四轮差分，以及 N020 第五轮完整展示版；[批次登记](expressions/component-review/n016-n040-decisions.json) 保存所选配方和部件指纹。当前待审队列已清空，N072/N077/N078 暂缓；复用批次十二项均已保存，记录见[本批确认登记](expressions/component-review/next-batch-decisions.json)。[已保存垂眼哭丧可爱版](drafts/expression-review/reuse-batch/round4/README.md)沿用八景 U 的嘆き顔情绪依据，按用户偏好改为圆润大瞳孔和简洁眼线，N041/N042 第四轮可爱版已确认保存。N051–N062 全批已保存，包括已确认泪花 A/B 差分。N063–N074 第二轮展示的 11 项已全部保存，见[批准登记](expressions/component-review/batch-63-74-decisions.json)。最后一轮九项已确认，见[登记](expressions/component-review/final-batch-decisions.json)；[表情核对收尾](drafts/expression-review/next-batch.html)记录三项暂缓用途；[89 项标准表情预览](standard-expressions.html)可继续查看。本批次九项修订已全部确认。N033 棕灰版已保存，A 为默认，B/C 为同编号备选，登记见[备选配方](expressions/variants/dull/index.json)。[当前比较页](drafts/expression-review/batch-16-40.html) 保留三款已确认棕灰版的对照。候选源与重建方式见 [第六轮说明](drafts/expression-review/round6/README.md)。

## 原作核对

本机八景 U v8.3 的资源与当前 DLL 对齐：解析 `level0` 中对象 76216 的 1352 个 Sprite 数组，消费字节数 224788 与对象长度完全一致；外部文件号 2 核实为 sharedassets0.assets。修正了旧反编译字段名称在后段错位的问题。

`provenance/expression-coverage.json` 登记了 237 个表情／眼部效果字段，其中 221 个有素材、16 个原版数组为空；220 个不同的参考 Sprite 均已提取核读，全部非空字段都能追溯到本库表情或效果层。

当前像素适配沿用此前普通脸使用的数组槽位 0。常用表情代码按“表情族偏移 ＋ 成长标记”取图，成长标记 0 与 1 是不同阶段；原版所有数组槽位已记录，但本库不为生长阶段重复创建整套模型。方向和生长阶段的绘画差异并非逐像素复刻。原核读图不包含在素材库中；新临时参考资料放项目内 `.local/reference/`，不进入正式贴图和导出包。

## 搬到其他项目与导出

本项目内保持本目录为制作入口。需要复现完整导出时，使用已有 Python 与 Pillow 环境运行以下命令：

```sh
python build.py
python body-standard/render_preview.py
python body-standard/verify_sources.py
```

当前构建读取本库的冻结专属输入、配方，以及项目内既有动作预览支持代码；完整来源和验证命令见 [圆润标准体型](body-standard/README.md)。`assembled/` 按角色保存 bbmodel、几何／动画 JSON、各表情贴图和独立 HTML 预览。它是配套导出，不另建重复的制作源；通过 bbmodel 人工编辑时，须把改动同步回组件和制作参数。

坐标：每格 16 单位，+Y 向上，-Z 为正面。当前圆润标准身体宽 12.8、高 9.6、最大纵深 10.73；家油、DOS、不足油保留各自旧比例。骨架适配、表情几何偏移与 UV 均独立登记。

基础 bbmodel 默认普通表情。导入其他引擎时，表情切换使用该角色导出的图集；鼓脸与挤脸类同时应用配方中的 geometry_offsets / cheek_scale 及所需组件。独立叠加效果按绑定 UV 做透明叠加。

## 新增前的检查

1. 在登记页搜索同义名称、原作字段与别名。
2. 能引用现有动作的，新增复合配方；能引用现有五官的，新增表情配方。
3. 只有确实缺少基础形变或像素部件时才新增基础素材。
4. 同步来源、组件及配套导出，查看并交付最新预览，记录检查结果；标准造型仍由用户确认。

库级新检查结果写入 `reports/current/`。旧库级检查输出已清理；当前造型确认以 `provenance/` 中的记录与文件指纹为准，运行验收仍需单独提供证据。
