# 当前动作适配

2026-10-16，第 11 版：只为 `marisa` 增加七段水上动作：持枝划船、收食入帽、帽内进食、扶稳饮水、帽中睡眠、醒来、登船收身。制作源在 [lore](../../../standards/yukkuri-library/motions/variants/lore/README.md)，运行行为见[水边生活](../../../docs/AQUATIC_MARISA_IMPLEMENTATION.md)。木棒跟随辫梢，亲油保持坐姿；当前离线预览由 `aquatic_preview.py` 写入 `.local/previews/aquatic/`。状态为待用户复查，其他 18 种形态资源内容不变。

2026-10-14，第 10 版：在第 9 版的世界观动作上，把魔理沙的摘帽换成用辫子摆弄帽子的 10 段（`hat_off`、`hat_hold`、`hat_on`、`hat_off_self`、`hat_on_self`，以及水生大帽的 `_aq` 版本），去掉 `doff_hat`。只有 `marisa` 的片段有增减，其他 18 种形态的片段、鼓腮形变和贴图逐字节不变，基础模型指纹不变。辫子和帽子的轨迹由 [hat.py](../../../standards/yukkuri-library/motions/variants/lore/hat.py) 按[帽子翻过来](../../../standards/yukkuri-library/interactions/marisa-hat-vessel/README.md)的站位求解；2026-10-15 起帽子倒过来放下时帽冠压扁（陆地贴地、水上略压）；同日为让新生儿沿抛物线越过帽檐落进帽里，陆上帽子改放在出口正前方，辫子动作随新站位重新求解。多毛灵梦补上呆滞与白眼哭泣两张脸（与灵梦同一张图集），其 `bereaved` 不再回落到惊讶。

第 9 版：同一资源新增 27 段[世界观动作](../../../standards/yukkuri-library/motions/variants/lore/README.md)，作为新的片段名合并进 19 种形态。

- 日常、社交与情绪 15 段。
- 分娩与接生 7 段（第 10 版起摘帽改为辫子动作）。
- 水生魔理沙的帽船 6 段。

每段按种族门控，只编入适用的形态，并带表情编排。世界观动作预览由 [births.py](../../../standards/yukkuri-library/motions/variants/lore/births.py) 在本机生成到 `.local/previews/lore/index.html`，不入库：一页演示夫妻、接生与出生的子油，一页逐个播放动作。呆滞脸贴图来源见 [extra-faces.json](extra-faces.json)。状态为待复查。第 8 版加入的魔理沙单辫跟随已随单辫动作保存。

2026-10-17，第十版鼓腮：整身挤压拉伸取代第七版的两颊椭球形变，状态为待用户复查。第七版的鼓包像下颌肉瘤、侧发被顶出，已退役；本节之外对第七版的描述只作历史记录。

## 本版效果

鼓腮（ぷくー）是一段整身根骨骼挤压拉伸，一轮2.8秒，各段按时长比例划分，完整体操10.4～13.6秒的鼓腮段（3.2秒）复用同一曲线：

| 段 | 比例 | 根缩放（宽,高,深，标准身体） | 脸 |
| --- | --- | --- | --- |
| 吸气 | 0～0.12 | 缩到(1.03, 0.94, 1.03)，前倾3° | 服务器当前表情 |
| 鼓起 | 0.12～0.30 | 带35%过冲鼓到(1.14, 1.12, 1.14)，前倾回0 | 0.10起 `puff`（无此贴图的形态用 `cheek_puff`） |
| 保持 | 0.30～0.80 | 约±2.5%呼吸（1.1Hz）加约1.2%快速轻颤（5Hz），侧倾≤1.2° | `puff` |
| ぷしゅー | 0.80～0.90 | 迅速泄成(1.05, 0.93, 1.05)压扁 | 0.84起 `cheek_puff` |
| 回落 | 0.90～1.0 | 压扁缓回1 | `cheek_puff` |

- 整身俯仰≤3°、侧倾≤1.2°、不转向，远低于12°限制；落地补偿使身体底部始终贴地，头发、帽子、翅膀与辫子随根骨骼整体缩放，无新增穿插。
- 豆形只横向鼓到约(1.10, 1.03, 1.10)，不拉高；歪脸（deibu）(1.07, 1.07, 1.07)，避免占地过大。形态按 ID 归类（含 `bean`、`deibu`）。
- 脸：有 `puff.png` 的13种形态鼓起期用自己的怒目噘嘴脸；其余（豆形、家油、芙兰朵露、蕾米莉亚）用生成的 `cheek_puff`。`cheek_puff` 以 `angry.png` 的眼睛与眉毛为底，嘴取标准鼓腮的小“ハ”形，腮红加深并加一对浅色高光。
- `expression_yield: ["cry"]`：服务器给出受伤哭脸时，哭脸优先于鼓腮时间线；吸气段不覆盖服务器表情，调用方先设的 `angry` 等脸保持到鼓起。
- 不再输出 `puff_surface`、`puff_attachments`、`puff_offsets`、`puff_envelope`；`YukkuriModel` 与 `pose.js` 对缺失项已空安全，无需改动。
- 时长常量 `YukkuriAction.PUFF` 同步为2.8秒；服务器调用方（`playAction("puff")` 或40～120 tick 的反应）每次一到两轮，轮末回到静止。
- 依据：Wiki 与本地参考只记载“鼓起脸颊、绷脸，随后睁大眼睛并跳起”（M01-003）和八景U的闭眼／睁眼噗咕脸，没有记载整身充气或ぷしゅー；整身鼓起与泄气是为无颈圆团子身体做的项目风格化编排。



## 制作与运行资源

制作源为[动作配方](../../../standards/yukkuri-library/motions/variants/contact-safe/recipe.json)和[生成器](../../../standards/yukkuri-library/motions/variants/contact-safe/author.py)，做法说明见[制作源说明](../../../standards/yukkuri-library/motions/variants/contact-safe/README.md)。`scripts/export_motion_corrections.py` 从当前19种游戏形态导出：

| 产物 | 位置 |
| --- | --- |
| 动作（第2版格式，鼓腮为整身根骨骼片段） | `src/main/resources/assets/mcyukkuri/yukkuri_presentation/motion_corrections.json` |
| 鼓腮表情贴图 | `src/main/resources/assets/mcyukkuri/textures/entity/<实体 ID>/cheek_puff.png` |
| 来源与产物指纹 | [manifest.json](manifest.json) |

运行资源中每种形态包含各动作的根骨骼关键帧、表情时间线和 `cheek_puff` 的嘴部几何补丁（第十版起不再含脸颊形变目标）。`YukkuriModel` 核对骨架名称及顺序后应用；骨架不匹配或格式不是2时保留基础资源并记录警告。基础选用模型、权重与静态网格不改写，伤口／徽章几何指纹不变。

`cheek_puff.png` 取愤怒表情的眼睛、眉毛和腮红形状（腮红加深并加高光），嘴取各形态自己的鼓腮贴图；两种家油、两种豆形和两种捕食种没有鼓腮贴图，按灵梦标准鼓腮嘴的像素与相对位置生成。

## 其他动作

同一资源替换整身鼓腮、侧弯、横向体操、跳动、伸展、伸展造型、纵向体操和完整体操八段动作，后七段沿用上一版：统一根部变换，侧弯13°（螺壳形态5°），伸展上限12%，跳动一轮0.8秒、最高点为身体高度的38%，服务器跳动周期同步为0.8秒；每个关键帧按可见网格最低点补偿地面接触。表情时段同时用于正常与受伤五官。魔理沙由 `hand_right` 带动粗辫的四种形态（普通、螺壳、歪脸、家油）另替换摇摆、撑住、压低三段，只在原动作上增加辫子跟随转角，侧弯类动作同样叠加；稀毛与豆形的辫子随皮肤变形，不加此修正。做法见[单辫跟随](../../../standards/yukkuri-library/motions/variants/contact-safe/README.md#魔理沙单辫跟随)。

[素材检查室](../../../preview/review/index.html)与游戏读取同一资源，表情默认“随动作自动切换”，手动选择其他表情时保留用户选择。

## 验收

离线检查与渲染不代表 Minecraft 实机效果，也不代表用户的标准造型确认。
