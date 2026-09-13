# 捕食种运行选用

[模型与动作交互预览](preview.html)读取当前运行导出的三角面、两骨混合权重与动画数据。可选择蕾米莉亚／弗兰、表情、通用动作及四段飞行动作，拖动转向、暂停逐帧查看。它是独立渲染器，游戏验收另见[捕食种实现](../../../docs/PREDATOR_IMPLEMENTATION.md)。

制作源在 `standards/yukkuri-library/subspecies/predator/`，选定的可编辑 `.bbmodel`、骨架、运行 JSON 与贴图快照放在相邻 `standard-runtime/remilia/`、`standard-runtime/flandre/`。本目录只保存当前两种的预览数据与来源索引，不覆盖七种既有模型。

按顺序运行 `author_species.py`、`scripts/export_predator_assets.py`、`art/props/predators/build.py`、`scripts/export_badges.py --mounts-only`、`scripts/export_wound_surfaces.cjs --ids=remilia,flandre` 、`standards/yukkuri-library/drafts/operation-tools/build.py` 与 `scripts/preview_predators.py`，同步源、运行模型、离身帽饰、徽章挂点、伤口表面、器具适配和预览。造型待确认；当前导出指纹见 [provenance.json](provenance.json)。
