# 鼓腮与体操动作修订

2026-10-17，第十版鼓腮修订（整身挤压拉伸），状态为待用户复查。

## 造型依据

第七版的两颊椭球形变（细分皮肤、腮红载体、侧发外摆）读起来像下颌肉瘤，侧发被顶出，已退役。无颈圆团子的情绪靠整身挤压拉伸、小位移和脸来表达：鼓腮是一段根骨骼片段加一条脸时间线，不再改写皮肤。Wiki 只记载“鼓腮绷脸、随后睁眼跳起”和八景U的闭眼／睁眼噗咕脸，整身鼓起与ぷしゅー泄气是项目风格化编排。

## 造型做法

`recipe.json` 的 `puff` 块和 `puff_seconds`（2.8秒）、`taiso_puff_seconds`（3.2秒）驱动 `author.py` 的 `puff_curve`，各段为时长比例，所以完整体操段复用同一曲线：

- 吸气（0～0.12）：缩到(1.03, 0.94, 1.03)，前倾3°。
- 鼓起（0.12～0.30）：带35%过冲到标准身体(1.14, 1.12, 1.14)；豆形(1.10, 1.03, 1.10)，歪脸(1.07, 1.07, 1.07)。
- 保持（0.30～0.80）：呼吸±2.5%（1.1Hz）、1.2%轻颤（5Hz）、侧倾≤1.2°。
- ぷしゅー（0.80～0.90）压扁到(1.05, 0.93, 1.05)，回落（0.90～1.0）缓回静止。
- 采样0.02秒（轻颤需要），其余动作仍0.04秒；落地补偿使最低点贴地。
- 脸：`expression_spans` 在0.10时进入 `puff`（无此贴图时 `cheek_puff`）、0.84起 `cheek_puff`；吸气段不指定，服务器当前表情保留。`expression_yield: ["cry"]` 让受伤哭脸优先。
- 表情 `cheek_puff`：以 `angry.png` 的眼睛、眉毛为底，嘴取标准鼓腮的“ハ”形噘嘴及其几何补丁（保留在 `expression_patches`），腮红加深并加浅色高光对。两种家油、两种豆形和两种捕食种没有鼓腮贴图，按灵梦标准鼓腮嘴的像素和相对位置生成。

侧弯12°（2026-10-17 动作第二轮由13°收回），螺壳形态5°；伸展上限12%。跳动一轮0.8秒，最高点为身体高度的38%。完整体操14秒，依次伸展、侧倾、转体、四次跳动、鼓腮、恢复。这些动作与上一版相同。

## 运行数据

`recipe.json` 与 `author.py` 是制作源，`scripts/export_motion_corrections.py` 导出第2版运行格式：根骨骼关键帧、表情时间线、嘴部补丁与表情别名；不再输出 `puff_surface`、`puff_attachments`、`puff_offsets`、`puff_envelope`。游戏和素材检查室读取同一资源 `src/main/resources/assets/mcyukkuri/yukkuri_presentation/motion_corrections.json`。基础选用模型、权重与静态网格不改写，原伤口／徽章几何指纹不变。

## 世界观动作（第 9 版）

2026-10-14，配方第 9 版用 `lore` 字段引用 [世界观动作](../lore/README.md)（第 2 版配方，25 段，按种族门控并带表情编排）。`compile_revision` 在已有片段之后编译这些新片段，落地补偿改为支持前倾（Rz·Ry·Rx·S 的完整 y 行）；不前倾时仍用原公式，已有片段输出不变。前倾、侧倾类新片段也经过下文的单辫跟随。

## 魔理沙单辫跟随

2026-10-14，配方第8版增加 `braid_follow`，同日随魔理沙单辫动作一起保存；鼓腮修订本身仍待复查。魔理沙的粗辫挂在 `hand_right` 上，原本随头部刚体运动；摇摆时下半身在辫子下方扭转，撑住／压低时头部下沉，辫梢会进入皮肤或地面。`author.py` 的 `braid_follow` 对 `recipe.json` 所列动作逐帧（0.04 秒）求解辫子转角：

- 绕 Y 轴跟随辫子所在高度的身体扭转，取该高度的 `soft_head_slice_*` 转角。
- 再绕 Z 轴取最小的外摆角，使辫子进入皮肤不超过静止时的深度加 0.02，辫梢高于地面 0.03；上限 30°，步长 1°。
- 峰值先保持再做 0.12 秒平滑，循环动作首尾相接。已有修正的侧弯类动作在原修正上叠加，不替换。
- 只作用于由 `hand_right` 及其子骨骼带动的辫子。稀毛和豆形的辫子随皮肤变形，不加此修正。

当前结果：摇摆时跟随扭转约4°（家油6.9°）、外摆3°（家油2°）；撑住与压低外摆19°（家油2°）；侧弯类外摆普通9°、歪脸15°、螺壳2°。游戏动作的穿插复核见[单辫修订](../../../body-standard/features/marisa-single-braid/README.md#穿插治理)。

## 重新导出

替换选用模型或修改本目录后，从项目根目录执行：

```powershell
python scripts/export_motion_corrections.py
python scripts/build_review_catalog.py
node scripts/verify_puff_motion.cjs
node scripts/verify_motion_corrections.cjs
node scripts/verify_puff_java.cjs
```

导出全部19种形态约需7分钟。`--only id,... --trial 目录` 只把试验结果写到指定目录，`--check` 核对现有导出而不写文件。`verify_puff_java.cjs` 需要先用独立初始化脚本完成构建并写出运行 classpath，见[脚本说明](../../../../../scripts/README.md)。随后重新构建模组。选用与验收见[游戏适配记录](../../../../../art/source/motion-corrections/README.md)。
