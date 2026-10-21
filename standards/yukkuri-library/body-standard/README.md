# 圆润标准体型

本包通过 `profile.json` 定义连续圆润身体截面，通过 `recipe.json` 和角色专属输入生成合并模型。当前角色选型、批准指纹和候选修订以各登记文件为准；造型来源分别见 [爱丽丝八景造型](features/alice-hakkei/README.md)、[魔理沙单辫](features/marisa-single-braid/README.md)、[后发贴合](features/hair-contact/recipe.json)、[灵胖面容](features/deibu-mature/README.md) 与 [灵胖蝴蝶结](features/deibu-bow/candidate.json)。

全部角色预览由 `features/hair-contact/gallery.py` 生成至 `.local/previews/body-standard/all-models/`，仓库入口为 `.local/previews/index.html`。各卡片独立取景，不用于尺寸比较。

[体型配方](recipe.json) · [共用身体截面](profile.json)

| 角色范围 | 身体适配 |
| --- | --- |
| 灵梦、魔理沙、橙、爱丽丝、帕秋莉 | 换用连续圆润身体，适配五官、头发、饰物及骨架挂接 |
| 螺壳魔理沙 | 同一身体，保留居中螺壳与缩入、探出动作 |
| 多毛灵梦 | 同一身体，保留外炸鬓角、毛束和鬓毛轻摆 |
| 灵胖 | 同一身体，保留专属发型与成熟表情；原有高胖身体比例已被替代 |
| 蕾米莉亚、弗兰 | 现有身体已使用相同截面，登记共用标准，模型几何保持原样 |
| 家油、DOS 与先天不足形态 | 由各自专属包维护；灵梦与魔理沙的稀毛、歪脸圆润形态从当前身体供体派生，见[不足形态](../subspecies/deficient/README.md) |

标准身体宽 12.8、高 9.6、最大前后深 10.73 模型单位。角色可在场景或游戏中另设整体尺寸；本包统一身体比例，不沿用亚种旧对照页的厘米标定。头发、帽子和壳不计入身体尺寸。身体采用连续网格与分段骨骼权重。灵胖采用 1.5 倍尺寸（19.2 × 14.4 × 16.095 模型单位）；蝴蝶结保持普通尺寸，仅调整挂点与局部位移。

佩尼佩尼与麻姆麻姆使用同一前下方表面挂点，常态缩回，切换时先完整收回，任一时刻只显现一种；油菊保留在后部。胎生沿用当前前下方出口，提供怀孕、生产、子油落地与恢复阶段，并同步五官到膨胀腹部前侧。胎生预览期间锁定独立伸缩控制。

## 制作与重建

`profile.json`、`recipe.json` 与 `build.py` 定义标准制作源。`inputs/<角色>/` 保存专属部件、表情和动作输入；`inputs/manifest.json` 登记其指纹。配方从这些输入生成组件，不从改形后的正式导出反向重复变换。

`components/`、`rigs/` 与 `anatomy.json` 是配方生成的标准组件和合并骨架。修改体型或挂接应先修改配方；修改专属部件应先更新输入，再同步生成组件、模型和预览。不要仅编辑生成组件或 `.bbmodel`。批准状态与源/导出指纹分别以角色登记和 provenance 文件为准。

正式导出仍位于 `../assembled/<角色>/`；多毛灵梦、灵胖位于 `../subspecies/reimu-dos/assembled/`。每种导出包含可编辑模型、几何、骨架、动画、贴图与独立预览。普通模型图集为 256×256，螺壳模型为 512×512，原表情像素保留。

从项目根目录运行：

```powershell
python standards/yukkuri-library/build.py
python standards/yukkuri-library/body-standard/render_preview.py
python standards/yukkuri-library/body-standard/verify_sources.py
node standards/yukkuri-library/body-standard/verify.cjs
node standards/yukkuri-library/body-standard/verify_anatomy.cjs
node standards/yukkuri-library/body-standard/verify_birth.cjs
```

`build.py` 把 `pose.js`、`preview-data.json` 等专项检查输入生成到仓库根目录 `.local/previews/body-standard/`，渲染与检查脚本读取这些文件；图片预览从仓库总入口 `.local/previews/index.html` 查看。检查结果写入 `.local/reports/body-standard/`，临时渲染数据放 `.local/previews/_work/body-standard/`。这些都是本机产物，不入库。胎生适配脚本位于 `runtime/birth.js`。只重建单个角色时加 `--character <角色>`，此模式读取已有的 `.local/previews/body-standard/preview-data.json`，需先完整构建一次。

主构建入口已转交本包，不自动重建独立家油或互动包。家油与 DOS 的派生制作保留对推广前输入的引用；不足油仍使用原有选定快照。历史种族方块生成脚本和帕秋莉局部导出工具不适用于本次合并网格。

普通五种与多毛种保留各自骨架动作；螺壳种、灵胖的动作菜单以其实际导出为准。

这次保存属于标准美术资产更新，未替换 `art/source/` 的游戏接入快照，也未修改 Minecraft 打包资源。游戏接入需要另行适配当前加载器、骨架和动画，并进行实机验收。
