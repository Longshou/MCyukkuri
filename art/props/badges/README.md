# 金银章挂接

本目录保存游戏挂点制作源 `mounts.json`，分别选取饰物表面、朝向与尺寸，圆润等派生形态经附件导出器适配。运行单位为 16 模型单位 / 格。漫画可见佩戴位置及小说文字依据见 [核读记录](../../../wiki/badges.md)；具体左右侧、尺寸和游戏几何适配为本项目制作选择。

圆牌像素图的制作源仍是 [`author_products.py`](../../structures/human-world/author_products.py)，对应源贴图保存在该目录的 `products/`。本次移除了旧绶带造型，改为圆形金属牌；没有复制外部漫画像素。挂点、贴图及当前展示版本保持待用户确认。

`python scripts/export_badges.py` 读取 `mounts.json`、圆润附件登记与当前运行模型，生成 `src/main/resources/assets/mcyukkuri/yukkuri/badge_mounts.json`。当前覆盖19种身上造型。每个挂点记录角色模型 SHA-256、饰物部件名、三角形、重心权重、静止表面和牌面尺寸。客户端在渲染同一只角色后，以同一套骨骼矩阵变换这三个饰物顶点。模型指纹不匹配时停用该挂点并输出日志，防止把旧顶点编号应用到另一版几何。

该适配不改写角色标准模型、骨架、动画或标准确认指纹。角色当前接入源仍由 `art/source/standard-runtime/` 与家油接入记录管理；角色导出变化后，应重跑徽章导出并更新实际预览。

玩法见[玩法说明](../../../docs/BADGES.md)。

`mounts.json` 的 `detached` 部分对应七种离身饰物物品。摘取／剪断时保存徽章外观级别，物品渲染沿其自身表面挂接圆牌；该外观数据不会授予资格。剪断物品的牌面随所属半边一起变换。

新增捕食种的圆牌挂在 `predator_cap_crown` 帽冠侧面；首轮帽带挂点被帽冠遮挡，已修正。角色模型由捕食种任务维护；其后续几何导出后运行 `python scripts/export_badges.py --mounts-only` 更新指纹及挂点，不写入共享语言文件。两种捕食种仍处于首轮造型迭代，不能将本轮徽章检查当作其整体造型通过。

手册采用本模块制作的金银双色徽章书封面，源图为 `badge_handbook.png`，绘制源在 `scripts/export_badges.py`，运行贴图为 `textures/item/badge_handbook.png`。

2026-10-16 补螺壳魔理沙：挂点位于右前壳表面 `shell_coil_0_3_0_1`，不继承普通魔理沙不存在于壳种的 `hat_band`。源坐标沿用圆润附件导出器的旧身体基线坐标约定，再映射到当前选用网格；派生目标点为 `[5.3, 12.9, -6.4]`，随后由徽章导出器投影至壳的实际三角面。圆牌直径取0.9模型单位，以适应壳表面的窄台阶，避免被相邻壳圈遮住。此位置与尺寸属于项目适配，漫画 M02 与小说 N12 仅支持“徽章附于饰物”，没有提供螺壳专用挂点。

定向复现：先运行 `python scripts/export_rounded_attachments.py --only marisa_tsumuri`，再运行 `python scripts/export_badges.py --mounts-only --only marisa_tsumuri`；后者附 `--check` 可核对该形态及其离身壳挂点，同时保留其他形态的现有派生数据。`python art/props/badges/preview.py --node <Node路径>` 使用当前运行模型、动作和徽章贴图做离线软件渲染，输出 `.local/previews/tsumuri-badge/tsumuri-badge.png`，含正面、右前、右侧与缩壳。挂点仍为 `pending_visual_approval`；离线可见性不代表19种形态的伤口／徽章游戏内显示已验收。
