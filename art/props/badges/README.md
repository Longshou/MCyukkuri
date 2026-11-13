# 徽章挂接与图标

漫画与小说依据见 [徽章来源](../../../wiki/badges.md)；实际挂点和尺寸是项目适配。运行单位为 16 模型单位／格。

挂点配方为 [mounts.json](mounts.json)，由徽章导出器按当前角色网格保存饰物部件、三角面、重心权重、表面法线和牌面尺寸。圆润等派生形态由附件导出器适配。模型指纹不匹配时游戏停用对应挂点，避免将旧三角编号用于新网格。此适配不改写角色模型、骨架、动画或标准确认指纹。

16×16 铜、银、金、钻石四档徽章像素由 [`author_badges.py`](author_badges.py) 生成，四档都是中央刻着油库里头像的圆章：铜为暗色单边圆盘，银为双边圆牌，金为齿边圆盘，钻石为冰蓝宝石色圆盘（与铜、金同为 14 像素宽），外圈切成八个明暗交替的刻面，左上一个白色闪光。钻石章沿用内部等级 id `platinum`（等级枚举、物品 ID、贴图文件名与语言键），旧存档与命令不受影响，只换外观与显示名。同一张图用于物品、佩戴贴花、商店与菜单；商店与档案里的 9×9 等级小章另在界面图集中按同样形状绘制。`scripts/export_badges.py` 复用同一图案和色板制作物品、佩戴贴花及手帖图标。离身饰物保存外观级别，圆牌按其自身表面挂接；该数据不授予资格。角色模型更新后，应重新导出徽章挂点。

改了图案或色板后用 `scripts/export.py --family human-world --family source:art/props/ui/author_shop_ui.py` 重出物品／佩戴贴图与界面图集；`python art/props/badges/preview.py --model <模型> --tiers bronze,silver,gold,platinum --out <目录>` 离线渲染各档佩戴效果。从仓库根目录运行 `python scripts/export_badges.py`；捕食种专用挂点更新可使用 `--mounts-only`。制作参数以 `mounts.json` 和 `author_badges.py` 为准，来源标准见[徽章来源](../../../wiki/badges.md)。

玩法规则见[宠物店](../../../docs/FEATURES.md#宠物店徽章与宠物培育)。
