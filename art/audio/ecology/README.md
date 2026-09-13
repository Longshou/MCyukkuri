# 日常哼唱

两段 8 秒、单声道原创短旋律，使用本机 AquesTalk1 的 f1／f2 声线产生无词音节，再按[配方](recipe.json)排列音高、时值和淡入淡出。没有复制小说歌词、既有歌曲旋律或游戏原录音。来源工具与产物指纹见[清单](manifest.json)。

普通灵梦及其他当前非魔理沙角色采用 f1 哼唱，魔理沙采用 f2；这是本项目的暂定声线分配。幼体播放音程稍高。两段旋律均是待听审的游戏适配，不能称为原作固定的“油库里之歌”。

WAV 为本目录母带，OGG 位于 `src/main/resources/assets/mcyukkuri/sounds/ecology/`；工具副本与原始单音节位于项目 `.local/ecology/audio/`。制作脚本只合并 `ecology.song.*` 两个声音条目，不替换原版对白目录。

```powershell
.local/audio-env/Scripts/python.exe scripts/build_ecology_audio.py --player 'E:/yukkuri/工具/aquestalkplayer/AquesTalkPlayer.exe'
.local/audio-env/Scripts/python.exe scripts/build_ecology_audio.py --check
```

客户端以同步的 `sing` 开始时刻播放一次；离开唱歌状态、死亡、静音、卸载或退出世界即停止。后进入跟踪范围不补播一段已经开始的歌。具体实机核对见[日常生态验收](../../../docs/ECOLOGY_DAILY_LIFE.md)。
