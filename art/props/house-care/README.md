# 家油缝隙与基础照料

[recipe.json](recipe.json)是可编辑制作源，[export_house_assets.py](../../../scripts/export_house_assets.py)生成方块模型、状态、物品显示、配方和来源指纹；[provenance.json](provenance.json)登记具体输出。游戏资源位于 `src/main/resources/`。

缝隙开口固定朝北，宽 10、高 8 模型单位，容纳家油的实际碰撞体。客户端根据方块实体库存显示垫料、藏食和 2–4 枚卵。卵由统一出生登记管理，孵化后显示真实幼体。

方块与物品使用 Minecraft 自带贴图和物品渲染：缝隙为橡木板，土冢为泥土，碎屑为面包图标，绷带为纸，草药为干海带，遗留物为收纳袋。它们是可玩的基础显示，尚未确认为独立标准美术。伤口和绷带由客户端在脸颊显示小片，不改变角色源模型。

最新实机图放在 [previews/current/](previews/current/)。规则、限制与验证见 [核心玩法记录](../../../docs/CORE_GAMEPLAY.md)。
