# 油库里对白的 AquesTalk1 配音

台词来源见[对白说明](../../dialogue/README.md)，发声路线与《油库里日记》素材边界见[原声研究](../DIARY_AUDIO_RESEARCH.md)。制作参数保存在 [recipe.json](recipe.json)；[manifest.json](manifest.json) 按条记录台词来源、发音符号、时长、音频与工具指纹。

灵梦使用 AquesTalk1 `f1`，魔理沙使用 `f2`；成体话速／音程为 100／100，幼语为 100／125，开启平板重音。爱丽丝使用 `f3`（100／105／131），橙使用 `f1`（110／115／144），帕秋莉使用 `f1`（85／90／112）；新增角色参数为本项目候选，不代表八景 U 或《油库里日记》的公开声线设定。

母带和 OGG 分别存于 `masters/` 与 `ogg/`，格式为 32 kHz 单声道。制作会复用台词、发音、参数、工具和现有音频指纹一致的录音。`scripts/build_aquestalk_dialogue.py` 支持 `--ids-json` 只制作指定 ID，接受字符串数组或 `{"ids": [...]}`；支持用 `--player`、`--work-directory` 指定播放器和临时工作目录。完整台词库不传 `--ids-json`。

发音输入使用 `#>` 音标；长叫声中的重复小假名写成长音，「ぢ／づ」在发音输入中写为「じ／ず」，原版日文显示文本保持原样。声音文件、AquesTalkPlayer 与字典不进入模组资源。使用条件以[官方下载与许可说明](https://www.a-quest.com/products/aquestalkplayer.html)为准。
