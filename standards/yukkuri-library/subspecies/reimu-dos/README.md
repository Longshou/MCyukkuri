# 多毛种、灵胖与 DOS 魔理沙

灵胖成长设定：**营养良好且处于低友善度环境的灵梦种，成熟后可能变为灵胖。** 这是有概率发生的后天形态变化，具体概率、营养与环境判定参数待定。规则登记见 [deibu-development.json](deibu-development.json)，完整说明见[灵胖成长转变](../../../../wiki/reimu-deibu-development.md)。目前登记设定，尚未接入游戏成长逻辑。

灵胖尺寸修订：身体宽、高、深设为普通灵梦的 **1.5 倍**，尺寸为19.2 × 14.4 × 16.095模型单位。蝴蝶结恢复普通灵梦的实际尺寸，仅挂接位置随身体调整。新的成熟面容和蝴蝶结待确认，见[候选及指纹](../../body-standard/features/deibu-bow/candidate.json)。

[打开三维预览与同尺度对照](index.html) · [参考核读](REFERENCES.md)

2026-09-13：多毛灵梦和灵胖已换用[圆润标准体型](../../body-standard/README.md)，包括多毛种。保留各自专属头发与表情；灵胖原有的高胖比例已被替代。DOS 保持高瘦椭圆形及原有尺寸。

当前多毛种、灵胖预览请进入[圆润标准预览](../../body-standard/previews/current/index.html)或下列独立模型。原总览的同尺度区保留推广前对照，不作为当前体型展示。

| 模型 | 造型 | 可编辑文件 |
| --- | --- | --- |
| 多毛灵梦 `reimu_wasa` | 普通灵梦身体与蝴蝶结，浓密鬓角直连太阳穴，头顶／刘海／后脑新增毛束 | [bbmodel](assembled/reimu_wasa/reimu_wasa.bbmodel) |
| 灵胖 `reimu_deibu` | 圆润标准身体，保留专属头发与成熟面容 | [bbmodel](assembled/reimu_deibu/reimu_deibu.bbmodel) |
| DOS 魔理沙 `marisa_dos` | 高椭圆身体，五官居上，短刘海与贴合头顶的低帽沿，沉稳威严面容 | [bbmodel](assembled/marisa_dos/marisa_dos.bbmodel) |

每个 `assembled/<模型>/` 内提供 `.bbmodel`、`.geo.json`、`.animation.json`、基础 PNG、13 个已保留表情贴图（灵胖、DOS 各增加 1 个亚种专用表情）与独立离线 HTML。可在 Blockbench 中打开并编辑。预览支持拖动旋转、缩放、静止／走动／转身／鼓脸和表情切换；多毛种另有鬓毛轻摆，使用独立 sideburn 左右节点与太阳穴支点；交互按钮为轻摆鬓角。动作沿用标准骨架，经当前体型适配；未将其他体操和繁殖动作声明为本次验收内容。

总览上方卡片各自适配画幅；下方“灵梦三型”和“普通魔理沙与 DOS”使用同一镜头比例与地面基线，可以准确比较导出模型尺寸。

## 编辑与重建

多毛灵梦和灵胖由 `../../body-standard/recipe.json` 与其构建器负责。此处 `recipes.json` 保留毛团和旧体型的制作参数；本目录 `build.py` 仅重新派生 DOS，另从已生成的新标准文件读取多毛种和灵胖。DOS 的基础来源已固定为 `../../body-standard/inputs/`，避免以后重建时跟随普通种换体型。

```powershell
python standards/yukkuri-library/build.py
```

完整检查命令见[圆润标准体型](../../body-standard/README.md)。本机执行遵循根目录 `AGENTS.md`。

模型构建需要基础库、Python 与 Pillow。`verify.cjs` 保留历史浏览器检查方式，供人工在允许的环境中使用；依赖 Playwright 与 Chrome，路径通过 `PLAYWRIGHT_MODULE` 和 `CHROME_PATH` 指定。

`manifest.json` 记录基础模型哈希和几何数量；根目录 `verification.json` 是导入时的历史验证记录，不证明后续版本通过。旧 `checks/` 截图已清理；新结果写入本包 `reports/current/verification.json`，截图写入 `reports/current/checks/`。交付验证结果时需同时记录版本、环境和时间。

这些亚种尚未接入 Minecraft 实体或生成物品。原作和网络图片仅用于参考，未进入正式素材；新增临时参考放项目内 `.local/reference/`，出处保存在 `REFERENCES.md`。基础表情沿用项目既有像素；两种专用表情基于既有像素与调色板局部创作，未修改基础库。

## 实际尺寸

DOS 的旧标定继续有效：16 模型单位代表 1 米，主体高 1.80 米、宽约 1.375 米，帽子另算。

多毛灵梦使用宽 12.8、高 9.6 的圆润标准身体；灵胖整体放大至 1.5 倍。旧 `physical-scale.json` 中的普通种与灵胖厘米数不适用于本轮导出；场景复用需按目的另外设置整体尺寸。当前造型预览按统一模型尺度展示。
