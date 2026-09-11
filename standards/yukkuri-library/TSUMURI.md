# 螺壳魔理沙标准模型

[可旋转预览](assembled/marisa_tsumuri/index.html) · [BBModel](assembled/marisa_tsumuri/marisa_tsumuri.bbmodel) · [几何 JSON](assembled/marisa_tsumuri/marisa_tsumuri.geo.json) · [动画 JSON](assembled/marisa_tsumuri/marisa_tsumuri.animation.json)

当前确认造型已登记为 `marisa_tsumuri`：六层圆润塔形卷壳、八枚凸刺，左右戴正，后倾 25°，挂接点 `[0, 5.9, 1.75]`。后缘刚好盖到油菊上方一点。常态身体保持标准比例。

编辑入口为 `characters/marisa_tsumuri.json`；壳的几何与骨架位于 `components/marisa-tsumuri/`。身体、油臀、五官、金发、辫子及伸缩部件引用共用标准。256×256 图集左上角沿用标准材质，右侧为壳材质；共用表情按标准绑定装配。

麻姆麻姆、佩尼佩尼共用 `components/common/anatomy.json`、`anatomy-rig.json` 和 `behaviors/anatomy.json`。默认缩回，按钮伸出后保持，切换时先缩回当前部位，再伸出另一部位。暂停动作时也可操作。两部位随身体进入壳内，控制接口与普通标准相同，见 [伸缩部件说明](ANATOMY.md)。

模型提供标准待机，以及静止、螺壳轻摆、缩入 / 探出三个形态片段；另外导出四个部件伸缩片段。缩入时保留身体宽度，高度压至 23%、纵深收至 78%，随后恢复标准比例。其他普通种动作尚未作壳体适配，不列入此模型的动作菜单。

运行本目录的 `python build.py` 和 `python validate.py` 可重建并检查。浏览器检查为项目根目录的 `node tools/verify-standard-anatomy.cjs` 和 `node tools/verify-tsumuri-standard.cjs`。

本目录是后续编辑和复用源，`yukkuri-v1/subspecies` 保留制作过程中的三种外形对照。当前交付为标准模型资产，尚未接入 Minecraft 生物实体。
