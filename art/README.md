# 当前实体接入素材

2026-10-07：[头饰与头发位置适配](source/placement-fit/README.md)已接入19种游戏形态与素材检查室，待用户复查：12种形态的头发顶部顺着圆润头顶收圆，魔理沙帽子戴低、灵梦系蝴蝶结贴住后脑、橙帽和爱丽丝发箍随发面；其余七种检查后不改。基础模型与指纹不变，鼓腮、帽子交互和果实悬挂点已按新网格重导。

鼓腮表情、头部挂接、侧弯与跳动的[本轮动作修订](source/motion-corrections/README.md)已接入当前19种游戏形态及素材检查室，待用户复查；鼓腮为第七版（圆鼓双颊、噘嘴，侧发不穿模）。基础造型快照与旧批准记录保持为基线。

集中检查当前19种游戏形态、动作、表情与290段声音，使用项目根目录的 `打开素材检查.cmd`。操作和范围见[素材检查室](../preview/review/README.md)；意见可按动作或声音记录并导出。

稀毛与歪脸灵梦也已完成[圆润体态接入](../standards/yukkuri-library/subspecies/deficient/rounded-reimu/README.md)，源、游戏资源及本轮实机记录均已同步。

稀毛与歪脸魔理沙已选用[本轮圆润体态修复](../standards/yukkuri-library/subspecies/deficient/rounded-marisa/README.md)。五种魔理沙帽子的独立脱戴与小幼油帽托见[帽子交互包](source/hat-interactions/README.md)，包含当前预览和有范围的验收记录。

2026-09-13：[圆润运行包](source/rounded-runtime/README.md)已选用八只当前标准导出并组合多毛灵胖，包含配套伤口、可脱落饰物、徽章与身体尺寸。普通五种、壳种和多毛的旧接入目录保留历史快照；当前入口为[本轮运行预览](source/rounded-runtime/previews/current/index.html)，以 `runtime-sources.json` 的逐项登记为准。

蕾米莉亚与弗兰的模型、飞行、帽饰和枝巢已从[捕食种制作包](../standards/yukkuri-library/subspecies/predator/README.md)选用接入，见[两种动作预览](source/predator-runtime/preview.html)与[实现和验收](../docs/PREDATOR_IMPLEMENTATION.md)。两种各含 19 个通用动作、四段行为动作和 12 套表情；造型待用户确认。下方七种记录保留其既有范围。

[全身伤口适配](props/body-wounds/README.md)已为七种实体接入按命中位置保存的多处伤口、局部凹陷、动作挂接与出口漏馅；选用源、对应骨架和产物指纹单独登记，角色标准造型保持原确认状态。

人类 NPC 的原创服装、原版人形骨架、长发与莫西干头部件见 [人类运行素材](source/human-runtime/README.md)；商店、三类独立工厂及原创机台／商品的制作源见 [建筑包](structures/human-world/README.md)。当前扩展已接入游戏，造型待用户确认。

当前对白以 [八景 U 原版整理](dialogue/README.md)为制作源，112 条 AquesTalk1 语音已接入头顶气泡，见[试听页](audio/aquestalk/index.html)和[验收记录](../docs/DIALOGUE_IMPLEMENTATION.md)。发声路线参考[《油库里日记》原声研究](audio/DIARY_AUDIO_RESEARCH.md)，音程与逐句节奏待听审。首轮 Mei 方案已被否决，52 个导出已撤回并保留于[归档](audio/archive/mei-first-pass/README.md)。

历史动作与表情源文件包见 [分类资料库](library/README.md)，独立馅料、食盆等模型见 [道具目录](props/README.md)。这些资料保留用于复用与追溯；[生活设施配方](props/care-facilities/README.md)已选择食盆盆体接入游戏，其他包不自动接入；恢复与使用边界见 [资产流程](../docs/ASSET_WORKFLOW.md)。

已确认的[标准帕秋莉模型](../standards/yukkuri-library/assembled/patchouli/patchouli.bbmodel)及其共用组件位于项目内的 `standards/yukkuri-library/`。角色登记、造型基准和预览见[素材库说明](../standards/yukkuri-library/README.md)。

当前接入快照位于 [`source/standard-runtime/`](source/standard-runtime/README.md)，覆盖灵梦、魔理沙、橙、爱丽丝、帕秋莉及两种家油。三种新增角色与长发动作适配见 [种族实装记录](../docs/SPECIES_IMPLEMENTATION.md)。[动作预览](source/standard-runtime/preview.html)读取与本次打包资源相同的数据。每种实体包含 19 个动作、11 套表情；家油保留连续身体网格，并增加分段混合绑定。构建、引擎检查与部分实际客户端审查已执行，当前范围见 [P1–P2 记录](../docs/P1_P2_IMPLEMENTATION.md)；家油造型确认状态保持待确认。

选定的 `.bbmodel` 保留原始编辑骨架；适配骨架与顶点绑定由 `scripts/export_runtime_models.py` 从选定源生成。运行 JSON 位于 `src/main/resources/assets/mcyukkuri/yukkuri/`，表情图集位于 `textures/entity/<实体 ID>/`。快照、配方、运行文件及指纹见 [runtime-sources.json](runtime-sources.json)。标准库更新不会自动替换游戏资源。

以下是保留的旧 M1 基线，当前渲染器不读取这些模型：

| 种族 | 历史来源 | 文件 |
| --- | --- | --- |
| 魔理沙 | 用户选定的去底层版本；历史制作目录名为 `variants/marisa-body` | `source/marisa/marisa.bbmodel`、`.geo.json`、`.png` |
| 灵梦 | 与基础魔理沙同尺度的八景 U 参考版本；历史制作目录名为 `variants/reimu-hakkei` | `source/reimu/reimu.bbmodel`、`.geo.json`、`.png` |

![魔理沙基础模型](source/marisa/preview-sheet.png)

![灵梦基础模型](source/reimu/preview-sheet.png)

上述 `.bbmodel`、贴图、`.geo.json` 与配图保留此前造型，不代表本次运行资源。当前实现和验收范围见[开发记录](../docs/ENTITY_IMPLEMENTATION.md)。

造型参考本机《ゆっくり八景U》相关资源，几何与像素贴图为此前项目制作。原作品作者署名与授权尚未完整核实，不把这些文件标为已取得第三方开源授权。具体角色与设定来源见 [Wiki](../wiki/README.md)。

历史目录名用于说明来源，不是当前制作入口；本项目不包含八景 U 原版程序、录音或原始美术文件，原文台词摘录见[对白来源](dialogue/README.md)。新素材在项目内的标准库制作，试用方案进入包内 `drafts/`；被替代的预览与输出核对引用后清理，仍有追溯用途的记录才进入 `archive/`。将新模型或动作接入游戏时，单独更新接入快照、运行资源、来源登记及实现说明。
