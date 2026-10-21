# 方块帽船

当前魔理沙帽的制作候选。配方在 `recipe.json`；`build.py` 从现行制作源与运行网格派生 `worn-hat.bbmodel` 和 `boat-hat.bbmodel`，不写游戏运行资源。两份模型均使用可编辑长方体与原帽贴图。

上段帽冠以 Y=17.9564 为界压低至原高度的 55%，保留逐层收窄的方块折尖。帽顶降低约 1.50 模型单位，船态经过原有 0.75 倍压缩后减少约 1.12 单位的下探。水生佩戴比例仍为 1.5。

帽檐、白带、蝴蝶结、三层内腔与前踏台保留原尺寸；因此载物、乘坐、划桨、辫子抓沿、子油攀沿所依赖的接触表面不变。此结论只覆盖静态几何；后续采用时需重新导出船底深度等派生数据，并在原试玩场核对摘戴和船岸交接。

在仓库根目录使用带 NumPy、Pillow 的 Python 运行：

```sh
.local/venv/bin/python standards/yukkuri-library/drafts/block-hat-boat/build.py
```

离线预览输出至 `.local/previews/block-hat-boat/`。工作树可通过 `MCY_LOCAL` 指向主检出的 `.local`。`comparison.png` 展示现版与候选的同尺度对照，`current.png` 展示候选佩戴和乘坐比例。
