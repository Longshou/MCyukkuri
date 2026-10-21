# 多毛种、灵胖与 DOS 魔理沙

本包索引多毛灵梦、灵胖与 DOS 魔理沙。模型来源与参考见 [REFERENCES.md](REFERENCES.md)，可旋转预览见 [index.html](index.html)。可编辑模型位于 `assembled/<模型>/`，包括 `.bbmodel`、几何、动画、贴图和独立预览。

| 模型 | 当前造型 | 来源入口 |
| --- | --- | --- |
| 多毛灵梦 `reimu_wasa` | 圆润标准身体与蝴蝶结，浓密鬓角连接太阳穴，头顶、刘海和后脑分层加毛束 | [recipes.json](recipes.json) |
| 灵胖 `reimu_deibu` | 1.5 倍圆润身体，保留专属成熟面容 | [body-standard](../../body-standard/README.md)、[候选登记](../../body-standard/features/deibu-mature/candidate.json) |
| DOS 魔理沙 `marisa_dos` | 主体高 28.80、宽 22.00 模型单位，帽高另计；身体为高椭圆形，五官居上、短刘海、帽檐贴合发顶 | [build.py](build.py)、[参考与设计参数](REFERENCES.md) |

灵胖成长设定记录在 [deibu-development.json](deibu-development.json)：营养良好且处于低友善度环境的灵梦种，成熟后可能转为灵胖；概率及判定参数仍待设计。此登记定义设定，不代表游戏成长逻辑已经实现。

多毛灵梦与灵胖复用当前圆润标准组件；`recipes.json` 负责专属头发与旧体型来源资料，本目录的 `build.py` 只构建 DOS，并从标准库读取多毛与灵胖模型。DOS 的基础输入固定取自 `../../body-standard/inputs/`。从仓库根目录运行 `python standards/yukkuri-library/build.py` 重建标准库。模型及源指纹见 [manifest.json](manifest.json)。

外部参考只用于造型依据，不进入运行素材。游戏接入状态以 `art/runtime-sources.json` 和对应运行资源为准。
