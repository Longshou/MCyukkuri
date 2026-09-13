# 螺壳魔理沙标准模型

[可旋转预览](assembled/marisa_tsumuri/index.html) · [BBModel](assembled/marisa_tsumuri/marisa_tsumuri.bbmodel) · [几何 JSON](assembled/marisa_tsumuri/marisa_tsumuri.geo.json) · [动画 JSON](assembled/marisa_tsumuri/marisa_tsumuri.animation.json)

当前确认造型已登记为 `marisa_tsumuri`：六层圆润塔形卷壳、八枚凸刺，左右戴正，后倾 25°，原确认挂接点为 `[0, 5.9, 1.75]`；2026-09-13 已随[圆润标准体型](body-standard/README.md)适配挂接，当前数值以合并骨架为准。后缘刚好盖到油菊上方一点。常态身体保持标准比例。

编辑入口为 `characters/marisa_tsumuri.json`，当前制作配方为 `body-standard/recipe.json`，组件与骨架位于该包。`components/marisa-tsumuri/` 保留推广前壳体制作资料。当前 512×512 图集保留原有壳材质与表情，另分配圆润身体肤色和后部标记区域。

麻姆麻姆、佩尼佩尼使用 `body-standard/anatomy.json` 与生成的合并组件、骨架，挂在同一前下方位置。默认缩回，按钮伸出后保持，切换时先缩回当前部位，再伸出另一部位。暂停动作时也可操作。两部位随身体进入壳内，控制接口与普通标准相同，见 [伸缩部件说明](ANATOMY.md)。

模型提供标准待机，以及静止、螺壳轻摆、缩入 / 探出三个形态片段；另外导出四个部件伸缩片段。缩入时保留身体宽度，高度压至 23%、纵深收至 78%，随后恢复标准比例。其他普通种动作尚未作壳体适配，不列入此模型的动作菜单。

运行本目录的 `python build.py` 重建；当前验证命令见 [圆润标准体型](body-standard/README.md)。原 `validate.py` 与旧螺壳断言面向推广前方块结构，不能证明新版通过。

本目录是后续编辑和复用源，`yukkuri-v1/subspecies` 保留制作过程中的三种外形对照。当前交付为标准模型资产，尚未接入 Minecraft 生物实体。
