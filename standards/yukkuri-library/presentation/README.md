# 演出数据

`moments.json` 保存演出组装件。`moments` 的每行有 `loop` 与有序 `steps`；每步填写 `clip`、`intensity`、`tempo`、`faces`（`[步内秒数, 表情 ID]`），可选 `emote`、原版简单粒子的 `particle`、原版声音的 `sound`。时长由片段长度除以 `tempo` 得到。幅度作用于平移、原地跳动、偏航及缩放相对默认姿势的变化；整体俯仰与横滚合计限制为 12°。

`behaviors`、`reactions`、`groups` 把现有语义 ID 映射到演出行；`conditions` 是身体状态优先表情，`predator_faces` 保留捕食种的显式表情映射。`face_condition` 支持移动状态与如厕急迫状态，`condition_face` 为条件成立时的表情。空表情序列沿用当前身体状态与片段表情。

运行 `scripts/export_moments.py` 导出，`--check` 只检查引用及导出一致性。表情复用既有全模型覆盖检查。运行 `scripts/preview.py moment wet_relief` 生成渲染条与 MP4，`--decision <问题>` 登记待查看；`scripts/preview.py kit` 查看动作、表情、演出行与符号目录。离线遮挡面板示意名牌透视效果，真实屋顶遮挡仍用游戏截图核对。

符号显示规则见[日常生态](../../../docs/FEATURES.md#日常生态)。粒子与声音在服务端步开始时触发。演出只观察既有行为，不决定实际移动或行为成功。
