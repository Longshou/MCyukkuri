# 表情第六轮

[当前比较页](../batch-16-40.html)仅列 N033 棕灰配色。用户认可第五轮样式，未单独指定 A/B/C，因此保留三款，并统一将红色、灰紫色改为此前的偏棕灰方向。

本轮只改变虹膜、暗瞳孔和眼线的 RGB 配色。眼部透明轮廓、眼白像素、尺寸和几何偏移均与第五轮相同；加宽后的虹膜和 B 版暗瞳孔保持原宽度。色阶上部为 `(74,67,63)`，中部为 `(116,107,96)`，下部为 `(153,143,128)`；眼线为 `(63,51,46)`，暗瞳孔为 `(45,40,36)`。

N020 已按用户“20通过”确认第五轮完整展示版，保存配方及部件指纹，移出待审。批准记录位于 `expressions/component-review/n016-n040-decisions.json` 的 `confirmed_versions.N020`。N033 已认可的是样式方向，新的棕灰配色随后获用户“通过”确认。三款保存到 `expressions/variants/dull/`；A 为当前默认配方，B/C 为同编号备选，各自保存配方及部件指纹。

制作阶段的命令如下；第一条会重放历史待审状态，已确认后不要重跑。仅刷新已确认导出和对照页时，运行后三条：

```sh
python tools/review_expression_round6.py
python tools/sync_expression_exports.py puff_teary dull
python tools/build_expression_round5_review.py --round 6 --previous-round 5
python validate.py
```

制作脚本应用本轮选择，后续不得覆盖更新的决定。候选来源为本目录 `candidates.json`、`parts/`，模板为 `review-template.html`；第五轮配方是前后对比基线。通用比较脚本沿用第五轮入口，参数指定当前轮次及比较基线。

N020 和 N033 默认 A 的配方及状态已同步到六个标准角色导出；本轮未更新 Minecraft 接入快照。当前实拍见[N033 棕灰对照](../../../previews/current/n033-variants.png)，检查见[第六轮报告](../../../reports/current/expression-round6.json)。
