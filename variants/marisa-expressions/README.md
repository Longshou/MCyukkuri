# 魔理沙：八景参考的分层表情

[表情工坊](preview.html) · [13 表情近景](expression-gallery.png) · [完整模型对照](expression-sheet.png) · [拆分图层示例](separated-parts.png) · [基础模型](marisa.bbmodel)

用户已确认保留 `marisa-exercise` 的半速体操。本候选在其基础上拆分表情，原有模型部件坐标与四套动作导出完整保留；左右体操为 2.36 秒，上下体操为 2.62 秒。新增 10 个独立面部部件，共 189 方块、155 骨骼。

## 表情与组合

13 个预设：基础微笑、闭眼笑、得意、生气、鼓嘴、吃惊、困惑、含泪、大哭、刚睡醒、睡眠、哈欠、馋嘴。默认基础微笑逐像素保持之前的自绘脸，其余根据八景 U 实际表情素材重新绘制，保持现有像素画与配色。

拆分部件为左右眼、左右眉、嘴、左右脸红、左右泪水与口水。每个部件都有独立薄片几何、骨骼与透明 PNG，统一跟随原有脸部动画，不拉长眼嘴。

预览初始静止，方便选表情；可播放任一原有动作。展开“拆分组合”可分别选择左右眼、眉毛、嘴形、脸红、泪水／口水。选择基础微笑的附加效果即可清除泪水和口水。改变表情保留当前动作与时间。

“下载当前表情模型”导出含当前组合贴图、10 个面部骨骼及四套动作的完整 `.bbmodel`；“下载当前贴图”导出可直接配套的 128×128 图集。模型格式中的左右按角色自身方向标注。

## 文件

- `marisa.bbmodel`：默认基础微笑，贴图与四套动画嵌入。
- `marisa.geo.json`、`marisa.animation.json`：几何与原有四套动作导出。
- `expressions.json`：预设、组件 UV、原始参考 Sprite ID 和贴图索引。
- `textures/<表情>.png`：13 个完整透明图集，可共用同一模型。
- `parts/<表情>/<部件>.png`：可独立修改的组件；`assembled.png` 为透明脸部合成参考。全套 130 个独立部件文件。
- `expressions.py`：自绘表情生成器。`generate.py` 构建模型与预览。

## 原作依据与验证

[原作表情对照](reference/source-faces.png) 由本机八景 U v8.3 的 13 个原始 Sprite 排列，已逐图核读；原始图仅用于参考，未嵌入交付贴图。来源与对应特征见 [参考说明](reference/README.md)。

检查默认脸与上一版的像素完全一致；原有几何坐标、四套动画导出 JSON 均保持一致。Chrome 验证 13 个预设互异、左右眼独立、自由组合、模型下载确实嵌入当前贴图、动作时间保持及手机布局。Blockbench 验证使用浏览器实际导出的自定义表情模型，检查四套动作姿态与动画导出。结果在 `*-verification.json`。

尚未接入 MC 实体、自动表情状态机或进行游戏实测。当前表情通过切换图集实现；骨骼已经拆分，后续可接入眨眼与嘴部动作。附带 Java 1.21–1.21.1 资源包仅作静态展示。

## 重建

仓库根目录运行 `python variants/marisa-expressions/generate.py`（NumPy、Pillow），再运行 `node variants/marisa-expressions/render-expressions.cjs`。模型生成器读取 `marisa-rump/waddle-rig.json` 以保留原有甩臀曲线。
