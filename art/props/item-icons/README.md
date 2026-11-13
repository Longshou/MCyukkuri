# 物品栏图标

玩具、设施、药品和照料用品在世界里是立体道具，物品栏里只显示本包的 16×16 像素图标。画法同小麦粉糊、油粮和鬼威惨工具：左上受光，每种材质一条色相偏移色阶，轮廓取本材质最暗一阶，无黑边、无抗锯齿、无半透明。药瓶共用一个瓶形、两种喷雾共用一个扳机瓶，靠瓶色、瓶盖和标签区分；超辣油粮沿用油粮袋。

制作源为 `standards/yukkuri-library/drafts/item-icons/draw.py`（复用 `operation-tools/icons.py` 的图标工具与色阶、`care-props/supplies.py` 的油粮袋），写入本目录的快照 PNG；`--check` 只读。`scripts/export_item_icons.py` 把快照写到 `textures/item/<ID>.png`，并写只用于物品栏的 `models/item/icon/<ID>.json`；物品自己的 `models/item/<ID>.json` 仍归各道具包。`client/ItemIcons` 让物品栏格子显示图标，放置、手持、叼在嘴里、掉落和展示框仍是立体道具。删掉某个图标时同时删快照、图标模型和贴图。

本机新旧对比：`python standards/yukkuri-library/drafts/item-icons/preview.py`（整栏改前／改后、1× 实际大小、逐件 8 倍）。饰物（帽子、软帽）的图标显示破损、剪断与徽章，保留立体。
