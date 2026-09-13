# 金银章挂接

本目录保存游戏挂点制作源 `mounts.json`，按九种角色分别选取饰物表面、朝向与尺寸。运行单位为 16 模型单位 / 格。漫画可见佩戴位置及小说文字依据见 [核读记录](../../../wiki/badges.md)；具体左右侧、尺寸和游戏几何适配为本项目制作选择。

圆牌像素图的制作源仍是 [`author_products.py`](../../structures/human-world/author_products.py)，对应源贴图保存在该目录的 `products/`。本次移除了旧绶带造型，改为圆形金属牌；没有复制外部漫画像素。挂点、贴图及当前展示版本保持待用户确认。

`python scripts/export_badges.py` 读取 `mounts.json` 及九份当前运行模型，生成 `src/main/resources/assets/mcyukkuri/yukkuri/badge_mounts.json`。每个挂点记录角色模型 SHA-256、饰物部件名、三角形、重心权重、静止表面和牌面尺寸。客户端在渲染同一只角色后，以同一套骨骼矩阵变换这三个饰物顶点。模型指纹不匹配时停用该挂点并输出日志，防止把旧顶点编号应用到另一版几何。

该适配不改写角色标准模型、骨架、动画或标准确认指纹。角色当前接入源仍由 `art/source/standard-runtime/` 与家油接入记录管理；角色导出变化后，应重跑徽章导出并更新实际预览。

查看[本轮实机预览和验证](../../../docs/validation/badges/README.md)及[玩法说明](../../../docs/BADGES.md)。

`mounts.json` 的 `detached` 部分对应七种离身饰物物品。摘取／剪断时保存徽章外观级别，物品渲染沿其自身表面挂接圆牌；该外观数据不会授予资格。剪断物品的牌面随所属半边一起变换。

新增捕食种的圆牌挂在 `predator_cap_crown` 帽冠侧面；首轮帽带挂点被帽冠遮挡，已通过当前截图修正。角色模型由捕食种任务维护；其后续几何导出后运行 `python scripts/export_badges.py --mounts-only` 更新指纹及挂点，不写入共享语言文件。两种捕食种仍处于首轮造型迭代，不能将本轮徽章检查当作其整体造型通过。

手册采用本模块制作的金银双色徽章书封面，源图为 `badge_handbook.png`，绘制源在 `scripts/export_badges.py`，运行贴图为 `textures/item/badge_handbook.png`。
