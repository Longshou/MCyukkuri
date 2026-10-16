# 人类商店与加工所

`build.py` 是原创方块布局和模板内实体的制作源；`manifest.json` 记录尺寸与数量，`exports.json` 记录本模块源与运行导出的 SHA-256。统一入口为 `python scripts/export_human_world.py`，检查使用 `--check`。

当前自然生成商店、劳力工场、馒头工厂和活体袋装厂。三类工厂独立生成，后两类各有亲代与子油育成间。旧 `yukkuri_factory` 模板保留手动放置与存档兼容，移除了自然放置集合。

`author_products.py` 是四类专用机台、馒头、活体包装袋与金银章的制作源；`products/` 保存原创像素贴图及登记。机台使用原生方块元素，分别构成滚轴台、蒸箱、封装支架与育成护理台，物品陈列使用固定 `item_display`。运行几何、贴图、物品模型、战利品表与结构模板由统一导出同步，外部漫画不进入游戏资源。

`HumanBuildingStructure.java` 复用原版单栋 jigsaw 拼装，并检查旋转后的完整地基采样，跳过积水与大高差候选。结构 JSON 使用注册类型 `mcyukkuri:human_building`。

运行 NBT 位于 `src/main/resources/data/mcyukkuri/structure/`，同目录树的 `worldgen/` 保存结构、池和放置规则，群系标签及人类生成规则也由脚本同步。模板使用 NeoForge 实体持久数据，按岗位绑定现场库存；不包含外部漫画图片或八景 U 提取物。

房间功能参考 [作品核读](../../../wiki/shops-factory-npcs.md)，建筑外形、台阶、柜台和机台为项目原创设计。当前为已接入、待用户造型确认版本；玩法见[玩法说明](../../../docs/HUMAN_WORLD.md)。

`author_pipeline.py` 制作接收检查台、分选台、清洁台、观察台和周转托盘；`pipeline_language.py` 保存对应中英文本。五种设备使用原生方块几何，托盘实体复用 `factory_transfer_tray` 方块模型，由 `FactoryTrayRenderer` 渲染，载物面高度为 4/16 格。布局将两组亲代、两栏育成、异常观察与四道出货工序分开，保留工人和托盘通道。造型待用户确认。

## 宠物店（2026-10-16 重设计）

`build.py` 的 `shop()` 生成 41×16×33 的单层坡屋顶街边宠物店（原结构 ID `mcyukkuri:yukkuri_shop`，世界生成文件不变）：主材为白石英墙、桦木框架与地板、泥砖屋顶；点缀为红白遮阳篷与深色橡木／绿色招牌。正面有灵梦头像招牌（美术评审方案 G）、遮阳篷、旗帜和整面橱窗；屋顶四行、立面六行。

- 销售厅（x2–22、z4–16）：四个临街展舱（内部 3×3，玻璃朝向街窗与过道；第四个是蕾米莉亚捕食种舱）、L 形柜台（店主在柜台后）、货架墙、咨询角。
- 后场（x2–11、z18–29）：四个低玻璃水槽栏、饲料桶与饲养员工位；繁育室（x13–22）：种油栏 5×5 与幼体栏 5×5。
- 考场（x24–38）：口试区（答题台对考官桌、躲藏箱、两个约定席）、7×5 玻璃模拟房间（`exam_mark` room、窗外观察位 examiner）、陪考生栏（三个 trainee 标记，门通模拟房间）、金章间（3×3 两格由玻璃隔开：gold／actor）。
- 员工：店主 Job 1、考官 Job 3（`shop_examiner`）、饲养员 Job 4（`shop_keeper`）；动物携带 `HumanCommerce` 的 `Use`／`Grade`／`Pedigree`，银章以上同时写旧键 `Medal`。

`author_exam_props.py` 绘制并导出三个考场功能方块（答题台、躲藏箱、考位标记，`kind` 含 room／promise／actor／gold／trainee／examiner）的贴图与模型，躲藏箱内部宽 14/16、高 15/16，容得下全部普通与捕食成体，不含胖大的 deibu。`check_structure.py` 在 `export_human_world.py` 导出与 `--check` 中运行：检查方块覆盖、实体占位与地板、栏内食盆窝位、地面光照 ≥8（按灯笼泛光计算）、门高、员工可达性、立面单一材质不超过 6×6、屋顶占比。

店内常客与走失委托：咨询角有一位顾客（caretaker_sister，岗位 `shop_sister`，Job 0）；店后有一处向东敞开的树篱小屋，走失的魔理沙（`Use` = `lost`，`PendingRole` = `shop_sister`）站在地面上，沿用旧模板的委托来源。考官改为 caretaker_brother。顶灯改为嵌入 y8 天花板的赭黄蛙明灯（约每 6 格地面一盏，保证地面光照 ≥8），柜台与过道上方保留少量吊灯。
