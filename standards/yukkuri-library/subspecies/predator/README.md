# 捕食种：蕾米莉亚与芙兰

制作源为 [species.json](species.json)、[author_species.py](author_species.py) 与 [species_geometry.py](species_geometry.py)；参考依据见[参考核读](REFERENCES.md)和[运行形态参考](REFERENCES-RUNTIME.md)。当前游戏选用模型为 `assembled/remilia/`、`assembled/flandre/`，输入与导出指纹见 [species-manifest.json](species-manifest.json)。

两种形态使用连续身体网格、软帽和翼膜。蕾米莉亚为蓝紫发、后置蝴蝶结与完整蝙蝠翼；芙兰为金发、侧置蝴蝶结，每侧翼挂四枚彩色晶体。每侧整翼绕一个翅根摆动，晶体另有悬挂关节。图集为 256×256，含基础表情和咬取表情；保留通用动作并增加飞行、俯冲、降落、咬取动作。身体采用同一圆润轮廓，油菊、互斥伸缩结构和通用行走骨架直接复用当前 `assembled/marisa`，输入指纹登记在清单；油菊使用独立图集区域避免与翼帽调色区域重叠。行走只摆动头部切片：面部皮肤锚点在不自带锚点轨道的片段里复制同枢轴切片的轨道，油菊、发冠、刘海与后发按身体高度连续加权（同魔理沙），翅根随所在高度的背部皮肤平移；侧发与芙兰马尾保留在 `hand_left`／`hand_right` 上供叼持片段使用。水生魔理沙沿用魔理沙身体。专用尺寸与动作参数以配方和生成器为准。

运行资源由 `scripts/export_predator_assets.py` 导出；它按 `.bbmodel` 每个元素自带的骨骼绑定导出，骨架的分组或顶点数与元素不一致时拒绝导出，须先重跑 `author_species.py`。统一入口 `scripts/export.py --changed` 在本包或共享魔理沙模型、输入、身体轮廓变化时先重跑 `author_species.py` 再导出下游。单独重建的顺序：

```powershell
python standards/yukkuri-library/subspecies/predator/author_species.py
python scripts/export_predator_assets.py
node scripts/export_wound_surfaces.cjs --ids=remilia,flandre
node scripts/export_placement_fit.cjs --update-only remilia,flandre
python scripts/export_motion_corrections.py --update-only remilia,flandre
python standards/yukkuri-library/subspecies/predator/preview_body.py
```

模型和参考页保留可编辑源、导出与依据；本说明不重复旧原型的参数、检查报告或返工过程。玩法规则见[捕食种实现](../../../../docs/FEATURES.md#捕食种)。
