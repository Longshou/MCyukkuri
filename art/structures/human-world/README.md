# 人类商店与加工所

`build.py` 是原创方块布局和模板内实体的制作源；`manifest.json` 记录尺寸与数量，`exports.json` 记录本模块源与运行导出的 SHA-256。统一入口为 `python scripts/export_human_world.py`，检查使用 `--check`。

当前自然生成商店、劳力工场、馒头工厂和活体袋装厂。三类工厂独立生成，后两类各有亲代与子油育成间。旧 `yukkuri_factory` 模板保留手动放置与存档兼容，移除了自然放置集合。

`author_products.py` 是四类专用机台、馒头、活体包装袋与金银章的制作源；`products/` 保存原创像素贴图及登记。机台使用原生方块元素，分别构成滚轴台、蒸箱、封装支架与育成护理台，物品陈列使用固定 `item_display`。运行几何、贴图、物品模型、战利品表与结构模板由统一导出同步，外部漫画不进入游戏资源。

`HumanBuildingStructure.java` 复用原版单栋 jigsaw 拼装，并检查旋转后的完整地基采样，跳过积水与大高差候选。结构 JSON 使用注册类型 `mcyukkuri:human_building`。

运行 NBT 位于 `src/main/resources/data/mcyukkuri/structure/`，同目录树的 `worldgen/` 保存结构、池和放置规则，群系标签及人类生成规则也由脚本同步。模板使用 NeoForge 实体持久数据，按岗位绑定现场库存；不包含外部漫画图片或八景 U 提取物。

房间功能参考 [作品核读](../../../wiki/shops-factory-npcs.md)，建筑外形、台阶、柜台和机台为项目原创设计。当前为已接入、待用户造型确认版本；[本轮实机截图和检查](../../../docs/validation/human-world-expansion/README.md)与[玩法说明](../../../docs/HUMAN_WORLD.md)同步维护。

`author_pipeline.py` 制作接收检查台、分选台、清洁台、观察台和周转托盘；`pipeline_language.py` 保存对应中英文本。五种设备使用原生方块几何，托盘实体复用 `factory_transfer_tray` 方块模型，由 `FactoryTrayRenderer` 渲染，载物面高度为 4/16 格。布局将两组亲代、两栏育成、异常观察与四道出货工序分开，保留工人和托盘通道。当前源与运行路径、预览和验收见 [流水线记录](../../../docs/validation/factory-pipeline/README.md)，造型待用户确认。
