# 标准美术素材库

本目录是可复用美术源和导出入口；游戏当前选用的是 `art/` 中的快照，见 [art/README.md](../../art/README.md)。修改标准源不会自动更新运行资源。

| 主题 | 制作源与权威登记 |
| --- | --- |
| 角色与部件 | [`characters/`](characters/) 登记角色组件、骨架与表情资源；`components/`、`rigs/`、`bindings/`、`textures/` 保存源文件 |
| 圆润标准身体 | [`body-standard/`](body-standard/README.md) 的配方与角色专属输入生成合并模型；排除范围见该页 |
| 表情 | [`expressions/`](expressions/) 保存部件与配方；89 项已确认集合和文件指纹以 [`provenance/standard-expressions.json`](provenance/standard-expressions.json) 为准，N033 默认采用 A，备选见 `expressions/variants/dull/index.json` |
| 动作 | [`motions/`](motions/) 保存单骨架基础与复合配方；茎生和胎生等多角色动作由 [`motions/procedural/index.json`](motions/procedural/index.json) 登记独立程序包 |
| 互动与生态 | [`interactions/`](interactions/)、[`ecology/`](ecology/)、[`subspecies/`](subspecies/) 保存专属配方及来源 |
| 用户认可 | `provenance/standard-species.json` 与各资产包的来源登记保存认可状态和指纹；候选源不继承其他资产的认可状态 |

每个角色用共用表情组件和配方，通过 `bindings/` 记录 UV 装配；动作不携带脸部贴图。复合动作只记录引用、时间、权重与组合规则，不另存整段烘焙轨道。来源与设定边界见对应专题及 [Wiki](../../wiki/README.md)。

## 构建

在仓库根目录运行完整素材构建及源检查：

```sh
python standards/yukkuri-library/build.py
python standards/yukkuri-library/body-standard/render_preview.py
python standards/yukkuri-library/body-standard/verify_sources.py
```

茎生、胎生动作包分别由包内 `build_lifecycle.py`、`build_viviparous.py` 重建；动作包登记由 `python standards/yukkuri-library/build_procedural_catalog.py` 更新。它们有独立程序状态，不进入单骨架关键帧编译器。

标准表情、角色登记和选用快照的认可范围分别引用其 canonical provenance JSON；本页不另存审批流水或替代指纹记录。
