# 消耗品选用与导出

本包接入酸奶饮品、兴奋药、幼语药、助眠喷雾、超辣油粮和生殖用馅六件原创道具及除霉剂，并提供清水、酸奶、两种口服药及新生食糜的口中份量。

制作入口是 [build.py](../../../standards/yukkuri-library/drafts/consumables/build.py)，配方与形状参数保存在同目录 `recipe.json`，可编辑模型和三角网格导出位于 `assembled/<id>/`。`selected/<id>/` 保存当前选用的 `.bbmodel`、`.mesh.json` 与贴图，`manifest.json` 记录制作脚本、基础几何工具和全部输出的 SHA-256。

| 运行内容 | 路径（相对项目根） | 挂接与动画 |
| --- | --- | --- |
| 道具网格 | `src/main/resources/assets/mcyukkuri/yukkuri_props/<id>.json` | 16 单位为一格，无独立骨架 |
| 道具贴图 | `src/main/resources/assets/mcyukkuri/textures/props/<id>.png` | 挤出道具直接复用 16×16 图标；口中份量使用图标色带像素图集 |
| 物品入口 | `src/main/resources/assets/mcyukkuri/models/item/<id>.json` | `PropModels` 动态渲染，沿用原版手持角度与 30° 固定展示 |
| 合成 | `src/main/resources/data/mcyukkuri/recipe/<id>.json` | 游戏适配配方；一份产物 |
| 口中份量 | 四件 `*_serving` 体素模型（方块风，图标色带） | 使用当前模型的嘴部挂点，与孕体变形同步 |
| 新生食糜 | `food_mash_serving` 网格及贴图（`care-props/small.py`） | 亲油加工后与幼油受喂时显示柔软小团；原食材、份数和营养保留，不注册另一份食品 |
| 生殖投药 | `reproductive_filling` 挤出网格及贴图 | `LiveBirthRenderer.renderDose` 在 0.75× 下将管嘴（`y=0`）朝向后部；短时操作由服务器控制 |

八件手持消耗品与散装袋（`mold_remover`、`yogurt_drink`、`stimulant`、`sitataran`、`sleep_spray`、`spicy_grain`、`reproductive_filling`、`wheat_flour`）均由各自 16×16 物品栏图标按原版 `item/generated` 挤出成一像素厚板（[方块道具工具](../../../standards/yukkuri-library/drafts/block-props/kit.py) `extrude`，保持原高度，正面朝 −Z；`spicy_grain` 不再由 `care-props/bags.py` 导出），贴图即图标本身；物品模型用原版手持角度，口中与展示框的固定展示转 30°，地上由掉落物自转。四件 `*_serving` 口中份量（`water_serving`、`yogurt_serving`、`stimulant_serving`、`sitataran_serving`）用 `kit.Block` 与图标色带重制为阶梯体素，尺寸保持在口部范围内。小麦粉糊在本包只保留合成配方，物品外观是 [16×16 图标](../supply-icons/README.md)，不使用网格。

原生机制核读与 Minecraft 适配区别、普通胎生的角色模型/变形来源及验收见 [消耗品与胎生实现](../../../docs/FEATURES.md#消耗品与胎生)。清水仍使用原版真实清水药水瓶；其他六件物品没有借用不相符的原版图标。

重新导出使用 `python standards/yukkuri-library/drafts/consumables/build.py`；多人协作时先协调语言文件，或加 `--skip-language`。`--check --skip-language` 只核对制作源与导出一致性。
