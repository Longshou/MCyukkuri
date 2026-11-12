# 人类商店与加工所

`build.py` 是原创方块布局和模板内实体的制作源；`language.json` 保存本模块当前中英文界面文案。`manifest.json` 记录尺寸与数量，`exports.json` 记录本模块源与运行导出的 SHA-256。统一入口为 `<py> scripts/export.py --family human-world`，检查加 `--check`。

宠物店随普通村庄生成。劳力工场、馒头工厂和活体袋装厂独立生成，后两类各有亲代与子油育成间。

`author_products.py` 是四类专用机台、馒头、活体包装袋与金银章的制作源；`products/` 保存原创像素贴图及登记。机台使用原生方块元素，分别构成滚轴台、蒸箱、封装支架与育成护理台，物品陈列使用固定 `item_display`。运行几何、贴图、物品模型、战利品表与结构模板由统一导出同步，外部漫画不进入游戏资源。

`HumanBuildingStructure.java` 复用原版单栋 jigsaw 拼装，并检查旋转后的完整地基采样，跳过积水与大高差候选。结构 JSON 使用注册类型 `mcyukkuri:human_building`。

三类工厂的结构集用原版 `exclusion_zone` 避开村庄候选起点周围 9 区块，覆盖原版村庄拼装范围与厂房地基。候选点的群系即使不允许村庄落地也会留出这段间隔。

运行 NBT 位于 `src/main/resources/data/mcyukkuri/structure/`，同目录树的 `worldgen/` 保存结构、池和放置规则，群系标签及人类生成规则也由脚本同步。模板使用 NeoForge 实体持久数据，按岗位绑定现场库存；不包含外部漫画图片或八景 U 提取物。

房间功能参考 [作品核读](../../../wiki/shops-factory-npcs.md)，建筑外形、台阶、柜台和机台为项目原创设计；玩法见[玩法说明](../../../docs/FEATURES.md#人类世界)。

`author_pipeline.py` 制作接收检查台、分选台、清洁台、观察台和周转托盘；`pipeline_language.py` 保存对应中英文本。五种设备使用原生方块几何，托盘实体复用 `factory_transfer_tray` 方块模型，由 `FactoryTrayRenderer` 渲染，载物面高度为 4/16 格。布局将两组亲代、两栏育成、异常观察与四道出货工序分开，保留工人和托盘通道。

## 村庄街面

`village_shop()` 在同一个店铺布局前延伸 8 格步道，实体及岗位随布局一起平移。村庄中心在原版打乱的道路接口中选择一个，先尝试 `mcyukkuri:village/<村庄类型>/frontage`，由原版 jigsaw 检查相接方向和占地。地块一旦选定，保留整个地基的水平范围，避免其他道路贴地铺设时横穿地块。地块放不下时池回退到同类型的原版道路；其他出口继续使用原版道路。每座村庄至多尝试一个店铺地块。已生成区块保留原布局。

街面两端从 `mcyukkuri:village/street_sites` 取可选地块。增加设施时，用制作源输出一个结构 NBT，在这个池追加一项，模板的临街边缘以 `name=mcyukkuri:street_site` 的水平 jigsaw 接步道。接口下方一格铺路，`final_state` 设为 `minecraft:air` 并保持行走净空；无需向外拼接时设 `pool=minecraft:empty`。在 NBT 中写入居民及其现有岗位数据。两个接口各自抽选，保留空元素时允许街段收尾；原版检查地块碰撞。

试玩时用种子 `42` 新建默认世界。在原点附近运行 `/locate structure minecraft:village_plains` 可定位到 `(656, -304)`；也可用 `/tp @s 651 80 -302` 直接到临街步道，再沿步道上正门台阶。

## 宠物店

`build.py` 的 `shop()` 生成 41×16×33 的单层坡屋顶宠物店，动作博物馆通过 `yukkuri human build shop` 使用 `mcyukkuri:yukkuri_shop` 模板。主材为白石英墙、桦木框架与地板、泥砖屋顶；点缀为红白遮阳篷与深色橡木／绿色招牌。正面有灵梦头像招牌（美术评审方案 G）、遮阳篷、旗帜和整面橱窗；屋顶四行、立面六行。

- 销售厅（x2–22、z4–16）：四个临街展舱（内部 3×3，玻璃朝向街窗与过道；第四个是蕾米莉亚捕食种舱）、L 形柜台（店主在柜台后）、货架墙、咨询角。展舱是抬高一格的展台（y3 羊毛坐垫台面，个体站在 y4，与过道里客人的视线齐平，也高出街面橱窗的泥砖台基；不铺地毯，地毯格对个体的站位检查不算可站），台面放一件真实玩具（不消失、隔着玻璃拾不到），玻璃板高出台面三格、顶部敞开供饲养员递篮；价签挂在过道侧台面正面。四只展示个体的先天五属性互不相同（灵梦活泼、蕾米莉亚爱招呼、橙怕生、金章爱丽丝高傲），对橱窗外客人的回应见[玩法说明](../../../docs/FEATURES.md#宠物店徽章与宠物培育)。后场有两只水槽、育幼栏也各放了玩具。
- 后场（x2–11、z18–29）：四个低玻璃水槽栏、饲料桶与饲养员工位；繁育室（x13–22）：种油栏 5×5 与幼体栏 5×5。
- 考场（x24–38）：口试区（答题台对考官桌、躲藏箱、两个约定席）、7×5 玻璃模拟房间（`exam_mark` room、窗外观察位 examiner）、陪考生栏（三个 trainee 标记，门通模拟房间）、金章间（3×3 两格由玻璃隔开：gold／actor）。
- 员工：店主 Job 1、考官 Job 3（`shop_examiner`）、饲养员 Job 4（`shop_keeper`）。动物的待绑定岗位与用途写入 `NeoForgeData.HumanCommerce`；资格与已学技能分别写入根 `YukkuriPet.Grade`／`KnownSkills`，五属性写入 `YukkuriLife.Personality.Innate`，不由徽章等级推导。初始展舱与种油各自明确配置六项技能，陪考生配置四项生活技能且无章，野良役与后场无章库存不预授技能。首次绑定店员时，已有资格的库存／种油补齐发证编号、发证者与当前世界时间下的有效期。

`author_exam_props.py` 绘制并导出三个考场功能方块（答题台、躲藏箱、考位标记，`kind` 含 room／promise／actor／gold／trainee／examiner）的贴图与模型，躲藏箱内部宽 14/16、高 15/16，容得下全部普通与捕食成体，不含胖大的 deibu。`check_structure.py` 在 `export_human_world.py` 导出与 `--check` 中运行：检查方块覆盖、实体占位与地板、栏内食盆窝位、地面光照 ≥8（按灯笼泛光计算）、门高、员工可达性、立面单一材质不超过 6×6、屋顶占比。

咨询角有一位顾客（caretaker_sister，岗位 `shop_sister`，Job 0）；店后设向东敞开的树篱小屋，走失的魔理沙（`Use` = `lost`，`PendingRole` = `shop_sister`）站在地面上。考官为 caretaker_brother。顶灯嵌入 y8 天花板，使用赭黄蛙明灯，约每 6 格地面一盏以保证地面光照 ≥8；柜台与过道上方保留少量吊灯。
