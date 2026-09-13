# N051–N062 组件复用批次

本批十二项已全部确认保存，包括[第三轮泪花 A/B 差分](round3/README.md)。当前进入 [N063–N074 下一批](../reuse-batch-63-74/README.md)，全部表情剩余 23 项待确认。下表保留首轮复用来源；正式选择及指纹以确认登记为准。

| 编号 | 复用与保留 |
| --- | --- |
| N051/N052 | N001 睁眼、N012 眉毛、N022 哭喊嘴、N024/N025 蓄泪与滑落 |
| N053 | N001 睁眼、N020 颤抿嘴，清除旧嘴周附加线 |
| N054 | O09 闭眼、O06 圆嘴；不带入口水 |
| N055 | O09 闭眼；偏侧咀嚼嘴与鼓腮保留原造型待核对 |
| N056/N057 | N014 挤眼、N029 生气眉、N024 泪花；N056 使用 O06 圆嘴，N057 保留偏侧咀嚼嘴与鼓腮 |
| N058 | N001 睁眼、N029 生气眉、N021 嘴、N034 潮红；组件已匹配，画面不变 |
| N059 | N027 白眼、N001 张嘴笑；组件已匹配，画面不变 |
| N060/N061 | N014 挤眼、N012 眉毛、N020 颤抿嘴、N024/N025 泪花；当前只核对静态表情，未制作发颤动作 |
| N062 | N027 白眼、N012 眉毛、N024 泪花；缺口哭喊嘴保留原造型待核对 |

`before.json` 保存本批原配方，`presets/` 是复用后的候选制作源，`textures/<角色>/` 是按六角色 UV 生成的配套图集。`reuse-manifest.json` 记录 113 个已确认供体槽位的来源与指纹，以及 7 个保留的特殊槽位。泪花沿各眼型可见下缘挂接，PNG 不重画。N055/N057 的特殊槽位包括嘴和左右鼓腮，N062 包括嘴。

项目根目录重建：

```powershell
python standards/yukkuri-library/tools/differentiate_expression_sheen.py
python standards/yukkuri-library/tools/build_reused_expression_review.py
```

[当前前后对照页](../next-batch.html)支持魔理沙、灵梦同步比较与旋转；[正面总览](../../../previews/current/pending-batch.png)、[斜侧面总览](../../../previews/current/pending-batch-oblique.png)来自本轮模型导出。数据和浏览器检查见[当前报告](../../../reports/current/expression-component-reuse.json)。检查不代替用户确认，Minecraft 运行资源未改动。
