# 稀毛与歪脸灵梦圆润体态

`reimu_sparse` 与 `reimu_face` 复用当前圆润灵梦身体、连续权重及 19 段动作，并分别保留稀毛或面部不对称特征。制作数据见 [recipe.json](recipe.json)、冻结的 `inputs/` 与上级 [recipes.json](../recipes.json)；模型和运行指纹见 [manifest.json](manifest.json)。

稀毛形态保留三束刘海、细侧发和短后束，发束沿圆润身体曲面弯曲；蝴蝶结与飘带贴合身体。歪脸保留完整头发，按配方调整左眼大小和位置、嘴部偏移，并使五官贴合身体表面。身体及连续权重来自当前圆润灵梦供体模型。

从仓库根目录运行 `python scripts/export_reimu_deficient_rounded.py` 定向导出。本入口同步两种源模型、骨架、运行模型、256 像素图集、嘴部及身体参数、离身蝴蝶结、徽章挂点与伤口表面；不重建魔理沙形态。
