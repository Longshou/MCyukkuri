# 油库里对白的 AquesTalk1 配音

共 402 条，246 条原版与 156 条项目原创分别标注；合成脚本同时在本目录生成试听页 `index.html`（本机输出，不入库）。台词来源见 [对白说明](../../dialogue/README.md)。

用户提供本机 AquesTalkPlayer 后，使用程序内置「れいむ」对应的 AquesTalk1 f1 与「まりさ」对应的 f2 制作。成体话速／音程为 100／100，幼语为 100／125；开启平板重音。幼语台词来自八景 U 原表，音程参数是本项目候选，不声称还原《油库里日记》的未公开设置。

新增角色使用以下候选参数；它们不是八景 U 或《油库里日记》公开的角色参数。橙与帕秋莉缺失事件的幼语由本项目补写，并标注原创。

| 角色 | 声种 | 话速 | 成体音程 | 幼语音程 |
| --- | --- | --- | --- | --- |
| 爱丽丝 | f3 | 100 | 105 | 131 |
| 橙 | f1 | 110 | 115 | 144 |
| 帕秋莉 | f1 | 85 | 90 | 112 |

[recipe.json](recipe.json)保存参数，[manifest.json](manifest.json)保存每条台词、来源类型、实际发音符号、时长、WAV／OGG 指纹和工具指纹。总计约 570.74 秒。母带与 OGG 均为 32 kHz 单声道；引擎输出经过窗函数 sinc 插值，峰值只向下限幅至 −3 dBFS，保留语音动态。全部导出均解码检查了时长、通道、非静音、有限数值与峰值。

合成使用 `#>`发音符号输入。长叫声中的重复小假名改成引擎支持的长音；「ぢ／づ」在发音输入中写成「じ／ず」。原版日文显示文本不做这些拼写替换。

`masters/`和`ogg/`是项目制作输出。水上情景的工具副本在 `.local/dialogue-aquatic/tool/aquestalkplayer/`，可用合成脚本的 `--work-directory` 指定独立临时目录；不修改用户下载目录，不将程序、字典、DLL 或《日记》原声打入模组。台词、发音、角色参数、引擎和已有文件指纹匹配时复用录音；本轮保留此前 288 条录音，新增 50 条水上情景配音；魔理沙成体／幼体共 34 条，灵梦、爱丽丝、橙、帕秋莉共 16 条。

需要只制作新增台词时，给 `scripts/build_aquestalk_dialogue.py` 传入 `--ids-json`。清单为 ID 字符串数组，也接受 `{"ids": [...]}`；例如本轮水上事件清单 `.local/aquatic-dialogue-web/selected-ids.json`。在仓库根目录执行：

```powershell
python scripts/build_aquestalk_dialogue.py --player ".local/tools/aquestalkplayer/AquesTalkPlayer.exe" --work-directory ".local/dialogue-aquatic" --ids-json ".local/aquatic-dialogue-web/selected-ids.json"
```

此选项只让清单中的台词进入合成与缓存流程；未选中的已有 WAV／OGG 不读取校验或重写，已有 manifest 条目合并保留，试听页继续列出库内已配音台词。部分导出的新录音在各自条目记录本次工具与引擎指纹，顶层旧指纹继续对应未处理的旧录音。不传 `--ids-json` 时仍按原方式处理完整台词库。空清单、重复或未知 ID、未定义的 `species.age` 声线都会在修改工具副本前明确报错，不替换为其他年龄或角色。制作参数继续直接读取 `recipe.json`。

工具使用条件以下载包及[官方说明](https://www.a-quest.com/products/aquestalkplayer.html)为准：个人非营利用途可免费使用，其他用途适用对应使用许可。此处记录生成工具，不把声音或八景 U 原句标为第三方开源素材；没有推定项目持有商业许可。

当前状态：已实装，待听审。此前被否决的 Mei 首轮包已删除。
