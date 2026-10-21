# 驱油喷雾

`recipe.json` 与 `build.py` 定义原创原生物品模型：分段握持瓶身、窄肩、泵头、独立喷嘴、向下弯的扳机，以及正面的油库里脸／红色禁入标记。标记由原创几何组成，不取用原版物品图标。

现行物品继续使用 `mcyukkuri:household_guard`，让旧存档中的防护盒物品显示为喷雾。喷雾不放置方块；旧盒方块模型由 `house-guard` 制作源保留，仅用于存档兼容。

蓝灰瓶身、深灰接头、白色标签、红色标记和黑色字形使用原版混凝土表面纹理，不复制原版纹理进模组资源。

导出：`python standards/yukkuri-library/ecology/house-repellent/build.py`，核对加 `--check`。选用配方与来源清单导出至 `art/source/house-repellent/`，运行资源是 `models/item/household_guard.json`。

`house-nest/render_supplies.py` 读取当前模型，使用本目录的 `preview.py` 软件渲染。当前主题输出为 `.local/previews/house-supplies/`，包括生态总图与 `repellent-spray.png` 喷雾／范围示意。原版材质来自本机资源 JAR，可由 `MCYUKKURI_VANILLA_JAR` 指定。无需启动游戏、浏览器或编译。

示意说明：对可交互方块喷一次，水平 ±8 格、垂直 ±4 格保护 72000 游戏 tick；正常 20 TPS 下为 60 分钟，即 3 个 Minecraft 日。每瓶 16 次；潜行右键查询覆盖与剩余时间不耗次数。行为与计时由服务端实现，模型预览不代表游戏测试。
