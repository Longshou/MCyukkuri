# 豆油头发贴合修订

后续发现的豆油行走遮眼／遮嘴问题已另行修复，当前动态表情检查见 [FACE-MOTION.md](FACE-MOTION.md)。本页说明首次贴合修订背景，追加的立体辫子、反向面与帽子修订见 [BRAID-HAT.md](BRAID-HAT.md)；下方 JSON 与预览现已刷新为当前版本。

2026-09-13，用户指出豆油灵梦、豆油魔理沙的头发与身体不适配。侧视复现为旧发帽沿用普通体型的深度，后发悬在收窄身体之外；发束与末端分别缩短也留下断层。此前背发修复未改豆油几何，其检查结论不能证明本问题已修复。

制作源为 [bean_hair.py](../../bean_hair.py) 和 [recipes.json](../../recipes.json)。连续发帽沿豆油实际截面包覆，刘海根部与发顶重叠，侧束改为连续曲面；配套调整帽子深度与蝴蝶结挂点。发帽、刘海、侧束及其绑带按身体高度使用相邻两段骨骼权重。原身体三角面、UV、法线和权重逐项与修前一致。

定向导出为 `python scripts/export_bean_assets.py`，同时写入标准源的 BBModel／骨架、两套选用快照与游戏模型。原 `export_subspecies.py` 全量重建保护保留，其他不足种、脸油、螺壳与多毛不由该命令重建。豆油沿用当前选用的 16 张贴图，导出会检查快照和运行贴图指纹一致。

本轮在 Windows、Python 3.13、Node.js 和 Chrome 上检查。源／导出／快照一致性、元素顶点计数、权重范围通过；两种各 19 段动作、每段 25 个时间点，共 950 个有限数值采样通过。当前实际导出已检查正面、斜侧、侧面、背面与走路四相位，没有观察到旧矩形外壳悬空和分离发尾。浏览器验证模型切换、时间轴、背面和重置；数值采样仅证明数据有效，不等同所有姿态均无穿插。

证据为 [hair-fit.json](hair-fit.json)、[浏览器检查](../../../../body-standard/reports/current/bean-fang-gallery-browser.json)、[灵梦四视图](../../../../body-standard/previews/current/all-models/reimu_bean-views.png)和[魔理沙四视图](../../../../body-standard/previews/current/all-models/marisa_bean-views.png)。最新源和运行指纹以 [hair-fit-manifest.json](../../hair-fit-manifest.json) 为准。本任务未执行游戏实机验收；已将四模型与贴图交给接入任务。造型仍待用户确认。

首次贴合修订曾通过实机四方向检查；当前辫子与帽子改动已按新指纹完成针对性实机复验。最终实机状态见 [hair-fit.json](hair-fit.json)，造型仍待用户确认。
