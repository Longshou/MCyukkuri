新增：[多毛灵梦、灵胖与高椭圆 DOS 三维设计](subspecies/reimu-dos/index.html) · [模型与重建说明](subspecies/reimu-dos/README.md) · [八景U及网络参考](subspecies/reimu-dos/REFERENCES.md)。

新增：[灵梦基底的标准橙、爱丽丝、帕秋莉](species.html) · [造型与参考](STANDARD-SPECIES.md)。三种角色复用当前灵梦身体、五官、骨架及动作，分别提供模型和独立预览。

已保存：[普通魔理沙七项辫子互动 v1](interactions/marisa-braids/saved-action.json) · [独立快照索引](interactions/marisa-braids/approved-snapshots.json)。

新增：[普通魔理沙七项辫子互动](interactions/marisa-braids/index.html) · [复用和导出](interactions/marisa-braids/README.md) · [漫画与小说参考](interactions/marisa-braids/REFERENCES.md)。包括拾石、挥剑、拔草、摘帽、护理帽子、挥辫道别、托子油到帽子上；属于模型与道具预览。

# 表情核对版本

原有与新增均登记在选择器及图鉴，O 编号为原有，N 编号为新增。相同语义可用“切换同名版本”按钮对照；像素一致的版本共用素材。新增状态为待核对，等待用户重绘清单；不覆盖原有版。完整编号见 expressions/review-list.md。

# 表情修订说明

按用户反馈恢复 13 个原有表情，包括笑脸、愤怒、哭泣、睡觉、困倦等；两角色共用原有像素。鼓脸保留此前确认的专用眼嘴与偏移。原有表情不再由新增适配覆盖。

其余新增表情为待重绘草稿（needs-redraw）。原作字段对应只表示已登记参考，不表示绘画质量合格或可直接用于成品。预览与图鉴均显示状态。

后续重绘只针对缺失表情，已有表情作为画风和五官位置基准；不得运行旧批量绘制脚本覆盖它们。

# 油库里美术素材库

新增：[家油魔理沙与灵梦](ecology/house-yukkuri/index.html) · [模型、道具与生命周期复用](ecology/house-yukkuri/README.md) · [Wiki／漫画／小说参考](ecology/house-yukkuri/REFERENCES.md)。两种微小成体、棉絮巢、卵、破壳幼体与生活片段已完成美术预览，未接入 Minecraft。

当前唯一复用入口是本目录。打开 `index.html` 查登记，打开 `compare.html` 同步查看两角色。旧标准与试验版不作为新素材的复制来源。

螺壳魔理沙已保存为标准模型 `marisa_tsumuri`，沿用确认的居中壳形，并接入共用麻姆麻姆、佩尼佩尼及互斥伸缩控制。见 [模型与编辑入口](TSUMURI.md)。

## 当前登记

- 9 个基础动作、6 个骨架复合动作配方、2 个已确认的多角色复合动作包。
- 13 个保留的原有表情、79 个待重绘草稿、2 个独立叠加效果。
- 魔理沙与灵梦引用同一份身体、油臀、脸部和鼓脸几何；3 个完全相同的头发几何也已提取共用。
- 辫子、后发与饰物分别建模；左右辫子可独立活动。
- 两角色共用麻姆麻姆、佩尼佩尼及伸缩控制；默认缩回，通过按钮伸出或缩回，切换时保持互斥。见 [标准伸缩部件](ANATOMY.md)。

魔理沙原先已有 happy 素材，但未完整接入共用登记与选择器。本库已统一提供睁眼笑、闭眼大笑、闭口微笑、含泪笑等表情。

## 动作不重复造轮子

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

角色的 `bindings/` 只记录 UV 装配位置。当前两角色的五官几何相同，所有表情统一使用；鼓脸表情另外声明所需鼓脸组件和局部五官偏移。已保留之前确认的普通脸、空心圆受惊脸及鼓脸眼型。

A/B 泪帧共用眼睛与嘴，只换眼泪部件。方向图、动作姿态图复用对应表情，由相机、身体动作表现方向。相同视觉配方保留别名，不再建立第二份素材。

`presentation/expressions.json` 只是“随动作建议”的独立时间表。手动选择表情后即可覆盖建议，动作数据不携带五官图片。

## 原作核对

本机八景 U v8.3 的资源与当前 DLL 对齐：解析 `level0` 中对象 76216 的 1352 个 Sprite 数组，消费字节数 224788 与对象长度完全一致；外部文件号 2 核实为 sharedassets0.assets。修正了旧反编译字段名称在后段错位的问题。

`provenance/expression-coverage.json` 登记了 237 个表情／眼部效果字段，其中 221 个有素材、16 个原版数组为空；220 个不同的参考 Sprite 均已提取核读，全部非空字段都能追溯到本库表情或效果层。

当前像素适配沿用此前普通脸使用的数组槽位 0。常用表情代码按“表情族偏移 ＋ 成长标记”取图，成长标记 0 与 1 是不同阶段；原版所有数组槽位已记录，但本库不为生长阶段重复创建整套模型。方向和生长阶段的绘画差异并非逐像素复刻。来源图只在项目 reference 目录，发行包中没有原作提取像素。

## 搬到其他项目与导出

复制整个文件夹，安装 Python 与 Pillow 后运行：

```sh
python -m pip install Pillow
python build.py
python validate.py
```

构建只读取本文件夹，不依赖旧 variants 或游戏目录。`assembled/` 是生成物，包含两个角色的 bbmodel、Bedrock 几何／动画 JSON、各表情贴图和独立 HTML 预览。不要编辑生成物后再把它复制回基础素材。

坐标：每格 16 单位，+Y 向上，-Z 为正面。标准身体高 8.48，纵深沿用确认的 B 加长版；奔跑不额外拉长油臀。骨架适配、表情几何偏移与 UV 均独立登记。

基础 bbmodel 默认普通表情。导入其他引擎时，表情切换使用该角色导出的图集；鼓脸与挤脸类同时应用配方中的 geometry_offsets / cheek_scale 及所需组件。独立叠加效果按绑定 UV 做透明叠加。

## 新增前的检查

1. 在登记页搜索同义名称、原作字段与别名。
2. 能引用现有动作的，新增复合配方；能引用现有五官的，新增表情配方。
3. 只有确实缺少基础形变或像素部件时才新增基础素材。
4. 登记来源和复用关系，运行 build.py、validate.py，并在双角色预览中检查正常、极限、恢复与侧面。

`verification.json` 和 `browser-verification.json` 记录组件、表情、动作、身体一致性及视角操作检查。
