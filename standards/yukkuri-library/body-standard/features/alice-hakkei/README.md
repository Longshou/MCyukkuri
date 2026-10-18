# 爱丽丝基础造型（八景版修订）

2026-10-18 按用户要求，依据 Wiki 与八景原作重做爱丽丝的基础造型。状态为**待用户确认**（[candidate.json](candidate.json)）；用户说“保存”后用 `record.py --approve "<原话>"` 另写 `approved.json`。圆润身体、五官、表情像素和骨架不变。

## 依据

- 八景U slot 2（`wiki/art-references/game/standard-species/hakkei-contact.png`，Sprite 7607 头发、7038 发箍、6880 鬓尖）：浅柠檬金短发（e7e37b／d6d36b／ffff94），比魔理沙的蜂蜜金更冷更浅；刘海是尖发束，两鬓较长、中央一束长尖、中央略右有短一截的分缝；侧发包脸、下端内收；红发箍戴在发际，两侧边缘都有白色蕾丝，下缘蕾丝更大。
- 八景 ver1.12 `pict/arisu`（用户提供的原作图层）：同样的柠檬金波波头和红色白边发箍；后发尖梢内收。该版另有蓝色虹膜和单侧白蝴蝶结小辫，本次未采用（见“待确认”）。
- Wiki 种族页（金发、红发箍、短发）；《饅饅來大講堂》爱丽丝段只记卡士达馅、持家与“都会派”，不涉及外形细节。
- 参考图只在 `wiki/art-references/` 与原作目录中查看，没有复制进运行资源或贴图。

## 造型

| 部分 | 做法 |
| --- | --- |
| 配色 | 改写 `textures/alice/base.png` 及全部表情图集第64行的六个色块：发 `#f6e67c`、亮 `#fff8ac`、暗 `#d6c260`、红 `#ec323a`、暗红 `#b41e2a`、蕾丝 `#fffdf4`。脸部 UV 区不动，表情像素逐像素相同。 |
| 发顶 | 发顶垫块按中线分左右两半；头顶垫块不再用亮色，避免俯视像盖子。游戏中仍由位置适配的圆角把发顶收圆。 |
| 刘海 | 七束长短错落，发尖向两侧偏，中央另加一束长尖。 |
| 侧发 | 波波侧墙外加三条外层发束形成鼓度，下端三级内卷；包脸前发（`hand_*` 骨骼）四级发尖指向下颌，按身体截面外移，静止时穿入身体不超过旧版。 |
| 后发 | 七束尖发高低交错，每束下面有内收的暗色发尾和尖梢。 |
| 发箍 | 红色发箍戴在发际，正好压在刘海根部；前缘挂白色蕾丝条和一排扇形小花边，后缘有一排较小的花边；两端沿发顶侧面落到包脸前发上方。 |

部件名保持游戏约定：饰物前缀 `alice_band_`／`alice_lace_`，徽章挂在 `alice_band_red_2` 正面。所有头发部件名都含 `hair` 或 `bang`，因此秃发能按左右隐藏（旧名 `alice_cap`、`alice_side_*` 等不含这些字样，秃发时不会消失）。

## 重建顺序

命令从项目根目录运行，Python 需带 Pillow；不编译、不启动游戏。

1. `python standards/yukkuri-library/body-standard/features/alice-hakkei/author.py`：写 `components/alice/` 的四个方块组件，参数在脚本顶部。
2. `python .../alice-hakkei/sync_inputs.py`：复用魔理沙单辫修订的装配逻辑重建 `inputs/alice`，重画色块，更新 `inputs/manifest.json`。
3. `python standards/yukkuri-library/body-standard/build.py --character alice`（需先完整构建一次）。
4. 游戏接入：`scripts/export_rounded_runtime.py`、`motions/variants/lore/faces.py`、`node scripts/export_placement_fit.cjs`（爱丽丝的部件正则已更新）、`scripts/export_rounded_attachments.py --only alice`、`scripts/export_badges.py --mounts-only --only alice`、`rounded_wound_surface.cjs` 的 `roundedSurface`（仅爱丽丝）、`scripts/export_family_assets.py`（`art/props/family-life/recipe.json` 中爱丽丝的悬挂点已移到新发箍中心）、`scripts/export_motion_corrections.py --only alice --trial <目录>` 后把爱丽丝条目与 `cheek_puff.png` 并入运行资源。
5. `python .../alice-hakkei/record.py`：更新 `candidate.json`。
6. `python .../alice-hakkei/preview.py`：在 `.local/previews/alice/` 生成新旧对照（含灵梦、魔理沙同比例）、八景U参照和动作关键帧。

本次在 Windows 上导出。共用导出器会顺带重写其他形态（PNG 编码、换行和既有的未同步差异），已按内容核对后还原，只保留爱丽丝相关变化；共用登记只改爱丽丝条目。

## 待确认与未完成

- 造型整体（配色、发箍位置和花边、刘海与发尾）待用户确认。
- 八景 ver1.12 的蓝色虹膜与单侧白蝴蝶结小辫未采用：前者会改动已确认的共用表情，后者在八景U中已取消。如需要可另行加入。
- 成体状态脸 `alice_agitated.png`、`breeding_period.png`、`state_recover.png` 属于另一项工作，未改动；它们是整张图集，发色色块仍是旧的金色，该状态下发色会切回旧色，需要那边按本目录 `sync_inputs.py` 的 `repaint()` 重画第64行色块后再导出。
- 未做实机查看和试玩；动作只做了离线关键帧渲染与静止穿插比较。
