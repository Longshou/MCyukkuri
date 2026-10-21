# 螺壳魔理沙标准模型

[可旋转预览](assembled/marisa_tsumuri/index.html) · [BBModel](assembled/marisa_tsumuri/marisa_tsumuri.bbmodel) · [几何 JSON](assembled/marisa_tsumuri/marisa_tsumuri.geo.json) · [动画 JSON](assembled/marisa_tsumuri/marisa_tsumuri.animation.json)

`marisa_tsumuri` 造型为六层圆润塔形卷壳、八枚凸刺，左右戴正并后倾 25°；壳后缘刚好盖到油菊上方，常态身体保持标准比例。当前挂接以生成的合并骨架为准，来源指纹见[标准体型来源登记](provenance/standard-body.json)。

编辑入口为 `characters/marisa_tsumuri.json`，制作配方为 `body-standard/recipe.json`，组件与骨架位于该包。`components/marisa-tsumuri/` 保留早期壳体制作资料。魔理沙当前采用[单辫造型](body-standard/features/marisa-single-braid/README.md)，壳形不变。512×512 图集保留原有壳材质与表情，并分配圆润身体肤色和后部标记区域。

麻姆麻姆、佩尼佩尼使用 `body-standard/anatomy.json` 与生成的合并组件、骨架，挂在同一前下方位置。默认缩回，按钮伸出后保持，切换时先缩回当前部位，再伸出另一部位。暂停动作时也可操作。两部位随身体进入壳内，控制接口与普通标准相同，见 [伸缩部件说明](ANATOMY.md)。

模型提供标准待机，以及静止、螺壳轻摆、缩入 / 探出三个形态片段；另外导出四个部件伸缩片段。缩入时保留身体宽度，高度压至 23%、纵深收至 78%，随后恢复标准比例。缩入时两侧头发后仰20°并略向外张开 6°（单辫较长较粗，内收会穿过压扁的身体）；动作源为 `motions/variants/marisa-tsumuri/`，经 `body-standard/features/marisa-single-braid/sync_inputs.py` 写入冻结输入。其他普通种动作尚未作壳体适配，不列入此模型的动作菜单。

从仓库根目录运行 `python standards/yukkuri-library/build.py` 重建标准库。

本目录是后续编辑和复用源；游戏已选用螺壳模型快照，选用源、快照与运行 JSON 见[运行来源登记](../../art/runtime-sources.json)。标准库修改后须重新选用导出，才会更新游戏资源。
