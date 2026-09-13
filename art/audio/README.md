# 项目音频

当前包是 [288 条 AquesTalk1 配音](aquestalk/README.md)，包括 204 条八景 U 原版与 84 条明确标注的项目原创，已接入头顶气泡与实体语音。入口为[新版试听](aquestalk/index.html)、[原版对白整理](../dialogue/README.md)和[本轮扩展记录](../../docs/DIALOGUE_SPECIES_IMPLEMENTATION.md)。声音为待听审候选。

## 已否决的首轮方案

以下为历史记录。上一版 **24 个动作音效、28 句日语语音已被否决并撤出游戏资源**，母带保留作错误对照。[旧试听页](index.html)仅用于对照错误方案；[《日记》研究](DIARY_AUDIO_RESEARCH.md)和[本机原声试听](../../.local/audio-reference/diary/index.html)保留查证依据。

动作音效采用本项目程序合成。灵梦与魔理沙均使用 OpenJTalk / HTS Voice Mei，通过音高、语速和台词区分；属于两套角色参数，并非两个独立声库，也不是经典 AquesTalk ゆっくり声线。未使用八景 U 游戏录音。

| 内容 | 位置与用途 |
| --- | --- |
| 台词与参数 | [recipe.json](recipe.json)：14 类对白、两套声线参数、12 类音效及随机种子 |
| 制作程序 | [build_audio.py](../../scripts/build_audio.py)：带限噪声、衰减振荡、混合、语音合成及导出 |
| 无损母带 | `masters/sfx/`、`masters/voice/<角色>/`：48 kHz、单声道、16-bit PCM WAV |
| 已撤回游戏导出 | `archive/mei-first-pass/runtime/sounds/`：48 kHz、单声道 Ogg Vorbis，共 52 个文件；不打包 |
| 历史声音索引 | `archive/mei-first-pass/sounds.json`：原 40 个事件；运行索引中的对应事件已撤下 |
| 来源与检查指纹 | [manifest.json](manifest.json)：源与导出映射、SHA-256、时长、峰值、RMS、制作环境和声库指纹 |
| 授权记录 | [LICENSE-Mei.txt](LICENSE-Mei.txt)、[VOICE-NOTICE.txt](VOICE-NOTICE.txt)；随旧导出一起归档 |

对白为本项目编写的日语短句，沿用通用欢迎语及「むーしゃ」「のぜ」等口吻，参考项目 [对白资料](../../wiki/hakkei-reference/dialogue/README.md)，不标作原作台词。当前只制作成体灵梦与魔理沙，尚无独立幼体或家油声线。

## 历史制作环境

以下记录上一版的环境和命令。默认生成入口现已停用，防止把被否决的 Mei 声线重新写入游戏；`--check`仅验证旧 WAV／OGG 指纹和撤回状态。当前流程见 [AquesTalk 配音](aquestalk/README.md)。

使用 Python 3.13，依赖固定于 [requirements.txt](requirements.txt)。本轮使用项目内 `.local/audio-env/`，不更改系统 Python 包。新环境可执行：

```powershell
python -m venv .local/audio-env
.local/audio-env/Scripts/python.exe -m pip install -r art/audio/requirements.txt
.local/audio-env/Scripts/python.exe scripts/build_audio.py
.local/audio-env/Scripts/python.exe scripts/build_audio.py --check
```

原制作程序及配方保存在 [归档](archive/mei-first-pass/README.md)。新制作方法须保留独立来源和参数，不能沿用原始文件指纹。

本轮 `soundfile` 0.12.1 使用 libsndfile 1.2.0；不同编码库版本可能产生不同的压缩文件指纹。`manifest.json` 记录实际制作环境。合成器未启用可选的 ONNX「何」读音预测，台词不依赖该功能。

## 接入与验收边界

旧文件与对应声音事件已撤出游戏资源；首轮没有实现行为触发、语音间隔或成长阶段选音。当前实现已另行接入原版台词与 AquesTalk1 配音。

上一版自动检查曾覆盖全部 52 对母带与导出的解码、声道、采样率、非静音、峰值余量、首尾静音、压缩前后波形相关性、文件指纹及 40 个事件的路径引用。这些检查不验证声线是否符合油库里，也不能支持继续采用旧方案。

上一版资源打包和浏览器播放检查曾通过，随后被用户否决。它们属于历史技术检查，不作为当前音频验收。

上一版已被用户否决。《日记》参考页播放原始样本，尚未完成其逐句听写或原声参数复原；新方案的 Minecraft 播放检查单独记录。
