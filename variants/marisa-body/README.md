# 魔理沙 · 当前基础建模版本

用户已确认本版本作为后续基础建模起点。

以冻结的 `../marisa-gentle/` 憨厚表情版完整副本为起点，仅在本目录修改与生成。模型制作未修改基准、其他变体、共享工具、八景游戏或存档；用户选定后已同步更新项目根说明。

## 修改

- 删除 `lower_curve` 独立底部方块：54 → 53 个方块，636 个三角形。
- 移除脸贴图 `(0,27)–(47,31)` 的横向阴影带，恢复统一肤色；眼睛、眉毛、腮红、嘴巴与其余图集像素逐像素保持一致。
- 剩余所有方块统一沿 Y 下移 1 单位，不改变尺寸、UV 或相对位置；头发、帽子、蝴蝶结与表情不变。新底面 Y=0，最高点 Y=18.25，分组枢轴同步下移。
- 静态及浏览器预览同步调整取景和地面阴影位置；比较图使用同尺度、同视角、同地面高度。

## 文件

- [前后对比](comparison.png)：左为冻结版，右为去底层版；上正面，下四分之三视角。
- [交互预览](preview.html)：离线可用，拖动、滚轮、视角按钮与自动旋转。
- [Blockbench 模型](marisa.bbmodel)：内嵌贴图。
- [骨骼 geometry](marisa.geo.json) / [128×128 贴图](marisa.png)。
- [Java 1.21–1.21.1 展示资源包](marisa-java-1.21.1-resourcepack.zip)：完整目录亦在 `resourcepack/`，沿用纸物品 custom_model_data 98001 的展示配置。
- [三视图](preview-sheet.png) / [浏览器实拍](browser-preview.png) / [手机尺寸实拍](browser-mobile.png)。

## 实际验证

`verify.py` 通过：三个模型导出的方块数量为 53、UUID 唯一、各方块与冻结版只有 Y=-1 平移、底面 Y=0、删除 lower_curve、表情及非目标贴图区域字节不变、内嵌贴图与外部贴图一致、Java 模型几何及 UV 一致、ZIP CRC 与包内文件字节一致、离线 HTML 内嵌完整与 JavaScript 语法有效。记录为 `verification.json`。

`verify-viewer.cjs` 执行真实的软件光栅渲染器：正面、四分之三、背面、侧面全部通过覆盖率检查，正面与四分之三面部中部无裂缝。

`browser-check.cjs` 使用本机 Chrome 与现有 Playwright，在真实浏览器中加载离线 HTML，验证四个视角画面不同、拖动改变画面、复位恢复画面、滚轮缩放、自动旋转、390×844 手机尺寸正常渲染，并确认无页面脚本错误。记录为 `browser-verification.json`，截图为 `browser-*.png`。已人工查看前后对比、三视图与浏览器截图，确认底部横条和独立下巴层消失。

未进行 Minecraft 客户端加载、Blockbench 应用导入或生物行为验证。本交付仍为模型资产和展示包，不是实体 Mod；geometry JSON 也不能直接作为 Java 实体加载。此次保留现有方块风格，仅去掉指定底层，不代表完整复刻八景的圆弧体型。

## 重生成

在项目根目录执行（Python 需 NumPy / Pillow；Node 和 Chrome 已在本机可用）：

```powershell
python variants/marisa-body/generate.py
python variants/marisa-body/compare.py
python variants/marisa-body/verify.py
node variants/marisa-body/verify-viewer.cjs variants/marisa-body
node variants/marisa-body/browser-check.cjs
```

所有测试脚本、截图及中间数据都保存在本独占目录。`compare.py` 只读取冻结版模型与贴图生成对比，不运行冻结版生成器。
