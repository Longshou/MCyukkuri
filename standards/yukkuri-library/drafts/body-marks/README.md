# 身体纹样美术草稿

本目录保存身体标记与伤病外观的材质制作源。用户要求换用既有伤口版本后，擦伤、压伤、裂伤、局部灼伤和旧疤已选入游戏，运行效果待试玩；霉斑、脏污、足缘状态和大创腔仍为草稿。

## 设计

- 霉斑：低饱和灰绿、疏密不同的菌落、薄粉霜和零星散孢；同一落点逐步扩大，四档保留。
- 旧疤：浅米色细长愈合痕、极浅凹边、尖细末端；取消通用针脚图案。
- 擦伤与压伤：断续划痕、薄层破皮和低对比暗沉，保留面皮本色。
- 裂伤：普通裂口为细长破皮、浅色翻边、哑光豆沙。中枢外露另用大缺损与三维创腔，核心保持在身体中央，不能放进小裂口。
- 脏污：底缘浮尘、方向性擦蹭、少量泥粒；三档积累，不均匀铺圆斑。
- 烤足：沿足缘焦化，向上衰减，加入细碎干裂，取消重复烙印轮廓。

## 来源与边界

依据 [身体与馅料](../../../../wiki/hakkei-reference/lore/entities-sources.md)、[照护](../../../../wiki/hakkei-reference/world/health-care.md) 的面皮、馅料与霉变设定；脏污另观察 [EH27-001](../../../../wiki/art-references/ehentai/images/EH27/001.jpg) 的柔和局部污迹，出处与核读范围见 [EH27](../../../../wiki/art-references/ehentai/works/EH27.md)。色板、形状、疏密、覆盖量均为原创美术选择，不是 Wiki 给定数值；没有复制外部图片到贴图。

`export_wounds.py` 直接调用 `preview.py` 既有材质函数，离线烘焙透明贴图到 `art/source/body-wound-materials/` 和运行资源，来源及文件指纹随快照保存。局部灼伤取现有足缘焦化材质并裁成局部伤口范围。`client/WoundRenderer.java` 将贴图裁到真实伤口附近的皮面三角形，沿用伤口锚点、等级范围、变形和骨架权重；不在每帧计算程序材质。选用关系在 `art/runtime-sources.json`。

## 制作与预览

`recipe.json` 是色板、菌落阶段、随机种子与来源登记；`preview.py` 是可重复生成的表面材质配方。`cavity.cjs` 制作大创口、面皮断面、凹入豆沙壁和唯一中央核心。`surface.cjs` 用当前模型和位置适配生成带深度遮挡的表面坐标，投影、光照和透明度裁切沿用库内软件渲染器的约定；`preview.html` 是展示模板。

2026-10-18 用户指出中枢外露入口过小后，已移除小裂口内的 core 色块，新增 `.local/previews/body-marks/core-cavity.png`（整只比例、近看、破裂和侧视）。当前草稿开口约体宽 48%、体高 53%，为美术提案尺寸。伤口来源、落点、形状、深度、核心受击／外露条件、愈合与接入顺序见 [伤口生成方案](WOUND_GENERATION.md)。

```sh
python standards/yukkuri-library/drafts/body-marks/preview.py --node node
python standards/yukkuri-library/drafts/body-marks/export_wounds.py
python standards/yukkuri-library/drafts/body-marks/export_wounds.py --check
python standards/yukkuri-library/drafts/body-marks/export_wounds.py --preview --node node
python scripts/build_preview_hub.py
```

需要 Python（numpy、Pillow）、Node.js 和微软雅黑。仅离线渲染，不启动浏览器、不启动游戏、不执行 Java 编译。源材质预览写入 `.local/previews/body-marks/`：总览、局部、新旧图形对照、轻重阶段、受伤补充和页面。`export_wounds.py --preview` 直接采样当前导出的五张 PNG，写入 `.local/previews/body-marks-runtime/current.png` 和同目录页面。检查信息写入 `.local/reports/body-marks-preview.json`，中间缓冲写入 `.local/body-marks-work/`，均不提交。

新旧对照的旧栏是旧配色和图形规则的同位置复现，不是游戏截图。当前表面伤口已通过离线贴图接入现有游戏伤口位置与变形；预览不代替游戏中的透明排序、距离与动态表现验收。大创腔与其核心空间位置尚未接入。
