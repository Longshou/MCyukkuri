# 报纸堆

城镇街角与小卖部门口的旧报纸捆，原生方块模型（元素与 16 像素贴图），颜色取共用色板 `art/props/palette.json`（`paper` 纸面，`straw` 麻绳，`chili`／`sky`／`berry`／`gold` 报头红印与夹页广告）。制作源为 `build.py`，从仓库根目录运行 `<py> art/props/newspaper-stack/build.py` 写出运行资源与 `manifest.json` 指纹，加 `--check` 只比较。预览与告示板共用 [`../notice-board/preview.py`](../notice-board/preview.py)。

| 状态 | 模型 | 外形 |
| --- | --- | --- |
| `bundles=1, taken=0` | `models/block/newspaper_stack_1.json` | 一捆 10×4×8 像素的对折报纸，麻绳十字捆扎，顶上一个绳结 |
| `bundles=2, taken=0` | `models/block/newspaper_stack_2.json` | 第二捆斜放 22.5° 压在上面，版面掉头 |
| `bundles=3, taken=0` | `models/block/newspaper_stack_3.json` | 第三捆稍偏地摞在最上，高 12 像素 |
| `bundles=1..3, taken=1..3` | `models/block/newspaper_stack_{1,2,3}_t{1,2,3}.json` | 最上一捆麻绳解开，按剩余散张（3／2／1 像素高）收薄并略错开页角，下方整捆保持捆扎 |

`facing` 随放置者朝向转动整堆。贴图为 `textures/block/newspaper_stack_{top,fold,edge,twine}.png`：头版（报头与红印、标题、照片、小字栏）、折边、切边（夹着两三张彩色广告单）与麻绳。方块在 `human/NewspaperStackBlock`：每捆折合 4 张纸，`taken`（`0..3`）记录最上捆已被抽走的张数；碰撞为一个 `10×(4n - taken)×10` 像素的盒子，各朝向相同；需要下方中心有支撑，支撑没了就掉落；最上捆完整（`taken=0`）且不足三捆时，手持报纸堆对着它使用加一捆；破坏或失去支撑时按剩余整捆掉落 `mcyukkuri:newspaper_stack`、已拆开的顶捆按剩余张数（`4 - taken`）掉落 `minecraft:paper`。流浪与野生个体的抽纸铺垫与盖身玩法见[功能说明](../../../docs/FEATURES.md#油库里的身份)。方块、物品模型、方块状态与战利品表都由 `build.py` 写出。
