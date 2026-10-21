# 水生魔理沙帽

游戏选用的水生魔理沙专用帽快照，状态为**待用户确认**。制作源为 [marisa-aquatic-hat](../../../standards/yukkuri-library/interactions/marisa-aquatic-hat/README.md)：一份几何同时用于戴在头上、摘下的物品、破损显示和帽船，帽腔比普通帽更深更圆。

- [recipe.json](recipe.json)：选用的配方，与制作源一致（哈希见 `selection.json`）。
- [selection.json](selection.json)：制作源、构建脚本与六份运行资源的 SHA-256。

更换方案时先改制作源并重跑 `build.py`、`check.py`，再更新本快照和 `art/runtime-sources.json`。
