# 表情与动作适配技能

凡新做或修改动作片段（`faces`）、演出行（`moments.json`）、`presentation/expressions.json` 里的表情时间线，或给某段动作选脸的包，开工前读本页，收工前跑[检查器](#3-检查)，把新经验补进[已踩过的坑](#已踩过的坑)。表情本身的造型与认可见 [STANDARD-EXPRESSIONS.md](STANDARD-EXPRESSIONS.md)，通用约束见[制作约定](AGENTS.md)，流程见[美术与动作制作者](../../docs/roles/art.md)。游戏里反应脸的保持与降级由 `FaceTier`（Java）管，本页只管制作源里写进片段的脸。数值以 `scripts/check_face_fit.py` 顶部常量为准，本页与源冲突时以源为准并当场改正本页。

## 1. 规则

1. **一张脸至少停留 0.5 秒（10 tick）。** 观众读不出更短的脸，只觉得闪。表情按“看起来”算：眼、眉、嘴的图和几何都相同的相邻脸是同一张脸（腮红、泪、效果层不算）；循环片段首尾相接的同一张脸算一段。0.25 秒内的闭眼脸夹在同一张脸中间是眨眼，不算切换（咀嚼循环里的闭眼帧同理）。
2. **一拍至多一次情绪变化。** 一个片段或一个演出步内每秒切换不超过 1.5 次（循环含首尾衔接）；起因变了（新的事件、新的接触）才是新的一拍，按新的一拍写。
3. **相邻两张脸眼睛不跳。** 两只眼的墨迹中心位移不超过 0.45 模型单位、墨迹高度变化不超过 0.9，除非两张脸之间有闭眼脸（眨眼、闭眼）当过渡，或是有意的转视线（视线脸本身位移小，不用靠跳）。`puff`、`puff_open`、`puff_teary` 的眼睛整体上移约 1.2 单位，进出它们必过闭眼脸，不在循环里与普通脸来回切。
4. **重复动作整段只用一张脸。** 推、撞、挣、跺这类循环让节奏由身体表达，脸整段保持；要变就最多变一次，并落在一个身体节点上（出力的顶点、结束后的回正）。
5. **脸与身体阶段、真实状态相符。** 脸跟着身体的这一拍（预备、发力、收尾）走，不超前也不拖尾；脸只表达个体此刻真实的状态，受伤、窒息、尿急等身体状态脸由服务端优先（片段的 `expression_yield`），片段不假装没事。玩闹类动作（相扑、追逐）用得意、笑，不用凶脸。这一条没有自动检查，由作者对着片段逐拍核对，审图时重点看。
6. **脸只在亲眼看到的片段里写。** 看不到的拍不写脸；段与段之间的衔接靠上一段的结束脸与下一段的起始脸眼位相近。

## 2. 阈值从哪来

- 停留 0.5 秒：现有约 600 段脸停留的中位数 0.75 秒，30% 分位正是 0.5 秒；表情层的反应脸 `SHORT` 档为 30 tick（1.5 秒），片段内的拍可以更短，但不短过 10 tick。
- 眼位 0.45：除 `puff` 一族外，54 张睁眼脸两两共 1431 对里 98% 的眼位差不超过 0.45（超过的都涉及 `sleepy`、`dull` 这类眼位本就偏低或偏高的脸）；`puff` 族与任何睁眼脸的眼位差都不小于 0.94。
- 眼位按预设表情的眼部贴图墨迹中心加几何偏移算，与 `test_expression_geometry.py` 和导出器读的是同一批预设与脸元素盒子。

## 3. 检查

```
<py> scripts/check_face_fit.py --check [--all | --changed REV]   # 超出基线即非零退出
<py> scripts/check_face_fit.py --list                            # 全部发现，最差的在前
<py> scripts/check_face_fit.py --transitions sumo_shove          # 某片段或演出行的每段停留与相邻脸眼位指标
```

- 读的来源：lore `recipe.json` 的片段 `faces`（`base` 预设已展开）、`interactions/toilet-contact/recipe.json`、`presentation/moments.json` 步的 `faces`、`presentation/expressions.json`。演出行里与片段自带脸完全相同的步不重复报，改片段时同提交改这些行。
- 基线 `scripts/face_fit_allow.json` 记录既有违规的每条时间线每类数量，只降不升；新做或修改的时间线必须干净，改好后用 `--write-baseline` 收紧。
- `scripts/export.py`（`face-fit`）与 `scripts/pkg.py land`（脸或动作源改动时）自动调用。
- 改了 `faces` 要重新导出：`<py> scripts/export.py --family motion-corrections --family moments --family ecology-expressions`（动作导出约 8～30 分钟，放后台）。

## 已踩过的坑

- 相扑出力循环曾写成 `smug / puff_open / endure / puff_open / smug`，每 0.18 秒换一次，且 `puff_open` 的眼睛比 `smug` 高 1.36 单位：眼睛上下跳、闪烁。改为整段 `smug`。
- 推挤（`push`）、挣扎（`struggle`）同样把 `angry` 与 `puff` 交替，每次跳 1.32 单位：改为整段 `angry`。
- 演出行与片段各存一份相同的脸：只改一处会让另一处继续闪；检查器把它们当同一份，改时一起改。
- 脸时间线可以长于运动循环（表演时钟长于 1 秒循环，末段常写到 63 秒当“直到结束”）：检查器对这种片段不做首尾衔接，也不拿尾段长度算切换率。
- A/`_b` 帧是同一张脸的逐帧动画（`registry.json` 的 `frame_ticks`），不当切换；片段里只写 A 帧 ID。
- 缺素材的脸会按片段的 `face_fallback` 退到别的脸（如 `puff`→`chewing_closed`/`cry`/`angry`）：检查器按预设本身算，新增回退链时核对退到的脸眼位是否相近。
- 如厕片段换入 `impact_teary` 后眼部出现线段式闪烁，不是时间线问题：它的泪卡纵向拉长盖住眯眼，各槽位烘焙在同一深度，眼与泪墨迹互相 z-fighting（`teary` 等含泪脸同样存在，只是重叠小）。现由渲染按 `Skin.java` 的 `FACE_LAYERS` 给每个槽位分层，`scripts/check_face_layers.py` 把关；选脸时片段帧里看到细线闪动，先跑它。
- 制裁宣告（`behavior_sanction_declare`）此前写成 `angry → puff → cheek_puff` 无闭眼过渡且 0.28 秒短切，制裁接近（`behavior_sanction_approach`）误借 `look_around` 的 `neutral`/`confused`；改为整段保持新标准凶悍脸 `fierce`，既贴合凶悍个体状态，又消除眼位跳变与短闪并收紧了基线。

## 补充规则

每个碰脸时间线的包在此补一条新经验，并同提交更新基线。
