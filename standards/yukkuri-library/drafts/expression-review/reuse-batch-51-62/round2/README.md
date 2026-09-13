# 第二轮：泪花与情绪修订

本页保留第二轮记录；当前状态与 A/B 区分见[第三轮](../round3/README.md)。

本轮修改 N051、N052、N056、N057、N058、N060、N061、N062；N053、N054、N055、N059 保持上一轮。十二项仍待确认，未将未点名项自动保存。

- N051/N052/N056/N057/N060/N061/N062 改用已确认的 N012 下眼缘泪花，并沿各自眼缘重新挂接。
- N056/N057 改为抬眉头、扁挤眼，分别配收窄哭喊嘴和紧绷波浪嘴，表达嫌难吃、勉强咽下。
- N058 复用 N039 下撇嘴，按当前表情缩至 0.8，表达不满。
- N060/N061 复用 N041 可爱眼型并压低为半睁，配 N038 紧绷波浪嘴，表达寒冷与无力。未制作动态发颤。

[原图与场景核读](REFERENCE.md)记录证据及适配边界。N051/N052、N060/N061 按要求采用同款泪花后各组同形，保留来源编号并登记重复配方关系。没有新增像素部件，正式配方及确认指纹未改动。

`before.json`、`before-manifest.json`、`before-overview.png`、`before-report.json` 保留首轮复用方案。当前候选源与六角色图集仍位于上一级 `presets/`、`textures/`。

项目根目录重建：

```powershell
python standards/yukkuri-library/tools/revise_expression_batch_51_62.py
python standards/yukkuri-library/tools/build_reused_expression_review.py
```

[当前模型对照](../../next-batch.html) · [正面总览](../../../../previews/current/pending-batch.png) · [斜侧面总览](../../../../previews/current/pending-batch-oblique.png)
