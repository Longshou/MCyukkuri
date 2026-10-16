# 家居防护盒

`recipe.json` 是原创制作源：低矮金属盒、正面竖向过滤栅、侧面横栅与越过顶盖的封口纸条。红色封口标记表示盒体完整，不绘制电源、燃料槽或持续消耗品。

尺寸为 10 × 5 × 8 模型单位，游戏内边界为 x=3..13、y=0..5、z=4..12；每格 16 单位。运行模型由 Minecraft 原生方块模型绘制，无专用方块实体或动画。

金属、深色过滤层、白纸和红色封印分别引用原版铁块、灰色混凝土、白色混凝土和红色陶瓦的表面纹理。这些是具体材质；没有复用原版物品图标。原版纹理不复制进模组包。

导出：`python standards/yukkuri-library/ecology/house-guard/build.py`。只读核对加 `--check`。选用配方与来源清单导出至 `art/source/house-guard/`。

当前离线预览由 `house-nest/render_supplies.py` 生成至 `.local/previews/house-supplies/house-supplies.png`，通过本目录 `preview.py` 读取已导出的方块模型与本机 Minecraft 资源包中的原版材质。可用环境变量 `MCYUKKURI_VANILLA_JAR` 指定资源 JAR。只进行软件光栅化，不启动游戏、浏览器或编译。

防护范围与防护结果由服务器逻辑决定；模型与示意图不作为实机测试结果。当前造型待用户审阅。
