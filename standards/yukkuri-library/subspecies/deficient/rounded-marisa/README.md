# 稀毛与歪脸魔理沙圆润体态

`marisa_sparse` 与 `marisa_face` 使用当前圆润魔理沙身体、冻结 donor 网格与骨架、256×256 图集及现行动作。制作数据见 [recipe.json](recipe.json)、`inputs/` 与上级 [recipes.json](../recipes.json)；来源、运行资源和指纹见 [manifest.json](manifest.json)。

稀毛保留三束刘海、侧短束和后束，发束贴合圆润头皮；歪脸保留大小眼与偏移嘴部。两种形态均使用单侧粗辫。稀毛的 `sparse_side_hair` 只保留一束辫、发结、发尾和短侧发；豆形魔理沙按配方缩放并沿头部轮廓外移。稀毛帽子由 `sparse_hat_seat` 放置，帽檐底面在眉顶上方 0.3 模型单位，帽身与头顶相交贴合；运行时不再叠加帽子下移。其余稀毛与歪脸头饰保持当前登记参数。

从仓库根目录运行 `python scripts/export_marisa_deficient_rounded.py` 定向导出两种形态；勿再用旧后发适配器覆盖。该入口同步可编辑源、运行快照、图集、伤口表面、嘴部与帽饰挂点。角色认可记录与模型指纹以登记文件为准。
