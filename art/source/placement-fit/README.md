# 头饰与头发位置适配（游戏接入）

游戏适配快照为 `src/main/resources/assets/mcyukkuri/yukkuri_presentation/placement_fit.json`。来源网格、逐形态调整参数与重建方式见[制作源说明](../../../standards/yukkuri-library/presentation/placement-fit/README.md)及其 [recipe.json](../../../standards/yukkuri-library/presentation/placement-fit/recipe.json)；每个基础模型和运行输出的指纹见 [manifest.json](manifest.json)。

`YukkuriModel.applyFit` 仅在基础模型指纹匹配时拼接适配网格。游戏和素材检查室先应用位置适配，再应用动作修订；帽子交互、鼓腮和果实挂点以拼接后的模型为输入。
