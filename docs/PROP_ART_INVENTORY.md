# 游戏周边美术清单

2026-09-13：完整家油卵与裂壳卵 R2.4 已由用户确认，制作源、模型、贴图与预览已冻结保存，见[家油卵标准造型](../standards/yukkuri-library/ecology/house-egg/README.md)。其他周边素材保持待确认；游戏接入状态不因造型确认而改变。

核对日期：2026-09-13。按玩家看到的设施、道具和身体附属物列出，不限于代码中的实体类型。本页记录当前接入事实和制作建议，不代表占位造型已经确认，也不代表已经完成替换。

专用造型已更新为 [R3.1 周边素材候选](../standards/yukkuri-library/drafts/peripheral-props/README.md)，可在[统一预览](../standards/yukkuri-library/drafts/peripheral-props/previews/current/index.html)按方块、物品、附属物、伤情与效果查看，共 44 个展示条目。R2 改用宠物食盆与三档袋装油粮，新增橙汁、去霉药；便便／土堆／头饰随体型、漏馅随数量联动，家油卵改竖长形并加入短茎组合。两类绷带取消，遗体待做。本轮按用户授权选用周边快照接入，原版短草、高草、小麦仍通过食物标签喂食，盆中改用横放几何。选用指纹和实机预览见[游戏适配包](../art/props/runtime-peripheral/README.md)，标准候选说明见 [R2 交接记录](../standards/yukkuri-library/drafts/peripheral-props/INTEGRATION.md)。

## 设施与食物

捕食种新增原创编织枝巢与两种软帽，源与运行资源已同步，造型待确认；详见[捕食种实现](PREDATOR_IMPLEMENTATION.md)和 `art/props/predators/`。软帽沿用实体的实际帽冠、帽带、荷叶边和结饰，支持离身及徽章显示。

| 对象／标识 | 当前接入与外观 | 美术处理建议 |
| --- | --- | --- |
| 食盆 `food_bowl` | 已接入 R2 宠物盆；三档油粮共用颗粒，草麦显示专用横放几何；0–8 份联动 | 八份以上外观饱和；厨余盆网格保留，但厨余食物玩法未接入 |
| 三档油粮 | 已注册廉价、普通、高级三档油粮；袋装外观和生存合成已接入 | 分别提供 12／20／30 营养，实际吞咽后结算 |
| 食物残渣 `food_scrap` | 已改为专用碎屑模型 | 区分整份食物、馅料与厨余，不把碎屑画成鱼骨果核 |
| 天然洞穴叶床 `forest_burrow`／结界 `nest_boundary` | 已接入零散叶床与细枝；岩土空腔来自现有地形 | [制作包](../standards/yukkuri-library/ecology/wild-shelters/README.md)，叶床／结界及浅坑布置已于 2026-09-13 确认 |
| 流浪油纸箱 `cardboard_shelter` | 已接入开口、箱壁、折盖与原创纸板纹理 | 有真实单人睡位及受雨损耗，造型待确认 |
| 野外便所 `wild_latrine` | 围枝土面，干净／脏污两态 | 复用有限便所容量，造型待确认 |
| 草窝 `yukkuri_nest` | 已接入 R2 凹陷垫窝 | 造型仍待确认 |
| 沙厕 `yukkuri_toilet` | 已接入 R2 专用厕盆，干净／脏污随库存变化 | 每份便便的来源体型保留至清理 |
| 陶瓷／带盖油厕 | 新增原创网格，分别容纳 16／32 份；当前多角度画面已复核 | [扩展包](../art/props/care-expansion/README.md)，待确认造型 |
| 报纸／泥土／砂纸垫料 | 原创网格与实际接地磨损、缓冲差异已接入 | 加上不铺垫材构成原生四档；不把静止计为滑擦 |
| 弹珠／橡胶球／两种布偶／石子／骰子 | 独立网格和真实物品接触，当前画面已复核 | 同一物品只被一场玩耍占用，不产生复制品 |
| 纸筒／陶管／滑梯／蹦床／水池 | 原创网格、多格碰撞、连续路线与有限水已接入 | [大型游具包](../art/props/playgrounds/README.md)，待确认造型 |
| 冷冻收容柜 `cold_storage` | 原创四抽屉保温柜、独立色板与四向方块模型已接入，当前实机闭环已复核 | [制作与预览](../art/props/cold-storage/README.md)，个人四格为 MC 适配，造型待确认 |
| 家油缝隙 `house_crevice` | 已接入 R2 箱体入口与内壁，碰撞范围已同步 | 家油可实际进出；垫料与卵分别显示 |
| 巢内垫材（附属显示） | 已改为纸屑与纤维垫材网格 | 保留原六份搬料建巢条件 |
| 家油卵（巢内附属显示） | 已接入用户确认的 R2.4 完整／裂壳卵 | 竖长、上窄下宽；位置按巢垫表面调整，仍无独立可拾取卵物品 |
| 排泄物 `yukkuri_waste` | 已接入独立便便模型，并保存排出者体型 | 沿用八景 U 独立便便图层来源，不与身体漏馅混用 |
| 漏出馅料 `leaked_filling` | 已接入馅团模型；专用物品实体按实际数量分组绘制 | 避免原版多份掉落渲染把整个堆叠重复绘制 |
| 埋葬土堆 `burial_mound` | 已接入 R2 土堆，保存被埋者身份与体型 | 破坏土堆不会重新生成同一遗体 |

## 携带、照料与互动道具

| 对象／标识 | 当前外观 | 美术处理建议 |
| --- | --- | --- |
| 携带篮 `yukkuri_carrier` | 已接入 R2 篮体、提手与衬垫 | 造型仍待确认；运输保持既有个体身份 |
| 清洗布 `wash_cloth` | 已接入 R2 折叠布 | 造型仍待确认 |
| 教导哨 `teaching_whistle` | 已接入 R2 哨子 | 造型仍待确认 |
| 毛线球 `yarn_ball` | 已接入 R2 球体与绕线外观 | 造型仍待确认 |
| 结伴绳 `companion_cord` | 已接入 R2 成对绳结 | 造型仍待确认 |
| 绷带 `bandage` | 保留旧注册与治疗逻辑兼容存档，撤下新配方和创造栏 | 用户取消；不再制作或绘制身体绷带 |
| 草药 `herbal_medicine` | 保留旧注册与治疗逻辑兼容存档，撤下新配方和创造栏 | 当前照料入口为橙汁和去霉药，感染仍是独立状态 |
| 橙汁 `orange_juice` | 已注册并接入 R2 瓶装外观、合成和治疗 | 恢复目前实现的生命值、伤口与馅量；不清除油霉或感染 |
| 去霉药 `mold_remover` | 已注册并接入 R2 药瓶、合成和治疗 | 只清除油霉；无效治疗不扣瓶 |
| 酸奶饮品 `yogurt_drink` | 原创瓶装网格与贴图已接入 | 缓解干燥与辛辣；造型待确认，实机画面待补 |
| 兴奋药 `stimulant` | 原创药瓶已接入 | 吞咽后进入有限状态，不绕过生育资格；实机待验 |
| 幼语药 `sitataran` | 原创药瓶已接入 | 改变口吻，不修复舌部、不改变年龄；实机待验 |
| 助眠喷雾 `sleep_spray` | 原创喷雾瓶已接入 | 延迟进入有限药物睡眠；实机待验 |
| 超辣油粮 `spicy_grain` | 原创袋装网格已接入 | 辛辣不适和实际有限吐馅在吞咽后结算；实机待验 |
| 生殖用馅 `reproductive_filling` | 原创管状道具已接入，连续操作时管嘴对准作用点 | 投药与原胎次身份统一；挂接画面待验，造型待确认 |
| 遗体 `yukkuri_remains` | 复用死者网格与原伤口，地面实体和物品均显示对应遗体；交互见[受伤与遗体](INJURIES_AND_REMAINS.md) | 死因专用美术仍待做；当前闭眼静态姿态只是运行适配，未确认为遗体标准 |
| 观察手册 `field_notebook` | 原版书图标；使用书页界面 | 按用户要求直接沿用原版书；R2.4 预览已同步，不另做专用封面 |
| 脱落灵梦蝴蝶结 `reimu_ribbon` | 已接入 R2 角色派生部件，按来源体型缩放 | 家油目前复用派生件缩放，不能据此声称两种原头饰完全一致 |
| 脱落魔理沙帽 `marisa_hat` | 已接入 R2 角色派生部件，按来源体型缩放 | 家油目前复用派生件缩放，标准确认仍独立 |
| 魔理沙帽子容器 `marisa_hat_vessel`（帽船、侧放接生、岸边浮帽） | 2026-10-14 起由当前魔理沙戴着的帽子逐块复制并掏空，沿用魔理沙图集的帽子格子，见[帽子容器](../standards/yukkuri-library/interactions/marisa-hat-vessel/README.md)；水生魔理沙按 1.5 倍绘制 | 待用户确认；旧扁平帽船道具 `hat_boat` 已不使用（保留文件） |

## 身体与繁殖附属物

六件消耗品及清水／酸奶／兴奋药／幼语药四种口中份量见[选用与导出](../art/props/consumables/README.md)。口中只显示一口份量，复用实际嘴部挂点并随孕体变形；清水物品本身仍沿用原版真实清水瓶。配方、可编辑源与当前指纹由该包登记，最新实机画面尚待补齐。

| 对象 | 当前外观 | 美术处理建议 |
| --- | --- | --- |
| 茎与枯茎 | 根据配方生成弯曲管状网格；白色混凝土贴图配合绿／褐色着色 | R2 使用更短的茎系数和专用材质，连同真实母体、幼体核对；独立断茎物品仍未实现 |
| 茎上幼体 | 使用当前接入角色模型及睡眠表情，随胎数排列 | R2 候选比例 0.30→0.18，两角色一至五胎可预览，尚未修改运行出生尺寸 |
| 伤口 | [R3.1 游戏适配](../art/props/body-wounds/README.md)：七种角色、四种伤情、最多八处独立挂点，随动作变形并从对应裂口释放有限馅料 | 2026-09-13 已按用户“保存”[冻结为伤口标准](../standards/yukkuri-library/ecology/body-wounds/README.md)；不改变角色身体的确认状态 |
| 身上绷带 | 已取消身体叠加显示 | 用户取消，不再作为制作任务 |

四种角色的刷怪蛋属于生成工具，暂不列入生活道具制作批次。实际喂食的苹果、实际搬运的小麦和纸等原版物品可以保留原版外观；把它们加工成新的专用道具后，应使用符合成品含义的美术。

## 制作顺序与来源

人类工厂扩展另增加原创滚轴作业台、馒头蒸箱、活体封装台、育成护理台、馒头、活体包装袋和金银章像素贴图。制作源与运行映射见[人类建筑包](../art/structures/human-world/README.md)，当前均已接入、待造型确认。劳力厂成品是实际纸张，仍用原版纸的外观。

本轮游戏接入以固定的周边快照为准；完整卵与裂壳卵已确认，其余沿用用户授权的待确认适配状态。短茎与 0.18 实子仍未接入，遗体保持待做，两类绷带不再作为制作任务。后续标准更新需重新选用、导出和验收，不自动覆盖运行快照。

现有 [`filling/`](../art/props/filling/README.md) 虽名为馅料团，来源是 `variants/marisa-poop` 的排泄动作。不能仅凭目录名把它当成身体漏出的馅料；是否适合排泄物，应先核对实际模型与参考。

初次盘点通过注册代码、制作配方、运行物品模型和渲染代码核对游戏现状。后续 R2 已制作并核对新模型与统一预览；2026-09-13 本轮已修改运行资源、通过六组 GameTest 并采集隔离客户端实机预览，具体范围见[实施记录](PORT_REMAINDER.md)。中文说明和链接已人工核对。

- [物品注册](../src/main/java/io/github/longshou/mcyukkuri/registry/ModItems.java)、[方块注册](../src/main/java/io/github/longshou/mcyukkuri/registry/ModBlocks.java)
- [生活设施配方](../art/props/care-facilities/recipe.json)、[家油照料配方](../art/props/house-care/recipe.json)、[家庭与茎生配方](../art/props/family-life/recipe.json)
- [食盆渲染](../src/main/java/io/github/longshou/mcyukkuri/client/CareRenderer.java)、[家油巢显示](../src/main/java/io/github/longshou/mcyukkuri/client/HouseNestRenderer.java)
- [茎生显示](../src/main/java/io/github/longshou/mcyukkuri/client/FamilyRenderer.java)、[身体附属显示](../src/main/java/io/github/longshou/mcyukkuri/client/YukkuriRenderer.java)
- [运行物品模型](../src/main/resources/assets/mcyukkuri/models/item/)、[道具目录](../art/props/README.md)

金银章现为饰物上的圆形金属牌，已接入实际饰物挂点、资格考核及摘章／补发，见[徽章制作源与实机预览](../art/props/badges/README.md)。此版造型仍待用户确认。
