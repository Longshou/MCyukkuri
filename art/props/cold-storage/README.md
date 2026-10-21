# 冷冻收容柜与观察手册

冷冻柜是原创的四抽屉保温柜，蓝灰金属外壳、浅色抽屉、雪花标记、黄铜个人凭记和背部散热管分别建模。模型使用 26 个静态方块，无骨架或动画，不依赖原版箱子贴图。造型为待确认的 Minecraft 适配版，尚未登记为用户确认标准。

制作范围与重建说明见[制作包](../../../standards/yukkuri-library/drafts/cold-storage/README.md)。制作源为 [`recipe.json`](../../../standards/yukkuri-library/drafts/cold-storage/recipe.json)，导出脚本为同目录的 [`build.py`](../../../standards/yukkuri-library/drafts/cold-storage/build.py)。源目录 `assembled/` 和本包 `selected/` 同步保存可编辑 Blockbench 模型、Java 方块模型与原创色板。游戏读取 `models/block/cold_storage.json` 和 `textures/block/cold_storage.png`，四个朝向通过方块状态旋转。源与输出指纹见 [`manifest.json`](manifest.json)。

执行 `python standards/yukkuri-library/drafts/cold-storage/build.py --check` 可只读核对导出；加 `--sheet` 在本机生成几何多角度预览 `.local/previews/cold-storage/model-sheet.png`，不入库。预览使用导出模型的实际方块和深度缓冲绘制，不能代替游戏客户端检查。

手册的三语文字、生存进度与配方解锁登记由本包 [`export_guide.py`](export_guide.py)维护。脚本只增量合并本包语言键；运行前遵守总语言表的协作写入约定，`--check` 不写文件。没有引入 Patchouli 或 Jade 依赖。

玩法、原生依据、身份接口与验证边界见[冷冻与手册记录](../../../docs/FREEZER_AND_NOTEBOOK.md)。
