# 圆润标准体型

2026-10-18 爱丽丝基础造型：按八景U与 ver1.12 重做为浅柠檬金波波头、发际红发箍（上下缘白蕾丝）、尖刘海与内收发尾；身体、五官与骨架不变，待用户确认。见 [alice-hakkei](features/alice-hakkei/README.md)。

2026-10-13 魔理沙单辫：普通与螺壳魔理沙的辫子改为自身左侧一条粗辫，另一侧为短侧发；冻结输入建立新修订，其他亚种与游戏资源同步。2026-10-14 用户保存单辫造型及适配动作，原批准记录保留为基线。见 [marisa-single-braid](features/marisa-single-braid/README.md)。

2026-09-13 后续纠正：[稀毛与歪脸魔理沙](../subspecies/deficient/rounded-marisa/README.md)按用户新要求另行采用圆润身体，并同步游戏资源。此项仅覆盖下方原排除范围中的这两种；其余不足形态、家油与 DOS 继续保留各自体型。

2026-09-13 头发修复：八种圆润模型的后发按身体截面增加遮盖余量，并与身体使用相同的分段权重；灵胖补齐上部刘海发根与头顶头发的连接。身体、面容和蝴蝶结尺寸保留。修订配方见 [hair-contact](features/hair-contact/recipe.json)，源与导出指纹见[本轮候选](features/hair-contact/candidate.json)。本轮头发修订待确认，原批准记录保留为基线。

全部油库里模型展示图由 `features/hair-contact/gallery.py` 在本机生成到 `previews/current/all-models/`（不入库），包含普通种、螺壳、多毛、灵胖、家油、捕食种、DOS 与不足形态，每种有四视图和动图。各卡片独立取景，不用于尺寸比较。

当前灵胖面容已按“大妈、减少可爱感”修改，半睁眼、小虹膜、浅眼袋与脸颊纹替代幼态大眼和粉红腮红。面容待确认，原圆润身体及1.5倍身体尺寸不变。制作与检查指纹见[面容候选记录](features/deibu-mature/candidate.json)；较早的面容批准和验证记录仅作为基线。

灵胖尺寸修订：身体宽、高、深设为普通灵梦的 **1.5 倍**，保持圆润身体比例，尺寸为19.2 × 14.4 × 16.095模型单位。2026-09-13追加要求：蝴蝶结恢复普通灵梦的实际尺寸，仅挂点随身体调整；取消整体放大及旧输入额外加宽。配方中 `bow_scale` 围绕结心调整几何、支点与局部动作位移，源与导出同步。当前面容和头饰待确认，见[本轮候选](features/deibu-bow/candidate.json)。

2026-09-13，用户确认当前圆润身体，并要求推广到除家油、DOS、不足油之外的所有油库里，包括多毛种。该版本已保存为标准，确认记录与文件指纹见 [standard-body.json](../provenance/standard-body.json)。

[体型配方](recipe.json) · [共用身体截面](profile.json)

| 适用角色 | 本次处理 |
| --- | --- |
| 灵梦、魔理沙、橙、爱丽丝、帕秋莉 | 换用连续圆润身体，适配五官、头发、饰物及骨架挂接 |
| 螺壳魔理沙 | 同一身体，保留居中螺壳与缩入、探出动作 |
| 多毛灵梦 | 同一身体，保留外炸鬓角、毛束和鬓毛轻摆 |
| 灵胖 | 同一身体，保留专属发型与成熟表情；原有高胖身体比例已被替代 |
| 蕾米莉亚、弗兰 | 现有身体已使用相同截面，登记共用标准，模型几何保持原样 |
| 家油、DOS、不足油 | 保留原有体型；本轮未更改其正式导出 |

标准身体宽 12.8、高 9.6、最大前后深 10.73 模型单位。角色可在场景或游戏中另设整体尺寸；本包统一身体比例，不沿用亚种旧对照页的厘米标定。头发、帽子和壳不计入身体尺寸。身体采用连续网格与分段骨骼权重。

佩尼佩尼与麻姆麻姆使用同一前下方表面挂点，常态缩回，切换时先完整收回，任一时刻只显现一种；油菊保留在后部。胎生沿用当前前下方出口，提供怀孕、生产、子油落地与恢复阶段，并同步五官到膨胀腹部前侧。胎生预览期间锁定独立伸缩控制。

## 制作与重建

`profile.json`、`recipe.json` 与 `build.py` 定义本次标准的制作过程。`inputs/<角色>/` 保存推广前的专属部件、表情和动作输入；`inputs/manifest.json` 保存其冻结指纹。它们是配方输入，不从改形后的正式导出反向重复变换。

`components/`、`rigs/` 与 `anatomy.json` 是配方生成的标准组件和合并骨架，角色登记已指向这些文件。修改体型或挂接应先修改配方；修改专属部件应先建立新的输入修订、记录与旧输入的差异，然后同步生成组件、模型和预览。不要仅编辑生成组件或 `.bbmodel`。新的造型修订不得沿用本次批准指纹。

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

`build.py` 把 `pose.js`、`preview-data.json` 等生成到 `previews/current/`，后面的渲染与检查脚本都读取这些文件；检查结果写入 `reports/current/`。两处都是本机产物，不入库。胎生适配脚本位于 `runtime/birth.js`。只重建单个角色时加 `--character <角色>`，此模式读取已有的 `previews/current/preview-data.json`，需先完整构建一次。

主构建入口已转交本包，不自动重建独立家油或互动包。家油与 DOS 的派生制作保留对推广前输入的引用；不足油仍使用原有选定快照。历史种族方块生成脚本和帕秋莉局部导出工具不适用于本次合并网格。

普通五种与多毛种保留各自骨架动作；螺壳种、灵胖的动作菜单以其实际导出为准。

这次保存属于标准美术资产更新，未替换 `art/source/` 的游戏接入快照，也未修改 Minecraft 打包资源。游戏接入需要另行适配当前加载器、骨架和动画，并进行实机验收。
