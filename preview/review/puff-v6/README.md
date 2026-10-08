# 第六版鼓腮归档

2026-10-07 第六版鼓腮的预览、检查记录和制作源快照，保留用于追溯，已由第七版替代，不作为当前造型或验收依据。

第六版用解析形变把两颊横向推出约半个身宽，侧发、发带和发梢按皮肤位置一起外推，表情为常态眼睛加猫嘴。用户随后再次要求“符合漫画表现的同时，不要穿模”；离线复查可见下半脸摊成扁平的环、侧发呈翅膀状。

| 内容 | 文件 |
| --- | --- |
| 预览 | [五视图](review.png)、[19种形态](all-models.png)、[侧面视角](contact-angles.png)、[单体动图](puff.gif)、[连续动作](face-stability.gif) |
| 当时的验收记录 | [acceptance.md](acceptance.md) 及同目录 JSON |
| 制作源快照 | [source/](source/)：`author.py`、`recipe.json`、导出器、`pose.js`、`YukkuriModel.java`（改名为 `.txt`）与清单 |

快照只用于对照，不能直接恢复运行：第七版的运行资源为第2版格式，游戏端已不读取第六版的解析形变参数。当前版本见[游戏适配记录](../../../art/source/motion-corrections/README.md)。
