# 魔理沙帽子交互资源

制作参数见 [recipe.json](recipe.json)，导出入口为 `scripts/export_hat_interactions.py`，运行资源为 `src/main/resources/assets/mcyukkuri/yukkuri_presentation/hat_interactions.json`。帽子位置变化时，连同头饰位置适配一并更新，具体参数见[制作源](../../../standards/yukkuri-library/presentation/placement-fit/README.md)。

普通、稀毛、歪脸、豆形和家魔理沙使用独立帽子实体及同一挂接接口。`hat_follow` 控制帽身、帽檐和蝴蝶结跟随动作骨架；取帽与戴回时保留唯一帽子令牌及亚种模型标识。帽托位置、支撑区和幼油包络以当前拼接后的模型生成。玩法行为见[帽子与帽船实现](../../../docs/AQUATIC_MARISA_IMPLEMENTATION.md)。
