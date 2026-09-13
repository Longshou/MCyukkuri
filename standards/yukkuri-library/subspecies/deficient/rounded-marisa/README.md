# 稀毛与歪脸魔理沙体态修复

2026-09-13，按用户“稀毛、歪脸魔理沙的体态没有修改，还是老的，修复”更新。此前圆润推广排除了全部不足形态，这两种仍从旧方块快照生成；本次仅将 `marisa_sparse`、`marisa_face` 改为当前圆润身体。灵梦不足形态、豆油和其他角色未改形。

[当前交互预览](previews/current/index.html) · [两种四视图](previews/current/overview.png) · [稀毛前后对照](previews/current/marisa_sparse-comparison.png) · [歪脸前后对照](previews/current/marisa_face-comparison.png)。20 形态总览与亚种预览也读取当前导出。

制作源由 [recipe.json](recipe.json)、`inputs/` 冻结的圆润魔理沙模型及骨架、上级 `recipes.json` 中的稀毛和歪脸参数组成。定向重建命令为 `python scripts/export_marisa_deficient_rounded.py`。该脚本同步两种可编辑源、运行快照、256×256 表情图集、伤口表面、旧伤口迁移输入、嘴部和帽饰挂点；不重新生成其余角色。后续运行旧后发适配器会明确拒绝覆盖这两种体态。

身体与后部标记的网格及连续权重逐项保留圆润基线；稀毛缩窄发束并保留三束刘海，歪脸保留大小眼和偏移嘴部，并重新按身体高度贴合表面。匹配贴图必须使用本包冻结的 256 像素图集，不能沿用旧不足包的 128 像素图集。配套骨架保留 19 段动作，水平移动由游戏控制。

源与运行路径、指纹见 [manifest.json](manifest.json)。造型标为待用户确认，不修改既有圆润基线的批准记录。该次修正优先于旧文档中“全部不足油保持旧体型”的范围说明。

稀毛的三束刘海、侧短束和后束已按圆润头皮重新生成并绑定到身体，移除了两种旧的浮动顶板。仅稀毛的帽身、帽檐、蝴蝶结与挂点下移一个模型单位；歪脸保留能覆盖其完整发顶的帽子高度。独立审查的首轮打回和修订后通过记录见[造型审查](reports/current/independent-review.md)。

执行 `python scripts/preview_marisa_deficient.py` 重建四视图、前后对照及行走、侧弯、伸展动图，并核对两种源与导出、图集尺寸、身体权重、伤口指纹和 950 个动作时点。执行 `node scripts/verify_marisa_deficient_browser.cjs` 检查实际交互页面。结果见 [数据检查](reports/current/checks.json)与[浏览器检查](reports/current/browser.json)。五种帽子的脱戴与帽托交互已在独立固定游戏包中验证，详见[交互包及实机记录](../../../../../art/source/hat-interactions/README.md)；测试地图的旧固定游戏包不会自动替换。
