# 报纸堆

城镇街角与小卖部门口的旧报纸捆，原生方块模型（元素与 16 像素贴图），颜色取共用色板 `art/props/palette.json`（`paper` 纸面，`straw` 麻绳，`chili`／`sky`／`berry`／`gold` 报头红印与夹页广告）。制作源为 `build.py`，从仓库根目录运行 `<py> art/props/newspaper-stack/build.py` 写出运行资源与 `manifest.json` 指纹，加 `--check` 只比较。预览与告示板共用 [`../notice-board/preview.py`](../notice-board/preview.py)。

| 状态 | 模型 | 外形 |
| --- | --- | --- |
| `bundles=1` | `models/block/newspaper_stack_1.json` | 一捆 10×4×8 像素的对折报纸，麻绳十字捆扎，顶上一个绳结 |
| `bundles=2` | `models/block/newspaper_stack_2.json` | 第二捆斜放 22.5° 压在上面，版面掉头 |
| `bundles=3` | `models/block/newspaper_stack_3.json` | 第三捆稍偏地摞在最上，高 12 像素 |

`facing` 随放置者朝向转动整堆。贴图为 `textures/block/newspaper_stack_{top,fold,edge,twine}.png`：头版（报头与红印、标题、照片、小字栏）、折边、切边（夹着两三张彩色广告单）与麻绳。方块在 `human/NewspaperStackBlock`：碰撞为一个 10×4n×10 像素的盒子，各朝向相同；需要下方中心有支撑，支撑没了就掉落；手持报纸堆对着它使用加一捆（至多三捆，同蜡烛），掉落按捆数计。纯装饰，流浪油仍睡旁边散放的纸。方块、物品模型、方块状态与战利品表都由 `build.py` 写出。
