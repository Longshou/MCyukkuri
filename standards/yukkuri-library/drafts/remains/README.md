# 遗体造型制作源

按死因类别区分的遗体形态与水中溶化残渣。规则（类别如何决定、水中阶段及后果）只在[受伤与遗体](../../../../docs/INJURIES_AND_REMAINS.md#同伴照护与遗体)维护；本目录保存配方、生成器与产物。状态：已选入游戏，用户尚未认可外观。

## 产物

| 文件 | 内容 |
| --- | --- |
| `recipe.json` | 每种形态的死亡脸、整体缩放与侧倾、皮肤／头发／饰物调色、表层与地面贴花；水中两阶段的叠加参数 |
| `build.py` | 生成 `assembled/<模型>/remains_<形态>.png`、`assembled/shared/overlay_*.png`、`decal_*.png` 与 `assembled/manifest.json` |
| `export.py` | 复制到 `art/source/remains/` 快照和运行资源，写出 `yukkuri_presentation/remains_forms.json`，登记 `art/runtime-sources.json` 的 `remains_art` |
| `render.cjs`、`preview.py` | 用运行网格、表情补丁与库内软件渲染器出预览 |

八种形态：寿终／未知 `natural`、饿死 `starved`、烧死 `burned`、压死 `crushed`、外伤 `violent`、溺死 `drowned`、冻死 `frozen`、病死 `sick`；另有溶化图集 `dissolving`。泡软不换图集，只叠加姿态与 `soaked` 表层。

## 做法

- **死亡脸**以已认可的标准表情（`provenance/standard-expressions.json`）为底：N068 闭眼平嘴（眼改画为灰色凹陷中的平缝，口换用 N069 的松弛小口，避免像熟睡）、N044 干枯失神、N027 白眼哭喊（叠加发面伤口包 `dry_face.py` 的三级干枯并去掉泪）、N045 压扁失神、N026 失神垂舌、N069 白眼喘嘴、N060 寒冷发颤（瞳孔蒙上灰蓝霜色）、N070 虚弱闭眼。改画部分是项目美术调整，不属于已认可表情本身。N068／N044／N026 的原作参照分别为寿命、干物死与キリ死亡脸（见各预设 `sources`）；其余对应关系为项目选择。溶化图集用 N069，五官向肤色褪去 78%，泪与效果层去除。
- **调色**按运行网格 UV 求出皮肤、头发、饰物实际采样的纹素，只改这些纹素；圆润身体的皮肤是单一色块，所以皮肤上的纹样不画进图集。死者腮红按配方淡去。烧死在头发色块上撒少量灰点，黑发也能看出。馅料用偏紫的豆沙褐，不用血红。
- **表层**是 64×16 的环向贴图：u 绕身体一周、正面在 0.5，v 从头顶到足底，运行时贴在实际蒙皮外侧 0.03 单位，受伤补丁处让给伤口美术。纹样为干纹、裂口露馅、焦黑结痂与灰、刀口渗馅、湿斑与滴水下摆、头顶与两侧的霜、黄斑与霉点、泡水大白斑与褶、溶化流痕；眼口区域留空。
- **姿态**只用整体缩放与不超过 12° 的侧倾，绕着地点；与饱腹塌陷、腐败塌陷相乘，高度下限 0.15。压死、外伤和溶化另画地面贴花（馅渍、侧边渗出的馅、面皮残渣），离地 0.02 格避免闪烁。
- 色板、形状、比例与纹样均为项目美术设计，不复制外部图片。

## 选用范围与边界

图集覆盖九个圆润运行模型（灵梦、魔理沙、橙、爱丽丝、帕秋莉、螺壳魔理沙、和灵梦、灵胖、和灵胖）。和灵梦与灵胖类按圆润导出器的做法，把灵梦死亡脸的五官框移到各自图集；灵胖类不套成熟脸风格。家油、豆型、半身、稀有形态等没有专用图集，保留睡脸与既有全身烧伤脸，只换姿态、表层与贴花。献身分体与切面不在本包。

```sh
python standards/yukkuri-library/drafts/remains/build.py      # --check 只读核对
python standards/yukkuri-library/drafts/remains/export.py     # --check 只读核对
python standards/yukkuri-library/drafts/remains/preview.py    # 输出主仓 .local/previews/remains/
```
