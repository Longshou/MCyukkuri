# 野外住所运行选用

制作源为[野外住所包](../../../standards/yukkuri-library/ecology/wild-shelters/README.md)，这里保存当前选用的可编辑模型、静态三角网格与原创图集。2026-09-13 已确认此前天然洞穴叶床、细枝结界及浅坑布置；本轮连续共巢叶垫和个人小叶巢均待用户确认，纸箱和便所也仍待确认。确认范围和文件指纹见 `manifest.json`。

| ID | 游戏运行文件 | 显示 |
| --- | --- | --- |
| `forest_burrow` | `assets/mcyukkuri/yukkuri_props/forest_burrow.json` | 相邻共巢睡位连成的厚实、连续叶垫；叶、干草和细枝仅在整片外缘形成围沿，无土墙或屋顶 |
| `personal_leaf_nest` | `assets/mcyukkuri/yukkuri_props/personal_leaf_nest.json` | 后期子体在原睡位完成的较小、碗形个人叶巢；不产生新住所外壳或物理睡位 |
| `nest_boundary` | `assets/mcyukkuri/yukkuri_props/nest_boundary.json` | 洞口至多三根细枝，靠向现有侧壁，无承重或防御碰撞 |
| `cardboard_shelter` | `assets/mcyukkuri/yukkuri_props/cardboard_shelter.json` | 开口、折盖、胶带、标签与箱内底面；R4 改用共用色板的纸板阶，加折盖接缝与折痕，几何不变 |
| `wild_latrine` / `wild_latrine_dirty` | `assets/mcyukkuri/yukkuri_props/<ID>.json` | 围枝浅土面，脏污切换；R4 只并入共用色板 |

以上路径均相对 `src/main/resources/`；各图集在 `assets/mcyukkuri/textures/props/<ID>.png`。住所碰撞在 `data/mcyukkuri/shelters/<ID>.json`。无骨架或独立动作；`ShelterRenderer` 按实际住户的睡位组合共巢外缘，并在已完成个人巢的原睡位显示小叶巢；`CareRenderer` 负责便所。物品缩略模型复用相同导出。

重建通过制作包的 `build.py` 同步，禁止只改本目录或打包资源。指纹见 `manifest.json`。`build.py` 同时用实际导出网格在本机生成预览，不入库；引擎验收见[生活环境说明](../../../docs/WILD_SHELTERS.md)。
