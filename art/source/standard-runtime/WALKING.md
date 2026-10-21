# 普通走动的油菊摆动

当前走动适配覆盖家油灵梦、家油魔理沙，以及普通灵梦、魔理沙、橙、爱丽丝和帕秋莉。制作配方在 [`scripts/runtime_waddle.py`](../../../scripts/runtime_waddle.py)，由 [`export_runtime_models.py`](../../../scripts/export_runtime_models.py) 导出；角色快照与来源指纹由 [provenance.json](provenance.json) 和 [`runtime-sources.json`](../../runtime-sources.json) 登记。

家油身体、贴面头发和油菊绑定在连续身体截面上，故走动使用截面绕前侧支点扭转：底部约 ±10°，向上平滑减至 ±3°。五种普通角色保留臀部骨骼，走动平移和转角为原幅度的 1.6 倍，身体以 ±2° 反向跟随。左右完整周期为 1.2 秒。

服务器按 `aiStep` 中实际发生的水平位移选择走动，停止或离地时退出；明确指定的动作保持原优先级。家油搬运时身体保留步态，短辫与物品继续跟随搬运夹持姿势；拾取、放下和场景蠕动使用各自动作曲线。

方向参考为本机八景 U v8.3 `sharedassets0.assets` 的 Sprite 7030 与 6523（缓存字段 `正面ケツ振りボディグラ1ベース[0]`、`正面ケツ振りボディグラ3ベース[0]`）。原图可见臀部倾斜与压低，只支持后部参与形变的方向，不证明普通行走周期；三维支点、幅度与节奏为项目适配。来源状态以本目录 `provenance.json` 与 `art/runtime-sources.json` 登记为准。
