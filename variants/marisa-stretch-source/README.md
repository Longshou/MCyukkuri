# 魔理沙：按八景 U 素材比例重做伸长

[原始图层对照](reference/source-comparison.png) · [动画预览](preview.html) · [动图](stretch.gif) · [模型](marisa.bbmodel)

用户否定了强烈收腰版本。本轮重新打开八景 U 原始身体分帧，并按 Sprite 裁剪偏移和动作中公共图层位移重组普通魔理沙。原图呈现连续的长卵形：正常与最大伸长身体裁剪尺寸分别为 216×200、219×270，宽度基本不变，高度约 1.35 倍，没有细脖子或沙漏腰。

本轮保留已选基础角色的脸、头发和帽子，把身体最大高度由 8 调为 10.8 单位，上方五官与饰物上移 2.8 单位。下部轻微收圆，保持连续馒头体型。二维素材没有提供三维深度，深度与方块过渡为本次适配选择；2.8 秒播放节奏也不是原作精确时长。

178 方块、144 骨骼，保留甩臀循环。提供 `marisa.bbmodel`、`marisa.geo.json`、`marisa.animation.json` 和自绘贴图；原作提取图仅用于参考，没有嵌入交付材质。

## 验证

141 个伸长姿态检查通过，底面 Y=0，分层垂直衔接误差低于 0.000001；模型完成伸长后恢复静止。原有未分层的五官、头发、帽子等部件甩臀运动保持一致。Chrome 动作切换、单次停止、时间轴与窄屏检查通过；Blockbench 双动画检查结果见 `blockbench-verification.json`。

尚未接入 MC 实体或进行游戏实测。附带 Java 1.21–1.21.1 资源包只作静态物品展示。

## 参考与重建

参考文件与索引在 `reference/`。完整对照由原作图层按偏移重组，不是实机截图；图层排序按表／裏名称安排，未验证额外运行时排序差异。提取脚本 `../extract-stretch-source.py` 为本轮本地提取记录。

可复用技能位于 `C:/Users/shaoli/.codex/skills/hakkei-u-art-reference/SKILL.md`，包含定位、提取、重组与原作比较流程。

重建：在仓库根目录依次运行 `python variants/marisa-stretch-source/generate.py`、`node variants/marisa-stretch-source/render-stretch.cjs`、`python variants/marisa-stretch-source/make-previews.py`。Python 需要 NumPy 和 Pillow。
