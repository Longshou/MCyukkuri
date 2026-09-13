# 新目录内的原版魔理沙

2026-09-09 从 `E:/yukkuri/Eight景U-ModLab/prototypes/mc-marisa` 完整复制到 `E:/yukkuri/MCyukkuri/marisa-original`。原路径留作备份。用户选定本版本继续制作；本轮仅重绘面部眼睛、红嘴和腮红。

已比较新旧 Blockbench elements/outliner 完全相同，geometry 文件字节完全相同；贴图、内嵌贴图、预览和展示资源包同步重新生成。新神态未做独立子 agent 审核，旧审核为历史记录。

# 魔理沙 · Minecraft 模型初稿

2026-09-09，Windows；应用户要求制作在当前项目目录中。范围仅 `prototypes/mc-marisa/`，基底提交 `a2741835d2219d762ccf72b4c967ff980126f300`；现有工作区的八景开发改动未纳入本次产物。

这是可编辑的方块模型、像素贴图及 Java 版展示资源包，不是已完成的生物 MOD。没有接入 AI、实体注册、碰撞、声音或存档。八景试玩和原版文件未修改。

## 看模型

- `preview.png`：实际模型的四分之三视角离线渲染。
- `preview-sheet.png`：正面、四分之三、背面三视图。
- `preview.html`：双击打开，可拖动旋转、滚轮缩放、自动旋转；模型和贴图内嵌，不需要联网或服务端。此交互入口未通过浏览器实测。
- `marisa.bbmodel`：用 Blockbench 打开；Generic Model 格式，含内嵌贴图及 root/head/hair/bangs/hat/bow 分组。
- `marisa.geo.json`：Minecraft geometry 1.12 格式的骨骼、方块及每面 UV，供后续实体制作导入；单独放进 Java 游戏不会生成生物。
- `marisa.png`：128×128 像素贴图，最近邻采样。

16 模型单位 = 1 方块；底部 Y=0，正面 -Z。头宽约 0.83 方块，帽檐宽约 1.19 方块，总高约 1.20 方块。54 个长方体，648 个三角形（未剔除内部面）。

## 在 Minecraft Java 1.21 / 1.21.1 展示

1. 将 `marisa-java-1.21.1-resourcepack.zip` 放入该游戏实例的 `resourcepacks` 文件夹，在游戏中启用。
2. 在允许命令的测试世界执行：

```mcfunction
/give @s minecraft:paper[minecraft:custom_model_data=98001]
```

3. 拿在手中或放入物品展示框观察。也可在玩家附近召唤展示实体：

```mcfunction
/summon minecraft:item_display ~ ~ ~ {Tags:["marisa_preview"],item:{id:"minecraft:paper",count:1,components:{"minecraft:custom_model_data":98001}},item_display:"none",transformation:{translation:[0.0f,0.5f,0.0f],left_rotation:[0.0f,0.0f,0.0f,1.0f],scale:[1.0f,1.0f,1.0f],right_rotation:[0.0f,0.0f,0.0f,1.0f]}}
```

仅删除附近本预览的展示实体：

```mcfunction
/kill @e[type=minecraft:item_display,tag=marisa_preview,distance=..8]
```

资源包只将纸的 CustomModelData=98001 映射为模型，普通纸保留原外观。其他修改纸模型的资源包可能覆盖同一路径。**资源包按 1.21/1.21.1 的 pack_format=34 和旧式物品模型 overrides 制作；不能保证后续版本直接使用。未运行 Minecraft 实机验证。**

## 制作与证据

方块几何、像素贴图均为本次代码原创；参考项目已有 `assets/living-room/house-marisa.png` 的金发、黑帽和白蝴蝶结识别特征，但不复制其中像素，也不将家油的纵长身形用于本普通魔理沙。当前是 MC 方块化转译候选，未接入八景，不宣称与八景原生画风完全一致。

在项目根加载 `env.ps1` 后，运行 `python prototypes/mc-marisa/generate.py` 可重新生成交付文件。使用本机已有 Pillow、NumPy，无新增全局依赖。PNG 离线渲染直接读取与导出模型共用的几何和 UV，非概念效果图。

格式参考：[Blockbench bbmodel](https://blockbench.net/wiki/docs/bbmodel/)、[Minecraft geometry](https://learn.microsoft.com/en-us/minecraft/creator/reference/content/visualreference/geometry.v1.16.0?view=minecraft-bedrock-stable)、[Minecraft Java 1.21 技术变更](https://www.minecraft.net/de-de/article/minecraft-java-edition-1-21)。

验证范围记录于 `verification.json`：只记录本次静态资产检查；不将静态检查、离线渲染或历史八景 QA 称为 Minecraft 实机通过。

### 预览空白修复（2026-09-09）

用户反馈浏览器只显示背景和按钮。源码发现顶点／片元着色器 varying 默认精度不一致，可能导致 WebGL 链接失败；已将两端显式统一为 mediump。用户浏览器的实际错误日志尚未取得，因此不将该问题写为已实机确认的唯一原因。

预览现默认显示内嵌静态图，成功提交首帧后才隐藏；WebGL 不可用、着色器失败、贴图失败／超时、首帧绘制报错或上下文丢失会显示原因，不再静默留白。

`node prototypes/mc-marisa/verify-viewer.cjs` 使用模拟 DOM/WebGL 覆盖正常启动及六类失败分支；这是脚本控制流回归，不能替代浏览器 GPU 渲染验证。

### 当前预览修复：像素深度绘制

用户再次反馈画面裂开；没有取得该次截图及浏览器日志，不能确认原 WebGL 失败的唯一原因。当前 preview.html 已改为 Canvas 2D 软件栅格化：真实三角形逐像素插值 UV、逐像素深度遮挡、共面重叠使用稳定优先级，保留旋转与缩放。software-viewer.js 同时供页面和 Node 执行，替换了旧 WebGL 路径及旧模拟测试。

本次实际执行同一 JavaScript 绘制函数的正面、四分之三、背面、侧面渲染，检查覆盖面积、正面和四分之三脸部无透明裂缝，并人工查看 viewer-render-check.png。浏览器交互未实测；MC 实机仍未验证。软件渲染静止时不重复绘制，旋转流畅度取决于设备。

复测命令：加载根 env.ps1，用 Pillow 将 marisa.png 转 RGBA 原始字节写入 E 盘测试目录的 texture.rgba，然后运行 node prototypes/mc-marisa/verify-viewer.cjs <该测试目录>。输出四个 600×600 RGBA 帧。旧 WebGL 模拟测试记录仅为历史，不代表当前实现。

## 当前神态修订

2026-09-09：去掉环状眼白，缩小眼睛并改为暖棕黑、单块高光；红圆嘴改为小笑嘴，减淡眉毛与腮红。几何未改。已运行 Chrome 无头浏览器，检查画布出现、正侧视角、拖动和缩放，无页面脚本错误；截图 browser-preview.png。Minecraft 内仍未实测。
