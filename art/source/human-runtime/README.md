# 人类 NPC 运行素材

制作源是标准库的 `tools/author_human_npcs.py`，七套 64 × 64 原创像素贴图先写入 `textures/human/`，再同步标准库 `assembled/humans/textures/`、本目录 `textures/` 及游戏 `assets/mcyukkuri/textures/entity/human/`。本目录 `manifest.json` 列出每份贴图的源、目标与指纹。

几何源为 `src/main/java/io/github/longshou/mcyukkuri/client/HumanRenderer.java`，几何层标识 `mcyukkuri:human#main`，复用 Minecraft `HumanoidModel` 的头、帽层、身体与四肢骨架。大姐姐的 `long_hair` 与鬼威惨的 `mohawk` 均挂在头骨上；莫西干头包含中央竖起的发脊与两侧剃短贴图。动作使用原版行走、转头、挥臂，另有站立休息姿态；没有单独的 GeckoLib 几何或动画文件。

角色登记见 [humans.json](../../../standards/yukkuri-library/characters/humans.json)，来源及确认状态见 [human-npcs.json](../../../standards/yukkuri-library/provenance/human-npcs.json)。角色没有下载玩家皮肤，也没有复制漫画或八景 U 的美术；当前为已接入、待用户造型确认，不能视为已确认标准。

店主、考官、饲养员三套职员贴图由 `tools/author_staff_skins.py` 绘制（头、帽层、身体与四肢逐面绘制；原版 HumanoidModel 没有外套层，体积只靠帽层），待用户造型确认。

统一导出 `python scripts/export_human_world.py`。
