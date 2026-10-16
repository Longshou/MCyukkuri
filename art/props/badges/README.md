# 金银章挂接

本目录保存游戏挂点制作源 `mounts.json`，分别选取饰物表面、朝向与尺寸，圆润等派生形态经附件导出器适配。运行单位为 16 模型单位 / 格。漫画可见佩戴位置及小说文字依据见 [核读记录](../../../wiki/badges.md)；具体左右侧、尺寸和游戏几何适配为本项目制作选择。

圆牌像素图的制作源是 [`author_badges.py`](author_badges.py)（铜／银／金三档，16×16），由 [`author_products.py`](../../structures/human-world/author_products.py) 调用导出物品贴图，源贴图在该目录的 `products/`。佩戴贴花、物品图标与手帖封面的徽章用同一份像素与色板，没有复制外部漫画像素。挂点、贴图及当前展示版本保持待用户确认（pending_visual_approval）。

## 新牌面（2026-10-16）

16×16 圆顶金属别针：1 像素深色描边、凸起边圈、4 阶明暗（光自左上）、左上高光点，中央是原创的简化油库里头部剪影（6×4，雕刻深色，右下 1 像素亮边）。三档靠亮度与外形区分：

| 档 | 外形 | 色板（描边 / 暗 → 亮 / 高光） |
| --- | --- | --- |
| 铜 `bronze` | 14 像素圆盘、单层边圈，明度最低 | `#3a1d0f` / `#6a3a1c` `#8f5428` `#b6713a` / `#f0c493` |
| 银 `silver` | 16 像素圆、双层边圈（中间沟槽 `#667383`） | `#2c3642` / `#8c98a6` `#bec8d2` `#e8eef3` / `#ffffff` |
| 金 `gold` | 8 齿缺口边（扇贝状），明度居中偏暖 | `#5c340e` / `#ba781e` `#e8aa2e` `#fad258` / `#fff8d0` |

色板常量 `PALETTES` 写在 `author_badges.py`，界面和手帖复用。运行贴图：`textures/item/{bronze,silver,gold}_yukkuri_badge.png`，佩戴贴花也读取同一文件（`YukkuriModel` 取 `textures/item/<档>_yukkuri_badge.png`）。手帖图标（16×16）由 `scripts/export_badges.py` 的 `handbook_png()` 按同一色板绘制。

## 挂点调整（2026-10-16）

牌径（模型单位）：灵梦／魔理沙／帕秋莉 1.9，橙 1.5，爱丽丝 1.6，家油 0.6，蕾米莉亚／芙兰 1.6，螺壳魔理沙 1.5；离身饰物同步。魔理沙（含家油、豆形、缺损形）改到帽带正面右端（避开前方蝴蝶结），橙改到帽下缘正面右侧，牌面法线为正前方，正面与右前 3/4 视角均可见；帕秋莉从金月牙旁移到软帽主体正面左侧。注意 `mounts.json` 的点坐标属于圆润附件导出器的旧基线坐标。

`python scripts/export_badges.py` 读取 `mounts.json`、圆润附件登记与当前运行模型，生成 `src/main/resources/assets/mcyukkuri/yukkuri/badge_mounts.json`。当前覆盖19种身上造型。每个挂点记录角色模型 SHA-256、饰物部件名、三角形、重心权重、静止表面和牌面尺寸。客户端在渲染同一只角色后，以同一套骨骼矩阵变换这三个饰物顶点。模型指纹不匹配时停用该挂点并输出日志，防止把旧顶点编号应用到另一版几何。

该适配不改写角色标准模型、骨架、动画或标准确认指纹。角色当前接入源仍由 `art/source/standard-runtime/` 与家油接入记录管理；角色导出变化后，应重跑徽章导出并更新实际预览。

玩法见[玩法说明](../../../docs/BADGES.md)。

`mounts.json` 的 `detached` 部分对应七种离身饰物物品。摘取／剪断时保存徽章外观级别，物品渲染沿其自身表面挂接圆牌；该外观数据不会授予资格。剪断物品的牌面随所属半边一起变换。

新增捕食种的圆牌挂在 `predator_cap_crown` 帽冠侧面；首轮帽带挂点被帽冠遮挡，已修正。角色模型由捕食种任务维护；其后续几何导出后运行 `python scripts/export_badges.py --mounts-only` 更新指纹及挂点，不写入共享语言文件。两种捕食种仍处于首轮造型迭代，不能将本轮徽章检查当作其整体造型通过。

手册采用本模块制作的金银双色徽章书封面，源图为 `badge_handbook.png`，绘制源在 `scripts/export_badges.py`，运行贴图为 `textures/item/badge_handbook.png`。

2026-10-16 补螺壳魔理沙：挂点位于右前壳表面 `shell_coil_0_3_0_1`，不继承普通魔理沙不存在于壳种的 `hat_band`。源坐标沿用圆润附件导出器的旧身体基线坐标约定，再映射到当前选用网格；派生目标点为 `[5.3, 12.9, -6.4]`，随后由徽章导出器投影至壳的实际三角面。圆牌直径取0.9模型单位，以适应壳表面的窄台阶，避免被相邻壳圈遮住。此位置与尺寸属于项目适配，漫画 M02 与小说 N12 仅支持“徽章附于饰物”，没有提供螺壳专用挂点。

定向复现：先运行 `python scripts/export_rounded_attachments.py --only marisa_tsumuri`，再运行 `python scripts/export_badges.py --mounts-only --only marisa_tsumuri`；后者附 `--check` 可核对该形态及其离身壳挂点，同时保留其他形态的现有派生数据。`python art/props/badges/preview.py --node <Node路径>` 使用当前运行模型、动作和徽章贴图做离线软件渲染，输出 `.local/previews/tsumuri-badge/tsumuri-badge.png`，含正面、右前、右侧与缩壳。挂点仍为 `pending_visual_approval`；离线可见性不代表19种形态的伤口／徽章游戏内显示已验收。
