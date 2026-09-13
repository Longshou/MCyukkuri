# 生活设施扩展草案

本目录为待确认的 MC 适配制作源。`build.py` 定义三种垫料、两种升级油厕与六种小玩具；复用相邻 `operation-tools/build.py` 的几何函数。导出同时更新本目录 `recipe.json`、`assembled/`、`art/props/care-expansion/selected/` 和对应游戏资源，不单独修改生成网格。

`--check` 只读检查导出一致性。几何使用每方块 16 单位，色板为原创绘制；模型无独立骨架。标准状态保持待确认，运行行为与来源说明见[接入包](../../../../art/props/care-expansion/README.md)。
