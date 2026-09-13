# 生活设施与表现配方

`recipe.json` 是本包制作源，`provenance.json` 登记源、输出及 SHA-256。执行 `python scripts/export_living_assets.py` 更新运行资源；`--check` 只读核对导出一致性。

食盆复用相邻 `food-bowl` 模型中的盆体八块几何和项目自制贴图，不复用静态食物。草窝、沙厕、携带箱和取下的饰物以本包方块配方制作，引用原版材质；盆中食物与厕所积存物由客户端按服务端实际状态绘制。

角色口中物品的位置、比例及嘴部咀嚼参数来自本包配方；睡眠压缩由 YukkuriRenderer 根据同步阶段叠加，角色本体继续读取 `art/source/standard-runtime/` 的选定模型。本包不修改标准库造型或批准记录。

输出位于 `src/main/resources/assets/mcyukkuri/` 的 `models/block/`、`models/item/`、`blockstates/`、`textures/block/` 与 `yukkuri_presentation/`。最新实机预览见 [P1–P2 证据](../../../docs/validation/p1p2/README.md)；本轮设施为开发造型，尚无用户标准确认。
