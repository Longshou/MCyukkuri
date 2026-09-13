# 消耗品选用与导出

本包接入酸奶饮品、兴奋药、幼语药、助眠喷雾、超辣油粮和生殖用馅六件原创道具，并为清水、酸奶和两种口服药提供四种一口份量。造型状态为待用户确认的游戏适配，不沿用既有角色标准的确认指纹。

制作入口是 [build.py](../../../standards/yukkuri-library/drafts/consumables/build.py)，配方与形状参数保存在同目录 `recipe.json`，可编辑模型和三角网格导出位于 `assembled/<id>/`。`selected/<id>/` 保存本次选用的 `.bbmodel`、`.mesh.json` 与贴图，`manifest.json` 记录制作脚本、基础几何工具和全部输出的 SHA-256。

| 运行内容 | 路径（相对项目根） | 挂接与动画 |
| --- | --- | --- |
| 道具网格 | `src/main/resources/assets/mcyukkuri/yukkuri_props/<id>.json` | 16 单位为一格，无独立骨架 |
| 道具贴图 | `src/main/resources/assets/mcyukkuri/textures/props/<id>.png` | 原创配色条，网格 UV 选择色块 |
| 物品入口 | `src/main/resources/assets/mcyukkuri/models/item/<id>.json` | `PropModels` 动态渲染六件道具 |
| 合成 | `src/main/resources/data/mcyukkuri/recipe/<id>.json` | 游戏适配配方；一份产物 |
| 口中份量 | 四件 `*_serving` 网格及贴图 | 使用当前模型的嘴部挂点，与孕体变形同步 |
| 生殖投药 | `reproductive_filling` 网格及贴图 | `LiveBirthRenderer.renderDose` 将管嘴朝向后部；短时操作由服务器控制 |

原生机制核读与 Minecraft 适配区别、普通胎生的角色模型/变形来源及验收见 [消耗品与胎生实现](../../../docs/CONSUMABLES_AND_BIRTH.md)。清水仍使用原版真实清水药水瓶；其他六件物品没有借用不相符的原版图标。

重新导出使用 `python standards/yukkuri-library/drafts/consumables/build.py`；多人协作时先协调语言文件，或加 `--skip-language`。`--check --skip-language` 只核对制作源与导出一致性。

最新 [实机画廊](previews/current/index.html) 包含道具、口中份量、孕体多角度和真实投药/双胎出生帧序列；[采集说明](previews/current/README.md) 记录运行快照、检查结果与待确认范围。
