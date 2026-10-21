# 当前实体接入素材

本目录保存游戏当前选用的素材快照与配套制作源。标准造型在 [标准素材库](../standards/yukkuri-library/README.md) 制作；选用到游戏时在这里更新快照、运行资源和[来源登记](runtime-sources.json)，标准库更新不会自动替换游戏资源。

集中查看当前 19 种游戏形态、动作、表情与声音，双击项目根目录的 `打开素材检查.cmd`，操作见[素材检查室](../preview/review/README.md)。

## 目录

| 目录 | 内容 |
| --- | --- |
| [source/standard-runtime/](source/standard-runtime/README.md) | 灵梦、魔理沙、橙、爱丽丝、帕秋莉与两种家油的选定快照、适配骨架；[交互查看页](source/standard-runtime/preview.html)读取与打包资源相同的数据 |
| [source/rounded-runtime/](source/rounded-runtime/README.md) | 八只圆润标准导出与多毛灵胖的选用，含配套伤口、可脱落饰物、徽章与身体尺寸 |
| [source/subspecies-runtime/](source/subspecies-runtime/README.md) | 亚种与先天形态的游戏选用 |
| [source/predator-runtime/](source/predator-runtime/README.md) | 蕾米莉亚与弗兰：19 个通用动作、四段行为动作和 12 套表情，[动作查看页](source/predator-runtime/preview.html) |
| [source/placement-fit/](source/placement-fit/README.md) | 19 种形态的头饰与头发位置适配，运行时在动作修订之前拼接 |
| [source/motion-corrections/](source/motion-corrections/README.md) | 鼓腮、头部挂接、侧弯与跳动的动作修订，以及世界观动作 |
| [source/hat-interactions/](source/hat-interactions/README.md) | 五种魔理沙帽子的脱戴与小幼油帽托 |
| [source/ecology/](source/ecology/README.md)、[source/human-runtime/](source/human-runtime/README.md) | 日常生态表情；人类 NPC 的服装、骨架与发型 |
| [props/](props/README.md) | 食盆、器具、玩具、伤口、徽章等独立道具 |
| [structures/human-world/](structures/human-world/README.md) | 商店、工厂的原创布局、机台与商品 |
| [audio/](audio/README.md)、[dialogue/](dialogue/README.md) | AquesTalk 配音与哼唱；八景 U 原版对白整理 |

## 现状

- 2026-10-14 用户保存[魔理沙单侧粗辫](../standards/yukkuri-library/body-standard/features/marisa-single-braid/README.md)造型与动作，已同步到六种游戏魔理沙（普通、螺壳、稀毛、歪脸、豆形、家油）及伤口表面、挂点、位置适配、动作修正、帽子交互、束具和家油夹取点；游戏内效果待复查。
- 头饰与头发位置适配、鼓腮第七版（圆鼓双颊、噘嘴，侧发不穿模）已接入 19 种游戏形态，待用户复查。
- 稀毛与歪脸的灵梦、魔理沙已采用圆润身体，制作源见 [rounded-reimu](../standards/yukkuri-library/subspecies/deficient/rounded-reimu/README.md) 与 [rounded-marisa](../standards/yukkuri-library/subspecies/deficient/rounded-marisa/README.md)。
- [全身伤口适配](props/body-wounds/README.md)为七种实体接入按命中位置保存的多处伤口、局部凹陷、动作挂接与出口漏馅。
- 捕食种、人类 NPC、建筑与对白配音已接入游戏，造型与声音待用户确认。

## 运行资源

选定的 `.bbmodel` 保留原始编辑骨架；九套圆润形态的适配骨架与顶点绑定由 `scripts/export_rounded_runtime.py` 从选定源生成；家油、捕食种与不足形态使用各自登记的导出配方。旧七模型批量导出入口 `export_runtime_models.py` 已禁止覆盖当前选用。运行 JSON 位于 `src/main/resources/assets/mcyukkuri/yukkuri/`，每种实体包含 19 个动作和表情套组，表情图集位于 `textures/entity/<实体 ID>/`。快照、配方、运行文件及指纹见 [runtime-sources.json](runtime-sources.json)。

## 来源

造型参考本机《ゆっくり八景U》相关资源，几何与像素贴图为本项目制作。原作品作者署名与授权尚未完整核实，不把这些文件标为已取得第三方开源授权。角色与设定来源见 [Wiki](../wiki/README.md)；游戏资源不包含八景 U 原版程序、录音或原始美术文件，原文台词摘录见[对白来源](dialogue/README.md)。

新素材在标准库制作，试用方案放包内 `drafts/`，被替代的方案直接删除。预览和检查结果在本机生成，不入库。
