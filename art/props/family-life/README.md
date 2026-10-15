# 家庭生长与茎生接入

制作源是 [recipe.json](recipe.json)，由 [export_family_assets.py](../../../scripts/export_family_assets.py)读取旧版生命周期、成长和茎组件，导出 `assets/mcyukkuri/yukkuri_presentation/family.json`。来源和输出指纹见 [provenance.json](provenance.json)。

普通灵梦与魔理沙继续使用 `art/source/standard-runtime/` 选定的角色快照、骨架和表情图。茎由 `FamilyGeometry` 共用于服务器出生位置与客户端绘制；挂茎子代读取同一运行模型的 `idle` 姿势和 `sleep` 贴图，脱落后交给真实实体。角色制作源与已确认记录未改动。

数量为 1–5，茎长、粗细随数量与个体差异变化。子代按[体型规范](../../../docs/GROWTH_BALANCE.md#体型规范)在茎上从 0.10 长到 0.20，落地 0.20，因此小母体的茎弧有独立长度下限；高帽魔理沙的连接点轻微埋入帽顶。出生后显示枯茎。剪下后由持有原胎次的离身茎方块接管，培养瓶与非瓶装根部的接入见[器具素材](../operation-tools/README.md)；其接地净空修正不改变母体上的茎形。

当前属于游戏适配造型，技术视觉检查通过不等于用户确认标准。最新实际预览位于 [previews/current/](previews/current/)，检查范围和实机结果见 [核心玩法记录](../../../docs/CORE_GAMEPLAY.md)。
