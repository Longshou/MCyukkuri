# 驱油喷雾

驱油喷雾是普通的原版物品：物品模型是 `item/generated`，贴图就是它的 16×16 物品栏图标（[物品栏图标](../../drafts/item-icons/draw.py) `household_guard`，由 `scripts/export_item_icons.py` 出到 `textures/item/`）。手持、掉落和展示框都按原版物品显示，没有自己的几何，也不引用原版纹理。

现行物品继续使用 `mcyukkuri:household_guard`，让旧存档中的防护盒物品显示为喷雾。喷雾不放置方块；旧盒方块模型由 `house-guard` 制作源保留，仅用于存档兼容。

导出：`python standards/yukkuri-library/ecology/house-repellent/build.py`，核对加 `--check`。`recipe.json` 登记物品模型与图标；选用配方与来源清单导出至 `art/source/house-repellent/`，运行资源是 `models/item/household_guard.json`。

`house-nest/render_supplies.py` 用本目录的 `preview.py` 把图标挤成一像素厚的薄片做软件渲染。当前主题输出为 `.local/previews/house-supplies/`，包括生态总图与 `repellent-spray.png` 喷雾／范围示意。无需启动游戏、浏览器或编译。

示意说明：对可交互方块喷一次，水平 ±8 格、垂直 ±4 格保护 72000 游戏 tick；正常 20 TPS 下为 60 分钟，即 3 个 Minecraft 日。每瓶 16 次；潜行右键查询覆盖与剩余时间不耗次数。行为与计时由服务端实现，模型预览不代表游戏测试。
