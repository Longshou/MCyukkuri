# 八景 U 原版对白与游戏接入

[原版对白检索](native-u/index.html)按种族、成体／幼语、原始场景和行号查询。原版程序集与 CSV 指纹见 [来源清单](native-u/sources.json)。八景 U 的加载器为幼体读取独立的 `sei_serifu.csv`；幼语应保留原表文本，不由现代日文或中文自动改写。

游戏选句源为 [runtime-lines.json](runtime-lines.json)，项目原创条目与角色补写见 [species-original.json](species-original.json) 和 [speech-tiers.json](speech-tiers.json)。来源类型、字幕键和声音键与原版分开登记；完整检索索引不进入模组。英文、日文和中文文本均由选句源分别维护。

## 导出

从仓库根目录运行：

```powershell
python scripts/export_dialogue.py
python scripts/export_dialogue.py --check
```

原版检索数据由 `scripts/catalogue_hakkei_dialogue.py` 从只读来源目录生成；配音由 `scripts/build_aquestalk_dialogue.py` 按 `art/audio/aquestalk/recipe.json` 制作。已配音的日文文本必须与录音台词保持一致，导出器会拒绝清单不一致的录音。未配音条目只导出字幕。

运行资源包括 `dialogue/catalog.json`、`lang/*.json`、`sounds.json` 与对白 OGG。导出器只更新本任务拥有的对白键；共享运行资源的其他条目由对应资源包维护。

## 播放规则

服务器选择台词并同步台词键、起止 tick 和递增序号。每个实体只保留一句当前台词；较高优先级可打断，重复或较低优先级不会重启当前句。结束后保留短间隔。文字时长按声音长度加 15 tick 计算，最短 45 tick。台词不写入存档，不向聊天栏广播；选句使用独立随机源，不改变实体行动的随机序列。

客户端在 tick 中响应新序号并按实体位置播放；打断、死亡、静音、离开世界或结束时停止。新进入跟踪范围时不重播已开始超过 5 tick 的旧句。渲染次数不会触发声音。气泡与名字标签分开，最多显示 4 行；距离超过 16 格、实体隐身或死亡、HUD 隐藏时不显示。

可用 `/yukkuri say @e[type=mcyukkuri:reimu,limit=1,sort=nearest] hello` 演示已登记事件；实际进食、入睡和如厕结束也会触发对白。原创对白和声音的源、角色适用范围及状态分别见选句源与录音清单。
