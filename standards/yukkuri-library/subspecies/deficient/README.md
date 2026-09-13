# 先天不足形态制作源

配方为 [recipes.json](recipes.json)，制作与接入脚本为 [export_subspecies.py](../../../../scripts/export_subspecies.py)。限定灵梦与魔理沙的稀疏毛发、面部不对称、豆形细长三类，几何与参数均为待确认的三维适配。身体没有腿或人形胴体。种族、命名亚种、先天形态和后天伤口分别保存；多毛灵梦与豆形细长不叠加。

本包读取当前实体选用的可编辑快照与当前动作骨架，生成自己的 `assembled/` 与游戏接入快照。基础七种模型及行走曲线不由本包重写。

参考核读见 [REFERENCES.md](REFERENCES.md)。运行行为、来源指纹和验收结果记录于 `docs/SUBSPECIES_IMPLEMENTATION.md`，该页在接入完成时更新。技术审查不代替用户确认。
