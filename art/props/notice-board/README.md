# 告示板

城镇路口与徽章会馆用的挂墙告示板，原生方块模型（元素与 16 像素贴图）。框、披檐与笔槽沿用长椅与木桌的村庄木纹贴图（`textures/block/village_wood_{planks,side,post}.png`，见 [`author_furniture.py`](../../structures/human-world/author_furniture.py)）；板面与传单取共用色板 `art/props/palette.json`（`wood`、`paper`、`chili`、`navy`、`berry`、`gold`、`steel`）。制作源为 `build.py`，从仓库根目录运行 `<py> art/props/notice-board/build.py` 写出运行资源与 `manifest.json` 指纹，加 `--check` 只比较；`<py> art/props/notice-board/preview.py` 出告示板与[报纸堆](../newspaper-stack/README.md)的离线预览到 `.local/previews/town-blocks/`。

| 部分 | 外形 |
| --- | --- |
| 板 | 16 宽、15 高（离地 1 像素起）、贴墙 3 像素深；深色竖木板，旧图钉眼，右上一枚旧图钉和撕剩的纸角 |
| 框与笔槽 | 两侧 1 像素立柱，下沿 3 像素深的笔槽 |
| 披檐 | 贴墙铰起、前沿下斜 22.5° 的单坡小檐，挑出 5 像素 |
| 传单 | 走失油库里（红头、黑帽油库里像、底边撕条）、徽章考试（蓝头、金徽章）、领养（粉头、心形，旧纸折角），各钉一枚图钉 |

模型画成朝北、背靠南侧方块，方块状态按 `facing`（板面朝向）转动，同原版墙上告示牌。方块在 `human/NoticeBoardBlock`：只能挂在背后实心的方块上（同墙上告示牌的判定，栅栏、墙也可），背后方块没了就掉落；碰撞为贴墙一个 16×15×3 像素的盒子；没有界面。摆放时板面离地一格更接近视线高度。贴图为 `textures/block/notice_board_{back,flyers}.png`。方块、物品模型、方块状态与战利品表都由 `build.py` 写出。
