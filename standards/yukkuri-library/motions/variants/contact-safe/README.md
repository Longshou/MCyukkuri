# 鼓腮与体操动作修订

2026-10-07，第七版鼓腮修订，状态为待用户复查。当前入口：[常态与满腮五视图](../../../../../art/source/motion-corrections/previews/current/review.png)、[19种形态特写](../../../../../art/source/motion-corrections/previews/current/close-ups.png)、[19种形态斜侧面](../../../../../art/source/motion-corrections/previews/current/all-models.png)、[连续动作](../../../../../art/source/motion-corrections/previews/current/face-stability.gif)。

## 造型依据

漫画表现以项目保存的[八景U噗咕参考](../../../../../variants/marisa-puff/reference/README.md)为准：两颊是独立的圆鼓球体，中间留出嘴部缺口，腮红在脸颊外上侧，嘴为小小的“ハ”形；八景第二组脸为睁眼。用户此前的意见也作为约束：只鼓腮帮、不横向拉长全身，身体底部不脱离，眼睛贴图不被带动，鼓腮要有明显的夸张效果，并且不穿模。

第六版把下半脸横向摊开，侧发被顶成翅膀状，表情为猫嘴，已由本版替代；其预览、检查和源文件见 [puff-v6](../../../../../preview/review/puff-v6/README.md)。

## 造型做法

- 表情 `cheek_puff`：眼睛与眉毛取常态，嘴取标准鼓腮表情的“ハ”形噘嘴及其几何补丁，腮红保持原像素形状并加深为粉色。两种家油、两种豆形和两种捕食种没有鼓腮贴图，按灵梦标准鼓腮嘴的像素和相对位置生成；捕食种按其4倍嘴部精度放大。
- 脸颊：每侧一个向外上方倾斜22°的椭球，与皮肤做平滑并集，从脸内锚点沿射线投影到新表面，鼓包保持圆润的球面。所有尺寸以腮红中心到脸中线的距离为单位，豆形的高身体和家油的小身体保持相同的脸部比例。
- 五官：按眼睛、嘴部贴图的实际不透明像素划出保护区，保护区内皮肤不动；沿脸面的滑动在两倍距离内逐渐停下，早于向前鼓起，避免皮肤向嘴角挤压而翻折。保护系数在空间中先取邻域最小值再平均，只会更保守。
- 头发：手部／鬓发骨骼上的侧发是可动件，左右以同一角度刚体外摆，枢轴取该骨骼的枢轴或发束顶端最内侧点中能摆得更远者，且只摆到不产生任何新穿插的角度，不足3°时不摆；当前灵梦外摆10.75°，歪脸灵梦14°，均绕骨骼枢轴。其余头发、帽子与饰物都是障碍物，脸颊在其前方留出间隙后停住，被压住的范围同样经保守平滑。若缩小不超过10%即可让头发只是轻擦，则整体缩小以保持圆润；两种豆形因此为0.93倍，其余保持原尺寸。
- 腮红：把细分皮肤按腮红矩形裁切，沿平滑法线浮起一个小间隙，与同一组三角形一起运动。
- 底部：靠近地面的皮肤不向下移动；根骨骼在鼓腮期间不动。
- 节奏：6.4秒一轮，鼓起与放气各占22%，满腮时以3.5Hz、4%的幅度轻颤（只会略微回缩）。完整体操10.4～13.6秒复用同一形变。

## 运行数据

`recipe.json` 与 `author.py` 是制作源，`scripts/export_motion_corrections.py` 导出第2版运行格式：每种形态一份细分皮肤、两份腮红网格和侧发偏移，全部为同一个静止空间形变目标，由动作的包络缩放；嘴部补丁与表情别名一起写入。游戏和素材检查室读取同一资源 `src/main/resources/assets/mcyukkuri/yukkuri_presentation/motion_corrections.json`。基础选用模型、权重与静态网格不改写，原伤口／徽章几何指纹不变。

侧弯13°，螺壳形态5°；伸展上限12%。跳动一轮0.8秒，最高点为身体高度的38%。完整体操14秒，依次伸展、侧倾、转体、四次跳动、鼓腮、恢复。这些动作与上一版相同。

## 重新导出

替换选用模型或修改本目录后，从项目根目录执行：

```powershell
python scripts/export_motion_corrections.py
python scripts/build_review_catalog.py
node scripts/verify_puff_revision.cjs
node scripts/verify_puff_face_contact.cjs
node scripts/verify_motion_corrections.cjs
node scripts/verify_puff_java.cjs
```

导出全部19种形态约需7分钟。`--only id,... --trial 目录` 只把试验结果写到指定目录，`--check` 核对现有导出而不写文件。`verify_puff_java.cjs` 需要先用独立初始化脚本完成构建并写出运行 classpath，见[脚本说明](../../../../../scripts/README.md)。随后重新构建模组。选用与验收见[游戏适配记录](../../../../../art/source/motion-corrections/README.md)。
