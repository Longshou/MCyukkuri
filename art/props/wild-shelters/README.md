# 野外住所运行选用

制作源为[野外住所包](../../../standards/yukkuri-library/ecology/wild-shelters/README.md)，这里保存当前选用的可编辑模型、静态三角网格与原创图集。天然洞穴叶床、细枝结界及浅坑布置已于 2026-09-13 经用户确认；纸箱和便所造型仍待确认。确认范围和文件指纹见 `manifest.json`。

| ID | 游戏运行文件 | 显示 |
| --- | --- | --- |
| `forest_burrow` | `assets/mcyukkuri/yukkuri_props/forest_burrow.json` | 现成洞穴内的零散叶床，无土墙或屋顶 |
| `nest_boundary` | `assets/mcyukkuri/yukkuri_props/nest_boundary.json` | 洞口至多三根细枝，靠向现有侧壁，无承重或防御碰撞 |
| `cardboard_shelter` | `assets/mcyukkuri/yukkuri_props/cardboard_shelter.json` | 开口、折盖、胶带、标签与箱内底面 |
| `wild_latrine` / `wild_latrine_dirty` | `assets/mcyukkuri/yukkuri_props/<ID>.json` | 围枝浅土面，脏污切换 |

以上路径均相对 `src/main/resources/`；各图集在 `assets/mcyukkuri/textures/props/<ID>.png`。住所碰撞在 `data/mcyukkuri/shelters/<ID>.json`。无骨架或独立动作；`ShelterRenderer` 负责实际床位、入口挂接、垫料及纸箱存粮，`CareRenderer` 负责便所。物品缩略模型复用相同导出。

重建通过制作包的 `build.py` 同步，禁止只改本目录或打包资源。指纹见 `manifest.json`。当前[预览](../../../standards/yukkuri-library/ecology/wild-shelters/previews/current/shelters-sheet.png)来自实际导出网格；引擎验收见[生活环境说明](../../../docs/WILD_SHELTERS.md)。
