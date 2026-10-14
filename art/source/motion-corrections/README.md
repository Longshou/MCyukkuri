# 当前动作适配

2026-10-14，第 9 版：同一资源新增 27 段[世界观动作](../../../standards/yukkuri-library/motions/variants/lore/README.md)，作为新的片段名合并进 19 种形态。

- 日常、社交与情绪 15 段。
- 分娩与接生 7 段（含摘帽、呆滞后哭泣）。
- 水生魔理沙的帽船 6 段。

每段按种族门控，只编入适用的形态，并带表情编排。配方第 3 版增加 `doff_hat`、`bereaved`，`midwife` 只给魔理沙；[出生与接生场景](previews/current/lore/births.html)演示夫妻、接生与出生的子油。呆滞脸贴图来源见 [extra-faces.json](extra-faces.json)。已有片段、鼓腮形变和贴图逐字节不变，基础模型指纹不变。[交互预览](previews/current/lore/motions.html)与[离线动图](previews/current/lore/index.html)直接双击打开，[问候与分娩关键帧](previews/current/lore/keyframes-greet-birth.png)、[水生帽船关键帧](previews/current/lore/keyframes-aquatic.png)。状态为待复查。第 8 版加入的魔理沙单辫跟随已随单辫动作保存。

2026-10-07 追加：接入[头饰与头发位置适配](../placement-fit/README.md)后，本修订按拼接后的网格重新导出，脸颊让位和侧发外摆以新的头发、帽子为障碍。灵梦、歪脸灵梦外摆角度与压脸量和之前相同（10.75°、14°）；魔理沙、歪脸魔理沙的侧发由固定改为外摆（11.5°、14°），脸颊不再被侧发压扁；其余形态的鼓腮结果不变。检查与预览已按新导出重跑，结果见[本轮验收](reports/current/README.md)。

2026-10-07，第七版鼓腮修订，状态为待用户复查。当前入口：[常态与满腮五视图](previews/current/review.png)、[19种形态特写](previews/current/close-ups.png)、[19种形态斜侧面](previews/current/all-models.png)、[左右侧面接触视角](previews/current/contact-angles.png)、[单体动图](previews/current/puff.gif)、[四种形态连续动作](previews/current/face-stability.gif)。

## 本版效果

依据八景U的“ぷくー”画法：两颊各鼓成一个圆球，中间留出嘴部缺口，腮红在鼓起的脸颊上；眼睛保持睁开，嘴换成标准鼓腮的“ハ”形噘嘴。鼓起约1.4秒，满腮时轻颤，再放气，一轮6.4秒；完整体操的鼓腮段使用同一形变。

- 只鼓脸颊：根骨骼不动，身体底部、头顶和背面不变，靠近地面的皮肤不向下移动。
- 眼睛、眉毛、嘴的贴片位置与贴图不变，鼓起的皮肤在它们周围按实际墨迹像素留出边距。
- 侧发不穿模：灵梦、歪脸灵梦的侧发以同一角度刚体外摆（10.75°、14°）；其余形态的侧发、侧帘、发辫与饰物固定，脸颊鼓到它们前方即停住。
- 两种豆形若按原尺寸鼓起会被发辫压出凹痕，因此自动缩小到0.93倍；其余形态保持原尺寸。

第六版（下半脸横向摊开、侧发被顶成翅膀、猫嘴表情）已替代，历史记录见 [puff-v6](../../../preview/review/puff-v6/README.md)。更早版本的记录见[素材检查室说明](../../../preview/review/README.md)。

## 制作与运行资源

制作源为[动作配方](../../../standards/yukkuri-library/motions/variants/contact-safe/recipe.json)和[生成器](../../../standards/yukkuri-library/motions/variants/contact-safe/author.py)，做法说明见[制作源说明](../../../standards/yukkuri-library/motions/variants/contact-safe/README.md)。`scripts/export_motion_corrections.py` 从当前19种游戏形态导出：

| 产物 | 位置 |
| --- | --- |
| 动作与鼓腮形变（第2版格式） | `src/main/resources/assets/mcyukkuri/yukkuri_presentation/motion_corrections.json` |
| 鼓腮表情贴图 | `src/main/resources/assets/mcyukkuri/textures/entity/<实体 ID>/cheek_puff.png` |
| 来源与产物指纹 | [manifest.json](manifest.json) |

运行资源中每种形态包含一份局部细分的身体皮肤、两份贴合皮肤的腮红网格、侧发的刚体偏移和嘴部几何补丁，均作为一个静止空间形变目标，由动作中的包络缩放。`YukkuriModel` 核对骨架名称及顺序后应用；骨架不匹配或格式不是2时保留基础资源并记录警告。基础选用模型、权重与静态网格不改写，伤口／徽章几何指纹不变。

`cheek_puff.png` 取常态的眼睛、眉毛和腮红形状，嘴取各形态自己的鼓腮贴图；两种家油、两种豆形和两种捕食种没有鼓腮贴图，按灵梦标准鼓腮嘴的像素与相对位置生成。

## 其他动作

同一资源替换鼓腮、侧弯、横向体操、跳动、伸展、伸展造型、纵向体操和完整体操八段动作，后七段沿用上一版：统一根部变换，侧弯13°（螺壳形态5°），伸展上限12%，跳动一轮0.8秒、最高点为身体高度的38%，服务器跳动周期同步为0.8秒；每个关键帧按可见网格最低点补偿地面接触。表情时段同时用于正常与受伤五官。魔理沙由 `hand_right` 带动粗辫的四种形态（普通、螺壳、歪脸、家油）另替换摇摆、撑住、压低三段，只在原动作上增加辫子跟随转角，侧弯类动作同样叠加；稀毛与豆形的辫子随皮肤变形，不加此修正。做法见[单辫跟随](../../../standards/yukkuri-library/motions/variants/contact-safe/README.md#魔理沙单辫跟随)。

[素材检查室](../../../preview/review/index.html)与游戏读取同一资源，表情默认“随动作自动切换”，手动选择其他表情时保留用户选择；[前后对比](../../../preview/review/motion-fix/comparison.png)已按本版重绘。旧批准快照及专题预览不证明本轮已确认。

## 验收

当前检查、构建指纹与未覆盖范围统一见[本轮验收](reports/current/README.md)。离线检查与渲染不代表 Minecraft 实机效果，也不代表用户的标准造型确认。
