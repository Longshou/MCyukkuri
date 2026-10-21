# 家油面部与走动挂接

五官适配配方为 [recipe.json](recipe.json)，制作源为 [`runtime_house_face.py`](../../../../scripts/runtime_house_face.py)，导出入口为 [`export_house_face_motion.py`](../../../../scripts/export_house_face_motion.py)。影响的模型、骨骼和快照指纹见 [manifest.json](manifest.json)。搬运夹持位置见[家油交互动作](../HOUSE_INTERACTIONS.md)。

行走时五官曲线按每个面片的实际高度从相邻身体分段插值；摆动峰值沿法线留 0.02 模型单位余量，静止与周期边界归零。适配器只生成十个 `face_*_skin_anchor` 的 `waddle` 曲线，不改静态顶点、UV、法线、权重、支点、五官子骨控制或其他动作。慢走使用同一条 `waddle` 曲线。

家油身体表面 `house_body_surface` 的共享光照法线按静态位置（精度 0.00001 模型单位）和原始法线分量（精度 0.001）分组；蒙皮后累加相邻三角面的叉积并归一化。不同原始法线保持分组以保留硬转角。Minecraft 的 [YukkuriModel.java](../../../../src/main/java/io/github/longshou/mcyukkuri/client/YukkuriModel.java)、伤口替换表面与素材运行时 [`render.js`](../../../../standards/yukkuri-library/runtime/render.js) 使用相同规则。

从仓库根目录运行 `python scripts/export_house_face_motion.py --publish` 发布当前适配；不带 `--publish` 时输出到 `.local/house-face/staged/`。该导出从未修改的身体曲线重新计算五官曲线，不累加位移，并同步两种家油的运行模型、骨架、来源登记和预览条目。
