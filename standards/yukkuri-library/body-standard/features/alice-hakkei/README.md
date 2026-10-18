# 爱丽丝基础造型（八景版修订）

2026-10-18 按用户要求，依据 Wiki 与八景原作重做爱丽丝的基础造型。状态为**待用户确认**（[candidate.json](candidate.json)）；用户说“保存”后用 `record.py --approve "<原话>"` 更新本登记的认可状态。圆润身体、五官形状、表情组合和骨架不变；睁眼虹膜统一为蓝色。

## 依据

- 八景U slot 2（`wiki/art-references/game/standard-species/hakkei-contact.png`，Sprite 7607 头发、7038 发箍、6880 鬓尖）：浅柠檬金短发（e7e37b／d6d36b／ffff94），比魔理沙的蜂蜜金更冷更浅；刘海是尖发束，两鬓较长、中央一束长尖、中央略右有短一截的分缝；侧发包脸、下端内收；红发箍戴在发际，两侧边缘都有白色蕾丝，下缘蕾丝更大。
- 八景 ver1.12 `pict/arisu`（用户提供的原作图层）：同样的柠檬金波波头和红色白边发箍；后发尖梢内收；**蓝色虹膜**（`sei_new/6.png`：虹膜 0000ff，下部亮蓝 5353ff／8d8dff，深色轮廓）。该版另有单侧白蝴蝶结小辫，本次未采用（见“待确认”）。
- Wiki 种族页（金发、红发箍、短发）；《饅饅來大講堂》爱丽丝段只记卡士达馅、持家与“都会派”，不涉及外形细节。
- 参考图只在 `wiki/art-references/` 与原作目录中查看，没有复制进运行资源或贴图。

## 造型

| 部分 | 做法 |
| --- | --- |
| 配色 | 改写 `textures/alice/base.png` 及全部表情图集第64行的六个色块：发 `#f6e67c`、亮 `#fff8ac`、暗 `#d6c260`、红 `#ec323a`、暗红 `#b41e2a`、蕾丝 `#fffdf4`。 |
| 眼睛 | 2026-10-18 用户追加：蓝眼是爱丽丝最易辨认的特征。两个眼睛 UV 区内，睁眼（有高光、无灰色死眼）的深棕虹膜改为蓝色：被眼睛包围的深色像素为虹膜 `#0f28ee`，边缘为轮廓 `#251f1f`，下部浅棕反光改为亮蓝 `#536aff`／`#8d8dff`；与另一项工作中 ver1.12 成体状态脸的蓝眼同色。高光保持白色；闭眼线条、空白眼和灰色死眼不变。覆盖全部 105 张图集，其中睁眼的表情受影响，其余脸部像素逐像素相同。 |
| 发顶 | 发顶垫块按中线分左右两半；头顶垫块不再用亮色，避免俯视像盖子。游戏中仍由位置适配的圆角把发顶收圆。 |
| 刘海 | 七束长短错落，发尖向两侧偏，中央另加一束长尖。 |
| 侧发 | 波波侧墙外加三条外层发束形成鼓度，下端三级内卷；包脸前发（`hand_*` 骨骼）四级发尖指向下颌，按身体截面外移，静止时穿入身体不超过旧版。 |
| 后发 | 七束尖发高低交错，每束下面有内收的暗色发尾和尖梢。 |
| 发箍 | 红色发箍戴在发际，正好压在刘海根部；前缘挂白色蕾丝条和一排扇形小花边，后缘有一排较小的花边；两端沿发顶侧面落到包脸前发上方。 |

部件名保持游戏约定：饰物前缀 `alice_band_`／`alice_lace_`，徽章挂在 `alice_band_red_2` 正面。所有头发部件名都含 `hair` 或 `bang`，因此秃发能按左右隐藏（旧名 `alice_cap`、`alice_side_*` 等不含这些字样，秃发时不会消失）。

## 重建顺序

命令从项目根目录运行，Python 需带 Pillow；不编译、不启动游戏。

1. `python standards/yukkuri-library/body-standard/features/alice-hakkei/author.py`：写 `components/alice/` 的四个方块组件，参数在脚本顶部。
2. `python .../alice-hakkei/sync_inputs.py`：复用魔理沙单辫修订的装配逻辑重建 `inputs/alice`，重画色块与蓝色虹膜（可重复执行），更新 `inputs/manifest.json`。
3. `python standards/yukkuri-library/body-standard/build.py --character alice`（需先完整构建一次）。
4. 游戏接入：`scripts/export_rounded_runtime.py`、`motions/variants/lore/faces.py`、`node scripts/export_placement_fit.cjs`（爱丽丝的部件正则已更新）、`scripts/export_rounded_attachments.py --only alice`、`scripts/export_badges.py --mounts-only --only alice`、`rounded_wound_surface.cjs` 的 `roundedSurface`（仅爱丽丝）、`scripts/export_family_assets.py`（`art/props/family-life/recipe.json` 中爱丽丝的悬挂点已移到新发箍中心）、`scripts/export_motion_corrections.py --only alice --trial <目录>` 后把爱丽丝条目与 `cheek_puff.png` 并入运行资源。
5. `python scripts/export_alice_appearance.py`：批量同步现有表情选用、特殊状态图集、内嵌中性贴图及来源指纹；`--check` 只检查不写文件。通用表情刷新与圆润构建也读取角色登记的 `texture_style`，不会把虹膜刷回棕色。
6. `python .../alice-hakkei/record.py`：更新 `candidate.json`。
7. `python .../alice-hakkei/preview.py`：在 `.local/previews/alice/` 生成新旧对照（含灵梦、魔理沙同比例）、八景U参照和动作关键帧。

本次在 Windows 上导出。共用导出器会顺带重写其他形态（PNG 编码、换行和既有的未同步差异），已按内容核对后还原，只保留爱丽丝相关变化；共用登记只改爱丽丝条目。

## 表情适配与剩余确认

- 造型整体（配色、发箍位置和花边、刘海与发尾）待用户确认。
- 八景 ver1.12 的单侧白蝴蝶结小辫未采用（八景U中已取消），如需要可另行加入。
- 蓝眼只改爱丽丝自己的图集，共用表情部件与其他种族不变；爱丽丝表情图集与标准表情登记中的旧指纹不再一致（发色色块与虹膜），登记保留为基线。
- 2026-10-18 已同步成体状态脸与派生贴图的配色；普通期 `breeding_period` 直接使用当前中性脸，恢复脸保留原表情。共享表情组件和其他角色不改。已废弃、在工作区删除的表情不重新启用。
- 未做实机查看和试玩；动作只做了离线关键帧渲染与静止穿插比较。

完整造型预览后运行 `python .../alice-hakkei/preview_expressions.py`，生成 `.local/previews/alice/expressions.png` 和 `all-expressions.png`（105 张库内图集加 3 张运行派生状态），再运行 `python scripts/build_preview_hub.py` 更新总入口。所有预览使用软件光栅器，不启动浏览器或游戏。
