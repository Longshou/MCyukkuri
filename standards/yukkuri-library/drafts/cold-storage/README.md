# 冷冻收容柜制作源

本包是尚待用户确认的原创 Minecraft 适配方案。`recipe.json` 是几何、色板与来源说明的制作源；`build.py` 同步生成 `assembled/`、`art/props/cold-storage/selected/` 与实际游戏方块、物品、配方和掉落表。修改造型应先改配方，再导出并检查当前多角度预览。

模型为 16 单位一格的静态 Java 方块模型，不使用角色骨架，不覆盖任何已确认角色、表情或动画。末影珍珠对应个人跨柜收容库，四抽屉对应每位玩家的四个槽位；抽屉为固定外观，不伪装成动态占用灯或开门动画。

运行 `python standards/yukkuri-library/drafts/cold-storage/build.py` 制作，附加 `--check` 只读核对。选用关系和状态见[接入包](../../../../art/props/cold-storage/README.md)。
