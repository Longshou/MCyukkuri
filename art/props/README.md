# 可复用道具

[金银章](badges/README.md)保存饰物表面挂点与圆牌贴图来源；已接入资格与考核系统，造型待确认。

[商店界面套件](ui/README.md)是商店、考官、饲养员与徽章界面共用的 256×256 图集（九宫格面板、行卡、页签与图标、餡統星、能力条、等级章），造型待确认。

[大型游具](playgrounds/README.md)保存纸筒、陶管、滑梯、蹦床和水池的制作来源与选用快照，造型待确认。

[垫料、升级厕所与小玩具](care-expansion/README.md)保存 P5 扩展设施的参数源、选用快照和游戏导出。造型保持待确认。

[全身伤口游戏适配](body-wounds/README.md)选用 R3.1 制作源，覆盖七种角色的四类伤口、多处挂接和实际漏馅，已按用户“保存”[登记为标准](../../standards/yukkuri-library/ecology/body-wounds/README.md)。

2026-09-13：完整家油卵与裂壳卵 R2.4 已由用户确认，见[家油卵标准造型](../../standards/yukkuri-library/ecology/house-egg/README.md)。除已单独登记的家油卵和全身伤口外，其他周边素材保持待确认；游戏接入状态不因造型确认而改变。

[照料道具统一造型](../../standards/yukkuri-library/drafts/care-props/README.md)（R4 候选，待确认）：食盆、油粮袋、窝、残渣、便便、厕所、垫料与携带用品共用 [色板](palette.json) 和低多边形语汇；制作脚本在该目录，快照仍保存在 `runtime-peripheral/`、`care-expansion/`、`consumables/`、[捕食种枝巢与软帽](predators/README.md)及 `wild-shelters/`。

[宠物照料四件物品图标](pet-goods/README.md)（糖粒、布丁、牵引胴带、襁褓布，R4 候选，待确认）：16×16 物品图标，同用共用色板；制作脚本为照料道具制作源的 `goods.py`，`pet-goods/` 是选用快照。

[游戏周边美术清单](../../docs/PROP_ART_INVENTORY.md)列出当前设施、食物、工具和附属物的接入状态、原版美术占位及建议制作顺序。

R3.1 周边候选曾制作宠物食盆、三档袋装油粮、橙汁／去霉药、体型／份数联动和短茎组合；R3 新增六类伤情、油霉与漏馅过程；R3.1 补充全身伤口与多处组合。候选制作源与预览已删除。两类绷带取消，遗体待做。当前游戏接入状态仍以下列配方为准。

[家庭与茎生适配](family-life/README.md)、[家油缝隙与照料](house-care/README.md)已经接入核心版本；各包保留制作源和导出指纹。

| 道具 | 来源 | 使用文件 |
| --- | --- | --- |
| [食盆与油粮](food-bowl/README.md) | 魔理沙进食场景；浅盆及可分别隐藏的油粮组 | `food_bowl.bbmodel`、`food_bowl.geo.json`、`feeding.png` |

[消耗品选用包](consumables/README.md)接入六件原创道具和四种口中份量，保留制作源、配方、选用指纹及运行路径；造型待确认。

[冷冻收容柜](cold-storage/README.md)保留原创柜体制作源、选用模型、个人收容与手册的资源登记；柜体造型待确认。

[生活设施配方](care-facilities/README.md)选取上述食盆的盆体接入游戏，并制作草窝、沙厕和运输／饰物模型；盆内食物来自真实库存。

这里是独立可编辑道具的归类副本。`provenance.json` 记录来源文件与 SHA-256；修改来源后应同步并重新记录。各包的制作脚本在包内或标准库对应的 `drafts/`、`ecology/` 目录。

野外家庭住所、流浪油纸箱与野外便所见[野外住所选用](wild-shelters/README.md)。
