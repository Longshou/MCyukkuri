# 先天不足形态制作源

2026-09-14：[稀毛与歪脸灵梦](rounded-reimu/README.md)已补齐圆润身体、贴体发束与灵梦蝴蝶结，连同两种魔理沙共四种采用圆润体态。豆形继续保留其专属身体。

2026-09-13：按用户追加纠正，[稀毛与歪脸魔理沙](rounded-marisa/README.md)已切换圆润体态，保留各自的稀毛与五官不对称特征，并同步两种游戏资源、伤口与帽饰。使用 `python scripts/export_marisa_deficient_rounded.py` 定向重建；两种的新造型待确认。其余形态继续遵循下方各自来源。

当前豆油另已修复行走时左右眼和半边嘴被身体遮住的问题。[bean_face.py](bean_face.py) 将五官挂点按其高度匹配身体相邻分段的运动，保留五官子骨控制；参数见 `bean.face_fit`。重建仍用 `python scripts/export_bean_assets.py`。当前检查见[动态表情报告](reports/current/FACE-MOTION.md)、[数值结果](reports/current/face-motion.json)及[正面行走动图](previews/current/face-motion.gif)。之前的静态四向实机检查不能证明移动时不遮脸。

配方为 [recipes.json](recipes.json)，制作与接入脚本为 [export_subspecies.py](../../../../scripts/export_subspecies.py)。限定灵梦与魔理沙的稀疏毛发、面部不对称、豆形细长三类，几何与参数均为待确认的三维适配。身体没有腿或人形胴体。种族、命名亚种、先天形态和后天伤口分别保存；多毛灵梦与豆形细长不叠加。

本包读取当前实体选用的可编辑快照与当前动作骨架，生成自己的 `assembled/` 与游戏接入快照。基础七种模型及行走曲线不由本包重写。

2026-09-13 豆油头发修订：两种豆油使用 [bean_hair.py](bean_hair.py) 按原豆形身体截面制作连续发顶、刘海与侧束，发顶和发束使用身体分段权重；灵梦蝴蝶结、魔理沙帽子同步调整挂点。原身体顶点、UV 与权重保持不变。参数在 `recipes.json` 的 `bean.hair_fit`，定向重建命令为 `python scripts/export_bean_assets.py`；不要运行会覆盖其他形态的旧全量导出。源模型、骨架和游戏快照指纹见 [hair-fit-manifest.json](hair-fit-manifest.json)。本次新增造型待用户确认。

[最新 20 形态预览](../../body-standard/previews/current/all-models/index.html?character=reimu_bean) · [本轮四模型展示](../../body-standard/previews/current/all-models/bean-fangs.png) · [检查记录](reports/current/hair-fit.json) · [修订说明](reports/current/HAIR-FIT.md)。两豆油共 950 个动作采样通过；本任务未启动 Minecraft，实际游戏复验由接入任务执行。

参考核读见 [REFERENCES.md](REFERENCES.md)。运行行为、来源指纹和验收结果记录于 `docs/SUBSPECIES_IMPLEMENTATION.md`，该页在接入完成时更新。技术审查不代替用户确认。

2026-09-13 追加辫子与帽子返工：侧束外表面原有反向三角面，且埋入身体较深；现改为封闭的双股曲面、环绕发带与收尖发尾，修正面朝向和外露位置。魔理沙帽子与挂点下移 0.62 模型单位，使帽檐贴住发顶。当前两种豆油 14,400 个动态表情样本无意外遮挡。[侧面行走拼图](previews/current/braid-hat-motion.png) · [本轮详细记录](reports/current/BRAID-HAT.md)。先前实机结论仅对应此前模型；当前修订已通过对应指纹的实机复验，见[实机记录](../../../../docs/validation/bean-braid-fangs/current/README.md)及当前检查 JSON 的实机字段。造型仍待用户确认。
