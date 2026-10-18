# 身体纹样美术草稿

2026-10-18：针对当天新增的身体标记与伤病外观，先做材质提案。`recipe.json` 登记为 `draft_pending_user_review`，未选入游戏。

## 设计

- 霉斑：低饱和灰绿、疏密不同的菌落、薄粉霜和零星散孢；同一落点逐步扩大，四档保留。
- 旧疤：浅米色细长愈合痕、极浅凹边、尖细末端；取消通用针脚图案。
- 擦伤与压伤：断续划痕、薄层破皮和低对比暗沉，保留面皮本色。
- 裂伤：普通裂口为细长破皮、浅色翻边、哑光豆沙。中枢外露另用大缺损与三维创腔，核心保持在身体中央，不能放进小裂口。
- 脏污：底缘浮尘、方向性擦蹭、少量泥粒；三档积累，不均匀铺圆斑。
- 烤足：沿足缘焦化，向上衰减，加入细碎干裂，取消重复烙印轮廓。

## 来源与边界

依据 [身体与馅料](../../../../wiki/hakkei-reference/lore/entities-sources.md)、[照护](../../../../wiki/hakkei-reference/world/health-care.md) 的面皮、馅料与霉变设定；脏污另观察 [EH27-001](../../../../wiki/art-references/ehentai/images/EH27/001.jpg) 的柔和局部污迹，出处与核读范围见 [EH27](../../../../wiki/art-references/ehentai/works/EH27.md)。色板、形状、疏密、覆盖量均为原创美术选择，不是 Wiki 给定数值；没有复制外部图片到贴图。

现有游戏标记由 `art/props/body-marks/` 和 `client/WoundRenderer.java` 维护。本草稿不替换这些文件或运行资源，因此 `art/runtime-sources.json` 保持原选用。

## 制作与预览

`recipe.json` 是色板、菌落阶段、随机种子与来源登记；`preview.py` 是可重复生成的表面材质配方。`cavity.cjs` 制作大创口、面皮断面、凹入豆沙壁和唯一中央核心。`surface.cjs` 用当前模型和位置适配生成带深度遮挡的表面坐标，投影、光照和透明度裁切沿用库内软件渲染器的约定；`preview.html` 是展示模板。

2026-10-18 用户指出中枢外露入口过小后，已移除小裂口内的 core 色块，新增 `.local/previews/body-marks/core-cavity.png`（整只比例、近看、破裂和侧视）。当前草稿开口约体宽 48%、体高 53%，为美术提案尺寸。伤口来源、落点、形状、深度、核心受击／外露条件、愈合与接入顺序见 [伤口生成方案](WOUND_GENERATION.md)。

```sh
python standards/yukkuri-library/drafts/body-marks/preview.py --node node
python scripts/build_preview_hub.py
```

需要 Python（numpy、Pillow）、Node.js 和微软雅黑。仅离线渲染，不启动浏览器、不启动游戏、不执行 Java 编译。预览写入 `.local/previews/body-marks/`：总览、局部、新旧图形对照、轻重阶段、受伤补充和页面。检查信息写入 `.local/reports/body-marks-preview.json`，中间缓冲写入 `.local/body-marks-work/`，均不提交。

新旧对照的旧栏是旧配色和图形规则的同位置复现，不是游戏截图。新稿先验证材质方向与静态创腔几何，尚未适配游戏的伤口位置、变形、阶段数值和渲染管线。柔和过渡与高频颗粒若选用，需再制作贴图／材质及远距离简化方案；不应把此处逐像素配方直接搬进每帧 Java 逻辑。
