# 项目音频

| 目录 | 内容 |
| --- | --- |
| [aquestalk/](aquestalk/README.md) | 288 条 AquesTalk1 配音：204 条八景 U 原版、84 条明确标注的项目原创，已接入头顶气泡与实体语音；制作工具 `scripts/build_aquestalk_dialogue.py` |
| [ecology/](ecology/README.md) | 两段原创无词哼唱；制作工具 `scripts/build_ecology_audio.py` |
| `reference/` | 《油库里日记》原声的核查清单，见下文研究 |

台词来源见[原版对白整理](../dialogue/README.md)，扩展见[三种角色对白与配音](../../docs/DIALOGUE_IMPLEMENTATION.md)。声音均为待听审候选。

发声路线参考[《油库里日记》原声研究](DIARY_AUDIO_RESEARCH.md)；提取的原声只放本机 `.local/audio-reference/diary/`，不加入游戏资源。

首轮 OpenJTalk／HTS Voice Mei 方案（24 个动作音效、28 句语音）已被用户否决，母带、导出和制作工具已于 2026-10-15 删除，需要时可从 Git 历史找回。

## 检查依赖

在项目虚拟环境中安装音频脚本依赖，使用预编译包，不进行本机编译：

```sh
python3 -m venv .local/venv
.local/venv/bin/python -m pip install --only-binary=:all: -r art/audio/requirements.txt
.local/venv/bin/python scripts/build_ecology_audio.py --check
```

Windows 使用对应虚拟环境的 `Scripts/python.exe`。检查模式只读取现有母带和运行音频，核对指纹、采样率、声道和振幅；不需要 AquesTalkPlayer，也不重新合成声音。
