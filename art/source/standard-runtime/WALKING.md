# 普通走动的油菊摆动

2026-09-13，按用户提出的“普通移动像平移，走路应甩动油菊”调整七种已接入角色：家油灵梦、家油魔理沙，以及普通灵梦、魔理沙、橙、爱丽丝、帕秋莉。[本轮实机动图](previews/current/walking.html)展示慢走与停下；[旋转预览](preview.html?subject=house_reimu&action=waddle)可逐帧查看导出。

家油原先保留了标准 `rump_sway`／`rump_roll` 曲线，但身体、头发和油菊实际绑定在连续的身体截面上，这些网格没有继承臀部骨骼。因此原走动只剩约 1.2% 的轻微压缩。新配方在实际截面上生成绕前侧支点的左右扭动：底部约 ±10°，向上平滑减至 ±3°，身体与贴面头发共用原绑定。五种普通角色保留有效的臀部骨骼，将左右平移和转角增至原幅度的 1.6 倍，并加入身体 ±2° 的反向跟随。完整左右周期仍为 1.2 秒。

服务器现在根据 `aiStep` 中实际发生的水平位移选择走动，避免地面摩擦后的剩余速度过小而误判待机。停下或离地后不继续自动走动；明确指定的动作保持原优先级。家油搬运时，身体采样走动，短辫保持搬运动作的夹持姿势，物品仍跟随实际辫梢。俯身夹取、放下和场景蠕动继续使用各自曲线。

制作配方位于 [`scripts/runtime_waddle.py`](../../../scripts/runtime_waddle.py)，由 [`export_runtime_models.py`](../../../scripts/export_runtime_models.py) 调用。七种 `animation-rig.json`、`runtime.model.json`、游戏 `yukkuri/<角色>.json` 与 `preview-data.js` 已同步，来源与指纹仍由 [provenance.json](provenance.json) 和 [runtime-sources.json](../../runtime-sources.json)登记。静态几何、骨架层级、顶点绑定、贴图、所选 `.bbmodel` 和另外 18 段指令动作均未变。徽章挂接和伤口运行数据随角色文件指纹重新导出，不修改已确认的标准源。

参考核读了本机八景 U v8.3 `sharedassets0.assets` 中的 Sprite 7030、6523，分别对应缓存字段 `正面ケツ振りボディグラ1ベース[0]`、`正面ケツ振りボディグラ3ベース[0]`。可见臀部倾斜和压低，支持后部参与形变的方向；这些是摆臀动作的身体图层，不能证明普通行走的完整周期。本轮三维支点、幅度和行走节奏是制作适配，原图未进入发行资源。提取元数据和原图保存在项目临时参考目录 `.local/waddle/reference/`。

当前数据、实际 Minecraft 渲染和慢走状态检查见[验收记录](../../../docs/validation/walking/current/README.md)。本轮为待用户观察的动作适配，不新增标准造型批准，也不代表其他并行任务的整个工作副本已经通过。
